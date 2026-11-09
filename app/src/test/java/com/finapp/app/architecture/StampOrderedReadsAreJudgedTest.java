package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * "The latest" by a stamp, outside the close and the terms: every statement that picks one row (or computes one value) by a
 * stamp, in every module's production SQL, is named here with its judgement (`P15-TSK-037`; {@code THREAT_MODEL.md} T13,
 * {@code DISTRIBUTED_EXECUTION.md} section 3). A database clock steps; PI-013 showed one such read can stop a month's close. The
 * rule (ADR-0063 decision 2) is that a stamp never decides which of two facts came first: the rows do. This is the census of
 * where that question arises and how each answers it - held by reading the code, not by a {@code grep} of one spelling.
 *
 * <h2>What counts, read from the code</h2>
 *
 * <ul>
 *   <li><strong>A stamp</strong> is a column declared {@code timestamp}/{@code timestamptz} in any module's migrations, or any
 *       name ending {@code _at}, plus a {@code id} first in a single-row ordering: a UUIDv7 primary key is the minting instance's
 *       clock in its high bits (ADR-0013). A {@code date} is not: business dates are the domain's, not a clock's.
 *   <li><strong>The statements</strong>: every Java production string, text block or concatenation, constants inlined; every
 *       function's and view's <em>latest</em> definition in chain order, PL/pgSQL bodies included. One-shot statements (a data
 *       migration's) are frozen history and are not reads.
 *   <li><strong>The shapes</strong>: {@code ORDER BY <stamp>}, ascending or descending, the stamp in any position of the sort
 *       keys, followed by {@code LIMIT 1}/{@code FETCH FIRST}; {@code DISTINCT ON}; a window ranking ({@code row_number},
 *       {@code first_value}, ...); an ordered aggregate ({@code array_agg(... ORDER BY stamp)}); {@code SELECT ... INTO} in a
 *       function body with a stamp order and no limit; and {@code max(stamp)}/{@code min(stamp)}.
 * </ul>
 *
 * <p>The shapes it does not see, each walked by hand on 2026-11-09 and listed in {@code DISTRIBUTED_EXECUTION.md} section 3: a
 * bound {@code LIMIT ?} (a page; a bind of one is invisible); Java that takes the first row of an unbounded ordering, or sorts in
 * memory; a stamp an alias renames; a positional sort key ({@code ORDER BY 1}, the grouped reports' idiom, none a single-row read).
 *
 * <h2>The judgements</h2>
 *
 * <ul>
 *   <li>{@code RANKED}/{@code NAMED}: it decides, and the rows decide it (a version number, a row-order column, a chain's tail, a
 *       partial unique).
 *   <li>{@code FACT_TIME}: it decides, on an instant the platform's clock did not write and that must not be ranked by arrival: a
 *       provider's or a producer's own, by rule.
 *   <li>{@code DISPLAYS}: it is shown or counted and no code branches on the row. {@code SCHEDULES}: it decides only which due row
 *       is worked first or when a timer fires, the act itself being conditional or idempotent.
 *   <li>{@code ELSEWHERE}: accounting, lending or bnpl - {@code P15-TSK-033}'s and {@code P14-REM-009}'s, kept in the census so a new
 *       read there is still red here.
 * </ul>
 *
 * <p>Text, not behaviour: that each fixed read ranks correctly with the clock stepped is its database case, written and owed to
 * {@code P15-TST-002}.
 */
@Tag("architecture")
@DisplayName("every single-row read or value picked by a stamp, in the shapes this guard reads from the code, is judged: deciding reads ranked by the rows, the rest recorded (P15-TSK-037)")
class StampOrderedReadsAreJudgedTest {

    enum Judgement { RANKED, NAMED, FACT_TIME, DISPLAYS, SCHEDULES, ELSEWHERE }

    enum Shape { SINGLE_ROW, DISTINCT_ON, WINDOW, AGGREGATE, SELECT_INTO, MIN_MAX }

    private record Judged(int count, Judgement judgement, String why) {}

    private static final Map<String, Judged> CENSUS = new TreeMap<>();

    private static void read(String key, int count, Judgement judgement, String why) {
        assertThat(CENSUS.put(key, new Judged(count, judgement, why))).as("the census names " + key + " twice").isNull();
    }

    private static final String ACCOUNTING = "accounting: P15-TSK-033 and P14-REM-009's (the close, the submissions), out of this census's scope";
    private static final String LENDING = "lending: P15-TSK-033's, out of this census's scope";
    private static final String BNPL = "bnpl: P15-TSK-033's, out of this census's scope";
    private static final String GAUGE = "reported to an operator (a gauge or a report's figure): the value is read and shown, nothing branches on it; a step moves it by at most the step";
    private static final String SWEEP_AGE =
            "a sweep's due-time: the latest transition or start decides only when the row is re-driven; the re-drive is conditional and idempotent, a step moves it by at most the step";
    private static final String CLAIM =
            "a claim's queue order: every due row is claimed, the predicate decides which are due, this order only which goes first";

    static {
        census();
    }

    private static void census() {
        read("accounting.JdbcCloseRunStore|SINGLE_ROW|(run.period_locked_at = period.locked_at) is true desc, run.period_revision desc, run.started_at desc, run.id desc", 1, Judgement.ELSEWHERE,
                ACCOUNTING);
        read("accounting.JdbcCloseRunStore|SINGLE_ROW|candidate.lease_until, candidate.id", 1, Judgement.ELSEWHERE,
                ACCOUNTING);
        read("accounting.JdbcPeriodCloseStore|SINGLE_ROW|(c.status = '') desc, c.period_revision desc, (c.status = '') desc, c.proposed_at desc, c.id desc", 1, Judgement.ELSEWHERE,
                ACCOUNTING);
        read("accounting.JdbcRegulatorySubmissionStore|SINGLE_ROW|next_inquiry_at, id", 1, Judgement.ELSEWHERE,
                ACCOUNTING);
        read("app.FxReferenceValuationRates|SINGLE_ROW|observed_at desc", 1, Judgement.FACT_TIME,
                "the reference source's own observation instant, by rule: a valuation asks what the source observed before an instant, and the source's order is the fact (fx V002: one row per (source, pair, observed_at); a not-newer observation is declined)");
        read("app.LedgerMonitoredLedger|SINGLE_ROW|id", 1, Judgement.SCHEDULES,
                "a keyset step along the journal by id: the monitoring intake's cursor; every entry is read in turn, and the id only names a position, not which of two facts came first");
        read("app.LendingReports|SINGLE_ROW|e.occurred_at desc, e.id desc", 6, Judgement.DISPLAYS,
                "a portfolio report: the status a loan was in at the end of a business date, counted and shown; nothing branches on it");
        read("app.ReconciliationMonthReads|MIN_MAX|min(r.decided_at)", 1, Judgement.ELSEWHERE,
                ACCOUNTING);
        read("bnpl.JdbcBnplAccrualStore|MIN_MAX|max(statement_timestamp() - ((x.next_date + 1)::timestamp at time zone x.zone))", 1, Judgement.ELSEWHERE,
                BNPL);
        read("bnpl.JdbcBnplApplicationStore|MIN_MAX|min(submitted_at)", 1, Judgement.ELSEWHERE,
                BNPL);
        read("bnpl.JdbcBnplDisputeStore|MIN_MAX|max(resolved_at)", 1, Judgement.ELSEWHERE,
                BNPL);
        read("bnpl.JdbcBnplDisputeStore|MIN_MAX|min(merchant_responded_at)", 1, Judgement.ELSEWHERE,
                BNPL);
        read("bnpl.bnpl_terms_version_permits_only_machine_edges|MIN_MAX|max(o.offered_at)", 1, Judgement.ELSEWHERE,
                BNPL);
        read("credit.JdbcCreditReads|MIN_MAX|max(retrieved_at)", 1, Judgement.DISPLAYS,
                "the customer's sources on file, shown; nothing branches on it");
        read("credit.JdbcDecisionRequestStore|MIN_MAX|min(submitted_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("credit.JdbcUnderwritingCaseStore|MIN_MAX|min(opened_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("credit.credit_policy_permits_only_machine_edges|MIN_MAX|max(v.effective_to)", 1, Judgement.RANKED,
                "the successor's start equals the predecessor's end by construction: each end is floored at its own start which is floored at the prior end, so the ends rise with the version and max is the latest (X-TSK-017, V014): monotone, not ordered by a clock");
        read("credit.data_request_permits_only_machine_edges|MIN_MAX|max(d.requested_at)", 1, Judgement.RANKED,
                "V015: a data request's stamp is floored just above its decision's earlier ones, the birth order held by the rows");
        read("credit.scorecard_model_permits_only_machine_edges|MIN_MAX|max(v.effective_to)", 1, Judgement.RANKED,
                "the successor's start equals the predecessor's end by construction: each end is floored at its own start which is floored at the prior end, so the ends rise with the version and max is the latest (X-TSK-017, V014): monotone, not ordered by a clock");
        read("crossborder.JdbcPaymentStore|MIN_MAX|min(e.occurred_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("fx.JdbcCoverStore|MIN_MAX|min(created_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("fx.JdbcCoverStore|MIN_MAX|min(last_dispatched_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("fx.JdbcRateSnapshotStore|SINGLE_ROW|observed_at desc", 3, Judgement.FACT_TIME,
                "the reference source's own observation instant, by rule: \"the latest\" of a pair is the source's order, not the platform's clock; fx V002 keeps one row per (source, pair, observed_at) and declines an observation not newer than the latest, so a platform step cannot reorder it");
        read("kyc.JdbcCheckStore|SINGLE_ROW|check_order desc nulls last, requested_at desc, id desc", 2, Judgement.RANKED,
                "check_order (kyc V012), a sequence drawn at the insert after the one-in-flight index admitted it; the stamps rank only rows from before V012, which have no order and sit below every row that does");
        read("kyc.JdbcCheckStore|SINGLE_ROW|check_order desc nulls last, requested_at desc, status_changed_at desc, id desc", 1, Judgement.RANKED,
                "check_order (kyc V012), a sequence drawn at the insert after the one-in-flight index admitted it; the stamps rank only rows from before V012, which have no order and sit below every row that does");
        read("kyc.JdbcCounterpartyScreeningStore|MIN_MAX|min(decided_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("lending.JdbcLoanApplicationStore|MIN_MAX|min(submitted_at)", 1, Judgement.ELSEWHERE,
                LENDING);
        read("lending.JdbcLoanDisbursementStore|MIN_MAX|min(created_at)", 1, Judgement.ELSEWHERE,
                LENDING);
        read("lending.JdbcLoanPayoutStore|MIN_MAX|min(created_at)", 1, Judgement.ELSEWHERE,
                LENDING);
        read("lending.JdbcLoanServicingStore|MIN_MAX|max(statement_timestamp() - ((x.next_date + 1)::timestamp - x.zone_offset) at time zone '')", 1, Judgement.ELSEWHERE,
                LENDING);
        read("lending.loan_terms_version_permits_only_machine_edges|MIN_MAX|max(o.offered_at)", 1, Judgement.ELSEWHERE,
                LENDING);
        read("merchant.JdbcFeeScheduleStore|MIN_MAX|max(v.effective_from)", 1, Judgement.RANKED,
                "the write-time floor of an immediately-effective version: the greatest start among immediate lower-numbered versions, chosen by version number, keeping the newest immediate version in force (INSERT_VERSION); the minting arbiter has committed every lower number");
        read("merchant.JdbcFeeScheduleStore|SINGLE_ROW|effective_from desc, version desc", 1, Judgement.RANKED,
                "in force is the greatest declared start, ties by version; an immediately-effective version's start is floored at the write, by version number, at its immediate predecessor's (JdbcFeeScheduleStore.INSERT_VERSION), so a stepped or skewed instance clock cannot shadow the newest rate");
        read("merchant.JdbcFeeScheduleStore|SINGLE_ROW|v.effective_from desc, v.version desc", 1, Judgement.RANKED,
                "in force is the greatest declared start, ties by version; an immediately-effective version's start is floored at the write, by version number, at its immediate predecessor's (JdbcFeeScheduleStore.INSERT_VERSION), so a stepped or skewed instance clock cannot shadow the newest rate");
        read("merchant.JdbcMerchantPayoutStore|MIN_MAX|max(occurred_at)", 2, Judgement.SCHEDULES,
                SWEEP_AGE);
        read("merchant.JdbcMerchantPayoutStore|MIN_MAX|min(case when p.status = '' then coalesce(h.entered, p.created_at) else p.last_dispatched_at end)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcCaptureHoldStore|SINGLE_ROW|held_until, intent_id", 1, Judgement.SCHEDULES,
                CLAIM);
        read("payments.JdbcDisputeResponseStore|MIN_MAX|max(occurred_at)", 2, Judgement.SCHEDULES,
                SWEEP_AGE);
        read("payments.JdbcDisputeResponseStore|MIN_MAX|min(case when r.status = '' then coalesce(h.entered, r.created_at) else r.send_permit end)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcDisputeStore|MIN_MAX|min(t.occurred_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcOutboundCreditStore|MIN_MAX|min(last_dispatched_at)", 2, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcPaymentAttemptStore|MIN_MAX|max(occurred_at)", 3, Judgement.SCHEDULES,
                SWEEP_AGE);
        read("payments.JdbcPaymentAttemptStore|MIN_MAX|min(coalesce(h.entered, a.created_at))", 1, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcPaymentAttemptStore|MIN_MAX|min(created_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcPaymentAttemptStore|SINGLE_ROW|attempt_order desc nulls last, created_at desc, id desc", 1, Judgement.RANKED,
                "attempt_order (payments V038), a sequence drawn at the insert after the intent's conditional transition; created_at ranks only rows from before V038, which have no order and sit below every row that does");
        read("payments.JdbcRefundStore|MIN_MAX|max(occurred_at)", 3, Judgement.SCHEDULES,
                SWEEP_AGE);
        read("payments.JdbcRefundStore|MIN_MAX|min(case when r.status = '' then coalesce(h.entered, r.created_at) else r.last_dispatched_at end)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcRoutingStore|MIN_MAX|max(v.effective_from)", 1, Judgement.RANKED,
                "the write-time floor of an immediately-effective version: the greatest start among immediate lower-numbered versions, chosen by version number, keeping the newest immediate version in force (INSERT_VERSION); the minting arbiter has committed every lower number");
        read("payments.JdbcRoutingStore|SINGLE_ROW|(chosen_rail is not null) desc, created_at desc, id desc", 2, Judgement.RANKED,
                "the chosen decision first, named by routing_decision_one_chosen_per_*; the stamp orders only the refused decisions an operator's explanation lists, which branches on nothing");
        read("payments.JdbcRoutingStore|SINGLE_ROW|effective_from desc, version desc", 1, Judgement.RANKED,
                "in force is the greatest declared start, ties by version; an immediately-effective version's start is floored at the write, by version number, at its immediate predecessor's (JdbcRoutingStore.INSERT_VERSION), so a stepped or skewed instance clock cannot shadow the newer one");
        read("payments.JdbcUnmatchedConfirmationStore|MIN_MAX|min(received_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcWithdrawalStore|MIN_MAX|max(occurred_at)", 2, Judgement.SCHEDULES,
                SWEEP_AGE);
        read("payments.JdbcWithdrawalStore|MIN_MAX|min(case when w.status = '' then coalesce(h.entered, w.created_at) else w.last_dispatched_at end)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("payments.JdbcWithdrawalStore|SINGLE_ROW|held_until, id", 1, Judgement.SCHEDULES,
                CLAIM);
        read("payments.JdbcWithdrawalStore|SINGLE_ROW|id", 1, Judgement.RANKED,
                "the withdrawal event table's bigint identity sequence: the first terminal transition by its own row order (a terminal status is reached once), never a stamp");
        read("platform.OutboxBacklog|MIN_MAX|min(coalesce(replay_ordered_at, occurred_at))", 2, Judgement.DISPLAYS,
                GAUGE);
        read("reconciliation.JdbcBreakCaseStore|SINGLE_ROW|id", 1, Judgement.NAMED,
                "the break's held suspense item: the partial uniques on suspense_item admit one live holding of an item, and a break holds one; the id only makes the rare multi-item parking deterministic, never by a clock");
        read("reconciliation.JdbcBreakReadings|MIN_MAX|min(raised_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("reconciliation.JdbcReportReadings|DISTINCT_ON|d.external_item_id, d.decided_at desc, d.id desc", 1, Judgement.DISPLAYS,
                "the fee variance report's latest expected fee of an item: shown in a report, it posts and decides nothing");
        read("reconciliation.JdbcReportReadings|SINGLE_ROW|case b.severity when '' then 0 when '' then 1 when '' then 2 else 3 end, b.raised_at, b.id", 2, Judgement.DISPLAYS,
                "a report's worst open break of an item: severity first, then the oldest raise, to name one; shown, nothing branches on it");
        read("reconciliation.JdbcRepudiationStore|SINGLE_ROW|b.id desc", 1, Judgement.RANKED,
                "the break a repudiation continues: the chain's tail, the resolved break of the expectation and type no break continues (break_one_open_per_expectation makes them sequential); the id only breaks a tie among tails a past branch might have left");
        read("reconciliation.JdbcRunReadings|MIN_MAX|min(created_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("reconciliation.JdbcRunReadings|MIN_MAX|min(overdue_since)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("risk.JdbcCustomerRiskProfileStore|SINGLE_ROW|next_recompute_at", 1, Judgement.SCHEDULES,
                CLAIM);
        read("risk.JdbcRegulatoryReportStore|SINGLE_ROW|next_inquiry_at, id", 1, Judgement.SCHEDULES,
                CLAIM);
        read("risk.JdbcRescreeningPermitStore|SINGLE_ROW|next_attempt_at, id", 1, Judgement.SCHEDULES,
                CLAIM);
        read("risk.JdbcRiskActionStore|SINGLE_ROW|next_attempt_at, id", 1, Judgement.SCHEDULES,
                CLAIM);
        read("risk.JdbcRiskAssessmentStore|SINGLE_ROW|id", 1, Judgement.DISPLAYS,
                "the velocity proof's fixed floor (the first transfer ever judged): reported and compared by the proof, a step moves the floor by at most the step");
        read("risk.JdbcRiskCaseStore|MIN_MAX|min(opened_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("risk.JdbcRiskCaseStore|SINGLE_ROW|c.id desc", 1, Judgement.RANKED,
                "a new case's predecessor: the chain's tail, the disposed case no later case names (risk_case_one_open makes a subject's cases of a kind sequential); the id only breaks a tie among tails a past branch might have left");
        read("risk.JdbcRiskProofReads|MIN_MAX|min(filed_at)", 1, Judgement.DISPLAYS,
                "the proofs' oldest-open age (the oldest filing not concluded): reported, and nothing branches on it");
        read("risk.JdbcRiskSignalStore|MIN_MAX|min(observed_at)", 1, Judgement.FACT_TIME,
                "a subject's first signal of a kind, the event's own instant, read as an age (SignalReadings.age); no order between two facts is asked");
        read("risk.JdbcRiskSignalStore|SINGLE_ROW|observed_at desc, id desc", 1, Judgement.FACT_TIME,
                "the source event's own instant, by rule (INV-EVT-04: a delayed event must not overtake a later one; an arrival sequence would): recorded_at is no longer the tie-break, the id is");
        read("risk.JdbcScreeningListStore|SINGLE_ROW|enqueue_next_at, id", 1, Judgement.SCHEDULES,
                CLAIM);
        read("risk.JdbcVelocityWindowStore|MIN_MAX|min(w.window_start)", 1, Judgement.FACT_TIME,
                "the subject's first counted hour, a bucket start read as an age to the hour (the velocity rules' novelty reading); no order between two facts is asked, a step moves it by at most the step across an hour's edge");
        read("risk.risk_policy_version_permits_only_machine_edges|MIN_MAX|max(a.assessed_at)", 1, Judgement.RANKED,
                "V020: the retirement ends one microsecond after the latest assessment (max over the pins) and the successor starts exactly there, by row order; the max can only push the end later");
        read("settlement.JdbcSettlementBatchStore|MIN_MAX|max(status_changed_at)", 1, Judgement.DISPLAYS,
                GAUGE);
        read("settlement.JdbcSettlementFileStore|MIN_MAX|min(f.received_at)", 1, Judgement.DISPLAYS,
                GAUGE);
    }

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern CREATE_FUNCTION = Pattern.compile("(?is)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\s+([a-z_]+\\.\\w+)");
    private static final Pattern DROP_FUNCTION = Pattern.compile("(?is)^\\s*DROP\\s+FUNCTION\\s+(?:IF\\s+EXISTS\\s+)?([a-z_]+\\.\\w+)");
    private static final Pattern CREATE_VIEW = Pattern.compile("(?is)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+([a-z_]+\\.\\w+)");
    private static final Pattern DROP_VIEW = Pattern.compile("(?is)^\\s*DROP\\s+(?:MATERIALIZED\\s+)?VIEW\\s+(?:IF\\s+EXISTS\\s+)?([a-z_]+\\.\\w+)");
    private static final Pattern DOLLAR = Pattern.compile("\\$([A-Za-z_]*)\\$");
    private static final Pattern STAMP_COLUMN = Pattern.compile("(?im)^\\s*([a-z_][a-z0-9_]*)\\s+timestamp(?:tz| with(?:out)? time zone)?\\b");
    private static final Pattern ADD_STAMP_COLUMN =
            Pattern.compile("(?i)ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)\\s+timestamp");
    private static final Pattern ORDER_BY = Pattern.compile("(?i)\\bORDER\\s+BY\\b");
    private static final Pattern MIN_MAX_CALL = Pattern.compile("(?i)\\b(min|max)\\s*\\(");
    private static final Pattern WINDOWED = Pattern.compile("(?i)\\bOVER\\s*$");
    private static final Pattern ORDERED_AGGREGATE = Pattern.compile("(?i)\\b(array_agg|string_agg|json_agg|jsonb_agg|xmlagg)\\s*$");
    private static final Pattern SINGLE_LIMIT = Pattern.compile("(?i)^\\)?\\s*(?:LIMIT\\s+1\\b|FETCH\\s+(?:FIRST|NEXT)\\s+(?:1\\s+)?ROWS?\\s+ONLY)");
    private static final Pattern ANY_LIMIT = Pattern.compile("(?i)^\\)?\\s*(?:LIMIT|FETCH\\s+(?:FIRST|NEXT))\\b");
    private static final Pattern ID_FIRST = Pattern.compile("(?i)^(?:[a-z_]\\w*\\.)?id(?:\\s+(?:asc|desc))?(?:\\s*,.*)?$");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Set<String> STAMPS = new TreeSet<>();
    private static final Map<String, Integer> FOUND = new TreeMap<>();
    private static final Map<String, List<String>> SAMPLES = new TreeMap<>();
    private static int filesRead;
    private static int functionsRead;

    /** Everything the rules below inspect - the migrations (stamps, functions), then every production Java file. */
    @BeforeAll
    static void readEveryModule() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<Path> modules;
        try (Stream<Path> children = Files.list(root)) {
            modules = children.filter(Files::isDirectory).sorted().toList();
        }
        Map<String, String> functions = new TreeMap<>();
        Map<String, String> views = new TreeMap<>();
        for (Path module : modules) {
            Path migrations = module.resolve("src/main/resources/db/migration").resolve(module.getFileName());
            if (Files.isDirectory(migrations)) {
                List<Path> scripts;
                try (Stream<Path> files = Files.list(migrations)) {
                    scripts = files.filter(file -> VERSIONED.matcher(file.getFileName().toString()).matches())
                            .sorted(Comparator.comparingInt(StampOrderedReadsAreJudgedTest::version)).toList();
                }
                for (Path script : scripts) {
                    readMigration(EvidenceCitations.read(script), functions, views);
                }
            }
        }
        for (Path module : modules) {
            Path sources = module.resolve("src/main/java");
            if (!Files.isDirectory(sources)) {
                continue;
            }
            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(sources)) {
                javaFiles = walk.filter(file -> file.getFileName().toString().endsWith(".java")).sorted().toList();
            }
            scanJava(module.getFileName().toString(), javaFiles);
        }
        functions.forEach((name, body) -> scanSql(functionUnit(name), body, true));
        views.forEach((name, body) -> scanSql(functionUnit(name), body, false));
        functionsRead = functions.size() + views.size();
    }

    private static String functionUnit(String qualified) {
        return qualified.substring(0, qualified.indexOf('.')) + "." + qualified.substring(qualified.indexOf('.') + 1);
    }

    private static int version(Path script) {
        Matcher matcher = VERSIONED.matcher(script.getFileName().toString());
        if (!matcher.matches()) {
            throw new UncheckedIOException(new IOException("Not a versioned migration: " + script));
        }
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    @DisplayName("the census reads every module with a statement: a floor, so a renamed folder cannot pass as an empty census")
    void theGuardReadTheCode() {
        assertThat(filesRead).as("production Java files read").isGreaterThanOrEqualTo(2000);
        assertThat(functionsRead).as("functions and views read at their latest definition").isGreaterThanOrEqualTo(400);
        assertThat(STAMPS).as("stamp columns read from the migrations' DDL").hasSizeGreaterThanOrEqualTo(120)
                .contains("assessed_at", "effective_from", "effective_to", "observed_at", "received_at")
                .doesNotContain("period_start", "business_date");
    }

    @Test
    @DisplayName("every stamp-ordered single-row read and every stamped min or max is in the census, at its count; a census row with no read is stale")
    void everyStampReadIsJudged() {
        Map<String, Integer> expected = new TreeMap<>();
        CENSUS.forEach((key, judged) -> expected.put(key, judged.count()));
        assertThat(FOUND)
                .as("a new read that picks by a stamp is judged here (DECIDES: ranked by the rows or named by identity with a pin"
                        + " and a clock-step case; DISPLAYS, SCHEDULES or FACT_TIME with its reason), or it is rewritten to rank by"
                        + " the rows; a row with no read left in the code leaves the census")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("every judgement states its reason, and a read that decides names how the rows decide")
    void everyJudgementIsReasoned() {
        CENSUS.forEach((key, judged) -> assertThat(judged.why()).as(key).isNotBlank().hasSizeGreaterThan(20));
    }

    @Test
    @DisplayName("each shape is recognised: planted reads are red in every spelling, and a non-reading is not")
    void plantedShapesAreRecognised() {
        assertThat(analyse("SELECT x FROM t ORDER BY proposed_at DESC, id DESC LIMIT 1", false)).containsExactly("SINGLE_ROW|proposed_at desc, id desc");
        assertThat(analyse("SELECT x FROM t ORDER BY proposed_at FETCH FIRST 1 ROW ONLY", false)).containsExactly("SINGLE_ROW|proposed_at");
        assertThat(analyse("SELECT x FROM t ORDER BY proposed_at ASC LIMIT 1 OFFSET 2", false)).containsExactly("SINGLE_ROW|proposed_at asc");
        assertThat(analyse("SELECT x FROM t ORDER BY status DESC, proposed_at DESC LIMIT 1", false)).hasSize(1);
        assertThat(analyse("SELECT DISTINCT ON (k) x FROM t ORDER BY k, decided_at DESC", false)).containsExactly("DISTINCT_ON|k, decided_at desc");
        assertThat(analyse("SELECT x, row_number() OVER (PARTITION BY k ORDER BY raised_at DESC) FROM t", false)).containsExactly("WINDOW|raised_at desc");
        assertThat(analyse("SELECT (array_agg(x ORDER BY raised_at DESC))[1] FROM t", false)).containsExactly("AGGREGATE|raised_at desc");
        assertThat(analyse("SELECT max(p.resolved_at) FROM t p", false)).containsExactly("MIN_MAX|max(p.resolved_at)");
        assertThat(analyse("SELECT 1 FROM t WHERE a = (SELECT min(a.created_at) FROM t a)", false)).containsExactly("MIN_MAX|min(a.created_at)");
        assertThat(analyse("SELECT x INTO y FROM t WHERE k = 1 ORDER BY recorded_at DESC;", true)).containsExactly("SELECT_INTO|recorded_at desc");
        assertThat(analyse("SELECT x FROM t WHERE k = 1 ORDER BY id DESC LIMIT 1", false)).as("a UUIDv7 key first").containsExactly("SINGLE_ROW|id desc");
        assertThat(analyse("SELECT x FROM t ORDER BY proposed_at DESC LIMIT ?", false)).as("a bound limit is a page").isEmpty();
        assertThat(analyse("SELECT x FROM t ORDER BY name DESC LIMIT 1", false)).as("not a stamp").isEmpty();
        assertThat(analyse("SELECT max(amount_minor) FROM t", false)).as("not a stamp").isEmpty();
        assertThat(analyse("SELECT x FROM t ORDER BY version DESC LIMIT 1", false)).isEmpty();
        assertThat(statementsOf("Plant", """
                class Plant {
                    private static final String T = "ORDER BY proposed_at DESC";
                    String sql() { return "SELECT x FROM t " + T + " LIMIT 1"; }
                    String block = \"""
                        SELECT y FROM u ORDER BY decided_at FETCH FIRST ROW ONLY
                        \""";
                    // "SELECT z FROM v ORDER BY raised_at LIMIT 1" is prose
                }
                """)).contains("SELECT x FROM t ORDER BY proposed_at DESC LIMIT 1", "SELECT y FROM u ORDER BY decided_at FETCH FIRST ROW ONLY")
                .noneMatch(statement -> statement.contains("raised_at"));
    }

    // ------------------------------------------------------------------------- reading the code

    private static void readMigration(String script, Map<String, String> functions, Map<String, String> views) {
        for (String statement : splitStatements(script)) {
            Matcher function = CREATE_FUNCTION.matcher(statement);
            Matcher dropFunction = DROP_FUNCTION.matcher(statement);
            Matcher view = CREATE_VIEW.matcher(statement);
            Matcher dropView = DROP_VIEW.matcher(statement);
            if (function.find()) {
                functions.put(function.group(1).toLowerCase(Locale.ROOT), bodyOf(statement));
            } else if (dropFunction.find()) {
                functions.remove(dropFunction.group(1).toLowerCase(Locale.ROOT));
            } else if (view.find()) {
                views.put(view.group(1).toLowerCase(Locale.ROOT), statement);
            } else if (dropView.find()) {
                views.remove(dropView.group(1).toLowerCase(Locale.ROOT));
            }
            if (statement.stripLeading().toUpperCase(Locale.ROOT).matches("(?s)^(CREATE\\s+(UNLOGGED\\s+)?TABLE|ALTER\\s+TABLE).*")) {
                Matcher column = STAMP_COLUMN.matcher(statement);
                while (column.find()) {
                    STAMPS.add(column.group(1).toLowerCase(Locale.ROOT));
                }
                Matcher added = ADD_STAMP_COLUMN.matcher(statement);
                while (added.find()) {
                    STAMPS.add(added.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    /** A function's body: the text between its first dollar-quote and that quote's close, or the whole statement for a view. */
    private static String bodyOf(String statement) {
        Matcher open = DOLLAR.matcher(statement);
        if (!open.find()) {
            return statement;
        }
        int close = statement.indexOf(open.group(), open.end());
        return close < 0 ? statement : statement.substring(open.end(), close);
    }

    /** Statements split on {@code ;} outside comments, strings and dollar quotes - and comments dropped. */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (sql.startsWith("/*", i)) {
                i = sql.indexOf("*/", i) + 2;
            } else if (c == '\'') {
                int end = i + 1;
                while (end < length) {
                    if (sql.charAt(end) == '\'') {
                        if (end + 1 < length && sql.charAt(end + 1) == '\'') {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                current.append(sql, i, end + 1);
                i = end + 1;
            } else if (c == '$') {
                Matcher open = DOLLAR.matcher(sql.substring(i, Math.min(length, i + 64)));
                if (open.lookingAt()) {
                    int close = sql.indexOf(open.group(), i + open.end());
                    current.append(sql, i, close + open.group().length());
                    i = close + open.group().length();
                } else {
                    current.append(c);
                    i++;
                }
            } else if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private record Token(char kind, String text) {}

    /** Java's tokens: string literals and text blocks (contents, unescaped), identifiers and punctuation, comments dropped. */
    private static List<Token> lex(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (source.startsWith("/*", i)) {
                i = source.indexOf("*/", i + 2) + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                while (source.charAt(end - 1) == '\\') {
                    end = source.indexOf("\"\"\"", end + 1);
                }
                String body = source.substring(i + 3, end);
                tokens.add(new Token('S', body.substring(body.indexOf('\n') + 1).replace("\\\n", " ")));
                i = end + 3;
            } else if (c == '"') {
                int end = i + 1;
                StringBuilder literal = new StringBuilder();
                while (source.charAt(end) != '"') {
                    if (source.charAt(end) == '\\') {
                        end++;
                        literal.append(source.charAt(end) == 'n' ? ' ' : source.charAt(end));
                    } else {
                        literal.append(source.charAt(end));
                    }
                    end++;
                }
                tokens.add(new Token('S', literal.toString()));
                i = end + 1;
            } else if (c == '\'') {
                int end = i + 1;
                while (source.charAt(end) != '\'') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                i = end + 1;
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = i;
                while (end < length && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                tokens.add(new Token('I', source.substring(i, end)));
                i = end;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                tokens.add(new Token('P', String.valueOf(c)));
                i++;
            }
        }
        return tokens;
    }

    /** Every Java statement in one file, its strings and constants joined, whitespace collapsed: what the driver would receive. */
    static Set<String> statementsOf(String className, String source) {
        Map<String, List<Token>> constants = new HashMap<>();
        List<Token> tokens = lex(source);
        collectConstants(tokens, constants);
        return statements(className, tokens, constants, Map.of());
    }

    private static void collectConstants(List<Token> tokens, Map<String, List<Token>> constants) {
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).equals(new Token('I', "String")) && tokens.get(i + 1).kind() == 'I'
                    && tokens.get(i + 2).equals(new Token('P', "="))) {
                List<Token> expression = new ArrayList<>();
                int depth = 0;
                for (int j = i + 3; j < tokens.size(); j++) {
                    Token token = tokens.get(j);
                    if (token.kind() == 'P' && "([{".contains(token.text())) {
                        depth++;
                    }
                    if (token.kind() == 'P' && ")]}".contains(token.text())) {
                        depth--;
                        if (depth < 0) {
                            break;
                        }
                    }
                    if (depth == 0 && token.equals(new Token('P', ";"))) {
                        break;
                    }
                    expression.add(token);
                }
                constants.putIfAbsent(tokens.get(i + 1).text(), expression);
            }
        }
    }

    private static final Set<String> SQL_MARKS = Set.of("ORDER BY", "MIN(", "MAX(", "DISTINCT ON", "OVER (", "ARRAY_AGG(");

    private static Set<String> statements(String className, List<Token> tokens, Map<String, List<Token>> constants,
            Map<String, Map<String, List<Token>>> everyClass) {
        Set<String> found = new LinkedHashSet<>();
        Map<String, StringBuilder> builders = new HashMap<>();
        List<Token> current = new ArrayList<>();
        for (Token token : tokens) {
            if (token.kind() == 'P' && ";{}".contains(token.text())) {
                if (!current.isEmpty()) {
                    collect(className, current, constants, everyClass, builders, found);
                }
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        return found;
    }

    /** One Java statement's text; a {@code StringBuilder}'s {@code append}s are chained across statements of one builder name. */
    private static void collect(String className, List<Token> statement, Map<String, List<Token>> constants,
            Map<String, Map<String, List<Token>>> everyClass, Map<String, StringBuilder> builders, Set<String> found) {
        String text = expand(statement, constants, everyClass, new LinkedHashSet<>()).replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return;
        }
        if (statement.size() >= 3 && statement.get(1).equals(new Token('P', ".")) && statement.get(2).equals(new Token('I', "append"))) {
            StringBuilder builder = builders.computeIfAbsent(statement.get(0).text(), name -> new StringBuilder());
            builder.append(text);
            found.add(builder.toString().replaceAll("\\s+", " ").strip());
            return;
        }
        for (int i = 0; i + 1 < statement.size(); i++) {
            if (statement.get(i).equals(new Token('I', "StringBuilder")) && statement.get(i + 1).kind() == 'I') {
                builders.put(statement.get(i + 1).text(), new StringBuilder(text));
            }
        }
        String upper = text.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (SQL_MARKS.stream().anyMatch(upper::contains) && upper.contains("SELECT") || upper.contains("ORDER BY")) {
            found.add(text);
        }
    }

    private static String expand(List<Token> tokens, Map<String, List<Token>> constants, Map<String, Map<String, List<Token>>> everyClass,
            Set<String> seen) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() == 'S') {
                text.append(token.text());
            } else if (token.kind() == 'I') {
                if (i + 1 < tokens.size() && tokens.get(i + 1).equals(new Token('P', "="))) {
                    continue; // an assignment's target (a field or local being defined) is not an operand of its own value
                }
                if (i + 2 < tokens.size() && tokens.get(i + 1).equals(new Token('P', ".")) && tokens.get(i + 2).kind() == 'I'
                        && everyClass.containsKey(token.text()) && everyClass.get(token.text()).containsKey(tokens.get(i + 2).text())) {
                    String reference = token.text() + "." + tokens.get(i + 2).text();
                    if (seen.add(reference)) {
                        text.append(expand(everyClass.get(token.text()).get(tokens.get(i + 2).text()), everyClass.get(token.text()), everyClass, seen));
                        seen.remove(reference);
                    }
                    i += 2;
                } else if (constants.containsKey(token.text()) && seen.add(token.text())) {
                    text.append(expand(constants.get(token.text()), constants, everyClass, seen));
                    seen.remove(token.text());
                }
            }
        }
        return text.toString();
    }

    private static void scanJava(String module, List<Path> javaFiles) {
        Map<String, Map<String, List<Token>>> everyClass = new HashMap<>();
        Map<String, List<Token>> tokensOf = new LinkedHashMap<>();
        for (Path file : javaFiles) {
            String className = file.getFileName().toString().replace(".java", "");
            List<Token> tokens = lex(EvidenceCitations.read(file));
            tokensOf.put(className, tokens);
            Map<String, List<Token>> constants = new HashMap<>();
            collectConstants(tokens, constants);
            everyClass.put(className, constants);
            filesRead++;
        }
        tokensOf.forEach((className, tokens) -> {
            for (String statement : statements(className, tokens, everyClass.get(className), everyClass)) {
                scanSql(module + "." + className, statement, false);
            }
        });
    }

    private static void scanSql(String unit, String sql, boolean functionBody) {
        for (String reading : analyse(sql, functionBody)) {
            FOUND.merge(unit + "|" + reading, 1, Integer::sum);
        }
    }

    /** The stamp-reading shapes of one statement (or one function body) - {@code "<shape>|<key>"}, one per occurrence. */
    static List<String> analyse(String raw, boolean functionBody) {
        String sql = raw.replaceAll("'(?:[^']|'')*'", "''").replaceAll("\\s+", " ");
        List<String> shapes = new ArrayList<>();
        Matcher orderBy = ORDER_BY.matcher(sql);
        while (orderBy.find()) {
            int start = orderBy.end();
            int end = endOfKeys(sql, start);
            String keys = sql.substring(start, end).strip().toLowerCase(Locale.ROOT);
            String after = sql.substring(end).stripLeading();
            int open = enclosingOpen(sql, orderBy.start());
            String beforeOpen = open < 0 ? "" : sql.substring(Math.max(0, open - 16), open).stripTrailing();
            boolean stamp = holdsAStamp(keys);
            if (WINDOWED.matcher(beforeOpen).find()) {
                if (stamp) {
                    shapes.add("WINDOW|" + keys);
                }
            } else if (ORDERED_AGGREGATE.matcher(beforeOpen).find()) {
                if (stamp) {
                    shapes.add("AGGREGATE|" + keys);
                }
            } else if (SINGLE_LIMIT.matcher(after).find()) {
                if (stamp || ID_FIRST.matcher(keys).matches()) {
                    shapes.add("SINGLE_ROW|" + keys);
                }
            } else if (Pattern.compile("(?i)\\bDISTINCT\\s+ON\\b").matcher(sql.substring(0, orderBy.start())).find()) {
                if (stamp) {
                    shapes.add("DISTINCT_ON|" + keys);
                }
            } else if (functionBody && stamp && !ANY_LIMIT.matcher(after).find()) {
                String statement = sql.substring(sql.lastIndexOf(';', orderBy.start()) + 1, orderBy.start());
                if (Pattern.compile("(?i)\\bINTO\\b").matcher(statement).find()) {
                    shapes.add("SELECT_INTO|" + keys);
                }
            }
        }
        Matcher aggregate = MIN_MAX_CALL.matcher(sql);
        while (aggregate.find()) {
            int close = matchingClose(sql, aggregate.end());
            String argument = sql.substring(aggregate.end(), close);
            if (holdsAStamp(argument)) {
                shapes.add("MIN_MAX|" + aggregate.group(1).toLowerCase(Locale.ROOT) + "(" + argument.strip().toLowerCase(Locale.ROOT) + ")");
            }
        }
        return shapes;
    }

    private static int matchingClose(String sql, int openEnd) {
        int depth = 0;
        for (int i = openEnd; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return sql.length();
    }

    private static boolean holdsAStamp(String expression) {
        Matcher word = WORD.matcher(expression);
        while (word.find()) {
            String name = word.group().toLowerCase(Locale.ROOT);
            if (STAMPS.contains(name) || name.endsWith("_at")) {
                return true;
            }
        }
        return false;
    }

    private static int enclosingOpen(String sql, int position) {
        int depth = 0;
        for (int i = position - 1; i >= 0; i--) {
            char c = sql.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    private static int endOfKeys(String sql, int start) {
        int depth = 0;
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (depth == 0 && (c == ';'
                    || Pattern.compile("(?i)^\\s(LIMIT|OFFSET|FETCH|FOR|UNION|EXCEPT|INTERSECT|WHERE|GROUP|HAVING|AS|FROM|INTO|LOOP|ORDER)\\b").matcher(sql.substring(i, Math.min(sql.length(), i + 12))).find())) {
                return i;
            }
        }
        return sql.length();
    }
}
