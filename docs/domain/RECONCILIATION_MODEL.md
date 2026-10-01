# Reconciliation Model

Reconciliation compares the platform's **internal authoritative records** with **external
financial evidence** — what the PSP, the instant scheme, the payout provider and the settlement
bank say happened — per counterparty, position, currency and item. Every disagreement becomes a
classified, aged break with both sides preserved, and **evidence of a reconciliation break is
never erased** (`INV-REC-01`, `INV-REC-02`).

*Rewritten from a 27-line stub by the Phase 7 → 8 transition (2026-09-28), the `LEDGER_MODEL.md`
and `PAYMENT_LIFECYCLES.md` precedent: the document that names a phase's model is written before
the phase's first task, from the decisions in ADR-0064…0073, and corrected by the tasks that build
it. The stub's list — its four pairs, its fourteen engine capabilities and its one rule — is
retained as the core: §15 maps every item to where it now lives. Until Phase 8's first task
lands, nothing in this document is implemented; every statement is the decided design, corrected
by the tasks that build it.*

Related: [the ADR index](../adr/README.md) — ADR-0064 (settlement holds the evidence,
reconciliation the expectations and the comparison) · ADR-0065 (two evidence hops; cash only on
the bank's statement) · ADR-0066 (raw files screened, authenticated, encrypted) · ADR-0067 (every
settling completion opens its expectation) · ADR-0068 (matching, rule versioning, tolerances) ·
ADR-0069 (breaks) · ADR-0070 (suspense) · ADR-0071 (resolution authority and four-eyes) ·
ADR-0072 (amounts never enter metrics) · ADR-0073 (payout returns) ·
[`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`](SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md)
(every machine named here) · [`LEDGER_MODEL.md`](LEDGER_MODEL.md) (the postings compared) ·
[`PHASE_8_PLAN.md`](../project/PHASE_8_PLAN.md).

---

## 1. The concepts, and what each is not

| Concept | Is | Is **not** |
|---|---|---|
| **Reconciliation** | The continuous, item-level comparison of an internal position with the external evidence that should discharge it | Settlement. Settlement discharges an obligation; reconciliation proves whether it was discharged as recorded |
| **Settlement Expectation** (internal record) | A copy of the immutable facts of one completed clearing line — kind, operation reference, account, direction, amount, journal entry, posting date, keys — plus its disposition, opened in the completion's own transaction. Owned by `reconciliation` (moved from `settlement` by ADR-0064) | The journal line, or a balance. It is the position decomposed into items |
| **Settlement Line** (canonical external record) | One normalised line of a counterparty file: type, direction, amount, dates, typed references, and the SHA-256 of the raw record that produced it. Immutable, owned by `settlement` | The provider's record. Provider vocabulary never leaves its format adapter |
| **External Item** | Reconciliation's working copy of one settlement line, carrying what reconciliation decided about it | A second copy of the evidence. The line in `settlement.line` stays the evidence |
| **Reconciliation Source** | A declared source: code, kind, format, counterparty, channels, remittance-reference pattern, and the position read from that counterparty's own declaration | A rail. Codes are never bare rail names, and no source restates a position its counterparty declares |
| **Settlement Batch** | The counterparty's settlement unit, one per file: a PSP day, a scheme cycle, a payout day, or one bank statement per currency. Always single-currency | A Reconciliation Batch |
| **Reconciliation Batch** (run) | One execution over one accepted settlement batch (`BATCH`), or over residual items (`REPROCESS`), pinning its rule set, business date and source sequence | A schedule tick, or the settlement batch it reads |
| **Match Decision** | One evaluation of one item: the pinned rule set, the rule that fired, the outcome and its explanation, and a snapshot of every candidate it saw | An allocation. A decision may allocate nothing |
| **Allocation** | An append-only amount joining one item to one expectation | An editable link. Only a repudiation adds a counter-allocation |
| **Rule Set** | A versioned per-source bundle: matching rules, tolerances, the provider fee schedule, severity thresholds, lags, grace windows and the gain minimum age | Code. A change is a new version, activated by a second person |
| **Tolerance** | A versioned bound on a comparison against a value the ledger has **not** recorded: a processing fee, or a date | An amount bound. No tolerance can exist on an amount already in a position (`INV-REC-08`) |
| **Break** | A classified discrepancy with a subject, a value at issue (possibly zero), a severity and a lifecycle | An error to be cleared. It is never deleted, and a recurrence after resolution is a new break |
| **Suspense Item** | One unit of value parked in `SUSPENSE_UNMATCHED`: side, amount, released amount, age, and exactly one owning break | A resting place (`INV-REC-05`, `INV-REC-09`) |
| **Resolution** | A decided disposition of a break — or, for repudiation, of a settlement batch — with a kind, a reason code, a narrative, a frozen amount and the residual version it judged | An edit to either record. A posting resolution produces a new ledger `ADJUSTMENT` entry |
| **Remittance** | The net funds movement a counterparty batch implies, N = T_in − T_out − F, opened as a `REMITTANCE` expectation on that counterparty's clearing position | Cash. Only the bank statement discharges it |
| **Exception** | The operator's umbrella over refused deliveries, rejected files, breaks, silent sources and blocked runs | An aggregate. It is a view over records the two modules already own |

## 2. What is reconciled against what

Four pairs. The first three compare a record the platform owns with evidence it does not; the
fourth proves the platform's own parking is owned.

| Pair | Internal side | External side | Agreement means |
|---|---|---|---|
| **Each clearing position vs its counterparty's report** | `SETTLEMENT_CLEARING`, `INSTANT_CLEARING`, `PAYOUT_CLEARING`, each decomposed into open expectations | The reports of `simulated-psp.settlement`, `simulated-scheme.cycle-report`, `simulated-payout.settlement` | Every report line allocates to an expectation of its own position, and every expectation is allocated in full |
| **Each counterparty's remittance vs the bank statement** | The `REMITTANCE` expectation each accepted report opens on its position | The attributed lines of `simulated-bank.statement` | The bank line equals the remittance exactly — singly, by its `REMITTANCE_REF` judged in the attributed source's key scope, or, when its reference reaches no remittance at all, as the whole of one value date's untouched remittances of that source that no other line of the chunk claims by key; a difference is `SETTLEMENT_MISMATCH` (`REMITTANCE_DIFFERS`), never absorbed *(as built by `P8-TSK-016`)* |
| **Cash vs the statement's closing balance** | `CASH_AT_BANK`, per currency | The closing balance of the highest-sequence accepted statement of an unbroken chain | The two are equal (the cash proof, §12) |
| **Suspense vs its owning breaks** | `SUSPENSE_UNMATCHED`, per currency | — (internal) | The balance equals the open suspense items, gross — plus Phase 7's unmatched confirmations not yet adopted, a term that reads 0 once `P8-TSK-020` adopts them — and every item names its break (the suspense proof, §12) |

**Two evidence hops, on each counterparty's own position** (ADR-0065):

```
completion ─────────▶ posts to clearing position P      + its expectation, same transaction
report accepted ────▶ fees F recognised on P            DR PROCESSING_COSTS / CR P
                      transaction lines post nothing    each allocates to its expectation
                      REMITTANCE |N| opened on P         N = T_in − T_out − F
statement accepted ─▶ cash moved against P              DR/CR CASH_AT_BANK, per attributed counterparty
                      the bank line                     allocates to P's remittance
```

A counterparty's report of payment is still that counterparty's obligation, and its clearing
position already says so. That is why there is no in-transit account: a shared one would net the
PSP's receivable against the payout provider's payable (`INV-RAIL-04`), and one per counterparty
would repeat what the expectations already carry. "Reported, awaiting cash" is an expectation
fact, answered per operation by `/settlement-status` (`PENDING`, `REPORTED`, `CASH_CONFIRMED`,
`OVERDUE`, `RESOLVED`). **The ledger's cash moves only on the bank's own statement**
(`INV-SET-06`): a report of payment is not cash (`INV-SET-01` at the last hop).

**Not pairs, by decision:**
- **The book rail** — wallet payments, book refunds, transfers. `SettlementModel.NONE` is
  `INV-SET-01`'s documented per-rail guarantee (ADR-0059 §4): nothing external settles it, and the
  trial balance and statement derivation cover it.
- **Webhook-derived records.** They already produced internal state, and reconciling state against
  its own inputs proves nothing. They feed matching instead — the ARN alias as a key, the announced
  cycle token as an attribute of the expectation, never a key (§4) — and their verbatim evidence
  (`payments.provider_evidence`) stays reachable from a break through its operation.
- **Network, acquirer and processor files.** The platform contracts with the PSP, whose report
  carries the ARN. Since the Phase 7 → 8 transition's card repair, a second, different network
  clearing of one capture is a distinct outcome, `SECOND_PRESENTMENT`, loud and counted unmappable,
  never absorbed — but stored only in the retained evidence: there is no clearing-notice table and
  no cleared amount on the wire. The cleared-amount and second-presentment evidence table is
  therefore Phase 8's clearing-level matching's to own, on the PSP report's hop (ADR-0065).

## 3. The reconciliation sources

A compiled register of four, composed in `app` from each counterparty's own declaration:

| Code | Kind | Format | Discharges (read at composition) | Channels | Unit |
|---|---|---|---|---|---|
| `simulated-psp.settlement` | `PSP_SETTLEMENT_REPORT` | `SIM_PSP_CSV` v1 | the card rail's `clearingPurpose()` | UPLOAD, PULL | a business day |
| `simulated-scheme.cycle-report` | `SCHEME_CYCLE_REPORT` | `SIM_SCHEME_JSON` v1 | the instant rail's `clearingPurpose()` | UPLOAD, PULL | a settlement cycle, by its token |
| `simulated-payout.settlement` | `PAYOUT_PROVIDER_REPORT` | `SIM_PAYOUT_CSV` v1 | `PayoutSettlementDeclaration.CLEARING_PURPOSE` | UPLOAD, PULL | a business day |
| `simulated-bank.statement` | `BANK_STATEMENT` | `SIM_STATEMENT_TAGGED` v1 (MT940-shaped) | `CASH_AT_BANK`, `settlement`'s own | UPLOAD, PULL | one statement per currency, by sequence |

- **One source per settling position, and only its evidence discharges it** (`INV-SET-05`). Every
  rail declaring `settlement() != NONE` gets exactly one source, and so does the payout position;
  `EverySettlingPositionHasASource` fails on a planted uncovered rail. No source's evidence posts to
  another counterparty's position.
- **Positions are read, never restated.** `settlement.source` holds identity and operational state
  and no position column, and neither module may name a `*_CLEARING` purpose.
- **Evidence takes effect only whole and authenticated** (`INV-SET-07`). A file pulled over its
  source's confined credential is accepted as delivered; the pull's source URLs are held to
  `ProviderTransportGuard`'s rule — no plaintext transport off loopback, refused at startup — which
  the pull task (`P8-TSK-021`) extends from the providers' https URLs to its own SFTP and HTTPS
  sources (the Phase 7 → 8 transition's transport repair). An uploaded file stays inert until a
  second person holding `SETTLEMENT_INGEST` attests it. A readmission — a file *our* validation
  wrongly rejected, or a genuine file rejected `CONFLICTING_BATCH` against a batch since repudiated —
  inherits its original's authentication through the identical checksum when the original was
  pulled or attested; the readmission of an unattested upload is itself attested — by a person
  distinct from the readmitter and from the original's uploader — before acceptance. Whether readmission extends to a declined upload is ADR-0066 §8's recorded question,
  carried into `P8-TSK-022`. *(The Phase 7 → 8 transition's consistency review, A11.)*
- **Refused at the door, before anything is stored** (ADR-0066): card-number and bank-identifier
  shapes in declared free-text fields. Reference fields are checked by their own shapes, so a
  15-digit network transaction identifier is never tested as free text, and a field that fails its
  declared class is screened as free text before the file can be stored as malformed — a card
  number in a reference column is refused, not retained *(the Phase 7 → 8 transition's consistency
  review, C6)*. A refused delivery keeps metadata only — never the value — and is recovered by
  re-presentation: for a refused delivery, `INV-PAY-02` and `INV-RAIL-03` take precedence over
  `INV-HIST-02`.
- **Retained verbatim otherwise**, as AES-256-GCM chunks in PostgreSQL, AAD-bound to file, source,
  checksum and position, checksum-verified on every read, and every content read audited with a
  reason (`INV-REC-10`).
- **Silence is visible.** The expected arrival per source is derived, not stored;
  `finapp.settlement.source.silence` rises when a file is missing, and the expectations it should
  have discharged age into breaks on schedule.

## 4. Expectations: the internal records

**Every externally settling completion opens its expectation in its own transaction**
(ADR-0067, `INV-SET-02`, `INV-REC-06`). The completing code — `PaymentOutcomes`,
`WithdrawalOutcomes`, `ChargebackAccounting`, `UnmatchedConfirmations`, `MerchantPayoutOutcomes` —
calls a required port past its acting exit (`SettlementExpectations` in `payments`, only when the
stored rail's `RailCapabilities.clearingPurpose()` is present; `PayoutSettlementExpectations` in
`merchant`), and `PaymentClearing` registers the ARN alias through the same port. The port is
infallible for valid input: a key that collides is skipped and recorded as a `KEY_COLLISION`
event, which later raises `DUPLICATE_INTERNAL`, and **never fails the payment**.

The expectation holds **copies of immutable facts** taken at completion, so reconciliation never
joins another module's rows. It is keyed on `(journal_entry_id, ledger_account_id)` and
`(kind, operation_ref)`, both unique, so a duplicate applier opens nothing twice.

| Kind | Opened by | Direction | Position | Allocated from |
|---|---|---|---|---|
| `CARD_CAPTURE` | `payment-capture:<attemptId>` | INBOUND | `SETTLEMENT_CLEARING` | `CAPTURE` |
| `CARD_REFUND` | `payment-refund:<refundId>` (card) | OUTBOUND | `SETTLEMENT_CLEARING` | `REFUND` |
| `CHARGEBACK` | `dispute-chargeback:<disputeId>` | OUTBOUND | `SETTLEMENT_CLEARING` | `CHARGEBACK` |
| `CHARGEBACK_REVERSAL` | `dispute-won:<disputeId>` | INBOUND | `SETTLEMENT_CLEARING` | `CHARGEBACK_REVERSAL` |
| `DISPUTE_FEE` | `dispute-fee:<disputeId>` | OUTBOUND | `SETTLEMENT_CLEARING` | `DISPUTE_FEE` |
| `PUSH_PAY_IN` | `payment-execution:<attemptId>` | INBOUND | `INSTANT_CLEARING` | `CREDIT_IN` |
| `UNMATCHED_CONFIRMATION` | `unmatched-confirmation:<rail>:<ref>` | INBOUND | `INSTANT_CLEARING` | `CREDIT_IN` |
| `PUSH_WITHDRAWAL` | `wallet-withdrawal:<withdrawalId>` | OUTBOUND | `INSTANT_CLEARING` | `DEBIT_OUT` |
| `PUSH_RETURN` | `payment-refund:<refundId>` (return) | OUTBOUND | `INSTANT_CLEARING` | `DEBIT_OUT` |
| `MERCHANT_PAYOUT` | `merchant-payout:<payoutId>` | OUTBOUND | `PAYOUT_CLEARING` | `PAYOUT_EXECUTED` |
| `PAYOUT_RETURN` | `merchant-payout-return:<payoutId>` | INBOUND | `PAYOUT_CLEARING` | `PAYOUT_RETURNED` |
| `REMITTANCE` | a report's acceptance (no journal entry of its own) | sign(N) | the report's position | attributed `BANK_CREDIT`, `BANK_DEBIT` |

- **Dated when opened.** `expected_by = posting_date + lag_days[kind]` from the active rule set,
  pinned on the row. Seeded: card 3 days, refund and dispute 3, instant 1, payout 2; a remittance
  `value_date + funding_lag_days` (2).
- **Overdue is a fact, not a state.** `overdue_since` is set once by the ageing sweep, on the
  database clock, when nothing is allocated past `expected_by + SETTLEMENT_DATE_DAYS`, and raises
  `MISSING_EXTERNAL`. An overdue expectation can still settle (`INV-SET-03`).
- **Not expected:** processing fees, which nothing posts before the counterparty reports them —
  they are checked against the pinned `provider_fee_schedule` and recognised from the report; and
  the book rail (§2).
- **Keys are scoped per source** (`reconciliation.expectation_key`, first writer wins), so a
  counterparty's token can never collide with another counterparty's. A reference that arrives
  before or after its expectation — the ARN — is an alias (`reconciliation.reference_alias`),
  resolved in a local two-hop read. On the instant rail, payments' `scheme_execution_claim`
  (payments `V023`, the Phase 7 → 8 transition's repair) gives every scheme execution one claimant
  before money moves, so a pay-in, a withdrawal, a return and a parking never share a `SCHEME_REF`
  for new executions; a parking `V023`'s backfill left without a claim — its execution also
  credited — meets the credit's `SCHEME_REF` at opening as a `KEY_COLLISION`, which surfaces the
  `DUPLICATE_INTERNAL` it is.
- **The announced cycle is an attribute, not a key** (ADR-0067 §5). The settlement cycle a pay-in's
  confirmation announced, or a withdrawal stored, is a column of the expectation row (reconciliation
  `V002`): compared at matching (a different cycle is `TIMING_DIFFERENCE`, cause `CYCLE_MISMATCH`)
  and a report dimension, never an `expectation_key` kind, because one cycle names many operations.
  *(The Phase 7 → 8 transition's consistency review, A5. As built by `P8-TSK-017`: the report's
  cycle token rides on the run, `reconciliation_batch.settlement_cycle`; it is compared, not used
  to choose between candidates — two reachable candidates stay `AMBIGUOUS_MATCH`.)*
- **A return's cycle is learned, not stored.** A return keeps no settlement cycle; the scheme's
  report supplies it, recorded on the item (`learned_cycle`). No payments migration. *(As built by
  `P8-TSK-017`, reconciliation `V009`: written once by the allocating chunk, only equal to the
  run's cycle and never at birth, for every writer.)*
- **A payout return is a merchant fact** (ADR-0073), applied from settlement evidence by
  `PayoutReturnSchedule` through `merchant.PayoutReturns.apply`, which finds the payout through its
  stored provider reference, posts `merchant-payout-return:<payoutId>`, then records the return
  (its money bound to the payout's by a composite foreign key) and opens `PAYOUT_RETURN` in one
  transaction. The return expectation opens **no key of its own** — the line's references are the
  payout's own keys, held by its `MERCHANT_PAYOUT` expectation — and is reached as that operation's
  `PAYOUT_RETURN` by `UNIQUE (kind, operation_ref)` (§6.1). The payout stays `COMPLETED`. *(The
  Phase 7 → 8 transition's consistency review, A4 and A6.)* *(Built by `P8-TSK-019`: a check
  that fails — no payout, not `COMPLETED`, already returned, a different amount, a payable no
  longer `ACTIVE` — is a typed outcome that writes nothing, the item waiting out its grace; the
  rematch worklist's anchored clause re-decides the item once the keyless `PAYOUT_RETURN`
  opens.)*
- **History before Phase 8** is brought in by the opening-position backfill: keyed, leaderless,
  converging on the same uniques as live completions.

## 5. The canonical external record

A file becomes lines in one transaction, or it is rejected whole: **no line of a file drives
matching or posting unless every line is valid and the control totals equal the lines**
(`INV-SET-07`). A malformed line, a trailer mismatch, an unknown currency or a scale mismatch
rejects the file with its errors recorded and no content; its expectations then age on schedule.
**Our own failure never rejects evidence**: a parser exception leaves the file `RECEIVED`, backed
off and visible. The matcher reads only `ACCEPTED` batches.

- **Line type** is a closed enum: `CAPTURE`, `REFUND`, `CHARGEBACK`, `CHARGEBACK_REVERSAL`,
  `DISPUTE_FEE`, `PROCESSING_FEE`, `CREDIT_IN`, `DEBIT_OUT`, `SCHEME_FEE`, `PAYOUT_EXECUTED`,
  `PAYOUT_RETURNED`, `COUNTERPARTY_ADJUSTMENT`, `BANK_CREDIT`, `BANK_DEBIT`, `BANK_FEE`, `OTHER_IN`,
  `OTHER_OUT`.
- **Direction** is the platform's view, `INBOUND` or `OUTBOUND`, and the amount is the ADR-0003
  triple, always positive.
- **Dates:** business, settlement and value.
- **Typed references** (`settlement.line_reference`): `PSP_CAPTURE_REF`, `PSP_REFUND_REF`,
  `ACQUIRER_REF`, `DISPUTE_REF`, `SCHEME_REF`, `END_TO_END_REF`, `SETTLEMENT_CYCLE`,
  `PAYOUT_PROVIDER_REF`, `OUR_REF`, `ORIGINAL_REF`, `REMITTANCE_REF`. All shape-checked;
  bank-identifier and alias shapes are refused by `CHECK` (`INV-RAIL-03`).
- **Fingerprints:** `raw_record_sha256` ties the line to the bytes that produced it;
  `canonical_fingerprint` — type, direction, amount, currency, dates and sorted references — is
  indexed and deliberately **not** unique, so a repeated line is kept and becomes a break.
- **The provider mapping is total, and its default is never a success.** An unknown well-formed
  line type becomes `OTHER_IN` or `OTHER_OUT`: an item, and eventually a break, never dropped.
- **A gross amount with a fee becomes two lines**: the transaction line, and a `PROCESSING_FEE`
  line carrying `ORIGINAL_REF`.
- **Bank-line attribution is normalisation, not matching.** Each source declares a remittance
  pattern; a bank line is attributed to the **unique** source whose pattern its structured
  remittance reference FULLY matches. Zero or two matches leave it unattributed — cash all the
  same (the recognition debits `CASH_AT_BANK`), its value parked owned under a
  `BANK_UNATTRIBUTED` suspense item and an `UNKNOWN_EXTERNAL` break from its transaction *(as
  built by `P8-TSK-016`: attributed at parse, written on the line, copied to the item with the
  attributed source's position)*.
- **Versions are frozen.** Every file and batch records its format and version, each version is
  pinned by golden files, and a behaviour change is a new version.

**Duplicates at the file boundary are idempotency, not breaks.** The same bytes delivered again —
by upload, by pull, by ten instances — converge on one file (`UNIQUE (source_id, content_sha256)`)
with a `DUPLICATE` receipt. Different bytes declaring an already-accepted batch or statement
sequence are `REJECTED(CONFLICTING_BATCH)`, retained and alerted, never applied; a counterparty
corrects itself by `COUNTERPARTY_ADJUSTMENT` lines in a later batch. A batch is recognised once,
from its own stored evidence (`INV-SET-04`).

## 6. Deterministic matching

ADR-0068. **The same stored inputs always produce the same matches** (`INV-REC-04`), and every
allocation is explainable from rows alone.

### 6.1 Keys (rule set v1, in priority order)

| Source | Line type | Keys, in priority | Expectation kind | Cardinality |
|---|---|---|---|---|
| PSP | `CAPTURE` | `PSP_CAPTURE_REF`, then `ACQUIRER_REF` through its alias | `CARD_CAPTURE` | `ONE_TO_ONE` |
| | `REFUND` | `PSP_REFUND_REF`, then `OUR_REF` (`rfd-…`) | `CARD_REFUND` | `ONE_TO_ONE` |
| | `CHARGEBACK`, `CHARGEBACK_REVERSAL`, `DISPUTE_FEE` | `DISPUTE_REF` plus the stage (`DISPUTE_CB_REF`, `DISPUTE_REV_REF`, `DISPUTE_FEE_REF`) | the kind of the same name | `ONE_TO_ONE` |
| | `PROCESSING_FEE` | `ORIGINAL_REF` → the capture | — | `CHECK` |
| | `COUNTERPARTY_ADJUSTMENT` | `ORIGINAL_REF` | the original's remainder, or the original item's parked excess | `CORRECTION` |
| Scheme | `CREDIT_IN` | `SCHEME_REF`, then `END_TO_END_REF` | `PUSH_PAY_IN` or `UNMATCHED_CONFIRMATION` | `ONE_TO_ONE` |
| | `DEBIT_OUT` | `SCHEME_REF`, then `END_TO_END_REF`, then `OUR_REF` | `PUSH_WITHDRAWAL` or `PUSH_RETURN` | `ONE_TO_ONE` |
| | `SCHEME_FEE` | `ORIGINAL_REF` → the execution, by its `SCHEME_REF` *(`P8-TSK-017`)* | — | `CHECK` |
| Payout | `PAYOUT_EXECUTED` | `PAYOUT_PROVIDER_REF`, then `OUR_REF` (`pyo-…`) | `MERCHANT_PAYOUT` | `ONE_TO_ONE` |
| | `PAYOUT_FEE` *(`P8-TSK-018`; the terms completed in rule set v1 by reconciliation `V010`)* | `ORIGINAL_REF` → the payout, by its `PAYOUT_PROVIDER_REF` | — | `CHECK` |
| | `PAYOUT_RETURNED` | **Operation-anchored, never key-matched**: `PAYOUT_PROVIDER_REF`, then `OUR_REF`, name the payout's operation through its `MERCHANT_PAYOUT` expectation's key, and the item is allocated to that operation's `PAYOUT_RETURN` (`UNIQUE (kind, operation_ref)`) once the return worker has applied it; until then it waits `UNMATCHED`, no break raised by the matcher | `PAYOUT_RETURN` | `ONE_TO_ONE` |
| Bank | `BANK_CREDIT`, `BANK_DEBIT` (attributed) | `REMITTANCE_REF`, then the value date | `REMITTANCE` of the attributed position | `ONE_TO_ONE`, then `GROUP_BY_VALUE_DATE` |
| | `BANK_FEE` | — | — | `CHECK` (the fee is posted at recognition) |

`OTHER_IN` and `OTHER_OUT` match no rule. Internal state that could *explain* an unmatched item is
read through `InternalReferenceLookup` — **for typing a break only, never for allocation**.

**The payout return's rule is operation-anchored in rule set v1**, seeded by `P8-TSK-004` and
frozen: an INBOUND `PAYOUT_RETURNED` line is never tried against the OUTBOUND `MERCHANT_PAYOUT`
expectation its references name — which would be a direction mismatch, a `REVERSAL_MISMATCH` parked
at once before the return worker had its chance. *(As built by `P8-TSK-018`: `Matching.resolve`
reads `operation_anchored`, the key reaching the anchor and the rule only the anchor operation's
`PAYOUT_RETURN`; waiting, then at grace `RETURN_NOT_APPLICABLE` when the lookup knows the payout.)* It waits for `P8-TSK-019`'s worker, which resolves
the payout row through the payout's stored provider reference and applies the return (§4); the
rematch leg then reaches the return's expectation by the operation-anchored lookup. No second rule
set version is needed. *(The Phase 7 → 8 transition's consistency review, A4: the row read
"`PAYOUT_PROVIDER_REF`, then `OUR_REF`", unqualified.)*

**On the instant rail, one scheme execution has one explanation.** Payments'
`scheme_execution_claim` (payments `V023`) holds one row per `(rail, scheme_reference)` naming its
one subject — `PAY_IN`, `WITHDRAWAL`, `RETURN` or `UNMATCHED` — claimed by every producer before
money moves. The lookup reads it to type a scheme line's break from the claimed subject's state.
A pay-in's claim is taken at `EXECUTED`, a withdrawal's or return's at `COMPLETED`, so a scheme
line for an operation still in flight has no claim yet: a scheme reference no claim holds names no
completed execution — after grace it types `MISSING_INTERNAL` when its other references name an
operation still in flight, and `UNKNOWN_EXTERNAL` otherwise. The claim types; the expectation's
keys allocate. *(As built by `P8-TSK-017`: the lookup is handed the item's key scope, and `app`
answers the rail as the one whose declared clearing purpose is that source's settled position.)*

### 6.2 The decision

One pure function, `decide(item, candidates, ruleSet) → Decision`, run inside a chunk
transaction:

1. **Candidates** are the expectations reachable from the item's keys under the pinned rule set,
   restricted to the same source, currency and direction with a remainder above zero, taking the
   highest-priority rule that yields any. Two candidates → `AMBIGUOUS_MATCH`. A key hit in another
   currency → `CURRENCY_MISMATCH`; in the opposite direction → `REVERSAL_MISMATCH`. Neither ever
   allocates, and a currency difference is never converted (`INV-MON-04`). A `PAYOUT_RETURNED`
   line takes no key hit at all (§6.1), so it never meets its payout's opposite direction.
2. **Cardinality** decides the allocation (§6.4).
3. **Timing.** A match whose settlement date is later than `expected_by + SETTLEMENT_DATE_DAYS`, or
   whose cycle token differs from the announced one, raises `TIMING_DIFFERENCE` (value 0). If an
   overdue break already exists, that break resolves `EVIDENCED` with the timing recorded instead.
4. **The unallocated remainder** is classified (§6.5).
5. **A poisoned item is contained.** The engine throwing on one item writes a decision with outcome
   `ERRORED`, parks the remainder and raises `PROCESSING_ERROR`; the chunk continues, because the
   rows behind a poisoned one are other people's money.

### 6.3 Claimant order

**The earlier record always wins.** Every allocation to an expectation goes through `allocate(E)`,
shared by the run, rematch, reprocess and manual paths, which serves claimants in
`(source_sequence, line_no)` order. `source_sequence` is assigned gaplessly at acceptance under the
source row's lock; a run is eligible only when every lower-sequence run of its source has
completed; and the run leg holds `pg_try_advisory_xact_lock(4, hashtext(source_id::text))` per
chunk.

Namespace 4 **orders** allocation; it does not arbitrate it. The arbiters are PostgreSQL's:
`UNIQUE (external_item_id, expectation_id) WHERE reverses_allocation_id IS NULL`, the deferred Σ
triggers keeping allocations within amounts on both sides, the amount `CHECK`s, and the items' and
expectations' conditional transitions — each proven by a lock-bypass test. There are no per-item
advisory locks. The contention register is DISTRIBUTED_EXECUTION §3.

### 6.4 Cardinalities

| Cardinality | Rule |
|---|---|
| `ONE_TO_ONE` | Allocate min(item amount, remainder). An under-payment leaves the remainder on the expectation and raises `AMOUNT_MISMATCH` against it; an over-payment parks the excess at once and raises `AMOUNT_MISMATCH` against the item |
| `PARTIAL` | Fill in claimant order |
| `GROUP_BY_VALUE_DATE` | The candidates are **all** open remittances of the source, direction, currency and value date with no other claimant; the item matches only if it equals their total exactly. There is no subset search |
| `CORRECTION` | Allocate to the original's expectation remainder (same direction). If the original item holds a parked excess of the opposite direction and equal amount, offset it instead — item `OFFSET`, a suspense release with cause `CORRECTION_OFFSET`, an unpark posting — and the original's `AMOUNT_MISMATCH` resolves `EVIDENCED` |
| `CHECK` | A non-allocating comparison: expected fee = round(rate × gross + fixed) under the pinned `provider_fee_schedule` (`numeric(7,6)`, a named rounding policy, `INV-MON-03`); beyond tolerance → `FEE_MISMATCH`. The item becomes `CHECKED` |

### 6.5 The unallocated remainder: park now, or wait out grace

| Class | Breaks | Treatment |
|---|---|---|
| **Definitive** | `DUPLICATE_EXTERNAL` (the expectation is already fully allocated, or the same fingerprint appeared earlier), `CURRENCY_MISMATCH`, the `AMOUNT_MISMATCH` excess, `AMBIGUOUS_MATCH`, `REFUND_MISMATCH` against a **terminal** failed refund, `REVERSAL_MISMATCH` against a terminal state (a capture on a `VOIDED` or `FAILED` attempt; a reversal on a dispute `LOST` or `ACCEPTED`) | Parked at once, in the deciding transaction, with its break |
| **Late internal evidence could change it** | `UNKNOWN_EXTERNAL` (the key is unknown), `MISSING_INTERNAL` (the operation is known but not completed: a capture `UNKNOWN`, a refund `DISPATCHED`, a dispute stage not yet applied), `PAYOUT_RETURNED` with no return applied yet (no break raised by the matcher; the return worker, §4) | `UNMATCHED` until `grace_until`, pinned per rule and judged on the database clock; the rematch leg retries it; at expiry the grace leg parks it with its break — for a payout return judged on the item's locked row against the worker (`P8-TSK-013`, `P8-TSK-019`) |

Classification precedence: the definitive specific types, then `MISSING_INTERNAL`, then
`UNKNOWN_EXTERNAL`. Grace cuts the noise of ordinary ordering — a report before its webhook, a bank
credit before its report — while unexplained value never rests unowned past grace.

### 6.6 Decision snapshots

Every evaluation writes a `reconciliation.match_decision` — item, origin (`RUN`, `REMATCH`,
`REPROCESS`, `MANUAL`), `rule_set_id NOT NULL`, rule priority, strategy, matched key kind, outcome,
claimant rank and count, date deviation and the timing tolerance applied, fee expected, reported
and tolerated, who and when — and one `reconciliation.match_candidate` row per candidate
considered, with its amount, direction, `remainder_before` and `opened_at`. Allocations reference
their decision, and all three tables are append-only (`INV-REC-07`, `INV-HIST-04`). "Why were
these two records matched?" is answered from these rows alone:

```
rule 1 PSP_CAPTURE_REF cap-psp-9f2; candidates 1 (remainder 2007 EUR/2, opened 2026-10-01);
allocated 2007; date deviation 1 day ≤ tolerance 2; claimant 1 of 1
```

### 6.7 Rule-set versioning

A rule set is frozen from `PROPOSED` and activated by a different `RECONCILIATION_ADMINISTER`
holder, the prior version retiring in the activating transaction, so each source has exactly one
active version. Every run, decision, allocation, break and expectation pins `rule_set_id`. **A new
version governs only new runs, rematches and explicit `REPROCESS` runs; it never alters a committed
allocation, park or break.** A rule defect that mis-matched items is corrected in money by
resolution or repudiation — committed matches stand as history.

### 6.8 Replay, which has three meanings

1. **Decision replay** re-runs `decide` over every stored decision's candidate snapshot under its
   pinned rule set and compares outcome and allocations. It appends a `reconciliation.run_replay`
   verdict (reconciliation `V012`, `P8-TSK-022`) — `IDENTICAL` or `DIVERGED` — and writes nothing
   else; `DIVERGED` raises a CRITICAL
   `PROCESSING_ERROR`. An item whose rematch is merely pending is reported `PENDING_REMATCH`, not as
   divergence.
2. **Reprocess** re-resolves candidates *now*, for residual items only (`UNMATCHED`, `PARKED`),
   under the active version. Its decisions are new rows; drift appears as new decisions, never as
   edits.
3. **Order-independence** is a property of the pure layer: shuffled processing orders yield
   identical allocations, because every allocation goes through `allocate(E)` in claimant order.

**The honest statement of determinism.** A decision is a pure function of its stored candidate
snapshot and its pinned rule set, so replay is exact. *Which* candidates a decision saw depends on
what had been recorded when it ran — expectation opening times, first-writer keys, grace expiry on
the database clock — and that is exactly why the snapshot is stored. Re-delivering a file or
re-accepting a batch is not replay: it is idempotency, and produces no second effect.

## 7. The tolerance model

**A tolerance never absorbs value** (`INV-REC-08`). Tolerances exist only for comparisons against
values the ledger has not already recorded, and the tolerance type has no amount member, so an
amount tolerance is unstorable, not merely unused.

| Comparison | Bounds | Why it absorbs nothing |
|---|---|---|
| `PROCESSING_FEE_PER_LINE` | One reported fee line against the fee the pinned schedule expects, per currency | The reported fee is what recognition expenses. A difference beyond the bound is a commercial `FEE_MISMATCH` with no residual; within it, the posted fee is still the reported fee |
| `PROCESSING_FEE_PER_BATCH` | A batch's reported fees against the fees the pinned schedule expects for it, per currency | As above |
| `SETTLEMENT_DATE_DAYS` | How late a match may settle after `expected_by` before `TIMING_DIFFERENCE`, and how long an unallocated expectation waits before `MISSING_EXTERNAL`. Seeded 2 days | A date moves no money |

**Every principal difference becomes a remainder or a parked item with its break** — one minor
unit, in either direction, is a break. An absorbing tolerance would be an unrecorded write-off
(`INV-BAL-03`, `INV-REC-02`). A request to store any other comparison is refused
(`reconciliation.ToleranceNotPermitted`).

**Not tolerances:** grace windows delay a classification and never close one; lags date an
expectation; the severity threshold ranks a break; the gain minimum age gates a resolution. None of
them decides that a difference is acceptable.

## 8. Breaks

ADR-0069. Fourteen types. A break names its subject — an expectation, an external item, a suspense
item, a run or a decision — and its value at issue, which is never negative.

**The resolutions column is ADR-0069's per-type table, the one authority**, carried identically in
`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §6 and `PHASE_8_PLAN.md` §12.6. Every type that owns a
suspense item admits `WRITE_OFF` for a DEBIT item, at any age, and `RECOGNISE_GAIN` for a CREDIT
item once past the pinned minimum age — both four-eyes (ADR-0071) — except where the table names an
exclusion and its reason. *(The Phase 7 → 8 transition's consistency review, A1, A2 and A3.)*

| Type | Detected by | Subject | Value at issue | Parked | Base severity | Resolutions |
|---|---|---|---|---|---|---|
| `MISSING_EXTERNAL` | The ageing sweep: nothing allocated past `expected_by + SETTLEMENT_DATE_DAYS` | expectation | the remainder | No — it stays in the position | MEDIUM; HIGH for `MERCHANT_PAYOUT` and `REMITTANCE` | `EVIDENCED` (late), `WRITE_OFF` (INBOUND), `TRANSFER_TO_ACCOUNT` (OUTBOUND) |
| `MISSING_INTERNAL` | The grace leg, through the lookup: the operation is known but not completed | item | the unallocated remainder | Yes | HIGH | `EVIDENCED` (the operation completes; rematch unparks), `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT item, any age), `RECOGNISE_GAIN` (CREDIT item, after the minimum age) |
| `UNKNOWN_EXTERNAL` | The grace leg; an unattributed bank line at recognition (cause `BANK_LINE_UNATTRIBUTED`); an unmatched confirmation (cause `PARKED_ON_RECEIPT`) | item or suspense item | the amount | Yes | HIGH; CRITICAL when OUTBOUND | as `MISSING_INTERNAL` |
| `AMOUNT_MISMATCH` | `ONE_TO_ONE` with a different amount; a dispute-fee line differing from its expectation | expectation (under) or item (over) | the difference | The over-part only | HIGH | `EVIDENCED` (a correction fills or offsets it), `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (an INBOUND remainder, or a DEBIT item at any age), `RECOGNISE_GAIN` (CREDIT item, after the minimum age) |
| `CURRENCY_MISMATCH` | A key hit in another currency | item | the item's amount, in its own currency | Yes | HIGH | `EVIDENCED` (a counterparty correction offsets it), `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT item, any age); **no `RECOGNISE_GAIN`** — a currency break is never income: it is resolved by `EVIDENCED` or `TRANSFER_TO_ACCOUNT` |
| `FEE_MISMATCH` | A fee check beyond tolerance | item | the difference (commercial) | No — already expensed | MEDIUM | `ACKNOWLEDGE` (four-eyes) |
| `DUPLICATE_EXTERNAL` | An expectation already fully allocated; a repeated fingerprint; a line arriving after its expectation was written off | item | the amount | Yes | HIGH | `EVIDENCED` (a claw-back correction offsets it), `OFFSET_SUSPENSE`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT item, any age), `RECOGNISE_GAIN` (a recovery: CREDIT item, after the minimum age) |
| `DUPLICATE_INTERNAL` | A key collision recorded at opening; an investigator's reclassification | expectation | the colliding expectation's amount | No | HIGH | `ACKNOWLEDGE`, `WRITE_OFF` |
| `AMBIGUOUS_MATCH` | Two or more candidates | item | the amount | Yes | MEDIUM | `MANUAL_MATCH`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT item, any age), `RECOGNISE_GAIN` (CREDIT item, after the minimum age) |
| `TIMING_DIFFERENCE` | A match later than its tolerance; a cycle other than the announced one | decision | 0 | No | LOW | `ACKNOWLEDGE` (one person) |
| `REVERSAL_MISMATCH` | A direction contradicting the record; a capture on a voided or failed attempt; a reversal without `WON`; a `PAYOUT_RETURNED` that cannot be applied (cause `RETURN_NOT_APPLICABLE`) | item | the amount | Yes | HIGH | `EVIDENCED` (a counterparty correction offsets it), `TRANSFER_TO_ACCOUNT` (for example, re-credit the payable), `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT item, any age); **no `RECOGNISE_GAIN`** — the value belongs to a counterparty (a merchant or a customer) and is resolved by `TRANSFER_TO_ACCOUNT` or `EVIDENCED`, never taken as the platform's gain |
| `REFUND_MISMATCH` | A `REFUND` line against a refund that failed internally, or against a capture with no such refund | item | the amount | Yes | CRITICAL | `EVIDENCED` (a late completion), `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT item, any age); **no `RECOGNISE_GAIN`**, for `REVERSAL_MISMATCH`'s reason |
| `SETTLEMENT_MISMATCH` | Cause `REMITTANCE_DIFFERS` (the bank line differs from the remittance); `STATEMENT_GAP` (a sequence gap, or an opening unequal to the previous closing); `OPENING_BALANCE` (the first statement opens other than at zero) | expectation or item; the statement batch, through its run, for the statement causes | the difference | Per side | HIGH; CRITICAL for the statement causes | `EVIDENCED` (the gap fills, or the funds arrive); for `REMITTANCE_DIFFERS` only, `WRITE_OFF`, `TRANSFER_TO_ACCOUNT` and, for a CREDIT excess item after the minimum age, `RECOGNISE_GAIN`; the statement causes close only `EVIDENCED` |
| `PROCESSING_ERROR` | An errored item; a blocked run (cause `RUN_BLOCKED`); a diverged replay | item, run or decision | the amount, or 0 | Items: yes | CRITICAL | Reprocess or requeue, then `EVIDENCED`; a diverged replay (`REPLAY_DIVERGED`): `ACKNOWLEDGE` alone, four-eyes, whatever its type after reclassification (`P8-TST-002`); otherwise, for a parked item, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT item, any age), `RECOGNISE_GAIN` (CREDIT item, after the minimum age) |

- **Each kind's lines decide where it can apply.** A resolution kind listed for a type is admissible
  only when the subject has the side its lines require (§10), and is refused otherwise
  (`reconciliation.ResolutionKindNotAllowed`); a kind the table does not list for the type is
  refused the same way, whatever the subject's side. A statement-cause `SETTLEMENT_MISMATCH` has no
  remainder or suspense item to post against, and cash is never adjusted to fit (`INV-SET-06`), so
  only evidence closes it.
- **Severity is deterministic.** The base comes from type and direction; it escalates one level per
  ageing band crossed (0–2, 3–7, 8–30, over 30 days) and one level when the value reaches the pinned
  per-currency `high_value_minor` — 1,000.00 in EUR, GBP and USD in rule set v1. It is stored at
  raise, and every escalation is an appended event; it only moves forward.
- **Ageing** is `now() − raised_at` on the database clock. Alert rules: CRITICAL over 0 hours, HIGH
  over 1 day, MEDIUM over 5 days, LOW over 15 days (`finapp.reconciliation.break.age` per severity).
- **Never discarded** (`INV-REC-02`). There is no `DELETE` grant on any reconciliation table and a
  refusing trigger beneath; a run cannot complete with a `PENDING` item; every unallocated remainder
  either waits in `UNMATCHED` — counted and aged — or parks with its break in its own transaction.
  One open break per (type, subject), and a recurrence after resolution is a new break naming the
  old one (`follows_break_id`).
- **Both sides preserved** (`INV-REC-01`). A break's subject reaches the settlement line, file,
  batch and raw content; the payments or merchant record, its journal entry and
  `payments.provider_evidence` — a parked confirmation's raw statement by its stored
  `unmatched_confirmation_id`, the fifth subject payments `V023` added; and the match decision with
  its candidates. Every table on that path is append-only except the dispositions.
- **Investigation** is the break's case file, not a machine of its own: assignment, append-only
  notes (refused when they hold a card-number or IBAN shape), evidence links, reclassification — in
  `OPEN` and `INVESTIGATING` only, as an appended event with a reason — and the identifier trace,
  which walks from the raw file to the journal entry with no timestamp join. `INVESTIGATING` is a
  break state; the machine is in `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`.
- **File-level problems are not breaks.** A refused delivery or a rejected file is a file state and
  an alert: a break needs a subject with value, and a rejected file's value is represented by its
  expectations ageing into `MISSING_EXTERNAL`.

## 9. Suspense

ADR-0070. **Every suspense item is owned by exactly one break** (`INV-REC-09`), which gives
"never a permanent resting place" (`INV-REC-05`) an owner per unit of value.

**Value enters `SUSPENSE_UNMATCHED` only in a transaction that records its owning break.** Four
posters, and no others:

| Origin | When | Break |
|---|---|---|
| `RECON_PARK` | A match decision, the grace leg or a rematch parks an item's remainder | the item's break |
| `BANK_UNATTRIBUTED` | Bank recognition meets a line no source's pattern attributes | `UNKNOWN_EXTERNAL`, cause `BANK_LINE_UNATTRIBUTED` |
| `UNMATCHED_CONFIRMATION` | Phase 7's unmatched pay-in confirmation, through the port; existing rows adopted by an idempotent backfill. The item keys on the parking's stored facts (payments `V023`): `named_reference`, `settlement_cycle`, `cause` and, exactly when attributed, `attempt_id` | `UNKNOWN_EXTERNAL`, cause `PARKED_ON_RECEIPT` |
| `REPUDIATION` | An approved `REPUDIATE_BATCH`, in its approval transaction, meets a `BANK_UNATTRIBUTED` item a posting resolution had already released: the recognition's reversal (`ReversalService`, scope `ledger.reverse`, key `settlement-batch:<batchId>`) still carries that item's suspense line, which opens a new item of the opposite side. `origin_ref` is the released item's id — an item is repudiated once — and the item opens on the reversal entry's posting date. The origin is admitted by `P8-TSK-023`'s reconciliation `V013` | a new `PROCESSING_ERROR`, raised in the same transaction |

*(The Phase 7 → 8 transition's re-check, R3: the table had named three posters, while ADR-0070
point 10 already had a repudiation open an item for value a resolution had released.)*

**What a parked confirmation already knows** (payments `V023`, the Phase 7 → 8 transition's
repair). Each parking records why it parked: `UNATTRIBUTED` (the statement named nothing we made),
`ATTEMPT_CONCLUDED` (it named an attempt already concluded) or `AMOUNT_MISMATCH` (a pay-by-bank
execution of another amount than the initiation asked — the executed value parked, the pay-in
failed `DECLINED`, judged by the one applier for the callback and the inquiry alike). The last two
are **attributed**: they name their attempt, so their natural way out is a `TRANSFER_TO_ACCOUNT`
crediting that attempt's counterparty, or a return to the payer — the deferred return-to-sender
(§13) — never a guess. The parking itself still has no state and no resolution; both are Phase 8's
(ADR-0070, `P8-TSK-020`). *(Built by `P8-TSK-020`: each parking — live, and every Phase 7 row the
backfill adopts — owns a CREDIT suspense item under an `UNKNOWN_EXTERNAL` break
(`PARKED_ON_RECEIPT`) standing on the item, the cause and the named attempt frozen on the break;
a parking whose execution a credit already explains is owned as a `DUPLICATE_EXTERNAL`
(`EXECUTION_ALREADY_EXPLAINED`) that admits no transfer.)*

**Parking moves unexplained value out of the counterparty's position** (P), in the transaction
that decides it:
- an **INBOUND** remainder u: DR P u / CR `SUSPENSE_UNMATCHED` u — a **CREDIT** suspense item;
- an **OUTBOUND** remainder u: DR `SUSPENSE_UNMATCHED` u / CR P u — a **DEBIT** suspense item;
- an **unpark** (a later allocation of a parked item) or a **correction offset**: the exact inverse
  for the amount, releasing the item.

Parks and unparks are aggregated per transaction and position into one entry of at most four
lines, keyed `recon-suspense:<parkId>`, posted last in their transaction and dated from stored data.

**Value leaves only** by an unpark or a correction offset — each closing its break `EVIDENCED` —
by an approved resolution, or by repudiating the batch it came from.

- **Aged and alertable.** Age runs from the item's opening date: `finapp.reconciliation.suspense.open`,
  `.age` (the oldest, NaN never zero) and `.unowned`, which must read 0.
- **Reported with amounts, never metered with them** (ADR-0072). The audited suspense report
  carries the ledger balance folded with `Money`, the items gross — CREDIT and DEBIT never netted —
  and their aged breaks.
- **Never permanent.** A CREDIT item leaves by evidence, by `TRANSFER_TO_ACCOUNT` to its owner, by
  `OFFSET_SUSPENSE` against an equal DEBIT item, or by `RECOGNISE_GAIN` once it is older than
  `gain_min_age_days` (seeded 90, four-eyes; `reconciliation.GainNotYetEligible` before) — where its
  break's type admits the gain (§8: never `REVERSAL_MISMATCH`, `REFUND_MISMATCH` or
  `CURRENCY_MISMATCH`). A DEBIT item leaves by evidence, by `OFFSET_SUSPENSE`, or by `WRITE_OFF`, at
  any age, which every type owning a suspense item admits.
- **Phase 7's gauges, corrected in meaning.** `finapp.payments.unmatched.active` and `.age` count
  every row ever parked; their descriptions become "parked, ever", and the alertable signal is
  `finapp.reconciliation.suspense.*`. The names stay.

## 10. Controlled resolution

ADR-0071. **Correction is never an edit.** A counterparty's correction is new evidence — a
`COUNTERPARTY_ADJUSTMENT` line in a later batch. A reversal is used only by repudiation, through
`ReversalService` (`INV-REV-01`). An adjustment happens only through a resolution (`INV-REC-03`).

The proposer chooses the kind, the reason code, the narrative and, for a transfer, the target.
**The lines are derived from the subject's current remainder, never typed.**

| Kind | Applies to | Lines (entry `ADJUSTMENT`, scope `ledger.adjust.approve:<proposalId>`) | Approvers |
|---|---|---|---|
| `EVIDENCED` | Any break a zero-residual allocation or offset explains | None of its own: the allocation's unpark or offset is the posting, and the stored resolution names the decision and the park | The platform only |
| `ACKNOWLEDGE` | `TIMING_DIFFERENCE`, `FEE_MISMATCH`, `DUPLICATE_INTERNAL`; a diverged replay's `PROCESSING_ERROR` (`REPLAY_DIVERGED`) | None | 1 for a zero-value `TIMING_DIFFERENCE` raised by a timing detector (`LATE_MATCH`, `CYCLE_MISMATCH`); otherwise 2 *(corrected 2026-10-01, `P8-TST-002`: this read "1 when the value is 0")* |
| `WRITE_OFF` | An INBOUND remainder in P; a DEBIT suspense item | DR `RECONCILIATION_LOSSES` / CR P (or CR `SUSPENSE_UNMATCHED`) | 2 |
| `TRANSFER_TO_ACCOUNT` | A CREDIT suspense item; an OUTBOUND remainder in P | DR `SUSPENSE_UNMATCHED` (or DR P) / CR a named `CUSTOMER_WALLET` or `MERCHANT_PAYABLE` — ACTIVE, same currency, share-locked before posting | 2 |
| `OFFSET_SUSPENSE` | A CREDIT and a DEBIT suspense item of equal amount and currency | None — the account already nets; both released | 2 |
| `RECOGNISE_GAIN` | A CREDIT suspense item older than `gain_min_age_days`, of a break type that admits it (§8) | DR `SUSPENSE_UNMATCHED` / CR `RECONCILIATION_GAINS` | 2 |
| `MANUAL_MATCH` | `AMBIGUOUS_MATCH` | A `MANUAL`-origin decision chooses one candidate; the allocation unparks as any late allocation does | 2 — it stands in for the engine |
| `REPUDIATE_BATCH` | An accepted settlement batch proven fabricated or mis-normalised | `ReversalService` on the recognition entry; append-only counter-allocations; unparks | 2 |

An approved resolution that removes an item's parked value moves the item to `RESOLVED`; one that
removes an expectation's remainder moves it to `RESOLVED_BY_ADJUSTMENT`. `RECONCILIATION_LOSSES` and
`RECONCILIATION_GAINS` are posted by nothing but approvals.

- **The four-eyes threshold** (`INV-AUD-04`). Every resolution with value at issue or a posting is
  four-eyes. A zero-value, zero-posting `ACKNOWLEDGE` of a `TIMING_DIFFERENCE` raised by a timing
  detector is one person's;
  every other acknowledgement, a diverged replay's included, is two people's *(corrected
  2026-10-01, `P8-TST-002`, ADR-0071 §3's note: derived from value alone, a diverged replay's
  zero-value acknowledgement was one person's; reconciliation `V014`)*; `EVIDENCED` is the
  platform's. No value-banded second approver. Person-distinctness holds at three ranks: the
  reconciliation domain, a `CHECK` on `reconciliation.resolution`, and the ledger's approver ≠
  initiator constraint beneath (`reconciliation.SelfApprovalRefused`).
- **Reason codes are closed** (`ResolutionReasonCode`, an allowed subset per kind, a narrative of
  1..1000 characters always required): `COUNTERPARTY_ERROR_CONFIRMED`, `INTERNAL_PROCESSING_ERROR`,
  `DUPLICATE_BY_COUNTERPARTY`, `FUNDS_ATTRIBUTED`, `UNATTRIBUTABLE_AGED`, `TIMING_CONFIRMED`,
  `FEE_ACCEPTED_AS_CHARGED`, `FEE_RECOVERED`, `AMBIGUITY_RESOLVED_BY_EVIDENCE`,
  `IMMATERIAL_DIFFERENCE`, `LOSS_ACCEPTED`, `EVIDENCE_REPUDIATED`, and `EVIDENCE_RECEIVED` (the
  platform's only). A code outside the kind's subset is refused (`reconciliation.ReasonCodeNotAllowed`).
- **Bound to the ledger, one to one.** A posting resolution goes through
  `AdjustmentService.proposeOwned`/`approveOwned` as a `ledger.adjustment_proposal` with origin
  `RECONCILIATION` and a closed reason code (ledger V015, `INV-REV-04`); resolution, proposal and
  entry are one-to-one at the database. The generic `/v1/ledger/adjustments` approval and `DELETE`
  refuse a `RECONCILIATION`-origin proposal (`ledger.AdjustmentOriginMismatch`).
- **Reconciled positions are closed to free adjustments.** A `MANUAL`-origin adjustment line on any
  of `AccountPurpose.reconciledPositions()` — the three clearings, `SUSPENSE_UNMATCHED`,
  `CASH_AT_BANK`, `PROCESSING_COSTS`, `RECONCILIATION_LOSSES`, `RECONCILIATION_GAINS` — is refused
  at the domain and the database (`ledger.AdjustmentOnReconciledPosition`). Otherwise a generic
  adjustment would create unowned suspense or unexplained clearing.
- **The approval judges what was proposed.** At proposal the resolution freezes the proposed
  amount, the break's `residual_version` and the ledger proposal's lines. Approval locks in
  DISTRIBUTED_EXECUTION §3's Phase 8 order — the source's advisory namespace `4` when it allocates,
  parks or unparks (`MANUAL_MATCH`, `REPUDIATE_BATCH`); break → resolution; the subject rows
  (expectations, then items, then suspense items, each sorted by id); the transfer target `FOR
  SHARE`; inside `approveOwned` the ledger proposal row, then the projection rows in the projection's
  own order — re-derives the lines and re-reads both; if either moved — `residual_version`
  bumps on every allocation, park, release or reclassification touching the subject — it is refused
  (`reconciliation.ResolutionStale`) and the resolution stays `PROPOSED`. An approval writing more
  than one entry — a repudiation's reversal beside its unparks — pre-locks the union of their
  projection rows in the projection's order before its first posting
  (`PostingService.lockBalancesInOrder`, the rule the Phase 7 → 8 transition's dispute repair set).
  *(The Phase 7 → 8 transition's consistency review, B9.)*
- **Evidence wins.** An `EVIDENCED` resolution arriving while a proposal is pending withdraws that
  proposal in the same transaction, and its ledger proposal is rejected. It is a stored resolution
  naming the decision and posting that explain the break — recorded, never silent, and the only
  resolution no person decides.
- **A transfer's target is judged at approval.** A target of another kind or currency is refused
  (`reconciliation.ResolutionTargetRefused`); a target no longer postable fails the approval with
  nothing written, and the resolution stays `PROPOSED` — a closed merchant's payable among them,
  since merchant close now closes the payable's ledger account (the Phase 7 → 8 transition's
  repair; a payout return to a closed merchant, §13).
- **Attribution is named where it lands, by origin** (ADR-0073 §6). Every payable line in a
  `RECONCILIATION`-origin `ADJUSTMENT` entry is `MerchantPayable`'s `reconciliationAttributed`,
  classified first, whatever it faces — `SUSPENSE_UNMATCHED`, or a clearing position when a
  transfer closes an OUTBOUND remainder — so a reconciliation transfer never reads as a capture or a
  payout returned; the customer statement labels the same lines `RECONCILIATION_ATTRIBUTION`. Both
  ship with `P8-TSK-015`, the first poster; `P8-TSK-019` adds only `payoutsReturned`. *(The Phase 7
  → 8 transition's consistency review, A12 and A13.)*
- **As built (`P8-TSK-015`).** Every kind above but `REPUDIATE_BATCH` is live (reconciliation
  `V007`, ledger `V017`). One disposal can close more than one break: the remainder an
  `AMOUNT_MISMATCH` and a later `MISSING_EXTERNAL` both answer for closes both with the approval
  that disposes of it, and an offset closes its two items' breaks; in each case one live proposal
  stands across them. Every resolution command takes the source's advisory first. Withdrawal is the
  proposer's alone and rejection another person's; the proposal's own edges live in its history
  (`resolution_event`), the break's history names the resolution that closed it.
- **Repudiation reverses, never deletes.** An approved `REPUDIATE_BATCH` reverses the recognition
  entry, counter-allocates every allocation of the batch's items, releases their parks — opening a
  `REPUDIATION` suspense item with its `PROCESSING_ERROR` break for a `BANK_UNATTRIBUTED` item a
  posting resolution had already released (§9) — and moves items and batch to `REPUDIATED`, freeing
  the batch's key. A bank item whose allocation named the repudiated batch's remittance is
  counter-allocated in the same transaction and reopened, `MATCHED → UNMATCHED`, to wait for the
  genuine remittance — the one edge out of `MATCHED` besides repudiation of the item's own batch. The file and its content are retained, and the genuine file
  is then re-presented and accepted normally, or readmitted when it was itself rejected
  `CONFLICTING_BATCH` against the repudiated batch (§3). These states, the `MATCHED → UNMATCHED`
  reopening, the `REPUDIATION` suspense origin and the `REPUDIATE_BATCH` kind arrive with
  `P8-TSK-023`'s reconciliation `V013`. *(The Phase 7 → 8 transition's consistency review, A8, A9
  and A11; its re-check, R3.)*
- **Who.** `RECONCILIATION_RESOLVE` is held by `RECONCILIATION_OPERATOR`; `RECONCILIATION_ADMINISTER`
  — rule sets, reprocessing, requeue, readmission, backfill — by `RECONCILIATION_CONTROLLER`. The
  roles are disjoint: whoever can loosen a tolerance cannot resolve the breaks it would hide.
  `LEDGER_OPERATOR` is not extended, so the desk that moves money does not reconcile it.

## 11. The dates reconciliation judges by

| Date | Decides | Comes from |
|---|---|---|
| **Business date** | Which counterparty day or cycle a batch covers | The file |
| **Settlement and value dates** | When the counterparty says value moved and became available; the recognition's value date | The evidence line |
| **`accepted_on`** | The recognition entry's posting date | Stamped **once** on the batch row in the acceptance transaction and read only from the row thereafter; never back-dated to the bank's booking date |
| **`expected_by`** | When an expectation should have been reported | `posting_date + lag_days[kind]`, pinned when it is opened |
| **`grace_until`** | When an unexplained item stops waiting | The pinned rule, at the item's decision |
| **The frozen `posting_date` of a resolution** | Which period its adjustment falls in | The proposal date, frozen with the proposal |

**Windows are judged in SQL on the database clock** (`now()`) against stored dates, never on an
instance's clock: ten instances then agree on one clock, and day-scale windows make instance drift
noise. **A recognition dated from stored facts is what lets a later-day replay converge** on its
posting key rather than conflict (`INV-SET-04`). Nothing is refused as stale: a late line
allocates like any other, and a late internal record is found by the rematch leg (`INV-SET-03`).

## 12. The four proofs

Report-only verifiers in the `TrialBalance` shape: lock-free, the scrape is the schedule, and they
**report and never repair** — a reconciliation that repairs itself has destroyed the evidence of
what went wrong. Each is computed in `app` in one `REPEATABLE READ` transaction on one connection,
composing the ledger's, settlement's and reconciliation's read APIs, folded with `Money` and never
a SQL `SUM`, and published as a verdict count behind a refresh floor — NaN when unreadable, never
zero, and never an amount.

**1. The position proof** (`INV-REC-06`), for each clearing position P and currency c, with
s(INBOUND) = +1 and s(OUTBOUND) = −1:

```
DR−CR(P,c) = Σ_{expectations e on P,c} s(e)·(amount − allocated − resolved)
           − Σ_{allocating items i on P,c, status ∈ {PENDING, UNMATCHED}} s(i)·(amount − allocated − parked − offset)
```

Remittance expectations are expectations; fee items are excluded, because their effect is in the
recognition entry. The identity holds across every step — before any match, after exact, over and
under matches, after parks, after cash, after resolutions — and **that identity is what
"explained" means here** (`CLAUDE.md` rule 11). `finapp.reconciliation.position.proof` counts the
currencies failing it and must read 0.

**2. The suspense proof** (`INV-REC-05`, `INV-REC-09`):

```
CR−DR(SUSPENSE_UNMATCHED,c) = Σ CREDIT items remaining − Σ DEBIT items remaining
                            + Σ Phase 7's unmatched confirmations not yet adopted
```

The last is a named term: the parkings whose id no suspense item's `origin_ref` names. Their
suspense line was posted in Phase 7 with no item beside it, so without the term the proof would fail
on any database holding one; with it the proof is exact before and after adoption, and the term
reads 0 once `P8-TSK-020` adopts the Phase 7 parkings as suspense items. Every item with a remainder
names an existing break (`finapp.reconciliation.suspense.unowned`, must read 0).

**3. The cash proof** (`INV-SET-06`): `DR−CR(CASH_AT_BANK,c)` equals the closing balance of the
highest-sequence accepted statement of an unbroken chain. The simulated bank opens at zero; a gap
or an `OPENING_BALANCE` break fails the verdict loudly until evidence resolves it — cash is never
adjusted to fit (`finapp.reconciliation.cash.proof`, must read 0).

**4. The completeness proof** (`INV-SET-02`, for every writer): every journal line on the three
clearings and `SUSPENSE_UNMATCHED` belongs to an entry reconciliation or settlement knows — an
expectation's `(journal_entry_id, ledger_account_id)`, a suspense item that owns it
(`INV-REC-09`), a batch's recognition, a park, a resolution, a repudiation, or a payout return.
`finapp.reconciliation.line.unattributed` counts the rest — a raw-SQL poster, a missed opener — and
must read 0: over the Phase 7 history on the clearing purposes once the opening-position backfill
has run, and on `SUSPENSE_UNMATCHED` once `P8-TSK-020` adopts the Phase 7 parkings as suspense items
(until then an unmatched confirmation's suspense line, which no expectation names, truthfully reads
as unknown). It is what lets expectation opening stay a co-committed port instead of a trigger on
the ledger's posting path. *(The Phase 7 → 8 transition's consistency review, A7.)*

Beneath all four, the trial balance stays zero per currency (`INV-ACC-01`).

## 13. What is out of scope

- **FX reconciliation**, and any conversion of mismatched currencies — Phase 9. A currency mismatch
  is a break, never a conversion (`INV-MON-04`).
- **GL mapping, period close, statements and regulatory reports** (`INV-ACC-03`, `INV-ACC-05`) —
  Phase 14, and with it the equity counter-account a non-zero opening cash balance would need.
- **Fraud and AML scoring of breaks, reserves, collection of parked chargeback shares, fee
  pass-through to merchants** — Phase 13.
- **Merchant-facing settlement statements** — Phase 12.
- **Settlement instructions** — net-settlement instructions, sweeps, prefunding, treasury and
  liquidity. Phase 8 originates no external movement; payouts and withdrawals are existing payment
  instructions. A non-goal, not a gap.
- **Recovering a DEBIT item from a customer or merchant.** No resolution kind debits an owned
  account; such value leaves suspense by evidence, offset or `WRITE_OFF`. *No owner was recorded at
  the transition.*
- **A payout return to a closed merchant.** Merchant close closes the payable's ledger account (the
  Phase 7 → 8 transition's repair), so neither the return nor a `TRANSFER_TO_ACCOUNT` can credit
  it: the value rests in suspense as `REVERSAL_MISMATCH`, owned, aged and alerting, until a person
  transfers it to an account that can take it. Returning it outside the platform is
  return-to-sender, below. *Recorded by ADR-0073 for the Phase 8 review to route; no owner yet.*
- **Deferred:** return-to-sender of unattributed funds; value-banded approver escalation;
  multi-part or superseding files; business-day calendars; fuzzy, ML or subset-sum matching;
  automatic reversal of a write-off on late evidence; re-allocating committed matches outside
  repudiation; PUSH delivery of files.
- **Never:** real provider connectivity or real formats — the four formats are simulated.
- **Object storage** for raw files — ADR-0036's trigger, re-assessed with named volume triggers by
  ADR-0066.
- **Amounts in metrics** — unmatched value, suspense balance and provider costs are audited
  operator reports (ADR-0072).
- **A Kafka consumer for any correctness.** Phase 8's events are notifications for future consumers
  (`INV-EVT-04`); Kafka is not financial truth.
- **Moving the payout onto the push rail** — recorded as not needed by ADR-0073: the canonical
  record already gives every outbound credit transfer one evidence shape.

## 14. Transition decisions the owner may revisit

*Settled on the synthesized design's recommendations at the Phase 7 → 8 transition (2026-09-28).
Each is the owner's to revisit; a changed decision corrects this document, with provenance.*

| # | Decision | Taken as | Here |
|---|---|---|---|
| O1 | Roles | Two pairwise-disjoint roles: `RECONCILIATION_OPERATOR` {`SETTLEMENT_INGEST`, `RECONCILIATION_INVESTIGATE`, `RECONCILIATION_RESOLVE`} and `RECONCILIATION_CONTROLLER` {`RECONCILIATION_ADMINISTER`} | §10 |
| O2 | Payout returns | Automated — a merchant fact applied by `PayoutReturnSchedule` — with the manual path kept as the fallback: a return that cannot apply becomes `REVERSAL_MISMATCH`, resolved by a four-eyes `TRANSFER_TO_ACCOUNT` | §4, §8 |
| O3 | PII-bearing files | Refused at the door, metadata only, rather than retained verbatim (ADR-0066) | §3 |
| O4 | A non-zero opening cash balance | The simulated bank opens at zero; a non-zero first opening raises `SETTLEMENT_MISMATCH` (`OPENING_BALANCE`) with nothing posted, and the equity counter-account waits for Phase 14 | §8, §12 |
| O5 | The gain minimum age | 90 days, four-eyes | §9, §10 |
| O6 | What to cut if scope must shrink | `P8-TSK-021` (pull; attested upload suffices), then `P8-TSK-019` (payout returns; the four-eyes transfer remains), then `P8-TSK-023` (repudiation; attestation and pull controls remain) — keeping 023 if possible | §3, §10 |
| O7 | The high-value severity threshold | 1,000.00 per currency (EUR, GBP, USD) in rule set v1 | §8 |

## 15. The stub's list, and where each item lives

The 27-line stub's pairs and capabilities, retained as the core of this document:

| Stub item | Now |
|---|---|
| internal ledger ↔ processor; payments ↔ PSP | Each card clearing position vs the PSP's report (§2). The processor sits behind the PSP, whose report carries the ARN; processor files are not a source |
| merchant payouts ↔ settlement files | `PAYOUT_CLEARING` vs the payout provider's report, then its remittance vs the bank (§2) |
| bank account ↔ internal cash records | `CASH_AT_BANK` vs the statement's closing balance (§2, §12) |
| batch/source metadata | The source register, file and batch metadata (§3, §5) |
| matching keys | Typed references, per-source keys and aliases (§5, §6.1) |
| tolerances | Fees and dates only, never amounts (§7) |
| duplicate detection | The content address and `CONFLICTING_BATCH` at the file boundary (§5); `DUPLICATE_EXTERNAL` and `DUPLICATE_INTERNAL` (§8) |
| missing internal record | `MISSING_INTERNAL`, `UNKNOWN_EXTERNAL` (§8) |
| missing external record | `MISSING_EXTERNAL` (§8) |
| amount difference | `AMOUNT_MISMATCH`, `SETTLEMENT_MISMATCH` (§8) |
| currency difference | `CURRENCY_MISMATCH` (§8) |
| fee difference | `FEE_MISMATCH` (§6.4, §8) |
| timing difference | `TIMING_DIFFERENCE` (§6.2, §8) |
| reversal | `REVERSAL_MISMATCH`, `REFUND_MISMATCH` (§8); `REPUDIATE_BATCH` (§10) |
| investigation | The break's case file (§8) |
| controlled resolution | Eight template-bound kinds, four-eyes, reason codes, stale approval (§10) |
| audit history | Every machine's append-only history, decision snapshots (§6.6), and a catalogued audit action for every privileged and platform act |
| Never erase evidence of a reconciliation break. | `INV-REC-01`, `INV-REC-02`: no `DELETE`, one open break per subject, recurrences as new breaks (§8) |
