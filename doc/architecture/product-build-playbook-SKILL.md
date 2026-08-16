---
name: product-build-playbook
description: TOGAF-aligned engineering discipline for building or reviewing a product/platform — covers security standards (NIST Zero Trust, Kubernetes Pod Security Standards, CIS Benchmark, OWASP Top 10, SOC 2, PCI-DSS scope, supply-chain SHA-pinning), no-override fix protection, phase-independent delivery, and cross-session resumability. Use this whenever starting a new product/platform build, doing an architecture or security review, resuming multi-session or multi-tool work on an existing codebase, or being asked to "close all gaps"/"fix identified issues" across a system — even if the user doesn't say "playbook" or "TOGAF" explicitly. Also trigger when asked to write ADRs, evaluate architecture against industry standards, or review whether previous fixes still hold.
---

> This is the condensed skill version — the same content packaged as a Claude Code skill at `.claude/skills/product-build-playbook/SKILL.md` (and mirrored personally at `~/.claude/skills/`). For the full reasoning, validated incidents, and evidence behind each rule, see [ENGINEERING-PLAYBOOK.md](ENGINEERING-PLAYBOOK.md) in this same directory.

# Product Build Playbook

A discipline for building or reviewing a product/platform without wasting tool calls, without silently overriding prior fixes, and in a way that any session or tool can pick back up later. Full rationale and validated incidents (specific bugs, specific costs, specific fixes) live in `references/full-playbook.md` alongside this file — read it when you need the "why," not just the "what." If the current repo also has its own `doc/architecture/ENGINEERING-PLAYBOOK.md`, prefer that copy (it may have project-specific amendments); otherwise this skill is self-sufficient. Offer to write a project-local copy into a new repo's `doc/` directory the first time this skill triggers there, so the project gets its own amendable version.

## 0. Security standards — apply by default, verify before trusting a past claim

A project can *claim* a standard in a doc without it being real (this happened twice in the reference project — a WAF and an image-scanning decision both sat "Accepted" in an ADR for weeks before either was actually built). Apply these on a new build; verify them before believing an inherited claim:

- **NIST Zero Trust (SP 800-207)**: no implicit trust from network position — pair workload identity (mTLS/SPIFFE or equivalent) with network segmentation (default-deny NetworkPolicy/security groups) as two *independent* layers, not one control doing both jobs.
- **Kubernetes Pod Security Standards / Pod Security Admission**: `restricted` profile as the default target — non-root, no privilege escalation, all capabilities dropped, immutable (`readOnlyRootFilesystem`) root filesystem, seccomp. Don't trust an ADR saying "restricted" — grep the actual `securityContext` blocks.
- **CIS Benchmark (Kubernetes or cloud-provider equivalent)**: namespace/account resource quotas against noisy-neighbor and exhaustion attacks; least-privilege RBAC on *infrastructure* components too, not just application workloads.
- **OWASP Top 10**: WAF/edge filtering (ModSecurity+CRS or equivalent) is defense-in-depth, not a substitute for fixing the underlying code path — treat an edge-layer catch as a signal to also check the application layer.
- **CI security gates**: SAST + SCA (dependency CVEs) + secret scanning + DAST, all reproducible locally, not only on a CI runner — a security gate you can't run before pushing is a slower feedback loop than it needs to be.
- **Supply-chain integrity**: pin any security-tooling CI action to a full commit SHA, not a floating version tag, when pinning at all is warranted — a real, dated compromise of a popular scanning action (every tag from an old version through a recent one) is exactly the scenario a floating tag doesn't protect against. Verify the exact commit via the tool's own release page/API; never guess a SHA.
- **PCI-DSS scope minimization**: decide client-side tokenization *before* any real payment integration begins, if payments are in scope at all — retrofitting it onto a server that already touches raw card data is a much bigger change.
- **SOC 2 / compliance mapping**: an honest current-state control mapping is not a certification — say so explicitly in the document every time, so it can't be mistaken for a compliance claim later.
- **PII minimization**: structural redaction (by field name, in a shared logging/audit utility) beats relying on every call site being careful — audit log/error/exception call sites for PII field names across the whole codebase at least once; don't assume "we don't log PII" without checking.
- **Secrets management**: sealed/encrypted secrets committed to git plus a secret-scanning CI gate, never plaintext credentials — even in an "internal" repo.

## 1. One discovery pass before the first fix

Before starting any multi-part request ("evaluate the architecture," "fix all gaps," "build phase N"), spend one pass purely on inventory — batched, parallel reads/searches, not fixes yet:

- What decision records already exist, and what do they *actually* say (not what a related doc assumes)?
- What test coverage genuinely exists — check for real test files, not a testing doc's description of a "target" pyramid?
- What's actually deployed/running vs. decided-but-never-built?
- What CI/tooling is real (open the workflow file) vs. aspirational?

Skipping this and discovering facts reactively, one `grep` at a time as each need arises, costs far more tool calls in aggregate than one upfront batch.

## 2. TOGAF ADM discipline — ADRs are the source of truth

- Every non-trivial technology or design decision gets a written ADR — Context, Options Considered, Evidence (cited, with honesty about "general practice" vs. "verified source"), Decision, Consequences (positive / accepted trade-off / follow-up required).
- If an ADR's Decision text and the actual codebase disagree, that's an open defect — treat it as a finding, not a footnote.
- Amend, don't silently rewrite: add a dated update section to the existing ADR when a decision changes or a gap it named gets closed. Keep one index doc mapping every ADR/architecture doc to its ADM phase.

## 3. Never override a fix without checking first

1. `git status`/`git diff` any file before editing it — don't rely on memory of what changed in a prior turn or session.
2. Search for an existing ADR on the same topic before writing a new one; amend, don't fork a duplicate.
3. Diff the full staged set against what you intended to touch before every commit.
4. **Write the regression test in the same turn as the fix**, not retroactively — a comment saying "don't change this" is advice; a test asserting the specific value (an order constant, a redaction predicate, a status-code path) is enforcement that survives a session change even the comment might get edited away.

## 4. Three-layer resumability

| Layer | Answers | Mechanism |
|---|---|---|
| Why | "Why does the code do this?" | ADRs — durable, survives any tool/session change |
| In flight | "What was I in the middle of?" | A task list, created at the *start* of a multi-part request, not added partway through |
| Proven vs. theoretical | "Has this actually been tested, or just written?" | "Verified live: ..." notes inline in ADRs/commits — lets a different session or tool trust a claim without re-deriving it |

## 5. Phase-independent delivery

A phase is genuinely independent only if it can be validated and shipped without the others being finished. Define phase boundaries before starting, and end each phase in a real commit (and push, if the user has authorized pushing) before starting the next — that's the actual exit criterion, not a nice-to-have at the very end of a long session. This way a session interruption at any phase boundary leaves a fully working state, never a half-finished cross-phase edit.

## 6. Root-cause discipline

Read the *complete* error/stack trace/log output on first encounter before forming a hypothesis — not just the last few lines. A truncated read is cheaper per call but provably more expensive once it triggers a wrong-hypothesis retry loop.

## 7. Background-task polling

Poll for an explicit, unambiguous completion marker your own command prints on exit (e.g. an echoed exit code), never for "file exists" or "file is non-empty" — those fire early on partial output and force a second, correct wait.

## 8. Batch independent work

If pieces of work don't depend on each other's output, issue them together — parallel tool calls, parallel subagents, or one combined command — rather than sequentially. If you can write the list of "what I need to know or do" before the first tool call, that list is your batch.
