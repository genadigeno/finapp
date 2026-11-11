# ADR-0123 — Secrets and key management: a managed secret store and KMS per environment, delivered as the environment the application already reads, field-level keys as versioned key rings

Status: Proposed
Date: 2026-11-07
Phase: 15
Context: Platform (secrets) · Security · every module holding a cipher (identity, kyc, payments, merchant, credit, fx,
settlement)
Rests on: ADR-0019, ADR-0020, ADR-0023, ADR-0032, ADR-0052, ADR-0083, ADR-0120, ADR-0121; owner decision C6 of
`PHASE_15_PLAN.md` §2.2 (ratified at its recommended default by the owner, 2026-11-07; *read "recommended default awaiting ratification" until then*); `SECRET_MANAGEMENT.md` §7; `SECURITY_ARCHITECTURE.md`
§Encryption at rest.

## Context

Secrets come from environment variables. Each one has a marked local default, and the guards confine that default to
loopback (ADR-0020). The code reads 37 key, secret, password and token properties *(27 distinct `finapp.*` credential
properties, each with its own `ConfinedCredential` key class, besides the database's credentials - recounted from the
code by `P15-TSK-001`, 2026-11-08; 35 with the eight `key-version` companions, which are not secrets)*. Eight AES-256-GCM cipher classes, built as nine
instances (`EvidenceCipher` twice, `PaymentBeans.java:83` and `:829`) over eight keys (`DocumentCipher` and
`CounterpartySubjectCipher` share `finapp.doc.key`, `CounterpartyScreeningBeans.java:65-76`), stamp a key version on
every row, but each holds only one active key. A rotation would therefore make every earlier row
unreadable, and many of these tables are `INSERT`-only evidence that must never be rewritten. There is no secrets
manager, no KMS and no rotation or revocation procedure. `SECRET_MANAGEMENT.md` and `SECURITY_ARCHITECTURE.md` still say
that no keys exist *(corrected by `P15-TSK-001`, 2026-11-08)*.

## Decision

1. **Storage.** A managed secret store holds every secret: AWS Secrets Manager in the reference (C1). KMS keys exist
   per environment and per purpose (etcd, database volume, backups, secrets, field-level key wrapping). Production and
   non-production use separate accounts and keys.
2. **Delivery.** External Secrets Operator syncs each secret into a Kubernetes Secret, which etcd envelope-encrypts with
   KMS. The Secret is injected as the environment variable the application already reads, so **the application's
   configuration code does not change**. Rotation takes effect through a rolling restart triggered by the secret's
   version. No committed manifest carries secret data, and a policy refuses one that does.
3. **Access.** Each workload has its own cloud identity that can read only its own secrets. Humans cannot `get` secrets
   in production. Break-glass access is time-bound and audited.
4. **Field-level key rings.** Each key - not each class: two classes share one key, and one class is built twice over
   its key - holds a ring: one **active** version for encryption and **retained** versions
   for decryption. A row is always decrypted with the version stamped on it. Rotation adds a version and moves the
   active pointer. **`INSERT`-only evidence is never re-encrypted.** A version is retired only when no row under it
   remains. Destroying a version on purpose (crypto-shredding) is the designed mechanism for a future purge. It is not
   exercised in Phase 15 (C12).
5. **Rotation without an outage.** Database credentials rotate by alternating users, **each holding exactly the
   per-table grants made to `finapp_app`** (by membership of a role that carries them; the grants stay in the
   migrations). Those users are created by the one-time bootstrap step that holds the master credentials, never by the
   per-release migrator (`finapp_migrator` is `NOCREATEROLE`). Kafka SCRAM credentials are added,
   rolled, then removed. HMAC callback secrets are verified against both the current and the previous secret during a
   window. Ingress certificates are renewed by cert-manager, with an alert on days to expiry.
6. **Revocation and recovery.** A runbook covers each class: database, Kafka, provider, field-level key, signing
   identity and certificate. It states what to rotate, what to restart, what to revoke (sessions through identity's
   existing command), and what stays readable. KMS key deletion is protected by a waiting period.
7. **Local stays local.** ADR-0020's marked default and the loopback guards remain the backstop. A local value can never
   start a deployed instance.
8. **JWT/JWKS is not applicable today.** The platform issues opaque sessions (ADR-0030). With workforce OIDC (C13), the
   identity provider will own the JWKS.

*Dated note (`P15-TSK-018`, 2026-11-09), on point 2, from what was built and read:* **one `ExternalSecret` per workload**, with one named entry per secret, because the workloads read one Secret each through `envFrom` and one object writes its target in one operation; `P15-TSK-018`'s entry said one per secret, which would need `Merge` into one target. **`refreshPolicy: OnChange`, `refreshInterval: 0s`**: the operator does not poll, so a store write alone moves nothing; it still reads the store when the object's spec or metadata changes and when its Secret is missing or altered. **`creationPolicy: Owner`** (deleting the object removes its Secret) and **`deletionPolicy: Retain`** (a store that stops answering strips nothing). "Triggered by the secret's version" is, concretely, a commit changing `finapp/rotation-generation` on the pod template after the same number on the `ExternalSecret` has synced: nothing restarts the fleet by itself (`SECRET_MANAGEMENT.md` §5.2). A `SecretStore` and a reader ServiceAccount per workload, so that the workloads' own accounts carry no cloud identity, and one store more for the entry the managed brokers name, which the application reads rather than copies. Built as manifests and checked over them; no cluster or store has run them.

*Dated note (`P15-TSK-019`, 2026-11-09), on points 4 and 5, from what was built and read:* **a ring is per key, so eight rings** (the two `EvidenceCipher` instances each hold their own key, and each its own ring; this ADR's "built twice" counted instances, and a backlog entry's "one ring" over them was corrected to two). **Ciphertext formats did not change**: the version stays where each cipher already put it (settlement on `settlement.file`, the others beside the ciphertext), and a row with no version has no ciphertext. **Two inputs make "known" and "active" separate steps**: `ring.versions` (a replica refuses to start without a key for every version listed, so a finished rollout means the fleet holds it) and `ring.active-version`, moved by a later change, with `infra/scripts/key-ring-activation-check.sh` refusing, in CI against the base commit, a change that activates a version the base did not already list. What no check here can prove is that the first rollout finished: that is the runbook's gate, section 11.29. **Retirement is checked over the real tables**: a replica started with a retired version refuses if any row still stands under it, and the guard lists every key-version column, held to the migrations by a test; credit's version column is unreadable to the application's role, so that ring refuses every retirement until a grant exists. Retiring on purpose with rows under it, crypto-shredding, stays designed and unbuilt (C12). The callback doors take a previous key beside the current one until a stated instant, at most seven days ahead of a replica's start: both are compared in constant time, and the instant closes the window with nothing restarted.

*Dated note (`P15-TSK-020`, 2026-11-11), on points 2, 4 and 6, from what was built and rehearsed:* **a check value per ring version** (`THREAT_MODEL.md` §6 item 32 (a), the owner's ruling): HMAC-SHA256 of a fixed label under the version's key, its first eight bytes in hexadecimal, published in the ring's non-secret configuration (`ring.check-values`). A replica whose stored value for a version does not give that version's published value refuses to start; once a ring holds more than one version every held version needs one, and CI refuses a change that alters a listed version's. One computation in `KeyRing`, one tool a person runs (`infra/scripts/KeyCheckValue.java`), held equal by a test. A ring of one version with none starts as before (§6 item 36 (b)). **The database's fleet credential alternates between two users per role**, `finapp_app_a`/`_b` and `finapp_reporting_a`/`_b`, each holding its carrier's grants by membership alone (the grants stay in the migrations), created by the bootstrap step with no password; a rotation gives the inactive one a password and moves the store to it, and the old user is retired with its password removed. **The broker's SCRAM credential rotates by a second user** that `modules/kafka` plans with the principal's exact ACLs before the roll, and the application's reader may read both entries. Every class the entry named, and each one's rollback, has run on one `kind` cluster under the workload, with the operator from its pinned chart reading a store generated per run (`OPERATIONS_RUNBOOK.md` §11.33); a provider's API credential has not (the simulated provider verifies none), and nothing has run against a cloud store.

## Alternatives Considered

### HashiCorp Vault
Pros: dynamic database credentials and a transit engine.
Cons: it is another stateful, highly available system for the platform to operate and secure. The managed store does the
same job with less to run.

### Secrets Store CSI driver (files, no Kubernetes Secret)
Pros: secrets never reach etcd.
Cons: the application reads environment variables, and a reload would still need a restart. etcd encryption with KMS
closes the same gap.

### Re-encrypting every row on rotation
Cons: this rewrites `INSERT`-only evidence (`INV-HIST-01`), and the grants refuse it anyway.

## Consequences

Positive: every secret has an owner, a store, a rotation, a revocation and an audit trail. A key can be rotated without
losing history.
Negative: the ciphers change from one key to a ring, which is a code change in eight places behind one `platform`
abstraction.
Operational impact: rotations become rolling restarts and runbook steps.
Security impact: a leaked secret can be revoked without an outage, and a leaked key version is bounded to the rows under
it.
Financial impact: none. No financial record is rewritten.

## Invariants / Constraints

There are no production secrets in source control. `INV-HIST-01`: evidence is never re-encrypted in place. Every
decryption uses the stamped version.

*Dated note (`P15-TSK-016`, 2026-11-09), on points 1 and 3, from what was built:* each key purpose of point 1 is a KMS key of `modules/security`, and two more exist: `field_keys` is the field-level purpose's own, and `logs` serves the CloudWatch log groups and the telemetry buckets. Point 3's cloud identity is one reader role per workload, trusted by that workload's reader ServiceAccount, naming each entry it may read. A separate bootstrap role, absent unless a change turns it on, reads what the one-time bootstrap step reads, and no standing human role reads a secret. Every one of these is plan-only: no account exists.

## Follow-up

`P15-TSK-016`, `-018`, `-019` and `-020` build this ADR. `P15-TSK-001` corrects the two stale documents. The purge by
crypto-shredding waits for a real jurisdiction (C12).
