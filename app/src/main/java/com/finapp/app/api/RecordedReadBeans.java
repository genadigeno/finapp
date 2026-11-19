package com.finapp.app.api;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one recorder of the operator's reads of customers (`P17-TSK-025`), shared by every module's read desk. */
@Configuration(proxyBeanMethods = false)
public class RecordedReadBeans {

    @Bean
    RecordedReads recordedReads(AuditWriter<Connection> auditWriter, IdGenerator idGenerator, Clock clock) {
        return new RecordedReads(auditWriter, idGenerator, clock);
    }
}
