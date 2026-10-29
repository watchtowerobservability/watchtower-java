package io.watchtower.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.watchtower.Watchtower;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AppenderTest {
  @Test
  void logbackEventBecomesLogRecordWithException() {
    InMemoryLogRecordExporter mem = InMemoryLogRecordExporter.create();
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("k")
        .exporter(InMemorySpanExporter.create()).logExporter(mem));
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    LogbackAppender app = new LogbackAppender();
    app.setContext(ctx);
    app.start();
    Logger log = ctx.getLogger("test.logger");
    log.addAppender(app);
    log.error("failed {}", 7, new RuntimeException("kaboom"));
    sdk.getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
    List<LogRecordData> out = mem.getFinishedLogRecordItems();
    assertEquals(1, out.size());
    assertEquals("failed 7", out.get(0).getBody().asString());
    assertEquals(Severity.ERROR, out.get(0).getSeverity());
    assertEquals("kaboom", out.get(0).getAttributes()
        .get(io.opentelemetry.api.common.AttributeKey.stringKey("exception.message")));
  }

  @Test
  void noopBeforeInit() {
    LogbackAppender app = new LogbackAppender();
    assertEquals(Severity.WARN, LogbackAppender.severity(ch.qos.logback.classic.Level.WARN));
    assertEquals(Severity.ERROR, Log4j2Appender.severity(org.apache.logging.log4j.Level.FATAL));
  }
}
