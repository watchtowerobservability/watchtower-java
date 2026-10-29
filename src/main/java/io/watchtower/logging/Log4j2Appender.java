package io.watchtower.logging;

import io.opentelemetry.api.logs.Severity;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

/** {@code <Configuration packages="io.watchtower.logging">} then {@code <Watchtower name="WT"/>}; needs Watchtower.init first. */
@Plugin(name = "Watchtower", category = "Core", elementType = Appender.ELEMENT_TYPE)
public final class Log4j2Appender extends AbstractAppender {
  private Log4j2Appender(String name, Filter filter) {
    super(name, filter, null, true, Property.EMPTY_ARRAY);
  }

  @PluginFactory
  public static Log4j2Appender create(@PluginAttribute("name") String name, @PluginElement("Filter") Filter filter) {
    return new Log4j2Appender(name == null ? "Watchtower" : name, filter);
  }

  static Severity severity(Level l) {
    if (l.isMoreSpecificThan(Level.ERROR)) return Severity.ERROR;
    if (l.isMoreSpecificThan(Level.WARN)) return Severity.WARN;
    if (l.isMoreSpecificThan(Level.INFO)) return Severity.INFO;
    if (l.isMoreSpecificThan(Level.DEBUG)) return Severity.DEBUG;
    return Severity.TRACE;
  }

  @Override public void append(LogEvent e) {
    WatchtowerLogs.emit(e.getLoggerName(), severity(e.getLevel()), e.getMessage().getFormattedMessage(),
        e.getThrown(), e.getContextData().toMap(), e.getTimeMillis());
  }
}
