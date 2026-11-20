package com.finapp.kyc;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why a reviewer asked the customer for more information (`P17-TSK-126`, ADR-0155 decision 4): the request's reason code, the
 * reason of its {@code kyc.InformationRequested} record.
 *
 * <p><strong>Internal, and named for what the customer must supply, never for why the case is in review.</strong> The code never
 * reaches the customer - neither their case view nor the notification event carries it - and the vocabulary holds no screening,
 * suspicion or investigation term, so that even a leak of the code tips nobody off. A reason the list does not name is
 * {@link #OTHER}, explained by the request's own text, which is screened ({@link CustomerRequestText}).
 *
 * <p>Persisted: kyc {@code V016}'s {@code CHECK} is generated from {@link #sqlValueList()} and reconciled by
 * {@code InformationRequestMigrationTest}.
 */
public enum InformationRequestReason {

    /** A document the customer supplied cannot be read: blurred, cropped, glared or partial. */
    DOCUMENT_UNREADABLE,

    /** A document the customer supplied had expired when it was supplied. */
    DOCUMENT_EXPIRED,

    /** A document the review needs was never supplied. */
    DOCUMENT_MISSING,

    /** The review needs proof of the customer's residential address. */
    PROOF_OF_ADDRESS,

    /** Details the customer gave differ from the document they supplied - a name, a date of birth, a number. */
    DETAILS_DIFFER,

    /** The review needs to know where the customer's funds come from. */
    SOURCE_OF_FUNDS,

    /** Anything else the review needs; the request's text says what. */
    OTHER;

    /** The codes as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(reason -> "'" + reason.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
