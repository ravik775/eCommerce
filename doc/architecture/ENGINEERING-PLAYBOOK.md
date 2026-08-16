# Engineering Playbook — TOGAF-Aligned, Resumable, Phase-Independent Product Building

Written from a validated retrospective of this project's own session history (45 ADRs, 28 passing tests, ~9 distinct multi-hour work sessions across security/observability/architecture review). Portable to any similar product-build engagement — copy this file into a new project's `doc/` directory as the starting operating discipline.

## 0. Security standards and industry frameworks — apply these by default, verify they're real

This section exists because a project can *claim* to follow a standard in a doc without actually implementing it — this project's own review found exactly that gap twice (WAF and Trivy scanning were "decided" in an ADR for weeks before either was built). Apply every row below by default on a new build; the "Verify" column is what to check before believing a past claim of compliance, on this project or any inherited one.

| Framework / standard | What it governs | How this project applied it | Verify |
|---|---|---|---|
| **NIST Zero Trust Architecture (SP 800-207)** | No implicit trust from network location alone; verify every request | SPIFFE/SPIRE X.509-SVIDs for workload identity + Kubernetes `NetworkPolicy` as two *independent* layers — a breach requires defeating both, not one control doing double duty | `common-lib`'s SPIFFE auto-config classes are actually wired into every service ([ADR-0002](../adr/ADR-0002-zero-trust-spire-app-level.md), [ADR-0006](../adr/ADR-0006-k8s-zero-trust-layers.md)) — check `SPIFFE_MTLS_ENABLED` is set on every deployment, not just documented as a default |
| **Kubernetes Pod Security Standards (PSS) / Pod Security Admission (PSA)** | Workload-level hardening — what a compromised container can do on its node | `restricted` profile: non-root, no privilege escalation, all capabilities dropped, immutable root filesystem, seccomp `RuntimeDefault` | Don't trust an ADR that says "restricted" — grep every Deployment's `securityContext` block directly. This project's own `readOnlyRootFilesystem` claim ([ADR-0020](../adr/ADR-0020-k8s-pod-security-standards.md)) was unset in practice for weeks despite being "Accepted" |
| **CIS Kubernetes Benchmark** | Cluster and workload configuration hardening, the operational counterpart to PSS | Namespace-scoped `ResourceQuota`/`LimitRange` (noisy-neighbor/DoS mitigation), least-privilege RBAC on infrastructure components themselves (not just app workloads — `ingress-nginx`'s own `ClusterRole` grants only the verbs it needs) | Check infra-component RBAC the same way you'd check an application's — a WAF or ingress controller with cluster-admin is a bigger blast radius than most app-level bugs |
| **OWASP Top 10** | The standard baseline of common web application attack classes (injection, broken auth, XSS, etc.) | Edge coverage via ModSecurity + OWASP Core Rule Set on the ingress controller ([ADR-0013](../adr/ADR-0013-edge-waf-modsecurity.md)); SAST (CodeQL) as the code-level counterpart | A WAF is defense-in-depth, not a substitute for fixing the underlying code path — treat a CRS rule match as a signal to also check the application layer, not just an edge-layer "solved" |
| **OWASP ASVS-aligned CI gates** (SAST/SCA/secret-scanning/DAST) | Shift-left detection of vulnerable code, vulnerable dependencies, leaked secrets, and runtime findings | CodeQL (SAST), OWASP Dependency-Check (SCA, fails build at CVSS ≥ 8), gitleaks (secret scanning, working-tree scoped), OWASP ZAP baseline (DAST, advisory until a real auth boundary exists to scan meaningfully) — [ADR-0010](../adr/ADR-0010-cicd-pipeline-sast-dast.md) | Confirm these actually run in CI (open the workflow file), and confirm they're reproducible locally (`scripts/run-sast-dast-local.sh` pattern) — a security gate only the CI runner can execute is a slower feedback loop than it needs to be |
| **Software supply-chain integrity** (SLSA-adjacent practice) | Trusting the third-party actions/images/dependencies your own pipeline pulls in | Container image CVE scanning (Trivy) in the same CI pipeline as source-code scanning; **pin any security-tooling GitHub Action to a full commit SHA, not a floating version tag** | This project's own `trivy-action` was a real, dated (March 2026) supply-chain compromise affecting every tag from `0.0.1` through `0.34.2` — a floating-tag pin would have silently pulled the compromised version. Verify the exact commit via the tool's own release page/API before pinning, don't guess a SHA |
| **PCI-DSS scope minimization** | Reducing audit/compliance burden for any payment-card-adjacent system | Client-side tokenization decided *before* any real payment gateway integration begins — the application server is architected to never receive raw card data at all ([ADR-0027](../adr/ADR-0027-payment-gateway-integration-deferred.md)) | Check this before integration work starts, not after — retrofitting tokenization onto a server that already handles raw card data is a much larger change than deciding it up front |
| **SOC 2 Trust Services Criteria** | Security, Availability, Processing Integrity, Confidentiality, Privacy — the standard framework enterprise customers ask about | An honest current-state mapping against all five categories, naming real gaps (no RTO/RPO, no data-subject-rights process) exactly as clearly as what's real ([ADR-0037](../adr/ADR-0037-soc2-trust-services-criteria-mapping.md)) | **Never claim compliance without a certification.** A control-mapping document is not a SOC 2 report — say so explicitly in the document itself, every time, so it can't be mistaken for a compliance claim later |
| **OWASP-aligned OIDC/OAuth2 hardening** | Preventing authorization-code interception and token-handling weaknesses | PKCE mandatory even for a confidential client ([ADR-0017](../adr/ADR-0017-oidc-pkce-public-client.md)); JWT validated independently by *every* service, not just the edge gateway (defense-in-depth, not trust-the-perimeter) — [ADR-0025](../adr/ADR-0025-jwt-rbac-method-security.md) | Check that RBAC role checks live at the method/endpoint level in each service, not only in a central gateway filter that could be bypassed by any direct service-to-service path |
| **PII/data minimization principles** (GDPR/CCPA-adjacent, not a certification claim) | Not leaking personal data into logs, error messages, or telemetry | Structural redaction by field-name pattern in the shared audit logger, not reliance on every caller being careful; a confirmed real leak (an email embedded in an HTTP error body, also an account-enumeration oracle) found and fixed with a regression test | Audit every log/audit/exception-message call site for PII by grepping for field names (`email`, `phone`, `address`, `card`, `token`) across the whole codebase — don't assume "we don't log PII" without checking, since this project found one real leak specifically by checking |
| **Secrets management** | Never committing plaintext credentials, even in an internal repo | Sealed Secrets (ciphertext committed, decryptable only by the in-cluster controller's private key) + secret-scanning CI gate | A `SealedSecret`'s field *names* are visible in plaintext even though values are encrypted — useful for auditing what secrets exist without needing decrypt access, but don't assume an encrypted-looking file has no information disclosure risk at all |

## 1. Before touching code: one Discovery Pass, not many

**Rule**: at the start of any multi-part request ("evaluate the architecture," "fix all gaps," "build phase N"), spend the first pass purely on inventory — no fixes, no new files. Answer these in one batch of parallel reads/searches, not as they come up piecemeal:

- What ADRs/decision records already exist, and what do they *actually* claim (not what a related doc assumes they claim)?
- What test coverage genuinely exists (`find *Test.java`, not "the testing doc says X")?
- What's actually deployed/running vs. documented as a decision but never implemented?
- What CI/tooling is real (open the workflow file) vs. aspirational (a doc describing a "target" pipeline)?

**Validated cost of skipping this**: this session re-discovered project facts across roughly six separate research rounds (three parallel sub-agents for the architecture review, then repeated ad-hoc `grep`s for ADR numbering, test files, resource quotas, secret key names) instead of one upfront sweep. A single inventory pass costs one batch of tool calls; discovering the same facts reactively costs one small tool call *each time*, repeated across the session.

## 2. TOGAF ADM discipline — non-negotiable, not decorative

- **Every non-trivial technology or design decision gets a written ADR before or immediately after implementation** — Context, Options Considered, Evidence (cited, with a confidence-honesty statement — "general practice" vs. "verified source"), Decision, Consequences (Positive / Negative-accepted-trade-off / Follow-up-required).
- **A decision's ADR is the source of truth for "is this closed."** If an ADR says "Decision: X" and the codebase doesn't do X, that is an open defect, not a documentation nuance — treat "ADR says X, code does Y" as a P1 finding, not a footnote.
- **Amendments, not silent edits.** When a decision changes, add a dated update note to the existing ADR (`**Status**: Accepted — closed 2026-08-16`, then a `## 2026-08-16 update` section) rather than rewriting history. This project's ADR-0019/0020/0013 updates are the validated pattern — each still shows its original Decision text plus what actually changed and when.
- **TOGAF phase mapping stays visible.** Keep one index document (`doc/architecture/README.md` in this project) mapping every ADR and architecture doc to its ADM phase (Preliminary, A–H). A reviewer — human or AI — should be able to answer "where are we in the ADM cycle" from one file, not by inference.

## 3. Never override a fix without checking first — the specific discipline, not just the instruction

This session's explicit standing rule ("ensure previous fixes are not overwritten") only worked because it was checked mechanically, not just remembered:

1. **Before editing any file touched in a prior session/turn, `git status` and `git diff` it first.** Don't assume memory of what changed — verify.
2. **Before writing a new ADR or fix, search for an existing one on the same topic** (`grep -rl "<keyword>" doc/adr/`) — extend/amend it if found, don't fork a duplicate decision record.
3. **Before committing, diff the full staged set against what you intended to touch** — this session caught zero accidental overwrites specifically because every commit's `git status --short` was checked against the list of files the current turn was supposed to touch before staging.
4. **Regression tests are the durable form of "don't override this fix."** A comment saying "don't change this" is advice; a test asserting the specific value (a filter's `getOrder()`, a redaction predicate, an HTTP status code path) is enforcement. This session wrote 11 regression tests specifically because 11 non-trivial fixes had shipped without them earlier in the same session — write the test in the same turn as the fix, not retroactively.

## 4. Track work so any session/tool can resume it — three layers, not one

A single "memory" isn't enough; use all three, because they answer different resumption questions:

| Layer | Answers | Mechanism used this session |
|---|---|---|
| **What decisions were made, and why** | "Why does the code do this?" | ADRs (durable, version-controlled, survives any tool/session change) |
| **What's in flight right now** | "What was I in the middle of?" | A task list (`TaskCreate`/`TaskUpdate` equivalent) — used inconsistently this session; should be used from turn one on any multi-part request, not added partway through |
| **What's proven to work vs. still theoretical** | "Has this actually been tested, or just written?" | Live verification notes inline in ADRs ("Verified live: ...") — this session's strongest resumability pattern, since it lets a *different* session/tool trust a claim without re-deriving it |

**Improvement over what this session actually did**: task tracking was reactive (added mid-session when reminded), not proactive. Start every multi-part request by writing the task list first, before the first fix — it becomes the resumable state even if the session is interrupted mid-way, which an ADR alone (written only at the end of a piece of work) does not provide for in-progress items.

## 5. Divide work into independent phases — and mean *independent*

A phase is genuinely independent only if it can be validated and shipped without the others being done. This session's actual phase breakdown (validated as independent because each was separately committed and pushed):

1. **Fix verification** (bug fixes with live/test proof) — no dependency on documentation work.
2. **Documentation-gap closure** (ADRs for decisions that existed only in code/comments) — depends on phase 1 only for *which* fixes need ADRs, not on any other phase.
3. **Implementation-gap closure** (decided-but-never-built items: WAF, Trivy scanning) — independent of phases 1 and 2; could have been done first.
4. **Cross-cutting review artifacts** (README, capacity planning, SOC2 mapping) — depends on 1–3 being *substantially* done, since it summarizes them, but doesn't block them.

**What made this work in practice**: each phase ended in a real commit + push before the next began, so a session interruption at any phase boundary left a fully working, independently-valid state — never a half-finished cross-phase edit. Structure future work the same way: define phase boundaries *before* starting, and treat "commit and validate" as the exit criterion for a phase, not an afterthought at the very end of a multi-hour session.

## 6. Root-cause discipline — read the whole error before hypothesizing

**Validated failure pattern from this session**: three separate incidents (a SPIFFE certificate-expiry cascade, an `ingress-nginx` `readOnlyRootFilesystem` crash loop, a payment-service `PlaceholderResolutionException`) each took 3–5 tool-call round-trips to root-cause, when the full error text — read once, completely, on the first encounter — contained the actual answer each time (`CertificateExpiredException: NotAfter: ...`, `open /etc/nginx/lua/cfg.json: read-only file system`, the exact placeholder name in the stack trace).

**Rule**: on the first error, read the *complete* stack trace / log output before forming a hypothesis — not just the last few lines. Truncated reads are cheaper per call but provably more expensive in aggregate once they trigger a wrong-hypothesis retry loop.

## 7. Background-task polling — use an explicit completion marker from the start

**Validated failure pattern**: two separate polling loops in this session used `[ -s file ]` (file is non-empty) as a "done" signal, which fired early because the file had *some* content (an intermediate log line) before the actual command finished — producing a false "complete" read and requiring a second, correct wait.

**Rule**: always poll for a specific, unambiguous completion marker your own command prints on exit (`echo "test exit: $?"`), never for "file exists" or "file is non-empty." Get this right on the first attempt — it's the same amount of upfront thought either way, but wrong on the first try costs a full extra round-trip.

## 8. Batch independent work aggressively

Whenever multiple pieces of work don't depend on each other's output, issue them together — parallel tool calls, parallel research agents, or a single combined shell command — rather than sequentially. This session's three-parallel-subagent research pass (security posture, PII/observability, HA/backup/capacity, dispatched together) is the validated pattern; the piecemeal `grep` sequences that followed later in the session are the anti-pattern. If you can write the list of "what I need to know" before making the first tool call, that list is your batch.

---

**Honesty note on this playbook itself**: every claim above is grounded in what actually happened in this project's own session transcript (specific counts, specific incidents, specific files) — not generic best-practice advice presented as if it were. Where a recommendation is general industry practice rather than something this session directly validated, treat it with the same "verified vs. general practice" honesty this project's own ADRs require.
