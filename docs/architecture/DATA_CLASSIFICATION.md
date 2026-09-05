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
