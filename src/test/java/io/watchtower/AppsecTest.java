package io.watchtower;

import static org.assertj.core.api.Assertions.assertThat;

import io.watchtower.probes.Json;
import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AppsecTest {
  @SuppressWarnings("unchecked")
  private static Map<String, Object> rules(String mode) {
    return (Map<String, Object>) Json.parse("{\"mode\":\"" + mode + "\",\"rules\":["
        + "{\"id\":\"sqli\",\"category\":\"injection\",\"pattern\":\"union\\\\s+select|' or 1=1\",\"fields\":[\"path\",\"query\"]},"
        + "{\"id\":\"bad\",\"category\":\"x\",\"pattern\":\"(\",\"fields\":[\"path\"]}],"
        + "\"denylist\":{\"ip\":[{\"value\":\"203.0.113.9\",\"expires_at\":4102444800}],\"user\":[{\"value\":\"mallory\"}]}}");
  }

  @BeforeEach
  void on() {
    Appsec.setRuleset(rules("block"));
  }

  @AfterEach
  void off() {
    Appsec.stop();
  }

  private static MockHttpServletRequest get(String query) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", "/items");
    r.setQueryString(query);
    r.setRemoteAddr("10.0.0.1");
    return r;
  }

  @Test
  void aPercentEncodedInjectionIsBlockedWith403AndTheAppNeverRuns() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    FilterChain chain = (a, b) -> ran.set(true);
    MockHttpServletResponse res = new MockHttpServletResponse();
    Appsec.filter().doFilter(get("q=1%27%20OR%201%3D1"), res, chain);
    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(res.getContentType()).contains("application/problem+json");
    assertThat(ran.get()).isFalse();
    MockHttpServletResponse ok = new MockHttpServletResponse();
    Appsec.filter().doFilter(get("q=shoes"), ok, chain);
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(ran.get()).isTrue();
  }

  @Test
  void monitorModeFlagsAndTheDenylistAlwaysBlocks() {
    Appsec.setRuleset(rules("monitor"));
    var d = Appsec.inspect(Appsec.fromRequest(get("q=1+union+select+1"), null), 0);
    assertThat(d.action()).isEqualTo("monitor");
    MockHttpServletRequest listed = get("q=ok");
    listed.addHeader("X-Forwarded-For", "203.0.113.9");
    var deny = Appsec.inspect(Appsec.fromRequest(listed, null), 0);
    assertThat(deny.action()).isEqualTo("block");
    assertThat(deny.ruleId()).isEqualTo("denylist");
    assertThat(Appsec.userBlocked("mallory")).isTrue();
  }

  @Test
  void aRunawayPatternIsTreatedAsNoMatchNotAHang() {
    Appsec.setRuleset(Map.of("mode", "block", "rules", java.util.List.of(
        Map.of("id", "boom", "category", "x", "pattern", "(a+)+$", "fields", java.util.List.of("query")))));
    long t = System.nanoTime();
    var d = Appsec.inspect(Map.of("query", "a".repeat(40) + "!"), 0);
    assertThat(d.action()).isEqualTo("allow");
    assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(5_000);
  }
}
