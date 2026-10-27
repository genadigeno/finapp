package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.AlertBornOnceCensus;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.AlertWindow;
import com.finapp.risk.Alerts;
import com.finapp.risk.AssessmentCensus;
import com.finapp.risk.CanonicalEvaluation;
import com.finapp.risk.CaseOneOpenCensus;
import com.finapp.risk.CommandRef;
import com.finapp.risk.DailyReplay;
import com.finapp.risk.EvaluationInputs;
import com.finapp.risk.FilingCensus;
import com.finapp.risk.JudgedCommands;
import com.finapp.risk.MonitoredCounterpartyKind;
import com.finapp.risk.MonitoredDirection;
import com.finapp.risk.MonitoredEntry;
import com.finapp.risk.MonitoredKind;
import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoredSubjectKind;
import com.finapp.risk.MonitoredTransaction;
import com.finapp.risk.MonitoredTransactionId;
import com.finapp.risk.MonitoredTransactionStore;
import com.finapp.risk.MonitoringCensus;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.MonitoringWatermarkStore;
import com.finapp.risk.NewAlert;
import com.finapp.risk.ProofVerdict;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskDay;
import com.finapp.risk.RiskEngineV1;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskOperationsReports;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskProofReads;
import com.finapp.risk.RiskProofs;
import com.finapp.risk.RiskStorageException;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityOutcome;
import com.finapp.risk.VelocityProof;
import com.finapp.risk.VelocityWindowKey;
import com.finapp.risk.Verdict;
import com.finapp.risk.WindowKind;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The risk proofs over real rows (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 12.4, 13.3; {@code INV-RSK-01},
 * {@code -03}, {@code -06}, {@code -09}; {@code DESIGN-023}): the entry's three distributed cases - a census under
 * concurrent movements reads one snapshot and agrees with itself; the velocity proof names an injected difference and
 * corrects nothing; a day's replay replays each assessment under its own pinned version - and one case per census over
 * the composition's own statements. The container is shared, so each case judges its own rows (by id, or by the
 * difference its rows make) and never assumes the day holds nothing else. No version is activated: a proposed version is
 * as immutable as an active one, and a pin is by id, so the replay's claim needs none - and nothing else in the shared
 * container is judged by these versions.
 *
 * <p>Written and owed under C5 (the standing criterion-7 deviation): the database tier is not run per task.
 *
 * <p><strong>Its own container</strong> ({@code own-container}, P13-DOC-001's database pass): to prove each census
 * names what it must, it COMMITS what no real path writes - a monitored transaction naming no journal entry (a phantom),
 * an assessment of a command that never existed, a window counted once more than its evidence - and none of them can be
 * removed (append-only by trigger). In a shared container every whole-database census another suite asserts would then
 * hold this suite's plants: the pass's re-run found {@code MonitoringIntakeDatabaseTest}'s journal census failing on
 * exactly this suite's phantom.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest
@DisplayName("the risk proofs over real rows: one snapshot, a difference named and never corrected, each seal under its"
        + " own version (P13-TSK-023)")
class RiskProofsDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Autowired private RiskTransactions riskTransactions;
    @Autowired private RiskEvaluation riskEvaluation;
    @Autowired private RiskOperationsReports riskOperationsReports;
    @Autowired private RiskProofReads riskProofReads;
    @Autowired private JudgedCommands judgedCommands;
    @Autowired private VelocityProof velocityProof;
    @Autowired private RiskPolicyAdministration riskPolicyAdministration;
    @Autowired private RiskPolicies riskPolicies;
    @Autowired private RiskAssessmentStore riskAssessmentStore;
    @Autowired private MonitoredTransactionStore monitoredTransactionStore;
    @Autowired private MonitoredLedger monitoredLedger;
    @Autowired private MonitoringWatermarkStore monitoringWatermarkStore;
    @Autowired private Alerts alerts;
    @Autowired private AuditWriter<Connection> auditWriter;

    // ------------------------------------------------------------------ the entry's three cases

    @Test
    @DisplayName("a census under concurrent movements reads one snapshot: seals committing between its two reads change"
            + " neither, and the census agrees with the decisions report it was read beside; a fresh snapshot sees them")
    void aCensusUnderLoadIsOneSnapshot() {
        RiskDay today = RiskDay.containing(Instant.now());
        long[] seen = riskTransactions.inSnapshot(snapshot -> {
            long before = transfers(riskProofReads.decisions(snapshot, today));
            CompletableFuture.allOf(new CompletableFuture<?>[] {
                    CompletableFuture.runAsync(() -> seal(IDS.next(), 100_00)),
                    CompletableFuture.runAsync(() -> seal(IDS.next(), 200_00)),
                    CompletableFuture.runAsync(() -> seal(IDS.next(), 300_00))}).join();
            long after = transfers(riskProofReads.decisions(snapshot, today));
            long census = new AssessmentCensus(riskProofReads, judgedCommands).run(snapshot, today).operations().stream()
                    .filter(operation -> operation.operation() == RiskOperation.TRANSFER).findFirst().orElseThrow()
                    .assessments();
            return new long[] {before, after, census};
        });
        assertThat(seen[1]).as("three seals committed between the two reads - the snapshot saw none").isEqualTo(seen[0]);
        assertThat(seen[2]).as("the census and the report read one world").isEqualTo(seen[0]);
        long fresh = riskTransactions.inSnapshot(snapshot -> transfers(riskProofReads.decisions(snapshot, today)));
        assertThat(fresh).isGreaterThanOrEqualTo(seen[0] + 3);
    }

    @Test
    @DisplayName("the velocity proof names an injected difference - a window counted once more than its assessments - and"
            + " corrects nothing: the row's figures are as the injection left them, the proof read-only")
    void theVelocityProofNamesADifferenceAndCorrectsNothing() throws Exception {
        UUID party = IDS.next();
        seal(party, 120_00);
        // A window counted within the skew of the evidence's completion is pending, not judged: let the skew pass ON THE
        // DATABASE'S CLOCK (P13-DOC-001's database pass: a fixed sleep assumed the database's clock keeps the JVM's pace,
        // and this machine's Docker clock advanced 3.9 s across a 6 s sleep - the window stayed pending and the proof,
        // judging nothing of it, named nothing).
        DatabaseClock.awaitPast(lastCountedAt(party).plus(VelocityProof.SKEW).plusMillis(100));
        assertThat(divergencesOf(party)).as("sealed and counted together - nothing to name").isEmpty();

        try (Connection application = DatabaseRoles.application()) {
            application.setAutoCommit(false);
            try (PreparedStatement drift = application.prepareStatement("UPDATE risk.velocity_window SET count ="
                    + " count + 1, sum_minor = sum_minor + 100 WHERE dimension = 'PARTY' AND subject_ref = ?"
                    + " AND window_kind = 'D30'")) {
                drift.setString(1, party.toString());
                assertThat(drift.executeUpdate()).isEqualTo(1);
            }
            application.commit();
        }
        // The injection is itself a count: V005's trigger stamps last_counted_at with the database clock, so the window is
        // pending again until the skew passes (P13-TST-001's first run of this owed suite) - on that clock.
        DatabaseClock.awaitPast(lastCountedAt(party).plus(VelocityProof.SKEW).plusMillis(100));
        long[] injected = figures(party);

        List<VelocityProof.Divergence> named = divergencesOf(party);
        assertThat(named).singleElement().satisfies(divergence -> {
            assertThat(divergence.key().windowKind()).isEqualTo(WindowKind.D30);
            assertThat(divergence.recordedCount()).isEqualTo(2);
            assertThat(divergence.toCount()).isEqualTo(1);
            assertThat(divergence.currency()).isEqualTo(EUR);
        });
        assertThat(figures(party)).as("named, never corrected").containsExactly(injected);
    }

    @Test
    @DisplayName("a day's replay pins each assessment's own version: seals under two versions of one operation replay"
            + " IDENTICAL - the day's identical count grows by exactly the seals, its divergent count not at all")
    void aDailyReplayPinsEachAssessmentsOwnVersion() {
        RiskPolicy v1 = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);
        RiskPolicy v2 = new RiskPolicy(v1.operation(), v1.engine(), v1.currency(), v1.rules().subList(0, 3),
                v1.scoreBands(), v1.ratingBands(), v1.failSafeBands(), v1.challengeAssurance(), v1.overrideThreshold(),
                v1.holdValidity());
        Actor officer = new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        RiskPolicyAdministration.Proposed first = inTransaction(uow -> riskPolicyAdministration.propose(uow, v1,
                "the replay suite's morning version", officer, CorrelationId.generate(IDS)));
        RiskPolicyAdministration.Proposed second = inTransaction(uow -> riskPolicyAdministration.propose(uow, v2,
                "the replay suite's afternoon version", officer, CorrelationId.generate(IDS)));
        DailyReplay replay = new DailyReplay(riskProofReads, riskPolicies,
                (unitOfWork, actor) -> Optional.of(UUID.randomUUID()), auditWriter, IDS, Clock.systemUTC());
        Actor analyst = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        RiskDay today = RiskDay.containing(Instant.now());
        long[] before = wholeDay(replay, today, analyst);

        for (int i = 0; i < 3; i++) {
            plant(v1, first);
            plant(v2, second);
        }

        long[] after = wholeDay(replay, today, analyst);
        assertThat(after[0] - before[0]).isGreaterThanOrEqualTo(6);
        assertThat(after[1]).as("each under its own version - none diverged").isEqualTo(before[1]);
    }

    @Test
    @DisplayName("a proof's snapshot is bounded (the review's I1, I3): statement_timeout armed for the transaction alone,"
            + " a statement past it cancelled 57014 - the read fails whole; the next transaction is unbounded again")
    void aProofsSnapshotIsBounded() {
        String armed = riskTransactions.inSnapshot(snapshot -> {
            RiskProofs.bound(snapshot, java.time.Duration.ofSeconds(5));
            return setting(snapshot);
        });
        assertThat(armed).isEqualTo("5s");
        Throwable cancelled = org.assertj.core.api.Assertions.catchThrowable(() -> riskTransactions.inSnapshot(
                snapshot -> {
                    RiskProofs.bound(snapshot, java.time.Duration.ofMillis(100));
                    try (PreparedStatement sleep = snapshot.prepareStatement("SELECT pg_sleep(2)")) {
                        sleep.executeQuery().close();
                        return null;
                    } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException(failure.getSQLState(), failure);
                    }
                }));
        assertThat(cancelled).hasMessageContaining(RiskStorageException.QUERY_CANCELED);
        assertThat(riskTransactions.inSnapshot(RiskProofsDatabaseTest::setting)).as("SET LOCAL - gone with the"
                + " transaction").isNotEqualTo("100ms");
    }

    @Test
    @DisplayName("a day's replay pages by cursor: the pages in order replay every assessment once - their counts sum to"
            + " the day's - and a page past the last answers no next")
    void aDayReplaysByPage() {
        DailyReplay replay = new DailyReplay(riskProofReads, riskPolicies,
                (unitOfWork, actor) -> Optional.of(UUID.randomUUID()), auditWriter, IDS, Clock.systemUTC());
        Actor analyst = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        RiskDay today = RiskDay.containing(Instant.now());
        seal(IDS.next(), 10_00);
        seal(IDS.next(), 20_00);
        long[] counted = riskTransactions.inSnapshot(snapshot -> {
            long whole = replay.replay(snapshot, today, analyst, Optional.empty(), DailyReplay.MAX_LIMIT).replayed();
            long paged = 0;
            Optional<RiskAssessmentId> cursor = Optional.empty();
            DailyReplay.Result page;
            do {
                page = replay.replay(snapshot, today, analyst, cursor, 1);
                paged += page.replayed();
                cursor = page.next();
            } while (cursor.isPresent() && paged < DailyReplay.MAX_LIMIT);
            return new long[] {whole, paged};
        });
        assertThat(counted[0]).isGreaterThanOrEqualTo(2);
        assertThat(counted[1]).as("one snapshot: the pages are the day").isEqualTo(Math.min(counted[0],
                DailyReplay.MAX_LIMIT));
        boolean todayClosed = riskTransactions.inSnapshot(snapshot -> replay.replay(snapshot, today, analyst).closed());
        assertThat(todayClosed).as("today is not closed - its pages may miss a row committed behind a cursor")
                .isFalse();
        boolean pastClosed = riskTransactions.inSnapshot(snapshot -> replay.replay(snapshot,
                today.previous().previous(), analyst).closed());
        assertThat(pastClosed).isTrue();
    }

    @Test
    @DisplayName("the journal link's floor is fixed: the first transfer ever judged, read through the primary key, at or"
            + " before every transfer seal - not the earliest of the 30-day read")
    void theFloorIsTheFirstTransferEverJudged() {
        java.util.Optional<Instant> before = riskTransactions.inSnapshot(riskAssessmentStore::firstTransferAt);
        seal(IDS.next(), 15_00);
        Instant sealed = Instant.now();
        java.util.Optional<Instant> floor = riskTransactions.inSnapshot(riskAssessmentStore::firstTransferAt);
        assertThat(floor).isPresent();
        assertThat(floor.get()).as("no later than the transfer just judged").isBeforeOrEqualTo(
                sealed.plus(VelocityProof.SKEW));
        before.ifPresent(first -> assertThat(floor).as("fixed - a new seal never moves it").contains(first));
    }

    // ------------------------------------------------------------------ one per census

    @Test
    @DisplayName("the assessment census reads the owners: a seal whose transfer no owner holds is orphaned, named by its"
            + " command - risk's own table alone would never see it")
    void theAssessmentCensusCountsTheJudgedCommandsThroughTheirOwners() {
        UUID transfer = IDS.next();
        inTransaction(uow -> riskEvaluation.evaluate(uow, request(transfer, IDS.next(), 10_00)));
        RiskDay today = RiskDay.containing(Instant.now());
        AssessmentCensus.Operation transfers = riskTransactions.inSnapshot(snapshot ->
                new AssessmentCensus(riskProofReads, judgedCommands).run(snapshot, today)).operations().stream()
                .filter(operation -> operation.operation() == RiskOperation.TRANSFER).findFirst().orElseThrow();
        assertThat(transfers.orphanedCount()).isGreaterThanOrEqualTo(1);
        assertThat(transfers.verdict()).isEqualTo(ProofVerdict.DIVERGED);
        java.util.Set<CommandRef> recorded = riskTransactions.inSnapshot(snapshot -> judgedCommands.recorded(snapshot,
                RiskOperation.TRANSFER, List.of(CommandRef.of(transfer))));
        assertThat(recorded).as("transfers holds no such row").isEmpty();
    }

    @Test
    @DisplayName("monitored = journal since the floor: a monitored row naming no journal entry is a phantom; the census"
            + " reads the journal's own ids through MonitoredLedger")
    void theMonitoringCensusEqualsTheJournalSinceTheFloor() {
        RiskDay today = RiskDay.containing(Instant.now());
        MonitoringCensus census = new MonitoringCensus(monitoredLedger, monitoringWatermarkStore, riskProofReads);
        long before = riskTransactions.inSnapshot(snapshot -> census.run(snapshot, today)).phantomCount();
        UUID phantom = IDS.next();
        inTransaction(uow -> monitoredTransactionStore.insertAbsent(uow, List.of(new MonitoredTransaction(
                MonitoredTransactionId.next(IDS), phantom, MonitoringWatermark.sequenceOf(phantom),
                MonitoredEntry.EntryType.POSTING, "transfer", MonitoredKind.TRANSFER,
                Optional.of(MonitoredSubjectKind.ACCOUNT), Optional.of(IDS.next()), Optional.of(MonitoredDirection.OUT),
                MonitoredCounterpartyKind.CUSTOMER, Optional.of(IDS.next()), Optional.empty(), 10_00, 2, EUR,
                LocalDate.now(ZoneOffset.UTC), Instant.now(), Optional.empty()))));
        MonitoringCensus.Result after = riskTransactions.inSnapshot(snapshot -> census.run(snapshot, today));
        assertThat(after.phantomCount()).isEqualTo(before + 1);
        assertThat(after.verdict()).isEqualTo(ProofVerdict.DIVERGED);
    }

    @Test
    @DisplayName("born once, re-proven by query: the same alert raised twice is one row - the census finds no second")
    void theBornOnceCensusFindsNoSecondAlert() {
        UUID party = IDS.next();
        RiskDay today = RiskDay.containing(Instant.now());
        AlertWindow window = AlertWindow.of(new VelocityWindowKey(VelocityDimension.PARTY, party.toString(),
                WindowKind.D1, today.start()));
        NewAlert alert = NewAlert.operational(AlertKind.VELOCITY_DIVERGENCE, AlertSeverity.HIGH,
                new SignalSubject(SubjectKind.PARTY, party.toString()), window, AlertEvidence.NONE,
                CorrelationId.generate(IDS), CausationId.of("proofs-suite"));
        inTransaction(uow -> alerts.raise(uow, alert));
        inTransaction(uow -> alerts.raise(uow, alert));
        AlertBornOnceCensus.Result result = riskTransactions.inSnapshot(snapshot ->
                new AlertBornOnceCensus(riskProofReads).run(snapshot, today));
        assertThat(result.raised()).isGreaterThanOrEqualTo(1);
        assertThat(result.duplicates()).isZero();
    }

    @Test
    @DisplayName("one open case per subject and kind, re-proven by query: the census's open cases are the table's in the"
            + " same snapshot, and no subject holds two of a kind")
    void theOneOpenCensusFindsNoSecondCase() {
        long[] read = riskTransactions.inSnapshot(snapshot -> {
            CaseOneOpenCensus.Result census = new CaseOneOpenCensus(riskProofReads).run(snapshot);
            try (PreparedStatement select = snapshot.prepareStatement("SELECT count(*) FROM risk.risk_case"
                    + " WHERE status <> 'DISPOSED'"); ResultSet row = select.executeQuery()) {
                row.next();
                return new long[] {census.open(), row.getLong(1), census.subjectsOverOne()};
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThat(read[0]).isEqualTo(read[1]);
        assertThat(read[2]).as("risk_case_one_open's truth").isZero();
    }

    @Test
    @DisplayName("the filing census over regulatory_report_due agrees with the table read in the same snapshot")
    void theFilingCensusNamesAnOverdueFiling() {
        long[] read = riskTransactions.inSnapshot(snapshot -> {
            FilingCensus.Result census = new FilingCensus(riskProofReads).run(snapshot);
            try (PreparedStatement select = snapshot.prepareStatement("SELECT count(*) FROM risk.regulatory_report"
                    + " WHERE status = 'FILED'"); ResultSet row = select.executeQuery()) {
                row.next();
                return new long[] {census.filed(), row.getLong(1), census.due(), census.overdue()};
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThat(read[0]).isEqualTo(read[1]);
        assertThat(read[2]).isLessThanOrEqualTo(read[0]);
        assertThat(read[3]).isLessThanOrEqualTo(read[2]);
    }

    // ------------------------------------------------------------------ fixtures

    /** A transfer of {@code party}'s judged and sealed by the composition's T-eval, counted in its windows. */
    private void seal(UUID party, long minor) {
        inTransaction(uow -> riskEvaluation.evaluate(uow, request(IDS.next(), party, minor)));
    }

    private static RiskEvaluationRequest request(UUID transfer, UUID party, long minor) {
        return RiskEvaluationRequest.builder(RiskOperation.TRANSFER, CommandRef.of(transfer),
                        Money.ofMinorUnits(minor, EUR), party, CorrelationId.generate(IDS), CausationId.of("proofs-suite"))
                .sourceAccount(IDS.next()).destinationAccount(IDS.next()).build();
    }

    /** An assessment sealed under {@code version} - the engine's own verdict for it - inserted whole. */
    private void plant(RiskPolicy policy, RiskPolicyAdministration.Proposed version) {
        Money amount = Money.ofMinorUnits(120_00, EUR);
        RiskEvaluationRequest request = request(IDS.next(), IDS.next(), 120_00);
        EvaluationInputs inputs = request.judged().build();
        Verdict verdict = RiskEngineV1.evaluate(policy, inputs);
        String text = CanonicalEvaluation.render(inputs);
        com.finapp.risk.RiskDecision decision = verdict.decision().orElseThrow();
        // Never counted: a permitted seal planted here is UNCOUNTED (recorded, its windows untouched), a refused one
        // NOT_PERMITTED - so the velocity proof, sharing the container, has nothing of these to judge.
        RiskAssessment assessment = new RiskAssessment(RiskAssessmentId.next(IDS), RiskOperation.TRANSFER,
                request.commandRef(), RiskAssessment.Subjects.of(request), amount,
                Optional.of(new RiskAssessment.Pin(version.id(), version.version())), verdict.engine(), verdict.score(),
                decision, Optional.empty(),
                decision == com.finapp.risk.RiskDecision.CHALLENGE ? Optional.of(false) : Optional.empty(),
                decision == com.finapp.risk.RiskDecision.ALLOW ? VelocityOutcome.UNCOUNTED : VelocityOutcome.NOT_PERMITTED,
                Optional.empty(), text, CanonicalEvaluation.sha256(text),
                CanonicalEvaluation.sha256(CanonicalEvaluation.render(verdict)), verdict.hits(), verdict.reasons(), 100,
                request.correlation().value(), "proofs-suite", Optional.empty());
        inTransaction(uow -> riskAssessmentStore.insert(uow, assessment));
    }

    /** {@code party}'s divergences in the record's link of the velocity proof, on a fresh snapshot. */
    private List<VelocityProof.Divergence> divergencesOf(UUID party) {
        VelocityProof.Whole whole = riskTransactions.inSnapshot(velocityProof::prove);
        List<VelocityProof.Divergence> named = new ArrayList<>();
        for (VelocityProof.Divergence divergence : whole.record().divergences()) {
            if (divergence.key().subjectRef().equals(party.toString())) {
                named.add(divergence);
            }
        }
        return named;
    }

    /** The count and the sum of {@code party}'s D30 window. */
    private static long[] figures(UUID party) throws Exception {
        try (Connection application = DatabaseRoles.application(); PreparedStatement select = application
                .prepareStatement("SELECT count, sum_minor FROM risk.velocity_window WHERE dimension = 'PARTY' AND"
                        + " subject_ref = ? AND window_kind = 'D30'")) {
            select.setString(1, party.toString());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new long[] {row.getLong(1), row.getLong(2)};
            }
        }
    }

    /** The latest {@code last_counted_at} of {@code party}'s windows - the database's stamp, read back. */
    private static java.time.Instant lastCountedAt(UUID party) throws Exception {
        try (Connection application = DatabaseRoles.application(); PreparedStatement select = application
                .prepareStatement("SELECT max(last_counted_at) FROM risk.velocity_window WHERE dimension = 'PARTY'"
                        + " AND subject_ref = ?")) {
            select.setString(1, party.toString());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, java.time.OffsetDateTime.class).toInstant();
            }
        }
    }

    private static long transfers(List<RiskProofReads.DecisionRow> rows) {
        return rows.stream().filter(row -> row.operation() == RiskOperation.TRANSFER)
                .mapToLong(RiskProofReads.DecisionRow::assessments).sum();
    }

    /** The whole day replayed page by page, each page its own snapshot: {replayed, diverged}. */
    private long[] wholeDay(DailyReplay replay, RiskDay day, Actor analyst) {
        long[] figures = new long[2];
        Optional<RiskAssessmentId> cursor = Optional.empty();
        do {
            Optional<RiskAssessmentId> after = cursor;
            DailyReplay.Result page = riskTransactions.inSnapshot(snapshot -> replay.replay(snapshot, day, analyst,
                    after, DailyReplay.MAX_LIMIT));
            figures[0] += page.replayed();
            figures[1] += page.diverged();
            cursor = page.next();
        } while (cursor.isPresent());
        return figures;
    }

    private static String setting(Connection snapshot) {
        try (PreparedStatement select = snapshot.prepareStatement("SELECT current_setting('statement_timeout')");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getString(1);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <T> T inTransaction(Function<Connection, T> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
