package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.CorrelationFilter;
import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.app.reconciliation.CorridorRuleSetV1;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.ledger.PostingService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
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
 * The FX and cross-border operator reads over HTTP (`P17-TSK-037`, gaps G-100…G-103): the reference rates with their age; each
 * pair's, provider's and corridor's availability with who changed it, when; the enable requests of both kinds, a §7 collection
 * each; the trades and the cross-border payments, §7 collections in a bounded window, every page recorded; each read under its
 * permission and refused without it; and the two searches walking `V014`'s and `V008`'s indexes.
 *
 * <p>Shared-container safe: every assertion names this suite's own rows or the order of whatever stands, never a count of
 * rows other suites may have written.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the FX and cross-border operator reads (P17-TSK-037)")
class FxCrossBorderReadsDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String FX = "/v1/operator/fx";
    private static final String CROSS_BORDER = "/v1/operator/cross-border";
    private static final String POLICIES = CROSS_BORDER + "/corridor-policies";
    private static final Pattern NEXT = Pattern.compile("\"nextCursor\":\"([^\"]+)\"");
    private static final String TRADES_LISTED = "fx.TradesListed";
    private static final String PAYMENTS_LISTED = "crossborder.CrossBorderPaymentsListed";
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;

    private static SimulatedCorridorEngine corridor() {
        try {
            return SimulatedCorridorEngine.start("a-corridor-callback-test-key-of-32-byte".getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) throws Exception {
        if (fxEngine == null) {
            fxEngine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(fxEngine, Map.of("EUR/USD", "1.0850240000"));
        }
        registry.add("finapp.fx.provider.url", () -> fxEngine.baseUrl().toString());
        registry.add("finapp.corridor.provider.url", () -> CORRIDOR.baseUrl().toString());
    }

    @AfterAll
    static void stop() {
        fxEngine.close();
        CORRIDOR.close();
    }

    private FxTestClient client() {
        return new FxTestClient(port);
    }

    @BeforeEach
    void policies() throws Exception {
        CORRIDOR.acceptOnReceipt(true);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        ensureCorridorRuleSetActive();
        awaitRoutingVersionInForce();
    }

    @Test
    @DisplayName("the reference rates: each canonical pair once, a decimal rate with its instants and its age in seconds by the"
            + " database's clock - never recorded; a price-setter and no session refused")
    void theReferenceRatesAndTheirAge() throws Exception {
        String investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        HttpResponse<String> rates = client().get(FX + "/rates", investigator);
        assertThat(rates.statusCode()).as(rates.body()).isEqualTo(200);
        assertThat(rates.body()).contains("\"source\":\"simulated-reference\"", "\"pair\":\"EUR/USD\"", "\"base\":\"EUR\"",
                "\"quote\":\"USD\"", "\"pair\":\"BHD/JPY\"");
        assertThat(occurrences(rates.body(), "\"pair\":\"")).as("one row per canonical pair").isEqualTo(10);
        String eurUsd = item(rates.body(), "\"pair\":\"EUR/USD\"");
        assertThat(eurUsd).containsPattern("\"rate\":\"[0-9]+(\\.[0-9]+)?\"").contains("\"observedAt\":\"", "\"receivedAt\":\"");
        Matcher seconds = Pattern.compile("\"ageSeconds\":(-?[0-9]+)").matcher(eurUsd);
        assertThat(seconds.find()).as(eurUsd).isTrue();
        assertThat(Long.parseLong(seconds.group(1))).as("fresh references were just received, never in the future")
                .isBetween(0L, 3600L);
        assertThat(recordsOf(rates)).as("market data, never recorded").isZero();

        assertThat(client().get(FX + "/rates", sessionWith(RoleName.FX_CONTROLLER)).statusCode()).isEqualTo(403);
        assertThat(client().get(FX + "/rates", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a pair, a provider and a corridor read with their availability and who changed it, when; the enable requests"
            + " of each kind listed newest first, a status narrowing them in the machine's words, a page continued by its"
            + " cursor; each read the controller's alone and never recorded")
    void theAvailabilityAndTheEnableRequests() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        String pairPath = FX + "/pairs/JPY-BHD/availability";
        String otherPath = FX + "/pairs/BHD-JPY/availability";
        String corridorPath = CROSS_BORDER + "/corridors/USD-EUR-DE/availability";
        String secondCorridorPath = CROSS_BORDER + "/corridors/USD-GBP-GB/availability";
        // The database outlives a run: a subject a failed run left stopped is restarted first.
        for (String path : List.of(pairPath, otherPath, corridorPath, secondCorridorPath)) {
            restarted(path, first, second);
        }
        List<HttpResponse<String>> reads = new ArrayList<>();

        // An FX pair stopped, then proposed for enabling, then approved by a second controller.
        assertThat(client().post(pairPath, availability(false, "a reads outage"), first, FxTestClient.key()).statusCode())
                .isEqualTo(200);
        HttpResponse<String> pairs = read(reads, FX + "/pairs", first);
        assertThat(item(pairs.body(), "\"subject\":\"JPY-BHD\"")).contains("\"available\":false", "\"reason\":\"a reads outage\"",
                "\"changedBy\":\"", "\"changedAt\":\"");
        assertThat(occurrences(pairs.body(), "\"subject\":\"")).as("every directed pair of five currencies").isEqualTo(20);
        String pairRequest = field(client().post(pairPath, availability(true, "a reads recovery"), first, FxTestClient.key())
                .body(), "requestId");
        // A second subject's request, so a page of one has a successor.
        assertThat(client().post(otherPath, availability(false, "a second outage"), first, FxTestClient.key()).statusCode())
                .isEqualTo(200);
        String otherRequest = field(client().post(otherPath, availability(true, "a second recovery"), first,
                FxTestClient.key()).body(), "requestId");

        HttpResponse<String> pending = read(reads, FX + "/enable-requests?status=PROPOSED", second);
        assertThat(item(pending.body(), "\"id\":\"" + pairRequest + "\"")).contains("\"subjectKind\":\"PAIR\"",
                "\"subject\":\"JPY-BHD\"", "\"status\":\"PROPOSED\"", "\"reason\":\"a reads recovery\"", "\"proposedBy\":\"",
                "\"proposedAt\":\"").doesNotContain("decidedBy");
        assertThat(pending.body().indexOf(otherRequest)).as("newest first").isLessThan(pending.body().indexOf(pairRequest));
        walksNewestFirst(FX + "/enable-requests?status=PROPOSED", second, "proposedAt", null);

        assertThat(client().post(FX + "/enable-requests/" + pairRequest + "/approval", "{\"reason\":\"verified\"}", second,
                null).statusCode()).isEqualTo(200);
        String approved = item(read(reads, FX + "/enable-requests?status=APPROVED", first).body(),
                "\"id\":\"" + pairRequest + "\"");
        assertThat(approved).contains("\"status\":\"APPROVED\"", "\"decidedBy\":\"", "\"decidedAt\":\"",
                "\"decisionReason\":\"verified\"");
        assertThat(field(approved, "decidedBy")).isNotEqualTo(field(approved, "proposedBy"));
        assertThat(read(reads, FX + "/enable-requests?status=PROPOSED", first).body())
                .as("a decided request leaves the approver's queue").doesNotContain(pairRequest);
        assertThat(item(read(reads, FX + "/pairs", first).body(), "\"subject\":\"JPY-BHD\"")).contains("\"available\":true");
        assertThat(client().post(FX + "/enable-requests/" + otherRequest + "/approval", "{\"reason\":\"verified\"}", second,
                null).statusCode()).isEqualTo(200);

        assertThat(read(reads, FX + "/providers", first).body()).contains("\"subject\":\"fx-sim-a\"");

        // A corridor outside the active policy stopped and proposed; the active policy's corridors listed.
        assertThat(client().post(corridorPath, availability(false, "a corridor outage"), first, FxTestClient.key())
                .statusCode()).isEqualTo(200);
        String corridorRequest = field(client().post(corridorPath, availability(true, "a corridor recovery"), first,
                FxTestClient.key()).body(), "requestId");
        assertThat(client().post(secondCorridorPath, availability(false, "a second corridor outage"), first,
                FxTestClient.key()).statusCode()).isEqualTo(200);
        String secondCorridorRequest = field(client().post(secondCorridorPath, availability(true, "a second corridor recovery"),
                first, FxTestClient.key()).body(), "requestId");
        HttpResponse<String> corridorRequests = read(reads, CROSS_BORDER + "/corridor-enable-requests?status=PROPOSED", second);
        assertThat(item(corridorRequests.body(), "\"id\":\"" + corridorRequest + "\"")).contains(
                "\"corridor\":\"USD-EUR-DE\"", "\"status\":\"PROPOSED\"", "\"reason\":\"a corridor recovery\"");
        walksNewestFirst(CROSS_BORDER + "/corridor-enable-requests", second, "proposedAt", null);
        for (String request : List.of(corridorRequest, secondCorridorRequest)) {
            assertThat(client().post(CROSS_BORDER + "/corridor-enable-requests/" + request + "/approval",
                    "{\"reason\":\"verified\"}", second, null).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> corridors = read(reads, CROSS_BORDER + "/corridors", first);
        assertThat(corridors.body()).contains("\"policyVersion\":");
        assertThat(item(corridors.body(), "\"corridor\":\"EUR-USD-US\"")).contains("\"rails\":[", "\"maximumAmount\":\"",
                "\"maximumCurrency\":\"USD\"", "\"deliveryEstimateHours\":", "\"available\":true");
        for (HttpResponse<String> served : reads) {
            assertThat(recordsOf(served)).as("configuration reads, never recorded: %s", served.uri()).isZero();
        }

        // The machine's words only; a foreign or unreadable cursor and an oversized page refused - the door's refusal alone.
        for (String refused : List.of(FX + "/enable-requests?status=PENDING", FX + "/enable-requests?cursor=not-a-cursor",
                FX + "/enable-requests?limit=201", CROSS_BORDER + "/corridor-enable-requests?status=pending",
                CROSS_BORDER + "/corridor-enable-requests?cursor=" + nextCursor(client().get(
                        FX + "/enable-requests?limit=1", first).body()))) {
            HttpResponse<String> answer = client().get(refused, first);
            assertThat(answer.statusCode()).as(refused).isEqualTo(422);
            assertThat(recordsOf(answer) - recordsOf(answer, "door.ActRefused")).as("nothing but the refusal: %s", refused)
                    .isZero();
        }

        String investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        for (String route : List.of(FX + "/pairs", FX + "/providers", FX + "/enable-requests", CROSS_BORDER + "/corridors",
                CROSS_BORDER + "/corridor-enable-requests")) {
            assertThat(client().get(route, investigator).statusCode()).as("%s without the controller's role", route)
                    .isEqualTo(403);
            assertThat(client().get(route, null).statusCode()).as("%s without a session", route).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("the trades in a window: newest first, narrowed by pair and status, continued by a cursor, every page"
            + " recorded; a malformed or unbounded window refused and no page recorded; the investigator's alone")
    void theTradesAreListedAndRecorded() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        String conversion = convertedTrade(client(), customer, "EUR", "USD", "FIXED_SOURCE", "100.00");
        String later = convertedTrade(client(), customer, "EUR", "USD", "FIXED_SOURCE", "50.00");
        String investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Instant now = Instant.now();
        String window = "from=" + now.minus(Duration.ofHours(1)) + "&to=" + now.plus(Duration.ofHours(1));

        HttpResponse<String> listed = client().get(FX + "/trades?" + window + "&pair=EUR-USD&status=BOOKED&limit=200",
                investigator);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(recordsOf(listed, TRADES_LISTED)).as("the page recorded").isEqualTo(1);
        assertThat(item(listed.body(), "\"id\":\"" + conversion + "\"")).contains("\"purpose\":\"CONVERSION\"",
                "\"pair\":\"EUR-USD\"", "\"fixedSide\":\"FIXED_SOURCE\"", "\"status\":\"BOOKED\"", "\"sourceAmount\":\"100.00\"",
                "\"sourceCurrency\":\"EUR\"", "\"destinationCurrency\":\"USD\"", "\"executedRate\":\"", "\"providerCode\":\"",
                "\"ownerPartyId\":\"", "\"bookedAt\":\"", "\"bookedOn\":\"");
        assertThat(listed.body().indexOf(later)).as("newest first").isLessThan(listed.body().indexOf(conversion));
        walksNewestFirst(FX + "/trades?" + window, investigator, "bookedAt", TRADES_LISTED);
        assertThat(client().get(FX + "/trades?" + window + "&pair=GBP-JPY", investigator).body()).doesNotContain(conversion);
        assertThat(client().get(FX + "/trades?" + window + "&status=REVERSED", investigator).body()).doesNotContain(conversion);

        refusedUnserved(FX + "/trades", investigator, now, window, TRADES_LISTED,
                List.of("&pair=EUR-EUR", "&pair=EURUSD", "&status=booked"));
        String route = FX + "/trades?" + window;
        for (String bearer : java.util.Arrays.asList(sessionWith(RoleName.FX_CONTROLLER), null, customer.token())) {
            HttpResponse<String> refused = client().get(route, bearer);
            assertThat(refused.statusCode()).as("without FX_INVESTIGATE").isIn(401, 403);
            assertThat(recordsOf(refused, TRADES_LISTED)).as("a refused reader is served no page").isZero();
        }
    }

    @Test
    @DisplayName("the cross-border payments in a window: newest first, narrowed by corridor and status, continued by a"
            + " cursor, every page recorded; a malformed or unbounded window refused and no page recorded; the investigator's"
            + " alone")
    void theCrossBorderPaymentsAreListedAndRecorded() throws Exception {
        String payment = paidCrossBorder();
        String later = paidCrossBorder();
        String investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Instant now = Instant.now();
        String window = "from=" + now.minus(Duration.ofHours(1)) + "&to=" + now.plus(Duration.ofHours(1));

        HttpResponse<String> listed = client().get(CROSS_BORDER + "/payments?" + window + "&corridor=EUR-USD-US&limit=200",
                investigator);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(recordsOf(listed, PAYMENTS_LISTED)).as("the page recorded").isEqualTo(1);
        assertThat(item(listed.body(), "\"id\":\"" + payment + "\"")).contains("\"corridor\":\"EUR-USD-US\"",
                "\"sentAmount\":\"1000.00\"", "\"sourceCurrency\":\"EUR\"", "\"destinationCurrency\":\"USD\"",
                "\"feeAmount\":\"", "\"totalDebitAmount\":\"", "\"deliveredAmount\":\"", "\"beneficiaryId\":\"",
                "\"ownerPartyId\":\"", "\"createdAt\":\"").doesNotContain("failureReason");
        assertThat(listed.body().indexOf(later)).as("newest first").isLessThan(listed.body().indexOf(payment));
        assertThat(client().get(CROSS_BORDER + "/payments?" + window + "&status=FAILED", investigator).body())
                .doesNotContain(payment);
        walksNewestFirst(CROSS_BORDER + "/payments?" + window, investigator, "createdAt", PAYMENTS_LISTED);

        refusedUnserved(CROSS_BORDER + "/payments", investigator, now, window, PAYMENTS_LISTED,
                List.of("&corridor=EUR-USD", "&status=in_transit"));
        String route = CROSS_BORDER + "/payments?" + window;
        for (String bearer : java.util.Arrays.asList(sessionWith(RoleName.FX_CONTROLLER), null)) {
            HttpResponse<String> refused = client().get(route, bearer);
            assertThat(refused.statusCode()).as("without FX_INVESTIGATE").isIn(401, 403);
            assertThat(recordsOf(refused, PAYMENTS_LISTED)).as("a refused reader is served no page").isZero();
        }
    }

    /**
     * Every malformed or unbounded window, cursor and limit of {@code base} - and each of {@code filters} - refused {@code 422},
     * no page served or recorded.
     */
    private void refusedUnserved(String base, String investigator, Instant now, String window, String listed,
            List<String> filters) throws Exception {
        Instant tooLate = now.plus(Duration.ofDays(32));
        List<String> refusals = new ArrayList<>(List.of(
                base,
                base + "?from=" + now,
                base + "?from=yesterday&to=" + now,
                base + "?from=" + now + "&to=" + now,
                base + "?from=" + now + "&to=" + tooLate,
                base + "?" + window + "&cursor=not-a-cursor",
                base + "?" + window + "&limit=0"));
        filters.forEach(filter -> refusals.add(base + "?" + window + filter));
        for (String refused : refusals) {
            HttpResponse<String> answer = client().get(refused, investigator);
            assertThat(answer.statusCode()).as(refused).isEqualTo(422);
            assertThat(recordsOf(answer, listed)).as("no page recorded: %s", refused).isZero();
        }
    }

    @Test
    @DisplayName("the trade and payment searches walk V014's and V008's indexes backwards, a window, a pair's and a"
            + " corridor's - no sequential scan and no sort")
    void theSearchesWalkTheirIndexes() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                try (PreparedStatement off = app.prepareStatement("SET LOCAL enable_seqscan = off")) {
                    off.execute();
                }
                String resume = " < (now(), '" + IDS.next() + "'::uuid)";
                for (String[] probe : List.of(
                        new String[] {"trade_by_booking", "EXPLAIN SELECT t.* FROM fx.trade t WHERE t.booked_at >= now()"
                                + " - interval '1 day' AND t.booked_at < now() AND (t.booked_at, t.id)" + resume
                                + " ORDER BY t.booked_at DESC, t.id DESC LIMIT 51"},
                        new String[] {"trade_by_pair_and_booking", "EXPLAIN SELECT t.* FROM fx.trade t WHERE t.booked_at >="
                                + " now() - interval '1 day' AND t.booked_at < now() AND t.source_currency = 'EUR' AND"
                                + " t.destination_currency = 'USD' AND (t.booked_at, t.id)" + resume
                                + " ORDER BY t.booked_at DESC, t.id DESC LIMIT 51"},
                        new String[] {"payment_by_creation", "EXPLAIN SELECT p.* FROM crossborder.payment p WHERE"
                                + " p.created_at >= now() - interval '1 day' AND p.created_at < now() AND (p.created_at, p.id)"
                                + resume + " ORDER BY p.created_at DESC, p.id DESC LIMIT 51"},
                        new String[] {"payment_by_corridor_and_creation", "EXPLAIN SELECT p.* FROM crossborder.payment p"
                                + " WHERE p.created_at >= now() - interval '1 day' AND p.created_at < now() AND p.corridor ="
                                + " 'EUR-USD-US' AND (p.created_at, p.id)" + resume
                                + " ORDER BY p.created_at DESC, p.id DESC LIMIT 51"})) {
                    String plan = plan(app, probe[1]);
                    assertThat(plan).as(plan).contains("Scan Backward using " + probe[0]).doesNotContain("Sort");
                }
            } finally {
                app.rollback();
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Walks {@code route} a page of one at a time - at most twenty pages - each page continuing the last by its cursor: each
     * row's {@code stamp} never later than the row before it, and each page recorded once as {@code recordedAs} (or not at all
     * when it is {@code null}).
     */
    private void walksNewestFirst(String route, String bearer, String stamp, String recordedAs) throws Exception {
        String separator = route.contains("?") ? "&" : "?";
        HttpResponse<String> page = client().get(route + separator + "limit=1", bearer);
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertRecorded(page, recordedAs);
        String previous = field(page.body(), stamp);
        String cursor = nextCursor(page.body());
        int continued = 0;
        while (cursor != null && continued < 20) {
            page = client().get(route + separator + "limit=1&cursor=" + cursor, bearer);
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            assertRecorded(page, recordedAs);
            continued++;
            String current = field(page.body(), stamp);
            assertThat(Instant.parse(current)).as("newest first").isBeforeOrEqualTo(Instant.parse(previous));
            previous = current;
            cursor = nextCursor(page.body());
        }
        assertThat(continued).as("a page of one has a successor here").isPositive();
    }

    private static void assertRecorded(HttpResponse<String> page, String recordedAs) throws Exception {
        if (recordedAs == null) {
            assertThat(recordsOf(page)).as("never recorded: %s", page.uri()).isZero();
        } else {
            assertThat(recordsOf(page, recordedAs)).as("recorded once: %s", page.uri()).isEqualTo(1);
            assertThat(recordsOf(page)).as("and nothing else: %s", page.uri()).isEqualTo(1);
        }
    }

    /** A read served and kept for the record check. */
    private HttpResponse<String> read(List<HttpResponse<String>> reads, String route, String bearer) throws Exception {
        HttpResponse<String> served = client().get(route, bearer);
        assertThat(served.statusCode()).as(route + ": " + served.body()).isEqualTo(200);
        reads.add(served);
        return served;
    }

    /** Leaves the subject at {@code path} available: a live request approved, or a stopped one proposed and approved. */
    private void restarted(String path, String first, String second) throws Exception {
        String subject = path.replaceAll(".*/(pairs|corridors)/([^/]+)/availability$", "$2");
        boolean pair = path.contains("/fx/pairs/");
        String stopped = pair
                ? "SELECT count(*) FROM (SELECT available FROM fx.pair_availability WHERE source_currency || '-' ||"
                        + " destination_currency = ? ORDER BY seq DESC LIMIT 1) latest WHERE NOT latest.available"
                : "SELECT count(*) FROM (SELECT available FROM crossborder.corridor_availability WHERE corridor = ?"
                        + " ORDER BY seq DESC LIMIT 1) latest WHERE NOT latest.available";
        if (count(stopped, subject) == 0) {
            return;
        }
        String live = pair
                ? "SELECT count(*) FROM fx.availability_enable_request WHERE subject_kind = 'PAIR' AND subject = ?"
                        + " AND status = 'PROPOSED'"
                : "SELECT count(*) FROM crossborder.corridor_enable_request WHERE corridor = ? AND status = 'PROPOSED'";
        String request = count(live, subject) > 0
                ? scalar(live.replace("count(*)", "id::text"), subject)
                : field(client().post(path, availability(true, "restarted before the reads suite"), first, FxTestClient.key())
                        .body(), "requestId");
        String approval = pair ? FX + "/enable-requests/" : CROSS_BORDER + "/corridor-enable-requests/";
        assertThat(client().post(approval + request + "/approval", "{\"reason\":\"restarted\"}", second, null).statusCode())
                .isEqualTo(200);
    }

    private static String nextCursor(String body) {
        Matcher next = NEXT.matcher(body);
        return next.find() ? next.group(1) : null;
    }

    /** The JSON object in {@code body} that contains {@code marker}. */
    private static String item(String body, String marker) {
        int at = body.indexOf(marker);
        assertThat(at).as("%s in %s", marker, body).isNotNegative();
        return body.substring(body.lastIndexOf('{', at), body.indexOf('}', at) + 1);
    }

    private static int occurrences(String body, String marker) {
        int found = 0;
        for (int at = body.indexOf(marker); at >= 0; at = body.indexOf(marker, at + 1)) {
            found++;
        }
        return found;
    }

    /** The records the answer's own request wrote - by its correlation, so another suite's writes never count. */
    private static long recordsOf(HttpResponse<String> answer) throws Exception {
        return count("SELECT count(*) FROM platform.audit_record WHERE correlation_id = ?", correlation(answer));
    }

    private static long recordsOf(HttpResponse<String> answer, String operation) throws Exception {
        return count("SELECT count(*) FROM platform.audit_record WHERE correlation_id = ? AND operation = ?",
                correlation(answer), operation);
    }

    private static String correlation(HttpResponse<String> answer) {
        return answer.headers().firstValue(CorrelationFilter.HEADER)
                .orElseThrow(() -> new AssertionError("every answer carries its correlation: " + answer.uri()));
    }

    private static String plan(Connection app, String explain) throws Exception {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement statement = app.prepareStatement(explain); ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                lines.add(row.getString(1));
            }
        }
        return String.join("\n", lines);
    }

    private static String availability(boolean available, String reason) {
        return "{\"available\":" + available + ",\"reason\":\"" + reason + "\"}";
    }

    /** Pays a cross-border offer of 1000.00 EUR to a US beneficiary; the provider accepts at once. */
    private String paidCrossBorder() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("1100.00", "EUR"));
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary("US", "USD", "individual", "match"));
        HttpResponse<String> registered = client().post("/v1/me/cross-border/beneficiaries", "{\"country\":\"US\",\"currency\":"
                + "\"USD\",\"grant\":\"" + grant + "\",\"name\":\"Clear Person\",\"nickname\":\"Payee\",\"entityType\":"
                + "\"INDIVIDUAL\"}", customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        HttpResponse<String> offered = client().post("/v1/me/cross-border/quotes", "{\"beneficiaryId\":\""
                + field(registered.body(), "id") + "\",\"sourceCurrency\":\"EUR\",\"fixedSide\":\"FIXED_SOURCE\","
                + "\"amount\":\"1000.00\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        HttpResponse<String> paid = client().post("/v1/me/cross-border/payments", "{\"quoteId\":\"" + field(offered.body(), "id")
                + "\"}", customer.token(), FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        return field(paid.body(), "paymentId");
    }

    private static void awaitRoutingVersionInForce() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (count("SELECT count(*) FROM payments.routing_policy_version WHERE version = 5 AND effective_from <= ?",
                java.sql.Timestamp.from(Instant.now())) == 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    /** corridor-sim-a's source version 1, when absent - as Phase9ReportsDatabaseTest performs it. */
    private void ensureCorridorRuleSetActive() throws Exception {
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'",
                CorridorRuleSetV1.SOURCE) > 0) {
            return;
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSetAdministration.propose(app, CorridorRuleSetV1.proposal("The corridor source's first version"),
                    new Actor("op-corridor-controller-a", ActorType.EMPLOYEE), Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSetAdministration.approve(app, ruleSet, new Actor("op-corridor-controller-b", ActorType.EMPLOYEE),
                    "Reviewed against O7", Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
    }

    private void activateCorridorPolicy() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id FROM crossborder.corridor_policy_version WHERE status = 'PROPOSED'");
                ResultSet row = select.executeQuery()) {
            if (row.next()) {
                client().post(POLICIES + "/" + row.getObject(1, UUID.class) + "/rejection", "{\"reason\":\"cleared\"}", first, null);
            }
        }
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the reads suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval", "{\"reason\":\"checked\"}",
                second, null).statusCode()).isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "fr." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client().post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key()).statusCode())
                .isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client().post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key());
        return com.finapp.app.session.OperatorStepUp.elevated(field(session.body(), "sessionToken"), client()::post);
    }

    @TestConfiguration
    static class Doubles {

        @Bean
        @Primary
        CounterpartyScreeningProvider clearReadsScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
