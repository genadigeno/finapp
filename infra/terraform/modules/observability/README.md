# `observability`

The two buckets where a log and a trace backend keep what they hold (`PHASE_15_PLAN.md` section 7). Called by each environment root.
**Never applied**: there is no account in Phase 15, so every property below is asserted by `tests/observability.tftest.hcl` over a
plan against a mock provider. That proves the configuration, not the service's behaviour.

## What it builds

| Property | Held by the run |
|---|---|
| A `logs` and a `traces` bucket, named for the environment and the account | `there_are_a_log_and_a_trace_bucket_named_for_this_account` |
| Public access blocked, ACLs off, plain transport refused, under the security module's `logs` key | `every_bucket_is_private_and_encrypted_under_the_given_key` |
| Each signal expires on its own period; an old version lasts a week beyond it | `each_signal_expires_on_its_own_period` |
| A period under a week, or an alias for the key, is refused | the `..._refused` runs |

The periods in each root are reference values: telemetry is the operational class, `DATA_RETENTION.md` leaves its period to each
environment, and Phase 16's load model will size it.

**These are not the audit archive.** `modules/object-storage` keeps what is never deleted. Nothing here carries an audit record, and
everything here expires.

**No role reads or writes these buckets.** The application has no use for a bucket. A telemetry backend gets its own identity with its
own workload when one is deployed, and the cluster has only two workload roles until then.

**The alerting endpoints stay where they are**, Alertmanager's placeholder receivers (`infra/alertmanager/alertmanager.yml`). A topic
nobody subscribes to would read as paging. `PHASE_15_PLAN.md` section 7 named "alerting endpoints (placeholders)" for this module, and
this is the answer to that line.

## Inputs and outputs

Inputs: `name_prefix`, `account_id`, `kms_key_arn`, `log_retention_days`, `trace_retention_days`.
Outputs: `bucket_names`, `encryption_key_arn`.

## What it does not do

No log or trace backend, no alert receiver, no cloud API trail (`THREAT_MODEL.md` §6 item 31), and no apply.
