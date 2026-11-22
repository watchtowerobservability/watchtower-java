package io.watchtower;

import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The offline buffer for metrics: as {@link SpoolingSpanExporter}, replayed to {@code /v1/metrics}.
 * Temporality and aggregation come from the inner exporter. A spooled delta batch is resent as is,
 * so no increment is lost while the endpoint is down.
 */
public final class SpoolingMetricExporter implements MetricExporter {
  private final MetricExporter inner;
  private final Spool spool;

  public SpoolingMetricExporter(MetricExporter inner, Path dir, String url, Map<String, String> headers, int maxFiles) {
    this.inner = inner;
    this.spool = new Spool(dir, url, headers, maxFiles);
  }

  List<Path> files() {
    return spool.files();
  }

  @Override
  public CompletableResultCode export(Collection<MetricData> metrics) {
    spool.replay();
    CompletableResultCode r = inner.export(metrics);
    r.whenComplete(() -> {
      if (r.isSuccess()) return;
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MetricsRequestMarshaler.create(metrics).writeBinaryTo(out);
        spool.write(out.toByteArray());
      } catch (IOException e) {
        // cannot encode: the batch is lost, as without a spool
      }
    });
    return r;
  }

  @Override
  public AggregationTemporality getAggregationTemporality(InstrumentType type) {
    return inner.getAggregationTemporality(type);
  }

  @Override
  public Aggregation getDefaultAggregation(InstrumentType type) {
    return inner.getDefaultAggregation(type);
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
