package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.PinnedMapping;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which version a month is read under (`P14-TSK-007`; {@code INV-ACC-06}, {@code INV-ACC-07}): a locked period's pin,
 * an open period's - or an unopened month's - version in force, and {@code NoActiveMapping} when there is none.
 */
@DisplayName("the version a GL month is read under (P14-TSK-007)")
class GlPinsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final YearMonth DECEMBER = YearMonth.of(2026, 12);

    private final InMemoryGlMappingStore store = new InMemoryGlMappingStore();
    private final Map<YearMonth, GlPins.PeriodPin> periods = new HashMap<>();
    private final GlPins pins = new GlPins(store, (snapshot, month) -> Optional.ofNullable(periods.get(month)));

    @Test
    @DisplayName("no version activated: NoActiveMapping - the GL is not readable until the first four-eyes activation")
    void noVersionIsNoActiveMapping() {
        assertThatThrownBy(() -> pins.forPeriod(null, NOVEMBER)).isInstanceOf(GlPins.NoActiveMapping.class)
                .hasMessageContaining("2026-11");
    }

    @Test
    @DisplayName("an open period - or a month no period reached - reads the version in force for the month")
    void anOpenPeriodReadsTheVersionInForce() {
        GlMappingVersionId first = activate(1, NOVEMBER);
        periods.put(NOVEMBER, new GlPins.PeriodPin(PeriodStatus.OPEN, Optional.empty()));
        PinnedMapping open = pins.forPeriod(null, NOVEMBER);
        assertThat(open.versionId()).isEqualTo(first);
        assertThat(open.version()).isEqualTo(1);
        assertThat(open.periodStatus()).contains(PeriodStatus.OPEN);
        assertThat(open.content().rules()).hasSize(40);
        PinnedMapping unopened = pins.forPeriod(null, DECEMBER);
        assertThat(unopened.versionId()).isEqualTo(first);
        assertThat(unopened.periodStatus()).isEmpty();
        assertThatThrownBy(() -> pins.forPeriod(null, NOVEMBER.minusMonths(1)))
                .as("before the first version's month nothing is in force").isInstanceOf(GlPins.NoActiveMapping.class);
    }

    @Test
    @DisplayName("a locked period reads the version it pinned, even after a successor took effect from a later month")
    void aLockedPeriodReadsItsPin() {
        GlMappingVersionId first = activate(1, NOVEMBER);
        periods.put(NOVEMBER, new GlPins.PeriodPin(PeriodStatus.LOCKED, Optional.of(first)));
        store.retire(null, first, DECEMBER);
        GlMappingVersionId second = activate(2, DECEMBER);
        assertThat(pins.forPeriod(null, NOVEMBER).versionId()).isEqualTo(first);
        assertThat(pins.forPeriod(null, DECEMBER).versionId()).isEqualTo(second);
        periods.put(NOVEMBER, new GlPins.PeriodPin(PeriodStatus.CLOSED, Optional.of(first)));
        assertThat(pins.forPeriod(null, NOVEMBER).periodStatus()).contains(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("a period locked with no version in force pins nothing: NoActiveMapping, never the version in force later")
    void aPeriodLockedWithoutAVersionIsNoActiveMapping() {
        periods.put(NOVEMBER, new GlPins.PeriodPin(PeriodStatus.LOCKED, Optional.empty()));
        activate(1, NOVEMBER);
        assertThatThrownBy(() -> pins.forPeriod(null, NOVEMBER)).isInstanceOf(GlPins.NoActiveMapping.class)
                .hasMessageContaining("locked");
    }

    private GlMappingVersionId activate(int number, YearMonth from) {
        GlMappingVersionId id = GlMappingVersionId.next(IDS);
        Actor proposer = new Actor("accountant-" + number, ActorType.EMPLOYEE);
        store.insertProposal(null, new GlMappingStore.NewProposal(id, number, ReferenceGlMapping.version(from), proposer,
                "the reference", CorrelationId.of("flow-" + number), UUID.randomUUID()));
        assertThat(store.decide(null, id, GlMappingVersionStatus.ACTIVE, new Actor("controller", ActorType.EMPLOYEE),
                "approved")).isTrue();
        return id;
    }
}
