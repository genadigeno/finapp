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

*Dated note (`P15-TSK-016`, 2026-11-09), on points 1 and 3, from what was built:* each key purpose of point 1 is a KMS key of `modules/security`, and two more exist: `field_keys` is the field-level purpose's own, and `logs` serves the CloudWatch log groups and the telemetry buckets. Point 3's cloud identity is one reader role per workload, trusted by that workload's reader ServiceAccount, naming each entry it may read. Break-glass, the only human reach to a secret, reads what the one-time bootstrap step reads. Every one of these is plan-only: no account exists.

## Follow-up

`P15-TSK-016`, `-018`, `-019` and `-020` build this ADR. `P15-TSK-001` corrects the two stale documents. The purge by
crypto-shredding waits for a real jurisdiction (C12).
