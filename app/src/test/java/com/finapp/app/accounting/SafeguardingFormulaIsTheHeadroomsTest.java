package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.reporting.SafeguardingPositionDefinition;
import com.finapp.ledger.AccountPurpose;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One formula, stated twice and pinned (`P14-TSK-019`; ADR-0096 section 6, the owner's item (h)): {@code ReportingNeverWritesTest} refuses a report port's adapter any edge to {@code com.finapp.lending}, so a FINAL's as-of figure cannot call {@code Headroom.lendingFunded()}. A second copy is unavoidable; this is what keeps the two one: here in {@code app}, the one module that sees both. A term dropped or added on either side, or a purpose added to the headroom and not to the return, fails here.
 */
@DisplayName("lending_funded as of the cutoff is the headroom's formula (P14-TSK-019)")
class SafeguardingFormulaIsTheHeadroomsTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    @Test
    @DisplayName("the same figure from the same terms, and each term counted: outstanding, committed and written off")
    void theSameFigure() {
        for (long[] terms : List.of(new long[] {0, 0, 0}, new long[] {30_00, 0, 0}, new long[] {0, 7_00, 0}, new long[] {0, 0, 5_00}, new long[] {30_00, 7_00, 5_00}, new long[] {1, 2, 4}, new long[] {-3_00, 7_00, 0})) {
            LendingCapitalHeadroom.Headroom headroom = new LendingCapitalHeadroom.Headroom(EUR, eur(1_000_00), eur(terms[1]), eur(terms[0]), eur(terms[2]));
            BigDecimal asOf = LendingFundedAsOfCutoff.lendingFunded(BigDecimal.valueOf(terms[0], 2), BigDecimal.valueOf(terms[1], 2), BigDecimal.valueOf(terms[2], 2));

            assertThat(asOf.movePointRight(2).longValueExact()).as(Arrays.toString(terms)).isEqualTo(headroom.lendingFunded().minorUnits());
        }
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
