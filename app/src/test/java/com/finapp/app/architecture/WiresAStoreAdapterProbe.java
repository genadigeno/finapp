package com.finapp.app.architecture;

import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountStore;
import java.sql.Connection;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * The composition root's wiring constructing a store adapter, for {@code ModuleBoundaryRulesTest}'s teeth (`P15-REM-001`): the
 * exemption of {@code moduleInternalsArePrivateToTheirModule}, which must hold, or no adapter could be built.
 *
 * <p>{@code @TestConfiguration}, which is a {@code @Configuration} by meta-annotation, so the rule reads it as wiring; and
 * Spring Boot's test filter keeps it out of every context's component scan. It declares no {@code @Bean} either, so even a
 * scan that took it would register nothing.
 */
@TestConfiguration(proxyBeanMethods = false)
@SuppressWarnings("unused")
class WiresAStoreAdapterProbe {

    LedgerAccountStore<Connection> ledgerAccounts() {
        return new JdbcLedgerAccountStore();
    }
}
