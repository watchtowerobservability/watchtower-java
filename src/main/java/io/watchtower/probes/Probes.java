package io.watchtower.probes;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Dynamic probes for running JVM code (PLT-044), with the safety model of the Python, Go and
 * browser agents: off unless {@link #start} is called and the JVM was started with a loopback
 * JDWP agent ({@code -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:PORT},
 * port in {@code WT_PROBES_JDWP_PORT}); read-only and bounded.
 *
 * <p>{@link Jdi} sets a breakpoint per live probe that suspends only the hitting thread, reads its
 * locals and {@code this} through JDI without invoking any method (field and array reads only,
 * depth at most 3), and resumes it. This class evaluates the condition subset ({@link Condition})
 * over those plain values, applies the per-minute rate limit, flattens them to
 * {@code code.snapshot.*} (8 KiB, 1 KiB per value) and emits {@code probe.snapshot}.
 *
 * <p>Kinds {@code span} and {@code span_decoration} are handled by {@link SpanProbes} on the JDI
 * thread; this class supplies their gate ({@link #allowed}) and tag rendering ({@link #tags}).
 */
public final class Probes {
  static final int VALUE_MAX = 1024;

  /** One live probe. */
  static final class Live {
    final Map<String, Object> def;
    final Predicate<Map<String, Object>> ok;
    final String file;
    final String className;
    final String method;
    final int line;
    long win;
    int n;

    @SuppressWarnings("unchecked")
    Live(Map<String, Object> def) {
      this.def = def;
      this.ok = Condition.compile((String) def.get("condition"));
      Map<String, Object> loc = (Map<String, Object>) def.getOrDefault("location", Map.of());
      String fn = String.valueOf(loc.getOrDefault("function", ""));
      int hash = fn.lastIndexOf('#');
      this.className = hash > 0 ? fn.substring(0, hash) : "";
      this.method = hash > 0 ? fn.substring(hash + 1) : fn;
      this.file = String.valueOf(loc.getOrDefault("file", ""));
      Object l = loc.get("line");
      this.line = l instanceof Number ? ((Number) l).intValue() : 0;
    }

    String id() {
      return String.valueOf(def.get("id"));
    }

    String kind() {
      Object k = def.get("kind");
      return k instanceof String ? (String) k : "snapshot";
    }

    int num(String k, int dflt, int max) {
      Object o = def.get(k);
      int v = o instanceof Number ? ((Number) o).intValue() : 0;
      return Math.min(v > 0 ? v : dflt, max);
    }
  }

  private final String endpoint;
  private final String key;
  private final String query;
  private final BiConsumer<String, Map<String, String>> emit;
  private final LongSupplier nowMs;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private volatile Map<String, Live> live = Map.of();
  private volatile Runnable onChange = () -> { };
  private String etag = "";

  public Probes(String endpoint, String key, String service, String env, String version,
      BiConsumer<String, Map<String, String>> emit, LongSupplier nowMs) {
    this.endpoint = endpoint.replaceAll("/+$", "");
    this.key = key;
    this.query = "service=" + enc(service) + "&env=" + enc(env) + "&version=" + enc(version)
        + "&kinds=log,snapshot,metric,span,span_decoration";
    this.emit = emit;
    this.nowMs = nowMs;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }

  Map<String, Live> live() {
    return live;
  }

  void onChange(Runnable r) {
    onChange = r;
  }

  /** Replaces the probe set; a probe whose condition is outside the subset is skipped. */
  @SuppressWarnings("unchecked")
  public void set(List<Object> defs) {
    Map<String, Live> next = new ConcurrentHashMap<>();
    for (Object d : defs) {
      try {
        Live l = new Live((Map<String, Object>) d);
        next.put(l.id(), l);
      } catch (RuntimeException e) {
        // a probe we cannot evaluate is skipped
      }
    }
    live = next;
    onChange.run();
  }

  /** Refreshes the probe set from {@code GET /v1/probes/active}; failures wait for the next poll. */
  @SuppressWarnings("unchecked")
  public void poll() {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + "/v1/probes/active?" + query))
          .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + key);
      if (!etag.isEmpty()) {
        b.header("If-None-Match", etag);
      }
      HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200 || r.body().length() > (4 << 20)) {
        return;
      }
      etag = r.headers().firstValue("ETag").orElse("");
      Object probes = ((Map<String, Object>) Json.parse(r.body())).getOrDefault("probes", List.of());
      set(probes instanceof List ? (List<Object>) probes : List.of());
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Handles one hit of probe id with plain values; never throws into the caller. */
  public void hit(String id, String function, String file, int line, Map<String, Object> vars) {
    Live p = live.get(id);
    try {
      if (p == null || !allowed(p, vars)) {
        return;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> cap = (Map<String, Object>) p.def.getOrDefault("capture", Map.of());
      Map<String, String> attrs = new LinkedHashMap<>();
      attrs.put("probe.id", id);
      attrs.put("code.function", function);
      attrs.put("code.filepath", file);
      attrs.put("code.lineno", Integer.toString(line));
      Object msg = p.def.get("message");
      if (msg != null && !msg.toString().isEmpty()) {
        attrs.put("probe.message", msg.toString());
      }
      int depth = Math.min(intOf(cap.get("locals_depth"), 3), 3);
      int max = Math.min(intOf(cap.get("max_bytes"), 8192), 8192);
      capture(vars, depth, max).forEach((k, v) -> attrs.put("code.snapshot." + k, v));
      emit.accept("probe.snapshot", attrs);
    } catch (RuntimeException e) {
      // the app must never fail because of a probe
    }
  }

  /** Not expired, inside the per-minute budget and the condition holds (counts the hit). */
  boolean allowed(Live p, Map<String, Object> vars) {
    long t = nowMs.getAsLong();
    Object exp = p.def.get("expires_at");
    if (exp instanceof Number && t >= ((Number) exp).doubleValue() * 1000) {
      return false;
    }
    synchronized (p) {
      long minute = t / 60_000;
      if (p.win != minute) {
        p.win = minute;
        p.n = 0;
      }
      if (p.n >= p.num("max_rate_per_min", 60, 60) || !p.ok.test(vars)) {
        return false;
      }
      p.n++;
      return true;
    }
  }

  private static final java.util.regex.Pattern TEMPLATE = java.util.regex.Pattern.compile("\\{([A-Za-z_][\\w.]*)\\}");

  /** Fills {@code {a.b}} from the plain values (missing names render empty), cut to 1 KiB. */
  public static String render(String template, Map<String, Object> vars) {
    String s = TEMPLATE.matcher(template).replaceAll(m -> {
      Object v = Condition.get(vars, m.group(1));
      return java.util.regex.Matcher.quoteReplacement(v == null ? "" : String.valueOf(v));
    });
    return s.length() > VALUE_MAX ? s.substring(0, VALUE_MAX) : s;
  }

  /** The span_decoration tags of a probe; wt.* names are never set. */
  @SuppressWarnings("unchecked")
  public static Map<String, String> tags(Map<String, Object> def, Map<String, Object> vars) {
    Map<String, String> out = new LinkedHashMap<>();
    Object cap = def.get("capture");
    Object tags = cap instanceof Map ? ((Map<String, Object>) cap).get("tags") : null;
    if (!(tags instanceof List)) {
      return out;
    }
    for (Object t : (List<Object>) tags) {
      if (!(t instanceof Map)) {
        continue;
      }
      String name = String.valueOf(((Map<String, Object>) t).getOrDefault("name", ""));
      Object value = ((Map<String, Object>) t).get("value");
      if (!name.isEmpty() && !name.startsWith("wt.")) {
        out.put(name, render(value == null ? "" : value.toString(), vars));
      }
    }
    return out;
  }

  private static int intOf(Object o, int dflt) {
    int v = o instanceof Number ? ((Number) o).intValue() : 0;
    return v > 0 ? v : dflt;
  }

  /** Flattens to dotted names: nesting past depth collapses, values cut to 1 KiB, total to maxBytes. */
  public static Map<String, String> capture(Map<String, Object> vars, int depth, int maxBytes) {
    Map<String, String> out = new LinkedHashMap<>();
    int[] used = {0};
    walk("", vars, 0, depth, maxBytes, used, out);
    return out;
  }

  @SuppressWarnings("unchecked")
  private static void walk(String prefix, Object v, int d, int depth, int max, int[] used, Map<String, String> out) {
    if (v instanceof Map) {
      if (d > depth) {
        put(prefix, "[depth limit]", max, used, out);
        return;
      }
      for (Map.Entry<String, Object> e : new TreeMap<>((Map<String, Object>) v).entrySet()) {
        walk(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), d + 1, depth, max, used, out);
      }
      return;
    }
    if (v instanceof List) {
      List<Object> l = (List<Object>) v;
      Map<String, Object> m = new LinkedHashMap<>();
      for (int i = 0; i < l.size(); i++) {
        m.put(Integer.toString(i), l.get(i));
      }
      walk(prefix, m, d, depth, max, used, out);
      return;
    }
    put(prefix, String.valueOf(v), max, used, out);
  }

  private static void put(String name, String val, int max, int[] used, Map<String, String> out) {
    String s = val.length() > VALUE_MAX ? val.substring(0, VALUE_MAX) : val;
    if (used[0] + name.length() + s.length() <= max) {
      used[0] += name.length() + s.length();
      out.put(name, s);
    }
  }

  /**
   * Starts polling (every interval seconds) and attaches to the JVM's own loopback JDWP port.
   * Returns null, and does nothing, when {@code WT_PROBES_JDWP_PORT} is unset.
   */
  public static Probes start(String endpoint, String key, String service, String env, String version,
      int intervalSeconds, BiConsumer<String, Map<String, String>> emit) {
    String port = System.getenv("WT_PROBES_JDWP_PORT");
    if (port == null || port.isBlank()) {
      return null;
    }
    Probes p = new Probes(endpoint, key, service, env, version, emit == null ? Probes::defaultEmit : emit,
        System::currentTimeMillis);
    Jdi.attach(p, Integer.parseInt(port.trim()));
    Thread poller = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        p.poll();
        try {
          Thread.sleep(Math.max(intervalSeconds, 5) * 1000L);
        } catch (InterruptedException e) {
          return;
        }
      }
    }, "watchtower-probes-poll");
    poller.setDaemon(true);
    poller.start();
    return p;
  }

  static void defaultEmit(String name, Map<String, String> attrs) {
    io.opentelemetry.api.common.AttributesBuilder b = io.opentelemetry.api.common.Attributes.builder();
    attrs.forEach(b::put);
    io.opentelemetry.api.GlobalOpenTelemetry.get().getLogsBridge().get("watchtower.probes")
        .logRecordBuilder().setBody(name).setAllAttributes(b.build()).emit();
  }
}
