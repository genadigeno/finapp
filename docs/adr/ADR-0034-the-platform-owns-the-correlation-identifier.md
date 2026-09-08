# ADR-0034 — The platform owns the correlation identifier

Status: Accepted

Date: 2026-09-04

## Context

`CorrelationFilter` adopts a well-formed inbound `X-Correlation-Id` as **the** correlation
identifier for the flow. That value then reaches, verbatim:

| Sink | Retention and access control |
|---|---|
| Every log line, as a top-level ECS field | A log aggregator, months, read by everyone on call |
| Every span, as `finapp.correlation_id` | A telemetry backend with its own access control (ADR-0017) |
| `idempotency_record`, `outbox_event`, `inbox_message`, `audit_record` | Durable; the audit trail is append-only **by privilege**, so a value written there cannot be deleted by the application at all |
| The `X-Correlation-Id` response header and every problem-detail body | Returned to whoever asked |

`P0-TSK-033` recorded this as debt after probing the filter, and the probe is what makes it
concrete rather than theoretical. All four of these were **confirmed accepted**:

```
jane.doe@example.com
acct:GB29NWBK60161331926819
customer-1990-05-14
+447700900123
```

That is a caller placing personal and financial data into systems with different access control and
months of retention, which is exactly what `INV-AUD-02` forbids. It is bounded today only by there
being no customers. **Phase 1 is when customers arrive**, which is why this lands before the first
customer-facing endpoint rather than after it.

### The constraint that decides the answer

**No charset can separate an opaque token from personal data**, and that is the finding that rules
out the obvious fix.

Narrowing `[A-Za-z0-9._:@/+=-]` to something strict — say `[A-Za-z0-9_-]` — removes the email and
the `acct:` IBAN. It leaves:

```
customer-1990-05-14        a date of birth
447700900123               a phone number
GB29NWBK60161331926819     an account number
```

Every one of those is alphanumeric, and any charset narrow enough to exclude them is too narrow to
carry a UUID, a W3C trace value or a provider reference — which is the entire reason the header is
accepted. **A lexical control cannot express the property we need.** The control has to be
structural.

### What must not be lost

Forbidding correlation identifiers from logs would "solve" the disclosure and defeat correlation,
which is the platform's primary diagnostic. `P0-TSK-014` through `P0-TSK-028` exist to put that
value in four sinks; the answer cannot be to take it out of them.

The legitimate need behind the inbound header is also real: a client, gateway or upstream service
wants to tie its records to ours.

## Decision

**The platform generates the correlation identifier for every flow. An inbound value is never
adopted as it.**

1. **`CorrelationId` is always minted by the platform** — UUIDv7, as `P0-TSK-014` already does when
   no header is supplied. It is now what happens on every request, without exception.
2. **A well-formed inbound `X-Correlation-Id` becomes a *client reference***: a distinct value with
   a distinct type, echoed back in the **`X-Client-Correlation-Id`** response header and carried
   **nowhere else**. Not the MDC, not a span attribute, not a database column, not the problem
   detail.
3. **The charset and the 128-character bound stay exactly as they are.** They are no longer the
   disclosure control — they never could be — but they remain the log-injection and unbounded-input
   control for the one place the value is still handled, and `CorrelationId` continues to enforce
   them for the value the platform mints and for the client reference it echoes.
4. **A malformed inbound value still does not fail the request**, and is still not echoed in the
   log that reports the rejection. Unchanged from `P0-TSK-025`, and for the same reason: a
   malformed diagnostic hint is never a reason to decline a payment.

### How the client still joins its logs to ours

Better than before, and this is the part worth stating because the previous design's advantage was
supposed to be exactly this.

- The response carries **our** identifier in `X-Correlation-Id`. A client logs it against its own
  request and the join exists from its side — which is where the client's own log lives anyway.
- The response also carries the client's own value back in `X-Client-Correlation-Id`, so a gateway
  or an asynchronous caller can match a response to a request it no longer has the connection for.

What is lost is the ability to search **our** logs by the client's value. That is deliberate: being
searchable by a caller-chosen string is precisely the property that made the disclosure possible.

## Alternatives Considered

### Option A — Narrow the accepted charset
Pros: one-line change; keeps the existing adoption behaviour; no new header.
Cons: **does not work.** A date of birth, a phone number and an account number are alphanumeric,
and a charset narrow enough to exclude them cannot carry a UUID or a trace value. It would look
like a fix, close the debt row, and leave three of the four probed values still accepted. Rejected
on the analysis above rather than on preference.

### Option B — Reject any inbound value that *looks like* PII
Pros: keeps adoption; targets the actual risk.
Cons: a detector for "looks like an email, a phone number, an IBAN, a date" is a denylist, and a
denylist is wrong the first time somebody sends a national identifier in a format nobody enumerated.
The repository already rejected denylist reasoning for the correlation charset itself, for the
output scrubber, and for the health-response leak test. Rejected for consistency with all three.

### Option C — Hash the inbound value and use the hash
Pros: keeps a stable join key; the plaintext never reaches a sink.
Cons: a hash of a low-entropy identifier is not a one-way function in practice — an email address
or a phone number is trivially recovered by enumeration, so the disclosure survives with an extra
step and a false sense of safety. It also breaks the client's join, because the client cannot
search our logs for a value it would have to compute the same way.

### Option D — Stop accepting the header entirely
Pros: the simplest possible surface; nothing to echo, nothing to validate on the inbound path.
Cons: it discards a real capability for no additional safety, since an echoed-only value reaches no
sink. Rejected as unnecessarily lossy — but it is the fallback if echoing ever proves to carry a
risk this ADR has not seen.

### Option E — Generate always; echo the caller's value only (chosen)
Pros: the disclosure is closed **structurally** rather than lexically, so it does not depend on
anticipating what a caller might send. Correlation keeps every property it had. The client keeps a
join. The change is confined to one filter and its contract.
Cons: our logs are no longer searchable by a client-supplied identifier, which is a genuine
capability loss for a caller who wanted it; and there is now a second header for a client author to
understand.

## Consequences

**Positive:**
- No caller-controlled value reaches a log line, a span, a durable column or a problem detail.
- The property holds for values nobody anticipated, because it does not depend on recognising them.
- Every correlation identifier in every sink is now a UUIDv7 minted by the platform, so the columns
  classified `INTERNAL` in `DATA_CLASSIFICATION.md` §5 hold what that level was always asserting
  they held.

**Negative:**
- A client that relied on searching our logs by its own identifier can no longer do so. There is no
  such client — the platform has no customers — but the capability is genuinely removed.
- A second response header exists, and a client author must be told which one is which.

**Operational impact:** none. No new dependency, no configuration, no schema change. Existing rows
keep whatever they were written with; nothing is rewritten, and nothing needs to be, because the
columns were never wrong — they were unenforced.

**Security impact:** this is the whole point. It closes the widest-reaching disclosure channel in
the platform, recorded as debt since `P0-TSK-033` and named as risk **R1** by the Phase 0 → Phase 1
transition.

**Financial impact:** none. Phase 1 moves no money.

## Invariants / Constraints

Enforces `INV-AUD-02` (sensitive data never enters logs, events or responses) for a channel it did
not previously cover. Preserves the four-sink correlation property established by `P0-TSK-014`,
`P0-TSK-019`, `P0-TSK-022` and `P0-TSK-028`, and `CorrelationSinkCoverageTest` continues to hold
it. Does not alter ADR-0017's span content rule or ADR-0018's metric cardinality rule; correlation
remains deliberately off metrics.

## Follow-up

- **`traceparent` is a related channel and is deliberately out of scope.** A caller can set the
  W3C trace id, which is joined rather than replaced (ADR-0017). It is constrained to 32 hex
  characters and is emitted by tracing libraries rather than typed by a person, so the accidental-
  PII path this ADR closes does not exist there. Recorded so the omission is a decision.
- The output scrubber recorded as debt in `P0-TSK-030` remains a separate concern: it covers a
  secret held in a local variable, not a caller-supplied identifier.
