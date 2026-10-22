# Domain Model

Canonical concepts to distinguish:

Party
Customer
User
Identity
Credential
Device
Session
Consent
KYC Case
KYB Case
Screening Result

Account
Wallet
Ledger Account
Bank Account
Settlement Account
Balance
Hold
Beneficiary

Payment
Transfer
Payment Intent
Payment Attempt
Payment Method
Authorization
Capture
Clearing
Settlement
Refund
Chargeback
Dispute
Payment Rail
Routing Decision
A2A Payment
Instant Payment
Withdrawal
Interaction Model
Void
Return Payment
Dispute Response

Merchant
Checkout Session
Order
Fee Schedule
Merchant Payable
Merchant Payout
PSP
Processor
Acquirer
Issuer
Payment Network

Credit Profile
Credit Score
Risk Score
Credit Decision
Loan Application
Loan Offer
Loan
Instalment
Exposure
Delinquency
BNPL Agreement

Credit Data
Credit Data Source
Credit Data Request
Credit Record
Credit Evidence
Credit Attribute
Decision Request
Decision Snapshot
Credit Assessment
Affordability Assessment
Credit Policy
Policy Version
Model Version
Engine Version
Policy Evaluation
Reason Code
Underwriting Case
Credit Product
Decision Consumption
Decision Replay

Loan Agreement
Loan Product Terms Version
Repayment Schedule
Instalment Billing
Interest Accrual
Repayment
Repayment Allocation
Repayment Reversal
Fee Assessment
Waiver
Loan Credit Balance
Days Past Due
Default
Payoff
Disbursement
Loan Payout
Credit Line
Credit Line Draw
Credit Line Statement
Minimum Payment
Available Limit
Restructuring
Refinance
Write-off
Collections
Lending Capital

BNPL Terms Version
BNPL Application
BNPL Offer
Pay-in-4
Pay-in-N
BNPL Purchase
Merchant Financing
Merchant Discount
BNPL Enablement
BNPL Fee Pin
Customer Repayment Obligation
BNPL Schedule
BNPL Instalment
BNPL Interest Accrual
BNPL Payoff
BNPL Repayment Reversal
BNPL Waiver
BNPL Refund
Refund Adjustment
BNPL Dispute

Money
Currency
Minor unit
Scale
Rounding policy

FX Quote
Exchange Rate
FX Trade
Reference Rate
Provider Rate
Customer Quote
Spread
Markup
Rate Lock
FX Cover
FX Position
Realised FX Result
Posting Plan
Unwind

Counterparty
Corridor
Corridor Rail
Cross-Border Payment
Outbound Credit
Payment Offer
Cross-Border Beneficiary
Counterparty Screening
Cancellation Request
Recall
Cross-Border Return
Payee Check

Risk Signal
Risk Policy
Risk Policy Version
Rule
Risk Assessment
Risk Decision
Decision Override
Velocity Window
Customer Risk Profile
Transaction Risk Profile
Monitored Transaction
Alert
Case
Investigator Action
Disposition
Escalation
Risk Action
Screening List Version
Regulatory Report
Fraud Detection
Transaction Monitoring

Journal Entry
Journal Line
Chart of Accounts
Suspense Account
Reconciliation Batch
Reconciliation Break
Settlement Batch
Remittance
Settlement Expectation
Match Decision
Settlement File
External Item
Suspense Item
Resolution
Matching Rule Set
Run Replay
Repudiation
Attestation
Readmission

Important: these names are not automatically aggregates or tables. Determine domain ownership and lifecycle before implementation.

Every term above is defined in [`GLOSSARY.md`](GLOSSARY.md), with an explicit statement of what it
is **not** and the module that owns it — or, for a term a later phase builds, will own it. The
glossary also defines the seven terms `CLAUDE.md` §Domain Distinctions forbids collapsing but this
list never named, and contrasts all eight of its groups. `DomainGlossaryTest` fails the build when
this list and the glossary stop agreeing, in either direction (`P0-DOC-011`). *(Settlement
Account, Settlement Batch, Remittance, Settlement Expectation and Match Decision were added by the
Phase 7 → 8 transition — ADR-0064, ADR-0065, ADR-0067 and ADR-0068 — each keeping a distinction
Phase 8 could collapse. Settlement File, External Item, Suspense Item, Resolution, Matching Rule
Set, Run Replay, Repudiation, Attestation and Readmission were added by the Phase 8 exit review,
`P8-DOC-001`, 2026-10-01: Phase 8 built each as a distinct concept, and neither list named one.
"Will own" corrected to "owns" there too.)* *(The Phase 8 → 9 transition, 2026-10-02 —
ADR-0074…0083 (`Proposed`) — added the twenty-two Phase 9 terms: the kernel's money
vocabulary (Money, Currency, Minor unit, Scale, Rounding policy), made canonical because
five currencies at three scales now post; the rate chain and the FX books (Reference Rate,
Provider Rate, Customer Quote, Spread, Markup, Rate Lock, FX Cover, FX Position, Realised
FX Result); and the cross-border model (Counterparty, Corridor, Corridor Rail, Cross-Border
Payment, Outbound Credit, Payment Offer, Cross-Border Beneficiary, Counterparty Screening) —
each keeping a distinction Phase 9 could collapse, defined in the glossary with the same
guard. The machines they name are in
[`FX_AND_CROSS_BORDER_LIFECYCLES.md`](FX_AND_CROSS_BORDER_LIFECYCLES.md).)* *(The Phase 9 exit review, `P9-DOC-001`,
2026-10-07, added six: Posting Plan and Unwind to the FX books, and Cancellation Request, Recall,
Cross-Border Return and Payee Check to the cross-border model — Phase 9 built each as a distinct
concept, and neither list named one.)* *(The Phase 9 → 10 transition, 2026-10-07 —
ADR-0084…0089 (`Proposed`), `PHASE_10_PLAN.md` §3 — added the fourteen Phase 10 terms in the
credit block after BNPL Agreement: the data side (Credit Data, Credit Data Source, Credit Bureau
Record, Credit Attribute), the request and its frozen input (Decision Request, Decision
Snapshot), the figures (Credit Assessment, Affordability Assessment), policy and model as
versioned data (Credit Policy, Policy Version, Model Version, Reason Code), the manual review
(Underwriting Case) and the closed product vocabulary (Credit Product) — each keeping a
distinction Phase 10 could collapse, `CLAUDE.md`'s Credit Score / Risk Score / Credit Decision /
Underwriting made physical. Nothing of Phase 10 is built: each is the planned design, defined in
the glossary with the same guard, and the machines are in `CREDIT_DECISIONING_LIFECYCLES.md`. The
same transition settled `Risk Score`'s owner as `risk` (Phase 13), credit consuming a risk
signal through a seam — the glossary's §10.)* *(The Phase 10 exit review, `P10-DOC-001`,
2026-10-09: Phase 10 is built — `P10-TSK-001`…`-021`, `X-TSK-017` and `P10-TST-001` — and every
credit entry was read against the code. Credit Bureau Record was renamed Credit Record, one
`credit_record` table holding both source kinds, and six terms were added, each built as its own
table or code concept that neither list named: Credit Data Request, Credit Evidence, Engine
Version, Policy Evaluation, Decision Consumption and Decision Replay.)* *(The Phase 10 → 11
transition, 2026-10-10 — ADR-0090…0100 (`Proposed`), `PHASE_11_PLAN.md` §3 and §12 — added the
twenty-three Phase 11 terms in the lending block after Decision Replay: the contract (Loan
Agreement, Loan Product Terms Version), the projection and what actually became due (Repayment
Schedule, Instalment Billing, Interest Accrual), money received and its split (Repayment,
Repayment Allocation, Loan Credit Balance), arrears (Days Past Due, Default), the money's way in
and out (Payoff, Disbursement, Loan Payout, Lending Capital), the revolving product (Credit Line,
Credit Line Draw, Credit Line Statement, Minimum Payment, Available Limit), contractual change
(Restructuring, Refinance) and the deferred ends (Write-off, Collections) — each keeping a
distinction Phase 11 could collapse. Nothing of Phase 11 is built: each is the planned design,
defined in the glossary's §7a with the same guard, and the machines are in
[`LENDING_LIFECYCLES.md`](LENDING_LIFECYCLES.md).)* *(`P11-TSK-020`, 2026-10-13, added Repayment Reversal
after Repayment Allocation: the four-eyes correction that negates a repayment's allocation by rows and swaps its entry
exactly - never an edit and never a re-allocation, a distinction a "refund the repayment" verb would collapse.)* *(`P11-TSK-025`, 2026-10-13, added Fee Assessment and Waiver
after it: the late fee charged once per billed item and capped, and the four-eyes forgiveness that posts - kept apart from
the Repayment Reversal, which undoes money applied, and from a write-off.)* *(The Phase 11 exit review,
`P11-DOC-001`, 2026-10-15: Phase 11 is built — `P11-TSK-001`…`-036`, lending `V001`…`V028` — and every lending
entry was read against the code; every term above is built except the three deferred ends (Refinance, Write-off,
Collections), so the transition's "Nothing of Phase 11 is built: each is the planned design" no longer holds and the
glossary's §7a states each as built. No term was added: Loan Amendment, Payoff Quote and Payoff Execution and
Line Closure Request were built as the glossary names them, under Restructuring, Payoff and Credit Line.)*
*(The Phase 11 → 12 transition, 2026-10-16 — ADR-0101…0106 (`Proposed`), `PHASE_12_PLAN.md` §3 and §12 — added
the fourteen Phase 12 terms in the BNPL block after Lending Capital: the product as versioned data and its two
products (BNPL Terms Version, Pay-in-4, Pay-in-N), origination at checkout (BNPL Application, BNPL Offer), the one
economic event and its two flows (BNPL Purchase; Merchant Financing and its Merchant Discount on the merchant's side,
the Customer Repayment Obligation on the customer's), the projection (BNPL Schedule, BNPL Instalment), and value
returned (BNPL Refund, Refund Adjustment, BNPL Dispute) — each keeping a distinction Phase 12 could collapse: a
purchase is not a payment or an order, financing is not a payout, the obligation is not a loan, a BNPL refund is not
a card refund, and a BNPL dispute is not a chargeback. BNPL Agreement keeps its place in the credit block,
sharpened. **Instalment** now names the loan's alone — the module register's Loan Instalment — and **BNPL
Instalment** is its own term: the register's resolution of "Instalment claimed by two modules", confirmed by the
same transition (the BNPL obligation is not a lending loan, ADR-0101), and the register's "Instalment Plan" is
renamed **BNPL Schedule** so that "plan" means only what the customer chooses. BNPL's servicing facts re-apply the
lending terms (billing, accrual, repayment, allocation, Days Past Due, Delinquency, Default, Payoff, Waiver,
Repayment Reversal) to the obligation and add none. Nothing of Phase 12 is built: each is the planned design,
defined in the glossary's §7b with the same guard, and the machines are in
[`BNPL_LIFECYCLES.md`](BNPL_LIFECYCLES.md).)* *(The Phase 12 exit review, `P12-DOC-001`, 2026-10-22: Phase 12 is built — `P12-TSK-001`…`-028`, `P12-TST-001` and `-002`, bnpl `V001`…`V017` — and every BNPL entry was read against the code; every term above is built, so the transition's "Nothing of Phase 12 is built: each is the planned design" no longer holds and the glossary states each as built. Six terms were added, each built as its own table, which the glossary already defined and this list did not name — a disagreement `DomainGlossaryTest`'s union check refuses: BNPL Enablement and BNPL Fee Pin (`merchant` `V010`, `P12-TSK-006`) after Merchant Discount, and BNPL Interest Accrual (`bnpl` `V008`/`V009`), BNPL Payoff (`V014`), BNPL Repayment Reversal and BNPL Waiver (`V016`) after BNPL Instalment — so "BNPL's servicing facts re-apply the lending terms … and add none" held for billing, repayment, allocation, Days Past Due, Delinquency and Default only. No term was renamed.)*
*(The Phase 12 → 13 transition, 2026-10-22 — ADR-0107…0112 (`Proposed`), `PHASE_13_PLAN.md` §3 and §12 — added the
twenty-one Phase 13 terms in a risk block after Payee Check: the inputs (Risk Signal, Velocity Window, Monitored
Transaction, Transaction Risk Profile), policy as versioned data (Risk Policy, Risk Policy Version, Rule), the
judgement (Risk Assessment, Risk Decision, Decision Override — Risk Score keeps its place in the credit block,
sharpened), the party's standing (Customer Risk Profile), the investigation (Alert, Case, Investigator Action,
Disposition, Escalation, Risk Action), the filing and the lists (Regulatory Report, Screening List Version) and the two
capabilities kept distinct inside one module (Fraud Detection, Transaction Monitoring) — each keeping a distinction
Phase 13 could collapse: an assessment is not a decision and neither is a score, an alert is not a case and a case is
not a report, a risk action is not the owning domain's effect, a velocity window is not a cache, and a monitored
transaction is not the journal entry. Screening Result was added after KYB Case: `kyc` has owned it since
`P0-TSK-006` and Phase 13 reads it through a port, so the list names it. Nothing of Phase 13 is built: each is the
planned design, defined in the glossary's §8a with the same guard, and the machines are in
[`RISK_LIFECYCLES.md`](RISK_LIFECYCLES.md).)*

---

## Time

Three distinct concepts, routinely conflated, each with different consequences when wrong.
Established here in Phase 0 (`P0-TSK-013`) because the ledger inherits them in Phase 3 and a
posting cannot be re-dated afterwards (`INV-HIST-01`).

| Concept | What it is | Where it comes from |
|---------|-----------|---------------------|
| **System time** | When the machine executed something | The injected `Clock`. Never a business fact |
| **Posting date** | The accounting date an entry belongs to; decides which period it falls in | A domain decision, subject to the period being open (`INV-ACC-03`) |
| **Value date** | When value is actually available or interest starts accruing | A rail, product or contract rule |

**Why the distinction is not pedantry.**

- A transfer executed at 23:59:58 on the 31st may have a posting date of the 31st and a value
  date of the following business day. Using system time for all three misstates both the
  period's totals and the customer's available balance.
- Back-dated corrections have a posting date in an open period while describing an economic
  event from a closed one. Collapsing the two either forbids the correction or silently reopens
  a closed period (`INV-ACC-03`).
- Interest accrual runs on value dates. Accruing on system time makes the amount depend on when
  the batch happened to run, which makes it irreproducible (`INV-CRD-01`).
- Settlement arriving late (`INV-SET-03`) has a system time long after its value date. A model
  with one date cannot represent it and will either reject it or misdate it.

**Consequences for implementation.** System time is read only from an injected `Clock`, enforced
mechanically (`MODULE_ARCHITECTURE.md` §6, *Time boundary*). Posting date and value date are
**inputs to a domain operation, never clock reads**; a component that derives a posting date by
calling the clock has silently decided that execution time and accounting date are the same
thing. No rule can catch that substitution — it is a design review question, and it is why the
three are named here rather than left implicit.

**One decided exception, and the rule that keeps it safe** (ADR-0065 §6; `P8-TSK-009`). A
settlement batch's recognition takes its posting date from the clock **once**: `BatchAcceptance`
derives the UTC business date at acceptance, stamps it on the batch row as `accepted_on` in the
acceptance transaction, and every later read — a replay, a later-day re-acceptance, a payout
return dated by the batch — takes `posting_date = accepted_on` from the stored row, never from the
clock again; the value date is the evidence's own. Because a posting's fingerprint binds its
dates, a date read from the clock at each attempt would make a later-day replay conflict; a date
read once and stored makes it converge (`INV-SET-04`). The Phase 5–7 flows that read their posting
dates from the clock at completion are reconciled with this section by `X-TSK-011`. *(Added at
the Phase 8 exit review, `P8-DOC-001`, 2026-10-01.)*

Posting date and value date become concrete types when the ledger exists (Phase 3). Phase 0
names the distinction and enforces the part that is mechanically enforceable.
