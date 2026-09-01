package com.finapp.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The audit table's own constraints, exercised directly.
 *
 * <p><strong>Why separately from {@link AuditWriterTest}.</strong> Most of these cannot be
 * reached through the writer: {@link AuditRecord} validates every bound and both enumerations
 * before SQL sees them. That is the argument for testing them here rather than treating them as
 * covered — a constraint application code cannot reach is one only the database will ever
 * enforce, against an operator, a migration, or a writer nobody has written yet. Reviews of
 * {@code V002} and {@code V005} both found this gap after the fact; writing these with the
 * migration is the correction.
 *
 * <p>Written as the <strong>migrator</strong>, deliberately. These are assertions about the
 * table's shape, and the migrator is the role that can put a malformed row in front of it; the
 * application role's inability to do most of this is {@link AuditImmutabilityTest}'s subject,
 * not this one's.
 */
@Tag("database")
class AuditRecordSchemaTest {

    private static final String TABLE = "platform.audit_record";
    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");

    private static Connection migrator;

    @BeforeAll
    static void connect() throws SQLException {
        migrator = DatabaseRoles.migrator();
        migrator.setAutoCommit(true);
        clean();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (migrator != null) {
            clean();
            migrator.close();
        }
    }

    private static void clean() throws SQLException {
        try (Statement statement = migrator.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE + " WHERE operation LIKE 'schema-probe%'");
        }
    }

    @Test
    @DisplayName("an actor type the enum does not declare is refused")
    void unknownActorTypesAreRefused() {
        // The reason the column is constrained rather than merely bounded: 'Employee' and
        // 'EMPLOYEE' would otherwise both be storable, and every audit query grouped by actor
        // type would silently return two populations where there is one.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().actorType("Employee")))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().actorType("ROBOT")))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("every actor type the enum declares is accepted")
    void everyDeclaredActorTypeIsAccepted() throws SQLException {
        // The positive half, derived from the enum rather than listed by hand: adding a constant
        // without extending the constraint fails here, and this is the direction the hermetic
        // AuditEnumMigrationTest cannot check against a live database.
        for (ActorType type : ActorType.values()) {
            insert(row().actorType(type.name()));
        }

        assertThat(probeCount()).isEqualTo(ActorType.values().length);
        clean();
    }

    @Test
    @DisplayName("every outcome the enum declares is accepted and nothing else is")
    void outcomesAreConstrained() throws SQLException {
        for (AuditOutcome outcome : AuditOutcome.values()) {
            insert(row().outcome(outcome.name()));
        }
        assertThat(probeCount()).isEqualTo(AuditOutcome.values().length);
        clean();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().outcome("PARTIAL")))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a present but empty reason is refused, so absence stays distinguishable")
    void anEmptyReasonIsRefused() throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().reason("")))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));

        // NULL is legitimate and must stay so: most actions require no reason.
        insert(row().reason(null));
        assertThat(probeCount()).isEqualTo(1);
        clean();
    }

    @Test
    @DisplayName("the questions an audit record must answer cannot be left null")
    void theMandatoryColumnsAreNotNull() {
        // INV-AUD-01 at the schema rather than only in AuditRecord's constructor. The writer is
        // not the only thing that will ever insert here.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().actorId(null)))
                .matches(e -> NOT_NULL_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().operation(null)))
                .matches(e -> NOT_NULL_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().targetId(null)))
                .matches(e -> NOT_NULL_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().correlationId(null)))
                .matches(e -> NOT_NULL_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("over-long values are refused rather than stored")
    void boundsAreEnforced() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().actorId("a".repeat(201))))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().correlationId("c".repeat(129))))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().changeSummary("s".repeat(4001))))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("values at exactly the maximum length are accepted, so the bounds are not off by one")
    void boundsAreInclusive() throws SQLException {
        // AuditRecord's constants and these constraints are two statements of one bound. If they
        // disagreed by one, a record that passed validation would be refused by the database -
        // and under ADR-0010 a refused audit write rolls back the action it was recording.
        insert(
                row().actorId("a".repeat(AuditRecord.MAX_NAME_LENGTH))
                        .correlationId("c".repeat(128))
                        .reason("r".repeat(AuditRecord.MAX_REASON_LENGTH))
                        .changeSummary("s".repeat(AuditRecord.MAX_CHANGE_SUMMARY_LENGTH)));

        assertThat(probeCount()).isEqualTo(1);
        clean();
    }

    @Test
    @DisplayName("the same audit id cannot be recorded twice")
    void theIdIsUnique() throws SQLException {
        UUID id = UUID.randomUUID();
        insert(row().auditId(id));

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(row().auditId(id)))
                .matches(e -> UNIQUE_VIOLATION.equals(e.getSQLState()));
        clean();
    }

    // -----------------------------------------------------------------

    private static Row row() {
        return new Row();
    }

    /** A valid row, with one field at a time made invalid by the test. */
    private static final class Row {
        private UUID auditId = UUID.randomUUID();
        private String actorId = "actor-1";
        private String actorType = "SYSTEM";
        private String operation = "schema-probe.Op";
        private String targetType = "Probe";
        private String targetId = "target-1";
        private String reason;
        private String outcome = "SUCCEEDED";
        private String correlationId = "flow-1";
        private String changeSummary;

        Row auditId(UUID value) {
            this.auditId = value;
            return this;
        }

        Row actorId(String value) {
            this.actorId = value;
            return this;
        }

        Row actorType(String value) {
            this.actorType = value;
            return this;
        }

        Row operation(String value) {
            this.operation = value;
            return this;
        }

        Row targetId(String value) {
            this.targetId = value;
            return this;
        }

        Row reason(String value) {
            this.reason = value;
            return this;
        }

        Row outcome(String value) {
            this.outcome = value;
            return this;
        }

        Row correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        Row changeSummary(String value) {
            this.changeSummary = value;
            return this;
        }
    }

    private static void insert(Row row) throws SQLException {
        String sql =
                "INSERT INTO " + TABLE + " (audit_id, actor_id, actor_type, occurred_at, operation, "
                        + "target_type, target_id, reason, outcome, correlation_id, change_summary) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = migrator.prepareStatement(sql)) {
            insert.setObject(1, row.auditId);
            insert.setString(2, row.actorId);
            insert.setString(3, row.actorType);
            insert.setTimestamp(4, Timestamp.from(OCCURRED));
            insert.setString(5, row.operation);
            insert.setString(6, row.targetType);
            insert.setString(7, row.targetId);
            insert.setString(8, row.reason);
            insert.setString(9, row.outcome);
            insert.setString(10, row.correlationId);
            insert.setString(11, row.changeSummary);
            insert.executeUpdate();
        }
    }

    private static int probeCount() throws SQLException {
        try (Statement statement = migrator.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT count(*) FROM " + TABLE + " WHERE operation LIKE 'schema-probe%'")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
