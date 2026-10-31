package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.GlMappingGap;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.GlMappingVersionStatus;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.accounting.gl.unmapped} fed (`P14-TSK-007`; {@code PHASE_14_PLAN.md} section 16): read from the database
 * on a read-only snapshot - the lines in the open period the active version does not map - cached 60 s, and no value
 * (never a zero) when the read fails.
 */
@DisplayName("finapp.accounting.gl.unmapped, fed from the journal (P14-TSK-007)")
class AccountingMappingMetricsTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T10:00:00Z"));
    private final IdGenerator ids = new IdGenerator(clock, new SecureRandom());
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryJournal journal = new InMemoryJournal();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("an unmapped purpose's lines in the open period are the reading; cached for 60 s, then re-read")
    void theUnmappedLinesAreTheReading() {
        activate(ReferenceGlMapping.without(NOVEMBER, Set.of("SUSPENSE_UNMATCHED")));
        suspense();
        metrics(new GlSnapshots(() -> new SnapshotSession().connection()));
        assertThat(gauge()).isEqualTo(1.0);
        suspense();
        clock.advance(Duration.ofSeconds(59));
        assertThat(gauge()).as("cached").isEqualTo(1.0);
        clock.advance(Duration.ofSeconds(2));
        assertThat(gauge()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a total version reads zero; no version reads zero")
    void zeroUnderATotalVersionOrNone() {
        suspense();
        metrics(new GlSnapshots(() -> new SnapshotSession().connection()));
        assertThat(gauge()).isZero();
        activate(ReferenceGlMapping.version(NOVEMBER));
        clock.advance(Duration.ofMinutes(2));
        assertThat(gauge()).isZero();
    }

    @Test
    @DisplayName("an unreadable database is no value, never a zero")
    void anUnreadableReadIsNoValue() {
        metrics(new GlSnapshots(() -> {
            throw new java.sql.SQLException("down", "08006");
        }));
        assertThat(gauge()).isNaN();
    }

    private void metrics(GlSnapshots snapshots) {
        new AccountingMappingMetrics(new GlMappingGap(mappings, unused -> ReferenceGlMapping.agreeingChart(p -> true),
                new InMemoryAccountingPeriodStore(), journal), snapshots, clock, registry);
    }

    private double gauge() {
        return registry.get(AccountingMappingMetrics.UNMAPPED).gauge().value();
    }

    private void suspense() {
        journal.post(LocalDate.of(2026, 11, 18), "EUR", 2, 100, "SUSPENSE_UNMATCHED", "SUSPENSE", "CASH_AT_BANK",
                "OPERATIONAL");
    }

    private void activate(com.finapp.accounting.GlMappingVersion content) {
        GlMappingVersionId id = GlMappingVersionId.next(ids);
        mappings.insertProposal(null, new GlMappingStore.NewProposal(id, 1, content,
                new Actor("accountant-1", ActorType.EMPLOYEE), "the reference", CorrelationId.of("flow-1"),
                UUID.randomUUID()));
        mappings.decide(null, id, GlMappingVersionStatus.ACTIVE, new Actor("controller-1", ActorType.EMPLOYEE), "ok");
    }

    /** A clock the suite moves. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
