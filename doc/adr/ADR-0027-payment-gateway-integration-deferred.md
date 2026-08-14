# ADR-0027: Real payment gateway integration — options researched, decision deferred

**Status**: Accepted (current behavior unchanged — simulated processors remain; this ADR records researched options for when real integration is picked up)
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

payment-service's `PaymentProcessor` Strategy pattern (5 implementations matching Notes.md's payment methods) currently uses `AbstractSimulatedProcessor` for all methods — deterministic, always-succeeds, no real gateway call, documented as deferred scope since building it needs real sandbox credentials this project doesn't have. Asked to research real integration options and record the current decision; the user's explicit choice was to continue with the current (simulated) design rather than commit to a gateway now.

## Two design changes any real gateway forces, independent of which one is chosen

1. **Client-side tokenization is mandatory, not optional.** "The client-side JavaScript collects card data and exchanges it directly with the gateway for a token... the application receives only the token, never the raw card data... this architecture ensures your server never directly handles sensitive cardholder data, significantly reducing PCI compliance obligations." ([Payblox — PCI compliance requirements for payment gateways](https://payblox.com/blog/pci-compliance-requirements-for-payment-gateways/)) Consequence: the UI (Phase 8) must perform tokenization (Stripe Elements / Razorpay Checkout.js); `POST /payments` receives a token, never a card number.
2. **Confirmation is asynchronous via webhook, not the current synchronous `pay()` return.** "Always verify payment amounts and statuses on your server... verify that signature checks reject tampered payloads, duplicate events do not double-process." ([Fyrosoft — Payment Gateway Integration Guide 2026](https://fyrosofttech.com/blog/payment-gateway-integration-guide-2026/)) Consequence: `PaymentProcessor.pay()` splits into an **initiate** step (returns pending + client secret/redirect) and a **webhook handler** that performs the terminal-state transition and the ADR-0007 outbox publish — that publish moves out of the initiate path entirely.

Both integrate cleanly with what's already built: ADR-0024's idempotency keys map directly onto Stripe/Razorpay's own idempotency-key support, and the webhook-triggered outbox publish is the same pattern already used by the simulated processors — this is a real design change but not a foundational rework.

## Options Considered (gateway choice)

| Option | Pros | Cons |
|---|---|---|
| Stripe only | "Stripe's API is the benchmark, with webhooks and idempotency keys as first-class features" ([Fyrosoft](https://fyrosofttech.com/blog/payment-gateway-integration-guide-2026/)) | Does not natively support UPI or India netbanking — 3 of this project's 5 `PaymentMethod` values would stay unaddressed |
| Razorpay only | Covers UPI/NetBanking/QR plus cards for an India-primary base | Weaker fit for non-India card customers; "webhook reliability has been an occasional issue... reconciliation jobs catch missed webhooks more often than with Stripe" ([Fyrosoft](https://fyrosofttech.com/blog/payment-gateway-integration-guide-2026/)) |
| Stripe (cards) + Razorpay (UPI/NetBanking/QR) | Matches the Strategy pattern already built — one real gateway per method-class; the documented standard pairing is "Razorpay for Indian transactions and Stripe for international" | Two gateways' worth of credentials, webhook endpoints, and reconciliation logic to build and operate |
| Continue simulated (current decision) | Zero new infrastructure, zero real-money risk while the rest of the platform (auth, eventing) is still being built | No real payment processing exists — acceptable now, not acceptable for anything beyond this build-out |

## Decision

**Continue with simulated processors** — no gateway integration begins now. This ADR exists so the research (options, evidence, and the two mandatory design changes) isn't lost by the time this is revisited, and so the eventual choice starts from evidence rather than a cold restart.

## Consequences

- Positive: no premature commitment to gateway credentials/contracts while auth (Phase 4) and the rest of the saga are still being built; the eventual integration work is scoped and evidenced in advance.
- Negative / accepted trade-off: no real payment processing exists in this platform today — explicitly acceptable at this stage, not a gap to paper over.
- Follow-up required: when real integration is picked up, revisit gateway choice using the options above; implement the initiate/webhook split in `PaymentProcessor` and payment-service's controller/service layer; add client-side tokenization to the UI (Phase 8).

## Related

- `PaymentProcessor` javadoc (`payment-service/src/main/java/org/bgm/paymentservice/service/strategy/PaymentProcessor.java`) — the original in-code deferral note this ADR formalizes
- ADR-0007 (saga/outbox — where the webhook-triggered publish fits), ADR-0024 (idempotency — maps onto gateway idempotency keys)
