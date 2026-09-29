# ADR-0067 — Every externally settling completion opens its expectation in its own transaction

Status: Proposed (2026-09-28, the Phase 7 → 8 transition)
Date: 2026-09-28
Phase: 8
Context: Reconciliation · Payments · Merchant · Ledger
Supersedes: nothing. Realises `INV-SET-02` ("every operation expected to settle externally
creates a tracked expectation") for every completion Phases 5–7 built, on the ownership ADR-0064
decides (reconciliation owns the Settlement Expectation) and the declarations ADR-0059 §4 made
(each external rail names its own clearing position). The co-commit rule is ADR-0043's — an
operation and its record commit together — carried one record further.

## Context

Three ledger positions hold value in flight to an external counterparty: `SETTLEMENT_CLEARING`
(the card PSP), `INSTANT_CLEARING` (the instant scheme) and `PAYOUT_CLEARING` (the payout
provider). Ten completions across five appliers post to them today, and nothing discharges them:
the positions only accumulate. A clearing balance is one number. Reconciling it against a
counterparty's report means knowing, item by item, which operation put each unit of value there,
what the counterparty will call it, and by when it should appear. Phase 8 names that decomposition
the **Settlement Expectation** (ADR-0064), and the position proof (`INV-REC-06`) says what
"explained" means: DR−CR of each clearing position equals the signed sum of its open expectation
remainders, less its unallocated, unparked items.

The ledger cannot supply the decomposition on its own:

- `PostingResult(entryId, replayed)` returns the entry, not its lines. `journal_entry` has no
  external-reference column: only `reference` (the operation id) and an `idempotency_scope` that
  is not unique (V004).
- The match keys are other modules' facts: the PSP's capture and refund references, the ARN
  (`payments.clearing_record`, which deliberately holds no amount, V015), the scheme reference,
  our end-to-end and `rfd-`/`pyo-` references, the dispute reference, the payout provider's
  reference. The announced settlement cycle is known then too, and it serves as a matching
  attribute rather than a key (point 5). `payments` and `merchant` know all of these at the moment
  of completion, and nobody else does.
- ADR-0064 gives `reconciliation` no build edge from `payments` or `merchant`, and neither of them
  an edge to it. Whatever records an expectation must cross a port that `app` composes (the
  `CaptureComposition` and `RailOutcomeObserver` shape).

So the question is when, and through what, an expectation comes into existence. The three
candidate designs split on it (the transition's synthesis, decision 3): one put an `AFTER INSERT`
open-item trigger on every clearing journal line; two put a co-commit port on the completing
code. The forces:

1. **A completion without its expectation is value silently assumed.** That is the failure
   `INV-SET-02` exists to prevent, and a gap of even one commit is a gap a crash can widen into
   a permanent one.
2. **A completion is an external fact.** A capture the PSP executed, a chargeback the network took,
   a withdrawal the scheme accepted cannot be refused because a reconciliation record could not
   be written. ADR-0061 rejected refusing to record a chargeback on exactly this ground.
3. **Ten instances apply one outcome.** A webhook door, a resolution sweep and a synchronous
   flight can all reach the same completion, and exactly one of them acts.
4. **History exists.** Every completion from Phase 5 to Phase 7 posted to a clearing position
   with no expectation behind it.
5. **A writer outside the port must still be seen.** A raw-SQL correction, or a future poster that
   forgets the port, would otherwise leave a clearing line no expectation explains, and every proof
   would then disagree with the ledger for a reason nobody recorded.

This ADR decides how the internal completions open their expectations. The `REMITTANCE`
expectation a report's acceptance opens is ADR-0065's; matching is ADR-0068's; the breaks a
collision raises are ADR-0069's; the unmatched confirmation's suspense item is ADR-0070's; the
payout return as a merchant fact is ADR-0073's.

## Decision

1. **The expectation is opened inside the completing transaction, through a required port.**
   - `payments` declares `SettlementExpectations`; `merchant` declares
     `PayoutSettlementExpectations`. `app` implements both with one
     `ReconciliationExpectationRecorder`, which delegates to `reconciliation.ExpectationRegister`.
   - The recorder runs on the caller's connection, in the caller's transaction, **after the posting
     whose clearing line it records** (it needs the entry id). The completion, its posting and its
     expectation commit together or not at all.
   - No event carries it, no Kafka consumer, no after-commit hook: Kafka is not financial truth
     (CLAUDE.md rule 12) and Phase 8 has no consumer on which any correctness rests.
   - Each port is a **required constructor parameter** of every applier that calls it, so the
     wiring is decided at compile time (the `RailOutcomeObserver` and `PostingObserver`
     reasoning: a defaulted overload is the quiet path a later author takes). Unlike
     `RailOutcomeObserver.NONE`, no do-nothing implementation ships in production code. A meter
     left unread loses a count; a completion that opens nothing loses track of money. Tests take a
     recording double from the test fixtures.

2. **It is called at the applier, past the acting exit, and nowhere else.** The seam sits where
   each completion is written, not at the doors, so the webhook door, the callback door, the
   synchronous flight and every resolution sweep are different *arrivals* of one call.
   `JudgementWritersAreConfinedTest` already pins every attempt, refund and withdrawal judgement to
   its applier. Each other completion has exactly one poster: dispute stages `ChargebackAccounting`,
   unmatched confirmations `UnmatchedConfirmations`, payouts `MerchantPayoutOutcomes`. The port is
   called only in the branch where **this call's own conditional transition fired** (past the
   acting exit, the `if (!acting)` return each applier already has). A converged application
   opens nothing.

   | Completion | Posting key (existing) | Applier | Kind | Direction |
   |---|---|---|---|---|
   | Card capture (top-up or checkout) | `payment-capture:<attemptId>` | `PaymentOutcomes` | `CARD_CAPTURE` | `INBOUND` |
   | Card refund | `payment-refund:<refundId>` | `PaymentOutcomes` | `CARD_REFUND` | `OUTBOUND` |
   | Chargeback | `dispute-chargeback:<disputeId>` | `ChargebackAccounting` | `CHARGEBACK` | `OUTBOUND` |
   | Chargeback won | `dispute-won:<disputeId>` | `ChargebackAccounting` | `CHARGEBACK_REVERSAL` | `INBOUND` |
   | Dispute fee | `dispute-fee:<disputeId>` | `ChargebackAccounting` | `DISPUTE_FEE` | `OUTBOUND` |
   | Instant pay-in | `payment-execution:<attemptId>` | `PaymentOutcomes` | `PUSH_PAY_IN` | `INBOUND` |
   | Unmatched pay-in confirmation | `unmatched-confirmation:<rail>:<ref>` | `UnmatchedConfirmations` | `UNMATCHED_CONFIRMATION` | `INBOUND` |
   | Instant withdrawal | `wallet-withdrawal:<withdrawalId>` | `WithdrawalOutcomes` | `PUSH_WITHDRAWAL` | `OUTBOUND` |
   | Return payment | `payment-refund:<refundId>` | `PaymentOutcomes` | `PUSH_RETURN` | `OUTBOUND` |
   | Merchant payout | `merchant-payout:<payoutId>` | `MerchantPayoutOutcomes` | `MERCHANT_PAYOUT` | `OUTBOUND` |
   | Payout return (new, ADR-0073) | `merchant-payout-return:<payoutId>` | `PayoutReturns.apply` | `PAYOUT_RETURN` | `INBOUND` |

   A stage walk (a "won" heard before its chargeback, ADR-0061 §2) posts both stages in one
   transaction and therefore opens both expectations in it. Postings that touch no clearing
   position open nothing: the dispute attribution, restoration, loss and re-attribution entries,
   transfers, and every book-rail movement. A book payment, book refund or wallet-to-wallet
   transfer is `FINAL_ON_POSTING`, and `SettlementModel.NONE` is `INV-SET-01`'s documented per-rail
   guarantee (ADR-0059 §4). The unmatched confirmation's call also opens its CREDIT suspense item
   and its `UNKNOWN_EXTERNAL` break (cause `PARKED_ON_RECEIPT`) in the same transaction, once
   `P8-TSK-020` lands (`INV-REC-09`, ADR-0070).

   The completions are the tree as the Phase 7 → 8 transition's repairs left it:
   - **An unmatched confirmation is posted for each cause its parking records** (payments
     `V023`). An `UNATTRIBUTED` execution, an `ATTEMPT_CONCLUDED` execution on a concluded
     attempt, and the executed value of a pay-in whose amount differed (`AMOUNT_MISMATCH`) all
     open one `UNMATCHED_CONFIRMATION` expectation. The mismatched pay-in itself fails
     `DECLINED`, posts no execution and opens no `PUSH_PAY_IN`, and the one applier judges the
     amount for the callback and the inquiry alike.
   - **A capture never sent or never received on a rail declaring `VOID` redirects into the
     void.** It never ends `FAILED` with the authorization standing. It posts nothing to the
     clearing and opens nothing.
   - **A chargeback against a closed merchant** parks the merchant's share in
     `CHARGEBACK_RECOVERABLE`, because the close now closes the payable's ledger account. Its
     clearing line is unchanged, and so is its `CHARGEBACK` expectation. The expectation copies
     the clearing line, never the counterparty side.

3. **It is keyed on the declared clearing purpose, never on a rail's name.**
   - The applier asks the stored rail's declaration, `RailCapabilities.clearingPurpose()`. That is
     the same read that chose the posting's clearing account, so the
     expectation's position and the posted account come from one declaration. The call is made
     exactly when the purpose is present. `RailCapabilities`' compact constructor already makes
     "a clearing position" and "something external settles" (`settlement() != NONE`) the same
     statement, so a book rail never calls and a new external rail opens expectations the moment it
     declares its position.
   - The payout's position is read from the new `merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE`,
     to which `MerchantPayoutOutcomes` also switches for its posting.
   - The port carries no source. The recorder resolves it from the position purpose through the
     source register `app` composes from the same declarations (ADR-0064). That register holds
     exactly one source per settling position (`INV-SET-05`, proven total at build time by
     `EverySettlingPositionHasASource`), so the lookup cannot miss at runtime.
   - **The direction is derived from the clearing line, never chosen:** a DEBIT line on the position
     is `INBOUND`, a CREDIT line `OUTBOUND`. The position proof's sign is therefore the ledger's own.
   - Nothing in the recorder, `settlement` or `reconciliation` names a rail or a `*_CLEARING`
     purpose. `RailVocabularyIsConfinedTest` gains `SettlementBeans.java` as a configuration file,
     and `clearingPositionsAreNamedOnlyByTheirDeclarations` widens to both new modules.

4. **What an expectation records: copies of immutable facts, taken at completion.**
   `reconciliation.expectation` (V002, `P8-TSK-004`):
   - `kind`, `operation_ref` (the identifier its posting key names), `posting_key`;
   - `source_id`, `position_purpose`, `ledger_account_id` (the posted clearing account);
   - `direction`;
   - the ADR-0003 money triple, equal to the clearing journal line;
   - `journal_entry_id`;
   - `posting_date`, copied from the entry and never re-read from the clock;
   - `settlement_cycle`, nullable: the cycle a pay-in's confirmation announced, a withdrawal
     stored, or an unmatched confirmation's parking stored. It is a matching attribute, never a
     key (point 5);
   - `expected_by = posting_date + lag_days[kind]` from the source's active rule set, with
     `rule_set_id NOT NULL` pinned on the row. The seeded lags are card 3, refund and dispute 3,
     instant 1 and payout 2 days;
   - `opened_at` from the injected clock;
   - born `OPEN`, with `allocated_minor = resolved_minor = 0`.

   - **The arbiters:** `UNIQUE (kind, operation_ref)`, `UNIQUE (journal_entry_id,
     ledger_account_id)`, and `CHECK ((kind = 'REMITTANCE') = (journal_entry_id IS NULL))`.
     Expectations are keyed on (entry, account) because a journal line's own id never leaves the
     ledger.
   - **No cross-schema foreign key.** The row holds copies that reconciliation owns, the practice of
     `unmatched_confirmation.entry_ref`, so matching never reads another module's tables to
     allocate (ADR-0064).
   - Opening writes no event and no audit record of its own. It is part of a completion its
     applier already audits and announces, and an event per expectation would fan out one record
     per payment to no consumer.

5. **Keys are scoped per source; the ARN arrives as an alias, in either order.**
   - **Scoped keys.** Each opener registers the typed references the counterparty will quote, in
     `reconciliation.expectation_key (source_id, key_kind, key_value, expectation_id)` with
     `UNIQUE (source_id, key_kind, key_value)`:
     - a capture: its `PSP_CAPTURE_REF`, and its attempt as the `CARD_ATTEMPT` anchor an alias
       resolves to;
     - a card refund: its `PSP_REFUND_REF` and `OUR_REF` (`rfd-…`);
     - each dispute stage: `DISPUTE_CB_REF`, `DISPUTE_REV_REF` or `DISPUTE_FEE_REF`;
     - a pay-in: its `SCHEME_REF` and `END_TO_END_REF`;
     - an unmatched confirmation: its `SCHEME_REF`;
     - a withdrawal: its `SCHEME_REF` and `END_TO_END_REF`;
     - a return: its `SCHEME_REF` and `OUR_REF`;
     - a payout: its `PAYOUT_PROVIDER_REF` and `OUR_REF` (`pyo-…`);
     - a payout return: **none** (below).

     Because keys are scoped per source, counterparty tokens can never collide across
     counterparties, and our own references lose nothing by the scoping. Where one reference names
     several expectations of one source, the key kind carries the stage, and the dispute's three
     stage kinds are the pattern.
   - **The instant rail's scheme references are one per execution.** Since the Phase 7 → 8
     transition's repair, every producer claims its `(rail, scheme_reference)` in
     `payments.scheme_execution_claim` (payments `V023`) before money moves: a pay-in, a
     withdrawal, a return or a parking. Two producers can therefore never register one
     `SCHEME_REF` on one source. A `KEY_COLLISION` on it is a defect, never a race.
   - **The unmatched confirmation's other stored facts are not keys.** Since payments `V023` a
     parking also stores `named_reference`, `cause` and `attempt_id`. None of them is registered
     as a key. A named reference may be an attempt's own end-to-end reference, which that
     attempt's expectation already keys. The facts travel instead to the suspense item
     `P8-TSK-020` opens (ADR-0070), which keys on them. The parking's stored `settlement_cycle` is
     copied into the expectation's cycle attribute.
   - **A payout return opens no key of its own.** Its references are its payout's, already held
     by the `MERCHANT_PAYOUT` expectation under `UNIQUE (source_id, key_kind, key_value)`. A key
     opened for the return would be a `KEY_COLLISION` and a false `DUPLICATE_INTERNAL`. The return
     expectation is instead reached through its operation: `UNIQUE (kind, operation_ref)`, where
     the kind is `PAYOUT_RETURN` and the operation is the payout.
     - Rule set v1, seeded by `P8-TSK-004` and frozen, declares the payout source's
       `PAYOUT_RETURNED` rule **operation-anchored** (ADR-0068 §2). The line's keys reach the
       payout only as the anchor of its operation, and never reach its OUTBOUND
       `MERCHANT_PAYOUT` expectation as a candidate. An INBOUND return line is therefore never
       key-matched against its own payout, and never raises a direction mismatch.
     - Until the return exists, the line waits `UNMATCHED` and the matcher raises no break for it.
       `P8-TSK-019`'s return worker resolves the line through the payout's stored provider
       reference to the payout row and applies the return, which opens the expectation this
       lookup finds (ADR-0073).
     - No rule set v2 or activation is needed for returns.

     *(Resolved at the transition: the design listed a return under its payout's own
     `PAYOUT_PROVIDER_REF` and `OUR_REF`, which the per-source unique would make a collision on
     every return. Resolved again by the Phase 7 → 8 transition's consistency review, A4: the
     return-qualified key kinds this passage first adopted would have had to be named in rule set
     v1. `P8-TSK-004` seeds and freezes v1 long before `P8-TSK-019` could name them, and with
     unqualified keys an INBOUND `PAYOUT_RETURNED` line would hit the OUTBOUND payout and park at
     once as `REVERSAL_MISMATCH`. The operation-anchored lookup needs no new kind.)*
   - **The announced cycle is an attribute, not a key.** The settlement cycle a pay-in's
     confirmation announced, a withdrawal stored, or an unmatched confirmation's parking stored
     (`payments.unmatched_confirmation.settlement_cycle`, `V023`) is recorded on the expectation
     row. It is compared at matching as a tie-breaker and a report dimension, and a different
     cycle is a `TIMING_DIFFERENCE` (ADR-0068). A return has none: its cycle is learned from the
     scheme's report onto the item (`learned_cycle`). This ADR is the authority for that rule, and
     every Phase 8 document that called the cycle "key `SETTLEMENT_CYCLE`" was aligned to it by
     the Phase 7 → 8 transition's consistency review (A5).
     *(Resolved at the transition: the design called the cycle a key, but one cycle names many
     operations, and under `UNIQUE (source_id, key_kind, key_value)` every operation after the
     first in a cycle would have been a collision. The column is `P8-TSK-004`'s, in V002.)*
   - **The ARN alias.** `PaymentClearing`'s acting insert, the `RECORDED` outcome, registers
     `reconciliation.reference_alias (source_id, ACQUIRER_REF, <arn> → CARD_ATTEMPT, <attemptId>)`
     through the port, under the same per-source unique.
     - The clearing notice may arrive before its capture's expectation exists or after it. The
       matcher resolves the alias in a local, immutable two-hop join (alias → anchor key →
       expectation), so the order never matters.
     - `ALREADY_RECORDED` and `REFERENCE_CLAIMED_ELSEWHERE` register nothing: the first record
       stands, as it does in `payments`. Neither does `SECOND_PRESENTMENT`, the outcome the
       Phase 7 → 8 transition's repair gave a second, different clearing of one capture. It is
       loud and counted as unmappable, and its references rest only in the retained evidence.
       Its cleared amount is Phase 8's evidence, carried by the PSP report's line (ADR-0065,
       "Clearing-level evidence").
     - A clearing that attaches to a voided or failed attempt still aliases. The matcher then finds
       no candidate, and the lookup types the break (ADR-0068, ADR-0069). The ARN is evidence
       whatever the attempt's state. Since the same repair, a capture never sent or never
       received on a rail declaring `VOID` ends voided rather than `FAILED`, and a void the
       provider never received is re-sent rather than concluded. A clearing that still arrives
       for such an attempt is exactly this case.

6. **Infallible for valid input: a collision becomes a break, never a failed payment.**
   - Every insert the recorder makes is `ON CONFLICT DO NOTHING`.
   - **A colliding key or alias** is skipped. The expectation is still inserted, and an
     `expectation_event (KEY_COLLISION)` is recorded and counted. The sweep raises
     `DUPLICATE_INTERNAL` from recorded collisions (`P8-TSK-010`), and the payment completes.
   - **A conflict on either expectation unique** converges on the standing row: an earlier opener's
     or the backfill's, for the same entry, because both uniques name it. If a defect ever produced
     a second clearing entry for one operation, its line would have no expectation, and the
     completeness verifier (point 9) would count it.
   - **What remains fallible is only what should be.** Invalid input is a programming defect: an
     amount ≤ 0, a currency other than the posted account's, a purpose outside
     `AccountPurpose.reconciledPositions()`, or no entry id. It throws, loudly. A database failure
     throws too. Either way **the completion rolls back with its expectation**, and its redelivery
     completes both: the provider's webhook retry, the resolution sweep's next query, or the
     flight's sweeper. This is the coupling the phase accepts (failure scenario 36). Opening
     "best effort" and letting the completion commit without it is refused, because that is
     force 1 exactly.
   - The recorder cannot fail to find a rule set. Rule set version 1 is seeded `ACTIVE` per source,
     and activation retires the prior version in its own transaction, so exactly one version is
     always active.

7. **Ten instances: the acting conditional decides, and the uniques back it.** For ten webhook,
   sweep and flight appliers racing one completion:
   - the applier's conditional transition admits one, and only that one calls the port;
   - `UNIQUE (kind, operation_ref)` and `UNIQUE (journal_entry_id, ledger_account_id)` are the
     backstop that holds even with the acting guard removed. That lock-bypass probe is a counted
     test, not an argument.

   The opener takes **no lock on any reconciliation row**:
   - it reads the active rule set without a lock (a concurrent activation shows either version,
     and either is valid, because it is pinned on the row);
   - it only inserts, and it waits only on a concurrent, uncommitted inserter of the same unique
     value;
   - it never takes advisory namespace 4, which orders allocation and not opening.

   No reconciliation worker waits on a `payments` or `merchant` lock. So the completion's order
   (its own rows, then the ledger's projection rows, then these inserts) closes no cycle with the
   Phase 8 lock order written into DISTRIBUTED_EXECUTION §3. A completion that posts several
   entries, such as a dispute stage or a stage walk, takes the ledger's rows as one union in the
   balance projection's order before its first posting (`PostingService.lockBalancesInOrder`,
   the Phase 7 → 8 transition's dispute repair). The opener's inserts come after all of those
   rows, so they change nothing in that order.

8. **The opening position is adopted once, by a keyed, audited backfill.**
   - The route is `POST /v1/operator/reconciliation/opening-position {reason}`:
     - permission `RECONCILIATION_ADMINISTER`, held by the `RECONCILIATION_CONTROLLER` role
       (identity V017);
     - keyed per principal;
     - audited as `reconciliation.OpeningPositionRecorded` with its reason.
   - It walks the completed operations Phases 5–7 left behind, through `payments`' and `merchant`'s
     public read stores (never a cross-schema SQL join):
     - captures, card refunds, returns, executions and withdrawals;
     - dispute stages and fees;
     - unmatched confirmations, whose cycle it copies into the expectation's attribute from the
       column payments `V023` added;
     - completed payouts;
     - existing clearing records, for their ARN aliases. A second presentment has no clearing
       record of its own, so it has no alias either.
   - It is leaderless and paged by id, one bounded page per transaction. It finds each entry by its
     posting key through the ledger's read API and derives every fact exactly as the live opener
     does, `ON CONFLICT DO NOTHING`.
   - Ten backfills racing live completions converge on the same uniques. A re-run under a new key
     opens nothing that already exists. It also adopts the stragglers a rolling deploy leaves:
     completions applied by instances still running without the port.
   - **History is never assumed settled.** A backfilled expectation carries its completion's own
     posting date. Its `expected_by` is computed from the active rule set, so it ages from when the
     money moved, and whatever no counterparty report covers becomes `MISSING_EXTERNAL` on
     schedule (ADR-0069).

9. **Completeness is proven by a register and a verifier, not by a ledger trigger.**
   - **The expectation-opener register** is a static test that enumerates the posting keys
     production code declares and classifies each one. A key falls into exactly one of three
     classes:
     - it opens an expectation of a named kind, with the database test proving it: exactly one
       expectation whose amount equals its clearing journal line, asserted against the ledger;
     - it touches no reconciled position, with the reason;
     - it is a Phase 8 record that the verifier knows by its own row (`settlement-batch:`,
       `recon-suspense:`, the resolution's adjustment, the repudiation's reversal).

     A new posting key fails the build until it is classified.
   - **The completeness verifier** runs beside the position proof (`INV-REC-06`, `P8-TSK-007`) in
     `app`, in the `TrialBalance` shape: lock-free, "the scrape is the schedule", reported and
     never repaired. It walks every journal line on the three clearing positions and
     `SUSPENSE_UNMATCHED`. A line is known when one of these accounts for it:
     - an expectation names its `(journal_entry_id, ledger_account_id)`;
     - a suspense item owns it (`INV-REC-09`);
     - its entry is a batch's, a park's, a resolution's, a repudiation's or a payout return's.
     *(Resolved at the transition: the design's list omitted the suspense item, so an unmatched
     confirmation's suspense line, which no expectation names, would have read as unknown forever.
     Until `P8-TSK-020` adopts the Phase 7 rows as suspense items, `SUSPENSE_UNMATCHED` truthfully
     reads above zero.)*
   - `finapp.reconciliation.line.unattributed` (tagged `purpose`) counts the lines that are not
     known and must read 0. The verifier computes it in one `REPEATABLE READ` transaction on one
     connection, composing the ledger's line reads with `reconciliation`'s and `settlement`'s read
     APIs. The figure is published behind a refresh floor, NaN never zero, with incremental
     watermarks as the recorded scale path.
   - **It never repairs.** A missing expectation is a defect whose cause has to be found. A verifier
     that opened what it found missing would hide a broken opener. The backfill is the one
     reasoned, audited act that adopts history.

## Alternatives Considered

### A ledger open-item trigger on every clearing journal line
Pros: every writer, raw SQL included, opens an item at database rank, and no Phase 5–7 call site
changes.
Cons:
- It changes the platform's most-probed posting path. Every clearing line would pay a trigger on
  top of V004's per-line re-validation, which is already O(N²) in the entry's line count.
- The ledger would own reconciliation's items, or write into reconciliation's schema. That inverts
  ADR-0064's ownership and the direction `INV-LED-04` protects.
- The trigger sees only an account, a direction, an amount and the entry's `reference`. The keys
  are `payments`' facts, so a second writer would have to enrich every item afterwards. That
  rebuilds the port, and adds a window before it runs.
- The design that proposed it paired items under per-item advisory locks, which carries the
  lock-table exhaustion risk that design itself found.

The register and the verifier deliver the same every-writer detection. They do not prevent a
foreign writer's line, but they name it at once.

### Open the expectation later: after commit, from an event, or by a sweep
Pros: the completing transaction is untouched, and a recorder defect cannot fail a payment.
Cons:
- A crash between the commit and the hook, a consumer outage, or a sweep not yet run leaves value
  in a position with no expectation. That is force 1, and it has to be repaired permanently rather
  than prevented once.
- `ledger.JournalEntryPosted` carries `{entryType}` only, and events carry no amounts, so the
  consumer would read everything back from its owners.
- Kafka is not financial truth.

The `CommittedRailOutcomes` after-commit shape is right for meters, where a rolled-back judgement
must not count. It is wrong for a financial record, where a committed completion must not go
untracked.

### Derive expectations at match time from the owning modules' tables
Pros: no second copy of any fact, and nothing to open.
Cons:
- The disposition (allocated, resolved, overdue) must live somewhere. In `payments`' rows it is
  shared mutable ownership. In a side table keyed by foreign rows it is this ADR's expectation
  without its facts.
- The matcher would read other modules' tables on its hot path. ADR-0064 confines
  `InternalReferenceLookup` to break typing, never allocation.
- `INV-REC-07`'s "allocations never exceed the expectation" could not be a local constraint.
- A decision snapshot needs a candidate whose amount cannot change beneath it.

### A fallible opener: a collision refuses the completion
Pros: a data defect is loud at the moment it happens.
Cons:
- The PSP has already captured and the scheme has already accepted. Refusing the completion leaves
  the money moved and the books silent, which is ADR-0061's rejected "refuse to record" in
  another place.
- Every redelivery fails the same way, so a reference collision becomes a payment stuck forever.

A `DUPLICATE_INTERNAL` break is just as loud, keeps the record, and puts the decision with a
person.

### Decide at each call site, by rail
Pros: each opener is explicit about the rails it serves.
Cons:
- It is a second copy of a declaration the rail already makes.
- `RailVocabularyIsConfinedTest` refuses it.
- A new external rail would open nothing, silently, until someone remembered.

Keyed on the declared clearing purpose, a new rail opens expectations as soon as it declares a
position, and it fails composition if the position has no source.

## Consequences

Positive:
- `INV-SET-02` holds at the commit that creates the obligation: no window, no consumer and no
  repair path to operate.
- Every expectation carries immutable copies of its facts (amount, keys, entry, account), so
  matching, ageing and the proofs read reconciliation's own rows. No module's tables are shared.
- The position proof's identity holds from the first commit of each position, before any report
  arrives.
- Duplicate appliers, the backfill and live completions converge on the same uniques.
- A reference collision never fails a customer's payment.
- The ARN resolves in either order.
- Completeness is detected for every writer, raw SQL included, without touching the posting path.

Negative:
- About eight Phase 5–7 call sites, in the five posting appliers and `PaymentClearing`, gain a
  required parameter and an insert. A
  recorder defect or a database error now rolls back a completion that would otherwise have
  committed. The redelivery recovers it, but it is a real coupling. It is mitigated by the
  infallible insert, the register, failure-injection tests, and re-running the Phase 7 storm and
  dispute battery inside `P8-TSK-004` and `-005`, not only at the review.
- The completing transaction grows by a few inserts after its posting, which extends the hold on
  the clearing projection row slightly. It adds no wait.
- Every settling completion now writes into a second module's schema in one transaction. That is
  sound in the modular monolith (ADR-0001). If `reconciliation` ever leaves the process, the
  co-commit becomes a transactional outbox write in the completing transaction (ADR-0005), and the
  verifier becomes the only completeness proof. This is recorded, not scheduled.
- For a writer outside the port, completeness is detection, not prevention.
- The first ageing sweep after the backfill may raise a burst of `MISSING_EXTERNAL` breaks for
  history no report covers. That is the true state of the opening position, shown.

Operational impact: `finapp.reconciliation.line.unattributed` must read 0 and alerts.
`finapp.reconciliation.expectation.open` and `.overdue` (ADR-0069's ageing) show the tracked
population. Key collisions surface as `DUPLICATE_INTERNAL` breaks. The backfill runs once at the
opening, and again after any deploy that ran completions without the port.
Security impact: one new route, the backfill, under `RECONCILIATION_ADMINISTER`, reasoned and
audited. The openers act inside the completing actor's own transaction and add no privilege.
Expectation keys and aliases hold references only: CONFIDENTIAL, shape-checked, and never a bank
identifier or alias (`INV-RAIL-03`). Amounts are RESTRICTED-FINANCIAL, never in an event, log or
metric.
Financial impact: none posted. No opener moves money. The expectations are the decomposition that
makes each clearing balance explainable (CLAUDE.md rule 11), and a clearing line nobody explains
becomes a counted, alerted fact instead of an unexplained balance.

## Invariants / Constraints

`INV-SET-02` (amended: Enforce becomes `DOMAIN` + `DB-CONSTRAINT` (the expectation uniques, in
the completion's transaction) + `PROCESS` (the completeness verifier), and Verify names the opener
register and the expectation gauges), `INV-REC-06` (new; its clause "every externally settling
completion opens its expectation in its own transaction" is this ADR), `INV-SET-01` (completion is
not settlement: an expectation records what must still be discharged), `INV-SET-05`, `INV-RAIL-01`
and `INV-RAIL-04` (keyed on each rail's declared position, never its name, never netted),
`INV-RAIL-03`, `INV-IDEM-04` and `INV-DSP-02` (one expectation per effect under duplicate
delivery), `INV-REC-09` (the unmatched confirmation's suspense item, through the same call),
`INV-LED-04` (no opener writes a journal row), ADR-0046 (the opener adds no provider call to any
transaction).

## Follow-up

- `P8-TSK-004` — **implemented** (2026-09-29): reconciliation V002 (`expectation` with its
  `settlement_cycle` attribute, `expectation_event`, `expectation_key`, `reference_alias`, and
  rule set v1 seeded `ACTIVE` for the four sources, the payout source's operation-anchored
  `PAYOUT_RETURNED` rule among them, point 5), `ExpectationRegister` with its
  `ON CONFLICT DO NOTHING` converges and counted `KEY_COLLISION` events (point 6), the
  required `SettlementExpectations` port in `PaymentOutcomes` (the card capture and the card
  refund, each in its acting branch after its posting) and `PaymentClearing` (the ARN alias,
  the `RECORDED` branch alone), and `app`'s `ReconciliationExpectationRecorder`, which derives
  amount and direction from the posted clearing line (point 3's "derived, never chosen") and
  resolves the source through the compiled register. The dispute, push and unmatched openings
  of point 2's table, the merchant port, the backfill (point 8) and the register/verifier
  (point 9) remain with their named tasks below.
- `P8-TSK-005`: `ChargebackAccounting`, push execution, `WithdrawalOutcomes`, returns,
  `UnmatchedConfirmations` and the merchant port in `MerchantPayoutOutcomes`, plus the
  expectation-opener register.
- `P8-TSK-007`: the opening-position backfill, the position proof and the completeness verifier.
  Its "unattributed 0" is reached on the clearing purposes there, and on `SUSPENSE_UNMATCHED` with
  `P8-TSK-020`.
- `P8-TSK-010`: `DUPLICATE_INTERNAL` raised from recorded collisions.
- `P8-TSK-019`: the payout return's expectation through `PayoutSettlementExpectations`, opening
  no key and reached through its operation (point 5), and the return worker that the
  operation-anchored rule waits for (automated returns are owner decision O2, a transition
  decision the owner may revisit). If `P8-TSK-019` is cut (owner decision O6, likewise revisitable), its opener and
  its register row go with it, and nothing else here changes.
- `P8-TSK-020`: the unmatched confirmation's suspense item and break through the same call.
- `P8-TST-001`: completeness and the position proof read in every storm round and at rest.
- Until `P8-TSK-004` lands, nothing in this ADR is implemented: every statement is the decided
  design, to be corrected by the tasks that build it.
- The Phase 8 review reads this ADR against the code before accepting it (`P8-DOC-001`).
