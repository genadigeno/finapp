# ADR-0024 — Single-instance assumptions fail the build

Status: Proposed

Date: 2026-09-02

## Context

ADR-0014 established that every service runs as N concurrent instances and N is never 1. It is a
design rule, and design rules decay — which is not a hypothetical here. The audit that produced
ADR-0014 found a real defect in code that had already passed review: `IdempotentExecutor` compared
a lease timestamp written by one instance's clock against a bound computed from another's, so an
instance running six minutes fast would treat a neighbour's fresh claim as abandoned and execute a
money-moving command the neighbour was still executing. **Two financial effects for one request.**

It passed every test, because every test ran in one JVM with one clock.

`INV-MON-01` and the no-ambient-time rule show what happens when a principle becomes a build
failure instead: the floating-point rule caught a `double` on the metrics path, and the time rule
caught a `systemDefault()` written the previous day. The single-instance assumption deserves the
same treatment for the part of it that is mechanically detectable.

## Decision

**Four patterns fail the build in production code**, with the exemption set being the register in
`DISTRIBUTED_EXECUTION.md` §3 rather than a list this rule keeps for itself:

1. **`synchronized`** — methods *and* blocks.
2. **Process-local locks** — `ReentrantLock`, `ReadWriteLock`, `StampedLock`, `Semaphore`,
   `CountDownLatch`, `CyclicBarrier`, `Phaser`, `Exchanger`.
3. **Ambient scheduling** — `ScheduledExecutorService`, `Timer`, `@Scheduled`, `TaskScheduler`.
4. **Static mutable state** — a non-final static field, a static field of a mutable type, or a
   mutable collection built in a static initialiser.

Each is a construct that means something **only within one process**. Its presence is a claim about
coordination that is false the moment a second instance starts, and the failure is worse than the
absence of any lock: the code reads as though the race was handled, which is exactly why it
survives review.

**The `synchronized` block check is not an ArchUnit rule.** ArchUnit models field and method
*accesses*; a `synchronized` method is an access flag and it sees that, while a block is a
`MONITORENTER` instruction and it is blind to it — verified by probe, where the block method
reported no modifiers at all. The acceptance criterion requires failing on a planted block, so that
check reads bytecode with ASM, declared as a test-scope dependency.

**Exemptions are named individually, not by type.** The two permitted `ThreadLocal`s —
`CorrelationContext.CURRENT` and `SecurityContext.CURRENT` — are listed one by one. A type-wide
exemption for `ThreadLocal` would admit the next one without anyone deciding, and §3 exists to force
that decision. The test an exemption must pass is not "it is convenient" but **"it cannot affect
correctness"**: each carries per-flow context within one instance, coordinates nothing, and losing
it costs traceability or refuses an operation rather than making two instances disagree about a
financial fact.

## What this deliberately does not claim

It does not make a design multi-instance correct. No static rule can: the `IdempotentExecutor`
defect that motivated ADR-0014 used **no** lock, **no** static state and **no** scheduler — it was
a clock comparison, and this rule set would not have caught it.

What the rules remove is the vocabulary of single-process coordination, so a claim about
coordination cannot be made silently. The design question — *would this still be correct if ten
instances executed it concurrently* — remains a review question, and `P0-TST-009` is the test
convention that gives it teeth.

There is a second, narrower limit, found by probing the static-state rule rather than by reading
it: **a mutable collection built by a factory method and assigned to an interface-typed static
field escapes**. `static final Map<K,V> M = buildIt();` puts the `new HashMap<>()` inside
`buildIt()` rather than the static initialiser, so neither the field type nor the `<clinit>` check
sees it. Widening to "any mutable construction anywhere in the class" would flag the common and
correct pattern of building a local collection and returning an immutable copy, so the gap is left
open and written down. The directly-constructed forms, the concrete-typed forms,
`Collections.synchronizedMap(new HashMap<>())` and static arrays are all caught — verified
individually.

Recording these limits is the point. A rule believed to be total is more dangerous than one whose
edges are known.

## Alternatives rejected

**Leaving it as a design rule.** That is the status quo ADR-0014 created, and the defect it found
shows what the status quo produces. `CLAUDE.md`'s completion question — "would this remain correct
if 10 instances executed it concurrently?" — is asked by a person who remembers to ask it.

**A type-wide exemption for `ThreadLocal`.** Simpler, and it makes the register decorative: the
third `ThreadLocal` would arrive with no decision, which is precisely how process-local state
accumulates.

**Flagging every static field of a collection interface type.** `static final Set<String> X =
Set.of(...)` is declared as `Set` and is immutable; flagging it makes every constant a violation,
and a rule with false positives is a rule somebody turns off (ADR-0019). The check is on concrete
mutable types plus **construction in a static initialiser**, which catches the interface-typed
mutable field without touching constants.

**Scanning source text for `synchronized` instead of bytecode.** No new dependency, but it is
textual: it cannot distinguish the keyword from the word in a comment, and this repository's
comments discuss `synchronized` often. Bytecode is what the code actually does, and it is the same
basis ArchUnit itself works from.

## Consequences

- The four patterns cannot enter production code without a deliberate, reviewed exemption.
- The exemption set is `DISTRIBUTED_EXECUTION.md` §3, so adding a process-local mechanism means
  registering it — and both current exemptions are proven load-bearing, since the same rule with an
  empty exemption set fires on both.
- A test-scope dependency on ASM. Nothing ships; it exists for the one check ArchUnit cannot make.
- The bytecode sweep asserts coverage **per module**, derived from the classpath by the same helper
  the ArchUnit coverage guard uses. Its first version counted methods instead and passed while
  reading only `app`'s classes — a consumed module reaches a dependent as a **jar**, and a block
  planted in `platform` was invisible.
- Static **arrays** count as mutable state — a `final` reference to an array protects nothing.
  Enum `$VALUES` is excluded as synthetic, which is the only reason arrays can be flagged at all.
- `CountDownLatch` and `CyclicBarrier` are forbidden in production while remaining freely available
  in tests, where they are the correct way to make threads contend. The rules exclude test code.
- The rules pass on arrival, because the §4 audit found the codebase already clean. That is the
  weakest kind of green, which is why every rule is proven to reject a planted violation and both
  exemptions are proven to be doing work.

## References

- ADR-0014 — multi-instance execution, the decision this enforces
- `DISTRIBUTED_EXECUTION.md` §3 (register), §4 (the audit), §5 (standing rules)
- `MODULE_ARCHITECTURE.md` §6 — the enforced rule list
- `INV-IDEM-02`, `INV-CON-01`; `CLAUDE.md` §Mandatory Multi-Instance Microservices Rule
