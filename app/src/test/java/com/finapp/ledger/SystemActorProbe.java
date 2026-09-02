package com.finapp.ledger;

import com.finapp.platform.security.Actor;

/**
 * A deliberate violation of {@code onlyTheSecurityContextClaimsTheSystemActor}, used by
 * {@code SystemActorRulesTest}.
 *
 * <p>It models the shortcut the rule exists to remove: a module that needs an actor to construct
 * an audit record, has not established one, and reaches for the constant that is public, constant
 * and right there. Nothing about this code looks wrong in review - which is the point.
 *
 * <p>It claims {@code com.finapp.ledger} for the same reason {@code SystemClockProbe} does: a
 * fixture living in {@code com.finapp.app.architecture} would be indistinguishable from the
 * composition root, and the rule must be shown rejecting an ordinary business module. It is a test
 * class, so {@code DoNotIncludeTests} keeps it out of every production sweep; only the teeth test
 * imports it, explicitly.
 */
@SuppressWarnings("unused")
public final class SystemActorProbe {

    private SystemActorProbe() {}

    /** The shortcut: an actor was needed, none was established, and one was to hand. */
    static Actor whoDidThis() {
        return Actor.SYSTEM;
    }
}
