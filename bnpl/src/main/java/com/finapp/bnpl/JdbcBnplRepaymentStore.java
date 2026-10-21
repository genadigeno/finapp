package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplRepaymentStore} over plain JDBC (`P12-TSK-018`, ADR-0033; lending's {@code JdbcRepaymentStore}): every instant
 * the database's - {@code bnpl V010}'s triggers stamp them after the act's locks - every customer read owner-scoped in the
 * statement or of an obligation the caller holds under B4, every failure its operation and SQLSTATE only
 * ({@link BnplStorageException}) - never an amount, a key or a party.
 */
public final class JdbcBnplRepaymentStore implements BnplRepaymentStore {

    /** A repayment's columns and its allocation's, one row per allocation line. */
    static final String REPAYMENTS = "SELECT r.id, r.obligation_id, r.kind, r.request_key, r.currency, r.scale,"
            + " r.amount_minor, r.agreement_id, r.agreement_version, r.allocation_engine, r.journal_entry_id, r.value_date,"
            + " r.received_at, l.id AS line_id, l.sequence, l.target, l.instalment_id, l.component,"
            + " l.amount_minor AS line_minor"
            + " FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_repayment_allocation l ON l.repayment_id = r.id";

    @Override
    public Optional<BnplServicingStore.Locked> lockOwnedBy(Connection unitOfWork, BnplAgreementId agreement, UUID party) {
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(party, "party");
        // The lock in its own statement, then the agreement in force read by a statement begun after it: READ COMMITTED's
        // re-check of a row it waited for refreshes only the locked row, never a joined one, so one statement would read
        // the version in force before a racing refund's commit (P12-TST-001's finding).
        try (PreparedStatement lock = unitOfWork.prepareStatement(
                // The owner is in the statement: another party's agreement is never locked (INV-AUTHZ, the uniform 404).
                "SELECT o.id FROM bnpl.bnpl_obligation o WHERE o.agreement_id = ? AND o.party_id = ? FOR UPDATE");
                PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT o.id, o.purchase_id, o.agreement_id, o.party_id, o.decision_id, o.product, o.plan, o.currency,"
                        + " o.status, o.servicing_zone, o.purchase_date, o.opened_at, o.closure_reason, o.correlation_id,"
                        + " a.version, a.terms_version_id, a.schedule_engine, a.accrual_engine, a.allocation_engine"
                        + " FROM bnpl.bnpl_obligation o"
                        + " JOIN LATERAL (SELECT * FROM bnpl.bnpl_agreement x WHERE x.obligation_id = o.id"
                        + " ORDER BY x.version DESC LIMIT 1) a ON true"
                        + " WHERE o.agreement_id = ? AND o.party_id = ?")) {
            lock.setObject(1, agreement.value());
            lock.setObject(2, party);
            try (ResultSet held = lock.executeQuery()) {
                if (!held.next()) {
                    return Optional.empty();
                }
            }
            select.setObject(1, agreement.value());
            select.setObject(2, party);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String closure = row.getString("closure_reason");
                BnplObligation locked = new BnplObligation(BnplObligationId.of(row.getObject("id", UUID.class)),
                        BnplPurchaseId.of(row.getObject("purchase_id", UUID.class)),
                        BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getObject("party_id", UUID.class),
                        row.getObject("decision_id", UUID.class), BnplProduct.valueOf(row.getString("product")),
                        BnplPlan.valueOf(row.getString("plan")), CurrencyCode.of(row.getString("currency").strip()),
                        BnplObligationStatus.valueOf(row.getString("status")),
                        row.getDate("purchase_date").toLocalDate(), row.getTimestamp("opened_at").toInstant(),
                        Optional.ofNullable(closure).map(BnplClosureReason::valueOf),
                        CorrelationId.of(row.getString("correlation_id")));
                return Optional.of(new BnplServicingStore.Locked(locked, locked.agreement(), row.getInt("version"),
                        BnplTermsVersionId.of(row.getObject("terms_version_id", UUID.class)),
                        row.getString("schedule_engine"), Optional.ofNullable(row.getString("accrual_engine")),
                        row.getString("allocation_engine"), row.getString("servicing_zone")));
            }
        } catch (SQLException failure) {
            throw failed("locking a customer's BNPL obligation", failure);
        }
    }

    @Override
    public Optional<BnplPurchaseStore.Repayment> findByKey(Connection unitOfWork, BnplObligationId obligation, String kind,
            String requestKey) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestKey, "requestKey");
        try (PreparedStatement select = unitOfWork.prepareStatement(REPAYMENTS
                + " WHERE r.obligation_id = ? AND r.kind = ? AND r.request_key = ? ORDER BY l.sequence")) {
            select.setObject(1, obligation.value());
            select.setString(2, kind);
            select.setString(3, requestKey);
            List<BnplPurchaseStore.Repayment> found = read(select);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        } catch (SQLException failure) {
            throw failed("reading a BNPL repayment by its key", failure);
        }
    }

    @Override
    public List<BnplPurchaseStore.Repayment> ofObligation(Connection unitOfWork, BnplObligationId obligation, int limit) {
        Objects.requireNonNull(obligation, "obligation");
        if (limit < 1) {
            throw new IllegalArgumentException("a history reads at least one repayment");
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(REPAYMENTS
                + " WHERE r.id IN (SELECT x.id FROM bnpl.bnpl_repayment x WHERE x.obligation_id = ?"
                + " ORDER BY x.received_at DESC, x.id DESC LIMIT ?)"
                + " ORDER BY r.received_at DESC, r.id DESC, l.sequence")) {
            select.setObject(1, obligation.value());
            select.setInt(2, limit);
            return read(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's repayments", failure);
        }
    }

    @Override
    public Optional<BnplClosure> closureOf(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, reason, currency, scale, refund_minor, refund_journal_entry_id, repayment_id, refund_id,"
                        + " correlation_id FROM bnpl.bnpl_obligation_closure WHERE obligation_id = ?")) {
            select.setObject(1, obligation.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID entry = row.getObject("refund_journal_entry_id", UUID.class);
                UUID repayment = row.getObject("repayment_id", UUID.class);
                UUID closingRefund = row.getObject("refund_id", UUID.class);
                return Optional.of(new BnplClosure(row.getObject("id", UUID.class), obligation,
                        BnplClosureReason.valueOf(row.getString("reason")),
                        Money.ofPersisted(row.getLong("refund_minor"), CurrencyCode.of(row.getString("currency").strip()),
                                row.getInt("scale")),
                        Optional.ofNullable(entry).map(JournalEntryId::of), Optional.ofNullable(repayment),
                        Optional.ofNullable(closingRefund), CorrelationId.of(row.getString("correlation_id"))));
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's closure", failure);
        }
    }

    @Override
    public void insertClosure(Connection unitOfWork, BnplClosure closure) {
        Objects.requireNonNull(closure, "closure");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // created_at is the trigger's.
                "INSERT INTO bnpl.bnpl_obligation_closure (id, obligation_id, reason, currency, scale, refund_minor,"
                        + " refund_journal_entry_id, repayment_id, created_at, correlation_id, refund_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?)")) {
            insert.setObject(1, closure.id());
            insert.setObject(2, closure.obligation().value());
            insert.setString(3, closure.reason().name());
            insert.setString(4, closure.refund().currency().code());
            insert.setInt(5, closure.refund().scale());
            insert.setLong(6, closure.refund().minorUnits());
            if (closure.refundEntry().isPresent()) {
                insert.setObject(7, closure.refundEntry().get().value());
            } else {
                insert.setNull(7, Types.OTHER);
            }
            if (closure.repayment().isPresent()) {
                insert.setObject(8, closure.repayment().get());
            } else {
                insert.setNull(8, Types.OTHER);
            }
            insert.setString(9, closure.correlation().value());
            // The BNPL refund whose transaction closed it (P12-TSK-021), if one did.
            if (closure.closingRefund().isPresent()) {
                insert.setObject(10, closure.closingRefund().get());
            } else {
                insert.setNull(10, Types.OTHER);
            }
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL obligation's closure", failure);
        }
    }

    @Override
    public boolean close(Connection unitOfWork, BnplObligationId obligation, BnplClosureReason reason) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(reason, "reason");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                // closed_at is the edge function's, after the lock.
                "UPDATE bnpl.bnpl_obligation SET status = 'CLOSED', closure_reason = ? WHERE id = ? AND status = 'ACTIVE'")) {
            update.setString(1, reason.name());
            update.setObject(2, obligation.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("closing a BNPL obligation", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    static List<BnplPurchaseStore.Repayment> read(PreparedStatement select) throws SQLException {
        Map<UUID, RowsOf> byId = new LinkedHashMap<>();
        try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                UUID id = rows.getObject("id", UUID.class);
                CurrencyCode currency = CurrencyCode.of(rows.getString("currency").strip());
                int scale = rows.getInt("scale");
                RowsOf repayment = byId.get(id);
                if (repayment == null) {
                    repayment = new RowsOf(id, BnplObligationId.of(rows.getObject("obligation_id", UUID.class)),
                            rows.getString("kind"), rows.getString("request_key"),
                            Money.ofPersisted(rows.getLong("amount_minor"), currency, scale),
                            BnplAgreementId.of(rows.getObject("agreement_id", UUID.class)),
                            rows.getInt("agreement_version"), rows.getString("allocation_engine"),
                            JournalEntryId.of(rows.getObject("journal_entry_id", UUID.class)),
                            rows.getDate("value_date").toLocalDate(), new ArrayList<>());
                    byId.put(id, repayment);
                }
                repayment.lines().add(new BnplPurchaseStore.Allocation(rows.getObject("line_id", UUID.class),
                        rows.getInt("sequence"), rows.getString("target"),
                        Optional.ofNullable(rows.getObject("instalment_id", UUID.class)),
                        Optional.ofNullable(rows.getString("component")),
                        Money.ofPersisted(rows.getLong("line_minor"), currency, scale)));
            }
        }
        List<BnplPurchaseStore.Repayment> repayments = new ArrayList<>();
        for (RowsOf rows : byId.values()) {
            repayments.add(new BnplPurchaseStore.Repayment(rows.id(), rows.obligation(), rows.kind(), rows.requestKey(),
                    rows.amount(), rows.agreement(), rows.agreementVersion(), rows.allocationEngine(), rows.entry(),
                    rows.valueDate(), rows.lines()));
        }
        return repayments;
    }

    private record RowsOf(UUID id, BnplObligationId obligation, String kind, String requestKey, Money amount,
            BnplAgreementId agreement, int agreementVersion, String allocationEngine, JournalEntryId entry,
            java.time.LocalDate valueDate, List<BnplPurchaseStore.Allocation> lines) {

        /** Never the key or the amount (`INV-AUD-02`). */
        @Override
        public String toString() {
            return "RowsOf[" + id + "]";
        }
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
