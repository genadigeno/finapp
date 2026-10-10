package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.BALANCES;
import static com.finapp.app.credit.CreditWorld.TRANSACTIONS;
import static com.finapp.app.credit.CreditWorld.count;
import static com.finapp.app.credit.CreditWorld.decide;
import static com.finapp.app.credit.CreditWorld.deciding;
import static com.finapp.app.credit.CreditWorld.eur;
import static com.finapp.app.credit.CreditWorld.evaluated;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionRequestStatus;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyFigure;
import com.finapp.credit.PolicyOperator;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.TransactionRunner;
import com.finapp.credit.UnavailableFallback;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 10's operations reports against a real database (`P10-TSK-020`; PHASE_10_PLAN.md sections 9 and 15;
 * {@code INV-AUD-01}, {@code INV-CRD-02}): a month's outcomes by pinned policy version with its referrals, its reason-code
 * distribution and its sources' availability - each equal to what the rows say, read by a holder of
 * {@code CREDIT_INVESTIGATE} in ONE snapshot and audited {@code credit.ReportRead} at every serving; a bad month 422 before
 * any read and unaudited; an underwriter and an anonymous caller refused; never an amount in a body. And the open-age
 * gauge reading the database's oldest open request per state.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings its own policies into force, and
 * its reports count every decision of the month in its database.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("Phase 10's operations reports (P10-TSK-020)")
class Phase10ReportsDatabaseTest {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final String REPORTS = "/v1/operator/reports/credit/";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static boolean policies;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private MeterRegistry meters;

    private CreditTestClient client;

    @BeforeEach
    void world() {
        client = new CreditTestClient(port);
        if (!policies) {
            CreditWorld.seedsInForce();
            CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
            referringLoan();
            policies = true;
        }
    }

    @Test
    @DisplayName("the three reports equal what the month's rows say - the platform's approvals and decline by pinned"
            + " version, the referral, the reasons, the sources - each audited at every serving, never an amount")
    void theReportsCountTheMonthAndAreAudited() throws Exception {
        List<UUID> ours = new ArrayList<>();
        ours.add(systemDecided(UUID.randomUUID()));
        ours.add(systemDecided(UUID.randomUUID()));
        UUID declinedParty = UUID.randomUUID();
        BALANCES.put(declinedParty, 3_000_000L);
        ours.add(systemDecided(declinedParty));
        UUID referred = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(500_000), WEEK);
        assertThat(decide(deciding(), referred)).isEqualTo(Decider.Decided.REFERRED);
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        // The month is the database's - the month our decisions were stamped in on its clock - never the JVM's: the
        // reports bucket decided_at, which the database stamps.
        List<String> months = new ArrayList<>();
        for (UUID request : ours) {
            months.add(CreditWorld.scalar("SELECT to_char(decided_at AT TIME ZONE 'UTC', 'YYYY-MM') FROM"
                    + " credit.credit_decision WHERE decision_request_id = ?", request));
        }
        months.add(CreditWorld.scalar("SELECT to_char(c.opened_at AT TIME ZONE 'UTC', 'YYYY-MM') FROM"
                + " credit.underwriting_case c WHERE c.decision_request_id = ?", referred));
        assertThat(months.stream().distinct()).as("the case's decisions and referral fell in one month on the database's"
                + " clock").hasSize(1);
        String month = months.get(0);
        // The month's bounds as UTC instants, whatever the session's time zone.
        String[] range = {month + "-01T00:00:00Z", YearMonth.parse(month).plusMonths(1) + "-01T00:00:00Z"};
        long audits = reportReads();

        JsonNode outcomes = read("outcomes?month=" + month, officer);
        assertThat(outcomes.path("month").asString()).isEqualTo(month);
        assertThat(outcomes.path("decisions").asLong()).isEqualTo(count("SELECT count(*) FROM credit.credit_decision"
                + " WHERE decided_at >= ?::timestamptz AND decided_at < ?::timestamptz", range[0], range[1]));
        long sum = 0;
        for (JsonNode row : outcomes.path("rows")) {
            sum += row.path("decisions").asLong();
            assertThat(row.path("approved").asLong() + row.path("declined").asLong()).isEqualTo(row.path("decisions").asLong());
            assertThat(row.path("decidedBy").asString()).isIn("system", "person");
        }
        assertThat(sum).as("the rows sum to the total").isEqualTo(outcomes.path("decisions").asLong());
        int lineVersion = Integer.parseInt(CreditWorld.scalar("SELECT max(version)::text FROM credit.credit_policy_version"
                + " WHERE product = 'CREDIT_LINE' AND status = 'ACTIVE'"));
        JsonNode line = row(outcomes.path("rows"), Map.of("product", "credit_line", "decidedBy", "system"),
                lineVersion);
        // Exactly what the month's rows say for the line's platform decisions under this version (another case of the
        // suite may have added its own), and at least ours: two approvals and the decline.
        String lineRows = "SELECT count(*) FROM credit.credit_decision d JOIN credit.credit_policy_version p"
                + " ON p.id = d.policy_version_id WHERE d.product = 'CREDIT_LINE' AND d.decided_by_type = 'SYSTEM'"
                + " AND p.version = ? AND d.outcome = ? AND d.decided_at >= ?::timestamptz AND d.decided_at < ?::timestamptz";
        long declinedRows = count(lineRows, lineVersion, "DECLINED", range[0], range[1]);
        long approvedRows = count(lineRows, lineVersion, "APPROVED", range[0], range[1]);
        assertThat(line.path("declined").asLong()).isEqualTo(declinedRows);
        assertThat(line.path("approved").asLong()).isEqualTo(approvedRows);
        assertThat(line.path("decisions").asLong()).isEqualTo(declinedRows + approvedRows);
        assertThat(declinedRows).as("our decline among them").isGreaterThanOrEqualTo(1);
        assertThat(approvedRows).as("our two approvals among them").isGreaterThanOrEqualTo(2);
        assertThat(line.path("approvalRate").asString())
                .isEqualTo(Phase10Reports.rate(line.path("approved").asLong(), line.path("decisions").asLong()));
        assertThat(outcomes.path("referrals")).anySatisfy(referral -> {
            assertThat(referral.path("product").asString()).isEqualTo("personal_loan");
            assertThat(referral.path("referred").asLong()).isEqualTo(count("SELECT count(*) FROM credit.underwriting_case"
                    + " c JOIN credit.decision_request r ON r.id = c.decision_request_id"
                    + " WHERE c.product = 'PERSONAL_LOAN' AND r.pinned_policy_version_id = (SELECT id FROM"
                    + " credit.credit_policy_version WHERE product = 'PERSONAL_LOAN' AND version = ?)"
                    + " AND c.opened_at >= ?::timestamptz AND c.opened_at < ?::timestamptz",
                    referral.path("policyVersion").asInt(), range[0], range[1]));
        });

        JsonNode reasons = read("reasons?month=" + month, officer);
        assertThat(reasons.path("decisions").asLong()).isEqualTo(outcomes.path("decisions").asLong());
        long carried = 0;
        for (JsonNode row : reasons.path("rows")) {
            carried += row.path("decisions").asLong();
            assertThat(row.path("asPrimary").asLong()).isBetween(0L, row.path("decisions").asLong());
            assertThat(ReasonCode.values()).extracting(ReasonCode::code).contains(row.path("reasonCode").asString());
        }
        assertThat(carried).as("every reason the month's decisions carry").isEqualTo(count(
                "SELECT count(*) FROM credit.credit_decision_reason r JOIN credit.credit_decision d ON d.id = r.decision_id"
                        + " WHERE d.decided_at >= ?::timestamptz AND d.decided_at < ?::timestamptz", range[0], range[1]));
        assertThat(reasons.path("rows")).anySatisfy(row -> {
            assertThat(row.path("product").asString()).isEqualTo("credit_line");
            assertThat(row.path("asPrimary").asLong()).isPositive();
        });

        JsonNode sources = read("sources", officer);
        assertThat(sources.path("month").asString()).as("the current month when absent").isEqualTo(month);
        assertThat(sources.path("dataRequests").asLong()).isPositive().isEqualTo(count("SELECT count(*) FROM"
                + " credit.data_request WHERE requested_at >= ?::timestamptz AND requested_at < ?::timestamptz",
                range[0], range[1]));
        long requests = 0;
        for (JsonNode row : sources.path("rows")) {
            requests += row.path("dataRequests").asLong();
            assertThat(row.path("received").asLong() + row.path("unavailable").asLong()
                    + row.path("consentWithdrawn").asLong() + row.path("awaiting").asLong())
                    .isEqualTo(row.path("dataRequests").asLong());
            assertThat(row.path("sourceKind").asString()).isIn("bureau", "financial_data");
        }
        assertThat(requests).isEqualTo(sources.path("dataRequests").asLong());
        assertThat(sources.path("rows")).anySatisfy(row -> {
            assertThat(row.path("sourceKind").asString()).isEqualTo("bureau");
            assertThat(row.path("availability").asString()).isEqualTo(
                    Phase10Reports.rate(row.path("received").asLong(),
                            row.path("received").asLong() + row.path("unavailable").asLong()));
        });

        assertThat(reportReads()).as("one record per serving").isEqualTo(audits + 3);
        assertThat(CreditWorld.scalar("SELECT change_summary FROM platform.audit_record WHERE operation ="
                + " 'credit.ReportRead' AND target_id = 'outcomes' ORDER BY occurred_at DESC LIMIT 1"))
                .startsWith("report=outcomes, month=" + month + ", rows=");
        for (JsonNode body : List.of(outcomes, reasons, sources)) {
            assertThat(body.toString()).as("counts and rates, never an amount or a party")
                    .doesNotContain("2000.00").doesNotContain("200000").doesNotContain("5000.00")
                    .doesNotContain(declinedParty.toString());
        }
    }

    @Test
    @DisplayName("a bad month is 422 before any read and writes no record; the current month is the default")
    void aBadMonthIs422BeforeAnyRead() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        long audits = reportReads();
        String next = YearMonth.now(ZoneOffset.UTC).plusMonths(1).toString();
        for (String report : List.of("outcomes", "reasons", "sources")) {
            for (String month : List.of("2026-13", "2025-12", next, "october", "2026-1")) {
                HttpResponse<String> refused = client.get(REPORTS + report + "?month=" + month, officer);
                assertThat(refused.statusCode()).as("%s?month=%s: %s", report, month, refused.body()).isEqualTo(422);
                assertThat(refused.body()).contains("api.ValidationFailed");
            }
        }
        assertThat(reportReads()).as("no refused period is recorded").isEqualTo(audits);
    }

    @Test
    @DisplayName("the reports are CREDIT_INVESTIGATE's: an underwriter and a customer are refused 403, no session 401,"
            + " none recorded")
    void onlyAnInvestigatorReads() throws Exception {
        String underwriter = sessionWith(RoleName.UNDERWRITER);
        String customer = client.customer(true).token();
        long audits = reportReads();
        for (String report : List.of("outcomes", "reasons", "sources")) {
            assertThat(client.get(REPORTS + report, underwriter).statusCode()).isEqualTo(403);
            assertThat(client.get(REPORTS + report, customer).statusCode()).as("a customer's token").isEqualTo(403);
            assertThat(client.get(REPORTS + report, null).statusCode()).isEqualTo(401);
        }
        assertThat(reportReads()).isEqualTo(audits);
    }

    @Test
    @DisplayName("each report reads one snapshot: a decision committed between its rows and its total is in neither")
    void eachReportReadsOneSnapshot() {
        UUID pending = evaluated(UUID.randomUUID(), CreditProduct.CREDIT_LINE, eur(200_000), WEEK);
        long before = count("SELECT count(*) FROM credit.credit_decision");
        Phase10Reports reports = new Phase10Reports(committingBetweenReads(
                () -> assertThat(decide(deciding(), pending)).isEqualTo(Decider.Decided.DECIDED)),
                new JdbcAuditWriter(), CreditWorld.IDS, CreditWorld.CLOCK);
        Phase10Reports.OutcomesReport report;
        try (SecurityContext.Scope actor = SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(CreditWorld.IDS)))) {
            report = reports.outcomes(null);
        }
        assertThat(count("SELECT count(*) FROM credit.credit_decision")).as("the decision did commit mid-read")
                .isEqualTo(before + 1);
        assertThat(report.rows().stream().mapToLong(Phase10Reports.OutcomeRow::decisions).sum())
                .as("the rows and the total read one snapshot").isEqualTo(report.decisions());
        assertThat(report.decisions()).as("the snapshot predates the decision").isEqualTo(before);
    }

    @Test
    @DisplayName("the open-age gauge reads the database's oldest open request per state - never NaN against a live"
            + " database - and the store answers only open states")
    void theOpenAgeGaugeReadsTheOldestOpenRequests() {
        CreditWorld.request(UUID.randomUUID(), CreditProduct.CREDIT_LINE, eur(200_000), WEEK);
        Map<DecisionRequestStatus, Duration> ages = TRANSACTIONS.inTransaction(CreditWorld.REQUESTS::oldestOpenAges);
        assertThat(ages).containsKey(DecisionRequestStatus.SUBMITTED);
        assertThat(DecisionRequestStatus.OPEN).containsAll(ages.keySet());
        assertThat(ages.values()).allSatisfy(age -> assertThat(age.isNegative()).isFalse());
        double submitted = meters.get(com.finapp.app.telemetry.CreditRequestAgeMetrics.AGE).tag("status", "submitted")
                .gauge().value();
        assertThat(submitted).as("a live database reads a number").isNotNaN().isGreaterThanOrEqualTo(0.0);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The world's transactions, each connection running {@code between} once, committed elsewhere, just before its SECOND
     * prepared statement - between a report's rows and its total.
     */
    private static TransactionRunner committingBetweenReads(Runnable between) {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return TRANSACTIONS.inTransaction(uow -> work.apply(hooked(uow, between)));
            }
        };
    }

    private static Connection hooked(Connection real, Runnable between) {
        AtomicInteger prepared = new AtomicInteger();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && prepared.incrementAndGet() == 2) {
                        between.run();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private JsonNode read(String path, String bearer) throws Exception {
        HttpResponse<String> response = client.get(REPORTS + path, bearer);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static JsonNode row(JsonNode rows, Map<String, String> fields, int policyVersion) {
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode row : rows) {
            if (row.path("policyVersion").asInt() == policyVersion
                    && fields.entrySet().stream().allMatch(field -> field.getValue().equals(row.path(field.getKey()).asString()))) {
                matching.add(row);
            }
        }
        assertThat(matching).as("one row for %s v%s in %s", fields, policyVersion, rows).hasSize(1);
        return matching.get(0);
    }

    private static long reportReads() {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReportRead'");
    }

    /** A line request decided by the platform - its id. */
    private static UUID systemDecided(UUID party) {
        Money amount = eur(200_000);
        UUID request = evaluated(party, CreditProduct.CREDIT_LINE, amount, WEEK);
        assertThat(decide(deciding(), request)).isEqualTo(Decider.Decided.DECIDED);
        return request;
    }

    /** PERSONAL_LOAN v1 without its score rules, plus a rule that refers every loan to a person. */
    private static void referringLoan() {
        TRANSACTIONS.inTransaction(uow -> {
            CreditPolicy v1 = CreditWorld.POLICIES.policy(uow, CreditWorld.LOAN_SEED).orElseThrow().policy();
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
            for (CreditPolicy.PolicyRule rule : v1.rules()) {
                if (!(rule.subject() instanceof CreditPolicy.Subject.Figure figure && figure.figure() == PolicyFigure.SCORE)) {
                    rules.add(rule);
                }
            }
            rules.add(new CreditPolicy.PolicyRule("REPORT_REFERRAL",
                    new CreditPolicy.Subject.Attribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE), PolicyOperator.GE,
                    new CreditPolicy.Operand.IntegerOperand(0), PolicyEffect.REFER, Optional.empty(),
                    ReasonCode.RISK_REFERRAL));
            CreditPolicy policy = new CreditPolicy(v1.product(), v1.assessmentRateBps(), v1.minimumDisposable(),
                    v1.minimumPaymentRatioBps(), v1.maximumExposure(), v1.maximumDataAge(), UnavailableFallback.REFER,
                    v1.autoApprovalCeiling(), rules);
            CreditPolicyAdministration.Proposed proposed = CreditWorld.POLICY_ADMINISTRATION.propose(uow, policy,
                    "the reports suite's referring loan", new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                    CorrelationId.generate(CreditWorld.IDS));
            CreditWorld.POLICY_ADMINISTRATION.approve(uow, proposed.id(),
                    new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), "activated",
                    CorrelationId.generate(CreditWorld.IDS));
            return null;
        });
    }

    /** A registered identity holding {@code role}, and its session. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "rp." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null, CreditTestClient.key()).statusCode())
                .isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(CreditWorld.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client.post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null,
                CreditTestClient.key());
        return CreditTestClient.field(session.body(), "sessionToken");
    }
}
