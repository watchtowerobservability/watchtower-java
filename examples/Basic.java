import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.watchtower.Watchtower;

public class Basic {
  public static void main(String[] args) {
    OpenTelemetrySdk otel = Watchtower.init(
        new Watchtower.Options().apiKey(System.getenv("WT_INGEST_KEY")).serviceName("example-java"));
    otel.getTracer("example").spanBuilder("hello").startSpan().end();
    otel.close(); // flush before exit
  }
}
