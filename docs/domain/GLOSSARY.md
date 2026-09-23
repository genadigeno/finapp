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

**Nothing here is implemented.** Phase 0 delivers the financial and platform kernel and *zero*
business capability, so no production class is named for any term below — checked, not assumed.
The owning module column names where each concept **will** live, per
[`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md) §4, which records the phase each
module arrives in.

---

## 1. How to read an entry

```
### Term
**Is:** what it is.
**Not:** the concept it is most often confused with, and why the difference matters.
**Owned by:** the module that will own this state, or `external` for a party we do not model.
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
Merchant Settlement   platform  ->  merchant      net of fees, on a payout cycle
```

Collapsing them is how fees disappear. The amount differs, the timing differs, the counterparty
differs, and the accounts differ — a customer payment credits a liability to the merchant, and a
settlement discharges it. `INV-SET-02` requires the gap between them to be a tracked expectation
that ages, precisely because the two are not the same event.

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
**Is:** the exchange of transaction records between institutions to establish what is owed.
**Not:** Settlement. Clearing agrees the obligation; settlement discharges it.
**Owned by:** `settlement`

### Settlement
**Is:** the actual movement of funds that discharges an obligation.
**Not:** Capture, Clearing, or Reconciliation. `INV-SET-01` keeps internal completion and
settlement as separate states; `INV-SET-03` requires late settlement to be processed rather than
discarded.
**Owned by:** `settlement`

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
**Is:** money flowing from the platform to a merchant, net of fees, on a payout cycle.
**Not:** a Customer Payment. Different direction, amount, timing and accounts.
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
policy — immutable once effective; change creates a new version effective forward.
**Not:** a fee assessment, which is the historical fact pinned to the version that priced
it (`INV-MER-03`).
**Owned by:** `merchant`

### Merchant Payable
**Is:** what the platform owes a merchant — the merchant's payable **ledger account
position**: captured − fees − refunds − payouts (`INV-MER-02`).
**Not:** a stored balance field. It exists nowhere except as postings.
**Owned by:** `ledger` (the position); `merchant` (the account's purpose)

### Merchant Payout
**Is:** a distinct money movement paying the merchant's net payable outward, with its own
lifecycle, hold, idempotency and provider ambiguity handling (ADR-0051).
**Not:** settlement of the customer's payment, and not automatic — initiated, bounded by
the payable, and final only at Phase 8's settlement.
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

## 9. Ledger and reconciliation

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
**Is:** a ledger account holding value whose final destination is not yet determined.
**Not:** a permanent home. `INV-REC-05` requires suspense to be aged, reported and alerted on —
ageing suspense is an unrecognised loss or liability.
**Owned by:** `ledger`

### Reconciliation Batch
**Is:** one run comparing a set of internal records against one external source, with its own
metadata and matching rule version.
**Not:** a Settlement Batch (which is the external file or cycle being compared against), and not
Settlement itself.
**Owned by:** `reconciliation`

### Reconciliation Break
**Is:** a classified discrepancy between internal and external records — missing on either side, or
differing in amount, currency, fee or timing.
**Not:** an error to be cleared. A break has a lifecycle, its evidence is preserved on both sides
(`INV-REC-01`), and it is resolved by a compensating posting with a reason code — never by editing
either record (`INV-REC-03`).
**Owned by:** `reconciliation`

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
7. **Every `INV-*` the glossary cites exists.** Twenty-three citations, none of which any other
   check would notice going stale.
8. Every distinction group has a §2 heading repeating the group exactly, so a group added to
   `CLAUDE.md` fails the build until it is contrasted.
9. All of the above are actually parsed, so a reformatted document fails loudly rather than
   silently matching nothing.

**Not enforced:** that a definition is *correct*. No test can check that. What the guard protects
is that no term is silently undefined, no definition omits its contrast, and no distinction the
platform's own instructions forbid collapsing is left uncontrasted.
