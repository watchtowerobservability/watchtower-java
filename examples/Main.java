// Run with the SDK on the classpath: WT_INGEST_KEY=... WT_OTLP_ENDPOINT=... java Main.java
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.watchtower.Watchtower;

public class Main {
  public static void main(String[] args) {
    OpenTelemetrySdk otel = Watchtower.init(new Watchtower.Options().serviceName("example").release("1.0.0"));
    Watchtower.installUncaughtHandler();
    try {
      throw new IllegalStateException("example error");
    } catch (RuntimeException e) {
      System.out.println("trace " + Watchtower.captureException(e));
    }
    otel.close();
  }
}
