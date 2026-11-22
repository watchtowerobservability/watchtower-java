package io.watchtower;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * IAST (opt-in): notices at runtime when request input reaches a sensitive sink and reports it to
 * {@code POST /v1/projects/{prj}/code-security/runtime}. Off until {@link #start} is called.
 *
 * <p>Register {@link #filter()} as a servlet filter; it records the request's parameter values,
 * path, query string and header values for the life of the request in a ThreadLocal. Call
 * {@link #checkSink(String, String)} from the code that runs a sink (JDBC wrapper, process
 * launcher, file open) with the sink text; a hit is queued with the first stack frame outside the
 * SDK and the active trace id, and flushed in the background. Each (type, location) is reported
 * once per process.
 *
 * <p>shortcut: taint is substring matching of raw input values (4 characters or more) in the sink
 * text, not byte-level propagation, and the caller wires {@code checkSink} into its own sinks (no
 * bytecode agent); input that is transformed before use is missed, and work handed to another
 * thread loses the request context. Upgrade: a java agent that instruments JDBC and process APIs.
 */
public final class Iast {
  private static final int MIN_LEN = 4;
  private static final int MAX_INPUTS = 256;
  private static final ThreadLocal<List<String>> INPUTS = new ThreadLocal<>();
  private static final Object LOCK = new Object();
  private static final List<String[]> QUEUE = new ArrayList<>();
  private static final Set<String> SEEN = new HashSet<>();
  private static volatile Options opts;
  private static ScheduledExecutorService timer;
  private static boolean scheduled;

  private Iast() {}

  /** Where and how findings are posted. {@code post} receives (url, json) and defaults to HTTP. */
  public record Options(
      String apiUrl, String apiKey, String project, String service, long flushMs,
      BiConsumer<String, String> post) {}

  public static void start(Options o) {
    synchronized (LOCK) {
      opts = o;
      QUEUE.clear();
      SEEN.clear();
      if (timer == null) {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "wt-iast");
          t.setDaemon(true);
          return t;
        });
      }
    }
  }

  public static void stop() {
    synchronized (LOCK) {
      opts = null;
      QUEUE.clear();
      SEEN.clear();
      scheduled = false;
    }
  }

  /** Servlet filter that marks the request's inputs as tainted for downstream sinks. */
  public static Filter filter() {
    return (ServletRequest req, ServletResponse res, FilterChain chain) -> {
      if (opts == null || !(req instanceof HttpServletRequest h)) {
        chain.doFilter(req, res);
        return;
      }
      List<String> in = new ArrayList<>();
      add(in, h.getRequestURI());
      add(in, h.getQueryString());
      for (Map.Entry<String, String[]> e : h.getParameterMap().entrySet()) {
        for (String v : e.getValue()) add(in, v);
      }
      Enumeration<String> names = h.getHeaderNames();
      while (names != null && names.hasMoreElements()) {
        add(in, h.getHeader(names.nextElement()));
      }
      INPUTS.set(in);
      try {
        chain.doFilter(req, res);
      } finally {
        INPUTS.remove();
      }
    };
  }

  /** Mark extra values (a parsed JSON body) as tainted for the current request. */
  public static void addInputs(String... values) {
    List<String> in = INPUTS.get();
    if (in != null) for (String v : values) add(in, v);
  }

  private static void add(List<String> in, String v) {
    if (v == null || v.length() < MIN_LEN || in.size() >= MAX_INPUTS) return;
    in.add(v);
    try {
      String d = URLDecoder.decode(v, StandardCharsets.UTF_8);
      if (!d.equals(v) && d.length() >= MIN_LEN) in.add(d);
    } catch (IllegalArgumentException ignored) {
      // malformed escape: the raw value is already kept
    }
  }

  /** Check a sink's text against the current request's inputs; queue a finding on a hit. */
  public static boolean checkSink(String vulnType, String text) {
    return checkSink(vulnType, text, "high");
  }

  public static boolean checkSink(String vulnType, String text, String severity) {
    Options o = opts;
    List<String> in = INPUTS.get();
    if (o == null || in == null || text == null) return false;
    boolean hit = false;
    for (String v : in) {
      if (text.contains(v)) {
        hit = true;
        break;
      }
    }
    if (!hit) return false;
    String loc = callerLocation();
    SpanContext sc = Span.current().getSpanContext();
    synchronized (LOCK) {
      if (!SEEN.add(vulnType + "|" + loc)) return true;
      QUEUE.add(new String[] {
        vulnType, sc.isValid() ? sc.getTraceId() : "", loc, severity,
        "Request input reaches " + vulnType.replace('_', ' ') + " sink"});
      if (!scheduled && timer != null) {
        scheduled = true;
        timer.schedule(Iast::flush, o.flushMs(), TimeUnit.MILLISECONDS);
      }
    }
    return true;
  }

  private static String callerLocation() {
    for (StackTraceElement f : new Throwable().getStackTrace()) {
      String c = f.getClassName();
      if (c.startsWith("io.watchtower.Iast") || c.startsWith("java.") || c.startsWith("jdk.")
          || c.startsWith("jakarta.servlet") || c.startsWith("org.apache.catalina")) continue;
      return f.getFileName() + ":" + f.getLineNumber();
    }
    return "";
  }

  /** Send queued findings now. Never throws; a failed send drops the batch (best effort). */
  public static void flush() {
    Options o;
    List<String[]> batch;
    synchronized (LOCK) {
      scheduled = false;
      o = opts;
      batch = new ArrayList<>(QUEUE);
      QUEUE.clear();
    }
    if (o == null || batch.isEmpty()) return;
    try {
      String url = o.apiUrl().replaceAll("/+$", "") + "/v1/projects/"
          + java.net.URLEncoder.encode(o.project(), StandardCharsets.UTF_8) + "/code-security/runtime";
      o.post().accept(url, json(o.service(), batch));
    } catch (RuntimeException ignored) {
      // IAST must never break the app
    }
  }

  static String json(String service, List<String[]> batch) {
    StringBuilder b = new StringBuilder("{\"service\":").append(q(service)).append(",\"findings\":[");
    for (int i = 0; i < batch.size(); i++) {
      String[] f = batch.get(i);
      if (i > 0) b.append(',');
      b.append("{\"vuln_type\":").append(q(f[0])).append(",\"trace_id\":").append(q(f[1]))
          .append(",\"code_location\":").append(q(f[2])).append(",\"severity\":").append(q(f[3]))
          .append(",\"title\":").append(q(f[4])).append('}');
    }
    return b.append("]}").toString();
  }

  private static String q(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') b.append('\\').append(c);
      else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
      else b.append(c);
    }
    return b.append('"').toString();
  }

  /** Default transport: POST with the API key as a bearer token. */
  public static BiConsumer<String, String> httpPost(String apiKey) {
    HttpClient c = HttpClient.newHttpClient();
    return (url, body) -> {
      try {
        c.send(HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.discarding());
      } catch (java.io.IOException | InterruptedException e) {
        throw new IllegalStateException(e);
      }
    };
  }
}
