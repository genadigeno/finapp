# ADR-0105 — The BNPL refund policy: latest instalments first, the excess to the customer, the merchant fee pro-rated back, the PN interest rule, and the merchant payable's third bounded negative source

Status: Accepted (2026-10-22, `P12-DOC-001` — read against the code and corrected first)
Date: 2026-10-16
Phase: 12
Context: BNPL · Merchant · Ledger · Accounts (wallet) · Identity
Supersedes: nothing. **Amends `INV-MER-07`** (a third, bounded source of merchant debt — in force since `P12-TSK-021`,
2026-10-19; *this read "effective when `P12-TSK-021` lands" until `P12-DOC-001`, 2026-10-22*), after ADR-0061 §5's second. Applies ADR-0054 and ADR-0050 §6 (a merchant refund funded by its net; the fee
returned by the cumulative method, `MerchantSettlement.refund`), ADR-0058 (a sale covers its fee), ADR-0052 (merchant
keys, `INV-MER-01`), ADR-0043 and `INV-HIST-01` (corrections post, never edit) and ADR-0093/ADR-0094 (the schedule's
`recalculateAtInstalment`; accrual). Rests on `PHASE_12_PLAN.md` §2.2 (B3, B7), §2.3 (A10–A13, A19), §5.3, §5.5–§5.7,
§6, §7.1 (T-ref), §7.2, §7.4, §12.5, §12.6, §12.11 (F5, F5a), §14 and §24 (OQ-6, -9, -13, -14, -15).

## Context

1. **A BNPL refund is not a card refund.** A card refund sends money back through a provider and may fail; it is
   dispatched, reserved and completed later (ADR-0054). A BNPL refund moves no money across a rail: it reduces the
   customer's debt to the platform, returns to the customer's wallet whatever the customer had already paid beyond
   what remains owed, and takes the price back from the merchant's payable — all inside the platform's own books, in
   one commit, with no provider and no pending state (plan §5.7).
2. **The customer has instalments in flight.** At any moment some are paid, some billed and unpaid, some not yet due;
   a PN obligation also carries accrued and billed interest, and any instalment may carry a late fee. "Refund 30" has
   to say which of those the 30 settles.
3. **The merchant was charged a fee on the gross.** If the sale shrinks, the fee must shrink with it, and a sequence
   of partial refunds must return exactly the fee charged when they total the gross — in any order.
4. **The merchant may already have been paid.** Payouts are on demand (ADR-0051, ADR-0057); a refund may arrive after
   the purchase's net left as cash. `INV-MER-07` today allows a payable below zero only by a retained fee and, since
   ADR-0061, by a chargeback.
5. **Owner decisions B3 and B7** (2026-10-16) answer 2–4: latest instalments first, the excess to the customer, the fee
   pro-rated back; a refund after payout may drive the payable negative by at most the purchase's own net, recovered
   before any payout. The remaining questions took the draft's defaults (B8: OQ-6, -9, -13, -14, -15).

## Decision

1. **The bound** (`INV-BNPL-07`). For each purchase, Σ refund amounts ≤ G, judged under **B5**, the `purchase` row
   `FOR UPDATE` — the one row a refund and a dispute (ADR-0106) both take first. A refund R with R > G − refunded is
   refused `422 bnpl.RefundExceedsPurchase`; any refund of a `FULLY_REFUNDED` purchase `409 bnpl.PurchaseFullyRefunded`.
   The refund's currency is the purchase's. Both ranks: the domain under B5 and an every-writer trigger summing the
   purchase's refunds. A refund that reaches Σ = G moves the purchase `FINANCED → FULLY_REFUNDED` (terminal); a partial
   refund leaves it `FINANCED` (partially refunded is derived, plan §5.3).

2. **Latest instalments first** (B3; the pure allocator, `P12-TSK-009`). Under B5 then **B4** (the obligation), over
   the due state derived from billings and allocations — never from a projection:
   1. Walk the current schedule's instalments from the **last** to the first; at each cancel `min(remaining R, its
      unpaid principal)`. Unbilled principal cancels from `BNPL_PRINCIPAL`; billed-unpaid principal from
      `BNPL_PRINCIPAL_DUE` (which cures that item's days past due). A partly paid instalment cancels only its unpaid
      part; a paid one cancels nothing.
   2. The **excess** `E = R − Σ cancelled` is owed to the customer: credited to the customer's `CUSTOMER_WALLET` in the
      same entry (A12), the wallet resolved or opened through `BnplWallets`. If the wallet's ledger account cannot take
      a posting (closed, OQ-13), E is credited to the obligation's `BNPL_CREDIT_BALANCE` and refunded at closure or when
      the wallet becomes postable — the obligation cannot close with a credit balance standing.
      *(Corrected by `P12-DOC-001`, 2026-10-22: as built (`P12-TSK-021`) the wallet is **never opened** —
      `BnplWallets.existingWallet` answers the party's existing wallet in the currency or nothing. With no wallet, or
      one not `ACTIVE`, E is credited to `BNPL_CREDIT_BALANCE` while the obligation is `ACTIVE`; the held credit is
      applied to what falls due by the servicing step's credit-balance applications (F3a) and refunded by the closure
      (`bnpl.closure-refund:<obligation>`) once a wallet can be credited — no act refunds it merely because a wallet
      became postable.)*
   3. Each cancellation is a born-once `bnpl_refund_adjustment` row (refund, instalment, from-not-due, from-due); a
      deferred trigger holds Σ adjustments + E = R (`INV-BNPL-06`). *(As built, `bnpl V012` / `V013`: the row carries
      its sequence, instalment, `kind` (`UNBILLED` | `BILLED_UNPAID`), `principal_cancelled_minor`,
      `interest_released_minor`, `whole` and `late_fee_released_minor` — one kind per row, not a from-not-due /
      from-due pair; `bnpl.bnpl_refund_conserves` holds the sum at commit.)*
   4. When an unbilled instalment is cancelled or shortened, schedule v n+1 and agreement v n+1 are born (billed
      instalments kept, unbilled superseded — `INV-HIST-04`); nothing is edited.
   5. Latest-first is the customer's choice to make the refund shorten the plan, not lower every instalment, and it is
      what makes a full refund before instalment 2 cancel instalments 2–4 and return instalment 1's payment.

3. **The merchant fee pro-rated back by the cumulative method** (A10). With the fee f pinned on the purchase
   (`bnpl_fee_pin`, `INV-MER-03`) and `cum_fee(x) = round(f · x / G)` in the pinned version's rounding (A8: `HALF_EVEN`),
   `cum_fee(0) = 0` and `cum_fee(G) = f` exactly:

   ```
   fee_returned(refund) = cum_fee(refunded_before + R) − cum_fee(refunded_before)
   ```

   It depends only on the two cumulative figures, so it is **order-independent** and **telescopes**: any sequence of
   refunds totalling G returns exactly f. Unlike a card refund (ADR-0054's least-share reservation over every
   completion order) it is computed **exactly**: `refunded_before` is the committed sum read under B5, and no sibling
   refund can be in flight. `FeeCalculation`'s arithmetic, through `BnplMerchants`; one fee arithmetic in the platform.

4. **The entry** (F5 with F5a, ADR-0103), one entry keyed `bnpl.refund:<refund>`, balanced:
   `DR MERCHANT_PAYABLE R; DR BNPL_MERCHANT_FEE_INCOME r_f` / `CR BNPL_PRINCIPAL (unbilled cancelled); CR
   BNPL_PRINCIPAL_DUE (billed cancelled); CR CUSTOMER_WALLET E (or BNPL_CREDIT_BALANCE); CR MERCHANT_PAYABLE r_f` —
   the payable falls by **R − r_f**, its net; the merchant's drill-down names it `BNPL_REFUND`, and `INV-MER-02`'s
   equation gains "− BNPL refunds + BNPL fees returned". The releases of points 5 and 6 ride in the same entry; they
   are platform income forgone, not part of R, and the merchant funds none of them.

5. **Late fees on cancelled instalments are released** (OQ-14, owner accepted default). A late fee still unpaid on an
   instalment whose whole unpaid principal the refund cancels is released in the entry (`DR BNPL_FEE_INCOME / CR
   BNPL_FEES_DUE`) — a fee on a debt that no longer exists is not owed. A fee on an instalment only shortened stands;
   a fee already paid stays paid (the lateness happened), as paid interest does.

6. **The PN interest rule** (A11, OQ-6, owner accepted default). Every elapsed date is accrued first: T-acc runs in
   its own transaction before T-ref, and T-ref verifies under B4 that it did. The principal reduction is
   `ScheduleEngineV1.recalculateAtInstalment` (same instalment, least term — latest-first for an annuity). Interest
   accrued to the refund date and interest billed stay owed; interest already paid is not refunded. **Except:** a
   refund that cancels the whole remaining principal also releases accrued-unbilled and billed-unpaid interest
   (`DR BNPL_INTEREST_INCOME / CR BNPL_INTEREST_ACCRUED, BNPL_INTEREST_DUE`), so the agreement closes with no residual
   obligation (`INV-BNPL-09`). *(As built (`P12-TSK-021`; recorded by `P12-DOC-001`, 2026-10-22), refined: a refund
   that leaves nothing more to bill also releases the accrued interest no billing could make due — the Follow-up's
   "decision 6 refined".)*

7. **Closure.** If every receivable is zero after the entry and no credit balance stands, the obligation closes in the
   refund's transaction: `REFUNDED` when a full refund settled it, `REFUNDED_PARTIALLY_REPAID` when refunds and
   repayments together did. A later customer repayment is `409 bnpl.ObligationClosed`. *(Corrected by `P12-DOC-001`,
   2026-10-22: this read "If every receivable is zero after the entry and no credit balance stands". As built the
   closure (`BnplClosures`, one for every act) refunds a standing credit balance to the customer's existing wallet held
   under (P) in the same transaction and then closes; only a credit balance no wallet can take now keeps the obligation
   open.)*

8. **Refunds after closure** (OQ-15, owner accepted default). The purchase is not terminal when its obligation is:
   a refund of a `CLOSED (REPAID | PAID_OFF_EARLY | REFUNDED_PARTIALLY_REPAID)` obligation is admitted within the
   bound, cancels nothing, and posts only the merchant's debit, the fee returned and E = R to the wallet; no BNPL
   receivable account moves and the obligation stays `CLOSED`. If that wallet cannot take a posting, the refund is
   refused with nothing written (a `CLOSED` obligation holds no credit balance) — the merchant retries when it can.

9. **The doors** (OQ-9, owner accepted default). The **first merchant-key refund door** in the platform:
   `POST /v1/merchant/bnpl-purchases/{id}/refunds` — merchant API key with the BNPL refund scope (ADR-0052), tenant-
   isolated (another merchant's purchase is `404`, `INV-MER-01`), keyed `bnpl.refund:MERCHANT:<id>` and arbitrated
   across lapsed claims by `UNIQUE (merchant_id, refund_key)` *(as built, `bnpl V012`: `UNIQUE (merchant_id,
   requested_by, refund_key)`, `requested_by` the door and its actor — `MERCHANT:<merchant>` or `OPERATOR:<id>` — so the
   two doors' keys never collide; corrected by `P12-DOC-001`, 2026-10-22)*, a reason required (`RESTRICTED-PII`, screened), bounded
   only by the purchase — no four-eyes and no threshold review (a merchant's act on its own sale). An **operator door
   beside it** for a merchant that cannot act over its key (suspended, or instructing out of band): reasoned, audited
   with actor, target, reason, correlation and outcome, self-dealing refused; its route and permission are named by
   `P12-TSK-021`'s design (the plan's §9 lists none), the payments precedent (one permission, `PAYMENT_REFUND`) its
   default. No operator otherwise cancels an instalment (plan §11). *(Named and built by `P12-TSK-021`:
   `POST /v1/operator/bnpl-purchases/{id}/refunds` under `PAYMENT_REFUND`, self-dealing refused
   `403 bnpl.SelfDealingRefused` through `BnplActingParty`; the merchant key's scope is `BNPL_REFUND` (merchant `V011`),
   a key without it refused `403 bnpl.RefundScopeRequired` and audited `bnpl.RefundScopeRefused`.)*

10. **The merchant payable's third bounded negative source** (B7). A BNPL refund is applied, never reserved: it places
    no hold and is not refused for want of payable. It may leave `MERCHANT_PAYABLE` below zero by **at most the
    purchase's own net** — what its financing credited (G − f) less what its earlier refunds took back. That bound
    needs no separate check; it follows from the bound of point 1 and the fee of point 3. For x = Σ refunds ≤ G and
    y = G − x, the purchase's cumulative payable debit is `x − cum_fee(x)`, and

    ```
    x − cum_fee(x) ≤ G − f   ⇔   cum_fee(x) ≥ f − y
    ```

    which holds because `f · x / G = f − f · y / G`, and since f < G (ADR-0058) that is strictly greater than the
    integer `f − y` whenever y ≥ 1, so any monotone rounding of it is at least `f − y`; at y = 0 it is f exactly. The
    debt is recovered from later captures and BNPL financings before any payout: `INV-MER-05` bounds a payout by the
    payable, so a payable below zero refuses every payout. It is counted by `finapp.ledger.negative.positions` and named
    `BNPL_REFUND` in the drill-down. **`INV-MER-07` is amended explicitly**, effective when `P12-TSK-021` lands
    *(in force since `P12-TSK-021`, 2026-10-19 — recorded by `P12-DOC-001`, 2026-10-22)*:

    > *Amended by ADR-0105 (the Phase 11 → 12 transition), in force from `P12-TSK-021`: a BNPL refund after a payout is
    > the third, bounded source of merchant debt — it leaves the payable below zero by no more than that purchase's own
    > net (its financing's credit less its earlier refunds' payable debits), the debt recovered from later captures and
    > financings before any payout (`INV-MER-05`). `INV-BNPL-07` holds the bound. A BNPL refund is applied in one
    > commit, never reserved, so it places no hold.*

11. **The transaction T-ref** (plan §7.1, §7.2). Claim `bnpl.refund:MERCHANT:<id>` → **B5** purchase → **B4**
    obligation → database clock → the split over the due state derived under B4 → schedule and agreement versions →
    **(P)** the merchant's `MERCHANT_PAYABLE` and, when E is credited or a credit balance refunded, the customer's
    `CUSTOMER_WALLET` ledger-account rows `FOR UPDATE` **as one element in `UUID.compareTo` order of the two account
    ids** — the order payments' book rail takes for exactly this pair (`PaymentOutcomes.lockPairInFixedOrder`), so a
    BNPL refund never deadlocks with a wallet-paid capture or refund by the same customer at the same merchant (plan
    §7.3 (b)); one of them alone at the same position → **L8** `lockBalancesInOrder` (the obligation's accounts and the
    currency's `BNPL_MERCHANT_FEE_INCOME`, `BNPL_FEE_INCOME`, `BNPL_INTEREST_INCOME`, none taken before (P)) → the entry
    → refund and adjustment rows → closure → outbox `BnplRefundApplied` → audit `bnpl.RefundApplied`. No L0 and no L6:
    a refund lowers exposure and restores capital headroom at commit (ADR-0104). The refund row is born `APPLIED`;
    a refusal writes no bnpl row. *(Corrected by `P12-DOC-001`, 2026-10-22: `bnpl_refund` has no status column — an
    `INSERT`-only fact whose existence is the applied refund; the claim is the door's key under
    `bnpl.refund:<actor type>:<id>` (`MERCHANT` or the operator's), and pay-in-N on an `ACTIVE` obligation behind its
    accrual answers `BehindAccrual` with nothing written, the door running T-acc first and retrying.)*

12. **Worked examples** (plan §12.6, §12.11's check). P4, G = 100.00 EUR, f = 4.00 (400 bps), four instalments of
    25.00 (A4), instalment 1 paid at purchase. F1 left the payable +96.00.

    | Case | Cancelled (latest first) | E | r_f | Payable (change → balance) | Obligation |
    |---|---|---|---|---|---|
    | **Before** instalment 2 — full refund R = 100 | #4 25, #3 25, #2 25 (unbilled) | 25.00 to wallet | cum(100) − cum(0) = 4.00 | −96.00 → 0 | `CLOSED (REFUNDED)` |
    | **During** — #2 billed unpaid; R = 30 | #4 25, #3 5 (→ 20) | 0 | cum(30) = 4.00 × 30 / 100 = 1.20 | −28.80 → 67.20 | `ACTIVE`; v2: #2 25 due, #3 20 |
    | then R = 70 | #3 20, #2 25 (`…PRINCIPAL_DUE`, cures #2's DPD) | 25.00 to wallet | cum(100) − cum(30) = 2.80 | −67.20 → 0 | `CLOSED (REFUNDED)` |
    | **After** completion (`CLOSED (REPAID)`) — R = 40 | nothing | 40.00 to wallet | cum(40) = 1.60 | −38.40 → 57.60 | stays `CLOSED` |

    Order independence: 70 then 30 returns cum(70) = 2.80 then 1.20 — the same 4.00 = f. **After payout**:
    had the merchant paid out its 96.00, the same two refunds leave the payable at −28.80, then −96.00 = −(G − f), the
    bound exactly; every payout is refused until later captures and financings restore a positive payable. **PN**
    (G = 600.00, 6 monthly): a refund of 150.00 in month 2 first accrues to date (T-acc), then shortens the plan from its
    end by `recalculateAtInstalment` at the same instalment; the month's accrued interest stays owed; a later refund
    cancelling every remaining unit of principal also releases the unpaid interest and closes the agreement.

    *(Corrected by `P12-TSK-009` (2026-10-17), whose golden cases compute these rows: the table and the order-independence
    sentence read "cum(30) = round(1.20) = 1.00 | −29.00 → 67.00", "cum(100) − cum(30) = 3.00 | −67.00 → 0",
    "cum(40) = round(1.60) = 2.00 | −38.00 → 58.00" and "cum(70) = round(2.80) = 3.00 then 1.00 … −29.00" - rounded to
    whole euros. `cum_fee` rounds to the currency's minor unit under the pinned version's policy (merchant's
    `FeeCalculation`, decision 3; A8), so the shares are exact to the cent here; the full refund's row, the totals (f =
    4.00, Σ payable debits = 96.00 = G − f) and every conclusion drawn from them are unchanged.)*

## Alternatives Considered

### Earliest instalments first
Pros: the customer's next payments disappear first, and billed items past due are cured before anything else.
Cons: contrary to B3; it keeps the plan's full length on a smaller debt, so the customer goes on paying for months on a
purchase mostly returned, and it diverges from `recalculateAtInstalment`'s least-term shape for PN. Refused (probe:
earliest-first turns `#refundCancelsLatestFirst` red). A refund larger than the unbilled tail still reaches the billed
items, so latest-first cures them too whenever it cancels them.

### Pro rata across every remaining instalment
Pros: the plan's length is preserved.
Cons: re-prices every unbilled instalment by rounding, multiplies schedule versions' differences, and makes the
customer's statement harder to explain. Refused.

### Excess to the original means of payment
Cons: instalments are wallet-funded only (B6); the wallet is the original means. Refused (A12).

### Refuse a refund the payable cannot fund (no third negative source)
Pros: `INV-MER-07` unchanged.
Cons: the customer keeps paying for returned goods while the platform waits on a merchant it already paid; refunds
after payout are the common case for on-demand payouts. Contrary to B7. Refused.

### Reserve the refund's net on the payable as a card refund does
Cons: there is no provider and no pending state to reserve across; a hold that is placed and released in the same
commit adds nothing but the refusal B7 rejects. Refused.

### An unbounded negative payable
Cons: a merchant could refund a purchase's value more than once out of the platform's money. Bounded by the
purchase's net, the platform's worst case on a purchase is what it paid the merchant for it. Refused.

### Fee returned by a running clamp, or never returned
Cons: a clamp is order-dependent (probe: the telescoping property red); never returning contradicts B3. Refused.

## Consequences

Positive:
- Every refund at every point preserves value: the receivable falls by what was owed, the customer receives what it
  had paid beyond that, the merchant gives back the price net of its returned fee, and the books balance per entry.
- A full refund closes the agreement with nothing stranded (`INV-BNPL-09`, gate criterion 2).
- Merchants gain a self-service refund door bounded by the purchase.

Negative:
- A third source of merchant debt, bounded per purchase but with no reserve: a merchant that stops trading after
  refunding paid-out purchases leaves the platform a loss of at most their nets. It joins the Known Architectural Debt
  row (merchant debt with no reserve, Phase 13).
- Interest accrued before a partial PN refund stays owed (A11) — recorded for review with the other defaults.
- Every refund posts to the currency's `BNPL_MERCHANT_FEE_INCOME` row at L8 (ADR-0104 Decision 9, measured in the storm).
  *(As built: measured by `P12-TST-001` — 12 waits on the row on the final run, purchases and refunds together;
  recorded by `P12-DOC-001`, 2026-10-22.)*

Operational impact: `finapp.bnpl.refund{kind,outcome}`; the negative-position gauge; the audited refunds report.
Security impact: the BNPL refund scope on merchant keys; tenant isolation negatives; the operator door reasoned and
audited.
Financial impact: `INV-MER-07` amended; no new account.

## Invariants / Constraints

`INV-BNPL-06` (a refund preserves value: R = Σ principal cancelled latest-first + E; the payable debited R − r_f; the
only other movements are point 5's and 6's releases), `INV-BNPL-07` (Σ refunds ≤ G under the purchase lock),
`INV-BNPL-09` (a full refund closes with nothing stranded), `INV-BNPL-05`, `INV-BNPL-08`, `INV-MER-01`, `INV-MER-02`,
`INV-MER-03`, `INV-MER-05`, `INV-MER-07` (amended), `INV-HIST-04`, `INV-IDEM-01`, `INV-LED-01`.

## Follow-up

- `P12-TSK-009` — the pure allocator and the fee arithmetic; properties: value preserved for every refund sequence,
  the fee telescoping to f in every order, the B7 bound, `r_f ≤ R` for every refund. *(As built, 2026-10-17:
  `RefundAllocationEngineV1` over a `RefundPosition`, the fee arithmetic a parameter (`MerchantFeeReturn`) bound to
  merchant's `FeeCalculation.returnedFee` through `BnplMerchants.returnedFee` by `P12-TSK-021` - one arithmetic, never
  restated in bnpl; each property above held over generated sequences; decision 12's worked figures corrected to the
  cent.)*
- `P12-TSK-021` — T-ref, F5/F5a, the doors, the versions, closure; mandatory scenarios 7, 8, 9, 16 and 17's release;
  the pair-element race against a wallet-paid capture in both id orders; the `INV-MER-07` amendment put in force. *(As
  built, 2026-10-19: `BnplRefunds` over `bnpl V012`, one body for both doors; the operator door `POST
  /v1/operator/bnpl-purchases/{id}/refunds` under `PAYMENT_REFUND` (decision 9's default), self-dealing refused; the merchant
  key's `BNPL_REFUND` scope an insert-time fact of the key (merchant `V011`), keys issued before carrying none; the
  wallet never opened - an absent or unpostable one holds the excess as credit, or (after closure) refuses the refund
  `409 bnpl.RefundNotPostable`; pay-in-N's adjustment rows the allocator's split, the version identity `principal(v n+1)
  = principal(v n) - unbilled cancelled`; accrued interest released also when nothing is left to bill (decision 6
  refined); the late fee's release (decision 5) and scenario 17's half carried to `P12-TSK-020`; `AccountClosing`'s
  pending-credits port not built (recorded for the owner); `INV-MER-07`'s amendment in force.)*
- `P12-TSK-016` — the drill-down's `BNPL_REFUND` kind and `INV-MER-02`'s equation with the BNPL terms.
- To settle in `P12-TSK-021`'s design: the operator door's route and permission; whether `AccountClosing`'s
  pending-credits port refuses closing a wallet while a BNPL obligation is `ACTIVE`.
  - *Settled by `P12-TSK-021` (recorded by `P12-DOC-001`, 2026-10-22):* the route and permission as decision 9's
    as-built note; the pending-credits port **not built** — a wallet may close while an obligation is `ACTIVE`, the
    excess then held as credit (decision 2.2's note) — recorded for the owner.
- **Acceptance.** **As built** (`P12-DOC-001`, 2026-10-22): `Proposed` at the Phase 11 → 12 transition
  (2026-10-16); the Phase 12 review read this ADR against the code, corrected it in place above with dated
  notes, and accepted it.
