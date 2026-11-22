package io.watchtower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogSpoolTest {
  static final class Flaky implements LogRecordExporter {
    volatile boolean down = true;

    @Override public CompletableResultCode export(Collection<LogRecordData> logs) {
      return down ? CompletableResultCode.ofFailure() : CompletableResultCode.ofSuccess();
    }
    @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
    @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
  }

  @Test
  void failedLogBatchesAreSpooledCappedAndReplayedToTheLogsPath(@TempDir Path dir) throws Exception {
    List<String> got = Collections.synchronizedList(new ArrayList<>());
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = srv.getAddress().getPort();
    srv.stop(0);
    Flaky inner = new Flaky();
    SpoolingLogRecordExporter spool = new SpoolingLogRecordExporter(inner, dir,
        "http://127.0.0.1:" + port + "/v1/logs", Map.of("Authorization", "Bearer k"), 2);
    var logger = SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(spool))
        .build().get("t");
    for (String b : new String[] {"a", "b", "c"}) logger.logRecordBuilder().setBody(b).emit();
    assertEquals(2, spool.files().size(), "drop-oldest keeps two batches");
    srv = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    try {
      srv.createContext("/v1/logs", x -> {
        got.add(x.getRequestHeaders().getFirst("Content-Type"));
        x.sendResponseHeaders(200, -1);
        x.close();
      });
      srv.start();
      inner.down = false;
      logger.logRecordBuilder().setBody("d").emit();
      assertEquals(List.of("application/x-protobuf", "application/x-protobuf"), got);
      assertTrue(spool.files().isEmpty());
    } finally {
      srv.stop(0);
    }
  }
}
