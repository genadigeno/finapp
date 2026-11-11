# ADR-0122 — CI/CD and the supply chain: one signed image per commit, promoted by digest, every production change two-person, every release schema-compatible with the last

Status: Proposed
Date: 2026-11-07
Phase: 15
Context: Platform (build and delivery) · Security (supply chain) · every module (migrations, events)
Rests on: ADR-0005, ADR-0011, ADR-0025, ADR-0026, ADR-0028, ADR-0120, ADR-0121; owner decisions C4, C7, C8 and C11 of
`PHASE_15_PLAN.md` §2.2 (ratified at their recommended defaults by the owner, 2026-11-07; *read "recommended defaults awaiting ratification" until then*); `DATA_MIGRATIONS.md` §4.1.

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

   *Note (2026-11-08, `P15-TSK-006`):* "vendored" was built as rules written for this repository under
   `infra/semgrep/rules` rather than files copied from Semgrep's registry. The registry's rules carry their own licence,
   and no registry rule can say what "constant" means for this codebase's idiom. Semgrep runs with no network at all. The
   Trivy pin moved from 0.74.0 to 0.76.2. Nothing else in this decision changed.
3. **Signing and admission.** Images are signed with Cosign. The SLSA provenance and the SBOM are attached as
   attestations. The cluster's admission controller refuses an image that is unsigned or comes from an unexpected
   identity. Keyless signing from CI's OIDC identity is the reference. Until a registry and an identity exist, CI proves
   the whole path against a local registry with an ephemeral key, and `kind` proves the refusal.

   *Note (2026-11-11, `P15-TSK-007`), as built:*
   - **CI** (`admission` job, `infra/scripts/admission-check.sh`) builds both images once, pushes them by digest to a
     registry of the run's own, and signs each with cosign under a key pair generated for the run in a temporary directory
     outside the repository. Each gets two attestations, SLSA provenance v1 (the commit, the source, the Dockerfile's
     pinned bases, the builder) and the CycloneDX SBOM. `cosign verify` and `cosign verify-attestation` accept both images
     before the job deploys them, and refuse an unsigned image, one signed by another key, and a signed one with no
     attestations, each by name. Nothing reaches a transparency log, and the job holds no secret and no OIDC token.
   - **Admission** is the Sigstore policy controller, pinned with its chart (`deploy/operators/policy-controller`):
     `no-match-policy: deny`, `failurePolicy: Fail`, two replicas, watching the namespaces labelled
     `policy.sigstore.dev/include` (`finapp` and `finapp-edge`). `deploy/admission` holds three policies. Two match
     `finapp-app` and `finapp-migrator` by repository name from any registry, one requiring the signature and the other
     both attestations: one authority that lists attestations checks no signature, and every matching policy must pass.
     The third is the one exception, the edge's Traefik admitted by its pinned digest alone. The authority is a
     placeholder until an environment supplies it. On `kind`, the check proves both images admitted, and unsigned,
     other-key and unattested images refused. It also proves an unsigned init container refused, any image no policy
     names refused, and a refusal while the controller is down. Each refusal sits beside the identical object admitted in
     a namespace the policy does not cover.
   - **Two facts read from the controller's own code** shaped this. Its key authority reads only cosign's tag-based
     format: the bundle format is keyless-only in v0.15.1. And its webhook is created with no rules, which it fills in
     when it starts. So a namespace whose pods are created before then is admitted unchecked. The order is the
     controller, its rules, the policies, then the platform's namespaces (`OPERATIONS_RUNBOOK.md` §11.32).
   - **Where signatures live, decided**: in a repository of their own per environment (cosign's `COSIGN_REPOSITORY`,
     read by the policy through `source.oci`), tag-based, with mutable tags and no lifecycle rule. cosign adds a second
     signature or attestation by rewriting a tag, and under `IMMUTABLE` tags the second `cosign attest` on one digest
     would be refused. The image repositories keep `IMMUTABLE` tags, and an untagged-expiry rule on them can never reach
     a signature. The `kind` check proves the policy reading signatures from that repository. `P15-TSK-017` carries the
     repository and the lifecycle rule.
   - **The production identity is the owner's** (`THREAT_MODEL.md` §6 item 35). Keyless signing needs `id-token: write`
     on the signing job alone, and nothing here grants it.
4. **Delivery is GitOps by pull.** Argo CD syncs environment overlays that are pinned by digest. Promotion is a pull
   request that changes a digest. A production change needs a second person's approval (CODEOWNERS on the production
   overlay) and names the staging evidence for that digest. No CI credential reaches production. The migration Job runs
   as a pre-sync step, before the rollout.
5. **Migration compatibility is enforced.** An **expand-only lint** checks every migration added since the last release.
   - It refuses destructive DDL: `DROP`, `RENAME` and a type change.
   - It also refuses **tightening DDL**, because each can refuse a write that release N − 1 still makes:
     - a new `CHECK`, trigger, `UNIQUE` or foreign key;
     - `SET NOT NULL`, or `ADD COLUMN … NOT NULL` with no default;
     - a `REVOKE`.

     This repository adds refusing triggers often.
   - A refused migration passes only if it carries a **contract marker** naming the first release whose code conforms
     to it.

   A **schema-version readiness guard** stops the application from becoming ready on a schema older than the one it was
   built for. The `kind` rehearsal runs release N − 1 against N's schema before it rolls.

   **A rollback is safe only to a release that no later contract marker excludes.** The promotion script refuses a
   rollback across a marker. Past that point, the way back is a forward fix.

   *(Corrected by the transition's review, 2026-11-07, its M-2: tightening DDL was not counted, and a rollback was read
   as always safe.)*

   *(As built by `P15-TSK-005`, 2026-11-09; `DATA_MIGRATIONS.md` §4.1 is the contract. "The last release" is a committed
   per-module file, `docs/migrations/released-schema-floor.yaml`, because the repository has no release tag and CI clones
   shallowly; it says `release: "none"` until one is cut. The lint is an allow-list of statement forms and reads no function
   body, so a `CREATE OR REPLACE` of something a release holds is refused whatever it says and needs a marker: a person's
   statement. A marker naming a release not yet out is refused, because release N − 1 is running. The guard reports
   `OUT_OF_SERVICE` only on an older schema, reading each schema's history through one definer function, platform `V013`, since
   the application role holds no access to those tables; it reads on a thread of its own, never the probe's. Because a consumed event and a scheduled tick are traffic too, schedules, the relay and consumers wait on the same verdict. A marker names the classes it waives.)*
6. **Event compatibility is enforced at build time, for payloads and for handlers.**
   - A schema catalogue lists every event type and version. A test compares it with the last release's catalogue and
     allows only additive changes within a major version.
   - Payload compatibility does not cover a **new handler**. The receiver commits the offset of a record that no
     registered handler wants (`UNHANDLED`). So while releases N − 1 and N share a module's consumer group, and after a
     rollback, a record of a type only N handles is committed unseen.
   - A **handler-set catalogue** of `(group, topic, eventType, consumerName)` is therefore diffed against the last
     release. A handler added to an existing group requires one of two things. The first is an audited replay of its
     type over the rollout window and any rollback window; the inbox deduplicates what N already applied. The second is
     a new consumer group whose starting offsets are chosen and recorded.
   - There is no runtime schema registry while no consumer exists outside the monolith.

   *(Widened by the transition's review, 2026-11-07, its I-2.)*

   *(Read-note, `P15-TSK-027`, 2026-11-08: built as written, with what the decision left open settled in
   `EVENT_ARCHITECTURE.md` §Schema evolution. Both catalogues are read from the compiled code, and the committed files
   in `docs/events/` stand for the last release, so no second list is kept by hand. "Within a major" is `eventVersion`,
   the number a consumer opts into: an additive change may stay under a version or take the next one, and a new version
   of a type a released handler consumes waits until the consumers have learned it. The handler diff runs both ways,
   because a handler removed opens the same window to a rollback that a handler added opens to a rollout.)*
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

*(2026-11-11, `P15-TSK-007`:)* decision 3 is built for CI and `kind` (its note above). What remains of it:
- the production identity, `THREAT_MODEL.md` §6 item 35;
- the signature repository in each environment's registry, its lifecycle rule, and a promotion that refuses a digest
  without its signature, all `P15-TSK-017`'s;
- the install order of the controller before the namespaces it watches, also `P15-TSK-017`'s.
