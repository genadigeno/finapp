package com.finapp.accounting.reporting;

import static com.finapp.accounting.testing.ReportBodies.flag;
import static com.finapp.accounting.testing.ReportBodies.isNull;
import static com.finapp.accounting.testing.ReportBodies.minor;
import static com.finapp.accounting.testing.ReportBodies.object;
import static com.finapp.accounting.testing.ReportBodies.objects;
import static com.finapp.accounting.testing.ReportBodies.scale;
import static com.finapp.accounting.testing.ReportBodies.text;
import static com.finapp.accounting.testing.SafeguardingBooks.BNPL_PRINCIPAL;
import static com.finapp.accounting.testing.SafeguardingBooks.CASH;
import static com.finapp.accounting.testing.SafeguardingBooks.CHARGEBACK;
import static com.finapp.accounting.testing.SafeguardingBooks.CORRIDOR;
import static com.finapp.accounting.testing.SafeguardingBooks.CREDIT_BALANCE;
import static com.finapp.accounting.testing.SafeguardingBooks.FX_POSITION;
import static com.finapp.accounting.testing.SafeguardingBooks.FX_PROVIDER;
import static com.finapp.accounting.testing.SafeguardingBooks.INSTANT;
import static com.finapp.accounting.testing.SafeguardingBooks.LOAN_PRINCIPAL;
import static com.finapp.accounting.testing.SafeguardingBooks.LOAN_PRINCIPAL_DUE;
import static com.finapp.accounting.testing.SafeguardingBooks.MERCHANT;
import static com.finapp.accounting.testing.SafeguardingBooks.PAYOUT;
import static com.finapp.accounting.testing.SafeguardingBooks.SETTLEMENT;
import static com.finapp.accounting.testing.SafeguardingBooks.SUSPENSE;
import static com.finapp.accounting.testing.SafeguardingBooks.WALLET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.accounting.testing.SafeguardingBooks;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code SAFEGUARDING_POSITION} (`P14-TSK-019`; ADR-0118 decision 4, ADR-0096 section 6; {@code INV-ACC-04}, {@code INV-RAIL-04}): the real definition over books holding each term. What the port's statements read against PostgreSQL is {@code SafeguardingReturnDatabaseTest}'s (owed, C5); a form drawn from this body, through the real machine, is {@code SafeguardingReturnTest}'s in {@code app}.
 */
@DisplayName("SAFEGUARDING_POSITION (P14-TSK-019)")
class SafeguardingPositionDefinitionTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            SafeguardingPositionDefinitionTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("a report reads only through its ports");
            });
    private static final YearMonth MONTH = YearMonth.of(2097, 6);
    private static final LocalDate DAY_ONE = MONTH.atDay(1);
    private static final LocalDate LAST_DAY = MONTH.atEndOfMonth();
    private static final LocalDate NEXT_MONTH = LAST_DAY.plusDays(1);
    private static final Instant LOCK = Instant.parse("2097-07-05T10:00:00Z");
    private static final String EUR = "EUR";

    private final AccountingPeriodId periodId = AccountingPeriodId.next(IDS);
    private final GlMappingVersionId pinned = GlMappingVersionId.next(IDS);
    private final SafeguardingBooks books = new SafeguardingBooks();

    private SafeguardingPositionDefinition definition() {
        books.journal.period(new TrialBalanceReads.Period(periodId, DAY_ONE, LAST_DAY, PeriodStatus.CLOSED, Optional.of(pinned)));
        books.journal.version(pinned, ReferenceGlMapping.version(MONTH));
        return new SafeguardingPositionDefinition(books.journal, books.journal.counterparties(), books);
    }

    private ReportInputs inputs() {
        return new ReportInputs(periodId, MONTH, 1, RunBasis.FINAL, Optional.of(LOCK), Optional.of(pinned), Optional.of(1), LOCK, RunParameters.none());
    }

    private CanonicalJson.Obj body() {
        return definition().generate(SNAPSHOT, inputs());
    }

    private CanonicalJson.Obj figures(String currency) {
        return objects(body(), "currencies").stream().filter(element -> text(element, "currency").equals(currency)).findFirst()
                .orElseThrow(() -> new AssertionError("no element for " + currency));
    }

    private static long figure(CanonicalJson.Obj figures, String name) {
        return minor(figures, name).longValueExact();
    }

    @Test
    @DisplayName("the definition: SAFEGUARDING_POSITION, version 1, FINAL, no rate set, not waiting for the close, and states all five facts")
    void theDefinition() {
        SafeguardingPositionDefinition definition = definition();
        assertThat(definition.code()).isEqualTo(ReportKind.SAFEGUARDING_POSITION.name());
        assertThat(definition.version()).isEqualTo(1);
        assertThat(definition.operational()).isFalse();
        assertThat(definition.takesValuationRateSet()).isFalse();
        assertThat(definition.issuedOnlyWhenClosed()).isFalse();
        assertThat(CanonicalJson.text(definition.statement().asValue())).as("nothing claims compliance").contains("no legal obligation is asserted").contains("Never a loan's status, lending's live capital proof");
    }

    @Test
    @DisplayName("lending_funded is subtracted: position = assets - (liabilities - lendingFunded), and the terms stand as lines of their own")
    void lendingFundedIsSubtracted() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 50_00)
                .accepted("disbursed", DAY_ONE, EUR, 2, 30_00).disbursed("disbursed", MONTH.atDay(2))
                .accepted("waiting", MONTH.atDay(3), EUR, 2, 7_00)
                .accepted("lost", DAY_ONE, EUR, 2, 5_00).disbursed("lost", DAY_ONE).writtenOff(MONTH.atDay(9), EUR, 2, 5_00, 25);

        CanonicalJson.Obj eur = figures(EUR);

        assertThat(figure(eur, "cashAtBank")).isEqualTo(150_00);
        assertThat(figure(eur, "assets")).isEqualTo(150_00);
        assertThat(figure(eur, "customerWallets")).isEqualTo(135_00);
        assertThat(figure(eur, "liabilities")).isEqualTo(135_00);
        assertThat(figure(eur, "principalOutstanding")).isEqualTo(30_00);
        assertThat(figure(eur, "principalCommitted")).isEqualTo(7_00);
        assertThat(text(eur, "principalCommittedLoans")).isEqualTo("1");
        assertThat(figure(eur, "principalWrittenOff")).isEqualTo(5_00);
        assertThat(figure(eur, "lendingFunded")).isEqualTo(42_00);
        assertThat(figure(eur, "customerFundedLiabilities")).isEqualTo(93_00);
        assertThat(figure(eur, "position")).isEqualTo(57_00);
        assertThat(figure(eur, "positionBeforeLendingFunded")).as("beside it, nothing subtracted: what the adjustment did stays visible").isEqualTo(15_00);
        assertThat(flag(eur, "shortfall")).isFalse();
    }

    @Test
    @DisplayName("owner item (h): a write-off moves principal from one term to another and no figure of the position, and the interest it also wrote off is not capital's")
    void aWriteOffMovesNoFigureOfThePosition() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 50_00).accepted("loan", DAY_ONE, EUR, 2, 30_00).disbursed("loan", DAY_ONE);
        CanonicalJson.Obj before = figures(EUR);

        books.writtenOff(MONTH.atDay(20), EUR, 2, 30_00, 4_00);
        CanonicalJson.Obj after = figures(EUR);

        assertThat(figure(before, "principalOutstanding")).isEqualTo(30_00);
        assertThat(figure(after, "principalOutstanding")).isZero();
        assertThat(figure(after, "principalWrittenOff")).as("the principal, never the whole expense").isEqualTo(30_00);
        assertThat(figure(after, "lendingFunded")).isEqualTo(figure(before, "lendingFunded"));
        assertThat(figure(after, "position")).isEqualTo(figure(before, "position"));
    }

    @Test
    @DisplayName("the disbursement's journal line and committed cross on one date: on the last day it is outstanding and not committed, dated the day after it is committed and not outstanding, never twice and never in neither")
    void aLoanCrossesOnOneDate() {
        books.accepted("on-the-day", DAY_ONE, EUR, 2, 10_00).disbursed("on-the-day", LAST_DAY);
        books.accepted("day-after", DAY_ONE, "GBP", 2, 20_00).disbursed("day-after", NEXT_MONTH);

        CanonicalJson.Obj eur = figures(EUR);
        assertThat(figure(eur, "principalOutstanding")).isEqualTo(10_00);
        assertThat(figure(eur, "principalCommitted")).isZero();
        assertThat(figure(eur, "lendingFunded")).isEqualTo(10_00);

        CanonicalJson.Obj gbp = figures("GBP");
        assertThat(figure(gbp, "principalOutstanding")).isZero();
        assertThat(figure(gbp, "principalCommitted")).isEqualTo(20_00);
        assertThat(figure(gbp, "lendingFunded")).isEqualTo(20_00);
    }

    @Test
    @DisplayName("as of the cutoff: a line on the last day counts, a line posted after it does not, whatever it is about, and a loan and a line alike and BNPL's principal with them")
    void theJournalIsReadByPostingDate() {
        books.post(LAST_DAY, EUR, 2, 11_00, LOAN_PRINCIPAL, WALLET);
        books.post(MONTH.atDay(2), EUR, 2, 3_00, LOAN_PRINCIPAL_DUE, LOAN_PRINCIPAL);
        books.post(DAY_ONE.minusMonths(1), EUR, 2, 13_00, BNPL_PRINCIPAL, MERCHANT);
        books.post(NEXT_MONTH, EUR, 2, 500_00, LOAN_PRINCIPAL, WALLET);

        CanonicalJson.Obj eur = figures(EUR);

        assertThat(figure(eur, "principalOutstanding")).as("loans not yet due, due, and BNPL: a reclassification moves nothing").isEqualTo(24_00);
        assertThat(figure(eur, "customerWallets")).as("the month after's line is not here").isEqualTo(11_00);
        assertThat(figure(eur, "merchantPayables")).isEqualTo(13_00);
    }

    @Test
    @DisplayName("INV-ACC-04: regenerating a locked month is byte-identical after everything that moves a live figure: a loan disbursed, one cancelled, one accepted, one written off, money moved")
    void aFinalRegeneratesIdenticalWhenTheBooksMoveOn() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).accepted("waiting", MONTH.atDay(5), EUR, 2, 30_00).accepted("failing", MONTH.atDay(6), EUR, 2, 9_00)
                .accepted("written", DAY_ONE, EUR, 2, 4_00).disbursed("written", DAY_ONE);
        SafeguardingPositionDefinition definition = definition();
        String issued = CanonicalJson.text(definition.generate(SNAPSHOT, inputs()));

        books.disbursed("waiting", NEXT_MONTH, 1_00);
        books.cancelled("failing", NEXT_MONTH);
        books.accepted("later", NEXT_MONTH, EUR, 2, 77_00);
        books.writtenOff(NEXT_MONTH, EUR, 2, 4_00, 0);
        books.deposit(NEXT_MONTH.plusDays(1), EUR, 2, 1_000_00);

        assertThat(CanonicalJson.text(definition.generate(SNAPSHOT, inputs()))).isEqualTo(issued);
        assertThat(figure(figures(EUR), "principalCommitted")).as("not vacuous: it counted what later moved").isEqualTo(39_00);
    }

    @Test
    @DisplayName("a reopened month's new revision: every line now dated in it counts, the position moves by exactly that, and committed does not move, because no fact behind it did")
    void aReopenedMonthsNewRevision() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).accepted("waiting", MONTH.atDay(5), EUR, 2, 30_00);
        CanonicalJson.Obj first = figures(EUR);

        // The month reopened: its adjustment dated in it. A later disbursement of the same loan changes nothing of the month.
        books.post(LAST_DAY, EUR, 2, 2_00, WALLET, "FEE_REVENUE/OPERATIONAL");
        books.disbursed("waiting", NEXT_MONTH.plusDays(4));
        CanonicalJson.Obj second = figures(EUR);

        assertThat(figure(second, "position") - figure(first, "position")).isEqualTo(2_00);
        assertThat(figure(second, "principalCommitted")).isEqualTo(figure(first, "principalCommitted")).isEqualTo(30_00);
    }

    @Test
    @DisplayName("question 2, built as ruled and pinned for the owner: an acceptance lifts the position by the principal with no cash and no liability behind it, and the disbursement takes exactly that away")
    void committedLiftsThePositionWithNothingBehindIt() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 50_00);
        long before = figure(figures(EUR), "position");
        assertThat(before).isEqualTo(50_00);

        books.accepted("loan", MONTH.atDay(5), EUR, 2, 30_00);
        CanonicalJson.Obj accepted = figures(EUR);
        assertThat(figure(accepted, "cashAtBank")).isEqualTo(150_00);
        assertThat(figure(accepted, "customerWallets")).isEqualTo(100_00);
        assertThat(figure(accepted, "position")).as("nothing moved in the books").isEqualTo(80_00);
        assertThat(figure(accepted, "positionBeforeLendingFunded")).as("the line that does not move").isEqualTo(50_00);

        books.disbursed("loan", MONTH.atDay(6));
        assertThat(figure(figures(EUR), "position")).isEqualTo(50_00);
    }

    @Test
    @DisplayName("question 2's reach, pinned for the owner: an accepted loan can read a real shortfall as a surplus")
    void committedCanMaskAShortfall() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        books.post(MONTH.atDay(2), EUR, 2, 20_00, CHARGEBACK, CASH);
        assertThat(figure(figures(EUR), "position")).isEqualTo(-20_00);

        books.accepted("loan", MONTH.atDay(5), EUR, 2, 30_00);
        CanonicalJson.Obj masked = figures(EUR);

        assertThat(figure(masked, "position")).isEqualTo(10_00);
        assertThat(flag(masked, "shortfall")).isFalse();
        assertThat(figure(masked, "positionBeforeLendingFunded")).as("the separate line tells it").isEqualTo(-20_00);
    }

    @Test
    @DisplayName("the second finding, pinned for the owner: the borrower withdraws, cash falls and the position does not, because outstanding is an upper bound on loan-funded money still in wallets")
    void thePositionDoesNotSeeLoanMoneyLeave() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 50_00).accepted("loan", DAY_ONE, EUR, 2, 30_00).disbursed("loan", DAY_ONE);
        assertThat(figure(figures(EUR), "position")).isEqualTo(50_00);

        books.withdraw(MONTH.atDay(4), EUR, 2, 30_00);
        CanonicalJson.Obj withdrawn = figures(EUR);

        assertThat(figure(withdrawn, "cashAtBank")).isEqualTo(120_00);
        assertThat(figure(withdrawn, "customerWallets")).isEqualTo(100_00);
        assertThat(figure(withdrawn, "position")).as("unmoved: capital, by double entry").isEqualTo(50_00);
        assertThat(figure(withdrawn, "positionBeforeLendingFunded")).as("cash over depositors' money").isEqualTo(20_00);
    }

    @Test
    @DisplayName("what the figure is, by double entry and pinned for the owner: capital plus results plus committed, less the assets the return leaves out")
    void thePositionIsCapitalResultsAndCommitted() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 50_00).accepted("loan", DAY_ONE, EUR, 2, 30_00).disbursed("loan", MONTH.atDay(2), 1_00)
                .accepted("waiting", MONTH.atDay(3), EUR, 2, 7_00).withdraw(MONTH.atDay(4), EUR, 2, 10_00);
        books.post(MONTH.atDay(5), EUR, 2, 2_00, SafeguardingBooks.LOAN_INTEREST_DUE, "LOAN_INTEREST_INCOME/OPERATIONAL");

        long capital = 50_00;
        long results = 1_00 + 2_00;
        long committed = 7_00;
        long leftOut = 2_00;

        assertThat(figure(figures(EUR), "position")).isEqualTo(capital + results + committed - leftOut);
    }

    @Test
    @DisplayName("a shortfall is a figure: negative, unfloored, flagged, per its own currency only")
    void aShortfallIsNegativeAndNeverFloored() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        books.post(MONTH.atDay(2), EUR, 2, 130_00, CHARGEBACK, CASH);
        books.deposit(DAY_ONE, "USD", 2, 5_00);

        CanonicalJson.Obj eur = figures(EUR);
        assertThat(figure(eur, "cashAtBank")).as("a negative asset stands").isEqualTo(-30_00);
        assertThat(figure(eur, "position")).isEqualTo(-130_00);
        assertThat(text(object(eur, "position"), "minor")).isEqualTo("-13000");
        assertThat(flag(eur, "shortfall")).isTrue();
        assertThat(figure(eur, "chargebackRecoverable")).as("the memo says why").isEqualTo(130_00);

        CanonicalJson.Obj usd = figures("USD");
        assertThat(figure(usd, "position")).as("a shortfall in one currency is never met by another").isZero();
        assertThat(flag(usd, "shortfall")).isFalse();
    }

    @Test
    @DisplayName("every clearing position in its place, payout clearing and a corridor's held as owed, because the cash has not left")
    void theClearingPositions() {
        books.post(DAY_ONE, EUR, 2, 10_00, SETTLEMENT, MERCHANT);
        books.post(DAY_ONE, EUR, 2, 20_00, INSTANT, WALLET);
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        books.post(MONTH.atDay(2), EUR, 2, 40_00, MERCHANT, PAYOUT);
        books.post(MONTH.atDay(2), EUR, 2, 5_00, WALLET, CREDIT_BALANCE);

        CanonicalJson.Obj eur = figures(EUR);

        assertThat(figure(eur, "clearingReceivables")).isEqualTo(30_00);
        assertThat(figure(eur, "clearingPayables")).isEqualTo(40_00);
        assertThat(figure(eur, "creditBalances")).isEqualTo(5_00);
        assertThat(figure(eur, "merchantPayables")).as("a figure against its normal side reads negative and is never moved").isEqualTo(-30_00);
        assertThat(figure(eur, "position")).isZero();
    }

    @Test
    @DisplayName("nothing nets between counterparties: one's debit balance is a receivable, another's credit balance a payable, and the two lines are gross while the position is the same")
    void nothingNetsBetweenCounterparties() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        books.journal.postFor("fx-sim-a", MONTH.atDay(2), SafeguardingBooks.noon(MONTH.atDay(2)), EUR, 2, 10_00, FX_PROVIDER, FX_POSITION);
        books.journal.postFor("fx-sim-b", MONTH.atDay(2), SafeguardingBooks.noon(MONTH.atDay(2)), EUR, 2, 10_00, FX_POSITION, FX_PROVIDER);
        books.journal.postFor("corridor-sim-a", MONTH.atDay(3), SafeguardingBooks.noon(MONTH.atDay(3)), EUR, 2, 4_00, WALLET, CORRIDOR);

        CanonicalJson.Obj body = body();
        CanonicalJson.Obj eur = objects(body, "currencies").get(0);

        assertThat(figure(eur, "clearingReceivables")).as("never a position of 0").isEqualTo(10_00);
        assertThat(figure(eur, "clearingPayables")).isEqualTo(14_00);
        assertThat(figure(eur, "position")).isEqualTo(figure(eur, "positionBeforeLendingFunded")).isZero();
        assertThat(objects(body, "rows").stream().filter(row -> text(row, "purpose").equals("FX_PROVIDER_CLEARING")).map(row -> text(row, "counterparty")).toList())
                .containsExactly("fx-sim-a", "fx-sim-b");
    }

    @Test
    @DisplayName("suspense: a net credit is held as owed, since it may be a customer's; a net debit is no negative liability hiding a shortfall, and stands beside the position")
    void suspenseOnItsOwnSide() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        books.post(MONTH.atDay(2), EUR, 2, 20_00, CASH, SUSPENSE);
        CanonicalJson.Obj credit = figures(EUR);
        assertThat(figure(credit, "suspenseHeld")).isEqualTo(20_00);
        assertThat(figure(credit, "suspenseNetDebit")).isZero();
        assertThat(figure(credit, "position")).as("the unattributed cash is no surplus").isZero();

        books.post(MONTH.atDay(3), EUR, 2, 50_00, SUSPENSE, CASH);
        CanonicalJson.Obj debit = figures(EUR);
        assertThat(figure(debit, "suspenseHeld")).isZero();
        assertThat(figure(debit, "suspenseNetDebit")).isEqualTo(30_00);
        assertThat(figure(debit, "position")).as("unexplained money gone is a shortfall").isEqualTo(-30_00);
    }

    @Test
    @DisplayName("an FX position is out and said: a conversion not yet covered is a true shortfall in its currency, a true surplus in the other, and never summed across them")
    void anUncoveredConversionIsATrueShortfall() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        books.post(MONTH.atDay(2), EUR, 2, 50_00, WALLET, FX_POSITION);
        books.post(MONTH.atDay(2), "USD", 2, 55_00, FX_POSITION, WALLET);

        assertThat(figure(figures(EUR), "position")).isEqualTo(50_00);
        CanonicalJson.Obj usd = figures("USD");
        assertThat(figure(usd, "position")).isEqualTo(-55_00);
        assertThat(figure(usd, "fxPosition")).isEqualTo(55_00);
        assertThat(flag(usd, "shortfall")).isTrue();
    }

    @Test
    @DisplayName("question 5: a currency with wallets and no lending carries every term, a true zero at its own scale, across scales and currencies, each at the scale its own amounts carry")
    void aCurrencyWithNoLendingHoldsTrueZeros() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).accepted("loan", DAY_ONE, EUR, 2, 5_00);
        books.deposit(DAY_ONE, "GBP", 2, 40_00);
        books.deposit(DAY_ONE, "JPY", 0, 900);
        books.deposit(DAY_ONE, "BHD", 3, 12_345);

        CanonicalJson.Obj body = body();
        assertThat(objects(body, "currencies")).extracting(element -> text(element, "currency")).containsExactly("BHD", "EUR", "GBP", "JPY");
        for (CanonicalJson.Obj element : objects(body, "currencies")) {
            for (String member : List.of("principalOutstanding", "principalWrittenOff", "clearingReceivables", "clearingPayables", "creditBalances", "suspenseHeld", "suspenseNetDebit", "chargebackRecoverable", "fxPosition")) {
                assertThat(minor(element, member)).as(text(element, "currency") + " " + member).isEqualTo(BigInteger.ZERO);
                assertThat(scale(element, member)).isEqualTo(Integer.parseInt(text(element, "scale")));
            }
        }
        assertThat(minor(figures("GBP"), "lendingFunded")).isEqualTo(BigInteger.ZERO);
        assertThat(scale(figures("GBP"), "lendingFunded")).isEqualTo(2);
        assertThat(scale(figures("JPY"), "position")).isEqualTo(0);
        assertThat(figure(figures("JPY"), "customerWallets")).isEqualTo(900);
        assertThat(scale(figures("BHD"), "position")).isEqualTo(3);
        assertThat(figure(figures("BHD"), "customerWallets")).isEqualTo(12_345);
        assertThat(figure(figures(EUR), "lendingFunded")).isEqualTo(5_00);
    }

    @Test
    @DisplayName("a currency holding only a commitment has an element, at the scale the commitment carries; a currency nothing touched has none")
    void aCurrencyOnlyTheCommitmentTouched() {
        books.accepted("loan", DAY_ONE, "JPY", 0, 7_000);

        CanonicalJson.Obj body = body();

        assertThat(objects(body, "currencies")).extracting(element -> text(element, "currency")).containsExactly("JPY");
        assertThat(figure(figures("JPY"), "position")).isEqualTo(7_000);
    }

    @Test
    @DisplayName("question 5: an unreadable read is no zero - either of the port's reads failing means no body at all, whatever the journal holds, and the same books read again answer")
    void anUnreadableProofIsNoZero() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        SafeguardingPositionDefinition definition = definition();

        books.committedUnreadable = true;
        assertThatThrownBy(() -> definition.generate(SNAPSHOT, inputs())).isInstanceOf(AccountingStorageException.class);
        books.committedUnreadable = false;

        books.writtenOffUnreadable = true;
        assertThatThrownBy(() -> definition.generate(SNAPSHOT, inputs())).isInstanceOf(AccountingStorageException.class);
        books.writtenOffUnreadable = false;

        assertThat(figure(objects(definition.generate(SNAPSHOT, inputs()), "currencies").get(0), "principalWrittenOff")).as("a true zero, from a read that succeeded").isZero();
    }

    @Test
    @DisplayName("a FINAL run whose version is not its period's pin is refused, as every journal-derived definition's")
    void aPinMismatchIsRefused() {
        SafeguardingPositionDefinition definition = definition();
        ReportInputs other = new ReportInputs(periodId, MONTH, 1, RunBasis.FINAL, Optional.of(LOCK), Optional.of(GlMappingVersionId.next(IDS)), Optional.of(1), LOCK, RunParameters.none());

        assertThatThrownBy(() -> definition.generate(SNAPSHOT, other)).isInstanceOf(TrialBalanceDefinition.PinMismatch.class);
    }

    @Test
    @DisplayName("a PROVISIONAL run is cut at its claim on every read: an entry, a loan's facts and a write-off recorded after it have not happened yet")
    void aProvisionalRunIsCutAtItsClaim() {
        LocalDate before = MONTH.atDay(10);
        LocalDate after = MONTH.atDay(12);
        books.deposit(before, EUR, 2, 100_00).accepted("waiting", before, EUR, 2, 30_00).accepted("written", before, EUR, 2, 4_00).disbursed("written", before)
                .disbursed("waiting", after).writtenOff(after, EUR, 2, 4_00, 0).accepted("later", after, EUR, 2, 77_00);
        books.journal.period(new TrialBalanceReads.Period(periodId, DAY_ONE, LAST_DAY, PeriodStatus.OPEN, Optional.empty()));
        SafeguardingPositionDefinition definition = new SafeguardingPositionDefinition(books.journal, books.journal.counterparties(), books);
        Instant cut = SafeguardingBooks.noon(MONTH.atDay(11));
        ReportInputs provisional = new ReportInputs(periodId, MONTH, 1, RunBasis.PROVISIONAL, Optional.empty(), Optional.empty(), Optional.empty(), cut, RunParameters.none());

        CanonicalJson.Obj eur = objects(definition.generate(SNAPSHOT, provisional), "currencies").get(0);

        assertThat(figure(eur, "principalCommitted")).isEqualTo(30_00);
        assertThat(figure(eur, "principalOutstanding")).isEqualTo(4_00);
        assertThat(figure(eur, "principalWrittenOff")).isZero();
        assertThat(figure(eur, "customerWallets")).isEqualTo(104_00);
    }

    @Test
    @DisplayName("each row names its GL account under the run's pinned version, and a new mapping moves no figure; a run with no version names none")
    void aMappingDecidesOnlyTheDrillDownKey() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        CanonicalJson.Obj body = body();
        CanonicalJson.Obj cash = objects(body, "rows").stream().filter(row -> text(row, "purpose").equals("CASH_AT_BANK")).findFirst().orElseThrow();
        assertThat(text(cash, "glAccount")).isEqualTo("1000");
        assertThat(text(cash, "treatment")).isEqualTo("CASH");
        assertThat(isNull(cash, "counterparty")).isTrue();
        assertThat(minor(cash, "closing").longValueExact()).isEqualTo(100_00);

        books.journal.period(new TrialBalanceReads.Period(periodId, DAY_ONE, LAST_DAY, PeriodStatus.OPEN, Optional.empty()));
        ReportInputs noVersion = new ReportInputs(periodId, MONTH, 1, RunBasis.PROVISIONAL, Optional.empty(), Optional.empty(), Optional.empty(), LOCK, RunParameters.none());
        CanonicalJson.Obj unmapped = new SafeguardingPositionDefinition(books.journal, books.journal.counterparties(), books).generate(SNAPSHOT, noVersion);
        CanonicalJson.Obj again = objects(unmapped, "rows").stream().filter(row -> text(row, "purpose").equals("CASH_AT_BANK")).findFirst().orElseThrow();
        assertThat(isNull(again, "glAccount")).isTrue();
        assertThat(figure(objects(unmapped, "currencies").get(0), "position")).isEqualTo(figure(objects(body, "currencies").get(0), "position"));
    }

    @Test
    @DisplayName("question 7: every figure has a member of its own name saying what stands behind it; a journal figure names its route under ACCOUNTING_READ, and committed names none, says what it rests on, and says who can open the one door that states it")
    void everyFigureSaysWhatStandsBehindIt() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        CanonicalJson.Obj body = body();
        CanonicalJson.Obj drillDown = object(body, "drillDown");
        Set<String> amounts = new TreeSet<>();
        objects(body, "currencies").get(0).members().forEach((member, value) -> {
            if (value instanceof CanonicalJson.Obj) {
                amounts.add(member);
            }
        });
        assertThat(amounts).hasSize(19);
        assertThat(drillDown.members().keySet()).containsAll(amounts);

        for (String journal : List.of("cashAtBank", "customerWallets", "merchantPayables", "principalOutstanding", "principalWrittenOff", "fxPosition")) {
            assertThat(text(drillDown, journal)).as(journal).contains("GET /v1/operator/accounting/report-runs/{runId}/lines").contains("ACCOUNTING_READ");
        }
        String committed = text(drillDown, "principalCommitted");
        assertThat(committed).startsWith("No journal line stands behind this figure").contains("lending's frozen rows").contains("No route under ACCOUNTING_READ lists those loans")
                .contains("LENDING_INVESTIGATE, which ACCOUNTING_READ does not carry").doesNotContain("/report-runs/");
    }

    @Test
    @DisplayName("the body says its own standing and its mapping, and no person, loan or party is in it")
    void theBodySaysItsOwnStanding() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).accepted("the-secret-loan", DAY_ONE, EUR, 2, 5_00);
        CanonicalJson.Obj body = body();

        assertThat(text(body, "standing")).isEqualTo("REFERENCE");
        assertThat(objects(body, "mapping")).hasSameSizeAs(SafeguardingPositionDefinition.DISPOSITIONS);
        assertThat(CanonicalJson.text(body)).doesNotContain("the-secret-loan");
        assertThat(text(object(body, "range"), "through")).isEqualTo(LAST_DAY.toString());
        assertThat(text(object(body, "periodBounds"), "through")).isEqualTo(LAST_DAY.toString());
    }
}
