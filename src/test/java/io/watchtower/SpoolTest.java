package io.watchtower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolTest {
  /** Fails while {@code down} is set, like an exporter with no network. */
  static final class Flaky implements SpanExporter {
    volatile boolean down = true;
    final InMemorySpanExporter mem = InMemorySpanExporter.create();

    @Override public CompletableResultCode export(Collection<SpanData> spans) {
      return down ? CompletableResultCode.ofFailure() : mem.export(spans);
    }
    @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
    @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
  }

  @Test
  void failedBatchesAreSpooledToDiskAndReplayedInOrder(@TempDir Path dir) throws Exception {
    List<String> got = Collections.synchronizedList(new ArrayList<>());
    // ephemeral port, kept bound for the whole test (never rebound: a freed port can be taken by another test); the collector is "offline" because the exporter is down, not the socket
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      AtomicBoolean online = new AtomicBoolean(false);
      srv.createContext("/v1/traces", x -> {
        if (!online.get()) { x.sendResponseHeaders(503, -1); x.close(); return; }
        got.add(x.getRequestHeaders().getFirst("Authorization") + " " + x.getRequestHeaders().getFirst("Content-Type"));
        x.sendResponseHeaders(200, -1);
        x.close();
      });
      srv.start();
      Flaky inner = new Flaky();
      String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/traces";
      SpoolingSpanExporter spool = new SpoolingSpanExporter(inner, dir, url, Map.of("Authorization", "Bearer k"), 2);
      OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(
          SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spool)).build()).build();
      var tracer = sdk.getTracer("t");
      for (String n : new String[] {"a", "b", "c"}) tracer.spanBuilder(n).startSpan().end();
      // drop-oldest: three failed batches, two kept
      assertEquals(2, spool.files().size());
      assertTrue(spool.files().stream().allMatch(p -> {
        try { return Files.size(p) > 0; } catch (java.io.IOException e) { return false; }
      }));
      online.set(true);
      inner.down = false;
      tracer.spanBuilder("d").startSpan().end();
      assertEquals(List.of("Bearer k application/x-protobuf", "Bearer k application/x-protobuf"), got);
      assertFalse(spool.files().iterator().hasNext());
      assertEquals("d", inner.mem.getFinishedSpanItems().get(0).getName());
    } finally {
      srv.stop(0);
    }
  }
}
