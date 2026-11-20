# Phase 17 Exit Review — Operator Console

**Status of this record: DRAFT.** Written 2026-11-20 by `P17-DOC-002` on branch `P17-DOC-002` from `31eb3ddeb` (the integration
tip about to become master). It is not a gate pass. `P17-DOC-002` stays `PLANNED` in the backlog, Phase 17's status is not
changed, and nothing here is the owner's decision. The gap left open on purpose is the **owner items** at the end of this record. The places first marked pending
`P17-TSK-040` were filled from its record at its gate (2026-11-21, `P17-TSK-040`'s branch, master `106da960d` merged in).

**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (the review areas), §3 (the twelve universal exit criteria, and the
financial-phase supplement where it applies), and §5 Phase 17 (the ten criteria, made measurable at `P17-INIT`, and the
"also at the exit" list). The console's half is evidenced by finapp-admin-panel's own records. This review reads them and cites
them. It claims nothing for them in this repository.
**Phase objective under review:** an operator console that staff can use. It is a same-origin single-page application on
the operator edge. It reads and commands only what the platform serves, with no backend-for-frontend and no authority of its own
(finapp-admin-panel ADR-C130…C136). This repository's half is the backend gaps, the money-control remediation, the console's
hosting, delivery and monitoring, and the end-to-end seeds (`BACKLOG.md` §Phase 17).
**Sources read:** this tree at `31eb3ddeb`. finapp-admin-panel at `b883177`, read-only: `docs/PHASE_17_EXIT_REVIEW.md`
(the console's side, written 2026-11-15 at M11 and refreshed 2026-11-20), `docs/FINAPP_BACKEND_WORK.md` (its status table,
"checked 2026-11-20, second pass"), `docs/adr/ADR-C130…C136`, `eslint.config.js` and `tests/eslint-guards.test.ts`.

**The owner's words of 2026-11-20 that bound this review**:
- "we dont need database tests" and "we dont need architecture tests": verification is the unit and slice tiers only. The
  `database`, `kafka`, own-container, `architecture` and `kind` tiers were **not run fresh** for this review. Below, that
  evidence is recorded as **owed, never as passed**.
- "Stop TST-001": `P17-TST-001`, the `kind` end-to-end battery, is stopped. Its partial work is committed, ungated, on its
  own branch `P17-TST-001`, and is not merged.
- `P17-TSK-044`'s cluster half is owed under PI-025. `kind` is not installed on this machine.

**This review ran no test.** Gradle was not run, by the instruction it was written under. Every count cited below is from a task's
own gate, with its date. The fresh unit and slice run on `31eb3ddeb` is **⟦OWED: the coordinator's run at merge⟧**.

| | Outcome |
|---|---|
| Items | **48**: **44 `COMPLETE`**, and four not. `P17-TSK-040` is `PLANNED` and being built now by the coordinator session. `P17-TSK-123` is `PLANNED`, split from `-031` on 2026-11-20 and not started. `P17-TST-001` is `PLANNED` here and stopped by the owner, its work ungated on its own branch. This review, `P17-DOC-002`, is `PLANNED` |
| The ten criteria (§5) | **2 MET** (7 the audit trail; 9 no floating-point money in the client, with a finding); **2 MET WITH DEBT** (3 and 5: their hermetic halves held, their `kind` halves owed); **6 NOT MET** (1, 2, 4, 6, 8, 10) |
| Also at the exit (5 clauses) | **2 MET** (every owed or designed-only clause named here; no compliance wording); **2 MET WITH DEBT** (`-044`'s monitoring, its cluster half under PI-025; `-105`'s designed-only delivery); **1 NOT MET** (the threats: T53's residual awaits the owner, item 73 (a)) |
| Universal criteria (12) | **2 MET** (3, 12 as a draft); **4 MET WITH DEBT** (5, 6, 8, 11 — each needing an owner ruling named below); **6 NOT MET** (1, 2, 4, 7, 9, 10) |
| Financial supplement | Read for the `DOD-FIN` tasks (`-028`, `-031`, `-116`). F3, F4 and F6 hold at their gates' database classes. F7 holds in Java at the gates and in the client by lint, with one finding. F1, F2, F5 and F8 are unchanged by construction: no posting rule changed |
| *"Correct with 10 concurrent instances?"* | **`PASS` by construction, task by task** (43 of the 44 complete tasks answer `PASS`). **`UNKNOWN` counted**: `-104` answers "the owner's postponement, not `PASS`", nothing of Phase 17 has run at three or ten replicas, and the ten-racer database cases shed `503` are red. **No `FAIL`** |
| ADR-C130…C136 | **Proposed for acceptance, none accepted here.** Two can be accepted as written (C130, C135 with its remainder named). Five need a correction in place first (C131, C132, C133, C134, C136), and one of those also waits on `-040` (C133). Also open in this register: **ADR-0152 is still `Proposed`** |
| Threats (`THREAT_MODEL.md`) | T48-T56 re-read against §1.3: **no rating changes**, and the reasons are recorded in place. Open: T53's residual (owner item 73 (a)); T48's and T54's cluster rows `PLANNED` on the stopped `P17-TST-001`; T50's fee-schedule row `PLANNED` on `P17-TSK-123` |
| Mutation register | The console's money-lint row **added** (it was missing). Its §4 row for `P17-TST-001` is due at the flip (below) |
| **Verdict** | **Phase 17 cannot exit yet.** Two things stand in the way. (a) Work not done: `P17-TSK-040`, `P17-TSK-123`, and backend gaps the console's status table lists with no task here (B6.6, B6.8). (b) Evidence the owner has waived or postponed but **not yet accepted as Phase 17's recorded deviation**: the `kind` battery, `-044`'s cluster half, and the database and architecture tiers. Each must be done, or explicitly accepted by the owner as debt, criterion by criterion. **The flip is the owner's, never this review's** |

---

## What the review found

**No CRITICAL defect was found in what is built.** Phase 17 adds no money movement and no invariant. Its `DOD-FIN` tasks
change who may trigger existing postings, not the postings themselves (ADR-0153, ADR-0154).

**IMPORTANT, six:**

1. **Backend gaps the console needs, with no task in this repository.** finapp-admin-panel's status table ("checked
   2026-11-20, second pass") lists three. **B6.6** is the owner's own M6 decisions of 2026-11-19 (D-25), which a customer's
   page needs: the customer's legal name and country, a KYC document download, and request-information as a new KYC state
   "in Phase 17". It reads "Not started — D-25's three items still have no `finapp` task". **B6.8** is three gaps found
   building M6: no read of pending account restrictions (an approver is blind), no read of one dead letter, and the KYC
   case's audit target type unconfirmed. **B7.6** is that pending money-act proposals have no list or read. `P17-TSK-040`
   claims B7.6 and probably B6.8 (1) as four-eyes domains. B6.6, B6.8 (2) and B6.8 (3) have no owner. Criterion 1 reads "every
   gap a shipped module needs is closed in its owning module". **The owner's**: place them in Phase 17, or rule them out of
   the phase with the console's pages saying so.
2. **Six `CURRENT_STATE.md` §Known Architectural Debt rows name Phase 17 as their owning phase**, and none is paid. They are:
   two FX database cases red on the base; two ten-racer `AdjustmentEndpointDatabaseTest` cases; six more red database
   cases; `RoutingPolicyDatabaseTest#mintingIsGaplessUnderTenWriters`; `BnplMerchantDrillDownTest`'s wall-clock bound; and
   `OwnershipIsScopedTest`'s re-import cost. Their trigger is "the whole database tier's run", which the owner waived
   today. A phase cannot exit owning debt it has not paid or handed on. **The owner's**: re-own them under PI-023 (as the
   ten-racer class already is there), or schedule them.
3. **Phase 17's status line reads `READY` (2026-11-17)** with 44 of 48 items done. That is the stale-status class the
   Phase 13 and Phase 16 reviews named. It should read `IN_PROGRESS` until `-040` lands and the owner has ruled, then
   `IN_REVIEW`. It is not changed here, by the instruction this draft was written under. *(Universal criterion 9.)*
4. **Two console ADR decisions are not what was built, and one is not built at all** (see *ADR-C130…C136* for each):
   - ADR-C131 decision 3 says "reads included" for the second factor. `-102` built it for acts only, with reads
     excluded, and the read tasks then chose per route.
   - ADR-C132 decision 2's shared holders were replaced by a partition (`-025`'s two recorded deviations). Its
     `IDENTITY_READ` for `AUDITOR` was never granted (open since `-017`).
   - ADR-C134 decision 6, the server-side audit export under `AUDIT_EXPORT`, is not built. No permission and no task
     exist: `-017` excluded it as "deferred to M10 by D-9 and not in this phase's backlog".

   Each needs a correction before acceptance, and the export needs an owner: a task, a postponed issue, or a struck
   decision.
5. **ADR-C136 decision 4 ("money is never a JavaScript number") is not literally true in the console**, and the lint
   rule has a gap. finapp-admin-panel `src/modules/lending/LendingAdminPages.tsx:422` converts the capital contribution's
   minor units with `Number(amountMinor.trim())`, and sends the result as `amountMinor`. The lint selectors match
   `Number(<identifier or member named amount>)` only, so a conversion wrapped in another call passes them. This is not
   floating-point arithmetic. The value is digits only, at most 16, checked `Number.isSafeInteger`, and this repository's
   contract types the field `int64` (`ContributionProposalRequest.amountMinor`), so the client must send a JSON number.
   **The console's**: either widen the lint to any `Number(...)` whose argument mentions an amount, and keep this one
   conversion under a named, reasoned exception; or correct ADR-C136 decision 4 to admit a minor-unit integer, checked
   safe, where the contract types it as an integer. Criterion 9's demonstration stands as recorded. This finding is beside
   it.
6. **`POSTPONED_ISSUES.md` PI-020 still reads `POSTPONED`, although `P17-TSK-016` built its fix.** PI-020's fix is:
   refuse the holding at assignment, and check the holdings that already exist. `-016` built both, for the owner's seven
   pairs (`RoleConflictsTest`, `RoleConflictDatabaseTest`, `GET /v1/operator/access-review/sod-conflicts`). Whether ADR-0071
   §10's pairwise rule for the break-resolution roles is wholly covered by those pairs is the one question left.
   **The owner's**: mark PI-020 `FIXED` (`-016`, 2026-11-18), or keep it open for that remainder.

**MINOR, recorded:**
- finapp-admin-panel's status table says the merge branch "has not reached `master`". It will be stale once `31eb3ddeb` is
  master. The console's re-pin, `npm run verify` and `npm run test:catalogue` follow (its own "Re-running this review").
- `edge-check.sh` over regenerated routes is owed by several tasks (`-016`, `-032`, `-033`, `-037`, `-039`, `-103` among
  them), as is `terraform test` for `-105`. These are container and cluster runs, owed with PI-025's.
- `-031`'s `MerchantPayoutDatabaseTest` was 31 of 32 on its final bytes. Its suspension-and-dispatch race is "owed a quiet
  re-run".
- `PHASE_GATES.md` §5 Phase 17 still says the CSP reports are routed by `-044`. `-044` decided, and the owner confirmed on
  2026-11-20, not to collect them. That clause is read against the decision under *Also at the exit*.

---

## The ten criteria (§5 Phase 17)

Rulings: **MET** means held by evidence on this tree or in the console's records. **MET WITH DEBT** means the part
executable here is held, and the rest is owed under a standing postponement; it needs the owner's explicit acceptance at
the flip (as Phase 16's acceptance A did). **NOT MET** means something is unbuilt, or unrun with no acceptance.

| # | Criterion | Ruling | Evidence | What remains, and whose |
|---|---|---|---|---|
| 1 | Every shipped console module backed by real operator endpoints; every gap closed in its owning module with negative authorisation tests | **NOT MET** | Per gap task: the route in `docs/api/openapi.json` (regenerated at `eea5a9039` and `633e8455c`); `EdgeAllowListTest`, `RoutePermissionRegisterTest`, `OpenApiContractTest` (slice, fresh at `-031`'s gate, 2026-11-20: 46 tests); a negative test per route in each task's database classes, run at its gate; reads recorded (`RecordedReadsTest`, `AuditableActionRegistryTest`). Console side: ✅ — "every page calls finapp's contract … scripted answers exist in tests only" (its exit review, criterion 1) | `-040` gated (2026-11-21): 37 pending-decision queues and five lists converted, every four-eyes domain's (G-140); B7.6's single reads `-124`'s. `P17-TSK-123`, the fee-schedule doors (G-53's remainder). B6.6 and B6.8 with no task (finding 1): the owner's |
| 2 | The seven scenarios as E2E on `kind`, 3 replicas, through the operator edge, from a fresh run | **NOT MET** | Built and gated: `-104` (the console on `kind`, its hermetic half: `KindRehearsalContractTest#theConsoleIsLoadedAppliedAndAskedAfterTheEdge`); the seeds `-106`, `-108`, `-110`, `-112`, `-113`, `-115`, `-117`, `-119`, `-121`, held test-only (`E2eSeedStaysTestOnlyTest`, architecture, 15 of 15 at `-121`'s gate, 2026-11-20); scenario 4 at the door (`PaymentRefundEndpointDatabaseTest`, `-031`'s gate) | `P17-TST-001` was stopped by the owner ("Stop TST-001", 2026-11-20). Its harness, proxy and spec contracts are ungated and unmerged on branch `P17-TST-001`. `kind` is not installed here. **The owner's**: resume it, or accept the battery as owed (under PI-025 or an item of its own). The gate text says an owed run leaves this criterion `PARTIAL`, never `PASS` |
| 3 | Same origin: `/console/**` off the public edge; the CSP clean across the battery; API routes `default-src 'none'` | **MET WITH DEBT** | `ConsoleHostingManifestsTest#theConsoleHeadersAreTheConsolesOwnDefinition`, `#theApiRoutesKeepTheStrictPolicy` (architecture, green at `-044`'s gate, 2026-11-19: 1,285 in 119); `edge.rego`'s console rules; `-101`'s `OperatorResponsesAreNotStoredTest#everyOperatorAnswerIsNoStore`. Console side: zero CSP violations on every route in both themes (`e2e/catalogue/routes.spec.ts`, last green at D-26) | The `kind` half: `/console` `404` on the public host, the policy header once, zero violations across `P17-TST-001`'s battery. Owed with criterion 2. The owner's acceptance is needed. The architecture tier is not fresh on `31eb3ddeb` (owner's waiver) |
| 4 | No session token at rest, across a full session including step-up and logout | **NOT MET** | Console side: the memory-only holder and the lint bans (ADR-C131); asserted after sign-in and a full route tour (the catalogue audit). Nothing in this repository | Step-up and logout under E2E on `kind`, as `P17-TST-001` (stopped). The owner's: resume or accept |
| 5 | ADR-C131 decision 3: every permissioned act refuses a password-only session, with a negative test per surface class | **MET WITH DEBT** | `EveryOperatorActRequiresAFactorTest#everyActDeclaresAFactor`, `#aPasswordSessionIsRefusedAtEveryAct`, `#everyRoleThatCanActNeedsAFactor` (slice; per act, not per class; fresh at `-031`'s gate, 2026-11-20, after its new doors); `RoleGrantNeedsAFactorTest#aGrantWithNoFactorIsRefused`; probe in `MUTATION_TESTING.md` §2 (`-102`) | The `kind` clause, the console's step-up replaying once under the same key, is owed with `P17-TST-001`. Reads are not covered by this guard (finding 4: ADR-C131 decision 3's wording) |
| 6 | Accessibility: axe zero serious/critical on every route in both themes, plus one manual screen-reader pass | **NOT MET** | **In finapp-admin-panel wholly.** `e2e/catalogue/routes.spec.ts` visits 80+ routes in light and dark: 16 findings fixed at M11, and zero since. "Not re-run for this change (the owner skipped it); its last green run was at D-26" | **The manual screen-reader pass is a person's, and is not done.** The catalogue is to be re-run after the console's re-pin to this tree. The console's and the owner's |
| 7 | The audit trail readable by `AUDITOR`, every read audited, a negative test for every other role | **MET** | `-017`: `AuditTrailApiDatabaseTest`, `AuditTrailDatabaseTest` (run at its gate, 2026-11-18, on the owner's allowance for each task's own database classes), `RoleNameTest`; `audit.RecordsRead` per page served; `ADMINISTRATOR` refused by the owner's decision of 2026-11-18 (ADR-C134 decision 2's default); no grant to update or delete; the indexes from recorded `EXPLAIN` plans (index-only backward scans, 60,000 records); `MUTATION_TESTING.md` §2's `-017` row. Console side ✅ | The audit export (ADR-C134 decision 6) is not part of the criterion; it is finding 4. The whole database tier is universal criterion 7's |
| 8 | Every operator collection a §7 collection or rendering its cap; no capped list presented as complete | **NOT MET** | `-042` and `-122` (the two retrofit batches, `OperatorListKeysetsTest`, `OperatorListRetrofitApiDatabaseTest`), and every list task's cursor test. Console side ✅: "every capped list shows its cap" | `-040` serves the pending-decision lists in one shape (2026-11-21), but its **register guard and `OperatorListKeysetsTest` entries are owed** on the owner's "tests are not needed", and the database tier was waived - the evidence the criterion asks is not there. The console's inbox, one function over them, is finapp-admin-panel's |
| 9 | No floating-point money in the client: the lint rule demonstrated on a planted `parseFloat(amount)`, recorded in `MUTATION_TESTING.md` | **MET** (with finding 5) | finapp-admin-panel M11 (`037612f`, 2026-11-15): planted `parseFloat(amount)` and `Number(amountMinor)` both fail lint. Held in-suite since by `tests/eslint-guards.test.ts` ("money never becomes a JavaScript number"). **Recorded by this review** in `MUTATION_TESTING.md` §2 | Finding 5: the selectors miss a wrapped conversion, and one live conversion sends a safe-integer number. The console's |
| 10 | The multi-instance question answered `PASS` for every backend task | **NOT MET** | 43 of the 44 complete tasks answer `PASS` at their gates, each with its races in its own database classes where the task contends (`-016`'s grant under the identity's lock; `-028`'s restriction under the row's lock; `-031`'s and `-116`'s decisions conditional on `PROPOSED` under `FOR UPDATE`) | `-104` answers "the owner's postponement, not `PASS`": counted on `kind`, not run. `-040` answers `PASS` by construction (reads; a stale decision answers `409` at its door), with no database race evidence - none is owed for a read. `-123`'s answer does not exist yet. The ten-racer database cases shed `503` (finding 2) are test defects by classification, but red. The owner's acceptance of the `kind` count is needed |

---

## Also at the exit (§5 Phase 17)

| Clause | Ruling | Evidence and remainder |
|---|---|---|
| The threats of B11 (T48-T54) each controlled or accepted by the owner, and the browser-cache threat registered | **NOT MET** | T55 is registered with built evidence (`-101`). T48-T56 are re-read here (*Threats*). Each is `CONTROL` with at least one `EXISTS` row. **T53's residual, a compromised workstation, is a `GAP` row on owner item 73 (a), "Awaiting the owner" since 2026-11-17**. Its recommendation is (i): accept, bounded by four eyes, the session bounds and the factor on every act, until a workforce identity provider exists (C13). **The owner's acceptance, never this review's.** T48's and T54's cluster rows read `PLANNED` on `P17-TST-001` (stopped); T50's fee-schedule row reads `PLANNED` on `P17-TSK-123` |
| `-044`'s console monitoring routed: a synthetic probe, pod readiness, CSP reports, asset 404s | **MET WITH DEBT** | `infra/prometheus/rules/console.yml`: `ConsoleProbeFailing`, `ConsoleReplicasUnavailable`, `ConsoleAssetsMissing`, `ConsoleServerErrors`; `OPERATIONS_RUNBOOK.md` §11.68; `ConsoleAlertRulesTest` 12 of 12 (unit, 2026-11-19). **CSP reports are not collected, a decision**: `-044`'s design, confirmed by the owner on 2026-11-20 under the standing ruling "if you rise questions, my answer is your recommended options", and held by `ConsoleAlertRulesTest` (no report endpoint named). The cluster half (the probe and alerts raised and cleared on `kind`, the capture of the real series) is owed under PI-025's `P17-TSK-044` entry. The promtool cases did not run (no Prometheus image) |
| `-105`'s registry repository and delivery, designed-only | **MET WITH DEBT** | `-105` gated 2026-11-18: the registry key, the fifth Application, promotion by digest (`DeliveryManifestsTest`, `PromotionTest#productionNeedsTheConsoleInStagingFirst`). Designed-only by nature (no cloud account). `terraform test` is owed; the console's signing identity in a real environment is open for the owner |
| Every clause owed or designed-only listed by name in `P17-DOC-002` | **MET** | *Every owed or designed-only clause, by name*, below |
| No document claiming production readiness, compliance, certification or attestation | **MET** | The diff `2a011059b..31eb3ddeb` (854 files; `P17-INIT` to this tip) adds no line matching production-ready, compliant, compliance with, certified, certification, SOC 2, PCI DSS or ISO 27001. finapp-admin-panel's `docs/` and `README.md` match none. Supply-chain "attestation" (signed SBOM and provenance) is not a claim |

---

## Universal exit criteria (§3)

| # | Criterion | Ruling | Evidence | What remains |
|---|---|---|---|---|
| 1 | Required functionality exists, end to end | **NOT MET** | 44 of 48 items gated | `-040` **⟦PENDING⟧**, `-123`, `P17-TST-001` (stopped), B6.6 and B6.8 (finding 1) |
| 2 | Architectural boundaries respected | **NOT MET** (owed) | The architecture tier was green at the gates: `-044` 1,285 in 119 (2026-11-19); `-121` 1,293 in 119 (2026-11-20); `-031` the eleven registers, 306 (2026-11-20). ADR-C132 decision 1: no cross-schema read, each module's own query port (`SchemaReadsArePinnedTest`, `OwnershipIsScopedTest`) | **Not run fresh on the merged tree**: the owner said "we dont need architecture tests" (2026-11-20). Owed. It becomes MET WITH DEBT only on the owner's explicit acceptance |
| 3 | Required invariants tested | **MET** | No new invariant (the console moves no money). Every Phase 17 task recorded its §2 row in `MUTATION_TESTING.md` (`-101` … `-116`); the `DOD-FIN` doors' probes performed (`-028`, `-031`, `-116`); the client's money-lint row added here | The database halves of those rows ran at their gates, not since |
| 4 | Failure cases handled | **NOT MET** | The console plan's §13 scenarios are the phase's failure cases. Each has a hermetic or door-level form: the factor refusal (`-102`), the `409` of a decided proposal (`-031`, `-116`, `-028`), the lost response under the same key (`PaymentRefundEndpointDatabaseTest`), stale records refused at the doors' conditional transitions, the console's "platform is not answering" banner (its M11) | The seven scenarios end to end are criterion 2's run. The owner's |
| 5 | Security requirements implemented | **MET WITH DEBT** | Authn and authz negatives per route (criterion 1's evidence; `EveryOperatorActRequiresAFactorTest`); `-016`'s segregation of duties at grant; `-103`'s per-source bounds; `-101`'s `no-store`; no secret in source (`-044`'s probe holds no credential, `TelemetryPipelineManifestsTest#theConsoleProbeHoldsNoCredentialAndReachesTheEdgeAlone`); every new read classified in `DATA_CLASSIFICATION.md` and recorded in `AUDITABLE_ACTIONS.md` per task | `ColumnClassificationTest` is database tier, owed with PI-023's pass. T53's residual is owner item 73 (a) |
| 6 | Observability exists | **MET WITH DEBT** | `-044`'s four alerts and runbook §11.68; the operator reads audited per page; correlation unchanged; every report states the database's as-of (`-039`, `EveryOperatorReportStatesItsAsOfTest`) | `-044`'s cluster half under PI-025 |
| 7 | Integration tests pass (full suite against real infrastructure) | **NOT MET** | Each task's own database classes ran at its gate, on the owner's allowance of 2026-11-17 | **The whole `database`, own-container and `kafka` tiers were not run.** The owner: "we dont need database tests" (2026-11-20). At least twelve database cases are known red and owned by "Phase 17" (finding 2). It becomes MET WITH DEBT only on the owner's explicit acceptance, with PI-023 named as the owner of the run |
| 8 | Documentation reflects reality | **MET WITH DEBT** | This review's edits (`THREAT_MODEL.md` re-read, `MUTATION_TESTING.md` row) | The ADR corrections of finding 4 and finding 5, made at acceptance; PI-020's status (finding 6); the console's stale status table |
| 9 | `CURRENT_STATE.md` updated | **NOT MET** | §Current Task carries this draft's line | The Phase 17 status line reads `READY` (finding 3) |
| 10 | Relevant ADRs exist and are `Accepted` | **NOT MET** | ADR-0153 and ADR-0154 `Accepted` at their gates | ADR-C130…C136 `Proposed` (proposals below); **ADR-0152 `Proposed`** (`-120`, the books' first month) — the owner's |
| 11 | No unresolved critical issues | **MET WITH DEBT** | No defect labelled critical or high is open in §Blockers ("None") | Owner item 73 (a) is the residual of a CRITICAL threat (T53). PI-016…PI-022 stand "before production use", as at Phase 16 |
| 12 | Formal phase review conducted | **MET** (as a draft) | This record | It becomes final once `-040`'s placeholders are filled and the owner has ruled |

### The financial-phase supplement, where it applies

Phase 17 is not a financial phase. The gate applies the supplement to each task carrying `DOD-FIN`: `-031`, `-116`, and `-028`
(its restriction meets a balance).

| # | Ruling | Proof, and what is owed |
|---|---|---|
| F1, F2 | Unchanged | No posting rule changed. An approval posts the same journal entries by the same code, one step later (ADR-0154 decision 4; ADR-C135 decision 4). A rejected or expired proposal posts nothing |
| F3 | Held at the gates | `PaymentRefundEndpointDatabaseTest` (scenario 4: lost response, same key, one refund); every proposal and approval keyed per principal (`IdempotencyScopesNameTheirPrincipalTest`) |
| F4 | Held | Proposals are new states before the existing effect; nothing edits a posting. A restriction refuses new outflows only (ADR-0153) |
| F5 | Unchanged | No new external event path |
| F6 | Held at the gates; the ten-racer class owed | `TransferReversalDatabaseTest`, `MerchantPayoutEndpointDatabaseTest`, `BnplRefundDatabaseTest#theFourEyesRacesHaveOneWinner`, `AccountRestrictionApiDatabaseTest` (a racing transfer wholly before or refused after). The shed ten-racer cases elsewhere are finding 2 |
| F7 | Held in Java at the gates; in the client with finding 5 | `NoFloatingPointMoneyRulesTest` (architecture tier, not fresh on this tree); the console's lint |
| F8 | Unchanged | No reconciliation rule changed |

---

## The ten-instance answer

**`PASS` by construction, task by task. `UNKNOWN` counted. No `FAIL`.** The owner's acceptance of the count as owed is needed.

- **By construction**: each complete backend task answers `PASS` at its gate except `-104`. Its arbitration is in
  PostgreSQL: a conditional transition from `PROPOSED` under `FOR UPDATE`, a restriction's machine under the row's lock, a
  conflicting grant under the identity's lock, and keyed commands converging on the idempotency record. Reads are each on
  one snapshot through the owning module's port.
- **Counted**: nothing of Phase 17 ran at three or ten replicas. `-104`'s answer is "the owner's postponement, not `PASS`";
  `P17-TST-001` was to count it at three and is stopped; `kind` is not installed here.
- **Open**: `-040` **⟦PENDING⟧** (expected "`PASS` by construction": reads, and a decided proposal answering `409` at its
  door) and `-123`. The ten-racer database cases shed `503 api.Overloaded` since `P16-TSK-016` are classified as test
  defects (PI-023's class (a)) but are red, so they prove nothing while red.

---

## ADR-C130 … ADR-C136: proposed for acceptance

**Proposals only.** Nothing here marks an ADR `Accepted`. They are finapp-admin-panel's register; this repository records the
proposal, and the acceptance is the owner's, written there. Each decision was read against this tree and the console's
records.

| ADR | Decision by decision | Proposal |
|---|---|---|
| **C130** The console, same-origin, no BFF | 1: separate repository, ✅. 2: `finapp-console`, its own workload; `deploy/console/base/networkpolicy.yaml` gives egress no rule; signed and admitted (`ImageAdmissionTest#theConsolesImageIsHeldToBothPlatformPolicies`) ✅. 3: `PathPrefix(/console)` on the operator edge only (`ConsoleHostingManifestsTest`) ✅. 4: no BFF; each gap built in its owning module ✅. 5: the console reaches no infrastructure ✅. 6: the contract pinned by commit (the console at `28460c49b`, a re-pin owed after this merge) ✅ | **Accept as written** |
| **C131** Session handling and hardening | 1, 2: the console's (memory-only holder, step-up replay), ✅ there. **3: built for acts only.** `-102` excludes safe methods; the reads that need a factor declare it per route (the staff directory, the audit trail, the customer reads). The decision's "reads included" is not what holds. 4: the headers, `ConsoleHostingManifestsTest` ✅ (HSTS, `nosniff` and `trusted-types` added at the console's M11). 5: `no-store` set **by the application**, not the edge (`-101`), which the decision allows ✅. 6: `-103`'s per-source bounds ✅. 7: reconsidered after M3; the console recommends keeping (a) (D-4) | **Accept after correcting decision 3** to "every act; reads per route, each declared". The owner confirms D-4's outcome for decision 7 |
| **C132** The operator read model | 1: each module's own reads ✅. **2: holders changed.** Roles are a partition (`RoleNameTest#theGrantsArePairwiseDisjoint`), so a reviewer who needs the reads holds `CUSTOMER_SUPPORT_AGENT` too (`-025`'s two deviations, "for the owner"). `AUDITOR` holds no `IDENTITY_READ` (open since `-017`). `PLATFORM_OVERVIEW_READ` created at `-025`. 3: identifier-first ✅; the per-field PII decisions taken at D-25 (B6.6) are unbuilt. 4: exact-identifier lookup, values in bodies ✅ (`-026`). 5: sensitive reads recorded (`RecordedReadsTest`) ✅. 6: ✅ | **Accept after correcting decision 2** to the partition as built. **The owner's first**: `-025`'s two deviations, and whether `AUDITOR` reads the staff directory (a permission of its own) |
| **C133** Collections and pending-decision queues | 1: §7 for every new list ✅. 2: the retrofit (`-042`, `-122`) ✅. 3: one shape (`-040`, `PendingDecisions`; a seventh field, `relatedId`, added for the repayment reversal) ✅, its guard owed. 4: no approval service; the inbox is composed client-side ✅. 5: every report states `asOf` (`-039`, `EveryOperatorReportStatesItsAsOfTest`) ✅ | **Accept**, decision 3 amended by `relatedId`; the guard owed (debt) |
| **C134** The audit trail read path | 1, 3, 4, 5, 7 ✅ (`-017`). 2: `ADMINISTRATOR` refused, by the owner on 2026-11-18 ✅. **6: export not built.** No `AUDIT_EXPORT` permission and no `audit.RecordsExported`; `-017` excluded it ("deferred to M10 by D-9 and not in this phase's backlog"), and M10's tasks (`-039`, `-040`, `-042`, `-122`) did not take it | **Accept after correcting decision 6** to "deferred, owned by …". **The owner's**: a backlog task, a postponed issue, or the decision struck |
| **C135** Single-actor money controls | 1: ADR-C131 decision 3's `-102` ✅. 2: keys on transfer reversal and void (`-031`), merchant suspension, reinstatement and closure (`-031`), the KYC decision (`-029`), rail availability (`-032`), BNPL fee assignment (`-116`) ✅; **fee-schedule creation and versioning: `P17-TSK-123`**. 3: adopted here by **ADR-0154 (`Accepted`)** and its amendment; **fee-schedule versions and assignment: `P17-TSK-123`**; the KYC decision single-reviewer by the owner's "Single reviewer" ✅. 4: new states before the effect ✅. 5: scheduled by the owner ✅ | **Accept**, recording decision 3's fee-schedule door as `P17-TSK-123`'s, or accept when `-123` is gated |
| **C136** The frontend stack and supply chain | 1-3, 5, 6: the console's CI (`npm run verify`, 524 tests, the licence policy, the bundle check; the npm tarball pinned by sha512 on 2026-11-20); this repository's half is `-104`'s SBOM and provenance at admission ✅. **4: finding 5**: one live `Number(amountMinor.trim())`, safe-integer checked, the contract's `int64` requiring a JSON number; the lint misses wrapped conversions | **Accept after the console fixes the lint, or after correcting decision 4** to admit a checked minor-unit integer where the contract types it so |

**Also in this register**: **ADR-0152** (the books' first month, `-120`, 2026-11-19) is still `Proposed`. Universal criterion
10 asks it to be `Accepted`. **The owner's**, beside the seven above. ADR-0153 and ADR-0154 were accepted at their gates. The
next free number is ADR-0155.

---

## Threats (`THREAT_MODEL.md`), re-read

Re-read on 2026-11-20 against §1.3's words and what Phase 17 built. The edits in place are a dated note under §3's summary
and one under §5's T48-T54 residual. **No likelihood, impact or rating changes**: ratings are inherent (§1.3), so they do not
fall as controls land.

| Threat | Rating | Re-read | What was built; what stays open |
|---|---|---|---|
| T48 token exfiltrated by script | CRITICAL (M × SEVERE) | Unchanged | The console's CSP and Trusted Types `EXISTS`. The end-to-end row is `PLANNED` on `P17-TST-001` (stopped) |
| T49 an approval clickjacked | HIGH (M × HIGH) | Unchanged, read twice. A framed approval completes a money act only with an insider's proposal pending, so its SEVERE reading is two footholds (LOW × SEVERE = HIGH); either reading is HIGH | `frameDeny` and `frame-ancestors 'none'` `EXISTS` |
| T50 a stolen session used for a money act | CRITICAL | Unchanged | Four eyes on the money doors (`-031`, `-116`), the factor on every act (`-102`), session bounds: `EXISTS`. The fee-schedule doors are `PLANNED` on `P17-TSK-123` |
| T51 an upload rendered as active content | CRITICAL (H × SEVERE) | Unchanged | `default-src 'none'` on every API answer `EXISTS`; the console renders no upload beyond type and size (its M11) |
| T52 a request storm from a faulty console | MEDIUM | Unchanged | `-103`'s per-source bounds `EXISTS`; no global limit claimed |
| T53 a compromised workstation | CRITICAL | Unchanged | Bounded by four eyes, the session bounds and the factor: `EXISTS`. **The residual is a `GAP` on owner item 73 (a), awaiting the owner.** Item 73 asked this review whether operator sessions are longer than the console's work needs. No measurement of operator use exists (no cluster run, no real operators), so the review cannot find that they are. Recommendation (i) stands; the decision is the owner's |
| T54 a hostile console dependency | CRITICAL | Unchanged | Admission signed and attested (`-104`) `EXISTS`. The cluster row is `PLANNED` on `P17-TST-001` (stopped) |
| T55 operator data kept by the browser | HIGH | Unchanged | `no-store` on every operator answer (`-101`) `EXISTS` |
| T56 the test seed run against a deployed database | HIGH (M × HIGH) | Unchanged, read twice. Every plant is made under the seed's own named identities and its doors' records, so nothing is "altered without detection". No posting is planted (`-121`: "no balance or posting written") | The seed refuses any stack but a test one, and ships nowhere: `EXISTS` |

**Due at the flip** (the guard `ThreatModelIsCompleteTest#noPromiseOutlivesItsTask` refuses a `PLANNED` row whose task is no
longer live): T48's and T54's `P17-TST-001` rows must become `EXISTS` from its run, or a `GAP` row naming the postponed issue
the owner chooses. T50's `P17-TSK-123` row must become `EXISTS` at that task's gate.

---

## Every owed or designed-only clause, by name

Each is built and held by its contract tests where it says so. **None is claimed as run.**

1. **The seven scenarios on `kind` at three replicas** (criterion 2): `P17-TST-001`, stopped. Its hermetic half is ungated
   on branch `P17-TST-001`.
2. **`/console` `404` on the public host, the policy header once, zero CSP violations across the battery** (criterion 3's
   `kind` half).
3. **No token at rest across step-up and logout** (criterion 4).
4. **The console's step-up replaying once under the same key, on `kind`** (criterion 5's `kind` clause).
5. **The multi-instance count at three replicas** (criterion 10); `-104`'s answer.
6. **`-044`'s cluster half**: the probe, alerts raised and cleared, the series captured into
   `infra/prometheus/console-series.txt`, and the checks `alert-rules-check.sh`, `telemetry-check.sh` and `edge-check.sh`
   (PI-025's `P17-TSK-044` entry).
7. **`-104`'s cluster half**: the admission check and the rehearsal with a console image named.
8. **`edge-check.sh` over regenerated routes** (`-016`, `-032`, `-033`, `-037`, `-039`, `-103` and others) and
   **`terraform test`** (`-105`).
9. **`-105`'s registry repository and delivery**: designed-only, no cloud account. The console's signing identity in a
   real environment is open for the owner.
10. **The whole `database`, own-container and `kafka` tiers on the merged tree** (PI-023's class), with the twelve known-red
    cases (finding 2) and `ColumnClassificationTest` over Phase 17's migrations.
11. **The `architecture` tier fresh on the merged tree** (the owner's waiver, 2026-11-20).
12. **The manual screen-reader pass** (criterion 6), a person's.
13. **The promtool cases** in `infra/prometheus/tests/console.test.yml` (no Prometheus image).

---

## Mutation register

- **§2: the console's money-lint row, added here.** It was not recorded at `P17-INIT` (finapp-admin-panel B11.3: "🟡 Partly
  — the console's money-lint row"). It cites the console's M11 commit `037612f` and its in-suite test. It starts with
  neither `INV-` nor a test of this repository, so `MutationDemonstrationTest` neither reads nor needs it.
- **§4: `P17-TST-001` needs a row at the flip.** `MutationDemonstrationTest#everyTestItemHasADemonstration` demands one for
  every `P{n}-TST-*` item of a `COMPLETE` phase. Its text depends on the owner's ruling on the battery: a deferral row in
  `P15-TST-001`'s convention if it is owed, or the run's probes if it runs. Not written here.

---

## Owner items

Nothing below is decided by this review. Each needs the owner's explicit word before the phase can exit. The standing
ruling ("if you rise questions, my answer is your recommended options") is noted where it would apply. Accepting a risk or a
deviation, and the flip, stay the owner's explicit acts.

| # | Item | Recommendation |
|---|---|---|
| O1 | **`P17-TSK-040`**: built by the coordinator session; this review's **⟦PENDING⟧** places filled from its gate | Wait for its gate |
| O2 | **`P17-TSK-123`**, the fee-schedule doors (ADR-C135 decision 3's remainder; T50's `PLANNED` row) | Build it in Phase 17; it is a money-control door |
| O3 | **`P17-TST-001`**: resume, or accept the `kind` battery as owed. This covers criteria 2, 3, 4, 5 and 10's `kind` halves, T48's and T54's rows, and `-104`'s count | Accept as owed under PI-025, as Phase 16's acceptance A did; criteria 2 and 4 then read `PARTIAL` by the owner's choice |
| O4 | **`-044`'s cluster half** under PI-025 | Accept (already listed there) |
| O5 | **The database and architecture tiers waived on 2026-11-20**: accept as Phase 17's recorded deviation (universal criteria 2 and 7) | Accept, naming PI-023 as the owner of the run |
| O6 | **The six §Known Architectural Debt rows owned by Phase 17** (finding 2) | Re-own them under PI-023 |
| O7 | **Owner item 73 (a)**: T53's compromised-workstation residual | (i), accept until C13. **The owner's acceptance** |
| O8 | **ADR-C130…C136**: acceptance, with the corrections above. Also **ADR-0152** | Accept C130 and C135 now; C131, C132, C134 and C136 after their corrections; C133 after `-040` |
| O9 | **B6.6** (D-25's three items) and **B6.8** (2), (3): place in Phase 17, or rule out of the phase (finding 1) | The owner's D-25 says request-info is "in Phase 17": place B6.6 as tasks, or re-decide D-25 |
| O10 | **ADR-C134 decision 6, the audit export**: a task, a postponed issue, or struck | A postponed issue, unless an auditor needs it before production |
| O11 | **`-025`'s two deviations** from ADR-C132 decision 2, and `AUDITOR`'s staff-directory read | Keep the partition; a read permission of its own if the auditor needs the directory |
| O12 | **PI-020**: `FIXED` by `-016` (finding 6) | `FIXED`, if the seven pairs cover ADR-0071 §10's rule as the owner reads it |
| O13 | **Phase 17's status line**: `IN_PROGRESS` now, `IN_REVIEW` when this review is re-run final (finding 3) | Correct it now |
| O14 | **The console's own**: the manual screen-reader pass; the re-pin and `npm run test:catalogue` on this tree; the settlement upload kept out; D-4's carrier kept (a); finding 5's lint | The console's recommendations |
| O15 | **The flip**, when O1-O14 are settled: the rehearsal of the guards that arm at it (`ThreatModelIsCompleteTest`, `MutationDemonstrationTest`, `ProductionReadinessIsEvidencedTest`, `PlannedMetersExistTest`), and the §4 row for `P17-TST-001` | The owner's act |

---

## Debt register (Phase 17's, as this draft finds it)

| Debt | Held by | Owner |
|---|---|---|
| The `kind` battery and every Phase 17 cluster run (items 1-7 of the owed list) | `P17-TST-001` (stopped); PI-025 once the owner accepts (O3) | The owner |
| `-044`'s cluster half | [PI-025](../POSTPONED_ISSUES.md#pi-025--phase-16s-cluster-runs-not-run-kind-experiments-load-profiles-and-drills), `P17-TSK-044` entry | The owner, after the phases |
| The database, own-container and Kafka tiers on the merged tree; the twelve known-red database cases; `ColumnClassificationTest` over Phase 17's migrations | [PI-023](../POSTPONED_ISSUES.md#pi-023--the-phase-15-database-pass-p15-tst-002-is-run-after-the-projects-phases) (its class (a) already lists the ten-racer cases); the six §Known Architectural Debt rows still name Phase 17 (O6) | The owner |
| The architecture tier fresh on the merged tree | Not yet held: the owner's waiver of 2026-11-20 (O5) | The owner |
| `edge-check.sh`, `terraform test`, the promtool cases | PI-025's class, per task | The owner, after the phases |
| The fee-schedule doors | `P17-TSK-123` | Phase 17 |
| The pending-decision lists | `P17-TSK-040` **⟦PENDING⟧** | Phase 17 (the coordinator session) |
| B6.6, B6.8 (2), (3): backend gaps with no task | None yet (O9) | The owner |
| The audit export (ADR-C134 decision 6) | None yet (O10) | The owner |
| T53's residual | [`THREAT_MODEL.md`](../../architecture/THREAT_MODEL.md) owner item 73 (a) | The owner |
| `MerchantPayoutDatabaseTest`'s quiet re-run (`-031`) | `-031`'s record | The next database run |
| The console's lint gap and its one numeric amount (finding 5) | finapp-admin-panel | The console |
| The manual screen-reader pass | finapp-admin-panel | A person |
| Before production use, unchanged from Phase 16 | [PI-016…PI-022](../POSTPONED_ISSUES.md) (PI-020 possibly `FIXED`, O12), PI-026, PI-027 | The owner |
| The storms of Phases 14 and 15; the platform rehearsals | [PI-004](../POSTPONED_ISSUES.md), [PI-024](../POSTPONED_ISSUES.md#pi-024--the-platform-rehearsals-not-run-whole-on-kind-p15-tsk-032) | The owner, after the phases |

---

## What happens next

1. ~~`P17-TSK-040` is gated, and its places filled~~ - done 2026-11-21: criteria 1, 8 and 10 and ADR-C133 decision 3 above. New
   debt from its record: the queue register guard, the keyset entries, a database test per new store read, the pending-status
   indexes, the `-022` and `-107` near-shape queues' retirement, and whether risk's self-dealing withholding applies to the queues.
   Also recorded: `P17-TSK-044`'s cluster half is owed by the owner's choice ("Keep it owed", PI-025), and the database and
   architecture tiers are waived for the phase on the owner's word (2026-11-20).
2. The owner rules on O2-O14.
3. This review is re-run final on the merged tree: the coordinator's fresh unit and slice run is recorded, and the flip's
   guards are rehearsed (O15).
4. **The flip is the owner's** (`PHASE_GATES.md` §1). This review marks no task `READY` and changes no status.
