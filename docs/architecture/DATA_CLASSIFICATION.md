# Data Classification

`DATA_ARCHITECTURE.md` §Data Rules requires that sensitive data is classified and protected
appropriately. This is that scheme (`P0-TSK-033`).

Sections are labelled **Implemented** or **Decided, not yet implemented**, the convention
`API_CONVENTIONS.md` and `SECRET_MANAGEMENT.md` use, because a security document that mixes what is
true with what is intended is worse than none: it is believed.

---

## 1. Why this exists in Phase 0, when there is nothing to classify

**Phase 0 holds no customer data, no money and no credentials.** That is by design — zero business
capability — and it is exactly why the scheme is written now.

A column's classification is not a property you can add later. Once a column holds data, changing
its classification means the handling it was given for its whole life was wrong: it may already be
in a log aggregator, an event stream, a backup, or a support screenshot, and none of that can be
recalled. This is the same argument ADR-0010 makes for actor attribution and `INV-HIST-01` makes
for financial history — the decision has to precede the first row.

So the register below classifies every column at the **ceiling** of what it may ever carry, not at
what it happens to contain today. Where today's content is lower, the note says so.

## 2. Levels — *Implemented*

Five levels, most permissive first. A record takes the highest level of any field in it.

### `PUBLIC`
Disclosure has no consequence. Published deliberately.

*Handling:* none. **Nothing in the platform is `PUBLIC` today**, and the marked local-development
database default is the only value in the repository deliberately published as a non-secret
(ADR-0020) — it is configuration, not data.

### `INTERNAL`
Operational metadata. Discloses how the platform works, not who uses it or what they own. Flow
identifiers, event types, lifecycle states, timestamps, attempt counts.

*Handling:* may appear in logs, traces, metrics and error responses. Correlation identifiers are
deliberately in this level and are deliberately *not* on metrics (ADR-0018 — a metric answers how
many, never which one). Not published to unauthenticated callers beyond what the operational
endpoints already expose (ADR-0016).

**For a caller-supplied value, this level is a requirement on the value, not an observation about
it** — see §5. A field that the platform propagates to every log line and every span is `INTERNAL`
only for as long as the platform controls what can be in it.

### `CONFIDENTIAL`
Business-sensitive. Discloses the platform's or a counterparty's operations. Producer and consumer
names, provider error text, integration identifiers.

*Handling:* may appear in logs and internal telemetry. **Never in an API response** unless it is
part of a published contract. Never in a metric tag.

### `RESTRICTED-FINANCIAL`
Reveals a party's money: amounts, balances, account and instrument identifiers, postings,
settlement positions.

*Handling:* **never in logs, traces, metrics or event payloads**; in an API response only to a
caller authorised for that party. Retained under the financial-history rules — immutable
(`INV-HIST-01`), never deleted to satisfy a retention policy without an explicit accounting
decision.

### `RESTRICTED-PII`
Identifies a natural person, or is authentication data: names, addresses, government identifiers,
contact details, credentials, tokens, PANs, sensitive authentication data.

*Handling:* **`INV-AUD-02` — never in application logs, event payloads or API responses.** Held in
`Sensitive<T>` where it is held in Java at all, which the build enforces for anything whose name
says it is a secret (ADR-0019). Card data is out of scope entirely and tokenised at the boundary
(`DECISIONS.md` §Deliberately Deferred); PCI scope is minimised rather than managed.

## 3. Where these rules are already enforced — *Implemented*

The handling rules above are not new obligations. They are the ones this platform already enforces,
gathered under names. **Referenced, never restated** — the same discipline `API_CONVENTIONS.md`
applies to the error-code catalogue, because a second copy drifts while looking authoritative.

| Rule | Enforced by | Level it protects |
|---|---|---|
| No secret in a type that can print itself | `secretsAreWrapped` (ADR-0019) | `RESTRICTED-PII` |
| Only one component writes the log context | `onlyCorrelationContextWritesTheMdc` | `RESTRICTED-PII` |
| No credential literal in committed configuration | `CommittedConfigurationHoldsNoSecretTest` (ADR-0020) | `RESTRICTED-PII` |
| No request-influenced value in a metric tag | `MetricConventionTest` (ADR-0018) | all restricted levels |
| No SQL text on a span | `P0-TSK-028`, ADR-0017 | `RESTRICTED-FINANCIAL` |
| No rejected input values in an error detail | `P0-TSK-025` | `RESTRICTED-PII` |
| No dependency, host or driver named in a health body | `P0-TSK-027`, ADR-0016 | `CONFIDENTIAL` |
| Audit records immutable at the privilege level | `INV-HIST-03`, ADR-0010 | all levels |

## 4. Column register — *Implemented*

*A table name two schemas share is written schema-qualified in the table cell - `transfers.beneficiary`, `crossborder.beneficiary` - and the guard compares those columns by schema (`P9-DOC-001`; `ColumnClassificationTest#tableNamesSharedBySchemasAreQualified`).*

Every column in every schema this repository owns — twelve at the Phase 6 review: `platform`,
`party`, `identity`, `kyc`, `consent`, `ledger`, `accounts`, `transfers`, `paymentmethods`,
`payments`, `merchant` and `checkout` — at its ceiling. The guard derives the schemas from the
database rather than from this list, so a thirteenth is covered without anyone remembering.
*(This named only `platform`, `party` and `identity` until the Phase 6 review, `P6-DOC-001`.)*
`ColumnClassificationTest` fails the build if this table and the live schema disagree in either
direction — so a migration that adds a column without a classification decision cannot land. That
guard, not this table, is what makes the scheme "referenced by later data-model tasks".

`flyway_schema_history` is excluded: it is Flyway's table, not this platform's design, and it holds
no business data.

| Table | Column | Level | Note |
|---|---|---|---|
| `audit_record` | `audit_id` | `INTERNAL` | A generated identifier. UUIDv7 discloses creation time by construction (ADR-0013) |
| `audit_record` | `actor_id` | `RESTRICTED-PII` | **Today it is always `system`.** From Phase 1 it is a person's identity-provider subject, and the column cannot be reclassified then |
| `audit_record` | `actor_type` | `INTERNAL` | An enumeration of four values |
| `audit_record` | `occurred_at` | `INTERNAL` | |
| `audit_record` | `operation` | `INTERNAL` | A registered action code (`AUDITABLE_ACTIONS.md`) |
| `audit_record` | `target_type` | `INTERNAL` | |
| `audit_record` | `target_id` | `RESTRICTED-FINANCIAL` | Identifies the thing acted on. From Phase 3 that is an account or a posting |
| `audit_record` | `reason` | `RESTRICTED-PII` | **Free text written by a person.** Its content is not constrained by any type, so it must be handled at the ceiling. *(Corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-04: every Phase 8 door that writes a person's reason here — the settlement decline, readmission, verification and content read, the reconciliation reprocess and requeue and the opening position, beside the case file's already-screened reasons — refuses a card-number or account-identifier shape before it writes (`InstrumentShapes`); this column has no CHECK twin of its own, and the reason doors of earlier modules stay with the cross-cutting owner.)* |
| `audit_record` | `outcome` | `INTERNAL` | |
| `audit_record` | `correlation_id` | `INTERNAL` | **Caller-supplied** - see §5 |
| `audit_record` | `change_summary` | `RESTRICTED-FINANCIAL` | Free text. `V009` already forbids a payload dump here (`INV-AUD-02`); the ceiling is what handling must assume |
| `idempotency_record` | `scope` | `INTERNAL` | |
| `idempotency_record` | `idempotency_key` | `INTERNAL` | **Caller-chosen** - see §5. Not a secret (`API_CONVENTIONS.md` §6) and deliberately recorded |
| `idempotency_record` | `request_fingerprint` | `CONFIDENTIAL` | A hash, so not reversible - but a hash of a low-entropy request is guessable, which is why it is not `INTERNAL` |
| `idempotency_record` | `fingerprint_algorithm` | `INTERNAL` | |
| `idempotency_record` | `state` | `INTERNAL` | |
| `idempotency_record` | `response_body` | `RESTRICTED-FINANCIAL` | **Stores a whole API response.** For a money-moving command that is balances and account identifiers |
| `idempotency_record` | `response_media_type` | `INTERNAL` | |
| `idempotency_record` | `correlation_id` | `INTERNAL` | **Caller-supplied** - see §5 |
| `idempotency_record` | `created_at` | `INTERNAL` | |
| `idempotency_record` | `completed_at` | `INTERNAL` | |
| `idempotency_record` | `expires_at` | `INTERNAL` | |
| `idempotency_record` | `lease_expires_at` | `INTERNAL` | |
| `inbox_message` | `consumer` | `INTERNAL` | |
| `inbox_message` | `dedupe_key` | `CONFIDENTIAL` | **Externally supplied** - see §5. An external system's message identifier; discloses a counterparty's numbering |
| `inbox_message` | `message_type` | `INTERNAL` | |
| `inbox_message` | `correlation_id` | `INTERNAL` | **Caller-supplied** - see §5 |
| `inbox_message` | `processed_at` | `INTERNAL` | |
| `inbox_message` | `expires_at` | `INTERNAL` | |
| `outbox_event` | `event_id` | `INTERNAL` | |
| `outbox_event` | `event_type` | `INTERNAL` | |
| `outbox_event` | `event_version` | `INTERNAL` | |
| `outbox_event` | `schema_version` | `INTERNAL` | |
| `outbox_event` | `aggregate_id` | `RESTRICTED-FINANCIAL` | Identifies the aggregate the fact concerns - from Phase 3, an account |
| `outbox_event` | `aggregate_type` | `INTERNAL` | |
| `outbox_event` | `occurred_at` | `INTERNAL` | |
| `outbox_event` | `producer` | `CONFIDENTIAL` | Names an internal component |
| `outbox_event` | `correlation_id` | `INTERNAL` | **Caller-supplied** - see §5 |
| `outbox_event` | `causation_id` | `INTERNAL` | |
| `outbox_event` | `payload` | `RESTRICTED-FINANCIAL` | **Opaque event data.** The envelope deliberately carries none (`P0-TSK-018`) so relays and logs cannot spill it; this column is where it actually lives |
| `outbox_event` | `payload_media_type` | `INTERNAL` | |
| `outbox_event` | `published_at` | `INTERNAL` | |
| `outbox_event` | `attempts` | `INTERNAL` | |
| `outbox_event` | `next_attempt_at` | `INTERNAL` | |
| `outbox_event` | `dead_lettered_at` | `INTERNAL` | |
| `outbox_event` | `last_error` | `CONFIDENTIAL` | A broker or adapter error. `V006` already forbids the payload here; an error string can still carry a host or endpoint |

### `party` and `identity` — *added by `P1-TSK-005`*

**The platform's first `RESTRICTED-PII` columns in quantity**, and the phase in which the
classification stops being a hypothetical. Every one below is classified at what it *may ever*
hold, not what it holds on the day it was created — a column cannot be reclassified once it has
data, because by then the handling it was given for its whole life is already settled and may be in
a log aggregator, an event stream or a backup (ADR-0022).

| Table | Column | Level | Note |
|---|---|---|---|
| `party` | `id` | `INTERNAL` | A generated identifier. UUIDv7 discloses creation time by construction (ADR-0013) |
| `party` | `kind` | `INTERNAL` | An enumeration of two values. Says nothing about a particular person |
| `party` | `display_name` | `RESTRICTED-PII` | A person's name. The clearest `RESTRICTED-PII` column on the platform, and the reason `Party.toString()` omits it and `PartyName.toString()` masks it |
| `party` | `registered_at` | `CONFIDENTIAL` | When someone became known to us. Not a name, but a behavioural fact about a person, and one that correlates with events they would not expect us to publish |
| `customer` | `id` | `INTERNAL` | Generated |
| `customer` | `party_id` | `INTERNAL` | An identifier of a person, not a fact about them. `RESTRICTED-PII` here would forbid it from a log line and defeat the traceability the audit trail is for; what must never be logged is what it *resolves to* |
| `customer` | `status` | `CONFIDENTIAL` | Whether someone is suspended is a fact about them that neither they nor we would want disclosed. Not PII on its own — it identifies nobody — but it must not be public |
| `customer` | `opened_at` | `CONFIDENTIAL` | As `party.registered_at` |
| `customer` | `status_changed_at` | `CONFIDENTIAL` | When a suspension happened, which is more disclosive than the status alone |
| `identity` | `id` | `INTERNAL` | Generated |
| `identity` | `party_id` | `INTERNAL` | As `customer.party_id` |
| `identity` | `login_identifier` | `CONFIDENTIAL` | **Not `RESTRICTED-PII`, and the reasoning matters.** It is not itself personal data — it is a handle the platform issued or the person chose. What it carries is *existence*: knowing one is in use tells an attacker an account exists, which is the enumeration risk `INV-IDN-07` exists for. That is a confidentiality requirement, not a privacy one, and it is why `Identity.toString()` omits it |
| `identity` | `status` | `CONFIDENTIAL` | Whether a login is suspended. As `customer.status` |
| `identity` | `created_at` | `CONFIDENTIAL` | As `party.registered_at` |
| `identity` | `status_changed_at` | `CONFIDENTIAL` | As `customer.status_changed_at` |

### `identity.credential` — *added by `P1-TSK-007`*

| Table | Column | Level | Note |
|---|---|---|---|
| `credential` | `id` | `INTERNAL` | Generated |
| `credential` | `identity_id` | `INTERNAL` | As `identity.party_id` — an identifier of a thing, not a fact about it |
| `credential` | `type` | `INTERNAL` | An enumeration with one member today. Which *kinds* of credential exist is a property of the platform, not of a person |
| `credential` | `derivation` | `RESTRICTED-PII` | **The most sensitive column on the platform.** Not the plaintext, and it does not need to be: it is exactly what an attacker with a copy of the database attacks offline, and a break yields the password itself — which people reuse. `RESTRICTED-PII` rather than a level of its own because the scheme has five and inventing a sixth for one column would weaken the other four by comparison |
| `credential` | `algorithm` | `CONFIDENTIAL` | **Not `INTERNAL`, and the reasoning is the same shape as `login_identifier`'s.** It is not personal data; what it carries is *how weakly this particular account is protected*, which is a targeting aid. Knowing the platform uses Argon2id is public; knowing that *this* credential does not is not |
| `credential` | `memory_kib` | `CONFIDENTIAL` | As `algorithm`. The cost factors are the answer to "how long would this one take to crack" |
| `credential` | `iterations` | `CONFIDENTIAL` | As `memory_kib` |
| `credential` | `parallelism` | `CONFIDENTIAL` | As `memory_kib` |
| `credential` | `status` | `CONFIDENTIAL` | Whether an identity currently has a usable credential. As `identity.status` |
| `credential` | `created_at` | `CONFIDENTIAL` | When somebody last set a password — a behavioural fact, and one that indicates which accounts have stale credentials |
| `credential` | `superseded_at` | `CONFIDENTIAL` | More disclosive than `created_at`: it dates a password change, which often dates a compromise |

**The four parameter columns are the one judgement here worth challenging.** They are configuration
values, and the argument for `INTERNAL` is that they say nothing about a person. They are
`CONFIDENTIAL` because of what a *set* of them discloses: an attacker who could read them would know
which accounts to attack first, and that is precisely the ranking an upgrade campaign exists to
eliminate. The same query serves both purposes, which is why the level has to assume the hostile
reader.

**Nothing here is `RESTRICTED-FINANCIAL`.** Phase 1 holds no money — as for `party` and `identity`.

**Where a login identifier legitimately appears outside this table** (`P1-TSK-006`):
`audit_record.target_id`, which is classified `RESTRICTED-FINANCIAL` and so comfortably above
`CONFIDENTIAL`. That is deliberate and is the only such place — `PHASE_1_PLAN.md` §10 makes the
audit trail the one artefact where an *attempted* identifier may appear, because it is
access-controlled and is the regulatory record. It is **not** in `idempotency_record.scope`, which
is `INTERNAL` and holds the command name alone; putting it there would have forced a Phase 0 column
to be reclassified, which ADR-0022 says must not happen. And it is not in any event payload, which
`EventPayload`'s charset makes structurally impossible rather than merely discouraged.

**Why no column here is `RESTRICTED-FINANCIAL`.** Phase 1 holds no money, no account and no
balance. When `target_id` on an audit record points at one of these rows it is still the audit
table's column and keeps that table's classification; nothing in `party` or `identity` becomes
financial by being referenced.

**The one judgement worth challenging** is `party_id` at `INTERNAL`. It identifies a person, and
the argument for `RESTRICTED-PII` is real. It is classified `INTERNAL` because the alternative is
unworkable rather than because the risk is absent: correlation and audit both require an identifier
to appear in records, and a level that forbade it would forbid the traceability those records exist
to provide — the same trap `correlation_id` presents in §5. What must never appear beside it is
what it *resolves to*, and that is enforced where the name lives, not here.

## 5. The columns whose content the platform does not decide

Some columns hold what a caller or an operator put there, so their classification is not a property
of the column. There are two kinds, and the second was missed on the first pass.


### `identity.authentication_failure` — *added by `P1-TSK-011`*

| Table | Column | Level | Note |
|---|---|---|---|
| `authentication_failure` | `identity_id` | `INTERNAL` | As `credential.identity_id` — an identifier of a thing |
| `authentication_failure` | `failures` | `CONFIDENTIAL` | **Not `INTERNAL`.** A count near the threshold says *this account is being attacked right now*, which is a targeting aid: an attacker who could read it would learn which accounts somebody else has already found worth attacking, and which are close to locking |
| `authentication_failure` | `window_started_at` | `CONFIDENTIAL` | With `failures`, it dates an attack. As `credential.superseded_at`, which is `CONFIDENTIAL` because it dates a password change |
| `authentication_failure` | `locked_until` | `CONFIDENTIAL` | Whether an identity can authenticate at this moment. As `identity.status` and `credential.status`, and for the same reason: it is a fact about a person's access |
| `authentication_failure` | `updated_at` | `CONFIDENTIAL` | Dates the most recent failed attempt |

**The whole table is classified at what it *implies*, not at what it stores.** Every column here is
a number or a timestamp, and the naive reading is that a counter is operational metadata. What the
row actually says is *somebody is trying to get into this account* — which is why the presence of a
row is itself the disclosure, and why nothing here records the login identifier that was attempted.
That fact lives on the audit record, where the trail is the regulatory artefact and the application
role cannot edit it.

### `identity.session` — *added by `P1-TSK-013`*

| Table | Column | Level | Note |
|---|---|---|---|
| `session` | `id` | `INTERNAL` | The aggregate's identifier. Generated, and **not** the value a client presents |
| `session` | `identity_id` | `INTERNAL` | As `credential.identity_id` — an identifier of a thing |
| `session` | `token_hash` | `RESTRICTED-PII` | **The second most sensitive column on the platform**, after `credential.derivation`, and it is more sensitive than it looks. It is not crackable — the input is 256 random bits — so it is not what `credential.derivation` is. What it is, is a precise identifier of one person's *live* session: anybody who could read it knows exactly which session to attack and when it was in use. The token itself is never stored, so this is the closest the database gets |
| `session` | `assurance` | `CONFIDENTIAL` | How strongly a particular person authenticated. Knowing the platform supports MFA is public; knowing that *this* session did not use it is a targeting aid, which is `credential.algorithm`'s reasoning exactly |
| `session` | `status` | `CONFIDENTIAL` | Whether a session is usable. As `identity.status` and `credential.status` |
| `session` | `issued_at` | `CONFIDENTIAL` | When a person logged in — a behavioural fact, and one that patterns a person's day |
| `session` | `idle_expires_at` | `CONFIDENTIAL` | With `issued_at`, it dates the *last activity*, which is more disclosive than the login itself |
| `session` | `absolute_expires_at` | `CONFIDENTIAL` | As `issued_at`. Derived from `live_from` since `X-TSK-007`, and from `issued_at` before |
| `session` | `device` | `RESTRICTED-PII` | **At its ceiling, not its content.** `P1-TSK-016` populates it, and what it holds is whatever a client sends about the machine a person uses — a user agent, a platform, a fingerprint. That is personal data about equipment in somebody's home, and there is no later moment at which classifying it lower becomes safe (ADR-0022) |
| `session` | `revoked_at` | `CONFIDENTIAL` | Dates a logout, or an intervention. As `credential.superseded_at`, which is `CONFIDENTIAL` because it dates a password change |
| `session` | `live_from` | `CONFIDENTIAL` | *Added by `X-TSK-007`.* The same moment as `issued_at`, read from the database's clock rather than the issuing instance's, so it dates a login exactly as `issued_at` does and patterns a person's day the same way. Coordination time, not a business fact (`V016`) |
| `mfa_enrolment` | `id` | `INTERNAL` | An aggregate identifier. Not secret, never presented |
| `mfa_enrolment` | `identity_id` | `RESTRICTED-PII` | Identifies a person, as `session.identity_id` does |
| `mfa_enrolment` | `type` | `INTERNAL` | Which kind of factor. Says nothing about who |
| `mfa_enrolment` | `secret_ciphertext` | `RESTRICTED-PII` | **The platform's only recoverable authentication secret.** `INV-IDN-01`'s irreversibility is unavailable — the server must hold the secret to compute a code — so confidentiality replaces it (`INV-IDN-08`): AES-256-GCM under a key held outside this database. Classified at its ceiling like every other column, but the classification is not what protects it; the encryption is |
| `mfa_enrolment` | `secret_nonce` | `INTERNAL` | Public by construction. A GCM nonce is not secret and is stored beside its ciphertext; what must never happen is a **reuse**, which is why it is generated per encryption and nothing here could regenerate one |
| `mfa_enrolment` | `key_version` | `INTERNAL` | Which key encrypted this row, so a rotation knows. Operational metadata |
| `mfa_enrolment` | `algorithm` | `INTERNAL` | The HMAC an enrolment uses. Published in the provisioning URI anyway |
| `mfa_enrolment` | `digits` | `INTERNAL` | As `algorithm` |
| `mfa_enrolment` | `period_seconds` | `INTERNAL` | As `algorithm` |
| `mfa_enrolment` | `status` | `CONFIDENTIAL` | Whether a person has a second factor, and whether one is half-enrolled. That is a fact about an account's defences, and it is exactly what an attacker choosing a target would like to know |
| `mfa_enrolment` | `created_at` | `CONFIDENTIAL` | When somebody began adding a factor. As `status`, and it dates the account's security posture |
| `mfa_enrolment` | `confirmed_at` | `CONFIDENTIAL` | As `created_at` |
| `mfa_enrolment` | `discarded_at` | `CONFIDENTIAL` | As `created_at` |
| `mfa_enrolment` | `last_used_step` | `CONFIDENTIAL` | The TOTP time step of the last accepted code. Operational on its face, and it is **not** `INTERNAL`: it says when the factor was last used to a thirty-second resolution, which is a record of when a person was at their device. Classified with the other timestamps on this table for the same reason |
| `role_assignment` | `id` | `INTERNAL` | An aggregate identifier |
| `role_assignment` | `identity_id` | `RESTRICTED-PII` | Identifies a person, as every other `identity_id` does |
| `role_assignment` | `role_name` | `CONFIDENTIAL` | **Which people are administrators.** Not `INTERNAL`: it is a target list. An attacker choosing whom to phish would like this more than almost anything else in the schema |
| `role_assignment` | `assigned_by` | `RESTRICTED-PII` | Identifies the person who granted it |
| `role_assignment` | `assigned_at` | `CONFIDENTIAL` | When somebody became privileged. As `role_name` — it dates the account's standing |
| `role_assignment` | `revoked_by` | `RESTRICTED-PII` | As `assigned_by` |
| `role_assignment` | `revoked_at` | `CONFIDENTIAL` | As `assigned_at` |
| `contact_channel` | `id` | `INTERNAL` | An entity identifier |
| `contact_channel` | `identity_id` | `RESTRICTED-PII` | Identifies a person |
| `contact_channel` | `kind` | `INTERNAL` | Which sort of channel. One value today |
| `contact_channel` | `address` | `RESTRICTED-PII` | **A person's email address.** Directly identifying, and the platform's only such column outside `party.display_name` |
| `contact_channel` | `verification_token_hash` | `CONFIDENTIAL` | The hash of a live challenge. Not the token - but a value that names a pending verification on a specific account, which is a takeover in progress |
| `contact_channel` | `verification_expires_at` | `CONFIDENTIAL` | When that challenge dies. As the hash: it dates an in-flight verification |
| `contact_channel` | `verified_at` | `CONFIDENTIAL` | **When control was proven.** `INV-IDN-06` names *recently changed channel* as an abuse case, so this column is the signal an investigator reads - and an attacker who could read it would know which accounts have just become recoverable |
| `contact_channel` | `added_at` | `CONFIDENTIAL` | As `verified_at` |
| `recovery_request` | `id` | `INTERNAL` | An aggregate identifier. It appears in a URL and is useless without the token |
| `recovery_request` | `identity_id` | `RESTRICTED-PII` | Identifies a person |
| `recovery_request` | `channel_id` | `INTERNAL` | Which channel it went to. The address itself is on `contact_channel` |
| `recovery_request` | `token_hash` | `CONFIDENTIAL` | The hash of a bearer credential that can replace a credential. Not the token, and still the most sensitive non-PII column here |
| `recovery_request` | `status` | `CONFIDENTIAL` | Whether a recovery is live on this account |
| `recovery_request` | `credential_id` | `INTERNAL` | Which credential the request was bound to - the concurrent-recovery-and-login control |
| `recovery_request` | `initiated_at` | `CONFIDENTIAL` | **When somebody tried to take the account over**, or when its owner forgot their password. Either way it dates an event on a named person |
| `recovery_request` | `expires_at` | `CONFIDENTIAL` | As `initiated_at` |
| `recovery_request` | `completed_at` | `CONFIDENTIAL` | As `initiated_at` |
| `recovery_request` | `cancelled_at` | `CONFIDENTIAL` | As `initiated_at` |

**`device` is the judgement worth challenging**, and it is classified above everything else here on
purpose. Every other column is a fact about the *session*; `device` is a fact about the *person* —
what they own and where they are. It is also the column most likely to be widened later by somebody
adding "just the user agent", which is why the ceiling is set before anything populates it.

### `kyc.kyc_case` — *added by `P2-TSK-005`*

| Table | Column | Level | Note |
|---|---|---|---|
| `kyc_case` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `kyc_case` | `customer_id` | `INTERNAL` | As `customer.party_id` — an identifier of a thing, not a fact about it. `RESTRICTED-PII` here would forbid it from the log lines and audit records that make a case investigable, and what must never be logged is what it *resolves to* |
| `kyc_case` | `status` | `CONFIDENTIAL` | **The tipping-off column.** `IN_REVIEW` means a screening hit or an unresolvable check — exactly what the customer-facing status is shaped to hide (`PHASE_2_PLAN.md` §6), and disclosure of a sanctions review in progress is not merely embarrassing but in some regimes an offence. As `customer.status`, with more behind it |
| `kyc_case` | `policy_version` | `CONFIDENTIAL` | The `credential.algorithm` reasoning: not a fact about a person on its face, but *which accounts were assessed under the lax regime* is a targeting aid, and the same query serves the upgrade campaign and the attacker |
| `kyc_case` | `opened_at` | `CONFIDENTIAL` | As `customer.opened_at` — it dates onboarding |
| `kyc_case` | `status_changed_at` | `CONFIDENTIAL` | Dates a review event, which is more disclosive than the status alone — `customer.status_changed_at`'s reasoning |
| `kyc_case` | `case_kind` | `INTERNAL` | *Added by `P2-TSK-015`.* As `party.kind` — an enumeration of two values, saying no more about the customer than the party row already says |

### `party.organisation_registrant` — *added by `P2-TSK-016`*

| Table | Column | Level | Why |
|---|---|---|---|
| `organisation_registrant` | `customer_id` | `INTERNAL` | An identifier of a thing, as `customer.id` |
| `organisation_registrant` | `registrant_party_id` | `CONFIDENTIAL` | The `beneficial_owner.owner_party_id` reasoning, the other way round: the pairing *is* the fact — this person acts for that organisation. What it resolves to stays `RESTRICTED-PII` as ever |
| `organisation_registrant` | `registered_at` | `INTERNAL` | Operational timestamp |

### `kyc.beneficial_owner` — *added by `P2-TSK-015`*

| Table | Column | Level | Note |
|---|---|---|---|
| `beneficial_owner` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `beneficial_owner` | `case_id` | `INTERNAL` | As `kyc_case.id` — an identifier of a thing |
| `beneficial_owner` | `case_kind` | `INTERNAL` | A pinned constant (`'KYB'`), the composite-FK leg — data only in the schema's sense |
| `beneficial_owner` | `owner_party_id` | `CONFIDENTIAL` | **Deliberately above the `customer.party_id` precedent.** There the column is an identifier and the fact lives elsewhere; here the pairing with `case_id` *is* the fact — this person owns or controls that organisation — the `review_task.status` tipping-off reasoning applied to a link. What it resolves to stays `RESTRICTED-PII` as ever |
| `beneficial_owner` | `verification_case_id` | `INTERNAL` | As `kyc_case.id` — an identifier of a thing |
| `beneficial_owner` | `verification_case_kind` | `INTERNAL` | A pinned constant (`'KYC'`), the composite-FK leg |
| `beneficial_owner` | `stake_basis_points` | `CONFIDENTIAL` | A person's ownership position in a named organisation — a fact about them, commercially sensitive in both directions |
| `beneficial_owner` | `control_role` | `CONFIDENTIAL` | As the stake: who directs an entity is a fact about the person, not an identifier |
| `beneficial_owner` | `declared_at` | `CONFIDENTIAL` | Dates a KYC event — `kyc_case.opened_at`'s reasoning |

### `ledger.ledger_account` — *added by `P3-TSK-002`*

| Table | Column | Level | Why |
|---|---|---|---|
| `ledger_account` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `ledger_account` | `account_type` | `INTERNAL` | An enumeration of the five classifications accounting has |
| `ledger_account` | `normal_balance` | `INTERNAL` | An enumeration of two values, derived from the type |
| `ledger_account` | `currency` | `INTERNAL` | An ISO 4217 code; which currency an account is in says nothing about anybody |
| `ledger_account` | `owner_kind` | `INTERNAL` | An enumeration of five values (`COUNTERPARTY` joined with `P9-TSK-010`) |
| `ledger_account` | `owner_ref` | `INTERNAL` | The `kyc_case.customer_id` reasoning: an identifier of a thing, not a fact about it — and it must appear in the log lines and audit records that make a posting investigable. What it *resolves to* is the accounts module's to classify |
| `ledger_account` | `purpose` | `INTERNAL` | An enumeration member |
| `ledger_account` | `gl_code` | `INTERNAL` | A GL classification code, free-form but platform-written (Phase 14); it classifies an account, never a person |
| `ledger_account` | `status` | `CONFIDENTIAL` | `POSTING_SUSPENDED` is an operational freeze — from Phase 13 a plausible risk action — and disclosing that an account is frozen is the `kyc_case.status` tipping-off reasoning one register down. The ceiling rule: classified for what the column will mean, not what Phase 3 writes into it |
| `ledger_account` | `created_at` | `CONFIDENTIAL` | For an owned account it dates a product opening — `customer.opened_at`'s reasoning, at its ceiling |
| `ledger_account` | `status_changed_at` | `CONFIDENTIAL` | Dates a freeze or a closure, which is more disclosive than the status alone |

**No amount column exists here, by design** — a balance is not a field on an account
(`INV-BAL-01`, ADR-0042). The `RESTRICTED-FINANCIAL` rows arrive with the journal tables
(`P3-TSK-005`), which is where the plan's *"balances and postings are RESTRICTED-FINANCIAL"*
sentence lands.

### `ledger.counterparty` — *added by `P9-TSK-010`*

| Table | Column | Level | Why |
|---|---|---|---|
| `counterparty` | `id` | `INTERNAL` | A registry identifier, what a `COUNTERPARTY` account's `owner_ref` names; hand-minted by the admitting migration |
| `counterparty` | `code` | `INTERNAL` | A platform-declared code for an institution the platform settles with (`fx-sim-a`) - it names a firm's role, never a person, and appears in the logs and reports that make a counterparty's position investigable |
| `counterparty` | `kind` | `INTERNAL` | An enumeration of two values |
| `counterparty` | `created_at` | `INTERNAL` | Dates the migration that registered the counterparty - a deployment fact, not a person's activity |



| Table | Column | Level | Why |
|---|---|---|---|
| `journal_entry` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `journal_entry` | `posting_date` | `CONFIDENTIAL` | Dates financial activity — the `kyc_case.opened_at` reasoning, on the record every later phase posts through |
| `journal_entry` | `value_date` | `CONFIDENTIAL` | As `posting_date` |
| `journal_entry` | `entry_type` | `INTERNAL` | An enumeration of three values |
| `journal_entry` | `reference` | `RESTRICTED-FINANCIAL` | The originating economic event — from Phase 4 a transfer or payment identifier, which is `audit_record.target_id`'s reasoning at its ceiling |
| `journal_entry` | `reason` | `RESTRICTED-PII` | **Free text written by a person** — `audit_record.reason`'s reasoning verbatim: content constrained by no type, handled at the ceiling |
| `journal_entry` | `actor_id` | `RESTRICTED-PII` | `audit_record.actor_id`'s reasoning: a person's identity-provider subject once real actors post |
| `journal_entry` | `correlation_id` | `INTERNAL` | Platform-minted since ADR-0034 |
| `journal_entry` | `causation_id` | `INTERNAL` | As above |
| `journal_entry` | `idempotency_scope` | `INTERNAL` | As `idempotency_record.scope` |
| `journal_entry` | `reverses_entry_id` | `INTERNAL` | *(added by `P3-TSK-016`)* The original a `REVERSAL` compensates (`INV-REV-01`) — a platform-minted entry identifier, the `journal_line.ledger_account_id` reasoning: it must appear in the joins that make a correction investigable, and what it resolves to carries its own levels |
| `journal_entry` | `created_at` | `CONFIDENTIAL` | System time of a posting still dates financial activity |
| `journal_line` | `id` | `INTERNAL` | A generated identifier |
| `journal_line` | `entry_id` | `INTERNAL` | An identifier of a thing |
| `journal_line` | `ledger_account_id` | `INTERNAL` | The `kyc_case.customer_id` reasoning: it must appear in the queries and audit trails that make a posting investigable; what it resolves to carries its own levels |
| `journal_line` | `direction` | `INTERNAL` | An enumeration of two values |
| `journal_line` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The amount.** The plan's own sentence: postings are `RESTRICTED-FINANCIAL` — the platform's first genuinely financial column, and the reason `toString`s, exception messages and event payloads upstream all refuse to carry it |
| `journal_line` | `currency` | `INTERNAL` | An ISO 4217 code; the fact lives in the pairing with the amount, which carries the level |
| `journal_line` | `scale` | `INTERNAL` | Precision metadata of the amount |
| `journal_line` | `seq` | `INTERNAL` | Line order |

### `ledger.account_balance` — *added by `P3-TSK-009`*

The balance projection (ADR-0041): derived from the journal in the posting's own transaction,
never authoritative, and read by no financial decision (`INV-BAL-05`). A derived copy of a
`RESTRICTED-FINANCIAL` fact carries the fact's own ceiling — projection is not laundering.

| Table | Column | Level | Why this level |
|---|---|---|---|
| `account_balance` | `ledger_account_id` | `INTERNAL` | `journal_line.ledger_account_id`'s reasoning |
| `account_balance` | `currency` | `INTERNAL` | As `journal_line.currency` |
| `account_balance` | `posted_minor` | `RESTRICTED-FINANCIAL` | **A balance.** `journal_line.amount_minor`'s reasoning summed: an account's whole position, which is more disclosive than any one amount, not less |
| `account_balance` | `holds_minor` | `RESTRICTED-FINANCIAL` | As `posted_minor` — the other half of available balance (`INV-BAL-04`) |
| `account_balance` | `scale` | `INTERNAL` | Precision metadata of the amounts |
| `account_balance` | `last_entry_seq` | `INTERNAL` | An applied-entry count — activity volume, the `journal_entry.created_at` question at most, and it dates nothing |
| `account_balance` | `updated_at` | `CONFIDENTIAL` | System time of the last posting still dates financial activity — `journal_entry.created_at`'s reasoning |

### `ledger.hold` — *added by `P3-TSK-015`*

Reservations against available balance (`INV-BAL-04`). A hold's existence discloses a pending
movement before any posting exists — which is why its status carries the amounts' own ceiling
rather than a lifecycle word's usual `INTERNAL`.

| Table | Column | Level | Why this level |
|---|---|---|---|
| `hold` | `id` | `INTERNAL` | A platform-minted UUIDv7; names nothing by itself |
| `hold` | `ledger_account_id` | `INTERNAL` | `journal_line.ledger_account_id`'s reasoning |
| `hold` | `amount_minor` | `RESTRICTED-FINANCIAL` | A reserved amount — `journal_line.amount_minor`'s reasoning, before the movement it anticipates even exists |
| `hold` | `currency` | `INTERNAL` | As `journal_line.currency` |
| `hold` | `scale` | `INTERNAL` | Precision metadata of the amount |
| `hold` | `status` | `RESTRICTED-FINANCIAL` | Whether value is reserved right now — half of available balance (`INV-BAL-04`), and a pending movement's existence is a financial fact, not lifecycle metadata |
| `hold` | `placed_at` | `CONFIDENTIAL` | Dates financial activity — `journal_entry.created_at`'s reasoning |
| `hold` | `released_at` | `CONFIDENTIAL` | Same: when a reservation ended dates the movement or its abandonment |

### `ledger.adjustment_proposal` and `ledger.adjustment_proposal_line` — *added by `P3-TSK-021`*

The four-eyes lifecycle (`INV-AUD-04`): what an initiator asked to move and a second person
answered. The payload is a journal entry in waiting, so every column carries the ceiling of
the journal column it becomes at approval.

| Table | Column | Level | Why this level |
|---|---|---|---|
| `adjustment_proposal` | `id` | `INTERNAL` | A platform-minted UUIDv7; names nothing by itself |
| `adjustment_proposal` | `status` | `RESTRICTED-FINANCIAL` | Whether a manual movement is pending, done or declined — `hold.status`'s reasoning: a pending movement's existence is a financial fact |
| `adjustment_proposal` | `posting_date` | `CONFIDENTIAL` | `journal_entry.posting_date`'s reasoning — it becomes that column at approval |
| `adjustment_proposal` | `value_date` | `CONFIDENTIAL` | As `posting_date` |
| `adjustment_proposal` | `reference` | `RESTRICTED-FINANCIAL` | `journal_entry.reference`'s reasoning, before the entry exists |
| `adjustment_proposal` | `reason` | `RESTRICTED-PII` | **Free text written by a person** — `journal_entry.reason`'s reasoning verbatim |
| `adjustment_proposal` | `reason_code` | `INTERNAL` | A closed enumeration (`P8-TSK-006`, `INV-REV-04`): the justification's category, never its text |
| `adjustment_proposal` | `origin` | `INTERNAL` | An enumeration of two values (`P8-TSK-006`): whose machinery decides the proposal |
| `adjustment_proposal` | `proposed_by` | `RESTRICTED-PII` | `journal_entry.actor_id`'s reasoning: a person's identity-provider subject |
| `adjustment_proposal` | `proposed_at` | `CONFIDENTIAL` | Dates financial activity — `journal_entry.created_at`'s reasoning |
| `adjustment_proposal` | `decided_by` | `RESTRICTED-PII` | The second person — `proposed_by`'s reasoning |
| `adjustment_proposal` | `decided_at` | `CONFIDENTIAL` | As `proposed_at` |
| `adjustment_proposal` | `journal_entry_id` | `INTERNAL` | A platform-minted entry identifier — the `reverses_entry_id` reasoning: it must appear in the joins that make the four-eyes trail investigable |
| `adjustment_proposal_line` | `proposal_id` | `INTERNAL` | A platform-minted proposal identifier |
| `adjustment_proposal_line` | `seq` | `INTERNAL` | Line order |
| `adjustment_proposal_line` | `ledger_account_id` | `INTERNAL` | `journal_line.ledger_account_id`'s reasoning |
| `adjustment_proposal_line` | `direction` | `INTERNAL` | An enumeration of two values |
| `adjustment_proposal_line` | `amount_minor` | `RESTRICTED-FINANCIAL` | A proposed movement's amount — `hold.amount_minor`'s reasoning, before the movement exists |
| `adjustment_proposal_line` | `currency` | `INTERNAL` | As `journal_line.currency` |
| `adjustment_proposal_line` | `scale` | `INTERNAL` | Precision metadata of the amount |

### `accounts.customer_account` — *added by `P3-TSK-012`*

**No amount column exists here, by design** — the product carries no balance (ADR-0042); the
money is `ledger`'s and classified there.

| Table | Column | Level | Why |
|---|---|---|---|
| `customer_account` | `id` | `INTERNAL` | An aggregate identifier. Generated — and the value the ledger stores as its opaque `owner_ref`, whose row already records that what it *resolves to* is this module's to classify |
| `customer_account` | `customer_id` | `INTERNAL` | The `kyc_case.customer_id` reasoning: an identifier of a thing, not a fact about it — it must appear in the audit records that make an opening investigable |
| `customer_account` | `product_type` | `INTERNAL` | An enumeration member |
| `customer_account` | `status` | `CONFIDENTIAL` | `SUSPENDED` is an administrative freeze against a person's product — `customer.status`'s reasoning, and `ledger_account.status`'s one register over. The ceiling rule: classified for what the column will mean, not what Phase 3 writes into it |
| `customer_account` | `opened_at` | `CONFIDENTIAL` | Dates a product opening — `ledger_account.created_at`'s reasoning verbatim |
| `customer_account` | `status_changed_at` | `CONFIDENTIAL` | Dates a freeze or a closure, which is more disclosive than the status alone |

### `transfers.transfer` and `transfers.transfer_event` — *added by `P4-TSK-004`*

**The first customer-commanded amounts outside the ledger schema.** The row is the judgement
and its record (ADR-0044); the money itself is the ledger posting `journal_entry_id` names.

| Table | Column | Level | Why |
|---|---|---|---|
| `transfer` | `id` | `INTERNAL` | An aggregate identifier — and the value that travels in the journal entry's `reference`, whose row already classifies what it resolves to |
| `transfer` | `customer_id` | `INTERNAL` | The `customer_account.customer_id` reasoning verbatim: an identifier of a thing, not a fact about it |
| `transfer` | `source_account_id` | `INTERNAL` | A ledger-account identifier by value; what it resolves to is `ledger_account`'s to classify |
| `transfer` | `destination_account_id` | `INTERNAL` | As `source_account_id` |
| `transfer` | `amount_minor` | `RESTRICTED-FINANCIAL` | A customer's commanded amount — `journal_line.amount_minor`'s reasoning verbatim |
| `transfer` | `currency` | `RESTRICTED-FINANCIAL` | Meaningless without the amount and meaning-giving with it — `journal_line.currency`'s reasoning |
| `transfer` | `scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape (`INV-MON-05`) |
| `transfer` | `reference` | `RESTRICTED-PII` | **Free text written by a person** — a memo names people ("rent for John") — `journal_entry.reason`'s reasoning verbatim: content constrained by no type, handled at the ceiling |
| `transfer` | `status` | `CONFIDENTIAL` | What happened to a person's money movement; with `REVERSED` it discloses an operator acted against the account |
| `transfer` | `failure_reason` | `CONFIDENTIAL` | `INSUFFICIENT_FUNDS` is a fact about a person's finances, not an enumeration technicality — the ceiling rule |
| `transfer` | `journal_entry_id` | `INTERNAL` | An identifier joining movement to evidence; the evidence classifies itself |
| `transfer` | `reversal_entry_id` | `INTERNAL` | As `journal_entry_id` |
| `transfer` | `reversed_by` | `RESTRICTED-PII` | The operator who reversed — `audit_record.actor_id`'s reasoning: from Phase 1 an identity of a person |
| `transfer` | `reversed_at` | `CONFIDENTIAL` | Dates an operator's correction against a person's account |
| `transfer` | `initiated_by` | `RESTRICTED-PII` | The commanding identity — as `reversed_by` |
| `transfer` | `initiated_at` | `CONFIDENTIAL` | Dates a person's financial act — `customer_account.opened_at`'s reasoning |
| `transfer_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `transfer_event` | `transfer_id` | `INTERNAL` | An identifier of a thing |
| `transfer_event` | `from_status` | `CONFIDENTIAL` | `transfer.status`'s reasoning — history is the same facts, older |
| `transfer_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `transfer_event` | `actor_id` | `RESTRICTED-PII` | As `initiated_by` |
| `transfer_event` | `occurred_at` | `CONFIDENTIAL` | As `initiated_at` |

### `transfers.beneficiary` — *added by `P4-TSK-006`*

**A person's saved address book.** The row is a convenience, never a trust decision — and its
one free-text column is the reason the section exists: a person names people.

| Table | Column | Level | Why |
|---|---|---|---|
| `transfers.beneficiary` | `id` | `INTERNAL` | An aggregate identifier |
| `transfers.beneficiary` | `party_id` | `CONFIDENTIAL` | The `consent_record.party_id` reasoning: the pairing is the fact — this person saves destinations, and paired with `destination_account_id` it discloses a relationship between two people. What it resolves to stays `RESTRICTED-PII` as ever |
| `transfers.beneficiary` | `display_name` | `RESTRICTED-PII` | **Free text a person writes about a person** — "Mum", a full name, a nickname that identifies. The `party.display_name` reasoning at one remove, and the plan (§8) classifies it in as many words |
| `transfers.beneficiary` | `destination_account_id` | `INTERNAL` | An identifier of a thing (`transfer.destination_account_id`'s reasoning); the relationship fact lives in the pairing and is carried by `party_id`'s level — the `consent_record.purpose` idiom |
| `transfers.beneficiary` | `status` | `INTERNAL` | An enumeration of two values, both the person's own acts — no administrative state, unlike `customer_account.status` |
| `transfers.beneficiary` | `created_at` | `CONFIDENTIAL` | Dates a person's act of saving a destination — `consent_record.recorded_at`'s reasoning |
| `transfers.beneficiary` | `removed_at` | `CONFIDENTIAL` | As `created_at` |

### `paymentmethods.payment_method` — *added by `P5-TSK-004`; the bank kind by `P7-TSK-007`*

**The PCI boundary's subject** (`INV-PAY-02`): a token reference plus display metadata, every
column's shape unable to carry a PAN by `CHECK` — which is why the levels below describe
instrument-linked data and never card data, there being no column that could hold any.
`P7-TSK-007` extends the same doctrine to bank data (`INV-RAIL-03`): the destination column's
`CHECK`s refuse account-number, international-identifier and phone shapes, so no column can
hold a bank identifier either.

| Table | Column | Level | Why |
|---|---|---|---|
| `payment_method` | `id` | `INTERNAL` | An aggregate identifier |
| `payment_method` | `kind` | `INTERNAL` | An enumeration of two values — which registry family the row is, disclosing nothing of the instrument |
| `payment_method` | `destination_reference` | `RESTRICTED-PII` | **The bank instrument reference itself** (`INV-RAIL-03`) — resolves at the rail provider to a person's account, and paired with the confined scheme credential it is payable-to. `token_reference`'s reasoning verbatim, carried structurally by the wrapped `DestinationReference` |
| `payment_method` | `payee_check` | `CONFIDENTIAL` | A fact about a person's instrument — the scheme directory's name-check word; `brand`'s tier, and deliberately never the checked name itself, which no column may hold |
| `payment_method` | `no_match_acknowledged_at` | `CONFIDENTIAL` | Dates a person's explicit consent to a mismatch (ADR-0062 §2) — `consent_record.recorded_at`'s reasoning |
| `payment_method` | `party_id` | `CONFIDENTIAL` | The `beneficiary.party_id` reasoning: the pairing is the fact — this person holds payment instruments |
| `payment_method` | `token_reference` | `RESTRICTED-PII` | **The instrument reference itself** — resolves at the provider to a person's card, and paired with the confined API credential it is chargeable. The `document.checksum_sha256` possession-oracle reasoning: never in a log, a message or a rendering, which the wrapped `TokenReference` carries structurally |
| `payment_method` | `brand` | `CONFIDENTIAL` | A fact about a person's instrument — `beneficiary.created_at`'s tier of disclosure, not an identifier |
| `payment_method` | `display_suffix` | `RESTRICTED-PII` | **A partial instrument identifier** — last4 is displayable by PCI's own definition, and its ceiling is still the instrument it partially names; paired with brand and expiry it narrows to one card |
| `payment_method` | `expiry_month` | `CONFIDENTIAL` | As `brand` |
| `payment_method` | `expiry_year` | `CONFIDENTIAL` | As `brand` |
| `payment_method` | `status` | `INTERNAL` | An enumeration of two values, both the person's own acts — the `beneficiary.status` reasoning |
| `payment_method` | `created_at` | `CONFIDENTIAL` | Dates a person's act of attaching an instrument — `consent_record.recorded_at`'s reasoning |
| `payment_method` | `detached_at` | `CONFIDENTIAL` | As `created_at` |

### `payments` — the intent, attempt, refund, their histories and the evidence — *added by `P5-TSK-008`*

**The payment domain's rows** (ADR-0045): the customer's objective, the provider-facing try,
the bounded return, their append-only histories, and the verbatim provider evidence. The
amounts are `RESTRICTED-FINANCIAL` (`transfer.amount_minor`'s reasoning verbatim); the
provider's operation references are deliberately **not** the token's level — each names one
operation, already scoped to an approved amount, and unlike `payment_method.token_reference`
it authorises nothing new even with the confined credential; the evidence rows that quote
them are classified at the ceiling regardless.

| Table | Column | Level | Why |
|---|---|---|---|
| `payment_intent` | `id` | `INTERNAL` | An aggregate identifier — and the capture posting's join value |
| `payment_intent` | `party_id` | `CONFIDENTIAL` | The `payment_method.party_id` reasoning: the pairing is the fact — this person pays from a saved instrument |
| `payment_intent` | `customer_id` | `INTERNAL` | The `customer_account.customer_id` reasoning: an identifier of a thing, not a fact about it |
| `payment_intent` | `payment_method_id` | `INTERNAL` | An identifier of a thing; what it resolves to is `payment_method`'s to classify |
| `payment_intent` | `credit_account_id` | `INTERNAL` | A ledger-account identifier by value — `transfer.destination_account_id`'s reasoning *(named `wallet_account_id` until `P7-TSK-002` paid the `P6-TSK-005` rename debt, payments `V012`)* |
| `payment_intent` | `debit_account_id` | `INTERNAL` | The payer's own wallet account when the instrument IS the wallet (`P7-TSK-011`, the intent's instrument XOR) — `credit_account_id`'s reasoning on the debit side |
| `payment_intent` | `capture_mode` | `INTERNAL` | An enumerated processing decision (ADR-0059, `P7-TSK-002`) — whether capture follows authorization without a further decision; nothing about a person |
| `payment_intent` | `amount_minor` | `RESTRICTED-FINANCIAL` | A customer's commanded amount — `transfer.amount_minor`'s reasoning verbatim |
| `payment_intent` | `currency` | `RESTRICTED-FINANCIAL` | Meaningless without the amount and meaning-giving with it |
| `payment_intent` | `scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape (`INV-MON-05`) |
| `payment_intent` | `status` | `CONFIDENTIAL` | What happened to a person's payment — `transfer.status`'s reasoning |
| `payment_intent` | `created_at` | `CONFIDENTIAL` | Dates a person's financial act |
| `payment_intent_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `payment_intent_event` | `intent_id` | `INTERNAL` | An identifier of a thing |
| `payment_intent_event` | `from_status` | `CONFIDENTIAL` | `payment_intent.status`'s reasoning — history is the same facts, older |
| `payment_intent_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `payment_intent_event` | `actor_id` | `RESTRICTED-PII` | The acting identity - a person's UUID as text, or `system` for the platform's own acts (`V006`, `P5-TSK-009`: an outcome is a provider's answer and has no session). `audit_record.actor_id`'s reasoning and its model |
| `payment_intent_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in - `audit_record.actor_type`'s reasoning (`V006`) |
| `payment_intent_event` | `occurred_at` | `CONFIDENTIAL` | Dates a person's financial act |
| `payment_attempt` | `id` | `INTERNAL` | An aggregate identifier — the capture posting's key carries it (`payment-capture:<attemptId>`) |
| `payment_attempt` | `intent_id` | `INTERNAL` | An identifier of a thing |
| `payment_attempt` | `auth_reference` | `INTERNAL` | An operation reference the platform minted (`INV-PAY-04`) — an identifier of an operation, not a fact about a person |
| `payment_attempt` | `capture_reference` | `INTERNAL` | As `auth_reference` |
| `payment_attempt` | `auth_provider_reference` | `CONFIDENTIAL` | The provider's name for one operation on a person's instrument (the section header's recorded distinction from the token: scoped to its own operation, it authorises nothing new). A fact about a person's payment, handled like `status` |
| `payment_attempt` | `capture_provider_reference` | `CONFIDENTIAL` | As `auth_provider_reference` |
| `payment_attempt` | `void_reference` | `INTERNAL` | As `auth_reference` — OUR minted reference for the release, stored before the send (`P7-TSK-004`, `INV-PAY-04`) |
| `payment_attempt` | `void_provider_reference` | `CONFIDENTIAL` | As `auth_provider_reference` — the provider's acknowledgement of the release (`P7-TSK-004`) |
| `clearing_record` | `id` | `INTERNAL` | Surrogate identifier (`P7-TSK-005`) |
| `clearing_record` | `attempt_id` | `INTERNAL` | Foreign key to the cleared capture's attempt |
| `clearing_record` | `acquirer_reference` | `CONFIDENTIAL` | The acquirer's reference for one cleared card transaction (the ARN class) — the `auth_provider_reference` reasoning: scoped to its own operation, it authorises nothing new, and it is Phase 8's primary match key |
| `clearing_record` | `network_transaction_id` | `CONFIDENTIAL` | As `acquirer_reference` — the card network's own identifier |
| `clearing_record` | `recorded_at` | `INTERNAL` | Server clock at recording (ADR-0014) |
| `withdrawal` | `id` | `INTERNAL` | An aggregate identifier (`P7-TSK-008`) |
| `withdrawal` | `party_id` | `CONFIDENTIAL` | The pairing is the fact — this person withdraws money (the `payment_intent.party_id` reasoning) |
| `withdrawal` | `customer_id` | `CONFIDENTIAL` | As `party_id` — the owning relationship, by value (ADR-0029) |
| `withdrawal` | `wallet_account_id` | `INTERNAL` | Which ledger account funds it — the intent's `credit_account_id` reasoning *(classified `CONFIDENTIAL` until the Phase 7 review, `P7-DOC-001`, which found the level contradicting the reasoning it cites: a ledger-account identifier by value is `INTERNAL` on the intent, its debit side and the transfer alike)* |
| `withdrawal` | `payment_method_id` | `CONFIDENTIAL` | Which registered instrument it pays to — an identifier, never the destination |
| `withdrawal` | `destination_reference` | `RESTRICTED-PII` | **The platform's copy of the bank instrument reference** (`INV-RAIL-03`) — `paymentmethods.payment_method.destination_reference`'s row verbatim: resolves at the rail provider to a person's account; never in a log, an event or a response |
| `withdrawal` | `amount_minor` | `RESTRICTED-FINANCIAL` | An amount (`INV-AUD-02`) |
| `withdrawal` | `currency` | `INTERNAL` | ISO 4217 |
| `withdrawal` | `scale` | `INTERNAL` | The amount's scale |
| `withdrawal` | `end_to_end_reference` | `CONFIDENTIAL` | OUR reference on the scheme's wire (`INV-PAY-04`) — the `provider_idempotency_reference` reasoning: scoped to one operation, Phase 8's join key |
| `withdrawal` | `rail` | `INTERNAL` | The routed rail's stored literal (the `payment_attempt.rail` row's reasoning) |
| `withdrawal` | `status` | `INTERNAL` | The machine's word |
| `withdrawal` | `failure_reason` | `INTERNAL` | An enumerated failure class, never provider text |
| `withdrawal` | `scheme_reference` | `CONFIDENTIAL` | The scheme's transaction reference (the `acquirer_reference` class) — Phase 8's match key |
| `withdrawal` | `settlement_cycle` | `INTERNAL` | The scheme's cycle identifier — a bucket name, not an identifier of anyone |
| `withdrawal` | `dispatch_key` | `INTERNAL` | The client's idempotency key, unique per customer (ADR-0057 §5) |
| `withdrawal` | `hold_reference` | `INTERNAL` | The ledger hold the dispatch placed |
| `withdrawal` | `created_at` | `CONFIDENTIAL` | Dates a person's act (the `payment_intent.created_at` reasoning) |
| `withdrawal` | `last_dispatched_at` | `INTERNAL` | The send permit (ADR-0057 §4) |
| `withdrawal_event` | `id` | `INTERNAL` | A sequence identifier |
| `withdrawal_event` | `withdrawal_id` | `INTERNAL` | The trail's subject |
| `withdrawal_event` | `from_status` | `INTERNAL` | The machine's word |
| `withdrawal_event` | `to_status` | `INTERNAL` | The machine's word |
| `withdrawal_event` | `actor_id` | `CONFIDENTIAL` | Who moved it — the audit actor class |
| `withdrawal_event` | `actor_type` | `INTERNAL` | An enumerated population |
| `withdrawal_event` | `occurred_at` | `INTERNAL` | Application-stamped transition instant |
| `provider_evidence` | `withdrawal_id` | `INTERNAL` | The evidence's third subject (`P7-TSK-008`); the bytes' own rows above carry the classification that matters |
| `provider_evidence` | `dispute_response_id` | `INTERNAL` | The evidence's fourth subject (`P7-TSK-014`); the bytes' own rows carry the classification that matters |
| `routing_decision` | `withdrawal_id` | `INTERNAL` | The decision's second subject (`P7-TSK-008`, ADR-0060 §2) — exactly one of intent and withdrawal, no FK by the refusal-precedes-birth decision `V016` records |
| `payment_attempt` | `authorized_amount_minor` | `RESTRICTED-FINANCIAL` | The issuer's promised amount — a customer amount |
| `payment_attempt` | `authorized_currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `payment_attempt` | `authorized_scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `payment_attempt` | `captured_amount_minor` | `RESTRICTED-FINANCIAL` | The amount actually taken — the posting's own number |
| `payment_attempt` | `captured_currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `payment_attempt` | `captured_scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `payment_attempt` | `failure_reason` | `CONFIDENTIAL` | `DECLINED` is a fact about a person's finances, not an enumeration technicality — `transfer.failure_reason`'s reasoning verbatim |
| `payment_attempt` | `status` | `CONFIDENTIAL` | What happened to a person's payment operation |
| `payment_attempt` | `created_at` | `CONFIDENTIAL` | Dates a person's financial act |
| `payment_attempt` | `rail` | `INTERNAL` | An enumerated name of the way the money travels (ADR-0059, `P7-TSK-001`) — it keys into declared capabilities that are code, and says nothing about a person the row's identifiers do not already say |
| `payment_attempt` | `interaction_model` | `INTERNAL` | Which machine the attempt lives in (ADR-0059 §2, `P7-TSK-002`) — the `rail` row's reasoning: an enumerated name keying into machines that are code |
| `payment_attempt` | `end_to_end_reference` | `CONFIDENTIAL` | OUR reference on the push model (`P7-TSK-009`, `INV-PAY-04`) — the `withdrawal.end_to_end_reference` row verbatim: scoped to one operation, the callback's attribution key, Phase 8's join |
| `payment_attempt` | `authorization_handle` | `RESTRICTED-PII` | **The payer's capability URL** (`P7-TSK-009`, ADR-0062 §5): possession can complete or observe one person's live authorization flow — the `session.token_hash` reasoning, and STRONGER, because this is the live value itself, stored bare of necessity: the scheme minted it and the owner must read it back. `Sensitive` end to end; rendered once to its owner on the awaiting view; never in a log, an event or an audit record |
| `payment_attempt` | `scheme_reference` | `CONFIDENTIAL` | The scheme's transaction reference (`P7-TSK-009`) — the `withdrawal.scheme_reference` / `acquirer_reference` class: Phase 8's match key |
| `payment_attempt` | `settlement_cycle` | `INTERNAL` | The scheme's cycle identifier — a bucket name, not an identifier of anyone (`P7-TSK-009`) |
| `payment_attempt` | `last_dispatched_at` | `INTERNAL` | The initiation permit (`P7-TSK-009`, ADR-0062 §3 adapted) — the `withdrawal.last_dispatched_at` reasoning |
| `unmatched_confirmation` | `id` | `INTERNAL` | A record identifier (`P7-TSK-009`) |
| `unmatched_confirmation` | `rail` | `INTERNAL` | The rail the statement arrived on — the `payment_attempt.rail` reasoning |
| `unmatched_confirmation` | `scheme_reference` | `CONFIDENTIAL` | The scheme's transaction reference for money with no commercial home (`INV-REC-05`) — the `acquirer_reference` class, and the parking's arbiter |
| `unmatched_confirmation` | `amount_minor` | `RESTRICTED-FINANCIAL` | An amount (`INV-AUD-02`) — parked value is still value |
| `unmatched_confirmation` | `currency` | `INTERNAL` | ISO 4217 |
| `unmatched_confirmation` | `scale` | `INTERNAL` | The amount's scale |
| `unmatched_confirmation` | `received_at` | `INTERNAL` | Server clock at parking (ADR-0014) — the age the `INV-REC-05` gauge reads |
| `unmatched_confirmation` | `entry_ref` | `INTERNAL` | The suspense entry this parking posted — the chain stays walkable by stored id |
| `unmatched_confirmation` | `named_reference` | `CONFIDENTIAL` | The end-to-end reference the statement named, when it had OUR minted shape (the Phase 7 → 8 transition, `V023`) — the `payment_attempt.end_to_end_reference` row's reasoning: scoped to one operation, Phase 8's clue to a mistyped or concluded payment |
| `unmatched_confirmation` | `settlement_cycle` | `INTERNAL` | The scheme's cycle the parked value rides in — the `payment_attempt.settlement_cycle` reasoning (`V023`) |
| `unmatched_confirmation` | `cause` | `INTERNAL` | Why it parked — an enumerated name (`UNATTRIBUTED`, `ATTEMPT_CONCLUDED`, `AMOUNT_MISMATCH`; `V023`) |
| `unmatched_confirmation` | `attempt_id` | `INTERNAL` | Foreign key to the attempt the statement named, exactly when it named one (`V023`) |
| `provider_evidence` | `unmatched_confirmation_id` | `INTERNAL` | The evidence's fifth subject (the Phase 7 → 8 transition, `V023`): a parking's raw statement found by stored identifier; the bytes' own rows carry the classification that matters |
| `scheme_execution_claim` | `rail` | `INTERNAL` | The rail of the execution — the `payment_attempt.rail` reasoning (the Phase 7 → 8 transition, `V023`) |
| `scheme_execution_claim` | `scheme_reference` | `CONFIDENTIAL` | The scheme's transaction reference — the `payment_attempt.scheme_reference` class, and the one-money-fact arbiter's key |
| `scheme_execution_claim` | `subject_kind` | `INTERNAL` | What explains the execution — an enumerated name (`PAY_IN`, `WITHDRAWAL`, `RETURN`, `UNMATCHED`) |
| `scheme_execution_claim` | `subject_id` | `INTERNAL` | The explaining row's identifier (an attempt, a withdrawal, a refund or a parking) |
| `scheme_execution_claim` | `claimed_at` | `INTERNAL` | Application-stamped claim instant |
| `dispute` | `id` | `INTERNAL` | An aggregate identifier (`P7-TSK-012`) — from `P7-TSK-013`, the suffix of each stage's posting key |
| `dispute` | `provider` | `INTERNAL` | The PSP's stable adapter name: the scope its dispute references are unique in |
| `dispute` | `provider_dispute_reference` | `CONFIDENTIAL` | The PSP's identifier for one dispute on a person's payment — the `acquirer_reference` class: scoped to its own dispute, it authorises nothing, and it is Phase 8's join to the stage entries. Shown to operators only, never to the merchant |
| `dispute` | `attempt_id` | `INTERNAL` | Foreign key to the contested attempt |
| `dispute` | `reason` | `CONFIDENTIAL` | The platform's reason category, never the network's code — `FRAUD` is an allegation about a person's payment (`payment_attempt.failure_reason`'s reasoning) |
| `dispute` | `stage` | `CONFIDENTIAL` | What is happening to a person's payment — `payment_attempt.status`'s reasoning |
| `dispute` | `chargeback_amount_minor` | `RESTRICTED-FINANCIAL` | An amount (`INV-AUD-02`) — what the network took, present exactly once the funds are taken (`payment_attempt.captured_amount_minor`'s reasoning: the posting's own number, from `P7-TSK-013`) |
| `dispute` | `chargeback_currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape (the captured amount's row) |
| `dispute` | `chargeback_scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `dispute` | `counterparty_share_amount_minor` | `RESTRICTED-FINANCIAL` | An amount (`INV-AUD-02`) — what the chargeback charged the payment's counterparty, the combined bound's judgement (`P7-TSK-013`, `INV-DSP-01`); grows only by re-attribution |
| `dispute` | `counterparty_share_currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape — the chargeback's own currency, `CHECK`-held |
| `dispute` | `counterparty_share_scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `dispute` | `parked_share_amount_minor` | `RESTRICTED-FINANCIAL` | An amount — the counterparty's share parked in `CHARGEBACK_RECOVERABLE` because its account took no postings (ADR-0061 §5): a sum a person or merchant owes, recovered by an operator |
| `dispute` | `parked_share_currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `dispute` | `parked_share_scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `dispute` | `dispute_fee_amount_minor` | `RESTRICTED-FINANCIAL` | An amount — the dispute fee the PSP charged the platform (ADR-0061 §4), a cost of a person's contested payment |
| `dispute` | `dispute_fee_currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `dispute` | `dispute_fee_scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `dispute` | `respond_by` | `CONFIDENTIAL` | The network's representment deadline on a person's contested payment (`P7-TSK-014`, ADR-0061 §7) - dates the contest, the `opened_at` reasoning; recorded once, it refuses only the platform's own late dispatch and raises the alarm |
| `dispute` | `opened_at` | `CONFIDENTIAL` | Dates a contest of a person's payment (the `payment_attempt.created_at` reasoning) |
| `dispute_event` | `id` | `INTERNAL` | A sequence identifier |
| `dispute_event` | `dispute_id` | `INTERNAL` | The trail's subject |
| `dispute_event` | `from_stage` | `CONFIDENTIAL` | `dispute.stage`'s reasoning — history is the same facts, older |
| `dispute_event` | `to_stage` | `CONFIDENTIAL` | As `from_stage` |
| `dispute_event` | `actor_id` | `CONFIDENTIAL` | Who moved it — the audit actor class (the platform for every notified stage) |
| `dispute_event` | `actor_type` | `INTERNAL` | An enumerated population |
| `dispute_event` | `occurred_at` | `INTERNAL` | Application-stamped transition instant |
| `dispute_evidence` | `id` | `INTERNAL` | A record identifier (`P7-TSK-014`). Generated |
| `dispute_evidence` | `dispute_id` | `INTERNAL` | The document's dispute - an identifier of a thing |
| `dispute_evidence` | `kind` | `CONFIDENTIAL` | *Which evidence* a merchant holds about a person's purchase (a delivery note, a chat log) is a fact about that purchase - `kyc_document.document_type`'s reasoning |
| `dispute_evidence` | `content_type` | `INTERNAL` | A media format. Three values, none about a person |
| `dispute_evidence` | `content_ciphertext` | `RESTRICTED-PII` | **The document** - receipts, correspondence, delivery records naming a person: classified at the ceiling of what it decrypts to (`kyc_document.content_ciphertext`'s reasoning, ADR-0022), under its own key (`INV-DSP-03`) |
| `dispute_evidence` | `content_nonce` | `INTERNAL` | Public-by-design cryptographic material; useless without the key |
| `dispute_evidence` | `key_version` | `INTERNAL` | Which key wrote the row - operational metadata for rotation |
| `dispute_evidence` | `checksum_sha256` | `RESTRICTED-PII` | **A possession oracle over the content** - `kyc_document.checksum_sha256`'s reasoning verbatim: anyone holding a candidate document can confirm it is this one |
| `dispute_evidence` | `content_length` | `CONFIDENTIAL` | Weakly identifying; with the kind it narrows a known document - errs up |
| `dispute_evidence` | `uploaded_by_id` | `CONFIDENTIAL` | Who attached it - the merchant or the operator, the audit actor class (`withdrawal_event.actor_id`) |
| `dispute_evidence` | `uploaded_by_type` | `INTERNAL` | An enumerated population |
| `dispute_evidence` | `uploaded_at` | `CONFIDENTIAL` | Dates an act in a person's dispute |
| `dispute_response` | `id` | `INTERNAL` | An aggregate identifier (`P7-TSK-014`) |
| `dispute_response` | `dispute_id` | `INTERNAL` | The answered dispute - an identifier of a thing |
| `dispute_response` | `kind` | `CONFIDENTIAL` | Whether a person's chargeback was contested or conceded - `dispute.stage`'s reasoning |
| `dispute_response` | `status` | `CONFIDENTIAL` | What happened to the answer - `payment_attempt.status`'s reasoning |
| `dispute_response` | `failure_reason` | `CONFIDENTIAL` | `payment_attempt.failure_reason`'s reasoning |
| `dispute_response` | `provider_idempotency_reference` | `CONFIDENTIAL` | OUR reference (`INV-PAY-04`) - the `withdrawal.end_to_end_reference` class: scoped to one operation, the query's key |
| `dispute_response` | `provider_reference` | `CONFIDENTIAL` | The PSP's submission reference - the `acquirer_reference` class, reconciliation's key; operators only |
| `dispute_response` | `evidence_ids` | `INTERNAL` | Identifiers of the documents it carried |
| `dispute_response` | `requested_by_id` | `CONFIDENTIAL` | Who answered - the audit actor class |
| `dispute_response` | `requested_by_type` | `INTERNAL` | An enumerated population |
| `dispute_response` | `reason` | `CONFIDENTIAL` | The operator's own words about a person's dispute - `rail_availability.reason`'s reasoning |
| `dispute_response` | `dispatch_scope` | `INTERNAL` | The claim's scope - `idempotency_record.scope`'s reasoning |
| `dispute_response` | `dispatch_key` | `INTERNAL` | The idempotency claim whose dispatch transaction created the row - **caller-chosen** key material, `refund.dispatch_key`'s reasoning and §5 |
| `dispute_response` | `send_permit` | `INTERNAL` | The send permit (ADR-0057 §4) - `withdrawal.last_dispatched_at`'s reasoning |
| `dispute_response` | `created_at` | `CONFIDENTIAL` | Dates an act in a person's dispute |
| `dispute_response_event` | `id` | `INTERNAL` | A sequence identifier |
| `dispute_response_event` | `response_id` | `INTERNAL` | The trail's subject |
| `dispute_response_event` | `from_status` | `INTERNAL` | The machine's word |
| `dispute_response_event` | `to_status` | `INTERNAL` | The machine's word |
| `dispute_response_event` | `actor_id` | `CONFIDENTIAL` | Who moved it - the audit actor class |
| `dispute_response_event` | `actor_type` | `INTERNAL` | An enumerated population |
| `dispute_response_event` | `occurred_at` | `INTERNAL` | Application-stamped transition instant |
| `routing_policy_version` | `id` | `INTERNAL` | An identifier of a thing — **the value a decision pins** (`INV-HIST-04`), the `fee_schedule_version.id` reasoning verbatim |
| `routing_policy_version` | `version` | `INTERNAL` | An ordinal |
| `routing_policy_version` | `effective_from` | `CONFIDENTIAL` | When a routing change starts applying — with the rules it dates an operational shift |
| `routing_policy_version` | `created_at` | `CONFIDENTIAL` | When the change was decided |
| `routing_policy_version` | `created_by` | `RESTRICTED-PII` | The acting operator's identity — `audit_record.actor_id`'s model |
| `routing_policy_version` | `reason` | `CONFIDENTIAL` | The operator's own words about an operational judgement (`fee_schedule`-adjacent: why the platform routes as it does) |
| `routing_rule` | `id` | `INTERNAL` | An identifier of a thing |
| `routing_rule` | `policy_version_id` | `INTERNAL` | An identifier of a thing |
| `routing_rule` | `rule_index` | `INTERNAL` | An ordinal |
| `routing_rule` | `direction` | `INTERNAL` | A fixed vocabulary (`PaymentDirection`) |
| `routing_rule` | `instrument_kind` | `INTERNAL` | A fixed vocabulary (`InstrumentKind`) |
| `routing_rule` | `currency` | `INTERNAL` | An enumeration; part of a matcher with no amount beside it |
| `routing_rule` | `ceiling_amount_minor` | `CONFIDENTIAL` | An operational bound the platform chose, not a person's amount and not a price — but with the rails it discloses how the platform hedges a rail, which is operational posture |
| `routing_rule` | `ceiling_currency` | `INTERNAL` | Part of the monetary shape; meaningless without the bound |
| `routing_rule` | `ceiling_scale` | `INTERNAL` | As `ceiling_currency` |
| `routing_rule_rail` | `rule_id` | `INTERNAL` | An identifier of a thing |
| `routing_rule_rail` | `position` | `INTERNAL` | An ordinal |
| `routing_rule_rail` | `rail` | `INTERNAL` | An enumerated rail name — `payment_attempt.rail`'s reasoning |
| `rail_availability` | `rail` | `INTERNAL` | An enumerated rail name |
| `rail_availability` | `available` | `CONFIDENTIAL` | Whether a rail is out of service is operational posture — an outage disclosed is an outage advertised |
| `rail_availability` | `reason` | `CONFIDENTIAL` | The operator's own words about an incident or a decision |
| `rail_availability` | `changed_by` | `RESTRICTED-PII` | The acting operator's identity — `audit_record.actor_id`'s model |
| `rail_availability` | `changed_at` | `CONFIDENTIAL` | Dates the operational act |
| `routing_decision` | `id` | `INTERNAL` | An identifier of a thing |
| `routing_decision` | `intent_id` | `INTERNAL` | An identifier of a thing — the payment side of the decision join |
| `routing_decision` | `policy_version_id` | `INTERNAL` | The pin itself (`INV-HIST-04`): an identifier |
| `routing_decision` | `direction` | `INTERNAL` | A fixed vocabulary, judged input |
| `routing_decision` | `instrument_kind` | `INTERNAL` | A fixed vocabulary, judged input |
| `routing_decision` | `amount_minor` | `RESTRICTED-FINANCIAL` | The judged amount is the intent's commanded amount, snapshotted so the decision recomputes — `payment_intent.amount_minor`'s classification travels with the value |
| `routing_decision` | `currency` | `RESTRICTED-FINANCIAL` | Meaningless without the amount and meaning-giving with it |
| `routing_decision` | `scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape (`INV-MON-05`) |
| `routing_decision` | `matched_rule_index` | `INTERNAL` | An ordinal into the pinned version |
| `routing_decision` | `chosen_rail` | `INTERNAL` | An enumerated rail name — which way a payment travelled, said by identifiers the row already carries |
| `routing_decision` | `created_at` | `CONFIDENTIAL` | Dates a person's financial act (`payment_intent.created_at`'s reasoning) |
| `routing_decision_step` | `decision_id` | `INTERNAL` | An identifier of a thing |
| `routing_decision_step` | `step_index` | `INTERNAL` | An ordinal |
| `routing_decision_step` | `rail` | `INTERNAL` | An enumerated rail name |
| `routing_decision_step` | `verdict` | `INTERNAL` | A fixed vocabulary (`RoutingStepVerdict`) |
| `routing_decision_step` | `rejection` | `CONFIDENTIAL` | Why a rail refused a payment: with `UNAVAILABLE` it discloses an outage, the `rail_availability.available` reasoning |
| `routing_decision_step` | `rail_available` | `CONFIDENTIAL` | The availability observation used — as `rail_availability.available` |
| `routing_decision_step` | `descriptor_version` | `INTERNAL` | An ordinal of a compiled declaration |
| `payment_attempt_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `payment_attempt_event` | `attempt_id` | `INTERNAL` | An identifier of a thing |
| `payment_attempt_event` | `from_status` | `CONFIDENTIAL` | History is the same facts, older |
| `payment_attempt_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `payment_attempt_event` | `actor_id` | `RESTRICTED-PII` | The acting identity - a person's UUID as text, or `system` for the platform's own acts (`V006`, `P5-TSK-009`: an outcome is a provider's answer and has no session). `audit_record.actor_id`'s reasoning and its model |
| `payment_attempt_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in - `audit_record.actor_type`'s reasoning (`V006`) |
| `payment_attempt_event` | `occurred_at` | `CONFIDENTIAL` | Dates a person's financial act |
| `refund` | `id` | `INTERNAL` | An aggregate identifier — the refund posting's key carries it (`payment-refund:<refundId>`) |
| `refund` | `attempt_id` | `INTERNAL` | An identifier of a thing |
| `refund` | `amount_minor` | `RESTRICTED-FINANCIAL` | Money returned to a person — a customer amount |
| `refund` | `currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `refund` | `scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `refund` | `reason` | `RESTRICTED-PII` | **Free text written by a person** — an operator explains a refund in words that can name people and disputes — `transfer.reference`'s reasoning verbatim: content constrained by no type, handled at the ceiling |
| `refund` | `hold_reference` | `INTERNAL` | A hold identifier by value; what it resolves to is `ledger.hold`'s to classify |
| `refund` | `provider_idempotency_reference` | `INTERNAL` | An operation reference the platform minted (`INV-PAY-04`) |
| `refund` | `provider_reference` | `CONFIDENTIAL` | As `payment_attempt.auth_provider_reference` |
| `refund` | `status` | `CONFIDENTIAL` | What happened to a person's refund |
| `refund` | `created_at` | `CONFIDENTIAL` | Dates a privileged act against a person's account — `transfer.reversed_at`'s reasoning |
| `refund` | `dispatch_key` | `INTERNAL` | The idempotency claim whose Tx1 created the row (`V008`, `P5-TSK-016`) — **caller-chosen** key material, `idempotency_record.idempotency_key`'s reasoning and §5. *Row added by the Phase 5 → 6 transition: `V008` landed the column without one and no targeted tier runs `ColumnClassificationTest` — found by the transition's fleet-wide battery, the register-decay class in this register* |
| `refund` | `last_dispatched_at` | `CONFIDENTIAL` | The latest send permit (`V009`, the Phase 6 → 7 transition; ADR-0057 §4's discipline): when the refund was last authorised onto the wire — `merchant_payout.last_dispatched_at`'s reasoning. *Classified in the migration's own change, the lesson of the row above* |
| `refund_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `refund_event` | `refund_id` | `INTERNAL` | An identifier of a thing |
| `refund_event` | `from_status` | `CONFIDENTIAL` | History is the same facts, older |
| `refund_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `refund_event` | `actor_id` | `RESTRICTED-PII` | The acting identity - a person's UUID as text, or `system` for the platform's own acts (`V006`, `P5-TSK-009`: an outcome is a provider's answer and has no session). `audit_record.actor_id`'s reasoning and its model |
| `refund_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in - `audit_record.actor_type`'s reasoning (`V006`) |
| `refund_event` | `occurred_at` | `CONFIDENTIAL` | Dates a privileged act against a person's account |
| `provider_evidence` | `id` | `INTERNAL` | An aggregate identifier |
| `provider_evidence` | `attempt_id` | `INTERNAL` | An identifier of a thing |
| `provider_evidence` | `refund_id` | `INTERNAL` | An identifier of a thing |
| `provider_evidence` | `kind` | `INTERNAL` | An enumeration of wire-artefact kinds — a fact about a message, not a person |
| `provider_evidence` | `content_ciphertext` | `RESTRICTED-PII` | **The provider's raw payload about a person's payment.** Classified at the ceiling of what it decrypts to (ADR-0022): provider payloads may quote masked instrument data, names and issuer messages — `verification_evidence.content_ciphertext`'s reasoning verbatim, and the level is what governs handling if the encryption is ever broken, mis-keyed or stripped |
| `provider_evidence` | `content_nonce` | `INTERNAL` | Public-by-design cryptographic material; useless without the key |
| `provider_evidence` | `key_version` | `INTERNAL` | Which key wrote the row — operational metadata for rotation |
| `provider_evidence` | `checksum_sha256` | `RESTRICTED-PII` | The possession oracle again: anyone holding a candidate payload can confirm this is what the provider sent about this person's payment. Classified with what it fingerprints — `kyc_document.checksum_sha256` |
| `provider_evidence` | `content_length` | `CONFIDENTIAL` | Weakly identifying alone; a decline body is longer than an approval's, so the length leaks the outcome's shape. Errs up, because ADR-0022 forbids reclassifying later |
| `provider_evidence` | `recorded_at` | `CONFIDENTIAL` | Dates a person's payment traffic |

### `merchant` — the counterparty and its history — *added by `P6-TSK-003`*

The first tables whose subject is a commercial counterparty rather than a person — and the
classification does not relax for it: which organisations the platform does business with,
and what standing they hold, is commercially sensitive in both directions. **No balance
column exists here and none ever will** (`INV-MER-02`); the payable is the ledger position.

| Table | Column | Level | Note |
|---|---|---|---|
| `merchant` | `id` | `INTERNAL` | A generated identifier — the payable account's opaque `owner_ref` and the future tenant key (`INV-MER-01`) |
| `merchant` | `party_ref` | `INTERNAL` | An identifier of a thing (`party.party` by value, ADR-0029) |
| `merchant` | `legal_name` | `CONFIDENTIAL` | An organisation's legal identity — not a person's name (`PartyKind.ORGANISATION` gates onboarding), but who the platform banks is a commercial fact both sides treat as non-public |
| `merchant` | `display_name` | `CONFIDENTIAL` | As `legal_name` — customer-facing at checkout one task on, but *whose* checkout it appears on is the sensitive part |
| `merchant` | `settlement_currency` | `INTERNAL` | An enumeration; part of the monetary shape with no amount beside it |
| `merchant` | `status` | `CONFIDENTIAL` | A merchant's standing — a suspension is a judgement about a counterparty |
| `merchant` | `created_at` | `CONFIDENTIAL` | When a commercial relationship began — `party.registered_at`'s reasoning |
| `merchant` | `status_changed_at` | `CONFIDENTIAL` | Dates a standing judgement |
| `merchant_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `merchant_event` | `merchant_id` | `INTERNAL` | An identifier of a thing |
| `merchant_event` | `from_status` | `CONFIDENTIAL` | History is the same facts, older |
| `merchant_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `merchant_event` | `actor_id` | `RESTRICTED-PII` | The acting identity — `audit_record.actor_id`'s reasoning and its model |
| `merchant_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in — `audit_record.actor_type`'s reasoning |
| `merchant_event` | `occurred_at` | `CONFIDENTIAL` | Dates a standing judgement against a counterparty |
| `merchant_api_key` | `id` | `INTERNAL` | **Public by design** — it is the lookup prefix of `<keyId>.<secret>` (ADR-0052) and the value audit records name. Knowing it achieves nothing without the secret |
| `merchant_api_key` | `merchant_id` | `INTERNAL` | An identifier of a thing |
| `merchant_api_key` | `secret_hash` | `CONFIDENTIAL` | A SHA-256 digest of 32 random bytes — **not crackable**, so not `RESTRICTED`; classified here for `session.token_hash`'s reason: in a log it is a precise identifier of one merchant's live credential, which is the single most useful thing to an attacker reading log archives |
| `merchant_api_key` | `algorithm` | `INTERNAL` | What produced the hash (`INV-IDN-02`) — a fixed vocabulary, and publishing it tells an attacker only what the code already says |
| `merchant_api_key` | `status` | `CONFIDENTIAL` | Whether a counterparty's integration is live |
| `merchant_api_key` | `issued_at` | `CONFIDENTIAL` | Dates a credential's life — correlates with an integration going live |
| `merchant_api_key` | `issued_by` | `RESTRICTED-PII` | The acting operator's identity — `audit_record.actor_id`'s reasoning and its model |
| `merchant_api_key` | `revoked_at` | `CONFIDENTIAL` | Dates a security judgement |
| `merchant_api_key_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `merchant_api_key_event` | `key_id` | `INTERNAL` | An identifier of a thing |
| `merchant_api_key_event` | `from_status` | `CONFIDENTIAL` | History is the same facts, older |
| `merchant_api_key_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `merchant_api_key_event` | `reason` | `RESTRICTED-PII` | **Free text written by a person** — an operator explains a revocation in words that can name people and incidents; `refund.reason`'s reasoning verbatim: content constrained by no type, handled at the ceiling |
| `merchant_api_key_event` | `actor_id` | `RESTRICTED-PII` | The acting identity — `audit_record.actor_id`'s model |
| `merchant_api_key_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in |
| `merchant_api_key_event` | `occurred_at` | `CONFIDENTIAL` | Dates a security judgement |
| `fee_schedule` | `id` | `INTERNAL` | An identifier of a thing |
| `fee_schedule` | `name` | `CONFIDENTIAL` | The platform's own pricing vocabulary — "Enterprise" beside "Standard" discloses that tiers exist and what they are called, which is competitive information about how the platform sells |
| `fee_schedule` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape with no amount beside it (`merchant.settlement_currency`'s reasoning) |
| `fee_schedule` | `created_at` | `CONFIDENTIAL` | When a pricing tier was introduced |
| `fee_schedule` | `created_by` | `RESTRICTED-PII` | The acting operator's identity — `audit_record.actor_id`'s reasoning and its model |
| `fee_schedule_version` | `id` | `INTERNAL` | An identifier of a thing — **the value an assessment pins** (`INV-HIST-04`), which is why it is an identifier rather than the terms themselves |
| `fee_schedule_version` | `fee_schedule_id` | `INTERNAL` | An identifier of a thing |
| `fee_schedule_version` | `version` | `INTERNAL` | An ordinal within a schedule |
| `fee_schedule_version` | `rate` | `RESTRICTED-FINANCIAL` | **What the platform charges.** Not an amount, and classified at the financial ceiling anyway: a rate plus a capture *is* an amount, so a log line carrying this and a gross discloses revenue. It is also the single most commercially sensitive number in this schema — what one merchant is charged, in a competitor's hands, is a negotiating position |
| `fee_schedule_version` | `fixed_amount_minor` | `RESTRICTED-FINANCIAL` | The flat charge — an amount, and the `MoneyColumns` triple's own classification (`journal_line.amount_minor`'s reasoning). **A price, not a position**: `INV-MER-02` forbids storing what the platform *owes*, and this is what it *charges* |
| `fee_schedule_version` | `fixed_currency` | `INTERNAL` | Part of the monetary shape; meaningless without the amount |
| `fee_schedule_version` | `fixed_scale` | `INTERNAL` | As `fixed_currency` |
| `fee_schedule_version` | `rounding_policy` | `INTERNAL` | A fixed vocabulary (`RoundingPolicy`); publishing it says only what the code already says. Required and never defaulted (`INV-MON-03`), which is a correctness property rather than a confidentiality one |
| `fee_schedule_version` | `refund_fee_policy` | `CONFIDENTIAL` | A term of a commercial agreement — whether the platform returns its fee on a refund is what a merchant negotiated |
| `fee_schedule_version` | `effective_from` | `CONFIDENTIAL` | When a price starts applying; with `rate` it dates a repricing |
| `fee_schedule_version` | `created_at` | `CONFIDENTIAL` | When the repricing was decided — and, with `effective_from`, the notice period |
| `fee_schedule_version` | `created_by` | `RESTRICTED-PII` | The acting operator's identity — `audit_record.actor_id`'s model |
| `merchant_fee_schedule` | `merchant_id` | `INTERNAL` | An identifier of a thing |
| `merchant_fee_schedule` | `fee_schedule_id` | `RESTRICTED-FINANCIAL` | **An identifier that is also a price.** On its own it names a row; joined to `fee_schedule_version` it says what this counterparty pays, which is exactly the disclosure `INV-MER-01` exists to prevent. Classified at the ceiling for `audit_record.target_id`'s reason — an identifier inherits the sensitivity of what it resolves to |
| `merchant_fee_schedule` | `assigned_at` | `CONFIDENTIAL` | When commercial terms last changed for this counterparty |
| `merchant_fee_schedule` | `assigned_by` | `RESTRICTED-PII` | The acting operator's identity |
| `merchant_fee_schedule_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `merchant_fee_schedule_event` | `merchant_id` | `INTERNAL` | An identifier of a thing |
| `merchant_fee_schedule_event` | `from_fee_schedule_id` | `RESTRICTED-FINANCIAL` | History is the same facts, older — `merchant_fee_schedule.fee_schedule_id`'s reasoning, and a *pair* of them discloses the direction a negotiation went |
| `merchant_fee_schedule_event` | `to_fee_schedule_id` | `RESTRICTED-FINANCIAL` | As `from_fee_schedule_id` |
| `merchant_fee_schedule_event` | `reason` | `RESTRICTED-PII` | **Free text written by a person** — an operator explains a repricing in words that can name people, deals and negotiations; `merchant_api_key_event.reason`'s reasoning verbatim: content constrained by no type, handled at the ceiling |
| `merchant_fee_schedule_event` | `actor_id` | `RESTRICTED-PII` | The acting identity — `audit_record.actor_id`'s model |
| `merchant_fee_schedule_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in |
| `merchant_fee_schedule_event` | `occurred_at` | `CONFIDENTIAL` | Dates a commercial judgement |
| `payment_fee_pin` | `payment_intent_ref` | `RESTRICTED-FINANCIAL` | The payment this prices, by value (ADR-0029). An identifier of a money movement — `audit_record.target_id`'s rule: an identifier inherits the sensitivity of what it names, and what this one names is a capture |
| `payment_fee_pin` | `merchant_id` | `RESTRICTED-FINANCIAL` | **Whose payment it is.** On its own an identifier of a thing; here it is the join that says *this merchant took a payment*, so the pair of columns is transaction data about a counterparty rather than a reference |
| `payment_fee_pin` | `fee_schedule_version_id` | `RESTRICTED-FINANCIAL` | `merchant_fee_schedule.fee_schedule_id`'s reasoning, sharper: joined to `fee_schedule_version` this says exactly what this merchant paid on this capture, which is the disclosure `INV-MER-01` exists to prevent |
| `payment_fee_pin` | `gross_amount_minor` | `RESTRICTED-FINANCIAL` | An amount — `journal_line.amount_minor`'s classification. What was agreed, never what is owed (`INV-MER-02`) |
| `payment_fee_pin` | `gross_currency` | `INTERNAL` | Part of the monetary shape; meaningless without the amount |
| `payment_fee_pin` | `gross_scale` | `INTERNAL` | As `gross_currency` |
| `payment_fee_pin` | `pinned_at` | `CONFIDENTIAL` | When the price was agreed — with `pinned_by`, the provenance of a money decision |
| `payment_fee_pin` | `pinned_by` | `RESTRICTED-PII` | The acting identity — `audit_record.actor_id`'s reasoning and its model |
| `payout_destination` | `id` | `INTERNAL` | An identifier of a thing — **the destination's version**: each change is its own row, so this is the value a payout records (`P6-TSK-011`, ADR-0056) |
| `payout_destination` | `merchant_id` | `INTERNAL` | An identifier of a thing |
| `payout_destination` | `destination_reference` | `RESTRICTED-PII` | **The provider's reference for a bank account** — `payment_method.token_reference`'s reasoning for bank data: it resolves at the provider to where a merchant's money goes, so it is never in a log, an event payload or an API response, and `PayoutDestinationReference` carries that structurally. Refused by `CHECK` if shaped like an account number, so raw bank details cannot be stored here at all |
| `payout_destination` | `display_suffix` | `RESTRICTED-PII` | **A partial account identifier** — `payment_method.display_suffix`'s reasoning: four characters an authorised operator reads to know which account they are approving, and its ceiling is still the account it partially names |
| `payout_destination` | `status` | `CONFIDENTIAL` | Where a counterparty's money is in the middle of being redirected is itself sensitive — a pending change is the fact an attacker would most like to know is cooling off |
| `payout_destination` | `proposed_by` | `RESTRICTED-PII` | The acting operator's identity — `audit_record.actor_id`'s reasoning and its model |
| `payout_destination` | `proposed_at` | `CONFIDENTIAL` | Dates a destination change |
| `payout_destination` | `proposal_reason` | `RESTRICTED-PII` | **Free text written by a person** — `audit_record.reason`'s ceiling; kept on the row so the approver reads what they approve, and never returned by the API |
| `payout_destination` | `approved_by` | `RESTRICTED-PII` | The second operator's identity — `audit_record.actor_id`'s model |
| `payout_destination` | `approved_at` | `CONFIDENTIAL` | Dates the four-eyes decision |
| `payout_destination` | `cooling_off_until` | `CONFIDENTIAL` | When a redirection of money takes effect — the window an attacker would wait out |
| `payout_destination` | `effective_at` | `CONFIDENTIAL` | When payouts started going to this destination |
| `payout_destination` | `superseded_at` | `CONFIDENTIAL` | When they stopped |
| `payout_destination` | `ended_by` | `RESTRICTED-PII` | The operator who rejected or withdrew the change |
| `payout_destination` | `ended_at` | `CONFIDENTIAL` | Dates a rejection or withdrawal |
| `payout_destination_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `payout_destination_event` | `payout_destination_id` | `INTERNAL` | An identifier of a thing |
| `payout_destination_event` | `from_status` | `CONFIDENTIAL` | History is the same facts, older |
| `payout_destination_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `payout_destination_event` | `actor_id` | `RESTRICTED-PII` | The acting identity, or the platform's for an effectuation — `audit_record.actor_id`'s model |
| `payout_destination_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in |
| `payout_destination_event` | `occurred_at` | `CONFIDENTIAL` | Dates a move in a destination change |
| `merchant_payout` | `id` | `INTERNAL` | An aggregate identifier — the payout posting's key carries it (`merchant-payout:<payoutId>`, `P6-TSK-012`) |
| `merchant_payout` | `merchant_id` | `INTERNAL` | An identifier of a thing |
| `merchant_payout` | `amount_minor` | `RESTRICTED-FINANCIAL` | Money paid out to a counterparty — `refund.amount_minor`'s reasoning for the merchant's side |
| `merchant_payout` | `currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `merchant_payout` | `scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `merchant_payout` | `destination_id` | `INTERNAL` | A destination VERSION by identifier (ADR-0056 §9) — what it resolves to is `payout_destination`'s to classify |
| `merchant_payout` | `hold_reference` | `INTERNAL` | A hold identifier by value; what it resolves to is `ledger.hold`'s to classify |
| `merchant_payout` | `provider_idempotency_reference` | `INTERNAL` | An operation reference the platform minted (`INV-PAY-04`) |
| `merchant_payout` | `provider_reference` | `CONFIDENTIAL` | The payout provider's identifier for a payout it accepted — `refund.provider_reference`'s reasoning |
| `merchant_payout` | `status` | `CONFIDENTIAL` | What happened to a counterparty's payout |
| `merchant_payout` | `failure_reason` | `CONFIDENTIAL` | Why a counterparty's payout failed — an enumeration, never provider text |
| `merchant_payout` | `dispatch_key` | `INTERNAL` | The idempotency claim whose dispatch transaction created the row — **caller-chosen** key material, `refund.dispatch_key`'s reasoning and §5 |
| `merchant_payout` | `requested_by` | `RESTRICTED-PII` | The merchant's identifier, or the acting operator's identity — `audit_record.actor_id`'s model |
| `merchant_payout` | `requested_by_type` | `INTERNAL` | Which vocabulary `requested_by` is in |
| `merchant_payout` | `reason` | `RESTRICTED-PII` | **Free text written by a person** — an operator's reason for moving a merchant's money, `refund.reason`'s ceiling; absent on the merchant's own payout |
| `merchant_payout` | `created_at` | `CONFIDENTIAL` | Dates a counterparty's payout |
| `merchant_payout` | `last_dispatched_at` | `CONFIDENTIAL` | The latest send permit (ADR-0057 §4): when the payout was last authorised onto the wire |
| `payout_return` | `id` | `INTERNAL` | A record identifier. Generated |
| `payout_return` | `payout_id` | `INTERNAL` | The returned payout - an identifier of a thing (`P8-TSK-019`) |
| `payout_return` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The returned amount** - the payout's own, by the composite key |
| `payout_return` | `currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape - `merchant_payout.currency`'s |
| `payout_return` | `scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape - `merchant_payout.scale`'s |
| `payout_return` | `external_item_ref` | `INTERNAL` | The reconciliation item whose evidence caused the application - an identifier of a thing |
| `payout_return` | `journal_entry_id` | `INTERNAL` | The return's own posting - an identifier of a thing |
| `payout_return` | `returned_on` | `CONFIDENTIAL` | The posting date - the evidence batch's stored acceptance date (`merchant_payout.created_at`'s reasoning: when money moved for a merchant) |
| `payout_return` | `value_date` | `CONFIDENTIAL` | The item's settlement date - the same reasoning |
| `payout_return` | `recorded_at` | `INTERNAL` | When the platform recorded the fact |
| `merchant_payout_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `merchant_payout_event` | `payout_id` | `INTERNAL` | An identifier of a thing |
| `merchant_payout_event` | `from_status` | `CONFIDENTIAL` | History is the same facts, older |
| `merchant_payout_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `merchant_payout_event` | `actor_id` | `RESTRICTED-PII` | The platform's identifier for every outcome it applied — `audit_record.actor_id`'s model |
| `merchant_payout_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in |
| `merchant_payout_event` | `occurred_at` | `CONFIDENTIAL` | Dates a move in a payout; the resolution sweep ages an `UNKNOWN` from it |
| `payout_evidence` | `id` | `INTERNAL` | An aggregate identifier |
| `payout_evidence` | `payout_id` | `INTERNAL` | An identifier of a thing |
| `payout_evidence` | `kind` | `INTERNAL` | An enumeration of wire-artefact kinds — a fact about a message, not a person |
| `payout_evidence` | `content_ciphertext` | `RESTRICTED-PII` | **The payout provider's raw answer about a counterparty's money.** Classified at the ceiling of what it decrypts to (ADR-0022): untrusted bytes the platform does not control, which a real provider could enrich with an account holder's details — `provider_evidence.content_ciphertext`'s reasoning, and the level governs handling if the encryption is ever broken, mis-keyed or stripped |
| `payout_evidence` | `content_nonce` | `INTERNAL` | Public-by-design cryptographic material; useless without the key |
| `payout_evidence` | `key_version` | `INTERNAL` | Which key wrote the row — operational metadata for rotation |
| `payout_evidence` | `checksum_sha256` | `RESTRICTED-PII` | The possession oracle: anyone holding a candidate answer can confirm this is what the provider said — `provider_evidence.checksum_sha256`'s reasoning |
| `payout_evidence` | `content_length` | `CONFIDENTIAL` | Weakly identifying alone; a decline's body differs from an acceptance's, so the length leaks the outcome's shape |
| `payout_evidence` | `recorded_at` | `CONFIDENTIAL` | Dates a counterparty's payout traffic |

### `checkout` — *added by `P6-TSK-006`*

| Table | Column | Level | Note |
|---|---|---|---|
| `checkout_session` | `id` | `INTERNAL` | An identifier of a thing — and deliberately **not** the token: naming a session is not the same act as being able to act on one |
| `checkout_session` | `merchant_ref` | `CONFIDENTIAL` | Whose shop this purchase is at. On its own an identifier; beside the rest of the row it says who is selling to whom |
| `checkout_session` | `amount_minor` | `RESTRICTED-FINANCIAL` | An amount — `journal_line.amount_minor`'s classification. What one person is being asked to pay |
| `checkout_session` | `amount_currency` | `INTERNAL` | Part of the monetary shape; meaningless without the amount |
| `checkout_session` | `amount_scale` | `INTERNAL` | As `amount_currency` |
| `checkout_session` | `line_summary` | `RESTRICTED-PII` | **Free text written by a merchant about one person's purchase.** Not a name or an identifier, and at the ceiling anyway: *what somebody bought* is among the most revealing facts a payments platform holds — medication, legal services, a gift to an address — and the content is constrained by no type (`refund.reason`'s reasoning, at a new subject) |
| `checkout_session` | `fee_schedule_version_ref` | `RESTRICTED-FINANCIAL` | `merchant_fee_schedule.fee_schedule_id`'s reasoning: joined to `fee_schedule_version` it says what this merchant pays on this purchase |
| `checkout_session` | `token_hash` | `CONFIDENTIAL` | A SHA-256 digest of 32 random bytes — **not crackable**, so not `RESTRICTED`; classified here for `session.token_hash`'s reason: in a log it is a precise identifier of one live checkout, which is what an attacker reading log archives would want |
| `checkout_session` | `algorithm` | `INTERNAL` | What produced the hash (`INV-IDN-02`) — a fixed vocabulary, and publishing it tells an attacker only what the code already says |
| `checkout_session` | `payment_intent_ref` | `RESTRICTED-FINANCIAL` | An identifier of a money movement — `audit_record.target_id`'s rule: an identifier inherits the sensitivity of what it names |
| `checkout_session` | `status` | `CONFIDENTIAL` | Where one person's purchase got to — and `ABANDONED` beside a merchant ref is commercially sensitive to that merchant |
| `checkout_session` | `expires_at` | `CONFIDENTIAL` | When an offer stops; with `created_at`, how long a customer was given |
| `checkout_session` | `created_at` | `CONFIDENTIAL` | When one person started buying something |
| `checkout_session` | `status_changed_at` | `CONFIDENTIAL` | Dates the purchase's last move |
| `checkout_session_event` | `id` | `INTERNAL` | A server-assigned ordinal |
| `checkout_session_event` | `session_id` | `INTERNAL` | An identifier of a thing |
| `checkout_session_event` | `from_status` | `CONFIDENTIAL` | History is the same facts, older |
| `checkout_session_event` | `to_status` | `CONFIDENTIAL` | As `from_status` |
| `checkout_session_event` | `actor_id` | `RESTRICTED-PII` | The acting identity — `audit_record.actor_id`'s reasoning and its model. Here it may be a CUSTOMER, a merchant or the platform |
| `checkout_session_event` | `actor_type` | `INTERNAL` | Which vocabulary `actor_id` is in |
| `checkout_session_event` | `occurred_at` | `CONFIDENTIAL` | Dates a step in one person's purchase |
| `checkout_order` | `id` | `INTERNAL` | An identifier of a thing |
| `checkout_order` | `session_ref` | `INTERNAL` | An identifier of a thing |
| `checkout_order` | `merchant_ref` | `CONFIDENTIAL` | Whose shop, as on the session |
| `checkout_order` | `amount_minor` | `RESTRICTED-FINANCIAL` | An amount — what actually changed hands |
| `checkout_order` | `amount_currency` | `INTERNAL` | Part of the monetary shape |
| `checkout_order` | `amount_scale` | `INTERNAL` | As `amount_currency` |
| `checkout_order` | `captured_entry_ref` | `RESTRICTED-FINANCIAL` | The journal entry that paid for this order — an identifier of a posting, at the ceiling for `audit_record.target_id`'s reason |
| `checkout_order` | `created_at` | `CONFIDENTIAL` | When one person bought something |

**`line_summary` is the platform's first column whose sensitivity is about *what somebody
bought*** (`P6-TSK-006`). Every prior `RESTRICTED-PII` column holds a name, an identity or a
person's own words about a decision; this one holds a merchant's description of a purchase,
and the reason it sits at the ceiling is that a payments platform's most revealing data is
often not the amount. There is no column here for a shipping address, a customer name or an
email — checkout holds the **offer**, and who the customer is remains `identity`'s and
`party`'s question.

**No column could hold a token** (`INV-IDN-01`): the only token-named column is `token_hash`,
bounded to base64-of-SHA-256's exact shape so a plaintext would not fit quietly, and the
database suite sweeps every text column in every schema for a token it issued.

**The order carries no status** (ADR-0053 §6): an order that exists is paid, and refund
standing is derived from the payment's refund rows at read time rather than stored.

**`payment_fee_pin` is the schema's first row about an individual money movement**
(`P6-TSK-005`). Everything before it in `merchant` is configuration or standing; this is one
payment, one merchant, one price — so almost every column sits at the financial ceiling,
including two identifiers, for the reason the fee tables' own note gives below.

**The fee tables are this schema's first `RESTRICTED-FINANCIAL` rows** (`P6-TSK-004`), and
three of them are not amounts. A rate is a ratio; a schedule identifier is a UUID. They are at
the financial ceiling because of what they *resolve to*: a rate beside a capture is revenue,
and a schedule identifier beside `fee_schedule_version` is what one counterparty pays. That is
`audit_record.target_id`'s rule — an identifier inherits the sensitivity of what it names —
applied where the thing named is a price rather than an account.

**These rows landed in the task that created the columns.** The standing scope since the
Phase 5 → 6 transition found `refund.dispatch_key` unclassified for a whole phase, because
`ColumnClassificationTest` lives in `:platform:databaseTest` — a tier no targeted run covers.

**There is no plaintext column here, and that is the point** (`INV-IDN-01`): the secret exists
for the length of one issuance response and reaches no storage at all — not this table, and
deliberately **not `platform.idempotency_record.response_body`** either, which is why the
issuance claim records the key id alone rather than the response bytes every other keyed
command records (`P6-TSK-002`; the database suite asserts it by sweeping every text column in
every schema for the issued secret).

### `settlement` — the source register and the evidence intake — *added by `P8-TSK-002`*

**The evidence store's rows** (ADR-0066): the seeded source identities, the received file's
metadata, its encrypted bytes, its receipts and history, and the refused deliveries. The one
content column is the chunk ciphertext, `RESTRICTED-PII` at the ceiling of what it decrypts to —
bank statements name people — and every other column is deliberately metadata: the door screen
exists so nothing hotter can enter this schema in the clear (`INV-PAY-02`, `INV-RAIL-03`). Actor
columns hold platform actor identifiers (`audit_record.actor`'s reasoning), and the checksum is
`INTERNAL` — a fingerprint of bytes, recoverable from nothing.

| Table | Column | Level | Why |
|---|---|---|---|
| `source` | `id` | `INTERNAL` | A seeded identifier (`P8-TSK-002`). Generated |
| `source` | `code` | `INTERNAL` | A compiled register name - `simulated-psp.settlement` - a category, never a person |
| `source` | `kind` | `INTERNAL` | Which statement family. Four values, none about a person |
| `source` | `status` | `INTERNAL` | ACTIVE or RETIRED - operational state |
| `source` | `next_sequence` | `INTERNAL` | The next statement sequence acceptance expects - a counter |
| `file` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `file` | `source_id` | `INTERNAL` | The delivering source - an identifier of a thing |
| `file` | `received_via` | `INTERNAL` | UPLOAD, PULL or READMISSION - which door |
| `file` | `status` | `INTERNAL` | The machine's position |
| `file` | `business_date` | `CONFIDENTIAL` | The date the counterparty claims the statement covers - a fact about the platform's commercial traffic, not about a person |
| `file` | `format_id` | `INTERNAL` | Which format family parsed it. Four values |
| `file` | `format_version` | `INTERNAL` | Which frozen version screened it |
| `file` | `content_sha256` | `INTERNAL` | The content address (INV-HIST-02): a fingerprint, recoverable from nothing |
| `file` | `content_length` | `INTERNAL` | A byte count |
| `file` | `line_count` | `INTERNAL` | A record count, by the screen's own walk |
| `file` | `key_version` | `INTERNAL` | Which key wrote the chunks - rotation metadata |
| `file` | `received_by` | `CONFIDENTIAL` | Which person delivered an upload (`audit_record.actor`'s reasoning): an actor identifier, needed to hold the attester distinct (INV-SET-07) |
| `file` | `attested_by` | `CONFIDENTIAL` | Which second person attested - the four-eyes fact itself |
| `file` | `attested_at` | `INTERNAL` | When the attestation was recorded |
| `file` | `readmits_file_id` | `INTERNAL` | The original a readmission recovers - an identifier of a thing |
| `file` | `rejection_code` | `INTERNAL` | Why the parse leg rejected - a closed vocabulary (`P8-TSK-008`) |
| `file` | `rejection_detail` | `INTERNAL` | At most 500 characters of OUR diagnostic - never content, the schema's own bound |
| `file` | `parse_failures` | `INTERNAL` | How often our parser failed - our defect's counter |
| `file` | `next_parse_at` | `INTERNAL` | The backoff's next attempt |
| `file` | `accept_failures` | `INTERNAL` | How often our accept leg failed - our defect's counter, `parse_failures`' twin (settlement `V013`, added 2026-10-02 by the Phase 8 → 9 transition, MI-7) |
| `file` | `next_accept_at` | `INTERNAL` | The accept leg's backoff: no acceptance candidate before it (settlement `V013`, MI-7) |
| `file` | `received_at` | `INTERNAL` | When the door committed it |
| `file` | `status_changed_at` | `INTERNAL` | When the machine last moved |
| `file` | `correlation_id` | `INTERNAL` | The flow's correlation - `audit_record.correlation_id`'s reasoning |
| `file_chunk` | `file_id` | `INTERNAL` | The chunk's file - an identifier of a thing |
| `file_chunk` | `seq` | `INTERNAL` | The chunk's seat |
| `file_chunk` | `ciphertext` | `RESTRICTED-PII` | **The evidence bytes** - counterparty statements; a bank statement names people: classified at the ceiling of what it decrypts to (`dispute_evidence.content_ciphertext`'s reasoning), AES-256-GCM under a key held outside the database, the AAD binding file, source, content and seat |
| `file_chunk` | `nonce` | `INTERNAL` | Public-by-design cryptographic material; useless without the key |
| `file_chunk` | `plaintext_length` | `INTERNAL` | A byte count |
| `file_event` | `seq` | `INTERNAL` | The history's server-assigned order |
| `file_event` | `file_id` | `INTERNAL` | The moved file - an identifier of a thing |
| `file_event` | `from_status` | `INTERNAL` | The edge's origin, NULL at birth |
| `file_event` | `to_status` | `INTERNAL` | The edge's destination |
| `file_event` | `actor` | `CONFIDENTIAL` | Who drove the edge (`audit_record.actor`'s reasoning) |
| `file_event` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `file_event` | `reason` | `CONFIDENTIAL` | The edge's stated reason - bounded, never content, but free prose by a person on a decline or a readmission (`batch_event.reason`'s and `audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by settlement `V012`'s `file_event_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `file_event` | `occurred_at` | `INTERNAL` | When the edge was driven |
| `file_event` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `file_receipt` | `id` | `INTERNAL` | A record identifier. Generated |
| `file_receipt` | `file_id` | `INTERNAL` | The delivered file - an identifier of a thing |
| `file_receipt` | `outcome` | `INTERNAL` | NEW or DUPLICATE - the content address's verdict |
| `file_receipt` | `channel` | `INTERNAL` | Which door the delivery used |
| `file_receipt` | `actor` | `CONFIDENTIAL` | Who delivered (`audit_record.actor`'s reasoning) |
| `file_receipt` | `actor_type` | `INTERNAL` | The actor's kind |
| `file_receipt` | `received_at` | `INTERNAL` | When the delivery arrived |
| `file_receipt` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `pull_permit` | `source_id` | `INTERNAL` | The pulled source - an identifier of a thing (`P8-TSK-021`) |
| `pull_permit` | `business_key` | `INTERNAL` | Which report a pull fetches: an ISO business date or a scheme cycle token - an operational label, no party's data |
| `pull_permit` | `last_attempt_at` | `INTERNAL` | When the herd last attempted - pacing, never correctness |
| `pull_permit` | `attempts` | `INTERNAL` | An attempt count |
| `pull_permit` | `created_at` | `INTERNAL` | A system timestamp |
| `refused_delivery` | `id` | `INTERNAL` | A record identifier. Generated |
| `refused_delivery` | `source_id` | `INTERNAL` | The delivering source - an identifier of a thing |
| `refused_delivery` | `content_sha256` | `INTERNAL` | The refused bytes' fingerprint - what chains the refusal to a re-presentation, recoverable from nothing |
| `refused_delivery` | `content_length` | `INTERNAL` | A byte count |
| `refused_delivery` | `format_id` | `INTERNAL` | Which format's screen refused |
| `refused_delivery` | `format_version` | `INTERNAL` | Which frozen version refused |
| `refused_delivery` | `reason` | `INTERNAL` | Why - a closed two-value vocabulary, never the value found |
| `refused_delivery` | `line_no` | `INTERNAL` | Where the screen found it - a position, not a value |
| `refused_delivery` | `field_name` | `INTERNAL` | The declared field that failed its class - a NAME bounded to 200 characters, never a value (the schema's own CHECK) |
| `refused_delivery` | `channel` | `INTERNAL` | Which door the delivery used |
| `refused_delivery` | `actor` | `CONFIDENTIAL` | Who delivered (`audit_record.actor`'s reasoning) |
| `refused_delivery` | `actor_type` | `INTERNAL` | The actor's kind |
| `refused_delivery` | `refused_at` | `INTERNAL` | When the door refused |
| `refused_delivery` | `correlation_id` | `INTERNAL` | The flow's correlation |

**The canonical batch** — *added by `P8-TSK-008`* (ADR-0066 §§3, 9; ADR-0065): what the parse
leg distils from the encrypted evidence, deliberately cooler than its file — types,
directions, amounts, dates, digests and typed references. The amounts are
`RESTRICTED-FINANCIAL` (a person's or counterparty's transaction value); every reference a
counterparty will quote is `CONFIDENTIAL` (Phase 8's match key, `expectation.operation_ref`'s
reasoning); free text stays inside the encrypted file and has no column here.

| Table | Column | Level | Why |
|---|---|---|---|
| `batch` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `batch` | `file_id` | `INTERNAL` | The file it distils - an identifier of a thing |
| `batch` | `source_id` | `INTERNAL` | The delivering source - an identifier of a thing |
| `batch` | `external_batch_ref` | `CONFIDENTIAL` | The counterparty's batch identity - a reference it will quote (the live key's member) |
| `batch` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape with no amount beside it |
| `batch` | `status` | `INTERNAL` | The machine's position |
| `batch` | `business_date` | `CONFIDENTIAL` | The day the batch covers - `file.business_date`'s reasoning |
| `batch` | `format_id` | `INTERNAL` | Which format family parsed it |
| `batch` | `format_version` | `INTERNAL` | Which frozen version parsed it |
| `batch` | `line_count` | `INTERNAL` | A record count - canonical lines, the split's included |
| `batch` | `declared_line_count` | `INTERNAL` | The trailer's own record count |
| `batch` | `net_minor` | `RESTRICTED-FINANCIAL` | **The trailer's declared net** - what the counterparty will remit |
| `batch` | `net_scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `batch` | `remittance_reference` | `CONFIDENTIAL` | What hop 2 attributes the bank line by (ADR-0065) - a reference both sides quote; NULL for a bank statement (`P8-TSK-016`), whose lines carry theirs |
| `batch` | `created_at` | `INTERNAL` | When the parse committed it |
| `batch` | `status_changed_at` | `INTERNAL` | When the machine last moved |
| `batch` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `batch` | `source_sequence` | `INTERNAL` | The gapless statement sequence (`P8-TSK-009`) - a counter |
| `batch` | `accepted_on` | `CONFIDENTIAL` | The UTC day acceptance recognised it - the recognition's posting date, a fact about commercial traffic |
| `batch` | `journal_entry_id` | `INTERNAL` | The recognition entry - an identifier of a thing; NULL exactly when the posting was honestly omitted |
| `batch` | `posting_omitted` | `INTERNAL` | Whether a zero fee omitted the entry - the honesty flag, never silent |
| `batch` | `statement_sequence` | `INTERNAL` | A bank statement's place in its account's chain (`P8-TSK-016`, INV-SET-06) - a counter; NULL for a report |
| `batch` | `opening_minor` | `RESTRICTED-FINANCIAL` | **A bank statement's opening balance** - the platform's cash as the bank states it; NULL for a report |
| `batch` | `closing_minor` | `RESTRICTED-FINANCIAL` | **A bank statement's closing balance** - what CASH_AT_BANK equals at the head of an unbroken chain; NULL for a report |
| `batch_event` | `seq` | `INTERNAL` | The history's server-assigned order |
| `batch_event` | `batch_id` | `INTERNAL` | The moved batch - an identifier of a thing |
| `batch_event` | `from_status` | `INTERNAL` | The edge's origin, NULL at birth |
| `batch_event` | `to_status` | `INTERNAL` | The edge's destination |
| `batch_event` | `actor` | `CONFIDENTIAL` | Who drove the edge (`audit_record.actor`'s reasoning) |
| `batch_event` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `batch_event` | `reason` | `CONFIDENTIAL` | The decline's stated reason - free prose by a person (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by settlement `V012`'s `batch_event_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `batch_event` | `occurred_at` | `INTERNAL` | When the edge was driven |
| `batch_event` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `batch_total` | `batch_id` | `INTERNAL` | The batch - an identifier of a thing |
| `batch_total` | `line_type` | `INTERNAL` | A closed vocabulary member |
| `batch_total` | `direction` | `INTERNAL` | INBOUND or OUTBOUND |
| `batch_total` | `line_count` | `INTERNAL` | A count |
| `batch_total` | `amount_minor` | `RESTRICTED-FINANCIAL` | **A folded settlement amount** - the attester's control total |
| `batch_total` | `amount_scale` | `INTERNAL` | The monetary shape's scale |
| `line` | `id` | `INTERNAL` | A record identifier. Generated |
| `line` | `batch_id` | `INTERNAL` | The line's batch - an identifier of a thing |
| `line` | `file_id` | `INTERNAL` | The line's file - an identifier of a thing |
| `line` | `line_no` | `INTERNAL` | The line's seat in its file |
| `line` | `line_type` | `INTERNAL` | The platform's closed vocabulary (INV-PAY-03) - never the provider's word |
| `line` | `direction` | `INTERNAL` | INBOUND or OUTBOUND |
| `line` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The reported settlement amount** - a person's or counterparty's transaction |
| `line` | `amount_scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `line` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `line` | `business_date` | `CONFIDENTIAL` | The transaction's day - `file.business_date`'s reasoning |
| `line` | `settlement_date` | `CONFIDENTIAL` | When the counterparty says it settles |
| `line` | `value_date` | `CONFIDENTIAL` | The value day the counterparty states |
| `line` | `raw_record_sha256` | `INTERNAL` | The delivered record's fingerprint - recoverable from nothing |
| `line` | `canonical_fingerprint` | `INTERNAL` | The canonical identity digest - deliberately not unique, so a duplicate survives to matching |
| `line` | `attributed_source_id` | `INTERNAL` | A bank credit's or debit's attributed source (`P8-TSK-016`, ADR-0065 section 3) - an identifier of a thing; NULL for a report line, a bank fee or an unattributed line |
| `line_reference` | `line_id` | `INTERNAL` | The reference's line - an identifier of a thing |
| `line_reference` | `kind` | `INTERNAL` | A closed vocabulary member |
| `line_reference` | `value` | `CONFIDENTIAL` | **A typed reference a counterparty quotes** - the match key (`expectation.operation_ref`'s reasoning); bank-identifier and alias shapes refused by CHECK (INV-RAIL-03) |
| `ingestion_error` | `file_id` | `INTERNAL` | The rejected file - an identifier of a thing |
| `ingestion_error` | `seq` | `INTERNAL` | The error's order, bounded at 100 |
| `ingestion_error` | `line_no` | `INTERNAL` | Where - a position, not a value |
| `ingestion_error` | `error_code` | `INTERNAL` | Why - a closed vocabulary, never the value found |
| `ingestion_error` | `field_name` | `INTERNAL` | The field that failed - a NAME bounded to 200 characters (`refused_delivery.field_name`'s reasoning) |

**Configuration, not a column** (`P8-TSK-016`): `finapp.settlement.bank.account-reference.{EUR,GBP,USD}`
is the bank's OPAQUE reference for the platform's settlement account per currency — the one
value a statement's account record must equal to be ours. It is **`CONFIDENTIAL`**: deployment
configuration, never persisted, never logged, never echoed in a defect, an exception message or
a `toString` (the adapter names only the currency). It is a reference the bank issues, shaped
`SIMBANK-[A-Z]{3}-[0-9]{2,8}` in v1 and validated at construction — never an IBAN or an account
number, which the platform does not hold for its own bank account (`INV-RAIL-03`).

### `reconciliation` — the expectation register and rule set v1 — *added by `P8-TSK-004`*

**The internal side of the position proof** (ADR-0067, ADR-0068): versioned matching
configuration — magnitudes of policy, nobody's money — and the expectation rows that copy each
clearing journal line. The expectation's money triple is `RESTRICTED-FINANCIAL` (an amount of a
person's or counterparty's transaction), every typed reference a counterparty will quote is
`CONFIDENTIAL` (Phase 8's match key), actor columns are `CONFIDENTIAL`
(`audit_record.actor`'s reasoning), and everything else is deliberately identifiers, enums,
counters and dates of things.

| Table | Column | Level | Why |
|---|---|---|---|
| `rule_set` | `id` | `INTERNAL` | A version identifier. Seeded/generated |
| `rule_set` | `source_id` | `INTERNAL` | The source's seeded identifier, copied (no cross-schema FK) |
| `rule_set` | `version` | `INTERNAL` | A counter |
| `rule_set` | `status` | `INTERNAL` | An enumeration member |
| `rule_set` | `funding_lag_days` | `INTERNAL` | A policy magnitude — days, nobody's money |
| `rule_set` | `gain_min_age_days` | `INTERNAL` | A policy magnitude (owner decision O5) |
| `rule_set` | `effective_from` | `INTERNAL` | A property of the artefact |
| `rule_set` | `proposed_by` | `CONFIDENTIAL` | Who proposed the version (`audit_record.actor`'s reasoning; v1's is the migration) |
| `rule_set` | `decided_by` | `CONFIDENTIAL` | Who activated it — the four-eyes fact once `P8-TSK-022` produces it |
| `rule_set` | `reason` | `CONFIDENTIAL` | Free prose by a person about a decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by reconciliation `V019`'s `rule_set_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `rule_set` | `created_at` | `INTERNAL` | A property of the artefact |
| `rule_set` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `rule_set` | `decided_at` | `INTERNAL` | When the version was activated or rejected (`P8-TSK-022`, `V012`); the seed's is its creation |
| `rule_set_event` | `seq` | `INTERNAL` | The history's server-assigned order |
| `rule_set_event` | `rule_set_id` | `INTERNAL` | The moved version - an identifier of a thing |
| `rule_set_event` | `from_status` | `INTERNAL` | The edge's origin, NULL at the proposal |
| `rule_set_event` | `to_status` | `INTERNAL` | The edge's destination - a closed vocabulary |
| `rule_set_event` | `actor` | `CONFIDENTIAL` | Who proposed, activated, retired by activation or rejected (`audit_record.actor`'s reasoning) |
| `rule_set_event` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `rule_set_event` | `reason` | `CONFIDENTIAL` | A controller's prose about a policy decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by reconciliation `V019`'s `rule_set_event_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `rule_set_event` | `occurred_at` | `INTERNAL` | When the edge was driven |
| `rule_set_event` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `rule_set_lag` | `rule_set_id` | `INTERNAL` | The owning version — an identifier of a thing |
| `rule_set_lag` | `expectation_kind` | `INTERNAL` | An enumeration member |
| `rule_set_lag` | `lag_days` | `INTERNAL` | A policy magnitude |
| `rule` | `rule_set_id` | `INTERNAL` | The owning version |
| `rule` | `priority` | `INTERNAL` | A position in a list |
| `rule` | `line_type` | `INTERNAL` | Canonical vocabulary — a line family, never a value |
| `rule` | `key_kind` | `INTERNAL` | An enumeration member — which reference KIND is consulted, never a reference |
| `rule` | `expectation_kind` | `INTERNAL` | An enumeration member |
| `rule` | `cardinality` | `INTERNAL` | An enumeration member |
| `rule` | `operation_anchored` | `INTERNAL` | A boolean of policy (the transition's A4) |
| `rule` | `grace_hours` | `INTERNAL` | A policy magnitude |
| `tolerance` | `rule_set_id` | `INTERNAL` | The owning version |
| `tolerance` | `comparison` | `INTERNAL` | An enumeration of exactly three — no amount member exists (`INV-REC-08`) |
| `tolerance` | `currency` | `INTERNAL` | An enumeration; part of a policy shape with no transaction beside it |
| `tolerance` | `absolute_minor` | `INTERNAL` | A policy bound on FEE comparison — two minor units of tolerance, nobody's money |
| `tolerance` | `days` | `INTERNAL` | A policy magnitude |
| `provider_fee_schedule` | `rule_set_id` | `INTERNAL` | The owning version |
| `provider_fee_schedule` | `line_type` | `INTERNAL` | An enumeration member |
| `provider_fee_schedule` | `currency` | `INTERNAL` | An enumeration |
| `provider_fee_schedule` | `rate` | `CONFIDENTIAL` | A counterparty's commercial terms — pinned so a `FEE_MISMATCH` is judged against what was agreed, and not for publication |
| `provider_fee_schedule` | `fixed_minor` | `CONFIDENTIAL` | The same commercial terms' fixed part |
| `provider_fee_schedule` | `scale` | `INTERNAL` | Part of the monetary shape |
| `provider_fee_schedule` | `rounding_policy` | `INTERNAL` | An enumeration member (`INV-MON-03`) |
| `severity_threshold` | `rule_set_id` | `INTERNAL` | The owning version |
| `severity_threshold` | `currency` | `INTERNAL` | An enumeration |
| `severity_threshold` | `high_value_minor` | `INTERNAL` | A policy magnitude (owner decision O7) — what counts as loud, nobody's money |
| `expectation` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `expectation` | `kind` | `INTERNAL` | An enumeration member |
| `expectation` | `operation_ref` | `CONFIDENTIAL` | The operation the completion names — an identifier that pairs a payment to its settlement (Phase 8's match key) |
| `expectation` | `posting_key` | `CONFIDENTIAL` | The completion's posting key — the same pairing in the ledger's vocabulary |
| `expectation` | `source_id` | `INTERNAL` | The source's seeded identifier, copied |
| `expectation` | `position_purpose` | `INTERNAL` | A chart purpose name — a category of account, never an account of a person |
| `expectation` | `ledger_account_id` | `INTERNAL` | The posted clearing account — an operational account's identifier, copied |
| `expectation` | `direction` | `INTERNAL` | An enumeration of two |
| `expectation` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The expected settlement amount** — the clearing journal line's copy |
| `expectation` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `expectation` | `scale` | `INTERNAL` | Part of the monetary shape |
| `expectation` | `journal_entry_id` | `INTERNAL` | The entry's identifier, copied — an identifier of a thing |
| `expectation` | `posting_date` | `INTERNAL` | The entry's own date, copied |
| `expectation` | `settlement_cycle` | `INTERNAL` | The scheme's cycle identifier — a bucket name (`payment_attempt.settlement_cycle`'s reasoning; an attribute, never a key) |
| `expectation` | `expected_by` | `INTERNAL` | A derived policy date |
| `expectation` | `rule_set_id` | `INTERNAL` | The deciding version, pinned (`INV-HIST-04`) |
| `expectation` | `status` | `INTERNAL` | The machine's state |
| `expectation` | `allocated_minor` | `RESTRICTED-FINANCIAL` | How much of the amount evidence has discharged — the amount's own level |
| `expectation` | `resolved_minor` | `RESTRICTED-FINANCIAL` | How much a resolution took — the amount's own level |
| `expectation` | `overdue_since` | `INTERNAL` | A one-way operational fact |
| `expectation` | `opened_at` | `INTERNAL` | A property of the row |
| `expectation` | `status_changed_at` | `INTERNAL` | A property of the row |
| `expectation` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `expectation_event` | `seq` | `INTERNAL` | The history's position |
| `expectation_event` | `expectation_id` | `INTERNAL` | The owning row — NULL exactly for an alias collision |
| `expectation_event` | `event_type` | `INTERNAL` | An enumeration member |
| `expectation_event` | `detail` | `CONFIDENTIAL` | Names the colliding kind and reference value — the match key's own level, kept in-table and never in a log or an event |
| `expectation_event` | `actor` | `CONFIDENTIAL` | Who drove it (`audit_record.actor`'s reasoning) |
| `expectation_event` | `actor_type` | `INTERNAL` | An enumeration member |
| `expectation_event` | `occurred_at` | `INTERNAL` | A property of the row |
| `expectation_event` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `expectation_key` | `source_id` | `INTERNAL` | The scope of the unique — a seeded identifier |
| `expectation_key` | `key_kind` | `INTERNAL` | An enumeration member |
| `expectation_key` | `key_value` | `CONFIDENTIAL` | **A typed reference a counterparty will quote** — Phase 8's match key, shape-checked vocabulary and never free text |
| `expectation_key` | `expectation_id` | `INTERNAL` | The owning row |
| `expectation_key` | `released_by_resolution_id` | `INTERNAL` | The repudiation that released a closed remittance's reference (the Phase 8 -> 9 transition, `V017`) - an identifier of a thing |
| `reference_alias` | `source_id` | `INTERNAL` | The scope of the unique |
| `reference_alias` | `key_kind` | `INTERNAL` | An enumeration member |
| `reference_alias` | `key_value` | `CONFIDENTIAL` | The foreign reference (the ARN) — the match key's level |
| `reference_alias` | `anchor_kind` | `INTERNAL` | An enumeration member |
| `reference_alias` | `anchor_value` | `CONFIDENTIAL` | The anchor it resolves to — an operation identifier pairing |
| `reference_alias` | `registered_at` | `INTERNAL` | A property of the row |
| `reference_alias` | `correlation_id` | `INTERNAL` | The flow's correlation |

**The runs and the external items** — *added by `P8-TSK-009`* (§5.3, §5.4, ADR-0064): the accepted batch's unit of matching work and its working copies. The item's money triple is `RESTRICTED-FINANCIAL` (a person's or counterparty's transaction), its keys `CONFIDENTIAL` (the match keys), a reprocess's reason `CONFIDENTIAL` prose; everything else identifiers, enums, counters and dates of things.

| Table | Column | Level | Why |
|---|---|---|---|
| `reconciliation_batch` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `reconciliation_batch` | `source_id` | `INTERNAL` | The source - an identifier of a thing |
| `reconciliation_batch` | `batch_id` | `INTERNAL` | The accepted batch - an identifier of a thing |
| `reconciliation_batch` | `kind` | `INTERNAL` | BATCH or REPROCESS - a closed vocabulary |
| `reconciliation_batch` | `rule_set_id` | `INTERNAL` | The pinned deciding version (INV-HIST-04) |
| `reconciliation_batch` | `business_date` | `CONFIDENTIAL` | The day the batch covers - `file.business_date`'s reasoning |
| `reconciliation_batch` | `source_sequence` | `INTERNAL` | The acceptance's gapless counter, mirrored |
| `reconciliation_batch` | `status` | `INTERNAL` | The machine's position |
| `reconciliation_batch` | `item_count` | `INTERNAL` | A count |
| `reconciliation_batch` | `cursor` | `INTERNAL` | The chunked walk's resume point - forward-only |
| `reconciliation_batch` | `failures` | `INTERNAL` | Consecutive chunk failures - the BLOCKED gate's counter |
| `reconciliation_batch` | `requested_by` | `CONFIDENTIAL` | Who asked for a reprocess (`audit_record.actor`'s reasoning) |
| `reconciliation_batch` | `reason` | `CONFIDENTIAL` | A reprocess's stated reason - free prose by a person. Screened for card-number and account-identifier shapes at the domain and by reconciliation `V019`'s `reconciliation_batch_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `reconciliation_batch` | `created_at` | `INTERNAL` | When the acceptance birthed it |
| `reconciliation_batch` | `status_changed_at` | `INTERNAL` | When the machine last moved |
| `reconciliation_batch` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `reconciliation_batch` | `settlement_cycle` | `INTERNAL` | A scheme report's cycle token, frozen at birth - `expectation.settlement_cycle`'s reasoning, a bucket name (`P8-TSK-017`) |
| `reconciliation_batch_event` | `seq` | `INTERNAL` | The history's server-assigned order |
| `reconciliation_batch_event` | `run_id` | `INTERNAL` | The moved run - an identifier of a thing |
| `reconciliation_batch_event` | `from_status` | `INTERNAL` | The edge's origin, NULL at birth |
| `reconciliation_batch_event` | `to_status` | `INTERNAL` | The edge's destination |
| `reconciliation_batch_event` | `actor` | `CONFIDENTIAL` | Who drove the edge (`audit_record.actor`'s reasoning) |
| `reconciliation_batch_event` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `reconciliation_batch_event` | `reason` | `CONFIDENTIAL` | The edge's stated reason - a requeue's is a person's prose. Screened for card-number and account-identifier shapes at the domain and by reconciliation `V019`'s `reconciliation_batch_event_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `reconciliation_batch_event` | `occurred_at` | `INTERNAL` | When the edge was driven |
| `reconciliation_batch_event` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `external_item` | `id` | `INTERNAL` | A record identifier. Generated |
| `external_item` | `run_id` | `INTERNAL` | The item's run - an identifier of a thing |
| `external_item` | `source_id` | `INTERNAL` | The source - an identifier of a thing |
| `external_item` | `settlement_line_id` | `INTERNAL` | The copied line - an identifier of a thing |
| `external_item` | `line_no` | `INTERNAL` | The line's seat in its file |
| `external_item` | `line_type` | `INTERNAL` | The mirrored canonical vocabulary (INV-PAY-03) |
| `external_item` | `direction` | `INTERNAL` | INBOUND or OUTBOUND |
| `external_item` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The reported amount** - the copied line's |
| `external_item` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `external_item` | `scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `external_item` | `position_purpose` | `INTERNAL` | Which position the item claims - a category; NULL for an unattributed bank line or a bank fee (`P8-TSK-016`) |
| `external_item` | `business_date` | `CONFIDENTIAL` | The transaction's day - the copied line's |
| `external_item` | `settlement_date` | `CONFIDENTIAL` | When the counterparty says it settles |
| `external_item` | `value_date` | `CONFIDENTIAL` | The value day the counterparty states |
| `external_item` | `canonical_fingerprint` | `INTERNAL` | The copied identity digest - recoverable from nothing |
| `external_item` | `allocated_minor` | `RESTRICTED-FINANCIAL` | **The allocated part** - a disposition of the amount |
| `external_item` | `parked_minor` | `RESTRICTED-FINANCIAL` | **The parked part** - a disposition of the amount |
| `external_item` | `offset_minor` | `RESTRICTED-FINANCIAL` | **The offset part** - a disposition of the amount |
| `external_item` | `status` | `INTERNAL` | The machine's position |
| `external_item` | `grace_until` | `INTERNAL` | The pinned grace deadline - a property of the row |
| `external_item` | `created_at` | `INTERNAL` | When the acceptance birthed it |
| `external_item` | `status_changed_at` | `INTERNAL` | When the machine last moved |
| `external_item` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `external_item` | `attributed_source_id` | `INTERNAL` | A bank item's attributed source - its key scope (`P8-TSK-016`) - an identifier of a thing |
| `external_item` | `learned_cycle` | `INTERNAL` | The cycle a return learned from the report that allocated it - `expectation.settlement_cycle`'s reasoning (`P8-TSK-017`) |
| `external_item_event` | `seq` | `INTERNAL` | The history's server-assigned order |
| `external_item_event` | `item_id` | `INTERNAL` | The moved item - an identifier of a thing |
| `external_item_event` | `from_status` | `INTERNAL` | The edge's origin, NULL at birth |
| `external_item_event` | `to_status` | `INTERNAL` | The edge's destination |
| `external_item_event` | `actor` | `CONFIDENTIAL` | Who drove the edge (`audit_record.actor`'s reasoning) |
| `external_item_event` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `external_item_event` | `reason` | `CONFIDENTIAL` | The edge's stated reason, where one is. Screened for card-number and account-identifier shapes at the domain and by reconciliation `V019`'s `external_item_event_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `external_item_event` | `occurred_at` | `INTERNAL` | When the edge was driven |
| `external_item_event` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `external_item_key` | `item_id` | `INTERNAL` | The keyed item - an identifier of a thing |
| `external_item_key` | `source_id` | `INTERNAL` | The source, denormalised for the match index |
| `external_item_key` | `key_kind` | `INTERNAL` | The mirrored reference vocabulary - a closed list |
| `external_item_key` | `key_value` | `CONFIDENTIAL` | **A reference the counterparty quotes** - the match key (`expectation.operation_ref`'s reasoning) |
| `break` | `id` | `INTERNAL` | A record identifier. Generated |
| `break` | `type` | `INTERNAL` | One of fourteen - a closed vocabulary |
| `break` | `cause` | `INTERNAL` | Which detector raised it - a closed vocabulary |
| `break` | `status` | `INTERNAL` | The machine's position |
| `break` | `severity` | `INTERNAL` | The computed grade - a closed vocabulary |
| `break` | `source_id` | `INTERNAL` | The source - an identifier of a thing |
| `break` | `rule_set_id` | `INTERNAL` | The pinned grading version (INV-HIST-04) |
| `break` | `expectation_id` | `INTERNAL` | A subject - an identifier of a thing |
| `break` | `external_item_id` | `INTERNAL` | A subject - an identifier of a thing |
| `break` | `suspense_item_id` | `INTERNAL` | A subject - an identifier of a thing |
| `break` | `run_id` | `INTERNAL` | A subject - an identifier of a thing |
| `break` | `decision_id` | `INTERNAL` | A subject - an identifier of a thing |
| `break` | `value_at_issue_minor` | `RESTRICTED-FINANCIAL` | **What is at issue** - frozen at raise, never in a metric, event or log |
| `break` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `break` | `scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `break` | `internal_classification` | `INTERNAL` | The lookup's frozen verdict - a closed vocabulary |
| `break` | `internal_operation_ref` | `CONFIDENTIAL` | The operation the lookup named - an operation reference |
| `break` | `internal_state` | `INTERNAL` | The named operation's state - an enumerated name |
| `break` | `assignee` | `CONFIDENTIAL` | Who investigates (`audit_record.actor`'s reasoning) |
| `break` | `residual_version` | `INTERNAL` | The staleness counter (ADR-0071) |
| `break` | `follows_break_id` | `INTERNAL` | The predecessor - an identifier of a thing |
| `break` | `raised_at` | `INTERNAL` | When the platform detected it |
| `break` | `resolved_at` | `INTERNAL` | When it closed, exactly with RESOLVED |
| `break` | `status_changed_at` | `INTERNAL` | When the machine last moved |
| `break` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `break_event` | `seq` | `INTERNAL` | The history's server-assigned order |
| `break_event` | `break_id` | `INTERNAL` | The moved break - an identifier of a thing |
| `break_event` | `event_type` | `INTERNAL` | RAISED and its siblings - a closed vocabulary |
| `break_event` | `actor` | `CONFIDENTIAL` | Who drove the edge (`audit_record.actor`'s reasoning) |
| `break_event` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `break_event` | `reason` | `CONFIDENTIAL` | The edge's stated reason - a person's prose where one is. Screened for card-number and account-identifier shapes at the domain and by reconciliation `V019`'s `break_event_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `break_event` | `detail` | `INTERNAL` | Identifiers and enumerated names only (INV-AUD-02) |
| `break_event` | `occurred_at` | `INTERNAL` | When the edge was driven |
| `break_event` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `break_event` | `resolution_id` | `INTERNAL` | The resolution a RESOLVED edge names - an identifier of a thing (V015, P8-DOC-001) |
| `break_note` | `id` | `INTERNAL` | A record identifier. Generated |
| `break_note` | `break_id` | `INTERNAL` | The noted break - an identifier of a thing |
| `break_note` | `body` | `CONFIDENTIAL` | **An investigator's own words** - never logged, evented or audited; screened at the database for PAN and IBAN shapes (INV-PAY-02, INV-RAIL-03). Screened for card-number and account-identifier shapes at the domain and by reconciliation `V019`'s re-added `break_note_no_card_number` and `break_note_no_account_shape` over the twin (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `break_note` | `author` | `CONFIDENTIAL` | Who wrote it (`audit_record.actor`'s reasoning) |
| `break_note` | `author_type` | `INTERNAL` | The author's kind - a closed vocabulary |
| `break_note` | `added_at` | `INTERNAL` | When it was appended |
| `break_note` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `break_evidence_link` | `id` | `INTERNAL` | A record identifier. Generated |
| `break_evidence_link` | `break_id` | `INTERNAL` | The linked break - an identifier of a thing |
| `break_evidence_link` | `target_kind` | `INTERNAL` | What the identifier names - a closed vocabulary |
| `break_evidence_link` | `target_ref` | `CONFIDENTIAL` | The named identifier - screened like a reference, never content |
| `break_evidence_link` | `added_by` | `CONFIDENTIAL` | Who linked it (`audit_record.actor`'s reasoning) |
| `break_evidence_link` | `added_by_type` | `INTERNAL` | The linker's kind - a closed vocabulary |
| `break_evidence_link` | `added_at` | `INTERNAL` | When it was appended |
| `break_evidence_link` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `park` | `id` | `INTERNAL` | A record identifier. Generated |
| `park` | `source_id` | `INTERNAL` | The source - an identifier of a thing |
| `park` | `kind` | `INTERNAL` | PARK or UNPARK - a closed vocabulary |
| `park` | `position_account_id` | `INTERNAL` | The position the entry touched - an identifier of a thing |
| `park` | `currency` | `INTERNAL` | An enumeration |
| `park` | `decided_on` | `CONFIDENTIAL` | The deciding day - stamped once, the entry's posting date |
| `park` | `value_date` | `CONFIDENTIAL` | The items' settlement day - the entry's value date |
| `park` | `journal_entry_id` | `INTERNAL` | The posted entry - an identifier of a thing |
| `park` | `actor` | `CONFIDENTIAL` | Who decided (`audit_record.actor`'s reasoning) |
| `park` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `park` | `created_at` | `INTERNAL` | When it was recorded |
| `park` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `suspense_item` | `id` | `INTERNAL` | A record identifier. Generated |
| `suspense_item` | `break_id` | `INTERNAL` | The one owning break (INV-REC-09) - an identifier of a thing |
| `suspense_item` | `external_item_id` | `INTERNAL` | The parked item - an identifier of a thing |
| `suspense_item` | `origin` | `INTERNAL` | Which opener - a closed vocabulary |
| `suspense_item` | `origin_ref` | `CONFIDENTIAL` | The origin's own row - an identifier, conservatively a reference |
| `suspense_item` | `side` | `INTERNAL` | CREDIT or DEBIT - fixed at birth, never netted |
| `suspense_item` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The parked value** - never in a metric, event or log |
| `suspense_item` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `suspense_item` | `scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `suspense_item` | `released_minor` | `RESTRICTED-FINANCIAL` | **The released part** - a disposition of the amount |
| `suspense_item` | `status` | `INTERNAL` | The machine's position - the amounts' mirror |
| `suspense_item` | `opened_on` | `CONFIDENTIAL` | The day the value entered suspense - the age's anchor, from stored data |
| `suspense_item` | `entry_id` | `INTERNAL` | The entry that carried the value in - an identifier of a thing |
| `suspense_item` | `park_id` | `INTERNAL` | The park that opened it - an identifier of a thing |
| `suspense_item` | `position_account_id` | `INTERNAL` | The parked position - the unpark's frozen inverse fact |
| `suspense_item` | `status_changed_at` | `INTERNAL` | When the machine last moved |
| `suspense_item` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `suspense_release` | `seq` | `INTERNAL` | The history's server-assigned order |
| `suspense_release` | `item_id` | `INTERNAL` | The released item - an identifier of a thing |
| `suspense_release` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The released value** - one row per release |
| `suspense_release` | `park_id` | `INTERNAL` | The unpark that took it, where one did - an identifier of a thing |
| `suspense_release` | `cause` | `INTERNAL` | Which path took the value - a closed vocabulary |
| `suspense_release` | `cause_ref` | `CONFIDENTIAL` | The decision, resolution or park behind it - an identifier, conservatively a reference |
| `suspense_release` | `actor` | `CONFIDENTIAL` | Who released (`audit_record.actor`'s reasoning) |
| `suspense_release` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `suspense_release` | `released_at` | `INTERNAL` | When the release was taken |
| `suspense_release` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `match_decision` | `id` | `INTERNAL` | A record identifier. Generated |
| `match_decision` | `external_item_id` | `INTERNAL` | The decided item - an identifier of a thing |
| `match_decision` | `run_id` | `INTERNAL` | The deciding run, where one did - an identifier of a thing |
| `match_decision` | `origin` | `INTERNAL` | RUN and its siblings - a closed vocabulary |
| `match_decision` | `rule_set_id` | `INTERNAL` | The pinned deciding version (INV-HIST-04) |
| `match_decision` | `rule_priority` | `INTERNAL` | Which rule fired - a small number |
| `match_decision` | `strategy` | `INTERNAL` | The rule's cardinality - a closed vocabulary |
| `match_decision` | `matched_key_kind` | `INTERNAL` | Which key reached the candidate - a closed vocabulary |
| `match_decision` | `outcome` | `INTERNAL` | MATCHED and its siblings - a closed vocabulary |
| `match_decision` | `claimant_rank` | `INTERNAL` | This item's rank in claimant order - a count |
| `match_decision` | `claimant_count` | `INTERNAL` | The live candidates seen - a count |
| `match_decision` | `date_deviation_days` | `INTERNAL` | Stored dates' difference - a count of days, never an amount |
| `match_decision` | `timing_tolerance_days` | `INTERNAL` | The applied window, frozen (INV-REC-04) |
| `match_decision` | `fee_expected_minor` | `RESTRICTED-FINANCIAL` | **The fee check's expected value** (`P8-TSK-012`'s writer) - never in a metric, event or log |
| `match_decision` | `fee_reported_minor` | `RESTRICTED-FINANCIAL` | **The fee the counterparty reported** - the check's other side |
| `match_decision` | `fee_tolerance_minor` | `RESTRICTED-FINANCIAL` | **The applied fee bound**, frozen - an amount, conservatively |
| `match_decision` | `decided_by` | `CONFIDENTIAL` | Who decided (`audit_record.actor`'s reasoning; the run leg is the system) |
| `match_decision` | `decided_by_type` | `INTERNAL` | The decider's kind - a closed vocabulary |
| `match_decision` | `decided_at` | `INTERNAL` | When it was decided |
| `match_decision` | `decided_on` | `CONFIDENTIAL` | The deciding day - a park's posting date where one follows |
| `match_decision` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `match_decision` | `verdict` | `INTERNAL` | What the decision's pure function concluded (`P8-TSK-022`, `V012`) - a closed vocabulary |
| `match_decision` | `judged_status` | `INTERNAL` | The item state the decision judged - a closed vocabulary |
| `match_decision` | `judged_minor` | `RESTRICTED-FINANCIAL` | **The item value the decision judged** - a parked item's remainder, else its amount; the replay's input |
| `match_decision` | `fingerprint_seen_earlier` | `INTERNAL` | The matching engine's duplicate input - a flag |
| `match_decision` | `group_membership_complete` | `INTERNAL` | The value-date group's membership input - a flag |
| `match_decision` | `fee_gross_minor` | `RESTRICTED-FINANCIAL` | **The gross a fee was priced on** - NULL when no original was reached; the replay's input |
| `match_parked_original` | `decision_id` | `INTERNAL` | The judging correction (`P8-TSK-022`, `V012`) - an identifier of a thing |
| `match_parked_original` | `ordinal` | `INTERNAL` | The judged order - a counter |
| `match_parked_original` | `original_item_id` | `INTERNAL` | The original item - an identifier of a thing |
| `match_parked_original` | `suspense_item_id` | `INTERNAL` | Its parked value's record - an identifier of a thing |
| `match_parked_original` | `break_id` | `INTERNAL` | The owning break - an identifier of a thing |
| `match_parked_original` | `side` | `INTERNAL` | DEBIT or CREDIT - a closed vocabulary |
| `match_parked_original` | `remainder_minor` | `RESTRICTED-FINANCIAL` | **The parked value the correction saw** - frozen because the live row moves on |
| `match_parked_original` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `match_parked_original` | `scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `match_reach` | `decision_id` | `INTERNAL` | The late leg's examination that judged the reach (the Phase 8 -> 9 transition, `V016`) - an identifier of a thing |
| `match_reach` | `expectation_id` | `INTERNAL` | An expectation that examination reached and consumed - an identifier of a thing |
| `run_replay` | `id` | `INTERNAL` | A record identifier (`P8-TSK-022`, `V012`). Generated |
| `run_replay` | `run_id` | `INTERNAL` | The replayed run - an identifier of a thing |
| `run_replay` | `requested_by` | `CONFIDENTIAL` | Who replayed (`audit_record.actor`'s reasoning) |
| `run_replay` | `requested_by_type` | `INTERNAL` | The requester's kind - a closed vocabulary |
| `run_replay` | `verdict` | `INTERNAL` | IDENTICAL or DIVERGED - a closed vocabulary |
| `run_replay` | `replayed` | `INTERNAL` | Decisions replayed - a count |
| `run_replay` | `not_replayed` | `INTERNAL` | Decisions with nothing pure to re-run - a count |
| `run_replay` | `divergences` | `INTERNAL` | Decisions that diverged - a count |
| `run_replay` | `pending_rematch` | `INTERNAL` | Items whose rematch is merely pending - a count |
| `run_replay` | `first_divergent_decision` | `INTERNAL` | The first diverged decision - an identifier of a thing |
| `run_replay` | `at` | `INTERNAL` | When the verdict was appended |
| `run_replay` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `match_candidate` | `decision_id` | `INTERNAL` | The seeing decision - an identifier of a thing |
| `match_candidate` | `expectation_id` | `INTERNAL` | The seen candidate - an identifier of a thing |
| `match_candidate` | `key_kind` | `INTERNAL` | How it was reached - a closed vocabulary; NULL for a value-date group's candidate (`P8-TSK-016`) |
| `match_candidate` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The candidate's amount as seen** - the snapshot replay reads (INV-REC-04) |
| `match_candidate` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `match_candidate` | `scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `match_candidate` | `direction` | `INTERNAL` | INBOUND or OUTBOUND - a closed vocabulary |
| `match_candidate` | `remainder_before_minor` | `RESTRICTED-FINANCIAL` | **The remainder the decision saw** - frozen because the live row moves on |
| `match_candidate` | `opened_at` | `INTERNAL` | The candidate's birth as seen |
| `allocation` | `id` | `INTERNAL` | A record identifier. Generated |
| `allocation` | `decision_id` | `INTERNAL` | The deciding evaluation - an identifier of a thing |
| `allocation` | `external_item_id` | `INTERNAL` | The claiming item - an identifier of a thing |
| `allocation` | `expectation_id` | `INTERNAL` | The claimed expectation - an identifier of a thing |
| `allocation` | `amount_minor` | `RESTRICTED-FINANCIAL` | **The allocated value** (INV-REC-07) - never in a metric, event or log |
| `allocation` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `allocation` | `scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `allocation` | `reverses_allocation_id` | `INTERNAL` | The reversed allocation, on a repudiation's counter-row - an identifier of a thing |
| `allocation` | `created_at` | `INTERNAL` | When it was recorded |
| `allocation` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `resolution` | `id` | `INTERNAL` | A record identifier. Generated |
| `resolution` | `break_id` | `INTERNAL` | The closed break - an identifier of a thing |
| `resolution` | `kind` | `INTERNAL` | EVIDENCED and its siblings - a closed vocabulary |
| `resolution` | `status` | `INTERNAL` | The machine's position - a closed vocabulary |
| `resolution` | `reason_code` | `INTERNAL` | The closed reason (ADR-0071) - never the narrative |
| `resolution` | `narrative` | `CONFIDENTIAL` | **The resolver's own words** - never logged, evented or audited; screened at the database for PAN and IBAN shapes (INV-PAY-02, INV-RAIL-03) |
| `resolution` | `four_eyes` | `INTERNAL` | Whether a second person was required |
| `resolution` | `proposed_amount_minor` | `RESTRICTED-FINANCIAL` | **The value the resolution explains or moves** - frozen at write, never in a metric, event or log |
| `resolution` | `currency` | `INTERNAL` | An enumeration; part of the monetary shape |
| `resolution` | `scale` | `INTERNAL` | The monetary shape's scale (INV-MON-05) |
| `resolution` | `residual_version` | `INTERNAL` | The break's staleness counter as frozen (ADR-0071) |
| `resolution` | `target_account_id` | `INTERNAL` | The named account, where a transfer names one - an identifier of a thing |
| `resolution` | `offset_item_id` | `INTERNAL` | The released suspense item - an identifier of a thing |
| `resolution` | `chosen_expectation_id` | `INTERNAL` | The manual match's choice (`P8-TSK-015`) - an identifier of a thing |
| `resolution` | `decision_id` | `INTERNAL` | The explaining decision - an identifier of a thing |
| `resolution` | `park_id` | `INTERNAL` | The offset's unpark - an identifier of a thing |
| `resolution` | `rule_set_id` | `INTERNAL` | The version active when written (INV-HIST-04) |
| `resolution` | `adjustment_proposal_id` | `INTERNAL` | The bound ledger proposal (`P8-TSK-015`) - an identifier of a thing |
| `resolution` | `journal_entry_id` | `INTERNAL` | The posted entry, where one posts - an identifier of a thing |
| `resolution` | `proposed_by` | `CONFIDENTIAL` | Who proposed (`audit_record.actor`'s reasoning; the platform for EVIDENCED) |
| `resolution` | `proposed_by_type` | `INTERNAL` | The proposer's kind - a closed vocabulary |
| `resolution` | `proposed_at` | `INTERNAL` | When it was proposed |
| `resolution` | `decided_by` | `CONFIDENTIAL` | Who decided (`audit_record.actor`'s reasoning) |
| `resolution` | `decided_by_type` | `INTERNAL` | The decider's kind - a closed vocabulary |
| `resolution` | `decided_at` | `INTERNAL` | When it was decided |
| `resolution` | `created_at` | `INTERNAL` | When it was recorded |
| `resolution` | `status_changed_at` | `INTERNAL` | When the machine last moved |
| `resolution` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `resolution` | `settlement_batch_id` | `INTERNAL` | A repudiation's subject (`P8-TSK-023`, `V013`) - an identifier of a thing |
| `resolution` | `subject_digest` | `INTERNAL` | The repudiation plan's SHA-256 - a hash of identifiers, states and counts |
| `repudiation_closure` | `break_id` | `INTERNAL` | The break a repudiation closed (`P8-TSK-023`, `V013`) - an identifier of a thing |
| `repudiation_closure` | `resolution_id` | `INTERNAL` | The repudiation that emptied its subject - an identifier of a thing |
| `repudiation_closure` | `closed_at` | `INTERNAL` | When it closed |
| `repudiation_closure` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `resolution_event` | `seq` | `INTERNAL` | The history's server-assigned order |
| `resolution_event` | `resolution_id` | `INTERNAL` | The moved resolution - an identifier of a thing |
| `resolution_event` | `from_status` | `INTERNAL` | The edge's origin - a closed vocabulary |
| `resolution_event` | `to_status` | `INTERNAL` | The edge's target - a closed vocabulary |
| `resolution_event` | `actor` | `CONFIDENTIAL` | Who drove the edge (`audit_record.actor`'s reasoning) |
| `resolution_event` | `actor_type` | `INTERNAL` | The actor's kind - a closed vocabulary |
| `resolution_event` | `reason` | `CONFIDENTIAL` | The edge's stated reason - a person's prose where one is. Screened for card-number and account-identifier shapes at the domain and by reconciliation `V019`'s `resolution_event_reason_no_instrument_shape` (corrected 2026-10-02 by the Phase 8 → 9 transition, SEC-03/SEC-04; the shared `InstrumentShapes` rule) |
| `resolution_event` | `occurred_at` | `INTERNAL` | When the edge was driven |
| `resolution_event` | `correlation_id` | `INTERNAL` | The flow's correlation |

### `fx` — the reference rate and its fetch permit — *added by `P9-TSK-005`*

**The independent reference's evidence** (ADR-0075 §1, `INV-FX-02`): a public market price per
canonical currency pair, the instant the source says it observed it and the instant the database
received it, and the herd's pacing row. Nothing here is about a person or a customer; a reference
mid-rate is market data, never executable and never a price anyone was offered. `INTERNAL`
throughout - the rate included, because it is the source's published mid, not the platform's
pricing (the margins and customer rates that ARE commercial arrive with the quote, `P9-TSK-008`).

| Table | Column | Level | Why |
|---|---|---|---|
| `rate_snapshot` | `id` | `INTERNAL` | A snapshot identifier. Generated |
| `rate_snapshot` | `source` | `INTERNAL` | The declared source's compiled code - `simulated-reference` - an organisation, never a person |
| `rate_snapshot` | `base_currency` | `INTERNAL` | An ISO 4217 code, one of the declaration's canonical pairs |
| `rate_snapshot` | `quote_currency` | `INTERNAL` | An ISO 4217 code, one of the declaration's canonical pairs |
| `rate_snapshot` | `rate` | `INTERNAL` | The source's published mid-market rate - market data, not the platform's pricing or anyone's offer |
| `rate_snapshot` | `observed_at` | `INTERNAL` | When the source says it observed the rate |
| `rate_snapshot` | `received_at` | `INTERNAL` | When the database received it - the instant freshness is judged from |
| `rate_fetch_permit` | `source` | `INTERNAL` | The paced source's compiled code |
| `rate_fetch_permit` | `last_attempt_at` | `INTERNAL` | The herd's last attempt, database-stamped - operational state |
| `rate_fetch_permit` | `attempts` | `INTERNAL` | A counter |
| `rate_fetch_permit` | `created_at` | `INTERNAL` | When the source was first fetched |

### `fx.fx_provider_evidence` — the FX provider's answers — *added by `P9-TSK-006`*

**Verbatim provider payloads** (`INV-HIST-02`): every request, response, inquiry result and
callback exchanged with an FX provider, AES-256-GCM under `FINAPP_FX_EVIDENCE_KEY`. The one content
column is the ciphertext, `CONFIDENTIAL` at the ceiling of what it decrypts to - the platform's
commercial traffic with its counterparty (rates, amounts, trade references), never a customer's
personal data; every other column is metadata. Named with its domain because the register keys on
`table.column` and `payments.provider_evidence` already exists (the `merchant.payout_evidence`
precedent).

| Table | Column | Level | Why |
|---|---|---|---|
| `fx_provider_evidence` | `id` | `INTERNAL` | An evidence identifier. Generated |
| `fx_provider_evidence` | `provider_code` | `INTERNAL` | The declared provider's compiled code - an organisation, never a person |
| `fx_provider_evidence` | `client_reference` | `INTERNAL` | Our own minted reference (`QR` or `T`, `INV-PAY-04`) - an identifier of a thing |
| `fx_provider_evidence` | `kind` | `INTERNAL` | Request, response, inquiry result or callback - a closed list |
| `fx_provider_evidence` | `content_ciphertext` | `CONFIDENTIAL` | The provider exchange, encrypted: rates, amounts and trade references - commercial traffic, at the ceiling of what it decrypts to |
| `fx_provider_evidence` | `content_nonce` | `INTERNAL` | The GCM nonce - public by design |
| `fx_provider_evidence` | `key_version` | `INTERNAL` | Which key wrote it - rotation metadata |
| `fx_provider_evidence` | `checksum_sha256` | `INTERNAL` | A fingerprint of the plaintext, recoverable from nothing |
| `fx_provider_evidence` | `content_length` | `INTERNAL` | A byte count |
| `fx_provider_evidence` | `recorded_at` | `INTERNAL` | When the exchange was retained |

### `fx` — the pricing policy and the kill switch — *added by `P9-TSK-007`*

**The platform's pricing and its stop switch** (ADR-0075 §7, the lifecycle document §3.10).
The margins, the band and the notional bounds ARE the platform's commercial terms -
`CONFIDENTIAL`; whether a pair or provider is stopped is operational posture - `CONFIDENTIAL`,
the `rail_availability` reasoning; every person-written reason and every actor follows the rule
set's precedent. Nothing here is about a customer.

| Table | Column | Level | Why |
|---|---|---|---|
| `pricing_policy_version` | `id` | `INTERNAL` | A version identifier - **the value a quote pins** (`INV-HIST-04`). Generated |
| `pricing_policy_version` | `version` | `INTERNAL` | An ordinal |
| `pricing_policy_version` | `status` | `INTERNAL` | An enumeration member |
| `pricing_policy_version` | `open_quote_cap` | `INTERNAL` | A policy magnitude - a count of quotes, nobody's money |
| `pricing_policy_version` | `proposed_by` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `rule_set.proposed_by` precedent) |
| `pricing_policy_version` | `proposed_at` | `INTERNAL` | A property of the artefact |
| `pricing_policy_version` | `proposal_reason` | `CONFIDENTIAL` | Free prose by a person about a pricing or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `fx V004`'s `<table>_<column>_no_instrument_shape` |
| `pricing_policy_version` | `decided_by` | `CONFIDENTIAL` | Who activated or rejected it - the four-eyes fact |
| `pricing_policy_version` | `decided_at` | `INTERNAL` | When the version was activated or rejected |
| `pricing_policy_version` | `decision_reason` | `CONFIDENTIAL` | Free prose by a person about a pricing or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `fx V004`'s `<table>_<column>_no_instrument_shape` |
| `pricing_policy_version` | `retired_at` | `INTERNAL` | When a successor retired it |
| `pricing_policy_event` | `id` | `INTERNAL` | An event identifier. Generated |
| `pricing_policy_event` | `policy_id` | `INTERNAL` | The moved version - an identifier of a thing |
| `pricing_policy_event` | `from_status` | `INTERNAL` | The edge's origin, NULL at the proposal |
| `pricing_policy_event` | `to_status` | `INTERNAL` | The edge's destination - a closed vocabulary |
| `pricing_policy_event` | `actor_id` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `rule_set.proposed_by` precedent) |
| `pricing_policy_event` | `reason` | `CONFIDENTIAL` | Free prose by a person about a pricing or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `fx V004`'s `<table>_<column>_no_instrument_shape` |
| `pricing_policy_event` | `occurred_at` | `INTERNAL` | Application-stamped transition instant |
| `pricing_pair` | `policy_id` | `INTERNAL` | The version it belongs to |
| `pricing_pair` | `source_currency` | `INTERNAL` | An ISO 4217 code |
| `pricing_pair` | `destination_currency` | `INTERNAL` | An ISO 4217 code |
| `pricing_pair` | `purpose` | `INTERNAL` | A closed vocabulary - conversion or cross-border |
| `pricing_pair` | `providers` | `INTERNAL` | Declared providers' compiled codes, in order - organisations, never persons |
| `pricing_pair` | `spread` | `CONFIDENTIAL` | The platform's pricing - a commercial term no competitor or customer should read off the table |
| `pricing_pair` | `markup` | `CONFIDENTIAL` | The platform's pricing - a commercial term no competitor or customer should read off the table |
| `pricing_pair` | `rate_scale` | `INTERNAL` | A precision - digits, nobody's money |
| `pricing_pair` | `rate_rounding` | `INTERNAL` | A closed vocabulary (`RoundingPolicy`) |
| `pricing_pair` | `amount_rounding` | `INTERNAL` | A closed vocabulary (`RoundingPolicy`) |
| `pricing_pair` | `margin_rounding` | `INTERNAL` | A closed vocabulary (`RoundingPolicy`) |
| `pricing_pair` | `window_seconds` | `INTERNAL` | A policy magnitude - seconds |
| `pricing_pair` | `cover_margin_seconds` | `INTERNAL` | A policy magnitude - seconds |
| `pricing_pair` | `band` | `CONFIDENTIAL` | How far a provider may stray from the reference before the platform refuses - with the margins it discloses how the platform hedges |
| `pricing_pair` | `reference_max_age_seconds` | `INTERNAL` | A policy magnitude - seconds |
| `pricing_pair` | `source_min_minor` | `CONFIDENTIAL` | An operational bound the platform chose, not a person's amount (the `routing_rule.ceiling_amount_minor` reasoning) |
| `pricing_pair` | `source_max_minor` | `CONFIDENTIAL` | An operational bound the platform chose, not a person's amount (the `routing_rule.ceiling_amount_minor` reasoning) |
| `pricing_pair` | `destination_min_minor` | `CONFIDENTIAL` | An operational bound the platform chose, not a person's amount (the `routing_rule.ceiling_amount_minor` reasoning) |
| `pricing_pair` | `destination_max_minor` | `CONFIDENTIAL` | An operational bound the platform chose, not a person's amount (the `routing_rule.ceiling_amount_minor` reasoning) |
| `availability_enable_request` | `id` | `INTERNAL` | A request identifier. Generated |
| `availability_enable_request` | `subject_kind` | `INTERNAL` | A closed vocabulary - pair or provider |
| `availability_enable_request` | `subject` | `INTERNAL` | `AAA-BBB` or a declared provider's code |
| `availability_enable_request` | `status` | `INTERNAL` | An enumeration member |
| `availability_enable_request` | `proposed_by` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `rule_set.proposed_by` precedent) |
| `availability_enable_request` | `proposed_at` | `INTERNAL` | A property of the request |
| `availability_enable_request` | `proposal_reason` | `CONFIDENTIAL` | Free prose by a person about a pricing or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `fx V004`'s `<table>_<column>_no_instrument_shape` |
| `availability_enable_request` | `decided_by` | `CONFIDENTIAL` | Who approved or rejected it - the four-eyes fact |
| `availability_enable_request` | `decided_at` | `INTERNAL` | When it was decided |
| `availability_enable_request` | `decision_reason` | `CONFIDENTIAL` | Free prose by a person about a pricing or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `fx V004`'s `<table>_<column>_no_instrument_shape` |
| `pair_availability` | `seq` | `INTERNAL` | The facts' server-assigned order - the newest is the subject's availability |
| `pair_availability` | `id` | `INTERNAL` | A fact identifier. Generated |
| `pair_availability` | `source_currency` | `INTERNAL` | An ISO 4217 code |
| `pair_availability` | `destination_currency` | `INTERNAL` | An ISO 4217 code |
| `pair_availability` | `available` | `CONFIDENTIAL` | Whether FX is stopped is operational posture - the `rail_availability.available` reasoning |
| `pair_availability` | `actor_id` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `rule_set.proposed_by` precedent) |
| `pair_availability` | `reason` | `CONFIDENTIAL` | Free prose by a person about a pricing or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `fx V004`'s `<table>_<column>_no_instrument_shape` |
| `pair_availability` | `recorded_at` | `INTERNAL` | Application-stamped instant of the act |
| `pair_availability` | `enable_request_id` | `INTERNAL` | The APPROVED request an enabling fact names; NULL on a disable |
| `provider_availability` | `seq` | `INTERNAL` | The facts' server-assigned order - the newest is the subject's availability |
| `provider_availability` | `id` | `INTERNAL` | A fact identifier. Generated |
| `provider_availability` | `provider_code` | `INTERNAL` | A declared provider's compiled code - an organisation |
| `provider_availability` | `available` | `CONFIDENTIAL` | Whether FX is stopped is operational posture - the `rail_availability.available` reasoning |
| `provider_availability` | `actor_id` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `rule_set.proposed_by` precedent) |
| `provider_availability` | `reason` | `CONFIDENTIAL` | Free prose by a person about a pricing or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `fx V004`'s `<table>_<column>_no_instrument_shape` |
| `provider_availability` | `recorded_at` | `INTERNAL` | Application-stamped instant of the act |
| `provider_availability` | `enable_request_id` | `INTERNAL` | The APPROVED request an enabling fact names; NULL on a disable |

### `fx` — the quote — *added by `P9-TSK-008`*

**The customer's frozen price** (ADR-0075 §§3-6, ADR-0076 §1). The plan's amounts are
`RESTRICTED-FINANCIAL` - the amounts the trade will post; the customer, provider and internal rates
and the margins are the platform's commercial terms, `CONFIDENTIAL`; the reference rate stays
`INTERNAL` (a published mid, `rate_snapshot.rate`'s reasoning). Every quote is owner-scoped at
every door. Nothing here is a person's PII beyond identifiers.

| Table | Column | Level | Why |
|---|---|---|---|
| `quote_request` | `id` | `INTERNAL` | A request identifier. Generated |
| `quote_request` | `reference` | `INTERNAL` | Our reference `QR`, sent to the provider - an identifier of a thing |
| `quote_request` | `claim_key` | `CONFIDENTIAL` | The idempotency scope and the caller's key - the `idempotency_record` key's reasoning |
| `quote_request` | `owner_party_id` | `INTERNAL` | An identifier of a party - `ledger_account.owner_ref`'s reasoning |
| `quote_request` | `purpose` | `INTERNAL` | A closed vocabulary |
| `quote_request` | `source_currency` | `INTERNAL` | An ISO 4217 code |
| `quote_request` | `destination_currency` | `INTERNAL` | An ISO 4217 code |
| `quote_request` | `fixed_side` | `INTERNAL` | A closed vocabulary |
| `quote_request` | `fixed_amount_minor` | `RESTRICTED-FINANCIAL` | An amount of the customer's planned conversion - `hold.amount_minor`'s reasoning, before the movement it plans exists |
| `quote_request` | `fixed_scale` | `INTERNAL` | Part of the monetary shape; meaningless without the amount |
| `quote_request` | `pricing_policy_version_id` | `INTERNAL` | The pinned version - an identifier of a thing (`INV-HIST-04`) |
| `quote_request` | `requested_at` | `INTERNAL` | The database's instant before the provider call - the window's anchor |
| `quote_request` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `quote_sourcing_step` | `quote_request_id` | `INTERNAL` | The request it sourced |
| `quote_sourcing_step` | `attempt` | `INTERNAL` | An ordinal |
| `quote_sourcing_step` | `position` | `INTERNAL` | The candidate's place in the pinned order |
| `quote_sourcing_step` | `provider_code` | `INTERNAL` | A declared provider's compiled code - an organisation |
| `quote_sourcing_step` | `declaration_version` | `INTERNAL` | Which declaration judged it |
| `quote_sourcing_step` | `outcome` | `INTERNAL` | A closed vocabulary |
| `quote_sourcing_step` | `detail` | `INTERNAL` | An enumerated reason name, never a provider's text |
| `quote_sourcing_step` | `recorded_at` | `INTERNAL` | The database's instant |
| `quote` | `id` | `INTERNAL` | A quote identifier - what the customer accepts and the trade pins |
| `quote` | `quote_request_id` | `INTERNAL` | Its request |
| `quote` | `owner_party_id` | `INTERNAL` | An identifier of a party - `ledger_account.owner_ref`'s reasoning |
| `quote` | `purpose` | `INTERNAL` | A closed vocabulary |
| `quote` | `source_currency` | `INTERNAL` | An ISO 4217 code |
| `quote` | `destination_currency` | `INTERNAL` | An ISO 4217 code |
| `quote` | `fixed_side` | `INTERNAL` | A closed vocabulary |
| `quote` | `pricing_policy_version_id` | `INTERNAL` | The pinned version (`INV-HIST-04`) |
| `quote` | `provider_code` | `INTERNAL` | A declared provider's compiled code |
| `quote` | `provider_quote_reference` | `CONFIDENTIAL` | The provider's own reference (`PHASE_9_PLAN.md` §8: provider references `CONFIDENTIAL`) |
| `quote` | `provider_rate` | `CONFIDENTIAL` | The provider's firm price to the platform - commercial, never shown to a customer |
| `quote` | `provider_valid_for_ms` | `INTERNAL` | A duration the provider stated |
| `quote` | `provider_value_date` | `INTERNAL` | A date the provider stated |
| `quote` | `obtained_at` | `INTERNAL` | When the answer arrived - provenance, never a decision |
| `quote` | `requested_at` | `INTERNAL` | The request's database instant, copied - the window's anchor |
| `quote` | `reference_snapshot_id` | `INTERNAL` | The reference the band judged against |
| `quote` | `reference_rate` | `INTERNAL` | The independent source's published mid - `rate_snapshot.rate`'s reasoning |
| `quote` | `customer_rate` | `CONFIDENTIAL` | The platform's price to its customer (`PHASE_9_PLAN.md` §8: quotes and rates `CONFIDENTIAL`, owner-scoped) |
| `quote` | `internal_rate` | `CONFIDENTIAL` | The dealing rate - commercial, stored for Phase 14's split |
| `quote` | `disclosed_margin` | `CONFIDENTIAL` | The margin over mid disclosed to this customer - their price's anatomy |
| `quote` | `spread` | `CONFIDENTIAL` | The pinned terms, copied - `pricing_pair.spread`'s reasoning |
| `quote` | `markup` | `CONFIDENTIAL` | The pinned terms, copied - `pricing_pair.markup`'s reasoning |
| `quote` | `rate_scale` | `INTERNAL` | A precision |
| `quote` | `rate_rounding` | `INTERNAL` | A closed vocabulary (`RoundingPolicy`) |
| `quote` | `amount_rounding` | `INTERNAL` | A closed vocabulary (`RoundingPolicy`) |
| `quote` | `margin_rounding` | `INTERNAL` | A closed vocabulary (`RoundingPolicy`) |
| `quote` | `window_seconds` | `INTERNAL` | A policy magnitude, copied |
| `quote` | `cover_margin_seconds` | `INTERNAL` | A policy magnitude, copied |
| `quote` | `source_scale` | `INTERNAL` | Part of the monetary shape |
| `quote` | `destination_scale` | `INTERNAL` | Part of the monetary shape |
| `quote` | `customer_source_minor` | `RESTRICTED-FINANCIAL` | A frozen posting-plan amount the trade will post - `journal_line.amount_minor`'s reasoning, ahead of the entry |
| `quote` | `customer_destination_minor` | `RESTRICTED-FINANCIAL` | A frozen posting-plan amount the trade will post - `journal_line.amount_minor`'s reasoning, ahead of the entry |
| `quote` | `position_source_minor` | `RESTRICTED-FINANCIAL` | A frozen posting-plan amount the trade will post - `journal_line.amount_minor`'s reasoning, ahead of the entry |
| `quote` | `position_destination_minor` | `RESTRICTED-FINANCIAL` | A frozen posting-plan amount the trade will post - `journal_line.amount_minor`'s reasoning, ahead of the entry |
| `quote` | `margin_minor` | `RESTRICTED-FINANCIAL` | A frozen posting-plan amount the trade will post - `journal_line.amount_minor`'s reasoning, ahead of the entry |
| `quote` | `spread_margin_minor` | `RESTRICTED-FINANCIAL` | A frozen posting-plan amount the trade will post - `journal_line.amount_minor`'s reasoning, ahead of the entry |
| `quote` | `markup_margin_minor` | `RESTRICTED-FINANCIAL` | A frozen posting-plan amount the trade will post - `journal_line.amount_minor`'s reasoning, ahead of the entry |
| `quote` | `residual_minor` | `RESTRICTED-FINANCIAL` | A frozen posting-plan amount the trade will post - `journal_line.amount_minor`'s reasoning, ahead of the entry |
| `quote` | `status` | `INTERNAL` | An enumeration member |
| `quote` | `issued_at` | `INTERNAL` | The database's instant of issue |
| `quote` | `expires_at` | `INTERNAL` | Computed by the database from durations - the lock's end |
| `quote` | `closed_at` | `INTERNAL` | The database's instant of the terminal edge |
| `quote` | `issued_event_id` | `INTERNAL` | The issue event's identifier - the expiry's causation |
| `quote` | `correlation_id` | `INTERNAL` | The request's correlation, restored by the sweeper |
| `quote_event` | `id` | `INTERNAL` | An event identifier. Generated |
| `quote_event` | `quote_id` | `INTERNAL` | The moved quote |
| `quote_event` | `from_status` | `INTERNAL` | The edge's origin, NULL at birth |
| `quote_event` | `to_status` | `INTERNAL` | The edge's destination - a closed vocabulary |
| `quote_event` | `actor_id` | `CONFIDENTIAL` | Who moved it - the audit actor class |
| `quote_event` | `actor_type` | `INTERNAL` | An enumerated population |
| `quote_event` | `detected_by` | `INTERNAL` | `SWEEP` or `ACCEPTANCE` - a closed vocabulary |
| `quote_event` | `occurred_at` | `INTERNAL` | The database's instant - the writing statement's, or for `ACCEPTED` and `CANCELLED` the instant their conditional judged against `expires_at` (`X-TSK-016`) |
| `quote_event` | `correlation_id` | `INTERNAL` | The flow's correlation |

### `fx` — the trade and the cover — *added by `P9-TSK-009`*

**The booked conversion and the cover it wants** (ADR-0076 §1, ADR-0077). The trade's amounts
are the posted plan - `RESTRICTED-FINANCIAL`; the rates are the platform's price - `CONFIDENTIAL`;
the cover's fixed amount is the platform's exposure - `RESTRICTED-FINANCIAL`. Owner-scoped at every
door.

| Table | Column | Level | Why |
|---|---|---|---|
| `trade` | `id` | `INTERNAL` | A trade identifier - the posting key's subject |
| `trade` | `quote_id` | `INTERNAL` | The executed quote (`UNIQUE`) |
| `trade` | `owner_party_id` | `INTERNAL` | An identifier of a party - `ledger_account.owner_ref`'s reasoning |
| `trade` | `purpose` | `INTERNAL` | A closed vocabulary |
| `trade` | `source_currency` | `INTERNAL` | An ISO 4217 code |
| `trade` | `destination_currency` | `INTERNAL` | An ISO 4217 code |
| `trade` | `fixed_side` | `INTERNAL` | A closed vocabulary |
| `trade` | `pricing_policy_version_id` | `INTERNAL` | The pinned version, copied (`INV-HIST-04`) |
| `trade` | `provider_code` | `INTERNAL` | A declared provider's compiled code |
| `trade` | `customer_rate` | `CONFIDENTIAL` | The platform's price to its customer, copied (`quote.customer_rate`'s reasoning) |
| `trade` | `executed_rate` | `CONFIDENTIAL` | Equal to the customer rate by `CHECK` - the same fact |
| `trade` | `source_scale` | `INTERNAL` | Part of the monetary shape |
| `trade` | `destination_scale` | `INTERNAL` | Part of the monetary shape |
| `trade` | `customer_source_minor` | `RESTRICTED-FINANCIAL` | A booked plan amount - the quote's copy, posted by the trade's entry (`journal_line.amount_minor`'s reasoning) |
| `trade` | `customer_destination_minor` | `RESTRICTED-FINANCIAL` | A booked plan amount - the quote's copy, posted by the trade's entry (`journal_line.amount_minor`'s reasoning) |
| `trade` | `position_source_minor` | `RESTRICTED-FINANCIAL` | A booked plan amount - the quote's copy, posted by the trade's entry (`journal_line.amount_minor`'s reasoning) |
| `trade` | `position_destination_minor` | `RESTRICTED-FINANCIAL` | A booked plan amount - the quote's copy, posted by the trade's entry (`journal_line.amount_minor`'s reasoning) |
| `trade` | `margin_minor` | `RESTRICTED-FINANCIAL` | A booked plan amount - the quote's copy, posted by the trade's entry (`journal_line.amount_minor`'s reasoning) |
| `trade` | `spread_margin_minor` | `RESTRICTED-FINANCIAL` | A booked plan amount - the quote's copy, posted by the trade's entry (`journal_line.amount_minor`'s reasoning) |
| `trade` | `markup_margin_minor` | `RESTRICTED-FINANCIAL` | A booked plan amount - the quote's copy, posted by the trade's entry (`journal_line.amount_minor`'s reasoning) |
| `trade` | `residual_minor` | `RESTRICTED-FINANCIAL` | A booked plan amount - the quote's copy, posted by the trade's entry (`journal_line.amount_minor`'s reasoning) |
| `trade` | `status` | `INTERNAL` | An enumeration member |
| `trade` | `booked_at` | `INTERNAL` | The database's instant of booking |
| `trade` | `booked_on` | `INTERNAL` | The booking's UTC date - the entry's posting and value date |
| `trade` | `journal_entry_id` | `INTERNAL` | The entry `fx-trade:<id>` - an identifier of a thing |
| `trade` | `correlation_id` | `INTERNAL` | The flow's correlation |
| `trade_reversal` | `id` | `INTERNAL` | A proposal identifier (`P9-TSK-025`) |
| `trade_reversal` | `trade_id` | `INTERNAL` | The conversion it would reverse |
| `trade_reversal` | `status` | `INTERNAL` | The machine's word |
| `trade_reversal` | `proposed_by` | `CONFIDENTIAL` | The proposing operator's identifier |
| `trade_reversal` | `proposed_reason` | `CONFIDENTIAL` | An operator's free-text reason - classified at its ceiling; a reason names no customer data by the desk's rule |
| `trade_reversal` | `proposed_at` | `INTERNAL` | Stamped by the database |
| `trade_reversal` | `decided_by` | `CONFIDENTIAL` | The deciding operator's identifier - never the proposer (`CHECK`) |
| `trade_reversal` | `decided_reason` | `CONFIDENTIAL` | As `proposed_reason` |
| `trade_reversal` | `decided_at` | `INTERNAL` | Stamped by the database |
| `trade_reversal` | `reversal_entry_id` | `INTERNAL` | The mirror entry - exactly when `APPROVED` |
| `trade_reversal` | `correlation_id` | `INTERNAL` | The proposal's flow |
| `trade_reversal_event` | `id` | `INTERNAL` | An edge identifier |
| `trade_reversal_event` | `reversal_id` | `INTERNAL` | As `trade_reversal.id` |
| `trade_reversal_event` | `from_status` | `INTERNAL` | As `trade_reversal.status` |
| `trade_reversal_event` | `to_status` | `INTERNAL` | As `trade_reversal.status` |
| `trade_reversal_event` | `actor` | `CONFIDENTIAL` | The acting operator's identifier |
| `trade_reversal_event` | `reason` | `CONFIDENTIAL` | As `proposed_reason` |
| `trade_reversal_event` | `occurred_at` | `INTERNAL` | Stamped by the database |
| `cover` | `id` | `INTERNAL` | A cover identifier |
| `cover` | `quote_id` | `INTERNAL` | The quote whose position it covers |
| `cover` | `kind` | `INTERNAL` | A closed vocabulary - COVER or UNWIND |
| `cover` | `status` | `INTERNAL` | An enumeration member |
| `cover` | `provider_code` | `INTERNAL` | A declared provider's compiled code |
| `cover` | `source_currency` | `INTERNAL` | An ISO 4217 code |
| `cover` | `destination_currency` | `INTERNAL` | An ISO 4217 code |
| `cover` | `fixed_side` | `INTERNAL` | A closed vocabulary |
| `cover` | `fixed_amount_minor` | `RESTRICTED-FINANCIAL` | The position's fixed leg the cover replicates - the platform's exposure |
| `cover` | `fixed_scale` | `INTERNAL` | Part of the monetary shape |
| `cover` | `attempts` | `INTERNAL` | An ordinal |
| `cover` | `last_dispatched_at` | `INTERNAL` | The send permit, stamped by the database |
| `cover` | `created_at` | `INTERNAL` | The database's instant of birth |
| `cover` | `correlation_id` | `INTERNAL` | The booking's correlation |
| `cover_attempt` | `cover_id` | `INTERNAL` | Its cover |
| `cover_attempt` | `attempt` | `INTERNAL` | An ordinal |
| `cover_attempt` | `client_reference` | `INTERNAL` | Our reference `T`, minted and stored before any send - an identifier of a thing |
| `cover_attempt` | `provider_quote_ref` | `CONFIDENTIAL` | The provider's quote it executes (`PHASE_9_PLAN.md` §8: provider references `CONFIDENTIAL`) |
| `cover_attempt` | `created_at` | `INTERNAL` | The database's instant |
| `cover_attempt` | `stated_counter_minor` | `RESTRICTED-FINANCIAL` | The firm quote's stated counter amount a requote or unwind executes against (fx `V010`, the Phase 9 → 10 transition) |
| `cover` | `caused_by_event_id` | `INTERNAL` | The `fx.FxQuoteAccepted` event the cover's events are caused by (`P9-TSK-012`) |
| `cover` | `requote_failures` | `INTERNAL` | A count of a rejected cover's refused requotes - the sweeper's backoff (`P9-TSK-012`) |
| `cover_execution` | `cover_id` | `INTERNAL` | Its cover - one execution per cover, the primary key (`P9-TSK-012`) |
| `cover_execution` | `attempt` | `INTERNAL` | The executing attempt's ordinal |
| `cover_execution` | `client_reference` | `INTERNAL` | Our reference `T` of that attempt - an identifier of a thing |
| `cover_execution` | `provider_code` | `INTERNAL` | A declared provider's compiled code |
| `cover_execution` | `provider_trade_ref` | `CONFIDENTIAL` | The provider's trade reference (`PHASE_9_PLAN.md` §8: provider references `CONFIDENTIAL`); `UNIQUE` per provider |
| `cover_execution` | `fixed_side` | `INTERNAL` | A closed vocabulary |
| `cover_execution` | `sold_currency` | `INTERNAL` | An ISO 4217 code |
| `cover_execution` | `sold_minor` | `RESTRICTED-FINANCIAL` | A booked execution amount - the cover entry posts it (`journal_line.amount_minor`'s reasoning) |
| `cover_execution` | `sold_scale` | `INTERNAL` | Part of the monetary shape |
| `cover_execution` | `bought_currency` | `INTERNAL` | An ISO 4217 code |
| `cover_execution` | `bought_minor` | `RESTRICTED-FINANCIAL` | A booked execution amount - the cover entry posts it (`journal_line.amount_minor`'s reasoning) |
| `cover_execution` | `bought_scale` | `INTERNAL` | Part of the monetary shape |
| `cover_execution` | `executed_rate` | `CONFIDENTIAL` | The provider's executed rate - beside the customer rate it discloses the platform's margin |
| `cover_execution` | `value_date` | `INTERNAL` | The provider's value date |
| `cover_execution` | `plan_sold_minor` | `RESTRICTED-FINANCIAL` | The quote's plan leg, copied and checked at birth - the platform's exposure |
| `cover_execution` | `plan_bought_minor` | `RESTRICTED-FINANCIAL` | The quote's plan leg, copied and checked at birth - the platform's exposure |
| `cover_execution` | `realised_sold_minor` | `RESTRICTED-FINANCIAL` | The leg's realised result, executed against plan by `CHECK` - the amount the entry posts as realised gain or loss |
| `cover_execution` | `realised_bought_minor` | `RESTRICTED-FINANCIAL` | The leg's realised result, executed against plan by `CHECK` - the amount the entry posts as realised gain or loss |
| `cover_execution` | `executed_off_plan` | `INTERNAL` | Whether the provider deviated on the fixed leg - a flag, derived by `CHECK` |
| `cover_execution` | `quoted_computed_minor` | `RESTRICTED-FINANCIAL` | The computed leg the execution was quoted at - the plan's on attempt 1, the stated counter after (fx `V010`) |
| `cover_execution` | `computed_deviation` | `INTERNAL` | Whether the provider executed the computed leg away from its own firm quote - a verdict, tied by `CHECK` (fx `V010`) |
| `cover_execution` | `executed_rate_coherent` | `INTERNAL` | Whether the executed rate explains the sold and bought amounts - a verdict (fx `V010`) |
| `cover_execution` | `journal_entry_id` | `INTERNAL` | The entry `fx-cover:<coverId>` (`UNIQUE`) - an identifier of a thing |
| `cover_execution` | `recorded_at` | `INTERNAL` | The database's instant |
| `cover_execution` | `recorded_on` | `INTERNAL` | The database's date - the entry's posting date |
| `cover_execution` | `correlation_id` | `INTERNAL` | The flow's correlation |

### `consent.consent_text` and `consent.consent_record` — *added by `P2-TSK-017`*

| Table | Column | Level | Why |
|---|---|---|---|
| `consent_text` | `purpose` | `INTERNAL` | An enumeration member |
| `consent_text` | `version` | `INTERNAL` | A counter |
| `consent_text` | `body` | `PUBLIC` | The words shown to every customer before they agree — published by design, and the first genuinely `PUBLIC` column on the platform. The classification is about the column's ceiling, and this column's ceiling is a notice |
| `consent_text` | `requires_reconsent` | `INTERNAL` | A property of the artefact |
| `consent_text` | `published_at` | `INTERNAL` | A property of the artefact |
| `consent_record` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `consent_record` | `party_id` | `CONFIDENTIAL` | The `beneficial_owner.owner_party_id` reasoning: the pairing with `purpose` and `action` *is* the fact — this person granted or refused this processing — and the plan (§6) classifies the consent history `CONFIDENTIAL` in as many words. What it resolves to stays `RESTRICTED-PII` as ever |
| `consent_record` | `purpose` | `INTERNAL` | An enumeration member; the fact lives in the pairing, carried by `party_id`'s level |
| `consent_record` | `action` | `INTERNAL` | An enumeration of two values, the same reasoning |
| `consent_record` | `text_version` | `INTERNAL` | A counter pinning an artefact (`INV-CNS-04`) |
| `consent_record` | `recorded_at` | `CONFIDENTIAL` | Dates a consent event for a person — `kyc_case.opened_at`'s reasoning |
| `consent_record` | `seq` | `INTERNAL` | The server-assigned position in the history — data only in the schema's sense |

### `kyc.kyc_document` — *added by `P2-TSK-008`*

| Table | Column | Level | Note |
|---|---|---|---|
| `kyc_document` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `kyc_document` | `case_id` | `INTERNAL` | As `kyc_case.id` — an identifier of a thing |
| `kyc_document` | `document_type` | `CONFIDENTIAL` | *Which papers a person submitted* is a fact about them — a driving licence versus a passport narrows who they are, and the row's existence dates their onboarding |
| `kyc_document` | `content_type` | `INTERNAL` | A media format. Three values, none about a person |
| `kyc_document` | `content_ciphertext` | `RESTRICTED-PII` | **The document.** Classified at the ceiling of what it decrypts to (ADR-0022), not at the comfort of its encryption: the level is what governs handling if the encryption is ever broken, mis-keyed or stripped by a migration — exactly the day the classification must already be right |
| `kyc_document` | `content_nonce` | `INTERNAL` | Public-by-design cryptographic material; useless without the key |
| `kyc_document` | `key_version` | `INTERNAL` | Which key wrote the row — operational metadata for rotation |
| `kyc_document` | `checksum_sha256` | `RESTRICTED-PII` | **A possession oracle over the content.** Anyone holding a candidate document can hash it and confirm this person submitted exactly it — the checksum identifies the content without revealing it, which is a disclosure about a person, not about a system. Classified with what it fingerprints |
| `kyc_document` | `content_length` | `CONFIDENTIAL` | Weakly identifying on its own; with the type it narrows a known document. Errs up, because ADR-0022 forbids reclassifying later |
| `kyc_document` | `uploaded_at` | `CONFIDENTIAL` | Dates a KYC event — `kyc_case.opened_at`'s reasoning |

### `kyc.verification_check` — *added by `P2-TSK-009`*

| Table | Column | Level | Note |
|---|---|---|---|
| `verification_check` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `verification_check` | `case_id` | `INTERNAL` | As `kyc_case.id` — an identifier of a thing |
| `verification_check` | `check_type` | `CONFIDENTIAL` | *Which questions were asked about a person* is a fact about them: a SANCTIONS row existing at all says the platform screened this customer, and the set of types narrows what kind of customer they are. `kyc_case.status`'s tipping-off reasoning, one table down |
| `verification_check` | `status` | `CONFIDENTIAL` | **The sharper tipping-off column.** `HIT` on a SANCTIONS check is precisely the disclosure that is an offence in some regimes; `INDETERMINATE` says a review is unresolved. The case's status is shaped to hide exactly this, so the underlying value gets at least the case's level |
| `verification_check` | `requested_at` | `CONFIDENTIAL` | Dates a KYC event — `kyc_case.opened_at`'s reasoning |
| `verification_check` | `status_changed_at` | `CONFIDENTIAL` | Dates the outcome, which with the status says *when* a hit landed — `kyc_case.status_changed_at`'s reasoning |

### `kyc.verification_evidence` — *added by `P2-TSK-009`*

The `kyc_document` at-rest shape (ADR-0036 groups provider evidence and document content under
one treatment), and the same classifications for the same columns, for the same reasons.

| Table | Column | Level | Note |
|---|---|---|---|
| `verification_evidence` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `verification_evidence` | `check_id` | `INTERNAL` | As `verification_check.id` — an identifier of a thing |
| `verification_evidence` | `content_ciphertext` | `RESTRICTED-PII` | **The provider's raw answer about a person.** Classified at the ceiling of what it decrypts to (ADR-0022): a screening response can carry the match — names, dates of birth, list entries — and the level is what governs handling if the encryption is ever broken, mis-keyed or stripped. `kyc_document.content_ciphertext`'s reasoning verbatim |
| `verification_evidence` | `content_nonce` | `INTERNAL` | Public-by-design cryptographic material; useless without the key |
| `verification_evidence` | `key_version` | `INTERNAL` | Which key wrote the row — operational metadata for rotation |
| `verification_evidence` | `checksum_sha256` | `RESTRICTED-PII` | The possession oracle again: anyone holding a candidate payload can confirm this is the answer the provider gave about this person. Classified with what it fingerprints — `kyc_document.checksum_sha256` |
| `verification_evidence` | `content_length` | `CONFIDENTIAL` | Weakly identifying alone; a hit response is longer than `{"status":"clear"}`, so the length leaks the outcome's shape. Errs up, because ADR-0022 forbids reclassifying later |
| `verification_evidence` | `received_at` | `CONFIDENTIAL` | Dates a KYC event — `kyc_case.opened_at`'s reasoning |

### `kyc.review_task` — *added by `P2-TSK-010`*

Identifiers only, by design: the raising detail stays in the encrypted evidence, and the
resolution's reason arrives with `P2-TSK-012`'s columns, classified then.

| Table | Column | Level | Note |
|---|---|---|---|
| `review_task` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `review_task` | `case_id` | `INTERNAL` | As `kyc_case.id` — an identifier of a thing |
| `review_task` | `check_id` | `INTERNAL` | As `verification_check.id` — an identifier of a thing |
| `review_task` | `status` | `CONFIDENTIAL` | **The tipping-off column, one table further down.** A review task *existing* says screening raised something about this person — a hit or an unresolvable check — which is exactly what the customer-facing status is shaped to hide (`PHASE_2_PLAN.md` §6), and in some regimes disclosing a sanctions review in progress is an offence. As `verification_check.status` |
| `review_task` | `opened_at` | `CONFIDENTIAL` | Dates a screening event on a named person — `kyc_case.status_changed_at`'s reasoning, sharpened: *when the platform started worrying* |
| `review_task` | `resolved_by` | `RESTRICTED-PII` | *Added by `P2-TSK-012`.* The reviewer's `IdentityId` — as `audit_record.actor_id`: from Phase 1 an identity names a person, and the ceiling is what the column may ever hold |
| `review_task` | `resolved_at` | `CONFIDENTIAL` | *Added by `P2-TSK-012`.* Dates a person's judgement about a screening event — `opened_at`'s reasoning, the other end |
| `review_task` | `resolution_reason` | `RESTRICTED-PII` | *Added by `P2-TSK-012`.* **Free text written by a person** about somebody's screening result — it may name the customer, a list entry or a case number. `audit_record.reason`'s ceiling, for `audit_record.reason`'s reason |

### `kyc.kyc_decision` and `kyc.kyc_decision_check` — *added by `P2-TSK-013`*

The record every later financial phase gates on (`INV-KYC-02`), append-only at the privilege
level. The decision's sensitive halves mirror the resolution's: who decided is a person, and
the reason is a person's prose about a person.

| Table | Column | Level | Note |
|---|---|---|---|
| `kyc_decision` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `kyc_decision` | `case_id` | `INTERNAL` | As `kyc_case.id` — an identifier of a thing |
| `kyc_decision` | `outcome` | `CONFIDENTIAL` | Whether a named person was approved or refused onboarding is a fact about them — `kyc_case.status`'s reasoning, at its sharpest: this column is the answer, permanently |
| `kyc_decision` | `decision_basis` | `CONFIDENTIAL` | `AUTOMATIC` discloses no review was raised; `REVIEWER` that one was — `review_task.status`'s tipping-off reasoning, one join away |
| `kyc_decision` | `decided_by` | `RESTRICTED-PII` | The reviewer's `IdentityId` — as `review_task.resolved_by`: from Phase 1 an identity names a person. `NULL` exactly when the basis is `AUTOMATIC`, and the ceiling is what the column may ever hold |
| `kyc_decision` | `reason` | `RESTRICTED-PII` | On the reviewer path, **free text written by a person** about somebody's verification — `review_task.resolution_reason`'s ceiling for the same reason. The automatic path writes a constant, and the ceiling is what the column may ever hold, not what the common row does |
| `kyc_decision` | `policy_version` | `INTERNAL` | A platform label naming a regime — as `kyc_case.policy_version` |
| `kyc_decision` | `decided_at` | `CONFIDENTIAL` | Dates the decision on a named person's case — `kyc_case.status_changed_at`'s reasoning |
| `kyc_decision_check` | `decision_id` | `INTERNAL` | As `kyc_decision.id` — an identifier of a thing |
| `kyc_decision_check` | `check_id` | `INTERNAL` | As `verification_check.id` — an identifier of a thing |

### `kyc.counterparty_screening` and `kyc.counterparty_screening_attempt` — *added by `P9-TSK-016`*

A counterparty is a payee abroad, never a customer: it cannot consent, and screening it rests on the
platform's **legal obligation** (ADR-0081 point 7). Its name rests **only here**, encrypted with the
screening id as associated data; every other module, log, event and audit body is asserted free of it
(the needle). The status columns carry `verification_check.status`'s tipping-off reasoning.

| Table | Column | Level | Note |
|---|---|---|---|
| `counterparty_screening` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `counterparty_screening` | `request_reference` | `INTERNAL` | The caller's identifier for the request (a beneficiary's, from `P9-TSK-017`) - an identifier of a thing |
| `counterparty_screening` | `subject_ciphertext` | `RESTRICTED-PII` | **The counterparty's name.** Classified at the ceiling of what it decrypts to (ADR-0022); AES-256-GCM bound to the row by its id, so a copied ciphertext names nobody |
| `counterparty_screening` | `subject_nonce` | `INTERNAL` | Public-by-design cryptographic material |
| `counterparty_screening` | `subject_key_version` | `INTERNAL` | Which key wrote the row - rotation metadata |
| `counterparty_screening` | `country` | `CONFIDENTIAL` | Provider-attested destination country - with the entity type, narrows who the payee is (`INV-RAIL-03` admits it) |
| `counterparty_screening` | `entity_type` | `CONFIDENTIAL` | An enumeration of two values, about a person or firm |
| `counterparty_screening` | `payee_verdict` | `CONFIDENTIAL` | The payee check as handed in - part of the decision's basis; says whether the name matched the account |
| `counterparty_screening` | `status` | `CONFIDENTIAL` | **The tipping-off column.** `IN_REVIEW` after a hit, `BLOCKED` after a true match - never shown to a customer |
| `counterparty_screening` | `review_reason` | `CONFIDENTIAL` | Why a person was asked - `HIT` is the disclosure the status column hides |
| `counterparty_screening` | `decision_basis` | `CONFIDENTIAL` | `AUTOMATIC` or `REVIEWER` - with the status, says whether a person judged a match |
| `counterparty_screening` | `policy_version` | `INTERNAL` | A platform policy label |
| `counterparty_screening` | `decided_at` | `CONFIDENTIAL` | Dates the outcome - `verification_check.status_changed_at`'s reasoning |
| `counterparty_screening` | `decided_by` | `CONFIDENTIAL` | The reviewer's actor id - who judged a sanctions match |
| `counterparty_screening` | `requested_by` | `CONFIDENTIAL` | The requester's actor id - who registered the beneficiary; a reviewer never decides their own (kyc `V010`, the Phase 9 → 10 transition) |
| `counterparty_screening` | `decision_reason_code` | `CONFIDENTIAL` | `TRUE_MATCH` is the sharpest disclosure the table holds |
| `counterparty_screening` | `decision_narrative` | `CONFIDENTIAL` | A reviewer's free prose about a counterparty - screened for instrument shapes at the domain and by `CHECK`; classified with the reasons it sits beside |
| `counterparty_screening` | `attempts` | `INTERNAL` | A counter |
| `counterparty_screening` | `next_attempt_at` | `INTERNAL` | The retry permit |
| `counterparty_screening` | `requested_at` | `CONFIDENTIAL` | Dates a screening event |
| `counterparty_screening_attempt` | `screening_id` | `INTERNAL` | As `counterparty_screening.id` |
| `counterparty_screening_attempt` | `attempt` | `INTERNAL` | A counter |
| `counterparty_screening_attempt` | `verdict` | `CONFIDENTIAL` | The provider's verdict - evidence, never the decision (`INV-KYC-01`); `HIT` is a tipping-off fact |
| `counterparty_screening_attempt` | `evidence_ciphertext` | `RESTRICTED-PII` | **The provider's raw answer about a counterparty** - `verification_evidence.content_ciphertext`'s reasoning verbatim |
| `counterparty_screening_attempt` | `evidence_nonce` | `INTERNAL` | Public-by-design cryptographic material |
| `counterparty_screening_attempt` | `evidence_key_version` | `INTERNAL` | Rotation metadata |
| `counterparty_screening_attempt` | `evidence_checksum` | `RESTRICTED-PII` | The possession oracle - classified with what it fingerprints |
| `counterparty_screening_attempt` | `evidence_length` | `CONFIDENTIAL` | A hit answer is longer than a clear one - `verification_evidence.content_length`'s reasoning |
| `counterparty_screening_attempt` | `answered_at` | `CONFIDENTIAL` | Dates a screening event |

### `crossborder` - the corridor policy and corridor availability - *added by `P9-TSK-015`*

**The platform's corridor terms and its stop switch** (ADR-0080 §4). The corridor policy is the
fx pricing policy's shape: the fee and the per-payment maximum ARE the platform's commercial terms -
`CONFIDENTIAL`; whether a corridor is stopped is operational posture - `CONFIDENTIAL`; every
person-written reason and every actor follows the pricing policy's precedent. Availability is an
append-only fact per change, read unlocked; its writers serialise on advisory namespace 8. Nothing
here is about a customer. (Registered by `P9-DOC-001`: the rows were missing while the database
tier that runs `ColumnClassificationTest` was skipped.)

| Table | Column | Level | Why |
|---|---|---|---|
| `corridor_policy_version` | `id` | `INTERNAL` | A version identifier - **the value an offer and a payment pin** (`INV-HIST-04`). Generated |
| `corridor_policy_version` | `version` | `INTERNAL` | An ordinal |
| `corridor_policy_version` | `status` | `INTERNAL` | An enumeration member |
| `corridor_policy_version` | `proposed_by` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `pricing_policy_version.proposed_by` precedent) |
| `corridor_policy_version` | `proposed_at` | `INTERNAL` | A property of the artefact |
| `corridor_policy_version` | `proposal_reason` | `CONFIDENTIAL` | Free prose by a person about a corridor policy or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `crossborder V002`'s `<table>_<column>_no_instrument_shape` |
| `corridor_policy_version` | `decided_by` | `CONFIDENTIAL` | Who activated or rejected it - the four-eyes fact (`corridor_policy_four_eyes`) |
| `corridor_policy_version` | `decided_at` | `INTERNAL` | When the version was activated or rejected |
| `corridor_policy_version` | `decision_reason` | `CONFIDENTIAL` | Free prose by a person about a corridor policy or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `crossborder V002`'s `<table>_<column>_no_instrument_shape` |
| `corridor_policy_version` | `retired_at` | `INTERNAL` | When a successor retired it |
| `corridor_policy_event` | `id` | `INTERNAL` | An event identifier. Generated |
| `corridor_policy_event` | `policy_id` | `INTERNAL` | The moved version - an identifier of a thing |
| `corridor_policy_event` | `from_status` | `INTERNAL` | The edge's origin, NULL at the proposal |
| `corridor_policy_event` | `to_status` | `INTERNAL` | The edge's destination - a closed vocabulary |
| `corridor_policy_event` | `actor_id` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `pricing_policy_version.proposed_by` precedent) |
| `corridor_policy_event` | `reason` | `CONFIDENTIAL` | Free prose by a person about a corridor policy or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `crossborder V002`'s `<table>_<column>_no_instrument_shape` |
| `corridor_policy_event` | `occurred_at` | `INTERNAL` | Application-stamped transition instant |
| `corridor` | `policy_id` | `INTERNAL` | The version it belongs to |
| `corridor` | `source_currency` | `INTERNAL` | An ISO 4217 code |
| `corridor` | `destination_currency` | `INTERNAL` | An ISO 4217 code |
| `corridor` | `destination_country` | `INTERNAL` | An ISO 3166 code - where the platform pays, not where any customer does |
| `corridor` | `rails` | `INTERNAL` | Platform-declared rail ids, in policy order |
| `corridor` | `fee_fixed_minor` | `CONFIDENTIAL` | The platform's price - a commercial term (`pricing_pair.markup`'s reasoning); no customer's amount |
| `corridor` | `fee_margin` | `CONFIDENTIAL` | The platform's price - a commercial term (`pricing_pair.markup`'s reasoning) |
| `corridor` | `fee_rounding` | `INTERNAL` | A closed vocabulary (`RoundingPolicy`) |
| `corridor` | `maximum_minor` | `CONFIDENTIAL` | An operational bound the platform chose, not a person's amount (the `routing_rule.ceiling_amount_minor` reasoning) |
| `corridor` | `screening_validity_hours` | `INTERNAL` | A policy magnitude - hours |
| `corridor` | `delivery_estimate_hours` | `INTERNAL` | A policy magnitude - hours |
| `corridor` | `required_data` | `INTERNAL` | A closed vocabulary - which beneficiary data the corridor requires, never the data |
| `corridor_enable_request` | `id` | `INTERNAL` | A request identifier. Generated |
| `corridor_enable_request` | `corridor` | `INTERNAL` | The corridor's stable code `S-D-CC` - platform configuration |
| `corridor_enable_request` | `status` | `INTERNAL` | An enumeration member |
| `corridor_enable_request` | `proposed_by` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `pricing_policy_version.proposed_by` precedent) |
| `corridor_enable_request` | `proposed_at` | `INTERNAL` | A property of the request |
| `corridor_enable_request` | `proposal_reason` | `CONFIDENTIAL` | Free prose by a person about a corridor policy or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `crossborder V002`'s `<table>_<column>_no_instrument_shape` |
| `corridor_enable_request` | `decided_by` | `CONFIDENTIAL` | Who approved or rejected it - the four-eyes fact (`corridor_enable_request_four_eyes`) |
| `corridor_enable_request` | `decided_at` | `INTERNAL` | When it was decided |
| `corridor_enable_request` | `decision_reason` | `CONFIDENTIAL` | Free prose by a person about a corridor policy or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `crossborder V002`'s `<table>_<column>_no_instrument_shape` |
| `corridor_availability` | `seq` | `INTERNAL` | The facts' server-assigned order - the newest is the corridor's availability |
| `corridor_availability` | `id` | `INTERNAL` | A fact identifier. Generated |
| `corridor_availability` | `corridor` | `INTERNAL` | The corridor's stable code `S-D-CC` |
| `corridor_availability` | `available` | `CONFIDENTIAL` | Whether a corridor is stopped is operational posture - the `rail_availability.available` reasoning |
| `corridor_availability` | `actor_id` | `CONFIDENTIAL` | Who proposed or decided it (`audit_record.actor`'s reasoning - the `pricing_policy_version.proposed_by` precedent) |
| `corridor_availability` | `reason` | `CONFIDENTIAL` | Free prose by a person about a corridor policy or availability decision (`audit_record.reason`'s reasoning). Screened for card-number and account-identifier shapes at the domain and by `crossborder V002`'s `<table>_<column>_no_instrument_shape` |
| `corridor_availability` | `recorded_at` | `INTERNAL` | Application-stamped instant of the act |
| `corridor_availability` | `enable_request_id` | `INTERNAL` | The APPROVED request an enabling fact names; NULL on a disable (`corridor_availability_enabling_is_approved`) |

### `crossborder` - the beneficiary and its corridor selection - *added by `P9-TSK-017`*

No name, no grant and no account identifier is stored here (`INV-RAIL-03`): the name is kyc's,
encrypted; the grant is exchanged and forgotten.

| Table | Column | Level | Note |
|---|---|---|---|
| `corridor_selection` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `corridor_selection` | `policy_id` | `INTERNAL` | The pinned corridor policy version - an identifier of a thing |
| `corridor_selection` | `destination_country` | `CONFIDENTIAL` | Where a customer pays abroad - a fact about their relationships |
| `corridor_selection` | `destination_currency` | `CONFIDENTIAL` | As the country |
| `corridor_selection` | `entity_type` | `CONFIDENTIAL` | Whether the payee is a person or a firm |
| `corridor_selection` | `available_corridors` | `INTERNAL` | Platform configuration observed at the selection |
| `corridor_selection` | `chosen_rail` | `INTERNAL` | A platform-declared rail id |
| `corridor_selection` | `selected_at` | `CONFIDENTIAL` | Dates a customer's registration |
| `corridor_selection_step` | `selection_id` | `INTERNAL` | As `corridor_selection.id` |
| `corridor_selection_step` | `ordinal` | `INTERNAL` | A position |
| `corridor_selection_step` | `corridor` | `INTERNAL` | A platform corridor code |
| `corridor_selection_step` | `rail` | `INTERNAL` | A platform-declared rail id |
| `corridor_selection_step` | `outcome` | `INTERNAL` | An enumeration of five values |
| `beneficiary_registration` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `beneficiary_registration` | `owner_party` | `CONFIDENTIAL` | Whose registration - links a customer to a payee abroad |
| `beneficiary_registration` | `exchange_reference` | `CONFIDENTIAL` | Derived one-way from the owner and the single-use grant (SHA-256, truncated) - never the grant; the provider's idempotency key for the exchange |
| `beneficiary_registration` | `selection_id` | `INTERNAL` | As `corridor_selection.id` |
| `beneficiary_registration` | `rail` | `INTERNAL` | A platform-declared rail id |
| `beneficiary_registration` | `destination_country` | `CONFIDENTIAL` | As `corridor_selection.destination_country` |
| `beneficiary_registration` | `destination_currency` | `CONFIDENTIAL` | As the country |
| `beneficiary_registration` | `entity_type` | `CONFIDENTIAL` | As `corridor_selection.entity_type` |
| `beneficiary_registration` | `created_at` | `CONFIDENTIAL` | Dates a customer's act |
| `crossborder.beneficiary` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `crossborder.beneficiary` | `owner_party` | `CONFIDENTIAL` | As the registration's |
| `crossborder.beneficiary` | `registration_id` | `INTERNAL` | As `beneficiary_registration.id` |
| `crossborder.beneficiary` | `rail` | `INTERNAL` | The issuing rail, frozen |
| `crossborder.beneficiary` | `destination_reference` | `RESTRICTED-FINANCIAL` | The corridor provider's opaque reference for a payee's account (`INV-RAIL-03`) - not an account identifier, but what instructs money to it; shape-`CHECK`ed and instrument-screened |
| `crossborder.beneficiary` | `suffix` | `CONFIDENTIAL` | Four display characters the customer recognises the payee by |
| `crossborder.beneficiary` | `payee_check` | `CONFIDENTIAL` | Whether the name matched the account at the provider |
| `crossborder.beneficiary` | `acknowledged_no_match` | `CONFIDENTIAL` | The customer's acknowledgement of a payee check that did not match |
| `crossborder.beneficiary` | `destination_country` | `CONFIDENTIAL` | Provider-attested (ADR-0080 section 3) |
| `crossborder.beneficiary` | `destination_currency` | `CONFIDENTIAL` | Provider-attested |
| `crossborder.beneficiary` | `entity_type` | `CONFIDENTIAL` | Provider-attested |
| `crossborder.beneficiary` | `nickname` | `CONFIDENTIAL` | The customer's free text naming a payee - screened for card and account shapes at the domain and by `CHECK`; never the payee's name, which is kyc's |
| `crossborder.beneficiary` | `status` | `CONFIDENTIAL` | **The tipping-off column**: `IN_REVIEW` and `BLOCKED` are shown to the customer only shaped (`PENDING_VERIFICATION`, `UNAVAILABLE`) |
| `crossborder.beneficiary` | `screening_id` | `INTERNAL` | kyc's screening identifier - an identifier of a thing |
| `crossborder.beneficiary` | `registered_at` | `CONFIDENTIAL` | Dates a customer's act |
| `crossborder.beneficiary` | `revoked_at` | `CONFIDENTIAL` | Dates a customer's act |
| `beneficiary_status_event` | `id` | `INTERNAL` | An event identifier. Generated |
| `beneficiary_status_event` | `beneficiary_id` | `INTERNAL` | As `beneficiary.id` |
| `beneficiary_status_event` | `from_status` | `CONFIDENTIAL` | As `beneficiary.status` - the history of a review |
| `beneficiary_status_event` | `to_status` | `CONFIDENTIAL` | As `beneficiary.status` |
| `beneficiary_status_event` | `cause` | `CONFIDENTIAL` | Whether the screening or the customer moved it |
| `beneficiary_status_event` | `screening_id` | `INTERNAL` | As `beneficiary.screening_id` |
| `beneficiary_status_event` | `occurred_at` | `CONFIDENTIAL` | Dates a screening outcome or a customer's act |

### `crossborder` - the offer - *added by `P9-TSK-018`*

| Table | Column | Level | Note |
|---|---|---|---|
| `offer_request` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `offer_request` | `claim_key` | `CONFIDENTIAL` | The principal's idempotency scope and key - names the actor |
| `offer_request` | `owner_party` | `CONFIDENTIAL` | Whose request |
| `offer_request` | `beneficiary_id` | `INTERNAL` | As `beneficiary.id` |
| `offer_request` | `corridor_policy_id` | `INTERNAL` | The pinned corridor version |
| `offer_request` | `corridor` | `CONFIDENTIAL` | Where a customer pays abroad |
| `offer_request` | `fixed_side` | `INTERNAL` | An enumeration of two values |
| `offer_request` | `amount_minor` | `RESTRICTED-FINANCIAL` | An amount a customer asked to send (`INV-AUD-02`) |
| `offer_request` | `amount_currency` | `CONFIDENTIAL` | Part of the amount's shape |
| `offer_request` | `rescreen_id` | `INTERNAL` | kyc's re-screen identifier |
| `offer_request` | `created_at` | `CONFIDENTIAL` | Dates a customer's act |
| `payment_offer` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `payment_offer` | `offer_request_id` | `INTERNAL` | As `offer_request.id` |
| `payment_offer` | `quote_id` | `INTERNAL` | fx's quote identifier |
| `payment_offer` | `owner_party` | `CONFIDENTIAL` | Whose offer |
| `payment_offer` | `beneficiary_id` | `INTERNAL` | As `beneficiary.id` |
| `payment_offer` | `corridor_policy_id` | `INTERNAL` | The pinned corridor version |
| `payment_offer` | `corridor` | `CONFIDENTIAL` | Where a customer pays abroad |
| `payment_offer` | `fee_minor` | `RESTRICTED-FINANCIAL` | The corridor fee the customer is charged |
| `payment_offer` | `fee_scale` | `INTERNAL` | Part of the amount's shape |
| `payment_offer` | `source_minor` | `RESTRICTED-FINANCIAL` | What the customer pays for the conversion |
| `payment_offer` | `total_debit_minor` | `RESTRICTED-FINANCIAL` | What the customer is debited - source plus fee, `CHECK`-held |
| `payment_offer` | `source_currency` | `CONFIDENTIAL` | Part of the amount's shape |
| `payment_offer` | `destination_minor` | `RESTRICTED-FINANCIAL` | The guaranteed amount the beneficiary receives |
| `payment_offer` | `destination_scale` | `INTERNAL` | Part of the amount's shape |
| `payment_offer` | `destination_currency` | `CONFIDENTIAL` | Part of the amount's shape |
| `payment_offer` | `delivery_estimate_hours` | `INTERNAL` | Corridor configuration, frozen |
| `payment_offer` | `created_at` | `CONFIDENTIAL` | Dates a customer's act |

### `crossborder` - the payment - *added by `P9-TSK-019`*

| Table | Column | Level | Note |
|---|---|---|---|
| `payment` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `payment` | `owner_party` | `CONFIDENTIAL` | Whose payment |
| `payment` | `beneficiary_id` | `INTERNAL` | As `beneficiary.id` |
| `payment` | `offer_id` | `INTERNAL` | As `payment_offer.id` - one payment per offer |
| `payment` | `quote_id` | `INTERNAL` | fx's quote - one payment per quote, the lock-free arbiter beside fx's own |
| `payment` | `corridor` | `CONFIDENTIAL` | Where a customer pays abroad |
| `payment` | `dispatch_key` | `CONFIDENTIAL` | The principal's idempotency scope and key - names the actor |
| `payment` | `outbound_credit_id` | `INTERNAL` | payments' outbound credit |
| `payment` | `cover_id` | `INTERNAL` | fx's cover |
| `payment` | `hold_id` | `INTERNAL` | The ledger hold of the total debit |
| `payment` | `status` | `CONFIDENTIAL` | The machine's word, shown shaped (`PROCESSING`, `SENT`, ...) |
| `payment` | `failure_reason` | `CONFIDENTIAL` | An enumerated failure class - a fact about a person's payment, never provider text |
| `payment` | `created_at` | `CONFIDENTIAL` | Dates a customer's act |
| `payment_event` | `id` | `INTERNAL` | An event identifier. Generated |
| `payment_event` | `payment_id` | `INTERNAL` | As `payment.id` |
| `payment_event` | `from_status` | `CONFIDENTIAL` | As `payment.status` |
| `payment_event` | `to_status` | `CONFIDENTIAL` | As `payment.status` |
| `payment_event` | `cause` | `INTERNAL` | A bounded machine word (`AUTHORIZED`, ...) |
| `payment_event` | `occurred_at` | `CONFIDENTIAL` | Dates a customer's payment |
| `cancellation_request` | `id` | `INTERNAL` | A fact identifier. Generated (`P9-TSK-024`) |
| `cancellation_request` | `payment_id` | `INTERNAL` | The payment whose recall was requested - one request per payment (`UNIQUE`) |
| `cancellation_request` | `requested_by` | `CONFIDENTIAL` | The requesting actor's identifier - the customer |
| `cancellation_request` | `correlation_id` | `INTERNAL` | The request's flow |
| `cancellation_request` | `requested_at` | `CONFIDENTIAL` | Dates a customer's act, stamped by the database |

### `payments` - the outbound credit and routing's third subject - *added by `P9-TSK-019`*

| Table | Column | Level | Note |
|---|---|---|---|
| `outbound_credit` | `id` | `INTERNAL` | An aggregate identifier. Generated |
| `outbound_credit` | `customer_party_id` | `CONFIDENTIAL` | Whose money leaves |
| `outbound_credit` | `subject_id` | `INTERNAL` | The cross-border payment it instructs - one credit per payment |
| `outbound_credit` | `dispatch_key` | `CONFIDENTIAL` | The principal's idempotency scope and key, unique per customer |
| `outbound_credit` | `rail` | `INTERNAL` | The routed corridor rail's stored literal |
| `outbound_credit` | `destination_reference` | `CONFIDENTIAL` | The corridor provider's opaque beneficiary reference - never an account identifier (`CHECK`-shaped) |
| `outbound_credit` | `amount_minor` | `RESTRICTED-FINANCIAL` | The instructed destination amount - the guaranteed amount the customer was shown |
| `outbound_credit` | `amount_currency` | `CONFIDENTIAL` | Part of the monetary shape |
| `outbound_credit` | `amount_scale` | `INTERNAL` | Part of the monetary shape |
| `outbound_credit` | `held_minor` | `RESTRICTED-FINANCIAL` | The total debit held on the customer's wallet |
| `outbound_credit` | `held_currency` | `CONFIDENTIAL` | Part of the monetary shape |
| `outbound_credit` | `held_scale` | `INTERNAL` | Part of the monetary shape |
| `outbound_credit` | `hold_id` | `INTERNAL` | The ledger hold |
| `outbound_credit` | `end_to_end_reference` | `CONFIDENTIAL` | OUR reference, minted once and stored before any send - the provider's dedupe key and Phase 8's match key (`INV-PAY-04`) |
| `outbound_credit` | `status` | `INTERNAL` | The machine's word |
| `outbound_credit` | `failure_reason` | `CONFIDENTIAL` | An enumerated failure class - `payment_attempt.failure_reason`'s reasoning |
| `outbound_credit` | `provider_reference` | `CONFIDENTIAL` | The corridor provider's own reference for the credit |
| `outbound_credit` | `delivered_at` | `CONFIDENTIAL` | Dates a delivery to a person |
| `outbound_credit` | `recall_requested_at` | `INTERNAL` | Operational timing |
| `outbound_credit` | `recall_outcome` | `INTERNAL` | An enumerated outcome |
| `outbound_credit` | `created_at` | `CONFIDENTIAL` | Dates a person's act |
| `outbound_credit` | `last_dispatched_at` | `INTERNAL` | The send permit, stamped by the database |
| `outbound_credit` | `last_inquired_at` | `INTERNAL` | The sweep's inquiry stamp, set by the database when the sweep claims the credit (payments `V030`, the Phase 9 → 10 transition) |
| `routing_rule` | `requires_destination_country` | `INTERNAL` | A matcher flag |
| `routing_decision` | `outbound_credit_id` | `INTERNAL` | The decision's third subject - exactly one of intent, withdrawal and outbound credit |
| `routing_decision` | `destination_country` | `CONFIDENTIAL` | Where a customer pays abroad |
| `routing_decision` | `reachable_rails` | `INTERNAL` | The rails that reach the destination, as judged |
| `provider_evidence` | `outbound_credit_id` | `INTERNAL` | The evidence's sixth subject; the bytes' own rows carry the classification that matters |

### `payments` - the outbound credit's return - *added by `P9-TSK-023`*

| Table | Column | Level | Note |
|---|---|---|---|
| `outbound_credit_return` | `id` | `INTERNAL` | A fact identifier. Generated |
| `outbound_credit_return` | `outbound_credit_id` | `INTERNAL` | The credit it returns - one return per credit (`UNIQUE`) |
| `outbound_credit_return` | `amount_minor` | `RESTRICTED-FINANCIAL` | The returned amount - for `APPLIER`, the instructed amount exactly (trigger) |
| `outbound_credit_return` | `amount_currency` | `CONFIDENTIAL` | Part of the monetary shape |
| `outbound_credit_return` | `amount_scale` | `INTERNAL` | Part of the monetary shape |
| `outbound_credit_return` | `return_reference` | `CONFIDENTIAL` | The corridor provider's own reference for the return, when the inquiry carried one |
| `outbound_credit_return` | `applied_by` | `INTERNAL` | `APPLIER` or `RESOLUTION` - which path recorded the fact |
| `outbound_credit_return` | `resolution_id` | `INTERNAL` | The four-eyes resolution that returned it (`RESOLUTION` only) |
| `outbound_credit_return` | `journal_entry_id` | `INTERNAL` | The return's entry (`APPLIER`) or the fee refund's (`RESOLUTION`) |
| `outbound_credit_return` | `returned_at` | `CONFIDENTIAL` | Dates money coming back to a person |
| `outbound_credit_return` | `created_at` | `INTERNAL` | System time, stamped by the trigger |

### `credit` - the reason-code catalogue - *added by `P10-TSK-001`*

The closed, migration-seeded catalogue a decision's reasons cite (`INV-CRD-02`). Platform reference
data, identical for every party: no row names a person, a figure or a bureau datum, and the customer
texts are written to be shown to the applicant. `SELECT` only to the application; never updated or
deleted by any role.

| Table | Column | Level | Note |
|---|---|---|---|
| `reason_code` | `code` | `INTERNAL` | The closed code (`CRD-...`), mirrored by the `ReasonCode` enum both ways |
| `reason_code` | `category` | `INTERNAL` | The code's family - a closed list (`CHECK`) |
| `reason_code` | `customer_text` | `INTERNAL` | Fixed platform wording, shown to the applicant for an adverse code - never a score, threshold or bureau datum |
| `reason_code` | `adverse` | `INTERNAL` | Whether the code explains a judgement against the applicant |

### `credit` - the credit profile - *added by `P10-TSK-004`*

One row per party and no figure (`INV-CRD-04`): the lock every deciding transaction for the party takes
first (`INV-CRD-09`). Never updated, deleted or truncated by any role.

| Table | Column | Level | Note |
|---|---|---|---|
| `credit_profile` | `id` | `INTERNAL` | A lock target's identifier. Generated |
| `credit_profile` | `party_id` | `CONFIDENTIAL` | Which party the platform holds a credit profile for - that a person sought credit at all |
| `credit_profile` | `created_at` | `INTERNAL` | System time, stamped by the trigger |

### `credit` - credit data collection - *added by `P10-TSK-006`*

A party's credit data as a bureau or a financial-data provider reported it (one `credit_record` table for both
source kinds; "as a bureau reported it" until the Phase 10 exit review, `P10-DOC-001`, 2026-10-09): the attribute
values and the payload are
`RESTRICTED-FINANCIAL`; the payload is ciphertext under credit's own key, unreadable by the application
role except through `credit.read_evidence` with a reason.

| Table | Column | Level | Note |
|---|---|---|---|
| `data_request` | `id` | `INTERNAL` | Generated |
| `data_request` | `decision_request_id` | `INTERNAL` | The decision request served |
| `data_request` | `party_id` | `CONFIDENTIAL` | That this party's credit data was sought |
| `data_request` | `product` | `INTERNAL` | A closed product code |
| `data_request` | `source_kind` | `INTERNAL` | `BUREAU` or `FINANCIAL_DATA` |
| `data_request` | `provider_code` | `INTERNAL` | A declared provider code |
| `data_request` | `request_reference` | `INTERNAL` | Our reference - the provider's idempotency key |
| `data_request` | `status` | `INTERNAL` | The machine's state |
| `data_request` | `attempts` | `INTERNAL` | A count |
| `data_request` | `retry_cadence` | `INTERNAL` | Configuration, frozen at birth |
| `data_request` | `collection_window` | `INTERNAL` | Configuration, frozen at birth |
| `data_request` | `next_attempt_at` | `INTERNAL` | The permit, on the database's clock |
| `data_request` | `requested_at` | `INTERNAL` | System time, stamped by the trigger |
| `data_request` | `deadline_at` | `INTERNAL` | System time, stamped by the trigger |
| `data_request` | `unavailable_reported` | `INTERNAL` | A one-way flag |
| `data_request_attempt` | `data_request_id` | `INTERNAL` | The request attempted |
| `data_request_attempt` | `attempt` | `INTERNAL` | A count |
| `data_request_attempt` | `outcome` | `INTERNAL` | A closed outcome - never data |
| `data_request_attempt` | `answered_at` | `INTERNAL` | System time |
| `credit_record` | `id` | `INTERNAL` | Generated |
| `credit_record` | `data_request_id` | `INTERNAL` | One record per request (`UNIQUE`) |
| `credit_record` | `party_id` | `CONFIDENTIAL` | Whose credit report this is |
| `credit_record` | `source_kind` | `INTERNAL` | A closed code |
| `credit_record` | `provider_code` | `INTERNAL` | The provenance (`INV-CRD-07`) |
| `credit_record` | `normaliser_version` | `INTERNAL` | The provenance (`INV-CRD-07`) |
| `credit_record` | `complete` | `INTERNAL` | Whether any attribute was absent |
| `credit_record` | `retrieved_at` | `CONFIDENTIAL` | When the bureau produced the person's report |
| `credit_record` | `recorded_at` | `INTERNAL` | System time, stamped by the trigger |
| `credit_record_attribute` | `record_id` | `INTERNAL` | The record it belongs to |
| `credit_record_attribute` | `code` | `INTERNAL` | A closed attribute code |
| `credit_record_attribute` | `value_type` | `INTERNAL` | A closed type |
| `credit_record_attribute` | `integer_value` | `RESTRICTED-FINANCIAL` | A person's score, account or delinquency count |
| `credit_record_attribute` | `money_minor` | `RESTRICTED-FINANCIAL` | A person's obligations or balance |
| `credit_record_attribute` | `money_currency` | `CONFIDENTIAL` | Part of the monetary shape |
| `credit_record_attribute` | `money_scale` | `INTERNAL` | Part of the monetary shape |
| `credit_record_attribute` | `boolean_value` | `RESTRICTED-FINANCIAL` | A person's insolvency flag |
| `credit_record_attribute` | `code_value` | `CONFIDENTIAL` | A closed code - a marker's source kind, a residency, a risk answer |
| `credit_record_attribute` | `absent` | `CONFIDENTIAL` | That a person's bureau did not report a figure |
| `credit_evidence` | `id` | `INTERNAL` | Generated - and the ciphertext's associated data |
| `credit_evidence` | `data_request_id` | `INTERNAL` | The request it answered |
| `credit_evidence` | `attempt` | `INTERNAL` | A count |
| `credit_evidence` | `duplicate` | `INTERNAL` | An answer that changed nothing |
| `credit_evidence` | `consent_withdrawn` | `INTERNAL` | An answer discarded unread |
| `credit_evidence` | `content_ciphertext` | `RESTRICTED-FINANCIAL` | The bureau's bytes, AES-256-GCM - never readable by the application role |
| `credit_evidence` | `content_nonce` | `INTERNAL` | GCM's nonce |
| `credit_evidence` | `key_version` | `INTERNAL` | Which key wrote it |
| `credit_evidence` | `checksum_sha256` | `CONFIDENTIAL` | The plaintext's digest - comparable, so not public |
| `credit_evidence` | `content_length` | `INTERNAL` | A length |
| `credit_evidence` | `retention_months` | `INTERNAL` | The product's retention |
| `credit_evidence` | `retain_until` | `INTERNAL` | System time plus the retention |
| `credit_evidence` | `recorded_at` | `INTERNAL` | System time, stamped by the trigger |

### `credit` - the decision snapshot - *added by `P10-TSK-008`*

Everything a decision may read, frozen: the canonical text carries every attribute value of the applicant, so it is
`RESTRICTED-FINANCIAL` and never reaches a log, a span or an event.

| Table | Column | Level | Note |
|---|---|---|---|
| `decision_snapshot` | `id` | `INTERNAL` | Generated |
| `decision_snapshot` | `decision_request_id` | `INTERNAL` | The decision request frozen |
| `decision_snapshot` | `sequence` | `INTERNAL` | 1 at the freeze; a successor only for a changed exposure |
| `decision_snapshot` | `snapshot_format` | `INTERNAL` | The canonical form's version |
| `decision_snapshot` | `canonical` | `RESTRICTED-FINANCIAL` | Every input of the applicant's decision, with provenance |
| `decision_snapshot` | `content_sha256` | `CONFIDENTIAL` | The canonical text's digest - comparable, so not public |
| `decision_snapshot` | `policy_version_id` | `INTERNAL` | The pinned policy version |
| `decision_snapshot` | `model_version_id` | `INTERNAL` | The pinned scorecard version |
| `decision_snapshot` | `engine_version` | `INTERNAL` | The evaluator's version |
| `decision_snapshot` | `frozen_at` | `INTERNAL` | System time, stamped by the trigger |

### `credit` - the scorecard model and the assessment - *added by `P10-TSK-011`*

The model is platform configuration - no person in it beyond who proposed and decided it; the assessment holds
an applicant's figures, so it is `RESTRICTED-FINANCIAL` and none of it reaches a log, a span or an event.

| Table | Column | Level | Note |
|---|---|---|---|
| `scorecard_model_version` | `id` | `INTERNAL` | Generated; v1 fixed by the seed |
| `scorecard_model_version` | `family` | `INTERNAL` | The model family |
| `scorecard_model_version` | `version` | `INTERNAL` | Numbered max + 1 per family |
| `scorecard_model_version` | `status` | `INTERNAL` | The machine state |
| `scorecard_model_version` | `base_points` | `INTERNAL` | Model configuration, no person in it |
| `scorecard_model_version` | `proposed_by` | `CONFIDENTIAL` | Who proposed it - `migration:V006` for the seed (the `corridor_policy_version.proposed_by` precedent) |
| `scorecard_model_version` | `proposed_at` | `INTERNAL` | System time, stamped by the trigger |
| `scorecard_model_version` | `proposal_reason` | `CONFIDENTIAL` | Free prose by a person about a model decision (`audit_record.reason`'s reasoning) |
| `scorecard_model_version` | `decided_by` | `CONFIDENTIAL` | Who activated or rejected it - the four-eyes fact (`scorecard_model_four_eyes`) |
| `scorecard_model_version` | `decided_at` | `INTERNAL` | System time, stamped by the trigger |
| `scorecard_model_version` | `decision_reason` | `CONFIDENTIAL` | Free prose by a person, as `proposal_reason` |
| `scorecard_model_version` | `effective_from` | `INTERNAL` | The activation's database instant |
| `scorecard_model_version` | `effective_to` | `INTERNAL` | The retirement's database instant, never at or before the version's start (`X-TSK-017`) - the successor's start |
| `scorecard_band` | `model_version_id` | `INTERNAL` | The version it was born with |
| `scorecard_band` | `attribute_code` | `INTERNAL` | A vocabulary code |
| `scorecard_band` | `ordinal` | `INTERNAL` | 0 for the absent band, 1.. in order |
| `scorecard_band` | `kind` | `INTERNAL` | `ABSENT`, `RANGE` or `CODES` |
| `scorecard_band` | `lower_bound` | `INTERNAL` | Model configuration |
| `scorecard_band` | `upper_bound` | `INTERNAL` | Model configuration |
| `scorecard_band` | `codes` | `INTERNAL` | Model configuration |
| `scorecard_band` | `points` | `INTERNAL` | Model configuration |
| `scorecard_model_event` | `id` | `INTERNAL` | Generated |
| `scorecard_model_event` | `model_version_id` | `INTERNAL` | The version moved |
| `scorecard_model_event` | `from_status` | `INTERNAL` | The machine edge |
| `scorecard_model_event` | `to_status` | `INTERNAL` | The machine edge |
| `scorecard_model_event` | `actor_id` | `CONFIDENTIAL` | Who moved it |
| `scorecard_model_event` | `reason` | `CONFIDENTIAL` | Free prose by a person, as `proposal_reason` |
| `scorecard_model_event` | `occurred_at` | `INTERNAL` | System time, stamped by the trigger |
| `credit_assessment` | `id` | `INTERNAL` | Generated |
| `credit_assessment` | `snapshot_id` | `INTERNAL` | The snapshot assessed - once |
| `credit_assessment` | `decision_request_id` | `INTERNAL` | The decision request |
| `credit_assessment` | `snapshot_sha256` | `CONFIDENTIAL` | The inputs' digest - comparable, so not public |
| `credit_assessment` | `policy_version_id` | `INTERNAL` | The pinned policy version |
| `credit_assessment` | `model_version_id` | `INTERNAL` | The pinned scorecard version |
| `credit_assessment` | `engine_version` | `INTERNAL` | The engine's version |
| `credit_assessment` | `currency` | `INTERNAL` | The product's currency |
| `credit_assessment` | `affordability_assessed` | `RESTRICTED-FINANCIAL` | Whether the applicant's affordability could be judged |
| `credit_assessment` | `income_minor` | `RESTRICTED-FINANCIAL` | The applicant's affordability figure |
| `credit_assessment` | `expenditure_minor` | `RESTRICTED-FINANCIAL` | The applicant's affordability figure |
| `credit_assessment` | `obligations_minor` | `RESTRICTED-FINANCIAL` | The applicant's affordability figure |
| `credit_assessment` | `repayment_minor` | `RESTRICTED-FINANCIAL` | The applicant's affordability figure |
| `credit_assessment` | `disposable_minor` | `RESTRICTED-FINANCIAL` | The applicant's affordability figure |
| `credit_assessment` | `affordable` | `RESTRICTED-FINANCIAL` | The applicant's affordability verdict |
| `credit_assessment` | `affordability_absent` | `RESTRICTED-FINANCIAL` | Which of the applicant's inputs were absent |
| `credit_assessment` | `exposure_assessed` | `RESTRICTED-FINANCIAL` | Whether the applicant's exposure could be judged |
| `credit_assessment` | `exposure_minor` | `RESTRICTED-FINANCIAL` | The applicant's exposure figure |
| `credit_assessment` | `headroom_minor` | `RESTRICTED-FINANCIAL` | The applicant's exposure figure |
| `credit_assessment` | `within_limit` | `RESTRICTED-FINANCIAL` | The applicant's exposure verdict |
| `credit_assessment` | `exposure_absent` | `RESTRICTED-FINANCIAL` | Which of the applicant's inputs were absent |
| `credit_assessment` | `score` | `RESTRICTED-FINANCIAL` | The applicant's score - a figure, never a decision (`INV-CRD-04`) |
| `credit_assessment` | `assessed_at` | `INTERNAL` | System time, stamped by the trigger |

### `credit` - the credit policy - *added by `P10-TSK-012`*
A policy is platform configuration - no person in it beyond who proposed and decided it - but its THRESHOLDS are
the lending rulebook: knowing them is knowing how to game a decision, so they are `CONFIDENTIAL` and leave the
platform only on the operator surface (`GET /v1/operator/credit/policies`, `CREDIT_INVESTIGATE`), never in an event,
a log, a span or a customer's explanation (which names reason codes alone, `INV-CRD-02`).

| Table | Column | Level | Note |
|---|---|---|---|
| `credit_policy_version` | `id` | `INTERNAL` | Generated; each product's v1 fixed by the seed |
| `credit_policy_version` | `product` | `INTERNAL` | The offered product |
| `credit_policy_version` | `version` | `INTERNAL` | Numbered max + 1 per product |
| `credit_policy_version` | `status` | `INTERNAL` | The machine state |
| `credit_policy_version` | `currency` | `INTERNAL` | The product's currency, stated once for every amount |
| `credit_policy_version` | `scale` | `INTERNAL` | The currency's scale, stated with it (`INV-MON-03`) |
| `credit_policy_version` | `assessment_rate_bps` | `CONFIDENTIAL` | A threshold: the affordability stress rate |
| `credit_policy_version` | `minimum_disposable_minor` | `CONFIDENTIAL` | A threshold |
| `credit_policy_version` | `minimum_payment_ratio_bps` | `CONFIDENTIAL` | A threshold |
| `credit_policy_version` | `maximum_exposure_minor` | `CONFIDENTIAL` | A threshold |
| `credit_policy_version` | `max_data_age_bureau_seconds` | `INTERNAL` | Which sources the policy reads, and how stale it accepts |
| `credit_policy_version` | `max_data_age_financial_data_seconds` | `INTERNAL` | As the bureau's |
| `credit_policy_version` | `unavailable_fallback` | `INTERNAL` | `REFER` or `DECLINE` - never approve (`INV-CRD-10`) |
| `credit_policy_version` | `auto_approval_ceiling_minor` | `CONFIDENTIAL` | A threshold |
| `credit_policy_version` | `proposed_by` | `CONFIDENTIAL` | Who proposed it - `migration:V008` for the seeds |
| `credit_policy_version` | `proposed_at` | `INTERNAL` | System time, stamped by the trigger |
| `credit_policy_version` | `proposal_reason` | `CONFIDENTIAL` | Free prose by a person about a policy decision |
| `credit_policy_version` | `decided_by` | `CONFIDENTIAL` | Who activated or rejected it - the four-eyes fact (`credit_policy_four_eyes`) |
| `credit_policy_version` | `decided_at` | `INTERNAL` | System time, stamped by the trigger |
| `credit_policy_version` | `decision_reason` | `CONFIDENTIAL` | Free prose by a person, as `proposal_reason` |
| `credit_policy_version` | `effective_from` | `INTERNAL` | The activation's database instant |
| `credit_policy_version` | `effective_to` | `INTERNAL` | The retirement's database instant, never at or before the version's start (`X-TSK-017`) - the successor's start |
| `credit_policy_rule` | `policy_version_id` | `INTERNAL` | The version it was born with |
| `credit_policy_rule` | `ordinal` | `INTERNAL` | Its place in the rule list, from 1 |
| `credit_policy_rule` | `rule_code` | `INTERNAL` | The rule's name |
| `credit_policy_rule` | `subject_kind` | `INTERNAL` | `ATTRIBUTE` or `FIGURE` |
| `credit_policy_rule` | `subject` | `INTERNAL` | A vocabulary code |
| `credit_policy_rule` | `operator` | `INTERNAL` | A closed operator |
| `credit_policy_rule` | `operand_integer` | `CONFIDENTIAL` | A threshold |
| `credit_policy_rule` | `operand_money_minor` | `CONFIDENTIAL` | A threshold |
| `credit_policy_rule` | `operand_boolean` | `CONFIDENTIAL` | A threshold |
| `credit_policy_rule` | `operand_codes` | `CONFIDENTIAL` | A threshold - a set of codes |
| `credit_policy_rule` | `effect` | `INTERNAL` | A closed effect |
| `credit_policy_rule` | `cap_amount_minor` | `CONFIDENTIAL` | A threshold: the cap |
| `credit_policy_rule` | `reason_code` | `INTERNAL` | A catalogued code (`INV-CRD-02`) |
| `credit_policy_event` | `id` | `INTERNAL` | Generated |
| `credit_policy_event` | `policy_version_id` | `INTERNAL` | The version moved |
| `credit_policy_event` | `from_status` | `INTERNAL` | The machine edge |
| `credit_policy_event` | `to_status` | `INTERNAL` | The machine edge |
| `credit_policy_event` | `actor_id` | `CONFIDENTIAL` | Who moved it |
| `credit_policy_event` | `reason` | `CONFIDENTIAL` | Free prose by a person, as `proposal_reason` |
| `credit_policy_event` | `occurred_at` | `INTERNAL` | System time, stamped by the trigger |

### `credit` - the policy evaluation - *added by `P10-TSK-013`*

What the pinned policy concluded about one assessment: its outcome, amounts and reason codes describe the applicant, so
they are `RESTRICTED-FINANCIAL`; which rules triggered, read with the policy, bound its thresholds, so `CONFIDENTIAL`.
*(The rows are unchanged; they sat under the credit policy's heading until the Phase 10 exit review, `P10-DOC-001`,
2026-10-09, gave them their own.)*

| Table | Column | Level | Note |
|---|---|---|---|
| `policy_evaluation` | `id` | `INTERNAL` | Generated |
| `policy_evaluation` | `assessment_id` | `INTERNAL` | The assessment evaluated - once |
| `policy_evaluation` | `decision_request_id` | `INTERNAL` | The decision request |
| `policy_evaluation` | `policy_version_id` | `INTERNAL` | The pinned policy version |
| `policy_evaluation` | `engine_version` | `INTERNAL` | The engine's version - the one replay selects |
| `policy_evaluation` | `outcome` | `RESTRICTED-FINANCIAL` | The evaluator's word on the applicant - never the decision (`INV-CRD-04`) |
| `policy_evaluation` | `currency` | `INTERNAL` | The product's currency |
| `policy_evaluation` | `requested_minor` | `RESTRICTED-FINANCIAL` | The applicant's requested amount |
| `policy_evaluation` | `approved_minor` | `RESTRICTED-FINANCIAL` | The amount an approval would grant |
| `policy_evaluation` | `reason_codes` | `RESTRICTED-FINANCIAL` | Catalogued codes - what they say of the applicant (`INV-CRD-02`) |
| `policy_evaluation` | `fallback_applied` | `RESTRICTED-FINANCIAL` | Whether the applicant's data was missing (`INV-CRD-10`) |
| `policy_evaluation` | `evaluated_at` | `INTERNAL` | System time, stamped by the trigger |
| `policy_evaluation_rule` | `evaluation_id` | `INTERNAL` | The evaluation it was born with |
| `policy_evaluation_rule` | `ordinal` | `INTERNAL` | The rule's place, from 1 |
| `policy_evaluation_rule` | `rule_code` | `INTERNAL` | The rule's name |
| `policy_evaluation_rule` | `effect` | `INTERNAL` | A closed effect |
| `policy_evaluation_rule` | `triggered` | `CONFIDENTIAL` | Which rules held - read with the policy, they bound the thresholds |
| `policy_evaluation_rule` | `assessed` | `CONFIDENTIAL` | Which rules read a missing value |

### `credit` - the decision request - *added by `P10-TSK-014`*

A customer's application: the requested terms and the declared income and expenditure are `RESTRICTED-FINANCIAL`, never
in an event or a view; where the request stands is `CONFIDENTIAL`.
*(The rows are unchanged; they sat under the credit policy's heading until the Phase 10 exit review, `P10-DOC-001`,
2026-10-09, gave them their own.)*

| Table | Column | Level | Note |
|---|---|---|---|
| `decision_request` | `id` | `INTERNAL` | Generated |
| `decision_request` | `party_id` | `CONFIDENTIAL` | The applicant's party - an identifier, owner-scoped in every customer read - that this party sought credit, as for `credit_profile`, `data_request` and `credit_record` (`INTERNAL` until the Phase 10 exit review, `P10-DOC-001`, aligned it with its siblings) |
| `decision_request` | `profile_id` | `INTERNAL` | The party's credit profile |
| `decision_request` | `product` | `INTERNAL` | A closed product |
| `decision_request` | `currency` | `INTERNAL` | The product's currency |
| `decision_request` | `requested_minor` | `RESTRICTED-FINANCIAL` | The applicant's requested amount |
| `decision_request` | `term_months` | `RESTRICTED-FINANCIAL` | The applicant's requested term |
| `decision_request` | `declared_income_minor` | `RESTRICTED-FINANCIAL` | The applicant's declared income - never in an event or a view |
| `decision_request` | `declared_expenditure_minor` | `RESTRICTED-FINANCIAL` | The applicant's declared expenditure - never in an event or a view |
| `decision_request` | `status` | `CONFIDENTIAL` | Where the applicant's request stands |
| `decision_request` | `closure_reason` | `CONFIDENTIAL` | Why the platform abandoned it - standing lost or consent withdrawn |
| `decision_request` | `request_validity` | `INTERNAL` | The product's declared validity, frozen |
| `decision_request` | `submitted_at` | `INTERNAL` | System time, stamped by the trigger |
| `decision_request` | `expires_at` | `INTERNAL` | System time, stamped by the trigger |
| `decision_request` | `next_step_at` | `INTERNAL` | The progress permit, on the database clock |
| `decision_request` | `pinned_policy_version_id` | `INTERNAL` | The pinned policy version |
| `decision_request` | `pinned_model_version_id` | `INTERNAL` | The pinned scorecard version |
| `decision_request` | `pinned_engine_version` | `INTERNAL` | The pinned engine version |
| `decision_request` | `correlation_id` | `INTERNAL` | The submission's correlation |
| `decision_request_event` | `id` | `INTERNAL` | Generated |
| `decision_request_event` | `decision_request_id` | `INTERNAL` | The request moved |
| `decision_request_event` | `from_status` | `CONFIDENTIAL` | The machine edge |
| `decision_request_event` | `to_status` | `CONFIDENTIAL` | The machine edge |
| `decision_request_event` | `actor_id` | `CONFIDENTIAL` | Who moved it - the applicant, the platform or a person |
| `decision_request_event` | `actor_type` | `INTERNAL` | A closed actor type |
| `decision_request_event` | `reason` | `CONFIDENTIAL` | Free prose where an edge carries one |
| `decision_request_event` | `occurred_at` | `INTERNAL` | System time, stamped by the trigger |

### `credit` - the credit decision - *added by `P10-TSK-016`*

What was decided about a person - the outcome, the amounts and the reasons - is `RESTRICTED-FINANCIAL`; the consumption
fact, created empty with Phase 11 its only writer, holds identifiers and a stamp.
*(The rows are unchanged; they sat under the credit policy's heading until the Phase 10 exit review, `P10-DOC-001`,
2026-10-09, gave them their own.)*

| Table | Column | Level | Note |
|---|---|---|---|
| `credit_decision` | `id` | `INTERNAL` | Generated |
| `credit_decision` | `decision_request_id` | `INTERNAL` | The request decided - once |
| `credit_decision` | `party_id` | `CONFIDENTIAL` | The applicant's party - that this party sought credit, as for `credit_profile`, `data_request` and `credit_record` (`INTERNAL` until the Phase 10 exit review, `P10-DOC-001`, aligned it with its siblings) |
| `credit_decision` | `profile_id` | `INTERNAL` | The party's credit profile |
| `credit_decision` | `product` | `INTERNAL` | A closed product |
| `credit_decision` | `snapshot_id` | `INTERNAL` | The snapshot it was made from |
| `credit_decision` | `snapshot_sha256` | `CONFIDENTIAL` | The inputs' digest - comparable, so not public |
| `credit_decision` | `outcome` | `RESTRICTED-FINANCIAL` | The decision on the applicant |
| `credit_decision` | `currency` | `INTERNAL` | The product's currency |
| `credit_decision` | `requested_minor` | `RESTRICTED-FINANCIAL` | The applicant's requested amount |
| `credit_decision` | `approved_minor` | `RESTRICTED-FINANCIAL` | The amount approved - the exposure reserved |
| `credit_decision` | `term_months` | `RESTRICTED-FINANCIAL` | The term approved |
| `credit_decision` | `decision_validity` | `INTERNAL` | The product's decision validity, frozen |
| `credit_decision` | `decided_at` | `INTERNAL` | System time, stamped by the trigger |
| `credit_decision` | `valid_until` | `INTERNAL` | System time plus the validity, stamped by the trigger |
| `credit_decision` | `decided_by` | `CONFIDENTIAL` | The platform, or the person who decided |
| `credit_decision` | `decided_by_type` | `INTERNAL` | SYSTEM or EMPLOYEE |
| `credit_decision` | `policy_version_id` | `INTERNAL` | The pinned policy version |
| `credit_decision` | `model_version_id` | `INTERNAL` | The pinned scorecard version |
| `credit_decision` | `engine_version` | `INTERNAL` | The pinned engine version |
| `credit_decision_reason` | `decision_id` | `INTERNAL` | The decision it explains |
| `credit_decision_reason` | `ordinal` | `INTERNAL` | Its place, from 1 |
| `credit_decision_reason` | `reason_code` | `RESTRICTED-FINANCIAL` | A catalogued code - what it says of the applicant (`INV-CRD-02`) |
| `credit_decision_consumption` | `id` | `INTERNAL` | Generated |
| `credit_decision_consumption` | `decision_id` | `INTERNAL` | The approval consumed - once |
| `credit_decision_consumption` | `consumed_at` | `INTERNAL` | System time, stamped by the trigger |

### `credit` - the underwriting case - *added by `P10-TSK-018`*

A person's review of a referred request: the decisions, amounts and reason codes it carries say what was decided about
the applicant, so they are `RESTRICTED-FINANCIAL`; who held and decided it, and their prose, are `CONFIDENTIAL`.
*(The rows are unchanged; they sat under the credit policy's heading until the Phase 10 exit review, `P10-DOC-001`,
2026-10-09, gave them their own.)*

| Table | Column | Level | Note |
|---|---|---|---|
| `underwriting_case` | `id` | `INTERNAL` | Generated |
| `underwriting_case` | `decision_request_id` | `INTERNAL` | The referred request - once |
| `underwriting_case` | `party_id` | `CONFIDENTIAL` | The applicant's party - that this party sought credit, as for `credit_profile`, `data_request` and `credit_record` (`INTERNAL` until the Phase 10 exit review, `P10-DOC-001`, aligned it with its siblings) |
| `underwriting_case` | `product` | `INTERNAL` | A closed product |
| `underwriting_case` | `currency` | `INTERNAL` | The product's currency |
| `underwriting_case` | `basis_evaluation_id` | `INTERNAL` | The REFER evaluation kept as the case's basis |
| `underwriting_case` | `requested_minor` | `RESTRICTED-FINANCIAL` | The applicant's requested amount |
| `underwriting_case` | `approvable_minor` | `RESTRICTED-FINANCIAL` | The referral's ceiling - the most a person may approve |
| `underwriting_case` | `four_eyes_threshold_minor` | `INTERNAL` | The product's published threshold, copied at birth |
| `underwriting_case` | `status` | `CONFIDENTIAL` | The machine state |
| `underwriting_case` | `assignee` | `CONFIDENTIAL` | The underwriter holding it |
| `underwriting_case` | `first_outcome` | `RESTRICTED-FINANCIAL` | A person's decision on the applicant |
| `underwriting_case` | `first_approved_minor` | `RESTRICTED-FINANCIAL` | The amount a person approved |
| `underwriting_case` | `first_reason_codes` | `RESTRICTED-FINANCIAL` | Catalogued codes - what they say of the applicant (`INV-CRD-11`) |
| `underwriting_case` | `first_reason` | `CONFIDENTIAL` | Free prose by a person about the applicant |
| `underwriting_case` | `first_decided_by` | `CONFIDENTIAL` | The underwriter who decided |
| `underwriting_case` | `first_decided_at` | `INTERNAL` | System time, stamped by the trigger |
| `underwriting_case` | `second_decided_by` | `CONFIDENTIAL` | The second underwriter - never the first |
| `underwriting_case` | `second_decided_at` | `INTERNAL` | System time, stamped by the trigger |
| `underwriting_case` | `closure_reason` | `CONFIDENTIAL` | Why it closed with its request |
| `underwriting_case` | `opened_at` | `INTERNAL` | System time, stamped by the trigger |
| `underwriting_case_event` | `id` | `INTERNAL` | Generated |
| `underwriting_case_event` | `case_id` | `INTERNAL` | The case moved |
| `underwriting_case_event` | `from_status` | `CONFIDENTIAL` | The machine edge |
| `underwriting_case_event` | `to_status` | `CONFIDENTIAL` | The machine edge |
| `underwriting_case_event` | `actor_id` | `CONFIDENTIAL` | Who moved it - the platform or an underwriter |
| `underwriting_case_event` | `actor_type` | `INTERNAL` | A closed actor type |
| `underwriting_case_event` | `outcome` | `RESTRICTED-FINANCIAL` | A decision's outcome, as the edge carried it |
| `underwriting_case_event` | `approved_minor` | `RESTRICTED-FINANCIAL` | A decision's amount, as the edge carried it |
| `underwriting_case_event` | `reason_codes` | `RESTRICTED-FINANCIAL` | A decision's catalogued codes, as the edge carried them |
| `underwriting_case_event` | `reason` | `CONFIDENTIAL` | Free prose by a person - a decision's or a refusal's reason |
| `underwriting_case_event` | `first_decided_by` | `CONFIDENTIAL` | The first decider a second person's act answered |
| `underwriting_case_event` | `occurred_at` | `INTERNAL` | System time, stamped by the trigger |

### Free text, classified at its ceiling

`audit_record.reason`, `audit_record.change_summary`, `idempotency_record.response_body`,
`outbox_event.payload`, `outbox_event.last_error`.

Each already carries a written constraint on what may be *written* into it — `V006` and `V009` say
so in the schema, and `P0-TSK-018` keeps payloads out of the event envelope — and those constraints
are the real control. The classification says how the column must be *handled* if a constraint is
ever broken.

### Caller-supplied identifiers, where the level is a requirement rather than an observation

`correlation_id` (four tables), `idempotency_record.idempotency_key`, `inbox_message.dedupe_key`.

These are classified `INTERNAL` (or `CONFIDENTIAL`) because that is what they **must** be: a
correlation identifier is written to every log line as a top-level ECS field, stamped on every span,
stored in four tables, and echoed back in the `X-Correlation-Id` response header and in every
problem-detail body. There is nowhere for it to be anything else.

`inbox_message.dedupe_key` gained its first genuinely external supplier in `P2-TSK-011` — a
provider callback's `deliveryId` — and the requirement is held the way this section demands:
the value is charset-bounded at the boundary (`[A-Za-z0-9._:@/+=-]`, the idempotency-key
charset) before it can reach the column.

**This scheme is what made the gap visible, and `P1-TSK-002` closed it.** It is recorded here in
full rather than deleted, because the reasoning is what keeps the requirement true for the next
caller-supplied column. Until 2026-09-04 the permitted charset was `[A-Za-z0-9._:@/+=-]`, a
well-formed caller value was accepted verbatim as the flow's identifier (`CorrelationFilter`), and
all four of these were confirmed accepted by probe:

| Supplied as `X-Correlation-Id` | Would be |
|---|---|
| `jane.doe@example.com` | `RESTRICTED-PII` |
| `acct:GB29NWBK60161331926819` | `RESTRICTED-FINANCIAL` |
| `customer-1990-05-14` | `RESTRICTED-PII` |
| `+447700900123` | `RESTRICTED-PII` |

So a caller can place personal or financial data into a value the platform then propagates to a
telemetry backend with different access control and months of retention — which is precisely what
`INV-AUD-02` forbids and what ADR-0017 and ADR-0018 keep SQL text and request-derived tags off
spans and metrics to prevent.

**The fix was to constrain the value, not to relax the handling.** Raising the classification would
have been the wrong repair: it would forbid correlation from appearing in logs, which is the entire
point of correlation.

**Closed by ADR-0034 (`P1-TSK-002`, 2026-09-04): the platform now mints the correlation identifier
on every request and never adopts an inbound one.** A well-formed caller value is echoed back in
`X-Client-Correlation-Id` and reaches no sink.

**Narrowing the charset was the other candidate and does not work** — which is the finding worth
keeping, because it is the repair most people would reach for. A date of birth, a phone number and
an account number are alphanumeric, so any charset still able to carry a UUID or a W3C trace value
also carries them. Of the four probed values above, narrowing to `[A-Za-z0-9_-]` would have stopped
two and left two. **The control had to be structural, not lexical.**

`CallerCorrelationIsNotPropagatedTest` asserts it at the source — what `CorrelationContext` holds
during a request — because all four columns, the MDC and the span attribute read from there, so the
property covers sinks that do not exist yet.

**A route out of a classified column that the scheme did not anticipate, found by the `P1-TSK-008`
gate and closed.** PostgreSQL reports a `CHECK` violation with a `DETAIL` line containing the
**entire refused row**, and the JDBC driver puts it in the exception message — so any code attaching
that exception as a cause carried a `RESTRICTED-PII` derivation, a person's name or a login
identifier into every log line that printed it. The classification was correct and the handling was
being bypassed by the error path. Closed by `platform.persistence.DatabaseFailure`, which keeps the
SQLState and drops the driver exception, and by removing the cause-taking constructor from the three
storage exceptions so the unsafe path does not compile.

**The scheme's weakest point, stated rather than glossed:** no build rule checks what a caller
writes into a free-text column. `correlation_id` is no longer among them — nothing caller-supplied
reaches it — but `audit_record.reason`, `idempotency_record.idempotency_key` and
`inbox_message.dedupe_key` still hold whatever a caller or an operator put there. The nearest
mechanical control is the output scrubber already recorded as debt for Phase 1, and it is a
deny-list.

## 6. What is deliberately not here

| | Why | Owning phase |
|---|---|---|
| A `Classification` enum in Java | Nothing would consume it. No production code today reads or writes a classified field, so an enum would be a speculative surface maintained for an imagined caller. The register plus its guard is what later tasks actually meet | Phase 1, with party data |
| Column-level encryption or tokenisation | There is nothing restricted in the database yet, and the mechanism belongs with the data | Phase 1 (PII), `P0-TSK-034` (at rest) |
| Per-level retention periods | Retention is a correctness bound for the idempotency, inbox and outbox tables (`DATA_MIGRATIONS.md` §8-9) and a regulatory one for audit and financial history. Those are different questions with different owners, and folding them into a classification level would answer both wrongly | Phase 15 |
| Access control per level | There is no authority to authorise against. The database role split (`P0-TSK-022`) is the only access control that exists | `P0-EPIC-10` onward |
| Classification of Kafka topics, Redis keys, object storage | None exist yet. The register covers the one store that holds data | Phase 3 onward |
