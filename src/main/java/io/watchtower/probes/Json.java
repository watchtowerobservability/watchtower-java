package io.watchtower.probes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader for the probes API response (objects, arrays, strings, numbers, literals). */
public final class Json {
  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  public static Object parse(String s) {
    Json j = new Json(s);
    Object v = j.value();
    j.ws();
    if (j.i != s.length()) {
      throw new IllegalArgumentException("trailing JSON");
    }
    return v;
  }

  private void ws() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
  }

  private Object value() {
    ws();
    if (i >= s.length()) {
      throw new IllegalArgumentException("short JSON");
    }
    char c = s.charAt(i);
    if (c == '{') {
      Map<String, Object> m = new LinkedHashMap<>();
      i++;
      ws();
      if (s.charAt(i) == '}') {
        i++;
        return m;
      }
      while (true) {
        ws();
        String k = string();
        ws();
        expect(':');
        m.put(k, value());
        ws();
        if (s.charAt(i++) == '}') {
          return m;
        }
      }
    }
    if (c == '[') {
      List<Object> l = new ArrayList<>();
      i++;
      ws();
      if (s.charAt(i) == ']') {
        i++;
        return l;
      }
      while (true) {
        l.add(value());
        ws();
        if (s.charAt(i++) == ']') {
          return l;
        }
      }
    }
    if (c == '"') {
      return string();
    }
    for (String lit : new String[] {"true", "false", "null"}) {
      if (s.startsWith(lit, i)) {
        i += lit.length();
        return lit.equals("null") ? null : (Object) Boolean.valueOf(lit);
      }
    }
    int start = i;
    while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) {
      i++;
    }
    String n = s.substring(start, i);
    if (n.isEmpty()) {
      throw new IllegalArgumentException("bad JSON at " + start);
    }
    return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
  }

  private void expect(char c) {
    if (s.charAt(i++) != c) {
      throw new IllegalArgumentException("expected " + c);
    }
  }

  private String string() {
    expect('"');
    StringBuilder b = new StringBuilder();
    while (true) {
      char c = s.charAt(i++);
      if (c == '"') {
        return b.toString();
      }
      if (c != '\\') {
        b.append(c);
        continue;
      }
      char e = s.charAt(i++);
      switch (e) {
        case 'n' -> b.append('\n');
        case 't' -> b.append('\t');
        case 'r' -> b.append('\r');
        case 'b' -> b.append('\b');
        case 'f' -> b.append('\f');
        case 'u' -> {
          b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
          i += 4;
        }
        default -> b.append(e);
      }
    }
  }
}
