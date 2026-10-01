package com.finapp.app.telemetry;

import com.finapp.reconciliation.BreakRegister;
import java.sql.Connection;
import java.util.Objects;

/**
 * {@code finapp.reconciliation.break.raised} at the one door every break is born through
 * (`P8-TSK-024`): a decorator over reconciliation's {@link BreakRegister} - the
 * {@code MeteredKycCaseStore} precedent - counting a raise this transaction CREATED, after it
 * commits, never a converged one (ten sweepers raising one discrepancy count one).
 */
public final class MeteredBreakRegister implements BreakRegister {

    private final BreakRegister delegate;
    private final ReconciliationOutcomeMeters meters;

    public MeteredBreakRegister(BreakRegister delegate, ReconciliationOutcomeMeters meters) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    @Override
    public Raised raise(Connection unitOfWork, NewBreak newBreak) {
        Raised raised = delegate.raise(unitOfWork, newBreak);
        if (raised.created()) {
            meters.countRaised(newBreak.type(), raised.severity());
        }
        return raised;
    }
}
