# ADR-0122 — CI/CD and the supply chain: one signed image per commit, promoted by digest, every production change two-person, every release schema-compatible with the last

Status: Proposed
Date: 2026-11-07
Phase: 15
Context: Platform (build and delivery) · Security (supply chain) · every module (migrations, events)
Rests on: ADR-0005, ADR-0011, ADR-0025, ADR-0026, ADR-0028, ADR-0120, ADR-0121; owner decisions C4, C7, C8 and C11 of
`PHASE_15_PLAN.md` §2.2 (recommended defaults awaiting ratification); `DATA_MIGRATIONS.md` §4.1.

## Context

CI already builds the platform, runs the hermetic, architecture, database and Kafka tiers, scans history for secrets, and
scans the SBOM. Every action is pinned by SHA and every scanner by digest. Nothing in CI builds an image, scans one, runs
static analysis, signs anything, checks manifests or Terraform, runs `promtool`, or deploys. Two further gaps matter.
First, the expand/contract rule (`DATA_MIGRATIONS.md` §4.1) is written down but nothing enforces it, so a rolling deploy
could run release N − 1 against a schema it cannot serve. Second, event payloads are bytes plus a media type, with no
compatibility check between releases.

## Decision

1. **Build once.** Each commit produces the app image and the migrator image, their SBOMs and their scans. The same
   digest moves through `ci`, `staging` and `production`. Nothing is rebuilt for an environment.
2. **Scanning and analysis.** Trivy, already pinned, scans the image (vulnerabilities, secrets, misconfiguration), the
   rendered manifests and the Terraform. HIGH and CRITICAL findings fail the build. Semgrep OSS runs with rules vendored
   in the repository, so no rules are fetched at runtime. Dependabot is extended to base images. Each new tool is pinned
   by digest beside `infra/scanner-pins.sh`. Each check is a script under `infra/scripts/` that also runs on a
   developer's machine.
3. **Signing and admission.** Images are signed with Cosign. The SLSA provenance and the SBOM are attached as
   attestations. The cluster's admission controller refuses an image that is unsigned or comes from an unexpected
   identity. Keyless signing from CI's OIDC identity is the reference. Until a registry and an identity exist, CI proves
   the whole path against a local registry with an ephemeral key, and `kind` proves the refusal.
4. **Delivery is GitOps by pull.** Argo CD syncs environment overlays that are pinned by digest. Promotion is a pull
   request that changes a digest. A production change needs a second person's approval (CODEOWNERS on the production
   overlay) and names the staging evidence for that digest. No CI credential reaches production. The migration Job runs
   as a pre-sync step, before the rollout.
5. **Migration compatibility is enforced.** An **expand-only lint** checks every migration added since the last release.
   It refuses `DROP`, `RENAME`, a type change, and a `NOT NULL` without a default on an existing column, unless the
   migration carries a contract marker that names the release in which its readers stopped. A **schema-version
   readiness guard** stops the application from becoming ready on a schema older than the one it was built for. The
   `kind` rehearsal runs release N − 1 against N's schema before it rolls.
6. **Event compatibility is enforced at build time.** A schema catalogue lists every event type and version. A test
   compares it with the last release's catalogue and allows only additive changes within a major version. There is no
   runtime schema registry while no consumer exists outside the monolith.
7. **Smoke tests never move production money.** The production smoke is read-only: health, build identity and the
   trial-balance gauge. Money flows are exercised only in `ci` and `staging`, against simulated providers.

## Alternatives Considered

### Push deploys from CI
Cons: CI would hold production credentials. It also leaves no declarative record of what runs where.

### A KMS-held signing key
Pros: it does not depend on a public transparency log.
Cons: someone must own the key and its rotation. It stays the alternative if keyless signing is not acceptable to the
owner.

### A runtime schema registry
Cons: it is infrastructure with no consumer to protect. It becomes the decision once an external consumer exists.

### Trusting reviewers to catch destructive migrations
Cons: one missed `DROP` during a rolling deploy is a financial-consistency incident. The lint is cheap. A reviewer can
still add the contract marker deliberately.

## Consequences

Positive: what runs in production can be traced to a commit, a signature, an SBOM and a reviewed change. The window in
which two versions run together is safe by construction.
Negative: CI adds image, rehearsal and analysis jobs, and the `kind` rehearsal takes minutes.
Operational impact: a release is a digest. A rollback is a revert.
Security impact: unsigned or unscanned images cannot run. The supply chain is narrowed from trust on first use to
verified provenance.
Financial impact: protective only. A deploy cannot run code against a schema it cannot serve.

## Invariants / Constraints

ADR-0011: migrations stay forward-only, and no schema rollback exists. ADR-0025 and ADR-0026: every artefact and tool is
pinned. The same digest runs in every environment.

## Follow-up

`P15-TSK-005`…`-007`, `-012`, `-017` and `-027` build this ADR. The production signing identity and GitHub's branch and
environment rules are the owner's to apply when a registry and hosted runners exist.
