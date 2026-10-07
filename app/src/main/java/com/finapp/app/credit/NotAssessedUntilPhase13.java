package com.finapp.app.credit;

import com.finapp.credit.CreditRiskSignal;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 10's risk seam (`P10-TSK-008`; ADR-0084 point 5, {@code INV-CRD-04}): the risk score is {@code risk}'s, built in
 * Phase 13, so every party is {@code NOT_ASSESSED} - deterministically, with the seam's version recorded in the
 * snapshot, so a decision made now replays identically after Phase 13 answers otherwise ({@code INV-CRD-01}).
 */
public final class NotAssessedUntilPhase13 implements CreditRiskSignal<Connection> {

    /** The seam's version. */
    public static final int SEAM_VERSION = 1;

    /** The one answer. */
    public static final String NOT_ASSESSED = "NOT_ASSESSED";

    @Override
    public RiskSignal signal(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(partyId, "partyId");
        return new RiskSignal(NOT_ASSESSED, SEAM_VERSION);
    }
}
