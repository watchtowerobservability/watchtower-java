package io.watchtower.profiling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WatchtowerProfilerTest {
  private static final String KEY = "wtk_example_not_a_real_key"; // fake

  @Test
  void uriNamesServiceTypeAndLabels() {
    WatchtowerProfiler p = new WatchtowerProfiler(KEY, "http://gw:4318/", "api", Map.of("env", "prod"), Duration.ofSeconds(15));
    String u = p.ingestUri(10, 25).toString();
    assertTrue(u.startsWith("http://gw:4318/ingest?name=api.cpu%7Benv%3Dprod%7D&from=10&until=25&format=jfr"), u);
    assertFalse(p.toString().contains(KEY));
    assertThrows(IllegalArgumentException.class, () -> new WatchtowerProfiler("", null, null, null, Duration.ZERO));
  }

  @Test
  void joinsCpuSamplesToTheSpanTheyRanIn() throws Exception {
    WatchtowerProfiler p = new WatchtowerProfiler(KEY, "http://127.0.0.1:9", "api", Map.of(), Duration.ofSeconds(1));
    long end = System.currentTimeMillis() + 2500;
    Thread t = new Thread(() -> p.withSpan("ab", "cd", () -> {
      double x = 0;
      while (System.currentTimeMillis() < end) x = Math.sqrt(x + 1);
      return x;
    }));
    t.start();
    byte[] jfr = WatchtowerProfiler.record(Duration.ofSeconds(2));
    t.join();
    Map<String, Long> folded = p.spanFolded(jfr);
    assertTrue(folded.keySet().stream().anyMatch(k -> k.startsWith("span_id=ab,trace_id=cd\t")), folded.toString());
  }

  @Test
  void recordsJfrAndCountsAFailedUpload() throws Exception {
    byte[] jfr = WatchtowerProfiler.record(Duration.ofMillis(200));
    assertEquals("FLR\0", new String(jfr, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    WatchtowerProfiler dead = new WatchtowerProfiler(KEY, "http://127.0.0.1:9", "api", Map.of(), Duration.ofSeconds(1));
    dead.upload(jfr, 0, 1);
    assertEquals(1, dead.dropped());
  }
}
