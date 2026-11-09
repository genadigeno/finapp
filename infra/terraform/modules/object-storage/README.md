# `object-storage`

Buckets for infrastructure on the reference cloud (S3, `PHASE_15_PLAN.md` C3: object storage is infrastructure's, and the
application uses none). Called by each environment root. **Never applied**: there is no account in Phase 15, so every
property below is asserted by `tests/object-storage.tftest.hcl` over a plan against a mock provider. That proves the
configuration, not S3's behaviour.

The security properties are literals in `main.tf`, not variables, so no environment can turn one off.

## What it builds

Three buckets: `terraform-state` (the one each root's partial `s3` backend names at `init`), `audit-archive` and
`log-archive`. Each name carries the account, because a bucket name is global.

| Property | Held by the run |
|---|---|
| Every bucket refuses public access, all four blocks on, and honours no object ACL | `every_bucket_refuses_public_access` |
| Every bucket keeps versions | `every_bucket_keeps_versions` |
| Every bucket is KMS-encrypted under the key given for it | `every_bucket_is_encrypted_under_the_key_given_for_it` |
| Every bucket refuses plain transport | `every_bucket_refuses_plain_transport` |
| The audit archive has Object Lock on from creation, with the mode and period given; no other bucket has it | `the_audit_archive_is_write_once_for_the_period_given` |
| Every principal is refused deletion from the audit archive and any bypass of its lock | `nobody_may_delete_from_the_audit_archive` |
| Archives move to colder storage and nothing in them ever expires | `nothing_in_an_archive_ever_expires` |
| An invented mode, a lock of no days, an alias are refused | the `..._refused` runs |

Every bucket carries `prevent_destroy` and `force_destroy = false`.

## The audit archive's lock is an open decision

`audit_archive_lock` (mode and days) is required and has **no default**: the period is `OPEN` until a real jurisdiction is
chosen (`DATA_RETENTION.md` §3), and `TerraformIsPinnedAndCheckedTest#anOpenDecisionHasNoDefault` refuses one. No period appears
in any root; the values in the tests are fixtures and decide nothing. So **no real plan of either root can run until
someone chooses**, which is the intent: `THREAT_MODEL.md` §6 item 23 holds the choice.

`GOVERNANCE` and `COMPLIANCE` are S3's names for its two modes and claim nothing about any standard (`PHASE_15_PLAN.md`
§1.1). In the first, a principal with `s3:BypassGovernanceRetention` could shorten a lock, which the bucket's policy refuses to
everyone while the policy stands. In the second, nobody can, including the account's root user, so a period chosen wrongly
cannot be corrected.

Object Lock is decided at creation: the bucket resource sets it, and turning it on later is not what this code does. The
lock's default applies to objects written afterwards.

No object exists here yet. The archival that writes to this bucket is designed in `DATA_RETENTION.md` §3 and built in Phase 16.

## Keys

Each root gives this module the security module's `state` and `archive` keys, which are keys of their own, so the archive's can
never be the database's. Each key's policy admits S3 and no other service. Neither workload's role holds an action on any bucket
here (`modules/security`'s tests assert it).

## Inputs and outputs

Inputs: `name_prefix`, `account_id`, `state_kms_key_arn`, `archive_kms_key_arn`, `audit_archive_lock`, `archive_transition_days`.
Outputs: `state_bucket_name`, `audit_archive_bucket_name`, `log_archive_bucket_name`, `audit_archive_lock` and
`encryption_key_arns`. See `variables.tf` and `outputs.tf`.

## Operating it

- **The state bucket comes before its own backend.** A root's first apply has no bucket to keep its state in, so
  that first apply runs with local state, and `terraform init -migrate-state -backend-config=bucket=<state_bucket_name>` moves
  it in. Every later `init` names the bucket and the region at `init` (`OPERATIONS_RUNBOOK.md` §11.13).
- **Before relying on this**: run the checks (`OPERATIONS_RUNBOOK.md` §11.13).

## What it does not do

No server access logging of these buckets (a logging target under SSE-KMS needs its key's policy to admit the logging service,
which is the security module's), no replication to another region (Phase 16), no KMS key, no application role, and no apply.
