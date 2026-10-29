package io.watchtower.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.watchtower.Watchtower;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SpringTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(WatchtowerAutoConfiguration.class));

  @Test
  void autoConfiguresFromProperties() {
    runner.withPropertyValues("watchtower.api-key=k", "watchtower.service=svc", "watchtower.capture-uncaught=false")
        .run(c -> {
          assertEquals(1, c.getBeansOfType(OpenTelemetrySdk.class).size());
          assertEquals("svc", c.getBean(WatchtowerProperties.class).getService());
        });
  }

  @Test
  void disabledBacksOff() {
    runner.withPropertyValues("watchtower.enabled=false")
        .run(c -> assertEquals(0, c.getBeansOfType(OpenTelemetrySdk.class).size()));
  }

  @Test
  void resolverCapturesAndPassesThrough() {
    InMemorySpanExporter mem = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("k").exporter(mem));
    var r = new WatchtowerExceptionResolver();
    assertNull(r.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null,
        new RuntimeException("boom")));
    sdk.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
    assertEquals(1, mem.getFinishedSpanItems().size());
  }

  @Test
  void clientErrorsNotCaptured() {
    InMemorySpanExporter mem = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options().apiKey("k").exporter(mem));
    new WatchtowerExceptionResolver().resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(),
        null, new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    sdk.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
    assertEquals(0, mem.getFinishedSpanItems().size());
  }
}
