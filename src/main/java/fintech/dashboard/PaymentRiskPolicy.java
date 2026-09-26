package fintech.dashboard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PaymentRiskPolicy {
    public enum Action { AUTHORIZE, REVIEW }

    public record PaymentEvent(String paymentId, String accountId, long amountMinor,
                               String currency, int riskScore, Instant occurredAt) {}

    public record AuditNotification(String paymentId, Action action, String reason,
                                    Instant decidedAt) {}

    public record Decision(Action action, AuditNotification audit,
                           List<Map<String, Object>> metricPoints,
                           Map<String, Object> dashboardEvent) {}

    private final long reviewThresholdMinor;

    public PaymentRiskPolicy(long reviewThresholdMinor) {
        this.reviewThresholdMinor = reviewThresholdMinor;
    }

    public Decision evaluate(PaymentEvent payment) {
        boolean highValue = payment.amountMinor() >= reviewThresholdMinor;
        boolean elevatedRisk = payment.riskScore() >= 70;
        Action action = highValue || elevatedRisk ? Action.REVIEW : Action.AUTHORIZE;
        String reason = elevatedRisk ? "risk_score" : highValue ? "amount_threshold" : "policy_clear";
        AuditNotification audit = new AuditNotification(payment.paymentId(), action, reason, payment.occurredAt());

        List<Map<String, Object>> points = List.of(
                metric("payments.received", 1, payment),
                metric("payments.amount_minor", payment.amountMinor(), payment),
                metric(action == Action.REVIEW ? "payments.reviewed" : "payments.authorized", 1, payment));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("payment_id", payment.paymentId());
        event.put("account_id", payment.accountId());
        event.put("amount_minor", payment.amountMinor());
        event.put("currency", payment.currency());
        event.put("risk_score", payment.riskScore());
        event.put("action", action.name().toLowerCase());
        event.put("reason", reason);
        event.put("occurred_at", payment.occurredAt().toString());
        return new Decision(action, audit, points, Map.copyOf(event));
    }

    private static Map<String, Object> metric(String name, long value, PaymentEvent payment) {
        return Map.of("name", name, "value", value, "timestamp", payment.occurredAt().toString(),
                "tags", Map.of("currency", payment.currency(), "account_id", payment.accountId()));
    }
}

