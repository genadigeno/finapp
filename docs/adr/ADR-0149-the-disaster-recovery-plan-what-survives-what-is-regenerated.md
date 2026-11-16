# ADR-0149 — The disaster-recovery plan: what survives outside the environment, what is regenerated, and the recovered platform proven before any role logs in

Status: Accepted (2026-11-16, `P16-DOC-001` — read against the code and corrected first)
Date: 2026-11-13
Phase: 16
Context: Platform · every module (recovered) · Ledger · Reconciliation
Rests on: ADR-0128 (tiers 1-4, decisions 3, 4 and 5); ADR-0140 (the hold, the release); ADR-0123 (secrets and key rings); ADR-0121
(infrastructure as code); ADR-0122 (images by digest); ADR-0129 (one region, active-passive); `OPERATIONS_RUNBOOK.md` §11.35, §11.55
and §11.64; `THREAT_MODEL.md` T32 and T42, §6 items 1, 23 (a), 38 and 62 *(read "21 (a)" here and in decision 2 until `P16-DOC-001`, 2026-11-16: the numbering slip ADR-0150 names)*; owner decisions C4 and C9 of `PHASE_16_PLAN.md` §2.2.

## Context

ADR-0128 set recovery objectives by tier and decided that no money moves on a restored database until the platform's own checks
pass and a person releases it. ADR-0140 made the hold the role lock and the release a recorded act. Neither says what a recovery
from the loss of the whole environment needs (ADR-0128's tier 3: the production account lost; tier 4, a region, is the same into
another region): which things must already exist outside the environment, which are made again, in what order the platform comes
back, and how the drill shows it. Three facts shape the answer. Field-level evidence is encrypted under key rings whose versions are
stamped on every row and never re-encrypted (ADR-0123): a version lost is evidence lost. The platform's credentials are the lost
environment's, and in tier 3 the account may be compromised. And a replica starting while the registry is unreachable cannot pull
its image (T42, accepted by the owner until 2027-06-30 and re-owned to this phase).

## Decision

1. **What must survive outside the environment, before any disaster** (the vault): the repository at the release's commit; the
   database's backup and its manifest, encrypted, each with its digest; an escrow of every version of every field-level key ring,
   encrypted under a key apart from the backup's, with each listed version's check value; the release record (the digests the
   fleet ran); the image set the release needs; the configuration fingerprint the fleet ran. Locally the vault is a directory
   outside the cluster; in a real environment it is the dedicated backup account (item 23 (a)) and a registry and key store there.
2. **Credentials are regenerated; key-ring versions are recovered; nothing else is carried.** Every password, broker credential,
   certificate, provider and callback key and the abuse key is generated again in the rebuilt environment and never restored from
   any copy, so a compromised account's credentials die with it; a provider's shared secret is issued again with that provider. A
   ring's version is never generated in a recovery: it comes from the escrow, and its published check value refuses any replica,
   or the verification, holding another value under that number. The local generator takes the rings and refuses any other name.
3. **The released bytes are redeployed, never rebuilt.** The fleet runs the release record's digests, from the image set. A
   starting replica needs no registry: locally the images are imported into each node with every node's registry access cut for
   the whole rebuild (T42's local answer, E88); in a real environment a registry in the recovery account holds the release's
   images, copied across the account boundary with the backup (designed only; `THREAT_MODEL.md` §6 item 71 (b)).
4. **The order, each step timed:** declare; the infrastructure from code; the images; the namespaces and policies; credentials
   generated and the rings recovered; the data tier and the broker from code (the broker empty); the bootstrap; the platform's
   roles held `NOLOGIN`; the restore, its counter, its comparison with the manifest and the platform's own verification; the
   window; the person's recorded release; only then the migrator, the fleet at its floor, the edge, the audited replay of the
   window before the restore point, money moved and the census; the configuration fingerprint compared with the lost fleet's;
   the fleet scaled. A refusal at any step leaves the database held and unused (§11.35 step 8).
5. **Every tier's objective names its evidence or its owed run.** The targets are ADR-0128's; a local drill measures the
   procedure's durations at the local size, never a managed service's, and no recovery time or loss window is claimed until its
   run is made (`POSTPONED_ISSUES.md` PI-025). Tier 1: E05; tier 2: E59, E60 and E61; tier 3: E87; tier 4: designed only, its
   local analogue E87 *(Corrected by `P16-DOC-001`, the Phase 16 exit review, 2026-11-16: since ADR-0150, 2026-11-14, tier 4 has a standing site in production's recovery region, holding the database's daily region copy and every ring version, checked with mock providers and never applied; its rebuild and run stay designed only, `THREAT_MODEL.md` §6 item 72)*; Kafka: E09 and E87's broker step.

## Alternatives Considered

### Restore the secret store whole from a copy
Pros: one step. Cons: in tier 3 the account may be the compromise, and its credentials would come back with it; a plaintext copy
of credentials is a second place to lose them. Rejected; only the rings are carried, and only because the evidence needs them.

### Generate the rings again and accept the evidence written under the old ones as lost
Pros: nothing to escrow. Cons: provider evidence, KYC documents, dispute and payout evidence and operators' factors become
unreadable for good, and `INSERT`-only evidence cannot be re-encrypted. Rejected; the escrow's mechanism in a real environment is
item 71 (a)'s.

### Rebuild the images from source in the recovery
Pros: no image store to keep. Cons: the bytes would not be the released bytes the release record and its evidence name; the
build's own dependencies need registries. Rejected.

### Start the fleet against the restore and let readiness hold it
Rejected by ADR-0140: the hold is the database's role lock, which needs no replica to read anything.

## Consequences

Positive: the recovered platform proves its books before any platform role can log in, and a recovery can never carry a credential.
Negative: the vault holds the image set (several GiB locally); the rings' escrow is a second place their material lives, under its
own key. Operational impact: a disaster recovery is a declared incident (§11.12) with the order above; the drill is §11.64.
Security impact: no credential survives the environment; the rings' escrow is ciphertext under a key apart from the backup's; the
verification reaches the database as an administrator does, through the API server, never through a policy opened for it.
Financial impact: everything committed by the backup's instant is present and proven; everything after it is lost by construction
and found by the providers' statements and reconciliation, as §11.35 says.

## Invariants / Constraints

ADR-0128's: `INV-ACC-01`, `INV-ACC-06`, `INV-ACC-12`, `INV-BAL-02`, `INV-HIST-01` and `INV-REC-*` judged before money moves; the first
rule of §11.35 never relaxed between the restore and the release.

## Follow-up

The exercise is built and contract-tested (`DisasterRecoveryRehearsalContractTest`); its runs, E87, E88 and E89, are owed
(`POSTPONED_ISSUES.md` PI-025). The rings' escrow and the image store in a real environment are `THREAT_MODEL.md` §6 item 71, for the
owner. The release's record in the restored database's own trail is item 62 (a)'s, decided by the owner on 2026-11-13 and held by
`POSTPONED_ISSUES.md` PI-026 until the first real environment's managed restore rehearsal.
