package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The projection rebuild never touches a journal line (`P14-TSK-026`, ADR-0119 decision 1; {@code INV-LED-03},
 * {@code INV-LED-04}; {@code CLAUDE.md} rules 5 and 11): it holds no posting collaborator, and every statement it runs is
 * one of the constants read here - each naming a journal table only in a {@code SELECT}, and writing only the projection
 * row and its own record. The rank beneath, the application role's {@code SELECT, INSERT} on the journal (V004), is the
 * database's ({@code ProjectionRebuildDatabaseTest}, owed). Probe: {@code MUTATION_TESTING.md} §2's journal-line row.
 */
@DisplayName("the projection rebuild never touches a journal line (P14-TSK-026)")
class ProjectionRebuildNeverPostsTest {

    /** The rebuild's classes, every one that runs on its path. */
    private static final List<Class<?>> REBUILD = List.of(ProjectionRebuilds.class, JdbcProjectionRebuildRows.class,
            JdbcProjectionRebuildStore.class, ProjectionRebuild.class);

    /** What can post, reverse, adjust or announce - none may be a field of the rebuild. */
    private static final Set<Class<?>> POSTERS = Set.of(PostingEffect.class, JournalEntryStore.class,
            JdbcJournalEntryStore.class, PostingService.class, ReversalService.class, AdjustmentService.class,
            BalanceProjection.class, HoldService.class, com.finapp.platform.outbox.OutboxWriter.class);

    /** A table a statement writes. */
    private static final Pattern WRITES = Pattern.compile("(?i)\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|TRUNCATE)\\s+([a-z_.]+)");

    @Test
    @DisplayName("no class on the rebuild's path holds a poster: no PostingEffect, journal store, posting, reversal or"
            + " adjustment command, projection apply port, hold service or outbox")
    void noPosterIsHeld() {
        for (Class<?> type : REBUILD) {
            for (Field field : type.getDeclaredFields()) {
                assertThat(POSTERS).as(type.getSimpleName() + "." + field.getName()).doesNotContain(field.getType());
            }
        }
    }

    @Test
    @DisplayName("every statement names a journal table only to read it, and writes only the projection row and the"
            + " rebuild's own record")
    void everyStatementReadsTheJournalAndWritesOnlyItsRows() throws IllegalAccessException {
        List<String> statements = statements();
        assertThat(statements).as("the guard sees the rebuild's SQL").hasSizeGreaterThan(10);
        for (String sql : statements) {
            String lower = sql.toLowerCase(Locale.ROOT);
            if (lower.contains("journal_")) {
                assertThat(lower).as(sql).startsWith("select").doesNotContainPattern("\\b(insert|update|delete|truncate)\\b");
            }
            Matcher writes = WRITES.matcher(sql);
            while (writes.find()) {
                assertThat(writes.group(1)).as(sql).isIn("ledger.account_balance", "ledger.projection_rebuild");
            }
            assertThat(lower).as(sql).doesNotContain("pg_advisory");
        }
    }

    @Test
    @DisplayName("the projection write is V006's four columns at the row's identity, and the insert never overwrites a row"
            + " a posting created")
    void theProjectionWriteIsV006sColumns() {
        assertThat(JdbcProjectionRebuildRows.WRITE_ROW_SQL)
                .isEqualTo("UPDATE ledger.account_balance SET posted_minor = ?, holds_minor = ?, last_entry_seq = ?,"
                        + " updated_at = ? WHERE ledger_account_id = ? AND scale = ? AND currency = ?");
        assertThat(JdbcProjectionRebuildRows.INSERT_ROW_SQL).endsWith("ON CONFLICT (ledger_account_id) DO NOTHING");
        assertThat(JdbcProjectionRebuildRows.SHARE_ACCOUNT_SQL).endsWith("FOR KEY SHARE");
        assertThat(JdbcProjectionRebuildRows.LOCK_ROW_SQL).endsWith("FOR UPDATE");
    }

    private static List<String> statements() throws IllegalAccessException {
        List<String> found = new ArrayList<>();
        for (Class<?> type : List.of(JdbcProjectionRebuildRows.class, JdbcProjectionRebuildStore.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    if (value.matches("(?is)\\s*(select|insert|update|delete|with).*")) {
                        found.add(value);
                    }
                }
            }
        }
        return found;
    }
}
