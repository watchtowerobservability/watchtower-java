package io.watchtower.probes;

import com.sun.jdi.Field;
import com.sun.jdi.LongValue;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import io.opentelemetry.context.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reads and sets the active OTel {@link Context} of another (JDI-suspended) platform thread in its
 * ThreadLocal map, for span parents, span decorations and making a probe span current. Needs
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED} and the API's default thread-local context
 * storage; otherwise (or for virtual threads) it returns null and span probes start root spans that
 * are not current, and decorations do nothing. Only touch a thread's map while it is suspended.
 */
final class ThreadContext {
  private static final java.lang.reflect.Field THREAD_LOCALS;
  private static final Method GET_ENTRY;
  private static final java.lang.reflect.Field VALUE;
  private static final Method SET;
  private static final Constructor<?> NEW_MAP;
  private static final ThreadLocal<?> STORAGE;

  static {
    java.lang.reflect.Field locals = null;
    Method get = null;
    java.lang.reflect.Field value = null;
    Method set = null;
    Constructor<?> newMap = null;
    ThreadLocal<?> storage = null;
    try {
      locals = Thread.class.getDeclaredField("threadLocals");
      locals.setAccessible(true);
      Class<?> map = Class.forName("java.lang.ThreadLocal$ThreadLocalMap");
      get = map.getDeclaredMethod("getEntry", ThreadLocal.class);
      get.setAccessible(true);
      value = Class.forName("java.lang.ThreadLocal$ThreadLocalMap$Entry").getDeclaredField("value");
      value.setAccessible(true);
      set = map.getDeclaredMethod("set", ThreadLocal.class, Object.class);
      set.setAccessible(true);
      newMap = map.getDeclaredConstructor(ThreadLocal.class, Object.class);
      newMap.setAccessible(true);
      java.lang.reflect.Field s = Class.forName("io.opentelemetry.context.ThreadLocalContextStorage")
          .getDeclaredField("THREAD_LOCAL_STORAGE");
      s.setAccessible(true);
      storage = (ThreadLocal<?>) s.get(null);
    } catch (ReflectiveOperationException | RuntimeException e) {
      locals = null; // not opened: no context reads
    }
    THREAD_LOCALS = locals;
    GET_ENTRY = get;
    VALUE = value;
    SET = set;
    NEW_MAP = newMap;
    STORAGE = storage;
  }

  private ThreadContext() { }

  static Context of(ThreadReference t) {
    Thread th = thread(t);
    return th == null ? null : current(th);
  }

  /** The live thread behind a JDI mirror, or null. */
  static Thread thread(ThreadReference t) {
    if (THREAD_LOCALS == null || STORAGE == null) {
      return null;
    }
    try {
      Field f = t.referenceType().fieldByName("tid");
      Value tid = f == null ? null : t.getValue(f);
      return tid instanceof LongValue ? find(((LongValue) tid).value()) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The thread's stored context: null when none is attached (or it cannot be read). */
  static Context current(Thread th) {
    if (THREAD_LOCALS == null || STORAGE == null) {
      return null;
    }
    try {
      Object map = THREAD_LOCALS.get(th);
      Object entry = map == null ? null : GET_ENTRY.invoke(map, STORAGE);
      Object v = entry == null ? null : VALUE.get(entry);
      return v instanceof Context ? (Context) v : null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /**
   * Stores {@code c} (null: none) as the thread's current context, what {@code makeCurrent} does on
   * the thread itself; restoring the earlier value is the scope's close. False when it cannot.
   */
  static boolean set(Thread th, Context c) {
    if (THREAD_LOCALS == null || STORAGE == null || SET == null || NEW_MAP == null) {
      return false;
    }
    try {
      Object map = THREAD_LOCALS.get(th);
      if (map == null) {
        THREAD_LOCALS.set(th, NEW_MAP.newInstance(STORAGE, c));
      } else {
        SET.invoke(map, STORAGE, c);
      }
      return true;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  @SuppressWarnings("deprecation") // getId: threadId() is Java 19+
  private static Thread find(long tid) {
    ThreadGroup g = Thread.currentThread().getThreadGroup();
    while (g.getParent() != null) {
      g = g.getParent();
    }
    Thread[] all = new Thread[g.activeCount() + 16];
    int n = g.enumerate(all, true);
    for (int i = 0; i < n; i++) {
      if (all[i].getId() == tid) {
        return all[i];
      }
    }
    return null;
  }
}
