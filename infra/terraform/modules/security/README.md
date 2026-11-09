# `security`

The platform's KMS keys, its Secrets Manager entries, the two workloads' cloud identities, the human IAM baseline and the cluster's
access entries, on the reference cloud (`PHASE_15_PLAN.md` C1 and C6, ADR-0121, ADR-0123). Called by each environment root.
**Never applied**: there is no account in Phase 15, so every property below is asserted by `tests/security.tftest.hcl` and
`tests/human-access-keys.tftest.hcl` over a plan against a mock provider. That proves the configuration, not AWS's behaviour.

**Nothing here lists a secret.** The entries are read at plan from `docs/architecture/SECRET_MANAGEMENT.md` §3.2 and §3.3, the two
`ExternalSecret`s of `deploy/components/secret-delivery` and the overlay's `secret-store.yaml`. Add a row to the inventory and its
entry to the `ExternalSecret` and the store's entry follows, under the right key, with nothing to edit here. Each root's tests hold
what is planned to the real files, and `TerraformIsPinnedAndCheckedTest` refuses a `.tf` file naming an inventoried variable.

## What it builds

| Property | Held by the run |
|---|---|
| One key per purpose (`etcd`, `database`, `database_backup`, `secrets`, `field_keys`, `brokers`, `state`, `archive`, `logs`), rotation on, a 30-day deletion window, `prevent_destroy` (`TerraformIsPinnedAndCheckedTest`) | `there_is_one_key_per_purpose_with_rotation_and_a_deletion_window` |
| A key's policy lets the account administer it, so a wrong policy can be repaired and no key is locked, and gives the account no use of it | `no_key_policy_locks_everyone_out_or_lets_the_account_use_it` |
| Each key admits the services its purpose names, by `kms:ViaService`; the etcd key this cluster's role alone; the logs key CloudWatch Logs, bound to this account's and this environment's log groups | `each_key_admits_the_services_its_purpose_names_and_no_other` |
| The database key is shared with a backup copy's destination only when that is another account | `the_database_key_is_shared_with_a_backup_copys_account_only_across_an_account`, `a_backup_copy_inside_this_account_shares_the_key_with_nobody` |
| One entry per row the inventory delivers, without a value, under `finapp/<environment>/<app\|migrator>/<VARIABLE>`; none for the master credential or a SCRAM secret; a field-level key under `field_keys`, every other under `secrets` | `the_entries_are_what_the_inventory_delivers`, `each_entry_is_under_its_purposes_key` |
| A plan stops on an inventory row with no entry, an entry with no row, a row it could not parse, or a store on another environment's path | the `..._stops_the_plan` runs |
| Each reader is trusted by its own ServiceAccount alone, `sub` and `aud` by `StringEquals` | `each_reader_is_trusted_by_its_own_service_account_alone` |
| Each reader reads exactly its own entries by name, and the application's also its SCRAM entry; nothing with a wildcard; nothing on a bucket; never the master credential | `each_reader_reads_exactly_its_own_entries` |
| Each reader decrypts only through Secrets Manager, and only under the keys its own entries are under | `each_reader_decrypts_only_through_secrets_manager_under_its_own_keys` |
| No policy this module plans grants a wildcard action | `no_secret_or_key_policy_holds_a_wildcard_action`; the whole tree: `TerraformIsPinnedAndCheckedTest` |
| Break-glass needs a factor at most an hour old and lasts an hour | `break_glass_needs_a_factor_and_lasts_an_hour` |
| Every human role needs a factor, names exactly its people, has the permission boundary, and forces the session to carry the person's name | `every_human_role_needs_a_factor_names_its_people_and_has_its_boundary` |
| Every person is in the group that refuses everything without a factor and refuses a long-lived credential | `every_person_is_in_the_group_that_refuses_everything_without_a_factor` |
| A person holding an access key, however it was made, stops the next plan | `a_person_holding_an_access_key_stops_the_plan` |
| Only break-glass reads a secret, and only what the bootstrap step reads: the master credential and the passwords the inventory names that step as consuming | `only_break_glass_reads_what_the_bootstrap_step_reads` |
| Two access entries, each one group on its role, with no access policy | `the_access_entries_carry_each_group_on_its_role_and_nothing_else`; no association anywhere: `TerraformIsPinnedAndCheckedTest` |

Each root's own tests assert the wiring against the real files: an entry per row of the real inventory, the application's reader
reading `modules/kafka`'s own entry, the trust naming what the overlay names, the access entries' groups being what
`deploy/base/rbac-humans.yaml` binds, and every store under the key of its purpose.

## What was decided

- **The inventory is read here, and not only held by a test.** Only the document says which class a secret is, and the class chooses
  the key. The two `ExternalSecret`s and the overlay's stores say what the operator will really ask for. Both are read, and a
  disagreement stops the plan, because a partial set would fail the operator's whole sync.
- **The readers name each entry.** `secret:<name>-??????`, the six characters being the random suffix Secrets Manager adds. A
  prefix would hand a reader whatever is created under it later.
- **The account administers a key and does not use it.** Without an administration statement a wrong policy would lock a key for
  good. With the usual `kms:*` every principal in the account could use it, given an IAM permission. So administration is an
  explicit list, use is by statement, and no statement holds a wildcard.
- **A service is admitted by `kms:ViaService`, from this account.** RDS, MSK, S3, AWS Backup and Secrets Manager call KMS as their
  caller, so there is no service principal to name. The equivalent bound is the service, and the root principal keeps it in this
  account. CloudWatch Logs does call as a service principal, so its statement is bound to this account's log groups instead.
- **No rotation schedule on any entry.** "Rotation where managed" is the master credential's, which RDS manages. Every other class
  rotates by an act the fleet follows (`SECRET_MANAGEMENT.md` §5.2), and a schedule would move a value on a clock the fleet cannot see.
  `TerraformIsPinnedAndCheckedTest` refuses `aws_secretsmanager_secret_rotation`.
- **Who may be a person.** Workforce federation waits for a workforce identity provider (C13). Until then a person is an IAM user in
  the `/humans/` path and the one group, named in `human_users`. A person's password and device are enrolled by hand and are in no
  Terraform state. A person holds no right but enrolling a factor and assuming the roles the trust names.
- **Break-glass reads what the bootstrap step reads.** The one-time step needs the master credential and the three passwords it sets
  (`SECRET_MANAGEMENT.md` §3.2, `DATA_MIGRATIONS.md` §2.2), and a person runs it. Those are the rows whose Consumer names the step, read
  from the inventory, and this is the only human reach to any secret: Kubernetes RBAC still gives no person a Secret. The step's URL is
  no secret and comes from the root's `database_endpoint` output.

## What could not be checked without an account

Each of these reads as AWS documents it and has never met AWS. A real first apply is the check, in staging first.

| Assumption | Where it could be wrong |
|---|---|
| A key policy accepts these principals and conditions | KMS validates a principal's existence when the policy is written, which is why each policy is a real dependency of the role it names |
| RDS, its Performance Insights, MSK's volumes, AWS Backup and S3 work through `kms:ViaService` from the account alone | A service that uses a service-linked role through a grant needs the caller to hold `kms:CreateGrant` through that service, which the statement gives; the caller still needs the same in IAM |
| The brokers read the SCRAM entries through a grant under the `secrets` key | `kafka` is admitted for this; whether MSK also needs `secretsmanager` as its caller is unverified |
| RDS writes its master credential under the `secrets` key through Secrets Manager | `secretsmanager` is admitted; a further `rds` statement may be needed |
| A backup copy across accounts needs the destination account on the database key | Named by `P15-TSK-015` from AWS's documentation, and that vault does not exist (`THREAT_MODEL.md` §6 item 23) |
| The operator needs `secretsmanager:GetSecretValue` alone | Read from the operator's source by `P15-TSK-018`, never run against a store |
| `aws:MultiFactorAuthAge` bounds the factor, and `sts:RoleSessionName` can be held to `${aws:username}` | As AWS documents them; the cluster's and CloudTrail's naming of the session has not been seen |

## Inputs and outputs

Inputs: `name_prefix`, `region`, `account_id`, `secret_inventory_path`, `external_secret_manifest_paths`, `secret_store_manifest_path`,
`workload_namespace`, `cluster_name`, `cluster_role_arn`, `workload_identity_provider_arn`, `workload_identity_issuer`,
`master_user_secret_arn`, `database_backup_copy_vault_arn`, `human_users`.
Outputs: `kms_key_arns`, `secret_entry_names` (carrying the plan's stops), `secret_reader_role_arns`, `human_role_arns`, and three that read
the plan back for the roots' tests. See `variables.tf` and `outputs.tf`.

## Operating it

- **Every entry needs a value before the first sync.** Terraform never holds one. `OPERATIONS_RUNBOOK.md` §11.7 is the first fill, which
  never overwrites. Under `Retain` a missing or empty entry fails the whole sync, the keys of providers that do not exist yet included.
- **The overlays' account and region stay placeholders in this repository.** The real role ARNs are the roots' `secret_reader_role_arns`
  outputs. An environment supplies them as a Kustomize patch held with that environment's delivery configuration, never committed here
  (`SECRET_MANAGEMENT.md` §3.5). The manifest policy refuses a real account in anything rendered from this repository.
- **The cluster's API**: a person assumes the role with a factor, naming their own session
  (`aws sts assume-role --role-session-name "$USER" ...`), and `aws eks update-kubeconfig`. A token outlives its session by at most
  fifteen minutes, so the hour is the bound to within that.
- **A key's deletion window** is 30 days and `prevent_destroy` refuses the plan. `aws kms cancel-key-deletion` stops one in the window.
- **Before relying on this**: run the checks (`OPERATIONS_RUNBOOK.md` §11.13).

## What it does not do

No secret value, no generator, no rotation function, no signing key (`P15-TSK-007`), no backup account or vault (`THREAT_MODEL.md` §6
item 23), no cloud API trail and nothing that installs a cluster's first components (§6 item 31), no resource policy on an entry, no
workforce federation (C13), and no apply.
