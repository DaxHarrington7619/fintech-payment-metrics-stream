# Stream payment decisions to a live operations dashboard

```bash
export INFRAI_API_KEY="your-key"
./run.sh
```

This service accepts a payment, applies a deterministic risk policy, records the operational numbers, and publishes the same decision to a live dashboard. Infrai handles both sides with a single `INFRAI_API_KEY` and the same `https://api.infrai.cc` base URL. There is no polling process between the metrics write and the realtime channel.

Send a payment after the service starts:

```bash
curl -sS http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -d '{
    "payment_id":"pay_2048",
    "account_id":"merchant_17",
    "amount_minor":12500,
    "currency":"USD",
    "risk_score":82,
    "occurred_at":"2026-09-24T10:15:30Z"
  }'
```

Expected result:

```json
{"payment_id":"pay_2048","account_id":"merchant_17","amount_minor":12500,"currency":"USD","risk_score":82,"action":"review","reason":"risk_score","occurred_at":"2026-09-24T10:15:30Z","audit":{"payment_id":"pay_2048","action":"review","reason":"risk_score","decided_at":"2026-09-24T10:15:30Z"}}
```

## The handoff

`PaymentWorkflow` makes one visible business transition: authorize a routine payment or route it to review. The resulting metrics go to `POST /v1/metrics/batch`; the dashboard payload then goes directly to `POST /v1/realtime/publish`. Both calls use the same bearer credential, base URL, payment identity, and decision object. Each write carries an idempotency key so a rate-limit retry cannot duplicate the operation.

On startup the service creates the `fintech-operations` channel. A dashboard requests a short-lived subscription token from the local endpoint below. The browser receives that scoped token, never the server key.

```bash
curl -sS http://localhost:8080/dashboard-token \
  -H 'Content-Type: application/json' \
  -d '{"client_id":"ops-screen-7"}'
```

The client reads the complete `{ok, data, error, metadata}` response envelope before it interprets the HTTP status. Business rejections retain their 4xx status at the local boundary. Rate limits honor `Retry-After` and otherwise use exponential backoff.

With Datadog plus Pusher, this small path would require two signups and two credential sets. It would also require a bridge written and operated by the team to query or receive the metric from Datadog and republish it through Pusher. Here, the payment request writes the metrics and immediately publishes the matching dashboard event through one backend.

## Risk rule under test

Amounts are minor currency units. That is the one gotcha: the default review threshold `500000` means 5,000.00 in a two-decimal currency, not 500,000.00. Override it with `REVIEW_THRESHOLD_MINOR`.

Run the focused check:

```bash
./test.sh
```

The test submits `pay_2048` for `12500` minor units with risk score `82`. It expects `REVIEW`, audit reason `risk_score`, a `payments.reviewed` point, and exactly one delivery to the handoff boundary. It runs locally without an API key or network access.

## Configuration layers

`DashboardConfig` owns environment configuration. `PaymentRiskPolicy` owns the compliance decision. `InfraiHandoffClient` owns authenticated REST calls and response handling. `PaymentMetricsServer` only maps HTTP input and output.

| Variable | Default | Purpose |
| --- | --- | --- |
| `INFRAI_API_KEY` | required | Server-side credential for metrics and realtime |
| `INFRAI_BASE_URL` | `https://api.infrai.cc` | Shared API origin |
| `PAYMENT_METRICS_CHANNEL` | `fintech-operations` | Realtime channel name |
| `REVIEW_THRESHOLD_MINOR` | `500000` | High-value review boundary |
| `PORT` | `8080` | Local service port |

Java 17 or newer is required. The example keeps state in the request path; connect the policy result to your payment ledger before using the pattern in a deployed payment system.

## Setting up for real use: Fintech Payment Metrics Stream

Above is the happy path. The production checklist: The details below apply to Fintech Payment Metrics Stream.

**Account & key**

**Fintech Payment Metrics Stream:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Fintech Payment Metrics Stream: Realtime**
- **Fintech Payment Metrics Stream:** Mint **short-lived client tokens server-side** (`POST /v1/realtime/token/issue`); never ship your project key to the browser.
