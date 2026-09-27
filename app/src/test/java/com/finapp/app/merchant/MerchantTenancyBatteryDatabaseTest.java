package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.core.MethodParameter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <strong>The cross-tenant battery</strong> (`P6-TST-001`, {@code INV-MER-01}): every merchant
 * endpoint, merchant A on merchant B's world — one refusal, zero rows — mechanised so a new
 * endpoint cannot dodge it.
 *
 * <h2>How a new endpoint is caught</h2>
 *
 * <p>The routes come from the application's own {@code RequestMappingHandlerMapping}, never from
 * a list: every handler on the merchant key, and every operator handler whose path names a
 * merchant. {@link #routes()} must name exactly that set, and each route's kind is derived from
 * its shape and held against the table, so a route can be neither missed nor mislabelled:
 *
 * <ul>
 *   <li>{@link Kind#DERIVED} — the merchant key and no path variable. The tenant IS the key, and
 *       the handler must take nothing that could name another tenant's world: no path variable,
 *       no identifier-shaped parameter or body field. Driven: A's reads and writes stay in A's
 *       world, and a payout pays only A's own destination.
 *   <li>{@link Kind#ID_ADDRESSED} — the merchant key and a path variable naming a resource. The
 *       tenant predicate in the statement is the control, and it is <em>probed</em>.
 *   <li>{@link Kind#PAIRED} — an operator path naming a merchant AND one of its resources. The
 *       pairing is the control, and it is probed: A's path, B's resource.
 *   <li>{@link Kind#SUBJECT} — an operator path naming only a merchant. The operator population
 *       acts across tenants by permission, so the merchant is the subject rather than a
 *       boundary; the route must declare its permission, whose own negatives live with it.
 * </ul>
 *
 * <h2>What a probe asserts</h2>
 *
 * <p>A's credential or path on B's resource answers {@code 404}, <strong>normalised
 * byte-identical</strong> to A on an identifier that does not exist, and both worlds'
 * fingerprints — every row of theirs a merchant route can write, their history, their audit and
 * outbox rows, their payable's lines and holds — are unchanged. Then, after every refusal, a
 * positive control: B on its own resource is <em>not</em> a {@code 404}, so a probe that misses
 * for reasons of its own cannot pass as a refusal.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the cross-tenant battery (P6-TST-001)")
class MerchantTenancyBatteryDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String PAID =
            "{\"status\":\"paid\",\"reference\":\"po-{{request.headers.Idempotency-Key}}\"}";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    /**
     * A name that could carry an identifier into a {@link Kind#DERIVED} route. Camel-case
     * aware, so {@code paidOut} is not an identifier and {@code paymentMethodId} is.
     */
    private static final Pattern IDENTIFIER_SHAPED =
            Pattern.compile("^(id|ref)$|.*(Id|Ref)$|(?i).*merchant.*");

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private PostingService postings;

    /** The MVC mapping, by name - actuator registers a second one (EveryEndpointDeclaresARuleTest). */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private String operator;
    private String schedule;

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
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
    }

    @BeforeEach
    void reset() {
        provider.reset();
        provider.succeedsWith(SimulatedPayoutProvider.PAYOUTS_PATH, 200, PAID);
    }

    // ----------------------------------------------------------------- the table

    /** How a merchant route knows whose world it acts in - derived from the route's shape. */
    private enum Kind {
        DERIVED,
        ID_ADDRESSED,
        PAIRED,
        SUBJECT
    }

    /** The route, as {@code caller}, addressing {@code resource}. */
    @FunctionalInterface
    private interface Probe {
        HttpResponse<String> send(World caller, String resource) throws Exception;
    }

    /** One row. A probe and its resource are required of the two addressed kinds, and only them. */
    private record Route(Kind kind, Probe probe, Function<World, String> resource, String reason) {}

    /** Every merchant route, by {@code METHOD pattern}. Held to the served set, exactly. */
    private Map<String, Route> routes() {
        Map<String, Route> table = new TreeMap<>();

        // THE MERCHANT KEY, NO IDENTIFIER: the tenant is the credential.
        table.put(
                "POST /v1/checkout/sessions",
                derived("opens the key's own offer - an amount, a currency, a summary"));
        table.put("GET /v1/merchant/me", derived("the key's own record"));
        table.put("GET /v1/merchant/payable", derived("the key's own payable position"));
        table.put(
                "GET /v1/merchant/transactions",
                derived("the key's own payable's movements; from and to are dates"));
        table.put(
                "POST /v1/merchant/payouts",
                derived("from the key's own payable to the key's own effective destination"));
        table.put(
                "GET /v1/merchant/disputes",
                derived("the disputes on payments that credited the key's own payable"
                        + " (P7-TSK-012)"));

        // THE MERCHANT KEY, A RESOURCE NAMED: the tenant predicate in the statement.
        table.put(
                "GET /v1/checkout/sessions/{id}",
                addressed(
                        World::session,
                        (caller, id) -> get("/v1/checkout/sessions/" + id, caller.key())));
        table.put(
                "POST /v1/checkout/sessions/{id}/abandonment",
                addressed(
                        World::session,
                        (caller, id) ->
                                post(
                                        "/v1/checkout/sessions/" + id + "/abandonment",
                                        "{\"reason\":\"the customer left\"}",
                                        caller.key(),
                                        null)));
        table.put(
                "GET /v1/merchant/payouts/{payoutId}",
                addressed(
                        World::payout,
                        (caller, id) -> get("/v1/merchant/payouts/" + id, caller.key())));
        // P7-TSK-012: the dispute's tenant is the disputed payment's credit account, and the
        // predicate rides the statement - a read, so the fingerprint must not move either way.
        table.put(
                "GET /v1/merchant/disputes/{disputeId}",
                addressed(
                        World::dispute,
                        (caller, id) -> get("/v1/merchant/disputes/" + id, caller.key())));
        // P7-TSK-014: answering a chargeback - the same predicate in every statement, the
        // upload's and the answer's LOCKING reads included; a refused act writes nothing.
        table.put(
                "POST /v1/merchant/disputes/{disputeId}/evidence",
                addressed(
                        World::dispute,
                        (caller, id) ->
                                post(
                                        "/v1/merchant/disputes/" + id + "/evidence",
                                        evidenceBody(),
                                        caller.key(),
                                        null)));
        // The document's resource is the PAIR "dispute/evidence" - B's dispute AND B's document
        // under A's key, the direct attack; an unknown single identifier addresses both.
        table.put(
                "GET /v1/merchant/disputes/{disputeId}/evidence/{evidenceId}",
                addressed(
                        World::evidence,
                        (caller, pair) -> {
                            String[] ids = pair.split("/");
                            String evidence = ids.length > 1 ? ids[1] : ids[0];
                            return get(
                                    "/v1/merchant/disputes/" + ids[0] + "/evidence/" + evidence,
                                    caller.key());
                        }));
        for (String answer : List.of("representment", "acceptance")) {
            table.put(
                    "POST /v1/merchant/disputes/{disputeId}/" + answer,
                    addressed(
                            World::dispute,
                            (caller, id) ->
                                    post(
                                            "/v1/merchant/disputes/" + id + "/" + answer,
                                            null,
                                            caller.key(),
                                            IDS.next().toString())));
        }

        // AN OPERATOR'S PAIRING: a merchant and one of its resources, both in the path.
        table.put(
                "DELETE /v1/operator/merchants/{id}/api-keys/{keyId}",
                paired(
                        World::spareKeyId,
                        (caller, id) ->
                                delete(
                                        "/v1/operator/merchants/" + caller.id() + "/api-keys/"
                                                + id,
                                        "{\"reason\":\"the key was rotated\"}",
                                        operator())));
        for (String decision : List.of("approval", "rejection", "withdrawal")) {
            table.put(
                    "POST /v1/operator/merchants/{merchantId}/payout-destinations/{destinationId}/"
                            + decision,
                    paired(
                            World::proposedDestination,
                            (caller, id) ->
                                    post(
                                            "/v1/operator/merchants/" + caller.id()
                                                    + "/payout-destinations/" + id + "/"
                                                    + decision,
                                            "{\"reason\":\"checked against the bank letter\"}",
                                            operator(),
                                            null)));
        }

        // AN OPERATOR'S SUBJECT: one merchant, named under a permission. WHICH permission is
        // pinned by RoutePermissionRegisterTest and the refusal of a caller without it proven
        // once for every route by DenyByDefaultDatabaseTest - the two compose into each
        // route's negative. P6-DOC-001: these reasons cited per-class negatives, and for seven
        // of the eleven routes the class named held none for that route.
        for (String route :
                List.of(
                        "GET /v1/operator/merchants/{id}",
                        "POST /v1/operator/merchants/{id}/suspension",
                        "POST /v1/operator/merchants/{id}/reinstatement",
                        "POST /v1/operator/merchants/{id}/closure")) {
            table.put(route, subject("MERCHANT_ADMINISTER; RoutePermissionRegisterTest"));
        }
        for (String route :
                List.of(
                        "GET /v1/operator/merchants/{id}/api-keys",
                        "POST /v1/operator/merchants/{id}/api-keys")) {
            table.put(
                    route,
                    subject(
                            "MERCHANT_ADMINISTER; RoutePermissionRegisterTest,"
                                    + " MerchantApiKeyDatabaseTest for issuance"));
        }
        for (String route :
                List.of(
                        "GET /v1/operator/merchants/{merchantId}/fee-schedule",
                        "PUT /v1/operator/merchants/{merchantId}/fee-schedule")) {
            table.put(route, subject("FEE_ADMINISTER; RoutePermissionRegisterTest"));
        }
        for (String route :
                List.of(
                        "GET /v1/operator/merchants/{merchantId}/payout-destinations",
                        "POST /v1/operator/merchants/{merchantId}/payout-destinations")) {
            table.put(
                    route,
                    subject(
                            "MERCHANT_ADMINISTER; RoutePermissionRegisterTest,"
                                    + " PayoutDestinationEndpointDatabaseTest"));
        }
        table.put(
                "POST /v1/operator/merchants/{merchantId}/payouts",
                subject(
                        "MERCHANT_PAYOUT; RoutePermissionRegisterTest,"
                                + " MerchantPayoutEndpointDatabaseTest"));
        return table;
    }

    private static Route derived(String reason) {
        return new Route(Kind.DERIVED, null, null, reason);
    }

    private static Route addressed(Function<World, String> resource, Probe probe) {
        return new Route(Kind.ID_ADDRESSED, probe, resource, null);
    }

    private static Route paired(Function<World, String> resource, Probe probe) {
        return new Route(Kind.PAIRED, probe, resource, null);
    }

    private static Route subject(String permissionAndItsNegatives) {
        return new Route(Kind.SUBJECT, null, null, permissionAndItsNegatives);
    }

    // ----------------------------------------------------------------- the battery

    @Test
    @DisplayName("the table is EVERY merchant route the application serves - derived, never"
            + " listed - and each route's kind is its shape's")
    void theTableIsEveryMerchantRoute() {
        Map<String, HandlerMethod> served = merchantRoutes();
        Map<String, Route> table = routes();

        assertThat(new TreeSet<>(served.keySet()))
                .as("a merchant route this battery does not name is one no probe covers: add it"
                        + " to routes() with the kind its shape has, and a probe if it is"
                        + " addressed")
                .isEqualTo(new TreeSet<>(table.keySet()));

        List<String> wrong = new ArrayList<>();
        served.forEach(
                (route, handler) -> {
                    Route row = table.get(route);
                    Kind shape = shapeOf(route, handler);
                    if (row.kind() != shape) {
                        wrong.add(route + " is " + shape + ", listed " + row.kind());
                    }
                    switch (shape) {
                        case ID_ADDRESSED, PAIRED -> {
                            if (row.probe() == null || row.resource() == null) {
                                wrong.add(route + " is addressed and carries no probe");
                            }
                        }
                        case DERIVED ->
                                identifierShapedInputs(handler)
                                        .forEach(input -> wrong.add(route + " takes " + input));
                        case SUBJECT -> {
                            if (!declares(handler, RequiresPermission.class)) {
                                wrong.add(route + " names a merchant under no permission");
                            }
                        }
                    }
                });
        assertThat(wrong)
                .as("a route's kind is its shape's, and each kind brings what it owes")
                .isEmpty();
        assertThat(EnumSet.copyOf(table.values().stream().map(Route::kind).toList()))
                .as("the battery is not vacuous: every kind is present")
                .isEqualTo(EnumSet.allOf(Kind.class));
    }

    @Test
    @DisplayName("INV-MER-01: every ADDRESSED route answers A on B's resource exactly as an unknown"
            + " one - normalised byte-identical - and neither world moves")
    void everyAddressedRouteAnswersAnotherTenantsResourceAsUnknown() throws Exception {
        World a = world("100.00", false);
        World b = world("250.00", true);
        Map<String, Route> table = routes();

        List<String> probed = new ArrayList<>();
        for (Map.Entry<String, Route> row : table.entrySet()) {
            Route route = row.getValue();
            if (route.probe() == null) {
                continue;
            }
            String before = fingerprint(a) + " / " + fingerprint(b);

            HttpResponse<String> foreign = route.probe().send(a, route.resource().apply(b));
            HttpResponse<String> unknown = route.probe().send(a, IDS.next().toString());

            assertThat(foreign.statusCode())
                    .as("%s, A on B's resource: %s", row.getKey(), foreign.body())
                    .isEqualTo(404);
            assertThat(unknown.statusCode())
                    .as("%s, A on nothing: %s", row.getKey(), unknown.body())
                    .isEqualTo(404);
            assertThat(normalised(foreign.body()))
                    .as("%s: another tenant's resource is indistinguishable from none",
                            row.getKey())
                    .isEqualTo(normalised(unknown.body()));
            assertThat(fingerprint(a) + " / " + fingerprint(b))
                    .as("%s: zero rows touched in either world", row.getKey())
                    .isEqualTo(before);
            probed.add(row.getKey());
        }

        // POSITIVE CONTROLS, after every refusal: the probe reaches a real resource, so a probe
        // that misses for reasons of its own cannot pass as a refusal. The table's order runs
        // the revocation on a spare key first, the reads before the withdrawal of the session
        // they read, and the destination's decisions in the order that leaves each one legal
        // to ask - every answer here is something other than the unknown resource's.
        //
        // And the fingerprint is shown to SEE what a route does: every control that succeeds on
        // a writing route moves B's fingerprint, and no read does - so "unchanged" above is a
        // measurement, not a string that never changes.
        for (Map.Entry<String, Route> row : table.entrySet()) {
            Route route = row.getValue();
            if (route.probe() == null) {
                continue;
            }
            String before = fingerprint(b);
            HttpResponse<String> own = route.probe().send(b, route.resource().apply(b));
            assertThat(own.statusCode())
                    .as("%s, B on its own resource: %s", row.getKey(), own.body())
                    .isNotEqualTo(404)
                    .isLessThan(500);
            boolean wrote = !row.getKey().startsWith("GET ") && own.statusCode() < 300;
            assertThat(!fingerprint(b).equals(before))
                    .as("%s answered %d: the fingerprint %s", row.getKey(), own.statusCode(),
                            wrote ? "must see what it wrote" : "must not move on a read or a refusal")
                    .isEqualTo(wrote);
        }
        assertThat(probed)
                .as("every addressed route was probed, and there are some")
                .hasSize(12);
    }

    @Test
    @DisplayName("INV-MER-01 on the DERIVED routes: A's key reads and writes A's world only, and"
            + " B's world does not move")
    void theDerivedRoutesActOnlyInTheCallersWorld() throws Exception {
        World a = world("100.00", false);
        World b = world("250.00", true);
        String untouched = fingerprint(b);

        String me = get("/v1/merchant/me", a.key()).body();
        String payable = get("/v1/merchant/payable", a.key()).body();
        String today = LocalDate.now(CLOCK).toString();
        String transactions =
                get("/v1/merchant/transactions?from=" + today + "&to=" + today, a.key()).body();
        String disputes = get("/v1/merchant/disputes", a.key()).body();
        HttpResponse<String> opened = openSession(a);

        assertThat(me).contains(a.id());
        assertThat(payable).contains("\"position\":\"100.00\"");
        assertThat(transactions).contains(a.fundingEntry());
        assertThat(disputes).contains(a.dispute());
        for (String body : List.of(me, payable, transactions, disputes)) {
            assertThat(body)
                    .as("nothing of B's reaches A's derived reads")
                    .doesNotContain(b.id())
                    .doesNotContain(b.payable().value().toString())
                    .doesNotContain(b.fundingEntry())
                    .doesNotContain(b.payout())
                    .doesNotContain(b.dispute());
        }
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        assertThat(merchantOfSession(field(opened.body(), "checkoutId")))
                .as("the offer is written under the key's own merchant")
                .isEqualTo(a.id());
        assertThat(fingerprint(b)).as("B's world did not move").isEqualTo(untouched);
    }

    @Test
    @DisplayName("INV-MER-01 at the dispatch: a merchant with no destination of its own is refused,"
            + " never paid out to another merchant's - the destination read's tenant predicate")
    void aPayoutPaysOnlyItsOwnMerchantsDestination() throws Exception {
        World a = world("100.00", false);
        World b = world("250.00", true);
        String before = fingerprint(a) + " / " + fingerprint(b);

        // A is funded and has no effective destination; B has one. Without merchant_id = ? in
        // the dispatch's share-locking read, A's payout would find B's account - or any
        // merchant's - and send A's money there.
        HttpResponse<String> refused = payout(a);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("merchant.NoEffectiveDestination");
        assertThat(fingerprint(a) + " / " + fingerprint(b))
                .as("nothing written in either world")
                .isEqualTo(before);

        // The positive control: the same request on B's key pays B's own destination, from B's
        // own payable - and A's world does not move while it does.
        String untouched = fingerprint(a);
        HttpResponse<String> paid = payout(b);
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(201);
        assertThat(destinationOfPayout(field(paid.body(), "id")))
                .isEqualTo(b.effectiveDestination());
        assertThat(fingerprint(a)).as("B's payout touched nothing of A's").isEqualTo(untouched);
    }

    // ----------------------------------------------------------------- the routes, served

    /** Every merchant route the application serves: the merchant key's, and operator paths naming a merchant. */
    private Map<String, HandlerMethod> merchantRoutes() {
        Map<String, HandlerMethod> routes = new TreeMap<>();
        mappings.getHandlerMethods()
                .forEach(
                        (info, handler) -> {
                            if (!handler.getBeanType().getName().startsWith("com.finapp.")) {
                                return;
                            }
                            boolean merchantKey = declares(handler, RequiresMerchantKey.class);
                            for (String pattern : info.getPatternValues()) {
                                if (!merchantKey && !pattern.contains("/merchants/{")) {
                                    continue;
                                }
                                // A mapping with no method serves every method, and is listed
                                // as ANY so the table cannot match it by accident.
                                if (info.getMethodsCondition().getMethods().isEmpty()) {
                                    routes.put("ANY " + pattern, handler);
                                }
                                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                                    routes.put(method.name() + " " + pattern, handler);
                                }
                            }
                        });
        return routes;
    }

    private static Kind shapeOf(String route, HandlerMethod handler) {
        List<String> variables = new ArrayList<>();
        Matcher variable = PATH_VARIABLE.matcher(route);
        while (variable.find()) {
            variables.add(variable.group(1));
        }
        if (declares(handler, RequiresMerchantKey.class)) {
            return variables.isEmpty() ? Kind.DERIVED : Kind.ID_ADDRESSED;
        }
        return variables.size() == 1 ? Kind.SUBJECT : Kind.PAIRED;
    }

    /** What a DERIVED handler takes that could name somebody's resource - which must be nothing. */
    private static List<String> identifierShapedInputs(HandlerMethod handler) {
        List<String> found = new ArrayList<>();
        for (MethodParameter parameter : handler.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(PathVariable.class)) {
                found.add("a path variable");
            }
            RequestParam parameterAnnotation = parameter.getParameterAnnotation(RequestParam.class);
            if (parameterAnnotation != null) {
                String name =
                        parameterAnnotation.name().isEmpty()
                                ? parameterAnnotation.value()
                                : parameterAnnotation.name();
                if (IDENTIFIER_SHAPED.matcher(name).matches()) {
                    found.add("the request parameter " + name);
                }
            }
            if (parameter.hasParameterAnnotation(RequestBody.class)
                    && parameter.getParameterType().isRecord()) {
                for (RecordComponent component :
                        parameter.getParameterType().getRecordComponents()) {
                    if (IDENTIFIER_SHAPED.matcher(component.getName()).matches()) {
                        found.add("the body field " + component.getName());
                    }
                }
            }
        }
        return found;
    }

    private static boolean declares(HandlerMethod handler, Class<? extends Annotation> annotation) {
        return handler.getMethodAnnotation(annotation) != null
                || handler.getBeanType().getAnnotation(annotation) != null;
    }

    // ----------------------------------------------------------------- worlds

    /**
     * One tenant's world. A's leaves the destination, proposal and payout fields empty: the
     * battery only ever addresses B's resources, and A must have no destination of its own.
     */
    private record World(
            String id,
            String key,
            String spareKeyId,
            LedgerAccountId payable,
            String fundingEntry,
            String session,
            String effectiveDestination,
            String proposedDestination,
            String payout,
            String dispute,
            // P7-TSK-014: one of the dispute's documents, as "disputeId/evidenceId".
            String evidence) {}

    /**
     * A trading merchant with a funded payable, two API keys, the platform's pricing and an open
     * offer - and, when {@code full}, an effective destination, a proposed change and a payout.
     */
    private World world(String funds, boolean full) throws Exception {
        UUID id = IDS.next();
        raw(
                "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                        + " settlement_currency, status, created_at, status_changed_at) VALUES"
                        + " (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', now(), now())",
                id,
                UUID.randomUUID());
        LedgerAccountId payable =
                asOperator(
                        uow ->
                                ledgerAccountStore
                                        .createOrConverge(
                                                uow,
                                                LedgerAccount.owned(
                                                        IDS, CLOCK, AccountType.LIABILITY,
                                                        AccountPurpose.MERCHANT_PAYABLE, EUR, id))
                                        .account()
                                        .id());
        String fundingEntry =
                asOperator(
                        uow -> {
                            LedgerAccount clearing =
                                    new ChartOfAccounts<>(ledgerAccountStore)
                                            .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, EUR);
                            LocalDate today = LocalDate.now(CLOCK);
                            UUID reference = UUID.randomUUID();
                            Money amount = Money.of(new BigDecimal(funds), EUR);
                            return postings.post(
                                            uow,
                                            new PostingCommand(
                                                    "tenancy-battery-fixture:" + reference,
                                                    today,
                                                    today,
                                                    reference.toString(),
                                                    List.of(
                                                            new JournalLine(
                                                                    clearing.id(),
                                                                    Direction.DEBIT,
                                                                    amount),
                                                            new JournalLine(
                                                                    payable,
                                                                    Direction.CREDIT,
                                                                    amount))))
                                    .entryId()
                                    .value()
                                    .toString();
                        });
        String key = issueKey(id).token();
        String spareKeyId = issueKey(id).keyId();
        HttpResponse<String> assigned =
                put(
                        "/v1/operator/merchants/" + id + "/fee-schedule",
                        "{\"feeScheduleId\":\"" + pricing() + "\",\"reason\":\"standard terms\"}",
                        operator());
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);

        World partial =
                new World(id.toString(), key, spareKeyId, payable, fundingEntry, null, null, null,
                        null, null, null);
        HttpResponse<String> opened = openSession(partial);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String session = field(opened.body(), "checkoutId");
        String dispute = disputeOn(payable);
        // A document on the dispute, attached through the merchant's own route (P7-TSK-014):
        // the content is encrypted under the dispute key, which only the application holds.
        HttpResponse<String> attached =
                post("/v1/merchant/disputes/" + dispute + "/evidence", evidenceBody(), key, null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        String evidence = dispute + "/" + field(attached.body(), "evidenceId");
        if (!full) {
            return new World(
                    id.toString(), key, spareKeyId, payable, fundingEntry, session, null, null,
                    null, dispute, evidence);
        }

        UUID effective = IDS.next();
        raw(
                "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " approved_by, approved_at, cooling_off_until, effective_at) VALUES"
                        + " (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() - interval '4 days',"
                        + " 'fixture', 'fixture-b', now() - interval '4 days', now() - interval"
                        + " '1 day', now() - interval '1 hour')",
                effective,
                id,
                reference());
        UUID proposed = IDS.next();
        // Stamped from the test's clock, not the database's now(): the positive controls
        // approve and withdraw this proposal through the domain, which stamps from the JVM's
        // clock and refuses a decision preceding its proposal - a database clock running
        // ahead made that control a 500 (X-TSK-005; P7-TSK-014's merchant fixture, again).
        OffsetDateTime proposedAt =
                OffsetDateTime.ofInstant(
                        Instant.now(CLOCK).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        raw(
                "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason)"
                        + " VALUES (?, ?, ?, '4000', 'PROPOSED', 'fixture-a', ?, 'fixture')",
                proposed,
                id,
                reference(),
                proposedAt);
        World funded =
                new World(
                        id.toString(), key, spareKeyId, payable, fundingEntry, session,
                        effective.toString(), proposed.toString(), null, dispute, evidence);
        HttpResponse<String> paid = payout(funded);
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(201);
        return new World(
                id.toString(), key, spareKeyId, payable, fundingEntry, session,
                effective.toString(), proposed.toString(), field(paid.body(), "id"), dispute,
                evidence);
    }

    /**
     * A chargeback on a sale that credited {@code payable} (`P7-TSK-012`): a captured card
     * payment and its dispute, seeded raw as the application role — the rows the notification
     * would write, whose tenant is the payment's credit account.
     */
    private static String disputeOn(LedgerAccountId payable) throws SQLException {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        UUID dispute = IDS.next();
        raw(
                "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                        + " payment_method_id, credit_account_id, amount_minor, currency, scale,"
                        + " status, created_at, capture_mode) VALUES (?, ?, ?, ?, ?, 1000, 'EUR',"
                        + " 2, 'SUCCEEDED', now(), 'AUTOMATIC')",
                intent, IDS.next(), IDS.next(), IDS.next(), payable.value());
        raw(
                "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                        + " capture_reference, auth_provider_reference, capture_provider_reference,"
                        + " authorized_amount_minor, authorized_currency, authorized_scale,"
                        + " captured_amount_minor, captured_currency, captured_scale, status,"
                        + " created_at, rail, interaction_model) VALUES (?, ?, ?, ?, ?, ?, 1000,"
                        + " 'EUR', 2, 1000, 'EUR', 2, 'CAPTURED', now(), 'card', 'TWO_STEP')",
                attempt, intent, "auth-" + IDS.next(), "cap-" + IDS.next(),
                "psp-auth-" + IDS.next(), "psp-cap-" + IDS.next());
        // The chargeback arrives with its attribution (V021, P7-TSK-013) - here the whole
        // amount charged to the payable, which is the tenant fact the reads scope by.
        raw(
                "INSERT INTO payments.dispute (id, provider, provider_dispute_reference,"
                        + " attempt_id, reason, stage, chargeback_amount_minor,"
                        + " chargeback_currency, chargeback_scale,"
                        + " counterparty_share_amount_minor, counterparty_share_currency,"
                        + " counterparty_share_scale, parked_share_amount_minor,"
                        + " parked_share_currency, parked_share_scale, opened_at)"
                        + " VALUES (?, 'simulated-card', ?, ?, 'FRAUD', 'CHARGED_BACK', 1000,"
                        + " 'EUR', 2, 1000, 'EUR', 2, 0, 'EUR', 2, now())",
                dispute, "dp_" + IDS.next().toString().replace("-", ""), attempt);
        return dispute.toString();
    }

    /** A small PDF-labelled document, distinct per call - a new upload, never a convergence. */
    private static String evidenceBody() {
        String content =
                java.util.Base64.getEncoder()
                        .encodeToString(
                                ("tenancy battery evidence " + UUID.randomUUID())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "{\"kind\":\"RECEIPT\",\"contentType\":\"PDF\",\"content\":\""
                + content
                + "\"}";
    }

    /**
     * Everything of one tenant's a merchant route can write, as one string: the merchant's
     * standing, keys, destinations, payouts and sessions with their statuses, every history row,
     * the payable's lines and holds, and the audit and outbox rows naming any of it.
     */
    private static String fingerprint(World world) throws SQLException {
        String sql =
                "WITH mine(id) AS ("
                        + " SELECT CAST(? AS uuid)"
                        + " UNION ALL SELECT id FROM merchant.merchant_api_key WHERE merchant_id = ?"
                        + " UNION ALL SELECT id FROM merchant.payout_destination WHERE merchant_id = ?"
                        + " UNION ALL SELECT id FROM merchant.merchant_payout WHERE merchant_id = ?"
                        + " UNION ALL SELECT id FROM checkout.checkout_session WHERE merchant_ref = ?"
                        // P7-TSK-014: the disputes on the merchant's sales and their answers, so
                        // an upload's and an answer's audit and outbox rows are the merchant's.
                        // Never the documents' own ids: an evidence READ writes its audit row
                        // against the document, and a read must not move the fingerprint.
                        + " UNION ALL SELECT d.id FROM payments.dispute d"
                        + "   JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                        + "   JOIN payments.payment_intent i ON i.id = a.intent_id"
                        + "   WHERE i.credit_account_id = ?"
                        + " UNION ALL SELECT r.id FROM payments.dispute_response r"
                        + "   JOIN payments.dispute d ON d.id = r.dispute_id"
                        + "   JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                        + "   JOIN payments.payment_intent i ON i.id = a.intent_id"
                        + "   WHERE i.credit_account_id = ?)"
                        + " SELECT concat_ws('|',"
                        + " (SELECT status FROM merchant.merchant WHERE id = ?),"
                        + " (SELECT count(*) FROM merchant.merchant_event WHERE merchant_id = ?),"
                        + " (SELECT string_agg(id::text || ':' || status, ',' ORDER BY id)"
                        + "    FROM merchant.merchant_api_key WHERE merchant_id = ?),"
                        + " (SELECT count(*) FROM merchant.merchant_api_key_event e"
                        + "    JOIN merchant.merchant_api_key k ON k.id = e.key_id"
                        + "    WHERE k.merchant_id = ?),"
                        + " (SELECT string_agg(id::text || ':' || status, ',' ORDER BY id)"
                        + "    FROM merchant.payout_destination WHERE merchant_id = ?),"
                        + " (SELECT count(*) FROM merchant.payout_destination_event e"
                        + "    JOIN merchant.payout_destination d ON d.id = e.payout_destination_id"
                        + "    WHERE d.merchant_id = ?),"
                        + " (SELECT string_agg(id::text || ':' || status, ',' ORDER BY id)"
                        + "    FROM merchant.merchant_payout WHERE merchant_id = ?),"
                        + " (SELECT count(*) FROM merchant.merchant_payout_event e"
                        + "    JOIN merchant.merchant_payout p ON p.id = e.payout_id"
                        + "    WHERE p.merchant_id = ?),"
                        + " (SELECT string_agg(id::text || ':' || status, ',' ORDER BY id)"
                        + "    FROM checkout.checkout_session WHERE merchant_ref = ?),"
                        + " (SELECT count(*) FROM checkout.checkout_session_event e"
                        + "    JOIN checkout.checkout_session s ON s.id = e.session_id"
                        + "    WHERE s.merchant_ref = ?),"
                        + " (SELECT count(*) FROM ledger.journal_line WHERE ledger_account_id = ?),"
                        + " (SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?),"
                        + " (SELECT count(*) FROM payments.dispute_evidence e"
                        + "    JOIN payments.dispute d ON d.id = e.dispute_id"
                        + "    JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                        + "    JOIN payments.payment_intent i ON i.id = a.intent_id"
                        + "    WHERE i.credit_account_id = ?),"
                        + " (SELECT string_agg(r.id::text || ':' || r.status, ',' ORDER BY r.id)"
                        + "    FROM payments.dispute_response r"
                        + "    JOIN payments.dispute d ON d.id = r.dispute_id"
                        + "    JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                        + "    JOIN payments.payment_intent i ON i.id = a.intent_id"
                        + "    WHERE i.credit_account_id = ?),"
                        + " (SELECT count(*) FROM platform.audit_record"
                        + "    WHERE target_id IN (SELECT id::text FROM mine)),"
                        + " (SELECT count(*) FROM platform.outbox_event"
                        + "    WHERE aggregate_id IN (SELECT id FROM mine)))";
        UUID merchant = UUID.fromString(world.id());
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            // In statement order: five merchant ids and two payables in the CTE, ten merchant
            // ids, then four payables (lines, holds, documents, answers).
            int parameter = 1;
            for (int i = 0; i < 5; i++) {
                read.setObject(parameter++, merchant);
            }
            read.setObject(parameter++, world.payable().value());
            read.setObject(parameter++, world.payable().value());
            for (int i = 0; i < 10; i++) {
                read.setObject(parameter++, merchant);
            }
            for (int i = 0; i < 4; i++) {
                read.setObject(parameter++, world.payable().value());
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** The platform's pricing for this test, created once: 2.9% + 0.30, which 10.00 covers. */
    private String pricing() throws Exception {
        if (schedule == null) {
            HttpResponse<String> created =
                    post(
                            "/v1/operator/fee-schedules",
                            "{\"name\":\"Tenancy " + UUID.randomUUID()
                                    + "\",\"currency\":\"EUR\"}",
                            operator(),
                            null);
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            schedule = field(created.body(), "feeScheduleId");
            HttpResponse<String> version =
                    post(
                            "/v1/operator/fee-schedules/" + schedule + "/versions",
                            "{\"rate\":0.029,\"fixedAmountMinor\":30,\"roundingPolicy\":"
                                    + "\"HALF_EVEN\",\"refundFeePolicy\":\"RETAINED\","
                                    + "\"reason\":\"initial pricing\"}",
                            operator(),
                            null);
            assertThat(version.statusCode()).as(version.body()).isEqualTo(201);
        }
        return schedule;
    }

    /** An API key: the token a request presents, and the key id an operator's path names. */
    private record IssuedKey(String keyId, String token) {}

    private IssuedKey issueKey(UUID merchant) throws Exception {
        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + merchant + "/api-keys", null, operator(), someKey());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        String keyId = field(issued.body(), "keyId");
        return new IssuedKey(keyId, keyId + "." + field(issued.body(), "secret"));
    }

    private HttpResponse<String> openSession(World world) throws Exception {
        return post(
                "/v1/checkout/sessions",
                "{\"amountMinor\":1000,\"currency\":\"EUR\",\"lineSummary\":\"A pot of tea\"}",
                world.key(),
                someKey());
    }

    private HttpResponse<String> payout(World world) throws Exception {
        return post(
                "/v1/merchant/payouts",
                "{\"amount\":\"10.00\",\"currency\":\"EUR\"}",
                world.key(),
                someKey());
    }

    private static String reference() {
        return "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String merchantOfSession(String session) throws SQLException {
        return one(
                "SELECT merchant_ref FROM checkout.checkout_session WHERE id = ?",
                UUID.fromString(session));
    }

    private static String destinationOfPayout(String payout) throws SQLException {
        return one(
                "SELECT destination_id FROM merchant.merchant_payout WHERE id = ?",
                UUID.fromString(payout));
    }

    // ----------------------------------------------------------------- operators

    /** A signed-in MERCHANT_ADMINISTRATOR, created once per test. */
    private String operator() throws Exception {
        if (operator == null) {
            String login = registered();
            UUID identity;
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement read =
                            app.prepareStatement(
                                    "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
                read.setString(1, login);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    identity = row.getObject("id", UUID.class);
                }
            }
            try (CorrelationContext.Scope flow =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(IDS)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                authorization.assign(
                        app,
                        IdentityId.of(identity),
                        RoleName.MERCHANT_ADMINISTRATOR,
                        IdentityId.of(identity),
                        "test fixture");
                app.commit();
            }
            HttpResponse<String> session =
                    post(
                            "/v1/authentications",
                            "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD
                                    + "\"}",
                            null,
                            someKey());
            operator = field(session.body(), "sessionToken");
        }
        return operator;
    }

    private String registered() throws Exception {
        String login = "tenancy." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration =
                post(
                        "/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null,
                        someKey());
        assertThat(registration.statusCode()).isEqualTo(201);
        return login;
    }

    private <R> R asOperator(Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    // ----------------------------------------------------------------- HTTP

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build());
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        return send(request(path, token, key, "POST", body));
    }

    private HttpResponse<String> put(String path, String body, String token) throws Exception {
        return send(request(path, token, null, "PUT", body));
    }

    private HttpResponse<String> delete(String path, String body, String token) throws Exception {
        return send(request(path, token, null, "DELETE", body));
    }

    private HttpRequest request(String path, String token, String key, String method, String body) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return request.build();
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** The per-request fields: the correlation id differs by design, and instance echoes the path. */
    private static String normalised(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"n\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"n\"");
    }

    private static String field(String body, String name) {
        Matcher found = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(found.find()).as("%s in %s", name, body).isTrue();
        return found.group(1);
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    // ----------------------------------------------------------------- SQL

    private static void raw(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String one(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }
}
