# Phase 6 — Kubernetes Security & Connectivity Test Log

Live verification commands and results for the `ecom` namespace manifests (`k8s/base/`, `k8s/overlays/local/`), run against Docker Desktop's local Kubernetes. This is a record of what was actually run and observed — not a test plan for hypothetical future execution. Re-run these commands verbatim to reproduce or re-verify after a manifest change.

## Environment note: `kubectl exec` is broken on this cluster

After a Docker Desktop crash/reset mid-session, `kubectl exec` and `kubectl cp` fail with:
```
error: Internal error occurred: error sending request: Post "//[::]:PORT/cri/exec/...": http: server gave HTTP response to HTTPS client
```
`kubectl logs` and `kubectl port-forward` are unaffected (different streaming path) and were used as workarounds throughout. If `kubectl exec` starts working again, prefer it directly for future ad-hoc checks — the workarounds below (self-contained test pods whose main process does the check and reports via `kubectl logs`) exist only because of this.

## 1. Cluster bring-up

```bash
kubectl apply -f <(kubectl kustomize k8s/overlays/local --load-restrictor=LoadRestrictionsNone)
kubectl get pods -n ecom
```
**Result**: 9/9 pods `Running` (api-gateway, user/order/catalog/inventory/payment/notification-service, rabbitmq, redis) — `replicas: 1` each, per the ResourceQuota/LimitRange in `k8s/base/resource-limits.yaml`.

## 2. Service DNS resolution (indirect — `exec` unavailable)

Verified via real application traffic rather than a direct `getent hosts` check:
- `kubectl logs -n ecom deploy/notification-service` shows successful RabbitMQ connections to `rabbitmq` (Service DNS) after startup.
- A full authenticated request through the gateway (§4 below) proves the gateway's Spring Cloud Gateway routes resolve `catalog-service` (Service DNS) correctly — no `lb://` / Eureka involved (ADR-0008), routes point directly at `http://catalog-service:8083` etc. via `k8s/base/configmap-gateway-routes.yaml`.

## 3. NetworkPolicy — real finding: **not enforced on this cluster**

```bash
cat <<'EOF' | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: netpol-test
  namespace: ecom
  labels: { app: netpol-test }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 1000, seccompProfile: { type: RuntimeDefault } }
  restartPolicy: Never
  containers:
    - name: test
      image: curlimages/curl:8.10.1
      command: ["sh", "-c", "exec 2>&1; echo START; timeout 5 curl -s --connect-timeout 3 http://redis:6379/ ; echo \"redis exit=$?\"; timeout 5 curl -s --connect-timeout 3 http://rabbitmq:15672/ ; echo \"rabbitmq exit=$?\"; echo DONE"]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
      resources: { requests: { cpu: 50m, memory: 32Mi }, limits: { cpu: 100m, memory: 64Mi } }
EOF
kubectl logs netpol-test -n ecom
kubectl delete pod netpol-test -n ecom
```

**Expected** (per `k8s/base/networkpolicy.yaml`): `netpol-test` has no matching allow-rule in `redis-policy` (only `api-gateway` is allowed) or an equivalent for RabbitMQ's management port — both connections should be blocked.

**Actual result**: both connections **succeeded**. `redis` returned curl exit 52 (empty reply — TCP connected fine, just not an HTTP-speaking server, i.e. RESP protocol). `rabbitmq:15672` returned the full Management UI HTML with exit 0.

**Root cause**: Docker Desktop's built-in Kubernetes has no NetworkPolicy-enforcing CNI installed:
```bash
kubectl get pods -n kube-system
# coredns, etcd, kube-apiserver, kube-controller-manager, kube-proxy,
# kube-scheduler, storage-provisioner, vpnkit-controller — no Calico,
# Cilium, Flannel, or Weave pod anywhere.
```
`NetworkPolicy` objects are accepted by the API server and stored in etcd (`kubectl get networkpolicy -n ecom` lists all 11 correctly), but nothing reads them and programs actual `iptables`/eBPF rules — `kube-proxy` alone does not implement `NetworkPolicy`. **The manifests are correct** and would enforce real least-privilege on any cluster with a NetworkPolicy-capable CNI (GKE/EKS/AKS default addons, or self-managed Calico/Cilium) — this is a Docker-Desktop-specific gap, not a manifest bug.

**Follow-up, not done this session**: install Calico (`kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/.../calico.yaml`) on this cluster to actually enforce and re-verify the block, if genuinely needed for local testing confidence — otherwise this is safe to defer to the real deployment target, which will run on a cluster with NetworkPolicy support by default (a documented requirement of ADR-0006).

## 4. OIDC login + role-based access through the K8s gateway

```bash
kubectl port-forward -n ecom svc/api-gateway 8080:8080 &
```
**Note**: must use local port `8080` to match — Keycloak's `ecommerce-gateway` client's Valid Redirect URIs whitelist doesn't include arbitrary ports (found live: port-forwarding to `18080` first produced `Invalid parameter: redirect_uri` from Keycloak).

Full Authorization Code + PKCE browser-equivalent flow (see `scripts/` session history for the `login()` helper function), then:

| Check | Expected | Actual |
|---|---|---|
| Unauthenticated `GET /catalog/products` | 302 → Keycloak | **302** |
| `customer1` → `POST /catalog/products` | 403 (RequireRole=ADMIN gate) | **403** |
| `admin1` → `POST /catalog/products` | 201 | **201** |

Confirms the gateway pod, Keycloak (standalone container, reachable via `host.docker.internal` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`), and the `RequireRoleGatewayFilterFactory` all work correctly inside the K8s topology — same mechanism verified in Phase 4/5, now proven again through K8s Service DNS routing to `catalog-service`.

## 5. Resource limits — real incidents found and fixed live

| Component | Issue found | Fix |
|---|---|---|
| Every app service | `server.port` silently fell back to Spring Boot's default `8080` (config-server, which normally supplies it, is disabled per ADR-0008) — Deployment/Service `targetPort` (8081–8087) never matched what the JVM actually listened on, causing permanent readiness/liveness probe failures | Explicit `SERVER_PORT` env var added per service |
| RabbitMQ | `256Mi` then `512Mi` limit both `OOMKilled` (exit 137) the pod on boot | Root cause: RabbitMQ's *default relative* memory watermark is documented as unsafe in containers (rabbitmq.com/docs/memory) — fixed with an absolute watermark (`vm_memory_high_watermark_absolute "700MB"`) + `requests == limits` at `1Gi`/`500m` (RabbitMQ's own K8s Guaranteed-QoS guidance) |
| RabbitMQ | Readiness probe `rabbitmq-diagnostics -q ping` timed out under the container's CPU limit — default `timeoutSeconds: 1` too short | `timeoutSeconds: 8` |
| Kafka (standalone container, not in K8s — see below) | No resource limits at all originally | `--memory=1536m --cpus=0.75`, validated against 2026 Strimzi/Red Hat KRaft sizing guidance (controller-only 500m/1Gi, broker-only 1000m/2Gi; this process combines both roles) |

Namespace-wide guardrails: `k8s/base/resource-limits.yaml` — `ResourceQuota` (4 CPU/4Gi requests, 8 CPU/8Gi limits, 30 pods) + `LimitRange` (default 250m/256Mi per container, max 1 CPU/1Gi).

## 6. Local-dev deviation: Postgres, Kafka, Keycloak run outside K8s

Documented in `k8s/base/configmap-common.yaml` and `k8s/base/kustomization.yaml`. Real resource-exhaustion incident (Docker Desktop's engine crashed with 13 Compose containers + 12 unthrottled K8s pods running simultaneously) drove moving these three to standalone Docker containers, reachable via `host.docker.internal`. NetworkPolicy egress to them uses an `ipBlock` rule (`192.168.65.254/32`, this machine's Docker Desktop host-gateway IP — confirmed via `docker run --rm alpine getent hosts host.docker.internal`) rather than a `podSelector`, since they're not pods. This is explicitly a local-only override — ADR-0022 (Postgres in-cluster) still holds for the real deployment target; `postgres.yaml`/`kafka.yaml`/`keycloak.yaml` remain in `k8s/base/` unreferenced by the local kustomization, ready to re-enable.

**Gotcha hit while starting these**: Git Bash on Windows mangles `-v host:container` Docker volume-mount arguments (auto path-conversion turns `/opt/keycloak/data/import` into a Windows path and corrupts the source path with a stray `;C`). Fixed by prefixing `MSYS_NO_PATHCONV=1` before any `docker run -v ...` command run from Git Bash — without it, Keycloak's `--import-realm` silently imports nothing (only the built-in `master` realm exists, `ecom` 404s) with no error logged.

## 7. SPIRE infrastructure (Phase 6b) — 7 real bugs found and fixed live

Deployed to the `spire` namespace (`k8s/base/spire/`, `privileged` PSS — deliberately not `restricted` like `ecom`, see that directory's `namespace.yaml` comment). Full bug list and rationale: `doc/adr/ADR-0002-zero-trust-spire-app-level.md`'s "Live deployment verification" section — not duplicated here, but summarized:

1. `k8sbundle` notifier patches, never creates, its target ConfigMap.
2. That ConfigMap can't be a repeatedly-`kubectl apply`'d kustomize resource — it gets reset to empty every time, wiping `spire-server`'s real writes. One-time `kubectl create` bootstrap instead.
3. `bitnami/kubectl:1.31` doesn't exist (deprecated Bitnami tag) — switched to `alpine/k8s:1.31.1`.
4. RBAC gap: `spire-agent`'s ServiceAccount couldn't read ConfigMaps, silently failing its bundle-wait init container.
5. `k8s_psat` node attestation needs an audience-scoped projected ServiceAccount token — not automatic.
6. SPIFFE CSI Driver needs `-node-id`, sourced from the Downward API.
7. CSI driver's own socket path and kubelet's registration path pointed at two different hostPath directories — unified into one.

**Result**: `spire-server` stable (1/1), `spire-agent` successfully node-attested (`spiffe://ecommerce.local/spire/agent/k8s_psat/docker-desktop/...`, serving the Workload API), `spiffe-csi-driver` steady at 2/2 with zero restarts.

```bash
kubectl get pods -n spire
kubectl logs -n spire -l app=spire-agent --tail=20   # look for "Node attestation was successful"
```

**Not yet done** (next checkpoint): `common-lib`'s `spiffe-mtls` module (Java code consuming the Workload API via a CSI ephemeral volume from an `ecom` pod), wiring it into services, then the raw-TLS-rejection and SVID-rotation DoD tests from Phase 6b.

## Related

- `k8s/base/` — the manifests these tests exercise.
- `doc/architecture/07-migration-planning.md`, Phase 6 — DoD checklist this log supports.
- `doc/adr/ADR-0006-k8s-zero-trust.md` — NetworkPolicy design (still correct; enforcement gap is cluster-specific, see §3).
- `doc/adr/ADR-0002-zero-trust-spire-app-level.md` — SPIRE architecture decision + full live-deployment bug list (§7 above summarizes it).
