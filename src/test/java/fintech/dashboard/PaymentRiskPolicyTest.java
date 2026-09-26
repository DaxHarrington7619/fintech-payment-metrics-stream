package fintech.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class PaymentRiskPolicyTest {
    public static void main(String[] args) throws Exception {
        List<PaymentRiskPolicy.Decision> delivered = new ArrayList<>();
        PaymentMetricSink sink = (payment, decision) -> delivered.add(decision);
        PaymentWorkflow workflow = new PaymentWorkflow(new PaymentRiskPolicy(500_000), sink);

        PaymentRiskPolicy.PaymentEvent payment = new PaymentRiskPolicy.PaymentEvent(
                "pay_2048", "merchant_17", 125_00, "USD", 82, Instant.parse("2026-09-24T10:15:30Z"));
        PaymentRiskPolicy.Decision decision = workflow.accept(payment);

        assert decision.action() == PaymentRiskPolicy.Action.REVIEW : "elevated risk must require review";
        assert decision.audit().reason().equals("risk_score") : "audit reason must identify the policy branch";
        assert decision.dashboardEvent().get("action").equals("review") : "dashboard must receive the decision";
        assert decision.metricPoints().stream().anyMatch(p -> p.get("name").equals("payments.reviewed"));
        assert delivered.size() == 1 : "one accepted payment produces one handoff";
        System.out.println("PASS risk score 82 -> review, audit reason risk_score, dashboard event emitted");
    }
}

