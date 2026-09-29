package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hop 1's arithmetic, pinned (`P8-TSK-009`, ADR-0065 §2): only the fees post — folded with
 * {@code Money} per row, a rebate the opposite direction, zero omitted honestly, the mirror
 * entry for a net rebate, and the 16-line bound stated as a loud failure.
 */
@DisplayName("the batch recognition's arithmetic (P8-TSK-009)")
class BatchRecognitionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LedgerAccountId COSTS = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId POSITION = LedgerAccountId.of(IDS.next());

    private static SettlementBatchStore.LineRow line(
            SettlementLineType type, LineDirection direction, long minor) {
        return new SettlementBatchStore.LineRow(
                IDS.next(),
                1,
                type,
                direction,
                minor,
                EUR,
                2,
                LocalDate.parse("2026-09-29"),
                Optional.empty(),
                Optional.empty(),
                new byte[32],
                Map.of());
    }

    @Test
    @DisplayName("only the fees post: DR PROCESSING_COSTS / CR the position, the transaction"
            + " lines contributing nothing")
    void onlyTheFeesPost() {
        BatchRecognition.Recognition recognition =
                BatchRecognition.recognise(
                        List.of(
                                line(SettlementLineType.CAPTURE, LineDirection.INBOUND,
                                        10_000),
                                line(SettlementLineType.REFUND, LineDirection.OUTBOUND,
                                        4_025),
                                line(SettlementLineType.PROCESSING_FEE,
                                        LineDirection.OUTBOUND, 175),
                                line(SettlementLineType.PROCESSING_FEE,
                                        LineDirection.OUTBOUND, 50)),
                        EUR,
                        2,
                        COSTS,
                        POSITION);
        assertThat(recognition.postingOmitted()).isFalse();
        assertThat(recognition.fee().minorUnits()).isEqualTo(225L);
        assertThat(recognition.entryLines())
                .containsExactly(
                        new com.finapp.ledger.JournalLine(
                                COSTS,
                                Direction.DEBIT,
                                com.finapp.sharedkernel.money.Money.ofPersisted(225, EUR, 2)),
                        new com.finapp.ledger.JournalLine(
                                POSITION,
                                Direction.CREDIT,
                                com.finapp.sharedkernel.money.Money.ofPersisted(
                                        225, EUR, 2)));
    }

    @Test
    @DisplayName("a rebate folds the opposite direction, and a NET rebate posts the mirror")
    void aNetRebatePostsTheMirror() {
        BatchRecognition.Recognition recognition =
                BatchRecognition.recognise(
                        List.of(
                                line(SettlementLineType.PROCESSING_FEE,
                                        LineDirection.OUTBOUND, 100),
                                // The counterparty returned more than it charged.
                                line(SettlementLineType.PROCESSING_FEE,
                                        LineDirection.INBOUND, 275)),
                        EUR,
                        2,
                        COSTS,
                        POSITION);
        assertThat(recognition.fee().minorUnits()).isEqualTo(-175L);
        assertThat(recognition.entryLines())
                .containsExactly(
                        new com.finapp.ledger.JournalLine(
                                POSITION,
                                Direction.DEBIT,
                                com.finapp.sharedkernel.money.Money.ofPersisted(175, EUR, 2)),
                        new com.finapp.ledger.JournalLine(
                                COSTS,
                                Direction.CREDIT,
                                com.finapp.sharedkernel.money.Money.ofPersisted(
                                        175, EUR, 2)));
    }

    @Test
    @DisplayName("a zero fee omits the entry HONESTLY - recorded, never silent")
    void zeroFeeOmitsTheEntry() {
        BatchRecognition.Recognition recognition =
                BatchRecognition.recognise(
                        List.of(line(SettlementLineType.CAPTURE, LineDirection.INBOUND,
                                10_000)),
                        EUR,
                        2,
                        COSTS,
                        POSITION);
        assertThat(recognition.postingOmitted()).isTrue();
        assertThat(recognition.entryLines()).isEmpty();
        assertThat(recognition.fee().minorUnits()).isZero();
    }

    @Test
    @DisplayName("the recognition is deterministic over stored rows - the posting key's"
            + " fingerprint converges on a re-claim")
    void deterministicOverStoredRows() {
        List<SettlementBatchStore.LineRow> lines =
                List.of(
                        line(SettlementLineType.CAPTURE, LineDirection.INBOUND, 10_000),
                        line(SettlementLineType.PROCESSING_FEE, LineDirection.OUTBOUND,
                                175));
        assertThat(BatchRecognition.recognise(lines, EUR, 2, COSTS, POSITION).entryLines())
                .isEqualTo(
                        BatchRecognition.recognise(lines, EUR, 2, COSTS, POSITION)
                                .entryLines());
    }

    @Test
    @DisplayName("the entry is bounded at 16 lines - a wider source redesigns, never drifts")
    void theBoundIsPinned() {
        assertThat(BatchRecognition.MAX_ENTRY_LINES).isEqualTo(16);
    }
}
