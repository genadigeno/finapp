package com.finapp.crossborder;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The closed vocabularies of a cross-border beneficiary (`P9-TSK-017`, ADR-0080 sections 3 and 5a), each
 * with the SQL list {@code crossborder V003}'s {@code CHECK}s are generated from.
 */
public final class BeneficiaryVocabulary {

    private BeneficiaryVocabulary() {}

    /** The provider's payee check: a close match is not a match. */
    public enum PayeeCheck {
        MATCH,
        NO_MATCH,
        UNAVAILABLE
    }

    /** What the provider attests the beneficiary is. */
    public enum EntityType {
        INDIVIDUAL,
        BUSINESS
    }

    /** How a candidate rail was judged at selection, in the order the judgement runs. */
    public enum SelectionOutcome {
        /** The build declares no corridor rail by this name. */
        UNDECLARED_BY_BUILD,
        /** The rail delivers no currency the beneficiary is paid in. */
        CURRENCY_UNSUPPORTED,
        /** The rail delivers the currency, but not in the beneficiary's country. */
        NO_COVERAGE,
        /** Declared and covering, but not operable in this deployment - nothing to exchange through. */
        UNAVAILABLE,
        /** The first eligible candidate: the beneficiary's issuing rail. */
        CHOSEN
    }

    /** What a counterparty screening decided, in crossborder's words. */
    public enum ScreeningOutcome {
        /** An automatic {@code CLEAR} or a person's {@code RELEASE}. */
        CLEARED,
        /** A hit, an indeterminate answer or an unverified payee: a person decides. */
        IN_REVIEW,
        /** A person's {@code BLOCK}. */
        BLOCKED,
        /** The provider could not be asked - nothing is decided. */
        UNAVAILABLE
    }

    /** Why a beneficiary moved. */
    public enum StatusCause {
        REGISTERED,
        SCREENING,
        CUSTOMER
    }

    /** {@code values} as a SQL literal list. */
    public static String sqlValueList(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
