# modules/recovery-region

The standing recovery site in a second region (`P16-TSK-026`, ADR-0150). Called by `environments/production-recovery` alone:
only production keeps a recovery region (ADR-0129, owner decision C10). Planned against mock providers and never applied.

## What it builds

| Resource | Why it exists before any disaster |
|---|---|
| `aws_kms_key.this["region_copies"]` | The key of the vault below. A copy crossing a region takes the destination vault's key, so the database's region copy is under a key of this region, apart from the storage key and from production's backup vault key |
| `aws_kms_key.this["field_keys"]` | The key every field-level key ring version's replica is wrapped under here (the security module's `recovery_region`). Without it, a region's loss leaves rows of evidence nothing can read (ADR-0149 decision 1) |
| `aws_backup_vault.region_copies` | Where production's daily snapshot is copied from its region (the postgres module's `backup_region_copy_vault_arn`): ADR-0128 tier 4's recovery point, 24 hours at most |
| `aws_backup_vault_policy.region_copies` | Refuses `CopyIntoBackupVault` from any other account, and grants nothing |

Each key rotates, keeps a thirty days' deletion window, is never multi-region, is used only through its own services in this region,
and is administered by the account, which keeps `PutKeyPolicy` and never uses it directly: the security module's rules, applied here.
The keys and the vault declare `prevent_destroy` (`TerraformIsPinnedAndCheckedTest`).

## What it does not build

- **Anything that takes a write.** The cluster, the data tier, the brokers and the fleet in this region are built only in a declared
  regional recovery, after production's region is fenced (`OPERATIONS_RUNBOOK.md` §11.65). Planning them a second time in the same
  account is `THREAT_MODEL.md` §6 item 72.
- **A multi-region key.** It would put one key's material in both regions; each store re-wraps under this region's key instead.
- **A credential.** Every credential is generated again in a recovery and never replicated (ADR-0149 decision 2): only the rings are.
- **The account's loss.** This site is in production's own account, so it answers the region's loss, not the account's. Tier 3 is the
  backup account's copy (`THREAT_MODEL.md` §6 item 23 (a)); the rings and the images outside the account are item 71.
- **A vault lock.** A retention lock on the copies is the same kind of choice as the audit archive's (item 23 (b)) and is not taken here.

## What could not be checked without an account

| Assumption | Where it could be wrong |
|---|---|
| A cross-region copy in one account re-encrypts under the destination vault's key | As AWS Backup documents copies; never seen on a real copy job |
| RDS restores the copied snapshot in this region through `rds` on the vault's key | Written from the restore path's documentation; the first regional restore rehearsal is the check |
| The vault policy's `aws:PrincipalAccount` condition holds for the service that writes a same-account copy | AWS Backup makes the copy as this account's role; if the condition refuses it, the copy fails closed and the first copy job shows it |

## Inputs and outputs

Inputs: `name_prefix` (`finapp-production` alone), `region`, `primary_region` (refused if equal to `region`), `account_id`.
Outputs: `region`, `database_region_copy_vault_arn` and `field_keys_kms_key_arn` (production's `recovery_region` input), `kms_key_arns`,
`vault_kms_key_arn`.
