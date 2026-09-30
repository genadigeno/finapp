# Phase 8 — Settlement and Reconciliation

Written by the Phase 7 → 8 transition (2026-09-28). Decisions in ADR-0064 (settlement holds the
external evidence; reconciliation holds the expectations and the comparison — two modules, no
edge between them), ADR-0065 (each counterparty's clearing position is discharged in two evidence
hops; cash moves only on the bank's statement), ADR-0066 (raw settlement files are screened at the
door, authenticated by pull credential or second-person attestation, and retained encrypted in
PostgreSQL behind a port), ADR-0067 (every externally settling completion opens its expectation in
its own transaction), ADR-0068 (matching strategy, rule versioning and tolerance model), ADR-0069
(break taxonomy and lifecycle), ADR-0070 (suspense account policy and ageing), ADR-0071 (break
resolution authority and four-eyes thresholds), ADR-0072 (amounts never enter metrics: unmatched
value, suspense balance and provider costs are audited operator reports) and ADR-0073 (a payout
return is a merchant fact applied from settlement evidence; the payout's push-rail convergence
trigger did not fire) — each Proposed (2026-09-28, the Phase 7 → 8 transition) and indexed in
[`docs/adr/README.md`](../adr/README.md). ADR-0063 is reserved by an unmerged branch
(`X-TSK-005`), so this phase's numbering starts at ADR-0064 and its cross-cutting tasks at
`X-TSK-008`. The domain-facing statement of the machines is
[`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`](../domain/SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md);
the reconciliation model is [`RECONCILIATION_MODEL.md`](../domain/RECONCILIATION_MODEL.md),
rewritten by the transition. Until Phase 8's first task lands, **nothing in this plan is
implemented**: every statement is the decided design, corrected by the tasks that build it.

## 1. Objective

**Answer, continuously and provably, per counterparty, position, currency and item: does the
platform's internal financial state match what the card PSP, the instant scheme, the payout
provider and the platform's bank say happened — and make every disagreement a classified, aged
break with both sides preserved, resolved only by evidence or by a controlled, four-eyes
adjustment.** Concretely:

- every externally settling completion opens a tracked **settlement expectation** in the
  transaction that posted it;
- the counterparties' reports and the bank's statements arrive as screened, encrypted,
  authenticated evidence, are normalised into canonical lines, and are recognised once;
- each report's lines are allocated to the expectations deterministically, every decision storing
  what it saw;
- each clearing position is discharged in two evidence hops — the counterparty's report, then the
  bank — and cash moves only on the bank's own statement;
- value nothing explains is parked in `SUSPENSE_UNMATCHED`, each unit owned by a break, and a
  break is resolved by evidence or by a template-bound, reason-coded resolution posted through the
  ledger's adjustment machinery. Nothing is edited or deleted.

Phase 5 proved money can enter and leave against one unreliable third party; Phase 6 changed who
the money is for; Phase 7 changed how it travels. Phase 8 **moves no money externally**: it asks
whether what the platform recorded is what happened, and holds every money-moving flow fixed
apart from the one new operation the evidence requires — the payout return (ADR-0073).

**Scope, concern by concern:**

| Concern | Verdict | Precisely |
|---|---|---|
| Settlement | IN | The discharge of each external counterparty's clearing position in two evidence hops: the report (fees recognised, the remittance expected, items allocated), then the bank (cash recognised against that counterparty's own clearing) — §12.1, ADR-0065 |
| Settlement instructions | OUT | Phase 8 originates no external movement: no net-settlement instruction, sweep, prefunding or liquidity management. Payouts and withdrawals remain payment instructions (ADR-0057, ADR-0062). A stated non-goal, not a gap |
| Settlement results and batches | IN | Counterparty reports and bank statements as canonical settlement records; one single-currency settlement unit per file (a PSP day, a scheme cycle, a payout day, one statement per currency); a file declaring several batches is `UNSUPPORTED_FORMAT` |
| External files and ingestion | IN | Four simulated formats, screened at the door, authenticated by a pull credential or a second person's attestation; operator upload with attestation (M8.1) and scheduled pull (M8.7), both idempotent on content |
| Reconciliation sources and batches | IN | A compiled register of four sources composed in `app` from each counterparty's own declaration; one run per accepted batch, created in the acceptance transaction, plus `REPROCESS` runs |
| Normalization | IN | A pure format adapter produces canonical lines; provider vocabulary is confined to its adapter (`INV-PAY-03`'s discipline) |
| Matching and rules | IN | Key-based and allocation-shaped; claimants served in acceptance order; rules versioned per source, pinned, and snapshotted per decision |
| Tolerances | IN, narrowed | Processing fees against pinned terms, and dates. No tolerance can exist on an amount already in a position — the type is not representable (`INV-REC-08`) |
| Duplicates and unmatched records | IN | Duplicate deliveries, conflicting re-issues, duplicate lines within and across files, over-allocation, internal key collisions; both sides' unmatched records aged, parked or broken |
| Breaks, investigation, exceptions | IN | Fourteen classified, severity-tagged, aged break types, never deleted; the break's case file; exceptions as views (a refused delivery or rejected file, a break, a silent source, a blocked run) — no third aggregate |
| Suspense | IN | Automatic parking with an owning break; aged and alertable; released by evidence, four-eyes resolution or a correction offset; Phase 7's unmatched confirmations adopted; never permanent |
| Controlled adjustments and resolution | IN | Eight template-derived resolution kinds posted through `ledger.AdjustmentService` with origin `RECONCILIATION` and a closed reason code; reconciled positions closed to free adjustments; only `EVIDENCED` is the platform's |
| Reporting | IN, operational | Audited operator reports carry amounts; metrics carry counts, ages and verdicts (ADR-0072). No GL, close or statements |
| Replay and reprocessing | IN | Decision replay from stored snapshots; `REPROCESS` of residual items under a new rule version; idempotent re-delivery; readmission of a file our own defect rejected; later-day re-acceptance converges; four-eyes batch repudiation |

## 2. Why this phase is shaped by decisions already taken

- **Every clearing position was built to be discharged here, and nothing discharges one today.**
  `SETTLEMENT_CLEARING` (ADR-0048, ADR-0050 §2), `INSTANT_CLEARING` (ADR-0062 §4) and
  `PAYOUT_CLEARING` (ADR-0057 §8) only accumulate; `SUSPENSE_UNMATCHED` has one poster, the Phase 7
  unmatched confirmation, and no releaser; and the chart has no cash account for a discharge to
  post against. Phase 8 adds the counter-accounts, each arriving with its first poster (ADR-0040).
- **A rail declares how it settles** (ADR-0059 §4). `RailCapabilities.settlement()` and
  `clearingPurpose()` decide which completions open expectations and which position a source
  discharges; `SettlementModel.NONE` is `INV-SET-01`'s documented per-rail guarantee for the book
  rail. Settlement code keys on declarations, never on rail names (`INV-RAIL-01`,
  `RailVocabularyIsConfinedTest`), and the payout provider — not a declared rail — gains its own
  declaration, `merchant.PayoutSettlementDeclaration`.
- **Webhook evidence was kept for this, but it is not a source.** ADR-0047 §2 stores every callback
  verbatim so that Phase 8 reconciles what was said. Reconciling state against its own inputs
  proves nothing, so webhooks feed matching as keys and attributes (the ARN alias a key; the
  announced cycle an attribute of the expectation row, never a key, ADR-0067 §5) and stay
  reachable from a break through the operation's `payments.provider_evidence`. *(The Phase 7 → 8
  transition's consistency review, A5.)*
- **The ledger is the only balance authority, and its correction path is already four-eyes**
  (ADR-0002, ADR-0009; ledger `V010`, `AdjustmentService`). Reconciliation posts through
  `PostingService`, `ReversalService` and `AdjustmentService`, never beside them.
- **A posting's fingerprint binds its dates** (`PostingService.java:205-228`;
  `MerchantPayoutOutcomes.java:215-217`), so a clock-dated recognition replayed on a later day
  would conflict rather than converge. Every Phase 8 posting takes its posting and value dates from
  stored rows. The Phase 5–7 flows' clock-read posting dates are not retrofitted; `X-TSK-009`
  reconciles `DOMAIN_MODEL.md` and `LEDGER_MODEL.md` with that practice.
- **Posting deduplication must not rest on retained idempotency rows.**
  `ledger.journal_entry.idempotency_scope` is not unique, so a posting key's one-effect guarantee
  rests on `platform.idempotency_record`'s rows being retained. Every Phase 8 effect is guarded by
  a domain unique as well — the live batch, the run, the payout return, the park row — the "unique
  on (process, entity, period)" `INV-IDEM-02` asks for.
- **No metric may carry an amount** (ADR-0018; `MetricNames`' forbidden fragments). DELIVERY_PLAN
  §10's "unmatched value" and "suspense account balance" become audited operator reports
  (ADR-0072), the `payments.ChargebackRatioRead` precedent.
- **Evidence is encrypted per concern and kept in PostgreSQL behind a port** (ADR-0036;
  `INV-KYC-06`, `INV-DSP-03`; the `DisputeEvidenceKey` precedent). ADR-0066 re-assesses ADR-0036
  for settlement files with named triggers, and rules that `INV-PAY-02` and `INV-RAIL-03` take
  precedence over `INV-HIST-02` for a refused delivery.
- **Every correction is a new record** (`CLAUDE.md` rules 3 and 11; `INV-HIST-01`, `INV-REV-01`).
  A counterparty's correction is a new line; a mistaken acceptance is reversed through
  `ReversalService`; money moves to fix a difference only through an approved resolution.

### The transition's decisions, each the owner's to revisit

The design left seven questions to the owner. The transition settled each on the design's
recommendation and records it here; revisiting one amends the ADR and the tasks named.

| # | Question | Decided | Binds |
|---|---|---|---|
| O1 | One reconciliation role or two | Two pairwise-disjoint roles: `RECONCILIATION_OPERATOR` {`SETTLEMENT_INGEST`, `RECONCILIATION_INVESTIGATE`, `RECONCILIATION_RESOLVE`} and `RECONCILIATION_CONTROLLER` {`RECONCILIATION_ADMINISTER`} — whoever can loosen a tolerance cannot resolve the breaks it would hide | ADR-0071; `P8-TSK-003`, `-007`, `-015` |
| O2 | Payout returns automated or manual | Automated: a merchant fact applied by a leaderless worker, with a four-eyes `TRANSFER_TO_ACCOUNT` as the fallback when it cannot apply | ADR-0073; `P8-TSK-019` |
| O3 | Refuse PII-bearing files at the door, or retain them verbatim | Refuse at the door, screening by field class; a refusal keeps metadata only and is recovered by re-presentation | ADR-0066; `P8-TSK-002` |
| O4 | A non-zero opening cash balance | The simulated bank opens at zero; a non-zero first opening raises `SETTLEMENT_MISMATCH(OPENING_BALANCE)` with nothing posted; an equity account waits for Phase 14 | ADR-0065; `P8-TSK-016` |
| O5 | The gain minimum age | 90 days (`gain_min_age_days`, seeded in rule set v1), four-eyes | ADR-0070; `P8-TSK-015` |
| O6 | What to cut if scope must shrink | `P8-TSK-021`, then `-019`, then `-023`, each recorded with an owner; keep `-023` if possible | §16 |
| O7 | The high-value severity threshold | 1,000.00 per currency in rule set v1 — EUR, GBP and USD (`high_value_minor` 100000 at scale 2) | ADR-0069; `P8-TSK-004`'s seed |

### Phase 7's recorded inputs, disposed

| Input | Disposition |
|---|---|
| A return keeps no settlement cycle (ADR-0062's follow-up; `PHASE_7_REVIEW.md`) | Learned from the scheme's report and recorded on the item (`learned_cycle`, `P8-TSK-017`); no payments migration |
| The per-rail cost meter (`DECISIONS.md`; ADR-0060 §6) | `PROCESSING_COSTS` recognised from evidence plus the provider-costs report (ADR-0072); never a metric |
| ADR-0062 §7's payout convergence trigger | Did not fire: the canonical settlement line already gives every outbound credit transfer one evidence shape (ADR-0073) |
| ADR-0057's follow-up: payout returns | The payout return (ADR-0073, `P8-TSK-019`) |
| `X-TSK-003`, per-principal idempotency scopes | Every Phase 8 scope is per principal from birth: `<command>:<actorType>:<actorId>` |
| The unmatched-confirmation gauges (`finapp.payments.unmatched.active`, `.age`) count every row ever parked | Their descriptions corrected to "parked, ever"; the alertable signal moves to `finapp.reconciliation.suspense.*` (`P8-TSK-020`); the names stay, so Phase 7's §15 still parses |
| The existing ciphers bind no AAD | `X-TSK-008` (`EvidenceCipher`, `PayoutEvidenceCipher`, `DocumentCipher`, `SecretCipher`) |
| Posting deduplication rests on retained idempotency rows | Domain uniques on every Phase 8 effect (§2 above) |
| The confirmation lacks a guard against a second push rail (`PHASE_7_REVIEW.md`) | Unaffected; stays with the phase that adds a second push rail |
| The fleet-wide `databaseTest kafkaTest` battery Phase 7 deferred | Run and counted at the Phase 7 → 8 transition |
| The seed-id ceiling equals the transition date | New seeds are hand-minted at `2026-09-27T12:00:00Z`, below the `2026-09-28T00:00:00Z` ceiling |
| The transition's gate repair: `payments.unmatched_confirmation` records what it parked (`V023` — `named_reference`, `settlement_cycle`, `cause` `UNATTRIBUTED` \| `ATTEMPT_CONCLUDED` \| `AMOUNT_MISMATCH`, `attempt_id` exactly when attributed), and `payments.provider_evidence` gains a fifth subject, `unmatched_confirmation_id` (ADR-0062 §5) | Adopted as it stands, no payments migration: the parking still has no state and no resolution — its suspense item and break are Phase 8's (ADR-0070, `P8-TSK-020`), keyed on those columns, its raw statement reached by the stored identifier. An attributed parking (`AMOUNT_MISMATCH` — pay-by-bank's executed amount judged by the one applier for callback and inquiry alike, the pay-in failed `DECLINED` — or `ATTEMPT_CONCLUDED`) resolves by a four-eyes `TRANSFER_TO_ACCOUNT` crediting the named attempt's counterparty; a return to the payer is the deferred return-to-sender (§17); never a guess |
| The transition's gate repair: `payments.scheme_execution_claim` (`V023`) — one claim per `(rail, scheme_reference)`, subject `PAY_IN` \| `WITHDRAWAL` \| `RETURN` \| `UNMATCHED`, taken by every producer before money moves | The instant rail's alias join: `InternalReferenceLookup` resolves a scheme line's reference to exactly one internal explanation through it — break typing, never allocation (§3). A pay-in's claim is taken at `EXECUTED`, a withdrawal's or return's at `COMPLETED`, so a scheme reference no claim holds names no completed execution: after grace it types `MISSING_INTERNAL` when its other references name an operation still in flight, and `UNKNOWN_EXTERNAL` otherwise |
| The transition's gate repair: a second, DIFFERENT network clearing of one capture is its own outcome, `SECOND_PRESENTMENT` — loud, counted unmappable, not absorbed — but rests only in the retained evidence: no clearing-notice table, no cleared amount on the wire (ADR-0059 §5); a capture never sent redirects into the void, and an unreceived void is re-sent | Phase 8's, and decided: the cleared-amount and second-presentment evidence belong to its clearing-level matching (ADR-0065's two evidence hops), whose clearing record is the PSP report's canonical line in `settlement.line` — there is no separate clearing-notice table. The report's `CAPTURE` line carries the cleared amount and date, and `payments.clearing_record` stays the ARN alias only. A second presentment claims the capture's expectation a second time, becoming `DUPLICATE_EXTERNAL` or an `AMOUNT_MISMATCH` excess, or quotes only an ARN no alias records and becomes `UNKNOWN_EXTERNAL` — parked with its break either way, never absorbed. `P8-TSK-011`'s design confirms this or amends ADR-0065; it is not an open question |
| The transition's gate repair: a merchant close refuses while a chargeback can still be won and closes the payable's ledger account (a later chargeback's share parks in `CHARGEBACK_RECOVERABLE`) | A payout return to a closed merchant cannot post: its grace expires into `REVERSAL_MISMATCH` (`RETURN_NOT_APPLICABLE`), the value rests in suspense owned by its break, and the four-eyes `TRANSFER_TO_ACCOUNT` names an account that can take it — never the closed payable (ledger `V007` refuses it; §14 scenarios 37–38) |
| The transition's gate repair: refund and dispute-response send permits strictly advance on every renewal | `settlement.pull_permit`, the same shape, strictly advances `last_attempt_at` on every renewal (§7, §8) |
| The transition's gate repair: dispute postings pre-lock the platform's rows in the balance projection's order before their first posting (`PostingService.lockBalancesInOrder`, ADR-0061 §5; `DISTRIBUTED_EXECUTION.md` §3) | Binds every Phase 8 transaction posting several entries over shared hot rows — a resolution's `ADJUSTMENT` beside its unpark, a repudiation's reversal beside its unparks: the union pre-locked in that order before the first posting (§7's lock order) |
| The transition's gate repair: `ProviderTransportGuard` refuses to start when a provider base URL is not `https` off loopback (`SECURITY_ARCHITECTURE.md`) | `P8-TSK-021` extends it to every settlement source's pull URL (§11) |

*(The seven "gate repair" rows were added by the Phase 7 → 8 transition's consistency review: the
gate repaired these before Phase 8 opens, so the plan describes the tree as it is and claims no gap
that is now closed. The second-presentment row states the clearing record as decided by the
transition's re-check, R10: it read "until a task tables them", tabling the clearing notice an
input to `P8-TSK-011`'s design.)*

## 3. Bounded contexts and modules

| Context | Module | In Phase 8 |
|---|---|---|
| 13 **Settlement** (new module) | `settlement` | The external side, sole writer: the source register (compiled descriptors plus a `settlement.source` identity row), the settlement file (encrypted chunks, receipts, history), refused deliveries, the settlement batch, the settlement line and its references, ingestion errors, and each batch's **recognition posting** |
| 14 **Reconciliation** (new module) | `reconciliation` | The internal side, the comparison and its outcome, sole writer: the **settlement expectation** (moved here from `settlement`, ADR-0064), expectation keys and reference aliases, the reconciliation batch (the run), the external item, the match decision and its candidate snapshot, the allocation, the rule set, the break and its case file, the resolution, the suspense item and the park, run replays |
| 7 Ledger | `ledger` | Adjustments gain a reason code and an origin, and reconciled positions close to free adjustments (`V015`); four purposes — `PROCESSING_COSTS`, `RECONCILIATION_LOSSES`, `RECONCILIATION_GAINS`, `CASH_AT_BANK` — each with its first poster (`V016`…`V018`) |
| 9 Payments | `payments` | The completing appliers call the new `SettlementExpectations` port past their acting exit; **no payments migration** |
| 12 Merchant | `merchant` | `PayoutSettlementDeclaration`; the `PayoutSettlementExpectations` port; the payout return (`V008`) and `PayoutReturns`; `MerchantPayable` gains `payoutsReturned` (`P8-TSK-019`) and `reconciliationAttributed` (`P8-TSK-015`, with its first poster; A12) |
| 2 Identity | `identity` | Four permissions and two roles (`V016`, `V017`) |

**Build graph.** `settlement → ledger, platform, sharedkernel`; `reconciliation → ledger, platform,
sharedkernel`; **no edge between them**, and neither `payments` nor `merchant` gains one. `app`
composes everything through ports, each a required constructor parameter so the wiring is decided
at compile time (the `RailOutcomeObserver` precedent). There is no cross-schema foreign key and no
cross-schema SQL join (ADR-0029; the `unmatched_confirmation.entry_ref` practice): an expectation
holds **copies of the immutable facts** it was opened from — amount, references, entry id, account
— and every proof that spans modules is computed in `app` by composing each module's read API in
one `REPEATABLE READ` transaction on one connection, folded with `Money`, never SQL `SUM` (the
storm-reading precedent).

| Port | Declared in | Implemented in `app` by | Called | Transaction |
|---|---|---|---|---|
| `SettlementExpectations` | `payments` | `ReconciliationExpectationRecorder` → `reconciliation.ExpectationRegister` | `PaymentOutcomes` (capture, execution, card refund, return), `WithdrawalOutcomes`, `ChargebackAccounting` (chargeback, won, fee), `UnmatchedConfirmations`, `PaymentClearing` (the ARN alias) — only past the acting exit, and only when the stored rail's `clearingPurpose()` is present | **Inside** the completing transaction; infallible for valid input (ADR-0067) |
| `PayoutSettlementExpectations` | `merchant` | the same recorder | `MerchantPayoutOutcomes` (`COMPLETED`), `PayoutReturns.apply` | Inside |
| `AcceptedBatchIntake` | `settlement` | `ReconciliationIntake` | the accept leg | Inside acceptance |
| `InternalReferenceLookup` | `reconciliation` | `JdbcInternalReferenceLookup`, over payments' and merchant's public read stores — for the instant rail, `payments.scheme_execution_claim` (`V023`), which names exactly one explaining subject per scheme reference | the matcher and the grace leg | The caller's; read-only; **for break typing only, never for allocation** |
| `PayoutReturns` | `merchant` | — (merchant implements it; `app`'s `PayoutReturnSchedule` calls it) | the return worker | Its own |
| The repudiation port (named by `P8-TSK-023`) | `reconciliation` | `app`, over `settlement`'s repudiation | an approved `REPUDIATE_BATCH` | **Inside** the approval: `settlement` writes the recognition's reversal through `ReversalService`, the batch's `ACCEPTED → REPUDIATED` and `SettlementBatchRepudiated` on the approval's connection (ADR-0064 §3, the fourth seam) |

*(The repudiation row and the claim table: the Phase 7 → 8 transition's consistency review, B5
and the gate's `V023`.)*

**Why the expectation moves** (ADR-0064, amending `MODULE_ARCHITECTURE.md` M8). An expectation is
internal state, not external evidence: allocation and ageing drive its lifecycle, and "allocations
never exceed the expectation" (`INV-REC-07`) must be a local constraint. Were `settlement` to own
it, `reconciliation` would mutate another module's rows — shared mutable ownership. The item's
disposition is contended state for the same reason, so reconciliation keeps a working copy of each
line while `settlement.line` stays immutable evidence.

**Source composition — no rail names, no second copy of a declaration.** `app`'s `SettlementBeans`
composes the register: each rail with `settlement() != NONE` gets exactly one source whose position
is read from `PaymentRails.capabilities(rail).clearingPurpose()`; the payout source reads
`merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE`, to which `MerchantPayoutOutcomes` also
switches; the bank source's position is settlement's own `CASH_AT_BANK`. `SettlementBeans.java`
joins `RailVocabularyIsConfinedTest.CONFIGURATION_FILES`, and
`clearingPositionsAreNamedOnlyByTheirDeclarations` widens to `settlement` and `reconciliation`:
neither may name a `*_CLEARING` purpose. The `settlement.source` row stores identity and
operational state (code, kind, status, `next_sequence`) and **no position column**.

**Isolation.** New `SettlementModuleIsolationTest` and `ReconciliationModuleIsolationTest`
(requiring `ledger`, `platform` and `sharedkernel`; refusing every sibling, each other included,
each refusal probed with a plant); every sibling isolation test gains both; `settings.gradle.kts`
includes both, and `ProductionModules` picks them up from the classpath.

**Alternatives rejected** (ADR-0064): one module (it collapses "settlement is not reconciliation"
and puts cash recognition inside the comparer); a `reconciliation → settlement` edge (the item's
disposition becomes mutable state in another module's rows, or a hot-path read); ledger open items
(an `AFTER INSERT` trigger on every clearing line, which changes the platform's most-probed posting
path and brings per-item advisory locks — completeness is delivered instead by the infallible port,
the opener register and a lock-free verifier, §12.7).

## 4. Aggregates and commands

| Aggregate | Module | Commands | Idempotency |
|---|---|---|---|
| `SettlementSource` | `settlement` | None at runtime: compiled descriptors composed in `app`; the row holds identity, status and `next_sequence` | Seeded (settlement `V002`) |
| `SettlementFile` | `settlement` | receive (upload, pull, readmission); attest or decline (a person); parse and accept (the platform) | `UNIQUE (source_id, content_sha256)` among non-readmissions; upload keyed per principal (`settlement.upload:<actorType>:<actorId>`); a duplicate appends a `DUPLICATE` receipt and returns the existing file |
| Refused delivery | `settlement` | Recorded at the door — metadata only | An append-only record, not an aggregate |
| `SettlementBatch` | `settlement` | accepted with its recognition posting, or rejected (the platform); repudiated by an approved `REPUDIATE_BATCH` | Live `UNIQUE (source_id, external_batch_ref, currency)`; posting key `settlement-batch:<batchId>`, dates read from the row |
| `SettlementExpectation` | `reconciliation` | open (inside the completing transaction through the port; `REMITTANCE` at acceptance; the backfill); allocate; age; resolve | `UNIQUE (kind, operation_ref)` and `UNIQUE (journal_entry_id, ledger_account_id)`, `ON CONFLICT DO NOTHING` |
| `ReconciliationBatch` (the run) | `reconciliation` | created at acceptance; chunked by the run leg; requeue and reprocess (a person) | `UNIQUE (batch_id)`; one open `REPROCESS` per source; the cursor advanced in the chunk's own transaction |
| `ExternalItem` | `reconciliation` | matched, checked, offset, left unmatched or parked (the platform); resolved; repudiated, or reopened by a repudiation | `UNIQUE (settlement_line_id)`; conditional transitions |
| `MatchDecision` / `Allocation` | `reconciliation` | decide (run, rematch, reprocess, manual) | `UNIQUE (external_item_id, expectation_id)` among non-reversing allocations; deferred Σ triggers; append-only |
| `RuleSet` | `reconciliation` | propose; activate (a different person); reject | v1 seeded `ACTIVE`; propose keyed per principal; one `ACTIVE` per source |
| `Break` | `reconciliation` | raise (the platform); assign, note, link evidence, reclassify (a person) | One open break per (type, subject); a recurrence is a new break with `follows_break_id` |
| `Resolution` | `reconciliation` | propose, approve, reject, withdraw (people); `EVIDENCED` (the platform only) | Keyed per principal (`reconciliation.resolve:<actorType>:<actorId>`); one `PROPOSED` per subject; `UNIQUE adjustment_proposal_id` |
| `SuspenseItem` / `Park` | `reconciliation` | park, unpark, offset (the platform); release by resolution or repudiation | The item's conditional transition; `UNIQUE (external_item_id)`; posting key `recon-suspense:<parkId>` |
| `PayoutReturn` | `merchant` | apply (the return worker, after re-reading the item under a share lock) | `UNIQUE (payout_id)`; the payout row's lock; posting key `merchant-payout-return:<payoutId>` |
| `AdjustmentProposal` (amended) | `ledger` | propose, approve, reject — now carrying `origin` and `reason_code`; `proposeOwned`, `approveOwned`, `rejectOwned` for reconciliation | Ledger `V010`'s arbiters, unchanged |

## 5. The lifecycles

Stated in full in `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`:
- the settlement file: `RECEIVED → PARSED → ACCEPTED`, `RECEIVED` or `PARSED → REJECTED`;
  attestation a NULL → value fact settable on `RECEIVED` or `PARSED` only, the attester never the
  uploader, and an upload never `ACCEPTED` unattested — nor a readmission of an unattested
  original, which must itself be attested before acceptance, by a person distinct from the
  readmitter and from the original's uploader (ADR-0066 §8). **Our own failure never
  rejects evidence**: a parser exception leaves the file `RECEIVED`, `parse_failures + 1`,
  `next_parse_at` backed off;
- the settlement batch: born `PARSED`, then `ACCEPTED` or `REJECTED`; `ACCEPTED → REPUDIATED` only
  by an approved `REPUDIATE_BATCH` — the single designed exit from `ACCEPTED`. There is no `HELD`:
  a control-total mismatch rejects the whole file;
- the reconciliation batch (the run): `OPEN → IN_PROGRESS → COMPLETED` (`OPEN → COMPLETED` only
  with no items); `OPEN` or `IN_PROGRESS → BLOCKED` after consecutive failures, raising a CRITICAL
  `PROCESSING_ERROR` break (cause `RUN_BLOCKED`) in the same transaction; `BLOCKED → IN_PROGRESS`
  by a reasoned requeue. Never `COMPLETED` with an item `PENDING`;
- the external item: `PENDING → MATCHED | CHECKED | OFFSET | UNMATCHED | PARKED`;
  `UNMATCHED → MATCHED` (rematch) or `PARKED` (grace expired, or a definitive class);
  `PARKED → MATCHED` (unpark) or `RESOLVED`; every non-terminal disposition `→ REPUDIATED` with its
  batch; and `MATCHED → UNMATCHED` (reopened) only by an approved `REPUDIATE_BATCH`, for a bank
  item of another batch whose allocation named the repudiated batch's remittance expectation (the
  repudiated batch's own items leave `MATCHED` by `→ REPUDIATED`) — that allocation
  counter-allocated in the same transaction, the bank item then waiting for the genuine
  remittance;
- the settlement expectation: `OPEN → PARTIALLY_SETTLED → SETTLED`, or `OPEN → SETTLED` by an
  allocation of the whole amount; `OPEN` or `PARTIALLY_SETTLED → RESOLVED_BY_ADJUSTMENT`; reopened
  only by a repudiation's counter-allocations; `overdue_since` a one-way fact set by the ageing
  sweep, not a state;
- the break: `OPEN → INVESTIGATING → RESOLUTION_PROPOSED → RESOLVED`, back to `INVESTIGATING` on a
  rejection or withdrawal; `EVIDENCED` by the platform only, from any open state; a zero-value
  `ACKNOWLEDGE` by one person; reclassification only in `OPEN` and `INVESTIGATING`; severity only
  escalates; never deleted;
- the resolution: `PROPOSED → APPROVED | REJECTED | WITHDRAWN`; born `APPROVED` for `EVIDENCED` and
  a single-person zero-value `ACKNOWLEDGE`; a stale approval refused with the resolution left
  `PROPOSED`;
- the suspense item: `OPEN → PARTIALLY_RELEASED → RELEASED`, or `OPEN → RELEASED` by a release
  of the whole, always owned by a break;
- the rule set: `PROPOSED → ACTIVE | REJECTED`, the prior `ACTIVE → RETIRED` in the activating
  transaction; v1 seeded `ACTIVE`; content frozen from `PROPOSED`;
- the payout return: a born-once `RECORDED` fact; the payout stays `COMPLETED` (merchant `V007`
  makes it terminal; a return is a new operation, `INV-LIFE-04`).

*(The direct `OPEN → SETTLED` and `OPEN → RELEASED` edges, the item's repudiation reopening and
the attested readmission: the Phase 7 → 8 transition's consistency review, C1, A9 and A11 — the
edges now read as the lifecycles document states them; the readmission's attester named by the
transition's re-check, R5.)*

Every stored machine gets the three-layer enforcement: the aggregate refuses invalid edges; a
generated schema `CHECK` and an every-writer transition trigger derived from
`permittedTransitions()`; and an append-only `*_event` history carrying actor, actor type,
occurrence time and reason.

## 6. Financial invariants Phase 8 must preserve

The in-scope set is **whatever the catalogue marks `Phase: 8`, token-parsed** — the standing rule,
never this list. At planning time that is **twenty-two**: the thirteen the catalogue already
carried and the nine the transition adds.

- `INV-HIST-02` (files) — stored bytes equal received bytes by checksum; a refused delivery keeps
  metadata only, because `INV-PAY-02` and `INV-RAIL-03` take precedence for it (ADR-0066).
- `INV-HIST-04` (matching) — every decision pins its rule set, and decision replay reproduces it.
- `INV-IDEM-02` — recognition is unique per (source, external batch reference, currency) among live
  batches: the "unique on (process, entity, period)", independent of idempotency-record retention.
- `INV-REV-04` — reason codes realised by ledger `V015`; the generic route assigns
  `MANUAL_CORRECTION`.
- `INV-SET-01…03` — completion is not settlement, now at the last hop too; every externally settling
  operation opens a tracked, ageing expectation; late settlement is processed, never refused.
- `INV-REC-01…05` — both sides preserved; every unallocated remainder a counted wait or a parked item
  with its break; resolution a controlled, reason-coded adjustment; matching deterministic and
  explainable; suspense temporary, aged and alertable.
- `INV-AUD-04` — its Phase 8 subjects: upload attestation, value-bearing or posting resolutions,
  batch repudiation, rule-set activation.
- `INV-SET-04` — new: a settlement batch is recognised once, from its own stored evidence.
- `INV-SET-05` — new: every externally settling position has exactly one declared source, and only
  that source's evidence discharges it.
- `INV-SET-06` — new: cash moves only on the bank's own statement.
- `INV-SET-07` — new: settlement evidence takes effect only whole and authenticated.
- `INV-REC-06` — new: every reconciled position is explained by its open items.
- `INV-REC-07` — new: a match allocates no more than either side holds, records what it saw, and is
  never undone.
- `INV-REC-08` — new: a tolerance never absorbs value.
- `INV-REC-09` — new: every suspense item is owned by exactly one break.
- `INV-REC-10` — new: settlement evidence is screened, encrypted, and every content access audited.

**Amended at the transition, each with provenance:** `INV-SET-02` (enforced by the expectation
uniques in the completion's transaction and the completeness verifier; verified by the opener
register and the expectation gauges); `INV-REC-02` (an `EVIDENCED` resolution — the platform closing
a break because a later zero-residual allocation or offset explains it, stored as a resolution row
naming that decision and posting — is recorded, not silent, and is the only resolution no person
decides); `INV-REC-03` (resolution ↔ proposal ↔ entry one-to-one; four-eyes whenever value is at
issue or the resolution posts; closed reason codes at both ranks; stale approval refused);
`INV-REC-04` (claimant order under namespace `4` and decision snapshots; restated honestly as "the
same stored inputs always produce the same matches"; the shuffled-order property test and snapshot
replay); `INV-REC-05` (Enforce: `RECOGNISE_GAIN` after the pinned minimum age, four-eyes; Verify:
the age by metric — `finapp.reconciliation.suspense.open`, `.age`, `.unowned` — and the balance by
the audited suspense report, because an amount never enters a metric, ADR-0070 §6, ADR-0072 —
*the Verify amendment added by the Phase 7 → 8 transition's consistency review, C2*); `INV-REV-04`;
`INV-AUD-04`; `INV-HIST-02`; `INV-HIST-04` (decision replay); `INV-IDEM-02` (the live-batch
unique); `INV-MER-02` (plus payouts returned, plus or minus reconciliation attributions);
`INV-PAY-02` and `INV-RAIL-03` (the sweep, the needle and the door refusal extended to settlement).

The catalogue stands at **110 invariants**. Protected throughout: `INV-LED-01…06`,
`INV-BAL-01/02/03/05`, `INV-HIST-01`, `INV-IDEM-01/03/04`, `INV-CON-01/02`, `INV-LIFE-01/02/04`,
`INV-REV-01`, `INV-EVT-01…04`, `INV-ACC-01`, `INV-AUD-01/02/03`, `INV-MON-01…06` (`INV-MON-04`: a
currency mismatch is never converted), `INV-PAY-01/02/03`, `INV-RAIL-01/03/04`, `INV-MER-02` and
`INV-DSP-02`.

## 7. Multi-instance architecture

Ten instances execute everything concurrently. Five leaderless schedules join
`NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` (nine → fourteen) and
`DISTRIBUTED_EXECUTION.md` §3's scheduler register, each with its argument. Each is a
`SmartLifecycle` on `scheduleWithFixedDelay` (the `ReturnResolutionSchedule` shape), off in test
contexts, its bounds refused at zero, its failures contained per row or chunk, its work taken
oldest first, with a `finapp.<module>.<x>.sweeper.enabled` gauge.

| Schedule | Legs | Coordination |
|---|---|---|
| `SettlementIntakeSchedule` | parse; accept | Parse: `FOR UPDATE SKIP LOCKED` on `RECEIVED` files whose `next_parse_at` has passed. Accept: the file `FOR UPDATE SKIP LOCKED` on eligible `PARSED` files, then the source row `FOR UPDATE` for the gapless `next_sequence` |
| `ReconciliationSchedule` | run; rematch; grace | Per source, `pg_try_advisory_xact_lock(4, hashtext(source_id::text))` per transaction; a refused instance moves to another source (the `OutboxRelay` argument) |
| `ReconciliationSweepSchedule` | expectation ageing; severity escalation; key-collision breaks; run-block detection | Idempotent through partial uniques and conditional updates; the expectation row `FOR UPDATE` |
| `PayoutReturnSchedule` | return application | The item re-read under a share lock (proceeding only while `UNMATCHED`); the payout row's lock; `UNIQUE (payout_id)`; the posting key |
| `SettlementPullSchedule` | per-source pull | `pull_permit` renewed conditionally and strictly forward (the send-permit shape, as the transition's gate repaired it) to pace the herd; the content unique |

**Advisory namespace `4`** — `hashtext(source_id::text)`, transaction-scoped — is registered in
`DISTRIBUTED_EXECUTION.md` §3 and pinned by `ReconciliationMigrationTest`. It releases on commit,
rollback or connection death, so nothing leaks and no lease clock exists. It **orders** allocation;
it does not arbitrate it. The arbiters are the uniques and Σ triggers below, proven by lock-bypass
probes, and there are no per-item advisory locks, so lock-table exhaustion cannot arise.

| Contention | PostgreSQL arbiter | The loser |
|---|---|---|
| The same file uploaded ten times, or by upload and pull at once | `UNIQUE (source_id, content_sha256) WHERE readmits_file_id IS NULL`; the idempotency record per principal (`settlement.upload:<actorType>:<actorId>`) | Converges; a `DUPLICATE` receipt |
| A different file for an accepted batch or statement | Live `UNIQUE (source_id, external_batch_ref, currency)`; live `UNIQUE (source_id, currency, statement_sequence)` | `REJECTED(CONFLICTING_BATCH)`, retained |
| Concurrent parse of one file | The `SKIP LOCKED` claim; conditional `RECEIVED → PARSED`; `UNIQUE (file_id, line_no)` | Skips |
| Two attesters, or attest against decline | The file row `FOR UPDATE`; the conditional NULL → value `attested_by`; `CHECK` attester ≠ receiver; for a readmission of a never-attested original, settlement's `-022` trigger (attester ≠ readmitter and ≠ the original's uploader, §8) | 409, or converges |
| Concurrent acceptance, and acceptance order | The file claim and conditional `PARSED → ACCEPTED`; the source row `FOR UPDATE`; `UNIQUE (source_id, source_sequence)`; `UNIQUE (batch_id)` on the run; the posting key; stored dates | Skips; a later-day replay converges |
| Recognition twice (retry, crash, later day, re-presentation) | The live-batch unique; the batch's conditional; the posting key | No second effect |
| Duplicate completion appliers opening an expectation | The applier's acting conditional; `UNIQUE (kind, operation_ref)` and `UNIQUE (journal_entry_id, ledger_account_id)`, `ON CONFLICT DO NOTHING` | No second row |
| A reference key collision | `UNIQUE (source_id, key_kind, key_value)`, `ON CONFLICT DO NOTHING`, plus a counted `KEY_COLLISION` event | Never a failed payment |
| The backfill racing live completions | The same uniques | Converges |
| Ten workers on one batch or source | Namespace `4` per chunk; the chunk re-reads the run cursor, processes at most 200 items and advances the cursor **in the same transaction**; the next chunk may run on any instance | Moves on |
| Duplicate matching (retry, takeover, rematch against run, manual against engine) | `UNIQUE (external_item_id, expectation_id) WHERE reverses_allocation_id IS NULL`; deferred Σ triggers (allocations equal `allocated_minor`; allocated plus resolved never above the amount, on both sides); conditional item and expectation transitions; append-only grants | Rolls back (`409 reconciliation.RecordAlreadyMatched` for a person) |
| Allocation against ageing on one expectation | The expectation row `FOR UPDATE` plus conditionals | Either order converges: no break, or `EVIDENCED` |
| Duplicate park, unpark or offset | The item's conditional transition; `UNIQUE (external_item_id)` on the suspense item; the park row's key | No second entry |
| Concurrent resolution proposals | Partial `UNIQUE (break_id) WHERE status = 'PROPOSED'` (and per batch for repudiation); the keyed propose | 409, or replay |
| Ten racing approvals; approve against reject | Break row `FOR UPDATE` → resolution `FOR UPDATE` → conditional `PROPOSED → APPROVED`; `UNIQUE adjustment_proposal_id`; ledger `V010`'s `UNIQUE journal_entry_id` | Converges on the entry (same approver), or 409 |
| `EVIDENCED` against a pending approval | The break row's lock, taken first by both | First wins; the loser sees `RESOLVED`, or `ResolutionStale` |
| Suspense released twice | The item `FOR UPDATE`; `CHECK released_minor ≤ amount_minor` | 409 |
| A rule-set activation race | Partial `UNIQUE (source_id) WHERE status = 'ACTIVE'`; the retirement inside the activation; `CHECK` activator ≠ proposer | 409 |
| Concurrent reprocess requests | Partial unique on one open `REPROCESS` run per source; the key | 409 |
| A payout return applied twice | The payout row's lock; `UNIQUE (payout_id)`; the posting key | Converges |
| The return worker against the grace leg on one item | The item's row lock — the worker's share lock, the grace leg's update — and the judgement made on the locked row (ADR-0073 §7) | Either order converges: allocated, or parked with no return applied; raced both ways by `P8-TSK-013` and `P8-TSK-019` |
| Ten instances pulling one report | An idempotent GET; the conditional `pull_permit` forward renewal paces; the content unique dedupes | No duplicate file |
| Window expiry judged by instances with skewed clocks | Judged **in SQL on the database clock** (`now()`) against stored dates; day-scale windows make the measured VM drift (+55–77 ms a second, ~1.6 s step-backs) noise; fixtures stamp from the test clock | — |

**Lock order**, written into `DISTRIBUTED_EXECUTION.md` §3 as a row: (1) namespace `4` (the
source), when the transaction allocates, parks or unparks — the run, rematch and grace legs, and a
`MANUAL_MATCH` or `REPUDIATE_BATCH` approval; (2) the break row, then the resolution row;
(3) expectation rows, then external item rows, then suspense item rows, each sorted by id; (4) the
merchant payout row (the return worker only, after its item row at rank 3); (5) the attribution
target's ledger account `FOR SHARE`; (6) inside `approveOwned`, the ledger proposal row, then
ledger projection rows sorted by account id. **Postings are last in every transaction**, and a
transaction posting several entries over shared rows — a resolution's `ADJUSTMENT` beside its
unpark, a repudiation's reversal beside its unparks — pre-locks the union of the platform's rows it
will touch in the projection's own order before its first posting
(`PostingService.lockBalancesInOrder`, `DISTRIBUTED_EXECUTION.md` §3's multi-entry lock-order
rule, which the Phase 7 → 8 transition's gate made for dispute postings, ADR-0061 §5), and takes
any runtime counterparty's ACCOUNT row — `FOR SHARE` where a share lock suffices — before any
projection row. *(Numbered, and the ledger proposal row and the pre-lock rule added, by the
transition's consistency review, B9 and A6: the order now reads as the `DISTRIBUTED_EXECUTION.md`
row.)* Payments' completions take their own locks and the ledger's, then only **insert**
reconciliation rows; their only waits are on uniques held by a concurrent inserter of the same
key, and no reconciliation worker waits on a payments lock, so no cycle exists. Among the
postings, deadlock freedom rests on that pre-lock, never on seed order: parks, unparks and
recognitions touch only seeded accounts, but the transition's gate found "seeded accounts sort
before runtime ones" wrong on a longer-lived database, and seed order stays load-bearing only for
`SETTLEMENT_CLEARING` (ledger `V003`, `DISTRIBUTED_EXECUTION.md` §3) — never a Phase 8 argument.

**Failure between steps.** A crash mid-parse rolls back and the file is re-claimed; a crash between
parse and accept leaves a `PARSED` file the accept leg sweeps; acceptance, a chunk and an approval
are each one transaction, all or nothing, a chunk resuming from its committed cursor on any instance
in the same claimant order. Phase 8 has **no Kafka consumer**: its events notify future consumers
(`INV-EVT-04`) and no correctness rests on them. Out-of-order files block nothing — the acceptance
sequence is arrival order, statement gaps raise breaks, and silence gauges and overdue expectations
make every gap visible. Partial batches are impossible. Scheduling is at-least-once ticks over
idempotent, conditional writes. Parallelism is per source; the recorded scale-out path locks by
(source, currency) under the same arbiters.

Nothing lives in process memory. **Would this remain correct if ten instances ran it
concurrently?** Every contention above names its PostgreSQL arbiter, and each has a **counted
ten-way test** plus, for allocation and parking, a lock-bypass probe (§13). The `PASS` verdict rests
on those tests; it is never claimed by construction.

## 8. Data architecture

Every table arrives with the task that creates it, with its `DATA_CLASSIFICATION.md` §4 rows in the
same change (`ColumnClassificationTest`). Money is `amount_minor BIGINT` / `currency CHAR(3)` /
`scale SMALLINT` (ADR-0003); ids are UUIDv7 (ADR-0013); times come from the injected clock, never
`DEFAULT now()`, while windows are judged on the server clock; migrations are forward-only
(ADR-0011) with explicit SQL (ADR-0033); no cross-schema foreign key; every machine has a generated
`CHECK`, an every-writer transition trigger and a `*_event` history; `finapp_app` has **no
`DELETE` anywhere** in either new schema. Migration numbers follow task order; a re-slice or a
deferral renumbers.

**`settlement`**

| Migration (task) | Tables | What carries the invariant |
|---|---|---|
| `V001` (`P8-TSK-001`) | None — the schema floor | Owner `finapp_migrator`, `REVOKE ALL FROM PUBLIC`, `USAGE` alone to `finapp_app` |
| `V002` (`-002`) | `source` (seeded with literal ids and timestamps; no position column), `file` (in `RECEIVED` only), `file_chunk`, `file_event`, `file_receipt`, `refused_delivery` | The content unique among non-readmissions; the attestation `CHECK`s; chunks AES-256-GCM, `SELECT, INSERT` only with append-only triggers; the file's `UPDATE` narrowed to `status`, `rejection_code`, `rejection_detail`, `parse_failures`, `next_parse_at`, `attested_by`, `attested_at`, `status_changed_at`; a refused delivery holds metadata, never a value |
| `V003` (`-008`) | `batch` (`PARSED`, `REJECTED`), `batch_event`, `batch_total`, `line`, `line_reference`, `ingestion_error`; the file's `PARSED` and `REJECTED` | The live-batch unique (the statement-sequence unique arrived with `V005`); `UNIQUE (file_id, line_no)`; `canonical_fingerprint` indexed and deliberately not unique; reference shape `CHECK`s refusing bank-identifier and alias shapes (`INV-RAIL-03`); at most 100 content-free errors per file |
| `V004` (`-009`) | Acceptance: the batch's `source_sequence`, `accepted_on`, `journal_entry_id`, `posting_omitted`; `ACCEPTED` on file and batch | `UNIQUE (source_id, source_sequence)`; the acceptance columns once-only by trigger; `CHECK` that an accepted batch has its sequence and `accepted_on`, and that `journal_entry_id` is NULL exactly when the posting was omitted |
| `V005` (`-016`) | The bank statement: the batch's `statement_sequence`, `opening_minor`, `closing_minor`; `remittance_reference` nullable; the bank line types and `REMITTANCE_REF` | The live statement-sequence unique `(source_id, currency, statement_sequence)` excluding `REJECTED`/`REPUDIATED`; a batch a report or a statement, exactly one, the facts whole and the net their difference by `CHECK`; attribution a bank credit's or debit's alone; the facts frozen with the parse statement |
| `V006` (`-017`) | The scheme cycle report's vocabulary: `CREDIT_IN`, `DEBIT_OUT`, `SCHEME_FEE`; the `SCHEME_REF` and `END_TO_END_REF` references | The regenerated `CHECK`s; the cycle is the batch's identity (`external_batch_ref`), never a line reference |
| `V007` (`-018`) | The payout provider report's vocabulary: `PAYOUT_EXECUTED`, `PAYOUT_RETURNED`, `PAYOUT_FEE`; the `PAYOUT_PROVIDER_REF` reference | The regenerated `CHECK`s |
| Next free (`-021`) | `pull_permit` | A forward-only `last_attempt_at` under a conditional `UPDATE`, strictly advancing on every renewal (the send permits' shape as the transition's gate repaired it) |
| Next free (`-022`) | The readmission's attestation rule on `file` | A trigger refusing `ACCEPTED` for a `READMISSION` of a never-attested original unless the readmission's `attested_by` is set and differs from the readmitter (its `received_by`) and from the original's `received_by` — `V002`'s `CHECK`s bind only `UPLOAD`, and a cross-row rule needs a trigger |
| Next free (`-023`) | The batch's `REPUDIATED` | The live uniques exclude it, so a genuine batch can follow |

*(The `-022` row added by the Phase 7 → 8 transition's re-check, R5: the readmission's attestation
held at the database, as `V002` holds the upload's.)*

**`reconciliation`**

| Migration (task) | Tables | What carries the invariant |
|---|---|---|
| `V001` (`-001`) | None — the schema floor | As settlement's |
| `V002` (`-004`) | `expectation`, `expectation_event`, `expectation_key`, `reference_alias`, `rule_set`, `rule`, `tolerance`, `provider_fee_schedule`, `severity_threshold`; rule set v1 seeded `ACTIVE` for the four sources, its `PAYOUT_RETURNED` rule operation-anchored (§12.5); the expectation's announced-cycle column, an attribute and never a key | `UNIQUE (kind, operation_ref)` and `UNIQUE (journal_entry_id, ledger_account_id)`; `CHECK` that only a `REMITTANCE` has no journal entry; allocated plus resolved never above the amount, and its deferred Σ trigger; keys and aliases `UNIQUE (source_id, key_kind, key_value)`; **`tolerance` has no amount member** (`INV-REC-08` at database rank); one `ACTIVE` rule set per source; content frozen from `PROPOSED`; `rule_set_id NOT NULL` on every expectation |
| `V003` (`-009`) | `reconciliation_batch` (+`_event`), `external_item` (+`_event`, `_key`) | `UNIQUE (source_id, source_sequence) WHERE kind = 'BATCH'`; `UNIQUE (batch_id)`; one open `REPROCESS` per source; allocated plus parked plus offset never above the item's amount |
| `V004` (`-010`) | `break` (+`_event`, `_note`, `_evidence_link`), `suspense_item`, `suspense_release`, `park` | One open break per (type, subject) by partial uniques; no `DELETE` grant plus a refusing trigger; `suspense_item.break_id NOT NULL`; released never above the amount; a note refused when it holds a Luhn-valid 13–19-digit run or an IBAN shape |
| `V005` (`-011`) | `match_decision`, `match_candidate`, `allocation` | `rule_set_id NOT NULL`; the allocation unique and deferred Σ triggers on both sides; **no `UPDATE` or `DELETE` grant** on decisions, candidates or allocations |
| `V006` (`-012`) | `resolution` (+`_event`), for the platform's `EVIDENCED` kind; the person kinds (`V007`, `-015`) and the batch subject (`V013`, `-023`) are admitted by the tasks that introduce them | Exactly one subject; one `PROPOSED` per subject; the four-eyes `CHECK`; an `EVIDENCED` resolution proposed by the system and born `APPROVED`; `UNIQUE adjustment_proposal_id`, `UNIQUE journal_entry_id` |
| `V007` (`-015`) | `resolution`'s generated `CHECK`s and transition trigger re-stated for the person kinds and the `PROPOSED`, `REJECTED` and `WITHDRAWN` states | The (kind, reason code) `CHECK`; the regenerated machine `CHECK` and every-writer trigger |
| `V008` (`-016`) | The bank item: `external_item.attributed_source_id`, `position_purpose` nullable under the position rule, the bank line types and `REMITTANCE_REF`; `match_candidate.key_kind` nullable for the value-date group | A report line always in its source's position and never attributed, a bank credit or debit in a position exactly when attributed, a bank fee in neither; the attribution frozen with the copied line |
| `V009` (`-017`) | The scheme item: the scheme's line types and references on the working copy; the cycle on the run (`reconciliation_batch.settlement_cycle`, frozen); the cycle a return learns (`external_item.learned_cycle`) | The learned cycle written once, equal to the item's run's cycle, never at birth — every-writer triggers; the UPDATE grant narrowed to it |
| `V010` (`-018`) | The payout item: the payout provider's line types and reference on the working copy; `PAYOUT_FEE` in the rule and fee schedule vocabularies; the payout rule set v1's missing fee rule and flat schedule, completed in place | The seed completed only behind a guard refusing once any run or decision names the payout rule set — no stored decision can be explained differently |
| `V011` (`-020`) | `break_suspense_item_fk` made `DEFERRABLE INITIALLY IMMEDIATE` — a parking's owner stands on its own suspense item, born in one transaction; the cause `EXECUTION_ALREADY_EXPLAINED` (`DUPLICATE_EXTERNAL`) for a parking payments `V023`'s backfill left unclaimed | Only the parking's opener defers the key, and sets it `IMMEDIATE` again at once; the cause `CHECK` and the raise pairing regenerated whole |
| `V012` (`-022`) | `run_replay` | Append-only |
| `V013` (`-023`) | The `REPUDIATE_BATCH` kind and the batch subject on `resolution`; the item's `REPUDIATED` and its `MATCHED → UNMATCHED` reopening; the expectation's reopening edges; the suspense item's `REPUDIATION` origin | The generated `CHECK`s and transition triggers regenerated; one `PROPOSED` repudiation per batch |

*(`V007`…`V009` numbered by the Phase 7 → 8 transition's consistency review, A8: `P8-TSK-023`
needs its own migration, because each state and edge arrives with its producer. A deferral of
`-022` or `-023` renumbers.)* *(`P8-TSK-016` took `V008` — its backlog entry had recorded "no
settlement or reconciliation migration", but the external item copied from a bank line admitted
neither its type nor its missing position — so `-022` and `-023` moved to `V009` and `V010`; and
it added settlement `V005` for the statement's facts and unique, which the plan's `V003` row had
claimed and the schema did not hold.)* *(`P8-TSK-017` took `V009` in turn — its backlog entry had
recorded "Persistence: none new", but neither the item's vocabulary nor `learned_cycle` existed — so
`-022` and `-023` moved again, to `V010` and `V011`; and settlement `V006` admitted the scheme's
line types and references.)* *(`P8-TSK-018` took `V010` — its backlog entry had recorded
"Persistence: none new" a third time, but the item admitted no payout vocabulary and the payout rule
set had no fee terms to check by — so `-022` and `-023` moved to `V011` and `V012`; and settlement
`V007` admitted the payout provider's line types and reference.)* *(`P8-TSK-020` took `V011` — its backlog entry
had recorded "Persistence: none new" a fourth time, but a parking's owner is a break standing on its
own suspense item, which neither immediate key let be born, and a parking payments `V023`'s backfill
left unclaimed needed a cause that admits no second attribution — so `-022` and `-023` moved to
`V012` and `V013`.)*

**Other schemas.**
- **ledger:** `V015` (`P8-TSK-006`) — `adjustment_proposal.reason_code` and `origin`, the
  uncoded-insert trigger, the binding trigger over `reconciledPositions()`, the re-stated freeze;
  `V016` (`-009`) — `PROCESSING_COSTS`; `V017` (`-015`) — `RECONCILIATION_LOSSES`,
  `RECONCILIATION_GAINS`; `V018` (`-016`) — `CASH_AT_BANK`. Each purpose follows the `V011`–`V014`
  ceremony: the four generated constraints regenerated, one seeded row per currency (EUR, GBP,
  USD) with hand-minted UUIDv7 literals stamped `2026-09-27T12:00:00Z` and literal timestamps,
  `OperationalChartMigrationTest.SEED_MIGRATIONS` gaining the file and `SEEDED_TYPES` moving to
  `Map.ofEntries` (nine → thirteen), `everySeededIdSortsBeforeEveryRuntimeId` green.
- **merchant:** `V008` (`-019`) — `payout_return`: `payout_id UNIQUE`, the money held equal to the
  payout's by a composite foreign key `(payout_id, amount_minor, currency, scale)` onto the payout's
  `(id, amount_minor, currency, scale)` (a `CHECK` cannot read another row; ADR-0073 §1),
  `external_item_ref`, `journal_entry_id UNIQUE`, `returned_on`, `value_date`, `recorded_at`;
  append-only, so it is inserted after its posting, carrying the entry id. *(The composite
  foreign key and the order: the Phase 7 → 8 transition's consistency review, A6.)*
- **identity:** `V016` (`-003`) admits `RECONCILIATION_OPERATOR`; `V017` (`-007`) admits
  `RECONCILIATION_CONTROLLER`.
- **payments:** none. The transition gate's `V023` — the parking's attribution columns,
  `provider_evidence`'s fifth subject and `scheme_execution_claim` — already stands (§2).

**Classification.** File content and ciphertext `RESTRICTED-PII` (bank statements carry names);
references `CONFIDENTIAL`, like the existing "Phase 8's match key" rows; amounts
`RESTRICTED-FINANCIAL`, never in logs, traces, metrics or events; notes and narratives
`CONFIDENTIAL`, never logged, evented or audited in their body.

## 9. API architecture

Every route is an operator route under `/v1/operator/`, with a `RoutePermissionRegisterTest` row,
OpenAPI entries (additive under v1, ADR-0015; the baseline regenerated per task), errors from
`ERROR_CONTRACT.md`, and lists bounded at 100 with a `truncated` flag. Idempotency scopes are per
principal: `<command>:<actorType>:<actorId>`.

| Operation | Door | Idempotency | Authorization | Asynchronous behaviour |
|---|---|---|---|---|
| Upload a settlement file | `POST /settlement/files` (source code, declared business date, base64 at most 8 MiB) → `202 {fileId, status, duplicateOf?}` | `Idempotency-Key` plus the content address | `SETTLEMENT_INGEST` | Inert until attested; parsed and accepted by the intake schedule; status read by `GET` |
| Attest or decline an upload | `POST /settlement/files/{id}/attestation`, `/decline {reason}` | State | `SETTLEMENT_INGEST`, the attester never the uploader | Acceptance follows attestation |
| Fetch now | `POST /settlement/sources/{code}/fetch` | Natural | `SETTLEMENT_INGEST` | A pull on the permit |
| Sources, files, refused deliveries, batches | `GET /settlement/sources`, `/files[/{id}]`, `/refused-deliveries`, `/batches/{id}` | Read | `RECONCILIATION_INVESTIGATE` | — |
| Read raw content | `POST /settlement/files/{id}/content-reads {reason}` | Per read | `RECONCILIATION_INVESTIGATE`, reasoned | Checksum verified; audited per access |
| Readmit a wrongly rejected file | `POST /settlement/files/{id}/readmission {reason}` | Keyed | `RECONCILIATION_ADMINISTER` | Re-parsed under the current format version. Admissible for an original our validation rejected, and for a `CONFLICTING_BATCH` original whose conflicting batch is now `REPUDIATED`; accepted when the original was pulled or attested, otherwise only once the readmission is itself attested by a person distinct from the readmitter and from the original's uploader (settlement's `-022` trigger, §8); whether a declined upload is readmissible is `P8-TSK-022`'s recorded question (ADR-0066 §8) |
| An operation's settlement status | `GET /reconciliation/settlement-status?kind=&operationRef=` | Read | `RECONCILIATION_INVESTIGATE` | `PENDING`, `REPORTED`, `CASH_CONFIRMED`, `OVERDUE` or `RESOLVED`, with the identifier trail |
| Expectations | `GET /reconciliation/expectations?status=&overdue=&source=`, `/{id}` | Read | `RECONCILIATION_INVESTIGATE` | — |
| Runs; requeue a blocked run | `GET /reconciliation/runs[/{id}]`; `POST /reconciliation/runs/{id}/requeue {reason}` | State | Read `RECONCILIATION_INVESTIGATE`; requeue `RECONCILIATION_ADMINISTER` | The run resumes on any instance |
| Decision and allocation explanation | `GET /reconciliation/decisions/{id}`, `/allocations/{id}` | Read | `RECONCILIATION_INVESTIGATE` | Answered from stored rows alone |
| Replay a run | `POST /reconciliation/runs/{id}/replay` | State (appends `run_replay`) | `RECONCILIATION_INVESTIGATE` | Synchronous verdict: `IDENTICAL`, `DIVERGED`, with `PENDING_REMATCH` reported apart |
| Reprocess a source | `POST /reconciliation/sources/{code}/reprocessing {reason}` | Keyed; one open per source | `RECONCILIATION_ADMINISTER` | A `REPROCESS` run over residual items |
| Opening-position backfill | `POST /reconciliation/opening-position {reason}` | Keyed; converges on the uniques | `RECONCILIATION_ADMINISTER` | Leaderless, paged by id |
| Breaks: list, detail, trace | `GET /reconciliation/breaks?type=&status=&severity=&source=&assignee=&agedOver=`, `/{id}`, `/{id}/trace` | Read | `RECONCILIATION_INVESTIGATE` | — |
| Assign; note; link evidence; reclassify | `POST /reconciliation/breaks/{id}/assignment`, `/notes`, `/evidence-links`, `/classification {type, reason}` | State; keyed; keyed; state | `RECONCILIATION_INVESTIGATE` | — |
| Propose a resolution | `POST /reconciliation/breaks/{id}/resolutions {kind, reasonCode, narrative, targetAccountId?, offsetItemId?, chosenExpectationId?}` | Keyed; one live per break | `RECONCILIATION_RESOLVE` | Posting kinds create a `RECONCILIATION`-origin ledger proposal; nothing posts |
| Propose a batch repudiation | `POST /reconciliation/batches/{settlementBatchId}/repudiation {reasonCode, narrative}` | Keyed; one live per batch | `RECONCILIATION_RESOLVE` | As a resolution |
| Approve, reject, withdraw | `POST /reconciliation/resolutions/{id}/approval`, `/rejection {reason}`; `DELETE /reconciliation/resolutions/{id}` | State | `RECONCILIATION_RESOLVE`, the approver never the proposer | Approval synchronous: one transaction |
| Rule sets | `GET`/`POST /reconciliation/rule-sets`, `POST /{id}/approval`, `/rejection` | Keyed; state | `RECONCILIATION_ADMINISTER`, four-eyes | A new version governs only forward decisions |
| Reports | `GET /reports/reconciliation/positions`, `/suspense`, `/unmatched`, `/summary?date=`, `/provider-costs?month=` | Read | `RECONCILIATION_INVESTIGATE`, audited | Amounts live here, never in a metric (ADR-0072) |

*(The readmission row widened by the Phase 7 → 8 transition's consistency review, A11; its
attester named, and held at the database, by the transition's re-check, R5.)*

**The ledger's door changes, request shapes unchanged.** `POST /v1/ledger/adjustments` assigns
`MANUAL_CORRECTION` server-side and refuses reconciled positions
(`422 ledger.AdjustmentOnReconciledPosition`); `POST /{id}/approval` and `DELETE /{id}` refuse
`RECONCILIATION`-origin proposals (`409 ledger.AdjustmentOriginMismatch`).

**New `ERROR_CONTRACT.md` codes**, each joining with the task that raises it:
`settlement.SourceUnknown`, `SourceRetired`, `FileTooLarge`, `DeliveryRefused`, `FileNotFound`,
`FileNotAttestable`, `AttestationBySubmitter`, `FileNotRejected`; `reconciliation.BreakNotFound`,
`BreakTerminal`, `ResolutionAlreadyProposed`, `ResolutionNotPending`, `SelfApprovalRefused`,
`ResolutionKindNotAllowed`, `ReasonCodeNotAllowed`, `ResolutionTargetRefused`, `ResolutionStale`,
`RecordAlreadyMatched`, `RuleSetNotPending`, `RuleSetActivationBySameActor`,
`ToleranceNotPermitted`, `ReprocessingInProgress`, `RunNotBlocked`, `GainNotYetEligible`;
`ledger.AdjustmentOriginMismatch`, `ledger.AdjustmentOnReconciledPosition`.

## 10. Event architecture

Terminal facts publish (ADR-0044's doctrine), written through the outbox on the acting connection
(`INV-EVT-01`) with the full ten-field envelope (`INV-EVT-03`), `event_version` and
`schema_version` 1. Payloads carry **identifiers, enums and counts only** — never an amount, a
reference value, a note or a file byte. Correlation is the ingesting request's id, stored on the
file and the run and restored per chunk; causation is the file, run, decision, break or resolution
id.

| Event | Producer / aggregate | Meaning | Payload | Task |
|---|---|---|---|---|
| `settlement.SettlementFileRejected` | settlement / file | Evidence refused whole | fileId, sourceCode, rejectionCode | `P8-TSK-008` |
| `settlement.SettlementBatchAccepted` (replaces the planned `SettlementBatchIngested`) | settlement / batch | Recognised once | batchId, fileId, sourceCode, sourceSequence, lineCount, journalEntryId? | `-009` |
| `settlement.SettlementBatchRepudiated` | settlement / batch | Recognition reversed by four-eyes | batchId, resolutionId, reversalEntryId | `-023` |
| `reconciliation.ReconciliationRunCompleted` (replaces the planned per-record `SettlementMatched`) | reconciliation / run | Every item disposed | runId, batchId, sourceCode, counts per outcome | `-011` |
| `reconciliation.SettlementExpectationSettled` | reconciliation / expectation | Fully allocated | expectationId, kind, operationRef, sourceCode | `-011` |
| `reconciliation.SettlementExpectationOverdue` (replaces the planned `settlement.SettlementExpectationUnmet`) | reconciliation / expectation | Aged past its bound | expectationId, kind, operationRef, expectedBy, breakId | `-013` |
| `reconciliation.ReconciliationBreakRaised` | reconciliation / break | A discrepancy recorded | breakId, type, cause, severity, sourceCode, subject ids | `-010` |
| `reconciliation.BreakInvestigationStarted` | reconciliation / break | First assignment | breakId, assigneeId | `-014` |
| `reconciliation.BreakResolved` | reconciliation / break | Terminal | breakId, resolutionId, kind, reasonCode, journalEntryId? | `-012` (first, for `EVIDENCED`), extended to the person kinds in `-015` |
| `merchant.MerchantPayoutReturned` | merchant / payout | A return fact | payoutId, merchantId, journalEntryId | `-019` |

*(`BreakResolved`'s first producer corrected to `P8-TSK-012` by the Phase 7 → 8 transition's
consistency review, B3.)*

DELIVERY_PLAN's `AdjustmentPosted` event is **dropped**: it collides with the audit action
`ledger.AdjustmentPosted`, and `BreakResolved.journalEntryId` with `ledger.JournalEntryPosted`
already carries it. `MODULE_ARCHITECTURE.md`'s event lines are amended by the transition.

## 11. Security and audit

**Permissions** — a permission exists when a distinct trust decision does; each arrives with its
first route:

| Permission | Trust decision | Arrives with |
|---|---|---|
| `SETTLEMENT_INGEST` | Introducing or attesting evidence that will move money | `P8-TSK-003` |
| `RECONCILIATION_INVESTIGATE` | Reading both sides (raw files audited per access) and investigating | `P8-TSK-003` |
| `RECONCILIATION_RESOLVE` | Money-moving, template-bound correction under four-eyes, repudiation included | `P8-TSK-015` |
| `RECONCILIATION_ADMINISTER` | Deciding what counts as a match (rule sets); reprocessing, requeueing, readmission, the backfill | `P8-TSK-007` |

**Roles, pairwise disjoint** (`RoleName`; `RoleNameTest`'s exact grants):
`RECONCILIATION_OPERATOR` = {`SETTLEMENT_INGEST`, `RECONCILIATION_INVESTIGATE`,
`RECONCILIATION_RESOLVE`}, admitted by identity `V016` in `P8-TSK-003` holding the first two, with
`RECONCILIATION_RESOLVE` joining in `P8-TSK-015`; `RECONCILIATION_CONTROLLER` =
{`RECONCILIATION_ADMINISTER`}, admitted by identity `V017` in `P8-TSK-007`. A person needing both
holds both roles, and that grant is recorded. `LEDGER_OPERATOR` is not extended: the desk that moves
money does not reconcile it. Refusing a person who holds `LEDGER_OPERATOR` together with a
reconciliation role, and "the resolver is not the operation's actor", are recorded for Phase 15.

**Four-eyes subjects** (`INV-AUD-04`): upload attestation (`INV-SET-07`); every value-bearing or
posting resolution; batch repudiation; rule-set activation. Person-distinctness is by actor id, at
the domain, the `CHECK` and — for postings — ledger `V010`. The known debt that operators are
audited as `CUSTOMER` (Phase 15's) does not weaken distinctness, because the resolution rows hold
both ids; it is flagged for the gate.

**Keys and credentials** — `ConfinedCredentialVariablesTest` from eleven to sixteen, each with a
loopback-only default and one key per concern: `FINAPP_SETTLEMENT_FILE_KEY` (`P8-TSK-002`; a
`KeySpec` with suffix `/settlement-file`, `EXACTLY_32`, the `DisputeEvidenceKey` precedent) and
`FINAPP_SETTLEMENT_PSP_REPORT_KEY`, `FINAPP_SETTLEMENT_SCHEME_REPORT_KEY`,
`FINAPP_SETTLEMENT_PAYOUT_REPORT_KEY`, `FINAPP_SETTLEMENT_BANK_STATEMENT_KEY` (`P8-TSK-021`) —
report access is not the money-moving API. *(Twelve if `P8-TSK-021` is deferred.)* The pull's
source URLs join `ProviderTransportGuard`, which since the Phase 7 → 8 transition's gate refuses
to start the application when a provider base URL is not `https` off loopback: `P8-TSK-021`
extends it to every settlement source's URL, SFTP or HTTPS.

**Evidence at rest** (`INV-REC-10`). Files are stored as ordered chunks of at most 1 MiB, each
AES-256-GCM with a 12-byte nonce and a `key_version`, the **AAD binding `file_id ‖ source_id ‖
content_sha256 ‖ seq`** so no chunk can be swapped across files, sources or positions. The
whole-plaintext SHA-256 is verified on every read; a mismatch serves nothing and audits the
failure. Content is read only through `content-reads`, reasoned, one audit record per read; a
guessed id records nothing. The existing ciphers gain AAD separately (`X-TSK-008`).

**The door screen** (ADR-0066). `SettlementFormat.screen(bytes)` is pure and runs in memory before
anything is stored. Bytes that parse structurally are checked field by field: reference fields
against their shape expressions, so an ARN or a 15-digit network transaction id is never tested as
free text; amounts and dates by type; only adapter-declared free-text fields for Luhn-valid 13–19
digit runs and IBAN, account or alias shapes; and a field that fails its declared class is
screened as free text before the file can be stored as malformed, so a card number in a reference
column is refused, never retained *(ADR-0066 §3; the Phase 7 → 8 transition's consistency review,
C6)*. Bytes that do not parse are screened whole and
conservatively: a clean malformed file is stored and rejected by the parse leg, a dirty one
refused. A refusal writes **only** a `settlement.refused_delivery` metadata row — source, checksum,
length, format version, reason, line number, field name, channel, actor, correlation, **never the
value** — an audit record and `finapp.settlement.delivery.refused`. Recovery is re-presentation;
a tokenising pre-processor outside the boundary is the recorded requirement for real formats.
`PaymentEndpointDatabaseTest#instrumentInputRestsNowhereButItsReference`'s sweep and
`PayByBankDatabaseTest#theDestinationReachesNoSink`'s needle extend over both new schemas and a
settlement flow, the needle riding a statement whose free text carries an IBAN shape.

**Audit.** Every privileged act has a catalogued action, with `requiresReason` wherever a person
judges (a content read, a decline, a readmission, a reclassification, a proposal, a rejection, a
requeue, a reprocess, a rule set, the backfill). Settlement's: `SettlementFileUploaded`,
`SettlementDeliveryRefused`, `SettlementFileAttested`, `SettlementFileDeclined`,
`SettlementFetchRequested`, `SettlementFileContentRead` (per access), `SettlementFileReadmitted`.
Reconciliation's: `RunRequeued`, `RunReplayed`, `ReprocessingRequested`, `OpeningPositionRecorded`,
`BreakAssigned`, `BreakNoteAdded` (never the body), `BreakEvidenceLinked`, `BreakReclassified`,
`ResolutionProposed`, `ResolutionApproved`, `ResolutionRejected`, `ResolutionWithdrawn`,
`RuleSetProposed`, `RuleSetActivated`, `RuleSetRejected`, and `reconciliation.ReportRead` naming
the report and period only; the ledger's `AdjustmentProposed` and `AdjustmentPosted` accompany the
posting kinds. Platform acts are audited acting-only, losers recording nothing:
`SettlementFileReceivedByPull`, `SettlementFileRejected`, `SettlementBatchAccepted`, one
`ReconciliationRunCompleted` per run, `BreakRaised`, `BreakResolvedByEvidence`,
`PayoutReturnApplied`. Change summaries carry identifiers only (the `UnmatchedConfirmations`
precedent); `SystemActorCallSitesAreEnumeratedTest` gains the five schedules and intake;
`AUDITABLE_ACTIONS.md` §3 gains about thirty-two rows. Application logs are not the audit trail.

## 12. Reconciliation

### 12.1 The accounting model: each counterparty's own position, two evidence hops

1. **Completion** (existing, unchanged) posts to the rail's clearing position and, **in the same
   transaction**, opens an expectation for that line (ADR-0067).
2. **Hop 1 — the report is accepted.** The recognition entry posts only what the platform had not
   recorded: **the counterparty's fees** (DR `PROCESSING_COSTS` / CR the position). The report's
   transaction lines post nothing — they describe value already in the position — and become items
   allocated to their expectations. One `REMITTANCE` expectation of the batch's net is opened on the
   **same** position.
3. **Hop 2 — the bank statement is accepted.** The recognition entry moves cash, DR or CR
   `CASH_AT_BANK`, against each **attributed counterparty's own clearing position**; the bank line
   then allocates to that position's remittance expectation.

There is no in-transit account (ADR-0065). A report of payment is still that counterparty's
obligation, which its clearing position already says; one shared `SETTLEMENT_IN_TRANSIT` would net
the PSP's receivable against the payout provider's payable (`INV-RAIL-04`); one per counterparty
would need a new owner kind (ledger `V002` allows one operational account per purpose and currency)
and would double every recognition entry for information the expectations already carry. "Reported,
awaiting cash" is an expectation-state fact, served by `/settlement-status` and the positions
report. Cash on report is `INV-SET-01` one level up; posting only when report and bank both arrive
couples posting to matching. **What "explained" means:** DR−CR of every clearing position always
equals the signed sum of its open expectation remainders, less its unallocated, unparked items
(§12.7).

### 12.2 When finality and settlement occur, per flow

| Flow | Completion and posting key (existing) | Financial finality | Expectation opened | Reported (hop 1) | Settled (hop 2, cash) |
|---|---|---|---|---|---|
| Card capture (top-up or checkout) | `CAPTURED`; `payment-capture:<attemptId>`, DR `SETTLEMENT_CLEARING` gross | Revocable until the dispute window ends (not modelled as ending in Phase 8); a chargeback is a new movement | `CARD_CAPTURE`, INBOUND | The PSP batch's `CAPTURE` line allocated | A bank credit matching that batch's remittance: DR `CASH_AT_BANK` / CR `SETTLEMENT_CLEARING` |
| Card refund | `COMPLETED`; `payment-refund:<refundId>`, CR `SETTLEMENT_CLEARING` | Final to us once the PSP reports it completed | `CARD_REFUND`, OUTBOUND | A `REFUND` line (netted) | The same remittance |
| Chargeback | Stage `CHARGED_BACK`; `dispute-chargeback:<disputeId>`, CR clearing D | Provisional until the network rules | `CHARGEBACK`, OUTBOUND | A `CHARGEBACK` line | The remittance |
| Chargeback won | `dispute-won:<disputeId>`, DR clearing D | Final at `WON` | `CHARGEBACK_REVERSAL`, INBOUND | A `CHARGEBACK_REVERSAL` line | The remittance |
| Dispute fee | `dispute-fee:<disputeId>`, CR clearing F | Final (the PSP's charge) | `DISPUTE_FEE`, OUTBOUND | A `DISPUTE_FEE` line — an allocating transaction line, not a processing fee | The remittance |
| Card processing fee | **Not posted before Phase 8** (ADR-0060 §6) | — | None; checked against the pinned `provider_fee_schedule` | Recognised at acceptance: DR `PROCESSING_COSTS` / CR `SETTLEMENT_CLEARING` | The remittance, net of it |
| Instant pay-in | `EXECUTED`; `payment-execution:<attemptId>`, DR `INSTANT_CLEARING` | `FINAL_ON_ACCEPTANCE` | `PUSH_PAY_IN`, INBOUND (the announced cycle, when there is one, recorded on the row as an attribute — never a key) | The cycle report's `CREDIT_IN` line | The bank movement of the cycle net against `INSTANT_CLEARING` |
| Unmatched pay-in confirmation — unattributed, or attributed to a named attempt (`AMOUNT_MISMATCH`: pay-by-bank's executed amount differing, the pay-in failed `DECLINED`; `ATTEMPT_CONCLUDED`: an execution on a concluded attempt) | `unmatched-confirmation:<rail>:<ref>`, DR `INSTANT_CLEARING` / CR `SUSPENSE_UNMATCHED`, posted only by the scheme-execution claim's winner | Final (the scheme's credit) | `UNMATCHED_CONFIRMATION`, INBOUND, **plus** a CREDIT suspense item with its `UNKNOWN_EXTERNAL` break (cause `PARKED_ON_RECEIPT`), carrying the parking's `cause`, `named_reference`, `settlement_cycle` and `attempt_id` | A `CREDIT_IN` line | The cycle net; the suspense released only by resolution — for an attributed parking, a `TRANSFER_TO_ACCOUNT` to the named attempt's counterparty |
| Instant withdrawal | `COMPLETED`; `wallet-withdrawal:<withdrawalId>`, CR `INSTANT_CLEARING` | `FINAL_ON_ACCEPTANCE` (`INV-REV-03`) | `PUSH_WITHDRAWAL`, OUTBOUND (the stored cycle recorded on the row as an attribute) | A `DEBIT_OUT` line | The cycle net |
| Return payment | `COMPLETED`; `payment-refund:<refundId>`, CR `INSTANT_CLEARING` | `FINAL_ON_ACCEPTANCE` | `PUSH_RETURN`, OUTBOUND, **no cycle** — the cycle is learned from the scheme's report and recorded on the item (`learned_cycle`) | A `DEBIT_OUT` line | The cycle net |
| Wallet book payment, book refund, transfer | Wallet ↔ payable or wallet ↔ wallet | `FINAL_ON_POSTING` | **None**: `SettlementModel.NONE` is `INV-SET-01`'s documented per-rail guarantee (ADR-0059 §4); covered internally by the trial balance and statement derivation | — | — |
| Merchant payout | `COMPLETED`; `merchant-payout:<payoutId>`, CR `PAYOUT_CLEARING` | Instructed and irrevocable at the provider; returnable by the beneficiary bank | `MERCHANT_PAYOUT`, OUTBOUND | The payout report's `PAYOUT_EXECUTED` line | A bank debit matching that day's remittance: DR `PAYOUT_CLEARING` / CR `CASH_AT_BANK` |
| Payout return (new) | The merchant fact `payout_return`; `merchant-payout-return:<payoutId>`, DR `PAYOUT_CLEARING` / CR `MERCHANT_PAYABLE`; the payout stays `COMPLETED` (`INV-LIFE-04`) | A new operation | `PAYOUT_RETURN`, INBOUND, with no key of its own | The payout report's `PAYOUT_RETURNED` line allocated to it through the operation-anchored rule (§12.5) | The day's remittance nets it |

*(The Phase 7 → 8 transition's consistency review, A5: the announced settlement cycle is an
attribute — ADR-0067 §5's column on the expectation row, added in reconciliation `V002` — compared
at matching as a tie-breaker and carried as a report dimension, never an `expectation_key` kind,
because one cycle names many operations and a key would collide on every operation after the
first. The unmatched-confirmation row describes the parking as the transition's gate left it,
payments `V023`.)*

Expectations are dated `expected_by = posting_date + lag_days[kind]` from the active rule set,
pinned on the row. Seeded: card 3 days, refund and dispute 3, instant 1, payout 2, remittance
`funding_lag_days` 2, timing tolerance 2.

### 12.3 Recognition postings

Settlement's, entry type `POSTING`, the system actor:
- one entry per accepted batch, key `settlement-batch:<batchId>` under scope `ledger.post`, the
  reference the batch id;
- **`posting_date = batch.accepted_on`** — the UTC business date stamped **once** on the batch row
  in the acceptance transaction and read only from the row thereafter: an open period by
  construction, never back-dated to the bank's booking date; **`value_date`** the evidence's value
  date;
- amounts folded with `Money`; the declared trailer net must equal the fold, or the file was already
  `REJECTED(CONTROL_TOTAL_MISMATCH)`;
- the posting is the **last statement** of the acceptance transaction, after the run, the items, the
  keys and the remittance expectation, keeping the hot clearing projection row locked briefly;
- zero lines are omitted; an all-zero batch is accepted with `posting_omitted = true` and no entry;
- entries are bounded to **at most 16 lines** (ledger `V004` re-validates an entry once per line),
  pinned by a test.

Let F be the fee lines (processing, scheme, bank; a rebate has the opposite direction) and
N = T_in − T_out − F.

| Source | Lines | Remittance expectation opened |
|---|---|---|
| PSP report | DR `PROCESSING_COSTS` F / CR `SETTLEMENT_CLEARING` F (the mirror for a net rebate) | \|N\| on `SETTLEMENT_CLEARING`, direction sign(N), key `REMITTANCE_REF`, `expected_by = value_date + funding_lag_days` |
| Scheme cycle | DR `PROCESSING_COSTS` F / CR `INSTANT_CLEARING` F | \|N\| on `INSTANT_CLEARING` |
| Payout report | DR `PROCESSING_COSTS` F / CR `PAYOUT_CLEARING` F (the liability grows: the provider is owed the fee) | \|N\| on `PAYOUT_CLEARING`, typically OUTBOUND |
| Bank statement | `CASH_AT_BANK` DR Σcredits and CR Σdebits, netted to one line; per attributed source s, CR clearing_s by (credits_s − debits_s), or DR when negative; DR `PROCESSING_COSTS` for bank fees; unattributed lines CR `SUSPENSE_UNMATCHED` Σcredits and DR Σdebits, each line opening a suspense item with an `UNKNOWN_EXTERNAL` break (cause `BANK_LINE_UNATTRIBUTED`) in this transaction | None — bank items allocate to remittances |

A report's transaction lines post nothing. **The ledger's cash moves only here, and on a
statement's repudiation** (`INV-SET-06`). Bank-line attribution is normalization, not matching:
each descriptor declares a `remittancePattern`, the bank adapter extracts only the structured
remittance reference (never a name or an account identifier), and a line is attributed to the
**unique** source whose pattern matches — none or two leaves it unattributed.

### 12.4 Park, unpark and offset

Parking moves unexplained value out of the counterparty's position into suspense, **in the
transaction that decides it** (a chunk, the grace leg or a rematch). For an item on position P: an
INBOUND remainder u posts DR P u / CR `SUSPENSE_UNMATCHED` u and opens a CREDIT suspense item; an
OUTBOUND remainder posts DR `SUSPENSE_UNMATCHED` u / CR P u and opens a DEBIT suspense item; a later
allocation of a parked item (unpark), or a correction offset, posts the exact inverse and releases
the item. Parks and unparks are aggregated per transaction and position into one entry of at most
four lines, key `recon-suspense:<parkId>`, where the `reconciliation.park` row minted in that
transaction names every suspense item it opened or released; posting date the park row's
`decided_on` (stamped once), value date the item's settlement date; only seeded accounts are
touched. The Phase 7 unmatched confirmation keeps its own entry and gains, through the port, its
`UNMATCHED_CONFIRMATION` expectation (`P8-TSK-005`) and its CREDIT suspense item with a
`PARKED_ON_RECEIPT` break (`P8-TSK-020`); rows that already exist are adopted by an idempotent
backfill. The suspense item keys on what the parking recorded (payments `V023`: `cause`,
`named_reference`, `settlement_cycle`, `attempt_id` exactly when attributed), and its raw statement
is reached by the stored identifier (`payments.provider_evidence.unmatched_confirmation_id`). An
attributed parking (`AMOUNT_MISMATCH`, `ATTEMPT_CONCLUDED`) names the attempt the value belongs
with, so its natural resolution is a four-eyes `TRANSFER_TO_ACCOUNT` crediting that attempt's
counterparty — a return to the payer is the deferred return-to-sender (§17) — never a guess; an
`UNATTRIBUTED` parking ages like any unknown credit.

**Value enters suspense only** in the transaction that records its owning break, and **leaves
only** by unpark or offset with `EVIDENCED`, an approved resolution, or repudiation (`INV-REC-09`).
It is never permanent: a CREDIT item is resolved by attribution, offset or `RECOGNISE_GAIN` after
its minimum age — where its break type admits the gain (§12.6: never `REVERSAL_MISMATCH`,
`REFUND_MISMATCH` or `CURRENCY_MISMATCH`, whose credit is resolved by attribution or evidence); a
DEBIT item by attribution to a counterparty or `WRITE_OFF` at any age (ADR-0070). *(The gain's
exclusions: the Phase 7 → 8 transition's consistency review, A1.)*

### 12.5 Matching

**Canonical lines.** A pure `SettlementFormat` (identifier, version, source kind, `screen`,
`parse`; no I/O, no clock, no database) turns bytes into lines of a closed type — `CAPTURE`,
`REFUND`, `CHARGEBACK`, `CHARGEBACK_REVERSAL`, `DISPUTE_FEE`, `PROCESSING_FEE`, `CREDIT_IN`,
`DEBIT_OUT`, `SCHEME_FEE`, `PAYOUT_EXECUTED`, `PAYOUT_RETURNED`, `COUNTERPARTY_ADJUSTMENT`,
`BANK_CREDIT`, `BANK_DEBIT`, `BANK_FEE`, `OTHER_IN`, `OTHER_OUT` — with a direction from the
platform's view, the ADR-0003 triple (amount above zero), three dates, `raw_record_sha256`, a
`canonical_fingerprint`, and typed references (`PSP_CAPTURE_REF`, `PSP_REFUND_REF`,
`ACQUIRER_REF`, `DISPUTE_REF`, `SCHEME_REF`, `END_TO_END_REF`, `SETTLEMENT_CYCLE` (compared
with the expectation's cycle attribute, never a key), `PAYOUT_PROVIDER_REF`, `OUR_REF`,
`ORIGINAL_REF`, `REMITTANCE_REF`). A gross line with a fee
becomes two lines. The provider mapping is total and its default is never a success: an unknown
well-formed type becomes `OTHER_IN` or `OTHER_OUT` — an item and eventually a break, never dropped.
Provider vocabulary lives only in `com.finapp.settlement.format.<format>`
(`SettlementVocabularyIsConfinedTest`). Every file and batch records `format_id` and
`format_version`, each version frozen by golden files; a behaviour change is a new version.

**Sources and keys** (rule set v1, in priority order; every key and alias scoped per source):

| Source (format) | Line type | Keys, in priority | Expectation kind | Cardinality |
|---|---|---|---|---|
| `simulated-psp.settlement` (`SIM_PSP_CSV` v1) | `CAPTURE` | `PSP_CAPTURE_REF`, then `ACQUIRER_REF` through its alias to the attempt | `CARD_CAPTURE` | `ONE_TO_ONE` |
| | `REFUND` | `PSP_REFUND_REF`, then `OUR_REF` (`rfd-…`) | `CARD_REFUND` | `ONE_TO_ONE` |
| | `CHARGEBACK`, `CHARGEBACK_REVERSAL`, `DISPUTE_FEE` | `DISPUTE_REF` plus stage (`DISPUTE_CB_REF`, `DISPUTE_REV_REF`, `DISPUTE_FEE_REF`) | `CHARGEBACK`, `CHARGEBACK_REVERSAL`, `DISPUTE_FEE` | `ONE_TO_ONE` |
| | `PROCESSING_FEE` | `ORIGINAL_REF` → the capture | — | `CHECK` against `provider_fee_schedule` |
| | `COUNTERPARTY_ADJUSTMENT` | `ORIGINAL_REF` | The original's expectation remainder, or the original item's parked excess | `CORRECTION` |
| `simulated-scheme.cycle-report` (`SIM_SCHEME_JSON` v1) | `CREDIT_IN` | `SCHEME_REF`, then `END_TO_END_REF` | `PUSH_PAY_IN` or `UNMATCHED_CONFIRMATION` (both reachable is ambiguity — which `payments.scheme_execution_claim`, one claim per scheme reference, makes a defect signal rather than an expected case) | `ONE_TO_ONE` |
| | `DEBIT_OUT` | `SCHEME_REF`, then `END_TO_END_REF`, then `OUR_REF` | `PUSH_WITHDRAWAL` or `PUSH_RETURN` | `ONE_TO_ONE` |
| | `SCHEME_FEE` | — | — | `CHECK` |
| `simulated-payout.settlement` (`SIM_PAYOUT_CSV` v1) | `PAYOUT_EXECUTED` | `PAYOUT_PROVIDER_REF`, then `OUR_REF` (`pyo-…`) | `MERCHANT_PAYOUT` | `ONE_TO_ONE` |
| | `PAYOUT_RETURNED` | **Operation-anchored**: never key-matched against its payout's OUTBOUND `MERCHANT_PAYOUT` expectation. The item waits `UNMATCHED` — the matcher raises no break — for the return worker (`P8-TSK-019`), which resolves the line's `PAYOUT_PROVIDER_REF` (then `OUR_REF`) through the payout's stored provider reference to the payout row; the rematch then reaches that operation's `PAYOUT_RETURN` expectation by `UNIQUE (kind, operation_ref)`, the payout's own keys read only to find its `operation_ref` | `PAYOUT_RETURN` (opened by the return worker, with no key of its own) | `ONE_TO_ONE` |
| `simulated-bank.statement` (`SIM_STATEMENT_TAGGED` v1, MT940-shaped) | `BANK_CREDIT`, `BANK_DEBIT` (attributed) | `REMITTANCE_REF`, then the value-date group | `REMITTANCE` of the attributed position | `ONE_TO_ONE`, then `GROUP_BY_VALUE_DATE` |
| | `BANK_FEE` | — | — | `CHECK` (the fee posted at recognition) |

*(The `PAYOUT_RETURNED` rule: the Phase 7 → 8 transition's consistency review, A4. Rule set v1 —
seeded by `P8-TSK-004` and frozen — declares it operation-anchored, so no v2 activation is needed
when `P8-TSK-019` lands and nothing changes if it is cut: the item then meets its grace and the
four-eyes fallback. Key-matched, an INBOUND return line would have hit its own payout's OUTBOUND
expectation as a direction mismatch and parked at once as `REVERSAL_MISMATCH`, before the worker
could apply it.)*

A key collision at opening is **never a payment failure**: the expectation is inserted, the
colliding key skipped `ON CONFLICT DO NOTHING`, and an `expectation_event(KEY_COLLISION)` recorded
and counted; the sweep raises `DUPLICATE_INTERNAL` from it.

**The algorithm** — one pure function, `decide(item, candidates, ruleSet) → Decision`, in a chunk
transaction:
1. **Candidates** are the expectations reachable from the item's keys under the pinned rule set, of
   the same source, currency and direction with a remainder, from the highest-priority rule that
   yields any. Two reachable expectations are `AMBIGUOUS_MATCH`; a key hit in another currency is
   `CURRENCY_MISMATCH`, in the opposite direction `REVERSAL_MISMATCH` — neither ever allocates. An
   operation-anchored rule (`PAYOUT_RETURNED`) makes no key hit, so its payout's OUTBOUND
   expectation is never read as a direction mismatch.
2. **Claimant order.** Every allocation goes through one `allocate(E)`, shared by the run, rematch,
   reprocess and manual legs, which serves claimants in `(source_sequence, line_no)` order;
   `source_sequence` is assigned gaplessly at acceptance under the source row's lock, the run leg
   holds namespace `4` per chunk, and a run is eligible only after every lower-sequence run of its
   source completed. The earlier record always wins.
3. **Cardinality.** `ONE_TO_ONE` allocates min(item, remainder): an under-payment leaves the
   remainder with `AMOUNT_MISMATCH` on the expectation; an over-payment parks the excess at once
   with `AMOUNT_MISMATCH` on the item. `PARTIAL` fills in claimant order. `GROUP_BY_VALUE_DATE`
   matches iff the item equals exactly the total of **all** open remittances of that source,
   direction, currency and value date with no other claimant — no subset search. `CORRECTION` tops
   up the original's remainder, or offsets an equal, opposite parked excess on the original item
   (item `OFFSET`, a release with cause `CORRECTION_OFFSET`, an unpark posting), resolving the
   original's break `EVIDENCED`. `CHECK` recomputes the expected fee as round(rate × gross + fixed)
   under the pinned `provider_fee_schedule` (`numeric(7,6)`, named rounding, `INV-MON-03`); beyond
   tolerance it is `FEE_MISMATCH`, commercial, with no residual — the reported fee is already
   expensed.
4. **Timing.** A settlement date later than `expected_by + SETTLEMENT_DATE_DAYS`, or a cycle other
   than the announced one, raises `TIMING_DIFFERENCE` of zero value — or resolves an existing
   overdue break `EVIDENCED` with the timing recorded.
5. **The unallocated remainder.** Definitive classes park at once with their break
   (`DUPLICATE_EXTERNAL`, `CURRENCY_MISMATCH`, the `AMOUNT_MISMATCH` excess, `AMBIGUOUS_MATCH`,
   `REFUND_MISMATCH` against a terminal failed refund, `REVERSAL_MISMATCH` against a terminal
   state). Classes late internal evidence could change — `UNKNOWN_EXTERNAL`, `MISSING_INTERNAL`, a
   `PAYOUT_RETURNED` with no return yet — wait `UNMATCHED` until `grace_until`, pinned per rule and
   judged on the database clock, when the grace leg parks them with their break, typed through
   `InternalReferenceLookup` (on the instant rail, a scheme reference no
   `payments.scheme_execution_claim` holds names no completed execution: it types
   `MISSING_INTERNAL` when its other references name an operation still in flight, and
   `UNKNOWN_EXTERNAL` otherwise).
6. **A poisoned item** is contained: a decision with outcome `ERRORED`, the remainder parked, a
   `PROCESSING_ERROR` break, and the chunk continues — the rows behind it are other people's money.

**Decision snapshots** (`INV-REC-04`, `INV-HIST-04`, `INV-REC-07`). Every evaluation writes a
`match_decision` (origin `RUN`, `REMATCH`, `REPROCESS` or `MANUAL`; `rule_set_id NOT NULL`, the
rule's priority, strategy, key kind, outcome, claimant rank and count, date deviation, the applied
timing tolerance, the fee expected, reported and tolerated) and one `match_candidate` row per
candidate it saw (expectation, key kind, amount, currency, direction, `remainder_before`,
`opened_at`). `GET /reconciliation/decisions/{id}` answers "why were these two records matched?"
from those rows alone.

**Versioning and replay.** A rule set is frozen from `PROPOSED` and activated under four-eyes; every
run, decision, allocation, break and expectation pins its `rule_set_id`; a new version governs only
new runs, rematches and explicit `REPROCESS` runs and **never alters a committed allocation, park or
break**. Replay has three meanings: **decision replay** re-runs `decide` over every stored snapshot
under its pinned rule set and appends a `run_replay` verdict — `IDENTICAL` or `DIVERGED`, the latter
raising a CRITICAL `PROCESSING_ERROR` break — writing nothing else, with items merely awaiting
rematch reported as `PENDING_REMATCH`; **reprocess** re-resolves candidates now, for residual items
only, as new decisions, never edits; **order-independence** is a property test over the pure layer.
The honest statement: a decision is a pure function of its stored snapshot and pinned rule set, so
replay is exact; which candidates it saw depended on what was recorded when it ran, which is exactly
why the snapshot is stored.

### 12.6 Breaks and their resolutions

| Type | Detected by | Parked | Base severity | Allowed resolutions |
|---|---|---|---|---|
| `MISSING_EXTERNAL` | The ageing sweep: no allocation past `expected_by + SETTLEMENT_DATE_DAYS` | No — it stays in the position | MEDIUM; HIGH for `MERCHANT_PAYOUT` and `REMITTANCE` | `EVIDENCED`, `WRITE_OFF` (INBOUND), `TRANSFER_TO_ACCOUNT` (OUTBOUND) |
| `MISSING_INTERNAL` | The grace leg, via the lookup: the operation known, not completed | Yes | HIGH | `EVIDENCED`, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT), `RECOGNISE_GAIN` (CREDIT, after the minimum age) |
| `UNKNOWN_EXTERNAL` | The grace leg; an unattributed bank line; `PARKED_ON_RECEIPT` | Yes | HIGH; CRITICAL when OUTBOUND | As above: `EVIDENCED`, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT), `RECOGNISE_GAIN` (CREDIT, after the minimum age) |
| `AMOUNT_MISMATCH` | `ONE_TO_ONE` with a different amount | The over-part only | HIGH | `EVIDENCED`, `WRITE_OFF` (an INBOUND remainder, or a DEBIT item), `TRANSFER_TO_ACCOUNT`, `RECOGNISE_GAIN` (CREDIT, after the minimum age) |
| `CURRENCY_MISMATCH` | A key hit in another currency; never converted (`INV-MON-04`) | Yes | HIGH | `EVIDENCED`, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT); **no `RECOGNISE_GAIN`** — a currency break is never income |
| `FEE_MISMATCH` | A fee check beyond tolerance | No — already expensed | MEDIUM | `ACKNOWLEDGE` (four-eyes) |
| `DUPLICATE_EXTERNAL` | The expectation already fully allocated, or a repeated fingerprint | Yes | HIGH | `EVIDENCED`, `OFFSET_SUSPENSE`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT), `RECOGNISE_GAIN` (a recovery: CREDIT, after the minimum age) |
| `DUPLICATE_INTERNAL` | A recorded key collision, or a reclassification | No | HIGH | `ACKNOWLEDGE`, `WRITE_OFF` |
| `AMBIGUOUS_MATCH` | Two or more candidates | Yes | MEDIUM | `MANUAL_MATCH`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT), `RECOGNISE_GAIN` (CREDIT, after the minimum age) |
| `TIMING_DIFFERENCE` | A late match beyond tolerance; a cycle mismatch | No — zero value | LOW | `ACKNOWLEDGE` (one person) |
| `REVERSAL_MISMATCH` | A contradicted direction; a capture on a voided or failed attempt; a reversal without `WON`; a return that cannot apply (cause `RETURN_NOT_APPLICABLE`) | Yes | HIGH | `EVIDENCED`, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT); **no `RECOGNISE_GAIN`** — the value is a merchant's or a customer's |
| `REFUND_MISMATCH` | A `REFUND` line against a refund that failed internally, or none | Yes | CRITICAL | `EVIDENCED`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT); **no `RECOGNISE_GAIN`** — the value is a customer's or a merchant's |
| `SETTLEMENT_MISMATCH` | `REMITTANCE_DIFFERS`; `STATEMENT_GAP`; `OPENING_BALANCE` | Per side | HIGH; CRITICAL for statement causes | `EVIDENCED`; for `REMITTANCE_DIFFERS` only, `WRITE_OFF` (an INBOUND remainder, or a DEBIT item), `TRANSFER_TO_ACCOUNT` and `RECOGNISE_GAIN` (CREDIT, after the minimum age). The statement causes close only `EVIDENCED` (`INV-SET-06`) |
| `PROCESSING_ERROR` | An errored item; a blocked run; a diverged replay | Items, yes | CRITICAL | Reprocess or requeue, then `EVIDENCED`; otherwise, for a parked item, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT), `RECOGNISE_GAIN` (CREDIT, after the minimum age) |

*(The Phase 7 → 8 transition's consistency review, A1–A3. ADR-0069's per-type table is the one
authority for which kinds a break type admits, and this table carries it identically, as do
`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §6 and `RECONCILIATION_MODEL.md` §8. On every type
that owns a suspense item, `WRITE_OFF` applies to a DEBIT item at any age and `RECOGNISE_GAIN` to a
CREDIT item after the minimum age, both four-eyes (ADR-0071) — except that `REVERSAL_MISMATCH` and
`REFUND_MISMATCH` admit no gain, because the value belongs to a merchant or a customer and is
resolved by `TRANSFER_TO_ACCOUNT` or `EVIDENCED`, and `CURRENCY_MISMATCH` admits none, because a
currency break is never income. `DUPLICATE_EXTERNAL` admits the gain for a recovery after a
write-off; `SETTLEMENT_MISMATCH`'s posting kinds are `REMITTANCE_DIFFERS`'s alone.)*

Classification precedence: the definitive specific types, then `MISSING_INTERNAL`, then
`UNKNOWN_EXTERNAL`. Severity is deterministic — the base by type and direction, one level per ageing
band crossed (0–2, 3–7, 8–30, over 30 days) and one level at or above the pinned per-currency
`high_value_minor` — stored at raise, escalations appended. Age is `now() − raised_at` on the
database clock; alerts at CRITICAL over 0 hours, HIGH over 1 day, MEDIUM over 5, LOW over 15. One
open break per (type, subject) by partial uniques; a recurrence is a new break with
`follows_break_id`; no `DELETE` grant and a refusing trigger (`INV-REC-02`). A break reaches its
evidence (`INV-REC-01`) through its expectation, item or suspense item: the settlement line, file,
batch and raw content; the payments or merchant record, its journal entry and
`payments.provider_evidence`; the decision and its candidates.

**Resolution kinds** (ADR-0071). The proposer chooses the kind, the reason code, the narrative and
(for transfers) the target; **the lines are derived from the subject's current remainder, never
typed**.

| Kind | Applies to | Lines (entry `ADJUSTMENT`, scope `ledger.adjust.approve:<proposalId>`) | Approvers |
|---|---|---|---|
| `EVIDENCED` | Any break explained by a zero-residual allocation or offset | None of its own — the allocation's unpark or offset is the posting; the resolution names the decision and the park | The platform only |
| `ACKNOWLEDGE` | `TIMING_DIFFERENCE`, `FEE_MISMATCH`, `DUPLICATE_INTERNAL` | None | One when the value is zero; otherwise two |
| `WRITE_OFF` | An INBOUND remainder in P; a DEBIT suspense item, at any age | DR `RECONCILIATION_LOSSES` / CR P (or CR `SUSPENSE_UNMATCHED`) | Two |
| `TRANSFER_TO_ACCOUNT` | A CREDIT suspense item; an OUTBOUND remainder in P | DR `SUSPENSE_UNMATCHED` (or DR P) / CR a named `CUSTOMER_WALLET` or `MERCHANT_PAYABLE`, active and in the same currency, share-locked before posting | Two |
| `OFFSET_SUSPENSE` | A CREDIT and a DEBIT suspense item of equal amount and currency | None — the account already nets; both released | Two |
| `RECOGNISE_GAIN` | A CREDIT suspense item older than `gain_min_age_days` (seeded 90), owned by a break type that admits the gain (the table above) | DR `SUSPENSE_UNMATCHED` / CR `RECONCILIATION_GAINS` | Two |
| `MANUAL_MATCH` | `AMBIGUOUS_MATCH` | A `MANUAL`-origin decision chooses one candidate; unpark as for any late allocation | Two — it stands in for the engine |
| `REPUDIATE_BATCH` | An accepted batch | `ReversalService` on the recognition entry (scope `ledger.reverse`, key `settlement-batch:<batchId>`), written by `settlement` through the repudiation port on the approval's connection (§3); append-only counter-allocations (`reverses_allocation_id`), a bank item allocated to the batch's remittance reopened `UNMATCHED`; unparks; items and batch `REPUDIATED`; the file retained. A `BANK_UNATTRIBUTED` item a resolution already released is not released again: the reversal's suspense line opens a new opposite-side item owned by a new `PROCESSING_ERROR` break (ADR-0070 §10); a payout return applied from the batch stands as a merchant fact, its reopened `PAYOUT_RETURN` expectation ageing into `MISSING_EXTERNAL` — the outcome `P8-TSK-023` states (ADR-0073) | Two |

*(`REPUDIATE_BATCH`'s row gains the bank item's reopening, the repudiation port and the two cases
ADR-0070 §10 and ADR-0073 recorded for `P8-TSK-023`: the Phase 7 → 8 transition's consistency
review, A9, A10 and B5. `WRITE_OFF`'s any age and `RECOGNISE_GAIN`'s per-type admission, A1.)*

- **The threshold, defined** (`INV-REC-03`): every resolution with value at issue or a posting is
  four-eyes; a zero-value, zero-posting `ACKNOWLEDGE` is one person's; `EVIDENCED` is the
  platform's. This keeps `AdjustmentService`'s unconditional rule, the proposal row the seam for a
  future pinned de-minimis policy; a value-banded second approver is deferred.
- **Distinctness at three ranks:** the reconciliation domain; `CHECK (status <> 'APPROVED' OR NOT
  four_eyes OR decided_by <> proposed_by)` on `resolution`; ledger `V010`'s `CHECK` and deferred
  trigger beneath.
- **Reason codes** — the closed `ResolutionReasonCode`, an allowed subset per kind, the narrative
  required (1..1000 characters): `COUNTERPARTY_ERROR_CONFIRMED`, `INTERNAL_PROCESSING_ERROR`,
  `DUPLICATE_BY_COUNTERPARTY`, `FUNDS_ATTRIBUTED`, `UNATTRIBUTABLE_AGED`, `TIMING_CONFIRMED`,
  `FEE_ACCEPTED_AS_CHARGED`, `FEE_RECOVERED`, `AMBIGUITY_RESOLVED_BY_EVIDENCE`,
  `IMMATERIAL_DIFFERENCE`, `LOSS_ACCEPTED`, `EVIDENCE_REPUDIATED`, and `EVIDENCE_RECEIVED` (the
  platform only).
- **The ledger binding** (ledger `V015`, `INV-REV-04`): `adjustment_proposal.reason_code`
  (`MANUAL_CORRECTION`, `RECONCILIATION_WRITE_OFF`, `RECONCILIATION_TRANSFER`,
  `RECONCILIATION_GAIN`, `RECONCILIATION_OFFSET`, `UNCODED`) and `origin` (`MANUAL` or
  `RECONCILIATION`), added with defaults so history stays valid and reads `UNCODED`/`MANUAL`; a
  `BEFORE INSERT` trigger refuses `UNCODED` on new proposals; the freeze re-stated over both
  columns; a `BEFORE INSERT` trigger on `adjustment_proposal_line` refuses any `MANUAL`-origin line
  on a `reconciledPositions()` purpose (the three clearings, `SUSPENSE_UNMATCHED`, `CASH_AT_BANK`,
  `PROCESSING_COSTS`, `RECONCILIATION_LOSSES`, `RECONCILIATION_GAINS`). Reconciled positions are
  adjusted only through breaks; otherwise a free adjustment would create unowned suspense or
  unexplained clearing. For posting kinds, resolution `APPROVED` ⇔ ledger proposal `APPROVED` ⇔
  its entry exists, in one transaction. `RECONCILIATION_OFFSET` is kept or dropped by
  `P8-TSK-006`'s design, before `V015` is written — migrations are forward-only, so the closed set
  cannot wait for `P8-TSK-015` *(the Phase 7 → 8 transition's consistency review, B14; ADR-0071)*.
- **Frozen payload and stale approval.** A proposal freezes `proposed_amount`, the break's
  `residual_version` and the ledger proposal's lines (`posting_date = proposed_on`). Approval locks
  break → resolution → subject rows (sorted), re-derives the lines and checks version and amount;
  a mismatch is `409 reconciliation.ResolutionStale`. `residual_version` bumps on every allocation,
  park, release or reclassification touching the subject. An `EVIDENCED` resolution arriving while
  a proposal is pending withdraws it in the same transaction, rejecting its ledger proposal through
  `rejectOwned`.
- **Correction, reversal, adjustment.** A counterparty's correction is new evidence, a
  `COUNTERPARTY_ADJUSTMENT` line; reversal is used only by repudiation (`INV-REV-01`); adjustment
  happens only through a resolution. A line arriving after its expectation was written off finds it
  `RESOLVED_BY_ADJUSTMENT`, parks as a recovery (`DUPLICATE_EXTERNAL`) and closes by a four-eyes
  `RECOGNISE_GAIN` or `TRANSFER_TO_ACCOUNT`; reversing a write-off automatically is deferred.

**Payout returns** (ADR-0073). `app`'s leaderless `PayoutReturnSchedule` reads reconciliation's
`UNMATCHED` `PAYOUT_RETURNED` items and, per item in its own transaction, first re-reads the item
under a share lock, proceeding only while it is still `UNMATCHED`, then calls
`merchant.PayoutReturns.apply`: lock the payout row, found by its stored provider reference (then
by `OUR_REF`); check it is `COMPLETED`, has no return, matches in amount and currency, and that the
payable is active, read `FOR SHARE` before any posting; post `merchant-payout-return:<payoutId>`
(posting date the item's stored batch `accepted_on`, value date its settlement date); then insert
`payout_return` with the entry id — it is append-only, so it is written complete, and its money is
held to the payout's by the composite foreign key; open a `PAYOUT_RETURN` expectation through the
port, with no key of its own. The rematch leg then allocates the item through the operation-anchored
rule (§12.5). The worker and the grace leg meet on the item's row and judge on the locked row, so
either order converges — allocated, or parked with no return applied — raced both ways by
`P8-TSK-013` (the grace leg's side) and `P8-TSK-019`. Any failed check writes nothing, and the
item's grace expires into `REVERSAL_MISMATCH` (cause `RETURN_NOT_APPLICABLE`), resolved by a
four-eyes `TRANSFER_TO_ACCOUNT` to an account that can take it — never a closed merchant's payable,
whose ledger account the merchant close now closes (the transition's gate repair).
`MerchantPayable` gains `payoutsReturned` (`P8-TSK-019`: a payable CREDIT in the return's
`POSTING`, which debits `PAYOUT_CLEARING`) and `reconciliationAttributed` (`P8-TSK-015`, which has
its first poster, `TRANSFER_TO_ACCOUNT`: every payable line in a `RECONCILIATION`-origin
`ADJUSTMENT` entry, whatever it faces, classified first); `P8-TSK-015` also gives the customer
statement derivation its `RECONCILIATION_ATTRIBUTION` label, by the same resolution-entry rule.
*(The Phase 7 → 8 transition's consistency review: the item share lock, the post-then-insert
order, the composite foreign key and the grace-leg race, A6; the operation-anchored rule, A4;
the terms' owners, A12; the origin rule, A13 — classified by its counterparty alone, a
`TRANSFER_TO_ACCOUNT` of an OUTBOUND clearing remainder would read as a capture or a payout
return.)*

### 12.7 The proofs

Report-only verifiers in the `TrialBalance` shape — lock-free, "the scrape is the schedule", report
and never repair — computed in `app` in one `REPEATABLE READ` transaction on one connection,
composing the ledger's `BalanceDerivation` and line reads with reconciliation's and settlement's
read APIs, folded with `Money`, published as verdict counts behind a refresh floor, NaN never zero:

- **The position proof** (`INV-REC-06`), per clearing position P and currency c, with s(INBOUND) = +1
  and s(OUTBOUND) = −1: DR−CR(P, c) = Σ over the expectations on (P, c) of s·(amount − allocated −
  resolved), less Σ over the allocating items on (P, c) still `PENDING` or `UNMATCHED` of s·(amount
  − allocated − parked − offset). Remittances are expectations; fee items are excluded, their effect
  being in the recognition entry. The identity holds before any match, after exact, over and under
  matches, after parks, after cash and after resolutions.
- **The suspense proof:** CR−DR(`SUSPENSE_UNMATCHED`, c) = Σ CREDIT items remaining − Σ DEBIT
  items remaining, **plus a named term for Phase 7's unmatched confirmations not yet adopted** —
  the parkings no suspense item's `origin_ref` names — so the proof is exact before and after
  adoption, the term reading 0 once `P8-TSK-020` gives each parking its suspense item;
  **suspense ownership:** every item with a remainder names an existing break
  (`finapp.reconciliation.suspense.unowned` must read 0).
- **The cash proof:** DR−CR(`CASH_AT_BANK`, c) equals the closing balance of the highest-sequence
  accepted statement of an unbroken chain for c; a gap or `OPENING_BALANCE` break fails it loudly
  until resolved.
- **Completeness** (`INV-SET-02` for every writer): every journal line on the three clearings and
  `SUSPENSE_UNMATCHED` is known — an expectation names its `(journal_entry_id,
  ledger_account_id)`, a suspense item owns it (`INV-REC-09`), or its entry is a batch's, a park's,
  a resolution's, a repudiation's or a payout return's. `finapp.reconciliation.line.unattributed`
  counts the rest (a raw-SQL poster, a missed opener) and must read 0. Pre-Phase-8 history is
  brought in by the keyed opening-position backfill (`P8-TSK-007`) on the clearing purposes; the
  Phase 7 unmatched confirmations' `SUSPENSE_UNMATCHED` lines, which no expectation names, are
  known once `P8-TSK-020` gives each its suspense item. The recorded scale path is incremental
  watermarks. *(The suspense item added to the known list by the Phase 7 → 8 transition's
  consistency review, A7, as ADR-0067 §9 has it.)*

### 12.8 The identifier chain

Every link is a stored identifier, walked with no timestamp join (`RECONCILIATION_MODEL.md`), and
served by `/breaks/{id}/trace` and `/settlement-status`:

| Chain | References preserved |
|---|---|
| External side | `file → batch → recognition entry → run → item → decision (candidates) → allocation, park or break → notes → resolution → adjustment proposal → journal entry` |
| Internal side | `expectation → journal entry → operation → payments.provider_evidence` (or the merchant payout and its `payout_evidence`) |
| Card capture ↔ PSP report ↔ bank | Our capture reference and the PSP's (`capture_provider_reference`), the ARN through `payments.clearing_record` as an alias, the capture's entry; the batch's external reference and remittance reference; the bank line's `REMITTANCE_REF`. A second presentment's references rest only in the retained evidence (ADR-0059 §5); its cleared-amount evidence is Phase 8's clearing-level matching's (§2) |
| Instant payment ↔ cycle report ↔ bank | Our end-to-end reference, the scheme's reference and its one `payments.scheme_execution_claim` subject, the cycle token (an attribute announced on the expectation, or learned on the item for a return), the `INSTANT_CLEARING` entry; for a parking, the `unmatched_confirmation` row (`named_reference`, `attempt_id` when attributed) and its raw statement through `provider_evidence.unmatched_confirmation_id` |
| Payout ↔ payout report ↔ bank | Our `pyo-` reference, the provider's reference, the `merchant-payout:` entry, and for a return the `payout_return` row and its `merchant-payout-return:` entry |
| Dispute ↔ PSP report | The provider dispute reference per stage, each stage's entry and the fee's entry |

`SETTLEMENT_CLEARING` stays the card rail's, `INSTANT_CLEARING` the scheme's and `PAYOUT_CLEARING`
the payout provider's; no source's evidence posts to another counterparty's position
(`INV-SET-05`, `INV-RAIL-04`).

## 13. Testing strategy

Per `TESTING.md`'s tiers: hermetic unit tests for every machine, every format version (golden
files, each field malformed, trailer mismatches, BOM, CRLF, empty, oversize, duplicate line
numbers, deterministic fingerprints, the screen's field classes — a Luhn-valid 15-digit network
transaction id **not** refused — and no raw value in an error or log) and the pure matcher (every
rule, cardinality, key priority and alias; ambiguity; currency and direction mismatch; group
uniqueness; correction offsets; **the shuffled-order property test**; replay of stored snapshots);
provider contract tests for each pull outcome (unavailable, slow, garbage, lost response, not ready,
a checksum lie), `ProviderFailureCoverageTest`'s "reconciliation detects a break" row gaining its
owner; database tests on Testcontainers PostgreSQL for every transaction boundary, constraint and
race, concurrency through `SimulatedInstance` (its own connection, its own clock); ledger invariant
tests — every recognition, park, unpark and resolution balances, the trial balance is zero per
currency, the position, suspense, cash and completeness proofs are zero after every scenario, a
later-day re-acceptance converges on every key (`settlement-batch:`, `recon-suspense:`,
`merchant-payout-return:`), recognition entries at most 16 lines; security tests (negatives per
route; self-attestation, self-approval and self-activation refused at every rank; `RoleNameTest`'s
disjointness; the plaintext needle over `file_chunk` and captured logs; a tampered chunk and an AAD
swap refused; read audits counted; refusals storing nothing but metadata; key confinement;
`UPDATE` and `DELETE` refused on every evidence table for every writer); static rules
(`SettlementVocabularyIsConfinedTest`; the clearing-purpose confinement widened; only bank
recognition and repudiation name `CASH_AT_BANK`; the expectation-opener register — every
externally settling completion's clearing-touching posting key has a test proving its expectation; `EverySettlingPositionHasASource`
with **a planted uncovered rail**; module isolation); and mutation probes recorded in
`MUTATION_TESTING.md` for every `Phase: 8` invariant read from the catalogue, every verdict read
from the failing testcases, every restore byte-identical, including the **replay-perturbation
probe** (a strategy constant changed → `DIVERGED` plus a CRITICAL break).

Fixtures stamp from the test clock, leases and permits lapse a minute back (the measured VM drift),
and windows are driven by moving stored dates, never by sleeping. The new fixture
**`SimulatedSettlementReports`** renders each format from the simulated providers' own records —
the payments and merchant tables, what the provider "did" — with injectable faults: a duplicate
line, a dropped line, an amount delta, a fee delta, a late date, an unknown line, a wrong currency, a
malformed field, a bad trailer, a PAN or IBAN in free text, a cycle shift, a statement gap, a
non-zero opening, a payout return, a counterparty correction. `SimulatedProvider` serves report
endpoints (200, 404 not yet, a truncated body, garbage, a lost response).

**The owner's ten scenarios, each a counted test, not an argument:**

1. **The same settlement file twice** —
   `SettlementFileReceptionDatabaseTest#theSameFileTenTimesIsOneFile`, sequentially and ten ways
   across upload and racing pulls: one file, batch, run and recognition entry; ten receipts, nine
   `DUPLICATE`; no extra decision or park.
2. **The same record twice in one file** — two lines of one fingerprint: the first `MATCHED`, the
   second `PARKED` as `DUPLICATE_EXTERNAL` in CREDIT suspense; the remittance includes both; the
   position and suspense proofs zero.
3. **Two workers on one batch** — ten instances drive the run leg: allocations equal the
   single-worker result, replay `IDENTICAL`, each chunk processed once, the losers observed skipping
   on namespace `4`; **and a variant with the try-lock bypassed**, where the uniques and Σ triggers
   alone leave at most one positive allocation per (item, expectation) and no over-allocation.
4. **External without internal** — an unknown reference goes `UNMATCHED` → grace (database clock) →
   `PARKED` with `UNKNOWN_EXTERNAL`; a capture still `UNKNOWN` is `MISSING_INTERNAL`, the sweeper
   completes it, the rematch unparks it, and the break resolves `EVIDENCED`.
5. **Internal without external** — an expectation passes `expected_by` → `overdue_since`, exactly one
   `MISSING_EXTERNAL` under ten ageing sweepers, the gauge moves; nothing is raised inside the
   window.
6. **The amount differs** — under: the remainder on the expectation, `AMOUNT_MISMATCH`, a four-eyes
   `WRITE_OFF` posting exactly the remainder; over: the excess parked, then `TRANSFER_TO_ACCOUNT`;
   and a PSP correction in the next batch nets each case `EVIDENCED`.
7. **The fee differs** — outside tolerance, `FEE_MISMATCH` with the reported fee expensed; exactly at
   the tolerance, none; one unit beyond, a break; the expected fee recomputed under the pinned
   schedule; a dispute-fee line unequal to its expectation is `AMOUNT_MISMATCH`.
8. **The record arrives late** — before grace: matched, nothing posted; after `MISSING_EXTERNAL`:
   `EVIDENCED` with the timing; after parking: unparked, `EVIDENCED`; after a write-off: a parked
   recovery; a late earlier-dated file follows acceptance order.
9. **A worker crashes halfway** — failure injected after N lines (parse), after the posting call
   inside acceptance, mid-chunk (the connection killed), between parse and accept, and between
   accept and the first chunk: nothing partial, resumed elsewhere, no duplicate or lost allocation,
   park, entry or event.
10. **A correction attempted twice** — proposed twice under one key (a replay) and under two keys
    (`ResolutionAlreadyProposed`); ten racing approvers produce one entry; the same approver
    retrying converges; approval or `DELETE` through `/v1/ledger/adjustments/{id}` refused
    `AdjustmentOriginMismatch`; evidence racing approval both ways; an allocation between proposal
    and approval refused `ResolutionStale`.

**`P8-TST-001`, the settlement and reconciliation storm.** Phase 7's multi-rail storm traffic
generates the internal records; the fixture renders prior days' files for all four sources with
seeded faults, delivered twice (upload with attestation and racing pulls), out of order and late,
with ten matcher instances, crash injection, rematches, payout returns and resolutions. Every
round, in one `REPEATABLE READ` snapshot, and again at rest: the position, suspense, cash and
completeness proofs; the trial balance; each fault producing exactly its break type and no other;
every item in at most one positive allocation per expectation; replay `IDENTICAL`; and the meters'
tally equal to the tables' (`P7-TSK-015`'s second-tally design, valid because schedules are off and
the database is private to the JVM).

**`P8-TST-002`, the break and resolution battery.** Every break type crossed with every allowed
resolution kind; concurrent approvals; evidence against approval; offsets and claw-backs; a
write-off then a recovery; a repudiation then the genuine re-presentation; four-eyes negatives;
threshold edges (a zero-value single-person `ACKNOWLEDGE` against a value-bearing one);
immutability by privilege and by trigger; `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS` posted
only by approvals.

## 14. Failure scenarios

Each has a test or a documented, accepted rationale (exit criterion 4):

1. A file is delivered twice or ten times: the content address, `DUPLICATE` receipts, one effect.
2. A different file arrives for an accepted batch or statement: `REJECTED(CONFLICTING_BATCH)`,
   retained, alerted; the counterparty corrects by adjustment lines, or by repudiation followed by
   re-presentation — or, for the genuine file already rejected, by its readmission, admissible once
   the conflicting batch is `REPUDIATED` (a byte-identical re-presentation meets the rejected
   file's content address).
3. A file is partially corrupt, or its control totals are wrong: rejected whole, its errors
   recorded; its expectations age.
4. A file carries a PAN or IBAN in free text: refused at the door, metadata only, alerted;
   recovered by re-issue or a corrected format version.
5. The parser throws (our defect): the file stays `RECEIVED`, backed off and visible; readmission
   covers a false rejection.
6. A file never arrives: source silence rises; its expectations age into `MISSING_EXTERNAL`.
7. Files arrive out of order: the arrival sequence decides and nothing blocks; late items rematch;
   statement gaps break until filled.
8. A crash mid-parse, or between parse and accept: rolled back and re-claimed; a `PARSED` file is
   swept.
9. A crash mid-acceptance: one transaction; nothing survives; re-accepted.
10. A crash mid-chunk: rolled back to the cursor; resumed on any instance in the same order.
11. A poisoned item: `ERRORED`, parked, `PROCESSING_ERROR`; the run completes.
12. A poisoned run: `BLOCKED`, a CRITICAL break, the source held visibly, requeued after a fix.
13. An external record with an unknown reference: grace, then `UNKNOWN_EXTERNAL`, parked and aged.
14. The external side settles a capture still `UNKNOWN` internally: grace; usually the sweeper
    completes it and the rematch matches; otherwise `MISSING_INTERNAL`, later unparked `EVIDENCED`.
15. A chargeback appears in the file before its webhook: grace, then a rematch when the stage posts.
16. An external capture on a voided or failed attempt: `REVERSAL_MISMATCH`, parked at once,
    resolved four-eyes.
17. A refund the platform never completed: `REFUND_MISMATCH` (CRITICAL), parked DEBIT.
18. The PSP settles one capture twice: `DUPLICATE_EXTERNAL` in CREDIT suspense; a claw-back
    correction offsets it `EVIDENCED`; otherwise `OFFSET_SUSPENSE` or `TRANSFER_TO_ACCOUNT`.
19. An amount under or over: a remainder on the expectation, or the excess parked, with
    `AMOUNT_MISMATCH`; a correction nets it, or a four-eyes resolution.
20. The wrong currency: never allocated or converted; parked.
21. A fee overcharged: `FEE_MISMATCH`; the fee expensed as reported; a four-eyes `ACKNOWLEDGE`.
22. Settled in another cycle than announced: `TIMING_DIFFERENCE`, zero value.
23. A bank credit before its report: grace, then a rematch when the remittance opens.
24. A bank amount differs from the remittance: `SETTLEMENT_MISMATCH(REMITTANCE_DIFFERS)` — a
    remainder, or a park.
25. A bank line matches no counterparty's pattern: parked at recognition with `UNKNOWN_EXTERNAL`
    (cause `BANK_LINE_UNATTRIBUTED`).
26. A statement gap, or an opening mismatch: `SETTLEMENT_MISMATCH`; the cash proof fails loudly
    until evidence fills it.
27. An internal operation never settles: `MISSING_EXTERNAL`, investigation, a four-eyes `WRITE_OFF`
    or `TRANSFER_TO_ACCOUNT`.
28. A line arrives after its expectation was written off: parked as a recovery
    (`DUPLICATE_EXTERNAL`), then four-eyes.
29. Two operators resolve one break; one correction is approved twice: one live proposal, one entry,
    the other a 409.
30. Evidence arrives while a write-off is pending: `EVIDENCED` wins and withdraws the proposal; after
    approval, the late line parks as a recovery.
31. An allocation lands between proposal and approval: `ResolutionStale`; re-proposed.
32. Self-approval, self-attestation or self-activation: refused at every rank.
33. Approval or rejection through the ledger's generic door: `AdjustmentOriginMismatch`.
34. A free adjustment on a clearing, suspense or cash position: `AdjustmentOnReconciledPosition` at
    the domain and the database.
35. A reference collides internally: `DUPLICATE_INTERNAL`; the payment is never failed.
36. The expectation recorder fails inside a completion: the completion rolls back and the
    redelivery completes both — the accepted coupling (ADR-0067).
37. The attribution target is not postable: ledger `V007` refuses with a 409; the resolution stays
    `PROPOSED`.
38. A payout is returned; its payable is closed: applied once by the worker, which re-reads the
    item under a share lock and converges with the grace leg either way; for a closed payable —
    whose ledger account the merchant close now closes — `REVERSAL_MISMATCH`, the value parked in
    suspense, then a four-eyes `TRANSFER_TO_ACCOUNT` to an account that can take it.
39. A fabricated or mis-normalised batch was accepted: a four-eyes `REPUDIATE_BATCH`, then
    re-presentation of the genuine file, or its readmission when it was already rejected
    `CONFLICTING_BATCH` (an unattested original's readmission itself attested, by a person distinct
    from the readmitter and from the original's uploader).
40. The recognition is replayed on a later day: stored dates make the key converge.
41. The pull response is lost, truncated, garbage or not ready: re-pulled on the permit; the trailer
    rejects a truncated body; the checksum dedupes.
42. A rule-set defect mis-matches items: a new version governs future decisions; committed
    allocations stand as history, and money is corrected by resolution or repudiation.
    Re-allocating committed matches is recorded as out of scope.
43. Tampered ciphertext: the checksum or the AAD refuses it; nothing is served; audited.
44. Clock skew or VM drift: windows are judged on the database clock against stored dates; fixtures
    stamp from the test clock.

*(Scenarios 2, 38 and 39 widened by the Phase 7 → 8 transition's consistency review — the
readmission rule, A11; the item share lock and the grace-leg race, A6; the merchant close's closed
payable account, the gate's repair. Scenario 39's attester named by the transition's re-check,
R5.)*

## 15. Observability

| Series | Kind and tags | What it answers |
|---|---|---|
| `finapp.settlement.file.received` | Counter `source`, `outcome` (new, duplicate) | Throughput; duplicate files |
| `finapp.settlement.delivery.refused` | Counter `source`, `outcome` (the reason) | Refused deliveries (alert) |
| `finapp.settlement.file.rejected` | Counter `source`, `outcome` (the reason) | Ingestion failures |
| `finapp.settlement.file.pending` | Gauge `source` | Non-terminal files, those awaiting attestation included |
| `finapp.settlement.file.age` | Gauge `source` | The oldest non-terminal file (alert) |
| `finapp.settlement.batch.accepted` | Counter `source` | Settlement throughput |
| `finapp.settlement.ingestion.latency` | Timer `source`, `stage` (parse, accept) | Ingestion latency |
| `finapp.settlement.source.silence` | Gauge `source` | Missing or late files: seconds since the last accepted batch (alert) |
| `finapp.settlement.pull.failure` | Counter `source`, `outcome` | Provider report failures |
| `finapp.reconciliation.run.pending` | Gauge `source` | Incomplete runs |
| `finapp.reconciliation.run.age` | Gauge `source` | The oldest incomplete run (alert) |
| `finapp.reconciliation.run.blocked` | Gauge `source` | Blocked runs (alert; must be 0) |
| `finapp.reconciliation.run.latency` | Timer `source` | Acceptance to run completed |
| `finapp.reconciliation.item` | Counter `source`, `outcome` (matched, unmatched, parked, checked, offset, errored) | The match rate |
| `finapp.reconciliation.item.unmatched` | Gauge `source` | Items inside grace |
| `finapp.reconciliation.rematch` | Counter `source`, `outcome` | Late records found |
| `finapp.reconciliation.expectation.open` | Gauge `source` | Open expectations |
| `finapp.reconciliation.expectation.overdue` | Gauge `source` | `INV-SET-02` (alert) |
| `finapp.reconciliation.expectation.overdue.age` | Gauge `source` | The oldest overdue expectation |
| `finapp.reconciliation.break.raised` | Counter `type`, `severity` | Break volume |
| `finapp.reconciliation.break.open` | Gauge `type`, `severity` | The backlog |
| `finapp.reconciliation.break.age` | Gauge `severity` | The oldest open break (alert per severity) |
| `finapp.reconciliation.resolution` | Counter `type`, `outcome` (approved, rejected, withdrawn, evidenced, stale) | Resolutions |
| `finapp.reconciliation.resolution.latency` | Timer `type` | Raised to resolved |
| `finapp.reconciliation.adjustment` | Counter `type` | Adjustment volume, counted |
| `finapp.reconciliation.suspense.open` | Gauge | Open suspense items (`INV-REC-05`) |
| `finapp.reconciliation.suspense.age` | Gauge | The oldest suspense item (alert) |
| `finapp.reconciliation.suspense.unowned` | Gauge | Items without an owning break (must be 0) |
| `finapp.reconciliation.position.proof` | Gauge `purpose` | Currencies failing `INV-REC-06` (must be 0) |
| `finapp.reconciliation.line.unattributed` | Gauge `purpose` | Unknown lines on reconciled positions (must be 0) |
| `finapp.reconciliation.cash.proof` | Gauge `currency` | The cash proof failing (must be 0) |
| `finapp.reconciliation.replay` | Counter `outcome` (identical, diverged) | Determinism |

**No amount in any series** (ADR-0018, ADR-0072): unmatched value, the suspense balance and provider
costs are the audited operator reports of §9. Two tag keys are new, each with its written argument
in `MetricNames` and each joining with its first series: **`source`** (bounded by the compiled
register; no forbidden fragment), with `P8-TSK-002`, and **`severity`** (a closed enum), with the
first severity-tagged series — the break meters, `P8-TSK-024`; `stage` already exists
(`P7-TSK-015`). *(When each key joins: the Phase 7 → 8 transition's consistency review, B6.)* Break type and resolution
kind use `type`, dispositions and reasons `outcome`, positions `purpose`, currencies `currency`.
Every gauge class joins `NoFloatingPointMoneyRulesTest.EXEMPT_CLASSES` — counts, ages in seconds and
verdicts only. Gauges are eager, NaN when unreadable and never zero, behind a refresh floor, and
aggregated with `max()` fleet-wide; counters count committed facts after commit (the
`CommittedRailOutcomes` shape). Each schedule also publishes its `sweeper.enabled` gauge.

`finapp.payments.unmatched.active` and `.age` keep their names — Phase 7's §15 still parses — but
their descriptions are corrected to "parked, ever", and the alertable signal becomes
`finapp.reconciliation.suspense.*` (`P8-TSK-020`).

**Tracing.** Spans `settlement.receive`, `settlement.parse`, `settlement.accept`,
`reconciliation.chunk`, `reconciliation.rematch`, `reconciliation.age`, `reconciliation.resolve`,
with identifier attributes only and one correlation restored per chunk. The durable trace is the
stored identifier chain of §12.8, not a span.

**Dashboard:** a "Settlement and reconciliation" row of ten panels. Alerts on every "must be 0"
gauge, source silence, overdue expectations, suspense age, break age per severity, refused
deliveries and blocked runs.

## 16. Milestones

| Milestone | Items | Acceptance |
|---|---|---|
| M8.1 Evidence intake | `P8-TSK-001`…`-003` | Both modules exist as build-graph facts with their floors; a file uploaded by one person and attested by another is stored encrypted, AAD-bound and checksum-verified, byte-identical on an audited read; a PAN or IBAN shape stores only metadata; ten deliveries of the same bytes are one file |
| M8.2 Every settling completion is expected | `P8-TSK-004`…`-007` | Every externally settling completion's clearing-touching posting key opens exactly one expectation in its own transaction (the opener register; Phase 8's own recognition, park, resolution and repudiation keys discharge and open none), the Phase 7 storm and dispute battery re-run green; free adjustments on reconciled positions refused at both ranks; after the backfill, the position proof reads 0 over the Phase 7 history, and `line.unattributed` reads 0 over the Phase 7 history on the clearing purposes; `SUSPENSE_UNMATCHED` reaches 0 with `P8-TSK-020` |
| M8.3 Card settlement reported end to end | `P8-TSK-008`…`-013` | A PSP report is parsed whole or rejected whole, accepted once with its fees recognised and its remittance expected, and its lines allocated in claimant order with decision snapshots; fees checked and corrections netted; grace, ageing and late evidence working; scenarios 2–5, 7 and 9 demonstrated for the card source, and 6 and 8 up to their write-off legs; the proof 0 after every run |
| M8.4 Investigation and controlled resolution | `P8-TSK-014`, `-015` | Every break traced to its raw file and journal entry by identifiers alone; every person-decided kind four-eyes where value is at issue, posting through `AdjustmentService`, self-approval refused at three ranks; scenario 10, and the write-off legs of 6 and 8 |
| M8.5 Cash confirmed | `P8-TSK-016` | A bank statement recognises cash against each attributed position; capture → reported → cash shown by `/settlement-status`; the cash proof holds; gaps, out-of-order statements and a non-zero opening break |
| M8.6 Every counterparty | `P8-TSK-017`…`-020` | The instant cycle report and the payout report reconciled to cash; the return's cycle learned; a payout return applied exactly once; the unmatched confirmations adopted into suspense with `suspense.unowned` 0; every settling position with its one source (`INV-SET-05`) |
| M8.7 Operating it | `P8-TSK-021`…`-024` | Pull acquisition over per-source credentials; rule sets under four-eyes; reprocess, readmission, requeue and replay; batch repudiation restoring exactly; every §15 series published by a fresh instance, the reports bounded and audited |
| M8.8 Proof | `P8-TST-001`, `P8-TST-002`, `P8-DOC-001` | The storm and the battery green and probed; the Phase 8 exit review against the twelve universal criteria, F1–F8, the seven original Phase 8 criteria and the twenty-one the transition adds (`PHASE_GATES.md`), with the full battery counted fleet-wide from fresh runs |

*(M8.2's `line.unattributed` criterion qualified by the Phase 7 → 8 transition's consistency
review, A7: the Phase 7 unmatched confirmations' suspense lines are known only once `P8-TSK-020`
gives each its suspense item, ADR-0067's follow-up.)*

| Item | Title | Cx |
|---|---|---|
| `P8-TSK-001` | The `settlement` and `reconciliation` modules and schemas | S |
| `P8-TSK-002` | The source register, the encrypted file store and the door screen | M |
| `P8-TSK-003` | The upload door, attestation and audited evidence access | M |
| `P8-TSK-004` | The expectation register and the card completions | L |
| `P8-TSK-005` | Disputes, push rails, unmatched confirmations and payouts open their expectations | M |
| `P8-TSK-006` | Adjustments carry a reason code and an origin; reconciled positions are closed to free adjustments | M |
| `P8-TSK-007` | The opening position, the position proof and the completeness verifier | M |
| `P8-TSK-008` | The PSP format: parse, normalise, reject whole | M |
| `P8-TSK-009` | Acceptance: fee recognition, remittance expectation and reconciliation intake | L |
| `P8-TSK-010` | Breaks and suspense as records | M |
| `P8-TSK-011` | The matcher: ordered allocation with decision snapshots, for the PSP source | L |
| `P8-TSK-012` | Processing fees and counterparty corrections | M |
| `P8-TSK-013` | Grace, ageing, rematch and late evidence | M |
| `P8-TSK-014` | Break reads, the case file and the settlement-status trail | M |
| `P8-TSK-015` | Four-eyes resolution through the ledger | L |
| `P8-TSK-016` | The bank statement: cash recognised, remittances matched | L |
| `P8-TSK-017` | The instant scheme's cycle report | M |
| `P8-TSK-018` | The payout provider's report | M |
| `P8-TSK-019` | Payout returns *(deferral candidate)* | M |
| `P8-TSK-020` | Unmatched confirmations join suspense management | S |
| `P8-TSK-021` | Pull acquisition, per-source credentials and source silence *(deferral candidate)* | M |
| `P8-TSK-022` | Rule-set administration, reprocessing, readmission, run requeue and replay | L |
| `P8-TSK-023` | Batch repudiation *(deferral candidate)* | M |
| `P8-TSK-024` | Meters, the dashboard row and operator reports | M |
| `P8-TST-001` | The settlement and reconciliation storm | L |
| `P8-TST-002` | The break and resolution battery | L |
| `P8-DOC-001` | The Phase 8 exit review | L |

Every task states the gate's twenty-three fields at design time *(the backlog's twenty-three
labelled fields; "twenty-two" corrected by the Phase 7 → 8 transition's consistency review, C3)*;
every task that can affect money carries `DOD-FIN`; the ten-instance answer must be `PASS`, backed
by counted tests. **If the phase must shrink** (O6), `P8-TSK-021` goes first (upload with
attestation suffices), then `-019` (the four-eyes `TRANSFER_TO_ACCOUNT` fallback), then `-023` (the
attestation and pull controls remain), each recorded with an owner.

## 17. What Phase 8 must NOT implement

FX reconciliation or any currency conversion (Phase 9); GL mapping, period close, `INV-ACC-03`/`-05`,
statements, regulatory reporting and fee accrual (Phase 14); fraud or AML scoring of breaks,
reserves, collection of parked chargeback shares and fee pass-through to merchants (Phase 13);
merchant-facing settlement statements (Phase 12); settlement instructions, prefunding, treasury or
liquidity management; return-to-sender of unattributed funds (a new payment capability, deferred);
automatic reversal of a write-off on late evidence; value-banded approver escalation (six-eyes);
object storage and retention deletion (ADR-0066's triggers); PUSH delivery of files, real formats
and connectivity, XML or ISO 20022, multi-part or superseding files; fuzzy, ML or subset-sum
matching; business-day calendars; re-allocating committed matches outside repudiation; moving the
payout onto the push rail (ADR-0073 records the trigger as not fired); a Kafka consumer for any
Phase 8 correctness; a second push rail and its confirmation guard (Phase 7's recorded input);
retrofitting clock-read posting dates in Phase 5–7 flows (`X-TSK-009`); an equity or capital
account for a non-zero opening cash balance (O4); real connectivity to any provider or bank (the
programme's non-goal).

## 18. Risks

- **The co-commit port touches about eight Phase 5–7 call sites**, and a defect could fail payments.
  Mitigated by an infallible insert (collisions become breaks), the opener register,
  failure-injection tests, and the Phase 7 storm and dispute battery re-run inside `P8-TSK-004` and
  `-005`, not only at the review.
- **Pre-Phase-8 history breaks the proofs.** Mitigated by the keyed opening-position backfill and
  the completeness gauge naming each unknown line.
- **A hot `SETTLEMENT_CLEARING` projection row.** Recognition touches it once per batch and parks
  only for exceptions; postings are last in every transaction; entries are bounded to at most 16
  lines (ledger `V004`'s per-line re-validation).
- **Per-source serialisation caps throughput** at one allocating transaction per source. Accepted
  for determinism; the (source, currency) partition path is recorded.
- **Cross-module proofs scan full history per scrape.** A refresh floor applies; incremental
  watermarks are the recorded path.
- **Simulated formats are cleaner than real ones**, which carry names, IBANs and PANs. Mitigated by
  the field-class screen and the recorded requirement for an out-of-boundary tokenising
  pre-processor.
- **Operator burden and rubber-stamp approvals.** Grace windows cut noise, `EVIDENCED` closes
  explained breaks, templates fix the lines, and ageing escalates.
- **Pressure to put amounts in metrics.** Answered by ADR-0072's audited reports.
- **The operator actor-type audit debt** (operators audited as `CUSTOMER`). Resolution rows hold
  both people; raised for the gate.
- **Settlement files in PostgreSQL.** ADR-0066's volume triggers — a source's daily volume above
  256 MiB, total evidence above 50 GiB, a real format exceeding the bound, or production deployment —
  must be watched; access goes only through the `SettlementFileStore` port, so a move is an adapter
  change plus a data migration.
- **Numbering collisions with unmerged branches.** ADR-0064 onward and `X-TSK-008` onward are
  recorded explicitly (ADR-0063 and `X-TSK-005`…`-007` are taken on unmerged branches).
- **The expectation ownership move and renamed events** diverge from DELIVERY_PLAN and
  `MODULE_ARCHITECTURE.md`. Mitigated by ADR-0064 and the transition's DELIVERY_PLAN addendum, with
  provenance.
- **Scope.** Twenty-four tasks plus three across eight milestones. Mitigated by vertical milestones
  (card reported end to end at M8.3, resolvable at M8.4), no XL task, and three named deferral
  candidates (O6).

## 19. The first task

`P8-TSK-001` — the `settlement` and `reconciliation` modules and schemas — is marked `READY` by the
transition, the one task that is. It lays build-graph facts and privilege floors only, before any
domain code (the `P5-TSK-001` and `P6-TSK-001` precedent):

- **Scope:** the `settings.gradle.kts` includes and the build files (per-schema Flyway, from the
  sibling templates); `settlement` `V001` and `reconciliation` `V001` — owner `finapp_migrator`,
  `REVOKE ALL FROM PUBLIC`, `USAGE` alone to `finapp_app`, no tables;
  `SettlementModuleIsolationTest` and `ReconciliationModuleIsolationTest`, each requiring `ledger`,
  `platform` and `sharedkernel` and refusing every sibling, the other new module included, with
  planted probes; every sibling isolation test gaining both; `ModuleBoundaryRulesTest`;
  `ProductionModules` from the classpath.
- **Out:** every table, aggregate, bean, endpoint, permission and event.
- **Invariant:** `INV-LED-04` — neither module writes journal rows directly.
- **Ten instances:** no state.
- **Accept:** the build green; the floors proven live (the ACL exactly
  `{finapp_migrator=UC, finapp_app=U}`, no `PUBLIC`); the isolation asymmetries demonstrated; the
  probes caught.
- **Definition of done:** `DOD-BUILD`, `DOD-ARCH`, `DOD-SEC`.

`P8-TSK-006` depends on nothing within Phase 8, `P8-TSK-002` only on `-001`, and `-004` on `-001`
and `-002` (the compiled register maps a clearing position to its source, and every expectation
and rule set names a seeded source identity); the first task's completion gate marks exactly one
next task `READY`. *(`-004`'s dependencies corrected by the Phase 7 → 8 transition's consistency
review, B1.)*
