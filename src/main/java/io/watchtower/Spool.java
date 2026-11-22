package io.watchtower;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The disk half of the offline buffer, shared by the span and log record spools: OTLP protobuf
 * requests in {@code dir}, at most {@code maxFiles} (drop-oldest), POSTed to {@code url} oldest
 * first. Delivery stays at-least-once.
 */
final class Spool {
  private final Path dir;
  private final URI url;
  private final Map<String, String> headers;
  private final int maxFiles;
  private final AtomicLong seq = new AtomicLong();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  Spool(Path dir, String url, Map<String, String> headers, int maxFiles) {
    this.dir = dir;
    this.url = URI.create(url);
    this.headers = Map.copyOf(headers);
    this.maxFiles = Math.max(1, maxFiles);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException("cannot create spool dir " + dir, e);
    }
  }

  /** Spooled batch files, oldest first (names start with the write time and a sequence). */
  List<Path> files() {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.getFileName().toString().endsWith(".otlp")).sorted().toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  /** Send spooled batches in order; stops at the first failure so order is kept. */
  void replay() {
    for (Path p : files()) {
      try {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-protobuf")
            .POST(HttpRequest.BodyPublishers.ofByteArray(Files.readAllBytes(p)));
        headers.forEach(b::header);
        int code = http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        if (code < 200 || code >= 300) return;
        Files.deleteIfExists(p);
      } catch (IOException e) {
        return; // still offline
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Write one encoded request, dropping the oldest files over the cap. */
  void write(byte[] request) {
    try {
      List<Path> have = files();
      for (int i = 0; i <= have.size() - maxFiles; i++) Files.deleteIfExists(have.get(i));
      String name = String.format("%013d-%06d-%s.otlp", System.currentTimeMillis(), seq.getAndIncrement() % 1_000_000, UUID.randomUUID());
      Path tmp = dir.resolve(name + ".tmp");
      Files.write(tmp, request);
      Files.move(tmp, dir.resolve(name));
    } catch (IOException e) {
      // disk full or not writable: the batch is lost, as without a spool
    }
  }
}
