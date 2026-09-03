# ADR-0017 — Correlation is carried on spans; a trace identifier never replaces it

Status: Accepted

Date: 2026-09-02

## Context

`SYSTEM_ARCHITECTURE.md` principle 7 requires observability across customer → request → domain
operation → provider → ledger → settlement → reconciliation. `P0-TSK-028` brings the first half of
that: distributed tracing.

Two questions have to be answered before any span is recorded, and both become expensive later.

**1. How do a trace identifier and a correlation identifier relate?** They look interchangeable.
Treating them as such is the mistake, and it is invisible until an incident:

| | `traceId` | `CorrelationId` |
|---|---|---|
| Owned by | The tracing system | This platform |
| Subject to sampling | **Yes** — a sampled-out trace leaves no record | **No** |
| Stored | Nowhere durable | `NOT NULL` on the idempotency, outbox, inbox and audit tables |
| What a customer quotes | Nothing | This |

A platform that relies on the trace id to find a flow can answer "what happened to my payment?"
only for the fraction of traffic that was sampled. That is acceptable for a web application and
not for a system whose records must be explainable.

**2. What does a database span record?** Every off-the-shelf JDBC tracing option records the SQL
statement text as a span attribute. On this platform's future tables that is amounts, account
identifiers and personal data, flowing into a telemetry backend with different retention and
different access control from the database — an `INV-AUD-02` violation that arrives silently the
first time somebody writes a query.

## Decision

**1. Correlation is an attribute on every span; the trace identifier is not a substitute.**

`TraceAttributes.CORRELATION_ID` (`finapp.correlation_id`) is set on every span, so a trace can be
found from the value a customer quotes even when the trace id is unknown. `CAUSATION_ID` is set
when the flow has a cause.

**2. It is applied once, by a span processor, not by each component.**

"Every span carries correlation" is a property no per-component discipline delivers: a component
added next year, or an instrumentation library nobody wrote, would each have to remember, and
forgetting is silent — the span is recorded, the trace looks complete, and it cannot be found. A
`SpanProcessor` in the composition root stamps spans the SDK starts, including spans this codebase
does not produce. Same argument as the correlation filter at `HIGHEST_PRECEDENCE` and the version
prefix applied in the composition root.

Nothing is stamped when no correlation scope is active. A fabricated identifier on a background
span would put a value in a dashboard that matches nothing in any table.

**3. Inbound W3C trace context is joined, never replaced.**

`ADR-0014` says N is never 1, so a request crossing instances is the normal case. A trace that
restarts at a service boundary is not a trace.

**4. No JDBC tracing library. A span for connection acquisition, and no statement text.**

The security argument above is the primary one. The available libraries are also either
alpha-versioned or third-party auto-configuration of uncertain compatibility with this Spring Boot
line — not something to put on a financial platform's runtime classpath for a narrow signal.

`finapp.db.connection` is what remains, and it is the signal this platform has already written down
twice: `ADR-0016` answers readiness *through the pool* because a check with its own connection
reports healthy while the pool is exhausted, and the connection-pool sizing debt notes that ten
instances at Hikari's default exhaust PostgreSQL's default `max_connections` before doing any work.
The span turns "requests are slow" into "requests are waiting for a connection" — different
incidents, different fixes.

Per-operation domain spans (`finapp.ledger.post`, `finapp.outbox.write`) belong to the components
that perform them and arrive with those components. A domain-named span is worth more than
`INSERT INTO …` anyway: it says what the operation *meant*.

**5. Sampling is 100%, and that is a Phase 0 number.**

There is no traffic to sample. It becomes a load decision at Phase 15 — noting that a platform
sampling at 1% cannot explain the one payment a customer is asking about, which is why correlation
is on the durable record and not only in the trace.

**6. Nothing about where traces go is committed to source.**

No exporter endpoint is configured. Spans are recorded and, absent an exporter, dropped — which is
what a developer's machine should do. A committed endpoint is either wrong everywhere or a hostname
nobody meant to publish.

**7. Telemetry is never the record.**

The same relationship `INV-EVT-02` sets out for Kafka. A trace is a diagnostic aid that may be
absent; the correlation identifier on a committed row is evidence that is not. No financial
decision, audit answer or reconciliation may read from a trace.

## Alternatives Considered

### A. Use the trace id as the correlation id
Pros: One identifier; no attribute to maintain; standard.
Cons: Sampling. A sampled-out flow becomes unfindable from anything the customer holds, and the
identifier is absent from every table. Also inverts ownership: the platform's own records would
depend on a telemetry library's identifier format.

### B. Propagate correlation as OpenTelemetry baggage instead of a span attribute
Pros: Propagates across process boundaries automatically, including to services that do not know
about our header.
Cons: Baggage travels on the wire to every downstream, including third parties, which is a
disclosure decision rather than a telemetry one. It also does not, by itself, put the value on a
span where a query can find it. Worth revisiting for cross-service propagation at Phase 16; the
attribute is the part that makes traces searchable and is needed either way.

### C. A JDBC tracing library (`opentelemetry-jdbc`, `datasource-micrometer`)
Pros: Spans per statement, with timing, for free.
Cons: Statement text as an attribute — the `INV-AUD-02` problem. Suppressing it leaves a span
saying only "an SQL statement happened". Alpha or third-party auto-configuration on the runtime
classpath.

### D. The OpenTelemetry Java agent
Pros: Broad instrumentation with no code at all.
Cons: A deployment artefact that cannot be exercised in-process, so none of the properties above
could be tested — and the correlation-on-every-span property is the one thing worth testing. Also
re-introduces statement capture by default.

## Consequences

Positive:
- A flow is findable from the identifier a customer has, regardless of sampling.
- The property holds for spans this codebase does not produce.
- No statement text, and no telemetry library on the runtime classpath beyond the SDK.
- Connection-pool waiting is visible, which is the failure mode already twice identified.

Negative:
- No per-statement timing. Slow *queries* are not yet visible in a trace; slow *connection
  acquisition* is. Per-operation spans close that gap as components arrive.
- Every span carries an extra attribute. Accepted: it is one short string on a structure that
  already carries several.

Operational impact:
- Sampling is 100%; the volume implication is nil today and is a Phase 15 decision.
- No exporter is configured, so a deployment must supply one or record nothing deliberately.

Security impact:
- No SQL, no credentials, no exception messages on spans — a JDBC failure is recorded as an error
  with its exception *type*, because the message names the host, database and sometimes the user.

Financial impact:
- None directly. Indirectly, `INV-EVT-02`'s principle is extended: telemetry is not truth, and no
  financial answer may be derived from a trace.

## Invariants / Constraints

- `INV-AUD-02` — no credential, PAN, PII or internal identifier in telemetry. The reason there is
  no statement capture.
- `INV-EVT-02` — extended in spirit: transport is not truth, and neither is telemetry.
- `ADR-0014` — trace context propagates across instances, because a request crossing instances is
  normal.
- `P0-TSK-014` — the trace sink, named in its acceptance criterion and unverifiable until now, is
  closed by this decision and asserted by `TraceAcrossDatabaseTest`.

## Follow-up

- **Kafka producer and consumer spans** arrive with the broker adapter (Phase 3), which is where
  `EventPublisher` gets an implementation. `P0-TSK-028`'s criterion is corrected in the backlog to
  say so rather than to claim it.
- **Per-operation domain spans** with the components that perform the operations (Phase 3 onward).
- **An exporter and a sampling rate** with Phase 15's deployment topology.
- **Baggage for cross-service propagation** at Phase 16, if services are ever extracted.
