package com.finapp.app.telemetry;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counts a committed fact once its transaction commits (`P9-TSK-027`, PHASE_9_PLAN.md section 15: counters count
 * committed facts after commit) - {@code CommittedIntakeOutcomes}' idiom, shared: inside a synchronised transaction the
 * count rides {@code afterCommit}, so a rolled-back transaction counts nothing; outside one it counts at once. A count
 * that fails never fails the work.
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable count) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(count);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(count);
            }
        });
    }

    private static void safely(Runnable count) {
        try {
            count.run();
        } catch (RuntimeException telemetryOnly) {
            // Telemetry, never the work's failure.
        }
    }
}
