package com.finapp.credit;

import java.time.Instant;
import java.util.Objects;

/**
 * A frozen decision input (`P10-TSK-008`; ADR-0087 section 2, {@code INV-CRD-07}, {@code INV-CRD-06}): born once per
 * {@code (decision request, sequence)} and never changed - its canonical form, the SHA-256 of those bytes, the format
 * that wrote them, and the content they say.
 *
 * <p>{@code toString} renders identifiers only: the canonical form is {@code RESTRICTED-FINANCIAL}.
 */
public record DecisionSnapshot(
        DecisionSnapshotId id,
        int sequence,
        int format,
        String canonical,
        byte[] sha256,
        Instant frozenAt,
        SnapshotContent content) {

    public DecisionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(canonical, "canonical");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(frozenAt, "frozenAt");
        Objects.requireNonNull(content, "content");
        if (sequence < 1 || format < 1) {
            throw new IllegalArgumentException("a sequence and a format count from 1");
        }
        sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    /** Whether the stored bytes still hash to the stored hash - a snapshot is re-verifiable (INV-CRD-07). */
    public boolean verifies() {
        return java.util.Arrays.equals(CanonicalSnapshot.sha256(canonical), sha256);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DecisionSnapshot that && id.equals(that.id) && canonical.equals(that.canonical);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, canonical);
    }

    @Override
    public String toString() {
        return "DecisionSnapshot[" + id.value() + ", sequence=" + sequence + ", format=" + format + "]";
    }
}
