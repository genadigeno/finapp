package com.finapp.accounting.reporting;

import static com.finapp.accounting.testing.ReportBodies.isNull;
import static com.finapp.accounting.testing.ReportBodies.object;
import static com.finapp.accounting.testing.ReportBodies.objects;
import static com.finapp.accounting.testing.ReportBodies.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.BreakStatus;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseStatusReads;
import com.finapp.accounting.PastTheCutoffPostings;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunBasis;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two reports that are never a run (`P14-TSK-017`; {@code PHASE_14_PLAN.md} section 13.6): {@code RECONCILIATION_STATUS} -
 * the month's runs, the close's disclosure, which past-the-cutoff entry is which (`P14-TSK-011`'s review, M-2) - and {@code
 * CLOSE_STATUS} - the run's state on the stalled bound, the checks, the breaks and the approved set (`P14-TSK-015`'s review, M-4).
 */
@DisplayName("the operational definitions (P14-TSK-017)")
class OperationalDefinitionsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            OperationalDefinitionsTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("a report reads only through its ports");
            });
    private static final YearMonth MONTH = YearMonth.of(2097, 3);
    private static final Instant LOCKED_AT = Instant.parse("2097-04-05T09:00:00Z");
    private static final CloseRunStall STALL = new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.STANDARD_POLLS);
    private static final UUID SOURCE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SET = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private final AccountingPeriodId periodId = AccountingPeriodId.next(IDS);

    // ------------------------------------------------------------------ RECONCILIATION_STATUS

    @Test
    @DisplayName("RECONCILIATION_STATUS: operational, its statement written, as of the database's instant - the month's runs per source, kind,"
            + " status and date, the close's disclosure as it reads it, and M-2's split")
    void reconciliationStatus() {
        ReconciliationStatusDefinition definition = new ReconciliationStatusDefinition(
                (snapshot, month) -> disclosure(),
                (snapshot, month) -> new ReconciliationRunCoverage.Coverage(month, List.of(
                        new ReconciliationRunCoverage.Line(SOURCE, "BATCH", "COMPLETED", MONTH.atDay(30), false, 2),
                        new ReconciliationRunCoverage.Line(SOURCE, "REPROCESS", "OPEN", MONTH.atDay(31), false, 1)),
                        List.of(new ReconciliationRunCoverage.Source(SOURCE, "simulated-psp.settlement", ReconciliationRunCoverage.SourceState.ACTIVE))));
        assertThat(definition.code()).isEqualTo(ReportKind.RECONCILIATION_STATUS.name());
        assertThat(definition.version()).as("P14-TSK-025: each source's days and whether the month requires it").isEqualTo(3);
        assertThat(definition.operational()).isTrue();
        assertThat(definition.takesValuationRateSet()).isFalse();

        Instant asOf = LOCKED_AT.plus(Duration.ofDays(3));
        CanonicalJson.Obj body = definition.generate(SNAPSHOT, inputs(asOf));
        assertThat(text(body, "asOf")).isEqualTo(asOf.toString());
        assertThat(CanonicalJson.text(body)).contains(CanonicalJson.text(definition.statement().asValue()));

        CanonicalJson.Obj coverage = object(body, "coverage");
        assertThat(text(coverage, "batchRuns")).isEqualTo("2");
        assertThat(coverage.members()).as("I-4: a count, and no verdict on the month").doesNotContainKey("noRun");
        assertThat(objects(coverage, "runs")).hasSize(2);
        assertThat(text(objects(coverage, "runs").get(0), "sourceId")).isEqualTo(SOURCE.toString());
        CanonicalJson.Obj source = objects(coverage, "sources").get(0);
        assertThat(text(source, "admittedOn")).as("P14-TSK-025: the source's days, beside whether the month requires it").isEqualTo("1970-01-01");
        assertThat(text(source, "ruleSetActiveOn")).isEqualTo("1970-01-01");
        assertThat(CanonicalJson.text(source)).contains("\"retiredOn\":null").contains("\"required\":true");
        assertThat(CanonicalJson.text(definition.statement().asValue())).contains("whether the month requires its run");

        assertThat(text(object(body, "disclosure"), "cutoff")).isEqualTo("2097-03-31");

        CanonicalJson.Obj batches = object(body, "disclosure", "pastTheCutoff", "settlement-batch");
        assertThat(text(batches, "entries")).isEqualTo("7");
        assertThat(text(batches, "floored")).as("a lock covered the date they stated").isEqualTo("2");
        assertThat(text(batches, "unrecorded")).as("no audit record: cannot be told from floored").isEqualTo("1");
        assertThat(text(batches, "ownValueDate")).as("accepted after the cutoff, valued at the business date: ordinary").isEqualTo("4");
        assertThat(body.members()).as("the owner's (b): one read, the disclosure's own, so the two cannot disagree").doesNotContainKey("pastTheCutoff");
    }

    @Test
    @DisplayName("I-4: the coverage -012 reads counts and does not judge - a REPROCESS is no accepted batch's run, a BLOCKED or OPEN one is counted,"
            + " and what makes a month run is -012's and the owner's; a split's floored are among its entries; M-3: the rest is said to be inferred")
    void theCoverage() {
        assertThat(new ReconciliationRunCoverage.Coverage(MONTH, List.of(), List.of()).batchRuns()).isZero();
        assertThat(new ReconciliationRunCoverage.Coverage(MONTH, List.of(
                new ReconciliationRunCoverage.Line(SOURCE, "REPROCESS", "OPEN", MONTH.atDay(1), false, 3)), List.of()).batchRuns()).isZero();
        ReconciliationRunCoverage.Coverage standing = new ReconciliationRunCoverage.Coverage(MONTH, List.of(
                new ReconciliationRunCoverage.Line(SOURCE, "BATCH", "BLOCKED", MONTH.atDay(1), false, 1),
                new ReconciliationRunCoverage.Line(SOURCE, "BATCH", "OPEN", MONTH.atDay(2), false, 2)), List.of());
        assertThat(standing.batchRuns()).isEqualTo(3);
        assertThat(CanonicalJson.text(standing.asValue())).doesNotContain("noRun");
        assertThat(CanonicalJson.text(new ReconciliationStatusDefinition((snapshot, period) -> {
            throw new AssertionError("the statement is read, not the disclosure");
        }, (snapshot, month) -> standing).statement().asValue()))
                .contains("other than LND-2").contains("a record present with no mark").doesNotContain("per floored rule's namespace");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PastTheCutoffPostings.Split(1, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PastTheCutoffPostings.Split(1, 1, 1))
                .as("the floored and the unrecorded are among the entries").isInstanceOf(IllegalArgumentException.class);
        assertThat(new PastTheCutoffPostings.Split(5, 1, 1).countsAsFloored()).isEqualTo(2);
        assertThat(new PastTheCutoffPostings.Split(5, 1, 1).ownValueDate()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ CLOSE_STATUS

    @Test
    @DisplayName("CLOSE_STATUS: operational, its statement written; the checks with their codes; the breaks by status, every status named;"
            + " the sets per status - and no figure, no person, no evidence")
    void closeStatus() {
        CloseStatusDefinition definition = new CloseStatusDefinition((snapshot, id) -> reading(PeriodStatus.LOCKED,
                Optional.of(LOCKED_AT), Optional.of(run(true, Optional.empty())), Optional.empty()), STALL);
        assertThat(definition.code()).isEqualTo(ReportKind.CLOSE_STATUS.name());
        assertThat(definition.operational()).isTrue();
        assertThat(definition.takesValuationRateSet()).isFalse();

        CanonicalJson.Obj body = definition.generate(SNAPSHOT, inputs(LOCKED_AT.plusSeconds(60)));
        assertThat(text(body, "period")).isEqualTo("2097-03");
        assertThat(text(body, "status")).isEqualTo("LOCKED");
        assertThat(text(body, "revision")).isEqualTo("1");
        assertThat(text(body, "lockedAt")).isEqualTo(LOCKED_AT.toString());
        assertThat(text(body, "pinnedMappingVersion")).isEqualTo("4");
        assertThat(text(object(body, "breaks"), BreakStatus.OPEN.name())).isEqualTo("2");
        assertThat(text(object(body, "breaks"), BreakStatus.RESOLVED.name())).as("a status with none is named at zero").isEqualTo("0");
        assertThat(text(object(body, "valuationRateSets"), "PROPOSED")).isEqualTo("1");
        CanonicalJson.Obj check = objects(object(body, "run"), "checks").get(0);
        assertThat(text(check, "check")).isEqualTo(CloseCheckCode.GL_EQUALS_LEDGER.name());
        assertThat(text(check, "status")).isEqualTo("FAILED");
        assertThat(text(check, "attempts")).isEqualTo("2");
        assertThat(text(check, "judgedBy")).isEqualTo("gl-mapping:v4");
        assertThat(((CanonicalJson.Arr) com.finapp.accounting.testing.ReportBodies.value(check, "failures")).elements())
                .containsExactly(new CanonicalJson.Text("MAPPING_GAP"));
        assertThat(CanonicalJson.text(body)).doesNotContain("\"minor\"").doesNotContain("evidence");
        assertThat(CanonicalJson.text(body)).contains(CanonicalJson.text(definition.statement().asValue()));
    }

    @Test
    @DisplayName("M-4: the period's approved set is named by id, as of its instant, with no rate - and null when it has none")
    void theApprovedSet() {
        Instant decided = LOCKED_AT.plusSeconds(600);
        CanonicalJson.Obj body = new CloseStatusDefinition((snapshot, id) -> reading(PeriodStatus.LOCKED, Optional.of(LOCKED_AT),
                Optional.empty(), Optional.of(new CloseStatusReads.ApprovedSet(SET, Instant.parse("2097-04-01T00:00:00Z"), 3,
                        Optional.of(decided)))), STALL).generate(SNAPSHOT, inputs(LOCKED_AT));
        CanonicalJson.Obj approved = object(body, "approvedValuationRateSet");
        assertThat(text(approved, "id")).isEqualTo(SET.toString());
        assertThat(text(approved, "rateCount")).isEqualTo("3");
        assertThat(text(approved, "decidedAt")).isEqualTo(decided.toString());
        assertThat(approved.members().keySet()).containsExactlyInAnyOrder("id", "asOf", "rateCount", "decidedAt");

        CanonicalJson.Obj none = new CloseStatusDefinition((snapshot, id) -> reading(PeriodStatus.OPEN, Optional.empty(),
                Optional.empty(), Optional.empty()), STALL).generate(SNAPSHOT, inputs(LOCKED_AT));
        assertThat(isNull(none, "approvedValuationRateSet")).isTrue();
        assertThat(isNull(none, "run")).isTrue();
        assertThat(isNull(none, "lockedAt")).isTrue();
    }

    @Test
    @DisplayName("the run's state: OPEN is no lock; LOCKED with no run is the ordinary gap up to the bound, STALLED from it, on the database's"
            + " instant; a run answering this lock RUNNING then CONCLUDED; a run an unlock left behind answers nothing")
    void theRunsState() {
        CloseStatusDefinition definition = new CloseStatusDefinition((snapshot, id) -> {
            throw new AssertionError("the state reads the reading it is given");
        }, STALL);
        Duration bound = STALL.after();
        assertThat(bound).isEqualTo(Duration.ofMinutes(5));
        assertThat(definition.state(reading(PeriodStatus.OPEN, Optional.empty(), Optional.empty(), Optional.empty()), LOCKED_AT))
                .isEqualTo(CloseStatusDefinition.RunState.NOT_LOCKED);

        CloseStatusReads.Reading noRun = reading(PeriodStatus.LOCKED, Optional.of(LOCKED_AT), Optional.empty(), Optional.empty());
        assertThat(definition.state(noRun, LOCKED_AT)).isEqualTo(CloseStatusDefinition.RunState.AWAITING_FIRST_POLL);
        assertThat(definition.state(noRun, LOCKED_AT.plus(bound).minusMillis(1)))
                .as("one millisecond inside the bound").isEqualTo(CloseStatusDefinition.RunState.AWAITING_FIRST_POLL);
        assertThat(definition.state(noRun, LOCKED_AT.plus(bound))).isEqualTo(CloseStatusDefinition.RunState.STALLED);

        assertThat(definition.state(reading(PeriodStatus.LOCKED, Optional.of(LOCKED_AT), Optional.of(run(true, Optional.empty())),
                Optional.empty()), LOCKED_AT.plus(bound))).isEqualTo(CloseStatusDefinition.RunState.RUNNING);
        assertThat(definition.state(reading(PeriodStatus.CLOSED, Optional.of(LOCKED_AT),
                Optional.of(run(true, Optional.of(LOCKED_AT.plusSeconds(90)))), Optional.empty()), LOCKED_AT.plus(bound)))
                .isEqualTo(CloseStatusDefinition.RunState.CONCLUDED);
        assertThat(definition.state(reading(PeriodStatus.LOCKED, Optional.of(LOCKED_AT), Optional.of(run(false,
                Optional.of(LOCKED_AT.minusSeconds(90)))), Optional.empty()), LOCKED_AT.plus(bound)))
                .as("the old run of an unlocked lock: the present lock has none").isEqualTo(CloseStatusDefinition.RunState.STALLED);
    }

    // -----------------------------------------------------------------

    private ReportInputs inputs(Instant asOf) {
        return new ReportInputs(periodId, MONTH, 1, RunBasis.PROVISIONAL, Optional.empty(), Optional.empty(), Optional.empty(), asOf,
                RunParameters.none());
    }

    private static CloseStatusReads.Run run(boolean current, Optional<Instant> concluded) {
        return new CloseStatusReads.Run(LOCKED_AT, current, false, LOCKED_AT.plusSeconds(30), concluded, List.of(
                new CloseStatusReads.Check(CloseCheckCode.GL_EQUALS_LEDGER, 2, CloseCheckStatus.FAILED, List.of("MAPPING_GAP"),
                        Optional.of("gl-mapping:v4"), Optional.of(LOCKED_AT.plusSeconds(45))),
                new CloseStatusReads.Check(CloseCheckCode.OPEN_ACCOUNTING_BREAKS, 1, CloseCheckStatus.PENDING, List.of(),
                        Optional.empty(), Optional.empty())));
    }

    private static CloseStatusReads.Reading reading(PeriodStatus status, Optional<Instant> lockedAt,
            Optional<CloseStatusReads.Run> run, Optional<CloseStatusReads.ApprovedSet> approved) {
        return new CloseStatusReads.Reading(MONTH, status, 1, lockedAt, lockedAt.map(ignored -> 4), run,
                Map.of(BreakStatus.OPEN, 2L), approved, new TreeMap<>(Map.of("PROPOSED", 1L, "APPROVED", 0L, "REJECTED", 0L)), List.of(SOURCE));
    }

    private static ReconciliationDisclosure.Disclosure disclosure() {
        TreeMap<String, Long> ageing = new TreeMap<>(Map.of("0-2", 1L));
        return new ReconciliationDisclosure.Disclosure(LocalDate.of(2097, 3, 31), Instant.parse("2097-04-01T00:00:00Z"),
                List.of(new ReconciliationDisclosure.OpenBreaks("EUR", 2, 1, BigInteger.TEN, ageing)), 0, List.of(), 0,
                new TreeMap<>(Map.of("settlement-batch", new PastTheCutoffPostings.Split(7, 2, 1))));
    }
}
