package com.finapp.risk;

import java.sql.Connection;
import java.util.Objects;

/**
 * T-sig's second half (`P13-TSK-007`; {@code PHASE_13_PLAN.md} section 7.1: "record a signal born once; pull the
 * sweep's permit forward") - a signal born about a party pulls that party's profile permit to now, enrolling the party
 * if it has no profile, in the inbox handler's own transaction. {@link #NONE} lets the hint fall: the profile's own
 * cadence converges without it.
 */
@FunctionalInterface
public interface ProfileHints {

    /** Hints nothing. */
    ProfileHints NONE = (unitOfWork, signal) -> { };

    /** {@code signal} was just born on {@code unitOfWork}; pull its party's permit forward if its party is placeable. */
    void signalRecorded(Connection unitOfWork, RiskSignal signal);

    /**
     * The hint over the profile store, through the party join: the party of the signal's subject, if placeable, has its
     * head born or its permit pulled to now - one waiting upsert ({@link CustomerRiskProfileStore#pullForward}).
     */
    static ProfileHints over(RiskSubjectParties parties, CustomerRiskProfileStore store) {
        Objects.requireNonNull(parties, "parties");
        Objects.requireNonNull(store, "store");
        return (unitOfWork, signal) -> {
            Objects.requireNonNull(unitOfWork, "unitOfWork");
            Objects.requireNonNull(signal, "signal");
            parties.partyOf(unitOfWork, signal.subject()).ifPresent(party -> store.pullForward(unitOfWork, party));
        };
    }
}
