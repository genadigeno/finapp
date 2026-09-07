# Mutation-Style Invariant Verification

Every test that claims to protect an invariant must be **demonstrated to fail when that invariant
is deliberately broken**, and the demonstration must be recorded here.

Established by `P0-TSK-038`. This is not a new practice — it is what every task in Phase 0 has
already been doing — so the task is to make it *repeatable and checkable* rather than to introduce
it.

**Why it is a rule and not a habit.** `DEFINITION_OF_DONE.md` §3 lists, among the things that block
done: *"a test would still pass if the invariant it claims to protect were removed"*.
`PHASE_GATES.md` §3 criterion 3 requires that every in-scope `INV-*` have *"at least one test that
fails if the invariant is broken"*. Neither is satisfiable by inspection: a test that cannot fail
looks exactly like a test that passes, and it is worse than no test, because it is believed. This
phase has now found four of them — `secretsAreWrapped`, two rules in `P0-TSK-041`, and a
clock-skew test named for a property it did not exercise.

---

## 1. What counts as a demonstration

A demonstration has three parts, and all three must be recorded:

1. **The mutation** — the specific change that breaks the invariant, precise enough to re-apply.
2. **The command** — what to run.
3. **The observed result** — which tests failed, by name or by count.

"I checked it" is not a demonstration. Neither is "the test asserts X", which is a statement about
the test rather than about what happens when the property is gone.

### Two admissible forms

| Form | What it is | Strength |
|---|---|---|
| **In-suite** | The proof lives in the suite and runs on every build: a fixture that violates the rule, asserted to be rejected | **Strongest.** Re-run continuously, so it cannot rot |
| **Recorded** | The mutation cannot live in the suite — it drops a constraint, widens a grant, or changes production code — so it is a written procedure with its observed result | Weaker: it is re-runnable but only if someone runs it |

**Prefer in-suite.** Use a recorded procedure only where the mutation is one the suite cannot
contain, and the register says which form each demonstration takes so the difference stays visible.

**A recorded demonstration is not prose.** Prose says "we proved it fails". A recorded
demonstration names the mutation, the command and the result, so a reviewer at the phase gate can
re-run it rather than trust it.

### What the form tells you

An in-suite demonstration proves the rule has teeth *today, on this build*. A recorded one proves
it had teeth *on the day it was written*. That difference is the reason the register exists: at the
Phase 0 exit gate, criterion 3 asks a question about now, and only the first form answers it
without work.

---

## 2. The register — Phase 0 invariants

Every invariant `FINANCIAL_INVARIANTS.md` marks as Phase 0. `MutationDemonstrationTest` fails the
build if one is missing, or if a row names a test class or method that does not exist.

| Invariant | Demonstrated by | Form | The mutation | Observed |
|---|---|---|---|---|
| `INV-MON-01` | `NoFloatingPointMoneyRulesTest#rulesRejectTheirViolations` | In-suite | Four fixtures, one per rule: a `double` field, a `float` signature, a floating-point call target, a floating-point field access | Rejected on every build. Also proven end to end (`P0-TSK-008`) by a `double` planted in `Money`, a `float` in a signature and a `Double.parseDouble` call, each in a different module |
| `INV-MON-02` | `MoneyColumnsDatabaseTest#schemaRejectsMalformedCurrency` | Recorded | Weaken the currency `CHECK` constraint in the DDL fragment | The constraint tests fail (`P0-TSK-011` review, which added them after probing showed `CHAR(3)` accepts `'US '`) |
| `INV-MON-03` | `MoneyPropertiesTest` | Recorded | Make every `RoundingPolicy` round `CEILING` | The per-policy defining properties fail (`P0-TST-001`) |
| `INV-MON-04` | `MoneyTest` | Recorded | Remove the currency check from `plus`/`minus`/`compareTo` | Cross-currency tests fail (`P0-TST-001` criterion: "currency checking deliberately broken") |
| `INV-MON-05` | `MoneyColumnsDatabaseTest#storedScaleSurvivesIndependentlyOfCurrentCurrencyData` | Recorded | In `MoneyColumns.read`, re-derive the scale from the currency instead of reading the stored column | **Exactly one test fails**, and it is that one. `P0-TSK-038` — see §4 |
| `INV-MON-06` | `MoneyTest` | Recorded | Remove the overflow check from monetary arithmetic | Boundary tests fail (`P0-TST-001` criterion: "overflow handling deliberately broken") |
| `INV-BAL-03` | `AllocationZeroResidualTest` | Recorded | Change the allocator to naive division, discarding the remainder | The sweep fails (`P0-TST-002`) |
| `INV-HIST-03` | `AuditImmutabilityTest` | Recorded | `GRANT UPDATE, DELETE` on `platform.audit_record` to the application role; separately `GRANT UPDATE (reason)` | Table-level fails six tests, column-level two (`P0-TST-007`). The column-level widening is the one that had previously passed unnoticed |
| `INV-IDEM-01` | `IdempotencyRecordSchemaTest` | Recorded | Drop the unique constraint on (scope, idempotency_key) | 17 tests fail (`P0-TST-004`) |
| `INV-IDEM-03` | `IdempotentExecutorTest#differingFingerprintIsRejected` | Recorded | In `IdempotentExecutor.resolveExistingClaim`, remove the `fingerprint.matches` guard | **Two tests fail**, both named for the property: the conflict test and the in-progress conflict test. `P0-TSK-038` — performed during review, because this row's observed result had been *inferred* from `P0-TSK-016`'s sweep rather than recorded |
| `INV-IDEM-04` | `InboxConsumerTest` | Recorded | Drop the inbox primary key | Nine tests fail across three classes (`P0-TST-006`) |
| `INV-EVT-01` | `OutboxWriterTest` | Recorded | Move the outbox write onto its own connection, outside the business transaction | Three tests fail (`P0-TST-005`) |
| `INV-EVT-02` | `NoDirectBrokerPublicationRulesTest#ruleRejectsDirectPublication` | In-suite | A fixture publishing directly to a broker package | Rejected on every build (`P0-TSK-019`) |
| `INV-EVT-03` | `EventEnvelopeTest` | Recorded | Any of eight: drop a mandatory field, reorder two fields of the canonical form, let an emitted event inherit its parent's causation | All eight caught (`P0-TSK-018` review) |
| `INV-EVT-04` | `InboxDeliveryOrderTest` | Recorded | Delete a dedupe record, as a sweep running inside the producer's redelivery window would | The message runs twice with nothing reporting it (`P0-TST-006`) |
| `INV-AUD-01` | `AuditableActionRegistryTest` | Recorded | Plant any of three faults: an action declared but not catalogued, one catalogued but not declared, a `requiresReason` flag that disagrees | Each caught (`P0-TSK-023`). **Partial — see §3** |
| `INV-AUD-02` | `NoUnwrappedSecretRulesTest#rulesRejectTheirViolations` | In-suite | A record component and a getter-only field, each holding an unwrapped secret; and a production write to the MDC | Rejected on every build (`P0-TST-008`, which found the rule could not fail at all before it) |
| `INV-AUD-02` | `CallerCorrelationIsNotPropagatedTest#personalDataReachesNoSink` | In-suite | A **second** enforcement of the same invariant, added by `P1-TSK-002`: the wrapper rule above governs what a *type stores*, and cannot see a caller-supplied identifier the platform propagates on purpose. Mutations: adopt the inbound header again; leak it into the MDC; echo it unvalidated | All rejected. The MDC leak fails **twice** - here and at `onlyCorrelationContextWritesTheMdc` - which is the two enforcements meeting (ADR-0034) |
| `INV-AUD-02` | `CredentialNeverReachesALogTest#aRawPasswordDoesNotPrintItself` | In-suite | A **third** enforcement, added by `P1-TSK-009`, and the invariant's first *real* subject: every demonstration before it used a fixture record written for the purpose. Mutations: `Sensitive.toString` stops masking; the negative control stops leaking | Both rejected. The second matters as much as the first - a control that cannot see a leak makes every `doesNotContain` beside it vacuous |
| `INV-AUD-02` | `NoUnwrappedSecretRulesTest#rulesRejectTheirViolations` (`CompoundSecrets`) | In-suite | The `P1-TSK-009` gate **measured** the rule's vocabulary and found **six of twenty-two entries dead**: the splitter separates `apiKey` into `[api, Key]`, so the compound entries `apikey`, `privatekey`, `signingkey`, `cardnumber`, `mfacode` and `sessionid` could never match the camel-case spelling anyone actually writes. `secretKey` was caught only because `secret` is separately an entry. Mutation: each of the six as a production field | Before the fix a production `String cardNumber` **passed the build** - the PAN field ADR-0019 named to stop PCI scope widening quietly. After adjacent-pair matching, all six fail, and `idempotencyKey`, `companyName` and `spinLockName` stay clean |
| `INV-IDN-01` | `SecretsAreUnwrappedInOnePlaceTest#theUnwrapSitesAreExactlyTheOnesNamed` | In-suite | The **emission** half. `P1-TSK-007` enforced storage at `DB-CONSTRAINT`; nothing bounded where a plaintext may be unwrapped. Mutation: an `expose()` call planted in `app` | Rejected, naming the class. This is the whitelist that replaces the rejected output scrubber - see `CURRENT_STATE.md` §Known Architectural Debt |
| `INV-IDN-01` | `CredentialVerifierLogsNothingSensitiveDatabaseTest#theDiscardedUpgradeWarningIsQuiet` | Recorded | Add the identity identifier to the verifier's one production log line | Fails, naming the identifier. The line is on the *failure* path, which is the half nobody reads until something is wrong |
| `INV-IDN-07` | `AuthenticationEndpointDatabaseTest#everyFailureLooksTheSame` | In-suite | The four failing causes are collected and asserted **equal to each other**, not each against a remembered expectation. Mutations: the failure event names the attempted account; the refusal is thrown so its audit record rolls back; a success is attributed to the platform rather than the proven identity | All caught (`P1-TSK-010`) |
| `INV-IDN-07` | `AuthenticationCostsTheSameDatabaseTest#everyFailingPathDoesTheWork` | In-suite | The **timing** half, and it exists because a mutation survived the response test: making a malformed password fail *without the derivation* left all four responses byte-identical and changed only how long one took. Mutation: `verifyNothing()` replaced by a bare failure | Caught only after this test was added - the response comparison could never have caught it |
| `INV-IDN-07` | `VerificationOutcomeTest` | In-suite | A response cannot be made to diverge by a single-point change: the caller is handed nothing to branch on. Mutation: add a `reason` field to `VerificationOutcome` - the first step any diverging response would need | Caught (`P1-TSK-008`'s reflective guard, re-proven by `P1-TSK-010`) |
| `INV-CON-03` | `AuthenticationLockoutDatabaseTest#theCounterIsNotBypassableByConcurrency` | In-suite | Catalogued at Phase 13 and **first enforced at Phase 1**, because ADR-0032 makes verification expensive and names lockout as part of the same design. Mutation: drop the `+ 1` so the atomic increment is lost | Caught. Ten simulated instances, own connection each, must produce exactly ten |
| `INV-IDN-07` | `AuthenticationLockoutDatabaseTest#aLockedIdentityIsNotCheaper` | In-suite | The oracle lockout creates if it is allowed to fail fast. Mutation: short-circuit the derivation for a locked identity — the CPU relief lockout appears to be for | Caught by **counting derivations**: a locked account answering in a millisecond while an unknown one takes ~46 ms is an existence oracle |
| `INV-IDN-06` | `AuthenticationLockoutDatabaseTest#anExpiredLockResetsTheCount` | In-suite | *Recovery cannot elevate an attacker* has a mirror image: a control must not permanently deny a legitimate one. Mutation: drop "a served lock ends the run" from the reset condition - the defect the gate found, where one failure after an expired lock re-locked for ever | Caught, and both halves of the condition are separately proven: removing the live-lock guard is caught too |
| `INV-CON-01` | `LoginRacingACredentialChangeDatabaseTest#theLoginStopsAtTheConditionalSupersede` | In-suite | A login racing a credential change: the hazard is that upgrade-on-use **reinstates the replaced password**. Mutations: an unconditional `supersede`; the upgrade ignoring its answer | Both caught - but only after the assertions moved from the OUTCOME to the COORDINATION. **Three** mechanisms produce the right end state here (conditional supersede, append-only trigger, partial unique index) and an outcome-only assertion let two mutations survive |
| `INV-AUD-01` | `AuthenticationFailsClosedDatabaseTest#aConnectionKilledMidFlightFailsClosed` | In-suite | Read from the other side: a failure that never committed must leave **no** record claiming it did. Mutation: write the audit record on its own connection, so it survives a transaction that failed | Caught. An audit trail saying somebody logged in when they did not is permanent under `INV-HIST-03`, and worse than no trail |
| `INV-IDN-03` | `NoProcessLocalSessionStateTest#nothingHoldsASession` | In-suite | The acceptance criterion, as a build rule. Mutation: a `Map<String, Session>` field on the production store — a cache that is not static, not a lock, and invisible to all four of ADR-0024's patterns | Caught. Transition risk **R7** was recorded precisely because those rules cannot see this shape |
| `INV-IDN-03` | `SessionLifecycleDatabaseTest#everyUnusableSessionLooksTheSame` | In-suite | Expired, revoked and never-existed must be one answer. Mutations: the lookup ignores revocation; ignores the idle bound; a touch pushes past the absolute bound; the token is stored in clear | All four caught. Each bound is asserted **alone**, because a suite testing them together passes against an implementation checking only one |
| `INV-IDN-03` | `SessionRevocationDatabaseTest#revocationIsImmediateAcrossInstances` | In-suite | The invariant's **own stated verification** — revoke on one instance, refuse on another. Mutation: **a session cache in front of the store**, which is the task's literal acceptance criterion | Caught, and caught **twice**: the behavioural test fails and `NoProcessLocalSessionStateTest` fails independently. Two controls, one defect — the point rather than a duplication |
| `INV-IDN-03` | `SessionRevocationDatabaseTest#revocationWinsAgainstAConcurrentIssue` | In-suite | *"A session must never survive a concurrent revoke"* (`PHASE_1_PLAN.md` §8). Mutations: drop the `FOR UPDATE` from bulk revocation; drop the explicit lock from issuance | The first is caught; **the second survives, correctly** — the foreign key already takes `FOR KEY SHARE`, so that lock was redundant and was removed. An outcome-only assertion caught neither, because both mechanisms leave identical rows |
| `INV-IDN-05` | `AssuranceLevelTest#theOrderingHoldsForEveryPair` | In-suite | The mechanism `INV-IDN-05` names as its own enforcement, and the completion gate found it had **no test at all**. Mutations: `atLeast` always satisfies (the boolean, restored); a level added without touching the `CHECK` constraint | Both caught. Every pair is swept rather than sampled, because the wrong pair would be the one nobody thought to write down |
| `INV-IDN-05` | `SessionRotationDatabaseTest#theOldIdentifierIsRefused` | In-suite | Session fixation: elevating in place lets an identifier stolen *before* the elevation become elevated. Mutations: reset the absolute bound on rotation; ignore the conditional revoke's row count; log the rotation as a revocation; make the predecessor's token reachable; **skip replacement when the assurance level is unchanged** | All caught — the last added by the completion gate, which found the same-level rotation (the credential change) untested. A sixth — deriving the new token from the old — **survived correctly**: it derived from the *hash*, which an attacker never holds, and the dangerous version is structurally unreachable |

## 3. What the register does not claim

**`INV-AUD-01` is demonstrated only in half.** The registry proves that a *recorded* action is one
the catalogue knows about. It cannot detect a privileged action that writes **no audit record at
all**, and `P0-TSK-023` recorded that limit rather than glossing it: a registry that looked
complete while the calls were missing would be worse than none, because it would be believed. The
missing half needs the Phase 15 audit-completeness verification.

**Two invariants are enforced by the type system, and a mutation is a compile error.**
`INV-MON-02`'s domain half — there is no no-currency constructor — and `INV-EVT-03`'s
all-fields-mandatory construction cannot be broken at run time, so the register records the
half that *is* mutable (the schema constraint, the field set). `P0-TSK-012` handled the same
situation by invoking `javac` on the substitution; that technique is available when a compile-time
property is worth demonstrating directly.

**A general test is not automatically the protecting one.** `INV-MON-05`'s mutation is *not* caught
by `roundTripsEveryCurrencyScale`, which writes amounts whose scale already matches the currency's
current minor units — so re-deriving gives the same answer and the test passes. Only
`storedScaleSurvivesIndependentlyOfCurrentCurrencyData` fails. Naming the *method* rather than the
class is therefore part of the convention wherever one method carries the property.

---

## 4. The register — `P0-TST-*` items

The task's own acceptance criterion: the convention is applied to every `P0-TST-*` item.

| Item | Its criterion's mutation | Observed |
|---|---|---|
| `P0-TST-001` | Break rounding, currency checking or overflow handling | All three named breaks fail. A later sweep found three surviving mutants and closed two real gaps — a reversed `compareTo`, and `equals` ignoring scale |
| `P0-TST-002` | Change the allocator to naive division | The sweep fails. Both sweeps also assert they encountered indivisible remainders, so neither can pass by allocating only divisible amounts |
| `P0-TST-003` | Remove propagation from a sink | The test fails, with a negative control proving an unwrapped handoff loses the identifier |
| `P0-TST-004` | Drop the unique constraint | 17 tests fail |
| `P0-TST-005` | Move the outbox write outside the business transaction | Three tests fail |
| `P0-TST-006` | Drop the inbox primary key | Nine tests fail across three classes |
| `P0-TST-007` | Widen the privilege grant | Six tests at table level, two at column level |
| `P0-TST-008` | Add a sensitive field without redaction | The rule rejects it — after `P0-TST-008` fixed the rule, which could not fail at all |
| `P0-TST-009` | Revert the `V004` fix so the lease is judged by the client's clock | The corrected skew test fails. Before the correction it stayed green, which is the defect that task found |

---

## 5. Applying this to a new test

1. Write the test.
2. Break the property it protects — in production code, in the schema, or in a fixture.
3. Run the tests. **Read the names of what failed**, not just the count: a mutation caught by an
   unrelated assertion is not a demonstration, and this repository has twice reported one as caught
   when a different test had objected.
4. Revert, and confirm the revert landed. `git checkout --` is a no-op on an untracked file and has
   three times destroyed uncommitted work on a tracked one; copy-based backup is the reliable form.
5. Record the mutation, the command and the result — here for an `INV-*`, in the change log
   otherwise.

**Isolate the mutation to the assertion under test.** `P0-TSK-031` changed two occurrences at once
and the failure came from a different guard entirely, which reported the mutation as caught when
the assertion under test had let it through.

---

## 6. What is enforced

`MutationDemonstrationTest`, on every build:

1. Every invariant `FINANCIAL_INVARIANTS.md` marks as Phase 0 has a register row in §2.
2. Every `P0-TST-*` item in `BACKLOG.md` has a register row in §4.
3. Every invariant §2 names **exists in the catalogue** — the other direction, so a row that has
   quietly stopped applying to anything is not indistinguishable from one that still does. Added
   during review, which found a planted `INV-ZZZ-99` row passing cleanly.
4. Every test class named in either register **exists**.
5. Every method named in **any** row **exists on that class** — so a claim of continuous
   proof cannot point at a method that was renamed or deleted. This caught the `INV-IDEM-03` row
   written during review, which named a method that does not exist.
6. Both forms are present and labelled, so the form column cannot quietly stop carrying
   information.
7. The registers are actually parsed, so a reformatted table fails loudly rather than silently
   matching nothing, and the in-suite rows are asserted to have actually resolved.

**Not enforced:** that a `Recorded` demonstration still reproduces. Re-running one means mutating
production code or the schema, which a build must not do to itself. That is the residual risk the
form column exists to make visible, and the reason to prefer in-suite proofs.
