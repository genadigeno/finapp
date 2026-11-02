package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.CheckOutcome;
import com.finapp.accounting.FinalTrialBalance;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link FinalTrialBalance} over the report run machine (`P14-TSK-011`; `P14-TSK-009`'s review, M-6; {@code PHASE_14_PLAN.md} section 13.5):
 * the period's run claimed - {@link TrialBalanceDefinition#CODE}, {@code RunParameters.none()}, in a short transaction - generated when
 * this call holds it, or read as it stands. <strong>Code, version and verdict path are {@link TrialBalanceDefinition}'s constants</strong>; the
 * run must be {@code FINAL}, of that code and that version, on the {@code FINAL} basis, with its hash stored, before a member of its content
 * is read, and each member is read from the frozen content by SQL on a read-only snapshot: exactly {@code true} or false.
 *
 * <p><strong>Failures, sorted</strong> (`P14-TSK-008`'s review, M-7): a run another holder generates is {@link InProgress}, deferred by the
 * caller, no thread held; a run {@code FAILED} is {@code REPORT_RUN_FAILED:<code>}, whatever the definition's failure was; a run not FINAL
 * or of another version is {@code REPORT_RUN_NOT_FINAL}; anything thrown - the snapshot's own failure, which leaves the run {@code GENERATING}
 * for the orphan leg - is {@code UNAVAILABLE:TRIAL_BALANCE}, with its class logged. No new {@code RunFailure} code: a database failure
 * inside the definition reads {@code DEFINITION_FAILED} on the run, a new revision allowed; the check fails closed in every one of them.
 */
@Slf4j
@RequiredArgsConstructor
public final class FinalTrialBalanceReader implements FinalTrialBalance {

    /** The check's failure when the run is not the FINAL one of this definition. */
    public static final String NOT_FINAL = "REPORT_RUN_NOT_FINAL";

    /** The prefix of a run that failed, with its {@code RunFailure}. */
    public static final String RUN_FAILED = "REPORT_RUN_FAILED:";

    /** The check's failure when the run could not be claimed, generated or read. */
    public static final String UNAVAILABLE = CheckOutcome.UNAVAILABLE + "TRIAL_BALANCE";

    /** The verdict's members, all from one cast of the content - {@code content::jsonb #>> path}. */
    static final String VERDICT_SQL = "SELECT verdict.content #>> ?::text[], verdict.content #>> ?::text[],"
            + " verdict.content #>> ?::text[], verdict.content #>> ?::text[], verdict.content #>> ?::text[]"
            + " FROM (SELECT content::jsonb AS content FROM accounting.report_run WHERE id = ?) verdict";

    /** The verdict's member the definition names - the check's. */
    static final String PASSES = "passes";

    /** The members read, {@link #PASSES} first; the legs by the name the content holds. */
    static final List<String> MEMBERS = List.of(PASSES, "openingBalanced", "periodBalanced", "balanced", "fullyMapped");

    /** Reads the verdict's members from a run's stored content; a member absent is absent. */
    @FunctionalInterface
    public interface VerdictReads {
        Map<String, String> read(ReportRunId run);
    }

    @NonNull private final ReportRuns runs;
    @NonNull private final ReportRunGenerator generator;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final VerdictReads verdicts;

    /** {@code member}'s path: {@link TrialBalanceDefinition#VERDICT_PATH}, its last segment replaced - the constant's own shape. */
    static String path(String member) {
        String verdict = TrialBalanceDefinition.VERDICT_PATH;
        if (!verdict.endsWith("," + PASSES + "}")) {
            throw new IllegalStateException("the trial balance's verdict moved: " + verdict);
        }
        return verdict.substring(0, verdict.length() - (PASSES.length() + 1)) + member + "}";
    }

    @Override
    public Reading read(AccountingPeriodId period, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        try {
            ReportRuns.Claimed claimed = transactions.inTransaction(uow -> runs.claim(uow, TrialBalanceDefinition.CODE, period,
                    RunParameters.none(), actor, correlation));
            ReportRunStore.RunRow run = claimed.mine() ? generator.generateAndComplete(claimed.run(), actor) : claimed.run();
            if (run.status() == RunStatus.GENERATING) {
                return new InProgress();
            }
            if (run.status() == RunStatus.FAILED) {
                return new Unavailable(RUN_FAILED + run.failure().map(Enum::name).orElse("UNKNOWN"));
            }
            if (run.status() != RunStatus.FINAL || run.basis() != RunBasis.FINAL
                    || !TrialBalanceDefinition.CODE.equals(run.definition())
                    || run.definitionVersion() != TrialBalanceDefinition.VERSION || run.contentSha256().isEmpty()) {
                return new Unavailable(NOT_FINAL);
            }
            Map<String, String> members = verdicts.read(run.id());
            return new Verdict(run.id().value().toString(), run.revision(), run.definitionVersion(),
                    run.contentSha256().get(), isTrue(members, PASSES), isTrue(members, "openingBalanced"),
                    isTrue(members, "periodBalanced"), isTrue(members, "balanced"), isTrue(members, "fullyMapped"));
        } catch (RuntimeException unreadable) {
            log.warn("The close could not read the FINAL trial balance - its checks are FAILED: {}",
                    unreadable.getClass().getName());
            return new Unavailable(UNAVAILABLE);
        }
    }

    private static boolean isTrue(Map<String, String> members, String member) {
        return "true".equals(members.get(member));
    }

    /** The verdict's members, by SQL on one read-only snapshot - the content is not loaded into this JVM. */
    public static VerdictReads overSnapshots(ReportSnapshots snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        return run -> snapshots.read(snapshot -> read(snapshot, run));
    }

    private static Map<String, String> read(Connection snapshot, ReportRunId run) {
        try (PreparedStatement read = snapshot.prepareStatement(VERDICT_SQL)) {
            int at = 1;
            for (String member : MEMBERS) {
                read.setString(at++, path(member));
            }
            read.setObject(at, run.value());
            Map<String, String> members = new HashMap<>();
            try (ResultSet row = read.executeQuery()) {
                if (row.next()) {
                    for (int member = 0; member < MEMBERS.size(); member++) {
                        members.put(MEMBERS.get(member), row.getString(member + 1));
                    }
                }
            }
            return members;
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading a trial balance's verdict", failure));
        }
    }
}
