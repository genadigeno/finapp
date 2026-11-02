package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.AccrualCompleteness.Accruer;
import com.finapp.accounting.AccrualCompleteness.Lagging;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * accounting's {@link AccrualCompleteness} over lending's and bnpl's own reads (`P14-TSK-006`; owner decision C3 - "the
 * close requires accruals caught up"; {@code PHASE_14_PLAN.md} section 13.5): each accruer's oldest unaccrued date per
 * currency becomes one {@link Lagging}, ordered by accruer then currency, on the caller's own unit of work; a period
 * blocks while any accruer has an unaccrued date on or before its end. The two reads' SQL is the modules' own database
 * suites' (owed, C5); this proves the composition the close's {@code ACCRUALS_COMPLETE} check (`P14-TSK-011`) reads.
 */
@DisplayName("accruals complete: lending's and bnpl's accrued-through per currency, blocking a period they have not"
        + " reached (P14-TSK-006, C3)")
class ServicingAccrualCompletenessTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate OCTOBER_END = LocalDate.of(2026, 10, 31);
    private static final java.util.function.BiFunction<Connection, LocalDate, List<AccrualCompleteness.Floored>> NONE_FLOORED =
            (connection, periodEnd) -> List.of();

    private final Connection unitOfWork = unused();
    private final List<Connection> seen = new ArrayList<>();

    @Test
    @DisplayName("each accruer behind becomes one position per currency, on the caller's unit of work, ordered by accruer"
            + " then currency; accrued-through is the day before the oldest unaccrued date")
    void eachAccruerBehindIsOnePositionPerCurrency() {
        AccrualCompleteness completeness = new ServicingAccrualCompleteness(
                read(Map.of(USD, LocalDate.of(2026, 11, 2), EUR, LocalDate.of(2026, 10, 29))),
                read(Map.of(EUR, LocalDate.of(2026, 11, 1))), NONE_FLOORED);
        List<Lagging> lagging = completeness.lagging(unitOfWork);
        assertThat(lagging).containsExactly(
                new Lagging(Accruer.LENDING, "EUR", LocalDate.of(2026, 10, 29)),
                new Lagging(Accruer.LENDING, "USD", LocalDate.of(2026, 11, 2)),
                new Lagging(Accruer.BNPL, "EUR", LocalDate.of(2026, 11, 1)));
        assertThat(lagging.get(0).accruedThrough()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(seen).as("both reads on the caller's unit of work").containsExactly(unitOfWork, unitOfWork);
    }

    @Test
    @DisplayName("a period blocks while an accruer has an unaccrued date on or before its end - and only then")
    void aPeriodBlocksOnlyWhileAnAccruerHasNotReachedItsEnd() {
        AccrualCompleteness completeness = new ServicingAccrualCompleteness(
                read(Map.of(EUR, LocalDate.of(2026, 10, 31))), read(Map.of(EUR, LocalDate.of(2026, 11, 1))), NONE_FLOORED);
        assertThat(completeness.blocking(unitOfWork, OCTOBER_END))
                .as("lending has not accrued 31 October; bnpl has accrued it")
                .containsExactly(new Lagging(Accruer.LENDING, "EUR", OCTOBER_END));
        assertThat(completeness.blocking(unitOfWork, LocalDate.of(2026, 10, 30)))
                .as("both accrued through 30 October").isEmpty();
        assertThat(completeness.blocking(unitOfWork, LocalDate.of(2026, 11, 30))).hasSize(2);
    }

    @Test
    @DisplayName("both caught up: nothing lagging, nothing blocking")
    void caughtUpBlocksNothing() {
        AccrualCompleteness completeness = new ServicingAccrualCompleteness(read(Map.of()), read(Map.of()), NONE_FLOORED);
        assertThat(completeness.lagging(unitOfWork)).isEmpty();
        assertThat(completeness.blocking(unitOfWork, OCTOBER_END)).isEmpty();
    }

    @Test
    @DisplayName("a read answering null is a defect, never 'caught up'")
    void aNullReadIsADefect() {
        AccrualCompleteness completeness = new ServicingAccrualCompleteness(read(Map.of()), connection -> null, NONE_FLOORED);
        assertThatThrownBy(() -> completeness.lagging(unitOfWork)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the entries floored past a period's end, on the caller's unit of work - the one snapshot blocking reads on (P14-TSK-011)")
    void theFlooredEntriesAreReadOnTheCallersUnitOfWork() {
        List<AccrualCompleteness.Floored> floored = List.of(new AccrualCompleteness.Floored(Accruer.LENDING, "EUR", 2, 1,
                java.math.BigInteger.TEN, 0, java.math.BigInteger.ZERO));
        AccrualCompleteness completeness = new ServicingAccrualCompleteness(read(Map.of()), read(Map.of()), (connection, periodEnd) -> {
            assertThat(connection).isSameAs(unitOfWork);
            assertThat(periodEnd).isEqualTo(OCTOBER_END);
            return floored;
        });
        assertThat(completeness.flooredPast(unitOfWork, OCTOBER_END)).isEqualTo(floored);
    }

    private java.util.function.Function<Connection, Map<CurrencyCode, LocalDate>> read(
            Map<CurrencyCode, LocalDate> oldest) {
        return connection -> {
            seen.add(connection);
            return oldest;
        };
    }

    private static Connection unused() {
        return (Connection) Proxy.newProxyInstance(ServicingAccrualCompletenessTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "unit-of-work";
                    }
                    throw new AssertionError("the composition touches no connection itself: " + method.getName());
                });
    }
}
