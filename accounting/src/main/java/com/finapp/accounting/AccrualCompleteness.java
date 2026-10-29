package com.finapp.accounting;

/**
 * How far lending's and BNPL's daily accruals have run (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 3, 13.5; owner
 * decision C3) - the accrued-through dates the {@link CloseCheckCode#ACCRUALS_COMPLETE} check compares with the period's
 * end, because a period may not close while interest earned in it is still unposted.
 *
 * <p><strong>Read-only.</strong> <strong>Declared empty</strong>: `P14-TSK-011` gives it its methods and {@code app} its
 * implementation.
 */
public interface AccrualCompleteness {
}
