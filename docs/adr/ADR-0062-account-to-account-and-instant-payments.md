# ADR-0062 — Account-to-account payments run on a provider-neutral push rail; bank details and aliases never enter; an instant payment is final on acceptance and settled on the scheme's cycle

Status: Proposed (2026-09-24, the Phase 6 → 7 transition)
Date: 2026-09-24
Phase: 7
Context: Payments · Payment Methods · Accounts · Ledger
Supersedes: nothing. Generalises ADR-0056 §7 (bank details never enter) from payout destinations
to every external account the platform pays or is paid from, and ADR-0057's send permit from
payouts to every outbound push.

## Context

An account-to-account (A2A) payment moves money between two accounts at two payment service
providers without a card network. The vocabulary is easy to collapse, so it is fixed here:

| Term | Is | Is not |
|---|---|---|
| **Payer / Payee** | The parties whose accounts are debited and credited | Their PSPs |
| **Payer PSP / Payee PSP** | The institutions holding the payer's and payee's accounts. For a wallet withdrawal the platform is the payer's PSP; for a pay-in it is the payee's | The scheme |
| **Payment switch (scheme)** | The central infrastructure routing messages between PSPs and running the settlement cycle | A PSP; the platform |
| **Source / Destination account** | The accounts debited and credited, each identified by a rail-specific identifier or reached through an alias | A Wallet or a Ledger Account |
| **Alias (payment address)** | A proxy (a phone number, an e-mail, a national identifier) that a scheme's directory resolves to an account | An account; something the platform stores |
| **Payer authorization** | The payer's consent to a push, given with strong customer authentication at the payer's own PSP | A card authorization, which reserves funds; nothing is reserved here |
| **Execution / Confirmation** | The credit transfer, and the scheme's final answer on it | Settlement |
| **Settlement** | Discharge of the interbank obligation on the scheme's cycle, real-time gross or deferred net | Finality, which the payee has at confirmation |
| **Return** | A new credit transfer back to the payer, referencing the original | A reversal; the original stays final |

The concepts this phase must keep apart:

- An **A2A payment** is a payment (an external outcome, `payments`).
- A **Transfer** moves money between two platform accounts in one transaction (`transfers`,
  Phase 4).
- A **Ledger Posting** is the accounting record of either.
- **Settlement** is the later discharge between institutions (`settlement`, Phase 8).

## Decision

1. **One provider-neutral push-rail port, country specifics in adapters.** The port offers:
   - `exchange(grant)`: returns an opaque destination reference, a four-character display
     suffix, and the confirmation-of-payee result (`MATCH`, `CLOSE_MATCH`, `NO_MATCH`,
     `UNAVAILABLE`);
   - `send(creditTransfer)`: carries our minted end-to-end reference, stored before the send;
   - `inquire(ourReference)`: the scheme's status investigation;
   - `initiate(payIn)` for pay-by-bank: returns an authorization handle the payer's client follows
     to the payer's PSP;
   - `inquireInitiation(ourReference)`.

   Identifier schemes, alias types, message formats, reason codes, time-outs, limits and operating
   hours are adapter configuration. The core sees our verdicts (`ACCEPTED`, `REJECTED`,
   `NOTHING_SENT`, `INDETERMINATE`), our references, and the scheme's transaction reference as a
   stored value. Phase 7 builds one simulated scheme behind the harness (ADR-0008, the programme's
   no-real-connectivity rule), with fault injection and contract tests *(shipped
   `P7-TSK-006`: `PushRail`, `SimulatedInstantSchemeAdapter` and its contract battery)*. A
   second scheme is an adapter plus routing rules.

2. **Bank details and aliases never enter the platform** (ADR-0056 §7, generalised).
   - The customer's client obtains a grant at the rail provider: by linking an account, or by
     resolving a payee's alias in the scheme's directory.
   - The platform exchanges the grant, holding no database connection, and stores only three
     things: the opaque reference, the display suffix, and the confirmation-of-payee result.
   - Values shaped like an account number, an international account identifier or a phone number
     are refused at the surface, in the domain types and by `CHECK`s (`INV-RAIL-03`).
   - An external account the customer pays from, or withdraws to, becomes a payment method of
     kind `BANK_ACCOUNT` in `paymentmethods`, the instrument registry. It is ownership-scoped,
     with step-up on registration when a factor is enrolled (the beneficiary pattern).
   - A `NO_MATCH` result requires the customer's explicit acknowledgement, recorded with the
     instrument. Risk scoring of it is Phase 13's.

   *(Shipped `P7-TSK-007`: the kinded `PaymentMethod` with `DestinationReference` and
   `PayeeCheck` restated behind the PCI build-graph isolation, paymentmethods `V003`'s
   per-kind coherence and shape `CHECK`s, and the keyed
   `POST /v1/me/payment-methods/bank-accounts` — keyed because the grant is single-use, so
   the claim, not a re-exchange, answers the retry. Two recorded consequences of this
   section's rules: an unacknowledged `NO_MATCH` is refused ON THE RECORD and the burned
   grant means the acknowledged retry re-links — the deliberate cost of refusing a pending
   half-instrument state — and the exchange's evidence bytes are dropped, because a provider
   body can carry the payee's name, which is exactly what this section forbids at rest.)*

3. **Final on acceptance; the scheme bounds its own ambiguity.** A send whose answer is lost is
   `EXECUTION_UNKNOWN` (`INV-LIFE-03`), with its hold standing. The scheme declares an outcome
   deadline (ADR-0059's `outcomeDeadline`). Once that deadline has passed since the **latest**
   send, the status inquiry is authoritative: executed, or never executed. This is where an
   instant rail differs materially from a card rail, whose unknowns only a provider's answer ends.

   Concluding "never executed" is safe only if no later send can follow it. So every outbound
   push adopts **ADR-0057's send permit**:
   - a permit is committed before each send, and re-sends reuse the stored end-to-end reference;
   - "never executed" is concluded only on the locked row, and only once the latest permit is
     older than the scheme's deadline plus the configured margin;
   - a takeover whose conditional permit renewal loses does not send.

   *(Shipped for the withdrawal by `P7-TSK-008`: the permit rules live in `V016`'s
   forward-only trigger and `WithdrawalOutcomes`' two re-judged arms — the first-send rule
   compared against the LOCKED row's permit, and `NEVER_RECEIVED` only past the rail's
   declared deadline plus the configured margin, both proven on seeded rows and a dead-port
   engine.)*

   *(Adapted for the pay-in INITIATION by `P7-TSK-009`, and the adaptation is recorded
   because it is deliberate: an initiation moves no money, and re-initiating converges on
   the scheme's dedupe — an opened initiation answers its existing handle, an unopened one
   opens late — so this section's danger, "never conclude failure while a later send can
   follow", becomes "never fail a row that HOLDS a handle": the unavailability conclusions
   carry `authorization_handle IS NULL` in their conditionals for every writer, no
   never-opened conclusion exists at all (the age gauge is the operator's standing alert
   instead of a clock's guess), and the permit (`payment_attempt.last_dispatched_at`,
   forward-only, conditionally renewed) paces the wire among instances rather than
   guarding money.)*

4. **Accounting: every accepted push lands in the scheme's own clearing position**
   (`INSTANT_CLEARING`, an operational asset per currency, added with its first poster; ADR-0059
   §4 — *refined by `P7-TSK-006`: the member and its seeded chart rows arrive with the
   descriptor that must name a settling rail's position at construction, ledger `V013`; the
   first POSTING remains `P7-TSK-009`'s, which is this line's substance*). Phase 8 discharges
   it against the scheme's settlement reports.

   | Operation | Lines, in the transaction that commits the outcome |
   |---|---|
   | Wallet withdrawal accepted | release the wallet hold; DR `CUSTOMER_WALLET`; CR `INSTANT_CLEARING` (`wallet-withdrawal:<id>`) |
   | Wallet withdrawal rejected | release the hold; no posting |
   | Pay-in to a wallet confirmed | DR `INSTANT_CLEARING`; CR `CUSTOMER_WALLET` |
   | Pay-in to a checkout confirmed | DR `INSTANT_CLEARING` gross; CR `MERCHANT_PAYABLE` gross; DR `MERCHANT_PAYABLE` fee; CR `FEE_REVENUE` fee — ADR-0050's four lines through the existing capture composition |
   | Return (refund) of a pay-in accepted | release the counterparty's hold; DR the counterparty (via `RefundComposition`, ADR-0054's net rule unchanged); CR `INSTANT_CLEARING` |

   Nothing posts before the scheme's confirmation. Nothing posts twice: each posting is keyed by
   the operation, and each outcome is a conditional transition.

5. **Pay-by-bank: the payer's PSP decides, and the platform's clock only decides when to ask.**
   - An initiation waits in `AWAITING_PAYER` until the payer's PSP reports it executed, rejected
     or expired. The inquiry sweep asks. It never concludes an expiry by our clock alone
     (`INV-LIFE-03`).
   - An execution that arrives after the checkout session expired lands: the merchant is credited
     and the order is completed late (`INV-MER-06`, applied to its second rail).
   - A pay-in confirmation that names no initiation the platform made is retained as evidence,
     parked in `SUSPENSE_UNMATCHED` if it carries money (`INV-REC-05`), and alerted. It is never
     credited by guesswork.

   *(Shipped `P7-TSK-009`: the push attempt's own facts and inbound edge — born
   `AWAITING_PAYER` with our reference and its initiation permit, the handle stored once and
   rendered once to its owner, `AWAITING_PAYER → EXECUTED` applied by the rail's SIGNED
   confirmation callback and by the initiation inquiry sweep through one shared
   `PaymentOutcomes.applyExecution`, the posting `payment-execution:<attemptId>` through the
   existing capture composition — a checkout completing `COMPLETED_LATE` when its session
   expired first, `INV-MER-06`'s second rail proven both orderings — and the unattributable,
   money-carrying confirmation parked beside its `unmatched-confirmation:<rail>:<reference>`
   entry in `payments.unmatched_confirmation`, `SUSPENSE_UNMATCHED`'s first poster, aged by
   the `finapp.payments.unmatched` gauges. Payments `V017`; routing version 3 seeds the
   `PAY_IN`+`BANK_ACCOUNT` rule beside both standing routes. The payer PSP's `expired` word
   deliberately maps to the rejection at the door — the core keeps its three failure
   reasons, and the scheme's own word rests in the retained evidence, `INV-PAY-03`.)*

6. **The wallet withdrawal is hold-then-dispatch on the wallet**, the refund's and the payout's
   discipline:
   - availability is judged under the wallet account's lock (`INV-BAL-04`, `INV-BAL-05`);
   - the command is keyed and ownership-scoped, with step-up when a factor is enrolled;
   - the routing decision is pinned (ADR-0060);
   - an accepted withdrawal is irrevocable, so a reversal of it is refused by the domain
     (`INV-REV-03`, ADR-0059 §3).

   *(Shipped `P7-TSK-008`: the `Withdrawal` aggregate and `V016`, the keyed customer door
   with the resolver inside the dispatch transaction, routing's second subject pinned beside
   the row, the posting keyed `wallet-withdrawal:<id>` into `INSTANT_CLEARING`, and the
   reversal refused from the declaration with zero transactions and zero wire calls — plus
   the machine's own shape: no edge leaves `COMPLETED`.)*

7. **Payouts keep their port.** The merchant payout (ADR-0057) stays on `PayoutProvider`. The
   push rail can implement that port in `app` without any `merchant` change. Converging the two
   is recorded, not scheduled. Its trigger is a second outbound rail, or Phase 8 needing one
   evidence shape for every outbound credit transfer.

## Alternatives Considered

### Store bank account numbers encrypted
Pros: the platform could show and validate full details.
Cons: it puts bank data in scope for no gain; the provider already holds it and the reference is
what a payment needs. This is the ADR-0056 argument, and nothing about pay-ins changes it.

### Conclude "never executed" by our own clock
Pros: frees a stuck withdrawal's hold sooner.
Cons: a lost confirmation, followed by our conclusion, followed by the scheme's execution,
gives away the customer's money with the books saying failed. The deadline-plus-permit rule is
the only safe version.

### Treat a return as a reversal of the original
Pros: symmetric with the card refund.
Cons: the original is final. A return is a new payment the payee's side may never receive. It
needs its own lifecycle, its own reference and its own posting.

## Consequences

Positive:
- The instant rail is irrevocable in the model, not merely in the adapter. `INV-REV-03`'s refusal
  and the return payment are two different operations.
- No bank identifier or alias is ever at rest in the platform.
- A second scheme, in another country, is an adapter, a routing rule and possibly a new clearing
  position. The chart of accounts is keyed by (purpose, currency), so two schemes in one currency
  would need owner-keyed clearing accounts. That is recorded as the split trigger.

Negative:
- Two outbound disciplines coexist (the payout's port and the push rail) until the convergence
  trigger fires.
- The simulated scheme is the only scheme. Contract tests stand in for rulebook conformance.

Operational impact: per-rail meters; the withdrawal's stuck gauge in the payout's shape
(`UNKNOWN`, plus `DISPATCHED` past the deadline); the pay-in initiation age gauge.
Security impact: grants and references are wrapped (`Sensitive`), their `expose()` sites
registered; strong customer authentication for pay-ins happens at the payer's PSP; step-up
applies on instrument registration and on withdrawal, when a factor is enrolled.
Financial impact: `INSTANT_CLEARING` per currency; the withdrawal's hold on the wallet.

## Invariants / Constraints

`INV-RAIL-03` (bank details and aliases never enter), `INV-RAIL-04` (per-rail clearing),
`INV-REV-03`, `INV-LIFE-03`, `INV-PAY-04`, `INV-BAL-04`, `INV-MER-06` (second rail),
`INV-REC-05`.

## Follow-up

- `P7-TSK-006` (bank-account instruments and the grant exchange), `P7-TSK-007` (the instant rail
  adapter and its simulator), `P7-TSK-008` (wallet withdrawal), `P7-TSK-009` (pay-by-bank pay-in),
  `P7-TSK-010` (returns on push rails).
- Recall requests and batch credit-transfer rails with return windows are out of Phase 7, and
  recorded.
