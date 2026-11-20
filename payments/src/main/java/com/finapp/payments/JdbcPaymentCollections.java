package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PaymentCollections} over JDBC (`P17-TSK-032`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>The search walks payments `V042`'s indexes - {@code (created_at, id)}, the party's and the credited account's - each a
 * keyset range read backwards, its window the index condition. The dispute queue and the held payments read tables that hold
 * the platform's open disputes and its open holds; the routing versions read one small table. The dispute queue reads
 * {@link JdbcDisputeStore#FOUND}, so a queue row and a by-id read never describe one dispute two ways.
 */
public final class JdbcPaymentCollections implements PaymentCollections {

    private final JdbcRoutingStore routing = new JdbcRoutingStore();

    /** Every attempt status that waits on a provider's answer - the machine's own {@code *_UNKNOWN} words, never a list here. */
    private static final String UNKNOWN_ATTEMPT_STATUSES = java.util.Arrays.stream(PaymentAttemptStatus.values())
            .map(Enum::name)
            .filter(name -> name.endsWith("_UNKNOWN"))
            .map(name -> "'" + name + "'")
            .collect(java.util.stream.Collectors.joining(", "));

    // ------------------------------------------------------------------ the payment search

    @Override
    public List<PaymentHead> payments(
            Connection unitOfWork, PaymentFilter filter, Optional<PaymentCursor> after, int limit) {
        Objects.requireNonNull(filter, "filter must not be null");
        Objects.requireNonNull(after, "after must not be null");
        StringBuilder sql = new StringBuilder(
                "SELECT i.id, i.party_id, i.status, i.amount_minor, i.currency, i.scale, i.created_at, a.rail,"
                        + " a.status AS attempt_status"
                        + " FROM payments.payment_intent i"
                        + " LEFT JOIN payments.payment_attempt a ON a.intent_id = i.id"
                        + " WHERE i.created_at >= ? AND i.created_at < ?");
        List<Object> arguments = new ArrayList<>(List.of(Timestamp.from(filter.from()), Timestamp.from(filter.to())));
        filter.party().ifPresent(party -> {
            sql.append(" AND i.party_id = ?");
            arguments.add(party);
        });
        filter.creditAccounts().ifPresent(accounts -> {
            if (accounts.size() == 1) {
                // A merchant's one payable - the common case - is an equality, which V042's (credit_account_id, created_at,
                // id) serves as an ordered range; several payables (one per currency) are a set the window's order sorts.
                sql.append(" AND i.credit_account_id = ?");
                arguments.add(accounts.iterator().next().value());
            } else {
                sql.append(" AND i.credit_account_id = ANY (?)");
                arguments.add(accounts.stream().map(LedgerAccountId::value).toArray(UUID[]::new));
            }
        });
        filter.status().ifPresent(status -> {
            sql.append(" AND i.status = ?");
            arguments.add(status.name());
        });
        filter.rail().ifPresent(rail -> {
            sql.append(" AND a.rail = ?");
            arguments.add(rail.value());
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (i.created_at, i.id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.createdAt()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY i.created_at DESC, i.id DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(sql.toString())) {
            bind(unitOfWork, select, arguments);
            List<PaymentHead> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    page.add(new PaymentHead(
                            PaymentIntentId.of(row.getObject("id", UUID.class)),
                            row.getObject("party_id", UUID.class),
                            PaymentIntentStatus.valueOf(row.getString("status")),
                            // The STORED scale, never re-derived (INV-MON-05).
                            Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("currency")),
                                    row.getShort("scale")),
                            row.getTimestamp("created_at").toInstant(),
                            Optional.ofNullable(row.getString("rail")).map(RailId::of),
                            Optional.ofNullable(row.getString("attempt_status")).map(PaymentAttemptStatus::valueOf)));
                }
            }
            return List.copyOf(page);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("searching the payments", failure));
        }
    }

    // ------------------------------------------------------------------ the dispute queue

    @Override
    public List<DisputeStore.Found> disputes(
            Connection unitOfWork,
            Optional<DisputeStage> stage,
            Optional<Instant> deadlineBefore,
            Optional<DisputeCursor> after,
            int limit) {
        StringBuilder sql = new StringBuilder(JdbcDisputeStore.FOUND + " WHERE true");
        List<Object> arguments = new ArrayList<>();
        stage.ifPresent(named -> {
            sql.append(" AND d.stage = ?");
            arguments.add(named.name());
        });
        deadlineBefore.ifPresent(bound -> {
            sql.append(" AND d.respond_by < ?");
            arguments.add(Timestamp.from(bound));
        });
        after.ifPresent(cursor -> {
            if (cursor.respondBy().isPresent()) {
                // After a dated row: a later deadline, the same one with a later id, or any undated row (they sort last).
                sql.append(" AND (d.respond_by > ? OR (d.respond_by = ? AND d.id > ?) OR d.respond_by IS NULL)");
                Timestamp at = Timestamp.from(cursor.respondBy().get());
                arguments.add(at);
                arguments.add(at);
                arguments.add(cursor.id());
            } else {
                sql.append(" AND d.respond_by IS NULL AND d.id > ?");
                arguments.add(cursor.id());
            }
        });
        sql.append(" ORDER BY d.respond_by ASC NULLS LAST, d.id ASC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(sql.toString())) {
            bind(unitOfWork, select, arguments);
            List<DisputeStore.Found> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    page.add(JdbcDisputeStore.found(row));
                }
            }
            return List.copyOf(page);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("paging the dispute queue", failure));
        }
    }

    // ------------------------------------------------------------------ the routing policy versions

    @Override
    public List<RoutingPolicyVersion> routingVersions(Connection unitOfWork, Optional<Integer> before, int limit) {
        return routing.pageVersions(unitOfWork, Objects.requireNonNull(before, "before must not be null"), limit);
    }

    @Override
    public Optional<RoutingPolicyVersion> routingVersion(Connection unitOfWork, int version) {
        return routing.findVersionByNumber(unitOfWork, version);
    }

    // ------------------------------------------------------------------ the held card payments

    @Override
    public List<HeldPayment> heldPayments(
            Connection unitOfWork, UUID withheldParty, Optional<HeldCursor> after, int limit) {
        Objects.requireNonNull(withheldParty, "withheldParty must not be null");
        Objects.requireNonNull(after, "after must not be null");
        StringBuilder sql = new StringBuilder(
                "SELECT h.intent_id, h.attempt_id, h.held_at, h.held_until, i.party_id, i.amount_minor, i.currency, i.scale,"
                        + " a.rail"
                        + " FROM payments.payment_capture_hold h"
                        + " JOIN payments.payment_intent i ON i.id = h.intent_id"
                        + " JOIN payments.payment_attempt a ON a.id = h.attempt_id"
                        + " WHERE h.resolution IS NULL AND i.party_id <> ?");
        List<Object> arguments = new ArrayList<>(List.of(withheldParty));
        after.ifPresent(cursor -> {
            sql.append(" AND (h.held_until, h.intent_id) > (?, ?)");
            arguments.add(Timestamp.from(cursor.heldUntil()));
            arguments.add(cursor.intent());
        });
        sql.append(" ORDER BY h.held_until ASC, h.intent_id ASC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(sql.toString())) {
            bind(unitOfWork, select, arguments);
            List<HeldPayment> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    page.add(new HeldPayment(
                            PaymentIntentId.of(row.getObject("intent_id", UUID.class)),
                            PaymentAttemptId.of(row.getObject("attempt_id", UUID.class)),
                            row.getObject("party_id", UUID.class),
                            Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("currency")),
                                    row.getShort("scale")),
                            RailId.of(row.getString("rail")),
                            row.getTimestamp("held_at").toInstant(),
                            row.getTimestamp("held_until").toInstant()));
                }
            }
            return List.copyOf(page);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("paging the held payments", failure));
        }
    }

    // ------------------------------------------------------------------ the payments report

    @Override
    public List<OutcomeCount> outcomes(Connection unitOfWork, Instant from, Instant to) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT a.rail, a.status AS attempt_status, i.status AS intent_status, count(*) AS n"
                        + " FROM payments.payment_intent i"
                        + " LEFT JOIN payments.payment_attempt a ON a.intent_id = i.id"
                        + " WHERE i.created_at >= ? AND i.created_at < ?"
                        + " GROUP BY a.rail, a.status, i.status"
                        + " ORDER BY a.rail NULLS LAST, a.status NULLS LAST, i.status")) {
            select.setTimestamp(1, Timestamp.from(from));
            select.setTimestamp(2, Timestamp.from(to));
            List<OutcomeCount> counts = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    counts.add(new OutcomeCount(
                            Optional.ofNullable(row.getString("rail")).map(RailId::of),
                            Optional.ofNullable(row.getString("attempt_status")).map(PaymentAttemptStatus::valueOf),
                            PaymentIntentStatus.valueOf(row.getString("intent_status")),
                            row.getLong("n")));
                }
            }
            return List.copyOf(counts);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("counting a day's payments", failure));
        }
    }

    @Override
    public List<UnknownCount> unknownBacklog(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT 'ATTEMPT' AS kind, status, count(*) AS n, min(created_at) AS oldest"
                        + " FROM payments.payment_attempt WHERE status IN (" + UNKNOWN_ATTEMPT_STATUSES + ") GROUP BY status"
                        + " UNION ALL"
                        + " SELECT 'REFUND' AS kind, status, count(*) AS n, min(created_at) AS oldest"
                        + " FROM payments.refund WHERE status = 'UNKNOWN' GROUP BY status"
                        + " ORDER BY kind, status")) {
            List<UnknownCount> counts = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    counts.add(new UnknownCount(row.getString("kind"), row.getString("status"), row.getLong("n"),
                            row.getTimestamp("oldest").toInstant()));
                }
            }
            return List.copyOf(counts);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("reading the unknown-state backlog", failure));
        }
    }

    // ------------------------------------------------------------------

    private static void bind(Connection unitOfWork, PreparedStatement statement, List<Object> arguments)
            throws SQLException {
        for (int i = 0; i < arguments.size(); i++) {
            Object argument = arguments.get(i);
            if (argument instanceof UUID[] ids) {
                statement.setArray(i + 1, unitOfWork.createArrayOf("uuid", ids));
            } else {
                statement.setObject(i + 1, argument);
            }
        }
    }
}
