package com.finapp.app.accounting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.AccountingCollections;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersionStatus;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.PendingDecisionKind;
import com.finapp.app.api.PendingDecisions;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The accounting desks' §7 collections (`P17-TSK-023`, gap G-113; finapp-admin-panel B5.4, ADR-C133): report runs, GL mapping
 * versions and valuation rate sets, until now each opened only by an identifier someone handed over. Each a page of at most
 * {@code limit} rows (50 unless named, 200 at most), the opaque {@code cursor} of the page before; a parameter outside its
 * vocabulary or an unreadable cursor {@code 422 api.ValidationFailed}, nothing read and nothing recorded.
 *
 * <p><strong>Each list under the permission of the desk that needs it</strong> - a route takes one, and the finance roles are a
 * partition (C12, {@code RoleNameTest}'s pairwise-disjoint grants): the report runs the accountant's ({@code ACCOUNTING_READ}); the
 * FINAL statements the controller's ({@code ACCOUNTING_CLOSE_APPROVE}) - exactly what {@code FinalStatementController} serves, the
 * owner's answer to the finance roles' read question (2026-11-19: no {@code ACCOUNTING_READ} on the controller); the mapping
 * versions their approver's ({@code ACCOUNTING_MAPPING_APPROVE}, who decides them and holds no read of them); the rate sets their
 * approver's ({@code ACCOUNTING_CLOSE_APPROVE}, as the set's own read), and a proposer's own captures under
 * {@code ACCOUNTING_CLOSE_PROPOSE}. Filtered to {@code PROPOSED}, a list is its approver's queue, each row marking whether the
 * caller proposed it, so the console disables the self-approval the door would refuse ({@code accounting.SelfApprovalRefused}).
 *
 * <p><strong>One bounded snapshot per page</strong> ({@link GlSnapshots}: verified {@code REPEATABLE READ READ ONLY}, every
 * statement under its timeout): past it {@code 503 api.TemporarilyUnavailable}, nothing served and nothing recorded.
 *
 * <p><strong>The report runs' lists are recorded</strong> ({@code accounting.ReportRunsListed},
 * {@code accounting.FinalStatementsListed}), as every read of a run is - per page, with the filter, in a transaction of their own
 * after the read and before the answer. The mapping versions' and the rate sets' are not, as the versions' and the sets' own reads
 * are not.
 *
 * <p><strong>What leaves</strong>: identifiers, statuses, months, instants and counts; a run's head as its own read serves it,
 * never its content; the person who proposed, by identifier. Never a proposal's or a decision's reason - a person's prose, on the
 * item's own page - nor a rate.
 */
@RequiredArgsConstructor
public final class AccountingCollectionDesk {

    static final String REPORT_RUNS = "accounting.report-runs";
    static final String FINAL_STATEMENTS = "accounting.final-statements";
    static final String MAPPING_VERSIONS = "accounting.mapping-versions";
    static final String RATE_SETS = "accounting.valuation-rate-sets";
    static final String OWN_RATE_SETS = "accounting.valuation-rate-sets.mine";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    /** The pending-decision queues (`P17-TSK-040`): each one's cursor collection, and its item's kind. */
    static final String PENDING_MAPPING_VERSIONS = "pending.accounting.mapping-versions";
    static final String PENDING_RATE_SETS = "pending.accounting.valuation-rate-sets";
    static final String PENDING_CLOSES = "pending.accounting.close-requests";
    static final String PENDING_REOPENS = "pending.accounting.reopen-requests";
    static final String PENDING_UNLOCKS = "pending.accounting.unlock-requests";
    static final PendingDecisionKind MAPPING_VERSION_KIND = PendingDecisionKind.ACCOUNTING_MAPPING_VERSION;
    static final PendingDecisionKind RATE_SET_KIND = PendingDecisionKind.ACCOUNTING_VALUATION_RATE_SET;
    static final PendingDecisionKind CLOSE_KIND = PendingDecisionKind.ACCOUNTING_CLOSE_REQUEST;
    static final PendingDecisionKind REOPEN_KIND = PendingDecisionKind.ACCOUNTING_REOPEN_REQUEST;
    static final PendingDecisionKind UNLOCK_KIND = PendingDecisionKind.ACCOUNTING_UNLOCK_REQUEST;
    /** The word each of these five queues answers to in {@code status}: their proposals' own {@code PROPOSED}. */
    static final String PENDING_WORD = "PROPOSED";

    static final String RUNS_TARGET = "report_run_list";
    static final String STATEMENTS_TARGET = "final_statement_list";

    @NonNull private final AccountingCollections collections;
    @NonNull private final GlSnapshots snapshots;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ views

    /** A page of report runs: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReportRunPage(List<ReportRunDesk.ReportRunView> items, String nextCursor) {}

    /** A page of FINAL statements: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FinalStatementPage(List<FinalStatementHead> items, String nextCursor) {}

    /**
     * A FINAL statement's run, as a controller finds it: its definition, its month and the revisions it was read at, and when it
     * completed. The statement itself is {@code GET .../report-runs/{runId}/final-statement}.
     */
    public record FinalStatementHead(String runId, String definition, int definitionVersion, String periodId, String period,
            int periodRevision, int revision, String completedAt) {}

    /** A page of GL mapping versions: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MappingVersionPage(List<MappingVersionItem> items, String nextCursor) {}

    /**
     * One GL mapping version: its number and status, the months it covers ({@code effectiveTo} absent until it is retired), who
     * proposed it and when, when it was decided and retired, and whether the caller proposed it.
     */
    public record MappingVersionItem(String versionId, int version, String status, String effectiveFrom, String effectiveTo,
            String proposedBy, String proposedAt, String decidedAt, String retiredAt, boolean proposedByCaller) {}

    /** A page of valuation rate sets: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RateSetPage(List<RateSetItem> items, String nextCursor) {}

    /**
     * One valuation rate set: its month, status, as-of instant and age bound, how many rates it holds and how many of them lie
     * beyond the bound (a reference gap, approved only on a second factor), who captured it and when, when it was decided, and
     * whether the caller captured it. Its rates are the set's own read.
     */
    public record RateSetItem(String setId, String periodId, String period, String status, String asOf, int maxAgeHours,
            int rateCount, int referenceGaps, String capturedBy, String capturedAt, String decidedAt, boolean capturedByCaller) {}

    // ------------------------------------------------------------------ report runs (ACCOUNTING_READ)

    /** A page of report runs, newest claim first, read and recorded. */
    public ReportRunPage reportRuns(String rawPeriodId, String rawStatus, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        AccountingCollections.RunFilter filter = runFilter(rawPeriodId, rawStatus);
        Optional<AccountingCollections.RunCursor> after = runCursor(REPORT_RUNS, cursor);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<ReportRunStore.RunRow> rows = bounded(() -> snapshots.read(snapshot ->
                collections.reportRuns(snapshot, filter, after, size + 1)));
        List<ReportRunStore.RunRow> served = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size ? runNext(REPORT_RUNS, served.get(served.size() - 1)) : null;
        record(actor, correlation, AccountingAuditAction.REPORT_RUNS_LISTED, RUNS_TARGET, target(filter.period()),
                "period=" + target(filter.period()) + ", status=" + filter.status().map(Enum::name).orElse("all")
                        + ", continued=" + after.isPresent() + ", served=" + served.size());
        return new ReportRunPage(served.stream().map(ReportRunDesk::view).toList(), next);
    }

    // ------------------------------------------------------------------ FINAL statements (ACCOUNTING_CLOSE_APPROVE)

    /** A page of the runs {@code FinalStatementController} serves - FINAL statements only - newest first, read and recorded. */
    public FinalStatementPage finalStatements(String rawPeriodId, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        AccountingCollections.RunFilter filter = statementsFilter(rawPeriodId);
        Optional<AccountingCollections.RunCursor> after = runCursor(FINAL_STATEMENTS, cursor);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<ReportRunStore.RunRow> rows = bounded(() -> snapshots.read(snapshot ->
                collections.reportRuns(snapshot, filter, after, size + 1)));
        List<ReportRunStore.RunRow> served = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size ? runNext(FINAL_STATEMENTS, served.get(served.size() - 1)) : null;
        record(actor, correlation, AccountingAuditAction.FINAL_STATEMENTS_LISTED, STATEMENTS_TARGET, target(filter.period()),
                "period=" + target(filter.period()) + ", continued=" + after.isPresent() + ", served=" + served.size());
        return new FinalStatementPage(served.stream().map(AccountingCollectionDesk::statementHead).toList(), next);
    }

    /** Any definition, in any status unless one is named: the accountant's list. */
    static AccountingCollections.RunFilter runFilter(String rawPeriodId, String rawStatus) {
        return new AccountingCollections.RunFilter(period(rawPeriodId),
                Optional.ofNullable(rawStatus).map(AccountingCollectionDesk::runStatus), Set.of());
    }

    /** FINAL statements only - {@link FinalStatementDesk#STATEMENTS}, the controller's statement door's admission, and nothing else. */
    static AccountingCollections.RunFilter statementsFilter(String rawPeriodId) {
        return new AccountingCollections.RunFilter(period(rawPeriodId), Optional.of(RunStatus.FINAL),
                FinalStatementDesk.STATEMENTS);
    }

    static FinalStatementHead statementHead(ReportRunStore.RunRow run) {
        return new FinalStatementHead(run.id().value().toString(), run.key().definition(), run.key().definitionVersion(),
                run.key().periodId().value().toString(), run.period().toString(), run.periodRevision(), run.revision(),
                run.completedAt().map(Instant::toString).orElse(null));
    }

    // ------------------------------------------------------------------ mapping versions (ACCOUNTING_MAPPING_APPROVE)

    /** A page of GL mapping versions, newest first; recording nothing. */
    public MappingVersionPage mappingVersions(String rawStatus, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<GlMappingVersionStatus> status = Optional.ofNullable(rawStatus).map(AccountingCollectionDesk::versionStatus);
        Optional<Integer> after = versionCursor(cursor);
        String caller = SecurityContext.require().id();
        List<GlMappingStore.VersionRow> rows = bounded(() -> snapshots.read(snapshot ->
                collections.mappingVersions(snapshot, status, after, size + 1)));
        List<GlMappingStore.VersionRow> served = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size
                ? OperatorCollection.cursor(MAPPING_VERSIONS, Integer.toString(served.get(served.size() - 1).version()))
                : null;
        return new MappingVersionPage(served.stream().map(row -> versionItem(row, caller)).toList(), next);
    }

    static MappingVersionItem versionItem(GlMappingStore.VersionRow row, String caller) {
        return new MappingVersionItem(row.id().value().toString(), row.version(), row.status().name(),
                row.effectiveFrom().toString(), row.effectiveTo().map(Object::toString).orElse(null), row.proposedBy(),
                row.proposedAt().toString(), row.decidedAt().map(Instant::toString).orElse(null),
                row.retiredAt().map(Instant::toString).orElse(null), row.proposedBy().equals(caller));
    }

    static Optional<Integer> versionCursor(String cursor) {
        return OperatorCollection.key(MAPPING_VERSIONS, cursor, 1).map(key -> {
            try {
                int version = Integer.parseInt(key.get(0));
                if (version < 1) {
                    throw OperatorCollection.invalidCursor();
                }
                return version;
            } catch (NumberFormatException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    // ------------------------------------------------------------------ rate sets (ACCOUNTING_CLOSE_APPROVE, _PROPOSE)

    /** A page of valuation rate sets, newest capture first; recording nothing. */
    public RateSetPage rateSets(String rawPeriodId, String rawStatus, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        return sets(RATE_SETS, setFilter(rawPeriodId, rawStatus), cursor, size, SecurityContext.require().id());
    }

    /** A page of the sets the caller captured, newest first; recording nothing - the proposer's own, as the backlog names it. */
    public RateSetPage ownRateSets(String rawStatus, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        String caller = SecurityContext.require().id();
        return sets(OWN_RATE_SETS, ownSetFilter(caller, rawStatus), cursor, size, caller);
    }

    /** Every person's sets: the approver's list. */
    static AccountingCollections.SetFilter setFilter(String rawPeriodId, String rawStatus) {
        return new AccountingCollections.SetFilter(period(rawPeriodId),
                Optional.ofNullable(rawStatus).map(AccountingCollectionDesk::setStatus), Optional.empty());
    }

    /** The sets {@code caller} captured, every period's: the proposer's own. */
    static AccountingCollections.SetFilter ownSetFilter(String caller, String rawStatus) {
        return new AccountingCollections.SetFilter(Optional.empty(),
                Optional.ofNullable(rawStatus).map(AccountingCollectionDesk::setStatus), Optional.of(caller));
    }

    private RateSetPage sets(String collection, AccountingCollections.SetFilter filter, String cursor, int size, String caller) {
        Optional<AccountingCollections.SetCursor> after = setCursor(collection, cursor);
        List<ValuationRateSetStore.SetRow> rows = bounded(() -> snapshots.read(snapshot ->
                collections.rateSets(snapshot, filter, after, size + 1)));
        List<ValuationRateSetStore.SetRow> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            ValuationRateSetStore.SetRow tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(collection, micros(tail.capturedAt()), tail.id().value().toString());
        }
        return new RateSetPage(served.stream().map(row -> setItem(row, caller)).toList(), next);
    }

    static RateSetItem setItem(ValuationRateSetStore.SetRow row, String caller) {
        return new RateSetItem(row.id().value().toString(), row.period().value().toString(), row.month().toString(),
                row.status().name(), row.asOf().toString(), row.maxAgeHours(), row.rateCount(), row.referenceGaps(),
                row.capturedBy(), row.capturedAt().toString(), row.decidedAt().map(Instant::toString).orElse(null),
                row.capturedBy().equals(caller));
    }

    static Optional<AccountingCollections.SetCursor> setCursor(String collection, String cursor) {
        return OperatorCollection.key(collection, cursor, 2).map(key -> {
            try {
                return new AccountingCollections.SetCursor(instant(key.get(0)), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    // ------------------------------------------------------------------ the pending-decision queues (`P17-TSK-040`)

    /**
     * The GL mapping versions awaiting a decision, oldest first (ADR-C133 decision 3's shape); recording nothing, as the versions'
     * list does not. No subject: a version acts on nothing but itself. No reason: {@code proposal_reason} is never served.
     */
    public PendingDecisions.PendingDecisionPage pendingMappingVersions(String status, String cursor, String limit) {
        return pending(PENDING_MAPPING_VERSIONS, MAPPING_VERSION_KIND, status, cursor, limit, collections::pendingMappingVersions);
    }

    /** The valuation rate sets awaiting a decision, oldest capture first; the subject the set's period. Recording nothing. */
    public PendingDecisions.PendingDecisionPage pendingRateSets(String status, String cursor, String limit) {
        return pending(PENDING_RATE_SETS, RATE_SET_KIND, status, cursor, limit, collections::pendingRateSets);
    }

    /** The close proposals awaiting a decision, oldest first; the subject the period. Recording nothing, as the period's requests read. */
    public PendingDecisions.PendingDecisionPage pendingCloses(String status, String cursor, String limit) {
        return pending(PENDING_CLOSES, CLOSE_KIND, status, cursor, limit, collections::pendingCloses);
    }

    /** The reopen proposals awaiting a decision, oldest first; the subject the period. Recording nothing. */
    public PendingDecisions.PendingDecisionPage pendingReopens(String status, String cursor, String limit) {
        return pending(PENDING_REOPENS, REOPEN_KIND, status, cursor, limit, collections::pendingReopens);
    }

    /**
     * The unlock proposals awaiting a decision, oldest first. An unlock has no row of its own: its id and its subject are the period's,
     * and its decision doors name the period and the instant it was proposed - this item's {@code proposedAt}. Recording nothing.
     */
    public PendingDecisions.PendingDecisionPage pendingUnlocks(String status, String cursor, String limit) {
        return pending(PENDING_UNLOCKS, UNLOCK_KIND, status, cursor, limit, collections::pendingUnlocks);
    }

    /** One queue's read: the order, the limit, the status and the cursor are the shared rules' ({@link PendingDecisions}). */
    @FunctionalInterface
    interface PendingRead {
        List<AccountingCollections.PendingRow> read(Connection snapshot, Optional<AccountingCollections.PendingCursor> after, int limit);
    }

    private PendingDecisions.PendingDecisionPage pending(String collection, PendingDecisionKind kind, String status, String cursor, String rawLimit,
            PendingRead read) {
        PendingDecisions.pendingOnly(status, PENDING_WORD);
        int size = PendingDecisions.limit(rawLimit);
        Optional<AccountingCollections.PendingCursor> after = pendingCursor(collection, cursor);
        List<AccountingCollections.PendingRow> rows = bounded(() -> snapshots.read(snapshot -> read.read(snapshot, after, size + 1)));
        return PendingDecisions.page(collection, rows.stream().map(row -> PendingDecisions.of(row.id(), kind, row.proposedBy(),
                row.proposedAt(), null, row.subject().orElse(null))).toList(), size);
    }

    /** A pending queue's cursor in the store's words. */
    static Optional<AccountingCollections.PendingCursor> pendingCursor(String collection, String cursor) {
        return PendingDecisions.position(collection, cursor)
                .map(position -> new AccountingCollections.PendingCursor(position.proposedAt(), position.id()));
    }

    // ------------------------------------------------------------------ the request in the domain's words

    static Optional<AccountingCollections.RunCursor> runCursor(String collection, String cursor) {
        return OperatorCollection.key(collection, cursor, 2).map(key -> {
            try {
                return new AccountingCollections.RunCursor(instant(key.get(0)), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    private static String runNext(String collection, ReportRunStore.RunRow tail) {
        return OperatorCollection.cursor(collection, micros(tail.claimedAt()), tail.id().value().toString());
    }

    /** An instant as a cursor carries it: whole microseconds since the epoch - the column's own precision, so none is lost. */
    static String micros(Instant instant) {
        return Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, instant));
    }

    static Instant instant(String micros) {
        return Instant.EPOCH.plus(Long.parseLong(micros), ChronoUnit.MICROS);
    }

    static Optional<AccountingPeriodId> period(String raw) {
        return Optional.ofNullable(raw).map(value -> {
            try {
                return AccountingPeriodId.of(UUID.fromString(value));
            } catch (IllegalArgumentException malformed) {
                throw OperatorCollection.invalid("periodId is an accounting period's identifier.");
            }
        });
    }

    static RunStatus runStatus(String raw) {
        try {
            return RunStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("status is one of GENERATING, FINAL, PROVISIONAL, FAILED, SUPERSEDED.");
        }
    }

    static GlMappingVersionStatus versionStatus(String raw) {
        try {
            return GlMappingVersionStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("status is one of PROPOSED, ACTIVE, RETIRED, REJECTED.");
        }
    }

    static ValuationRateSetStatus setStatus(String raw) {
        try {
            return ValuationRateSetStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("status is one of PROPOSED, APPROVED, REJECTED.");
        }
    }

    private static String target(Optional<AccountingPeriodId> period) {
        return period.map(id -> id.value().toString()).orElse("all");
    }

    /** A statement past the snapshot's timeout: nothing served, nothing recorded - the caller narrows the read. */
    static <R> R bounded(Supplier<R> read) {
        try {
            return read.get();
        } catch (GlSnapshots.ReadTimedOut timedOut) {
            throw new ApiException(PlatformErrorCode.TEMPORARILY_UNAVAILABLE, "An accounting list outlived its statement timeout",
                    "the read did not finish within its bound; nothing was served - retry, or narrow the filters.");
        }
    }

    // ------------------------------------------------------------------ the audited read

    /** The page's record, {@code SUCCEEDED}, in a transaction of its own - before the answer leaves. */
    private void record(Actor actor, CorrelationId correlation, AccountingAuditAction action, String targetType,
            String targetId, String summary) {
        transactions.inTransaction(unitOfWork -> {
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), action, targetType, targetId,
                    Optional.empty(), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
            return null;
        });
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an accounting list runs inside a correlation scope"))
                .correlationId();
    }
}
