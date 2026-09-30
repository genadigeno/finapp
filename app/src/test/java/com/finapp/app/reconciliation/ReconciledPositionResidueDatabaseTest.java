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
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExpectationReadings;
import com.finapp.reconciliation.SuspenseReadings;
import com.finapp.settlement.SettlementBatchStore;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * THE SHARED CONTAINER'S RESIDUE, JUDGED ONCE AND LAST (`INV-REC-06`): after every other suite
 * of the {@code database} tier has run, the opening backfill adopts what real operations left,
 * and the position proof and the completeness verifier must then read exactly what
 * {@code ReconciliationOpeningDatabaseTest} and the multi-rail storm demand at rest.
 *
 * <h2>Why it exists</h2>
 *
 * <p>Every app database suite shares one Testcontainers database in one JVM. A fixture that
 * posts directly onto a reconciled position and commits leaves a line no expectation names
 * and no backfill can adopt — permanent, since journal lines are immutable. Twenty-one suites
 * did exactly that (`da48fb2`), and the damage surfaced only as those two suites failing
 * <em>when the offender happened to run first</em>. Gradle's class order is no contract - it
 * follows the file scan and runs last run's failures first, so it changes between runs on one
 * machine - and an offender sorting after both victims was never caught at all. This suite removes the order from the
 * question: it runs last ({@code @Order(Integer.MAX_VALUE)} under the tier's
 * {@link ClassOrderer.OrderAnnotation}, wired in {@code app/build.gradle.kts}), so the
 * verdict is deterministic and its failure lists every unexplained line by the entry's
 * reference and idempotency scope — a fixture's key names the fixture.
 *
 * <h2>Why a residue check and not a source rule</h2>
 *
 * <p>It judges the harm itself, however it was written — {@code PostingService},
 * {@code ReversalService}, raw SQL, a helper in another file, a production path whose posting
 * key the opener register missed. A source scan would have allow-listed every file still
 * able to post to a clearing position, and cannot tell a rolled-back probe from a committed
 * one (the design record is this suite's change-log row).
 *
 * <h2>The one recorded exception</h2>
 *
 * <p>{@code SUSPENSE_UNMATCHED}'s unattributed COUNT is not asserted: Phase 7's parkings read
 * there honestly until `P8-TSK-020` gives each a suspense item ({@link PositionProof}'s own
 * javadoc). A stray suspense line still fails the suspense IDENTITY, which carries the Phase 7
 * term and is asserted. When `-020` lands, the purpose joins {@link #ASSERTED}.
 *
 * <p>Stated limit: the tier's residue is judged, not each class's — the listing names the
 * entry, and the entry's key names its writer. A targeted run sees the verdict only when it
 * names this suite too ({@code --tests '*ResidueDatabaseTest'}); the orderer still runs it
 * last.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE)
@DisplayName("no database-tier suite leaves an unexplained line on a reconciled position"
        + " (INV-REC-06, judged last over the shared container)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class ReconciledPositionResidueDatabaseTest {

    /** The reconciled positions whose unattributed count must read zero at rest. */
    static final Set<AccountPurpose> ASSERTED =
            EnumSet.of(
                    AccountPurpose.SETTLEMENT_CLEARING,
                    AccountPurpose.INSTANT_CLEARING,
                    AccountPurpose.PAYOUT_CLEARING,
                    AccountPurpose.PROCESSING_COSTS);

    /** The configuration parameter the tier sets, and the orderer it must name. */
    static final String ORDERER_PARAMETER = "junit.jupiter.testclass.order.default";

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** How many unexplained lines a failure message lists after its per-writer summary. */
    private static final int LISTED = 25;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private PositionProof positionProof;
    @Autowired private PostingService postings;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private JournalEntryStore<Connection> journalEntryStore;
    @Autowired private ExpectationReadings<Connection> expectationReadings;
    @Autowired private SettlementBatchStore<Connection> settlementBatchStore;
    @Autowired private SuspenseReadings suspenseReadings;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @Test
    @DisplayName("the tier runs this suite last: the class orderer is the tier's, and this"
            + " suite carries the highest order")
    void theTierRunsThisSuiteLast() {
        assertThat(ReconciledPositionResidueDatabaseTest.class.getAnnotation(Order.class).value())
                .as("the residue is judged after every other suite, so this suite's order is"
                        + " the highest there is")
                .isEqualTo(Integer.MAX_VALUE);
        // Under Gradle (the tier declaration is present) the orderer must be the one that reads
        // @Order - without it the annotation above is decoration and the verdict is judged
        // wherever the file scan happened to put this class. An IDE's own runner sets
        // neither, and a lone run of this suite is last by construction.
        if (System.getProperty("finapp.test.tiers") != null) {
            assertThat(System.getProperty(ORDERER_PARAMETER))
                    .as("app/build.gradle.kts must set %s on databaseTest, or @Order is ignored",
                            ORDERER_PARAMETER)
                    .isEqualTo(ClassOrderer.OrderAnnotation.class.getName());
        }
    }

    @Test
    @DisplayName("the judgement has teeth: a fixture-shaped posting onto SETTLEMENT_CLEARING,"
            + " uncommitted, is counted, listed by its key, and fails the identity - then rolled"
            + " back")
    void theJudgementCatchesAPlantedFixturePosting() throws SQLException {
        String key = "residue-probe:" + IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                Judgement before = judge(app);
                // The shape every one of da48fb2's twenty-one funders had: a direct posting
                // through the ledger's own service, a reconciled position on one side, no
                // expectation and no operation a backfill could adopt.
                try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                        CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
                    LedgerAccount clearing = operational(app, AccountPurpose.SETTLEMENT_CLEARING);
                    LedgerAccount fee = operational(app, AccountPurpose.FEE_REVENUE);
                    LocalDate today = LocalDate.now(CLOCK);
                    Money amount = Money.ofMinorUnits(7_00, EUR);
                    postings.post(
                            app,
                            new PostingCommand(
                                    key,
                                    today,
                                    today,
                                    "residue-probe",
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(), Direction.DEBIT, amount),
                                            new JournalLine(
                                                    fee.id(), Direction.CREDIT, amount))));
                }
                Judgement after = judge(app);
                // Judged as a difference, never against an assumed-clean baseline: the probe
                // run that re-armed two funders found the first draft's listing cap hid the
                // planted line behind thirty earlier ones.
                Set<JournalEntryStore.LineKey> planted =
                        new LinkedHashSet<>(after.unexplained(AccountPurpose.SETTLEMENT_CLEARING));
                planted.removeAll(before.unexplained(AccountPurpose.SETTLEMENT_CLEARING));
                assertThat(planted).as("the planted line is counted, and only it").hasSize(1);
                assertThat(readLine(app, planted.iterator().next()).description())
                        .as("the line names its writer by the posting's key")
                        .contains(key);
                assertThat(after.describe(app, AccountPurpose.SETTLEMENT_CLEARING))
                        .as("and the writer summary names it however many lines precede it")
                        .anySatisfy(line -> assertThat(line)
                                .contains("written under ledger.post:residue-probe:<id>"));
                assertThat(after.report()
                                .currenciesFailing(AccountPurpose.SETTLEMENT_CLEARING))
                        .as("and the position identity fails with it")
                        .isPositive();
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("after every other suite and one backfill: every position and suspense"
            + " identity holds, and no line on a reconciled position is unexplained")
    void noSuiteLeftAnUnexplainedLine() throws Exception {
        // What real operations left - quiet-double completions, the dispute suites' captures
        // under their real CAPTURED attempts - is the backfill's to adopt, exactly as the
        // storm and the opening suite let it. What it cannot adopt is the residue.
        String controller = controllerToken();
        HttpResponse<String> backfill =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://localhost:" + port
                                                        + "/v1/operator/reconciliation"
                                                        + "/opening-position"))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + controller)
                                .header("Idempotency-Key", "residue-" + IDS.next())
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"reason\":\"the database tier's residue, judged"
                                                + " last\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(backfill.statusCode()).as(backfill.body()).isEqualTo(200);

        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                Judgement judgement = judge(snapshot);
                // Completeness first: it names the writers. The identities after it catch the
                // mirror image - an open remainder no line backs - and a stray suspense line.
                for (AccountPurpose purpose : ASSERTED) {
                    assertThat(judgement.unexplained(purpose))
                            .as("at rest: every %s line is known - a suite committed a line"
                                            + " no expectation names and no backfill can adopt:"
                                            + "%n%s",
                                    purpose,
                                    String.join("\n", judgement.describe(snapshot, purpose)))
                            .isEmpty();
                }
                for (PositionProof.PositionVerdict verdict : judgement.report().verdicts()) {
                    assertThat(verdict.explained())
                            .as("at rest: %s %s explained - DR-CR %s = open remainders %s -"
                                            + " open items %s (INV-REC-06)",
                                    verdict.purpose(), verdict.currency(),
                                    verdict.ledgerBalance(), verdict.openRemainders(),
                                    verdict.openItems())
                            .isTrue();
                }
                for (PositionProof.SuspenseVerdict verdict :
                        judgement.report().suspenseVerdicts()) {
                    assertThat(verdict.explained())
                            .as("at rest: SUSPENSE_UNMATCHED %s - CR-DR %s = CREDIT %s - DEBIT"
                                            + " %s + Phase 7 %s (ADR-0070 section 7)",
                                    verdict.currency(), verdict.ledgerBalance(),
                                    verdict.creditRemainders(), verdict.debitRemainders(),
                                    verdict.unadoptedParkings())
                            .isTrue();
                }
            } finally {
                snapshot.rollback();
            }
        }
    }

    // ----------------------------------------------------------------- the judgement

    /**
     * The verifier's report and, beside it, the unexplained lines themselves — recomputed
     * from the same four readers {@link PositionProof} composes, in the same snapshot, and
     * held to the report's count per purpose, so the listing cannot drift from the verdict.
     */
    private record Judgement(
            PositionProof.Report report,
            Map<AccountPurpose, List<JournalEntryStore.LineKey>> lines) {

        List<JournalEntryStore.LineKey> unexplained(AccountPurpose purpose) {
            return lines.getOrDefault(purpose, List.of());
        }

        /**
         * Every writer first — each idempotency scope with its ids folded to {@code <id>},
         * counted, so one prolific fixture cannot push another off the message — then the
         * first {@link #LISTED} lines themselves.
         */
        List<String> describe(Connection app, AccountPurpose purpose) throws SQLException {
            List<JournalEntryStore.LineKey> keys = unexplained(purpose);
            Map<String, Integer> byWriter = new TreeMap<>();
            List<String> samples = new ArrayList<>();
            for (JournalEntryStore.LineKey key : keys) {
                LineDetail line = readLine(app, key);
                byWriter.merge(writerOf(line.scope()), 1, Integer::sum);
                if (samples.size() < LISTED) {
                    samples.add(line.description());
                }
            }
            List<String> described = new ArrayList<>();
            byWriter.forEach((writer, count) ->
                    described.add("  " + count + " line(s) written under " + writer));
            described.addAll(samples);
            if (keys.size() > LISTED) {
                described.add("  ... and " + (keys.size() - LISTED) + " more lines");
            }
            return described;
        }
    }

    /** One unexplained line: its entry's idempotency scope, and a sentence naming it. */
    private record LineDetail(String scope, String description) {}

    /** A scope with its generated parts folded, so one writer's many entries read as one. */
    private static String writerOf(String scope) {
        return UUID_PATTERN.matcher(String.valueOf(scope)).replaceAll("<id>");
    }

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{12}");

    private Judgement judge(Connection unitOfWork) throws SQLException {
        PositionProof.Report report = positionProof.sweep(unitOfWork);

        Map<LedgerAccountId, AccountPurpose> purposeOf = new HashMap<>();
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            for (CurrencyCode currency : SupportedCurrencies.ALL) {
                ledgerAccountStore
                        .findOperational(unitOfWork, purpose, currency)
                        .ifPresent(account -> purposeOf.put(account.id(), purpose));
            }
        }
        Set<ExpectationReadings.KnownLine> known =
                new HashSet<>(expectationReadings.knownLines(unitOfWork));
        Set<UUID> knownEntries =
                new HashSet<>(settlementBatchStore.acceptedRecognitionEntries(unitOfWork));
        knownEntries.addAll(suspenseReadings.knownEntries(unitOfWork));

        Map<AccountPurpose, List<JournalEntryStore.LineKey>> lines =
                new EnumMap<>(AccountPurpose.class);
        for (JournalEntryStore.LineKey line :
                journalEntryStore.lineKeysOn(unitOfWork, purposeOf.keySet())) {
            boolean explained =
                    known.contains(
                                    new ExpectationReadings.KnownLine(
                                            line.entry().value(), line.account().value()))
                            || knownEntries.contains(line.entry().value());
            if (!explained) {
                lines.computeIfAbsent(purposeOf.get(line.account()), p -> new ArrayList<>())
                        .add(line);
            }
        }
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            assertThat((long) lines.getOrDefault(purpose, List.of()).size())
                    .as("the listing reproduces the verifier's own %s count", purpose)
                    .isEqualTo(report.unattributedByPurpose().getOrDefault(purpose, 0L));
        }
        return new Judgement(report, lines);
    }

    private static LineDetail readLine(Connection app, JournalEntryStore.LineKey key)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT e.idempotency_scope, e.reference, e.entry_type, e.posting_date,"
                                + " l.direction, l.amount_minor, l.scale, l.currency"
                                + " FROM ledger.journal_line l"
                                + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                + " WHERE l.entry_id = ? AND l.ledger_account_id = ?"
                                + " ORDER BY l.seq")) {
            read.setObject(1, key.entry().value());
            read.setObject(2, key.account().value());
            try (ResultSet row = read.executeQuery()) {
                StringBuilder described = new StringBuilder("  entry " + key.entry().value());
                String scope = null;
                while (row.next()) {
                    scope = row.getString("idempotency_scope");
                    described.append(String.format(
                            " | %s %s %s | scope=%s reference=%s %s %s",
                            row.getString("direction"),
                            java.math.BigDecimal.valueOf(
                                            row.getLong("amount_minor"), row.getInt("scale"))
                                    .toPlainString(),
                            row.getString("currency"),
                            row.getString("idempotency_scope"),
                            row.getString("reference"),
                            row.getString("entry_type"),
                            row.getObject("posting_date")));
                }
                return new LineDetail(scope, described.toString());
            }
        }
    }

    private LedgerAccount operational(Connection app, AccountPurpose purpose) {
        return ledgerAccountStore.findOperational(app, purpose, EUR).orElseThrow();
    }

    // ----------------------------------------------------------------- the door

    private String controllerToken() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Residue Controller', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    "rr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        IdentityId controller = IdentityId.of(identity);
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, controller, RoleName.RECONCILIATION_CONTROLLER, controller,
                    "test fixture");
            app.commit();
        }
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session.Draft session =
                com.finapp.identity.Session.issue(
                        IDS,
                        CLOCK,
                        controller,
                        SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("residue-" + UUID.randomUUID()))
                .causing(CausationId.of("residue-cause"));
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
