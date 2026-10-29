# Capability Map

## Customer and Identity
- Customer / Party
- Identity
- Authentication
- Authorization
- MFA / Passkeys
- Device and session management
- Account recovery

## Trust and Compliance
- KYC
- KYB
- Beneficial ownership
- Sanctions
- PEP / adverse media
- Consent
- AML transaction monitoring
- Case management

## Money and Accounts
- Accounts
- Wallets
- Balances / holds
- Transfers
- Statements
- Ledger
- Accounting GL

## Payments
- Payment intents
- Payment attempts
- Payment methods
- Gateway / processor adapters
- Authorization / capture
- Refunds
- Disputes / chargebacks
- Webhooks
- Checkout
- Merchant settlements

## Rails
- Cards
- Wallets
- A2A
- Instant-payment abstraction

## FX and Cross-Border *(Phase 9, delivered 2026-10-07 — `P9-DOC-001`)*
- FX conversion (the platform as principal) — delivered: reference rates and a four-eyes
  pricing policy; the server-authoritative, single-use quote that is a frozen posting plan;
  multi-currency wallets and wallet conversion booked locally through `FX_POSITION`; the
  back-to-back provider cover, exactly once however the provider answers, and its unwind; the
  four-eyes operator FX trade reversal; FX explained to cash per currency, never by conversion
- Cross-border payments (corridors, beneficiaries, screening) — delivered: the corridor rail and
  a four-eyes corridor policy; beneficiaries held by provider reference with a payee check, and
  kyc's counterparty screening before pricing; disclosed offers, a hold at authorization and one
  entry at the corridor provider's acceptance; returns, cancellation by recall, and the
  corridor's settlement to cash; a second FX and corridor provider each

*(Marked "planned" by the Phase 8 → 9 transition, 2026-10-02; marked delivered by the Phase 9
exit review. Earlier phases' capabilities were never annotated here — `CURRENT_STATE.md` and
`history/` are their record.)*

## Credit *(Phase 10, delivered 2026-10-09 — `P10-DOC-001`)*
- Credit profile — delivered: one born-once row per party, holding no figure, the row every
  deciding transaction for the party serialises on
- Credit bureau integration — delivered: bureau and financial-data collection behind
  provider-neutral ports, under consent re-read at every step, raw evidence encrypted with a
  stored retention (its purge Phase 15's); two simulated bureaus (`bureau-sim-a`, `bureau-sim-b`)
  with source selection and a simulated financial-data provider, each through the port's contract
  suite. **No real bureau is connected**: production composes the fail-safe sources and refuses a
  named provider until unresolved questions #13 and #14 are answered
- Affordability assessment — delivered: exact single-currency arithmetic (the annuity at scale
  10, rounded once), a foreign-currency source partial data, never converted
- Exposure — delivered: the party's bureau balance, the platform's outstanding credit (since
  Phase 12 `PlatformCreditExposure` version 3, `P12-TSK-012`: every loan's committed and
  outstanding principal, every open credit line's limit and every BNPL obligation's principal not
  yet due and billed, one statement; decisions frozen under versions 1 and 2 replay from their own
  snapshots), the reserved exposure of current approvals and the request, judged under the party's
  profile lock; a policy that does not bound its own limit refused at proposal *(read "the
  platform's outstanding credit (zero until Phase 11)" until the Phase 11 exit review,
  `P11-DOC-001`, 2026-10-15, and "(since Phase 11 `PlatformCreditExposure` version 2,
  `P11-TSK-013`: every loan's committed and outstanding principal and every open credit line's
  limit, one statement)" until the Phase 12 exit review, `P12-DOC-001`, 2026-10-22)*
- Decisioning — delivered: a versioned scorecard and a versioned policy of rules as data, both
  four-eyes, through a deterministic engine with its own version; a keyed asynchronous decision
  request decided once from a sealed snapshot, immutable with ordered reason codes; the customer's
  adverse-action explanation, the operator's audited explanation, evidence read and replay, and a
  proof that replays every decision `IDENTICAL`
- Underwriting (manual review) — delivered: a referral opens one case, taken by one underwriter,
  decided with reasons, never overriding a hard decline, a second underwriter above the product's
  threshold who may refuse it back, and a case that closes with its request
- **Production constraint** *(the Phase 10 → 11 transition, 2026-10-10)* — confirmed after repair:
  every submission requires a `MULTI_FACTOR` session, an underwriter never decides their own
  party's case, a decision rests on its own request's snapshot, nothing is decided on stale data,
  and **a person may not approve on an absent bureau balance** — so, with production composing only
  the fail-safe sources, **production approves no credit until a real bureau is connected**
  (unresolved #13, #14)
- BNPL *(Phase 12 — see the BNPL section below)*: credit decides the two BNPL products
  (`BNPL_PAY_IN_4`, `BNPL_PAY_IN_N`) on a request from the BNPL origin, consumed by a
  `BNPL_AGREEMENT` (credit `V022`, `P12-TSK-001`)

*(The Credit section's Phase 10 capabilities were marked "planned" by the Phase 9 → 10 transition,
2026-10-07 (`PHASE_10_PLAN.md`; ADR-0084…0089, then `Proposed`), marked delivered by the Phase
10 exit review, `P10-DOC-001` (ADR-0084…0089 `Accepted`), and confirmed after repair by the Phase
10 → 11 transition, 2026-10-10 (`reviews/PHASE_10_TO_11_TRANSITION.md`), which also moved loan
servicing and delinquency to the Lending section below. The risk score is the Risk section's,
Phase 13: credit consumes a risk signal through a seam and computes none.)*

## Lending *(Phase 11, delivered 2026-10-15 — `P11-DOC-001`)*
- Loan application, offer and acceptance — delivered: one customer act opens credit's decision
  request; an approved decision becomes an expiring offer pinned to the four-eyes terms version
  active at the offering instant, its canonical terms sealed by a hash; the acceptance (a
  `MULTI_FACTOR` or stronger session, the hash echoed) commits an immutable, versioned agreement and
  its evidence, takes the approval up through credit's consumption port and commits the exposure
  and, for a loan, the capital, atomically under the party's profile lock
- Personal loan servicing — delivered: a deterministic amortisation schedule, ACT/365F simple daily
  interest accrued once per date and billed from actual accrual, repayment from the wallet and
  scheduled auto-collection, allocation oldest-due first (fees, interest, principal; any excess held
  as a credit balance), payoff quote and execution, and partial prepayment with recalculation (the
  same instalment over a shorter term)
- Revolving credit line — delivered: draws to the wallet against the approved limit, revolving
  interest, a monthly statement and minimum payment, the available limit derived from postings
  under the line's lock, closure through `CLOSING`; exposure counts the committed limit
- Disbursement — delivered: to the borrower's wallet, or on to an external bank account through
  payments' system-actor withdrawal on the borrower's recorded instruction, its ambiguity held by
  payments and concluded only by inquiry; interest starts at the payout's terminal outcome
- Lending capital — delivered: every loan and draw funded from the platform's own capital, a
  four-eyes contribution recognised only from bank evidence and never over-deployed, so loan-funded
  wallet money is platform-funded, proven per currency by the capital proof; since Phase 12 one pool
  with BNPL - the headroom, the capital proof and the capital report count BNPL principal too
  (`P12-TSK-005`, ADR-0104) *(added by the Phase 12 exit review, `P12-DOC-001`, 2026-10-22)*
- Delinquency and default — delivered: days past due derived, buckets, default at 90 days past due,
  late fees as configured and capped, waivers and repayment reversals four-eyes, restructuring by an
  amendment the customer accepts
- Loan accounting — delivered: per-loan ledger accounts, due split from not-due, every figure a
  ledger balance, proven by the subledger and replay proofs, explained per account to an
  investigator, and reported (portfolio, delinquency, accrual, capital)
- **Production constraint** — production originates nothing: no terms version is `ACTIVE` in
  production, so the application door answers `422 lending.ProductNotOffered` until a real bureau is
  connected (unresolved #13, #14) and `OPERATIONS_RUNBOOK.md` §7.4's activation gate is passed;
  Phase 11 proves origination end to end against the simulators
- Collections operations *(later phases — Phase 13's case management consumes lending's facts; as built by
  `P13-TSK-025`, a thin slice: a loan's or BNPL agreement's default opens its party's `COLLECTIONS` case, and contact
  strategy, promises to pay, placement and write-off stay out, C3 - P13-DOC-001, 2026-10-27)*;
  refinance *(not Phase 12 — decided by the Phase 11 → 12 transition; unowned)*; write-off and provisioning *(Phase 14)*

*(Marked "planned" by the Phase 10 → 11 transition, 2026-10-10 (`PHASE_11_PLAN.md`; ADR-0090…0100,
then `Proposed`), and marked delivered by the Phase 11 exit review, `P11-DOC-001`, 2026-10-15
(ADR-0090…0100 `Accepted`) - each capability built by `P11-TSK-001`…`-036` and proven by
`P11-TST-001` (the lending storm) and `P11-TST-002` (the battery). This note read "to be marked
delivered by the Phase 11 exit review" until then. **Confirmed after repair by the Phase 11 → 12 transition's
completion gate, 2026-10-16** (`reviews/PHASE_11_TO_12_TRANSITION.md`): interest on the right base after a clock
step, held credit settling past-due items, a refunded fee never forgiven twice, self-approval refusals on the
record, no admitted terms version that cannot be scheduled - lending `V029`, `V030`.)*

## BNPL *(Phase 12, delivered 2026-10-22 — `P12-DOC-001`; confirmed after repair by the Phase 12 → 13 transition gate, 2026-10-22)*
- BNPL at checkout — delivered: the customer reads the products the session's merchant offers for its gross, each plan
  illustrated (`POST /v1/checkout/sessions/bnpl-options`, the session's token - no eligibility, credit not asked), then
  applies inside the checkout session (a `MULTI_FACTOR` session) - one credit decision per purchase for exactly the
  session's gross, no spending limit; an approved decision becomes an expiring offer (at most 30 minutes, never beyond the
  decision or the session) pinned to the four-eyes terms version in force, its canonical terms sealed by a hash the
  acceptance echoes; the acceptance completes the session `OPEN → COMPLETED` by financing, intent-less (checkout `V004`),
  so a financed session is as paid as a captured one; the merchant reads its own purchase over its API key and never the
  customer's credit *(the plan's "the merchant sees only eligible, not eligible or pending" was not built: the merchant
  sees the completed session and its purchase, nothing before)*
- Pay-in-4 — delivered: four interest-free instalments, the first collected from the wallet at purchase, then every
  fourteen days; the remainder of the one division (at most three minor units) on the first, so the three open
  instalments are equal (`BNPL_SCHEDULE_ENGINE_V1`); late fees per the pinned terms, capped per agreement
- Pay-in-N — delivered: 3, 6 or 12 monthly instalments with interest, ACT/365F simple daily interest accrued once per
  obligation and date, on lending's engines moved unchanged into the pure `creditmath` module (every Phase 11 figure
  replaying identically)
- Merchant enablement and pricing — delivered: a merchant BNPL-enabled under four eyes (`MERCHANT_ADMINISTER`, merchant
  `V010`), each product priced by a BNPL fee schedule whose versions return their fee on refund (`RETURNED`), the fee
  pinned per purchase; a suspended, closed, unenabled or unpriced merchant is not offered BNPL
- Merchant financing — delivered: the purchase, the merchant's payable credited the gross less the merchant discount,
  the customer's obligation, agreement and schedule version 1, credit's decision consumed and lending's capital headroom
  judged (`422 bnpl.CapitalUnavailable`), and pay-in-4's first instalment collected, in one transaction in the Phase 12
  lock order; the merchant paid through its existing payout, its payable drill-down naming the BNPL terms
- Customer repayment — delivered: repayment and scheduled auto-collection from the wallet only, allocated by the pinned
  order over billed items oldest first, any excess held as a credit balance; early payoff, a quote and its execution, for
  both products; days past due derived, buckets, default at the pinned threshold (90 days), late fees; repayment
  reversals and fee waivers four-eyes (`BNPL_SERVICE`, `BNPL_SERVICE_APPROVE`), never on an operator's own party's
  agreement
- Refunds and disputes — delivered: refunds by the merchant over a key carrying the `BNPL_REFUND` scope, or by an operator,
  cancelling the latest instalments first, the excess to the customer's wallet or credit balance, the merchant fee
  pro-rated back; a refund never exceeds what remains of its purchase, and one after the payout leaves the payable below
  zero by at most the purchase's own net, recovered from later captures and financings before any payout (B7, the amended
  `INV-MER-07`); the customer's dispute holds its instalments (no collection, days past due or late fee), the merchant
  accepts or contests before its deadline - its silence is acceptance, concluded by the deadline sweep - and an operator
  resolves a contested one, four-eyes above the pinned threshold, all under the one refund bound
- BNPL accounting and reconciliation — delivered: six ledger accounts per obligation (ledger `V027`), every figure a
  journal balance, the receivable funded from lending capital under the shared headroom, credit exposure version 3
  counting BNPL principal; the three-way proof per currency (merchant leg, customer leg, economic identity, trial balance,
  capital, exposure), the subledger proof and the replay by the record's own order (bnpl `V017`); each agreement explained
  and replayed to an investigator (`BNPL_INVESTIGATE`), and reported - portfolio, merchant financing, refunds,
  delinquency - with the alerts and procedures of `OPERATIONS_RUNBOOK.md` §8
- **Production constraint** — production originates nothing: no BNPL terms version is `ACTIVE` in production and no
  production credit decision can be `APPROVED` (inherited, unresolved #13, #14), so the application door answers
  `422 bnpl.MerchantNotEnabled` or `422 bnpl.ProductNotOffered` until `OPERATIONS_RUNBOOK.md` §8.1's activation gate is
  passed - lending's preconditions, legal review of the merchant agreement and of the consumer terms, the merchant-fee
  schedule approved, a four-eyes terms activation - none of which is met; Phase 12 proves BNPL end to end against the
  simulators

*(Marked "planned" by the Phase 11 → 12 transition, 2026-10-16 (`PHASE_12_PLAN.md`; ADR-0101…0106, `Proposed`), and
marked delivered by the Phase 12 exit review, `P12-DOC-001`, 2026-10-22 - each capability built by `P12-TSK-001`…`-028`
and proven by `P12-TST-001` (the BNPL storm) and `P12-TST-002` (the battery); ADR-0101…0106 `Accepted` by the review
(2026-10-22, `P12-DOC-001`, each read against the code and corrected first) *(P12-DOC-001, 2026-10-22: this read "stay
`Proposed` until the review's verdict" until the ADRs were accepted)*. This note read "to be marked delivered by the Phase 12 exit review, `P12-DOC-001`" until then. **Confirmed after repair by the Phase 12 → 13 transition's completion gate, 2026-10-22** (`reviews/PHASE_12_TO_13_TRANSITION.md`): a refund-scoped merchant key needs `PAYMENT_REFUND` beside `MERCHANT_ADMINISTER` and is refused on a purchase of its issuer's own party, and a dispute ACCEPT needs the `BNPL_REFUND` scope; a withdrawn dispute never moves the days-past-due clock and starts a 30-day cooldown on its purchase; contested disputes are announced, gauged, alerted after seven days and queued for operators (`GET /v1/operator/bnpl-disputes`); a dispute refund that cannot post is alerted and a wallet cannot close beneath an open dispute; the terms pin is never "neither" during an activation; a repayment reversal is never valued before its repayment and releases a late fee a refund released; the BNPL fee is pinned on the database clock; merchant BNPL reasons are screened for card and account numbers; the delinquency bounds are the ones the labels name - bnpl `V018`, `V019`, merchant `V012`, lending `V031`.)*

## Risk *(Phase 13, built 2026-10-22…2026-10-26 — `P13-TSK-001`…`-025`, `P13-TST-002`; COMPLETE pending the exit review's verdict, `P13-DOC-001`, and the 13 → 14 transition; the storm, `P13-TST-001`, POSTPONED — PI-004)*
- Risk evaluation at the seams — built: every money-moving command judged inside its owner's transaction through the
  seams Phases 4, 9 and 10 reserved (`TransferLimitCheck`, `TransferRiskDecision`, `CrossBorderLimitCheck`,
  `CrossBorderRiskDecision`, `CreditRiskSignal` version 2) and the two added - payments' `WithdrawalRiskDecision` (the
  wallet withdrawal, with a `HELD` state, payments `V033`) and `PaymentRiskDecision` (the checkout card and wallet
  payment confirmation, payments `V034`); one channel-neutral `risk` capability that owns the judgement and none of the
  money; a decision `ALLOW | CHALLENGE | DENY | HOLD | REVIEW` with reason codes, each admitted only where its operation
  can honour it (`HOLD` for the withdrawal and the card payment - its capture deferred, risk `V008`; `REVIEW` for the
  transfer, the withdrawal and cross-border); the fail-safe a recorded decision per operation and value band, never a
  silent default - and, when the pin itself cannot be read, the compiled `ALLOW` (`RISK_UNAVAILABLE`) for every
  operation (owner item 75); an override of a `DENY`, `HOLD` or `REVIEW` under four eyes at or above the pinned
  threshold or for any `DENY` (C7), a held movement released or refused through payments' keyed doors, a refusal
  recorded for its resubmission
- Policy versioning — built: rules as data of closed kinds, weights, score bands and fail-safe bands per operation (and
  rating bands for `MONITORING`), versioned, four-eyes activated, one `ACTIVE` per operation, the pin never "neither"
  during an activation; a deterministic engine (`RISK_ENGINE_V1`); every assessment sealed with its inputs and the
  versions it pinned, replayable `IDENTICAL` one at a time or a day at a time, and explained to an investigator from its
  sealed rows (`P13-TSK-023`)
- Limits / velocity — built: durable windows (`H1`, `D1`, `D7`, `D30`) per party, source account, destination, device,
  merchant and - since `P13-TSK-014` - confirming session, upserted in the judged command's own transaction (a hot
  subject serialised on its subject locks), proven by the velocity proof against the counted assessments and, for
  transfers' windows, the monitored journal; no cache and no counter outside the money transaction. No seam carries a
  device yet, so no device window is written (owner item 4)
- Customer risk profile — built: a versioned, append-only rating per party (`LOW | MEDIUM | HIGH | PROHIBITED`;
  `UNRATED` until first evaluated) recomputed by the monitoring sweep's profile leg under the `MONITORING` version from
  signals and the journal's readings, set `PROHIBITED` only by a four-eyes disposition; credit consumes it through
  `CreditRiskSignal` version 2 with the profile version it read (credit `V023`) and computes no risk score. A
  `PROHIBITED` rating refuses a movement only where the active version carries a `PROFILE_RATING` → `DENY` rule (owner
  item 83)
- Fraud detection — built: account takeover (a credential change or recovery followed by a new payee and a new device
  and money leaving - the `ACCOUNT_TAKEOVER` alert and an `ATO` case; the device continuation and recent authentication
  failures read `ABSENT` until a seam carries them, owner items 3, 4) and payment, merchant and BNPL abuse (refund and
  chargeback ratios - `MERCHANT_ABUSE` alerts and cases - and BNPL multi-application) as rule kinds with parameters,
  synchronous and retrospective; "many checkouts in one session" is a `CHALLENGE`-weighted `SESSION` velocity rule, with
  no alert and no case (owner item 80)
- AML transaction monitoring — built: a leaderless sweep (`RiskMonitoringSchedule`) over the ledger's own journal,
  classifying every movement into a monitored transaction born once per journal entry (`UNCLASSIFIED` for a namespace
  the register does not know); monitoring rules per subject over durable aggregates (structuring, rapid in-out,
  dormant-then-active, round amounts, high-risk corridors, velocity); alerts born once per (subject, rule, window
  start); reprocessing from an earlier watermark idempotent, under the operator's order. The 60 s settle horizon is
  unenforced: a posting committing later is skipped until a reprocessing (owner item 92)
- Manual review (cases) — built: one case open per (subject, kind) for fraud, account takeover, merchant abuse, AML and
  - built by `P13-TSK-025` at the owner's request, no longer a cut candidate (C3) - collections: a `LoanDefaulted` or
  `BnplDefaulted` delivery raises a policy-free default alert folded into the party's `COLLECTIONS` case, which proposes
  nothing; a `REVIEW` or `HOLD` decision opens a `FRAUD` or `ATO` case in the judged command's own transaction (a `DENY`
  opens none, owner item 94); taken, worked by append-only investigator actions and evidence by reference behind an
  audited door, escalated (`L1`…`L3`), disposed - a `CLOSED_*` by one person, a confirming, referring or prohibiting
  disposition under four eyes (owner item 78); concurrent updates by revision; queues oldest first; the tipping-off
  control - no alert, case or report reaches a customer or merchant surface (`NoCustomerSurfaceReadsRiskTest`)
- Downstream actions — built, as the code's `ActionKind`: identity suspension, reinstatement and session revocation (a
  party's case), merchant suspension (a merchant-abuse case), a held withdrawal's release or denial and a held card
  payment's capture or void (a movement the case holds, or an approved override's) - each a keyed command
  (`risk.action:<id>`) through the owning domain's existing door, proposed and approved under four eyes, dispatched by a
  leaderless schedule (`RiskActionSchedule`); account suspension and reinstatement and the beneficiary block are
  unavailable - no owning command exists (owner item, `-017` MINOR 2) - and `RESCREEN_PARTY` is never proposed or
  dispatched (owner item 93); risk never writes another domain's row and never reverses a transaction
- Regulatory report artefact — built: a filing distinct from the investigation, drafted from an `AML` case disposed
  `CONFIRMED_SUSPICIOUS`, approved by a second `AML_OFFICER`, filed through `RegulatoryFilingProvider` (simulated; the
  jurisdiction `EU-REF`, adapter data) with `FILED` committed before the call, an unknown answer inquired and never
  re-filed, immutable once filed; a rejection is a new draft
- Rescreening — built: a sanctions or PEP list version recorded and activated by two `RISK_POLICY_ADMINISTER` holders
  enqueues one permit per party whose customer relationship is `ACTIVE` or `PENDING`; `RiskActionSchedule`'s
  rescreening leg asks kyc's **new** rescreen door (the owner's decision O1 - not the existing screening ports) for a
  `RESCREEN` check on the party's latest KYC case; a hit is a `SCREENING_HIT` alert in the party's `AML` case (and, on a
  case not yet decided, kyc's own review item); a burnt version is re-driven by a successor; kyc stays the screening
  owner (Refinement 3)
- Operating risk — built: the meters, thirty alert rules (`infra/prometheus/rules/risk.yml`), four audited operations
  reports with their censuses, the velocity proof and the production censuses behind `RiskCensusDiverged`, and
  `OPERATIONS_RUNBOOK.md` §9 with the production activation gate (`P13-TSK-023`, `-024`)
- **Production constraint** — production evaluates nothing until a risk policy version is activated under four eyes and
  `OPERATIONS_RUNBOOK.md` §9.1's activation gate is passed; until then every seam answers `ALLOW` with the reason code
  `NO_POLICY_ACTIVE`, recorded, and the monitoring sweep raises no rule's alert - while the policy-free facts still
  reach the desks (a default's `COLLECTIONS` case, a list version's rescreening hits); the filing adapter is simulated and
  the reference jurisdiction neutral (C4). The gate's step 2 - the storm's latency figures - cannot be met until the
  storm runs (PI-004)

*(Marked "planned" by the Phase 12 → 13 transition, 2026-10-22 (`PHASE_13_PLAN.md`; ADR-0107…0112 `Proposed`), and
marked built by the Phase 13 exit review, `P13-DOC-001`, 2026-10-27 - each capability built by `P13-TSK-001`…`-025`
and evidenced by each task's hermetic proofs, the policy battery (`P13-TST-002`), the owed database suites (run for the
first time in the owner-authorised database pass, 2026-10-27, green after test-only repairs), the production censuses
and proofs (`AssessmentCensus`, `MonitoringCensus`, `VelocityProof`, `RescreeningCensus`, `FilingCensus`,
`AlertBornOnceCensus`, `CaseOneOpenCensus`) and the independent reviews. **Not by the storm**: `P13-TST-001` is POSTPONED
by the owner until after the project's phases (`POSTPONED_ISSUES.md` PI-004; its partial ninth run green on all 57 census
checks, never finished - no latency figure, no probe, no final run). COMPLETE awaits the review's verdict and the
13 → 14 transition; the open owner items are `PHASE_13_PLAN.md` §1.2's. This note read "to be marked delivered by the
Phase 13 exit review, `P13-DOC-001`", and each capability "planned", until then; the section read "Fraud / Transaction
risk / Account takeover / AML monitoring / Limits / velocity / Manual review", unannotated, before the transition.)*

## Financial Operations
- Settlement
- Reconciliation
- Exceptions / breaks
- Suspense
- Financial reporting
- Regulatory reporting abstraction
- Audit

## Accounting and Financial Reporting *(Phase 14, planned at the Phase 13 → 14 transition, 2026-10-29 — `PHASE_14_PLAN.md`)*
- General ledger — planned: GL accounts and their mapping from the ledger's purposes as versioned data, four-eyes
  activated, never retroactive; every GL figure computed from the one journal, no second ledger and no stored balance;
  drill-down from any figure to its journal lines
- Accounting periods and close — planned: calendar-month periods locked and closed in order; the lock moves a posting
  cutoff the ledger enforces at insert, so nothing dated in a locked period commits after it; a close run of recorded
  readiness checks (trial balance, GL = ledger, rule conformance, source completeness, accruals, reconciliation
  disclosure); a four-eyes close; prior-period adjustments in an open period; a four-eyes reopen that supersedes, never
  deletes
- Accounting rules — planned: one register of every posting namespace with its source, rule, version, line shape and
  dating; every entry checked against it; discrepancies raised once as accounting breaks, never silently corrected
- Multi-currency reporting — planned: EUR functional and reporting currency; valuation rate sets captured from reference
  rates with provenance, approved and pinned; originals always shown; translation differences explicit; no revaluation
  posted
- Financial reporting — planned: immutable, reproducible report runs — trial balance, GL activity, balance sheet and
  income statement, fee and interest revenue, receivables and payables, settlement positions, suspense, FX results, loan
  portfolio, BNPL exposure, reconciliation and close status — FINAL for closed periods, provisional otherwise
- Regulatory reporting — planned: obligations as configuration, submissions only from final figures, four-eyes, with
  lineage, corrected by resubmission; a simulated reference adapter and a reference safeguarding position return; no
  legal obligation asserted
- Write-off — planned, optional (owner decision C6): loan and BNPL write-off under four eyes on the rules already designed;
  provisioning and effective-interest amortisation deferred for qualified review and disclosed

*(Marked "planned" by the Phase 13 → 14 transition, 2026-10-29 (`PHASE_14_PLAN.md`; ADR-0113…0118 `Proposed`); to be
marked delivered by the Phase 14 exit review, `P14-DOC-001`. "Accounting GL" under Money and Accounts and "Financial
reporting" / "Regulatory reporting abstraction" above are this section's capabilities.)*

## Platform
- API gateway
- Eventing
- Notification
- Observability
- Policy/configuration
- Security
- Data governance
