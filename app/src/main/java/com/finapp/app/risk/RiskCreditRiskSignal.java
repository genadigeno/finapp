package com.finapp.app.risk;

import com.finapp.credit.CreditRiskSignal;
import com.finapp.risk.CustomerRiskProfiles;
import java.sql.Connection;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Credit's risk seam, answered by {@code risk} - <strong>seam version 2</strong> (`P13-TSK-007`; {@code PHASE_13_PLAN.md}
 * sections 7.3, 12.1, 12.5; ADR-0084 point 5's follow-up; {@code INV-RSK-10}, {@code INV-CRD-01}, {@code INV-CRD-04}),
 * replacing Phase 10's {@code NotAssessedUntilPhase13} in the composition.
 *
 * <p><strong>The answer.</strong> The party's current customer risk rating - its code, {@code LOW}, {@code MEDIUM},
 * {@code HIGH} or {@code PROHIBITED}, never risk's signal score ({@code INV-CRD-04}: a rating code crosses the seam,
 * never a number summed into a credit score) - with the profile version it is, read from the head with no lock on
 * credit's own unit of work (credit takes no lock outside credit, ADR-0084 point 3). A party risk has not assessed - no
 * profile, or one enrolled and not yet recomputed - answers {@code NOT_ASSESSED} with no version: no profile is not a
 * clean bill of health, and {@code NOT_ASSESSED} is the code every credit policy already handles (`DESIGN-007` D1).
 *
 * <p><strong>Replay.</strong> Credit's freezer records the code, the seam version and the profile version in the
 * sealed snapshot; a decision replays from that snapshot and never asks this seam again, so a rating that moves after a
 * decision was sealed changes nothing about it (scenario 13), and a decision made under seam version 1 replays
 * {@code NOT_ASSESSED} as it always has.
 */
public final class RiskCreditRiskSignal implements CreditRiskSignal<Connection> {

    /** The seam's version - 2: risk's profile, with the version it was read from. */
    public static final int SEAM_VERSION = 2;

    /** The answer for a party risk has not assessed. */
    public static final String NOT_ASSESSED = "NOT_ASSESSED";

    private final CustomerRiskProfiles profiles;

    public RiskCreditRiskSignal(CustomerRiskProfiles profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public RiskSignal signal(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        return profiles.current(unitOfWork, partyId)
                .map(current -> new RiskSignal(current.rating().name(), SEAM_VERSION, OptionalLong.of(current.version())))
                .orElseGet(() -> new RiskSignal(NOT_ASSESSED, SEAM_VERSION));
    }
}
