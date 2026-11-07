# ADR-0121 — Infrastructure as code: Terraform modules per concern, environments as root modules, verified without an apply

Status: Proposed
Date: 2026-11-07
Phase: 15
Context: Platform (infrastructure) · Security
Rests on: ADR-0025, ADR-0026, ADR-0120; owner decisions C1, C3 and C8 of `PHASE_15_PLAN.md` §2.2 (recommended defaults
awaiting ratification); `DECISIONS.md` §Deliberately Deferred "Kubernetes and Terraform — Phase 15".

## Context

No infrastructure code exists. `infra/postgres/initdb/00-roles.sql` says its job belongs to Terraform once the platform is
deployed. The repository has no cloud account, and none is assumed. Infrastructure that nobody can apply is still worth
writing, but only if it is held to checks that do not need an account. Otherwise it becomes an untested design written in
HCL.

## Decision

1. **Terraform, with one module per concern under `infra/terraform/modules/`.** The modules are `network`,
   `kubernetes`, `postgres`, `kafka`, `object-storage`, `security`, `registry` and `observability`. Root modules per
   environment under `infra/terraform/environments/{staging,production}` compose them. The reference provider is AWS
   (C1). The Kubernetes layer stays provider-neutral (ADR-0120).
2. **Pinned and locked.** The Terraform version and every provider are pinned, and `.terraform.lock.hcl` is committed,
   which mirrors ADR-0025 for Gradle. Every tool used to check them (`tflint`, the Trivy already pinned) is pinned by
   digest.
3. **State.** State is remote per environment, with locking, versioning and encryption. It is never initialised by CI.
   **No secret value ever enters Terraform state.** The secrets manager generates credentials, and Terraform creates
   only the entries and the policies.
4. **Guarded resources.** `prevent_destroy` and deletion protection are set on the database, its backups and every KMS
   key. Production and non-production live in separate accounts.
5. **Verified without an account.** Every change runs `terraform fmt -check`, `terraform validate`, `tflint` and
   `trivy config`. It also runs **`terraform test` with mock providers**, which assert the security properties:
   encryption on, public access off, deletion protection on, Multi-AZ on, TLS forced, and `max_connections` equal to the
   application's declaration.
6. **`terraform apply` against a real account is outside Phase 15.** It is the owner's to perform when an account
   exists. A drift check is a scheduled plan in a real environment.

## Alternatives Considered

### Cloud-neutral (Kubernetes-only) infrastructure code
Pros: no provider is chosen.
Cons: it cannot express networking, managed databases, Kafka, KMS or the registry, and the brief asks for all of these.

### Pulumi or CDK
Pros: real programming languages.
Cons: they add a runtime and an SDK dependency tree to the supply chain. HCL is declarative and reviewable as data.

### OpenTofu
The HCL is kept compatible. Choosing the binary is a licensing question that the owner may answer at ratification.

## Consequences

Positive: the infrastructure is reviewable, reproducible and checked on every change. Its security properties are tests,
not prose.
Negative: mock-provider tests prove the configuration, not the cloud's behaviour. The gap stays open until a real
apply.
Operational impact: every infrastructure change becomes a pull request with a plan.
Security impact: encryption, private networking and deletion protection become asserted defaults.
Financial impact: none.

## Invariants / Constraints

There is no secret in state or in the repository. `max_connections` has one source of truth. Production resources cannot
be destroyed by a plan.

## Follow-up

`P15-TSK-013`…`-016` build this ADR. Phase 16 adds the multi-AZ and multi-region variants and cross-region backup.
