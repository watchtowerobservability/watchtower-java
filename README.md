# watchtower-sdk (Java)

[![CI](https://github.com/watchtowerobservability/watchtower-java/actions/workflows/ci.yml/badge.svg)](https://github.com/watchtowerobservability/watchtower-java/actions/workflows/ci.yml)

## Install

GitHub Packages Maven repository `https://maven.pkg.github.com/watchtowerobservability/watchtower-java` (needs a token with `read:packages`), artifact `io.github.watchtowerobservability:watchtower-sdk`. The attested jar is also on each release.

Thin layer over the OpenTelemetry Java SDK: `Authorization: Bearer` header, endpoint default,
release and environment resource attributes, `wt.*`-safe resource, error capture linked to the active trace,
log appenders and a Spring Boot starter. Java 17+. Offline buffer, redaction hook and prompt/score helpers
are not in the Java package yet; use the Python or Node SDK for those.

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
| Logback | `<appender class="io.watchtower.logging.LogbackAppender"/>` (see `examples/logback.xml`) |
| Log4j2 | `<Watchtower name="WT"/>` with `packages="io.watchtower.logging"` (see `examples/log4j2.xml`) |
| Gradle (Android and JVM) | `watchtower-gradle-plugin`: uploads ProGuard/R8 mappings and native symbols, injects the release name |
| Maven | `watchtower-maven-plugin`: uploads ProGuard mappings, injects the release name |

Logging and Spring libraries are optional dependencies: add only the ones you use. Initialise Watchtower
before the first log line you want shipped (the Spring starter does this for you).

Profiling: attach async-profiler with a Pyroscope-compatible agent; no wrapper is shipped here.

## Configuration

| Option | Env | Spring property | Default |
|---|---|---|---|
| ingest key | `WT_INGEST_KEY` | `watchtower.api-key` | required |
| endpoint | `WT_OTLP_ENDPOINT` | `watchtower.endpoint` | `http://localhost:4318` |
| service | `WT_SERVICE` | `watchtower.service` | `app` |
| environment | `WT_ENVIRONMENT` | `watchtower.environment` | none |
| release | `WT_RELEASE` | `watchtower.release` | `META-INF/watchtower.properties` written by the build plugins |
| uncaught handler | | `watchtower.capture-uncaught` | `true` |
| switch off | | `watchtower.enabled` | `true` |

The key is masked in `toString` and never logged.

## Provenance

Every release artifact carries a GitHub build attestation. Verify one with
`gh attestation verify <file> --repo watchtowerobservability/watchtower-java`.

## License

Apache-2.0. Source of truth is the Watchtower monorepo; this repository is a published mirror.
