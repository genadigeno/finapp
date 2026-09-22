package com.finapp.merchant;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link FeeScheduleStore} over JDBC (`P6-TSK-004`, ADR-0033: explicit SQL, no ORM).
 *
 * <p><strong>There is no {@code UPDATE} and no {@code DELETE} against either fee table in this
 * file</strong>, and there is no way to add one that would work: the application role holds no
 * such grant, and `V004`'s trigger refuses both unconditionally for every writer. What can be
 * written here is a new schedule, a new version, and the merchant's pointer — which is the
 * whole of {@code INV-MER-03}'s "change creates a new version effective forward".
 *
 * <p><strong>The version number is minted optimistically and arbitrated by a unique
 * index</strong> ({@link #insertVersionIfNumberIsFree}), because a schedule row cannot be
 * locked: PostgreSQL requires the {@code UPDATE} privilege to take a row lock, and `V004`
 * deliberately withholds it from a table nothing may ever update. The immutability and the
 * choice of arbiter are the same fact stated twice. That is the entire concurrency story of
 * this class; everything else reads immutable rows.
 */
public final class JdbcFeeScheduleStore implements FeeScheduleStore<Connection> {

    private static final String SCHEDULE_COLUMNS = "id, name, currency, created_at, created_by";

    private static final MoneyColumns.ColumnNames FIXED = MoneyColumns.columnsFor("fixed");

    private static final String VERSION_COLUMNS =
            "id, fee_schedule_id, version, rate,"
                    + " " + FIXED.amountMinor() + ", " + FIXED.currency() + ", " + FIXED.scale()
                    + ", rounding_policy, refund_fee_policy, effective_from, created_at,"
                    + " created_by";

    /**
     * The resolution rule, as SQL — the same ordering {@code FeeScheduleVersion.EFFECTIVE_ORDER}
     * expresses in Java. Greatest {@code effective_from} first, ties broken by the greater
     * version number, so superseding a not-yet-effective version at the same instant works.
     */
    private static final String EFFECTIVE_ORDER = " ORDER BY effective_from DESC, version DESC";

    /** SQLState 23505. The arbiter's answer, not a failure. */
    private static final String UNIQUE_VIOLATION = "23505";

    // ----------------------------------------------------------------- schedules

    @Override
    public void insertSchedule(Connection unitOfWork, FeeSchedule schedule) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.fee_schedule (" + SCHEDULE_COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?)")) {
            insert.setObject(1, schedule.id().value());
            insert.setString(2, schedule.name());
            insert.setString(3, schedule.currency().code());
            insert.setTimestamp(4, Timestamp.from(schedule.createdAt()));
            insert.setString(5, schedule.createdBy());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("inserting a fee schedule", failure));
        }
    }

    @Override
    public Optional<FeeSchedule> findSchedule(Connection unitOfWork, FeeScheduleId id) {
        return readSchedule(unitOfWork, id);
    }

    @Override
    public List<FeeSchedule> listSchedules(Connection unitOfWork) {
        List<FeeSchedule> schedules = new ArrayList<>();
        try (PreparedStatement select =
                        unitOfWork.prepareStatement(
                                "SELECT " + SCHEDULE_COLUMNS + " FROM merchant.fee_schedule"
                                        + " ORDER BY created_at DESC, id DESC");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                schedules.add(scheduleFrom(rows));
            }
            return List.copyOf(schedules);
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("listing fee schedules", failure));
        }
    }

    // ----------------------------------------------------------------- versions

    @Override
    public boolean insertVersionIfNumberIsFree(
            Connection unitOfWork, FeeScheduleVersion version) {
        // The savepoint is what keeps a lost race cheap: a unique violation poisons the
        // transaction, and without one the audit record written beside this version would be
        // lost with it. Rolled back to on refusal, released on success.
        Savepoint attempt;
        try {
            attempt = unitOfWork.setSavepoint("fee_schedule_version");
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("preparing a fee schedule version insert", failure));
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.fee_schedule_version (" + VERSION_COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, version.id().value());
            insert.setObject(2, version.scheduleId().value());
            insert.setInt(3, version.version());
            insert.setBigDecimal(4, version.rate().toBigDecimal());
            insert.setLong(5, MoneyColumns.amountMinorOf(version.fixed()));
            insert.setString(6, MoneyColumns.currencyOf(version.fixed()));
            insert.setShort(7, MoneyColumns.scaleOf(version.fixed()));
            insert.setString(8, version.roundingPolicy().policyName());
            insert.setString(9, version.refundFeePolicy().name());
            insert.setTimestamp(10, Timestamp.from(version.effectiveFrom()));
            insert.setTimestamp(11, Timestamp.from(version.createdAt()));
            insert.setString(12, version.createdBy());
            insert.executeUpdate();
            unitOfWork.releaseSavepoint(attempt);
            return true;
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                // Another writer took this number. Not an error - the arbiter answering.
                rollbackTo(unitOfWork, attempt);
                return false;
            }
            throw new MerchantStorageException(
                    DatabaseFailure.describe("inserting a fee schedule version", failure));
        }
    }

    private static void rollbackTo(Connection unitOfWork, Savepoint attempt) {
        try {
            unitOfWork.rollback(attempt);
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe(
                            "abandoning a lost fee schedule version race", failure));
        }
    }

    @Override
    public int nextVersionNumber(Connection unitOfWork, FeeScheduleId scheduleId) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT COALESCE(MAX(version), 0) FROM merchant.fee_schedule_version"
                                + " WHERE fee_schedule_id = ?")) {
            select.setObject(1, scheduleId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1) + 1;
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading the next fee schedule version", failure));
        }
    }

    @Override
    public Optional<FeeScheduleVersion> findVersion(
            Connection unitOfWork, FeeScheduleVersionId id) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + VERSION_COLUMNS + " FROM merchant.fee_schedule_version"
                                + " WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(versionFrom(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading a fee schedule version", failure));
        }
    }

    @Override
    public List<FeeScheduleVersion> listVersions(
            Connection unitOfWork, FeeScheduleId scheduleId) {
        List<FeeScheduleVersion> versions = new ArrayList<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + VERSION_COLUMNS + " FROM merchant.fee_schedule_version"
                                + " WHERE fee_schedule_id = ?" + EFFECTIVE_ORDER)) {
            select.setObject(1, scheduleId.value());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    versions.add(versionFrom(rows));
                }
            }
            return List.copyOf(versions);
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("listing fee schedule versions", failure));
        }
    }

    @Override
    public Optional<FeeScheduleVersion> findEffectiveVersion(
            Connection unitOfWork, FeeScheduleId scheduleId, Instant instant) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + VERSION_COLUMNS + " FROM merchant.fee_schedule_version"
                                + " WHERE fee_schedule_id = ? AND effective_from <= ?"
                                + EFFECTIVE_ORDER + " LIMIT 1")) {
            select.setObject(1, scheduleId.value());
            select.setTimestamp(2, Timestamp.from(instant));
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(versionFrom(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("resolving the effective fee schedule version",
                            failure));
        }
    }

    // ----------------------------------------------------------------- assignment

    @Override
    public Optional<FeeScheduleId> findAssignment(Connection unitOfWork, MerchantId merchantId) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT fee_schedule_id FROM merchant.merchant_fee_schedule"
                                + " WHERE merchant_id = ?")) {
            select.setObject(1, merchantId.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? Optional.of(FeeScheduleId.of(rows.getObject(1, UUID.class)))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading a merchant's fee schedule", failure));
        }
    }

    @Override
    public void assign(
            Connection unitOfWork,
            MerchantId merchantId,
            Optional<FeeScheduleId> previous,
            FeeScheduleId scheduleId,
            String reason,
            Instant at) {
        // The acting operator, from the established context (ADR-0021: an unestablished actor
        // is an error, never a default).
        Actor actor = SecurityContext.require();
        String sql =
                previous.isPresent()
                        ? "UPDATE merchant.merchant_fee_schedule"
                                + " SET fee_schedule_id = ?, assigned_at = ?, assigned_by = ?"
                                + " WHERE merchant_id = ?"
                        : "INSERT INTO merchant.merchant_fee_schedule"
                                + " (fee_schedule_id, assigned_at, assigned_by, merchant_id)"
                                + " VALUES (?, ?, ?, ?)";
        try (PreparedStatement write = unitOfWork.prepareStatement(sql)) {
            write.setObject(1, scheduleId.value());
            write.setTimestamp(2, Timestamp.from(at));
            write.setString(3, actor.id());
            write.setObject(4, merchantId.value());
            if (write.executeUpdate() != 1) {
                // Unreachable under the merchant row's lock; loud rather than silent if an
                // unknown writer proves otherwise (INV-CON-01).
                throw new MerchantStorageException(
                        "a locked merchant's fee schedule assignment wrote no row");
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("assigning a merchant's fee schedule", failure));
        }
        appendAssignmentHistory(unitOfWork, merchantId, previous, scheduleId, reason, actor, at);
    }

    @Override
    public Optional<FeeScheduleVersion> findEffectiveVersionFor(
            Connection unitOfWork, MerchantId merchantId, Instant instant) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + prefixed(VERSION_COLUMNS, "v")
                                + " FROM merchant.merchant_fee_schedule a"
                                + " JOIN merchant.fee_schedule_version v"
                                + "   ON v.fee_schedule_id = a.fee_schedule_id"
                                + " WHERE a.merchant_id = ? AND v.effective_from <= ?"
                                + " ORDER BY v.effective_from DESC, v.version DESC"
                                + " LIMIT 1")) {
            select.setObject(1, merchantId.value());
            select.setTimestamp(2, Timestamp.from(instant));
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(versionFrom(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe(
                            "resolving a merchant's effective fee schedule version", failure));
        }
    }

    // -----------------------------------------------------------------

    private void appendAssignmentHistory(
            Connection unitOfWork,
            MerchantId merchantId,
            Optional<FeeScheduleId> previous,
            FeeScheduleId scheduleId,
            String reason,
            Actor actor,
            Instant at) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.merchant_fee_schedule_event"
                                + " (merchant_id, from_fee_schedule_id, to_fee_schedule_id,"
                                + " reason, actor_id, actor_type, occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, merchantId.value());
            if (previous.isPresent()) {
                insert.setObject(2, previous.get().value());
            } else {
                insert.setNull(2, Types.OTHER);
            }
            insert.setObject(3, scheduleId.value());
            insert.setString(4, reason);
            insert.setString(5, actor.id());
            insert.setString(6, actor.type().name());
            insert.setTimestamp(7, Timestamp.from(at));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("recording a fee schedule assignment", failure));
        }
    }

    private Optional<FeeSchedule> readSchedule(Connection unitOfWork, FeeScheduleId id) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + SCHEDULE_COLUMNS + " FROM merchant.fee_schedule"
                                + " WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(scheduleFrom(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading a fee schedule", failure));
        }
    }

    private static FeeSchedule scheduleFrom(ResultSet rows) throws SQLException {
        return FeeSchedule.rehydrate(
                FeeScheduleId.of(rows.getObject(1, UUID.class)),
                rows.getString(2),
                CurrencyCode.of(rows.getString(3).stripTrailing()),
                rows.getTimestamp(4).toInstant(),
                rows.getString(5));
    }

    private static FeeScheduleVersion versionFrom(ResultSet rows) throws SQLException {
        Money fixed = MoneyColumns.read(rows.getLong(5), rows.getString(6), rows.getShort(7));
        return FeeScheduleVersion.rehydrate(
                FeeScheduleVersionId.of(rows.getObject(1, UUID.class)),
                FeeScheduleId.of(rows.getObject(2, UUID.class)),
                rows.getInt(3),
                FeeRate.ofStored(rows.getBigDecimal(4)),
                fixed,
                // Never defaulted: an unrecognised stored policy throws rather than becoming
                // somebody's guess, because a guess would change the meaning of the decision
                // being replayed (RoundingPolicy.ofName's rule).
                RoundingPolicy.ofName(rows.getString(8)),
                RefundFeePolicy.ofName(rows.getString(9)),
                rows.getTimestamp(10).toInstant(),
                rows.getTimestamp(11).toInstant(),
                rows.getString(12));
    }

    /** Qualifies a column list for a joined read, keeping one definition of the columns. */
    private static String prefixed(String columns, String alias) {
        StringBuilder qualified = new StringBuilder();
        for (String column : columns.split(",")) {
            if (qualified.length() > 0) {
                qualified.append(", ");
            }
            qualified.append(alias).append('.').append(column.trim());
        }
        return qualified.toString();
    }
}
