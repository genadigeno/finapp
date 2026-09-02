# ADR-0022 — Data is classified per column, at its ceiling, before it holds anything

Status: Proposed

Date: 2026-09-02

## Context

`DATA_ARCHITECTURE.md` §Data Rules requires that sensitive data is classified and protected
appropriately. That was one sentence with nothing behind it. `P0-TSK-033` supplies the scheme.

Phase 0 holds **no customer data, no money and no credentials** — zero business capability, by
design. A classification scheme written now therefore classifies almost nothing that is currently
sensitive, which invites the obvious objection: why not wait until there is something to classify?

Because a column's classification cannot be added later. By the time a column holds data, the
handling it was given for its whole life is already settled: it may be in a log aggregator, an
event stream, a backup or a support screenshot, and none of that can be recalled. Reclassifying
is not a schema change, it is an admission that the previous handling was wrong.

This is the same argument ADR-0010 makes for actor attribution and `INV-HIST-01` makes for
financial history: the decision has to precede the first row.

## Decision

**1. Five levels**: `PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED-FINANCIAL`, `RESTRICTED-PII`.
A record takes the highest level of any field in it.

**2. Classification is per column, not per table or per entity.** A table mixes levels — the audit
record holds an operation code, a correlation identifier and a free-text reason, and treating them
alike would either over-restrict the operational fields or under-protect the reason.

**3. Every column is classified at its *ceiling*** — the most sensitive thing it may ever hold, not
what it holds today. `audit_record.actor_id` is `RESTRICTED-PII` although it currently contains the
literal `system`, because from Phase 1 it is a person's identity-provider subject and there will be
no moment at which it is safe to change the answer.

**4. The register is guarded.** `ColumnClassificationTest` compares the register against the live
schema in both directions, so a migration that adds a column without a classification decision
fails the build. That guard, not the document, is what makes the acceptance criterion — "referenced
by later data-model tasks" — true rather than hoped: Phase 3 adds ledger tables and cannot land
them unclassified.

**5. Handling rules are referenced, never restated.** Every rule the levels imply is already
enforced somewhere — redaction (ADR-0019), metric cardinality (ADR-0018), no SQL on spans
(ADR-0017), no credential in configuration (ADR-0020), audit immutability (ADR-0010). The scheme
gathers them under names and points at them. A second copy would drift while looking authoritative,
which is the discipline `API_CONVENTIONS.md` already applies to the error-code catalogue.

**6. No `Classification` enum in Java yet.** Nothing would consume it. No production code reads or
writes a classified field, so an enum would be a speculative surface maintained for an imagined
caller — the same objection `P0-TSK-014` recorded against a speculative executor decorator.

## Alternatives rejected

**Classifying at current content, and revisiting when the data arrives.** The revisit never happens
on time, and "on time" here means *before the first row*, not before the first incident. It also
makes every classification a lie with an expiry date nobody tracks.

**Classifying per table.** Cheaper to write and wrong in the direction that matters: the most
sensitive column in a table sets the level, so either the operational columns get restricted
handling they do not need — which makes people work around the scheme — or the free-text column
gets handling it does need and does not have.

**Three levels (public / internal / sensitive).** Simpler, and it collapses the distinction between
money and identity. Those have different handling: financial data may be shown to an authorised
counterparty and must never be deleted to satisfy retention, while personal data may have to be
deleted on request and must never reach a log. A scheme that cannot express that difference cannot
guide either decision.

**A `@Classified(RESTRICTED_PII)` annotation on entity fields.** The right shape eventually, and
premature now: there are no entities, and an annotation enforced by nothing is a label. When there
is production code holding classified data, an annotation plus a rule is the natural next step.

**Folding retention into the levels.** Retention is a *correctness* bound for the idempotency,
inbox and outbox tables (`DATA_MIGRATIONS.md` §8–9) and a *regulatory* one for audit and financial
history. They are different questions with different owners; one number per level would answer both
wrongly.

## Consequences

- A new column cannot reach the schema without a classification decision, and the decision is made
  while it is still cheap.
- The register states the ceiling, so handling built against it is not invalidated when Phase 1 and
  Phase 3 put real data in the columns.
- **The scheme found a real defect on its first application, which is the strongest evidence it
  earns its place.** `correlation_id` was classified `INTERNAL` in four tables on the reasoning that
  it is operational metadata. Applying the ceiling rule to it exposed that it is *caller-supplied*:
  the charset is `[A-Za-z0-9._:@/+=-]` and a well-formed inbound header is accepted verbatim, so an
  email address, an IBAN, a date of birth and a phone number are all valid — confirmed by probe. The
  value is then written to every log line, every span, four tables and every error body. The level
  stays `INTERNAL` because that is what it must be for correlation to work at all; what has to
  change is the value, not the handling. Recorded as debt for Phase 1 rather than fixed here, since
  it is `P0-TSK-025`'s ingress behaviour.
- Five further columns are classified above their current content because what they hold is decided
  by a caller rather than by a type — `audit_record.reason`, `audit_record.change_summary`,
  `idempotency_record.response_body`, `outbox_event.payload`, `outbox_event.last_error`. **No build
  rule checks what is written into a free-text column.** That is the scheme's weakest point, is
  stated in the document rather than glossed, and the nearest mechanical control is the output
  scrubber already recorded as debt for Phase 1.
- The scheme covers PostgreSQL only. Kafka topics, Redis keys and object storage hold nothing yet;
  they join the register when they do.

## References

- `docs/architecture/DATA_CLASSIFICATION.md` — the scheme and the register
- `DATA_ARCHITECTURE.md` §Data Rules — the requirement this satisfies
- `INV-AUD-02`; ADR-0010, ADR-0017, ADR-0018, ADR-0019, ADR-0020
