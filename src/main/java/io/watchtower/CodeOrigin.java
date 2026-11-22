package io.watchtower;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Optional;

/**
 * Code origin (L2): stamps the file, line and method that started a span as the OTel semconv
 * attributes code.filepath, code.lineno, code.namespace and code.function. Opt-in
 * ({@code Options.codeOrigin}, WT_CODE_ORIGIN=1) because it walks the stack per span. Frames in
 * the JDK, OpenTelemetry and this SDK are skipped. A span that already carries code.filepath is
 * left alone.
 */
final class CodeOrigin implements SpanProcessor {
  private static final String[] SKIP = {"java.", "javax.", "jdk.", "sun.", "io.opentelemetry.", "io.watchtower.", "org.junit."};
  private static final AttributeKey<String> FILE = AttributeKey.stringKey("code.filepath");

  static boolean skipped(String cls) {
    if (cls.endsWith("Test")) return false;
    for (String p : SKIP) if (cls.startsWith(p)) return true;
    return false;
  }

  @Override
  public void onStart(Context parent, ReadWriteSpan span) {
    if (span.getAttribute(FILE) != null) return;
    Optional<StackWalker.StackFrame> f = StackWalker.getInstance().walk(s ->
        s.filter(fr -> !skipped(fr.getClassName()) && fr.getFileName() != null).findFirst());
    f.ifPresent(fr -> {
      span.setAttribute(FILE, fr.getFileName());
      span.setAttribute("code.lineno", (long) fr.getLineNumber());
      span.setAttribute("code.namespace", fr.getClassName());
      span.setAttribute("code.function", fr.getMethodName());
    });
  }

  @Override public boolean isStartRequired() { return true; }
  @Override public void onEnd(ReadableSpan span) {}
  @Override public boolean isEndRequired() { return false; }
}
