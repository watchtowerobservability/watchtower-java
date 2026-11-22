package io.watchtower.spring;

import io.opentelemetry.api.trace.Span;
import io.watchtower.PayloadCapture;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Payload capture (APM-016): records request and response bodies and the GraphQL operation on the
 * current server span per the capture rule matching the route (Spring's best matching pattern).
 * Passes straight through while no rules are loaded. Body copies are capped at 2 * HARD_MAX.
 */
public class WatchtowerPayloadFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // event streams must not be buffered by the caching response wrapper
    String accept = request.getHeader("Accept");
    if (!PayloadCapture.active() || (accept != null && accept.contains("text/event-stream"))) {
      chain.doFilter(request, response);
      return;
    }
    ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, 2 * PayloadCapture.HARD_MAX);
    ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
    try {
      chain.doFilter(req, res);
    } finally {
      Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      byte[] out = res.getContentAsByteArray();
      PayloadCapture.record(Span.current(), pattern instanceof String p ? p : req.getRequestURI(),
          req.getContentType(), req.getContentAsByteArray(), res.getContentType(),
          Arrays.copyOf(out, Math.min(out.length, 2 * PayloadCapture.HARD_MAX)));
      res.copyBodyToResponse();
    }
  }
}
