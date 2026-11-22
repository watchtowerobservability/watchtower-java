# watchtower-sdk (Java)

[![CI](https://github.com/watchtowerobservability/watchtower-java/actions/workflows/ci.yml/badge.svg)](https://github.com/watchtowerobservability/watchtower-java/actions/workflows/ci.yml)

## Install

GitHub Packages Maven repository `https://maven.pkg.github.com/watchtowerobservability/watchtower-java` (needs a token with `read:packages`), artifact `io.github.watchtowerobservability:watchtower-sdk`. The attested jar is also on each release.

Thin layer over the OpenTelemetry Java SDK: `Authorization: Bearer` header, endpoint default,
release and environment resource attributes, `wt.*`-safe resource, error capture linked to the active trace,
log appenders, OTLP metrics with JVM runtime metrics, an offline spool and a Spring Boot starter. Java 17+.
Redaction hook and prompt/score helpers are not in the Java package yet; use the Python or Node SDK for those.

Metrics: `Watchtower.init(...).getMeter("app")` records to `/v1/metrics` through a periodic reader
(every `OTEL_METRIC_EXPORT_INTERVAL` ms, default 60000). Counters and histograms use delta temporality
unless `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE` is `cumulative` or `lowmemory`. JVM runtime
metrics (GC, memory pools, threads, classes, CPU) come from the official `opentelemetry-runtime-telemetry-java8`.

## Quick start

```java
OpenTelemetrySdk otel = Watchtower.init(new Watchtower.Options().apiKey(System.getenv("WT_INGEST_KEY")));
Watchtower.installUncaughtHandler();          // uncaught exceptions on any thread
Watchtower.captureException(exception);       // handled errors, tied to the current span's trace
```

## Integrations

| Integration | Use |
|---|---|
| Spring Boot 3 | Add the artifact; auto-configured from `watchtower.*` (below). MVC exceptions are captured (5xx and non-status errors) and still reach your `@ExceptionHandler`s. See `examples/spring-boot/`. |
| Logback | `<appender class="io.watchtower.logging.LogbackAppender"/>` (see `examples/logback.xml`); `-Dwatchtower.log.caller=true` stamps `code.filepath`/`code.lineno` (log4j2: `includeLocation="true"`) |
| Log4j2 | `<Watchtower name="WT"/>` with `packages="io.watchtower.logging"` (see `examples/log4j2.xml`) |
| Gradle (Android and JVM) | `watchtower-gradle-plugin`: uploads ProGuard/R8 mappings and native symbols, injects the release name |
| Maven | `watchtower-maven-plugin`: uploads ProGuard mappings, injects the release name |

Logging and Spring libraries are optional dependencies: add only the ones you use. Initialise Watchtower
before the first log line you want shipped (the Spring starter does this for you).

Profiling: `io.watchtower.profiling.WatchtowerProfiler.fromEnv().start()` records the JDK's own
Flight Recorder (`profile` settings: CPU, allocations, locks, thread park; no agent) and posts each
15 s recording to the gateway's `/ingest` as JFR. `profiler.withCurrentSpan(() -> work())` labels the
CPU samples of that thread with the current span: they are joined after each recording and posted
as `<service>.span_cpu`, a separate type so they do not count twice. For wall-clock and native
frames attach async-profiler with a Pyroscope-compatible agent instead.

### Embedded and in-process stores

`io.watchtower.datastore.EmbeddedStores` reports `wt.embedded.*` metrics: `observeCaffeine(meter, cache, name)`
(build with `recordStats()`), `observeRocksDb(meter, path, p -> db.getLongProperty(p))`, `observeFiles(meter,
"h2", path, wal)` for H2, HSQLDB, SQLite JDBC or MapDB files, and `observe(meter, system, ns, supplier)` for
anything else (Ehcache, Chronicle Map). SQL spans for embedded JDBC engines come from the upstream OTel JDBC
instrumentation. Close the returned handle to stop.

## Configuration

| Option | Env | Spring property | Default |
|---|---|---|---|
| ingest key | `WT_INGEST_KEY` | `watchtower.api-key` | required |
| endpoint | `WT_OTLP_ENDPOINT` | `watchtower.endpoint` | `http://localhost:4318` |
| service | `WT_SERVICE` | `watchtower.service` | `app` |
| environment | `WT_ENVIRONMENT` | `watchtower.environment` | none |
| release | `WT_RELEASE` | `watchtower.release` | `META-INF/watchtower.properties` written by the build plugins |
| logs on/off | `WATCHTOWER_LOGS` (`OTEL_LOGS_EXPORTER=none` also turns it off) | | `on` |
| metrics on/off | `WATCHTOWER_METRICS` (`OTEL_METRICS_EXPORTER=none` also turns it off) | | `on` |
| offline spool | `WT_SPOOL_DIR` | | none (spans in the root, `logs/`, `metrics/`, 500 batches each) |
| uncaught handler | | `watchtower.capture-uncaught` | `true` |
| switch off | | `watchtower.enabled` | `true` |

The key is masked in `toString` and never logged.

## Provenance

Every release artifact carries a GitHub build attestation. Verify one with
`gh attestation verify <file> --repo watchtowerobservability/watchtower-java`.

## License

Apache-2.0. Source of truth is the Watchtower monorepo; this repository is a published mirror.
