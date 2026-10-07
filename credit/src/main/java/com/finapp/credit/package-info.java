/**
 * Credit decisioning: whether credit may be offered to a party, why, and under which versioned
 * policy - and never the credit itself.
 *
 * <p><strong>What belongs here.</strong> The Credit Profile and its collected Credit Records,
 * with their evidence encrypted and their provenance kept; the frozen, hashed Decision Snapshot;
 * the Assessment (affordability, exposure, the scorecard's score); the Scorecard and Policy as
 * versioned, four-eyes data; the Evaluation; the Credit Decision - born once, never updated or
 * deleted by any role, carrying its ordered reason codes ({@code INV-CRD-02}) - with its
 * explanation and its replay ({@code INV-CRD-01}); and the Underwriting Case (ADR-0084 to
 * ADR-0089).
 *
 * <p><strong>What never belongs here.</strong> Money. Credit moves none: nothing here is posted,
 * settled or reconciled, so there is no build edge to {@code ledger}. The Loan Application, the
 * loan and its servicing are Phase 11's ({@code lending}), and they will <em>reference</em> a
 * Credit Decision, never be one. The risk score is {@code risk}'s (Phase 13), consumed through a
 * seam; the lawful basis for bureau access is {@code consent}'s, the party's standing
 * {@code party}'s - each reached through a port this module declares and {@code app} composes.
 * The edges are exactly {@code platform} and {@code sharedkernel} ({@code CreditModuleIsolationTest}).
 *
 * <p><strong>Score is not decision</strong> ({@code INV-CRD-04}). An attribute, a score, an
 * evaluation's outcome and a decision's outcome are distinct types from the first class:
 * {@link com.finapp.credit.DecisionOutcome} is {@code APPROVED} or {@code DECLINED} and nothing
 * else - a referral is an evaluation's outcome, never a decision's.
 *
 * <p><strong>What exists so far.</strong> The boundary, the migrator-owned schema floor and the
 * closed vocabularies ({@code P10-TSK-001}): {@link com.finapp.credit.CreditProduct},
 * {@link com.finapp.credit.CreditAttributeCode}, {@link com.finapp.credit.ReasonCode} with its
 * immutable, migration-seeded {@code reason_code} catalogue, and
 * {@link com.finapp.credit.DecisionOutcome}. No behaviour, no endpoint; the consent purposes are
 * {@code P10-TSK-002}'s, the profile {@code P10-TSK-004}'s.
 */
package com.finapp.credit;
