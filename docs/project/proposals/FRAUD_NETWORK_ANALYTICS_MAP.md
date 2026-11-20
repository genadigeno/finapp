# Fraud Network Analytics: Implementation Map

**Status:** a proposal for the owner's decision. Nothing here is approved, scheduled or built, and `BACKLOG.md`,
`ROADMAP.md` and `CURRENT_STATE.md` are unchanged. Every `NET-*` slice, table, rule kind, invariant and ADR number below
is proposed.

**Source:** Kamal Kishore, *How to Detect Financial Fraud Patterns with Knowledge Graphs: A Production Guide*,
freeCodeCamp, 2026-10-30
([article](https://www.freecodecamp.org/news/how-to-detect-financial-fraud-patterns-with-knowledge-graphs-a-production-guide/)).

**Read against:** master `37677fc6` (Phase 14, 15 of 24). Section 11 lists what was not verified. A bare "item N" means
`PHASE_13_PLAN.md` §1.2's numbered owner items.

---

## 1. Summary

The article's useful idea is that some fraud only shows in the relationships between parties. That applies here. All
22 `RuleKind`s judge one subject at a time, so the platform has two blind spots:

- **It cannot see that several alerts are one scheme.** In a ring of A to B to C to D to A, `RAPID_IN_OUT` may fire on
  each party, but the alerts fold into four unrelated `AML` cases and no investigator sees the ring.
- **It cannot count relationships.** `VELOCITY_COUNT` on `DESTINATION` counts movements, so 25 senders paying one
  collector look the same as one sender paying it 25 times. No rule reads a shared identifier at all.

Recommendations:

1. **Adopt the idea, not the stack.** The article is about accounts-payable fraud, and its queries have defects
   (section 2). Decisions already made rule out its ML classifier, its batch loader and any Neo4j call on the money path.
2. **Build it inside `risk`, on PostgreSQL.** It is a new asynchronous leg on `RiskMonitoringSchedule`, with no new
   module, datastore or build edge.
3. **Reuse the machinery that exists.** The flow record, versioned policy, born-once alerts, cases and four-eyes
   dispositions are already built. The new parts are relationship tables, their readers and a few rule kinds.
4. **The biggest gap is data, not graph technology.** The article's headline pattern is many payers funnelling to one
   bank account. It cannot be detected for external accounts today, because `INV-RAIL-03` deliberately leaves the
   platform unable to tell that two references are one account (section 4).
5. **Build asynchronous first, and touch the money path last.** Reading a network result in T-eval is blocked on a
   latency measurement that does not exist yet (`PI-004`, `PHASE_13_PLAN.md` §1.2 items 76 and 95).
6. **No planned phase can take this.** Phases 15 and 16 exclude new business capability, so the recommendation is a new
   Phase 17 (section 9, D1).

---

## 2. How far to trust the article

It is a good introduction to the technique and not a production design, despite its title. Its evidence is one
synthetic dataset (seed 42) with planted patterns, scored on recall alone. It reports no precision and no test on clean
data. The queries below were re-checked against the article's own code.

| The article claims | Its code does | What that means here |
|---|---|---|
| Circular detection finds invoicing loops | A triangle over `SHARED_ACCOUNT_WITH`. The model has no vendor-to-vendor money edge | This is shared-account detection, not cycle detection. A real cycle needs real flow edges |
| The duplicate check handles near-identical amounts | `i1.amount = i2.amount`, exact equality | The description and the code disagree. Not relevant here (section 3) |
| The collusion score finds colluders | Matches outgoing edges only, but stores each pair once, low id to high id, so the higher-id vendor misses its `SHARED_ACCOUNT_WITH` count. Its validator reports 89 flagged, in a dataset of 100 vendors | Do not copy the score. Flagging most of the population is not a signal |
| Mule detection works on fan-in | Counts distinct vendors per account, with no time window and depth one | Keep the distinct count. Add a window and the onward movement |
| Supernodes are handled by pruning high-degree nodes | Skips accounts above a degree limit | This hides the largest collectors. Here a hub becomes a finding, not a filter |
| Inline checks are "sub-millisecond", at "millions of transactions daily" | Neither is measured | Treat as unproven. Section 5.2 sets a measured gate |

**Worth keeping:** materialising relationships at ingestion, separating a fast gate from slower scoring, feeding analyst
outcomes back as ground truth, and running a few high-signal rules rather than many.

**Not covered, and required here:** time on edges, concurrency, replay, tipping-off, privacy, audit and false-positive
measurement.

---

## 3. Translating the article to this platform

| Article | Platform | Verdict |
|---|---|---|
| `Vendor` | `Party`; a `Merchant` through its `party_ref` | Translate |
| `Invoice` | Nothing; there is no accounts-payable domain | Drop |
| `Payment` | `risk.monitored_transaction`, born once from the journal and proven by census (`INV-RSK-09`) | Reuse |
| `BankAccount` | Internal: a customer account. External: an opaque provider reference only | Internal reuse, external gap |
| `Organization` | The merchant's organisation party and `kyc.beneficial_owner` | Reuse |
| `Employee` + `APPROVED` | An operator identity and its four-eyes acts | Optional (`NET-9`) |
| Derived vendor edges | `party_flow` and identifier nodes (section 5.3) | New |
| Stage 1, synchronous gate | T-eval | Read one precomputed row, never traverse (`NET-7`) |
| Stage 2, asynchronous features | A new leg on `RiskMonitoringSchedule` | New |
| Risk score | `RISK_ENGINE_V1`'s explainable sum of named contributions | Extend with new kinds |
| Alert / approve / escalate | Alert, Case, Disposition, Override (ADR-0108, ADR-0110) | Reuse |
| `:ConfirmedFraud` label | `CONFIRMED_FRAUD` and `CONFIRMED_SUSPICIOUS`, two persons' acts | Reuse as seeds |
| ML classifier on graph features | Refused: `DECISIONS.md` §Deliberately Deferred, ADR-0109, `PHASE_13_PLAN.md` §20 | Out |
| Batch JSON with `UNWIND` | A watermark sweep over born-once rows (ADR-0111) | Out |

**The article's five patterns**

| Pattern | Here | Action |
|---|---|---|
| Duplicate invoices | No invoices. Duplicate commands are idempotency's job, and duplicate external items are reconciliation's | Drop |
| Circular rings | A real need that the article's query does not meet | Rebuild as `CIRCULAR_FLOW` |
| Money mule | Real, and the highest value | `FAN_IN_DISTINCT`: internal first, external after `NET-6` |
| Vendor collusion | The idea of shared attributes applies, but not the summed score | `SHARED_IDENTIFIER`, on exact identifiers only |
| Approval anomalies | Prevented already by permissions and four eyes | Optional detective control (`NET-9`) |

Three patterns the article omits are also worth having: `FLOW_CHAIN` (layering), `FAN_OUT_DISTINCT` (distribution) and
`CONFIRMED_PROXIMITY` (closeness to a confirmed case).

---

## 4. What can actually be linked today

This table decides the sequence. Link analysis needs the same real-world thing to have the same value across parties.

| Relationship | Source | Linkable today? | Note |
|---|---|---|---|
| Customer-to-customer money flow | `risk.monitored_transaction` (`transfer:`), `MonitoredParties` | **Yes** | One counterparty per entry, the first line off the subject. `NET-1` must confirm a `transfer:` entry has no further customer line |
| Saved internal payee | `transfers.beneficiary` (`party_id`, `destination_account_id`) | **Yes** | Exact |
| Beneficial owner across merchants | `kyc.beneficial_owner.owner_party_id`, `merchant.merchant.party_ref` | **Yes** | Exact, and a high-value link |
| Confirmed outcome | `risk_case` disposition | **Yes** | The seeds for proximity |
| Verified email | `identity.contact_channel.address` | **Yes, as a keyed digest** | The unique index is per identity, so two identities can verify one address. Households make this a weak signal |
| Client IP at credit acceptance | `client_ip` on lending's and bnpl's acceptance rows | **Needs a decision** | Collected as acceptance evidence, so fraud use is a purpose question. NAT also makes it weak (D6) |
| Card payer to merchant | Not in the journal: a capture debits clearing and credits merchant payable (ADR-0050). Risk's sealed assessment names the payer | **Partly** | Judged payments only |
| One card across parties | `payment_method.token_reference` | **No** | Nothing promises two parties' tokens for one card are equal |
| One external account across parties | `destination_reference` on `crossborder.beneficiary`, `payments.withdrawal`, `merchant.payout_destination` | **No** | Opaque by design (`INV-RAIL-03`, ADR-0080). Needs a provider-attested fingerprint (`NET-6`) |
| Device | `identity.session.device` | **No** | Free text, and risk receives none (item 4). Hashing a `User-Agent` gives everyone on one browser build the same value |
| Postal address, tax id, routing code | Not held | **No** | `INV-RAIL-03` forbids routing codes, so the article's routing-prefix edge is impossible here |

**The consequence:** internal flows and ownership links can be built now. External mule funnels and shared-card rings
need `NET-6`, and shared-device rings need the device provider that `PHASE_13_PLAN.md` §1.2 item 4 defers to Phase 15 at
the latest.

---

## 5. Proposed design

### 5.1 Where it sits

Bounded context: Fraud and AML in `risk` (`MODULE_ARCHITECTURE.md` M3). Build edges stay `risk → platform, sharedkernel`.
Other modules' facts arrive through a port that `risk` declares and `app` implements, as `MonitoredLedger` does.

```
 ledger journal ──MonitoredLedger──> monitored_transaction ───────┐
                                     (exists, INV-RSK-09)         │
                                                                  v
 transfers, kyc, identity ──LinkFacts──> link_identifier    party_flow
 merchant, providers (NET-6)             + party_link             │
                                               │                  │
                                               v                  v
                           T-net-judge: bounded reads, pure evaluation
                                               │
                                               v
                              network_finding + finding_member (frozen)
                                     │                          │
                       T-net-raise   v                          v   derived, NET-7
                 risk_alert ──> risk_case ──> disposition    party_network_reading
                 (existing: born once, one open, four eyes)     │
                                     │                          v   one row, sealed
                                     └── seeds ──> (back)     T-eval (money path)
```

### 5.2 PostgreSQL, not a graph database

**Recommendation: PostgreSQL.** A graph engine could never serve the synchronous path, and the asynchronous path does
not yet need one.

| Option | Assessment |
|---|---|
| **PostgreSQL tables and a pure Java evaluator** | **Recommended.** It keeps every read inside the `risk` schema, needs no new infrastructure and fits explicit SQL (ADR-0033) |
| Neo4j as a derived projection | In T-eval it is the out-of-process call ADR-0107 and ADR-0108 refuse. Elsewhere it adds a second store to back up, secure, verify (ADR-0025) and reconcile. Clustering may need a licensed edition (not verified) |
| A graph extension such as Apache AGE | No migration in the repository uses `CREATE EXTENSION`, so this adds a new supply-chain and upgrade dependency for syntax alone |
| An in-memory graph in the JVM | Refused by ADR-0024 |

**Traversal runs in pure Java, not in recursive SQL.** The readers fetch a bounded neighbourhood one hop at a time
(`ORDER BY posted_at, id LIMIT cap`, under a statement timeout), and a pure function evaluates it. Logic inside a
recursive CTE could not join the hermetic two-JVM battery that `RISK_ENGINE_V1` is held to (ADR-0109 decision 3). This
also avoids the global cycle scans the article warns about, because a search starts from one new edge rather than the
whole graph.

**The gate:** `NET-0` measures this on PostgreSQL 18.6 at a stated volume. It is revisited only with evidence: the p99
misses its target after index work, or production genuinely needs more than six hops or global community detection. Even
then, a graph store is an asynchronous projection and never an authority.

### 5.3 Data model

All tables are in the `risk` schema, with no cross-schema foreign key (ADR-0029) and no `DELETE` for any role.

| Table | One row per | Born-once key | What can change |
|---|---|---|---|
| `party_flow` | Customer-to-customer monitored transaction, resolved to parties | `UNIQUE (monitored_transaction_id)` | `judged_at` |
| `link_identifier` | A shared thing: saved payee, owner, email digest, later a fingerprint | `UNIQUE (kind, key_id, value)` | Nothing |
| `party_link` | One party observed with one identifier | `UNIQUE (source_kind, source_ref, party_id, identifier_id)` | `judged_at` |
| `network_finding` | One detected pattern, with its frozen path, policy and engine versions | `UNIQUE (rule_code, canonical_digest, window_start)` | Nothing |
| `network_finding_member` | One party in a finding | `PRIMARY KEY (finding_id, party_id)` | `alert_id`, `next_attempt_at` |
| `party_network_reading` (`NET-7`) | Party: distance to a confirmed case, computed-at | `PRIMARY KEY (party_id)` | Forward-only upsert; rebuildable |

Five choices here are deliberate:

- **Identifiers are nodes, not pairwise edges.** The article's pairwise shape grows with the square of the parties
  sharing a value. Here each party adds one row.
- **`party_flow` copies only what the recursion needs.** `MonitoredParties` is a port, and a query cannot cross a port.
  Resolving parties up front keeps every traversal inside `risk`. The row keeps the `ADR-0003` money columns and is never
  summed into a balance.
- **Links are historical facts and are never retracted.** A removed beneficiary is still evidence, since removal is a
  common evasion. Rules bound their reach with `lookback_days`, and retention belongs to Phase 15's policy.
- **Paths stay in one currency.** A path does not cross a currency in the first version. Ratios use integer basis points
  by cross-multiplication, never floating point.
- **Raw values never enter `risk`.** Email and fingerprints arrive as HMAC digests under a managed key with a key id
  (ADR-0020). The key is platform-wide, because a per-party salt would make matching impossible.

### 5.4 New rule kinds

Parameters are data, as in ADR-0109. New kinds mean `RISK_ENGINE_V2`, with V1 kept for replay.

| Kind | Scope | Fires when | Slice |
|---|---|---|---|
| `FAN_IN_DISTINCT` | Monitoring | At least N distinct parties pay one party in a window, summing to at least X | `NET-2` |
| `FAN_OUT_DISTINCT` | Monitoring | The mirror image, one party paying at least N distinct parties | `NET-2` |
| `FLOW_CHAIN` | Monitoring | Money moves through at least K parties, each hop within H hours and keeping at least R basis points of the last | `NET-3` |
| `CIRCULAR_FLOW` | Monitoring | The same chain returns to its start | `NET-3` |
| `SHARED_IDENTIFIER` | Monitoring | At least N other parties share an identifier of a named kind within a lookback | `NET-4` |
| `CONFIRMED_PROXIMITY` | Monitoring | Within N hops of a party whose **latest** disposed case is `CONFIRMED_*` | `NET-5` |
| `PAYEE_NETWORK_PROXIMITY` | Judged | The payee's sealed distance to a confirmed case is at most N, and its reading is fresh | `NET-7` |

"Latest" matters because a case never re-opens (ADR-0110). Without it, one disproved confirmation would remain a seed for
ever.

**Rules start in observe mode.** Findings are recorded but raise no alert. Moving to alerting is a new policy version
under four eyes. Today every fired AML kind alerts (item 36), and an unproven network rule would flood the queue. This
per-rule mode also settles item 36.

### 5.5 Transactions and locks

| Transaction | Takes, in order | Holds no |
|---|---|---|
| **T-net-ingest** | Its own watermark row (a new source on the existing table) `FOR UPDATE SKIP LOCKED`, then born-once inserts, the advance and the commit | Case, profile or money row |
| **T-net-judge** | Due `party_flow` or `party_link` rows `FOR UPDATE SKIP LOCKED`, the `MONITORING` version `FOR SHARE` (R1), plain bounded reads, born-once findings and members, then `judged_at` | Case, profile or money row |
| **T-net-raise** | One due member row `FOR UPDATE SKIP LOCKED`, then the existing raise: alert (R5), case (R4), attach (R5) | Money or profile row (never R8), and one party's case at most |

**Why three transactions:** `MonitoringRun` deliberately commits rules inside the journal claim, and a slow traversal
there would stall AML intake (item 37). Raising one member per transaction keeps T-eval's existing untimed wait on a case
row (item 95) as short as possible. It adds one more short holder of R4, which `NET-1`'s metrics must watch.

**Timing cannot hide a ring.** Ingestion is serial under the watermark claim, and judgement starts only after ingestion
commits. So the last-ingested edge of any ring sees all the others, whichever instance judges it. If two edges both find
the ring, `canonical_digest` makes them one finding, because its window comes from the path's own edges and never from a
run's clock. The row caps and timeouts can still hide a ring, which is why section 6 counts them.

**Would this remain correct with ten instances? PASS by design, owed by test:** one claimant per watermark, one winner
per due row, every row born once, no process memory, and time read from stored rows. Sections 7 and 8 list what proves it.

### 5.6 The synchronous reading (`NET-7`)

T-eval never traverses. It reads one `party_network_reading` row by primary key, under the existing savepoint and 250 ms
statement timeout, and seals the value and its age as an input, so replay stays `IDENTICAL` (`INV-RSK-01`). It takes no
lock and adds no row to the lock order. A missing or stale row reads `ABSENT` and does not fire, so the money path never
depends on the sweep being healthy. The seams do not change.

### 5.7 Proposed invariants

| Proposed | Statement | Enforced by |
|---|---|---|
| `INV-RSK-11` | A network edge is born once from a recorded fact and names it. It is a projection, never financial truth. A census compares it with its source, and a difference is named, never self-corrected | Uniques, census, Prometheus rule |
| `INV-RSK-12` | A finding freezes its path, policy and engine versions. Reprocessing creates none. Explanation reads those rows and never re-traverses | Trigger, unique, battery |
| `INV-RSK-13` | **Association alone never refuses money.** An association kind (`SHARED_IDENTIFIER`, `CONFIRMED_PROXIMITY`, `PAYEE_NETWORK_PROXIMITY`) carries a weight and no hard decision, and the policy door refuses a version whose association weights alone reach a refusing band | Policy door (`risk.PolicyInvalid`), battery |

`INV-RSK-13` needs the owner's decision (D4). Its reason: `REVIEW` also refuses the movement (ADR-0108), and being
connected to someone is not evidence of what a person did. Shared households, flatmates and family businesses are
ordinary. A person still decides through a case (`INV-RSK-07`).

`INV-RSK-08` (tipping-off) extends to the new tables without a new invariant.

### 5.8 Security, audit and operations

- **Investigators:** a network read sits under `RISK_INVESTIGATE`, is bounded, and is audited as `risk.NetworkRead`. Self-
  dealing is judged for **every party served**, the precedent set by item 79, because a path names other people.
- **Classification:** the pairing is the sensitive fact, so flows and links are at least `CONFIDENTIAL`, and digests are
  `RESTRICTED-PII` by `DATA_CLASSIFICATION.md`'s possession-oracle reasoning. Every column needs a register row.
- **Effects:** a finding proposes nothing. Any action is a person's proposal on the case, through the owning domain
  (ADR-0112).
- **Metrics:** `finapp.risk.network.lag{source}`, `.finding{rule,mode}`, `.traversal.latency{rule}`, `.truncated{rule}`,
  `.hub{kind}`, `.unjudged`. Tags are bounded, and amounts never enter a metric (ADR-0072). Each alert gets a runbook
  entry in `OPERATIONS_RUNBOOK.md` §9.
- **Reconciliation:** the censuses compare `risk`'s records with their sources. No money is involved.

---

## 6. Failure behaviour

| What happens | Result |
|---|---|
| A monitored row or link fact is delivered or read twice | The unique absorbs it: no second edge, finding or alert |
| An instance dies during judgement | The lock releases, `judged_at` stays null, and the next claim re-judges into the same keys |
| An instance dies between judging and raising | The member row stays due and the next sweep raises it. The alert key is born once |
| A traversal hits its timeout | Undone by its savepoint and retried with backoff, then counted and alerted past a bound. Other work continues |
| A hop exceeds its row cap | The read is deterministic, the truncation is recorded and counted, and it never reads as "clear" |
| An identifier exceeds its hub cap | No fan-out through it, and the hub is reported to operators as its own signal |
| A flow is later reversed | Traversal excludes it from then on. An earlier finding stands as what was seen |
| A policy activates mid-run | Each judgement pins one version (R1), recorded on its finding |
| A disproved party was a seed | It stops seeding once its latest disposed case is no longer `CONFIRMED_*`. Existing alerts stand, and a person closes them |
| A posting commits past the settle horizon | The same bound as `MonitoredLedger` (item 92): the census names it and reprocessing recovers it |
| Reprocessing runs | Everything is born once, so nothing new appears, as with `INV-RSK-09` |
| A provider sends no fingerprint | The link is not recorded, the rule reads `ABSENT`, and the gap is counted |
| The sweep stalls while T-eval runs | Readings age to `ABSENT`, movements are judged without them, and the lag alert fires |

---

## 7. Implementation map

Sizes are relative. Migration numbers are assigned at build, and `V019` is the latest in `risk` today. Every slice runs
the design, implement and gate loop, and each one's multi-instance answer must be `PASS`.

```
 NET-0 decisions, ADR, volume spike
   └─> NET-1 flow edges and the leg
         ├─> NET-2 fan-in/out, engine V2, findings, observe mode
         │     └─> NET-3 chains and cycles ───────────┐
         └─> NET-4 identifier links (needs NET-2)     ├─> NET-5 proximity, investigator view
               └─> NET-6 provider fingerprints        │         │
                                                      │         └─ + PI-004 figures ─> NET-7 T-eval reading
   cut candidates:  NET-8 merchant collusion    NET-9 operator pairs
   all kept slices ─> NET-10 battery, exit review
```

| Slice | Delivers | Mainly touches | Needs | Profiles | Size | Done when |
|---|---|---|---|---|---|---|
| **NET-0** | Owner decisions; ADR-0119; ADR-0109 amendment; the phase plan and gate; the volume spike | `ROADMAP`, `DELIVERY_PLAN`, `PHASE_GATES`, `DECISIONS`, `BOUNDED_CONTEXTS`, `FINANCIAL_INVARIANTS` | Phase 16 closed, if D1 is accepted | `DOD-ARCH`, `DOD-DOC` | M | The spike's figures are recorded and PostgreSQL is confirmed or refused on them |
| **NET-1** | `party_flow`, its watermark source, T-net-ingest as a schedule leg, backfill, census, lag gauge | `risk` migration, `MonitoringIntake`'s shape, `app`'s `RiskMonitoringSchedule`, `CensusKind` | NET-0 | `DOD-DOMAIN`, `DOD-OBS`, `DOD-TEST` | M | Ten sweepers ingest once; census equals the source both ways; reversals are excluded; self-transfers are counted and skipped |
| **NET-2** | `FAN_IN_DISTINCT`, `FAN_OUT_DISTINCT`; `RISK_ENGINE_V2`; observe mode; findings and members; T-net-judge and T-net-raise; finding evidence through `AlertEvidence` and `CaseEvidence`; a per-rule report | `RuleKind`, `RiskEngine`, policy door, `risk_alert` CHECK, `RiskOperationsReports` | NET-1 | `DOD-DOMAIN`, `DOD-SEC`, `DOD-OBS`, `DOD-TEST` | L | V1 replays `IDENTICAL`; an observe-mode rule raises nothing; every member's alert is born once; a crash between judge and raise loses none |
| **NET-3** | `FLOW_CHAIN`, `CIRCULAR_FLOW`; the pure `FlowPaths` evaluator; hop-by-hop reader; canonical path | `risk` | NET-2 | `DOD-DOMAIN`, `DOD-TEST` | L | A ring is found whichever edge arrives last; two discoveries make one finding; two JVMs agree byte for byte |
| **NET-4** | `LinkFacts` port and `app`'s implementation; identifier tables; HMAC digests; saved payee, beneficial owner and verified email sources; `SHARED_IDENTIFIER`; hub handling | `risk`, `app`, published views in `transfers`, `kyc`, `identity`, `merchant` | NET-2 | `DOD-SEC`, `DOD-DOMAIN`, `DOD-TEST` | L | No raw value reaches `risk` (guard); census per source; a hub is reported, not skipped |
| **NET-5** | `CONFIRMED_PROXIMITY`; seeds from dispositions; the audited network read door and related findings on the case file | `risk`, `app` controller, OpenAPI, `ERROR_CONTRACT`, `AUDITABLE_ACTIONS` | NET-3, NET-4 | `DOD-API`, `DOD-SEC`, `DOD-TEST` | M | An own-party reference anywhere in a path is refused; a disproved seed stops seeding; no customer surface reads it |
| **NET-6** | Provider-attested fingerprints on tokenisation, corridor beneficiaries, withdrawal instruments and payout destinations, with a dated `INV-RAIL-03` amendment; device once a provider exists | `paymentmethods`, `crossborder`, `merchant`, `payments`, simulated adapters and their contract tests | NET-4 | `DOD-SEC`, `DOD-DOMAIN`, `DOD-TEST` | L | External fan-in is detected; a missing fingerprint reads `ABSENT`; the column sweep still refuses account-shaped values |
| **NET-7** | `party_network_reading`; `PAYEE_NETWORK_PROXIMITY`; a new `EvaluationInputs` canonical format; `INV-RSK-13` at the door | `risk`, `PlatformRiskInputs` | NET-5, **T-eval figures from `PI-004`** | `DOD-FIN`, `DOD-DOMAIN`, `DOD-OBS`, `DOD-TEST` | M | T-eval's p99 stays within its 50 ms budget with the reading added, as measured; a stale reading never fires; an association-only refusal cannot be activated |
| **NET-8** *(cut candidate)* | Merchant collusion: payers concentrated on linked parties, or an owner paying their own merchant | `risk` | NET-5, item 35 (merchant monitoring) | `DOD-DOMAIN`, `DOD-TEST` | M | Legitimate loyal-customer and family-business populations stay under the flag budget |
| **NET-9** *(cut candidate, first)* | Operator pair concentration across four-eyes acts: the article's approver idea as insider-risk detection | `risk`, the audit trail, a new subject kind | NET-2 | `DOD-SEC`, `DOD-TEST` | M | Self-dealing holds when the subject is an operator; small-team baselines do not flag |
| **NET-10** | The battery (section 8); runbook; mutation register rows; exit review. The storm joins `PI-004` | Tests, `MUTATION_TESTING`, `OPERATIONS_RUNBOOK` | All kept slices | `DOD-TEST`, `DOD-DOC` | L | Flag rate is within budget on clean populations, with precision reported per rule |

**Each stage is useful alone.** Stopping after `NET-3` still gives real ring and mule detection on internal flows, and
stopping after `NET-5` adds ownership links and proximity. Nothing changes the money path before `NET-7`.

---

## 8. Testing

The article's validation is the main thing not to copy. It plants a pattern, finds it and reports recall.

- **Clean populations are mandatory, with a flag-rate budget per rule.** These look suspicious and are legitimate:
  flatmates splitting rent (fan-in), payroll and marketplace sellers (fan-out), family repayments and rotating savings
  groups (cycles), households sharing an email, and one owner holding several honest merchants.
- **Planted positives go beyond the easy shape:** patterns split across window boundaries, slow layering, amounts just
  under the retention ratio, and rings mixed into clean traffic.
- **The battery matches `P13-TST-002`:** an independent oracle, generated subjects, two JVMs byte-identical, and every
  perturbation flipping a verdict and naming its row. A committed fingerprint keeps it honest.
- **Database tests:** ten sweepers ingest once, two instances raise one alert, a crash between each pair of transactions,
  reprocessing creates nothing, an activation mid-judgement, and a census that detects both a lost and a phantom row.
- **Guards:** no raw identifier reaches `risk`; no customer surface reads the new tables (`NoCustomerSurfaceReadsRiskTest`
  extended); events, logs and metric tags stay code-only.
- **In production, before alerting:** run in observe mode and read the flag rate and the overlap with existing confirmed
  cases. No report does this per rule today: `RiskOperationsReports` covers decisions, alerts, cases and velocity.

---

## 9. Decisions for the owner

| # | Decision | Recommendation | Why |
|---|---|---|---|
| **D1** | Where this goes | **A new Phase 17**, after Phase 16 | Phases 15 and 16 exclude new capability. Its inputs arrive by then: the device provider (Phase 15) and the capacity model (Phase 16). `PI-004` waits for "after the phases", so this also needs a ruling that `P13-TST-001` runs before `NET-7`. The cost is that Phase 17's gate must repeat the threat-model, retention and PII reviews for its own tables. Inserting it before Phase 15 avoids that repeat, but disturbs the agreed sequence, and test guards read `PHASE_<n>_PLAN.md` paths, so a non-integer phase would need checking |
| **D2** | Storage | **PostgreSQL**, gated by `NET-0`'s spike | Section 5.2 |
| **D3** | Machine learning | **Stays out** | `DECISIONS.md` already defers it, and the article's value does not depend on it. Graph readings feed explainable rules |
| **D4** | `INV-RSK-13` | **Accept it** | Section 5.7. Otherwise a shared email could refuse someone's money |
| **D5** | Provider fingerprints | **Accept them, as a dated `INV-RAIL-03` amendment** | A fingerprint is still an opaque provider-issued value and no account identifier enters the platform. Without it, the article's main pattern stays undetectable for external accounts |
| **D6** | Personal data as links: verified email, and client IP collected at credit acceptance | **Email yes, as a digest. IP no for now** | IP was collected as acceptance evidence, and NAT makes it weak. Both need a privacy review before any real use |
| **D7** | Cut candidates | **Keep `NET-8` and `NET-9` out unless wanted** | `NET-8` needs item 35. `NET-9` is a useful exercise in insider risk but is the furthest from the article's core |

**Three small things worth settling sooner, whatever D1 says.** None of them changes Phase 14.

1. **`P13-REM-001` (keyed device hash):** keep one platform-wide key with a key id, as it now reads. A per-party salt
   would permanently prevent matching devices across parties.
2. **The same task's limit:** keying fixes reversibility, not identity. A keyed `User-Agent` hash is still shared by
   everyone on one browser build, so a useful link needs a stable device id from the Phase 15 provider.
3. **That provider's contract (item 4):** require a stable device identifier in it from the start.

---

## 10. Deliberately not doing

| Not doing | Why | What would change that |
|---|---|---|
| Neo4j or another graph store | Section 5.2 | `NET-0` or Phase 16 evidence, and then only as an asynchronous projection |
| A classifier, embeddings or a GNN | `DECISIONS.md`; not reproducible from sealed inputs | The owner reverses that decision |
| PageRank, Louvain, betweenness | The article names them but never runs them. They add floating-point scores and tuning to a deterministic path, and bounded distance and distinct counts come first | A measured gap that those simpler readings cannot close |
| Whole-graph clustering | Search from each new edge is bounded and enough to start | Investigators need population-level views |
| Fuzzy name matching | The platform avoids holding the data, and a false match accuses a stranger | A reviewed entity-resolution design |
| Location and routing-prefix edges | Weak signals, and `INV-RAIL-03` forbids routing codes | Not expected |
| A graph fed from Kafka | Events are hints (ADR-0024, ADR-0111) | Not expected |
| Automatic actions from a finding | Every action is two persons' act (`INV-RSK-07`, ADR-0112) | Not expected |
| A merged "network case" | A case has one subject (ADR-0110). Shared findings connect the cases for now | Investigators find separate cases unworkable |

---

## 11. What was not verified

- **No performance figure exists.** No query here has been run at volume, which is why `NET-0` has a spike.
- **The design is not implemented or tested.** Its "ten instances" answer is a design argument until section 8's tests pass.
- **Two facts need `NET-1`'s or `NET-6`'s design to confirm them:** that a `transfer:` entry has no further customer line,
  and that real providers would attest a stable fingerprint. Payment providers commonly offer one, but that comes from
  general knowledge, and the simulated adapters offer none.
- **The article's companion repository was not read,** so section 2 describes the article's published code only.
- **Nothing here is legal advice.** Linking people through shared data is profiling, and purpose limitation and automated
  decision rules need review by counsel before any real use. The platform stays jurisdiction-neutral (`ROADMAP.md`
  §Non-Goals).
- **Neo4j licensing** in section 5.2 comes from general knowledge and should be checked if that option is ever revisited.
