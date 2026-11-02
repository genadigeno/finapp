package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.ValuationRateSet;
import com.finapp.accounting.ValuationRateSetId;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The port a translated report reads its pinned set through (`P14-TSK-015`; {@code INV-ACC-11}): the set's row and its rates,
 * by id, on the snapshot it is handed - the store's two reads and nothing else, so what reaches the report is the immutable
 * capture and its status. Hermetic: the store in memory; through a real read-only snapshot in
 * {@code ValuationRateSetDatabaseTest} (owed, C5).
 */
@DisplayName("what a translated report reads of its pinned set (P14-TSK-015)")
class PinnedValuationRatesTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            PinnedValuationRatesTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the store is in memory");
            });
    private static final YearMonth MONTH = YearMonth.of(2096, 2);

    @Test
    @DisplayName("a set by id: its id, period, month, status, instant, bound and rates with their provenance; no set, nothing")
    void aSetIsReadWholeByItsId() {
        InMemoryValuationRateSetStore store = new InMemoryValuationRateSetStore();
        AccountingPeriodId period = AccountingPeriodId.next(IDS);
        Instant asOf = ValuationRateSet.asOf(MONTH);
        ValuationRate rate = new ValuationRate(CurrencyCode.of("EUR"), CurrencyCode.of("USD"), new BigDecimal("1.1561"),
                "reference.test", UUID.randomUUID(), asOf.minusSeconds(60), asOf.minusSeconds(59));
        ValuationRateSetId id = ValuationRateSetId.next(IDS);
        store.insert(SNAPSHOT, new ValuationRateSetStore.NewSet(id, new ValuationRateSet(period, MONTH, asOf,
                ValuationStaleness.standard(), List.of(rate)), new Actor("capturer", ActorType.CUSTOMER), Optional.empty(),
                CorrelationId.generate(IDS)));
        ValuationRateReads reads = new PinnedValuationRates(store);

        ValuationRateReads.PinnedSet set = reads.set(SNAPSHOT, id.value()).orElseThrow();
        assertThat(set.id()).isEqualTo(id.value());
        assertThat(set.period()).isEqualTo(period);
        assertThat(set.month()).isEqualTo(MONTH);
        assertThat(set.status()).isEqualTo(ValuationRateSetStatus.PROPOSED);
        assertThat(set.asOf()).isEqualTo(asOf);
        assertThat(set.maxAgeHours()).isEqualTo(ValuationStaleness.DEFAULT_HOURS);
        assertThat(set.rates()).containsExactly(rate);
        assertThat(reads.set(SNAPSHOT, ValuationRateSetId.next(IDS).value())).isEmpty();
    }

    @Test
    @DisplayName("every read reaches the database through the definition's restricted view: the store's set read and its rates"
            + " read, each admitted - a SELECT, armed by the bound - never a lock, never a write")
    void everyReadPassesTheView() {
        List<String> prepared = new ArrayList<>();
        Connection view = new ReadOnlySnapshot(recording(prepared), Duration.ofSeconds(5), () -> 0L).connection();
        JdbcValuationRateSetStore store = new JdbcValuationRateSetStore();
        assertThat(new PinnedValuationRates(store).set(view, ValuationRateSetId.next(IDS).value())).isEmpty();
        assertThat(store.rates(view, ValuationRateSetId.next(IDS))).isEmpty();
        assertThat(prepared).filteredOn(sql -> sql.contains("set_config")).as("the bound armed before each read").hasSize(2);
        assertThat(prepared).filteredOn(sql -> !sql.contains("set_config")).hasSize(2)
                .allSatisfy(sql -> assertThat(sql).startsWith("SELECT").doesNotContain("FOR UPDATE"))
                .anyMatch(sql -> sql.contains("FROM accounting.valuation_rate_set"))
                .anyMatch(sql -> sql.contains("FROM accounting.valuation_rate WHERE"));
    }

    /** A connection whose statements record their SQL; the view's bound answers one row, all else none. */
    private static Connection recording(List<String> prepared) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        prepared.add((String) args[0]);
                        yield statement(((String) args[0]).contains("set_config"));
                    }
                    case "toString" -> "a recording connection";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PreparedStatement statement(boolean oneRow) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length == 2) {
                        return null;
                    }
                    return switch (name) {
                        case "executeQuery" -> rows(oneRow ? 1 : 0);
                        case "close" -> null;
                        case "toString" -> "a recording statement";
                        default -> throw new UnsupportedOperationException(name);
                    };
                });
    }

    private static ResultSet rows(int count) {
        int[] left = {count};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> left[0]-- > 0;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
