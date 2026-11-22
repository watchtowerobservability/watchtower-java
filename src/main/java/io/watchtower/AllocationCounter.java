package io.watchtower;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request allocation (APM-007): the bytes a SERVER span's thread allocated between its start and
 * its end ({@code ThreadMXBean.getThreadAllocatedBytes}), exported as {@code runtime.alloc.bytes}.
 * Watchtower shows them per endpoint with the worst request's trace. A span that ends on another
 * thread (an async hand-off) is skipped rather than guessed. Off with {@code WT_ALLOC_COUNTER=0}.
 *
 * <p>Span attributes cannot change after the end, so the processor keeps the value by span id and
 * {@link #wrap} adds it at export.
 */
final class AllocationCounter implements SpanProcessor {
  static final AttributeKey<Long> KEY = AttributeKey.longKey("runtime.alloc.bytes");
  private static final int MAX = 10_000;

  private final com.sun.management.ThreadMXBean mx;
  private final Map<String, long[]> open = new ConcurrentHashMap<>();
  private final Map<String, Long> done = new ConcurrentHashMap<>();

  private AllocationCounter(com.sun.management.ThreadMXBean mx) {
    this.mx = mx;
  }

  /** The counter, or null when switched off or the JVM cannot count per thread. */
  static AllocationCounter create() {
    if ("0".equals(System.getenv("WT_ALLOC_COUNTER"))) return null;
    if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean mx)) return null;
    try {
      if (!mx.isThreadAllocatedMemorySupported()) return null;
      if (!mx.isThreadAllocatedMemoryEnabled()) mx.setThreadAllocatedMemoryEnabled(true);
    } catch (UnsupportedOperationException | SecurityException e) {
      return null;
    }
    return new AllocationCounter(mx);
  }

  @SuppressWarnings("deprecation") // Thread.getId: threadId() needs Java 19, the SDK targets 17
  private static long tid() {
    return Thread.currentThread().getId();
  }

  @Override
  public void onStart(Context parent, ReadWriteSpan span) {
    if (span.getKind() != SpanKind.SERVER || open.size() >= MAX) return;
    long t = tid();
    open.put(span.getSpanContext().getSpanId(), new long[] {t, mx.getThreadAllocatedBytes(t)});
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    long[] s = open.remove(span.getSpanContext().getSpanId());
    if (s == null || s[0] != tid()) return;
    long bytes = mx.getThreadAllocatedBytes(s[0]) - s[1];
    if (bytes < 0 || s[1] < 0) return; // -1 means the JVM could not measure
    if (done.size() >= MAX) done.clear(); // spans recorded but never exported must not pile up
    done.put(span.getSpanContext().getSpanId(), bytes);
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  /** Adds the measured bytes to exported spans. */
  SpanExporter wrap(SpanExporter ex) {
    return new SpanExporter() {
      @Override
      public CompletableResultCode export(Collection<SpanData> spans) {
        List<SpanData> out = new ArrayList<>(spans.size());
        for (SpanData s : spans) out.add(with(s, done.remove(s.getSpanContext().getSpanId())));
        return ex.export(out);
      }

      @Override
      public CompletableResultCode flush() {
        return ex.flush();
      }

      @Override
      public CompletableResultCode shutdown() {
        return ex.shutdown();
      }
    };
  }

  static SpanData with(SpanData s, Long bytes) {
    if (bytes == null) return s;
    Attributes a = s.getAttributes().toBuilder().put(KEY, bytes).build();
    return new DelegatingSpanData(s) {
      @Override
      public Attributes getAttributes() {
        return a;
      }

      @Override
      public int getTotalAttributeCount() {
        return s.getTotalAttributeCount() + 1;
      }
    };
  }
}
