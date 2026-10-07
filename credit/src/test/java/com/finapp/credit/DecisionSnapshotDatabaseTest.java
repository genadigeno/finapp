package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The decision snapshot against a real database (`P10-TSK-008`; {@code INV-CRD-07}, {@code INV-CRD-08},
 * {@code INV-CRD-06}, {@code INV-CRD-10}, {@code INV-CRD-12}, {@code INV-CRD-04}). Records are seeded through the
 * collection's own store; freshness is judged on the freezing transaction's database instant, which the boundary
 * cases read inside the same transaction - so "exactly at the maximum age" is exactly that.
 */
@Tag("database")
@DisplayName("the decision input snapshot (P10-TSK-008)")
class DecisionSnapshotDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Duration MAX_AGE = Duration.ofDays(30);
    private static final PinnedVersions VERSIONS = new PinnedVersions(UUID.randomUUID(), UUID.randomUUID(), 1);

    private final JdbcCreditDataRequestStore requests = new JdbcCreditDataRequestStore();
    private final JdbcDecisionSnapshotStore snapshots = new JdbcDecisionSnapshotStore();

    // ------------------------------------------------------------------ freshness on the database's clock

    @Test
    @DisplayName("a record one second past the maximum age re-collects - a new data request, no snapshot")
    void aRecordOneSecondPastMaxAgeReCollects() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        SnapshotFreezer.Freeze freeze = inOneTransaction(uow -> {
            seedBureau(uow, decision, party, databaseNow(uow).minus(MAX_AGE).minusSeconds(1), cleanBureau());
            return freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation());
        });
        assertThat(freeze).isInstanceOf(SnapshotFreezer.Freeze.Recollecting.class);
        assertThat(((SnapshotFreezer.Freeze.Recollecting) freeze).reopened().get(CreditSourceKind.BUREAU))
                .isInstanceOf(CreditDataCollection.Opened.Requested.class);
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ?", decision))
                .as("the stale one and the new one").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", decision)).isZero();
    }

    /** Distinct from every other port's version, so the provenance is seen to be the port's own. */
    private static final int PLATFORM_EXPOSURE_VERSION = 2;

    @Test
    @DisplayName("the platform's outstanding credit is recorded - zero in Phase 10 - with its port's version (P10-TSK-010)")
    void platformOutstandingIsRecordedZeroWithItsVersion() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        SnapshotFreezer.Freeze freeze = inOneTransaction(uow -> {
            seedBureau(uow, decision, party, databaseNow(uow).minusSeconds(60), cleanBureau());
            return freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation());
        });
        CreditAttribute outstanding = ((SnapshotFreezer.Freeze.Frozen) freeze).snapshot().content()
                .attribute(CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT);
        assertThat(outstanding.value()).isEqualTo(new AttributeValue.MoneyValue(Money.zero(CurrencyCode.of("EUR"))));
        assertThat(outstanding.provenance())
                .isEqualTo(new AttributeProvenance.Port("platform-exposure", PLATFORM_EXPOSURE_VERSION));
    }

    @Test
    @DisplayName("a record at exactly the maximum age is fresh")
    void aRecordAtExactlyMaxAgeIsFresh() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        SnapshotFreezer.Freeze freeze = inOneTransaction(uow -> {
            seedBureau(uow, decision, party, databaseNow(uow).minus(MAX_AGE), cleanBureau());
            return freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation());
        });
        assertThat(freeze).isInstanceOf(SnapshotFreezer.Freeze.Frozen.class);
        DecisionSnapshot snapshot = ((SnapshotFreezer.Freeze.Frozen) freeze).snapshot();
        assertThat(snapshot.verifies()).isTrue();
        assertThat(snapshot.content().attribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE).value())
                .isEqualTo(new AttributeValue.IntegerValue(712));
        assertThat(snapshot.content().attribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME).provenance())
                .as("a kind the policy does not read").isEqualTo(new AttributeProvenance.NotRead(CreditSourceKind.FINANCIAL_DATA));
    }

    @Test
    @DisplayName("a skewed instance neither accepts stale nor refuses fresh - the judgement is the database's")
    void aSkewedInstanceNeitherAcceptsStaleNorRefusesFresh() throws Exception {
        UUID freshDecision = IDS.next();
        UUID staleDecision = IDS.next();
        SnapshotFreezer.Freeze fresh = inOneTransaction(uow -> {
            UUID party = IDS.next();
            seedBureau(uow, freshDecision, party, databaseNow(uow).minus(MAX_AGE).plusSeconds(2), cleanBureau());
            return freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(freshDecision, party, 1), correlation());
        });
        SnapshotFreezer.Freeze stale = inOneTransaction(uow -> {
            UUID party = IDS.next();
            seedBureau(uow, staleDecision, party, databaseNow(uow).minus(MAX_AGE).minusSeconds(2), cleanBureau());
            return freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(staleDecision, party, 1), correlation());
        });
        assertThat(fresh).as("two seconds inside the age: fresh, whatever an instance's clock says")
                .isInstanceOf(SnapshotFreezer.Freeze.Frozen.class);
        assertThat(stale).as("two seconds past it: stale, whatever an instance's clock says")
                .isInstanceOf(SnapshotFreezer.Freeze.Recollecting.class);
    }

    // ------------------------------------------------------------------ born once

    @Test
    @DisplayName("ten freezers on one request leave one snapshot, and every one answers with it")
    void tenFreezersLeaveOneSnapshot() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        inOneTransaction(uow -> {
            seedBureau(uow, decision, party, databaseNow(uow).minus(Duration.ofDays(1)), cleanBureau());
            return null;
        });
        int freezers = 10;
        CyclicBarrier start = new CyclicBarrier(freezers);
        ExecutorService instances = Executors.newFixedThreadPool(freezers);
        Set<UUID> ids = new HashSet<>();
        try {
            List<Future<SnapshotFreezer.Freeze>> freezes = new ArrayList<>();
            for (int i = 0; i < freezers; i++) {
                freezes.add(instances.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return inOneTransaction(uow -> freezer(signal("NOT_ASSESSED", 1))
                            .freeze(uow, input(decision, party, 1), correlation()));
                }));
            }
            for (Future<SnapshotFreezer.Freeze> freeze : freezes) {
                SnapshotFreezer.Freeze answer = freeze.get(60, TimeUnit.SECONDS);
                assertThat(answer).isInstanceOf(SnapshotFreezer.Freeze.Frozen.class);
                ids.add(((SnapshotFreezer.Freeze.Frozen) answer).snapshot().id().value());
            }
        } finally {
            instances.shutdownNow();
        }
        assertThat(ids).hasSize(1);
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", decision))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a fresher record arriving during the freeze belongs to no snapshot")
    void aFresherRecordArrivingDuringTheFreezeBelongsToNoSnapshot() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        CreditRecordId first = inOneTransaction(uow ->
                seedBureau(uow, decision, party, databaseNow(uow).minus(Duration.ofDays(2)), cleanBureau()));
        CreditRecordId later;
        try (Connection freezing = DatabaseRoles.application()) {
            freezing.setAutoCommit(false);
            SnapshotFreezer.Freeze freeze = freezer(signal("NOT_ASSESSED", 1))
                    .freeze(freezing, input(decision, party, 1), correlation());
            assertThat(freeze).isInstanceOf(SnapshotFreezer.Freeze.Frozen.class);
            // Another instance records a fresher answer for the same source while the freeze is uncommitted.
            later = inOneTransaction(uow ->
                    seedBureau(uow, decision, party, databaseNow(uow).minusSeconds(5), cleanBureau()));
            freezing.commit();
        }
        DecisionSnapshot snapshot = ((SnapshotFreezer.Freeze.Frozen) inOneTransaction(uow ->
                freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation()))).snapshot();
        assertThat(snapshot.canonical()).contains(first.value().toString()).doesNotContain(later.value().toString());
    }

    // ------------------------------------------------------------------ absence and its markers

    @Test
    @DisplayName("an unavailable source enters ABSENT with its marker")
    void anUnavailableSourceEntersAbsentWithItsMarker() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        CreditDataRequestId request = inOneTransaction(uow -> {
            CreditDataRequestId id = CreditDataRequestId.next(IDS);
            requests.insertRequested(uow, new CreditDataRequestStore.NewRequest(id, decision, party,
                    CreditProduct.PERSONAL_LOAN, CreditSourceKind.BUREAU, "bureau-test", "CDR-" + id.value(),
                    Duration.ofMillis(1), Duration.ofMillis(2)));
            requests.markUnavailable(uow, id, 1);
            return id;
        });
        Thread.sleep(50);
        SnapshotFreezer.Freeze freeze = inOneTransaction(uow ->
                freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation()));
        SnapshotContent content = ((SnapshotFreezer.Freeze.Frozen) freeze).snapshot().content();
        for (CreditAttributeCode code : CreditBureau.ATTRIBUTES) {
            assertThat(content.attribute(code).absent()).as(code.name()).isTrue();
            assertThat(content.attribute(code).provenance())
                    .isEqualTo(new AttributeProvenance.Unavailable(CreditSourceKind.BUREAU, request));
        }
        assertThat(content.attribute(CreditAttributeCode.SOURCE_UNAVAILABLE).value())
                .isEqualTo(new AttributeValue.CodeValue("BUREAU"));
    }

    @Test
    @DisplayName("a foreign-currency source is partial data, never converted")
    void aForeignCurrencySourceIsPartialNeverConverted() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        List<CreditAttribute> foreign = new ArrayList<>(cleanBureau());
        foreign.removeIf(a -> a.code() == CreditAttributeCode.BUREAU_TOTAL_BALANCE
                || a.code() == CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS);
        // As the normaliser stores a foreign balance: absent, with the record's own marker...
        foreign.add(attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE, new AttributeValue.Absent()));
        foreign.add(attribute(CreditAttributeCode.CURRENCY_NOT_SUPPORTED, new AttributeValue.CodeValue("BUREAU")));
        // ...and a foreign figure that reached storage anyway: the freeze refuses to carry it.
        foreign.add(attribute(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
                new AttributeValue.MoneyValue(Money.ofMinorUnits(35_000, CurrencyCode.of("USD")))));
        SnapshotFreezer.Freeze freeze = inOneTransaction(uow -> {
            seedBureau(uow, decision, party, databaseNow(uow).minus(Duration.ofDays(1)), foreign);
            return freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation());
        });
        SnapshotContent content = ((SnapshotFreezer.Freeze.Frozen) freeze).snapshot().content();
        assertThat(content.attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE).absent()).isTrue();
        assertThat(content.attribute(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS).absent()).as("never converted").isTrue();
        assertThat(content.attribute(CreditAttributeCode.CURRENCY_NOT_SUPPORTED).value())
                .isEqualTo(new AttributeValue.CodeValue("BUREAU"));
    }

    @Test
    @DisplayName("the risk signal is recorded as given, with its seam's version")
    void theRiskSignalIsRecordedWithItsSeamVersion() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        SnapshotFreezer.Freeze freeze = inOneTransaction(uow -> {
            seedBureau(uow, decision, party, databaseNow(uow).minus(Duration.ofDays(1)), cleanBureau());
            return freezer(signal("NOT_ASSESSED", 7)).freeze(uow, input(decision, party, 1), correlation());
        });
        DecisionSnapshot snapshot = ((SnapshotFreezer.Freeze.Frozen) freeze).snapshot();
        assertThat(snapshot.content().attribute(CreditAttributeCode.RISK_SIGNAL).value())
                .isEqualTo(new AttributeValue.CodeValue("NOT_ASSESSED"));
        assertThat(snapshot.canonical()).contains("{\"kind\":\"port\",\"port\":\"risk-signal\",\"version\":\"7\"}");
        assertThat(snapshot.content().attribute(CreditAttributeCode.PARTY_AGE_YEARS).absent())
                .as("no party fact is held (#13): absent, never defaulted").isTrue();
    }

    @Test
    @DisplayName("not ready and withdrawn sources freeze nothing")
    void notReadyAndWithdrawnFreezeNothing() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        SnapshotFreezer.Freeze none = inOneTransaction(uow ->
                freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation()));
        assertThat(none).isEqualTo(new SnapshotFreezer.Freeze.NotReady(Set.of(CreditSourceKind.BUREAU)));
        inOneTransaction(uow -> {
            CreditDataRequestId id = CreditDataRequestId.next(IDS);
            requests.insertRequested(uow, new CreditDataRequestStore.NewRequest(id, decision, party,
                    CreditProduct.PERSONAL_LOAN, CreditSourceKind.BUREAU, "bureau-test", "CDR-" + id.value(),
                    Duration.ofMinutes(1), Duration.ofMinutes(30)));
            requests.withdraw(uow, id, CreditDataRequestStatus.REQUESTED, 1);
            return null;
        });
        SnapshotFreezer.Freeze withdrawn = inOneTransaction(uow ->
                freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation()));
        assertThat(withdrawn).isEqualTo(new SnapshotFreezer.Freeze.ConsentWithdrawn(Set.of(CreditSourceKind.BUREAU)));
    }

    @Test
    @DisplayName("a snapshot row is never updated, deleted or truncated - and a body that disagrees with its hash is refused")
    void aSnapshotRowIsNeverUpdated() throws Exception {
        UUID decision = IDS.next();
        UUID party = IDS.next();
        inOneTransaction(uow -> {
            seedBureau(uow, decision, party, databaseNow(uow).minus(Duration.ofDays(1)), cleanBureau());
            return freezer(signal("NOT_ASSESSED", 1)).freeze(uow, input(decision, party, 1), correlation());
        });
        String where = " WHERE decision_request_id = '" + decision + "'";
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE credit.decision_snapshot SET sequence = 2" + where, "42501");
            refused(app, "DELETE FROM credit.decision_snapshot" + where, "42501");
            refused(app, "INSERT INTO credit.decision_snapshot (id, decision_request_id, sequence, snapshot_format,"
                    + " canonical, content_sha256, policy_version_id, model_version_id, engine_version, frozen_at) VALUES"
                    + " (gen_random_uuid(), gen_random_uuid(), 1, 1, '{}', sha256(convert_to('{ }', 'UTF8')),"
                    + " gen_random_uuid(), gen_random_uuid(), 1, now())", "23514");
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "UPDATE credit.decision_snapshot SET canonical = canonical" + where, "P0001");
            refused(owner, "DELETE FROM credit.decision_snapshot" + where, "P0001");
            refused(owner, "TRUNCATE credit.decision_snapshot", "P0001");
        }
    }

    // ------------------------------------------------------------------ harness

    private SnapshotFreezer freezer(CreditRiskSignal<Connection> risk) {
        CreditConsentGate<Connection> permits = (uow, partyId, kind) -> true;
        CreditBureau noBureau = new CreditBureau() {
            @Override
            public String code() {
                return "bureau-test";
            }

            @Override
            public CreditDataAnswer pull(CreditDataPull request) {
                throw new UnsupportedOperationException("the freeze opens collection; it never asks");
            }
        };
        FinancialDataProvider noFindata = new FinancialDataProvider() {
            @Override
            public String code() {
                return "findata-test";
            }

            @Override
            public CreditDataAnswer pull(CreditDataPull request) {
                throw new UnsupportedOperationException("the freeze opens collection; it never asks");
            }
        };
        CreditDataCollection.Timing timing = new CreditDataCollection.Timing(Duration.ofMinutes(1), Duration.ofMinutes(30));
        CreditDataCollection collection = new CreditDataCollection(requests,
                CreditDataCollection.Sources.of(noBureau, timing, noFindata, timing), permits,
                new CreditEvidenceCipher(new byte[32], 1, new SecureRandom()), CreditDataObserver.NONE,
                new JdbcAuditWriter(), new JdbcOutboxWriter(), DecisionSnapshotDatabaseTest::inOneTransaction, IDS, CLOCK);
        CreditPartyStanding<Connection> noFacts = (uow, partyId) ->
                new CreditPartyStanding.PartyFacts(Optional.empty(), Optional.empty(), 1);
        ReservedExposure<Connection> nothingReserved = new ReservedExposure<>() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public Money reservedFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
                return Money.zero(currency);
            }
        };
        PlatformCreditExposure<Connection> noLoans = new PlatformCreditExposure<>() {
            @Override
            public int version() {
                return PLATFORM_EXPOSURE_VERSION;
            }

            @Override
            public Money outstandingFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
                return Money.zero(currency);
            }
        };
        return new SnapshotFreezer(snapshots, collection, noFacts, risk, nothingReserved, noLoans, IDS);
    }

    private static CreditRiskSignal<Connection> signal(String code, int version) {
        return (uow, partyId) -> new CreditRiskSignal.RiskSignal(code, version);
    }

    private static SnapshotFreezer.FreezeInput input(UUID decision, UUID party, int sequence) {
        return new SnapshotFreezer.FreezeInput(decision, party, CreditProduct.PERSONAL_LOAN,
                Money.ofMinorUnits(1_000_000, EUR), Optional.of(36), Optional.of(Money.ofMinorUnits(320_000, EUR)),
                Optional.empty(), VERSIONS, Map.of(CreditSourceKind.BUREAU, MAX_AGE), sequence);
    }

    /** A received bureau data request for the decision, its record retrieved at {@code retrievedAt}. */
    private CreditRecordId seedBureau(
            Connection uow, UUID decision, UUID party, Instant retrievedAt, List<CreditAttribute> attributes) {
        CreditDataRequestId id = CreditDataRequestId.next(IDS);
        requests.insertRequested(uow, new CreditDataRequestStore.NewRequest(id, decision, party,
                CreditProduct.PERSONAL_LOAN, CreditSourceKind.BUREAU, "bureau-test", "CDR-" + id.value(),
                Duration.ofMinutes(1), Duration.ofMinutes(30)));
        requests.receive(uow, id, 1);
        CreditRecordId record = CreditRecordId.next(IDS);
        requests.insertRecord(uow, record, requests.find(uow, id).orElseThrow(), "bureau-test", 1, true, retrievedAt,
                attributes);
        return record;
    }

    private static List<CreditAttribute> cleanBureau() {
        return List.of(
                attribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, new AttributeValue.IntegerValue(712)),
                attribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, new AttributeValue.IntegerValue(4)),
                attribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, new AttributeValue.IntegerValue(0)),
                attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M, new AttributeValue.IntegerValue(0)),
                attribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, new AttributeValue.BooleanValue(false)),
                attribute(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
                        new AttributeValue.MoneyValue(Money.ofMinorUnits(35_000, EUR))),
                attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                        new AttributeValue.MoneyValue(Money.ofMinorUnits(420_050, EUR))));
    }

    private static CreditAttribute attribute(CreditAttributeCode code, AttributeValue value) {
        return new CreditAttribute(code, value, new AttributeProvenance.Provider(CreditSourceKind.BUREAU, "bureau-test", 1));
    }

    private static Instant databaseNow(Connection uow) {
        try (Statement statement = uow.createStatement();
                ResultSet now = statement.executeQuery("SELECT transaction_timestamp()")) {
            now.next();
            return now.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
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

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static int count(String sql, UUID decision) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setObject(1, decision);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static void refused(Connection connection, String sql, String sqlState) throws SQLException {
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
}
