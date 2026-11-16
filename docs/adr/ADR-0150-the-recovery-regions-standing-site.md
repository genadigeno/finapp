# ADR-0150 — The recovery region's standing site: the database's region copy, the rings replicated under the region's own key, no multi-region key, and promotion only after the fence

Status: Accepted (2026-11-16, `P16-DOC-001` — read against the code; no correction needed)
Date: 2026-11-14
Phase: 16
Context: Platform · infrastructure (Terraform) · Ledger (one writer) · every module holding field-level evidence
Rests on: ADR-0121 (infrastructure as code); ADR-0123 (secrets and key rings); ADR-0128 (tier 4: RPO 24 h, RTO 24 h for the region
until replication is bought); ADR-0129 (one region, multi-AZ active-passive, no active-active writes, decisions 4 and 5); ADR-0149
(what survives, what is regenerated); `THREAT_MODEL.md` §6 items 23 (a), 71 and 72; owner decisions C9 and C10 of
`PHASE_16_PLAN.md` §2.2.

## Context

ADR-0129 decided that another region is a recovery site, restored from backups, promoted by a person after the first region is
fenced, and that "backups and key material are made available in the recovery region before they are needed". It did not say
which backups, which key material, under which keys, or what the site holds before a disaster. Three facts shape the answer.

- The database's daily copy goes to the dedicated backup account **in the same region** (`THREAT_MODEL.md` §6 item 23 (a), decided
  2026-11-09; the plan and ADR-0128 cite it as "item 21 (a)", a numbering slip). A region's loss takes that copy too, so ADR-0128's
  tier 4 had no recovery point outside the region.
- Every field-level key ring version is its own secret entry in production's region, wrapped by a key of that region (ADR-0123).
  Rows are never re-encrypted, so a ring version unreadable in the recovery region is evidence lost (ADR-0149 decision 1).
- A recovery generates every credential again and carries only the rings (ADR-0149 decision 2).

## Decision

1. **A standing site in a second region, in production's account, before any disaster.** A root of its own,
   `infra/terraform/environments/production-recovery`, holds two keys and one vault in the recovery region, under its own state key
   (`finapp/production/recovery-region/terraform.tfstate`, the bucket in the recovery region). It is production's site: it names and tags
   itself as production. It takes no write and can promote nothing.
2. **The database's region copy.** The same daily snapshot that is copied across the account (item 23 (a)) is also copied to the
   site's vault, under the site's own `region_copies` key. A copy crossing a region takes the destination vault's key, so the copy is
   under a key apart from the storage key and from production's backup vault key. A destination in the database's own region is
   refused at plan. Tier 4's recovery point stays 24 hours (ADR-0128); point-in-time replication across regions is still the option
   the owner may buy (ADR-0129, C10 (B)).
3. **The rings replicated, and nothing else.** Every field-key entry is replicated to the recovery region, wrapped there under the
   site's own `field_keys` key, before any disaster. No credential entry is replicated: credentials are generated again in a recovery.
4. **No multi-region key.** A multi-region key puts one key's material in both regions, so a compromise, a mistaken policy or a
   scheduled deletion in one reaches the other. Each store that crosses re-wraps under a key that never leaves its region instead: AWS
   Backup's copy takes the destination vault's key, Secrets Manager's replica takes the key named for its region. The database key
   admits AWS Backup and RDS in the recovery region for the copy, as it already admits them in the vault's region for a copy across
   an account.
5. **Residency is one rule for both regions.** Production's region, its recovery region and its account copy's region must each be in
   `residency_regions`, an open decision with no default until a jurisdiction is chosen.
6. **Promotion only after the fence, by a person.** A regional recovery is a declared incident. The fence comes first and is recorded:
   the public names withdrawn from the lost region, every provider credential revoked at its provider (they are regenerated anyway; money
   leaves the platform only through a provider) and the secret readers' trust revoked, and, if its control plane answers, its database
   stopped and its fleet scaled to zero. Only then is the site's copy restored,
   under the post-recovery gate (ADR-0128, ADR-0140, ADR-0149). A region that returns is never promoted back: it stays stopped, and what
   it wrote after the copy's instant is found by the providers' statements and reconciliation (`OPERATIONS_RUNBOOK.md` §11.65). No health
   check promotes anything.

## Alternatives Considered

### Multi-region keys for the rings and the copies
Pros: one key identity in both regions; nothing re-wrapped. Cons: one key's material in two regions; a policy or deletion mistake
reaches both. Rejected (decision 4).

### The site in the backup account's second region
Pros: the copy would cross both the account and the region, so tiers 3 and 4 would meet one site. Cons: Secrets Manager replicates only
within an account, so the rings would need the escrow item 71 (a) leaves to the owner; whether one copy action can cross both boundaries
for RDS was not read from the provider's documentation; a restore into production's account would first copy the point back. Not taken
here: item 72 puts it to the owner beside item 71.

### Replicate automated backups across regions (point-in-time)
Pros: tier 4's recovery point falls to minutes. Cons: a second region's running cost and a decision the owner kept (C10 (B)). Left open.

### Build the whole platform in the recovery region from the production root at need
Not possible as written: IAM roles, users and groups and S3 bucket names are global to an account or to AWS, so a second plan of the
production root in the same account collides with the first, and production's state lives in the region that was lost. Recorded as
`THREAT_MODEL.md` §6 item 72, designed-only.

## Consequences

Positive: a region's loss leaves a recovery point and every ring version readable, before anyone has to act. No credential and no key
material crosses a region boundary in clear or under a shared key.
Negative: two more keys and a vault to keep; the copy doubles the daily snapshot's storage; the at-need rebuild is not yet plannable
(item 72).
Operational impact: the recovery-region root is applied before production's, which takes its outputs. A regional promotion is
§11.65's procedure, rehearsable only with an account.
Security impact: every key policy admits the account alone and only through each key's service in its own region; the vault refuses a
copy written from another account and grants nothing.
Financial impact: none on the books. The single writer is unchanged (`INV-LED-*`, `INV-BAL-*`); a regional recovery loses at most the
day since the copy, recovered by reconciliation as ADR-0149 states.

## Invariants / Constraints

ADR-0129's: one writer of the journal, never two regions accepting postings. ADR-0128's: no money moves on a restored database before the
post-recovery gate and a person's release.

## Follow-up

Built and checked with mock providers, never applied (`infra/scripts/terraform-check.sh`, the plants `region-copy-in-region`,
`ring-not-replicated` and `recovery-site-in-primary-region`). The at-need rebuild in the recovery region is `THREAT_MODEL.md` §6 item 72;
the account's loss is item 71; the procedure is `OPERATIONS_RUNBOOK.md` §11.65.
