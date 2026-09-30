package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExternalItems;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.ItemKeyKind;
import com.finapp.reconciliation.JdbcExpectationRegister;
import com.finapp.reconciliation.JdbcExternalItems;
import com.finapp.reconciliation.JdbcReconciliationRuns;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.ReconciliationRuns;
import com.finapp.reconciliation.RunKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The matcher over the app's own composition (`P8-TSK-011`, ADR-0064, ADR-0068): the run
 * leg's BEAN — wired to the ledger's chart, `P8-TSK-010`'s suspense and breaks, and the
 * composed {@code InternalReferenceLookup} over payments' and merchant's public reads —
 * decides a real run, and the explanation doors serve it over real HTTP with their
 * negatives three ways and the named {@code 404}s.
 *
 * <p>The engine's full verdict matrix, the races and the containment are the
 * reconciliation module's {@code MatchingDatabaseTest}; this suite proves the COMPOSITION
 * and the doors. It owns a private source so its commits never race another suite's.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the matcher composed, and its explanation doors (P8-TSK-011)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class ReconciliationMatchingDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID SOURCE =
            UUID.fromString("01a0e2bc-8200-7012-8000-000000000012");
    private static final UUID RULE_SET =
            UUID.fromString("01a0e2bd-8300-7012-8000-000000000012");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 90_000);

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private Matching matching;
    @Autowired private PositionProof positionProof;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final JdbcReconciliationRuns runs = new JdbcReconciliationRuns();
    private final JdbcExternalItems items = new JdbcExternalItems();
    private final JdbcExpectationRegister expectations = new JdbcExpectationRegister(IDS);

    // ----------------------------------------------------------------- the composition

    @Test
    @DisplayName("the composed leg decides a real run - an allocation through the live"
            + " register, a waiting item typed by the composed lookup - and the doors"
            + " explain it from stored rows alone, over HTTP")
    void theComposedLegDecidesAndTheDoorsExplain() throws Exception {
        seedPrivateRuleSet();
        String key = "CAP-APP-" + UUID.randomUUID().toString().substring(0, 8);
        UUID expectation = openExpectation(key, 77_00);
        // The waiting item's balancing expectation, under a key line 3 PARTIALLY
        // allocates: the container's position identity stays exact (open remainders
        // equal open items), and the balancer is HELD by its own allocation row - the
        // acceptance suite's platform-root register wipe deletes only UNHELD rows, so
        // this suite's residue survives it consistent for every later global proof.
        String balancing = "CAP-APP-BAL-" + UUID.randomUUID().toString().substring(0, 8);
        openExpectation(balancing, 13_00);
        UUID runId =
                seedRun(
                        item(1, 77_00, key),
                        // A capture reference no payments row carries: the COMPOSED
                        // lookup answers UNKNOWN over the real stores, so it waits.
                        item(2, 12_00, "CAP-APP-NOBODY-" + UUID.randomUUID()),
                        item(3, 1_00, balancing));

        matching.sweep();

        try (Connection app = DatabaseRoles.application()) {
            assertThat(string(app, "SELECT status FROM"
                    + " reconciliation.reconciliation_batch WHERE id = ?", runId))
                    .isEqualTo("COMPLETED");
            assertThat(string(app, "SELECT status FROM reconciliation.expectation WHERE"
                    + " id = ?", expectation)).isEqualTo("SETTLED");
            assertThat(count(app, "SELECT count(*) FROM reconciliation.external_item"
                    + " WHERE run_id = ? AND status = 'UNMATCHED' AND grace_until IS NOT"
                    + " NULL", runId)).isEqualTo(1);
        }

        // The position identity holds over this suite's residue: the settled pair nets
        // to zero and the waiting item is balanced by its own expectation (INV-REC-06).
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            PositionProof.Report report = positionProof.sweep(app);
            PositionProof.PositionVerdict clearing =
                    report.verdicts().stream()
                            .filter(verdict ->
                                    verdict.purpose() == AccountPurpose.SETTLEMENT_CLEARING
                                            && verdict.currency().equals(
                                                    com.finapp.sharedkernel.money
                                                            .CurrencyCode.of("EUR")))
                            .findFirst()
                            .orElseThrow();
            assertThat(clearing.explained())
                    .as("SETTLEMENT_CLEARING EUR: %s = %s - %s",
                            clearing.ledgerBalance(), clearing.openRemainders(),
                            clearing.openItems())
                    .isTrue();
            app.rollback();
        }

        // The doors, as the investigator, from stored rows alone.
        Session operator = operatorSession();
        HttpResponse<String> run = door(operator.token(), "/runs/" + runId);
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(run.body())
                .contains("\"COMPLETED\"")
                .contains("\"MATCHED\":2")
                .contains("\"UNMATCHED\":1");
        assertThat(door(operator.token(), "/runs").body()).contains(runId.toString());

        UUID decisionId;
        UUID allocationId;
        try (Connection app = DatabaseRoles.application()) {
            decisionId = (UUID) one(app, "SELECT d.id FROM"
                    + " reconciliation.match_decision d JOIN reconciliation.external_item"
                    + " i ON i.id = d.external_item_id WHERE d.run_id = ? AND"
                    + " i.line_no = 1", runId);
            allocationId = (UUID) one(app, "SELECT id FROM reconciliation.allocation"
                    + " WHERE decision_id = ?", decisionId);
        }
        HttpResponse<String> decision =
                door(operator.token(), "/decisions/" + decisionId);
        assertThat(decision.statusCode()).isEqualTo(200);
        assertThat(decision.body())
                .contains("\"outcome\":\"MATCHED\"")
                .contains("\"matchedKeyKind\":\"PSP_CAPTURE_REF\"")
                .contains("\"remainderBefore\":\"77.00\"")
                .contains("\"amount\":\"77.00\"")
                .contains(expectation.toString());
        HttpResponse<String> allocation =
                door(operator.token(), "/allocations/" + allocationId);
        assertThat(allocation.statusCode()).isEqualTo(200);
        assertThat(allocation.body())
                .contains("\"amount\":\"77.00\"")
                .contains(decisionId.toString());
    }

    // ----------------------------------------------------------------- the negatives

    @Test
    @DisplayName("the doors hold their permission - anonymous 401, role-less 403, the"
            + " controller's disjoint desk 403 - and unknown or malformed ids are one"
            + " named 404 each, recording nothing")
    void theDoorsHoldTheirPermissionsAndName404s() throws Exception {
        Session operator = operatorSession();
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session roleless = rolelessSession();

        for (String path :
                List.of("/runs", "/runs/" + UUID.randomUUID(),
                        "/decisions/" + UUID.randomUUID(),
                        "/allocations/" + UUID.randomUUID())) {
            assertThat(door(null, path).statusCode()).as("%s anonymous", path)
                    .isEqualTo(401);
            assertThat(door(roleless.token(), path).statusCode())
                    .as("%s role-less", path)
                    .isEqualTo(403);
            assertThat(door(controller.token(), path).statusCode())
                    .as("%s: the controller adopts; the investigator reads", path)
                    .isEqualTo(403);
        }

        record Absent(String path, String code) {}
        List<Absent> absents =
                List.of(
                        new Absent("/runs/" + UUID.randomUUID(),
                                "reconciliation.RunNotFound"),
                        new Absent("/runs/not-a-uuid", "reconciliation.RunNotFound"),
                        new Absent("/decisions/" + UUID.randomUUID(),
                                "reconciliation.DecisionNotFound"),
                        new Absent("/decisions/not-a-uuid",
                                "reconciliation.DecisionNotFound"),
                        new Absent("/allocations/" + UUID.randomUUID(),
                                "reconciliation.AllocationNotFound"),
                        new Absent("/allocations/not-a-uuid",
                                "reconciliation.AllocationNotFound"));
        for (Absent absent : absents) {
            HttpResponse<String> answer = door(operator.token(), absent.path());
            assertThat(answer.statusCode()).as(absent.path()).isEqualTo(404);
            assertThat(answer.body())
                    .as("unknown and malformed are ONE named answer (the"
                            + " settlement.FileNotFound departure)")
                    .contains(absent.code());
        }
    }

    // ----------------------------------------------------------------- seeding

    private static void seedPrivateRuleSet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at,"
                            + " correlation_id) VALUES (?, ?, 1, 'ACTIVE', 2, 90, ?,"
                            + " 'test', 'test', 'ReconciliationMatchingDatabaseTest"
                            + " private rule set', now(), 'p8-tsk-011-app-test')"
                            + " ON CONFLICT (id) DO NOTHING",
                    RULE_SET, SOURCE, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality,"
                            + " operation_anchored, grace_hours) VALUES (?, 1, 'CAPTURE',"
                            + " 'PSP_CAPTURE_REF', 'CARD_CAPTURE', 'ONE_TO_ONE', false,"
                            + " 48) ON CONFLICT DO NOTHING",
                    RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                            + " currency, absolute_minor, days) VALUES"
                            + " (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2)"
                            + " ON CONFLICT DO NOTHING",
                    RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id,"
                            + " currency, high_value_minor) VALUES (?, 'EUR', 100000)"
                            + " ON CONFLICT DO NOTHING",
                    RULE_SET);
            app.commit();
        }
    }

    private record Line(int lineNo, long minor, String keyValue) {}

    private static Line item(int lineNo, long minor, String keyValue) {
        return new Line(lineNo, minor, keyValue);
    }

    private UUID seedRun(Line... lines) throws SQLException {
        UUID runId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            runs.birth(
                    app,
                    new ReconciliationRuns.NewRun(
                            runId,
                            SOURCE,
                            Optional.of(IDS.next()),
                            RunKind.BATCH,
                            RULE_SET,
                            SETTLED_ON,
                            Optional.of(SEQUENCES.incrementAndGet()),
                            lines.length,
                            Optional.empty(),
                            Optional.empty(),
                            PLATFORM,
                            Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (Line line : lines) {
                byte[] fingerprint = new byte[32];
                RANDOMNESS.nextBytes(fingerprint);
                newItems.add(
                        new ExternalItems.NewItem(
                                IDS.next(),
                                runId,
                                SOURCE,
                                IDS.next(),
                                line.lineNo(),
                                ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND,
                                Money.ofPersisted(line.minor(), EUR, 2),
                                AccountPurpose.SETTLEMENT_CLEARING,
                                SETTLED_ON,
                                Optional.of(SETTLED_ON),
                                Optional.of(SETTLED_ON),
                                fingerprint,
                                Map.of(ItemKeyKind.PSP_CAPTURE_REF, line.keyValue()),
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
            }
            items.birthAll(app, PLATFORM, newItems);
            app.commit();
        }
        return runId;
    }

    private UUID openExpectation(String keyValue, long minor) throws SQLException {
        String operationRef = "op-app-" + keyValue;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID position =
                    new JdbcLedgerAccountStore()
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                            .orElseThrow()
                            .id()
                            .value();
            expectations.open(
                    app,
                    new NewExpectation(
                            ExpectationKind.CARD_CAPTURE,
                            operationRef,
                            "payment-capture:" + operationRef,
                            SOURCE,
                            AccountPurpose.SETTLEMENT_CLEARING,
                            position,
                            ExpectationDirection.INBOUND,
                            Money.ofPersisted(minor, EUR, 2),
                            Optional.of(IDS.next()),
                            SETTLED_ON,
                            Optional.empty(),
                            SETTLED_ON.plusDays(3),
                            RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(
                                    KeyKind.PSP_CAPTURE_REF, keyValue)),
                            PLATFORM,
                            Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            app.commit();
            return (UUID) one(app, "SELECT id FROM reconciliation.expectation WHERE"
                    + " operation_ref = ?", operationRef);
        }
    }

    // ----------------------------------------------------------------- the doors

    private HttpResponse<String> door(String token, String path) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port
                                        + "/v1/operator/reconciliation" + path))
                        .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Session(IdentityId identity, String token) {}

    private Session operatorSession() throws SQLException {
        return sessionWith(RoleName.RECONCILIATION_OPERATOR);
    }

    private Session rolelessSession() throws SQLException {
        IdentityId identity = givenAnIdentity();
        return new Session(identity, givenASessionFor(identity));
    }

    private Session sessionWith(RoleName role) throws SQLException {
        IdentityId identity = givenAnIdentity();
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        }
        return new Session(identity, givenASessionFor(identity));
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8t11-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t11-cause"));
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Matching Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier,"
                            + " status, created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    "mt" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session.Draft session =
                com.finapp.identity.Session.issue(
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

    // ----------------------------------------------------------------- plumbing

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Object one(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static long count(Connection connection, String sql, Object... args)
            throws SQLException {
        return ((Number) one(connection, sql, args)).longValue();
    }

    private static String string(Connection connection, String sql, Object... args)
            throws SQLException {
        return (String) one(connection, sql, args);
    }
}
