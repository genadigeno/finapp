package com.finapp.platform.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A JDBC double that answers a store's statements from a script (`P16-TSK-017`), so a sweep's REAL listing, its REAL row
 * readers and its REAL failure record run with no database: each query is answered by the first script whose fragment its
 * text contains, or by no rows, and every statement is recorded with its parameters. What the SQL selects on a real
 * PostgreSQL is the owed database cases'; this proves what the code sends and what it does with what comes back.
 *
 * <p>A row is a map of column label to value, read case-insensitively; a column the row does not hold reads as SQL
 * {@code NULL} ({@code wasNull} true), so a row holding only an {@code id} is exactly a row a reader refuses.
 * {@code statement_timestamp()} read through {@code createStatement} answers this double's own fixed instant.
 */
public final class ScriptedListingConnection {

    /** One statement sent: its text and its parameters by index. */
    public record Sent(String sql, Map<Integer, Object> parameters) {

        /** The parameter at {@code index}, 1-based as JDBC numbers them. */
        public Object parameter(int index) {
            return parameters.get(index);
        }
    }

    private record Script(String fragment, List<Map<String, Object>> rows, int updated) {}

    private final List<Script> scripts = new ArrayList<>();
    private final List<Sent> sent = new ArrayList<>();
    private final Instant now;
    private int savepoints;
    private int rolledBackToSavepoint;

    public ScriptedListingConnection(Instant now) {
        this.now = now;
    }

    /** Answers any query whose text contains {@code fragment} with {@code rows}, in order. */
    public ScriptedListingConnection answering(String fragment, List<Map<String, Object>> rows) {
        scripts.add(new Script(fragment, rows, 0));
        return this;
    }

    /** Answers any update whose text contains {@code fragment} with {@code updated} rows changed. */
    public ScriptedListingConnection updating(String fragment, int updated) {
        scripts.add(new Script(fragment, List.of(), updated));
        return this;
    }

    /** One row: its column labels to their values, in order. */
    public static Map<String, Object> row(Object... labelsAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            row.put(((String) labelsAndValues[i]).toLowerCase(Locale.ROOT), labelsAndValues[i + 1]);
        }
        return row;
    }

    /** A version-4 id: a valid UUID that no domain writer could mint (ADR-0013 refuses all but version 7). */
    public static UUID versionFour() {
        UUID random = UUID.randomUUID();
        if (random.version() != 4) {
            throw new AssertionError("UUID.randomUUID() answered version " + random.version());
        }
        return random;
    }

    /** Every statement sent so far, in order. */
    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** The statements whose text contains {@code fragment}, in order. */
    public List<Sent> sentContaining(String fragment) {
        return sent.stream().filter(s -> s.sql().contains(fragment)).toList();
    }

    /** How many savepoints were set, and how many were rolled back to. */
    public int savepoints() {
        return savepoints;
    }

    public int rolledBackToSavepoint() {
        return rolledBackToSavepoint;
    }

    /** The connection itself. */
    public Connection connection() {
        return proxy(Connection.class, (self, method, arguments) -> switch (method.getName()) {
            case "prepareStatement" -> statement((String) arguments[0]);
            case "createStatement" -> clock();
            case "createArrayOf" -> proxy(java.sql.Array.class, (a, m, args) -> switch (m.getName()) {
                case "getArray" -> arguments[1];
                case "free" -> null;
                default -> throw new AssertionError("Array." + m.getName() + " is not scripted");
            });
            case "setSavepoint" -> {
                savepoints++;
                yield proxy(Savepoint.class, (s, m, a) -> switch (m.getName()) {
                    case "getSavepointName" -> "scripted";
                    case "hashCode" -> System.identityHashCode(s);
                    case "equals" -> s == a[0];
                    case "toString" -> "scripted savepoint";
                    default -> throw new AssertionError("Savepoint." + m.getName() + " is not scripted");
                });
            }
            case "rollback" -> {
                if (arguments != null && arguments.length == 1) {
                    rolledBackToSavepoint++;
                }
                yield null;
            }
            case "releaseSavepoint", "close", "setAutoCommit", "commit" -> null;
            case "getAutoCommit", "isClosed" -> false;
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == arguments[0];
            case "toString" -> "scripted listing connection";
            default -> throw new AssertionError("Connection." + method.getName() + " is not scripted");
        });
    }

    private PreparedStatement statement(String sql) {
        Map<Integer, Object> parameters = new TreeMap<>();
        return proxy(PreparedStatement.class, (self, method, arguments) -> {
            String name = method.getName();
            if (name.startsWith("set") && arguments != null && arguments.length >= 2 && arguments[0] instanceof Integer index) {
                parameters.put(index, arguments[1]);
                return null;
            }
            return switch (name) {
                case "executeQuery" -> {
                    sent.add(new Sent(sql, Map.copyOf(parameters)));
                    yield rows(scripts.stream().filter(s -> sql.contains(s.fragment())).findFirst()
                            .map(Script::rows).orElse(List.of()));
                }
                case "executeUpdate" -> {
                    sent.add(new Sent(sql, Map.copyOf(parameters)));
                    yield scripts.stream().filter(s -> sql.contains(s.fragment())).findFirst()
                            .map(Script::updated).orElse(1);
                }
                case "close" -> null;
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == arguments[0];
                case "toString" -> "scripted statement";
                default -> throw new AssertionError("PreparedStatement." + name + " is not scripted");
            };
        });
    }

    private Statement clock() {
        return proxy(Statement.class, (self, method, arguments) -> switch (method.getName()) {
            case "executeQuery" -> {
                String sql = (String) arguments[0];
                if (!sql.contains("statement_timestamp()")) {
                    throw new AssertionError("a plain statement other than the clock's: " + sql);
                }
                sent.add(new Sent(sql, Map.of()));
                yield rows(List.of(row("statement_timestamp", Timestamp.from(now))));
            }
            case "close" -> null;
            default -> throw new AssertionError("Statement." + method.getName() + " is not scripted");
        });
    }

    private static ResultSet rows(List<Map<String, Object>> rows) {
        int[] at = {-1};
        boolean[] lastNull = {false};
        return proxy(ResultSet.class, (self, method, arguments) -> {
            String name = method.getName();
            if (name.equals("next")) {
                at[0]++;
                return at[0] < rows.size();
            }
            if (name.equals("close")) {
                return null;
            }
            if (name.equals("wasNull")) {
                return lastNull[0];
            }
            if (name.startsWith("get") && arguments != null && arguments.length >= 1) {
                Map<String, Object> row = rows.get(at[0]);
                Object value = arguments[0] instanceof Integer index
                        ? new ArrayList<>(row.values()).get(index - 1)
                        : row.get(((String) arguments[0]).toLowerCase(Locale.ROOT));
                lastNull[0] = value == null;
                return switch (name) {
                    case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                    case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                    case "getShort" -> value == null ? (short) 0 : ((Number) value).shortValue();
                    case "getBoolean" -> value != null && (Boolean) value;
                    case "getString" -> value == null ? null : value.toString();
                    default -> value;
                };
            }
            throw new AssertionError("ResultSet." + name + " is not scripted");
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
