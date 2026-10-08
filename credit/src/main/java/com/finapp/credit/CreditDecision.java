package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A decision request's decision, as recorded (`P10-TSK-016`, {@code credit V011}; {@code INV-CRD-02}, {@code INV-CRD-06},
 * {@code INV-CRD-09}, {@code INV-HIST-04}): born once, never changed. An approval approves an amount (and an instalment
 * product's term) and reserves exposure until {@code validUntil}; a decline approves nothing. A decline, or an approval
 * below its request, carries its reasons - refused otherwise, here and by the table's deferred trigger at commit.
 *
 * @param snapshot the snapshot the decision was made from - the latest, a successor when the reservation moved
 * @param decidedBy the person's id, or the platform's
 * @param decidedAt the database's statement time at the insert
 * @param validUntil {@code decidedAt +} the product's decision validity, on the database's clock
 */
public record CreditDecision(
        CreditDecisionId id,
        UUID decisionRequest,
        UUID party,
        CreditProfileId profile,
        CreditProduct product,
        DecisionSnapshotId snapshot,
        byte[] snapshotSha256,
        DecisionOutcome outcome,
        Money requested,
        Optional<Money> approved,
        Optional<Integer> termMonths,
        List<ReasonCode> reasons,
        PinnedVersions versions,
        String decidedBy,
        String decidedByType,
        Instant decidedAt,
        Instant validUntil) {

    public CreditDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(decisionRequest, "decisionRequest");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(snapshotSha256, "snapshotSha256");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(approved, "approved");
        Objects.requireNonNull(termMonths, "termMonths");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedByType, "decidedByType");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(validUntil, "validUntil");
        snapshotSha256 = snapshotSha256.clone();
        reasons = List.copyOf(reasons);
        if ((outcome == DecisionOutcome.APPROVED) != approved.isPresent()) {
            throw new IllegalArgumentException("an approval, and only an approval, approves an amount");
        }
        if (approved.isPresent() && (!approved.get().isPositive() || approved.get().compareTo(requested) > 0)) {
            throw new IllegalArgumentException("an approval approves a positive amount, at most the request");
        }
        boolean explained = outcome == DecisionOutcome.DECLINED || approved.get().compareTo(requested) < 0;
        if (explained && reasons.isEmpty()) {
            throw new IllegalArgumentException("a decline, or an approval below its request, carries its reasons (INV-CRD-02)");
        }
    }

    @Override
    public byte[] snapshotSha256() {
        return snapshotSha256.clone();
    }

    /** The snapshot's hash, hexadecimal - an identifier-shaped token for the event. */
    public String snapshotSha256Hex() {
        return HexFormat.of().formatHex(snapshotSha256);
    }

    /** No amount renders. */
    @Override
    public String toString() {
        return "CreditDecision[" + id + ", " + product + ", " + outcome + "]";
    }
}
