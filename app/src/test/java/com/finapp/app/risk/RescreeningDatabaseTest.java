package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.kyc.CaseAssessment;
import com.finapp.app.kyc.DecisionRecording;
import com.finapp.app.kyc.ReviewService;
import com.finapp.app.kyc.VerificationRunService;
import com.finapp.identity.IdentityId;
import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckPurpose;
import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.ChecksAssessment;
import com.finapp.kyc.DecisionOutcome;
import com.finapp.kyc.DocumentVerificationAdapter;
import com.finapp.kyc.IdentityVerificationAdapter;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.ReviewTaskId;
import com.finapp.kyc.ScreeningAdapter;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.PartyRescreening;
import com.finapp.risk.RescreeningRun;
import com.finapp.risk.ScreeningClearances;
import com.finapp.risk.ScreeningList;
import com.finapp.risk.ScreeningListVersion;
import com.finapp.risk.ScreeningListVersions;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The rescreening over the database, through kyc's re-screening door and its simulated provider (`P13-TSK-022`, the
 * owner's decision O1; {@code PHASE_13_PLAN.md} sections 7.4, 12.10; {@code INV-RSK-06}, {@code INV-RSK-02},
 * {@code INV-RSK-07}; {@code INV-LIFE-04} as amended). The existing base is planted as it stands in production:
 * parties whose customer relationship is {@code ACTIVE}, each with a KYC case {@code APPROVED} and its onboarding
 * screening answered long <em>before</em> the list version is activated - the parties the leg could not rescreen
 * before the door. Then the composed application's {@link RescreeningRun} and {@link RescreeningDriver}, ten threads at
 * once where the race is the point:
 *
 * <ul>
 *   <li>{@link #anActivationEnqueuesEveryPartyOnce} - ten enqueuers and an injected crash: one permit per {@code ACTIVE}
 *       and per {@code PENDING} party (the review's MINOR 9), none for {@code SUSPENDED} or {@code CLOSED};</li>
 *   <li>{@link #tenSweepersRescreenEveryActivePartyOnceThroughKyc} - <strong>the acceptance criterion</strong>: every
 *       decided party rescreened through kyc exactly once - one keyed {@code RESCREEN} check each, one provider call
 *       each, every permit {@code DONE(CLEARED)} with one signal - and every decision standing;</li>
 *   <li>{@link #aPostDecisionHitAlertsOnceAndCasesAndTheDecisionStands} - a hit: one signal, one {@code SCREENING_HIT}
 *       fact alert (no policy) attached to one {@code AML} case; the KYC case still {@code APPROVED} (kyc-approved
 *       true), no kyc review task; T-eval's screening reading a hit; a re-drive creates nothing more; a second list's
 *       hit is its own alert (one per party and list version - the second review's IMPORTANT 3) in the one open case;</li>
 *   <li>{@link #aRescreenHitBeforeTheDecisionNeedsAPerson}, {@link #aRescreenHitOnACaseInReviewOpensItsTask} - the
 *       second review's IMPORTANT 1 (fail-closed): an undecided case's rescreen hit blocks the automatic approval and is
 *       resolved by a kyc reviewer, its decision naming it; a case in review gains the rescreen's task;</li>
 *   <li>{@link #aCallKilledMidFlightIsAskedAgain}, {@link #anOutageThenRecoveryIsCleared} - IMPORTANT 2: a question
 *       abandoned mid-call is asked again under the next ordinal; an outage's "we do not know" is asked again at the next
 *       claim, the provider's recovery the clearance;</li>
 *   <li>{@link #anOnboardingPartyWithNoCaseIsNotOnboarded} - IMPORTANT 4; {@link #theScreeningReadIsTheLatestDecidedPerType}
 *       - MINOR 6;</li>
 *   <li>{@link #aRescreenHitOnACaseReadyForDecisionHoldsTheDecision} - the last review's item 1: no decision past the
 *       hit's open task; {@link #aLateHitAfterAClearIsStillTold} - item 3: a late callback's hit on an abandoned
 *       question, after the permit concluded CLEARED, still alerted once;</li>
 *   <li>{@link #aLateCallbackForARescreenAssessesNothing} - a rescreen stranded {@code DISPATCHED} (a crash mid-call),
 *       its provider's signed callback a hit: completed, its evidence kept, the case not assessed - no review task, still
 *       {@code APPROVED} - and the leg records it from the row with no provider call of its own;</li>
 *   <li>{@link #tenAskersOfOneKeyMakeOneQuestion} - kyc's door under one key from ten threads: one row, one provider
 *       call;</li>
 *   <li>{@link #aSecondActivationOfOneVersionIsRefused} - two activators racing: one activation;</li>
 *   <li>{@link #theRanksBeneathTheDomain} - risk's persons and four eyes, kyc's frozen purpose and key, for a raw
 *       writer.</li>
 * </ul>
 *
 * <p><strong>Written and owed under C5</strong> (the standing criterion-7 deviation): the database tier is not run per
 * task. The database is shared with every other suite, so each case asserts over its own parties and its own versions.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the rescreening over the database: every active party once through kyc, a hit alerted once, the"
        + " decision standing (P13-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RescreeningDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final byte[] KEY = "a-32-byte-signing-key-for-tests!".getBytes(StandardCharsets.UTF_8);
    private static final String[] ALL_PATHS = {
        IdentityVerificationAdapter.PATH, DocumentVerificationAdapter.PATH, ScreeningAdapter.SANCTIONS_PATH,
        ScreeningAdapter.PEP_PATH, ScreeningAdapter.ADVERSE_MEDIA_PATH
    };

    private static SimulatedProvider provider;

    @Autowired private ScreeningListVersions lists;
    @Autowired private RescreeningRun run;
    @Autowired private RescreeningDriver driver;
    @Autowired private RiskTransactions transactions;
    @Autowired private VerificationRunService runs;
    @Autowired private CheckStore<Connection> checks;
    @Autowired private ScreeningClearances clearances;
    @Autowired private CaseAssessment assessment;
    @Autowired private ReviewService reviews;
    @Autowired private DecisionRecording decisions;
    @LocalServerPort private int port;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.kyc.provider.url", () -> provider.baseUrl());
        registry.add("finapp.kyc.provider.timeout", () -> "PT0.7S");
        registry.add("finapp.kyc.callback.key", () -> Base64.getEncoder().encodeToString(KEY));
    }

    @BeforeEach
    void reset() {
        provider.reset();
        for (String path : ALL_PATHS) {
            provider.succeedsWith(path, 200, "{\"status\":\"clear\"}");
        }
    }

    @Test
    @DisplayName("an activation enqueues every ACTIVE and every PENDING party once under ten enqueuers and an injected"
            + " crash mid-batch - none SUSPENDED or CLOSED; the census complete")
    void anActivationEnqueuesEveryPartyOnce() throws Exception {
        List<UUID> reached = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            reached.add(onboarded("ACTIVE", "APPROVED").party());
        }
        for (int i = 0; i < 3; i++) {
            reached.add(onboarded("PENDING", "CHECKS_IN_PROGRESS").party());
        }
        List<UUID> unreached = List.of(onboarded("SUSPENDED", "APPROVED").party(), plantedCustomer("CLOSED"));
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS);
        AtomicInteger crashes = new AtomicInteger();
        concurrently(10, () -> {
            while (true) {
                if (crashes.getAndIncrement() < 2) {
                    // The injected crash: a batch enqueued and then rolled back whole before its commit.
                    try {
                        transactions.inTransaction(uow -> {
                            run.enqueueNextBatch(uow, 7);
                            throw new IllegalStateException("crash before the commit");
                        });
                    } catch (IllegalStateException crashed) {
                        // Rolled back: its permits and its advance are gone together.
                    }
                    continue;
                }
                if (!transactions.inTransaction(uow -> run.enqueueNextBatch(uow, 7)).claimed()) {
                    return null;
                }
            }
        });
        for (UUID party : reached) {
            assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?",
                    party, version.id().value())).as("one permit per ACTIVE or PENDING party").isEqualTo(1);
        }
        for (UUID party : unreached) {
            assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ?", party))
                    .as("no SUSPENDED or CLOSED party").isZero();
        }
        assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE list_version_id = ?", version.id().value()))
                .as("the census is the version's own count")
                .isEqualTo(count("SELECT enqueued FROM risk.screening_list_version WHERE id = ?", version.id().value()));
        assertThat(count("SELECT count(*) FROM risk.screening_list_version WHERE id = ? AND enqueue_next_at IS NULL"
                + " AND enqueue_completed_at IS NOT NULL", version.id().value())).isEqualTo(1);
    }

    @Test
    @DisplayName("the acceptance criterion: ten sweepers rescreen every decided ACTIVE party exactly once THROUGH KYC -"
            + " one keyed RESCREEN check and one provider call each, asked after the activation on the database's"
            + " clock; every permit DONE CLEARED with one signal; every case still APPROVED, no review task")
    void tenSweepersRescreenEveryActivePartyOnceThroughKyc() throws Exception {
        List<Onboarded> base = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            base.add(onboarded("ACTIVE", "APPROVED"));
        }
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS);
        Instant activatedAt = version.activatedAt().orElseThrow();
        enqueueAll();
        int before = provider.requestCount(ScreeningAdapter.SANCTIONS_PATH);
        List<UUID> parties = base.stream().map(Onboarded::party).toList();
        concurrently(10, () -> {
            Instant deadline = Instant.now().plusSeconds(180);
            while (Instant.now().isBefore(deadline) && concludedOf(version, parties) < parties.size()) {
                driver.driveDue();
            }
            return null;
        });
        assertThat(provider.requestCount(ScreeningAdapter.SANCTIONS_PATH) - before)
                .as("one provider call per party - never two under ten sweepers").isEqualTo(parties.size());
        for (Onboarded onboarded : base) {
            UUID permit = scalarUuid("SELECT id FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?"
                    + " AND status = 'DONE' AND outcome = 'CLEARED'", onboarded.party(), version.id().value());
            String key = PartyRescreening.KEY_PREFIX + permit + "#0";
            assertThat(count("SELECT count(*) FROM kyc.verification_check WHERE case_id = ? AND purpose = 'RESCREEN'",
                    onboarded.kycCase())).as("one keyed rescreen on the decided case").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM kyc.verification_check WHERE rescreen_key = ? AND check_type ="
                    + " 'SANCTIONS' AND status = 'CLEAR' AND requested_at >= ?", key,
                    java.sql.Timestamp.from(activatedAt))).as("asked after the activation, one clock").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ? AND kind ="
                    + " 'SCREENING_CLEARED' AND list_version_ref = ?", permit, version.id().value())).isEqualTo(1);
            assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", onboarded.kycCase()))
                    .as("evidence beside the decision, never a re-decision").isEqualTo("APPROVED");
            assertThat(count("SELECT count(*) FROM kyc.review_task WHERE case_id = ?", onboarded.kycCase())).isZero();
            assertThat(scalarText("SELECT status FROM party.customer WHERE party_id = ?", onboarded.party()))
                    .isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("a post-decision hit: one SCREENING_HIT signal, one CRITICAL fact alert (no policy, the activation's"
            + " day) attached to one AML case; the KYC case still APPROVED - kyc-approved true - with no review task;"
            + " T-eval's screening reading a hit; a re-drive creates nothing more; a PEP version's hit is its own alert"
            + " (one per party and list version - the second review's IMPORTANT 3), folded into the one open AML case")
    void aPostDecisionHitAlertsOnceAndCasesAndTheDecisionStands() throws Exception {
        Onboarded onboarded = onboarded("ACTIVE", "APPROVED");
        UUID party = onboarded.party();
        ScreeningListVersion sanctions = activated(ScreeningList.SANCTIONS);
        enqueueAll();
        provider.succeedsWith(ScreeningAdapter.SANCTIONS_PATH, 200, "{\"status\":\"hit\"}");
        driveUntil(() -> concludedOf(sanctions, List.of(party)) == 1);
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE subject_ref = ? AND kind = 'SCREENING_HIT'",
                party.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'SCREENING_HIT'"
                + " AND rule_code = 'SCREENING_HIT' AND policy_version_id IS NULL AND window_kind = 'D1'"
                + " AND severity = 'CRITICAL' AND status = 'ATTACHED'", party.toString()))
                .as("a fact's alert, whatever policy is in force").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML'", party.toString()))
                .isEqualTo(1);
        assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", onboarded.kycCase()))
                .as("the case stays APPROVED - kycApproved stays true").isEqualTo("APPROVED");
        assertThat(count("SELECT count(*) FROM kyc.review_task WHERE case_id = ?", onboarded.kycCase()))
                .as("no kyc review task: risk's AML case is the work").isZero();
        assertThat(scalarText("SELECT status FROM party.customer WHERE party_id = ?", party)).isEqualTo("ACTIVE");
        assertThat(transactions.inSnapshot(uow -> clearances.latestFor(uow, party)).orElseThrow().hit())
                .as("T-eval reads the rescreen's hit").isTrue();
        driver.driveDue();
        driver.driveDue();

        provider.succeedsWith(ScreeningAdapter.PEP_PATH, 200, "{\"status\":\"hit\"}");
        ScreeningListVersion pep = activated(ScreeningList.PEP);
        enqueueAll();
        driveUntil(() -> concludedOf(pep, List.of(party)) == 1);
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE subject_ref = ? AND kind = 'SCREENING_HIT'",
                party.toString())).as("one signal per (party, version)").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'SCREENING_HIT'"
                + " AND status = 'ATTACHED' AND fact_signal_id = evidence_signal_ids[1]", party.toString()))
                .as("born once per (party, list version): two versions, two alerts, whatever the day")
                .isEqualTo(2);
        assertThat(count("SELECT count(DISTINCT attached_case_id) FROM risk.risk_alert WHERE subject_ref = ?"
                + " AND kind = 'SCREENING_HIT'", party.toString())).as("both folded into one case").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML'", party.toString()))
                .as("one open AML case per party").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM kyc.review_task WHERE case_id = ?", onboarded.kycCase())).isZero();
    }

    @Test
    @DisplayName("the second review's IMPORTANT 1, the automatic path (fail-closed): a rescreen HIT on an undecided case"
            + " - every onboarding check clear, its assessment not yet run - blocks the automatic approval: the case"
            + " IN_REVIEW with the rescreen's task, no decision, the customer PENDING; a re-assessment decides nothing;"
            + " the reviewer resolves it and the decision names it as evidence")
    void aRescreenHitBeforeTheDecisionNeedsAPerson() throws Exception {
        Undecided undecided = undecided("CHECKS_IN_PROGRESS", null);
        provider.succeedsWith(ScreeningAdapter.SANCTIONS_PATH, 200, "{\"status\":\"hit\"}");
        String key = PartyRescreening.KEY_PREFIX + IDS.next() + "#0";
        VerificationCheck hit = inFlow(() -> runs.rescreen(KycCaseId.of(undecided.kycCase()), undecided.customer(),
                CheckType.SANCTIONS, key));
        assertThat(hit.status()).isEqualTo(CheckStatus.HIT);
        assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", undecided.kycCase()))
                .as("routed to review exactly as an onboarding hit").isEqualTo("IN_REVIEW");
        assertThat(count("SELECT count(*) FROM kyc.review_task WHERE case_id = ? AND check_id = ? AND status = 'OPEN'",
                undecided.kycCase(), hit.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM kyc.kyc_decision WHERE case_id = ?", undecided.kycCase())).isZero();
        assertThat(scalarText("SELECT status FROM party.customer WHERE id = ?", undecided.customer())).isEqualTo("PENDING");

        // A duplicate callback's re-assessment: still blocked - the rescreen's hit is among what it reads.
        assertThat(inFlow(() -> assessment.assess(KycCaseId.of(undecided.kycCase()))).assessment())
                .isEqualTo(ChecksAssessment.BLOCKED);
        assertThat(count("SELECT count(*) FROM kyc.kyc_decision WHERE case_id = ?", undecided.kycCase())).isZero();

        IdentityId reviewer = IdentityId.of(IDS.next());
        UUID task = scalarUuid("SELECT id FROM kyc.review_task WHERE check_id = ?", hit.id().value());
        asReviewer(() -> reviews.resolve(KycCaseId.of(undecided.kycCase()), ReviewTaskId.of(task), reviewer,
                "a namesake: the date of birth differs"));
        assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", undecided.kycCase()))
                .isEqualTo("READY_FOR_DECISION");
        asReviewer(() -> decisions.byReviewer(KycCaseId.of(undecided.kycCase()), reviewer, DecisionOutcome.APPROVED,
                "the screening hit resolved as a namesake"));
        assertThat(count("SELECT count(*) FROM kyc.kyc_decision_check c JOIN kyc.kyc_decision d ON d.id = c.decision_id"
                + " WHERE d.case_id = ? AND c.check_id = ?", undecided.kycCase(), hit.id().value()))
                .as("the decision record names the rescreen it rests on (INV-KYC-02)").isEqualTo(1);
        assertThat(scalarText("SELECT status FROM party.customer WHERE id = ?", undecided.customer())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the second review's IMPORTANT 1, the IN_REVIEW path: a rescreen HIT on a case already in review opens"
            + " its own task; resolving the onboarding hit's task does not exit review while the rescreen's is open; the"
            + " reviewer reads both checks, each with its purpose")
    void aRescreenHitOnACaseInReviewOpensItsTask() throws Exception {
        Undecided undecided = undecided("IN_REVIEW", "SANCTIONS");
        provider.succeedsWith(ScreeningAdapter.PEP_PATH, 200, "{\"status\":\"hit\"}");
        VerificationCheck hit = inFlow(() -> runs.rescreen(KycCaseId.of(undecided.kycCase()), undecided.customer(),
                CheckType.PEP, PartyRescreening.KEY_PREFIX + IDS.next() + "#0"));
        assertThat(count("SELECT count(*) FROM kyc.review_task WHERE case_id = ? AND status = 'OPEN'",
                undecided.kycCase())).as("the onboarding hit's task and the rescreen's").isEqualTo(2);

        IdentityId reviewer = IdentityId.of(IDS.next());
        UUID onboardingTask = scalarUuid("SELECT id FROM kyc.review_task WHERE check_id = ?", undecided.onboardingHit());
        asReviewer(() -> reviews.resolve(KycCaseId.of(undecided.kycCase()), ReviewTaskId.of(onboardingTask), reviewer,
                "the onboarding hit is a namesake"));
        assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", undecided.kycCase()))
                .as("the rescreen's task still open: no exit").isEqualTo("IN_REVIEW");
        ReviewService.CaseFile file = asReviewer(() -> reviews.readCase(KycCaseId.of(undecided.kycCase())))
                .orElseThrow();
        assertThat(file.checks()).filteredOn(check -> check.id().equals(hit.id())).singleElement()
                .satisfies(check -> assertThat(check.purpose()).isEqualTo(CheckPurpose.RESCREEN));
        assertThat(file.checks()).filteredOn(check -> check.purpose() == CheckPurpose.ONBOARDING).hasSize(5);

        UUID rescreenTask = scalarUuid("SELECT id FROM kyc.review_task WHERE check_id = ?", hit.id().value());
        asReviewer(() -> reviews.resolve(KycCaseId.of(undecided.kycCase()), ReviewTaskId.of(rescreenTask), reviewer,
                "the PEP hit is a namesake too"));
        assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", undecided.kycCase()))
                .isEqualTo("READY_FOR_DECISION");
    }

    @Test
    @DisplayName("the last review's item 1: a rescreen HIT on a READY_FOR_DECISION case opens its task and holds the"
            + " decision - the reviewer's decision NOT_READY while the task is OPEN; resolved, the decision is recorded"
            + " naming the hit")
    void aRescreenHitOnACaseReadyForDecisionHoldsTheDecision() throws Exception {
        Undecided undecided = undecided("READY_FOR_DECISION", null);
        provider.succeedsWith(ScreeningAdapter.SANCTIONS_PATH, 200, "{\"status\":\"hit\"}");
        VerificationCheck hit = inFlow(() -> runs.rescreen(KycCaseId.of(undecided.kycCase()), undecided.customer(),
                CheckType.SANCTIONS, PartyRescreening.KEY_PREFIX + IDS.next() + "#0"));
        assertThat(count("SELECT count(*) FROM kyc.review_task WHERE case_id = ? AND check_id = ? AND status = 'OPEN'",
                undecided.kycCase(), hit.id().value())).isEqualTo(1);
        assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", undecided.kycCase()))
                .as("READY_FOR_DECISION cannot move back").isEqualTo("READY_FOR_DECISION");

        IdentityId reviewer = IdentityId.of(IDS.next());
        assertThat(asReviewer(() -> decisions.byReviewer(KycCaseId.of(undecided.kycCase()), reviewer,
                DecisionOutcome.APPROVED, "approved before the hit was seen")))
                .as("never decided past an OPEN task (INV-KYC-04)").isEqualTo(DecisionRecording.Recording.NOT_READY);
        assertThat(count("SELECT count(*) FROM kyc.kyc_decision WHERE case_id = ?", undecided.kycCase())).isZero();

        UUID task = scalarUuid("SELECT id FROM kyc.review_task WHERE check_id = ?", hit.id().value());
        asReviewer(() -> reviews.resolve(KycCaseId.of(undecided.kycCase()), ReviewTaskId.of(task), reviewer,
                "a namesake: the date of birth differs"));
        assertThat(asReviewer(() -> decisions.byReviewer(KycCaseId.of(undecided.kycCase()), reviewer,
                DecisionOutcome.APPROVED, "the screening hit resolved as a namesake")))
                .isEqualTo(DecisionRecording.Recording.RECORDED);
        assertThat(count("SELECT count(*) FROM kyc.kyc_decision_check c JOIN kyc.kyc_decision d ON d.id = c.decision_id"
                + " WHERE d.case_id = ? AND c.check_id = ?", undecided.kycCase(), hit.id().value())).isEqualTo(1);
    }

    @Test
    @DisplayName("the last review's item 3: an abandoned question's HIT arriving by callback after the permit concluded"
            + " CLEARED on a newer question is still told - a late SCREENING_HIT signal born of kyc's check, its own alert"
            + " in the party's AML case; the permit stays CLEARED")
    void aLateHitAfterAClearIsStillTold() throws Exception {
        Onboarded onboarded = onboarded("ACTIVE", "APPROVED");
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS);
        enqueueAll();
        UUID permit = scalarUuid("SELECT id FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?",
                onboarded.party(), version.id().value());
        String key = PartyRescreening.KEY_PREFIX + permit;
        UUID abandoned = IDS.next();
        try (Connection migrator = DatabaseRoles.migrator()) {
            execute(migrator, "UPDATE risk.rescreening_permit SET attempts = 1,"
                    + " next_attempt_at = clock_timestamp() + interval '1 millisecond' WHERE id = ?", permit);
            execute(migrator, "INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at,"
                    + " status_changed_at, purpose, rescreen_key) VALUES (?, ?, 'SANCTIONS', 'DISPATCHED',"
                    + " clock_timestamp() - interval '10 minute', clock_timestamp() - interval '10 minute', 'RESCREEN',"
                    + " ?)", abandoned, onboarded.kycCase(), key + "#0");
        }
        Thread.sleep(20);
        driveUntil(() -> concludedOf(version, List.of(onboarded.party())) == 1);
        assertThat(scalarText("SELECT outcome FROM risk.rescreening_permit WHERE id = ?", permit)).isEqualTo("CLEARED");

        String body = "{\"deliveryId\":\"late-" + UUID.randomUUID() + "\",\"checkId\":\"" + abandoned
                + "\",\"status\":\"hit\"}";
        assertThat(provider.deliverSignedCallback(URI.create("http://localhost:" + port + "/v1/providers/kyc/callbacks"),
                body, KEY, 1)).isBetween(200, 299);
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE kind = 'SCREENING_HIT' AND source_event_id = ?",
                abandoned)).as("born of kyc's check").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'SCREENING_HIT'"
                + " AND status = 'ATTACHED'", onboarded.party().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML'",
                onboarded.party().toString())).isEqualTo(1);
        assertThat(scalarText("SELECT outcome FROM risk.rescreening_permit WHERE id = ?", permit))
                .as("the permit as it concluded").isEqualTo("CLEARED");
        assertThat(provider.deliverSignedCallback(URI.create("http://localhost:" + port + "/v1/providers/kyc/callbacks"),
                body, KEY, 1)).as("a redelivery").isBetween(200, 299);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'SCREENING_HIT'",
                onboarded.party().toString())).as("told once").isEqualTo(1);
    }

    @Test
    @DisplayName("the second review's IMPORTANT 2, a restart mid-call: the permit's question dispatched more than a lease"
            + " ago and never completed is abandoned at the next claim and the next ordinal asked - one provider call, the"
            + " permit DONE on the new question's answer, never failed waiting")
    void aCallKilledMidFlightIsAskedAgain() throws Exception {
        Onboarded onboarded = onboarded("ACTIVE", "APPROVED");
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS);
        enqueueAll();
        UUID permit = scalarUuid("SELECT id FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?",
                onboarded.party(), version.id().value());
        String key = PartyRescreening.KEY_PREFIX + permit;
        try (Connection migrator = DatabaseRoles.migrator()) {
            // What a claim that died mid-call leaves: one attempt counted, its question dispatched ten minutes ago.
            execute(migrator, "UPDATE risk.rescreening_permit SET attempts = 1,"
                    + " next_attempt_at = clock_timestamp() + interval '1 millisecond' WHERE id = ?", permit);
            execute(migrator, "INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at,"
                    + " status_changed_at, purpose, rescreen_key) VALUES (?, ?, 'SANCTIONS', 'DISPATCHED',"
                    + " clock_timestamp() - interval '10 minute', clock_timestamp() - interval '10 minute', 'RESCREEN',"
                    + " ?)", IDS.next(), onboarded.kycCase(), key + "#0");
        }
        Thread.sleep(20);
        int before = provider.requestCount(ScreeningAdapter.SANCTIONS_PATH);
        driveUntil(() -> concludedOf(version, List.of(onboarded.party())) == 1);
        assertThat(scalarText("SELECT outcome FROM risk.rescreening_permit WHERE id = ?", permit)).isEqualTo("CLEARED");
        assertThat(scalarText("SELECT status FROM kyc.verification_check WHERE rescreen_key = ?", key + "#1"))
                .as("the next ordinal asked and answered").isEqualTo("CLEAR");
        assertThat(scalarText("SELECT status FROM kyc.verification_check WHERE rescreen_key = ?", key + "#0"))
                .as("the abandoned question stays as it stood").isEqualTo("DISPATCHED");
        assertThat(provider.requestCount(ScreeningAdapter.SANCTIONS_PATH) - before).isEqualTo(1);
        assertThat(scalarText("SELECT screening_ref::text FROM risk.risk_signal WHERE source_event_id = ?", permit))
                .isEqualTo(scalarText("SELECT id::text FROM kyc.verification_check WHERE rescreen_key = ?", key + "#1"));
    }

    @Test
    @DisplayName("the second review's IMPORTANT 2, an outage then recovery: the provider down at the first claim is"
            + " \"we do not know\" under #0; the next claim (its backoff fast-forwarded by a deferral-shaped write) asks"
            + " #1 and the provider's recovery is the permit's clearance - never three questions and a failure")
    void anOutageThenRecoveryIsCleared() throws Exception {
        Onboarded onboarded = onboarded("ACTIVE", "APPROVED");
        provider.failsThenSucceeds(ScreeningAdapter.SANCTIONS_PATH, 1, 200, "{\"status\":\"clear\"}");
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS);
        enqueueAll();
        UUID permit = scalarUuid("SELECT id FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?",
                onboarded.party(), version.id().value());
        String key = PartyRescreening.KEY_PREFIX + permit;
        driveUntil(() -> count("SELECT count(*) FROM risk.rescreening_permit WHERE id = ? AND attempts = 1"
                + " AND cause IS NOT NULL", permit) == 1);
        assertThat(scalarText("SELECT status FROM kyc.verification_check WHERE rescreen_key = ?", key + "#0"))
                .isEqualTo("INDETERMINATE");
        try (Connection migrator = DatabaseRoles.migrator()) {
            execute(migrator, "UPDATE risk.rescreening_permit SET next_attempt_at = clock_timestamp()"
                    + " + interval '1 millisecond' WHERE id = ?", permit);
        }
        Thread.sleep(20);
        driveUntil(() -> concludedOf(version, List.of(onboarded.party())) == 1);
        assertThat(scalarText("SELECT outcome FROM risk.rescreening_permit WHERE id = ?", permit)).isEqualTo("CLEARED");
        assertThat(scalarText("SELECT status FROM kyc.verification_check WHERE rescreen_key = ?", key + "#1"))
                .isEqualTo("CLEAR");
    }

    @Test
    @DisplayName("the second review's IMPORTANT 4: an onboarding (PENDING) party with no KYC case is DONE(NOT_ONBOARDED) -"
            + " no provider call, nothing recorded; an ACTIVE one with no case is never done - decided on nothing")
    void anOnboardingPartyWithNoCaseIsNotOnboarded() throws Exception {
        UUID pending = plantedCustomer("PENDING");
        UUID active = plantedCustomer("ACTIVE");
        ScreeningListVersion version = activated(ScreeningList.PEP);
        enqueueAll();
        int before = provider.requestCount(ScreeningAdapter.PEP_PATH);
        driveUntil(() -> concludedOf(version, List.of(pending)) == 1
                && count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?"
                        + " AND attempts >= 1", active, version.id().value()) == 1);
        assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?"
                + " AND status = 'DONE' AND outcome = 'NOT_ONBOARDED' AND signal_id IS NULL", pending,
                version.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?"
                + " AND status = 'PENDING' AND cause = 'NOT_SCREENED'", active, version.id().value()))
                .as("the exposure waits, failing at its bound, counted").isEqualTo(1);
        assertThat(provider.requestCount(ScreeningAdapter.PEP_PATH) - before).isZero();
    }

    @Test
    @DisplayName("the second review's MINOR 6: T-eval's read is per screening type the decided check asked last, both"
            + " purposes - an old onboarding clear under a newer rescreen hit reads the hit; nothing in flight reads")
    void theScreeningReadIsTheLatestDecidedPerType() throws Exception {
        Onboarded onboarded = onboarded("ACTIVE", "APPROVED");
        UUID rescreenHit = IDS.next();
        try (Connection migrator = DatabaseRoles.migrator()) {
            execute(migrator, "INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at,"
                    + " status_changed_at, purpose, rescreen_key) VALUES (?, ?, 'SANCTIONS', 'HIT',"
                    + " now() - interval '1 day', now() - interval '1 day', 'RESCREEN', ?)", rescreenHit,
                    onboarded.kycCase(), PartyRescreening.KEY_PREFIX + IDS.next() + "#0");
            execute(migrator, "INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at,"
                    + " status_changed_at, purpose, rescreen_key) VALUES (?, ?, 'SANCTIONS', 'DISPATCHED', now(),"
                    + " now(), 'RESCREEN', ?)", IDS.next(), onboarded.kycCase(),
                    PartyRescreening.KEY_PREFIX + IDS.next() + "#0");
        }
        List<VerificationCheck> latest = transactions.inSnapshot(uow -> checks.latestDecidedScreenings(uow,
                KycCaseId.of(onboarded.kycCase())));
        assertThat(latest).hasSize(2);
        assertThat(latest).filteredOn(check -> check.type() == CheckType.SANCTIONS).singleElement()
                .satisfies(check -> assertThat(check.id().value()).isEqualTo(rescreenHit));
        assertThat(latest).filteredOn(check -> check.type() == CheckType.PEP).singleElement()
                .satisfies(check -> assertThat(check.onboarding()).isTrue());
        assertThat(transactions.inSnapshot(uow -> clearances.latestFor(uow, onboarded.party())).orElseThrow().hit())
                .isTrue();
    }

    @Test
    @DisplayName("a late callback for a rescreen assesses nothing: a rescreen stranded DISPATCHED, its signed callback a"
            + " hit - completed and its evidence kept, the case not assessed (still APPROVED, no review task); the leg"
            + " then records it from the row with no provider call of its own")
    void aLateCallbackForARescreenAssessesNothing() throws Exception {
        Onboarded onboarded = onboarded("ACTIVE", "APPROVED");
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS);
        enqueueAll();
        UUID permit = scalarUuid("SELECT id FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?",
                onboarded.party(), version.id().value());
        String key = PartyRescreening.KEY_PREFIX + permit + "#0";
        // The state a crash mid-call leaves: the keyed rescreen dispatched, the provider never heard back from.
        VerificationCheck stranded;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            stranded = checks.requestRescreen(app, KycCaseId.of(onboarded.kycCase()), CheckType.SANCTIONS,
                    CheckId.next(IDS), key).check();
            assertThat(checks.dispatch(app, stranded.id(), Instant.now(CLOCK))).isTrue();
            app.commit();
        }
        int before = provider.requestCount(ScreeningAdapter.SANCTIONS_PATH);
        String body = "{\"deliveryId\":\"rescreen-" + UUID.randomUUID() + "\",\"checkId\":\"" + stranded.id().value()
                + "\",\"status\":\"hit\"}";
        assertThat(provider.deliverSignedCallback(URI.create("http://localhost:" + port + "/v1/providers/kyc/callbacks"),
                body, KEY, 1)).isBetween(200, 299);
        assertThat(scalarText("SELECT status FROM kyc.verification_check WHERE id = ?", stranded.id().value()))
                .isEqualTo(CheckStatus.HIT.name());
        assertThat(count("SELECT count(*) FROM kyc.verification_evidence WHERE check_id = ?", stranded.id().value()))
                .as("the provider's statement kept").isEqualTo(1);
        assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", onboarded.kycCase()))
                .as("a rescreen's callback assesses nothing").isEqualTo("APPROVED");
        assertThat(count("SELECT count(*) FROM kyc.review_task WHERE case_id = ?", onboarded.kycCase())).isZero();

        driveUntil(() -> concludedOf(version, List.of(onboarded.party())) == 1);
        assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE id = ? AND outcome = 'HIT'", permit))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'SCREENING_HIT'",
                onboarded.party().toString())).isEqualTo(1);
        assertThat(provider.requestCount(ScreeningAdapter.SANCTIONS_PATH) - before)
                .as("the answer was the row's: no second question").isZero();
        assertThat(count("SELECT count(*) FROM kyc.review_task WHERE case_id = ?", onboarded.kycCase())).isZero();
    }

    @Test
    @DisplayName("kyc's door under one key from ten threads: one RESCREEN row, one provider call, one outcome")
    void tenAskersOfOneKeyMakeOneQuestion() throws Exception {
        Onboarded onboarded = onboarded("ACTIVE", "APPROVED");
        String key = PartyRescreening.KEY_PREFIX + IDS.next() + "#0";
        int before = provider.requestCount(ScreeningAdapter.PEP_PATH);
        concurrently(10, () -> {
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()))) {
                runs.rescreen(KycCaseId.of(onboarded.kycCase()), onboarded.customer(), CheckType.PEP, key);
            }
            return null;
        });
        assertThat(count("SELECT count(*) FROM kyc.verification_check WHERE rescreen_key = ?", key)).isEqualTo(1);
        assertThat(provider.requestCount(ScreeningAdapter.PEP_PATH) - before).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'kyc.CheckCompleted' AND target_id ="
                + " (SELECT id::text FROM kyc.verification_check WHERE rescreen_key = ?)", key)).isEqualTo(1);
        assertThat(scalarText("SELECT status FROM kyc.kyc_case WHERE id = ?", onboarded.kycCase())).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("a second activation of one version is refused: two activators racing leave one activation, the other"
            + " ListVersionStale; one ACTIVE per list")
    void aSecondActivationOfOneVersionIsRefused() throws Exception {
        ScreeningListVersion recorded = recorded(ScreeningList.PEP);
        AtomicInteger stale = new AtomicInteger();
        concurrently(2, () -> {
            try {
                inFlow(() -> transactions.inTransaction(uow -> lists.activate(uow, recorded.id(), "in force",
                        person(), correlation())));
            } catch (ScreeningListVersions.ListVersionStale refused) {
                stale.incrementAndGet();
            }
            return null;
        });
        assertThat(stale).hasValue(1);
        assertThatThrownBy(() -> inFlow(() -> transactions.inTransaction(uow -> lists.activate(uow, recorded.id(),
                "again", person(), correlation())))).isInstanceOf(ScreeningListVersions.ListVersionStale.class);
        assertThat(count("SELECT count(*) FROM risk.screening_list_version WHERE list = 'PEP' AND status = 'ACTIVE'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the ranks beneath the domain, for a raw writer: risk's persons and four eyes, a permit for a version not"
            + " in force refused, nothing deleted; kyc's rescreen keyed, a screening, unique by key, its purpose and key"
            + " frozen")
    void theRanksBeneathTheDomain() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            assertThat(sqlState(app, "INSERT INTO risk.screening_list_version (id, list, version, ordinal, status,"
                    + " recorded_by, recorded_by_type, recorded_at, enqueued, correlation_id) SELECT '" + IDS.next()
                    + "', 'SANCTIONS', 'raw-" + UUID.randomUUID().toString().substring(0, 8) + "', coalesce(max(ordinal), 0)"
                    + " + 1, 'RECORDED', 'platform', 'SYSTEM', now(), 0, 'c' FROM risk.screening_list_version WHERE list"
                    + " = 'SANCTIONS'")).as("a SYSTEM recorder").isEqualTo(CHECK_VIOLATION);
        }
        ScreeningListVersion recorded = recorded(ScreeningList.SANCTIONS);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(sqlState(app, "UPDATE risk.screening_list_version SET status = 'ACTIVE', activated_by = '"
                    + recorded.recordedBy().id() + "', activated_by_type = 'CUSTOMER' WHERE id = '" + recorded.id().value()
                    + "'")).as("four eyes for every writer").isEqualTo(CHECK_VIOLATION);
            assertThat(sqlState(app, "INSERT INTO risk.rescreening_permit (id, party_id, list_version_id, status,"
                    + " attempts, next_attempt_at, enqueued_at, correlation_id) VALUES ('" + IDS.next() + "', '"
                    + IDS.next() + "', '" + recorded.id().value() + "', 'PENDING', 0, now(), now(), 'c')"))
                    .as("a permit for a version not in force").isEqualTo(CHECK_VIOLATION);
            assertThat(sqlState(app, "DELETE FROM risk.screening_list_version WHERE id = '" + recorded.id().value() + "'"))
                    .as("no DELETE for any role").isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
        Onboarded onboarded = onboarded("ACTIVE", "APPROVED");
        String insert = "INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at,"
                + " status_changed_at, purpose, rescreen_key) VALUES ('%s', '" + onboarded.kycCase() + "', '%s',"
                + " 'REQUESTED', now(), now(), '%s', %s)";
        try (Connection app = DatabaseRoles.application()) {
            assertThat(sqlState(app, String.format(insert, IDS.next(), "SANCTIONS", "RESCREEN", "NULL")))
                    .as("a rescreen is keyed").isEqualTo(CHECK_VIOLATION);
            assertThat(sqlState(app, String.format(insert, IDS.next(), "IDENTITY", "RESCREEN", "'k-identity'")))
                    .as("a rescreen is a screening").isEqualTo(CHECK_VIOLATION);
            assertThat(sqlState(app, String.format(insert, IDS.next(), "SANCTIONS", "ONBOARDING", "'k-onboarding'")))
                    .as("only a rescreen is keyed").isEqualTo(CHECK_VIOLATION);
            String key = "'k-" + UUID.randomUUID() + "'";
            assertThat(sqlState(app, String.format(insert, IDS.next(), "SANCTIONS", "RESCREEN", key)))
                    .isEqualTo("succeeded");
            assertThat(sqlState(app, String.format(insert, IDS.next(), "PEP", "RESCREEN", key)))
                    .as("one question per key").isEqualTo(UNIQUE_VIOLATION);
            assertThat(sqlState(app, "UPDATE kyc.verification_check SET purpose = 'ONBOARDING', rescreen_key = NULL"
                    + " WHERE rescreen_key = " + key)).as("the purpose and the key frozen").isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
        assertThat(scalarText("SELECT recorded_by_type FROM risk.screening_list_version WHERE id = ?",
                recorded.id().value())).as("the operator the session types CUSTOMER").isEqualTo("CUSTOMER");
    }

    // ------------------------------------------------------------------ fixtures

    /** A party onboarded long before any list version: its customer, its KYC case and its onboarding screenings. */
    private record Onboarded(UUID party, UUID customer, UUID kycCase) {}

    /**
     * A party whose customer relationship is {@code customerStatus}, with a KYC case in {@code caseStatus} whose
     * onboarding SANCTIONS and PEP checks were answered {@code CLEAR} 400 days ago - the existing base as production
     * holds it.
     */
    private static Onboarded onboarded(String customerStatus, String caseStatus) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID kycCase = IDS.next();
        try (Connection migrator = DatabaseRoles.migrator()) {
            execute(migrator, "INSERT INTO party.party (id, kind, display_name, registered_at)"
                    + " VALUES (?, 'PERSON', 'Rescreened Holder', now() - interval '400 day')", party);
            execute(migrator, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, ?, now() - interval '400 day', now() - interval '400 day')", customer, party,
                    customerStatus);
            execute(migrator, "INSERT INTO kyc.kyc_case (id, customer_id, status, policy_version, opened_at,"
                    + " status_changed_at, case_kind) VALUES (?, ?, ?, 'kyc-v1', now() - interval '400 day',"
                    + " now() - interval '399 day', 'KYC')", kycCase, customer, caseStatus);
            for (String type : List.of("SANCTIONS", "PEP")) {
                execute(migrator, "INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at,"
                        + " status_changed_at) VALUES (?, ?, ?, 'CLEAR', now() - interval '400 day',"
                        + " now() - interval '400 day')", IDS.next(), kycCase, type);
            }
        }
        return new Onboarded(party, customer, kycCase);
    }

    private record Undecided(UUID party, UUID customer, UUID kycCase, UUID onboardingHit) {}

    /**
     * An onboarding party (customer {@code PENDING}) whose KYC case stands in {@code caseStatus}, every required type's
     * onboarding check answered {@code CLEAR} - but {@code hitType}'s, answered {@code HIT} with its review task OPEN,
     * when named. The case is undecided: the second review's IMPORTANT 1.
     */
    private static Undecided undecided(String caseStatus, String hitType) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID kycCase = IDS.next();
        UUID onboardingHit = null;
        try (Connection migrator = DatabaseRoles.migrator()) {
            execute(migrator, "INSERT INTO party.party (id, kind, display_name, registered_at)"
                    + " VALUES (?, 'PERSON', 'Onboarding Holder', now() - interval '1 day')", party);
            execute(migrator, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, 'PENDING', now() - interval '1 day', now() - interval '1 day')", customer, party);
            execute(migrator, "INSERT INTO kyc.kyc_case (id, customer_id, status, policy_version, opened_at,"
                    + " status_changed_at, case_kind) VALUES (?, ?, ?, 'kyc-v1', now() - interval '1 day',"
                    + " now() - interval '1 hour', 'KYC')", kycCase, customer, caseStatus);
            for (String type : List.of("IDENTITY", "DOCUMENT", "SANCTIONS", "PEP", "ADVERSE_MEDIA")) {
                UUID check = IDS.next();
                boolean hit = type.equals(hitType);
                execute(migrator, "INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at,"
                        + " status_changed_at) VALUES (?, ?, ?, ?, now() - interval '2 hour', now() - interval '2 hour')",
                        check, kycCase, type, hit ? "HIT" : "CLEAR");
                if (hit) {
                    onboardingHit = check;
                    execute(migrator, "INSERT INTO kyc.review_task (id, case_id, check_id, status, opened_at)"
                            + " VALUES (?, ?, ?, 'OPEN', now() - interval '1 hour')", IDS.next(), kycCase, check);
                }
            }
        }
        return new Undecided(party, customer, kycCase, onboardingHit);
    }

    /** {@code work} as a KYC reviewer - a person, in a flow of its own. */
    private static <T> T asReviewer(java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope person = SecurityContext.enter(new Actor(UUID.randomUUID().toString(),
                        ActorType.EMPLOYEE));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    /** A party with a customer relationship in {@code status} and no KYC case; the party. */
    private static UUID plantedCustomer(String status) throws SQLException {
        UUID party = IDS.next();
        try (Connection migrator = DatabaseRoles.migrator()) {
            execute(migrator, "INSERT INTO party.party (id, kind, display_name, registered_at)"
                    + " VALUES (?, 'PERSON', 'Rescreened Holder', now() - interval '400 day')", party);
            execute(migrator, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, ?, now() - interval '400 day', now() - interval '400 day')", IDS.next(), party,
                    status);
        }
        return party;
    }

    /** A recorded version of {@code list} by one CUSTOMER-typed person. */
    private ScreeningListVersion recorded(ScreeningList list) {
        return inFlow(() -> transactions.inTransaction(uow -> lists.record(uow, list,
                "v-" + UUID.randomUUID().toString().substring(0, 12), "the provider published a new edition", person(),
                correlation()).version()));
    }

    /** A version recorded by one person and activated by another. */
    private ScreeningListVersion activated(ScreeningList list) {
        ScreeningListVersion recorded = recorded(list);
        return inFlow(() -> transactions.inTransaction(uow -> lists.activate(uow, recorded.id(), "in force", person(),
                correlation()).version()));
    }

    private void enqueueAll() {
        while (transactions.inTransaction(uow -> run.enqueueNextBatch(uow, 500)).claimed()) {
            // The next batch.
        }
    }

    private void driveUntil(Callable<Boolean> done) throws Exception {
        Instant deadline = Instant.now().plusSeconds(180);
        while (!done.call()) {
            assertThat(Instant.now()).as("the leg concluded the permit in time").isBefore(deadline);
            driver.driveDue();
        }
    }

    private long concludedOf(ScreeningListVersion version, List<UUID> parties) throws SQLException {
        long done = 0;
        for (UUID party : parties) {
            done += count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ? AND"
                    + " status = 'DONE'", party, version.id().value());
        }
        return done;
    }

    private static Actor person() {
        return new Actor("officer-" + UUID.randomUUID(), ActorType.CUSTOMER);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static <T> T inFlow(java.util.function.Supplier<T> work) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    private static void concurrently(int threads, Callable<Void> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> running = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                running.add(pool.submit(() -> {
                    start.await();
                    try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
                        return work.call();
                    }
                }));
            }
            start.countDown();
            for (Future<Void> future : running) {
                future.get(300, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String sqlState(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
            return "succeeded";
        } catch (SQLException refused) {
            return refused.getSQLState();
        }
    }

    private static long count(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static UUID scalarUuid(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as(sql).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static Instant scalarInstant(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        }
    }

    private static String scalarText(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }
}
