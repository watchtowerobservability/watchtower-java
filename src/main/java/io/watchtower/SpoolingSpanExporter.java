package io.watchtower;

import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Offline buffer: a batch the inner exporter fails to send is written to {@code dir} as an OTLP
 * protobuf request and POSTed again, oldest first, before the next export. At most
 * {@code maxFiles} batches are kept (drop-oldest). Delivery stays at-least-once.
 */
public final class SpoolingSpanExporter implements SpanExporter {
  private final SpanExporter inner;
  private final Spool spool;

  public SpoolingSpanExporter(SpanExporter inner, Path dir, String url, Map<String, String> headers, int maxFiles) {
    this.inner = inner;
    this.spool = new Spool(dir, url, headers, maxFiles);
  }

  List<Path> files() {
    return spool.files();
  }

  void spool(Collection<SpanData> spans) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TraceRequestMarshaler.create(spans).writeBinaryTo(out);
      spool.write(out.toByteArray());
    } catch (IOException e) {
      // cannot encode: the batch is lost, as without a spool
    }
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    spool.replay();
    CompletableResultCode r = inner.export(spans);
    r.whenComplete(() -> {
      if (!r.isSuccess()) spool(spans);
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
