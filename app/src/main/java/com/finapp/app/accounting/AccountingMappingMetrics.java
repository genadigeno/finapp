package com.finapp.app.accounting;

import com.finapp.accounting.GlMappingGap;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * {@code finapp.accounting.gl.unmapped} (`P14-TSK-003` registered it at zero; `P14-TSK-007` feeds it;
 * {@code PHASE_14_PLAN.md} section 16; alert {@code AccountingMappingGap}; {@code INV-ACC-06}): the journal lines dated in
 * the open period(s) whose purpose and owner kind the active GL mapping version does not map - the lines the GL shows in
 * its unmapped bucket and that make GL = ledger disagree, never silently dropped. Read from the database
 * ({@link GlMappingGap}, on a {@link GlSnapshots} read-only snapshot), so every instance reports the same value -
 * aggregate with {@code max()}, never {@code sum()}. In the normal state - the version total over the ledger's purposes -
 * the reading is 0 without a journal read; 0 with no active version (the GL then answers
 * {@code accounting.NoActiveMapping}, not a gap). Bounded by {@link AccountingGaugeRead} on the snapshots it is given, whose 10 s suits a journal read; cached for 60 s; unreadable is no value (NaN at Micrometer's edge),
 * never a zero for a read that failed. No tag, no id, no amount; a count read as a {@code Long} - never a floating-point
 * signature ({@code NoFloatingPointMoneyRulesTest}).
 */
public final class AccountingMappingMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_gl_unmapped}, the plan's name. */
    public static final String UNMAPPED = "finapp.accounting.gl.unmapped";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    private final AccountingGaugeRead<Long> read;

    public AccountingMappingMetrics(GlMappingGap gap, GlSnapshots snapshots, Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(gap, "gap");
        this.read = new AccountingGaugeRead<>("The unmapped GL lines", snapshots, clock, MIN_REFRESH, gap::unmappedLines);
        Gauge.builder(UNMAPPED, this::unmappedLines)
                .description("Journal lines in the open period whose purpose the active GL mapping version does not map"
                        + " - shown in the GL's unmapped bucket, never dropped; 0 under a total version or none, no value"
                        + " when unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The cached reading; {@code null} - no value - when the read failed. */
    Long unmappedLines() {
        return read.current();
    }
}
