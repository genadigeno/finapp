package com.finapp.settlement.format;

import com.finapp.settlement.LineDirection;
import com.finapp.settlement.LineReferenceKind;
import com.finapp.settlement.SettlementLineType;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One canonical settlement line, as the parse produced it (`P8-TSK-008`, ADR-0065) — typed,
 * positive, dated, and carrying its typed references. The provider's words are already gone:
 * only the adapter that built this ever saw them ({@code INV-PAY-03}).
 *
 * <p><strong>Two digests, two questions.</strong> {@link #rawRecordSha256()} answers "which
 * delivered record produced this line" (a split fee line shares its transaction's record);
 * {@link #canonicalFingerprint()} answers "is this the same economic statement" — SHA-256
 * over type, direction, amount, currency, dates and the references sorted by kind, so it is
 * deterministic across re-parses and DELIBERATELY NOT UNIQUE in the store: a repeated line
 * must survive parsing to become {@code DUPLICATE_EXTERNAL} at matching, never vanish here.
 */
public record ParsedLine(
        int lineNo,
        SettlementLineType type,
        LineDirection direction,
        Money amount,
        LocalDate businessDate,
        Optional<LocalDate> settlementDate,
        Optional<LocalDate> valueDate,
        SortedMap<LineReferenceKind, String> references,
        byte[] rawRecordSha256) {

    public ParsedLine {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(settlementDate, "settlementDate must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(references, "references must not be null");
        Objects.requireNonNull(rawRecordSha256, "rawRecordSha256 must not be null");
        if (lineNo < 1) {
            throw new IllegalArgumentException("a line number is 1-based");
        }
        if (amount.minorUnits() <= 0) {
            // The ADR-0003 triple: the sign lives in the direction, never in the amount.
            throw new IllegalArgumentException("a settlement line's amount is positive");
        }
        if (rawRecordSha256.length != 32) {
            throw new IllegalArgumentException("rawRecordSha256 is a SHA-256");
        }
        references.forEach(
                (kind, value) -> {
                    Objects.requireNonNull(value, "a reference value must not be null");
                    if (value.isBlank() || value.length() > 100) {
                        throw new IllegalArgumentException(
                                "a " + kind + " reference is 1..100 characters");
                    }
                });
        references = new TreeMap<>(references);
        rawRecordSha256 = rawRecordSha256.clone();
    }

    @Override
    public byte[] rawRecordSha256() {
        return rawRecordSha256.clone();
    }

    /**
     * The canonical identity digest — one algorithm for every format, so two formats cannot
     * fingerprint one statement two ways.
     */
    public byte[] canonicalFingerprint() {
        StringBuilder canonical =
                new StringBuilder()
                        .append(type.name())
                        .append('|')
                        .append(direction.name())
                        .append('|')
                        .append(amount.minorUnits())
                        .append('|')
                        .append(amount.scale())
                        .append('|')
                        .append(amount.currency().code())
                        .append('|')
                        .append(businessDate)
                        .append('|')
                        .append(settlementDate.map(LocalDate::toString).orElse(""))
                        .append('|')
                        .append(valueDate.map(LocalDate::toString).orElse(""));
        for (Map.Entry<LineReferenceKind, String> reference : references.entrySet()) {
            canonical.append('|')
                    .append(reference.getKey().name())
                    .append('=')
                    .append(reference.getValue());
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The one digest the formats and the canonicaliser share. */
    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", impossible);
        }
    }

    /** Identifiers only — an amount in a log line is `INV-AUD-02`'s to refuse. */
    @Override
    public String toString() {
        return "ParsedLine[" + lineNo + ", " + type + ", " + direction + "]";
    }
}
