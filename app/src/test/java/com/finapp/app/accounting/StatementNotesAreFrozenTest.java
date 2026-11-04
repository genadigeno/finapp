package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CloseRecordReads;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.PriorPeriodCorrections;
import com.finapp.accounting.ReconciliationAsOfCutoff;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.StatementNotes;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.reconciliation.BreakStatus;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.reconciliation.Severity;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statements' two worst failures, the second of them: <strong>a FINAL statement whose bytes depend on something that moves after the lock</strong> (`P14-TSK-016`; {@code INV-ACC-04}, {@code INV-ACC-05}). Through the real folds of what reconciliation holds - {@link ReconciliationMonthReads} and {@link
 * ReconciliationAsOfCutoffReads} - behind both real definitions, with what moves after a close moved: a break resolved, reclassified and re-rated; an item aged, by a month long past; a later correction. And the folds' own arithmetic: gross, per currency and scale, past a {@code long}. Hermetic over {@link
 * ReportReadings}: what the SQL's own as-of answers are is {@code StatementsDatabaseTest}'s (owed, C5).
 */
@DisplayName("a FINAL statement's notes cannot move (P14-TSK-016)")
class StatementNotesAreFrozenTest {

    private static final YearMonth JANUARY = YearMonth.of(1999, 1);
    private static final LocalDate CUTOFF = JANUARY.atEndOfMonth();
    private static final Instant AS_OF = Instant.parse("1999-02-01T00:00:00Z");
    private static final Instant LOCK = Instant.parse("1999-02-05T09:00:00Z");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        throw new UnsupportedOperationException("the readings are stood in, and a definition reads only through its ports");
    });

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private final AccountingPeriodId period = AccountingPeriodId.next(IDS);
    private final GlMappingVersionId pinned = GlMappingVersionId.next(IDS);
    private final InMemoryTrialBalanceReads books = new InMemoryTrialBalanceReads()
            .version(pinned, ReferenceGlMapping.version(YearMonth.of(1999, 1)))
            .period(new TrialBalanceReads.Period(period, JANUARY.atDay(1), JANUARY.atEndOfMonth(), PeriodStatus.CLOSED, Optional.of(pinned)));

    private final List<ReportReadings.BreakLine> breaks = new ArrayList<>();
    private final List<ReportReadings.SuspenseAsOfLine> items = new ArrayList<>();
    private final List<Object> asked = new ArrayList<>();
    private final ReportReadings readings = (ReportReadings) Proxy.newProxyInstance(ReportReadings.class.getClassLoader(), new Class<?>[] {ReportReadings.class}, (proxy, method, args) -> {
        switch (method.getName()) {
            case "eachBreakAround" -> {
                asked.add(args[1]);
                asked.add(args[2]);
                breaks.forEach(StatementNotesAreFrozenTest.<ReportReadings.BreakLine>visitor(args[3]));
            }
            case "eachSuspenseItemBornAsOf" -> {
                asked.add(args[1]);
                asked.add(args[2]);
                items.forEach(StatementNotesAreFrozenTest.<ReportReadings.SuspenseAsOfLine>visitor(args[3]));
            }
            default -> throw new AssertionError("a FINAL statement's notes read no moving reading: " + method.getName());
        }
        return null;
    });

    @SuppressWarnings("unchecked")
    private static <T> Consumer<T> visitor(Object visitor) {
        return (Consumer<T>) visitor;
    }

    private final ReconciliationAsOfCutoffReads standing = new ReconciliationAsOfCutoffReads(readings);
    private final StatementNotes notes = new StatementNotes(new ReconciliationMonthReads(readings, Set.of()), standing, (snapshot, month) -> new PriorPeriodCorrections.Corrections(List.of(), List.of(), 0),
            (snapshot, id, revision) -> Optional.of(new CloseRecordReads.CloseRecord(UUID.nameUUIDFromBytes(new byte[] {1}), revision, LOCK, UUID.nameUUIDFromBytes(new byte[] {2}), pinned.value(), UUID.nameUUIDFromBytes(new byte[] {3}), 1, 0, LOCK.plusSeconds(60), LOCK.plusSeconds(120), List.of(), List.of())));

    private List<ReportDefinition> statements() {
        return List.of(new BalanceSheetDefinition(books, books.counterparties(), (snapshot, id) -> Optional.empty(), notes), new IncomeStatementDefinition(books, books.counterparties(), (snapshot, id) -> Optional.empty(), notes));
    }

    private ReportInputs finalRun() {
        return new ReportInputs(period, JANUARY, 1, RunBasis.FINAL, Optional.of(LOCK), Optional.of(pinned), Optional.of(1), LOCK, RunParameters.none());
    }

    private String bytesOf(ReportDefinition definition) {
        return CanonicalJson.text(definition.generate(SNAPSHOT, finalRun()));
    }

    private static ReportReadings.BreakLine breakLine(UUID id, BreakType type, Severity severity, BreakStatus status, long value, CurrencyCode currency, String raised, Optional<String> resolved) {
        return new ReportReadings.BreakLine(id, UUID.nameUUIDFromBytes(new byte[] {9}), type, severity, status, Money.ofMinorUnits(value, currency), Instant.parse(raised), resolved.map(Instant::parse));
    }

    private static ReportReadings.SuspenseAsOfLine item(UUID id, SuspenseSide side, long unreleased, LocalDate openedOn, UUID owner) {
        return new ReportReadings.SuspenseAsOfLine(id, side, Money.ofMinorUnits(unreleased, EUR), openedOn, owner);
    }

    @Test
    @DisplayName("a break resolved after the close, its severity and type moved with it: no byte of either statement moves, because a note reads only its raise, its resolution against the instant, and its value at issue. And the control: resolved before the instant, it did not stand, and the bytes differ")
    void aBreakResolvedAfterTheCloseMovesNoByte() {
        UUID id = UUID.randomUUID();
        breaks.add(breakLine(id, BreakType.values()[0], Severity.LOW, BreakStatus.OPEN, 450, EUR, "1999-01-20T10:00:00Z", Optional.empty()));
        List<String> issued = statements().stream().map(this::bytesOf).toList();
        assertThat(issued.get(0)).contains(id.toString()).contains("\"valueAtIssueMinor\":\"450\"");

        breaks.clear();
        breaks.add(breakLine(id, BreakType.values()[1], Severity.CRITICAL, BreakStatus.RESOLVED, 450, EUR, "1999-01-20T10:00:00Z", Optional.of("1999-03-02T10:00:00Z")));
        assertThat(statements().stream().map(this::bytesOf).toList()).isEqualTo(issued);

        breaks.clear();
        breaks.add(breakLine(id, BreakType.values()[1], Severity.CRITICAL, BreakStatus.RESOLVED, 450, EUR, "1999-01-20T10:00:00Z", Optional.of("1999-01-25T10:00:00Z")));
        assertThat(statements().stream().map(this::bytesOf).toList()).as("the control: the comparison can see a break that did not stand").isNotEqualTo(issued);
    }

    @Test
    @DisplayName("P14-TSK-022 (v): what a controller reads of either real statement names no door it cannot open: over the real bytes, with a break, an item and a correction in them, no route, no drillDown, no reasons, no /v1/, no other permission and no 'this role' is left; every other member of the issued tree stands, figures and notes alike")
    void aControllersViewNamesNoDoor() throws IOException {
        breaks.add(breakLine(UUID.randomUUID(), BreakType.values()[0], Severity.LOW, BreakStatus.OPEN, 450, EUR, "1999-01-20T10:00:00Z", Optional.empty()));
        items.add(item(UUID.randomUUID(), SuspenseSide.CREDIT, 700, CUTOFF.minusDays(2), UUID.randomUUID()));
        ObjectMapper json = new ObjectMapper();
        for (ReportDefinition definition : statements()) {
            String issued = bytesOf(definition);
            assertThat(issued).as("the issued bytes do name them: the control").contains("/v1/").contains("ACCOUNTING_READ").contains("RECONCILIATION_INVESTIGATE").contains("under this role").contains("drillDown");
            assertThat(issued).as("and the close record's reasons names the one door that does open, beside an accountant's: why the words served say what they say").contains("close-proposal under ACCOUNTING_CLOSE_APPROVE").contains("close-run (ACCOUNTING_READ)");

            FinalStatementDesk.Projection projection = FinalStatementDesk.project(issued);

            assertThat(projection.statement()).doesNotContain("/v1/").doesNotContain("ACCOUNTING_READ").doesNotContain("RECONCILIATION_INVESTIGATE").doesNotContain("under this role").doesNotContain("ACCOUNTING_CLOSE_APPROVE");
            assertThat(projection.withheld()).containsExactly("drillDown", "notes.closeRecord.reasons", "notes.closeRecord.route", "notes.priorPeriodCorrections.route", "notes.reconciliationBreaks.route", "notes.suspense.route");
            com.fasterxml.jackson.databind.node.ObjectNode expected = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(issued);
            expected.remove("drillDown");
            for (String note : List.of("closeRecord", "priorPeriodCorrections", "reconciliationBreaks", "suspense")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("notes").get(note)).remove("route");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("notes").get("closeRecord")).remove("reasons");
            assertThat(json.readTree(projection.statement())).as("nothing else moved").isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("what the reading must not be handed anyway is judged again: a break raised at or after the instant is no break of this month, whatever its reading served")
    void aBreakRaisedAfterTheInstantIsNotCounted() {
        breaks.add(breakLine(UUID.randomUUID(), BreakType.values()[0], Severity.LOW, BreakStatus.OPEN, 1, EUR, "1999-02-01T00:00:00Z", Optional.empty()));
        assertThat(standing.ofCutoff(SNAPSHOT, JANUARY).breaks()).isEmpty();
        assertThat(asked).as("the empty window at the instant, and the items through the last day").containsExactly(AS_OF, AS_OF, CUTOFF, AS_OF);
    }

    @Test
    @DisplayName("an item aged after the close: this is a month twenty-seven years gone and an item two days old at its cutoff, and it reads 0-2, because age is from the cutoff and no clock is reachable from here. A break likewise")
    void anItemAgedAfterTheCloseMovesNoByte() {
        items.add(item(UUID.randomUUID(), SuspenseSide.CREDIT, 700, CUTOFF.minusDays(2), UUID.randomUUID()));
        breaks.add(breakLine(UUID.randomUUID(), BreakType.values()[0], Severity.LOW, BreakStatus.OPEN, 1, EUR, "1999-01-29T10:00:00Z", Optional.empty()));
        for (ReportDefinition definition : statements()) {
            String body = bytesOf(definition);
            assertThat(body).contains("\"ageing\":{\"0-2\":\"1\",\"3-7\":\"0\",\"8-30\":\"0\",\"over-30\":\"0\"}").doesNotContain("\"over-30\":\"1\"");
            assertThat(bytesOf(definition)).isEqualTo(body);
        }
    }

    @Test
    @DisplayName("the register's figures come from its one frozen read, gross, a CREDIT never netting a DEBIT, with each item and owning break named: the note's route")
    void theItemsAndTheirOwners() {
        UUID credit = UUID.randomUUID();
        UUID debit = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        items.add(item(credit, SuspenseSide.CREDIT, 700, CUTOFF.minusDays(40), owner));
        items.add(item(debit, SuspenseSide.DEBIT, 90, CUTOFF.minusDays(1), owner));
        String body = bytesOf(statements().get(0));
        assertThat(body).contains("\"creditUnreleasedMinor\":\"700\"").contains("\"debitUnreleasedMinor\":\"90\"").contains("\"over-30\":\"1\"").contains("\"oldestOpenedOn\":\"1998-12-22\"");
        assertThat(standing.ofCutoff(SNAPSHOT, JANUARY).items()).containsExactly(new ReconciliationAsOfCutoff.ParkedItem(credit, owner), new ReconciliationAsOfCutoff.ParkedItem(debit, owner));
    }

    @Test
    @DisplayName("the breaks' fold: per currency and scale, gross, a value past a long exact, bands from the raise to the cutoff, the oldest raise, each id")
    void theBreaksFold() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        breaks.add(breakLine(first, BreakType.values()[0], Severity.LOW, BreakStatus.OPEN, Long.MAX_VALUE, EUR, "1998-12-01T10:00:00Z", Optional.empty()));
        breaks.add(breakLine(second, BreakType.values()[0], Severity.LOW, BreakStatus.OPEN, Long.MAX_VALUE, EUR, "1999-01-25T10:00:00Z", Optional.of("1999-02-03T00:00:00Z")));
        breaks.add(breakLine(third, BreakType.values()[0], Severity.LOW, BreakStatus.OPEN, 7, USD, "1999-01-31T23:59:59Z", Optional.empty()));
        breaks.add(breakLine(UUID.randomUUID(), BreakType.values()[0], Severity.LOW, BreakStatus.RESOLVED, 5, USD, "1999-01-02T10:00:00Z", Optional.of("1999-01-31T23:59:59Z")));

        List<ReconciliationAsOfCutoff.OpenBreaks> groups = standing.ofCutoff(SNAPSHOT, JANUARY).breaks();

        assertThat(groups).extracting(ReconciliationAsOfCutoff.OpenBreaks::currency).containsExactly("EUR", "USD");
        ReconciliationAsOfCutoff.OpenBreaks euros = groups.get(0);
        assertThat(euros.breaks()).isEqualTo(2);
        assertThat(euros.valueAtIssueMinor()).isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO));
        assertThat(euros.ageing()).containsEntry("over-30", 1L).containsEntry("3-7", 1L).containsEntry("0-2", 0L).containsEntry("8-30", 0L);
        assertThat(euros.oldestRaisedOn()).isEqualTo(LocalDate.of(1998, 12, 1));
        assertThat(euros.breakIds()).containsExactly(first, second);
        ReconciliationAsOfCutoff.OpenBreaks dollars = groups.get(1);
        assertThat(dollars.breaks()).as("resolved before the instant, it did not stand").isEqualTo(1);
        assertThat(dollars.breakIds()).containsExactly(third);
        assertThat(dollars.ageing()).as("raised on the last day").containsEntry("0-2", 1L);
    }

    @Test
    @DisplayName("every route a statement names, from a figure or a note, is a route of the API's own contract: a route that does not exist is no route, and a figure with no route is a defect")
    void everyRouteNamedIsARouteOfTheApi() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        Set<String> contract = new TreeSet<>();
        new ObjectMapper().readTree(Files.readString(directory.resolve("docs/api/openapi.json"))).get("paths").fieldNames().forEachRemaining(route -> contract.add(unnamed(route)));
        for (ReportDefinition definition : statements()) {
            Set<String> named = routesNamedBy(definition);
            assertThat(named).as(definition.code()).contains("/v1/operator/accounting/gl/accounts/{}/activity", "/v1/operator/accounting/gl/entries/{}", "/v1/operator/reconciliation/breaks/{}", "/v1/operator/accounting/periods/{}/close-proposal",
                    "/v1/operator/accounting/periods/{}/close-run", "/v1/operator/accounting/periods/{}/prior-period-adjustments", "/v1/operator/accounting/breaks");
            assertThat(contract).as(definition.code()).containsAll(named);
        }
        assertThat(routesNamedBy(statements().get(0))).as("an opening's own door: only the balance sheet holds an opening").contains("/v1/operator/accounting/report-runs/{}/lines");
    }

    private Set<String> routesNamedBy(ReportDefinition definition) {
        Set<String> named = new TreeSet<>();
        Matcher route = Pattern.compile("GET (/v1/[^ ?,;:)\"]+)").matcher(bytesOf(definition));
        while (route.find()) {
            named.add(unnamed(route.group(1)));
        }
        return named;
    }

    /** A route with its path variables' names gone: the words of a note name them for the reader. */
    private static String unnamed(String route) {
        return route.replaceAll("\\{[^}]+}", "{}");
    }

    @Test
    @DisplayName("a reading that fails fails the report: never an empty note beside the figures")
    void aReadingThatFailsFailsTheReport() {
        ReportReadings failing = (ReportReadings) Proxy.newProxyInstance(ReportReadings.class.getClassLoader(), new Class<?>[] {ReportReadings.class}, (proxy, method, args) -> {
            throw new IllegalStateException("the register is away");
        });
        assertThatThrownBy(() -> new ReconciliationAsOfCutoffReads(failing).ofCutoff(SNAPSHOT, JANUARY)).isInstanceOf(IllegalStateException.class);
    }
}
