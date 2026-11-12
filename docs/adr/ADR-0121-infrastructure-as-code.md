# ADR-0121 — Infrastructure as code: Terraform modules per concern, environments as root modules, verified without an apply

Status: Accepted (2026-11-12, `P15-DOC-001` — read against the code and corrected first)
Date: 2026-11-07
Phase: 15
Context: Platform (infrastructure) · Security
Rests on: ADR-0025, ADR-0026, ADR-0120; owner decisions C1, C3 and C8 of `PHASE_15_PLAN.md` §2.2 (ratified at their recommended defaults by the owner, 2026-11-07; *read "recommended defaults awaiting ratification" until then*); `DECISIONS.md` §Deliberately Deferred "Kubernetes and Terraform — Phase 15".

## Context

No infrastructure code exists. `infra/postgres/initdb/00-roles.sql` says its job belongs to Terraform once the platform is
deployed. The repository has no cloud account, and none is assumed. Infrastructure that nobody can apply is still worth
writing, but only if it is held to checks that do not need an account. Otherwise it becomes an untested design written in
HCL.

## Decision

1. **Terraform, with one module per concern under `infra/terraform/modules/`.** The modules are `network`,
   `kubernetes`, `postgres`, `kafka`, `object-storage`, `security`, `registry` and `observability`. Root modules per
   environment under `infra/terraform/environments/{staging,production}` compose them. The reference provider is AWS
   (C1). The Kubernetes layer stays provider-neutral (ADR-0120). *(Corrected by `P15-DOC-001`, the Phase 15 exit review, 2026-11-12: provider-neutral holds of `deploy/base`
   and `deploy/components`; the staging and production overlays name the reference provider: workload-identity role
   ARNs, the secret store's `SecretsManager` provider and the load balancer's annotations.)*
2. **Pinned and locked.** The Terraform version and every provider are pinned, and `.terraform.lock.hcl` is committed,
   which mirrors ADR-0025 for Gradle. Every tool used to check them (`tflint`, the Trivy already pinned) is pinned by
   digest.
3. **State.** State is remote per environment, with locking, versioning and encryption. It is never initialised by CI.
   **No secret value ever enters Terraform state.** The secrets manager generates credentials, and Terraform creates
   only the entries and the policies. *(Corrected by `P15-DOC-001`, the Phase 15 exit review, 2026-11-12: as built, Terraform creates the entries without values, and their
   policies; the managed database generates its own master credential (`manage_master_user_password`), and every other
   first value is written into the store by a person (`OPERATIONS_RUNBOOK.md` section 11.7). No generator and no version
   resource exists, and `TerraformIsPinnedAndCheckedTest` refuses both.)*
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

## As built

`P15-TSK-013` (2026-11-08), the foundations. The decision stands. Four points record how it was built, and the first
is a small deviation from decision 1's layout:

- **One module beyond decision 1's eight: `modules/conventions`.** It holds the name prefix (`finapp-<environment>`)
  and the tags (`Project`, `Environment`, `ManagedBy`) once, so that the two roots and the eight concern modules never
  repeat them. Each root hands its tags to the AWS provider's `default_tags`, and every resource carries them. It
  creates no resource. The eight concern modules are not created empty: each arrives with its task (`-014`…`-016`).
- **The checks (decision 5) run as one pinned script, `infra/scripts/terraform-check.sh`, in CI's `iac` job.**
  Terraform itself runs from its image pinned by digest beside `tflint`'s (`infra/scanner-pins.sh`), and every root's
  `required_version` equals that image's version. Each check is also run, in the same job, over a copy of the tree
  carrying one planted defect it must refuse by name: an unformatted file, a dropped provider pin, a camel-case
  identifier, a public bucket, an undeclared reference and a dropped tag.
- **State (decision 3).** Each root declares an `s3` backend with its own key, `encrypt = true` and
  `use_lockfile = true`. The bucket and the region are given at `init` in a real environment, as a partial
  configuration, because no account exists to name them. `-015`'s object-storage module builds the bucket. CI runs
  every `init` with `-backend=false`.
- **Guarded resources (decision 4) and no secret in state (decision 3) are build rules.**
  `TerraformIsPinnedAndCheckedTest` refuses a resource of a guarded type (the database, its backups, every KMS key)
  without `prevent_destroy = true`. It also refuses a resource or data source that writes a secret value into state,
  and a database given its password. No such resource exists yet. Each rule is proven against a planted violation.

`P15-TSK-014` (2026-11-08), the `network` and `kubernetes` modules. The decision stands. Three points the build settled:

- **A concern module that requires a provider commits a lock file.** Every `init` in the check script is
  `-lockfile=readonly`, so a module tested on its own needs one, which `modules/conventions`, having no provider, never
  showed. Each holds the exact version the roots pin (`TerraformIsPinnedAndCheckedTest`), so a module's tests run
  against the provider its roots use. A module's own constraint stays a floor (`>= 6.72.0`), as `required_version` does.
- **The chosen security properties are literals, not inputs.** The endpoint's privacy, the audit log, etcd's encryption
  and the three-zone rule cannot be turned off from a root. Decision 5's `terraform test` asserts them over the plan,
  and the check script plants a defect against each.
- **Keys remain inputs until `-016`.** etcd's key is a variable each root requires, and no KMS key is created here.
  The access entries through which any principal reaches the cluster are `-016`'s too; the cluster grants its
  creator none.

`P15-TSK-015` (2026-11-09), the `postgres`, `kafka` and `object-storage` modules. The decision stands, with one claim corrected and four points the build settled. Nothing here has been applied, and every engine version, instance class and service limit is unverified against AWS:

- **The plan's phrase meets a limit of RDS.** "Automated backups under a separate key" is not something RDS offers: its backups and snapshots carry the instance's key, and AWS Backup's table of independently encrypted resources says RDS is not supported. Within one account and region a vault's key changes nothing about an RDS recovery point. So the module builds a vault under a separate key, a daily snapshot, and a required copy to a vault in another account or region, the only place another key really applies. Point-in-time recovery stays with RDS, under the volume's key, and no copy is point-in-time. Where that destination lives is `THREAT_MODEL.md` §6 item 23.
- **A second provider, chosen knowingly: `mongey/kafka`.** The AWS provider has no topic or ACL resource. This one makes topics and ACLs reviewable resources in a plan, at the price of a community provider in the supply chain and the only provider here that reaches the workload itself. A root's provider block carries no credential (`KAFKA_SASL_*` come from the runner), and `TerraformIsPinnedAndCheckedTest` now refuses a credential argument of this provider too and applies the AWS-only rules to the AWS provider.
- **A first apply is a sequence.** `provisioning_stage`: the brokers first, then the SCRAM users, topics and ACLs, then `allow.everyone.if.no.acl.found=false`. MSK has no super users, so with no ACL and that setting off nobody could write the first ACL, and AWS documents it as losing access to the cluster. No default, because each earlier value is an unfinished cluster.
- **`max_connections` is written once in the repository**, in each overlay. Each root reads it from there, so the check script now mounts `deploy/` read-only too, and each root's test reads it again and compares. Being static, it needs a reboot and an order, which is the `postgres` module's README.
- **Decided data stays where it was decided.** Topics, ACLs and the broker posture are read from `infra/kafka/` by path; a run with fixture files proves the module reads what it is given, and a guard refuses a copy under `infra/terraform`. No ADR-0129: nothing about the decision changed.

`P15-TSK-016` (2026-11-09), the `security`, `registry` and `observability` modules. The decision stands. Nothing has been applied, and every ARN, region and policy is plan-only. The points the build settled:

- **The roots' key variables are gone.** Each root takes its keys from the security module, one per purpose, and the data-tier and etcd variables they replaced no longer exist. The two etcd and data-tier validations they carried are held instead by each key being its own resource of that root's provider, which `allowed_account_ids` confines to its account. Each root's test asserts the wiring, not the account.
- **The secret entries are read, not listed.** The module reads `SECRET_MANAGEMENT.md` and the two `ExternalSecret`s at plan and refuses a difference, so the check script now mounts `docs/` read-only too. A build rule refuses a `.tf` file naming an inventoried variable.
- **Decision 1's security module is wider than its first line.** It holds the human IAM baseline and the cluster's access entries (`THREAT_MODEL.md` section 6 item 3), and nine key purposes, not the plan's five: `etcd`, `database`, `database_backup`, `secrets`, `field_keys`, `brokers`, `state`, `archive` and `logs`, the last for the CloudWatch log groups and the telemetry buckets.
- **No rotation schedule.** A rotation here is a deliberate act, and a build rule refuses `aws_secretsmanager_secret_rotation`. The one rotation a service manages is RDS's own.
- **A person appears in one module.** `aws_iam_user`, `aws_iam_group` and `aws_eks_access_entry` are confined to it by a build rule, and an access policy association is refused anywhere.
