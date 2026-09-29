package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@link SuspenseReadings} over JDBC (ADR-0033: explicit SQL, no mapper); no locks. */
public final class JdbcSuspenseReadings implements SuspenseReadings {

    @Override
    public List<OpenSuspenseRemainder> openRemainders(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT side, amount_minor, released_minor, currency, scale"
                                + " FROM reconciliation.suspense_item"
                                + " WHERE status <> 'RELEASED'")) {
            try (ResultSet rows = read.executeQuery()) {
                List<OpenSuspenseRemainder> remainders = new ArrayList<>();
                while (rows.next()) {
                    CurrencyCode currency = CurrencyCode.of(rows.getString("currency").trim());
                    int scale = rows.getInt("scale");
                    Money amount =
                            Money.ofPersisted(rows.getLong("amount_minor"), currency, scale);
                    Money released =
                            Money.ofPersisted(
                                    rows.getLong("released_minor"), currency, scale);
                    remainders.add(
                            new OpenSuspenseRemainder(
                                    SuspenseSide.valueOf(rows.getString("side")),
                                    amount.minus(released)));
                }
                return List.copyOf(remainders);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the open suspense remainders", failure);
        }
    }

    @Override
    public long openCount(Connection unitOfWork) {
        return scalarLong(
                unitOfWork,
                "SELECT count(*) FROM reconciliation.suspense_item"
                        + " WHERE status <> 'RELEASED'");
    }

    @Override
    public Optional<LocalDate> oldestOpenedOn(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT min(opened_on) AS oldest FROM reconciliation.suspense_item"
                                + " WHERE status <> 'RELEASED'")) {
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(row.getObject("oldest", LocalDate.class));
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the oldest suspense item", failure);
        }
    }

    @Override
    public long unownedCount(Connection unitOfWork) {
        return scalarLong(
                unitOfWork,
                "SELECT count(*) FROM reconciliation.suspense_item i"
                        + " LEFT JOIN reconciliation.break b ON b.id = i.break_id"
                        + " WHERE i.status <> 'RELEASED'"
                        + " AND (b.id IS NULL OR b.status = 'RESOLVED')");
    }

    @Override
    public Set<String> ownedOriginRefs(Connection unitOfWork, SuspenseOrigin origin) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT origin_ref FROM reconciliation.suspense_item"
                                + " WHERE origin = ?")) {
            read.setString(1, origin.name());
            try (ResultSet rows = read.executeQuery()) {
                Set<String> refs = new HashSet<>();
                while (rows.next()) {
                    refs.add(rows.getString("origin_ref"));
                }
                return Set.copyOf(refs);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the owned origin references", failure);
        }
    }

    @Override
    public List<UUID> knownEntries(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT journal_entry_id AS entry_id FROM reconciliation.park"
                                + " UNION"
                                + " SELECT entry_id FROM reconciliation.suspense_item")) {
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> entries = new ArrayList<>();
                while (rows.next()) {
                    entries.add(rows.getObject("entry_id", UUID.class));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the suspense-known entries", failure);
        }
    }

    private long scalarLong(Connection unitOfWork, String sql) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the suspense counters", failure);
        }
    }
}
