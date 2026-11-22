package io.watchtower;

import io.opentelemetry.api.trace.Span;
import io.watchtower.probes.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payload and GraphQL capture (APM-016), driven by the capture rules set in Watchtower per service
 * and route ({@code GET /v1/capture-rules?service=}, an ingest key with scope {@code probes:read}).
 * Opt-in: nothing is fetched or recorded unless {@code WT_CAPTURE_RULES=1} and a rule matches the
 * route. Only text-like bodies are kept, at most the rule's {@code body_bytes} (hard cap 8 KiB),
 * after the default secret and PII detectors (a port of the pipeline's redact stage; ingest
 * applies the project's detectors again). The Spring servlet filter records them.
 * shortcut: rules are fetched once at startup; poll with the ETag (poll_s) when live edits matter.
 */
public final class PayloadCapture {
  public static final int HARD_MAX = 8192;
  private static final String R = "[REDACTED]";
  private static final String KEYS =
      "password|passwd|secret|token|access_token|refresh_token|client_secret|api_?key|authorization";
  private static final Pattern JSON_SECRET =
      Pattern.compile("(\"(?:" + KEYS + ")\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern FORM_SECRET =
      Pattern.compile("\\b((?:" + KEYS + ")=)[^&\\s]*", Pattern.CASE_INSENSITIVE);
  private static final List<Pattern> PATTERNS = List.of(
      Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
      Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
      Pattern.compile("bearer\\s+[A-Za-z0-9._~+/-]+=*", Pattern.CASE_INSENSITIVE),
      Pattern.compile("wt[ik]_[a-z0-9]{8}_[A-Za-z0-9_-]{43}"),
      Pattern.compile("(?:AKIA|ASIA)[0-9A-Z]{16}"),
      Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
      Pattern.compile("[rs]k_(?:live|test)_[A-Za-z0-9]{16,}"),
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"),
      Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"));
  private static final Pattern CARD = Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{1,7}\\b");
  private static final Pattern GQL =
      Pattern.compile("(?:^|\\})\\s*(query|mutation|subscription)\\b\\s*([_A-Za-z][_0-9A-Za-z]*)?");
  private static final Pattern TEXT =
      Pattern.compile("json|^text/|xml|x-www-form-urlencoded|graphql", Pattern.CASE_INSENSITIVE);

  /** One rule as the edge serves it. */
  public record Rule(String route, int bodyBytes, boolean graphqlQuery) {}

  private static volatile List<Rule> rules = List.of();

  private PayloadCapture() {}

  public static void setRules(List<Rule> r) { rules = r == null ? List.of() : List.copyOf(r); }

  public static boolean active() { return !rules.isEmpty(); }

  /** Fetches the service's rules; any failure leaves capture off. */
  @SuppressWarnings("unchecked")
  public static List<Rule> load(String apiUrl, String key, String service) {
    setRules(null);
    try {
      HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl.replaceAll("/+$", "")
              + "/v1/capture-rules?service=" + URLEncoder.encode(service, StandardCharsets.UTF_8)))
          .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + key).GET().build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) return rules;
      Object list = ((Map<String, Object>) Json.parse(res.body())).get("rules");
      List<Rule> out = new ArrayList<>();
      if (list instanceof List<?> l) {
        for (Object o : l) {
          if (!(o instanceof Map<?, ?> m)) continue;
          Object route = m.get("route");
          Object bytes = m.get("body_bytes");
          out.add(new Rule(route instanceof String s ? s : null, bytes instanceof Number n ? n.intValue() : 0,
              Boolean.TRUE.equals(m.get("graphql_query"))));
        }
      }
      setRules(out);
    } catch (Exception e) { // IO, interruption or a malformed answer: capture stays off
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      setRules(null);
    }
    return rules;
  }

  /** {@code *} matches any run of characters (the edge's glob). */
  public static boolean glob(String pat, String s) {
    StringBuilder re = new StringBuilder();
    for (String part : pat.split("\\*", -1)) {
      if (re.length() > 0) re.append(".*");
      re.append(Pattern.quote(part));
    }
    return Pattern.compile(re.toString(), Pattern.DOTALL).matcher(s).matches();
  }

  static Rule ruleFor(String route) {
    for (Rule r : rules) {
      if (r.route() == null || r.route().isEmpty() || glob(r.route(), route)) return r;
    }
    return null;
  }

  private static boolean luhn(String s) {
    int sum = 0;
    int n = 0;
    for (int i = s.length() - 1; i >= 0; i--) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') continue;
      int x = c - '0';
      if (n++ % 2 == 1) x = x * 2 > 9 ? x * 2 - 9 : x * 2;
      sum += x;
    }
    return n >= 13 && sum % 10 == 0;
  }

  /** Default secret and PII detectors: secret-named JSON and form fields, keys, tokens, emails, SSNs, Luhn-valid cards. */
  public static String redact(String s) {
    s = JSON_SECRET.matcher(s).replaceAll("$1\"" + Matcher.quoteReplacement(R) + "\"");
    s = FORM_SECRET.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(R));
    for (Pattern p : PATTERNS) s = p.matcher(s).replaceAll(Matcher.quoteReplacement(R));
    return CARD.matcher(s).replaceAll(m -> Matcher.quoteReplacement(luhn(m.group()) ? R : m.group()));
  }

  private static String cap(String s, int n) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (b.length <= n) return s;
    int end = n;
    while (end > 0 && (b[end] & 0xC0) == 0x80) end--; // do not split a character
    return new String(b, 0, end, StandardCharsets.UTF_8);
  }

  /** Redacts a wider window than it keeps so a secret cut at the edge is still masked. */
  public static String excerpt(String s, int n) { return cap(redact(cap(s, n * 2)), n); }

  /** {type, name} of a GraphQL document ({@code { ... }} is an anonymous query), or null. */
  public static String[] graphqlOp(String doc) {
    String d = doc.replaceAll("#[^\n]*", "");
    Matcher m = GQL.matcher(d);
    if (m.find()) return new String[] {m.group(1), m.group(2)};
    return d.stripLeading().startsWith("{") ? new String[] {"query", null} : null;
  }

  private static String text(String type, byte[] body) {
    if (body == null || body.length == 0 || (type != null && !type.isEmpty() && !TEXT.matcher(type).find())) return null;
    return new String(body, StandardCharsets.UTF_8);
  }

  /** Records the bodies and GraphQL operation the rule matching {@code route} asks for; no rule, nothing. */
  public static void record(Span span, String route, String reqType, byte[] reqBody, String resType, byte[] resBody) {
    Rule rule = ruleFor(route == null ? "" : route);
    if (rule == null || span == null || !span.isRecording()) return;
    int n = Math.min(rule.bodyBytes(), HARD_MAX);
    String req = text(reqType, reqBody);
    String res = text(resType, resBody);
    if (n > 0 && req != null) span.setAttribute("http.request.body", excerpt(req, n));
    if (n > 0 && res != null) span.setAttribute("http.response.body", excerpt(res, n));
    String doc = null;
    String opName = null;
    if (req != null && reqType != null && reqType.toLowerCase().contains("graphql")) {
      doc = req;
    } else if (req != null) {
      try {
        if (Json.parse(req) instanceof Map<?, ?> j && j.get("query") instanceof String q) {
          doc = q;
          opName = j.get("operationName") instanceof String o ? o : null;
        }
      } catch (RuntimeException notJson) {
        // not a JSON body
      }
    }
    String[] op = doc == null ? null : graphqlOp(doc);
    if (op == null) return;
    span.setAttribute("graphql.operation.type", op[0]);
    String name = opName != null ? opName : op[1];
    if (name != null) span.setAttribute("graphql.operation.name", name);
    if (rule.graphqlQuery()) span.setAttribute("graphql.document", excerpt(doc, HARD_MAX));
  }
}
