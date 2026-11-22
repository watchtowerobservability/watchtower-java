package io.watchtower;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AllocationCounterTest {
  static volatile Object sink; // keeps the test allocation from being optimised away

  @Test
  void serverSpansCarryTheirThreadsAllocation() {
    InMemorySpanExporter mem = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("k").exporter(mem));
    var tracer = sdk.getTracer("t");
    var server = tracer.spanBuilder("GET /orders").setSpanKind(SpanKind.SERVER).startSpan();
    byte[][] keep = new byte[64][];
    for (int i = 0; i < keep.length; i++) keep[i] = new byte[16 * 1024];
    sink = keep;
    server.end();
    tracer.spanBuilder("internal").startSpan().end();
    sdk.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
    SpanData s = mem.getFinishedSpanItems().stream().filter(x -> x.getName().equals("GET /orders")).findFirst().orElseThrow();
    Long bytes = s.getAttributes().get(AllocationCounter.KEY);
    assertNotNull(bytes, "the JVM under test supports per-thread allocation counting");
    assertTrue(bytes >= keep.length * 16 * 1024L, "counted " + bytes);
    SpanData other = mem.getFinishedSpanItems().stream().filter(x -> x.getName().equals("internal")).findFirst().orElseThrow();
    assertNull(other.getAttributes().get(AllocationCounter.KEY));
  }
}
