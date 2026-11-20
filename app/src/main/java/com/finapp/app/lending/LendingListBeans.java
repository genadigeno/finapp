package com.finapp.app.lending;

import com.finapp.app.api.RecordedReads;
import com.finapp.app.credit.IdentityActingParty;
import com.finapp.identity.IdentityStore;
import com.finapp.lending.CreditLineStatements;
import com.finapp.lending.JdbcLendingOperatorLists;
import com.finapp.lending.LendingOperatorLists;
import com.finapp.lending.LoanExplanations;
import com.finapp.lending.LoanOperatorReads;
import com.finapp.lending.LoanSchedules;
import com.finapp.lending.LoanStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for lending's §7 collections and the investigator's account reads (`P17-TSK-035`): the read model over lending's
 * tables, the schedule and statement reads judged for self-dealing, and the desk the collection doors serve them through -
 * whose party a reader is read through the identity store (credit's {@link IdentityActingParty}, one read for every module).
 */
@Configuration(proxyBeanMethods = false)
public class LendingListBeans {

    @Bean
    LendingOperatorLists lendingOperatorLists() {
        return new JdbcLendingOperatorLists();
    }

    @Bean
    LoanOperatorReads loanOperatorReads(LoanStore loanStore, IdentityStore<Connection> identityStore,
            LoanSchedules loanSchedules, CreditLineStatements creditLineStatements) {
        return new LoanOperatorReads(loanStore, new IdentityActingParty(identityStore), loanSchedules, creditLineStatements);
    }

    @Bean
    LendingListDesk lendingListDesk(
            LendingOperatorLists lendingOperatorLists,
            LoanOperatorReads loanOperatorReads,
            LoanExplanations loanExplanations,
            IdentityStore<Connection> identityStore,
            RecordedReads recordedReads,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            LendingTransactions lendingTransactions) {
        return new LendingListDesk(lendingOperatorLists, loanOperatorReads, loanExplanations,
                new IdentityActingParty(identityStore), recordedReads, auditWriter, idGenerator, clock, lendingTransactions);
    }
}
