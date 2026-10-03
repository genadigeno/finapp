package com.finapp.app.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.reconciliation.BreakStatus;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ItemStatus;
import com.finapp.reconciliation.ReconciliationAuditAction;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.reconciliation.Severity;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.settlement.LineDirection;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementLineType;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The four remaining reconciliation reports (`P8-TSK-024`), built to ADR-0072 §2, §3 and §6:
 * {@code suspense}, {@code unmatched}, {@code summary} and {@code provider-costs}. Each is ONE
 * {@code REPEATABLE READ} transaction on one connection, taking no row lock, composed here in
 * {@code app} from the owning modules' read APIs — reconciliation's {@link ReportReadings} and
 * {@link PositionProof}, settlement's batch, totals and source reads, the ledger's entry and
 * chart reads; no cross-schema SQL — and each serving's {@code reconciliation.ReportRead} record
 * commits in that same transaction, so a read that cannot be put on the record returns nothing
 * (the {@code payments.ChargebackRatioRead} precedent). The audit names the report, the period
 * and the row count: never an amount, never a counterparty, and no reason is asked for.
 *
 * <p>Every figure is folded here with {@code Money}, per currency and never across currencies
 * ({@code INV-MON-04}), never by a SQL {@code SUM}; directions that netting would hide are kept
 * apart (suspense CREDIT and DEBIT gross, unmatched INBOUND and OUTBOUND). Amounts render as
 * plain decimals beside their currency. Rows are bounded at {@link #BOUND}, worst first, with a
 * {@code truncated} flag per list; totals are folded over EVERY row, streamed, never retained. A
 * period is validated before anything is read: an invalid one is the chargeback-ratio report's
 * 422 and writes nothing, the audit included. Rows carry identifiers, codes, states, dates,
 * counts and amounts — never a counterparty reference, a note, a narrative or a file byte.
 */
@RequiredArgsConstructor
public class ReconciliationReports {

    /** The rows' bound — the rule every Phase 8 operator list follows (ADR-0072 §2). */
    static final int BOUND = 100;

    /** The earliest reportable business date — the chargeback-ratio report's 2000-01. */
    static final LocalDate EARLIEST_REPORT_DATE = LocalDate.of(2000, 1, 1);

    /** The earliest reportable month. */
    static final YearMonth EARLIEST_REPORT_MONTH = YearMonth.of(2000, 1);

    /** The open-break ageing bands, in days (ADR-0072 §3's summary row). */
    static final List<String> AGEING_BANDS = List.of("0-2", "3-7", "8-30", ">30");

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern MONTH = Pattern.compile("[0-9]{4}-[0-9]{2}");
    private static final String TARGET_TYPE = "reconciliation_report";
    private static final String UNKNOWN_SOURCE = "unknown";

    @NonNull private final ReportReadings readings;
    @NonNull private final PositionProof proof;
    @NonNull private final SettlementBatchStore<Connection> batches;
    @NonNull private final SettlementFileStore<Connection> sourceRows;
    @NonNull private final SettlementSources sources;
    @NonNull private final JournalEntryStore<Connection> entries;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionTemplate reconciliationTransactions;
    @NonNull private final DataSource dataSource;

    // ================================================================= the suspense report

    /**
     * One open suspense item: our identifiers, its origin and side, the unreleased remainder in
     * its own currency, its age in whole days from {@code opened_on}, and its owning break's
     * type and severity.
     */
    public record SuspenseReportItem(
            String id,
            String origin,
            String side,
            String unreleased,
            String currency,
            String openedOn,
            long ageDays,
            String breakId,
            String breakType,
            String breakSeverity) {}

    /**
     * One currency of {@code SUSPENSE_UNMATCHED}: the ledger balance (CR−DR), the items
     * remaining CREDIT and DEBIT gross — never netted — the Phase 7 parkings no item owns yet,
     * and the suspense proof's verdict (ADR-0070 §7). {@code ledgerBalance},
     * {@code unadoptedParkings} and {@code explained} are absent for a currency with no seeded
     * suspense account: its items are shown, the proof has nothing to judge them against.
     */
    public record SuspenseReportCurrency(
            String currency,
            String ledgerBalance,
            String creditUnreleased,
            long creditItems,
            String debitUnreleased,
            long debitItems,
            String unadoptedParkings,
            Boolean explained) {}

    /** The suspense report: per currency, then the oldest items, bounded. */
    public record SuspenseReport(
            List<SuspenseReportCurrency> currencies,
            List<SuspenseReportItem> items,
            boolean truncated) {}

    /**
     * The suspense report (ADR-0072 §3): per currency the {@code SUSPENSE_UNMATCHED} balance,
     * the CREDIT and DEBIT items gross and the suspense proof's verdict — the positions report's
     * own sweep, in the same snapshot — and every open item, oldest first, bounded.
     */
    public SuspenseReport suspense() {
        return inOneTransaction(
                unitOfWork -> {
                    PositionProof.Report swept = proof.sweep(unitOfWork);
                    SuspenseFold fold = new SuspenseFold(today());
                    readings.eachOpenSuspenseItem(unitOfWork, fold);
                    SuspenseReport report = fold.report(swept.suspenseVerdicts());
                    recordServing(unitOfWork, "suspense", "none",
                            report.currencies().size() + report.items().size());
                    return report;
                });
    }

    /**
     * The suspense fold: keeps the first {@link #BOUND} rows of an oldest-first stream and folds
     * EVERY row into per-currency CREDIT and DEBIT totals, gross.
     */
    static final class SuspenseFold implements Consumer<ReportReadings.OpenSuspenseLine> {

        private final LocalDate today;
        private final List<SuspenseReportItem> rows = new ArrayList<>();
        private final Map<String, Sum> credit = new TreeMap<>();
        private final Map<String, Sum> debit = new TreeMap<>();
        private long seen;

        SuspenseFold(LocalDate today) {
            this.today = today;
        }

        @Override
        public void accept(ReportReadings.OpenSuspenseLine line) {
            seen++;
            if (rows.size() < BOUND) {
                rows.add(
                        new SuspenseReportItem(
                                line.id().toString(),
                                line.origin().name(),
                                line.side().name(),
                                rendered(line.unreleased()),
                                line.unreleased().currency().code(),
                                line.openedOn().toString(),
                                ChronoUnit.DAYS.between(line.openedOn(), today),
                                line.breakId().toString(),
                                line.owner().map(owner -> owner.type().name()).orElse(null),
                                line.owner()
                                        .map(owner -> owner.severity().name())
                                        .orElse(null)));
            }
            String currency = line.unreleased().currency().code();
            (line.side() == SuspenseSide.CREDIT ? credit : debit)
                    .computeIfAbsent(currency, absent -> new Sum())
                    .add(line.unreleased());
        }

        SuspenseReport report(List<PositionProof.SuspenseVerdict> verdicts) {
            Map<String, PositionProof.SuspenseVerdict> proven = new TreeMap<>();
            verdicts.forEach(verdict -> proven.put(verdict.currency().code(), verdict));
            Set<String> currencies = new java.util.TreeSet<>(proven.keySet());
            currencies.addAll(credit.keySet());
            currencies.addAll(debit.keySet());
            List<SuspenseReportCurrency> perCurrency = new ArrayList<>();
            for (String code : currencies) {
                CurrencyCode currency = CurrencyCode.of(code);
                Optional<PositionProof.SuspenseVerdict> verdict =
                        Optional.ofNullable(proven.get(code));
                Sum credits = credit.getOrDefault(code, new Sum());
                Sum debits = debit.getOrDefault(code, new Sum());
                perCurrency.add(
                        new SuspenseReportCurrency(
                                code,
                                verdict.map(v -> rendered(v.ledgerBalance())).orElse(null),
                                rendered(credits.orZero(currency)),
                                credits.count(),
                                rendered(debits.orZero(currency)),
                                debits.count(),
                                verdict.map(v -> rendered(v.unadoptedParkings())).orElse(null),
                                verdict.map(PositionProof.SuspenseVerdict::explained)
                                        .orElse(null)));
            }
            return new SuspenseReport(
                    bounded(perCurrency), List.copyOf(rows),
                    seen > BOUND || perCurrency.size() > BOUND);
        }
    }

    // ================================================================= the unmatched report

    /** A count, its value and the oldest member's age in days (absent when the count is 0). */
    public record UnmatchedFigure(long count, String value, Long oldestAgeDays) {}

    /**
     * One source, currency and direction's unexplained value (ADR-0072 §3): external items
     * {@code UNMATCHED} inside grace and {@code PARKED} with a break, and open expectations
     * inside their window and overdue — each with count, value and oldest age.
     */
    public record UnmatchedReportGroup(
            String source,
            String currency,
            String direction,
            UnmatchedFigure unmatchedItems,
            UnmatchedFigure parkedItems,
            UnmatchedFigure expectationsInWindow,
            UnmatchedFigure expectationsOverdue) {}

    /** The open breaks' value at issue for one type, severity and currency. */
    public record UnmatchedReportBreaks(
            String type, String severity, String currency, long count, String valueAtIssue) {}

    /**
     * One external item holding unexplained value — an {@code UNMATCHED} remainder inside its
     * grace, or a {@code PARKED} item's parked value — and the worst open break on it, if any.
     */
    public record UnmatchedReportItem(
            String id,
            String source,
            String lineType,
            String direction,
            String status,
            String value,
            String currency,
            String businessDate,
            long ageDays,
            String graceUntil,
            String breakId,
            String breakType,
            String breakSeverity) {}

    /** The unmatched report: groups, the open breaks' value at issue, the oldest items. */
    public record UnmatchedReport(
            List<UnmatchedReportGroup> groups,
            boolean groupsTruncated,
            List<UnmatchedReportBreaks> openBreaks,
            boolean openBreaksTruncated,
            List<UnmatchedReportItem> items,
            boolean truncated) {}

    /**
     * The unmatched report (ADR-0072 §3): per source, currency and direction the unexplained
     * items and the open expectations, each split and counted with value and oldest age; the
     * open breaks' value at issue by type and severity, CRITICAL first; and the oldest items.
     */
    public UnmatchedReport unmatched() {
        return inOneTransaction(
                unitOfWork -> {
                    UnmatchedFold fold = new UnmatchedFold(sourceCodes(unitOfWork), today());
                    readings.eachUnexplainedItem(unitOfWork, fold::item);
                    readings.eachOpenExpectation(unitOfWork, fold::expectation);
                    readings.eachOpenBreak(unitOfWork, fold::openBreak);
                    UnmatchedReport report = fold.report();
                    recordServing(unitOfWork, "unmatched", "none",
                            report.groups().size() + report.openBreaks().size()
                                    + report.items().size());
                    return report;
                });
    }

    /** The unmatched fold: groups and break groups over every row, the first rows kept. */
    static final class UnmatchedFold {

        private record GroupKey(String source, String currency, ExpectationDirection direction) {}

        private record BreakKey(Severity severity, BreakType type, String currency) {}

        private static final class Group {
            private final Figure unmatched = new Figure();
            private final Figure parked = new Figure();
            private final Figure inWindow = new Figure();
            private final Figure overdue = new Figure();
        }

        private final Map<UUID, String> codes;
        private final LocalDate today;
        private final List<UnmatchedReportItem> rows = new ArrayList<>();
        private final Map<GroupKey, Group> groups =
                new TreeMap<>(
                        Comparator.comparing(GroupKey::source)
                                .thenComparing(GroupKey::currency)
                                .thenComparing(GroupKey::direction));
        // Worst first: CRITICAL before LOW, then the type and the currency.
        private final Map<BreakKey, Sum> breaks =
                new TreeMap<>(
                        Comparator.comparing(BreakKey::severity, Comparator.reverseOrder())
                                .thenComparing(BreakKey::type)
                                .thenComparing(BreakKey::currency));
        private long seen;

        UnmatchedFold(Map<UUID, String> codes, LocalDate today) {
            this.codes = codes;
            this.today = today;
        }

        void item(ReportReadings.UnexplainedItemLine line) {
            seen++;
            String source = codeOf(codes, line.sourceId());
            if (rows.size() < BOUND) {
                rows.add(
                        new UnmatchedReportItem(
                                line.id().toString(),
                                source,
                                line.lineType().name(),
                                line.direction().name(),
                                line.status().name(),
                                rendered(line.value()),
                                line.value().currency().code(),
                                line.businessDate().toString(),
                                ChronoUnit.DAYS.between(line.businessDate(), today),
                                line.graceUntil().map(Instant::toString).orElse(null),
                                line.openBreak().map(open -> open.id().toString()).orElse(null),
                                line.openBreak().map(open -> open.type().name()).orElse(null),
                                line.openBreak()
                                        .map(open -> open.severity().name())
                                        .orElse(null)));
            }
            Group group = groupOf(source, line.value(), line.direction());
            (line.status() == ItemStatus.PARKED ? group.parked : group.unmatched)
                    .add(line.value(), line.businessDate());
        }

        void expectation(ReportReadings.OpenExpectationLine line) {
            Group group =
                    groupOf(codeOf(codes, line.sourceId()), line.remainder(), line.direction());
            (line.overdueSince().isPresent() ? group.overdue : group.inWindow)
                    .add(line.remainder(), line.postingDate());
        }

        void openBreak(ReportReadings.BreakLine line) {
            breaks.computeIfAbsent(
                            new BreakKey(
                                    line.severity(), line.type(),
                                    line.valueAtIssue().currency().code()),
                            absent -> new Sum())
                    .add(line.valueAtIssue());
        }

        private Group groupOf(String source, Money value, ExpectationDirection direction) {
            return groups.computeIfAbsent(
                    new GroupKey(source, value.currency().code(), direction),
                    absent -> new Group());
        }

        UnmatchedReport report() {
            List<UnmatchedReportGroup> grouped = new ArrayList<>();
            groups.forEach(
                    (key, group) -> {
                        CurrencyCode currency = CurrencyCode.of(key.currency());
                        grouped.add(
                                new UnmatchedReportGroup(
                                        key.source(),
                                        key.currency(),
                                        key.direction().name(),
                                        group.unmatched.render(currency, today),
                                        group.parked.render(currency, today),
                                        group.inWindow.render(currency, today),
                                        group.overdue.render(currency, today)));
                    });
            List<UnmatchedReportBreaks> breakGroups = new ArrayList<>();
            breaks.forEach(
                    (key, sum) ->
                            breakGroups.add(
                                    new UnmatchedReportBreaks(
                                            key.type().name(),
                                            key.severity().name(),
                                            key.currency(),
                                            sum.count(),
                                            rendered(sum.orZero(
                                                    CurrencyCode.of(key.currency()))))));
            return new UnmatchedReport(
                    bounded(grouped),
                    grouped.size() > BOUND,
                    bounded(breakGroups),
                    breakGroups.size() > BOUND,
                    List.copyOf(rows),
                    seen > BOUND);
        }
    }

    // ================================================================= the summary report

    /** One disposition's items for one currency: how many, and their lines' value. */
    public record SummaryDisposition(String status, String currency, long items, String value) {}

    /** Breaks of one type, present severity and currency: how many, and their value at issue. */
    public record SummaryBreaks(
            String type, String severity, String currency, long count, String valueAtIssue) {}

    /** Resolutions APPROVED of one kind and currency: how many, and the value they posted. */
    public record SummaryResolutions(
            String kind, String currency, long approved, String posted) {}

    /** How many breaks stood open at the date's end with an age in this band. */
    public record SummaryAgeingBand(String band, long openBreaks) {}

    /**
     * One source's business date. {@code matchRate} is {@code itemsMatched} over
     * {@code itemsCounted} to four places, rounded half up — a ratio of counts, never a
     * {@code double}, absent when nothing was counted; matched means disposed by a rule
     * ({@code MATCHED}, {@code CHECKED} or {@code OFFSET}), counted means every item but those
     * whose evidence a repudiation withdrew.
     */
    public record DailySummarySource(
            String source,
            long batchesAccepted,
            List<SummaryDisposition> items,
            long itemsMatched,
            long itemsCounted,
            String matchRate,
            List<SummaryBreaks> breaksRaised,
            List<SummaryBreaks> breaksResolved,
            List<SummaryResolutions> resolutionsApproved,
            List<SummaryAgeingBand> openBreaksByAge) {}

    /** The summary of one UTC business date, per source code. */
    public record DailySummaryReport(
            String date, List<DailySummarySource> sources, boolean truncated) {}

    /**
     * The summary of one UTC business date ({@code YYYY-MM-DD}; today when absent; ADR-0072
     * §3): per source, the batches accepted for the date, its items by disposition as count and
     * value, the match rate, the breaks raised and resolved that day by type and present
     * severity with their value at issue, the resolutions approved that day by kind with the
     * value they posted, and the breaks standing open at the day's end by ageing band — exact
     * as of the date, the raise and resolve instants being frozen facts. Refused with the 422
     * before anything is read when the date is not a reportable one.
     */
    public DailySummaryReport summary(String date) {
        LocalDate businessDate = date == null ? today() : parsedDate(date);
        Instant from = businessDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = businessDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return inOneTransaction(
                unitOfWork -> {
                    SummaryFold fold =
                            new SummaryFold(sourceCodes(unitOfWork), businessDate, from, until);
                    readings.acceptedBatchesOfBusinessDate(unitOfWork, businessDate)
                            .forEach(fold::batches);
                    readings.eachItemOfBusinessDate(unitOfWork, businessDate, fold::item);
                    readings.eachBreakAround(unitOfWork, from, until, fold::breakLine);
                    readings.resolutionsApprovedBetween(unitOfWork, from, until)
                            .forEach(fold::approved);
                    DailySummaryReport report = fold.report();
                    recordServing(
                            unitOfWork, "summary", businessDate.toString(),
                            report.sources().size());
                    return report;
                });
    }

    /** The summary fold: one accumulator per source code, every declared source present. */
    static final class SummaryFold {

        private record StatusKey(ItemStatus status, String currency) {}

        private record BreakKey(BreakType type, Severity severity, String currency) {}

        private record KindKey(String kind, String currency) {}

        private static final Comparator<BreakKey> BREAK_ORDER =
                Comparator.comparing(BreakKey::type)
                        .thenComparing(BreakKey::severity, Comparator.reverseOrder())
                        .thenComparing(BreakKey::currency);

        private static final class SourceDay {
            private long batches;
            private long matched;
            private long counted;
            private final Map<StatusKey, Sum> items =
                    new TreeMap<>(
                            Comparator.comparing(StatusKey::status)
                                    .thenComparing(StatusKey::currency));
            private final Map<BreakKey, Sum> raised = new TreeMap<>(BREAK_ORDER);
            private final Map<BreakKey, Sum> resolved = new TreeMap<>(BREAK_ORDER);
            private final Map<KindKey, Sum> approved =
                    new TreeMap<>(
                            Comparator.comparing(KindKey::kind)
                                    .thenComparing(KindKey::currency));
            private final Map<KindKey, Long> approvedCount = new HashMap<>();
            private final long[] bands = new long[AGEING_BANDS.size()];
        }

        private final Map<UUID, String> codes;
        private final LocalDate date;
        private final Instant from;
        private final Instant until;
        private final Map<String, SourceDay> days = new TreeMap<>();

        SummaryFold(Map<UUID, String> codes, LocalDate date, Instant from, Instant until) {
            this.codes = codes;
            this.date = date;
            this.from = from;
            this.until = until;
            for (String code : codes.values()) {
                days.put(code, new SourceDay());
            }
        }

        private SourceDay dayOf(String code) {
            return days.computeIfAbsent(code, absent -> new SourceDay());
        }

        void batches(UUID sourceId, Long count) {
            dayOf(codeOf(codes, sourceId)).batches += count;
        }

        void item(ReportReadings.DayItemLine line) {
            SourceDay day = dayOf(codeOf(codes, line.sourceId()));
            day.items
                    .computeIfAbsent(
                            new StatusKey(line.status(), line.amount().currency().code()),
                            absent -> new Sum())
                    .add(line.amount());
            if (line.status() == ItemStatus.REPUDIATED) {
                return;
            }
            day.counted++;
            if (line.status() == ItemStatus.MATCHED
                    || line.status() == ItemStatus.CHECKED
                    || line.status() == ItemStatus.OFFSET) {
                day.matched++;
            }
        }

        void breakLine(ReportReadings.BreakLine line) {
            SourceDay day = dayOf(codeOf(codes, line.sourceId()));
            BreakKey key =
                    new BreakKey(line.type(), line.severity(),
                            line.valueAtIssue().currency().code());
            if (within(line.raisedAt())) {
                day.raised.computeIfAbsent(key, absent -> new Sum()).add(line.valueAtIssue());
            }
            if (line.resolvedAt().filter(this::within).isPresent()) {
                day.resolved.computeIfAbsent(key, absent -> new Sum()).add(line.valueAtIssue());
            }
            boolean openAtEnd =
                    line.raisedAt().isBefore(until)
                            && line.resolvedAt().map(at -> !at.isBefore(until)).orElse(true);
            if (openAtEnd) {
                long age =
                        ChronoUnit.DAYS.between(
                                LocalDate.ofInstant(line.raisedAt(), ZoneOffset.UTC), date);
                day.bands[bandOf(age)]++;
            }
        }

        void approved(ReportReadings.ApprovedResolutionLine line) {
            String code = line.sourceId().map(id -> codeOf(codes, id)).orElse(UNKNOWN_SOURCE);
            SourceDay day = dayOf(code);
            KindKey key = new KindKey(line.kind().name(), line.proposed().currency().code());
            Sum posted = day.approved.computeIfAbsent(key, absent -> new Sum());
            if (line.posted()) {
                posted.add(line.proposed());
            }
            day.approvedCount.merge(key, 1L, Long::sum);
        }

        private boolean within(Instant instant) {
            return !instant.isBefore(from) && instant.isBefore(until);
        }

        static int bandOf(long ageDays) {
            if (ageDays <= 2) {
                return 0;
            }
            if (ageDays <= 7) {
                return 1;
            }
            return ageDays <= 30 ? 2 : 3;
        }

        DailySummaryReport report() {
            List<DailySummarySource> rows = new ArrayList<>();
            for (Map.Entry<String, SourceDay> entry : days.entrySet()) {
                SourceDay day = entry.getValue();
                List<SummaryDisposition> items = new ArrayList<>();
                day.items.forEach(
                        (key, sum) ->
                                items.add(
                                        new SummaryDisposition(
                                                key.status().name(),
                                                key.currency(),
                                                sum.count(),
                                                rendered(sum.orZero(
                                                        CurrencyCode.of(key.currency()))))));
                List<SummaryResolutions> approved = new ArrayList<>();
                day.approved.forEach(
                        (key, sum) ->
                                approved.add(
                                        new SummaryResolutions(
                                                key.kind(),
                                                key.currency(),
                                                day.approvedCount.getOrDefault(key, 0L),
                                                rendered(sum.orZero(
                                                        CurrencyCode.of(key.currency()))))));
                List<SummaryAgeingBand> bands = new ArrayList<>();
                for (int band = 0; band < AGEING_BANDS.size(); band++) {
                    bands.add(new SummaryAgeingBand(AGEING_BANDS.get(band), day.bands[band]));
                }
                rows.add(
                        new DailySummarySource(
                                entry.getKey(),
                                day.batches,
                                List.copyOf(items),
                                day.matched,
                                day.counted,
                                ratioOf(day.matched, day.counted),
                                breakRows(day.raised),
                                breakRows(day.resolved),
                                List.copyOf(approved),
                                List.copyOf(bands)));
            }
            return new DailySummaryReport(date.toString(), bounded(rows), rows.size() > BOUND);
        }

        private static List<SummaryBreaks> breakRows(Map<BreakKey, Sum> breaks) {
            List<SummaryBreaks> rows = new ArrayList<>();
            breaks.forEach(
                    (key, sum) ->
                            rows.add(
                                    new SummaryBreaks(
                                            key.type().name(),
                                            key.severity().name(),
                                            key.currency(),
                                            sum.count(),
                                            rendered(sum.orZero(
                                                    CurrencyCode.of(key.currency()))))));
            return List.copyOf(rows);
        }
    }

    /** A ratio of counts to four places, half up — BigDecimal, never a double; none sans base. */
    static String ratioOf(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .toPlainString();
    }

    // ================================================================= the provider-costs report

    /** One fee line type's evidence total: Σ OUTBOUND (charged) − Σ INBOUND (rebated). */
    public record ProviderFeeEvidence(String lineType, String value) {}

    /**
     * One source and currency's month (ADR-0072 §6), four things:
     * <ol>
     *   <li>{@code charged} — the ledger's own figure: the {@code PROCESSING_COSTS} lines (DEBIT
     *       positive, CREDIT negative) of the source's recognition entries posted in the month,
     *       less those of every repudiation reversal posted in the month;
     *   <li>{@code evidence} — the same batches' fee totals by line type from their immutable
     *       {@code batch_total} rows, a reversed batch's subtracted, and whether their sum
     *       {@code evidenceAgrees} with {@code charged} (if not, the defect is ours);
     *   <li>{@code expected} — what the fee check's {@code CHECK} decisions recorded under their
     *       pinned schedules for those batches' fee lines, on the same basis;
     *   <li>the {@code FEE_MISMATCH} breaks raised in the month: count, value at issue, and how
     *       many remain open.
     * </ol>
     */
    public record ProviderCostRow(
            String source,
            String currency,
            long batches,
            long repudiations,
            String charged,
            List<ProviderFeeEvidence> evidence,
            String evidenceTotal,
            boolean evidenceAgrees,
            String expected,
            long feeMismatchBreaks,
            String feeMismatchValueAtIssue,
            long feeMismatchOpen) {}

    /** The provider-costs report for one calendar month of posting dates. */
    public record ProviderCostsReport(
            String month,
            String from,
            String until,
            List<ProviderCostRow> costs,
            boolean truncated) {}

    /**
     * The provider-costs report for {@code month} ({@code YYYY-MM}; the current UTC month when
     * absent; ADR-0072 §6). The month is a POSTING-date month: a recognition is dated by its
     * batch's stored {@code accepted_on}, so a later-day replay never moves a cost between
     * months; a repudiation's reversal is dated by its approval's day and subtracts in ITS
     * month — a batch accepted and repudiated in one month nets to nothing there, one
     * repudiated a month later shows as a negative cost in the later month. Reports and bank
     * statements alike; grouped by source code, never by a rail's name ({@code INV-RAIL-01}).
     * Refused with the 422 before anything is read when the month is not a reportable one.
     */
    public ProviderCostsReport providerCosts(String month) {
        YearMonth period = month == null ? currentMonth() : parsedMonth(month);
        LocalDate from = period.atDay(1);
        LocalDate until = period.plusMonths(1).atDay(1);
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant untilInstant = until.atStartOfDay(ZoneOffset.UTC).toInstant();
        return inOneTransaction(
                unitOfWork -> {
                    Map<CurrencyCode, Optional<LedgerAccountId>> costAccounts = new HashMap<>();
                    ProviderCostFold fold =
                            new ProviderCostFold(
                                    currency ->
                                            costAccounts.computeIfAbsent(
                                                    currency,
                                                    absent -> costAccountOf(unitOfWork, absent)));

                    List<SettlementBatchStore.RecognisedBatch> recognised =
                            batches.recognisedBetween(unitOfWork, from, until);
                    // A reversal posts on its approval's UTC day: candidates a day either side,
                    // placed by the ledger's own posting date.
                    List<Reversal> reversals = new ArrayList<>();
                    for (ReportReadings.RepudiationPosting posting :
                            readings.repudiationsApprovedBetween(
                                    unitOfWork,
                                    fromInstant.minus(1, ChronoUnit.DAYS),
                                    untilInstant.plus(1, ChronoUnit.DAYS))) {
                        JournalEntry entry = entryOf(unitOfWork, posting.journalEntryId());
                        if (entry.postingDate().isBefore(from)
                                || !entry.postingDate().isBefore(until)) {
                            continue;
                        }
                        reversals.add(
                                new Reversal(
                                        batchOf(unitOfWork, posting.settlementBatchId()),
                                        entry.lines()));
                    }

                    Set<UUID> batchIds = new LinkedHashSet<>();
                    recognised.forEach(batch -> batchIds.add(batch.batchId()));
                    reversals.forEach(reversal -> batchIds.add(reversal.batch().id()));
                    Map<UUID, List<ReportReadings.ExpectedFeeLine>> expected =
                            readings.expectedFeesOf(unitOfWork, batchIds).stream()
                                    .collect(
                                            Collectors.groupingBy(
                                                    fee -> fee.settlementBatchId()));

                    for (SettlementBatchStore.RecognisedBatch batch : recognised) {
                        fold.recognised(
                                batch.sourceCode(),
                                batch.currency(),
                                batch.journalEntryId()
                                        .map(entry -> entryOf(unitOfWork, entry).lines())
                                        .orElse(List.of()),
                                batches.totalsOf(unitOfWork, batch.batchId()),
                                expected.getOrDefault(batch.batchId(), List.of()));
                    }
                    for (Reversal reversal : reversals) {
                        fold.reversed(
                                reversal.batch().sourceCode(),
                                reversal.batch().currency(),
                                reversal.lines(),
                                batches.totalsOf(unitOfWork, reversal.batch().id()),
                                expected.getOrDefault(reversal.batch().id(), List.of()));
                    }
                    Map<UUID, String> codes = sourceCodes(unitOfWork);
                    readings.eachBreakAround(
                            unitOfWork,
                            fromInstant,
                            untilInstant,
                            line -> {
                                if (line.type() == BreakType.FEE_MISMATCH
                                        && !line.raisedAt().isBefore(fromInstant)
                                        && line.raisedAt().isBefore(untilInstant)) {
                                    fold.feeMismatch(codeOf(codes, line.sourceId()), line);
                                }
                            });

                    List<ProviderCostRow> rows = fold.rows();
                    ProviderCostsReport report =
                            new ProviderCostsReport(
                                    period.toString(),
                                    from.toString(),
                                    until.toString(),
                                    bounded(rows),
                                    rows.size() > BOUND);
                    recordServing(
                            unitOfWork, "provider-costs", period.toString(),
                            report.costs().size());
                    return report;
                });
    }

    /** One reversal placed in the month: the batch it reversed and the reversal's lines. */
    private record Reversal(SettlementBatchStore.BatchRow batch, List<JournalLine> lines) {}

    /**
     * The provider-costs fold, pure so it is proven hermetically: per (source code, currency) the
     * four things of {@link ProviderCostRow}, every figure through {@code Money}. A recognition
     * adds; a reversal adds its own lines (a charge's reversal CREDITs the costs, so it
     * subtracts by its direction) and subtracts its batch's evidence and expected fees, so the
     * three figures stay on one basis.
     */
    static final class ProviderCostFold {

        private record Key(String source, String currency) {}

        private static final class Row {
            private long batches;
            private long repudiations;
            private final Sum charged = new Sum();
            private final Map<SettlementLineType, Sum> evidence =
                    new EnumMap<>(SettlementLineType.class);
            private final Sum expected = new Sum();
            private final Sum mismatch = new Sum();
            private long mismatchOpen;
        }

        private final Function<CurrencyCode, Optional<LedgerAccountId>> costAccountOf;
        private final Map<Key, Row> rows =
                new TreeMap<>(Comparator.comparing(Key::source).thenComparing(Key::currency));

        ProviderCostFold(Function<CurrencyCode, Optional<LedgerAccountId>> costAccountOf) {
            this.costAccountOf = costAccountOf;
        }

        private Row rowOf(String source, CurrencyCode currency) {
            return rows.computeIfAbsent(new Key(source, currency.code()), absent -> new Row());
        }

        void recognised(
                String source,
                CurrencyCode currency,
                List<JournalLine> recognition,
                List<SettlementBatchStore.TotalRow> totals,
                List<ReportReadings.ExpectedFeeLine> expected) {
            Row row = rowOf(source, currency);
            row.batches++;
            fold(row, currency, recognition, totals, expected, false);
        }

        void reversed(
                String source,
                CurrencyCode currency,
                List<JournalLine> reversal,
                List<SettlementBatchStore.TotalRow> totals,
                List<ReportReadings.ExpectedFeeLine> expected) {
            Row row = rowOf(source, currency);
            row.repudiations++;
            fold(row, currency, reversal, totals, expected, true);
        }

        void feeMismatch(String source, ReportReadings.BreakLine line) {
            Row row = rowOf(source, line.valueAtIssue().currency());
            row.mismatch.add(line.valueAtIssue());
            if (line.status() != BreakStatus.RESOLVED) {
                row.mismatchOpen++;
            }
        }

        private void fold(
                Row row,
                CurrencyCode currency,
                List<JournalLine> lines,
                List<SettlementBatchStore.TotalRow> totals,
                List<ReportReadings.ExpectedFeeLine> expected,
                boolean reversed) {
            Optional<LedgerAccountId> costs = costAccountOf.apply(currency);
            for (JournalLine line : lines) {
                if (costs.isPresent() && line.account().equals(costs.get())) {
                    // The entry's own direction carries the sign: a reversal's CREDIT subtracts.
                    row.charged.add(
                            line.direction() == Direction.DEBIT
                                    ? line.amount()
                                    : line.amount().negated());
                }
            }
            for (SettlementBatchStore.TotalRow total : totals) {
                if (!total.lineType().isReportFee()
                        && total.lineType() != SettlementLineType.BANK_FEE) {
                    continue;
                }
                Money amount =
                        Money.ofPersisted(total.amountMinor(), currency, total.amountScale());
                Money charge = total.direction() == LineDirection.OUTBOUND
                        ? amount : amount.negated();
                row.evidence
                        .computeIfAbsent(total.lineType(), absent -> new Sum())
                        .add(reversed ? charge.negated() : charge);
            }
            for (ReportReadings.ExpectedFeeLine fee : expected) {
                Money charge = fee.direction() == ExpectationDirection.OUTBOUND
                        ? fee.expected() : fee.expected().negated();
                row.expected.add(reversed ? charge.negated() : charge);
            }
        }

        List<ProviderCostRow> rows() {
            List<ProviderCostRow> rendered = new ArrayList<>();
            rows.forEach(
                    (key, row) -> {
                        CurrencyCode currency = CurrencyCode.of(key.currency());
                        Sum evidenceTotal = new Sum();
                        List<ProviderFeeEvidence> evidence = new ArrayList<>();
                        row.evidence.forEach(
                                (type, sum) -> {
                                    Money value = sum.orZero(currency);
                                    evidenceTotal.add(value);
                                    evidence.add(new ProviderFeeEvidence(type.name(),
                                            rendered(value)));
                                });
                        Money charged = row.charged.orZero(currency);
                        Money evidenced = evidenceTotal.orZero(currency);
                        rendered.add(
                                new ProviderCostRow(
                                        key.source(),
                                        key.currency(),
                                        row.batches,
                                        row.repudiations,
                                        rendered(charged),
                                        List.copyOf(evidence),
                                        rendered(evidenced),
                                        charged.toBigDecimal()
                                                .compareTo(evidenced.toBigDecimal()) == 0,
                                        rendered(row.expected.orZero(currency)),
                                        row.mismatch.count(),
                                        rendered(row.mismatch.orZero(currency)),
                                        row.mismatchOpen));
                    });
            return List.copyOf(rendered);
        }
    }

    // ================================================================= shared folding

    /** A {@code Money} running total and its member count; absent until the first member. */
    static final class Sum {
        private Money total;
        private long count;

        void add(Money amount) {
            total = total == null ? amount : total.plus(amount);
            count++;
        }

        long count() {
            return count;
        }

        Money orZero(CurrencyCode currency) {
            return total == null ? Money.zero(currency) : total;
        }
    }

    /** A count, a value and the oldest member's anchor date. */
    static final class Figure {
        private final Sum value = new Sum();
        private LocalDate oldest;

        void add(Money amount, LocalDate anchor) {
            value.add(amount);
            if (oldest == null || anchor.isBefore(oldest)) {
                oldest = anchor;
            }
        }

        UnmatchedFigure render(CurrencyCode currency, LocalDate today) {
            return new UnmatchedFigure(
                    value.count(),
                    rendered(value.orZero(currency)),
                    oldest == null ? null : ChronoUnit.DAYS.between(oldest, today));
        }
    }

    private static <T> List<T> bounded(List<T> rows) {
        return List.copyOf(rows.subList(0, Math.min(rows.size(), BOUND)));
    }

    private static String rendered(Money money) {
        return money.toBigDecimal().toPlainString();
    }

    // ================================================================= the period gates

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private YearMonth currentMonth() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC));
    }

    /**
     * The business date asked for, bounded at the boundary: {@code YYYY-MM-DD}, a real calendar
     * date, from {@link #EARLIEST_REPORT_DATE} to today (UTC). Anything else is the 422 —
     * refused here, before any read and before any audit.
     */
    LocalDate parsedDate(String date) {
        if (DATE.matcher(date).matches()) {
            try {
                LocalDate parsed = LocalDate.parse(date);
                if (!parsed.isBefore(EARLIEST_REPORT_DATE) && !parsed.isAfter(today())) {
                    return parsed;
                }
            } catch (DateTimeParseException unparseable) {
                // Falls through to the refusal: 2026-02-30 has the shape and no meaning.
            }
        }
        throw new ApiException(
                PlatformErrorCode.VALIDATION_FAILED,
                "A reconciliation summary was asked for a date that is not a reportable"
                        + " business date",
                "'date' must be a calendar date, YYYY-MM-DD, from " + EARLIEST_REPORT_DATE
                        + " to today (UTC).");
    }

    /**
     * The month asked for: {@code YYYY-MM} from {@link #EARLIEST_REPORT_MONTH} to the current
     * UTC month (the chargeback-ratio report's gate, `P7-TSK-015`).
     */
    YearMonth parsedMonth(String month) {
        if (MONTH.matcher(month).matches()) {
            try {
                YearMonth period = YearMonth.parse(month);
                if (!period.isBefore(EARLIEST_REPORT_MONTH) && !period.isAfter(currentMonth())) {
                    return period;
                }
            } catch (DateTimeParseException unparseable) {
                // Falls through to the refusal: 2026-13 has the shape and no meaning.
            }
        }
        throw new ApiException(
                PlatformErrorCode.VALIDATION_FAILED,
                "A provider-costs report was asked for a period that is not a reportable"
                        + " calendar month",
                "'month' must be a calendar month, YYYY-MM, from " + EARLIEST_REPORT_MONTH
                        + " to the current month.");
    }

    // ================================================================= composition

    /** The declared source codes onto the rows' source ids — the gauges' own mapping. */
    private Map<UUID, String> sourceCodes(Connection unitOfWork) {
        Map<UUID, String> codes = new HashMap<>();
        for (SettlementSourceDescriptor declared : sources.declared()) {
            sourceRows
                    .sourceByCode(unitOfWork, declared.code())
                    .ifPresent(row -> codes.put(row.id(), declared.code()));
        }
        return Map.copyOf(codes);
    }

    /** A source id's declared code; an id the register does not declare shows as itself. */
    private static String codeOf(Map<UUID, String> codes, UUID sourceId) {
        return codes.getOrDefault(sourceId, sourceId.toString());
    }

    /** The currency's {@code PROCESSING_COSTS} account, if seeded — the chart's own read. */
    private Optional<LedgerAccountId> costAccountOf(Connection unitOfWork, CurrencyCode currency) {
        return ledgerAccounts
                .findOperational(unitOfWork, AccountPurpose.PROCESSING_COSTS, currency)
                .map(LedgerAccount::id);
    }

    private SettlementBatchStore.BatchRow batchOf(Connection unitOfWork, UUID batchId) {
        return batches.batchById(unitOfWork, batchId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a repudiation names a batch that is missing: "
                                                + batchId));
    }

    private JournalEntry entryOf(Connection unitOfWork, UUID entryId) {
        return entries.findById(unitOfWork, JournalEntryId.of(entryId))
                .map(JournalEntryStore.PostedEntry::entry)
                // A batch or a resolution names an entry that was posted: a missing one is a
                // defect, and a quietly smaller cost would hide it.
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a journal entry a report reads is missing: " + entryId));
    }

    private void recordServing(Connection unitOfWork, String report, String period, int rows) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        ReconciliationAuditAction.REPORT_READ,
                        TARGET_TYPE,
                        report,
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        CorrelationContext.current()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "every request runs in a correlation"
                                                                + " scope"))
                                .correlationId(),
                        // The report, its period and its shape - never its figures, never a
                        // counterparty (ADR-0072 section 2).
                        Optional.of(
                                "report=" + report + ", period=" + period + ", rows=" + rows)));
    }

    private <R> R inOneTransaction(SqlFunction<R> work) {
        return reconciliationTransactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        // One snapshot for every read and the audit beside them (ADR-0072 §2):
                        // set before the transaction's first statement.
                        unitOfWork.setTransactionIsolation(
                                Connection.TRANSACTION_REPEATABLE_READ);
                        return work.apply(unitOfWork);
                    } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException(
                                "a reconciliation report could not set its snapshot", failure);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }

    @FunctionalInterface
    private interface SqlFunction<R> {
        R apply(Connection connection) throws java.sql.SQLException;
    }
}
