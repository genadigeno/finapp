# ADR-0072 — Amounts never enter metrics: unmatched value, suspense balance and provider costs are audited operator reports

Status: Proposed (2026-09-28, the Phase 7 → 8 transition)
Date: 2026-09-28
Phase: 8
Context: Reconciliation · Settlement · Ledger · Payments · Observability
Supersedes: nothing. Resolves `DELIVERY_PLAN.md` §Phase 8.10 ("unmatched value", "suspense
account balance") against ADR-0018 §2 and `DATA_CLASSIFICATION.md`'s `RESTRICTED-FINANCIAL`
handling, in the classification's favour; the transition's Phase 8 addendum amends §10 with
provenance. Settles the per-rail cost meter that ADR-0060 §6 left to Phase 8 (`DECISIONS.md`
§Deliberately Deferred) as the `PROCESSING_COSTS` purpose (ADR-0065) plus the audited
provider-costs report, and annotates ADR-0060 §6. Carries `P7-TSK-015`'s report-not-tag rule
(ADR-0061's operational impact) from a ratio of counts to figures of money.

## Context

Phase 8 is the first phase whose operational questions are about amounts. The delivery plan's
observability line for it asks for "match rate, unmatched value and count, break age
distribution, break count by type, suspense account balance and age, time-to-resolution", and
adds that an ageing suspense balance "is an alertable operational risk indicator" (§Phase 8.10).
Two of those items are amounts, and the last sentence makes an amount the subject of an alert.

Phase 7 left a third. ADR-0060 §6 said per-rail cost "is observed (a meter), not charged". The
Phase 7 review found that no Phase 7 rail reports a cost, so no meter was built, and gave the
meter to Phase 8, "where the processor's fees arrive with its settlement evidence"
(`DECISIONS.md` §Deliberately Deferred). They do arrive there. The card PSP's report carries
processing fees, the instant scheme's cycle report carries scheme fees, the payout provider's
report carries its fees and the bank statement carries bank fees. ADR-0065 recognises every one
of them as a ledger fact (DR `PROCESSING_COSTS` / CR the counterparty's position) when the report
is accepted. A cost is an amount.

The repository already refuses amounts in telemetry, in more than one place:

- **ADR-0018 §2.** A tag value comes from a small, closed set fixed at compile time, "never an
  identifier, correlation id, account, customer, amount, key, or path".
  `MetricNames.FORBIDDEN_TAG_KEY_FRAGMENTS` refuses `amount` and `account` in any tag key.
- **`DATA_CLASSIFICATION.md` §2.** `RESTRICTED-FINANCIAL` data (amounts, balances, postings,
  settlement positions) is "never in logs, traces, metrics or event payloads".
- **`NoFloatingPointMoneyRulesTest.EXEMPT_CLASSES`.** The only production classes allowed a
  `double` are gauge and counter classes, 28 entries today. Each is admitted because it
  "publishes a count, an age in seconds or a verdict — never an amount" (`MODULE_ARCHITECTURE.md`,
  the no-floating-point rule).
- **The precedents.**
  - `finapp.ledger.trial.balance` publishes a verdict per currency, "never the imbalance amount"
    (`LedgerMetrics`).
  - `P7-TSK-015` made the chargeback ratio an audited operator report
    (`GET /v1/operator/reports/chargeback-ratio`, `payments.ChargebackRatioRead`) and never a
    merchant tag.
  - `finapp.payments.unmatched.parked` counts parkings, not the money parked.

The letter of ADR-0018 covers tags. The sample value, meaning the number a gauge reports, is
governed only by the classification and by the exemption set's argument. Nothing reconciled
either of them with the delivery plan's line. The invariant catalogue points both ways:
`INV-REC-02` asks for an "unmatched-count metric", while `INV-REC-05`'s Verify reads "suspense age
and balance metrics with alerting". Phase 8 must decide this once, rather than let each task pick
a reading.

The question is not one of style. An amount in a metric fails in four ways:

1. **Disclosure.** A scrape is retained for months by a system with its own access control, and
   `/actuator/prometheus` is unauthenticated until `P0-EPIC-10` (ADR-0018 §5, recorded debt).
   Anyone who can reach the port could read the platform's settlement positions and suspense
   balance, and nothing would record that they did.
2. **Precision and meaning.** Micrometer reports a `double`. A balance in minor units is exact only
   below 2^53, and a minor unit means nothing without its scale (ADR-0003's triple). No tag
   carries scale.
3. **Aggregation under N instances.** Each instance reads every gauge behind a refresh floor, and
   the fleet aggregates the readings with `max()`. That rule is right for an age and for a count of
   failing verdicts, because the worst reading is the one to alert on. It is wrong for a balance:
   the maximum of several stale readings of one balance is none of them. A per-instance counter of
   value, summed by the scraper, restarts at zero with each instance, which makes it a second ledger
   that nobody reconciles.
4. **Truth.** `CLAUDE.md` rule 11 requires a balance to be explainable from authoritative records,
   and rule 12 says caches and projections are not financial truth. A figure in Prometheus has no
   identifier chain and no snapshot. Read beside a count taken at another instant, it describes a
   state that never existed (ADR-0018 §3's argument for reading depth and age in one statement). An
   operator who acts on it ("suspense is back to zero") has acted on a number that no record
   explains.

The operator's need is real all the same. An ageing suspense balance is an unrecognised loss or
liability (`INV-REC-05`). A counterparty that overcharges is a cost. Unmatched value is money that
nobody has yet explained. The decision must answer each of these needs without putting an amount
on a scrape.

## Decision

1. **No series the platform publishes carries an amount, either in a tag or as its value.**
   Metrics answer how many, how old, how long and whether: counts, ages in seconds, durations and
   verdicts. This is ADR-0018 §2 widened from the tag to the sample. It binds every Phase 8 series
   and every later one, and it is enforced at four ranks:
   - **Tags:** the existing guard. `MetricNames.ALLOWED_TAG_KEYS` is closed, and the fragment rule
     refuses `amount` and `account` in any key.
   - **Values, by exemption:** every Phase 8 gauge and counter class joins
     `NoFloatingPointMoneyRulesTest.EXEMPT_CLASSES` with a written argument naming what it counts.
     Its readings come from store methods that return a `long` from `count(*)`, an age in whole
     seconds, or a verdict enum. This is the `IdentityMetrics` precedent: a `long` all the way to
     the registry boundary.
   - **Values, statically:** `P8-TSK-024` adds a rule that no class in `EXEMPT_CLASSES` depends on
     the `Money` type or on `MoneyColumns`, and proves the rule by catching a planted violation.
     All 28 entries satisfy it today. A `CurrencyCode` stays permitted, because a currency is a tag
     value and a `Money` is not.
   - **Review and gate:** the rule has a known edge, stated here rather than hidden (the
     no-floating-point rule's own discipline). A store method that returned a monetary `long` would
     pass the static rule. That is why each exemption's argument names its store method, and why
     the Phase 8 review reads every new exemption against its query.

2. **An operator reads value through an audited operator report.** Phase 8 has five reports, all
   under `/v1/operator/reports/reconciliation/` and all under `RECONCILIATION_INVESTIGATE`. Every
   one follows the same rules:
   - **One snapshot.** A report runs as one `REPEATABLE READ` transaction on one connection,
     composed in `app` from each module's read API (the ledger's `BalanceDerivation` and line
     reads, and reconciliation's and settlement's reads). This follows the storm-reading precedent
     (ADR-0064). It takes no row lock, so it never contends with matching, parking or posting.
   - **Audited in the same transaction.** The report and its audit record commit together, or
     neither does (the `payments.ChargebackRatioRead` precedent), so a read that cannot be audited
     returns nothing. One action, `reconciliation.ReportRead`, names only the report and the
     period. It never names an amount, a source's figure or a counterparty, and it requires no
     reason: a person judging something, such as a raw file's content read, gives a reason, but a
     desk reading its own reports does not.
   - **Folded with `Money`, never summed in SQL.** Every figure is derived when the report is
     read, from the ledger's lines and reconciliation's rows, and folded with `Money`
     (`PositionBreakdown`'s rule). Nothing is precomputed, cached or stored as a balance.
   - **Per currency, gross.** Every figure carries the ADR-0003 triple. No total crosses currencies
     (`INV-MON-04`; conversion belongs to Phase 9). Directions are never netted where netting would
     hide one side: suspense CREDIT and DEBIT items, and unmatched INBOUND and OUTBOUND value, are
     shown separately.
   - **Identifiers, not references.** A row carries our identifiers, source codes, types, states,
     dates, counts and amounts. It never carries a counterparty reference value (`CONFIDENTIAL`), a
     note or narrative, or any byte of a file. To see more, an investigator goes from a report to a
     break's trace (`/v1/operator/reconciliation/breaks/{id}/trace`) or an operation's settlement
     status (`/v1/operator/reconciliation/settlement-status`), each a separate route with its own
     permission check.
   - **Bounded.** At most 100 rows, worst first, with `truncated` set when there were more (the
     rule every Phase 8 operator list follows).
   - **Periods validated at the door.** `month` is `YYYY-MM` from 2000-01 to the current UTC month,
     and defaults to the current month. `date` is a UTC business date no later than today. Any
     other value gets the same 422 the chargeback-ratio report returns. This is `P7-TSK-015`'s gate
     finding: a period that matches the pattern but that no record can carry is refused at the
     boundary, not discovered by the database driver.
   - **Read-only.** A report claims, keys and writes nothing except its audit record.

3. **The five reports.**

   | Report | Question it answers | Carries | Built by |
   |---|---|---|---|
   | `positions` | Is each reconciled position explained, and what is in it? | Per clearing position and currency: the ledger balance; open expectation remainders, split into not yet reported and reported-but-awaiting-cash (the `REMITTANCE` expectations); unallocated, unparked items; and the proof's verdict. For `CASH_AT_BANK`: the ledger balance against the closing balance of the latest statement in an unbroken chain, and that verdict | `P8-TSK-007` (the clearings); `P8-TSK-016` adds cash |
   | `suspense` | What sits in `SUSPENSE_UNMATCHED`, how old is it, and who owns it? | Per currency: the ledger balance; the items remaining, CREDIT and DEBIT gross; each item's age from `opened_on` and its owning break's type and severity; the suspense proof's verdict | `P8-TSK-024` |
   | `unmatched` | How much value is unexplained on each side? | Per source, currency and direction: external items with unallocated value (`UNMATCHED` inside grace, `PARKED` with a break) and open expectations (inside their window, and overdue), each with count, value and oldest age; and the open breaks' value at issue, by type and severity | `P8-TSK-024` |
   | `summary?date=` | What did reconciliation do on one business date? | Per source: batches accepted; items by disposition, as count and value; the match rate as a ratio of counts to four places, rounded half up, never a `double` (the ratio precedent); breaks raised and resolved by type and severity, with their value at issue; resolutions approved by kind, with the value they posted; open breaks by ageing band (0–2, 3–7, 8–30, >30 days) | `P8-TSK-024` |
   | `provider-costs?month=` | What did each counterparty charge us, and was it what we agreed? | See point 6 | `P8-TSK-024` |

   These are operational reports, not accounting ones. They include no GL mapping, period close or
   statement (Phase 14), and nothing merchant-facing (Phase 12). A `positions`, `suspense` or
   `unmatched` read describes "now", meaning the instant of its snapshot. This ADR does not decide
   an as-of parameter (see Follow-up).

4. **The delivery plan's line, item by item.** Every item it asks for is answered. Each amount is
   answered by a report:

   | `DELIVERY_PLAN.md` §Phase 8.10 asks for | A metric (count, age, duration or verdict) | A report (amounts, audited) |
   |---|---|---|
   | Match rate | `finapp.reconciliation.item` by `source` and `outcome` | `summary` |
   | Unmatched count | `finapp.reconciliation.item.unmatched` by `source` (inside grace); `finapp.reconciliation.break.open` by `type` and `severity` (parked) | — |
   | Unmatched value | — | `unmatched` |
   | Break age distribution | `finapp.reconciliation.break.age` by `severity`: the oldest open break per severity, where severity escalates with each ageing band crossed | `summary` (open breaks by band) |
   | Break count by type | `finapp.reconciliation.break.raised` and `finapp.reconciliation.break.open`, by `type` and `severity` | — |
   | Suspense age | `finapp.reconciliation.suspense.age` (the oldest open item), `finapp.reconciliation.suspense.open`, `finapp.reconciliation.suspense.unowned` (must be 0) | `suspense` |
   | Suspense account balance | — | `suspense` |
   | Time-to-resolution | `finapp.reconciliation.resolution.latency` by `type` | `summary` |
   | An ageing suspense balance is alertable | Point 5 | `suspense` gives the amounts behind the alert |
   | *(Phase 7's input)* per-rail cost | — | `provider-costs`; `PROCESSING_COSTS` in the ledger (point 6) |

   The transition's `DELIVERY_PLAN.md` Phase 8 addendum amends §10 to match this table, with
   provenance, as the Phase 7 addendum did for its own lines.

5. **Value reaches alerting only as a severity.** An alert fires on an age or a severity, never on
   an amount. Value enters alerting only through a pinned, versioned rule:
   - Every suspense item has exactly one owning break (`INV-REC-09`, ADR-0070). A break's severity
     is escalated one level when its value at issue reaches the rule set's per-currency
     `high_value_minor` (the `severity_threshold` row), and one more level for each ageing band it
     crosses (ADR-0069).
   - `finapp.reconciliation.break.open` and `finapp.reconciliation.break.age` alert per severity:
     CRITICAL above 0 hours, HIGH above 1 day, MEDIUM above 5 days, LOW above 15 days.
     `finapp.reconciliation.suspense.age` alerts on its own.

   A large parked amount and an old one therefore both raise an alert, and whoever answers it reads
   the amount from the audited `suspense` report. The threshold is rule-set content: it is pinned
   on every break and changes only through a new version approved under four-eyes (ADR-0068). The
   reason a break was HIGH can therefore be replayed. A dashboard threshold on a number would be
   none of these things. `severity` is the only tag derived from value, and it comes from the
   pinned rule, not from the amount itself. There is no value-band tag.

6. **The per-rail cost meter is settled as a ledger fact and a report, never a metric.**
   - **"Not charged" stands.** Routing chooses the rail and nothing else (ADR-0060 §6). No price
     varies with the rail, and passing costs on to merchants stays deferred, together with
     dispute-fee pass-through. Costs feed no routing decision in Phase 8. A cost-aware routing
     policy would be a new policy version under ADR-0060, not a side effect of this ADR.
   - **"A meter" is replaced.** A counterparty's cost is known only from its evidence, and ADR-0065
     records it as a ledger fact when that evidence is accepted, dated by the batch's stored
     `accepted_on`:
     - a report's fees post DR `PROCESSING_COSTS` / CR the counterparty's clearing position (the
       mirror entry for a net rebate);
     - the bank's own fees post DR `PROCESSING_COSTS` against `CASH_AT_BANK`.

     `PROCESSING_COSTS` (EXPENSE, ledger `V016`, `P8-TSK-009`) is posted by recognition, by a
     repudiation's reversal of a recognition (report item 1 below; ADR-0065 §5), and by nothing
     else. *(The reversal was missing from this sentence until the transition's consistency
     review, B8.)* The fee check (`P8-TSK-012`) compares every reported fee line with the fee the
     pinned `provider_fee_schedule` expects. A difference beyond the tolerance is a `FEE_MISMATCH`
     break with no residual, because the reported fee is already expensed.
   - **"Per rail" without naming a rail.** Every rail that declares `settlement() != NONE` has
     exactly one source (`INV-SET-05`, ADR-0064). A report grouped by source code is therefore
     grouped by rail, through the compiled register and never through a rail literal
     (`INV-RAIL-01`, `RailVocabularyIsConfinedTest`). The payout provider and the bank are sources
     too, so the report covers every counterparty that charges us, not only the rails.
   - **The provider-costs report,**
     `GET /v1/operator/reports/reconciliation/provider-costs?month=YYYY-MM`, states four things per
     source and currency for the calendar month, all from stored rows:
     1. **What we were charged:** the `PROCESSING_COSTS` lines of the source's recognition entries
        whose posting date falls in the month, less the lines of any repudiation reversal posted
        in that month (ADR-0065 point 10). This is the ledger's own figure.
     2. **What the evidence says:** the month's fee totals by line type (`PROCESSING_FEE`,
        `SCHEME_FEE`, `BANK_FEE`) from the accepted batches' immutable `batch_total` rows, and a
        verdict on whether they agree with item 1. If they disagree, the defect is ours, not the
        counterparty's.
     3. **What we expected:** the sum of the expected fees that the `CHECK` decisions recorded under
        their pinned schedules.
     4. **Where they differ:** the `FEE_MISMATCH` breaks raised in the month, with their count, their
        value at issue, and how many remain open.

     The month is a posting-date month. A later-day replay of a recognition keeps its stored
     `accepted_on`, so it never moves a cost from one month to another.

7. **The same boundary holds on every other telemetry channel.**
   - **Traces:** Phase 8's spans (`settlement.receive`, `settlement.parse`, `settlement.accept`,
     `reconciliation.chunk`, `reconciliation.rematch`, `reconciliation.age`,
     `reconciliation.resolve`) carry identifiers only.
   - **Logs:** no amount, reference value, note or file byte is ever logged (`RESTRICTED-FINANCIAL`,
     `CONFIDENTIAL`, `INV-AUD-02`).
   - **Events:** every Phase 8 event carries identifiers, enums and counts only. For example,
     `reconciliation.ReconciliationRunCompleted` counts items per outcome, never their value. The
     amount wire format in `EVENT_ARCHITECTURE.md` stays reserved for an event whose fact *is* an
     amount (`merchant.FeeAssessed`, `merchant.FeeReturned`). No Phase 8 event is such a fact, so a
     consumer that needs an amount reads it from the amount's owner.
   - **Audit records:** change summaries carry identifiers only (the `UnmatchedConfirmations`
     precedent), and `reconciliation.ReportRead` names only the report and the period.

8. **What the metrics carry, and how.** Phase 8's series are listed in `PHASE_8_PLAN.md` §15, which
   `PlannedMetersExistTest` reads once the phase is COMPLETE. They are counts, ages, durations and
   verdicts, under closed tags:
   - **Two new tag keys, each with its written argument in `MetricNames`.**
     - `source` is bounded by the compiled source register (four codes today) and contains no
       forbidden fragment.
     - `severity` is a closed enum.
     - Existing keys cover the rest: break type and resolution kind use `type`; dispositions and
       reasons use `outcome`; positions use `purpose`; currencies use `currency`; and `stage`
       already exists (`P7-TSK-015`).
     - No tag names a counterparty reference, a batch, a file, a break, an expectation, a merchant,
       a customer or an account.
     - Each new key arrives, with its argument, in the first task that publishes a series under it:
       `source` in `P8-TSK-002`, with `finapp.settlement.file.received` and
       `finapp.settlement.delivery.refused`; `severity` in `P8-TSK-024`, with the break series
       (`finapp.reconciliation.break.raised`, `.open` and `.age`), the first tagged by severity.
       `P8-TSK-024` completes the set. *(Aligned with the backlog by the transition's consistency
       review, B6.)*
   - **Proofs publish verdicts,** in the trial balance's shape:
     - `finapp.reconciliation.position.proof` by `purpose` and `finapp.reconciliation.cash.proof`
       by `currency` count the currencies failing their identity;
     - `finapp.reconciliation.line.unattributed` by `purpose` counts the journal lines that no
       Phase 8 record knows.

     Each must read 0. None reports the size of a discrepancy; the size is in the `positions`
     report.
   - **Gauges** read the shared database behind a refresh floor. They report `NaN` when the
     database cannot be read, never zero (ADR-0018 §4), and aggregate across instances with
     `max()`.
   - **Counters** count committed facts after commit, in the `CommittedRailOutcomes` shape, so the
     fleet's counters sum to what the tables hold. `P8-TST-001` asserts that tally, following
     `P7-TSK-015`'s second-tally design.
   - **The dashboard** is the "Settlement and reconciliation" row of ten panels. It is a file in git
     that queries only published series (ADR-0018 §6, `DashboardQueriesResolveTest`). No panel reads
     the database and no panel shows an amount. Alerts cover every gauge that must read 0, source
     silence, overdue expectations, suspense age, break age per severity, refused deliveries and
     blocked runs.
   - **Phase 7's suspense gauges are corrected in meaning** (ADR-0070, `P8-TSK-020`).
     `finapp.payments.unmatched.active` and `finapp.payments.unmatched.age` count every row ever
     parked. Their descriptions become "parked, ever", and the alertable signal becomes
     `finapp.reconciliation.suspense.*`. The names stay, so the rows in `PHASE_7_PLAN.md` §15 still
     parse.

9. **Correct under ten instances.** None of this decides anything, and nothing that must be correct
   depends on one process.
   - A report is one read-only snapshot, so every instance gives the same answer for the same
     snapshot. Its audit row is an insert that contends with nothing.
   - Gauges are shared-database readings, and their fleet aggregate, `max()`, is right for an age
     or a count of failing verdicts. That is exactly why no balance is among them.
   - Counters count only what a commit made true.

   No figure depends on which instance answered.

10. **Owner decisions this ADR carries.** The owner's open decisions were settled at the
    transition on the design's recommendations, and each is recorded as a transition decision the
    owner may revisit.
    - **O7, the high-value severity threshold:** `high_value_minor` is seeded at 1,000.00 for EUR,
      GBP and USD in rule set v1 (ADR-0069). It is the only route by which value reaches an alert
      (point 5). Revisiting it means a new rule-set version under four-eyes, not a migration.
      *(Settled 2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by the
      owner.)*
    - **O1, roles:** two pairwise-disjoint roles (ADR-0071). The reports are read under
      `RECONCILIATION_INVESTIGATE`, which `RECONCILIATION_OPERATOR` holds and
      `RECONCILIATION_CONTROLLER` does not, so whoever sets tolerances reads the reports only by
      also holding the operator role, a recorded grant. *(Settled 2026-09-28 at the Phase 7 → 8
      transition, on the recommendation; revisitable by the owner.)*
    - **O6, the cut order:** if scope must shrink, `P8-TSK-021`, then `P8-TSK-019`, then
      `P8-TSK-023`. The design places `finapp.settlement.source.silence` beside
      `finapp.settlement.pull.failure` in `P8-TSK-021`. But silence is measured from each source's
      last accepted batch, not from pulls, and a file that never arrives must stay visible without
      pull acquisition. If `P8-TSK-021` is cut, the silence gauge moves into `P8-TSK-024`'s "every
      remaining series" (`PHASE_8_PLAN.md` §15), and only `pull.failure` goes with the pull.
      *(Settled 2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by the
      owner. The silence gauge's move is this ADR's reading of the cut, recorded for the backlog.)*

    O2, O3, O4 and O5 do not touch telemetry. They are recorded in ADR-0073, ADR-0066, ADR-0065 and
    ADR-0070.

11. **Amended at the transition, with provenance.**
    - `DELIVERY_PLAN.md` §Phase 8.10, in the Phase 8 addendum (point 4).
    - `INV-REC-05`'s Verify line, "suspense age and balance metrics with alerting", becomes
      "suspense age and count gauges with alerting; the balance in the audited suspense report
      (ADR-0072)". This is in addition to the Enforce amendment the transition makes. The statement
      ("tracked, aged, reported and alerted on") is unchanged, and it is met in full.
    - ADR-0060 §6 is annotated: the cost meter is settled as `PROCESSING_COSTS` plus the
      provider-costs report. `DECISIONS.md`'s Deliberately Deferred row records this ADR as its
      disposition.
    - `PHASE_GATES.md`'s Phase 8 extension carries the rule as exit criteria: "Suspense and
      adjustment" (suspense reported by the audited `/reports/reconciliation/suspense`, CREDIT and
      DEBIT items gross) and "Observability" (no amount in any tag or value; unmatched value, the
      suspense balance and provider costs are audited operator reports). *(Cited by number until
      the transition's consistency review, B15: the gate's criteria are labels.)*

## Alternatives Considered

### Publish the amounts the delivery plan names as gauges
Pros: one pane of glass; an alert threshold directly on value; the plan's line read literally.
Cons: it hits all four failures described in the Context: `RESTRICTED-FINANCIAL` data on an
unauthenticated scrape that is retained for months; a `double` with no scale; `max()` over stale
readings of a balance, which yields none of them; and a figure with no record behind it. Every
gauge class would need an exemption arguing that it publishes an amount, which is the one
argument the exempt set exists to refuse.

### Publish integer minor units, with the currency as a tag
Pros: no floating arithmetic in our code, and `currency` is already an allowed key.
Cons: the registry boundary is still a `double`, exact only below 2^53, and the scale is carried
nowhere. It changes the arithmetic, not the disclosure, the aggregation or the truth.

### A value-band tag (for example `band=high`)
Pros: materiality on the dashboard without exact figures.
Cons: a tag key derived from an amount, which is what the fragment rule exists to stop. A band per
currency is policy, and policy belongs pinned in the rule set, where `severity` and
`high_value_minor` already carry the same fact, versioned and replayable. Two vocabularies for one
fact drift apart.

### The per-rail cost meter as ADR-0060 §6 wrote it
Pros: it is what was written, and it would show a cost rate per rail on a panel.
Cons: it puts an amount in a metric. Costs are known only when a report is accepted, daily or per
cycle, so the meter would move in steps and its rate would add no operational signal. A
per-instance counter of money restarts at zero and cannot be replayed. It would sit beside
`PROCESSING_COSTS` as a second, unreconciled figure for the same fact. The ledger already holds the
authoritative figure, with an identifier chain to each fee line.

### Value panels reading PostgreSQL directly from Grafana
Pros: no new routes, and live figures on the dashboard.
Cons: every read of `RESTRICTED-FINANCIAL` data here must be authorised and audited, and a
dashboard data source is neither. It would be a new database principal outside the application's
grants, with no audit trail. The dashboard is a reviewed file that queries only published series
(ADR-0018 §6). A panel would also sum in SQL, where the platform folds with `Money`.

### Amounts on events, for a reporting consumer
Pros: reporting would be decoupled from the reconciliation module.
Cons: events carry identifiers (`EVENT_ARCHITECTURE.md`). The fee-assessed exception exists for an
event whose fact is an amount; it is not a precedent for publishing a balance. Kafka is not
financial truth (`INV-EVT-02`), Phase 8 has no consumer, and a reporting store fed by events is a
projection that would itself need reconciling.

### Unaudited reports
Pros: fewer audit rows, and the reports are read-only anyway.
Cons: a report that aggregates every counterparty's position, suspense and costs is a bulk read of
`RESTRICTED-FINANCIAL` data, which is the kind of read `INV-AUD-01` puts on the record. The
chargeback-ratio report is audited and carries only counts; these reports carry money, which makes
the case stronger. An unaudited bulk read is an exfiltration path nobody would see.

## Consequences

Positive:
- One rule covers every telemetry channel, with no exception. The classification, the tag guard
  and the exemption set now say the same thing, and the static rule makes it a build fact.
- Every amount an operator sees is explainable. It is derived at read time from the ledger and
  reconciliation's rows, in one snapshot, per currency, and it carries the identifiers needed to
  walk back to the evidence.
- The cost-meter debt is paid with a stronger instrument than the one that was deferred: it is
  authoritative, replayable, dated by posting date and checked against a pinned schedule.
- Value can still raise an alert, through severity, which is pinned, versioned and replayable.
- The meters stay a second, independent tally of the tables (`P8-TST-001`).

Negative:
- There is no time series of value. Suspense balance and unmatched value cannot be graphed over
  time; a trend means reading successive reports. An as-of parameter is recorded, not decided (the
  ledger's `AsOf` already offers `postingDate`), and period reporting belongs to Phase 14.
- A report folds every relevant open row in Java on every read. For `positions`, `suspense` and
  `unmatched` the cost is bounded by the open set, not by history; for `provider-costs` it is
  bounded by the month. Incremental watermarks are the recorded path, as for the proofs.
- An alert names a severity and an age. The amount behind it is one audited read away, by design.
- Materiality is only as good as the pinned threshold (O7). It is set per currency and never
  converted, so 1,000.00 GBP and 1,000.00 USD produce the same severity until Phase 9.
- Every report read writes an audit row.

Operational impact:
- The five reports: `positions`, `suspense`, `unmatched`, `summary` and `provider-costs`, under
  `/v1/operator/reports/reconciliation/`, each with a `RoutePermissionRegisterTest` row and an
  OpenAPI entry (additive under v1, ADR-0015).
- Phase 8's series in `PHASE_8_PLAN.md` §15. The "Settlement and reconciliation" dashboard row and
  its alerts. The `source` and `severity` tag keys. One `NoFloatingPointMoneyRulesTest` exemption
  per gauge class, each with its argument.
- An operator answering an alert reads the relevant report. The alert itself carries the severity
  and the age.

Security impact:
- The scrape endpoint stays free of financial data. This ADR does not rely on `P0-EPIC-10` to
  protect amounts; it keeps them off the endpoint.
- The reports are authorised by `RECONCILIATION_INVESTIGATE`, with a negative test per route.
  Every read is audited as `reconciliation.ReportRead`, naming only the report and the period.
- Report rows carry identifiers and amounts, never a counterparty reference, a note, a narrative
  or a file byte.

Financial impact:
- Provider costs live in `PROCESSING_COSTS`, posted only by recognition from accepted evidence
  and by a repudiation's reversal of that recognition.
- No metric is a financial record, and nothing financial is decided from one (ADR-0018's
  financial impact, carried forward).
- Routing and merchant pricing are unchanged.

## Invariants / Constraints

- `INV-AUD-02` and `DATA_CLASSIFICATION.md`'s `RESTRICTED-FINANCIAL` handling: no amount in
  telemetry.
- `INV-AUD-01` and `INV-AUD-03`: every report read is authorised, with a negative test per route,
  and audited.
- `INV-MON-01`: the gauge exemptions publish counts, ages and verdicts only. `INV-MON-04`: no
  report total crosses currencies.
- `INV-BAL-05` and `INV-EVT-02`: no financial decision is taken from a metric or an event. A metric
  carries even less authority than a projection.
- `INV-REC-05`: its Verify line is amended (point 11), and its statement is met by gauges and the
  audited report together. `INV-REC-02`: the unmatched-count metric. `INV-SET-02`: the expectation
  gauges alert.
- `INV-RAIL-01` and `INV-SET-05`: costs are grouped by source, never by rail name.
- `INV-HIST-04`: a break's severity, and therefore its alert, can be replayed from the pinned
  threshold.

## Follow-up

- **Implemented so far** *(every other statement is the decided design, corrected by the tasks
  that build it)*: `P8-TSK-002` (2026-09-29) brought the `source` key and the first series;
  `P8-TSK-007` (2026-09-29) the `positions` report with the clearing rows — one open-remainders
  figure per row until `P8-TSK-009`'s `REMITTANCE` expectations give the split —
  `reconciliation.ReportRead`, both verdict gauges and `finapp.reconciliation.expectation.open`.
  `P8-TSK-016` (2026-09-30) the `positions` report's cash rows — a trailing `cash` list, additive
  under v1: per currency the `CASH_AT_BANK` balance, the head closing of the statement chain,
  their difference, the latest accepted sequence and the `unbroken` and `explained` verdicts, from
  the same one-snapshot sweep — and `finapp.reconciliation.cash.proof` by `currency`: 1 when the
  currency's cash is not the unbroken chain's closing, else 0, eager per supported currency, NaN
  never zero, aggregated with `max()`; a verdict, never an amount (§8).
- **The tasks that build it:**
  - `P8-TSK-002` — **implemented** (2026-09-29) — brings the `source` key with its written
    argument, and the first series under it
    (`finapp.settlement.file.received`, `finapp.settlement.delivery.refused`).
  - `P8-TSK-007` — **implemented** (2026-09-29) — builds the first report (`positions`, with the clearing rows), the
    `reconciliation.ReportRead` action, the proof gauges (`finapp.reconciliation.position.proof`
    and `finapp.reconciliation.line.unattributed`) and `finapp.reconciliation.expectation.open`,
    under the `source` key. The report needs `RECONCILIATION_INVESTIGATE`, which `P8-TSK-003`
    introduces, so `P8-TSK-007`'s Deps line names `P8-TSK-003` *(recorded here as a request, and
    applied to the backlog by the transition's consistency review, B2)*.
  - `P8-TSK-009` adds `PROCESSING_COSTS` (ledger `V016`) and fee recognition. `P8-TSK-012` adds the
    fee check whose decisions the provider-costs report reads.
  - `P8-TSK-010` publishes the suspense gauges, `P8-TSK-011` the run gauges, and `P8-TSK-013` the
    overdue-expectation and unmatched-item gauges, each exemption argued. The break series
    (`finapp.reconciliation.break.raised`, `.open` and `.age`) and the `severity` key's argument
    are `P8-TSK-024`'s. *(This line gave the break gauges to `P8-TSK-010` and `P8-TSK-013` until
    the transition's consistency review, B6.)*
  - `P8-TSK-016` — **implemented** (2026-09-30) — adds the cash rows and
    `finapp.reconciliation.cash.proof`.
  - `P8-TSK-020` corrects the descriptions of Phase 7's suspense gauges.
  - `P8-TSK-021` adds `finapp.settlement.source.silence` and `finapp.settlement.pull.failure`. If
    it is cut, silence moves to `P8-TSK-024` (point 10, O6).
  - `P8-TSK-024` adds every remaining series, completes the tag arguments, and builds the
    `suspense`, `unmatched`, `summary` and `provider-costs` reports, the dashboard row and its
    alerts, and the static rule with its planted violation. Its acceptance: a fresh instance
    publishes every series; `NaN` never zero; reports bounded and audited; no amount in any series.
  - `P8-TST-001` asserts the meters' tally against the tables in every round. `P8-DOC-001` reads
    every new exemption against its query (point 1) and rules on the "Observability" criterion.
- **At the transition, with provenance:** the `DELIVERY_PLAN.md` Phase 8 addendum (§10);
  `INV-REC-05`'s Verify line; ADR-0060 §6's annotation and `DECISIONS.md`'s Deliberately Deferred
  row; `PHASE_GATES.md`'s "Suspense and adjustment" and "Observability" criteria.
- **Recorded, not decided:**
  - an as-of parameter for the `positions`, `suspense` and `unmatched` reports;
  - cost-aware routing (a policy version under ADR-0060, when a phase has two eligible rails for
    one instrument);
  - passing costs on to merchants (with dispute-fee pass-through, in Phase 13's neighbourhood);
  - authentication on the scrape endpoint (still `P0-EPIC-10`'s).
- **Acceptance.** The Phase 8 review (`P8-DOC-001`) reads this ADR against the code before
  accepting it, following the `P7-DOC-001` precedent.
