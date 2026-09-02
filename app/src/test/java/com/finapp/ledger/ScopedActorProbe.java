package com.finapp.ledger;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;

/**
 * The correct shape, used as {@code SystemActorRulesTest}'s positive control.
 *
 * <p>A module that needs to know who is acting asks, and is told - or is told that nobody was
 * established, which is an error rather than a default. This must pass the rule, or the rule would
 * be rejecting all code that touches actors at all and its teeth test would not reveal it.
 */
// "try": the scope handle is deliberately unread - entering it is the effect.
@SuppressWarnings({"unused", "try"})
public final class ScopedActorProbe {

    private ScopedActorProbe() {}

    static Actor whoDidThis() {
        return SecurityContext.require();
    }

    static void platformInitiatedWork(Runnable work) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem()) {
            work.run();
        }
    }
}
