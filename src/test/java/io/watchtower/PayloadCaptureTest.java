package io.watchtower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PayloadCaptureTest {
  private static Attributes attrs(Consumer<Span> fn) {
    InMemorySpanExporter mem = InMemorySpanExporter.create();
    try (SdkTracerProvider tp = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(mem)).build()) {
      Span s = tp.get("t").spanBuilder("req").startSpan();
      fn.accept(s);
      s.end();
      return mem.getFinishedSpanItems().get(0).getAttributes();
    }
  }

  private static String get(Attributes a, String k) { return a.get(AttributeKey.stringKey(k)); }

  @AfterEach
  void reset() { PayloadCapture.setRules(null); }

  @Test
  void bodiesAreCappedRedactedAndRouteScoped() {
    PayloadCapture.setRules(List.of(new PayloadCapture.Rule("/api/*", 40, false)));
    byte[] body = "{\"user\":\"a@b.io\",\"password\":\"hunter2\",\"card\":\"4111 1111 1111 1111\"}"
        .getBytes(StandardCharsets.UTF_8);
    Attributes a = attrs(s -> PayloadCapture.record(s, "/api/login", "application/json", body, "image/png", new byte[] {1}));
    String got = get(a, "http.request.body");
    assertTrue(got.getBytes(StandardCharsets.UTF_8).length <= 40);
    assertFalse(got.contains("hunter2") || got.contains("a@b.io"));
    assertNull(get(a, "http.response.body"));
    assertTrue(attrs(s -> PayloadCapture.record(s, "/health", "text/plain", new byte[] {65}, null, null)).isEmpty());
    assertEquals("4111 1111 1111 1112", PayloadCapture.redact("4111 1111 1111 1112"));
  }

  @Test
  void graphqlOperationAndDocument() {
    PayloadCapture.setRules(List.of(new PayloadCapture.Rule(null, 0, true)));
    byte[] q = "{\"query\":\"# c\\nmutation AddUser { add(email: \\\"x@y.io\\\") { id } }\"}".getBytes(StandardCharsets.UTF_8);
    Attributes a = attrs(s -> PayloadCapture.record(s, "/graphql", "application/json", q, null, null));
    assertEquals("mutation", get(a, "graphql.operation.type"));
    assertEquals("AddUser", get(a, "graphql.operation.name"));
    assertFalse(get(a, "graphql.document").contains("x@y.io"));
    assertNull(PayloadCapture.graphqlOp("shoes"));
  }
}
