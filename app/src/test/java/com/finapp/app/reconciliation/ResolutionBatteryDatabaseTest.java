package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.telemetry.ReconciliationOutcomeMeters;
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
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.BreakRegister;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.ResolutionKind;
import com.finapp.reconciliation.ResolutionOutcome;
import com.finapp.reconciliation.RuleSets;
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
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The break and resolution battery over the app's own composition and real HTTP (`P8-TST-002`;
 * ADR-0071, ADR-0069; {@code INV-REC-03}, {@code -05}, {@code -07}, {@code -09}, {@code INV-AUD-04},
 * {@code INV-REV-01}, {@code INV-REV-04}, {@code INV-SET-03}, {@code INV-SET-06}): a representative
 * crossing per kind through the doors - the keyed proposal (one key replays, a second key
 * {@code ResolutionAlreadyProposed}), the approval, the reasoned rejection and the proposer's
 * withdrawal - over REAL value: a completion entry's expectation written off and transferred,
 * a valued duplicate acknowledged four-eyes, a statement's unattributed credit transferred to its
 * owner and an unattributed credit and debit offset, and a fabricated PSP report repudiated, then
 * its genuine re-presentation accepted - which settles the reopened capture and closes the
 * overdue break raised meanwhile {@code EVIDENCED}, the one platform resolution.
 *
 * <p>Around the crossings: four-eyes at its three ranks (the domain's 409, the resolution
 * {@code CHECK} and ledger {@code V010} for raw writers); the ledger's generic door refusing a
 * resolution's {@code RECONCILIATION}-origin proposal both ways and a free adjustment on every
 * reconciled position; then the suite's scans - every {@code RECONCILIATION_LOSSES} and
 * {@code RECONCILIATION_GAINS} line an approved resolution's entry; one
 * {@code reconciliation.BreakResolved} per resolved break; {@code BreakResolvedByEvidence} for the
 * platform's resolutions only; no narrative in any audit body, event or stored receipt;
 * {@code finapp.reconciliation.resolution}'s deltas per kind and outcome equal to the resolution
 * rows the suite produced; every released suspense item traced to its resolution or evidence;
 * and every door's negatives (401, 403) re-run.
 *
 * <p><strong>Run apart.</strong> Like {@code BatchRepudiationDatabaseTest}, this suite runs in its
 * own container: its proof assertions are ABSOLUTE - every position, suspense and cash identity
 * explained, nothing unattributed, nothing unowned, the trial balance in balance - after every
 * crossing, which holds only when no suite that legitimately leaves a proof failing ran before
 * it in the same database. Every flow keeps its money balanced: each written-off or transferred
 * expectation names a completion entry actually posted to the clearing, the PSP capture is seeded
 * with its posting, the fee is the pinned schedule's own (1.5% + 0.25), and each statement
 * continues its currency's chain from the head it finds.
 *
 * <p>{@code RECOGNISE_GAIN} and {@code MANUAL_MATCH} are crossed in the module battery
 * ({@code ResolutionMatrixDatabaseTest}), not here: real ingestion cannot age a parking past the
 * pinned 90 days inside one run, and the engine never produces the ambiguous decision a manual
 * match stands in for (one expectation per key) - planting either would forge the evidence these
 * absolute proofs read.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the break and resolution battery over HTTP (P8-TST-002)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class ResolutionBatteryDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final UUID PRIVATE_SOURCE =
            UUID.fromString("01a0e2bc-8200-7017-8000-0000000000a2");
    private static final UUID PRIVATE_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7017-8000-0000000000a2");
    private static final LocalDate FAR = LocalDate.parse("2027-12-31");
    private static final String BASE = "/v1/operator/reconciliation";
    private static final String PSP_SOURCE = "simulated-psp.settlement";
    private static final String BANK_SOURCE = "simulated-bank.statement";

    /** Every narrative this suite writes carries this marker - and no record may. */
    private static final String NEEDLE = "batteryneedle" + letters(10);

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BreakRegister breakRegister;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private PostingService postingService;
    @Autowired private PositionProof positionProof;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private RuleSets ruleSets;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private TransactionRunner settlementTransactionRunner;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // What the ordered cases hand on.
    private static UUID counterpartWallet;
    private static Map<ResolutionKind, Map<ResolutionOutcome, Double>> metersBefore;
    private static Map<String, Long> rowsBefore;
    private static final List<String> PERSON_RESOLUTIONS = new ArrayList<>();
    private static final List<String> RECONCILIATION_PROPOSALS = new ArrayList<>();
    private static String evidencedResolution;

    // ----------------------------------------------------------------- 1. the write-off

    @Test
    @Order(1)
    @DisplayName("WRITE_OFF over HTTP: the keyed proposal replays its receipt and a second key is"
            + " ResolutionAlreadyProposed; the proposer's approval 409 SelfApprovalRefused; the"
            + " second operator's posts DR losses / CR the clearing and a retry converges; a"
            + " reasoned rejection by another and the proposer's withdrawal each return the break"
            + " to investigation, nothing posted - the books hold after each")
    void aWriteOffAndItsRejectionAndWithdrawal() throws Exception {
        seedPrivateRuleSet();
        metersBefore = meters();
        rowsBefore = resolutionRows();
        assertBooksHold("before the battery");
        counterpartWallet = openAccount(AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next());
        Subject subject = completedExpectation(ExpectationDirection.INBOUND, 12_00,
                BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session other = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        // The investigator's note carries the same marker: a note, like a narrative, never
        // leaves its own row (the scans of case 6).
        HttpResponse<String> noted = call(proposer.token(), "POST",
                BASE + "/breaks/" + subject.breakId() + "/notes",
                "{\"body\":\"the PSP will never pay this capture " + NEEDLE + "\"}", key());
        assertThat(noted.statusCode()).as(noted.body()).isEqualTo(201);
        assertThat(noted.body()).doesNotContain(NEEDLE);

        String body = proposal("WRITE_OFF", "LOSS_ACCEPTED", null, null);
        String key = key();
        HttpResponse<String> proposed = propose(proposer, subject.breakId(), key, body);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"status\":\"PROPOSED\"")
                .contains("\"fourEyes\":true").doesNotContain(NEEDLE);
        HttpResponse<String> replayed = propose(proposer, subject.breakId(), key, body);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(replayed.body()).as("one key replays the receipt").isEqualTo(proposed.body());
        HttpResponse<String> second = propose(proposer, subject.breakId(), key(), body);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).as("a second key")
                .contains("reconciliation.ResolutionAlreadyProposed");

        String resolution = field(proposed.body(), "resolutionId");
        RECONCILIATION_PROPOSALS.add(field(proposed.body(), "adjustmentProposalId"));
        HttpResponse<String> self = approve(proposer, resolution);
        assertThat(self.statusCode()).as("DOMAIN RANK over HTTP").isEqualTo(409);
        assertThat(self.body()).contains("reconciliation.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                resolution)).as("the refusal wrote nothing").isZero();

        HttpResponse<String> approved = approve(approver, resolution);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        String entry = field(approved.body(), "journalEntryId");
        HttpResponse<String> retried = approve(approver, resolution);
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(field(retried.body(), "journalEntryId")).as("a retry converges")
                .isEqualTo(entry);
        assertThat(entryLines(UUID.fromString(entry)))
                .containsExactlyInAnyOrder("RECONCILIATION_LOSSES:DEBIT:1200",
                        "SETTLEMENT_CLEARING:CREDIT:1200");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                subject.expectationId())).isEqualTo("RESOLVED_BY_ADJUSTMENT");
        PERSON_RESOLUTIONS.add(resolution);
        assertBooksHold("the write-off approved");

        // The rejection: another operator's reasoned act; the break back to investigation.
        Subject rejectedSubject = completedExpectation(ExpectationDirection.INBOUND, 13_00,
                BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE);
        HttpResponse<String> toReject = propose(proposer, rejectedSubject.breakId(), key(), body);
        assertThat(toReject.statusCode()).as(toReject.body()).isEqualTo(201);
        String rejected = field(toReject.body(), "resolutionId");
        HttpResponse<String> selfReject = reject(proposer, rejected, "not mine to judge");
        assertThat(selfReject.statusCode()).isEqualTo(409);
        assertThat(selfReject.body()).contains("reconciliation.SelfApprovalRefused");
        HttpResponse<String> rejection = reject(other, rejected, "the PSP confirmed it will pay");
        assertThat(rejection.statusCode()).as(rejection.body()).isEqualTo(200);
        assertThat(field(rejection.body(), "status")).isEqualTo("REJECTED");
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?",
                rejectedSubject.breakId())).isEqualTo("INVESTIGATING");
        assertThat(one("SELECT status FROM ledger.adjustment_proposal WHERE id = ?::uuid",
                field(toReject.body(), "adjustmentProposalId"))).isEqualTo("REJECTED");
        PERSON_RESOLUTIONS.add(rejected);

        // The withdrawal: the proposer's alone; then a fresh proposal approved.
        HttpResponse<String> toWithdraw =
                propose(proposer, rejectedSubject.breakId(), key(), body);
        assertThat(toWithdraw.statusCode()).as(toWithdraw.body()).isEqualTo(201);
        String withdrawn = field(toWithdraw.body(), "resolutionId");
        HttpResponse<String> notTheirs = withdraw(other, withdrawn);
        assertThat(notTheirs.statusCode()).isEqualTo(409);
        assertThat(notTheirs.body()).contains("reconciliation.NotTheProposer");
        HttpResponse<String> withdrawal = withdraw(proposer, withdrawn);
        assertThat(withdrawal.statusCode()).as(withdrawal.body()).isEqualTo(200);
        assertThat(field(withdrawal.body(), "status")).isEqualTo("WITHDRAWN");
        assertThat(withdraw(proposer, withdrawn).statusCode()).as("the retry converges")
                .isEqualTo(200);
        PERSON_RESOLUTIONS.add(withdrawn);
        String finalResolution = resolve(proposer, approver, rejectedSubject.breakId(), body);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference IN (?, ?)",
                rejected, withdrawn)).as("neither the rejection nor the withdrawal posted")
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                finalResolution)).isEqualTo(1);
        assertBooksHold("the rejection, the withdrawal and the re-proposal");
    }

    // ----------------------------------------------------------------- 2. transfer, acknowledge

    @Test
    @Order(2)
    @DisplayName("TRANSFER_TO_ACCOUNT of an OUTBOUND remainder credits the owner's wallet; a valued"
            + " duplicate's ACKNOWLEDGE is four-eyes and disposes of nothing; four-eyes holds at"
            + " the resolution CHECK and ledger V010 for raw writers; the generic ledger door"
            + " refuses a resolution's proposal both ways")
    void aTransferAnAcknowledgementAndTheRanks() throws Exception {
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session ledger = sessionWith(RoleName.LEDGER_OPERATOR);

        UUID wallet = openAccount(AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next());
        Subject outbound = completedExpectation(ExpectationDirection.OUTBOUND, 7_00,
                BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE);
        HttpResponse<String> transfer = propose(proposer, outbound.breakId(), key(),
                proposal("TRANSFER_TO_ACCOUNT", "FUNDS_ATTRIBUTED", wallet, null));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(201);
        String transferId = field(transfer.body(), "resolutionId");
        String ledgerProposal = field(transfer.body(), "adjustmentProposalId");
        RECONCILIATION_PROPOSALS.add(ledgerProposal);
        for (String method : List.of("POST", "DELETE")) {
            HttpResponse<String> generic = call(ledger.token(), method,
                    "/v1/ledger/adjustments/" + ledgerProposal
                            + ("POST".equals(method) ? "/approval" : ""), null, null);
            assertThat(generic.statusCode())
                    .as("the generic door %s refuses a RECONCILIATION-origin proposal", method)
                    .isEqualTo(409);
            assertThat(generic.body()).contains("ledger.AdjustmentOriginMismatch");
        }
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThatThrownBy(() -> execute(raw, "UPDATE ledger.adjustment_proposal SET status"
                    + " = 'APPROVED', decided_by = proposed_by, decided_at = now() WHERE id ="
                    + " ?::uuid", ledgerProposal))
                    .as("V010 RANK: the same person on the resolution's ledger proposal")
                    .hasMessageContaining("adjustment_proposal_approver_is_not_initiator");
            raw.rollback();
        }
        HttpResponse<String> approved = approve(approver, transferId);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(entryLines(UUID.fromString(field(approved.body(), "journalEntryId"))))
                .containsExactlyInAnyOrder("SETTLEMENT_CLEARING:DEBIT:700",
                        "CUSTOMER_WALLET:CREDIT:700");
        PERSON_RESOLUTIONS.add(transferId);

        Subject duplicate = completedExpectation(ExpectationDirection.INBOUND, 9_00,
                BreakType.DUPLICATE_INTERNAL, BreakCause.KEY_COLLISION);
        HttpResponse<String> acknowledged = propose(proposer, duplicate.breakId(), key(),
                proposal("ACKNOWLEDGE", "INTERNAL_PROCESSING_ERROR", null, null));
        assertThat(acknowledged.statusCode()).as(acknowledged.body()).isEqualTo(201);
        assertThat(acknowledged.body()).contains("\"fourEyes\":true")
                .contains("\"status\":\"PROPOSED\"");
        String acknowledgement = field(acknowledged.body(), "resolutionId");
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThatThrownBy(() -> execute(raw, "UPDATE reconciliation.resolution SET status ="
                    + " 'APPROVED', decided_by = proposed_by, decided_by_type = 'EMPLOYEE',"
                    + " decided_at = now(), status_changed_at = now() WHERE id = ?::uuid",
                    acknowledgement))
                    .as("CHECK RANK: a non-posting kind the ledger never sees")
                    .hasMessageContaining("resolution_four_eyes_distinct");
            raw.rollback();
        }
        HttpResponse<String> self = approve(proposer, acknowledgement);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("reconciliation.SelfApprovalRefused");
        HttpResponse<String> decided = approve(approver, acknowledgement);
        assertThat(decided.statusCode()).as(decided.body()).isEqualTo(200);
        assertThat(decided.body()).doesNotContain("\"journalEntryId\":\"");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                duplicate.expectationId())).as("an acknowledgement disposes of nothing")
                .isEqualTo("OPEN");
        PERSON_RESOLUTIONS.add(acknowledgement);
        assertBooksHold("the transfer and the acknowledgement");
    }

    // ----------------------------------------------------------------- 3. the statement

    @Test
    @Order(3)
    @DisplayName("a statement's unattributed lines: a CREDIT transferred to its owner - DR"
            + " suspense / CR the wallet - and an equal CREDIT and DEBIT offset four-eyes, both"
            + " released, both breaks closed, nothing posted; cash and suspense explained")
    void aStatementsUnattributedLines() throws Exception {
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String marker = letters(10);
        Head head = head("GBP");
        long closing = head.closingMinor() + 31_00 + 17_00 - 17_00;
        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-BAT-" + marker, "GBP", head.sequence() + 1,
                                "C,2026-09-25,GBP," + decimal(head.closingMinor()),
                                "C,2026-09-26,GBP," + decimal(closing),
                                ":61:2026-09-26,C,31.00",
                                ":61:2026-09-26,C,17.00",
                                ":61:2026-09-26,D,17.00"));
        List<String> parked = rows("SELECT i.line_type || ':' || i.amount_minor || ':' || s.id"
                + " || ':' || s.break_id FROM reconciliation.external_item i JOIN"
                + " reconciliation.reconciliation_batch r ON r.id = i.run_id JOIN"
                + " reconciliation.suspense_item s ON s.external_item_id = i.id WHERE r.batch_id"
                + " = ? ORDER BY i.line_type, i.amount_minor", statement);
        assertThat(parked).as("three unattributed lines, each parked with its owner").hasSize(3);
        String[] creditToOwner = parked.stream().filter(row -> row.startsWith("BANK_CREDIT:3100"))
                .findFirst().orElseThrow().split(":");
        String[] creditToOffset = parked.stream()
                .filter(row -> row.startsWith("BANK_CREDIT:1700")).findFirst().orElseThrow()
                .split(":");
        String[] debitToOffset = parked.stream().filter(row -> row.startsWith("BANK_DEBIT:1700"))
                .findFirst().orElseThrow().split(":");

        UUID wallet = openAccount(AccountPurpose.CUSTOMER_WALLET, GBP, IDS.next());
        String transfer = resolve(proposer, approver, UUID.fromString(creditToOwner[3]),
                proposal("TRANSFER_TO_ACCOUNT", "FUNDS_ATTRIBUTED", wallet, null));
        UUID transferEntry = (UUID) one("SELECT journal_entry_id FROM reconciliation.resolution"
                + " WHERE id = ?::uuid", transfer);
        assertThat(entryLines(transferEntry))
                .containsExactlyInAnyOrder("SUSPENSE_UNMATCHED:DEBIT:3100",
                        "CUSTOMER_WALLET:CREDIT:3100");

        String offset = resolve(proposer, approver, UUID.fromString(creditToOffset[3]),
                proposal("OFFSET_SUSPENSE", "COUNTERPARTY_ERROR_CONFIRMED", null,
                        UUID.fromString(debitToOffset[2])));
        assertThat(one("SELECT journal_entry_id FROM reconciliation.resolution WHERE id ="
                + " ?::uuid", offset)).as("SUSPENSE_UNMATCHED already nets them").isNull();
        for (String[] side : List.of(creditToOffset, debitToOffset)) {
            assertThat(one("SELECT status || ':' || released_minor FROM"
                    + " reconciliation.suspense_item WHERE id = ?::uuid", side[2]))
                    .isEqualTo("RELEASED:1700");
            assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?::uuid",
                    side[3])).isEqualTo("RESOLVED");
        }
        assertBooksHold("the statement's lines transferred and offset");
    }

    // ----------------------------------------------------------------- 4. repudiation

    @Test
    @Order(4)
    @DisplayName("a fabricated PSP report repudiated four-eyes - its recognition reversed once, the"
            + " capture's allocation countered and the capture reopened - then its genuine"
            + " re-presentation accepted into the freed identity, settling the capture and closing"
            + " the overdue break raised meanwhile EVIDENCED: the books hold at every step")
    void repudiationThenTheGenuineRepresentation() throws Exception {
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String marker = letters(10);
        LocalDate day = LocalDate.parse("2026-09-25");
        String operationRef = "op-p8tst2-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        String batchRef = "PSPB-BAT-" + marker;
        seedCapture(operationRef, captureRef, 100_00, day.minusDays(1));

        UUID fabricated = acceptedBatch(PSP_SOURCE,
                pspReport(batchRef, day, captureRef, "100.00", "1.75", "98.25", remittanceRef(),
                        "Sale"));
        matchUntilQuiet();
        UUID capture = (UUID) one("SELECT id FROM reconciliation.expectation WHERE kind ="
                + " 'CARD_CAPTURE' AND operation_ref = ?", operationRef);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", capture))
                .isEqualTo("SETTLED");
        UUID recognition = (UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE id ="
                + " ?", fabricated);
        assertBooksHold("the fabricated report accepted and matched");

        HttpResponse<String> proposed = call(proposer.token(), "POST",
                BASE + "/batches/" + fabricated + "/repudiation",
                "{\"reasonCode\":\"EVIDENCE_REPUDIATED\",\"narrative\":\"fabricated report "
                        + NEEDLE + "\"}", key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String repudiation = field(proposed.body(), "resolutionId");
        HttpResponse<String> self = approve(proposer, repudiation);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("reconciliation.SelfApprovalRefused");
        HttpResponse<String> approved = approve(approver, repudiation);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reverses_entry_id = ?"
                + " AND entry_type = 'REVERSAL'", recognition))
                .as("the recognition reversed once (INV-REV-01)").isEqualTo(1);
        assertThat(one("SELECT status || ':' || allocated_minor FROM reconciliation.expectation"
                + " WHERE id = ?", capture)).as("the capture owes its whole value again")
                .isEqualTo("OPEN:0");
        assertThat(one("SELECT status FROM settlement.batch WHERE id = ?", fabricated))
                .isEqualTo("REPUDIATED");
        PERSON_RESOLUTIONS.add(repudiation);
        assertBooksHold("the fabricated report repudiated");

        // Ageing's overdue break on the reopened capture, stood in for: the platform's raise.
        UUID pspSource;
        UUID pspRuleSet;
        try (Connection app = DatabaseRoles.application()) {
            pspSource = settlementFileStore.sourceByCode(app, PSP_SOURCE).orElseThrow().id();
            pspRuleSet = ruleSets.activeFor(app, pspSource).id();
        }
        UUID overdue = IDS.next();
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            breakRegister.raise(app, new BreakRegister.NewBreak(
                    overdue, BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                    BreakRegister.Subject.expectation(capture), pspSource, pspRuleSet,
                    Money.ofPersisted(100_00, EUR, 2), Optional.of(ExpectationDirection.INBOUND),
                    Optional.of(ExpectationKind.CARD_CAPTURE), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), PLATFORM, Instant.now(CLOCK),
                    CorrelationId.generate(IDS)));
            app.commit();
        }

        UUID genuine = acceptedBatch(PSP_SOURCE,
                pspReport(batchRef, day, captureRef, "100.00", "1.75", "98.25", remittanceRef(),
                        "Genuine sale"));
        matchUntilQuiet();
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE external_batch_ref = ? AND"
                + " status NOT IN ('REJECTED', 'REPUDIATED')", batchRef))
                .as("one live batch per identity").isEqualTo(1);
        assertThat(one("SELECT status FROM settlement.batch WHERE id = ?", genuine))
                .isEqualTo("ACCEPTED");
        assertThat(one("SELECT status || ':' || allocated_minor FROM reconciliation.expectation"
                + " WHERE id = ?", capture)).as("the genuine line settles the reopened capture")
                .isEqualTo("SETTLED:10000");
        assertThat(one("SELECT b.status || ':' || r.kind || ':' || r.proposed_by_type FROM"
                + " reconciliation.break b JOIN reconciliation.resolution r ON r.break_id = b.id"
                + " AND r.status = 'APPROVED' WHERE b.id = ?", overdue))
                .as("the late settlement closes the overdue break by evidence")
                .isEqualTo("RESOLVED:EVIDENCED:SYSTEM");
        evidencedResolution = one("SELECT id::text FROM reconciliation.resolution WHERE"
                + " break_id = ? AND kind = 'EVIDENCED'", overdue).toString();
        assertBooksHold("the genuine report accepted and matched");
    }

    // ----------------------------------------------------------------- 5. the generic door

    @Test
    @Order(5)
    @DisplayName("the ledger's generic door: a free adjustment on every reconciled position -"
            + " the two P&L accounts, the clearing and suspense - is 422"
            + " AdjustmentOnReconciledPosition; every RECONCILIATION_LOSSES and"
            + " RECONCILIATION_GAINS line belongs to an approved resolution's entry")
    void theGenericDoorAndThePlScan() throws Exception {
        Session ledger = sessionWith(RoleName.LEDGER_OPERATOR);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (AccountPurpose reconciled :
                List.of(AccountPurpose.RECONCILIATION_LOSSES, AccountPurpose.RECONCILIATION_GAINS,
                        AccountPurpose.SETTLEMENT_CLEARING, AccountPurpose.SUSPENSE_UNMATCHED)) {
            UUID position = operational(reconciled, EUR);
            String body = "{\"postingDate\":\"" + today + "\",\"valueDate\":\"" + today + "\","
                    + "\"reference\":\"adj-battery-probe\",\"reason\":\"free probe\","
                    + "\"lines\":[{\"accountId\":\"" + position + "\",\"direction\":\"DEBIT\","
                    + "\"amount\":\"1.00\",\"currency\":\"EUR\"},{\"accountId\":\""
                    + counterpartWallet + "\",\"direction\":\"CREDIT\",\"amount\":\"1.00\","
                    + "\"currency\":\"EUR\"}]}";
            HttpResponse<String> refused =
                    call(ledger.token(), "POST", "/v1/ledger/adjustments", body, key());
            assertThat(refused.statusCode()).as(reconciled.name()).isEqualTo(422);
            assertThat(refused.body()).contains("ledger.AdjustmentOnReconciledPosition");
        }
        assertThat(count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id WHERE a.purpose IN ('RECONCILIATION_LOSSES',"
                + " 'RECONCILIATION_GAINS') AND l.entry_id NOT IN (SELECT journal_entry_id FROM"
                + " reconciliation.resolution WHERE status = 'APPROVED' AND kind IN"
                + " ('WRITE_OFF', 'RECOGNISE_GAIN') AND journal_entry_id IS NOT NULL)"))
                .as("the two P&L positions posted by nothing but approved write-offs and gains")
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id WHERE a.purpose = 'RECONCILIATION_LOSSES'"))
                .as("this suite's write-offs posted there").isEqualTo(2);
        for (String proposal : RECONCILIATION_PROPOSALS) {
            assertThat(one("SELECT origin FROM ledger.adjustment_proposal WHERE id = ?::uuid",
                    proposal)).isEqualTo("RECONCILIATION");
        }
    }

    // ----------------------------------------------------------------- 6. the scans

    @Test
    @Order(6)
    @DisplayName("the scans: one BreakResolved per resolved break; BreakResolvedByEvidence for the"
            + " platform's resolutions only; no narrative or note in any audit body, event or"
            + " receipt;"
            + " the resolution meter's deltas per kind and outcome equal the rows produced; every"
            + " released suspense item traced to a resolution or a decision; the books hold")
    void theScans() throws Exception {
        assertThat(count("SELECT count(*) FROM reconciliation.break b WHERE b.status = 'RESOLVED'"
                + " AND (SELECT count(*) FROM platform.outbox_event e WHERE e.event_type ="
                + " 'reconciliation.BreakResolved' AND e.aggregate_id = b.id) <> 1"))
                .as("exactly one reconciliation.BreakResolved per resolved break").isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record a JOIN"
                + " reconciliation.resolution r ON r.id::text = a.target_id WHERE a.operation ="
                + " 'reconciliation.BreakResolvedByEvidence' AND r.kind <> 'EVIDENCED'"))
                .as("BreakResolvedByEvidence names only the platform's EVIDENCED").isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.BreakResolvedByEvidence' AND target_id = ?",
                evidencedResolution)).as("the late settlement's, once").isEqualTo(1);
        for (String person : PERSON_RESOLUTIONS) {
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                    + " 'reconciliation.BreakResolvedByEvidence' AND target_id = ?", person))
                    .as("a person's resolution is never the platform's evidence").isZero();
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                    + " operation = 'reconciliation.ResolutionProposed'", person))
                    .as("every person's proposal audited").isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE"
                + " coalesce(change_summary, '') || coalesce(reason, '') LIKE ?",
                "%" + NEEDLE + "%")).as("no narrative in any audit body").isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                + " convert_from(payload, 'UTF8') LIKE ?", "%" + NEEDLE + "%"))
                .as("no narrative in any event").isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE"
                + " convert_from(response_body, 'UTF8') LIKE ?", "%" + NEEDLE + "%"))
                .as("no narrative in any stored receipt").isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE narrative LIKE ?",
                "%" + NEEDLE + "%")).as("the narratives themselves stand, CONFIDENTIAL, where"
                + " they belong").isPositive();
        assertThat(count("SELECT count(*) FROM reconciliation.break_note WHERE body LIKE ?",
                "%" + NEEDLE + "%")).as("and the note stands in its own row").isEqualTo(1);

        Map<ResolutionKind, Map<ResolutionOutcome, Double>> after = meters();
        Map<String, Long> rowsAfter = resolutionRows();
        for (ResolutionKind kind : ResolutionKind.values()) {
            for (ResolutionOutcome outcome : ResolutionOutcome.values()) {
                long expected = switch (outcome) {
                    case APPROVED -> kind == ResolutionKind.EVIDENCED
                            ? 0 : delta(rowsAfter, kind, "APPROVED");
                    case EVIDENCED -> kind == ResolutionKind.EVIDENCED
                            ? delta(rowsAfter, kind, "APPROVED") : 0;
                    case REJECTED -> delta(rowsAfter, kind, "REJECTED");
                    case WITHDRAWN -> delta(rowsAfter, kind, "WITHDRAWN");
                    case STALE -> 0;
                };
                assertThat(after.get(kind).get(outcome) - metersBefore.get(kind).get(outcome))
                        .as("finapp.reconciliation.resolution{type=%s, outcome=%s}", kind,
                                outcome)
                        .isEqualTo((double) expected);
            }
        }
        assertThat(delta(rowsAfter, ResolutionKind.WRITE_OFF, "APPROVED")).isEqualTo(2);
        assertThat(delta(rowsAfter, ResolutionKind.EVIDENCED, "APPROVED")).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release r WHERE NOT ("
                + " (r.cause_ref LIKE 'resolution=%' AND EXISTS (SELECT 1 FROM"
                + " reconciliation.resolution x WHERE 'resolution=' || x.id = r.cause_ref AND"
                + " x.status = 'APPROVED'))"
                + " OR (r.cause_ref LIKE 'decision=%' AND EXISTS (SELECT 1 FROM"
                + " reconciliation.match_decision d WHERE 'decision=' || d.id = r.cause_ref)))"))
                .as("every released suspense item traced to its resolution or evidence")
                .isZero();
        assertBooksHold("after every crossing");
    }

    // ----------------------------------------------------------------- 7. the negatives

    @Test
    @Order(7)
    @DisplayName("every resolution door, the repudiation door included, holds"
            + " RECONCILIATION_RESOLVE: anonymous 401, role-less 403, the controller's disjoint"
            + " desk 403")
    void everyDoorHoldsItsPermission() throws Exception {
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session roleless = rolelessSession();
        String some = UUID.randomUUID().toString();
        record Door(String method, String path, String body) {}
        List<Door> doors = List.of(
                new Door("POST", "/breaks/" + some + "/resolutions",
                        proposal("WRITE_OFF", "LOSS_ACCEPTED", null, null)),
                new Door("POST", "/batches/" + some + "/repudiation",
                        "{\"reasonCode\":\"EVIDENCE_REPUDIATED\",\"narrative\":\"n\"}"),
                new Door("POST", "/resolutions/" + some + "/approval", null),
                new Door("POST", "/resolutions/" + some + "/rejection", "{\"reason\":\"r\"}"),
                new Door("DELETE", "/resolutions/" + some, null));
        for (Door door : doors) {
            assertThat(call(null, door.method(), BASE + door.path(), door.body(), key())
                    .statusCode()).as("%s %s anonymous", door.method(), door.path())
                    .isEqualTo(401);
            assertThat(call(roleless.token(), door.method(), BASE + door.path(), door.body(),
                    key()).statusCode()).as("%s %s role-less", door.method(), door.path())
                    .isEqualTo(403);
            assertThat(call(controller.token(), door.method(), BASE + door.path(), door.body(),
                    key()).statusCode())
                    .as("%s %s: the controller cannot resolve", door.method(), door.path())
                    .isEqualTo(403);
        }
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
                        .as("%s: suspense %s", when, verdict.currency()).isTrue());
        assertThat(report.cashVerdicts())
                .as("%s: the cash chains", when)
                .allSatisfy(verdict -> assertThat(verdict.explained())
                        .as("%s: cash %s", when, verdict.currency()).isTrue());
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
                return positionProof.sweep(app);
            } finally {
                app.rollback();
            }
        }
    }

    // ----------------------------------------------------------------- the meter and the rows

    private Map<ResolutionKind, Map<ResolutionOutcome, Double>> meters() {
        Map<ResolutionKind, Map<ResolutionOutcome, Double>> all =
                new EnumMap<>(ResolutionKind.class);
        for (ResolutionKind kind : ResolutionKind.values()) {
            Map<ResolutionOutcome, Double> byOutcome = new EnumMap<>(ResolutionOutcome.class);
            for (ResolutionOutcome outcome : ResolutionOutcome.values()) {
                byOutcome.put(outcome, meterRegistry.get(ReconciliationOutcomeMeters.RESOLUTION)
                        .tag("type", kind.name().toLowerCase(java.util.Locale.ROOT))
                        .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                        .counter()
                        .count());
            }
            all.put(kind, byOutcome);
        }
        return all;
    }

    private static Map<String, Long> resolutionRows() throws SQLException {
        Map<String, Long> counted = new java.util.TreeMap<>();
        for (String row : rows("SELECT kind || '/' || status || '/' || count(*) FROM"
                + " reconciliation.resolution GROUP BY kind, status")) {
            String[] parts = row.split("/");
            counted.put(parts[0] + "/" + parts[1], Long.parseLong(parts[2]));
        }
        return counted;
    }

    private static long delta(Map<String, Long> after, ResolutionKind kind, String status) {
        String key = kind.name() + "/" + status;
        return after.getOrDefault(key, 0L) - rowsBefore.getOrDefault(key, 0L);
    }

    // ----------------------------------------------------------------- seeding

    private record Subject(UUID expectationId, UUID breakId) {}

    /**
     * REAL value: a completion entry posted to the clearing against the counterpart wallet, the
     * expectation naming its line (the completeness verifier knows it), and the break raised on
     * it as its detector would.
     */
    private Subject completedExpectation(
            ExpectationDirection direction, long minor, BreakType type, BreakCause cause)
            throws Exception {
        UUID clearing = operational(AccountPurpose.SETTLEMENT_CLEARING, EUR);
        String operationRef = "op-bat-" + UUID.randomUUID();
        Money amount = Money.ofPersisted(minor, EUR, 2);
        List<JournalLine> lines =
                direction == ExpectationDirection.INBOUND
                        ? List.of(
                                new JournalLine(LedgerAccountId.of(clearing), Direction.DEBIT,
                                        amount),
                                new JournalLine(LedgerAccountId.of(counterpartWallet),
                                        Direction.CREDIT, amount))
                        : List.of(
                                new JournalLine(LedgerAccountId.of(counterpartWallet),
                                        Direction.DEBIT, amount),
                                new JournalLine(LedgerAccountId.of(clearing), Direction.CREDIT,
                                        amount));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        UUID breakId = IDS.next();
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID entry = postingService.post(app, new PostingCommand(
                            "p8tst2-completion:" + operationRef, today, today, operationRef,
                            lines))
                    .entryId().value();
            ExpectationKind kind = direction == ExpectationDirection.INBOUND
                    ? ExpectationKind.CARD_CAPTURE : ExpectationKind.CARD_REFUND;
            expectationRegister.open(
                    app,
                    new NewExpectation(
                            kind, operationRef, "p8tst2:" + operationRef, PRIVATE_SOURCE,
                            AccountPurpose.SETTLEMENT_CLEARING, clearing, direction, amount,
                            Optional.of(entry), today, Optional.empty(), FAR, PRIVATE_RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(
                                    direction == ExpectationDirection.INBOUND
                                            ? KeyKind.PSP_CAPTURE_REF : KeyKind.PSP_REFUND_REF,
                                    "BAT-" + UUID.randomUUID())),
                            PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            UUID expectation = (UUID) one(app, "SELECT id FROM reconciliation.expectation WHERE"
                    + " kind = ? AND operation_ref = ?", kind.name(), operationRef);
            breakRegister.raise(
                    app,
                    new BreakRegister.NewBreak(
                            breakId, type, cause, BreakRegister.Subject.expectation(expectation),
                            PRIVATE_SOURCE, PRIVATE_RULE_SET, amount, Optional.of(direction),
                            Optional.of(kind), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            app.commit();
            return new Subject(expectation, breakId);
        }
    }

    /** A captured card sale seeded WITH its posting and its CARD_CAPTURE expectation. */
    private void seedCapture(String operationRef, String captureRef, long minor, LocalDate on)
            throws Exception {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = ledgerAccountStore.createOrConverge(app, LedgerAccount.owned(
                    IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR,
                    IDS.next())).account();
            LedgerAccount clearing = ledgerAccountStore
                    .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR).orElseThrow();
            Money amount = Money.ofPersisted(minor, EUR, 2);
            UUID entry = postingService.post(app, new PostingCommand(
                            "payment-capture:" + operationRef, on, on, operationRef,
                            List.of(new JournalLine(clearing.id(), Direction.DEBIT, amount),
                                    new JournalLine(wallet.id(), Direction.CREDIT, amount))))
                    .entryId().value();
            UUID pspSource = settlementFileStore.sourceByCode(app, PSP_SOURCE).orElseThrow().id();
            expectationRegister.open(app, new NewExpectation(
                    ExpectationKind.CARD_CAPTURE, operationRef, "payment-capture:" + operationRef,
                    pspSource, AccountPurpose.SETTLEMENT_CLEARING, clearing.id().value(),
                    ExpectationDirection.INBOUND, amount, Optional.of(entry), on,
                    Optional.empty(), on.plusDays(3), ruleSets.activeFor(app, pspSource).id(),
                    List.of(new NewExpectation.ExpectationKey(KeyKind.PSP_CAPTURE_REF,
                            captureRef)),
                    Actor.SYSTEM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
        }
    }

    private static String pspReport(String batchRef, LocalDate day, String captureRef,
            String gross, String fee, String net, String remittanceRef, String narrative) {
        return "H,SIM_PSP_CSV,1," + batchRef + ",EUR," + day + "\n"
                + "D,1,SALE," + gross + "," + fee + ",EUR," + day + ",,," + captureRef
                + ",,,ORD-" + letters(8) + "," + narrative + "\n"
                + "T,1," + net + "," + remittanceRef + "\n";
    }

    private static String statement(String reference, String currency, long sequence,
            String opening, String closing, String... body) {
        StringBuilder text = new StringBuilder()
                .append(":20:").append(reference).append('\n')
                .append(":25:SIMBANK-").append(currency).append("-01\n")
                .append(":28C:").append(sequence).append('\n')
                .append(":60F:").append(opening).append('\n');
        for (String record : body) {
            text.append(record).append('\n');
        }
        return text.append(":62F:").append(closing).append('\n').toString();
    }

    private record Head(long sequence, long closingMinor) {}

    private static Head head(String currency) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT b.statement_sequence, b.closing_minor FROM settlement.batch b"
                                + " JOIN settlement.source s ON s.id = b.source_id WHERE s.code"
                                + " = ? AND b.currency = ? AND b.status = 'ACCEPTED' AND"
                                + " b.statement_sequence IS NOT NULL ORDER BY"
                                + " b.statement_sequence DESC LIMIT 1")) {
            read.setString(1, BANK_SOURCE);
            read.setString(2, currency);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? new Head(row.getLong(1), row.getLong(2)) : new Head(0, 0);
            }
        }
    }

    /** Minor units as the formats' two-decimal text - integer arithmetic only. */
    private static String decimal(long minor) {
        return (minor / 100) + "." + String.format("%02d", minor % 100);
    }

    private UUID acceptedBatch(String source, String content) throws SQLException {
        UUID fileId;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            FileReception.Result result = settlementTransactionRunner.inTransaction(
                    uow -> reception.receive(uow, new FileReception.Delivery(
                            source, DeliveryChannel.PULL,
                            content.getBytes(StandardCharsets.UTF_8), Optional.empty(),
                            Actor.SYSTEM, SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                            CorrelationContext.current().orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            fileId = ((FileReception.Result.New) result).fileId();
        }
        for (int sweep = 0; sweep < 50
                && "RECEIVED".equals(one("SELECT status FROM settlement.file WHERE id = ?",
                        fileId)); sweep++) {
            parsing.sweep();
        }
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", fileId))
                .as("parsed").isEqualTo("PARSED");
        for (int sweep = 0; sweep < 50
                && "PARSED".equals(one("SELECT status FROM settlement.file WHERE id = ?",
                        fileId)); sweep++) {
            acceptance.sweep();
        }
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", fileId))
                .as("accepted").isEqualTo("ACCEPTED");
        return (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", fileId);
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static UUID openAccount(AccountPurpose purpose, CurrencyCode currency, UUID owner)
            throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID account = new com.finapp.ledger.JdbcLedgerAccountStore()
                    .createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                            purpose, currency, owner))
                    .account().id().value();
            app.commit();
            return account;
        }
    }

    private static UUID operational(AccountPurpose purpose, CurrencyCode currency)
            throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new com.finapp.ledger.JdbcLedgerAccountStore()
                    .findOperational(app, purpose, currency).orElseThrow().id().value();
        }
    }

    private static void seedPrivateRuleSet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'PROPOSED', 2, 90, DATE '2026-09-25', 'test',"
                            + " NULL, 'ResolutionBatteryDatabaseTest private rule set', now(),"
                            + " 'p8-tst-002-app-test') ON CONFLICT (id) DO NOTHING",
                    PRIVATE_RULE_SET, PRIVATE_SOURCE);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', 100000) ON CONFLICT DO"
                            + " NOTHING",
                    PRIVATE_RULE_SET);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by ="
                            + " 'test-activator', decided_at = now() WHERE id = ? AND status ="
                            + " 'PROPOSED'",
                    PRIVATE_RULE_SET);
            app.commit();
        }
    }

    // ----------------------------------------------------------------- the doors

    /** A proposal body with this suite's marked narrative and the kind's one operand. */
    private static String proposal(String kind, String reasonCode, UUID target, UUID offsetItem) {
        return "{\"kind\":\"" + kind + "\",\"reasonCode\":\"" + reasonCode + "\","
                + "\"narrative\":\"the investigator's account " + NEEDLE + "\""
                + (target == null ? "" : ",\"targetAccountId\":\"" + target + "\"")
                + (offsetItem == null ? "" : ",\"offsetItemId\":\"" + offsetItem + "\"")
                + "}";
    }

    /** Proposed by one, approved by another: the resolution's id. */
    private String resolve(Session proposer, Session approver, UUID breakId, String body)
            throws Exception {
        HttpResponse<String> proposed = propose(proposer, breakId, key(), body);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String resolution = field(proposed.body(), "resolutionId");
        HttpResponse<String> self = approve(proposer, resolution);
        assertThat(self.statusCode()).as("DOMAIN RANK over HTTP").isEqualTo(409);
        assertThat(self.body()).contains("reconciliation.SelfApprovalRefused");
        HttpResponse<String> approved = approve(approver, resolution);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        PERSON_RESOLUTIONS.add(resolution);
        return resolution;
    }

    private HttpResponse<String> propose(Session proposer, UUID breakId, String key, String body)
            throws Exception {
        return call(proposer.token(), "POST", BASE + "/breaks/" + breakId + "/resolutions", body,
                key);
    }

    private HttpResponse<String> approve(Session approver, String resolutionId) throws Exception {
        return call(approver.token(), "POST", BASE + "/resolutions/" + resolutionId + "/approval",
                null, null);
    }

    private HttpResponse<String> reject(Session rejecter, String resolutionId, String reason)
            throws Exception {
        return call(rejecter.token(), "POST",
                BASE + "/resolutions/" + resolutionId + "/rejection",
                "{\"reason\":\"" + reason + "\"}", null);
    }

    private HttpResponse<String> withdraw(Session proposer, String resolutionId)
            throws Exception {
        return call(proposer.token(), "DELETE", BASE + "/resolutions/" + resolutionId, null,
                null);
    }

    private HttpResponse<String> call(
            String token, String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (key != null) {
                request.header(IdempotencyKeyHeader.NAME, key);
            }
        } else if ("DELETE".equals(method)) {
            request.DELETE();
        } else {
            request.GET();
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String key() {
        return "bat-" + UUID.randomUUID();
    }

    private static String field(String json, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, json).isTrue();
        return matcher.group(1);
    }

    /** The entry's lines as purpose:direction:minor, read against the chart. */
    private static List<String> entryLines(UUID entryId) throws SQLException {
        return rows("SELECT a.purpose || ':' || l.direction || ':' || l.amount_minor::text FROM"
                + " ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                + " l.ledger_account_id WHERE l.entry_id = ?", entryId);
    }

    // ----------------------------------------------------------------- sessions

    private record Session(IdentityId identity, String token) {}

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
                            + " VALUES (?, 'PERSON', 'Battery Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "bt" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
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
        return Correlation.startingWith(CorrelationId.of("p8tst2-" + UUID.randomUUID()))
                .causing(CausationId.of("p8tst2-cause"));
    }

    /** Letters only: no digit run the screens could read as a card number. */
    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('a' + RANDOMNESS.nextInt(26)));
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

    private static List<String> rows(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            List<String> found = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    found.add(row.getString(1));
                }
            }
            return found;
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }
}
