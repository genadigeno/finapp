package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.app.database.ReportingPool;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import com.finapp.accounting.AccountingStorageException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The production wiring (`P15-TSK-021`; C13; {@code INV-ACC-14}; {@code THREAT_MODEL.md} T7, T44): <strong>report
 * generation opens its snapshot from the reporting pool, as {@code finapp_reporting}, and never asks the application's
 * pool - not even when the reporting pool cannot connect.</strong>
 *
 * <p>The real context over a closed port: the writer's data source wrapped to record whether this thread asked it
 * ({@link WriterAskedSource}), the reporting pool the production bean, built from production's file. A run's generation
 * and a FINAL run's regeneration each fail <em>at the reporting pool</em> - the snapshot cannot open, so no content is
 * produced and no run completes - and the writer is never asked: there is no fallback. What the role does at a real
 * database (a planted write refused, a run generated) is {@code ReportingRoleDatabaseTest}'s, owed.
 */
@Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("report generation reads as the reporting role and never falls back to the writer (P15-TSK-021)")
class ReportGenerationReadsAsTheReportingRoleTest {

    private static final WriterAskedSource WRITER = new WriterAskedSource();
    private static final IdGenerator IDS = new IdGenerator(
            Clock.fixed(Instant.parse("2026-11-08T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());

    @TestConfiguration
    static class RecordingTheWriter {

        @Bean
        static BeanPostProcessor recordingWriter() {
            return WRITER.postProcessor();
        }
    }

    @Autowired private ReportRunGenerator generator;
    @Autowired private ReportDefinitions definitions;

    @Test
    @DisplayName("the reporting pool is the reporting role's, sized by production's file")
    void thePoolIsTheReportingRoles() {
        ReportingPool reportingPool = WRITER.reportingPool();
        assertThat(reportingPool).as("the context built the production pool").isNotNull();
        assertThat(reportingPool.username()).isEqualTo("finapp_reporting");
        assertThat(reportingPool.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a run's generation opens the reporting pool alone: unreachable, it fails closed - no content, no"
            + " completion - and the writer is never asked")
    void aRunsGenerationFailsClosedAtTheReportingPool() {
        ReportDefinition trialBalance = definitions.current("TRIAL_BALANCE").orElseThrow();
        ReportRunStore.RunRow held = generating(trialBalance);
        WRITER.callerIs(Thread.currentThread());
        Throwable refused = catchThrowable(() -> generator.generateAndComplete(held,
                new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE)));
        assertThat(refused).as("the snapshot's own failure, which leaves the run GENERATING")
                .isInstanceOf(AccountingStorageException.class).hasMessageContaining("opening a report snapshot");
        assertThat(WRITER.reportingAskedByTheCaller()).as("the snapshot asked the reporting pool, once").hasSize(1);
        assertThat(WRITER.askedByTheCaller()).as("no fallback to the writer, and no completion written").isEmpty();
    }

    @Test
    @DisplayName("a FINAL run's regeneration opens the reporting pool alone, and the writer is never asked")
    void aRegenerationReadsAsTheReportingRole() {
        WRITER.callerIs(Thread.currentThread());
        Throwable refused = catchThrowable(() -> generator.regenerate(ReportRunId.next(IDS)));
        assertThat(refused).isInstanceOf(AccountingStorageException.class).hasMessageContaining("opening a report snapshot");
        assertThat(WRITER.reportingAskedByTheCaller()).as("the regeneration's snapshot asked the reporting pool").hasSize(1);
        assertThat(WRITER.askedByTheCaller()).isEmpty();
    }

    /** A PROVISIONAL run its caller holds, of a definition the build holds. */
    private static ReportRunStore.RunRow generating(ReportDefinition definition) {
        Instant now = Instant.parse("2026-11-08T09:00:00Z");
        return new ReportRunStore.RunRow(ReportRunId.next(IDS),
                new ReportRunStore.RunKey(definition.code(), definition.version(), AccountingPeriodId.next(IDS),
                        "parameters"),
                1, YearMonth.of(2026, 10), 1, RunParameters.none(), RunBasis.PROVISIONAL, RunStatus.GENERATING,
                Optional.empty(), Optional.empty(), Optional.empty(), now, "operator", "EMPLOYEE", now, 1,
                now.plusSeconds(300), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                CorrelationId.generate(IDS));
    }
}
