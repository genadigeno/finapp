package com.finapp.app.crossborder;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.crossborder.BeneficiaryId;
import com.finapp.crossborder.BeneficiaryStore;
import com.finapp.crossborder.CorridorAvailabilityStore;
import com.finapp.crossborder.CorridorDirectory;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.CorridorSelection;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.kyc.CounterpartyScreeningId;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Decision;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.kyc.CounterpartyScreenings;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The cross-border beneficiary over HTTP against a live database and the simulated corridor provider
 * (`P9-TSK-017`, ADR-0080 sections 3 and 5a, ADR-0081; {@code INV-XB-02}, {@code INV-RAIL-02},
 * {@code INV-RAIL-03}, {@code INV-KYC-05}): registration by grant with the selection pinned and recomputed,
 * the payee check acknowledged and handed to kyc, a hit or an unverified payee never {@code ACTIVE} without
 * a person, revocation from every non-terminal state with one identical answer and a later review leaving
 * it {@code REVOKED}, the tipping-off shapes, the races counted, the name and grant nowhere but kyc's
 * ciphertext, step-up, and every text column refusing a bank identifier.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("cross-border beneficiaries (P9-TSK-017)")
class CrossBorderBeneficiaryDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String DOOR = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final SimulatedCorridorEngine ENGINE = start();

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private CounterpartyScreenings counterpartyScreenings;
    @Autowired private BeneficiaryStore crossBorderBeneficiaryStore;
    @Autowired private CorridorPolicyStore corridorPolicyStore;
    @Autowired private CorridorAvailabilityStore corridorAvailabilityStore;
    @Autowired private CorridorDirectory corridorDirectory;
    @Autowired private TransactionRunner crossborderTransactionRunner;
    @Autowired private com.finapp.kyc.TransactionRunner kycTransactionRunner;

    private static SimulatedCorridorEngine start() {
        try {
            return SimulatedCorridorEngine.start("a-corridor-callback-test-key-of-32-byte".getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void corridorProvider(DynamicPropertyRegistry registry) {
        registry.add("finapp.corridor.provider.url", () -> ENGINE.baseUrl().toString());
    }

    @AfterAll
    static void stop() {
        ENGINE.close();
    }

    @BeforeEach
    void anActiveCorridorPolicy() throws Exception {
        if (!activeExists()) {
            String first = sessionWith(RoleName.FX_CONTROLLER);
            String second = sessionWith(RoleName.FX_CONTROLLER);
            withdrawPending(first);
            HttpResponse<String> proposed = post(POLICIES, CorridorPolicyV1.json("v1 for the beneficiary suite"), first, key());
            assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
            assertThat(post(POLICIES + "/" + field(proposed.body(), "id") + "/approval", "{\"reason\":\"checked\"}",
                            second, null).statusCode())
                    .isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ registration

    @Test
    @DisplayName("a MATCH payee with a clear name is ACTIVE at once; the selection is pinned and recomputes exactly")
    void aClearMatchIsActive() throws Exception {
        String customer = sessionWith(null);
        HttpResponse<String> registered = register(customer, grant("match"), "Clear Person", false, key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        assertThat(field(registered.body(), "status")).isEqualTo("ACTIVE");
        assertThat(field(registered.body(), "country")).isEqualTo("JP");
        BeneficiaryId id = BeneficiaryId.of(UUID.fromString(field(registered.body(), "id")));
        assertThat(statusOf(id)).isEqualTo("ACTIVE");

        UUID selectionId = selectionOf(id);
        crossborderTransactionRunner.inTransaction(uow -> {
            BeneficiaryStore.SelectionRow stored = crossBorderBeneficiaryStore.selection(uow, selectionId).orElseThrow();
            CorridorSelection.Selection recomputed = CorridorSelection.select(stored.inputs(),
                    corridorPolicyStore.version(uow, stored.policy()).orElseThrow().corridors(),
                    stored.availableCorridors()::contains, corridorDirectory);
            assertThat(recomputed.steps()).as("the pinned version over the stored inputs").isEqualTo(stored.steps());
            assertThat(recomputed.chosen()).contains("corridor-sim-a");
            return null;
        });
    }

    @Test
    @DisplayName("a NO_MATCH payee is refused unacknowledged (nothing registered); acknowledged with the same grant it"
            + " registers PENDING_VERIFICATION - in review, never ACTIVE without a person - and a release activates it")
    void aNoMatchPayeeMeetsAPerson() throws Exception {
        String customer = sessionWith(null);
        String grant = grant("no_match");
        HttpResponse<String> refused = register(customer, grant, "Clear Person", false, key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("crossborder.NoMatchUnacknowledged");

        HttpResponse<String> registered = register(customer, grant, "Clear Person", true, key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        assertThat(field(registered.body(), "status")).isEqualTo("PENDING_VERIFICATION");
        BeneficiaryId id = BeneficiaryId.of(UUID.fromString(field(registered.body(), "id")));
        assertThat(statusOf(id)).isEqualTo("IN_REVIEW");
        assertThat(screeningReviewReason(id)).isEqualTo("PAYEE_UNVERIFIED");

        review(id, Decision.RELEASE, ReasonCode.PAYEE_CONFIRMED);
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(field(get(DOOR + "/" + id.value(), customer).body(), "status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a hit is PENDING_VERIFICATION and a block UNAVAILABLE; an unavailable screening stays PENDING_VERIFICATION")
    void hitsBlocksAndUnavailability() throws Exception {
        String customer = sessionWith(null);
        BeneficiaryId hit = registered(customer, grant("match"), "HIT Listed Person");
        assertThat(statusOf(hit)).isEqualTo("IN_REVIEW");
        review(hit, Decision.BLOCK, ReasonCode.TRUE_MATCH);
        assertThat(statusOf(hit)).isEqualTo("BLOCKED");
        assertThat(field(get(DOOR + "/" + hit.value(), customer).body(), "status")).isEqualTo("UNAVAILABLE");

        BeneficiaryId down = registered(customer, grant("match"), "DOWN Provider Person");
        assertThat(statusOf(down)).isEqualTo("PENDING_SCREENING");
        assertThat(field(get(DOOR + "/" + down.value(), customer).body(), "status")).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    @DisplayName("the read is byte-identical for a beneficiary being screened and one in review (tipping-off)")
    void theReadRevealsNoReview() throws Exception {
        String customer = sessionWith(null);
        BeneficiaryId screening = registered(customer, grant("match"), "DOWN Pending Person");
        BeneficiaryId review = registered(customer, grant("match"), "HIT Review Person");
        assertThat(statusOf(screening)).isEqualTo("PENDING_SCREENING");
        assertThat(statusOf(review)).isEqualTo("IN_REVIEW");
        assertThat(normalised(get(DOOR + "/" + screening.value(), customer).body(), screening))
                .isEqualTo(normalised(get(DOOR + "/" + review.value(), customer).body(), review));
    }

    @Test
    @DisplayName("an unknown country is CorridorNotOffered; a used grant is GrantRefused; another customer's beneficiary 404s")
    void refusals() throws Exception {
        String customer = sessionWith(null);
        HttpResponse<String> notOffered = post(DOOR, body("FR", "EUR", grant("match"), "Clear Person", false), customer, key());
        assertThat(notOffered.statusCode()).as(notOffered.body()).isEqualTo(422);
        assertThat(notOffered.body()).contains("crossborder.CorridorNotOffered");

        String grant = grant("match");
        assertThat(register(customer, grant, "Clear Person", false, key()).statusCode()).isEqualTo(201);
        HttpResponse<String> used = register(sessionWith(null), grant, "Clear Person", false, key());
        assertThat(used.statusCode()).as(used.body()).isEqualTo(422);
        assertThat(used.body()).contains("crossborder.GrantRefused");

        BeneficiaryId mine = registered(customer, grant("match"), "Clear Person");
        String stranger = sessionWith(null);
        assertThat(get(DOOR + "/" + mine.value(), stranger).statusCode()).isEqualTo(404);
        assertThat(post(DOOR + "/" + mine.value() + "/revocation", "", stranger, key()).statusCode()).isEqualTo(404);
        assertThat(get(DOOR + "/not-a-uuid", customer).statusCode()).isEqualTo(404);
        assertThat(get(DOOR, null).statusCode()).isEqualTo(401);
        assertThat(statusOf(mine)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ revocation

    @Test
    @DisplayName("revocation from PENDING_SCREENING, IN_REVIEW, BLOCKED and ACTIVE answers one byte-identical body;"
            + " a review decided afterwards leaves the beneficiary REVOKED")
    void revocationFromEveryStateRevealsNothing() throws Exception {
        String customer = sessionWith(null);
        BeneficiaryId pending = registered(customer, grant("match"), "DOWN Pending Person");
        BeneficiaryId inReview = registered(customer, grant("match"), "HIT Review Person");
        BeneficiaryId blocked = registered(customer, grant("match"), "HIT Blocked Person");
        review(blocked, Decision.BLOCK, ReasonCode.TRUE_MATCH);
        BeneficiaryId active = registered(customer, grant("match"), "Clear Person");
        assertThat(List.of(statusOf(pending), statusOf(inReview), statusOf(blocked), statusOf(active)))
                .containsExactly("PENDING_SCREENING", "IN_REVIEW", "BLOCKED", "ACTIVE");

        List<String> bodies = new ArrayList<>();
        for (BeneficiaryId id : List.of(pending, inReview, blocked, active)) {
            HttpResponse<String> revoked = post(DOOR + "/" + id.value() + "/revocation", "", customer, key());
            assertThat(revoked.statusCode()).isEqualTo(200);
            bodies.add(revoked.body());
            assertThat(statusOf(id)).isEqualTo("REVOKED");
        }
        assertThat(bodies).as("one identical answer from every state").containsOnly(bodies.get(0));
        assertThat(post(DOOR + "/" + active.value() + "/revocation", "", customer, key()).body())
                .as("revoking a revoked beneficiary answers the same").isEqualTo(bodies.get(0));

        review(inReview, Decision.RELEASE, ReasonCode.FALSE_POSITIVE);
        assertThat(statusOf(inReview)).as("a later release leaves it REVOKED").isEqualTo("REVOKED");
        assertThat(screeningStatus(inReview)).as("kyc records its own outcome").isEqualTo("RELEASED");
    }

    // ------------------------------------------------------------------ races

    @Test
    @DisplayName("ten registrations with one key produce one beneficiary (counted)")
    void tenRegistrationsOneBeneficiary() throws Exception {
        String customer = sessionWith(null);
        String grant = grant("match");
        String key = key();
        String nickname = "Herd " + UUID.randomUUID().toString().substring(0, 8);
        int herd = 10;
        ExecutorService pool = Executors.newFixedThreadPool(herd);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> codes = new ArrayList<>();
        for (int i = 0; i < herd; i++) {
            codes.add(pool.submit(() -> {
                start.await();
                return post(DOOR, body("JP", "JPY", grant, "Clear Person", false).replace("Aunt in Osaka", nickname),
                        customer, key).statusCode();
            }));
        }
        start.countDown();
        for (Future<Integer> code : codes) {
            assertThat(code.get(60, TimeUnit.SECONDS)).isIn(201, 409);
        }
        pool.shutdown();
        assertThat(count("SELECT count(*) FROM crossborder.beneficiary_registration WHERE exchange_reference ="
                        + " (SELECT exchange_reference FROM crossborder.beneficiary_registration r"
                        + " JOIN crossborder.beneficiary b ON b.registration_id = r.id WHERE b.nickname = ?)", nickname))
                .as("one registration").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM crossborder.beneficiary WHERE nickname = ?", nickname))
                .as("one beneficiary").isEqualTo(1);
    }

    @Test
    @DisplayName("revocation raced against a release: in either order a revoked beneficiary never becomes ACTIVE (counted)")
    void revocationRacesARelease() throws Exception {
        String customer = sessionWith(null);
        int rounds = 6;
        int revokedWins = 0;
        for (int round = 0; round < rounds; round++) {
            BeneficiaryId id = registered(customer, grant("match"), "HIT Raced Person " + round);
            assertThat(statusOf(id)).isEqualTo("IN_REVIEW");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revoke = pool.submit(() -> {
                start.await();
                return post(DOOR + "/" + id.value() + "/revocation", "", customer, key()).statusCode();
            });
            Future<?> release = pool.submit(() -> {
                start.await();
                review(id, Decision.RELEASE, ReasonCode.FALSE_POSITIVE);
                return null;
            });
            start.countDown();
            assertThat(revoke.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            release.get(60, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(statusOf(id)).as("round %d", round).isEqualTo("REVOKED");
            if (count("SELECT count(*) FROM crossborder.beneficiary_status_event WHERE beneficiary_id = ?"
                    + " AND to_status = 'ACTIVE'", id.value()) == 0) {
                revokedWins++;
            }
        }
        assertThat(rounds).as("every round ends REVOKED, whichever came first (%d revoked before release)", revokedWins)
                .isEqualTo(rounds);
    }

    // ------------------------------------------------------------------ security

    @Test
    @DisplayName("step-up: an MFA-enrolled customer on a PASSWORD session is refused, nothing registered")
    void stepUp() throws Exception {
        String password = sessionWith(null);
        enrolAndConfirm(password);
        long before = count("SELECT count(*) FROM crossborder.beneficiary");
        HttpResponse<String> refused = register(password, grant("match"), "Clear Person", false, key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertThat(count("SELECT count(*) FROM crossborder.beneficiary")).isEqualTo(before);
    }

    @Test
    @DisplayName("the needle (registration's leg): the name and the grant are in no table the application reads, no"
            + " event and no response - the name only as kyc's ciphertext")
    void theNameAndGrantReachNoSink() throws Exception {
        String customer = sessionWith(null);
        String name = "Quintavius Needleworth " + UUID.randomUUID().toString().substring(0, 6);
        String grant = grant("match");
        HttpResponse<String> registered = register(customer, grant, name, false, key());
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat(registered.body()).doesNotContain(name).doesNotContain(grant);
        BeneficiaryId id = BeneficiaryId.of(UUID.fromString(field(registered.body(), "id")));
        assertThat(get(DOOR + "/" + id.value(), customer).body()).doesNotContain(name).doesNotContain(grant);
        for (String needle : List.of(name, grant)) {
            assertThat(tablesHolding(needle)).as("tables holding %s in clear", needle.equals(name) ? "the name" : "the grant")
                    .isEmpty();
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE convert_from(payload, 'UTF8') LIKE ?",
                            "%" + needle + "%"))
                    .isZero();
        }
    }

    @Test
    @DisplayName("every text column of crossborder.beneficiary refuses a bank identifier, and the machine refuses a"
            + " revoked beneficiary's resurrection - by raw SQL")
    void theSchemaHoldsTheLine() throws Exception {
        String customer = sessionWith(null);
        BeneficiaryId id = registered(customer, grant("match"), "Clear Person");
        try (Connection app = DatabaseRoles.application()) {
            List<String> columns = new ArrayList<>();
            try (PreparedStatement select = app.prepareStatement(
                            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'crossborder'"
                                    + " AND table_name = 'beneficiary' AND data_type = 'text' ORDER BY ordinal_position");
                    ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
            assertThat(columns).contains("destination_reference", "nickname", "suffix");
            List<String> all = new ArrayList<>();
            try (PreparedStatement select = app.prepareStatement(
                            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'crossborder'"
                                    + " AND table_name = 'beneficiary' ORDER BY ordinal_position");
                    ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    all.add(rows.getString(1));
                }
            }
            for (String column : columns) {
                // A copy of a real row with one text column replaced by an IBAN: the CHECKs judge it before
                // the unique registration does, so each column's own rank answers.
                String projection = all.stream()
                        .map(name -> name.equals("id") ? "gen_random_uuid()"
                                : name.equals(column) ? "'GB82WEST12345698765432'" : name)
                        .collect(java.util.stream.Collectors.joining(", "));
                try (PreparedStatement insert = app.prepareStatement("INSERT INTO crossborder.beneficiary ("
                        + String.join(", ", all) + ") SELECT " + projection + " FROM crossborder.beneficiary WHERE id = ?")) {
                    insert.setObject(1, id.value());
                    insert.executeUpdate();
                    throw new AssertionError(column + " admitted an IBAN");
                } catch (SQLException refused) {
                    assertThat(refused.getSQLState()).as("%s: %s", column, refused.getMessage()).isEqualTo("23514");
                }
            }
        }
        assertThat(post(DOOR + "/" + id.value() + "/revocation", "", customer, key()).statusCode()).isEqualTo(200);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement resurrect = app.prepareStatement(
                        "UPDATE crossborder.beneficiary SET status = 'ACTIVE', revoked_at = NULL WHERE id = ?")) {
            resurrect.setObject(1, id.value());
            resurrect.executeUpdate();
            throw new AssertionError("a revoked beneficiary was resurrected");
        } catch (SQLException refused) {
            assertThat(refused.getMessage()).contains("final");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private BeneficiaryId registered(String session, String grant, String name) throws Exception {
        HttpResponse<String> registered = register(session, grant, name, false, key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return BeneficiaryId.of(UUID.fromString(field(registered.body(), "id")));
    }

    private HttpResponse<String> register(String session, String grant, String name, boolean acknowledge, String key)
            throws Exception {
        return post(DOOR, body("JP", "JPY", grant, name, acknowledge), session, key);
    }

    private static String body(String country, String currency, String grant, String name, boolean acknowledge) {
        return "{\"country\":\"" + country + "\",\"currency\":\"" + currency + "\",\"grant\":\"" + grant
                + "\",\"name\":\"" + name + "\",\"nickname\":\"Aunt in Osaka\",\"entityType\":\"INDIVIDUAL\""
                + (acknowledge ? ",\"acknowledgeNoMatch\":true" : "") + "}";
    }

    private static String grant(String payeeCheck) {
        return ENGINE.issueGrant(new SimulatedCorridorEngine.Beneficiary("JP", "JPY", "individual", payeeCheck));
    }

    private void review(BeneficiaryId id, Decision decision, ReasonCode code) {
        UUID screening = screeningOf(id);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            kycTransactionRunner.inTransaction(uow -> counterpartyScreenings.review(uow, CounterpartyScreeningId.of(screening),
                    new Actor("reviewer-" + UUID.randomUUID(), ActorType.EMPLOYEE), decision, code, "reviewed in the suite",
                    CorrelationId.generate(IDS)));
        }
    }

    private static String normalised(String body, BeneficiaryId id) {
        return body.replace(id.value().toString(), "<id>").replaceAll("\"suffix\":\"[A-Za-z0-9]{4}\"", "\"suffix\":\"<s>\"");
    }

    private static String statusOf(BeneficiaryId id) throws SQLException {
        return string("SELECT status FROM crossborder.beneficiary WHERE id = ?", id.value());
    }

    private static UUID screeningOf(BeneficiaryId id) {
        try {
            return UUID.fromString(string("SELECT screening_id::text FROM crossborder.beneficiary WHERE id = ?", id.value()));
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static UUID selectionOf(BeneficiaryId id) throws SQLException {
        return UUID.fromString(string("SELECT r.selection_id::text FROM crossborder.beneficiary b"
                + " JOIN crossborder.beneficiary_registration r ON r.id = b.registration_id WHERE b.id = ?", id.value()));
    }

    private static String screeningStatus(BeneficiaryId id) throws SQLException {
        return string("SELECT status FROM kyc.counterparty_screening WHERE id = ?", screeningOf(id));
    }

    private static String screeningReviewReason(BeneficiaryId id) throws SQLException {
        return string("SELECT review_reason FROM kyc.counterparty_screening WHERE id = ?", screeningOf(id));
    }

    private static String string(String sql, Object arg) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            select.setObject(1, arg);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as(sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                select.setObject(i + 1, args[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static List<String> tablesHolding(String needle) throws SQLException {
        List<String> holders = new ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(true);
            List<String> tables = new ArrayList<>();
            try (PreparedStatement list = app.prepareStatement(
                            "SELECT table_schema || '.' || table_name FROM information_schema.tables"
                                    + " WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')"
                                    + " AND has_table_privilege(table_schema || '.' || table_name, 'SELECT')");
                    ResultSet rows = list.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            for (String table : tables) {
                try (PreparedStatement scan = app.prepareStatement("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?")) {
                    scan.setString(1, "%" + needle + "%");
                    try (ResultSet count = scan.executeQuery()) {
                        count.next();
                        if (count.getLong(1) > 0) {
                            holders.add(table);
                        }
                    }
                }
            }
        }
        return holders;
    }

    private static boolean activeExists() throws SQLException {
        return count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") > 0;
    }

    private void withdrawPending(String controller) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id FROM crossborder.corridor_policy_version WHERE status = 'PROPOSED'");
                ResultSet row = select.executeQuery()) {
            if (row.next()) {
                assertThat(post(POLICIES + "/" + row.getObject(1, UUID.class) + "/rejection",
                                "{\"reason\":\"cleared by the beneficiary suite\"}", controller, null).statusCode())
                        .isEqualTo(200);
            }
        }
    }

    /** A signed-in session holding {@code role}, or a plain registered customer when it is null. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "bn." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, key()).statusCode())
                .isEqualTo(201);
        if (role != null) {
            UUID identity;
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
                read.setString(1, login);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    identity = row.getObject("id", UUID.class);
                }
            }
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
                app.commit();
            }
        }
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, key());
        return field(session.body(), "sessionToken");
    }

    /** Enrols and confirms a TOTP factor over the real endpoints - the PayoutDestinationEndpointDatabaseTest helper. */
    private void enrolAndConfirm(String sessionToken) throws Exception {
        Matcher matcher = Pattern.compile("secret=([A-Z2-7]+)").matcher(post("/v1/me/mfa", null, sessionToken, null).body());
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        Sensitive<String> secret = Sensitive.of(matcher.group(1));
        long step = Instant.now(CLOCK).getEpochSecond() / TotpParameters.current().periodSeconds();
        String confirming = Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond((step - 1) * TotpParameters.current().periodSeconds()));
        assertThat(post("/v1/me/mfa/confirmation", "{\"code\":\"" + confirming + "\"}", sessionToken, null).statusCode())
                .isEqualTo(204);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    /** The screening provider, scripted by the name: HIT is a hit, DOWN is unavailable, anything else clear. */
    @TestConfiguration
    static class ScriptedScreening {

        @Bean
        @Primary
        CounterpartyScreeningProvider scriptedCounterpartyScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> {
                if (subject.name().startsWith("HIT")) {
                    return CounterpartyScreeningProvider.Answer.of(Verdict.HIT, evidence);
                }
                if (subject.name().startsWith("DOWN")) {
                    return CounterpartyScreeningProvider.Answer.withoutEvidence(Verdict.UNAVAILABLE);
                }
                return CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
            };
        }
    }
}
