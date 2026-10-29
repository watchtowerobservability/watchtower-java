package io.watchtower.spring;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.watchtower.Watchtower;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Registered through META-INF/spring/...AutoConfiguration.imports. Disable with {@code watchtower.enabled=false}. */
@AutoConfiguration
@EnableConfigurationProperties(WatchtowerProperties.class)
@ConditionalOnProperty(prefix = "watchtower", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WatchtowerAutoConfiguration {

  /** Fails fast without a key (watchtower.api-key or WT_INGEST_KEY). */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public OpenTelemetrySdk watchtowerOpenTelemetry(WatchtowerProperties p) {
    OpenTelemetrySdk sdk = Watchtower.init(new Watchtower.Options()
        .apiKey(p.getApiKey()).endpoint(p.getEndpoint()).serviceName(p.getService())
        .environment(p.getEnvironment()).release(p.getRelease()));
    if (p.isCaptureUncaught()) Watchtower.installUncaughtHandler();
    return sdk;
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(HandlerExceptionResolver.class)
  static class Web {
    @Bean
    @ConditionalOnMissingBean
    WatchtowerExceptionResolver watchtowerExceptionResolver() {
      return new WatchtowerExceptionResolver();
    }
  }
}
