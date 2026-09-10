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
