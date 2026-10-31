package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.ValuationRates;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceSourceDeclaration;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * accounting's {@code ValuationRates} over fx's reference snapshots (`P14-TSK-014`; ADR-0116 decision 2; ADR-0075
 * decision 1; {@code INV-ACC-11}, {@code INV-FX-05}) - <strong>the entry's probe target</strong>: the adapter reads
 * {@code fx.rate_snapshot} and nothing else of fx - never a quote's, a trade's executed or customer rate, a cover's or the
 * provider's evidence - for the declared reference source, each valued currency's EUR pair in the reference's canonical
 * direction, the last observation strictly before the instant; the row mapped exactly, the rate's scale included.
 * Hermetic: the connection is a recording stand-in that answers scripted rows; {@code ValuationRateSetDatabaseTest} reads
 * the live table (owed, C5).
 */
@DisplayName("valuation reads fx's reference snapshots only - never an executed or customer rate (P14-TSK-014)")
class FxReferenceValuationRatesTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Instant AS_OF = Instant.parse("2026-10-01T00:00:00Z");

    /** fx's tables and columns that hold execution evidence - a customer's or a provider's binding rate. */
    private static final List<String> EXECUTION_EVIDENCE = List.of("executed_rate", "customer_rate", "internal_rate",
            "provider_rate", "fx.quote", "fx.trade", "fx.cover", "fx.cover_attempt", "fx.fx_provider_evidence");

    @Test
    @DisplayName("the statement reads fx.rate_snapshot alone, for the declared source and each valued EUR pair, strictly"
            + " before the instant, newest first - and every statement the adapter runs is that one")
    void readsOnlyTheReferenceSnapshots() {
        Set<String> tables = new TreeSet<>();
        Matcher named = Pattern.compile("\\bfx\\.([a-z_]+)").matcher(FxReferenceValuationRates.LAST_BEFORE);
        while (named.find()) {
            tables.add(named.group(1));
        }
        assertThat(tables).as("the only fx table a valuation reads").containsExactly("rate_snapshot");
        for (String evidence : EXECUTION_EVIDENCE) {
            assertThat(FxReferenceValuationRates.LAST_BEFORE).as("never %s (INV-FX-05)", evidence)
                    .doesNotContain(evidence);
        }
        assertThat(FxReferenceValuationRates.LAST_BEFORE)
                .contains("WHERE source = ? AND base_currency = ? AND quote_currency = ? AND observed_at < ?")
                .endsWith("ORDER BY observed_at DESC LIMIT 1");

        Recording jdbc = new Recording(Map.of());
        FxReferenceValuationRates.reference().lastObservedBefore(jdbc.connection(), AS_OF);

        assertThat(jdbc.prepared).containsOnly(FxReferenceValuationRates.LAST_BEFORE);
        assertThat(jdbc.executions).hasSize(4);
        List<String> quotes = new ArrayList<>();
        for (Map<Integer, Object> bound : jdbc.executions) {
            assertThat(bound.get(1)).as("the declared reference source").isEqualTo(ReferenceSourceDeclaration.SOURCE);
            assertThat(bound.get(2)).as("EUR-based").isEqualTo("EUR");
            assertThat(bound.get(4)).as("strictly before the period's end").isEqualTo(Timestamp.from(AS_OF));
            quotes.add((String) bound.get(3));
        }
        assertThat(quotes).containsExactly("GBP", "USD", "JPY", "BHD");
    }

    @Test
    @DisplayName("the row is mapped exactly - the rate's NUMERIC(20,10) value and scale untouched, the snapshot id, source"
            + " and both instants; a pair with no row answers empty")
    void theRowIsMappedExactly() {
        UUID snapshot = UUID.randomUUID();
        Map<String, Object> gbp = new HashMap<>();
        gbp.put("id", snapshot);
        gbp.put("source", ReferenceSourceDeclaration.SOURCE);
        gbp.put("base_currency", "EUR");
        gbp.put("quote_currency", "GBP");
        gbp.put("rate", new BigDecimal("0.8612000000"));
        gbp.put("observed_at", Timestamp.from(Instant.parse("2026-09-30T16:00:00Z")));
        gbp.put("received_at", Timestamp.from(Instant.parse("2026-09-30T16:00:01.250Z")));
        Recording jdbc = new Recording(Map.of("GBP", gbp));

        List<ValuationRates.Observed> observed =
                FxReferenceValuationRates.reference().lastObservedBefore(jdbc.connection(), AS_OF);

        assertThat(observed).extracting(ValuationRates.Observed::currency).extracting(CurrencyCode::code)
                .containsExactly("GBP", "USD", "JPY", "BHD");
        ValuationRates.Observation read = observed.get(0).observation().orElseThrow();
        assertThat(read.snapshotId()).isEqualTo(snapshot);
        assertThat(read.source()).isEqualTo(ReferenceSourceDeclaration.SOURCE);
        assertThat(read.base()).isEqualTo(EUR);
        assertThat(read.quote()).isEqualTo(CurrencyCode.of("GBP"));
        assertThat(read.rate()).isEqualTo(new BigDecimal("0.8612000000")).extracting(BigDecimal::scale).isEqualTo(10);
        assertThat(read.observedAt()).isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
        assertThat(read.receivedAt()).isEqualTo(Instant.parse("2026-09-30T16:00:01.250Z"));
        assertThat(observed.subList(1, 4)).allMatch(entry -> entry.observation().isEmpty());
    }

    @Test
    @DisplayName("the valued currencies are the ledger's postable ones but EUR, each a pair the reference publishes from"
            + " EUR in its canonical direction - nothing is ever inverted; a currency it does not publish refuses startup")
    void theValuedCurrenciesAreTheLedgersButEur() {
        List<CurrencyCode> valued = FxReferenceValuationRates.reference().valued();
        assertThat(valued).doesNotContain(EUR)
                .containsExactlyElementsOf(SupportedCurrencies.ALL.stream().filter(c -> !c.equals(EUR)).toList());
        for (CurrencyCode currency : valued) {
            assertThat(ReferenceSourceDeclaration.declares(new ReferencePair(EUR, currency)))
                    .as("EUR/%s is canonical", currency).isTrue();
        }
        assertThatThrownBy(() -> new FxReferenceValuationRates("simulated-reference",
                List.of(EUR, CurrencyCode.of("CHF")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EUR/CHF");
        assertThatThrownBy(() -> new FxReferenceValuationRates("simulated-reference", List.of(EUR)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A connection that records every statement and its bindings and answers the scripted row per quote currency. */
    private static final class Recording {

        final List<String> prepared = new ArrayList<>();
        final List<Map<Integer, Object>> executions = new ArrayList<>();
        private final Map<String, Map<String, Object>> rows;

        Recording(Map<String, Map<String, Object>> rows) {
            this.rows = rows;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            prepared.add((String) args[0]);
                            yield statement();
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private PreparedStatement statement() {
            Map<Integer, Object> bound = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setString", "setTimestamp", "setObject" -> {
                            bound.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "executeQuery" -> {
                            executions.add(Map.copyOf(bound));
                            yield result(rows.get((String) bound.get(3)));
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private ResultSet result(Map<String, Object> row) {
            boolean[] served = {false};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            boolean next = row != null && !served[0];
                            served[0] = true;
                            yield next;
                        }
                        case "getString", "getBigDecimal", "getTimestamp", "getObject" -> row.get((String) args[0]);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
