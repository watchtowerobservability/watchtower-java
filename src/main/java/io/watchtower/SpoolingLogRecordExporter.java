package io.watchtower;

import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** The offline buffer for log records: as {@link SpoolingSpanExporter}, replayed to {@code /v1/logs}. */
public final class SpoolingLogRecordExporter implements LogRecordExporter {
  private final LogRecordExporter inner;
  private final Spool spool;

  public SpoolingLogRecordExporter(LogRecordExporter inner, Path dir, String url, Map<String, String> headers, int maxFiles) {
    this.inner = inner;
    this.spool = new Spool(dir, url, headers, maxFiles);
  }

  List<Path> files() {
    return spool.files();
  }

  @Override
  public CompletableResultCode export(Collection<LogRecordData> logs) {
    spool.replay();
    CompletableResultCode r = inner.export(logs);
    r.whenComplete(() -> {
      if (r.isSuccess()) return;
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LogsRequestMarshaler.create(logs).writeBinaryTo(out);
        spool.write(out.toByteArray());
      } catch (IOException e) {
        // cannot encode: the batch is lost, as without a spool
      }
    });
    return r;
  }

  @Override
  public CompletableResultCode flush() {
    return inner.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return inner.shutdown();
  }
}
