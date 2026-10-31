package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.PostingObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.accounting.cutoff.refused} on the write path's meters (`P14-TSK-004`; {@code PHASE_14_PLAN.md} section
 * 16): registered at zero for every command before the first refusal, and counted only by the cutoff's own
 * observation - the production observer overrides {@link PostingObserver#refusedAtTheCutoff}, whose interface default
 * observes nothing. And {@code finapp.accounting.cutoff.floored} (`P14-TSK-006`, owner decision C3) the same way, through
 * {@link PostingObserver#flooredAtTheCutoff}: a system poster's catch-up across a lock, posted on the first open date.
 */
@DisplayName("the cutoff refusal and floor meters: eager, per command, counted apart (P14-TSK-004, P14-TSK-006)")
class LedgerWriteMetersTest {

    @Test
    @DisplayName("every command's series exists at zero before the first refusal")
    void theSeriesAreEager() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new LedgerWriteMeters(registry);
        for (PostingObserver.Command command : PostingObserver.Command.values()) {
            assertThat(counter(registry, command).count()).isZero();
        }
    }

    @Test
    @DisplayName("a cutoff refusal counts on its command only; a plain refusal does not")
    void aCutoffRefusalCountsOnItsCommand() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LedgerWriteMeters meters = new LedgerWriteMeters(registry);
        meters.observe(PostingObserver.Outcome.REFUSED, Duration.ofMillis(3));
        assertThat(counter(registry, PostingObserver.Command.POSTING).count()).isZero();

        meters.refusedAtTheCutoff(PostingObserver.Command.ADJUSTMENT);
        assertThat(counter(registry, PostingObserver.Command.ADJUSTMENT).count()).isEqualTo(1.0);
        assertThat(counter(registry, PostingObserver.Command.POSTING).count()).isZero();
        assertThat(counter(registry, PostingObserver.Command.REVERSAL).count()).isZero();
    }

    @Test
    @DisplayName("a floor counts on its command only, apart from the refusals; every command's floor series exists at"
            + " zero (P14-TSK-006)")
    void aFloorCountsOnItsCommand() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LedgerWriteMeters meters = new LedgerWriteMeters(registry);
        for (PostingObserver.Command command : PostingObserver.Command.values()) {
            assertThat(floors(registry, command).count()).isZero();
        }
        meters.flooredAtTheCutoff(PostingObserver.Command.POSTING);
        meters.observe(PostingObserver.Outcome.POSTED, Duration.ofMillis(2));
        assertThat(floors(registry, PostingObserver.Command.POSTING).count()).isEqualTo(1.0);
        assertThat(floors(registry, PostingObserver.Command.ADJUSTMENT).count()).isZero();
        assertThat(counter(registry, PostingObserver.Command.POSTING).count()).as("a floor is no refusal").isZero();
    }

    @Test
    @DisplayName("the production observer overrides the interface's do-nothing default")
    void theProductionObserverOverridesTheDefault() throws NoSuchMethodException {
        assertThat(LedgerWriteMeters.class
                        .getDeclaredMethod("refusedAtTheCutoff", PostingObserver.Command.class)
                        .getDeclaringClass())
                .isEqualTo(LedgerWriteMeters.class);
        assertThat(LedgerWriteMeters.CUTOFF_REFUSED).isEqualTo("finapp.accounting.cutoff.refused");
        assertThat(LedgerWriteMeters.class
                        .getDeclaredMethod("flooredAtTheCutoff", PostingObserver.Command.class)
                        .getDeclaringClass())
                .isEqualTo(LedgerWriteMeters.class);
        assertThat(LedgerWriteMeters.CUTOFF_FLOORED).isEqualTo("finapp.accounting.cutoff.floored");
    }

    private static Counter floors(SimpleMeterRegistry registry, PostingObserver.Command command) {
        return registry.get(LedgerWriteMeters.CUTOFF_FLOORED)
                .tag("command", command.name().toLowerCase(Locale.ROOT))
                .counter();
    }

    private static Counter counter(SimpleMeterRegistry registry, PostingObserver.Command command) {
        return registry.get(LedgerWriteMeters.CUTOFF_REFUSED)
                .tag("command", command.name().toLowerCase(Locale.ROOT))
                .counter();
    }
}
