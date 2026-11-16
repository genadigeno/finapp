package com.finapp.ledger.internal;

/**
 * A type under {@code com.finapp.ledger.internal}, for {@code ModuleBoundaryRulesTest}'s teeth (`P15-REM-001`): no module has
 * an internal package, so the rule's package half is shown red only by a planted one.
 *
 * <p>It declares a package it does not live in, as {@code com.finapp.ledger.SystemClockProbe} does and for the same reason: a
 * module is derived from the package name. It is a test class, so {@code DoNotIncludeTests} keeps it out of every production
 * sweep; only the teeth test imports it, explicitly.
 */
@SuppressWarnings("unused")
public final class InternalTypeProbe {

    private InternalTypeProbe() {}
}
