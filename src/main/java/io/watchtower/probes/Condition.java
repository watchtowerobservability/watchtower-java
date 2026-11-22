package io.watchtower.probes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The probe condition subset shared by every Watchtower agent: comparisons, {@code in},
 * {@code contains}, {@code && || !} and parentheses over dotted local names. There are no calls,
 * so evaluating a condition cannot run code. Values are the plain snapshot values of {@link
 * Snapshot} (String, Long, Double, Boolean, null, List, Map).
 */
public final class Condition {
  private static final Pattern TOKEN = Pattern.compile(
      "\\s*(&&|\\|\\||==|!=|<=|>=|[<>!()\\[\\],]|'[^']*'|\"[^\"]*\"|-?\\d+(?:\\.\\d+)?|[A-Za-z_][\\w.]*)");

  private interface Node {
    Object eval(Map<String, Object> v);
  }

  private final List<String> t = new ArrayList<>();
  private int i;

  private Condition(String src) {
    String s = src.stripTrailing();
    Matcher m = TOKEN.matcher(s);
    int pos = 0;
    while (pos < s.length()) {
      if (!m.find(pos) || m.start() != pos) {
        throw new IllegalArgumentException("bad condition");
      }
      t.add(m.group(1));
      pos = m.end();
    }
  }

  /** Compiles src; throws IllegalArgumentException outside the subset (calls included). */
  public static Predicate<Map<String, Object>> compile(String src) {
    if (src == null || src.isBlank()) {
      return v -> true;
    }
    Condition c = new Condition(src);
    Node root = c.or();
    if (c.i != c.t.size()) {
      throw new IllegalArgumentException("bad condition");
    }
    return v -> truthy(root.eval(v));
  }

  private String peek() {
    return i < t.size() ? t.get(i) : null;
  }

  private boolean eat(String s) {
    if (s.equals(peek())) {
      i++;
      return true;
    }
    return false;
  }

  private static Object lit(String x) {
    switch (x) {
      case "true": return Boolean.TRUE;
      case "false": return Boolean.FALSE;
      case "null": return null;
      default:
        if (x.startsWith("'") || x.startsWith("\"")) {
          return x.substring(1, x.length() - 1);
        }
        return x.contains(".") ? (Object) Double.parseDouble(x) : (Object) Long.parseLong(x);
    }
  }

  @SuppressWarnings("unchecked")
  static Object get(Map<String, Object> v, String name) {
    Object o = v;
    for (String k : name.split("\\.")) {
      o = o instanceof Map ? ((Map<String, Object>) o).get(k) : null;
    }
    return o;
  }

  private Node atom() {
    String x = peek();
    if (x == null) {
      throw new IllegalArgumentException("bad condition");
    }
    i++;
    if (x.equals("(")) {
      Node e = or();
      if (!eat(")")) {
        throw new IllegalArgumentException("bad condition");
      }
      return e;
    }
    if (x.equals("[")) {
      List<Object> items = new ArrayList<>();
      while (!"]".equals(peek())) {
        String n = peek();
        if (n == null) {
          throw new IllegalArgumentException("bad condition");
        }
        i++;
        if (!n.equals(",")) {
          items.add(lit(n));
        }
      }
      i++;
      return v -> items;
    }
    if (x.matches("^['\"\\-\\d].*") || x.equals("true") || x.equals("false") || x.equals("null")) {
      Object val = lit(x);
      return v -> val;
    }
    if ("(".equals(peek())) {
      throw new IllegalArgumentException("function calls are not allowed");
    }
    return v -> get(v, x);
  }

  private static boolean truthy(Object o) {
    return o != null && !Boolean.FALSE.equals(o);
  }

  private static Object num(Object o) {
    return o instanceof Number ? (Object) ((Number) o).doubleValue() : o;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static boolean compare(String op, Object x, Object y) {
    Object a = num(x);
    Object b = num(y);
    switch (op) {
      case "==": return Objects.equals(a, b);
      case "!=": return !Objects.equals(a, b);
      case "in":
        return y instanceof List && ((List<Object>) y).stream().anyMatch(e -> Objects.equals(num(e), a));
      case "contains": return x instanceof String && y instanceof String && ((String) x).contains((String) y);
      default:
        boolean ok = (a instanceof Double && b instanceof Double) || (a instanceof String && b instanceof String);
        if (!ok) {
          return false;
        }
        int c = ((Comparable) a).compareTo(b);
        return switch (op) {
          case "<" -> c < 0;
          case "<=" -> c <= 0;
          case ">" -> c > 0;
          default -> c >= 0;
        };
    }
  }

  private Node cmp() {
    Node a = atom();
    for (String op : new String[] {"==", "!=", "<=", ">=", "<", ">", "in", "contains"}) {
      if (eat(op)) {
        Node b = atom();
        return v -> compare(op, a.eval(v), b.eval(v));
      }
    }
    return a;
  }

  private Node not() {
    if (eat("!")) {
      Node n = not();
      return v -> !truthy(n.eval(v));
    }
    return cmp();
  }

  private Node and() {
    Node l = not();
    while (eat("&&")) {
      Node a = l;
      Node r = not();
      l = v -> truthy(a.eval(v)) && truthy(r.eval(v));
    }
    return l;
  }

  private Node or() {
    Node l = and();
    while (eat("||")) {
      Node a = l;
      Node r = and();
      l = v -> truthy(a.eval(v)) || truthy(r.eval(v));
    }
    return l;
  }
}
