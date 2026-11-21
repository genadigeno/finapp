package com.finapp.app.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.accounting.GlSnapshots;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.PendingDecisionKind;
import com.finapp.app.api.PendingDecisions;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAuditAction;
import com.finapp.ledger.LedgerCollections;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger's §7 collections (`P17-TSK-022`, gaps G-110, G-111, G-112; finapp-admin-panel B5.1-B5.3, ADR-C132, ADR-C133): the
 * account register and the journal entry search, under {@code ACCOUNTING_READ}, and the two approvers' queues - manual
 * adjustments and projection rebuilds awaiting a second person - under {@code LEDGER_ADJUST}, the permission their doors decide
 * under. Each a page of at most {@code limit} rows (50 unless named, 200 at most), the opaque {@code cursor} of the page before;
 * a parameter outside its vocabulary, an unreadable cursor or a window out of bounds {@code 422 api.ValidationFailed}, nothing
 * read.
 *
 * <p><strong>One bounded snapshot per page</strong> ({@link GlSnapshots}: verified {@code REPEATABLE READ READ ONLY}, every
 * statement under the GL's statement timeout): a statement past it ends the read {@code 503 api.TemporarilyUnavailable} - narrow
 * the window - with nothing served and nothing recorded.
 *
 * <p><strong>The register and the search are recorded</strong> ({@code ledger.AccountsListed}, {@code ledger.EntriesSearched};
 * ADR-C132 decision 5: customer-scoped lists, per request with the filter, never per row) in a transaction of their own after the
 * read and before the answer - the {@code GeneralLedgerDesk} order: a failed record fails the read, so nothing is served
 * unrecorded. The queues are not, as the proposals' own reads beside them are not.
 *
 * <p><strong>What leaves</strong>: identifiers, classifications, statuses, dates and exact decimal amounts with their currency.
 * Never an entry's reference - matched, never served ({@code RESTRICTED-FINANCIAL}), and accepted only identifier-shaped, so free
 * text never travels in a URL (ADR-C132 decision 4) - nor a proposal's or a rebuild's reason, a person's prose: an adjustment's
 * queue summarises it by its reason code, a rebuild's by which figures drifted.
 */
@RequiredArgsConstructor
public final class LedgerCollectionDesk {

    static final String ACCOUNTS = "ledger.accounts";
    static final String ENTRIES = "ledger.entries";
    static final String ADJUSTMENTS = "ledger.adjustments";
    static final String REBUILDS = "ledger.projection-rebuilds";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;
    /** The widest window one search covers, inclusive - a month of posting dates; the window, not the journal, bounds a read. */
    static final int MAXIMUM_WINDOW_DAYS = 31;

    static final String REGISTER_TARGET = "ledger_account_register";
    static final String SEARCH_TARGET = "journal_entry_search";

    /**
     * A reference as the search matches it: an identifier - a posting's {@code transfer:<id>}, a resolution's id, an accounting
     * constant - letters, digits and {@code : . _ -}, at most the column's 200. Whitespace is refused: an operator's free text is
     * not searched, and never travels in a URL.
     */
    static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:._-]{0,199}");

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    @NonNull private final LedgerCollections collections;
    @NonNull private final GlSnapshots snapshots;
    @NonNull private final TransactionTemplate ledgerTransactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ views

    /** A page of the register: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LedgerAccountPage(List<LedgerAccountView> items, String nextCursor) {}

    /**
     * One ledger account: its purpose, its owner's kind and reference - an id, never a name; absent for the platform's own
     * accounts - its currency, its type and normal balance, its status and when it was opened (ISO-8601 UTC).
     */
    public record LedgerAccountView(String accountId, String purpose, String ownerKind, String ownerRef, String currency,
            String accountType, String normalBalance, String status, String openedAt) {}

    /** A page of the search: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JournalEntryPage(List<JournalEntryHeadView> items, String nextCursor) {}

    /**
     * One journal entry's head: its type, its posting and value dates, when it was posted (ISO-8601 UTC), the entry it reverses,
     * its line count and its movement per currency. The entry in full is {@code GET /v1/operator/accounting/gl/entries/{entryId}}.
     */
    public record JournalEntryHeadView(String entryId, String entryType, String postingDate, String valueDate, String postedAt,
            String reversesEntryId, int lineCount, List<EntryAmountView> amounts) {}

    /** An entry's debits in one currency - equal to its credits - as a decimal string at the currency's scale. */
    public record EntryAmountView(String currency, String amount) {}

    /** A page of the manual adjustments awaiting a decision: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingLedgerAdjustmentPage(List<PendingLedgerAdjustment> items, String nextCursor) {}

    /**
     * One manual adjustment awaiting a second person, in ADR-C133's pending-decision shape: its subject is the proposal itself,
     * opened at {@code GET /v1/ledger/adjustments/{id}}; the reason summary its reason code; the posting date it would land on;
     * and whether the caller proposed it, so the console disables the self-approval the door would refuse.
     */
    public record PendingLedgerAdjustment(String id, String kind, String proposedBy, String proposedAt, String reasonSummary,
            String subjectId, String subjectKind, String postingDate, boolean proposedByCaller) {}

    /** A page of the projection rebuilds awaiting a decision: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingProjectionRebuildPage(List<PendingProjectionRebuild> items, String nextCursor) {}

    /**
     * One projection rebuild awaiting a second person, in ADR-C133's pending-decision shape: its subject the ledger account whose
     * projection it would rebuild, in its currency; the reason summary which figures the proposer saw drift
     * ({@code NO_PROJECTION_ROW}, or {@code DRIFTED:} and {@code POSTED}, {@code HOLDS}, {@code APPLIED_ENTRIES}) - never the
     * proposer's prose; and whether the caller proposed it.
     */
    public record PendingProjectionRebuild(String id, String kind, String proposedBy, String proposedAt, String reasonSummary,
            String subjectId, String subjectKind, String currency, boolean proposedByCaller) {}

    // ------------------------------------------------------------------ the account register (G-110)

    /** A page of the register in {@code (purpose, currency, id)} order, read and recorded. */
    public LedgerAccountPage accounts(String rawPurpose, String rawCurrency, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        LedgerCollections.AccountFilter filter = accountFilter(rawPurpose, rawCurrency);
        Optional<LedgerCollections.AccountCursor> after = accountCursor(cursor);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<LedgerCollections.AccountRow> rows = bounded(() -> snapshots.read(snapshot ->
                collections.accounts(snapshot, filter, after, size + 1)));
        List<LedgerCollections.AccountRow> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            LedgerCollections.AccountRow tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(ACCOUNTS, tail.purpose().name(), tail.currency(), tail.id().toString());
        }
        record(actor, correlation, LedgerAuditAction.ACCOUNTS_LISTED, REGISTER_TARGET,
                filter.purpose().map(Enum::name).orElse("all"),
                "purpose=" + filter.purpose().map(Enum::name).orElse("all") + ", currency=" + filter.currency().orElse("all")
                        + ", continued=" + after.isPresent() + ", served=" + served.size());
        return new LedgerAccountPage(served.stream().map(LedgerCollectionDesk::accountView).toList(), next);
    }

    static LedgerCollections.AccountFilter accountFilter(String rawPurpose, String rawCurrency) {
        return new LedgerCollections.AccountFilter(Optional.ofNullable(rawPurpose).map(LedgerCollectionDesk::purpose),
                Optional.ofNullable(rawCurrency).map(LedgerCollectionDesk::currency));
    }

    static Optional<LedgerCollections.AccountCursor> accountCursor(String cursor) {
        return OperatorCollection.key(ACCOUNTS, cursor, 3).map(key -> {
            try {
                if (!CURRENCY.matcher(key.get(1)).matches()) {
                    throw OperatorCollection.invalidCursor();
                }
                return new LedgerCollections.AccountCursor(AccountPurpose.valueOf(key.get(0)), key.get(1),
                        UUID.fromString(key.get(2)));
            } catch (IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static LedgerAccountView accountView(LedgerCollections.AccountRow row) {
        return new LedgerAccountView(row.id().toString(), row.purpose().name(), row.ownerKind().name(),
                row.ownerRef().map(UUID::toString).orElse(null), row.currency(), row.accountType().name(),
                row.normalBalance().name(), row.status().name(), row.openedAt().toString());
    }

    // ------------------------------------------------------------------ the journal entry search (G-111)

    /** A page of the entries posted in {@code [from, to]}, newest posting date first, read and recorded. */
    public JournalEntryPage entries(String rawFrom, String rawTo, String rawReference, String rawAccountId, String cursor,
            String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        LedgerCollections.EntryFilter filter = entryFilter(rawFrom, rawTo, rawReference, rawAccountId);
        Optional<LedgerCollections.EntryCursor> after = entryCursor(cursor);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<LedgerCollections.EntryHead> rows = bounded(() -> snapshots.read(snapshot ->
                collections.entries(snapshot, filter, after, size + 1)));
        List<LedgerCollections.EntryHead> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            LedgerCollections.EntryHead tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(ENTRIES, tail.postingDate().toString(), tail.id().toString());
        }
        record(actor, correlation, LedgerAuditAction.ENTRIES_SEARCHED, SEARCH_TARGET,
                filter.account().map(id -> id.value().toString()).orElse("all"),
                "from=" + filter.from() + ", to=" + filter.to()
                        + filter.reference().map(value -> ", reference=" + value).orElse("")
                        + filter.account().map(id -> ", account=" + id.value()).orElse("")
                        + ", continued=" + after.isPresent() + ", served=" + served.size());
        return new JournalEntryPage(served.stream().map(LedgerCollectionDesk::entryView).toList(), next);
    }

    /** The search's request: the window of posting dates, required and bounded, and the identifier-shaped filters. */
    static LedgerCollections.EntryFilter entryFilter(String rawFrom, String rawTo, String rawReference, String rawAccountId) {
        LocalDate from = date("from", rawFrom);
        LocalDate to = date("to", rawTo);
        if (to.isBefore(from)) {
            throw OperatorCollection.invalid("to is on or after from: the window is the posting dates from one to the other.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAXIMUM_WINDOW_DAYS) {
            throw OperatorCollection.invalid("the window holds at most " + MAXIMUM_WINDOW_DAYS
                    + " posting dates, from and to included; search a narrower one.");
        }
        Optional<String> reference = Optional.ofNullable(rawReference).map(raw -> {
            if (!REFERENCE.matcher(raw).matches()) {
                throw OperatorCollection.invalid("reference is an identifier - letters, digits and : . _ - - matched exactly;"
                        + " free text is not searched.");
            }
            return raw;
        });
        Optional<LedgerAccountId> account = Optional.ofNullable(rawAccountId).map(raw -> {
            try {
                return LedgerAccountId.of(UUID.fromString(raw));
            } catch (IllegalArgumentException malformed) {
                throw OperatorCollection.invalid("accountId is a ledger account's identifier.");
            }
        });
        return new LedgerCollections.EntryFilter(from, to, reference, account);
    }

    static Optional<LedgerCollections.EntryCursor> entryCursor(String cursor) {
        return OperatorCollection.key(ENTRIES, cursor, 2).map(key -> {
            try {
                if (!DATE.matcher(key.get(0)).matches()) {
                    throw OperatorCollection.invalidCursor();
                }
                return new LedgerCollections.EntryCursor(LocalDate.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (IllegalArgumentException | DateTimeParseException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static JournalEntryHeadView entryView(LedgerCollections.EntryHead head) {
        return new JournalEntryHeadView(head.id().toString(), head.entryType().name(), head.postingDate().toString(),
                head.valueDate().toString(), head.postedAt().toString(), head.reverses().map(UUID::toString).orElse(null),
                head.lines(), head.amounts().stream()
                        .map(amount -> new EntryAmountView(amount.currency(), amount.amount().toPlainString())).toList());
    }

    // ------------------------------------------------------------------ the approvers' queues (G-112)

    /** A page of the manual adjustments awaiting a second person, oldest first; recording nothing. */
    public PendingLedgerAdjustmentPage pendingAdjustments(String status, String cursor, String limit) {
        int size = queue(status, limit);
        Optional<LedgerCollections.PendingCursor> after = pendingCursor(ADJUSTMENTS, cursor);
        String caller = SecurityContext.require().id();
        List<LedgerCollections.PendingAdjustment> rows = bounded(() -> snapshots.read(snapshot ->
                collections.pendingAdjustments(snapshot, after, size + 1)));
        List<LedgerCollections.PendingAdjustment> served = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size ? pendingNext(ADJUSTMENTS, served.get(served.size() - 1).proposedAt(),
                served.get(served.size() - 1).id().value()) : null;
        return new PendingLedgerAdjustmentPage(served.stream().map(row -> adjustmentView(row, caller)).toList(), next);
    }

    static PendingLedgerAdjustment adjustmentView(LedgerCollections.PendingAdjustment row, String caller) {
        String id = row.id().value().toString();
        return new PendingLedgerAdjustment(id, "ledger.ManualAdjustment", row.proposedBy(), row.proposedAt().toString(),
                row.reasonCode().name(), id, "LEDGER_ADJUSTMENT", row.postingDate().toString(), row.proposedBy().equals(caller));
    }

    /** A page of the projection rebuilds awaiting a second person, oldest first; recording nothing. */
    public PendingProjectionRebuildPage pendingRebuilds(String status, String cursor, String limit) {
        int size = queue(status, limit);
        Optional<LedgerCollections.PendingCursor> after = pendingCursor(REBUILDS, cursor);
        String caller = SecurityContext.require().id();
        List<LedgerCollections.PendingRebuild> rows = bounded(() -> snapshots.read(snapshot ->
                collections.pendingRebuilds(snapshot, after, size + 1)));
        List<LedgerCollections.PendingRebuild> served = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size ? pendingNext(REBUILDS, served.get(served.size() - 1).proposedAt(),
                served.get(served.size() - 1).id().value()) : null;
        return new PendingProjectionRebuildPage(served.stream().map(row -> rebuildView(row, caller)).toList(), next);
    }

    static PendingProjectionRebuild rebuildView(LedgerCollections.PendingRebuild row, String caller) {
        return new PendingProjectionRebuild(row.id().value().toString(), "ledger.ProjectionRebuild", row.proposedBy(),
                row.proposedAt().toString(), drift(row), row.account().value().toString(), "LEDGER_ACCOUNT", row.currency(),
                row.proposedBy().equals(caller));
    }

    /** Which figures the proposer saw drift, by name: the rebuild's reason is a person's prose and stays on its own page. */
    static String drift(LedgerCollections.PendingRebuild row) {
        if (!row.rowObserved()) {
            return "NO_PROJECTION_ROW";
        }
        List<String> drifted = new ArrayList<>();
        if (row.postedDrifted()) {
            drifted.add("POSTED");
        }
        if (row.holdsDrifted()) {
            drifted.add("HOLDS");
        }
        if (row.appliedEntriesDrifted()) {
            drifted.add("APPLIED_ENTRIES");
        }
        return drifted.isEmpty() ? "DRIFTED" : "DRIFTED:" + String.join(",", drifted);
    }

    // ------------------------------------------------------------------ the pending-decision queues (P17-TSK-040, G-140)

    /** The two queues' cursor collections, in the one pending-decision shape. */
    static final String PENDING_ADJUSTMENTS = "pending.ledger.adjustments";
    static final String PENDING_REBUILDS = "pending.ledger.projection-rebuilds";

    /**
     * {@code GET /v1/operator/pending-decisions/ledger/adjustments}: the manual adjustments awaiting a second person in the one
     * pending-decision shape - oldest first, the id the tiebreak; {@code status} {@code PROPOSED} or absent. The reason summary is
     * the reason code - the proposer's prose stays on the proposal's own page, as in {@link #pendingAdjustments}; no subject: an
     * adjustment's lines name several accounts. Recording nothing, as the proposal's own read records nothing.
     */
    public PendingDecisions.PendingDecisionPage adjustmentDecisions(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<LedgerCollections.PendingCursor> after = decisionCursor(PENDING_ADJUSTMENTS, cursor);
        List<LedgerCollections.PendingAdjustment> rows = bounded(() -> snapshots.read(snapshot ->
                collections.pendingAdjustments(snapshot, after, size + 1)));
        return PendingDecisions.page(PENDING_ADJUSTMENTS, rows.stream()
                .map(row -> PendingDecisions.of(row.id().value(), PendingDecisionKind.LEDGER_ADJUSTMENT, row.proposedBy(), row.proposedAt(),
                        row.reasonCode().name(), null))
                .toList(), size);
    }

    /**
     * {@code GET /v1/operator/pending-decisions/ledger/projection-rebuilds}: the projection rebuilds awaiting a second person in
     * the one pending-decision shape, each subject the ledger account it would rebuild. A rebuild has no reason code, so its reason
     * summary is which figures the proposer saw drift ({@link #drift}) - never the proposer's prose. Recording nothing.
     */
    public PendingDecisions.PendingDecisionPage rebuildDecisions(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<LedgerCollections.PendingCursor> after = decisionCursor(PENDING_REBUILDS, cursor);
        List<LedgerCollections.PendingRebuild> rows = bounded(() -> snapshots.read(snapshot ->
                collections.pendingRebuilds(snapshot, after, size + 1)));
        return PendingDecisions.page(PENDING_REBUILDS, rows.stream()
                .map(row -> PendingDecisions.of(row.id().value(), PendingDecisionKind.LEDGER_PROJECTION_REBUILD, row.proposedBy(), row.proposedAt(),
                        drift(row), row.account().value()))
                .toList(), size);
    }

    private static Optional<LedgerCollections.PendingCursor> decisionCursor(String collection, String cursor) {
        return PendingDecisions.position(collection, cursor)
                .map(position -> new LedgerCollections.PendingCursor(position.proposedAt(), position.id()));
    }

    static int queue(String status, String limit) {
        if (status != null && !"PROPOSED".equals(status)) {
            throw OperatorCollection.invalid("status is PROPOSED: the queue holds what awaits a decision.");
        }
        return OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
    }

    static Optional<LedgerCollections.PendingCursor> pendingCursor(String collection, String cursor) {
        return OperatorCollection.key(collection, cursor, 2).map(key -> {
            try {
                return new LedgerCollections.PendingCursor(
                        Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    private static String pendingNext(String collection, Instant proposedAt, UUID id) {
        return OperatorCollection.cursor(collection, Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, proposedAt)),
                id.toString());
    }

    // ------------------------------------------------------------------ the request in the domain's words

    static AccountPurpose purpose(String raw) {
        try {
            return AccountPurpose.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("purpose is one of the ledger's account purposes.");
        }
    }

    static String currency(String raw) {
        if (!CURRENCY.matcher(raw).matches()) {
            throw OperatorCollection.invalid("currency is an ISO 4217 code.");
        }
        try {
            return CurrencyCode.of(raw).code();
        } catch (IllegalArgumentException unsupported) {
            throw OperatorCollection.invalid("currency is an ISO 4217 code the platform supports.");
        }
    }

    static LocalDate date(String name, String raw) {
        if (raw == null) {
            throw OperatorCollection.invalid(name + " is required: a search covers a window of posting dates, YYYY-MM-DD.");
        }
        if (!DATE.matcher(raw).matches()) {
            throw OperatorCollection.invalid(name + " is a posting date, YYYY-MM-DD.");
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw OperatorCollection.invalid(name + " is a posting date, YYYY-MM-DD.");
        }
    }

    /** A statement past the snapshot's timeout: nothing served, nothing recorded - the caller narrows the read. */
    static <R> R bounded(Supplier<R> read) {
        try {
            return read.get();
        } catch (GlSnapshots.ReadTimedOut timedOut) {
            throw new ApiException(PlatformErrorCode.TEMPORARILY_UNAVAILABLE, "A ledger read outlived its statement timeout",
                    "the read did not finish within its bound; nothing was served - retry, or narrow the window or the filters.");
        }
    }

    // ------------------------------------------------------------------ the audited read

    /** The page's record, {@code SUCCEEDED}, in a transaction of its own - before the answer leaves. */
    private void record(Actor actor, CorrelationId correlation, LedgerAuditAction action, String targetType, String targetId,
            String summary) {
        ledgerTransactions.executeWithoutResult(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), action, targetType,
                        targetId, Optional.empty(), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a ledger read runs inside a correlation scope"))
                .correlationId();
    }
}
