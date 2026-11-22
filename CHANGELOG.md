# Changelog

## 0.1.1

- Sync with the monorepo: log code location, in-app WAF, remote sampling and code origin.

- Metrics: the OTel metrics SDK with a periodic OTLP reader (delta temporality for counters and histograms by default), JVM runtime metrics from `opentelemetry-runtime-telemetry-java8`, and `SpoolingMetricExporter` under `WT_SPOOL_DIR/metrics`. `WATCHTOWER_LOGS` / `WATCHTOWER_METRICS` (or `Options.logs` / `Options.metrics`) switch the two signals, default on.

- Offline file spool: `Options.spoolDir` / `WT_SPOOL_DIR` writes failed span batches as OTLP protobuf and resends them oldest first (`SpoolingSpanExporter`, at most 500 files). Log batches spool the same way under `logs/` (`SpoolingLogRecordExporter`) and replay to `/v1/logs`.

## 0.1.0

- OpenTelemetry Java SDK distribution: `Authorization: Bearer` key, endpoint default, release and environment resource attributes, `wt.*`-safe resource.
- `Watchtower.captureException` (linked to the active span) and `Watchtower.installUncaughtHandler`.
- Logs to `/v1/logs` through `LogbackAppender` and `Log4j2Appender`.
- Spring Boot auto-configuration (`watchtower.*` properties) with MVC exception capture.
- The Gradle and Maven plugins that write the release name and upload ProGuard/R8 mappings are separate packages.
