package com.finapp.kyc;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the captured paper <em>is</em> — deliberately distinct from {@link DocumentContentType},
 * which says what the bytes are. "A passport" and "a JPEG" are different questions, and a
 * verification check (`P2-TSK-009`) routes on this one.
 *
 * <p>A closed enumeration rather than a free string, for the {@code ConsentPurpose} reason
 * ({@code PHASE_2_PLAN.md} §4): a free-string type is a vocabulary nobody controls, and the
 * check that must handle "a passport" cannot be written against whatever a client sent.
 *
 * <p>The schema's {@code CHECK} constraint is generated from {@link #sqlValueList()}
 * (the {@code P0-TSK-022} pattern), so the enum and {@code V003} are one definition and
 * {@code KycDocumentMigrationTest} fails the build if they drift.
 */
public enum DocumentType {
    PASSPORT,
    ID_CARD_FRONT,
    ID_CARD_BACK,
    DRIVING_LICENCE,
    PROOF_OF_ADDRESS;

    /** The quoted, comma-separated value list the migration's {@code CHECK} constraint uses. */
    public static String sqlValueList() {
        return Stream.of(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
