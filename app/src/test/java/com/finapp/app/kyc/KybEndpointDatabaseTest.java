package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The KYB endpoints (`P2-TSK-016`): the organisation's acting person carries the graph over
 * HTTP, and a stranger cannot.
 *
 * <p>The gate, the lock and the re-route themselves are `P2-TSK-015`'s, proven in
 * {@code KybCaseDatabaseTest}; this suite proves the <em>surface</em> — that the derived chain
 * lands every request on the caller's own organisation and nobody else's, that the refusals
 * disclose what they may and nothing more, and that the shaped view keeps tipping-off closed.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the KYB endpoints (P2-TSK-016)")
class KybEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Pattern CUSTOMER_ID =
            Pattern.compile("\"customerId\"\\s*:\\s*\"([0-9a-f-]+)\"");

    @LocalServerPort private int port;
    @Autowired private DecisionRecording decisionRecording;
    @Autowired private CheckStore<Connection> checks;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final KycCaseStore<Connection> cases = new JdbcKycCaseStore();

    // -----------------------------------------------------------------
    // The acceptance, end to end over the new surface

    @Test
    @DisplayName("the acting person registers, declares and watches the graph gate the decision")
    @SuppressWarnings("try") // The scopes are used for their close side effect.
    void theActingPersonCarriesTheGraphOverHttp() throws Exception {
        Person acting = givenAPerson();

        // Register the organisation: 201, and the KYB case exists in the same commit.
        HttpResponse<String> registered =
                post("/me/organisations", acting.session(), "{\"name\":\"Acme Holdings\"}");
        assertThat(registered.statusCode()).isEqualTo(201);
        UUID organisationCustomer = customerIdIn(registered.body());
        KycCaseId orgCase = latestCaseOf(organisationCustomer);
        assertThat(kindOf(orgCase)).isEqualTo("KYB");

        // Declare a registered person as owner: 201, audited against the ACTING PERSON -
        // the interceptor-proven actor OwnerDeclaration's javadoc promised this task brings.
        Person owner = givenAPersonWithAKycCase();
        assertThat(
                        post(
                                        "/me/kyb/owners",
                                        acting.session(),
                                        "{\"ownerPartyId\":\"" + owner.party()
                                                + "\",\"stakeBasisPoints\":6000}")
                                .statusCode())
                .isEqualTo(201);
        assertThat(ownerDeclaredAuditActor(orgCase))
                .as("the declaration names the proven person, never the platform")
                .isEqualTo(acting.identity().toString());

        // The view: case OPEN, the owner pending.
        String view = get("/me/kyb", acting.session()).body();
        assertThat(view).contains("\"status\":\"OPEN\"");
        assertThat(view).contains("\"verificationPending\":true");

        // The gate holds while the owner is unanswered (P2-TSK-015's predicate, reached
        // through this surface's case). The clear check is the evidence the decision will
        // reference (INV-KYC-02 - a decision must rest on checks).
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            VerificationCheck check =
                    checks.requestOrConverge(
                                    app,
                                    VerificationCheck.request(
                                            IDS, CLOCK, orgCase, CheckType.SANCTIONS))
                            .check();
            checks.dispatch(app, check.id(), Instant.now(CLOCK));
            checks.complete(app, check.id(), CheckOutcome.CLEAR, Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    orgCase,
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            assertThat(
                            cases.moveToReadyForDecision(
                                    app, orgCase, KycCaseStatus.CHECKS_IN_PROGRESS,
                                    Instant.now(CLOCK)))
                    .as("an unanswered owner blocks readiness")
                    .isFalse();
            app.commit();
        }

        // The owner's verification answers; the graph is complete.
        walkToApproved(owner.kycCase());
        assertThat(get("/me/kyb", acting.session()).body())
                .contains("\"verificationPending\":false");
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertThat(
                            cases.moveToReadyForDecision(
                                    app, orgCase, KycCaseStatus.CHECKS_IN_PROGRESS,
                                    Instant.now(CLOCK)))
                    .isTrue();
            app.commit();
        }
        assertThat(get("/me/kyb", acting.session()).body())
                .contains("\"status\":\"PENDING_DECISION\"");

        // The reviewer decides over the fully verified graph; the acting person sees APPROVED.
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            assertThat(
                            decisionRecording.byReviewer(
                                    orgCase,
                                    IdentityId.of(IDS.next()),
                                    com.finapp.kyc.DecisionOutcome.APPROVED,
                                    "graph complete: sole 60% owner verified"))
                    .isEqualTo(DecisionRecording.Recording.RECORDED);
        }
        assertThat(get("/me/kyb", acting.session()).body())
                .contains("\"status\":\"APPROVED\"");
    }

    // -----------------------------------------------------------------
    // Ownership: the stranger, and the chains that cannot cross

    @Test
    @DisplayName("a stranger has nothing to declare onto - their own chain resolves to a 404")
    void aStrangerHasNothingToDeclareOnto() throws Exception {
        Person stranger = givenAPerson();
        assertThat(get("/me/kyb", stranger.session()).statusCode()).isEqualTo(404);
        assertThat(
                        post(
                                        "/me/kyb/owners",
                                        stranger.session(),
                                        "{\"ownerPartyId\":\"" + IDS.next()
                                                + "\",\"stakeBasisPoints\":100}")
                                .statusCode())
                .as("no case identifier exists to name a victim's case with")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("two acting persons' declarations land on their own organisations only")
    void declarationsCannotCrossOrganisations() throws Exception {
        Person actingOne = givenAPersonWithAnOrganisation("First Org");
        Person actingTwo = givenAPersonWithAnOrganisation("Second Org");
        Person owner = givenAPersonWithAKycCase();

        assertThat(
                        post(
                                        "/me/kyb/owners",
                                        actingTwo.session(),
                                        "{\"ownerPartyId\":\"" + owner.party()
                                                + "\",\"stakeBasisPoints\":100}")
                                .statusCode())
                .isEqualTo(201);

        // The resolution-chain proof (the P1-TSK-030 shape): the declaration reached exactly
        // the declarant's own graph.
        assertThat(get("/me/kyb", actingTwo.session()).body())
                .contains(owner.party().toString());
        assertThat(get("/me/kyb", actingOne.session()).body())
                .doesNotContain(owner.party().toString());
    }

    @Test
    @DisplayName("the endpoints refuse the unauthenticated, uniformly")
    void unauthenticatedIsRefused() throws Exception {
        assertThat(post("/me/organisations", null, "{\"name\":\"Acme\"}").statusCode())
                .isEqualTo(401);
        assertThat(post("/me/kyb/owners", null, "{\"ownerPartyId\":\"x\"}").statusCode())
                .isEqualTo(401);
        assertThat(get("/me/kyb", null).statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------
    // The refusals

    @Test
    @DisplayName("owner ineligibility is ONE refusal - unknown, organisation, caseless, malformed")
    void ownerIneligibilityIsOneRefusal() throws Exception {
        Person acting = givenAPersonWithAnOrganisation("Uniform Org");
        Person caseless = givenAPerson();
        UUID organisationParty = organisationPartyOf(acting);

        List<HttpResponse<String>> refusals = new ArrayList<>();
        refusals.add(declare(acting, IDS.next().toString())); // no such party
        refusals.add(declare(acting, organisationParty.toString())); // the depth-1 bound
        refusals.add(declare(acting, caseless.party().toString())); // person, but caseless...
        refusals.add(declare(acting, "not-a-uuid")); // ...and a value that names nobody

        // Wait - a registered person WITHOUT a case: the fixture person has a customer but no
        // case, which is OWNER_NOT_VERIFIABLE. All four must be byte-identical after the
        // per-request identifiers are stripped (the everyFailureLooksTheSame idiom).
        List<String> bodies =
                refusals.stream().map(KybEndpointDatabaseTest::withoutCorrelation).toList();
        for (HttpResponse<String> refusal : refusals) {
            assertThat(refusal.statusCode()).isEqualTo(422);
        }
        assertThat(bodies.stream().distinct())
                .as("one refusal for every cause: no oracle over third parties")
                .hasSize(1);
        assertThat(bodies.get(0)).contains("kyc.OwnerNotEligible");
    }

    @Test
    @DisplayName("the caller's own graph refusals are specific: repeat, over-stake, frozen set")
    void ownGraphRefusalsAreSpecific() throws Exception {
        Person acting = givenAPersonWithAnOrganisation("Specific Org");
        Person owner = givenAPersonWithAKycCase();

        assertThat(declare(acting, owner.party().toString(), 5_000).statusCode()).isEqualTo(201);

        HttpResponse<String> repeated = declare(acting, owner.party().toString(), 5_000);
        assertThat(repeated.statusCode()).isEqualTo(409);
        assertThat(repeated.body()).contains("kyc.OwnerAlreadyDeclared");

        Person second = givenAPersonWithAKycCase();
        HttpResponse<String> overStake = declare(acting, second.party().toString(), 6_000);
        assertThat(overStake.statusCode()).isEqualTo(422);
        assertThat(overStake.body()).contains("kyc.StakeExceedsWhole");

        // Freeze the set: walk the case to READY_FOR_DECISION - the sole declared owner must
        // be answered first, or the mover itself refuses.
        walkToApproved(owner.kycCase());
        KycCaseId orgCase = latestCaseOf(customerOf(acting));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            cases.moveStatus(
                    app,
                    orgCase,
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            assertThat(
                            cases.moveToReadyForDecision(
                                    app, orgCase, KycCaseStatus.CHECKS_IN_PROGRESS,
                                    Instant.now(CLOCK)))
                    .isTrue();
            app.commit();
        }
        HttpResponse<String> late = declare(acting, second.party().toString(), 1_000);
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(late.body()).contains("kyc.CaseNotAcceptingOwners");
    }

    // -----------------------------------------------------------------
    // Registration semantics

    @Test
    @DisplayName("registration converges on the same name and conflicts on a different one")
    void registrationConvergesAndConflicts() throws Exception {
        Person acting = givenAPerson();
        HttpResponse<String> first =
                post("/me/organisations", acting.session(), "{\"name\":\"Converge Ltd\"}");
        assertThat(first.statusCode()).isEqualTo(201);

        HttpResponse<String> replay =
                post("/me/organisations", acting.session(), "{\"name\":\"Converge Ltd\"}");
        assertThat(replay.statusCode())
                .as("same intent converges and replays the ORIGINAL answer - the platform's"
                        + " convergence idiom (the P2-TSK-008 documents endpoint's shape)")
                .isEqualTo(201);
        assertThat(customerIdIn(replay.body())).isEqualTo(customerIdIn(first.body()));

        HttpResponse<String> different =
                post("/me/organisations", acting.session(), "{\"name\":\"Other Ltd\"}");
        assertThat(different.statusCode())
                .as("a different request must not silently receive the first organisation"
                        + " (INV-IDEM-03's shape)")
                .isEqualTo(409);
        assertThat(different.body()).contains("api.Conflict");

        // Created announces; converged is SILENT - two registration records for one
        // organisation is the ambiguity the one-decision rule exists to prevent.
        assertThat(organisationRegisteredAuditCount(customerIdIn(first.body())))
                .as("one registration, one record, however many times the request arrived")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ten concurrent registrations produce one organisation")
    void tenInstancesRegisterOneOrganisation() throws Exception {
        Person acting = givenAPerson();
        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return post(
                                                    "/me/organisations",
                                                    acting.session(),
                                                    "{\"name\":\"Race Ltd\"}")
                                            .statusCode();
                                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            // Every racer receives the one organisation's 201 - the one-per-registrant
            // index is the arbiter and the savepoint is what lets a loser converge instead
            // of erroring. Who actually created it is answered by the records below: one
            // registrant row, and (asserted in registrationConvergesAndConflicts) one audit
            // record however many times the request arrived.
            assertThat(statuses).hasSize(racers).allMatch(status -> status == 201);
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM party.organisation_registrant"
                                        + " WHERE registrant_party_id = ?")) {
            count.setObject(1, acting.party());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                assertThat(row.getInt(1)).isEqualTo(1);
            }
        }
    }

    // -----------------------------------------------------------------
    // The shaped view

    @Test
    @DisplayName("a case in review renders IN_PROGRESS, and no review vocabulary escapes")
    void theViewShapesAwayReview() throws Exception {
        Person acting = givenAPersonWithAnOrganisation("Shaped Org");
        KycCaseId orgCase = latestCaseOf(customerOf(acting));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            cases.moveStatus(
                    app,
                    orgCase,
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    orgCase,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    KycCaseStatus.IN_REVIEW,
                    Instant.now(CLOCK));
            app.commit();
        }
        String body = get("/me/kyb", acting.session()).body();
        assertThat(body).contains("\"status\":\"IN_PROGRESS\"");
        // The tipping-off control (plan §6, INV-IDN-07's reasoning): a screening hit must be
        // indistinguishable from ordinary processing, so the vocabulary must not exist here.
        assertThat(body).doesNotContain("REVIEW").doesNotContain("HIT");
    }

    @Test
    @DisplayName("no body shape produces a 500 on either POST")
    void noBodyShapeProducesA500() throws Exception {
        Person acting = givenAPersonWithAnOrganisation("Sturdy Org");
        String[] organisationBodies = {
            "not json", "{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":12}",
            "{\"name\":\"a\\u0000b\"}"
        };
        for (String body : organisationBodies) {
            assertThat(post("/me/organisations", acting.session(), body).statusCode())
                    .as("organisation body: %s", body)
                    .isLessThan(500);
        }
        // The no-qualification shape names an ELIGIBLE owner deliberately: an unknown party
        // is refused by eligibility before the aggregate is ever constructed, so only an
        // eligible one can reach the IllegalArgumentException the boundary check exists to
        // pre-empt - the surviving-mutation finding of this task's own sweep.
        Person eligible = givenAPersonWithAKycCase();
        String[] ownerBodies = {
            "not json",
            "{}",
            "{\"ownerPartyId\":null}",
            "{\"ownerPartyId\":\"" + eligible.party() + "\"}", // neither stake nor role
            "{\"ownerPartyId\":\"" + IDS.next() + "\",\"stakeBasisPoints\":0}",
            "{\"ownerPartyId\":\"" + IDS.next() + "\",\"stakeBasisPoints\":10001}",
            "{\"ownerPartyId\":\"" + IDS.next() + "\",\"controlRole\":\"EMPEROR\"}",
            "{\"ownerPartyId\":\"" + IDS.next() + "\",\"stakeBasisPoints\":\"lots\"}"
        };
        for (String body : ownerBodies) {
            assertThat(post("/me/kyb/owners", acting.session(), body).statusCode())
                    .as("owner body: %s", body)
                    .isLessThan(500);
        }
    }

    // -----------------------------------------------------------------
    // HTTP

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

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> declare(Person acting, String ownerPartyId) throws Exception {
        return post(
                "/me/kyb/owners",
                acting.session(),
                "{\"ownerPartyId\":\"" + ownerPartyId + "\",\"stakeBasisPoints\":100}");
    }

    private HttpResponse<String> declare(Person acting, String ownerPartyId, int stake)
            throws Exception {
        return post(
                "/me/kyb/owners",
                acting.session(),
                "{\"ownerPartyId\":\"" + ownerPartyId + "\",\"stakeBasisPoints\":" + stake + "}");
    }

    private static String withoutCorrelation(HttpResponse<String> response) {
        return response.body().replaceAll("\"correlationId\"\\s*:\\s*\"[^\"]*\"", "");
    }

    private static UUID customerIdIn(String body) {
        Matcher matcher = CUSTOMER_ID.matcher(body);
        assertThat(matcher.find()).as("the response names the customer: %s", body).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    // -----------------------------------------------------------------
    // Fixtures

    private record Person(UUID party, UUID customer, UUID identity, KycCaseId kycCase,
            String session) {}

    private Person givenAPerson() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        UUID customer = IDS.next();
        String login = "kyb" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Grace Hopper', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    identity,
                    party,
                    login);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'PENDING',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        return new Person(
                party, customer, identity, null, givenASessionFor(IdentityId.of(identity)));
    }

    private Person givenAPersonWithAKycCase() throws SQLException {
        Person person = givenAPerson();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            KycCase opened =
                    cases.openOrConverge(
                                    app,
                                    KycCase.open(IDS, CLOCK, person.customer(), KycCaseKind.KYC))
                            .kycCase();
            app.commit();
            return new Person(
                    person.party(),
                    person.customer(),
                    person.identity(),
                    opened.id(),
                    person.session());
        }
    }

    private Person givenAPersonWithAnOrganisation(String name) throws Exception {
        Person person = givenAPerson();
        assertThat(
                        post(
                                        "/me/organisations",
                                        person.session(),
                                        "{\"name\":\"" + name + "\"}")
                                .statusCode())
                .isEqualTo(201);
        return person;
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    /** The organisation customer the acting person registered, read by the ownership chain. */
    private static UUID customerOf(Person acting) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT customer_id FROM party.organisation_registrant"
                                        + " WHERE registrant_party_id = ?")) {
            select.setObject(1, acting.party());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static UUID organisationPartyOf(Person acting) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT c.party_id FROM party.organisation_registrant r"
                                        + " JOIN party.customer c ON c.id = r.customer_id"
                                        + " WHERE r.registrant_party_id = ?")) {
            select.setObject(1, acting.party());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private KycCaseId latestCaseOf(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return cases.findLatestFor(app, customerId).orElseThrow().id();
        }
    }

    private static String kindOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT case_kind FROM kyc.kyc_case WHERE id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long organisationRegisteredAuditCount(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'party.OrganisationRegistered'"
                                        + " AND target_id = ?")) {
            select.setString(1, customerId.toString());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String ownerDeclaredAuditActor(KycCaseId orgCase) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT actor_id FROM platform.audit_record"
                                        + " WHERE operation = 'kyc.OwnerDeclared'"
                                        + " AND target_id = ?")) {
            select.setString(1, orgCase.value().toString());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("the declaration is audited").isTrue();
                return row.getString(1);
            }
        }
    }

    /** OPEN → CHECKS_IN_PROGRESS → READY_FOR_DECISION → APPROVED, the store walk. */
    private void walkToApproved(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            cases.moveStatus(
                    app,
                    caseId,
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            cases.moveToReadyForDecision(
                    app, caseId, KycCaseStatus.CHECKS_IN_PROGRESS, Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    caseId,
                    KycCaseStatus.READY_FOR_DECISION,
                    KycCaseStatus.APPROVED,
                    Instant.now(CLOCK));
            app.commit();
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
