package com.finapp.risk;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.Optional;

/**
 * The assurance of the acting session (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 12.2, 12.3; ADR-0108) - the port
 * T-eval reads to satisfy a {@link RiskDecision#CHALLENGE} and T-advise reads at a door to turn an unsatisfied challenge
 * into a step-up before any row exists; declared here and implemented in {@code app} over identity's session, because
 * risk cannot see {@code identity}. Read on the caller's unit of work; empty when the actor has no session (the system
 * actor, a merchant key), which a challenge treats as unsatisfied. First implemented and consumed by `-008`.
 */
@FunctionalInterface
public interface SessionAssurance {

    /** The acting session's assurance - empty when the actor acts under no session. */
    Optional<RiskAssurance> of(Connection unitOfWork, Actor actor);
}
