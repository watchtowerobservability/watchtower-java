package io.watchtower.probes;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodExitRequest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kinds {@code span} and {@code span_decoration} for {@link Jdi}, run on the JDI thread while the
 * hitting thread is suspended. JDI cannot run code in the target thread, so a span is started at
 * the method's entry breakpoint with the thread's active OTel context ({@link ThreadContext}) as
 * parent, and ended by a method-exit event of that frame, or with status error when an exception
 * leaves it. While the call runs the probe span is the thread's current context (set in the
 * suspended thread's ThreadLocal map, restored at the exit), so spans the method starts nest under
 * it. Without {@code --add-opens java.base/java.lang=ALL-UNNAMED} it is a root span, not current.
 */
final class SpanProbes {
  private static final class Open {
    final Span span;
    final Method method;
    final int depth;
    final List<EventRequest> reqs;
    final Thread thread; // null: the span was not made current
    final Context before;

    Open(Span span, Method method, int depth, List<EventRequest> reqs, Thread thread, Context before) {
      this.span = span;
      this.method = method;
      this.depth = depth;
      this.reqs = reqs;
      this.thread = thread;
      this.before = before;
    }
  }

  private final Probes probes;
  private final EventRequestManager erm;
  private final Map<ThreadReference, Deque<Open>> open = new HashMap<>();

  SpanProbes(Probes probes, EventRequestManager erm) {
    this.probes = probes;
    this.erm = erm;
  }

  static boolean handles(Probes.Live l) {
    return l.kind().equals("span") || l.kind().equals("span_decoration");
  }

  /** At a probe breakpoint of a span kind, the thread still suspended. */
  void onEntry(Probes.Live l, BreakpointEvent e, Map<String, Object> vars, String fn, String path, int line)
      throws IncompatibleThreadStateException {
    ThreadReference t = e.thread();
    if (l.kind().equals("span") && l.className.isEmpty()) {
      return; // a span needs a method: Class#method
    }
    if (!probes.allowed(l, vars)) {
      return;
    }
    Thread th = ThreadContext.thread(t);
    Context ctx = th == null ? null : ThreadContext.current(th);
    if (l.kind().equals("span_decoration")) {
      if (ctx != null) {
        Span s = Span.fromContext(ctx);
        Probes.tags(l.def, vars).forEach(s::setAttribute);
      }
      return;
    }
    Location loc = e.location();
    Span span = GlobalOpenTelemetry.getTracer("io.watchtower.probes").spanBuilder("probe " + loc.method().name())
        .setParent(ctx != null ? ctx : Context.root())
        .setAttribute("probe.id", l.id()).setAttribute("code.function", fn)
        .setAttribute("code.filepath", path).setAttribute("code.lineno", (long) line).startSpan();
    MethodExitRequest mx = erm.createMethodExitRequest();
    mx.addThreadFilter(t);
    mx.addClassFilter(loc.declaringType());
    ExceptionRequest ex = erm.createExceptionRequest(null, true, true);
    ex.addThreadFilter(t);
    for (EventRequest r : List.of(mx, ex)) {
      r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); // frames are read at the event
      r.enable();
    }
    boolean current = th != null && ThreadContext.set(th, (ctx != null ? ctx : Context.root()).with(span));
    open.computeIfAbsent(t, k -> new ArrayDeque<>())
        .push(new Open(span, loc.method(), t.frameCount(), List.of(mx, ex), current ? th : null, ctx));
  }

  void onExit(MethodExitEvent e) throws IncompatibleThreadStateException {
    Deque<Open> d = open.get(e.thread());
    if (d == null) {
      return;
    }
    int fc = e.thread().frameCount(); // the exiting frame is still on the stack
    while (!d.isEmpty() && fc < d.peek().depth) {
      end(e.thread(), d, "frame left unseen"); // an exit we missed
    }
    if (!d.isEmpty() && fc == d.peek().depth && e.method().equals(d.peek().method)) {
      end(e.thread(), d, null);
    }
  }

  /** Ends every open span of the thread whose frame the exception will leave. */
  void onException(ExceptionEvent e) throws IncompatibleThreadStateException {
    Deque<Open> d = open.get(e.thread());
    if (d == null) {
      return;
    }
    Location c = e.catchLocation();
    int fc = e.thread().frameCount();
    while (!d.isEmpty()) {
      boolean caught = false;
      // frame 0 is the throw site; the probed frame is at fc - depth
      for (int i = 0; c != null && !caught && i <= fc - d.peek().depth && i < fc; i++) {
        caught = e.thread().frame(i).location().method().equals(c.method());
      }
      if (caught) {
        return;
      }
      end(e.thread(), d, e.exception().referenceType().name());
    }
  }

  private void end(ThreadReference t, Deque<Open> d, String error) {
    Open o = d.pop();
    erm.deleteEventRequests(o.reqs);
    if (o.thread != null) {
      ThreadContext.set(o.thread, o.before); // the scope's close: LIFO, so nested probes unwind in order
    }
    if (error != null) {
      o.span.setStatus(StatusCode.ERROR, error);
      o.span.setAttribute("exception.type", error);
    }
    o.span.end();
    if (d.isEmpty()) {
      open.remove(t);
    }
  }
}
