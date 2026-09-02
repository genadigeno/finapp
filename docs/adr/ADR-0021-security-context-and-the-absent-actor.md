# ADR-0021 — An unestablished actor is an error, never the system actor

Status: Proposed

Date: 2026-09-02

## Context

ADR-0010 requires an `ActorId`/`ActorType` abstraction in Phase 0, "populated by a system actor
until Phase 1 supplies real identity", and states that the schema does not change when identity
arrives. `INV-AUD-01` requires every privileged action to record who performed it.

`P0-TSK-022` delivered the value types: `Actor`, `ActorType`, an `Actor.SYSTEM` constant, and a
mandatory `actor` on `AuditRecord` with `CHECK`-constrained columns behind it. What it did not
deliver is the *mechanism* — how code that must record an actor obtains one. Today every call site
would name `Actor.SYSTEM` itself.

That is not merely inconvenient. It decides, at every call site independently, what happens when
nobody established who was acting.

## Decision

**1. A security context, per flow, established explicitly.** `SecurityContext` holds the current
`Actor` for the executing thread and carries it across handoffs, so the actor does not have to be
threaded through every signature between the entry point and the audit write.

**2. `require()` throws when no actor was established. It does not return `Actor.SYSTEM`.**

This is the decision. Defaulting would be convenient, and *correct today* — the system is the only
actor there is. It becomes wrong the moment Phase 1 lands, and it becomes wrong silently: an
authenticated request whose scope was never established would record the platform as having done
what a customer did. Nothing fails. The record is complete, plausible, and about the wrong party —
and `INV-HIST-03` makes it permanent.

`Actor` already states this principle for a blank identifier: an action whose actor was not
established is not attributable to the platform, it is an action nobody can answer for, and
recording a plausible actor makes the record *worse than absent by making it wrong*. This applies
the same rule one level up.

**3. Phase 0 says "the platform is acting" out loud**, through `enterSystem()` rather than
`enter(Actor.SYSTEM)`. The call is greppable, and that is its purpose: it is the list of places
Phase 1 must revisit to decide, for each, whether the work is genuinely platform-initiated or a
request that should now carry a person.

**4. Reading `Actor.SYSTEM` is a build failure outside `SecurityContext`**
(`onlyTheSecurityContextClaimsTheSystemActor`). Every audit record needs an actor, so every call
site has a parameter to satisfy, and the shortest way to satisfy it is a public constant that is
right there. ADR-0019's argument applies unchanged: the safe thing must be the default and the
unsafe thing must be hard to reach, because relying on people not to take the shortcut is relying
on nobody ever being in a hurry.

**5. `Actor` and `ActorType` move from `platform.audit` to `platform.security`.** Audit *records* an
actor; it does not own the concept of one. Leaving them in the audit package would mean every module
that needs to know who is acting imports the audit package, and would make the security context
depend on audit rather than the other way round. Same reasoning as `P0-TSK-018`, which moved the
correlation identifiers into `sharedkernel`: a value type sits below the mechanism that moves it.

## Alternatives rejected

**`current()` returning `Actor.SYSTEM` by default.** Rejected above. Worth naming explicitly because
it is what almost every framework does, and because the cost is invisible during the phase where it
is harmless.

**Carrying the actor inside `Correlation`.** They travel together and are established at the same
entry points, so combining them looks like simplification. It is not. A correlation identifier
names one execution and attributes nothing to anybody; an actor names a party and is a durable
attribution. Merging them would put a customer identifier into every log line and every span — a
disclosure into systems with different access control and months of retention (`INV-AUD-02`) — and
would make the audit trail's actor a function of tracing configuration. `CorrelationSinkCoverageTest`
records `security` as deliberately *not* a correlation sink for this reason.

**`InheritableThreadLocal`.** The obvious mechanism and wrong on every server: the value is copied
when a thread is *created*, not when work is submitted, so a pooled worker keeps the actor of
whichever request happened to create it. For correlation that misattributes a trace; for an actor it
misattributes a financial action to a real person. Context is captured at submission, exactly as
`CorrelationContext` does.

**Threading an `Actor` parameter through every call.** Explicit, and it fails in the direction that
matters: a signature that must carry an actor invites callers to pass whatever compiles. The
context makes "no actor" representable and therefore refusable.

**Waiting for Phase 1.** The audit table exists now, and `INV-HIST-01` means a record written with
the wrong attribution cannot be corrected — only compensated. The abstraction has to be right
before the first record, which is the argument ADR-0010 already made for introducing it in Phase 0.

## Consequences

- Code that must record an actor cannot silently get the wrong one; it gets an error naming the
  wiring defect.
- `enterSystem()` is the complete, searchable list of places claiming to act as the platform.
- Phase 1 adds a filter that establishes a scope from an authenticated principal. It changes no
  audit schema — asserted today by `Phase1IdentityFitsTheAuditSchemaTest`, which writes a record as
  every non-`SYSTEM` actor type carrying the identifier shapes real identity providers issue.
- A caller can still forge `new Actor("system", ActorType.SYSTEM)`. No static rule distinguishes
  that from an actor built out of a real identity, which is why actors are constructible at all.
  The rule removes the easy path, not every path — recorded rather than glossed.
- Two contexts must now be propagated across a handoff. They compose
  (`SecurityContext.propagate(CorrelationContext.propagate(pool))`) rather than combining, because
  they are separate concerns with separate lifetimes.

## References

- ADR-0010 — audit trail; the actor abstraction this implements
- ADR-0019 — default-deny redaction; the same argument about shortcuts
- `INV-AUD-01`, `INV-HIST-01`, `INV-HIST-03`
- `MODULE_ARCHITECTURE.md` §6
