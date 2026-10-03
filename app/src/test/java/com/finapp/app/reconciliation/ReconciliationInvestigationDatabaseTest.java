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
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.UnmatchedConfirmation;
import com.finapp.payments.UnmatchedConfirmationStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.BreakRegister;
import com.finapp.reconciliation.BreakTrace;
import com.finapp.reconciliation.BreakTraces;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.ExternalItems;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.ItemKeyKind;
import com.finapp.reconciliation.JdbcBreakInquiries;
import com.finapp.reconciliation.JdbcExternalItems;
import com.finapp.reconciliation.JdbcReconciliationRuns;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.ReconciliationRuns;
import com.finapp.reconciliation.RunKind;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The investigator's desk over the app's own composition and real HTTP (`P8-TSK-014`,
 * ADR-0069 §7): a break on an item of a REAL accepted PSP batch traced to settlement's own file
 * and the batch's recognition entry; a Phase 7 parking's provider statement reached through the
 * evidence's fifth subject over payments' real store; the case file keyed and screened with a
 * note's body absent from every log, event, audit record and idempotency record; an
 * operation's settlement status over the wire; and every door's negatives three ways, with the
 * named {@code 404}s.
 *
 * <p>Identity discipline (the `P8-TSK-012` lesson): the pulled batch is balanced by its own
 * remittance, the private operation settles against its own item and is held by that
 * allocation, and the parking walk — whose unposted parking would unbalance the suspense proof
 * — runs in ONE uncommitted transaction and rolls back.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the investigator's desk composed, over HTTP (P8-TSK-014)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class ReconciliationInvestigationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PSP_SOURCE = "simulated-psp.settlement";
    private static final UUID PRIVATE_SOURCE =
            UUID.fromString("01a0e2bc-8200-7016-8000-000000000016");
    private static final UUID PRIVATE_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7016-8000-000000000016");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 60_000);
    private static final String BASE = "/v1/operator/reconciliation";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private Matching matching;
    @Autowired private BreakRegister breakRegister;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private ComposedCaseFileEvidence composedCaseFileEvidence;
    @Autowired private ProviderEvidenceStore<Connection> providerEvidenceStore;
    @Autowired private UnmatchedConfirmationStore<Connection> unmatchedConfirmationStore;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private com.finapp.settlement.TransactionRunner settlementTransactionRunner;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    private static UUID settledExpectation;
    private static String settledOperation;
    private static UUID fileId;
    private static UUID batchId;
    private static UUID recognitionEntry;
    private static UUID pspItem;
    private static UUID breakId;

    // ----------------------------------------------------------------- the composition

    @Test
    @Order(1)
    @DisplayName("an operation's settlement status over the wire: REPORTED, with its trail -"
            + " the allocation, the item and the report batch it settled from")
    void settlementStatusOverTheWire() throws Exception {
        seedPrivateRuleSet();
        String key = "BCF-APP-" + marker();
        settledOperation = "op-bcf-" + UUID.randomUUID();
        settledExpectation = openPrivateExpectation(settledOperation, key, 10_00);
        UUID runBatch = IDS.next();
        seedPrivateRun(runBatch, key, 10_00);
        matching.sweep();

        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> status =
                get(operator.token(),
                        "/settlement-status?kind=CARD_CAPTURE&operationRef=" + settledOperation);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body())
                .contains("\"status\":\"REPORTED\"")
                .contains("\"expectationId\":\"" + settledExpectation + "\"")
                .contains(runBatch.toString());

        HttpResponse<String> detail = get(operator.token(), "/expectations/" + settledExpectation);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"status\":\"SETTLED\"").contains(key);
        assertThat(get(operator.token(),
                        "/expectations?status=SETTLED&source=" + PRIVATE_SOURCE).body())
                .contains(settledExpectation.toString());

        assertThat(get(operator.token(), "/settlement-status?kind=REMITTANCE&operationRef=x")
                        .statusCode())
                .as("a remittance is a report's promise, not an operation")
                .isEqualTo(422);
        assertThat(get(operator.token(), "/settlement-status?kind=CARD_CAPTURE&operationRef="
                        + "op-nobody-" + UUID.randomUUID()).body())
                .contains("reconciliation.ExpectationNotFound");
        assertThat(get(operator.token(), "/breaks?severity=LOUD").statusCode())
                .as("a closed vocabulary refuses what it does not name")
                .isEqualTo(422);
    }

    @Test
    @Order(2)
    @DisplayName("a break on an item of a REAL accepted batch traces to settlement's own file"
            + " and the batch's recognition entry - stored identifiers, never a time join")
    void theTraceReachesTheRealFileAndEntry() throws Exception {
        String marker = marker();
        String batchRef = "PSPB-BCF-" + marker;
        fileId = pulled(
                "H,SIM_PSP_CSV,1," + batchRef + ",EUR,2026-09-25\n"
                        + "D,1,SALE,100.00,1.75,EUR,2026-09-25,,,PSP-CAP-" + marker
                        + ",44400012345678901,,ORD-" + marker + ",Desk sale\n"
                        + "T,1,98.25,PSP-REM-11" + digits() + "\n");
        parsing.sweep();
        acceptance.sweep();
        batchId = (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ? AND status ="
                + " 'ACCEPTED'", fileId);
        assertThat(batchId).as("the pulled file was accepted").isNotNull();
        recognitionEntry = (UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE"
                + " id = ?", batchId);
        assertThat(recognitionEntry).as("the 1.75 fee posted a recognition").isNotNull();
        UUID runId = (UUID) one("SELECT id FROM reconciliation.reconciliation_batch WHERE"
                + " batch_id = ?", batchId);
        pspItem = (UUID) one("SELECT id FROM reconciliation.external_item WHERE run_id = ?"
                + " AND line_type = 'CAPTURE'", runId);
        UUID lineId = (UUID) one("SELECT settlement_line_id FROM reconciliation.external_item"
                + " WHERE id = ?", pspItem);

        breakId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = (UUID) one("SELECT rule_set_id FROM reconciliation"
                    + ".reconciliation_batch WHERE id = ?", runId);
            UUID source = (UUID) one("SELECT source_id FROM reconciliation"
                    + ".reconciliation_batch WHERE id = ?", runId);
            breakRegister.raise(
                    app,
                    new BreakRegister.NewBreak(
                            breakId, BreakType.UNKNOWN_EXTERNAL, BreakCause.GRACE_EXPIRED,
                            BreakRegister.Subject.externalItem(pspItem), source, ruleSet,
                            Money.ofPersisted(100_00, EUR, 2),
                            Optional.of(ExpectationDirection.INBOUND), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            app.commit();
        }

        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> trace = get(operator.token(), "/breaks/" + breakId + "/trace");
        assertThat(trace.statusCode()).isEqualTo(200);
        assertThat(trace.body())
                .contains("\"toKind\":\"SETTLEMENT_LINE\",\"toId\":\"" + lineId + "\"")
                .contains("\"toKind\":\"SETTLEMENT_BATCH\",\"toId\":\"" + batchId + "\"")
                .contains("\"toKind\":\"SETTLEMENT_FILE\",\"toId\":\"" + fileId + "\"")
                .contains("\"relation\":\"RECOGNISED_BY\",\"toKind\":\"JOURNAL_ENTRY\",\"toId\":\""
                        + recognitionEntry + "\"");

        HttpResponse<String> listed =
                get(operator.token(), "/breaks?type=UNKNOWN_EXTERNAL&status=OPEN&source="
                        + one("SELECT source_id FROM reconciliation.break WHERE id = ?", breakId));
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(breakId.toString());
    }

    @Test
    @Order(3)
    @DisplayName("a Phase 7 parking's raw statement is reached through the evidence's fifth"
            + " subject over payments' REAL store - in one uncommitted transaction, rolled back")
    void aParkingIsTracedThroughTheFifthSubject() throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(flow());
                SecurityContext.Scope system = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                String reference = "BCF-E2E-" + marker();
                UUID parking = IDS.next();
                unmatchedConfirmationStore.insert(
                        app,
                        new UnmatchedConfirmation(
                                parking, RailId.of("sim-instant"),
                                new ProviderReference(reference),
                                Money.ofPersisted(12_00, EUR, 2), Instant.now(CLOCK),
                                IDS.next(),
                                UnmatchedConfirmation.Attribution.unattributed(
                                        Optional.empty(), Optional.empty())));
                providerEvidenceStore.appendForUnmatched(
                        app, parking, EvidenceKind.WEBHOOK,
                        "{\"statement\":\"parked\"}".getBytes(StandardCharsets.UTF_8),
                        Instant.now(CLOCK));
                UUID evidence = (UUID) one(app, "SELECT id FROM payments.provider_evidence"
                        + " WHERE unmatched_confirmation_id = ?", parking);
                String operation = "sim-instant:" + reference;
                UUID position = new JdbcLedgerAccountStore()
                        .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                        .orElseThrow().id().value();
                expectationRegister.open(
                        app,
                        new NewExpectation(
                                ExpectationKind.UNMATCHED_CONFIRMATION, operation,
                                "unmatched-confirmation:" + operation, PRIVATE_SOURCE,
                                AccountPurpose.SETTLEMENT_CLEARING, position,
                                ExpectationDirection.INBOUND, Money.ofPersisted(12_00, EUR, 2),
                                Optional.of(IDS.next()), SETTLED_ON, Optional.empty(),
                                SETTLED_ON.plusDays(1), PRIVATE_RULE_SET, List.of(), PLATFORM,
                                Instant.now(CLOCK), CorrelationId.generate(IDS)));
                UUID expectation = (UUID) one(app, "SELECT id FROM reconciliation.expectation"
                        + " WHERE kind = 'UNMATCHED_CONFIRMATION' AND operation_ref = ?",
                        operation);
                UUID overdue = IDS.next();
                breakRegister.raise(
                        app,
                        new BreakRegister.NewBreak(
                                overdue, BreakType.MISSING_EXTERNAL,
                                BreakCause.EXPECTATION_OVERDUE,
                                BreakRegister.Subject.expectation(expectation), PRIVATE_SOURCE,
                                PRIVATE_RULE_SET, Money.ofPersisted(12_00, EUR, 2),
                                Optional.of(ExpectationDirection.INBOUND),
                                Optional.of(ExpectationKind.UNMATCHED_CONFIRMATION),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), PLATFORM, Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));

                BreakTrace trace =
                        new BreakTraces(new JdbcBreakInquiries(), composedCaseFileEvidence)
                                .trace(app, overdue)
                                .orElseThrow();
                assertThat(trace.steps())
                        .as("expectation -> operation -> the parking's own statement")
                        .contains(new BreakTrace.Step(
                                BreakTrace.NodeKind.EXPECTATION, expectation.toString(),
                                BreakTrace.Relation.TRACKS, BreakTrace.NodeKind.OPERATION,
                                "UNMATCHED_CONFIRMATION:" + operation))
                        .contains(new BreakTrace.Step(
                                BreakTrace.NodeKind.OPERATION,
                                "UNMATCHED_CONFIRMATION:" + operation,
                                BreakTrace.Relation.EVIDENCED_BY,
                                BreakTrace.NodeKind.PROVIDER_EVIDENCE, evidence.toString()));
                assertThat(composedCaseFileEvidence.exists(
                                app, com.finapp.reconciliation.EvidenceTargetKind.PROVIDER_EVIDENCE,
                                evidence))
                        .as("the link verifier sees the same row")
                        .isTrue();
            } finally {
                app.rollback();
            }
        }
    }

    // ----------------------------------------------------------------- the case file

    @Test
    @Order(4)
    @DisplayName("the case file over HTTP: an assignee that is no active investigator - a card"
            + " number, a free-text or unknown id, a role-less person, the controller - refused"
            + " 422 with nothing written or published (SEC-06); assignment opens the"
            + " investigation once, a keyed note replays, its body reaches no log, event, audit"
            + " or idempotency record, a PAN-bearing note is refused with nothing stored, links"
            + " are verified")
    void theCaseFileOverHttp(CapturedOutput output) throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session colleague = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session roleless = rolelessSession();
        String pan = "4111111111111111";
        for (String refusedAssignee : List.of(pan, "op-desk-1", UUID.randomUUID().toString(),
                roleless.identity().value().toString(),
                controller.identity().value().toString())) {
            HttpResponse<String> refusedAssignment = post(operator.token(), "/breaks/" + breakId
                    + "/assignment", null, "{\"assigneeId\":\"" + refusedAssignee + "\"}");
            assertThat(refusedAssignment.statusCode())
                    .as("%s: %s", refusedAssignee, refusedAssignment.body())
                    .isEqualTo(422);
            assertThat(refusedAssignment.body())
                    .contains("api.ValidationFailed")
                    .doesNotContain(refusedAssignee);
        }
        assertThat(one("SELECT assignee FROM reconciliation.break WHERE id = ?", breakId))
                .as("SEC-06: the refused assignees wrote nothing")
                .isNull();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakInvestigationStarted' AND aggregate_id = ?", breakId))
                .as("SEC-06: and published nothing")
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                + " convert_from(payload, 'UTF8') LIKE ?", "%" + pan + "%")).isZero();
        HttpResponse<String> assigned = post(operator.token(), "/breaks/" + breakId
                + "/assignment", null, "{\"assigneeId\":\"" + colleague.identity().value()
                        + "\"}");
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);
        assertThat(assigned.body()).contains("\"status\":\"INVESTIGATING\"")
                .contains("\"assignee\":\"" + colleague.identity().value() + "\"");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakInvestigationStarted' AND aggregate_id = ?", breakId))
                .isEqualTo(1);

        // Letters only: a UUID's hex can hold a Luhn-valid digit run the note screen rightly
        // refuses as a card number - the flaky-fixture class (found by P8-TSK-020's gate).
        String needle = "needle" + UUID.randomUUID().toString().replace("-", "")
                .replaceAll("[0-9]", "q");
        String key = "note-" + UUID.randomUUID();
        HttpResponse<String> noted = post(operator.token(), "/breaks/" + breakId + "/notes",
                key, "{\"body\":\"the PSP confirms the capture " + needle + "\"}");
        assertThat(noted.statusCode()).isEqualTo(201);
        assertThat(noted.body()).doesNotContain(needle);
        HttpResponse<String> replayed = post(operator.token(), "/breaks/" + breakId + "/notes",
                key, "{\"body\":\"the PSP confirms the capture " + needle + "\"}");
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(replayed.body()).as("a lost response replays the receipt").isEqualTo(noted.body());
        assertThat(count("SELECT count(*) FROM reconciliation.break_note WHERE break_id = ?",
                breakId)).isEqualTo(1);

        assertThat(output.getAll()).as("the body never reaches a log").doesNotContain(needle);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE change_summary LIKE"
                + " ? OR reason LIKE ?", "%" + needle + "%", "%" + needle + "%")).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                + " convert_from(payload, 'UTF8') LIKE ?", "%" + needle + "%")).isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE"
                + " convert_from(response_body, 'UTF8') LIKE ?", "%" + needle + "%"))
                .as("the stored receipt carries no body")
                .isZero();

        String panKey = "note-pan-" + UUID.randomUUID();
        HttpResponse<String> refused = post(operator.token(), "/breaks/" + breakId + "/notes",
                panKey, "{\"body\":\"customer card 4111111111111111\"}");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("api.ValidationFailed").doesNotContain("4111");
        assertThat(output.getAll())
                .as("the refused card number reaches no log either")
                .doesNotContain("4111111111111111");
        assertThat(count("SELECT count(*) FROM reconciliation.break_note WHERE break_id = ?",
                breakId)).as("nothing stored").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE"
                + " idempotency_key = ?", panKey))
                .as("the screen runs before the claim: not even a claim")
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.BreakNoteAdded' AND target_id = ? AND outcome ="
                + " 'SUCCEEDED'", breakId.toString()))
                .as("the note is audited exactly once: the replayed key and the refused"
                        + " PAN-bearing note write no second reconciliation.BreakNoteAdded")
                .isEqualTo(1);

        String linkKey = "link-" + UUID.randomUUID();
        String link = "{\"targetKind\":\"SETTLEMENT_FILE\",\"targetRef\":\"" + fileId + "\"}";
        HttpResponse<String> linked = post(operator.token(), "/breaks/" + breakId
                + "/evidence-links", linkKey, link);
        assertThat(linked.statusCode()).isEqualTo(201);
        HttpResponse<String> relinked = post(operator.token(), "/breaks/" + breakId
                + "/evidence-links", linkKey, link);
        assertThat(relinked.statusCode()).isEqualTo(201);
        assertThat(relinked.body()).as("a lost response replays the link's receipt")
                .isEqualTo(linked.body());
        HttpResponse<String> dangling = post(operator.token(), "/breaks/" + breakId
                + "/evidence-links", "link-" + UUID.randomUUID(),
                "{\"targetKind\":\"JOURNAL_ENTRY\",\"targetRef\":\"" + UUID.randomUUID() + "\"}");
        assertThat(dangling.statusCode()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.BreakEvidenceLinked' AND target_id = ? AND outcome ="
                + " 'SUCCEEDED'", breakId.toString()))
                .as("the link is audited exactly once: the replayed key and the refused"
                        + " dangling link write no second reconciliation.BreakEvidenceLinked")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break_evidence_link WHERE"
                + " break_id = ?", breakId))
                .as("one link stored under the replayed key, none dangling")
                .isEqualTo(1);

        HttpResponse<String> reclassified = post(operator.token(), "/breaks/" + breakId
                + "/classification", null,
                "{\"type\":\"FEE_MISMATCH\",\"reason\":\"a fee line, not a capture\"}");
        assertThat(reclassified.statusCode())
                .as("nothing parked on this item, so a non-parking type may stand on it")
                .isEqualTo(200);
        assertThat(reclassified.body()).contains("\"type\":\"FEE_MISMATCH\"");

        HttpResponse<String> detail = get(operator.token(), "/breaks/" + breakId);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body())
                .as("the case file serves the note to the authorised investigator")
                .contains(needle)
                .contains("\"eventType\":\"RECLASSIFIED\"")
                .contains("\"targetKind\":\"SETTLEMENT_FILE\"");
    }

    // ----------------------------------------------------------------- the negatives

    @Test
    @Order(5)
    @DisplayName("every door holds its permission - anonymous 401, role-less 403, the"
            + " controller's disjoint desk 403 - and unknown or malformed ids are one named"
            + " 404 each")
    void everyDoorHoldsItsPermission() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session roleless = rolelessSession();
        String some = UUID.randomUUID().toString();
        record Door(String method, String path, String body) {}
        List<Door> doors = List.of(
                new Door("GET", "/breaks", null),
                new Door("GET", "/breaks/" + some, null),
                new Door("GET", "/breaks/" + some + "/trace", null),
                new Door("POST", "/breaks/" + some + "/assignment", "{\"assigneeId\":\"op-x\"}"),
                new Door("POST", "/breaks/" + some + "/notes", "{\"body\":\"n\"}"),
                new Door("POST", "/breaks/" + some + "/evidence-links",
                        "{\"targetKind\":\"RUN\",\"targetRef\":\"" + some + "\"}"),
                new Door("POST", "/breaks/" + some + "/classification",
                        "{\"type\":\"FEE_MISMATCH\",\"reason\":\"r\"}"),
                new Door("GET", "/expectations", null),
                new Door("GET", "/expectations/" + some, null),
                new Door("GET", "/settlement-status?kind=CARD_CAPTURE&operationRef=x", null));
        for (Door door : doors) {
            assertThat(call(null, door.method(), door.path(), door.body()).statusCode())
                    .as("%s %s anonymous", door.method(), door.path())
                    .isEqualTo(401);
            assertThat(call(roleless.token(), door.method(), door.path(), door.body())
                            .statusCode())
                    .as("%s %s role-less", door.method(), door.path())
                    .isEqualTo(403);
            assertThat(call(controller.token(), door.method(), door.path(), door.body())
                            .statusCode())
                    .as("%s %s: the controller decides what counts; the investigator works"
                            + " the case", door.method(), door.path())
                    .isEqualTo(403);
        }

        record Absent(String method, String path, String body, String code) {}
        List<Absent> absents = List.of(
                new Absent("GET", "/breaks/" + some, null, "reconciliation.BreakNotFound"),
                new Absent("GET", "/breaks/not-a-uuid", null, "reconciliation.BreakNotFound"),
                new Absent("GET", "/breaks/" + some + "/trace", null,
                        "reconciliation.BreakNotFound"),
                new Absent("POST", "/breaks/" + some + "/assignment",
                        "{\"assigneeId\":\"" + operator.identity().value() + "\"}",
                        "reconciliation.BreakNotFound"),
                new Absent("POST", "/breaks/not-a-uuid/notes", "{\"body\":\"n\"}",
                        "reconciliation.BreakNotFound"),
                new Absent("POST", "/breaks/" + some + "/classification",
                        "{\"type\":\"FEE_MISMATCH\",\"reason\":\"r\"}",
                        "reconciliation.BreakNotFound"),
                new Absent("GET", "/expectations/" + some, null,
                        "reconciliation.ExpectationNotFound"),
                new Absent("GET", "/expectations/not-a-uuid", null,
                        "reconciliation.ExpectationNotFound"));
        for (Absent absent : absents) {
            HttpResponse<String> answer =
                    call(operator.token(), absent.method(), absent.path(), absent.body());
            assertThat(answer.statusCode()).as(absent.path()).isEqualTo(404);
            assertThat(answer.body()).as(absent.path()).contains(absent.code());
        }
    }

    // ----------------------------------------------------------------- seeding

    private UUID pulled(String content) {
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
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private static void seedPrivateRuleSet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'PROPOSED', 2, 90, ?, 'test', NULL,"
                            + " 'ReconciliationInvestigationDatabaseTest private rule set',"
                            + " now(), 'p8-tsk-014-app-test') ON CONFLICT (id) DO NOTHING",
                    PRIVATE_RULE_SET, PRIVATE_SOURCE, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality, operation_anchored,"
                            + " grace_hours) VALUES (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF',"
                            + " 'CARD_CAPTURE', 'ONE_TO_ONE', false, 48) ON CONFLICT DO NOTHING",
                    PRIVATE_RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency,"
                            + " absolute_minor, days) VALUES (?, 'SETTLEMENT_DATE_DAYS', NULL,"
                            + " NULL, 2) ON CONFLICT DO NOTHING",
                    PRIVATE_RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', 100000)"
                            + " ON CONFLICT DO NOTHING",
                    PRIVATE_RULE_SET);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'test-activator',"
                            + " decided_at = now() WHERE id = ? AND status = 'PROPOSED'",
                    PRIVATE_RULE_SET);
            app.commit();
        }
    }

    private UUID openPrivateExpectation(String operationRef, String key, long minor)
            throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID position = new JdbcLedgerAccountStore()
                    .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                    .orElseThrow().id().value();
            expectationRegister.open(
                    app,
                    new NewExpectation(
                            ExpectationKind.CARD_CAPTURE, operationRef,
                            "payment-capture:" + operationRef, PRIVATE_SOURCE,
                            AccountPurpose.SETTLEMENT_CLEARING, position,
                            ExpectationDirection.INBOUND, Money.ofPersisted(minor, EUR, 2),
                            Optional.of(IDS.next()), SETTLED_ON, Optional.empty(),
                            SETTLED_ON.plusDays(3), PRIVATE_RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(
                                    KeyKind.PSP_CAPTURE_REF, key)),
                            PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
            return (UUID) one(app, "SELECT id FROM reconciliation.expectation WHERE"
                    + " kind = 'CARD_CAPTURE' AND operation_ref = ?", operationRef);
        }
    }

    private void seedPrivateRun(UUID runBatch, String key, long minor) throws SQLException {
        UUID runId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            new JdbcReconciliationRuns().birth(
                    app,
                    new ReconciliationRuns.NewRun(
                            runId, PRIVATE_SOURCE, Optional.of(runBatch), RunKind.BATCH,
                            PRIVATE_RULE_SET, SETTLED_ON, Optional.of(SEQUENCES.incrementAndGet()),
                            1, Optional.empty(), Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            byte[] fingerprint = new byte[32];
            RANDOMNESS.nextBytes(fingerprint);
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            newItems.add(
                    new ExternalItems.NewItem(
                            IDS.next(), runId, PRIVATE_SOURCE, IDS.next(), 1,
                            ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                            Money.ofPersisted(minor, EUR, 2), AccountPurpose.SETTLEMENT_CLEARING,
                            SETTLED_ON, Optional.of(SETTLED_ON), Optional.of(SETTLED_ON),
                            fingerprint, Map.of(ItemKeyKind.PSP_CAPTURE_REF, key),
                            Instant.now(CLOCK), CorrelationId.generate(IDS)));
            new JdbcExternalItems().birthAll(app, PLATFORM, newItems);
            app.commit();
        }
    }

    // ----------------------------------------------------------------- the doors

    private HttpResponse<String> get(String token, String path) throws Exception {
        return call(token, "GET", path, null);
    }

    private HttpResponse<String> post(String token, String path, String key, String body)
            throws Exception {
        return call(token, "POST", path, body, key);
    }

    private HttpResponse<String> call(String token, String method, String path, String body)
            throws Exception {
        return call(token, method, path, body, "key-" + UUID.randomUUID());
    }

    private HttpResponse<String> call(
            String token, String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE + path));
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
        } else {
            request.GET();
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Session(IdentityId identity, String token) {}

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
        return Correlation.startingWith(CorrelationId.of("p8t14-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t14-cause"));
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Desk Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "bc" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    private static String marker() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private static String digits() {
        return String.valueOf(10_000_000 + RANDOMNESS.nextInt(89_999_999));
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
        try (Connection app = DatabaseRoles.application()) {
            return one(app, sql, args);
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

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }
}
