package com.finapp.settlement.format;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One whole file, canonicalised (`P8-TSK-008`, `INV-SET-07`): the counterparty's batch
 * identity, its declared control totals, and every line — or nothing at all, because a
 * partially parsed batch does not exist as a type.
 *
 * <p>The declared totals are the trailer's own words; the parse has already proven them equal
 * to the {@code Money} fold of the lines (fees subtracted, the counterparty's netting), so a
 * reader may treat them as the batch's arithmetic. {@code declaredNet} may be negative — a
 * refund-heavy day is money the platform owes.
 *
 * @param externalBatchRef the counterparty's batch identity — the live key's second member
 * @param businessDate the day the batch covers, from the header
 * @param remittanceReference the trailer's remittance reference, matching the source's
 *     declared shape — what hop 2 attributes the bank line by (ADR-0065)
 * @param declaredLineCount the trailer's record count (source records, before any fee split)
 * @param declaredNet the trailer's net — what the counterparty says it will remit
 */
public record ParsedBatch(
        String externalBatchRef,
        LocalDate businessDate,
        String remittanceReference,
        int declaredLineCount,
        Money declaredNet,
        List<ParsedLine> lines) {

    public ParsedBatch {
        Objects.requireNonNull(externalBatchRef, "externalBatchRef must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(remittanceReference, "remittanceReference must not be null");
        Objects.requireNonNull(declaredNet, "declaredNet must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        if (externalBatchRef.isBlank() || externalBatchRef.length() > 100) {
            throw new IllegalArgumentException("a batch reference is 1..100 characters");
        }
        if (remittanceReference.isBlank() || remittanceReference.length() > 100) {
            throw new IllegalArgumentException("a remittance reference is 1..100 characters");
        }
        if (declaredLineCount < 0) {
            throw new IllegalArgumentException("a declared line count is never negative");
        }
        lines = List.copyOf(lines);
        for (ParsedLine line : lines) {
            if (!line.amount().currency().equals(declaredNet.currency())) {
                throw new IllegalArgumentException(
                        "a batch is one currency (INV-SET-07): the parse rejects a mixed file"
                                + " before building this");
            }
        }
    }

    /** Identifiers only. */
    @Override
    public String toString() {
        return "ParsedBatch[" + externalBatchRef + ", " + lines.size() + " lines]";
    }
}
