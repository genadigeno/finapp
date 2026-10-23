/**
 * Risk, fraud, AML and case management: the judgement, the evidence and the case - and none of the money
 * (`P13-TSK-001`; ADR-0107...ADR-0112, {@code PHASE_13_PLAN.md}).
 *
 * <p><strong>What belongs here.</strong> One module, three capabilities kept distinct by timing and obligation
 * (MODULE_ARCHITECTURE.md M3): <em>fraud decisioning</em>, synchronous, inside the caller's money transaction and
 * latency-bounded - the risk policy version with its rules as data ({@link com.finapp.risk.RuleKind}), the deterministic
 * engine ({@link com.finapp.risk.RiskEngine}), the sealed assessment with its score and its decision
 * ({@link com.finapp.risk.RiskDecision}), the override under four eyes, the durable velocity windows
 * ({@link com.finapp.risk.WindowKind}) and the customer risk profile ({@link com.finapp.risk.RiskRating});
 * <em>AML monitoring</em>, asynchronous and retrospective - the born-once signal, the monitored transaction read from the
 * ledger's journal through a port, the monitoring rules and the born-once alert ({@link com.finapp.risk.AlertKind});
 * and <em>case management</em>, which serves both and never decides money - the case ({@link com.finapp.risk.CaseKind},
 * {@link com.finapp.risk.CaseStatus}), the investigator's acts, the disposition ({@link com.finapp.risk.Disposition}),
 * the downstream action's record ({@link com.finapp.risk.ActionKind}), the screening list version and the regulatory
 * report. This module owns every one of those and nothing co-owned.
 *
 * <p><strong>What never belongs here.</strong> Money. Risk never posts, never holds, never releases a hold of its own and
 * never writes another module's row ({@code INV-RSK-02}): a decision changes a money movement only because the owning
 * domain consulted a seam in its own transaction and committed its own outcome, and an action is a keyed command
 * through the owning domain's existing door, recorded on both sides. No table here holds a balance. The Screening
 * Result is {@code kyc}'s; the transfer, withdrawal, payment, cross-border payment, BNPL application and loan keep their
 * lifecycles in their modules; the targets of actions are identity's, accounts', merchant's and crossborder's;
 * collections operations are a later context's (owner decision C3), write-off and provisioning Phase 14's. Nothing of
 * an alert, a case, a report or an investigation reaches a customer or merchant surface, a log, an event payload or a
 * metric tag ({@code INV-RSK-08}, {@code NoCustomerSurfaceReadsRiskTest}).
 *
 * <p><strong>Edges.</strong> Exactly {@code platform} and {@code sharedkernel} ({@code RiskModuleIsolationTest}) - no
 * ledger edge, no business module - and no module depends on this one. The seams the consumers declare are implemented
 * in {@code app} as adapters over this module's evaluation API; every other domain is a port this module declares and
 * {@code app} implements: {@link com.finapp.risk.RiskActingParty}, {@link com.finapp.risk.SessionAssurance},
 * {@link com.finapp.risk.MonitoredLedger}, {@link com.finapp.risk.RegulatoryFilingProvider},
 * {@link com.finapp.risk.ScreeningClearances}, {@link com.finapp.risk.PartyRescreening} and the action ports
 * ({@link com.finapp.risk.IdentityActions}, {@link com.finapp.risk.AccountActions},
 * {@link com.finapp.risk.MerchantActions}, {@link com.finapp.risk.BeneficiaryActions},
 * {@link com.finapp.risk.HeldWithdrawalActions}) - declared with the boundary, each implemented by the task that first
 * consumes it, each free to be reshaped by it while it has no implementation. No port returns a row of another module.
 *
 * <p><strong>Trust.</strong> Three disjoint desks, one live role per identity ({@code INV-AUD-03}, {@code RoleNameTest}):
 * the risk analyst investigates and approves another analyst's disposition, action or suppression; the risk officer
 * administers policy versions and overrides; the AML officer approves and files regulatory reports. Four eyes is two
 * holders of one permission, never two roles, and never one's own act ({@code risk.SelfApprovalRefused}); no holder acts
 * on a subject of their own party ({@code risk.SelfDealingRefused}, fail closed).
 *
 * <p><strong>What exists so far.</strong> The boundary, the migrator-owned schema floor ({@code V001}, no table), the
 * closed vocabularies ({@link com.finapp.risk.RiskOperation} with the decisions each admits, {@code RuleKind} with each
 * kind's operations and parameter schema, the alert, case, disposition, action, subject, window, rating and assurance
 * vocabularies, the engine identity), the aggregates' typed identifiers, the error codes and audit actions declared so
 * far, the span names and the ports above (`P13-TSK-001`); and the risk policy version as data (`P13-TSK-002`,
 * {@code V002}): {@link com.finapp.risk.RiskPolicy} - the immutable content judged whole at construction (rules of
 * closed kinds with {@link com.finapp.risk.RuleParameters} held to each kind's schema, score, rating and fail-safe
 * bands, the challenge assurance, the override threshold and the hold validity), {@link com.finapp.risk.RiskPolicyVersion}
 * with its machine ({@link com.finapp.risk.RiskPolicyVersionStatus}), {@link com.finapp.risk.RiskPolicyAdministration}
 * (propose, activate under four eyes with the predecessor retired in the same transaction, reject - advisory namespace
 * 13 first, T-pol) and {@link com.finapp.risk.RiskPolicies} (the reads and <em>the pin</em> at (R1): the ACTIVE row
 * {@code FOR SHARE}, re-read under the shared namespace when the first read is empty, so it never answers "neither"),
 * the event {@code risk.RiskPolicyActivated}, and the test-only policy v1 fixtures (C8). No evaluation, no schedule:
 * production evaluates nothing until a policy version is activated under four eyes (section 1.1), and until
 * `-008`…`-011` and `-007` land, the consumers' seams stay wired to their permit-all and not-assessed defaults.
 */
package com.finapp.risk;
