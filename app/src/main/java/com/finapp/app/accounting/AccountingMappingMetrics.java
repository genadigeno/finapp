package com.finapp.app.accounting;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code finapp.accounting.gl.unmapped} (`P14-TSK-003`; {@code PHASE_14_PLAN.md} section 16; alert
 * {@code AccountingMappingGap}): the journal lines in the open period whose purpose (and owner kind) the active GL
 * mapping version does not map. <strong>Registered at zero</strong>, as the backlog entry states: before the periods
 * (`P14-TSK-005`) and the GL read model (`P14-TSK-007`) exist there is no open period and no classified line, so no line
 * can be unmapped; `P14-TSK-010` (the {@code MAPPING_GAP} close check) feeds it. Registered now so the series and its
 * alert exist from the first deployment. A purpose the ledger adds later makes the active version non-total - the
 * activation's refusal ({@code accounting.MappingNotTotal}) holds every new version, and this gauge the version in force.
 *
 * <p>No tag, no id, no amount. Fleet-wide from every instance: aggregate with {@code max()}, never {@code sum()}.
 */
public final class AccountingMappingMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_gl_unmapped}, the plan's name. */
    public static final String UNMAPPED = "finapp.accounting.gl.unmapped";

    public AccountingMappingMetrics(MeterRegistry registry) {
        // A count of lines, a whole number - read as a Number, never through a floating-point signature (INV-MON-01's
        // build rule, NoFloatingPointMoneyRulesTest); Micrometer converts at its own edge.
        Gauge.builder(UNMAPPED, () -> UNMAPPED_LINES_BEFORE_PERIODS)
                .description("Journal lines in the open period whose purpose the active GL mapping version does not map"
                        + " - registered at zero by P14-TSK-003 (no period or classified line exists yet), fed by"
                        + " P14-TSK-010's MAPPING_GAP check. Aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** Before an accounting period exists no line is in an open period, so none is unmapped. */
    private static final Long UNMAPPED_LINES_BEFORE_PERIODS = 0L;
}
