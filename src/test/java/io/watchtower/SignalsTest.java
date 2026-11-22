package io.watchtower;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.watchtower.logging.LogbackAppender;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SignalsTest {
  record Req(String path, String auth, byte[] body) {}

  static HttpServer otlp(List<Req> got, int port) throws Exception {
    return otlp(got, port, new AtomicBoolean(true));
  }

  // While `online` is false the collector answers 503 and records nothing: an offline collector on a port that stays ours.
  static HttpServer otlp(List<Req> got, int port, AtomicBoolean online) throws Exception {
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    srv.createContext("/", x -> {
      if (!online.get()) { x.sendResponseHeaders(503, -1); x.close(); return; }
      got.add(new Req(x.getRequestURI().getPath(), x.getRequestHeaders().getFirst("Authorization"),
          x.getRequestBody().readAllBytes()));
      x.sendResponseHeaders(200, -1);
      x.close();
    });
    srv.start();
    return srv;
  }

  static byte[] bodies(List<Req> got, String path) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Req r : got) if (r.path().equals(path)) out.writeBytes(r.body());
    return out.toByteArray();
  }

  static boolean contains(byte[] hay, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= hay.length; i++) {
      for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
      return true;
    }
    return false;
  }

  @Test
  void logbackRecordsCarryTraceIdsAndMetricsReachTheEndpoint() throws Exception {
    List<Req> got = Collections.synchronizedList(new ArrayList<>());
    HttpServer srv = otlp(got, 0);
    try {
      OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("test-key-not-real")
          .endpoint("http://127.0.0.1:" + srv.getAddress().getPort()).exporter(InMemorySpanExporter.create())
          .logs(true).metrics(true));
      LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
      LogbackAppender app = new LogbackAppender();
      app.setContext(ctx);
      app.start();
      Logger log = ctx.getLogger("signals.test");
      log.addAppender(app);
      Span span = sdk.getTracer("t").spanBuilder("op").startSpan();
      try (Scope s = span.makeCurrent()) {
        log.info("hello from logback");
      } finally {
        span.end();
        log.detachAppender(app);
      }
      sdk.getMeter("t").counterBuilder("jobs.done").build().add(3);
      sdk.getSdkLoggerProvider().forceFlush().join(10, TimeUnit.SECONDS);
      sdk.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS);

      byte[] logs = bodies(got, "/v1/logs");
      assertTrue(contains(logs, "hello from logback".getBytes(UTF_8)));
      assertTrue(contains(logs, span.getSpanContext().getTraceIdBytes()), "log record carries the trace id");
      assertTrue(contains(logs, span.getSpanContext().getSpanIdBytes()), "log record carries the span id");
      assertTrue(contains(bodies(got, "/v1/metrics"), "jobs.done".getBytes(UTF_8)));
      assertTrue(got.stream().allMatch(r -> "Bearer test-key-not-real".equals(r.auth())));
    } finally {
      srv.stop(0);
    }
  }

  @Test
  void metricsDefaultToDeltaForCountersAndHistograms() {
    MetricExporter ex = Watchtower.metricExporter("http://127.0.0.1:1", "k");
    assertEquals(AggregationTemporality.DELTA, ex.getAggregationTemporality(InstrumentType.COUNTER));
    assertEquals(AggregationTemporality.DELTA, ex.getAggregationTemporality(InstrumentType.HISTOGRAM));
    assertEquals(AggregationTemporality.CUMULATIVE, ex.getAggregationTemporality(InstrumentType.UP_DOWN_COUNTER));
    assertFalse(Watchtower.switchOn(false, "WATCHTOWER_METRICS", "OTEL_METRICS_EXPORTER"));
  }

  static final class Flaky implements MetricExporter {
    volatile boolean down = true;

    @Override public CompletableResultCode export(Collection<MetricData> m) {
      return down ? CompletableResultCode.ofFailure() : CompletableResultCode.ofSuccess();
    }
    @Override public AggregationTemporality getAggregationTemporality(InstrumentType t) { return AggregationTemporality.DELTA; }
    @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
    @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
  }

  @Test
  void failedMetricBatchesAreSpooledAndReplayedToTheMetricsPath(@TempDir Path dir) throws Exception {
    List<Req> got = Collections.synchronizedList(new ArrayList<>());
    AtomicBoolean online = new AtomicBoolean(false);
    HttpServer srv = otlp(got, 0, online); // stays bound: rebinding a freed port races other tests
    int port = srv.getAddress().getPort();
    Flaky inner = new Flaky();
    SpoolingMetricExporter spool = new SpoolingMetricExporter(inner, dir, "http://127.0.0.1:" + port + "/v1/metrics",
        Map.of("Authorization", "Bearer k"), 500);
    SdkMeterProvider mp = SdkMeterProvider.builder().registerMetricReader(PeriodicMetricReader.create(spool)).build();
    var counter = mp.get("t").counterBuilder("offline").build();
    counter.add(1);
    mp.forceFlush().join(10, TimeUnit.SECONDS);
    assertEquals(1, spool.files().size());
    try {
      online.set(true);
      inner.down = false;
      counter.add(1);
      mp.forceFlush().join(10, TimeUnit.SECONDS);
      assertEquals(1, got.size());
      assertEquals("/v1/metrics", got.get(0).path());
      assertTrue(contains(got.get(0).body(), "offline".getBytes(UTF_8)));
      assertTrue(spool.files().isEmpty());
    } finally {
      srv.stop(0);
      mp.shutdown();
    }
  }
}
