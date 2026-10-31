package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reading behind {@code finapp.accounting.gl.unmapped} (`P14-TSK-007`; {@code INV-ACC-06}): the lines in the open
 * period(s) the active version does not map - none without a version, none and no journal read under a total one, and
 * otherwise counted from the open period's start, never before the version's own month.
 */
@DisplayName("the unmapped lines in the open period (P14-TSK-007)")
class GlMappingGapTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final YearMonth DECEMBER = YearMonth.of(2026, 12);

    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryJournal journal = new InMemoryJournal();
    private final GlMappingGap gap = new GlMappingGap(mappings, unused -> ReferenceGlMapping.agreeingChart(p -> true),
            periods, journal);

    @Test
    @DisplayName("no active version: 0 - the GL answers NoActiveMapping, which is not a mapping gap")
    void noVersionIsNoGap() {
        suspense(LocalDate.of(2026, 11, 2));
        assertThat(gap.unmappedLines(null)).isZero();
        assertThat(journal.reads()).isZero();
    }

    @Test
    @DisplayName("a total version: 0 without reading the journal - the normal state costs nothing")
    void aTotalVersionReadsNoJournal() {
        activate(ReferenceGlMapping.version(NOVEMBER));
        suspense(LocalDate.of(2026, 11, 2));
        assertThat(gap.unmappedLines(null)).isZero();
        assertThat(journal.reads()).isZero();
    }

    @Test
    @DisplayName("a purpose the active version does not map: its lines counted from the version's month when no period"
            + " exists - never the lines dated before it, never the mapped ones")
    void unmappedLinesAreCountedFromTheVersionsMonth() {
        activate(ReferenceGlMapping.without(NOVEMBER, Set.of("SUSPENSE_UNMATCHED")));
        suspense(LocalDate.of(2026, 10, 31));
        suspense(LocalDate.of(2026, 11, 2));
        suspense(LocalDate.of(2026, 12, 9));
        assertThat(gap.unmappedLines(null)).as("one line each in November and December, the CASH_AT_BANK sides mapped")
                .isEqualTo(2);
        assertThat(GlMappingGap.unmappedKeys(ReferenceGlMapping.agreeingChart(p -> true),
                ReferenceGlMapping.without(NOVEMBER, Set.of("SUSPENSE_UNMATCHED"))))
                .containsExactly("SUSPENSE_UNMATCHED/SUSPENSE");
    }

    @Test
    @DisplayName("with periods, the open period's start: November locked, December open - only December's line counts;"
            + " every period locked - the month after the latest")
    void unmappedLinesAreCountedFromTheOpenPeriod() {
        GlMappingVersionId version = activate(ReferenceGlMapping.without(NOVEMBER, Set.of("SUSPENSE_UNMATCHED")));
        suspense(LocalDate.of(2026, 11, 2));
        suspense(LocalDate.of(2026, 12, 9));
        open(NOVEMBER);
        AccountingPeriodStore.PeriodRow november = periods.month(NOVEMBER).orElseThrow();
        assertThat(periods.markLocked(null, november.id(), 1, new Actor("accountant", ActorType.EMPLOYEE),
                Optional.of(new AccountingPeriodStore.Pin(version, 1)))).isTrue();
        assertThat(gap.unmappedLines(null)).as("November locked, no period open: from December").isEqualTo(1);
        open(DECEMBER);
        assertThat(gap.unmappedLines(null)).as("December the open period").isEqualTo(1);
    }

    private void suspense(LocalDate day) {
        journal.post(day, "EUR", 2, 100, "SUSPENSE_UNMATCHED", "SUSPENSE", "CASH_AT_BANK", "OPERATIONAL");
    }

    private void open(YearMonth month) {
        periods.insertIfAbsent(null, new AccountingPeriodStore.NewPeriod(AccountingPeriodId.next(IDS), month,
                CorrelationId.of("flow-open")));
    }

    private GlMappingVersionId activate(GlMappingVersion content) {
        GlMappingVersionId id = GlMappingVersionId.next(IDS);
        mappings.insertProposal(null, new GlMappingStore.NewProposal(id, 1, content,
                new Actor("accountant", ActorType.EMPLOYEE), "the reference", CorrelationId.of("flow-1"),
                UUID.randomUUID()));
        assertThat(mappings.decide(null, id, GlMappingVersionStatus.ACTIVE, new Actor("controller", ActorType.EMPLOYEE),
                "approved")).isTrue();
        return id;
    }
}
