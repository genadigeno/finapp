package com.finapp.settlement;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;

/**
 * Hop 1's arithmetic, pure (`P8-TSK-009`, ADR-0065 §2): a batch's canonical lines to its
 * recognition entry — deterministic over stored rows, so a re-claim after a crash produces
 * byte-identical lines and the posting key's fingerprint converges.
 *
 * <p><strong>Only the fees post.</strong> A report's transaction lines describe value the
 * completions already put in the position; posting them again would double it. With F the
 * fee fold (a rebate the opposite direction), the entry is DR {@code PROCESSING_COSTS} F /
 * CR the position F — the mirror for a net rebate — and an all-zero F omits the entry
 * honestly ({@code posting_omitted}). Folded with {@code Money}, never SQL ({@code
 * INV-MON-01}); one currency per batch is the parse's own guarantee ({@code INV-MON-04}).
 *
 * <p><strong>At most {@value #MAX_ENTRY_LINES} lines</strong>, pinned: ledger `V004`
 * re-validates an entry once per line, so a recognition that grew unbounded lines (a later
 * source's shape) must redesign, not drift.
 */
public final class BatchRecognition {

    public static final int MAX_ENTRY_LINES = 16;

    private BatchRecognition() {}

    /** The recognition's whole answer: the entry's lines (possibly none), and the fee fold. */
    public record Recognition(List<JournalLine> entryLines, Money fee) {

        public Recognition {
            Objects.requireNonNull(entryLines, "entryLines must not be null");
            Objects.requireNonNull(fee, "fee must not be null");
            entryLines = List.copyOf(entryLines);
        }

        /** A zero fee posts nothing — recorded as {@code posting_omitted}, never silent. */
        public boolean postingOmitted() {
            return entryLines.isEmpty();
        }
    }

    /**
     * F = Σ(fee OUT) − Σ(fee IN), folded per row through the kernel; the entry when F ≠ 0.
     *
     * @param lines the batch's canonical lines, as stored
     * @param currency the batch's one currency, at {@code scale} (the zero fold's shape)
     * @param processingCosts the {@code PROCESSING_COSTS} account of that currency
     * @param position the source's clearing position account (from the compiled register —
     *     the caller read it off the descriptor; this class never names a purpose)
     */
    public static Recognition recognise(
            List<SettlementBatchStore.LineRow> lines,
            com.finapp.sharedkernel.money.CurrencyCode currency,
            int scale,
            LedgerAccountId processingCosts,
            LedgerAccountId position) {
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(processingCosts, "processingCosts must not be null");
        Objects.requireNonNull(position, "position must not be null");
        Money fee = Money.ofPersisted(0, currency, scale);
        for (SettlementBatchStore.LineRow line : lines) {
            // A report's own fees - the PSP's processing fee, the scheme's fee (P8-TSK-017).
            if (!line.lineType().isReportFee()) {
                continue;
            }
            Money amount =
                    Money.ofPersisted(
                            line.amountMinor(), line.currency(), line.scale());
            fee =
                    line.direction() == LineDirection.OUTBOUND
                            ? fee.plus(amount)
                            : fee.minus(amount);
        }
        if (fee.minorUnits() == 0) {
            return new Recognition(List.of(), fee);
        }
        Money magnitude = fee.minorUnits() > 0 ? fee : fee.negated();
        List<JournalLine> entry =
                fee.minorUnits() > 0
                        // The counterparty charged: the expense grows, the position shrinks.
                        ? List.of(
                                new JournalLine(processingCosts, Direction.DEBIT, magnitude),
                                new JournalLine(position, Direction.CREDIT, magnitude))
                        // A net rebate: the mirror (ADR-0065 §2).
                        : List.of(
                                new JournalLine(position, Direction.DEBIT, magnitude),
                                new JournalLine(
                                        processingCosts, Direction.CREDIT, magnitude));
        if (entry.size() > MAX_ENTRY_LINES) {
            throw new IllegalStateException(
                    "a recognition entry is at most " + MAX_ENTRY_LINES
                            + " lines (P8-TSK-009): a wider source redesigns, never drifts");
        }
        return new Recognition(entry, fee);
    }
}
