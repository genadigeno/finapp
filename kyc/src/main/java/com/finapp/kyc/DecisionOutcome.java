package com.finapp.kyc;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a KYC/KYB decision concluded (`PHASE_2_PLAN.md` §4, {@code INV-KYC-02}).
 *
 * <p>Deliberately not {@link KycCaseStatus}: a decision's outcome is two values and a case's
 * status is seven, and reusing the case enum here would let a decision "conclude"
 * {@code IN_REVIEW}. The {@link #caseStatus()} mapping is the one place the two vocabularies
 * meet, so the terminal a decision drives its case to cannot drift from the outcome recorded on
 * the decision itself.
 */
public enum DecisionOutcome {

    /** The platform decided the party may be onboarded. */
    APPROVED,

    /** The platform decided the party may not be onboarded. */
    REJECTED;

    /** The terminal case status this outcome drives the case to. */
    public KycCaseStatus caseStatus() {
        return switch (this) {
            case APPROVED -> KycCaseStatus.APPROVED;
            case REJECTED -> KycCaseStatus.REJECTED;
        };
    }

    /** The outcomes as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(outcome -> "'" + outcome.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
