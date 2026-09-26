package fintech.dashboard;

public final class PaymentWorkflow {
    private final PaymentRiskPolicy policy;
    private final PaymentMetricSink sink;

    public PaymentWorkflow(PaymentRiskPolicy policy, PaymentMetricSink sink) {
        this.policy = policy;
        this.sink = sink;
    }

    public PaymentRiskPolicy.Decision accept(PaymentRiskPolicy.PaymentEvent payment) throws Exception {
        PaymentRiskPolicy.Decision decision = policy.evaluate(payment);
        sink.recordAndPublish(payment, decision);
        return decision;
    }
}

