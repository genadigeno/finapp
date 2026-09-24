# Rail and Dispute Lifecycles

Written by the Phase 6 → 7 transition (2026-09-24), the `PAYMENT_LIFECYCLES.md` and
`CHECKOUT_MERCHANT_LIFECYCLES.md` precedent: the document that names a phase's model is written
before the phase's first task, from the decisions in ADR-0059…0062, and corrected by the tasks
that implement it. **Until Phase 7's first task lands, nothing in this document is implemented**;
every statement is the decided design.

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

**Two-step (card)** — Phase 5's machine, extended by the void:

```
AUTH_DISPATCHED ─> AUTH_UNKNOWN ─> AUTHORIZED ─> CAPTURE_DISPATCHED ─> CAPTURE_UNKNOWN ─> CAPTURED
       │                 │              │                 │                    │
       └─────────────────┴──> FAILED    ├─> VOID_DISPATCHED ─> VOID_UNKNOWN ─> VOIDED
                                        │                 └──────────────────> VOIDED
                                        └─> FAILED
```

**Push (instant, A2A)**:

```
AWAITING_PAYER ──> EXECUTION_DISPATCHED ──> EXECUTION_UNKNOWN ──> EXECUTED
      │                      │                     │
      └──────────────────────┴─────────────────────┴──> FAILED
```

`AWAITING_PAYER` exists only for a pay-in: the payer authorizes at the payer PSP, and the attempt
waits for that PSP's answer — never for our clock. A withdrawal and a return payment are born
`EXECUTION_DISPATCHED`.

**Book (wallet)**: born `EXECUTED` or `FAILED` inside the confirmation's transaction.

## 4. The routing decision (ADR-0060)

`policy version` + `stored inputs` → `ordered candidates, each with its reason` → `CHOSEN`.
Pinned before dispatch, frozen, recomputable. It advances only on `NOTHING_SENT` or an
eligibility refusal, and never after an ambiguous dispatch.

## 5. The withdrawal (ADR-0062 §6)

```
DISPATCHED ──> UNKNOWN ──> COMPLETED
     │             │
     └─────────────┴──> FAILED
```

The dispatch holds the amount on the wallet account in its lock, commits `DISPATCHED` with our
end-to-end reference and a send permit, and only then sends. `COMPLETED` releases and posts;
`FAILED` releases only on the scheme's own refusal, or on a first send's refused connection that
no later permit has overtaken, or past the scheme's deadline under the permit rule. An accepted
withdrawal is irrevocable: a reversal of it is refused by the domain (`INV-REV-03`).

## 6. The dispute (ADR-0061 §2)

```
INQUIRY ──> CHARGED_BACK ──> REPRESENTED ──> WON
   │             │                 └────────> LOST
   │             ├──────────────────────────> LOST
   │             └──────────────────────────> ACCEPTED
   └──> CLOSED  (an inquiry that never became a chargeback)
```

Entered at `INQUIRY` or directly at `CHARGED_BACK`. Terminal: `WON`, `LOST`, `ACCEPTED`,
`CLOSED`.

## 7. The financial flows, per rail and instrument

`business operation → payment state → ledger effect → external rail → settlement → reconciliation`.
**Where Phase 7 only orchestrates**, the ledger effect column says so.

| Operation | State | Ledger effect | Rail | Settlement | Reconciliation |
|---|---|---|---|---|---|
| Card top-up / card checkout | authorize → capture | None at authorization. Capture: DR `SETTLEMENT_CLEARING` / CR wallet, or the payable gross with the fee split (ADR-0050) | Card PSP | Deferred via clearing (Phase 8) | Capture entry ↔ PSP references ↔ clearing references |
| Card void | `VOIDED` | **None** — orchestration only: nothing was captured | Card PSP | None | Void reference ↔ PSP |
| Card clearing notice | evidence recorded | **None** — clearing agrees an obligation, it does not move money | Card PSP | Phase 8 | Clearing references stored |
| Card refund | refund `COMPLETED` | DR wallet or payable (ADR-0054) / CR `SETTLEMENT_CLEARING` | Card PSP | Phase 8 | Refund entry ↔ PSP reference |
| Chargeback | dispute `CHARGED_BACK` | CR `SETTLEMENT_CLEARING` D; DR counterparty share; DR `CHARGEBACK_RECOVERABLE` excess (ADR-0061 §4) | Card PSP / network | Netted by the PSP (Phase 8) | Dispute reference ↔ stage entries |
| Dispute won / lost | `WON` / `LOST` | Won: the exact inverse of the principal lines. Lost: excess written off to `DISPUTE_COSTS` | Card PSP | Phase 8 | As above |
| Pay-in to a wallet (pay-by-bank) | push `EXECUTED` | DR `INSTANT_CLEARING` / CR wallet | Instant scheme | Scheme cycle (Phase 8) | End-to-end reference ↔ scheme reference |
| Pay-in to a checkout | push `EXECUTED` | DR `INSTANT_CLEARING` gross / CR payable gross; DR payable fee / CR `FEE_REVENUE` fee | Instant scheme | Scheme cycle | As above, plus the order |
| Return payment (refund of a pay-in) | refund `COMPLETED` | DR wallet or payable / CR `INSTANT_CLEARING` | Instant scheme | Scheme cycle | Return reference ↔ original |
| Withdrawal | withdrawal `COMPLETED` | Release the hold; DR wallet / CR `INSTANT_CLEARING` | Instant scheme | Scheme cycle | End-to-end reference ↔ scheme reference |
| Wallet pays a checkout | book `EXECUTED` | DR wallet gross / CR payable gross; DR payable fee / CR `FEE_REVENUE` fee — one transaction | None (internal) | None | The entry is the whole record |
| Book refund of a wallet payment | refund `COMPLETED` | DR payable / CR wallet (fee share per ADR-0054) | None | None | The entry |
| Wallet to wallet | transfer `COMPLETED` | DR source wallet / CR destination wallet (Phase 4) | None | None | The entry |

Every entry balances per currency (`INV-LED-01`); every posting is keyed by its operation so a
duplicate outcome cannot post twice; nothing posts on a rail before that rail's own confirmation.
