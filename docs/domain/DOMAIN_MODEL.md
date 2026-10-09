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
Version, Policy Evaluation, Decision Consumption and Decision Replay.)*

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
