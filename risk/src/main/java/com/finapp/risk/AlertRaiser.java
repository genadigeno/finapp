package com.finapp.risk;

import java.sql.Connection;
import java.util.List;

/**
 * How a raiser inside another flow raises its alerts (`P13-TSK-013`; {@code PHASE_13_PLAN.md} sections 7.1, 12.6;
 * {@code INV-RSK-06}) - T-eval after the seal, T-mon's profile leg after the evaluation. One call per transaction, as
 * {@link Alerts#raiseAll} requires (`-015`: every birth in {@link AlertKey} order, before any fold): the account-takeover
 * alerts today, `-014`'s abuse alerts beside them in the same list. Production is {@code alerts::raiseAll}; a port so the
 * evaluation's and the recompute's suites can watch, or fail, the raise without the alert machine.
 */
@FunctionalInterface
public interface AlertRaiser {

    /** Raises nothing - the suites that judge no account-takeover rule. */
    AlertRaiser NONE = (unitOfWork, alerts) -> List.of();

    /** Raises {@code alerts} on the caller's unit of work - births, then folds; a conflict is an answer. */
    List<Alerts.Raised> raiseAll(Connection unitOfWork, List<NewAlert> alerts);
}
