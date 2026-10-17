package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.LedgerStorageException;
import com.finapp.ledger.OwnerKind;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The capital headroom and the capital proof count BNPL principal, hermetic (`P12-TSK-005`; owner decision B2,
 * ADR-0104 decisions 1, 2 and 7, PHASE_12_PLAN.md section 12.9; {@code INV-LND-13} extended, {@code INV-BNPL-10},
 * {@code INV-BAL-05}): over an in-memory ledger that answers {@link PurposeBalances#netDebit} by summing exactly the
 * purposes it is asked for - so a purpose dropped from the headroom's set is a purpose not counted - and records every
 * lock and read in order:
 *
 * <ul>
 *   <li>the outstanding term is ONE read over {@code LOAN_PRINCIPAL}, {@code LOAN_PRINCIPAL_DUE}, {@code BNPL_PRINCIPAL}
 *       and {@code BNPL_PRINCIPAL_DUE} - never interest, fees, credit balances or income (A29);
 *   <li>{@link LendingCapitalHeadroom#underLock} takes the capital row first, then reads the committed term before the
 *       outstanding one (`P11-TSK-012`'s rule, unchanged); {@code atRest} locks nothing;
 *   <li>with no BNPL line the figure is Phase 11's exactly;
 *   <li>the capital proof's headroom arm, {@code lending_funded} and the {@code headroom.low} gauge's count all see the
 *       BNPL principal - through the one derivation.
 * </ul>
 *
 * <p>The figures against real journal lines, the race for the last capital under L6 and the proof over committed BNPL
 * lines are {@code app}'s {@code LendingCapitalDatabaseTest} and {@code BnplCapitalDatabaseTest}.
 */
@DisplayName("capital headroom counts BNPL principal: one pool, one statement, one proof (P12-TSK-005)")
class LendingCapitalHeadroomTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Connection UOW =
            (Connection) Proxy.newProxyInstance(
                    LendingCapitalHeadroomTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("the fakes never touch the connection");
                    });

    private final Books books = new Books();
    private final LendingCapitalHeadroom headroom =
            new LendingCapitalHeadroom(books.chart(), books, books.loans());

    // ------------------------------------------------------------------ the principal set

    @Test
    @DisplayName("a BNPL principal line lowers the headroom: capital less committed, less loan principal, less BNPL"
            + " principal - one outstanding figure")
    void aBnplPrincipalLineLowersTheHeadroom() {
        books.capital(1_000_00).committed(100_00).balance(AccountPurpose.LOAN_PRINCIPAL, 200_00);
        assertThat(headroom.underLock(UOW, EUR).headroom()).isEqualTo(eur(700_00));

        books.balance(AccountPurpose.BNPL_PRINCIPAL, 300_00);
        LendingCapitalHeadroom.Headroom judged = headroom.underLock(UOW, EUR);
        assertThat(judged.outstanding()).isEqualTo(eur(500_00));
        assertThat(judged.headroom()).as("lowered by every unit of BNPL principal").isEqualTo(eur(400_00));
        assertThat(judged.capital()).as("capital itself unmoved").isEqualTo(eur(1_000_00));
        assertThat(judged.admits(eur(400_00))).isTrue();
        assertThat(judged.admits(eur(400_01))).as("a loan beside the BNPL book finds less").isFalse();
    }

    @Test
    @DisplayName("BNPL principal billed due still counts: a billing moving principal from not-due to due leaves the"
            + " headroom where it was")
    void bnplPrincipalDueStillCounts() {
        books.capital(1_000_00).balance(AccountPurpose.BNPL_PRINCIPAL, 400_00);
        Money before = headroom.underLock(UOW, EUR).headroom();

        books.balance(AccountPurpose.BNPL_PRINCIPAL, 250_00).balance(AccountPurpose.BNPL_PRINCIPAL_DUE, 150_00);
        LendingCapitalHeadroom.Headroom billed = headroom.underLock(UOW, EUR);
        assertThat(billed.headroom()).as("the same 400.00 owed, part of it due").isEqualTo(before).isEqualTo(eur(600_00));
        assertThat(billed.outstanding()).isEqualTo(eur(400_00));

        books.balance(AccountPurpose.BNPL_PRINCIPAL, 0).balance(AccountPurpose.BNPL_PRINCIPAL_DUE, 400_00);
        assertThat(headroom.underLock(UOW, EUR).headroom()).as("all of it due").isEqualTo(eur(600_00));
    }

    @Test
    @DisplayName("a repaid or refunded BNPL principal restores the headroom at commit, unit for unit")
    void aRepaidBnplPrincipalRestoresTheHeadroom() {
        books.capital(1_000_00).balance(AccountPurpose.BNPL_PRINCIPAL, 600_00).balance(AccountPurpose.BNPL_PRINCIPAL_DUE, 150_00);
        assertThat(headroom.underLock(UOW, EUR).headroom()).isEqualTo(eur(250_00));

        books.balance(AccountPurpose.BNPL_PRINCIPAL_DUE, 0); // a repayment of the instalment due
        assertThat(headroom.underLock(UOW, EUR).headroom()).isEqualTo(eur(400_00));

        books.balance(AccountPurpose.BNPL_PRINCIPAL, 100_00); // a refund cancelling the latest instalments
        assertThat(headroom.underLock(UOW, EUR).headroom()).isEqualTo(eur(900_00));
    }

    @Test
    @DisplayName("the outstanding term is ONE read over exactly the four principal purposes - no BNPL interest, fee,"
            + " credit balance or income is capital (A29)")
    void theOutstandingTermIsOneReadOverTheFourPrincipalPurposes() {
        books.capital(1_000_00)
                .balance(AccountPurpose.BNPL_INTEREST_ACCRUED, 11_00)
                .balance(AccountPurpose.BNPL_INTEREST_DUE, 12_00)
                .balance(AccountPurpose.BNPL_FEES_DUE, 13_00)
                .balance(AccountPurpose.BNPL_CREDIT_BALANCE, -14_00)
                .balance(AccountPurpose.BNPL_MERCHANT_FEE_INCOME, -15_00)
                .balance(AccountPurpose.BNPL_INTEREST_INCOME, -16_00)
                .balance(AccountPurpose.LOAN_INTEREST_DUE, 17_00);
        assertThat(headroom.underLock(UOW, EUR).headroom())
                .as("interest, fees, credit balances and income move no capital")
                .isEqualTo(eur(1_000_00));

        List<String> principalReads = books.events.stream().filter(event -> event.startsWith("netDebit:")
                        && !event.equals("netDebit:[LENDING_CAPITAL]"))
                .toList();
        assertThat(principalReads)
                .as("one statement, so a billing in flight is wholly in or wholly out")
                .containsExactly("netDebit:[BNPL_PRINCIPAL, BNPL_PRINCIPAL_DUE, LOAN_PRINCIPAL, LOAN_PRINCIPAL_DUE]");
    }

    @Test
    @DisplayName("with no BNPL line the headroom is Phase 11's exactly - capital less committed less loan and line"
            + " principal - and every lending consumer's verdict unchanged")
    void withoutBnplLinesTheHeadroomIsPhase11s() {
        books.capital(1_700_00).committed(300_00)
                .balance(AccountPurpose.LOAN_PRINCIPAL, 900_00)
                .balance(AccountPurpose.LOAN_PRINCIPAL_DUE, 100_00);
        LendingCapitalHeadroom.Headroom judged = headroom.underLock(UOW, EUR);
        assertThat(judged.headroom()).isEqualTo(eur(1_700_00 - 300_00 - 900_00 - 100_00));
        assertThat(judged.outstanding()).isEqualTo(eur(1_000_00));
        assertThat(judged.lendingFunded()).isEqualTo(eur(1_300_00));
        assertThat(judged.admits(eur(400_00))).isTrue();
        assertThat(judged.admits(eur(400_01))).isFalse();
    }

    // ------------------------------------------------------------------ the lock and the order

    @Test
    @DisplayName("under L6: the capital row locked FOR UPDATE first, then the committed term, then the outstanding -"
            + " BNPL's arm in the same statement; at rest, no lock")
    void underLockTakesTheCapitalRowFirstThenCommittedThenOutstanding() {
        books.capital(1_000_00).balance(AccountPurpose.BNPL_PRINCIPAL, 10_00);
        headroom.underLock(UOW, EUR);
        assertThat(books.events.stream().filter(event -> !event.startsWith("find:")).toList())
                .containsExactly(
                        "lock:" + books.capitalId.value(),
                        "committed",
                        "netDebit:[LENDING_CAPITAL]",
                        "netDebit:[BNPL_PRINCIPAL, BNPL_PRINCIPAL_DUE, LOAN_PRINCIPAL, LOAN_PRINCIPAL_DUE]");

        books.events.clear();
        headroom.atRest(UOW, EUR);
        assertThat(books.events).as("a reading for the proof and the report locks nothing").noneMatch(event -> event.startsWith("lock:"));
        assertThat(books.events.indexOf("committed"))
                .as("the committed term before the outstanding, at rest too")
                .isLessThan(books.events.indexOf(
                        "netDebit:[BNPL_PRINCIPAL, BNPL_PRINCIPAL_DUE, LOAN_PRINCIPAL, LOAN_PRINCIPAL_DUE]"));
    }

    @Test
    @DisplayName("a failed read fails the caller - never a headroom of zero or of the old formula")
    void aFailedReadFailsTheCaller() {
        books.capital(1_000_00).failingPrincipal = true;
        assertThatThrownBy(() -> headroom.underLock(UOW, EUR)).isInstanceOf(LedgerStorageException.class);
        assertThatThrownBy(() -> headroom.atRest(UOW, EUR)).isInstanceOf(LedgerStorageException.class);
    }

    // ------------------------------------------------------------------ the proof, the safeguarding term, the gauge

    @Test
    @DisplayName("the capital proof counts BNPL principal: recognised capital = headroom + committed + loan and BNPL"
            + " principal outstanding; BNPL principal beyond the capital fails the headroom arm, completeness unmoved")
    void theCapitalProofCountsBnplPrincipal() {
        books.capital(1_000_00).committed(100_00)
                .balance(AccountPurpose.LOAN_PRINCIPAL, 300_00)
                .balance(AccountPurpose.BNPL_PRINCIPAL, 400_00)
                .balance(AccountPurpose.BNPL_PRINCIPAL_DUE, 100_00);
        LendingCapitalProof proof = new LendingCapitalProof(headroom, books, books.recognitions());
        LendingCapitalProof.CurrencyVerdict verdict = proof.prove(UOW, EUR);
        assertThat(verdict.complete()).isTrue();
        assertThat(verdict.headroomHolds()).isTrue();
        assertThat(verdict.terms().headroom()).isEqualTo(eur(100_00));
        assertThat(verdict.terms().headroom().plus(verdict.terms().committed()).plus(verdict.terms().outstanding()))
                .as("the identity the proof stands on")
                .isEqualTo(verdict.terms().capital());

        books.balance(AccountPurpose.BNPL_PRINCIPAL_DUE, 200_01);
        LendingCapitalProof.CurrencyVerdict over = proof.prove(UOW, EUR);
        assertThat(over.complete()).as("every capital line is still a recognition's").isTrue();
        assertThat(over.headroomHolds()).as("BNPL principal beyond the capital is over-deployment").isFalse();
        assertThat(over.holds()).isFalse();
    }

    @Test
    @DisplayName("the safeguarding term lending_funded includes BNPL principal: committed + loan + BNPL principal"
            + " outstanding")
    void lendingFundedIncludesBnplPrincipal() {
        books.capital(2_000_00).committed(250_00)
                .balance(AccountPurpose.LOAN_PRINCIPAL, 300_00)
                .balance(AccountPurpose.LOAN_PRINCIPAL_DUE, 50_00)
                .balance(AccountPurpose.BNPL_PRINCIPAL, 400_00)
                .balance(AccountPurpose.BNPL_PRINCIPAL_DUE, 100_00);
        LendingCapitalProof.CurrencyVerdict verdict =
                new LendingCapitalProof(headroom, books, books.recognitions()).prove(UOW, EUR);
        assertThat(verdict.lendingFunded()).isEqualTo(eur(250_00 + 300_00 + 50_00 + 400_00 + 100_00));
    }

    @Test
    @DisplayName("the headroom.low gauge's count sees BNPL principal: a currency pushed below its floor by a BNPL book"
            + " counts, as one held above it by none does not")
    void theHeadroomLowCountSeesBnplPrincipal() {
        books.capital(1_000_00).balance(AccountPurpose.LOAN_PRINCIPAL, 100_00);
        LendingCapitalProof proof = new LendingCapitalProof(headroom, books, books.recognitions());
        Map<String, Long> floors = Map.of("EUR", 500_00L);
        assertThat(new LendingCapitalProof.Report(List.of(proof.prove(UOW, EUR))).belowFloor(floors)).isZero();

        books.balance(AccountPurpose.BNPL_PRINCIPAL, 400_01);
        assertThat(new LendingCapitalProof.Report(List.of(proof.prove(UOW, EUR))).belowFloor(floors))
                .as("headroom 499.99 < floor 500.00, by the BNPL book")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ the in-memory ledger

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    /**
     * Net debit per purpose (DR-CR), the capital's credit recognised entry by entry, the committed principal - and every
     * lock and read in order. {@link #netDebit} sums exactly the purposes asked for.
     */
    private static final class Books implements PurposeBalances<Connection> {

        final List<String> events = new ArrayList<>();
        final LedgerAccountId capitalId = LedgerAccountId.of(IDS.next());
        final Map<AccountPurpose, Long> netDebits = new EnumMap<>(AccountPurpose.class);
        final JournalEntryId recognitionEntry = JournalEntryId.of(IDS.next());
        long committedMinor;
        boolean failingPrincipal;

        Books capital(long minor) {
            netDebits.put(AccountPurpose.LENDING_CAPITAL, -minor);
            return this;
        }

        Books committed(long minor) {
            committedMinor = minor;
            return this;
        }

        Books balance(AccountPurpose purpose, long netDebitMinor) {
            netDebits.put(purpose, netDebitMinor);
            return this;
        }

        @Override
        public Money netDebit(Connection unitOfWork, Set<AccountPurpose> purposes, CurrencyCode currency) {
            events.add("netDebit:" + new TreeSet<>(purposes.stream().map(Enum::name).collect(Collectors.toSet())));
            if (failingPrincipal && !purposes.equals(EnumSet.of(AccountPurpose.LENDING_CAPITAL))) {
                throw new LedgerStorageException("the principal lines could not be read");
            }
            long sum = 0;
            for (AccountPurpose purpose : purposes) {
                sum += netDebits.getOrDefault(purpose, 0L);
            }
            return Money.ofMinorUnits(sum, currency);
        }

        @Override
        public List<EntryLine> linesOn(Connection unitOfWork, LedgerAccountId account) {
            assertThat(account).isEqualTo(capitalId);
            long capital = -netDebits.getOrDefault(AccountPurpose.LENDING_CAPITAL, 0L);
            return capital == 0 ? List.of() : List.of(new EntryLine(recognitionEntry, Direction.CREDIT, eur(capital)));
        }

        @Override
        public List<DatedLine> datedLinesOn(
                Connection unitOfWork, Set<LedgerAccountId> accounts, LocalDate valueDateThrough) {
            throw new UnsupportedOperationException("the headroom reads no dated lines");
        }

        CapitalRecognitions recognitions() {
            return new CapitalRecognitions() {
                @Override
                public Map<String, ExpectationReading> expectations(
                        Connection unitOfWork, java.util.Collection<String> expectationRefs) {
                    throw new UnsupportedOperationException("the proof reads recognitions, not expectations");
                }

                @Override
                public List<Recognition> recognitions(Connection unitOfWork, CurrencyCode currency) {
                    long capital = -netDebits.getOrDefault(AccountPurpose.LENDING_CAPITAL, 0L);
                    return capital == 0 ? List.of() : List.of(new Recognition(recognitionEntry, eur(capital)));
                }
            };
        }

        @SuppressWarnings("unchecked")
        LedgerAccountStore<Connection> chart() {
            LedgerAccount capital = LedgerAccount.rehydrate(
                    capitalId, AccountType.EQUITY, AccountType.EQUITY.normalBalance(), EUR, OwnerKind.OPERATIONAL, null,
                    AccountPurpose.LENDING_CAPITAL, null, LedgerAccountStatus.ACTIVE, CLOCK.instant(), CLOCK.instant());
            return (LedgerAccountStore<Connection>) Proxy.newProxyInstance(
                    LendingCapitalHeadroomTest.class.getClassLoader(), new Class<?>[] {LedgerAccountStore.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findOperational" -> {
                            events.add("find:" + args[1]);
                            yield Optional.of(capital);
                        }
                        case "lockForUpdate" -> {
                            events.add("lock:" + ((LedgerAccountId) args[1]).value());
                            yield Optional.of(capital);
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        LoanStore loans() {
            return (LoanStore) Proxy.newProxyInstance(
                    LendingCapitalHeadroomTest.class.getClassLoader(), new Class<?>[] {LoanStore.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("committedPrincipal")) {
                            events.add("committed");
                            return Money.ofMinorUnits(committedMinor, (CurrencyCode) args[1]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
