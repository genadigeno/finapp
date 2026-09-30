package com.finapp.reconciliation;

import com.finapp.ledger.AdjustmentReasonCode;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The eight template-bound ways a break closes (`P8-TSK-012`, ADR-0071 §2) — the closed
 * vocabulary `V006` narrowed to {@code EVIDENCED} and `V007` (`P8-TSK-015`) regenerates for
 * the person kinds; {@code REPUDIATE_BATCH} and its batch subject arrive with `-023`'s `V013`.
 * Each kind carries its admitted reason codes (ADR-0071 §5's matrix, pinned per kind), whether
 * it posts through the ledger's owned adjustment, and that posting's ledger reason code.
 */
public enum ResolutionKind {

    /** Any break explained by a zero-residual allocation or offset — the platform's only. */
    EVIDENCED,

    /** An observation accepted: a timing difference, a fee as charged, a collision. No lines. */
    ACKNOWLEDGE,

    /** An INBOUND remainder or DEBIT item absorbed to RECONCILIATION_LOSSES. */
    WRITE_OFF,

    /** A CREDIT item or OUTBOUND remainder attributed to a named wallet or payable. */
    TRANSFER_TO_ACCOUNT,

    /** A CREDIT and a DEBIT item of equal amount netted, no posting. */
    OFFSET_SUSPENSE,

    /** An aged CREDIT item recognised as income, where its type admits it. */
    RECOGNISE_GAIN,

    /** A person chooses the ambiguous candidate the engine refused to guess. */
    MANUAL_MATCH,

    /** An accepted batch reversed whole (`P8-TSK-023`). */
    REPUDIATE_BATCH;

    /** The kinds `V007` admits: every kind but the batch subject's ({@code V013}, `-023`). */
    public static Set<ResolutionKind> admittedByV007() {
        return EnumSet.complementOf(EnumSet.of(REPUDIATE_BATCH));
    }

    /**
     * The reason codes this kind admits — ADR-0071 §5's matrix, pinned per kind by
     * {@code ResolutionTemplatesTest}; a code outside is {@code ReasonCodeNotAllowed} at the
     * domain and `V007`'s pairing {@code CHECK}.
     */
    public Set<ResolutionReasonCode> admittedReasonCodes() {
        return switch (this) {
            case EVIDENCED -> EnumSet.of(ResolutionReasonCode.EVIDENCE_RECEIVED);
            case ACKNOWLEDGE ->
                    EnumSet.of(
                            ResolutionReasonCode.TIMING_CONFIRMED,
                            ResolutionReasonCode.FEE_ACCEPTED_AS_CHARGED,
                            ResolutionReasonCode.FEE_RECOVERED,
                            ResolutionReasonCode.IMMATERIAL_DIFFERENCE,
                            ResolutionReasonCode.INTERNAL_PROCESSING_ERROR,
                            ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED);
            case WRITE_OFF ->
                    EnumSet.of(
                            ResolutionReasonCode.LOSS_ACCEPTED,
                            ResolutionReasonCode.IMMATERIAL_DIFFERENCE,
                            ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED,
                            ResolutionReasonCode.INTERNAL_PROCESSING_ERROR,
                            ResolutionReasonCode.UNATTRIBUTABLE_AGED);
            case TRANSFER_TO_ACCOUNT ->
                    EnumSet.of(
                            ResolutionReasonCode.FUNDS_ATTRIBUTED,
                            ResolutionReasonCode.INTERNAL_PROCESSING_ERROR,
                            ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED);
            case OFFSET_SUSPENSE ->
                    EnumSet.of(
                            ResolutionReasonCode.DUPLICATE_BY_COUNTERPARTY,
                            ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED,
                            ResolutionReasonCode.INTERNAL_PROCESSING_ERROR);
            case RECOGNISE_GAIN -> EnumSet.of(ResolutionReasonCode.UNATTRIBUTABLE_AGED);
            case MANUAL_MATCH -> EnumSet.of(ResolutionReasonCode.AMBIGUITY_RESOLVED_BY_EVIDENCE);
            case REPUDIATE_BATCH -> EnumSet.of(ResolutionReasonCode.EVIDENCE_REPUDIATED);
        };
    }

    /** Whether the kind posts through the ledger's owned adjustment (ADR-0071 §2). */
    public boolean postsAdjustment() {
        return this == WRITE_OFF || this == TRANSFER_TO_ACCOUNT || this == RECOGNISE_GAIN;
    }

    /** The ledger proposal's reason code for a posting kind (ADR-0071 §5). */
    public Optional<AdjustmentReasonCode> ledgerReasonCode() {
        return switch (this) {
            case WRITE_OFF -> Optional.of(AdjustmentReasonCode.RECONCILIATION_WRITE_OFF);
            case TRANSFER_TO_ACCOUNT -> Optional.of(AdjustmentReasonCode.RECONCILIATION_TRANSFER);
            case RECOGNISE_GAIN -> Optional.of(AdjustmentReasonCode.RECONCILIATION_GAIN);
            default -> Optional.empty();
        };
    }

    /** The value list `V006` narrowed from — every member (kept for its migration test). */
    public static String sqlValueList() {
        return sqlValueList(EnumSet.allOf(ResolutionKind.class));
    }

    /** The value list of {@code kinds}, in declaration order — `V007`'s regenerated CHECK. */
    public static String sqlValueList(Set<ResolutionKind> kinds) {
        return Arrays.stream(values())
                .filter(kinds::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** `V007`'s (kind, reason code) pairing rule over {@code kinds}, generated. */
    public static String sqlReasonPairingRule(Set<ResolutionKind> kinds) {
        return Arrays.stream(values())
                .filter(kinds::contains)
                .map(
                        kind ->
                                "(kind = '" + kind.name() + "' AND reason_code IN ("
                                        + ResolutionReasonCode.sqlValueList(
                                                kind.admittedReasonCodes())
                                        + "))")
                .collect(Collectors.joining(" OR "));
    }
}
