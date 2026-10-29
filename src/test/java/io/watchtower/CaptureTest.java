package io.watchtower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CaptureTest {
  private static SpanData flushOne(OpenTelemetrySdk sdk, InMemorySpanExporter mem) {
    sdk.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
    return mem.getFinishedSpanItems().get(0);
  }

  @Test
  void captureOutsideSpanMakesExceptionSpan() {
    InMemorySpanExporter mem = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("k").exporter(mem));
    String trace = Watchtower.captureException(new IllegalStateException("boom"));
    SpanData s = flushOne(sdk, mem);
    assertEquals(trace, s.getTraceId());
    assertEquals("exception", s.getName());
    assertEquals("exception", s.getEvents().get(0).getName());
  }

  @Test
  void captureInsideSpanLinksToIt() {
    InMemorySpanExporter mem = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("k").exporter(mem));
    Span span = sdk.getTracer("t").spanBuilder("req").startSpan();
    String trace;
    try (Scope ignored = span.makeCurrent()) {
      trace = Watchtower.captureException(new RuntimeException("x"));
    }
    span.end();
    SpanData s = flushOne(sdk, mem);
    assertEquals("req", s.getName());
    assertEquals(trace, s.getTraceId());
    assertEquals(1, s.getEvents().size());
  }

  @Test
  void releaseAndEnvironmentOnResource() {
    var a = Watchtower.safeResource("svc", "prod", "1.2.3", java.util.Map.of("wt.x", "1"));
    assertEquals("prod", a.get(AttributeKey.stringKey("deployment.environment.name")));
    assertEquals("1.2.3", a.get(AttributeKey.stringKey("service.version")));
    assertNull(a.get(AttributeKey.stringKey("wt.x")));
  }

  @Test
  void optionsToStringMasksKey() {
    assertFalse(new Watchtower.Options().apiKey("secret-key-value").toString().contains("secret"));
  }

  @Test
  void uncaughtHandlerCapturesAndChains() throws Exception {
    InMemorySpanExporter mem = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("k").exporter(mem));
    Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
    var chained = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    try {
      Thread.setDefaultUncaughtExceptionHandler((t, e) -> chained.set(e));
      Watchtower.installUncaughtHandler();
      Thread t = new Thread(() -> { throw new IllegalArgumentException("late"); });
      t.start();
      t.join();
      assertNotNull(chained.get());
      assertTrue(flushOne(sdk, mem).getEvents().size() == 1);
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(old);
    }
  }
}
