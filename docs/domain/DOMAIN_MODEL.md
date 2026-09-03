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

Merchant
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

FX Quote
Exchange Rate
FX Trade

Journal Entry
Journal Line
Chart of Accounts
Suspense Account
Reconciliation Batch
Reconciliation Break

Important: these names are not automatically aggregates or tables. Determine domain ownership and lifecycle before implementation.

Every term above is defined in [`GLOSSARY.md`](GLOSSARY.md), with an explicit statement of what it
is **not** and the module that will own it. The glossary also defines the seven terms
`CLAUDE.md` §Domain Distinctions forbids collapsing but this list never named, and contrasts all
eight of its groups. `DomainGlossaryTest` fails the build when this list and the glossary stop
agreeing, in either direction (`P0-DOC-011`).

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

Posting date and value date become concrete types when the ledger exists (Phase 3). Phase 0
names the distinction and enforces the part that is mechanically enforceable.
