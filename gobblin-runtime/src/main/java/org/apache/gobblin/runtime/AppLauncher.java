import org.apache.gobblin.runtime.metrics.OpenTelemetryConfig;

public class AppLauncher {
    public static void main(String[] args) {
        // Initialize OpenTelemetry
        OpenTelemetryConfig.initialize();
    }
} 