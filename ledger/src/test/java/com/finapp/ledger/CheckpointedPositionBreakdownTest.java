package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link CheckpointedPositionBreakdown} does with what its statements hand back (`P16-REM-001`): the real class over a scripted
 * connection, so the fold of a checkpoint and the lines since, the store past the threshold, and every way it falls back to the whole
 * history are each held without a database. That the statements are what PostgreSQL runs, the watermark's argument under concurrent
 * posting, the racing writers, the version and the cluster refusal are {@code PositionCheckpointDatabaseTest}'s.
 */
@DisplayName("the merchant payable read from a commit-order checkpoint, over a scripted connection (P16-REM-001)")
class CheckpointedPositionBreakdownTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String FACING_CLEARING = "SETTLEMENT_CLEARING|DEBIT";
    private static final List<AccountPurpose> PRECEDENCE = List.of(AccountPurpose.SETTLEMENT_CLEARING);
    private static final LedgerAccountId ACCOUNT = LedgerAccountId.next(IDS);

    /** One line as the statements return it: on a CREDIT-normal payable, classified, and settled below the read's watermark or not. */
    private static Map<String, Object> line(String direction, long minor, String counterparty, boolean settled) {
        Map<String, Object> row = new HashMap<>();
        row.put("direction", direction);
        row.put("amount_minor", minor);
        row.put("currency", "EUR");
        row.put("scale", (short) 2);
        row.put("origin", null);
        row.put("counterparty", counterparty);
        row.put("namespace", null);
        row.put("settled", settled);
        row.put("watermark", "900");
        return row;
    }

    /** A stored checkpoint's bucket row, joined to its checkpoint's watermark and line count. */
    private static Map<String, Object> stored(String direction, long minor, String counterparty) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("lines", 40L);
        row.put("watermark", "500");
        row.put("direction", direction);
        row.put("counterparty", counterparty);
        row.put("reconciliation_attributed", false);
        row.put("accounting_origin", false);
        row.put("namespace", null);
        row.put("amount_minor", minor);
        row.put("currency", "EUR");
        row.put("scale", (short) 2);
        return row;
    }

    /** A scripted connection: each statement answered by what it is, and every savepoint, bound value and stored row recorded. */
    private static final class Script {
        List<Map<String, Object>> checkpoint = List.of();
        List<Map<String, Object>> lines = List.of();
        boolean noSavepoint;
        boolean checkpointUnreadable;
        boolean storeRefused;
        final List<String> journal = new ArrayList<>();
        final Map<Integer, Object> since = new TreeMap<>();
        final Map<Integer, Object> store = new TreeMap<>();
        final List<Map<Integer, Object>> buckets = new ArrayList<>();

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "createArrayOf" -> Proxy.newProxyInstance(Array.class.getClassLoader(), new Class<?>[] {Array.class}, (array, called, given) -> null);
                case "setSavepoint" -> {
                    if (noSavepoint) {
                        throw new SQLException("Cannot establish a savepoint in auto-commit mode.", "25000");
                    }
                    journal.add("savepoint");
                    yield Proxy.newProxyInstance(Savepoint.class.getClassLoader(), new Class<?>[] {Savepoint.class}, (point, called, given) -> null);
                }
                case "releaseSavepoint" -> {
                    journal.add("released");
                    yield null;
                }
                case "rollback" -> {
                    journal.add("rolled back to the savepoint");
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private PreparedStatement statement(String sql) {
            Map<Integer, Object> bound = new TreeMap<>();
            String which;
            if (sql.startsWith("SELECT currency, normal_balance")) {
                which = "account";
            } else if (sql.equals(CheckpointedPositionBreakdown.LATEST)) {
                which = "latest";
            } else if (sql.equals(CheckpointedPositionBreakdown.SINCE)) {
                which = "since";
            } else if (sql.equals(CheckpointedPositionBreakdown.STORE)) {
                which = "store";
            } else if (sql.equals(CheckpointedPositionBreakdown.STORE_BUCKET)) {
                which = "bucket";
            } else {
                throw new AssertionError("an unscripted statement: " + sql);
            }
            journal.add(which);
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith("set") && args != null && args.length == 2 && args[0] instanceof Integer at) {
                    bound.put(at, args[1]);
                    return null;
                }
                switch (name) {
                    case "executeQuery" -> {
                        switch (which) {
                            case "account" -> {
                                return rows(List.of(Map.of("currency", "EUR", "normal_balance", "CREDIT")));
                            }
                            case "latest" -> {
                                if (checkpointUnreadable) {
                                    throw new SQLException("permission denied for function pg_control_system", "42501");
                                }
                                return rows(checkpoint);
                            }
                            case "since" -> {
                                since.putAll(bound);
                                return rows(lines);
                            }
                            case "store" -> {
                                if (storeRefused) {
                                    throw new SQLException("cannot execute INSERT in a read-only transaction", "25006");
                                }
                                store.putAll(bound);
                                return rows(List.of(Map.of("id", UUID.randomUUID())));
                            }
                            default -> throw new AssertionError(which + " is not a query");
                        }
                    }
                    case "addBatch" -> {
                        buckets.add(new TreeMap<>(bound));
                        return null;
                    }
                    case "executeBatch" -> {
                        return new int[buckets.size()];
                    }
                    case "close" -> {
                        return null;
                    }
                    default -> throw new UnsupportedOperationException(name);
                }
            });
        }

        Money storedSum() {
            Money sum = Money.ofPersisted(0, EUR, 2);
            for (Map<Integer, Object> bucket : buckets) {
                Money total = Money.ofPersisted((Long) bucket.get(8), EUR, (Short) bucket.get(10));
                sum = "CREDIT".equals(bucket.get(3)) ? sum.plus(total) : sum.minus(total);
            }
            return sum;
        }
    }

    private static ResultSet rows(List<Map<String, Object>> rows) {
        Iterator<Map<String, Object>> cursor = rows.iterator();
        Object[] current = new Object[1];
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> {
                if (!cursor.hasNext()) {
                    yield false;
                }
                current[0] = cursor.next();
                yield true;
            }
            case "getString", "getLong", "getShort", "getBoolean", "getObject" -> ((Map<?, ?>) current[0]).get(args[0]);
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static PositionBreakdown.Breakdown read(Script script, int threshold) {
        return new CheckpointedPositionBreakdown(threshold).breakdown(script.connection(), ACCOUNT, PRECEDENCE, List.of());
    }

    @Test
    @DisplayName("with no checkpoint the read folds from the journal's beginning, and stores nothing below its threshold")
    void withNoCheckpointItFoldsEverything() {
        Script script = new Script();
        script.lines = List.of(line("CREDIT", 10_000, FACING_CLEARING, true), line("DEBIT", 250, null, true));

        PositionBreakdown.Breakdown breakdown = read(script, 5);

        assertThat(script.since.get(6)).as("the lines from the beginning").isEqualTo("0");
        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(9_750, EUR, 2));
        assertThat(script.journal).as("the checkpoint read in its savepoint, released; no store").doesNotContain("store", "rolled back to the savepoint")
                .containsSubsequence("savepoint", "latest", "released", "since");
    }

    @Test
    @DisplayName("a checkpoint and the lines since it are the whole fold: the same position and the same buckets")
    void aCheckpointAndTheLinesSinceAreTheWholeFold() {
        Script script = new Script();
        script.checkpoint = List.of(stored("CREDIT", 90_000, FACING_CLEARING), stored("DEBIT", 3_000, null));
        script.lines = List.of(line("CREDIT", 10_000, FACING_CLEARING, false), line("DEBIT", 250, null, false));

        PositionBreakdown.Breakdown breakdown = read(script, 5);

        assertThat(script.since.get(6)).as("only the lines at or above the checkpoint's watermark").isEqualTo("500");
        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(90_000 + 10_000 - 3_000 - 250, EUR, 2));
        assertThat(breakdown.buckets()).as("two buckets, each the checkpoint's and the new lines' sum").hasSize(2)
                .anySatisfy(bucket -> assertThat(bucket.total()).isEqualTo(Money.ofPersisted(100_000, EUR, 2)))
                .anySatisfy(bucket -> assertThat(bucket.total()).isEqualTo(Money.ofPersisted(3_250, EUR, 2)));
        assertThat(script.journal).doesNotContain("store");
    }

    @Test
    @DisplayName("past its threshold a read stores the checkpoint and the lines below its own watermark - never one above it")
    void pastTheThresholdItStoresTheFoldBelowItsWatermark() {
        Script script = new Script();
        script.checkpoint = List.of(stored("CREDIT", 90_000, FACING_CLEARING));
        script.lines = List.of(
                line("CREDIT", 10_000, FACING_CLEARING, true),
                line("DEBIT", 250, null, true),
                line("CREDIT", 7, FACING_CLEARING, false));

        PositionBreakdown.Breakdown breakdown = read(script, 2);

        assertThat(breakdown.position()).as("the answer folds every line").isEqualTo(Money.ofPersisted(90_000 + 10_000 - 250 + 7, EUR, 2));
        assertThat(script.store.get(5)).as("at the watermark its own statement read").isEqualTo("900");
        assertThat(script.store.get(6)).as("the checkpoint's 40 lines and the two settled").isEqualTo(42L);
        assertThat(script.storedSum()).as("the stored fold leaves out the line its snapshot could not prove final")
                .isEqualTo(Money.ofPersisted(90_000 + 10_000 - 250, EUR, 2));
        assertThat(script.journal).containsSubsequence("savepoint", "store", "bucket", "released");
    }

    @Test
    @DisplayName("below its threshold a read stores nothing")
    void belowTheThresholdNothingIsStored() {
        Script script = new Script();
        script.lines = List.of(line("CREDIT", 10_000, FACING_CLEARING, true), line("DEBIT", 250, null, false));

        read(script, 2);

        assertThat(script.journal).doesNotContain("store");
    }

    @Test
    @DisplayName("a checkpoint that cannot be read is rolled back to its savepoint, and the read folds the whole history")
    void anUnreadableCheckpointFoldsTheWholeHistory() {
        Script script = new Script();
        script.checkpointUnreadable = true;
        script.lines = List.of(line("CREDIT", 10_000, FACING_CLEARING, false));

        PositionBreakdown.Breakdown breakdown = read(script, 5);

        assertThat(script.journal).containsSubsequence("savepoint", "latest", "rolled back to the savepoint", "since");
        assertThat(script.since.get(6)).isEqualTo("0");
        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(10_000, EUR, 2));
    }

    @Test
    @DisplayName("a stored bucket in a currency foreign to the account is refused, and the read folds the whole history")
    void aForeignBucketFoldsTheWholeHistory() {
        Script script = new Script();
        Map<String, Object> foreign = new HashMap<>(stored("CREDIT", 90_000, FACING_CLEARING));
        foreign.put("currency", "USD");
        script.checkpoint = List.of(foreign);
        script.lines = List.of(line("CREDIT", 10_000, FACING_CLEARING, false));

        PositionBreakdown.Breakdown breakdown = read(script, 5);

        assertThat(script.journal).contains("rolled back to the savepoint");
        assertThat(script.since.get(6)).isEqualTo("0");
        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(10_000, EUR, 2));
    }

    @Test
    @DisplayName("a store the database refuses is rolled back to its savepoint, and the read still answers")
    void aRefusedStoreNeverFailsTheRead() {
        Script script = new Script();
        script.storeRefused = true;
        script.lines = List.of(line("CREDIT", 10_000, FACING_CLEARING, true), line("DEBIT", 250, null, true));

        PositionBreakdown.Breakdown breakdown = read(script, 2);

        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(9_750, EUR, 2));
        assertThat(script.journal).containsSubsequence("savepoint", "store", "rolled back to the savepoint").doesNotContain("bucket");
    }

    @Test
    @DisplayName("without a unit of work to hold a savepoint, no checkpoint is read or stored, and the whole history is folded")
    void withoutASavepointTheWholeHistoryIsFolded() {
        Script script = new Script();
        script.noSavepoint = true;
        script.lines = List.of(line("CREDIT", 10_000, FACING_CLEARING, true), line("DEBIT", 250, null, true));

        PositionBreakdown.Breakdown breakdown = read(script, 1);

        assertThat(script.journal).containsExactly("account", "since");
        assertThat(script.since.get(6)).isEqualTo("0");
        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(9_750, EUR, 2));
    }
}
