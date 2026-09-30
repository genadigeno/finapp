# Settlement and Reconciliation Lifecycles

Written by the Phase 7 → 8 transition (2026-09-28), the `RAIL_AND_DISPUTE_LIFECYCLES.md`
precedent: the document that names a phase's model is written before the phase's first task,
from the decisions in ADR-0064…0073, and corrected by the tasks that implement it. Until Phase
8's first task lands, **nothing in this document is implemented**; every statement is the
decided design, corrected by the tasks that build it. The engineering plan is
[`PHASE_8_PLAN.md`](../project/PHASE_8_PLAN.md); the model this phase realises is
[`RECONCILIATION_MODEL.md`](RECONCILIATION_MODEL.md).

Related: ADR-0064 (settlement holds the evidence, reconciliation the expectations and the
comparison) · ADR-0065 (two evidence hops on each counterparty's own position; cash only on the
bank's statement) · ADR-0066 (the file door, attestation, encrypted retention) · ADR-0067 (every
settling completion opens its expectation) · ADR-0068 (matching, rule versioning, tolerances) ·
ADR-0069 (the break taxonomy and lifecycle) · ADR-0070 (suspense policy and ageing) · ADR-0071
(resolution authority and four-eyes) · ADR-0072 (no amounts in metrics) · ADR-0073 (payout
returns) — all Proposed at the transition, indexed in [`docs/adr/README.md`](../adr/README.md) ·
[`RAIL_AND_DISPUTE_LIFECYCLES.md`](RAIL_AND_DISPUTE_LIFECYCLES.md) and
[`CHECKOUT_MERCHANT_LIFECYCLES.md`](CHECKOUT_MERCHANT_LIFECYCLES.md) (the completions this phase
settles).

---

## 1. The settlement concepts, kept apart

The `CLAUDE.md` §Domain Distinctions pairs this phase could collapse — Authorization / Capture /
Clearing / Settlement; Wallet / Bank Account / Ledger Account / Operational Account; Customer
Payment / Merchant Settlement — made concrete for settlement. The canonical terms — Clearing,
Settlement, Merchant Settlement, Suspense Account, Reconciliation Batch, Reconciliation Break —
are in [`GLOSSARY.md`](GLOSSARY.md); this transition adds Settlement Batch, Settlement
Expectation, Remittance, Settlement Account and Match Decision, and the rest are this document's
working vocabulary. *(The Phase 7 → 8 transition's consistency review, B11: five terms, as the
architecture register adds them, not three.)*

| Term | Is, in this platform | Is not | Owner |
|---|---|---|---|
| **Payment Completion** | An operation's internal terminal success — `CAPTURED`, `EXECUTED`, `COMPLETED`, a dispute stage applied, a payout `COMPLETED` — with its posting to the rail's clearing position, or straight to the payee on a book rail | Settlement (`INV-SET-01`): completion says *we* concluded; the counterparty has not paid | `payments`, `merchant` |
| **Clearing** (the exchange) | The network's or scheme's agreement of what is owed, evidenced by `payments.clearing_record` (ARN, network transaction id, no amount — payments `V015`) and by a push confirmation's `settlement_cycle`. A second, different network clearing of one capture is a distinct outcome, `SECOND_PRESENTMENT`, loud and counted unmappable and never absorbed, but stored only in the retained evidence: there is no clearing-notice table and no cleared amount on the wire, so the cleared-amount and second-presentment evidence table is Phase 8's clearing-level matching's to own (ADR-0065's two evidence hops) *(the Phase 7 → 8 transition's card repair)* | A posting or a movement of funds: it moves nothing | `payments` |
| **Clearing position** | The counterparty's own ledger account for value in flight: `SETTLEMENT_CLEARING` (the card PSP), `INSTANT_CLEARING` (the instant scheme), `PAYOUT_CLEARING` (the payout provider) — one counterparty per account (`INV-RAIL-04`) | The settlement account; an in-transit account shared by counterparties | `ledger` |
| **Settlement** | The discharge of a counterparty's obligation, recognised from external evidence in two hops: **reported** when its accepted batch allocates to our expectations and opens a remittance on the same position, **final** when the bank statement moves cash against that position (§3) | Completion; a report of payment — a report is not cash (`INV-SET-06`) | `settlement` (recognition), `reconciliation` (allocation) |
| **Settlement Batch** | The counterparty's settlement unit: a PSP day, a scheme cycle, a payout day, or one bank statement per currency. Single-currency, one per file, identified among live batches by `(source, external_batch_ref, currency)` | Our run over it (the Reconciliation Batch, §4) | `settlement` |
| **Settlement File** | The verbatim bytes a source delivered — screened, encrypted, checksummed, carrying exactly one batch (a file declaring several is `UNSUPPORTED_FORMAT`) | A refused delivery, which keeps metadata only (§5.1) | `settlement` |
| **Settlement Record (Line)** | One canonical, immutable line: type, direction, amount, dates, typed references, and the SHA-256 of the raw record that produced it | The working copy matching disposes (the External Item) | `settlement` |
| **Settlement Account** | The platform's own account at its settlement bank, mirrored per currency by the new purpose `CASH_AT_BANK` and known externally only by the bank's opaque account reference (`INV-RAIL-03`) | A wallet, a customer's bank account, or a clearing position (ADR-0042) | `ledger` (position); configuration (reference) |
| **Remittance** | The net funds movement a counterparty batch implies, N = T_in − T_out − F (N > 0: the counterparty pays us), opened as one `REMITTANCE` expectation of \|N\| on that counterparty's clearing position, which only the bank statement discharges | Settlement: until cash moves, a remittance is a promise | `reconciliation` |
| **External Settlement Reference** | The counterparty's identifier for a settlement unit or funds movement — a PSP batch or remittance id, a scheme cycle token (opaque, 1..64, `PushAnswer.MAX_CYCLE_LENGTH`), a payout-day id, a statement number or entry reference — stored as `batch.external_batch_ref`, `batch.remittance_reference` and `REMITTANCE_REF` on bank lines | Our own references (`cap-…`, `rfd-…`, `pyo-…`, the end-to-end reference) | `settlement` |
| **Payout** | A merchant payout: an instruction the platform originates, `COMPLETED` meaning *instructed* (CR `PAYOUT_CLEARING`, ADR-0057). "Final only at Phase 8's settlement" means the provider's report plus our bank's debit | Settlement of the payable; a settlement instruction | `merchant` |
| **Settlement Instruction** | An outbound instruction to move settlement funds — net settlement, a sweep, prefunding | Anything Phase 8 creates: **it creates none** | — |

**Phase 8 originates no external movement.** Payouts and withdrawals are existing *payment*
instructions (ADR-0057, ADR-0062); Phase 8 recognises what the counterparties and the bank say
happened, and nothing more. Its effects are exactly-once in our database, by keys and unique
constraints; exactly-once external execution is never claimed, because nothing is executed.
Settlement instructions, prefunding, treasury and liquidity management are stated non-goals, not
gaps.

| Kept apart | Why |
|---|---|
| **Settlement** vs **Reconciliation** | Settlement recognises external evidence — it posts the counterparty's fees and the bank's cash. Reconciliation compares that evidence with our expectations and owns the outcome: allocations, breaks, suspense, resolutions. Two modules with no build edge between them (ADR-0064) |
| **Settlement Batch** vs **Reconciliation Batch** | The counterparty's unit of evidence vs one run of ours over it, created in the batch's acceptance transaction — one per accepted batch |
| **Settlement Line** vs **External Item** | The line is immutable evidence; the item is reconciliation's working copy carrying the contended disposition, so no module mutates another's rows |
| **Settlement Expectation** vs **Settlement** | The expectation is our record that value *should* settle, opened when the completion posts (`INV-SET-02`); settlement is the evidence that it did |
| **Remittance** vs **Cash** | A report's net is expected funds; `CASH_AT_BANK` moves only on the bank's own statement (`INV-SET-06`) |
| **Payout** vs **Settlement Instruction** | A payout moves money the platform owes a merchant, on the platform's instruction to its payout provider; a settlement instruction would move the platform's own settlement funds between institutions. Phase 8 creates no settlement instruction and leaves the payout's lifecycle unchanged, adding only its return (§5.10) |
| **Break** vs **Exception** | A break is an aggregate with a subject, a value at issue and a lifecycle; an exception is the operator's umbrella over refused deliveries, rejected files, breaks, silent sources and blocked runs — a view, never a third aggregate |
| **Resolution** vs **Adjustment** | The resolution is the decided disposition (kind, reason code, narrative, frozen amount); the adjustment is the ledger `ADJUSTMENT` entry a posting kind produces through `ledger.adjustment_proposal`, one-to-one with it |
| **Suspense Item** vs **Break** | The item is the parked value; the break is its owner — exactly one per item (`INV-REC-09`) |
| **Tolerance** vs **Write-off** | A tolerance compares unposted quantities — a processing fee, a date — and can never absorb value already in a position (`INV-REC-08`). Absorbing a difference is a write-off, which only an approved resolution posts |

## 2. When finality and settlement occur, per flow

Finality is the rail's declaration (`RailCapabilities.Finality`, ADR-0059 §1); settlement is
evidence. A flow opens a **settlement expectation** exactly when its completion posts to a
clearing position — when the stored rail's `RailCapabilities.clearingPurpose()` is present, or
for the payout, `merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE` — **inside the
completion's own transaction**, past the applier's acting exit (ADR-0067). The port is infallible
for valid input: a reference collision records a counted `KEY_COLLISION` event and becomes a
break, never a failed payment; a recorder failure rolls the completion back, and the redelivery
completes both.

| Flow | Completion and posting key (existing) | Financial finality | Expectation opened (kind, direction) | Reported (hop 1) | Settled (hop 2, cash) |
|---|---|---|---|---|---|
| Card capture (top-up or checkout) | `CAPTURED`; `payment-capture:<attemptId>`, DR `SETTLEMENT_CLEARING` gross | Revocable until the dispute window ends (not modelled as ending in Phase 8); a chargeback is a new movement | `CARD_CAPTURE`, INBOUND | The PSP batch's `CAPTURE` line allocated | A bank credit matching that batch's remittance: DR `CASH_AT_BANK` / CR `SETTLEMENT_CLEARING` |
| Card refund | `COMPLETED`; `payment-refund:<refundId>`, CR `SETTLEMENT_CLEARING` | Final to us once the PSP reports it completed | `CARD_REFUND`, OUTBOUND | `REFUND` line, netted | The same remittance |
| Chargeback | stage `CHARGED_BACK`; `dispute-chargeback:<disputeId>`, CR the clearing D | Provisional until the network rules | `CHARGEBACK`, OUTBOUND | `CHARGEBACK` line | The remittance |
| Chargeback won | `dispute-won:<disputeId>`, DR the clearing D | Final at `WON` | `CHARGEBACK_REVERSAL`, INBOUND | `CHARGEBACK_REVERSAL` line | The remittance |
| Dispute fee | `dispute-fee:<disputeId>`, CR the clearing F | Final (the PSP's charge) | `DISPUTE_FEE`, OUTBOUND | `DISPUTE_FEE` line — an allocating transaction line, not a processing fee | The remittance |
| Card processing fee | **Not posted before Phase 8** (`DECISIONS.md`; ADR-0060 §6) | — | None: checked against the pinned `provider_fee_schedule` | Recognised at acceptance: DR `PROCESSING_COSTS` / CR `SETTLEMENT_CLEARING` | The remittance, net of it |
| Instant pay-in | `EXECUTED`; `payment-execution:<attemptId>`, DR `INSTANT_CLEARING` | `FINAL_ON_ACCEPTANCE` | `PUSH_PAY_IN`, INBOUND (the announced cycle, when announced, recorded as an attribute of the row) | The cycle report's `CREDIT_IN` line | The bank movement of the cycle's net against `INSTANT_CLEARING` |
| Unmatched pay-in confirmation | `unmatched-confirmation:<rail>:<ref>`, DR `INSTANT_CLEARING` / CR `SUSPENSE_UNMATCHED` — one parking per scheme execution, claimed in `payments.scheme_execution_claim` before money moves (payments `V023`) | Final (the scheme's credit) | `UNMATCHED_CONFIRMATION`, INBOUND, **plus** a CREDIT suspense item with its `UNKNOWN_EXTERNAL` break (cause `PARKED_ON_RECEIPT`), keyed on the parking's stored facts: `named_reference`, `settlement_cycle`, `cause` and `attempt_id` | `CREDIT_IN` line | The cycle's net; the suspense is released only by resolution |
| Instant withdrawal | `COMPLETED`; `wallet-withdrawal:<withdrawalId>`, CR `INSTANT_CLEARING` | `FINAL_ON_ACCEPTANCE` (`INV-REV-03`) | `PUSH_WITHDRAWAL`, OUTBOUND (the stored cycle recorded as an attribute of the row) | `DEBIT_OUT` line | The cycle's net |
| Return payment | `COMPLETED`; `payment-refund:<refundId>`, CR `INSTANT_CLEARING` | `FINAL_ON_ACCEPTANCE` | `PUSH_RETURN`, OUTBOUND, **no cycle attribute** | `DEBIT_OUT` line; the cycle is **learned from the scheme's report** and recorded on the item (`learned_cycle`) | The cycle's net |
| Wallet book payment, book refund, transfer | Wallet ↔ payable, or wallet ↔ wallet | `FINAL_ON_POSTING` | **None**: `SettlementModel.NONE` is `INV-SET-01`'s documented per-rail guarantee (ADR-0059 §4), covered internally by the trial balance and statement derivation | — | — |
| Merchant payout | `COMPLETED`; `merchant-payout:<payoutId>`, CR `PAYOUT_CLEARING` | Instructed and irrevocable at the provider; returnable by the beneficiary bank | `MERCHANT_PAYOUT`, OUTBOUND | The payout report's `PAYOUT_EXECUTED` line | A bank debit matching that day's remittance: DR `PAYOUT_CLEARING` / CR `CASH_AT_BANK` |
| Payout return (new, §5.10) | The merchant fact `payout_return`; `merchant-payout-return:<payoutId>`, DR `PAYOUT_CLEARING` / CR `MERCHANT_PAYABLE`; the payout stays `COMPLETED` (`INV-LIFE-04`) | A new operation | `PAYOUT_RETURN`, INBOUND | The payout report's `PAYOUT_RETURNED` line allocated to it | The day's remittance nets it |

**The announced cycle is an attribute, never a key** (ADR-0067 §5): a column of the expectation
row, added in reconciliation `V002`, compared at matching as a tie-breaker (a different cycle is a
`TIMING_DIFFERENCE`) and carried as a report dimension — never an `expectation_key` kind, because
one cycle names many operations and the per-source key unique would make every operation after the
first a collision. *(The Phase 7 → 8 transition's consistency review, A5: this table said "keyed
`SETTLEMENT_CYCLE`" and "the stored cycle as a key".)* *(As built by `P8-TSK-017`: the report's
cycle token is the batch's `external_batch_ref`, carried to the run as its frozen
`settlement_cycle` (reconciliation `V009`) and compared there, never used to choose between
candidates; a shift is `TIMING_DIFFERENCE` with cause `CYCLE_MISMATCH`. A return's item records the
run's cycle in `learned_cycle` when it allocates — once, only that value, never at birth. The
scheme's fees are recognised at acceptance like the PSP's: DR `PROCESSING_COSTS` / CR
`INSTANT_CLEARING`.)*

**The unmatched confirmation, as the Phase 7 → 8 transition left it** (payments `V023`). Each
parking stores the end-to-end reference the statement named when it had our minted shape
(`named_reference`), its `settlement_cycle`, **why** it parked (`cause`: `UNATTRIBUTED`,
`ATTEMPT_CONCLUDED` — an execution on an attempt already concluded — or `AMOUNT_MISMATCH` — a
pay-by-bank execution of another amount than the initiation asked, the pay-in failed `DECLINED`)
and, exactly when attributed, the `attempt_id` it named; `payments.provider_evidence` gained the
fifth subject `unmatched_confirmation_id`, so a parking's raw statement is found by stored
identifier. The parking still has no state and no resolution — both are this phase's (ADR-0070,
`P8-TSK-020`). An attributed parking is not a guess: its natural way out is a
`TRANSFER_TO_ACCOUNT` crediting the named attempt's counterparty, or a return to the payer, which
is the deferred return-to-sender.

Three of the table's rows close recorded Phase 7 inputs. The **return's cycle** is learned from the scheme's
report, so ADR-0062's follow-up closes with no payments migration. The **per-rail cost meter**
(ADR-0060 §6) is answered by `PROCESSING_COSTS` recognised from evidence plus the audited
provider-costs report — never a metric (ADR-0072). **ADR-0062 §7's payout convergence trigger did
not fire**: the canonical settlement line already gives every outbound credit transfer one
evidence shape (ADR-0073).

**The settlement status of an operation** is a reading, never a stored machine:
`GET /v1/operator/reconciliation/settlement-status?kind=&operationRef=` answers `PENDING` (the
expectation is open, nothing allocated), `REPORTED` (allocated from a counterparty's accepted
batch whose remittance the bank has not discharged), `CASH_CONFIRMED` (that remittance is
settled by bank items), `OVERDUE` (`overdue_since` is set and it is not settled) or `RESOLVED`
(`RESOLVED_BY_ADJUSTMENT`), with the identifier trail expectation → allocations → items → batch
→ remittance → bank items. "Reported, awaiting cash" is this reading, not an account. The precedence, decided and built by
`P8-TSK-014` (`SettlementStatus.derive`, pure): `RESOLVED` > `CASH_CONFIRMED` > `REPORTED` >
`OVERDUE` > `PENDING`. A partially allocated operation is `OVERDUE` once its window passed and
`PENDING` before — part of the money is still unaccounted for; `CASH_CONFIRMED` needs EVERY
report batch the expectation was allocated from to have its `REMITTANCE` settled, so an operation
settled from a zero-net batch (which opens no remittance) stays `REPORTED`; `kind=REMITTANCE` is
refused (`422`) — a report's promise is read as an expectation, not as an operation.

## 3. The accounting model (ADR-0065)

### 3.1 Two evidence hops on each counterparty's own position

1. **Completion** — existing and unchanged — posts to the rail's clearing position and, in the
   same transaction, opens an expectation for that line (§2).
2. **Hop 1: the counterparty's report is accepted.** The recognition entry posts **only what the
   platform had not already recorded — the counterparty's fees**: DR `PROCESSING_COSTS` F / CR
   the position F. The report's transaction lines post nothing: they describe value already
   sitting in the position, and they become items allocated to their expectations. One
   `REMITTANCE` expectation of |N| opens on the **same** position.
3. **Hop 2: the bank statement is accepted.** The recognition entry moves cash, DR or CR
   `CASH_AT_BANK`, against **each attributed counterparty's own clearing position**. The bank
   line then allocates to that position's remittance expectation.

**The identity.** For every clearing position and currency, DR−CR equals the signed sum of its
open expectation remainders, less its unallocated, unparked items (§7). That identity is what
*explained* means in this phase: a balance is explainable from authoritative records
(`CLAUDE.md` rule 11) because it decomposes, unit by unit, into expectations and items.

**Why no in-transit account.**

- A report of payment is still that counterparty's obligation. The clearing position already
  says so, and `INV-RAIL-04` forbids netting two counterparties in one account.
- A single shared in-transit account would net the PSP's receivable against the payout
  provider's payable.
- One per counterparty would need a new owner kind — ledger `V002` allows one operational
  account per purpose and currency — and would double every recognition entry's lines for
  information the expectations already carry.
- Cash on report is `INV-SET-01` one level up; posting only when report and bank both arrive
  couples posting to matching. Both were rejected (ADR-0065).

### 3.2 The four new purposes

Each arrives with its first poster, by the V011–V014 ceremony: the four regenerated
constraints, one seeded row per currency (EUR, GBP, USD) with hand-minted UUIDv7 literals below
the `2026-09-28T00:00:00Z` ceiling and literal timestamps, `OperationalChartMigrationTest`'s seed
list extended (`SEEDED_TYPES` moving to `Map.ofEntries`, 9 → 13), and
`everySeededIdSortsBeforeEveryRuntimeId` green — part of the ceremony, not a lock-order argument:
seed order is load-bearing only for `SETTLEMENT_CLEARING` (`V003`) and is never a Phase 8
argument — Phase 8's multi-entry transactions rest on the pre-lock rule (§3.5; DISTRIBUTED_EXECUTION
§3).

| Purpose | Type | Migration (task) | Posted only by |
|---|---|---|---|
| `PROCESSING_COSTS` | EXPENSE | ledger `V016` (`P8-TSK-009`) | Recognition of what external institutions charge for moving money — PSP processing, scheme and bank fees — from their evidence, and a repudiation's reversal of it *(the Phase 7 → 8 transition's consistency review, B8)* |
| `RECONCILIATION_LOSSES` | EXPENSE | ledger `V017` (`P8-TSK-015`) | An approved `WRITE_OFF` |
| `RECONCILIATION_GAINS` | REVENUE | ledger `V017` (`P8-TSK-015`) | An approved `RECOGNISE_GAIN`, after the pinned minimum age |
| `CASH_AT_BANK` | ASSET | ledger `V018` (`P8-TSK-016`) | Recognition of an accepted bank statement, and the repudiation of one (`INV-SET-06`) |

`AccountPurpose.reconciledPositions()` is the three clearings, `SUSPENSE_UNMATCHED`,
`CASH_AT_BANK`, `PROCESSING_COSTS`, `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS`. They are
**closed to free adjustments** at the domain and the database — ledger `V015`'s binding trigger
refuses a `MANUAL`-origin proposal line on any of them (`422
ledger.AdjustmentOnReconciledPosition`) — so a reconciled position is adjusted only through a
break. Otherwise a free adjustment would create unowned suspense or unexplained clearing.

### 3.3 Recognition postings

One entry per accepted batch, written by `settlement` as a system `POSTING`:

- keyed `settlement-batch:<batchId>` under scope `ledger.post`, reference the batch id;
- **`posting_date` = `batch.accepted_on`**, the UTC business date stamped **once** on the batch
  row in the acceptance transaction and read only from the row thereafter — an open period by
  construction, never back-dated to the bank's booking date; **`value_date`** = the evidence's
  value date. Because the posting fingerprint binds both dates, dates from stored data are what
  make a later-day replay converge instead of conflicting (`INV-SET-04`);
- amounts folded with `Money` — the declared trailer net equals the fold, or the file was already
  `REJECTED(CONTROL_TOTAL_MISMATCH)`;
- the **last statement** of the acceptance transaction, after the run, the items, the keys and
  the remittance expectation, so the hot clearing projection row is held as briefly as possible;
- zero lines omitted: an all-zero batch is accepted with `posting_omitted = true` and
  `journal_entry_id NULL`;
- at most 16 lines, pinned by a test (ledger `V004` re-validates the entry once per inserted line).

Phase 8's own postings all take their dates from stored data. The Phase 5–7 flows' clock-read
posting dates are not retrofitted; X-TSK-009 reconciles `DOMAIN_MODEL.md` and `LEDGER_MODEL.md`
with that practice.

Let F be the fee lines (processing, scheme, bank; a rebate has the opposite direction), and N =
T_in − T_out − F.

| Source | Lines | Remittance expectation opened |
|---|---|---|
| PSP report | DR `PROCESSING_COSTS` F / CR `SETTLEMENT_CLEARING` F (the mirror for a net rebate) | \|N\| on `SETTLEMENT_CLEARING`, direction sign(N), keyed `REMITTANCE_REF`, `expected_by` = value date + `funding_lag_days` |
| Scheme cycle | DR `PROCESSING_COSTS` F / CR `INSTANT_CLEARING` F | \|N\| on `INSTANT_CLEARING` |
| Payout report | DR `PROCESSING_COSTS` F / CR `PAYOUT_CLEARING` F — the liability grows: we owe the provider the fee *(as built by `P8-TSK-018`, the fee its own `PAYOUT_FEE` type)* | \|N\| on `PAYOUT_CLEARING`, typically OUTBOUND |
| Bank statement | DR `CASH_AT_BANK` the credits and CR `CASH_AT_BANK` the debits, netted to one line; per attributed source s, CR its clearing (credits_s − debits_s), or DR when negative; DR `PROCESSING_COSTS` the bank fees; for unattributed lines, CR `SUSPENSE_UNMATCHED` their credits and DR their debits, each line opening a suspense item with an `UNKNOWN_EXTERNAL` break (cause `BANK_LINE_UNATTRIBUTED`) in this transaction | None: bank items allocate to remittances |

A bank line is attributed during normalisation, not matching: the bank adapter extracts only the
structured remittance reference, and the line belongs to the **unique** source whose declared
`remittancePattern` matches — zero or two matches leave it unattributed (`INV-SET-05`).

### 3.4 A card day, worked

A capture of 100.00, a refund of 10.00, and a PSP report carrying both with a processing fee of
1.50, so N = 100.00 − 10.00 − 1.50 = 88.50. Signed remainders count INBOUND as + and OUTBOUND as
−; the proof column is the open expectations less the unallocated allocating items.

| Step | Entry | `SETTLEMENT_CLEARING` DR−CR | Open expectations | Unallocated items | Proof |
|---|---|---|---|---|---|
| Capture | `payment-capture:<a>` DR `SETTLEMENT_CLEARING` 100.00 / CR wallet 100.00 | 100.00 | `CARD_CAPTURE` +100.00 | — | 100.00 |
| Refund | `payment-refund:<r>` DR wallet 10.00 / CR `SETTLEMENT_CLEARING` 10.00 | 90.00 | + `CARD_REFUND` −10.00 | — | 90.00 |
| Report accepted | `settlement-batch:<b>` DR `PROCESSING_COSTS` 1.50 / CR `SETTLEMENT_CLEARING` 1.50 | 88.50 | + `REMITTANCE` +88.50 (178.50) | `CAPTURE` +100.00, `REFUND` −10.00 (90.00); the fee item excluded | 88.50 |
| Run over the report | none — the two items allocate, their expectations `SETTLED`; the fee line `CHECKED` against the pinned schedule | 88.50 | `REMITTANCE` +88.50 | — | 88.50 |
| Statement accepted | `settlement-batch:<s>` DR `CASH_AT_BANK` 88.50 / CR `SETTLEMENT_CLEARING` 88.50 | 0.00 | `REMITTANCE` +88.50 | the bank credit +88.50 | 0.00 |
| Run over the statement | none — the bank item allocates to the remittance | 0.00 | — | — | 0.00 |

At rest `CASH_AT_BANK` reads 88.50, the statement's closing balance (the cash proof, §7), and
`PROCESSING_COSTS` 1.50. A payout runs the same hops with a liability's signs: after a 50.00
payout `PAYOUT_CLEARING` reads −50.00 (a `MERCHANT_PAYOUT` OUTBOUND expectation), −50.25 once the
provider's 0.25 fee is recognised (a `REMITTANCE` OUTBOUND of 50.25), and 0.00 after the bank's
debit, DR `PAYOUT_CLEARING` 50.25 / CR `CASH_AT_BANK` 50.25.

### 3.5 Park, unpark and offset

Parking moves value nothing explains out of the counterparty's position into
`SUSPENSE_UNMATCHED`, **in the transaction that decides it** — a run chunk, the grace leg or a
rematch. With P the item's position:

| Act | Lines | Suspense item |
|---|---|---|
| Park an INBOUND remainder u | DR P u / CR `SUSPENSE_UNMATCHED` u | A CREDIT item opens: funds arrived that nothing explains |
| Park an OUTBOUND remainder u | DR `SUSPENSE_UNMATCHED` u / CR P u | A DEBIT item opens: funds left that nothing explains |
| Unpark (a later allocation of a parked item), or a correction offset | The exact inverse, for the amount | The item(s) released |

Parks and unparks are **aggregated per transaction and position into one entry** of at most 4
lines, keyed `recon-suspense:<parkId>` — the `reconciliation.park` row is minted in that
transaction and names every suspense item it opened or released. The item's conditional
transition is the arbiter; the posting is the last statement of its transaction; the posting
date is the park row's `decided_on` (stamped once) and the value date the item's settlement date.
Only the platform's own accounts are touched, and seed order is no part of the argument — it is
load-bearing only for `SETTLEMENT_CLEARING` (`V003`). A transaction posting several entries over
shared rows — a repudiation's reversal beside its unparks, or any other — pre-locks the union of
the platform's rows it will touch in the projection's own order before its first posting
(`PostingService.lockBalancesInOrder`, DISTRIBUTED_EXECUTION §3's multi-entry lock-order rule, set
by the Phase 7 → 8 transition's dispute repair), and takes any runtime counterparty's ACCOUNT row
— `FOR SHARE` where a share lock suffices — before any projection row.

The Phase 7 unmatched confirmation keeps its own entry (DR `INSTANT_CLEARING` / CR
`SUSPENSE_UNMATCHED`); through the port it gains its `UNMATCHED_CONFIRMATION` expectation
(`P8-TSK-005`) and its CREDIT suspense item with the `PARKED_ON_RECEIPT` break (`P8-TSK-020`), keyed
on the parking's stored `named_reference`, `settlement_cycle`, `cause` and `attempt_id` (payments
`V023`, §2), and the rows that already exist are adopted by an idempotent backfill. A parking that
`V023`'s backfill left without a scheme-execution claim — its execution was also credited, the
double record `V023` closed for new executions — stands visible in both tables for this phase's
resolution: the two expectations' `SCHEME_REF` keys meet at opening as a `KEY_COLLISION`, which
surfaces the `DUPLICATE_INTERNAL` it is (§4 of [`RECONCILIATION_MODEL.md`](RECONCILIATION_MODEL.md)).

**Correction, reversal, adjustment.** A counterparty's correction is never an edit: it is new
evidence, a `COUNTERPARTY_ADJUSTMENT` line in a later batch. A **reversal** is used only by batch
repudiation, through `ReversalService` (`INV-REV-01`). An **adjustment** happens only through a
resolution.

### 3.6 What a resolution posts

The proposer chooses the kind, the reason code, the narrative and, for a transfer, the target.
**The lines are derived from the subject's current remainder, never typed.** A posting kind
writes an `ADJUSTMENT` entry through a `ledger.adjustment_proposal` with origin
`RECONCILIATION` and a closed reason code, under scope `ledger.adjust.approve:<proposalId>`, dated
`proposed_on` as frozen at proposal.

| Kind | Applies to | Lines | Approvers |
|---|---|---|---|
| `EVIDENCED` | Any break explained by a zero-residual allocation or offset | None of its own — the allocation's unpark or the offset is the posting; the stored resolution names the decision and the park | The platform only |
| `ACKNOWLEDGE` | `TIMING_DIFFERENCE`, `FEE_MISMATCH`, `DUPLICATE_INTERNAL` | None | One when the value is 0; otherwise two |
| `WRITE_OFF` | An INBOUND remainder in P; a DEBIT suspense item | DR `RECONCILIATION_LOSSES` / CR P (or CR `SUSPENSE_UNMATCHED`) | Two |
| `TRANSFER_TO_ACCOUNT` | A CREDIT suspense item; an OUTBOUND remainder in P | DR `SUSPENSE_UNMATCHED` (or DR P) / CR a named `CUSTOMER_WALLET` or `MERCHANT_PAYABLE`, `ACTIVE` and in the same currency, share-locked before posting (the chargeback precedent) | Two |
| `OFFSET_SUSPENSE` | A CREDIT and a DEBIT suspense item of equal amount and currency | None — the account already nets; both items released | Two |
| `RECOGNISE_GAIN` | A CREDIT suspense item older than `gain_min_age_days` (pinned; seeded 90), of a break type that admits it (§6) | DR `SUSPENSE_UNMATCHED` / CR `RECONCILIATION_GAINS` | Two |
| `MANUAL_MATCH` | `AMBIGUOUS_MATCH` | A `MANUAL`-origin decision chooses one candidate; the unpark as for any late allocation | Two — it stands in for the engine |
| `REPUDIATE_BATCH` | An accepted batch (§5.2) | `ReversalService` on the recognition entry; counter-allocations; unparks | Two |

**Which kinds a break admits is one table: ADR-0069's per-type table**, carried identically in
§6. A kind is admissible only where that table lists it for the break's type and the subject has
the side its lines require (`reconciliation.ResolutionKindNotAllowed` otherwise). The "Applies
to" column above says which side each kind's lines need; where it names break types
(`ACKNOWLEDGE`, `MANUAL_MATCH`) it repeats that table and never widens it. *(The Phase 7 → 8
transition's consistency review, A1.)*

When an approved closing resolution removes an item's parked value the item moves to `RESOLVED`;
when it removes an expectation's remainder the expectation moves to `RESOLVED_BY_ADJUSTMENT`. A
transfer credits a named customer or merchant account, so the attribution is named where it lands,
by **origin**: every payable line in a `RECONCILIATION`-origin `ADJUSTMENT` entry (ledger `V015`'s
origin) is `MerchantPayable`'s `reconciliationAttributed`, classified first, **whatever it faces** —
`SUSPENSE_UNMATCHED`, or a clearing position when a `TRANSFER_TO_ACCOUNT` closes an OUTBOUND
remainder (DR P) — so a reconciliation transfer never reads as a capture or a payout returned; and
the customer statement labels the same resolution-entry lines `RECONCILIATION_ATTRIBUTION`
(ADR-0073 §6). Both ship with `P8-TSK-015`, whose `TRANSFER_TO_ACCOUNT` is their first poster — each
with a counted test. *(The Phase 7 → 8 transition's consistency review, A12 and A13: the rule was
"a payable line facing `SUSPENSE_UNMATCHED`", and the owner was left ambiguous with `P8-TSK-019`.)*

## 4. The reconciliation concepts and their owners

| Term | Is | Owner |
|---|---|---|
| **Reconciliation Job** | A source's standing configuration: its descriptor, its active rule set, and the legs that drive it (run, rematch, grace, ageing). Configuration, not an aggregate | `reconciliation` |
| **Reconciliation Batch** (a run) | One execution over one accepted settlement batch, created in the acceptance transaction, pinning `rule_set_id`, the batch's business date and `source_sequence`; kind `BATCH`, or `REPROCESS` over residual items. Table `reconciliation.reconciliation_batch`, the glossary's term kept | `reconciliation` |
| **Reconciliation Source** | A declared source: code, kind, format, counterparty (a rail, the payout provider, or the bank), channels, remittance-reference pattern, and the position read from that counterparty's own declaration | `settlement` (composed in `app`) |
| **Internal Record** | A **Settlement Expectation**: the immutable facts of one completed clearing line — kind, operation reference, `ledger_account_id`, position purpose, direction, amount, `journal_entry_id`, posting date, keys — plus its disposition, expected by a date and pinned to the rule set that dated it. `REMITTANCE` expectations are opened by report acceptance | `reconciliation` |
| **External Record** | An **External Item**: reconciliation's working copy of one settlement line — type, direction, amount, dates, keys, fingerprint, position — with its disposition. The immutable original stays in `settlement.line` | `reconciliation` (copy), `settlement` (line) |
| **Match** | A **Match Decision** — one evaluation of one item: its pinned rule set, the rule that fired, the outcome and explanation, and a **snapshot of every candidate it saw** — plus the **Allocations** it produced, append-only item → expectation amounts | `reconciliation` |
| **Matching Rule** | A versioned rule: line type → expectation kind, key kind, cardinality (`ONE_TO_ONE`, `PARTIAL`, `GROUP_BY_VALUE_DATE`, `CORRECTION`, `CHECK`), priority | `reconciliation` |
| **Tolerance** | A versioned bound in the rule set, on `PROCESSING_FEE_PER_LINE`, `PROCESSING_FEE_PER_BATCH` or `SETTLEMENT_DATE_DAYS` only — there is no amount member (`INV-REC-08`) | `reconciliation` |
| **Reconciliation Break** | A classified discrepancy with a value at issue (possibly 0), a severity, a residual version, the internal classification the lookup found, evidence links and a lifecycle. Never deleted; a recurrence after resolution is a new break naming its predecessor (`follows_break_id`) | `reconciliation` |
| **Exception** | The operational umbrella over refused deliveries, rejected files, breaks, silent sources and blocked runs. A view | both |
| **Investigation** | The break's case file: assignee history, notes, evidence links, reclassifications, audited content reads. No machine of its own — `INVESTIGATING` is a break state | `reconciliation` |
| **Resolution** | A decided disposition with a subject (a break, or for `REPUDIATE_BATCH` a settlement batch), a kind, a reason code, a narrative, the frozen proposed amount and the residual version | `reconciliation` |
| **Adjustment** | The ledger `ADJUSTMENT` entry a posting resolution produces, one-to-one with it | `ledger` (posted), `reconciliation` (derives the lines) |
| **Suspense Entry / Suspense Item** | The suspense line — from a park, a bank recognition's unattributed line, or a Phase 7 unmatched confirmation — and the tracked item: side, amount, released amount, age, and **exactly one owning break** (`INV-REC-09`) | `ledger` (line), `reconciliation` (item) |

**Ownership** (ADR-0064). `settlement` (context 13) is the sole writer of the source register,
files with their chunks, receipts and history, refused deliveries, batches, lines and their
references, ingestion errors, and each batch's recognition posting — the external side.
`reconciliation` (context 14) is the sole writer of expectations with their keys and aliases,
runs, external items, match decisions with their candidate snapshots, allocations, rule sets,
breaks with their case files, resolutions, suspense items and parks, and run replays — the
internal side, the comparison and the outcome. The expectation moves here from `settlement`
(`MODULE_ARCHITECTURE.md`'s M8 amended): it is internal state whose lifecycle allocation drives,
and `INV-REC-07` must be a local constraint. Neither module depends on the other; `app` composes
them through required-constructor ports — `SettlementExpectations` (declared in `payments`),
`PayoutSettlementExpectations` and `PayoutReturns` (`merchant`), `AcceptedBatchIntake`
(`settlement`), `InternalReferenceLookup` (`reconciliation`; read-only, used for break typing
only, never for allocation), and the repudiation seam `P8-TSK-023` names, through which
`settlement` writes the reversal and `ACCEPTED → REPUDIATED` on the approval's connection — the
fourth cross-module transaction, `reconciliation` then `settlement` then `ledger` (ADR-0064 §3;
the Phase 7 → 8 transition's consistency review, B5). There is no cross-schema foreign key or
join: an expectation holds copies of the immutable facts it was opened from.

**The sources**, composed in `app` from each counterparty's declaration — one per rail with
`settlement() != NONE`, its position read from `clearingPurpose()`; the payout provider's from
`PayoutSettlementDeclaration`; the bank's from `settlement`'s own `CASH_AT_BANK` — so no rail name
and no second copy of a declaration exists (`INV-SET-05`):

| Code | Kind | Format | Position | Channels |
|---|---|---|---|---|
| `simulated-psp.settlement` | `PSP_SETTLEMENT_REPORT` | `SIM_PSP_CSV` v1 | the card rail's clearing | UPLOAD, PULL |
| `simulated-scheme.cycle-report` | `SCHEME_CYCLE_REPORT` | `SIM_SCHEME_JSON` v1 | the instant rail's clearing | UPLOAD, PULL |
| `simulated-payout.settlement` | `PAYOUT_PROVIDER_REPORT` | `SIM_PAYOUT_CSV` v1 | `PAYOUT_CLEARING` | UPLOAD, PULL |
| `simulated-bank.statement` | `BANK_STATEMENT` | `SIM_STATEMENT_TAGGED` v1 (MT940-shaped) | `CASH_AT_BANK` | UPLOAD, PULL |

Webhook-derived records are **not** sources: they already produced internal state, so
reconciling state against its own inputs proves nothing. They feed matching instead — the ARN
alias `PaymentClearing` registers through the port as a key, and the instant callback's cycle
token recorded on the expectation as an attribute, never a key (§2).

## 5. The state machines

Every stored machine below gets the platform's standing three-layer enforcement: the aggregate's
exhaustive transition sweep refusing invalid edges; a generated schema `CHECK` plus an every-writer
transition trigger, both from the aggregate's `permittedTransitions()`; and an append-only history
recording actor id, actor type, occurred at and reason (`INV-LIFE-01/-02`, the ADR-0044 doctrine:
**states are earned by producers**). §5.11 lists the tables. `finapp_app` holds no `DELETE` on any
table of either schema.

### 5.1 Settlement file (`settlement.file`)

```
 (upload · pull · readmission)
              │
              v
          RECEIVED ──parse ok──> PARSED ──accept──> ACCEPTED
              │                    │
              └─────────┬──────────┘
                        v
                    REJECTED   (a content defect at parse · DECLINED · SOURCE_RETIRED)
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `RECEIVED` | The door: an upload (a person with `SETTLEMENT_INGEST`), a pull (the platform, over the source's confined credential, from a source URL held to `ProviderTransportGuard`'s rule — no plaintext transport off loopback, refused at startup — which the pull task, `P8-TSK-021`, extends from the providers' https URLs to its own SFTP and HTTPS source URLs; the Phase 7 → 8 transition's transport repair), a readmission (a person with `RECONCILIATION_ADMINISTER`, reasoned) | Within 8 MiB and 50,000 lines (`413 settlement.FileTooLarge` otherwise, nothing stored); passed the screen; not a duplicate of live content — `UNIQUE (source_id, content_sha256) WHERE readmits_file_id IS NULL`, whose loser appends a `DUPLICATE` receipt and is answered with the existing file (`duplicateOf`) |
| `RECEIVED → PARSED` | The parse leg of `SettlementIntakeSchedule` (the platform) | The whole file valid; the trailer's count and net equal the `Money` fold; one currency; no live batch conflict. The batch is born `PARSED` in the same transaction, with its lines, references and totals |
| `RECEIVED → REJECTED` | The parse leg | A content defect: `MALFORMED`, `CONTROL_TOTAL_MISMATCH`, `UNKNOWN_CURRENCY`, `SCALE_MISMATCH`, `UNSUPPORTED_FORMAT`, `CONFLICTING_BATCH` — up to 100 `ingestion_error` rows, never content, and no batch |
| `RECEIVED` or `PARSED` → `REJECTED` | The uploader or an attester (`DECLINED`, reasoned) | The file is not terminal |
| `PARSED → REJECTED` | The accept leg (`SOURCE_RETIRED`) | The source was retired after receipt |
| `PARSED → ACCEPTED` | The accept leg (the platform) | PULL: always. UPLOAD: only once `attested_by` is set, with `attested_by ≠ received_by`. READMISSION: when the original was pulled or attested, it inherits that authentication through the identical checksum; when the original was an unattested upload — rejected at parse before anyone attested it — the readmission is itself attested before acceptance, by a person distinct from the readmitter (its `received_by`) and from the original's uploader (the original's `received_by`), held at the database by `P8-TSK-022`'s own settlement migration *(the Phase 7 → 8 transition's re-check, R5)* |

- **Attestation** is a `NULL → value` fact (`attested_by`, `attested_at`), settable on `RECEIVED`
  or `PARSED` and never on a terminal file (`settlement.FileNotAttestable`), by a second person
  holding `SETTLEMENT_INGEST` (`settlement.AttestationBySubmitter` otherwise). The attester may
  first read the content, audited, and the parsed totals. Two schema rules hold it for every
  writer: `CHECK (attested_by IS NULL OR attested_by <> received_by)` and `CHECK (status <>
  'ACCEPTED' OR received_via <> 'UPLOAD' OR attested_by IS NOT NULL)`. An unattested upload is
  retained but inert (`INV-SET-07`), and the same rule binds a readmission whose original was an
  unattested upload: it inherits no authentication, so it is attested before acceptance by a
  person distinct from the readmitter and from the original's uploader. `V002`'s `CHECK`s bind
  only `UPLOAD`, and a cross-row rule needs a trigger, so `P8-TSK-022` holds it at the database
  with a settlement migration of its own: a trigger refusing `ACCEPTED` for a `READMISSION` of a
  never-attested original unless the readmission's `attested_by` is set and differs from the
  readmitter (the readmission's `received_by`) and from the original's `received_by` *(the Phase
  7 → 8 transition's re-check, R5)*.
- **Invalid:** `RECEIVED → ACCEPTED`; any edge out of `ACCEPTED` or `REJECTED`; `PARSED →
  RECEIVED`.
- **Terminal:** `ACCEPTED`, `REJECTED`. A file stays `ACCEPTED` when its batch is repudiated: the
  evidence is retained, and what is undone is its recognition (§5.2).
- **Our own failure never rejects evidence.** A parser exception leaves the file `RECEIVED`, with
  `parse_failures + 1` and `next_parse_at` backed off, visible as a stuck file
  (`finapp.settlement.file.age`). A wrongly rejected file — rejected by *our* validation — is
  readmitted: a new row naming the original (`readmits_file_id`), born `RECEIVED`, parsed under
  the current format version; the original stays `REJECTED` (`settlement.FileNotRejected` for any
  other original). **A genuine file rejected `CONFLICTING_BATCH` against a batch since
  `REPUDIATED` is admissible for readmission too**: the conflict was with evidence now proven false,
  and a byte-identical re-presentation would only meet the rejected file's own content address as
  its duplicate, so readmission is how a repudiation's genuine file is recovered (§5.2). **A
  declined upload is not readmitted** — declining is a person's judgement, not our validation;
  whether readmission extends to it is ADR-0066 §8's recorded question, carried into `P8-TSK-022`,
  and such a readmission would inherit no authentication. *(The Phase 7 → 8 transition's
  consistency review, A11.)*
- **A refused delivery is not a file.** The door screen runs in memory before anything is stored:
  reference fields by their shapes, free text for Luhn-valid 13–19-digit runs and IBAN, account or
  alias shapes — and a field that fails its declared class is screened as free text before the
  file can be stored as malformed (ADR-0066 §3; the Phase 7 → 8 transition's consistency review,
  C6) — and a conservative whole-stream screen for bytes that do not parse. A dirty
  delivery writes only a `settlement.refused_delivery` metadata row — source, SHA-256, length,
  reason, line number, field name, never the value — plus an audit record and the alertable
  `finapp.settlement.delivery.refused`. ADR-0066 rules that `INV-RAIL-03` and `INV-PAY-02` take
  precedence over `INV-HIST-02` for a refused delivery (transition decision O3, §8). Recovery is
  by re-presentation; the refused row and the later file share the checksum, so the chain shows.

### 5.2 Settlement batch (`settlement.batch`)

```
 (born with its file, at parse)
PARSED ──accept──> ACCEPTED ──an approved REPUDIATE_BATCH──> REPUDIATED
   └──its file rejected──> REJECTED
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `PARSED` | The parse leg, in its file's `RECEIVED → PARSED` transaction | The live-batch uniques admit it: `UNIQUE (source_id, external_batch_ref, currency)` and, for statements, `UNIQUE (source_id, currency, statement_sequence)`, both among batches not `REJECTED` or `REPUDIATED` |
| `PARSED → ACCEPTED` | The accept leg, in its file's `PARSED → ACCEPTED` transaction | The source row locked `FOR UPDATE` for the gapless `source_sequence`; `accepted_on` stamped once; the run created (`AcceptedBatchIntake`); items, keys and the remittance expectation — or, for a statement, the unattributed lines' suspense items with their breaks — then the recognition posting as the last CONTENDED write, PRECEDING the batch's accepting `UPDATE`: `V004`'s honesty `CHECK` wants the entry id in that same statement, and every row after the posting is one the transaction already exclusively claimed *(built so by `P8-TSK-009`, the design's recorded correction of this row's "posting last")*. For a statement (`P8-TSK-016`): the accepted predecessor and successor of its currency read under the same source row lock; the unattributed lines' breaks raised and their items moved `PENDING → PARKED`; the chain judged (`STATEMENT_GAP`/`OPENING_BALANCE` on the run, a filled successor's gap closed `EVIDENCED`); then the recognition; then, AFTER it, each unattributed line's `BANK_UNATTRIBUTED` suspense item carrying the entry id; no remittance. Publishes `settlement.SettlementBatchAccepted` |
| `PARSED → REJECTED` | Whatever rejects its `PARSED` file (a decline, `SOURCE_RETIRED`), in the same transaction | — |
| `ACCEPTED → REPUDIATED` | The approval of a `REPUDIATE_BATCH` resolution by a second person holding `RECONCILIATION_RESOLVE` (`P8-TSK-023`) | Four-eyes, reason `EVIDENCE_REPUDIATED`. Publishes `settlement.SettlementBatchRepudiated` |

- **Invalid:** any edge out of `REJECTED` or `REPUDIATED`; `ACCEPTED → REJECTED`; `REPUDIATED →
  ACCEPTED` — the genuine file is a new batch, admitted because the live uniques exclude the
  repudiated one; any birth but `PARSED`.
- **Terminal:** `REJECTED`, `REPUDIATED`. `ACCEPTED` is final but for the one designed exit.
- The acceptance columns (`source_sequence`, `accepted_on`, `journal_entry_id`,
  `posting_omitted`) are set once, by trigger; `CHECK` holds `ACCEPTED ⇒ source_sequence,
  accepted_on NOT NULL` and `(journal_entry_id IS NULL) = posting_omitted` once accepted.
- **There is no `HELD`.** A control-total mismatch rejects the whole file (a partially corrupt
  file fails the batch); a remittance that differs from the bank is a break, not a batch state.

**Repudiation** — for an accepted batch proven fabricated or mis-normalised. In the approval
transaction — the seam through which `settlement` writes on the approval's connection (§4):
`ReversalService` reverses the recognition entry (scope `ledger.reverse`, key
`settlement-batch:<batchId>`, the `TransferReversal` precedent); every allocation of the batch's
items gains an append-only counter-allocation (`reverses_allocation_id`), restoring expectation
remainders; a bank item whose allocation named the batch's `REMITTANCE` expectation is
counter-allocated in the same transaction and reopened, `MATCHED → UNMATCHED`, to wait for the
genuine remittance (§5.4); the items' `RECON_PARK` suspense items still holding value are released
by an unpark (`recon-suspense:<parkId>`), while a `BANK_UNATTRIBUTED` item is released by the
recognition's reversal itself, which already carries its suspense line (ADR-0070 §10); the items and
the batch move to `REPUDIATED`. These are effects, not order: reconciliation's rows first, then
settlement's transition, then the reversal and the unparks last — postings last (ADR-0065,
ADR-0064 §6, PHASE_8_PLAN §7) — their union pre-locked by the multi-entry rule (§3.5). The file
and its content are retained, and the genuine file is then re-presented and accepted normally — or
readmitted, when it was itself rejected `CONFLICTING_BATCH` against the repudiated batch (§5.1).
The item's `REPUDIATED` and its reopening `MATCHED → UNMATCHED`, the expectation's reopening
edges, the suspense item's origin `REPUDIATION`, the `REPUDIATE_BATCH` kind and the batch subject
arrive with `P8-TSK-023`'s reconciliation `V012` (§5.11).

These consequences are left to `P8-TSK-023`'s design, which confirms or corrects them. **An item
still `PENDING` has no edge to `REPUDIATED`**, so a repudiation is approvable only once the
batch's run has disposed every item. **The remittance expectation the batch opened** must leave
the position proof with it: this document closes its unallocated remainder with the approved
`REPUDIATE_BATCH` — the one edge the expectation machine offers an approved resolution, `→
RESOLVED_BY_ADJUSTMENT` — including the part a bank allocation's counter-allocation restores
(§5.4), so the reopened bank item alone carries that cash until the genuine remittance arrives.
With §3.4's card day repudiated after its cash: `SETTLEMENT_CLEARING` reads 1.50 (the fee's
recognition reversed); the reopened capture and refund give +100.00 − 10.00, the closed remittance
nothing, and the reopened bank credit, unallocated, −88.50 — 1.50, so the proof holds at the
approval's commit. *(A9 replaced "leaves open the remittance the bank already
discharged", which the reopening makes unbalanced.)* **Items
already `RESOLVED`, and breaks whose subjects are the batch's items**, stay as they are, because
`RESOLVED` is terminal and a resolution has one subject; whether the repudiation closes those
breaks is that task's question. **A `BANK_UNATTRIBUTED` item a posting resolution already
released** cannot be released again: the reversal still carries its suspense line, which opens a
new item of the opposite side — origin `REPUDIATION`, the fourth opener (§5.8), `origin_ref` the
released item's id, opened on the reversal entry's posting date — owned by a new
`PROCESSING_ERROR` break raised in the approval transaction, and a person decides where the loss
falls (ADR-0070 §10). **A payout return applied
from the repudiated batch** stands as a merchant fact — repudiation does not touch `merchant` — and
its reopened `PAYOUT_RETURN` expectation ages into `MISSING_EXTERNAL`, so nothing is silent; taking
the value back from the merchant is no Phase 8 resolution kind, and the task states the outcome
(ADR-0073). **A bank item `PARKED` with an allocation to the repudiated remittance standing beside
its excess** (an over-payment) has no reopening edge, and the task settles it. *(The Phase 7 → 8
transition's consistency review, A8, A9 and A10; its re-check, R3: the released item's new item
named no origin, while ADR-0070 point 10 makes the repudiation a fourth opener.)*

### 5.3 Reconciliation batch — the run (`reconciliation.reconciliation_batch`)

```
OPEN ──first chunk──> IN_PROGRESS ──last chunk──> COMPLETED
  │ └──────────── no items (item_count = 0) ───────────^
  │                      │
  └──────────────────────┴──N consecutive failures──> BLOCKED ──requeue (a person)──> IN_PROGRESS
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `OPEN`, kind `BATCH` | The acceptance transaction, through `AcceptedBatchIntake` | `UNIQUE (batch_id)`; `UNIQUE (source_id, source_sequence) WHERE kind = 'BATCH'` |
| (birth) → `OPEN`, kind `REPROCESS` | A person with `RECONCILIATION_ADMINISTER`, keyed and reasoned | One open `REPROCESS` per source (`reconciliation.ReprocessingInProgress`); it re-resolves residual items only (`UNMATCHED` or `PARKED`) under the active rule set |
| `OPEN → IN_PROGRESS` | The run leg of `ReconciliationSchedule` (the platform), first chunk | Eligible only when no lower-`source_sequence` `BATCH` run of its source is incomplete; `pg_try_advisory_xact_lock(4, hashtext(source_id::text))` held per chunk |
| `IN_PROGRESS → COMPLETED` | The run leg, last chunk | No item `PENDING` — the domain, and a deferred trigger. Publishes `reconciliation.ReconciliationRunCompleted` |
| `OPEN → COMPLETED` | The run leg | `item_count = 0` |
| `OPEN` or `IN_PROGRESS` → `BLOCKED` | The run leg, or the sweep's run-block detection | N consecutive chunk failures. A CRITICAL `PROCESSING_ERROR` break (cause `RUN_BLOCKED`) is raised in the same transaction |
| `BLOCKED → IN_PROGRESS` | A person with `RECONCILIATION_ADMINISTER` (requeue, reasoned, audited) | The run is `BLOCKED` (`reconciliation.RunNotBlocked` otherwise). Once the run completes, its `RUN_BLOCKED` break resolves `EVIDENCED` |

- A chunk re-reads the run's cursor, processes at most 200 items and advances the cursor **in the
  same transaction**; the next chunk may run on any instance. A crash rolls back to the last
  cursor and resumes elsewhere in the same claimant order.
- **Invalid:** completing with any item `PENDING`; any edge out of `COMPLETED`; `BLOCKED →
  COMPLETED` without a requeue; any edge back to `OPEN`.
- **Terminal:** `COMPLETED`. A `BLOCKED` run holds its source visibly — later runs of the source
  are ineligible and `finapp.reconciliation.run.blocked` alerts — until it is requeued. There is no
  silent skip.

### 5.4 External item — the matching status (`reconciliation.external_item`)

```
            ┌──> MATCHED ───┐
            ├──> CHECKED ───┤
PENDING ────┼──> OFFSET ────┼──batch repudiated──> REPUDIATED
            ├──> UNMATCHED ─┤
            └──> PARKED ────┘
UNMATCHED ──rematch (the whole remainder)──> MATCHED
UNMATCHED ──grace expired, or a definitive class──> PARKED
PARKED ──rematch, or an approved MANUAL_MATCH (unpark)──> MATCHED
PARKED ──an approved closing resolution──> RESOLVED
MATCHED ──an approved REPUDIATE_BATCH of the batch whose remittance it matched (a bank item)──> UNMATCHED
```

Born `PENDING` in the acceptance transaction, one per settlement line (`UNIQUE
settlement_line_id`). Its amounts obey `allocated_minor + parked_minor + offset_minor ≤
amount_minor` (`CHECK`).

| Edge | Driver | Condition |
|---|---|---|
| `PENDING → MATCHED` | The run leg (a chunk), a `REPROCESS` run | The item is allocated whole under the pinned rule — `ONE_TO_ONE`, `PARTIAL`, `GROUP_BY_VALUE_DATE` (the item equals exactly the total of **all** open remittances of its source, direction, currency and value date with no other claimant), or a `CORRECTION` topping up the original's remainder |
| `PENDING → CHECKED` | The run leg | A non-allocating fee or bank-fee line, checked under `CHECK` against the pinned `provider_fee_schedule`; `FEE_MISMATCH` raised beyond the tolerance |
| `PENDING → OFFSET` | The run leg | A `CORRECTION` whose original item holds a parked excess of the opposite direction and equal amount: a suspense release with cause `CORRECTION_OFFSET` and an unpark posting; the original's `AMOUNT_MISMATCH` resolves `EVIDENCED` |
| `PENDING → UNMATCHED` | The run leg | A remainder that late internal evidence could still change — `UNKNOWN_EXTERNAL` (the key is unknown), `MISSING_INTERNAL` (the operation is known but not completed), a `PAYOUT_RETURNED` with no return yet — with `grace_until` pinned per rule. A `PAYOUT_RETURNED` line always takes this edge: rule set v1 declares its rule **operation-anchored**, so it is never key-matched against the OUTBOUND `MERCHANT_PAYOUT` expectation its references name (which would be a direction mismatch, parked at once as `REVERSAL_MISMATCH`), and the matcher raises no break for it; it waits for the return worker (§5.10) *(the Phase 7 → 8 transition's consistency review, A4)* |
| `PENDING → PARKED` | The run leg | A definitive class, parked at once with its break in this transaction: `DUPLICATE_EXTERNAL`, `CURRENCY_MISMATCH`, the `AMOUNT_MISMATCH` excess, `AMBIGUOUS_MATCH`, `REFUND_MISMATCH` against a terminal failed refund, `REVERSAL_MISMATCH` against a terminal state; or a poisoned item (decision `ERRORED`, a `PROCESSING_ERROR` break), contained so the chunk continues |
| `UNMATCHED → MATCHED` | The rematch leg | A late internal record allocates the whole remainder |
| `UNMATCHED → PARKED` | The grace leg; the rematch leg | `grace_until` has passed, judged in SQL on the **database clock**, or the rematch finds a definitive class — parked with its break in this transaction |
| `PARKED → MATCHED` | The rematch leg; an approved `MANUAL_MATCH` | A late allocation of the parked remainder, with the unpark in the same transaction; the break resolves `EVIDENCED`, or by the `MANUAL_MATCH` itself |
| `PARKED → RESOLVED` | The approval of a closing resolution; the platform's `EVIDENCED` | The parked value removed by `WRITE_OFF`, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE` or `RECOGNISE_GAIN`, as the break's type admits (§6), or — for an original whose excess a correction offset — by the `EVIDENCED` resolution the offset produced |
| `MATCHED`, `CHECKED`, `OFFSET`, `UNMATCHED` or `PARKED` → `REPUDIATED` | The approval of its batch's `REPUDIATE_BATCH` | Counter-allocations and unparks in the same transaction (§5.2) |
| `MATCHED → UNMATCHED` (reopened) | Only the approval of a `REPUDIATE_BATCH` — never a person, a leg or a rule-set change — and only for an item **of another batch**: a bank item whose allocation named the repudiated batch's `REMITTANCE` expectation. The repudiated batch's own items leave `MATCHED` by `→ REPUDIATED` (the row above) | That allocation gains its counter-allocation in the same transaction, restoring a remainder above zero; the item then waits, with a fresh `grace_until` under its pinned rule (the window is `P8-TSK-023`'s to pin), for the genuine remittance the re-presented or readmitted file opens, and the rematch and grace legs treat it like any other `UNMATCHED` item *(the Phase 7 → 8 transition's consistency review, A9)* |

- `UNMATCHED` means a remainder above zero, not parked, grace running; `PARKED` means the
  remainder is fully parked (an over-payment is `PARKED` with its allocation standing beside the
  excess); `CHECKED` means a non-allocating line.
- **Claimant order.** Every allocation to an expectation goes through one `allocate(E)`, shared by
  the run, rematch, reprocess and manual legs, serving claimants in `(source_sequence, line_no)`
  order — so the earlier record always wins. Namespace 4 **orders** allocation; the uniques and
  the Σ triggers **arbitrate** it.
- **Classification precedence** for an unallocated remainder: the definitive specific types
  first, then `MISSING_INTERNAL` (the operation is known), then `UNKNOWN_EXTERNAL`. On the instant
  rail the typing reads `payments.scheme_execution_claim` (payments `V023`) through
  `InternalReferenceLookup`: one row per `(rail, scheme_reference)`, naming its one subject
  (`PAY_IN`, `WITHDRAWAL`, `RETURN` or `UNMATCHED`), claimed by every producer before money moves.
  A scheme line's reference therefore has at most one internal explanation; a claim whose subject
  has not completed types `MISSING_INTERNAL`. A pay-in's claim is taken at `EXECUTED`, a
  withdrawal's or return's at `COMPLETED`, so a scheme line for an operation still in flight has
  no claim yet: a scheme reference no claim holds names no completed execution — after grace it
  types `MISSING_INTERNAL` when its other references name an operation still in flight, and
  `UNKNOWN_EXTERNAL` otherwise. The claim types a break; it never allocates.
- **Invalid:** any edge back to `PENDING`; `PENDING → RESOLVED`; `PENDING → REPUDIATED`; anything
  out of `RESOLVED` or `REPUDIATED`; any edge out of `CHECKED` or `OFFSET` but the repudiation; any
  edge out of `MATCHED` but the repudiation's two — `→ REPUDIATED` for its own batch's items, `→
  UNMATCHED` for a bank item reopened. A person allocating a record already matched is refused
  (`reconciliation.RecordAlreadyMatched`).
- **Terminal:** `RESOLVED`, `REPUDIATED`. `MATCHED`, `CHECKED` and `OFFSET` are final unless their
  batch is repudiated — or, for a bank item's `MATCHED`, the batch whose remittance it matched.

**Every decision is on the record** (`INV-REC-04`, `INV-REC-07`). Each evaluation writes a
`match_decision` — origin (`RUN`, `REMATCH`, `REPROCESS`, `MANUAL`), `rule_set_id NOT NULL`, the
rule's priority, the strategy, the matched key kind, the outcome, the claimant's rank and count,
the date deviation and the applied tolerances — and one `match_candidate` per candidate
considered, with its remainder before. Allocations reference their decision and are append-only
(`UNIQUE (external_item_id, expectation_id) WHERE reverses_allocation_id IS NULL`, deferred Σ
triggers on both sides, no `UPDATE` or `DELETE` grant). A decision is a pure function of its
stored candidate snapshot and its pinned rule set, so **decision replay** is exact; a rule-set
version governs only forward decisions and never alters a committed allocation, park or break.

### 5.5 Settlement expectation (`reconciliation.expectation`)

```
OPEN ──allocation──> PARTIALLY_SETTLED ──allocation──> SETTLED
  └────────allocation of the whole amount──────────────^
OPEN or PARTIALLY_SETTLED ──an approved closing resolution──> RESOLVED_BY_ADJUSTMENT
SETTLED or PARTIALLY_SETTLED ──a repudiation's counter-allocation──> OPEN or PARTIALLY_SETTLED
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `OPEN` | The completing transaction through the port (`payments`' appliers, `merchant`'s payout outcomes, the payout-return worker); report acceptance (`REMITTANCE`); the opening-position backfill (a person with `RECONCILIATION_ADMINISTER`, keyed) | `UNIQUE (kind, operation_ref)` and `UNIQUE (journal_entry_id, ledger_account_id)`, `ON CONFLICT DO NOTHING`; keys `UNIQUE (source_id, key_kind, key_value)`, a colliding key skipped and recorded as `expectation_event(KEY_COLLISION)`; `expected_by` = posting date + `lag_days[kind]`, pinned with `rule_set_id`; `CHECK ((kind = 'REMITTANCE') = (journal_entry_id IS NULL))` |
| `OPEN → PARTIALLY_SETTLED` | The run, rematch, reprocess and `MANUAL_MATCH` legs, through `allocate(E)` | An allocation less than the remainder |
| `OPEN → SETTLED` | The same | An allocation of the whole amount. Publishes `reconciliation.SettlementExpectationSettled` |
| `PARTIALLY_SETTLED → SETTLED` | The same | An allocation equal to the remainder. Publishes the same |
| `OPEN` or `PARTIALLY_SETTLED` → `RESOLVED_BY_ADJUSTMENT` | The approval of a closing resolution (`WRITE_OFF`, `TRANSFER_TO_ACCOUNT`), or the batch's `REPUDIATE_BATCH` approval, no posting (its own `REMITTANCE`, §5.2) | `resolved_minor` takes the remainder |
| `SETTLED` → `OPEN` or `PARTIALLY_SETTLED`; `PARTIALLY_SETTLED → OPEN` | The approval of a `REPUDIATE_BATCH` | Append-only counter-allocations restore the remainder (`INV-REV-01`'s shape applied to matches) |

- `allocated_minor + resolved_minor ≤ amount_minor` (`CHECK`); `SETTLED` holds exactly when the
  allocations equal the amount; a deferred Σ trigger holds the allocations equal to
  `allocated_minor`.
- **`overdue_since` is a fact, not a state**: a one-way `NULL → value` set by the ageing sweep
  when nothing is allocated past `expected_by` plus the pinned `SETTLEMENT_DATE_DAYS`, judged on
  the database clock; it raises `MISSING_EXTERNAL` and publishes
  `reconciliation.SettlementExpectationOverdue`. An overdue expectation still settles: a late line
  allocates like any other, and the break resolves `EVIDENCED` with its timing recorded — nothing
  is refused as stale (`INV-SET-03`).
- **Invalid:** any edge out of `RESOLVED_BY_ADJUSTMENT`; an allocation beyond the remainder —
  refused at the domain, by `CHECK` and by the deferred Σ trigger; `SETTLED →
  RESOLVED_BY_ADJUSTMENT` — nothing remains to resolve.
- **Terminal:** `RESOLVED_BY_ADJUSTMENT`. `SETTLED` is final but for a repudiation's reopening;
  `SettlementExpectationSettled` names that terminal-unless-repudiated fact. A line arriving after
  its expectation was written off finds it `RESOLVED_BY_ADJUSTMENT`, stays unallocated and parks
  as a recovery (`DUPLICATE_EXTERNAL`), closed four-eyes by `RECOGNISE_GAIN` (a CREDIT item, after
  the minimum age) or `TRANSFER_TO_ACCOUNT` — an offset against the loss is not possible; reversing
  a write-off automatically is deferred.

### 5.6 Break (`reconciliation.break`)

```
OPEN ──assign──> INVESTIGATING ──propose──> RESOLUTION_PROPOSED ──approve──> RESOLVED
  │                    ^                          │   ^                          ^
  │                    └───reject / withdraw──────┘   │                          │
  └──────────────────────────propose──────────────────┘                          │
OPEN, INVESTIGATING or RESOLUTION_PROPOSED ──EVIDENCED (the platform only)───────┤
OPEN or INVESTIGATING ──a zero-value ACKNOWLEDGE (one person)────────────────────┘
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `OPEN` | `BreakRegister.raise`, by the platform, in the transaction that detects it: a run chunk, the grace or rematch leg, the ageing sweep, the key-collision sweep, bank recognition, an unmatched confirmation through the port, a run block, a diverged replay | One open break per (type, subject) (§6); severity computed at raise. Publishes `reconciliation.ReconciliationBreakRaised` |
| `OPEN → INVESTIGATING` | The first assignment (`RECONCILIATION_INVESTIGATE`) | Serialised on the break row. Publishes `reconciliation.BreakInvestigationStarted` |
| `OPEN` or `INVESTIGATING` → `RESOLUTION_PROPOSED` | A proposal (`RECONCILIATION_RESOLVE`, keyed per principal) | The kind allowed for the type (`reconciliation.ResolutionKindNotAllowed`), the reason code allowed for the kind (`reconciliation.ReasonCodeNotAllowed`), no other live proposal (`reconciliation.ResolutionAlreadyProposed`) |
| `RESOLUTION_PROPOSED → INVESTIGATING` | A rejection (another `RECONCILIATION_RESOLVE` holder, reasoned); a withdrawal by the proposer — the proposer's path after a stale refusal | — |
| `RESOLUTION_PROPOSED → RESOLVED` | The approval (§5.7) | Four-eyes where required; the residual version and the subject's remainder unchanged since the proposal |
| `OPEN`, `INVESTIGATING` or `RESOLUTION_PROPOSED` → `RESOLVED` | `EVIDENCED`, by the platform only | A zero-residual allocation or offset explains the break. A pending proposal is withdrawn in the same transaction — its resolution `WITHDRAWN` by the platform, its ledger proposal rejected through `AdjustmentService.rejectOwned` |
| `OPEN` or `INVESTIGATING` → `RESOLVED` | A single-person `ACKNOWLEDGE` of zero value | The type admits `ACKNOWLEDGE` and the value at issue is 0 |

- **Appended events, not states:** a reclassification — type and hence severity — in `OPEN` or
  `INVESTIGATING` only, with a reason; severity escalations from ageing, forward only;
  reassignment; notes (append-only, 1..4000 characters, refused when they hold a Luhn-valid
  13–19-digit run or an IBAN shape); evidence links; `residual_version`, which bumps on every
  allocation, park, release or reclassification touching the break's subject.
- **Invalid:** anything out of `RESOLVED` (`reconciliation.BreakTerminal`); any `DELETE` — no
  grant, and a refusing trigger; `RESOLVED` by a person without an approved resolution;
  `EVIDENCED` by a person; `RESOLVED` while the residual is non-zero, `ACKNOWLEDGE` of the types it
  applies to excepted; a reclassification in `RESOLUTION_PROPOSED`.
- **Terminal:** `RESOLVED`. A recurrence is a new break with `follows_break_id`.
- **The case file.** The break references its expectation, item or suspense item, each of which
  reaches the settlement line, file, batch and raw content; the payments or merchant record, its
  journal entry and `payments.provider_evidence` — a parked confirmation's raw statement by its
  stored `unmatched_confirmation_id`, the fifth subject payments `V023` added; and the match
  decision with its candidates —
  walked by identifiers alone, with no timestamp join
  (`GET /v1/operator/reconciliation/breaks/{id}/trace`, `INV-REC-01`).

### 5.7 Resolution (`reconciliation.resolution`)

```
PROPOSED ──approve (a second person when four-eyes)──> APPROVED
    ├────reject (another RESOLVE holder, reasoned)───> REJECTED
    └────withdraw (the proposer; the platform on evidence)──> WITHDRAWN

born APPROVED: EVIDENCED (the platform) · a zero-value ACKNOWLEDGE (one person)
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `PROPOSED` | A person with `RECONCILIATION_RESOLVE`, keyed under `reconciliation.resolve:<actorType>:<actorId>` | At most one `PROPOSED` per subject (partial unique; per batch for repudiation). Freezes `proposed_amount`, the break's `residual_version` and the ledger proposal's lines (`AdjustmentService.proposeOwned`, origin `RECONCILIATION`, a reason code, dated `proposed_on`); the break moves to `RESOLUTION_PROPOSED` in the same transaction |
| (birth) → `APPROVED` | The platform (`EVIDENCED`); one person (a zero-value `ACKNOWLEDGE`) | `CHECK (kind = 'EVIDENCED' ⇒ proposed_by = system AND status = 'APPROVED')` |
| `PROPOSED → APPROVED` | Another `RECONCILIATION_RESOLVE` holder where four-eyes | Locks in DISTRIBUTED_EXECUTION §3's Phase 8 order: the source's advisory namespace `4` when the approval allocates, parks or unparks (`MANUAL_MATCH`, `REPUDIATE_BATCH`); the break, then the resolution; the subject rows — expectations, then external items, then suspense items, each sorted by id; the transfer target's ACCOUNT row `FOR SHARE`, before any projection row; inside `approveOwned`, the ledger proposal row, then the projection rows in the projection's own order — and an approval posting several entries over shared rows pre-locks the union of the platform's rows it will touch in the projection's own order before its first posting (`PostingService.lockBalancesInOrder`, DISTRIBUTED_EXECUTION §3's multi-entry lock-order rule), never resting on seed order *(the transition's consistency review, B9)*; re-derives the lines; the residual version and the remainder unchanged (`409 reconciliation.ResolutionStale` otherwise, and the resolution stays `PROPOSED`); `approveOwned` posts the `ADJUSTMENT`, or `ReversalService` reverses for `REPUDIATE_BATCH`; releases, the expectation or item terminal, the break `RESOLVED`, `reconciliation.BreakResolved` — one transaction |
| `PROPOSED → REJECTED` | Another `RECONCILIATION_RESOLVE` holder, reasoned | `rejectOwned`; the break returns to `INVESTIGATING` |
| `PROPOSED → WITHDRAWN` | The proposer; the platform when `EVIDENCED` arrives | `rejectOwned`; the break returns to `INVESTIGATING`, or resolves `EVIDENCED` |

- **The four-eyes threshold, defined** (`INV-REC-03`, `INV-AUD-04`): every resolution **with a
  value at issue or a posting** is four-eyes; a zero-value, zero-posting `ACKNOWLEDGE` is
  single-person; `EVIDENCED` is the platform's. `AdjustmentService`'s unconditional four-eyes rule
  is kept, and a value-banded second approver is deferred.
- **Person-distinctness at three ranks:** the reconciliation domain
  (`reconciliation.SelfApprovalRefused`); `CHECK (status <> 'APPROVED' OR NOT four_eyes OR
  decided_by <> proposed_by)`; ledger `V010`'s approver-is-not-initiator `CHECK` and its deferred
  `adjustment_entry_is_approved` beneath. For a posting kind, `APPROVED` ⇔ the ledger proposal
  `APPROVED` ⇔ its entry exists, in one transaction and at database rank (`UNIQUE
  adjustment_proposal_id`, `UNIQUE journal_entry_id`).
- **Reason codes** — the closed `ResolutionReasonCode`, an allowed subset per kind, a narrative of
  1..1000 characters: `COUNTERPARTY_ERROR_CONFIRMED`, `INTERNAL_PROCESSING_ERROR`,
  `DUPLICATE_BY_COUNTERPARTY`, `FUNDS_ATTRIBUTED`, `UNATTRIBUTABLE_AGED`, `TIMING_CONFIRMED`,
  `FEE_ACCEPTED_AS_CHARGED`, `FEE_RECOVERED`, `AMBIGUITY_RESOLVED_BY_EVIDENCE`,
  `IMMATERIAL_DIFFERENCE`, `LOSS_ACCEPTED`, `EVIDENCE_REPUDIATED`, and `EVIDENCE_RECEIVED`
  (the platform's only). Each kind admits exactly this subset — `ResolutionKind.admittedReasonCodes()`,
  generated into `V007`'s pairing `CHECK` and pinned per kind by `ReconciliationV007MigrationTest`
  (`P8-TSK-015`; changing one is a reviewed code change):

  | Kind | Admitted reason codes |
  |---|---|
  | `EVIDENCED` | `EVIDENCE_RECEIVED` — no other kind admits it |
  | `ACKNOWLEDGE` | `TIMING_CONFIRMED`, `FEE_ACCEPTED_AS_CHARGED`, `FEE_RECOVERED`, `IMMATERIAL_DIFFERENCE`, `INTERNAL_PROCESSING_ERROR`, `COUNTERPARTY_ERROR_CONFIRMED` |
  | `WRITE_OFF` | `LOSS_ACCEPTED`, `IMMATERIAL_DIFFERENCE`, `COUNTERPARTY_ERROR_CONFIRMED`, `INTERNAL_PROCESSING_ERROR`, `UNATTRIBUTABLE_AGED` |
  | `TRANSFER_TO_ACCOUNT` | `FUNDS_ATTRIBUTED`, `INTERNAL_PROCESSING_ERROR`, `COUNTERPARTY_ERROR_CONFIRMED` |
  | `OFFSET_SUSPENSE` | `DUPLICATE_BY_COUNTERPARTY`, `COUNTERPARTY_ERROR_CONFIRMED`, `INTERNAL_PROCESSING_ERROR` |
  | `RECOGNISE_GAIN` | `UNATTRIBUTABLE_AGED` |
  | `MANUAL_MATCH` | `AMBIGUITY_RESOLVED_BY_EVIDENCE` |
  | `REPUDIATE_BATCH` | `EVIDENCE_REPUDIATED` (`V012`, `P8-TSK-023`) |

- **As built (`P8-TSK-015`).** Every resolution command — propose, approve, reject, withdraw —
  takes the break's source advisory first (both sources, sorted, for an offset spanning two),
  then the break rows sorted by id, then the resolution row. Withdrawal is the proposer's alone
  (`reconciliation.NotTheProposer`); a rejection is never the proposer's
  (`reconciliation.SelfApprovalRefused`); the same person's retry of any decision converges. The
  proposal, rejection and withdrawal edges are recorded in `resolution_event`; the break's own
  history records the terminal edge naming the resolution. **Remainder siblings:** an
  `AMOUNT_MISMATCH` and a `MISSING_EXTERNAL` on one expectation answer for the same remainder, so
  one live proposal stands across them and the approval that disposes of the remainder closes
  both (each `break_event` naming the resolution). A manual match allocates only when the chosen
  stored candidate absorbs the whole parked value, and names its decision, park and entry on the
  resolution; an approved closing resolution appends the expectation's `RESOLVED` event.
- **Invalid:** self-approval, at all three ranks; approving anything not `PROPOSED`
  (`reconciliation.ResolutionNotPending`); approving or rejecting a `RECONCILIATION`-origin
  proposal through `/v1/ledger/adjustments` (`409 ledger.AdjustmentOriginMismatch`) — and
  `approveOwned`/`rejectOwned` refuse `MANUAL` ones; a transfer to an account not `ACTIVE` or in
  another currency (`reconciliation.ResolutionTargetRefused`, ledger `V007` beneath) — a closed
  merchant's payable among them, since merchant close closes the payable's ledger account (the
  Phase 7 → 8 transition's repair); a
  `RECOGNISE_GAIN` before the minimum age (`reconciliation.GainNotYetEligible`); any edge out of a
  terminal.
- **Terminal:** `APPROVED`, `REJECTED`, `WITHDRAWN`.
- **Two disjoint roles** hold these permissions (transition decision O1, §8):
  `RECONCILIATION_OPERATOR` = {`SETTLEMENT_INGEST`, `RECONCILIATION_INVESTIGATE`,
  `RECONCILIATION_RESOLVE`}, `RECONCILIATION_CONTROLLER` = {`RECONCILIATION_ADMINISTER`} — whoever
  can loosen a tolerance cannot resolve the breaks it would hide. `LEDGER_OPERATOR` is not
  extended: the desk that moves money does not reconcile it.

### 5.8 Suspense item (`reconciliation.suspense_item`)

```
OPEN ──partial release──> PARTIALLY_RELEASED ──release of the rest──> RELEASED
  └───────────────────────release of the whole──────────────────────────^
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `OPEN` | Only in the transaction that records its owning break, by one of four openers and no fifth: a park (origin `RECON_PARK` — CREDIT for an INBOUND remainder, DEBIT for an OUTBOUND one); bank recognition's unattributed line (`BANK_UNATTRIBUTED`, cause `BANK_LINE_UNATTRIBUTED`); an unmatched confirmation through the port (`UNMATCHED_CONFIRMATION`, cause `PARKED_ON_RECEIPT`), or the backfill adopting one; a repudiation (`REPUDIATION`), in the `REPUDIATE_BATCH` approval transaction, for a `BANK_UNATTRIBUTED` item a posting resolution had already released — its line is the suspense line the recognition's reversal (`ReversalService`, scope `ledger.reverse`, key `settlement-batch:<batchId>`) carries for that item, so the new item is of the opposite side, owned by a new `PROCESSING_ERROR` break raised in the same transaction, `origin_ref` the released item's id and `opened_on` the reversal entry's posting date; the origin admitted by reconciliation `V011` (`P8-TSK-023`) *(the Phase 7 → 8 transition's re-check, R3)* | `break_id NOT NULL`; `UNIQUE (external_item_id)`; `UNIQUE origin_ref` — an item is repudiated once, so a `REPUDIATION` item's holds too |
| `OPEN → PARTIALLY_RELEASED` | A release: an unpark (a late allocation), an approved resolution, a correction offset (`CORRECTION_OFFSET`), a repudiation | Part of the amount; the item locked `FOR UPDATE` |
| `OPEN` or `PARTIALLY_RELEASED` → `RELEASED` | The same | `released_minor` reaches `amount_minor` |

- `released_minor ≤ amount_minor` (`CHECK`); each release is a `suspense_release` row (amount,
  park, cause, cause reference), the item's history.
- **Invalid:** a birth without a break; a release beyond the amount; anything out of `RELEASED`.
- **Terminal:** `RELEASED`.
- **Never permanent** (`INV-REC-05`, ADR-0070): a CREDIT item leaves by attribution
  (`TRANSFER_TO_ACCOUNT`), an offset, evidence, or `RECOGNISE_GAIN` once older than the pinned
  minimum age — 90 days, four-eyes (transition decision O5, §8) — where its break's type admits the
  gain (§6: never `REVERSAL_MISMATCH`, `REFUND_MISMATCH` or `CURRENCY_MISMATCH`); a DEBIT item by
  evidence attributing it to its counterparty, an offset, or `WRITE_OFF`, at any age, which every
  type owning a suspense item admits. Age runs from `opened_on`:
  `finapp.reconciliation.suspense.open`, `.age` (the oldest, NaN never zero) and `.unowned`, which
  must read 0. The suspense report, audited, carries the amounts — CREDIT and DEBIT items gross,
  never netted.
- Phase 7's `finapp.payments.unmatched.active` and `.age` count every row ever parked; their
  descriptions are corrected to "parked, ever", and the alertable signal becomes
  `finapp.reconciliation.suspense.*` (`P8-TSK-020`).

### 5.9 Rule set (`reconciliation.rule_set`)

```
PROPOSED ──activate (a second RECONCILIATION_ADMINISTER holder)──> ACTIVE ──a successor activated──> RETIRED
    └──reject──> REJECTED
(version 1 of each source is seeded ACTIVE by its migration)
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `ACTIVE` | Reconciliation `V002` only — version 1 per source, with the migration as provenance (the payments `V013` routing precedent) | Seeded literals |
| (birth) → `PROPOSED` | A person with `RECONCILIATION_ADMINISTER`, keyed and reasoned | `UNIQUE (source_id, version)`; no amount tolerance can be written (`reconciliation.ToleranceNotPermitted`) |
| `PROPOSED → ACTIVE` | A **different** person with `RECONCILIATION_ADMINISTER` | `CHECK decided_by <> proposed_by` when `ACTIVE` (`reconciliation.RuleSetActivationBySameActor`); the prior `ACTIVE → RETIRED` **in the activating transaction**, so exactly one version per source is active (partial `UNIQUE (source_id) WHERE status = 'ACTIVE'`) |
| `PROPOSED → REJECTED` | A person with `RECONCILIATION_ADMINISTER`, reasoned | — |
| `ACTIVE → RETIRED` | Its successor's activation | Same transaction |

- **Content is frozen from `PROPOSED`** by trigger: the rules, tolerances, `provider_fee_schedule`
  (`rate numeric(7,6)`, fixed amount, rounding policy — `INV-MON-03`), `severity_threshold`
  (`high_value_minor`, seeded at 1,000.00 per currency — transition decision O7, §8), the lag days
  per kind (card 3, refund and dispute 3, instant 1, payout 2), `funding_lag_days` (2) and
  `gain_min_age_days` (90).
- **Invalid:** a content edit after birth; activating or rejecting anything not `PROPOSED`
  (`reconciliation.RuleSetNotPending`); self-activation; any edge back to `PROPOSED`; anything out
  of `RETIRED` or `REJECTED` — returning to older rules is a new version.
- **Terminal:** `RETIRED`, `REJECTED`.
- Every run, decision, allocation, break and expectation pins `rule_set_id`. A new version governs
  only new runs, rematches and explicit `REPROCESS` runs; every earlier decision still replays
  `IDENTICAL` under the version it pinned.
- Version 1 of the payout source already declares its `PAYOUT_RETURNED` rule operation-anchored
  (§5.10), so payout returns need no second version and no four-eyes activation *(the Phase 7 → 8
  transition's consistency review, A4)*.

### 5.10 Payout return (`merchant.payout_return`) — a fact, not a machine

Born once, `RECORDED`, with its posting and its expectation in the same transaction; no edges
(ADR-0073, closing ADR-0057's follow-up). **The matcher never key-matches a `PAYOUT_RETURNED`
line**: rule set v1 — seeded by `P8-TSK-004` and frozen — declares the payout source's
`PAYOUT_RETURNED` rule **operation-anchored**, so the line waits `UNMATCHED`, with no break raised
by the matcher, instead of meeting its own payout's OUTBOUND `MERCHANT_PAYOUT` expectation as a
direction mismatch (§5.4). `app`'s `PayoutReturnSchedule`, leaderless, reads reconciliation's
`UNMATCHED` `PAYOUT_RETURNED` items in claimant order and, **in each item's own transaction**:

1. re-reads the item under a share lock, and proceeds only while it is still `UNMATCHED`;
2. calls `merchant.PayoutReturns.apply`, which in order:
   1. locks the payout row `FOR UPDATE`, found through the payout's stored provider reference
      (`PAYOUT_PROVIDER_REF`, set exactly when the payout is `COMPLETED`), then by `OUR_REF`
      (`pyo-…`);
   2. checks it is `COMPLETED`, has no return, matches in amount and currency, and that the
      payable is `ACTIVE`, read `FOR SHARE` before any posting;
   3. posts `merchant-payout-return:<payoutId>`, DR `PAYOUT_CLEARING` / CR `MERCHANT_PAYABLE`, dated
      the item's stored batch `accepted_on` with the item's settlement date as value date;
   4. inserts the `payout_return` with that entry's id (`UNIQUE (payout_id)`; its money bound equal
      to the payout's by a composite foreign key onto the payout's `(id, amount_minor, currency,
      scale)` — a `CHECK` cannot read another row);
   5. opens a `PAYOUT_RETURN` INBOUND expectation through the port — **with no key of its own**: the
      line's references are the payout's own keys, held by its `MERCHANT_PAYOUT` expectation under
      the per-source key unique, and a key opened for the return would be a `KEY_COLLISION` and a
      false `DUPLICATE_INTERNAL`; publishes `merchant.MerchantPayoutReturned`.

*(The Phase 7 → 8 transition's consistency review, A4 and A6: the steps inserted before they
posted — impossible for an append-only row that stores the entry id — and "`CHECK`ed" the money;
the worker read the item unlocked; and how the line reached the return's expectation was unstated,
while ADR-0067 and ADR-0073 spoke of return-qualified key kinds.)* *(Built by `P8-TSK-019`:
step 2's checks are typed outcomes that write nothing — `NO_PAYOUT`, `PAYOUT_NOT_COMPLETED`,
`ALREADY_RETURNED`, `AMOUNT_DIFFERS`, `PAYABLE_NOT_POSTABLE` — and `V008`'s insert trigger
refuses a return naming a payout that is not `COMPLETED`, the second rank.)*

The rematch leg then allocates the item by the **operation-anchored lookup**: the line's
`PAYOUT_PROVIDER_REF`, then its `OUR_REF`, names the payout's operation through its
`MERCHANT_PAYOUT` expectation's key, and the item is allocated to **that operation's
`PAYOUT_RETURN` expectation**, reached by `UNIQUE (kind, operation_ref)` — never to the payout's own.
The worker allocates nothing. **The payout stays `COMPLETED`** — merchant `V007` makes it terminal,
and a return is a new operation (`INV-LIFE-04`, ADR-0062's argument for returns applied to payouts).

**Ten instances.** The item is judged on its locked row: if the worker's share lock comes first, the
grace leg waits and then finds the committed return's expectation as a candidate, so it allocates
rather than parks; if the grace leg's lock comes first, the item parks, and the worker, re-reading
under its share lock, finds it no longer `UNMATCHED` and writes nothing — it never applies a
parked or resolved item. That race and its test, both ways, are `P8-TSK-013`'s (the grace leg
judging on its locked row) and `P8-TSK-019`'s. The payout row, `UNIQUE (payout_id)` and the posting
key decide ten workers on one item. The lock order is DISTRIBUTED_EXECUTION §3's: the item row
(rank 3), the payout row (rank 4, the return worker only), the payable's ACCOUNT row `FOR SHARE`
(rank 5) — the runtime counterparty's row before any projection row — then the ledger projection
rows in the projection's own order (rank 6). Seed order is no part of it: a transaction posting
several entries over shared rows pre-locks the union of the platform's rows it will touch in the
projection's own order before its first posting (`PostingService.lockBalancesInOrder`,
DISTRIBUTED_EXECUTION §3's multi-entry lock-order rule).

If any check fails, nothing is written; the item's grace expires into `REVERSAL_MISMATCH` (cause
`RETURN_NOT_APPLICABLE`), resolved by a four-eyes `TRANSFER_TO_ACCOUNT` — the manual fallback
(transition decision O2, §8). **A return to a closed merchant** is the case that fallback cannot
finish: merchant close — since the Phase 7 → 8 transition's repair — refuses while a chargeback can
still be won and closes the payable's ledger account, so both the return's check and a
`TRANSFER_TO_ACCOUNT` to that payable are refused. The CREDIT item rests in suspense, owned by its
break, aged and alerting, until a person transfers it to an account that can take it; returning it
to the merchant outside the platform is the deferred return-to-sender (ADR-0073, recorded for the
Phase 8 review to route). The worker against a merchant close is decided by the payable `FOR
SHARE` against the close's lock.

`MerchantPayable` gains `payoutsReturned` — a payable CREDIT in the return's `POSTING`, which debits
`PAYOUT_CLEARING` — with this task, and `INV-MER-02`'s statement gains "plus payouts returned";
`reconciliationAttributed`, the customer statement label and the "plus or minus reconciliation
attributions" term are `P8-TSK-015`'s (§3.6). *(The Phase 7 → 8 transition's consistency review,
A12.)*

### 5.11 The three layers, per machine

| Machine | Table (migration, task) | History | Database rank beyond the edge trigger |
|---|---|---|---|
| Settlement file | `settlement.file` (`V002`, `P8-TSK-002`; states widened by `V003` and `V004`) | `file_event`; a `file_receipt` per delivery | Content unique; the attestation `CHECK`s, and the readmission's attestation trigger (`P8-TSK-022`'s own settlement migration); status ↔ columns `CHECK`s; `UPDATE` narrowed to the status, rejection, parse-retry and attestation columns |
| Settlement batch | `settlement.batch` (`V003`, `P8-TSK-008`; acceptance `V004`, `P8-TSK-009`; `REPUDIATED` with `P8-TSK-023`) | `batch_event` | The live uniques; `UNIQUE (source_id, source_sequence)`; once-only acceptance columns |
| Reconciliation batch | `reconciliation.reconciliation_batch` (`V003`, `P8-TSK-009`) | `reconciliation_batch_event` | `UNIQUE (batch_id)`; the per-source sequence unique; one open `REPROCESS` per source; the deferred no-`PENDING` trigger |
| External item | `reconciliation.external_item` (`V003`, `P8-TSK-009`; `REPUDIATED` and the reopening `MATCHED → UNMATCHED` by `V012`, `P8-TSK-023`) | `external_item_event` | `UNIQUE settlement_line_id`; the amounts `CHECK`; disposition columns only |
| Settlement expectation | `reconciliation.expectation` (`V002`, `P8-TSK-004`, with the announced-cycle attribute column; the reopening edges by `V012`, `P8-TSK-023`) | `expectation_event` (`KEY_COLLISION` among its kinds) | The two uniques; the `REMITTANCE` `CHECK`; `allocated + resolved ≤ amount`; the deferred Σ trigger |
| Break | `reconciliation.break` (`V004`, `P8-TSK-010`) | `break_event`, `break_note`, `break_evidence_link` | The one-open-per-(type, subject) partial uniques; no `DELETE` grant and a refusing trigger; forward-only severity |
| Resolution | `reconciliation.resolution` (`V006`, `P8-TSK-012` for `EVIDENCED`; the person kinds `P8-TSK-015`; the `REPUDIATE_BATCH` kind and its batch subject by `V012`, `P8-TSK-023`) | `resolution_event` | One `PROPOSED` per subject; the four-eyes `CHECK`; the `EVIDENCED` `CHECK`; the proposal and entry uniques; decision columns once |
| Suspense item | `reconciliation.suspense_item` (`V004`, `P8-TSK-010`; origin `REPUDIATION` by `V012`, `P8-TSK-023`) | `suspense_release` — every edge is a release | `break_id NOT NULL`; `released ≤ amount`; the origin uniques |
| Rule set | `reconciliation.rule_set` (`V002`, `P8-TSK-004`) | `rule_set_event` *(the three-layer rule requires it; the transition's data model names only the row's decision columns — `P8-TSK-004` adds it)* | Content frozen from `PROPOSED`; one `ACTIVE` per source; activator ≠ proposer |
| Payout return | `merchant.payout_return` (`V008`, `P8-TSK-019`) | The row itself — born once | `UNIQUE (payout_id)`; the composite foreign key binding its money to the payout's `(id, amount_minor, currency, scale)`; `UNIQUE journal_entry_id`; append-only |

Each state arrives with its producer's task, so a deferral removes states rather than stranding
them (transition decision O6, §8): without `P8-TSK-023` there is no `REPUDIATED` on the batch or
the item, no reopening edge on the item or the expectation, no `REPUDIATION` suspense origin and
no `REPUDIATE_BATCH` kind — its
reconciliation `V012`, numbered after `P8-TSK-022`'s `run_replay` in `V011` (and `P8-TSK-016`'s bank items in `V008`, `P8-TSK-017`'s scheme items in `V009`, `P8-TSK-018`'s payout items in `V010`), is what admits them;
without `P8-TSK-019` no `payout_return` exists, and a returned payout takes the four-eyes
`TRANSFER_TO_ACCOUNT` path. *(The Phase 7 → 8 transition's consistency review, A6 and A8.)*

## 6. The breaks (ADR-0069)

Fourteen types, each detectable with a counted test, classified, severity-tagged, aged and never
deleted.

**The allowed resolutions are ADR-0069's per-type table, the one authority**, carried here
identically (and in `PHASE_8_PLAN.md` §12.6 and [`RECONCILIATION_MODEL.md`](RECONCILIATION_MODEL.md)
§8). Every type that owns a suspense item admits `WRITE_OFF` for a DEBIT item, at any age, and
`RECOGNISE_GAIN` for a CREDIT item once past the pinned minimum age — both four-eyes (ADR-0071) —
except where the table names an exclusion and its reason. A kind is admissible only where its
lines find their side (§3.6).

| Type | Detected by | Subject | Parked? | Base severity | Allowed resolutions |
|---|---|---|---|---|---|
| `MISSING_EXTERNAL` | The ageing sweep: nothing allocated past `expected_by` plus `SETTLEMENT_DATE_DAYS` | expectation | No — it stays in the position | MEDIUM; HIGH for `MERCHANT_PAYOUT` and `REMITTANCE` | `EVIDENCED` (late), `WRITE_OFF` (INBOUND), `TRANSFER_TO_ACCOUNT` (OUTBOUND) |
| `MISSING_INTERNAL` | The grace leg: the operation is known but not completed | item | Yes | HIGH | `EVIDENCED` (it completes; the rematch unparks), `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT item, any age), `RECOGNISE_GAIN` (CREDIT item, after the minimum age) |
| `UNKNOWN_EXTERNAL` | The grace leg; an unattributed bank line; an unmatched confirmation (`PARKED_ON_RECEIPT`) | item or suspense item | Yes | HIGH; CRITICAL when OUTBOUND | As `MISSING_INTERNAL` |
| `AMOUNT_MISMATCH` | `ONE_TO_ONE` with a different amount; a dispute-fee line unequal to its expectation | expectation (under) or item (over) | The excess only | HIGH | `EVIDENCED` (a correction fills or offsets it), `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (an INBOUND remainder, or a DEBIT item at any age), `RECOGNISE_GAIN` (CREDIT item, after the minimum age) |
| `CURRENCY_MISMATCH` | A key hit in another currency — never converted (`INV-MON-04`; FX is Phase 9) | item | Yes | HIGH | `EVIDENCED` (a counterparty correction offsets it), `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT item, any age); **no `RECOGNISE_GAIN`** — a currency break is never income: it is resolved by `EVIDENCED` or `TRANSFER_TO_ACCOUNT` |
| `FEE_MISMATCH` | The fee check beyond its tolerance: \|reported − expected\| above it, the expected fee recomputed under the pinned schedule | item | No — the reported fee is already expensed | MEDIUM | `ACKNOWLEDGE` (four-eyes) |
| `DUPLICATE_EXTERNAL` | The expectation already fully allocated; a repeated fingerprint; a line after its expectation was written off (a recovery) | item | Yes | HIGH | `EVIDENCED` (a claw-back correction offsets it), `OFFSET_SUSPENSE`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT item, any age), `RECOGNISE_GAIN` (a recovery: CREDIT item, after the minimum age) |
| `DUPLICATE_INTERNAL` | A key collision recorded at opening; an investigator's reclassification | expectation | No | HIGH | `ACKNOWLEDGE`, `WRITE_OFF` |
| `AMBIGUOUS_MATCH` | Two or more candidates | item | Yes | MEDIUM | `MANUAL_MATCH`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT item, any age), `RECOGNISE_GAIN` (CREDIT item, after the minimum age) |
| `TIMING_DIFFERENCE` | A match later than `expected_by` plus `SETTLEMENT_DATE_DAYS`; a cycle other than the one announced | decision | No — value 0 | LOW | `ACKNOWLEDGE` (one person) |
| `REVERSAL_MISMATCH` | A direction contradicting the record; a capture on a `VOIDED` or `FAILED` attempt; a reversal without `WON`; a `PAYOUT_RETURNED` that cannot apply (`RETURN_NOT_APPLICABLE`) | item | Yes | HIGH | `EVIDENCED` (a counterparty correction offsets it), `TRANSFER_TO_ACCOUNT` (for example, re-crediting the payable), `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT item, any age); **no `RECOGNISE_GAIN`** — the value belongs to a counterparty (a merchant or a customer) and is resolved by `TRANSFER_TO_ACCOUNT` or `EVIDENCED`, never taken as the platform's gain |
| `REFUND_MISMATCH` | A `REFUND` line against a refund that failed internally, or a capture with no such refund | item | Yes | CRITICAL | `EVIDENCED` (a late completion), `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT item, any age); **no `RECOGNISE_GAIN`**, for `REVERSAL_MISMATCH`'s reason |
| `SETTLEMENT_MISMATCH` | `REMITTANCE_DIFFERS` (bank ≠ remittance: a remittance remainder, or an item's excess); `STATEMENT_GAP` (a sequence gap, or an opening unequal to the previous closing); `OPENING_BALANCE` (the first statement opens ≠ 0) | expectation or item; the statement batch, through its run, for the statement causes | Per side | HIGH; CRITICAL for the statement causes | `EVIDENCED` (the gap fills, the funds arrive); for `REMITTANCE_DIFFERS` only, `WRITE_OFF`, `TRANSFER_TO_ACCOUNT` and, for a CREDIT excess item after the minimum age, `RECOGNISE_GAIN`; the statement causes close only `EVIDENCED` |
| `PROCESSING_ERROR` | An errored item; a blocked run (`RUN_BLOCKED`); a diverged replay | item, run or decision | Items: yes | CRITICAL | Reprocess or requeue, then `EVIDENCED`; otherwise, for a parked item, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT item, any age), `RECOGNISE_GAIN` (CREDIT item, after the minimum age) |

*(The Phase 7 → 8 transition's consistency review, A1, A2 and A3: the table now carries ADR-0069's
per-type rule — `WRITE_OFF` and `RECOGNISE_GAIN` on every type owning a suspense item, the three
named exclusions with their reasons, `WRITE_OFF` added to `DUPLICATE_EXTERNAL`, and
`SETTLEMENT_MISMATCH`'s posting kinds confined to `REMITTANCE_DIFFERS`. The statement causes still
name their batch through its run — one run per accepted batch — because a break's subject columns
(`expectation_id`, `external_item_id`, `suspense_item_id`, `run_id`, `decision_id`) carry no batch;
`P8-TSK-016` confirms or adds one.)* *(Confirmed by `P8-TSK-016`: no column is added — the run IS
the batch's, `UNIQUE (batch_id)`, and `break_one_open_per_run` gives one open statement break per
statement. As built, a missing predecessor and a predecessor's closing unequal to the opening are
both `STATEMENT_GAP`, value the opening or the difference; `OPENING_BALANCE` is the first opening
alone. A gap whose missing statement is later accepted, and stitches, closes `EVIDENCED` naming
that statement; a mis-stitched opening has nothing to fill it and waits for `P8-TSK-023`'s
repudiation and correction.)*

**One open break per (type, subject)** — partial uniques `(type, expectation_id)`, `(type,
external_item_id)`, `(type, suspense_item_id)` and `(type, run_id)`, all `WHERE status <>
'RESOLVED'`. Two types may stand open on one subject (a `MISSING_EXTERNAL` beside an
`AMOUNT_MISMATCH`), never two of one type; ten sweepers raising the same break write one row. A
recurrence after resolution is a **new** break naming its predecessor (`follows_break_id`), never
a reopened one. A break whose only subject is a decision (`TIMING_DIFFERENCE`) is raised in that
decision's own transaction, and a decision is written once.

**Severity** is deterministic: the base by type and direction, raised one level per ageing band
crossed (0–2, 3–7, 8–30, over 30 days) and one level when the value at issue reaches the pinned
per-currency `high_value_minor` (seeded 1,000.00 — transition decision O7). It is stored at raise;
each escalation is an appended `break_event`, and the column only moves forward. **Ageing** is
`now() − raised_at` on the database clock; the gauges report the oldest open break per severity,
alerting at CRITICAL over 0 hours, HIGH over 1 day, MEDIUM over 5 days and LOW over 15 days.

**The causes, closed** (`P8-TSK-010`, ADR-0069 §2): `EXPECTATION_OVERDUE` (the ageing sweep); `GRACE_EXPIRED` (the grace leg — the one cause two types share); `PARKED_ON_RECEIPT`; `BANK_LINE_UNATTRIBUTED`; `AMOUNT_DIFFERS`; `CURRENCY_DIFFERS`; `FEE_BEYOND_TOLERANCE`; `EXPECTATION_EXHAUSTED`; `REPEATED_FINGERPRINT`; `KEY_COLLISION`; `MULTIPLE_CANDIDATES`; `LATE_MATCH`; `CYCLE_MISMATCH`; `DIRECTION_CONTRADICTED`; `TERMINAL_STATE_CONTRADICTED`; `RETURN_NOT_APPLICABLE`; `REFUND_CONTRADICTED`; `REMITTANCE_DIFFERS`; `STATEMENT_GAP`; `OPENING_BALANCE`; `ITEM_ERRORED`; `RUN_BLOCKED`; `REPLAY_DIVERGED`; `EVIDENCE_REPUDIATED`. The type—cause pairing binds the RAISE by a generated `BEFORE INSERT` trigger, never a table `CHECK`: a reclassification moves the type while the cause stays frozen, so a life-long pairing would refuse the edge §5.6 allows. An investigator's reclassification raises nothing and so has no cause member.

**Never discarded** (`INV-REC-02`): there is no `DELETE` grant and a refusing trigger; a run
cannot complete with a `PENDING` item; every unallocated remainder either waits in `UNMATCHED`
(counted, aged) or parks with its break in its own transaction; and an `EVIDENCED` resolution —
the platform closing a break because a later zero-residual allocation or offset explains it — is a
stored Resolution naming that decision and posting: recorded, not silent, and the only resolution
no person decides.

**What each discrepancy gets:**

| Situation | At once | The way out |
|---|---|---|
| External value with no attributable internal record | Waits out grace (`UNMATCHED`, counted), then parks with a break | Evidence (a late record → rematch → unpark → `EVIDENCED`), or a resolution |
| A definitive external anomaly — a duplicate, a currency, an excess, an ambiguity, a terminal-state contradiction | Parked with a break at once | Evidence (a correction offset, a manual match), or a resolution |
| External less than internal | The remainder stays on the expectation, with `AMOUNT_MISMATCH` | Evidence (a later correction line), or `WRITE_OFF` |
| Internal never reported | Stays in the position; ages into `MISSING_EXTERNAL` | Evidence (late settlement), or `WRITE_OFF` / `TRANSFER_TO_ACCOUNT` after investigation |
| Bank unequal to the remittance | A remainder on the remittance, or the excess parked; `SETTLEMENT_MISMATCH(REMITTANCE_DIFFERS)` | Evidence (the balance arrives), or a resolution |
| Accepted evidence proven fabricated or mis-normalised | A `PROCESSING_ERROR` break, reclassified | `REPUDIATE_BATCH` (four-eyes), then re-presentation of the genuine file |
| A fee or timing difference accepted | A break, no money | `ACKNOWLEDGE` |

## 7. The proofs — explained, owned, cash, complete

Report-only verifiers in the `TrialBalance` shape: lock-free, "the scrape is the schedule", report
and never repair. Each is computed in `app` in **one** `REPEATABLE READ` transaction on one
connection, composing the ledger's `BalanceDerivation` and line reads with reconciliation's and
settlement's read APIs, folded with `Money` and never a SQL SUM, and published as verdict counts
behind a refresh floor — NaN never zero, no amount in any series (ADR-0072).

**The position proof** (`INV-REC-06`), for each clearing position P and currency c, with
s(INBOUND) = +1 and s(OUTBOUND) = −1:

```
DR−CR(P,c) = Σ over expectations e on (P,c):  s(e) · (amount − allocated − resolved)
           − Σ over allocating items i on (P,c) in PENDING or UNMATCHED:
                                               s(i) · (amount − allocated − parked − offset)
```

Remittance expectations are expectations; fee items are excluded, their effect being in the
recognition entry. The identity holds across every step — before any match, after exact, over and
under matches, after parks, after cash, after resolutions (a `WRITE_OFF` credits P and raises
`resolved`) — as §3.4 shows for one day. Published as `finapp.reconciliation.position.proof`,
failing currencies per purpose, which must read 0.

**The suspense proof:** `CR−DR(SUSPENSE_UNMATCHED, c)` = the CREDIT items' remaining amounts −
the DEBIT items' remaining amounts + the named term for Phase 7's unmatched confirmations not yet
adopted (the parkings no suspense item's `origin_ref` names) — exact before and after adoption,
the term reading 0 once `P8-TSK-020` adopts the parkings. **Suspense ownership:** every suspense item with a remainder
names an existing break; `finapp.reconciliation.suspense.unowned` must read 0 (`INV-REC-09`).

**The cash proof** (`INV-SET-06`): `DR−CR(CASH_AT_BANK, c)` equals the closing balance of the
highest-sequence accepted statement of an unbroken chain for c. A `STATEMENT_GAP` or
`OPENING_BALANCE` break makes the verdict fail loudly (`finapp.reconciliation.cash.proof`) until
it is resolved, and nothing is ever adjusted to fit. The simulated bank opens at zero; a non-zero
first opening raises `SETTLEMENT_MISMATCH(OPENING_BALANCE)` and posts nothing, because the only
honest counter-account is equity, which is Phase 14's (transition decision O4, §8). *As built
(`P8-TSK-016`):* `PositionProof.CashVerdict`, per currency, in the sweep's one `REPEATABLE READ`
snapshot — each bank account's accepted statements must run 1..n with no hole, the first opening at
zero and every opening its predecessor's closing, and `CASH_AT_BANK`'s DR−CR must equal the sum of
the chains' head closings; the verdict is the positions report's cash row and the gauge's 0/1.

**Completeness** (`INV-SET-02` for every writer): every journal line on the three clearings and
`SUSPENSE_UNMATCHED` belongs to an entry reconciliation or settlement knows — an expectation's
`(journal_entry_id, ledger_account_id)`, a suspense item that owns it (`INV-REC-09`), a batch's
`journal_entry_id`, a park's, a resolution's, a repudiation's, or a payout return's.
`finapp.reconciliation.line.unattributed` counts the rest — a raw-SQL poster, a missed opener — and
must read 0. It stands in for a ledger trigger on every clearing line, refused by ADR-0064 and
ADR-0067 as a change to the platform's most-probed posting path; after the opening-position
backfill it covers the pre-Phase-8 history on the clearing purposes, and `SUSPENSE_UNMATCHED`
reaches 0 once `P8-TSK-020` adopts the Phase 7 parkings as suspense items — until then an unmatched
confirmation's suspense line, which no expectation names, truthfully reads as unknown. *(The Phase
7 → 8 transition's consistency review, A7.)*

## 8. The owner's transition decisions stated here

Settled at the Phase 7 → 8 transition (2026-09-28) on the design's recommendations; each is a
transition decision the owner may revisit, recorded with the phase's plan.

| # | Decision | Where it bites |
|---|---|---|
| O1 | Two pairwise-disjoint roles, `RECONCILIATION_OPERATOR` and `RECONCILIATION_CONTROLLER`, rather than one | §5.7 |
| O2 | Payout returns applied automatically by a leaderless worker, with the four-eyes `TRANSFER_TO_ACCOUNT` as the fallback | §5.10 |
| O3 | A delivery bearing card-number or bank-identifier shapes is refused at the door, keeping metadata only, rather than retained verbatim | §5.1 |
| O4 | The simulated bank opens at zero; a non-zero opening is a break, and an equity account waits for Phase 14 | §7 |
| O5 | `RECOGNISE_GAIN` only after a minimum age of 90 days, four-eyes | §5.8, §5.9 |
| O6 | If scope must shrink, cut `P8-TSK-021`, then `P8-TSK-019`, then `P8-TSK-023` — keeping `P8-TSK-023` if possible | §5.11 |
| O7 | The high-value severity threshold is 1,000.00 per currency (EUR, GBP, USD) in rule set v1 | §5.9, §6 |
