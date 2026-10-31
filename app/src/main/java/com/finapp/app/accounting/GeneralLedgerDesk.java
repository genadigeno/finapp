package com.finapp.app.accounting;

import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlActivity;
import com.finapp.accounting.reporting.GlBalances;
import com.finapp.accounting.reporting.GlEntry;
import com.finapp.accounting.reporting.GlFigure;
import com.finapp.accounting.reporting.PinnedMapping;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The GL doors' boundary (`P14-TSK-007`; ADR-0113 decision 2; {@code PHASE_14_PLAN.md} sections 10, 12;
 * {@code INV-ACC-02}, {@code INV-ACC-06}, {@code INV-ACC-14}): parse the request exactly - a period {@code YYYY-MM}, an
 * ISO currency, a page size, a cursor, each {@code api.ValidationFailed} when malformed - then compute the answer in
 * <strong>one {@code REPEATABLE READ READ ONLY} snapshot</strong> ({@link GlSnapshots}: the period's pin, the version,
 * the journal reads), then <strong>record {@code accounting.GlRead} before answering</strong>, in a transaction of its own
 * that holds nothing but the audit row - a failed record fails the read, nothing is served unrecorded (the
 * {@code PostingRuleDesk} order). Refusals: {@code accounting.NoActiveMapping} 409 when no version is pinned by or in
 * force for the period; {@code accounting.NotFound} 404 for a GL account the version does not hold or an unknown entry;
 * {@code accounting.ReadTimedOut} 503 when a statement outlives the snapshot's {@code statement_timeout} - nothing served,
 * nothing recorded.
 *
 * <p>What leaves: GL figures and journal lines with their owner <em>references</em> (UUIDs) and counterparty codes - never
 * a name, never the entry's {@code reference} (free text on a {@code MANUAL} adjustment), reason or actor. The audit
 * summary carries the door, the period, the currency, the version and a count - never an amount or an owner reference
 * ({@code INV-AUD-02}).
 */
public final class GeneralLedgerDesk {

    static final String BALANCES_SPAN = "accounting.gl.balances";
    static final String ACTIVITY_SPAN = "accounting.gl.activity";
    static final String ENTRY_SPAN = "accounting.gl.entry";

    /** The audit record's target types. */
    static final String PERIOD_TARGET = "gl_period";
    static final String ACCOUNT_TARGET = "gl_account";
    static final String ENTRY_TARGET = "journal_entry";

    /** A drill-down page's default and largest size. */
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = JournalReads.LineQuery.MAX_LIMIT;

    private static final Pattern PERIOD = Pattern.compile("[0-9]{4}-[0-9]{2}");

    private final GeneralLedger ledger;
    private final GlPins pins;
    private final GlSnapshots snapshots;
    private final AccountingTransactions transactions;
    private final AuditWriter<Connection> audit;
    private final AccountingFlowScope flow;
    private final IdGenerator ids;
    private final Clock clock;

    public GeneralLedgerDesk(GeneralLedger ledger, GlPins pins, GlSnapshots snapshots,
            AccountingTransactions transactions, AuditWriter<Connection> audit, AccountingFlowScope flow,
            IdGenerator ids, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A period's GL - every figure with movement, the unmapped lines, GL against ledger per currency. */
    public GlBalancesView balances(String rawPeriod, String rawCurrency) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        YearMonth period = period(rawPeriod);
        Optional<String> currency = rawCurrency == null ? Optional.empty() : Optional.of(currency(rawCurrency));
        GlBalances balances = guarded(() -> flow.door(BALANCES_SPAN, () -> snapshots.read(snapshot ->
                ledger.balances(snapshot, pins.forPeriod(snapshot, period), currency))));
        record(actor, correlation, PERIOD_TARGET, period.toString(), "door=balances, period=" + period + ", currency="
                + currency.orElse("ALL") + ", version=" + balances.mapping().version() + ", figures="
                + balances.figures().size() + ", unmapped=" + balances.unmapped().size());
        return GlBalancesView.of(balances);
    }

    /** One GL account's activity in a currency: its figure and one keyset page of its lines. */
    public GlActivityView activity(String rawCode, String rawPeriod, String rawCurrency, String rawAfter,
            Integer rawLimit) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String code = code(rawCode);
        YearMonth period = period(rawPeriod);
        if (rawCurrency == null) {
            throw invalid("a GL account's activity is read in one currency");
        }
        String currency = currency(rawCurrency);
        Optional<JournalReads.LineCursor> after = after(rawAfter, period);
        int limit = limit(rawLimit);
        GlActivity activity = guarded(() -> flow.door(ACTIVITY_SPAN, () -> snapshots.read(snapshot ->
                ledger.activity(snapshot, pins.forPeriod(snapshot, period), code, currency, after, limit))));
        record(actor, correlation, ACCOUNT_TARGET, code, "door=activity, period=" + period + ", currency=" + currency
                + ", version=" + activity.mapping().version() + ", lines=" + activity.lines().size() + ", page="
                + (after.isPresent() ? "next" : "first"));
        return GlActivityView.of(activity);
    }

    /** One journal entry's GL view, under its posting month's version. */
    public GlEntryView entry(String rawEntryId) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        UUID entryId = entryId(rawEntryId);
        GlEntry entry = guarded(() -> flow.door(ENTRY_SPAN, () -> snapshots.read(snapshot -> {
            JournalReads.Entry found = ledger.findEntry(snapshot, entryId).orElseThrow(GeneralLedgerDesk::notFound);
            return ledger.entry(pins.forPeriod(snapshot, YearMonth.from(found.postingDate())), found);
        })));
        record(actor, correlation, ENTRY_TARGET, entryId.toString(), "door=entry, period=" + entry.mapping().period()
                + ", version=" + entry.mapping().version() + ", lines=" + entry.lines().size());
        return GlEntryView.of(entry);
    }

    // ------------------------------------------------------------------ the audited read

    /** {@code accounting.GlRead}, SUCCEEDED, in a transaction of its own - before the answer leaves. */
    private void record(Actor actor, CorrelationId correlation, String targetType, String targetId, String summary) {
        transactions.inTransaction(unitOfWork -> {
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    AccountingAuditAction.GL_READ, targetType, targetId, Optional.empty(), AuditOutcome.SUCCEEDED,
                    correlation, Optional.of(summary)));
            return null;
        });
    }

    // ------------------------------------------------------------------ the request in the domain's words

    static YearMonth period(String raw) {
        if (raw == null || !PERIOD.matcher(raw).matches()) {
            throw invalid("the period is a calendar month, YYYY-MM");
        }
        try {
            return YearMonth.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw invalid("the period is a calendar month, YYYY-MM");
        }
    }

    static String currency(String raw) {
        try {
            return CurrencyCode.of(raw).code();
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw invalid("the currency is an ISO 4217 code with a minor unit");
        }
    }

    static int limit(Integer raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        if (raw < 1 || raw > MAX_LIMIT) {
            throw invalid("a drill-down page holds 1 to " + MAX_LIMIT + " lines");
        }
        return raw;
    }

    /**
     * The cursor, which must be a position inside {@code period}'s posting-date range: a forged or stale cursor - another
     * month, a year far outside the calendar - is a 422, never a read and never a 5xx.
     */
    static Optional<JournalReads.LineCursor> after(String raw, YearMonth period) {
        if (raw == null) {
            return Optional.empty();
        }
        JournalReads.LineCursor cursor;
        try {
            cursor = JournalReads.LineCursor.parse(raw);
        } catch (IllegalArgumentException malformed) {
            throw invalid("after is the next cursor of a previous page");
        }
        if (!JournalReads.Range.of(period).holds(cursor.postingDate())) {
            throw invalid("after is a cursor of this period's drill-down");
        }
        return Optional.of(cursor);
    }

    private static String code(String raw) {
        if (raw == null || !GlAccount.CODE.matcher(raw).matches()) {
            throw notFound();
        }
        return raw;
    }

    private static UUID entryId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    /** The domain's refusals, in the API's words. */
    static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (GlPins.NoActiveMapping none) {
            throw new ApiException(AccountingErrorCode.NO_ACTIVE_MAPPING, "The GL cannot be read for the period",
                    none.getMessage());
        } catch (GeneralLedger.GlAccountNotFound unknown) {
            throw new ApiException(AccountingErrorCode.NOT_FOUND, "No GL account answers the request",
                    unknown.getMessage());
        } catch (GlSnapshots.ReadTimedOut timedOut) {
            throw new ApiException(AccountingErrorCode.READ_TIMED_OUT, "A GL read outlived its statement timeout",
                    "the read did not finish within its bound; nothing was served - retry, or narrow it (one currency).");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "A GL read was requested with an invalid parameter",
                detail);
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No GL record answers the request", "no such record.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a GL read runs inside a correlation scope"))
                .correlationId();
    }

    private static String amount(BigDecimal value) {
        return value.toPlainString();
    }

    // ------------------------------------------------------------------ the views

    /**
     * A period's GL: the period and its accounting period's status ({@code NONE} when no period row exists yet - an open
     * month's figures still move; a {@code LOCKED} or {@code CLOSED} period's cannot), the mapping version every figure
     * is computed under, the figures, the unmapped lines and the agreement with the ledger per currency.
     */
    public record GlBalancesView(
            String period,
            String periodStatus,
            String mappingVersionId,
            int mappingVersion,
            String currency,
            List<GlFigureView> figures,
            List<GlUnmappedView> unmapped,
            List<GlAgreementView> agreement,
            boolean agrees) {

        static GlBalancesView of(GlBalances balances) {
            PinnedMapping mapping = balances.mapping();
            return new GlBalancesView(mapping.period().toString(), status(mapping), mapping.versionId().value().toString(),
                    mapping.version(), balances.currency().orElse(null),
                    balances.figures().stream().map(GlFigureView::of).toList(),
                    balances.unmapped().stream().map(GlUnmappedView::of).toList(),
                    balances.agreement().stream().map(GlAgreementView::of).toList(), balances.agrees());
        }
    }

    /**
     * One GL figure: the account, the currency, the period's debits and credits, the net on the account's normal side
     * ({@code netMovement} - the period's movement, not a cumulative balance), the line count, its components by purpose, owner kind and counterparty, and the mapping version
     * it is computed under.
     */
    public record GlFigureView(
            String glAccountCode,
            String name,
            String classification,
            String section,
            String normalBalance,
            String currency,
            String debits,
            String credits,
            String netMovement,
            long lines,
            List<GlComponentView> components,
            String mappingVersionId,
            int mappingVersion) {

        static GlFigureView of(GlFigure figure) {
            GlAccount account = figure.account();
            return new GlFigureView(account.code(), account.name(), account.classification().name(),
                    account.section().name(), account.normalBalance(), figure.currency(), amount(figure.debits()),
                    amount(figure.credits()), amount(figure.netMovement()), figure.lines(),
                    figure.components().stream().map(GlComponentView::of).toList(),
                    figure.versionId().value().toString(), figure.version());
        }
    }

    /** One ledger purpose and owner kind (and counterparty, a dimension) folded into a figure. */
    public record GlComponentView(
            String purpose, String ownerKind, String counterparty, String debits, String credits, long lines) {

        static GlComponentView of(GlFigure.Component component) {
            return new GlComponentView(component.purpose(), component.ownerKind(), component.counterparty().orElse(null),
                    amount(component.debits()), amount(component.credits()), component.lines());
        }
    }

    /** Journal lines of a purpose and owner kind no rule of the version maps - shown, never dropped. */
    public record GlUnmappedView(
            String purpose, String ownerKind, String currency, String debits, String credits, long lines) {

        static GlUnmappedView of(GlBalances.Unmapped unmapped) {
            return new GlUnmappedView(unmapped.purpose(), unmapped.ownerKind(), unmapped.currency(),
                    amount(unmapped.debits()), amount(unmapped.credits()), unmapped.lines());
        }
    }

    /** GL against the ledger in one currency over the period: the mapped, the unmapped and the ledger's own totals. */
    public record GlAgreementView(
            String currency,
            String glDebits,
            String glCredits,
            long glLines,
            String unmappedDebits,
            String unmappedCredits,
            long unmappedLines,
            String ledgerDebits,
            String ledgerCredits,
            long ledgerLines,
            boolean agrees) {

        static GlAgreementView of(GlBalances.Agreement agreement) {
            return new GlAgreementView(agreement.currency(), amount(agreement.glDebits()), amount(agreement.glCredits()),
                    agreement.glLines(), amount(agreement.unmappedDebits()), amount(agreement.unmappedCredits()),
                    agreement.unmappedLines(), amount(agreement.ledgerDebits()), amount(agreement.ledgerCredits()),
                    agreement.ledgerLines(), agreement.agrees());
        }
    }

    /** A GL account's activity: its figure and one page of its journal lines; {@code next} the cursor of the next page. */
    public record GlActivityView(
            String period,
            String periodStatus,
            String mappingVersionId,
            int mappingVersion,
            GlFigureView figure,
            List<GlLineView> lines,
            String next) {

        static GlActivityView of(GlActivity activity) {
            PinnedMapping mapping = activity.mapping();
            return new GlActivityView(mapping.period().toString(), status(mapping),
                    mapping.versionId().value().toString(), mapping.version(), GlFigureView.of(activity.figure()),
                    activity.lines().stream().map(line -> GlLineView.of(line, null)).toList(),
                    activity.next().map(JournalReads.LineCursor::encoded).orElse(null));
        }
    }

    /**
     * One journal line: its entry and order, the entry's dates and type, its ledger account, that account's purpose,
     * owner kind and owner reference (never a name) and counterparty code, its side and amount - and, in an entry's view,
     * the GL account it maps to ({@code UNMAPPED} when no rule maps it).
     */
    public record GlLineView(
            String entryId,
            int seq,
            String postingDate,
            String valueDate,
            String entryType,
            String ledgerAccountId,
            String purpose,
            String ownerKind,
            String ownerRef,
            String counterparty,
            String side,
            String amount,
            String currency,
            String glAccountCode) {

        static GlLineView of(JournalReads.Line line, String glAccountCode) {
            return new GlLineView(line.entryId().toString(), line.seq(), line.postingDate().toString(),
                    line.valueDate().toString(), line.entryType(), line.ledgerAccountId().toString(), line.purpose(),
                    line.ownerKind(), line.ownerRef().map(UUID::toString).orElse(null), line.counterparty().orElse(null),
                    line.side().name(), GeneralLedgerDesk.amount(line.amount()), line.currency(), glAccountCode);
        }
    }

    /** One journal entry's GL view: the entry and each line with the GL account it maps to under its month's version. */
    public record GlEntryView(
            String entryId,
            String entryType,
            String postingDate,
            String valueDate,
            String reversesEntryId,
            String period,
            String periodStatus,
            String mappingVersionId,
            int mappingVersion,
            List<GlLineView> lines) {

        static GlEntryView of(GlEntry view) {
            JournalReads.Entry entry = view.entry();
            PinnedMapping mapping = view.mapping();
            return new GlEntryView(entry.id().toString(), entry.entryType(), entry.postingDate().toString(),
                    entry.valueDate().toString(), entry.reverses().map(UUID::toString).orElse(null),
                    mapping.period().toString(), status(mapping), mapping.versionId().value().toString(),
                    mapping.version(), view.lines().stream().map(mapped -> GlLineView.of(mapped.line(),
                            mapped.glAccountCode().orElse("UNMAPPED"))).toList());
        }
    }

    private static String status(PinnedMapping mapping) {
        return mapping.periodStatus().map(PeriodStatus::name).orElse("NONE");
    }
}
