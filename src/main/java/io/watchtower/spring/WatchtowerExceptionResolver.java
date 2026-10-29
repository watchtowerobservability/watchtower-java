package io.watchtower.spring;

import io.watchtower.Watchtower;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Captures every exception that reaches Spring MVC's resolver chain, then returns null so the
 * application's own {@code @ExceptionHandler}s and the default resolvers still run. Runs first, so
 * errors an {@code @ExceptionHandler} turns into a normal response are still captured.
 * Client errors (ResponseStatusException below 500) are not captured.
 */
public class WatchtowerExceptionResolver implements HandlerExceptionResolver, Ordered {
  @Override
  public ModelAndView resolveException(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
    boolean client = ex instanceof ResponseStatusException r && r.getStatusCode().is4xxClientError();
    if (!client) Watchtower.captureException(ex);
    return null;
  }

  @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
