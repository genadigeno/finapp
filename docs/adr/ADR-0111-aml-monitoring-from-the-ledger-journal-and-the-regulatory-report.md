# ADR-0111 — AML transaction monitoring from the ledger journal, and the regulatory report as an artefact distinct from the investigation, behind a filing port

Status: Proposed
Date: 2026-10-22
Phase: 13
Context: Risk (AML / Transaction Monitoring) · Ledger · KYC · Identity · Audit · Reconciliation
Supersedes: nothing. Applies ADR-0107 (`MonitoredLedger` is a port `app` implements over the ledger; `risk` has no
ledger edge), ADR-0024 (no correctness in process memory; events are hints, sweeps read rows), ADR-0109 (the
monitoring policy is a versioned policy of the same engine) and ADR-0110 (alerts born once, folding into the AML
case). Follows the `ScreeningAdapter` precedent for a simulated provider and the payout precedent for an `UNKNOWN`
answer. Rests on `PHASE_13_PLAN.md` §1.1, §2.1, §2.2 (C4), §3.1, §4, §5, §7.1 (T-mon, T-rep, T-list), §7.2 (R7, R8),
§7.4, §7.5, §9, §10, §11, §12.4 (`VelocityProof`), §12.5, §12.7, §12.9, §12.10, §13.2 (scenarios 2, 8, 12), §14 and
`INV-RSK-03`, `-06`, `-08`, `-09`, `-10`.

## Context

1. **Monitoring must see every movement, and the only record of every movement is the journal.** Transfers,
   withdrawals, checkout captures, payouts, corridor payments, disbursements, repayments and BNPL financings each
   post through the ledger in their own module; their events are published at-least-once, by outbox, and consumed
   through an inbox — a hint that something happened, never the thing. A monitor that read events would miss what an
   event did not carry and would build a second record of money that can disagree with the first.
2. **The sweep must be leaderless and reprocessable.** A policy change or an incident will require re-running the
   monitoring rules over a history; the re-run must create nothing a first run did not (`INV-RSK-09`). The lending
   and BNPL schedules give the shape: a database-stamped permit, a `SKIP LOCKED` claim, born-once rows.
3. **An investigation and a filing are different obligations** (OQ-7). A case is the platform's own; a report is a
   communication to an authority with its own content rules, approvers, retention and an acknowledgement. Making the
   case the report would file whatever was investigated and investigate only what is filed.
4. **The jurisdiction is still neutral** (C4; `DECISIONS.md` §Deferred). The platform models no real FIU; what a
   real jurisdiction requires of a report must be researched now and verified later, as adapter data.
5. **kyc owns screening** (`ROADMAP.md` Refinement 3): `risk` monitors and asks; it never calls a list provider.

## Decision

1. **The intake reads the ledger journal through `MonitoredLedger`** — a port `risk` declares and `app` implements
   over the ledger's journal entry store ordered by its sequence, read from a per-source **watermark** row. Each entry
   becomes one **monitored transaction**, born once by `UNIQUE (journal_entry_id)` (`risk V004`, `P13-TSK-019`), with
   the kind, subject references, counterparties, amount, currency and value date its posting-key namespace yields.

2. **Classification is by posting-key namespace, through a closed register.** `transfer:`, `payments.`,
   `merchant-payout:`, `crossborder.`, `lending.`, `bnpl.`, … each map to a kind and to the rule that names the
   subject and the counterparties. An entry whose namespace the register does not know is recorded as
   `UNCLASSIFIED` **and alerted** — nothing passes unseen — and `finapp.risk.monitoring.unclassified` gauges it. A new
   posting-key namespace anywhere in the platform therefore surfaces as an alert, not as a blind spot.

3. **T-mon is leaderless, per claim, in its own transactions.** `RiskMonitoringSchedule` claims the watermark row
   `FOR UPDATE SKIP LOCKED` (R7) by its `next_sweep_at` permit, reads the entries since it through the port, inserts
   the monitored transactions, advances the watermark and commits — alone, holding no other row. Then, per subject:
   the `customer_risk_profile` head row `FOR UPDATE` (R8), the monitoring policy's rules over the subject's monitored
   transactions and velocity windows (`STRUCTURING`, `RAPID_IN_OUT`, `DORMANT_THEN_ACTIVE`, `ROUND_AMOUNT`,
   `CORRIDOR_HIGH_RISK`, `VELOCITY_*`; ADR-0109), alerts born once per (subject, rule, window start), folded into the
   subject's open `AML` case (ADR-0110), and the profile recomputed as a new version (`INV-RSK-10`). Completion events
   from transfers, payments and crossborder are hints that pull the permit forward; the sweep reads rows. A crash
   mid-claim lapses the permit; the next claim re-reads from the committed watermark and the born-once rows absorb the
   overlap (plan §14).
   *(As built by `P13-TSK-020`, 2026-10-25: the per-subject leg runs **inside** the intake's claim - after the inserts,
   before the advance, each subject under a savepoint - because the claim's commit is the only durable record that its
   movements were seen; "per subject" is per **party** (a monitored transaction's subject is a customer account, joined
   to its party through `MonitoredParties`; every AML alert is the party's, so `-018`'s queue serves it); the windows are
   cut on the movement's value date and every alert's key is that date's bucket, never a run's clock;
   `CORRIDOR_HIGH_RISK` reads `ABSENT` (the journal carries no corridor) and merchant subjects are not judged - both
   owner items. `PHASE_13_PLAN.md` §12.7's as-built note.)*

4. **Reprocessing is idempotent by construction** (`INV-RSK-09`). An operator under `RISK_POLICY_ADMINISTER` orders a
   re-run from an earlier watermark, audited; monitored transactions, alerts, cases, actions and filings are all
   born-once rows with uniques, so the re-run creates nothing new (scenario 8:
   `MonitoringIntakeDatabaseTest#reprocessingCreatesNothingNew`). Two proofs keep the record honest: a **census**
   proving the monitoring record never diverges from the journal (monitored transactions = classified journal
   entries since the first watermark, by count and sum per namespace), and **`VelocityProof`** recomputing every live
   velocity window from the monitored transactions (`INV-RSK-03`) — a difference is an alert, never self-corrected.

5. **The regulatory report is its own aggregate** (`risk V011`, `P13-TSK-021`), distinct from the case in
   lifecycle, permissions and retention. It is drafted from a disposed `CONFIRMED_SUSPICIOUS` case by an
   `AML_REPORT_APPROVE` holder (`UNIQUE (case_id)` while a draft is open), approved by a **second** such holder, and
   filed by an `AML_REPORT_FILE` holder: `DRAFT → APPROVED → FILED → ACKNOWLEDGED | REJECTED`; a rejected filing is a
   new draft; `FILED` is immutable. It carries a **jurisdiction code**, a format version, the reporting entity's
   reference, the subject and account references, the transactions referenced (monitored transaction ids), the
   grounds for suspicion (the case's reason codes and the approved narrative, `RESTRICTED-PII`, readable only by
   `AML_REPORT_*` holders), the attachments' references and the filing's acknowledgement. Approval and filing need a
   `MULTI_FACTOR` session; the proposer never approves (`INV-RSK-07`); self-dealing fails closed.
   *(As built by `P13-TSK-021`, 2026-10-25 - **`risk V016`**, `V011` having gone to `-014`; `DESIGN-021`: drafted from an
   **`AML`** case of a party alone; the transactions, accounts and grounds **drawn from the case** - the monitored
   transactions born from the journal entries its alerts and evidence name, touching the party's accounts; the party's
   accounts; the alerts' rule codes - the drafter writing the narrative, the jurisdiction and the attachments' references;
   the report's id its `REPORT_DRAFTED` investigator action's, written on the disposed case in the same transaction (an
   investigator action, never a risk action: no owning domain executes a report); **one live or accepted report per
   case** - `UNIQUE (case_id) WHERE status <> 'REJECTED'`, the decision's "while a draft is open" widened, since a draft
   beside a filed or acknowledged report is a second filing; the content frozen **from birth** for every writer and in no
   `UPDATE` grant; a read door `GET …/reports/{reportId}` (`AML_REPORT_APPROVE`, audited `risk.RegulatoryReportRead`)
   added so the second person reads what they approve; persons `CUSTOMER` or `EMPLOYEE`.)*

6. **Filing goes through `RegulatoryFilingProvider`, a port whose Phase 13 implementation is a simulated FIU
   endpoint** (the `ScreeningAdapter` precedent). T-rep commits `FILED` with the provider's reference pending
   **before** the call; the call holds no connection; the acknowledgement or rejection applies by a conditional edge
   in a second transaction; an `UNKNOWN` answer is reconciled by inquiry and **never re-filed** (the payout
   precedent). `RegulatoryReportFiled` carries the report id and the jurisdiction — never the subject.
   *(As built by `P13-TSK-021`, 2026-10-25: the port refined - `declared(jurisdiction)` (adapter data, never a call),
   `file(request)` and `inquire(filingReference)`, outcomes `ACKNOWLEDGED | REJECTED | UNKNOWN | NOT_RECEIVED`; **the filing
   reference is the report's id**, known before the call and deduplicated on by the provider, so a lost answer is inquired
   by it. The drive is T-act's three steps - the claim `FOR UPDATE SKIP LOCKED` on the `FILED` row's due permit (one
   attempt, a lease ahead) committed, the call on no connection as the platform, the conditional record - and its **first
   attempt submits while every later one inquires**, submitting under the same reference only when the authority answers
   `NOT_RECEIVED` (nothing arrived: the filing is still owed, never a second); `UNKNOWN` keeps `FILED` with the permit an
   inquiry interval ahead. The filing door drives after its commit; the re-drive is the **second leg of
   `RiskActionSchedule`** (no new scheduler). The simulated FIU (`app`'s `SimulatedFilingProvider`) is stateless and
   deterministic by `finapp.risk.filing.simulation`, its references derived from the filing reference so every instance
   agrees; the EU reference's additions - the report type, the legal basis "to be verified" - are its rendering's.)*

7. **What was researched for the EU reference, to be verified when a jurisdiction is chosen** (C4; plan §12.9).
   Regulation (EU) 2024/1624 (the AMLR) Article 69 is understood to place the duty to report suspicions to the
   national FIU on the obliged entity's own initiative, with no amount threshold, including attempted transactions
   and cases where due diligence could not be completed; FIU information requests are generally answered within five
   working days, shortenable in urgent cases; AMLA is consulting on implementing technical standards under
   Article 69(3) for the reporting format and transaction records. The regulation's general application date is
   understood to be 10 July 2027 and the tipping-off prohibition to be Article 73 — both from general knowledge and
   **to be verified against the official text** at eur-lex.europa.eu/eli/reg/2024/1624/oj. FATF Recommendation 20
   requires prompt reporting of suspicions to the FIU; Recommendation 21 protects good-faith reporters and prohibits
   tipping-off. Secondary sources consulted on 2026-10-22:
   [springlex.eu — AMLR Article 69](https://www.springlex.eu/en/packages/aml/amlr-regulation/article-69/),
   [AMLA — public hearing on the draft ITS for reporting suspicions (2026-09-09)](https://www.amla.europa.eu/events/public-hearing-draft-its-format-reporting-suspicions-and-providing-transaction-records-2026-09-09_de),
   [paytechlaw.com — minimum content requirements (GwGMeldV)](https://paytechlaw.com/en/flood-of-suspicious-reports-introduction-of-minimum-content-requirements-with-the-gwgmeldv/),
   [crf.public.lu — guidelines on cooperation with the FIU](https://crf.public.lu/en/actualites/guidelinescooperation.html),
   [financialcrimeacademy.org — confidentiality of SARs and tipping-off](https://financialcrimeacademy.org/confidentiality-of-sars-and-tipping-off/),
   [thevoiceslu.com — FATF Recommendation 21](https://thevoiceslu.com/?p=67132). None of this is a platform rule:
   the report is an abstraction — a jurisdiction code, a format version, the content categories above, an adapter —
   and the German minimum-content rules (GwGMeldV) are an example of what an adapter adds. The reference jurisdiction
   stays the neutral EUR one; every field a real jurisdiction adds is adapter data.

8. **Rescreening on list updates** (plan §12.10, `P13-TSK-022`, cuttable). A screening list version (sanctions,
   PEP) is an operator-recorded fact under `RISK_POLICY_ADMINISTER` (`risk V012`; `UNIQUE (list, version)`); its
   activation enqueues one rescreening permit per `ACTIVE` party; the sweep's rescreening leg requests the screening
   through kyc's existing ports (`CounterpartyScreenings` for counterparties; the case-bound screening for parties),
   records the clearance as a signal and raises an alert on a hit. `risk` asks, kyc screens (OQ-8); `risk` never
   calls a list provider.

9. **Tipping-off, applied to monitoring** (`INV-RSK-08`). No AML alert, case, report or evidence reaches a customer
   or merchant surface, a log, an event payload or a metric tag; `AlertRaised` carries an alert id, kind, severity and
   subject reference; the report's existence is never derivable from a response — a timing difference being outside
   this phase's claims (plan §11).

10. **Would this remain correct with ten instances?** Ten sweepers over one watermark claim it once by
    `SKIP LOCKED` and ingest each entry once by `UNIQUE (journal_entry_id)`
    (`MonitoringIntakeDatabaseTest#tenSweepersIngestOnce`); two instances raising one alert write one row
    (`AlertDatabaseTest#twoInstancesRaiseOnce`); the profile recomputed by two sweepers writes one version under the
    head row lock (`ProfileDatabaseTest#tenRecomputesOneVersion`); a re-run from any watermark creates nothing new
    (`#reprocessingCreatesNothingNew`). T-mon takes no money row, so it waits on nothing a money transaction holds,
    and a money transaction never takes a watermark, profile head, alert or case row — so the sweep cannot breach the
    synchronous budget except by contention on velocity rows, which the storm measures beside T-eval (M3's trigger).
    Every window boundary, deadline and value-date judgement is the database clock's, read after the lock.
    `finapp.risk.monitoring.lag` (journal head − watermark) is the gauge that says the sweep is behind, never a
    per-instance timer.

## Alternatives Considered

### Monitor from Kafka events
Pros: no port over the ledger; the consumers already exist.
Cons: an event is a hint published at-least-once with the fields its producer chose; a movement whose event was
never published, or whose payload omits a counterparty, is unmonitored; the monitoring record would be a second
account of money free to disagree with the journal. Refused: events pull the permit forward; the sweep reads the
journal.

### Monitor from each module's own tables (transfers, payments, crossborder, lending, bnpl)
Cons: N readers of N schemas, each a `risk → module` edge ADR-0107 refuses, each missing the movements posted by a
module not yet listed. Refused: the journal is one record of all of them, reached through one port.

### A leader-elected monitoring sweeper
Cons: a leader is a process-local fact and a single point of lag; its election is coordination the platform has
refused since ADR-0024. Refused: leaderless `SKIP LOCKED` claims, every instance sweeps.

### The case is the report
Refused (OQ-7): different approvers (`AML_REPORT_*` versus `RISK_*`), different content rules, different retention,
and a filed artefact must be immutable while a case is worked; one aggregate would file whatever was investigated.

### A real FIU adapter and a jurisdiction-specific report shape
Refused (C4): the reference jurisdiction is neutral and no real provider is connected in any phase
(`DECISIONS.md` §Deferred); a simulated endpoint behind the port gives the same design pressure. The research is
recorded with its caveats so that a real adapter starts from verified text, not from this ADR.

### Drop or defer unclassified journal entries
Cons: an unmonitored namespace is exactly the gap a new module would open silently. Refused: `UNCLASSIFIED` is
recorded and alerted.

### `risk` calls the screening provider for rescreening
Refused (OQ-8; Refinement 3): kyc owns screening evidence and its retention; `risk` asks through kyc's ports and
records the clearance as a signal.

### Self-correct a velocity window that disagrees with the proof
Cons: a correction without a reason hides the defect that caused it. Refused: a difference is an alert; the window is
derivable and the fix is a person's.

## Consequences

Positive:
- Nothing moves money on this platform without becoming a monitored transaction; a new posting namespace is an
  alert, not a blind spot; the monitoring record is provably the journal's.
- Reprocessing is safe at any time, so a policy change can be applied to history without a migration.
- The filing is a distinct, immutable, acknowledged artefact with a jurisdiction field; the investigation stays the
  platform's own.

Negative:
- The sweep lags the journal by its permit interval; `monitoring.lag` and its alert are the operational cost.
- The EU research is secondary and dated; a real jurisdiction must verify every claim in point 7 before a real
  adapter is written.
- The classification register is a list that must be kept: every new posting-key namespace needs a row, or it alerts.

Operational impact: `RiskMonitoringSchedule` with its gauge and claim-failure meter; `finapp.risk.monitoring.lag`,
`monitoring.unclassified`, `velocity.proof{verdict}`, `report{status}`; alerts on lag over an hour, unclassified > 0,
a failing proof and a filing `UNKNOWN`; reprocessing as an audited operator act.
Security impact: `AML_OFFICER` with `AML_REPORT_APPROVE` and `AML_REPORT_FILE`, four eyes and step-up on approval and
filing; the narrative `RESTRICTED-PII`; the report never on any customer surface, log, event or tag.
Financial impact: none — the monitor reads the journal and posts nothing; `VelocityProof` and the census are
reconciliation of `risk`'s records against the ledger, not of money.

## Invariants / Constraints

`INV-RSK-09` (every monitored transaction born once from a journal entry; the record never diverges; reprocessing
idempotent), `INV-RSK-06` (born-once alerts and cases), `INV-RSK-03` (windows equal the monitored transactions they
count), `INV-RSK-08` (tipping-off), `INV-RSK-10` (the profile versioned, append-only), `INV-RSK-07` (report approval
is two persons' act), `INV-EVT-04`, `INV-IDEM-04`, `INV-HIST-02` (the filing's evidence retained), ADR-0024,
ADR-0107, ADR-0109, ADR-0110.

## Follow-up

- `P13-TSK-019` — the journal intake, the watermark and reprocessing (`risk V004`); `P13-TSK-020` — the monitoring
  rules and AML alerts; `P13-TSK-021` — the AML case and the regulatory report (`risk V011`); `P13-TSK-022` —
  rescreening (`risk V012`, cuttable); `P13-TSK-023` — the census, `VelocityProof` and the explanation door.
- `P13-TST-001` runs scenarios 2, 8 and 12 with counts and measures the sweep beside T-eval (M3's trigger).
- When a reporting jurisdiction is chosen: verify point 7 against the official text, write the adapter, and record
  every added field as adapter data in a dated amendment here; `DECISIONS.md` §Deferred "Jurisdiction-specific
  compliance" stays open until then.
- `RISK_LIFECYCLES.md` §Regulatory report and §Monitored transaction are the machines' record; the classification
  register is `P13-TSK-019`'s and is corrected here as built.
- *(As built, `P13-TSK-023`, 2026-10-26 - decision 1's census.)* `MonitoringCensus` proves monitored = journal by sequence one UTC day at a time: the journal's entry ids of the day through `MonitoredLedger.entryIds` (`app`, the journal's primary key) against the monitored rows' `journal_entry_id`s, as sets - a lost entry and a phantom row never cancel out - bounded by the watermark (pending behind it, ahead of a reprocessing). Decision 6's filing has its census too: `FILED` reports over `regulatory_report_due`, a report whose permit came more than one inquiry interval ago overdue. Both name a difference; nothing is corrected.
- **Acceptance.** `Proposed` at the Phase 12 → 13 transition (2026-10-22); the Phase 13 review (`P13-DOC-001`) reads
  this ADR against the code, corrects it in place with dated notes, and accepts it.
