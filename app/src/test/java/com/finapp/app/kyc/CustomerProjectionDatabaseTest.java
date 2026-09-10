package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.DecisionOutcome;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycDecisionStore;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.JdbcPartyStore;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The projection, atomically (`P2-TSK-014`, ADR-0035, {@code INV-KYC-05}).
 *
 * <p>The acceptance, clause by clause: <strong>an approved case's customer is
 * {@code ACTIVE}</strong> (both doors — the reviewer's endpoint and the automatic run),
 * <strong>atomically</strong> (a failure injected at the projection — the recording's last
 * write — and a backend killed mid-transaction each leave <em>nothing</em>: no decision, no
 * moved status, the case still awaiting), and <strong>the reconciliation sweep proves the pair
 * cannot drift</strong>, in both directions — a decision's customer moved, and no customer
 * under verification left {@code PENDING} without a decision authorizing it.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("a decision moves the customer projection (P2-TSK-014)")
@SuppressWarnings("try") // correlation and security scopes are used for their close side effect
class CustomerProjectionDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static final String REJECTION =
            "{\"outcome\":\"REJECTED\",\"reason\":\"confirmed sanctions hit, ticket OPS-9040\"}";
    private static final String APPROVAL =
            "{\"outcome\":\"APPROVED\",\"reason\":\"false positive, cleared against list entry\"}";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @Autowired private Authorization authorization;
    @Autowired private CheckStore<Connection> checkStore;
    @Autowired private ReviewTaskStore<Connection> reviewTaskStore;
    @Autowired private KycDecisionStore<Connection> decisionStore;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private VerificationRunService runs;

    @Autowired
    @Qualifier("kycTransactions")
    private TransactionTemplate kycTransactions;

    @Autowired private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

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
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The acceptance, both doors

    @Test
    @DisplayName("a reviewer's approval activates the customer, in the decision's transaction")
    void anApprovalActivatesTheCustomer() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();
        Instant fixtureStamp = statusChangedAtOf(fixture.customerId());

        assertThat(post(decisionOf(fixture.caseId()), givenASessionFor(givenAReviewer()),
                                APPROVAL)
                        .statusCode())
                .isEqualTo(204);

        assertThat(customerStatusOf(fixture.customerId()))
                .as("the PENDING Phase 1 parked finally moves (ADR-0035)")
                .isEqualTo("ACTIVE");
        assertThat(statusChangedAtOf(fixture.customerId()))
                .as("the move is dated by the decision, not left at the fixture's back-dated"
                        + " stamp")
                .isNotEqualTo(fixtureStamp);
    }

    @Test
    @DisplayName("the automatic door projects too: an all-clear run leaves the customer ACTIVE")
    void anAllClearRunActivatesTheCustomer() throws Exception {
        Fixture fixture = givenAnOpenCase();
        stubEveryPathClear();

        run(fixture);

        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("APPROVED");
        assertThat(customerStatusOf(fixture.customerId()))
                .as("one recording routine, both doors - which is what DecisionRecording"
                        + " being one class buys")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a rejection rejects the customer, and frees the party's one-live slot")
    void aRejectionRejectsTheCustomer() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();

        assertThat(post(decisionOf(fixture.caseId()), givenASessionFor(givenAReviewer()),
                                REJECTION)
                        .statusCode())
                .isEqualTo(204);

        assertThat(customerStatusOf(fixture.customerId()))
                .as("REJECTED, not CLOSED: refused and ended are different facts, and the"
                        + " projection stays faithful to the decision it mirrors (INV-KYC-05)")
                .isEqualTo("REJECTED");
        // The freed slot, behaviourally: a terminal relationship is not a live one, so
        // re-onboarding after changed circumstances is a NEW Customer (INV-LIFE-04) - the
        // index predicate V005 widened, proven by the insert it now admits.
        assertThatCode(
                        () -> {
                            try (Connection app = DatabaseRoles.application()) {
                                execute(
                                        app,
                                        "INSERT INTO party.customer (id, party_id, status,"
                                                + " opened_at, status_changed_at)"
                                                + " VALUES (?, ?, 'PENDING', now(), now())",
                                        IDS.next(),
                                        fixture.partyId());
                            }
                        })
                .as("a rejected party can be re-onboarded as a new relationship")
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------
    // Atomicity: no between exists

    @Test
    @DisplayName("a failure at the projection - the last write - leaves nothing at all")
    void anInjectedFailureLeavesNothing() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();
        DecisionRecording failingAtTheProjection =
                recordingOver(
                        new FailingMove(new JdbcPartyStore(), FailingMove.Mode.THROW));

        Throwable failure = decideExpectingFailure(failingAtTheProjection, fixture);

        assertThat(failure).as("the injected failure must actually propagate").isNotNull();
        assertThat(decisionCountOf(fixture.caseId()))
                .as("no decision without its projection (INV-KYC-05): one transaction commits"
                        + " both or neither")
                .isZero();
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("READY_FOR_DECISION");
        assertThat(customerStatusOf(fixture.customerId())).isEqualTo("PENDING");
        assertThat(auditCountOf("kyc.DecisionRecorded", fixture.caseId().value().toString()))
                .isZero();
    }

    @Test
    @DisplayName("a backend killed mid-recording leaves nothing at all")
    void aKilledBackendLeavesNothing() throws Exception {
        // The crash shape rather than the exception shape: the connection's own backend is
        // terminated from inside the flow (the P1-TSK-012 deterministic-kill idiom), so the
        // transaction is gone with the process that held it.
        Fixture fixture = givenACaseAwaitingDecision();
        DecisionRecording killedAtTheProjection =
                recordingOver(new FailingMove(new JdbcPartyStore(), FailingMove.Mode.KILL));

        Throwable failure = decideExpectingFailure(killedAtTheProjection, fixture);

        assertThat(failure).as("the killed backend must surface as a failure").isNotNull();
        assertThat(decisionCountOf(fixture.caseId())).isZero();
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("READY_FOR_DECISION");
        assertThat(customerStatusOf(fixture.customerId())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a customer closed mid-verification refuses the decision whole, loudly")
    void aClosedCustomerRefusesTheDecisionWhole() throws Exception {
        // The one reachable cause of a lost projection conditional. Recording the decision
        // beside an unmoved projection would be the silent drift INV-KYC-05 forbids, so the
        // refusal takes the decision with it - and it is loud (a 500 is honest here: the
        // platform's own state contradicts itself, and only an operator can answer it).
        Fixture fixture = givenACaseAwaitingDecision();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE party.customer SET status = 'CLOSED', status_changed_at = now()"
                            + " WHERE id = ?",
                    fixture.customerId());
        }

        HttpResponse<String> refused =
                post(decisionOf(fixture.caseId()), givenASessionFor(givenAReviewer()), APPROVAL);

        assertThat(refused.statusCode()).isEqualTo(500);
        assertThat(decisionCountOf(fixture.caseId())).isZero();
        assertThat(caseStatusOf(fixture.caseId()))
                .as("the case stays decidable once the contradiction is resolved")
                .isEqualTo("READY_FOR_DECISION");
        assertThat(customerStatusOf(fixture.customerId())).isEqualTo("CLOSED");
    }

    // -----------------------------------------------------------------
    // The reconciliation sweep (INV-KYC-05, ADR-0035's carried obligation)

    @Test
    @DisplayName("the sweep: decision and projection agree, in both directions")
    void theDecisionAndTheProjectionCannotDrift() throws Exception {
        // Drive one of each through the real doors first, so the sweep always has subjects
        // whatever the suite's execution order.
        Fixture approved = givenACaseAwaitingDecision();
        post(decisionOf(approved.caseId()), givenASessionFor(givenAReviewer()), APPROVAL);
        Fixture rejected = givenACaseAwaitingDecision();
        post(decisionOf(rejected.caseId()), givenASessionFor(givenAReviewer()), REJECTION);

        // Direction one: every decision's customer moved as the outcome says. Exact today -
        // nothing in Phase 2 suspends or closes an activated customer - and a later phase
        // that does will meet this assertion and relax it with provenance.
        List<String> disagreeing = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT d.outcome, c.status, d.case_id"
                                        + " FROM kyc.kyc_decision d"
                                        + " JOIN kyc.kyc_case k ON k.id = d.case_id"
                                        + " JOIN party.customer c ON c.id = k.customer_id")) {
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String outcome = rows.getString(1);
                    String status = rows.getString(2);
                    boolean agrees =
                            ("APPROVED".equals(outcome) && "ACTIVE".equals(status))
                                    || ("REJECTED".equals(outcome)
                                            && "REJECTED".equals(status));
                    if (!agrees) {
                        disagreeing.add(
                                "case " + rows.getString(3) + ": decision " + outcome
                                        + " but customer " + status);
                    }
                }
            }
        }
        assertThat(disagreeing)
                .as("a decision whose customer did not move is the drift INV-KYC-05 forbids")
                .isEmpty();

        // Direction two - the sharp one: no customer under verification has left PENDING
        // without a decision authorizing it. Scoped to customers WITH a KYC case, because a
        // customer with no case has no decision-projection pair to reconcile (schema-suite
        // fixtures insert ACTIVE customers with no case to probe the one-live index, and
        // sweeping them would assert a pair that does not exist). The closed-customer edge
        // above closes its case's customer by fixture, which is a move this platform's
        // machine permits independently of verification - so CLOSED is not evidence of an
        // unauthorized projection, while ACTIVE and REJECTED are exactly that.
        List<String> unauthorized = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT c.id, c.status FROM party.customer c"
                                        + " WHERE c.status IN ('ACTIVE', 'REJECTED')"
                                        + " AND EXISTS (SELECT 1 FROM kyc.kyc_case k"
                                        + "     WHERE k.customer_id = c.id)"
                                        + " AND NOT EXISTS (SELECT 1 FROM kyc.kyc_decision d"
                                        + "     JOIN kyc.kyc_case k2 ON k2.id = d.case_id"
                                        + "     WHERE k2.customer_id = c.id)")) {
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    unauthorized.add(
                            "customer " + rows.getString(1) + " is " + rows.getString(2)
                                    + " with no decision");
                }
            }
        }
        assertThat(unauthorized)
                .as("a projection that moved without a decision has a second writer -"
                        + " the shared mutable ownership INV-KYC-05 exists to prevent")
                .isEmpty();
    }

    // -----------------------------------------------------------------
    // The grant, narrowed

    @Test
    @DisplayName("the UPDATE grant names status and its date, and nothing else")
    void theGrantIsNarrow() throws Exception {
        Fixture fixture = givenAnOpenCase();
        for (String column : List.of("party_id", "opened_at", "id")) {
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement update =
                            app.prepareStatement(
                                    "UPDATE party.customer SET " + column + " = " + column
                                            + " WHERE id = ?")) {
                update.setObject(1, fixture.customerId());
                assertThatExceptionOfType(SQLException.class)
                        .as("UPDATE (%s) must be denied: V005 narrowed the table-level grant"
                                + " V002 paid ahead of any writer", column)
                        .isThrownBy(update::executeUpdate)
                        .withMessageContaining("permission denied");
            }
        }
        // The positive control, without which the refusals above would also pass against a
        // role with no UPDATE at all - and the projection could never have worked.
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE party.customer SET status = status,"
                                        + " status_changed_at = status_changed_at"
                                        + " WHERE id = ?")) {
            update.setObject(1, fixture.customerId());
            assertThatCode(update::executeUpdate).doesNotThrowAnyException();
        }
    }

    // -----------------------------------------------------------------

    private record Fixture(KycCaseId caseId, UUID customerId, UUID partyId) {}

    private static final String[] ALL_PATHS = {
        com.finapp.kyc.IdentityVerificationAdapter.PATH,
        com.finapp.kyc.DocumentVerificationAdapter.PATH,
        com.finapp.kyc.ScreeningAdapter.SANCTIONS_PATH,
        com.finapp.kyc.ScreeningAdapter.PEP_PATH,
        com.finapp.kyc.ScreeningAdapter.ADVERSE_MEDIA_PATH
    };

    private static void stubEveryPathClear() {
        for (String path : ALL_PATHS) {
            provider.succeedsWith(path, 200, "{\"status\":\"clear\"}");
        }
    }

    private void run(Fixture fixture) {
        try (CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            runs.runChecks(fixture.caseId(), fixture.customerId());
        }
    }

    /** The real recording routine over a decorated party store — the atomicity probe. */
    private DecisionRecording recordingOver(PartyStore<Connection> parties) {
        return new DecisionRecording(
                cases,
                checkStore,
                decisionStore,
                parties,
                auditWriter,
                IDS,
                CLOCK,
                kycTransactions,
                dataSource);
    }

    private Throwable decideExpectingFailure(DecisionRecording recording, Fixture fixture) {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(
                                        CorrelationId.of(UUID.randomUUID().toString())));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return catchThrowable(
                    () ->
                            recording.byReviewer(
                                    fixture.caseId(),
                                    givenAnIdentity(),
                                    DecisionOutcome.APPROVED,
                                    "approved just before the failure"));
        }
    }

    /** Throws or kills its own backend at the projection move; everything else is real. */
    private static final class FailingMove implements PartyStore<Connection> {
        enum Mode {
            THROW,
            KILL
        }

        private final PartyStore<Connection> real;
        private final Mode mode;

        private FailingMove(PartyStore<Connection> real, Mode mode) {
            this.real = real;
            this.mode = mode;
        }

        @Override
        public boolean moveCustomerStatus(
                Connection unitOfWork,
                CustomerId customerId,
                CustomerStatus from,
                CustomerStatus to,
                Instant at) {
            if (mode == Mode.KILL) {
                try (Statement kill = unitOfWork.createStatement()) {
                    kill.execute("SELECT pg_terminate_backend(pg_backend_pid())");
                } catch (SQLException expected) {
                    throw new IllegalStateException("backend terminated mid-recording", expected);
                }
                throw new IllegalStateException("backend terminated mid-recording");
            }
            throw new IllegalStateException("injected failure at the projection - the last write");
        }

        @Override
        public java.util.Optional<com.finapp.party.Party> findById(
                Connection unitOfWork, com.finapp.party.PartyId id) {
            return real.findById(unitOfWork, id);
        }

        @Override
        public java.util.Optional<com.finapp.party.Customer> findLiveCustomerFor(
                Connection unitOfWork, com.finapp.party.PartyId partyId) {
            return real.findLiveCustomerFor(unitOfWork, partyId);
        }

        @Override
        public java.util.Optional<com.finapp.party.PartyName> rename(
                Connection unitOfWork,
                com.finapp.party.PartyId id,
                com.finapp.party.PartyName newName) {
            return real.rename(unitOfWork, id, newName);
        }
    }

    /** A case whose one HIT was reviewed and resolved: durably {@code READY_FOR_DECISION}. */
    private Fixture givenACaseAwaitingDecision() throws SQLException {
        Fixture fixture = givenAnOpenCase();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            VerificationCheck check =
                    checkStore
                            .requestOrConverge(
                                    app,
                                    VerificationCheck.request(
                                            IDS, CLOCK, fixture.caseId(), CheckType.SANCTIONS))
                            .check();
            checkStore.dispatch(app, check.id(), Instant.now(CLOCK));
            checkStore.complete(app, check.id(), CheckOutcome.HIT, Instant.now(CLOCK));
            ReviewTask task = ReviewTask.open(IDS, CLOCK, fixture.caseId(), check.id());
            reviewTaskStore.openForCheck(app, task);
            reviewTaskStore.resolve(
                    app,
                    task.id(),
                    fixture.caseId(),
                    IDS.next(),
                    "resolved for the projection fixture",
                    Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    fixture.caseId(),
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    fixture.caseId(),
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    KycCaseStatus.IN_REVIEW,
                    Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    fixture.caseId(),
                    KycCaseStatus.IN_REVIEW,
                    KycCaseStatus.READY_FOR_DECISION,
                    Instant.now(CLOCK));
            app.commit();
        }
        return fixture;
    }

    private Fixture givenAnOpenCase() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at)"
                            + " VALUES (?, ?, 'PENDING', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    customer,
                    party);
            app.setAutoCommit(false);
            KycCase opened = cases.openOrConverge(app, KycCase.open(IDS, CLOCK, customer)).kycCase();
            app.commit();
            return new Fixture(opened.id(), customer, party);
        }
    }

    private IdentityId givenAReviewer() throws SQLException {
        IdentityId reviewer = givenAnIdentity();
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, reviewer, RoleName.KYC_REVIEWER, reviewer, "fixture");
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
        return reviewer;
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    // Back-dated: the P1-TSK-031 remedy for the container clock's corrections.
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    identity,
                    party,
                    "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    // -----------------------------------------------------------------

    private static String decisionOf(KycCaseId caseId) {
        return "/kyc/cases/" + caseId.value() + "/decision";
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String customerStatusOf(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT status FROM party.customer WHERE id = ?")) {
            select.setObject(1, customerId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static Instant statusChangedAtOf(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT status_changed_at FROM party.customer WHERE id = ?")) {
            select.setObject(1, customerId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private static String caseStatusOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement("SELECT status FROM kyc.kyc_case WHERE id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static long decisionCountOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.kyc_decision WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long auditCountOf(String operation, String targetId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ?")) {
            select.setString(1, operation);
            select.setString(2, targetId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
