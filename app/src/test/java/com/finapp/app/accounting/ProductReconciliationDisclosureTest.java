package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.reconciliation.BreakStatus;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.reconciliation.Severity;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation's disclosure at the close (`P14-TSK-011`; {@code INV-ACC-05}; gate criterion 5; `P14-TSK-010`'s
 * review, M-10; `P14-TSK-006`'s, M-2) and the journal's postings past the cutoff (I-1): every figure as of the cutoff's
 * instant, gross and per currency, what the grace days added counted apart, the unowned counted as of the cutoff.
 * Hermetic: reconciliation's reads and the two SQL statements scripted; the statements against PostgreSQL are {@code
 * CloseRunDatabaseTest#suspenseIsDisclosed}'s (owed, C5).
 */
@DisplayName("reconciliation's disclosure as of the cutoff (P14-TSK-011)")
class ProductReconciliationDisclosureTest {

    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final LocalDate CUTOFF = MARCH.atEndOfMonth();
    private static final Instant AS_OF = Instant.parse("2097-04-01T00:00:00Z");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private final List<ReportReadings.BreakLine> around = new ArrayList<>();
    private final List<ReportReadings.BreakLine> open = new ArrayList<>();
    private final List<ReportReadings.SuspenseAsOfLine> items = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> rows = new HashMap<>();
    private final List<Object> asked = new ArrayList<>();

    private final ReportReadings readings = (ReportReadings) Proxy.newProxyInstance(ReportReadings.class.getClassLoader(),
            new Class<?>[] {ReportReadings.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "eachBreakAround" -> {
                        asked.add(args[1]);
                        asked.add(args[2]);
                        around.forEach(castBreaks(args[3]));
                    }
                    case "eachOpenBreak" -> open.forEach(castBreaks(args[1]));
                    case "eachSuspenseItemAsOf" -> {
                        asked.add(args[1]);
                        asked.add(args[2]);
                        items.forEach(castItems(args[3]));
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
                return null;
            });

    @SuppressWarnings("unchecked")
    private static Consumer<ReportReadings.BreakLine> castBreaks(Object visitor) {
        return (Consumer<ReportReadings.BreakLine>) visitor;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<ReportReadings.SuspenseAsOfLine> castItems(Object visitor) {
        return (Consumer<ReportReadings.SuspenseAsOfLine>) visitor;
    }

    @Test
    @DisplayName("nothing open, nothing posted: nothing to disclose, read at the cutoff's own instant")
    void nothingToDisclose() {
        ReconciliationDisclosure.Disclosure read = disclosure().asOfCutoff(connection(), MARCH);
        assertThat(read.nothingToDisclose()).isTrue();
        assertThat(read.cutoff()).isEqualTo(CUTOFF);
        assertThat(read.asOf()).isEqualTo(AS_OF);
        assertThat(asked).as("an empty window at the instant; the items through the last day").containsExactly(AS_OF, AS_OF, CUTOFF, AS_OF);
    }

    @Test
    @DisplayName("the breaks standing open at the cutoff, per currency and scale: count, value at issue, ageing - a break resolved before the"
            + " instant not among them, one resolved after it is; what the grace days raised counted apart, never folded in")
    void theBreaksAsOfTheCutoff() {
        UUID standing = UUID.randomUUID();
        around.add(breakLine(standing, 1_000, EUR, "2097-03-30T10:00:00Z", Optional.empty()));
        around.add(breakLine(UUID.randomUUID(), 2_000, EUR, "2097-03-20T10:00:00Z", Optional.of("2097-04-02T10:00:00Z")));
        around.add(breakLine(UUID.randomUUID(), 4_000, EUR, "2097-01-20T10:00:00Z", Optional.of("2097-03-02T10:00:00Z")));
        around.add(breakLine(UUID.randomUUID(), 300, USD, "2097-02-01T10:00:00Z", Optional.empty()));
        open.add(breakLine(standing, 1_000, EUR, "2097-03-30T10:00:00Z", Optional.empty()));
        open.add(breakLine(UUID.randomUUID(), 9_000, EUR, "2097-04-02T08:00:00Z", Optional.empty()));

        ReconciliationDisclosure.Disclosure read = disclosure().asOfCutoff(connection(), MARCH);

        assertThat(read.breaks()).hasSize(2);
        ReconciliationDisclosure.OpenBreaks euro = read.breaks().get(0);
        assertThat(euro.currency()).isEqualTo("EUR");
        assertThat(euro.scale()).isEqualTo(2);
        assertThat(euro.breaks()).as("the one resolved before the instant is out").isEqualTo(2);
        assertThat(euro.valueAtIssueMinor()).isEqualTo(BigInteger.valueOf(3_000));
        assertThat(euro.ageing()).containsEntry("0-2", 1L).containsEntry("8-30", 1L).containsEntry("3-7", 0L).containsEntry("over-30", 0L);
        assertThat(read.breaks().get(1).ageing()).containsEntry("over-30", 1L);
        assertThat(read.raisedSinceStillOpen()).as("raised after the instant, apart").isEqualTo(1);
        assertThat(read.nothingToDisclose()).isFalse();
    }

    @Test
    @DisplayName("SUSPENSE_UNMATCHED at the cutoff: CREDIT and DEBIT gross, never netted, the ledger's two sides through the last day, ageing and"
            + " the oldest; an item whose owner was not standing open counted unowned (M-10)")
    void suspenseIsDisclosedGross() {
        UUID owner = UUID.randomUUID();
        around.add(breakLine(owner, 1_000, EUR, "2097-03-30T10:00:00Z", Optional.empty()));
        items.add(new ReportReadings.SuspenseAsOfLine(UUID.randomUUID(), SuspenseSide.CREDIT, Money.ofMinorUnits(1_000, EUR),
                LocalDate.of(2097, 2, 1), owner));
        items.add(new ReportReadings.SuspenseAsOfLine(UUID.randomUUID(), SuspenseSide.DEBIT, Money.ofMinorUnits(400, EUR),
                LocalDate.of(2097, 3, 30), UUID.randomUUID()));
        rows.put(ProductReconciliationDisclosure.SUSPENSE_LEDGER_SQL, List.of(
                row("currency", "EUR", "scale", 2, "direction", "CREDIT", "total", new BigDecimal(5_000)),
                row("currency", "EUR", "scale", 2, "direction", "DEBIT", "total", new BigDecimal(4_400)),
                row("currency", "BHD", "scale", 3, "direction", "CREDIT", "total", new BigDecimal(7))));

        ReconciliationDisclosure.Disclosure read = disclosure().asOfCutoff(connection(), MARCH);

        assertThat(read.suspense()).hasSize(2);
        ReconciliationDisclosure.Suspense euro = read.suspense().stream().filter(item -> item.currency().equals("EUR")).findFirst().orElseThrow();
        assertThat(euro.creditItems()).isEqualTo(1);
        assertThat(euro.creditUnreleasedMinor()).isEqualTo(BigInteger.valueOf(1_000));
        assertThat(euro.debitItems()).isEqualTo(1);
        assertThat(euro.debitUnreleasedMinor()).isEqualTo(BigInteger.valueOf(400));
        assertThat(euro.ledgerCreditsMinor()).isEqualTo(BigInteger.valueOf(5_000));
        assertThat(euro.ledgerDebitsMinor()).isEqualTo(BigInteger.valueOf(4_400));
        assertThat(euro.ageing()).containsEntry("over-30", 1L).containsEntry("0-2", 1L);
        assertThat(euro.oldestOpenedOn()).contains(LocalDate.of(2097, 2, 1));
        ReconciliationDisclosure.Suspense dinar = read.suspense().stream().filter(item -> item.currency().equals("BHD")).findFirst().orElseThrow();
        assertThat(dinar.ledgerCreditsMinor()).as("a ledger balance with no item is on the record too").isEqualTo(BigInteger.valueOf(7));
        assertThat(read.suspenseUnowned()).as("the second item's owner was not standing").isEqualTo(1);
        assertThat(read.nothingToDisclose()).isFalse();
        assertThat(read.evidence().members()).containsKeys("openBreaks", "suspense", "suspenseUnowned", "pastTheCutoff");
    }

    @Test
    @DisplayName("a ledger account netting to zero with no item is nothing to disclose; a read that fails throws - never an empty answer")
    void aReadThatFailsThrows() {
        rows.put(ProductReconciliationDisclosure.SUSPENSE_LEDGER_SQL, List.of(
                row("currency", "EUR", "scale", 2, "direction", "CREDIT", "total", new BigDecimal(70)),
                row("currency", "EUR", "scale", 2, "direction", "DEBIT", "total", new BigDecimal(70))));
        assertThat(disclosure().asOfCutoff(connection(), MARCH).nothingToDisclose()).isTrue();

        ReportReadings broken = (ReportReadings) Proxy.newProxyInstance(ReportReadings.class.getClassLoader(),
                new Class<?>[] {ReportReadings.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("reconciliation's read failed");
                });
        assertThatThrownBy(() -> new ProductReconciliationDisclosure(broken, new LedgerFlooredPostings())
                .asOfCutoff(connection(), MARCH)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("M-2, the owner's (b): entries past the cutoff are told only for the floored rules' namespaces, LND-2 left to ACCRUALS_COMPLETE; floored or unrecorded asks for a disclosure, an ordinary"
            + " late posting under its rule's own dating asks for nothing")
    void postingsPastTheCutoffAreTheFlooredRules() {
        rows.put(LedgerFlooredPostings.PAST_THE_CUTOFF_SQL, List.of(
                row("namespace", "settlement-batch", "entries", 2L, "floored", 1L, "unrecorded", 0L),
                row("namespace", "recon-suspense", "entries", 1L, "floored", 0L, "unrecorded", 0L),
                row("namespace", "lending.accrual", "entries", 9L, "floored", 9L, "unrecorded", 0L),
                row("namespace", "bnpl.accrual", "entries", 40L, "floored", 0L, "unrecorded", 0L),
                row("namespace", "transfer", "entries", 3L, "floored", 0L, "unrecorded", 0L)));
        ReconciliationDisclosure.Disclosure read = disclosure().asOfCutoff(connection(), MARCH);
        assertThat(read.pastTheCutoff()).containsOnlyKeys("settlement-batch", "recon-suspense");
        assertThat(read.nothingToDisclose()).isFalse();

        rows.put(LedgerFlooredPostings.PAST_THE_CUTOFF_SQL, List.of(row("namespace", "settlement-batch", "entries", 5L, "floored", 0L, "unrecorded", 0L)));
        assertThat(disclosure().asOfCutoff(connection(), MARCH).nothingToDisclose()).as("ordinary late postings").isTrue();

        rows.put(LedgerFlooredPostings.PAST_THE_CUTOFF_SQL, List.of(row("namespace", "settlement-batch", "entries", 5L, "floored", 0L, "unrecorded", 1L)));
        assertThat(disclosure().asOfCutoff(connection(), MARCH).nothingToDisclose()).as("a missing record is never its own dating").isFalse();
    }

    @Test
    @DisplayName("I-1 and C-1: the accrual entries past the cutoff are LND-2's and BNPL-F2a's, by each rule's own key prefix: the interest per"
            + " accruer, currency and scale, the income line's amount, the period's last day apart from anything older")
    void theAccrualsPastTheCutoffAreBothAccruers() {
        rows.put(LedgerFlooredPostings.ACCRUALS_SQL, List.of(
                row("namespace", "bnpl.accrual", "currency", "EUR", "scale", 2, "entries", 7L, "interest", new BigDecimal(70),
                        "last_day_entries", 1L, "last_day_interest", new BigDecimal(10)),
                row("namespace", "lending.accrual", "currency", "EUR", "scale", 2, "entries", 3L, "interest", new BigDecimal(4_250),
                        "last_day_entries", 0L, "last_day_interest", BigDecimal.ZERO)));
        List<AccrualCompleteness.Floored> floored = new LedgerFlooredPostings().accruals(connection(), CUTOFF);
        assertThat(floored).containsExactly(
                new AccrualCompleteness.Floored(AccrualCompleteness.Accruer.BNPL, "EUR", 2, 7, BigInteger.valueOf(70), 1, BigInteger.TEN),
                new AccrualCompleteness.Floored(AccrualCompleteness.Accruer.LENDING, "EUR", 2, 3, BigInteger.valueOf(4_250), 0, BigInteger.ZERO));
        assertThat(LedgerFlooredPostings.LENDING_SCOPE).isEqualTo("ledger.post:lending.accrual:%");
        assertThat(LedgerFlooredPostings.BNPL_SCOPE).as("a correction's bnpl.accrual-correction: is not this").isEqualTo("ledger.post:bnpl.accrual:%");
        assertThat(PostingRuleRegister.CURRENT.rule("LND-2", 2).orElseThrow().namespace()).as("the register's own namespace")
                .isEqualTo("lending.accrual:");
        assertThat(PostingRuleRegister.CURRENT.rule("BNPL-F2a", 1).orElseThrow().namespace()).as("the register's own namespace")
                .isEqualTo("bnpl.accrual:");
        java.sql.Date end = java.sql.Date.valueOf(CUTOFF);
        assertThat(asked).as("every date bound is the period's end, both scopes").containsExactly(end, end, end, end,
                LedgerFlooredPostings.LENDING_SCOPE, LedgerFlooredPostings.BNPL_SCOPE);
    }

    @Test
    @DisplayName("C-1: a namespace the statement was not asked for is this read's defect, never a figure")
    void anUnknownNamespaceIsNotAFigure() {
        rows.put(LedgerFlooredPostings.ACCRUALS_SQL, List.of(row("namespace", "transfer", "currency", "EUR", "scale", 2, "entries", 1L, "interest",
                new BigDecimal(1), "last_day_entries", 0L, "last_day_interest", BigDecimal.ZERO)));
        assertThatThrownBy(() -> new LedgerFlooredPostings().accruals(connection(), CUTOFF)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the statements only read, and the value date and posting date straddle the cutoff; the income line alone")
    void theStatementsOnlyRead() {
        for (String sql : List.of(LedgerFlooredPostings.ACCRUALS_SQL, ProductReconciliationDisclosure.SUSPENSE_LEDGER_SQL)) {
            String upper = sql.toUpperCase(Locale.ROOT);
            assertThat(upper).startsWith("SELECT ");
            for (String forbidden : List.of("INSERT", "UPDATE", "DELETE", "FOR SHARE", "FOR UPDATE", "PG_ADVISORY", "REASON", "ACTOR_ID",
                    "REFERENCE")) {
                assertThat(upper).as("%s in %s", forbidden, sql).doesNotContain(forbidden);
            }
        }
        assertThat(LedgerFlooredPostings.ACCRUALS_SQL).contains("entry.posting_date > ? AND entry.value_date <= ?")
                .contains("line.direction = 'CREDIT'").contains("count(*) FILTER (WHERE entry.value_date = ?)")
                .contains("sum(line.amount_minor) FILTER (WHERE entry.value_date = ?)");
        assertThat(LedgerFlooredPostings.PAST_THE_CUTOFF_SQL).contains("entry.posting_date > ? AND entry.value_date <= ?");
        assertThat(ProductReconciliationDisclosure.SUSPENSE_LEDGER_SQL).contains("account.purpose = ? AND entry.posting_date <= ?");
    }

    // ------------------------------------------------------------------ plumbing

    private ProductReconciliationDisclosure disclosure() {
        return new ProductReconciliationDisclosure(readings, new LedgerFlooredPostings());
    }

    private static ReportReadings.BreakLine breakLine(UUID id, long value, CurrencyCode currency, String raised, Optional<String> resolved) {
        return new ReportReadings.BreakLine(id, UUID.randomUUID(), BreakType.values()[0], Severity.LOW, BreakStatus.OPEN,
                Money.ofMinorUnits(value, currency), Instant.parse(raised), resolved.map(Instant::parse));
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    /** A connection answering each statement with its scripted rows - none when unscripted. */
    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return statement((String) args[0]);
                });
    }

    private PreparedStatement statement(String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length == 2) {
                        if (sql.equals(LedgerFlooredPostings.ACCRUALS_SQL)) {
                            asked.add(args[1]);
                        }
                        return null;
                    }
                    return switch (name) {
                        case "executeQuery" -> rows(rows.getOrDefault(sql, List.of()).iterator());
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(name);
                    };
                });
    }

    private static ResultSet rows(Iterator<Map<String, Object>> iterator) {
        Object[] current = new Object[1];
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next" -> {
                            boolean more = iterator.hasNext();
                            current[0] = more ? iterator.next() : null;
                            return more;
                        }
                        case "getString", "getBigDecimal", "getLong", "getInt" -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> row = (Map<String, Object>) current[0];
                            return row.get(args[0]);
                        }
                        case "close" -> {
                            return null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
