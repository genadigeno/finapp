package com.finapp.app.telemetry;

import java.time.Duration;

/**
 * The bounds Phase 14's accounting alerts are drawn against (`P14-TSK-020`; {@code PHASE_14_PLAN.md} section 16; {@link RiskObjectives}' shape) - declared once, here, so
 * {@code infra/prometheus/rules/accounting.yml}, the beans that read past them and {@code AccountingAlertRulesTest} cannot drift apart. <strong>Each is a default a qualified
 * person should confirm</strong>, not a figure any regulator or calendar states: the close's target is an owner item in {@code CURRENT_STATE.md} section Next Task.
 *
 * <ul>
 *   <li><strong>The close's week</strong>: {@code period.open.age} counts whole days past the first day a month may lock ({@code LockGrace}, two business days after its end). Seven
 *       calendar days hold five business days whichever weekday the grace ends on, so a month locked, judged, proposed and approved inside a working week never reads past it.
 *   <li><strong>The break's and the proposal's week</strong>: the same seven days. A break still {@code OPEN}, or a prior-period adjustment still {@code PROPOSED}, a close's week after it
 *       was born has either blocked a month or been forgotten; the database's clock measures it.
 *   <li><strong>The unreadable wait</strong>: fifteen minutes, sixty floors of the gauges' 15 s, the wait {@code AccountingCloseRunStallUnreadable} was opened with.
 * </ul>
 */
public final class AccountingObjectives {

    /** How many days past its grace the oldest unclosed month may stand before {@code AccountingCloseOverdue}. */
    public static final long CLOSE_OVERDUE_DAYS = 7;

    /** How long a break may stand {@code OPEN} - {@code finapp.accounting.break.aged-after}'s default. */
    public static final String BREAK_AGED_AFTER = "P7D";

    /** How long a prior-period adjustment may stand {@code PROPOSED} - {@code finapp.accounting.adjustment.standing-after}'s default. */
    public static final String ADJUSTMENT_STANDING_AFTER = "P7D";

    /** A gauge read's statement timeout - {@code finapp.accounting.gauges.statement-timeout}'s default. */
    public static final String GAUGE_STATEMENT_TIMEOUT = "PT2S";

    /** How long a gauge may read no value before it is ticketed. */
    public static final Duration UNREADABLE_WAIT = Duration.ofMinutes(15);

    private AccountingObjectives() {}
}
