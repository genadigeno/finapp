package com.finapp.transfers;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why a transfer committed {@code FAILED} (ADR-0044): the enumerated, recorded refusals — every
 * value here is a <em>committed domain outcome</em> with a producer in the execution command
 * ({@code P4-TSK-005}), never an exception leak and never a 4xx. The distinction is the boundary's
 * ({@code PHASE_4_PLAN.md} §14): a malformed request, an unknown account or somebody else's
 * account is the caller's 4xx with nothing written; a well-formed request the domain refuses is a
 * {@code FAILED} transfer with its reason, because a refusal without its reason is a support
 * ticket the platform caused.
 *
 * <p>Deliberately absent: any "amount not positive" reason — a non-positive amount is a boundary
 * mistake (422), refused again by {@link Transfer}'s constructor as defence in depth, and never a
 * committed outcome.
 */
public enum FailureReason {

    /** The availability derivation inside the source-account lock said no ({@code INV-BAL-04}). */
    INSUFFICIENT_FUNDS,

    /** The source ledger account refuses postings — its product closed mid-flight, typically. */
    SOURCE_NOT_POSTABLE,

    /** The destination ledger account refuses postings ({@code P3-TSK-014}'s trigger, observed). */
    DESTINATION_NOT_POSTABLE,

    /** The two legs are not the same currency; cross-currency transfers are Phase 9's. */
    CURRENCY_MISMATCH,

    /**
     * Source and destination are the same account — a self-transfer moves nothing and would mint
     * a balanced no-op entry. The one reason whose committed record legitimately stores an
     * <em>equal</em> account pair; {@link Transfer}'s constructor holds that coherence in both
     * directions.
     */
    SELF_TRANSFER;

    /** The reasons as a SQL literal list, for {@code V002}'s {@code CHECK} ({@code P4-TSK-004}). */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(reason -> "'" + reason.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
