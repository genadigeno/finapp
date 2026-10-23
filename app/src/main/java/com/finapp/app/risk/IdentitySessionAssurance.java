package com.finapp.app.risk;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Session;
import com.finapp.identity.SessionStore;
import com.finapp.platform.security.Actor;
import com.finapp.risk.RiskAssurance;
import com.finapp.risk.SessionAssurance;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SessionAssurance} over identity's session - its first implementation (`P13-TSK-008`; {@code PHASE_13_PLAN.md}
 * sections 12.2, 12.3; ADR-0108 decision 3): what makes a {@code CHALLENGE} satisfiable. Until this, the port was
 * unwired and every challenge was unsatisfied ({@code P13-TSK-004}'s MINOR 7).
 *
 * <p><strong>The read.</strong> The acting session is the one the request presented ({@link ActingSession}); its
 * assurance is trusted only after a <strong>live re-read through the caller's unit of work</strong> -
 * {@link SessionStore#findLiveFor} for the identity, the presented session found among them by id - so the answer is
 * the database's, on the database's clock, in the judged command's own transaction and after its locks (the seam's
 * in-lock contract): a session revoked, rotated by a step-up, or expired on any instance since the door answered is no
 * session at all, and a {@code CHALLENGE} over it is unsatisfied. Read once per evaluation (T-eval and T-advise both
 * read it whatever rule fired), inside T-eval's timed savepoint through {@code PlatformRiskInputs}.
 *
 * <p><strong>The levels.</strong> Identity's three ({@link AssuranceLevel}) to risk's two ({@link RiskAssurance}):
 * {@code PASSWORD} is {@code SINGLE_FACTOR}; {@code MULTI_FACTOR} and {@code STRONG} are {@code MULTI_FACTOR} - a
 * phishing-resistant authenticator satisfies a step-up, as {@code AssuranceLevel.atLeast} says everywhere else.
 */
public final class IdentitySessionAssurance implements SessionAssurance {

    private final SessionStore<Connection> sessions;

    public IdentitySessionAssurance(SessionStore<Connection> sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public Optional<RiskAssurance> of(Connection unitOfWork, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        return ActingSession.of(actor)
                .flatMap(presented -> sessions.findLiveFor(unitOfWork, presented.identityId()).stream()
                        .filter(live -> live.id().equals(presented.id()))
                        .findFirst())
                .map(Session::assurance)
                .map(IdentitySessionAssurance::assurance);
    }

    /** Identity's level as risk reads it. */
    static RiskAssurance assurance(AssuranceLevel level) {
        return switch (Objects.requireNonNull(level, "level")) {
            case PASSWORD -> RiskAssurance.SINGLE_FACTOR;
            case MULTI_FACTOR, STRONG -> RiskAssurance.MULTI_FACTOR;
        };
    }
}
