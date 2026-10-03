# ADR-0073 — A payout return is a merchant fact applied from settlement evidence; the payout's push-rail convergence trigger did not fire

Status: Accepted (2026-10-01, `P8-DOC-001` — read against the code and corrected first)
Date: 2026-09-28
Phase: 8
Context: Merchant · Reconciliation · Settlement · Ledger
Supersedes: nothing. Pays ADR-0057's follow-up ("Payout returns … Phase 8, a compensating DEBIT
`PAYOUT_CLEARING` / CREDIT payable") on the boundary ADR-0064 draws and the co-commit rule ADR-0067
decides. Evaluates the second of ADR-0062 §7's two convergence triggers and records it as not
fired. Amends `INV-MER-02`'s statement (payouts returned, reconciliation attributions).

## Context

A merchant payout is an instruction the platform originates (ADR-0051, ADR-0057). `COMPLETED`
means the payout provider accepted it irrevocably: the hold is released and
`merchant-payout:<payoutId>` posts DR `MERCHANT_PAYABLE` / CR `PAYOUT_CLEARING`, instructed and
not settled (`INV-SET-01`). The glossary already says a payout is "final only at Phase 8's
settlement", and ADR-0065 makes that concrete: the provider's report allocates the payout's line,
and the bank's debit discharges the day's remittance.

What the provider accepted, the beneficiary's bank can still send back. The account may have been
closed since the destination was effected, or the bank may refuse the credit. The provider then
reports the payout returned and nets the funds into what it and the platform owe each other. The
simulated payout provider has no webhook (ADR-0057 §10), and its query only resolves whether a
payout was accepted. So the first place a return is heard, and in Phase 8 the only place, is the
provider's settlement report: a `PAYOUT_RETURNED` line on `simulated-payout.settlement`, quoting
the payout's provider reference and our `pyo-…` reference.

Without a decision, that line finds no internal record. It waits out its grace, parks in
`SUSPENSE_UNMATCHED` and becomes a break, while the merchant's payable still shows the money paid
out. The forces:

1. **The payout's `COMPLETED` is terminal, and it is true.** Merchant `V007` makes it terminal
   (`INV-LIFE-04`), and the provider did execute the payout. A return is a later economic event
   with its own evidence and its own date. ADR-0062 made the same argument for a push-rail
   return: the original stays final, and the return is a new operation.
2. **The external fact comes first.** The provider has already returned the money and will net
   it. The platform cannot refuse to record it; ADR-0061 rejected that option for chargebacks.
3. **A return is routine when nothing needs judging.** If the payout is identifiable and
   `COMPLETED`, the amounts are equal and the payable can take a posting, there is no decision
   left for a person. Four-eyes exists for judgement (ADR-0071), not for arithmetic. The authority
   here is the evidence, which takes effect only whole and authenticated (`INV-SET-07`). That is
   the same authority by which the platform applies a capture or a chargeback from an
   authenticated notification.
4. **The modules own different halves.** `merchant` owns the payout, the payable's attribution
   and therefore the return. `reconciliation` owns the items and the expectations. There is no
   build edge between them (ADR-0064), and the matcher must never wait on a merchant lock
   (`DISTRIBUTED_EXECUTION.md` §3).
5. **Ten instances, and duplicate lines.** A line can repeat within a file or across files, and
   several workers can reach the same return at once.
6. **The merchant's view must name it.** `INV-MER-02` derives the payable from postings, and
   `MerchantPayable` explains it term by term. Today a payable credit facing `PAYOUT_CLEARING`
   or `SUSPENSE_UNMATCHED` falls into `other`: summed correctly, explained wrongly. That is the
   `P7-TSK-010` mislabel class.

There is a second question. ADR-0062 §7 kept the payout on its own port, `PayoutProvider`, and
recorded converging it onto the push rail as "recorded, not scheduled", with two triggers: "a
second outbound rail, or Phase 8 needing one evidence shape for every outbound credit transfer"
(`DECISIONS.md`, Deliberately Deferred). Phase 8 is the phase the second trigger names, so it must
evaluate it.

## Decision

1. **A payout return is a merchant fact, born once, beside a payout that stays `COMPLETED`.**
   Merchant `V008` (`P8-TSK-019`) creates `merchant.payout_return`:
   - `id`; `payout_id UNIQUE`;
   - the ADR-0003 money triple, equal to the payout's;
   - `external_item_ref`: the reconciliation item whose evidence caused the application, an
     identifier with no cross-schema foreign key (ADR-0064 §4);
   - `journal_entry_id UNIQUE`;
   - `returned_on`: the posting date, which is the stored `accepted_on` of the item's settlement
     batch;
   - `value_date`: the item's settlement date, or its business date when the line carries none
     (`COALESCE(settlement_date, business_date)`, `JdbcWaitingPayoutReturns`);
   - `recorded_at`, from the injected clock.

   `RECORDED` is the fact's only state, so the row carries no status column and no machine, the
   way `payments.clearing_record` records a clearing. It is append-only: `SELECT, INSERT` only to
   `finapp_app`, an append-only trigger, no `DELETE`. `DATA_CLASSIFICATION.md` gains its rows in
   the same change.

   One return per payout, because a payout is paid once: `UNIQUE (payout_id)`. A return of any
   other amount is not this fact, and it goes to a person (point 5).

   *(Resolved at the transition: the design gave the money as "`CHECK` equal to the payout", but a
   `CHECK` cannot read another row. The equality is a composite foreign key,
   `(payout_id, amount_minor, currency, scale)` onto the payout's `(id, amount_minor, currency,
   scale)`, the precedent of ADR-0057 §7's destination binding, legal because both tables are
   `merchant`'s.)*

   **The payout stays `COMPLETED`.** Its meaning does not change: instructed, and accepted
   irrevocably. Its `paidOut` term stays in the payable, and the return is a term of its own
   (point 6).

2. **The accounting is one `POSTING`, keyed by the payout and dated from stored evidence.**

   | Entry | Lines |
   |---|---|
   | `merchant-payout-return:<payoutId>` (scope `ledger.post`, entry type `POSTING`, the platform) | DR `PAYOUT_CLEARING` A; CR the merchant's `MERCHANT_PAYABLE` A |

   - **Dates come from stored data, never from the clock.** The posting date is the item's batch
     `accepted_on`; the value date is the item's settlement date (its business date when it has
     none). The posting fingerprint binds both dates (`PostingService.canonicalForm`), and
     `MerchantPayoutOutcomes`'s acting branch already warns that a replay on a later day would
     otherwise conflict. With stored dates, a retry, a
     crash-and-reclaim or a later-day replay converges on the key. This is ADR-0065 §6's rule for
     every Phase 8 poster.
   - **The position comes from the declaration.** The clearing account is read from
     `merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE`, the constant `MerchantPayoutOutcomes`
     posts to and the source register reads (ADR-0064 §5). The return therefore lands on the one
     position the payout provider's evidence discharges (`INV-SET-05`, `INV-RAIL-04`).
   - **No hold is involved.** The payout's hold was released at its completion.
   - **What the debit means.** The return reduces what the platform owes the provider for the
     day. If the payout's cash has already moved, the provider now owes the platform. Either way it
     is the provider's position that moves. The day's remittance, N = T_in − T_out − F (ADR-0065),
     nets the `PAYOUT_RETURNED` line (in T_in) against the day's executed payouts, and the bank
     settles the net.

3. **It opens its expectation in the same transaction** (ADR-0067).
   - Through `PayoutSettlementExpectations`, past the acting exit: kind `PAYOUT_RETURN`,
     direction `INBOUND` (derived from the DEBIT line on the position, never chosen),
     `operation_ref` the payout id that the posting key names, the posting key, the entry id, the
     `PAYOUT_CLEARING` account and the amount.
   - **Keys: none of its own; the rule is operation-anchored.** A `PAYOUT_RETURNED` line quotes
     the payout's own `PAYOUT_PROVIDER_REF` and `OUR_REF`, which its `MERCHANT_PAYOUT` expectation
     already holds under `UNIQUE (source_id, key_kind, key_value)`. So the return expectation
     opens no key: one opened for it would be a `KEY_COLLISION` and a false `DUPLICATE_INTERNAL`.
     It is reached through the operation instead. Rule set v1, seeded by `P8-TSK-004` and frozen,
     declares the payout source's `PAYOUT_RETURNED` line rule OPERATION-ANCHORED (ADR-0067 §5,
     ADR-0068 §2):
     - such a line is never key-matched against the `OUTBOUND` `MERCHANT_PAYOUT` expectation, which
       would be a direction mismatch parked at once as `REVERSAL_MISMATCH` (ADR-0068 §3);
     - its `PAYOUT_PROVIDER_REF`, then its `OUR_REF`, name the payout's operation, and the rule
       allocates only to that operation's `PAYOUT_RETURN` expectation, found by
       `UNIQUE (kind, operation_ref)`;
     - until the return worker has opened that expectation, the line waits: the item stays
       `UNMATCHED`, and the matcher raises no break for it. The worker resolves the payout row
       through the payout's stored provider reference (point 4), and the grace leg types whatever
       is still unapplied at grace (point 5). The one exception is a return the platform's own
       record already contradicts: when `InternalReferenceLookup` answers that the payout is
       terminal — `FAILED`, so no return can ever apply — the run parks the line at once as
       `REVERSAL_MISMATCH`, cause `TERMINAL_STATE_CONTRADICTED`, with no grace clock (point 5).
       *(Corrected 2026-10-01, `P8-DOC-001`: this bullet said every unapplied line waits;
       `Matching.applyUnreached` parks a terminal answer definitively, as for every source,
       proven by `PayoutMatchingDatabaseTest` case (g).)*

     No rule-set v2 activation is needed.
     *(The Phase 7 → 8 transition's consistency review, A4. This bullet had registered the payout's
     keys under return-qualified key kinds that `P8-TSK-019` would name. A frozen v1 could not carry
     kinds named later, and the unqualified rule would have sent every return line to the
     outbound payout's expectation and parked it before the worker could act.)*
   - `UNIQUE (kind, operation_ref)` is therefore both the lookup and a second arbiter of one return
     per payout.
   - The completeness verifier knows the entry both through the expectation's
     `(journal_entry_id, ledger_account_id)` and through `payout_return.journal_entry_id`
     (ADR-0067 §9).

   Through a return, `PAYOUT_CLEARING` stays explained at every commit. With the position proof's
   right-hand side written as Σ s(e)·(open remainder) − Σ s(i)·(unallocated, unparked), for a
   return of A on a day with no other lines, after the payout's own cash has moved:

   | Step | Posting | DR−CR | Right-hand side |
   |---|---|---|---|
   | The day's report is accepted: the `PAYOUT_RETURNED` item `UNMATCHED`; `REMITTANCE` A, `INBOUND` | none (no fee) | 0 | +A − A = 0 |
   | The return is applied | DR `PAYOUT_CLEARING` / CR payable | +A | +A + A − A = +A |
   | The rematch leg allocates the item | none | +A | +A + 0 − 0 = +A |
   | The bank credit discharges the remittance | DR `CASH_AT_BANK` / CR `PAYOUT_CLEARING` | 0 | 0 |
   | *Fallback:* grace expires unapplied, and the item parks | DR `PAYOUT_CLEARING` / CR `SUSPENSE_UNMATCHED` | +A | +A (the remittance; the parked item is excluded) |

   A return reported on the same day as executed payouts nets into one remittance, and the
   identity holds line by line.

4. **A leaderless worker applies it from the evidence. The matcher never does.**
   `app`'s `PayoutReturnSchedule` has the shape every Phase 8 worker shares (the
   `ReturnResolutionSchedule` shape): `SmartLifecycle` with `scheduleWithFixedDelay`, off in test
   contexts, bounds refused at zero, failures contained per row, oldest first, and a
   sweeper-enabled gauge. It joins `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS`
   and `DISTRIBUTED_EXECUTION.md` §3's scheduler register with its argument. Its platform actor
   joins `SystemActorCallSitesAreEnumeratedTest`.

   Each tick walks every one of reconciliation's `UNMATCHED` `PAYOUT_RETURNED` items in claimant
   order, `(source_sequence, line_no)`, a bounded page at a time by keyset, through the
   `WaitingPayoutReturns` port reconciliation declares — a fixed first page of returns not yet
   applicable would starve every return behind it until grace. For each item,
   in its own transaction:
   1. **The worker re-reads the item under a share lock** and proceeds only while it is still
      `UNMATCHED` (point 7).
   2. It calls `merchant.PayoutReturns.apply` with the item's identifier, its payout references,
      its money and its two stored dates (the batch's `accepted_on` is read through `settlement`'s
      read API). `apply`, in order:
      1. locks the payout row `FOR UPDATE`, found by `provider_reference` (`PAYOUT_PROVIDER_REF`,
         set exactly when the payout is `COMPLETED`), then by `provider_idempotency_reference`
         (`OUR_REF`, `pyo-…`);
      2. checks that the payout is `COMPLETED`, has no return, and matches in amount and
         currency, and that the merchant's payable account is `ACTIVE`, read `FOR SHARE` before
         any posting (ADR-0061 §5's rule: share, never upgraded);
      3. posts `merchant-payout-return:<payoutId>` (point 2);
      4. inserts `payout_return` with the entry id;
      5. opens the `PAYOUT_RETURN` expectation through the port (point 3);
      6. writes `merchant.MerchantPayoutReturned` (payoutId, merchantId, journalEntryId) through
         the outbox on the acting connection, and the audit record `merchant.PayoutReturnApplied`.
         Both carry identifiers only. The correlation is restored from the item's run, and the
         causation is the item.

   *(Resolved at the transition: the design listed the insert before the posting. The row carries
   the entry's id and is append-only, so it cannot be completed afterwards. The posting comes
   first, and the insert follows it, uncontended because the payout row lock is already held.)*

   - **The worker allocates nothing.** The rematch leg (ADR-0068) finds the new expectation
     through the operation-anchored rule (point 3) — the item's keys name the payout, and the
     payout's operation names its `PAYOUT_RETURN` expectation — and allocates it in claimant order,
     like any late internal record.
   - **The worker sits outside the matching chunk.** It takes no advisory namespace 4, and no
     matcher ever waits on a merchant row.
   - **A suspended merchant's return applies.** Suspension gates new work and never touches
     arrived outcomes or the payable (`CHECKOUT_MERCHANT_LIFECYCLES.md` §5).
   - **A return reported before its payout resolves waits for it.** A payout still `UNKNOWN`
     while the report says executed and returned is not yet applicable. The worker applies the
     return on the first tick after the resolution sweep completes the payout, inside the item's
     grace.

5. **When the return cannot apply, nothing is written and a person decides.**
   - **A failed check writes nothing:** no row, no posting, no expectation, no audit record, no
     event. Losers and refusals record nothing. The item stays `UNMATCHED`, counted by
     `finapp.reconciliation.item.unmatched`, and is retried each tick until its grace ends
     (`grace_until`, from the rule's `grace_hours`, judged on the database clock).
   - **At grace the break is typed through `InternalReferenceLookup`.** A known payout that the
     return cannot apply to (payable not postable — for a merchant closed since, whose close now
     closes its payable's ledger account, the transition's repairs — amount or currency different,
     payout still in flight and never completed inside the grace) is `REVERSAL_MISMATCH`, cause
     `RETURN_NOT_APPLICABLE`, HIGH. A reference naming no payout is `UNKNOWN_EXTERNAL`, like any
     unknown key (ADR-0069's precedence). Either way the item parks: DR `PAYOUT_CLEARING` / CR
     `SUSPENSE_UNMATCHED`, a CREDIT suspense item owned by its break (`INV-REC-09`, ADR-0070).
   - **A `FAILED` payout's return does not wait for grace.** The lookup's terminal answer is a
     definitive contradiction, so the run parks the line when it first decides it, as
     `REVERSAL_MISMATCH`, cause `TERMINAL_STATE_CONTRADICTED`, by the same posting and the same
     ownership (point 3). The exits below are the same. *(Corrected 2026-10-01, `P8-DOC-001`:
     this point listed "payout not `COMPLETED`" among the grace-time `RETURN_NOT_APPLICABLE`
     causes; a `FAILED` payout is typed at run time, and only a payout still in flight waits.)*
   - **The way out is a person's decision.** The usual path is a four-eyes `TRANSFER_TO_ACCOUNT`
     (ADR-0071) to a `MERCHANT_PAYABLE` that is `ACTIVE` in the currency, share-locked before
     posting: DR `SUSPENSE_UNMATCHED` / CR the payable - **once the payout is `COMPLETED`**. A
     payout still in flight has not debited the payable, so its fallback waits
     (`409 reconciliation.OperationNotTerminal`), and a payout that `FAILED` released its hold to
     the merchant, so its return is no party's credit (`422 reconciliation.ResolutionTargetRefused`;
     an `OFFSET_SUSPENSE` against the provider's execution, or a write-off). *(Corrected
     2026-10-02 by the Phase 8 -> 9 transition, IDEM-1's residual: this listed the in-flight
     payout among the transfer's cases, and a transfer approved while the payout was in flight,
     the payout then failing, credited the merchant twice - `PayoutReturnDatabaseTest`.)* The merchant is re-credited by decision,
     reason-coded (for example `FUNDS_ATTRIBUTED`) and audited. When the provider's own claw-back
     arrives, it closes the break as evidence if it names the returned line (a correction offset,
     `EVIDENCED`), and by a four-eyes `OFFSET_SUSPENSE` if nothing correlates it. ADR-0069's
     per-type table admits no `RECOGNISE_GAIN` on `REVERSAL_MISMATCH`, so money the platform owes a
     merchant never becomes the platform's gain by this path. *(This bullet stated the exclusion as
     its own rule until the transition's consistency review, A1, made the table the one
     authority.)*
   - **The fallback leaves no merchant fact.** The payout then has no `payout_return` row. The
     merchant is re-credited as a reconciliation attribution (point 6), and the break and its
     resolution name the payout.
   - **The fallback is bound to the payout: one return, one credit.** The person's transfer and
     the worker serialise on the payout row `FOR UPDATE`. The transfer's proposal and approval
     take it (after their own break, resolution and suspense rows, before the target) and are
     refused (`409 reconciliation.ReturnAlreadyAttributed`) once a `payout_return` stands or
     another break's transfer for the payout stands. The worker's application asks, under the
     same row, whether such a transfer stands `PROPOSED` or `APPROVED` (reconciliation's
     `PayoutReturnFallbacks`) and, if so, writes nothing (`RETURNED_BY_PERSON`): the person's
     transfer IS the payout's return, and a later report repeating the line waits into a break
     of its own. *(Corrected 2026-10-02 by the Phase 8 -> 9 transition, IDEM-1: the fallback left
     nothing the worker checked, and a repeat of the return in a later day's report carries
     another fingerprint, so a payout completed after the transfer was credited twice -
     `PayoutReturnDatabaseTest`, both orders on the row and the race counted.)*
   - **This is also the whole path if `P8-TSK-019` is cut** (owner decision O6, below).

6. **The payable names the return, and `INV-MER-02` gains its terms.**
   - **Two new terms.** `MerchantPayable.Payable` gains:
     - `payoutsReturned`: a payable CREDIT in a `POSTING` entry that debits `PAYOUT_CLEARING`;
     - `reconciliationAttributed` (signed): every payable line in a `RECONCILIATION`-origin
       `ADJUSTMENT` entry, whatever it faces (the origin rule, resolved below).

     Both are classified in the same single statement as the existing terms:
     `reconciliationAttributed` first, by the entry's origin, which no entry before ledger `V015`
     carries; `payoutsReturned` after the existing terms. So no existing entry's label moves.
     Today both fall into `other`.
   - **Who builds which.** `payoutsReturned` is `P8-TSK-019`'s. `reconciliationAttributed`, the
     customer statement label below and `INV-MER-02`'s attribution clause are `P8-TSK-015`'s: its
     `TRANSFER_TO_ACCOUNT` is their first poster, and they must not leave with `P8-TSK-019` if it is
     cut. *(The Phase 7 → 8 transition's consistency review, A12.)*
   - **The identity.** position = captured − fees − refunded + feesReturned − paidOut +
     payoutsReturned − chargedBack + chargebacksReversed + reconciliationAttributed + other.
     `other` keeps what it holds today: a manual operator adjustment.
   - *(Resolved at the transition: a reconciliation `TRANSFER_TO_ACCOUNT` of an `OUTBOUND` clearing
     remainder (DR P / CR the payable, ADR-0071) faces a clearing position, not suspense. Classified
     by its counterparty alone, it would read as a capture when it faces a sale clearing, and as a
     payout returned when it faces `PAYOUT_CLEARING`. Every payable line in a reconciliation
     resolution's `ADJUSTMENT` entry, meaning origin `RECONCILIATION` from ledger `V015`, is
     therefore `reconciliationAttributed`, classified first, whatever it faces. `payoutsReturned`
     is confined to the return's `POSTING`.)*
   - **`INV-MER-02`'s statement gains** "plus payouts returned, plus or minus reconciliation
     attributions", each clause with its term's task. Its Verify's independent SQL gains the
     payout-return records.
   - **The customer statement derivation** labels every wallet line in a `RECONCILIATION`-origin
     `ADJUSTMENT` entry `RECONCILIATION_ATTRIBUTION`, whatever it faces (a wallet credited by a
     transfer) — the same origin rule, never a `SUSPENSE_UNMATCHED` counterparty test. *(The
     consistency review, A13.)*
   - Each change has a counted test.

7. **With ten instances, the payout row decides, the uniques back it, and the item is judged on
   its locked row.**

   | Contention | PostgreSQL arbiter | Loser |
   |---|---|---|
   | Ten workers on one item | payout row `FOR UPDATE`; `UNIQUE (payout_id)`; the posting key | Finds the return standing and writes nothing |
   | Ten duplicate `PAYOUT_RETURNED` lines, within one file or across files | the same; then the matcher's claimant order | One return, one entry, one expectation. The earliest claimant is allocated, and the rest park as `DUPLICATE_EXTERNAL` (ADR-0068) — with one recorded exception: a later report's repeat of a returned line, already parked `DUPLICATE` with an empty candidate snapshot, can reach the rematch worklist and take the return before the genuine line. Value is conserved; the attribution is wrong — the genuine line parks at grace and its break is raised. Recorded debt, scheduled to Phase 15 (see below). A repeat arriving after a person's fallback transfer finds no `PAYOUT_RETURN` and waits; the worker, on the payout row, finds the transfer and writes nothing, and the repeat's own transfer is refused — one return, one credit *(added 2026-10-02 by the Phase 8 -> 9 transition, IDEM-1; point 5)* |
   | The payout lock bypassed (the probe) | `UNIQUE (payout_id)`, `UNIQUE (kind, operation_ref)` and the posting key alone. A duplicate line from another batch carries other stored dates, so its fingerprint conflicts | Rolls back |
   | The worker against the grace leg on one item | the item's row lock, the judgement made on the locked row | Either order converges: allocated, or parked with no return applied |
   | The worker against a merchant close | the payable `FOR SHARE` against the close's lock | Return first: the close finds the payable owed. Close first: the close has closed the payable's ledger account in its own transaction (the transition's repairs), so the return finds it not postable and is not applicable (point 5) |
   | A crash mid-application | one transaction | Nothing survives, and the next tick applies |

   - **The item is judged on its locked row** (the shape of ADR-0057 §4's send permit). If the
     worker's share lock comes first, the grace leg waits, and when it takes the row it finds the
     committed return's expectation as a candidate, so it allocates rather than parks. If the grace
     leg's lock comes first, the item parks, and the worker, re-reading under its share lock, finds
     it no longer `UNMATCHED` and writes nothing. The worker never applies a parked or resolved
     item.

     *(Resolved at the transition: the design's worker read `UNMATCHED` items without locking
     them. A grace leg that classified an item before a return committed could then park an item
     whose return existed, and a transfer approved before the next rematch would credit the
     merchant twice: once by the return, and once from suspense.)*
   - **`external_item_ref` records the item that won the payout row.** Allocation is the
     matcher's, in claimant order, never the worker's.
   - **The duplicate that takes the return (recorded debt).** The anchored rematch clause, as
     `P8-TST-001` corrected it, puts on the worklist every item whose decisions have not yet seen
     the return as a candidate. A repeat of a returned line in a later report, parked
     `DUPLICATE_EXTERNAL` after the return opened and so with an empty snapshot, qualifies, and
     claimant order (`line_no` across runs) can let it allocate before the genuine line. The old
     clause had the same race when both decisions preceded the return; the correction widened its
     window, and no test covers it. Nothing is created or lost — one return, one allocation — but
     the wrong line holds it. The fix — a `DUPLICATE` verdict counting as having seen the reach,
     with a test against claimant order — is scheduled to Phase 15 by the Phase 8 exit review
     (`P8-DOC-001`, 2026-10-01; `CURRENT_STATE.md` Known Architectural Debt).
   - **Lock order** (`DISTRIBUTED_EXECUTION.md` §3's row): the item row (step 3), then the
     payout row (step 4, the return worker only), then the payable account `FOR SHARE` (step 5),
     then the ledger projection rows sorted by account id (step 6). The posting is the last
     statement that takes a lock. The return row, the expectation, the outbox and the audit rows
     after it are inserts, which can wait only on a same-key inserter, and the payout row lock has
     already serialised that one. The payable is a runtime counterparty's account, so its
     `ACCOUNT` row is share-locked at step 5, before any projection row; the order never rests on
     `PAYOUT_CLEARING`'s seed sorting first, and seed order is load-bearing only for
     `SETTLEMENT_CLEARING` (`V003`, `DISTRIBUTED_EXECUTION.md` §3). The return posts one entry,
     so the multi-entry rule — a transaction posting several entries over shared rows pre-locks
     the union of the platform's rows it will touch in the projection's own order before its first
     posting (`PostingService.lockBalancesInOrder`, `DISTRIBUTED_EXECUTION.md` §3's multi-entry
     lock-order rule, ADR-0061 §5), and takes any runtime counterparty's `ACCOUNT` row —
     `FOR SHARE` where a share lock suffices — before any projection row — adds nothing here
     beyond the share lock step 5 already takes.
   - **Would this remain correct if ten instances ran it concurrently?** PASS, resting on
     `P8-TSK-019`'s counted ten-way tests and the lock-bypass probe, not on construction.

8. **ADR-0062 §7's convergence trigger did not fire: the canonical record already is the one
   evidence shape.**
   - **The second trigger asked whether Phase 8 needs one evidence shape for every outbound
     credit transfer.** It needs one, and it has one, at normalization rather than at dispatch.
     - Each counterparty's report passes through a pure format adapter into the canonical
       `settlement.line`: a closed line type, a direction, the money triple, dates, typed
       references, `raw_record_sha256` and `canonical_fingerprint`. Every internal record is one
       Settlement Expectation shape.
     - A withdrawal or a return the scheme reports (`DEBIT_OUT`, with `SCHEME_REF`,
       `END_TO_END_REF`, `OUR_REF`) and a payout the provider reports (`PAYOUT_EXECUTED` or
       `PAYOUT_RETURNED`, with `PAYOUT_PROVIDER_REF`, `OUR_REF`) are the same shape to the matcher.
       They differ in their source and their key kinds, and they should: they are two
       counterparties with two positions (`INV-RAIL-04`, `INV-SET-05`).
     - Reconciliation never reads a dispatch port's answers. Provider answers and webhook-derived
       records are keys, not sources (ADR-0064), so the port a payout leaves through is invisible
       to settlement.
   - **Seen from settlement, converging would not be the adapter-only change §7 describes.**
     §7's "without any `merchant` change" holds for the dispatch. It does not hold for settlement:
     - The payout would leave through the scheme and settle in the scheme's cycle net, while
       `merchant` still credits `PAYOUT_CLEARING`. That position's one declared source, the payout
       provider's report, would then report nothing, and the scheme's evidence may not discharge
       it.
     - Converging would therefore re-declare the payout's position and source, and it would move
       a Phase 6 money path (ADR-0057's send permit, resolution sweep and evidence) in the phase
       that reconciles it.
     - It would also give the payout the push rail's return, which is an outbound payment the
       platform originates. A payout return is an inbound refusal by the beneficiary's bank.
   - **The first trigger, a second outbound rail, did not fire either.** Phase 8 adds no rail. The
     second push rail's missing confirmation guard (`PHASE_7_REVIEW.md`:301) stays with the phase
     that adds one.
   - **So moving the payout onto the push rail is recorded as not needed by Phase 8**, and it is
     listed among what Phase 8 must not implement. The Deliberately Deferred row stays open on
     its first trigger, with its second evaluated here. Two outbound disciplines coexist, which is
     ADR-0062's recorded negative.

**Owner decisions settled at the transition.** Each is recorded as a transition decision that the
owner may revisit.
- **O2:** payout returns are applied automatically by `PayoutReturnSchedule` (points 4 and 7),
  and the manual path is kept as the fallback: a return that cannot apply becomes
  `REVERSAL_MISMATCH`, resolved by a four-eyes `TRANSFER_TO_ACCOUNT` (point 5).
- **O6:** if scope must shrink, the cut order is `P8-TSK-021`, then `P8-TSK-019`, then
  `P8-TSK-023`, keeping `P8-TSK-023` if possible. Cutting `P8-TSK-019` removes merchant `V008`,
  `PayoutReturns`, its schedule, the posting key and the `PAYOUT_RETURN` expectation. Every
  returned payout then takes point 5's path, and `reconciliationAttributed` with the customer
  label stays, being `P8-TSK-015`'s (point 6).

O1, O3, O4, O5 and O7 are recorded in ADR-0071, ADR-0066, ADR-0065, ADR-0070 and ADR-0069.

## Alternatives Considered

### Manual only: every return a four-eyes transfer out of suspense
Pros:
- No new table, worker or migration; the resolution machinery already exists.
- A person sees every return.

Cons:
- A routine, fully evidenced event needs two people. That is the operator burden that trains
  rubber-stamp approvals.
- The merchant's money waits in suspense, value already explained parked as though unexplained,
  for at least the grace window and then until two people act.
- The payout gains no merchant-side record of its return. The payable could name it only as a
  reconciliation attribution, and `INV-MER-02` could not state it.

This is kept as the fallback (point 5).

### Apply the return inside the matching chunk
Pros:
- One transaction, and the allocation is immediate.

Cons:
- The chunk holds advisory namespace 4 and reconciliation rows. It would then lock the merchant's
  payout row and payable, so the matcher would wait on merchant locks, which the lock order
  refuses.
- `reconciliation` would need an edge to `merchant`, or a port called inside its hottest
  transaction.
- A refusal (a closed payable) or a merchant defect would roll back, or poison, a chunk carrying
  other counterparties' items.

A separate worker avoids the lock nesting.

### A `RETURNED` payout state
Pros:
- The payout row tells the whole story.

Cons:
- `COMPLETED` is terminal (merchant `V007`, `INV-LIFE-04`), and it is true.
- Every reader of `COMPLETED` would change meaning: `paidOut`, the payout meters, the resolution
  sweep, the stuck gauges.
- It conflates the instruction with what happened afterwards. A return is a new operation.

### Reverse the payout's entry through `ReversalService`
Pros:
- Existing machinery, and `INV-REV-01`'s shape.

Cons:
- A reversal says the original posting was wrong. The payout was executed, and the return is a
  later event with its own evidence and dates, matched to its own line.
- The reversal's key and reference name the payout's entry, not the evidence that caused it.
- Reversal is a person's authority (`TRANSFER_REVERSE`).

### Hear returns from the provider directly, by webhook or query
Pros:
- Faster than a daily report.

Cons:
- ADR-0057 §10 deferred the payout webhook. A second provider door brings its own
  authentication, freshness and evidence surface.
- The report already carries the return with the provider's reference, and it is authenticated
  under `INV-SET-07`.

This is recorded for a real provider.

### Converge the payout onto the push rail now (ADR-0062 §7)
Pros:
- One outbound discipline, one dispatch port.

Cons:
- The trigger's premise is already met by the canonical record (point 8).
- It re-declares the payout's position and source, migrates a Phase 6 money path during the
  phase that reconciles it, and confuses a push return with a payout return.

## Consequences

Positive:
- The merchant's money comes back without a person when nothing needs judging, and only then.
  Everything else goes to a person, with the value owned by a break.
- The payout's meaning never changes. `COMPLETED` still means accepted, and the return is its own
  fact, with its own key, dates, expectation, event and audit record.
- `PAYOUT_CLEARING` is explained at every commit of a return (the table in point 3), and the
  day's remittance nets it.
- The payable names returns and attributions. Neither falls into `other`, and a reconciliation
  transfer is never labelled a capture.
- ADR-0057's follow-up is paid and ADR-0062 §7's trigger is decided, with no change to Phase 6's
  payout path.

Negative:
- The payable gains a writer driven by settlement evidence: a return raises it without a capture,
  and the merchant may pay out again (`INV-MER-05` bounds the next payout by the restored payable).
  A returned payout's destination is not blocked. Changing a destination remains ADR-0056's
  proposal flow.
- Returns are heard only as fast as the provider's report arrives, daily in the simulation.
- A return that falls back to a person leaves no merchant fact. The payable shows a reconciliation
  attribution, and the payout's return is readable only through the break and its resolution.
- **A return to a closed merchant.** A merchant closed after its last payout can still be sent a
  return. Since the transition's repairs the close refuses while a chargeback can still be won,
  and closes the payable's ledger account in its own transaction, so a later chargeback's share
  parks in `CHARGEBACK_RECOVERABLE` and a later return cannot post (`V007`). The value rests in
  suspense, aged and alerting, owned by its break, until a person transfers it to an account that
  can take it. Returning it to the merchant outside the platform is return-to-sender, which is
  deferred. Merchant closure waits on the chargeback window but not on a payout's return window,
  because Phase 8 models none. *(Routed 2026-10-01 by the Phase 8 review, `P8-DOC-001`: owned by
  Phase 15, with return-to-sender and ADR-0070 §4's residual. Phase 12 is BNPL in the roadmap,
  not merchant-facing flows in general; Phase 15 makes reconciliation operable — runbooks,
  ageing SLAs, escalation — and, building no new business capability itself, either writes the
  operating procedure for this value or schedules return-to-sender by ADR. This read "recorded
  here for the Phase 8 review to route".)*
- **A return applied from a batch later repudiated.** It stands as a merchant fact, because
  repudiation reverses the recognition entry and counter-allocates the items, but it does not
  touch `merchant`. The reopened `PAYOUT_RETURN` expectation ages into `MISSING_EXTERNAL`, so
  nothing is silent. *(Corrected 2026-10-02 by the Phase 8 -> 9 transition, REC-4: ageing takes
  only an expectation never overdue, so a return already overdue at its match - whose
  `MISSING_EXTERNAL` the repudiated line closed - would have stayed silent; the repudiation's
  approval now raises its fresh `MISSING_EXTERNAL` itself, following the closed break.)* But taking the value back from the merchant is not a Phase 8 resolution kind.
  *(Stated by `P8-TSK-023`, 2026-10-01, as built: the `payout_return` fact and its posting stand
  untouched, the payout stays `COMPLETED`, the return item leaves to `REPUDIATED`, the
  `PAYOUT_RETURN` expectation is reopened to age by the sweep, and `PAYOUT_CLEARING` stays
  explained — `BatchRepudiationDatabaseTest` case 13. This read "`P8-TSK-023` must state the
  outcome".)*
- The platform gains another leaderless schedule, one of the five Phase 8 adds (the register goes
  from nine to fourteen).

Operational impact: returns waiting inside grace are counted by
`finapp.reconciliation.item.unmatched`, and applied returns show in `finapp.reconciliation.item`
and `finapp.reconciliation.rematch` as items matched. A return that cannot apply becomes a HIGH
`REVERSAL_MISMATCH` break with cause `RETURN_NOT_APPLICABLE` (`TERMINAL_STATE_CONTRADICTED` for a
`FAILED` payout's, at once), alerting by age. The schedule
publishes its sweeper-enabled gauge. No series carries an amount (ADR-0072).
Security impact: no route, no permission and no credential. No person can apply a return
directly; the manual path is four-eyes. The worker acts as the platform and is enumerated. Audit
is acting-only and carries identifiers only. The amounts in `payout_return` are
RESTRICTED-FINANCIAL. The return's authority is evidence authenticated before it takes effect
(`INV-SET-07`). The provider's own word for a return stays in `SIM_PAYOUT_CSV`'s adapter
(`SettlementVocabularyIsConfinedTest`, `INV-PAY-03`).
Financial impact: no new purpose, and one new posting key on existing accounts. Between a return
and its remittance's cash, `PAYOUT_CLEARING` can hold a debit balance, the provider owing the
platform. That is a counterparty position, not an anomaly, and the position proof explains it.
`INV-MER-02` is amended.

## Invariants / Constraints

`INV-LIFE-04` (the payout stays `COMPLETED`; a return is a new operation), `INV-MER-02` (amended:
payouts returned, reconciliation attributions), `INV-SET-01` (the return is a movement the
counterparty's evidence reports, and the bank settles its net), `INV-SET-02` and `INV-REC-06` (its
expectation is opened in its own transaction; the position is explained at every step),
`INV-SET-05` and `INV-RAIL-04` (only the payout provider's evidence discharges it, on the
provider's own position), `INV-SET-07` (the evidence is authenticated before it takes effect),
`INV-IDEM-02` and `INV-IDEM-04` (one return per payout by a domain unique, independent of
idempotency-record retention; duplicate lines have one effect), `INV-CON-01` and `INV-CON-02`
(the payout row lock; the item judged on its locked row), `INV-REC-09` (the fallback's suspense is
owned by its break), `INV-LED-01`, `INV-EVT-01`, `INV-AUD-01` and `INV-AUD-02` (acting-only audit,
identifiers only), `INV-PAY-03` (the provider's vocabulary is confined), `INV-MER-05` (the restored
payable bounds the next payout).

## Follow-up

- `P8-TSK-019` builds this ADR:
  - merchant `V008` (`payout_return`, the composite foreign key, the append-only grant and
    trigger, and its classification rows);
  - `PayoutReturns.apply`, `PayoutReturnSchedule`, `merchant-payout-return:<payoutId>`,
    `merchant.MerchantPayoutReturned` and `merchant.PayoutReturnApplied`;
  - the `payoutsReturned` term and `INV-MER-02`'s returns clause. `reconciliationAttributed`, the
    customer statement label and the attribution clause are `P8-TSK-015`'s (point 6);
  - no key kinds: the `PAYOUT_RETURN` expectation opens no key, and the `PAYOUT_RETURNED` rule it
    is reached by is already OPERATION-ANCHORED in rule set v1 (point 3), so `P8-TSK-019` needs no
    rule-set activation;
  - the item re-read under a share lock before `apply` (point 7);
  - Its tests: ten workers and ten duplicate lines leave one return and one entry; the lock-bypass
    probe; the payout stays `COMPLETED`; the payable is restored exactly once and labelled; a
    closed payable yields `REVERSAL_MISMATCH` with nothing posted; worker against grace leg, both
    ways; a later-day retry converges on the key; a failure injected after the posting rolls back
    the return, the posting and the expectation together.
  - **Implemented** (2026-09-30), as listed, with four facts the list left open: `V008` also
    declares `merchant_payout_money_is_unique UNIQUE (id, amount_minor, currency, scale)` on the
    payout, the target the composite foreign key needs, and its insert trigger refuses a
    return naming a payout that is not `COMPLETED` (`23514`) — the second rank beside
    `apply`'s check; `apply` answers a typed outcome (`APPLIED`, `ALREADY_RETURNED`,
    `NO_PAYOUT`, `PAYOUT_NOT_COMPLETED`, `AMOUNT_DIFFERS`, `PAYABLE_NOT_POSTABLE`) and every
    outcome but the first writes nothing, so the item waits out its grace and is typed there;
    the worker reads reconciliation's items through a port it declares (`WaitingPayoutReturns`:
    a bounded page in claimant order, and the item re-read `FOR SHARE` only while it is still
    an `UNMATCHED` `PAYOUT_RETURNED` item) and the batch's stored `accepted_on` through
    settlement's `acceptedOnOf`; and the rematch worklist gained `P8-TSK-018`'s recorded
    anchored clause — an item whose key reaches an anchor whose operation's anchored kind
    opened, under the anchor's source, after the item's latest decision and still holds a
    remainder — without which the applied return's expectation, holding no key, would never
    be re-decided (the remainder condition keeps a duplicate line's reach to a SPENT return
    out of the worklist, where it would be re-locked on every tick). Proven with REAL
    clocks in `PayoutMatchingDatabaseTest` and end to end in `PayoutReturnDatabaseTest`. *(Corrected by `P8-TST-001`, 2026-10-01: the storm found that "opened after its latest decision" compared the return's `opened_at` - the worker's instance clock - with the line's `decided_at` - the matcher's - so a return applied within the clock skew never reached the worklist and waited for grace. The clause now asks whether any decision of the item has already seen the return as a candidate, judged on rows alone. The keyed and value-date clauses still compare `opened_at` with the latest decision across instances - recorded debt, grace their backstop. The correction also widened a duplicate line's chance to take the return first - point 7's recorded debt, value conserved and attribution wrong, scheduled to Phase 15 by `P8-DOC-001`.)*
- `P8-TSK-002` declares `PayoutSettlementDeclaration`. `P8-TSK-004` seeds rule set v1 with the
  payout source's `PAYOUT_RETURNED` rule OPERATION-ANCHORED (point 3). `P8-TSK-005` switches
  `MerchantPayoutOutcomes` to it and opens `MERCHANT_PAYOUT` through the port.
- `P8-TSK-013`: the grace leg judges a `PAYOUT_RETURNED` item on its locked row (point 7), and
  shares the worker-against-grace-leg race and its test with `P8-TSK-019`. **The grace side
  implemented** (2026-09-30): `Matching`'s grace leg locks each expired item `FOR UPDATE` and
  re-reads its candidates under that lock, so an expectation committed by a holder of the
  item's share lock is allocated, never parked beside it — proven in
  `GraceAndRematchDatabaseTest` with a share-lock holder standing in for the worker, and the
  dropped-item-lock probe killed by that test. The worker's own side of the race stays
  `P8-TSK-019`'s.
- `P8-TSK-018`: the payout provider's report and rules. **Implemented** (2026-09-30): the
  `PAYOUT_RETURNED` line is held as an item under the operation-anchored rule, never a candidate of
  its payout's `MERCHANT_PAYOUT`; it waits `UNMATCHED` with no break, and at grace types
  `RETURN_NOT_APPLICABLE` and parks — the fallback this ADR names while `P8-TSK-019`'s worker does
  not exist — the four-eyes transfer crediting the payable back, the payout still `COMPLETED`.
- `P8-TSK-015`: the fallback's `TRANSFER_TO_ACCOUNT`, and with it `reconciliationAttributed`, the
  customer statement's `RECONCILIATION_ATTRIBUTION` label and `INV-MER-02`'s attribution clause
  (point 6), so cutting `P8-TSK-019` takes none of them. `P8-TSK-006`: the `origin` column the
  classifier reads. *(The consistency review, A4, A6 and A12: this list had given `P8-TSK-019`
  the attribution term, the label and return-qualified key kinds, and named the grace-leg race
  only on `P8-TSK-019`'s side.)*
- `P8-TSK-023` — **implemented** (2026-10-01): it stated what happens to a return applied from a
  repudiated batch — the return stands, its expectation reopened (Negative, above).
- `P8-TST-001` (payout returns in the storm, with every round's proofs) and `P8-TST-002`
  (`REVERSAL_MISMATCH` crossed with its allowed resolutions, `TRANSFER_TO_ACCOUNT` among them) —
  both **done** (2026-10-01); the storm's find, the anchored clause's two clocks, is the note
  under `P8-TSK-019` above.
- At the transition, with provenance: ADR-0057's follow-up is annotated as paid by this ADR, and
  ADR-0062 §7 as evaluated, with its second trigger not fired.
- **As built** (2026-10-01, read against the code by `P8-DOC-001`): every point of this ADR is
  implemented by the tasks above, all `COMPLETE`, and every statement above is true of the code.
  The review's corrections: point 1's value date, point 2's references, point 3's and point 5's
  `FAILED`-payout exception (parked at once, `TERMINAL_STATE_CONTRADICTED`), point 4's keyset
  walk, point 7's duplicate-line debt (Phase 15), and the closed merchant's routing (Phase 15).
  *(This bullet read "Until `P8-TSK-019` lands, nothing in this ADR is implemented but point 7's
  grace side" until the review.)*
- The Phase 8 review (`P8-DOC-001`) read this ADR against the code, corrected it where it had
  drifted, and accepted it on 2026-10-01.
- *The Phase 8 → 9 transition* (ADR-0079, `Proposed`): the precedent reused — a corridor
  return is a born-once fact applied by a worker, with a person's four-eyes resolution as
  the way out for anything but the exact instructed credit; `WaitingPayoutReturns` is
  scoped by source, so the merchant worker and the corridor worker never see each other's
  items.
