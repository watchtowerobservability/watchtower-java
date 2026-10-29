package io.watchtower;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Thin Watchtower layer over the OpenTelemetry Java SDK: key header, endpoint default, wt.*-safe resource. */
public final class Watchtower {
  public static final String DEFAULT_ENDPOINT = "http://localhost:4318";

  private static volatile OpenTelemetrySdk current;

  private Watchtower() {}

  /** Builder options. Unset values fall back to WT_INGEST_KEY, WT_OTLP_ENDPOINT, WT_SERVICE, WT_ENVIRONMENT, WT_RELEASE. */
  public static final class Options {
    String apiKey;
    String endpoint;
    String serviceName;
    String environment;
    String release;
    Map<String, String> resource = Map.of();
    SpanExporter exporter;
    LogRecordExporter logExporter;

    public Options apiKey(String v) { apiKey = v; return this; }
    public Options endpoint(String v) { endpoint = v; return this; }
    public Options serviceName(String v) { serviceName = v; return this; }
    public Options environment(String v) { environment = v; return this; }
    public Options release(String v) { release = v; return this; }
    public Options resource(Map<String, String> v) { resource = v; return this; }
    /** Replace the span exporter (tests). */
    public Options exporter(SpanExporter v) { exporter = v; return this; }
    /** Replace the log exporter (tests). */
    public Options logExporter(LogRecordExporter v) { logExporter = v; return this; }

    /** Never prints the key. */
    @Override public String toString() {
      return "Options{apiKey=" + (apiKey == null ? "null" : "***") + ", endpoint=" + endpoint
          + ", serviceName=" + serviceName + "}";
    }
  }

  public static String endpoint(String configured) {
    String e = configured != null ? configured : System.getenv("WT_OTLP_ENDPOINT");
    return (e == null || e.isEmpty() ? DEFAULT_ENDPOINT : e).replaceAll("/+$", "");
  }

  public static String authHeader(String apiKey) {
    return "Bearer " + apiKey;
  }

  private static String or(String v, String env, String dflt) {
    if (v != null && !v.isEmpty()) return v;
    String e = System.getenv(env);
    return e == null || e.isEmpty() ? dflt : e;
  }

  /** Release written by the Gradle or Maven plugin into META-INF/watchtower.properties, or null. */
  static String bundledRelease() {
    try (InputStream in = Watchtower.class.getClassLoader().getResourceAsStream("META-INF/watchtower.properties")) {
      if (in == null) return null;
      Properties p = new Properties();
      p.load(in);
      return p.getProperty("release");
    } catch (IOException e) {
      return null;
    }
  }

  /** Drops caller-supplied wt.* keys: tenant identity comes only from the key. */
  public static Attributes safeResource(String serviceName, Map<String, String> attrs) {
    return safeResource(serviceName, null, null, attrs);
  }

  public static Attributes safeResource(String serviceName, String environment, String release,
      Map<String, String> attrs) {
    AttributesBuilder b = Attributes.builder().put(AttributeKey.stringKey("service.name"), serviceName);
    if (environment != null) b.put(AttributeKey.stringKey("deployment.environment.name"), environment);
    if (release != null) b.put(AttributeKey.stringKey("service.version"), release);
    attrs.forEach((k, v) -> { if (!k.startsWith("wt.")) b.put(AttributeKey.stringKey(k), v); });
    return b.build();
  }

  /** The SDK from the last {@link #init}, or null. */
  public static OpenTelemetrySdk current() { return current; }

  public static OpenTelemetrySdk init(Options o) {
    String key = or(o.apiKey, "WT_INGEST_KEY", null);
    if (key == null) throw new IllegalArgumentException("apiKey is required");
    Resource res = Resource.create(safeResource(or(o.serviceName, "WT_SERVICE", "app"),
        or(o.environment, "WT_ENVIRONMENT", null), or(o.release, "WT_RELEASE", bundledRelease()), o.resource));
    String base = endpoint(o.endpoint);
    SpanExporter ex = o.exporter != null ? o.exporter
        : OtlpHttpSpanExporter.builder().setEndpoint(base + "/v1/traces")
            .addHeader("Authorization", authHeader(key)).build();
    LogRecordExporter lex = o.logExporter != null ? o.logExporter
        : OtlpHttpLogRecordExporter.builder().setEndpoint(base + "/v1/logs")
            .addHeader("Authorization", authHeader(key)).build();
    SdkTracerProvider tp = SdkTracerProvider.builder().setResource(res)
        .addSpanProcessor(BatchSpanProcessor.builder(ex).build()).build();
    SdkLoggerProvider lp = SdkLoggerProvider.builder().setResource(res)
        .addLogRecordProcessor(BatchLogRecordProcessor.builder(lex).build()).build();
    current = OpenTelemetrySdk.builder().setTracerProvider(tp).setLoggerProvider(lp).build();
    return current;
  }

  /**
   * Record a handled or unhandled error. It lands on the active span when there is one (so the error
   * links to its trace), otherwise on a new "exception" span. Returns the trace id, or null if not initialised.
   */
  public static String captureException(Throwable t) {
    OpenTelemetrySdk sdk = current;
    if (sdk == null || t == null) return null;
    Span span = Span.current();
    boolean own = !span.getSpanContext().isValid();
    if (own) span = sdk.getTracer("io.watchtower").spanBuilder("exception").startSpan();
    span.recordException(t);
    span.setStatus(StatusCode.ERROR, String.valueOf(t.getMessage()));
    String trace = span.getSpanContext().getTraceId();
    if (own) span.end();
    return trace;
  }

  /** Capture uncaught exceptions on any thread, then defer to the previous handler. */
  public static void installUncaughtHandler() {
    Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((th, e) -> {
      try {
        captureException(e);
        OpenTelemetrySdk sdk = current;
        if (sdk != null) sdk.getSdkTracerProvider().forceFlush().join(2, TimeUnit.SECONDS);
      } finally {
        if (prev != null) prev.uncaughtException(th, e);
      }
    });
  }
}
