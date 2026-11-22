package io.watchtower;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import io.watchtower.probes.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Remote sampling (S5): polls GET /v1/sampling (ETag) and applies the project's head rules in the
 * process. The decision uses fnv64a of the trace id like the gateway, so every service agrees on a
 * trace. A failed poll keeps the last good config (initially: keep everything). Wrap it in
 * {@code Sampler.parentBased}. Spans dropped here never reach the gateway, so its exact request
 * counts see only what is kept.
 */
public final class RemoteSampler implements Sampler, AutoCloseable {
  private volatile Map<String, Object> cfg = Map.of();
  private volatile String etag = "";
  private final String endpoint, auth, service, env;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "wt-remote-sampling");
    t.setDaemon(true);
    return t;
  });

  public RemoteSampler(String endpoint, String key, String service, String env, long intervalS) {
    this.endpoint = endpoint.replaceAll("/+$", "");
    this.auth = Watchtower.authHeader(key);
    this.service = service;
    this.env = env == null ? "" : env;
    long every = Math.max(1, intervalS);
    pool.scheduleWithFixedDelay(this::poll, 0, every, TimeUnit.SECONDS);
  }

  /** One poll; any failure or a 304 keeps the current config. */
  @SuppressWarnings("unchecked")
  void poll() {
    try {
      String q = "service=" + URLEncoder.encode(service, StandardCharsets.UTF_8)
          + (env.isEmpty() ? "" : "&env=" + URLEncoder.encode(env, StandardCharsets.UTF_8));
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + "/v1/sampling?" + q))
          .timeout(Duration.ofSeconds(10)).header("Authorization", auth);
      if (!etag.isEmpty()) b.header("If-None-Match", etag);
      HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200 || r.body().length() > (1 << 20)) return;
      if (Json.parse(r.body()) instanceof Map<?, ?> m) {
        cfg = (Map<String, Object>) m;
        etag = r.headers().firstValue("ETag").orElse("");
      }
    } catch (Exception e) {
      // keep the last good config
    }
  }

  void setConfig(Map<String, Object> c) { cfg = c; }

  @Override public void close() { pool.shutdownNow(); }

  @Override public String getDescription() { return "WatchtowerRemoteSampler"; }

  /** fnv64a over the 16 trace-id bytes, as a fraction of 2^64. */
  static double traceFraction(String traceIdHex) {
    long h = 0xcbf29ce484222325L;
    for (int i = 0; i < 32; i += 2) {
      h = (h ^ Integer.parseInt(traceIdHex.substring(i, i + 2), 16)) * 0x100000001b3L;
    }
    return (h >>> 1) * 2.0 / 18446744073709551616.0 + ((h & 1) / 18446744073709551616.0);
  }

  private static double clamp(Object o) {
    return o instanceof Number n ? Math.min(1, Math.max(0, n.doubleValue())) : 1;
  }

  private static boolean glob(String g, String text) {
    String re = Pattern.quote(g).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
    return Pattern.compile(re, Pattern.DOTALL).matcher(text).matches();
  }

  /** Keep ratio for a root span (1 keeps); the first matching ratio or drop rule wins. */
  static double ratioFor(Map<String, Object> c, String service, String env, String name, Attributes attrs) {
    if ("PROBABILISTIC".equals(c.get("strategyType")) && c.get("probabilisticSampling") instanceof Map<?, ?> p) {
      return p.get("samplingRate") == null ? 1 : clamp(p.get("samplingRate"));
    }
    if (!(c.get("rules") instanceof List<?> rules)) return 1;
    for (Object ro : rules) {
      if (!(ro instanceof Map<?, ?> r) || !(r.get("match") instanceof Map<?, ?> m)) continue;
      if (Boolean.FALSE.equals(r.get("enabled")) || m.get("min_duration_ms") != null
          || (m.get("status") instanceof String s && !s.isEmpty())) continue; // decided at the gateway
      if (m.get("service") instanceof String s && !s.isEmpty() && !s.equals(service)) continue;
      if (m.get("env") instanceof String s && !s.isEmpty() && !s.equals(env)) continue;
      if (m.get("span_name_prefix") instanceof String s && !name.startsWith(s)) continue;
      if (m.get("span_name_glob") instanceof String s && !s.isEmpty() && !glob(s, name)) continue;
      if (m.get("span_name_regex") instanceof String s && !s.isEmpty()) {
        try {
          if (!Pattern.compile(s).matcher(name).find()) continue;
        } catch (PatternSyntaxException e) {
          continue;
        }
      }
      boolean ok = true;
      if (m.get("attrs") instanceof Map<?, ?> want) {
        for (var e : want.entrySet()) {
          boolean found = false;
          for (var a : attrs.asMap().entrySet()) {
            if (a.getKey().getKey().equals(e.getKey()) && String.valueOf(a.getValue()).equals(String.valueOf(e.getValue()))) {
              found = true;
              break;
            }
          }
          if (!found) { ok = false; break; }
        }
      }
      if (!ok) continue;
      if (r.get("action") instanceof Map<?, ?> a) {
        if ("drop".equals(a.get("kind"))) return 0;
        if ("ratio".equals(a.get("kind")) && a.get("ratio") != null) return clamp(a.get("ratio"));
      }
      return 1;
    }
    return 1;
  }

  @Override
  public SamplingResult shouldSample(Context parent, String traceId, String name, SpanKind kind,
      Attributes attributes, List<LinkData> links) {
    double r = ratioFor(cfg, service, env, name, attributes);
    return r >= 1 || (r > 0 && traceFraction(traceId) < r) ? SamplingResult.recordAndSample() : SamplingResult.drop();
  }
}
