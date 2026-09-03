package com.finapp.ledger;

/**
 * A deliberate violation of {@code noMethodIsSynchronized}, used by
 * {@code NoSingleInstanceAssumptionRulesTest}.
 *
 * <p>The shape that looks careful and is not: a method guarding shared state with a monitor held
 * inside one JVM. With N instances the other nine enter it freely.
 *
 * <p>It claims {@code com.finapp.ledger} for the same reason the other architecture probes do - a
 * fixture in {@code com.finapp.app.architecture} would sit in the composition root and could not
 * demonstrate a rule scoped by module. It is a test class, so {@code DoNotIncludeTests} keeps it
 * out of every production sweep.
 */
@SuppressWarnings("unused")
public final class SynchronizedMethodProbe {

    private int balanceInMinorUnits;

    public synchronized void credit(int amount) {
        balanceInMinorUnits += amount;
    }
}
