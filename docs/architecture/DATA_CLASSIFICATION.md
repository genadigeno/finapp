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

Every column in every schema this repository owns — `platform`, `party` and `identity` — at its
ceiling.
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
| `audit_record` | `reason` | `RESTRICTED-PII` | **Free text written by a person.** Its content is not constrained by any type, so it must be handled at the ceiling |
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
| `session` | `absolute_expires_at` | `CONFIDENTIAL` | As `issued_at`, from which it is derived |
| `session` | `device` | `RESTRICTED-PII` | **At its ceiling, not its content.** `P1-TSK-016` populates it, and what it holds is whatever a client sends about the machine a person uses — a user agent, a platform, a fingerprint. That is personal data about equipment in somebody's home, and there is no later moment at which classifying it lower becomes safe (ADR-0022) |
| `session` | `revoked_at` | `CONFIDENTIAL` | Dates a logout, or an intervention. As `credential.superseded_at`, which is `CONFIDENTIAL` because it dates a password change |
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
| `ledger_account` | `owner_kind` | `INTERNAL` | An enumeration of three values |
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

### `ledger.journal_entry` and `ledger.journal_line` — *added by `P3-TSK-005`*

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
| `beneficiary` | `id` | `INTERNAL` | An aggregate identifier |
| `beneficiary` | `party_id` | `CONFIDENTIAL` | The `consent_record.party_id` reasoning: the pairing is the fact — this person saves destinations, and paired with `destination_account_id` it discloses a relationship between two people. What it resolves to stays `RESTRICTED-PII` as ever |
| `beneficiary` | `display_name` | `RESTRICTED-PII` | **Free text a person writes about a person** — "Mum", a full name, a nickname that identifies. The `party.display_name` reasoning at one remove, and the plan (§8) classifies it in as many words |
| `beneficiary` | `destination_account_id` | `INTERNAL` | An identifier of a thing (`transfer.destination_account_id`'s reasoning); the relationship fact lives in the pairing and is carried by `party_id`'s level — the `consent_record.purpose` idiom |
| `beneficiary` | `status` | `INTERNAL` | An enumeration of two values, both the person's own acts — no administrative state, unlike `customer_account.status` |
| `beneficiary` | `created_at` | `CONFIDENTIAL` | Dates a person's act of saving a destination — `consent_record.recorded_at`'s reasoning |
| `beneficiary` | `removed_at` | `CONFIDENTIAL` | As `created_at` |

### `paymentmethods.payment_method` — *added by `P5-TSK-004`*

**The PCI boundary's subject** (`INV-PAY-02`): a token reference plus display metadata, every
column's shape unable to carry a PAN by `CHECK` — which is why the levels below describe
instrument-linked data and never card data, there being no column that could hold any.

| Table | Column | Level | Why |
|---|---|---|---|
| `payment_method` | `id` | `INTERNAL` | An aggregate identifier |
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
| `payment_intent` | `wallet_account_id` | `INTERNAL` | A ledger-account identifier by value — `transfer.destination_account_id`'s reasoning |
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
| `payment_attempt` | `authorized_amount_minor` | `RESTRICTED-FINANCIAL` | The issuer's promised amount — a customer amount |
| `payment_attempt` | `authorized_currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `payment_attempt` | `authorized_scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `payment_attempt` | `captured_amount_minor` | `RESTRICTED-FINANCIAL` | The amount actually taken — the posting's own number |
| `payment_attempt` | `captured_currency` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `payment_attempt` | `captured_scale` | `RESTRICTED-FINANCIAL` | Part of the monetary shape |
| `payment_attempt` | `failure_reason` | `CONFIDENTIAL` | `DECLINED` is a fact about a person's finances, not an enumeration technicality — `transfer.failure_reason`'s reasoning verbatim |
| `payment_attempt` | `status` | `CONFIDENTIAL` | What happened to a person's payment operation |
| `payment_attempt` | `created_at` | `CONFIDENTIAL` | Dates a person's financial act |
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
