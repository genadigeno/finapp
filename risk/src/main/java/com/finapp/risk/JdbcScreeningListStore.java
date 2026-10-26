package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ScreeningListStore} over {@code risk.screening_list_version} ({@code risk V018}; `P13-TSK-022`): explicit SQL on
 * the caller's connection (ADR-0033), every write one statement conditional on the state it leaves, the trigger judging
 * it again and stamping the instants on the database clock. Risk's own table only - no other schema is named
 * ({@code INV-RSK-02}). A failure is named by its operation and SQLSTATE only, never the driver's message.
 */
public final class JdbcScreeningListStore implements ScreeningListStore {

    /** Risk's advisory namespace (ADR-0107 decision 11) - the list's key beside the operations' policy keys. */
    static final int NAMESPACE = 13;

    /** A0′: the list's administration lock, held to commit. */
    static final String LOCK_LIST = "SELECT pg_advisory_xact_lock(" + NAMESPACE + ", hashtext(?))";

    static final String COLUMNS = "id, list, version, ordinal, status, recorded_by, recorded_by_type, recorded_at,"
            + " activated_by, activated_by_type, activated_at, retired_at, enqueue_next_at, enqueue_cursor, enqueued,"
            + " enqueue_completed_at, correlation_id";

    static final String MAX_ORDINAL = "SELECT coalesce(max(ordinal), 0) FROM risk.screening_list_version WHERE list = ?";

    /** Born RECORDED; the label's unique the arbiter beneath the list's lock. */
    static final String INSERT = "INSERT INTO risk.screening_list_version (id, list, version, ordinal, status,"
            + " recorded_by, recorded_by_type, recorded_at, enqueued, correlation_id)"
            + " VALUES (?, ?, ?, ?, 'RECORDED', ?, ?, clock_timestamp(), 0, ?)"
            + " ON CONFLICT (list, version) DO NOTHING RETURNING " + COLUMNS;

    static final String FIND = "SELECT " + COLUMNS + " FROM risk.screening_list_version WHERE id = ?";

    static final String FIND_BY_LABEL = "SELECT " + COLUMNS + " FROM risk.screening_list_version"
            + " WHERE list = ? AND version = ?";

    static final String LOCK = FIND + " FOR UPDATE";

    static final String LOCK_ACTIVE = "SELECT " + COLUMNS + " FROM risk.screening_list_version"
            + " WHERE list = ? AND status = 'ACTIVE' FOR UPDATE";

    /** ACTIVE -> RETIRED, the enqueue stopped; retired_at the trigger's. */
    static final String RETIRE = "UPDATE risk.screening_list_version SET status = 'RETIRED', enqueue_next_at = NULL"
            + " WHERE id = ? AND status = 'ACTIVE'";

    /** RECORDED -> ACTIVE, the enqueue due at once; activated_at the trigger's. */
    static final String ACTIVATE = "UPDATE risk.screening_list_version SET status = 'ACTIVE', activated_by = ?,"
            + " activated_by_type = ?, correlation_id = ?, enqueue_next_at = clock_timestamp()"
            + " WHERE id = ? AND status = 'RECORDED' RETURNING " + COLUMNS;

    /** The enqueue's claim: the oldest version in force whose enqueue permit has come, SKIP LOCKED (R9). */
    static final String CLAIM_ENQUEUE_DUE = "SELECT " + COLUMNS + " FROM risk.screening_list_version"
            + " WHERE status = 'ACTIVE' AND enqueue_next_at <= clock_timestamp()"
            + " ORDER BY enqueue_next_at, id LIMIT 1 FOR UPDATE SKIP LOCKED";

    /** The enqueue's advance - due again at once, or cleared (the census complete; the trigger stamps it). */
    static final String ADVANCE = "UPDATE risk.screening_list_version SET enqueue_cursor = ?, enqueued = enqueued + ?,"
            + " enqueue_next_at = CASE WHEN ? THEN NULL ELSE clock_timestamp() END"
            + " WHERE id = ? AND status = 'ACTIVE' AND enqueue_next_at IS NOT NULL RETURNING " + COLUMNS;

    @Override
    public void lockList(Connection unitOfWork, ScreeningList list) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(list, "list");
        try (PreparedStatement lock = unitOfWork.prepareStatement(LOCK_LIST)) {
            lock.setString(1, "screening_list:" + list.name());
            lock.execute();
        } catch (SQLException failure) {
            throw failed("locking a screening list", failure);
        }
    }

    @Override
    public int maxOrdinal(Connection unitOfWork, ScreeningList list) {
        Objects.requireNonNull(list, "list");
        try (PreparedStatement read = unitOfWork.prepareStatement(MAX_ORDINAL)) {
            read.setString(1, list.name());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a screening list's last ordinal", failure);
        }
    }

    @Override
    public Optional<ScreeningListVersion> insert(Connection unitOfWork, NewVersion version) {
        Objects.requireNonNull(version, "version");
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT)) {
            insert.setObject(1, version.id().value());
            insert.setString(2, version.list().name());
            insert.setString(3, version.version());
            insert.setInt(4, version.ordinal());
            insert.setString(5, version.recorder().id());
            insert.setString(6, version.recorder().type().name());
            insert.setString(7, version.correlationId());
            return single(insert);
        } catch (SQLException failure) {
            throw failed("recording a screening list version", failure);
        }
    }

    @Override
    public Optional<ScreeningListVersion> find(Connection unitOfWork, ScreeningListVersionId id) {
        return byId(unitOfWork, id, FIND, "reading a screening list version");
    }

    @Override
    public Optional<ScreeningListVersion> findByLabel(Connection unitOfWork, ScreeningList list, String version) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(version, "version");
        try (PreparedStatement read = unitOfWork.prepareStatement(FIND_BY_LABEL)) {
            read.setString(1, list.name());
            read.setString(2, version);
            return single(read);
        } catch (SQLException failure) {
            throw failed("reading a screening list version by its label", failure);
        }
    }

    @Override
    public Optional<ScreeningListVersion> lock(Connection unitOfWork, ScreeningListVersionId id) {
        return byId(unitOfWork, id, LOCK, "locking a screening list version");
    }

    @Override
    public Optional<ScreeningListVersion> lockActive(Connection unitOfWork, ScreeningList list) {
        Objects.requireNonNull(list, "list");
        try (PreparedStatement lock = unitOfWork.prepareStatement(LOCK_ACTIVE)) {
            lock.setString(1, list.name());
            return single(lock);
        } catch (SQLException failure) {
            throw failed("locking a screening list's version in force", failure);
        }
    }

    @Override
    public boolean retire(Connection unitOfWork, ScreeningListVersionId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement retire = unitOfWork.prepareStatement(RETIRE)) {
            retire.setObject(1, id.value());
            return retire.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("retiring a screening list version", failure);
        }
    }

    @Override
    public Optional<ScreeningListVersion> activate(Connection unitOfWork, ScreeningListVersionId id, Actor activator,
            String correlationId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(activator, "activator");
        Objects.requireNonNull(correlationId, "correlationId");
        try (PreparedStatement activate = unitOfWork.prepareStatement(ACTIVATE)) {
            activate.setString(1, activator.id());
            activate.setString(2, activator.type().name());
            activate.setString(3, correlationId);
            activate.setObject(4, id.value());
            return single(activate);
        } catch (SQLException failure) {
            throw failed("activating a screening list version", failure);
        }
    }

    @Override
    public Optional<ScreeningListVersion> claimEnqueueDue(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement claim = unitOfWork.prepareStatement(CLAIM_ENQUEUE_DUE)) {
            return single(claim);
        } catch (SQLException failure) {
            throw failed("claiming a screening list version's enqueue", failure);
        }
    }

    @Override
    public ScreeningListVersion advanceEnqueue(Connection unitOfWork, ScreeningListVersionId id, Optional<UUID> cursor,
            int born, boolean complete) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cursor, "cursor");
        try (PreparedStatement advance = unitOfWork.prepareStatement(ADVANCE)) {
            if (cursor.isPresent()) {
                advance.setObject(1, cursor.get());
            } else {
                advance.setNull(1, Types.OTHER);
            }
            advance.setInt(2, born);
            advance.setBoolean(3, complete);
            advance.setObject(4, id.value());
            return single(advance).orElseThrow(() -> new IllegalStateException(
                    "the claimed list version's enqueue moved under its own row lock: the claim was bypassed"));
        } catch (SQLException failure) {
            throw failed("advancing a screening list version's enqueue", failure);
        }
    }

    // -----------------------------------------------------------------

    private static Optional<ScreeningListVersion> byId(Connection unitOfWork, ScreeningListVersionId id, String sql,
            String operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static Optional<ScreeningListVersion> single(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            return row.next() ? Optional.of(read(row)) : Optional.empty();
        }
    }

    static ScreeningListVersion read(ResultSet row) throws SQLException {
        String activator = row.getString("activated_by");
        String activatorType = row.getString("activated_by_type");
        Optional<RiskCase.Person> activatedBy = activator == null ? Optional.empty()
                : Optional.of(new RiskCase.Person(activator, ActorType.valueOf(activatorType)));
        return new ScreeningListVersion(
                ScreeningListVersionId.of(row.getObject("id", UUID.class)),
                ScreeningList.valueOf(row.getString("list")),
                row.getString("version"),
                row.getInt("ordinal"),
                ScreeningListVersionStatus.valueOf(row.getString("status")),
                new RiskCase.Person(row.getString("recorded_by"), ActorType.valueOf(row.getString("recorded_by_type"))),
                row.getTimestamp("recorded_at").toInstant(),
                activatedBy,
                instant(row, "activated_at"),
                instant(row, "retired_at"),
                instant(row, "enqueue_next_at"),
                Optional.ofNullable(row.getObject("enqueue_cursor", UUID.class)),
                row.getLong("enqueued"),
                instant(row, "enqueue_completed_at"),
                row.getString("correlation_id"));
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp stamp = row.getTimestamp(column);
        return stamp == null ? Optional.empty() : Optional.of(stamp.toInstant());
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
