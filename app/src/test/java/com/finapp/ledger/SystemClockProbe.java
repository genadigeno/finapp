package com.finapp.ledger;

import java.time.Clock;

/**
 * A deliberate violation of {@code onlyTheCompositionRootBuildsASystemClock}, used by
 * {@code NoAmbientTimeRulesTest}.
 *
 * <p><strong>Why it declares a package it does not live in.</strong> That rule is scoped by
 * module, and a module is derived from the package name ({@code ProductionModules.of}). Every
 * other fixture in the architecture suite sits in {@code com.finapp.app.architecture} and is
 * therefore in the composition root — the one module the rule deliberately permits. A fixture
 * there could never demonstrate the rule, because the rule is right to allow it.
 *
 * <p>So this probe claims {@code com.finapp.ledger}, which is what a real module would look
 * like to the rule, while physically living in the architecture suite's own test sources
 * beside the test that uses it. It is a test class, so {@code DoNotIncludeTests} keeps it out
 * of every production sweep; only the teeth test imports it, explicitly.
 *
 * <p>The alternative — asserting a differently-configured copy of the condition — would test
 * something other than the rule that actually runs, which is the failure mode these
 * architecture tests exist to avoid.
 */
@SuppressWarnings("unused")
public final class SystemClockProbe {

    private SystemClockProbe() {}

    static Clock clock() {
        return Clock.systemUTC();
    }
}
