package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.AlertFolding;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertObserver;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.AlertWindow;
import com.finapp.risk.Alerts;
import com.finapp.risk.JdbcRiskAlertStore;
import com.finapp.risk.NewAlert;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.RuleKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityWindowKey;
import com.finapp.risk.WindowKind;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The suppression door, end to end (`P13-TSK-018`; `-015`'s machine; {@code PHASE_13_PLAN.md} sections 9, 11;
 * {@code INV-RSK-07}, {@code INV-AUD-04}; the routed defect's proof over HTTP): an analyst - {@code CUSTOMER}-typed, as
 * the session establishes every identity, which {@code risk V014} admits - proposes a reasoned suppression of an open
 * alert ({@code 201}); the proposer's own approval is {@code 403 risk.SelfApprovalRefused}, recorded {@code FAILED}; a
 * second analyst's password-only session {@code 403 identity.AssuranceRequired}; from {@code MULTI_FACTOR} the approval
 * suppresses ({@code 200}), audited {@code risk.AlertSuppressed}; the same key replayed answers the alert as it stands;
 * an attached alert {@code 409 risk.AlertNotOpen}; a card-shaped reason {@code 422 risk.ReasonRequired}; a risk officer
 * {@code 403}. <strong>Written and owed</strong> (C5).
 *
 * <p>An open alert is raised by the machine with no folding - the drained backlog's shape (the composition's raises fold
 * into a case at once); the attached one by the composition's own {@link Alerts}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the suppression door over HTTP: two persons, four eyes, never the system (P13-TSK-018)")
class AlertSuppressionApiTest {

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private RiskPolicyAdministration riskPolicyAdministration;
    @Autowired private Alerts composedAlerts;

    private RiskOperatorSessions http;

    @BeforeEach
    void sessions() {
        http = new RiskOperatorSessions(port, authorization);
    }

    @Test
    @DisplayName("201 PROPOSED; the proposer's approval 403 SelfApprovalRefused recorded FAILED; a password session 403"
            + " AssuranceRequired; from MULTI_FACTOR 200 SUPPRESSED by two CUSTOMER-typed persons; the key replayed")
    void twoPersonsSuppress() throws Exception {
        RiskAlert alert = openAlert();
        String door = "/v1/operator/risk/alerts/" + alert.id().value() + "/suppression";
        RiskOperatorSessions.Session alice = http.sessionWith(RoleName.RISK_ANALYST, true);
        HttpResponse<String> proposed = http.post(door, "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":"
                + "\"the customer's payroll\"}", alice.token(), "proposal-" + alert.id().value());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"status\":\"PROPOSED\"").doesNotContain(alice.identity().toString());

        HttpResponse<String> own = http.post(door + "/approval", "{\"reason\":\"mine\"}", alice.token());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("risk.SelfApprovalRefused");
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.SelfApprovalRefused' AND outcome = 'FAILED' AND target_id = '" + alert.id().value() + "'"))
                .isEqualTo(1);

        RiskOperatorSessions.Session bobPassword = http.sessionWith(RoleName.RISK_ANALYST, false);
        HttpResponse<String> stepUp = http.post(door + "/approval", "{\"reason\":\"ok\"}", bobPassword.token());
        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(403);
        assertThat(stepUp.body()).contains("identity.AssuranceRequired");

        RiskOperatorSessions.Session bob = http.sessionOf(bobPassword, true);
        HttpResponse<String> approved = http.post(door + "/approval", "{\"reason\":\"agreed: payroll\"}",
                bob.token());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"SUPPRESSED\"").contains("\"status\":\"DECIDED\"");
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM risk.risk_alert WHERE id = '" + alert.id().value()
                + "' AND suppression_proposed_by_type = 'CUSTOMER' AND suppression_decided_by_type = 'CUSTOMER'"))
                .as("both persons as the session establishes them - V014's widening").isEqualTo(1);
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.AlertSuppressed' AND target_id = '" + alert.id().value() + "'")).isEqualTo(1);

        HttpResponse<String> replayed = http.post(door, "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":"
                + "\"the customer's payroll\"}", alice.token(), "proposal-" + alert.id().value());
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(201);
        assertThat(replayed.body()).as("the key's replay answers the alert as it stands").contains(
                "\"status\":\"SUPPRESSED\"");
    }

    @Test
    @DisplayName("the refusals: an attached alert 409 AlertNotOpen; a card-shaped reason 422 ReasonRequired; an unknown"
            + " alert 404; a risk officer 403; no session 401")
    void theRefusals() throws Exception {
        RiskOperatorSessions.Session analyst = http.sessionWith(RoleName.RISK_ANALYST, false);
        RiskAlert attached = attachedAlert();
        HttpResponse<String> notOpen = http.post("/v1/operator/risk/alerts/" + attached.id().value() + "/suppression",
                "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":\"seen\"}", analyst.token());
        assertThat(notOpen.statusCode()).as(notOpen.body()).isEqualTo(409);
        assertThat(notOpen.body()).contains("risk.AlertNotOpen");

        RiskAlert open = openAlert();
        String door = "/v1/operator/risk/alerts/" + open.id().value() + "/suppression";
        HttpResponse<String> card = http.post(door, "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":"
                + "\"card 4111 1111 1111 1111\"}", analyst.token());
        assertThat(card.statusCode()).as(card.body()).isEqualTo(422);
        assertThat(card.body()).contains("risk.ReasonRequired");
        assertThat(http.post("/v1/operator/risk/alerts/" + RiskOperatorSessions.IDS.next() + "/suppression",
                "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":\"none\"}", analyst.token()).statusCode())
                .isEqualTo(404);
        RiskOperatorSessions.Session officer = http.sessionWith(RoleName.RISK_OFFICER, false);
        assertThat(http.post(door, "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":\"another desk\"}",
                officer.token()).statusCode()).isEqualTo(403);
        assertThat(http.post(door, "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":\"no one\"}", null).statusCode())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------ fixtures

    /** An open alert of a fresh party - raised by the machine with no folding, the drained backlog's shape. */
    private RiskAlert openAlert() {
        Alerts unfolded = new Alerts(new JdbcRiskAlertStore(), AlertFolding.NONE, (uow, actor) -> Optional.empty(),
                RiskSubjectParties.NONE, new JdbcAuditWriter(), new JdbcOutboxWriter(), RiskOperatorSessions.IDS,
                java.time.Clock.systemUTC(), Spans.NONE, AlertObserver.NONE);
        return RiskOperatorSessions.inOneTransaction(uow -> unfolded.raise(uow, alert())).alert();
    }

    /** An alert raised through the composition's machine - folded into its subject's case at once. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private RiskAlert attachedAlert() {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return RiskOperatorSessions.inOneTransaction(uow -> composedAlerts.raise(uow, alert())).alert();
        }
    }

    private NewAlert alert() {
        SignalSubject subject = SignalSubject.of(SubjectKind.PARTY, RiskOperatorSessions.IDS.next());
        return NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, AlertSeverity.HIGH, subject,
                RuleKind.RECENT_CREDENTIAL_CHANGE, AlertWindow.of(new VelocityWindowKey(VelocityDimension.PARTY,
                        subject.ref(), WindowKind.D1, Instant.now().truncatedTo(ChronoUnit.DAYS))),
                SuiteVersion.of(riskPolicyAdministration),
                AlertEvidence.assessment(RiskAssessmentId.of(RiskOperatorSessions.IDS.next())),
                CorrelationId.generate(RiskOperatorSessions.IDS), CausationId.of("suppression-api"));
    }

    /** The suite's one policy version, proposed on first use - never activated. */
    private static final class SuiteVersion {
        private static RiskPolicyVersionId id;

        static synchronized RiskPolicyVersionId of(RiskPolicyAdministration administration) {
            if (id == null) {
                id = RiskOperatorSessions.inOneTransaction(uow -> administration.propose(uow,
                        RiskPolicyFixtures.v1(RiskOperation.TRANSFER), "the suppression API suite's version",
                        new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE),
                        CorrelationId.generate(RiskOperatorSessions.IDS))).id();
            }
            return id;
        }
    }
}
