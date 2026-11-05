package com.finapp.app.accounting;

import static com.finapp.accounting.testing.ReportBodies.flag;
import static com.finapp.accounting.testing.ReportBodies.isNull;
import static com.finapp.accounting.testing.ReportBodies.object;
import static com.finapp.accounting.testing.ReportBodies.objects;
import static com.finapp.accounting.testing.ReportBodies.text;
import static com.finapp.accounting.testing.ReportBodies.value;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CloseRecordReads;
import com.finapp.accounting.CutoffReaction;
import com.finapp.accounting.DatingClass;
import com.finapp.accounting.EntryType;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.JournalShapes;
import com.finapp.accounting.LineShape;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.PostingRule;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.PriorPeriodCorrections;
import com.finapp.accounting.ReconciliationAsOfCutoff;
import com.finapp.accounting.RuleConformance;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.Side;
import com.finapp.accounting.StatementSection;
import com.finapp.accounting.SuspenseAsOfCutoff;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlBalances;
import com.finapp.accounting.reporting.GlFigure;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.PinnedMapping;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.ReportRefused;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.StatementNotes;
import com.finapp.accounting.reporting.TrialBalance;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.app.accounting.AccountingBatteryWorld.Chart;
import com.finapp.app.accounting.AccountingBatteryWorld.Entry;
import com.finapp.app.accounting.AccountingBatteryWorld.Kind;
import com.finapp.app.accounting.AccountingBatteryWorld.Line;
import com.finapp.app.accounting.AccountingBatteryWorld.ShapeLine;
import com.finapp.ledger.BalanceProjection;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingAttribution;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * The accounting battery's books, hermetic (`P14-TST-002`): {@link AccountingBatteryWorld}'s journal posted through the
 * <strong>real</strong> {@link PostingService} - its balance validation, its idempotency claim, its attribution - over stores in
 * memory (the {@code BnplPayoutWorld} precedent), read back through the accounting module's in-memory read adapters
 * ({@link InMemoryTrialBalanceReads} and {@link InMemoryJournal}, the stand-ins for {@code LedgerTrialBalanceReads} and {@code
 * LedgerJournalReads}, whose SQL is the database half's), and reported by the <strong>production folds</strong>:
 * {@link TrialBalanceDefinition}, {@link GeneralLedger}, {@link BalanceSheetDefinition}, {@link IncomeStatementDefinition} -
 * each translated at the month's set - and {@link RuleConformance}. Their output is read into the facts
 * {@link AccountingBatteryOracle} computes, keyed alike.
 *
 * <p>Every id a report writes is a function of the world: a period's, a version's, a rate set's, a snapshot's and a close
 * record's are minted here from fixed numbers; an entry's from an {@link IdGenerator} on a clock set per entry and a
 * {@link Random} of the seed. So two JVMs write the same bytes, which is what the second JVM is for.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class AccountingBatteryBooks {

    private AccountingBatteryBooks() {}

    /** No SQL is ever issued: every store is in memory, every report reads through its ports. */
    static final Connection NO_SQL = (Connection) Proxy.newProxyInstance(AccountingBatteryBooks.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "toString" -> "AccountingBatteryBooks.NO_SQL";
                case "hashCode" -> 0;
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("the battery's books issue no SQL: " + method.getName());
            });

    /** 2001-01-01T00:00:00Z - the battery's ids are minted from it. */
    private static final long EPOCH_MILLIS = 978_307_200_000L;

    /** A UUIDv7 that is a function of {@code n} alone. */
    static UUID v7(long n) {
        return new UUID(((EPOCH_MILLIS + n) << 16) | 0x7000L, 0x8000_0000_0000_0000L | n);
    }

    // ------------------------------------------------------------------ the versions, periods and sets

    /** A production version: its id, its number and its content. */
    record Version(GlMappingVersionId id, int number, GlMappingVersion content) {}

    /** The version {@code month} pins under {@code chart}: the reference (from January), restated from July; moved when perturbed. */
    static Version version(Chart chart, YearMonth month) {
        int number = AccountingBatteryWorld.versionOf(month);
        return new Version(GlMappingVersionId.of(v7((chart == Chart.MOVED ? 10 : 0) + number)), number, content(chart, number));
    }

    /** The content of version {@code number} under {@code chart}, from {@code ReferenceGlMapping} - never from the world's chart. */
    static GlMappingVersion content(Chart chart, int number) {
        YearMonth from = number == 1 ? AccountingBatteryWorld.MONTHS.get(0) : AccountingBatteryWorld.RESTATED_FROM;
        GlMappingVersion reference = ReferenceGlMapping.version(from);
        List<GlAccount> accounts = new ArrayList<>(reference.accounts());
        List<MappingRule> rules = new ArrayList<>(reference.rules());
        if (number == 2) {
            restate(accounts, rules, AccountingBatteryWorld.FEES, new GlAccount(AccountingBatteryWorld.RESTATED_FEES,
                    "Fee income - restated", GlClassification.REVENUE, StatementSection.FEE_AND_COMMISSION_INCOME));
        }
        if (chart == Chart.MOVED) {
            restate(accounts, rules, AccountingBatteryWorld.MOVED_PURPOSE, new GlAccount(AccountingBatteryWorld.MOVED_TO,
                    "Chargebacks recoverable - moved", GlClassification.ASSET,
                    StatementSection.valueOf(AccountingBatteryWorld.MOVED_TO_SECTION)));
        }
        return new GlMappingVersion(from, accounts, rules);
    }

    /** {@code purpose} mapped to {@code to} instead, the account it left retired when no rule names it any more. */
    private static void restate(List<GlAccount> accounts, List<MappingRule> rules, String purpose, GlAccount to) {
        String retired = rules.stream().filter(rule -> rule.purpose().equals(purpose)).findFirst().orElseThrow().glAccountCode();
        rules.replaceAll(rule -> rule.purpose().equals(purpose) ? new MappingRule(rule.purpose(), rule.ownerKind(), to.code()) : rule);
        if (rules.stream().noneMatch(rule -> rule.glAccountCode().equals(retired))) {
            accounts.removeIf(account -> account.code().equals(retired));
        }
        accounts.add(to);
    }

    static AccountingPeriodId period(YearMonth month) {
        return AccountingPeriodId.of(v7(100 + AccountingBatteryWorld.MONTHS.indexOf(month)));
    }

    static UUID rateSet(YearMonth month) {
        return v7(200 + AccountingBatteryWorld.MONTHS.indexOf(month));
    }

    /** The instant each month was locked: the fifth of the next month, 10:00 UTC. */
    static Instant lockedAt(YearMonth month) {
        return month.plusMonths(1).atDay(5).atTime(10, 0).toInstant(ZoneOffset.UTC);
    }

    /** A set's as-of: the first instant after the month. */
    static Instant asOf(YearMonth month) {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The question a FINAL run of {@code month} asks, translated at its set. */
    static ReportInputs inputs(Chart chart, YearMonth month) {
        Version version = version(chart, month);
        return new ReportInputs(period(month), month, 1, RunBasis.FINAL, Optional.of(lockedAt(month)), Optional.of(version.id()),
                Optional.of(version.number()), lockedAt(month), RunParameters.of(Map.of(), Optional.of(rateSet(month))));
    }

    /** The register the battery's kinds are judged by - built from the world's shapes. */
    static PostingRuleRegister register() {
        List<PostingRule> rules = new ArrayList<>();
        for (Kind kind : AccountingBatteryWorld.KINDS) {
            List<LineShape> shape = new ArrayList<>();
            for (ShapeLine line : kind.shape()) {
                Side side = Side.valueOf(line.side());
                shape.add(line.required() ? LineShape.required(side, line.purposes()) : LineShape.optional(side, line.purposes()));
            }
            rules.add(new PostingRule(kind.id(), 1, AccountingBatteryWorld.MONTHS.get(0).atDay(1), EntryType.POSTING,
                    kind.namespace(), "app", List.of("AccountingBatteryBooks"), "the battery's generated " + kind.namespace()
                            + " entry", "the generated entry's index", DatingClass.ECONOMIC_DATE, CutoffReaction.REFUSED, shape,
                    "P14-TST-002"));
        }
        return new PostingRuleRegister(1, rules);
    }

    // ------------------------------------------------------------------ posting

    /** One posted entry: the generated one and what the posting service stored. */
    record Posted(Entry entry, JournalEntry stored, PostingAttribution attribution) {}

    /** The posted journal: every entry in posting order, the replays and the entry ids by index. */
    record Journal(List<Posted> entries, int replays, int replayedSame, Map<UUID, Integer> indexOf) {}

    /** {@code entries} through the real posting service over stores in memory, every {@link AccountingBatteryWorld#REPLAYED}th twice. */
    static Journal post(List<Entry> entries, long seed) {
        MutableClock clock = new MutableClock(Instant.parse("2001-01-01T00:00:00Z"));
        IdGenerator ids = new IdGenerator(clock, new Random(seed));
        Ledger ledger = new Ledger();
        PostingService postings = new PostingService(new IdempotentExecutor(ledger, clock, Duration.ofDays(400),
                Duration.ofMinutes(5)), ledger, ledger, ledger, ledger, ids, clock, PostingObserver.NONE);
        int replays = 0;
        int same = 0;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)))) {
            for (Entry entry : entries) {
                clock.set(entry.postingDate().atTime(23, 0).toInstant(ZoneOffset.UTC).plusMillis(entry.index()));
                PostingCommand command = command(entry);
                PostingResult first = postings.post(NO_SQL, command);
                if (first.replayed()) {
                    throw new IllegalStateException("a fresh key replayed: " + entry.key());
                }
                if (entry.index() % AccountingBatteryWorld.REPLAYED == 0) {
                    PostingResult again = postings.post(NO_SQL, command);
                    replays += again.replayed() ? 1 : 0;
                    same += again.entryId().equals(first.entryId()) ? 1 : 0;
                }
            }
        }
        Map<String, Entry> byKey = new HashMap<>();
        entries.forEach(entry -> byKey.put(entry.key(), entry));
        List<Posted> posted = new ArrayList<>();
        Map<UUID, Integer> indexOf = new HashMap<>();
        for (JournalEntryStore.PostedEntry stored : ledger.journal) {
            String key = stored.attribution().idempotencyScope().substring((PostingService.IDEMPOTENCY_SCOPE + ":").length());
            Entry entry = byKey.get(key);
            posted.add(new Posted(entry, stored.entry(), stored.attribution()));
            indexOf.put(stored.entry().id().value(), entry.index());
        }
        return new Journal(List.copyOf(posted), replays, same, Map.copyOf(indexOf));
    }

    /** The posting command of {@code entry}: its key, its two dates, its lines on the world's accounts. */
    static PostingCommand command(Entry entry) {
        CurrencyCode currency = CurrencyCode.of(entry.currency());
        List<JournalLine> lines = new ArrayList<>();
        for (Line line : entry.lines()) {
            lines.add(new JournalLine(account(line, entry.currency()), line.debit() ? Direction.DEBIT : Direction.CREDIT,
                    Money.ofMinorUnits(line.minor(), currency)));
        }
        return new PostingCommand(entry.key(), entry.postingDate(), entry.valueDate(), "accounting-battery", lines);
    }

    /** The account a line is on: one per purpose, owner and currency, its id a function of the three. */
    static LedgerAccountId account(Line line, String currency) {
        int purpose = indexOfPurpose(line.purpose());
        int unit = AccountingBatteryWorld.UNITS.indexOf(AccountingBatteryWorld.unit(currency));
        return LedgerAccountId.of(v7(1_000_000L + purpose * 1_000L + unit * 10L + line.owner()));
    }

    /** An account as the ledger's account row holds it: its purpose, owner kind and owner. */
    record Holder(String purpose, String ownerKind, int owner) {}

    /** The ledger's accounts - every purpose of the chart in every currency, four wallets each - by id. */
    static final Map<LedgerAccountId, Holder> ACCOUNTS = accounts();

    private static Map<LedgerAccountId, Holder> accounts() {
        Map<LedgerAccountId, Holder> accounts = new HashMap<>();
        for (AccountingBatteryWorld.ChartRow row : AccountingBatteryWorld.CHART) {
            int owners = row.purpose().equals(AccountingBatteryWorld.WALLET) ? AccountingBatteryWorld.WALLETS : 1;
            for (AccountingBatteryWorld.Unit unit : AccountingBatteryWorld.UNITS) {
                for (int owner = 0; owner < owners; owner++) {
                    Line line = new Line(row.purpose(), row.ownerKind(), owner, true, 1);
                    accounts.put(account(line, unit.currency()), new Holder(row.purpose(), row.ownerKind(), owner));
                }
            }
        }
        return Map.copyOf(accounts);
    }

    private static int indexOfPurpose(String purpose) {
        for (int i = 0; i < AccountingBatteryWorld.CHART.size(); i++) {
            if (AccountingBatteryWorld.CHART.get(i).purpose().equals(purpose)) {
                return i;
            }
        }
        throw new IllegalArgumentException("not the battery's purpose: " + purpose);
    }

    // ------------------------------------------------------------------ the books as accounting reads them

    /** The posted journal through accounting's read ports, under {@code chart}. */
    record Books(Chart chart, Journal journal, InMemoryTrialBalanceReads reads, InMemoryJournal lines,
            List<JournalShapes.EntryShape> shapes) {}

    /** The books of {@code journal} under {@code chart}, the lines served in posting order - or shuffled by {@code order}. */
    static Books books(Journal journal, Chart chart, Optional<Long> order) {
        InMemoryTrialBalanceReads reads = new InMemoryTrialBalanceReads();
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            Version version = version(chart, month);
            reads.version(version.id(), version.content());
            Optional<GlMappingVersionId> previous = month.equals(AccountingBatteryWorld.MONTHS.get(0)) ? Optional.empty()
                    : Optional.of(version(chart, month.minusMonths(1)).id());
            reads.period(new TrialBalanceReads.Period(period(month), month.atDay(1), month.atEndOfMonth(), PeriodStatus.CLOSED,
                    Optional.of(version.id()), previous));
        }
        List<Posted> served = new ArrayList<>(journal.entries());
        order.ifPresent(seed -> Collections.shuffle(served, new Random(seed)));
        InMemoryJournal lines = new InMemoryJournal();
        List<JournalShapes.EntryShape> shapes = new ArrayList<>();
        for (Posted posted : served) {
            JournalEntry stored = posted.stored();
            UUID id = stored.id().value();
            List<JournalReads.Line> entryLines = new ArrayList<>();
            List<JournalShapes.ShapeLine> shapeLines = new ArrayList<>();
            List<JournalLine> storedLines = stored.lines();
            for (int seq = 0; seq < storedLines.size(); seq++) {
                JournalLine line = storedLines.get(seq);
                // The stored line joined to its account, as the SQL joins ledger_account: never the generated line.
                Holder held = Optional.ofNullable(ACCOUNTS.get(line.account()))
                        .orElseThrow(() -> new IllegalStateException("a stored line on no account of the chart"));
                Side side = line.direction() == Direction.DEBIT ? Side.DEBIT : Side.CREDIT;
                reads.line(new InMemoryTrialBalanceReads.JournalLine(stored.postingDate(), stored.createdAt(), held.purpose(),
                        held.ownerKind(), line.amount().currency().code(), line.amount().scale(), side, line.amount().minorUnits()));
                Optional<UUID> owner = held.ownerKind().equals("CUSTOMER") ? Optional.of(v7(2_000_000L + held.owner()))
                        : Optional.empty();
                entryLines.add(new JournalReads.Line(id, seq, stored.postingDate(), stored.valueDate(), "POSTING",
                        line.account().value(), held.purpose(), held.ownerKind(), owner, Optional.empty(), side,
                        line.amount().minorUnits(), line.amount().currency().code(), line.amount().scale()));
                shapeLines.add(new JournalShapes.ShapeLine(seq, held.purpose(), side, line.amount().currency().code(),
                        line.amount().scale(), BigInteger.valueOf(line.amount().minorUnits())));
            }
            lines.post(new JournalReads.Entry(id, "POSTING", stored.postingDate(), stored.valueDate(), Optional.empty(), entryLines));
            String key = posted.attribution().idempotencyScope().substring((PostingService.IDEMPOTENCY_SCOPE + ":").length());
            shapes.add(new JournalShapes.EntryShape(id, EntryType.POSTING, stored.postingDate(),
                    Optional.of(PostingRuleRegister.namespaceOf(key)), shapeLines));
        }
        return new Books(chart, journal, reads, lines, List.copyOf(shapes));
    }

    // ------------------------------------------------------------------ the reports

    /** Every fact of every month, and per month and report the SHA-256 of the content bytes. */
    record Reported(SortedMap<String, String> facts, SortedMap<String, String> contents) {}

    /** The production folds over {@code books} for every month, translated at {@code rates}. */
    static Reported report(Books books, Function<YearMonth, SortedMap<String, BigDecimal>> rates) {
        SortedMap<String, String> facts = new TreeMap<>();
        SortedMap<String, String> contents = new TreeMap<>();
        ValuationRateReads valuation = valuation(rates);
        StatementNotes notes = notes(books.chart());
        TrialBalanceDefinition trialBalance = new TrialBalanceDefinition(books.reads(), valuation);
        BalanceSheetDefinition sheet = new BalanceSheetDefinition(books.reads(), books.reads().counterparties(), valuation, notes);
        IncomeStatementDefinition income = new IncomeStatementDefinition(books.reads(), books.reads().counterparties(), valuation,
                notes);
        GeneralLedger ledger = new GeneralLedger(books.lines());
        RuleConformance conformance = new RuleConformance(register());
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            String m = month.toString();
            ReportInputs inputs = inputs(books.chart(), month);

            TrialBalance balance = trialBalance.trialBalance(NO_SQL, inputs);
            CanonicalJson.Obj tb = trialBalance.generate(NO_SQL, inputs);
            contents.put(m + " TRIAL_BALANCE", CanonicalJson.sha256(CanonicalJson.text(tb)));
            trialBalanceFacts(facts, m, balance, tb);

            Version version = version(books.chart(), month);
            GlBalances gl = ledger.balances(NO_SQL, new PinnedMapping(month, Optional.of(PeriodStatus.CLOSED), version.id(),
                    version.number(), version.content()), Optional.empty());
            glFacts(facts, m, gl);

            try {
                CanonicalJson.Obj body = sheet.generate(NO_SQL, inputs);
                contents.put(m + " BALANCE_SHEET", CanonicalJson.sha256(CanonicalJson.text(body)));
                balanceSheetFacts(facts, m, body);
            } catch (ReportRefused refused) {
                facts.put("BS|" + m + "|refused", refusal(refused));
                contents.put(m + " BALANCE_SHEET", "refused " + refusal(refused));
            }
            try {
                CanonicalJson.Obj body = income.generate(NO_SQL, inputs);
                contents.put(m + " INCOME_STATEMENT", CanonicalJson.sha256(CanonicalJson.text(body)));
                incomeStatementFacts(facts, m, body);
            } catch (ReportRefused refused) {
                facts.put("IS|" + m + "|refused", refusal(refused));
                contents.put(m + " INCOME_STATEMENT", "refused " + refusal(refused));
            }

            RuleConformance.Tally tally = conformance.tally(Map.of(), 100_000);
            books.shapes().stream().filter(shape -> YearMonth.from(shape.postingDate()).equals(month)).forEach(tally::add);
            facts.put("RC|" + m, findings(tally, books.journal().indexOf()));
        }
        return new Reported(facts, contents);
    }

    static String refusal(ReportRefused refused) {
        return refused.reason() == ReportRefused.Reason.UNCLASSIFIED ? refused.reason().name()
                : refused.reason().name() + ":" + String.join(",", refused.subjects());
    }

    /** The month's sets: one per month, approved, at {@code rates}. */
    static ValuationRateReads valuation(Function<YearMonth, SortedMap<String, BigDecimal>> rates) {
        return (snapshot, id) -> AccountingBatteryWorld.MONTHS.stream().filter(month -> rateSet(month).equals(id)).findFirst()
                .map(month -> new ValuationRateReads.PinnedSet(id, period(month), month, ValuationRateSetStatus.APPROVED,
                        asOf(month), 96, rates.apply(month).entrySet().stream().map(rate -> new ValuationRate(CurrencyCode.of("EUR"),
                                CurrencyCode.of(rate.getKey()), rate.getValue(), "battery-reference", snapshot(month, rate.getKey()),
                                asOf(month).minusSeconds(3_600), asOf(month).minusSeconds(3_598))).toList()));
    }

    private static UUID snapshot(YearMonth month, String currency) {
        return v7(300 + AccountingBatteryWorld.MONTHS.indexOf(month) * 10L + AccountingBatteryWorld.UNITS.indexOf(
                AccountingBatteryWorld.unit(currency)));
    }

    /** The notes' ports stood in: nothing in suspense, no break, no correction; each month's close record its own. */
    static StatementNotes notes(Chart chart) {
        SuspenseAsOfCutoff suspense = (snapshot, month) -> new SuspenseAsOfCutoff.Register(month.atEndOfMonth(), asOf(month), List.of());
        ReconciliationAsOfCutoff reconciliation = (snapshot, month) -> new ReconciliationAsOfCutoff.Standing(month.atEndOfMonth(),
                asOf(month), List.of(), List.of());
        PriorPeriodCorrections corrections = (snapshot, month) -> new PriorPeriodCorrections.Corrections(List.of(), List.of(), 0);
        CloseRecordReads closes = (snapshot, id, revision) -> AccountingBatteryWorld.MONTHS.stream()
                .filter(month -> period(month).equals(id)).findFirst()
                .map(month -> new CloseRecordReads.CloseRecord(v7(400 + AccountingBatteryWorld.MONTHS.indexOf(month)), revision,
                        lockedAt(month), v7(500 + AccountingBatteryWorld.MONTHS.indexOf(month)), version(chart, month).id().value(),
                        rateSet(month), 0, 0, lockedAt(month).plusSeconds(60), lockedAt(month).plusSeconds(120), List.of(),
                        List.of()));
        return new StatementNotes(suspense, reconciliation, corrections, closes);
    }

    // ------------------------------------------------------------------ the facts, read from production's output

    static void trialBalanceFacts(SortedMap<String, String> facts, String m, TrialBalance balance, CanonicalJson.Obj body) {
        for (TrialBalance.AccountRow row : balance.accounts()) {
            facts.put("TB|" + m + "|A:" + row.account().code() + "|" + row.currency(), sides(row.opening()) + " " + sides(row.movement()));
        }
        for (TrialBalance.UnmappedRow row : balance.unmapped()) {
            facts.put("TB|" + m + "|U:" + row.purpose() + "/" + row.ownerKind() + "|" + row.currency(),
                    sides(row.opening()) + " " + sides(row.movement()));
        }
        for (TrialBalance.CurrencyCheck check : balance.currencies()) {
            facts.put("TB|" + m + "|C:" + check.currency(), "o=" + sides(check.opening()) + " m=" + sides(check.movement())
                    + " ledger=o:" + sides(check.ledgerOpening()) + ",m:" + sides(check.ledgerMovement()) + ",c:"
                    + sides(check.ledgerClosing()) + " dr=" + normal(check.closingDebitBalances()) + " cr="
                    + normal(check.closingCreditBalances()) + " unmapped=" + check.unmappedLines() + " zero=" + check.zero()
                    + " openingZero=" + check.openingZero() + " periodZero=" + check.periodZero() + " equalsLedger="
                    + check.equalsLedger());
        }
        facts.put("TB|" + m + "|verdict", "passes=" + balance.passes() + " fullyMapped=" + balance.fullyMapped() + " reclassified="
                + balance.openingReclassified());
        for (CanonicalJson.Obj row : objects(body, "accounts")) {
            facts.put("TBT|" + m + "|row|A:" + text(row, "account") + "|" + text(row, "currency"), translatedRow(row));
        }
        for (CanonicalJson.Obj row : objects(body, "unmapped")) {
            facts.put("TBT|" + m + "|row|U:" + text(row, "purpose") + "/" + text(row, "ownerKind") + "|" + text(row, "currency"),
                    translatedRow(row));
        }
        translationFacts(facts, "TBT|" + m, object(body, "translation"));
    }

    private static String translatedRow(CanonicalJson.Obj row) {
        CanonicalJson.Obj translated = object(row, "translated");
        return money(translated, "opening") + " " + money(translated, "debits") + " " + money(translated, "credits") + " "
                + money(translated, "closing");
    }

    static void glFacts(SortedMap<String, String> facts, String m, GlBalances gl) {
        for (GlFigure figure : gl.figures()) {
            facts.put("GL|" + m + "|" + figure.account().code() + "|" + figure.currency(), normal(figure.debits()) + "/"
                    + normal(figure.credits()) + "/" + figure.lines());
        }
        for (GlBalances.Unmapped gap : gl.unmapped()) {
            facts.put("GL|" + m + "|U:" + gap.key() + "|" + gap.currency(), normal(gap.debits()) + "/" + normal(gap.credits()) + "/"
                    + gap.lines());
        }
        for (GlBalances.Agreement agreement : gl.agreement()) {
            facts.put("GL|" + m + "|=" + agreement.currency(), "gl=" + normal(agreement.glDebits()) + "/"
                    + normal(agreement.glCredits()) + "/" + agreement.glLines() + " unmapped=" + normal(agreement.unmappedDebits())
                    + "/" + normal(agreement.unmappedCredits()) + "/" + agreement.unmappedLines() + " ledger="
                    + normal(agreement.ledgerDebits()) + "/" + normal(agreement.ledgerCredits()) + "/" + agreement.ledgerLines());
        }
    }

    static void balanceSheetFacts(SortedMap<String, String> facts, String m, CanonicalJson.Obj body) {
        for (CanonicalJson.Obj section : objects(body, "sections")) {
            String name = text(section, "section");
            for (CanonicalJson.Obj row : objects(section, "rows")) {
                String code = text(row, "glAccount");
                String currency = text(row, "currency");
                facts.put("BS|" + m + "|row|" + name + "|" + code + "|" + currency, money(row, "opening") + " " + money(row, "debits")
                        + " " + money(row, "credits") + " " + money(row, "closing") + " " + text(row, "lines") + " "
                        + text(row, "openingLines"));
                facts.put("BST|" + m + "|row|" + code + "|" + currency, money(object(row, "translated"), "closing"));
            }
            for (CanonicalJson.Obj total : objects(section, "totals")) {
                facts.put("BS|" + m + "|total|" + name + "|" + text(total, "classification") + "|" + text(total, "currency"),
                        money(total, "closing"));
            }
        }
        for (CanonicalJson.Obj equation : objects(body, "equation")) {
            String currency = text(equation, "currency");
            facts.put("BS|" + m + "|eq|" + currency, "assets=" + money(equation, "assets") + " liabilities="
                    + money(equation, "liabilities") + " equity=" + money(equation, "equity") + " retained="
                    + money(equation, "retainedEarnings") + " result=" + money(equation, "currentPeriodResult") + " difference="
                    + money(equation, "difference"));
            CanonicalJson.Obj translated = object(equation, "translated");
            facts.put("BST|" + m + "|eq|" + currency, money(translated, "retainedEarnings") + " "
                    + money(translated, "currentPeriodResult"));
        }
        facts.put("BS|" + m + "|bridge", Boolean.toString(!isNull(body, "reclassification")));
        CanonicalJson.Obj translation = object(body, "translation");
        translationFacts(facts, "BST|" + m, translation);
        facts.put("BST|" + m + "|balanced", Boolean.toString(flag(translation, "balanced")));
    }

    static void incomeStatementFacts(SortedMap<String, String> facts, String m, CanonicalJson.Obj body) {
        for (CanonicalJson.Obj section : objects(body, "sections")) {
            String name = text(section, "section");
            for (CanonicalJson.Obj row : objects(section, "rows")) {
                String code = text(row, "glAccount");
                String currency = text(row, "currency");
                facts.put("IS|" + m + "|row|" + name + "|" + code + "|" + currency, money(row, "debits") + " " + money(row, "credits")
                        + " " + money(row, "net") + " " + text(row, "lines"));
                facts.put("IST|" + m + "|row|" + code + "|" + currency, money(object(row, "translated"), "net"));
            }
            for (CanonicalJson.Obj total : objects(section, "totals")) {
                facts.put("IS|" + m + "|total|" + name + "|" + text(total, "classification") + "|" + text(total, "currency"),
                        money(total, "net"));
            }
        }
        for (CanonicalJson.Obj result : objects(body, "result")) {
            facts.put("IS|" + m + "|result|" + text(result, "currency"), "revenue=" + money(result, "revenue") + " expenses="
                    + money(result, "expenses") + " result=" + money(result, "result"));
        }
        for (CanonicalJson.Obj fx : objects(body, "netRealisedFx")) {
            facts.put("IS|" + m + "|fx|" + text(fx, "currency"), "gains=" + money(fx, "gains") + " losses=" + money(fx, "losses")
                    + " net=" + money(fx, "net"));
        }
        translationFacts(facts, "IST|" + m, object(body, "translation"));
    }

    /** A translation member's facts: per column and currency its group, per column the report-wide figures, each pinned rate. */
    private static void translationFacts(SortedMap<String, String> facts, String prefix, CanonicalJson.Obj translation) {
        for (CanonicalJson.Obj rate : objects(translation, "rates")) {
            if (text(rate, "basis").equals("PINNED_RATE")) {
                facts.put(prefix + "|rate|" + text(rate, "currency"), normal(new BigDecimal(text(rate, "rate"))));
            }
        }
        for (CanonicalJson.Obj currency : objects(translation, "currencies")) {
            CanonicalJson.Obj columns = object(currency, "columns");
            for (Map.Entry<String, CanonicalJson.Value> column : columns.members().entrySet()) {
                if (column.getValue() instanceof CanonicalJson.Obj group) {
                    facts.put(prefix + "|" + column.getKey() + "|" + text(currency, "currency"), money(group, "original") + " "
                            + money(group, "translatedParts") + " " + money(group, "translationDifference") + " "
                            + money(group, "translatedTotal"));
                }
            }
        }
        CanonicalJson.Obj totals = object(translation, "total");
        for (Map.Entry<String, CanonicalJson.Value> column : totals.members().entrySet()) {
            CanonicalJson.Obj figures = (CanonicalJson.Obj) column.getValue();
            facts.put(prefix + "|" + column.getKey() + "|*", money(figures, "translatedParts") + " "
                    + money(figures, "translationDifference") + " " + money(figures, "translatedTotal"));
        }
    }

    /** Conformance's findings, each entry named by its generated index. */
    static String findings(RuleConformance.Tally tally, Map<UUID, Integer> indexOf) {
        List<String> named = new ArrayList<>();
        tally.findings().forEach(finding -> {
            String subject = finding.subject();
            String what = switch (finding.type()) {
                case UNREGISTERED_POSTING -> subject.substring(subject.indexOf(' ') + 1);
                case SHAPE_MISMATCH, UNBALANCED_ENTRY -> Integer.toString(indexOf.get(UUID.fromString(
                        subject.substring(subject.indexOf(':') + 1))));
                default -> subject;
            };
            named.add(finding.type().name() + ":" + what);
        });
        Collections.sort(named);
        return named.isEmpty() ? "none" : String.join(",", named);
    }

    // ------------------------------------------------------------------ text

    private static String sides(TrialBalance.Sides sides) {
        return normal(sides.debits()) + "/" + normal(sides.credits()) + "/" + sides.lines();
    }

    /** An amount member {@code {"currency","minor","scale"}} as a plain number. */
    private static String money(CanonicalJson.Obj from, String name) {
        CanonicalJson.Obj amount = (CanonicalJson.Obj) value(from, name);
        return normal(new BigDecimal(new BigInteger(text(amount, "minor")), Integer.parseInt(text(amount, "scale"))));
    }

    /** A plain number, trailing zeros dropped, zero as {@code 0}. */
    static String normal(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ the second JVM's lines

    /** What two JVMs compare: the journal's fingerprint, every report's content hash, the facts' digest. */
    static List<String> lines(String fingerprint, Reported reported) {
        List<String> lines = new ArrayList<>();
        lines.add("fingerprint " + fingerprint);
        reported.contents().forEach((report, sha) -> lines.add(report + " " + sha));
        java.security.MessageDigest digest = AccountingBatteryWorld.sha256();
        reported.facts().forEach((key, fact) -> digest.update((key + "=" + fact + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        lines.add("facts " + reported.facts().size() + " " + java.util.HexFormat.of().formatHex(digest.digest()));
        return lines;
    }

    // ------------------------------------------------------------------ the stores, in memory

    /** The posting service's stores: the journal, the idempotency claims, the audit trail and the outbox (counted), no projection. */
    static final class Ledger implements JournalEntryStore<Connection>, IdempotencyRecordStore<Connection>, AuditWriter<Connection>,
            OutboxWriter<Connection>, BalanceProjection<Connection> {

        private final List<PostedEntry> journal = new ArrayList<>();
        private final Map<String, PostedEntry> byScope = new HashMap<>();
        private final Map<IdempotencyKey, IdempotencyRecord> claims = new LinkedHashMap<>();
        private int audited;
        private int published;

        @Override
        public void append(Connection unitOfWork, JournalEntry entry, PostingAttribution attribution) {
            PostedEntry posted = new PostedEntry(entry, attribution);
            if (byScope.putIfAbsent(attribution.idempotencyScope(), posted) != null) {
                throw new IllegalStateException("one entry per command key");
            }
            journal.add(posted);
        }

        @Override
        public Optional<PostedEntry> findById(Connection unitOfWork, JournalEntryId entryId) {
            return journal.stream().filter(posted -> posted.entry().id().equals(entryId)).findFirst();
        }

        @Override
        public List<JournalLine> reversalLinesOf(Connection unitOfWork, JournalEntryId original) {
            return List.of();
        }

        @Override
        public Optional<JournalEntryId> findByIdempotencyScope(Connection unitOfWork, String scope) {
            return Optional.ofNullable(byScope.get(scope)).map(posted -> posted.entry().id());
        }

        @Override
        public List<LineKey> lineKeysOn(Connection unitOfWork, Collection<LedgerAccountId> accounts) {
            throw new UnsupportedOperationException("the battery reads its lines through accounting's ports");
        }

        @Override
        public List<PostedEntry> entriesAfter(Connection unitOfWork, UUID after, Duration settleHorizon, int limit) {
            throw new UnsupportedOperationException("the battery reads its lines through accounting's ports");
        }

        @Override
        public long countBeyond(Connection unitOfWork, UUID after, long cap) {
            throw new UnsupportedOperationException("the battery reads its lines through accounting's ports");
        }

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(),
                    correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState, StoredResponse response,
                Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response, held.correlationId(),
                    held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId, Instant now,
                Instant expiresAt, Duration lease) {
            return false;
        }

        @Override
        public void append(Connection unitOfWork, AuditRecord record) {
            audited++;
        }

        @Override
        public void write(Connection unitOfWork, EventEnvelope envelope, byte[] payload, String payloadMediaType) {
            published++;
        }

        @Override
        public void apply(Connection unitOfWork, JournalEntry entry) {}

        @Override
        public void adjustHolds(Connection unitOfWork, LedgerAccountId account, Money delta, Instant at) {}

        @Override
        public void lockInOrder(Connection unitOfWork, Collection<LedgerAccountId> accounts) {}
    }

    /** A clock the posting loop sets per entry - so an entry's creation instant and id are functions of the world. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the battery's clock is UTC");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
