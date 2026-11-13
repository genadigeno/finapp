package com.finapp.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.platform.audit.archive.AuditArchival;
import com.finapp.platform.audit.archive.AuditArchiveDatabaseException;
import com.finapp.platform.audit.archive.AuditArchiveSegments;
import com.finapp.platform.audit.archive.AuditArchiveStore;
import com.finapp.platform.audit.archive.AuditArchiveStoreException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The audit archive's tick and its meters, with no database and no store (`P16-TSK-010`). */
@DisplayName("the audit archive's tick: bounded, stopping at the first segment it cannot archive, every outcome counted (P16-TSK-010)")
class AuditArchiveScheduleTest {

    @Test
    @DisplayName("a tick archives segments until none is left, counts them and their rows, and stops")
    void aTickArchivesUntilIdle() {
        Fake fake = new Fake(3);
        MeterRegistry registry = new SimpleMeterRegistry();
        AuditArchiveSchedule schedule = schedule(fake, fake, registry);

        assertThat(schedule.archiveOnce()).isEqualTo(3);
        assertThat(fake.marked).isEqualTo(3);
        assertThat(count(registry, "archived")).isEqualTo(3);
        assertThat(registry.get(AuditArchiveMeters.ROWS).counter().count()).isEqualTo(6);
    }

    @Test
    @DisplayName("a tick takes at most twenty segments, then yields to the next tick")
    void aTickIsBounded() {
        Fake fake = new Fake(50);
        AuditArchiveSchedule schedule = schedule(fake, fake, new SimpleMeterRegistry());

        assertThat(schedule.archiveOnce()).isEqualTo(AuditArchiveSchedule.MAX_SEGMENTS);
        assertThat(fake.claims).isEqualTo(AuditArchiveSchedule.MAX_SEGMENTS);
    }

    @Test
    @DisplayName("the store down ends the tick at its first segment, counted, and nothing is marked")
    void anUnavailableStoreEndsTheTick() {
        Fake fake = new Fake(5);
        fake.storeDown = true;
        MeterRegistry registry = new SimpleMeterRegistry();

        assertThat(schedule(fake, fake, registry).archiveOnce()).isZero();
        assertThat(fake.claims).isEqualTo(1);
        assertThat(fake.marked).isZero();
        assertThat(count(registry, "store_unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("a database failure is counted as failed and the tick ends quietly, naming the operation and SQLState alone")
    void aDatabaseFailureIsCounted() {
        Fake fake = new Fake(5);
        fake.claimFails = "55000";
        MeterRegistry registry = new SimpleMeterRegistry();
        AuditArchiveSchedule schedule = schedule(fake, fake, registry);

        schedule.archiveQuietly();

        assertThat(count(registry, AuditArchiveMeters.FAILED)).isEqualTo(1);
    }

    @Test
    @DisplayName("every outcome has its series at zero from the start, and the enabled gauge says whether this instance archives")
    void theSeriesExistEagerly() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Fake fake = new Fake(0);
        new AuditArchiveMeters(registry, fake, false);

        for (String outcome : new String[] {"archived", "store_unavailable", "mismatch", "fenced", "refused", "failed"}) {
            assertThat(count(registry, outcome)).as(outcome).isZero();
        }
        assertThat(registry.find(AuditArchiveMeters.SEGMENTS).tag("outcome", "idle").counter()).as("idle is not an outcome counted").isNull();
        assertThat(registry.get(AuditArchiveMeters.ENABLED).gauge().value()).isZero();
        assertThat(registry.get(AuditArchiveMeters.PENDING).gauge().value()).isEqualTo(4);
        assertThat(registry.get(AuditArchiveMeters.OLDEST).gauge().value()).as("whole seconds").isEqualTo(90);
    }

    @Test
    @DisplayName("an unreadable backlog is no value, never a zero")
    void anUnreadableBacklogIsNoValue() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Fake fake = new Fake(0);
        fake.backlogFails = true;
        new AuditArchiveMeters(registry, fake, true);

        assertThat(registry.get(AuditArchiveMeters.PENDING).gauge().value()).isNaN();
        assertThat(registry.get(AuditArchiveMeters.OLDEST).gauge().value()).isNaN();
        assertThat(registry.get(AuditArchiveMeters.ENABLED).gauge().value()).isEqualTo(1);
    }

    @Test
    @DisplayName("a stopped schedule claims nothing more, and its interval must be positive")
    void stoppingAndBounds() {
        Fake fake = new Fake(5);
        AuditArchiveSchedule schedule = schedule(fake, fake, new SimpleMeterRegistry());
        schedule.start();
        schedule.stop();
        assertThat(schedule.archiveOnce()).as("a stopping instance takes no further segment").isZero();
        assertThat(schedule.isRunning()).isFalse();

        AuditArchival archival = new AuditArchival(fake, fake, Duration.ofMinutes(2), 10);
        AuditArchiveMeters meters = new AuditArchiveMeters(new SimpleMeterRegistry(), fake, true);
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditArchiveSchedule(archival, Duration.ZERO, meters));
    }

    @Test
    @DisplayName("the wiring refuses a blank store, plain http off loopback, and a lease that four store calls at their timeout could outlast")
    void theWiringRefusesWhatCannotWork() {
        Fake fake = new Fake(0);
        AuditArchiveMeters meters = new AuditArchiveMeters(new SimpleMeterRegistry(), fake, true);
        AuditArchiveBeans beans = new AuditArchiveBeans();
        Duration timeout = Duration.ofSeconds(5);

        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> beans.auditArchiveSchedule(fake, meters, " ", "finapp-audit-archive", timeout, Duration.ofMinutes(2),
                        Duration.ofMinutes(1), 10))
                .withMessageContaining("blank");
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> beans.auditArchiveSchedule(fake, meters, "https://archive.example", "finapp-audit-archive", timeout,
                        Duration.ofSeconds(30), Duration.ofMinutes(1), 10))
                .withMessageContaining("finapp.audit.archive.lease");
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> beans.auditArchiveSchedule(fake, meters, "http://archive.example", "finapp-audit-archive", timeout,
                        Duration.ofMinutes(2), Duration.ofMinutes(1), 10))
                .withMessageContaining("finapp.audit.archive.endpoint");
        assertThat(beans.auditArchiveSchedule(fake, meters, "https://archive.example", "finapp-audit-archive", timeout,
                Duration.ofSeconds(31), Duration.ofMinutes(1), 10)).isNotNull();
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static AuditArchiveSchedule schedule(AuditArchiveSegments segments, AuditArchiveStore store, MeterRegistry registry) {
        return new AuditArchiveSchedule(new AuditArchival(segments, store, Duration.ofMinutes(2), 10), Duration.ofMinutes(1),
                new AuditArchiveMeters(registry, segments, true));
    }

    private static double count(MeterRegistry registry, String outcome) {
        return registry.get(AuditArchiveMeters.SEGMENTS).tag("outcome", outcome).counter().count();
    }

    /** A queue of segments of two rows each, and a store in memory. */
    private static final class Fake implements AuditArchiveSegments, AuditArchiveStore {

        private final Map<String, byte[]> objects = new HashMap<>();
        private int left;
        int claims;
        int marked;
        boolean storeDown;
        boolean backlogFails;
        String claimFails;

        Fake(int segments) {
            this.left = segments;
        }

        @Override
        public Optional<Claim> claim(Duration lease, int batchSize) {
            if (claimFails != null) {
                throw new AuditArchiveDatabaseException("claim", claimFails);
            }
            if (left == 0) {
                return Optional.empty();
            }
            claims++;
            return Optional.of(new Claim(claims, UUID.randomUUID()));
        }

        @Override
        public String content(long segment) {
            return "[\"" + segment + "a\"]\n[\"" + segment + "b\"]\n";
        }

        @Override
        public String manifest(long segment) {
            return "{\"segment\" : " + segment + "}";
        }

        @Override
        public void complete(Claim claim, String contentSha256, String manifestSha256) {
            marked++;
            left--;
        }

        @Override
        public Backlog backlog(int cap) {
            if (backlogFails) {
                throw new AuditArchiveDatabaseException("backlog", "08006");
            }
            return new Backlog(4, Duration.ofMillis(90_500));
        }

        @Override
        public Put putIfAbsent(String key, byte[] bytes) {
            if (storeDown) {
                throw new AuditArchiveStoreException("the archive store answered 503 to a write");
            }
            return objects.putIfAbsent(key, bytes) == null ? Put.WRITTEN : Put.ALREADY_PRESENT;
        }

        @Override
        public Optional<byte[]> get(String key, int maxBytes) {
            return Optional.ofNullable(objects.get(key));
        }
    }
}
