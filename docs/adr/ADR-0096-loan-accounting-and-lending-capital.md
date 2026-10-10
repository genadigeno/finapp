# ADR-0096 — Loan accounting and lending capital: per-loan ledger accounts with due split from not-due, the posting rules, and a lending-capital equity account recognised from bank evidence that funds every loan

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Lending · Ledger · Reconciliation · Settlement · Identity
Supersedes: nothing. Applies ADR-0002 (the journal is the record), ADR-0040 (a flat account with a
typed classification), ADR-0041 and ADR-0009 (balances derived, the projection never decides),
ADR-0042 (wallet, ledger account and operational account are distinct), ADR-0043 (posting in the
owner's transaction), ADR-0065 and `INV-SET-06` (cash moves only on the bank's statement), ADR-0067
(the completion opens its expectation) and ADR-0071 (`INV-REC-06`, no free adjustment on a
reconciled position). Rests on `PHASE_11_PLAN.md` §2.3 (L1), §2.4 (A17, A28, A29), §7.2, §12.8,
§12.9, §13.1 and `INV-LND-01`, `-08`, `-13`. Delivers the loan accounting `DELIVERY_PLAN.md` §14
anticipated.

## Context

1. **A loan is several claims, not one.** Principal not yet due, principal billed, interest
   earned, interest billed, fees billed and money owed back to the borrower behave differently for
   delinquency, income and payoff. One receivable account per loan would make "how much is past
   due" a calculation over rows instead of a balance.
2. **Three numbers must not compete.** The ledger's balances, lending's operational rows (billings,
   accruals, allocations) and, later, the general ledger. Only one may be authoritative.
3. **A disbursement creates wallet money.** `DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET` raises the
   platform's liabilities to customers without any cash entering. Unless the platform's own money
   backs it, safeguarded customer cash silently funds loans, and any future proof of customer
   liabilities against cash shows the loan book as a shortfall (L1).
4. **Capital must be real.** A capital figure an operator can type is a plug. The platform already
   has exactly one way cash becomes recognised: a bank statement line matched by reconciliation
   (`INV-SET-06`).

## Decision

1. **The account plan** (ledger `V026`, `P11-TSK-003`). A new `OwnerKind.LOAN` (`owner_ref` = the
   loan id), for both products:

   | Purpose | Owner | Type (normal) | Per | Holds |
   |---|---|---|---|---|
   | `LOAN_PRINCIPAL` | LOAN | ASSET (debit) | account | principal outstanding, not yet due (a line: drawn, not billed) |
   | `LOAN_PRINCIPAL_DUE` | LOAN | ASSET | account | billed principal unpaid |
   | `LOAN_INTEREST_ACCRUED` | LOAN | ASSET | account | interest earned, not yet due |
   | `LOAN_INTEREST_DUE` | LOAN | ASSET | account | billed interest unpaid |
   | `LOAN_FEES_DUE` | LOAN | ASSET | account | assessed fees unpaid |
   | `LOAN_CREDIT_BALANCE` | LOAN | LIABILITY (credit) | account | money received beyond every receivable, owed to the borrower |
   | `LOAN_INTEREST_INCOME` | OPERATIONAL | REVENUE | currency | |
   | `LOAN_FEE_INCOME` | OPERATIONAL | REVENUE | currency | |
   | `LOAN_WRITE_OFF_EXPENSE` | OPERATIONAL | EXPENSE | currency | seeded, posted by nothing in Phase 11 |
   | `LENDING_CAPITAL` | OPERATIONAL | EQUITY (credit) | currency | the platform's own funds committed to lending, recognised from bank evidence |

   The six per-loan accounts are opened by `createOrConverge` in the acceptance transaction, in the
   account's currency only. The four operational accounts are seeded per supported currency. All
   ten join `AccountPurpose.closedToFreeAdjustments()` (no `AdjustmentService` line ever touches
   them; the `V015`-lineage binding trigger re-stated); `LENDING_CAPITAL` also joins
   `reconciledPositions()` with its single poster, capital recognition. No `*_CLEARING` purpose is
   added (`INV-RAIL-04`): lending moves no money across a rail itself.

2. **The posting rules.** Every entry: `posting_date = value_date =` the business date of the
   database clock under L3 (rule 2: the accrual date); `reference` = the lending operation's id;
   balanced per currency by construction; posted through `PostingService.post` or
   `ReversalService.reverse` in lending's transaction (rule 0: reconciliation's).

   | # | Economic event | Debit | Credit | Key |
   |---|---|---|---|---|
   | 0 | Capital contributed (bank evidence) | `CASH_AT_BANK` | `LENDING_CAPITAL` | reconciliation's recognition key |
   | 1 | Loan disbursed / line drawn | `LOAN_PRINCIPAL` P | `CUSTOMER_WALLET` P − f; `LOAN_FEE_INCOME` f | `lending.disbursement:<loan>` / `lending.draw:<draw>` |
   | 2 | A day's interest earned | `LOAN_INTEREST_ACCRUED` | `LOAN_INTEREST_INCOME` | `lending.accrual:<loan>:<date>` |
   | 3 | Instalment billed / statement issued | `LOAN_INTEREST_DUE` I; `LOAN_PRINCIPAL_DUE` Pj | `LOAN_INTEREST_ACCRUED` I; `LOAN_PRINCIPAL` Pj | `lending.billing:<instalment>` / `lending.statement:<statement>` |
   | 4 | Late fee assessed | `LOAN_FEES_DUE` | `LOAN_FEE_INCOME` | `lending.fee:<assessment>` |
   | 5 | Repayment from the wallet | `CUSTOMER_WALLET` A | due accounts per allocation; `LOAN_PRINCIPAL` (line paydown); `LOAN_CREDIT_BALANCE` rest | `lending.repayment:<repayment>` |
   | 6 | Credit balance applied | `LOAN_CREDIT_BALANCE` | due accounts per allocation | `lending.credit-application:<billing>` |
   | 7 | Payoff | rules 2, 3, 5, then `LOAN_CREDIT_BALANCE` | … `CUSTOMER_WALLET` (refund) | `lending.payoff:<quote>` (+ the rule keys) |
   | 8 | Fee waived (unpaid) | `LOAN_FEE_INCOME` | `LOAN_FEES_DUE` | `ReversalService` of rule 4's entry, scope `ledger.reverse` |
   | 8a | Paid fee refunded | `LOAN_FEE_INCOME` | `LOAN_CREDIT_BALANCE` | `lending.waiver:<waiver>` |
   | 9 | Interest waived | `LOAN_INTEREST_INCOME` | `LOAN_INTEREST_DUE` / `…ACCRUED` | `lending.waiver:<waiver>` |
   | 10 | Repayment reversed | rule 5's credits | `CUSTOMER_WALLET` | `ReversalService`, `lending.repayment-reversal:<reversal>` |
   | 11 | Disbursement failed | — | — | nothing posted: a failed disbursement rolls back whole |
   | 12 | Payout (path X) | `CUSTOMER_WALLET` | `INSTANT_CLEARING` | payments' own posting, reversal and return (ADR-0097) |
   | 13 | Arrears re-scheduled (amendment) | `LOAN_PRINCIPAL` | `LOAN_PRINCIPAL_DUE` | `lending.amendment:<amendment>` |
   | 14 | Write-off (deferred, Phase 14) | `LOAN_WRITE_OFF_EXPENSE` | every LOAN asset account | — |

   A multi-entry transaction (payoff) pre-locks the union of its accounts through
   `lockBalancesInOrder` before its first post (the Phase 9 rule). The origination fee (A17) is
   recognised upfront in the operational ledger; effective-interest amortisation is Phase 14's.

3. **Three outstanding numbers, one authority.** *Ledger* (authoritative): the six per-account
   balances derived from journal lines. *Operational* (lending's rows): immutable facts that
   **explain** the ledger and must sum to it — `LoanSubledgerProof` (`P11-TSK-029`) per account:
   `LOAN_INTEREST_ACCRUED` = Σ accruals − Σ billed interest − waived from accrued;
   `LOAN_INTEREST_DUE` = Σ billed − allocated − waived; `LOAN_PRINCIPAL_DUE` = Σ billed − allocated
   (± reversals, re-schedules); `LOAN_PRINCIPAL` = Σ disbursed or drawn − Σ billed − Σ paid down;
   `LOAN_FEES_DUE` = Σ assessed − allocated − waived; `LOAN_CREDIT_BALANCE` = Σ overpayments −
   applications − refunds; no due account negative; a `CLOSED` account's six balances zero. A
   difference is an alert, never self-corrected. *GL* (Phase 14): a mapping of these purposes,
   `gl_code` null now. `LendingBooksHaveOnePosterTest`: only lending's operations post to loan
   purposes; only capital recognition posts to `LENDING_CAPITAL`.

4. **Lending capital: EQUITY, recognised only from bank evidence** (`P11-TSK-004`). A treasury
   officer (`LENDING_CAPITAL_ADMINISTER`, role `LENDING_TREASURY_OFFICER`) proposes a contribution
   (amount, currency, bank reference); a second approves it (four-eyes, domain and `CHECK`;
   advisory namespace `11`, `hashtext(currency)`; keyed `lending.capital:EMPLOYEE:<id>`). The
   approval opens a `LENDING_CAPITAL_CONTRIBUTION` expectation through reconciliation's opener in
   the same transaction (reconciliation `V022`, ADR-0067's shape; `UNIQUE (expectation_ref)` on the
   contribution). The contribution's machine is only `PROPOSED → APPROVED | REJECTED`. When the
   platform's corporate transfer appears on the settlement bank's statement, bank recognition —
   still the one poster of `CASH_AT_BANK` — matches the line and posts rule 0 in reconciliation's
   own transaction. **Recognition is a derived condition, not a lending state:** lending reads
   reconciliation's expectation and the ledger line through an `app`-implemented read port
   (reconciliation never calls lending), and the same read shows a contribution whose expectation
   aged out unmatched as unrecognised (lapsed). An unmatched line is a break like any other.
   **Headroom reads the `LENDING_CAPITAL` ledger balance, never the contribution's status.** Capital
   is never adjusted, never posted by a person, and never withdrawn in Phase 11 (a capital return
   is a later treasury task).

5. **The headroom invariant makes "funded from capital" binding** (`INV-LND-13`):

   ```
   headroom(c) = CR−DR(LENDING_CAPITAL, c)
               − Σ committed principal of loans PENDING_DISBURSEMENT in c
               − Σ DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) over every loan and line in c   ≥ 0
   ```

   Judged under **L6**, the currency's `LENDING_CAPITAL` `ledger_account` row `FOR UPDATE`, derived
   from journal lines, at every loan acceptance (the commitment) and every draw; a shortfall refuses
   the act `422 lending.CapitalUnavailable`. Disbursement needs no re-check: its principal was
   committed at acceptance. Repaid principal restores headroom at commit; interest and fee income
   are never capital (A29); an undrawn line limit consumes none (A28). L6 sits after the act's own
   row (L5) and before any wallet row (L7); only lending locks it.

6. **The capital proof and the safeguarding term** (`LendingCapitalProof`, gauged, alerting on any
   failure), per currency in one `REPEATABLE READ` snapshot: (a) every journal line on
   `LENDING_CAPITAL` is a recognised contribution's (completeness — no other poster); (b) headroom
   ≥ 0 at rest; (c) the published **safeguarding adjustment** `lending_funded(c) = Σ principal
   outstanding + committed`, the term by which a future safeguarding proof (Phase 14/15's, comparing
   customer liabilities with cash) subtracts loan-funded wallet money from customer-funded money.
   Customer liabilities − `lending_funded` is unchanged by any lending act except the loans' own
   principal flows and repayments of interest and fees (platform income), each explained by its
   entry.

## Alternatives Considered

### One receivable account per loan
Pros:
- One account to open; simpler entries.

Cons:
- Past-due amounts become a calculation over rows; delinquency, income and the subledger proof
  lose a ledger anchor per component.

Refused: due split from not-due, interest from principal.

### Capital as a platform-owned LIABILITY "lending wallet", debited at disbursement
Pros:
- Disbursement looks like a transfer between two wallets.

Cons:
- In the platform's consolidated books the platform cannot owe itself; the "wallet" would be a
  fiction, and its balance a second capital figure beside the equity it pretends to be.

Refused.

### A four-line disbursement entry debiting `LENDING_CAPITAL`
Pros:
- The funding is visible on the disbursement itself.

Cons:
- Debiting equity records a reduction of the platform's capital that did not happen — the capital
  is still the platform's, now deployed as a receivable. The ledger already shows the funding: the
  contribution put cash beside the capital, and the disbursement turns wallet capacity into a
  receivable.

Refused: the entry stays two-sided; the headroom invariant binds.

### Recording funding as a debt (a lending facility payable)
Pros:
- Models a bank-funded lender.

Cons:
- No facility, lender or interest on funding exists; a recorded debt with no counterparty and no
  evidence is a plug (L1 chose the platform's own capital).

Refused.

### Capital as an operator-entered figure
Pros:
- No reconciliation dependency.

Cons:
- Unbacked capital is unbacked wallet money; `INV-SET-06`'s discipline exists precisely so cash is
  never typed.

Refused: recognised only from bank evidence.

### Map to the GL now
Refused: Phase 14's; `gl_code` stays null.

## Consequences

Positive:
- Every loan figure is a ledger balance; every operational figure is proven against it.
- Wallet money created by lending is always matched by recognised platform capital; the
  safeguarding position stays exact and explainable.

Negative:
- Six ledger accounts per loan; the chart grows with the book.
- The capital row per currency is a hot row: every acceptance and draw in a currency serialise on
  it — correct by construction, measured in the storm, partitioned into tranches by Phase 16 if the
  characterisation demands.
- No acceptance or draw can occur until a treasury officer's contribution is recognised.

Operational impact: `finapp.lending.subledger.proof`, `finapp.lending.capital.proof`,
`finapp.lending.capital.headroom.low`; the capital headroom amount is an audited report
(`GET /v1/operator/reports/lending/capital`), never a metric (ADR-0072).
Security impact: `LENDING_CAPITAL_ADMINISTER` four-eyes; no person posts to a loan or capital
account.
Financial impact: the operational ledger gains the loan book and the platform's lending equity.

## Invariants / Constraints

`INV-LND-01` (a loan's figures are ledger balances), `INV-LND-08` (closed means settled),
`INV-LND-13` (capital recognised only from bank evidence and never over-deployed), `INV-LED-01`…`05`,
`INV-BAL-01`…`05`, `INV-SET-06`, `INV-REC-06`, `INV-RAIL-04`, ADR-0040, ADR-0042, ADR-0065.

## Follow-up

- Built by `P11-TSK-003` (ledger `V026`: `OwnerKind.LOAN`, the ten purposes, the seeded operational
  accounts, the adjustment guard), `P11-TSK-004` (contributions, reconciliation `V022`, the capital
  proof, the L6 lock-wait test), and each posting rule by the task that posts it (`-014`, `-016`,
  `-017`, `-018`, `-020`, `-021`, `-022`, `-025`, `-026`, `-027`); the subledger proof by `-029`.
- **Settled at the transition (2026-10-10):** a repayment reversal (rule 10) re-instates principal
  and credits the wallet — the same shape as a disbursement — so it consumes capital headroom. Found
  while this ADR was written; `PHASE_11_PLAN.md` §7.1, §7.2 and §12.9 now make T-rev take L6 after
  L5 and re-check headroom for the principal it re-instates, refusing `422
  lending.CapitalUnavailable` with the proposal staying `PROPOSED` (`P11-TSK-020`), so no correction
  can breach `INV-LND-13`.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
