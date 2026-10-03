package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.merchant.PayoutReturnSweep;
import com.finapp.app.settlement.SimulatedPayoutReports;
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
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.payments.SettlementExpectations;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.InternalReferenceLookup;
import com.finapp.reconciliation.JdbcRepudiationStore;
import com.finapp.reconciliation.JdbcResolutionStore;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.MatchingRules;
import com.finapp.reconciliation.MatchingStore;
import com.finapp.reconciliation.ReconciliationSweep;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.RepudiationStore;
import com.finapp.reconciliation.ResolutionStatus;
import com.finapp.reconciliation.Resolutions;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A settlement batch repudiated over real HTTP and the REAL composition (`P8-TSK-023`,
 * ADR-0065 §10, ADR-0070 §10, ADR-0071; {@code INV-REV-01}, {@code INV-AUD-04},
 * {@code INV-REC-07}, {@code INV-REC-09}): the four-eyes {@code REPUDIATE_BATCH} resolution
 * proposed by one operator and approved by another, its five effects counted in the tables -
 * the recognition reversed once under {@code ledger.reverse:settlement-batch:<batchId>}, every
 * allocation countered and its expectation reopened, the batch's own remittance closed, the
 * items and the batch {@code REPUDIATED}, the event and the audit records - and the position,
 * suspense and cash proofs explained after every approval.
 *
 * <p>The scenarios are the lifecycle document's §3.4 card day and its bank hop: a fabricated PSP
 * report and the genuine file it blocked (readmitted into the freed identity); a fabricated
 * statement and its genuine replacement under the same sequence; a PSP report repudiated
 * beneath a standing statement (ADR-0065 §10's worked equation: DR−CR = f = g − (g − f)); an
 * unattributed bank credit a person already transferred out of suspense, answered by a new
 * {@code REPUDIATION} item rather than released twice; the refusals; ten racing approvers; the
 * door's negatives; and the repudiation against the REAL matcher both ways and in a true race,
 * over a capture line waiting under grace until its expectation opens (cases 9-11: the rematch
 * leg first makes the proposal stale; the repudiation first leaves the matcher nothing; the
 * race lands exactly one of the two); a break the repudiation closed, its closure row and its
 * case file naming the repudiation (case 12); and a payout return applied from the repudiated
 * report left standing as a merchant fact, its expectation reopened to age (case 13 - payouts
 * paid by the simulated provider through the real merchant flow, as
 * {@code PayoutReturnDatabaseTest} pays them); and ten operators racing to propose one batch's
 * repudiation, one proposal landing (case 14, the plan's §7 C14).
 *
 * <p>The Phase 8 -> 9 transition's corrections, each case its finding's reproduction: a bank item
 * the repudiation reopened parks AGAIN at its next grace without jamming its source, the
 * repudiated remittance's reference freed for the genuine one, the repudiated report's own bytes
 * readmitted and re-settling everything, and the settlement status over standing allocations
 * (case 15: REC-3, REC-8/ATOM-03, MI-2, SET-3); a reopened expectation already overdue owned at
 * once by a fresh {@code MISSING_EXTERNAL} (case 16: REC-4/SET-1); the four shapes refused before
 * anything is written, the corrected original among them (case 17: REC-7, T-2); and a statement
 * repudiated in the middle of its chain owning its hole on the successor's run (case 18: SET-2),
 * and the same while its successor's acceptance races the approval (case 19); and a batch whose
 * run blocked at its completion, every item decided, refused at proposal and at approval until the
 * run is requeued and completes (case 20: MI-8).
 *
 * <p><strong>Run apart.</strong> Like {@code SchemeCycleCashDatabaseTest},
 * {@code PayoutSettlementCashDatabaseTest} and {@code PayoutReturnDatabaseTest}, this suite runs
 * in its own container: it repudiates batches, and its proof assertions are ABSOLUTE - every
 * verdict explained, nothing unattributed, nothing unowned - which holds only when no suite
 * that legitimately leaves a proof failing (a non-zero first opening, a fixture parking) ran
 * before it in the same database. Every flow keeps its money balanced: each capture is seeded
 * with its posting and its {@code CARD_CAPTURE} expectation, each fee is the pinned schedule's
 * own (1.5% + 0.25), each statement continues its currency's chain from the head it finds
 * (opening = the head's closing), and every amount is distinct, so the bank rule's
 * value-date grouping can never join two cases' remittances.
 *
 * <p>The cases build on each other in order ({@code @Order}): case 2 recovers case 1's genuine
 * file, case 8 probes case 4's standing statement, case 7 races over case 6's batch.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("a settlement batch repudiated, four-eyes, over HTTP (P8-TSK-023)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class BatchRepudiationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final String PSP_SOURCE = "simulated-psp.settlement";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final String BASE = "/v1/operator/reconciliation";
    private static final String FILES = "/v1/operator/settlement/files";
    private static final String REPUDIATED_EVENT = "settlement.SettlementBatchRepudiated";
    private static final String NOT_PENDING = "reconciliation.ResolutionNotPending";
    private static final String PAYOUT_SOURCE = "simulated-payout.settlement";

    /** The simulated payout provider's answer: paid, its reference minted letters-only. */
    private static final String PAID =
            "{\"status\":\"paid\",\"reference\":\"po_{{randomValue length=12"
                    + " type='ALPHABETIC'}}\"}";

    /** The payout provider case 13's payouts are paid by (PayoutReturnDatabaseTest's fixture). */
    private static SimulatedProvider payoutProvider;

    @DynamicPropertySource
    static void payoutProviderUrl(DynamicPropertyRegistry registry) {
        if (payoutProvider == null) {
            payoutProvider = SimulatedProvider.start();
        }
        registry.add("finapp.merchant.payout.provider.url", () -> payoutProvider.baseUrl());
    }

    @AfterAll
    static void stopPayoutProvider() {
        if (payoutProvider != null) {
            payoutProvider.close();
        }
    }

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private ReconciliationSweep reconciliationSweep;
    @Autowired private PositionProof proof;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private RuleSets ruleSets;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private MerchantPayouts payouts;
    @Autowired private PayoutReturnSweep payoutReturnSweep;
    @Autowired private SettlementExpectations settlementExpectations;
    // The matcher's own parts, for case 20's matcher of two items a chunk.
    @Autowired private MatchingStore matchingStore;
    @Autowired private MatchingRules matchingRules;
    @Autowired private com.finapp.reconciliation.BreakRegister breakRegister;
    @Autowired private Suspense suspense;
    @Autowired private Resolutions resolutions;
    @Autowired private InternalReferenceLookup internalReferenceLookup;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // What the ordered cases hand on.
    private static UUID fabricatedBatch;
    private static UUID fabricatedFile;
    private static String fabricatedBatchRef;
    private static String fabricatedCaptureOperation;
    private static UUID genuineFile;
    private static UUID standingStatement;
    private static UUID raceBatch;
    private static UUID raceFile;
    private static String raceCaptureOperation;

    // ----------------------------------------------------------------- 1. the card day

    @Test
    @Order(1)
    @DisplayName("a fabricated PSP report, uploaded, attested, accepted and matched, is repudiated:"
            + " the proposer's approval refused, the second operator's reverses the recognition"
            + " ONCE, counters the capture's allocation and reopens it, closes the remittance"
            + " RESOLVED_BY_ADJUSTMENT, repudiates every item and the batch (the file and its"
            + " bytes untouched), announces and audits - the narrative nowhere - and every proof"
            + " holds")
    void aFabricatedPspReportIsRepudiated() throws Exception {
        assertBooksHold("before the fabricated report");
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-25");
        String operationRef = "op-p8t23-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        String batchRef = "PSPB-RPD-" + marker;
        seedCapture(operationRef, captureRef, 100_00, day.minusDays(1));
        Session uploader = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session attester = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        // The fabrication arrives the realistic way: uploaded, attested by a second person.
        UUID file =
                uploaded(uploader, PSP_SOURCE, day,
                        pspReport(batchRef, day, captureRef, "100.00", "1.75", "98.25",
                                remittanceRef(), "Sale"));
        HttpResponse<String> attested = attest(file, attester);
        assertThat(attested.statusCode()).as(attested.body()).isEqualTo(200);
        assertThat(parseSettled(file)).isEqualTo("PARSED");
        assertThat(acceptanceSettled(file)).isEqualTo("ACCEPTED");
        UUID batch = batchOf(file);
        matchUntilQuiet();
        UUID capture = expectationOf("CARD_CAPTURE", operationRef);
        UUID remittance = expectationOf("REMITTANCE", batch.toString());
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("SETTLED");
        assertThat(itemsOf(batch))
                .as("every item disposed: the capture matched, the fee checked")
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");
        UUID recognition = recognitionOf(batch);
        assertThat(entryLines(recognition))
                .containsExactlyInAnyOrder("PROCESSING_COSTS:DEBIT:175",
                        "SETTLEMENT_CLEARING:CREDIT:175");
        UUID original =
                (UUID) one("SELECT id FROM reconciliation.allocation WHERE expectation_id = ?"
                        + " AND reverses_allocation_id IS NULL", capture);
        assertBooksHold("the fabricated report accepted and matched");

        // The genuine file, delivered while the fabrication stands: refused as a conflict.
        UUID genuine =
                pulled(PSP_SOURCE,
                        pspReport(batchRef, day, captureRef, "100.00", "1.75", "98.25",
                                remittanceRef(), "Genuine sale"));
        assertThat(parseSettled(genuine)).isEqualTo("REJECTED");
        assertThat(one("SELECT rejection_code FROM settlement.file WHERE id = ?", genuine))
                .isEqualTo("CONFLICTING_BATCH");
        String shaBefore = sha(file);

        // The proposal: counts, never the narrative.
        String needle = "needle" + letters(12);
        HttpResponse<String> proposed =
                propose(proposer, batch.toString(), key(), "fabricated report " + needle);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(field(proposed.body(), "settlementBatchId")).isEqualTo(batch.toString());
        assertCounts(proposed.body(), 2, 1, 0, 0, 0, 0, true, true);
        assertThat(proposed.body()).doesNotContain(needle);
        String resolution = field(proposed.body(), "resolutionId");

        // Four eyes: the proposer cannot approve - and nothing moved.
        HttpResponse<String> self = approve(proposer, resolution);
        assertThat(self.statusCode()).as(self.body()).isEqualTo(409);
        assertThat(self.body()).contains("reconciliation.SelfApprovalRefused");
        assertThat(batchStatus(batch)).isEqualTo("ACCEPTED");
        assertThat(reversalsOf(recognition)).isZero();
        assertThat(resolutionStatus(resolution)).isEqualTo("PROPOSED");

        HttpResponse<String> approved = approve(approver, resolution);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(field(approved.body(), "settlementBatchId")).isEqualTo(batch.toString());
        assertThat(approved.body())
                .as("a repudiation's subject is the batch, never a break")
                .doesNotContain("\"breakId\":\"");
        String entry = field(approved.body(), "journalEntryId");
        HttpResponse<String> retried = approve(approver, resolution);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        assertThat(field(retried.body(), "journalEntryId"))
                .as("the approver's retry converges")
                .isEqualTo(entry);

        // (1) The recognition reversed once, through ReversalService, line by line.
        assertThat(reversalsOf(recognition)).isEqualTo(1);
        assertThat(one("SELECT id::text || '|' || entry_type || '|' || idempotency_scope FROM"
                        + " ledger.journal_entry WHERE reverses_entry_id = ?", recognition))
                .isEqualTo(entry + "|REVERSAL|ledger.reverse:settlement-batch:" + batch);
        assertThat(entryLines(UUID.fromString(entry)))
                .containsExactlyInAnyOrderElementsOf(mirrored(entryLines(recognition)));

        // (2) The allocation countered, the capture reopened, the remittance closed.
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                        + " reverses_allocation_id = ? AND expectation_id = ? AND amount_minor"
                        + " = 10000", original, capture))
                .as("one counter-allocation mirroring the original (INV-REC-07)")
                .isEqualTo(1);
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", capture))
                .as("the capture owes its whole value again")
                .isEqualTo("OPEN:0");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                        + " expectation_id = ? AND event_type = 'REOPENED' AND detail = ?",
                        capture, "resolution=" + resolution))
                .isEqualTo(1);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .as("the report's remittance leaves with the fee that justified it")
                .isEqualTo("RESOLVED_BY_ADJUSTMENT");

        // (4) The items and the batch REPUDIATED; the file and its bytes as they were.
        assertThat(itemsOf(batch))
                .containsExactlyInAnyOrder("CAPTURE:REPUDIATED", "PROCESSING_FEE:REPUDIATED");
        assertThat(count("SELECT count(*) FROM reconciliation.external_item_event e JOIN"
                        + " reconciliation.external_item i ON i.id = e.item_id JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ? AND e.to_status = 'REPUDIATED'", batch))
                .isEqualTo(2);
        assertThat(batchStatus(batch)).isEqualTo("REPUDIATED");
        assertThat(count("SELECT count(*) FROM settlement.batch_event WHERE batch_id = ? AND"
                        + " from_status = 'ACCEPTED' AND to_status = 'REPUDIATED' AND reason = ?"
                        + " AND actor = ?",
                        batch, "resolution=" + resolution, approver.actorId()))
                .isEqualTo(1);
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", file))
                .as("the evidence is what it was: only the batch's verdict changes")
                .isEqualTo("ACCEPTED");
        assertThat(sha(file)).isEqualTo(shaBefore);
        assertThat(one("SELECT status || '|' || kind || '|' || (break_id IS NULL)::text || '|'"
                        + " || settlement_batch_id::text || '|' || journal_entry_id::text || '|'"
                        + " || proposed_by || '|' || decided_by FROM reconciliation.resolution"
                        + " WHERE id = ?::uuid", resolution))
                .isEqualTo("APPROVED|REPUDIATE_BATCH|true|" + batch + "|" + entry + "|"
                        + proposer.actorId() + "|" + approver.actorId());

        // (5) Announced and audited, the narrative in none of it.
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ? AND"
                        + " aggregate_id = ? AND convert_from(payload, 'UTF8') LIKE ? AND"
                        + " convert_from(payload, 'UTF8') LIKE ?",
                        REPUDIATED_EVENT, file, "%" + batch + "%", "%" + entry + "%"))
                .isEqualTo(1);
        assertThat(audits("reconciliation.ResolutionProposed", resolution, proposer)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'reconciliation.ResolutionApproved' AND target_id = ? AND actor_id"
                        + " = ? AND change_summary LIKE ?",
                        resolution, approver.actorId(), "%journalEntry=" + entry + "%"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND"
                        + " target_id = ? AND actor_id = ? AND change_summary LIKE ?",
                        REPUDIATED_EVENT, batch.toString(), approver.actorId(),
                        "%reversalEntry=" + entry + "%"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE"
                        + " coalesce(change_summary, '') || coalesce(reason, '') LIKE ?",
                        "%" + needle + "%"))
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                        + " convert_from(payload, 'UTF8') LIKE ?", "%" + needle + "%"))
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE"
                        + " convert_from(response_body, 'UTF8') LIKE ?", "%" + needle + "%"))
                .as("the stored receipt carries no narrative")
                .isZero();

        assertBooksHold("the fabricated report repudiated");
        fabricatedBatch = batch;
        fabricatedFile = file;
        fabricatedBatchRef = batchRef;
        fabricatedCaptureOperation = operationRef;
        genuineFile = genuine;
    }

    // ----------------------------------------------------------------- 2. the genuine file

    @Test
    @Order(2)
    @DisplayName("the genuine file the fabrication blocked (CONFLICTING_BATCH) is readmitted once"
            + " the batch is repudiated: it parses into the freed identity, is accepted, and its"
            + " capture line settles the reopened capture - the proofs hold")
    void theGenuineFileIsRecovered() throws Exception {
        assertThat(genuineFile).as("case 1 ran").isNotNull();
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);

        HttpResponse<String> readmitted =
                post(FILES + "/" + genuineFile + "/readmission",
                        reasonBody("the standing batch was repudiated as fabricated"),
                        controller.token(), key());
        assertThat(readmitted.statusCode()).as(readmitted.body()).isEqualTo(202);
        assertThat(field(readmitted.body(), "readmitsFileId")).isEqualTo(genuineFile.toString());
        UUID readmission = UUID.fromString(field(readmitted.body(), "fileId"));
        if ("ATTESTATION_REQUIRED".equals(field(readmitted.body(), "authentication"))) {
            HttpResponse<String> attested =
                    attest(readmission, sessionWith(RoleName.RECONCILIATION_OPERATOR));
            assertThat(attested.statusCode()).as(attested.body()).isEqualTo(200);
        }

        assertThat(parseSettled(readmission))
                .as("the repudiated batch freed its live identity")
                .isEqualTo("PARSED");
        assertThat(acceptanceSettled(readmission)).isEqualTo("ACCEPTED");
        UUID batch = batchOf(readmission);
        assertThat(one("SELECT external_batch_ref FROM settlement.batch WHERE id = ?", batch))
                .isEqualTo(fabricatedBatchRef);
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE external_batch_ref = ? AND"
                        + " status NOT IN ('REJECTED', 'REPUDIATED')", fabricatedBatchRef))
                .as("one live batch per identity")
                .isEqualTo(1);

        matchUntilQuiet();
        UUID capture = expectationOf("CARD_CAPTURE", fabricatedCaptureOperation);
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", capture))
                .as("the genuine capture line settles the reopened capture")
                .isEqualTo("SETTLED:10000");
        assertThat(itemsOf(batch))
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");
        assertThat(batchStatus(fabricatedBatch)).isEqualTo("REPUDIATED");
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", fabricatedFile))
                .isEqualTo("ACCEPTED");
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", genuineFile))
                .as("the original is never touched")
                .isEqualTo("REJECTED");
        assertBooksHold("the genuine file accepted");
    }

    // ----------------------------------------------------------------- 3. the statement

    @Test
    @Order(3)
    @DisplayName("a fabricated statement that matched a standing report's remittance is"
            + " repudiated: CASH_AT_BANK reversed - the cash verdict back to its pre-statement"
            + " one - the remittance reopened, the bank items REPUDIATED; the genuine statement"
            + " is then accepted under the same sequence and settles the remittance")
    void aFabricatedStatementIsRepudiated() throws Exception {
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-22");
        String remittanceRef = remittanceRef();
        String operationRef = "op-p8t23-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        seedCapture(operationRef, captureRef, 200_00, day.minusDays(1));
        UUID report =
                acceptedBatch(PSP_SOURCE,
                        pspReport("PSPB-RPD-" + marker, day, captureRef, "200.00", "3.25",
                                "196.75", remittanceRef, "Sale"));
        matchUntilQuiet();
        UUID remittance = expectationOf("REMITTANCE", report.toString());
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .isEqualTo("OPEN");
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        PositionProof.CashVerdict before = proofs().cashOf(EUR).orElseThrow();
        Head head = head("EUR");
        long closing = head.closingMinor() + 196_75 - 50;
        String opening = "C," + day.plusDays(1) + ",EUR," + decimal(head.closingMinor());
        String closed = "C," + day.plusDays(2) + ",EUR," + decimal(closing);
        String credit = ":61:" + day.plusDays(2) + ",C,196.75," + remittanceRef;
        String fee = ":61:" + day.plusDays(2) + ",F,0.50";
        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-RPD-F-" + marker, "EUR", head.sequence() + 1, opening,
                                closed, credit, ":86:Remittance for the day", fee));
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .isEqualTo("SETTLED");
        assertThat(itemsOf(statement))
                .containsExactlyInAnyOrder("BANK_CREDIT:MATCHED", "BANK_FEE:CHECKED");
        UUID recognition = recognitionOf(statement);
        UUID original =
                (UUID) one("SELECT id FROM reconciliation.allocation WHERE expectation_id = ?"
                        + " AND reverses_allocation_id IS NULL", remittance);
        assertBooksHold("the fabricated statement matched");

        HttpResponse<String> proposed =
                propose(proposer, statement.toString(), key(), "the statement is fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertCounts(proposed.body(), 2, 1, 0, 0, 0, 0, false, true);
        HttpResponse<String> approved =
                approve(approver, field(proposed.body(), "resolutionId"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        String entry = field(approved.body(), "journalEntryId");

        assertThat(reversalsOf(recognition)).isEqualTo(1);
        assertThat(entryLines(UUID.fromString(entry)))
                .as("cash leaves the books with the statement that put it there")
                .containsExactlyInAnyOrderElementsOf(mirrored(entryLines(recognition)));
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                        + " reverses_allocation_id = ?", original))
                .isEqualTo(1);
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", remittance))
                .as("the PSP's remittance waits for the genuine cash")
                .isEqualTo("OPEN:0");
        assertThat(itemsOf(statement))
                .containsExactlyInAnyOrder("BANK_CREDIT:REPUDIATED", "BANK_FEE:REPUDIATED");
        assertThat(batchStatus(statement)).isEqualTo("REPUDIATED");
        assertThat(batchStatus(report)).as("the standing report is untouched")
                .isEqualTo("ACCEPTED");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind ="
                        + " 'CARD_CAPTURE' AND operation_ref = ?", operationRef))
                .isEqualTo("SETTLED");
        PositionProof.CashVerdict after = proofs().cashOf(EUR).orElseThrow();
        assertThat(after.ledgerBalance())
                .as("CASH_AT_BANK back where it stood before the statement")
                .isEqualTo(before.ledgerBalance());
        assertThat(after.latestSequence()).isEqualTo(before.latestSequence());
        assertThat(after.explained()).isEqualTo(before.explained()).isTrue();
        assertBooksHold("the fabricated statement repudiated");

        // The genuine statement: the live sequence unique was freed.
        UUID genuine =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-RPD-G-" + marker, "EUR", head.sequence() + 1, opening,
                                closed, credit, ":86:Genuine remittance", fee));
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .isEqualTo("SETTLED");
        assertThat(itemsOf(genuine))
                .containsExactlyInAnyOrder("BANK_CREDIT:MATCHED", "BANK_FEE:CHECKED");
        PositionProof.CashVerdict recovered = proofs().cashOf(EUR).orElseThrow();
        assertThat(recovered.chainClosing().minorUnits()).isEqualTo(closing);
        assertThat(recovered.latestSequence()).isEqualTo(head.sequence() + 1);
        assertBooksHold("the genuine statement accepted");
    }

    // ----------------------------------------------------------------- 4. beneath a statement

    @Test
    @Order(4)
    @DisplayName("a PSP report repudiated beneath a standing statement: the bank item's"
            + " allocation countered first, the item reopened MATCHED -> UNMATCHED with a fresh"
            + " grace, the remittance closed RESOLVED_BY_ADJUSTMENT whole - DR-CR = f = g - (g -"
            + " f), cash untouched, the proofs hold")
    void aReportRepudiatedBeneathAStandingStatement() throws Exception {
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-23");
        String remittanceRef = remittanceRef();
        String operationRef = "op-p8t23-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        seedCapture(operationRef, captureRef, 300_00, day.minusDays(1));
        UUID report =
                acceptedBatch(PSP_SOURCE,
                        pspReport("PSPB-RPD-" + marker, day, captureRef, "300.00", "4.75",
                                "295.25", remittanceRef, "Sale"));
        matchUntilQuiet();
        UUID capture = expectationOf("CARD_CAPTURE", operationRef);
        UUID remittance = expectationOf("REMITTANCE", report.toString());
        Head head = head("EUR");
        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-RPD-S-" + marker, "EUR", head.sequence() + 1,
                                "C," + day.plusDays(1) + ",EUR," + decimal(head.closingMinor()),
                                "C," + day.plusDays(2) + ",EUR,"
                                        + decimal(head.closingMinor() + 295_25 - 50),
                                ":61:" + day.plusDays(2) + ",C,295.25," + remittanceRef,
                                ":86:Remittance for the day",
                                ":61:" + day.plusDays(2) + ",F,0.50"));
        matchUntilQuiet();
        UUID bankItem = itemOf(statement, "BANK_CREDIT");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", bankItem))
                .isEqualTo("MATCHED");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .isEqualTo("SETTLED");
        PositionProof.CashVerdict cashBefore = proofs().cashOf(EUR).orElseThrow();
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        HttpResponse<String> proposed =
                propose(proposer, report.toString(), key(), "the report was mis-normalised");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertCounts(proposed.body(), 2, 2, 1, 0, 0, 0, true, true);
        HttpResponse<String> approved =
                approve(approver, field(proposed.body(), "resolutionId"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);

        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.external_item WHERE id = ?", bankItem))
                .as("the bank item waits for the genuine report's remittance")
                .isEqualTo("UNMATCHED:0");
        assertThat(count("SELECT count(*) FROM reconciliation.external_item WHERE id = ? AND"
                        + " grace_until > now() + interval '47 hours' AND grace_until <= now()"
                        + " + interval '49 hours'", bankItem))
                .as("a fresh grace from the bank rule's 48 hours")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.external_item_event WHERE item_id"
                        + " = ? AND from_status = 'MATCHED' AND to_status = 'UNMATCHED'",
                        bankItem))
                .isEqualTo(1);
        assertThat(one("SELECT status || ':' || allocated_minor::text || ':' ||"
                        + " resolved_minor::text FROM reconciliation.expectation WHERE id = ?",
                        remittance))
                .as("countered first, then closed whole by the repudiation")
                .isEqualTo("RESOLVED_BY_ADJUSTMENT:0:29525");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                        + " reverses_allocation_id IS NOT NULL AND expectation_id IN (?, ?)",
                        capture, remittance))
                .as("one counter per allocation: the capture's and the bank item's")
                .isEqualTo(2);
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("OPEN:0");
        assertThat(itemsOf(report))
                .containsExactlyInAnyOrder("CAPTURE:REPUDIATED", "PROCESSING_FEE:REPUDIATED");
        assertThat(batchStatus(statement)).isEqualTo("ACCEPTED");
        assertThat(itemsOf(statement))
                .containsExactlyInAnyOrder("BANK_CREDIT:UNMATCHED", "BANK_FEE:CHECKED");
        PositionProof.Report after = proofs();
        assertThat(after.cashOf(EUR).orElseThrow().ledgerBalance())
                .as("the cash the bank reported stands")
                .isEqualTo(cashBefore.ledgerBalance());
        assertBooksHold("the report repudiated beneath its statement");
        standingStatement = statement;
    }

    // ----------------------------------------------------------------- 5. released once

    @Test
    @Order(5)
    @DisplayName("an unattributed bank credit: a proposal made while it is parked goes stale once"
            + " a TRANSFER_TO_ACCOUNT releases it (409 ResolutionStale, nothing moved), is"
            + " withdrawn by its proposer alone, and the fresh proposal's approval answers the"
            + " released value with ONE new REPUDIATION item on the opposite side, owned by an"
            + " open PROCESSING_ERROR break - never released twice; the proofs hold")
    void aValueAlreadyReleasedIsAnsweredNeverReleasedTwice() throws Exception {
        String marker = letters(10);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session resolver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session resolverApprover = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        PositionProof.CashVerdict before = proofs().cashOf(GBP).orElseThrow();
        Head head = head("GBP");
        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-RPD-U-" + marker, "GBP", head.sequence() + 1,
                                "C,2026-09-25,GBP," + decimal(head.closingMinor()),
                                "C,2026-09-26,GBP," + decimal(head.closingMinor() + 20_00),
                                ":61:2026-09-26,C,20.00"));
        UUID item = itemOf(statement, "BANK_CREDIT");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("PARKED");
        UUID released =
                (UUID) one("SELECT id FROM reconciliation.suspense_item WHERE external_item_id"
                        + " = ?", item);
        UUID owner =
                (UUID) one("SELECT break_id FROM reconciliation.suspense_item WHERE id = ?",
                        released);
        assertThat(one("SELECT origin || ':' || side || ':' || amount_minor::text FROM"
                        + " reconciliation.suspense_item WHERE id = ?", released))
                .isEqualTo("BANK_UNATTRIBUTED:CREDIT:2000");
        UUID recognition = recognitionOf(statement);

        // (a) Proposed while the value is still parked.
        HttpResponse<String> first =
                propose(proposer, statement.toString(), key(), "the statement is fabricated");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertCounts(first.body(), 1, 0, 0, 0, 0, 1, false, true);
        String stale = field(first.body(), "resolutionId");

        // (b) Another resolution moves the subject: the value transferred to its owner.
        UUID wallet = openWallet(GBP);
        HttpResponse<String> transfer =
                post(BASE + "/breaks/" + owner + "/resolutions",
                        "{\"kind\":\"TRANSFER_TO_ACCOUNT\",\"reasonCode\":\"FUNDS_ATTRIBUTED\","
                                + "\"narrative\":\"the customer funds attributed\","
                                + "\"targetAccountId\":\"" + wallet + "\"}",
                        resolver.token(), key());
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(201);
        HttpResponse<String> transferred =
                approve(resolverApprover, field(transfer.body(), "resolutionId"));
        assertThat(transferred.statusCode()).as(transferred.body()).isEqualTo(200);
        assertThat(one("SELECT released_minor::text FROM reconciliation.suspense_item WHERE id"
                        + " = ?", released))
                .isEqualTo("2000");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("RESOLVED");

        // (c) The stale proposal: refused, nothing moved.
        HttpResponse<String> refused = approve(approver, stale);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("reconciliation.ResolutionStale");
        assertThat(resolutionStatus(stale)).isEqualTo("PROPOSED");
        assertThat(batchStatus(statement)).isEqualTo("ACCEPTED");
        assertThat(reversalsOf(recognition)).isZero();
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("RESOLVED");

        // The proposer alone withdraws it; the retry converges.
        HttpResponse<String> notTheirs = withdraw(approver, stale);
        assertThat(notTheirs.statusCode()).as(notTheirs.body()).isEqualTo(409);
        assertThat(notTheirs.body()).contains("reconciliation.NotTheProposer");
        HttpResponse<String> withdrawn = withdraw(proposer, stale);
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("WITHDRAWN");
        assertThat(field(withdrawn.body(), "settlementBatchId")).isEqualTo(statement.toString());
        assertThat(withdraw(proposer, stale).statusCode()).isEqualTo(200);
        assertThat(audits("reconciliation.ResolutionWithdrawn", stale, proposer)).isEqualTo(1);

        // (d) The fresh proposal answers what was already released.
        HttpResponse<String> second =
                propose(proposer, statement.toString(), key(), "the statement is fabricated");
        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertCounts(second.body(), 1, 0, 0, 0, 1, 0, false, true);
        HttpResponse<String> approved = approve(approver, field(second.body(), "resolutionId"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        String entry = field(approved.body(), "journalEntryId");

        assertThat(entryLines(UUID.fromString(entry)))
                .containsExactlyInAnyOrderElementsOf(mirrored(entryLines(recognition)));
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE origin ="
                        + " 'REPUDIATION' AND origin_ref = ?", released.toString()))
                .as("one answer per released value (UNIQUE origin_ref)")
                .isEqualTo(1);
        assertThat(one("SELECT s.side || ':' || s.amount_minor::text || ':' || trim(s.currency)"
                        + " || ':' || s.released_minor::text || ':' || s.status || ':'"
                        + " || (s.entry_id = ?::uuid)::text || ':' || (s.external_item_id IS"
                        + " NULL)::text || ':' || (s.park_id IS NULL)::text || ':' ||"
                        + " (s.position_account_id IS NULL)::text FROM"
                        + " reconciliation.suspense_item s WHERE s.origin = 'REPUDIATION' AND"
                        + " s.origin_ref = ?", entry, released.toString()))
                .as("the reversal's suspense line, on the side opposite the released item")
                .isEqualTo("DEBIT:2000:GBP:0:OPEN:true:true:true:true");
        assertThat(one("SELECT b.type || ':' || b.cause || ':' || b.status || ':'"
                        + " || (b.suspense_item_id = s.id)::text FROM reconciliation.suspense_item"
                        + " s JOIN reconciliation.break b ON b.id = s.break_id WHERE s.origin ="
                        + " 'REPUDIATION' AND s.origin_ref = ?", released.toString()))
                .as("owned from its transaction by a new open break (INV-REC-09)")
                .isEqualTo("PROCESSING_ERROR:EVIDENCE_REPUDIATED:OPEN:true");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE item_id = ?"
                        + " AND cause = 'REPUDIATION'", released))
                .as("the released item is never released twice")
                .isZero();
        assertThat(one("SELECT released_minor::text FROM reconciliation.suspense_item WHERE id"
                        + " = ?", released))
                .isEqualTo("2000");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("REPUDIATED");
        assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE ledger_account_id = ?",
                        wallet))
                .as("the person's transfer stands: the repudiation never touches the wallet")
                .isEqualTo(1);
        PositionProof.CashVerdict after = proofs().cashOf(GBP).orElseThrow();
        assertThat(after.ledgerBalance()).isEqualTo(before.ledgerBalance());
        assertThat(after.latestSequence()).isEqualTo(before.latestSequence());
        assertThat(after.explained()).isEqualTo(before.explained()).isTrue();
        assertBooksHold("the released value answered");
    }

    // ----------------------------------------------------------------- 6. the refusals

    @Test
    @Order(6)
    @DisplayName("refusals, each writing nothing: an undisposed batch 409 BatchNotDisposed, a"
            + " PARSED and a REPUDIATED batch 409 BatchNotRepudiable, unknown and malformed ids"
            + " 404 BatchNotFound; the key replays the receipt byte for byte, the same key with"
            + " another body 409 api.Conflict, a second proposal 409 ResolutionAlreadyProposed;"
            + " self-decisions and another's withdrawal refused; a reasoned rejection by another"
            + " person leaves the batch ACCEPTED and everything untouched")
    void theRefusals() throws Exception {
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-21");
        String operationRef = "op-p8t23-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        seedCapture(operationRef, captureRef, 40_00, day.minusDays(1));
        String content =
                pspReport("PSPB-RPD-" + marker, day, captureRef, "40.00", "0.85", "39.15",
                        remittanceRef(), "Sale");
        UUID file = pulled(PSP_SOURCE, content);
        assertThat(parseSettled(file)).isEqualTo("PARSED");
        assertThat(acceptanceSettled(file)).isEqualTo("ACCEPTED");
        UUID batch = batchOf(file);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session other = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session third = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        HttpResponse<String> undisposed =
                propose(proposer, batch.toString(), key(), "too early to judge");
        assertThat(undisposed.statusCode()).as(undisposed.body()).isEqualTo(409);
        assertThat(undisposed.body()).contains("reconciliation.BatchNotDisposed");
        assertThat(resolutionsOf(batch)).isZero();
        matchUntilQuiet();
        assertThat(itemsOf(batch))
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");

        // A PARSED batch (uploaded, never attested) and an already-repudiated one.
        UUID unattested =
                uploaded(proposer, PSP_SOURCE, day,
                        pspReport("PSPB-RPD-P-" + marker, day, "PSP-CAP-P" + marker, "50.00",
                                "1.00", "49.00", remittanceRef(), "Sale"));
        assertThat(parseSettled(unattested)).isEqualTo("PARSED");
        UUID parsed = batchOf(unattested);
        HttpResponse<String> notAccepted =
                propose(proposer, parsed.toString(), key(), "never accepted");
        assertThat(notAccepted.statusCode()).as(notAccepted.body()).isEqualTo(409);
        assertThat(notAccepted.body()).contains("reconciliation.BatchNotRepudiable");
        HttpResponse<String> declined =
                post(FILES + "/" + unattested + "/decline", reasonBody("test fixture"),
                        proposer.token(), null);
        assertThat(declined.statusCode()).as(declined.body()).isEqualTo(200);
        assertThat(fabricatedBatch).as("case 1 ran").isNotNull();
        HttpResponse<String> twice =
                propose(proposer, fabricatedBatch.toString(), key(), "already repudiated");
        assertThat(twice.statusCode()).as(twice.body()).isEqualTo(409);
        assertThat(twice.body()).contains("reconciliation.BatchNotRepudiable");
        for (String absent : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> unknown = propose(proposer, absent, key(), "a guess");
            assertThat(unknown.statusCode()).as("%s: %s", absent, unknown.body()).isEqualTo(404);
            assertThat(unknown.body()).contains("reconciliation.BatchNotFound");
        }
        assertThat(resolutionsOf(parsed)).isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE"
                        + " settlement_batch_id = ? AND status = 'APPROVED'", fabricatedBatch))
                .isEqualTo(1);

        // Once: the key replays, the same key with another body conflicts, a second refused.
        String key = key();
        HttpResponse<String> proposed = propose(proposer, batch.toString(), key, "fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertCounts(proposed.body(), 2, 1, 0, 0, 0, 0, true, true);
        HttpResponse<String> replayed = propose(proposer, batch.toString(), key, "fabricated");
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(replayed.body()).as("a lost response replays the receipt")
                .isEqualTo(proposed.body());
        HttpResponse<String> changed =
                propose(proposer, batch.toString(), key, "fabricated, amended");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(409);
        assertThat(changed.body()).contains("api.Conflict");
        for (Session someone : List.of(proposer, other)) {
            HttpResponse<String> again = propose(someone, batch.toString(), key(), "fabricated");
            assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
            assertThat(again.body()).contains("reconciliation.ResolutionAlreadyProposed");
        }
        assertThat(resolutionsOf(batch)).as("one live proposal per batch").isEqualTo(1);
        String resolution = field(proposed.body(), "resolutionId");

        // The decisions' negatives.
        HttpResponse<String> selfApproval = approve(proposer, resolution);
        assertThat(selfApproval.statusCode()).as(selfApproval.body()).isEqualTo(409);
        assertThat(selfApproval.body()).contains("reconciliation.SelfApprovalRefused");
        HttpResponse<String> selfRejection = reject(proposer, resolution, "my own mistake");
        assertThat(selfRejection.statusCode()).as(selfRejection.body()).isEqualTo(409);
        assertThat(selfRejection.body()).contains("reconciliation.SelfApprovalRefused");
        HttpResponse<String> othersWithdrawal = withdraw(other, resolution);
        assertThat(othersWithdrawal.statusCode()).as(othersWithdrawal.body()).isEqualTo(409);
        assertThat(othersWithdrawal.body()).contains("reconciliation.NotTheProposer");
        HttpResponse<String> unreasoned = reject(other, resolution, "   ");
        assertThat(unreasoned.statusCode()).as(unreasoned.body()).isEqualTo(422);
        assertThat(unreasoned.body()).contains("api.ValidationFailed");
        assertThat(resolutionStatus(resolution)).isEqualTo("PROPOSED");

        // A reasoned rejection by another person: nothing else moves.
        String reason = "the evidence was genuine after all";
        HttpResponse<String> rejected = reject(other, resolution, reason);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(field(rejected.body(), "status")).isEqualTo("REJECTED");
        assertThat(rejected.body()).doesNotContain("\"journalEntryId\":\"");
        assertThat(reject(other, resolution, reason).statusCode())
                .as("the rejecter's retry converges")
                .isEqualTo(200);
        HttpResponse<String> late = approve(third, resolution);
        assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
        assertThat(late.body()).contains(NOT_PENDING);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'reconciliation.ResolutionRejected' AND target_id = ? AND actor_id ="
                        + " ? AND reason = ?", resolution, other.actorId(), reason))
                .isEqualTo(1);
        UUID capture = expectationOf("CARD_CAPTURE", operationRef);
        assertThat(batchStatus(batch)).isEqualTo("ACCEPTED");
        assertThat(reversalsOf(recognitionOf(batch))).isZero();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("SETTLED");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", batch.toString()))
                .isEqualTo("OPEN");
        assertThat(itemsOf(batch))
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE expectation_id ="
                        + " ? AND reverses_allocation_id IS NOT NULL", capture))
                .isZero();
        assertBooksHold("the refusals and the rejection");
        raceBatch = batch;
        raceFile = file;
        raceCaptureOperation = operationRef;
    }

    // ----------------------------------------------------------------- 7. ten approvers

    @Test
    @Order(7)
    @DisplayName("ten operators approve one repudiation at once: exactly one 200, the rest 409 -"
            + " one reversal, one counter per allocation, one reopening, one batch edge, one"
            + " event and one approval record, counted in the tables")
    void tenRacingApprovers() throws Exception {
        assertThat(raceBatch).as("case 6 ran").isNotNull();
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                propose(proposer, raceBatch.toString(), key(), "fabricated after all");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String resolution = field(proposed.body(), "resolutionId");
        UUID recognition = recognitionOf(raceBatch);
        UUID capture = expectationOf("CARD_CAPTURE", raceCaptureOperation);
        UUID original =
                (UUID) one("SELECT id FROM reconciliation.allocation WHERE expectation_id = ?"
                        + " AND reverses_allocation_id IS NULL", capture);
        List<Session> approvers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            approvers.add(sessionWith(RoleName.RECONCILIATION_OPERATOR));
        }

        List<HttpResponse<String>> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(approvers.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> racers = new ArrayList<>();
            for (Session approver : approvers) {
                racers.add(pool.submit(() -> {
                    start.await();
                    return approve(approver, resolution);
                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> racer : racers) {
                outcomes.add(racer.get(3, TimeUnit.MINUTES));
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> entries = new ArrayList<>();
        for (HttpResponse<String> outcome : outcomes) {
            if (outcome.statusCode() == 200) {
                entries.add(field(outcome.body(), "journalEntryId"));
            } else {
                assertThat(outcome.statusCode()).as(outcome.body()).isEqualTo(409);
                // Every loser is answered by the machine: blocked on the advisories it reads the
                // row decided, and one whose lock-free pre-reads straddled the winner's commit
                // re-reads the row before refusing - never the half-seen batch.
                assertThat(outcome.body()).contains(NOT_PENDING);
            }
        }
        assertThat(entries).as("exactly one approval lands").hasSize(1);

        assertThat(reversalsOf(recognition)).as("one reversal - ten approvers").isEqualTo(1);
        assertThat(one("SELECT id::text FROM ledger.journal_entry WHERE reverses_entry_id = ?",
                        recognition))
                .isEqualTo(entries.get(0));
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                        + " reverses_allocation_id = ?", original))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                        + " expectation_id = ? AND event_type = 'REOPENED'", capture))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM settlement.batch_event WHERE batch_id = ? AND"
                        + " to_status = 'REPUDIATED'", raceBatch))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ? AND"
                        + " aggregate_id = ?", REPUDIATED_EVENT, raceFile))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND"
                        + " target_id = ?", REPUDIATED_EVENT, raceBatch.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'reconciliation.ResolutionApproved' AND target_id = ?", resolution))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE"
                        + " settlement_batch_id = ? AND status = 'APPROVED'", raceBatch))
                .isEqualTo(1);
        assertThat(batchStatus(raceBatch)).isEqualTo("REPUDIATED");
        assertThat(itemsOf(raceBatch))
                .containsExactlyInAnyOrder("CAPTURE:REPUDIATED", "PROCESSING_FEE:REPUDIATED");
        assertBooksHold("ten racing approvers");
    }

    // ----------------------------------------------------------------- 8. the door

    @Test
    @Order(8)
    @DisplayName("the repudiation door holds RECONCILIATION_RESOLVE - anonymous 401, role-less,"
            + " the controller and the ledger operator 403 - and refuses a keyless request 422"
            + " api.IdempotencyKeyRequired, a reason code other than EVIDENCE_REPUDIATED 422"
            + " ReasonCodeNotAllowed, an unknown code or a card-shaped narrative 422"
            + " api.ValidationFailed - nothing written")
    void theDoorHoldsItsPermission() throws Exception {
        assertThat(standingStatement).as("case 4 ran").isNotNull();
        String path = BASE + "/batches/" + standingStatement + "/repudiation";
        String body = repudiationBody("EVIDENCE_REPUDIATED", "fabricated");
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session ledger = sessionWith(RoleName.LEDGER_OPERATOR);
        Session roleless = rolelessSession();

        assertThat(post(path, body, null, key()).statusCode()).as("anonymous").isEqualTo(401);
        for (Session refused : List.of(roleless, controller, ledger)) {
            assertThat(post(path, body, refused.token(), key()).statusCode())
                    .as("whoever can loosen a tolerance or adjust the ledger cannot repudiate"
                            + " the evidence it would hide")
                    .isEqualTo(403);
        }
        HttpResponse<String> keyless = post(path, body, operator.token(), null);
        assertThat(keyless.statusCode()).as(keyless.body()).isEqualTo(422);
        assertThat(keyless.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> wrongCode =
                post(path, repudiationBody("LOSS_ACCEPTED", "fabricated"), operator.token(),
                        key());
        assertThat(wrongCode.statusCode()).as(wrongCode.body()).isEqualTo(422);
        assertThat(wrongCode.body()).contains("reconciliation.ReasonCodeNotAllowed");
        HttpResponse<String> unknownCode =
                post(path, repudiationBody("NOT_A_CODE", "fabricated"), operator.token(), key());
        assertThat(unknownCode.statusCode()).as(unknownCode.body()).isEqualTo(422);
        assertThat(unknownCode.body()).contains("api.ValidationFailed");
        HttpResponse<String> card =
                post(path, repudiationBody("EVIDENCE_REPUDIATED", "card 4111111111111111"),
                        operator.token(), key());
        assertThat(card.statusCode()).as(card.body()).isEqualTo(422);
        assertThat(card.body()).contains("api.ValidationFailed").doesNotContain("4111");

        assertThat(resolutionsOf(standingStatement)).as("no refused door wrote anything")
                .isZero();
        assertThat(batchStatus(standingStatement)).isEqualTo("ACCEPTED");
    }

    // ----------------------------------------------------------------- 9-11. against the matcher

    @Test
    @Order(9)
    @DisplayName("the matcher first: a proposal over a capture line waiting under grace goes stale"
            + " once its expectation opens and the rematch leg allocates the line - 409"
            + " ResolutionStale, nothing moved, the rematch allocation standing; withdrawn and"
            + " proposed afresh, the approval counters that allocation and the proofs hold")
    void theMatcherFirstMakesTheProposalStale() throws Exception {
        Waiting waiting = waitingCapture(LocalDate.parse("2026-09-20"), "60.00", "1.15", "58.85",
                60_00);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                propose(proposer, waiting.batch().toString(), key(), "the report is fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertCounts(proposed.body(), 2, 0, 0, 0, 0, openBreaksOf(waiting.batch()), true, true);
        String stale = field(proposed.body(), "resolutionId");
        UUID recognition = recognitionOf(waiting.batch());

        openTheCapture(waiting);
        matchUntilQuiet();
        UUID capture = expectationOf("CARD_CAPTURE", waiting.operationRef());
        assertThat(itemStatus(waiting.item())).isEqualTo("MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                        + " external_item_id = ? AND origin = 'REMATCH'", waiting.item()))
                .as("the rematch leg decided the waiting line")
                .isEqualTo(1);
        assertThat(standingAllocationsOf(waiting.item())).isEqualTo(1);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("SETTLED");
        UUID rematched =
                (UUID) one("SELECT id FROM reconciliation.allocation WHERE external_item_id = ?"
                        + " AND reverses_allocation_id IS NULL", waiting.item());

        HttpResponse<String> refused = approve(approver, stale);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("reconciliation.ResolutionStale");
        assertThat(resolutionStatus(stale)).isEqualTo("PROPOSED");
        assertThat(batchStatus(waiting.batch())).isEqualTo("ACCEPTED");
        assertThat(reversalsOf(recognition)).isZero();
        assertThat(itemStatus(waiting.item())).isEqualTo("MATCHED");
        assertThat(standingAllocationsOf(waiting.item()))
                .as("the rematch allocation stands")
                .isEqualTo(1);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("SETTLED");

        HttpResponse<String> withdrawn = withdraw(proposer, stale);
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("WITHDRAWN");
        HttpResponse<String> fresh =
                propose(proposer, waiting.batch().toString(), key(), "the report is fabricated");
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(201);
        assertCounts(fresh.body(), 2, 1, 0, 0, 0, openBreaksOf(waiting.batch()), true, true);
        HttpResponse<String> approved = approve(approver, field(fresh.body(), "resolutionId"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);

        assertThat(reversalsOf(recognition)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                        + " reverses_allocation_id = ?", rematched))
                .as("the rematch allocation countered once")
                .isEqualTo(1);
        assertThat(standingAllocationsOf(waiting.item())).isZero();
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("OPEN:0");
        assertThat(itemsOf(waiting.batch()))
                .containsExactlyInAnyOrder("CAPTURE:REPUDIATED", "PROCESSING_FEE:REPUDIATED");
        assertThat(batchStatus(waiting.batch())).isEqualTo("REPUDIATED");
        assertNothingStandsOnARepudiatedItem();
        assertBooksHold("the matcher first, then a fresh repudiation");
    }

    @Test
    @Order(10)
    @DisplayName("the repudiation first: a capture line waiting under grace is repudiated, then"
            + " its expectation opens and the matcher sweeps - no decision and no allocation for"
            + " the REPUDIATED item (counted), the expectation stays OPEN, the proofs hold")
    void theRepudiationFirstLeavesTheMatcherNothing() throws Exception {
        Waiting waiting = waitingCapture(LocalDate.parse("2026-09-19"), "70.00", "1.30", "68.70",
                70_00);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                propose(proposer, waiting.batch().toString(), key(), "the report is fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertCounts(proposed.body(), 2, 0, 0, 0, 0, openBreaksOf(waiting.batch()), true, true);
        HttpResponse<String> approved = approve(approver, field(proposed.body(), "resolutionId"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(itemsOf(waiting.batch()))
                .containsExactlyInAnyOrder("CAPTURE:REPUDIATED", "PROCESSING_FEE:REPUDIATED");
        long decisions = decisionsOf(waiting.item());

        openTheCapture(waiting);
        matchUntilQuiet();
        UUID capture = expectationOf("CARD_CAPTURE", waiting.operationRef());
        assertThat(decisionsOf(waiting.item()))
                .as("a REPUDIATED item is in no leg's worklist")
                .isEqualTo(decisions);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                        + " external_item_id = ?", waiting.item()))
                .isZero();
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", capture))
                .as("the capture waits for genuine evidence")
                .isEqualTo("OPEN:0");
        assertThat(itemStatus(waiting.item())).isEqualTo("REPUDIATED");
        assertNothingStandsOnARepudiatedItem();
        assertBooksHold("the repudiation first, then the matcher");
    }

    @Test
    @Order(11)
    @DisplayName("a true race: the approval and five matcher sweeps start together over a"
            + " waiting line whose expectation just opened - whatever the interleaving, exactly"
            + " one of {200 with the item REPUDIATED and nothing standing on it, 409"
            + " ResolutionStale with the batch ACCEPTED}, never a 500, no sweep escaping; the"
            + " proofs hold (after a fresh approval when stale)")
    void theApprovalRacesTheMatcher() throws Exception {
        Waiting waiting = waitingCapture(LocalDate.parse("2026-09-18"), "80.00", "1.45", "78.55",
                80_00);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                propose(proposer, waiting.batch().toString(), key(), "the report is fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String resolution = field(proposed.body(), "resolutionId");
        UUID recognition = recognitionOf(waiting.batch());
        openTheCapture(waiting);

        HttpResponse<String> decision;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<HttpResponse<String>> approval = pool.submit(() -> {
                start.await();
                return approve(approver, resolution);
            });
            List<Future<Object>> sweeps = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                sweeps.add(pool.submit(() -> {
                    start.await();
                    matching.sweep();
                    return null;
                }));
            }
            start.countDown();
            decision = approval.get(3, TimeUnit.MINUTES);
            for (Future<Object> sweep : sweeps) {
                sweep.get(3, TimeUnit.MINUTES); // A sweep contains its failures: none escapes.
            }
        } finally {
            pool.shutdownNow();
        }
        matchUntilQuiet(); // Whatever the race left, a later sweep must not change it wrongly.
        UUID capture = expectationOf("CARD_CAPTURE", waiting.operationRef());

        if (decision.statusCode() == 200) {
            assertThat(batchStatus(waiting.batch())).isEqualTo("REPUDIATED");
            assertThat(reversalsOf(recognition)).isEqualTo(1);
            assertThat(itemsOf(waiting.batch()))
                    .containsExactlyInAnyOrder("CAPTURE:REPUDIATED", "PROCESSING_FEE:REPUDIATED");
            assertThat(standingAllocationsOf(waiting.item()))
                    .as("every allocation of the repudiated item countered")
                    .isZero();
            assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                            + " reconciliation.expectation WHERE id = ?", capture))
                    .isEqualTo("OPEN:0");
        } else {
            assertThat(decision.statusCode()).as("never a 500: %s", decision.body())
                    .isEqualTo(409);
            assertThat(decision.body()).contains("reconciliation.ResolutionStale");
            assertThat(batchStatus(waiting.batch())).isEqualTo("ACCEPTED");
            assertThat(reversalsOf(recognition)).isZero();
            assertThat(itemStatus(waiting.item())).isEqualTo("MATCHED");
            assertThat(standingAllocationsOf(waiting.item())).isEqualTo(1);
            assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", capture))
                    .isEqualTo("SETTLED");
            assertBooksHold("the matcher won the race");

            HttpResponse<String> withdrawn = withdraw(proposer, resolution);
            assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
            HttpResponse<String> fresh =
                    propose(proposer, waiting.batch().toString(), key(),
                            "the report is fabricated");
            assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(201);
            HttpResponse<String> approved =
                    approve(approver, field(fresh.body(), "resolutionId"));
            assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
            assertThat(batchStatus(waiting.batch())).isEqualTo("REPUDIATED");
            assertThat(reversalsOf(recognition)).isEqualTo(1);
            assertThat(standingAllocationsOf(waiting.item())).isZero();
            assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                            + " external_item_id = ? AND reverses_allocation_id IS NOT NULL",
                            waiting.item()))
                    .isEqualTo(1);
            assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                            + " reconciliation.expectation WHERE id = ?", capture))
                    .isEqualTo("OPEN:0");
        }
        assertNothingStandsOnARepudiatedItem();
        assertBooksHold("the approval raced the matcher");
    }

    // ----------------------------------------------------------------- 12. the case file

    @Test
    @Order(12)
    @DisplayName("a repudiation that closes a break: the statement's unattributed credit still"
            + " parked, its owner break closed by the approval - one repudiation_closure row per"
            + " closed break naming the resolution, the parked value released once by the"
            + " repudiation (no answer item), and the break's case file lists the REPUDIATE_BATCH"
            + " resolution that closed it; the trace still reads; the proofs hold")
    void aClosedBreaksCaseFileNamesTheRepudiation() throws Exception {
        String marker = letters(10);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        PositionProof.CashVerdict before = proofs().cashOf(GBP).orElseThrow();
        Head head = head("GBP");
        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-RPD-C-" + marker, "GBP", head.sequence() + 1,
                                "C,2026-09-27,GBP," + decimal(head.closingMinor()),
                                "C,2026-09-28,GBP," + decimal(head.closingMinor() + 33_00),
                                ":61:2026-09-28,C,33.00"));
        UUID item = itemOf(statement, "BANK_CREDIT");
        assertThat(itemStatus(item)).isEqualTo("PARKED");
        UUID parked =
                (UUID) one("SELECT id FROM reconciliation.suspense_item WHERE external_item_id"
                        + " = ?", item);
        UUID owner =
                (UUID) one("SELECT break_id FROM reconciliation.suspense_item WHERE id = ?",
                        parked);
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", owner))
                .isNotEqualTo("RESOLVED");

        HttpResponse<String> proposed =
                propose(proposer, statement.toString(), key(), "the statement is fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertCounts(proposed.body(), 1, 0, 0, 0, 0, 1, false, true);
        String resolution = field(proposed.body(), "resolutionId");
        HttpResponse<String> approved = approve(approver, resolution);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);

        // The closure: one row per closed break, naming the repudiation.
        assertThat(count("SELECT count(*) FROM reconciliation.repudiation_closure WHERE"
                        + " resolution_id = ?::uuid", resolution))
                .as("as many closures as the receipt's breaksClosed")
                .isEqualTo(number(proposed.body(), "breaksClosed"));
        assertThat(count("SELECT count(*) FROM reconciliation.repudiation_closure WHERE"
                        + " break_id = ? AND resolution_id = ?::uuid", owner, resolution))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.repudiation_closure c WHERE"
                        + " c.resolution_id = ?::uuid AND NOT EXISTS (SELECT 1 FROM"
                        + " reconciliation.break b WHERE b.id = c.break_id AND b.status ="
                        + " 'RESOLVED')", resolution))
                .as("every break a closure names is RESOLVED")
                .isZero();
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", owner))
                .isEqualTo("RESOLVED");
        assertThat(count("SELECT count(*) FROM reconciliation.repudiation_closure c WHERE"
                        + " c.resolution_id = ?::uuid AND NOT EXISTS (SELECT 1 FROM"
                        + " reconciliation.break_event e JOIN reconciliation.resolution r ON"
                        + " r.id = e.resolution_id WHERE e.break_id = c.break_id AND"
                        + " e.event_type = 'RESOLVED' AND e.resolution_id = c.resolution_id"
                        + " AND r.status = 'APPROVED' AND r.kind = 'REPUDIATE_BATCH')",
                        resolution))
                .as("V015: every closed break's RESOLVED edge names the APPROVED"
                        + " REPUDIATE_BATCH resolution by column - a resolution that names no"
                        + " break of its own, approved after the closures in one transaction")
                .isZero();

        // The parked value: released once by the repudiation - the reversal carried its line.
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE item_id = ?"
                        + " AND cause = 'REPUDIATION' AND amount_minor = 3300", parked))
                .isEqualTo(1);
        assertThat(one("SELECT released_minor::text FROM reconciliation.suspense_item WHERE id"
                        + " = ?", parked))
                .isEqualTo("3300");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE origin ="
                        + " 'REPUDIATION' AND origin_ref = ?", parked.toString()))
                .as("nothing was released elsewhere: nothing to answer")
                .isZero();
        assertThat(itemStatus(item)).isEqualTo("REPUDIATED");

        // The case file names the repudiation that closed the break.
        Session investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> caseFile = get(investigator, BASE + "/breaks/" + owner);
        assertThat(caseFile.statusCode()).as(caseFile.body()).isEqualTo(200);
        assertThat(caseFile.body())
                .as("the closing repudiation in the break's resolutions")
                .contains("\"id\":\"" + resolution + "\",\"kind\":\"REPUDIATE_BATCH\","
                        + "\"status\":\"APPROVED\"");
        HttpResponse<String> trace = get(investigator, BASE + "/breaks/" + owner + "/trace");
        assertThat(trace.statusCode()).as(trace.body()).isEqualTo(200);

        PositionProof.CashVerdict after = proofs().cashOf(GBP).orElseThrow();
        assertThat(after.ledgerBalance()).isEqualTo(before.ledgerBalance());
        assertThat(after.explained()).isEqualTo(before.explained()).isTrue();
        assertBooksHold("a break closed by a repudiation");
    }

    // ----------------------------------------------------------------- 13. a payout return

    @Test
    @Order(13)
    @DisplayName("a payout return applied from the batch stands: the provider's report whose"
            + " RETURNED line the worker applied and the rematch leg allocated is repudiated -"
            + " the payout_return fact and its merchant-payout-return posting untouched, the"
            + " PAYOUT_RETURN expectation reopened to age, the return item REPUDIATED, the"
            + " payout still COMPLETED, PAYOUT_CLEARING's identity explained")
    void aPayoutReturnAppliedFromTheBatchStands() throws Exception {
        payoutProvider.reset();
        payoutProvider.succeedsWith(SimulatedPayoutProvider.PAYOUTS_PATH, 200, PAID);
        PayoutMerchant merchant = fundedMerchant(150_00);
        UUID payout = paidPayout(merchant, 45_00);
        assertBooksHold("a payout paid");

        // Its SETTLED line accepted and matched: the MERCHANT_PAYOUT settled.
        acceptedBatch(PAYOUT_SOURCE,
                new String(payoutReport().with(payoutLine(payout, true)).render(),
                        StandardCharsets.UTF_8));
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind ="
                        + " 'MERCHANT_PAYOUT' AND operation_ref = ?", payout.toString()))
                .isEqualTo("SETTLED");

        // Its RETURNED line: waiting, applied by the worker, allocated by the rematch leg.
        UUID report =
                acceptedBatch(PAYOUT_SOURCE,
                        new String(payoutReport().with(payoutLine(payout, false)).render(),
                                StandardCharsets.UTF_8));
        matchUntilQuiet();
        UUID item = itemOf(report, "PAYOUT_RETURNED");
        assertThat(itemStatus(item)).as("no PAYOUT_RETURN stands yet").isEqualTo("UNMATCHED");
        payoutReturnSweep.sweep();
        String fact =
                (String) one("SELECT id::text || '|' || amount_minor::text || '|' ||"
                        + " journal_entry_id::text || '|' || external_item_ref::text || '|' ||"
                        + " returned_on::text FROM merchant.payout_return WHERE payout_id = ?",
                        payout);
        assertThat(fact).as("the worker applied the return").isNotNull();
        UUID returnEntry =
                (UUID) one("SELECT journal_entry_id FROM merchant.payout_return WHERE payout_id"
                        + " = ?", payout);
        assertThat(one("SELECT idempotency_scope FROM ledger.journal_entry WHERE id = ?",
                        returnEntry))
                .isEqualTo(PostingService.IDEMPOTENCY_SCOPE + ":merchant-payout-return:"
                        + payout);
        List<String> returnLines = entryLines(returnEntry);
        assertThat(returnLines)
                .containsExactlyInAnyOrder("PAYOUT_CLEARING:DEBIT:4500",
                        "MERCHANT_PAYABLE:CREDIT:4500");
        matchUntilQuiet();
        UUID expectation = expectationOf("PAYOUT_RETURN", payout.toString());
        assertThat(itemStatus(item)).isEqualTo("MATCHED");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", expectation))
                .isEqualTo("SETTLED");
        UUID allocation =
                (UUID) one("SELECT id FROM reconciliation.allocation WHERE external_item_id = ?"
                        + " AND reverses_allocation_id IS NULL", item);
        assertBooksHold("the return applied and matched");

        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        boolean remittance =
                count("SELECT count(*) FROM reconciliation.expectation WHERE kind ="
                        + " 'REMITTANCE' AND operation_ref = ?", report.toString()) > 0;
        boolean recognised =
                one("SELECT journal_entry_id FROM settlement.batch WHERE id = ?", report) != null;
        int items =
                (int) count("SELECT count(*) FROM reconciliation.external_item i JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ?", report);
        HttpResponse<String> proposed =
                propose(proposer, report.toString(), key(), "the provider report is fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertCounts(proposed.body(), items, 1, 0, 0, 0, openBreaksOf(report), remittance,
                recognised);
        HttpResponse<String> approved = approve(approver, field(proposed.body(), "resolutionId"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);

        // The merchant fact stands: the fact and its posting untouched, never reversed.
        assertThat(one("SELECT id::text || '|' || amount_minor::text || '|' ||"
                        + " journal_entry_id::text || '|' || external_item_ref::text || '|' ||"
                        + " returned_on::text FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .isEqualTo(fact);
        assertThat(count("SELECT count(*) FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .isEqualTo(1);
        assertThat(entryLines(returnEntry)).containsExactlyInAnyOrderElementsOf(returnLines);
        assertThat(reversalsOf(returnEntry))
                .as("the return's own posting is a merchant fact, never reversed here")
                .isZero();
        assertThat(one("SELECT status FROM merchant.merchant_payout WHERE id = ?", payout))
                .isEqualTo("COMPLETED");

        // The expectation reopened to age; the item and the batch REPUDIATED.
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", expectation))
                .as("the return's expectation waits again for evidence")
                .isEqualTo("OPEN:0");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                        + " expectation_id = ? AND event_type = 'REOPENED'", expectation))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                        + " reverses_allocation_id = ?", allocation))
                .isEqualTo(1);
        assertThat(itemStatus(item)).isEqualTo("REPUDIATED");
        assertThat(batchStatus(report)).isEqualTo("REPUDIATED");
        PositionProof.Report proven = proofs();
        assertThat(proven.verdicts())
                .filteredOn(verdict -> verdict.purpose() == AccountPurpose.PAYOUT_CLEARING)
                .as("PAYOUT_CLEARING: the return's posting against its reopened expectation")
                .isNotEmpty()
                .allSatisfy(verdict -> assertThat(verdict.explained())
                        .as("%s: %s = %s - %s", verdict.currency(), verdict.ledgerBalance(),
                                verdict.openRemainders(), verdict.openItems())
                        .isTrue());
        assertNothingStandsOnARepudiatedItem();
        assertBooksHold("the payout report repudiated beneath its applied return");
    }

    // ----------------------------------------------------------------- 14. ten proposers

    @Test
    @Order(14)
    @DisplayName("C14: ten operators propose the repudiation of one batch at once, each under"
            + " its own key: exactly one 201, nine 409 ResolutionAlreadyProposed, one PROPOSED"
            + " resolution and one proposal record - never a 500")
    void tenRacingProposers() throws Exception {
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-17");
        String operationRef = "op-p8t23-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        // 90.00 under the pinned schedule (1.5% + 0.25): fee 1.60, net 88.40 - a distinct amount.
        seedCapture(operationRef, captureRef, 90_00, day.minusDays(1));
        UUID file =
                pulled(PSP_SOURCE,
                        pspReport("PSPB-RPD-C14-" + marker, day, captureRef, "90.00", "1.60",
                                "88.40", remittanceRef(), "Sale"));
        assertThat(parseSettled(file)).isEqualTo("PARSED");
        assertThat(acceptanceSettled(file)).isEqualTo("ACCEPTED");
        UUID batch = batchOf(file);
        matchUntilQuiet();
        assertThat(itemsOf(batch))
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");
        List<Session> proposers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            proposers.add(sessionWith(RoleName.RECONCILIATION_OPERATOR));
        }

        List<HttpResponse<String>> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(proposers.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> racers = new ArrayList<>();
            for (Session proposer : proposers) {
                String key = key();
                racers.add(pool.submit(() -> {
                    start.await();
                    return propose(proposer, batch.toString(), key, "fabricated, raced");
                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> racer : racers) {
                outcomes.add(racer.get(3, TimeUnit.MINUTES));
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> proposed = new ArrayList<>();
        for (HttpResponse<String> outcome : outcomes) {
            if (outcome.statusCode() == 201) {
                proposed.add(field(outcome.body(), "resolutionId"));
            } else {
                assertThat(outcome.statusCode())
                        .as("C14: every losing proposer is answered 409, never a 500: %s",
                                outcome.body())
                        .isEqualTo(409);
                assertThat(outcome.body())
                        .as("C14: the loser is told a proposal already stands")
                        .contains("reconciliation.ResolutionAlreadyProposed");
            }
        }
        assertThat(proposed).as("C14: exactly one proposal lands - ten proposers").hasSize(1);
        String resolution = proposed.get(0);
        assertThat(resolutionsOf(batch))
                .as("C14: one resolution row per batch, however many raced")
                .isEqualTo(1);
        assertThat(resolutionStatus(resolution)).isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM platform.audit_record a JOIN"
                        + " reconciliation.resolution r ON a.target_id = r.id::text WHERE"
                        + " r.settlement_batch_id = ? AND a.operation ="
                        + " 'reconciliation.ResolutionProposed'", batch))
                .as("C14: one proposal record")
                .isEqualTo(1);
        assertThat(batchStatus(batch)).isEqualTo("ACCEPTED");
        assertThat(reversalsOf(recognitionOf(batch))).isZero();

        // Leave it decided: a reasoned rejection by a person who did not propose.
        HttpResponse<String> rejected =
                reject(sessionWith(RoleName.RECONCILIATION_OPERATOR), resolution,
                        "raced proposal, rejected to close the case");
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(batchStatus(batch)).isEqualTo("ACCEPTED");
        assertBooksHold("ten racing proposers");
    }

    // ----------------------------------------------------------------- 15. recovery

    @Test
    @Order(15)
    @DisplayName("the Phase 8 -> 9 transition (REC-3, REC-8, MI-2, SET-3): a bank credit parked at"
            + " grace and rematched to a report's remittance is reopened by the report's"
            + " repudiation, which releases the remittance's reference; at its next grace it parks"
            + " AGAIN, owned by a new break, and a second expired line of the source is graced in"
            + " the same sweep; the report's own bytes, readmitted, inherit NOTHING (NEW-SEC-1)"
            + " and are accepted into the freed identity only once a distinct second person"
            + " attests the readmission - the remittance takes the freed"
            + " reference, the capture is re-allocated and the parked credit rematched to it; the"
            + " capture's settlement status is CASH_CONFIRMED, its trail naming only standing"
            + " evidence; the proofs hold")
    void aReopenedBankItemParksAgainAndTheGenuineBytesRecoverIt() throws Exception {
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-15");
        String operationRef = "op-p8t23-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        String reference = remittanceRef();
        String strayReference = remittanceRef();
        // 500.00 under the pinned schedule (1.5% + 0.25): fee 7.75, net 492.25.
        Head head = head("EUR");
        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-RPD-R3-" + marker, "EUR", head.sequence() + 1,
                                "C," + day.plusDays(1) + ",EUR," + decimal(head.closingMinor()),
                                "C," + day.plusDays(2) + ",EUR,"
                                        + decimal(head.closingMinor() + 492_25 + 12_34),
                                ":61:" + day.plusDays(2) + ",C,492.25," + reference,
                                ":86:Remittance ahead of its report",
                                ":61:" + day.plusDays(2) + ",C,12.34," + strayReference,
                                ":86:A remittance no report announces"));
        matchUntilQuiet();
        List<UUID> credits =
                rows("SELECT i.id::text FROM reconciliation.external_item i JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ? AND i.line_type = 'BANK_CREDIT' ORDER BY"
                        + " i.amount_minor DESC", statement)
                        .stream().map(UUID::fromString).toList();
        UUID cash = credits.get(0);
        UUID stray = credits.get(1);
        assertThat(itemStatus(cash)).as("no remittance names the credit yet").isEqualTo("UNMATCHED");
        assertThat(itemStatus(stray)).isEqualTo("UNMATCHED");

        // Its grace expires: parked whole, owned by its first break.
        expireGrace(cash, "3 hours");
        matchUntilQuiet();
        assertThat(itemStatus(cash)).isEqualTo("PARKED");
        UUID firstPark =
                (UUID) one("SELECT id FROM reconciliation.suspense_item WHERE external_item_id = ?"
                        + " AND origin = 'RECON_PARK'", cash);
        UUID firstOwner =
                (UUID) one("SELECT break_id FROM reconciliation.suspense_item WHERE id = ?",
                        firstPark);
        assertBooksHold("the early credit parked at grace");

        // The report arrives: its remittance takes the reference, the parked credit rematches.
        seedCapture(operationRef, captureRef, 500_00, day.minusDays(1));
        UUID reportFile =
                pulled(PSP_SOURCE,
                        pspReport("PSPB-RPD-R3-" + marker, day, captureRef, "500.00", "7.75",
                                "492.25", reference, "Sale"));
        assertThat(parseSettled(reportFile)).isEqualTo("PARSED");
        assertThat(acceptanceSettled(reportFile)).isEqualTo("ACCEPTED");
        UUID report = batchOf(reportFile);
        matchUntilQuiet();
        UUID capture = expectationOf("CARD_CAPTURE", operationRef);
        UUID remittance = expectationOf("REMITTANCE", report.toString());
        assertThat(itemStatus(cash)).as("the parked credit rematched").isEqualTo("MATCHED");
        assertThat(one("SELECT status FROM reconciliation.suspense_item WHERE id = ?", firstPark))
                .as("its first park released by the rematch's unpark")
                .isEqualTo("RELEASED");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .isEqualTo("SETTLED");
        assertBooksHold("the report matched beneath the early credit");

        // MI-2's refusal: an accepted file whose batch stands is no readmissible original.
        // The readmitter ALSO holds the ingest permission, so their attestation below meets
        // the domain's submitter rank (409), never the permission check (403).
        Session controller =
                sessionWith(RoleName.RECONCILIATION_CONTROLLER, RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> standing =
                post(FILES + "/" + reportFile + "/readmission",
                        reasonBody("re-parse while the batch still stands"), controller.token(),
                        key());
        assertThat(standing.statusCode()).as(standing.body()).isEqualTo(409);
        assertThat(standing.body()).contains("settlement.FileNotRejected");

        // The report repudiated beneath its statement: the credit reopened, the key released.
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                propose(proposer, report.toString(), key(), "the adapter mis-normalised it");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String resolution = field(proposed.body(), "resolutionId");
        HttpResponse<String> approved = approve(approver, resolution);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(itemStatus(cash)).isEqualTo("UNMATCHED");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .isEqualTo("RESOLVED_BY_ADJUSTMENT");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE"
                        + " expectation_id = ? AND key_kind = 'REMITTANCE_REF' AND"
                        + " released_by_resolution_id = ?::uuid", remittance, resolution))
                .as("REC-8: the closed remittance's reference released by its repudiation")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'reconciliation.ResolutionApproved' AND target_id = ? AND"
                        + " change_summary LIKE '%releasedKeys=1%'", resolution))
                .isEqualTo(1);
        assertBooksHold("the report repudiated beneath its statement");

        // REC-3: both graces expire - the reopened credit first in the worklist - ONE sweep.
        expireGrace(cash, "2 hours");
        expireGrace(stray, "1 hour");
        matching.sweep();
        assertThat(itemStatus(cash))
                .as("REC-3: the reopened credit parks again - its released first park no bar")
                .isEqualTo("PARKED");
        assertThat(rows("SELECT status FROM reconciliation.suspense_item WHERE external_item_id ="
                        + " ? AND origin = 'RECON_PARK' ORDER BY opened_on, status", cash))
                .as("REC-3: one RECON_PARK item per park - the first RELEASED, the second OPEN")
                .containsExactlyInAnyOrder("RELEASED", "OPEN");
        UUID secondOwner =
                (UUID) one("SELECT break_id FROM reconciliation.suspense_item WHERE"
                        + " external_item_id = ? AND origin = 'RECON_PARK' AND status = 'OPEN'",
                        cash);
        assertThat(secondOwner).as("REC-3: the new park is owned by a new break")
                .isNotEqualTo(firstOwner);
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", secondOwner))
                .isEqualTo("OPEN");
        assertThat(itemStatus(stray))
                .as("REC-3: the next expired line of the source is graced in the same sweep")
                .isEqualTo("PARKED");
        assertBooksHold("the reopened credit parked again");

        // MI-2: the report's own bytes readmitted - an ACCEPTED file whose batch is REPUDIATED.
        // NEW-SEC-1: the readmission inherits NOTHING - the four-eyes repudiation verdict is
        // not undone by one person - so it waits for its own attestation.
        HttpResponse<String> readmitted =
                post(FILES + "/" + reportFile + "/readmission",
                        reasonBody("re-parse the genuine bytes after the adapter fix"),
                        controller.token(), key());
        assertThat(readmitted.statusCode())
                .as("MI-2: a repudiated batch's file is readmissible: %s", readmitted.body())
                .isEqualTo(202);
        assertThat(field(readmitted.body(), "authentication"))
                .as("NEW-SEC-1: a repudiated batch's file passes nothing on")
                .isEqualTo("ATTESTATION_REQUIRED");
        UUID readmission = UUID.fromString(field(readmitted.body(), "fileId"));
        assertThat(parseSettled(readmission)).isEqualTo("PARSED");
        for (int sweep = 0; sweep < 3; sweep++) {
            acceptance.sweep();
        }
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", readmission))
                .as("NEW-SEC-1: the accept sweep passes the unattested readmission over")
                .isEqualTo("PARSED");

        // The readmitter's own attestation is refused by the distinct-person rank: the
        // fabrication path stays shut - reinstating a repudiated batch takes two people.
        HttpResponse<String> byReadmitter = attest(readmission, controller);
        assertThat(byReadmitter.statusCode()).as(byReadmitter.body()).isEqualTo(409);
        assertThat(byReadmitter.body()).contains("settlement.AttestationBySubmitter");

        Session distinctAttester = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> bySecondPerson = attest(readmission, distinctAttester);
        assertThat(bySecondPerson.statusCode()).as(bySecondPerson.body()).isEqualTo(200);
        assertThat(acceptanceSettled(readmission))
                .as("attested by a person distinct from every submitter, the accept leg"
                        + " takes it")
                .isEqualTo("ACCEPTED");
        UUID genuine = batchOf(readmission);
        assertThat(one("SELECT external_batch_ref FROM settlement.batch WHERE id = ?", genuine))
                .isEqualTo("PSPB-RPD-R3-" + marker);
        UUID genuineRemittance = expectationOf("REMITTANCE", genuine.toString());
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE"
                        + " expectation_id = ? AND key_kind = 'REMITTANCE_REF' AND key_value = ?"
                        + " AND released_by_resolution_id IS NULL", genuineRemittance, reference))
                .as("REC-8: the genuine remittance holds the freed reference")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                        + " expectation_id = ? AND event_type = 'KEY_COLLISION'",
                        genuineRemittance))
                .isZero();

        matchUntilQuiet();
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", capture))
                .as("MI-2: the genuine capture line re-allocates the reopened capture")
                .isEqualTo("SETTLED:50000");
        assertThat(itemStatus(cash))
                .as("REC-8: the re-parked credit rematched to the genuine remittance")
                .isEqualTo("MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation a WHERE"
                        + " a.external_item_id = ? AND a.expectation_id = ? AND"
                        + " a.reverses_allocation_id IS NULL AND NOT EXISTS (SELECT 1 FROM"
                        + " reconciliation.allocation c WHERE c.reverses_allocation_id = a.id)",
                        cash, genuineRemittance))
                .isEqualTo(1);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                        genuineRemittance))
                .isEqualTo("SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE status <> 'RESOLVED'"
                        + " AND (expectation_id = ? OR external_item_id = ?) AND type IN"
                        + " ('DUPLICATE_INTERNAL', 'DUPLICATE_EXTERNAL', 'MISSING_EXTERNAL')",
                        genuineRemittance, cash))
                .as("no duplicate and no missing remittance stands")
                .isZero();
        assertBooksHold("the genuine bytes recovered");

        // SET-3: the capture's settlement status, over standing allocations only.
        Session investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> status =
                get(investigator, BASE + "/settlement-status?kind=CARD_CAPTURE&operationRef="
                        + operationRef);
        assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
        assertThat(field(status.body(), "status"))
                .as("SET-3: re-settled after a repudiation, cash confirmed: %s", status.body())
                .isEqualTo("CASH_CONFIRMED");
        assertThat(status.body())
                .as("SET-3: the trail names the genuine batch, its remittance and the cash")
                .contains(genuine.toString(), genuineRemittance.toString(), cash.toString())
                .doesNotContain(report.toString())
                .doesNotContain(remittance.toString());
    }

    // ----------------------------------------------------------------- 16. overdue, reopened

    @Test
    @Order(16)
    @DisplayName("the Phase 8 -> 9 transition (REC-4, SET-1): a capture aged overdue and then"
            + " settled by a late report - its MISSING_EXTERNAL closed EVIDENCED - is reopened by"
            + " the report's repudiation with a FRESH MISSING_EXTERNAL raised in the approval,"
            + " following the closed one - ageing, run again, raises none and no second; a"
            + " write-off is admitted against it; the proofs hold")
    void aReopenedOverdueExpectationIsOwnedAtOnce() throws Exception {
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-14");
        String operationRef = "op-p8t23-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        // 550.00 under the pinned schedule: fee 8.50, net 541.50.
        seedCapture(operationRef, captureRef, 550_00, day.minusDays(1));
        UUID capture = expectationOf("CARD_CAPTURE", operationRef);
        reconciliationSweep.sweep();
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE id = ? AND"
                        + " overdue_since IS NOT NULL", capture))
                .as("aged: its window long past")
                .isEqualTo(1);
        UUID overdue =
                (UUID) one("SELECT id FROM reconciliation.break WHERE expectation_id = ? AND"
                        + " type = 'MISSING_EXTERNAL' AND status <> 'RESOLVED'", capture);
        assertThat(overdue).as("ageing raised its MISSING_EXTERNAL").isNotNull();

        UUID report =
                acceptedBatch(PSP_SOURCE,
                        pspReport("PSPB-RPD-R4-" + marker, day, captureRef, "550.00", "8.50",
                                "541.50", remittanceRef(), "Late sale"));
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("SETTLED");
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", overdue))
                .as("the late evidence closed the overdue break")
                .isEqualTo("RESOLVED");
        assertBooksHold("the overdue capture settled late");

        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                propose(proposer, report.toString(), key(), "the late report is fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String resolution = field(proposed.body(), "resolutionId");
        HttpResponse<String> approved = approve(approver, resolution);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);

        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("OPEN:0");
        // Ageing runs again first: it never takes an expectation already overdue (overdue_since
        // is one-way), so whatever owns the reopened remainder now was raised by the approval.
        reconciliationSweep.sweep();
        assertThat(rows("SELECT b.cause || ':' || b.status || ':' || b.value_at_issue_minor::text"
                        + " || ':' || (b.follows_break_id = ?)::text FROM reconciliation.break b"
                        + " WHERE b.expectation_id = ? AND b.type = 'MISSING_EXTERNAL' AND"
                        + " b.status <> 'RESOLVED'", overdue, capture))
                .as("REC-4: the reopened overdue capture is owned by exactly one fresh"
                        + " MISSING_EXTERNAL that follows the break repudiated evidence closed -"
                        + " ageing, run again, raised none and no second")
                .containsExactly("EXPECTATION_OVERDUE:OPEN:55000:true");
        UUID fresh =
                (UUID) one("SELECT id FROM reconciliation.break WHERE expectation_id = ? AND"
                        + " type = 'MISSING_EXTERNAL' AND status <> 'RESOLVED'", capture);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'reconciliation.ResolutionApproved' AND target_id = ? AND"
                        + " change_summary LIKE ?", resolution, "%overdueBreaks=[" + fresh + "]%"))
                .as("REC-4: raised in the approval itself, which names it")
                .isEqualTo(1);

        // A person can now dispose of the remainder.
        Session resolver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> writeOff =
                post(BASE + "/breaks/" + fresh + "/resolutions",
                        "{\"kind\":\"WRITE_OFF\",\"reasonCode\":\"LOSS_ACCEPTED\","
                                + "\"narrative\":\"the capture never settled\"}",
                        resolver.token(), key());
        assertThat(writeOff.statusCode())
                .as("REC-4: a write-off is admitted against the reopened remainder: %s",
                        writeOff.body())
                .isEqualTo(201);
        HttpResponse<String> withdrawn = withdraw(resolver, field(writeOff.body(), "resolutionId"));
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertBooksHold("the overdue capture reopened and owned");
    }

    // ----------------------------------------------------------------- 17. the four refusals

    @Test
    @Order(17)
    @DisplayName("the Phase 8 -> 9 transition (REC-7, T-2): the four shapes this phase refuses,"
            + " each 409 RepudiationNotSupported with nothing written - the ORIGINAL whose parked"
            + " excess a later report's correction offset (at approval, the correction arriving"
            + " after the proposal, and at a fresh proposal), the correcting report holding its"
            + " OFFSET item, a report whose capture a person wrote off RESOLVED_BY_ADJUSTMENT, and"
            + " a report whose remittance a bank item a person RESOLVED matched; the proofs hold")
    void theFourUncompensatedShapesAreRefused() throws Exception {
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-13");
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session resolver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session resolverApprover = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        // (1) and (2): an over-paid capture, its excess parked, then offset by a correction.
        String overRef = "PSP-CAP-O" + marker;
        String overOperation = "op-p8t23-" + UUID.randomUUID();
        seedCapture(overOperation, overRef, 120_00, day.minusDays(1));
        UUID original =
                acceptedBatch(PSP_SOURCE,
                        pspReport("PSPB-RPD-O-" + marker, day, overRef, "130.00", "2.20",
                                "127.80", remittanceRef(), "Over-paid sale"));
        matchUntilQuiet();
        UUID overCapture = expectationOf("CARD_CAPTURE", overOperation);
        UUID overItem = itemOf(original, "CAPTURE");
        assertThat(itemStatus(overItem)).as("the excess parked").isEqualTo("PARKED");
        HttpResponse<String> early =
                propose(proposer, original.toString(), key(), "the over-paying report is wrong");
        assertThat(early.statusCode()).as("proposed before the correction: %s", early.body())
                .isEqualTo(201);
        String earlyResolution = field(early.body(), "resolutionId");
        UUID correction =
                acceptedBatch(PSP_SOURCE,
                        report("PSPB-RPD-OC-" + marker, day, "-10.00", remittanceRef(),
                                adjustmentLine(1, day, overRef, "-10.00")));
        matchUntilQuiet();
        assertThat(itemStatus(overItem)).as("the correction offset the excess")
                .isEqualTo("RESOLVED");
        assertThat(itemsOf(correction)).containsExactly("COUNTERPARTY_ADJUSTMENT:OFFSET");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release r JOIN"
                        + " reconciliation.suspense_item s ON s.id = r.item_id WHERE"
                        + " s.external_item_id = ? AND r.cause = 'CORRECTION_OFFSET'", overItem))
                .isEqualTo(1);
        UUID originalRecognition = recognitionOf(original);
        assertBooksHold("the excess offset by the correction");

        HttpResponse<String> late = approve(approver, earlyResolution);
        assertThat(late.statusCode())
                .as("REC-7: the shape arose after the proposal - refused at approval: %s",
                        late.body())
                .isEqualTo(409);
        assertThat(late.body()).contains("reconciliation.RepudiationNotSupported");
        assertThat(resolutionStatus(earlyResolution)).isEqualTo("PROPOSED");
        assertNothingRepudiated(original, originalRecognition, overCapture, "SETTLED");
        assertThat(withdraw(proposer, earlyResolution).statusCode()).isEqualTo(200);
        HttpResponse<String> again =
                propose(proposer, original.toString(), key(), "the over-paying report is wrong");
        assertThat(again.statusCode())
                .as("REC-7: the corrected original refused at proposal: %s", again.body())
                .isEqualTo(409);
        assertThat(again.body()).contains("reconciliation.RepudiationNotSupported");
        assertThat(resolutionsOf(original)).as("only the withdrawn proposal").isEqualTo(1);
        HttpResponse<String> offset =
                propose(proposer, correction.toString(), key(), "the correction is wrong");
        assertThat(offset.statusCode())
                .as("the correcting report's OFFSET item refused: %s", offset.body())
                .isEqualTo(409);
        assertThat(offset.body()).contains("reconciliation.RepudiationNotSupported");
        assertThat(resolutionsOf(correction)).isZero();
        assertThat(batchStatus(correction)).isEqualTo("ACCEPTED");

        // (3): a short capture whose remainder a person wrote off.
        String shortRef = "PSP-CAP-S" + marker;
        String shortOperation = "op-p8t23-" + UUID.randomUUID();
        seedCapture(shortOperation, shortRef, 150_00, day.minusDays(1));
        UUID shortReport =
                acceptedBatch(PSP_SOURCE,
                        pspReport("PSPB-RPD-S-" + marker, day, shortRef, "140.00", "2.35",
                                "137.65", remittanceRef(), "Short sale"));
        matchUntilQuiet();
        UUID shortCapture = expectationOf("CARD_CAPTURE", shortOperation);
        UUID shortfall =
                (UUID) one("SELECT id FROM reconciliation.break WHERE expectation_id = ? AND"
                        + " status <> 'RESOLVED' ORDER BY raised_at LIMIT 1", shortCapture);
        assertThat(shortfall).as("the shortfall is owned by a break").isNotNull();
        HttpResponse<String> writeOff =
                post(BASE + "/breaks/" + shortfall + "/resolutions",
                        "{\"kind\":\"WRITE_OFF\",\"reasonCode\":\"LOSS_ACCEPTED\","
                                + "\"narrative\":\"the shortfall is accepted\"}",
                        resolver.token(), key());
        assertThat(writeOff.statusCode()).as(writeOff.body()).isEqualTo(201);
        HttpResponse<String> writtenOff =
                approve(resolverApprover, field(writeOff.body(), "resolutionId"));
        assertThat(writtenOff.statusCode()).as(writtenOff.body()).isEqualTo(200);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", shortCapture))
                .isEqualTo("RESOLVED_BY_ADJUSTMENT");
        UUID shortRecognition = recognitionOf(shortReport);
        HttpResponse<String> adjusted =
                propose(proposer, shortReport.toString(), key(), "the short report is wrong");
        assertThat(adjusted.statusCode())
                .as("a capture a person closed RESOLVED_BY_ADJUSTMENT refused: %s",
                        adjusted.body())
                .isEqualTo(409);
        assertThat(adjusted.body()).contains("reconciliation.RepudiationNotSupported");
        assertThat(resolutionsOf(shortReport)).isZero();
        assertNothingRepudiated(shortReport, shortRecognition, shortCapture,
                "RESOLVED_BY_ADJUSTMENT");

        // (4): a remittance an over-paying bank credit matched, its excess transferred.
        String paidRef = "PSP-CAP-P" + marker;
        String paidOperation = "op-p8t23-" + UUID.randomUUID();
        String paidReference = remittanceRef();
        seedCapture(paidOperation, paidRef, 160_00, day.minusDays(1));
        UUID paidReport =
                acceptedBatch(PSP_SOURCE,
                        pspReport("PSPB-RPD-P-" + marker, day, paidRef, "160.00", "2.65",
                                "157.35", paidReference, "Sale"));
        matchUntilQuiet();
        UUID paidCapture = expectationOf("CARD_CAPTURE", paidOperation);
        Head head = head("EUR");
        UUID paidStatement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-RPD-P-" + marker, "EUR", head.sequence() + 1,
                                "C," + day.plusDays(1) + ",EUR," + decimal(head.closingMinor()),
                                "C," + day.plusDays(2) + ",EUR,"
                                        + decimal(head.closingMinor() + 167_35),
                                ":61:" + day.plusDays(2) + ",C,167.35," + paidReference,
                                ":86:Remittance, over-paid"));
        matchUntilQuiet();
        UUID paidCash = itemOf(paidStatement, "BANK_CREDIT");
        assertThat(itemStatus(paidCash)).as("the excess parked").isEqualTo("PARKED");
        UUID excessOwner =
                (UUID) one("SELECT break_id FROM reconciliation.suspense_item WHERE"
                        + " external_item_id = ? AND status <> 'RELEASED'", paidCash);
        UUID wallet = openWallet(EUR);
        HttpResponse<String> transfer =
                post(BASE + "/breaks/" + excessOwner + "/resolutions",
                        "{\"kind\":\"TRANSFER_TO_ACCOUNT\",\"reasonCode\":\"FUNDS_ATTRIBUTED\","
                                + "\"narrative\":\"the over-payment attributed\","
                                + "\"targetAccountId\":\"" + wallet + "\"}",
                        resolver.token(), key());
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(201);
        HttpResponse<String> transferred =
                approve(resolverApprover, field(transfer.body(), "resolutionId"));
        assertThat(transferred.statusCode()).as(transferred.body()).isEqualTo(200);
        assertThat(itemStatus(paidCash)).isEqualTo("RESOLVED");
        UUID paidRecognition = recognitionOf(paidReport);
        HttpResponse<String> resolvedCash =
                propose(proposer, paidReport.toString(), key(), "the paid report is wrong");
        assertThat(resolvedCash.statusCode())
                .as("a bank item a person RESOLVED on the remittance refused: %s",
                        resolvedCash.body())
                .isEqualTo(409);
        assertThat(resolvedCash.body()).contains("reconciliation.RepudiationNotSupported");
        assertThat(resolutionsOf(paidReport)).isZero();
        assertNothingRepudiated(paidReport, paidRecognition, paidCapture, "SETTLED");
        assertThat(itemStatus(paidCash)).isEqualTo("RESOLVED");
        assertBooksHold("the four refusals");
    }

    // ----------------------------------------------------------------- 18. the chain

    @Test
    @Order(18)
    @DisplayName("the Phase 8 -> 9 transition (SET-2): a statement repudiated in the middle of"
            + " its chain opens a STATEMENT_GAP on its accepted successor's run - a fresh one too"
            + " when it had filled that successor's earlier gap - the cash chain broken and"
            + " owned, until the genuine statement's acceptance stitches and closes it EVIDENCED;"
            + " the proofs hold")
    void aMidChainRepudiationOwnsTheHole() throws Exception {
        String marker = letters(10);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Head head = head("GBP");
        long s1 = head.sequence() + 1;
        long c0 = head.closingMinor();
        long c1 = c0 + 1_11;
        long c2 = c1 + 2_22;
        long c3 = c2 + 4_44;

        // Case 1: 1, 2 and 3 stitched - no break - then 2 repudiated.
        acceptedBatch(BANK_SOURCE, gbpStatement("SB-RPD-N1-" + marker, s1, c0, c1, "1.11"));
        UUID second =
                acceptedBatch(BANK_SOURCE,
                        gbpStatement("SB-RPD-N2-" + marker, s1 + 1, c1, c2, "2.22"));
        UUID third =
                acceptedBatch(BANK_SOURCE,
                        gbpStatement("SB-RPD-N3-" + marker, s1 + 2, c2, c3, "4.44"));
        assertThat(gapsOn(third)).as("a stitched chain owns no gap").isEmpty();
        assertBooksHold("three stitched statements");

        repudiate(proposer, approver, second, "the middle statement is fabricated");
        assertThat(gapsOn(third))
                .as("SET-2: the successor of a repudiated statement owns the hole, valued at"
                        + " its opening")
                .containsExactly("OPEN:" + c2);
        assertThat(proofs().cashOf(GBP).orElseThrow().explained())
                .as("the chain is broken until the genuine statement arrives")
                .isFalse();

        acceptedBatch(BANK_SOURCE, gbpStatement("SB-RPD-N2G-" + marker, s1 + 1, c1, c2, "2.22"));
        assertThat(gapsOn(third))
                .as("the genuine statement stitches and closes the hole EVIDENCED")
                .containsExactly("RESOLVED:" + c2);
        assertThat(count("SELECT count(*) FROM reconciliation.break_event e JOIN"
                        + " reconciliation.resolution r ON r.id = e.resolution_id JOIN"
                        + " reconciliation.break b ON b.id = e.break_id JOIN"
                        + " reconciliation.reconciliation_batch rb ON rb.id = b.run_id WHERE"
                        + " rb.batch_id = ? AND b.cause = 'STATEMENT_GAP' AND e.event_type ="
                        + " 'RESOLVED' AND r.kind = 'EVIDENCED'", third))
                .isEqualTo(1);
        assertBooksHold("the genuine middle statement accepted");

        // Case 2: 3 before 2 - 3's gap filled by 2 - then 2 repudiated: a FRESH gap on 3.
        long t1 = s1 + 3;
        long d1 = c3 + 5_55;
        long d2 = d1 + 6_66;
        long d3 = d2 + 7_07;
        acceptedBatch(BANK_SOURCE, gbpStatement("SB-RPD-M1-" + marker, t1, c3, d1, "5.55"));
        UUID lastOfChain =
                acceptedBatch(BANK_SOURCE,
                        gbpStatement("SB-RPD-M3-" + marker, t1 + 2, d2, d3, "7.07"));
        assertThat(gapsOn(lastOfChain)).as("accepted ahead of its predecessor")
                .containsExactly("OPEN:" + d2);
        UUID filler =
                acceptedBatch(BANK_SOURCE,
                        gbpStatement("SB-RPD-M2-" + marker, t1 + 1, d1, d2, "6.66"));
        assertThat(gapsOn(lastOfChain)).as("filled by its predecessor")
                .containsExactly("RESOLVED:" + d2);
        assertBooksHold("the gap filled");

        repudiate(proposer, approver, filler, "the filling statement is fabricated");
        assertThat(gapsOn(lastOfChain))
                .as("SET-2: the filled gap stays RESOLVED; a fresh one owns the reopened hole")
                .containsExactlyInAnyOrder("RESOLVED:" + d2, "OPEN:" + d2);
        acceptedBatch(BANK_SOURCE, gbpStatement("SB-RPD-M2G-" + marker, t1 + 1, d1, d2, "6.66"));
        assertThat(gapsOn(lastOfChain))
                .containsExactlyInAnyOrder("RESOLVED:" + d2, "RESOLVED:" + d2);
        assertBooksHold("the genuine filler accepted");
    }

    @Test
    @Order(19)
    @DisplayName("the Phase 8 -> 9 transition (SET-2), raced: the approval repudiating a mid-chain"
            + " statement and the acceptance of its successor start together - serialised on"
            + " settlement's source row, whichever commits first, the successor's run owns exactly"
            + " ONE open STATEMENT_GAP, never none; the genuine statement then closes it")
    void theSuccessorsAcceptanceRacesTheRepudiation() throws Exception {
        String marker = letters(10);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Head head = head("GBP");
        long first = head.sequence() + 1;
        long c0 = head.closingMinor();
        long c1 = c0 + 8_08;
        long c2 = c1 + 9_09;
        long c3 = c2 + 10_10;
        acceptedBatch(BANK_SOURCE, gbpStatement("SB-RPD-K1-" + marker, first, c0, c1, "8.08"));
        UUID middle =
                acceptedBatch(BANK_SOURCE,
                        gbpStatement("SB-RPD-K2-" + marker, first + 1, c1, c2, "9.09"));
        UUID successorFile =
                pulled(BANK_SOURCE,
                        gbpStatement("SB-RPD-K3-" + marker, first + 2, c2, c3, "10.10"));
        assertThat(parseSettled(successorFile)).isEqualTo("PARSED");
        HttpResponse<String> proposed =
                propose(proposer, middle.toString(), key(), "the middle statement is fabricated");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String resolution = field(proposed.body(), "resolutionId");

        HttpResponse<String> decision;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<HttpResponse<String>> approval = pool.submit(() -> {
                start.await();
                return approve(approver, resolution);
            });
            Future<Object> accepting = pool.submit(() -> {
                start.await();
                acceptance.sweep();
                return null;
            });
            start.countDown();
            decision = approval.get(3, TimeUnit.MINUTES);
            accepting.get(3, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        assertThat(decision.statusCode()).as("never a 500: %s", decision.body()).isEqualTo(200);
        assertThat(acceptanceSettled(successorFile)).isEqualTo("ACCEPTED");
        UUID successor = batchOf(successorFile);
        assertThat(batchStatus(middle)).isEqualTo("REPUDIATED");
        assertThat(gapsOn(successor))
                .as("SET-2: whichever committed first, exactly one open gap owns the hole")
                .containsExactly("OPEN:" + c2);

        acceptedBatch(BANK_SOURCE, gbpStatement("SB-RPD-K2G-" + marker, first + 1, c1, c2, "9.09"));
        assertThat(gapsOn(successor)).containsExactly("RESOLVED:" + c2);
        assertBooksHold("the raced chain recovered");
    }

    // ----------------------------------------------------------------- 20. the run first

    @Test
    @Order(20)
    @DisplayName("the Phase 8 -> 9 transition (MI-8): a batch whose run blocked at its completion -"
            + " every item decided, the completion refused three times, the source held - is"
            + " refused 409 BatchNotDisposed at proposal, and a proposal standing from before the"
            + " correction is refused at approval, nothing written either time; requeued, the run"
            + " completes and the batch is then repudiated; the proofs hold")
    void aBatchIsRepudiatedOnlyOnceItsRunCompleted() throws Exception {
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-12");
        String operationRef = "op-p8t23-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-B" + marker;
        seedCapture(operationRef, captureRef, 210_00, day.minusDays(1));
        UUID batch =
                acceptedBatch(PSP_SOURCE,
                        pspReport("PSPB-RPD-B-" + marker, day, captureRef, "210.00", "3.40",
                                "206.60", remittanceRef(), "Sale"));
        UUID run = (UUID) one("SELECT id FROM reconciliation.reconciliation_batch WHERE"
                + " batch_id = ? AND kind = 'BATCH'", batch);
        UUID capture = expectationOf("CARD_CAPTURE", operationRef);
        UUID recognition = recognitionOf(batch);

        // MI-8's chunk boundary: two items a chunk, so both decisions commit in one chunk and the
        // completion falls to the next - refused for this run alone by the owner's fixture
        // trigger, standing in for whatever fails a completing chunk, three sweeps in a row.
        Matching twoPerChunk =
                new Matching(
                        matchingStore, matchingRules, breakRegister, suspense, resolutions,
                        internalReferenceLookup, ledgerAccountStore, outboxWriter, auditWriter,
                        IDS, CLOCK, new Matching.Config(2, 3), reconciliationTransactionRunner);
        refuseCompletionOf(run);
        try {
            for (int sweep = 0; sweep < 3; sweep++) {
                twoPerChunk.sweep();
            }
        } finally {
            allowCompletions();
        }
        assertThat(one("SELECT status || ':' || failures FROM"
                        + " reconciliation.reconciliation_batch WHERE id = ?", run))
                .as("every item decided, the completion refused three times")
                .isEqualTo("BLOCKED:3");
        assertThat(itemsOf(batch))
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");
        UUID runBlocked =
                (UUID) one("SELECT id FROM reconciliation.break WHERE run_id = ? AND cause ="
                        + " 'RUN_BLOCKED' AND status <> 'RESOLVED'", run);
        assertThat(runBlocked).as("the blocked run holds its source visibly").isNotNull();

        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        HttpResponse<String> early =
                propose(proposer, batch.toString(), key(), "the report is fabricated");
        assertThat(early.statusCode())
                .as("MI-8: refused at proposal while the run is BLOCKED: %s", early.body())
                .isEqualTo(409);
        assertThat(early.body()).contains("reconciliation.BatchNotDisposed");
        assertThat(resolutionsOf(batch)).isZero();

        // A proposal standing from before the correction, written as the door then wrote it:
        // the approval re-judges the run and refuses, nothing written.
        UUID standing = IDS.next();
        Actor standingProposer = new Actor(proposer.actorId(), ActorType.CUSTOMER);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            byte[] digest = new byte[32];
            RANDOMNESS.nextBytes(digest);
            Instant now = Instant.now(CLOCK);
            CorrelationId correlation = CorrelationId.generate(IDS);
            new JdbcRepudiationStore().insert(app, new RepudiationStore.NewRepudiation(
                    standing, batch, "the report is fabricated", Money.ofPersisted(210_00, EUR, 2),
                    (UUID) one("SELECT rule_set_id FROM reconciliation.reconciliation_batch WHERE"
                            + " id = ?", run),
                    digest, standingProposer, now, correlation));
            new JdbcResolutionStore().appendEvent(
                    app, standing, Optional.empty(), ResolutionStatus.PROPOSED, standingProposer,
                    Optional.empty(), now, correlation);
            app.commit();
        }
        HttpResponse<String> late = approve(approver, standing.toString());
        assertThat(late.statusCode())
                .as("MI-8: refused at approval while the run is BLOCKED: %s", late.body())
                .isEqualTo(409);
        assertThat(late.body()).contains("reconciliation.BatchNotDisposed");
        assertThat(resolutionStatus(standing.toString())).isEqualTo("PROPOSED");
        assertNothingRepudiated(batch, recognition, capture, "SETTLED");
        assertThat(itemsOf(batch))
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");
        assertThat(withdraw(proposer, standing.toString()).statusCode()).isEqualTo(200);

        HttpResponse<String> requeued =
                post(BASE + "/runs/" + run + "/requeue",
                        reasonBody("the completing chunk's failure is fixed"), controller.token(),
                        null);
        assertThat(requeued.statusCode()).as(requeued.body()).isEqualTo(200);
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                        run))
                .isEqualTo("COMPLETED");
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", runBlocked))
                .as("the completion explains its blocked run")
                .isEqualTo("RESOLVED");
        repudiate(proposer, approver, batch, "the report is fabricated");
        assertThat(itemsOf(batch))
                .containsExactlyInAnyOrder("CAPTURE:REPUDIATED", "PROCESSING_FEE:REPUDIATED");
        assertThat(reversalsOf(recognition)).isEqualTo(1);
        assertNothingStandsOnARepudiatedItem();
        assertBooksHold("MI-8: repudiated once its run completed");
    }

    /** A repudiation proposed by one person and approved by another: 201, then 200. */
    private void repudiate(Session proposer, Session approver, UUID batch, String narrative)
            throws Exception {
        HttpResponse<String> proposed = propose(proposer, batch.toString(), key(), narrative);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = approve(approver, field(proposed.body(), "resolutionId"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(batchStatus(batch)).isEqualTo("REPUDIATED");
    }

    /** One GBP statement of the chain: one unattributed credit, opening and closing given. */
    private static String gbpStatement(
            String reference, long sequence, long openingMinor, long closingMinor,
            String credit) {
        return statement(reference, "GBP", sequence,
                "C,2026-09-29,GBP," + decimal(openingMinor),
                "C,2026-09-30,GBP," + decimal(closingMinor),
                ":61:2026-09-30,C," + credit);
    }

    /** The STATEMENT_GAP breaks on the batch's run, as status:value. */
    private static List<String> gapsOn(UUID batch) throws SQLException {
        return rows("SELECT b.status || ':' || b.value_at_issue_minor::text FROM"
                + " reconciliation.break b JOIN reconciliation.reconciliation_batch r ON r.id ="
                + " b.run_id WHERE r.batch_id = ? AND b.cause = 'STATEMENT_GAP'", batch);
    }

    /** A refused repudiation wrote nothing: the batch, its recognition, its capture as they were. */
    private static void assertNothingRepudiated(
            UUID batch, UUID recognition, UUID capture, String captureStatus)
            throws SQLException {
        assertThat(batchStatus(batch)).as("nothing written: the batch").isEqualTo("ACCEPTED");
        assertThat(reversalsOf(recognition)).as("nothing written: no reversal").isZero();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", capture))
                .as("nothing written: the capture").isEqualTo(captureStatus);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation a JOIN"
                        + " reconciliation.external_item i ON i.id = a.external_item_id JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ? AND a.reverses_allocation_id IS NOT NULL", batch))
                .as("nothing written: no counter-allocation")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.external_item i JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ? AND i.status = 'REPUDIATED'", batch))
                .as("nothing written: no item repudiated")
                .isZero();
    }

    /**
     * The owner's fixture trigger (MI-8): this run's completion refused for every writer, as a
     * completing chunk that fails is - the run blocks with every item decided.
     */
    private static void refuseCompletionOf(UUID run) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            execute(owner, "CREATE OR REPLACE FUNCTION reconciliation.mi8_refuse_completion()"
                    + " RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION"
                    + " 'MI-8 fixture: the completing chunk fails'; END; $$");
            execute(owner, "CREATE TRIGGER mi8_refuse_completion BEFORE UPDATE ON"
                    + " reconciliation.reconciliation_batch FOR EACH ROW WHEN (NEW.status ="
                    + " 'COMPLETED' AND NEW.id = '" + run + "'::uuid) EXECUTE FUNCTION"
                    + " reconciliation.mi8_refuse_completion()");
        }
    }

    /** The fixture trigger dropped: completions commit again. */
    private static void allowCompletions() throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            execute(owner, "DROP TRIGGER IF EXISTS mi8_refuse_completion ON"
                    + " reconciliation.reconciliation_batch");
            execute(owner, "DROP FUNCTION IF EXISTS reconciliation.mi8_refuse_completion()");
        }
    }

    /** The item's grace moved into the past by {@code interval} - the clock's work, done now. */
    private static void expireGrace(UUID item, String interval) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "UPDATE reconciliation.external_item SET grace_until = now() - ?::interval"
                    + " WHERE id = ? AND status = 'UNMATCHED'", interval, item);
        }
    }

    /** A PSP report of the given detail lines, its trailer's count and net stated. */
    private static String report(
            String batchRef, LocalDate day, String net, String remittanceRef, String... lines) {
        StringBuilder text =
                new StringBuilder("H,SIM_PSP_CSV,1," + batchRef + ",EUR," + day + "\n");
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.append("T,").append(lines.length).append(',').append(net).append(',')
                .append(remittanceRef).append('\n').toString();
    }

    /** A counterparty correction naming its original line's capture reference. */
    private static String adjustmentLine(
            int seq, LocalDate day, String originalRef, String signedAmount) {
        return "D," + seq + ",ADJUSTMENT," + signedAmount + ",,EUR," + day + ",,," + originalRef
                + ",,,,Counterparty correction";
    }

    /** A merchant with an effective destination and a funded EUR payable. */
    private record PayoutMerchant(MerchantId id, LedgerAccountId payable) {}

    /**
     * {@code PayoutReturnDatabaseTest}'s funding: the merchant and its destination inserted, the
     * payable funded by a capture-shaped entry (DR settlement clearing / CR payable) whose
     * capture expectation opens through the live port - every clearing line explained.
     */
    private PayoutMerchant fundedMerchant(long fundingMinor) throws SQLException {
        MerchantId merchant = MerchantId.next(IDS);
        OffsetDateTime created =
                OffsetDateTime.ofInstant(
                        Instant.now(CLOCK).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                            + " settlement_currency, status, created_at, status_changed_at)"
                            + " VALUES (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', ?, ?)",
                    merchant.value(), UUID.randomUUID(), created, created);
            execute(app,
                    "INSERT INTO merchant.payout_destination (id, merchant_id,"
                            + " destination_reference, display_suffix, status, proposed_by,"
                            + " proposed_at, proposal_reason, approved_by, approved_at,"
                            + " cooling_off_until, effective_at) VALUES (?, ?, ?, '3000',"
                            + " 'EFFECTIVE', 'fixture-a', now() - interval '4 days', 'fixture',"
                            + " 'fixture-b', now() - interval '4 days', now() - interval"
                            + " '1 day', now() - interval '1 hour')",
                    IDS.next(), merchant.value(),
                    "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        LedgerAccountId payable =
                asOperator(uow -> ledgerAccountStore
                        .createOrConverge(
                                uow,
                                LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                                        AccountPurpose.MERCHANT_PAYABLE, EUR, merchant.value()))
                        .account()
                        .id());
        Money funding = Money.ofPersisted(fundingMinor, EUR, 2);
        asOperator(uow -> {
            LedgerAccount clearing =
                    new ChartOfAccounts<>(ledgerAccountStore)
                            .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, EUR);
            LocalDate today = LocalDate.now(CLOCK);
            UUID reference = UUID.randomUUID();
            PostingResult posted =
                    postingService.post(
                            uow,
                            new PostingCommand(
                                    "repudiation-payout-fixture:" + reference, today, today,
                                    reference.toString(),
                                    List.of(new JournalLine(clearing.id(), Direction.DEBIT,
                                                    funding),
                                            new JournalLine(payable, Direction.CREDIT,
                                                    funding))));
            settlementExpectations.open(
                    uow,
                    new SettlementExpectations.Opening(
                            SettlementExpectations.Kind.CARD_CAPTURE,
                            reference.toString(),
                            "repudiation-payout-fixture:" + reference,
                            AccountPurpose.SETTLEMENT_CLEARING,
                            clearing.id(),
                            posted.entryId(),
                            Optional.empty(),
                            List.of(new SettlementExpectations.Key(
                                    SettlementExpectations.ReferenceKind.PSP_CAPTURE_REF,
                                    "fx_" + letters(12))),
                            Correlation.startingWith(CorrelationId.generate(IDS))));
            return posted;
        });
        return new PayoutMerchant(merchant, payable);
    }

    /** A payout through the REAL merchant flow, paid by the simulated provider: COMPLETED. */
    private UUID paidPayout(PayoutMerchant merchant, long minor) {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(merchant.id().value().toString(), ActorType.MERCHANT))) {
            MerchantPayouts.Initiated initiated =
                    payouts.initiate(
                            new MerchantPayouts.InitiateCommand(
                                    merchant.id(), Money.ofPersisted(minor, EUR, 2),
                                    "pay-" + UUID.randomUUID(), Optional.empty(),
                                    Optional.empty()));
            assertThat(initiated.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
            return initiated.payout().value();
        }
    }

    /** The provider's line for a payout, read off {@code merchant.merchant_payout}. */
    private static SimulatedPayoutReports.Entry payoutLine(UUID payout, boolean settled)
            throws SQLException {
        String stored =
                (String) one("SELECT provider_reference || '|' || provider_idempotency_reference"
                        + " || '|' || amount_minor::text || '|' || scale::text FROM"
                        + " merchant.merchant_payout WHERE id = ?", payout);
        String[] fields = stored.split("\\|");
        assertThat(fields[3]).as("a two-decimal payout").isEqualTo("2");
        String amount = decimal(Long.parseLong(fields[2]));
        return settled
                ? SimulatedPayoutReports.Entry.settled(amount, "0.25", fields[0], fields[1])
                : SimulatedPayoutReports.Entry.returned(amount, fields[0], fields[1]);
    }

    private static SimulatedPayoutReports payoutReport() {
        return new SimulatedPayoutReports("PAYDAY-" + letters(10), "EUR",
                LocalDate.now(CLOCK).toString(),
                "PAY-REM-23" + (10_000_000 + RANDOMNESS.nextInt(89_999_999)));
    }

    private <R> R asOperator(Function<Connection, R> work) throws SQLException {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                R result = work.apply(app);
                app.commit();
                return result;
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    private HttpResponse<String> get(Session reader, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + reader.token())
                        .GET()
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A fresh PSP batch whose one capture line no expectation names yet: swept, waiting. */
    private record Waiting(
            UUID batch, UUID item, String operationRef, String captureRef, LocalDate day,
            long grossMinor) {}

    private Waiting waitingCapture(
            LocalDate day, String gross, String fee, String net, long grossMinor)
            throws Exception {
        String marker = letters(10);
        String captureRef = "PSP-CAP-" + marker;
        UUID batch =
                acceptedBatch(PSP_SOURCE,
                        pspReport("PSPB-RPD-W-" + marker, day, captureRef, gross, fee, net,
                                remittanceRef(), "Sale"));
        matchUntilQuiet();
        assertThat(itemsOf(batch))
                .as("the capture line waits under grace: no expectation names it yet")
                .containsExactlyInAnyOrder("CAPTURE:UNMATCHED", "PROCESSING_FEE:CHECKED");
        assertBooksHold("a capture line waiting under grace");
        return new Waiting(batch, itemOf(batch, "CAPTURE"), "op-p8t23-" + UUID.randomUUID(),
                captureRef, day, grossMinor);
    }

    /** The capture the waiting line describes, posted and expected - opened after its decision. */
    private void openTheCapture(Waiting waiting) throws Exception {
        seedCapture(waiting.operationRef(), waiting.captureRef(), waiting.grossMinor(),
                waiting.day().minusDays(1));
    }

    /** The open breaks on the batch's run or its items - what a repudiation would close. */
    private static int openBreaksOf(UUID batchId) throws SQLException {
        return (int) count("SELECT count(*) FROM reconciliation.break b WHERE b.status <>"
                + " 'RESOLVED' AND (b.run_id = (SELECT id FROM"
                + " reconciliation.reconciliation_batch WHERE batch_id = ?) OR"
                + " b.external_item_id IN (SELECT i.id FROM reconciliation.external_item i JOIN"
                + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id ="
                + " ?))", batchId, batchId);
    }

    private static String itemStatus(UUID itemId) throws SQLException {
        return (String) one("SELECT status FROM reconciliation.external_item WHERE id = ?",
                itemId);
    }

    private static long decisionsOf(UUID itemId) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", itemId);
    }

    /** Positive allocations of the item no counter-allocation names. */
    private static long standingAllocationsOf(UUID itemId) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.allocation a WHERE"
                + " a.external_item_id = ? AND a.reverses_allocation_id IS NULL AND NOT EXISTS"
                + " (SELECT 1 FROM reconciliation.allocation c WHERE c.reverses_allocation_id ="
                + " a.id)", itemId);
    }

    /** No REPUDIATED item anywhere holds an uncountered allocation (INV-REC-07). */
    private static void assertNothingStandsOnARepudiatedItem() throws SQLException {
        assertThat(count("SELECT count(*) FROM reconciliation.allocation a JOIN"
                        + " reconciliation.external_item i ON i.id = a.external_item_id WHERE"
                        + " i.status = 'REPUDIATED' AND a.reverses_allocation_id IS NULL AND NOT"
                        + " EXISTS (SELECT 1 FROM reconciliation.allocation c WHERE"
                        + " c.reverses_allocation_id = a.id)"))
                .as("a repudiated item's every allocation is countered")
                .isZero();
    }

    // ----------------------------------------------------------------- the books

    /**
     * Every proof explained, absolutely (the suite runs apart): each position identity, each
     * suspense identity, each cash chain; no line on a reconciled position unknown; no open
     * suspense value unowned; the trial balance in balance.
     */
    private void assertBooksHold(String when) throws SQLException {
        PositionProof.Report report = proofs();
        assertThat(report.verdicts())
                .as("%s: the position identities", when)
                .allSatisfy(verdict -> assertThat(verdict.explained())
                        .as("%s: %s %s balance %s = remainders %s - items %s", when,
                                verdict.purpose(), verdict.currency(), verdict.ledgerBalance(),
                                verdict.openRemainders(), verdict.openItems())
                        .isTrue());
        assertThat(report.suspenseVerdicts())
                .as("%s: the suspense identities", when)
                .allSatisfy(verdict -> assertThat(verdict.explained())
                        .as("%s: suspense %s balance %s = CR %s - DR %s + unadopted %s", when,
                                verdict.currency(), verdict.ledgerBalance(),
                                verdict.creditRemainders(), verdict.debitRemainders(),
                                verdict.unadoptedParkings())
                        .isTrue());
        assertThat(report.cashVerdicts())
                .as("%s: the cash chains", when)
                .allSatisfy(verdict -> assertThat(verdict.explained())
                        .as("%s: cash %s balance %s = chain %s (unbroken %s)", when,
                                verdict.currency(), verdict.ledgerBalance(),
                                verdict.chainClosing(), verdict.unbroken())
                        .isTrue());
        assertThat(report.unattributedByPurpose().values())
                .as("%s: every line on a reconciled position is known %s", when,
                        report.unattributedByPurpose())
                .allSatisfy(unknown -> assertThat(unknown).isZero());
        assertThat(report.suspenseUnowned())
                .as("%s: every open suspense value owned (INV-REC-09)", when)
                .isZero();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(new TrialBalance().sweep(app).outOfBalance())
                    .as("%s: the trial balance (INV-ACC-01)", when)
                    .isEmpty();
            app.rollback();
        }
    }

    private PositionProof.Report proofs() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return proof.sweep(app);
            } finally {
                app.rollback();
            }
        }
    }

    private static void assertCounts(
            String receipt,
            int items,
            int counterAllocations,
            int reopenedItems,
            int unparks,
            int answered,
            int breaksClosed,
            boolean remittanceClosed,
            boolean reversesRecognition) {
        assertThat(number(receipt, "items")).as("items: %s", receipt).isEqualTo(items);
        assertThat(number(receipt, "counterAllocations")).as("counters: %s", receipt)
                .isEqualTo(counterAllocations);
        assertThat(number(receipt, "reopenedItems")).as("reopened: %s", receipt)
                .isEqualTo(reopenedItems);
        assertThat(number(receipt, "unparks")).as("unparks: %s", receipt).isEqualTo(unparks);
        assertThat(number(receipt, "answered")).as("answered: %s", receipt).isEqualTo(answered);
        assertThat(number(receipt, "breaksClosed")).as("breaks: %s", receipt)
                .isEqualTo(breaksClosed);
        assertThat(receipt)
                .contains("\"remittanceClosed\":" + remittanceClosed)
                .contains("\"reversesRecognition\":" + reversesRecognition);
    }

    // ----------------------------------------------------------------- seeding

    /**
     * A captured card sale seeded WITH its posting (DR the card clearing / CR a wallet) and its
     * {@code CARD_CAPTURE} expectation naming that line, keyed by the PSP's capture reference
     * under the PSP source and expected three days on (the report's line falls inside the
     * two-day tolerance) - the clearing identity holds at every step.
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

    /** An owned wallet in {@code currency}: the transfer template's permitted target. */
    private UUID openWallet(CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID account =
                    ledgerAccountStore
                            .createOrConverge(
                                    app,
                                    LedgerAccount.owned(
                                            IDS, CLOCK, AccountType.LIABILITY,
                                            AccountPurpose.CUSTOMER_WALLET, currency,
                                            IDS.next()))
                            .account()
                            .id()
                            .value();
            app.commit();
            return account;
        }
    }

    /** One SALE with its fee, the trailer's net and remittance reference; letters elsewhere. */
    private static String pspReport(
            String batchRef,
            LocalDate day,
            String captureRef,
            String gross,
            String fee,
            String net,
            String remittanceRef,
            String narrative) {
        return "H,SIM_PSP_CSV,1," + batchRef + ",EUR," + day + "\n"
                + "D,1,SALE," + gross + "," + fee + ",EUR," + day + ",,," + captureRef
                + ",,,ORD-" + letters(8) + "," + narrative + "\n"
                + "T,1," + net + "," + remittanceRef + "\n";
    }

    private static String statement(
            String reference, String currency, long sequence, String opening, String closing,
            String... body) {
        StringBuilder text =
                new StringBuilder()
                        .append(":20:").append(reference).append('\n')
                        .append(":25:SIMBANK-").append(currency).append("-01\n")
                        .append(":28C:").append(sequence).append('\n')
                        .append(":60F:").append(opening).append('\n');
        for (String record : body) {
            text.append(record).append('\n');
        }
        return text.append(":62F:").append(closing).append('\n').toString();
    }

    /** The head of a currency's accepted statement chain: its sequence and closing (0, 0). */
    private record Head(long sequence, long closingMinor) {}

    private static Head head(String currency) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT b.statement_sequence, b.closing_minor FROM"
                                        + " settlement.batch b JOIN settlement.source s ON s.id"
                                        + " = b.source_id WHERE s.code = ? AND b.currency = ?"
                                        + " AND b.status = 'ACCEPTED' AND b.statement_sequence"
                                        + " IS NOT NULL ORDER BY b.statement_sequence DESC"
                                        + " LIMIT 1")) {
            read.setString(1, BANK_SOURCE);
            read.setString(2, currency);
            try (ResultSet row = read.executeQuery()) {
                Head head = row.next() ? new Head(row.getLong(1), row.getLong(2)) : new Head(0, 0);
                assertThat(head.closingMinor()).as("a credit closing to continue from")
                        .isNotNegative();
                return head;
            }
        }
    }

    /** Minor units as the formats' two-decimal text - integer arithmetic only. */
    private static String decimal(long minor) {
        return (minor / 100) + "." + String.format("%02d", minor % 100);
    }

    private UUID pulled(String source, String content) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            FileReception.Result result =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    reception.receive(
                                            uow,
                                            new FileReception.Delivery(
                                                    source,
                                                    DeliveryChannel.PULL,
                                                    content.getBytes(StandardCharsets.UTF_8),
                                                    Optional.empty(),
                                                    Actor.SYSTEM,
                                                    SettlementAuditAction
                                                            .SETTLEMENT_FILE_UPLOADED,
                                                    CorrelationContext.current()
                                                            .orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private UUID acceptedBatch(String source, String content) throws SQLException {
        UUID fileId = pulled(source, content);
        assertThat(parseSettled(fileId)).isEqualTo("PARSED");
        assertThat(acceptanceSettled(fileId)).isEqualTo("ACCEPTED");
        return batchOf(fileId);
    }

    /** Uploads over the real door: 202, RECEIVED, a new file. */
    private UUID uploaded(Session uploader, String source, LocalDate day, String content)
            throws Exception {
        HttpResponse<String> landed =
                post(FILES,
                        "{\"sourceCode\":\"" + source + "\",\"businessDate\":\"" + day + "\","
                                + "\"content\":\""
                                + Base64.getEncoder().encodeToString(
                                        content.getBytes(StandardCharsets.UTF_8))
                                + "\"}",
                        uploader.token(), key());
        assertThat(landed.statusCode()).as(landed.body()).isEqualTo(202);
        assertThat(field(landed.body(), "status")).isEqualTo("RECEIVED");
        return UUID.fromString(field(landed.body(), "fileId"));
    }

    /** Sweeps the REAL parse leg until the file leaves RECEIVED. */
    private String parseSettled(UUID fileId) throws SQLException {
        for (int sweep = 0; sweep < 50; sweep++) {
            parsing.sweep();
            String status = (String) one("SELECT status FROM settlement.file WHERE id = ?", fileId);
            if (!"RECEIVED".equals(status)) {
                return status;
            }
        }
        throw new AssertionError("settlement file " + fileId + " never left RECEIVED");
    }

    /** Sweeps the REAL accept leg until the file leaves PARSED. */
    private String acceptanceSettled(UUID fileId) throws SQLException {
        for (int sweep = 0; sweep < 50; sweep++) {
            acceptance.sweep();
            String status = (String) one("SELECT status FROM settlement.file WHERE id = ?", fileId);
            if (!"PARSED".equals(status)) {
                return status;
            }
        }
        throw new AssertionError("settlement file " + fileId + " never left PARSED");
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    // ----------------------------------------------------------------- reads

    private static UUID batchOf(UUID fileId) throws SQLException {
        return (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", fileId);
    }

    private static UUID recognitionOf(UUID batchId) throws SQLException {
        UUID entry =
                (UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE id = ?", batchId);
        assertThat(entry).as("batch %s recognised by an entry", batchId).isNotNull();
        return entry;
    }

    private static String batchStatus(UUID batchId) throws SQLException {
        return (String) one("SELECT status FROM settlement.batch WHERE id = ?", batchId);
    }

    private static UUID expectationOf(String kind, String operationRef) throws SQLException {
        UUID id =
                (UUID) one("SELECT id FROM reconciliation.expectation WHERE kind = ? AND"
                        + " operation_ref = ?", kind, operationRef);
        assertThat(id).as("%s %s", kind, operationRef).isNotNull();
        return id;
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

    private static long reversalsOf(UUID entryId) throws SQLException {
        return count("SELECT count(*) FROM ledger.journal_entry WHERE reverses_entry_id = ?"
                + " AND entry_type = 'REVERSAL'", entryId);
    }

    private static String resolutionStatus(String resolutionId) throws SQLException {
        return (String) one("SELECT status FROM reconciliation.resolution WHERE id = ?::uuid",
                resolutionId);
    }

    private static long resolutionsOf(UUID batchId) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.resolution WHERE settlement_batch_id"
                + " = ?", batchId);
    }

    private static long audits(String operation, String target, Session actor)
            throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND"
                + " target_id = ? AND actor_id = ? AND outcome = 'SUCCEEDED'",
                operation, target, actor.actorId());
    }

    private static String sha(UUID fileId) throws SQLException {
        return (String) one("SELECT encode(content_sha256, 'hex') FROM settlement.file WHERE id"
                + " = ?", fileId);
    }

    /** The entry's lines as purpose:direction:minor, read against the chart. */
    private static List<String> entryLines(UUID entryId) throws SQLException {
        return rows("SELECT a.purpose || ':' || l.direction || ':' || l.amount_minor::text FROM"
                + " ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                + " l.ledger_account_id WHERE l.entry_id = ?", entryId);
    }

    /** The same lines, every direction turned: what a whole reversal must post. */
    private static List<String> mirrored(List<String> lines) {
        return lines.stream()
                .map(line -> line.contains(":DEBIT:")
                        ? line.replace(":DEBIT:", ":CREDIT:")
                        : line.replace(":CREDIT:", ":DEBIT:"))
                .toList();
    }

    // ----------------------------------------------------------------- the doors

    private HttpResponse<String> propose(
            Session proposer, String batchId, String key, String narrative) throws Exception {
        return post(BASE + "/batches/" + batchId + "/repudiation",
                repudiationBody("EVIDENCE_REPUDIATED", narrative), proposer.token(), key);
    }

    private HttpResponse<String> approve(Session approver, String resolutionId)
            throws Exception {
        return post(BASE + "/resolutions/" + resolutionId + "/approval", null, approver.token(),
                null);
    }

    private HttpResponse<String> reject(Session rejecter, String resolutionId, String reason)
            throws Exception {
        return post(BASE + "/resolutions/" + resolutionId + "/rejection", reasonBody(reason),
                rejecter.token(), null);
    }

    private HttpResponse<String> withdraw(Session proposer, String resolutionId)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + BASE + "/resolutions/"
                                        + resolutionId))
                        .header("Authorization", "Bearer " + proposer.token())
                        .DELETE()
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> attest(UUID fileId, Session attester) throws Exception {
        return post(FILES + "/" + fileId + "/attestation", null, attester.token(), null);
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

    private static String repudiationBody(String reasonCode, String narrative) {
        return "{\"reasonCode\":\"" + reasonCode + "\",\"narrative\":\"" + narrative + "\"}";
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String key() {
        return "rpd-" + UUID.randomUUID();
    }

    private static String field(String json, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, json).isTrue();
        return matcher.group(1);
    }

    private static long number(String json, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":(-?\\d+)").matcher(json);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, json).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    // ----------------------------------------------------------------- sessions

    /** A person's session; the identity is the actor id every record names. */
    private record Session(IdentityId identity, String token) {

        String actorId() {
            return identity.value().toString();
        }
    }

    private Session sessionWith(RoleName... roles) throws SQLException {
        IdentityId identity = givenAnIdentity();
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (RoleName role : roles) {
                authorization.assign(app, identity, role, identity, "test fixture");
            }
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
                            + " VALUES (?, 'PERSON', 'Repudiation Desk Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "rp" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session session =
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
        return Correlation.startingWith(CorrelationId.of("p8t23-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t23-cause"));
    }

    /** Letters only: no digit run the door's screen could read as a card number. */
    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOMNESS.nextInt(26)));
        }
        return letters.toString();
    }

    /** The PSP's remittance pattern ({@code PSP-REM-[0-9]{4,12}}): ten digits, far short of a card. */
    private static String remittanceRef() {
        return "PSP-REM-23" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
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
