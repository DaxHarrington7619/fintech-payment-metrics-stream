package fintech.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class PaymentMetricsServer {
    private final DashboardConfig config;
    private final InfraiHandoffClient infrai;
    private final PaymentWorkflow workflow;

    private PaymentMetricsServer(DashboardConfig config) {
        this.config = config;
        this.infrai = new InfraiHandoffClient(config);
        this.workflow = new PaymentWorkflow(new PaymentRiskPolicy(config.reviewThresholdMinor()), infrai);
    }

    public static void main(String[] args) throws Exception {
        DashboardConfig config = DashboardConfig.fromEnvironment();
        PaymentMetricsServer application = new PaymentMetricsServer(config);
        application.infrai.createChannel();
        application.start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.createContext("/payments", this::payments);
        server.createContext("/dashboard-token", this::dashboardToken);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Payment metrics service listening on http://localhost:" + config.port());
    }

    @SuppressWarnings("unchecked")
    private void payments(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, Map.of("error", "method_not_allowed")); return; }
        try {
            Map<String, Object> input = (Map<String, Object>) JsonCodec.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            PaymentRiskPolicy.PaymentEvent payment = new PaymentRiskPolicy.PaymentEvent(
                    required(input, "payment_id"), required(input, "account_id"), number(input, "amount_minor"),
                    required(input, "currency"), (int) number(input, "risk_score"), Instant.parse(required(input, "occurred_at")));
            PaymentRiskPolicy.Decision decision = workflow.accept(payment);
            Map<String, Object> output = new LinkedHashMap<>(decision.dashboardEvent());
            output.put("audit", Map.of("payment_id", decision.audit().paymentId(), "action", decision.audit().action().name().toLowerCase(),
                    "reason", decision.audit().reason(), "decided_at", decision.audit().decidedAt().toString()));
            send(exchange, 202, output);
        } catch (InfraiHandoffClient.InfraiException e) {
            send(exchange, e.status() >= 400 && e.status() < 500 ? e.status() : 502,
                    Map.of("error", e.code(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, Map.of("error", "invalid_payment", "message", e.getMessage()));
        } catch (Exception e) {
            send(exchange, 502, Map.of("error", "upstream_request_failed"));
        }
    }

    @SuppressWarnings("unchecked")
    private void dashboardToken(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, Map.of("error", "method_not_allowed")); return; }
        try {
            Map<String, Object> input = (Map<String, Object>) JsonCodec.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, infrai.issueDashboardToken(required(input, "client_id")));
        } catch (InfraiHandoffClient.InfraiException e) {
            send(exchange, e.status() >= 400 && e.status() < 500 ? e.status() : 502, Map.of("error", e.code(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, Map.of("error", "invalid_client", "message", e.getMessage()));
        } catch (Exception e) {
            send(exchange, 502, Map.of("error", "upstream_request_failed"));
        }
    }

    private static String required(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is required");
        return text;
    }
    private static long number(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be a number");
        return number.longValue();
    }
    private static void send(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = JsonCodec.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
