package com.finapp.kyc;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The small closed vocabularies of a counterparty screening (`P9-TSK-016`, ADR-0081), each with the
 * SQL list {@code kyc V009}'s {@code CHECK}s are generated from.
 */
public final class CounterpartyScreeningVocabulary {

    private CounterpartyScreeningVocabulary() {}

    /** Why a screening waits for a person - a hit, an unknown answer, or a payee not verified. */
    public enum ReviewReason {
        HIT,
        INDETERMINATE,
        /** The provider cleared the name, but the payee check was {@code NO_MATCH} or {@code UNAVAILABLE}. */
        PAYEE_UNVERIFIED
    }

    /**
     * The beneficiary's payee check as handed in by the registration (ADR-0080 point 3) - part of the
     * decision basis: an automatic {@code CLEAR} exists only with a {@code MATCH}.
     */
    public enum PayeeVerdict {
        MATCH,
        NO_MATCH,
        UNAVAILABLE
    }

    /** What the counterparty is. */
    public enum EntityType {
        INDIVIDUAL,
        BUSINESS
    }

    /** A reviewer's decision on a screening in review. */
    public enum Decision {
        RELEASE,
        BLOCK
    }

    /** The coded half of a reviewer's reason; the narrative is the other half. */
    public enum ReasonCode {
        FALSE_POSITIVE(Decision.RELEASE),
        PAYEE_CONFIRMED(Decision.RELEASE),
        TRUE_MATCH(Decision.BLOCK),
        PAYEE_NOT_CONFIRMED(Decision.BLOCK),
        INSUFFICIENT_INFORMATION(Decision.BLOCK);

        private final Decision decision;

        ReasonCode(Decision decision) {
            this.decision = decision;
        }

        /** Whether this code can justify {@code made} - {@code kyc V009}'s release and block CHECKs. */
        public boolean justifies(Decision made) {
            return decision == made;
        }
    }

    /** A provider's verdict on a counterparty, before kyc decides anything. */
    public enum Verdict {
        CLEAR,
        HIT,
        INDETERMINATE,
        /** The provider could not be asked or did not answer - nothing is known. */
        UNAVAILABLE
    }

    /** {@code values} as a SQL literal list. */
    public static String sqlValueList(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
