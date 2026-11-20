package com.finapp.app.bnpl;

import com.finapp.app.api.RecordedReads;
import com.finapp.app.credit.IdentityActingParty;
import com.finapp.bnpl.BnplOperatorLists;
import com.finapp.bnpl.JdbcBnplOperatorLists;
import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for BNPL's §7 lists (`P17-TSK-035`): the read model over bnpl's tables and the desk the list doors serve them through
 * - whose party a reader is read through the identity store (credit's {@link IdentityActingParty}, one read for every module).
 */
@Configuration(proxyBeanMethods = false)
public class BnplListBeans {

    @Bean
    BnplOperatorLists bnplOperatorLists() {
        return new JdbcBnplOperatorLists();
    }

    @Bean
    BnplListDesk bnplListDesk(
            BnplOperatorLists bnplOperatorLists,
            IdentityStore<Connection> identityStore,
            RecordedReads recordedReads,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            BnplTransactions bnplTransactions) {
        return new BnplListDesk(bnplOperatorLists, new IdentityActingParty(identityStore), recordedReads, auditWriter,
                idGenerator, clock, bnplTransactions);
    }
}
