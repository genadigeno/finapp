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
    SELF_TRANSFER,

    /**
     * The limit/velocity seam refused ({@code P4-TSK-010}): {@link TransferLimitCheck} answered
     * {@link SeamVerdict#REFUSE} under the source lock, and the execution committed the refusal
     * as this reason — the producer is the execution's own mapping arm, exercised today by a
     * refusing test decorator and owned in production by Phase 13's implementation, which
     * therefore changes no contract. Admitted at the schema by `V004`.
     */
    LIMIT_REFUSED,

    /**
     * The risk-decision seam refused ({@code P4-TSK-010}): {@link TransferRiskDecision}'s
     * {@link SeamVerdict#REFUSE}, committed by the execution's sibling arm —
     * {@link #LIMIT_REFUSED}'s reasoning verbatim, one reserved reason per seam so neither
     * implementation can commit the other's vocabulary. Admitted at the schema by `V004`.
     */
    RISK_REFUSED;

    /**
     * The reasons as a SQL literal list, for the {@code transfer_failure_reason_is_known}
     * {@code CHECK} ({@code P4-TSK-004}; replaced by `V004` when the seam reasons arrived —
     * {@code TransferMigrationTest} reconciles the <em>latest</em> definition against this).
     */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(reason -> "'" + reason.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
