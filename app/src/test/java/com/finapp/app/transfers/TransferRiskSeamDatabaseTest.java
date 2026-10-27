package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.TotpParameters;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityWindows;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import com.finapp.transfers.FailureReason;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferLimitCheck;
import com.finapp.transfers.TransferParticipants;
import com.finapp.transfers.TransferResult;
import com.finapp.transfers.TransferRiskDecision;
import com.finapp.transfers.TransferStatus;
import com.finapp.transfers.TransferStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Transfers judged by risk against a live PostgreSQL, through the composition root's own wiring (`P13-TSK-008`;
 * {@code PHASE_13_PLAN.md} sections 7.2-7.4, 12.3, scenarios 7, 9 and 14; ADR-0108; {@code INV-RSK-01},
 * {@code INV-RSK-02}, {@code INV-RSK-03}, {@code INV-RSK-04}, {@code INV-RSK-08}, {@code INV-CON-03},
 * {@code INV-IDEM-01}): the door's step-up answered {@code 403} with nothing written and the same key completing from a
 * {@code MULTI_FACTOR} session; an unsatisfied challenge reaching the command (the race) committed
 * {@code FAILED(RISK_REFUSED)} with nothing posted or counted; the EUR 10,000 limit committed
 * {@code FAILED(LIMIT_REFUSED)}; one assessment per transfer across both seams and across a retried key; ten
 * concurrent transfers of one party from ten accounts counted in the party's window exactly as the counted assessments
 * say (R2a); opposite transfers closing no lock cycle; the unavailable path committing the band's outcome, recorded;
 * a posting refused after the permit leaving the decision standing. <strong>Written and owed</strong> (owner decision
 * C5: the database tiers are skipped per task; the owner-authorised database pass before the exit review runs it).
 *
 * <p><strong>Its own container</strong> ({@code own-container}): it activates the C8 EUR {@code TRANSFER} version, and
 * from then on every transfer in the database is judged by it - a USD transfer of another suite in a shared container
 * would be refused by the open-ended fail-safe band (an amount the EUR bands cannot place). The first case runs before
 * the activation (the production default: no version, {@code ALLOW(NO_POLICY_ACTIVE)} recorded); every other case
 * activates once and keys its subjects fresh.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("transfers judged by risk: the seams in the real execution, the door's step-up (P13-TSK-008)")
class TransferRiskSeamDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();

    /** Words no customer answer about a judged transfer may carry (INV-RSK-08). */
    private static final List<String> RISK_WORDS = List.of("risk_unavailable", "amount_band", "velocity", "score",
            "assessment", "challenge", "screening", "rule", "reason1", "no_policy");

    @LocalServerPort private int port;
    @Autowired private TransferExecution transferExecution;
    @Autowired private TransferLimitCheck<Connection> transferLimitCheck;
    @Autowired private TransferRiskDecision<Connection> transferRiskDecision;
    @Autowired private TransferParticipants<Connection> transferParticipants;
    @Autowired private TransferStore<Connection> transferStore;

    private record Customer(String login, String token, UUID identity, UUID party) {}

    // ------------------------------------------------------------------ before any version

    @Test
    @Order(1)
    @DisplayName("scenario 9, no policy: the production default - ALLOW(NO_POLICY_ACTIVE) recorded and counted, the"
            + " transfer COMPLETED")
    void noPolicyRecordsAllowAndTheTransferPosts() throws Exception {
        assertThat(ACTIVATED).as("this case runs before the suite activates a version").isFalse();
        Customer payer = customer();
        UUID source = fundedWallet(payer, 1_000_00);
        HttpResponse<String> posted = transfer(payer.token(), source, wallet(customer()), "50.00", someKey());
        assertThat(posted.statusCode()).as(posted.body()).isEqualTo(201);
        assertThat(field(posted.body(), "status")).isEqualTo("COMPLETED");
        String transferId = field(posted.body(), "id");
        assertThat(column("SELECT decision || ',' || fail_safe_reason || ',' || unavailable_cause || ','"
                + " || velocity_outcome FROM risk.risk_assessment WHERE operation = 'TRANSFER' AND command_ref = ?",
                transferId)).containsExactly("ALLOW,NO_POLICY_ACTIVE,NO_POLICY,COUNTED");
        assertThat(windowCount("PARTY", payer.party(), "D1")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the door

    @Test
    @Order(2)
    @DisplayName("the door's step-up: EUR 600 from a password session is 403 identity.AssuranceRequired with nothing"
            + " written - no transfer, no assessment, no window; the same key from a MULTI_FACTOR session COMPLETED, the"
            + " challenge satisfied and counted")
    void anUnsatisfiedChallengeIsRefusedAtTheDoor() throws Exception {
        activateTransferPolicy();
        Customer payer = customer();
        UUID source = fundedWallet(payer, 2_000_00);
        UUID destination = wallet(customer());
        String key = someKey();

        HttpResponse<String> refused = transfer(payer.token(), source, destination, "600.00", key);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertNoRiskWord(refused.body());
        assertThat(count("SELECT count(*) FROM transfers.transfer t JOIN party.customer c ON c.id = t.customer_id"
                + " WHERE c.party_id = ?", payer.party())).isZero();
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", payer.party())).isZero();
        assertThat(count("SELECT count(*) FROM risk.velocity_window WHERE subject_ref = ?", payer.party().toString()))
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE scope = 'transfer.execute'"
                + " AND idempotency_key = ?", key)).as("the key was never claimed").isZero();

        String stepped = multiFactor(payer);
        HttpResponse<String> completed = transfer(stepped, source, destination, "600.00", key);
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(201);
        assertThat(field(completed.body(), "status")).isEqualTo("COMPLETED");
        assertThat(column("SELECT decision || ',' || challenge_satisfied || ',' || velocity_outcome"
                + " FROM risk.risk_assessment WHERE operation = 'TRANSFER' AND command_ref = ?",
                field(completed.body(), "id"))).containsExactly("CHALLENGE,true,COUNTED");
        assertThat(windowCount("PARTY", payer.party(), "D1")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the committed refusals

    @Test
    @Order(3)
    @DisplayName("an unsatisfied challenge reaching the command - no session, the race's answer - commits"
            + " FAILED(RISK_REFUSED): nothing posted, nothing counted, the assessment CHALLENGE unsatisfied")
    void aRefusedTransferCountsNothingAndCommitsRiskRefused() throws Exception {
        activateTransferPolicy();
        Customer payer = customer();
        UUID source = fundedWallet(payer, 2_000_00);
        TransferResult refused = executeDirectly(payer, source, wallet(customer()), eur(600_00));
        assertThat(refused.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(refused.failureReason()).contains(FailureReason.RISK_REFUSED);
        String transferId = refused.transferId().value().toString();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?", transferId)).isZero();
        assertThat(column("SELECT decision || ',' || challenge_satisfied || ',' || velocity_outcome"
                + " FROM risk.risk_assessment WHERE operation = 'TRANSFER' AND command_ref = ?", transferId))
                .containsExactly("CHALLENGE,false,NOT_PERMITTED");
        // The exit review's walk (P13-DOC-001; the gate's "Decision explainability": "a refused transfer ... walked by
        // id from the operation's outcome to its assessment to its hits"): the transfer's id is the assessment's command
        // ref; its fired hits name the EUR 500 amount band that asked the step-up, its reasons why.
        String assessment = "(SELECT id FROM risk.risk_assessment WHERE operation = 'TRANSFER' AND command_ref = ?)";
        assertThat(column("SELECT kind || ':' || coalesce(hard_decision, '-') FROM risk.risk_rule_hit WHERE assessment_id"
                + " = " + assessment + " AND outcome = 'FIRED' ORDER BY ordinal", transferId))
                .as("the rule that refused it").contains("AMOUNT_BAND:CHALLENGE");
        assertThat(column("SELECT code FROM risk.risk_reason WHERE assessment_id = " + assessment + " ORDER BY position",
                transferId)).as("its ordered reasons").isNotEmpty();
        assertThat(count("SELECT count(*) FROM risk.velocity_window WHERE subject_ref = ?", payer.party().toString()))
                .as("a refused movement counts nothing (scenario 14)").isZero();
    }

    @Test
    @Order(4)
    @DisplayName("the EUR 10,000 AMOUNT_BAND DENY commits FAILED(LIMIT_REFUSED) over the real door: a 201 whose body"
            + " says the generic reason and nothing of the judgement; nothing posted, nothing counted")
    void aLimitRefusalCommitsLimitRefused() throws Exception {
        activateTransferPolicy();
        Customer payer = customer();
        UUID source = fundedWallet(payer, 20_000_00);
        HttpResponse<String> refused = transfer(payer.token(), source, wallet(customer()), "10000.00", someKey());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(201);
        assertThat(field(refused.body(), "status")).isEqualTo("FAILED");
        assertThat(field(refused.body(), "failureReason")).isEqualTo("LIMIT_REFUSED");
        assertNoRiskWord(refused.body());
        String transferId = field(refused.body(), "id");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?", transferId)).isZero();
        assertThat(column("SELECT decision FROM risk.risk_assessment WHERE command_ref = ?", transferId))
                .containsExactly("DENY");
        assertThat(windowCount("PARTY", payer.party(), "D1")).isZero();
    }

    // ------------------------------------------------------------------ one assessment

    @Test
    @Order(5)
    @DisplayName("both seams in the real execution: ONE assessment for the transfer's id and ONE RiskDecisionMade, the"
            + " windows counted once (INV-RSK-01, INV-CON-03)")
    void oneAssessmentPerTransferAcrossBothSeams() throws Exception {
        activateTransferPolicy();
        Customer payer = customer();
        UUID source = fundedWallet(payer, 1_000_00);
        // A MULTI_FACTOR session: since P13-TSK-013 a never-paid payee is NEW_PAYEE (250), which with a password
        // session's SESSION_ASSURANCE_BELOW (100) reaches the CHALLENGE band and the door steps up.
        HttpResponse<String> posted = transfer(multiFactor(payer), source, wallet(customer()), "50.00", someKey());
        assertThat(field(posted.body(), "status")).isEqualTo("COMPLETED");
        String transferId = field(posted.body(), "id");
        List<String> assessments = column("SELECT id::text FROM risk.risk_assessment WHERE operation = 'TRANSFER'"
                + " AND command_ref = ?", transferId);
        assertThat(assessments).hasSize(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RiskDecisionMade'"
                + " AND aggregate_id = ?::uuid", assessments.get(0))).isEqualTo(1);
        assertThat(windowCount("PARTY", payer.party(), "D1")).isEqualTo(1);
        assertThat(windowCount("SOURCE_ACCOUNT", source, "D1")).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("a retry under the same key replays byte for byte without a second assessment - and a challenged"
            + " transfer completed from a MULTI_FACTOR session replays to a later password session: the door never"
            + " advises a recorded key (INV-IDEM-01)")
    void aRetryUnderTheSameKeyReplaysWithoutASecondAssessment() throws Exception {
        activateTransferPolicy();
        Customer payer = customer();
        UUID source = fundedWallet(payer, 2_000_00);
        UUID destination = wallet(customer());
        String key = someKey();
        // MULTI_FACTOR from the start: a new payee from a password session is a CHALLENGE since P13-TSK-013.
        String stepped = multiFactor(payer);
        HttpResponse<String> first = transfer(stepped, source, destination, "50.00", key);
        HttpResponse<String> retry = transfer(stepped, source, destination, "50.00", key);
        assertThat(normalized(retry.body())).isEqualTo(normalized(first.body()));
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", payer.party())).isEqualTo(1);
        assertThat(windowCount("PARTY", payer.party(), "D1")).isEqualTo(1);

        String challengedKey = someKey();
        HttpResponse<String> challenged = transfer(stepped, source, destination, "600.00", challengedKey);
        assertThat(field(challenged.body(), "status")).isEqualTo("COMPLETED");
        String password = tokenFrom(authenticate(payer.login()).body());
        HttpResponse<String> replayed = transfer(password, source, destination, "600.00", challengedKey);
        assertThat(replayed.statusCode()).as("never a 403 for a recorded key").isEqualTo(201);
        assertThat(normalized(replayed.body())).isEqualTo(normalized(challenged.body()));
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", payer.party())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @Order(7)
    @DisplayName("scenario 14 through the seam: ten concurrent transfers of one party from ten accounts - ten money locks,"
            + " one party - all COMPLETED, the party's window equal to the counted assessments, ten unless a budget"
            + " breach was recorded (R2a)")
    void tenTransfersOfOneSubjectCountTen() throws Exception {
        activateTransferPolicy();
        Customer payer = customer();
        List<UUID> sources = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sources.add(fundedWallet(payer, 100_00));
        }
        UUID destination = wallet(customer());
        String stepped = multiFactor(payer); // a new payee from a password session is a CHALLENGE (P13-TSK-013)
        List<HttpResponse<String>> responses = race(sources.stream()
                .<Callable<HttpResponse<String>>>map(source -> () -> transfer(stepped, source, destination,
                        "10.00", someKey()))
                .toList());
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
            assertThat(field(r.body(), "status")).isEqualTo("COMPLETED");
        });
        long counted = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND velocity_outcome ="
                + " 'COUNTED'", payer.party());
        long breached = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND velocity_outcome ="
                + " 'UNCOUNTED' AND fail_safe_reason = 'RISK_UNAVAILABLE'", payer.party());
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", payer.party())).isEqualTo(10);
        assertThat(counted + breached).as("every assessment counted, or its breach recorded").isEqualTo(10);
        assertThat(windowCount("PARTY", payer.party(), "D1")).as("the window is exactly its evidence (INV-RSK-03)")
                .isEqualTo(counted);
        assertThat(breached).as("ten counted when no budget was breached (the expected run)").isZero();
    }

    @Test
    @Order(8)
    @DisplayName("opposite transfers A→B and B→A, ten each at once: every answer a domain outcome, no deadlock - the"
            + " money rows in one order, then (R1)-(R3) after them, the velocity locks in one global order")
    void oppositeTransfersCloseNoLockCycle() throws Exception {
        activateTransferPolicy();
        Customer alice = customer();
        Customer bob = customer();
        UUID a = fundedWallet(alice, 1_000_00);
        UUID b = fundedWallet(bob, 1_000_00);
        // MULTI_FACTOR sessions: a new payee from a password session is a CHALLENGE since P13-TSK-013.
        String aliceStepped = multiFactor(alice);
        String bobStepped = multiFactor(bob);
        List<Callable<HttpResponse<String>>> both = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            both.add(() -> transfer(aliceStepped, a, b, "10.00", someKey()));
            both.add(() -> transfer(bobStepped, b, a, "10.00", someKey()));
        }
        List<HttpResponse<String>> responses = race(both);
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(responses).extracting(r -> field(r.body(), "status")).containsOnly("COMPLETED");
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id IN (?, ?)", alice.party(),
                bob.party())).isEqualTo(20);
    }

    // ------------------------------------------------------------------ the fail-safe

    @Test
    @Order(9)
    @DisplayName("scenario 9, unavailable: the party's subject lock held elsewhere past the 250 ms budget - EUR 999.99"
            + " COMPLETED under ALLOW(RISK_UNAVAILABLE), EUR 1,000 FAILED(RISK_REFUSED) under DENY(RISK_UNAVAILABLE),"
            + " both recorded TIMEOUT; the transfer's transaction never aborted")
    void riskUnavailableCommitsTheBandsOutcome() throws Exception {
        activateTransferPolicy();
        Customer payer = customer();
        UUID source = fundedWallet(payer, 5_000_00);
        UUID destination = wallet(customer());
        TransferResult below;
        TransferResult at;
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            try (Statement hold = blocker.createStatement()) {
                hold.execute("SELECT pg_advisory_xact_lock(131, "
                        + VelocityWindows.lockKey(VelocityDimension.PARTY, payer.party().toString()) + ")");
            }
            below = executeDirectly(payer, source, destination, eur(999_99));
            at = executeDirectly(payer, source, destination, eur(1_000_00));
            blocker.rollback();
        }
        assertThat(below.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(at.failureReason()).contains(FailureReason.RISK_REFUSED);
        assertThat(column("SELECT decision || ',' || fail_safe_reason || ',' || unavailable_cause"
                + " FROM risk.risk_assessment WHERE command_ref = ?", below.transferId().value().toString()))
                .containsExactly("ALLOW,RISK_UNAVAILABLE,TIMEOUT");
        assertThat(column("SELECT decision || ',' || fail_safe_reason || ',' || unavailable_cause"
                + " FROM risk.risk_assessment WHERE command_ref = ?", at.transferId().value().toString()))
                .containsExactly("DENY,RISK_UNAVAILABLE,TIMEOUT");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                at.transferId().value().toString())).isZero();
    }

    // ------------------------------------------------------------------ scenario 7

    @Test
    @Order(10)
    @DisplayName("scenario 7, the transfer half: the destination closed between resolution and posting - the seams"
            + " permitted and counted, the posting refused, FAILED(DESTINATION_NOT_POSTABLE) committed; the decision"
            + " stands, sealed ALLOW and COUNTED, and risk retries nothing")
    void aPostingRefusedAfterThePermitLeavesTheDecision() throws Exception {
        activateTransferPolicy();
        Customer payer = customer();
        UUID source = fundedWallet(payer, 1_000_00);
        UUID destination = wallet(customer());
        LedgerAccountId closedLate;
        try (Connection app = DatabaseRoles.application()) {
            closedLate = new JdbcLedgerAccountStore().findAllOwned(app, destination).stream()
                    .filter(account -> account.purpose() == AccountPurpose.CUSTOMER_WALLET).findFirst().orElseThrow()
                    .id();
        }
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement close = migrator.prepareStatement("UPDATE ledger.ledger_account SET status ="
                        + " 'CLOSED', status_changed_at = GREATEST(now(), created_at) WHERE id = ?")) {
            close.setObject(1, closedLate.value());
            close.executeUpdate();
        }
        TransferParticipants<Connection> liesAboutDestination = new TransferParticipants<>() {
            @Override
            public Optional<Source> sourceOwnedBy(Connection uow, UUID party, UUID product, CurrencyCode currency) {
                return transferParticipants.sourceOwnedBy(uow, party, product, currency);
            }

            @Override
            public Optional<Side> destination(Connection uow, UUID product, CurrencyCode currency) {
                return transferParticipants.destination(uow, product, currency)
                        .map(side -> new Side(side.account(), side.currency(), true));
            }

            @Override
            public boolean destinationExists(Connection uow, UUID product) {
                return transferParticipants.destinationExists(uow, product);
            }
        };
        TransferResult refused = as(payer, app -> {
            TransferResult result = execution(liesAboutDestination).execute(app, new TransferCommand(someKey(),
                    payer.party(), source, destination, eur(50_00), "rent"));
            app.commit();
            return result;
        });
        assertThat(refused.failureReason()).contains(FailureReason.DESTINATION_NOT_POSTABLE);
        assertThat(column("SELECT decision || ',' || velocity_outcome FROM risk.risk_assessment WHERE command_ref = ?",
                refused.transferId().value().toString())).containsExactly("ALLOW,COUNTED");
        assertThat(windowCount("PARTY", payer.party(), "D1")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the execution, directly

    /** The composition root's own execution, outside any request: no session, so a challenge is unsatisfied. */
    private TransferResult executeDirectly(Customer payer, UUID source, UUID destination, Money amount)
            throws Exception {
        return as(payer, app -> {
            TransferResult result = transferExecution.execute(app, new TransferCommand(someKey(), payer.party(), source,
                    destination, amount, "rent"));
            app.commit();
            return result;
        });
    }

    /** The execution as the composition root builds it, but over {@code participants} - risk's two real seams. */
    private TransferExecution execution(TransferParticipants<Connection> participants) {
        IdempotentExecutor executor = new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK,
                Duration.ofDays(1), Duration.ofMinutes(5));
        LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
        AuditWriter<Connection> audit = new JdbcAuditWriter();
        OutboxWriter<Connection> outbox = new JdbcOutboxWriter();
        return new TransferExecution(executor, participants, ledgerAccounts,
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                new PostingService(executor, new JdbcJournalEntryStore(IDS), audit, outbox, new JdbcBalanceProjection(),
                        IDS, CLOCK, PostingObserver.NONE),
                transferStore, transferLimitCheck, transferRiskDecision, audit, outbox, IDS, CLOCK);
    }

    private interface InTransaction<R> {
        R run(Connection app) throws Exception;
    }

    private static <R> R as(Customer caller, InTransaction<R> work) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(caller.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            return work.run(app);
        }
    }

    // ------------------------------------------------------------------ the policy

    private static void activateTransferPolicy() {
        if (!ACTIVATED.compareAndSet(false, true)) {
            return;
        }
        RiskPolicyAdministration administration = new RiskPolicyAdministration(new JdbcRiskPolicyStore(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
        RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.TRANSFER), "the seam suite's transfer version", employee(),
                CorrelationId.generate(IDS)));
        inOneTransaction(uow -> administration.activate(uow, proposed.id(), employee(), "activated",
                CorrelationId.generate(IDS)));
    }

    private static <R> R inOneTransaction(java.util.function.Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            connection.setAutoCommit(false);
            R result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    // ------------------------------------------------------------------ customers, wallets, money

    /** Registers over HTTP, promotes the customer to ACTIVE (seeded, the endpoint suite's shape), signs in. */
    private Customer customer() throws Exception {
        String login = "judged." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                + " Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, someKey()).statusCode()).isEqualTo(201);
        UUID identity;
        UUID party;
        try (Connection app = DatabaseRoles.application()) {
            try (PreparedStatement promote = app.prepareStatement("UPDATE party.customer SET status = 'ACTIVE',"
                    + " status_changed_at = GREATEST(now(), opened_at) WHERE party_id = (SELECT party_id FROM"
                    + " identity.identity WHERE login_identifier = ?)")) {
                promote.setString(1, login);
                promote.executeUpdate();
            }
            try (PreparedStatement read = app.prepareStatement(
                    "SELECT id, party_id FROM identity.identity WHERE login_identifier = ?")) {
                read.setString(1, login);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    identity = row.getObject("id", UUID.class);
                    party = row.getObject("party_id", UUID.class);
                }
            }
        }
        return new Customer(login, tokenFrom(authenticate(login).body()), identity, party);
    }

    /** A EUR wallet opened over the real endpoint - its product identifier. */
    private UUID wallet(Customer owner) throws Exception {
        HttpResponse<String> opened = post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                owner.token(), someKey());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        return UUID.fromString(field(opened.body(), "id"));
    }

    private UUID fundedWallet(Customer owner, long minor) throws Exception {
        UUID product = wallet(owner);
        fund(product, minor);
        return product;
    }

    /** Funds the wallet with a real posting from the EUR fee-revenue account (the endpoint suite's fixture). */
    private static void fund(UUID product, long minor) throws Exception {
        JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            LedgerAccountId wallet = ledgerAccounts.findAllOwned(app, product).stream()
                    .filter(a -> a.purpose() == AccountPurpose.CUSTOMER_WALLET).findFirst().orElseThrow().id();
            LedgerAccount operational = ledgerAccounts.findOperational(app, AccountPurpose.FEE_REVENUE, EUR)
                    .orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            IdempotentExecutor executor = new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK,
                    Duration.ofDays(1), Duration.ofMinutes(5));
            new PostingService(executor, new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                    new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE)
                    .post(app, new PostingCommand("fund-" + IDS.next(), today, today, "funding", List.of(
                            new JournalLine(operational.id(), Direction.DEBIT, eur(minor)),
                            new JournalLine(wallet, Direction.CREDIT, eur(minor)))));
            app.commit();
        }
    }

    /** Steps the customer up: a TOTP factor enrolled and confirmed, the session elevated (the credit client's shape). */
    private String multiFactor(Customer customer) throws Exception {
        String token = tokenFrom(authenticate(customer.login()).body());
        Matcher secret = Pattern.compile("secret=([A-Z2-7]+)").matcher(post("/v1/me/mfa", null, token, null).body());
        if (!secret.find()) {
            // Already enrolled by an earlier step-up of this customer: elevate with the factor's next code.
            throw new IllegalStateException("each customer is stepped up once in this suite");
        }
        Sensitive<String> factor = Sensitive.of(secret.group(1));
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        long confirmed = -1;
        for (long candidate : new long[] {step - 1, step, step + 1}) {
            if (post("/v1/me/mfa/confirmation", "{\"code\":\"" + codeAt(factor, candidate) + "\"}", token, null)
                    .statusCode() == 204) {
                confirmed = candidate;
                break;
            }
        }
        assertThat(confirmed).as("some step around this JVM's confirms the factor").isNotNegative();
        HttpResponse<String> elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 1)
                + "\"}", token, null);
        if (elevated.statusCode() == 401) {
            elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 2) + "\"}", token,
                    null);
        }
        assertThat(elevated.statusCode()).as("the step-up: %s", elevated.body()).isEqualTo(200);
        assertThat(field(elevated.body(), "assurance")).isEqualTo("MULTI_FACTOR");
        return field(elevated.body(), "sessionToken");
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    // ------------------------------------------------------------------ HTTP

    private HttpResponse<String> transfer(String token, UUID source, UUID destination, String amount, String key)
            throws Exception {
        return post("/v1/transfers", "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\""
                + destination + "\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"reference\":\"rent\"}", token,
                key);
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD
                + "\"}", null, null);
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
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static List<HttpResponse<String>> race(List<Callable<HttpResponse<String>>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (Callable<HttpResponse<String>> request : requests) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalized\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"normalized\"");
    }

    private static void assertNoRiskWord(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        for (String word : RISK_WORDS) {
            assertThat(lower).as("a customer answer naming '%s'", word).doesNotContain(word);
        }
    }

    // ------------------------------------------------------------------ counters - the tables, read as the migrator

    private static long windowCount(String dimension, UUID subject, String kind) throws SQLException {
        return count("SELECT COALESCE(sum(count), 0) FROM risk.velocity_window WHERE dimension = ? AND subject_ref = ?"
                + " AND window_kind = ?", dimension, subject.toString(), kind);
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static List<String> column(String sql, Object... arguments) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
        }
        return values;
    }
}
