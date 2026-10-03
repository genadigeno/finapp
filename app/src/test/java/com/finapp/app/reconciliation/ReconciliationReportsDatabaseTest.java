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
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.BreakRegister;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.InternalClassification;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.RuleSets;
import com.finapp.reconciliation.Suspense;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four remaining operator reports over real HTTP and the REAL composition (`P8-TSK-024`,
 * ADR-0072 §2, §3 and §6): their doors (anonymous 401, role-less and controller 403 — the
 * reports are the investigator's), their period gates (the chargeback-ratio report's 422,
 * refused before anything is read and nothing audited), and one real day read through every
 * report — three PSP reports accepted and matched (a capture matched with a fee beyond its
 * pinned schedule, a capture nothing expects waiting under grace, a capture parked with its
 * break into suspense), then the waiting report repudiated four-eyes — each report's figures
 * asserted as DELTAS over a read taken before the day, because the shared container carries
 * other suites' rows. Every serving is audited once, naming the report, the period and the row
 * count, never an amount.
 *
 * <p>Every flow keeps its money balanced (the repudiation suite's discipline): the matched
 * capture is seeded with its posting and its {@code CARD_CAPTURE} expectation; the waiting and
 * parked captures are balanced by their reports' own {@code REMITTANCE} expectations and the
 * park's own posting; the repudiation reverses its recognition. EUR only, and no bank
 * statement: statements are {@code BankStatementCashDatabaseTest}'s alone in the shared
 * container (its chain discipline), so the bank-fee leg of provider costs, the 101-row bound
 * and the OUTBOUND half of the direction split are proven in {@code
 * ReconciliationReportsFoldTest}, hermetically.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the reconciliation operator reports: suspense, unmatched, summary and provider"
        + " costs (P8-TSK-024)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class ReconciliationReportsDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PSP_SOURCE = "simulated-psp.settlement";
    private static final String BASE = "/v1/operator/reconciliation";
    private static final String REPORTS = "/v1/operator/reports/reconciliation";
    private static final List<String> ROUTES =
            List.of("/suspense", "/unmatched", "/summary", "/provider-costs");
    private static final String READ = "reconciliation.ReportRead";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private RuleSets ruleSets;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private BreakRegister breakRegister;
    @Autowired private Suspense suspense;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // ----------------------------------------------------------------- 1. the doors

    @Test
    @Order(1)
    @DisplayName("every report holds its permission: anonymous 401, a role-less session 403,"
            + " the controller 403 (the investigator reads) - and a refused read writes nothing")
    void theDoorsHold() throws Exception {
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session roleless = rolelessSession();
        long served = count("SELECT count(*) FROM platform.audit_record WHERE operation = ?",
                READ);

        for (String route : ROUTES) {
            assertThat(get(null, REPORTS + route).statusCode()).as("anonymous %s", route)
                    .isEqualTo(401);
            assertThat(get(roleless.token(), REPORTS + route).statusCode())
                    .as("role-less %s", route)
                    .isEqualTo(403);
            assertThat(get(controller.token(), REPORTS + route).statusCode())
                    .as("the controller sets tolerances; the investigator reads %s", route)
                    .isEqualTo(403);
        }

        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ?",
                        READ))
                .as("a refused read serves nothing, so nothing is on the record")
                .isEqualTo(served);
    }

    // ----------------------------------------------------------------- 2. the period gates

    @Test
    @Order(2)
    @DisplayName("a period no record can carry is the 422 before anything is read - before"
            + " 2000, in the future, unreal or malformed - and nothing is audited; the earliest"
            + " periods are served and audited with their period")
    void invalidPeriodsAreRefusedAtTheDoor() throws Exception {
        Session reader = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
        List<String> refused =
                List.of(
                        "/summary?date=1999-12-31",
                        "/summary?date=" + today.plusDays(1),
                        "/summary?date=2026-02-30",
                        "/summary?date=2026-1-01",
                        "/summary?date=yesterday",
                        "/provider-costs?month=1999-12",
                        "/provider-costs?month=" + thisMonth.plusMonths(1),
                        "/provider-costs?month=2026-13",
                        "/provider-costs?month=2026-1",
                        "/provider-costs?month=last");
        for (String path : refused) {
            HttpResponse<String> answer = get(reader.token(), REPORTS + path);
            assertThat(answer.statusCode()).as("%s: %s", path, answer.body()).isEqualTo(422);
            assertThat(answer.body()).as(path).contains("api.ValidationFailed");
        }
        assertThat(audits(reader)).as("refused at the door: nothing read, nothing audited")
                .isZero();

        assertThat(get(reader.token(), REPORTS + "/summary?date=2000-01-01").statusCode())
                .isEqualTo(200);
        assertThat(get(reader.token(), REPORTS + "/provider-costs?month=2000-01").statusCode())
                .isEqualTo(200);
        assertThat(get(reader.token(), REPORTS + "/summary?date=" + today).statusCode())
                .isEqualTo(200);
        assertThat(summaries(reader))
                .as("each serving names its report and period, never a figure")
                .containsExactlyInAnyOrder(
                        "summary|summary|2000-01-01",
                        "provider-costs|provider-costs|2000-01",
                        "summary|summary|" + today);
        assertAuditsCarryNoAmount(reader);
    }

    // ----------------------------------------------------------------- 3. a real day

    @Test
    @Order(3)
    @DisplayName("a real day through every report: a matched capture with a fee beyond its"
            + " schedule, a waiting capture later repudiated, a parked capture in suspense -"
            + " every figure a delta over the day before, per currency and direction, and each"
            + " serving audited once without an amount")
    void aRealDayReadsThroughEveryReport() throws Exception {
        Session reader = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String month = YearMonth.now(ZoneOffset.UTC).toString();
        // The shared container may hold other suites' files and runs the legs never swept:
        // drained FIRST, so the day's sweeps below move nothing but this suite's own evidence
        // and every delta is this day's alone.
        drain();

        JsonNode suspenseBefore = report(reader, "/suspense");
        JsonNode unmatchedBefore = report(reader, "/unmatched");
        JsonNode summaryBefore = report(reader, "/summary?date=" + today);
        JsonNode costsBefore = report(reader, "/provider-costs");
        assertThat(costsBefore.path("month").stringValue()).isEqualTo(month);
        assertThat(summaryBefore.path("date").stringValue()).isEqualTo(today.toString());

        // A: a capture seeded with its posting and expectation; the PSP charges 2.75 where
        // its pinned schedule says 1.75 (1.5% + 0.25) - a FEE_MISMATCH of 1.00.
        String captureA = "PSP-CAP-" + letters(10);
        String operationA = "op-p8t24-" + UUID.randomUUID();
        seedCapture(operationA, captureA, 100_00, today.minusDays(1));
        UUID batchA = acceptedBatch(pspReport(captureA, today, "100.00", "2.75", "97.25"));
        // B: a capture nothing expects: it waits under grace. Its fee's original is
        // unreachable, so the check prices it at zero (ADR-0068 section 7's F1, the
        // conservative reading) - a FEE_MISMATCH of the whole 1.00, closed below by the
        // repudiation that empties its subject.
        UUID batchB =
                acceptedBatch(pspReport("PSP-CAP-" + letters(10), today, "50.00", "1.00",
                        "49.00"));
        // C: another capture nothing expects - parked into suspense with its break; its
        // 3.25 fee is likewise a FEE_MISMATCH of the whole, and stays open.
        UUID batchC =
                acceptedBatch(pspReport("PSP-CAP-" + letters(10), today, "200.00", "3.25",
                        "196.75"));
        matchUntilQuiet();
        assertThat(itemsOf(batchA))
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");
        assertThat(itemsOf(batchB))
                .containsExactlyInAnyOrder("CAPTURE:UNMATCHED", "PROCESSING_FEE:CHECKED");
        assertThat(itemsOf(batchC))
                .containsExactlyInAnyOrder("CAPTURE:UNMATCHED", "PROCESSING_FEE:CHECKED");
        String feeSeverity =
                (String) one("SELECT severity FROM reconciliation.break WHERE type ="
                        + " 'FEE_MISMATCH' AND external_item_id = ? AND value_at_issue_minor ="
                        + " 100 AND status <> 'RESOLVED'", itemOf(batchA, "PROCESSING_FEE"));
        assertThat(feeSeverity).as("the 1.00 beyond the schedule is a FEE_MISMATCH").isNotNull();
        UUID parkedItem = itemOf(batchC, "CAPTURE");
        UUID parkedBreak = parkWithBreak(parkedItem, 200_00, today);
        String parkedSeverity =
                (String) one("SELECT severity FROM reconciliation.break WHERE id = ?",
                        parkedBreak);
        UUID suspenseItem =
                (UUID) one("SELECT id FROM reconciliation.suspense_item WHERE"
                        + " external_item_id = ?", parkedItem);

        // UNMATCHED, mid-day: B waits inside grace, C is parked, three remittances are owed.
        JsonNode unmatchedMid = report(reader, "/unmatched");
        assertFigureDelta(unmatchedBefore, unmatchedMid, "unmatchedItems", 1, "50.00");
        assertFigureDelta(unmatchedBefore, unmatchedMid, "parkedItems", 1, "200.00");
        assertFigureDelta(unmatchedBefore, unmatchedMid, "expectationsInWindow", 3, "343.00");
        assertFigureDelta(unmatchedBefore, unmatchedMid, "expectationsOverdue", 0, "0.00");

        // The waiting report repudiated, four-eyes: its recognition reversed today.
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                post(BASE + "/batches/" + batchB + "/repudiation",
                        "{\"reasonCode\":\"EVIDENCE_REPUDIATED\",\"narrative\":\"the report is"
                                + " fabricated\"}",
                        proposer.token(), "rpt-" + UUID.randomUUID());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String resolution = JSON.readTree(proposed.body()).path("resolutionId").stringValue();
        HttpResponse<String> approved =
                post(BASE + "/resolutions/" + resolution + "/approval", null, approver.token(),
                        null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(itemsOf(batchB))
                .containsExactlyInAnyOrder("CAPTURE:REPUDIATED", "PROCESSING_FEE:REPUDIATED");

        // ---- SUSPENSE: the parked 200.00, CREDIT, gross, in the proof's own snapshot.
        JsonNode suspenseAfter = report(reader, "/suspense");
        JsonNode eurBefore = byField(suspenseBefore.path("currencies"), "currency", "EUR");
        JsonNode eurAfter = byField(suspenseAfter.path("currencies"), "currency", "EUR");
        assertThat(eurAfter).as("EUR's suspense account is seeded: a row with a verdict")
                .isNotNull();
        assertThat(decimal(eurAfter, "creditUnreleased")
                        .subtract(decimal(eurBefore, "creditUnreleased")))
                .isEqualByComparingTo("200.00");
        assertThat(number(eurAfter, "creditItems") - number(eurBefore, "creditItems"))
                .isEqualTo(1);
        assertThat(decimal(eurAfter, "debitUnreleased")
                        .subtract(decimal(eurBefore, "debitUnreleased")))
                .as("never netted: the DEBIT side is untouched by a CREDIT park")
                .isEqualByComparingTo("0");
        assertThat(decimal(eurAfter, "ledgerBalance").subtract(decimal(eurBefore, "ledgerBalance")))
                .as("the park's posting CR SUSPENSE_UNMATCHED 200.00")
                .isEqualByComparingTo("200.00");
        assertThat(eurAfter.path("explained").booleanValue())
                .as("the suspense proof's verdict is unchanged by a balanced park")
                .isEqualTo(eurBefore == null || eurBefore.path("explained").booleanValue());
        if (!suspenseAfter.path("truncated").booleanValue()) {
            JsonNode item = byField(suspenseAfter.path("items"), "id", suspenseItem.toString());
            assertThat(item).as("the parked item is listed").isNotNull();
            assertThat(item.path("side").stringValue()).isEqualTo("CREDIT");
            assertThat(item.path("origin").stringValue()).isEqualTo("RECON_PARK");
            assertThat(item.path("unreleased").stringValue()).isEqualTo("200.00");
            assertThat(item.path("currency").stringValue()).isEqualTo("EUR");
            assertThat(item.path("ageDays").longValue()).isZero();
            assertThat(item.path("breakId").stringValue()).isEqualTo(parkedBreak.toString());
            assertThat(item.path("breakType").stringValue()).isEqualTo("UNKNOWN_EXTERNAL");
            assertThat(item.path("breakSeverity").stringValue()).isEqualTo(parkedSeverity);
        }

        // ---- UNMATCHED: B's item and remittance left the live evidence; C stands parked.
        JsonNode unmatchedAfter = report(reader, "/unmatched");
        assertFigureDelta(unmatchedBefore, unmatchedAfter, "unmatchedItems", 0, "0.00");
        assertFigureDelta(unmatchedBefore, unmatchedAfter, "parkedItems", 1, "200.00");
        assertFigureDelta(unmatchedBefore, unmatchedAfter, "expectationsInWindow", 2, "294.00");
        assertBreakDelta(unmatchedBefore.path("openBreaks"), unmatchedAfter.path("openBreaks"),
                "FEE_MISMATCH", feeSeverity, 2, "4.25");
        assertBreakDelta(unmatchedBefore.path("openBreaks"), unmatchedAfter.path("openBreaks"),
                "UNKNOWN_EXTERNAL", parkedSeverity, 1, "200.00");
        for (JsonNode group : iterable(unmatchedAfter.path("groups"))) {
            assertThat(group.path("direction").stringValue())
                    .as("every group is one direction, never a netted pair")
                    .isIn("INBOUND", "OUTBOUND");
        }
        if (!unmatchedAfter.path("truncated").booleanValue()) {
            JsonNode item = byField(unmatchedAfter.path("items"), "id", parkedItem.toString());
            assertThat(item).as("the parked capture is listed").isNotNull();
            assertThat(item.path("status").stringValue()).isEqualTo("PARKED");
            assertThat(item.path("source").stringValue()).isEqualTo(PSP_SOURCE);
            assertThat(item.path("direction").stringValue()).isEqualTo("INBOUND");
            assertThat(item.path("value").stringValue()).isEqualTo("200.00");
            assertThat(item.path("breakId").stringValue()).isEqualTo(parkedBreak.toString());
        }

        // ---- SUMMARY of today: three acceptances, dispositions with value, the rate.
        JsonNode summaryAfter = report(reader, "/summary?date=" + today);
        JsonNode pspBefore = byField(summaryBefore.path("sources"), "source", PSP_SOURCE);
        JsonNode pspAfter = byField(summaryAfter.path("sources"), "source", PSP_SOURCE);
        assertThat(pspAfter).as("every declared source answers").isNotNull();
        assertThat(number(pspAfter, "batchesAccepted") - number(pspBefore, "batchesAccepted"))
                .isEqualTo(3);
        assertDispositionDelta(pspBefore, pspAfter, "MATCHED", 1, "100.00");
        assertDispositionDelta(pspBefore, pspAfter, "CHECKED", 2, "6.00");
        assertDispositionDelta(pspBefore, pspAfter, "PARKED", 1, "200.00");
        assertDispositionDelta(pspBefore, pspAfter, "REPUDIATED", 2, "51.00");
        assertThat(number(pspAfter, "itemsMatched") - number(pspBefore, "itemsMatched"))
                .isEqualTo(3);
        assertThat(number(pspAfter, "itemsCounted") - number(pspBefore, "itemsCounted"))
                .as("the repudiated report's items are withdrawn evidence: not counted")
                .isEqualTo(4);
        assertThat(pspAfter.path("matchRate").stringValue())
                .as("a ratio of counts to four places, half up - never a double")
                .matches("[0-9]+\\.[0-9]{4}")
                .isEqualTo(BigDecimal.valueOf(number(pspAfter, "itemsMatched"))
                        .divide(BigDecimal.valueOf(number(pspAfter, "itemsCounted")), 4,
                                RoundingMode.HALF_UP)
                        .toPlainString());
        JsonNode raisedBefore = pspBefore == null ? null : pspBefore.path("breaksRaised");
        assertSummaryBreakDelta(raisedBefore, pspAfter.path("breaksRaised"),
                "FEE_MISMATCH", feeSeverity, 3, "5.25");
        assertSummaryBreakDelta(raisedBefore, pspAfter.path("breaksRaised"),
                "UNKNOWN_EXTERNAL", parkedSeverity, 1, "200.00");
        JsonNode repudiationsBefore =
                byField(pspBefore == null ? null : pspBefore.path("resolutionsApproved"),
                        "kind", "REPUDIATE_BATCH");
        JsonNode repudiationsAfter =
                byField(pspAfter.path("resolutionsApproved"), "kind", "REPUDIATE_BATCH");
        assertThat(number(repudiationsAfter, "approved") - number(repudiationsBefore, "approved"))
                .as("the repudiation approved today, attributed to its batch's source")
                .isEqualTo(1);
        assertThat(pspAfter.path("openBreaksByAge").size()).isEqualTo(4);
        assertThat(number(byField(pspAfter.path("openBreaksByAge"), "band", "0-2"),
                        "openBreaks")
                        - number(byField(pspBefore == null ? null
                                        : pspBefore.path("openBreaksByAge"), "band", "0-2"),
                                "openBreaks"))
                .as("A's and C's fee mismatches and C's parked capture, raised today, stand"
                        + " open - B's closed with its repudiation")
                .isEqualTo(3);

        // ---- PROVIDER COSTS this month: the four things.
        JsonNode costsAfter = report(reader, "/provider-costs");
        JsonNode costBefore = costRow(costsBefore);
        JsonNode costAfter = costRow(costsAfter);
        assertThat(costAfter).as("the PSP's EUR row").isNotNull();
        assertThat(number(costAfter, "batches") - number(costBefore, "batches"))
                .as("three recognitions posted this month, the repudiated one included")
                .isEqualTo(3);
        assertThat(number(costAfter, "repudiations") - number(costBefore, "repudiations"))
                .isEqualTo(1);
        assertThat(decimal(costAfter, "charged").subtract(decimal(costBefore, "charged")))
                .as("(1) the ledger: 2.75 + 1.00 + 3.25, less the 1.00 reversed this month")
                .isEqualByComparingTo("6.00");
        assertThat(decimal(byField(costAfter.path("evidence"), "lineType", "PROCESSING_FEE"),
                        "value")
                        .subtract(decimal(byField(costBefore == null ? null
                                        : costBefore.path("evidence"), "lineType",
                                "PROCESSING_FEE"), "value")))
                .as("(2) the evidence's own fee totals, on the same basis")
                .isEqualByComparingTo("6.00");
        assertThat(decimal(costAfter, "evidenceTotal")
                        .subtract(decimal(costBefore, "evidenceTotal")))
                .isEqualByComparingTo("6.00");
        assertThat(costAfter.path("evidenceAgrees").booleanValue())
                .as("the ledger and the evidence agree, as they did before the day")
                .isEqualTo(costBefore == null || costBefore.path("evidenceAgrees").booleanValue());
        assertThat(decimal(costAfter, "expected").subtract(decimal(costBefore, "expected")))
                .as("(3) what the checks recorded: A's 1.75, and zero for B and C whose"
                        + " originals were unreachable - B's zero reversed")
                .isEqualByComparingTo("1.75");
        assertThat(number(costAfter, "feeMismatchBreaks")
                        - number(costBefore, "feeMismatchBreaks"))
                .as("(4) the month's fee mismatches, B's closed one included")
                .isEqualTo(3);
        assertThat(decimal(costAfter, "feeMismatchValueAtIssue")
                        .subtract(decimal(costBefore, "feeMismatchValueAtIssue")))
                .isEqualByComparingTo("5.25");
        assertThat(number(costAfter, "feeMismatchOpen") - number(costBefore, "feeMismatchOpen"))
                .isEqualTo(2);

        // ---- THE RECORD: one serving, one audit row, the shape and never a figure.
        assertThat(audits(reader)).as("nine servings, nine records").isEqualTo(9);
        assertThat(summaries(reader))
                .containsExactlyInAnyOrder(
                        "suspense|suspense|none", "suspense|suspense|none",
                        "unmatched|unmatched|none", "unmatched|unmatched|none",
                        "unmatched|unmatched|none",
                        "summary|summary|" + today, "summary|summary|" + today,
                        "provider-costs|provider-costs|" + month,
                        "provider-costs|provider-costs|" + month);
        assertAuditsCarryNoAmount(reader);
    }

    // ----------------------------------------------------------------- report reading

    private JsonNode report(Session reader, String path) throws Exception {
        HttpResponse<String> answer = get(reader.token(), REPORTS + path);
        assertThat(answer.statusCode()).as("%s: %s", path, answer.body()).isEqualTo(200);
        return JSON.readTree(answer.body());
    }

    /** The PSP's EUR group, INBOUND: every seeded flow lands there. */
    private static JsonNode pspInbound(JsonNode unmatched) {
        return first(unmatched.path("groups"),
                group -> PSP_SOURCE.equals(group.path("source").stringValue())
                        && "EUR".equals(group.path("currency").stringValue())
                        && "INBOUND".equals(group.path("direction").stringValue()));
    }

    private static void assertFigureDelta(
            JsonNode before, JsonNode after, String figure, long count, String value) {
        JsonNode was = pspInbound(before);
        JsonNode is = pspInbound(after);
        JsonNode wasFigure = was == null ? null : was.path(figure);
        JsonNode isFigure = is == null ? null : is.path(figure);
        assertThat(number(isFigure, "count") - number(wasFigure, "count"))
                .as("%s count", figure)
                .isEqualTo(count);
        assertThat(decimal(isFigure, "value").subtract(decimal(wasFigure, "value")))
                .as("%s value", figure)
                .isEqualByComparingTo(value);
    }

    private static void assertBreakDelta(
            JsonNode before, JsonNode after, String type, String severity, long count,
            String value) {
        Predicate<JsonNode> match =
                row -> type.equals(row.path("type").stringValue())
                        && severity.equals(row.path("severity").stringValue())
                        && "EUR".equals(row.path("currency").stringValue());
        JsonNode was = first(before, match);
        JsonNode is = first(after, match);
        assertThat(number(is, "count") - number(was, "count")).as("%s %s", type, severity)
                .isEqualTo(count);
        assertThat(decimal(is, "valueAtIssue").subtract(decimal(was, "valueAtIssue")))
                .as("%s %s value at issue", type, severity)
                .isEqualByComparingTo(value);
    }

    private static void assertSummaryBreakDelta(
            JsonNode before, JsonNode after, String type, String severity, long count,
            String value) {
        assertBreakDelta(before, after, type, severity, count, value);
    }

    private static void assertDispositionDelta(
            JsonNode before, JsonNode after, String status, long items, String value) {
        Predicate<JsonNode> match =
                row -> status.equals(row.path("status").stringValue())
                        && "EUR".equals(row.path("currency").stringValue());
        JsonNode was = before == null ? null : first(before.path("items"), match);
        JsonNode is = first(after.path("items"), match);
        assertThat(number(is, "items") - number(was, "items")).as("%s items", status)
                .isEqualTo(items);
        assertThat(decimal(is, "value").subtract(decimal(was, "value")))
                .as("%s value", status)
                .isEqualByComparingTo(value);
    }

    private static JsonNode costRow(JsonNode costs) {
        return first(costs.path("costs"),
                row -> PSP_SOURCE.equals(row.path("source").stringValue())
                        && "EUR".equals(row.path("currency").stringValue()));
    }

    private static JsonNode byField(JsonNode array, String field, String value) {
        return first(array, row -> value.equals(row.path(field).stringValue()));
    }

    private static JsonNode first(JsonNode array, Predicate<JsonNode> match) {
        if (array == null) {
            return null;
        }
        for (JsonNode row : iterable(array)) {
            if (match.test(row)) {
                return row;
            }
        }
        return null;
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            rows.add(array.get(i));
        }
        return rows;
    }

    /** A row's number, zero when the row is absent (nothing of it existed before). */
    private static long number(JsonNode row, String field) {
        return row == null ? 0L : row.path(field).longValue();
    }

    /** A row's decimal string as {@link BigDecimal}, zero when the row or value is absent. */
    private static BigDecimal decimal(JsonNode row, String field) {
        if (row == null || row.path(field).isNull() || row.path(field).isMissingNode()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(row.path(field).stringValue());
    }

    // ----------------------------------------------------------------- the record

    private static long audits(Session reader) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND"
                + " actor_id = ?", READ, reader.actorId());
    }

    /** Each of the reader's servings as target_id|report|period, from its change summary. */
    private static List<String> summaries(Session reader) throws SQLException {
        List<String> served = new ArrayList<>();
        for (String row : rows("SELECT target_id || '|' || change_summary FROM"
                + " platform.audit_record WHERE operation = ? AND actor_id = ?", READ,
                reader.actorId())) {
            String[] parts = row.split("\\|", 2);
            java.util.regex.Matcher shape =
                    java.util.regex.Pattern.compile(
                                    "report=([a-z-]+), period=([0-9A-Za-z-]+), rows=([0-9]+)")
                            .matcher(parts[1]);
            assertThat(shape.matches()).as("the summary's shape: %s", parts[1]).isTrue();
            served.add(parts[0] + "|" + shape.group(1) + "|" + shape.group(2));
        }
        return served;
    }

    /** No serving's record carries an amount, a reason or anything but its shape. */
    private static void assertAuditsCarryNoAmount(Session reader) throws SQLException {
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND"
                        + " actor_id = ? AND (change_summary ~ '[0-9]+\\.[0-9]{2}' OR reason IS"
                        + " NOT NULL OR target_type <> 'reconciliation_report')",
                        READ, reader.actorId()))
                .as("never an amount, never a reason asked for (ADR-0072 section 2)")
                .isZero();
    }

    // ----------------------------------------------------------------- seeding

    /**
     * A captured card sale seeded WITH its posting (DR the card clearing / CR a wallet) and its
     * {@code CARD_CAPTURE} expectation keyed by the PSP's capture reference - the clearing
     * identity holds at every step (the repudiation suite's fixture).
     */
    private void seedCapture(String operationRef, String captureRef, long minor, LocalDate on)
            throws Exception {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount wallet =
                    ledgerAccountStore
                            .createOrConverge(
                                    app,
                                    LedgerAccount.owned(
                                            IDS, CLOCK, AccountType.LIABILITY,
                                            AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next()))
                            .account();
            LedgerAccount clearing =
                    ledgerAccountStore
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                            .orElseThrow();
            Money amount = Money.ofPersisted(minor, EUR, 2);
            UUID entry =
                    postingService
                            .post(
                                    app,
                                    new PostingCommand(
                                            "payment-capture:" + operationRef,
                                            on,
                                            on,
                                            operationRef,
                                            List.of(
                                                    new JournalLine(clearing.id(),
                                                            Direction.DEBIT, amount),
                                                    new JournalLine(wallet.id(),
                                                            Direction.CREDIT, amount))))
                            .entryId()
                            .value();
            UUID pspSource =
                    settlementFileStore.sourceByCode(app, PSP_SOURCE).orElseThrow().id();
            expectationRegister.open(
                    app,
                    new NewExpectation(
                            ExpectationKind.CARD_CAPTURE, operationRef,
                            "payment-capture:" + operationRef, pspSource,
                            AccountPurpose.SETTLEMENT_CLEARING, clearing.id().value(),
                            ExpectationDirection.INBOUND, amount, Optional.of(entry),
                            on, Optional.empty(), on.plusDays(3),
                            ruleSets.activeFor(app, pspSource).id(),
                            List.of(new NewExpectation.ExpectationKey(
                                    KeyKind.PSP_CAPTURE_REF, captureRef)),
                            Actor.SYSTEM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
        }
    }

    /**
     * The grace leg's shape, driven directly (the suspense suite's fixture): the item's
     * UNKNOWN_EXTERNAL break raised and the item parked beside it in one transaction
     * ({@code INV-REC-09}) - the park's own posting moves the value into suspense.
     */
    private UUID parkWithBreak(UUID itemId, long minor, LocalDate decidedOn) throws Exception {
        UUID sourceId =
                (UUID) one("SELECT source_id FROM reconciliation.external_item WHERE id = ?",
                        itemId);
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return settlementTransactionRunner.inTransaction(
                    uow -> {
                        UUID position =
                                ledgerAccountStore
                                        .findOperational(
                                                uow, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                                        .orElseThrow()
                                        .id()
                                        .value();
                        BreakRegister.Raised raised =
                                breakRegister.raise(
                                        uow,
                                        new BreakRegister.NewBreak(
                                                IDS.next(),
                                                BreakType.UNKNOWN_EXTERNAL,
                                                BreakCause.GRACE_EXPIRED,
                                                BreakRegister.Subject.externalItem(itemId),
                                                sourceId,
                                                ruleSets.activeFor(uow, sourceId).id(),
                                                Money.ofPersisted(minor, EUR, 2),
                                                Optional.of(ExpectationDirection.INBOUND),
                                                Optional.empty(),
                                                Optional.of(InternalClassification.UNKNOWN),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Actor.SYSTEM,
                                                Instant.now(CLOCK),
                                                CorrelationId.generate(IDS)));
                        assertThat(raised.created()).isTrue();
                        Suspense.ParkResult parked =
                                suspense.park(
                                        uow,
                                        new Suspense.ParkCommand(
                                                sourceId,
                                                decidedOn,
                                                List.of(
                                                        new Suspense.ParkedItem(
                                                                itemId,
                                                                raised.breakId(),
                                                                Money.ofPersisted(
                                                                        minor, EUR, 2),
                                                                position)),
                                                Actor.SYSTEM,
                                                Instant.now(CLOCK),
                                                CorrelationId.generate(IDS)));
                        assertThat(parked.parked()).hasSize(1);
                        return raised.breakId();
                    });
        }
    }

    /** One SALE with its fee, the trailer's net and a remittance reference; letters elsewhere. */
    private static String pspReport(
            String captureRef, LocalDate day, String gross, String fee, String net) {
        return "H,SIM_PSP_CSV,1,PSPB-RPT-" + letters(10) + ",EUR," + day + "\n"
                + "D,1,SALE," + gross + "," + fee + ",EUR," + day + ",,," + captureRef
                + ",,,ORD-" + letters(8) + ",Sale\n"
                + "T,1," + net + "," + remittanceRef() + "\n";
    }

    private UUID acceptedBatch(String content) throws SQLException {
        UUID fileId;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            FileReception.Result result =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    reception.receive(
                                            uow,
                                            new FileReception.Delivery(
                                                    PSP_SOURCE,
                                                    DeliveryChannel.PULL,
                                                    content.getBytes(StandardCharsets.UTF_8),
                                                    Optional.empty(),
                                                    Actor.SYSTEM,
                                                    SettlementAuditAction
                                                            .SETTLEMENT_FILE_UPLOADED,
                                                    CorrelationContext.current()
                                                            .orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            fileId = ((FileReception.Result.New) result).fileId();
        }
        assertThat(settled(fileId, "RECEIVED", parsing::sweep)).isEqualTo("PARSED");
        assertThat(settled(fileId, "PARSED", acceptance::sweep)).isEqualTo("ACCEPTED");
        return (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", fileId);
    }

    /** Sweeps a REAL leg until the file leaves {@code status}. */
    private static String settled(UUID fileId, String status, Runnable sweep)
            throws SQLException {
        for (int attempt = 0; attempt < 50; attempt++) {
            sweep.run();
            String now = (String) one("SELECT status FROM settlement.file WHERE id = ?", fileId);
            if (!status.equals(now)) {
                return now;
            }
        }
        throw new AssertionError("settlement file " + fileId + " never left " + status);
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    /** Every leg swept until quiet: whatever earlier suites left waiting settles now. */
    private void drain() {
        for (int i = 0; i < 3; i++) {
            parsing.sweep();
            acceptance.sweep();
            matchUntilQuiet();
        }
    }

    private static UUID itemOf(UUID batchId, String lineType) throws SQLException {
        return (UUID) one("SELECT i.id FROM reconciliation.external_item i JOIN"
                + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id = ?"
                + " AND i.line_type = ?", batchId, lineType);
    }

    /** The batch's items as line_type:status. */
    private static List<String> itemsOf(UUID batchId) throws SQLException {
        return rows("SELECT i.line_type || ':' || i.status FROM reconciliation.external_item i"
                + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                + " r.batch_id = ?", batchId);
    }

    // ----------------------------------------------------------------- the doors

    private HttpResponse<String> get(String token, String path) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ----------------------------------------------------------------- sessions

    /** A person's session; the identity is the actor id every record names. */
    private record Session(IdentityId identity, String token) {

        String actorId() {
            return identity.value().toString();
        }
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

    private Session rolelessSession() throws SQLException {
        IdentityId identity = givenAnIdentity();
        return new Session(identity, givenASessionFor(identity));
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Report Desk Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "rr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session.Draft session =
                com.finapp.identity.Session.issue(
                        IDS, CLOCK, identity, SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD, SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    // ----------------------------------------------------------------- plumbing

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8t24-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t24-cause"));
    }

    /** Letters only: no digit run the door's screen could read as a card number. */
    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOMNESS.nextInt(26)));
        }
        return letters.toString();
    }

    /** The PSP's remittance pattern ({@code PSP-REM-[0-9]{4,12}}): ten digits, short of a card. */
    private static String remittanceRef() {
        return "PSP-REM-24" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
    }

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Object one(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static List<String> rows(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            List<String> rows = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    rows.add(row.getString(1));
                }
            }
            return rows;
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }
}
