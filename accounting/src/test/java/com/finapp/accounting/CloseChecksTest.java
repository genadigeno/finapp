package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The nine checks' judges (`P14-TSK-011`; {@code PHASE_14_PLAN.md} section 13.5; {@code INV-ACC-05}, {@code
 * INV-ACC-09}): each passes only when it read everything and found nothing - a false verdict, an exception, an
 * incomplete proof, a missing reading and an empty set each {@code FAILED}; {@code DISCLOSED} only as the check admits
 * it; a {@code RECURRED} outcome neither; {@code ACCRUALS_COMPLETE} never {@code PASSED} on a period short of interest.
 * Hermetic: every port a double, every snapshot one stand-in.
 */
@DisplayName("the close checks: never a false pass (P14-TSK-011)")
class CloseChecksTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final LocalDate MARCH_END = MARCH.atEndOfMonth();
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final String BOOM = "a figure the log must not carry: 12345";

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryValuationRateSetStore setStore = new InMemoryValuationRateSetStore().watching(periods);
    private final AccountingPeriodId period = AccountingPeriodId.next(IDS);

    private CloseChecks.BreakChecks breakChecks = (month, check, actor, correlation) -> clean(check);
    private FinalTrialBalance trialBalance = (id, actor, correlation) -> verdict(true, true, true, true, true);
    private List<AccrualCompleteness.Lagging> behind = List.of();
    private List<AccrualCompleteness.Floored> floored = List.of();
    private Function<SourceProofs.Source, List<SourceProofs.Reading>> proofs = source -> List.of(
            new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, true, List.of()));
    private ReconciliationDisclosure disclosure = (snapshot, month) -> nothing();
    private int snapshotsOpened;

    private final AccrualCompleteness accruals = new AccrualCompleteness() {
        @Override
        public List<Lagging> lagging(Connection unitOfWork) {
            return behind;
        }

        @Override
        public List<Floored> flooredPast(Connection unitOfWork, LocalDate periodEnd) {
            assertThat(periodEnd).isEqualTo(MARCH_END);
            return floored;
        }
    };

    @BeforeEach
    void marchIsLockedAndPinned() {
        periods.insertIfAbsent(SNAPSHOT, new AccountingPeriodStore.NewPeriod(period, MARCH, correlation()));
        periods.markLocked(SNAPSHOT, period, 1, ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(
                GlMappingVersionId.next(IDS), 1)));
    }

    // ------------------------------------------------------------------ the trial balance

    @Test
    @DisplayName("LEDGER_TRIAL_BALANCE is the FINAL run's verdict with its legs, naming the definition's version and the run")
    void theTrialBalancePasses() {
        CheckVerdict verdict = judge(CloseCheckCode.LEDGER_TRIAL_BALANCE).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(verdict.judgedBy()).isEqualTo("report:TRIAL_BALANCE:v1");
        assertThat(verdict.evidence()).contains("\"run\":\"run-1\"", "\"contentSha256\":\"abc\"", "\"revision\":\"2\"");
    }

    @Test
    @DisplayName("LEDGER_TRIAL_BALANCE fails on passes false, and on any leg false with passes true - I-1's legs")
    void aFalseVerdictOrLegFails() {
        for (boolean[] flags : List.of(new boolean[] {false, true, true, true}, new boolean[] {true, false, true, true},
                new boolean[] {true, true, false, true}, new boolean[] {true, true, true, false})) {
            trialBalance = (id, actor, correlation) -> verdict(flags[0], flags[1], flags[2], flags[3], true);
            CheckVerdict verdict = judge(CloseCheckCode.LEDGER_TRIAL_BALANCE).orElseThrow();
            assertThat(verdict.status()).isEqualTo(CloseCheckStatus.FAILED);
            assertThat(verdict.failures()).containsExactly(CloseChecks.TRIAL_BALANCE_FALSE);
        }
    }

    @Test
    @DisplayName("LEDGER_TRIAL_BALANCE: a run another worker generates is not an answer yet - deferred, not failed; a run unavailable is"
            + " FAILED with its code; a read that throws FAILED")
    void aRunNotReadableIsNeverAPass() {
        trialBalance = (id, actor, correlation) -> new FinalTrialBalance.InProgress();
        assertThat(judge(CloseCheckCode.LEDGER_TRIAL_BALANCE)).isEmpty();

        trialBalance = (id, actor, correlation) -> new FinalTrialBalance.Unavailable("REPORT_RUN_FAILED:DEFINITION_FAILED");
        CheckVerdict unavailable = judge(CloseCheckCode.LEDGER_TRIAL_BALANCE).orElseThrow();
        assertThat(unavailable.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(unavailable.failures()).containsExactly("REPORT_RUN_FAILED:DEFINITION_FAILED");

        trialBalance = (id, actor, correlation) -> {
            throw new IllegalStateException(BOOM);
        };
        anExceptionIsFailedNeverPassed(CloseCheckCode.LEDGER_TRIAL_BALANCE);
    }

    // ------------------------------------------------------------------ GL = ledger

    @Test
    @DisplayName("GL_EQUALS_LEDGER passes only with the run clean, a pin and every line mapped")
    void theGlPasses() {
        CheckVerdict verdict = judge(CloseCheckCode.GL_EQUALS_LEDGER).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(verdict.evidence()).contains("\"pinned\":true", "\"fullyMapped\":true");
    }

    @Test
    @DisplayName("GL_EQUALS_LEDGER: a period pinning no version fails though the run - which falls back to the version in force -"
            + " passed (P14-TSK-009's M-6)")
    void aPeriodPinningNoVersionFails() {
        AccountingPeriodId unpinned = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(SNAPSHOT, new AccountingPeriodStore.NewPeriod(unpinned, MARCH.plusMonths(1), correlation()));
        CheckVerdict verdict = checks().judge(claimed(CloseCheckCode.GL_EQUALS_LEDGER, unpinned)).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(verdict.failures()).containsExactly(CloseChecks.NO_PINNED_VERSION);
    }

    @Test
    @DisplayName("GL_EQUALS_LEDGER: fullyMapped false fails it, the trial balance's own verdict true or not; a trial balance unavailable"
            + " fails it; one in progress defers before the run")
    void anUnmappedLineFails() {
        trialBalance = (id, actor, correlation) -> verdict(true, true, true, true, false);
        assertThat(judge(CloseCheckCode.GL_EQUALS_LEDGER).orElseThrow().failures()).containsExactly(CloseChecks.NOT_FULLY_MAPPED);

        trialBalance = (id, actor, correlation) -> new FinalTrialBalance.Unavailable("REPORT_RUN_NOT_FINAL");
        assertThat(judge(CloseCheckCode.GL_EQUALS_LEDGER).orElseThrow().failures()).containsExactly("REPORT_RUN_NOT_FINAL");

        int[] runs = {0};
        breakChecks = (month, check, actor, correlation) -> {
            runs[0]++;
            return clean(check);
        };
        trialBalance = (id, actor, correlation) -> new FinalTrialBalance.InProgress();
        assertThat(judge(CloseCheckCode.GL_EQUALS_LEDGER)).isEmpty();
        assertThat(runs[0]).as("deferred before anything is judged").isZero();
    }

    @Test
    @DisplayName("GL_EQUALS_LEDGER is PASSED or it blocks: every finding acknowledged still FAILS it")
    void theGlIsNeverDisclosed() {
        breakChecks = (month, check, actor, correlation) -> outcome(check, 2, 0, 0, 2, 0);
        CheckVerdict verdict = judge(CloseCheckCode.GL_EQUALS_LEDGER).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.FAILED);
    }

    // ------------------------------------------------------------------ the raising checks

    @Test
    @DisplayName("a raising check: clean PASSED; an open or new finding FAILED; every finding ACKNOWLEDGED, nothing else failing, DISCLOSED")
    void theRaisingChecks() {
        for (CloseCheckCode check : List.of(CloseCheckCode.RULE_CONFORMANCE, CloseCheckCode.SOURCE_COMPLETENESS)) {
            assertThat(judge(check).orElseThrow().status()).isEqualTo(CloseCheckStatus.PASSED);

            breakChecks = (month, code, actor, correlation) -> outcome(code, 1, 1, 0, 0, 0);
            assertThat(judge(check).orElseThrow().status()).as("raised").isEqualTo(CloseCheckStatus.FAILED);

            breakChecks = (month, code, actor, correlation) -> outcome(code, 2, 0, 1, 1, 0);
            assertThat(judge(check).orElseThrow().status()).as("one still open").isEqualTo(CloseCheckStatus.FAILED);

            breakChecks = (month, code, actor, correlation) -> outcome(code, 2, 0, 0, 2, 0);
            CheckVerdict disclosed = judge(check).orElseThrow();
            assertThat(disclosed.status()).as("all acknowledged").isEqualTo(CloseCheckStatus.DISCLOSED);
            assertThat(disclosed.evidence()).contains("\"knownAcknowledged\":\"2\"");

            breakChecks = (month, code, actor, correlation) -> clean(code);
        }
    }

    @Test
    @DisplayName("RECURRED never passes and never discloses: a resolved break's discrepancy still present is FAILED, the recurrence on the"
            + " record - the default until the owner rules on ADR-0117's question")
    void aRecurrenceNeverPassesNorDiscloses() {
        breakChecks = (month, code, actor, correlation) -> outcome(code, 1, 0, 0, 0, 1);
        CheckVerdict alone = judge(CloseCheckCode.SOURCE_COMPLETENESS).orElseThrow();
        assertThat(alone.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(alone.failures()).contains(CheckOutcome.RECURRED);
        assertThat(alone.evidence()).contains("\"recurred\":\"1\"");

        breakChecks = (month, code, actor, correlation) -> outcome(code, 3, 0, 0, 2, 1);
        assertThat(judge(CloseCheckCode.RULE_CONFORMANCE).orElseThrow().status())
                .as("beside acknowledged findings").isEqualTo(CloseCheckStatus.FAILED);
    }

    @Test
    @DisplayName("a raising check's other failures keep it FAILED beside acknowledged findings: unavailable, capped, no mapping")
    void otherFailuresNeverDisclose() {
        for (String failure : List.of(CheckOutcome.UNAVAILABLE + "POSITION", CheckOutcome.FINDINGS_CAPPED, CheckOutcome.NO_MAPPING)) {
            breakChecks = (month, code, actor, correlation) -> new CheckOutcome(month, code, CheckOutcome.Result.FAILED, "source-proofs:v1",
                    1, 0, 0, 1, 0, List.of(), List.of(CheckOutcome.DISCREPANCIES, failure));
            CheckVerdict verdict = judge(CloseCheckCode.SOURCE_COMPLETENESS).orElseThrow();
            assertThat(verdict.status()).as(failure).isEqualTo(CloseCheckStatus.FAILED);
            assertThat(verdict.failures()).contains(failure);
        }
    }

    @Test
    @DisplayName("a source unavailable is FAILED; a raise that throws FAILED - no path from an exception to a pass, for any check")
    void anExceptionIsFailedNeverPassed() {
        breakChecks = (month, code, actor, correlation) -> {
            throw new IllegalStateException(BOOM);
        };
        anExceptionIsFailedNeverPassed(CloseCheckCode.RULE_CONFORMANCE);
        anExceptionIsFailedNeverPassed(CloseCheckCode.SOURCE_COMPLETENESS);
        anExceptionIsFailedNeverPassed(CloseCheckCode.GL_EQUALS_LEDGER);

        proofs = source -> {
            throw new IllegalStateException(BOOM);
        };
        anExceptionIsFailedNeverPassed(CloseCheckCode.PROJECTION_VERIFIED);

        disclosure = (snapshot, month) -> {
            throw new IllegalStateException(BOOM);
        };
        anExceptionIsFailedNeverPassed(CloseCheckCode.RECONCILIATION_DISCLOSURE);
    }

    // ------------------------------------------------------------------ accruals

    @Test
    @DisplayName("ACCRUALS_COMPLETE: nothing behind and nothing floored PASSED, on one snapshot")
    void accrualsCaughtUpPass() {
        CheckVerdict verdict = judge(CloseCheckCode.ACCRUALS_COMPLETE).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(snapshotsOpened).as("both legs on one snapshot: never a third state").isEqualTo(1);
    }

    @Test
    @DisplayName("ACCRUALS_COMPLETE: a missing accrual blocks - FAILED, naming the accruer, the currency and the date; never disclosed")
    void aMissingAccrualBlocks() {
        behind = List.of(new AccrualCompleteness.Lagging(AccrualCompleteness.Accruer.LENDING, "EUR", MARCH.atDay(30)));
        CheckVerdict verdict = judge(CloseCheckCode.ACCRUALS_COMPLETE).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(verdict.failures()).containsExactly(CloseChecks.ACCRUALS_BEHIND);
        assertThat(verdict.evidence()).contains("\"accruer\":\"LENDING\"", "\"oldestUnaccrued\":\"2097-03-30\"");

        behind = List.of(new AccrualCompleteness.Lagging(AccrualCompleteness.Accruer.BNPL, "EUR", MARCH_END.plusDays(1)));
        assertThat(judge(CloseCheckCode.ACCRUALS_COMPLETE).orElseThrow().status())
                .as("behind only after the period's end: not this period's").isEqualTo(CloseCheckStatus.PASSED);
    }

    @Test
    @DisplayName("ACCRUALS_COMPLETE after the lock (P14-TSK-006's I-1): the catch-up floored past the cutoff leaves nothing behind and the"
            + " period short of interest - DISCLOSED with the figure, never PASSED")
    void aFlooredAccrualIsDisclosedNeverPassed() {
        floored = List.of(new AccrualCompleteness.Floored(AccrualCompleteness.Accruer.LENDING, "EUR", 2, 3, BigInteger.valueOf(4_250), 0,
                BigInteger.ZERO));
        CheckVerdict verdict = judge(CloseCheckCode.ACCRUALS_COMPLETE).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.DISCLOSED);
        assertThat(verdict.evidence()).contains("\"interestMinor\":\"4250\"", "\"entries\":\"3\"");

        behind = List.of(new AccrualCompleteness.Lagging(AccrualCompleteness.Accruer.LENDING, "USD", MARCH.atDay(2)));
        assertThat(judge(CloseCheckCode.ACCRUALS_COMPLETE).orElseThrow().status())
                .as("and behind besides: blocked").isEqualTo(CloseCheckStatus.FAILED);
    }

    @Test
    @DisplayName("C-1: a BNPL sweep's catch-up past the cutoff reads nothing behind and the period short - DISCLOSED with the figures, never"
            + " PASSED, the structural last day apart from anything older")
    void aBnplCatchUpIsDisclosedTheLastDayApart() {
        floored = List.of(new AccrualCompleteness.Floored(AccrualCompleteness.Accruer.BNPL, "EUR", 2, 7, BigInteger.valueOf(70), 1,
                BigInteger.TEN));
        CheckVerdict verdict = judge(CloseCheckCode.ACCRUALS_COMPLETE).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.DISCLOSED);
        assertThat(verdict.evidence()).contains("\"accruer\":\"BNPL\"", "\"entries\":\"7\"", "\"interestMinor\":\"70\"",
                "\"lastDay\":{\"entries\":\"1\",\"interestMinor\":\"10\"}", "\"older\":{\"entries\":\"6\",\"interestMinor\":\"60\"}");

        assertThatThrownBy(() -> new AccrualCompleteness.Floored(AccrualCompleteness.Accruer.BNPL, "EUR", 2, 1, BigInteger.TEN, 2, BigInteger.ONE))
                .as("more entries on the last day than in all").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccrualCompleteness.Floored(AccrualCompleteness.Accruer.BNPL, "EUR", 2, 1, BigInteger.ONE, 1, BigInteger.TEN))
                .as("more interest on the last day than in all").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("I-1: a finding an ACKNOWLEDGED break holds that now reads differently is FAILED ACKNOWLEDGED_CHANGED, its subject recorded,"
            + " however many findings are acknowledged - never DISCLOSED")
    void anAcknowledgedFindingThatChangedIsNeverDisclosed() {
        CheckOutcome changed = new CheckOutcome(MARCH, CloseCheckCode.RULE_CONFORMANCE, CheckOutcome.Result.FAILED, "posting-rules:v2", 2, 0, 0,
                2, 0, List.of("namespace:POSTING probe:"), List.of(CheckOutcome.DISCREPANCIES, CheckOutcome.ACKNOWLEDGED_CHANGED));
        CheckVerdict verdict = CloseChecks.verdictOf(changed);
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(verdict.failures()).containsExactly(CheckOutcome.DISCREPANCIES, CheckOutcome.ACKNOWLEDGED_CHANGED);
        assertThat(verdict.evidence()).contains("\"acknowledgedChanged\":\"1\"", "\"changedSubjects\":[\"namespace:POSTING probe:\"]");

        assertThatThrownBy(() -> new CheckOutcome(MARCH, CloseCheckCode.RULE_CONFORMANCE, CheckOutcome.Result.FAILED, "posting-rules:v2", 1, 0, 0,
                1, 0, List.of("shape:1"), List.of(CheckOutcome.DISCREPANCIES))).as("a changed subject with no failure")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckOutcome(MARCH, CloseCheckCode.RULE_CONFORMANCE, CheckOutcome.Result.FAILED, "posting-rules:v2", 1, 0, 0,
                1, 0, List.of(), List.of(CheckOutcome.DISCREPANCIES, CheckOutcome.ACKNOWLEDGED_CHANGED))).as("a failure with no subject")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckOutcome(MARCH, CloseCheckCode.RULE_CONFORMANCE, CheckOutcome.Result.FAILED, "posting-rules:v2", 1, 0, 1,
                0, 0, List.of("shape:1"), List.of(CheckOutcome.DISCREPANCIES, CheckOutcome.ACKNOWLEDGED_CHANGED))).as("nothing acknowledged")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ projections

    @Test
    @DisplayName("PROJECTION_VERIFIED: a clean reading PASSED; incomplete FAILED; a drift FAILED, subjects bounded, count whole; a reading"
            + " missing FAILED - an empty read is no pass")
    void theProjections() {
        assertThat(judge(CloseCheckCode.PROJECTION_VERIFIED).orElseThrow().status()).isEqualTo(CloseCheckStatus.PASSED);

        proofs = source -> List.of(new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, false, List.of()));
        assertThat(judge(CloseCheckCode.PROJECTION_VERIFIED).orElseThrow().failures())
                .containsExactly(CloseChecks.PROJECTION_INCOMPLETE);

        List<SourceProofs.Discrepancy> many = new ArrayList<>();
        for (int i = 0; i < CloseChecks.MAX_SUBJECTS + 5; i++) {
            many.add(new SourceProofs.Discrepancy("account-" + i, "drift"));
        }
        proofs = source -> List.of(new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, true, many));
        CheckVerdict drift = judge(CloseCheckCode.PROJECTION_VERIFIED).orElseThrow();
        assertThat(drift.failures()).containsExactly(CloseChecks.PROJECTION_DRIFT);
        assertThat(drift.evidence()).contains("\"drifting\":\"25\"", "account-19").doesNotContain("account-20");

        proofs = source -> List.of();
        CheckVerdict missing = judge(CloseCheckCode.PROJECTION_VERIFIED).orElseThrow();
        assertThat(missing.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(missing.failures()).containsExactly(CheckOutcome.UNAVAILABLE + "PROJECTION");
    }

    @Test
    @DisplayName("PROJECTION_VERIFIED reads the projection source only, never raising a break")
    void theProjectionReadIsTheProofsOwn() {
        List<SourceProofs.Source> asked = new ArrayList<>();
        Function<SourceProofs.Source, List<SourceProofs.Reading>> clean = proofs;
        proofs = source -> {
            asked.add(source);
            return clean.apply(source);
        };
        breakChecks = (month, code, actor, correlation) -> {
            throw new AssertionError("no break is raised by this check");
        };
        assertThat(judge(CloseCheckCode.PROJECTION_VERIFIED).orElseThrow().status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(asked).containsExactly(SourceProofs.Source.PROJECTION);
    }

    // ------------------------------------------------------------------ the disclosure

    @Test
    @DisplayName("RECONCILIATION_DISCLOSURE: nothing to disclose PASSED; anything open DISCLOSED with its figures per currency - never"
            + " netted, never omitted (INV-ACC-05)")
    void suspenseIsDisclosed() {
        assertThat(judge(CloseCheckCode.RECONCILIATION_DISCLOSURE).orElseThrow().status()).isEqualTo(CloseCheckStatus.PASSED);

        disclosure = (snapshot, month) -> new ReconciliationDisclosure.Disclosure(MARCH_END, Instant.parse("2097-04-01T00:00:00Z"),
                List.of(new ReconciliationDisclosure.OpenBreaks("EUR", 2, 2, BigInteger.valueOf(5_000), new TreeMap<>())), 1,
                List.of(new ReconciliationDisclosure.Suspense("EUR", 2, BigInteger.valueOf(700), BigInteger.valueOf(1_000), 1,
                        BigInteger.valueOf(1_000), 1, BigInteger.valueOf(700), new TreeMap<>(), Optional.of(MARCH.atDay(3)))),
                0, new TreeMap<>());
        CheckVerdict verdict = judge(CloseCheckCode.RECONCILIATION_DISCLOSURE).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.DISCLOSED);
        assertThat(verdict.judgedBy()).isEqualTo(ReconciliationDisclosure.JUDGED_BY);
        assertThat(verdict.evidence()).contains("\"creditUnreleasedMinor\":\"1000\"", "\"debitUnreleasedMinor\":\"700\"",
                "\"valueAtIssueMinor\":\"5000\"", "\"raisedSinceStillOpen\":\"1\"", "\"cutoff\":\"2097-03-31\"");
    }

    @Test
    @DisplayName("RECONCILIATION_DISCLOSURE: each thing alone keeps it from passing - unowned suspense (M-10), a posting past the cutoff"
            + " (M-2), a break raised since, a ledger balance with no item")
    void anythingOpenDisclosesAlone() {
        TreeMap<String, Long> posted = new TreeMap<>();
        posted.put("settlement-batch", 1L);
        List<ReconciliationDisclosure.Disclosure> each = List.of(
                new ReconciliationDisclosure.Disclosure(MARCH_END, Instant.EPOCH, List.of(), 0, List.of(), 1, new TreeMap<>()),
                new ReconciliationDisclosure.Disclosure(MARCH_END, Instant.EPOCH, List.of(), 0, List.of(), 0, posted),
                new ReconciliationDisclosure.Disclosure(MARCH_END, Instant.EPOCH, List.of(), 1, List.of(), 0, new TreeMap<>()),
                new ReconciliationDisclosure.Disclosure(MARCH_END, Instant.EPOCH, List.of(), 0, List.of(new ReconciliationDisclosure.Suspense(
                        "EUR", 2, BigInteger.ONE, BigInteger.ZERO, 0, BigInteger.ZERO, 0, BigInteger.ZERO, new TreeMap<>(),
                        Optional.empty())), 0, new TreeMap<>()));
        for (ReconciliationDisclosure.Disclosure read : each) {
            disclosure = (snapshot, month) -> read;
            assertThat(judge(CloseCheckCode.RECONCILIATION_DISCLOSURE).orElseThrow().status()).as(read.toString())
                    .isEqualTo(CloseCheckStatus.DISCLOSED);
        }
    }

    // ------------------------------------------------------------------ the valuation set

    @Test
    @DisplayName("VALUATION_SET_APPROVED: none approved FAILED, the period's proposals named so the controller can find them")
    void noApprovedSetFails() {
        ValuationRateSets.Valued proposed = sets().capture(SNAPSHOT, period, ACCOUNTANT, null, correlation());
        CheckVerdict verdict = judge(CloseCheckCode.VALUATION_SET_APPROVED).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(verdict.failures()).containsExactly(CloseChecks.NO_APPROVED_SET);
        assertThat(verdict.judgedBy()).isEqualTo("valuation-set:none");
        assertThat(verdict.evidence()).contains(proposed.id().value().toString(), "PROPOSED");
    }

    @Test
    @DisplayName("VALUATION_SET_APPROVED: the approved set PASSED, naming it - read afresh per attempt, pinned nowhere")
    void anApprovedSetPasses() {
        ValuationRateSets.Valued proposed = sets().capture(SNAPSHOT, period, ACCOUNTANT, null, correlation());
        sets().approve(SNAPSHOT, proposed.id(), CONTROLLER, "the rates agree", correlation());
        CheckVerdict verdict = judge(CloseCheckCode.VALUATION_SET_APPROVED).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(verdict.judgedBy()).isEqualTo("valuation-set:" + proposed.id().value());
        assertThat(verdict.evidence()).contains("\"approved\":\"" + proposed.id().value() + "\"", "\"rates\":\"1\"");
    }

    // ------------------------------------------------------------------ the breaks' check

    @Test
    @DisplayName("OPEN_ACCOUNTING_BREAKS is not a judge's: it is judged in its own conclusion, after every raise")
    void theBreaksAreJudgedInTheirConclusion() {
        assertThatThrownBy(() -> judge(CloseCheckCode.OPEN_ACCOUNTING_BREAKS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the rule the vocabulary left to this task: which checks may be disclosed")
    void whichChecksMayBeDisclosed() {
        assertThat(java.util.Arrays.stream(CloseCheckCode.values()).filter(CloseCheckCode::disclosable)).containsExactlyInAnyOrder(
                CloseCheckCode.RULE_CONFORMANCE, CloseCheckCode.SOURCE_COMPLETENESS, CloseCheckCode.ACCRUALS_COMPLETE,
                CloseCheckCode.RECONCILIATION_DISCLOSURE, CloseCheckCode.OPEN_ACCOUNTING_BREAKS);
        assertThat(java.util.Arrays.stream(CloseCheckCode.values()).filter(CloseCheckCode::raisesBreaks)).containsExactlyInAnyOrder(
                CloseCheckCode.GL_EQUALS_LEDGER, CloseCheckCode.RULE_CONFORMANCE, CloseCheckCode.SOURCE_COMPLETENESS);
    }

    // ------------------------------------------------------------------ plumbing

    private void anExceptionIsFailedNeverPassed(CloseCheckCode check) {
        CheckVerdict verdict = judge(check).orElseThrow();
        assertThat(verdict.status()).as(check.name()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(verdict.failures()).as(check.name()).anyMatch(failure -> failure.startsWith(CheckOutcome.UNAVAILABLE));
        assertThat(verdict.evidence()).doesNotContain("12345");
    }

    private Optional<CheckVerdict> judge(CloseCheckCode check) {
        return checks().judge(claimed(check, period));
    }

    private CloseRuns.ClaimedCheck claimed(CloseCheckCode check, AccountingPeriodId id) {
        return new CloseRuns.ClaimedCheck(CloseCheckId.next(IDS), CloseRunId.next(IDS), id, MARCH, check, 1, UUID.randomUUID(), 0,
                false, correlation(), Actor.SYSTEM);
    }

    private CloseChecks checks() {
        SourceProofs reading = (snapshot, source) -> proofs.apply(source);
        return new CloseChecks((month, check, actor, correlation) -> breakChecks.run(month, check, actor, correlation),
                (id, actor, correlation) -> trialBalance.read(id, actor, correlation), accruals, reading,
                (snapshot, month) -> disclosure.asOfCutoff(snapshot, month), sets(), periods,
                new AccountingChecks.Snapshots() {
                    @Override
                    public <R> R read(Function<Connection, R> work) {
                        snapshotsOpened++;
                        return work.apply(SNAPSHOT);
                    }
                });
    }

    private ValuationRateSets sets() {
        return new ValuationRateSets(setStore, periods, new OneRate(), ValuationStaleness.standard(), (uow, record) -> { }, IDS, CLOCK);
    }

    private static FinalTrialBalance.Verdict verdict(boolean passes, boolean opening, boolean period, boolean balanced, boolean mapped) {
        return new FinalTrialBalance.Verdict("run-1", 2, 1, "abc", passes, opening, period, balanced, mapped);
    }

    private static CheckOutcome clean(CloseCheckCode check) {
        return new CheckOutcome(MARCH, check, CheckOutcome.Result.PASSED, "posting-rules:v2", 0, 0, 0, 0, 0, List.of(), List.of());
    }

    private static CheckOutcome outcome(CloseCheckCode check, int findings, int raised, int open, int acknowledged, int resolved) {
        List<String> failures = new ArrayList<>(List.of(CheckOutcome.DISCREPANCIES));
        if (resolved > 0) {
            failures.add(CheckOutcome.RECURRED);
        }
        return new CheckOutcome(MARCH, check, CheckOutcome.Result.FAILED, "posting-rules:v2", findings, raised, open, acknowledged,
                resolved, List.of(), failures);
    }

    private static ReconciliationDisclosure.Disclosure nothing() {
        return new ReconciliationDisclosure.Disclosure(MARCH_END, Instant.parse("2097-04-01T00:00:00Z"), List.of(), 0, List.of(), 0,
                new TreeMap<>());
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** The reference: one valued pair, observed the day before the cutoff. */
    private static final class OneRate implements ValuationRates {

        private static final CurrencyCode USD = CurrencyCode.of("USD");

        @Override
        public List<CurrencyCode> valued() {
            return List.of(USD);
        }

        @Override
        public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
            return List.of(new Observed(USD, Optional.of(new Observation(UUID.randomUUID(), "simulated-reference",
                    CurrencyCode.of("EUR"), USD, new BigDecimal("1.1734000000"), before.minusSeconds(3_600),
                    before.minusSeconds(3_598)))));
        }
    }
}
