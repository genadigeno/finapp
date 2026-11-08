// The rules' own test: `semgrep --test` over this directory, offline, on every run of infra/scripts/sast.sh, and the
// planted cases of THREAT_MODEL.md T45, which the job's real scan must also refuse by name.
package plant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

class SqlRules {

    private static final String SELECT = """
            SELECT id
              FROM t
            """;
    private static final String COLUMNS = "a, b";
    private static final String BOTH = SELECT + " WHERE " + COLUMNS;

    // --------------------------------------------------------------- what the codebase legitimately does

    void textBlock(Connection c) throws Exception {
        // ok: finapp-sql-text-not-constant
        c.prepareStatement(SELECT);
    }

    void constantsConcatenated(Connection c) throws Exception {
        // ok: finapp-sql-text-not-constant
        c.prepareStatement(BOTH + " AND x = ?");
    }

    void aLocalConstant(Connection c) throws Exception {
        String sql = "SELECT 1";
        // ok: finapp-sql-text-not-constant
        c.prepareStatement(sql);
    }

    void aLocalBuiltFromConstants(Connection c) throws Exception {
        String sql = SELECT + " WHERE id = ?";
        // ok: finapp-sql-text-not-constant
        c.prepareStatement(sql);
    }

    void aTernaryOfConstants(Connection c, boolean lock) throws Exception {
        // ok: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + (lock ? " FOR UPDATE" : ""));
    }

    void aLocalTernaryOfConstants(Connection c, boolean lock) throws Exception {
        String suffix = lock ? " FOR UPDATE" : "";
        // ok: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + suffix);
    }

    void constantStatement(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            // ok: finapp-sql-text-not-constant
            s.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        }
    }

    void aHelperGivenAConstant(Connection c) throws Exception {
        // ok: finapp-sql-helper-given-non-constant
        edge(c, SELECT, 1);
    }

    private static boolean edge(Connection c, String sql, int id) throws Exception {
        // ok: finapp-sql-text-not-constant
        c.prepareStatement(sql);
        return true;
    }

    private static void aFragment(Connection c, String lock) throws Exception {
        // ok: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + lock);
    }

    void aMethodOnAConstantGivenConstants(Connection c) throws Exception {
        // ok: finapp-sql-text-not-constant
        c.prepareStatement("SELECT " + COLUMNS.replace("a,", "") + " FROM t");
    }

    void aSqlListOnAnEnum(Connection c) throws Exception {
        // ok: finapp-sql-text-not-constant
        c.prepareStatement("SELECT 1 WHERE x IN (" + Status.terminalSqlValueList() + ")");
    }

    // ---------------------------------------------------------------- a statement built from anything else

    public void aMethodOnAConstantGivenAValue(Connection c, String id) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement("SELECT " + COLUMNS.replace("a,", id) + " FROM t");
    }

    public void aListMethodGivenAValue(Connection c, String id) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement("SELECT 1 WHERE x IN (" + Status.terminalSqlValueList(id) + ")");
    }

    void anotherMethodOnAnEnum(Connection c) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement("SELECT " + Status.display());
    }

    private void aHelperNotInTheVocabulary(Connection c, String text) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + text);
    }

    public void aVarStatement(Connection c, String id) throws Exception {
        try (var s = c.createStatement()) {
            // ok: finapp-sql-text-not-constant
            s.execute("SELECT 1");
            // ruleid: finapp-sql-text-not-constant
            s.executeUpdate("DELETE FROM t WHERE id = '" + id + "'");
        }
    }

    public void aJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbc, String id) {
        // ok: finapp-sql-text-not-constant
        jdbc.update("DELETE FROM t WHERE id = ?", id);
        // ruleid: finapp-sql-text-not-constant
        jdbc.update("DELETE FROM t WHERE id = '" + id + "'");
    }

    public void aJdbcClient(org.springframework.jdbc.core.simple.JdbcClient client, String id) {
        // ruleid: finapp-sql-text-not-constant
        client.sql("SELECT * FROM t WHERE id = '" + id + "'").query();
    }

    public void concatenation(Connection c, String id) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement("SELECT * FROM t WHERE id = '" + id + "'");
    }

    void orderBy(Connection c, String order) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + " ORDER BY " + order);
    }

    private void aLocalFromACall(Connection c, Request request) throws Exception {
        String id = request.id();
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + id);
    }

    public void format(Connection c, String id) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement(String.format("SELECT %s", id));
    }

    public void formatted(Connection c, String id) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement("SELECT %s".formatted(id));
    }

    public void builder(Connection c, String id) throws Exception {
        StringBuilder sql = new StringBuilder(SELECT);
        sql.append(id);
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement(sql.toString());
    }

    public void aStatement(Connection c, String id) throws Exception {
        try (Statement s = c.createStatement()) {
            // ruleid: finapp-sql-text-not-constant
            s.executeQuery("SELECT " + id);
        }
    }

    private String table;

    void aField(Connection c) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement("SELECT * FROM " + table);
    }

    void anotherObjectsField(Connection c, Request request) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement("SELECT * FROM " + request.table);
    }

    private void aLoopVariable(Connection c, List<String> ids) throws Exception {
        for (String id : ids) {
            // ruleid: finapp-sql-text-not-constant
            c.prepareStatement(SELECT + id);
        }
    }

    private void aLambdaParameter(Connection c, List<String> ids) {
        ids.forEach(id -> {
            try {
                // ruleid: finapp-sql-text-not-constant
                c.prepareStatement(SELECT + id);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void anArrayElement(Connection c, String[] values) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + values[0]);
    }

    void aHelperGivenAValue(Connection c, String id) throws Exception {
        // ruleid: finapp-sql-helper-given-non-constant
        edge(c, "UPDATE t SET a = '" + id + "'", 1);
    }

    void aFragmentGivenAValue(Connection c, String id) throws Exception {
        // ruleid: finapp-sql-helper-given-non-constant
        aFragment(c, id);
    }

    void aConstantNamedLocal(Connection c, String id) throws Exception {
        // ruleid: finapp-sql-constant-name-not-constant
        String ID = id;
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + ID);
    }

    // A name spelt like a constant is what the rules above trust as one, so the spelling is held to the fact.

    // ruleid: finapp-sql-constant-name-not-constant
    private String MUTABLE = "x";

    // ruleid: finapp-sql-constant-name-not-constant
    void aConstantNamedParameter(Connection c, String CLAUSE) throws Exception {
        // ruleid: finapp-sql-text-not-constant
        c.prepareStatement(SELECT + CLAUSE);
    }

    // ok: finapp-sql-constant-name-not-constant
    private static final String HONEST = "SELECT 1";

    record Request(String table) {
        String id() {
            return table;
        }
    }

    enum Status {
        OPEN;

        static String terminalSqlValueList() {
            return "'OPEN'";
        }

        static String terminalSqlValueList(String extra) {
            return "'OPEN'" + extra;
        }

        static String display() {
            return "open";
        }
    }
}
