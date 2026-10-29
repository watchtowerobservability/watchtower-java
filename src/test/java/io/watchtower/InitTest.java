package io.watchtower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InitTest {
  @Test
  void authHeaderUsesBearer() {
    assertEquals("Bearer test-key-not-real", Watchtower.authHeader("test-key-not-real"));
  }

  @Test
  void endpointDefaultsAndTrimsSlash() {
    assertEquals("http://x:4318", Watchtower.endpoint("http://x:4318/"));
  }

  @Test
  void wtResourceAttributesDropped() {
    var a = Watchtower.safeResource("svc", Map.of("wt.tenant_id", "x", "env", "p"));
    assertNull(a.get(AttributeKey.stringKey("wt.tenant_id")));
    assertEquals("p", a.get(AttributeKey.stringKey("env")));
  }

  @Test
  void exportsSpans() {
    InMemorySpanExporter mem = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("k").exporter(mem));
    sdk.getTracer("t").spanBuilder("op").startSpan().end();
    sdk.getSdkTracerProvider().forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals("op", mem.getFinishedSpanItems().get(0).getName());
  }

  @Test
  void apiKeyRequired() {
    assertThrows(IllegalArgumentException.class, () -> Watchtower.init(new Watchtower.Options()));
  }
}
