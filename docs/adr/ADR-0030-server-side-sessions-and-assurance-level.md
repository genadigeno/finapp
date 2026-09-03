# ADR-0030 — Server-side sessions, and assurance is a level rather than a flag

Status: Proposed

Date: 2026-09-03

## Context

`DELIVERY_PLAN.md` §5 requires a decision on "token strategy and session invalidation semantics".
`MODULE_ARCHITECTURE.md` states the constraint the decision must satisfy: *"Session revocation is
immediate, never eventually consistent — an eventually-revoked session is an unrevoked session."*

This is a genuine fork. The two mainstream answers have opposite failure modes, and the choice is
decided by which failure a financial platform can least afford.

A second question is usually answered badly and is decided here too: **what does a session record
about how it was established?** The common answer is a boolean — `mfaCompleted`. That answer is
what `INV-IDN-05` exists to prevent.

## Decision

### Sessions are server-side and authoritative in PostgreSQL

Every request presenting a session identifier results in a lookup of authoritative session state.
There is no self-contained bearer token whose validity can be determined without asking.

**Revocation is therefore immediate by construction**, on every instance, with no cache to expire
and no propagation delay (`INV-IDN-03`).

**No Redis, deliberately.** Redis is running, pinned and unused, and session storage is the obvious
first use — which is precisely why the decision must be made rather than drifted into. A session in
Redis is state whose durability is weaker than the account it protects: a Redis restart logs
everyone out (survivable) or, with the wrong persistence settings, *resurrects* a revoked session
(not survivable). PostgreSQL already holds the identity that the session refers to, in the same
transaction boundary, with the same durability. Redis becomes a session cache only if measurement
shows the lookup is a bottleneck — and then it is a cache in front of an authority, never the
authority (`INV-BAL-05`'s reasoning, applied outside money).

### The session records an assurance level, not an MFA boolean

A session carries the **level of assurance** at which it was established, and an operation states
the level it requires.

| Level | Established by |
|---|---|
| `PASSWORD` | A single knowledge factor |
| `MULTI_FACTOR` | A knowledge factor plus a possession or inherence factor |
| `STRONG` | A phishing-resistant authenticator (WebAuthn/passkey) |

**Why a level and not a flag.** A boolean answers "did this session do MFA?" — and every real MFA
bypass is a path that produces a session the boolean says is fine. A recovery flow, a refresh, an
older session, a second-factor enrolment: each is a route to a session, and with a boolean each
must remember to set it correctly. With a level, an operation asks *"was this session established
to at least `MULTI_FACTOR`?"*, and a route that cannot answer that question cannot produce a
session that passes. The check moves from every producer to every consumer, and consumers are the
ones with the requirement.

**Step-up does not mutate the session's level in place.** Elevating produces a new session
identifier, so a stolen pre-elevation identifier does not become elevated behind the legitimate
user's back.

### Session identifiers are opaque, rotated, and never carry claims

An identifier is a random value with no structure a client can read or a server can trust. It is
**rotated on every privilege change** — login, step-up, credential change — which is the standard
defence against session fixation and costs one insert.

### Expiry is two bounds, not one

Both an **idle timeout** and an **absolute lifetime**. Idle alone lets an active attacker hold a
session indefinitely; absolute alone logs out a working user mid-task. Both are configuration, and
both are recorded on the session so a change of policy does not retroactively extend sessions
issued under the old one (`INV-HIST-04`'s reasoning).

## Alternatives Considered

### Option A — Self-contained JWTs, stateless validation
Pros: No lookup per request; scales without touching the database; the standard answer.
Cons: **Revocation is the whole problem.** A self-contained token is valid until it expires,
because validity is a property of the signature and not of any current state. Every mitigation
reintroduces the lookup it was chosen to avoid: a revocation list is a lookup, short expiry plus
refresh is a lookup on refresh and a window of validity in between, and a "logout everywhere"
button becomes a promise the architecture cannot keep. For a platform where session compromise
means access to money, an unrevocable credential with a lifetime is the wrong trade. Rejected on
`INV-IDN-03`.

### Option B — JWT access token plus server-side refresh token
Pros: The common compromise; short-lived access tokens bound the exposure window.
Cons: It bounds it, it does not close it. Revocation still takes effect at the next refresh, so
"immediate" becomes "within the access-token lifetime" — an eventually-revoked session with a
tunable delay. It also doubles the surface: two token types, two lifetimes, two rotation rules, and
a refresh endpoint that is itself an authentication path needing its own abuse controls. The
complexity is real and the property is still not achieved.

### Option C — Server-side sessions in Redis
Pros: Fast; the conventional home for session state; Redis is already running.
Cons: Splits authoritative state across two stores with different durability. A session outliving
its revocation after a restore is a security failure, and one that no test on a healthy system
finds. `CLAUDE.md` rule 12: Redis is not truth.

### Option D — Server-side sessions in PostgreSQL (chosen)
Pros: Revocation is immediate with no mechanism. One store, one durability model, one transaction
boundary — a session created as part of a login commits with it. Cheap to reason about and cheap to
audit.
Cons: A database read per authenticated request. Accepted deliberately: it is an indexed
primary-key lookup on a table whose working set is small, on a platform whose next phase performs
several database round trips per money-moving request anyway. If it becomes a bottleneck the answer
is a measured cache in front of an authority — and `P1-TSK-004`'s pool sizing is where the cost is
first accounted for.

## Consequences

Positive:
- `INV-IDN-03` holds by construction rather than by discipline.
- `INV-IDN-05` is enforced at the point of use, so a new authentication route cannot silently
  produce an over-privileged session.
- No process-local session state, so ADR-0024's rules have nothing to catch here — and the
  temptation they cannot catch (`R7`) is removed rather than watched.

Negative:
- A database round trip per authenticated request. Measured in `P1-TSK-004`, not assumed.
- Session rows accumulate and need a retention sweep. Recorded as debt with Phase 15, on the same
  terms as the outbox and inbox sweeps — and with the same caution: early expiry logs a user out,
  late expiry grows a table, and neither is a correctness failure the way early inbox expiry is.
- Sessions must be revoked on credential change, which is a cross-aggregate effect inside
  `identity`. It is one transaction, in one module.

## Invariants / Constraints

`INV-IDN-03`, `INV-IDN-04`, `INV-IDN-05`, `INV-AUD-01`. ADR-0014 (N instances), ADR-0024
(no process-local coordination), ADR-0021 (`SecurityContext` carries the actor, and now the level).

## Follow-up

- `P1-TSK-004` sizes the connection pool for N instances before the first authenticated endpoint.
- Phase 4's step-up requirement for high-value transfers consumes the assurance level; the seam is
  the level itself, and no Phase 4 logic is implemented here.
- Redis remains in `compose.yaml` and unused. If session lookup is ever measured to be the
  bottleneck, a cache is an additive change behind the same interface.
