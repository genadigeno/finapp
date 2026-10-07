package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.consent.ConsentGate;
import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.JdbcConsentStore;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditDataObserver;
import com.finapp.credit.CreditDataRequestId;
import com.finapp.credit.CreditDataRequestStatus;
import com.finapp.credit.CreditEvidenceCipher;
import com.finapp.credit.CreditEvidenceId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.JdbcCreditDataRequestStore;
import com.finapp.credit.TransactionRunner;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CountryCode;
import java.nio.charset.StandardCharsets;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Bureau data collection against a real database and the simulated bureau (`P10-TSK-006`; ADR-0085,
 * {@code INV-CRD-03}, {@code INV-CRD-10}, {@code INV-LIFE-03}, {@code INV-AUD-01}, {@code INV-CRD-07}).
 *
 * <p>Every count is read from the bureau ({@link SimulatedBureauEngine#pulls()}, its idempotency keys) or from the
 * rows - never from the collection's own say-so. Separate connections stand for separate instances.
 */
@Tag("database")
@DisplayName("bureau data collection (P10-TSK-006)")
class BureauCollectionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final byte[] KEY = "a-bureau-test-key-of-32-bytes-ok!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY = "a-credit-evidence-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RAISE_EXCEPTION = "P0001";
    private static final CreditDataCollection.Timing TIMING =
            new CreditDataCollection.Timing(Duration.ofMinutes(1), Duration.ofMinutes(30));

    private final JdbcConsentStore consents = new JdbcConsentStore();
    private final CreditEvidenceCipher cipher = new CreditEvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom());
    private final Map<CreditDataObserver.Outcome, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    private SimulatedBureauEngine engine;
    private CreditBureau adapter;

    @BeforeEach
    void start() throws Exception {
        engine = SimulatedBureauEngine.start();
        engine.slowness(Duration.ofMillis(2_000));
        adapter = new SimulatedBureauAdapter(engine.baseUrl(), Duration.ofMillis(600), KEY,
                reference -> Optional.of(new CreditDataSubject(
                        "Applicant " + reference, LocalDate.of(1980, 1, 1), CountryCode.of("DE"))));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    // ------------------------------------------------------------------ the counted races and faults

    @Test
    @DisplayName("an answer delivered twice leaves one record - the second is duplicate evidence")
    void anAnswerDeliveredTwiceLeavesOneRecord() throws Exception {
        UUID party = consentedParty();
        CyclicBarrier bothAsking = new CyclicBarrier(2);
        CreditBureau bothAtOnce = new Wrapped(adapter, request -> {
            await(bothAsking);
            return adapter.pull(request);
        });
        CreditDataCollection collection = collection(bothAtOnce, CLOCK, TIMING);
        CreditDataRequestId id = openOnly(collection, party);

        ExecutorService instances = Executors.newFixedThreadPool(2);
        try {
            Future<CreditDataRequestStatus> first = instances.submit(() -> collection.ask(id, correlation()));
            Future<CreditDataRequestStatus> second = instances.submit(() -> collection.ask(id, correlation()));
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(CreditDataRequestStatus.RECEIVED);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        } finally {
            instances.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_evidence WHERE data_request_id = ? AND duplicate", id))
                .as("the second delivery is kept, flagged").isEqualTo(1);
        assertThat(engine.pulls()).as("one pull at the bureau - it deduped our reference").isEqualTo(1);
        assertThat(outcome(CreditDataObserver.Outcome.DUPLICATE)).isEqualTo(1);

        // The second arbiter, for a writer that bypassed the lock and the REQUESTED condition: the database itself
        // refuses a second record for one data request (UNIQUE (data_request_id)).
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertRefused(app, "INSERT INTO credit.credit_record (id, data_request_id, party_id, source_kind,"
                    + " provider_code, normaliser_version, complete, retrieved_at, recorded_at) VALUES"
                    + " (gen_random_uuid(), '" + id.value() + "', '" + party + "', 'BUREAU', 'bureau-sim-a', 1, true,"
                    + " now(), now())", "23505");
        }
    }

    @Test
    @DisplayName("a lost response is re-asked under the same reference - one pull at the bureau")
    void aLostResponseIsReaskedUnderTheSameReference() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(adapter, CLOCK, TIMING);
        engine.arm(SimulatedBureauEngine.Fault.LOSE_RESPONSE);
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.UNAVAILABLE);

        makeDue(id);
        assertThat(sweep(collection)).isEqualTo(1);

        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        assertThat(engine.pulls()).isEqualTo(1);
        assertThat(engine.idempotencyKeys()).hasSize(2).containsOnly(reference(id));
        assertThat(attempts(id)).containsExactly("PROVIDER_ERROR", "RECEIVED");
    }

    @Test
    @DisplayName("consent withdrawn in flight: the payload is discarded unread - only its arrival is kept")
    void consentWithdrawnInFlightDiscardsThePayload() throws Exception {
        UUID party = consentedParty();
        CreditBureau withdrawingMidPull = new Wrapped(adapter, request -> {
            withdraw(party);
            return adapter.pull(request);
        });
        CreditDataCollection collection = collection(withdrawingMidPull, CLOCK, TIMING);
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));

        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.CONSENT_WITHDRAWN);
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM credit.credit_evidence WHERE data_request_id = ? AND consent_withdrawn"
                + " AND content_ciphertext IS NULL", id)).as("the arrival, and nothing of its content").isEqualTo(1);
        assertThat(attempts(id)).containsExactly("CONSENT_WITHDRAWN");
        assertThat(engine.pulls()).isEqualTo(1);
    }

    @Test
    @DisplayName("consent absent at open asks nothing - no request is born, no pull is made")
    void consentAbsentAtOpenAsksNothing() throws Exception {
        UUID party = IDS.next();
        CreditDataCollection collection = collection(adapter, CLOCK, TIMING);
        UUID decision = IDS.next();
        assertThat(collection.open(new CreditDataCollection.Opening(
                        decision, party, CreditProduct.PERSONAL_LOAN, CreditSourceKind.BUREAU), correlation()))
                .isInstanceOf(CreditDataCollection.Opened.ConsentAbsent.class);
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ?", decision)).isZero();
        assertThat(engine.pulls()).isZero();
        assertThat(engine.idempotencyKeys()).isEmpty();
    }

    @Test
    @DisplayName("ten retry sweepers on one due request ask once per permit - counted at the bureau")
    void tenRetrySweepersAskOncePerPermit() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(adapter, CLOCK, TIMING);
        engine.arm(SimulatedBureauEngine.Fault.UNAVAILABLE);
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        makeDue(id);

        int sweepers = 10;
        CyclicBarrier start = new CyclicBarrier(sweepers);
        ExecutorService instances = Executors.newFixedThreadPool(sweepers);
        AtomicInteger claimed = new AtomicInteger();
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < sweepers; i++) {
                CreditDataCollection instance = collection(adapter, CLOCK, TIMING);
                runs.add(instances.submit(() -> {
                    await(start);
                    for (CreditDataRequestId due : instance.claimDue(5)) {
                        if (due.equals(id)) {
                            claimed.incrementAndGet();
                        }
                        instance.retry(due, correlation());
                    }
                    return null;
                }));
            }
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            instances.shutdownNow();
        }
        assertThat(claimed.get()).as("one sweeper claimed the permit").isEqualTo(1);
        assertThat(engine.idempotencyKeys().stream().filter(reference(id)::equals))
                .as("the first ask and exactly one re-ask").hasSize(2);
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);
    }

    @Test
    @DisplayName("the deadline stops retries, and CreditDataUnavailable is emitted exactly once")
    void theDeadlineStopsRetriesAndEmitsUnavailableOnce() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(new UnconfiguredBureau(), CLOCK,
                new CreditDataCollection.Timing(Duration.ofMillis(200), Duration.ofMillis(900)));
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.UNAVAILABLE);

        awaitDatabasePast("SELECT deadline_at FROM credit.data_request WHERE id = ?", id);

        assertThat(collection.claimDue(10)).as("past its deadline, never asked again").doesNotContain(id);
        int reported = 0;
        for (int i = 0; i < 5; i++) {
            reported += collection.reportOverdue(10, correlation());
        }
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = '"
                + CreditDataCollection.UNAVAILABLE_EVENT + "'", id)).as("exactly once").isEqualTo(1);
        assertThat(reported).isGreaterThanOrEqualTo(1);
        assertRefusedAsOwner("UPDATE credit.data_request SET status = 'REQUESTED' WHERE id = '" + id.value() + "'");
    }

    @Test
    @DisplayName("a skewed sweeper neither retries early nor gives up early - every window is the database's")
    void aSkewedSweeperNeitherRetriesEarlyNorGivesUpEarly() throws Exception {
        CreditDataCollection.Timing timing = new CreditDataCollection.Timing(Duration.ofSeconds(30), Duration.ofSeconds(60));
        for (Duration skew : List.of(Duration.ofSeconds(5), Duration.ofSeconds(-5))) {
            UUID party = consentedParty();
            Clock skewed = Clock.offset(CLOCK, skew);
            CreditDataCollection collection = collection(new UnconfiguredBureau(), skewed, timing);
            CreditDataRequestId id = opened(collection.open(opening(party), correlation()));

            long permitAhead = millis("SELECT EXTRACT(EPOCH FROM (next_attempt_at - statement_timestamp())) * 1000"
                    + " FROM credit.data_request WHERE id = ?", id);
            assertThat(permitAhead).as("%s: the permit is the database's instant plus the cadence", skew)
                    .isBetween(28_000L, 30_500L);
            assertThat(millis("SELECT EXTRACT(EPOCH FROM (deadline_at - requested_at)) * 1000"
                    + " FROM credit.data_request WHERE id = ?", id)).isEqualTo(60_000L);
            assertThat(collection.claimDue(10)).as("%s: not early", skew).doesNotContain(id);

            makeDue(id);
            assertThat(collection.claimDue(10)).as("%s: due before the database's deadline - not given up", skew)
                    .contains(id);
        }
    }

    @Test
    @DisplayName("a timeout is UNAVAILABLE and retried to an answer")
    void aTimeoutIsUnavailableAndRetried() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(adapter, CLOCK, TIMING);
        engine.arm(SimulatedBureauEngine.Fault.SILENT);
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.UNAVAILABLE);

        makeDue(id);
        sweep(collection);
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        assertThat(attempts(id)).containsExactly("TIMEOUT", "RECEIVED");
    }

    @Test
    @DisplayName("a crash after the opening transaction is re-asked by the sweep")
    void aCrashAfterTheOpeningTransactionIsReasked() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(adapter, CLOCK, TIMING);
        CreditDataRequestId id = openOnly(collection, party);
        assertThat(engine.pulls()).isZero();

        assertThat(collection.claimDue(10)).as("the opener's permit still holds").doesNotContain(id);
        makeDue(id);
        sweep(collection);
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        assertThat(engine.pulls()).isEqualTo(1);
    }

    @Test
    @DisplayName("undue work never starves due work - the oldest permit first, each claim re-stamped")
    void undueWorkNeverStarvesDueWork() throws Exception {
        CreditDataCollection collection = collection(new UnconfiguredBureau(), CLOCK, TIMING);
        List<CreditDataRequestId> due = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            CreditDataRequestId id = opened(collection.open(opening(consentedParty()), correlation()));
            due.add(id);
        }
        List<CreditDataRequestId> undue = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            undue.add(opened(collection.open(opening(consentedParty()), correlation())));
        }
        for (int i = 0; i < due.size(); i++) {
            migrator("UPDATE credit.data_request SET next_attempt_at = statement_timestamp() - interval '"
                    + (10 - i) + " seconds' WHERE id = '" + due.get(i).value() + "'");
        }
        List<CreditDataRequestId> order = new ArrayList<>();
        for (int i = 0; i < due.size(); i++) {
            List<CreditDataRequestId> claimed = collection.claimDue(1).stream()
                    .filter(id -> due.contains(id) || undue.contains(id)).toList();
            order.addAll(claimed);
        }
        assertThat(order).as("oldest permit first, and a claimed request moves out of the way").isEqualTo(due);
        assertThat(order).doesNotContainAnyElementsOf(undue);
    }

    @Test
    @DisplayName("a withdrawal after the answer leaves the record RECEIVED - the decision request acts on it")
    void aWithdrawalAfterTheAnswerLeavesTheRecordReceived() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(adapter, CLOCK, TIMING);
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);

        withdraw(party);
        sweep(collection);
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        assertRefusedAsOwner("UPDATE credit.data_request SET status = 'CONSENT_WITHDRAWN' WHERE id = '" + id.value() + "'");
    }

    @Test
    @DisplayName("a retry after a withdrawal asks nothing - UNAVAILABLE becomes CONSENT_WITHDRAWN")
    void aRetryAfterAWithdrawalAsksNothing() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(adapter, CLOCK, TIMING);
        engine.arm(SimulatedBureauEngine.Fault.UNAVAILABLE);
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        withdraw(party);
        makeDue(id);
        sweep(collection);
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.CONSENT_WITHDRAWN);
        assertThat(engine.idempotencyKeys()).as("only the first ask reached the bureau").hasSize(1);
        assertThat(attempts(id)).containsExactly("PROVIDER_ERROR", "CONSENT_WITHDRAWN");
    }

    // ------------------------------------------------------------------ the record, the evidence, the audit

    @Test
    @DisplayName("a received answer: the record, its typed attributes, the sealed evidence, the event and the audit")
    void aReceivedAnswerIsRecordedWhole() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(adapter, CLOCK, TIMING);
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));

        assertThat(count("SELECT count(*) FROM credit.credit_record_attribute a JOIN credit.credit_record r"
                + " ON a.record_id = r.id WHERE r.data_request_id = ?", id)).isEqualTo(CreditBureau.ATTRIBUTES.size());
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ? AND provider_code = '"
                + SimulatedBureauAdapter.CODE + "' AND normaliser_version = 1 AND complete", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = '"
                + CreditDataCollection.COLLECTED_EVENT + "'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation ="
                + " 'credit.BureauDataRequested'", id)).as("audited once, by the opener").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_evidence WHERE data_request_id = ?"
                + " AND retain_until = recorded_at + interval '25 months'", id)).isEqualTo(1);

        // The evidence reads back only through the reasoned definer function, and decrypts only under its own id.
        EvidenceRow evidence = readEvidence(id, "dispute review OPS-1");
        byte[] plaintext = cipher.decrypt(evidence.id(),
                new CreditEvidenceCipher.Encrypted(evidence.ciphertext(), evidence.nonce(), evidence.keyVersion()));
        assertThat(new String(plaintext, StandardCharsets.UTF_8)).contains("report_complete");
        assertThatExceptionOfType(IllegalStateException.class).as("bound to its own row")
                .isThrownBy(() -> cipher.decrypt(CreditEvidenceId.of(IDS.next()),
                        new CreditEvidenceCipher.Encrypted(evidence.ciphertext(), evidence.nonce(), evidence.keyVersion())));
    }

    @Test
    @DisplayName("a needle in the bureau's payload is nowhere but in the ciphertext")
    void theNeedleIsOnlyInTheCiphertext() throws Exception {
        String needle = "NEEDLE" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        engine.note(needle);
        UUID party = consentedParty();
        CreditDataRequestId id = opened(collection(adapter, CLOCK, TIMING).open(opening(party), correlation()));
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        for (String table : List.of("credit.data_request", "credit.data_request_attempt", "credit.credit_record",
                "credit.credit_record_attribute", "credit.credit_evidence", "platform.outbox_event",
                "platform.audit_record")) {
            assertThat(countLike("SELECT count(*) FROM " + table + " t WHERE row_to_json(t)::text LIKE ?", needle))
                    .as(table).isZero();
        }
        EvidenceRow evidence = readEvidence(id, "needle check");
        assertThat(new String(cipher.decrypt(evidence.id(), new CreditEvidenceCipher.Encrypted(
                evidence.ciphertext(), evidence.nonce(), evidence.keyVersion())), StandardCharsets.UTF_8))
                .as("and it IS in the sealed bytes").contains(needle);
    }

    // ------------------------------------------------------------------ the grants and the machine, raw

    @Test
    @DisplayName("the application cannot read the evidence, and the definer function demands a reason")
    void theEvidenceIsUnreadableWithoutAReason() throws Exception {
        UUID party = consentedParty();
        CreditDataRequestId id = opened(collection(adapter, CLOCK, TIMING).open(opening(party), correlation()));
        UUID evidenceId = uuid("SELECT id FROM credit.credit_evidence WHERE data_request_id = ?", id);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertRefused(app, "SELECT * FROM credit.credit_evidence", INSUFFICIENT_PRIVILEGE);
            assertRefused(app, "SELECT * FROM credit.read_evidence('" + evidenceId + "', '  ')", RAISE_EXCEPTION);
            assertRefused(app, "UPDATE credit.credit_record SET complete = false", INSUFFICIENT_PRIVILEGE);
            assertRefused(app, "DELETE FROM credit.credit_record", INSUFFICIENT_PRIVILEGE);
            assertRefused(app, "DELETE FROM credit.data_request", INSUFFICIENT_PRIVILEGE);
            assertRefused(app, "UPDATE credit.data_request SET deadline_at = now()", INSUFFICIENT_PRIVILEGE);
        }
        assertRefusedAsOwner("UPDATE credit.credit_record SET complete = false WHERE data_request_id = '" + id.value() + "'");
        assertRefusedAsOwner("DELETE FROM credit.credit_record_attribute");
        assertRefusedAsOwner("UPDATE credit.credit_evidence SET duplicate = true WHERE id = '" + evidenceId + "'");
        assertRefusedAsOwner("DELETE FROM credit.credit_evidence WHERE id = '" + evidenceId + "'");
        assertRefusedAsOwner("TRUNCATE credit.credit_evidence");
    }

    @Test
    @DisplayName("every machine edge: the valid ones move, the invalid ones and every frozen column are refused")
    void everyMachineEdge() throws Exception {
        UUID party = consentedParty();
        CreditDataCollection collection = collection(new UnconfiguredBureau(), CLOCK, TIMING);
        CreditDataRequestId unavailable = opened(collection.open(opening(party), correlation()));
        CreditDataRequestId requested = openOnly(collection, party);
        String u = "'" + unavailable.value() + "'";
        String r = "'" + requested.value() + "'";

        // REQUESTED: to itself, and never back from a terminal.
        migrator("UPDATE credit.data_request SET next_attempt_at = statement_timestamp() WHERE id = " + r);
        // UNAVAILABLE -> REQUESTED before the deadline, and back.
        migrator("UPDATE credit.data_request SET status = 'REQUESTED' WHERE id = " + u);
        migrator("UPDATE credit.data_request SET status = 'UNAVAILABLE' WHERE id = " + u);
        // UNAVAILABLE -> CONSENT_WITHDRAWN (G11), then terminal.
        migrator("UPDATE credit.data_request SET status = 'CONSENT_WITHDRAWN' WHERE id = " + u);
        assertRefusedAsOwner("UPDATE credit.data_request SET status = 'REQUESTED' WHERE id = " + u);
        assertRefusedAsOwner("UPDATE credit.data_request SET status = 'RECEIVED' WHERE id = " + u);
        // REQUESTED -> RECEIVED, then terminal.
        migrator("UPDATE credit.data_request SET status = 'RECEIVED' WHERE id = " + r);
        assertRefusedAsOwner("UPDATE credit.data_request SET status = 'UNAVAILABLE' WHERE id = " + r);
        assertRefusedAsOwner("UPDATE credit.data_request SET status = 'CONSENT_WITHDRAWN' WHERE id = " + r);
        // Frozen, monotone and undeletable.
        assertRefusedAsOwner("UPDATE credit.data_request SET request_reference = 'CDR-other' WHERE id = " + r);
        assertRefusedAsOwner("UPDATE credit.data_request SET deadline_at = deadline_at + interval '1 hour' WHERE id = " + r);
        assertRefusedAsOwner("UPDATE credit.data_request SET retry_cadence = interval '1 second' WHERE id = " + r);
        assertRefusedAsOwner("UPDATE credit.data_request SET attempts = attempts - 1 WHERE id = " + r);
        assertRefusedAsOwner("DELETE FROM credit.data_request WHERE id = " + r);
        assertRefusedAsOwner("INSERT INTO credit.data_request (id, decision_request_id, party_id, product, source_kind,"
                + " provider_code, request_reference, status, attempts, retry_cadence, collection_window,"
                + " unavailable_reported) VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
                + " 'PERSONAL_LOAN', 'BUREAU', 'bureau-x', 'CDR-born-received', 'RECEIVED', 0, interval '1 minute',"
                + " interval '1 hour', false)");
    }

    // ------------------------------------------------------------------ harness

    private CreditDataCollection collection(CreditBureau bureau, Clock clock, CreditDataCollection.Timing timing) {
        return new CreditDataCollection(new JdbcCreditDataRequestStore(),
                CreditDataCollection.Sources.of(bureau, timing, new UnconfiguredFinancialData(), timing),
                new ConsentBackedCreditConsentGate(new ConsentGate<>(consents)), cipher, new CreditDataObserver() {
                    @Override
                    public void answered(CreditSourceKind kind, String providerCode, Outcome outcome) {
                        outcomes.computeIfAbsent(outcome, key -> new AtomicInteger()).incrementAndGet();
                    }

                    @Override
                    public void called(CreditSourceKind kind, String providerCode, Duration took) {}
                }, new JdbcAuditWriter(), new JdbcOutboxWriter(), TRANSACTIONS, IDS, clock);
    }

    private static final TransactionRunner TRANSACTIONS = new TransactionRunner() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection connection = DatabaseRoles.application()) {
                connection.setAutoCommit(false);
                try {
                    R result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    /** A bureau that runs {@code around} instead of the adapter's pull, keeping its code. */
    private record Wrapped(CreditBureau inner, Function<com.finapp.credit.CreditDataPull, CreditDataAnswer> around)
            implements CreditBureau {
        @Override
        public String code() {
            return inner.code();
        }

        @Override
        public CreditDataAnswer pull(com.finapp.credit.CreditDataPull request) {
            return around.apply(request);
        }
    }

    private record EvidenceRow(CreditEvidenceId id, byte[] ciphertext, byte[] nonce, int keyVersion) {}

    private UUID consentedParty() throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            consents.append(app, ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.CREDIT_BUREAU_ACCESS, 1));
        }
        return party;
    }

    private void withdraw(UUID party) {
        try (Connection app = DatabaseRoles.application()) {
            consents.append(app, ConsentRecord.withdrawal(IDS, CLOCK, party, ConsentPurpose.CREDIT_BUREAU_ACCESS, 1));
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static CreditDataCollection.Opening opening(UUID party) {
        return new CreditDataCollection.Opening(IDS.next(), party, CreditProduct.PERSONAL_LOAN, CreditSourceKind.BUREAU);
    }

    /** Tx1 alone, committed - the opener then "crashes" before asking. */
    private CreditDataRequestId openOnly(CreditDataCollection collection, UUID party) {
        return opened(TRANSACTIONS.inTransaction(uow -> collection.openWithin(uow, opening(party), correlation())));
    }

    private static CreditDataRequestId opened(CreditDataCollection.Opened opened) {
        assertThat(opened).isInstanceOf(CreditDataCollection.Opened.Requested.class);
        return ((CreditDataCollection.Opened.Requested) opened).id();
    }

    private int sweep(CreditDataCollection collection) {
        List<CreditDataRequestId> claimed = collection.claimDue(20);
        for (CreditDataRequestId id : claimed) {
            collection.retry(id, correlation());
        }
        collection.reportOverdue(20, correlation());
        return claimed.size();
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private int outcome(CreditDataObserver.Outcome outcome) {
        return outcomes.getOrDefault(outcome, new AtomicInteger()).get();
    }

    private static String reference(CreditDataRequestId id) {
        return "CDR-" + id.value();
    }

    private static void makeDue(CreditDataRequestId id) throws SQLException {
        migrator("UPDATE credit.data_request SET next_attempt_at = statement_timestamp() - interval '1 second'"
                + " WHERE id = '" + id.value() + "'");
    }

    private static CreditDataRequestStatus status(CreditDataRequestId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT status FROM credit.data_request WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return CreditDataRequestStatus.valueOf(row.getString(1));
            }
        }
    }

    private static List<String> attempts(CreditDataRequestId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT outcome FROM credit.data_request_attempt WHERE data_request_id = ? ORDER BY attempt")) {
            select.setObject(1, id.value());
            List<String> outcomes = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    outcomes.add(rows.getString(1));
                }
            }
            return outcomes;
        }
    }

    private static int count(String sql, CreditDataRequestId id) throws SQLException {
        return count(sql, id.value());
    }

    private static int count(String sql, UUID id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            if (sql.contains("?")) {
                select.setObject(1, sql.contains("target_id") ? id.toString() : id);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static int countLike(String sql, String needle) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setString(1, "%" + needle + "%");
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static long millis(String sql, CreditDataRequestId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return Math.round(row.getDouble(1));
            }
        }
    }

    private static UUID uuid(String sql, CreditDataRequestId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static EvidenceRow readEvidence(CreditDataRequestId id, String reason) throws SQLException {
        UUID evidenceId = uuid("SELECT id FROM credit.credit_evidence WHERE data_request_id = ? AND NOT duplicate"
                + " AND NOT consent_withdrawn ORDER BY attempt DESC LIMIT 1", id);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT content_ciphertext, content_nonce, key_version FROM credit.read_evidence(?, ?)")) {
            read.setObject(1, evidenceId);
            read.setString(2, reason);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the definer function returns the row").isTrue();
                return new EvidenceRow(CreditEvidenceId.of(evidenceId), row.getBytes(1), row.getBytes(2), row.getInt(3));
            }
        }
    }

    private static void awaitDatabasePast(String sql, CreditDataRequestId id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection migrator = DatabaseRoles.migrator();
                    PreparedStatement select = migrator.prepareStatement(
                            "SELECT (" + sql.replace("?", "'" + id.value() + "'") + ") <= statement_timestamp()")) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the database's clock never passed the instant");
    }

    private static void migrator(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertRefusedAsOwner(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            assertRefused(migrator, sql, RAISE_EXCEPTION);
        }
    }

    private static void assertRefused(Connection connection, String sql, String sqlState) throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .as(sql)
                .isThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .matches(e -> sqlState.equals(e.getSQLState()), "SQLState " + sqlState);
        connection.rollback();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
