package io.watchtower.datastore;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.metrics.ObservableMeasurement;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code wt.embedded.*} health metrics for embedded and in-process stores: Caffeine, RocksDB JNI
 * (property reader), any file-backed engine (H2, HSQLDB, SQLite JDBC, MapDB, Chronicle Map files) and
 * anything else through {@link #observe}. SQL spans for H2/HSQLDB/SQLite come from the upstream OTel
 * JDBC instrumentation. Keys and values are never recorded. Close the handle to stop.
 */
public final class EmbeddedStores {
  private EmbeddedStores() {}

  public static final String[] GAUGES = {
    "file.size", "wal.size", "map.size", "map.used", "memory.used", "cache.size", "keys",
    "compaction.pending", "transactions.open", "free_pages", "levels.files", "snapshots.open",
    "iterators.open", "readers"
  };
  public static final String[] COUNTERS = {
    "cache.hits", "cache.misses", "cache.evictions", "cache.rejections", "lock.waits", "lock.wait_time",
    "compactions", "compaction.duration", "flushes", "transactions", "bytes.written", "bytes.read"
  };

  /** Reads {@code read} (metric name to value) at each collection, tagged db.system.name and db.namespace. */
  public static AutoCloseable observe(Meter meter, String system, String ns, Supplier<Map<String, Long>> read) {
    Attributes attrs = Attributes.of(
        AttributeKey.stringKey("db.system.name"), system, AttributeKey.stringKey("db.namespace"), ns);
    Map<String, ObservableLongMeasurement> m = new LinkedHashMap<>();
    for (String g : GAUGES) {
      m.put("wt.embedded." + g, meter.gaugeBuilder("wt.embedded." + g).ofLongs().buildObserver());
    }
    for (String c : COUNTERS) {
      m.put("wt.embedded." + c, meter.counterBuilder("wt.embedded." + c).buildObserver());
    }
    List<ObservableMeasurement> all = new ArrayList<>(m.values());
    return meter.batchCallback(() -> {
      Map<String, Long> r;
      try {
        r = read.get();
      } catch (RuntimeException e) {
        return; // a closed store reports nothing
      }
      r.forEach((k, v) -> {
        ObservableLongMeasurement o = m.get(k);
        if (o != null && v != null && v >= 0) o.record(v, attrs);
      });
    }, all.get(0), all.subList(1, all.size()).toArray(new ObservableMeasurement[0]));
  }

  /** File and WAL size: SQLite ({@code path + "-wal"}), H2 ({@code .mv.db}), HSQLDB ({@code .log}), MapDB files. */
  public static AutoCloseable observeFiles(Meter meter, String system, String path, String walPath) {
    return observe(meter, system, path, () -> {
      Map<String, Long> r = new HashMap<>();
      File f = new File(path);
      if (f.exists()) r.put("wt.embedded.file.size", f.length());
      if (walPath != null && new File(walPath).exists()) r.put("wt.embedded.wal.size", new File(walPath).length());
      return r;
    });
  }

  /** RocksDB properties; pass {@code p -> { try { return db.getLongProperty(p); } catch (RocksDBException e) { return null; } }}. */
  public static AutoCloseable observeRocksDb(Meter meter, String ns, Function<String, Long> prop) {
    String[][] props = {
      {"wt.embedded.keys", "rocksdb.estimate-num-keys"},
      {"wt.embedded.cache.size", "rocksdb.block-cache-usage"},
      {"wt.embedded.compaction.pending", "rocksdb.estimate-pending-compaction-bytes"},
      {"wt.embedded.memory.used", "rocksdb.cur-size-all-mem-tables"},
      {"wt.embedded.file.size", "rocksdb.total-sst-files-size"},
      {"wt.embedded.snapshots.open", "rocksdb.num-snapshots"},
    };
    return observe(meter, "rocksdb", ns, () -> {
      Map<String, Long> r = new HashMap<>();
      for (String[] p : props) {
        Long v = prop.apply(p[1]);
        if (v != null) r.put(p[0], v);
      }
      return r;
    });
  }

  /** Caffeine cache: entries, hits, misses, evictions (build it with {@code recordStats()}). */
  public static AutoCloseable observeCaffeine(Meter meter, com.github.benmanes.caffeine.cache.Cache<?, ?> cache, String name) {
    return observe(meter, "caffeine", name, () -> {
      var s = cache.stats();
      Map<String, Long> r = new HashMap<>();
      r.put("wt.embedded.keys", cache.estimatedSize());
      r.put("wt.embedded.cache.hits", s.hitCount());
      r.put("wt.embedded.cache.misses", s.missCount());
      r.put("wt.embedded.cache.evictions", s.evictionCount());
      return r;
    });
  }
}
