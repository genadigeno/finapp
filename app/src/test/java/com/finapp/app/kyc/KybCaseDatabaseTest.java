package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.identity.IdentityId;
import com.finapp.kyc.BeneficialOwner;
import com.finapp.kyc.BeneficialOwnerStore;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.ControlRole;
import com.finapp.kyc.DecisionOutcome;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.kyc.VerificationProvider;
import com.finapp.party.PartyId;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The KYB case and its beneficial-ownership graph (`P2-TSK-015`): an organisation's case
 * decides only over a declared, fully-answered graph — and the gate holds under concurrency
 * because every clause is in the transition statement, made trustworthy by the case-row lock
 * both sides take.
 *
 * <p>The assessment here is constructed directly over the real stores with a stub provider
 * list: the assessment never calls a provider (it reads committed checks), and what these
 * tests exercise is the routing, the gate and the re-route — the provider wire has its own
 * suites (`P2-TSK-009`/`-010`/`-011`).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the KYB ownership gate (P2-TSK-015)")
@SuppressWarnings("try") // correlation and security scopes are used for their close side effect
class KybCaseDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Autowired private CheckStore<Connection> checkStore;
    @Autowired private ReviewTaskStore<Connection> reviewTaskStore;
    @Autowired private BeneficialOwnerStore<Connection> ownerStore;
    @Autowired private DecisionRecording decisionRecording;
    @Autowired private OwnerDeclaration ownerDeclaration;
    @Autowired private com.finapp.kyc.KycDecisionStore<Connection> decisionStore;
    @Autowired private com.finapp.party.PartyStore<Connection> parties;
    @Autowired private com.finapp.platform.audit.AuditWriter<Connection> auditWriter;

    @Autowired
    @Qualifier("kycTransactions")
    private TransactionTemplate kycTransactions;

    @Autowired private DataSource dataSource;

    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

    // -----------------------------------------------------------------
    // The acceptance, end to end

    @Test
    @DisplayName("an organisation decides only on a fully verified ownership graph")
    void theGraphGatesTheDecision() throws Exception {
        Person owner = givenAPerson();
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        declare(orgCase, owner.party(), OptionalInt.of(6_000), Optional.empty());

        CaseAssessment assessment = assessment();
        try (CorrelationContext.Scope flow = flow()) {
            assessment.assess(orgCase);
        }
        assertThat(statusOf(orgCase))
                .as("all checks CLEAR, but the owner's verification is not terminal: the gate"
                        + " holds in the statement")
                .isEqualTo("CHECKS_IN_PROGRESS");

        // The owner's own case goes all-clear and decides automatically (KYC kind) - and the
        // re-route is what re-asks the organisation's readiness question.
        addClearCheckAndBeginChecks(owner.kycCase());
        try (CorrelationContext.Scope flow = flow()) {
            assessment.assess(owner.kycCase());
        }

        assertThat(statusOf(owner.kycCase())).isEqualTo("APPROVED");
        assertThat(statusOf(orgCase))
                .as("the owner's terminal decision re-routed the parent")
                .isEqualTo("READY_FOR_DECISION");
        assertThat(decisionCountOf(orgCase))
                .as("a KYB case is never decided automatically - it awaits a KYC_REVIEWER")
                .isZero();

        try (CorrelationContext.Scope flow = flow();
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            assertThat(
                            decisionRecording.byReviewer(
                                    orgCase,
                                    IdentityId.of(IDS.next()),
                                    DecisionOutcome.APPROVED,
                                    "graph complete: sole 60% owner verified and approved"))
                    .isEqualTo(DecisionRecording.Recording.RECORDED);
        }
        assertThat(statusOf(orgCase)).isEqualTo("APPROVED");
        assertThat(customerStatusOf(orgCase))
                .as("the projection moves for an organisation exactly as for a person"
                        + " (INV-KYC-05)")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a KYB case with no declared owner cannot become ready - an empty graph is not"
            + " a verified graph")
    void anEmptyGraphIsNotAVerifiedGraph() throws Exception {
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        try (CorrelationContext.Scope flow = flow()) {
            assessment().assess(orgCase);
        }
        assertThat(statusOf(orgCase)).isEqualTo("CHECKS_IN_PROGRESS");
    }

    @Test
    @DisplayName("an owner added during review gates the exit, and the owner's decision reopens it")
    void anOwnerAddedDuringReviewReRoutes() throws Exception {
        KycCaseId orgCase = givenAKybCaseInReviewWithOneHit();
        Person owner = givenAPerson();
        declare(orgCase, owner.party(), OptionalInt.empty(), Optional.of(ControlRole.DIRECTOR));

        // The reviewer resolves the only task; the exit conditional now finds no open task -
        // and still refuses, because the owner's verification is not terminal.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            reviewTaskStore.resolve(
                    app,
                    openTaskOf(orgCase),
                    orgCase,
                    IDS.next(),
                    "false positive: name-only match",
                    Instant.now(CLOCK));
            assertThat(
                            cases.moveToReadyForDecision(
                                    app, orgCase, KycCaseStatus.IN_REVIEW, Instant.now(CLOCK)))
                    .as("no open task, but an unanswered owner: the exit stays closed")
                    .isFalse();
            app.commit();
        }
        assertThat(statusOf(orgCase)).isEqualTo("IN_REVIEW");

        // The owner's automatic decision re-routes the parent through the IN_REVIEW door.
        addClearCheckAndBeginChecks(owner.kycCase());
        try (CorrelationContext.Scope flow = flow()) {
            assessment().assess(owner.kycCase());
        }
        assertThat(statusOf(orgCase)).isEqualTo("READY_FOR_DECISION");
    }

    @Test
    @DisplayName("an owner decided by a REVIEWER re-routes the parent - the recording's own hook")
    void anOwnerDecidedByAReviewerReRoutes() throws Exception {
        // The sibling test above drives the re-route through the assess door (an automatic
        // decision); this one drives the OTHER door - an owner whose verification went to a
        // person, decided through DecisionRecording.byReviewer. The completion gate's sweep
        // is why it exists: with only the sibling, removing the byReviewer re-route survived.
        KycCaseId orgCase = givenAKybCaseInReviewWithOneHit();
        Person owner = givenAPerson();
        declare(orgCase, owner.party(), OptionalInt.empty(), Optional.of(ControlRole.DIRECTOR));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            reviewTaskStore.resolve(
                    app,
                    openTaskOf(orgCase),
                    orgCase,
                    IDS.next(),
                    "false positive: name-only match",
                    Instant.now(CLOCK));
            cases.moveToReadyForDecision(
                    app, orgCase, KycCaseStatus.IN_REVIEW, Instant.now(CLOCK));
            app.commit();
        }
        assertThat(statusOf(orgCase))
                .as("no open task, but an unanswered owner: the exit stays closed")
                .isEqualTo("IN_REVIEW");

        // The owner's case reaches READY_FOR_DECISION and a reviewer approves it. The
        // recording is built with this suite's assessment, the same way the suite builds
        // CaseAssessment itself - the context has no provider URL, so no assessment bean.
        addClearCheckAndBeginChecks(owner.kycCase());
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            cases.moveToReadyForDecision(
                    app, owner.kycCase(), KycCaseStatus.CHECKS_IN_PROGRESS, Instant.now(CLOCK));
            app.commit();
        }
        try (CorrelationContext.Scope flow = flow();
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            assertThat(
                            recordingWithReRoute()
                                    .byReviewer(
                                            owner.kycCase(),
                                            IdentityId.of(IDS.next()),
                                            DecisionOutcome.APPROVED,
                                            "identity verified in review"))
                    .isEqualTo(DecisionRecording.Recording.RECORDED);
        }
        assertThat(statusOf(orgCase))
                .as("the reviewer's decision on the owner answered the parent's question")
                .isEqualTo("READY_FOR_DECISION");
    }

    // -----------------------------------------------------------------
    // The distributed edge: declarations race the readiness transition

    @Test
    @DisplayName("a declaration racing the readiness transition serialises on the case-row lock,"
            + " and the reader that waited sees the owner")
    void aDeclarationRacingReadinessIsSeen() throws Exception {
        Person verified = givenAVerifiedPerson();
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        declare(orgCase, verified.party(), OptionalInt.of(5_000), Optional.empty());
        // As it stands the gate holds: one owner, terminal. The race adds an UNANSWERED owner.
        Person unverified = givenAPerson();

        try (Connection declarer = DatabaseRoles.application()) {
            declarer.setAutoCommit(false);
            assertThat(
                            ownerStore.declare(
                                    declarer,
                                    BeneficialOwner.declare(
                                            IDS,
                                            CLOCK,
                                            orgCase,
                                            unverified.party().value(),
                                            latestCaseOf(unverified),
                                            OptionalInt.of(1_000),
                                            Optional.empty())))
                    .isEqualTo(BeneficialOwnerStore.Declared.DECLARED);
            // Uncommitted: the declarer holds the case-row lock. A readiness mover must now
            // WAIT rather than decide on a snapshot that cannot see this owner - the write
            // skew a bare predicate cannot close (BeneficialOwnerStore.declare's argument).
            ExecutorService mover = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> readiness =
                        mover.submit(
                                (Callable<Boolean>)
                                        () -> {
                                            try (Connection racer =
                                                    DatabaseRoles.application()) {
                                                racer.setAutoCommit(false);
                                                boolean moved =
                                                        cases.moveToReadyForDecision(
                                                                racer,
                                                                orgCase,
                                                                KycCaseStatus
                                                                        .CHECKS_IN_PROGRESS,
                                                                Instant.now(CLOCK));
                                                racer.commit();
                                                return moved;
                                            }
                                        });
                awaitABlockedCaseLock();
                assertThat(readiness.isDone())
                        .as("the mover must be waiting on the declarer's lock, not deciding on"
                                + " a stale snapshot")
                        .isFalse();
                declarer.commit();
                assertThat(readiness.get(30, TimeUnit.SECONDS))
                        .as("resumed after the declaration committed, the mover's fresh"
                                + " statement sees the unanswered owner and refuses")
                        .isFalse();
            } finally {
                mover.shutdownNow();
            }
        }
        assertThat(statusOf(orgCase)).isEqualTo("CHECKS_IN_PROGRESS");
    }

    @Test
    @DisplayName("in the opposite order the case is ready first, and the late declaration is"
            + " refused: the set froze")
    void aLateDeclarationMeetsAFrozenSet() throws Exception {
        Person verified = givenAVerifiedPerson();
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        declare(orgCase, verified.party(), OptionalInt.of(5_000), Optional.empty());

        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertThat(
                            cases.moveToReadyForDecision(
                                    app, orgCase, KycCaseStatus.CHECKS_IN_PROGRESS,
                                    Instant.now(CLOCK)))
                    .as("one owner, terminal: the gate opens")
                    .isTrue();
            app.commit();
        }

        assertThat(
                        ownerDeclarationOf(
                                orgCase,
                                givenAVerifiedPerson().party(),
                                OptionalInt.of(1_000),
                                Optional.empty()))
                .as("from READY_FOR_DECISION on, the owner set is part of what the decision"
                        + " rests on - frozen")
                .isEqualTo(OwnerDeclaration.Declaration.CASE_NOT_ACCEPTING_OWNERS);
    }

    @Test
    @DisplayName("ten instances declaring one owner produce one row and nine honest answers")
    void tenInstancesDeclareOneOwnerOnce() throws Exception {
        Person owner = givenAVerifiedPerson();
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        KycCaseId verification = KycCaseId.of(latestCaseOf(owner).value());

        ExecutorService instances = Executors.newFixedThreadPool(10);
        try {
            List<Future<BeneficialOwnerStore.Declared>> answers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                answers.add(
                        instances.submit(
                                () -> {
                                    try (Connection own = DatabaseRoles.application()) {
                                        own.setAutoCommit(false);
                                        BeneficialOwnerStore.Declared declared =
                                                ownerStore.declare(
                                                        own,
                                                        BeneficialOwner.declare(
                                                                IDS,
                                                                CLOCK,
                                                                orgCase,
                                                                owner.party().value(),
                                                                verification,
                                                                OptionalInt.of(2_000),
                                                                Optional.empty()));
                                        own.commit();
                                        return declared;
                                    }
                                }));
            }
            int declared = 0;
            int alreadyDeclared = 0;
            for (Future<BeneficialOwnerStore.Declared> answer : answers) {
                switch (answer.get(60, TimeUnit.SECONDS)) {
                    case DECLARED -> declared++;
                    case ALREADY_DECLARED -> alreadyDeclared++;
                    default -> throw new AssertionError("unexpected declaration outcome");
                }
            }
            assertThat(declared).isEqualTo(1);
            assertThat(alreadyDeclared).isEqualTo(9);
        } finally {
            instances.shutdownNow();
        }
        assertThat(ownerRowCountOf(orgCase)).isEqualTo(1);
    }

    @Test
    @DisplayName("declared stakes cannot exceed the whole organisation")
    void stakesCannotExceedTheWhole() throws Exception {
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        declare(orgCase, givenAVerifiedPerson().party(), OptionalInt.of(6_000), Optional.empty());
        assertThat(
                        ownerDeclarationOf(
                                orgCase,
                                givenAVerifiedPerson().party(),
                                OptionalInt.of(5_000),
                                Optional.empty()))
                .isEqualTo(OwnerDeclaration.Declaration.STAKE_EXCEEDS_WHOLE);
    }

    // -----------------------------------------------------------------
    // The declaration service's recorded Phase 2 bounds, and its audit record

    @Test
    @DisplayName("an organisation owner is refused: the graph is bounded at depth one")
    void anOrganisationOwnerIsRefused() throws Exception {
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        PartyId organisation = givenAParty("ORGANISATION");
        assertThat(
                        ownerDeclarationOf(
                                orgCase, organisation, OptionalInt.of(1_000), Optional.empty()))
                .isEqualTo(OwnerDeclaration.Declaration.OWNER_NOT_A_NATURAL_PERSON);
    }

    @Test
    @DisplayName("an owner with no verification history is refused - the registered-customer"
            + " bound, recorded")
    void anUnverifiableOwnerIsRefused() throws Exception {
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        PartyId strayParty = givenAParty("PERSON");
        assertThat(
                        ownerDeclarationOf(
                                orgCase, strayParty, OptionalInt.of(1_000), Optional.empty()))
                .isEqualTo(OwnerDeclaration.Declaration.OWNER_NOT_VERIFIABLE);
    }

    @Test
    @DisplayName("a KYC case cannot be declared onto, and the schema itself binds the kinds")
    void theKindsAreBoundAtEveryRank() throws Exception {
        Person person = givenAPerson();
        assertThat(
                        ownerDeclarationOf(
                                person.kycCase(),
                                person.party(),
                                OptionalInt.of(1_000),
                                Optional.empty()))
                .isEqualTo(OwnerDeclaration.Declaration.NOT_A_KYB_CASE);

        // DB-CONSTRAINT rank: the composite FK refuses an owner row on a KYC-kind case and a
        // verification reference to a KYB-kind case, whatever any code path does.
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        try (Connection app = DatabaseRoles.application()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(
                            () ->
                                    insertOwnerRow(
                                            app, person.kycCase(), person.party().value(),
                                            person.kycCase()))
                    .withMessageContaining("beneficial_owner")
                    .extracting(SQLException::getSQLState)
                    .isEqualTo("23503");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(
                            () ->
                                    insertOwnerRow(
                                            app, orgCase, person.party().value(), orgCase))
                    .extracting(SQLException::getSQLState)
                    .isEqualTo("23503");
        }
    }

    @Test
    @DisplayName("a declaration is audited against the case, naming owner and verification")
    void aDeclarationIsAudited() throws Exception {
        Person owner = givenAVerifiedPerson();
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        declare(orgCase, owner.party(), OptionalInt.of(4_000), Optional.of(ControlRole.DIRECTOR));

        assertThat(auditSummariesFor(orgCase))
                .as("kyc.OwnerDeclared carries identifiers and enumerated names only")
                .contains("ownerParty=" + owner.party().value())
                .contains("stakeBp=4000")
                .contains("role=DIRECTOR");
    }

    // -----------------------------------------------------------------
    // Privileges: append-only owner rows, and the narrowed case grant

    @Test
    @DisplayName("owner rows are append-only: UPDATE and DELETE are denied on every column")
    void ownerRowsAreAppendOnly() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            for (String column : columnsOf(app, "beneficial_owner")) {
                assertThatExceptionOfType(SQLException.class)
                        .as("UPDATE (%s) must be denied", column)
                        .isThrownBy(
                                () ->
                                        execute(
                                                app,
                                                "UPDATE kyc.beneficial_owner SET " + column
                                                        + " = " + column))
                        .extracting(SQLException::getSQLState)
                        .isEqualTo("42501");
            }
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(app, "DELETE FROM kyc.beneficial_owner"))
                    .extracting(SQLException::getSQLState)
                    .isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("the case grant narrowed: kind, customer, policy and opened_at are unwritable")
    void theCaseGrantIsNarrow() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            for (String frozen :
                    List.of("id", "customer_id", "case_kind", "policy_version", "opened_at")) {
                assertThatExceptionOfType(SQLException.class)
                        .as("UPDATE (%s) must be denied - a KYB -> KYC flip would disarm the"
                                + " ownership gate", frozen)
                        .isThrownBy(
                                () ->
                                        execute(
                                                app,
                                                "UPDATE kyc.kyc_case SET " + frozen + " = "
                                                        + frozen))
                        .extracting(SQLException::getSQLState)
                        .isEqualTo("42501");
            }
            // The positive control: the two columns the machine moves stay writable, which is
            // also what proves SELECT ... FOR UPDATE stays available to the lock protocol.
            execute(app, "UPDATE kyc.kyc_case SET status = status WHERE false");
            execute(
                    app,
                    "UPDATE kyc.kyc_case SET status_changed_at = status_changed_at WHERE false");
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    /** A natural person: party, PENDING customer, and their registration-opened KYC case. */
    private record Person(PartyId party, KycCaseId kycCase) {}

    private Person givenAPerson() throws SQLException {
        PartyId party = givenAParty("PERSON");
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'PENDING',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party.value());
            app.setAutoCommit(false);
            KycCase opened =
                    cases.openOrConverge(
                                    app, KycCase.open(IDS, CLOCK, customer, KycCaseKind.KYC))
                            .kycCase();
            app.commit();
            return new Person(party, opened.id());
        }
    }

    /** A person whose verification is already terminal: their case walked to APPROVED. */
    private Person givenAVerifiedPerson() throws SQLException {
        Person person = givenAPerson();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            cases.moveStatus(
                    app,
                    person.kycCase(),
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            cases.moveToReadyForDecision(
                    app, person.kycCase(), KycCaseStatus.CHECKS_IN_PROGRESS, Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    person.kycCase(),
                    KycCaseStatus.READY_FOR_DECISION,
                    KycCaseStatus.APPROVED,
                    Instant.now(CLOCK));
            app.commit();
        }
        return person;
    }

    private PartyId givenAParty(String kind) throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, '" + kind + "', 'Grace Hopper', now())",
                    party);
        }
        return PartyId.of(party);
    }

    /** An ORGANISATION customer's KYB case at CHECKS_IN_PROGRESS with one CLEAR check. */
    private KycCaseId givenAKybCaseInChecksWithAClearCheck() throws SQLException {
        PartyId party = givenAParty("ORGANISATION");
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'PENDING',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party.value());
            app.setAutoCommit(false);
            KycCase opened =
                    cases.openOrConverge(
                                    app, KycCase.open(IDS, CLOCK, customer, KycCaseKind.KYB))
                            .kycCase();
            addClearCheck(app, opened.id());
            cases.moveStatus(
                    app,
                    opened.id(),
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            app.commit();
            return opened.id();
        }
    }

    /** A KYB case IN_REVIEW with one HIT and its open task — the review-door fixture. */
    private KycCaseId givenAKybCaseInReviewWithOneHit() throws SQLException {
        KycCaseId orgCase = givenAKybCaseInChecksWithAClearCheck();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            VerificationCheck hit =
                    checkStore
                            .requestOrConverge(
                                    app,
                                    VerificationCheck.request(
                                            IDS, CLOCK, orgCase, CheckType.PEP))
                            .check();
            checkStore.dispatch(app, hit.id(), Instant.now(CLOCK));
            checkStore.complete(app, hit.id(), CheckOutcome.HIT, Instant.now(CLOCK));
            reviewTaskStore.openForCheck(app, ReviewTask.open(IDS, CLOCK, orgCase, hit.id()));
            cases.moveStatus(
                    app,
                    orgCase,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    KycCaseStatus.IN_REVIEW,
                    Instant.now(CLOCK));
            app.commit();
        }
        return orgCase;
    }

    private void addClearCheckAndBeginChecks(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            addClearCheck(app, caseId);
            cases.moveStatus(
                    app,
                    caseId,
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            app.commit();
        }
    }

    private void addClearCheck(Connection app, KycCaseId caseId) {
        VerificationCheck check =
                checkStore
                        .requestOrConverge(
                                app,
                                VerificationCheck.request(IDS, CLOCK, caseId, CheckType.SANCTIONS))
                        .check();
        checkStore.dispatch(app, check.id(), Instant.now(CLOCK));
        checkStore.complete(app, check.id(), CheckOutcome.CLEAR, Instant.now(CLOCK));
    }

    /** The real recording with this suite's assessment as its re-route hook. */
    private DecisionRecording recordingWithReRoute() {
        return new DecisionRecording(
                cases,
                checkStore,
                decisionStore,
                parties,
                auditWriter,
                IDS,
                CLOCK,
                kycTransactions,
                dataSource,
                new org.springframework.beans.factory.ObjectProvider<CaseAssessment>() {
                    @Override
                    public CaseAssessment getIfAvailable() {
                        return assessment();
                    }
                });
    }

    /** The assessment over the real stores, with the one stub type no test ever dispatches. */
    private CaseAssessment assessment() {
        VerificationProvider sanctionsOnly =
                new VerificationProvider() {
                    @Override
                    public CheckType checkType() {
                        return CheckType.SANCTIONS;
                    }

                    @Override
                    public ProviderResult verify(VerificationSubject subject) {
                        throw new UnsupportedOperationException(
                                "the assessment reads committed checks; it never calls a"
                                        + " provider");
                    }
                };
        return new CaseAssessment(
                cases,
                checkStore,
                reviewTaskStore,
                ownerStore,
                decisionRecording,
                List.of(sanctionsOnly),
                IDS,
                CLOCK,
                kycTransactions,
                dataSource);
    }

    private void declare(
            KycCaseId orgCase, PartyId owner, OptionalInt stake, Optional<ControlRole> role)
            throws SQLException {
        assertThat(ownerDeclarationOf(orgCase, owner, stake, role))
                .isEqualTo(OwnerDeclaration.Declaration.DECLARED);
    }

    private OwnerDeclaration.Declaration ownerDeclarationOf(
            KycCaseId orgCase, PartyId owner, OptionalInt stake, Optional<ControlRole> role) {
        try (CorrelationContext.Scope flow = flow();
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return ownerDeclaration.declare(orgCase, owner, stake, role);
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    // -----------------------------------------------------------------
    // Probes

    private String statusOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement("SELECT status FROM kyc.kyc_case WHERE id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private String customerStatusOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT c.status FROM party.customer c"
                                        + " JOIN kyc.kyc_case k ON k.customer_id = c.id"
                                        + " WHERE k.id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private long decisionCountOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.kyc_decision WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long ownerRowCountOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.beneficial_owner WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private com.finapp.kyc.ReviewTaskId openTaskOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT id FROM kyc.review_task WHERE case_id = ?"
                                        + " AND status = 'OPEN'")) {
            select.setObject(1, caseId.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return com.finapp.kyc.ReviewTaskId.of(row.getObject(1, UUID.class));
            }
        }
    }

    /** The case a declaration would pin for this person — their one case, in these fixtures. */
    private static KycCaseId latestCaseOf(Person person) {
        return person.kycCase();
    }

    private String auditSummariesFor(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT coalesce(string_agg(coalesce(change_summary, ''), ' '),"
                                        + " '') FROM platform.audit_record"
                                        + " WHERE operation = 'kyc.OwnerDeclared'"
                                        + " AND target_id = ?")) {
            select.setString(1, caseId.value().toString());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    /** Waits until PostgreSQL reports a backend blocked on our case-row lock (P0-TST-004). */
    private static void awaitABlockedCaseLock() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                // JDBC renders the placeholder as $1, so the blocked
                                // statement reads "... WHERE id = $1 FOR UPDATE"; the observer
                                // itself never matches, because it is running, not Lock-waiting.
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND query LIKE '%kyc.kyc_case%'"
                                        + " AND query LIKE '%FOR UPDATE%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "the readiness mover never blocked on the case-row lock - without the lock the"
                        + " gate is a stale-snapshot guess");
    }

    private void insertOwnerRow(
            Connection app, KycCaseId caseId, UUID ownerParty, KycCaseId verification)
            throws SQLException {
        try (PreparedStatement insert =
                app.prepareStatement(
                        "INSERT INTO kyc.beneficial_owner (id, case_id, owner_party_id,"
                                + " verification_case_id, stake_basis_points, declared_at)"
                                + " VALUES (?, ?, ?, ?, 1000, now())")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, caseId.value());
            insert.setObject(3, ownerParty);
            insert.setObject(4, verification.value());
            insert.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private static List<String> columnsOf(Connection connection, String table)
            throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_schema = 'kyc' AND table_name = ?")) {
            select.setString(1, table);
            try (ResultSet rows = select.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
                assertThat(columns).as("the sweep must see the table").isNotEmpty();
                return columns;
            }
        }
    }
}
