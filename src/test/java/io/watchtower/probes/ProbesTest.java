package io.watchtower.probes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProbesTest {
  @Test
  void conditionSubsetRefusesCalls() {
    assertThrows(IllegalArgumentException.class, () -> Condition.compile("System.exit(1)"));
    Map<String, Object> v = Map.of("user", Map.of("id", 7L), "name", "a", "total", 2.5);
    assertTrue(Condition.compile("user.id == 7 && name in ['a', 'b']").test(v));
    assertFalse(Condition.compile("total > 10 || !(name == 'a')").test(v));
  }

  @Test
  void captureIsBounded() {
    Map<String, Object> vars = Map.of("a", Map.of("b", Map.of("c", Map.of("d", 1L))), "big", "x".repeat(5000));
    Map<String, String> out = Probes.capture(vars, 2, 2000);
    assertEquals("[depth limit]", out.get("a.b.c"));
    assertEquals(1024, out.get("big").length());
  }

  @Test
  void hitsAreConditionalRateLimitedAndExpire() {
    List<Map<String, String>> events = new ArrayList<>();
    Probes p = new Probes("http://127.0.0.1:1", "k", "s", "", "", (n, a) -> events.add(a), () -> 60_000L);
    p.set(List.of(
        Json.parse("{\"id\":\"p1\",\"location\":{\"function\":\"com.acme.Shop#checkout\"},\"condition\":\"total > 1\",\"max_rate_per_min\":2}"),
        Json.parse("{\"id\":\"old\",\"location\":{\"file\":\"Shop.java\",\"line\":3},\"expires_at\":1}"),
        Json.parse("{\"id\":\"bad\",\"condition\":\"f(x)\"}")));
    assertEquals(2, p.live().size(), "the probe with a call in its condition is skipped");
    for (int i = 0; i < 3; i++) {
      p.hit("p1", "com.acme.Shop#checkout", "com/acme/Shop.java", 12, Map.of("total", 5L));
      p.hit("old", "f", "Shop.java", 3, Map.of());
    }
    p.hit("p1", "com.acme.Shop#checkout", "com/acme/Shop.java", 12, Map.of("total", 0L));
    assertEquals(2, events.size());
    assertEquals("5", events.get(0).get("code.snapshot.total"));
    assertEquals("p1", events.get(0).get("probe.id"));
  }

  @Test
  void refusedPackagesProtectTheProbeThreads() {
    assertTrue(Jdi.refused("java.util.HashMap"));
    assertTrue(Jdi.refused("io.opentelemetry.sdk.logs.SdkLogger"));
    assertFalse(Jdi.refused("com.acme.Shop"));
  }

  @Test
  void startIsInertWithoutJdwpPort() {
    if (System.getenv("WT_PROBES_JDWP_PORT") == null) {
      assertEquals(null, Probes.start("http://127.0.0.1:1", "k", "s", "", "", 30, null));
    }
  }

  @Test
  void spanKindsShareTheGateAndRenderTagsWithoutWt() {
    Probes p = new Probes("http://127.0.0.1:1", "k", "s", "", "", (n, a) -> { }, () -> 60_000L);
    p.set(List.of(
        Json.parse("{\"id\":\"sp\",\"kind\":\"span\",\"location\":{\"function\":\"com.acme.Shop#pay\"},\"condition\":\"total > 1\",\"max_rate_per_min\":1}"),
        Json.parse("{\"id\":\"d\",\"kind\":\"span_decoration\",\"expires_at\":1,\"location\":{\"function\":\"com.acme.Shop#pay\"},"
            + "\"capture\":{\"tags\":[{\"name\":\"order\",\"value\":\"t={total} u={user.id} x={nope} $1\"},{\"name\":\"wt.tenant\",\"value\":\"evil\"}]}}")));
    Probes.Live sp = p.live().get("sp");
    assertEquals("span", sp.kind());
    assertTrue(SpanProbes.handles(sp));
    assertFalse(p.allowed(sp, Map.of("total", 0L)), "condition");
    assertTrue(p.allowed(sp, Map.of("total", 5L)));
    assertFalse(p.allowed(sp, Map.of("total", 5L)), "rate limit");
    Probes.Live d = p.live().get("d");
    assertFalse(p.allowed(d, Map.of()), "expired");
    Map<String, String> tags = Probes.tags(d.def, Map.of("total", 42L, "user", Map.of("id", 7L)));
    assertEquals(Map.of("order", "t=42 u=7 x= $1"), tags);
    assertEquals(1024, Probes.render("{a}", Map.of("a", "x".repeat(5000))).length());
  }

  @Test
  void aProbeSpanIsCurrentInTheOtherThreadUntilRestored() throws Exception {
    Span probe = Span.wrap(SpanContext.create("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
        TraceFlags.getSampled(), TraceState.getDefault()));
    CountDownLatch in = new CountDownLatch(1);
    CountDownLatch read = new CountDownLatch(1);
    CountDownLatch out = new CountDownLatch(1);
    AtomicReference<Span> during = new AtomicReference<>();
    AtomicReference<Span> after = new AtomicReference<>();
    Thread th = new Thread(() -> {
      try {
        in.await();
        during.set(Span.current()); // a span started here would get the probe span as parent
        read.countDown();
        out.await();
        after.set(Span.current());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    th.start();
    Context before = ThreadContext.current(th);
    assertTrue(ThreadContext.set(th, Context.root().with(probe)), "needs --add-opens java.base/java.lang");
    in.countDown();
    read.await();
    assertEquals(probe.getSpanContext(), during.get().getSpanContext());
    assertEquals(probe.getSpanContext(), Span.fromContext(ThreadContext.current(th)).getSpanContext());
    assertTrue(ThreadContext.set(th, before));
    out.countDown();
    th.join();
    assertFalse(after.get().getSpanContext().isValid(), "restored: no probe span after the call");
  }
}
