package io.watchtower.logging;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.watchtower.Watchtower;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;

/** Shared by the logback and log4j2 appenders. Trace context comes from the calling thread. */
final class WatchtowerLogs {
  private WatchtowerLogs() {}

  static void emit(String logger, Severity sev, String text, Throwable t, Map<String, String> mdc, long epochMillis) {
    OpenTelemetrySdk sdk = Watchtower.current();
    if (sdk == null) return; // appender configured before Watchtower.init: drop, never throw into app logging
    LogRecordBuilder b = sdk.getSdkLoggerProvider().get(logger == null ? "root" : logger).logRecordBuilder()
        .setContext(Context.current())
        .setTimestamp(Instant.ofEpochMilli(epochMillis))
        .setSeverity(sev).setSeverityText(sev.name()).setBody(text == null ? "" : text);
    if (mdc != null) mdc.forEach((k, v) -> { if (!k.startsWith("wt.")) b.setAttribute(AttributeKey.stringKey(k), v); });
    if (t != null) {
      StringWriter sw = new StringWriter();
      t.printStackTrace(new PrintWriter(sw));
      b.setAttribute(AttributeKey.stringKey("exception.type"), t.getClass().getName());
      b.setAttribute(AttributeKey.stringKey("exception.message"), String.valueOf(t.getMessage()));
      b.setAttribute(AttributeKey.stringKey("exception.stacktrace"), sw.toString());
    }
    b.emit();
  }
}
