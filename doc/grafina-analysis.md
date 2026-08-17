Resolved for a concrete walkthrough: **order 69**, correlationId `25b9fbc0-438b-4947-8c46-d6deeac854a3`, real OTel traceId `baf801abbf87dcca98800aeeaceb7ac0` (from order-service's sync leg).

Important reality check before the steps: **there is no single Tempo trace spanning order/payment/notification-service** — the saga is Kafka/RabbitMQ-driven (choreography, ADR-0007), and per ADR-0052/0043 those async hops don't carry real OTel span context. So "view the trace inside payment-service" and "inside notification-service" mean something different from Tempo's usual single-trace view. Here's how to actually see everything, accurately:

---

## 1. Login to Grafana

1. Open `http://localhost:3000` (make sure the port-forward is running: `kubectl port-forward -n ecom svc/grafana 3000:3000`).
2. Log in with the Grafana admin credentials.

## 2. View order-service's trace in Tempo

Order-service is the only one of the three with a real synchronous span tree (gateway → order-service HTTP call).

1. Left sidebar → **Explore**.
2. Top-left datasource dropdown → select **Tempo**.
3. Click the **TraceQL** tab (not Search).
4. Paste: `{ trace:id = "baf801abbf87dcca98800aeeaceb7ac0" }` and hit **Run query** — or just paste the raw trace ID `baf801abbf87dcca98800aeeaceb7ac0` directly into the query box, Grafana's Tempo datasource accepts a bare trace ID too.
5. Click the resulting trace row → opens the **span tree**: `http post /orders` (root) → Spring Security filter-chain spans → `secured request` (shows `force_trace` if this order used forced tracing).
6. With ADR-0053 now live, expand any span → **Span Attributes** panel on the right shows `correlationId`, `orderId`, `appTraceId` directly on the span (this is the fix from earlier — confirm you see `orderId: 69` there).

**This is genuinely the "trace inside order-service."**

## 3. "View payment-service trace" — what to actually do

Payment-service ran on a Kafka-consumer thread with no inherited span context, so it has **its own separate, unrelated trace ID** (or may not be sampled/exported to Tempo at all, per the 10% success-sample-rate — see the sampling gap noted earlier). There is no `baf801...` span for payment-service to find.

1. Still in Explore → Tempo → **Search** tab (not TraceQL this time).
2. **Service Name** → select `payment-service`.
3. **Tags** row → tag `orderId`, operator `=`, value `69` (available now because of ADR-0053's enrichment — this is the correct way to find it).
4. Set the time range (top-right) to bracket when the order was placed (06:54 UTC in this example — give yourself a few minutes' margin).
5. Run — if a matching span exists, click it to open its own, independent span tree (payment-service's outbound call to order-service via `OrderServiceClient`, if that leg was sampled/exported).
6. **If nothing comes back**: that's the known sampling gap, not a UI mistake — payment-service's spans for this specific request may not have been exported to Tempo at all (10% success sample rate, and `force_trace` doesn't currently propagate through Kafka to force it). This is the honest current limitation, documented as a follow-up in ADR-0052.

## 4. "View notification-service trace" — same caveat, and likely nothing to find

Notification-service's actual work (`NotificationDispatchWorker`) runs on a RabbitMQ listener thread. Check the same way — **Search** tab, Service Name = `notification-service`, Tag `orderId=69` — but realistically, **there will be no span at all**, because nothing in this codebase currently starts an OTel span for RabbitMQ consumption (only Kafka/HTTP paths are auto-instrumented). ADR-0054 fixed the **log correlation** (MDC), not span creation — those are separate concerns.

## 5. The view that actually shows the complete cross-service trail today

Since Tempo can't unify all three services into one trace, **Loki is the real "complete trace" view** for this saga:

1. Explore → datasource dropdown → **Loki**.
2. Query:
   ```
   {app=~"order-service|inventory-service|payment-service|notification-service"} |= "orderId=69"
   ```
3. Sort ascending (clock icon near the query) to see the true chronological handoff: order created → inventory reserved → payment succeeded → notification queued → notification dispatched.
4. Each line carries `correlationId=25b9fbc0...`, `appTraceId=8000bc12...`, `orderId=69` — this is the value that's genuinely consistent across **all four** services (verified via `outbox_event.trace_id` too), unlike Tempo's per-service, independently-sampled spans.

**Bottom line:** Tempo gives you a real hierarchical trace only for order-service's synchronous leg; Loki (using `appTraceId`/`orderId`) is currently the only place you get the true end-to-end saga view across all four services. If you want the payment/notification legs to show up as real Tempo spans too, that's a separate, larger piece of work (OTel context propagation through Kafka/RabbitMQ) — want me to scope that as a follow-up?