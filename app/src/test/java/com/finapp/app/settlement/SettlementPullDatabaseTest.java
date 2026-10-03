package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.telemetry.SettlementPullMetrics;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.ExpectedArrivals;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.PullPermitStore;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementPull;
import com.finapp.settlement.SettlementReportCollector;
import com.finapp.settlement.SettlementReportCollector.FailureOutcome;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The settlement pull over the REAL composition (`P8-TSK-021`, ADR-0066 §1): the card PSP
 * source's collector pointed at a {@link SimulatedProvider} over the source's own confined
 * credential, and every claim driven through the beans the schedule and the operator's route
 * use — {@link SettlementPull}, {@link SettlementPullSweep}, the fetch route, the door, the parse
 * and accept legs, the pull's meters.
 *
 * <ul>
 *   <li><strong>One file per report</strong>: a pulled report is born {@code RECEIVED} with
 *       {@code received_via = PULL} and no deliverer, a {@code NEW} receipt and ONE acting-only
 *       {@code SettlementFileReceivedByPull} record, the source's bearer credential on the wire —
 *       and the accept leg accepts it once parsed with NO attestation ({@code INV-SET-07}: the
 *       channel's credential is its authentication).
 *   <li><strong>A derived, paced schedule</strong>: the sweep asks the counterparty for exactly
 *       the dates the acceptances say are owed, each once, as the platform; its next tick is
 *       paced by the permit; once the reports are accepted nothing is owed.
 *   <li><strong>Every failure a value</strong>: not yet, a refused answer, bytes that are not
 *       HTTP, a lost answer, a timeout and an unreachable host each write nothing under the
 *       pull's correlation and are counted exactly once under their own outcome; the retry
 *       lands ONE file and a repeat converges on it.
 *   <li><strong>Evidence, never trust</strong>: a truncated report is received and rejected
 *       whole at parse, the complete re-pull is a new file and is accepted; a pull and an upload
 *       of the same bytes are one file in either order — and a pull's receipt never
 *       authenticates an unattested upload.
 *   <li><strong>Ten instances</strong>: ten racing schedule pulls of one key make ONE fetch and
 *       one file, the permit taken once; an operator's fetch then renews it strictly forward,
 *       never paced, and converges.
 *   <li><strong>The route, the gauge, the credential</strong>: the fetch route answers what the
 *       pull came to and records each call; the silence gauge reads a finite age; the report
 *       credential crosses the wire and reaches no log and no audit record.
 * </ul>
 *
 * <p><strong>Shared container</strong>: every count is scoped to the case's own rows — by
 * content address, file, correlation or business key — never absolute. The direct cases' keys
 * are dates years before the sweep's lookback, distinct per case, so no permit one case takes can
 * pace another's; every report is unique (random LETTERS in every reference, so no digit run of
 * card length can meet the door's screen), and each carries one capture and its fee — the shape
 * {@code SettlementAcceptanceDatabaseTest} leaves accepted in the same container. Every provider
 * path a case requests is stubbed first, so the simulator never logs an unmatched request.
 *
 * <p>The credential case runs LAST, after every other pull and fetch this suite makes. A
 * pull that THROWS - a collector defect, a storage failure - is audited {@code FAILED} by the
 * operator's fetch before it propagates (the tests agent's find).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the settlement pull: one file per report, paced, retried, credentialed (P8-TSK-021)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
@Slf4j
class SettlementPullDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String SOURCE = "simulated-psp.settlement";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final String UNKNOWN_SOURCE = "simulated-nowhere.settlement";
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String FILES = "/v1/operator/settlement/files";
    private static final String RECEIVED_BY_PULL = "settlement.SettlementFileReceivedByPull";
    private static final String FETCH_REQUESTED = "settlement.SettlementFetchRequested";
    private static final String FETCH_STARTED = "settlement.SettlementFetchStarted";
    private static final LocalDate REPORT_DATE = LocalDate.parse("2026-09-29");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int LOOKBACK_DAYS = 7;
    private static final Duration CUT_OFF = Duration.ofHours(6);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SLOW = Duration.ofSeconds(6);
    private static final int RACERS = 10;
    private static final int INTAKE_TICKS = 25;

    /** The counter's {@code outcome} vocabulary: {@code not_yet} and the collector's own. */
    private static final List<String> FAILURE_OUTCOMES =
            List.of("not_yet", "unavailable", "timeout", "transport", "refused_answer");

    /** This run's report credential: 32 fresh random bytes, base64 — never a committed value. */
    private static final String REPORT_KEY = freshReportKey();

    /** The direct cases' business keys: dates years before any lookback, fresh per run. */
    private static final LocalDate KEY_BASE =
            LocalDate.of(2011, 1, 1).plusDays(RANDOMNESS.nextInt(2000));

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private SettlementPull settlementPull;
    @Autowired private SettlementPullSweep settlementPullSweep;
    @Autowired private SettlementSources settlementSources;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private PullPermitStore<Connection> pullPermitStore;
    @Autowired private FileReception<Connection> reception;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private SettlementPullMetrics settlementPullMetrics;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Authorization authorization;
    @Autowired private MeterRegistry registry;
    @Autowired private Clock clock;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private IdGenerator idGenerator;

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
    static void pulledSource(DynamicPropertyRegistry properties) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        properties.add("finapp.settlement.psp.report.url", () -> provider.baseUrl());
        properties.add("finapp.settlement.psp.report.key", () -> REPORT_KEY);
        // Short, so the slow provider costs seconds; the rest restated so the suite's
        // arithmetic never silently follows a changed default.
        properties.add("finapp.settlement.pull.timeout", TIMEOUT::toString);
        properties.add("finapp.settlement.pull.window", WINDOW::toString);
        properties.add("finapp.settlement.pull.lookback-days", () -> "" + LOOKBACK_DAYS);
        properties.add("finapp.settlement.pull.cutoff", CUT_OFF::toString);
    }

    @BeforeEach
    void freshProvider() {
        provider.reset();
    }

    // ----------------------------------------------------------------- one file per report

    @Test
    @Order(1)
    @DisplayName("a pulled report lands as ONE file - received via PULL with no deliverer, a NEW"
            + " receipt, one acting-only reception record by the platform, the source's own"
            + " bearer credential on the wire - and is accepted once parsed with NO attestation")
    void aPulledReportLandsAsOneFileAndIsAcceptedWithoutAttestation() throws Exception {
        String key = businessKey(1, 0);
        String report = report("PSPB-PULL-" + letters(10), REPORT_DATE);
        provider.succeedsWith(pathOf(key), 200, report);

        UUID fileId = newFileOf(pull(settlementPull, key, Optional.of(WINDOW)).outcome());

        assertThat(filesWithContent(report)).as("one file per content address")
                .containsExactly(fileId);
        assertThat(text("SELECT received_via || ':' || (received_by IS NULL)::text || ':'"
                        + " || status || ':' || business_date::text FROM settlement.file"
                        + " WHERE id = ?", fileId))
                .as("born RECEIVED over PULL with no person as its deliverer, dated by the key")
                .isEqualTo("PULL:true:RECEIVED:" + key);
        assertThat(receiptsOf(fileId)).isEqualTo("NEW:PULL:SYSTEM:system");
        assertThat(text("SELECT string_agg(actor_type || ':' || actor_id || ':' || target_type"
                        + " || ':' || outcome, ',') FROM platform.audit_record"
                        + " WHERE operation = ? AND target_id = ?",
                        RECEIVED_BY_PULL, fileId.toString()))
                .as("ONE reception record, the platform acting")
                .isEqualTo("SYSTEM:system:settlement_file:SUCCEEDED");
        assertThat(text("SELECT change_summary FROM platform.audit_record"
                        + " WHERE operation = ? AND target_id = ?",
                        RECEIVED_BY_PULL, fileId.toString()))
                .as("identifiers and the content address - never a byte of the report")
                .contains("channel=PULL")
                .contains("sha256=" + HexFormat.of().formatHex(sha256(report)))
                .doesNotContain("PSP-CAP-");
        assertThat(provider.headerValues(pathOf(key), "Authorization"))
                .as("one fetch, carrying the PSP report source's own bearer credential")
                .containsExactly("Bearer " + REPORT_KEY);
        assertThat(permit(SOURCE, key))
                .hasValueSatisfying(permit -> assertThat(permit.attempts()).isEqualTo(1));

        assertThat(driveIntake(fileId, "ACCEPTED", "REJECTED"))
                .as("the accept leg accepts a parsed PULL file")
                .isEqualTo("ACCEPTED");
        assertThat(text("SELECT (attested_by IS NULL)::text || ':' || (attested_at IS NULL)::text"
                        + " FROM settlement.file WHERE id = ?", fileId))
                .as("accepted with NO attestation: the source's credential authenticated it"
                        + " (INV-SET-07)")
                .isEqualTo("true:true");
        assertThat(text("SELECT status FROM settlement.batch WHERE file_id = ?", fileId))
                .isEqualTo("ACCEPTED");
    }

    // ----------------------------------------------------------------- the schedule

    @Test
    @Order(2)
    @DisplayName("the sweep derives the owed dates from acceptances and pulls each ONCE as the"
            + " platform; its next tick is paced by the permit; once the reports are accepted,"
            + " nothing is owed")
    void theSweepPullsEachOwedDateOnceAndPacesTheNextTick() throws Exception {
        Instant now = CLOCK.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        // The provider holds a report for every date the window could name, and one either
        // side of it: what the sweep ASKS for is the claim, not what the provider happens to
        // serve. Each report declares its own date, as the counterparty's would.
        Map<String, String> served = new LinkedHashMap<>();
        for (LocalDate date = today.minusDays(LOOKBACK_DAYS + 1L); !date.isAfter(today);
                date = date.plusDays(1)) {
            String report = report("PSPB-SWEEP-" + letters(10), date);
            served.put(date.toString(), report);
            provider.succeedsWith(pathOf(date.toString()), 200, report);
        }
        List<String> owed =
                ExpectedArrivals.daily(
                        now, LOOKBACK_DAYS, CUT_OFF,
                        acceptedPspDates(today.minusDays(LOOKBACK_DAYS), today));
        assertThat(owed).as("precondition: the window owes at least one date").isNotEmpty();

        assertThat(settlementPullSweep.sweep())
                .as("every owed date pulled; nothing paced, nothing failed")
                .isEqualTo(new SettlementPullSweep.SweepResult(owed.size(), owed.size(), 0, 0));
        Map<String, UUID> landed = new LinkedHashMap<>();
        for (Map.Entry<String, String> date : served.entrySet()) {
            boolean isOwed = owed.contains(date.getKey());
            assertThat(provider.requestCount(pathOf(date.getKey())))
                    .as("%s: %s", date.getKey(),
                            isOwed ? "owed, fetched once" : "not owed, never asked for")
                    .isEqualTo(isOwed ? 1 : 0);
            List<UUID> files = filesWithContent(date.getValue());
            if (!isOwed) {
                assertThat(files).as("%s lands nothing", date.getKey()).isEmpty();
                continue;
            }
            assertThat(files).as("%s lands one file", date.getKey()).hasSize(1);
            UUID fileId = files.get(0);
            landed.put(date.getKey(), fileId);
            assertThat(text("SELECT received_via || ':' || business_date::text"
                            + " FROM settlement.file WHERE id = ?", fileId))
                    .isEqualTo("PULL:" + date.getKey());
            assertThat(receiptsOf(fileId)).isEqualTo("NEW:PULL:SYSTEM:system");
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                            + " AND target_id = ? AND actor_type = 'SYSTEM'",
                            RECEIVED_BY_PULL, fileId.toString()))
                    .as("%s: one reception record, the platform acting", date.getKey())
                    .isEqualTo(1);
            assertThat(permit(SOURCE, date.getKey()))
                    .hasValueSatisfying(permit -> assertThat(permit.attempts()).isEqualTo(1));
        }

        // The next tick, inside the window: every owed date paced, not one fetch.
        assertThat(settlementPullSweep.sweep())
                .as("the herd's memory: the permit paces the second tick")
                .isEqualTo(new SettlementPullSweep.SweepResult(owed.size(), 0, owed.size(), 0));
        for (String date : owed) {
            assertThat(provider.requestCount(pathOf(date))).as("%s paced", date).isEqualTo(1);
        }

        // Accepted, the dates are no longer owed: the worklist reads acceptances, not pulls.
        for (Map.Entry<String, UUID> file : landed.entrySet()) {
            assertThat(driveIntake(file.getValue(), "ACCEPTED", "REJECTED"))
                    .as("%s accepted without attestation", file.getKey())
                    .isEqualTo("ACCEPTED");
        }
        assertThat(settlementPullSweep.sweep())
                .as("nothing owed once every due date has an accepted batch")
                .isEqualTo(new SettlementPullSweep.SweepResult(0, 0, 0, 0));
    }

    @Test
    @Order(3)
    @DisplayName("the silence gauge reads a finite, non-negative age in seconds once the source"
            + " has an accepted batch - never NaN once there is an answer")
    void theSilenceGaugeReadsSecondsSinceTheLastAcceptance() throws Exception {
        assertThat(count("SELECT count(*) FROM settlement.batch b JOIN settlement.source s"
                        + " ON s.id = b.source_id WHERE s.code = ? AND b.status = 'ACCEPTED'",
                        SOURCE))
                .as("precondition: the cases above accepted pulled batches")
                .isPositive();
        Gauge silence = registry.find(SettlementPullMetrics.SILENCE).tag("source", SOURCE).gauge();
        assertThat(silence).as("registered eagerly per declared source").isNotNull();

        double seconds = finiteReading(silence);

        // An hour is generous: the latest acceptance is this run's, seconds or minutes old.
        assertThat(seconds)
                .as("seconds since the source's last ACCEPTED batch")
                .isFinite()
                .isGreaterThanOrEqualTo(0.0)
                .isLessThan(3600.0);
    }

    // ----------------------------------------------------------------- every failure a value

    @Test
    @Order(4)
    @DisplayName("every failure is a value that writes nothing and is counted ONCE under its own"
            + " outcome - not yet, a refused answer, bytes that are not HTTP, a lost answer, a"
            + " timeout, an unreachable host - and the retry lands ONE file")
    void eachFailureIsCountedOnceAndTheRetryLandsOneFile() throws Exception {
        String key = businessKey(4, 0);
        String path = pathOf(key);
        String report = report("PSPB-RETRY-" + letters(10), REPORT_DATE);
        // Each attempt is the explicit form (the permit renewed), so no window stands between
        // the retries; the herd case proves the window.
        record Step(String name, Runnable stub, SettlementPull.Outcome expected, String counted) {}
        List<Step> steps =
                List.of(
                        new Step("404, not published yet", () -> provider.failsWith(path, 404),
                                new SettlementPull.Outcome.NotYet(), "not_yet"),
                        new Step("503, an answer that is not a report",
                                () -> provider.isUnavailable(path),
                                failed(FailureOutcome.REFUSED_ANSWER), "refused_answer"),
                        new Step("bytes that are not HTTP",
                                () -> provider.respondsWithGarbage(path),
                                failed(FailureOutcome.TRANSPORT), "transport"),
                        new Step("the answer lost after the provider acted",
                                () -> provider.receivesTheRequestThenLosesTheResponse(path),
                                failed(FailureOutcome.TRANSPORT), "transport"),
                        // The provider DOES write the real report - after the client gave up.
                        new Step("slower than the timeout",
                                () -> provider.respondsAfter(path, SLOW, 200, report),
                                failed(FailureOutcome.TIMEOUT), "timeout"));
        for (Step step : steps) {
            provider.reset();
            step.stub().run();
            Map<String, Double> before = failureCounts();

            Pulled pulled = pull(settlementPull, key, Optional.empty());

            assertThat(pulled.outcome()).as(step.name()).isEqualTo(step.expected());
            assertThat(provider.requestCount(path))
                    .as("%s: the fetch reached the provider", step.name())
                    .isPositive();
            assertCountedOnce(before, step.counted(), step.name());
            assertWroteNothing(pulled.correlationId(), step.name());
        }

        // Nothing listening: the collector's own UNAVAILABLE. The configured provider cannot
        // refuse connections, so the same composition is pointed at a closed loopback port -
        // with a generous connect timeout, because a refused loopback connect can take seconds
        // to be reported on some platforms and must not read as TIMEOUT.
        SettlementPull unreachable =
                new SettlementPull(
                        settlementSources,
                        settlementFileStore,
                        pullPermitStore,
                        reception,
                        settlementTransactionRunner,
                        settlementPullMetrics,
                        clock,
                        Map.<String, SettlementReportCollector>of(
                                SOURCE,
                                new HttpSettlementReportCollector(
                                        SOURCE,
                                        URI.create("http://127.0.0.1:" + closedPort()),
                                        Duration.ofSeconds(15),
                                        Base64.getDecoder().decode(REPORT_KEY))));
        Map<String, Double> beforeRefusal = failureCounts();
        Pulled refused = pull(unreachable, key, Optional.empty());
        assertThat(refused.outcome()).as("nothing listening")
                .isEqualTo(failed(FailureOutcome.UNAVAILABLE));
        assertCountedOnce(beforeRefusal, "unavailable", "nothing listening");
        assertWroteNothing(refused.correlationId(), "nothing listening");
        assertThat(filesWithContent(report)).as("no failure landed the report").isEmpty();

        // The provider recovers: one retry lands ONE file, and a repeat converges on it.
        provider.reset();
        provider.succeedsWith(path, 200, report);
        Map<String, Double> quiet = failureCounts();
        UUID fileId = newFileOf(pull(settlementPull, key, Optional.empty()).outcome());
        assertThat(pull(settlementPull, key, Optional.empty()).outcome())
                .as("the same report pulled again converges on the standing file")
                .isEqualTo(new SettlementPull.Outcome.Received(
                        new FileReception.Result.Duplicate(fileId)));
        assertThat(filesWithContent(report)).containsExactly(fileId);
        assertThat(receiptChannelsOf(fileId)).isEqualTo("NEW:PULL,DUPLICATE:PULL");
        assertThat(count("SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = ? AND target_id = ?",
                        RECEIVED_BY_PULL, fileId.toString()))
                .as("a duplicate pull writes its receipt and no second reception record")
                .isEqualTo(1);
        assertThat(failureCounts()).as("a delivered pull is never a failure").isEqualTo(quiet);
        assertThat(permit(SOURCE, key).orElseThrow().attempts())
                .as("every attempt, failed or not, advanced the permit")
                .isEqualTo(steps.size() + 3);
    }

    // ----------------------------------------------------------------- evidence, never trust

    @Test
    @Order(5)
    @DisplayName("a truncated report is received as evidence and rejected whole at parse; the"
            + " complete re-pull is a NEW file and is accepted")
    void aTruncatedReportIsRejectedWholeAndTheCompleteRepullIsAccepted() throws Exception {
        String key = businessKey(5, 0);
        String complete = report("PSPB-TRUNC-" + letters(10), REPORT_DATE);
        String truncated = complete.substring(0, complete.lastIndexOf("\nT,") + 1);
        assertThat(truncated).as("the fixture: every record but the trailer")
                .isNotEmpty()
                .endsWith("\n")
                .doesNotContain("\nT,");
        provider.succeedsWith(pathOf(key), 200, truncated);

        UUID truncatedId = newFileOf(pull(settlementPull, key, Optional.of(WINDOW)).outcome());
        assertThat(driveIntake(truncatedId, "ACCEPTED", "REJECTED"))
                .as("received, then rejected whole at parse - never accepted in part")
                .isEqualTo("REJECTED");
        assertThat(text("SELECT rejection_code FROM settlement.file WHERE id = ?", truncatedId))
                .isEqualTo("MALFORMED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", truncatedId))
                .as("no batch from a report missing its control totals")
                .isZero();

        // The provider publishes the whole report; an explicit fetch pulls it now, since the
        // schedule's window still stands for this key.
        provider.reset();
        provider.succeedsWith(pathOf(key), 200, complete);
        UUID completeId = newFileOf(pull(settlementPull, key, Optional.empty()).outcome());
        assertThat(completeId).as("different bytes, a different content address")
                .isNotEqualTo(truncatedId);
        assertThat(driveIntake(completeId, "ACCEPTED", "REJECTED")).isEqualTo("ACCEPTED");
        assertThat(text("SELECT status FROM settlement.file WHERE id = ?", truncatedId))
                .as("the truncated evidence stays on the record, rejected")
                .isEqualTo("REJECTED");
    }

    @Test
    @Order(6)
    @DisplayName("a pull and an upload of the same bytes are ONE file in either order, each"
            + " later delivery a DUPLICATE receipt - and a pull's receipt never authenticates"
            + " an unattested upload")
    void aPullAndAnUploadOfTheSameBytesAreOneFileInEitherOrder() throws Exception {
        Operator operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        // The pull first, then an operator uploads the very same bytes.
        String pulledFirst = report("PSPB-BOTH-" + letters(10), REPORT_DATE);
        String keyA = businessKey(6, 0);
        provider.succeedsWith(pathOf(keyA), 200, pulledFirst);
        UUID pulledId = newFileOf(pull(settlementPull, keyA, Optional.of(WINDOW)).outcome());
        HttpResponse<String> upload =
                post(FILES, uploadBody(pulledFirst), operator.token(), someKey());
        assertThat(upload.statusCode()).isEqualTo(202);
        assertThat(field(upload.body(), "fileId")).isEqualTo(pulledId.toString());
        assertThat(field(upload.body(), "duplicateOf")).isEqualTo(pulledId.toString());
        assertThat(filesWithContent(pulledFirst)).containsExactly(pulledId);
        assertThat(receiptChannelsOf(pulledId)).isEqualTo("NEW:PULL,DUPLICATE:UPLOAD");
        assertThat(text("SELECT received_via || ':' || (received_by IS NULL)::text"
                        + " FROM settlement.file WHERE id = ?", pulledId))
                .as("the first delivery's channel stands")
                .isEqualTo("PULL:true");

        // The upload first, then the pull of the same bytes.
        String uploadedFirst = report("PSPB-BOTH-" + letters(10), REPORT_DATE);
        HttpResponse<String> landed =
                post(FILES, uploadBody(uploadedFirst), operator.token(), someKey());
        assertThat(landed.statusCode()).isEqualTo(202);
        assertThat(landed.body()).contains("\"duplicateOf\":null");
        UUID uploadedId = UUID.fromString(field(landed.body(), "fileId"));
        String keyB = businessKey(6, 1);
        provider.succeedsWith(pathOf(keyB), 200, uploadedFirst);
        assertThat(pull(settlementPull, keyB, Optional.of(WINDOW)).outcome())
                .isEqualTo(new SettlementPull.Outcome.Received(
                        new FileReception.Result.Duplicate(uploadedId)));
        assertThat(filesWithContent(uploadedFirst)).containsExactly(uploadedId);
        assertThat(receiptChannelsOf(uploadedId)).isEqualTo("NEW:UPLOAD,DUPLICATE:PULL");
        assertThat(text("SELECT received_via || ':' || received_by FROM settlement.file"
                        + " WHERE id = ?", uploadedId))
                .isEqualTo("UPLOAD:" + operator.identity());
        assertThat(count("SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = ? AND target_id = ?",
                        RECEIVED_BY_PULL, uploadedId.toString()))
                .as("a duplicate pull writes its receipt and no reception record")
                .isZero();

        // A receipt never authenticates: authentication follows the FILE row's channel, so the
        // pulled duplicate leaves the upload parsed, unattested and never accepted.
        assertThat(driveIntake(uploadedId, "PARSED", "REJECTED")).isEqualTo("PARSED");
        acceptance.sweep();
        assertThat(text("SELECT status || ':' || (attested_by IS NULL)::text"
                        + " FROM settlement.file WHERE id = ?", uploadedId))
                .as("still PARSED and unattested after the accept leg ran (INV-SET-07)")
                .isEqualTo("PARSED:true");

        // Declined - the upload's own recovery - so the shared container keeps no parked file.
        HttpResponse<String> declined =
                post(FILES + "/" + uploadedId + "/decline",
                        "{\"reason\":\"pull suite fixture, not a counterparty report\"}",
                        operator.token(), null);
        assertThat(declined.statusCode()).isEqualTo(200);
    }

    // ----------------------------------------------------------------- ten instances

    @Test
    @Order(7)
    @DisplayName("ten racing schedule pulls of one key make ONE fetch and one file, the permit"
            + " taken once; an operator's fetch then renews it strictly forward and converges")
    void tenRacingPullersMakeOneFetchAndAnOperatorRenews() throws Exception {
        String key = businessKey(7, 0);
        String report = report("PSPB-HERD-" + letters(10), REPORT_DATE);
        provider.succeedsWith(pathOf(key), 200, report);

        List<SettlementPull.Outcome> outcomes =
                race(RACERS, () -> pull(settlementPull, key, Optional.of(WINDOW)).outcome());

        assertThat(outcomes.stream().filter(SettlementPull.Outcome.Paced.class::isInstance)
                        .count())
                .as("nine lost the permit and fetched nothing")
                .isEqualTo(RACERS - 1L);
        List<SettlementPull.Outcome> fetched =
                outcomes.stream()
                        .filter(outcome -> !(outcome instanceof SettlementPull.Outcome.Paced))
                        .toList();
        assertThat(fetched).as("one took the permit").hasSize(1);
        UUID fileId = newFileOf(fetched.get(0));
        assertThat(provider.requestCount(pathOf(key)))
                .as("ONE fetch reached the counterparty")
                .isEqualTo(1);
        assertThat(filesWithContent(report)).containsExactly(fileId);
        assertThat(receiptChannelsOf(fileId)).isEqualTo("NEW:PULL");
        Permit taken = permit(SOURCE, key).orElseThrow();
        assertThat(taken.attempts()).as("the herd took the permit once").isEqualTo(1);

        // An operator's fetch inside the window: renewed, never paced - and the same bytes
        // converge on the standing file.
        Operator operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> operatorFetch =
                post(fetchPath(SOURCE), fetchBody(key), operator.token(), null);
        assertThat(operatorFetch.statusCode()).isEqualTo(200);
        assertThat(field(operatorFetch.body(), "outcome")).isEqualTo("DUPLICATE");
        assertThat(field(operatorFetch.body(), "fileId")).isEqualTo(fileId.toString());
        Permit renewed = permit(SOURCE, key).orElseThrow();
        assertThat(renewed.attempts()).isEqualTo(2);
        assertThat(renewed.lastAttemptAt())
                .as("the renewal moves the permit strictly forward")
                .isAfter(taken.lastAttemptAt());
        assertThat(provider.requestCount(pathOf(key))).isEqualTo(2);
        assertThat(filesWithContent(report)).containsExactly(fileId);
        assertThat(receiptChannelsOf(fileId)).isEqualTo("NEW:PULL,DUPLICATE:PULL");
    }

    @Test
    @Order(8)
    @DisplayName("ten racing deliveries of ONE report - five operator pulls and five uploads of"
            + " the same bytes - land ONE file and a receipt per delivery (counted)")
    void tenRacingPullsAndUploadsOfOneReportLandOneFile() throws Exception {
        String key = businessKey(7, 5);
        String report = report("PSPB-RACE-" + letters(10), REPORT_DATE);
        provider.succeedsWith(pathOf(key), 200, report);
        Operator operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String body = uploadBody(report);
        java.util.concurrent.atomic.AtomicInteger seat =
                new java.util.concurrent.atomic.AtomicInteger();

        List<String> answers =
                race(RACERS, () -> {
                    if (seat.getAndIncrement() % 2 == 0) {
                        // The explicit form: renewed, never paced - every pull fetches.
                        SettlementPull.Outcome outcome =
                                pull(settlementPull, key, Optional.empty()).outcome();
                        return outcome instanceof SettlementPull.Outcome.Received received
                                ? "PULL:" + received.result().getClass().getSimpleName()
                                : "PULL:" + outcome;
                    }
                    return "UPLOAD:" + post(FILES, body, operator.token(), someKey())
                            .statusCode();
                });

        assertThat(answers.stream().filter(answer -> answer.startsWith("PULL:")).toList())
                .as("every pull delivered the bytes to the door")
                .hasSize(RACERS / 2)
                .allSatisfy(answer -> assertThat(answer).isIn("PULL:New", "PULL:Duplicate"));
        assertThat(answers.stream().filter(answer -> answer.startsWith("UPLOAD:")).toList())
                .as("every upload was received")
                .containsOnly("UPLOAD:202");
        assertThat(provider.requestCount(pathOf(key)))
                .as("the five pulls each fetched")
                .isEqualTo(RACERS / 2);
        List<UUID> files = filesWithContent(report);
        assertThat(files).as("ONE file for ten deliveries of one content address").hasSize(1);
        UUID fileId = files.get(0);
        assertThat(count("SELECT count(*) FROM settlement.file_receipt WHERE file_id = ?",
                        fileId))
                .as("a receipt per delivery")
                .isEqualTo(RACERS);
        assertThat(text("SELECT count(*) FILTER (WHERE outcome = 'NEW') || ':'"
                        + " || count(*) FILTER (WHERE channel = 'PULL') || ':'"
                        + " || count(*) FILTER (WHERE channel = 'UPLOAD')"
                        + " FROM settlement.file_receipt WHERE file_id = ?", fileId))
                .as("one NEW, five by pull, five by upload")
                .isEqualTo("1:5:5");
        assertThat(permit(SOURCE, key).orElseThrow().attempts())
                .as("five concurrent renewals, each strictly advancing the one row")
                .isEqualTo(RACERS / 2);

        // Tidy the shared container whichever channel won: a pulled winner is accepted, an
        // uploaded one declined - unattested, it could never be accepted here.
        if (text("SELECT received_via FROM settlement.file WHERE id = ?", fileId)
                .equals("PULL")) {
            assertThat(driveIntake(fileId, "ACCEPTED", "REJECTED")).isEqualTo("ACCEPTED");
        } else {
            assertThat(driveIntake(fileId, "PARSED", "REJECTED")).isEqualTo("PARSED");
            assertThat(post(FILES + "/" + fileId + "/decline",
                            "{\"reason\":\"pull suite fixture, not a counterparty report\"}",
                            operator.token(), null)
                            .statusCode())
                    .isEqualTo(200);
        }
    }

    // ----------------------------------------------------------------- the route

    @Test
    @Order(9)
    @DisplayName("the fetch route answers RECEIVED then DUPLICATE under SETTLEMENT_INGEST and"
            + " records each call; the wrong caller, an unknown source and a malformed key are"
            + " refused with nothing fetched; an unpulled source answers NOT_PULLABLE")
    void theFetchRouteAnswersWhatThePullCameTo() throws Exception {
        Operator operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String key = businessKey(8, 0);
        String report = report("PSPB-ROUTE-" + letters(10), REPORT_DATE);
        provider.succeedsWith(pathOf(key), 200, report);

        HttpResponse<String> first = post(fetchPath(SOURCE), fetchBody(key), operator.token(),
                null);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(field(first.body(), "outcome")).isEqualTo("RECEIVED");
        String fileId = field(first.body(), "fileId");
        assertThat(filesWithContent(report)).containsExactly(UUID.fromString(fileId));
        assertThat(text("SELECT received_via || ':' || (received_by IS NULL)::text"
                        + " FROM settlement.file WHERE id = ?::uuid", fileId))
                .as("an operator asked, the source's credential delivered: PULL, no deliverer")
                .isEqualTo("PULL:true");
        assertThat(text("SELECT string_agg(actor_type || ':' || actor_id, ',')"
                        + " FROM platform.audit_record WHERE operation = ? AND target_id = ?",
                        RECEIVED_BY_PULL, fileId))
                .as("the reception records who acted - the operator")
                .isEqualTo("CUSTOMER:" + operator.identity());

        HttpResponse<String> repeat = post(fetchPath(SOURCE), fetchBody(key), operator.token(),
                null);
        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat(field(repeat.body(), "outcome")).isEqualTo("DUPLICATE");
        assertThat(field(repeat.body(), "fileId")).isEqualTo(fileId);
        assertThat(filesWithContent(report)).as("natural idempotency: the content address")
                .hasSize(1);
        for (String outcome : List.of("RECEIVED", "DUPLICATE")) {
            assertThat(text("SELECT string_agg(actor_type || ':' || actor_id, ',')"
                            + " FROM platform.audit_record WHERE operation = ?"
                            + " AND target_type = 'settlement_source' AND target_id = ?"
                            + " AND change_summary = ?",
                            FETCH_REQUESTED, SOURCE,
                            "businessKey=" + key + ", outcome=" + outcome))
                    .as("ONE fetch record for the %s call, naming the key and the outcome",
                            outcome)
                    .isEqualTo("CUSTOMER:" + operator.identity());
        }
        assertThat(permit(SOURCE, key))
                .hasValueSatisfying(permit -> assertThat(permit.attempts()).isEqualTo(2));
        assertThat(text("SELECT string_agg(actor_type || ':' || actor_id, ',')"
                        + " FROM platform.audit_record WHERE operation = ?"
                        + " AND target_type = 'settlement_source' AND target_id = ?"
                        + " AND change_summary = ?",
                        FETCH_STARTED, SOURCE, "businessKey=" + key))
                .as("each call's start, committed with its permit (SEC-08)")
                .isEqualTo("CUSTOMER:" + operator.identity() + ",CUSTOMER:"
                        + operator.identity());

        // Refused before any pull: nothing fetched, nothing permitted, nothing recorded. The
        // path is stubbed so a leak past the guard WOULD reach the provider and be counted.
        String untouched = businessKey(8, 1);
        provider.succeedsWith(pathOf(untouched), 200,
                report("PSPB-ROUTE-" + letters(10), REPORT_DATE));
        assertThat(post(fetchPath(SOURCE), fetchBody(untouched), null, null).statusCode())
                .as("no session")
                .isEqualTo(401);
        Operator ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(post(fetchPath(SOURCE), fetchBody(untouched), ledgerOperator.token(), null)
                        .statusCode())
                .as("a session without SETTLEMENT_INGEST")
                .isEqualTo(403);
        assertThat(provider.requestCount(pathOf(untouched))).isZero();
        assertThat(permit(SOURCE, untouched)).isEmpty();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                        + " AND change_summary LIKE ?",
                        FETCH_REQUESTED, "businessKey=" + untouched + ",%"))
                .isZero();

        HttpResponse<String> unknown =
                post(fetchPath(UNKNOWN_SOURCE), fetchBody(untouched), operator.token(), null);
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("settlement.SourceUnknown");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                        + " AND target_id = ?", FETCH_REQUESTED, UNKNOWN_SOURCE))
                .as("an unknown source writes nothing")
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                        + " AND change_summary = ?", FETCH_STARTED, "businessKey=" + untouched))
                .as("no permit was taken for a refused or unknown fetch, so nothing started")
                .isZero();

        // A traversal-shaped key fails the key's own shape at the boundary: the platform's
        // validation contract renders it 422 (ERROR_CONTRACT: the data is wrong).
        HttpResponse<String> malformed =
                post(fetchPath(SOURCE), "{\"businessKey\":\"../reports\"}", operator.token(),
                        null);
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("api.ValidationFailed");
        assertThat(provider.requestCount(pathOf(untouched))).isZero();

        // Declared, but no collector is configured for it here: answered, never pulled.
        String unpulled = businessKey(8, 2);
        HttpResponse<String> notPullable =
                post(fetchPath(BANK_SOURCE), fetchBody(unpulled), operator.token(), null);
        assertThat(notPullable.statusCode()).isEqualTo(200);
        assertThat(field(notPullable.body(), "outcome")).isEqualTo("NOT_PULLABLE");
        assertThat(permit(BANK_SOURCE, unpulled)).isEmpty();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                        + " AND change_summary = ?", FETCH_STARTED, "businessKey=" + unpulled))
                .as("a source not pulled takes no permit and starts nothing")
                .isZero();
    }

    // ----------------------------------------------------------------- the credential

    @Test
    @Order(10)
    @DisplayName("an operator's fetch whose pull THROWS is recorded FAILED naming the"
            + " exception's class, then propagates - the ask is never left unrecorded")
    void aThrowingPullIsRecordedFailed() throws Exception {
        String key = businessKey(9, 5);
        SettlementPull defective =
                new SettlementPull(
                        settlementSources,
                        settlementFileStore,
                        pullPermitStore,
                        reception,
                        settlementTransactionRunner,
                        settlementPullMetrics,
                        clock,
                        Map.<String, SettlementReportCollector>of(
                                SOURCE,
                                new SettlementReportCollector() {
                                    @Override
                                    public String sourceCode() {
                                        return SOURCE;
                                    }

                                    @Override
                                    public Collected collect(String businessKey) {
                                        throw new IllegalStateException("a collector defect");
                                    }
                                }));
        SettlementFetch fetch =
                new SettlementFetch(
                        defective, auditWriter, settlementTransactionRunner, idGenerator, clock);
        Correlation correlation = Correlation.startingWith(CorrelationId.generate(IDS));
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(correlation)) {
            assertThatThrownBy(() -> fetch.fetch(SOURCE, new SettlementFetchRequest(key)))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(text("SELECT outcome || '|' || change_summary FROM platform.audit_record"
                        + " WHERE operation = ? AND correlation_id = ?",
                        FETCH_REQUESTED, correlation.correlationId().value()))
                .as("the ask recorded FAILED, the exception's class and never its message")
                .isEqualTo("FAILED|businessKey=" + key + ", outcome=ERROR:IllegalStateException");
        assertThat(permit(SOURCE, key))
                .as("its permit was renewed before the collector threw")
                .hasValueSatisfying(permit -> assertThat(permit.attempts()).isEqualTo(1));
    }

    @Test
    @Order(11)
    @DisplayName("the report credential crosses the wire and reaches no log line and no audit"
            + " record - through a pull, a refused answer, an operator's fetch and a refused"
            + " route")
    void theCredentialNeverReachesALog(CapturedOutput output) throws Exception {
        String control = "pull-capture-control-" + letters(12);
        log.info("the output capture is live: {}", control);

        String key = businessKey(9, 0);
        String report = report("PSPB-LOGS-" + letters(10), REPORT_DATE);
        provider.succeedsWith(pathOf(key), 200, report);
        UUID fileId = newFileOf(pull(settlementPull, key, Optional.of(WINDOW)).outcome());
        provider.reset();
        provider.isUnavailable(pathOf(key));
        assertThat(pull(settlementPull, key, Optional.empty()).outcome())
                .isEqualTo(failed(FailureOutcome.REFUSED_ANSWER));
        provider.reset();
        provider.succeedsWith(pathOf(key), 200, report);
        Operator operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> fetched =
                post(fetchPath(SOURCE), fetchBody(key), operator.token(), null);
        assertThat(field(fetched.body(), "outcome")).isEqualTo("DUPLICATE");
        assertThat(field(fetched.body(), "fileId")).isEqualTo(fileId.toString());
        assertThat(post(fetchPath(UNKNOWN_SOURCE), fetchBody(key), operator.token(), null)
                        .statusCode())
                .isEqualTo(422);
        assertThat(provider.headerValues(pathOf(key), "Authorization"))
                .as("the credential really crossed the wire here - or its absence below"
                        + " would prove nothing")
                .containsExactly("Bearer " + REPORT_KEY);

        byte[] decoded = Base64.getDecoder().decode(REPORT_KEY);
        String all = output.getAll();
        assertThat(all).as("the capture saw this case's own line").contains(control);
        assertThat(all)
                .as("... and a production line from this case: the refused route's warning")
                .contains("settlement.SourceUnknown");
        assertThat(all).as("the credential as sent (INV-AUD-02)").doesNotContain(REPORT_KEY);
        assertThat(all).as("nor its url-safe spelling")
                .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded));
        assertThat(all).as("nor its bytes in hex")
                .doesNotContainIgnoringCase(HexFormat.of().formatHex(decoded));
        assertThat(count("SELECT count(*) FROM platform.audit_record"
                        + " WHERE coalesce(change_summary, '') || coalesce(reason, '') LIKE ?",
                        "%" + REPORT_KEY + "%"))
                .as("nor any audit record")
                .isZero();
    }

    @Test
    @Order(12)
    @DisplayName("an operator's fetch whose process dies past its permit - before the record of"
            + " what it came to - still leaves who asked for which report: the start commits"
            + " with the permit (SEC-08)")
    void aFetchThatDiesPastItsPermitLeavesItsStart() throws Exception {
        String key = businessKey(9, 6);
        SettlementPull dying =
                new SettlementPull(
                        settlementSources,
                        settlementFileStore,
                        pullPermitStore,
                        reception,
                        settlementTransactionRunner,
                        settlementPullMetrics,
                        clock,
                        Map.<String, SettlementReportCollector>of(
                                SOURCE,
                                new SettlementReportCollector() {
                                    @Override
                                    public String sourceCode() {
                                        return SOURCE;
                                    }

                                    @Override
                                    public Collected collect(String businessKey) {
                                        throw new SimulatedCrash();
                                    }
                                }));
        SettlementFetch fetch =
                new SettlementFetch(
                        dying, auditWriter, settlementTransactionRunner, idGenerator, clock);
        Correlation correlation = Correlation.startingWith(CorrelationId.generate(IDS));
        String actor;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(correlation)) {
            actor = SecurityContext.require().type().name() + ":"
                    + SecurityContext.require().id();
            assertThatThrownBy(() -> fetch.fetch(SOURCE, new SettlementFetchRequest(key)))
                    .isInstanceOf(SimulatedCrash.class);
        }
        assertThat(permit(SOURCE, key))
                .as("the fetch's first effect committed: its permit was renewed")
                .hasValueSatisfying(permit -> assertThat(permit.attempts()).isEqualTo(1));
        assertThat(text("SELECT actor_type || ':' || actor_id || '|' || target_id || '|'"
                        + " || outcome || '|' || change_summary FROM platform.audit_record"
                        + " WHERE operation = ? AND correlation_id = ?",
                        FETCH_STARTED, correlation.correlationId().value()))
                .as("who asked for which report is on the record, beside the permit")
                .isEqualTo(actor + "|" + SOURCE + "|SUCCEEDED|businessKey=" + key);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                        + " AND correlation_id = ?",
                        FETCH_REQUESTED, correlation.correlationId().value()))
                .as("the process died before the record of what it came to")
                .isZero();
    }

    /** The injected crash: an {@code Error}, which the fetch never catches or records. */
    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("simulated crash past the permit");
        }
    }

    // ----------------------------------------------------------------- pulls and reports

    /** What one pull came to, and the correlation its rows (if any) carry. */
    private record Pulled(SettlementPull.Outcome outcome, String correlationId) {}

    /** A person with a session: the bearer token and the identity the audit records name. */
    private record Operator(String token, UUID identity) {}

    /** One {@code settlement.pull_permit} row. */
    private record Permit(int attempts, OffsetDateTime lastAttemptAt) {}

    /** One pull as the platform, in a fresh correlation the case can scope its reads by. */
    private static Pulled pull(SettlementPull through, String key, Optional<Duration> window) {
        Correlation correlation = Correlation.startingWith(CorrelationId.generate(IDS));
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(correlation)) {
            return new Pulled(
                    through.pull(SOURCE, key, window, SecurityContext.require(), correlation),
                    correlation.correlationId().value());
        }
    }

    private static UUID newFileOf(SettlementPull.Outcome outcome) {
        if (outcome instanceof SettlementPull.Outcome.Received received
                && received.result() instanceof FileReception.Result.New landed) {
            return landed.fileId();
        }
        throw new AssertionError("expected a NEW file, but the pull came to " + outcome);
    }

    private static SettlementPull.Outcome failed(FailureOutcome outcome) {
        return new SettlementPull.Outcome.Failed(outcome);
    }

    /** One capture and its fee, every reference random letters, the remittance's own digits. */
    private static String report(String batchRef, LocalDate businessDate) {
        return new String(
                new SimulatedSettlementReports(
                                batchRef, "EUR", businessDate, "PSP-REM-" + digits(12))
                        .with(SimulatedSettlementReports.Line.capture(
                                "PSP-CAP-" + letters(12), "", "", "100.00", "1.75"))
                        .render(),
                StandardCharsets.UTF_8);
    }

    private static String pathOf(String businessKey) {
        return HttpSettlementReportCollector.REPORTS_PATH + SOURCE + "/" + businessKey;
    }

    private static String businessKey(int caseNo, int n) {
        return KEY_BASE.plusDays(caseNo * 10L + n).toString();
    }

    /** Parse and accept until the file reaches one of {@code until}, or the ticks run out. */
    private String driveIntake(UUID fileId, String... until) throws SQLException {
        List<String> wanted = List.of(until);
        for (int tick = 0; tick < INTAKE_TICKS; tick++) {
            String status = statusOf(fileId);
            if (wanted.contains(status)) {
                return status;
            }
            parsing.sweep();
            acceptance.sweep();
        }
        return statusOf(fileId);
    }

    private static String statusOf(UUID fileId) throws SQLException {
        return text("SELECT status FROM settlement.file WHERE id = ?", fileId);
    }

    // ----------------------------------------------------------------- meters

    private Map<String, Double> failureCounts() {
        Map<String, Double> counts = new LinkedHashMap<>();
        for (String outcome : FAILURE_OUTCOMES) {
            Counter counter =
                    registry.find(SettlementPullMetrics.PULL_FAILURE)
                            .tag("source", SOURCE)
                            .tag("outcome", outcome)
                            .counter();
            assertThat(counter).as("the %s series is registered eagerly", outcome).isNotNull();
            counts.put(outcome, counter.count());
        }
        return counts;
    }

    private void assertCountedOnce(Map<String, Double> before, String counted, String step) {
        Map<String, Double> after = failureCounts();
        for (String outcome : FAILURE_OUTCOMES) {
            assertThat(after.get(outcome) - before.get(outcome))
                    .as("%s: finapp.settlement.pull.failure{outcome=%s}", step, outcome)
                    .isEqualTo(outcome.equals(counted) ? 1.0 : 0.0);
        }
    }

    /** The reading is cached for seconds; one taken before an acceptance may still say NaN. */
    private static double finiteReading(Gauge gauge) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        double value = gauge.value();
        while (Double.isNaN(value) && System.nanoTime() < deadline) {
            Thread.sleep(250);
            value = gauge.value();
        }
        return value;
    }

    // ----------------------------------------------------------------- reads, scoped

    private static List<UUID> filesWithContent(String content) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT f.id FROM settlement.file f"
                                        + " JOIN settlement.source s ON s.id = f.source_id"
                                        + " WHERE s.code = ? AND f.content_sha256 = ?"
                                        + " ORDER BY f.received_at, f.id")) {
            bind(read, SOURCE, sha256(content));
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> files = new ArrayList<>();
                while (rows.next()) {
                    files.add(rows.getObject(1, UUID.class));
                }
                return files;
            }
        }
    }

    private static String receiptsOf(UUID fileId) throws SQLException {
        return text("SELECT string_agg(outcome || ':' || channel || ':' || actor_type || ':'"
                + " || actor, ',' ORDER BY received_at, id) FROM settlement.file_receipt"
                + " WHERE file_id = ?", fileId);
    }

    private static String receiptChannelsOf(UUID fileId) throws SQLException {
        return text("SELECT string_agg(outcome || ':' || channel, ',' ORDER BY received_at, id)"
                + " FROM settlement.file_receipt WHERE file_id = ?", fileId);
    }

    private static Optional<Permit> permit(String source, String key) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT p.attempts, p.last_attempt_at"
                                        + " FROM settlement.pull_permit p"
                                        + " JOIN settlement.source s ON s.id = p.source_id"
                                        + " WHERE s.code = ? AND p.business_key = ?")) {
            bind(read, source, key);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(new Permit(
                                row.getInt(1), row.getObject(2, OffsetDateTime.class)))
                        : Optional.empty();
            }
        }
    }

    /** The sweep's own read, restated: the source's ACCEPTED batch dates in the window. */
    private static Set<LocalDate> acceptedPspDates(LocalDate from, LocalDate to)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT DISTINCT b.business_date FROM settlement.batch b"
                                        + " JOIN settlement.source s ON s.id = b.source_id"
                                        + " WHERE s.code = ? AND b.status = 'ACCEPTED'"
                                        + " AND b.business_date BETWEEN ? AND ?")) {
            bind(read, SOURCE, from, to);
            try (ResultSet rows = read.executeQuery()) {
                Set<LocalDate> dates = new HashSet<>();
                while (rows.next()) {
                    dates.add(rows.getObject(1, LocalDate.class));
                }
                return dates;
            }
        }
    }

    /** A failed pull commits nothing but its permit: no file, receipt, refusal or audit. */
    private static void assertWroteNothing(String correlationId, String step)
            throws SQLException {
        assertThat(count("SELECT (SELECT count(*) FROM settlement.file WHERE correlation_id = ?)"
                        + " + (SELECT count(*) FROM settlement.file_receipt"
                        + "    WHERE correlation_id = ?)"
                        + " + (SELECT count(*) FROM settlement.refused_delivery"
                        + "    WHERE correlation_id = ?)"
                        + " + (SELECT count(*) FROM platform.audit_record"
                        + "    WHERE correlation_id = ?)",
                        correlationId, correlationId, correlationId, correlationId))
                .as("%s: nothing written under the pull's correlation", step)
                .isZero();
    }

    private static String text(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            bind(read, args);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("a row for %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            bind(read, args);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("a row for %s", sql).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    // ----------------------------------------------------------------- concurrency

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(3, TimeUnit.MINUTES));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A loopback port nothing listens on: bound by the OS, then released. */
    private static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        }
    }

    // ----------------------------------------------------------------- HTTP and sessions

    private static String fetchPath(String sourceCode) {
        return "/v1/operator/settlement/sources/" + sourceCode + "/fetch";
    }

    private static String fetchBody(String businessKey) {
        return "{\"businessKey\":\"" + businessKey + "\"}";
    }

    private static String uploadBody(String content) {
        return "{\"sourceCode\":\"" + SOURCE + "\",\"businessDate\":\"" + REPORT_DATE + "\","
                + "\"content\":\""
                + Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))
                + "\"}";
    }

    private Operator sessionWith(RoleName role) throws Exception {
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
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return new Operator(tokenFrom(authenticate(login).body()), identity);
    }

    private String registered() throws Exception {
        String login = "pull." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post(
                                "/v1/registrations",
                                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":"
                                        + "\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}",
                                null,
                                someKey())
                        .statusCode())
                .isEqualTo(201);
        return login;
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    // ----------------------------------------------------------------- randomness

    private static String freshReportKey() {
        byte[] key = new byte[32];
        RANDOMNESS.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOMNESS.nextInt(26)));
        }
        return letters.toString();
    }

    /** Short of card length by construction: the remittance reference's shape wants digits. */
    private static String digits(int length) {
        StringBuilder digits = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            digits.append((char) ('0' + RANDOMNESS.nextInt(10)));
        }
        return digits.toString();
    }

    private static byte[] sha256(String content) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", impossible);
        }
    }
}
