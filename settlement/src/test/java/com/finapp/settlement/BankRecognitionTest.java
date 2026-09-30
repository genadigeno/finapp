package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hop 2's arithmetic, pinned (`P8-TSK-016`, ADR-0065 §3, {@code INV-SET-06}): a bank statement's
 * canonical lines become ONE recognition entry — cash the whole fold, each attributed source's
 * clearing position the opposite of its own fold, the bank's fee to {@code PROCESSING_COSTS}, and
 * the unattributed credits and debits to {@code SUSPENSE_UNMATCHED} on two lines that are NEVER
 * netted (ADR-0070). The entry balances by construction; an all-zero statement omits it honestly;
 * a stored net that disagrees with the lines, a non-bank line or an attributed source without a
 * resolved position is a loud failure, never something posted around.
 */
@DisplayName("the bank statement's recognition arithmetic (P8-TSK-016)")
class BankRecognitionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate STATEMENT_DAY = LocalDate.parse("2026-09-25");

    // The seeded source rows' own UUIDv7 literals (settlement V002): their order is the entry's.
    private static final UUID PSP_SOURCE =
            UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID SCHEME_SOURCE =
            UUID.fromString("01a0e2bc-8200-7002-8000-000000000002");
    private static final UUID PAYOUT_SOURCE =
            UUID.fromString("01a0e2bc-8200-7003-8000-000000000003");

    private static final LedgerAccountId CASH = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId COSTS = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId SUSPENSE = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId SETTLEMENT_CLEARING = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId INSTANT_CLEARING = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId PAYOUT_CLEARING = LedgerAccountId.of(IDS.next());

    private static final BankRecognition.Accounts ACCOUNTS =
            new BankRecognition.Accounts(
                    CASH,
                    COSTS,
                    SUSPENSE,
                    Map.of(
                            PSP_SOURCE, SETTLEMENT_CLEARING,
                            SCHEME_SOURCE, INSTANT_CLEARING,
                            PAYOUT_SOURCE, PAYOUT_CLEARING));

    private static SettlementBatchStore.LineRow line(
            int lineNo,
            SettlementLineType type,
            LineDirection direction,
            long minor,
            Optional<UUID> attributedSource) {
        return new SettlementBatchStore.LineRow(
                IDS.next(),
                lineNo,
                type,
                direction,
                minor,
                EUR,
                2,
                STATEMENT_DAY,
                Optional.empty(),
                Optional.of(STATEMENT_DAY),
                new byte[32],
                Map.of(),
                attributedSource);
    }

    private static SettlementBatchStore.LineRow credit(int lineNo, long minor, UUID source) {
        return line(lineNo, SettlementLineType.BANK_CREDIT, LineDirection.INBOUND, minor,
                Optional.of(source));
    }

    private static SettlementBatchStore.LineRow unattributedCredit(int lineNo, long minor) {
        return line(lineNo, SettlementLineType.BANK_CREDIT, LineDirection.INBOUND, minor,
                Optional.empty());
    }

    private static SettlementBatchStore.LineRow debit(int lineNo, long minor, UUID source) {
        return line(lineNo, SettlementLineType.BANK_DEBIT, LineDirection.OUTBOUND, minor,
                Optional.of(source));
    }

    private static SettlementBatchStore.LineRow unattributedDebit(int lineNo, long minor) {
        return line(lineNo, SettlementLineType.BANK_DEBIT, LineDirection.OUTBOUND, minor,
                Optional.empty());
    }

    private static SettlementBatchStore.LineRow fee(int lineNo, long minor) {
        return line(lineNo, SettlementLineType.BANK_FEE, LineDirection.OUTBOUND, minor,
                Optional.empty());
    }

    /** The golden statement's five lines (settlement's golden-v1.txt), every pattern seeded. */
    private static List<SettlementBatchStore.LineRow> goldenShaped() {
        return List.of(
                credit(1, 9_825, PSP_SOURCE),
                credit(2, 4_000, SCHEME_SOURCE),
                debit(3, 1_250, PAYOUT_SOURCE),
                unattributedCredit(4, 500),
                fee(5, 50));
    }

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static JournalLine dr(LedgerAccountId account, long minor) {
        return new JournalLine(account, Direction.DEBIT, eur(minor));
    }

    private static JournalLine cr(LedgerAccountId account, long minor) {
        return new JournalLine(account, Direction.CREDIT, eur(minor));
    }

    private static BankRecognition.Recognition recognise(
            List<SettlementBatchStore.LineRow> lines, long declaredNetMinor) {
        return BankRecognition.recognise(lines, EUR, 2, eur(declaredNetMinor), ACCOUNTS);
    }

    /** Debits and credits folded apart with {@code Money}: the balance asserted, not assumed. */
    private static void assertBalances(List<JournalLine> entry) {
        Money debits = eur(0);
        Money credits = eur(0);
        for (JournalLine each : entry) {
            if (each.direction() == Direction.DEBIT) {
                debits = debits.plus(each.amount());
            } else {
                credits = credits.plus(each.amount());
            }
        }
        assertThat(debits).as("total debits = total credits (INV-LED-01)").isEqualTo(credits);
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the golden statement's entry: DR cash the net, each attributed position the"
            + " opposite of its fold, DR PROCESSING_COSTS the fee, CR SUSPENSE the unattributed"
            + " credit - and debits equal credits")
    void theGoldenStatementsEntry() {
        BankRecognition.Recognition recognition = recognise(goldenShaped(), 13_025);

        assertThat(recognition.postingOmitted()).isFalse();
        assertThat(recognition.cash()).isEqualTo(eur(13_025));
        assertThat(recognition.entryLines())
                .as("cash first, the positions by source id, the fee, then suspense")
                .containsExactly(
                        dr(CASH, 13_025),
                        cr(SETTLEMENT_CLEARING, 9_825),
                        cr(INSTANT_CLEARING, 4_000),
                        dr(PAYOUT_CLEARING, 1_250),
                        dr(COSTS, 50),
                        cr(SUSPENSE, 500));
        assertBalances(recognition.entryLines());
    }

    @Test
    @DisplayName("an unattributed credit and an unattributed debit of equal size are TWO"
            + " suspense lines, never netted - and the entry stands although cash nets to zero")
    void unattributedSidesAreNeverNetted() {
        BankRecognition.Recognition recognition =
                recognise(List.of(unattributedCredit(1, 700), unattributedDebit(2, 700)), 0);

        assertThat(recognition.cash().isZero()).isTrue();
        assertThat(recognition.postingOmitted())
                .as("every suspense item must name an entry (INV-REC-09): never omitted")
                .isFalse();
        assertThat(recognition.entryLines())
                .containsExactly(cr(SUSPENSE, 700), dr(SUSPENSE, 700));
        assertBalances(recognition.entryLines());
    }

    @Test
    @DisplayName("an attributed source's lines net to ONE position line, while its"
            + " unattributed neighbours stay apart")
    void anAttributedSourceNetsToOnePositionLine() {
        BankRecognition.Recognition recognition =
                recognise(
                        List.of(
                                credit(1, 300, PSP_SOURCE),
                                debit(2, 100, PSP_SOURCE),
                                unattributedCredit(3, 40),
                                unattributedDebit(4, 15)),
                        225);

        assertThat(recognition.entryLines())
                .containsExactly(
                        dr(CASH, 225),
                        cr(SETTLEMENT_CLEARING, 200),
                        cr(SUSPENSE, 40),
                        dr(SUSPENSE, 15));
        assertBalances(recognition.entryLines());
    }

    @Test
    @DisplayName("a net outflow CREDITS cash: the payout's debit leaves the account and"
            + " discharges its clearing position")
    void aNetOutflowCreditsCash() {
        BankRecognition.Recognition recognition =
                recognise(List.of(debit(1, 1_250, PAYOUT_SOURCE), fee(2, 50)), -1_300);

        assertThat(recognition.cash()).isEqualTo(eur(-1_300));
        assertThat(recognition.entryLines())
                .containsExactly(cr(CASH, 1_300), dr(PAYOUT_CLEARING, 1_250), dr(COSTS, 50));
        assertBalances(recognition.entryLines());
    }

    @Test
    @DisplayName("an all-zero statement - no lines - omits the entry HONESTLY")
    void anAllZeroStatementOmitsTheEntry() {
        BankRecognition.Recognition recognition = recognise(List.of(), 0);

        assertThat(recognition.postingOmitted()).isTrue();
        assertThat(recognition.entryLines()).isEmpty();
        assertThat(recognition.cash().isZero()).isTrue();
    }

    @Test
    @DisplayName("a stored net that disagrees with the lines is a corrupted parse statement -"
            + " refused loudly, never posted around (INV-SET-06)")
    void aDisagreeingNetIsRefused() {
        assertThatThrownBy(() -> recognise(goldenShaped(), 13_026))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INV-SET-06");
    }

    @Test
    @DisplayName("a statement carries bank lines only - a report line is refused")
    void aNonBankLineIsRefused() {
        SettlementBatchStore.LineRow capture =
                line(1, SettlementLineType.CAPTURE, LineDirection.INBOUND, 1_000,
                        Optional.empty());

        assertThatThrownBy(() -> recognise(List.of(capture), 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank lines only");
    }

    @Test
    @DisplayName("an attributed source with no resolved position account is refused - never"
            + " guessed into another position")
    void anUnresolvedPositionIsRefused() {
        BankRecognition.Accounts withoutPositions =
                new BankRecognition.Accounts(CASH, COSTS, SUSPENSE, Map.of());

        assertThatThrownBy(
                        () ->
                                BankRecognition.recognise(
                                        List.of(credit(1, 9_825, PSP_SOURCE)),
                                        EUR,
                                        2,
                                        eur(9_825),
                                        withoutPositions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no position account resolved")
                .hasMessageContaining(PSP_SOURCE.toString());
    }

    @Test
    @DisplayName("the recognition is deterministic over stored rows: the same input twice, or"
            + " in another order, gives equal lines - the positions ordered by source id")
    void theRecognitionIsDeterministic() {
        List<SettlementBatchStore.LineRow> golden = goldenShaped();
        List<SettlementBatchStore.LineRow> reversed = new ArrayList<>(golden);
        Collections.reverse(reversed);

        List<JournalLine> first = recognise(golden, 13_025).entryLines();
        assertThat(recognise(golden, 13_025).entryLines())
                .as("a re-claim after a crash converges on the posting key's fingerprint")
                .isEqualTo(first);
        assertThat(recognise(reversed, 13_025).entryLines())
                .as("the payout line first changes nothing: positions follow source id")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("a statement wider than the 16-line bound is refused loudly - it redesigns,"
            + " never drifts")
    void theLineBoundHolds() {
        // 1 cash + 13 positions + 1 fee + 2 suspense = 17 lines.
        Map<UUID, LedgerAccountId> positions = new HashMap<>();
        List<SettlementBatchStore.LineRow> lines = new ArrayList<>();
        long net = 0;
        for (int i = 0; i < 13; i++) {
            UUID source = IDS.next();
            positions.put(source, LedgerAccountId.of(IDS.next()));
            lines.add(credit(i + 1, 100 + i, source));
            net += 100 + i;
        }
        lines.add(fee(14, 5));
        lines.add(unattributedCredit(15, 20));
        lines.add(unattributedDebit(16, 10));
        net += -5 + 20 - 10;
        BankRecognition.Accounts wide =
                new BankRecognition.Accounts(CASH, COSTS, SUSPENSE, positions);
        long declared = net;

        assertThatThrownBy(() -> BankRecognition.recognise(lines, EUR, 2, eur(declared), wide))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(BatchRecognition.MAX_ENTRY_LINES));
    }
}
