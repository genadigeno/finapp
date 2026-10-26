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
That is no longer so: Phases 1 to 10 built classes for many of them, Phase 6's merchant, checkout,
fee and payout terms, Phase 8's settlement and reconciliation terms, Phase 9's FX and
cross-border terms and Phase 10's credit terms included. Entries still name
no class, except where a class's name collides with a term (`MerchantSettlement`, §2 and §5). The
owning module column names where each concept lives, or — for a later phase's term — **will**
live, per [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md) §4, which records the
phase each module arrives in. *(Corrected at the Phase 6 review, `P6-DOC-001`: this said "Nothing
here is implemented". "Phases 1 to 6" corrected to 1 to 8 at the Phase 8 exit review,
`P8-DOC-001`, 2026-10-01, to 1 to 9 at the Phase 9 exit review, `P9-DOC-001`,
2026-10-07, and to 1 to 10 at the Phase 10 exit review, `P10-DOC-001`, 2026-10-09.)*

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

They also have different owners. *(Settled by the Phase 9 → 10 transition, 2026-10-07 — ADR-0084
(`Proposed`), `PHASE_10_PLAN.md` §3:)* the Credit Score, Underwriting and the Credit Decision are
`credit`'s (Phase 10); the Risk Score is `risk`'s (Phase 13). Credit consumes a risk signal as one
input among many through its `CreditRiskSignal` port, records the answer it was given in the
Decision Snapshot, and never computes a risk score of its own (§10).

*(The Phase 12 → 13 transition, 2026-10-22 — ADR-0107…0109 (`Proposed`), `PHASE_13_PLAN.md` §3.1 and §12.2: the Risk
Score is now defined to the design — an integer 0–1000, Σ rule contributions clamped, a field of the Risk Assessment,
never stored anywhere else — and the signal credit consumes is the Customer Risk Profile's rating code with seam
version 2 (`P13-TSK-007`), never the score; the four stay four, and the risk-side terms are in §8a.)*

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

**A BNPL purchase has the second flow without the first** *(added by the Phase 11 → 12 transition,
2026-10-16 — `PHASE_12_PLAN.md` §1, ADR-0101; built by `P12-TSK-013`)*: no customer payment happens at purchase. The
platform credits the merchant's payable the gross less the Merchant Discount out of its own lending
capital — the Merchant Financing — and the merchant is paid out by the same on-demand payout; the
customer instead owes the platform a receivable, repaid in instalments from the wallet — the Customer
Repayment Obligation. Still two flows of one economic event, never collapsed: the merchant's amount,
timing and account are the financing's, the customer's the obligation's (§7b). Pay-in-4's first instalment, taken from the wallet in the purchase's own transaction (a repayment of kind `AT_PURCHASE`, posted beside the financing), is the obligation's first repayment — not a customer payment to the merchant. *(`P12-DOC-001`, 2026-10-22: read "ADR-0101, planned" until the Phase 12 exit review, which read it against the code and added the pay-in-4 sentence.)*

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

### Screening Result
**Is:** kyc's recorded outcome of one screening of a party or counterparty against sanctions and PEP lists — at
onboarding, or at a transaction-time check — `CLEAR`, a hit, or a person's release, with the evidence and the basis it
was decided on; Counterparty Screening (§8) is its transaction-time form for an external party.
**Not:** `risk`'s state — Phase 13 reads clearances through a port and records what it was given as a Risk Signal; a
Screening List Version and the rescreening its activation enqueues are `risk`'s, the screening itself and its result
stay kyc's (`ROADMAP.md` Refinement 3; `MODULE_ARCHITECTURE.md` §5 "Screening result reads like one state but is
two"). Nor a KYC Case: a case may run several screenings. *(Added by the Phase 12 → 13 transition, 2026-10-22: the
canonical list never named it, though the register has owned it to `kyc` since `P0-TSK-006`; Phase 13 reads it, so
the list names it.)*
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
*(`P13-TSK-009`: a person's withdrawal is judged by risk in its dispatch transaction, after the
hold - permitted it is `DISPATCHED` as before; held it is **`HELD`**: accepted, the wallet's hold
KEPT, nothing sent, until a person releases it (it is then dispatched) or denies it, or its hold
validity lapses on the database clock (it then fails `RISK_REFUSED`, detail `HOLD_EXPIRED`, the
hold released); refused it fails `RISK_REFUSED` with the hold released at once. The customer reads
`HELD` as a status word and nothing of why. A `HELD` withdrawal is not a Hold - the hold is the
ledger's reservation it keeps - and not in flight: the scheme has never heard of it.)*
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
settled by the transition's consistency review, A12 and A13.)* *(The BNPL terms, `P12-TSK-016`: + BNPL financed − BNPL fees − BNPL refunded + BNPL fees returned - the financing (F1, `bnpl.financing:`) and the BNPL merchant refund (F5, `bnpl.refund:`), told apart from a capture's and a refund's by their posting key, never their counterparty. A merchant transaction of the kinds `BNPL_FINANCING` and `BNPL_REFUND` in `GET /v1/merchant/transactions`.)*
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

*(Fourteen Phase 10 terms added alphabetically after the eleven originals, and Credit Profile,
Credit Score, Risk Score, Credit Decision, Underwriting and Exposure sharpened to the design, by
the Phase 9 → 10 transition, 2026-10-07 — ADR-0084…0089 (`Proposed`), `PHASE_10_PLAN.md` §3 and
§12. Nothing of Phase 10 is built: every `credit` entry below is the decided design, marked
planned, and the task that builds a concept corrects its entry to the code. The machines they
name are in `CREDIT_DECISIONING_LIFECYCLES.md`.)*

*(Built, and read against the code by the Phase 10 exit review, `P10-DOC-001`, 2026-10-09:
`P10-TSK-001`…`-021`, `X-TSK-017` and `P10-TST-001` built every concept below, so the *planned*
markers are gone and each entry states what was built. Credit Bureau Record is renamed Credit
Record — one `credit_record` table holds both source kinds — and six terms are added
alphabetically, each its own table or code concept that neither list named: Credit Data Request,
Credit Evidence, Decision Consumption, Decision Replay, Engine Version and Policy Evaluation.
Affordability Assessment, Credit Assessment, Credit Attribute, Credit Data, Credit Data Source,
Credit Policy, Credit Product and Underwriting Case corrected to the code.)*

*(The Phase 10 → 11 transition, 2026-10-10, sharpened Loan Application, Loan Offer, Loan,
Instalment and Delinquency to `PHASE_11_PLAN.md`, gave Decision Consumption its owner — credit's
port, `P11-TSK-001` — and Exposure its version-2 measure (`P11-TSK-013`), each with a dated note.
Those corrections were planned then; Phase 11 built them, read against the code by the Phase 11 exit
review, `P11-DOC-001`, 2026-10-15 (this read "Those corrections are planned, not built" until then).
The twenty-three new lending terms are in §7a.)*

*(The Phase 11 → 12 transition, 2026-10-16, sharpened BNPL Agreement to `PHASE_12_PLAN.md` and
narrowed Instalment to the loan's — the register's Loan Instalment — each with a dated note; both planned then. Phase 12 built them, read against the code by the Phase 12 exit review, `P12-DOC-001`, 2026-10-22 (this read "both planned, not built" until then), which also corrected Credit Product, Exposure and Decision Consumption here, and Lending Capital in §7a, for what Phase 12 changed in them. The fourteen new BNPL terms are in §7b.)*

### Credit Profile
**Is:** a Party's credit identity in this platform — one row per party, born once, holding no
figures of its own — and the row every deciding transaction for that party locks first, so two
decisions for one party serialise.
**Not:** the Credit Data, a Credit Score or a Credit Decision. The profile is the anchor; the data
is evidence, the score one derived figure and the decision the outcome. *(This entry read "the
assembled view of a Party's credit position — bureau data, internal history, exposure" until the
Phase 9 → 10 transition, 2026-10-07: assembling those is the Decision Snapshot's job, per
request, and a profile that stored them would be a second, drifting copy.)*
**Owned by:** `credit`

### Credit Score
**Is:** one derived figure — the versioned scorecard Model Version's points over a Decision
Snapshot, integer arithmetic only — answering *how likely is this Party to repay?*.
**Not:** a Risk Score, and not a Credit Decision (see §2). Nor a bureau's own score, which is a
Credit Attribute in the snapshot — evidence the scorecard may read, never our score. *(This entry
read "ours or a bureau's" until the Phase 9 → 10 transition, 2026-10-07.)*
**Owned by:** `credit`

### Risk Score
**Is:** an integer 0–1000 — the sum of the contributions of the Rules that fired in one Risk Assessment, clamped —
answering *how likely is this to be fraud or abuse?*; a field of the assessment, pinned with the policy and engine
versions that produced it, never stored anywhere else. *(Sharpened by the Phase 12 → 13 transition, 2026-10-22 —
ADR-0107…0109 (`Proposed`), `PHASE_13_PLAN.md` §3.1 and §12.2; this read "a numeric assessment of the likelihood that
an action is fraudulent or abusive" until then.)*
**Not:** a Credit Score. Different question, different inputs, different consequence — and summing
them produces a number that answers nothing (`INV-CRD-04`). Nor a Risk Decision: a score band maps to a decision, a
hard rule's decision beats a band's, and the decision is what the seam answers. Credit consumes a risk *signal* through
its `CreditRiskSignal` port and records what it was given; in Phase 10 the port answers `NOT_ASSESSED` for every party,
deterministically, so a decision made before Phase 13 replays identically after it; Phase 13's `app` implements the
port over the Customer Risk Profile's rating with seam version 2 (`P13-TSK-007`), never over this score. See §10: the
owner is settled.
*(As built, `P13-TSK-003`, 2026-10-23: `Score`, an integer record bounded to [0, 1000], `Score.clamped(sum)` the one
clamp; the decision is the strictest of the score band's and every fired hard decision — a band never softens a hard
rule — and with nothing fired the band covering 0 decides; the `MONITORING` version's score maps to a rating, never a
decision.)*
**Owned by:** `risk` (Phase 13) *(was `credit` until the Phase 9 → 10 transition, 2026-10-07 —
ADR-0084, `MODULE_ARCHITECTURE.md` §4)*

### Credit Decision
**Is:** the recorded, immutable outcome for one Decision Request — `APPROVED` or `DECLINED`, the
approved amount and term, its validity, the ordered Reason Codes, who decided (the system or a
person), the pinned Policy Version, Model Version and engine version, and its Decision Snapshot's
hash — born once, never updated or deleted by any role.
**Not:** a score, and not Underwriting (the assessment that produced it). Nor a loan, or a Loan
Offer: Phase 11's Loan Application references a decision; the decision moves no money.
`INV-CRD-01` requires it to be reproducible; `INV-CRD-02` requires reason codes sufficient for an
adverse-action explanation. A change of mind is a new request, never an update; its consumption
by a Phase 11 loan is a separate born-once fact, the Decision Consumption, never a change to the
decision.
**Owned by:** `credit`

### Underwriting
**Is:** the assessment weighing a Party's Credit Assessment against a versioned Credit Policy —
the deterministic evaluator's run over a snapshot or, for a referral, a person's review in an
Underwriting Case.
**Not:** the Credit Decision it produces. See §2. Nor the Underwriting Case, which is the record of
one manual review; underwriting is the activity, automatic or manual.
**Owned by:** `credit`

### Loan Application
**Is:** one customer's request for one lending product (`PERSONAL_LOAN` or `CREDIT_LINE`), with its
own lifecycle — `SUBMITTED → AWAITING_DECISION → OFFERED | DECLINED | CLOSED_UNDECIDED | WITHDRAWN`
— that opens exactly one credit Decision Request in its own transaction, through credit's
submission port, and stores only that request's id; the terms version active at submission is
recorded on it - informational, the offer pins the version active at its own instant. Built by
`P11-TSK-010`: it holds no figure - the amount or limit, the term and the declared income and
expenditure are the Decision Request's - only the product, the request's id, the terms version,
the repayment or statement day, the Repayment Method and the payout preference.
**Not:** a Loan Offer — an application may be declined, withdrawn or closed undecided without any
offer. Nor the Decision Request, which is credit's envelope asking only for a decision: lending
never reads a score, an attribute or a threshold, and a decline is never reconsidered (a new
application). *(This entry read "a Party's request for credit, with its own lifecycle" until the
Phase 10 → 11 transition, 2026-10-10.)*
**Owned by:** `lending`

### Loan Offer
**Is:** the terms the platform will grant on one approved Credit Decision and the pinned Loan
Product Terms Version — exactly the approved amount and term, or limit (no counter-offer), as
canonical terms with their SHA-256 — expiring at the earlier of the decision's validity and the
terms' offer validity, judged on the database clock: `OFFERED → ACCEPTED | DECLINED | EXPIRED`,
never `EXPIRED → ACCEPTED`. The pinned version is the one `ACTIVE` at the offering instant, not the one recorded on
the application; a loan offer shows its illustrative schedule from the offering day with the total interest and total
payable (A5), a line offer its rate and minimum-payment rule. Built by `P11-TSK-011` (the machine row money-free, the
terms INSERT-only; a decline only while unexpired; acceptance `-012`'s).
**Not:** a Loan, nor the Loan Agreement: nothing is contracted or disbursed, and an expired offer
is not a loan that failed. An expired or declined offer releases nothing in credit — the unconsumed
decision lapses at its own validity. *(This entry read "terms the platform is prepared to grant,
with an explicit expiry" until the Phase 10 → 11 transition, 2026-10-10.)*
**Owned by:** `lending`

### Loan
**Is:** the loan account, of one of two kinds — an amortising `INSTALMENT` loan
(`PENDING_DISBURSEMENT → ACTIVE → CLOSED`, or `→ CANCELLED` before disbursement) or a revolving
Credit Line (`ACTIVE → CLOSING → CLOSED`) — holding identity, kind, lifecycle state and permits
only: **no amount, rate or balance column**. Its contractual figures live on the immutable Loan
Agreement version, and every changing figure is a ledger balance of its six per-loan accounts,
derived from journal lines (`INV-LND-01`). Born at the offer's acceptance by `P11-TSK-012` (one per offer and per
decision, with its six accounts; a loan `PENDING_DISBURSEMENT`, a line `ACTIVE`); its later edges are `-014`'s,
`-023`'s and `-026`'s.
**Not:** a Loan Offer, and not the Exposure it contributes to. Nor its conditions: delinquency and
default are conditions recorded beside the lifecycle, so an `ACTIVE` loan is `ACTIVE` whether
current or 120 days past due, and a restructuring is a new agreement version, never a state. Closed
is closed, and closed means settled (`INV-LND-08`). *(This entry read "a disbursed credit agreement
being serviced — schedule, accruals, repayments, state" until the Phase 10 → 11 transition,
2026-10-10: a line is a loan before any draw, and none of those figures is a column of it.)*
**Owned by:** `lending`

### Instalment
**Is:** one scheduled repayment within a loan's Repayment Schedule — an amount and a due date; the
module register's **Loan Instalment** (`MODULE_ARCHITECTURE.md` §5). In Phase 11 a row of one
Repayment Schedule version, projected by the pinned schedule engine; what became due at its date is
its Instalment Billing, not the projection.
**Not:** a BNPL Instalment (§7b), which shares the word and not the lifecycle — a merchant's refund
can cancel it, and nothing but an amendment or a prepayment changes a loan's; the register named the
two Loan Instalment and BNPL Instalment, and the Phase 11 → 12 transition, 2026-10-16, confirmed the
split (the BNPL obligation is not a lending loan, ADR-0101). Nor a Repayment, which is money actually
received, and not a Repayment Allocation, which is how received money settled it. An instalment can
be upcoming, due, past due or paid; a repayment is an event. Nor the Minimum Payment of a Credit Line,
which has no schedule. Both spellings appear in the wild; this platform uses **Instalment**
throughout (`P0-DOC-011` corrected the one American spelling in the canonical list). *(The Phase 10 →
11 transition, 2026-10-10, added the schedule version, the billing and the allocation to this entry;
it read "within a loan or BNPL schedule" until the Phase 11 → 12 transition, 2026-10-16.)*
**Owned by:** `lending`

### Exposure
**Is:** the platform's aggregate at-risk amount for a Party, across every credit product. In
Phase 10 it is computed per request as bureau total balance + platform outstanding credit (zero
until Phase 11's loans, recorded) + the **reserved exposure** of the party's current approvals
(approved, unexpired on the database clock, with no consumption fact) + the requested amount, and
the reserved part is re-read under the party's Credit Profile lock in the deciding transaction, so
concurrent approvals never together exceed the policy's limit.
From Phase 11 the platform outstanding credit is `PlatformCreditExposure` **version 2** (built by
`P11-TSK-013`, 2026-10-12 - `app`'s `LendingCreditExposure` over lending `V007`'s definer view
`party_principal_exposure`): one statement over lending's rows and the ledger's journal lines answering, per
party and currency, the committed principal of loans awaiting disbursement, the outstanding
principal (`LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE`) of loans not cancelled, the **limit** of every
open (`ACTIVE`) Credit Line and the drawn principal of a closing one — principal only; and every
lending transaction that raises it takes the same profile lock first (`INV-LND-06`). Decisions
made under version 1 replay identically. From Phase 12 it is **version 3** (built by `P12-TSK-012` - `app`'s `BnplCreditExposure`, one statement over lending `V007`'s and bnpl `V007`'s definer views `party_principal_exposure` and the journal lines): version 2's figure plus, per BNPL obligation of the party in the currency, the net of its `BNPL_PRINCIPAL` and `BNPL_PRINCIPAL_DUE` lines whatever its status, a negative counting zero - no committed term, because a purchase is financed in the commit that consumes its decision, under the same profile lock taken first; decisions frozen under versions 1 and 2 replay from their own snapshots. *(`P12-DOC-001`, 2026-10-22: this entry stopped at version 2 until the Phase 12 exit review.)*
**Not:** the outstanding balance of one loan. Exposure is what a limit is checked against, and
computing it per product is how a Party borrows the same limit several times. Nor a ledger
balance: an approval's reservation is a recorded fact that lapses or is consumed, never a hold.
Nor a line's Available Limit, which is its undrawn amount: exposure counts an open line at its full
limit, because it can be drawn without a new decision. *(This entry said "zero until Phase 11's
loans" without the version-2 measure, and "Phase 10 posts nothing", until the Phase 10 → 11
transition, 2026-10-10.)*
**Owned by:** `credit`

### Delinquency
**Is:** the condition arising from contractual payments being missed — `CURRENT`, or `PAST_DUE`
with its Days Past Due and bucket (`1–29`, `30–59`, `60–89`, `90+`, the pinned terms' bounds) —
derived daily by the servicing step from billed and allocated rows on the database clock, and
recorded only as append-only changes, once per account, business date and kind; cured when every
billed amount is paid (`INV-LND-12`). Built by `P11-TSK-024` (`lending V020`'s `loan_condition_event`,
`LoanConditions`, `DelinquencyState`): derived live by every decision that needs it, recorded by the
servicing step's daily evaluation at most once a business date - a repayment that cures is recorded by the
next evaluation, not by itself.
**Not:** a lifecycle state — a delinquent loan stays `ACTIVE`. Nor a Default, which is a flag set
at 90 days past due and posts nothing, and not a Write-off, the deferred accounting event with its
own posting. Delinquency is recoverable, and lending only records it: contact strategy and
placement are a future collections owner's. *(This entry read "the state arising from contractual
payments being missed, with defined stages", and called default "an accounting event with its own
postings", until the Phase 10 → 11 transition, 2026-10-10.)*
**Owned by:** `lending`

### BNPL Agreement
**Is:** the immutable, versioned contract binding one customer, one merchant's BNPL Purchase and one
plan (Pay-in-4, or Pay-in-N over 3, 6 or 12 months) — version 1 born at the BNPL Offer's acceptance,
the offer's canonical terms copied byte for byte beside the acceptance evidence; version n+1 when a
refund cancels or shortens an unbilled instalment — one `INSERT`-only row per version, its status
the Customer Repayment Obligation's. Born in the one transaction that also finances the merchant and
creates the receivable the customer owes (`INV-BNPL-02`); its id is the consumer reference under
which credit records the decision's consumption (`ConsumerKind.BNPL_AGREEMENT`). Version 1 built by `P12-TSK-013` (`bnpl V006`'s `bnpl_agreement`, `UNIQUE (obligation_id, version)`, INSERT-only and immutable for every role; its evidence `bnpl_acceptance`, `MULTI_FACTOR` by `CHECK`), version n+1 by `P12-TSK-021` (`V012`: version n's terms, engines and pins copied exactly, beside its refund and the schedule version it governs); ADR-0101, ADR-0102. *(`P12-DOC-001`, 2026-10-22: read "Planned" until the Phase 12 exit review.)*
**Not:** a Loan or a Loan Agreement. Its origination is embedded in a purchase, the merchant is
financed separately in the same commit, and a returned item adjusts the agreement — none of which a
loan does; nor is it a lending `loan` of a new product (ADR-0101 refused composing lending as its
back-end). Nor the BNPL Offer, which may expire, and acceptance is not Consent (`INV-IDN-04`).
*(This entry read "a short-term instalment credit agreement created at the point of checkout" until
the Phase 11 → 12 transition, 2026-10-16.)*
**Owned by:** `bnpl`

### Affordability Assessment
**Is:** the part of a Credit Assessment that asks whether the Party can carry the repayment: in
the product's one currency, exact decimal, income (the lower of verified and declared) less
expenditure (the higher of verified and declared), bureau obligations and the requested credit's
repayment — an annuity at the policy's stress rate for `PERSONAL_LOAN`, the limit times the
policy's minimum payment ratio for `CREDIT_LINE` — compared with the policy's minimum disposable
income, carried at scale 10 and rounded once to minor units (`INV-CRD-12`). A missing input
makes it unassessable, naming the absent attribute codes — never a zero.
**Not:** a Credit Score (likelihood of repaying, from history) and not Exposure (how much credit
the Party already holds). A source in another currency is never converted: it is a recorded
partial-data case.
**Owned by:** `credit`

### Credit Assessment
**Is:** the derived figures for one Decision Snapshot — affordability, exposure and the Credit
Score — computed once per snapshot (`UNIQUE (snapshot_id)`, `credit V007`) under its pinned
versions, and referencing its inputs by the snapshot's id and SHA-256 and the pinned policy,
model and engine versions; a figure that could not be computed is recorded unassessable with the
attribute codes that were absent, never a default.
**Not:** a Credit Decision, and not Underwriting. The assessment is arithmetic; underwriting weighs
it against policy, and the decision records the outcome (`INV-CRD-04`). Nor the Policy
Evaluation, which is that weighing's result, and not `risk`'s Risk Assessment, which answers a
fraud question.
**Owned by:** `credit`

### Credit Attribute
**Is:** one typed input the decision engine may read — a code from a closed vocabulary (e.g.
`BUREAU_DELINQUENCIES_24M`, `DECLARED_MONTHLY_INCOME`, `PLATFORM_RESERVED_EXPOSURE`,
`RISK_SIGNAL`), a value (integer, money as minor units with currency, boolean or code, or `ABSENT`)
and its provenance (`AttributeProvenance`): the Credit Record it was read from, with its source
kind, provider and normaliser version; an unavailable source's Credit Data Request; a source kind
the policy does not read; the applicant's declaration; or a platform port and its version.
**Not:** the raw evidence it was normalised from, and not a rule. A rule reading an attribute the
snapshot lacks is an evaluation error, never a default; a missing optional attribute is the value
`ABSENT`, which a policy must reason about explicitly.
**Owned by:** `credit`

### Credit Data
**Is:** evidence about a Party's credit retrieved from a Credit Data Source through a Credit Data
Request — kept as received in Credit Evidence (encrypted) and normalised to a Credit Record's
Credit Attributes — and retrieved only under a recorded, current lawful basis (`INV-CRD-03`).
**Not:** the Credit Profile, and not a Credit Score. Data is evidence; a profile is an anchor and a
score a derived figure. Bureau data is restricted financial PII: it never appears in a log, a
metric, an event or a customer response.
**Owned by:** `credit`

### Credit Data Request
**Is:** one ask of one source kind for one Decision Request (`credit.data_request`, `V004`) —
born `REQUESTED` under its own reference (`CDR-<id>`), the provider selected once at birth and
stamped on it, its deadline and retry cadence frozen from configuration — moving
`REQUESTED → RECEIVED | UNAVAILABLE | CONSENT_WITHDRAWN`, `UNAVAILABLE → REQUESTED` (a retry,
before the deadline only) and `UNAVAILABLE → CONSENT_WITHDRAWN`, every edge judged by a trigger
for every writer and every window on the database clock; each ask is an attempt row, and the
consent gate is re-read before each ask and at the recording (`INV-CRD-03`).
**Not:** the Decision Request it serves, and not the Credit Record its answer makes. A lost
response is re-asked under the same reference, so the provider counts one pull; there is no
failover to another provider; and a record found stale at the freeze opens a new data request
rather than re-using the old (`INV-CRD-08`).
**Owned by:** `credit`

### Credit Data Source
**Is:** the declared kind of an external credit input — a credit bureau (`BUREAU`) or a
financial-data provider (`FINANCIAL_DATA`), each behind the provider-neutral `CreditDataSource`
port (`CreditBureau`, `FinancialDataProvider`) and its own consent purpose
(`CREDIT_BUREAU_ACCESS`, `FINANCIAL_DATA_ACCESS`) — and the provider answering for it, selected
per kind from a configured order and stamped on each Credit Data Request at its birth.
**Not:** the provider itself, which is `external` and reached only through an adapter whose formats
never leak into the domain (ADR-0008). Nor the applicant's declaration or a platform port: those
are other provenances of a Credit Attribute, never a source. A source unavailable past its
deadline yields `ABSENT` attributes and the policy's declared fallback — never an approval
(`INV-CRD-10`). Until unresolved questions #13 and #14 are answered production names no provider,
and each kind's fail-safe answers unavailable.
**Owned by:** `credit`

### Credit Evidence
**Is:** the raw bytes a provider delivered for one attempt of a Credit Data Request, kept as
received (`credit_evidence`, `V004`) — AES-256-GCM under credit's own key
(`finapp.credit.evidence.key`), the evidence id as associated data, with the plaintext's SHA-256
and a `retain_until` from the product's retention — born once, never updated or deleted, and read
by the application only through the reasoned, audited `credit.read_evidence` function.
**Not:** the Credit Record. The record is the normalised attributes a decision reads; the evidence
is what the provider actually said, kept so the normalisation can be checked and a dispute
answered. A duplicate answer is evidence flagged `duplicate`, never a second record; an answer
arriving after consent was withdrawn is flagged and its payload discarded unread.
**Owned by:** `credit`

### Credit Policy
**Is:** a credit product's rules as data — ordered rows over a closed vocabulary of operators,
derived figures and effects (`HARD_DECLINE`, `DECLINE`, `REFER`, `CAP_AMOUNT`), each naming its
Reason Code — plus the policy's parameters: stress rate, minimum disposable income, maximum
exposure, maximum data age per source kind, the fallback for an unavailable source and the
auto-approval ceiling (and, for a revolving product, the minimum payment ratio) — judged whole
when proposed: a fallback for every source kind it reads (`INV-CRD-10`) and a rule guaranteed to
stop an approval past its maximum exposure (`INV-CRD-09`), else `credit.PolicyIncomplete`.
**Not:** code. Policy as opaque, unversioned conditionals is forbidden (`CREDIT_MODEL.md`); a rule
the vocabulary cannot express is a new operator in a new engine version, reviewed. Nor a Policy
Version: the policy is the per-product line of versions.
**Owned by:** `credit`

### Credit Product
**Is:** a closed enumeration of what credit can be decided for — Phase 10's `PERSONAL_LOAN` and
`CREDIT_LINE` — each declaring its currency, amount and term bounds, four-eyes threshold, request
validity (seven days, how long a Decision Request may stay open), decision validity (thirty days,
how long an approval reserves exposure) and evidence retention. Since `P12-TSK-001` (credit `V022`) the enumeration has four members: the BNPL family's `BNPL_PAY_IN_4` (EUR 50.00 to 1,000.00, a fixed plan of four instalments, no term) and `BNPL_PAY_IN_N` (EUR 200.00 to 5,000.00, a term of 3, 6 or 12 months), each with a request validity of one hour and a decision validity of one day, requested only from a BNPL Application (`RequestOrigin.BNPL_APPLICATION`), taken up only by a BNPL Agreement, and decided automatically only — never referred to an Underwriting Case. *(`P12-DOC-001`, 2026-10-22: this entry named only Phase 10's two products until the Phase 12 exit review.)*
**Not:** a Loan or a Loan Offer, which are Phase 11's. A product is a reviewed code change with its
migration, never a string a caller supplies.
**Owned by:** `credit`

### Credit Record
**Is:** the normalised Credit Attributes of one answered Credit Data Request — born once per data
request (`credit_record`, `UNIQUE (data_request_id)`, `V004`), from either source kind
(`source_kind` `BUREAU` or `FINANCIAL_DATA`), stamped with its provider and normaliser version
(`INV-CRD-07`) and with the provider's stated retrieval beside our own recording — beside the raw
answer kept as Credit Evidence with a declared retention deadline.
**Not:** the source's own state (`external`), and not the Credit Profile. A record is what one
source said at one retrieval time; it can be stale, and stale data never decides — its age runs
from the earlier of its retrieval and its recording (`INV-CRD-08`). *(Named Credit Bureau Record,
with a financial-data record as its sibling, until the Phase 10 exit review, `P10-DOC-001`,
2026-10-09: one table holds both kinds.)*
**Owned by:** `credit`

### Decision Consumption
**Is:** the born-once fact that a Phase 11 loan or a Phase 12 BNPL agreement has taken up one approved Credit Decision —
`credit_decision_consumption`, `UNIQUE (decision_id)`, append-only by trigger (`V011`) — created
empty in Phase 10; an approval with a consumption row reserves no exposure. **Credit is its only
writer** (built by `P11-TSK-001`, credit `V021`, 2026-10-10): the credit-owned port
`CreditDecisionConsumptions.consume` takes the party's Credit Profile lock, refuses a decision
not `APPROVED`, lapsed or consumed by another, replays for the same consumer, and inserts through a
`SECURITY DEFINER` function that is the table's only path (the application role's `INSERT`
revoked). Lending's offer acceptance calls the port, in its own transaction, and since `P12-TSK-013` so does bnpl's, in the purchase's transaction, through `app`'s `CreditBnplDecisions` *(`P12-DOC-001`, 2026-10-22: the entry named lending's caller alone)*. It names its consumer
(`ConsumerRef`: `LOAN_AGREEMENT` - or, since `P12-TSK-001`, `BNPL_AGREEMENT`, each only of its own product family - and the agreement's id; one consumer takes up one decision) and is
stamped with the database instant judged under the lock, always before the approval's `valid_until`.
**Not:** a change to the decision, which is never updated (`INV-CRD-02`), and not a loan — it
records only that one took the approval up. Nor a lending table: lending never writes credit's
rows. Nor a lapse: an approval past its validity stops reserving on the database clock, with no
row. *(This entry read "Phase 11 its only writer" until the Phase 10 → 11 transition, 2026-10-10:
the write had no owner, and credit owns it.)*
**Owned by:** `credit`

### Decision Replay
**Is:** the proof that a recorded Credit Decision is reproducible (`INV-CRD-01`) — the snapshot's
SHA-256 recomputed over its stored canonical text, then the pinned policy, scorecard and engine
re-run in memory over it — answering `IDENTICAL`, or `DIVERGED` naming what differed (`HASH`,
`OUTCOME`, `AMOUNT`, `REASONS` or `UNREPLAYABLE`); a person's decision is verified against its
Underwriting Case. Read-only, in one `REPEATABLE READ` snapshot; the operator's replay is audited.
**Not:** a re-decision. A replay records, changes and supersedes nothing, and reads only the
versions the decision pinned — never what is in force now. Nor the FX plan replay, which
recomputes a Posting Plan.
**Owned by:** `credit`

### Decision Request
**Is:** the envelope of one application for a credit decision — party, Credit Product, requested
amount and term, declared income and expenditure — submitted under an idempotency key and
progressed `SUBMITTED → COLLECTING → READY → EVALUATED → DECIDED` (through `IN_REVIEW` on a
referral; `READY → COLLECTING` when a record is found stale at the freeze), or closed
`CANCELLED` (by the applicant, before evaluation), `EXPIRED` (no decision within its validity) or
`ABANDONED` (closed by the platform with a reason — `STANDING_LOST`, `CONSENT_WITHDRAWN`); at
most one open per party and product, decided at most once (`INV-CRD-06`).
**Not:** a Loan Application. A decision request asks only for a decision; Phase 11's loan
application is the lending aggregate that references the decision. Nor the Credit Decision:
a request may close with none.
**Owned by:** `credit`

### Decision Snapshot
**Is:** the frozen, complete and sealed input of one evaluation — every Credit Attribute any rule,
the scorecard or the arithmetic reads, with its provenance, in canonical form sorted by code, plus
the requested product, amount and term and the pinned versions — and the SHA-256 of that canonical
form, stored and re-verified. One per evaluation, numbered per request: the first at the freeze,
and a successor (the same records, the new exposure) only when the deciding transaction finds the
party's reserved exposure changed; the decision names the snapshot it was made from, and the
request keeps every one in history (`INV-CRD-07`).
**Not:** the Credit Data it was built from, and not the Credit Profile. Records arriving after the
freeze belong to no snapshot; a frozen snapshot is never re-collected (a new request is). Replaying
the snapshot under its pinned versions must reproduce the decision (`INV-CRD-01`).
**Owned by:** `credit`

### Engine Version
**Is:** the version of credit's deterministic engine — the assessment arithmetic and the policy
evaluator (`PolicyEvaluatorV1`), both version 1 in Phase 10 — pinned on the Decision Request with
the policy and model versions and carried by its snapshot, assessment, Policy Evaluation and
Credit Decision; `EngineVersions` holds every engine the build can replay and selects the one
pinned, never the newest.
**Not:** a Policy Version or a Model Version, which are data; the engine is code. A change to what
the engine computes is a new engine version added beside the old, which stays for replay — never
an edit of the old (`INV-CRD-01`).
**Owned by:** `credit`

### Model Version
**Is:** one immutable version of a scorecard model family (`RETAIL_SCORECARD`) — a points table as
rows: per attribute, ordered bands each with integer points, plus a base and an absent band —
proposed, then activated by a second person, at most one `ACTIVE` per family.
**Not:** a Policy Version (the model scores; the policy decides), and not a machine-learned model,
which Phase 10 does not build. A request pins the version when collection begins, its decision is
scored by that version, and an activation mid-request never changes it (`INV-HIST-04`).
**Owned by:** `credit`

### Policy Evaluation
**Is:** what the pinned Credit Policy concluded about one Credit Assessment under the pinned Engine
Version — the outcome (`APPROVE`, `REFER`, `DECLINE` or `HARD_DECLINE`), the approved amount, the
reason codes and whether the declared fallback applied, with every rule's triggered and assessed
state — computed by a pure evaluator and born once per assessment (`policy_evaluation`,
`UNIQUE (assessment_id)`, with `policy_evaluation_rule`, `V009`).
**Not:** the Credit Decision (`INV-CRD-04`): the evaluation is the evaluator's word, and the
deciding transaction records the decision — or, on `REFER`, opens an Underwriting Case where a
person decides. Nor Underwriting, the activity that runs it.
**Owned by:** `credit`

### Policy Version
**Is:** one immutable version of a Credit Policy for one product — `PROPOSED → ACTIVE → RETIRED` or
`PROPOSED → REJECTED`, activated only by a second person, at most one `ACTIVE` per product, its
rules born with it and immutable for every writer from insert, and which version was active at any
past instant answerable from the rows (`INV-CRD-05`).
**Not:** fx's pricing-policy or crossborder's corridor-policy versions, which share the shape but
not the state. A request pins the version when collection begins (`SUBMITTED → COLLECTING`), its
evaluation and decision read exactly that version, and an activation mid-request never changes it
(`INV-HIST-04`).
**Owned by:** `credit`

### Reason Code
**Is:** an entry in a closed, migration-seeded catalogue — code, category, customer text and
adverse flag — that a triggered rule names; a decision carries its reason codes in rule order,
deduplicated, and every adverse decision carries at least one.
**Not:** an error code (`ERROR_CONTRACT.md`) and not an internal figure. The customer receives the
adverse reasons' customer texts in order — never a score, a threshold, an attribute or a bureau's
data (`INV-CRD-02`).
**Owned by:** `credit`

### Underwriting Case
**Is:** the record of one manual review of a referred Decision Request — born once per referral,
`OPEN → ASSIGNED → DECIDED`, released `ASSIGNED → OPEN`, through `AWAITING_SECOND` when an
approval is above the product's four-eyes threshold (a disagreeing second approver refusing it
back to the first, `AWAITING_SECOND → ASSIGNED`, the first decision cleared), or `CLOSED` when its
request closes undecided (`V013`) — in which a person decides `APPROVED` or `DECLINED` with at least one
Reason Code, an approval bounded by the referral's ceiling (`approvable_minor` - the request capped
by every cap its evaluation triggered; a `REFER` approves no amount) and the exposure limit re-read
under the profile lock, and never while that exposure is unassessable (`INV-CRD-11`, `INV-CRD-09`).
**Not:** Underwriting itself (the activity, usually automatic), and not `risk`'s Case or `kyc`'s
Review Task. A person may never approve a request whose evaluation included a hard decline, and
never be their own case's second approval (`INV-AUD-04`). The person's decision is *the* Credit
Decision, recorded once.
**Owned by:** `credit`

---

## 7a. Lending

*(Added by the Phase 10 → 11 transition, 2026-10-10 — ADR-0090…0100 (`Proposed`),
`PHASE_11_PLAN.md` §3 and §12. Twenty-three Phase 11 terms, in the order `DOMAIN_MODEL.md` lists
them. Phase 11 built every concept below except those marked **Deferred** (Refinance, Write-off,
Collections) - each entry states what was built, read against the code by the Phase 11 exit review,
`P11-DOC-001`, 2026-10-15; this note read "Nothing of Phase 11 is built: every entry is the planned
design, and the task that builds a concept corrects its entry to the code" until then. The machines they name are in
[`LENDING_LIFECYCLES.md`](LENDING_LIFECYCLES.md). Loan Application, Loan Offer, Loan, Instalment,
Exposure, Delinquency and Decision Consumption stay in §7, sharpened.)*

### Loan Agreement
**Is:** the immutable, versioned contract of one Loan — version 1 born at the offer's acceptance,
version n+1 at each accepted amendment or prepayment — one `INSERT`-only row per version carrying
the full, self-contained canonical terms (principal or limit, currency, rate, term, repayment or
statement day, fees, allocation order, day count, servicing zone, rounding, delinquency bounds,
engine and template versions) and their SHA-256, beside the acceptance evidence of what the customer
saw and how they were authenticated. Every servicing row names the agreement version it was
computed under. Version 1 built by `P11-TSK-012`: the offer's canonical terms (in the offer's format - 1, or 2 from `P11-TSK-031`) and their hash copied byte
for byte - text, never re-serialised - held to the offer's terms by trigger and to its bytes by `CHECK`; its id is the
consumer reference under which credit records the decision's consumption. Version n+1 built by
`P11-TSK-027` (an accepted amendment's, canonical terms format 3) and `P11-TSK-028` (a prepayment's, format 4);
every version's hash re-verified by the replay (`P11-TSK-029`).
**Not:** the Loan Offer (a proposal that may expire) or the Loan (the account and its state). Nor
the Loan Product Terms Version it was built from: a later terms version never changes a signed
agreement (`INV-LND-05`). And acceptance is not Consent (`INV-IDN-04`): no consent purpose is
created.
**Owned by:** `lending`

### Loan Product Terms Version
**Is:** one immutable version of a lending product's terms as data — the nominal rate (fixed only),
fee rules, offer validity, allowed repayment days, allocation order and overpayment treatment,
auto-collection, day count and servicing zone, delinquency bounds and default threshold, a line's
minimum-payment rule and the engine versions — `PROPOSED → ACTIVE → RETIRED` or
`PROPOSED → REJECTED`, activated by a second person, at most one `ACTIVE` per product. A product is
offered only while one is active; production activates none until a real bureau exists. Built by
`P11-TSK-005` (`lending V003`: the machine row `loan_terms_version` and its INSERT-only term set
`loan_terms`).
**Not:** credit's Policy Version, which decides *whether* to lend; terms say *on what conditions*.
Nor the Loan Agreement: an agreement copies the terms it was offered under and keeps them for life
(`INV-LND-05`, `INV-HIST-04`). Never activated by a migration.
**Owned by:** `lending`

### Repayment Schedule
**Is:** one version of a loan's projected instalments, generated by the pinned schedule engine
(version 1: fixed rate, monthly level-payment annuity with actual-day interest, the instalment
rounded once, due dates clamped to month end from the intended day) from an agreement version — at
the accrual start, and again from agreement v n+1 after an accepted change. Principal is conserved
exactly (`INV-LND-03`); billed instalments of an old version are kept and unbilled ones superseded,
nothing updated. The engine is built (`P11-TSK-006`: the level instalment is the actual-day annuity,
of which the textbook `P·i/(1−(1+i)^−n)` is the equal-month case); persisted (`lending V008`'s `repayment_schedule`
and `loan_instalment`) at the accrual start by `-014` (path W) and `-015` (path X), and recalculated by `-027` (an amendment's
`recalculate`) and `-028` (a prepayment's `recalculateAtInstalment`, the same instalment over the least term).
**Not:** the bill. The schedule projects; Instalment Billing bills the interest actually accrued, so
a late payment changes what becomes due while the projection stands. Nor a Credit Line's terms — a
line has statements, not a schedule — and not the offer's illustrative schedule, which is labelled
as such.
**Owned by:** `lending`

### Instalment Billing
**Is:** the born-once fact of what became due on one Instalment at its due date — interest due the
period's posted accruals, principal due the level instalment less that interest (the final one all
remaining principal) — written once per instalment, only after every accrual through the day
before, with one entry re-classifying `LOAN_INTEREST_ACCRUED` and `LOAN_PRINCIPAL` into their due
accounts, then any Loan Credit Balance applied. After the final due date, each month in which interest
accrued on principal still outstanding is billed the same way, interest only (a post-maturity billing).
Built (`P11-TSK-017`: `instalment_billing`, `InstalmentBillings`).
**Not:** the Instalment (the projection) and not a Repayment. Billing moves nothing between parties:
it re-classifies the same claim from not due to due, which is what makes Days Past Due readable. A
Credit Line's equivalent is the Credit Line Statement.
**Owned by:** `lending`

### Interest Accrual
**Is:** the interest one account earned on one calendar date — ACT/365F simple daily interest on
principal outstanding, never on interest or fees, cumulatively rounded so a period's postings sum to
its exact interest rounded once — born once per account and date with its entry
`DR LOAN_INTEREST_ACCRUED / CR LOAN_INTEREST_INCOME`. A date is accruable only once the database
clock, read under the loan's lock, is past its end in the agreement's servicing zone (`INV-LND-02`,
`INV-LND-09`, `INV-LND-10`). Built by `P11-TSK-007` (`AccrualEngineV1` over `Act365Fixed`) and `-016`
(`lending V010`: `interest_accrual`, `UNIQUE (loan_id, accrual_date)`; `LoanServicing`, posting rule 2 under
`lending.accrual:<loan>:<date>`).
**Not:** interest *due* (the billing) or *paid* (an allocation): accrued, due, paid and outstanding
are four words for four things. Nor an event — no per-day event is published; the row and the entry
are the record. Penalty interest and interest on interest are not built.
**Owned by:** `lending`

### BNPL Interest Accrual
**Is:** the interest one pay-in-N BNPL Obligation earned on one calendar date of its *accrual span* — the latest
schedule version's instalment periods from the purchase's business date up to, not including, the final due date —
on `ACCRUAL_ENGINE_V1` (lending's conventions, ACT/365F, cumulative rounding once per instalment period) over the
principal outstanding at the date's end (`BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE` from journal lines valued on or before
it), born once per obligation and date with F2a `DR BNPL_INTEREST_ACCRUED / CR BNPL_INTEREST_INCOME` under
`bnpl.accrual:<obligation>:<date>` (none for a zero day), in T-acc — its own transaction, holding no wallet or payable.
Built by `P12-TSK-017` (`bnpl V009`'s writer of `-015`'s `bnpl_interest_accrual`; `BnplAccruals`).
**Not:** lending's Interest Accrual (a loan's), nor interest due (the billing) or paid. Nothing accrues after the final
due date and pay-in-4 never accrues. Every other BNPL act checks the record *caught up* and never accrues itself.
**Owned by:** `bnpl`

### Repayment
**Is:** money received against one Loan from the borrower's wallet — the customer's repayment, a
scheduled auto-collection or a payoff — born `ALLOCATED`, with its Repayment Allocation and its one
journal entry debiting the wallet, in a single transaction (a row exists only once its money moved),
and `REVERSED` only by an approved, four-eyes repayment reversal (`INV-LND-11`). Built for the customer
(`P11-TSK-018`: `repayment`, `Repayments`, `POST /v1/me/loans/{id}/repayments`) and for auto-collection (`P11-TSK-019`: kind
`AUTO_COLLECTION`, through the same posting half, named by its collection attempt, `LENDING_LIFECYCLES.md` section 4.8) and
for the payoff (`P11-TSK-026`: kind `PAYOFF`, the one repayment of a payoff's execution, keyed by its quote), and as a
partial prepayment (`P11-TSK-028`: kind `PREPAYMENT`, principal not yet due, beside agreement and schedule v n+1). The row
carries no status column: `ALLOCATED` and `REVERSED` are derived (the latter from an approved Repayment Reversal).
**Not:** an Instalment (what was scheduled) or an Instalment Billing (what became due): a repayment
is an event that may settle several, part of one, or none. Nor a failed collection, which is a
collection attempt and never a repayment, and not a Transfer — it credits receivables, not a
wallet. External inbound repayment rails are deferred: money arrives as a wallet top-up.
**Owned by:** `lending`

### Repayment Reversal
**Is:** the four-eyes correction of an operator's mistake in applying money (`P11-TSK-020`, ADR-0099 point 4): one
person holding `LOAN_SERVICE` proposes undoing one Repayment with a reason; a different person holding
`LOAN_SERVICE_APPROVE` approves it, which posts rule 10 - the exact `ReversalService` swap of the repayment's entry,
the wallet credited back - and negates each of its allocation rows by a `repayment_allocation_reversal` row; what it
paid is due again, every later allocation untouched. The Repayment's `REVERSED` status is derived from it
(`repayment_reversal`, `RepaymentReversals`, `POST /v1/operator/loans/{id}/repayments/{rid}/reversal`).
**Not:** an edit or a re-allocation (nothing committed is re-cut), a refund of a closed loan (a closed loan is terminal,
A12), a partial reversal (a reversal is whole), or a Waiver (`P11-TSK-025`).
**Owned by:** `lending`

### Fee Assessment
**Is:** a fee charged to a loan or line on one billed item after origination (`P11-TSK-025`, A18): kind `LATE` - the
pinned late fee, once per item, on the business date its own age reaches the pinned late-fee day (beyond grace) while it
still owes interest or principal, capped per account (the sum never above the pinned cap; a fee the cap partly admits is
charged at what it admits). Posts rule 4 (`DR LOAN_FEES_DUE / CR LOAN_FEE_INCOME`, key `lending.fee:<item>:LATE`) and joins
its item's fees, so it is allocated first by the pinned order and an unpaid fee keeps its item past due; a line's next
statement carries it into the minimum payment (`fee_assessment`, `LateFees`).
**Not:** penalty or default interest (none exists, L8), the origination fee (rule 1, deducted at disbursement), or a
figure anyone edits - a fee is forgiven only by a Waiver.
**Owned by:** `lending`

### Waiver
**Is:** the reasoned, four-eyes forgiveness of an amount a borrower owes (`P11-TSK-025`, A14, ADR-0099): one person
holding `LOAN_SERVICE` proposes, a different person holding `LOAN_SERVICE_APPROVE` approves, which posts - kind `FEE` the
`ReversalService` of the fee's rule 4 entry for at most its unpaid remainder (rule 8), `FEE_REFUND` a paid fee owed back to
the Loan Credit Balance (rule 8a), `INTEREST` billed interest released (rule 9) - each bounded by what is due now under the
loan's lock, the account closed in the same transaction when it settled the last receivable (`waiver`, `waiver_decision`,
`Waivers`, `POST /v1/operator/loans/{id}/waivers`).
**Not:** an edit of a fee or a balance, a Repayment Reversal (which undoes money applied), a write-off (Phase 14), or a
waiver of accrued interest not yet billed (a billing's interest is exactly its period's accruals - refined out).
**Owned by:** `lending`

### Repayment Allocation
**Is:** the split of one Repayment (or one credit-balance application) across due items and
components, born with it in the pinned order — billed items oldest due date first, fees → interest
→ principal within each, the remainder held as Loan Credit Balance (loan) or paying down drawn
principal (line) — computed by a pure engine under the loan's lock from postings, its lines summing
to the amount exactly and none above its component's due (`INV-LND-04`). The engine is built
(`P11-TSK-008`: `AllocationEngineV1`, its `AllocationLine`s); the rows and the postings built by `-017`
(`credit_balance_allocation`) and `-018` (`repayment_allocation`, every line - the held remainder included).
**Not:** the Repayment (the money) and not the journal entry, whose lines are aggregated per
account: the allocation is the per-item explanation the entry's figures must equal. Never edited — a
reversal negates allocations by rows, and later allocations are not re-cut.
**Owned by:** `lending`

### Loan Credit Balance
**Is:** money received beyond every receivable of one loan and owed back to the borrower — the
per-loan liability account `LOAN_CREDIT_BALANCE` — applied automatically at the next billing (or,
held while something is already due, at the next servicing step - a catch-up application, lending `V029`),
refunded to the wallet at closure, and the destination of a paid fee's refund. The account by
`P11-TSK-003`; its application at each billing built by `P11-TSK-017` (`credit_balance_application`);
its refund at closure built by `P11-TSK-018` (`loan_closure`, `lending.closure-refund:<loan>`); a payoff's through the same
closure by `P11-TSK-026`.
**Not:** a wallet balance, and not a prepayment: an overpayment on an instalment loan does not reduce
principal (on a line it pays down drawn principal first, and only the excess is held). Nor a
receivable: it is subtracted from what is outstanding, and a closed loan's credit balance is zero
(`INV-LND-08`).
**Owned by:** `lending`

### Days Past Due
**Is:** the days between the oldest past-due item's due date and the current business date
(database clock, servicing zone), 0 when nothing is past due — derived from billing and allocation
rows each time it is needed, never stored as a counter — from which the bucket, the late fee and
Default follow (`INV-LND-12`). Built by `P11-TSK-024`: `lending.days_past_due(loan, date)` in SQL and
`DelinquencyState.derive` in Java, one rule.
**Not:** the bucket, which is the range DPD falls in from the terms' bounds (`1–29`, `30–59`,
`60–89`, `90+`), and not Delinquency, the condition. Grace delays only the late fee, never DPD; a
partial payment does not reset it — it runs from the oldest due date still unpaid.
**Owned by:** `lending`

### Default
**Is:** a flag on a Loan, set when Days Past Due reaches 90 (the pinned terms' threshold) and
cleared when the account is current again, without probation — announced, suspending a line's
draws, and recorded as an append-only condition change. Built by `P11-TSK-024` (`loan_condition_event`,
`LoanConditions`): set at the threshold, held while anything is past due, cleared at cure.
**Not:** a lifecycle state, and not acceleration: Phase 11 accelerates nothing, charges no default
interest and posts nothing on default. Nor a Write-off (the deferred recognition of the loss), and
not Delinquency, the graduated condition of which default is one threshold.
**Owned by:** `lending`

### Payoff
**Is:** settling everything a loan or line owes, early, in one act — an immutable quote
(good-through date; amount the derived outstanding plus the accrual through the day before) and its
born-once execution on that date, which under the loan's lock catches up accrual and billing,
recomputes, and either refuses as stale or posts one repayment to every component, refunds any
credit balance and closes the account. Built by `P11-TSK-026` (`lending V022`: `payoff_quote`, `payoff_execution`,
`Payoffs`, `POST /v1/me/loans/{id}/payoff-quotes` and `…/payoff`): the quote made on a caught-up account (every elapsed
date accrued under the servicing step's uniques), its figure `P + PD + IA + ID + FD - CB` plus the interest
`ACCRUAL_ENGINE_V1` projects for `[today, G - 1]` on the current base; the execution on `G` only, every unbilled instalment
billed early beside it (a line's current cycle stated early), stale when the recomputed figure differs; a loan closes
`PAID_OFF_EARLY`, a line `CLOSED_BY_CUSTOMER` (its closure request born by the payoff). **Payoff Quote**: the immutable
fact; **Payoff Execution**: its born-once execution (`UNIQUE (quote_id)`, `UNIQUE (loan_id)`), which alone admits the
early billing and the `PAID_OFF_EARLY` closure.
**Not:** a partial prepayment (principal paid early while the loan continues under a new agreement
version — built by `P11-TSK-028`, *"the cut candidate" until `P11-DOC-001`, 2026-10-15*), and not a Refinance (a new loan paying off the old,
deferred). No prepayment fee, and no rebate arithmetic: daily accrual makes an early-settlement
rebate zero by construction.
**Owned by:** `lending`

### BNPL Payoff
**Is:** settling everything a BNPL obligation owes, early, in one act, with no fee (A21, L8) — the Payoff's shape for
both BNPL products: an immutable **BNPL Payoff Quote** (`bnpl_payoff_quote`; good-through date `G` today up to the pinned
horizon; amount `P + PD + IA + ID + FD - CB` from the obligation's ledger accounts under B4 plus, pay-in-N, the interest
`ACCRUAL_ENGINE_V1` projects for `[today, G - 1]` of the accrual span - pay-in-4's is its unpaid instalments plus fees due
less the credit balance) and its born-once **BNPL Payoff Execution** (`bnpl_payoff_execution`, `UNIQUE (quote_id)`,
`UNIQUE (obligation_id)`) on `G`, which under B4 bills every unbilled instalment early (each row still dated its due date,
its entry the payoff's value date), applies the held credit, recomputes - stale (`409 bnpl.PayoffQuoteStale`) when the
figure moved - posts one `PAYOFF` repayment to every component, refunds any credit balance and closes the obligation
`PAID_OFF_EARLY`; every fact of it dated `max(G, floor)`, the floor read before it writes. Built by `P12-TSK-022`
(`bnpl V014`, `BnplPayoffs`, `POST /v1/me/bnpl-agreements/{id}/payoff-quotes` and `…/payoff`).
**Not:** the lending Payoff (`lending V022`, a loan or a line), and not a refund (the merchant's act, latest instalments
first); a payoff makes no schedule or agreement version. No rebate arithmetic: daily accrual makes it zero by construction.
**Owned by:** `bnpl`

### BNPL Repayment Reversal
**Is:** the four-eyes undoing of one BNPL repayment applied by mistake - a `bnpl_repayment_reversal` proposed with a reason by
a holder of `BNPL_SERVICE` and approved by a different holder of `BNPL_SERVICE_APPROVE` - posting F8, the ledger's
`ReversalService` swap of the repayment's F3 entry exactly (the wallet credited back, the due accounts and any held credit
debited), valued `max(today, floor)`; one negating `bnpl_repayment_allocation_reversal` row per allocation line, so what it
paid is due again and every later allocation stands. The repayment's `REVERSED` status is derived from the approved row.
Never a payoff's, never on a `CLOSED` obligation. Built by `P12-TSK-024` (`bnpl V016`, `BnplRepaymentReversals`).
**Not:** a refund (the merchant's return of value) and not a waiver (income forgone); nothing is edited.
**Owned by:** `bnpl`

### BNPL Waiver
**Is:** the four-eyes forgoing of an amount of one BNPL late fee's unpaid remainder - a `bnpl_waiver` and its born-once
`bnpl_waiver_decision` - posting F7, the ledger's `ReversalService` of the fee's F4 entry for the amount (`DR BNPL_FEE_INCOME /
CR BNPL_FEES_DUE`), bounded by `bnpl.fee_waiver_room`: never what was paid, released by a refund (OQ-14) or already waived
(the Phase 11 gate's R3). A waiver of the last receivable closes the obligation `REPAID`. Built by `P12-TSK-024`
(`bnpl V016`, `BnplWaivers`).
**Not:** a fee release (a refund's, automatic) and not an interest waiver (none exists in BNPL).
**Owned by:** `bnpl`

### Disbursement
**Is:** lending's act of making an instalment loan's principal the borrower's — born `PENDING` once
per loan at acceptance, `POSTED` in the one transaction that credits the borrower's wallet, debits
`LOAN_PRINCIPAL` (less any deducted origination fee) and makes the loan `ACTIVE`, or `FAILED` when
the loan is cancelled. The receivable is born exactly with the wallet credit, at most once
(`INV-LND-07`). Built by `P11-TSK-014` (`lending V008`, `LoanDisbursements`, rule 1 under
`lending.disbursement:<loan>`); the cancellation `DISBURSEMENT_FAILED` follows only an attempt that fails at or
after the deadline.
**Not:** the Loan Payout (the optional external leg that follows and posts nothing of lending's),
and not payments' Withdrawal. Nor a Credit Line Draw, which happens many times against the available
limit. A failed disbursement posts nothing, so nothing is compensated.
**Owned by:** `lending`

### Loan Payout
**Is:** lending's record of a disbursement's external leg, for a borrower who chose to receive the
loan in their own bank account — born `PENDING` with a ledger hold on the funds in the wallet,
dispatched as payments' system-actor withdrawal on the recorded instruction (adopting the hold), and
moving `PENDING → DISPATCHING → DISPATCHED → PAID_OUT | FAILED`, or
`DISPATCHING → NOT_DISPATCHED` when payments refuses it, by *reading* payments' outcome. Interest starts at its terminal outcome.
Built by `P11-TSK-015` (`lending V009`: `loan_payout`; `LoanPayoutSteps`; payments `V032`). *(The planned
`RETURNED` has no producer and is not a state - payments keeps no return record for an instant withdrawal; this
read "`PAID_OUT | FAILED | RETURNED`" until `P11-DOC-001`, 2026-10-15.)*
**Not:** the Disbursement — the receivable was born before it — and not the Withdrawal itself, which
is payments', with its entry, ambiguity, return and reconciliation. Lending never re-dispatches, no
payout outcome creates, duplicates or undoes the loan (`INV-LND-07`), and no account identifier
enters lending (`INV-RAIL-03`). Nor a Merchant Payout.
**Owned by:** `lending`

### Credit Line
**Is:** the revolving lending product (`CREDIT_LINE`): a Loan of kind `REVOLVING`, born `ACTIVE` at
acceptance with an agreed limit, against which the borrower draws to their wallet and repays
repeatedly, billed by monthly Credit Line Statements with a Minimum Payment, and closed
`ACTIVE → CLOSING → CLOSED` at the customer's request (directly `CLOSED` when nothing is
outstanding); no expiry in Phase 11. Built by `P11-TSK-021` (draws), `-022` (statements) and `-023` (repayment - what is
due, then principal paid down, restoring the limit, only a true excess held - and closure: a Line Closure Request,
`line_closure_request`, born once per line; `CLOSING` takes no draw and closes `CLOSED_BY_CUSTOMER` in the transaction
that settles its last receivable; never reopened).
**Not:** an instalment loan — no schedule and no disbursement, draws instead. Not a card or an
overdraft: draws go to the wallet only. Nor its Available Limit (the undrawn part) or the Exposure
it adds (the full limit while open).
**Owned by:** `lending`

### Credit Line Draw
**Is:** principal taken against a Credit Line — born once per line and draw key under the line's
lock, admitted only while the line is `ACTIVE`, its draws not suspended, the amount within the
Available Limit (`INV-LND-14`) and within lending capital headroom (`INV-LND-13`) — posting
`DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET`. Built by `P11-TSK-021` (`credit_line_draw`, `CreditLineDraws`; the door
`POST /v1/me/loans/{id}/draws`, `MULTI_FACTOR`).
**Not:** a Disbursement (once per instalment loan), and not a Withdrawal: a draw lands in the
wallet, and leaving the platform is the borrower's own withdrawal. Capital is consumed by a draw,
never by an undrawn limit.
**Owned by:** `lending`

### Credit Line Statement
**Is:** the born-once fact of one Credit Line's cycle, on the agreement's statement day after the
cycle's accruals — billing the cycle's accrued interest, carrying assessed fees, billing the Minimum
Payment's principal part and stating the minimum payment and its due date (25 days later). It
stores only what it billed: its opening and closing balances are derived from the ledger. Built by
`P11-TSK-022` (`lending V017`, `CreditLineStatements` in T-svc's billing slot): a billed item beside an Instalment
Billing - the credit balance applied at it, auto-collected on its due date - its principal part taken of the drawn
principal at the cycle's close (journal lines valued before the statement date), so a draw on the statement date
belongs to the next cycle.
**Not:** a balance report that could drift, and not an Instalment Billing (a line has no schedule).
Nor the whole amount owed: paying only the minimum leaves the rest drawn and accruing.
**Owned by:** `lending`

### Minimum Payment
**Is:** the least a Credit Line borrower must pay by a statement's due date to stay current —
interest billed plus fees billed plus a principal part set by the pinned terms' ratio and floor
(by default 3 % of drawn principal, raised so the whole payment is at least EUR 25.00), capped at the drawn principal — the
principal part billed as principal due; Days Past Due runs from its due date while it is unpaid. Computed by
`STATEMENT_ENGINE_V1` (`P11-TSK-009`: the ratio of all drawn principal, capped at the drawn principal not yet due);
stated and collected from `P11-TSK-022`.
**Not:** an Instalment — nothing amortises to zero over a term — and not the full outstanding.
Paying more pays down drawn principal and restores the Available Limit.
**Owned by:** `lending`

### Available Limit
**Is:** how much more a Credit Line borrower may draw now — the agreement's limit less drawn
principal (`LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE`), derived from journal lines under the line's lock
at each draw and never stored; a principal repayment restores it at commit, and interest and fees
never consume it; zero once drawn principal reaches the limit, and zero on a line no longer `ACTIVE`. Built by
`P11-TSK-021` (`AvailableLimit`; shown on the line's read).
**Not:** Exposure — credit counts an open line at its full limit whatever is drawn, since it can be
drawn without a new decision. Nor lending capital headroom (the platform's, per currency), and not a
wallet's available balance.
**Owned by:** `lending`

### Restructuring
**Is:** a contractual change to a live loan through a Loan Amendment — proposed by a person,
approved by a second, accepted by the customer (`MULTI_FACTOR`, with its own acceptance evidence),
and conditional on the agreement version it was proposed against — yielding agreement and schedule
version n+1 on the same loan, possibly re-scheduling arrears as principal not yet due; overdue
interest is never capitalised in Phase 11 (`INV-LND-11`). Built by `P11-TSK-027` (`lending V023`:
`loan_amendment`, `loan_amendment_decision`, `loan_amendment_rescheduling`; `LoanAmendments`), the amendment's
state derived from its rows and the clock, never written.
**Not:** a lifecycle state, and not an edit — the agreement and schedule are new versions, the old
kept. Nor a Refinance, which needs a new decision and a new loan, and not an operator's override of
a computed figure, which does not exist.
**Owned by:** `lending`

### Refinance
**Is:** replacing a loan with a new one — a new credit decision, and a new loan whose funding pays off
the old in one atomic act across two accounts. **Deferred**: the Phase 11 → 12 transition decides
which later lending phase takes it; credit's decision is its precondition.
**Not:** a Restructuring (the same loan, a new version, no new decision), and not a Payoff from the
borrower's own funds. Nothing of it is built in Phase 11.
**Owned by:** `lending`

### Write-off
**Is:** the recognition that a loan's receivable is no longer expected to be collected — lending's
event, which will move every per-loan asset balance to `LOAN_WRITE_OFF_EXPENSE`. **Deferred** to
Phase 14 with provisioning; the expense account is seeded in Phase 11 and posted by nothing.
**Not:** Default (a flag, posting nothing) or Delinquency. Nor a waiver, which is a reasoned,
four-eyes release of specific due amounts. And not the general ledger: the GL mapping, provisioning
and IFRS 9 staging are `accounting`'s — lending owns the event, accounting its books.
**Owned by:** `lending`

### Collections
**Is:** the operational pursuit of overdue amounts — contact strategy, promises to pay, placement
and case management — consuming lending's delinquency and default events. **Deferred** to Phase 13's
case management.
**Not:** Delinquency, the contractual facts lending records, and not auto-collection, lending's own
scheduled wallet debit on the due date (a source of Repayment). Collections never write lending's
rows.
**Owned by:** `risk`

### Lending Capital
**Is:** the platform's own funds committed to lending — the operational, per-currency, equity
account `LENDING_CAPITAL` — credited only by reconciliation's bank recognition of a four-eyes
capital contribution matched to a bank statement line (`DR CASH_AT_BANK / CR LENDING_CAPITAL`).
Every acceptance, draw and principal-re-instating repayment reversal checks, under that account's row lock, that recognised capital less
committed and outstanding principal stays at or above zero (`INV-LND-13`), so loan-funded wallet
money is platform-funded. Lending administers the contributions; the account is the ledger's.
Built by `P11-TSK-003` (ledger `V026`, the account) and `-004` (`lending V002`'s contributions, reconciliation
`V022`'s recognition, `LendingCapitalHeadroom`, `LendingCapitalProof`). Since `P12-TSK-005` the same pool funds BNPL (owner decision B2, `INV-BNPL-10`): the outstanding term counts `BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE` by ledger purpose, and a BNPL acceptance (headroom at least the gross) and a principal-re-instating BNPL repayment reversal judge it under the same row lock, through `app`'s `LendingBnplCapital`. *(`P12-DOC-001`, 2026-10-22: added by the Phase 12 exit review.)*
**Not:** a wallet — it is nobody's money held for a customer — and not `CASH_AT_BANK`, the cash
itself, posted only by bank recognition (`INV-SET-06`): capital is the source of funds, the loan its
use, and a disbursement does not debit it. Interest and fee income is not capital in Phase 11, and no
person adjusts it.
**Owned by:** `lending`

---

## 7b. BNPL

*(Added by the Phase 11 → 12 transition, 2026-10-16 — ADR-0101…0106 (`Proposed`),
`PHASE_12_PLAN.md` §3 and §12. Fourteen Phase 12 terms, in the order `DOMAIN_MODEL.md` lists them.
Nothing of Phase 12 is built: every entry is the planned design, and the task that builds a concept
corrects its entry to the code. The machines they name are in
[`BNPL_LIFECYCLES.md`](BNPL_LIFECYCLES.md). BNPL Agreement stays in §7, sharpened, and Instalment is
narrowed there to the loan's. BNPL's servicing facts — billing, interest accrual, repayment and its
allocation, collection attempt, late fee, Days Past Due, Delinquency, Default, Payoff, Waiver and
Repayment Reversal — re-apply §7a's definitions to the Customer Repayment Obligation in `bnpl`'s own
rows (the shape, never lending's rows), and take no entry of their own: the word is lending's, and
the module register prefixes BNPL's (`MODULE_ARCHITECTURE.md` §4 `bnpl`).)*

*(Built, and read against the code by the Phase 12 exit review, `P12-DOC-001`, 2026-10-22: `P12-TSK-001`…`-028`, `P12-TST-001` and `-002` built every concept below — bnpl `V001`…`V017`, `V005` a deliberate gap — so the *planned* markers are gone and each entry states what was built; the note above is the transition's, kept as written. Its "take no entry of their own" held only in part: four BNPL servicing facts were built as their own tables and have entries beside their lending counterparts in §7a — BNPL Interest Accrual, BNPL Payoff, BNPL Repayment Reversal and BNPL Waiver — and `merchant`'s BNPL Enablement and BNPL Fee Pin are defined here; the canonical list names all six since this review.)*

### BNPL Terms Version
**Is:** one immutable version of a BNPL product's terms as data — the plans offered (Pay-in-4's four
biweekly instalments; Pay-in-N's 3, 6 or 12 monthly), the rate (Pay-in-N fixed, ACT/365F; Pay-in-4
zero by `CHECK`), the late fee with its day, grace and cap, the delinquency bounds and default
threshold, the allocation order, the engines, the offer validity cap, the dispute response window and
the servicing zone — `PROPOSED → ACTIVE → RETIRED` or `PROPOSED → REJECTED`, activated by a second
person, at most one `ACTIVE` per product, never by a migration. Built by `P12-TSK-004` (`bnpl V002`: the money-free machine row `bnpl_terms_version` and its INSERT-only term set `bnpl_terms`, which also holds the collection cadence, the dispute four-eyes threshold, the payoff horizon and the refund allocation engine; the offer validity at most 30 minutes) *(`P12-DOC-001`, 2026-10-22: read "Planned" until the Phase 12 exit review)*;
production activates none, so BNPL ships closed (`422 bnpl.ProductNotOffered`).
**Not:** the Loan Product Terms Version — the same shape, another module's table for other products.
Nor credit's Policy Version, which decides *whether* to finance, and not the merchant's Fee Schedule,
which prices the Merchant Discount and is `merchant`'s. An agreement keeps the version it was offered
under for life (`INV-HIST-04`).
**Owned by:** `bnpl`

### BNPL Application
**Is:** one customer's choice of a BNPL product and plan inside one open Checkout Session —
`SUBMITTED → AWAITING_DECISION → OFFERED | DECLINED | CLOSED_UNDECIDED | WITHDRAWN` — opening exactly
one credit Decision Request for the session's gross in its own transaction and storing only its id;
at most one open per session, and per party and product. One decision per purchase and no
pre-approved spending limit (owner decision B5). Built by `P12-TSK-010` (`bnpl V003`'s money-free `bnpl_application`, `UNIQUE (decision_request_id)`; `SUBMITTED` transient, never committed) and offered by `-011` (`V004`); `CLOSED_UNDECIDED` always names its `BnplApplicationClosureReason` (`CREDIT_REQUEST_CANCELLED`, `CREDIT_REQUEST_EXPIRED`, `CREDIT_REQUEST_ABANDONED`, `SESSION_CLOSED`, `APPROVED_BELOW_PURCHASE`, `PRODUCT_NOT_OFFERED`, `DECISION_LAPSED`). "Open per session" counts `OFFERED`: an offered application holds its session for good, so a declined or expired offer does not free it for a second application. *(`P12-DOC-001`, 2026-10-22: read "Planned" until the Phase 12 exit review.)*
**Not:** a Loan Application — it exists only inside a checkout, its amount is the purchase's fixed
price (an approval below it is not offerable), and a session that closes closes it. Nor the Decision
Request, credit's envelope: bnpl never reads a score, and the merchant learns one bit, the event `bnpl.BnplEligibilityAssessed` with `ELIGIBLE` (an offer) or `NOT_ELIGIBLE` (a decline, or an approval below the purchase) — never a reason, a score or an amount (OQ-12). No `PENDING` signal was built, and an application closed undecided for any other reason emits none. *(`P12-DOC-001`, 2026-10-22: read "the merchant learns only `ELIGIBLE`, `NOT_ELIGIBLE` or `PENDING` for its session" until the Phase 12 exit review.)*
**Owned by:** `bnpl`

### BNPL Offer
**Is:** the financing the platform will grant for one purchase on one approved Credit Decision and
the pinned BNPL Terms Version — exactly the session's gross, the plan, an illustrative schedule and
the total payable, as canonical terms with their SHA-256 — expiring at the earliest of the decision's
validity, the checkout session's expiry and the pinned terms' offer validity (at most 30 minutes), judged on the database clock:
`OFFERED → ACCEPTED | DECLINED | EXPIRED`. Its acceptance *is* the purchase authorisation. Built by `P12-TSK-011` (`bnpl V004`: the money-free machine row `bnpl_offer`, `UNIQUE (application_id)` and `UNIQUE (decision_id)`, its expiry stamped by the database; the INSERT-only `bnpl_offer_terms`, canonical terms format 1 as text with their SHA-256 held to the bytes by `CHECK`; a decline only while unexpired) and accepted by `-013` (`V006`: `OFFERED → ACCEPTED` only beside its `FINANCED` purchase, the complement of the expiry). *(`P12-DOC-001`, 2026-10-22: read "Planned", and "the checkout session's expiry and 30 minutes", until the Phase 12 exit review.)*
**Not:** the BNPL Agreement (nothing is contracted before acceptance) and not a Loan Offer: it cannot
outlive the checkout session, and accepting it finances the merchant and completes the session in
the same commit. An expired or declined offer releases nothing — the unconsumed decision lapses at
its own validity.
**Owned by:** `bnpl`

### Pay-in-4
**Is:** the BNPL product `BNPL_PAY_IN_4` (owner decision B1): four instalments of the purchase price,
the first collected from the customer's wallet in the purchase's own transaction and the next three
every 14 days — `A = floor(P / 4)` for instalments 2–4, the remainder `P − 3A` on instalment 1, so
principal is conserved exactly (OQ-3) — with no customer interest and no customer fee but a late fee;
the merchant pays a Merchant Discount. Scheduled by a new pure engine, `BNPL_SCHEDULE_ENGINE_V1`.
Built: `P12-TSK-001` named the credit product (credit `V022`: EUR 50.00 to 1,000.00, a fixed plan of four, no term), `-008` the engine (`BnplScheduleEngineV1`: `A` by `RoundingMode.DOWN`, the one division; due dates `D1 + 14(k − 1)` from the purchase's business date, bnpl's own `InstalmentFrequency.BIWEEKLY`), `-004` held its rate to `'0'` by `CHECK` (`bnpl V002`) and `-013` collects instalment 1 at purchase (repayment kind `AT_PURCHASE`). *(`P12-DOC-001`, 2026-10-22: read "Planned" until the Phase 12 exit review.)*
**Not:** Pay-in-N — no accrual, no interest income, and a biweekly frequency lending's
`RepaymentFrequency` does not have. Nor a payment split into four card charges: one financing, one
obligation, instalments collected from the wallet only (owner decision B6).
**Owned by:** `bnpl`

### Pay-in-N
**Is:** the BNPL product `BNPL_PAY_IN_N` (B1): 3, 6 or 12 monthly instalments with customer interest
at a fixed rate, accrued daily ACT/365F (the Phase 11 conventions), the first one month after the
purchase with no down payment (OQ-4) — scheduled by lending's `SCHEDULE_ENGINE_V1` and accrued by
`ACCRUAL_ENGINE_V1`, both reused through `creditmath` with every golden hash unchanged; it may carry a
Merchant Discount (OQ-17). Built: `P12-TSK-001` named the credit product (credit `V022`: EUR 200.00 to 5,000.00, a term of 3, 6 or 12 months), `P12-TSK-002` moved the three version-1 engines into `creditmath` unchanged, `-011` and `-013` schedule it (`InstalmentFrequency.MONTHLY`, the first due one month after the purchase on its day of the month, clamped to month end), `-015` bills and `-017` accrues it (BNPL Interest Accrual, §7a). *(`P12-DOC-001`, 2026-10-22: read "Planned" until the Phase 12 exit review.)*
**Not:** a Loan of product `PERSONAL_LOAN`: the same engines and conventions, a different contract —
born at checkout with a merchant leg, reduced by merchant refunds, serviced by `bnpl`'s own rows. Nor
Pay-in-4.
**Owned by:** `bnpl`

### BNPL Purchase
**Is:** BNPL's financed commercial fact (`bnpl.purchase`, the plan's "Purchase") — the checkout
session and its order, the merchant, the gross and its currency, the pinned BNPL fee schedule version
and the instant it was financed — born at the offer's acceptance and moved `AUTHORISED → FINANCED` in
the same transaction as its Merchant Financing, then `FULLY_REFUNDED` once its refunds total the gross
(partially refunded is derived). At most one per checkout session (`INV-BNPL-03`); its row is the lock
under which every refund and dispute is bounded (`INV-BNPL-07`). Built by `P12-TSK-013` (`bnpl V006`'s `bnpl.purchase`, `UNIQUE (checkout_session_id)` and `UNIQUE (offer_id)`, never committed `AUTHORISED`; `FINANCED → FULLY_REFUNDED` by `P12-TSK-021`). The row is money-free: it carries the session, the order, the merchant, the party, the plan, the currency, the business date and the instants, while the gross, the Merchant Discount, the net and the pinned fee schedule version are its Merchant Financing's. *(`P12-DOC-001`, 2026-10-22: read "Planned" until the Phase 12 exit review; "the gross … the pinned BNPL fee schedule version" are read through the financing row.)*
**Not:** the Order — checkout's commercial fact, which BNPL's commit brings into being — and not a
Payment: no intent, attempt or rail exists, because the merchant is paid from the platform's capital
against a customer receivable, not by money crossing a network. Nor a Customer Payment.
*Checkout's half is built (`P12-TSK-007`): the session the purchase finances moves `OPEN → COMPLETED`
"by financing" — intent-less, its `financed_by_ref` naming the purchase, set once and one session per
purchase (checkout `V004`) — and the order is born with the financing entry as its
`captured_entry_ref`; the session read and `checkout.OrderPaid` say `completedAs = FINANCED`.*
**Owned by:** `bnpl`

### Merchant Financing
**Is:** the born-once record that the platform paid for a BNPL Purchase on the merchant's behalf —
gross, Merchant Discount and net, `gross = fee + net` by `CHECK` — and its journal entry F1, which in one
posting of four lines debits the customer's receivable `BNPL_PRINCIPAL` the gross, credits the merchant's
`MERCHANT_PAYABLE` the gross and debits it the fee, and credits `BNPL_MERCHANT_FEE_INCOME` the fee — the
payable rising by the net, its gross and fee visible as a card capture's are (a zero fee posts two lines;
`bnpl.financing:<purchase>`, `INV-BNPL-02`, `INV-BNPL-04`, ADR-0103 decision 5); funded from lending capital under the shared headroom (`INV-BNPL-10`,
owner decision B2). Built by `P12-TSK-013` (`bnpl V006`'s INSERT-only `merchant_financing`, keyed by the purchase, `UNIQUE (journal_entry_id)`, the fee schedule version pinned by value; F1 posted in the purchase's transaction after the capital row's lock, headroom at least the gross). *(`P12-DOC-001`, 2026-10-22: read "Planned" until the Phase 12 exit review.)* *(The Phase 12 → 13 transition gate, 2026-10-22: read "credits the merchant's `MERCHANT_PAYABLE` the net and `BNPL_MERCHANT_FEE_INCOME` the fee" - three lines - until the gate; as built F1 is four lines, ADR-0103 decision 5.)*
**Not:** a Merchant Payout or a Merchant Settlement: financing creates the platform's liability to
the merchant, and `merchant`'s unchanged on-demand payout later discharges it. Nor a Disbursement: no
money moves at purchase, and nothing reaches a customer's wallet.
**Owned by:** `bnpl`

### Merchant Discount
**Is:** the fee a merchant pays the platform on a BNPL Purchase — basis points of the gross plus an
optional fixed part, from the merchant's BNPL fee schedule version pinned on the purchase
(`INV-MER-03`), rounded once under that version's rounding policy, always below the gross — taken out of the payable in the
financing entry and recognised at purchase as `BNPL_MERCHANT_FEE_INCOME` (A9, OQ-16). On a refund it is
returned pro rata by the cumulative method, `cum(after) − cum(before)` with `cum(x) = round(f · x / G)`,
so any sequence of refunds totalling the gross returns exactly the fee charged (owner decision B3). Built by `P12-TSK-006` (merchant `V010`, `BnplPricing` - merchant's one `FeeCalculation`; a schedule with any `RETAINED` version refused for BNPL) and `-013`. *(`P12-DOC-001`, 2026-10-22: read "rounded once `HALF_EVEN`" - the plan's A8 - until the Phase 12 exit review; the code rounds by the pinned version's policy, any of the six, and nothing restricts a BNPL schedule to `HALF_EVEN`.)*
**Not:** a card capture's fee (`FEE_REVENUE`, `MerchantSettlement`'s split), and not a late fee or
interest, which the customer pays. Nor capital: fee income does not raise lending headroom. Whether it
should be amortised as a yield is Phase 14's question.
**Owned by:** `bnpl`

### BNPL Enablement
**Is:** a merchant's permission to offer BNPL (`merchant.merchant_bnpl_enablement`, `P12-TSK-006`, OQ-8):
proposed by one operator, approved by a different one (`INV-AUD-04`), withdrawn by any one; one live per
merchant. A merchant may offer a BNPL product only while it is `ACTIVE` (trading), its enablement is
`ACTIVE`, and the product has a BNPL fee schedule with a returning version in force - the standing.
**Not:** the merchant's own status (a suspension stops BNPL without touching the enablement), and not a
per-product switch: the product's pricing is the BNPL fee schedule assignment.
**Owned by:** `merchant`

### BNPL Fee Pin
**Is:** the record of which fee schedule version prices one BNPL Purchase (`merchant.bnpl_fee_pin`,
`P12-TSK-006`) - born once, in the purchase's transaction, keyed by the purchase, never changed; the
Merchant Discount and its returns are that version applied to the pinned gross.
**Not:** the Payment Fee Pin, which is keyed by a payment intent and cannot hold a purchase; and not a
stored fee - the fee is recomputed, never copied.
**Owned by:** `merchant`

### Customer Repayment Obligation
**Is:** the customer's account for one BNPL Purchase (`bnpl.bnpl_obligation`) — identity, product,
plan, status and permits only, **no money column** (`INV-BNPL-01`) — born `ACTIVE` at the acceptance
and `CLOSED` (reason `REPAID`, `PAID_OFF_EARLY`, `REFUNDED` or `REFUNDED_PARTIALLY_REPAID`) by the
transaction that zeroes its last receivable; every figure a balance of its six per-obligation ledger
accounts (`OwnerKind.BNPL_OBLIGATION`), and its row the serialisation point of every servicing act on
it. Delinquency, default and a dispute hold are conditions beside it. Built by `P12-TSK-013` (`bnpl V006`'s `bnpl_obligation`, unique per purchase, agreement and consumed decision, its six accounts opened in the purchase's transaction) *(`P12-DOC-001`, 2026-10-22: read "Planned" until the Phase 12 exit review)*. Its closure
(`REPAID`) built by `P12-TSK-018`: `bnpl_obligation_closure`, one per obligation, born by the customer's repayment or
the servicing step that zeroes the last receivable, refunding its BNPL credit balance - money held beyond every receivable,
`BNPL_CREDIT_BALANCE`, applied at the next billing and never to principal not yet due - to the wallet under
`bnpl.closure-refund:<obligation>`.
Its refunds' closures (`REFUNDED`, `REFUNDED_PARTIALLY_REPAID`) built by `P12-TSK-021`, its payoff's (`PAID_OFF_EARLY`) by `P12-TSK-022` (`V014`).
**Not:** a Loan — not born by a disbursement, reducible by a merchant's refund, never `CANCELLED`, and
never a lending row (ADR-0101). Nor the Merchant Financing, the other flow of the same event, and not
the customer's Exposure, credit's per-party figure, which counts its outstanding principal from version 3.
**Owned by:** `bnpl`

### BNPL Schedule
**Is:** one version of a Customer Repayment Obligation's projected BNPL Instalments
(`bnpl.bnpl_schedule`) — version 1 at the acceptance from the pinned engine (`BNPL_SCHEDULE_ENGINE_V1`
for Pay-in-4, `SCHEDULE_ENGINE_V1` for Pay-in-N), version n+1 when a refund cancels or shortens an
unbilled instalment (Pay-in-N's by `recalculateAtInstalment`: the same instalment over the least term,
which is "latest first") — billed instalments kept, unbilled superseded, nothing updated, principal
conserved exactly (`INV-BNPL-05`). The module register's "Instalment Plan", renamed by the Phase 11 →
12 transition so that "plan" keeps one meaning: the plan the customer chooses. Built by `P12-TSK-013` (`bnpl V006`'s INSERT-only `bnpl_schedule` and `bnpl_instalment`, the principal sum held at commit) and `P12-TSK-021` (version n+1, `V012`); a payoff makes no version. *(`P12-DOC-001`, 2026-10-22: added by the Phase 12 exit review.)*
**Not:** a lending Repayment Schedule, changed only by a four-eyes amendment or a prepayment, and not
the bill: Pay-in-N's billing bills the interest actually accrued. Nor the BNPL Offer's illustrative
schedule.
**Owned by:** `bnpl`

### BNPL Instalment
**Is:** one scheduled repayment of a BNPL Schedule version — principal (and Pay-in-N's projected
interest) and a due date — which a refund may cancel in whole or in part, latest first; what became
due at its date is its billing, what paid it its allocations, and each automatic try to collect it a
collection attempt, born once per instalment and date (`bnpl_collection_attempt`, `P12-TSK-019`: from the
customer's wallet only, owner decision B6 - a failed attempt posts nothing and is the evidence). Once
billed and unpaid at the end of its due date it is past due: the oldest such instalment's age is the
obligation's Days Past Due, recorded as a `bnpl_condition_event` only on a change of bucket or default
flag (`P12-TSK-020`); and its own age reaching the pinned late-fee day earns it the pinned late fee once
(`bnpl_fee_assessment`, `UNIQUE (instalment_id, kind)`, capped per agreement), which joins what it owes
and is released if a refund cancels it whole (OQ-14).
**Not:** a Loan Instalment (§7's Instalment): the same word, a different lifecycle — a BNPL instalment
can be cancelled by a third party's refund, a loan instalment cannot (`MODULE_ARCHITECTURE.md` §5).
Nor a Repayment, which is money received.
**Owned by:** `bnpl`

### BNPL Refund
**Is:** a return of value on a BNPL Purchase — requested by the merchant over its key (the platform's
first merchant-key refund door, OQ-9), by an operator, or executed as a BNPL Dispute's outcome — born
`APPLIED` in one transaction that, under the purchase's lock, bounds it by the gross less earlier
refunds (`INV-BNPL-07`), cancels unpaid instalments from the last backwards, credits any excess to the
customer's wallet, debits the merchant's payable net of the Merchant Discount returned, and posts the
whole as one entry (`INV-BNPL-06`); a full refund closes the obligation with nothing stranded
(`INV-BNPL-09`). Built by `P12-TSK-021` (`bnpl.bnpl_refund`, `BnplRefunds`): the merchant's key must carry the
`BNPL_REFUND` **API key scope** - an insert-time fact of the key, chosen at issuance (`merchant V011`), keys issued before
carrying none; the operator's door beside it under `PAYMENT_REFUND`.
**Not:** payments' Refund of a card capture — no provider, no `PENDING` state, no rail — and not a
Repayment Reversal, which undoes money the customer paid. After a payout it may drive the payable below
zero only within its purchase's own net (owner decision B7; `INV-MER-07` amended).
**Owned by:** `bnpl`

### Refund Adjustment
**Is:** the born-once allocation of one BNPL Refund — one row per instalment it cancels (principal not
yet billed, or billed and still unpaid) plus the excess owed to the customer, summing exactly to the
refund by a deferred trigger — with the late fees on a cancelled instalment released and, at a full
cancellation, Pay-in-N's unpaid interest waived (A11, OQ-14).
**Not:** the BNPL Refund itself (the act and its entry), and not a Waiver: nothing is forgiven at the
platform's expense — the merchant funds every unit of it. Never edited, and never re-cut by a later
refund. Built by `P12-TSK-021` (`bnpl.bnpl_refund_adjustment`): one row per instalment touched, the excess on the refund
row itself; the principal cancelled (`UNBILLED` or `BILLED_UNPAID`), pay-in-N billed interest released, and whether the
instalment owes no principal after it (`whole`) - and, built by `P12-TSK-020` (OQ-14), the unpaid late fees
of a billed instalment cancelled whole, released in the refund's own entry (`late_fee_released_minor`), never a fee
already paid and never twice. The interest released is
platform income forgone, not part of the refund the merchant funds.
**Owned by:** `bnpl`

### BNPL Dispute
**Is:** the customer's contest of a BNPL Purchase with the platform — `OPEN → MERCHANT_RESPONDED →
RESOLVED_REFUND | RESOLVED_UPHELD`, `OPEN → RESOLVED_REFUND` when the merchant accepts or stays silent
past its pinned response window (OQ-10's 14 days; the terms admit 1 to 60 - `P12-DOC-001`, 2026-10-22, read "its 14-day window"), `→ WITHDRAWN` by the customer — at most one open per purchase, holding
the disputed amount from late fees, Days Past Due and auto-collection while open. A refund outcome
executes a BNPL Refund under the same lock and the same bound, so refund and dispute never
double-credit (`INV-BNPL-08`). Built by `P12-TSK-023` (`bnpl V015`): the money-free machine row beside
its INSERT-only claim and hold; above the pinned four-eyes threshold an operator's resolution is a proposal
another holder of `BNPL_DISPUTE_RESOLVE` approves. **The dispute hold** - a condition, never a state - is
read from the dispute rows: a held instalment (by its due date) takes no auto-collection, no Days Past Due
and no late fee while the dispute is open, and after the lift counts both from the later of its due date
and the lift's business date.
**Not:** payments' Dispute or a Chargeback: no card network stands behind a BNPL purchase, so the
financed amount has no chargeback; one can arise only on a card-funded wallet top-up, which is
payments' and unrelated to the agreement (`INV-DSP-01`). Nor Delinquency.
**Owned by:** `bnpl`

---

## 8. Foreign exchange and cross-border

*(Retitled, and the twenty-two Phase 9 terms added alphabetically after the three
originals, by the Phase 8 → 9 transition, 2026-10-02 — ADR-0074…0083 (`Proposed`) — each
keeping a distinction Phase 9 could collapse. The kernel's money vocabulary joins the
canonical list here because five currencies at three scales now post.)*

*(Six terms added alphabetically by the Phase 9 exit review, `P9-DOC-001`, 2026-10-07 —
Cancellation Request, Cross-Border Return, Payee Check, Posting Plan, Recall and Unwind:
Phase 9 built each as a distinct concept, and neither list named one. FX Quote, FX Trade,
FX Cover and Outbound Credit corrected to what was built.)*

### FX Quote
**Is:** a rate offered to a specific party for a specific amount, valid for a stated window —
server-authoritative, exclusive to its owner, single-use, bounded on the database clock, and
carrying its frozen Posting Plan (`INV-FX-02`, `INV-FX-04`).
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
(`INV-FX-01`) — booked at most once per quote, posting exactly its Posting Plan, and final
but for a four-eyes operator reversal of a wallet conversion (`INV-AUD-04`); a cross-border
trade is never reversed (`INV-REV-03`).
**Not:** a Quote. A quote may expire unexercised; a trade has postings. Nor the FX Cover: the
trade is the customer's conversion, booked locally without waiting on a provider
(`INV-FX-09`).
**Owned by:** `fx`

### Cancellation Request
**Is:** a customer's request to cancel an authorized cross-border payment the corridor
provider has not yet accepted — a born-once, append-only fact (`crossborder.cancellation_request`,
one per payment), recorded in one transaction with the outbound credit marked for Recall, and
answered asynchronously.
**Not:** a cancellation. Requesting cancels nothing: the payment fails `RECALLED` (shown to the
customer as cancelled) only on the provider's definitive answer, and stays in transit when the
recall comes too late (`INV-LIFE-03`). Nor an FX Quote's cancellation, which withdraws an
unaccepted quote with no provider involved, and not the Recall, which is the platform's act
toward the provider.
**Owned by:** `crossborder`

### Corridor
**Is:** a priced, versioned route — (source currency, destination currency, destination
country) — with ordered candidate rails, a fee schedule, a maximum per payment, a screening
validity and a delivery estimate, under a four-eyes corridor policy version (`INV-AUD-04`,
`INV-HIST-04`).
**Not:** a Payment Rail. The corridor is policy about where value may go and at what price;
the rail is the declared transport that carries it (`INV-RAIL-01`). Fees are per corridor,
never per rail.
**Owned by:** `crossborder`

### Corridor Rail
**Is:** a declared payment rail that carries outbound cross-border credits — push, final on
acceptance, refunding nothing — with its own `CORRIDOR_CLEARING` position per counterparty
(`INV-RAIL-04`), and its return window and decision deadline in its own declaration.
**Not:** the Corridor, which is `crossborder`'s policy over it; nor a card PSP or instant
scheme — a routing of a pay-in to it is refused, because it declares no pay-in direction.
**Owned by:** `payments`

### Counterparty
**Is:** an external institution the platform itself settles with — an FX provider, a corridor
provider — registered in the ledger's counterparty registry and owning its own clearing
position per currency, discharged only by its own declared source's evidence (`INV-RAIL-04`,
`INV-SET-05`).
**Not:** a Beneficiary or a Merchant. A counterparty is who the platform owes or is owed by;
a beneficiary is whom a customer pays. Netting two counterparties on one account is the
collapse `INV-RAIL-04` forbids.
**Owned by:** `ledger`

### Counterparty Screening
**Is:** kyc's sanctions screening of a payment counterparty — a cross-border beneficiary —
every outcome a recorded platform decision with its basis, policy version and time
(`INV-KYC-01`), a hit or an unverified payee resolved only by a person (`INV-KYC-04`,
`INV-XB-02`).
**Not:** the beneficiary's lifecycle, which is `crossborder`'s projection of the screening's
outcome; nor the customer's own onboarding screening, which belongs to a KYC Case.
**Owned by:** `kyc`

### Cross-Border Beneficiary
**Is:** the registered destination of cross-border payments — the corridor provider's opaque
destination reference, a display suffix, the payee-check verdict and the provider-attested
destination country, currency and entity type — payable only while `ACTIVE` with a current
clearance (`INV-XB-02`).
**Not:** the `transfers` Beneficiary, an internal book destination; and not a stored account:
no account identifier enters the platform (`INV-RAIL-03`), and the name is held only by
`kyc`, encrypted.
**Owned by:** `crossborder`

### Cross-Border Payment
**Is:** a customer's instruction to deliver value abroad at a disclosed price — one accepted
quote, a hold, then one entry at the corridor provider's acceptance; what was shown is what
is held, posted and instructed (`INV-XB-01`, `INV-XB-03`).
**Not:** the Outbound Credit (one instruction on one rail) nor the FX Trade (the conversion):
the payment composes both and adds the price, the beneficiary and the return.
**Owned by:** `crossborder`

### Cross-Border Return
**Is:** value coming back from the corridor after the provider accepted an outbound credit —
one born-once return fact per credit (`payments.outbound_credit_return`), reaching the platform
by an inquiry answer or the provider's settlement report, and applied automatically only when
exact: the instructed currency and amount, on a completed credit, for an active customer —
credited in that currency, never re-converted at the original rate, the transfer fee refunded.
Any other return parks with its break and reaches the customer only through a four-eyes
transfer whose approval records the return in the same transaction (`INV-XB-04`,
`INV-AUD-04`).
**Not:** a Return Payment, which is our refund sent as a new credit back to a payer; not a
reversal — the platform never reverses an accepted credit (`INV-REV-03`), and a return is the
receiving side's act, admitted whenever it arrives; and not a Recall, which stops a credit
before acceptance at the customer's request.
**Owned by:** `payments`

### Currency
**Is:** an ISO 4217 code with its minor-unit scale, explicit on every monetary value
(`INV-MON-02`).
**Not:** a formatting detail. Arithmetic across currencies is rejected (`INV-MON-04`); only a
conversion through an FX position turns one currency into another (`INV-FX-01`).
**Owned by:** `sharedkernel`

### Customer Quote
**Is:** the customer-facing side of an FX Quote: the customer rate and the frozen amounts the
customer is shown and may accept — the posting plan of `INV-FX-04`, with the margin over mid
disclosed.
**Not:** the Provider Rate or the Reference Rate. The customer rate is derived from the
provider's firm rate under the pinned pricing policy, and the margin between them is posted
explicitly (`INV-FX-03`), never concealed in the rate.
**Owned by:** `fx`

### FX Cover
**Is:** the platform's back-to-back provider trade hedging an accepted quote — one per
accepted quote, dispatched under a reference stored before sending, closing exactly the
plan's position legs with any difference posted as realised result (`INV-FX-08`). An
executed cover whose position is no longer wanted is taken back by its Unwind.
**Not:** the FX Trade. The trade is the customer's conversion, booked locally and never
waiting on a provider (`INV-FX-09`); the cover is the platform's own risk management, and
its failure is the platform's P&L, never the customer's.
**Owned by:** `fx`

### FX Position
**Is:** per currency, the platform's own ledger account for value opened by conversions and
closed by covers — zero at rest, explainable from its open legs, with one poster and no free
adjustment (`INV-FX-06`).
**Not:** a clearing position, which is a counterparty's value in flight and opens settlement
expectations; the FX position is nobody's debt — it is the platform's own currency exposure,
and it joins no reconciled-positions proof.
**Owned by:** `fx`

### Markup
**Is:** the commercial component of the conversion margin, applied over the internal rate to
reach the customer rate, stored as its own attributed part of the one revenue line
(`INV-FX-03`).
**Not:** the Spread, which covers the platform's cost and risk between the provider's rate
and the internal rate. The split is a stored fact, not a second posting.
**Owned by:** `fx`

### Minor unit
**Is:** the smallest representable unit of a currency — 0 decimal places for JPY, 2 for EUR,
GBP and USD, 3 for BHD — pinning the scale of every amount in it (`INV-MON-05`).
**Not:** a rounding choice. The minor unit is the currency's fact; how an amount reaches that
scale is a named rounding policy (`INV-MON-03`).
**Owned by:** `sharedkernel`

### Money
**Is:** the kernel value type binding an exact decimal amount to its explicit currency and
scale — the only representation of monetary value in the platform (`INV-MON-01`,
`INV-MON-02`).
**Not:** a number. An amount without currency and precision semantics is refused at the type
level, and overflow is rejected, never wrapped (`INV-MON-06`).
**Owned by:** `sharedkernel`

### Outbound Credit
**Is:** one instruction on one rail to one destination, carrying the provider's ambiguity —
dispatched, unknown, received, completed or failed — with its end-to-end reference minted
and stored before any send (`INV-PAY-04`) and its outcome adopted only from knowledge
(`INV-LIFE-03`); its Recall and its Cross-Border Return are recorded beside it, each once.
**Not:** the Cross-Border Payment, which is the customer's product-level instruction and
never shows the provider's ambiguity; and not a Withdrawal, which moves the customer's own
money to the customer's own account.
**Owned by:** `payments`

### Payee Check
**Is:** the provider directory's verdict on whether the name the customer typed belongs to the
destination account, taken at registration — of a cross-border beneficiary (`MATCH`,
`NO_MATCH` or `UNAVAILABLE`, a close match stored as `NO_MATCH`, ADR-0080) and, since
Phase 7, of a bank account instrument (ADR-0062) — kept as a word, never with the name or
account it compared (`INV-RAIL-03`), a non-match registered only with the customer's
acknowledgement.
**Not:** screening. The payee check ties a name to an account; Counterparty Screening asks
whether the name is sanctioned. A clear screen of an unverified payee says nothing about the
real recipient, so a beneficiary whose payee is unverified meets a person exactly as a hit
does (`INV-XB-02`, `INV-KYC-04`), and the customer's acknowledgement never stands in for
screening.
**Owned by:** `crossborder`

### Payment Offer
**Is:** the disclosed price of a cross-border payment — destination amount, transfer fee,
total debit — frozen beside its FX quote under the pinned corridor policy version; accepting
it is what `INV-XB-03` holds the posting and the instruction to.
**Not:** the FX Quote, which is the conversion's frozen plan: the offer adds the corridor's
fee and limits. An unaccepted offer is not a payment.
**Owned by:** `crossborder`

### Posting Plan
**Is:** every amount a conversion will post — the customer legs, the position legs, the
margin with its spread and markup attribution, and the rounding residual — computed once at
quote time by one pure function under the pinned pricing version and frozen on the FX
quote: balanced per currency by a plan-identity constraint, guarded against change, and
executable at most once (`INV-FX-04`, ADR-0076). The FX plan replay recomputes every booked
trade's plan from its stored inputs alone and compares it with the posted entry line by
line; a divergence is critical (`INV-FX-05`).
**Not:** a price recomputed at execution — execution posts the plan and never re-prices. Nor
the Customer Quote, which is the part of the plan the customer is shown and accepts: the
position legs and the residual are the platform's own. A divergence from the plan is our
defect, not a reconciliation break — no external evidence states the spread.
**Owned by:** `fx`

### Provider Rate
**Is:** the FX provider's firm quoted rate, with its stated counter-amount and validity — the
executable link of the rate chain, frozen on the quote and replayable (`INV-FX-05`).
**Not:** the Customer Quote, which is derived from it under policy; nor the Reference Rate,
against which it must be plausible before it may be used (`INV-FX-02`).
**Owned by:** `fx`

### Rate Lock
**Is:** the issued FX quote itself — exclusive to its owner, single-use, bounded on the
database clock, never lengthened by provider skew or network time (`INV-FX-04`).
**Not:** a reservation of provider liquidity. If the provider refuses its own firm quote, the
cover re-quotes and the difference is the platform's realised result — the customer's price
stands (`INV-FX-09`).
**Owned by:** `fx`

### Realised FX Result
**Is:** the gain or loss posted when a cover or unwind executes off its plan — one explained
line per execution, gains never netted with losses (`INV-FX-06`, `INV-FX-08`).
**Not:** revaluation or unrealised P&L, which wait for Phase 14: covered positions are zero
at rest, so there is nothing to revalue.
**Owned by:** `fx`

### Recall
**Is:** the platform's request to the corridor provider to stop an outbound credit it has not
yet accepted, prompted by a Cancellation Request — sent by the resolution sweep under the
credit's own end-to-end reference, so idempotent at the provider (`INV-PAY-04`), its outcome
(`RECALLED` or `REFUSED`) recorded once on the credit; a credit with a recall requested is
never re-sent.
**Not:** assumed. Only the provider's definitive `RECALLED` fails the credit — releasing the
hold, abandoning the quote and unwinding an executed cover, the customer debited nothing
(`INV-XB-01`, `INV-LIFE-03`); an acceptance that wins the race leaves it completed. Nor a
Cross-Border Return, which comes after acceptance and is the receiving side's act.
**Owned by:** `payments`

### Reference Rate
**Is:** an independently sourced market rate used for plausibility and disclosure only, fresh
on the database clock and failing closed when stale (`INV-FX-02`).
**Not:** an executable rate. No customer and no cover ever trades on it — it is the check on
the provider, which is why it must not come from the provider.
**Owned by:** `fx`

### Rounding policy
**Is:** a named rounding rule, recorded wherever an amount or rate is rounded
(`INV-MON-03`); a conversion names three — rate, amount and margin — pinned on the decision
(`INV-HIST-04`).
**Not:** an implementation detail. Replaying the named policies reproduces the posted entry
exactly (`INV-FX-05`); an unnamed rounding is an unexplainable cent.
**Owned by:** `sharedkernel`

### Scale
**Is:** the number of decimal places a stored value carries — a currency's minor units for
amounts, the pair's pinned rate scale for rates — preserved in persistence (`INV-MON-05`).
**Not:** precision by accident. A value at the wrong scale is refused, never silently
rescaled.
**Owned by:** `sharedkernel`

### Spread
**Is:** the platform's margin between the provider's rate and the internal rate, posted
explicitly as revenue with its attribution stored (`INV-FX-03`).
**Not:** the Markup, the commercial component over the internal rate; and not something
concealed inside the applied rate — hidden margin is unreportable revenue.
**Owned by:** `fx`

### Unwind
**Is:** the FX cover of kind `UNWIND` that takes back an executed cover whose position is no
longer wanted — its cross-border payment failed or was recalled, or its wallet conversion
was reversed — replicating the cover's fixed leg in the opposite direction at a fresh firm
quote from the same provider, under the cover's dispatch discipline. Exactly one per quote,
created by whichever writer first finds the position unwanted, any difference from the plan
posted as realised result (`INV-FX-08`, `INV-FX-06`).
**Not:** a reversal of the customer's trade, which a failed payment never booked and a
reversed conversion has already mirrored in the ledger; the unwind is the platform's own
provider leg. Nor a voided cover: a cover that never executed is voided, never unwound.
**Owned by:** `fx`

---

## 8a. Risk, fraud, AML and case management

*(Added by the Phase 12 → 13 transition, 2026-10-22 — ADR-0107…0112 (`Proposed`), `PHASE_13_PLAN.md` §3 and §12.
Twenty-one Phase 13 terms, in the order `DOMAIN_MODEL.md` lists them; Screening Result, `kyc`'s, gains its entry in §3
and Risk Score is sharpened in §7. Nothing of Phase 13 is built: every entry is the planned design, and the task that
builds a concept corrects its entry to the code. The machines they name are in
[`RISK_LIFECYCLES.md`](RISK_LIFECYCLES.md); the module is `MODULE_ARCHITECTURE.md` §4 `risk`. The canonical names
(`PHASE_13_PLAN.md` §3.1): **Risk Assessment** is the record of one evaluation, **Risk Decision** its outcome, **Risk
Score** its number, **Alert** the born-once observation, **Case** the investigation, **Disposition** its end,
**Regulatory Report** the filing artefact — never "the SAR" in code; the jurisdiction names it. The ten Phase 13
invariants (`PHASE_13_PLAN.md` §6) are catalogued by the same transition; the entries below cite only invariants
catalogued before it, and the building tasks add the Phase 13 citations when they correct an entry to the code — the
Phase 9 → 10 precedent.)*

**The words the entries use.** The five decisions, each with a fixed meaning and the operations that admit it
(`PHASE_13_PLAN.md` §12.3, owner decision C2): **`ALLOW`** — the movement may proceed; **`CHALLENGE`** — it may proceed
only under `MULTI_FACTOR` assurance, else it is refused as the reserved `RISK_REFUSED` with reason `CHALLENGE_REQUIRED`;
**`DENY`** — refuse, the caller committing its reserved reason; **`HOLD`** — accept the command and move nothing until a
person decides, admitted only where the operation has a native hold-then-dispatch shape (a withdrawal `HELD`; a card
payment in `P13-TSK-010`'s second slice); **`REVIEW`** — refuse now and open a case. Precedence when rules disagree:
`DENY` > `HOLD` > `REVIEW` > `CHALLENGE` > `ALLOW`, a hard rule's decision beating a score band's. A **fail-safe
decision** is the decision taken when the evaluation is unavailable — no `ACTIVE` policy, a failed read, a throwing
rule, the statement timeout — the policy's fail-safe band for the judged amount (or, with no policy at all, the compiled
`ALLOW`), always **recorded** as an assessment with reason `RISK_UNAVAILABLE` or `NO_POLICY_ACTIVE`; never a silent
default (owner decision C1). **Four eyes** is two persons' acts — the proposer never the approver, by the domain and by
a `CHECK`, a refused self-approval recorded `FAILED` (`INV-AUD-04`). **Self-dealing** is an operator acting on their
own party's subject: refused, fail closed, recorded `FAILED`. **Tipping-off** is letting a subject learn that an AML
alert, case, report or investigation exists: no customer- or merchant-facing surface, log, event payload or metric tag
may carry one, and a customer's answer is the operation's generic outcome.

### Risk Signal
**Is:** an append-only fact about a subject — a party, account, device, merchant, beneficiary or session — of a kind
from a closed vocabulary, with when it was observed and the attributes its source event carried; born once per source
event (`UNIQUE (source_event_id)`) by an inbox handler from identity, kyc, accounts, merchant, crossborder, lending,
bnpl and payments events, or by the monitoring sweep. A device arrives as a hash computed at the source; no
description is stored. *(As built by `P13-TSK-005`, 2026-10-23: the subject is the aggregate the event names — the
events carry their own aggregate and codes, never the party — so a subject is also an identity or a credit agreement
(a loan or a BNPL obligation); thirteen kinds from the events that exist; the completions are hints and record no
signal; a device's hash is `DeviceHash`, SHA-256 of the description, the only shape admitted.)*
**Not:** a Risk Decision or an Alert — a signal is an input nobody judged; a rule reads signals and may raise an alert.
Nor credit's Credit Attribute (evidence from a bureau about creditworthiness), nor the risk *signal* credit receives
through its `CreditRiskSignal` port, which is the Customer Risk Profile's rating code, not a row here.
**Owned by:** `risk`

### Risk Policy
**Is:** the per-operation line of Risk Policy Versions — `TRANSFER`, `WITHDRAWAL`, `CARD_PAYMENT`, `WALLET_PAYMENT`,
`CROSS_BORDER`, `CREDIT_SIGNAL` and `MONITORING` (the sweep's policy, with no seam) — identified by its operation; at most one version
`ACTIVE` at a time, and only an `ACTIVE` version is ever evaluated.
**Not:** the Credit Policy, which decides whether to lend; nor payments' routing policy or crossborder's corridor
policy; nor code — a Rule is data. Production holds no active risk policy until one is activated under four eyes and
the runbook's activation gate is passed; until then every seam answers `ALLOW` with reason `NO_POLICY_ACTIVE`,
recorded.
**Owned by:** `risk`

### Risk Policy Version
**Is:** one immutable version of a Risk Policy as data — its Rules in declared order, each with a weight and/or a hard
decision; score bands mapping [0, 1000] to decisions (for `MONITORING`, rating bands mapping the party's signal score to
`LOW`, `MEDIUM` or `HIGH`); fail-safe bands mapping amount bands to the decision taken when the evaluation is
unavailable (owner decision C1); the hold validity (how long a `HOLD` may stand, default 72 h); the challenge
assurance (`MULTI_FACTOR`); the override threshold
(C7); the engine version it is written for (`RISK_ENGINE_V1`); effective-from — `PROPOSED → ACTIVE → RETIRED` or
`PROPOSED → REJECTED`, activated by a second person, its successor's activation retiring it in one transaction so the
pin never reads "neither". Every Risk Assessment pins the version that judged it (`INV-HIST-04`).
**Not:** credit's Policy Version or bnpl's BNPL Terms Version — the same four-eyes shape, other modules' tables for
other questions. Nor the engine: the engine is code, versioned separately, and a policy version names which engine
reads it.
**Owned by:** `risk`

### Rule
**Is:** one row inside a Risk Policy Version: a `RuleKind` from the closed vocabulary (`AMOUNT_BAND`,
`VELOCITY_COUNT`, `VELOCITY_SUM`, `NEW_PAYEE`, `NEW_DEVICE`, `SESSION_ASSURANCE_BELOW`, `RECENT_AUTH_FAILURES`,
`RECENT_CREDENTIAL_CHANGE`, `RECENT_RECOVERY`, `ACCOUNT_AGE_BELOW`, `KYC_NOT_APPROVED`, `SCREENING_HIT`,
`PROFILE_RATING`, `MERCHANT_SUSPENDED`, `CORRIDOR_HIGH_RISK`, `DORMANT_THEN_ACTIVE`, `RAPID_IN_OUT`, `STRUCTURING`,
`ROUND_AMOUNT`, `REFUND_RATIO`, `CHARGEBACK_RATIO`, `BNPL_MULTI_APPLICATION`) with typed parameters validated against
the kind's schema at the policy door, and a weight (its score contribution when it fires) and/or a hard decision —
data, never code.
**Not:** a kind — the kind is code and declares the parameter schema; the rule is one parameterisation of it. Nor
credit's policy rule row (an operand over credit attributes with an adverse Reason Code) nor reconciliation's Match
Rule (a member of a Matching Rule Set). A rule's firing is a rule hit recorded on the Risk Assessment; a rule never
decides alone — precedence and the bands do. *(As built, `P13-TSK-003`, 2026-10-23: the kind's code is its
`RuleEvaluation`, one per kind; a rule's hit is a `RuleHit` — ordinal, kind, outcome `FIRED` / `NOT_FIRED` / `ABSENT`,
contribution, hard decision, the judged reading's token — recorded for every rule of the version, fired or not; a rule
over an absent reading does not fire and says so.)*
**Owned by:** `risk`

### Risk Assessment
**Is:** the sealed record of one evaluation of one judged command — operation, subject refs, the judged amount, the
inputs read, the policy and engine versions, every rule hit and its contribution, the Risk Score, the Risk Decision
with its ordered reason codes, whether the fail-safe applied, the latency — written `INSERT`-only in the judged
command's own transaction after the caller's locks, `UNIQUE (operation, command_ref)`, born `DECIDED` and never
changed; replayable from its sealed inputs to `IDENTICAL` or `DIVERGED`.
**Not:** credit's Credit Assessment or Decision Snapshot (the affordability, exposure and scorecard figures over frozen
credit data), and not the outcome the caller commits — a transfer's `FAILED(RISK_REFUSED)`, a withdrawal's `HELD` —
which is the owning domain's own row. Nor an Alert, which is an observation a rule made, not a judgement on a command.
The assessment moves no money and is never read by a customer or merchant surface.
**Owned by:** `risk`

### Risk Decision
**Is:** the outcome field of a Risk Assessment — `ALLOW | CHALLENGE | DENY | HOLD | REVIEW` (the five words above) with
its ordered reason codes — immutable, admitted per operation by the policy, mapped by `app`'s seam adapter to the
consuming seam's two-valued verdict (`PERMIT` / `REFUSE`) or, for a withdrawal, to its `HELD`. A fail-safe decision is
a Risk Decision like any other, recorded with reason `RISK_UNAVAILABLE` or `NO_POLICY_ACTIVE`.
**Not:** the money movement's outcome — the caller commits that in its own machine (`transfers`' `RISK_REFUSED`;
payments' `HELD` or `RISK_REFUSED`), and `risk` never posts, holds or releases. Nor a Credit Decision (`INV-CRD-04`'s
four kept apart), nor a Disposition (a person's end to a case), nor a Decision Override, which is a separate aggregate
that never edits the decision.
**Owned by:** `risk`

### Decision Override
**Is:** a four-eyes proposal over one `DENY`, `HOLD` or `REVIEW` Risk Decision — `PROPOSED → APPROVED | REJECTED`, a
second `RISK_OVERRIDE` holder at or above the threshold (owner decision C7: the judged amount at or above EUR 1,000,
or a `DENY`), one holder with a reason below it — recording the action it issues: the release of a held withdrawal (keyed
`risk.action:<override id>` from the approval door; a Risk Action proper once `P13-TSK-017` folds it in), or, for a
denied transfer, a one-time allowance for the same command reference that the customer's retry
is evaluated against. *(As built by `P13-TSK-012`, 2026-10-24: the proposal names its effect - a held withdrawal
released or denied, a held card payment captured or voided, each through payments' keyed door after the approval
commits; for a refused command (`DENY`, `REVIEW`) a **Resubmission**: nothing dispatched, the officers' judgement that
the refusal was wrong recorded, the customer's resubmission judged afresh. The one-time allowance is not built.)*
**Not:** an edit of the decision — the assessment stands as sealed and the override names it. Nor a Disposition (a
case's end), nor an `ALLOW` undone: an `ALLOW` is never overridden after the fact, the money has moved and a case is
the remedy. Nor credit's underwriting second approval.
**Owned by:** `risk`

### Velocity Window
**Is:** a durable counter per (dimension, subject, window kind, window start) — dimensions party, source account,
destination account or beneficiary, device, merchant; kinds `H1`, `D1`, `D7`, `D30`, cut on the database clock read
after the caller's lock — holding a count and a sum in an explicit currency; read by the evaluation under the
subject's serialisation lock (`VelocityDimension`, R2a, `P13-TSK-006`) and upserted in one
statement in the judged command's own transaction, only when the decision permits the movement, so a refused movement
counts for nothing and a committed one exactly once; derivable, and proven against the Monitored Transactions it
counts (`INV-CON-03`).
**Not:** a cache or a Redis counter — a counter that can be lost is not an authority, and one outside the money
transaction can be passed twice. Nor a Balance (the ledger's), nor a limit: a limit is a Rule's parameter judged over
the window. It has no lifecycle.
**Owned by:** `risk`

### Customer Risk Profile
**Is:** `risk`'s rating of one party — `LOW | MEDIUM | HIGH | PROHIBITED` — as versioned, append-only rows
(`UNIQUE (party_id, version)`) naming the assessment or disposition that set each, recomputed by the monitoring sweep
on signals and case outcomes under the profile's head-row lock, through the `MONITORING` policy version's rating
bands (`LOW`, `MEDIUM`, `HIGH`); `PROHIBITED` only by a four-eyes disposition. Read by
every evaluation (`PROFILE_RATING`) and answered to credit's `CreditRiskSignal` seam as the rating code with seam
version 2, recorded in the decision's snapshot so the decision replays as it was sealed whatever the rating does after
(`INV-CRD-01`).
**Not:** credit's Credit Profile (the deciding transaction's anchor, holding no rating) nor kyc's Risk Rating
(onboarding's own, set by the KYC case); nor a Risk Score, which is one assessment's number. A rating change never
rewrites a decision already made, and no customer or merchant surface reads it.
*(As built, `P13-TSK-007`, 2026-10-23: a head row per party and a version per rating **change**; a party risk has not
assessed has no rating at all - credit's seam answers `NOT_ASSESSED`, never a default `LOW` - and nothing is rated while
no `MONITORING` version is `ACTIVE`; credit's snapshot pins the profile version beside the code.)*
**Owned by:** `risk`

### Transaction Risk Profile
**Is:** the computed view of one judged movement — its amount band, counterparty novelty, device novelty and velocity
readings — assembled by the evaluation from signals, the profile and the windows, and stored only as the Risk
Assessment's sealed inputs.
**Not:** a Customer Risk Profile (per party, versioned, persistent) and not a row of its own: it is a value inside the
assessment, never a table, so the same movement judged again under the same inputs reads the same view (the
`INV-CRD-01` discipline, applied to risk).
**Owned by:** `risk`

### Monitored Transaction
**Is:** the channel-neutral record of one money movement, born once per `ledger` Journal Entry
(`UNIQUE (journal_entry_id)`) by the monitoring sweep reading the journal through the `MonitoredLedger` port from a
watermark — classified by the entry's posting-key namespace into a kind with subject refs, counterparties, amount and
value date; an entry of an unknown namespace recorded `UNCLASSIFIED` and alerted, so nothing passes unseen. The
subject of the monitoring rules and the proof of the Velocity Windows.
**Not:** the Journal Entry (the ledger's truth, which it references and never copies as a balance), nor a Transfer,
Payment or Cross-Border Payment (the owning domains' aggregates), nor an event: events are hints that pull the sweep's
permit forward and the sweep reads rows. Reprocessing from any earlier watermark — ordered at
`POST /v1/operator/risk/monitoring/reprocessing`, audited `risk.ReprocessingOrdered` — creates no second row.
**Owned by:** `risk`

### Alert
**Is:** the born-once observation of a monitoring or fraud Rule over a subject — `UNIQUE (subject_ref, rule_code,
window_start)` — carrying its kind, severity, policy version and evidence references; `OPEN → ATTACHED` (to a Case — an
alert raised while its subject has an open case of the kind attaches to it, the system's dedupe; else the case is
opened) `| SUPPRESSED` (only an operator's reasoned suppression under four eyes); never deleted. An **AML alert** is an alert of kind `AML`, raised by the monitoring sweep over
Monitored Transactions; a fraud alert one raised by a fraud rule, synchronously or retrospectively.
**Not:** a Risk Decision (nothing is refused by an alert), nor a Case (the investigation it folds into), nor an
operational alert (`infra/prometheus/rules/risk.yml` fires on metrics, never on a row). A replayed event or a
reprocessed history raises no second alert, and no alert reaches a customer or merchant surface, log, event payload or
metric tag. *(As built, `P13-TSK-015`: the window is a bucket of its kind - `H1`, `D1`, `D7`, `D30` on the velocity
windows' anchor - from a database instant that is the evidence's own; a **suppression** is closing an alert without a
case, two persons' act with a closed `SuppressionReason` - never `DUPLICATE`, which attaches; the operational kinds
`UNCLASSIFIED_TRANSACTION` and `VELOCITY_DIVERGENCE` are declared but no writer raises them as rows. As built by
`P13-TSK-018`, 2026-10-25: suppressed through the analyst's door - proposed under `RISK_INVESTIGATE`, approved by a
second `RISK_CASE_APPROVE` holder from a `MULTI_FACTOR` session - its two persons authenticated humans, `CUSTOMER`- or
`EMPLOYEE`-typed, `risk V014`.)*
**Owned by:** `risk`

### Risk Queue
**Is:** the investigator's ordered read of risk's alerts or cases (`P13-TSK-018`) — oldest first by the immutable key
(`raised_at` or `opened_at`, then id), filtered by one status and optionally one kind, keyset-paged after the last row
served, one read-only snapshot a page, every page audited (`risk.AlertRead`, `risk.CaseRead`) before it is answered;
every row whose subject's accountable party is the reader's own, or cannot be placed, **withheld** — never served,
never named by a cursor.
**Not:** a projection or a store of its own — it reads the tables (`AlertQueue`, `CaseWorklist`). Nor `CaseQueue`, the
routing vocabulary of which desk works a case (`FIRST_LINE` … `SPECIALIST`); nor kyc's review queue or bnpl's dispute
queue, each its own module's read of its own rows. Nor a work assignment: reading a case takes nothing; the take is an
Investigator Action.
**Owned by:** `risk`

### Case File
**Is:** one Case read whole (`P13-TSK-018`, `GET /v1/operator/risk/cases/{caseId}`) — its head, the Alerts attached
to it, its Investigator Actions and history, its Risk Actions, and every evidence reference it holds (an assessment, a
signal or a journal entry, each a kind and an id) — on one snapshot, audited `risk.CaseRead`, refused for the reader's
own party's subject or one the platform cannot place.
**Not:** the evidence itself — every reference is resolved by its own audited read (an assessment through the
assessment door, `risk.AssessmentRead`), never embedded. Nor a Regulatory Report: no narrative is ever part of a file
(`-021`'s own permission). Nor anything a person did by name: who proposed, approved or took is withheld.
**Owned by:** `risk`

### Case
**Is:** the unit of a person's judgement — the investigation — of kind `FRAUD | ATO | MERCHANT_ABUSE | AML |
COLLECTIONS` (owner decision C3), `OPEN → IN_INVESTIGATION → ESCALATED → DISPOSED`, one open per (subject, kind),
opened by the evaluation (`REVIEW`, `HOLD`, a policy-flagged `DENY`), by the sweep (an alert with no open case) or by
hand; taken by one investigator; worked by append-only Investigator Actions under a `revision` compare-and-set;
carrying an SLA deadline; closable as a false positive or with no action straight from `OPEN` by one person; disposed
under four eyes where the Disposition confirms or acts; never re-opened — a new
case references it. An **AML case** is a case of kind `AML`, the subject's open case the monitoring alerts fold into
and the only case a Regulatory Report is drafted from.
**Not:** kyc's KYC Case or Review Task (onboarding's own files), credit's Underwriting Case (a referral's second look
at a credit decision) or reconciliation's Investigation (a break's case file, with no machine of its own) — four
states with four owners, never one shared store (`MODULE_ARCHITECTURE.md` §5). Nor the Regulatory Report: the case is
the platform's own and the report a filing to an authority — different aggregates, lifecycles, permissions and
retention. Nor a collections operation: the `COLLECTIONS` case holds facts only. *(As built, `P13-TSK-016`, 2026-10-25,
`risk V012`: one open per (subject, kind) by the partial unique `risk_case_one_open`; a `revision` that every step - a
person's act or the system's attach of an alert or an assessment - moves by exactly one, each with its history row; a
level (`L1`-`L3`) and a queue an escalation raises; the SLA in hours per kind (`SlaDeadlines`) stamped on the database
clock; opened by T-eval's `REVIEW` or `HOLD` (`ATO` when the sealed hits show the account-takeover anchors, else `FRAUD`
- an anchor rule's hold and its alert one case), by an alert's folding, or by hand; a merchant's `MERCHANT_ABUSE` case
judged for self-dealing on the merchant's organisation party.)*
**Owned by:** `risk`

### Investigator Action
**Is:** one append-only, reasoned, audited fact of a Case's investigation — a note, evidence attached (as references:
signal, assessment and journal entry ids resolved through audited reads, never copied), an escalation, an action
proposed, approved or rejected, a disposition proposed, approved or rejected, a report drafted — each carrying the
case's `revision` it was made against, so two investigators' concurrent actions are both kept while one answers
`409 risk.CaseRevisionStale`.
**Not:** a Risk Action — the investigator's act records; the risk action is the downstream command through an owning
domain's door. Nor a case state: an action never edits or deletes an earlier one. Notes are bounded, screened for
instrument shapes and classified `CONFIDENTIAL`. *(As built, `P13-TSK-016`: `risk_case_action`, `INSERT`-only for every
writer, its `ordinal` the case revision it produced; the kinds this task writes - `NOTE`, `EVIDENCE_ATTACHED`, `TAKEN`,
`ESCALATED`, `DISPOSITION_PROPOSED` (whose id is the disposition's), `DISPOSITION_APPROVED`, `DISPOSITION_REJECTED` - and
`ACTION_*` (`-017`) and `REPORT_DRAFTED` (`-021`) admitted; every one but the take carries its person's text, which is
classified `RESTRICTED-PII` (`DESIGN-016` D13, the override's and suppression's level). `REPORT_DRAFTED` as built by
`P13-TSK-021`: written on the disposed AML case beside the report it names (its id the report's), untexted, its ordinal
past the case's last revision - a disposed case never steps - and withheld from the analyst's case file.)*
**Owned by:** `risk`

### Disposition
**Is:** a Case's end — `CLOSED_FALSE_POSITIVE | CLOSED_NO_ACTION | CONFIRMED_FRAUD | CONFIRMED_SUSPICIOUS | REFERRED` —
proposed by a `RISK_INVESTIGATE` holder and, for every `CONFIRMED_*` and for any disposition that issues a Risk
Action, approved by a second person holding `RISK_CASE_APPROVE` on a `MULTI_FACTOR` session; recorded as Investigator
Actions; the act that may set a Customer Risk Profile to `PROHIBITED` and that permits a Regulatory Report
(`CONFIRMED_SUSPICIOUS`).
**Not:** a Risk Decision (made by the engine on a command, before any person) nor a Decision Override (a person's act
on one decision). Nor a reversal: `REFERRED` recommends the owning domain's four-eyes act and performs nothing.
*(As built, `P13-TSK-016`: a slot on the case - a standing proposal or the decision; `CLOSED_FALSE_POSITIVE` and
`CLOSED_NO_ACTION` decided by their proposal, from `OPEN` too; `CONFIRMED_*`, `REFERRED` and any that sets
`PROHIBITED` proposed on a taken case and approved by a second person - `risk_case_disposition_four_eyes`;
`PROHIBITED` asked by the proposal (`prohibit`), never implied, of a confirming disposition of a party's case alone, and
written to the profile after the case commits; an action-issuing disposition joins the four-eyes set with `-017`.)*
**Owned by:** `risk`

### Escalation
**Is:** the case transition `IN_INVESTIGATION → ESCALATED` — the case's level and queue raised by an investigator,
reasoned and recorded as an Investigator Action, published as `CaseEscalated`; the case goes on. *(As built,
`P13-TSK-016`: one level a step, `L1` to `L3`, to a queue named (`SECOND_LINE`, `SENIOR_REVIEW`, `SPECIALIST`); an
escalated case may be escalated again until `L3`.)*
**Not:** a Disposition (an escalated case is still open) nor a Risk Action (nothing leaves `risk`). Nor
reconciliation's break escalation, which is a severity's forward-only step on a break.
**Owned by:** `risk`

### Risk Action
**Is:** `risk`'s record of one downstream effect a Case or a Decision Override issues — `PROPOSED → APPROVED` (a
second person) `| REJECTED`; `APPROVED → DISPATCHED → DONE | FAILED` — of a kind from the closed vocabulary
(`SUSPEND_IDENTITY`, `REINSTATE_IDENTITY`, `REVOKE_SESSIONS`, `SUSPEND_ACCOUNT`, `REINSTATE_ACCOUNT`,
`SUSPEND_MERCHANT`, `BLOCK_BENEFICIARY`, `RELEASE_HELD_WITHDRAWAL`, `DENY_HELD_WITHDRAWAL`, `RESCREEN_PARTY`), one
live per (case, kind, target); dispatched by `RiskActionSchedule` in its own transaction as a keyed command
(`risk.action:<id>`) through the owning domain's **existing** door on that domain's lock order, its outcome recorded —
a domain's refusal (already suspended) a recorded `DONE`, never an error; a `FAILED` action re-proposable, never
re-dispatched by itself. *(As built by `P13-TSK-017`, 2026-10-25, `risk V013`: the id is the proposing investigator
action's or the issuing override's, so the key is fixed at birth; the effect already in place (an identity already
suspended) is `DONE(ALREADY)` and an owner's refusal `FAILED(REFUSED)`, each recorded once and never retried; the
owner's command runs as the platform, the deciding persons recorded on the action; `SUSPEND_ACCOUNT`,
`REINSTATE_ACCOUNT` and `BLOCK_BENEFICIARY` have no owning command and are unavailable; no disposition issues one -
every case action is proposed on the case and approved by a second person; an approved override's is born `APPROVED`.)*
**Not:** the effect — the suspension, the block, the release are the owning domain's own commands, audited by their
owner; `risk` never writes another module's row, never posts and never reverses a transaction (ADR-0112). Nor an
Investigator Action (the case's record of proposing it).
**Owned by:** `risk`

### Screening List Version
**Is:** an operator-recorded fact, under `RISK_POLICY_ADMINISTER`, that a sanctions or PEP list version is in force —
`UNIQUE (list, version)`, recorded then activated — whose activation enqueues one rescreening permit per `ACTIVE`
party; the sweep's rescreening leg requests each screening through kyc's existing ports, records the clearance as a
Risk Signal and raises an Alert on a hit.
**Not:** a Screening Result — kyc screens and owns the result and its evidence; `risk` asks, and owns only the list
fact, the permits and what it records from the answer (`ROADMAP.md` Refinement 3). Nor a Risk Policy Version: no rule
reads the list itself. *(As built by `P13-TSK-022`, `risk V017`, 2026-10-26: `RECORDED → ACTIVE → RETIRED`, recorded and
activated by two persons, the predecessor retired beside the activation; each permit (one per party and version) records
kyc's case-bound screening of the list's type only when kyc was asked it after the activation - by the ask, never
the answer (`P13-TSK-022`'s review); an older screening is not a rescreening, and the permit waits and fails at its bound, counted. kyc has no door yet that re-screens a decided
party, so the permits of parties screened before the activation fail until it does - an owner item.)*
**Owned by:** `risk`

### Regulatory Report
**Is:** the filing artefact to an authority, drafted from a `DISPOSED` `CONFIRMED_SUSPICIOUS` Case by an
`AML_REPORT_APPROVE` holder, approved by a second and filed by an `AML_REPORT_FILE` holder through the
`RegulatoryFilingProvider` port (simulated in Phase 13) — `DRAFT → APPROVED → FILED → ACKNOWLEDGED | REJECTED`, a
rejected filing a new draft — carrying a jurisdiction code and format version, the reporting entity's reference, the
subject, account and transaction references, the grounds (the case's reason codes and the approved narrative,
`RESTRICTED-PII`), the attachments' references and the acknowledgement; immutable once `FILED`; `FILED` committed
before the call, the acknowledgement or rejection recorded by the filing port's inquiry leg, and an unknown answer a
permit on the `FILED` row, never re-filed.
**Not:** the Case — the platform's own investigation, with other permissions and retention — nor "the SAR": the
jurisdiction names the report, and every field a real jurisdiction adds is the adapter's data (owner decision C4,
ADR-0111). Nor an audit record, nor a report in the operator-reports sense (`GET /v1/operator/reports/…`). Its event
carries the report id and jurisdiction and never the subject; its existence is never derivable from a customer or
merchant surface (tipping-off). *(As built, `P13-TSK-021`: `risk V016`'s `regulatory_report`; drafted from an `AML`
case of a party alone, its transactions, accounts and grounds drawn from the case; its id its `REPORT_DRAFTED`
investigator action's and its filing reference; one live or accepted report per case (only a `REJECTED` filing frees the
case); the content frozen from birth; filed through `app`'s simulated FIU, the first attempt submitting and every later
one inquiring by the filing reference; never in the analyst's case file - the narrative is served by the AML desk's
doors alone.)*
**Owned by:** `risk`

### Fraud Detection
**Is:** the capability (bounded context 21) that judges a money-moving command before it happens and watches for abuse
after: synchronous fraud decisioning in the caller's transaction, latency-bounded, through the seams the domains
declare — the Risk Assessment and its Risk Decision — and retrospective fraud rules over Risk Signals (account
takeover: a credential change or recovery followed by a new payee, a new device and a withdrawal; payment, merchant
and BNPL abuse: chargeback and refund ratios, refunds to new accounts, applications across merchants, a session
confirming many checkouts), each raising an Alert born once and folding into a Case of the matching kind.
**Not:** Transaction Monitoring — fraud decisioning is synchronous and asks "should this movement proceed?";
monitoring is asynchronous and asks "is this subject's pattern suspicious?". Nor onboarding screening (kyc's), nor
credit's Underwriting, nor a machine-learned model: rules as data through a versioned, deterministic engine, every
verdict explainable as a sum of rule contributions.
**Owned by:** `risk`

### Transaction Monitoring
**Is:** the AML capability (bounded context 22) that watches money after it moves: a leaderless sweep reading the
ledger journal — the one financial truth — through a port from a watermark, classifying every entry into a Monitored
Transaction, evaluating the monitoring policy's rules per subject over durable aggregates (`STRUCTURING`,
`RAPID_IN_OUT`, `DORMANT_THEN_ACTIVE`, `ROUND_AMOUNT`, `CORRIDOR_HIGH_RISK`, `VELOCITY_*`), raising Alerts born once,
folding them into the subject's open AML Case, recomputing the Customer Risk Profile, and keeping the internal
investigation physically distinct from the Regulatory Report; idempotent under reprocessing from any watermark. *(As
built by `P13-TSK-020`: "per subject" is per **party** - the customer over all its accounts, its movements read from
the journal's record by its accounts as subject or customer counterparty, its windows cut on the value date, its AML
alerts the party's; the rules run inside the intake's claim.)*
**Not:** Fraud Detection (synchronous, in the money transaction, never from the journal), nor kyc's onboarding
screening or Counterparty Screening (a decision at a moment about a party; monitoring is ongoing and about patterns —
rescreening on list updates is requested through kyc), nor reconciliation (which compares the platform's records with
external evidence; monitoring judges the platform's own movements). It moves no money and reads no event as truth.
**Owned by:** `risk`

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

**`Risk Score`'s owner — settled: `risk` (Phase 13).** *(Settled by the Phase 9 → 10
transition, 2026-10-07 — ADR-0084 (`Proposed`), `PHASE_10_PLAN.md` §3.)* `P0-DOC-011` found the
question open: `MODULE_ARCHITECTURE.md` §4 listed `Risk Score` under `credit`, beside
`Credit Score`, while `risk` owned `Risk Assessment` and `Risk Decision`, and a risk score measures
fraud and abuse — how this glossary defines it, and how `CLAUDE.md` contrasts it with a credit
score. The glossary followed the register then, because ADR-0012 makes the register the authority
on ownership: **a glossary must not settle an ownership question by quietly disagreeing with the
document that owns it**, and the guard in §11 makes such a disagreement a build failure.

The answer was given where it belonged, in the register: a risk score answers a fraud question,
from fraud inputs, with a fraud consequence, so it is `risk`'s. Credit needs a risk signal only as
an input — a hard decline on a confirmed fraud flag — and so declares the `CreditRiskSignal` port,
whose Phase 10 composition answers `NOT_ASSESSED` for every party, deterministically; the Decision
Snapshot records that answer and the seam's version, so a decision made before Phase 13 replays
identically after it (`INV-CRD-01`). `MODULE_ARCHITECTURE.md` §4 and §5 moved `Risk Score` from
`credit` to `risk` in the same change as this entry, so the two documents never disagreed.

**The canonical list spelled `Installment` once**, against twenty uses of `Instalment` elsewhere
including the module register that assigns its ownership. Corrected in `DOMAIN_MODEL.md` to the
majority spelling.

**`Instalment` was claimed by two modules, and the canonical list kept one word for both** —
settled by the Phase 11 → 12 transition, 2026-10-16 (ADR-0101; built - `bnpl`'s own `bnpl_instalment`, never a lending row - read against the code by `P12-DOC-001`, 2026-10-22, which read "ADR-0101, planned" until then). The register had already
renamed the two states Loan Instalment (`lending`) and BNPL Instalment (`bnpl`) —
`MODULE_ARCHITECTURE.md` §5 — while the canonical list and this glossary still defined one
`Instalment` "within a loan or BNPL schedule", owned by `lending`. The plain `Instalment` stays (it is
the canonical term `DomainGlossaryTest` names) and now defines the loan's alone; `BNPL Instalment` is
its own canonical term in §7b, beside `BNPL Schedule` — the register's "Instalment Plan", renamed so
that "plan" means only what the customer chooses (Pay-in-4, or Pay-in-N over 3, 6 or 12 months).
Register, list and glossary changed together, so the three never disagreed.

**`Investigation` is `reconciliation`'s, and the risk investigation is `Case`** *(the Phase 12 → 13 transition,
2026-10-22 — ADR-0107, ADR-0110 (`Proposed`))*. The register's `reconciliation` `Owns:` line names Investigation — the
break's case file, with no machine of its own — so this glossary defines no `Investigation` entry for Phase 13, which
would contradict the register under the §11 guard; the plan's canonical name for the risk investigation is **Case**
(§8a), whose entry contrasts the four case-like states with four owners (kyc's KYC Case and Review Task, credit's
Underwriting Case, reconciliation's Investigation, risk's Case). Likewise "AML alert" and "AML case" are kinds of
Alert and Case, defined inside those entries, not canonical terms. **`Screening Result` is `kyc`'s** and gains an entry
in §3 because Phase 13 reads it: `risk` owns the Screening List Version and records a Risk Signal from the answer,
never the result — the register's "Screening result reads like one state but is two", resolved in favour of one
owner.

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
   Added during review, which found `Risk Score` attributed to `risk` while the register said
   `credit` — settled by the Phase 9 → 10 transition (2026-10-07, §10), which moved it to `risk`
   in the register itself, both documents changed together.
7. **Every `INV-*` the glossary cites exists.** Ninety-six distinct invariants, cited two hundred
   and two times (the Phase 12 → 13 transition, 2026-10-22, added §8a's twenty-one entries, Screening Result in §3 and the sharpened Risk Score, each citing only invariants catalogued before it — the ten Phase 13 invariants the same transition catalogued are to be cited when the building tasks correct the entries to the code, the Phase 9 → 10 precedent; the count is the Phase 12 exit review's, to be recounted at the Phase 13 exit review; recounted by the Phase 12 exit review, `P12-DOC-001`, 2026-10-22, after its corrections to the BNPL
   entries - no new invariant cited; the guard checks only that each cited invariant exists, never the count
   *(P12-DOC-001, 2026-10-22: this read "cited two hundred times", stale already at `2b0d68b6`'s two hundred and one)*;
   the Phase 11 → 12 transition, 2026-10-16, had counted ninety-six and two hundred after §7b's fourteen new entries,
   the sharpened BNPL Agreement and Instalment and §2's BNPL paragraph, which cite the ten `INV-BNPL`
   invariants the same transition catalogued; the Phase 10 → 11 transition, 2026-10-10, had counted
   eighty-four and one hundred and eighty-three after
   §7a's twenty-three new entries and seven corrected ones in §7, which cite the fourteen
   `INV-LND` invariants the same transition catalogued; the Phase 10 exit review, `P10-DOC-001`,
   2026-10-09, had counted sixty-nine and one hundred and fifty-nine after its six new entries,
   the renamed Credit Record and twelve corrected ones,
   which cite for the first time the eight Phase 10 credit invariants the transition catalogued;
   the
   Phase 9 → 10 transition, 2026-10-07, had counted sixty-one and one hundred and forty-two
   after its
   fourteen new entries and six sharpened ones — which cite only invariants catalogued before
   it; the eight new Phase 10 credit invariants the same transition catalogued were to be cited by a
   term's entry when the task that builds it corrects that entry; the Phase 9 exit review, `P9-DOC-001`, 2026-10-07, had
   counted sixty-one and one hundred and thirty-four after its six new entries and four
   corrections; the Phase 8 → 9 transition,
   2026-10-02, had counted sixty and one hundred and fifteen after its twenty-two new
   entries; the Phase 8 exit review, `P8-DOC-001`, had counted forty-two
   and seventy-two, the Phase 7 → 8 transition thirty-eight and fifty-four, and the Phase 6
   review, `P6-DOC-001`, twenty-five and twenty-nine), none of which any other check would
   notice going stale.
8. Every distinction group has a §2 heading repeating the group exactly, so a group added to
   `CLAUDE.md` fails the build until it is contrasted.
9. All of the above are actually parsed, so a reformatted document fails loudly rather than
   silently matching nothing.

**Not enforced:** that a definition is *correct*. No test can check that. What the guard protects
is that no term is silently undefined, no definition omits its contrast, and no distinction the
platform's own instructions forbid collapsing is left uncontrasted.
