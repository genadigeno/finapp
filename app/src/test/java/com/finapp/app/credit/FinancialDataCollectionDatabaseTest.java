package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.consent.ConsentGate;
import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.JdbcConsentStore;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditDataObserver;
import com.finapp.credit.CreditDataPull;
import com.finapp.credit.CreditDataRequestId;
import com.finapp.credit.CreditDataRequestStatus;
import com.finapp.credit.CreditEvidenceCipher;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.FinancialDataProvider;
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
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Financial-data collection against a real database and the simulated provider (`P10-TSK-007`; ADR-0085,
 * {@code INV-CRD-03}, {@code INV-CRD-10}): `-006`'s machinery for the second source kind - the duplicate, the lost
 * response and the withdrawal re-run and counted at the provider - under its OWN purpose: a bureau consent never
 * admits a financial-data pull.
 */
@Tag("database")
@DisplayName("financial-data collection (P10-TSK-007)")
class FinancialDataCollectionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final byte[] KEY = "a-findata-test-key-of-32-bytes-ok".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY = "a-credit-evidence-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);
    private static final CreditDataCollection.Timing TIMING =
            new CreditDataCollection.Timing(Duration.ofMinutes(1), Duration.ofMinutes(30));

    private final JdbcConsentStore consents = new JdbcConsentStore();
    private final CreditEvidenceCipher cipher = new CreditEvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom());
    private SimulatedBureauEngine engine;
    private FinancialDataProvider adapter;

    @BeforeEach
    void start() throws Exception {
        engine = SimulatedBureauEngine.startFinancialData();
        engine.slowness(Duration.ofMillis(2_000));
        adapter = new SimulatedFinancialDataAdapter(engine.baseUrl(), Duration.ofMillis(600), KEY,
                reference -> Optional.of(new CreditDataSubject(
                        "Applicant " + reference, LocalDate.of(1980, 1, 1), CountryCode.of("DE"))));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    @Test
    @DisplayName("an answer delivered twice leaves one record - the second is duplicate evidence")
    void anAnswerDeliveredTwiceLeavesOneRecord() throws Exception {
        UUID party = consented(ConsentPurpose.FINANCIAL_DATA_ACCESS);
        CyclicBarrier bothAsking = new CyclicBarrier(2);
        CreditDataCollection collection = collection(new Wrapped(adapter, request -> {
            await(bothAsking);
            return adapter.pull(request);
        }));
        CreditDataRequestId id = opened(TRANSACTIONS.inTransaction(uow ->
                collection.openWithin(uow, opening(party), correlation())));
        ExecutorService instances = Executors.newFixedThreadPool(2);
        try {
            Future<CreditDataRequestStatus> first = instances.submit(() -> collection.ask(id, correlation()));
            Future<CreditDataRequestStatus> second = instances.submit(() -> collection.ask(id, correlation()));
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(CreditDataRequestStatus.RECEIVED);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        } finally {
            instances.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ? AND source_kind ="
                + " 'FINANCIAL_DATA'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_evidence WHERE data_request_id = ? AND duplicate", id))
                .isEqualTo(1);
        assertThat(engine.pulls()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation ="
                + " 'credit.FinancialDataRequested'", id)).as("its own act, once").isEqualTo(1);
    }

    @Test
    @DisplayName("a lost response is re-asked under the same reference - one pull at the provider")
    void aLostResponseIsReaskedUnderTheSameReference() throws Exception {
        UUID party = consented(ConsentPurpose.FINANCIAL_DATA_ACCESS);
        CreditDataCollection collection = collection(adapter);
        engine.arm(SimulatedBureauEngine.Fault.LOSE_RESPONSE);
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.UNAVAILABLE);

        migrator("UPDATE credit.data_request SET next_attempt_at = statement_timestamp() - interval '1 second'"
                + " WHERE id = '" + id.value() + "'");
        for (CreditDataRequestId due : collection.claimDue(20)) {
            collection.retry(due, correlation());
        }
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        assertThat(engine.pulls()).isEqualTo(1);
        assertThat(engine.idempotencyKeys()).hasSize(2).containsOnly("CDR-" + id.value());
    }

    @Test
    @DisplayName("consent withdrawn in flight: the payload is discarded unread")
    void consentWithdrawnInFlightDiscardsThePayload() throws Exception {
        UUID party = consented(ConsentPurpose.FINANCIAL_DATA_ACCESS);
        CreditDataCollection collection = collection(new Wrapped(adapter, request -> {
            withdraw(party, ConsentPurpose.FINANCIAL_DATA_ACCESS);
            return adapter.pull(request);
        }));
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.CONSENT_WITHDRAWN);
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM credit.credit_evidence WHERE data_request_id = ? AND consent_withdrawn"
                + " AND content_ciphertext IS NULL", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a bureau consent does not admit a financial-data pull - nothing is born, nothing is asked")
    void aBureauConsentDoesNotAdmitAFinancialDataPull() throws Exception {
        UUID party = consented(ConsentPurpose.CREDIT_BUREAU_ACCESS);
        CreditDataCollection collection = collection(adapter);
        CreditDataCollection.Opening opening = opening(party);
        assertThat(collection.open(opening, correlation()))
                .isInstanceOf(CreditDataCollection.Opened.ConsentAbsent.class);
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ?",
                opening.decisionRequestId())).isZero();
        assertThat(engine.idempotencyKeys()).isEmpty();
    }

    // ------------------------------------------------------------------ harness

    private CreditDataCollection collection(FinancialDataProvider provider) {
        return new CreditDataCollection(new JdbcCreditDataRequestStore(),
                CreditDataCollection.Sources.of(new UnconfiguredBureau(), TIMING, provider, TIMING),
                new ConsentBackedCreditConsentGate(new ConsentGate<>(consents)), cipher, CreditDataObserver.NONE,
                new JdbcAuditWriter(), new JdbcOutboxWriter(), TRANSACTIONS, IDS, CLOCK);
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

    /** A provider that runs {@code around} instead of the adapter's pull, keeping its code. */
    private record Wrapped(FinancialDataProvider inner, Function<CreditDataPull, CreditDataAnswer> around)
            implements FinancialDataProvider {
        @Override
        public String code() {
            return inner.code();
        }

        @Override
        public CreditDataAnswer pull(CreditDataPull request) {
            return around.apply(request);
        }
    }

    private UUID consented(ConsentPurpose purpose) throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            consents.append(app, ConsentRecord.grant(IDS, CLOCK, party, purpose, 1));
        }
        return party;
    }

    private void withdraw(UUID party, ConsentPurpose purpose) {
        try (Connection app = DatabaseRoles.application()) {
            consents.append(app, ConsentRecord.withdrawal(IDS, CLOCK, party, purpose, 1));
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static CreditDataCollection.Opening opening(UUID party) {
        // A real request (`P10-TSK-014`): the data request references it.
        UUID decision = TRANSACTIONS.inTransaction(
                uow -> DecisionRequestRows.submitted(uow, party, CreditProduct.PERSONAL_LOAN));
        return new CreditDataCollection.Opening(decision, party, CreditProduct.PERSONAL_LOAN, CreditSourceKind.FINANCIAL_DATA);
    }

    private static CreditDataRequestId opened(CreditDataCollection.Opened opened) {
        assertThat(opened).isInstanceOf(CreditDataCollection.Opened.Requested.class);
        return ((CreditDataCollection.Opened.Requested) opened).id();
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
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

    private static int count(String sql, CreditDataRequestId id) throws SQLException {
        return count(sql, id.value());
    }

    private static int count(String sql, UUID id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setObject(1, sql.contains("target_id") ? id.toString() : id);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static void migrator(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
