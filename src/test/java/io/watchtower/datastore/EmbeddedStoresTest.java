package io.watchtower.datastore;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbeddedStoresTest {
  private static Map<String, Long> values(InMemoryMetricReader reader) {
    Map<String, Long> out = new HashMap<>();
    for (MetricData m : reader.collectAllMetrics()) {
      switch (m.getType()) {
        case LONG_GAUGE -> m.getLongGaugeData().getPoints().forEach(p -> out.put(m.getName(), p.getValue()));
        case LONG_SUM -> m.getLongSumData().getPoints().forEach(p -> out.put(m.getName(), p.getValue()));
        default -> {}
      }
    }
    return out;
  }

  @Test
  void caffeineHitsMissesAndUnknownNamesIgnored() throws Exception {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider mp = SdkMeterProvider.builder().registerMetricReader(reader).build();
    Cache<String, Integer> c = Caffeine.newBuilder().recordStats().build();
    c.put("a", 1);
    c.getIfPresent("a");
    c.getIfPresent("b");
    try (AutoCloseable h = EmbeddedStores.observeCaffeine(mp.get("t"), c, "sessions");
        AutoCloseable g = EmbeddedStores.observe(mp.get("t"), "x", "y", () -> Map.of("not.ours", 1L))) {
      Map<String, Long> v = values(reader);
      assertThat(v).containsEntry("wt.embedded.cache.hits", 1L).containsEntry("wt.embedded.cache.misses", 1L);
      assertThat(v).doesNotContainKey("not.ours");
    }
  }

  @Test
  void rocksDbPropertiesViaReader() throws Exception {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider mp = SdkMeterProvider.builder().registerMetricReader(reader).build();
    try (AutoCloseable h = EmbeddedStores.observeRocksDb(mp.get("t"), "/r",
        p -> p.equals("rocksdb.estimate-num-keys") ? 42L : null)) {
      assertThat(values(reader)).containsEntry("wt.embedded.keys", 42L).doesNotContainKey("wt.embedded.file.size");
    }
  }
}
