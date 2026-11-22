package io.watchtower;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IastTest {
  @AfterEach
  void off() {
    Iast.stop();
  }

  @Test
  void requestInputReachingASinkIsReportedOnce() throws Exception {
    List<String> sent = new ArrayList<>();
    Iast.start(new Iast.Options("https://api.example/", "k", "prj_1", "checkout", 60_000,
        (url, body) -> {
          sent.add(url);
          sent.add(body);
        }));
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/u");
    req.addParameter("name", "bob' OR 1=1");
    FilterChain chain = (a, b) -> {
      assertThat(Iast.checkSink("sql_injection", "SELECT * FROM u WHERE n='bob' OR 1=1'")).isTrue();
      assertThat(Iast.checkSink("sql_injection", "SELECT * FROM u WHERE n=?")).isFalse();
    };
    Iast.filter().doFilter(req, new MockHttpServletResponse(), chain);
    assertThat(Iast.checkSink("sql_injection", "bob' OR 1=1")).isFalse(); // outside a request
    Iast.flush();
    assertThat(sent.get(0)).isEqualTo("https://api.example/v1/projects/prj_1/code-security/runtime");
    assertThat(sent.get(1)).contains("\"service\":\"checkout\"").contains("\"vuln_type\":\"sql_injection\"");
  }
}
