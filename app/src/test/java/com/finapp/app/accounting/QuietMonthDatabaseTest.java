package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckId;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.app.reconciliation.CorridorRuleSetV1;
import com.finapp.app.reconciliation.FxRuleSetV1;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BatchRepudiations;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.ResolutionReasonCode;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSetProposal;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * An honestly quiet month closes (`P14-TSK-012`; the owner's decisions of 2026-11-02 and 2026-11-03; {@code RECONCILIATION_DISCLOSURE}): a month with no run is {@code FAILED}, naming every active source; each counterparty's nil report goes through the existing door - the
 * channel's authentication here, a pull - the parse, the acceptance and the run leg, its run {@code COMPLETED} at no items with nothing posted; and the check then reads the month as run. Nothing here mints a run: every one is born in an acceptance's transaction, from a stored file. The real
 * composition, against PostgreSQL. <strong>Written and owed</strong> (owner decision C5); the formats' half is {@code NilReportsAreEvidenceTest}, and runs on every build.
 *
 * <p><strong>Rule sets</strong>: reconciliation {@code V002} seeds the four first sources' rule sets; no migration seeds a counterparty's (D26, {@code OPERATIONS_RUNBOOK.md} section 3), and with none a parsed report waits {@code PARSED}
 * with backoff. So a person activates the other four, four eyes, as a deployment must before such a source can have a run; {@link #everySourceHasItsRuleSet} does it through the same administration.
 * <strong>Since `P14-TSK-025`</strong> a source is required only from the day its first rule set stood active: this suite activates the four at its own {@code Instant.now()}, years after March 1999, so March
 * requires the four seeded sources alone, and the counterparties' nil reports are runs beside it, finished and counted for nothing. That a source activated by a month's last day is required in it is
 * {@code SourceRequirementDatabaseTest}'s.
 *
 * <p><strong>A repudiation</strong> ({@link #aRepudiatedBatchsRunCountsForNothingAndTheNextBatchCounts}): the run stands and counts for nothing; settlement's live uniques exclude {@code REPUDIATED}, so a new batch of the same source, date and
 * identity is admitted, with its own run.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): a bank statement's chain starts at sequence 1 with a zero opening, and a shared database's has moved on.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@SpringBootTest
@DisplayName("a quiet month runs on nil reports (P14-TSK-012)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class QuietMonthDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final YearMonth MONTH = YearMonth.of(1999, 3);
    private static final LocalDate DAY = MONTH.atDay(31);
    /** A month of its own, so the two cases read nothing of each other's. */
    private static final YearMonth REPUDIATED_MONTH = YearMonth.of(1999, 4);
    private static final String PSP = "simulated-psp.settlement";
    /** Reconciliation {@code V002}'s four seeded rule sets' sources: required from their admission, 1970-01-01 (settlement {@code V018}). */
    private static final java.util.Set<String> SEEDED_BY_V002 = java.util.Set.of(PSP, "simulated-scheme.cycle-report", "simulated-payout.settlement",
            "simulated-bank.statement");

    @Autowired private SettlementSources sources;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private ReconciliationMonthReads monthReads;
    @Autowired private CloseChecks closeChecks;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private BatchRepudiations repudiations;
    @Autowired private com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner;

    @BeforeEach
    void everySourceHasItsRuleSet() throws Exception {
        ruleSetActive(FxRuleSetV1.SOURCE, FxRuleSetV1.proposal("The quiet month suite: fx-sim-a's first version"));
        ruleSetActive(FxRuleSetV1.SOURCE_B, FxRuleSetV1.proposalB("The quiet month suite: fx-sim-b's first version"));
        ruleSetActive(CorridorRuleSetV1.SOURCE, CorridorRuleSetV1.proposal("The quiet month suite: corridor-sim-a's first version"));
        ruleSetActive(CorridorRuleSetV1.SOURCE_B, CorridorRuleSetV1.proposalB("The quiet month suite: corridor-sim-b's first version"));
    }

    @Test
    @DisplayName("no run: FAILED MONTH_NOT_RUN, naming every source the month requires - the four seeded ones, not the four whose rule sets this suite activated years after it; nil reports through the door: COMPLETED BATCH runs, posting nothing, the month run")
    void aQuietMonthRunsOnNilReports() throws Exception {
        CheckVerdict quiet = judged(MONTH);
        assertThat(quiet.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(quiet.failures()).containsExactly(CloseChecks.MONTH_NOT_RUN);
        sources.declared().forEach(source -> assertThat(quiet.evidence()).as("every source on the record").contains(source.code()));
        String notRun = sourcesNotRun(MONTH);
        for (SettlementSourceDescriptor source : sources.declared()) {
            boolean seeded = SEEDED_BY_V002.contains(source.code());
            assertThat(notRun.contains("\"" + source.code() + "\"")).as("%s: required in March 1999 only by its seed (P14-TSK-025)", source.code()).isEqualTo(seeded);
        }

        for (SettlementSourceDescriptor source : sources.declared()) {
            pull(source, nilReport(source));
        }
        for (int round = 0; round < sources.declared().size(); round++) {
            parsing.sweep();
            acceptance.sweep();
            matching.sweep();
        }

        ReconciliationRunCoverage.Coverage coverage;
        try (Connection app = DatabaseRoles.application()) {
            coverage = monthReads.ofMonth(app, MONTH);
        }
        assertThat(coverage.sources()).filteredOn(source -> source.state() == ReconciliationRunCoverage.SourceState.ACTIVE).hasSize(sources.declared().size());
        assertThat(coverage.lines()).as("one run a source, born in its acceptance").hasSize(sources.declared().size()).allSatisfy(line -> {
            assertThat(line.kind()).isEqualTo("BATCH");
            assertThat(line.status()).isEqualTo("COMPLETED");
            assertThat(line.repudiated()).isFalse();
        });

        CheckVerdict run = judged(MONTH);
        assertThat(run.status()).as("run; PASSED or DISCLOSED by what else stands, never FAILED by the month").isNotEqualTo(CloseCheckStatus.FAILED);
        assertThat(run.evidence()).contains("\"sourcesNotRun\":[]");
    }

    @Test
    @DisplayName("a repudiated batch's COMPLETED run counts for nothing, against the EXISTS on resolution, read on the application's own READ COMMITTED connection, as the close reads it; a new batch of the same source, date and identity has its own run, which counts")
    void aRepudiatedBatchsRunCountsForNothingAndTheNextBatchCounts() throws Exception {
        LocalDate day = REPUDIATED_MONTH.atDay(15);
        SettlementSourceDescriptor psp = sources.declared().stream().filter(source -> source.code().equals(PSP)).findFirst().orElseThrow();
        pull(psp, pspNil(day, "PSP-REM-19990415"));
        sweepAll();

        assertThat(pspLines(day)).singleElement().satisfies(line -> {
            assertThat(line.status()).isEqualTo("COMPLETED");
            assertThat(line.repudiated()).isFalse();
        });
        assertThat(sourcesNotRun(REPUDIATED_MONTH)).as("the month has PSP's run").doesNotContain(PSP);

        repudiate(batchOf(psp, day));
        assertThat(pspLines(day)).as("the run stands, its evidence withdrawn").singleElement().satisfies(line -> {
            assertThat(line.kind()).isEqualTo("BATCH");
            assertThat(line.status()).isEqualTo("COMPLETED");
            assertThat(line.repudiated()).isTrue();
        });
        assertThat(sourcesNotRun(REPUDIATED_MONTH)).as("a repudiated batch's COMPLETED run counts for nothing").contains(PSP);

        // Other bytes, the same identity: settlement's live uniques exclude REPUDIATED, and a gapless sequence gives the new run its own number.
        pull(psp, pspNil(day, "PSP-REM-199904150"));
        sweepAll();
        assertThat(pspLines(day)).as("the withdrawn run beside its successor, one date").hasSize(2)
                .anySatisfy(line -> assertThat(line.repudiated()).isTrue())
                .anySatisfy(line -> {
                    assertThat(line.repudiated()).isFalse();
                    assertThat(line.status()).isEqualTo("COMPLETED");
                });
        assertThat(sourcesNotRun(REPUDIATED_MONTH)).as("the way on: a new counting run").doesNotContain(PSP);
    }

    private void sweepAll() {
        for (int round = 0; round < sources.declared().size(); round++) {
            parsing.sweep();
            acceptance.sweep();
            matching.sweep();
        }
    }

    private List<ReconciliationRunCoverage.Line> pspLines(LocalDate day) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            ReconciliationRunCoverage.Coverage coverage = monthReads.ofMonth(app, YearMonth.from(day));
            UUID psp = coverage.sources().stream().filter(source -> source.code().equals(PSP)).findFirst().orElseThrow().id();
            return coverage.lines().stream().filter(line -> line.sourceId().equals(psp) && line.businessDate().equals(day)).toList();
        }
    }

    private String sourcesNotRun(YearMonth month) {
        String evidence = judged(month).evidence();
        int from = evidence.indexOf("\"sourcesNotRun\":[");
        return evidence.substring(from, evidence.indexOf(']', from));
    }

    private static UUID batchOf(SettlementSourceDescriptor source, LocalDate day) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement("SELECT b.id FROM settlement.batch b JOIN settlement.source s ON s.id = b.source_id"
                        + " WHERE s.code = ? AND b.business_date = ? AND b.status = 'ACCEPTED'")) {
            read.setString(1, source.code());
            read.setObject(2, day);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getObject(1, UUID.class);
            }
        }
    }

    /** {@code REPUDIATE_BATCH} under four eyes, through reconciliation's own machine; a nil batch has no recognition to reverse. */
    private void repudiate(UUID batch) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            UUID resolution = reconciliationTransactionRunner.inTransaction(uow -> repudiations.propose(uow, batch, ResolutionReasonCode.EVIDENCE_REPUDIATED, "the suite withdraws this evidence",
                    new Actor("quiet-controller-a", ActorType.EMPLOYEE), CorrelationContext.current().orElseThrow().correlationId()).resolutionId());
            reconciliationTransactionRunner.inTransaction(uow -> repudiations.approve(uow, resolution, new Actor("quiet-controller-b", ActorType.EMPLOYEE),
                    CorrelationContext.current().orElseThrow().correlationId()));
        }
    }

    /** A person's act, four eyes, the suite's as a deployment's; once, whichever case comes first. */
    private void ruleSetActive(UUID source, RuleSetProposal proposal) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'")) {
            read.setObject(1, source);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                if (row.getLong(1) > 0) {
                    return;
                }
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSetAdministration.propose(app, proposal, new Actor("quiet-controller-a", ActorType.EMPLOYEE), Instant.now(), CorrelationId.generate(IDS)).ruleSetId();
            ruleSetAdministration.approve(app, ruleSet, new Actor("quiet-controller-b", ActorType.EMPLOYEE), "the suite's first version", Instant.now(), CorrelationId.generate(IDS));
            app.commit();
        }
    }

    private static String pspNil(LocalDate day, String remittance) {
        return "H,SIM_PSP_CSV,1,PSPB-" + day + "-01,EUR," + day + "\nT,0,0.00," + remittance + "\n";
    }

    private CheckVerdict judged(YearMonth month) {
        Optional<CheckVerdict> verdict = closeChecks.judge(new CloseRuns.ClaimedCheck(CloseCheckId.next(IDS), CloseRunId.next(IDS), AccountingPeriodId.of(IDS.next()), month,
                CloseCheckCode.RECONCILIATION_DISCLOSURE, 1, IDS.next(), 0, false, CorrelationId.generate(IDS), Actor.SYSTEM));
        return verdict.orElseThrow();
    }

    private void pull(SettlementSourceDescriptor source, String content) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            FileReception.Result result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow, new FileReception.Delivery(source.code(), DeliveryChannel.PULL,
                    content.getBytes(StandardCharsets.UTF_8), Optional.empty(), Actor.SYSTEM, SettlementAuditAction.SETTLEMENT_FILE_UPLOADED, CorrelationContext.current().orElseThrow())));
            assertThat(result).as(source.code()).isInstanceOf(FileReception.Result.New.class);
        }
    }

    /** A nil report in the source's own format, one of its settled two-decimal currencies, its own remittance shape. */
    private static String nilReport(SettlementSourceDescriptor source) {
        String currency = source.settledCurrencies().stream().filter(code -> code.minorUnits() == 2).map(CurrencyCode::code).sorted().findFirst().orElse("EUR");
        String remittance = source.remittanceReferencePattern().map(pattern -> pattern.substring(0, pattern.indexOf('[')) + "19990331").orElse("");
        return switch (source.format()) {
            case SIM_PSP_CSV -> "H,SIM_PSP_CSV,1,PSPB-" + DAY + "-01," + currency + "," + DAY + "\nT,0,0.00," + remittance + "\n";
            case SIM_PAYOUT_CSV -> "H,SIM_PAYOUT_CSV,1,PAYDAY-" + DAY + "-" + currency + "," + currency + "," + DAY + "\nT,0,0.00," + remittance + "\n";
            case SIM_FX_CSV -> "H,SIM_FX_CSV,1,FXB-" + DAY + "-" + currency + "," + currency + "," + DAY + "\nT,0,0.00," + remittance + "\n";
            case SIM_CORRIDOR_CSV -> "H,SIM_CORRIDOR_CSV,1,XB-" + DAY + "-" + currency + "," + currency + "," + DAY + "\nT,0,0.00," + remittance + "\n";
            case SIM_SCHEME_JSON -> "{\"format\":\"SIM_SCHEME_JSON\",\"version\":1,\"cycle\":\"" + DAY + "-C1\",\"currency\":\"" + currency + "\",\"businessDate\":\"" + DAY + "\",\"remittanceReference\":\"" + remittance
                    + "\",\"entries\":[],\"net\":\"0.00\",\"entryCount\":0}";
            case SIM_STATEMENT_TAGGED -> ":20:SB-STMT-19990331-" + currency + "\n:25:SIMBANK-" + currency + "-01\n:28C:1\n:60F:C," + DAY.minusDays(1) + "," + currency + ",0.00\n:62F:C," + DAY + "," + currency + ",0.00\n";
        };
    }
}
