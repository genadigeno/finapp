package com.finapp.settlement.format;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One whole file, canonicalised (`P8-TSK-008`, `INV-SET-07`): the counterparty's batch
 * identity, its declared control totals, and every line — or nothing at all, because a
 * partially parsed batch does not exist as a type.
 *
 * <p>The declared totals are the file's own words; the parse has already proven them equal
 * to the {@code Money} fold of the lines (fees subtracted, the counterparty's netting), so a
 * reader may treat them as the batch's arithmetic. {@code declaredNet} may be negative — a
 * refund-heavy day is money the platform owes.
 *
 * <p><strong>Two shapes</strong> (`P8-TSK-016`): a counterparty's REPORT carries a remittance
 * reference — what it promises to pay by, what hop 2 attributes the bank line by — and no
 * statement facts; a bank STATEMENT carries its sequence and its opening and closing balances,
 * and no remittance reference of its own (its lines carry the references). Exactly one of the two
 * is present, the database's rank beside it (settlement `V005`).
 *
 * @param externalBatchRef the counterparty's batch identity — the live key's second member
 * @param businessDate the day the batch covers
 * @param remittanceReference a report's remittance reference, matching the source's declared
 *     shape; empty for a statement
 * @param declaredLineCount the file's record count (source records, before any fee split)
 * @param declaredNet what the counterparty says moves — a report's remittance, a statement's
 *     closing minus opening
 * @param statement a statement's continuity facts; empty for a report
 */
public record ParsedBatch(
        String externalBatchRef,
        LocalDate businessDate,
        Optional<String> remittanceReference,
        int declaredLineCount,
        Money declaredNet,
        List<ParsedLine> lines,
        Optional<StatementFacts> statement) {

    /**
     * A bank statement's continuity facts (`P8-TSK-016`, `INV-SET-06`): its sequence in the
     * account's chain and its opening and closing balances, SIGNED (a debit balance negative),
     * in the statement's currency. The account reference is verified by the adapter and never
     * carried further ({@code INV-RAIL-03}).
     */
    public record StatementFacts(long sequence, Money opening, Money closing) {

        public StatementFacts {
            Objects.requireNonNull(opening, "opening must not be null");
            Objects.requireNonNull(closing, "closing must not be null");
            if (sequence < 1) {
                throw new IllegalArgumentException("a statement sequence starts at 1");
            }
            if (!opening.currency().equals(closing.currency())
                    || opening.scale() != closing.scale()) {
                throw new IllegalArgumentException(
                        "a statement's balances are one currency and scale");
            }
        }
    }

    public ParsedBatch {
        Objects.requireNonNull(externalBatchRef, "externalBatchRef must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(remittanceReference, "remittanceReference must not be null");
        Objects.requireNonNull(declaredNet, "declaredNet must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(statement, "statement must not be null");
        if (externalBatchRef.isBlank() || externalBatchRef.length() > 100) {
            throw new IllegalArgumentException("a batch reference is 1..100 characters");
        }
        if (remittanceReference.isPresent() == statement.isPresent()) {
            throw new IllegalArgumentException(
                    "a batch is a report (a remittance reference) or a statement (its continuity"
                            + " facts), exactly one");
        }
        remittanceReference.ifPresent(
                reference -> {
                    if (reference.isBlank() || reference.length() > 100) {
                        throw new IllegalArgumentException(
                                "a remittance reference is 1..100 characters");
                    }
                });
        statement.ifPresent(
                facts -> {
                    if (!facts.opening().currency().equals(declaredNet.currency())
                            || !facts.closing().minus(facts.opening()).equals(declaredNet)) {
                        throw new IllegalArgumentException(
                                "a statement's net is its closing minus its opening");
                    }
                });
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

    /** A counterparty's report: a remittance reference, no statement facts. */
    public ParsedBatch(
            String externalBatchRef,
            LocalDate businessDate,
            String remittanceReference,
            int declaredLineCount,
            Money declaredNet,
            List<ParsedLine> lines) {
        this(externalBatchRef, businessDate,
                Optional.of(Objects.requireNonNull(remittanceReference,
                        "remittanceReference must not be null")),
                declaredLineCount, declaredNet, lines, Optional.empty());
    }

    /** Identifiers only. */
    @Override
    public String toString() {
        return "ParsedBatch[" + externalBatchRef + ", " + lines.size() + " lines]";
    }
}
