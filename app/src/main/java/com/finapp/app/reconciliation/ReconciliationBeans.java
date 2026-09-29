package com.finapp.app.reconciliation;

import com.finapp.ledger.JournalEntryStore;
import com.finapp.payments.SettlementExpectations;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.JdbcExpectationRegister;
import com.finapp.reconciliation.JdbcRuleSets;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSources;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The reconciliation module's composition (`P8-TSK-004`, ADR-0064, ADR-0067) — and the ONE
 * place `payments`' expectation port meets `reconciliation`'s register: neither module may
 * compile against the other, so the recorder below is the join, on the completing
 * connection, in the completing transaction.
 */
@Configuration
public class ReconciliationBeans {

    @Bean
    ExpectationRegister expectationRegister(IdGenerator idGenerator) {
        return new JdbcExpectationRegister(idGenerator);
    }

    @Bean
    RuleSets ruleSets() {
        return new JdbcRuleSets();
    }

    /**
     * The one implementation of {@code payments.SettlementExpectations} (`-005` and `-019`
     * point `merchant`'s ports at this same recorder): source resolved from the declared
     * position through the compiled register (`INV-SET-05`), amount and direction read off
     * the posted clearing line, the dating pinned from the source's ACTIVE rule set
     * ({@code INV-HIST-04}).
     */
    @Bean
    SettlementExpectations settlementExpectations(
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            JournalEntryStore<Connection> journalEntryStore,
            RuleSets ruleSets,
            ExpectationRegister expectationRegister,
            Clock clock) {
        return new ReconciliationExpectationRecorder(
                settlementSources,
                settlementFileStore,
                journalEntryStore,
                ruleSets,
                expectationRegister,
                clock);
    }
}
