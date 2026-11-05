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
 * {@code SAFEGUARDING_POSITION} (`P14-TSK-019`, `P14-TSK-023`; ADR-0118 decision 4, ADR-0096 section 6 as the owner corrected it; {@code INV-ACC-04}, {@code INV-RAIL-04}): the real definition over books holding each term. What the port's statements read against PostgreSQL is {@code SafeguardingReturnDatabaseTest}'s (owed, C5); a form drawn from this body, through the real machine, is {@code SafeguardingReturnTest}'s in {@code app}.
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
    @DisplayName("principal unrepaid is subtracted: position = assets - (liabilities - principalUnrepaid), outstanding and written off with committed a line beside them, and the terms stand as lines of their own")
    void principalUnrepaidIsSubtracted() {
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
        assertThat(figure(eur, "principalUnrepaid")).as("the 7 committed are not in it").isEqualTo(35_00);
        assertThat(figure(eur, "customerFundedLiabilities")).isEqualTo(100_00);
        assertThat(figure(eur, "position")).isEqualTo(50_00);
        assertThat(figure(eur, "positionBeforePrincipalUnrepaid")).as("the figure that warns, nothing subtracted: what the adjustment did stays visible").isEqualTo(15_00);
        assertThat(flag(eur, "positionNegative")).isFalse();
        assertThat(flag(eur, "positionBeforePrincipalUnrepaidNegative")).isFalse();
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
        assertThat(figure(after, "principalUnrepaid")).as("the written-off principal stays in the subtraction").isEqualTo(figure(before, "principalUnrepaid")).isEqualTo(30_00);
        assertThat(figure(after, "position")).isEqualTo(figure(before, "position"));
    }

    @Test
    @DisplayName("given the poster's one date, a loan crosses once in a FINAL: on the last day it is outstanding and not committed, dated the day after it is committed and not outstanding, never twice and never in neither")
    void aLoanCrossesOnOneDate() {
        books.accepted("on-the-day", DAY_ONE, EUR, 2, 10_00).disbursed("on-the-day", LAST_DAY);
        books.accepted("day-after", DAY_ONE, "GBP", 2, 20_00).disbursed("day-after", NEXT_MONTH);

        CanonicalJson.Obj eur = figures(EUR);
        assertThat(figure(eur, "principalOutstanding")).isEqualTo(10_00);
        assertThat(figure(eur, "principalCommitted")).isZero();
        assertThat(figure(eur, "principalUnrepaid")).isEqualTo(10_00);

        CanonicalJson.Obj gbp = figures("GBP");
        assertThat(figure(gbp, "principalOutstanding")).isZero();
        assertThat(figure(gbp, "principalCommitted")).isEqualTo(20_00);
        assertThat(figure(gbp, "principalUnrepaid")).as("committed is no part of it until it crosses").isZero();
    }

    @Test
    @DisplayName("what that one date holds up, shown by a poster the real one is not: its line and its value date a day apart read a loan in neither term, or in both. Against the real poster: SafeguardingCommittedDatabaseTest#theRealPosterDatesItsLineTheValueDate, owed")
    void whatThePostersOneDateHoldsUp() {
        books.deposit(DAY_ONE, EUR, 2, 1_00).accepted("in-neither", DAY_ONE, EUR, 2, 10_00).disbursedOnTwoDates("in-neither", LAST_DAY, NEXT_MONTH);
        books.accepted("in-both", DAY_ONE, "GBP", 2, 20_00).disbursedOnTwoDates("in-both", NEXT_MONTH, LAST_DAY);

        CanonicalJson.Obj eur = figures(EUR);
        assertThat(figure(eur, "principalOutstanding") + figure(eur, "principalCommitted")).as("in neither").isZero();
        CanonicalJson.Obj gbp = figures("GBP");
        assertThat(figure(gbp, "principalOutstanding") + figure(gbp, "principalCommitted")).as("in both").isEqualTo(40_00);
    }

    @Test
    @DisplayName("M-6, pinned: a PROVISIONAL cut between a disbursement's two statements counts the loan in both terms: \"never twice\" is a FINAL's, and a PROVISIONAL run is labelled and never issued")
    void aProvisionalCutInsideADisbursementCountsItTwice() {
        LocalDate day = MONTH.atDay(10);
        Instant cut = SafeguardingBooks.noon(day).plusSeconds(1);
        books.accepted("loan", MONTH.atDay(9), EUR, 2, 30_00).disbursedDecidedAt("loan", day, cut.plusSeconds(1));
        books.journal.period(new TrialBalanceReads.Period(periodId, DAY_ONE, LAST_DAY, PeriodStatus.OPEN, Optional.empty()));
        SafeguardingPositionDefinition definition = new SafeguardingPositionDefinition(books.journal, books.journal.counterparties(), books);
        ReportInputs provisional = new ReportInputs(periodId, MONTH, 1, RunBasis.PROVISIONAL, Optional.empty(), Optional.empty(), Optional.empty(), cut, RunParameters.none());

        CanonicalJson.Obj eur = objects(definition.generate(SNAPSHOT, provisional), "currencies").get(0);

        assertThat(figure(eur, "principalOutstanding")).isEqualTo(30_00);
        assertThat(figure(eur, "principalCommitted")).isEqualTo(30_00);
        assertThat(CanonicalJson.text(definition.statement().asValue())).contains("a disbursement straddling the cut can read in both terms or neither");
    }

    @Test
    @DisplayName("I-1: a wallet in debit is no cover for another customer's money: A at +100, B at -30, cash 70 is short by 30, the two liability figures gross, the debt beside them, the trial balance's net footing to both")
    void aWalletInDebtIsNoCover() {
        books.depositFor("A", DAY_ONE, EUR, 2, 100_00).withdrawFor("B", MONTH.atDay(2), EUR, 2, 30_00);

        CanonicalJson.Obj eur = figures(EUR);

        assertThat(figure(eur, "cashAtBank")).isEqualTo(70_00);
        assertThat(figure(eur, "customerWallets")).as("A is owed 100").isEqualTo(100_00);
        assertThat(figure(eur, "customerWalletDebt")).as("B owes 30").isEqualTo(30_00);
        assertThat(text(eur, "customerWalletDebtAccounts")).isEqualTo("1");
        assertThat(figure(eur, "customerWalletsNet")).as("what the trial balance says").isEqualTo(70_00);
        assertThat(figure(eur, "customerWallets") - figure(eur, "customerWalletDebt")).isEqualTo(figure(eur, "customerWalletsNet"));
        assertThat(figure(eur, "position")).isEqualTo(-30_00);
        assertThat(figure(eur, "positionBeforePrincipalUnrepaid")).as("the figure that warns does not count it either").isEqualTo(-30_00);
        assertThat(flag(eur, "positionNegative")).isTrue();
    }

    @Test
    @DisplayName("I-1: the same of merchant debt and of a credit balance in debit, each account its own, across holders: nothing another holder owes covers what this one is owed")
    void debtInEveryPurposeReadPerAccount() {
        books.postFor("M1", DAY_ONE, EUR, 2, 50_00, CASH, MERCHANT).postFor("M2", MONTH.atDay(2), EUR, 2, 20_00, MERCHANT, CASH);
        books.postFor("L1", DAY_ONE, EUR, 2, 9_00, CASH, CREDIT_BALANCE).postFor("L2", MONTH.atDay(2), EUR, 2, 4_00, CREDIT_BALANCE, CASH);
        books.postFor("B1", DAY_ONE, EUR, 2, 3_00, CASH, SafeguardingBooks.BNPL_CREDIT_BALANCE).postFor("B2", MONTH.atDay(2), EUR, 2, 1_00, SafeguardingBooks.BNPL_CREDIT_BALANCE, CASH);

        CanonicalJson.Obj eur = figures(EUR);

        assertThat(figure(eur, "merchantPayables")).isEqualTo(50_00);
        assertThat(figure(eur, "merchantDebt")).isEqualTo(20_00);
        assertThat(figure(eur, "merchantPayablesNet")).isEqualTo(30_00);
        assertThat(figure(eur, "creditBalances")).as("loans' and BNPL's, gross").isEqualTo(12_00);
        assertThat(figure(eur, "creditBalanceDebt")).isEqualTo(5_00);
        assertThat(figure(eur, "creditBalancesNet")).isEqualTo(7_00);
        assertThat(text(eur, "merchantDebtAccounts")).isEqualTo("1");
        assertThat(text(eur, "creditBalanceDebtAccounts")).isEqualTo("2");
        assertThat(figure(eur, "cashAtBank")).isEqualTo(37_00);
        assertThat(figure(eur, "liabilities")).isEqualTo(62_00);
        assertThat(figure(eur, "position")).isEqualTo(-25_00);
    }

    @Test
    @DisplayName("one holder's own line and its reversal is one balance, not a debt and a credit; and a holder repaying what it owed owes nothing")
    void aBalanceIsPerAccountNotPerLine() {
        books.depositFor("A", DAY_ONE, EUR, 2, 40_00).withdrawFor("A", MONTH.atDay(2), EUR, 2, 25_00);
        books.withdrawFor("B", DAY_ONE, EUR, 2, 5_00).depositFor("B", MONTH.atDay(2), EUR, 2, 5_00);

        CanonicalJson.Obj eur = figures(EUR);

        assertThat(figure(eur, "customerWallets")).isEqualTo(15_00);
        assertThat(figure(eur, "customerWalletDebt")).isZero();
        assertThat(text(eur, "customerWalletDebtAccounts")).isEqualTo("0");
    }

    @Test
    @DisplayName("I-2: each flag says its own figure: a month short before the adjustment and over after it, loan money having left, reads positionNegative false and positionBeforePrincipalUnrepaidNegative true, and no flag is called shortfall")
    void eachFlagSaysItsOwnFigure() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 30_00).accepted("loan", DAY_ONE, EUR, 2, 30_00).disbursed("loan", DAY_ONE).withdraw(MONTH.atDay(2), EUR, 2, 30_00);
        books.post(MONTH.atDay(3), EUR, 2, 20_00, CHARGEBACK, CASH);

        CanonicalJson.Obj eur = figures(EUR);

        assertThat(figure(eur, "position")).isEqualTo(10_00);
        assertThat(flag(eur, "positionNegative")).isFalse();
        assertThat(figure(eur, "positionBeforePrincipalUnrepaid")).isEqualTo(-20_00);
        assertThat(flag(eur, "positionBeforePrincipalUnrepaidNegative")).isTrue();
        assertThat(eur.members()).doesNotContainKey("shortfall");
    }

    @Test
    @DisplayName("I-2: customerFundedLiabilities can read negative, and the run says so: liabilities 100, principal unrepaid 130")
    void customerFundedLiabilitiesCanReadNegative() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 130_00).accepted("loan", DAY_ONE, EUR, 2, 130_00).disbursed("loan", DAY_ONE).withdraw(MONTH.atDay(2), EUR, 2, 130_00);

        CanonicalJson.Obj body = body();
        CanonicalJson.Obj eur = objects(body, "currencies").get(0);

        assertThat(figure(eur, "liabilities")).isEqualTo(100_00);
        assertThat(figure(eur, "principalUnrepaid")).isEqualTo(130_00);
        assertThat(figure(eur, "customerFundedLiabilities")).isEqualTo(-30_00);
        assertThat(text(object(body, "drillDown"), "customerFundedLiabilities")).contains("It can read negative");
    }

    @Test
    @DisplayName("as of the cutoff: a line on the last day counts and a line posted after it does not; principal not yet due, due, and BNPL's alike, and a billing between the first two moves nothing")
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
    @DisplayName("the owner's ruling, P14-TSK-019's (a): an acceptance moves no figure of the position: no cash and no liability stand behind it, so it is a line and nothing else, and it is committed that crosses at the disbursement, which leaves the position where it was and lowers the figure that warns by the principal")
    void anAcceptanceMovesNoFigureOfThePosition() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 50_00);
        CanonicalJson.Obj before = figures(EUR);
        assertThat(figure(before, "position")).isEqualTo(50_00);

        books.accepted("loan", MONTH.atDay(5), EUR, 2, 30_00);
        CanonicalJson.Obj accepted = figures(EUR);
        assertThat(figure(accepted, "principalCommitted")).as("disclosed").isEqualTo(30_00);
        assertThat(text(accepted, "principalCommittedLoans")).isEqualTo("1");
        for (String unmoved : List.of("cashAtBank", "assets", "customerWallets", "liabilities", "principalOutstanding", "principalWrittenOff", "principalUnrepaid", "customerFundedLiabilities", "position", "positionBeforePrincipalUnrepaid")) {
            assertThat(figure(accepted, unmoved)).as(unmoved).isEqualTo(figure(before, unmoved));
        }

        books.disbursed("loan", MONTH.atDay(6));
        CanonicalJson.Obj disbursed = figures(EUR);
        assertThat(figure(disbursed, "principalCommitted")).isZero();
        assertThat(figure(disbursed, "principalUnrepaid")).as("now it has created wallet money").isEqualTo(30_00);
        assertThat(figure(disbursed, "customerWallets")).isEqualTo(130_00);
        assertThat(figure(disbursed, "customerFundedLiabilities")).isEqualTo(100_00);
        assertThat(figure(disbursed, "position")).as("a disbursement does not move it").isEqualTo(50_00);
        assertThat(figure(disbursed, "positionBeforePrincipalUnrepaid")).as("the figure that warns falls by the principal: wallet money created, no cash entering").isEqualTo(20_00);
    }

    @Test
    @DisplayName("the worked month: capital 30, which admits the acceptance (INV-LND-13), deposits 100, a loss of 50 and 30 accepted reads short by 20 on both positions, where it read 10 over until the owner's ruling; disbursed, -20 and -50")
    void anAcceptanceCannotMaskAShortfall() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 30_00);
        books.post(MONTH.atDay(2), EUR, 2, 50_00, CHARGEBACK, CASH);
        assertThat(figure(figures(EUR), "position")).isEqualTo(-20_00);

        books.accepted("loan", MONTH.atDay(5), EUR, 2, 30_00);
        CanonicalJson.Obj accepted = figures(EUR);

        assertThat(figure(accepted, "position")).isEqualTo(-20_00);
        assertThat(flag(accepted, "positionNegative")).isTrue();
        assertThat(figure(accepted, "positionBeforePrincipalUnrepaid")).isEqualTo(-20_00);
        assertThat(flag(accepted, "positionBeforePrincipalUnrepaidNegative")).isTrue();
        assertThat(figure(accepted, "principalCommitted")).isEqualTo(30_00);

        books.disbursed("loan", MONTH.atDay(6));
        CanonicalJson.Obj disbursed = figures(EUR);

        assertThat(figure(disbursed, "position")).isEqualTo(-20_00);
        assertThat(figure(disbursed, "positionBeforePrincipalUnrepaid")).isEqualTo(-50_00);
    }

    @Test
    @DisplayName("what this ruling did not cure, stated precisely: a shortfall can still be masked on position, by loan money that has left. It reads zero or above in a month short on the figure that warns whenever principal unrepaid is at least that shortfall, and below it reads negative")
    void loanMoneyThatLeftCanStillMaskAShortfall() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).capital(DAY_ONE, EUR, 2, 30_00).accepted("loan", DAY_ONE, EUR, 2, 30_00).disbursed("loan", DAY_ONE).withdraw(MONTH.atDay(2), EUR, 2, 30_00);
        books.post(MONTH.atDay(3), EUR, 2, 20_00, CHARGEBACK, CASH);
        CanonicalJson.Obj masked = figures(EUR);
        assertThat(figure(masked, "cashAtBank")).isEqualTo(80_00);
        assertThat(figure(masked, "customerWallets")).isEqualTo(100_00);
        assertThat(figure(masked, "positionBeforePrincipalUnrepaid")).as("depositors are owed 100 against cash of 80").isEqualTo(-20_00);
        assertThat(figure(masked, "principalUnrepaid")).isEqualTo(30_00);
        assertThat(figure(masked, "position")).as("over, in a month that is short").isEqualTo(10_00);

        books.post(MONTH.atDay(4), EUR, 2, 10_00, CHARGEBACK, CASH);
        assertThat(figure(figures(EUR), "position")).as("the shortfall exactly the term: reads zero, still no warning").isZero();
        assertThat(flag(figures(EUR), "positionNegative")).isFalse();
        assertThat(figure(figures(EUR), "positionBeforePrincipalUnrepaid")).isEqualTo(-30_00);

        books.post(MONTH.atDay(5), EUR, 2, 1, CHARGEBACK, CASH);
        assertThat(figure(figures(EUR), "position")).as("past the term it reads short").isEqualTo(-1);
        assertThat(flag(figures(EUR), "positionNegative")).isTrue();
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
        assertThat(figure(withdrawn, "positionBeforePrincipalUnrepaid")).as("cash over depositors' money").isEqualTo(20_00);
    }

    @Test
    @DisplayName("what each figure is, by double entry: position is capital plus results plus written off, and the figure that warns capital plus results less outstanding, each less the assets the return leaves out, a holder's debt among them; committed is in neither")
    void thePositionIsCapitalResultsAndWrittenOff() {
        books.deposit(DAY_ONE, EUR, 2, 200_00).capital(DAY_ONE, EUR, 2, 100_00).accepted("lost", DAY_ONE, EUR, 2, 40_00).disbursed("lost", MONTH.atDay(2), 2_00).withdraw(MONTH.atDay(3), EUR, 2, 38_00);
        books.post(MONTH.atDay(4), EUR, 2, 3_00, SafeguardingBooks.LOAN_INTEREST_DUE, SafeguardingBooks.LOAN_INTEREST_INCOME);
        books.writtenOff(MONTH.atDay(5), EUR, 2, 40_00, 3_00);

        long capital = 100_00;
        long results = 2_00 + 3_00 - 43_00;
        assertThat(figure(figures(EUR), "position")).as("the worked example: 300 - 38 cash less 200 owed, plus 40 written off").isEqualTo(102_00).isEqualTo(capital + results + 40_00);
        assertThat(figure(figures(EUR), "positionBeforePrincipalUnrepaid")).as("nothing outstanding: 262 cash less 200 owed").isEqualTo(62_00).isEqualTo(capital + results);

        books.accepted("waiting", MONTH.atDay(6), EUR, 2, 7_00);
        books.accepted("out", MONTH.atDay(6), EUR, 2, 11_00).disbursed("out", MONTH.atDay(6));
        books.post(MONTH.atDay(7), EUR, 2, 2_00, SafeguardingBooks.LOAN_INTEREST_DUE, SafeguardingBooks.LOAN_INTEREST_INCOME);
        books.withdrawFor("in-debit", MONTH.atDay(8), EUR, 2, 5_00);
        long resultsNow = results + 2_00;
        long leftOut = 2_00 + 5_00;
        CanonicalJson.Obj eur = figures(EUR);

        assertThat(figure(eur, "principalCommitted")).isEqualTo(7_00);
        assertThat(figure(eur, "position")).isEqualTo(capital + resultsNow + 40_00 - leftOut);
        assertThat(figure(eur, "positionBeforePrincipalUnrepaid")).isEqualTo(capital + resultsNow - 11_00 - leftOut);
        assertThat(figure(eur, "position") - figure(eur, "positionBeforePrincipalUnrepaid")).as("the one is the other plus the term").isEqualTo(figure(eur, "principalUnrepaid")).isEqualTo(11_00 + 40_00);
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
        assertThat(flag(eur, "positionNegative")).isTrue();
        assertThat(flag(eur, "positionBeforePrincipalUnrepaidNegative")).isTrue();
        assertThat(figure(eur, "chargebackRecoverable")).as("the memo says why").isEqualTo(130_00);

        CanonicalJson.Obj usd = figures("USD");
        assertThat(figure(usd, "position")).as("a shortfall in one currency is never met by another").isZero();
        assertThat(flag(usd, "positionNegative")).isFalse();
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
        assertThat(figure(eur, "merchantPayables")).as("the one account, in debit: nothing is owed").isZero();
        assertThat(figure(eur, "merchantDebt")).as("and that merchant owes, out of the position").isEqualTo(30_00);
        assertThat(figure(eur, "merchantPayablesNet")).isEqualTo(-30_00);
        assertThat(figure(eur, "position")).as("the debt was cover until I-1").isEqualTo(-30_00);
    }

    @Test
    @DisplayName("nothing nets between counterparties: one's debit balance is a receivable, another's credit balance a payable, and the two lines are gross while the position is the same")
    void nothingNetsBetweenCounterparties() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        books.postCounterparty("fx-sim-a", MONTH.atDay(2), EUR, 2, 10_00, FX_PROVIDER, FX_POSITION);
        books.postCounterparty("fx-sim-b", MONTH.atDay(2), EUR, 2, 10_00, FX_POSITION, FX_PROVIDER);
        books.postCounterparty("corridor-sim-a", MONTH.atDay(3), EUR, 2, 4_00, WALLET, CORRIDOR);

        CanonicalJson.Obj body = body();
        CanonicalJson.Obj eur = objects(body, "currencies").get(0);

        assertThat(figure(eur, "clearingReceivables")).as("never a position of 0").isEqualTo(10_00);
        assertThat(figure(eur, "clearingPayables")).isEqualTo(14_00);
        assertThat(figure(eur, "position")).isEqualTo(figure(eur, "positionBeforePrincipalUnrepaid")).isZero();
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
        assertThat(flag(usd, "positionNegative")).isTrue();
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
            for (String member : List.of("principalOutstanding", "principalWrittenOff", "clearingReceivables", "clearingPayables", "creditBalances", "creditBalancesNet", "creditBalanceDebt", "customerWalletDebt", "merchantPayables", "merchantPayablesNet", "merchantDebt", "suspenseHeld", "suspenseNetDebit", "chargebackRecoverable", "fxPosition")) {
                assertThat(minor(element, member)).as(text(element, "currency") + " " + member).isEqualTo(BigInteger.ZERO);
                assertThat(scale(element, member)).isEqualTo(Integer.parseInt(text(element, "scale")));
            }
        }
        assertThat(minor(figures("GBP"), "principalUnrepaid")).isEqualTo(BigInteger.ZERO);
        assertThat(scale(figures("GBP"), "principalUnrepaid")).isEqualTo(2);
        assertThat(scale(figures("JPY"), "position")).isEqualTo(0);
        assertThat(figure(figures("JPY"), "customerWallets")).isEqualTo(900);
        assertThat(scale(figures("BHD"), "position")).isEqualTo(3);
        assertThat(figure(figures("BHD"), "customerWallets")).isEqualTo(12_345);
        assertThat(figure(figures(EUR), "principalUnrepaid")).as("accepted, not disbursed").isZero();
        assertThat(figure(figures(EUR), "principalCommitted")).isEqualTo(5_00);
    }

    @Test
    @DisplayName("a currency holding only a commitment has an element, at the scale the commitment carries; a currency nothing touched has none")
    void aCurrencyOnlyTheCommitmentTouched() {
        books.accepted("loan", DAY_ONE, "JPY", 0, 7_000);

        CanonicalJson.Obj body = body();

        assertThat(objects(body, "currencies")).extracting(element -> text(element, "currency")).containsExactly("JPY");
        assertThat(figure(figures("JPY"), "principalCommitted")).isEqualTo(7_000);
        assertThat(figure(figures("JPY"), "position")).as("disclosed, and moving nothing").isZero();
        assertThat(figure(figures("JPY"), "positionBeforePrincipalUnrepaid")).isZero();
    }

    @Test
    @DisplayName("question 5: an unreadable read is no zero - any of the port's three reads failing means no body at all, whatever the journal holds, and the same books read again answer")
    void anUnreadableProofIsNoZero() {
        books.deposit(DAY_ONE, EUR, 2, 100_00);
        SafeguardingPositionDefinition definition = definition();

        books.committedUnreadable = true;
        assertThatThrownBy(() -> definition.generate(SNAPSHOT, inputs())).isInstanceOf(AccountingStorageException.class);
        books.committedUnreadable = false;

        books.writtenOffUnreadable = true;
        assertThatThrownBy(() -> definition.generate(SNAPSHOT, inputs())).isInstanceOf(AccountingStorageException.class);
        books.writtenOffUnreadable = false;

        books.heldUnreadable = true;
        assertThatThrownBy(() -> definition.generate(SNAPSHOT, inputs())).isInstanceOf(AccountingStorageException.class);
        books.heldUnreadable = false;

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
        assertThat(amounts).hasSize(25);
        assertThat(drillDown.members().keySet()).containsAll(amounts);

        for (String journal : List.of("cashAtBank", "customerWallets", "customerWalletDebt", "merchantPayables", "merchantDebt", "creditBalanceDebt", "principalOutstanding", "principalWrittenOff", "fxPosition")) {
            assertThat(text(drillDown, journal)).as(journal).contains("GET /v1/operator/accounting/report-runs/{runId}/lines").contains("ACCOUNTING_READ");
        }
        String committed = text(drillDown, "principalCommitted");
        assertThat(committed).startsWith("No journal line stands behind this figure").contains("Disclosed and not subtracted").contains("it has created no wallet money").contains("lending's frozen rows").contains("No route under ACCOUNTING_READ lists those loans")
                .contains("LENDING_INVESTIGATE, which ACCOUNTING_READ does not carry").doesNotContain("/report-runs/");
        assertThat(text(drillDown, "position")).as("I-2: a positive figure proves nothing, and the run says so").contains("A disclosure, not the figure that warns").contains("A positive figure proves no cover").contains("it can read over in a month positionBeforePrincipalUnrepaid reads short").contains("Read it beside positionBeforePrincipalUnrepaid");
        assertThat(text(drillDown, "positionBeforePrincipalUnrepaid")).contains("The figure that warns").contains("short whatever position reads");
        assertThat(text(drillDown, "principalUnrepaid")).contains("principalCommitted not among them").contains("A disclosure");
    }

    @Test
    @DisplayName("P14-TSK-023: the words the run writes say the new thing: the statement, the drill-down and the mapping name the term and the figure that warns, and no word says the old thing: no member of the old name, no acceptance lifting a figure, no flag or figure named for a term that has gone")
    void theOldTermsAndWordsAreGone() {
        books.deposit(DAY_ONE, EUR, 2, 100_00).accepted("loan", DAY_ONE, EUR, 2, 5_00);
        CanonicalJson.Obj body = body();
        String words = CanonicalJson.text(body);

        assertThat(words).doesNotContain("\"lendingFunded\"").doesNotContain("positionBeforeLendingFunded").doesNotContain("lending_funded").doesNotContain("rises with an accepted loan").doesNotContain("committed plus written off");
        assertThat(text(object(body, "statement"), "rule")).contains("is the figure that warns").contains("principalUnrepaid is principal outstanding plus written off").contains("a disclosure").contains("an acceptance moves neither position").contains("subtracted from nothing");
        assertThat(objects(body, "mapping").stream().filter(row -> text(row, "treatment").equals(SafeguardingPositionDefinition.Treatment.PRINCIPAL.name())).map(row -> text(row, "reason")).toList())
                .hasSize(4).allSatisfy(reason -> assertThat(reason).startsWith("principalUnrepaid's outstanding term, a disclosure"));
        assertThat(text(object(body, "drillDown"), "principalUnrepaid")).contains("Lending's capital report states capital's own lendingFunded figure, which is this plus principalCommitted");
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
