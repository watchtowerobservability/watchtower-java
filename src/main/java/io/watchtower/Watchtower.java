package io.watchtower;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Map;

/** Thin Watchtower layer over the OpenTelemetry Java SDK: key header, endpoint default, wt.*-safe resource. */
public final class Watchtower {
  public static final String DEFAULT_ENDPOINT = "http://localhost:4318";

  private Watchtower() {}

  /** Builder options. */
  public static final class Options {
    String apiKey;
    String endpoint;
    String serviceName = "app";
    Map<String, String> resource = Map.of();
    SpanExporter exporter;

    public Options apiKey(String v) { apiKey = v; return this; }
    public Options endpoint(String v) { endpoint = v; return this; }
    public Options serviceName(String v) { serviceName = v; return this; }
    public Options resource(Map<String, String> v) { resource = v; return this; }
    /** Replace the exporter (tests). */
    public Options exporter(SpanExporter v) { exporter = v; return this; }
  }

  public static String endpoint(String configured) {
    String e = configured != null ? configured : System.getenv("WT_OTLP_ENDPOINT");
    return (e == null || e.isEmpty() ? DEFAULT_ENDPOINT : e).replaceAll("/+$", "");
  }

  public static String authHeader(String apiKey) {
    return "Bearer " + apiKey;
  }

  /** Drops caller-supplied wt.* keys: tenant identity comes only from the key. */
  public static Attributes safeResource(String serviceName, Map<String, String> attrs) {
    AttributesBuilder b = Attributes.builder().put(AttributeKey.stringKey("service.name"), serviceName);
    attrs.forEach((k, v) -> { if (!k.startsWith("wt.")) b.put(AttributeKey.stringKey(k), v); });
    return b.build();
  }

  public static OpenTelemetrySdk init(Options o) {
    if (o.apiKey == null || o.apiKey.isEmpty()) throw new IllegalArgumentException("apiKey is required");
    SpanExporter ex = o.exporter != null ? o.exporter
        : OtlpHttpSpanExporter.builder()
            .setEndpoint(endpoint(o.endpoint) + "/v1/traces")
            .addHeader("Authorization", authHeader(o.apiKey))
            .build();
    SdkTracerProvider tp = SdkTracerProvider.builder()
        .setResource(Resource.create(safeResource(o.serviceName, o.resource)))
        .addSpanProcessor(BatchSpanProcessor.builder(ex).build())
        .build();
    return OpenTelemetrySdk.builder().setTracerProvider(tp).build();
  }
}
