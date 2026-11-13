package com.finapp.app.audit;

import com.finapp.platform.audit.archive.AuditArchival;
import com.finapp.platform.audit.archive.AuditArchiveSegments;
import com.finapp.platform.audit.archive.JdbcAuditArchiveSegments;
import com.finapp.platform.retention.RetentionConnectionSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The audit archive (`P16-TSK-010`; {@code DATA_RETENTION.md} section 3; ADR-0135). Its database half and its meters exist on every
 * instance; the schedule only where {@code finapp.audit.archive.endpoint} names a store. No committed overlay names one today, because
 * no environment has a store the archive may write to: the queue then grows, and the pending gauges say so. The experiments scope on
 * {@code kind} names its test-only store (runbook section 11.50).
 */
@Configuration(proxyBeanMethods = false)
class AuditArchiveBeans {

    @Bean
    AuditArchiveSegments auditArchiveSegments(RetentionConnectionSource connections) {
        return new JdbcAuditArchiveSegments(connections);
    }

    @Bean
    AuditArchiveMeters auditArchiveMeters(
            MeterRegistry registry, AuditArchiveSegments segments, @Value("${finapp.audit.archive.endpoint:}") String endpoint) {
        return new AuditArchiveMeters(registry, segments, !endpoint.isBlank() && !endpoint.trim().equalsIgnoreCase("false"));
    }

    /**
     * The archive's schedule. The lease must outlast the four store calls a segment makes at their timeout, or a live holder would
     * be taken over in mid-copy: refused at start otherwise.
     */
    @Bean
    @ConditionalOnProperty(name = "finapp.audit.archive.endpoint")
    AuditArchiveSchedule auditArchiveSchedule(
            AuditArchiveSegments segments,
            AuditArchiveMeters meters,
            @Value("${finapp.audit.archive.endpoint:}") String endpoint,
            @Value("${finapp.audit.archive.bucket:finapp-audit-archive}") String bucket,
            @Value("${finapp.audit.archive.timeout:PT5S}") Duration timeout,
            @Value("${finapp.audit.archive.lease:PT2M}") Duration lease,
            @Value("${finapp.audit.archive.interval:PT1M}") Duration interval,
            @Value("${finapp.audit.archive.batch-size:1000}") int batchSize) {
        if (lease.compareTo(timeout.multipliedBy(4).plusSeconds(10)) <= 0) {
            throw new IllegalStateException("Refusing to start: finapp.audit.archive.lease (" + lease + ") must exceed four store calls at"
                    + " finapp.audit.archive.timeout (" + timeout + ") and ten seconds for the database's, or a live holder is taken"
                    + " over in mid-copy (OPERATIONS_RUNBOOK.md section 11.50)");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpAuditArchiveStore store = new HttpAuditArchiveStore(client, endpoint, bucket, timeout);
        return new AuditArchiveSchedule(new AuditArchival(segments, store, lease, batchSize), interval, meters);
    }
}
