package com.finapp.app.accounting;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A stand-in connection for a port's statements (`P14-TSK-017`; the {@code ProductReconciliationDisclosureTest} shape): each statement
 * answered by a function of its text, its parameters and the statements recorded in order. The statements' behaviour against
 * PostgreSQL is {@code OperationalReportsDatabaseTest}'s (owed, C5).
 */
final class ScriptedStatements {

    final List<String> prepared = new ArrayList<>();
    final List<Object> bound = new ArrayList<>();
    private final Function<String, List<Map<String, Object>>> answers;

    ScriptedStatements(Function<String, List<Map<String, Object>>> answers) {
        this.answers = answers;
    }

    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String sql = (String) args[0];
                    prepared.add(sql);
                    return statement(sql);
                });
    }

    private PreparedStatement statement(String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length == 2) {
                        bound.add(args[1]);
                        return null;
                    }
                    return switch (name) {
                        case "executeQuery" -> rows(answers.apply(sql).iterator());
                        case "close" -> null;
                        case "setFetchSize" -> null;
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
                        case "getString", "getBigDecimal", "getLong", "getInt", "getDate", "getObject", "getTimestamp" -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> row = (Map<String, Object>) current[0];
                            return row.get(String.valueOf(args[0])); // By name, or by index: "1".
                        }
                        case "close" -> {
                            return null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
