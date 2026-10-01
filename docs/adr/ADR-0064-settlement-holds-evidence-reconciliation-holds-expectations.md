# ADR-0064 — Settlement holds external evidence; reconciliation holds the expectations and the comparison

Status: Accepted (2026-10-01, `P8-DOC-001` — read against the code and corrected first)
Date: 2026-09-28
Phase: 8
Context: Settlement · Reconciliation · Payments · Merchant · Ledger
Supersedes: nothing. Amends `MODULE_ARCHITECTURE.md` M8, the two modules' §4 register entries and
their §5 ownership rows: the Settlement Expectation moves from `settlement` to `reconciliation`.
Adds four seams to §6's list of transactions that span two modules' state *(it read "three"
until the Phase 7 → 8 transition's consistency review, B5, counted the repudiation seam of point
3)*. Applies ADR-0006's data boundary (schema per module, no cross-schema foreign keys, one writer
per table) to both new modules. ADR-0012's map is otherwise unchanged: contexts 13 and 14 stay two
modules.

## Context

Phase 8 answers one question, continuously and per counterparty, position, currency and item:
does the platform's internal financial state match what the card PSP, the instant scheme, the
payout provider and the settlement bank say happened? Three records take part. The first is the
ledger's clearing positions, broken down into the operations that posted them. The second is
each counterparty's settlement report, and the third is the bank statement.

`MODULE_ARCHITECTURE.md` M8 decided, before any of this existed, that contexts 13 and 14 are two
modules: "Settlement owns external evidence (files, batches, expectations). Reconciliation owns
the comparison and its outcome". `DELIVERY_PLAN.md` Phase 8 §6 lists the Expectation beside the
Settlement File and Line. Designing the phase tested that placement, and it does not hold:

- An expectation is internal state, not external evidence. It is opened when an operation
  completes, before any counterparty has said anything. Every step of its lifecycle after that
  (partially settled, settled, overdue, resolved) comes from matching and ageing, which are
  reconciliation's acts.
- "Allocations never exceed the expectation" (`INV-REC-07`) is a strong constraint only when the
  allocation and the expectation share a schema, where it is a `CHECK` plus a deferred Σ trigger.
  Across two modules it becomes a cross-module invariant held by care.
- If `settlement` owned expectations, `reconciliation` would update another module's rows. That
  is the shared mutable ownership `CLAUDE.md` forbids.

The external line gets the same test. A settlement line is evidence and must never change. Its
disposition (matched, unmatched, parked, offset, resolved), by contrast, is contended state that
ten matcher instances race over.

Three facts about the repository shape the seams:

1. **Nothing opens an expectation today, and the flows that must open one cannot see Phase 8.**
   Every clearing posting is made by `payments` (captures, executions, refunds, returns,
   withdrawals, chargeback stages, unmatched confirmations) or by `merchant` (payouts). Their
   build edges are `payments → ledger, platform` and `merchant → ledger, platform`. Phases 6 and
   7 joined flows like these through ports that `app` implements (`CaptureComposition`,
   `DisputeComposition`, `RailOutcomeObserver`), and no edge was added.
2. **Positions are named only by their declarations.** `RailVocabularyIsConfinedTest` refuses
   rail names outside their declaring files in every module.
   `clearingPositionsAreNamedOnlyByTheirDeclarations` confines each rail's clearing purpose to its
   `RailCapabilities`. The payout provider is not a declared rail: its position,
   `PAYOUT_CLEARING`, is named inside `merchant`.
3. **Kafka carries no financial truth** (`CLAUDE.md` rule 12). The platform's only Kafka consumer
   is `kyc.CustomerOpenedOpensCase`. Every money path so far joins its effects in one PostgreSQL
   transaction.

## Decision

1. **Two modules, each depending only downward, with no build edge between them.**
   `settlement` (context 13) and `reconciliation` (context 14) are separate modules, as M8
   records. Each depends on `ledger`, `platform` and `sharedkernel`, and on nothing else.

   | Module | Owns (sole writer) | Build edges |
   |---|---|---|
   | `settlement` | **The external side:** the source register (compiled descriptors plus a `settlement.source` identity row); Settlement File (encrypted chunks, receipts, history, and its readmission chain: `readmits_file_id` with the submitters and authentication rules of `V009`); Refused Delivery; Settlement Batch (and its `REPUDIATED` edge, `V010`); Settlement Line with its typed references; ingestion errors; each source's pull permits (`pull_permit`, `V008`); the recognition posting of each accepted batch, requested of `ledger` | `settlement → ledger, platform, sharedkernel` |
   | `reconciliation` | **The internal side, the comparison and the outcome:** Settlement Expectation, with its keys and reference aliases; Reconciliation Batch (the run: a `BATCH` run born of an accepted batch, or a `REPROCESS` run, which names no batch); External Item; Match Decision with its candidate snapshot (and `match_parked_original`, `V012`); Match Allocation (with its append-only counter-allocations, `V013`); Matching Rule Set (rules, tolerances, provider fee schedule, severity thresholds; its machine and `rule_set_event` history, `V012`); Reconciliation Break and its case file; Resolution; Suspense Item; Park; run replays (`run_replay`, `V012`); the record of each break a repudiation closed (`repudiation_closure`, `V013`) | `reconciliation → ledger, platform, sharedkernel` |

   *(The labels are the module register's: "Match Allocation" and "Matching Rule Set", never a
   bare "Allocation" or "Rule Set", which would collide with `lending`'s and `risk`'s names. They
   read bare until the Phase 7 → 8 transition's consistency review, B12. The shorter forms below
   and in the other Phase 8 ADRs mean these two.)*

   Neither module sees `payments`, `merchant` or the other. `app` composes them, as it composes
   every other join.

   **Earlier terms.** The terms that `DELIVERY_PLAN.md` and M8 use keep their meaning under this
   ownership:
   - a Match is a Match Decision together with the Match Allocations it produced;
   - Match Rule and Tolerance are members of a versioned Matching Rule Set;
   - Investigation is the break's case file (assignment, append-only notes, evidence links,
     reclassification) and has no machine of its own;
   - Reconciliation Batch is the run, in table `reconciliation.reconciliation_batch`.

   **What stays with `ledger`:** the journal entries every poster requests, the
   `SUSPENSE_UNMATCHED` line beneath each Suspense Item, and the `adjustment_proposal` beneath
   each posting Resolution (origin `RECONCILIATION`, ADR-0071). Neither module writes a journal
   row (`INV-LED-04`).

   **Provider structures stay at settlement's edge.** They live behind the `SettlementFormat` SPI
   and its adapters under `com.finapp.settlement.format.<format>`, with their vocabulary confined
   there (`SettlementVocabularyIsConfinedTest`, ADR-0066). Pull clients are `app`'s adapters of
   `SettlementReportCollector` (ADR-0008's SPI shape). Their source URLs join
   `ProviderTransportGuard`'s list, so the application refuses to start on a pull endpoint that
   is not `https` or `sftp` off loopback (ADR-0066 §1). `reconciliation` sees only canonical
   lines.

   *Merge trigger:* the no-edge boundary costs more than it protects in either of two cases:
   - `reconciliation` comes to need `settlement`'s rows on its hot path, beyond the copy the
     intake makes;
   - `settlement` comes to need reconciliation's dispositions to decide acceptance.

   Either case reopens this decision by ADR. The edge is never added quietly.

2. **The Settlement Expectation moves from `settlement` to `reconciliation`.** The Context gives
   the three reasons.

   M8 is amended, with provenance, to read: "Settlement owns external evidence (files, batches,
   lines) and recognises it. Reconciliation owns the expectations, the comparison and its outcome
   (matches, breaks, resolutions, suspense items)." §4's `Owns:` lines and §5's rows follow
   point 1.

   **What an expectation holds.** It carries the facts of one completed clearing line (kind,
   operation reference, ledger account, position purpose, direction, amount, journal entry,
   posting date, keys) plus its disposition. It is pinned to the rule set that dated it.

   **`INV-REC-07` is then local.** Three constraints enforce it, all in one schema:
   - `CHECK (allocated + resolved ≤ amount)`;
   - the deferred Σ triggers on both sides of `reconciliation.allocation`;
   - `UNIQUE (external_item_id, expectation_id) WHERE reverses_allocation_id IS NULL`;
   - since reconciliation `V013`, a counter-allocation is bound to its original once
     (`allocation_reversed_once`, `UNIQUE (reverses_allocation_id)`) and as its exact mirror
     (`allocation_counter_mirrors_its_original`: the same item, expectation and amount), for
     every writer.

   **The line's disposition lives in reconciliation too.** `settlement.line` is append-only
   evidence. `reconciliation.external_item` is reconciliation's working copy of it: type,
   direction, amount, dates, keys, fingerprint and position, plus `allocated_minor`,
   `parked_minor`, `offset_minor`, `grace_until` and its status. As built, a bank item's position
   is nullable and it carries `attributed_source_id`: a bank credit or debit stands in a position
   exactly when it is attributed, a bank fee in none (reconciliation `V008`); a scheme item may
   carry the `learned_cycle` its report taught (`V009`). It is joined to its line by
   `settlement_line_id UNIQUE`, with no foreign key.

3. **The seams are ports, each a required constructor parameter, composed in `app`.** A port's
   declaring module never learns who implements it (the `CaptureComposition` shape). A required
   parameter decides the wiring at compile time (the `RailOutcomeObserver` precedent), so no flow
   can be built without its seam.

   | Port | Declared in | Implemented in `app` by | Called by | Transaction |
   |---|---|---|---|---|
   | `SettlementExpectations` | `payments` | `ReconciliationExpectationRecorder` → `reconciliation.ExpectationRegister` | `PaymentOutcomes` (capture, execution, card refund, return), `WithdrawalOutcomes`, `ChargebackAccounting` (chargeback, won, fee), `UnmatchedConfirmations`, `PaymentClearing` (the ARN alias). Called only past the acting exit, and only when the stored rail's `RailCapabilities.clearingPurpose()` is present | Inside the completing transaction |
   | `PayoutSettlementExpectations` | `merchant` | the same recorder | `MerchantPayoutOutcomes` (`COMPLETED`), `PayoutReturns.apply` | Inside |
   | `AcceptedBatchIntake` | `settlement` | `ReconciliationIntake` | the accept leg | Inside acceptance |
   | `InternalReferenceLookup` | `reconciliation` | `JdbcInternalReferenceLookup`, over `payments`' and `merchant`'s public read stores | the matcher and the grace leg | The caller's; read-only |
   | `PayoutReturns` | `merchant`, which also implements it | none: `app`'s `PayoutReturnSchedule` calls it | the return worker | Its own |
   | `SettlementExpectations.parked` *(added by `P8-TSK-020`)* | `payments` | the same recorder → `reconciliation.ParkedConfirmations` | `UnmatchedConfirmations`, for each parking it posts | Inside the parking's transaction |
   | `WaitingPayoutReturns` *(`P8-TSK-019`)* | `reconciliation`, which also implements it (`JdbcWaitingPayoutReturns`) | none: `app`'s `PayoutReturnSweep` reads it | the return worker: lock-free pages, then the item re-read `FOR SHARE` | The worker's, one per item |
   | `EvidenceTargets`, `TraceEvidence` *(`P8-TSK-014`)* | `reconciliation` | `ComposedCaseFileEvidence`, over `settlement`'s, `ledger`'s and `payments`' public read stores | the case file's evidence links and the break's trace | The caller's; read-only |
   | `SettlementBatchRepudiations` *(`P8-TSK-023`)* | `reconciliation` | `ComposedBatchRepudiations` → `settlement.BatchRepudiation` | the repudiation's proposal and approval (`BatchRepudiations`) | Inside the approval |
   | `SettlementReportCollector` *(`P8-TSK-021`)* | `settlement` | `HttpSettlementReportCollector`, one per configured source URL | `SettlementPull` | None held: the fetch holds no connection |
   | `SettlementCycleReads` *(`P8-TSK-021`)* | `payments`, which also implements it (`JdbcSettlementCycleReads`) | none: `app`'s `SettlementPullSweep` reads it | the scheme's cycle worklist | Its own; read-only |

   *(The last six rows were added at `P8-DOC-001`, 2026-10-01: the tasks named added seams by
   the same rule, each a port declared by its owner and composed in `app`, with no build edge.)*

   - **`AcceptedBatchIntake`** carries, in the acceptance transaction, everything
     `reconciliation` owns that an accepted batch creates: the run, the external items and their
     keys, and the `REMITTANCE` expectation. For a bank statement it also carries the suspense
     items and breaks of the statement's unattributed lines (ADR-0065).
   - **`InternalReferenceLookup`** is used **for break typing only, never for allocation**. What
     `payments` or `merchant` say now may decide which break an unallocated remainder raises
     (`MISSING_INTERNAL` rather than `UNKNOWN_EXTERNAL`). Which expectation a line allocates to
     depends only on reconciliation's own rows. That keeps every match decision a function of
     stored inputs (ADR-0068). For a scheme line the lookup reads `payments.scheme_execution_claim`
     (payments `V023`, the Phase 7 → 8 transition's repair): one claim per `(rail,
     scheme_reference)`, taken by every producer before money moves, names exactly one subject
     (`PAY_IN`, `WITHDRAWAL`, `RETURN` or `UNMATCHED`), and a scheme reference no claim holds
     names no completed execution: after grace it types `MISSING_INTERNAL` when its other
     references name an operation still in flight, and `UNKNOWN_EXTERNAL` otherwise.
   - **`SettlementExpectations`: this ADR decides only where the seam sits and which transaction
     it joins.** ADR-0067 decides what the port promises: it is infallible for valid input, a
     collision is recorded and never fails a payment, an opening-position backfill covers
     earlier history, and a completeness verifier checks the result. The recorder resolves an
     expectation's source from the composed register by the completion's clearing purpose (point
     5).

   **The repudiation seam** (the fourth cross-module transaction of point 6). *(The design's port
   table names no seam for batch repudiation. The repudiation is approved in `reconciliation`,
   but the batch and its recognition entry belong to `settlement`.)* It is resolved here without
   an edge:
   - ~~`settlement` writes, on the approval's connection, the reversal of the recognition entry
     through `ReversalService`, the batch's `ACCEPTED → REPUDIATED` and
     `settlement.SettlementBatchRepudiated`.~~ `settlement` writes, on the approval's
     connection, the batch's `ACCEPTED → REPUDIATED`, its history row,
     `settlement.SettlementBatchRepudiated` and its audit record, and nothing else
     (`settlement.BatchRepudiation`). It is reached through the port
     `SettlementBatchRepudiations`, which `reconciliation` declares and `app`'s
     `ComposedBatchRepudiations` implements.
   - The reversal of the recognition entry is posted by `reconciliation`
     (`BatchRepudiations`), through `ledger.ReversalService` on the same connection, under the
     key `settlement-batch:<batchId>` in scope `ledger.reverse`. The counter-allocations,
     unparks, re-postings and item transitions are `reconciliation`'s too.

   `P8-TSK-023` named and built the port. *(Corrected 2026-10-01, `P8-DOC-001`: the design had
   `settlement` post the reversal. As built `settlement` writes no journal request on
   repudiation; the poster is `reconciliation`, which already holds `ReversalService` and the
   balance-projection pre-lock for its own postings, and locks the reversal's lines in one
   union with its unparks. The write order of point 6 is unchanged: reconciliation's rows, then
   settlement's edge, then the ledger's reversal and unparks, then settlement's event.)*

4. **Other modules' facts are copied at completion, never joined.**
   - **No cross-schema foreign keys and no cross-schema SQL joins.** This is ADR-0006's data
     boundary, applied since ADR-0029's registration and practised by
     `payments.unmatched_confirmation.entry_ref`. The following are identifiers the owning
     transaction wrote, with no constraint across the schema boundary: an expectation's
     `journal_entry_id` and `ledger_account_id`; an external item's `settlement_line_id`; a
     resolution's `adjustment_proposal_id`, `journal_entry_id` and, for a `REPUDIATE_BATCH`,
     `settlement_batch_id` (reconciliation `V013`); a rule set's or expectation's `source_id`.
   - **An expectation is a snapshot reconciliation owns.** It holds copies of immutable facts
     taken in the completion's transaction: amount, references, entry id, account. The sources (a
     captured amount, a journal line) never change, so the copy cannot drift from them. The
     position proof checks the copies against the ledger (ADR-0067, `INV-REC-06`).
   - **A proof or trace that spans modules is computed in `app`.** It composes each module's read
     API inside **one** `REPEATABLE READ` transaction on one connection, and folds the result
     with `Money`, never with a SQL `SUM`. This is the reading precedent of Phase 7's storm,
     `P7-TST-001`.
   - **Traces walk identifiers, with no timestamp join.** The break's trace and
     `/settlement-status` walk two identifier chains:
     - the external chain: `file → batch → recognition entry → run → item → decision →
       allocation | park | break → resolution → adjustment proposal → journal entry` (a
       `REPROCESS` run names no batch: its items are reached through their own runs);
     - the internal chain: `expectation → journal entry → operation → provider_evidence`. For an
       unmatched confirmation the operation is the parking row, whose raw statement
       `payments.provider_evidence` finds by stored identifier through its fifth subject,
       `unmatched_confirmation_id` (payments `V023`, the Phase 7 → 8 transition's repair).

5. **The source register is composed from each counterparty's own declaration.** It uses no rail
   name and makes no second copy of any declaration.
   - **`app`'s `SettlementBeans` composes the register.** It declares the four source
     descriptors by hand (code, kind, format, version, channels, remittance pattern), but never
     a position: each position is read from its counterparty's declaration.
     - each rail source's position is read from `PaymentRails.capabilitiesOf(rail)
       .clearingPurpose()`, and every declared rail whose capabilities carry a clearing purpose
       must be discharged by exactly one source, or composition refuses;
     - the payout source reads the new `merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE`.
       `MerchantPayoutOutcomes` switches to the same constant, so the purpose a payout posts and
       the purpose settlement discharges are one constant;
     - the bank source declares **no** position. `CASH_AT_BANK` is named by its poster,
       settlement's bank recognition inside `BatchAcceptance` (ADR-0065, `INV-SET-06`). The only
       other movement of it is the ledger reversal of a repudiated statement's recognition,
       which `reconciliation` posts (point 3).
   - **`settlement.source` stores no position column.** It holds identity and operational state:
     `code`, `kind`, `status`, `next_sequence`. Source codes are never bare rail names
     (`simulated-psp.settlement`, `simulated-scheme.cycle-report`,
     `simulated-payout.settlement`, `simulated-bank.statement`).
   - **The confinement rules widen.** `SettlementBeans.java` joins
     `RailVocabularyIsConfinedTest.CONFIGURATION_FILES`. As built the widening is a sibling
     rule, `theSettlementModulesNameNoClearingPosition`, beside the unchanged
     `clearingPositionsAreNamedOnlyByTheirDeclarations` (which still scans `payments`): neither
     `settlement` nor `reconciliation` may name any `AccountPurpose.*_CLEARING`. Both receive
     positions as values from the composed register.
   - **A missing source fails the build.** `SettlementBeans.composedSettlementSources` refuses
     composition when a settling rail or the payout position has no source, so every startup
     fails too. `EverySettlingPositionHasASourceTest` drives it both ways, with a planted
     uncovered rail (`INV-SET-05`).

6. **Transactions that span two modules' state are few, and each one is named and justified.**
   The platform is one PostgreSQL database with a schema per module. Joining two modules' writes
   in one transaction is the atomicity the modular monolith exists to give (ADR-0001), and
   `MODULE_ARCHITECTURE.md` §6 admits such a transaction only where an ADR justifies it. Phase 8
   adds four seams and uses one that is already listed:

   | Seam | Modules, in write order | Arbiter in the owning schema |
   |---|---|---|
   | A completion opens its expectation | `payments` or `merchant`, then `reconciliation` | the applier's acting conditional; `UNIQUE (kind, operation_ref)` and `UNIQUE (journal_entry_id, ledger_account_id)`, `ON CONFLICT DO NOTHING` |
   | Acceptance hands the batch to reconciliation | `settlement`, then `reconciliation` | `reconciliation_batch.batch_id UNIQUE`; `external_item.settlement_line_id UNIQUE`; the posting key `settlement-batch:<batchId>` |
   | A payout return is applied | as built: `reconciliation` (the waiting item re-read `FOR SHARE`, proceeding only while `UNMATCHED`), then `merchant` (the payout row, the payable `FOR SHARE`), then `ledger`, then `reconciliation` (the `PAYOUT_RETURN` expectation inserted) | the item's share lock against the grace leg's `FOR UPDATE`; `UNIQUE (payout_id)`; the posting key `merchant-payout-return:<payoutId>` |
   | A batch is repudiated (point 3) | `reconciliation` (its locks and rows), then `settlement` (the conditional edge), then `ledger` (the reversal, the unparks and re-postings), then rows already locked or newly inserted (the answering items, the closures, the resolution's `APPROVED`) and `settlement`'s event and audit | the resolution's conditional four-eyes approval; the batch's conditional `ACCEPTED → REPUDIATED`; the reversal key `settlement-batch:<batchId>` under scope `ledger.reverse`, bounded by ledger `V009` (`INV-REV-01`) |
   | Resolution plus adjustment (already on §6's list) | `reconciliation`, then `ledger` | `resolution.adjustment_proposal_id UNIQUE`; ledger `V010`'s approver ≠ initiator `CHECK` and its deferred trigger |

   *(The repudiation row was missing until the Phase 7 → 8 transition's consistency review, B5:
   point 3 creates the seam, so it is the fourth. `P8-TSK-023` built it; the task was not
   cut. The payout-return row read "`merchant`, then `reconciliation`" until `P8-DOC-001`
   (2026-10-01): `P8-TSK-019`'s worker first re-reads the reconciliation item, so the return
   and the grace leg serialise on that row (ADR-0073 §7).)*

   **The transaction is shared, but the tables are not.** Each module writes only its own schema,
   through its own API, on the caller's connection, so "one writer per table" holds. Phase 8's
   postings join `ledger` the way every poster has since Phase 3: through its API, on the
   caller's connection.

   **The seams add no lock cycle.** A completion takes its own locks and the ledger's, then only
   **inserts** reconciliation rows. The most it waits on is a unique held by a concurrent
   inserter of the same key. No reconciliation worker waits on a `payments` or `merchant` lock.
   The payout-return worker is `app`'s and holds one reconciliation row, its item `FOR SHARE`,
   before any `merchant` row; the grace leg that locks the same item `FOR UPDATE` takes no
   `merchant` lock (its lookup is read-only), so the order cannot cycle.
   The lock order reconciliation keeps inside its own transactions is `DISTRIBUTED_EXECUTION.md`
   §3's new row (ADR-0068). Where a seam's transaction posts more than one entry over shared hot
   rows (a dispute stage walk, or a repudiation's reversal beside its unparks), it pre-locks
   their union in the balance projection's order before its first posting
   (`PostingService.lockBalancesInOrder`). That is the rule the Phase 7 → 8 transition's dispute
   repair wrote into `DISTRIBUTED_EXECUTION.md` §3, and Phase 8's multi-entry transactions keep
   it too.

7. **No correctness rests on Kafka.** Phase 8 has no Kafka consumer.
   - Expectations are opened in the completing transaction, not by consuming `PaymentCaptured`
     or `MerchantPayoutCompleted`.
   - Runs are created in the acceptance transaction, not by consuming `SettlementBatchAccepted`.
   - Phase 8's events are written through the outbox on the acting connection (`INV-EVT-01`),
     with the full envelope. They carry identifiers, enums and counts only. They are
     notifications for future consumers (`INV-EVT-04`).

   Each event is produced by the module that owns its aggregate. This amends the module
   register's event lines:
   - **`settlement`:**
     - `SettlementFileRejected`;
     - `SettlementBatchAccepted`, which replaces the planned `SettlementBatchIngested`, because a
       batch is recognised once, at acceptance, not at ingestion;
     - `SettlementBatchRepudiated`.
   - **`reconciliation`:**
     - `ReconciliationRunCompleted`, which replaces the planned per-record `SettlementMatched`;
     - `SettlementExpectationSettled`;
     - `SettlementExpectationOverdue`, which replaces the planned
       `settlement.SettlementExpectationUnmet`: the expectation moved, so its producer moved;
     - `ReconciliationBreakRaised`, `BreakInvestigationStarted`, `BreakResolved`.
   - **`merchant`:** `MerchantPayoutReturned`.
   - **The planned `AdjustmentPosted` event is dropped.** It collides with the audit action
     `ledger.AdjustmentPosted`, and `BreakResolved.journalEntryId` together with
     `ledger.JournalEntryPosted` already carries the fact.

8. **The boundary is a build-graph fact before any domain code exists** (the `P6-TSK-001`
   precedent).
   - `settings.gradle.kts` includes both modules. `ProductionModules` picks them up from the
     classpath.
   - `settlement` `V001` and `reconciliation` `V001` lay privilege floors only: owner
     `finapp_migrator`, `REVOKE ALL FROM PUBLIC`, `USAGE` alone to `finapp_app`, and no tables.
     `finapp_app` is granted no `DELETE` anywhere in either schema.
   - `SettlementModuleIsolationTest` and `ReconciliationModuleIsolationTest` require exactly the
     edges `ledger`, `platform` and `sharedkernel`, and refuse every sibling module. Each refusal
     is probed with a plant. Every sibling isolation test gains both modules, and
     `ModuleBoundaryRulesTest` covers both packages.

## Alternatives Considered

### One module for both contexts
Pros: this is ADR-0012's default of fewer, larger modules, because merging is the reversible
direction. Acceptance and intake become one module's transaction, with no `AcceptedBatchIntake`
port and no working copy of each line.
Cons: it collapses "settlement is not reconciliation" (`CLAUDE.md`, M8) at the one place where the
distinction carries money. Cash recognition would sit inside the comparer, and a defect in the
component that decides matches could move `CASH_AT_BANK`. The line between introducing evidence
(`SETTLEMENT_INGEST`, attested) and judging it (`RECONCILIATION_RESOLVE`, four-eyes) would become
a convention inside one module, not a boundary a build can check.

### A `reconciliation → settlement` edge
Pros: it is workable, and two of the three candidate designs proposed it. Reconciliation reads
lines directly through settlement's API and needs no copy.
Cons: the item's disposition must then live in one of two bad places: on `settlement`'s rows,
which is mutable state in another module's table, or behind a read of `settlement`'s API in every
chunk on the hot path. Gradle refuses the call in the other direction, so the run could still not
be created in the acceptance transaction without a port. The edge adds a dependency and saves
nothing.

### The expectation stays in `settlement`, as M8 and `DELIVERY_PLAN.md` §6 have it
Pros: no amendment is needed, and the expectation sits beside the evidence it awaits.
Cons: allocation and ageing drive its lifecycle, so `reconciliation` would update `settlement`'s
rows, which is shared mutable ownership. `INV-REC-07` would become a cross-module invariant
instead of a `CHECK`, a deferred Σ trigger and a unique in one schema.

### Ledger open items (candidate design B)
Pros: every-writer completeness. An `AFTER INSERT` trigger on every clearing `journal_line` opens
an item whoever posts it, including a raw-SQL poster.
Cons:
- It changes the platform's most-probed posting path.
- It pairs items under per-item advisory locks, which carries the lock-table exhaustion risk that
  B's own author found.
- It puts reconciliation's state into `ledger`'s schema, where the ledger would have to learn
  what an expectation is.

ADR-0067 delivers completeness another way: the infallible port, the opener register, and a
lock-free completeness verifier (`finapp.reconciliation.line.unattributed`).

### Expectations and runs fed by events
Pros: the loosest coupling. No Phase 5–7 call site changes, and `payments` and `merchant` stay
unaware of Phase 8.
Cons:
- An event-fed expectation lags its posting by the relay.
- A stalled or lagging consumer leaves positions that no record explains at a given commit, so
  the position proof could only hold "eventually".
- It rests financial correctness on Kafka (`CLAUDE.md` rule 12), in the phase whose purpose is to
  prove correctness.

## Consequences

Positive:
- Evidence and judgement have different owners, schemas and permissions. Settlement's rows are
  append-only evidence. Reconciliation's dispositions are the only contended state, and every
  contention over them has a PostgreSQL arbiter in one schema.
- `INV-REC-07` is local: a `CHECK`, deferred Σ triggers and uniques, not a convention.
- An expectation exists as of the commit of the posting it tracks, so a position is explainable
  at every commit, not eventually.
- A rail that settles externally gets its source by composition, and a missing source fails the
  build. Neither module learns a rail name.
- Both modules remain extractable (ADR-0006, `MODULE_ARCHITECTURE.md` §7): nothing crosses the
  boundary but ports, identifiers and events.

Negative:
- About eight Phase 5–7 call sites gain a required port parameter, and a defect in the recorder
  could fail a payment. Mitigations:
  - ADR-0067's infallible insert;
  - the opener register;
  - failure-injection tests;
  - re-running the Phase 7 storm and dispute battery inside `P8-TSK-004` and `-005`, not only at
    the review.
- There are two copies: each external item copies its line, and each expectation copies its
  completion's facts. The sources are immutable, so the copies cannot drift. The cost is storage.
- Cross-module proofs scan full history on every scrape. A refresh floor bounds the cost, and
  incremental watermarks are the recorded path to scale.
- `MODULE_ARCHITECTURE.md` and `DELIVERY_PLAN.md` diverge from this design: the expectation's
  owner and four event names. They are amended at the transition, with provenance.
- Traces and reports that span modules are composed by identifier in `app`. That is more verbose
  than a join, which is ADR-0006's intended cost.

Operational impact:
- Two schemas, each with its own migration history.
- Five leaderless schedules, each driving one module's legs: `SettlementIntakeSchedule` and
  `SettlementPullSchedule` for `settlement`, `ReconciliationSchedule` and
  `ReconciliationSweepSchedule` for `reconciliation`. The one that joins two modules,
  `PayoutReturnSchedule`, is `app`'s and calls `merchant`'s `PayoutReturns`.
- An exception is a view over what each module owns, not a third aggregate. Refused deliveries
  and rejected files are `settlement`'s, breaks and blocked runs are `reconciliation`'s, and a
  silent source is a gauge.

Security impact: the module boundary is also a classification boundary.
- Raw file content (`RESTRICTED-PII`) exists only in `settlement`. It is held as ciphertext
  behind the `SettlementFileStore` port and read only through audited content reads.
- `reconciliation` holds references (`CONFIDENTIAL`) and amounts (`RESTRICTED-FINANCIAL`), and
  never a byte of a file.
- Neither schema has a `DELETE` grant.
- `SETTLEMENT_INGEST` guards settlement's evidence-introducing doors (upload, fetch, attestation,
  decline), and the `RECONCILIATION_*` permissions guard reconciliation's (ADR-0066, ADR-0071).
  As built, settlement's other doors take reconciliation's permissions: its reads, content reads
  and re-parse verification need `RECONCILIATION_INVESTIGATE`, and readmission needs
  `RECONCILIATION_ADMINISTER` (`SettlementOperationsController`). *(Corrected 2026-10-01,
  `P8-DOC-001`: this read as if `SETTLEMENT_INGEST` guarded every settlement door.)*

Financial impact: this ADR adds no account and changes no posting. It fixes who requests each
Phase 8 posting:
- `settlement`: the recognition (`settlement-batch:<batchId>`);
- `reconciliation`: parks, unparks and offsets (`recon-suspense:<parkId>`), resolutions
  (entry type `ADJUSTMENT`, through `ledger.AdjustmentService`), and a repudiated batch's
  postings: the `REVERSAL` entry of its recognition (through `ledger.ReversalService`, key
  `ledger.reverse:settlement-batch:<batchId>`, point 3), its unparks and the inverse
  re-posting of a value a resolution already released (each `recon-suspense:<parkId>`);
- `merchant`: the payout return (`merchant-payout-return:<payoutId>`).

An expectation's amount is a copy, never a balance. Balances stay derived from the ledger
(`INV-BAL-01`), and the position proof compares the two.

## Invariants / Constraints

- `INV-SET-02`, as amended: the expectation is opened in the completion's transaction.
- `INV-SET-05`: every settling position has one declared source, composed from its own
  declaration.
- `INV-REC-06`: every reconciled position is explained by its open items.
- `INV-REC-07`: allocation bounds are local to one schema.
- `INV-REC-01`: evidence tables are append-only for every writer.
- `INV-LED-04`, `INV-BAL-01`, `INV-EVT-01`, `INV-EVT-04`.
- `INV-RAIL-01`: positions are read from declarations, never from rail names.
- `INV-RAIL-04`: each source discharges only its own counterparty's position.
- `INV-PAY-03`: provider vocabulary stays in settlement's format adapters.

## Follow-up

- **The tasks that build it:**
  - `P8-TSK-001` builds the two modules, their floors and their isolation tests (point 8).
  - `P8-TSK-002` builds the composed source register, `merchant.PayoutSettlementDeclaration`,
    `EverySettlingPositionHasASource` and the widened confinement (point 5).
  - `P8-TSK-004` and `-005` move every settling completion onto `SettlementExpectations` and
    `PayoutSettlementExpectations` (points 2 and 3; ADR-0067).
  - `P8-TSK-009` builds `AcceptedBatchIntake`.
  - `P8-TSK-010` builds `InternalReferenceLookup`.
  - `P8-TSK-007` builds the cross-module proofs in `app` (point 4).
  - `P8-TSK-019` builds `PayoutReturns` and `PayoutReturnSchedule`.
  - `P8-TSK-023` builds the repudiation seam (point 3, the fourth seam of point 6).
- **At the transition, with provenance:**
  - `MODULE_ARCHITECTURE.md`: M8, §4's two entries (`Owns:` and `Events:`), §5's two rows, and
    §6's transaction-boundary list, which gains all four seams of point 6;
  - the `DELIVERY_PLAN.md` Phase 8 addendum: §6's Expectation owner and §8's event names.
- **If the phase must shrink.** The cut order is owner decision O6, settled at the transition on
  the design's recommendation and open to the owner's revisiting: `P8-TSK-021`, `-019`, `-023`,
  in that order.
  - Cutting `-019` removes `PayoutReturns` and its schedule. A returned payout then falls back to
    a four-eyes `TRANSFER_TO_ACCOUNT`.
  - Cutting `-023` removes the repudiation seam.
  - The boundary itself does not change.

  The automated payout return is owner decision O2, settled the same way. *(As built nothing
  was cut: `P8-TSK-019`, `-021` and `-023` were all kept and built, so O6 was never
  exercised.)*
- **What is implemented (as built, read at `P8-DOC-001`).** The whole decision:
  - `P8-TSK-001` laid the boundary: the two modules with no build edge between them in either
    direction, each with its migrator-owned schema floor (settlement `V001`, reconciliation
    `V001`: `REVOKE ALL FROM PUBLIC`, `USAGE` alone to `finapp_app`, no tables, no default
    privileges), the floors proven live and the refusals probed with planted edges.
  - `P8-TSK-002` composed the source register in `SettlementBeans`, added
    `merchant.PayoutSettlementDeclaration` and the widened confinement (settlement `V002`).
  - `P8-TSK-004` and `-005` moved every settling completion onto `SettlementExpectations` and
    `PayoutSettlementExpectations`, through `ReconciliationExpectationRecorder`
    (reconciliation `V002`).
  - `P8-TSK-007` built the cross-module proofs in `app` (`PositionProof`, the opening
    position).
  - `P8-TSK-009` built `AcceptedBatchIntake` as `app`'s `ReconciliationIntake` (settlement
    `V004`, reconciliation `V003`), `P8-TSK-010` the breaks and suspense items it raises
    (reconciliation `V004`), and `P8-TSK-011` the matcher, with `JdbcInternalReferenceLookup`
    used for break typing only (reconciliation `V005`).
  - `P8-TSK-016`, `-017` and `-018` added the bank, scheme and payout sources (settlement
    `V005`–`V007`, reconciliation `V008`–`V010`).
  - `P8-TSK-019` built `PayoutReturns` and `PayoutReturnSchedule` (merchant `V008`).
  - `P8-TSK-021` built the pull adapters of `SettlementReportCollector` and
    `SettlementPullSchedule` (settlement `V008`).
  - `P8-TSK-023` built the repudiation seam, with `reconciliation` posting the reversal
    (point 3's correction; settlement `V010`, reconciliation `V013`).
- **Acceptance.** The Phase 8 review (`P8-DOC-001`) read this ADR against the code before
  accepting it, following the `P7-DOC-001` precedent. It corrected point 3's poster of the
  reversal, point 5's composition and the names of its guards, and this record.
