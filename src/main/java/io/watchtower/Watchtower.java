package io.watchtower;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.instrumentation.runtimemetrics.java8.RuntimeMetrics;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Thin Watchtower layer over the OpenTelemetry Java SDK: key header, endpoint default, wt.*-safe resource. */
public final class Watchtower {
  public static final String DEFAULT_ENDPOINT = "http://localhost:4318";

  private static volatile OpenTelemetrySdk current;
  private static volatile RuntimeMetrics runtime;
  private static volatile io.watchtower.profiling.WatchtowerProfiler profiler;
  private static volatile RemoteSampler sampler;

  private static boolean flag(Boolean set, String env) {
    return set != null ? set : "1".equals(trim(System.getenv(env)));
  }

  private static long parseLong(String v, long dflt) {
    try {
      long n = Long.parseLong(trim(v));
      return n > 0 ? n : dflt;
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private Watchtower() {}

  /** Builder options. Unset values fall back to WT_INGEST_KEY, WT_OTLP_ENDPOINT, WT_SERVICE, WT_ENVIRONMENT, WT_RELEASE, WT_SPOOL_DIR. */
  public static final class Options {
    String apiKey;
    String endpoint;
    String serviceName;
    String environment;
    String release;
    Map<String, String> resource = Map.of();
    SpanExporter exporter;
    LogRecordExporter logExporter;
    MetricExporter metricExporter;
    Boolean logs;
    Boolean metrics;
    String spoolDir;
    Boolean remoteSampling;
    Long samplingIntervalS;
    Boolean codeOrigin;

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
    /** Replace the metric exporter (tests). */
    public Options metricExporter(MetricExporter v) { metricExporter = v; return this; }
    /** Export OTLP logs; unset reads WATCHTOWER_LOGS (on unless {@code off}; OTEL_LOGS_EXPORTER=none also turns it off). */
    public Options logs(boolean v) { logs = v; return this; }
    /** Export OTLP metrics and JVM runtime metrics; unset reads WATCHTOWER_METRICS (as for logs, with OTEL_METRICS_EXPORTER). */
    public Options metrics(boolean v) { metrics = v; return this; }
    /** Spool failed span, log and metric batches to this directory (under {@code logs/} and {@code metrics/}) and resend them (WT_SPOOL_DIR); unset keeps no offline buffer. */
    public Options spoolDir(String v) { spoolDir = v; return this; }
    /** Apply the project's head sampling rules in the SDK, polled from /v1/sampling. Default off (WT_REMOTE_SAMPLING=1 turns it on): spans dropped here never reach the gateway, so its exact request counts see only what is kept. */
    public Options remoteSampling(boolean v) { remoteSampling = v; return this; }
    /** Seconds between sampling polls; default WT_SAMPLING_INTERVAL_S or 30. */
    public Options samplingIntervalS(long v) { samplingIntervalS = v; return this; }
    /** Stamp code.filepath, code.lineno, code.namespace and code.function on spans. Default off (WT_CODE_ORIGIN=1 turns it on). */
    public Options codeOrigin(boolean v) { codeOrigin = v; return this; }

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

  /** Explicit flag, else {@code env} (on unless off/false/0/no); {@code otelEnv=none} also turns it off. */
  static boolean switchOn(Boolean flag, String env, String otelEnv) {
    if (flag != null) return flag;
    if ("none".equalsIgnoreCase(trim(System.getenv(otelEnv)))) return false;
    String v = trim(System.getenv(env)).toLowerCase(Locale.ROOT);
    return !(v.equals("off") || v.equals("false") || v.equals("0") || v.equals("no"));
  }

  private static String trim(String s) {
    return s == null ? "" : s.trim();
  }

  /** OTLP/HTTP metric exporter: delta for counters and histograms unless OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE says otherwise. */
  static MetricExporter metricExporter(String base, String key) {
    String pref = trim(System.getenv("OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE")).toLowerCase(Locale.ROOT);
    AggregationTemporalitySelector sel = switch (pref) {
      case "cumulative" -> AggregationTemporalitySelector.alwaysCumulative();
      case "lowmemory" -> AggregationTemporalitySelector.lowMemory();
      default -> AggregationTemporalitySelector.deltaPreferred();
    };
    return OtlpHttpMetricExporter.builder().setEndpoint(base + "/v1/metrics")
        .addHeader("Authorization", authHeader(key)).setAggregationTemporalitySelector(sel).build();
  }

  /** OTEL_METRIC_EXPORT_INTERVAL in ms; 60 s when unset or not a positive number. */
  static long exportInterval() {
    try {
      long ms = Long.parseLong(trim(System.getenv("OTEL_METRIC_EXPORT_INTERVAL")));
      return ms > 0 ? ms : 60_000;
    } catch (NumberFormatException e) {
      return 60_000;
    }
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
    String spool = or(o.spoolDir, "WT_SPOOL_DIR", null);
    if (spool != null) {
      ex = new SpoolingSpanExporter(ex, java.nio.file.Path.of(spool), base + "/v1/traces",
          Map.of("Authorization", authHeader(key)), 500);
    }
    boolean logsOn = switchOn(o.logs, "WATCHTOWER_LOGS", "OTEL_LOGS_EXPORTER");
    LogRecordExporter lex = !logsOn ? null : o.logExporter != null ? o.logExporter
        : OtlpHttpLogRecordExporter.builder().setEndpoint(base + "/v1/logs")
            .addHeader("Authorization", authHeader(key)).build();
    if (spool != null && lex != null) {
      // log batches spool next to the span batches, in their own directory
      lex = new SpoolingLogRecordExporter(lex, java.nio.file.Path.of(spool, "logs"), base + "/v1/logs",
          Map.of("Authorization", authHeader(key)), 500);
    }
    AllocationCounter alloc = AllocationCounter.create();
    var tpb = SdkTracerProvider.builder().setResource(res);
    if (alloc != null) {
      tpb.addSpanProcessor(alloc); // before the batch processor, so the value exists at export
      ex = alloc.wrap(ex);
    }
    if (flag(o.codeOrigin, "WT_CODE_ORIGIN")) tpb.addSpanProcessor(new CodeOrigin());
    RemoteSampler rs = null;
    if (flag(o.remoteSampling, "WT_REMOTE_SAMPLING")) {
      long every = o.samplingIntervalS != null ? o.samplingIntervalS : parseLong(System.getenv("WT_SAMPLING_INTERVAL_S"), 30);
      rs = new RemoteSampler(base, key, or(o.serviceName, "WT_SERVICE", "app"), or(o.environment, "WT_ENVIRONMENT", null), every);
      tpb.setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.parentBased(rs));
    }
    RemoteSampler old = sampler;
    if (old != null) old.close(); // a re-init replaces the previous poller
    sampler = rs;
    SdkTracerProvider tp = tpb.addSpanProcessor(BatchSpanProcessor.builder(ex).build()).build();
    var lpb = SdkLoggerProvider.builder().setResource(res);
    if (lex != null) lpb.addLogRecordProcessor(BatchLogRecordProcessor.builder(lex).build());
    var mpb = SdkMeterProvider.builder().setResource(res);
    boolean metricsOn = switchOn(o.metrics, "WATCHTOWER_METRICS", "OTEL_METRICS_EXPORTER");
    if (metricsOn) {
      MetricExporter mex = o.metricExporter != null ? o.metricExporter : metricExporter(base, key);
      if (spool != null) {
        mex = new SpoolingMetricExporter(mex, java.nio.file.Path.of(spool, "metrics"), base + "/v1/metrics",
            Map.of("Authorization", authHeader(key)), 500);
      }
      mpb.registerMetricReader(PeriodicMetricReader.builder(mex).setInterval(Duration.ofMillis(exportInterval())).build());
    }
    RuntimeMetrics prev = runtime;
    if (prev != null) prev.close(); // a re-init replaces the previous SDK's observers
    current = OpenTelemetrySdk.builder().setTracerProvider(tp).setLoggerProvider(lpb.build())
        .setMeterProvider(mpb.build()).build();
    runtime = metricsOn ? RuntimeMetrics.create(current) : null; // GC, memory, threads, classes, CPU
    io.watchtower.profiling.WatchtowerProfiler oldProfiler = profiler;
    if (oldProfiler != null) oldProfiler.close();
    profiler = null;
    String prof = System.getenv("WT_PROFILING"); // "1" or "true": also set by the agent's lambda_instrument command
    if ("1".equals(prof) || "true".equals(prof)) {
      try {
        profiler = new io.watchtower.profiling.WatchtowerProfiler(key, base,
            or(o.serviceName, "WT_SERVICE", "app"), Map.of(), Duration.ofSeconds(15)).start();
      } catch (RuntimeException e) {
        profiler = null; // best effort: never break init
      }
    }
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
