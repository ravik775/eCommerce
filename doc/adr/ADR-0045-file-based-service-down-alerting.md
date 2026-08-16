# ADR-0045: File-based ServiceDown alerting via Alertmanager

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

Prometheus/Grafana dashboards existed (Phase 7, ADR-0032-adjacent work) but nothing pushed anywhere — a genuinely pull-based setup where a real outage was only visible to someone actively looking at a dashboard. An architecture review this session named this gap explicitly; this ADR records the decision made to close it.

## Options Considered

| Option | New infrastructure? | Fit |
|---|---|---|
| No alerting, dashboards only (status quo) | None | Rejected — the actual gap being closed |
| Alertmanager → Slack/email/PagerDuty | Alertmanager + an external integration (webhook credentials, SMTP relay, or a PagerDuty account) | Rejected for now: no such integration exists in this project for anything, and standing one up purely for this is disproportionate to a 20-active-user internal tool with no on-call rotation |
| Alertmanager → a file, tailed manually | Alertmanager (one new Deployment) + a small custom webhook receiver (no existing "write Alertmanager output to a file" receiver type ships built-in) | **Chosen** — smallest thing that makes an alert genuinely *push* somewhere instead of only being pull-observable, matching this project's consistent "smallest infrastructure for the actual need" bias |

## Decision

`k8s/base/alertmanager.yaml`: a `ServiceDown` Prometheus alert rule (`up == 0` for 2 minutes — the 2-minute threshold matches this environment's own routine readiness-probe delays, so a normal rolling redeploy doesn't self-alert), routed through Alertmanager to a small dependency-free Node.js webhook receiver running as a sidecar in the same pod, appending each firing/resolved alert to a file on a dedicated PersistentVolumeClaim. The receiver also serves a plain `GET /` returning the file's current contents — deliberately not requiring `kubectl exec` to read (found this session's own `kubectl exec` was broken in this local Docker Desktop cluster; `kubectl port-forward` + `curl` works regardless).

`hostPath` was considered and rejected: it would make the log directly visible on the host filesystem, but the `ecom` namespace enforces the Kubernetes `restricted` Pod Security Standard (ADR-0020), which disallows `hostPath` volumes outright. A PVC is the `restricted`-compliant equivalent.

## Regression guard

No unit test — this is fundamentally a live-infrastructure behavior (a Prometheus rule firing, Alertmanager routing, an HTTP webhook actually being called) that doesn't reduce meaningfully to a unit-testable pure function. The regression check that verified this works, and should be re-run after any change to `k8s/base/alertmanager.yaml` or `k8s/base/prometheus.yaml`'s `rule_files`/`alerting` blocks:

1. `kubectl scale deployment/<any-service> -n ecom --replicas=0`.
2. Wait ~2 minutes (the rule's `for:` duration).
3. Confirm the alert appears in Prometheus's own `/api/v1/alerts` as `state: firing`.
4. Confirm it appears in Alertmanager's `/api/v2/alerts`.
5. Confirm it lands in the file (`kubectl port-forward deploy/alertmanager 9095:9095` then `curl localhost:9095/`).
6. `kubectl scale ... --replicas=1` to restore the service.

This exact sequence was run live this session (against `notification-service`) to validate the implementation before it was considered done — steps 3–5 all passed, entries visible with correct timestamps and alert metadata.

## Consequences

- Positive: an outage is now visible without anyone actively watching a dashboard — a human checking the file (or a future automated tail-and-page script) catches it instead of a dashboard nobody happened to be looking at.
- Negative / accepted trade-off: "monitored manually" is explicitly not the same as paging — there is no on-call rotation, no SLA, and no guarantee anyone reads the file promptly. Acceptable for the stated scale (20 active internal users, no external commitment) per the same reasoning as ADR-0034/ADR-0041; would need real paging (Slack/PagerDuty) the moment an external SLA exists.
- Follow-up required: if this system's user base or commitments grow past the internal-tool scope this ADR (and ADR-0036/ADR-0041) assume, revisit toward a real paging integration rather than scaling the file-tail approach further.

## Related

- Related: ADR-0020 (Pod Security Standard `restricted` — why `hostPath` was rejected here), ADR-0034 (backup/replication posture — same "smallest infra for actual need" reasoning applied to a different gap), ADR-0036 (capacity/scale assumptions this ADR's "monitored manually is acceptable" judgment rests on)
