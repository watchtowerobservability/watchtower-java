package io.watchtower.probes;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The JDI side of {@link Probes}: attaches to the JVM's own loopback JDWP port, sets breakpoints
 * that suspend only the hitting thread, reads values without invoking methods and resumes. The
 * JDI thread runs only JDK and probe classes, so probes on those packages (and OpenTelemetry,
 * which the emitter uses) are refused: a breakpoint there could suspend the thread that resumes.
 * Condition, rate limit and emit run on a separate thread fed by a bounded queue.
 */
final class Jdi {
  static final String[] REFUSED = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "io.watchtower.probes.",
      "io.opentelemetry."};
  private static final int MAX_READS_PER_MIN = 600;
  private static final int MAX_FIELDS = 50;

  private final Probes probes;
  private final VirtualMachine vm;
  private final List<EventRequest> requests = new ArrayList<>();
  private final Map<String, int[]> reads = new HashMap<>();
  private final BlockingQueue<Runnable> work = new ArrayBlockingQueue<>(1000);
  private final SpanProbes spans;
  private long readWin;

  private Jdi(Probes probes, VirtualMachine vm) {
    this.probes = probes;
    this.vm = vm;
    this.spans = new SpanProbes(probes, vm.eventRequestManager());
  }

  static void attach(Probes p, int port) {
    Thread t = new Thread(() -> run(p, port), "watchtower-probes-jdi");
    t.setDaemon(true);
    t.start();
  }

  private static void run(Probes p, int port) {
    try {
      AttachingConnector c = Bootstrap.virtualMachineManager().attachingConnectors().stream()
          .filter(x -> x.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
      Map<String, Connector.Argument> args = c.defaultArguments();
      args.get("hostname").setValue("127.0.0.1"); // loopback only: the plane never exposes JDWP
      args.get("port").setValue(Integer.toString(port));
      Jdi j = new Jdi(p, c.attach(args));
      Thread emit = new Thread(j::drain, "watchtower-probes-emit");
      emit.setDaemon(true);
      emit.start();
      p.onChange(j::sync);
      j.sync();
      j.loop();
    } catch (Exception e) {
      // probes stay off; the application keeps running
    }
  }

  static boolean refused(String className) {
    for (String r : REFUSED) {
      if (className.startsWith(r)) {
        return true;
      }
    }
    return false;
  }

  private void drain() {
    while (true) {
      try {
        work.take().run();
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException e) {
        // never stop the emitter
      }
    }
  }

  /** Replaces every request with the current probe set. */
  synchronized void sync() {
    EventRequestManager erm = vm.eventRequestManager();
    erm.deleteEventRequests(requests);
    requests.clear();
    for (Probes.Live l : probes.live().values()) {
      ClassPrepareRequest cp = erm.createClassPrepareRequest();
      if (!l.className.isEmpty()) {
        cp.addClassFilter(l.className);
      } else if (!l.file.isEmpty()) {
        cp.addSourceNameFilter("*" + l.file.substring(l.file.lastIndexOf('/') + 1));
      } else {
        continue; // shortcut: JVM function probes need Class#method; a bare name would scan every class
      }
      cp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
      cp.putProperty("probe", l.id());
      cp.enable();
      requests.add(cp);
      for (ReferenceType rt : vm.allClasses()) {
        place(erm, l, rt);
      }
    }
  }

  private void place(EventRequestManager erm, Probes.Live l, ReferenceType rt) {
    if (refused(rt.name())) {
      return;
    }
    List<Location> locs = new ArrayList<>();
    try {
      if (!l.className.isEmpty()) {
        if (!rt.name().equals(l.className)) {
          return;
        }
        for (Method m : rt.methodsByName(l.method)) {
          if (!m.isAbstract() && !m.isNative() && m.location() != null) {
            locs.add(m.location());
          }
        }
      } else {
        boolean same = false;
        for (String sp : rt.sourcePaths(null)) {
          same |= l.file.endsWith(sp);
        }
        if (!same || l.line <= 0) {
          return;
        }
        locs.addAll(rt.locationsOfLine(l.line));
      }
    } catch (AbsentInformationException e) {
      return; // compiled without debug information
    }
    for (Location loc : locs) {
      BreakpointRequest bp = erm.createBreakpointRequest(loc);
      bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
      bp.putProperty("probe", l.id());
      bp.enable();
      requests.add(bp);
    }
  }

  private void loop() throws InterruptedException {
    while (true) {
      EventSet es = vm.eventQueue().remove();
      try {
        for (Event e : es) {
          if (e instanceof VMDisconnectEvent) {
            return;
          }
          if (e instanceof ClassPrepareEvent) {
            Probes.Live l = probes.live().get((String) e.request().getProperty("probe"));
            if (l != null) {
              synchronized (this) {
                place(vm.eventRequestManager(), l, ((ClassPrepareEvent) e).referenceType());
              }
            }
          } else if (e instanceof BreakpointEvent) {
            onBreakpoint((BreakpointEvent) e);
          } else if (e instanceof MethodExitEvent) {
            spans.onExit((MethodExitEvent) e);
          } else if (e instanceof ExceptionEvent) {
            spans.onException((ExceptionEvent) e);
          }
        }
      } catch (RuntimeException | com.sun.jdi.IncompatibleThreadStateException ex) {
        // a failed read never keeps the thread suspended
      } finally {
        es.resume();
      }
    }
  }

  private boolean budget(String id) {
    long minute = System.currentTimeMillis() / 60_000;
    if (minute != readWin) {
      readWin = minute;
      reads.clear();
    }
    return ++reads.computeIfAbsent(id, k -> new int[1])[0] <= MAX_READS_PER_MIN;
  }

  private void onBreakpoint(BreakpointEvent e) {
    String id = (String) e.request().getProperty("probe");
    Probes.Live live = probes.live().get(id);
    if (live == null || !budget(id)) {
      return;
    }
    Map<String, Object> vars = new LinkedHashMap<>();
    try {
      StackFrame f = e.thread().frame(0);
      try {
        for (LocalVariable lv : f.visibleVariables()) {
          vars.put(lv.name(), plain(f.getValue(lv), 1));
        }
      } catch (AbsentInformationException ignored) {
        // no local variable table: only `this` is read
      }
      ObjectReference self = f.thisObject();
      if (self != null) {
        vars.put("this", plain(self, 1));
      }
    } catch (com.sun.jdi.IncompatibleThreadStateException ex) {
      return;
    }
    Location loc = e.location();
    String file;
    try {
      file = loc.sourcePath();
    } catch (AbsentInformationException ex) {
      file = loc.declaringType().name();
    }
    String fn = loc.declaringType().name() + "#" + loc.method().name();
    String path = file;
    int line = loc.lineNumber();
    if (SpanProbes.handles(live)) {
      try {
        spans.onEntry(live, e, vars, fn, path, line);
      } catch (com.sun.jdi.IncompatibleThreadStateException ex) {
        // thread no longer suspended: skip
      }
      return;
    }
    work.offer(() -> probes.hit(id, fn, path, line, vars)); // dropped when the emitter is behind
  }

  /** A JDI value as a plain value, read without invoking any method. */
  static Object plain(Value v, int d) {
    if (v == null) {
      return null;
    }
    if (v instanceof StringReference) {
      return ((StringReference) v).value();
    }
    if (v instanceof BooleanValue) {
      return ((BooleanValue) v).value();
    }
    if (v instanceof CharValue) {
      return String.valueOf(((CharValue) v).value());
    }
    if (v instanceof FloatValue || v instanceof DoubleValue) {
      return ((PrimitiveValue) v).doubleValue();
    }
    if (v instanceof PrimitiveValue) {
      return ((PrimitiveValue) v).longValue();
    }
    if (d > 3) {
      return "[depth limit]";
    }
    if (v instanceof ArrayReference) {
      ArrayReference a = (ArrayReference) v;
      List<Object> out = new ArrayList<>();
      for (Value x : a.getValues(0, Math.min(a.length(), MAX_FIELDS))) {
        out.add(plain(x, d + 1));
      }
      return out;
    }
    ObjectReference o = (ObjectReference) v;
    List<Field> fields = new ArrayList<>();
    for (Field f : o.referenceType().allFields()) {
      if (!f.isStatic() && fields.size() < MAX_FIELDS) {
        fields.add(f);
      }
    }
    if (o.referenceType().name().startsWith("java.lang.") && fields.size() == 1) {
      return plain(o.getValue(fields.get(0)), d); // boxed primitives: their `value` field
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<Field, Value> e : o.getValues(fields).entrySet()) {
      out.put(e.getKey().name(), plain(e.getValue(), d + 1));
    }
    return out;
  }
}
