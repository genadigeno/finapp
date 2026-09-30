package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.ledger.JdbcStatementDerivation;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.StatementDerivation;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayable;
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
import com.finapp.reconciliation.NewExpectation;
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
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The resolver's desk over the app's own composition and real HTTP (`P8-TSK-015`, ADR-0071):
 * a keyed proposal that replays and a second key refused, the generic ledger doors refusing a
 * REAL reconciliation proposal both ways, self-approval refused and the second person's approval
 * posting — the position, suspense and completeness proofs explained after each; an approved
 * transfer of an OUTBOUND clearing remainder reading as {@code reconciliationAttributed} on the
 * merchant's payable, never a capture, and as {@code RECONCILIATION_ATTRIBUTION} on the
 * customer's statement; the two P&amp;L positions closed to free adjustment at both ranks and
 * posted by nothing but approvals; and every door's negatives three ways.
 *
 * <p>Identity discipline (the `P8-TSK-012` lesson): every subject is REAL value — its expectation
 * names a completion entry actually posted to the clearing — so each approval moves a proved
 * position and its remainder together, and the shared container's proofs stay explained after
 * this suite's residue.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the resolver's desk composed, over HTTP (P8-TSK-015)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class ReconciliationResolutionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID PRIVATE_SOURCE =
            UUID.fromString("01a0e2bc-8200-7017-8000-000000000017");
    private static final UUID PRIVATE_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7017-8000-000000000017");
    private static final LocalDate FAR = LocalDate.parse("2027-12-31");
    private static final String BASE = "/v1/operator/reconciliation";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BreakRegister breakRegister;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private PostingService postingService;
    @Autowired private PositionProof positionProof;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    /** The completion entries' counterpart: credited by the INBOUND one before any debit. */
    private static UUID counterpartWallet;

    // ----------------------------------------------------------------- the write-off

    @Test
    @Order(1)
    @DisplayName("a keyed proposal replays and a second key is refused; the generic ledger doors"
            + " refuse the REAL reconciliation proposal both ways; the proposer's approval is"
            + " refused and a second operator's posts - the proofs explained after; the"
            + " narrative reaches no log, event, audit or idempotency record")
    void aWriteOffOverHttp(CapturedOutput output) throws Exception {
        seedPrivateRuleSet();
        PositionProof.Report before = proofs();
        counterpartWallet = openAccount(AccountPurpose.CUSTOMER_WALLET, IDS.next());
        Subject subject = completedExpectation(ExpectationDirection.INBOUND, 12_00);
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session ledger = sessionWith(RoleName.LEDGER_OPERATOR);

        String needle = "needle" + UUID.randomUUID().toString().replace("-", "");
        String body = "{\"kind\":\"WRITE_OFF\",\"reasonCode\":\"LOSS_ACCEPTED\","
                + "\"narrative\":\"the PSP will never pay " + needle + "\"}";
        String key = "rsl-" + UUID.randomUUID();
        HttpResponse<String> proposed =
                post(proposer.token(), "/breaks/" + subject.breakId() + "/resolutions", key, body);
        assertThat(proposed.statusCode()).isEqualTo(201);
        assertThat(proposed.body())
                .contains("\"status\":\"PROPOSED\"")
                .contains("\"fourEyes\":true")
                .doesNotContain(needle);
        HttpResponse<String> replayed =
                post(proposer.token(), "/breaks/" + subject.breakId() + "/resolutions", key, body);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(replayed.body()).as("a lost response replays the receipt")
                .isEqualTo(proposed.body());
        HttpResponse<String> second = post(proposer.token(), "/breaks/" + subject.breakId()
                + "/resolutions", "rsl-" + UUID.randomUUID(), body);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("reconciliation.ResolutionAlreadyProposed");

        String resolution = field(proposed.body(), "resolutionId");
        String proposal = field(proposed.body(), "adjustmentProposalId");
        HttpResponse<String> generic = call(ledger.token(), "POST",
                "/v1/ledger/adjustments/" + proposal + "/approval", null, null);
        assertThat(generic.statusCode())
                .as("a LEDGER_ADJUST holder cannot post a resolution's lines around the break")
                .isEqualTo(409);
        assertThat(generic.body()).contains("ledger.AdjustmentOriginMismatch");
        HttpResponse<String> genericDelete = call(ledger.token(), "DELETE",
                "/v1/ledger/adjustments/" + proposal, null, null);
        assertThat(genericDelete.statusCode()).isEqualTo(409);
        assertThat(genericDelete.body()).contains("ledger.AdjustmentOriginMismatch");

        HttpResponse<String> self =
                post(proposer.token(), "/resolutions/" + resolution + "/approval", null, null);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("reconciliation.SelfApprovalRefused");

        HttpResponse<String> approved =
                post(approver.token(), "/resolutions/" + resolution + "/approval", null, null);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"");
        String entry = field(approved.body(), "journalEntryId");
        HttpResponse<String> retried =
                post(approver.token(), "/resolutions/" + resolution + "/approval", null, null);
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(field(retried.body(), "journalEntryId")).as("a retry converges")
                .isEqualTo(entry);
        assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE entry_id = ?::uuid",
                entry)).isEqualTo(2);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                subject.expectationId())).isEqualTo("RESOLVED_BY_ADJUSTMENT");

        assertProofsUnmovedSince(before);
        assertThat(output.getAll()).as("the narrative never reaches a log").doesNotContain(needle);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE change_summary LIKE"
                + " ? OR reason LIKE ?", "%" + needle + "%", "%" + needle + "%")).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                + " convert_from(payload, 'UTF8') LIKE ?", "%" + needle + "%")).isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE"
                + " convert_from(response_body, 'UTF8') LIKE ?", "%" + needle + "%"))
                .as("the stored receipt carries no narrative")
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.adjustment_proposal WHERE reason LIKE ?",
                "%" + needle + "%")).as("the ledger's reason is identifiers and codes").isZero();

        HttpResponse<String> pan = post(proposer.token(), "/breaks/" + subject.breakId()
                + "/resolutions", "rsl-pan-" + UUID.randomUUID(),
                "{\"kind\":\"WRITE_OFF\",\"reasonCode\":\"LOSS_ACCEPTED\","
                        + "\"narrative\":\"card 4111111111111111\"}");
        assertThat(pan.statusCode()).isEqualTo(422);
        assertThat(pan.body()).contains("api.ValidationFailed").doesNotContain("4111");
    }

    // ----------------------------------------------------------------- attributions

    @Test
    @Order(2)
    @DisplayName("an OUTBOUND clearing remainder transferred to a merchant payable reads as"
            + " reconciliationAttributed - never captured - and one transferred to a wallet is"
            + " labelled RECONCILIATION_ATTRIBUTION on the owner's statement")
    void anAttributionReadsAsItself() throws Exception {
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        PositionProof.Report before = proofs();

        UUID merchant = IDS.next();
        UUID payable = openAccount(AccountPurpose.MERCHANT_PAYABLE, merchant);
        Subject toPayable = completedExpectation(ExpectationDirection.OUTBOUND, 7_00);
        resolve(proposer, approver, toPayable,
                "{\"kind\":\"TRANSFER_TO_ACCOUNT\",\"reasonCode\":\"FUNDS_ATTRIBUTED\","
                        + "\"narrative\":\"the payout came back to the merchant\","
                        + "\"targetAccountId\":\"" + payable + "\"}");
        MerchantPayable.Payable owed;
        try (Connection app = DatabaseRoles.application()) {
            owed = new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown())
                    .payablesOf(app, MerchantId.of(merchant)).get(0);
        }
        assertThat(owed.reconciliationAttributed()).isEqualTo(Money.ofPersisted(7_00, EUR, 2));
        assertThat(owed.captured().isZero())
                .as("the line faces the clearing it debited, yet the origin rule reads first")
                .isTrue();
        assertThat(owed.terms()).isEqualTo(owed.position());
        assertThat(owed.position()).isEqualTo(Money.ofPersisted(7_00, EUR, 2));

        UUID customer = IDS.next();
        UUID wallet = openAccount(AccountPurpose.CUSTOMER_WALLET, customer);
        Subject toWallet = completedExpectation(ExpectationDirection.OUTBOUND, 5_00);
        resolve(proposer, approver, toWallet,
                "{\"kind\":\"TRANSFER_TO_ACCOUNT\",\"reasonCode\":\"FUNDS_ATTRIBUTED\","
                        + "\"narrative\":\"the customer's own funds\","
                        + "\"targetAccountId\":\"" + wallet + "\"}");
        List<StatementDerivation.AccountStatement> statements;
        try (Connection app = DatabaseRoles.application()) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            statements = new JdbcStatementDerivation(new JdbcBalanceDerivation())
                    .statementsFor(app, customer, today.minusDays(3), today.plusDays(3));
        }
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).lines()).hasSize(1);
        StatementDerivation.StatementLine line = statements.get(0).lines().get(0);
        assertThat(line.reconciliationAttribution()).isTrue();
        assertThat(line.direction()).isEqualTo(Direction.CREDIT);
        assertThat(line.amount()).isEqualTo(Money.ofPersisted(5_00, EUR, 2));
        assertThat(line.entryType().name()).isEqualTo("ADJUSTMENT");

        assertProofsUnmovedSince(before);
    }

    // ----------------------------------------------------------------- the closed positions

    @Test
    @Order(3)
    @DisplayName("RECONCILIATION_LOSSES and RECONCILIATION_GAINS: a MANUAL line is 422 at the"
            + " door and refused by V017's re-stated trigger for a raw writer; every line on"
            + " them belongs to an approved resolution's entry")
    void theProfitAndLossPositionsAreClosed() throws Exception {
        Session ledger = sessionWith(RoleName.LEDGER_OPERATOR);
        for (AccountPurpose closed :
                List.of(AccountPurpose.RECONCILIATION_LOSSES, AccountPurpose.RECONCILIATION_GAINS)) {
            UUID position = operational(closed);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String body = "{\"postingDate\":\"" + today + "\",\"valueDate\":\"" + today + "\","
                    + "\"reference\":\"adj-pl-probe\",\"reason\":\"free P&L probe\","
                    + "\"lines\":[{\"accountId\":\"" + position + "\",\"direction\":\"DEBIT\","
                    + "\"amount\":\"1.00\",\"currency\":\"EUR\"},{\"accountId\":\""
                    + counterpartWallet + "\",\"direction\":\"CREDIT\",\"amount\":\"1.00\","
                    + "\"currency\":\"EUR\"}]}";
            HttpResponse<String> refused = call(ledger.token(), "POST", "/v1/ledger/adjustments",
                    body, "adj-" + IDS.next());
            assertThat(refused.statusCode()).as(closed.name()).isEqualTo(422);
            assertThat(refused.body()).contains("ledger.AdjustmentOnReconciledPosition");

            try (Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                UUID proposal = IDS.next();
                execute(app, "INSERT INTO ledger.adjustment_proposal (id, status, posting_date,"
                        + " value_date, reference, reason, proposed_by, proposed_at, reason_code,"
                        + " origin) VALUES (?, 'PROPOSED', current_date, current_date,"
                        + " 'raw-pl-probe', 'raw probe', 'op-raw', now(), 'MANUAL_CORRECTION',"
                        + " 'MANUAL')", proposal);
                assertThatThrownBy(() -> execute(app, "INSERT INTO ledger.adjustment_proposal_line"
                        + " (proposal_id, seq, ledger_account_id, direction, amount_minor,"
                        + " currency, scale) VALUES (?, 0, ?, 'DEBIT', 100, 'EUR', 2)",
                        proposal, position))
                        .as(closed.name())
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("closed to free adjustments");
                app.rollback();
            }
        }
        assertThat(count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id WHERE a.purpose IN ('RECONCILIATION_LOSSES',"
                + " 'RECONCILIATION_GAINS') AND l.entry_id NOT IN (SELECT journal_entry_id FROM"
                + " reconciliation.resolution WHERE status = 'APPROVED' AND journal_entry_id IS"
                + " NOT NULL)"))
                .as("posted by nothing but approved resolutions")
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id WHERE a.purpose = 'RECONCILIATION_LOSSES'"))
                .as("this suite's write-off posted there").isPositive();
    }

    // ----------------------------------------------------------------- the negatives

    @Test
    @Order(4)
    @DisplayName("every door holds RECONCILIATION_RESOLVE - anonymous 401, role-less 403, the"
            + " controller's disjoint desk 403 - and unknown or malformed ids are one named"
            + " 404 each")
    void everyDoorHoldsItsPermission() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session roleless = rolelessSession();
        String some = UUID.randomUUID().toString();
        String proposal = "{\"kind\":\"WRITE_OFF\",\"reasonCode\":\"LOSS_ACCEPTED\","
                + "\"narrative\":\"n\"}";
        record Door(String method, String path, String body) {}
        List<Door> doors = List.of(
                new Door("POST", "/breaks/" + some + "/resolutions", proposal),
                new Door("POST", "/resolutions/" + some + "/approval", null),
                new Door("POST", "/resolutions/" + some + "/rejection", "{\"reason\":\"r\"}"),
                new Door("DELETE", "/resolutions/" + some, null));
        for (Door door : doors) {
            assertThat(call(null, door.method(), BASE + door.path(), door.body(), key())
                            .statusCode())
                    .as("%s %s anonymous", door.method(), door.path())
                    .isEqualTo(401);
            assertThat(call(roleless.token(), door.method(), BASE + door.path(), door.body(),
                            key()).statusCode())
                    .as("%s %s role-less", door.method(), door.path())
                    .isEqualTo(403);
            assertThat(call(controller.token(), door.method(), BASE + door.path(), door.body(),
                            key()).statusCode())
                    .as("%s %s: whoever can loosen a tolerance cannot resolve the breaks it"
                            + " would hide", door.method(), door.path())
                    .isEqualTo(403);
        }

        record Absent(String method, String path, String body, String code) {}
        List<Absent> absents = List.of(
                new Absent("POST", "/breaks/" + some + "/resolutions", proposal,
                        "reconciliation.BreakNotFound"),
                new Absent("POST", "/breaks/not-a-uuid/resolutions", proposal,
                        "reconciliation.BreakNotFound"),
                new Absent("POST", "/resolutions/" + some + "/approval", null,
                        "reconciliation.ResolutionNotFound"),
                new Absent("POST", "/resolutions/not-a-uuid/approval", null,
                        "reconciliation.ResolutionNotFound"),
                new Absent("POST", "/resolutions/" + some + "/rejection", "{\"reason\":\"r\"}",
                        "reconciliation.ResolutionNotFound"),
                new Absent("DELETE", "/resolutions/" + some, null,
                        "reconciliation.ResolutionNotFound"));
        for (Absent absent : absents) {
            HttpResponse<String> answer = call(operator.token(), absent.method(),
                    BASE + absent.path(), absent.body(), key());
            assertThat(answer.statusCode()).as(absent.path()).isEqualTo(404);
            assertThat(answer.body()).as(absent.path()).contains(absent.code());
        }
        HttpResponse<String> evidenced = post(operator.token(), "/breaks/" + some
                + "/resolutions", key(), "{\"kind\":\"EVIDENCED\",\"reasonCode\":"
                + "\"EVIDENCE_RECEIVED\",\"narrative\":\"n\"}");
        assertThat(evidenced.statusCode()).as("EVIDENCED is refused before any lookup")
                .isEqualTo(422);
        assertThat(evidenced.body()).contains("reconciliation.ResolutionKindNotAllowed");
    }

    // ----------------------------------------------------------------- seeding

    private record Subject(UUID expectationId, UUID breakId) {}

    private void resolve(Session proposer, Session approver, Subject subject, String body)
            throws Exception {
        HttpResponse<String> proposed = post(proposer.token(), "/breaks/" + subject.breakId()
                + "/resolutions", key(), body);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = post(approver.token(), "/resolutions/"
                + field(proposed.body(), "resolutionId") + "/approval", null, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
    }

    /**
     * REAL value: a completion entry posted to the clearing against the counterpart wallet, the
     * expectation naming its clearing line (so the completeness verifier knows it), and the
     * overdue break raised on it.
     */
    private Subject completedExpectation(ExpectationDirection direction, long minor)
            throws Exception {
        UUID clearing = operational(AccountPurpose.SETTLEMENT_CLEARING);
        String operationRef = "op-rsl-" + UUID.randomUUID();
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
        UUID entry;
        UUID breakId = IDS.next();
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            entry = postingService.post(app, new PostingCommand(
                            "p8t15-completion:" + operationRef, today, today, operationRef,
                            lines))
                    .entryId().value();
            ExpectationKind kind = direction == ExpectationDirection.INBOUND
                    ? ExpectationKind.CARD_CAPTURE : ExpectationKind.CARD_REFUND;
            expectationRegister.open(
                    app,
                    new NewExpectation(
                            kind, operationRef, "p8t15:" + operationRef, PRIVATE_SOURCE,
                            AccountPurpose.SETTLEMENT_CLEARING, clearing, direction, amount,
                            Optional.of(entry), today, Optional.empty(), FAR, PRIVATE_RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(
                                    direction == ExpectationDirection.INBOUND
                                            ? KeyKind.PSP_CAPTURE_REF : KeyKind.PSP_REFUND_REF,
                                    "RSL-" + UUID.randomUUID())),
                            PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            UUID expectation = (UUID) one(app, "SELECT id FROM reconciliation.expectation WHERE"
                    + " kind = ? AND operation_ref = ?", kind.name(), operationRef);
            breakRegister.raise(
                    app,
                    new BreakRegister.NewBreak(
                            breakId, BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                            BreakRegister.Subject.expectation(expectation), PRIVATE_SOURCE,
                            PRIVATE_RULE_SET, amount, Optional.of(direction), Optional.of(kind),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            app.commit();
            return new Subject(expectation, breakId);
        }
    }

    private PositionProof.Report proofs() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            PositionProof.Report report = positionProof.sweep(app);
            app.rollback();
            return report;
        }
    }

    /**
     * This suite's acts moved the proofs by NOTHING: every verdict as explained as before,
     * the proved position this suite posts to explained outright, and no reconciled line left
     * unknown that was known - judged against the shared container's own baseline, because
     * Phase 7's unadopted parkings legitimately read above zero until `P8-TSK-020` (the
     * transition's A7) and are no act of this suite's.
     */
    private void assertProofsUnmovedSince(PositionProof.Report before) throws SQLException {
        PositionProof.Report after = proofs();
        assertThat(explainedFlags(after))
                .as("no verdict flipped by an approval")
                .isEqualTo(explainedFlags(before));
        assertThat(after.verdicts())
                .filteredOn(verdict -> verdict.purpose() == AccountPurpose.SETTLEMENT_CLEARING
                        && verdict.currency().equals(EUR))
                .as("the position every approval here moved is explained by its remainders")
                .allMatch(PositionProof.PositionVerdict::explained);
        assertThat(after.suspenseVerdicts())
                .filteredOn(verdict -> verdict.currency().equals(EUR))
                .allMatch(PositionProof.SuspenseVerdict::explained);
        assertThat(after.unattributedByPurpose())
                .as("every resolution entry's lines are known the moment they post")
                .isEqualTo(before.unattributedByPurpose());
        assertThat(after.suspenseUnowned()).isEqualTo(before.suspenseUnowned());
    }

    private static java.util.Map<String, Boolean> explainedFlags(PositionProof.Report report) {
        java.util.Map<String, Boolean> flags = new java.util.TreeMap<>();
        report.verdicts().forEach(verdict -> flags.put(
                verdict.purpose() + ":" + verdict.currency().code(), verdict.explained()));
        report.suspenseVerdicts().forEach(verdict -> flags.put(
                "SUSPENSE:" + verdict.currency().code(), verdict.explained()));
        return flags;
    }

    private static UUID openAccount(AccountPurpose purpose, UUID owner) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID account = new JdbcLedgerAccountStore()
                    .createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                            purpose, EUR, owner))
                    .account().id().value();
            app.commit();
            return account;
        }
    }

    private static UUID operational(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new JdbcLedgerAccountStore().findOperational(app, purpose, EUR).orElseThrow()
                    .id().value();
        }
    }

    private static void seedPrivateRuleSet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'ACTIVE', 2, 90, DATE '2026-09-25', 'test',"
                            + " 'test', 'ReconciliationResolutionDatabaseTest private rule set',"
                            + " now(), 'p8-tsk-015-app-test') ON CONFLICT (id) DO NOTHING",
                    PRIVATE_RULE_SET, PRIVATE_SOURCE);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', 100000)"
                            + " ON CONFLICT DO NOTHING",
                    PRIVATE_RULE_SET);
            app.commit();
        }
    }

    // ----------------------------------------------------------------- the doors

    private HttpResponse<String> post(String token, String path, String key, String body)
            throws Exception {
        return call(token, "POST", BASE + path, body, key);
    }

    private HttpResponse<String> call(
            String token, String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
            if (key != null) {
                request.header("Idempotency-Key", key);
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
        return "rsl-" + UUID.randomUUID();
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as(name + " in " + json).isTrue();
        return matcher.group(1);
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
        return Correlation.startingWith(CorrelationId.of("p8t15-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t15-cause"));
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Resolver Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "rs" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
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
