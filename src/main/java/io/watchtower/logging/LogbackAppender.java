package io.watchtower.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.logs.Severity;

/** {@code <appender name="WT" class="io.watchtower.logging.LogbackAppender"/>}; needs Watchtower.init first. */
public class LogbackAppender extends AppenderBase<ILoggingEvent> {
  static Severity severity(Level l) {
    switch (l.toInt()) {
      case Level.ERROR_INT: return Severity.ERROR;
      case Level.WARN_INT: return Severity.WARN;
      case Level.INFO_INT: return Severity.INFO;
      case Level.DEBUG_INT: return Severity.DEBUG;
      default: return Severity.TRACE;
    }
  }

  @Override protected void append(ILoggingEvent e) {
    IThrowableProxy p = e.getThrowableProxy();
    Throwable t = p instanceof ThrowableProxy ? ((ThrowableProxy) p).getThrowable() : null;
    WatchtowerLogs.emit(e.getLoggerName(), severity(e.getLevel()), e.getFormattedMessage(), t,
        e.getMDCPropertyMap(), e.getTimeStamp());
  }
}
