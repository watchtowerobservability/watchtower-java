package io.watchtower.profiling;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Continuous profiler on the JDK's own Flight Recorder (no agent): every period a JFR recording
 * with the {@code profile} settings (CPU samples, allocations, locks, thread park) is closed and
 * posted to the gateway's Pyroscope-compatible {@code /ingest} as {@code format=jfr}. Uploads are
 * best effort: a failure is counted and dropped. JFR events carry no OpenTelemetry context, so
 * {@link #withSpan} records each span's thread and time, and the CPU samples falling inside are
 * joined after the recording and posted as folded stacks of {@code <service>.span_cpu}: a second
 * type, because they are also in the JFR upload and must not count twice.
 */
public final class WatchtowerProfiler implements AutoCloseable {
  private final String key;
  private final String endpoint;
  private final String service;
  private final Map<String, String> tags;
  private final Duration period;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final AtomicLong uploaded = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final ConcurrentLinkedQueue<SpanInterval> intervals = new ConcurrentLinkedQueue<>();
  private volatile boolean stop;
  private Thread thread;

  public WatchtowerProfiler(String key, String endpoint, String service, Map<String, String> tags, Duration period) {
    if (key == null || key.isEmpty()) {
      throw new IllegalArgumentException("key is required");
    }
    this.key = key;
    this.endpoint = (endpoint == null ? "http://localhost:4318" : endpoint).replaceAll("/+$", "");
    this.service = service == null ? "app" : service;
    this.tags = new TreeMap<>(tags == null ? Map.of() : tags);
    this.period = period.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : period;
  }

  /** Key, endpoint and service from WT_INGEST_KEY, WT_OTLP_ENDPOINT and WT_SERVICE. */
  public static WatchtowerProfiler fromEnv() {
    Map<String, String> tags = new TreeMap<>();
    if (System.getenv("WT_ENVIRONMENT") != null) tags.put("env", System.getenv("WT_ENVIRONMENT"));
    if (System.getenv("WT_RELEASE") != null) tags.put("version", System.getenv("WT_RELEASE"));
    return new WatchtowerProfiler(System.getenv("WT_INGEST_KEY"), System.getenv("WT_OTLP_ENDPOINT"),
        System.getenv("WT_SERVICE"), tags, Duration.ofSeconds(15));
  }

  private static final class SpanInterval {
    final long thread = Thread.currentThread().getId();
    final Instant start = Instant.now();
    volatile Instant end;
    final String prefix;

    SpanInterval(String spanId, String traceId) {
      prefix = "span_id=" + spanId + ",trace_id=" + traceId + "\t";
    }

    boolean covers(long t, Instant at) {
      Instant e = end;
      return t == thread && !at.isBefore(start) && (e == null || !at.isAfter(e));
    }
  }

  /** Labels this thread's CPU samples with a span (hex ids) while {@code fn} runs. */
  public <T> T withSpan(String spanId, String traceId, Supplier<T> fn) {
    SpanInterval iv = new SpanInterval(spanId, traceId);
    intervals.add(iv);
    try {
      return fn.get();
    } finally {
      iv.end = Instant.now();
    }
  }

  /** Labels the current OpenTelemetry span's samples while {@code fn} runs. */
  public <T> T withCurrentSpan(Supplier<T> fn) {
    SpanContext sc = Span.current().getSpanContext();
    return sc.isValid() ? withSpan(sc.getSpanId(), sc.getTraceId(), fn) : fn.get();
  }

  /** Folded stacks of the CPU samples inside a span; forgets intervals ended before the recording's end. */
  Map<String, Long> spanFolded(byte[] jfr) throws IOException {
    Map<String, Long> out = new TreeMap<>();
    if (intervals.isEmpty()) return out;
    Instant cut = Instant.now();
    Path tmp = Files.createTempFile("wt-profile", ".jfr");
    try {
      Files.write(tmp, jfr);
      for (RecordedEvent e : RecordingFile.readAllEvents(tmp)) {
        if (!e.getEventType().getName().equals("jdk.ExecutionSample") || e.getStackTrace() == null) continue;
        RecordedThread th = e.getThread("sampledThread");
        if (th == null) continue;
        for (SpanInterval iv : intervals) {
          if (iv.covers(th.getJavaThreadId(), e.getStartTime())) {
            out.merge(iv.prefix + foldFrames(e.getStackTrace().getFrames()), 1L, Long::sum);
            break;
          }
        }
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
    intervals.removeIf(iv -> iv.end != null && iv.end.isBefore(cut));
    return out;
  }

  private static String foldFrames(List<RecordedFrame> leafFirst) {
    StringBuilder b = new StringBuilder();
    for (int i = leafFirst.size() - 1; i >= 0; i--) {
      RecordedMethod m = leafFirst.get(i).getMethod();
      if (b.length() > 0) b.append(';');
      b.append((m.getType().getName() + "." + m.getName()).replace(';', ':'));
    }
    return b.toString();
  }

  public long uploaded() {
    return uploaded.get();
  }

  public long dropped() {
    return dropped.get();
  }

  @Override
  public String toString() {
    return "WatchtowerProfiler{service=" + service + "}";
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  URI ingestUri(long fromS, long untilS) {
    return uri(".cpu", "&format=jfr", fromS, untilS);
  }

  private URI uri(String suffix, String format, long fromS, long untilS) {
    String labels = tags.entrySet().stream().filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(","));
    return URI.create(endpoint + "/ingest?name=" + enc(service + suffix + "{" + labels + "}") + "&from=" + fromS
        + "&until=" + untilS + format + "&spyName=watchtower-java");
  }

  /** One recording of {@code d}, as JFR bytes. */
  static byte[] record(Duration d) throws IOException, ParseException, InterruptedException {
    Path tmp = Files.createTempFile("wt-profile", ".jfr");
    try (Recording r = new Recording(Configuration.getConfiguration("profile"))) {
      r.start();
      Thread.sleep(d.toMillis());
      r.stop();
      r.dump(tmp);
      return Files.readAllBytes(tmp);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  void upload(byte[] jfr, long fromS, long untilS) {
    post(ingestUri(fromS, untilS), jfr);
    try {
      StringBuilder body = new StringBuilder();
      spanFolded(jfr).forEach((k, n) -> body.append(k).append(' ').append(n).append('\n'));
      if (body.length() > 0) {
        post(uri(".span_cpu", "&format=folded&units=samples&sampleRate=100", fromS, untilS), body.toString().getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      dropped.incrementAndGet();
    }
  }

  private void post(URI uri, byte[] body) {
    try {
      HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
          .header("Authorization", "Bearer " + key).header("Content-Type", "application/octet-stream")
          .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
      int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
      (code / 100 == 2 ? uploaded : dropped).incrementAndGet();
    } catch (IOException e) {
      dropped.incrementAndGet();
    } catch (InterruptedException e) {
      dropped.incrementAndGet();
      Thread.currentThread().interrupt();
    }
  }

  public synchronized WatchtowerProfiler start() {
    if (thread != null) return this;
    thread = new Thread(() -> {
      while (!stop) {
        long from = System.currentTimeMillis() / 1000;
        try {
          byte[] jfr = record(period);
          upload(jfr, from, System.currentTimeMillis() / 1000);
        } catch (InterruptedException e) {
          return;
        } catch (IOException | ParseException | RuntimeException e) {
          dropped.incrementAndGet();
        }
      }
    }, "watchtower-profiler");
    thread.setDaemon(true);
    thread.start();
    return this;
  }

  @Override
  public synchronized void close() {
    stop = true;
    if (thread != null) thread.interrupt();
    thread = null;
  }
}
