# watchtower-sdk (Java)

## Install

GitHub Packages Maven repository `https://maven.pkg.github.com/watchtowerobservability/watchtower-java` (needs a token with `read:packages`), artifact `io.github.watchtowerobservability:watchtower-sdk`. The attested jar is also on each release.

Thin layer over the OpenTelemetry Java SDK: `Authorization: Bearer` header, endpoint default
(`WT_OTLP_ENDPOINT`), `wt.*`-safe resource attributes. Offline buffer, redaction hook and prompt/score helpers
are not in the Java package yet; use the Python or Node SDK for those.

Profiling: attach async-profiler with a Pyroscope-compatible agent; no wrapper is shipped here.

```java
OpenTelemetrySdk otel = Watchtower.init(new Watchtower.Options().apiKey(System.getenv("WT_INGEST_KEY")));
```

## Provenance

Every release artifact carries a GitHub build attestation. Verify one with
`gh attestation verify <file> --repo watchtowerobservability/watchtower-java`.

## License

Apache-2.0. Source of truth is the Watchtower monorepo; this repository is a published mirror.
