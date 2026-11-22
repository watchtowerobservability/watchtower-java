package io.watchtower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.watchtower.probes.Json;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OriginSamplingTest {
  @Test
  @SuppressWarnings("unchecked")
  void rulesPickTheRatio() {
    var cfg = (Map<String, Object>) Json.parse("{\"strategyType\":\"RULES\",\"rules\":["
        + "{\"match\":{\"service\":\"api\",\"span_name_glob\":\"GET /health*\"},\"action\":{\"kind\":\"drop\"}},"
        + "{\"match\":{\"service\":\"api\"},\"action\":{\"kind\":\"ratio\",\"ratio\":0.1}}]}");
    assertEquals(0, RemoteSampler.ratioFor(cfg, "api", "", "GET /health/live", Attributes.empty()));
    assertEquals(0.1, RemoteSampler.ratioFor(cfg, "api", "", "GET /x", Attributes.empty()));
    assertEquals(1, RemoteSampler.ratioFor(cfg, "web", "", "GET /x", Attributes.empty()));
    var prob = (Map<String, Object>) Json.parse("{\"strategyType\":\"PROBABILISTIC\",\"probabilisticSampling\":{\"samplingRate\":0.25}}");
    assertEquals(0.25, RemoteSampler.ratioFor(prob, "api", "", "x", Attributes.empty()));
  }

  @Test
  void traceFractionIsStableAndUniformEnough() {
    int kept = 0;
    for (int i = 0; i < 2000; i++) {
      String id = String.format("%032x", (long) i * 7919 + (7L << 40));
      assertEquals(RemoteSampler.traceFraction(id), RemoteSampler.traceFraction(id));
      if (RemoteSampler.traceFraction(id) < 0.5) kept++;
    }
    assertTrue(kept > 800 && kept < 1200, "kept " + kept);
  }

  @Test
  void spanGetsTheCodeOfTheCaller() {
    var mem = InMemorySpanExporter.create();
    var tp = SdkTracerProvider.builder().addSpanProcessor(new CodeOrigin()).addSpanProcessor(SimpleSpanProcessor.create(mem)).build();
    tp.get("t").spanBuilder("op").startSpan().end();
    var a = mem.getFinishedSpanItems().get(0).getAttributes();
    assertEquals("OriginSamplingTest.java", a.get(AttributeKey.stringKey("code.filepath")));
    assertEquals("spanGetsTheCodeOfTheCaller", a.get(AttributeKey.stringKey("code.function")));
  }
}
