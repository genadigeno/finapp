# ADR-0104 — BNPL funding from lending capital: the shared headroom and credit exposure version 3

Status: Proposed
Date: 2026-10-16
Phase: 12
Context: BNPL · Lending · Credit · Ledger · Merchant
Supersedes: nothing. Extends ADR-0096 §5 and §6 (the headroom invariant, the capital proof and the safeguarding
term) to a second consumer of the same capital, and ADR-0091 (decision consumption and exposure, the versioned
`PlatformCreditExposure`) to a third version. Applies ADR-0041 and ADR-0009 (balances derived, the projection never
decides), ADR-0043 (posting in the owner's transaction) and `INV-SET-06` (capital recognised only from bank evidence).
Rests on `PHASE_12_PLAN.md` §1, §2.2 (B2), §3.2, §3.3, §6 (`INV-BNPL-02`, `-10`), §7.1 (T-pur, T-rev), §7.2, §7.3
(b), (f), §7.4, §12.4, §12.9, §12.10, §13.4, §21 and §23 (#4, #21), and on `INV-LND-06`, `INV-LND-13`, `INV-CRD-09`.

## Context

1. **A BNPL purchase deploys platform money without cash entering.** The financing entry (F1, ADR-0103) debits the
   obligation's `BNPL_PRINCIPAL` by the gross G and credits the merchant's `MERCHANT_PAYABLE` by G − f: a liability
   to the merchant, payable on demand by `merchant`'s payout, created in a commit that receives no cash. It is the
   disbursement's shape (ADR-0096 Context 3) with a merchant in the wallet's place. Unless recognised platform money
   backs it, safeguarded customer and merchant money silently funds the receivable — and the merchant's payout of it
   is real cash leaving the bank.
2. **Owner decision B2** (2026-10-16): the merchant is paid upfront, net of fee, the platform carries the credit risk,
   and the receivable is **funded from `LENDING_CAPITAL` under L6 headroom**. There is one capital pool per currency;
   it is lending's (ADR-0096 §4), recognised only from bank evidence, and every loan acceptance, draw and
   principal-re-instating reversal already judges it under its row lock (`INV-LND-13`).
3. **`LendingCapitalHeadroom` already sums by ledger purpose.** Its outstanding term is one `PurposeBalances`
   statement over `{LOAN_PRINCIPAL, LOAN_PRINCIPAL_DUE}` across every account of the currency — not over lending's
   tables. The BNPL receivable lives in purposes of its own (`BNPL_PRINCIPAL`, `BNPL_PRINCIPAL_DUE`, ledger `V027`,
   OQ-11 — ADR-0103), so the headroom does not see it today.
4. **A BNPL purchase is credit, and the next decision must see it.** Credit's `PlatformCreditExposure` v2 (`app`'s
   `LendingCreditExposure`, lending `V007`'s view) counts a party's reservations (credit's approved, unconsumed
   decisions) and lending's committed and outstanding principal. A BNPL decision consumed by an agreement stops being
   a reservation in the same commit that creates the receivable; under v2 nothing would count it — an exposure gap
   that `INV-LND-06`'s no-gap rule exists to forbid.
5. **BNPL has no committed-but-undisbursed principal.** Lending counts the agreement principal of loans
   `PENDING_DISBURSEMENT` because acceptance and disbursement are two commits. A BNPL acceptance, the purchase, the
   financing and the obligation are one commit (T-pur, `INV-BNPL-02`, plan §12.4): from the commit there is only
   outstanding principal, and before it there is nothing.
6. **Two rows become shared at BNPL volume.** The currency's `LENDING_CAPITAL` `ledger_account` row (L6) is now taken
   by every BNPL purchase beside every loan acceptance and draw; and the currency-wide `BNPL_MERCHANT_FEE_INCOME`
   balance row is posted by every purchase and every refund. Phase 11 recorded both kinds of row as debts it did not
   measure (ADR-0096 §Consequences; the gate's A2-F1).

## Decision

1. **One pool, one formula.** No BNPL capital account and no BNPL headroom of its own. The headroom of currency c is
   (plan §12.9):

   ```
   headroom(c) = CR−DR(LENDING_CAPITAL, c)
               − Σ committed principal of loans PENDING_DISBURSEMENT in c              (lending)
               − Σ DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) over every loan and line in c (lending)
               − Σ DR−CR(BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE) over every obligation in c    (bnpl)     ≥ 0
   ```

   The BNPL term has no committed arm (Context 5). The figure consumed by a purchase is the gross G — the receivable,
   not the cash the merchant will be paid (G − f) — exactly as a loan consumes its principal P although its borrower
   receives P − f (ADR-0096 rule 1): the bound is on what the platform is owed and must fund, and counting the gross
   errs low.

2. **Lending sums the BNPL purposes; lending learns no bnpl type** (`P12-TSK-005`). `LendingCapitalHeadroom`'s
   principal set widens from `{LOAN_PRINCIPAL, LOAN_PRINCIPAL_DUE}` to those two plus `BNPL_PRINCIPAL` and
   `BNPL_PRINCIPAL_DUE` — ledger `AccountPurpose` members, read through the same one-statement `PurposeBalances` read,
   so a BNPL billing moving principal from not-due to due (F2) is wholly in or wholly out. The committed term is read
   first, as today (`P11-TSK-012`'s ordering rule, unchanged). `underLock`, `atRest`, `Headroom.lendingFunded()` and
   `LendingCapitalProof` all read the widened set. *(As built by `P12-TSK-005`: the set is
   `LendingCapitalHeadroom.PRINCIPAL`; the `Headroom` record keeps its shape - one `outstanding` term for loan, line and
   BNPL principal alike, so `lendingFunded()` and the proof's headroom arm widen with it and the proof keeps no second
   copy of the formula; the operator capital report's `outstandingMinor` is that one figure. `BnplBooksHaveOnePosterTest`
   gains its first reader permit, the headroom, held to name exactly `BNPL_PRINCIPAL` and `BNPL_PRINCIPAL_DUE`.)* Lending gains no migration (the headroom is Java; lending's next free
   stays `V031`), no edge, and no knowledge of a purchase, a merchant or an obligation: `lending/package-info.java`'s
   rule ("bnpl will reference lending's concepts by identifier and own neither; lending never knows a merchant") holds.

3. **bnpl judges headroom through an `app` port** (`BnplCapital.underLock(uow, currency)`, declared by `bnpl`,
   implemented in `app` over `LendingCapitalHeadroom.underLock` — the crossborder/lending port precedent). `bnpl`'s
   build edges stay `creditmath, ledger, platform, sharedkernel` (ADR-0101); `LendingModuleIsolationTest`'s "no module
   depends on lending" holds. The port runs on bnpl's own connection, inside bnpl's transaction, and returns
   bnpl's own `BnplHeadroom` record; bnpl asks `admits(G)` and refuses a shortfall `422 bnpl.CapitalUnavailable` with
   nothing written — the offer stays `OFFERED` until it expires, the decision unconsumed (the consumption rolls back
   with the rest). *(As built by `P12-TSK-005` (2026-10-17): this read "returns the `Headroom` record" - lending's type,
   which bnpl cannot see without the edge this decision refuses. `BnplHeadroom(currency, headroom)` carries the one
   figure a judgement needs - not the capital, committed or outstanding terms - with `admits(G)` (a positive gross in the
   headroom's own currency) and no amount in its string form; `app`'s `LendingBnplCapital` builds it from
   `LendingCapitalHeadroom.underLock` and never from `atRest`. The refusal code is `-013`'s.)*

4. **Where L6 is judged, and why nowhere else.** Every act that **raises** BNPL principal holds L6 to its commit:
   - **T-pur** (the acceptance = the purchase): L0 → B2 offer → B3 session (judged at once) → consume → **L6** →
     the rows born → (P) → L8 → F1 (plan §7.1, §7.2). L6 sits after every bnpl row and before any wallet or payable row.
   - **T-rev** (a four-eyes repayment reversal re-instating principal, `P12-TSK-024`): L0 → B4 → B6 proposal →
     **L6** → (P) → L8; a shortfall refuses the approval with the proposal staying `PROPOSED` (ADR-0096's Follow-up
     rule for lending's reversal, applied unchanged).

   Every other BNPL act lowers or reclassifies principal and takes no L6: a refund (T-ref), a repayment or collection,
   a payoff, a billing (principal → principal due, same sum), a dispute outcome (a refund, or an uphold that re-bills
   nothing, ADR-0106). Under `READ COMMITTED` such an act committing during another's derivation can only make the
   outstanding term read high and the headroom low — a refusal at worst, never an over-deployment — and no act can raise
   the term without first waiting on the row the reader holds. Lending's own consumers are unchanged and now see the
   BNPL principal.

5. **One lock order across both modules** (plan §7.3 (f)). L6 is taken by lending's acceptance, draw and reversal and
   by bnpl's acceptance and reversal, each after its own module's rows and before any wallet or payable row (lending's
   L6 → L7; bnpl's L6 → (P)). No lending transaction holds a bnpl row; no bnpl transaction holds a lending row other than
   L6; bank recognition's foreign-key share on the capital row stays a one-way wait. The seeded capital accounts' ids
   sort first in any multi-account lock (ADR-0096 §5's note). The cycle argument is the existing one with a second
   module on the same side of the row.

6. **Income is not capital** (lending A29, kept by the transition — `PHASE_11_REVIEW.md` agenda #4 stays open).
   `BNPL_MERCHANT_FEE_INCOME`, `BNPL_INTEREST_INCOME` and `BNPL_FEE_INCOME` are never counted in headroom; a merchant
   fee returned on a refund lowers income, never capital; capital is still credited only by bank recognition (rule 0,
   `LendingBooksHaveOnePosterTest`'s capital rule unchanged — no bnpl operation posts `LENDING_CAPITAL`, and
   `BnplBooksHaveOnePosterTest` holds the mirror for the BNPL purposes).

7. **The capital proof and the safeguarding term.** `LendingCapitalProof` (gauged `finapp.lending.capital.proof`,
   alerting) gains the BNPL term in (b), headroom ≥ 0 at rest; (a), completeness of `LENDING_CAPITAL`'s lines, is
   unchanged. The published safeguarding adjustment becomes `lending_funded(c) = Σ committed + Σ principal outstanding
   of loans, lines and BNPL obligations`. BNPL creates no wallet money at purchase but creates merchant-payable
   liabilities without cash entering; a future safeguarding proof (Phase 14/15's) subtracts the capital-funded part
   of the platform's liabilities to customers and merchants by this one term, each movement explained by its entry.
   `finapp.lending.capital.headroom.low` is shared; the headroom amount stays an audited report, never a series
   (ADR-0072).

8. **Platform credit exposure version 3** (`P12-TSK-012`, in `app`; plan §12.10). Per party and currency:

   ```
   exposure_v3(party, c) = exposure_v2(party, c)
                         + Σ DR−CR(BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE) over the party's obligations in c not CLOSED
   ```

   Principal only (lending A3: no interest, fees or credit balance); no committed term (Context 5). It is read in
   **one statement** over lending's definer view, bnpl's definer view (`bnpl V007`: obligation → party, currency,
   status, account ids; no money column, `INV-BNPL-01`) and the journal lines — the v2 precedent, one snapshot, credit
   reading no bnpl table and depending on no bnpl type (no module depends on `bnpl`). Credit keeps the judgement.
   - **No gap:** T-pur takes L0 first and commits the consumption (reservation ends) and F1 (outstanding begins)
     together; credit's deciding transaction takes L0 and reads v3, so it sees the reservation or the outstanding,
     never neither and never both. T-rev, the only act that raises a party's BNPL outstanding afterwards, takes L0 first.
   - **Versions replay identically:** a decision's snapshot records the exposure figure with the implementation's
     `version()` as provenance (`INV-CRD-01`); replay reads the recorded figure, so every v1 and v2 decision replays
     `IDENTICAL` after v3 ships, and the version recorded says which composition produced it.
   - **v3 before the first BNPL consumption.** A consumed BNPL decision is invisible to v2, so `-012` and `-013` are
     built on one branch and merged together (the Phase 11 `-012`/`-013` precedent); nothing can call
     `consume(BNPL_AGREEMENT, …)` before v3 is the composed version.
   - **The census** (`ExposureCensus`) gains BNPL obligations as items at their exposure figure, each carrying its
     decision's policy maximum. Its order-free rule still holds: an obligation's principal is G at its commit and only
     falls afterwards, a reversal re-instating at most what was repaid — never above the figure its decision judged.
   - The merchant never sees exposure, reasons or amounts (plan §11; OQ-12).

9. **The hot rows are designed against, then measured** (plan §21, agenda #21). No currency-wide income row is ever
   taken before (P) (plan §7.2): the PN accrual commits alone (T-acc), and every other income posting is at L8, after
   the wallet and the payable — so neither L6 nor an income row is held across a wallet wait (A2-F1 designed out, not
   inherited). L6 is held from the headroom judgement to the commit, across only bnpl's inserts, (P) and the postings.
   `P12-TST-001` **measures** lock waits and latency on the capital row and on `BNPL_MERCHANT_FEE_INCOME` under the
   storm's mixed lending and BNPL load — not only proves correctness — and records the figures against the two debt
   rows. Capital tranches and batched or per-obligation income recognition are Phase 16's, if the measurement demands.

## Alternatives Considered

### A separate BNPL capital pool (`BNPL_CAPITAL`)
Pros: BNPL's capacity independent of lending's; no shared hot row.
Cons: contrary to B2; two pools double the contribution, recognition and proof machinery and need a treasury
allocation rule nobody has decided; `PHASE_12_PLAN.md` §20 excludes "a separate capital pool". Refused.

### BNPL funded by the merchant (pay the merchant as the customer pays)
Pros: no platform capital; no merchant credit risk.
Cons: contrary to B2 (the merchant paid upfront); turns BNPL into a merchant-side instalment collection. Refused.

### A build edge `bnpl → lending` calling `LendingCapitalHeadroom` directly
Pros: one call, no port.
Cons: breaks the "no module depends on lending" rule `LendingModuleIsolationTest` pins and the reason ADR-0101 extracted
`creditmath` (bnpl can never reach a lending store). The `app` port costs one interface. Refused.

### Lending reads bnpl's obligations (or learns a bnpl type) to sum BNPL principal
Pros: an explicit list of obligations in lending's code.
Cons: lending would depend on bnpl (a cycle once bnpl reads capital), or read another module's schema. The ledger
purpose is already the shared vocabulary, and the headroom already sums by purpose. Refused.

### Reuse `LOAN_*` purposes under a new owner kind so the headroom counts BNPL for free
Pros: no change to lending at all.
Cons: makes `LendingBooksHaveOnePosterTest`'s "only lending posts loan purposes" false and blurs two receivables'
explanations (OQ-11, ADR-0103). Refused.

### Count G − f (the merchant's net) rather than G
Pros: the cash the platform actually pays out.
Cons: the receivable is G and is what the platform must recover; lending counts P gross of its origination fee; the
lower figure errs high. Refused.

### Exposure v3 as two reads (lending's, then bnpl's)
Pros: no change to v2's statement.
Cons: two snapshots; the census and the decision would read figures no single instant held. The v2 precedent is one
statement. Refused.

### Recycle BNPL fee income into capital
Refused: A29 kept; capital return and income recycling remain the open treasury question (agenda #4).

## Consequences

Positive:
- Every BNPL receivable is backed by recognised platform capital from its own commit; one proof covers loans, lines
  and BNPL; the safeguarding term stays one figure.
- A BNPL purchase is visible to the next credit decision from the instant it exists, with no gap and no double count.
- Lending's code changes by one set of purposes; lending's races, battery and golden hashes re-run unchanged.

Negative:
- BNPL and lending compete for one capital figure: a busy BNPL book can refuse a loan, and the reverse
  (`422 lending.CapitalUnavailable` / `422 bnpl.CapitalUnavailable`). Allocation between them is a treasury decision
  not taken in Phase 12.
- The capital row and the merchant-fee income row are throughput ceilings per currency until measured and, if needed,
  partitioned (Phase 16).
- A repudiated capital contribution (ADR-0096's Follow-up, `P11-TSK-012`) can now leave BNPL purchases as well as loans
  over-deployed; the proof reports it and every later BNPL purchase and principal-re-instating reversal is refused
  until capital is recognised again.

Operational impact: the shared capital gauges and alert; the audited capital report gains the BNPL term; the storm's
lock-wait figures recorded against the debt rows.
Security impact: none new; the merchant never sees capital or exposure.
Financial impact: `LENDING_CAPITAL` now funds a second receivable book; no new account.

## Invariants / Constraints

`INV-BNPL-10` (BNPL principal counted in capital headroom and in credit exposure from the purchase's commit, with no
gap), `INV-BNPL-02` (financing and obligation atomic — the reason no committed term exists), `INV-LND-13` (capital
recognised only from bank evidence and never over-deployed — its statement extended to BNPL obligations, judged at
every BNPL purchase and every principal-re-instating BNPL reversal), `INV-LND-06` (one decision, no exposure gap — the
rule extended to `BNPL_AGREEMENT`), `INV-CRD-01`, `INV-CRD-09`, `INV-SET-06`, `INV-BAL-05`, ADR-0096, ADR-0091.

## Follow-up

- `P12-TSK-005` — the widened principal set in `LendingCapitalHeadroom` and `LendingCapitalProof`, `lending_funded`
  widened, the `BnplCapital` port in `app`; lending's acceptance, draw and reversal races re-run unchanged; a planted
  BNPL principal line lowers headroom and refuses the last loan acceptance.
- `P12-TSK-012` with `P12-TSK-013` (one branch, one merge) — exposure v3, bnpl `V007`'s definer view, the census, v1/v2
  replay `IDENTICAL`; `-013` the first BNPL consumer: purchases racing loans and draws for the last capital, one
  admitted, the L6 lock-wait asserted (the Phase 11 M3 lesson); the probe "skip L6" letting two purchases over-deploy
  (`MUTATION_TESTING.md` §2).
  *(As built by the pair (2026-10-18): `bnpl V007`'s `bnpl.party_principal_exposure` (per obligation, no figure, no ledger
  table named); `app`'s `BnplCreditExposure` (version 3) wired into credit's freezer - one statement, lending's arm `UNION
  ALL` bnpl's, each over its own principal purposes bound from its module's declaration; an obligation counted whatever its
  status, a negative principal zero, no principal line refused; the census's BNPL statement. `-013`'s T-pur judges
  headroom ≥ the gross under L6 after the consumption (`422 bnpl.CapitalUnavailable`). The race against a LOAN acceptance
  for the last capital is `-005`'s `BnplCapitalDatabaseTest` (unchanged); `-013`'s suite races two BNPL acceptances for
  it, the L6 lock-wait asserted. Database suites written, owed a first run - the owner's instruction for the pair.)*
- `P12-TSK-024` — T-rev's L6 re-check and its refusal leaving the proposal `PROPOSED`.
- `P12-TST-001` — the capital proof and the census at every storm round with capital contested across lending and BNPL;
  the lock-wait measurement of Decision 9, recorded in `CURRENT_STATE.md` §Known Architectural Debt.
- `FINANCIAL_INVARIANTS.md` — `INV-LND-13`'s statement and Verify extended to BNPL as above (the transition); the
  `DISTRIBUTED_EXECUTION.md` §3 register's L6 row gains bnpl's two holders.
- **A write-off would raise headroom without returning capital** (found by `P12-TSK-005`'s design, 2026-10-17; for
  loans and BNPL alike). The headroom subtracts principal outstanding; a write-off (F11 here, lending's rule 14) credits
  the principal against an expense, so it would lower the outstanding term and raise headroom by the amount lost - the
  capital that funded the receivable is gone, yet the headroom would offer it again. Nothing posts a write-off in Phase 11
  or 12 (`LOAN_WRITE_OFF_EXPENSE` and `BNPL_WRITE_OFF_EXPENSE` seeded, carrying no line), so no figure is wrong today;
  Phase 14's accounting design must charge the loss against capital (or subtract the write-off expense in the headroom)
  in the same commit. Recorded beside the write-off debt row in `CURRENT_STATE.md` §Known Architectural Debt.
- **Acceptance.** `Proposed` at the Phase 11 → 12 transition (2026-10-16); read against the code and accepted by the
  Phase 12 exit review (`P12-DOC-001`).
