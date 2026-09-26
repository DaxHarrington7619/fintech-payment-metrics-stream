package fintech.dashboard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public final class InfraiHandoffClient implements PaymentMetricSink {
    public static final String API_IDIOM = "infrai.metrics.batch -> infrai.realtime.publish";

    public static final class InfraiException extends Exception {
        private final String code;
        private final int status;
        InfraiException(String code, String message, int status) {
            super(message); this.code = code; this.status = status;
        }
        public String code() { return code; }
        public int status() { return status; }
    }

    private final DashboardConfig config;
    private final HttpClient http;

    public InfraiHandoffClient(DashboardConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    InfraiHandoffClient(DashboardConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    public void createChannel() throws Exception {
        write("POST", "/v1/realtime/channel/create",
                Map.of("channel", config.channel(), "type", "public"), "channel:" + config.channel());
    }

    public Map<String, Object> issueDashboardToken(String clientId) throws Exception {
        return write("POST", "/v1/realtime/token/issue", Map.of(
                "client_id", clientId,
                "channels", List.of(config.channel()),
                "capabilities", List.of("subscribe"),
                "ttl_seconds", 900), "dashboard-token:" + clientId);
    }

    @Override
    public void recordAndPublish(PaymentRiskPolicy.PaymentEvent payment,
                                 PaymentRiskPolicy.Decision decision) throws Exception {
        String operation = "payment:" + payment.paymentId();
        write("POST", "/v1/metrics/batch", Map.of(
                "points", decision.metricPoints(), "idempotency_key", operation + ":metrics"), operation + ":metrics");
        write("POST", "/v1/realtime/publish", Map.of(
                "channel", config.channel(),
                "event", "payment.decision",
                "data", decision.dashboardEvent(),
                "account_id", payment.accountId()), operation + ":realtime");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> write(String method, String path, Map<String, Object> body,
                                      String idempotencyKey) throws Exception {
        String payload = JsonCodec.write(body);
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(config.baseUrl().resolve(path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .method(method, HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response;
            try { response = http.send(request, HttpResponse.BodyHandlers.ofString()); }
            catch (IOException e) {
                if (attempt == 3) throw e;
                Thread.sleep(250L << attempt);
                continue;
            }

            Object decoded = JsonCodec.read(response.body());
            if (!(decoded instanceof Map<?, ?> raw)) throw new IOException("Infrai returned a non-object envelope");
            Map<String, Object> envelope = (Map<String, Object>) raw;
            if (!Boolean.TRUE.equals(envelope.get("ok"))) {
                if (response.statusCode() == 429 && attempt < 3) {
                    Thread.sleep(retryDelayMillis(response, attempt));
                    continue;
                }
                Map<String, Object> error = envelope.get("error") instanceof Map<?, ?> value
                        ? (Map<String, Object>) value : Map.of();
                throw new InfraiException(String.valueOf(error.getOrDefault("code", "REQUEST_REJECTED")),
                        String.valueOf(error.getOrDefault("message", "Request rejected")), response.statusCode());
            }
            if (response.statusCode() >= 500) throw new IOException("Infrai transport status " + response.statusCode());
            return envelope.get("data") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
        }
        throw new IOException("Request attempts exhausted");
    }

    private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try { return Math.max(1, Long.parseLong(value)) * 1000L; }
        catch (NumberFormatException ignored) {
            try {
                long millis = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis();
                if (millis > 0) return millis;
            } catch (RuntimeException ignoredDate) { }
            return 250L << attempt;
        }
    }
}

interface PaymentMetricSink {
    void recordAndPublish(PaymentRiskPolicy.PaymentEvent payment,
                          PaymentRiskPolicy.Decision decision) throws Exception;
}

