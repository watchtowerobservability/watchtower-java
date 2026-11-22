package io.watchtower;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.watchtower.probes.Json;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * App and API Protection in-app WAF, the same behavior as the Node, Python and Go agents.
 *
 * <p>{@link #start} polls {@code GET /v1/appsec/ruleset} (an ingestion key with scope
 * {@code probes:read}, ETag, every {@code poll_s}): the mode ({@code monitor} or {@code block}),
 * the enabled pattern rules and the denylist. Register {@link #filter()} as a servlet filter (inside
 * the OpenTelemetry one so the server span is current): it inspects the path, query, User-Agent and
 * the other header values, percent-decoded once, against the rules the worker runs over stored
 * spans. A match is recorded on the current span ({@code appsec.event}, {@code appsec.rule_id},
 * {@code appsec.category}, {@code appsec.blocked}); in {@code block} mode, and for denylisted IPs or
 * users in either mode, the request gets 403 problem+json and the app never runs. Rules match
 * case-insensitively; one Java cannot compile is skipped, and one that runs past a step budget is
 * treated as a non-match. Nothing happens until {@link #start} or {@link #setRuleset} is called.
 *
 * <p>shortcut: trusts X-Forwarded-For for the client IP; run behind a proxy that overwrites it.
 */
public final class Appsec {
  /** The verdict on one request. */
  public record Decision(String action, String ruleId, String category, String match) {}

  private record Rule(String id, String category, Pattern re, List<String> fields) {}

  private record State(
      boolean block, List<Rule> rules, Map<String, Double> ip, Map<String, Double> user) {}

  private static final String PROBLEM =
      "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
          + "\"detail\":\"Request blocked by Watchtower App and API Protection\"}";
  private static final int STEP_BUDGET = 2_000_000;
  private static volatile State state = new State(false, List.of(), Map.of(), Map.of());
  private static ScheduledExecutorService timer;
  private static String etag = "";

  private Appsec() {}

  /** A CharSequence that gives up after a fixed number of reads, so a catastrophic pattern ends. */
  private static final class Budgeted implements CharSequence {
    private final CharSequence s;
    private int left = STEP_BUDGET;

    Budgeted(CharSequence s) {
      this.s = s;
    }

    @Override public int length() { return s.length(); }

    @Override public char charAt(int i) {
      if (--left < 0) throw new IllegalStateException("regex budget exhausted");
      return s.charAt(i);
    }

    @Override public CharSequence subSequence(int a, int b) { return s.subSequence(a, b); }

    @Override public String toString() { return s.toString(); }
  }

  private static Map<String, Double> expiry(Object entries) {
    Map<String, Double> out = new HashMap<>();
    if (entries instanceof List<?> l) {
      for (Object o : l) {
        if (o instanceof Map<?, ?> m && m.get("value") instanceof String v && !v.isEmpty()) {
          out.put(v, m.get("expires_at") instanceof Number n ? n.doubleValue() : Double.POSITIVE_INFINITY);
        }
      }
    }
    return out;
  }

  /** Replace the active ruleset (a map as the edge serves it; null clears it). */
  public static void setRuleset(Map<String, Object> r) {
    Map<String, Object> m = r == null ? Map.of() : r;
    List<Rule> rules = new ArrayList<>();
    if (m.get("rules") instanceof List<?> l) {
      for (Object o : l) {
        if (!(o instanceof Map<?, ?> rule) || !(rule.get("pattern") instanceof String p)) continue;
        try {
          List<String> fields = new ArrayList<>();
          if (rule.get("fields") instanceof List<?> f) for (Object x : f) fields.add(String.valueOf(x));
          rules.add(new Rule(String.valueOf(rule.get("id")),
              rule.get("category") == null ? "" : String.valueOf(rule.get("category")),
              Pattern.compile(p, Pattern.CASE_INSENSITIVE), fields));
        } catch (PatternSyntaxException e) {
          // not portable to Java: the worker still evaluates it
        }
      }
    }
    Map<?, ?> deny = m.get("denylist") instanceof Map<?, ?> d ? d : Map.of();
    state = new State("block".equals(m.get("mode")), List.copyOf(rules), expiry(deny.get("ip")), expiry(deny.get("user")));
  }

  public static boolean active() {
    State s = state;
    return !(s.rules.isEmpty() && s.ip.isEmpty() && s.user.isEmpty());
  }

  private static boolean listed(Map<String, Double> m, String v, double now) {
    return v != null && !v.isEmpty() && m.getOrDefault(v, 0.0) > now;
  }

  /** Whether {@code userId} is denylisted (call after authentication to refuse a listed user). */
  public static boolean userBlocked(String userId) {
    return listed(state.user, userId, System.currentTimeMillis() / 1000.0);
  }

  /** Judge one request: {@code path}, {@code query}, {@code user_agent}, {@code headers}, {@code body}, {@code ip}, {@code user}. */
  public static Decision inspect(Map<String, String> req, double nowSeconds) {
    State s = state;
    if (listed(s.ip, req.get("ip"), nowSeconds) || listed(s.user, req.get("user"), nowSeconds)) {
      return new Decision("block", "denylist", "abuse", null);
    }
    for (Rule r : s.rules) {
      for (String f : r.fields) {
        String v = req.get(f);
        if (v == null || v.isEmpty()) continue;
        try {
          var m = r.re.matcher(new Budgeted(v));
          if (m.find()) {
            String hit = m.group();
            return new Decision(s.block ? "block" : "monitor", r.id, r.category, hit.substring(0, Math.min(200, hit.length())));
          }
        } catch (IllegalStateException budget) {
          // a pattern that runs away on this input counts as no match
        }
      }
    }
    return new Decision("allow", null, null, null);
  }

  private static String decode(String s, boolean plusIsSpace) {
    if (s == null) return "";
    try {
      return URLDecoder.decode(plusIsSpace ? s : s.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException bad) {
      return s;
    }
  }

  /** The request fields the rules read. */
  public static Map<String, String> fromRequest(HttpServletRequest h, String user) {
    List<String> headers = new ArrayList<>();
    Enumeration<String> names = h.getHeaderNames();
    for (String n : names == null ? Collections.<String>emptyList() : Collections.list(names)) {
      if (!"user-agent".equalsIgnoreCase(n)) headers.add(h.getHeader(n));
    }
    String xff = h.getHeader("X-Forwarded-For");
    String ip = xff != null && !xff.isBlank() ? xff.split(",")[0].trim()
        : h.getHeader("X-Real-IP") != null ? h.getHeader("X-Real-IP") : h.getRemoteAddr();
    Map<String, String> req = new HashMap<>();
    req.put("path", decode(h.getRequestURI(), false));
    req.put("query", decode(h.getQueryString(), true));
    req.put("user_agent", h.getHeader("User-Agent") == null ? "" : h.getHeader("User-Agent"));
    req.put("headers", String.join("\n", headers));
    req.put("ip", ip == null ? "" : ip);
    if (user != null) req.put("user", user);
    return req;
  }

  /** Record a decision on the current span. */
  public static void annotate(Decision d, String ip) {
    Span span = Span.current();
    if (!span.isRecording()) return;
    if (ip != null && !ip.isEmpty()) span.setAttribute("appsec.client_ip", ip);
    if ("allow".equals(d.action)) return;
    span.setAttribute(AttributeKey.booleanKey("appsec.event"), true);
    span.setAttribute("appsec.rule_id", d.ruleId == null ? "" : d.ruleId);
    span.setAttribute("appsec.category", d.category == null ? "" : d.category);
    span.setAttribute(AttributeKey.booleanKey("appsec.blocked"), "block".equals(d.action));
  }

  /** Mark the current request as a login attempt; failed ones feed the account-takeover rules. */
  public static void trackLogin(String login, boolean success) {
    Span span = Span.current();
    span.setAttribute("appsec.login", login);
    span.setAttribute(AttributeKey.booleanKey("appsec.login.success"), success);
    if (success) span.setAttribute(AttributeKey.booleanKey("appsec.auth"), true);
  }

  /** Servlet filter; {@code user} names the caller from the request when known (may be null). */
  public static Filter filter(java.util.function.Function<HttpServletRequest, String> user) {
    return (ServletRequest req, ServletResponse res, FilterChain chain) -> {
      if (!active() || !(req instanceof HttpServletRequest h) || !(res instanceof HttpServletResponse out)) {
        chain.doFilter(req, res);
        return;
      }
      Map<String, String> fields = fromRequest(h, user == null ? null : user.apply(h));
      Decision d = inspect(fields, System.currentTimeMillis() / 1000.0);
      annotate(d, fields.get("ip"));
      if ("block".equals(d.action)) {
        out.setStatus(403);
        out.setContentType("application/problem+json");
        out.getWriter().write(PROBLEM);
        return;
      }
      chain.doFilter(req, res);
    };
  }

  public static Filter filter() {
    return filter(null);
  }

  /** One poll; false when the ruleset is unchanged or the fetch failed. */
  @SuppressWarnings("unchecked")
  static synchronized boolean poll(String apiUrl, String key, String service) {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl.replaceAll("/+$", "")
              + "/v1/appsec/ruleset?service=" + URLEncoder.encode(service, StandardCharsets.UTF_8)))
          .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + key);
      if (!etag.isEmpty()) b.header("If-None-Match", etag);
      HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
          .send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200 || res.body().length() > (4 << 20)) return false;
      etag = res.headers().firstValue("ETag").orElse("");
      setRuleset((Map<String, Object>) Json.parse(res.body()));
      return true;
    } catch (java.io.IOException | RuntimeException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Fetch the ruleset now and keep polling every 30 seconds in a daemon thread. */
  public static synchronized void start(String apiUrl, String key, String service) {
    stop();
    poll(apiUrl, key, service);
    timer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "wt-appsec");
      t.setDaemon(true);
      return t;
    });
    timer.scheduleWithFixedDelay(() -> poll(apiUrl, key, service), 30, 30, TimeUnit.SECONDS);
  }

  public static synchronized void stop() {
    if (timer != null) timer.shutdownNow();
    timer = null;
    etag = "";
    setRuleset(null);
  }
}
