package com.finapp.ledger;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A connection that answers the posting cutoff's two reads as a database would (`P14-TSK-006`): the shared key's
 * statement ({@link PostingCutoffs#SHARED_READ_LOCK}) with a transaction isolation, then the cutoff row - unset, a date,
 * or missing - recording every statement it was asked to prepare. Any other statement is a test failure: the floor and
 * the approval guard read nothing else.
 */
final class CutoffScript {

    final List<String> statements = new CopyOnWriteArrayList<>();
    private final String isolation;
    private final Optional<Optional<LocalDate>> row;

    private CutoffScript(String isolation, Optional<Optional<LocalDate>> row) {
        this.isolation = isolation;
        this.row = row;
    }

    /** The cutoff unset - no period ever locked - under READ COMMITTED. */
    static CutoffScript unset() {
        return new CutoffScript(PostingCutoffs.READ_COMMITTED, Optional.of(Optional.empty()));
    }

    /** The cutoff at {@code closedThrough} under READ COMMITTED. */
    static CutoffScript at(LocalDate closedThrough) {
        return new CutoffScript(PostingCutoffs.READ_COMMITTED, Optional.of(Optional.of(closedThrough)));
    }

    /** The cutoff at {@code closedThrough} under {@code isolation} ({@code current_setting}'s spelling). */
    static CutoffScript at(LocalDate closedThrough, String isolation) {
        return new CutoffScript(isolation, Optional.of(Optional.of(closedThrough)));
    }

    /** The cutoff row missing - the fail-closed case. */
    static CutoffScript missingRow() {
        return new CutoffScript(PostingCutoffs.READ_COMMITTED, Optional.empty());
    }

    Connection connection() {
        return (Connection) Proxy.newProxyInstance(CutoffScript.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        throw new AssertionError("the cutoff read uses statements only, not Connection."
                                + method.getName());
                    }
                    String sql = (String) args[0];
                    statements.add(sql);
                    return statement(sql);
                });
    }

    private PreparedStatement statement(String sql) {
        final Object[] answer;
        if (sql.equals(PostingCutoffs.SHARED_READ_LOCK)) {
            answer = new Object[] {null, isolation};
        } else if (sql.equals(PostingCutoffs.READ) || sql.equals(PostingCutoffs.READ + " FOR SHARE")) {
            answer = row.map(cut -> new Object[] {cut.orElse(null)}).orElse(null);
        } else {
            throw new AssertionError("not a cutoff statement: " + sql);
        }
        return (PreparedStatement) Proxy.newProxyInstance(CutoffScript.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (statement, call, values) -> switch (call.getName()) {
                    case "executeQuery" -> resultSet(answer);
                    case "close" -> null;
                    default -> throw new AssertionError("PreparedStatement." + call.getName());
                });
    }

    private static ResultSet resultSet(Object[] row) {
        boolean[] read = {false};
        return (ResultSet) Proxy.newProxyInstance(CutoffScript.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, call, values) -> switch (call.getName()) {
                    case "next" -> {
                        boolean next = row != null && !read[0];
                        read[0] = true;
                        yield next;
                    }
                    case "getString" -> (String) row[(Integer) values[0] - 1];
                    case "getObject" -> row[(Integer) values[0] - 1];
                    case "close" -> null;
                    default -> throw new AssertionError("ResultSet." + call.getName());
                });
    }
}
