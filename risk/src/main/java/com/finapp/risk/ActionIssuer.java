package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;

/**
 * How an approved override issues its action into the action machine (`P13-TSK-017`; ADR-0112 decision 3: an
 * override-issued action is born {@code APPROVED} by the override's own approval) - {@link RiskActions#issued} in
 * production, inside T-ovr's transaction after the approval's edge: the {@code risk_action} row under the override's own
 * id (its key {@code risk.action:<override id>}), due at once, and {@code risk.RiskActionRequested} beside it. Nothing is
 * dispatched here.
 */
@FunctionalInterface
public interface ActionIssuer {

    /** Issues {@code approved}'s dispatchable effect on {@code unitOfWork}. */
    void issue(Connection unitOfWork, DecisionOverride approved, CorrelationId correlation);
}
