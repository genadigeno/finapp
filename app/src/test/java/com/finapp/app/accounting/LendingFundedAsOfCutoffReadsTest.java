package com.finapp.app.accounting;

import static com.finapp.app.accounting.ScriptedStatements.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the adapter's statements say and how it folds what they answer (`P14-TSK-019`; {@code INV-ACC-04}, {@code INV-ACC-14}): over a stand-in connection, so these hold the text and the fold. Whether PostgreSQL answers as this assumes is {@code SafeguardingReturnDatabaseTest}'s and {@code SafeguardingCommittedDatabaseTest}'s (owed, C5).
 */
@DisplayName("lending_funded's reads as of the cutoff (P14-TSK-019)")
class LendingFundedAsOfCutoffReadsTest {

    private static final LocalDate CUTOFF = LocalDate.of(2097, 6, 30);
    private static final Instant MIDDAY = Instant.parse("2097-06-15T12:00:00Z");

    private final LendingFundedAsOfCutoffReads reads = new LendingFundedAsOfCutoffReads();

    private static java.util.Map<String, Object> loan(String currency, int scale, long principal, LocalDate openedOn, Instant openedAt, LocalDate disbursedOn, LocalDate cancelledOn, Instant decidedAt) {
        return row("currency", currency, "scale", scale, "amount_minor", principal, "opened_on", Date.valueOf(openedOn), "opened_at", Timestamp.from(openedAt),
                "value_date", disbursedOn == null ? null : Date.valueOf(disbursedOn), "cancelled_on", cancelledOn == null ? null : Date.valueOf(cancelledOn), "decided_at", decidedAt == null ? null : Timestamp.from(decidedAt));
    }

    private static Set<String> schemasIn(String sql) {
        Set<String> schemas = new TreeSet<>();
        Matcher named = Pattern.compile("\\b(?:FROM|JOIN)\\s+([a-z_]+)\\.").matcher(sql);
        while (named.find()) {
            schemas.add(named.group(1));
        }
        return schemas;
    }

    @Test
    @DisplayName("each statement names one module's schema, reads, and is admitted by a report's read-only view: it names no refused function and no second statement")
    void eachStatementIsOneRead() throws SQLException {
        assertThat(schemasIn(LendingFundedAsOfCutoffReads.COMMITTED_FACTS)).containsExactly("lending");
        assertThat(schemasIn(LendingFundedAsOfCutoffReads.WRITTEN_OFF)).containsExactly("ledger");
        Connection view = new ReadOnlySnapshot(new ScriptedStatements(ignored -> List.of(row())).connection(), Duration.ofSeconds(5), () -> 0L).connection();
        for (String sql : List.of(LendingFundedAsOfCutoffReads.COMMITTED_FACTS,
                LendingFundedAsOfCutoffReads.WRITTEN_OFF + LendingFundedAsOfCutoffReads.WRITTEN_OFF_GROUPS,
                LendingFundedAsOfCutoffReads.WRITTEN_OFF + LendingFundedAsOfCutoffReads.AT_THE_CUT + LendingFundedAsOfCutoffReads.WRITTEN_OFF_GROUPS)) {
            try (var admitted = view.prepareStatement(sql)) {
                assertThat(admitted).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("a FINAL cannot read what moves: the committed read names no status of any row, and no reason, actor, party, or other person's data")
    void theCommittedReadNamesNoStatus() {
        String lowered = LendingFundedAsOfCutoffReads.COMMITTED_FACTS.toLowerCase(Locale.ROOT);
        assertThat(lowered).doesNotContain("status").doesNotContain("party").doesNotContain("actor").doesNotContain("reason,").doesNotContain("correlation");
        assertThat(lowered).as("only ever the agreement's first version: an amendment exists only on an ACTIVE loan").contains("agreement.version = 1");
        assertThat(lowered).as("a cancellation's date only when it was one").contains("case when disbursement.failure_reason is not null");
        assertThat(lowered).as("the one nullable comparison in SQL names both branches").contains("disbursement.value_date is null or disbursement.value_date > ?");
        assertThat(lowered).as("driven from one row per instalment loan, so no join multiplies a loan").contains("from lending.loan_disbursement disbursement");
    }

    @Test
    @DisplayName("the journal's read is by posting date through the cutoff, on the four principal purposes of entries beside a write-off expense, grouped by scale so no sum crosses one; a value date is not read")
    void theWrittenOffReadsThePostingDate() {
        String sql = LendingFundedAsOfCutoffReads.WRITTEN_OFF;
        assertThat(sql).contains("entry.posting_date <= ?").doesNotContain("value_date");
        assertThat(sql).contains("'BNPL_PRINCIPAL', 'BNPL_PRINCIPAL_DUE', 'LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE'");
        assertThat(sql).contains("'BNPL_WRITE_OFF_EXPENSE', 'LOAN_WRITE_OFF_EXPENSE'");
        assertThat(sql).contains("beside.entry_id = line.entry_id");
        assertThat(LendingFundedAsOfCutoffReads.WRITTEN_OFF_GROUPS).contains("line.scale");
    }

    @Test
    @DisplayName("the facts are folded by the port's own rule, each column read to its own fact and every absence an absence, the cutoff bound as the statement's parameter, and char(3)'s padding gone")
    void theFactsAreFolded() {
        ScriptedStatements statements = new ScriptedStatements(sql -> List.of(
                loan("EUR", 2, 10_00, CUTOFF.minusDays(9), MIDDAY, null, null, null),
                loan("EUR", 2, 20_00, CUTOFF.minusDays(8), MIDDAY, CUTOFF.plusDays(2), null, MIDDAY.plusSeconds(1_000_000)),
                loan("EUR", 2, 40_00, CUTOFF.minusDays(7), MIDDAY, null, CUTOFF.minusDays(1), MIDDAY.plusSeconds(1)),
                loan("EUR", 2, 80_00, CUTOFF.minusDays(6), MIDDAY, null, CUTOFF.plusDays(1), MIDDAY.plusSeconds(1_000_000)),
                loan("EUR", 2, 160_00, CUTOFF.plusDays(1), MIDDAY, null, null, null),
                loan("USD", 0, 7, CUTOFF, MIDDAY, null, null, null)));

        List<LendingFundedAsOfCutoff.Committed> committed = reads.committed(statements.connection(), CUTOFF, Optional.empty());

        assertThat(committed).extracting(LendingFundedAsOfCutoff.Committed::currency, LendingFundedAsOfCutoff.Committed::scale, LendingFundedAsOfCutoff.Committed::minor, LendingFundedAsOfCutoff.Committed::loans)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EUR", 2, BigInteger.valueOf(110_00), 3L), org.assertj.core.groups.Tuple.tuple("USD", 0, BigInteger.valueOf(7), 1L));
        assertThat(statements.prepared).containsExactly(LendingFundedAsOfCutoffReads.COMMITTED_FACTS);
        assertThat(statements.bound).containsExactly(Date.valueOf(CUTOFF));
    }

    @Test
    @DisplayName("a currency read with nothing is an empty answer, the read having succeeded: the definition's zero, never one the port invents")
    void nothingIsAnEmptyAnswer() {
        assertThat(reads.committed(new ScriptedStatements(sql -> List.of()).connection(), CUTOFF, Optional.empty())).isEmpty();
        assertThat(reads.writtenOff(new ScriptedStatements(sql -> List.of()).connection(), CUTOFF, Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("a PROVISIONAL cut is the journal's own, and a loan's facts: the fold is told it, and the journal's statement carries it")
    void theCut() {
        Instant cut = MIDDAY.plusSeconds(10);
        ScriptedStatements facts = new ScriptedStatements(sql -> List.of(
                loan("EUR", 2, 10_00, CUTOFF.minusDays(9), MIDDAY.plusSeconds(20), null, null, null),
                loan("EUR", 2, 20_00, CUTOFF.minusDays(9), MIDDAY, null, null, null)));
        assertThat(reads.committed(facts.connection(), CUTOFF, Optional.of(cut))).singleElement().satisfies(held -> assertThat(held.minor()).isEqualTo(BigInteger.valueOf(20_00)));
        assertThat(facts.bound).as("the statement is the same one, with the same one parameter").containsExactly(Date.valueOf(CUTOFF));

        ScriptedStatements lines = new ScriptedStatements(sql -> List.of());
        reads.writtenOff(lines.connection(), CUTOFF, Optional.of(cut));
        assertThat(lines.prepared).singleElement().asString().contains(LendingFundedAsOfCutoffReads.AT_THE_CUT);
        assertThat(lines.bound).containsExactly(Date.valueOf(CUTOFF), Timestamp.from(cut));

        ScriptedStatements noCut = new ScriptedStatements(sql -> List.of());
        reads.writtenOff(noCut.connection(), CUTOFF, Optional.empty());
        assertThat(noCut.prepared).singleElement().asString().doesNotContain(LendingFundedAsOfCutoffReads.AT_THE_CUT);
        assertThat(noCut.bound).containsExactly(Date.valueOf(CUTOFF));
    }

    @Test
    @DisplayName("written off: the credits less the debits per currency and scale, so a reversed write-off nets, past a long, sorted, the lines counted")
    void theWrittenOffFolds() {
        ScriptedStatements statements = new ScriptedStatements(sql -> List.of(
                row("currency", "EUR", "scale", 2, "direction", "CREDIT", "total", new BigDecimal("9223372036854775807"), "lines", 2L),
                row("currency", "EUR", "scale", 2, "direction", "DEBIT", "total", new BigDecimal("7"), "lines", 1L),
                row("currency", "EUR", "scale", 3, "direction", "CREDIT", "total", new BigDecimal("5"), "lines", 1L),
                row("currency", "USD", "scale", 2, "direction", "DEBIT", "total", new BigDecimal("3"), "lines", 1L)));

        List<LendingFundedAsOfCutoff.WrittenOff> writtenOff = reads.writtenOff(statements.connection(), CUTOFF, Optional.empty());

        assertThat(writtenOff).extracting(LendingFundedAsOfCutoff.WrittenOff::currency, LendingFundedAsOfCutoff.WrittenOff::scale, LendingFundedAsOfCutoff.WrittenOff::minor, LendingFundedAsOfCutoff.WrittenOff::lines).containsExactly(
                org.assertj.core.groups.Tuple.tuple("EUR", 2, new BigInteger("9223372036854775800"), 3L),
                org.assertj.core.groups.Tuple.tuple("EUR", 3, BigInteger.valueOf(5), 1L),
                org.assertj.core.groups.Tuple.tuple("USD", 2, BigInteger.valueOf(-3), 1L));
    }

    @Test
    @DisplayName("a read that fails throws, naming its operation and SQLSTATE and no row: never an empty answer that would read as a zero")
    void aFailureThrows() {
        Connection broken = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            throw new SQLException("the secret row", "08006");
        });

        assertThatThrownBy(() -> reads.committed(broken, CUTOFF, Optional.empty())).isInstanceOf(AccountingStorageException.class)
                .hasMessageContaining("committed").hasMessageNotContaining("secret");
        assertThatThrownBy(() -> reads.writtenOff(broken, CUTOFF, Optional.empty())).isInstanceOf(AccountingStorageException.class)
                .hasMessageContaining("written off").hasMessageNotContaining("secret");
    }
}
