# Changelog

## 0.1.0

- OpenTelemetry Java SDK distribution: `Authorization: Bearer` key, endpoint default, release and environment resource attributes, `wt.*`-safe resource.
- `Watchtower.captureException` (linked to the active span) and `Watchtower.installUncaughtHandler`.
- Logs to `/v1/logs` through `LogbackAppender` and `Log4j2Appender`.
- Spring Boot auto-configuration (`watchtower.*` properties) with MVC exception capture.
- The Gradle and Maven plugins that write the release name and upload ProGuard/R8 mappings are separate packages.
