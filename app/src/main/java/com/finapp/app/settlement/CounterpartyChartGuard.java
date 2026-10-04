package com.finapp.app.settlement;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Counterparty;
import com.finapp.ledger.CounterpartyChart;
import com.finapp.ledger.CounterpartyClearing;
import com.finapp.ledger.JdbcCounterpartyStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Refuses to start on an incomplete counterparty chart (`P9-TSK-010`, ADR-0078 section 4): every
 * declared counterparty position ({@link CounterpartyClearings}) must have its registry row, of
 * its declared kind, and one seeded account per settled currency - so a missing seed is caught at
 * deploy, never at the first cover or recognition. The rule is the ledger's
 * ({@link CounterpartyChart}); this runs it on every instance at startup, read-only.
 *
 * <p><strong>A gap refuses; an unreachable database does not.</strong> Migrations run before
 * the application, never inside it (ADR-0011), so a reachable database holds the chart this
 * build must find. A database down at startup is not a finding about the chart - the
 * {@code ConnectionPoolSizingGuard} reasoning: readiness reports it - and the per-call refusal
 * of {@code ChartOfAccounts.resolve} stays the backstop. With nothing declared, nothing is read.
 */
@Slf4j
@Component
public class CounterpartyChartGuard {

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    CounterpartyChartGuard(DataSource dataSource) {
        verify(CounterpartyClearings.declared(), dataSource::getConnection);
    }

    /** The startup rule, over planted declarations in its tests. */
    public static void verify(Collection<CounterpartyClearing> declared, Connections connections) {
        if (declared.isEmpty()) {
            return;
        }
        JdbcCounterpartyStore registry = new JdbcCounterpartyStore();
        JdbcLedgerAccountStore chart = new JdbcLedgerAccountStore();
        Connection connection;
        try {
            connection = connections.open();
        } catch (SQLException unreachable) {
            log.warn("The counterparty chart could not be read at startup ({}); the per-call"
                    + " refusal of ChartOfAccounts.resolve stays the backstop (ADR-0078)",
                    unreachable.getSQLState());
            return;
        }
        try (connection) {
            connection.setReadOnly(true);
            CounterpartyChart.verify(declared, new CounterpartyChart.Readings() {
                @Override
                public Optional<Counterparty> counterparty(String code) {
                    return registry.findByCode(connection, code);
                }

                @Override
                public boolean hasAccount(AccountPurpose purpose, String code, CurrencyCode currency) {
                    return chart.findCounterpartyAccount(connection, purpose, code, currency).isPresent();
                }
            });
        } catch (SQLException closing) {
            log.warn("Closing the counterparty chart's startup read failed ({})", closing.getSQLState());
        }
    }
}
