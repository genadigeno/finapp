# Domain Glossary

Every term in [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md)'s canonical list, plus every term
`CLAUDE.md` §Domain Distinctions forbids collapsing, defined with an explicit statement of what
it is **not**.

Established by `P0-DOC-011`.

**Why the "not" is the point.** `CLAUDE.md` forbids collapsing pairs like authorization and
capture, or consent and authentication. A definition alone does not stop that: two definitions can
each be correct and still be applied to the same thing by two people. Naming the concept a term is
most often confused with is what turns "these are different" from an assertion into something a
reviewer can point at.

**This is a glossary, not a data model.** `DOMAIN_MODEL.md` is explicit that these names "are not
automatically aggregates or tables". Nothing here declares a class, a table or a lifecycle.

**Written before anything was implemented.** At `P0-DOC-011` no production class existed for any
term below — Phase 0 delivered the financial and platform kernel and *zero* business capability.
That is no longer so: Phases 1 to 8 built classes for many of them, Phase 6's merchant, checkout,
fee and payout terms and Phase 8's settlement and reconciliation terms included. Entries still name
no class, except where a class's name collides with a term (`MerchantSettlement`, §2 and §5). The
owning module column names where each concept lives, or — for a later phase's term — **will**
live, per [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md) §4, which records the
phase each module arrives in. *(Corrected at the Phase 6 review, `P6-DOC-001`: this said "Nothing
here is implemented". "Phases 1 to 6" corrected to 1 to 8 at the Phase 8 exit review,
`P8-DOC-001`, 2026-10-01.)*

---

## 1. How to read an entry

```
### Term
**Is:** what it is.
**Not:** the concept it is most often confused with, and why the difference matters.
**Owned by:** the module that owns (or will own) this state, or `external` for a party we do not model.
```

`external` is an answer, not a blank: a PSP is a company we contract with, and modelling one as
our own state would be the first step towards a domain that belongs to a vendor (ADR-0008).

**The glossary defines exactly the union of the two lists and nothing else.** A term the lists do
not name belongs in its owning domain document — `Debit` and `Credit` in
[`LEDGER_MODEL.md`](LEDGER_MODEL.md), `Tolerance` in
[`RECONCILIATION_MODEL.md`](RECONCILIATION_MODEL.md). This is the same rule that keeps
`ERROR_CONTRACT.md` the only place error codes are listed: a second, unguarded copy drifts while
looking authoritative.

---

## 2. The eight distinctions, contrasted

`CLAUDE.md` §Domain Distinctions lists eight groups. Each is contrasted here under a heading that
repeats the group exactly, so the document and the rule cannot drift apart.

### Identity / Authentication / Authorization / Customer / KYC

Five different questions, routinely answered as one:

| Question | Concept |
|---|---|
| *Who does this account belong to?* | **Identity** |
| *Is the person at the keyboard the one who controls it?* | **Authentication** |
| *May they do this particular thing?* | **Authorization** |
| *Do we have a commercial relationship with them?* | **Customer** |
| *Have we verified they are who they claim, to a regulator's satisfaction?* | **KYC** |

Collapsing them produces the two most common access-control defects in fintech. Treating
authentication as authorization means anyone who logs in can do anything. Treating KYC as
authentication means a verified customer is assumed authenticated, or — worse — an authenticated
one is assumed verified, and money moves for a Party nobody checked.

A **Customer** may exist with no Identity at all (onboarded by an agent), and an **Identity** may
exist for someone who is not a Customer (staff).

### Payment / Transfer / Transaction / Journal Entry

Four layers of the same event, and each has a different owner and failure mode:

| | What it is | Owner | Can it fail after we commit? |
|---|---|---|---|
| **Payment** | value moving across an external rail | `payments` | **Yes** — outcome decided by a third party |
| **Transfer** | value moving between two internal accounts | `transfers` | No — one database, one transaction |
| **Transaction** | the bookkeeping envelope for one economic event | `ledger` | No |
| **Journal Entry** | the balanced debit/credit record | `ledger` | No |

The dangerous collapse is **Payment into Transfer**: it implies the outcome is ours to decide. It
is not, and `INV-LIFE-03` exists because a payment's state may be permanently *unknown* — which is
not a state an internal transfer ever has.

The other dangerous collapse is **Transfer into Journal Entry**: a transfer is a business
operation with a lifecycle; a journal entry is the accounting consequence. One transfer may produce
several entries (execution, fee, reversal), and `INV-LED-04` makes the ledger their only writer.

### Authorization / Capture / Clearing / Settlement

A sequence, not synonyms — and the money is in a different place at each step:

```
Authorization  funds reserved by the issuer; nothing has moved
Capture        instruction to take the reserved funds
Clearing       institutions exchange records and agree what is owed
Settlement     funds actually move and the obligation is discharged
```

`INV-SET-01` is the rule this exists for: internal "captured" is **not** settlement. Treating them
as one overstates available funds and misstates the balance sheet — the platform believes it holds
money that is still with someone else.

Note the word **Authorization** appears in this group and in the identity group above, meaning two
unrelated things: permission to act, and a reservation of funds. Prose must say which.

### Wallet / Bank Account / Ledger Account / Operational Account

Four things a developer may casually call "the account":

| | Whose money | Where it lives |
|---|---|---|
| **Wallet** | the customer's | stored value we hold |
| **Bank Account** | the customer's | an external institution |
| **Ledger Account** | *nobody's* — it is a bookkeeping node | our chart of accounts |
| **Operational Account** | the platform's | our chart of accounts |

A **Ledger Account** is the one that is not a product. Collapsing it into Wallet is how a balance
ends up as a mutable field on a customer record, which `INV-BAL-01` forbids. Collapsing customer
and operational accounts is how fee revenue and customer funds end up in the same place — the
error that makes a shortfall invisible.

### PSP / Processor / Acquirer / Issuer / Payment Network

Five distinct commercial parties. One company may play several roles, which is exactly why the
roles must stay named:

| Role | What it does |
|---|---|
| **PSP** | the party we hold a contract with |
| **Processor** | the party that technically executes the transaction |
| **Acquirer** | the merchant's bank, holding the acquiring relationship |
| **Issuer** | the cardholder's bank; it authorizes, and it carries the credit risk |
| **Payment Network** | the scheme whose rules route between acquirer and issuer |

Getting **Acquirer** and **Issuer** the wrong way round inverts who bears a loss, which is a
question that only ever arises during a dispute — the worst moment to be wrong about it.

### Credit Score / Risk Score / Credit Decision / Underwriting

| | Answers |
|---|---|
| **Credit Score** | *how likely is this Party to repay?* |
| **Risk Score** | *how likely is this to be fraud or abuse?* |
| **Underwriting** | the assessment that weighs those against policy |
| **Credit Decision** | the recorded outcome, with reason codes |

`INV-CRD-04` requires all four to be separately modelled and recorded. A score is not a decision:
a decline must be explainable to the applicant and to a regulator (`INV-CRD-02`), and "the model
said 412" is not an explanation. Credit and risk scores answer different questions and must never
be summed into one number.

### Consent / Authentication / Authorization

| | |
|---|---|
| **Consent** | a lawful basis, given by a Party, for a specific purpose |
| **Authentication** | proof that a claimant controls an Identity |
| **Authorization** | permission for a specific action |

The collapse that matters: **being logged in is not consent.** `INV-CRD-03` forbids retrieving
bureau data without a recorded lawful basis, and an authenticated session is not one. Consent is
purpose-scoped, versioned and withdrawable; authorization is not withdrawn by the customer, and
authentication expires rather than being withdrawn at all.

### Customer Payment / Merchant Settlement

The same money, two different movements, in opposite directions:

```
Customer Payment      customer  ->  platform      gross, at purchase time
Merchant Settlement   platform  ->  merchant      net of fees, paid out on demand
```

Collapsing them is how fees disappear. The amount differs, the timing differs, the counterparty
differs, and the accounts differ — a customer payment credits a liability to the merchant, and a
settlement discharges it. `INV-SET-02` requires the gap between them to be a tracked expectation
that ages, precisely because the two are not the same event.

**There is no payout cycle.** Each payout is initiated on demand — by the merchant over its key,
or by an operator holding `MERCHANT_PAYOUT` — and bounded by the payable (ADR-0051, ADR-0057
§6). Nor is the class `MerchantSettlement` this movement: it is the capture-time fee split, the
entry that credits the payable gross and takes the fee from it (ADR-0050 §3). *(Corrected at the
Phase 6 review, `P6-DOC-001`: this said "on a payout cycle".)*

---

## 3. Party and identity

### Party
**Is:** a natural or legal person the platform knows about — the root identity concept from which
customer, merchant and beneficial-owner roles hang.
**Not:** a Customer. A Customer is a *role* a Party plays; the same Party may be a customer, a
merchant's beneficial owner and a payee at once, and duplicating them per role is how one person
becomes three unlinked records.
**Owned by:** `party`

### Customer
**Is:** a Party with whom the platform has a commercial relationship.
**Not:** a Party (the underlying person), and not an Account (what they hold). A Customer with no
account is a normal state.
**Owned by:** `party`

### User
**Is:** an authenticated actor operating the system — a customer, an operator or a service.
**Not:** a Customer. Staff are Users and not Customers; a Customer may be served over the phone
and never be a User. Conflating them is how an operator's action is attributed to the customer it
was performed on (`INV-AUD-01`).
**Owned by:** `identity`

### Identity
**Is:** the durable digital identity a Party authenticates as.
**Not:** the Party (one Party may hold several identities), not Authentication (the act), and not
Authorization (what it may do). See §2.
**Owned by:** `identity`

### Credential
**Is:** a secret, key or authenticator proving control of an Identity — a password, a passkey, a
TOTP seed.
**Not:** the Identity. A credential is rotatable, revocable and compromisable; an Identity
survives all three. Treating them as one means a password reset creates a new person.
**Owned by:** `identity`

### Device
**Is:** a physical or logical endpoint from which an Identity operates, with its own trust state.
**Not:** a Session. A Device outlives any session on it, and revoking a device is a different act
from ending a session.
**Owned by:** `identity`

### Session
**Is:** a bounded period of authenticated interaction.
**Not:** a Device, and not an Identity. A Session expires; an Identity does not.
**Owned by:** `identity`

### Consent
**Is:** a recorded, versioned, purpose-scoped lawful basis given by a Party.
**Not:** Authentication, and not Authorization. See §2 — being logged in is not consent.
**Owned by:** `consent`

### KYC
**Is:** the process of verifying that a natural person is who they claim to be, to the standard a
regulator requires.
**Not:** Authentication (see §2), and not the KYC Case, which is the record of one run of this
process.
**Owned by:** `kyc`

### KYC Case
**Is:** the case file for one KYC process on one Party — its lifecycle, the checks run, the
evidence retained and the decision reached.
**Not:** the decision itself, and not KYC the process. A case may be reopened; a decision is
immutable.
**Owned by:** `kyc`

### KYB Case
**Is:** the equivalent for a business, additionally establishing the beneficial-ownership graph and
control persons.
**Not:** a KYC Case with a flag set. The ownership graph is structurally different — it is
recursive, and it terminates in Parties who must each be verified.
**Owned by:** `kyc`

### Authentication
**Is:** establishing that a claimant controls an Identity.
**Not:** Identity, Authorization, or Consent. See §2.
**Owned by:** `identity`

---

## 4. Accounts and money

### Account
**Is:** the customer-facing product — its lifecycle, status and terms.
**Not:** a Ledger Account, and not a Balance. `accounts` owns the product; `ledger` owns the money.
A balance is not a field on an account.
**Owned by:** `accounts`

### Wallet
**Is:** stored value held by the platform on a Party's behalf.
**Not:** a Bank Account (that is money at another institution) and not a Ledger Account. See §2.
**Owned by:** `accounts`

### Ledger Account
**Is:** a node in the chart of accounts, with a declared type and normal balance side, against
which journal lines are posted.
**Not:** a customer's Account or Wallet. It is a bookkeeping construct and belongs to nobody;
`INV-LED-06` forbids reclassifying one once it has postings.
**Owned by:** `ledger`

### Bank Account
**Is:** an account at an external institution, identified by rail-specific details.
**Not:** anything the platform holds. We store a *reference* and never a balance — the institution
owns the account, and its balance is not ours to derive.
**Owned by:** `external`

### Operational Account
**Is:** a ledger account holding the platform's own position — fee revenue, suspense, FX position,
settlement clearing.
**Not:** a customer account. Mixing the two is how a shortfall becomes invisible. See §2.
**Owned by:** `ledger`

### Settlement Account
**Is:** the platform's own account at its settlement bank, where counterparties' remittances
arrive and payouts leave — mirrored per currency in the ledger by the operational account
`CASH_AT_BANK`, which only the bank's own statement posts — or the reversal of that statement's
recognition when its batch is repudiated *(`P8-DOC-001`, 2026-10-01)* — and known externally only by the
bank's opaque account reference (`INV-SET-06`, `INV-RAIL-03`, ADR-0065).
**Not:** a Wallet (customers' stored value), a customer's Bank Account, or a clearing position — a
clearing position is what a counterparty owes, the settlement account is cash the platform holds
(ADR-0042). Nor is it adjusted to fit: `CASH_AT_BANK` mirrors the statements, and a gap or an
opening mismatch is a break, never an adjustment.
**Owned by:** `ledger` (the `CASH_AT_BANK` position); the bank's account reference is configuration

### Balance
**Is:** a monetary position for a ledger account, derived from its postings.
**Not:** an independent authority. `INV-BAL-01` and ADR-0009 make every balance either the
position derived from postings or a documented projection of it; `INV-BAL-02` requires it to be
reproducible from zero. A balance that can be set is a balance that can create money.
**Owned by:** `ledger`

### Hold
**Is:** a claim against available balance, reserving funds for an anticipated movement.
**Not:** a posting, and not a debit — value has not moved. `INV-BAL-04` makes available balance the
ledger balance minus active holds, which is why a hold that is never released is as damaging as a
wrong posting.
**Owned by:** `ledger`

### Beneficiary
**Is:** a saved payment destination belonging to a Party.
**Not:** a Party, and not an Account. The destination may be entirely external; the beneficiary
record is ours, and a new one is a risk signal (`transfers` §Seams).
**Owned by:** `transfers`

---

## 5. Payments

### Payment
**Is:** the economic act of value moving between a payer and a payee across a rail whose outcome a
third party decides.
**Not:** a Transfer, a Transaction, or a Journal Entry. See §2 — the distinguishing property is
that a Payment's state may be permanently unknown.
**Owned by:** `payments`

### Transfer
**Is:** movement of funds between two accounts the platform controls.
**Not:** a Payment. A transfer's state transition and its ledger posting commit in one
transaction; a payment's outcome cannot.
**Owned by:** `transfers`

### Payment Intent
**Is:** the customer-facing record of what is to be paid, by whom, to whom and in what currency.
**Not:** a Payment Attempt. One Intent may have many Attempts — a declined card retried on
another is one intent and two attempts.
**Owned by:** `payments`

### Payment Attempt
**Is:** one execution of an Intent against one provider, with its own provider references and
evidence.
**Not:** the Intent. Counting attempts as payments overstates volume; counting intents as attempts
loses the evidence a dispute needs.
**Owned by:** `payments`

### Payment Method
**Is:** the instrument value moves on — a card, a bank account, a wallet.
**Not:** the rail (how it moves) and not the token (the reference we hold instead of the
instrument). Raw card data never enters the platform.
**Owned by:** `paymentmethods`

### Authorization
**Is:** *(payments sense)* a reservation of funds by the issuer, with an expiry.
**Not:** Capture, Clearing or Settlement — nothing has moved. Also not Authorization in the
access-control sense; see §2.
**Owned by:** `payments`

### Capture
**Is:** the instruction to take funds that were authorized.
**Not:** Authorization (which reserved them) and not Settlement (which moves them). `INV-SET-01`.
**Owned by:** `payments`

### Clearing
**Is:** the exchange of transaction records between institutions to establish what is owed. The
platform keeps its evidence — the card network's clearing notice for one capture (its acquirer
reference and network transaction identifiers, no amount) and a push confirmation's settlement
cycle — and, apart from it, each counterparty's *clearing position*: the ledger account holding
what that counterparty owes, or is owed, while value is in flight (`INV-RAIL-04`).
**Not:** Settlement. Clearing agrees the obligation and moves nothing; settlement discharges it,
from the counterparty's report and then the bank's statement (ADR-0065). And not the platform's
*clearing record* as a settlement source: `payments` records it once, with no ledger effect
(`P7-TSK-005`), and in Phase 8 it feeds matching as a key — the acquirer reference an alias of its
capture — never as evidence of payment. A second, different clearing of one capture is its own
outcome, `SECOND_PRESENTMENT`, loud and counted unmappable, the first record standing and its
references kept only in the retained provider evidence — there is no clearing-notice table and no
cleared amount; Phase 8 records the cleared amount at its first evidence hop, as the PSP report's
line, not in a new `payments` table (ADR-0059 §5, ADR-0065). *(Owner corrected by the Phase 7 → 8
transition: this said `settlement`, which owns the counterparties' reports and the bank's
statements, never the clearing exchange's evidence or the positions. The record's owner was named
by the Phase 7 review; the second presentment is the transition gate's repair, recorded here by
its consistency review.)*
**Owned by:** `payments` (the clearing evidence); `ledger` (the clearing positions)

### Settlement
**Is:** the actual movement of funds that discharges an obligation. The platform recognises it
from external evidence in two hops on the counterparty's own clearing position: *reported* when
the counterparty's accepted batch recognises its fees, allocates its lines to our settlement
expectations and opens the Remittance; *final* when the bank's statement moves the cash against
that position (ADR-0065).
**Not:** Capture, Clearing, or Reconciliation. `INV-SET-01` keeps internal completion and
settlement as separate states — at the last hop too, because a counterparty's report of payment
is not cash (`INV-SET-06`). `INV-SET-03` requires late settlement to be processed rather than
discarded. Reconciliation compares the evidence with our expectations; it does not recognise it
(ADR-0064). *(Extended by the Phase 7 → 8 transition, ADR-0064 and ADR-0065.)*
**Owned by:** `settlement` (the recognition); `reconciliation` (the allocation)

### Refund
**Is:** a new, forward movement returning value to the payer, referencing the original payment.
**Not:** a reversal of the original (`INV-REV-01` — history is never edited) and not a Chargeback
(which the payer's institution forces on us). A refund is our decision; a chargeback is not.
**Owned by:** `payments`

### Chargeback
**Is:** a forced reversal initiated through the payer's institution and the network's rules.
**Not:** a Refund, and not a Dispute — a chargeback is one possible *outcome* of a dispute, and it
arrives with deadlines and evidence requirements a refund does not have.
**Owned by:** `payments`

### Dispute
**Is:** the process by which a payer contests a payment, with defined stages and evidence.
**Not:** a Chargeback. Treating them as one loses the representment stage, which is the only
opportunity to defend the payment.
**Owned by:** `payments`

### Payment Rail
**Is:** a way money travels between institutions — a card network through a processor, an
instant credit-transfer scheme, or the platform's own books for a wallet payment — described by
a declared capability descriptor: its interaction model (two-step, push or book), when an
operation is final, whether and how it can be reversed, how a refund executes, how long an
unknown outcome may last, and which clearing position a completion posts to (ADR-0059,
`INV-RAIL-01`).
**Not:** a PSP, which is a company that operates on a rail, and not a Payment Method, which is
the customer's instrument. A rail is never branched on by name outside its adapter: the domain
acts on what the rail declared.
**Owned by:** `payments`

### Routing Decision
**Is:** the recorded choice of rail for one payment, made once at confirmation by a pinned,
versioned routing policy over stored inputs — the instrument, the currency and amount, the
capabilities each candidate declared, and each rail's recorded availability — with every
rejected candidate and its reason (ADR-0060, `INV-RAIL-02`, `INV-HIST-04`).
**Not:** a retry policy, and not load balancing. Phase 7 has no cross-rail fallback after
dispatch: a candidate is rejected inside the decision before anything is sent, and a dispatch
answered "nothing sent" fails the payment. Were an advance ever built, it would move only on
knowledge that nothing was sent, never after an ambiguous dispatch (ADR-0060 §5).
**Owned by:** `payments`

### A2A Payment
**Is:** an account-to-account payment — value moved from a payer's bank account to a payee's
over a credit-transfer rail, initiated by the payer or by the platform on the payer's authority
(a pay-by-bank pay-in), or by the platform to a customer's own bank account (a withdrawal)
(ADR-0062).
**Not:** a Transfer, which moves value between two ledger accounts on the platform's own books,
and not a Journal Entry, which is how either is recorded. An A2A payment's settlement happens
between the institutions; the platform records its clearing position (`INV-RAIL-04`).
**Owned by:** `payments`

### Instant Payment
**Is:** an A2A payment on a rail whose scheme confirms acceptance within seconds and makes it
final on acceptance, with an outcome deadline after which an inquiry is authoritative
(ADR-0062).
**Not:** settled when accepted: acceptance is final between payer and payee, while the
institutions settle later under the scheme's own cycle (`INV-SET-01`). And not reversible — a
mistaken instant payment is corrected by a new return payment (`INV-REV-03`).
**Owned by:** `payments`

### Withdrawal
**Is:** a customer's instruction to move value from their wallet to their own external bank
account — held on the wallet at dispatch, sent under a send permit, and posted when the rail
accepts it (ADR-0062).
**Not:** a Refund, which returns a payment to its payer, and not a Merchant Payout, which pays a
merchant its payable. The three share the outbound disciplines and have different subjects and
bounds.
**Owned by:** `payments`

### Interaction Model
**Is:** how a rail conducts a payment, declared by the rail and frozen on the attempt at birth:
`TWO_STEP` (authorize, then capture - the card), `PUSH` (one credit transfer the payer's side
executes - the instant scheme) or `BOOK` (one movement on the platform's own ledger - the
wallet). It owns the attempt's machine: which states a payment on that rail can occupy and which
edges it can take (ADR-0059 §2, `INV-RAIL-01`).
**Not:** a Payment Rail, which declares one, and not a status: `EXECUTED` on a push attempt and
`CAPTURED` on a card attempt are different machines' completions, and a completion is read
together with its model.
**Owned by:** `payments`

### Void
**Is:** the release of a card authorization that was never captured - the promise withdrawn,
by the customer, an operator with a reason, or the platform when a capture is declined - on a
rail whose declaration lists it (ADR-0059 §3, `INV-REV-03`). No money moved, so nothing posts.
**Not:** a Refund, which returns captured money as a new movement, and not a Reversal of an
irrevocable payment, which the domain refuses before anything is written or sent.
**Owned by:** `payments`

### Return Payment
**Is:** a refund executed on a push rail as a NEW credit transfer back to the payer, citing the
original's scheme reference - the declared `refundMode` of a rail that cannot reverse
(ADR-0059 §3, `P7-TSK-010`).
**Not:** a reversal or a recall of the original, which stays final (`INV-REV-03`), and not a
separate aggregate: it is a Refund row, bounded and resolved exactly as every refund is.
**Owned by:** `payments`

### Dispute Response
**Is:** the platform's answer to a chargeback - a representment carrying evidence, or an
acceptance - dispatched once through the card PSP under our own minted reference, one live
answer per dispute (ADR-0061 §7, `P7-TSK-014`).
**Not:** a stage of the Dispute: the PSP taking the answer moves no stage and no money; the
network's verdict still arrives by notification and stays the network's word.
**Owned by:** `payments`


### Transaction
**Is:** the bookkeeping envelope grouping the journal entries produced by one economic event.
**Not:** a Payment, not a Transfer, and never — in domain prose — a *database* transaction. This is
the most overloaded word in the vocabulary, which is why prose should prefer the specific term.
**Owned by:** `ledger`

### Customer Payment
**Is:** money flowing from a customer into the platform, gross, at purchase time.
**Not:** a Merchant Settlement. See §2.
**Owned by:** `payments`

### Merchant Settlement
**Is:** money flowing from the platform to a merchant, net of fees — paid out on demand, each
payout initiated by the merchant or an operator; there is no payout cycle (ADR-0051, ADR-0057 §6).
**Not:** a Customer Payment. Different direction, amount, timing and accounts. Nor the class
`MerchantSettlement`, which is the capture-time fee split (ADR-0050 §3), not this outbound flow.
*(Corrected at the Phase 6 review, `P6-DOC-001`: this said "on a payout cycle".)*
**Owned by:** `merchant`

### Checkout Session
**Is:** a merchant's short-lived, expiring offer to a customer to pay — the purchase
experience, referencing the payment intent it opens (ADR-0053).
**Not:** the Order (a session that dies unpaid produces no order) and not the Payment (it
owns nothing of the payment's lifecycle).
**Owned by:** `checkout`

### Order
**Is:** the commercial fact a paid checkout session produces — permanent, one per session,
the unit a merchant reconciles against.
**Not:** the payment (Phase 5's record of the money) and not fulfilment (the merchant's
business, outside the platform's books).
**Owned by:** `checkout`

### Fee Schedule
**Is:** versioned pricing configuration — rate, fixed part, rounding mode, refund-fee
policy — immutable from creation (no `UPDATE` grant, and a trigger refuses every change: merchant
`V004`); change creates a new version effective forward. *(This read "immutable once effective"
until the Phase 6 review, `P6-DOC-001`.)*
**Not:** a fee assessment, which is the historical fact pinned to the version that priced
it (`INV-MER-03`).
**Owned by:** `merchant`

### Merchant Payable
**Is:** what the platform owes a merchant — the merchant's payable **ledger account
position**: captured − fees − refunds + fees returned − payouts − chargebacks + chargebacks
reversed + payouts returned ± reconciliation attributions (`INV-MER-02`; the last two terms are
Phase 8's, ADR-0073: payouts returned built by `P8-TSK-019`, and reconciliation attributions —
every payable line of a reconciliation resolution's adjustment entry, whatever it faces — by
`P8-TSK-015`). *(Amended by the Phase 7 → 8 transition: this read "captured − fees − refunds −
payouts", which had lacked the fee-return term since the Phase 6 review amended `INV-MER-02`, and
the chargeback terms since `P7-TSK-013`. The Phase 8 terms' owners and the attribution rule were
settled by the transition's consistency review, A12 and A13.)*
**Not:** a stored balance field. It exists nowhere except as postings.
**Owned by:** `ledger` (the position); `merchant` (the account's purpose)

### Merchant Payout
**Is:** a distinct money movement paying the merchant's net payable outward, with its own
lifecycle, hold, idempotency and provider ambiguity handling (ADR-0051).
**Not:** settlement of the customer's payment, and not automatic — initiated, bounded by
the payable, and final only at Phase 8's settlement: the payout provider's report, then the
platform's bank debit (ADR-0065). `COMPLETED` means instructed. A beneficiary bank's return is a
new operation, the payout return, and the payout stays `COMPLETED` (ADR-0073). *(Extended by the
Phase 7 → 8 transition.)*
**Owned by:** `merchant`

---

## 6. External parties

### Merchant
**Is:** a business accepting payments through the platform, with its own onboarding, accounts and
payout arrangements.
**Not:** a Customer, though the same Party may be both. A merchant's money is a liability we owe;
a customer's is a liability we hold.
**Owned by:** `merchant`

### PSP
**Is:** the payment service provider we hold a commercial contract with.
**Not:** the Processor. A PSP may outsource processing, and the contract and the technical
integration can change independently.
**Owned by:** `external`

### Processor
**Is:** the party that technically executes the transaction against the network.
**Not:** the PSP. See §2.
**Owned by:** `external`

### Acquirer
**Is:** the merchant's bank, holding the acquiring relationship and receiving funds on the
merchant's behalf.
**Not:** the Issuer. Reversing the two inverts who bears a loss.
**Owned by:** `external`

### Issuer
**Is:** the cardholder's bank — it authorizes the transaction and carries the credit risk.
**Not:** the Acquirer. See §2.
**Owned by:** `external`

### Payment Network
**Is:** the scheme whose rules govern routing, liability and dispute procedure between acquirer and
issuer.
**Not:** the PSP or the Processor. Network rules set deadlines we must meet regardless of which
provider we use, which is why they cannot live inside a provider adapter.
**Owned by:** `external`

---

## 7. Credit

### Credit Profile
**Is:** the assembled view of a Party's credit position — bureau data, internal history, exposure.
**Not:** a Credit Score. The profile is the inputs; the score is one derived figure.
**Owned by:** `credit`

### Credit Score
**Is:** a numeric assessment of the likelihood a Party repays, ours or a bureau's.
**Not:** a Risk Score, and not a Credit Decision. See §2.
**Owned by:** `credit`

### Risk Score
**Is:** a numeric assessment of the likelihood that an action is fraudulent or abusive.
**Not:** a Credit Score. Different question, different inputs, different consequence — and summing
them produces a number that answers nothing. See §10: which module owns this is not yet settled,
and the register's answer is followed here rather than overridden.
**Owned by:** `credit`

### Credit Decision
**Is:** the recorded, immutable outcome of underwriting, with reason codes and pinned policy and
model versions.
**Not:** a score, and not Underwriting (the assessment that produced it). `INV-CRD-01` requires it
to be reproducible; `INV-CRD-02` requires reason codes sufficient for an adverse-action
explanation.
**Owned by:** `credit`

### Underwriting
**Is:** the assessment weighing a Party's profile and scores against a versioned policy.
**Not:** the Credit Decision it produces. See §2.
**Owned by:** `credit`

### Loan Application
**Is:** a Party's request for credit, with its own lifecycle.
**Not:** a Loan Offer. An application may be declined, withdrawn or expire without any offer.
**Owned by:** `lending`

### Loan Offer
**Is:** terms the platform is prepared to grant, with an explicit expiry.
**Not:** a Loan. Nothing has been disbursed, and an expired offer is not a loan that failed.
**Owned by:** `lending`

### Loan
**Is:** a disbursed credit agreement being serviced — schedule, accruals, repayments, state.
**Not:** a Loan Offer, and not the Exposure it contributes to.
**Owned by:** `lending`

### Instalment
**Is:** one scheduled repayment within a loan or BNPL schedule — an amount and a due date.
**Not:** a Repayment, which is money actually received. An instalment can be due, overdue or paid;
a repayment is an event. Both spellings appear in the wild; this platform uses **Instalment**
throughout (`P0-DOC-011` corrected the one American spelling in the canonical list).
**Owned by:** `lending`

### Exposure
**Is:** the platform's aggregate at-risk amount for a Party, across every credit product.
**Not:** the outstanding balance of one loan. Exposure is what a limit is checked against, and
computing it per product is how a Party borrows the same limit several times.
**Owned by:** `credit`

### Delinquency
**Is:** the state arising from contractual payments being missed, with defined stages.
**Not:** a default, and not a write-off. Delinquency is recoverable; the later states are
accounting events with their own postings.
**Owned by:** `lending`

### BNPL Agreement
**Is:** a short-term instalment credit agreement created at the point of checkout.
**Not:** a Loan. Its origination is embedded in a purchase, the merchant is financed separately,
and a returned item adjusts the agreement — none of which a loan does.
**Owned by:** `bnpl`

---

## 8. Foreign exchange

### FX Quote
**Is:** a rate offered to a specific party for a specific amount, valid for a stated window.
**Not:** an Exchange Rate. A quote is ours, priced, time-bounded and rejectable when stale
(`INV-FX-02`).
**Owned by:** `fx`

### Exchange Rate
**Is:** an observed market rate from a source, at an instant.
**Not:** the rate a customer receives. The difference between them is spread, and `INV-FX-03`
requires it to be posted as revenue explicitly rather than concealed inside the applied rate.
**Owned by:** `fx`

### FX Trade
**Is:** an executed conversion — two legs, through an FX position, preserving total value
(`INV-FX-01`).
**Not:** a Quote. A quote may expire unexercised; a trade has postings.
**Owned by:** `fx`

---

## 9. Ledger, settlement and reconciliation

### Journal Entry
**Is:** a balanced set of journal lines recording one financial event, immutable once committed.
**Not:** a Payment or a Transfer (the business operations that caused it). `INV-LED-01` requires
debits to equal credits per currency; `INV-LED-02` forbids a single-line entry.
**Owned by:** `ledger`

### Journal Line
**Is:** one debit or credit of one amount against one ledger account.
**Not:** a Journal Entry. A line alone is unbalanced value movement, which is why the entry is the
unit that commits.
**Owned by:** `ledger`

### Chart of Accounts
**Is:** the structured set of ledger accounts, each with a declared type and normal balance.
**Not:** the general-ledger mapping used for reporting — that is a separate, versioned artefact
(`accounting`), so a reporting change never rewrites the ledger's structure.
**Owned by:** `ledger`

### Suspense Account
**Is:** a ledger account holding value whose final destination is not yet determined —
`SUSPENSE_UNMATCHED`, where reconciliation parks value nothing explains, a Phase 7 unmatched
confirmation included. Each unit in it is a tracked suspense item owned by exactly one break,
entering only in the transaction that records that break (`INV-REC-09`, ADR-0070).
**Not:** a permanent home. `INV-REC-05` requires suspense to be aged, reported and alerted on —
ageing suspense is an unrecognised loss or liability. Value leaves it only by evidence (an unpark
or an offset), by an approved four-eyes resolution of a kind its break's type admits — a
recognised gain only after the minimum age of the rule set its owning break pins, and never for
value owed to a merchant or a customer, nor for a currency break (ADR-0069's per-type table) — or
by repudiating the batch that parked it, or the batch whose remittance a parked bank item
over-paid (the over-payer reopened whole, its excess unparked). *(Corrected 2026-10-01,
`P8-DOC-001`: the gain's age read from the owning break's pinned rule set, and the over-payer's
release by repudiation, as built.)* Nor is it a tolerance: a difference too small to chase is still a break, never absorbed
(`INV-REC-08`). *(Extended by the Phase 7 → 8 transition, ADR-0070; which resolutions may leave
it was settled by its consistency review, A1: ADR-0069's per-type table is the one authority.)*
**Owned by:** `ledger` (the account and its lines); `reconciliation` (the suspense items)

### Reconciliation Batch
**Is:** one run of the matcher over one accepted settlement batch — created in that batch's
acceptance transaction, pinning its rule set, business date and acceptance sequence, and completed
only when every item it holds is disposed of — or a `REPROCESS` run over residual items under the
rule set active for its source when the run opens, which need not be newer (ADR-0068).
*(Corrected 2026-10-01, `P8-DOC-001`: this read "under a newer rule set".)*
**Not:** a Settlement Batch (which is the counterparty's unit of evidence the run compares against
our settlement expectations), and not Settlement itself. A blocked run is never skipped: it holds
its source, visibly, until a person requeues it. *(Made concrete by the Phase 7 → 8 transition,
ADR-0068: this read "one run comparing a set of internal records against one external source".)*
**Owned by:** `reconciliation`

### Reconciliation Break
**Is:** a classified discrepancy between internal and external records — missing on either side,
differing in amount, currency, fee, timing or direction, or duplicated — of one of fourteen closed
types, with a subject, a value at issue fixed when it is raised, and a severity set by its type,
its age and a pinned value threshold (ADR-0069). One break is open per type and subject; a
recurrence after resolution is a new break.
**Not:** an error to be cleared. A break has a lifecycle and is never deleted, its evidence is
preserved on both sides (`INV-REC-01`), and it closes only by a new record — never by editing
either side (`INV-REC-03`): by evidence, when a later allocation or offset leaves nothing at issue
(`EVIDENCED`, the only resolution no person decides, `INV-REC-02`), by a person's
template-bound, reason-coded resolution of a kind its type admits — four-eyes for every kind but a
zero-value `ACKNOWLEDGE` of a `TIMING_DIFFERENCE` raised by a timing detector — whose compensating
entry goes through the ledger's adjustment machinery (ADR-0071), or by an approved
`REPUDIATE_BATCH` that empties its subject, recorded in a `repudiation_closure` row. For every
writer it becomes `RESOLVED` only if, at commit, a `RESOLVED` event of the break names a
resolution that is `APPROVED`; that the named resolution is the break's own is held by the
domain alone (recorded debt, Phase 15).
*(Extended by the Phase 7 → 8 transition, ADR-0069 and ADR-0071. Corrected 2026-10-01,
`P8-DOC-001`: this read "four-eyes whenever value is at issue or it posts", which reconciliation
`V014` narrowed, and omitted closure by repudiation; the every-writer binding is reconciliation
`V015`.)*
**Owned by:** `reconciliation`

### Settlement Batch
**Is:** a counterparty's settlement unit as it delivered it — a PSP's day, an instant scheme's
cycle, a payout provider's day, or one bank statement per currency — carried by exactly one
settlement file, single-currency, and identified among live batches by source, external batch
reference and currency. It is recognised at most once, and its acceptance posts only what the
platform had not already recorded: the counterparty's fees, or the bank's cash (`INV-SET-04`,
ADR-0065).
**Not:** a Reconciliation Batch, which is the platform's run over it, and not Settlement itself:
a counterparty's batch reports what it settled and what it will remit, and the value stays in
that counterparty's clearing position until the bank's statement moves the cash
(`INV-SET-06`).
**Owned by:** `settlement`

### Remittance
**Is:** the net funds movement a counterparty's accepted batch implies — what it reported in,
less what it reported out, less its fees (N = T_in − T_out − F, positive when the counterparty
pays the platform) — opened as one `REMITTANCE` settlement expectation on that counterparty's own
clearing position, which only the bank's statement discharges (ADR-0065).
**Not:** cash, and not Settlement: until the bank's statement shows the funds, a remittance is a
counterparty's promise, and the platform's cash does not move on it (`INV-SET-06`). Nor an
instruction: the platform originates no settlement movement; it expects one.
**Owned by:** `reconciliation` (the `REMITTANCE` expectation); the remittance reference is
`settlement`'s evidence

### Settlement Expectation
**Is:** the platform's record that one completed operation's clearing posting should be settled
by its counterparty — the immutable facts of that journal line (kind, operation reference,
account, direction, amount, entry) copied and opened in the same transaction that posted it,
dated by the pinned rule set's lag, and aged until external evidence is allocated to it
(`INV-SET-02`, ADR-0067). A counterparty batch's net opens one too: its Remittance.
**Not:** Settlement — the expectation says value *should* settle; settlement is the evidence that
it did — and not a balance: its amount is a copy of a posted line, and the ledger stays the only
balance authority (`INV-BAL-01`). An overdue expectation is not a lost one: it can still settle,
and meanwhile ageing raises a break and leaves its value in the position.
**Owned by:** `reconciliation`

### Match Decision
**Is:** one evaluation of one external item by the matcher — its pinned rule set, the rule and
key that fired, the outcome, its rank among claimants, the timing and fee comparisons it applied,
and a stored snapshot of every candidate expectation it saw — together with the allocations it
produced: append-only amounts from that item to those expectations, never more than either side
holds (`INV-REC-04`, `INV-REC-07`, ADR-0068).
**Not:** an opinion to be re-derived: replay re-runs the decision over its own stored snapshot
and must reproduce it, so "why were these two records matched?" is answered from the decision's
rows, never from today's state (`INV-HIST-04`). And not a Resolution: a `MANUAL_MATCH` resolution
may cause one, but the decision only allocates, and it is never edited — a batch repudiation adds
counter-allocations beside it.
**Owned by:** `reconciliation`

*(The nine entries below — Settlement File, External Item, Suspense Item, Resolution, Matching Rule
Set, Run Replay, Repudiation, Attestation and Readmission — were added at the Phase 8 exit review,
`P8-DOC-001`, 2026-10-01, with their lines in `DOMAIN_MODEL.md`: Phase 8 built each as a distinct
concept, and none was defined anywhere a reader would look.)*

### Settlement File
**Is:** the raw bytes one counterparty delivered — uploaded by a person or pulled over the
source's own credential — screened at the door, stored encrypted in ordered chunks with their
checksum verified on every read, and identified among non-readmissions by its source and content
address, so ten deliveries of the same bytes are one file (`INV-HIST-02`, `INV-REC-10`). It moves
`RECEIVED → PARSED → ACCEPTED`, or to `REJECTED` whole, and carries exactly one Settlement Batch.
**Not:** the Settlement Batch, which is the counterparty's unit of evidence the file declares, nor
a refused delivery, of which only metadata is kept. And not trusted on arrival: an upload takes
effect only once attested (`INV-SET-07`); our own parse failure leaves it `RECEIVED`, never
rejected.
**Owned by:** `settlement`

### External Item
**Is:** reconciliation's working copy of one immutable settlement line, carrying the contended
disposition the matcher decides — `PENDING`, then `MATCHED`, `CHECKED`, `OFFSET`, `UNMATCHED` or
`PARKED`, later `RESOLVED` or `REPUDIATED` — with its typed keys and the run that holds it.
**Not:** the Settlement Line, which stays settlement's immutable evidence; copying it is what lets
reconciliation change a disposition without mutating another module's rows (ADR-0064). Nor an
expectation: an item is what a counterparty said, an expectation what the platform recorded.
**Owned by:** `reconciliation`

### Suspense Item
**Is:** one tracked unit of value in `SUSPENSE_UNMATCHED` — CREDIT or DEBIT, with its origin (a
park, an unattributed bank line, an adopted unmatched confirmation, or a repudiation's answer) and
its opening date — owned by exactly one break, entering only in the transaction that records that
break and released by an unpark, an offset, an approved resolution or a repudiation
(`INV-REC-09`, `INV-REC-05`). It moves `OPEN → PARTIALLY_RELEASED → RELEASED`.
**Not:** the Suspense Account, which is the ledger account whose lines the items explain, nor a
balance: the item records why value is parked and who owns it; the ledger holds the amount.
**Owned by:** `reconciliation`

### Resolution
**Is:** the record that closes a break — or, for a batch repudiation, decides a settlement batch —
naming its kind, a closed reason code, a narrative and the frozen proposal its approver approves:
`PROPOSED → APPROVED | REJECTED | WITHDRAWN`, an `EVIDENCED` resolution born `APPROVED` by the
platform alone, and a zero-value acknowledgement of a timing-detected `TIMING_DIFFERENCE` born
`APPROVED` by one person; every other kind four-eyes, a stale approval refused (`INV-REC-02`,
`INV-REC-03`, `INV-AUD-04`).
**Not:** the adjustment itself. A posting kind creates a `RECONCILIATION`-origin ledger proposal,
one to one, and the ledger's entry is the money; the resolution is the reasoned decision. Nor a
Match Decision: a `MANUAL_MATCH` resolution causes one, but only the decision allocates.
**Owned by:** `reconciliation`

### Matching Rule Set
**Is:** the versioned, per-source set of rules the matcher applies — its match rules, tolerances,
provider fee schedule and severity thresholds — proposed by one person and activated by another
(`PROPOSED → ACTIVE | REJECTED`, the prior `ACTIVE → RETIRED` in the same transaction), content
frozen from `PROPOSED`, one `ACTIVE` per source, and pinned by every run, decision, break and
expectation (`INV-REC-04`, `INV-HIST-04`).
**Not:** `lending`'s or `risk`'s rule sets, which the name keeps apart, and not a tolerance on
value: no member can absorb an amount already in a position (`INV-REC-08`). A new version governs
only forward decisions; it never alters a committed allocation, park or break.
**Owned by:** `reconciliation`

### Run Replay
**Is:** one re-run of a reconciliation run's stored decisions — each re-evaluated by the pure
matcher over its own candidate snapshot under the rule set it pinned, in one repeatable-read
snapshot — appended as a verdict, `IDENTICAL` or `DIVERGED`, items awaiting rematch reported
apart; a divergence raises one CRITICAL `PROCESSING_ERROR` break (`INV-HIST-04`, `INV-REC-04`).
**Not:** a `REPROCESS` run, which re-resolves residual items now and writes new decisions. A replay
writes nothing but its verdict (and a divergence's break), and never re-derives a decision from
today's state.
**Owned by:** `reconciliation`

### Repudiation
**Is:** the four-eyes withdrawal of an accepted settlement batch proven fabricated or
mis-normalised — an approved `REPUDIATE_BATCH` resolution whose one transaction reverses the
batch's recognition through the ledger's reversal machinery, counter-allocates every allocation of
its items append-only, releases its parks, closes the breaks it emptied, and moves its items and
the batch to `REPUDIATED`, the file retained byte-identical (`INV-REV-01`, `INV-AUD-04`,
`INV-REC-07`). The genuine file can then be accepted.
**Not:** a rejection, which refuses evidence before it takes effect, nor a deletion or an edit:
every effect is a new record, and the batch keeps its acceptance facts. Nor a payment Reversal.
**Owned by:** `reconciliation` (the decision); `settlement` holds the batch's `REPUDIATED` status

### Attestation
**Is:** a second person's confirmation, recorded once on the file, that an uploaded settlement
file is genuine — never the uploader, and for a readmission that inherits no authentication never
any submitter along its chain — without which an upload never takes effect (`INV-SET-07`).
**Not:** a check of the content's correctness, which the parse and the matcher make, nor needed for
a pulled file, which the source's own credential authenticates. Declining is the opposite
judgement: a declined file is rejected, and passes no authentication to a readmission.
**Owned by:** `settlement`

### Readmission
**Is:** a new file record re-presenting a rejected file's own stored bytes for parsing under the
source's current format version — admissible for a file our validation rejected, a declined
upload, or a `CONFLICTING_BATCH` original once no live batch holds its identity — inheriting its
original's authentication when the original was pulled or attested, and otherwise attested itself
(`INV-SET-07`).
**Not:** an edit of the original, which stays `REJECTED` with its verdict frozen, nor a re-upload:
a byte-identical upload meets the original's content address and is a duplicate.
**Owned by:** `settlement`

---

## 10. Where the two lists disagreed

Recorded because the disagreement is the kind of thing a glossary exists to settle, and because
`P0-DOC-011` is the first task to compare the lists mechanically.

**Seven terms are forbidden from being collapsed but were never named as canonical concepts:**
`Authentication`, `Authorization` *(access-control sense)*, `Transaction`, `Operational Account`,
`Underwriting`, `Customer Payment`, `Merchant Settlement`. All seven are defined above. The
glossary is therefore the **union** of the two lists, which is what the enforcement in §11 checks.

**`KYC` appears in the distinctions and `KYC Case` in the canonical list.** These are genuinely
different — a process and the record of one run of it — so both are defined rather than merged.

**`Authorization` means two unrelated things**, in the identity group and the payments sequence.
Both senses are defined in the single entry, and prose must say which is meant.

**`Risk Score`'s owner is genuinely unsettled.** `MODULE_ARCHITECTURE.md` §4 lists it under
`credit`, beside `Credit Score`; `risk` owns `Risk Assessment` and `Risk Decision`. If a risk score
measures fraud and abuse — which is how this glossary defines it, and how `CLAUDE.md` contrasts it
with a credit score — then `risk` is where it belongs, and the register would be attributing one
module's concept to another.

The register is followed here, because ADR-0012 makes it the authority on ownership and
`P0-TSK-006` verified single ownership by script. **A glossary must not settle an ownership
question by quietly disagreeing with the document that owns it.** Resolving it is a Phase 10 or 13
decision; recording it is this task's job. The review that found it also added the guard in §11
that makes the next such contradiction a build failure rather than a discovery.

**The canonical list spelled `Installment` once**, against twenty uses of `Instalment` elsewhere
including the module register that assigns its ownership. Corrected in `DOMAIN_MODEL.md` to the
majority spelling.

---

## 11. What is enforced

`DomainGlossaryTest`, on every build:

1. Every term in `DOMAIN_MODEL.md`'s canonical list has an entry here.
2. Every term named in a `CLAUDE.md` §Domain Distinctions group has an entry here.
3. **No entry exists for a term neither list names** — so the glossary stays the union of the two
   lists rather than becoming a second, unguarded home for vocabulary its owning document should
   define.
4. Every entry states what the term is **not**.
5. Every entry names an owning module, and every module named is one `MODULE_ARCHITECTURE.md`
   declares (or `external`).
6. **No owner contradicts the register.** Where `MODULE_ARCHITECTURE.md` §4 names a concept in a
   module's `Owns:` line, the glossary must agree — it is the authority on ownership (ADR-0012).
   Added during review, which found `Risk Score` attributed to `risk` while the register says
   `credit`.
7. **Every `INV-*` the glossary cites exists.** Forty-two distinct invariants, cited
   seventy-two times (recounted at the Phase 8 exit review, `P8-DOC-001`, after its nine new
   entries; the Phase 7 → 8 transition had counted thirty-eight and fifty-four, and the Phase 6
   review, `P6-DOC-001`, twenty-five and twenty-nine), none of which any other check would notice
   going stale.
8. Every distinction group has a §2 heading repeating the group exactly, so a group added to
   `CLAUDE.md` fails the build until it is contrasted.
9. All of the above are actually parsed, so a reformatted document fails loudly rather than
   silently matching nothing.

**Not enforced:** that a definition is *correct*. No test can check that. What the guard protects
is that no term is silently undefined, no definition omits its contrast, and no distinction the
platform's own instructions forbid collapsing is left uncontrasted.
