# Security Architecture

Security objectives:
- confidentiality of PII and financial data
- integrity of financial state
- strong authentication
- least privilege
- auditable privileged actions
- secure service-to-service communication
- minimized PCI/card-data exposure

Controls to consider:
- OAuth2/OIDC
- MFA
- WebAuthn/passkeys
- RBAC/ABAC
- service identity
- mTLS where appropriate
- secrets management
- KMS/key rotation
- encryption at rest and in transit
- tokenization
- field-level protection where appropriate
- secure logging
- privileged-access controls
- rate limits / abuse controls

## Logging

Logs are ECS JSON in every environment, including a developer's machine (`P0-TSK-030`, ADR-0019).
Human-readable locally and JSON in production would mean the encoder that matters is the one nobody
tests, and a redaction defect living in it would be invisible to everyone who never runs it.

`correlationId`, `traceId` and `spanId` are queryable fields lifted from the MDC, so a log line
joins to its trace and to the durable record.

**A secret cannot be stored in a type that would print it.** `Sensitive<T>` masks on every
rendering path, and `secretsAreWrapped` fails the build on any field or no-argument accessor whose
name says it holds a secret unless it is wrapped. This is `INV-AUD-02`'s own prescribed
enforcement - default-deny - rather than an annotate-the-sensitive-fields scheme that fails on the
field somebody forgot.

**What it does not cover**, stated so it is not mistaken for total coverage: a secret held only in
a local variable and passed straight to a log call has no declaration to inspect. An output
scrubber would net some of those and is recorded as debt; it is a deny-list and must never be
mistaken for the control.

## Operational endpoints

The actuator is an allow-list, not a default (`P0-TSK-027`, ADR-0016). Only `health` and `info`
are served. The rest exist as beans whether or not they are reachable, and each is one property
away from being exposed:

| Endpoint | What it would give an unauthenticated caller |
|---|---|
| `/actuator/heapdump` | Every secret the process has ever held in memory |
| `/actuator/env`, `/actuator/configprops` | The configuration, including connection details |
| `/actuator/beans`, `/actuator/mappings` | A map of the application |
| `/actuator/loggers` | A writable endpoint |

Health responses publish **that** something is wrong, never **what**: `show-details: never` and
`show-components: never`, because a detailed body names the JDBC URL, the host, the database, the
driver and the failing exception. `when-authorized` becomes correct once `P0-EPIC-10` provides an
authority to authorize against; there is none yet, so `never` is the honest setting.

`/actuator/info` publishes build identity only - no JVM version, no OS, no environment
properties. A JVM version is free reconnaissance for anyone matching a CVE to a target.

The application connects to PostgreSQL as `finapp_app`, never the bootstrap superuser, so a
health check cannot pass on privileges the application would not otherwise hold.

## Transport encryption

The expectations per hop, and what enforces them (`P0-TSK-034`, ADR-0023).

| Hop | Local | Deployed | Enforced by |
|---|---|---|---|
| Application to PostgreSQL | plaintext, loopback only | `sslmode=verify-full` | `TransportSecurityGuard` - the application refuses to start otherwise |
| Application to Kafka | `PLAINTEXT` listeners | TLS with client authentication | *Decided, not yet implemented* - there is no Kafka client on the classpath |
| Application to Redis | plaintext, no auth | TLS, and a credential | *Decided, not yet implemented* - there is no Redis client |
| Inbound HTTP | plaintext | TLS terminated at the edge; the application is never the TLS endpoint | *Decided, not yet implemented* - Phase 15, with deployment |
| Application to a provider | none exist | TLS with certificate verification, never a disabled check | Phase 5, with the first adapter |

**The default this closes.** The PostgreSQL driver's `sslmode` default is `prefer`: it attempts TLS
and **silently falls back to plaintext**. Measured against this repository's own container, which
runs `ssl = off` — unset and `prefer` both connected unencrypted with no warning; `require` and
`verify-full` were refused. The platform sets no `sslmode`, which is correct locally and would be a
plaintext connection to a remote database in a deployment, with nothing saying so.

That is what the acceptance criterion means by *local setup must not normalise insecure defaults
into later environments*, and a comment saying "remember to set sslmode" is not a control. The
control is that a non-loopback database requires `verify-full`.

**Why `verify-full` and not `require`.** `require` encrypts and verifies nothing — it stops passive
eavesdropping, not an active attacker presenting their own certificate, which is the threat on the
path to a financial database. `verify-ca` checks the issuer but not the hostname, so it still
accepts a valid certificate issued for a different host. Only `verify-full` checks both.

**Loopback is exempt, deliberately.** A connection over loopback does not leave the host. Requiring
TLS there would mean every developer provisioning certificates for a container, and a setup step
that elaborate is one people work around — which costs more security than it buys.

**What the guard cannot see.** A libpq service file (`?service=name` with `pg_service.conf`) can
also set `sslmode`, and the application cannot read it. It fails in the safe direction only: a
service file setting a weak mode is still refused, and one setting `verify-full` produces a false
refusal. The driver reads `sslmode` from no environment variable, so there is no silent override.

## Encryption at rest

**Nothing in this platform is encrypted at rest today, and nothing in it needs to be yet.** Phase 0
holds no customer data, no money and no credentials; `DATA_CLASSIFICATION.md` records that no column
currently carries data above `CONFIDENTIAL`, though several are classified at a higher ceiling for
the data they will hold.

| Concern | Position | Owning phase |
|---|---|---|
| Database volume encryption | An infrastructure property, not an application one. Provisioned with the cluster | Phase 15 |
| Backups | Encrypted, and the key held separately from the backup. A backup is a copy of the ledger | Phase 15 |
| Object storage (settlement files, documents) | Server-side encryption, and evidence integrity by checksum (`INV-HIST-02`) | Phase 8 |
| Column-level encryption or tokenisation | Per classification level, when a column actually carries restricted data. Card data is tokenised at the boundary and never stored (`DECISIONS.md`) | Phase 1 (PII), Phase 5 (cards) |
| Key management and rotation | A KMS decision, meaningless without keys | Phase 15 |

**The local stack is deliberately unencrypted**, and that is recorded rather than left implicit:
volumes are plain Docker volumes on a developer's disk. The boundary is that nothing local is a
pattern for a deployment — the same statement `compose.yaml` makes about its credentials.

## Who is acting

`SecurityContext` carries the current `Actor` for the executing flow and across thread handoffs
(`P0-TSK-032`, ADR-0021). `Actor` and `ActorType` live in `platform.security`: audit *records* an
actor, it does not own the concept of one.

**An unestablished actor is an error, never the system actor.** `require()` throws rather than
defaulting. Defaulting is convenient and correct while the system is the only actor there is, and
wrong the moment real identity arrives - an authenticated request whose scope was never established
would record the platform as having done what a customer did. Nothing fails; the record is complete,
plausible, about the wrong party, and permanent (`INV-HIST-03`).

Phase 0 claims the system actor explicitly through `SecurityContext.enterSystem()`, which is the
searchable list of places Phase 1 must revisit. Reading `Actor.SYSTEM` anywhere else fails the build
(`onlyTheSecurityContextClaimsTheSystemActor`), because every audit record needs an actor and the
constant is the shortest way to make a call site compile when none was established.

**The first caller arrived with `P1-TSK-006`, and it is an `enterSystem()` that stays.**
`RegistrationService` establishes the scope for `POST /v1/registrations`, and the actor is the
platform because the caller is **unauthenticated**: there is no other honest answer. Attributing the
action to the Party it creates was considered and rejected - it is circular, and it is unavailable
on the refusal path, where nothing was created, so the actor would differ between success and
failure. What carries the information instead is the audit record's **target**, which is the
attempted login identifier on both paths.

Phase 1 revisits these call sites and this one survives the review. The `enterSystem()` sites that
must go are the ones where a real actor exists and was not established; a public registration
endpoint is not one of them, and the distinction is worth writing down because "revisit every
`enterSystem()`" reads as "remove every `enterSystem()`" and would be wrong here.

The actor is deliberately not part of the correlation context. A correlation identifier names one
execution and attributes nothing to anybody; an actor names a party. Merging them would put a
customer identifier into every log line and every span, which is a disclosure into systems with
different access control and retention (`INV-AUD-02`).

## Secrets

The approach is [`SECRET_MANAGEMENT.md`](SECRET_MANAGEMENT.md) and ADR-0020. In summary
(`P0-TSK-031`):

**No credential value is in this repository**, and that is a build rule rather than a promise.
`CommittedConfigurationHoldsNoSecretTest` fails the build unless every value assigned to a
credential-named key in committed configuration is externalised, or is the one marked local
default. Files are discovered, not listed, so a new configuration file is covered without anyone
remembering.

**The CI secret scanner is a net, not the control.** Probing it found that gitleaks catches a
private key, a high-entropy token and a real-shaped AWS key pair, and misses `password: hunter2` -
there is nothing about a memorable password to detect, and a memorable password is what a human
commits. "Secret scanning green" and "no secret in the repository" are therefore different claims.
The two mechanisms are blind in different directions, which is why both run.

**A name is not a control.** The marked local default is published deliberately, and
`DatabaseCredentialGuard` refuses to start the application when it is aimed at a database that is
not on loopback - closing the one documented way around externalised configuration, which is
forgetting to set the variable.

Never put secrets, API credentials, private keys, or raw payment credentials in source code.
