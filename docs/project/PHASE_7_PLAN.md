# Phase 7 — Cards, Wallets, A2A and Instant Payments

Written by the Phase 6 → 7 transition (2026-09-24). Decisions in
[ADR-0059](../adr/ADR-0059-payment-rails-capabilities-and-finality.md) (a rail declares its
capabilities; finality is modelled per rail; the attempt's machine follows the rail's
interaction model), [ADR-0060](../adr/ADR-0060-rail-routing-pinned-and-explainable.md)
(routing is a versioned policy, decided once per payment, pinned and explainable),
[ADR-0061](../adr/ADR-0061-disputes-and-chargeback-accounting.md) (disputes and chargeback
accounting) and [ADR-0062](../adr/ADR-0062-account-to-account-and-instant-payments.md)
(account-to-account and instant payments). The domain-facing statement of the lifecycles is
[`RAIL_AND_DISPUTE_LIFECYCLES.md`](../domain/RAIL_AND_DISPUTE_LIFECYCLES.md). Until Phase 7's
first task lands, **nothing in this plan is implemented**: every statement is the decided design,
corrected by the tasks that build it.

## 1. Objective

**Extend the platform from one payment provider to several payment rails with materially
different lifecycles, timing and failure semantics, and introduce disputes, while keeping the
existing payment infrastructure, the ledger, the merchant and checkout boundaries and the
distributed-system guarantees exactly as they are.** Concretely:

- the card rail becomes a declared rail, with its void and its clearing evidence;
- an instant credit-transfer rail, simulated behind a provider-neutral port, carries wallet
  withdrawals to external bank accounts, pay-by-bank pay-ins and return payments;
- the platform's own wallet becomes a rail a customer pays a checkout with;
- routing chooses the rail by a versioned, pinned, explainable policy;
- chargebacks on card payments run a dispute lifecycle whose postings can never debit a
  merchant twice.

Phase 5 proved money can enter and leave against one unreliable third party; Phase 6 changed
who the money is for. Phase 7 changes **how it travels** and holds everything else fixed.

## 2. Why this phase is shaped by decisions already taken

- **The first rail was chosen to be the maximal one** (ADR-0049). The card-style two-step flow
  exercises every lifecycle distinction, so a push rail specialises the abstraction rather than
  widening it. Phase 7 builds the rail abstraction from two data points, as ADR-0049 §4 said.
- **The payment machinery is extended, not reopened.** Intent, attempt, refund, evidence, the
  webhook door, the sweep and the dispatch-before-call discipline (ADR-0045…0047) stay. What is
  new is the rail they run on and the machine each interaction model needs.
- **Every outbound push reuses the payout's disciplines** (ADR-0057): hold-then-dispatch, the
  send permit, and a failure concluded only on knowledge. The Phase 6 → 7 transition brought the
  send permit to the refund (payments `V009`), so every re-sending flow on the platform now
  carries one before a second rail inherits the shape.
- **Bank details never enter** (ADR-0056 §7). The payout destination's grant exchange is the
  template for every external account the platform pays or is paid from (ADR-0062 §2).
- **The ledger is the only balance authority** (ADR-0002, ADR-0009, ADR-0042). A wallet balance,
  a pending balance and a wallet transaction are views over postings, never a second store.

## 3. Bounded contexts and modules

| Context | Module | In Phase 7 |
|---|---|---|
| 9 Payments | `payments` | The rail port and descriptor, per-model attempt machines, routing, the card void and clearing evidence, pay-ins, withdrawals, returns, and disputes |
| 29 **Disputes** (new) | `payments` (merged) | The dispute aggregate and its accounting; merged because the chargeback bound and the refund bound are one arithmetic under one lock (ADR-0061 §1). Split trigger recorded (`MODULE_ARCHITECTURE.md` M11) |
| 10 Payment Methods | `paymentmethods` | The `BANK_ACCOUNT` instrument through the grant exchange; card tokens unchanged (`INV-PAY-02`) |
| 6 Wallet / 5 Accounts | `accounts` | Unchanged product; the wallet stays in `accounts` — ADR-0042's split trigger evaluated and not met (ADR-0059 §6) |
| 7 Ledger | `ledger` | New operational purposes — `INSTANT_CLEARING`, `CHARGEBACK_RECOVERABLE`, `DISPUTE_COSTS` — each with the task that first posts to it |
| 11 Checkout, 12 Merchant | `checkout`, `merchant` | Consumed: pay-by-bank and wallet payments at checkout credit the payable through the existing capture composition; chargebacks debit it through a dispute composition `app` implements |

**Build-graph edges unchanged.** `payments → ledger` remains the only money edge; `payments`
still cannot see `merchant`, `checkout`, `accounts` or `paymentmethods`, and every new join
(the dispute's counterparty lines, the pay-in's credit account, the bank instrument's reference)
is a port `app` implements — the `CaptureComposition` shape. **No card issuing** (ADR-0059 §5):
Cardholder, Card Account, Physical Card and Virtual Card are external.

## 4. Aggregates and commands

| Aggregate | Module | Commands | Idempotency |
|---|---|---|---|
| `PaymentIntent` | `payments` | create; confirm (now routed); cancel | Create keyed per payer; confirm converges by machine; the routing decision is pinned in confirm's Tx1 |
| `PaymentAttempt` (three machines) | `payments` | card: authorize, capture, **void**; push: initiate / execute; book: execute | Every provider operation keyed by our minted reference (`INV-PAY-04`); book execution one transaction |
| `RoutingPolicy` / `RoutingDecision` | `payments` | create version, set rail availability (operator); decide (the platform) | Versions immutable; a decision is one row per payment, frozen |
| `Refund` | `payments` | refund — provider refund, return payment or book refund by the rail's `refundMode` | Keyed (`payment.refund`); send permit (`V009`) |
| `Withdrawal` | `payments` | withdraw (customer); outcomes by the platform | Keyed per customer; hold-then-dispatch; send permit |
| `Dispute` | `payments` | opened, charged back, represented, resolved (by notification); submit evidence and accept (merchant or operator) | Unique on (provider, provider dispute reference); stages conditional; each posting keyed by dispute and stage |
| `PaymentMethod` (`BANK_ACCOUNT`) | `paymentmethods` | register via grant, detach | Register keyed per party; detach converges |

## 5. The lifecycles

Stated in full in `RAIL_AND_DISPUTE_LIFECYCLES.md`:
- the two-step attempt: Phase 5's seven states, plus `VOID_DISPATCHED`, `VOID_UNKNOWN` and
  `VOIDED`;
- the push attempt: `AWAITING_PAYER`, `EXECUTION_DISPATCHED`, `EXECUTION_UNKNOWN`, `EXECUTED`,
  `FAILED`;
- the book attempt: `EXECUTED`, `FAILED`;
- the withdrawal: `DISPATCHED`, `UNKNOWN`, `COMPLETED`, `FAILED`;
- the dispute: `INQUIRY`, `CHARGED_BACK`, `REPRESENTED`, `WON`, `LOST`, `ACCEPTED`, `CLOSED`.

Every machine gets the three-layer enforcement: an exhaustive aggregate sweep; a generated
schema `CHECK` and a transition trigger binding every writer, keyed on the interaction model for
the attempt; and an append-only history.

## 6. Financial invariants Phase 7 must preserve

The in-scope set is **whatever the catalogue marks `Phase: 7`, token-parsed** — the standing
rule, never this list. At planning time that is **ten**:

- `INV-RAIL-01…04` — new: capabilities acted on, never names; routing deterministic, pinned and
  never re-routed after an ambiguous dispatch; bank identifiers and aliases never enter; per-rail
  clearing positions.
- `INV-DSP-01…03` — new: refunds and chargebacks together bounded by the capture; each stage
  posts once and a resolution mirrors what it resolves; dispute evidence least-privilege,
  encrypted, access-audited.
- `INV-REV-03` — reversal on an irrevocable rail rejected by the domain. Catalogued at
  initiation, subjectless until now (ADR-0049 §3).
- `INV-SET-01` — internal completion is not settlement, per rail and with no exception
  (ADR-0059 §4).
- `INV-HIST-04` — its routing element: the decision records the policy version that produced it.

The platform stands at **101 invariants**. The standing families (`INV-LIFE-*`, `INV-IDEM-*`,
`INV-CON-*`, `INV-BAL-*`, `INV-LED-*`, `INV-PAY-*`, `INV-MER-*`, `INV-AUD-*`) apply as always;
the new machines are `INV-LIFE-01`'s next subjects, and `INV-MER-07` is amended by ADR-0061 §5.

## 7. Multi-instance architecture

Ten instances execute everything concurrently. Every contended decision names its PostgreSQL
arbiter:

| Contention | Arbiter |
|---|---|
| Duplicate confirmations routing one intent | The intent's conditional `REQUIRES_CONFIRMATION → PROCESSING`; the decision row is born in the winner's Tx1, and the one-live-attempt index (`V003`) refuses a second attempt |
| A fallback racing an ambiguous dispatch | None can occur: a decision advances only on `NOTHING_SENT` or an eligibility refusal, both before any send (`INV-RAIL-02`), and the attempt is `FAILED(PROVIDER_UNAVAILABLE)` before the next is born |
| Rail availability changing mid-routing | The availability row is read inside the decision's transaction and recorded with it; a change after the decision reroutes nothing in flight |
| Concurrent wallet debits (withdrawal, wallet payment, transfer, refund of a top-up) | The wallet's ledger account `FOR UPDATE`, availability derived in-lock with holds (`INV-BAL-04`, `INV-CON-01`) — the transfer's and the refund's existing arbiter |
| Concurrent wallet credits (pay-in, top-up capture, transfer in) | None needed for correctness: credits raise no availability question; each is keyed by its own operation and posted once |
| A hold released while another balance-affecting operation runs | The account lock both take (`HoldService` release and placement, the transfer's lock, the posting's key-share); the release's conditional makes a second release converge |
| Duplicate instant-payment sends, a takeover and the inquiry sweep | Our end-to-end reference, stored before the send (`INV-PAY-04`); the send permit; conditional outcomes on the locked row |
| Duplicate rail callbacks (instant confirmations, card clearing, dispute notifications) | The inbox's primary key (`INV-IDEM-04`), then conditional transitions on the locked row |
| A chargeback racing a refund on one payment | The attempt row's `FOR UPDATE`, taken by both before the combined bound is judged (`INV-DSP-01`) |
| A dispute stage racing a refund of ANOTHER payment to the same counterparty | The counterparty's account, share-locked before the stage's first posting - the account every hold takes before any balance row *(added by `P7-TST-001`, whose storm met the missing lock as a `40P01` on a win)* |
| Duplicate chargeback notifications | `UNIQUE (provider, provider_dispute_reference)`, the stage's conditional transition, and the posting key per stage (`INV-DSP-02`) |
| A pay-in confirmation racing its initiation's expiry | Nothing expires by our clock alone: the payer PSP's answer decides, and a late execution lands (`INV-MER-06`'s second rail) |
| Concurrent sweeps (attempts, refunds, withdrawals, initiations) | None needed — the registered leaderless pattern: queries idempotent, writes conditional, sends permitted |

Nothing lives in process memory; no routing input is instance-local (ADR-0060 §4).

## 8. Data architecture

Every table arrives with the task that creates it, with its `DATA_CLASSIFICATION.md` §4 rows in
the same change (the `refund.dispatch_key` lesson, and payments `V009`'s own practice):

- `payments.payment_attempt` gains `rail` and `interaction_model` (frozen at birth), and the
  card void's columns.
- `payments.routing_policy_version`, `payments.rail_availability`, `payments.routing_decision`
  and its steps.
- `payments.withdrawal` and its history.
- `payments.dispute`, its stage history, and its evidence (encrypted).
- `paymentmethods.payment_method` gains the `BANK_ACCOUNT` kind: an opaque reference, a display
  suffix and the confirmation-of-payee result, never an account number (`INV-RAIL-03`).
- The ledger chart gains `INSTANT_CLEARING`, `CHARGEBACK_RECOVERABLE` and `DISPUTE_COSTS`, each
  with its constraint-regenerating migration (the `V011`/`V012` precedent).
- The `payment_intent.wallet_account_id` rename (a recorded debt) lands with the first
  migration that recreates the intent's every-writer trigger *(landed: `P7-TSK-002`,
  payments `V012`)*.

Forward-only migrations (ADR-0011); explicit SQL (ADR-0033); money as minor units, currency
and scale (ADR-0003).

## 9. API architecture

Rail-agnostic payments with rail-specific detail objects (`DELIVERY_PLAN.md` §7):

| Operation | Door | Idempotency | Authorization | Asynchronous behaviour |
|---|---|---|---|---|
| Create / confirm a payment (instrument: card, bank or wallet) | Customer session | Keyed / converges | Own intent; step-up by policy | Routed at confirm; card and pay-by-bank complete asynchronously (`PROCESSING`), wallet synchronously |
| Void (reverse) an authorization | Customer or operator | Converges by machine | Own intent / `PAYMENT_REFUND` | Asynchronous; refused as `payments.ReversalNotSupported` on an irrevocable rail |
| Refund | Operator (`PAYMENT_REFUND`) or merchant | Keyed | Reasoned | Per `refundMode`: provider refund, return payment or book refund |
| Register a bank account (grant) | Customer session | Keyed | Own party; step-up when a factor is enrolled | The grant exchange runs holding no connection |
| Withdraw to a bank account | Customer session | Keyed per customer | Own wallet and instrument; step-up when enrolled | Hold committed; `DISPATCHED` → final on the scheme's answer |
| Routing policy versions; rail availability | Operator (`PAYMENT_ROUTING_ADMINISTER`) | Keyed / converges | Reasoned, audited | — |
| A payment's routing explanation | Operator | Read | `PAYMENT_ROUTING_ADMINISTER` | — |
| Disputes: list, read, submit evidence, accept | Merchant key (tenant-scoped) or operator | Keyed | `INV-MER-01`; operator permission | Evidence submission is a dispatch-before-call operation |
| Rail callbacks: instant confirmations, card clearing, dispute notifications | Signed webhook door, per rail key | Inbox | `SIGNED_CALLBACK` | Evidence first, then conditional effect |

Every financial command states its idempotency, authorization, state validation, error
semantics and asynchronous behaviour in its task. New error codes join `ERROR_CONTRACT.md` with
the task that raises them.

## 10. Event architecture

Terminal facts publish (ADR-0044's doctrine): `RailSelected`, `PaymentClearedOnRail`,
`PaymentExecuted` (push and book), `AuthorizationVoided`, `WithdrawalInitiated`,
`WithdrawalCompleted`, `WithdrawalFailed`, `DisputeOpened`, `ChargebackReceived`,
`DisputeEvidenceSubmitted`, `DisputeResolved`. Every event carries the full envelope
(`INV-EVT-03`); none carries an account identifier, an alias, a token or an amount beyond the
recorded fee-events precedent; `UNKNOWN` publishes nothing.

## 11. Security and audit

- **PCI scope unchanged**: card data still stops at the tokenisation boundary (`INV-PAY-02`);
  bank data and aliases stop at the grant exchange (`INV-RAIL-03`).
- **Strong customer authentication**: the platform's step-up (`MULTI_FACTOR` when a factor is
  enrolled — the conditional `P4-TSK-007` pattern) on bank-account registration and withdrawal;
  the payer's own SCA at the payer PSP for pay-by-bank. Value-based step-up and payment limits
  are Phase 13's (the recorded seams).
- **Privileged acts** — routing versions, rail availability, voids by an operator, dispute
  acceptance and evidence on behalf — each behind a named permission, reasoned, audited, with a
  negative test.
- **Dispute evidence** encrypted under a key held outside the database, every access audited
  (`INV-DSP-03`).
- **Service authentication** per rail: each adapter's credentials and webhook key are confined
  credentials (the `P5-TSK-002` mechanism), pinned by `ConfinedCredentialVariablesTest`.

## 12. Reconciliation

Every link is a stored identifier, both directions (`RECONCILIATION_MODEL.md`), and what each
rail needs for Phase 8 is preserved:

| Chain | References preserved |
|---|---|
| Card transaction ↔ processor ↔ network ↔ settlement | Our authorization, capture, void and refund references; the PSP's references; the network's clearing references (acquirer reference and network transaction identifiers) from the clearing notification; the capture's journal entry |
| Wallet transaction ↔ ledger | The journal entry keyed by the operation (`payment-capture:`, `wallet-withdrawal:`, `transfer:`, `payment-refund:`); a wallet statement line is a view over those |
| A2A payment ↔ rail ↔ settlement | Our end-to-end reference; the scheme's transaction reference; the settlement cycle the scheme reports (Phase 8 ingests it); the `INSTANT_CLEARING` entry |
| Instant payment ↔ rail transaction ↔ settlement | As A2A, plus the directory resolution's opaque destination reference |
| Dispute ↔ chargeback ↔ settlement | The provider dispute reference, each stage's entry, and the dispute fee's entry |

`SETTLEMENT_CLEARING` stays the card rail's, `INSTANT_CLEARING` the scheme's
(`INV-RAIL-04`). Settlement file ingestion is Phase 8's and is not built here.

## 13. Testing strategy

Per `TESTING.md`'s tiers: hermetic unit tests for every machine and descriptor; provider and rail
contract tests over the simulated card PSP and the simulated instant scheme; database tests for
every transaction boundary, constraint and race; ledger invariant tests, meaning trial balance
per currency, per-rail clearing reconciliation and the combined dispute bound; security tests
(the tenancy battery extended to the dispute routes, permission negatives, the needle over new
sinks); and mutation probes recorded in `MUTATION_TESTING.md` for every `Phase: 7` invariant.

**The four scenarios the gate names, each a counted test, not an argument:**

1. **The provider succeeds and the response is lost** — for a card capture, an instant
   withdrawal and a pay-in: `UNKNOWN`, resolved by query or callback, exactly one entry.
2. **Two instances attempt the same operation** — ten-way races on confirm, withdraw, refund,
   void and dispute stage application: one effect, counted in the tables.
3. **The same callback arrives many times** — instant confirmations, clearing notifications and
   chargeback notifications, ten deliveries each under distinct and identical event ids: one
   effect.
4. **A hold is released concurrently with another balance-affecting operation** — a withdrawal's
   completion racing a wallet payment and a transfer on the same wallet: available balance never
   negative, every posting explained.

## 14. Failure scenarios

Each has a test or a documented, accepted rationale (exit criterion 4):

1. A rail is unavailable before dispatch: routing falls back on `NOTHING_SENT` only, recorded.
2. A rail becomes unavailable mid-flight: the attempt is `UNKNOWN` on its rail; no fallback.
3. The instant scheme times out: `EXECUTION_UNKNOWN`; the inquiry past the scheme's deadline is
   authoritative.
4. A takeover re-sends a withdrawal and the connection is refused: nothing concluded (the permit).
5. Card clearing arrives days later, twice: recorded once, no ledger effect.
6. A void is attempted on an instant payment: refused by the domain, nothing written or sent.
7. A chargeback arrives on an already-refunded payment: the excess goes to
   `CHARGEBACK_RECOVERABLE`, the merchant is not debited twice.
8. A duplicate chargeback notification: one effect.
9. A representment is submitted after resolution: refused.
10. A chargeback arrives on a payment whose counterparty account is no longer postable (a closed
    wallet): its share is parked in `CHARGEBACK_RECOVERABLE`, visible, never refused (the Phase
    6 → 7 transition's C2 finding, applied forward).
11. A pay-in executes after its checkout session expired: `COMPLETED_LATE`, never dropped.
12. A pay-in confirmation names no initiation: evidence retained, parked in
    `SUSPENSE_UNMATCHED` if it carries money, alerted.
13. The wallet is debited by a withdrawal and a wallet payment at once: exactly the affordable
    set proceeds.
14. A merchant is suspended while a pay-by-bank payment is awaiting the payer: the payment admitted
    before the suspension lands (the Phase 6 rule).
15. An authorization rests with nothing to capture it: the sweep chains the capture only for
    auto-capture intents (the transition's stranded-chain leg, kept correct when manual capture
    arrives).

## 15. Observability

| Series | What it answers |
|---|---|
| `finapp.payments.rail.outcome` | Outcomes by rail and judgement (bounded tags) |
| `finapp.payments.rail.latency` | Provider latency by rail and operation |
| `finapp.payments.routing.decision` | Decisions by chosen rail and rejection reason |
| `finapp.payments.withdrawal.unknown.active` | Stuck withdrawals: every `UNKNOWN`, and `DISPATCHED` past the bound |
| `finapp.payments.withdrawal.unknown.age` | The oldest one's age, in the payout's shape |
| `finapp.payments.dispute` | Disputes by stage and outcome |
| `finapp.payments.dispute.deadline.near` | Disputes whose respond-by date is near |
| `finapp.payments.dispute.response.unknown.active` | Stuck dispute answers: every `UNKNOWN`, and `DISPATCHED` past the sweep's bound |
| `finapp.payments.dispute.response.unknown.age` | The oldest one's age, in the withdrawal's shape |
| `finapp.ledger.negative.positions` | Counterparties below zero after a chargeback (merchant debt, customer receivable) |

The chargeback ratio per merchant is an operator report, never a metric tag (ADR-0018).
Everything is eager, NaN never zero, and aggregated with `max()` for fleet-wide gauges.

*(The two `dispute.response.unknown` rows were added at `P7-TSK-015`'s design: `P7-TSK-014` gave
the dispute answer a modelled `UNKNOWN` state after this table was written, and `INV-LIFE-03`'s own
Verify clause asks for an "unknown-state age metric" for it. `finapp.payments.dispute` is one
gauge tagged `stage` — the terminal stages are the outcomes — and `rail.outcome` counts acting
judgements tagged `rail`, `type` and `outcome`.)*

## 16. Milestones

| Milestone | Items | Acceptance |
|---|---|---|
| M7.1 Rail foundations | `P7-TSK-001`…`-003` | The card rail runs as a declared rail with no behaviour change (full battery green); the per-model machines enforced at three layers; routing decisions pinned and recomputable |
| M7.2 The card rail completed | `P7-TSK-004`, `-005` | A void is performed on the revocable rail; clearing evidence recorded once, with no ledger effect |
| M7.3 External accounts and the instant rail | `P7-TSK-006`…`-008` | A withdrawal is paid out over the simulated scheme, final on acceptance, `INSTANT_CLEARING` reconciled, and its reversal refused (`INV-REV-03`'s first subject) |
| M7.4 Pay-ins by bank | `P7-TSK-009`, `-010` | Pay-by-bank funds a wallet and a checkout; a return payment refunds it |
| M7.5 The wallet as an instrument | `P7-TSK-011` | A checkout paid from a wallet in one transaction, and refunded by a book movement |
| M7.6 Disputes | `P7-TSK-012`…`-014` | A chargeback lifecycle end to end with the combined bound, a representment won and lost, and duplicate notifications with one effect |
| M7.7 Observability and demonstration | `P7-TSK-015`, `P7-TST-001`, `P7-TST-002` | The meters live on a running instance; the multi-rail storm and the dispute battery green and probed |
| M7.8 The gate | `P7-DOC-001` | The Phase 7 exit review |

## 17. What Phase 7 must NOT implement

Settlement file ingestion and settlement matching (Phase 8); FX and cross-currency rails (Phase
9); fraud scoring, velocity limits and value-based step-up (Phase 13); card issuing (outside the
programme, ADR-0059 §5); recall requests and batch credit-transfer rails with return windows
(recorded, ADR-0062); merchant reserves and dispute-fee pass-through (recorded, ADR-0061);
moving the merchant payout onto the push rail (recorded, ADR-0062 §7); real connectivity to any
network (the programme's non-goal).

## 18. Risks

- **A lowest-common-denominator abstraction.** Mitigated by three machines keyed on the
  interaction model and a descriptor recorded with every decision (ADR-0059).
- **Reversal on an irrevocable rail.** Mitigated by the capability check before any write or
  send (`INV-REV-03`), probed.
- **Chargeback accounting that double-debits.** Mitigated by the combined bound under one lock
  and the per-stage posting keys (`INV-DSP-01`, `-02`), probed and stormed.
- **Routing that cannot be explained.** Mitigated by the pinned decision and its recomputation
  test (`INV-RAIL-02`).
- **A second balance authority for wallets.** Mitigated by construction: no balance column, and
  the pending figure is a view over in-flight payments (ADR-0059 §6).
- **Scope.** Eighteen items across eight milestones; each rail lands as its own vertical slice,
  so a milestone that slips does not strand the others.
