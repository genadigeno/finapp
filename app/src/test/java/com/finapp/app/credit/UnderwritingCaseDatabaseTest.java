package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.BALANCES;
import static com.finapp.app.credit.CreditWorld.SUSPENDED;
import static com.finapp.app.credit.CreditWorld.TRANSACTIONS;
import static com.finapp.app.credit.CreditWorld.WITHDRAWN;
import static com.finapp.app.credit.CreditWorld.awaitDatabase;
import static com.finapp.app.credit.CreditWorld.count;
import static com.finapp.app.credit.CreditWorld.decide;
import static com.finapp.app.credit.CreditWorld.deciding;
import static com.finapp.app.credit.CreditWorld.eur;
import static com.finapp.app.credit.CreditWorld.evaluated;
import static com.finapp.app.credit.CreditWorld.scalar;
import static com.finapp.app.credit.CreditWorld.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.JdbcCreditProfiles;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyFigure;
import com.finapp.credit.PolicyOperator;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.UnavailableFallback;
import com.finapp.credit.UnderwritingCaseId;
import com.finapp.credit.UnderwritingCaseStatus;
import com.finapp.credit.UnderwritingCases;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The manual review case against a real database (`P10-TSK-018`; ADR-0089, CREDIT_DECISIONING_LIFECYCLES.md section 3.3;
 * {@code INV-CRD-11}, {@code INV-CRD-09}, {@code INV-CRD-06}, {@code INV-CRD-02}, {@code INV-AUD-04}): a referral opens
 * one case; underwriters take, release, decide and second-approve it under the lock order; a person never second-approves
 * their own decision, never approves a hard decline, more than the evaluation allows or beyond the exposure limit; an
 * open case closes with its request and a taken case is decided by its person whatever the validity. Separate
 * {@link UnderwritingCases} and deciders stand for separate instances; every count is read from the rows. And the doors:
 * {@code CREDIT_UNDERWRITE} alone, every act keyed, the queue's reads audited with no raw evidence in them.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings its own loan policies into force -
 * one that refers every request that has a bureau answer (and caps a tiny-balance applicant's referral), and its twin
 * whose exposure rule is a hard decline - and records decisions that reserve exposure.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try")
@DisplayName("underwriting: the manual review case (P10-TSK-018)")
class UnderwritingCaseDatabaseTest {

    private static final Duration WEEK = Duration.ofDays(7);
    /** 17,000.00 on the bureau: a 21,000.00 loan fits alone (38,000.00 of 40,000.00), not beside the line's 2,500.00. */
    private static final long LIMIT_BALANCE = 1_700_000L;
    private static final Money BIG_LOAN = eur(2_100_000);
    private static final Money LINE = eur(300_000);
    private static final List<ReasonCode> REASONS = List.of(ReasonCode.RISK_REFERRAL);
    /** The race's alternating head start - small enough that the two transactions still overlap. */
    private static final long HEAD_START_MILLIS = 25;
    /** How fresh a staleness case's bureau record is at its freeze. */
    private static final Duration STALE_MARGIN = Duration.ofSeconds(8);

    private enum Loan { REFERRING, HARD_EXPOSURE }

    private static Loan loanInForce;
    private static boolean lineInForce;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private CreditTestClient client;

    @BeforeEach
    void world() {
        client = new CreditTestClient(port);
        if (!lineInForce) {
            CreditWorld.seedsInForce();
            CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
            lineInForce = true;
        }
        loan(Loan.REFERRING);
    }

    // ------------------------------------------------------------------ the referral and the queue

    @Test
    @DisplayName("ten underwriters take one case: one ASSIGNED, nine credit.CaseTaken - one assignment edge, one audit"
            + " record; and a REFER opens exactly one case, the request IN_REVIEW, one ManualReviewRequired")
    void twoUnderwritersTakeOneCase() throws Exception {
        UUID request = referred(UUID.randomUUID(), eur(500_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", request)).isEqualTo("IN_REVIEW");
        assertThat(count("SELECT count(*) FROM credit.underwriting_case WHERE decision_request_id = ?", request)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.ManualReviewRequired'"
                + " AND aggregate_id = ?", id.value())).isEqualTo(1);
        String payload = scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                + " WHERE event_type = 'credit.ManualReviewRequired' AND aggregate_id = ?", id.value());
        assertThat(payload).contains(request.toString()).contains(id.value().toString())
                .contains("\"referralReasonCode1\":\"CRD-RISK-REFERRAL\"")
                .as("never an attribute").doesNotContain("740").doesNotContain("420050").doesNotContain("BUREAU_");
        assertThat(scalar("SELECT approvable_minor || '/' || four_eyes_threshold_minor FROM credit.underwriting_case"
                + " WHERE id = ?", id.value())).isEqualTo("500000/1000000");
        assertThat(decide(deciding(), request)).as("a second decider finds the request IN_REVIEW")
                .isEqualTo(Decider.Decided.NOTHING);
        List<Actor> underwriters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            underwriters.add(underwriter());
        }
        List<Object> outcomes = race(underwriters.stream()
                .<Callable<Object>>map(actor -> () -> act(uow -> reviewing().assign(uow, id, actor, correlation())))
                .toList());
        assertThat(outcomes.stream().filter(UnderwritingCases.Acted.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(UnderwritingCases.CaseTaken.class::isInstance)).hasSize(9);
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("ASSIGNED");
        assertThat(count("SELECT count(*) FROM credit.underwriting_case_event WHERE case_id = ? AND to_status = 'ASSIGNED'",
                id.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewCaseAssigned'"
                + " AND target_id = ?", id.value().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("a released case can be retaken - by another underwriter, never released by one who does not hold it")
    void aReleasedCaseCanBeRetaken() throws Exception {
        UnderwritingCaseId id = caseOf(referred(UUID.randomUUID(), eur(500_000), WEEK));
        Actor first = underwriter();
        Actor second = underwriter();
        act(uow -> reviewing().assign(uow, id, first, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().release(uow, id, second, correlation())))
                .isInstanceOf(UnderwritingCases.CaseTaken.class);
        assertThatThrownBy(() -> act(uow -> reviewing().assign(uow, id, second, correlation())))
                .isInstanceOf(UnderwritingCases.CaseTaken.class);
        assertThat(act(uow -> reviewing().release(uow, id, first, correlation())).reviewCase().status().name())
                .isEqualTo("OPEN");
        assertThat(act(uow -> reviewing().assign(uow, id, second, correlation())).reviewCase().assignee())
                .contains(second.id());
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewCaseReleased'"
                + " AND target_id = ?", id.value().toString())).isEqualTo(1);
        assertThat(eventsOf(id)).containsExactly("->OPEN", "OPEN->ASSIGNED", "ASSIGNED->OPEN", "OPEN->ASSIGNED");
    }

    // ------------------------------------------------------------------ what a person may decide

    @Test
    @DisplayName("a decision without a reason code or a reason is refused credit.ReasonRequired - and so is a reason"
            + " code about automation; nothing changes")
    void aDecisionWithoutAReasonIsRefused() throws Exception {
        UnderwritingCaseId id = caseOf(referred(UUID.randomUUID(), eur(500_000), WEEK));
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, id, approve(eur(100)), person, correlation())))
                .as("below the product's minimum amount").isInstanceOf(UnderwritingCases.JudgementInvalid.class);
        for (UnderwritingCases.Judgement judgement : List.of(
                new UnderwritingCases.Judgement(DecisionOutcome.DECLINED, Optional.empty(), List.of(), "thin file"),
                new UnderwritingCases.Judgement(DecisionOutcome.DECLINED, Optional.empty(), REASONS, " "),
                new UnderwritingCases.Judgement(DecisionOutcome.APPROVED, Optional.of(eur(500_000)),
                        List.of(ReasonCode.AUTO_APPROVAL_CEILING), "within"))) {
            assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, id, judgement, person, correlation())))
                    .as(judgement.toString()).isInstanceOf(UnderwritingCases.ReasonRequired.class);
        }
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("ASSIGNED");
        assertThat(count("SELECT count(*) FROM credit.credit_decision d JOIN credit.underwriting_case c"
                + " ON c.decision_request_id = d.decision_request_id WHERE c.id = ?", id.value())).isZero();
    }

    @Test
    @DisplayName("an approval at or below the threshold is one person's decision - decided_by the person, the case's"
            + " reasons, the referral kept as the case's basis; above it the case awaits a second person, who records it")
    void anApprovalAboveTheThresholdAwaitsASecondPerson() throws Exception {
        UUID small = referred(UUID.randomUUID(), eur(500_000), WEEK);
        Actor person = underwriter();
        UnderwritingCaseId smallCase = caseOf(small);
        act(uow -> reviewing().assign(uow, smallCase, person, correlation()));
        UnderwritingCases.Acted decided = act(uow -> reviewing().decide(uow, smallCase, approve(eur(500_000)), person,
                correlation()));
        assertThat(decided.reviewCase().status().name()).isEqualTo("DECIDED");
        assertThat(scalar("SELECT decided_by || '/' || decided_by_type || '/' || outcome || '/' || approved_minor"
                + " FROM credit.credit_decision WHERE decision_request_id = ?", small))
                .isEqualTo(person.id() + "/EMPLOYEE/APPROVED/500000");
        assertThat(scalar("SELECT string_agg(reason_code, ',' ORDER BY ordinal) FROM credit.credit_decision_reason r"
                + " JOIN credit.credit_decision d ON d.id = r.decision_id WHERE d.decision_request_id = ?", small))
                .isEqualTo("CRD-RISK-REFERRAL");
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", small)).isEqualTo("DECIDED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewDecided'"
                + " AND target_id = ? AND reason IS NOT NULL", smallCase.value().toString())).isEqualTo(1);

        UUID party = UUID.randomUUID();
        UUID big = referred(party, eur(1_200_000), WEEK);
        UnderwritingCaseId bigCase = caseOf(big);
        act(uow -> reviewing().assign(uow, bigCase, person, correlation()));
        assertThat(act(uow -> reviewing().decide(uow, bigCase, approve(eur(1_200_000)), person, correlation()))
                .reviewCase().status().name()).isEqualTo("AWAITING_SECOND");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", big)).isZero();
        Actor second = underwriter();
        UnderwritingCases.Acted approved = act(uow -> reviewing().approveSecond(uow, bigCase, Optional.empty(), second,
                correlation()));
        assertThat(approved.reviewCase().status().name()).isEqualTo("DECIDED");
        assertThat(approved.reviewCase().secondDecidedBy()).contains(second.id());
        assertThat(scalar("SELECT decided_by || '/' || approved_minor FROM credit.credit_decision"
                + " WHERE decision_request_id = ?", big)).isEqualTo(person.id() + "/1200000");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewSecondApproval'"
                + " AND target_id = ?", bigCase.value().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("the first decider cannot second-approve - refused at the domain credit.SelfApprovalRefused, and by the"
            + " four-eyes CHECK for a raw writer that otherwise takes every legal step")
    void theFirstDeciderCannotSecondApprove() throws Exception {
        UUID request = referred(UUID.randomUUID(), eur(1_200_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        act(uow -> reviewing().decide(uow, id, approve(eur(1_200_000)), person, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().approveSecond(uow, id, Optional.empty(), person, correlation())))
                .isInstanceOf(UnderwritingCases.SelfApprovalRefused.class);
        assertThatThrownBy(() -> act(uow -> reviewing().refuseSecond(uow, id, "disagree", person, correlation())))
                .isInstanceOf(UnderwritingCases.SelfApprovalRefused.class);
        // A raw writer: the decision, the request DECIDED, then the case DECIDED with the first decider as second.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            rawDecision(app, request);
            update(app, "UPDATE credit.decision_request SET status = 'DECIDED' WHERE id = ?", request);
            try (Statement savepoint = app.createStatement()) {
                savepoint.execute("SAVEPOINT four_eyes");
            }
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'DECIDED', second_decided_by = first_decided_by"
                    + " WHERE id = '" + id.value() + "'", "23514");
            try (Statement back = app.createStatement()) {
                back.execute("ROLLBACK TO SAVEPOINT four_eyes");
            }
            // The positive control: a different second person takes the same step.
            assertThat(update(app, "UPDATE credit.underwriting_case SET status = 'DECIDED', second_decided_by = 'someone-else'"
                    + " WHERE id = ?", id.value())).isEqualTo(1);
            app.rollback();
        }
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("AWAITING_SECOND");
    }

    @Test
    @DisplayName("a refused second approval returns to the first underwriter - a reason required, the first decider refused"
            + " as refuser; the first decision cleared from the case and kept in its history; the first decides again")
    void aRefusedSecondApprovalReturnsToTheFirstUnderwriter() throws Exception {
        UUID request = referred(UUID.randomUUID(), eur(1_200_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        Actor person = underwriter();
        Actor second = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        act(uow -> reviewing().decide(uow, id, approve(eur(1_200_000)), person, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().refuseSecond(uow, id, " ", second, correlation())))
                .isInstanceOf(UnderwritingCases.ReasonRequired.class);
        UnderwritingCases.Acted refused = act(uow -> reviewing().refuseSecond(uow, id, "income too thin for this amount",
                second, correlation()));
        assertThat(refused.reviewCase().status().name()).isEqualTo("ASSIGNED");
        assertThat(refused.reviewCase().assignee()).contains(person.id());
        assertThat(refused.reviewCase().first()).isEmpty();
        assertThat(scalar("SELECT outcome || '/' || approved_minor || '/' || first_decided_by || '/' || reason"
                + " FROM credit.underwriting_case_event WHERE case_id = ? AND from_status = 'AWAITING_SECOND'", id.value()))
                .isEqualTo("APPROVED/1200000/" + person.id() + "/income too thin for this amount");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewSecondApprovalRefused'"
                + " AND target_id = ? AND reason = 'income too thin for this amount'", id.value().toString())).isEqualTo(1);
        // The event row's own rank: a refusal by the first decider is unstorable.
        try (Connection app = DatabaseRoles.application()) {
            assertRefused(app, "INSERT INTO credit.underwriting_case_event (id, case_id, from_status, to_status, actor_id,"
                    + " actor_type, reason, first_decided_by, occurred_at) VALUES (gen_random_uuid(), '" + id.value()
                    + "', 'AWAITING_SECOND', 'ASSIGNED', 'p', 'EMPLOYEE', 'r', 'p', now())", "23514");
        }
        assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, id, approve(eur(1_200_000)), second, correlation())))
                .isInstanceOf(UnderwritingCases.CaseTaken.class);
        assertThat(act(uow -> reviewing().decide(uow, id, decline(), person, correlation())).reviewCase().status().name())
                .isEqualTo("DECIDED");
        assertThat(scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", request))
                .isEqualTo("DECLINED");
        assertThat(eventsOf(id)).containsExactly("->OPEN", "OPEN->ASSIGNED", "ASSIGNED->AWAITING_SECOND",
                "AWAITING_SECOND->ASSIGNED", "ASSIGNED->DECIDED");
    }

    @Test
    @DisplayName("ten second approvers record one decision - one CreditDecisionRecorded, one ReviewSecondApproval, one"
            + " edge; nine find the case DECIDED (credit.CaseTaken)")
    void tenSecondApproversRecordOneDecision() throws Exception {
        UUID request = referred(UUID.randomUUID(), eur(1_200_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        act(uow -> reviewing().decide(uow, id, approve(eur(1_200_000)), person, correlation()));
        List<Callable<Object>> approvers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Actor approver = underwriter();
            approvers.add(() -> act(uow -> reviewing().approveSecond(uow, id, Optional.empty(), approver, correlation())));
        }
        List<Object> outcomes = race(approvers);
        assertThat(outcomes.stream().filter(UnderwritingCases.Acted.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(UnderwritingCases.CaseTaken.class::isInstance)).hasSize(9);
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", request)).isEqualTo(1);
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", request);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRecorded'"
                + " AND aggregate_id = ?", UUID.fromString(decision))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewSecondApproval'"
                + " AND target_id = ?", id.value().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.underwriting_case_event WHERE case_id = ? AND to_status = 'DECIDED'",
                id.value())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the exposure, a person bound by it too

    @Test
    @DisplayName("a manual approval and a system decision for one party, at the exposure limit, thirty rounds: serialised"
            + " on the profile - never both approved; the person after the system is refused credit.ExposureLimitExceeded;"
            + " both orders occur (each side given a head start in turn)")
    void aManualApprovalAndASystemDecisionForOnePartySerialise() throws Exception {
        int manualFirst = 0;
        int systemFirst = 0;
        for (int round = 0; round < 30; round++) {
            UUID party = UUID.randomUUID();
            BALANCES.put(party, LIMIT_BALANCE);
            UUID loan = referred(party, BIG_LOAN, WEEK);
            UnderwritingCaseId id = caseOf(loan);
            Actor person = underwriter();
            act(uow -> reviewing().assign(uow, id, person, correlation()));
            act(uow -> reviewing().decide(uow, id, approve(BIG_LOAN), person, correlation()));
            UUID line = evaluated(party, CreditProduct.CREDIT_LINE, LINE, WEEK);
            Actor second = underwriter();
            // A head start of a few milliseconds, alternating: the transactions still overlap, and each order is met
            // (P10 to P11 transition - the count below could not fail while it summed two sides of one coin).
            long personDelay = round % 2 == 0 ? 0 : HEAD_START_MILLIS;
            long systemDelay = round % 2 == 0 ? HEAD_START_MILLIS : 0;
            List<Object> outcomes = race(List.of(
                    () -> {
                        Thread.sleep(personDelay);
                        return act(uow -> reviewing().approveSecond(uow, id, Optional.empty(), second, correlation()));
                    },
                    () -> {
                        Thread.sleep(systemDelay);
                        return decide(deciding(), line);
                    }));
            String loanOutcome = scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", loan);
            String lineOutcome = scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", line);
            boolean loanApproved = "APPROVED".equals(loanOutcome);
            boolean lineApproved = "APPROVED".equals(lineOutcome);
            assertThat(loanApproved && lineApproved).as("round %d: never both beyond the limit", round).isFalse();
            assertThat(loanApproved || lineApproved).as("round %d: one of the two approved", round).isTrue();
            if (loanApproved) {
                manualFirst++;
                assertThat(lineOutcome).as("round %d: the line saw the person's reservation", round).isEqualTo("DECLINED");
            } else {
                systemFirst++;
                assertThat(outcomes).as("round %d", round)
                        .anySatisfy(outcome -> assertThat(outcome).isInstanceOf(UnderwritingCases.ExposureLimitExceeded.class));
                assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value()))
                        .as("round %d: the case unchanged", round).isEqualTo("AWAITING_SECOND");
                assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", loan))
                        .as("round %d: nothing recorded - not even the successor", round).isEqualTo(1);
            }
        }
        assertThat(manualFirst).as("the person won the profile in some round").isPositive();
        assertThat(systemFirst).as("the system won the profile in some round").isPositive();
    }

    @Test
    @DisplayName("the person's deciding transactions take the profile first: held elsewhere, a second approval and a"
            + " below-threshold decision each wait on it holding nothing - the request still free to lock - then decide"
            + " (the Phase 10 to 11 transition)")
    void thePersonsDecidingTransactionWaitsOnTheProfile() throws Exception {
        UUID party = UUID.randomUUID();
        UUID big = referred(party, eur(1_200_000), WEEK);
        UnderwritingCaseId bigCase = caseOf(big);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, bigCase, person, correlation()));
        act(uow -> reviewing().decide(uow, bigCase, approve(eur(1_200_000)), person, correlation()));
        Actor second = underwriter();
        assertWaitsOnTheProfile(party, big,
                () -> act(uow -> reviewing().approveSecond(uow, bigCase, Optional.empty(), second, correlation())));

        UUID otherParty = UUID.randomUUID();
        UUID small = referred(otherParty, eur(500_000), WEEK);
        UnderwritingCaseId smallCase = caseOf(small);
        act(uow -> reviewing().assign(uow, smallCase, person, correlation()));
        assertWaitsOnTheProfile(otherParty, small,
                () -> act(uow -> reviewing().decide(uow, smallCase, approve(eur(500_000)), person, correlation())));
    }

    /** Holds {@code party}'s profile; {@code act} must block on it - and only on it - then decide once it is released. */
    private static void assertWaitsOnTheProfile(UUID party, UUID request, Callable<UnderwritingCases.Acted> act)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM credit.credit_profile WHERE party_id = '" + party + "' FOR UPDATE")
                        .close();
                try (ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
                    pid.next();
                    holderPid = pid.getInt(1);
                }
            }
            Future<UnderwritingCases.Acted> acted = pool.submit(act);
            awaitDatabase("SELECT count(*) > 0 FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))", holderPid);
            try (Connection other = DatabaseRoles.application()) {
                other.setAutoCommit(false);
                try (PreparedStatement lock = other.prepareStatement(
                        "SELECT status FROM credit.decision_request WHERE id = ? FOR UPDATE NOWAIT")) {
                    lock.setObject(1, request);
                    try (ResultSet row = lock.executeQuery()) {
                        assertThat(row.next()).as("the waiting person holds no lock on the request").isTrue();
                    }
                }
                other.rollback();
            }
            holder.rollback();
            assertThat(acted.get(1, TimeUnit.MINUTES).reviewCase().status()).isEqualTo(UnderwritingCaseStatus.DECIDED);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the Phase 10 to 11 transition

    @Test
    @DisplayName("an underwriter who is the applicant's own party is refused every act on the case -"
            + " credit.SelfDealingRefused, nothing changed - below the threshold and at the second approval alike")
    void anUnderwriterNeverActsOnTheirOwnPartysCase() throws Exception {
        UUID party = UUID.randomUUID();
        UnderwritingCaseId small = caseOf(referred(party, eur(500_000), WEEK));
        Actor insider = underwriter();
        CreditWorld.ACTOR_PARTIES.put(insider.id(), party);
        assertThatThrownBy(() -> act(uow -> reviewing().assign(uow, small, insider, correlation())))
                .isInstanceOf(UnderwritingCases.SelfDealingRefused.class);
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", small.value())).isEqualTo("OPEN");
        Actor colleague = underwriter();
        act(uow -> reviewing().assign(uow, small, colleague, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, small, approve(eur(500_000)), insider, correlation())))
                .as("the self-dealing refusal, before the holder's").isInstanceOf(UnderwritingCases.SelfDealingRefused.class);
        // The colleague declines it - the party's one open loan request closed, so it may apply again.
        act(uow -> reviewing().decide(uow, small, decline(), colleague, correlation()));

        UUID bigRequest = referred(party, eur(1_200_000), WEEK);
        UnderwritingCaseId big = caseOf(bigRequest);
        act(uow -> reviewing().assign(uow, big, colleague, correlation()));
        act(uow -> reviewing().decide(uow, big, approve(eur(1_200_000)), colleague, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().approveSecond(uow, big, Optional.empty(), insider, correlation())))
                .isInstanceOf(UnderwritingCases.SelfDealingRefused.class);
        assertThatThrownBy(() -> act(uow -> reviewing().refuseSecond(uow, big, "disagree", insider, correlation())))
                .isInstanceOf(UnderwritingCases.SelfDealingRefused.class);
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", big.value()))
                .isEqualTo("AWAITING_SECOND");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", bigRequest)).isZero();
        // An actor the platform cannot place in a party is refused too - fail closed.
        UnderwritingCases nowhere = new UnderwritingCases(new JdbcUnderwritingCaseStore(), CreditWorld.REQUESTS,
                new JdbcCreditProfiles(CreditWorld.IDS), deciding(), new JdbcDecisionSnapshotStore(),
                new JdbcPolicyEvaluationStore(), CreditWorld.POLICIES, (uow, actor) -> Optional.empty(),
                new JdbcAuditWriter(), CreditWorld.IDS, CreditWorld.CLOCK);
        UnderwritingCaseId open = caseOf(referred(UUID.randomUUID(), eur(500_000), WEEK));
        assertThatThrownBy(() -> act(uow -> nowhere.assign(uow, open, underwriter(), correlation())))
                .isInstanceOf(UnderwritingCases.SelfDealingRefused.class);
    }

    @Test
    @DisplayName("over HTTP, a customer who is also an underwriter cannot take their own referral - 403"
            + " credit.SelfDealingRefused, the case OPEN, and the attempt recorded FAILED credit.ReviewOwnCaseRefused")
    void aCustomerUnderwriterCannotTakeTheirOwnReferralOverHttp() throws Exception {
        CreditTestClient.Customer customer = client.consentingCustomer();
        grant(customer.login(), RoleName.UNDERWRITER);
        String insider = client.login(customer);
        UnderwritingCaseId id = caseOf(referred(customer.party(), eur(500_000), WEEK));
        String base = "/v1/operator/credit/review-cases/" + id.value();
        HttpResponse<String> refused = client.post(base + "/assignment", null, insider, CreditTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("credit.SelfDealingRefused");
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewOwnCaseRefused'"
                + " AND target_id = ? AND outcome = 'FAILED'", id.value().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewCaseAssigned'"
                + " AND target_id = ?", id.value().toString())).isZero();
        // The positive control: another underwriter takes it.
        HttpResponse<String> taken = client.post(base + "/assignment", null, sessionWith(RoleName.UNDERWRITER),
                CreditTestClient.key());
        assertThat(taken.statusCode()).as(taken.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("a person's reasons hold no card-number or bank-account shape - refused credit.ReasonRequired at the"
            + " domain, nothing changed; and by the reason columns' CHECKs for a raw writer")
    void aPersonsReasonsHoldNoInstrumentShape() throws Exception {
        String pan = "verified card 4111 1111 1111 1111 by phone";
        String iban = "pays from GB82 WEST 1234 5698 7654 32 monthly";
        UnderwritingCaseId small = caseOf(referred(UUID.randomUUID(), eur(500_000), WEEK));
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, small, person, correlation()));
        for (String reason : List.of(pan, iban)) {
            assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, small, new UnderwritingCases.Judgement(
                    DecisionOutcome.DECLINED, Optional.empty(), REASONS, reason), person, correlation())))
                    .as(reason).isInstanceOf(UnderwritingCases.ReasonRequired.class)
                    .hasMessageContaining("card-number or bank-account");
        }
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", small.value())).isEqualTo("ASSIGNED");
        UnderwritingCaseId big = caseOf(referred(UUID.randomUUID(), eur(1_200_000), WEEK));
        act(uow -> reviewing().assign(uow, big, person, correlation()));
        act(uow -> reviewing().decide(uow, big, approve(eur(1_200_000)), person, correlation()));
        Actor second = underwriter();
        assertThatThrownBy(() -> act(uow -> reviewing().refuseSecond(uow, big, iban, second, correlation())))
                .isInstanceOf(UnderwritingCases.ReasonRequired.class);
        assertThatThrownBy(() -> act(uow -> reviewing().approveSecond(uow, big, Optional.of(pan), second, correlation())))
                .isInstanceOf(UnderwritingCases.ReasonRequired.class);
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", big.value()))
                .isEqualTo("AWAITING_SECOND");
        // A raw writer taking every other legal step of ASSIGNED -> AWAITING_SECOND, only the reason carrying the shape.
        UnderwritingCaseId assigned = caseOf(referred(UUID.randomUUID(), eur(1_200_000), WEEK));
        act(uow -> reviewing().assign(uow, assigned, person, correlation()));
        try (Connection app = DatabaseRoles.application()) {
            for (String reason : List.of(pan, iban)) {
                assertRefusedBy(app, "UPDATE credit.underwriting_case SET status = 'AWAITING_SECOND',"
                        + " first_outcome = 'APPROVED', first_approved_minor = 1200000,"
                        + " first_reason_codes = '{CRD-RISK-REFERRAL}', first_reason = '" + reason + "',"
                        + " first_decided_by = assignee, first_decided_at = now() WHERE id = '" + assigned.value() + "'",
                        "underwriting_case_first_reason_no_instrument_shape");
                assertRefusedBy(app, "INSERT INTO credit.underwriting_case_event (id, case_id, from_status, to_status,"
                        + " actor_id, actor_type, reason, first_decided_by, occurred_at) VALUES (gen_random_uuid(), '"
                        + big.value() + "', 'AWAITING_SECOND', 'ASSIGNED', 'q', 'EMPLOYEE', '" + reason + "', 'p', now())",
                        "underwriting_case_event_reason_no_instrument_shape");
            }
        }
    }

    @Test
    @DisplayName("a person cannot approve while the exposure is unassessable - the bureau's total balance absent:"
            + " credit.ExposureUnassessable below the threshold and at the four-eyes first decision, nothing recorded;"
            + " a decline stands (INV-CRD-09, the owner's decision)")
    void aPersonCannotApproveAnUnassessableExposure() throws Exception {
        UUID party = UUID.randomUUID();
        CreditWorld.NO_BALANCE.add(party);
        UUID request = referred(party, eur(500_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, id, approve(eur(500_000)), person, correlation())))
                .isInstanceOf(UnderwritingCases.ExposureUnassessable.class);
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("ASSIGNED");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", request)).isZero();

        UUID bigParty = UUID.randomUUID();
        CreditWorld.NO_BALANCE.add(bigParty);
        UnderwritingCaseId big = caseOf(referred(bigParty, eur(1_200_000), WEEK));
        act(uow -> reviewing().assign(uow, big, person, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, big, approve(eur(1_200_000)), person, correlation())))
                .isInstanceOf(UnderwritingCases.ExposureUnassessable.class);
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", big.value())).isEqualTo("ASSIGNED");

        assertThat(act(uow -> reviewing().decide(uow, id, decline(), person, correlation())).reviewCase().status())
                .isEqualTo(UnderwritingCaseStatus.DECIDED);
        assertThat(scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", request))
                .isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("a person cannot decide on records past the policy's maximum data age - re-judged on the database's"
            + " clock in the deciding transaction: credit.DataStale, nothing recorded (not even a successor), the case"
            + " released to expire; the system decides nothing on them either; and a successor refuses to copy them")
    void nothingIsDecidedOnStaleData() throws Exception {
        Duration bureauAge = CreditWorld.TRANSACTIONS.inTransaction(uow -> CreditWorld.POLICIES
                .policy(uow, CreditWorld.LOAN_SEED).orElseThrow().policy().maximumDataAge().get(CreditSourceKind.BUREAU));
        UUID party = UUID.randomUUID();
        // Fresh at the freeze by STALE_MARGIN, stale soon after.
        CreditWorld.RETRIEVED_AGO.put(party, bureauAge.minus(STALE_MARGIN));
        UUID request = referred(party, eur(500_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        awaitBureauStale(request, bureauAge);
        for (UnderwritingCases.Judgement judgement : List.of(approve(eur(500_000)), decline())) {
            assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, id, judgement, person, correlation())))
                    .as(judgement.toString()).isInstanceOf(UnderwritingCases.DataStale.class);
        }
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("ASSIGNED");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", request)).isZero();
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", request))
                .isEqualTo(1);
        // The way out: released, the case is OPEN again - and expires with its request.
        assertThat(act(uow -> reviewing().release(uow, id, person, correlation())).reviewCase().status())
                .isEqualTo(UnderwritingCaseStatus.OPEN);

        // A successor never copies a stale record, whoever asks.
        assertThatThrownBy(() -> CreditWorld.TRANSACTIONS.inTransaction(uow -> CreditWorld.freezer().successor(uow,
                CreditWorld.freezer().latest(uow, request).orElseThrow(), eur(0), eur(0),
                java.util.Map.of(CreditSourceKind.BUREAU, bureauAge, CreditSourceKind.FINANCIAL_DATA, bureauAge))))
                .isInstanceOf(com.finapp.credit.SnapshotFreezer.RecordsStale.class);

        // The system: an evaluated request whose bureau record ages out before its decision decides nothing.
        UUID systemParty = UUID.randomUUID();
        CreditWorld.RETRIEVED_AGO.put(systemParty, bureauAge.minus(STALE_MARGIN));
        UUID evaluated = evaluated(systemParty, CreditProduct.PERSONAL_LOAN, eur(500_000), WEEK);
        awaitBureauStale(evaluated, bureauAge);
        assertThat(decide(deciding(), evaluated)).isEqualTo(Decider.Decided.NOTHING);
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", evaluated)).isEqualTo("EVALUATED");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", evaluated)).isZero();
        assertThat(count("SELECT count(*) FROM credit.underwriting_case WHERE decision_request_id = ?", evaluated)).isZero();
    }

    @Test
    @DisplayName("the outstanding credit is re-read beside the reservation under the profile lock - a consumption landing"
            + " between the evaluation and the decision (reserved unchanged, outstanding up) refuses the person's"
            + " approval past the limit and declines the system's, each on a successor carrying the new outstanding"
            + " (INV-CRD-09, ADR-0088)")
    void theOutstandingCreditIsReReadAtTheDecision() throws Exception {
        UUID party = UUID.randomUUID();
        BALANCES.put(party, LIMIT_BALANCE);
        UUID loan = referred(party, eur(500_000), WEEK);
        UnderwritingCaseId id = caseOf(loan);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        // 17,000.00 on the bureau + 20,000.00 now outstanding + 5,000.00 = 42,000.00 > 40,000.00.
        CreditWorld.OUTSTANDING.put(party, 2_000_000L);
        assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, id, approve(eur(500_000)), person, correlation())))
                .isInstanceOf(UnderwritingCases.ExposureLimitExceeded.class);
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", loan)).isZero();
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", loan)).isEqualTo(1);
        // 10,000.00 outstanding: 32,000.00 - within the limit, decided on the successor that carries it.
        CreditWorld.OUTSTANDING.put(party, 1_000_000L);
        assertThat(act(uow -> reviewing().decide(uow, id, approve(eur(500_000)), person, correlation())).reviewCase()
                .status()).isEqualTo(UnderwritingCaseStatus.DECIDED);
        assertThat(outstandingInDecisionSnapshot(loan)).isEqualTo(eur(1_000_000));
        assertThat(scalar("SELECT s.sequence::text FROM credit.credit_decision d JOIN credit.decision_snapshot s"
                + " ON s.id = d.snapshot_id WHERE d.decision_request_id = ?", loan)).isEqualTo("2");

        UUID lineParty = UUID.randomUUID();
        BALANCES.put(lineParty, LIMIT_BALANCE);
        UUID line = evaluated(lineParty, CreditProduct.CREDIT_LINE, LINE, WEEK);
        assertThat(scalar("SELECT outcome FROM credit.policy_evaluation WHERE decision_request_id = ?", line))
                .as("approvable at its evaluation").isEqualTo("APPROVE");
        CreditWorld.OUTSTANDING.put(lineParty, 2_200_000L);
        assertThat(decide(deciding(), line)).isEqualTo(Decider.Decided.DECIDED);
        assertThat(scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", line))
                .as("the consumption's exposure seen at the decision").isEqualTo("DECLINED");
        assertThat(outstandingInDecisionSnapshot(line)).isEqualTo(eur(2_200_000));
    }

    @Test
    @DisplayName("a decision, its assessment and its evaluation rest on their own request's snapshot, sealed to it -"
            + " by composite key and by trigger, for a raw writer (INV-CRD-06)")
    void aDecisionRestsOnItsOwnRequestsSnapshot() throws Exception {
        UUID own = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(500_000), WEEK);
        UUID other = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(500_000), WEEK);
        String insert = "INSERT INTO credit.credit_decision (id, decision_request_id, party_id, profile_id, product,"
                + " snapshot_id, snapshot_sha256, outcome, currency, requested_minor, approved_minor, term_months,"
                + " decision_validity, decided_at, valid_until, decided_by, decided_by_type, policy_version_id,"
                + " model_version_id, engine_version) SELECT gen_random_uuid(), r.id, r.party_id, r.profile_id, r.product,"
                + " s.id, %s, 'APPROVED', r.currency, r.requested_minor, r.requested_minor, r.term_months,"
                + " interval '30 days', now(), now(), 'raw', 'EMPLOYEE', r.pinned_policy_version_id,"
                + " r.pinned_model_version_id, r.pinned_engine_version FROM credit.decision_request r"
                + " JOIN credit.decision_snapshot s ON s.decision_request_id = '%s' WHERE r.id = '" + own + "'";
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertRefusedBy(app, String.format(insert, "s.content_sha256", other), "own request's snapshot");
            assertRefusedBy(app, String.format(insert, "sha256('not the snapshot'::bytea)", own), "own request's snapshot");
            // A successor (sequence 2) born for the request: a decision on the superseded first is refused...
            update(app, "INSERT INTO credit.decision_snapshot (id, decision_request_id, sequence, snapshot_format,"
                    + " canonical, content_sha256, policy_version_id, model_version_id, engine_version, frozen_at)"
                    + " SELECT gen_random_uuid(), decision_request_id, 2, snapshot_format, canonical, content_sha256,"
                    + " policy_version_id, model_version_id, engine_version, now() FROM credit.decision_snapshot"
                    + " WHERE decision_request_id = ? AND sequence = 1", own);
            assertRefusedBy(app, String.format(insert, "s.content_sha256", own) + " AND s.sequence = 1", "latest snapshot");
            // ...an assessment of the successor whose hash or pins are not its snapshot's is refused...
            String assessment = "INSERT INTO credit.credit_assessment SELECT (jsonb_populate_record(NULL::"
                    + "credit.credit_assessment, to_jsonb(a) || jsonb_build_object('id', gen_random_uuid(), 'snapshot_id',"
                    + " (SELECT id FROM credit.decision_snapshot WHERE decision_request_id = '" + own
                    + "' AND sequence = 2)) || %s)).* FROM credit.credit_assessment a WHERE a.decision_request_id = '"
                    + own + "'";
            assertRefusedBy(app, String.format(assessment,
                    "jsonb_build_object('snapshot_sha256', '\\x' || encode(sha256('x'::bytea), 'hex'))"), "own request's snapshot");
            assertRefusedBy(app, String.format(assessment, "jsonb_build_object('policy_version_id', '"
                    + CreditWorld.LINE_SEED.value() + "')"), "own request's snapshot");
            // ...and the positive controls: the successor's own assessment, and the decision on the latest snapshot.
            assertThat(update(app, String.format(assessment, "'{}'::jsonb"))).isEqualTo(1);
            assertThat(update(app, String.format(insert, "s.content_sha256", own) + " AND s.sequence = 2")).isEqualTo(1);
            app.rollback();
        }
        // The composite key alone, the seal trigger off: another request's snapshot is still refused (23503).
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("ALTER TABLE credit.credit_decision DISABLE TRIGGER credit_decision_is_sealed_to_its_snapshot");
            }
            assertRefused(owner, String.format(insert, "s.content_sha256", other), "23503");
            owner.rollback();
        }
    }

    private static Money outstandingInDecisionSnapshot(UUID request) {
        String canonical = scalar("SELECT s.canonical FROM credit.credit_decision d JOIN credit.decision_snapshot s"
                + " ON s.id = d.snapshot_id WHERE d.decision_request_id = ?", request);
        return ((com.finapp.credit.AttributeValue.MoneyValue) com.finapp.credit.CanonicalSnapshot.parse(canonical)
                .attribute(CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT).value()).value();
    }

    private static void awaitBureauStale(UUID request, Duration bureauAge) throws Exception {
        awaitDatabase("SELECT bool_and(LEAST(r.retrieved_at, r.recorded_at) < statement_timestamp() - ? * interval"
                + " '1 millisecond') FROM credit.credit_record r JOIN credit.data_request d ON d.id = r.data_request_id"
                + " WHERE d.decision_request_id = ? AND r.source_kind = 'BUREAU'", bureauAge.toMillis(), request);
    }

    /** Runs {@code sql} expecting a refusal whose message names {@code fragment} - a CHECK's name or a trigger's words. */
    private static void assertRefusedBy(Connection connection, String sql, String fragment) throws SQLException {
        boolean manual = !connection.getAutoCommit();
        if (manual) {
            try (Statement savepoint = connection.createStatement()) {
                savepoint.execute("SAVEPOINT refused");
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("expected a refusal naming " + fragment + ": " + sql);
        } catch (SQLException refused) {
            assertThat(refused.getMessage()).as(sql).contains(fragment);
        }
        if (manual) {
            try (Statement back = connection.createStatement()) {
                back.execute("ROLLBACK TO SAVEPOINT refused");
            }
        }
    }

    @Test
    @DisplayName("a person cannot approve beyond the exposure limit or above the evaluation's ceiling - credit"
            + ".ExposureLimitExceeded, nothing recorded, the case unchanged; the person decides again, a smaller approval")
    void aPersonCannotApproveBeyondTheExposureLimit() throws Exception {
        UUID party = UUID.randomUUID();
        BALANCES.put(party, LIMIT_BALANCE);
        UUID loan = referred(party, BIG_LOAN, WEEK);
        UnderwritingCaseId id = caseOf(loan);
        UUID line = evaluated(party, CreditProduct.CREDIT_LINE, LINE, WEEK);
        assertThat(decide(deciding(), line)).isEqualTo(Decider.Decided.DECIDED);
        assertThat(scalar("SELECT approved_minor::text FROM credit.credit_decision WHERE decision_request_id = ?", line))
                .isEqualTo("250000");
        Actor person = underwriter();
        Actor second = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        act(uow -> reviewing().decide(uow, id, approve(BIG_LOAN), person, correlation()));
        // 17,000.00 + 2,500.00 reserved + 21,000.00 = 40,500.00 > 40,000.00.
        assertThatThrownBy(() -> act(uow -> reviewing().approveSecond(uow, id, Optional.empty(), second, correlation())))
                .isInstanceOf(UnderwritingCases.ExposureLimitExceeded.class);
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("AWAITING_SECOND");
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", loan)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", loan)).isZero();
        act(uow -> reviewing().refuseSecond(uow, id, "beyond the limit beside the line", second, correlation()));
        UnderwritingCases.Acted smaller = act(uow -> reviewing().decide(uow, id, approve(eur(900_000)), person,
                correlation()));
        assertThat(smaller.reviewCase().status().name()).isEqualTo("DECIDED");
        assertThat(scalar("SELECT d.approved_minor || '/' || s.sequence FROM credit.credit_decision d"
                + " JOIN credit.decision_snapshot s ON s.id = d.snapshot_id WHERE d.decision_request_id = ?", loan))
                .as("decided from the successor frozen under the person - the reservation re-read").isEqualTo("900000/2");

        // The evaluation's ceiling: a tiny-balance applicant's referral is capped at 8,000.00.
        UUID capped = UUID.randomUUID();
        BALANCES.put(capped, 5_000L);
        UUID cappedLoan = referred(capped, eur(900_000), WEEK);
        UnderwritingCaseId cappedCase = caseOf(cappedLoan);
        assertThat(scalar("SELECT approvable_minor::text FROM credit.underwriting_case WHERE id = ?", cappedCase.value()))
                .isEqualTo("800000");
        act(uow -> reviewing().assign(uow, cappedCase, person, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, cappedCase, approve(eur(850_000)), person,
                correlation()))).isInstanceOf(UnderwritingCases.ExposureLimitExceeded.class);
        assertThat(act(uow -> reviewing().decide(uow, cappedCase, approve(eur(800_000)), person, correlation()))
                .reviewCase().status().name()).isEqualTo("DECIDED");
    }

    @Test
    @DisplayName("a hard decline cannot be approved - a successor evaluation that hard-declines refuses the approval"
            + " credit.HardDeclineNotOverridable, nothing recorded; a decline stands; and no case is born on a non-REFER basis")
    void aHardDeclineCannotBeApproved() throws Exception {
        loan(Loan.HARD_EXPOSURE);
        UUID party = UUID.randomUUID();
        BALANCES.put(party, LIMIT_BALANCE);
        UUID loan = referred(party, BIG_LOAN, WEEK);
        UnderwritingCaseId id = caseOf(loan);
        Actor person = underwriter();
        Actor second = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        act(uow -> reviewing().decide(uow, id, approve(BIG_LOAN), person, correlation()));
        UUID line = evaluated(party, CreditProduct.CREDIT_LINE, LINE, WEEK);
        assertThat(decide(deciding(), line)).isEqualTo(Decider.Decided.DECIDED);
        assertThatThrownBy(() -> act(uow -> reviewing().approveSecond(uow, id, Optional.empty(), second, correlation())))
                .isInstanceOf(UnderwritingCases.HardDeclineNotOverridable.class);
        act(uow -> reviewing().refuseSecond(uow, id, "a hard decline under the line", second, correlation()));
        assertThatThrownBy(() -> act(uow -> reviewing().decide(uow, id, approve(eur(100_000)), person, correlation())))
                .as("however small the amount").isInstanceOf(UnderwritingCases.HardDeclineNotOverridable.class);
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", loan)).isEqualTo(1);
        assertThat(act(uow -> reviewing().decide(uow, id, decline(), person, correlation())).reviewCase().status().name())
                .isEqualTo("DECIDED");
        assertThat(scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", loan))
                .isEqualTo("DECLINED");
        // The birth trigger by a raw writer: a REFER evaluation carrying a triggered hard decline (planted by the owner with
        // the born-with-its-evaluation trigger disabled, inside a transaction rolled back) is no case's basis.
        loan(Loan.REFERRING);
        UUID planted = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(500_000), WEEK);
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement plant = owner.createStatement()) {
                plant.execute("ALTER TABLE credit.policy_evaluation_rule DISABLE TRIGGER"
                        + " policy_evaluation_rule_is_born_with_its_evaluation");
                plant.execute("INSERT INTO credit.policy_evaluation_rule (evaluation_id, ordinal, rule_code, effect,"
                        + " triggered, assessed) SELECT id, 99, 'PLANTED_HARD_DECLINE', 'HARD_DECLINE', true, true"
                        + " FROM credit.policy_evaluation WHERE decision_request_id = '" + planted + "' AND outcome = 'REFER'");
            }
            assertRefused(owner, "INSERT INTO credit.underwriting_case (id, decision_request_id, party_id, product, currency,"
                    + " basis_evaluation_id, requested_minor, approvable_minor, four_eyes_threshold_minor, status, opened_at)"
                    + " SELECT gen_random_uuid(), r.id, r.party_id, r.product, r.currency, e.id, r.requested_minor,"
                    + " r.requested_minor, 1000000, 'OPEN', now() FROM credit.decision_request r"
                    + " JOIN credit.policy_evaluation e ON e.decision_request_id = r.id WHERE r.id = '" + planted + "'",
                    "P0001");
            owner.rollback();
        }
        // The birth trigger: a case's basis is its request's REFER evaluation - a decline's is refused.
        UUID heavy = UUID.randomUUID();
        BALANCES.put(heavy, 3_000_000L);
        UUID declined = evaluated(heavy, CreditProduct.CREDIT_LINE, LINE, WEEK);
        assertThat(scalar("SELECT outcome FROM credit.policy_evaluation WHERE decision_request_id = ?", declined))
                .isEqualTo("DECLINE");
        try (Connection app = DatabaseRoles.application()) {
            assertRefused(app, "INSERT INTO credit.underwriting_case (id, decision_request_id, party_id, product, currency,"
                    + " basis_evaluation_id, requested_minor, approvable_minor, four_eyes_threshold_minor, status, opened_at)"
                    + " SELECT gen_random_uuid(), r.id, r.party_id, r.product, r.currency, e.id, r.requested_minor,"
                    + " r.requested_minor, 250000, 'OPEN', now() FROM credit.decision_request r"
                    + " JOIN credit.policy_evaluation e ON e.decision_request_id = r.id WHERE r.id = '" + declined + "'",
                    "P0001");
        }
    }

    // ------------------------------------------------------------------ the validity, the standing, the consent

    @Test
    @DisplayName("an open case closes with its expiring request - the request EXPIRED, the case CLOSED (EXPIRED),"
            + " CreditDecisionRequestClosed")
    void anOpenCaseClosesWithItsExpiringRequest() throws Exception {
        UUID request = referred(UUID.randomUUID(), eur(500_000), Duration.ofSeconds(4));
        UnderwritingCaseId id = caseOf(request);
        DecisionProgress sweeper = CreditWorld.progress(CreditWorld.CLOCK, deciding());
        assertThat(step(sweeper, request)).isEqualTo(DecisionProgress.Step.WAITING);
        awaitExpiry(request);
        assertThat(step(sweeper, request)).isEqualTo(DecisionProgress.Step.EXPIRED);
        assertThat(scalar("SELECT status || '/' || closure_reason FROM credit.underwriting_case WHERE id = ?", id.value()))
                .isEqualTo("CLOSED/EXPIRED");
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", request)).isEqualTo("EXPIRED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRequestClosed'"
                + " AND aggregate_id = ?", request)).isEqualTo(1);
    }

    @Test
    @DisplayName("a taken case is decided after the request's validity - the sweep leaves it alone, a raw expiry is"
            + " refused, and its person decides it")
    void aTakenCaseIsDecidedAfterTheRequestsValidity() throws Exception {
        UUID request = referred(UUID.randomUUID(), eur(500_000), Duration.ofSeconds(4));
        UnderwritingCaseId id = caseOf(request);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        awaitExpiry(request);
        assertThat(step(CreditWorld.progress(CreditWorld.CLOCK, deciding()), request)).isEqualTo(DecisionProgress.Step.WAITING);
        try (Connection app = DatabaseRoles.application()) {
            assertRefused(app, "UPDATE credit.decision_request SET status = 'EXPIRED' WHERE id = '" + request + "'", "P0001");
        }
        assertThat(act(uow -> reviewing().decide(uow, id, approve(eur(500_000)), person, correlation())).reviewCase()
                .status().name()).isEqualTo("DECIDED");
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", request)).isEqualTo("DECIDED");
    }

    @Test
    @DisplayName("a case whose request is abandoned closes - from OPEN by the sweep (standing lost), from ASSIGNED only by"
            + " the person's own deciding transaction (standing lost, consent withdrawn), nothing decided")
    void aCaseWhoseRequestIsAbandonedCloses() throws Exception {
        DecisionProgress sweeper = CreditWorld.progress(CreditWorld.CLOCK, deciding());
        UUID openParty = UUID.randomUUID();
        UUID open = referred(openParty, eur(500_000), WEEK);
        SUSPENDED.add(openParty);
        assertThat(step(sweeper, open)).isEqualTo(DecisionProgress.Step.ABANDONED);
        assertThat(scalar("SELECT status || '/' || closure_reason FROM credit.underwriting_case WHERE decision_request_id = ?",
                open)).isEqualTo("CLOSED/STANDING_LOST");

        UUID takenParty = UUID.randomUUID();
        UUID taken = referred(takenParty, eur(500_000), WEEK);
        UnderwritingCaseId takenCase = caseOf(taken);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, takenCase, person, correlation()));
        SUSPENDED.add(takenParty);
        assertThat(step(sweeper, taken)).as("the sweep leaves a taken case to its person")
                .isEqualTo(DecisionProgress.Step.WAITING);
        UnderwritingCases.Acted closed = act(uow -> reviewing().decide(uow, takenCase, approve(eur(500_000)), person,
                correlation()));
        assertThat(closed.reviewCase().status().name()).isEqualTo("CLOSED");
        assertThat(closed.reviewCase().closureReason()).contains("STANDING_LOST");
        assertThat(closed.decision()).isEmpty();
        assertThat(scalar("SELECT status || '/' || closure_reason FROM credit.decision_request WHERE id = ?", taken))
                .isEqualTo("ABANDONED/STANDING_LOST");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", taken)).isZero();

        UUID withdrawnParty = UUID.randomUUID();
        UUID withdrawn = referred(withdrawnParty, eur(1_200_000), WEEK);
        UnderwritingCaseId withdrawnCase = caseOf(withdrawn);
        act(uow -> reviewing().assign(uow, withdrawnCase, person, correlation()));
        act(uow -> reviewing().decide(uow, withdrawnCase, approve(eur(1_200_000)), person, correlation()));
        WITHDRAWN.put(withdrawnParty, Set.of(CreditSourceKind.BUREAU));
        UnderwritingCases.Acted withdrawnClosed = act(uow -> reviewing().approveSecond(uow, withdrawnCase,
                Optional.empty(), underwriter(), correlation()));
        assertThat(withdrawnClosed.reviewCase().status().name()).isEqualTo("CLOSED");
        assertThat(withdrawnClosed.reviewCase().closureReason()).contains("CONSENT_WITHDRAWN");
        assertThat(scalar("SELECT closure_reason FROM credit.decision_request WHERE id = ?", withdrawn))
                .isEqualTo("CONSENT_WITHDRAWN");
    }

    @Test
    @DisplayName("an unassigned case's assignment races its request's expiry, eight requests at once: exactly one of"
            + " ASSIGNED and EXPIRED each time - request then case, both sides")
    void assignmentRacesExpiryExactlyOne() throws Exception {
        List<UUID> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(referred(UUID.randomUUID(), eur(500_000), Duration.ofSeconds(4)));
        }
        for (UUID request : requests) {
            awaitExpiry(request);
        }
        for (UUID request : requests) {
            UnderwritingCaseId id = caseOf(request);
            Actor person = underwriter();
            race(List.of(() -> act(uow -> reviewing().assign(uow, id, person, correlation())),
                    () -> step(CreditWorld.progress(CreditWorld.CLOCK, deciding()), request)));
            String caseStatus = scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value());
            String requestStatus = scalar("SELECT status FROM credit.decision_request WHERE id = ?", request);
            assertThat(caseStatus + "/" + requestStatus).isIn("ASSIGNED/IN_REVIEW", "CLOSED/EXPIRED");
        }
    }

    // ------------------------------------------------------------------ every edge, by raw SQL

    @Test
    @DisplayName("every case edge by raw SQL - the machine refuses the illegal edges, the frozen columns, a terminal case,"
            + " a closure while the request is open, a request IN_REVIEW without its case, and every delete or truncate")
    void everyCaseEdgeIsHeldByTheDatabase() throws Exception {
        UUID openRequest = referred(UUID.randomUUID(), eur(500_000), WEEK);
        UnderwritingCaseId open = caseOf(openRequest);
        UUID awaitingRequest = referred(UUID.randomUUID(), eur(1_200_000), WEEK);
        UnderwritingCaseId awaiting = caseOf(awaitingRequest);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, awaiting, person, correlation()));
        act(uow -> reviewing().decide(uow, awaiting, approve(eur(1_200_000)), person, correlation()));
        UUID decidedRequest = referred(UUID.randomUUID(), eur(500_000), WEEK);
        UnderwritingCaseId decided = caseOf(decidedRequest);
        act(uow -> reviewing().assign(uow, decided, person, correlation()));
        act(uow -> reviewing().decide(uow, decided, decline(), person, correlation()));
        UUID evaluatedRequest = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(500_000), WEEK);
        UnderwritingCaseId assigned = caseOf(referred(UUID.randomUUID(), eur(1_200_000), WEEK));
        act(uow -> reviewing().assign(uow, assigned, person, correlation()));
        try (Connection app = DatabaseRoles.application()) {
            String o = "'" + open.value() + "'";
            // INV-CRD-11 by a raw writer: a first decision without a reason code - none, or an empty list - is unstorable.
            for (String codes : List.of("NULL", "'{}'")) {
                assertRefused(app, "UPDATE credit.underwriting_case SET status = 'AWAITING_SECOND', first_outcome = 'APPROVED',"
                        + " first_approved_minor = 1200000, first_reason_codes = " + codes + ", first_reason = 'r',"
                        + " first_decided_by = assignee, first_decided_at = now() WHERE id = '" + assigned.value() + "'",
                        "23514");
            }
            String a = "'" + awaiting.value() + "'";
            String d = "'" + decided.value() + "'";
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'DECIDED' WHERE id = " + o, "P0001");
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'AWAITING_SECOND', assignee = 'x' WHERE id = "
                    + o, "P0001");
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'CLOSED', closure_reason = 'EXPIRED' WHERE id = "
                    + o, "P0001");
            assertRefused(app, "UPDATE credit.underwriting_case SET approvable_minor = approvable_minor - 1 WHERE id = " + o,
                    "P0001");
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'ASSIGNED' WHERE id = " + o, "23514");
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'OPEN', assignee = NULL, first_outcome = NULL,"
                    + " first_approved_minor = NULL, first_reason_codes = NULL, first_reason = NULL, first_decided_by = NULL,"
                    + " first_decided_at = NULL WHERE id = " + a, "P0001");
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'ASSIGNED', assignee = 'another',"
                    + " first_outcome = NULL, first_approved_minor = NULL, first_reason_codes = NULL, first_reason = NULL,"
                    + " first_decided_by = NULL, first_decided_at = NULL WHERE id = " + a, "P0001");
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'CLOSED', closure_reason = 'EXPIRED' WHERE id = "
                    + d, "P0001");
            assertRefused(app, "UPDATE credit.underwriting_case SET first_reason = 'rewritten' WHERE id = " + d, "P0001");
            assertRefused(app, "DELETE FROM credit.underwriting_case WHERE id = " + o, "42501");
            assertRefused(app, "UPDATE credit.underwriting_case_event SET reason = 'x' WHERE case_id = " + o, "42501");
            assertRefused(app, "UPDATE credit.decision_request SET status = 'IN_REVIEW' WHERE id = '" + evaluatedRequest + "'",
                    "P0001");
            assertRefused(app, "UPDATE credit.underwriting_case SET status = 'ASSIGNED', assignee = 'x' WHERE id = '"
                    + caseOf(decidedRequest).value() + "'", "P0001");
            // A request leaves IN_REVIEW only with its case terminal - judged at commit.
            app.setAutoCommit(false);
            rawDecision(app, openRequest);
            update(app, "UPDATE credit.decision_request SET status = 'DECIDED' WHERE id = ?", openRequest);
            assertThatThrownBy(app::commit).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
            app.rollback();
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            assertRefused(owner, "DELETE FROM credit.underwriting_case WHERE id = '" + open.value() + "'", "P0001");
            assertRefused(owner, "TRUNCATE credit.underwriting_case CASCADE", "P0001");
            assertRefused(owner, "UPDATE credit.underwriting_case_event SET reason = 'x' WHERE case_id = '" + open.value()
                    + "'", "P0001");
            assertRefused(owner, "TRUNCATE credit.underwriting_case_event", "P0001");
        }
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", open.value())).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("every act from a state its edge does not leave is refused by the domain, the case unchanged - and"
            + " every edge out of a terminal case refused by raw SQL (P10-DOC-001)")
    void everyInvalidTransitionIsRefusedByTheDomainAndByRawSql() throws Exception {
        UUID closingRequest = referred(UUID.randomUUID(), eur(500_000), Duration.ofSeconds(6));
        Actor person = underwriter();
        Actor other = underwriter();
        UnderwritingCaseId open = caseOf(referred(UUID.randomUUID(), eur(500_000), WEEK));
        UnderwritingCaseId assigned = caseOf(referred(UUID.randomUUID(), eur(500_000), WEEK));
        act(uow -> reviewing().assign(uow, assigned, person, correlation()));
        UnderwritingCaseId awaiting = caseOf(referred(UUID.randomUUID(), eur(1_200_000), WEEK));
        act(uow -> reviewing().assign(uow, awaiting, person, correlation()));
        act(uow -> reviewing().decide(uow, awaiting, approve(eur(1_200_000)), person, correlation()));
        UnderwritingCaseId decided = caseOf(referred(UUID.randomUUID(), eur(500_000), WEEK));
        act(uow -> reviewing().assign(uow, decided, person, correlation()));
        act(uow -> reviewing().decide(uow, decided, decline(), person, correlation()));
        awaitExpiry(closingRequest);
        assertThat(step(CreditWorld.progress(CreditWorld.CLOCK, deciding()), closingRequest))
                .isEqualTo(DecisionProgress.Step.EXPIRED);
        UnderwritingCaseId closed = caseOf(closingRequest);

        // Each act and the one edge it takes: assign OPEN->ASSIGNED, release ASSIGNED->OPEN, decide ASSIGNED->DECIDED,
        // a second approval AWAITING_SECOND->DECIDED, its refusal AWAITING_SECOND->ASSIGNED - each in the machine.
        Map<String, UnderwritingCaseStatus> from = Map.of("assign", UnderwritingCaseStatus.OPEN,
                "release", UnderwritingCaseStatus.ASSIGNED, "decide", UnderwritingCaseStatus.ASSIGNED,
                "approveSecond", UnderwritingCaseStatus.AWAITING_SECOND,
                "refuseSecond", UnderwritingCaseStatus.AWAITING_SECOND);
        Map<String, UnderwritingCaseStatus> to = Map.of("assign", UnderwritingCaseStatus.ASSIGNED,
                "release", UnderwritingCaseStatus.OPEN, "decide", UnderwritingCaseStatus.DECIDED,
                "approveSecond", UnderwritingCaseStatus.DECIDED, "refuseSecond", UnderwritingCaseStatus.ASSIGNED);
        from.forEach((name, source) -> assertThat(source.canTransitionTo(to.get(name)))
                .as(name + "'s edge is in the machine").isTrue());
        Map<UnderwritingCaseStatus, UnderwritingCaseId> cases = Map.of(UnderwritingCaseStatus.OPEN, open,
                UnderwritingCaseStatus.ASSIGNED, assigned, UnderwritingCaseStatus.AWAITING_SECOND, awaiting,
                UnderwritingCaseStatus.DECIDED, decided, UnderwritingCaseStatus.CLOSED, closed);
        int refusals = 0;
        for (Map.Entry<UnderwritingCaseStatus, UnderwritingCaseId> held : cases.entrySet()) {
            UnderwritingCaseId id = held.getValue();
            String before = scalar("SELECT status || '/' || coalesce(assignee, '-') FROM credit.underwriting_case"
                    + " WHERE id = ?", id.value());
            assertThat(before).as("the case under test").startsWith(held.getKey().name());
            for (String name : from.keySet()) {
                if (from.get(name) == held.getKey()) {
                    continue;
                }
                // The holder where the act is a holder's; another underwriter where it is a second person's.
                Actor actor = name.endsWith("Second") ? other : person;
                Function<Connection, UnderwritingCases.Acted> attempt = switch (name) {
                    case "assign" -> uow -> reviewing().assign(uow, id, actor, correlation());
                    case "release" -> uow -> reviewing().release(uow, id, actor, correlation());
                    case "decide" -> uow -> reviewing().decide(uow, id, decline(), actor, correlation());
                    case "approveSecond" -> uow -> reviewing().approveSecond(uow, id, Optional.empty(), actor, correlation());
                    default -> uow -> reviewing().refuseSecond(uow, id, "disagree", actor, correlation());
                };
                // The domain's refusal, never the trigger's: CaseTaken from the act's own precondition.
                assertThatThrownBy(() -> act(attempt)).as(name + " from " + held.getKey())
                        .isInstanceOf(UnderwritingCases.CaseTaken.class);
                assertThat(scalar("SELECT status || '/' || coalesce(assignee, '-') FROM credit.underwriting_case"
                        + " WHERE id = ?", id.value())).as(name + " from " + held.getKey() + " changed nothing")
                        .isEqualTo(before);
                refusals++;
            }
        }
        assertThat(refusals).as("every act from every state it does not leave").isEqualTo(5 * 5 - 5);
        try (Connection app = DatabaseRoles.application()) {
            for (UnderwritingCaseId terminal : List.of(decided, closed)) {
                String t = "'" + terminal.value() + "'";
                for (String edge : List.of("status = 'OPEN', assignee = NULL", "status = 'ASSIGNED', assignee = 'x'",
                        "status = 'AWAITING_SECOND'", "status = 'DECIDED'", "status = 'CLOSED', closure_reason = 'EXPIRED'")) {
                    assertRefused(app, "UPDATE credit.underwriting_case SET " + edge + " WHERE id = " + t, "P0001");
                }
            }
        }
        assertThat(count("SELECT count(*) FROM credit.credit_decision d JOIN credit.underwriting_case c"
                + " ON c.decision_request_id = d.decision_request_id WHERE c.id = ?", closed.value())).isZero();
    }

    @Test
    @DisplayName("every born-once arbiter refuses a copy with every trigger off - the unique alone, never a lock or a"
            + " trigger, holds the profile, the data request's reference, the snapshot, the assessment, the evaluation,"
            + " the decision and the case (P10-DOC-001)")
    void everyBornOnceArbiterRefusesACopyWithEveryTriggerOff() throws Exception {
        UUID party = UUID.randomUUID();
        UUID request = referred(party, eur(500_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        Actor person = underwriter();
        act(uow -> reviewing().assign(uow, id, person, correlation()));
        act(uow -> reviewing().decide(uow, id, decline(), person, correlation()));
        // table -> the row to copy, and the column given a fresh identifier so only the arbiter can refuse the copy.
        Map<String, String> arbiters = new java.util.LinkedHashMap<>();
        arbiters.put("credit_profile", "party_id = '" + party + "'");
        arbiters.put("data_request", "decision_request_id = '" + request + "'");
        arbiters.put("decision_snapshot", "decision_request_id = '" + request + "'");
        arbiters.put("credit_assessment", "decision_request_id = '" + request + "'");
        arbiters.put("policy_evaluation", "decision_request_id = '" + request + "'");
        arbiters.put("credit_decision", "decision_request_id = '" + request + "'");
        arbiters.put("underwriting_case", "decision_request_id = '" + request + "'");
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            for (Map.Entry<String, String> arbiter : arbiters.entrySet()) {
                String table = "credit." + arbiter.getKey();
                try (Statement statement = owner.createStatement()) {
                    statement.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
                    statement.execute("SAVEPOINT copy");
                }
                String copy = "INSERT INTO " + table + " SELECT (jsonb_populate_record(NULL::" + table
                        + ", to_jsonb(t) || jsonb_build_object('id', gen_random_uuid()))).* FROM " + table + " t WHERE "
                        + arbiter.getValue() + " LIMIT 1";
                assertThat(count("SELECT count(*) FROM " + table + " WHERE " + arbiter.getValue()))
                        .as(table + " holds the row to copy").isPositive();
                assertThatThrownBy(() -> {
                    try (Statement statement = owner.createStatement()) {
                        statement.execute(copy);
                    }
                }).as(table).isInstanceOf(SQLException.class)
                        .satisfies(refused -> assertThat(((SQLException) refused).getSQLState())
                                .as(table + ": " + refused.getMessage()).isEqualTo("23505"));
                try (Statement statement = owner.createStatement()) {
                    statement.execute("ROLLBACK TO SAVEPOINT copy");
                }
            }
            owner.rollback();
        }
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", request)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the doors

    @Test
    @DisplayName("an underwriter works a case over HTTP - the queue with its basis and no raw evidence (audited), a keyed"
            + " assignment replayed byte-identical, a decision recorded; the refusals in the contract's words")
    void anUnderwriterWorksACaseOverHttp() throws Exception {
        CreditTestClient.Customer customer = client.consentingCustomer();
        UUID request = referred(customer.party(), eur(500_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        String underwriter = sessionWith(RoleName.UNDERWRITER);
        String base = "/v1/operator/credit/review-cases/" + id.value();
        HttpResponse<String> queue = client.get("/v1/operator/credit/review-cases?status=OPEN", underwriter);
        assertThat(queue.statusCode()).as(queue.body()).isEqualTo(200);
        assertThat(queue.body()).contains(id.value().toString()).contains("\"basisAttributes\"")
                .contains("REVIEW_REFERRAL").contains("CRD-RISK-REFERRAL");
        assertThat(queue.body()).as("never the raw evidence").doesNotContain("answer").doesNotContain("ciphertext");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewCasesRead'"
                + " AND target_id = 'OPEN'")).isGreaterThanOrEqualTo(1);
        assertThat(client.get("/v1/operator/credit/review-cases?status=SIDEWAYS", underwriter).statusCode()).isEqualTo(422);

        HttpResponse<String> missingKey = client.post(base + "/assignment", null, underwriter, null);
        assertThat(missingKey.statusCode()).isEqualTo(422);
        assertThat(missingKey.body()).contains("api.IdempotencyKeyRequired");
        String key = CreditTestClient.key();
        HttpResponse<String> assigned = client.post(base + "/assignment", null, underwriter, key);
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);
        assertThat(CreditTestClient.field(assigned.body(), "status")).isEqualTo("ASSIGNED");
        assertThat(client.post(base + "/assignment", null, underwriter, key).body()).isEqualTo(assigned.body());
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewCaseAssigned'"
                + " AND target_id = ?", id.value().toString())).isEqualTo(1);
        String other = sessionWith(RoleName.UNDERWRITER);
        HttpResponse<String> taken = client.post(base + "/assignment", null, other, CreditTestClient.key());
        assertThat(taken.statusCode()).isEqualTo(409);
        assertThat(taken.body()).contains("credit.CaseTaken");

        HttpResponse<String> unreasoned = client.post(base + "/decision",
                "{\"outcome\":\"DECLINED\",\"reasonCodes\":[],\"reason\":\"thin\"}", underwriter, CreditTestClient.key());
        assertThat(unreasoned.statusCode()).isEqualTo(422);
        assertThat(unreasoned.body()).contains("credit.ReasonRequired");
        HttpResponse<String> decided = client.post(base + "/decision", "{\"outcome\":\"APPROVED\",\"approvedAmount\":"
                + "\"5000.00\",\"currency\":\"EUR\",\"reasonCodes\":[\"CRD-RISK-REFERRAL\"],\"reason\":\"verified by phone\"}",
                underwriter, CreditTestClient.key());
        assertThat(decided.statusCode()).as(decided.body()).isEqualTo(200);
        assertThat(CreditTestClient.field(decided.body(), "status")).isEqualTo("DECIDED");
        assertThat(CreditTestClient.field(decided.body(), "decisionId"))
                .isEqualTo(scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", request));
        HttpResponse<String> unknown = client.post("/v1/operator/credit/review-cases/" + CreditWorld.IDS.next()
                + "/assignment", null, underwriter, CreditTestClient.key());
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("every keyed act replays its response under its key - the release, a decision, a refused second approval"
            + " and a second approval - each recorded and audited once (P10-DOC-001)")
    void everyKeyedActReplaysItsResponse() throws Exception {
        // A real customer: the application's own standing port reads the party (a UUIDv7) in the deciding transaction.
        UUID request = referred(client.consentingCustomer().party(), eur(1_200_000), WEEK);
        UnderwritingCaseId id = caseOf(request);
        String first = sessionWith(RoleName.UNDERWRITER);
        String second = sessionWith(RoleName.UNDERWRITER);
        String base = "/v1/operator/credit/review-cases/" + id.value();
        String decision = "{\"outcome\":\"APPROVED\",\"approvedAmount\":\"12000.00\",\"currency\":\"EUR\","
                + "\"reasonCodes\":[\"CRD-RISK-REFERRAL\"],\"reason\":\"verified by phone\"}";
        assertThat(client.post(base + "/assignment", null, first, CreditTestClient.key()).statusCode()).isEqualTo(200);
        assertReplays(base + "/release", null, first, "OPEN");
        assertThat(client.post(base + "/assignment", null, first, CreditTestClient.key()).statusCode()).isEqualTo(200);
        assertReplays(base + "/decision", decision, first, "AWAITING_SECOND");
        assertReplays(base + "/second-approval", "{\"decision\":\"REFUSE\",\"reason\":\"income too thin\"}", second,
                "ASSIGNED");
        assertThat(client.post(base + "/decision", decision, first, CreditTestClient.key()).statusCode()).isEqualTo(200);
        assertReplays(base + "/second-approval", "{\"decision\":\"APPROVE\",\"reason\":\"agreed\"}", second, "DECIDED");
        String target = id.value().toString();
        for (String operation : List.of("credit.ReviewCaseReleased", "credit.ReviewSecondApprovalRefused",
                "credit.ReviewSecondApproval")) {
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND target_id = ?", operation,
                    target)).as(operation + " once, whatever the replays").isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewDecided'"
                + " AND target_id = ?", target)).as("two first decisions, each once").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", request)).isEqualTo(1);
    }

    /** Posts under one key twice: the same status and the byte-identical body - the replay, never a second act. */
    private void assertReplays(String path, String body, String session, String status) throws Exception {
        String key = CreditTestClient.key();
        HttpResponse<String> once = client.post(path, body, session, key);
        assertThat(once.statusCode()).as(path + ": " + once.body()).isEqualTo(200);
        assertThat(CreditTestClient.field(once.body(), "status")).as(path).isEqualTo(status);
        HttpResponse<String> again = client.post(path, body, session, key);
        assertThat(again.statusCode()).as(path + " replayed").isEqualTo(200);
        assertThat(again.body()).as(path + " replayed byte-identical").isEqualTo(once.body());
    }

    @Test
    @DisplayName("the doors are CREDIT_UNDERWRITE's alone - no session 401; a customer and a credit policy officer 403;"
            + " nothing taken, decided or recorded")
    void theDoorsAreTheUnderwritersAlone() throws Exception {
        UnderwritingCaseId id = caseOf(referred(UUID.randomUUID(), eur(500_000), WEEK));
        String base = "/v1/operator/credit/review-cases/" + id.value();
        String decision = "{\"outcome\":\"DECLINED\",\"reasonCodes\":[\"CRD-RISK-REFERRAL\"],\"reason\":\"r\"}";
        String second = "{\"decision\":\"APPROVE\"}";
        assertThat(client.get("/v1/operator/credit/review-cases", null).statusCode()).isEqualTo(401);
        assertThat(client.post(base + "/assignment", null, null, CreditTestClient.key()).statusCode()).isEqualTo(401);
        // P10-DOC-001: every door without a session, not only the first two.
        assertThat(client.post(base + "/release", null, null, CreditTestClient.key()).statusCode()).isEqualTo(401);
        assertThat(client.post(base + "/decision", decision, null, CreditTestClient.key()).statusCode()).isEqualTo(401);
        assertThat(client.post(base + "/second-approval", second, null, CreditTestClient.key()).statusCode())
                .isEqualTo(401);
        CreditTestClient.Customer customer = client.customer(true);
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        for (String bearer : List.of(customer.token(), officer)) {
            assertThat(client.get("/v1/operator/credit/review-cases", bearer).statusCode()).isEqualTo(403);
            assertThat(client.post(base + "/assignment", null, bearer, CreditTestClient.key()).statusCode()).isEqualTo(403);
            assertThat(client.post(base + "/release", null, bearer, CreditTestClient.key()).statusCode()).isEqualTo(403);
            assertThat(client.post(base + "/decision", decision, bearer, CreditTestClient.key()).statusCode()).isEqualTo(403);
            assertThat(client.post(base + "/second-approval", second, bearer, CreditTestClient.key()).statusCode())
                    .isEqualTo(403);
        }
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation LIKE 'credit.Review%'"
                + " AND target_id = ?", id.value().toString())).isZero();
    }

    // ------------------------------------------------------------------ plumbing

    /** An instance's review - its own stores over the world's deciding transaction. */
    private static UnderwritingCases reviewing() {
        return new UnderwritingCases(new JdbcUnderwritingCaseStore(), CreditWorld.REQUESTS,
                new JdbcCreditProfiles(CreditWorld.IDS), deciding(), new JdbcDecisionSnapshotStore(),
                new JdbcPolicyEvaluationStore(), CreditWorld.POLICIES, CreditWorld.ACTING_PARTIES,
                new JdbcAuditWriter(), CreditWorld.IDS,
                CreditWorld.CLOCK);
    }

    /** {@code party}'s loan driven to its referral: the case open, the request IN_REVIEW. */
    private static UUID referred(UUID party, Money amount, Duration validity) {
        UUID request = evaluated(party, CreditProduct.PERSONAL_LOAN, amount, validity);
        assertThat(decide(deciding(), request)).isEqualTo(Decider.Decided.REFERRED);
        return request;
    }

    private static UnderwritingCaseId caseOf(UUID request) {
        return UnderwritingCaseId.of(UUID.fromString(
                scalar("SELECT id::text FROM credit.underwriting_case WHERE decision_request_id = ?", request)));
    }

    private static Actor underwriter() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(CreditWorld.IDS);
    }

    private static <R> R act(Function<Connection, R> work) {
        return TRANSACTIONS.inTransaction(work);
    }

    private static UnderwritingCases.Judgement approve(Money amount) {
        return new UnderwritingCases.Judgement(DecisionOutcome.APPROVED, Optional.of(amount), REASONS,
                "reviewed against the referral");
    }

    private static UnderwritingCases.Judgement decline() {
        return new UnderwritingCases.Judgement(DecisionOutcome.DECLINED, Optional.empty(), REASONS, "not convinced");
    }

    private static void awaitExpiry(UUID request) throws Exception {
        awaitDatabase("SELECT expires_at <= statement_timestamp() FROM credit.decision_request WHERE id = ?", request);
    }

    private static List<String> eventsOf(UnderwritingCaseId id) {
        String joined = scalar("SELECT string_agg(coalesce(from_status, '') || '->' || to_status, ',' ORDER BY occurred_at, id)"
                + " FROM credit.underwriting_case_event WHERE case_id = ?", id.value());
        return List.of(joined.split(","));
    }

    /** Runs every task at once from a shared start; each result is what it returned, or what it threw. */
    private static List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RuntimeException refused) {
                        return refused;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A decision row a raw writer could insert for {@code request} - a full approval from its latest snapshot. */
    private static void rawDecision(Connection app, UUID request) throws SQLException {
        update(app, "INSERT INTO credit.credit_decision (id, decision_request_id, party_id, profile_id, product, snapshot_id,"
                + " snapshot_sha256, outcome, currency, requested_minor, approved_minor, term_months, decision_validity,"
                + " decided_at, valid_until, decided_by, decided_by_type, policy_version_id, model_version_id, engine_version)"
                + " SELECT gen_random_uuid(), r.id, r.party_id, r.profile_id, r.product, s.id, s.content_sha256, 'APPROVED',"
                + " r.currency, r.requested_minor, r.requested_minor, r.term_months, interval '30 days', now(), now(), 'raw',"
                + " 'EMPLOYEE', r.pinned_policy_version_id, r.pinned_model_version_id, r.pinned_engine_version"
                + " FROM credit.decision_request r JOIN credit.decision_snapshot s ON s.decision_request_id = r.id"
                + " WHERE r.id = ? ORDER BY s.sequence DESC LIMIT 1", request);
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static void assertRefused(Connection connection, String sql, String sqlState) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("expected " + sqlState + ": " + sql);
        } catch (SQLException refused) {
            assertThat(refused.getSQLState()).as(sql + " - " + refused.getMessage()).isEqualTo(sqlState);
        }
    }

    /**
     * The loan policy {@code variant} in force: PERSONAL_LOAN v1 without its score rules, a rule referring every request
     * with a bureau answer, a cap of 8,000.00 on a referral whose bureau balance is under 100.00 - and, for
     * {@code HARD_EXPOSURE}, its exposure rule a hard decline.
     */
    private static void loan(Loan variant) {
        if (loanInForce == variant) {
            return;
        }
        TRANSACTIONS.inTransaction(uow -> {
            CreditPolicy v1 = CreditWorld.POLICIES.policy(uow, CreditWorld.LOAN_SEED).orElseThrow().policy();
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
            for (CreditPolicy.PolicyRule rule : v1.rules()) {
                if (rule.subject() instanceof CreditPolicy.Subject.Figure figure && figure.figure() == PolicyFigure.SCORE) {
                    continue;
                }
                if (variant == Loan.HARD_EXPOSURE && rule.ruleCode().equals("EXPOSURE_LIMIT")) {
                    rules.add(new CreditPolicy.PolicyRule(rule.ruleCode(), rule.subject(), rule.operator(), rule.operand(),
                            PolicyEffect.HARD_DECLINE, rule.cap(), rule.reason()));
                    continue;
                }
                rules.add(rule);
            }
            rules.add(new CreditPolicy.PolicyRule("REVIEW_CAP",
                    new CreditPolicy.Subject.Attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE), PolicyOperator.LT,
                    new CreditPolicy.Operand.MoneyOperand(eur(10_000)), PolicyEffect.CAP_AMOUNT, Optional.of(eur(800_000)),
                    ReasonCode.INSUFFICIENT_CREDIT_HISTORY));
            rules.add(new CreditPolicy.PolicyRule("REVIEW_REFERRAL",
                    new CreditPolicy.Subject.Attribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE), PolicyOperator.GE,
                    new CreditPolicy.Operand.IntegerOperand(0), PolicyEffect.REFER, Optional.empty(),
                    ReasonCode.RISK_REFERRAL));
            CreditPolicy policy = new CreditPolicy(v1.product(), v1.assessmentRateBps(), v1.minimumDisposable(),
                    v1.minimumPaymentRatioBps(), v1.maximumExposure(), v1.maximumDataAge(), UnavailableFallback.REFER,
                    v1.autoApprovalCeiling(), rules);
            CreditPolicyAdministration.Proposed proposed = CreditWorld.POLICY_ADMINISTRATION.propose(uow, policy,
                    "the review suite's loan: " + variant, underwriter(), correlation());
            CreditWorld.POLICY_ADMINISTRATION.approve(uow, proposed.id(), underwriter(), "activated", correlation());
            return null;
        });
        loanInForce = variant;
    }

    /** A registered identity holding {@code role}, and its session. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "uw." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null, CreditTestClient.key()).statusCode())
                .isEqualTo(201);
        grant(login, role);
        HttpResponse<String> session = client.post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null,
                CreditTestClient.key());
        return CreditTestClient.field(session.body(), "sessionToken");
    }

    /** Grants {@code role} to the identity registered as {@code login}. */
    private void grant(String login, RoleName role) throws Exception {
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(
                        CorrelationId.generate(CreditWorld.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
    }
}
