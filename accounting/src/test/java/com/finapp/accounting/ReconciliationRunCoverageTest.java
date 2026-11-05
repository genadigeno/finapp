package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.ReconciliationRunCoverage.Coverage;
import com.finapp.accounting.ReconciliationRunCoverage.Source;
import com.finapp.accounting.ReconciliationRunCoverage.SourceState;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a month requires of a source (`P14-TSK-025`; the owner's rulings of 2026-11-06 on `P14-TSK-012`'s (c), (e) and (k)): {@link Source#requiredIn}, the one
 * rule, at each of its boundaries - admitted by the month's last day, not retired before its first, its rule set active by its last day, declared by the build -
 * and {@link Coverage#required()}, the one list the check, the re-judge and the gauge read. That they read it alike is {@code OneRuleForTheMonthTest}'s.
 */
@DisplayName("what a month requires of a source (P14-TSK-025)")
class ReconciliationRunCoverageTest {

    private static final YearMonth MAY = YearMonth.of(2097, 5);
    private static final LocalDate FIRST = MAY.atDay(1);
    private static final LocalDate LAST = MAY.atEndOfMonth();
    private static final LocalDate LONG_AGO = LocalDate.of(2001, 1, 1);

    @Test
    @DisplayName("admission: admitted on the month's last day it served the month; the day after, it did not")
    void admittedByTheLastDay() {
        assertThat(source(SourceState.ACTIVE, LAST, Optional.empty(), Optional.of(LONG_AGO)).requiredIn(MAY)).isTrue();
        assertThat(source(SourceState.ACTIVE, LAST.plusDays(1), Optional.empty(), Optional.of(LONG_AGO)).requiredIn(MAY)).isFalse();
        assertThat(source(SourceState.ACTIVE, LAST.plusDays(1), Optional.empty(), Optional.of(LONG_AGO)).requiredIn(MAY.plusMonths(1))).as("from its own month").isTrue();
    }

    @Test
    @DisplayName("retirement: retired on the month's first day or later it served the month, and is required; retired the day before, it is not - a RETIRED row is no excuse by itself")
    void notRetiredBeforeTheFirstDay() {
        assertThat(source(SourceState.RETIRED, LONG_AGO, Optional.of(FIRST), Optional.of(LONG_AGO)).requiredIn(MAY)).isTrue();
        assertThat(source(SourceState.RETIRED, LONG_AGO, Optional.of(LAST.plusDays(40)), Optional.of(LONG_AGO)).requiredIn(MAY)).isTrue();
        assertThat(source(SourceState.RETIRED, LONG_AGO, Optional.of(FIRST.minusDays(1)), Optional.of(LONG_AGO)).requiredIn(MAY)).isFalse();
        assertThat(source(SourceState.RETIRED, LONG_AGO, Optional.of(FIRST.minusDays(1)), Optional.of(LONG_AGO)).requiredIn(MAY.minusMonths(1)))
                .as("the month it was retired in, it served").isTrue();
    }

    @Test
    @DisplayName("the rule set (D26): none ever activated is no run to give; activated on the month's last day it is required; the day after, it is not")
    void aRuleSetActiveByTheLastDay() {
        assertThat(source(SourceState.ACTIVE, LONG_AGO, Optional.empty(), Optional.empty()).requiredIn(MAY)).isFalse();
        assertThat(source(SourceState.ACTIVE, LONG_AGO, Optional.empty(), Optional.of(LAST)).requiredIn(MAY)).isTrue();
        assertThat(source(SourceState.ACTIVE, LONG_AGO, Optional.empty(), Optional.of(LAST.plusDays(1))).requiredIn(MAY)).isFalse();
    }

    @Test
    @DisplayName("declaration: an undeclared row has no door and no month requires it, however it is dated")
    void anUndeclaredSourceIsNeverRequired() {
        assertThat(source(SourceState.UNDECLARED, LONG_AGO, Optional.empty(), Optional.of(LONG_AGO)).requiredIn(MAY)).isFalse();
        assertThat(source(SourceState.ACTIVE, LONG_AGO, Optional.empty(), Optional.of(LONG_AGO)).requiredIn(MAY)).isTrue();
    }

    @Test
    @DisplayName("the convenience form is required in every month, and refuses RETIRED, which names its day; a retirement day exactly when RETIRED, never before the admission")
    void theFormsAreCoherent() {
        Source always = new Source(UUID.randomUUID(), "simulated-psp.settlement", SourceState.ACTIVE);
        assertThat(always.admittedOn()).isEqualTo(Source.SINCE_THE_BEGINNING).isEqualTo(LocalDate.EPOCH);
        assertThat(always.requiredIn(YearMonth.of(1970, 1))).isTrue();
        assertThat(always.requiredIn(MAY)).isTrue();
        assertThatThrownBy(() -> new Source(UUID.randomUUID(), "old.settlement", SourceState.RETIRED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source(SourceState.ACTIVE, LONG_AGO, Optional.of(LAST), Optional.of(LONG_AGO))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source(SourceState.RETIRED, LAST, Optional.of(FIRST), Optional.of(LONG_AGO))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Coverage.required: the sources the month requires, in the register's order, and the coverage's value names each source's days and whether it is required")
    void theOneList() {
        Source required = source(SourceState.ACTIVE, LONG_AGO, Optional.empty(), Optional.of(LONG_AGO));
        Source late = source(SourceState.ACTIVE, LAST.plusDays(1), Optional.empty(), Optional.of(LONG_AGO));
        Source retired = source(SourceState.RETIRED, LONG_AGO, Optional.of(FIRST), Optional.of(LONG_AGO));
        Source unactivated = source(SourceState.ACTIVE, LONG_AGO, Optional.empty(), Optional.empty());
        Coverage coverage = new Coverage(MAY, List.of(), List.of(required, late, retired, unactivated));
        assertThat(coverage.required()).containsExactly(required, retired);
        String value = com.finapp.accounting.reporting.CanonicalJson.text(coverage.asValue());
        assertThat(value).contains("\"admittedOn\":\"" + LAST.plusDays(1) + "\"").contains("\"retiredOn\":\"" + FIRST + "\"").contains("\"retiredOn\":null")
                .contains("\"ruleSetActiveOn\":null").contains("\"required\":true").contains("\"required\":false");
    }

    private static Source source(SourceState state, LocalDate admittedOn, Optional<LocalDate> retiredOn, Optional<LocalDate> ruleSetActiveOn) {
        return new Source(UUID.randomUUID(), "source-" + UUID.randomUUID().toString().substring(0, 8) + ".settlement", state, admittedOn, retiredOn, ruleSetActiveOn);
    }
}
