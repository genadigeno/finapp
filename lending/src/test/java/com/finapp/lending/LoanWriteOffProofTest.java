package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryOverflowException;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The subledger proof and the replay gain rule 14 (`P14-TSK-021`; ADR-0096 points 2 and 3; {@code INV-LND-01}), hermetic, over a record
 * built by hand - what keeps {@code SOURCE_COMPLETENESS} honest about a written-off month: a write-off the rows explain holds, and one
 * they do not fails, so the proofs can fail on it. And the position rule 14's lines come from.
 */
@DisplayName("the subledger proof and the replay gain rule 14 (P14-TSK-021)")
class LoanWriteOffProofTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate WRITTEN_OFF_ON = LocalDate.of(2026, 11, 4);
    private static final Instant DRAWN_AT = Instant.parse("2026-01-02T10:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-11-04T10:00:00Z");

    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(DECIDED_AT, ZoneOffset.UTC), new SecureRandom());

    private final LedgerAccountId principal = LedgerAccountId.of(IDS.next());
    private final LedgerAccountId credit = LedgerAccountId.of(IDS.next());
    private final JournalEntryId drawEntry = JournalEntryId.of(IDS.next());
    private final JournalEntryId writeOffEntry = JournalEntryId.of(IDS.next());

    @Test
    @DisplayName("a written-off account whose rows explain its lines holds: its balances zero, its entry named, IDENTICAL on replay")
    void anExplainedWriteOffHolds() {
        LoanRecord record = record(LoanStatus.WRITTEN_OFF, List.of(writeOff(100_00)), writeOffLines(100_00));
        LoanSubledgerProof.Verdict verdict = new LoanSubledgerProof().prove(record);
        assertThat(verdict.holds()).as("%s", verdict).isTrue();
        assertThat(verdict.terminalZero()).isTrue();
        assertThat(LoanSubledgerProof.namedEntries(record).get(writeOffEntry).kind()).isEqualTo("WRITE_OFF");
        LoanReplayProof.Replay replay = new LoanReplayProof().replay(record);
        assertThat(replay.verdict()).as("%s", replay.findings()).isEqualTo(LoanReplayProof.Verdict.IDENTICAL);
    }

    @Test
    @DisplayName("a write-off no row explains fails the proofs twice over: the balance, the unnamed entry, and the status against no row")
    void anUnexplainedWriteOffFails() {
        LoanRecord record = record(LoanStatus.WRITTEN_OFF, List.of(), writeOffLines(100_00));
        LoanSubledgerProof.Verdict verdict = new LoanSubledgerProof().prove(record);
        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.failures()).contains("LOAN_PRINCIPAL", "UNEXPLAINED_ENTRIES");
        assertThat(new LoanReplayProof().replay(record).checks()).contains(LoanReplayProof.Check.WRITE_OFF);
    }

    @Test
    @DisplayName("a partial write-off fails: the row took less than the receivable held, whichever writer wrote it")
    void aPartialWriteOffFails() {
        LoanRecord record = record(LoanStatus.WRITTEN_OFF, List.of(writeOff(60_00)), writeOffLines(60_00));
        LoanSubledgerProof.Verdict verdict = new LoanSubledgerProof().prove(record);
        assertThat(verdict.failures()).as("left standing on a terminal account").contains("TERMINAL_NOT_ZERO");
        assertThat(new LoanReplayProof().replay(record).checks()).contains(LoanReplayProof.Check.WRITE_OFF);
    }

    @Test
    @DisplayName("a row naming more than its entry credited fails: the figures named and the ledger are one state, or the replay says not")
    void aRowAndAnEntryDisagreeingFails() {
        LoanRecord record = record(LoanStatus.WRITTEN_OFF, List.of(writeOff(100_00)), writeOffLines(90_00));
        assertThat(new LoanSubledgerProof().prove(record).holds()).isFalse();
        assertThat(new LoanReplayProof().replay(record).checks()).contains(LoanReplayProof.Check.WRITE_OFF);
    }

    @Test
    @DisplayName("an approved write-off on an account still ACTIVE diverges: the status and the row say one thing")
    void theStatusAndTheRowSayOneThing() {
        LoanRecord record = record(LoanStatus.ACTIVE, List.of(writeOff(100_00)), writeOffLines(100_00));
        assertThat(new LoanReplayProof().replay(record).checks()).contains(LoanReplayProof.Check.WRITE_OFF);
    }

    @Test
    @DisplayName("a rejected write-off explains nothing and asks nothing: the account is unmoved")
    void aRejectedWriteOffExplainsNothing() {
        LoanRecord.WriteOffRow rejected = new LoanRecord.WriteOffRow(new UUID(9, 9), eur(100_00), eur(0), eur(0), eur(0), eur(0), 1, DRAWN_AT,
                Optional.of("REJECTED"), Optional.of(DECIDED_AT), Optional.empty(), Optional.empty(), 2);
        LoanRecord record = record(LoanStatus.ACTIVE, List.of(rejected), List.of());
        assertThat(new LoanSubledgerProof().prove(record).holds()).isTrue();
        assertThat(new LoanReplayProof().replay(record).verdict()).isEqualTo(LoanReplayProof.Verdict.IDENTICAL);
    }

    // ------------------------------------------------------------------ the position

    @Test
    @DisplayName("rule 14's lines: the total debited, each receivable holding anything credited by exactly that, nothing for a zero - balanced")
    void theLinesAreWholeAndBalanced() {
        LoanReceivables.Accounts accounts = new LoanReceivables.Accounts(id(1), id(2), id(3), id(4), id(5), id(6));
        LedgerAccountId expense = id(7);
        LoanWriteOffPosition position = new LoanWriteOffPosition(eur(700_00), eur(100_00), eur(0), eur(20_00), eur(15_00), 9);
        List<JournalLine> lines = position.lines(expense, accounts);
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0).account()).isEqualTo(expense);
        assertThat(lines.get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(lines.get(0).amount()).isEqualTo(eur(835_00));
        assertThat(position.principalTotal()).isEqualTo(eur(800_00));
        Money credits = eur(0);
        for (JournalLine line : lines.subList(1, lines.size())) {
            assertThat(line.direction()).isEqualTo(Direction.CREDIT);
            assertThat(line.account()).as("never the credit balance").isNotEqualTo(accounts.creditBalance());
            credits = credits.plus(line.amount());
        }
        assertThat(credits).isEqualTo(lines.get(0).amount());
    }

    @Test
    @DisplayName("nothing to take builds no entry; a negative figure, another currency or another scale is no position")
    void aPositionIsWellFormed() {
        LoanReceivables.Accounts accounts = new LoanReceivables.Accounts(id(1), id(2), id(3), id(4), id(5), id(6));
        Assertions.assertThatThrownBy(() -> new LoanWriteOffPosition(eur(0), eur(0), eur(0), eur(0), eur(0), 0).lines(id(7), accounts))
                .isInstanceOf(IllegalStateException.class);
        Assertions.assertThatThrownBy(() -> new LoanWriteOffPosition(eur(-1), eur(0), eur(0), eur(0), eur(0), 0))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() -> new LoanWriteOffPosition(eur(1), Money.ofMinorUnits(1, CurrencyCode.of("GBP")), eur(0), eur(0),
                eur(0), 0)).isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() -> new LoanWriteOffPosition(eur(1), eur(0), eur(0), eur(0), eur(0), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a total past what the platform can hold is the money type's refusal, which the door answers deliberately - never a wrap")
    void aTotalPastTheBoundIsRefused() {
        Money huge = Money.ofPersisted(Long.MAX_VALUE, EUR, 2);
        LoanWriteOffPosition position = new LoanWriteOffPosition(huge, huge, eur(0), eur(0), eur(0), 2);
        Assertions.assertThatThrownBy(position::total).isInstanceOf(MonetaryOverflowException.class);
    }

    // ------------------------------------------------------------------ plumbing

    private static LedgerAccountId id(int low) {
        return LedgerAccountId.of(IDS.next());
    }

    private LoanRecord.WriteOffRow writeOff(long minor) {
        return new LoanRecord.WriteOffRow(new UUID(9, 1), eur(minor), eur(0), eur(0), eur(0), eur(0), 1, DRAWN_AT, Optional.of("APPROVED"),
                Optional.of(DECIDED_AT), Optional.of(writeOffEntry), Optional.of(WRITTEN_OFF_ON), 200);
    }

    private List<PurposeBalances.DatedLine> writeOffLines(long minor) {
        return List.of(new PurposeBalances.DatedLine(writeOffEntry, principal, WRITTEN_OFF_ON, Direction.CREDIT, eur(minor)));
    }

    /** A credit line drawn 100.00, in the account's lines and rows, and {@code writeOffs} and {@code more} beside it. */
    private LoanRecord record(LoanStatus status, List<LoanRecord.WriteOffRow> writeOffs, List<PurposeBalances.DatedLine> more) {
        LoanRecord.Account account = new LoanRecord.Account(LoanId.of(IDS.next()), new UUID(1, 1),
                LoanProduct.CREDIT_LINE, LoanKind.REVOLVING, status, EUR, LoanOfferId.of(IDS.next()),
                new UUID(1, 2), DRAWN_AT, Optional.empty(), Optional.empty(), Optional.empty());
        List<LoanRecord.LedgerAccountRow> ledger = new ArrayList<>();
        ledger.add(new LoanRecord.LedgerAccountRow(principal, AccountPurpose.LOAN_PRINCIPAL, EUR, "ACTIVE"));
        ledger.add(new LoanRecord.LedgerAccountRow(id(10), AccountPurpose.LOAN_PRINCIPAL_DUE, EUR, "ACTIVE"));
        ledger.add(new LoanRecord.LedgerAccountRow(id(11), AccountPurpose.LOAN_INTEREST_ACCRUED, EUR, "ACTIVE"));
        ledger.add(new LoanRecord.LedgerAccountRow(id(12), AccountPurpose.LOAN_INTEREST_DUE, EUR, "ACTIVE"));
        ledger.add(new LoanRecord.LedgerAccountRow(id(13), AccountPurpose.LOAN_FEES_DUE, EUR, "ACTIVE"));
        ledger.add(new LoanRecord.LedgerAccountRow(credit, AccountPurpose.LOAN_CREDIT_BALANCE, EUR, "ACTIVE"));
        List<PurposeBalances.DatedLine> lines = new ArrayList<>();
        lines.add(new PurposeBalances.DatedLine(drawEntry, principal, LocalDate.of(2026, 1, 2), Direction.DEBIT, eur(100_00)));
        lines.addAll(more);
        LoanRecord.Draw draw = new LoanRecord.Draw(new UUID(3, 3), eur(100_00), new UUID(4, 4), 1, drawEntry, LocalDate.of(2026, 1, 2),
                DRAWN_AT, 100);
        return new LoanRecord(account, List.of(), Map.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), List.of(draw), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), Optional.empty(), List.of(), List.of(), Optional.empty(), List.of(), List.of(), ledger, lines, writeOffs);
    }

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }
}
