package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.SafeguardingPositionDefinition;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.accounting.testing.ReportBodies;
import com.finapp.accounting.testing.SafeguardingBooks;
import com.finapp.ledger.AccountPurpose;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two figures, one relation, pinned (`P14-TSK-019`, `P14-TSK-023`; ADR-0096 section 6 as the owner corrected it on 2026-11-05, and `P14-TSK-021`'s item (h)): the return's term + committed = {@code Headroom.lendingFunded()}. {@code ReportingNeverWritesTest} refuses a report port's adapter any edge to {@code com.finapp.lending}, so a FINAL's as-of figure cannot call the headroom. A second statement is unavoidable; this is what holds the two together: here in {@code app}, the one module that sees both. A term dropped or added on either side, or a purpose added to the headroom and not to the return, fails here. Capital's identity does not move: capital = headroom + outstanding + committed + written off.
 */
@DisplayName("the return's term + committed is the headroom's lendingFunded (P14-TSK-019, P14-TSK-023)")
class SafeguardingFormulaIsTheHeadroomsTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final YearMonth MONTH = YearMonth.of(2097, 6);
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static final List<long[]> TERMS = List.of(new long[] {0, 0, 0}, new long[] {30_00, 0, 0}, new long[] {0, 7_00, 0}, new long[] {0, 0, 5_00}, new long[] {30_00, 7_00, 5_00}, new long[] {1, 2, 4}, new long[] {-3_00, 7_00, 0});

    /** {outstanding, committed, written off}, in minor units, as the headroom holds them, from capital of 1 000. */
    private static LendingCapitalHeadroom.Headroom headroomOf(long[] terms) {
        return new LendingCapitalHeadroom.Headroom(EUR, eur(1_000_00), eur(terms[1]), eur(terms[0]), eur(terms[2]));
    }

    private static long returnsTerm(long[] terms) {
        return LendingFundedAsOfCutoff.principalUnrepaid(BigDecimal.valueOf(terms[0], 2), BigDecimal.valueOf(terms[2], 2)).movePointRight(2).longValueExact();
    }

    @Test
    @DisplayName("from the return's side: its term + committed is the headroom's figure, each term counted: outstanding, committed and written off")
    void theReturnsTermPlusCommittedIsTheHeadrooms() {
        for (long[] terms : TERMS) {
            assertThat(returnsTerm(terms) + terms[1]).as(Arrays.toString(terms)).isEqualTo(headroomOf(terms).lendingFunded().minorUnits());
        }
    }

    @Test
    @DisplayName("from the headroom's side: its figure less committed is the return's term, and an acceptance moves the headroom's figure and not the return's")
    void theHeadroomsLessCommittedIsTheReturnsTerm() {
        for (long[] terms : TERMS) {
            LendingCapitalHeadroom.Headroom headroom = headroomOf(terms);
            assertThat(headroom.lendingFunded().minus(headroom.committed()).minorUnits()).as(Arrays.toString(terms)).isEqualTo(returnsTerm(terms));
        }
        long[] before = {30_00, 0, 5_00};
        long[] accepted = {30_00, 7_00, 5_00};
        assertThat(returnsTerm(accepted)).as("the return's").isEqualTo(returnsTerm(before));
        assertThat(headroomOf(accepted).lendingFunded().minorUnits()).as("capital's").isEqualTo(headroomOf(before).lendingFunded().minorUnits() + 7_00);
    }

    @Test
    @DisplayName("capital's identity does not change: capital = headroom + outstanding + committed + written off, and = headroom + lendingFunded")
    void capitalsIdentityDoesNotChange() {
        for (long[] terms : TERMS) {
            LendingCapitalHeadroom.Headroom headroom = headroomOf(terms);
            assertThat(headroom.headroom().minorUnits() + terms[0] + terms[1] + terms[2]).as(Arrays.toString(terms)).isEqualTo(headroom.capital().minorUnits());
            assertThat(headroom.headroom().plus(headroom.lendingFunded())).as(Arrays.toString(terms)).isEqualTo(headroom.capital());
        }
    }

    @Test
    @DisplayName("through the real definition over real books, from both sides: the body's principalUnrepaid + principalCommitted is the headroom's figure over the body's own terms, customerFundedLiabilities rests on the first alone, and the written-off principal is in it")
    void theRealDefinitionHoldsTheRelation() {
        SafeguardingBooks books = new SafeguardingBooks();
        books.deposit(MONTH.atDay(1), "EUR", 2, 100_00).capital(MONTH.atDay(1), "EUR", 2, 80_00)
                .accepted("out", MONTH.atDay(1), "EUR", 2, 30_00).disbursed("out", MONTH.atDay(2))
                .accepted("lost", MONTH.atDay(1), "EUR", 2, 5_00).disbursed("lost", MONTH.atDay(2)).writtenOff(MONTH.atDay(9), "EUR", 2, 5_00, 0)
                .accepted("waiting", MONTH.atDay(3), "EUR", 2, 7_00);
        CanonicalJson.Obj eur = ReportBodies.objects(bodyOver(books), "currencies").get(0);

        long outstanding = minor(eur, "principalOutstanding");
        long committed = minor(eur, "principalCommitted");
        long writtenOff = minor(eur, "principalWrittenOff");
        assertThat(new long[] {outstanding, committed, writtenOff}).as("not vacuous: each term stands").containsExactly(30_00, 7_00, 5_00);
        LendingCapitalHeadroom.Headroom headroom = headroomOf(new long[] {outstanding, committed, writtenOff});

        assertThat(minor(eur, "principalUnrepaid")).isEqualTo(35_00);
        assertThat(minor(eur, "principalUnrepaid") + committed).isEqualTo(headroom.lendingFunded().minorUnits());
        assertThat(headroom.lendingFunded().minorUnits() - committed).isEqualTo(minor(eur, "principalUnrepaid"));
        assertThat(minor(eur, "customerFundedLiabilities")).isEqualTo(minor(eur, "liabilities") - minor(eur, "principalUnrepaid"));
        assertThat(minor(eur, "position") - minor(eur, "positionBeforePrincipalUnrepaid")).isEqualTo(minor(eur, "principalUnrepaid"));
    }

    private static long minor(CanonicalJson.Obj figures, String name) {
        return ReportBodies.minor(figures, name).longValueExact();
    }

    private static CanonicalJson.Obj bodyOver(SafeguardingBooks books) {
        AccountingPeriodId period = AccountingPeriodId.next(IDS);
        GlMappingVersionId pinned = GlMappingVersionId.next(IDS);
        books.journal.period(new TrialBalanceReads.Period(period, MONTH.atDay(1), MONTH.atEndOfMonth(), PeriodStatus.CLOSED, Optional.of(pinned)));
        books.journal.version(pinned, ReferenceGlMapping.version(MONTH));
        Instant lock = MONTH.atEndOfMonth().plusDays(5).atStartOfDay().toInstant(ZoneOffset.UTC);
        Connection snapshot = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            throw new UnsupportedOperationException("a report reads only through its ports");
        });
        return new SafeguardingPositionDefinition(books.journal, books.journal.counterparties(), books).generate(snapshot, new ReportInputs(period, MONTH, 1, RunBasis.FINAL, Optional.of(lock), Optional.of(pinned), Optional.of(1), lock, RunParameters.none()));
    }

    @Test
    @DisplayName("the outstanding term's purposes are the headroom's own, by name, and the definition's treatment of them")
    void thePrincipalPurposesAreTheHeadrooms() {
        Set<String> theHeadrooms = LendingCapitalHeadroom.principalPurposes().stream().map(AccountPurpose::name).collect(Collectors.toCollection(TreeSet::new));

        assertThat(new TreeSet<>(LendingFundedAsOfCutoff.PRINCIPAL_PURPOSES)).isEqualTo(theHeadrooms);
        assertThat(purposesTreatedAs(SafeguardingPositionDefinition.Treatment.PRINCIPAL)).isEqualTo(theHeadrooms);
    }

    @Test
    @DisplayName("what marks an entry a write-off is the headroom's own, by name, and the definition's treatment of them")
    void theWriteOffExpensesAreTheHeadrooms() {
        Set<String> theHeadrooms = LendingCapitalHeadroom.writeOffExpenses().stream().map(AccountPurpose::name).collect(Collectors.toCollection(TreeSet::new));

        assertThat(new TreeSet<>(LendingFundedAsOfCutoff.WRITE_OFF_EXPENSES)).isEqualTo(theHeadrooms);
        assertThat(purposesTreatedAs(SafeguardingPositionDefinition.Treatment.WRITE_OFF_ROUTE)).isEqualTo(theHeadrooms);
    }

    private static Set<String> purposesTreatedAs(SafeguardingPositionDefinition.Treatment treatment) {
        return SafeguardingPositionDefinition.DISPOSITIONS.stream().filter(held -> held.treatment() == treatment)
                .map(held -> held.key().split("/", 2)[0]).collect(Collectors.toCollection(TreeSet::new));
    }
}
