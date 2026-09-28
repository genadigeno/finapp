# Rail and Dispute Lifecycles

Written by the Phase 6 → 7 transition (2026-09-24), the `PAYMENT_LIFECYCLES.md` and
`CHECKOUT_MERCHANT_LIFECYCLES.md` precedent: the document that names a phase's model is written
before the phase's first task, from the decisions in ADR-0059…0062, and corrected by the tasks
that implement it. *(It read "until Phase 7's first task lands, nothing in this document is
implemented" until the Phase 7 review, `P7-DOC-001`: every section below is shipped, and the
review read each against the code - the machines are exactly the code's transition tables.)*

Related: [ADR-0059](../adr/ADR-0059-payment-rails-capabilities-and-finality.md) (rails,
capabilities, finality, the three machines) ·
[ADR-0060](../adr/ADR-0060-rail-routing-pinned-and-explainable.md) (routing) ·
[ADR-0061](../adr/ADR-0061-disputes-and-chargeback-accounting.md) (disputes) ·
[ADR-0062](../adr/ADR-0062-account-to-account-and-instant-payments.md) (A2A and instant) ·
[`PAYMENT_LIFECYCLES.md`](PAYMENT_LIFECYCLES.md) (the Phase 5 machines this extends).

---

## 1. The concepts, kept apart

### Cards: what the platform owns, and what it delegates

| Concept | Is | Owned by |
|---|---|---|
| **Card** | A payment card issued by an issuer to a cardholder | `external` (the issuer) |
| **Cardholder** | The person the issuer issued the card to | `external` |
| **Card Account** | The issuer's account behind a card: credit line or deposit account | `external` |
| **Physical Card / Virtual Card** | The plastic, or the card-number-only credential, the issuer produces | `external` |
| **Card Token** | The platform's only handle on a card: a tokenised reference, `paymentmethods`' payment method (`INV-PAY-02`) | `paymentmethods` |
| **Payment Instrument** | What the customer presents to pay: a card token, a bank-account reference, or the wallet itself | `paymentmethods` for external instruments; the wallet is `accounts`' |
| **Authorization** | The issuer reserving funds against the card: no ledger effect (ADR-0048) | `payments` |
| **Capture** | Taking the reserved funds: the ledger's first touch (DR `SETTLEMENT_CLEARING`) | `payments` |
| **Reversal (void)** | Releasing an uncaptured authorization at the issuer: the card rail's one reversal (ADR-0059 §3) | `payments` |
| **Clearing** | The network's exchange of records agreeing what is owed: evidence here, no posting | `payments` records it, `settlement` reconciles it (Phase 8) |
| **Settlement** | The movement of funds that discharges the obligation | `settlement` (Phase 8) |
| **Refund** | A new movement returning captured value, our decision | `payments` |
| **Chargeback** | A reversal forced through the issuer and the network, with its own lifecycle | `payments` (Disputes, context 29) |

**PCI scope**: no PAN, CVV or track data ever enters the platform. The card is tokenised at the
boundary by the tokenisation provider, and the platform stores the token reference, a brand, a
four-digit suffix and an expiry — nothing reconstructable (`INV-PAY-02`, unchanged by Phase 7).

### Wallets: one product, the ledger's money, no second truth

| Concept | Is | Is not |
|---|---|---|
| **Wallet** | The customer's stored-value product: a Customer Account of product type `WALLET` (`accounts`) | A balance, a ledger account, or a bank account |
| **Wallet Account** | The ledger account (`CUSTOMER_WALLET`, one per currency) recording the wallet's position (`ledger`) | The product; something the customer "has" apart from the wallet |
| **Wallet Balance** | The wallet account's settled balance, derived from postings (`INV-BAL-01`) | A stored column |
| **Available Balance** | Settled minus active holds, derived in the account's lock for every debit decision (`INV-BAL-04`, `-05`) | The projection |
| **Pending Balance** | A **view over in-flight inbound payments** — a pay-in awaiting its payer, an authorization not yet captured — shown apart and never spendable | A ledger figure; nothing posts until the money lands |
| **Wallet Transaction** | A statement line: a view over the wallet account's journal lines joined to the operation that posted them | A second store of movements |
| **Funding** | Money entering the wallet from outside: a card top-up (Phase 5) or a pay-by-bank pay-in (Phase 7), each a payment crediting the wallet account | A transfer |
| **Withdrawal** | Money leaving the wallet to the customer's external bank account: an outbound push on a rail, hold-then-dispatch | A transfer; a refund |
| **Wallet Transfer** | Wallet to wallet inside the platform: Phase 4's `Transfer`, one transaction | A payment |
| **Hold** | A ledger reservation on the wallet account: a refund's, a withdrawal's | A posting |

**Wallet ↔ Account ↔ Ledger Account**: the wallet *is* a customer account (the product), which
references its ledger account(s) (the money). Nothing outside the ledger stores an amount the
wallet holds (ADR-0042, ADR-0059 §6).

### Account-to-account and instant payments

The vocabulary is ADR-0062's table: payer, payee, payer PSP, payee PSP, the payment switch
(scheme), source and destination account, alias, payer authorization (consent plus strong
customer authentication at the payer's PSP — not a card authorization), execution, confirmation,
settlement (on the scheme's cycle) and return (a new payment).

| Kept apart | Why |
|---|---|
| **A2A Payment** vs **Transfer** | An A2A payment's outcome is decided by other institutions; a transfer's is decided in one transaction of ours |
| **A2A Payment** vs **Payment** | An A2A payment is one kind of payment: a push on a rail, with no card network |
| **Payment** vs **Ledger Posting** | The posting is the accounting record of an outcome; the payment is the business operation |
| **Finality** vs **Settlement** | An instant payment is final on the scheme's confirmation and settled on the scheme's cycle |

## 2. The rail capabilities (ADR-0059 §1)

| | Card | Instant | Wallet |
|---|---|---|---|
| Interaction model | two-step | push | book |
| Finality | revocable until the dispute window ends | final on acceptance | final on posting |
| Reversals honoured | void of an uncaptured authorization | none | none |
| Refund mode | provider refund | return payment | book refund |
| Settlement | deferred via clearing | scheme-reported | none |
| Outcome deadline | none | the scheme's | not applicable |
| Disputes | chargebacks | none | none |
| Clearing position | `SETTLEMENT_CLEARING` | `INSTANT_CLEARING` | none |

## 3. The attempt's three machines (ADR-0059 §2)

**Two-step (card)** — Phase 5's machine, extended by the void (`P7-TSK-004`, shipped;
the lists are exactly `InteractionModel.edges()`, regenerated into the schema's edge trigger
by `V014` and, latest, `V017`):

```
AUTH_DISPATCHED    -> AUTH_UNKNOWN | AUTHORIZED | FAILED
AUTH_UNKNOWN       -> AUTHORIZED | FAILED
AUTHORIZED         -> CAPTURE_DISPATCHED | VOID_DISPATCHED
CAPTURE_DISPATCHED -> CAPTURE_UNKNOWN | CAPTURED | FAILED | VOID_DISPATCHED
CAPTURE_UNKNOWN    -> CAPTURED | FAILED | VOID_DISPATCHED
VOID_DISPATCHED    -> VOIDED | VOID_UNKNOWN | FAILED
VOID_UNKNOWN       -> VOIDED | FAILED

terminals: CAPTURED, VOIDED, FAILED
```

Two edges deserve their provenance. `AUTHORIZED -> FAILED`, drawn in this section's first
version, was removed by the implementing task: no producer exists — abandoning a promise
is the void's own act, and a declined void lands `FAILED` from the void states, carrying its
mapped reason. `CAPTURE_* -> VOID_DISPATCHED` is the **capture redirect**: on a rail whose
declared reversals contain `VOID`, a declined capture releases the standing authorization
instead of leaving it to lapse against the customer's funds. *(The Phase 7 → 8 transition's gate
widened the redirect to a capture that never left - a refused connection - and to one the
provider says it never received, and made a never-received VOID a re-send by its stored
reference, concluded by that answer (`VOID_UNKNOWN` → `VOIDED` | `FAILED`), never
`FAILED(NEVER_RECEIVED)`: each had failed the payment with the authorization standing.)*

**Push (instant, A2A)**:

```
AWAITING_PAYER ──> EXECUTION_DISPATCHED ──> EXECUTION_UNKNOWN ──> EXECUTED
      │    │                 │                     │                  ^
      │    └─────────────────┼─────────────────────┼──────────────────┘
      └──────────────────────┴─────────────────────┴──> FAILED
```

`AWAITING_PAYER` exists only for a pay-in: the payer authorizes at the payer PSP, and the attempt
waits for that PSP's answer — never for our clock. **`AWAITING_PAYER → EXECUTED` is the inbound
edge** (`P7-TSK-009`, ADR-0062 §5), added with its producer: a pay-in's execution is the PAYER's
act, reported by the scheme's signed confirmation or the initiation inquiry — the platform never
dispatches it, so the waiting state concludes directly. This section's first version routed the
conclusion through `EXECUTION_DISPATCHED`, a state no pay-in ever occupies; the correction is
recorded here with provenance, exactly as the two-step machine's `AUTHORIZED → FAILED` was.
**The outbound states have no producer.** *(This read "the outbound states remain for their
own producers: a return payment is born `EXECUTION_DISPATCHED` (`P7-TSK-010`)" until the Phase
7 review: a return is a refund row in the refund's own four-state machine - payments `V018`
changed no refund state - and a withdrawal is its own aggregate, §5. `EXECUTION_DISPATCHED`
and `EXECUTION_UNKNOWN` are declared, carried by the generated constraints and written by
nothing; a scheme's timeout lands the withdrawal's or the refund's `UNKNOWN`. They stay
reserved for an outbound push that is an attempt, which no Phase 7 flow is - Known
Architectural Debt under ADR-0044's no-state-without-a-producer rule, recorded in
`CURRENT_STATE.md`.)*

**One scheme execution, one money fact** *(the Phase 7 → 8 transition, payments `V023`)*: every
producer of a scheme execution on a rail — the pay-in's `EXECUTED`, the withdrawal's
`COMPLETED`, the return's `COMPLETED` and the suspense parking — claims its
`(rail, scheme reference)` in `payments.scheme_execution_claim` before any money moves, and the
primary key decides between them for every instance. The executed amount is judged by the ONE
applier for the callback and the inquiry alike: a mismatch parks the executed value
(`AMOUNT_MISMATCH`) and fails the pay-in `DECLINED`; an `expired` inquiry answer fails it; a
statement of value on a concluded attempt parks (`ATTEMPT_CONCLUDED`); and a withdrawal's or a
return's own confirmation is recognised at the door as the echo it is.

The pay-in's initiation ambiguity is deliberately NOT a state: an `initiate()` whose answer was
lost leaves `AWAITING_PAYER` **without a stored handle**, and the resolution is the sweep's
convergent re-initiate under the scheme's dedupe — one act resolves "opened, answer lost" and
"never opened" alike, so a state distinguishing them would carry no behaviour. The failing
conclusions are conditional on the handle's absence for every writer (a row the payer can still
complete is never failed by our unavailability — ADR-0062 §3, adapted).

**Book (wallet)**: born `EXECUTED` inside the confirmation's transaction. *(It read "born
`EXECUTED` or `FAILED`" until the review: an unaffordable wallet payment rolls the whole
transaction back - `WalletPaymentUnfunded`, nothing written - so `FAILED`, in the model's
state set, is never born.)*

**Status (`P7-TSK-009`)**: the three machines are code — `InteractionModel.edges()`
owns them, payments `V017` regenerates the every-writer edge trigger from them (`V012`
and `V014` are applied history), and the model is a frozen birth fact on every attempt
row. The two-step machine runs end to end INCLUDING the void; the push machine runs its
INBOUND life end to end — the initiation, the handle, the signed confirmation, the
inquiry sweep and the suspense parking (`P7-TSK-009`); the book birth runs in the
confirmation's one transaction (`P7-TSK-011`); and the outbound pushes - the withdrawal
(`P7-TSK-008`) and the return (`P7-TSK-010`) - run as their own aggregates, not as push
attempts.

## 4. The routing decision (ADR-0060)

**Status (`P7-TSK-003`)**: shipped. The policy is versioned and immutable (payments `V013`,
seeded with version 1 — the standing card pay-in route), availability is a recorded operator
fact read inside the decision's transaction, and every confirmation pins its decision — the
version, the judged inputs, every candidate's step — in the same Tx1 as the attempt it
governs, publishing `RailSelected`. No eligible rail is a recorded refusal (`payments.NoEligibleRail`,
retryable by design). **Phase 7 has no cross-rail fallback after dispatch** *(this read "the
cross-rail re-dispatch arrives with the rails that can carry one (`P7-TSK-006`, `-009`)" until
the Phase 7 review; both shipped without one)*: the only fallback is candidate rejection inside
the decision, before anything is sent. A card authorization answered `NOTHING_SENT` appends its
`ABANDONED` step and the payment fails; a pay-in initiation's or a withdrawal's `NOTHING_SENT`
fails it with no step. No Phase 7 policy offers one instrument two eligible rails.

`policy version` + `stored inputs` → `ordered candidates, each with its reason` → `CHOSEN`.
Pinned before dispatch, frozen, recomputable - from its own stored row, proven by
`PaymentEndpointDatabaseTest#routingPinsTheConfirm` since the review. Were it ever to advance, it
would advance only on `NOTHING_SENT` or an eligibility refusal, and never after an ambiguous
dispatch.

## 5. The withdrawal (ADR-0062 §6) — *shipped `P7-TSK-008`*

The machine's own edge list, from `WithdrawalStatus.permittedTransitions()` (`V016`'s trigger
carries the same disjunction for every writer; `PaymentsMigrationTest` reconciles):

- `DISPATCHED → COMPLETED | FAILED | UNKNOWN`
- `UNKNOWN → COMPLETED | FAILED`
- `COMPLETED → ∅` and `FAILED → ∅` — nothing leaves a terminal, and the empty edge set out of
  `COMPLETED` **is** `INV-REV-03` on a final-on-acceptance rail.

The dispatch holds the amount on the wallet account in its lock, commits `DISPATCHED` with our
end-to-end reference and a send permit, and only then sends. `COMPLETED` releases and posts;
`FAILED` releases only on the scheme's own refusal, or on a first send's refused connection that
no later permit has overtaken, or past the scheme's **declared** deadline plus the configured
margin under the permit rule — judged on the locked row, never a clock alone. An accepted
withdrawal is irrevocable: a reversal of it is refused by the domain (`INV-REV-03`) — the
declaration gate before anything exists, and the machine's own shape after.

## 6. The dispute (ADR-0061 §2) — *shipped `P7-TSK-012`; its money `P7-TSK-013`*

```
INQUIRY ──> CHARGED_BACK ──> REPRESENTED ──> WON
   │             │                 └────────> LOST
   │             ├──────────────────────────> LOST
   │             └──────────────────────────> ACCEPTED
   └──> CLOSED  (an inquiry that never became a chargeback)
```

Entered at `INQUIRY` or directly at `CHARGED_BACK`. Terminal: `WON`, `LOST`, `ACCEPTED`,
`CLOSED`. The lists are exactly `DisputeStage.permittedTransitions()`, which `V020` generates
into its every-writer trigger; the birth trigger admits only the two entry stages.

**How a notification lands** (`P7-TSK-012`, `DisputeNotifications`), every stage the card PSP's
word alone — the platform never decides one:

| The notified stage against the dispute's current one | What happens |
|---|---|
| No dispute yet | Born at the entry stage on the shortest walk to the notified stage, then walked to it — a `WON` heard first opens at `CHARGED_BACK` and passes `REPRESENTED` |
| The same stage | Nothing: the network repeating itself (`INV-IDEM-04`) |
| A stage ahead | Every stage between applied in order, each its own conditional edge, trail row and audit record (ADR-0061 §2) |
| A stage the dispute has passed | Nothing, quietly: a late delivery is ordering, not breakage |
| Any other stage | Nothing, **loudly** (the webhook meter's `unmappable`): a second outcome, a chargeback on a closed inquiry — the first record stands (`INV-LIFE-04`) |

The shortest walk is exactly the stages the target *implies* (it lies inside every walk to it,
asserted for every pair), so no stage the network did not need to pass through is ever
invented. The opening statement — provider, reference, attempt, reason category — is frozen
for every writer. **The chargeback's amount arrives with the chargeback** (the captured
amount's discipline, `NULL → value`): an inquiry records none, a chargeback — at birth or on
the inquiry's escalation — records what the network took, which may be less than the
transaction, and it never moves after; it is the figure `P7-TSK-013` posts. A later statement
of another chargeback amount, another payment's operation or another currency moves nothing,
loudly; a late inquiry repeating the transaction's full amount is ordering, not a
contradiction. A dispute is
recorded against whatever card attempt the network names, whatever its state: the external
fact first (ADR-0061 §4), attribution being the combined bound's (`P7-TSK-013`). A second
cycle arrives with a new reference and is a new dispute, never a reopened one.

**What each stage posts** (`P7-TSK-013`, `ChargebackAccounting`; ADR-0061 §3–§5) — once per
stage, keyed by the dispute and the stage, behind the stage's conditional transition:

| Stage entered | Entries | Lines |
|---|---|---|
| `CHARGED_BACK` | `dispute-chargeback:<id>` — the external fact | DR `CHARGEBACK_RECOVERABLE` D / CR the rail's clearing D |
|  | `dispute-attribution:<id>` — iff the counterparty bears a posted share | DR the counterparty (payable or wallet) S / CR `CHARGEBACK_RECOVERABLE` S |
| `WON` | `dispute-won:<id>` | DR the rail's clearing D / CR `CHARGEBACK_RECOVERABLE` D |
|  | `dispute-restoration:<id>` — iff a posted share | DR `CHARGEBACK_RECOVERABLE` S / CR the counterparty S |
| `LOST` / `ACCEPTED` | `dispute-loss:<id>` — iff an excess | DR `DISPUTE_COSTS` E / CR `CHARGEBACK_RECOVERABLE` E |
| any charged-back stage | `dispute-fee:<id>` — once, when the PSP reports its fee | DR `DISPUTE_COSTS` F / CR the rail's clearing F |
| (standing; headroom freed) | `dispute-reattribution:<id>:<cause>` — a capture landed, a counted refund failed, or a sibling chargeback won | DR the counterparty x / CR `CHARGEBACK_RECOVERABLE` x (or CR `DISPUTE_COSTS` after a loss) |

**The split is judged when the chargeback arrives**, under the attempt row lock both money
paths take: the counterparty bears `S = min(D, captured − non-failed refunds − standing
attributions)` — posted to its account, or **parked** in the recoverable when its account takes
no postings — and the rest, the excess `E`, is value the network took that the platform had
already returned (or, for an attempt that has not captured, never credited). A refund is judged
by the same arithmetic the other way. A chargeback is recorded at once whatever the capture's
state, and every event that frees headroom — a capture landing, a counted refund failing, a
sibling chargeback won — re-attributes the standing excess to the counterparty, so the split is
always the one a chargeback arriving now would take. The stage facts (`ChargebackReceived`, `DisputeResolved`) name the split's accounts —
`counterpartyAccountId`, `recoverableAccountId` — never an amount. A counterparty below zero is
merchant debt or a receivable from the customer, counted by `finapp.ledger.negative.positions`
and never absorbed. **The lock order**: the attempt first, then the counterparty's account
share-locked BEFORE the stage's first posting, then the balance rows the postings touch — the
order every hold keeps (the account before any balance row). Posting the external fact first
and reaching the counterparty only after deadlocked a win against a refund of another payment to
the same counterparty (`P7-TST-001`'s multi-rail storm, a `40P01`). *(The Phase 7 → 8
transition's gate added the balance rows' own order across entries: a stage posting several
entries - a loss that first reports the fee - takes the platform's three rows (the rail's
clearing, the recoverable, the costs) in the projection's order before its first posting, since
the loss-then-fee order reached back to the clearing every chargeback takes first.)*

**Answering a chargeback** (`P7-TSK-014`, ADR-0061 §7; `DisputeResponses`) — the responder's
answer is a `DisputeResponse`, dispatched through the card PSP, and it moves **no stage and no
money**: `SUBMITTED` means the PSP took it, and the network's own `under_review`
(`REPRESENTED`), `accepted`, `won` and `lost` still arrive by notification, each posting what
this section's table says.

```
DISPATCHED ──> SUBMITTED
    │    └───> FAILED     (DECLINED; PROVIDER_UNAVAILABLE only on the first send)
    └──> UNKNOWN ──> SUBMITTED
             └─────> FAILED
```

The list is exactly `DisputeResponseStatus.permittedTransitions()`, generated into `V022`'s
every-writer trigger; born `DISPATCHED` with our reference; `SUBMITTED` and `FAILED` terminal;
one LIVE (non-`FAILED`) answer per dispute for every writer. The rules an answer and an upload
obey, judged under the attempt lock and then the dispute's (every delivery's order):

| Rule | Answer |
|---|---|
| The dispute is not `CHARGED_BACK` (an inquiry, represented, resolved) | `payments.DisputeNotRespondable`, nothing written (`INV-LIFE-04`) |
| A live answer already stands (the evidence set froze with it) | `payments.DisputeAlreadyAnswered` |
| The network's `respond_by` has passed | `payments.DisputeDeadlinePassed` — the platform refuses its OWN late dispatch; the outcome stays the network's |
| A representment with no document / a sixth document | `payments.DisputeEvidenceRequired` / `payments.DisputeEvidenceLimitReached` |
| An operator on a payment with a merchant | `payments.DisputeAnsweredByItsMerchant` — the operator acts only where the payment credited a customer wallet |

The deadline is the network's `respondBy`, recorded once with the chargeback (`NULL → value`,
the first statement standing; an inquiry's own answer-by date is dropped at the door), and
`finapp.payments.dispute.deadline.near` counts the chargebacks near or past it with no answer
the PSP took — the alarm, never a decision. A timed-out answer is honestly `UNKNOWN` until the
sweep asks the PSP by our reference, re-sending the SAME request where the PSP never saw it.

## 7. The financial flows, per rail and instrument

`business operation → payment state → ledger effect → external rail → settlement → reconciliation`.
**Where Phase 7 only orchestrates**, the ledger effect column says so.

| Operation | State | Ledger effect | Rail | Settlement | Reconciliation |
|---|---|---|---|---|---|
| Card top-up / card checkout | authorize → capture | None at authorization. Capture: DR `SETTLEMENT_CLEARING` / CR wallet, or the payable gross with the fee split (ADR-0050) | Card PSP | Deferred via clearing (Phase 8) | Capture entry ↔ PSP references ↔ clearing references |
| Card void | `VOIDED` | **None** — orchestration only: nothing was captured | Card PSP | None | Void reference ↔ PSP |
| Card clearing notice | evidence recorded (shipped `P7-TSK-005`: one `clearing_record` per capture, append-only) | **None** — clearing agrees an obligation, it does not move money (`INV-SET-01`) | Card PSP | Phase 8 | Clearing references stored (`acquirer_reference`, `network_transaction_id`) |
| Card refund | refund `COMPLETED` | DR wallet or payable (ADR-0054) / CR `SETTLEMENT_CLEARING` | Card PSP | Phase 8 | Refund entry ↔ PSP reference |
| Chargeback | dispute `CHARGED_BACK` (the stage shipped `P7-TSK-012`: from notifications alone, one row per provider dispute reference; its posting shipped `P7-TSK-013`: two entries, the external fact then the attribution, the split judged under the attempt lock) | CR `SETTLEMENT_CLEARING` D; DR counterparty share; DR `CHARGEBACK_RECOVERABLE` excess (ADR-0061 §4) — per account; as entries, DR recoverable D / CR clearing D, then DR counterparty S / CR recoverable S (§6's table) | Card PSP / network | Netted by the PSP (Phase 8) | Dispute reference (stored, unique per provider) ↔ stage entries (their `reference` is the dispute id) |
| Dispute won / lost | `WON` / `LOST` (shipped `P7-TSK-013`) | Won: the exact inverse of the principal lines, every account netting to zero. Lost: excess written off to `DISPUTE_COSTS`, the counterparty's share standing as its debt | Card PSP | Phase 8 | As above |
| Dispute fee | reported with or after the chargeback (shipped `P7-TSK-013`) | DR `DISPUTE_COSTS` / CR `SETTLEMENT_CLEARING` — the platform bears it in Phase 7 | Card PSP | Netted by the PSP | The fee entry ↔ the dispute |
| Pay-in to a wallet (pay-by-bank) | push `EXECUTED` | DR `INSTANT_CLEARING` / CR wallet | Instant scheme | Scheme cycle (Phase 8) | End-to-end reference ↔ scheme reference |
| Pay-in to a checkout | push `EXECUTED` | DR `INSTANT_CLEARING` gross / CR payable gross; DR payable fee / CR `FEE_REVENUE` fee | Instant scheme | Scheme cycle | As above, plus the order |
| Return payment (refund of a pay-in) | refund `COMPLETED` (shipped `P7-TSK-010`: the refund command per `refundMode`, `V018`'s per-model bound, `ReturnResolution`'s partitioned sweep) | DR wallet or payable / CR `INSTANT_CLEARING` (merchant-bound: the capture's four-line inverse, ADR-0054) | Instant scheme | Scheme cycle | Return reference (ours, on the refund row) ↔ original (the attempt's scheme reference, cited on the wire); the return's own scheme reference lands at `COMPLETED` |
| Withdrawal | withdrawal `COMPLETED` | Release the hold; DR wallet / CR `INSTANT_CLEARING` | Instant scheme | Scheme cycle | End-to-end reference ↔ scheme reference |
| Wallet pays a checkout | book `EXECUTED` (shipped `P7-TSK-011`: born EXECUTED in the confirmation's one transaction, availability under the wallet's lock, `book|4` routed) | DR wallet gross / CR payable gross; DR payable fee / CR `FEE_REVENUE` fee — one transaction | None (internal) | None | The entry is the whole record |
| Book refund of a wallet payment | refund `COMPLETED` (shipped `P7-TSK-011`: dispatch and outcome one transaction, the reference a `bke-` platform marker) | DR payable / CR wallet (fee share per ADR-0054; the counterpart is the intent's own debit wallet) | None | None | The entry |
| Wallet to wallet | transfer `COMPLETED` | DR source wallet / CR destination wallet (Phase 4) | None | None | The entry |

Every entry balances per currency (`INV-LED-01`); every posting is keyed by its operation so a
duplicate outcome cannot post twice; nothing posts on a rail before that rail's own confirmation.
