# Phase 1 — Identity and Customer Foundation

The engineering plan. [`DELIVERY_PLAN.md`](DELIVERY_PLAN.md) §Phase 1 states the objective and
scope in summary; this document is the elaboration the entry gate requires (`PHASE_GATES.md` §2
criteria 3–10). The task list is in [`BACKLOG.md`](BACKLOG.md) §Phase 1.

**Status:** entry gate satisfied except criterion 1 (Phase 0 not yet `COMPLETE`). See
[`reviews/PHASE_0_TO_1_TRANSITION.md`](reviews/PHASE_0_TO_1_TRANSITION.md).

---

## 1. Business scope

**Establish who the actors are, prove who they claim to be, and make every subsequent financial
action attributable to an authenticated, authorised actor.**

Phase 1 delivers a platform where a person can be registered, can authenticate with a second
factor, holds sessions that can be listed and revoked immediately, is authorised per operation, can
recover an account without that becoming the way in — and where every privileged action produces an
audit record naming who did it.

**It moves no money.** It has no accounts, no balances and no ledger. Its output is the actor that
Phases 3 and 4 will attribute postings to — which is why `ROADMAP.md` makes Phase 4 depend on
Phase 1 as well as Phase 3: *a posting whose actor cannot be identified is not auditable*.

### What "done" looks like

A `POST /v1/registrations` creates a Party, a Customer and an Identity in one transaction. That
Identity can authenticate, is challenged for a second factor, receives a session, calls an endpoint
that checks both permission and ownership, lists and revokes its own sessions, and recovers access
through a verified channel — with an audit record for each privileged step and a negative test for
each protected endpoint.

## 2. Bounded contexts

| # | Context | Module | Role in Phase 1 |
|---|---|---|---|
| 1 | Party & Customer | `party` | **Owns** Party, Customer |
| 2 | Identity, Authentication & Authorization | `identity` | **Owns** Identity, Credential, MFA enrolment, Device, Session, Role assignment |
| 26 | Audit | `platform` | **Consumed.** Phase 0 built it; Phase 1 is its first real writer |

Context 2 is renamed by ADR-0031: it previously read "Identity & Authentication" while owning role
assignment, so the context list omitted a concept `CLAUDE.md` forbids collapsing. The merge is now
recorded with a split trigger, as ADR-0012 requires of every merge.

**No other context participates.** Phase 2's KYC attaches to Party later and Phase 1 must not
anticipate it.

## 3. Capabilities — in and out

| Capability | Phase 1? | Reasoning |
|---|---|---|
| Party registration and lifecycle | ✅ **In** | The root aggregate; everything references it |
| Customer relationship, distinct from Party | ✅ **In** | ADR-0029; the distinction is unpickable later |
| Identity lifecycle | ✅ **In** | |
| Password credential (Argon2id) | ✅ **In** | ADR-0032 |
| Authentication, enumeration-safe | ✅ **In** | `INV-IDN-07` |
| Brute-force and credential-stuffing controls | ✅ **In** | Not an extra: ADR-0032 makes login the most CPU-costly operation |
| Session issuance, listing, revocation | ✅ **In** | ADR-0030 |
| Authorization — roles plus ownership | ✅ **In** | ADR-0031; `INV-AUD-03` |
| MFA — TOTP | ✅ **In** | The second factor `INV-IDN-05` is about |
| Actor-attributed audit | ✅ **In** | The phase's reason for existing |
| Account recovery | ✅ **In**, last | `INV-IDN-06`; deliberately built against a working MFA and session model rather than beside them |
| Device registration and trust | ⚠️ **Minimal** | A session records the device it was established from, so revocation can be reasoned about per device. **No trust scoring, no "remember this device" MFA bypass** — that is a risk decision and belongs to Phase 13 |
| WebAuthn / passkeys | ❌ **Deferred** | Wanted, and not required to close Phase 1's gate. The assurance level (ADR-0030) already has a `STRONG` rung reserved, so adding it later is additive rather than structural. Deferring it keeps the phase finishable; carrying it would put a second, larger authenticator model beside a recovery flow that has not been built yet |
| Step-up authentication | ⚠️ **Seam only** | The assurance level exists and operations can require one. Nothing in Phase 1 requires `MULTI_FACTOR` for a specific action, because Phase 1 has no high-value action. Consumed by Phase 4 |
| Party merge | ❌ **Deferred** | Two records found to be one person is a Phase 2 concern that arrives with verification evidence. Designing it now is designing against nothing |
| Organisations, delegated access | ❌ **Deferred** | The ADR-0031 split trigger names this as what would force an `authorization` module. Phase 6 (merchant) is the first plausible need |

## 4. Domain model

### Aggregates

| Aggregate | Module | Identity | Invariants it holds |
|---|---|---|---|
| **Party** | `party` | `PartyId` | Exists once; never deleted |
| **Customer** | `party` | `CustomerId` | References exactly one Party for life; lifecycle is open → suspended → closed |
| **Identity** | `identity` | `IdentityId` | References one Party; may hold many Credentials but at most one *active* per type |
| **Credential** | `identity` | `CredentialId` | `INV-IDN-01`, `INV-IDN-02`. Entity within Identity — never independently addressable |
| **Session** | `identity` | `SessionId` | `INV-IDN-03`; records assurance level and device |
| **RecoveryRequest** | `identity` | `RecoveryRequestId` | `INV-IDN-06`; single-use, expiring, channel-bound |

**Credential is an entity inside the Identity aggregate, not an aggregate.** A credential has no
meaning apart from the identity it authenticates, and the rule "at most one active credential per
type" is an invariant across the set — which is exactly what an aggregate boundary is for.

**Session is its own aggregate**, despite belonging to an identity. Sessions are created and
revoked far more often than identities change, they are queried independently, and loading every
session to authenticate one request would make the aggregate boundary a performance liability with
no invariant to justify it. The cross-aggregate rule — *revoking an identity revokes its sessions* —
is enforced in one transaction inside `identity`.

### Value objects

`PartyName`, `EmailAddress` (normalised, and never the identity's key), `PhoneNumber`,
`CredentialDerivation` (wrapped, never rendered), `AssuranceLevel`, `RoleName`, `PermissionName`,
`DeviceFingerprint`, `RecoveryToken` (single-use, hashed at rest like a credential).

**`EmailAddress` is not the login identifier.** A login identifier that is also a contact channel
cannot be changed without changing how someone logs in, and cannot be verified without blocking
login. `Identity` carries an opaque identifier and a separate, changeable, separately-verified
email.

### Commands

`RegisterParty`, `OpenCustomerRelationship`, `SuspendCustomer`, `CloseCustomer`,
`CreateIdentity`, `SetCredential`, `Authenticate`, `EnrolMfa`, `VerifyMfa`, `IssueSession`,
`RevokeSession`, `RevokeAllSessions`, `AssignRole`, `RevokeRole`, `InitiateRecovery`,
`CompleteRecovery`.

### Domain events

Owned and published by Phase 1, through the outbox (`INV-EVT-01`), carrying the full envelope
(`INV-EVT-03`) and **no credential material and no unnecessary PII** (`INV-AUD-02`):

| Event | Owner | Carries |
|---|---|---|
| `PartyRegistered` | `party` | `PartyId`, party kind |
| `CustomerOpened` | `party` | `CustomerId`, `PartyId` |
| `CustomerSuspended` / `CustomerClosed` | `party` | `CustomerId`, reason code |
| `IdentityCreated` | `identity` | `IdentityId`, `PartyId` |
| `CredentialChanged` | `identity` | `IdentityId`, credential type. **Never the derivation** |
| `AuthenticationSucceeded` | `identity` | `IdentityId`, assurance level |
| `AuthenticationFailed` | `identity` | **No identity identifier** — see below |
| `MfaEnrolled` | `identity` | `IdentityId`, factor type |
| `SessionRevoked` | `identity` | `SessionId`, `IdentityId`, reason |
| `RoleAssigned` / `RoleRevoked` | `identity` | `IdentityId`, `RoleName` |

**`AuthenticationFailed` deliberately carries no identity identifier.** A failure event naming the
account it was for is an enumeration oracle for anyone who can read the event stream, and the
stream reaches systems with different access control (`INV-AUD-02`, `INV-IDN-07`). The audit record
— which is access-controlled and is the regulatory artefact — carries the attempted identifier; the
integration event does not.

### State machines

**Customer:** `PENDING → ACTIVE → SUSPENDED ⇄ ACTIVE`, `→ CLOSED` (terminal, `INV-LIFE-04`).
Reopening is a new relationship, not a transition out of `CLOSED`.

**Identity:** `ACTIVE ⇄ SUSPENDED`, `→ CLOSED` (terminal).

**Session:** `ACTIVE → REVOKED` (terminal) or `→ EXPIRED` (terminal). No transition out of either;
"extending" is a new session.

**Credential:** `ACTIVE → SUPERSEDED` (terminal). A credential is never edited — a change creates a
new one and supersedes the old, which is `INV-HIST-01`'s reasoning applied to authentication.

**RecoveryRequest:** `INITIATED → VERIFIED → COMPLETED` (terminal), or `→ EXPIRED` / `→ CANCELLED`
(terminal). Single use: `COMPLETED` cannot be re-entered.

### Invariants Phase 1 must protect

`INV-IDN-01` … `INV-IDN-07` (new, this transition), `INV-AUD-01`, `INV-AUD-02`, `INV-AUD-03`,
`INV-HIST-03`, `INV-IDEM-01`, `INV-IDEM-03`, `INV-EVT-01`, `INV-EVT-03`, `INV-LIFE-01`,
`INV-LIFE-02`, `INV-LIFE-04`, `INV-CON-01`.

## 5. Security model

| Concern | Decision |
|---|---|
| **Authentication** | Password (Argon2id, ADR-0032) plus TOTP second factor. Enumeration-safe: one response shape and equivalent timing for existing and absent accounts |
| **Authorization** | Permission at the boundary, ownership in the domain, both always (ADR-0031). Deny by default |
| **MFA** | Required to reach `MULTI_FACTOR`. Enforced as an assurance *level* on the session, so no alternative route can produce an equivalent session (`INV-IDN-05`) |
| **Session security** | Server-side, opaque identifier, rotated on every privilege change, idle **and** absolute expiry, immediate revocation (ADR-0030) |
| **Credential lifecycle** | Derivation plus queryable algorithm and parameters; upgrade-on-use; change revokes all other sessions (ADR-0032) |
| **Recovery** | Proof of control of a **previously registered and verified** channel. Rate-limited, cooling-off, notification to the registered channel, single-use expiring token hashed at rest. Never lowers the assurance required (`INV-IDN-06`) |
| **Privileged actions** | Role assignment, identity suspension, forced session revocation, credential reset by an operator. Each requires an explicit permission, produces an audit record, and has a negative test |
| **Audit** | Every command above writes an `AuditRecord` in the **same transaction** as its effect. `identity` and `party` each declare an `AuditableAction` enum, joining the registry Phase 0 built (`P0-TSK-023`) |

**`SecurityContext.enterSystem()` call sites are revisited in this phase**, as
`SECURITY_ARCHITECTURE.md` says they must be. They are the greppable list of places claiming the
platform acted, and Phase 1 is when a real actor exists to replace most of them.

## 6. Data model

**Authoritative data**, all in PostgreSQL, all owned by exactly one module:

| Table | Module | Notes |
|---|---|---|
| `party.party` | `party` | |
| `party.customer` | `party` | FK to party; unique active relationship per party |
| `identity.identity` | `identity` | FK to party **by identifier only**, no cross-module FK |
| `identity.credential` | `identity` | Derivation, algorithm, parameters; partial unique index on (identity, type) where active |
| `identity.mfa_enrolment` | `identity` | Secret encrypted at rest under a key outside the database (`INV-IDN-08`). **"Never emitted" is corrected by `P1-TSK-017`**: the QR code *is* the secret, so it is emitted **once**, to the proven owner, and never retrievable afterwards. `INV-IDN-01` cannot apply — a shared secret must be recoverable — and the catalogue now says so |
| `identity.session` | `identity` | Indexed on the opaque identifier; assurance level; device; expiry |
| `identity.device` | `identity` | |
| `identity.role_assignment` | `identity` | |
| `identity.recovery_request` | `identity` | Token stored hashed |

**Schema-per-module**, each with its own Flyway history (ADR-0011). `party` and `identity` are the
first modules other than `platform` to own a schema.

**No cross-module foreign key.** `identity.identity` references a `PartyId` value, not
`party.party(id)`. A database-level FK across a module boundary is a coupling Gradle and ArchUnit
cannot see and would make extraction (ADR-0001's stated escape) a data migration. Referential
integrity across that edge is a domain rule, enforced by the registration transaction.

**Transaction boundaries:**

- **Registration** — Party, Customer, Identity, Credential, audit records and outbox rows commit in
  **one** transaction. Two modules, one database, one transaction; this is exactly the property
  ADR-0001 exists to preserve.
- **Authentication** — session insert, credential upgrade-on-use and audit record in one.
- **Credential change** — new credential, supersede old, revoke other sessions, audit, outbox: one.
- **Recovery completion** — same shape.

**Constraints that carry invariants**, not just tidiness: unique active credential per identity and
type; unique active customer relationship per party; `CHECK` on every state column generated from
its enum (the `P0-TSK-022` pattern); `NOT NULL` on algorithm and parameters (`INV-IDN-02`); session
expiry columns non-null.

**Indexes:** session by opaque identifier (the per-request lookup ADR-0030 accepts); session by
identity (listing and bulk revocation); credential by identity and type; recovery by token hash;
role assignment by identity. Each exists to serve a stated query, and any index without one is
removed.

**Every new column is classified** in `DATA_CLASSIFICATION.md` before it holds anything —
`ColumnClassificationTest` fails the build otherwise (ADR-0022). Phase 1 introduces the platform's
first `RESTRICTED-PII` columns in quantity.

## 7. API model

All under `/v1` (ADR-0015). Every error is an RFC 9457 problem detail (`ERROR_CONTRACT.md`).

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /v1/registrations` | none | **Idempotent** — `Idempotency-Key` required |
| `POST /v1/authentications` | none | Enumeration-safe; rate-limited; lockout |
| `POST /v1/authentications/mfa` | partial session | Challenge/verify; elevates assurance |
| `DELETE /v1/sessions/current` | session | Logout |
| `GET /v1/sessions` | session | Own sessions only — ownership check |
| `DELETE /v1/sessions/{id}` | session | Ownership; immediate |
| `GET /v1/me` | session | Profile |
| `PATCH /v1/me` | session | |
| `POST /v1/me/credential` | session, `MULTI_FACTOR` | Revokes other sessions |
| `POST /v1/me/mfa` | session | Enrolment — returns the provisioning URI **once** |
| `POST /v1/me/mfa/confirmation` | session | **Added by `P1-TSK-017`.** This table listed one row, and the same plan requires that *"enrolment is not complete until confirmed by a valid code"* — which is a second request, because the customer must go and read their authenticator in between. Under-specified rather than wrong |
| `POST /v1/recoveries` | none | Enumeration-safe; always the same response |
| `POST /v1/recoveries/{id}/completion` | recovery token | Single use |
| `POST /v1/identities/{id}/suspension` | session, admin role | Privileged; audited |
| `POST /v1/identities/{id}/roles` | session, admin role | Privileged; audited |

**How a session is presented, and the task that had to build it** (`P1-TSK-016`). The `Auth`
column above says *"session"* for eight endpoints, and this plan named no task that turns a
presented session into a caller. `P1-TSK-020` (permission) and `P1-TSK-021` (ownership) both
*presuppose* one, and `P1-TSK-027` hands a token out rather than consuming one. It is
`Authorization: Bearer <token>` — never a query parameter, which reaches access logs, proxies and
browser history — resolved by `SessionAuthenticationInterceptor` against the database on every
request, with no cache (`INV-IDN-03`, ADR-0030). Built by the first endpoint that needed it, and
recorded here so the next seven do not each rediscover it.

**Idempotency:** Phase 1 moves no money, so `INV-IDEM-01`'s money-moving requirement is vacuous
here — and that is stated rather than left implied. **Registration is made idempotent anyway**,
because a retried registration that creates a second Party is a duplicate person, which is
expensive in a different way. It uses the Phase 0 kernel and `@RequiresIdempotencyKey`
(`P0-TSK-017`), making Phase 1 that mechanism's first real user.

**Authentication behaviour:** a failure returns one shape regardless of cause — unknown identity,
wrong credential, locked account — and takes equivalent time (`INV-IDN-07`).

**Authorization behaviour:** every protected endpoint declares a permission and is refused without
it; every resource-scoped endpoint additionally checks ownership. Both have negative tests.

**The published contract changes**, so `docs/api/openapi.json` moves with every endpoint and each
difference is labelled `BREAKING` or `COMPATIBLE`. Additive endpoints are compatible.

## 8. Failure scenarios

Each has a test, per `DEFINITION_OF_DONE.md` §1.7.

| Scenario | Expected behaviour |
|---|---|
| **Duplicate registration**, same idempotency key | One Party. The original response replayed byte for byte |
| **Duplicate registration**, same email, no key | Enumeration-safe rejection — the same response an unrelated failure gives |
| **Concurrent credential change** on one identity | One wins; the other is a domain outcome, not a lost update (`INV-CON-01`) |
| **Concurrent login and revocation** | Revocation wins. A session must never survive a concurrent revoke |
| **Expired session** | Refused as unauthenticated, indistinguishable from revoked — a distinction would tell an attacker whether the session ever existed |
| **Invalid credential** | One response shape, equivalent timing, failure counted |
| **Account lockout** | After a threshold; the response is unchanged from an ordinary failure. Lockout counters are **database-backed**, never process-local (ADR-0024) |
| **Recovery abuse** — replay, unverified channel, recently changed channel, concurrent with login | Each refused; each its own test (`INV-IDN-06`) |
| **Partial MFA enrolment** — enrolment started, never confirmed | The identity's assurance is unchanged; a half-enrolled factor never satisfies a challenge |
| **Database unavailable** | Readiness reports NOT_READY; authentication fails closed. Never a session issued without a durable record |
| **Partial transaction failure** | Registration is one transaction; a failure leaves no Party, no Customer, no Identity, no audit row and no outbox row |
| **Provider failure** | **Not applicable — and stated.** Phase 1 has no external provider. Notification delivery for recovery is a seam: the recovery record commits, the notification is an outbox event, and the channel adapter is Phase 15's |

## 9. Testing strategy

| Tier | What |
|---|---|
| **unit** | Value objects, state machines, assurance-level comparison, enumeration-safe response shaping |
| **architecture** | `party` and `identity` boundaries; no cross-module entity reference; no credential type outside `identity`; every `AuditableAction` catalogued |
| **slice** | Every endpoint over real HTTP, including the error contract for each failure |
| **database** | Every constraint; concurrency; multi-instance revocation |
| **security** | A **negative authorization test for every protected endpoint** — the phase's exit criterion; MFA bypass attempts, one per enumerated path; enumeration probes comparing existing and absent accounts; credential absence from logs, events and responses |
| **contract** | `openapi.json` byte-compared; `AUDITABLE_ACTIONS.md` reconciled; `DATA_CLASSIFICATION.md` reconciled against the live schema |
| **concurrency** | Per the `P0-TST-009` convention — each simulated instance gets its own connection, component and, where a clock decides, its own server-anchored clock |
| **failure** | One per row of §8 |

**Every `INV-IDN-*` gets a row in `MUTATION_TESTING.md`**, with a demonstration that its test fails
when the invariant is broken. `MutationDemonstrationTest` currently enforces this for Phase 0
invariants; `P1-TSK-024` extends it to Phase 1.

## 10. Observability

| Signal | Why |
|---|---|
| `finapp.identity.authentication` — counter, tagged by outcome and assurance | Success/failure rate is the primary compromise indicator |
| `finapp.identity.lockout` — counter | A spike is credential stuffing |
| `finapp.identity.mfa_challenge` — counter by outcome | |
| `finapp.identity.session_lifetime` — timer | |
| `finapp.identity.recovery` — counter by stage | Recovery is the ATO vector; its rate is a security signal |
| `finapp.identity.active_sessions` — gauge | |
| `finapp.party.registration` — counter | |

**No tag value derives from a request** (ADR-0018): no identity identifier, no email, no IP. A
failure-rate metric answers *how many*, and *which one* is the audit trail's question.

**Logs** carry the correlation identifier and never a credential, token, session identifier or
recovery token. **Traces** carry no PII (ADR-0017). **Audit** is the regulatory artefact and is the
only place an attempted identifier appears.

**Security signals worth alerting**, recorded now so Phase 15 does not invent them: authentication
failure rate by identity and globally, lockout rate, recovery initiation rate, privileged action
volume, and sessions revoked in bulk.

---

## 11. Milestones

Vertical slices. Each ends with something demonstrable end to end, not a layer.

### M1.1 — A person exists and is registered

**Objective:** one transaction creates a Party, a Customer and an Identity, and the three are
provably separate.
**Scope:** ADR ratification for the data-access mechanism; the correlation-identifier constraint;
`party` and `identity` module skeletons and schemas; `POST /v1/registrations`, idempotent.
**Dependencies:** Phase 0 complete.
**Tasks:** `P1-TSK-001` … `P1-TSK-006`.
**Tests:** registration transaction atomicity; the three-aggregate separation; idempotent retry;
enumeration-safe collision; every new column classified.
**Docs:** ADR-0033 (data access); `DOMAIN_MODEL.md` updated with the settled distinctions.
**Acceptance:** a registration creates exactly one Party, Customer and Identity, or none; a retry
with the same key creates nothing more and replays the original response; the three have distinct
identifiers and lifecycles, proven by a test that fails if any two are merged.

### M1.2 — That person can authenticate

**Objective:** password authentication that is enumeration-safe and cannot be brute-forced.
**Scope:** credential storage and verification; upgrade-on-use; authentication endpoint; failure
counting and lockout; session issuance.
**Dependencies:** M1.1.
**Tasks:** `P1-TSK-007` … `P1-TSK-012`.
**Tests:** credential never recoverable; parameters recorded and upgraded; equivalent timing and
response for absent accounts; lockout under concurrent attempts, with database-backed counters.
**Acceptance:** an identity authenticates and receives a session; a wrong credential, an unknown
identity and a locked account are indistinguishable; a credential written under weak parameters is
upgraded on next successful use.

### M1.3 — Sessions are real, and revocation is immediate

**Objective:** session lifecycle with revocation that takes effect on every instance at once.
**Scope:** session listing and revocation; rotation on privilege change; idle and absolute expiry;
device recorded.
**Dependencies:** M1.2.
**Tasks:** `P1-TSK-013` … `P1-TSK-016`.
**Tests:** multi-instance revocation (revoke on one, refused on another); rotation on privilege
change; expired indistinguishable from revoked; concurrent login and revocation.
**Acceptance:** `INV-IDN-03` demonstrated to fail when the session lookup is cached.

### M1.4 — A second factor that cannot be bypassed

**Objective:** TOTP enrolment and challenge, enforced as an assurance level.
**Scope:** enrolment, challenge, verification; assurance level on the session; step-up producing a
new session identifier.
**Dependencies:** M1.3.
**Tasks:** `P1-TSK-017` … `P1-TSK-019`.
**Tests:** one bypass-attempt test per enumerated alternative path; partial enrolment never
satisfies a challenge; replayed TOTP code refused.
**Acceptance:** `INV-IDN-05` demonstrated — every enumerated path either requires the factor or
cannot produce a `MULTI_FACTOR` session.

### M1.5 — Authorization, and audit that names the actor

**Objective:** every protected endpoint checks permission and ownership, and every privileged
action is attributable.
**Scope:** roles and permissions; boundary permission check; ownership checks; `AuditableAction`
enums for `party` and `identity`; replacing `enterSystem()` at real call sites.
**Dependencies:** M1.3.
**Tasks:** `P1-TSK-020` … `P1-TSK-022`.
**Tests:** a negative authorization test per protected endpoint; a negative ownership test per
resource-scoped operation; an audit record per privileged action with all seven fields.
**Acceptance:** `INV-AUD-01` and `INV-AUD-03` hold for every Phase 1 privileged action;
`SecurityContext.require()` is satisfied by a real actor, not the system actor.

### M1.6 — Recovery that is not the way in

**Objective:** account recovery that cannot be used for takeover.
**Scope:** initiation, channel verification, single-use expiring token, completion, notification
as an outbox event; rate limiting and cooling-off.
**Dependencies:** M1.4, M1.5 — deliberately last, so recovery is built against a working MFA,
session and audit model rather than beside them.
**Tasks:** `P1-TSK-023`.
**Tests:** the `INV-IDN-06` abuse cases — replay, unverified channel, recently changed channel,
concurrent recovery and login, recovery lowering assurance.
**Acceptance:** every abuse case refused, each with its own test.

### M1.7 — Phase review

**Objective:** the exit gate.
**Tasks:** `P1-TSK-024` (extend the mutation register), plus the phase review record.
**Acceptance:** all twelve universal criteria and all six Phase 1-specific criteria.

---

## 12. What Phase 1 must not implement

`EXECUTION_PROTOCOL.md` rule 3: no future-phase functionality; where later capability is
structurally needed, a **seam** only.

| Must not | Why, and what is permitted instead |
|---|---|
| **KYC / KYB verification** | Phase 2. Party carries no verification status field — not even nullable. Phase 2 owns the decision and Party will project a non-authoritative status then |
| **Consent records** | Phase 2. Consent is not authentication and not authorization (`INV-IDN-04`); modelling it beside a session invites exactly that collapse |
| **Accounts, wallets, balances** | Phase 3. No account concept, no balance field, nothing named `account` in `party` or `identity` |
| **The ledger, any posting** | Phase 3 |
| **Any money movement** | Phase 4 |
| **Credit profile, scores, decisioning** | Phase 10 |
| **Risk or fraud scoring** | Phase 13. **Specifically excluded:** device *trust* scoring and "remember this device" MFA suppression. A device is recorded; whether it is trusted is a risk decision |
| **A rules engine for authorization** | ADR-0031. Phase 13's engine is for risk and must not become an authorization engine |
| **WebAuthn / passkeys** | Deferred within the phase; the `STRONG` assurance rung is reserved so it lands additively |
| **Organisations, delegated access** | The ADR-0031 split trigger; Phase 6 at the earliest |
| **A notification channel adapter** | Phase 15. Recovery emits an outbox event; nothing delivers it. Phase 1 tests assert the event, not a delivered message |

**Minimal foundations that are genuinely required**, with the reason:

1. **A broker adapter behind `EventPublisher`** — Phase 1 is the first phase that publishes domain
   events, and Phase 0 recorded the adapter as debt owned by "the first module that emits a domain
   event". That is now. Only the adapter: the wire format and topic scheme are decided here because
   they must be, and no consumer is built.
2. **The data-access mechanism** (unresolved question 12) — six aggregates cannot be persisted
   without it, and choosing by accident is the risk `MoneyColumns` was written to avoid.
3. **Connection-pool sizing** — Phase 1 is the first real pool user, and ten instances at the
   default exhaust PostgreSQL's `max_connections` before doing any work.
