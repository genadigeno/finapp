package com.finapp.app.risk;

import static com.finapp.app.risk.RiskEvaluationWorld.IDS;
import static com.finapp.app.risk.RiskEvaluationWorld.UNTOUCHABLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Session;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.RiskAssurance;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The acting session's assurance, as risk reads it (`P13-TSK-008`; {@code PHASE_13_PLAN.md} section 12.3;
 * {@code INV-IDN-03}): the session the request presented, trusted only after a live re-read through the caller's unit
 * of work; identity's three levels to risk's two; nothing outside a request, nothing for another identity's actor.
 */
@DisplayName("the session's assurance for risk: the presented session, re-read live (P13-TSK-008)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class IdentitySessionAssuranceTest {

    private final UUID identity = IDS.next();
    private final Actor actor = new Actor(identity.toString(), ActorType.CUSTOMER);
    private final List<String> asked = new ArrayList<>();

    @Test
    @DisplayName("identity's levels to risk's: PASSWORD is SINGLE_FACTOR; MULTI_FACTOR and STRONG are MULTI_FACTOR")
    void theLevelsMap() {
        assertThat(IdentitySessionAssurance.assurance(AssuranceLevel.PASSWORD)).isEqualTo(RiskAssurance.SINGLE_FACTOR);
        assertThat(IdentitySessionAssurance.assurance(AssuranceLevel.MULTI_FACTOR)).isEqualTo(RiskAssurance.MULTI_FACTOR);
        assertThat(IdentitySessionAssurance.assurance(AssuranceLevel.STRONG)).isEqualTo(RiskAssurance.MULTI_FACTOR);
    }

    @Test
    @DisplayName("the presented session, live in the caller's transaction, answers its own level - and the store is"
            + " asked through the caller's unit of work, once")
    void thePresentedLiveSessionAnswers() throws Exception {
        Session mfa = RiskEvaluationWorld.session(identity, AssuranceLevel.MULTI_FACTOR, null);
        Session password = RiskEvaluationWorld.session(identity, AssuranceLevel.PASSWORD, null);
        IdentitySessionAssurance assurance =
                new IdentitySessionAssurance(RiskEvaluationWorld.liveSessions(asked, mfa, password));
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(password)) {
            assertThat(assurance.of(UNTOUCHABLE, actor))
                    .as("the identity's other, stronger session is not the acting one").contains(RiskAssurance.SINGLE_FACTOR);
        }
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(mfa)) {
            assertThat(assurance.of(UNTOUCHABLE, actor)).contains(RiskAssurance.MULTI_FACTOR);
        }
        assertThat(asked).containsExactly("findLiveFor", "findLiveFor");
    }

    @Test
    @DisplayName("a session the live re-read no longer finds - revoked, rotated by a step-up, expired - is no session"
            + " (INV-IDN-03): empty, so a challenge is unsatisfied")
    void aSessionNoLongerLiveIsNone() throws Exception {
        Session revoked = RiskEvaluationWorld.session(identity, AssuranceLevel.MULTI_FACTOR, null);
        IdentitySessionAssurance assurance = new IdentitySessionAssurance(RiskEvaluationWorld.liveSessions(asked));
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(revoked)) {
            assertThat(assurance.of(UNTOUCHABLE, actor)).isEmpty();
        }
    }

    @Test
    @DisplayName("no request being served, or a request presenting another identity's session: empty, and the store is"
            + " never asked")
    void noRequestOrAnotherIdentityIsNone() throws Exception {
        Session strangers = RiskEvaluationWorld.session(IDS.next(), AssuranceLevel.MULTI_FACTOR, null);
        IdentitySessionAssurance assurance =
                new IdentitySessionAssurance(RiskEvaluationWorld.liveSessions(asked, strangers));
        assertThat(assurance.of(UNTOUCHABLE, actor)).isEmpty();
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(strangers)) {
            assertThat(assurance.of(UNTOUCHABLE, actor)).isEmpty();
            assertThat(ActingSession.of(actor)).isEmpty();
        }
        assertThat(asked).isEmpty();
    }
}
