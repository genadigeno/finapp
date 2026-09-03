# ADR-0019 — A secret is unloggable by default, not redacted by remembering

Status: Accepted

Date: 2026-09-02

## Context

`INV-AUD-02` is the only invariant that specifies its own enforcement mechanism: *default-deny
redaction*. `.claude/rules/security.md` states the rule — never log credentials, tokens, PANs or
unnecessary PII — and `P0-TSK-030` is the only Phase 0 task the backlog marks **High risk**,
because logging leaks are a common and severe fintech incident class.

The usual implementation is opt-*in*: annotate the sensitive fields, and a filter masks them. It
fails the first time somebody adds a field without thinking about it, which is every time somebody
is in a hurry — and the failure is silent, because a log line with one extra field in it looks
completely normal.

**The accident is Java's own.** A record generates a `toString()` printing every component:

```java
record Credentials(String username, String password) {}
log.info("authenticating {}", credentials);   // prints the password
```

There is no getter call, no concatenation, no string building — nothing a reviewer would stop at.
The credential is now in the log, in the shipper, and in whatever retains it for ninety days.

## Decision

**1. A secret is held in `Sensitive<T>`, whose every rendering path is a mask.**

`toString()` returns `«redacted»`, which covers SLF4J's `{}`, interpolation, concatenation, a
record's generated `toString` and a serialiser's fallback. Retrieving the value requires
`expose()` — named so that a grep, a code search and a reviewer all find every place a secret
leaves its wrapper.

**`equals` and `hashCode` are identity-based.** Value equality would make the wrapper an oracle:
`sensitive.equals(Sensitive.of(guess))` answers whether the guess is right, and a wrapper that
answers questions about the value it hides is not hiding it. Two wrappers around equal values are
not equal, and that is correct.

**2. The build fails on an unwrapped secret. That is what makes it default-deny.**

`secretsAreWrapped` rejects any production field, or no-argument accessor, whose *name* says it
holds a secret unless the type is `Sensitive<?>`. Default-deny is not a property of the wrapper —
a wrapper people must remember is opt-in with extra steps. It is a property of the rule.

**Accessors as well as fields**, because a serialiser reads accessors: a private `pw` behind
`getPassword()` is invisible to a field-only rule and is exactly what Jackson and a record's
`toString` reach for.

**The vocabulary is narrow, and `key` is not in it.** An idempotency key is not a secret —
`API_CONVENTIONS.md` §6 says so, and it is recorded on the audit record deliberately. A rule that
flagged `idempotencyKey` is a rule somebody turns off, and a rule that is off protects nothing.
Matching is on camel-case word boundaries, so `companyName` is not a PAN and `spinLock` is not a
PIN. False positives are how a security rule gets deleted.

**3. Masked serialisation is stated, not inherited.**

Jackson already declines to reveal a `Sensitive` — it finds no properties and emits `{}`. That is
safety by **accident**, and it ends silently the day somebody adds a getter. `INV-AUD-02` covers
API responses as firmly as logs, so a serialiser in `app` renders the mask explicitly. This
codebase has been bitten by exactly this shape before: `ProblemDetail` serialised directly produced
`"correlationId":{}` — an empty object where a value was expected, with nothing failing.

The serialiser is in `app` rather than on the type, because `sharedkernel` is framework-free by
construction and annotating it would put a serialisation library into the one module whose point is
having none.

**4. ECS JSON on the console, in every environment.**

Including a developer's machine. Human-readable locally and JSON in production is the class of
difference this project rejects elsewhere — `compose.yaml` pins the same images the tests use on
exactly this argument — and here it is sharper: a redaction defect that only manifests in the
encoder a deployment uses would be invisible to everyone who never runs that encoder.

Correlation reaches a log line through the MDC, which ECS lifts into the document, so
`correlationId`, `traceId` and `spanId` are queryable fields rather than text inside a message.
That is what makes a line joinable to a trace and to the durable record.

**5. Tests read emitted output, never a list appender.**

A list appender holds the event *before* encoding, so a defect introduced by the encoder, by a
serialiser's fallback, or by structured logging lifting an argument into a field would be invisible
to it. What matters is the bytes that leave the process, because those are what the shipper takes.

Every such test carries a **negative control** proving it can see a leak. Without one, a capture
that silently caught nothing would make every "does not contain" assertion pass while checking
nothing at all.

## Alternatives Considered

### A. Annotate sensitive fields, mask in a filter (`@Sensitive String password`)
Pros: Familiar; no wrapper type; existing code unchanged.
Cons: Opt-in. The field somebody forgets is the field that leaks, and nothing fails. It also does
not survive `toString()`, which is the actual leak path.

### B. Scrub the rendered output by pattern (PAN shapes, JWT shapes, `password=`)
Pros: Catches secrets in text the platform does not control — a driver's exception message, a
third-party library's log line.
Cons: A deny-list, which is the opposite of what `INV-AUD-02` specifies, and it cannot know about
the next secret. Worth having as a **net** rather than as the control; not built here, and recorded
as debt with the residual risk it would cover.

### C. A logging facade that accepts only declared-safe argument types
Pros: Would close the remaining hole — a secret held in a local and passed straight to a log call.
Cons: Every log statement in the platform changes, and an ArchUnit rule must forbid obtaining a
logger any other way. Larger than this task, and the value depends on how much logging exists,
which today is eight statements. Revisit when there is a business module logging real flows.

### D. Human-readable logs locally, JSON in deployment
Pros: A developer reads logs by eye.
Cons: The encoder that matters is then the one nobody tests. Mitigated instead by documenting the
one-line override for local use.

## Consequences

Positive:
- A credential cannot be stored in a type that would print it; the build says so.
- The property holds for code nobody has written yet, which is the only kind of guarantee worth
  having in a platform with sixteen phases to go.
- Log lines are machine-parseable and joinable to traces and to the durable record.

Negative:
- Local logs are JSON. A one-line override is documented; it is still a cost.
- `Sensitive` is a wrapper to unwrap at every real use — deliberate friction, and friction that is
  occasionally annoying is the point.
- The rules cover what a type *stores*. A secret in a transient local has no declaration to
  inspect, and is not covered.

Operational impact:
- `logging.structured.format.console=ecs`; override empty for a readable local console.

Security impact:
- Verified against a running instance: real stdout is ECS JSON, a client-supplied correlation
  identifier arrives as a queryable field, and the configured database password appears nowhere —
  including when a full authentication failure is logged with its stack trace.

Financial impact:
- None directly. Indirectly, a credential in a log is an incident with regulatory consequences, and
  this is the control that prevents the most common way one gets there.

## Invariants / Constraints

- `INV-AUD-02` — this ADR is its Phase 0 enforcement, in the form the invariant itself specifies.
- `sharedkernel` stays framework-free: `Sensitive` is plain Java, and the Jackson serialiser that
  renders it lives in `app`.

## Follow-up

- **An output scrubber**, as a net for text the platform does not control — a driver exception, a
  third-party log line. Recorded as debt; it is a deny-list and must never be mistaken for the
  control.
- **A logging facade** (alternative C) when there is a business module logging real flows.
- **`P0-TST-008`** asserts sensitive markers never appear across all appenders; `secretsAreWrapped`
  already satisfies its acceptance criterion, which that task should confirm rather than duplicate.
- **PII beyond credentials** — names, addresses, identifiers — is a data-classification question
  that arrives with the first real customer record in Phase 1.
