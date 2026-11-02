package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.OperationalReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.ReportStatement;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An operational report is never a run (`P14-TSK-017`; ADR-0115 As built; {@code INV-ACC-04}): {@code accounting V004} holds a
 * PROVISIONAL run to an {@code OPEN} period for every writer and a FINAL run keeps answering, so the claim refuses it before anything is
 * read or written, whatever the period's status; {@code ReportGeneration} refuses it FINAL inputs; and it is answered live, labelled
 * {@code OPERATIONAL}, on the snapshot's database instant, with nothing stored but the record of the read. Every other definition is
 * exactly as it was - its header's basis, its key, its bytes.
 */
@DisplayName("an operational report is never a run (P14-TSK-017)")
class OperationalReportsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Instant DATABASE_NOW = Instant.parse("2097-04-05T09:30:00Z");

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final Moving moving = new Moving();
    private final ReportDefinitions definitions = ReportDefinitions.of(moving, TestReportDefinition.echo());
    private final ReportGeneration generation = new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, Duration.ofMinutes(4),
            () -> 0L);
    private final OperationalReports reports = new OperationalReports(definitions, periods, generation,
            (uow, record) -> audited.add(record), IDS, CLOCK);
    private final ReportRuns runs = new ReportRuns(new InMemoryReportRunStore(CLOCK, periods), periods, new InMemoryGlMappingStore(),
            definitions, ReportRuns.Leases.STANDARD, (uow, record) -> audited.add(record),
            (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);

    @Test
    @DisplayName("the claim refuses it first, OPEN or LOCKED - no row, no lease, no key - and answers any other definition as it did")
    void theClaimRefusesIt() {
        AccountingPeriodId march = period();
        assertThatThrownBy(() -> runs.claim(uow(), Moving.CODE, march, RunParameters.none(), ACCOUNTANT, correlation()))
                .as("OPEN").isInstanceOf(ReportRuns.DefinitionIsOperational.class);
        periods.markLocked(uow(), march, 1, ACCOUNTANT, Optional.empty());
        assertThatThrownBy(() -> runs.claim(uow(), Moving.CODE, march, RunParameters.none(), ACCOUNTANT, correlation()))
                .as("LOCKED").isInstanceOf(ReportRuns.DefinitionIsOperational.class);
        assertThat(audited).isEmpty();
        assertThatThrownBy(() -> runs.claim(uow(), Moving.CODE, AccountingPeriodId.next(IDS), RunParameters.none(), ACCOUNTANT,
                correlation())).as("refused before the period is even read").isInstanceOf(ReportRuns.DefinitionIsOperational.class);

        ReportRuns.Claimed plain = runs.claim(uow(), TestReportDefinition.CODE, march, RunParameters.none(), ACCOUNTANT, correlation());
        assertThat(plain.run().basis()).isEqualTo(RunBasis.FINAL);
    }

    @Test
    @DisplayName("its body is never generated FINAL: the generation refuses those inputs, whoever asks")
    void itIsNeverGeneratedFinal() {
        ReportInputs finalInputs = new ReportInputs(AccountingPeriodId.next(IDS), MARCH, 1, RunBasis.FINAL, Optional.of(DATABASE_NOW),
                Optional.empty(), Optional.empty(), DATABASE_NOW, RunParameters.none());
        assertThatThrownBy(() -> generation.generate(uow(), moving, finalInputs)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the answer: labelled OPERATIONAL, its cut the database's instant read on the snapshot, the content hashed - and a second read"
            + " of what moved is a different answer, which no run could have been")
    void theAnswerIsLabelled() {
        AccountingPeriodId march = period();
        periods.markLocked(uow(), march, 1, ACCOUNTANT, Optional.empty());
        OperationalReports.Answer first = reports.answer(uow(), Moving.CODE, march);
        assertThat(first.asOf()).isEqualTo(DATABASE_NOW);
        assertThat(first.month()).isEqualTo(MARCH);
        assertThat(first.definition()).isEqualTo(Moving.CODE);
        assertThat(first.generated().content())
                .contains("\"basis\":\"" + ReportGeneration.OPERATIONAL + "\"")
                .contains("\"cut\":\"" + DATABASE_NOW + "\"")
                .doesNotContain("\"basis\":\"FINAL\"")
                .doesNotContain("\"basis\":\"PROVISIONAL\"");
        assertThat(first.generated().sha256()).isEqualTo(CanonicalJson.sha256(first.generated().content()));

        moving.attempts++;
        assertThat(reports.answer(uow(), Moving.CODE, march).generated().sha256()).isNotEqualTo(first.generated().sha256());
    }

    @Test
    @DisplayName("the refusals: a run's definition is not read here, an unknown code is unknown, an unknown period not found - nothing recorded")
    void theRefusals() {
        AccountingPeriodId march = period();
        assertThatThrownBy(() -> reports.answer(uow(), TestReportDefinition.CODE, march))
                .isInstanceOf(OperationalReports.NotOperational.class);
        assertThatThrownBy(() -> reports.answer(uow(), "NO_SUCH_REPORT", march)).isInstanceOf(ReportRuns.DefinitionUnknown.class);
        assertThatThrownBy(() -> reports.answer(uow(), Moving.CODE, AccountingPeriodId.next(IDS)))
                .isInstanceOf(AccountingPeriods.PeriodNotFound.class);
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("the record of the read: its action, the period, the definition, its version and hash - never the content")
    void theReadIsRecorded() {
        AccountingPeriodId march = period();
        OperationalReports.Answer answer = reports.answer(uow(), Moving.CODE, march);
        reports.recordRead(uow(), answer, ACCOUNTANT, correlation());
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(AccountingAuditAction.OPERATIONAL_REPORT_READ);
            assertThat(record.targetType()).isEqualTo(OperationalReports.TARGET_TYPE);
            assertThat(record.targetId()).isEqualTo(march.value().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("definition=" + Moving.CODE, "version=1", "period=2097-03",
                    "contentSha256=" + answer.generated().sha256()).doesNotContain("attempts");
        });
    }

    @Test
    @DisplayName("the stalled gauge's read: a lock within the bound and a lock with a run are not stalled; a lock past the bound with none is,"
            + " on the store's clock; an unlock clears it - and the bound refuses to be shorter than the ordinary gap")
    void theStall() {
        InMemoryCloseRunStore store = new InMemoryCloseRunStore(periods);
        Duration bound = new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.STANDARD_POLLS).after();
        assertThat(bound).isEqualTo(Duration.ofMinutes(5));
        AccountingPeriodId march = period();
        periods.markLocked(uow(), march, 1, ACCOUNTANT, Optional.empty());
        Instant lockedAt = periods.find(uow(), march).orElseThrow().lockedAt().orElseThrow();
        store.advance(Duration.between(Instant.parse("2097-04-05T09:00:00Z"), lockedAt));
        assertThat(store.stalledLocks(uow(), bound)).as("just locked: the ordinary gap").isZero();
        store.advance(bound.minusMillis(1));
        assertThat(store.stalledLocks(uow(), bound)).as("a millisecond inside").isZero();
        store.advance(Duration.ofMillis(1));
        assertThat(store.stalledLocks(uow(), bound)).as("at the bound").isEqualTo(1);
        CloseRuns closeRuns = new CloseRuns(store, periods, new com.finapp.accounting.testing.InMemoryAccountingBreakStore(),
                CloseRuns.Leases.STANDARD, (uow, record) -> { }, IDS, CLOCK);
        closeRuns.startMissing(uow(), Actor.SYSTEM);
        assertThat(store.stalledLocks(uow(), bound)).as("the leg ran").isZero();

        assertThatThrownBy(() -> new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.MIN_POLLS - 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloseRunStall(Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private AccountingPeriodId period() {
        AccountingPeriodId id = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(uow(), new AccountingPeriodStore.NewPeriod(id, MARCH, correlation()));
        return id;
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** A snapshot that answers only the database's instant - the in-memory stores never touch it. */
    private static Connection uow() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && OperationalReports.AS_OF_SQL.equals(args[0])) {
                        return instantStatement();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PreparedStatement instantStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> instantRow();
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet instantRow() {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getTimestamp" -> Timestamp.from(DATABASE_NOW);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A definition reading what moves: its body changes between two reads of the same period. */
    private static final class Moving implements OperationalReportDefinition {
        static final String CODE = "TEST_MOVING";
        private int attempts = 1;

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public ReportStatement statement() {
            return new ReportStatement("a suite's moving source", "a count that moves", "the suite's own instant",
                    "holds no amount at all", "operational and never a run");
        }

        @Override
        public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
            return CanonicalJson.object().put("attempts", attempts).put("id", UUID.nameUUIDFromBytes(new byte[] {1}).toString());
        }
    }
}
