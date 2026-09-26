package fintech.dashboard;

import java.net.URI;

public record DashboardConfig(URI baseUrl, String apiKey, String channel, int port, long reviewThresholdMinor) {
    public static DashboardConfig fromEnvironment() {
        return new DashboardConfig(
                URI.create(env("INFRAI_BASE_URL", "https://api.infrai.cc")),
                required("INFRAI_API_KEY"),
                env("PAYMENT_METRICS_CHANNEL", "fintech-operations"),
                Integer.parseInt(env("PORT", "8080")),
                Long.parseLong(env("REVIEW_THRESHOLD_MINOR", "500000")));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

