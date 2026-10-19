package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplRefundStore} over plain JDBC (`P12-TSK-021`, ADR-0033; {@code bnpl V012}): every instant the database's -
 * stamped by {@code bnpl V012}'s triggers after the act's locks, whatever is bound overwritten - every merchant read
 * tenant-scoped in the statement, every failure its operation and SQLSTATE only ({@link BnplStorageException}).
 */
public final class JdbcBnplRefundStore implements BnplRefundStore {

    /** The purchase under B5 with its financing and obligation. */
    private static final String LOCKED = "SELECT p.id, p.offer_id, p.checkout_session_id, p.party_id, p.merchant_id,"
            + " p.product, p.plan, p.currency, p.status, p.business_date, p.authorised_at, p.financed_at, p.order_id,"
            + " p.correlation_id, f.scale, f.gross_minor, f.fee_minor, f.net_minor, o.id AS obligation_id"
            + " FROM bnpl.purchase p JOIN bnpl.merchant_financing f ON f.purchase_id = p.id"
            + " JOIN bnpl.bnpl_obligation o ON o.purchase_id = p.id";

    private static final String REFUND_COLUMNS = "r.id, r.purchase_id, r.obligation_id, r.merchant_id, r.door,"
            + " r.requested_by, r.refund_key, r.currency, r.scale, r.amount_minor, r.refunded_before_minor,"
            + " r.fee_returned_minor, r.payable_debit_minor, r.cancelled_unbilled_minor, r.cancelled_billed_minor,"
            + " r.excess_minor, r.excess_to, r.interest_accrued_released_minor, r.interest_due_released_minor,"
            + " r.allocation_engine, r.agreement_id, r.agreement_version, r.schedule_version, r.reason,"
            + " r.journal_entry_id, r.value_date, r.created_at, r.correlation_id";

    @Override
    public Optional<LockedPurchase> lockAtMerchant(Connection unitOfWork, BnplPurchaseId id, UUID merchant) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchant, "merchant");
        // The tenant is in the locking statement (INV-MER-01): another merchant's purchase is never locked.
        return locked(unitOfWork, LOCKED + " WHERE p.id = ? AND p.merchant_id = ? AND f.merchant_id = p.merchant_id"
                + " FOR UPDATE OF p", id.value(), merchant);
    }

    @Override
    public Optional<LockedPurchase> lock(Connection unitOfWork, BnplPurchaseId id) {
        Objects.requireNonNull(id, "id");
        return locked(unitOfWork, LOCKED + " WHERE p.id = ? FOR UPDATE OF p", id.value());
    }

    /** The caller's locking statement over {@link #LOCKED}, its parameters bound in order - its SQL is the caller's own. */
    private static Optional<LockedPurchase> locked(Connection unitOfWork, String sql, Object... parameters) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                select.setObject(index + 1, parameters[index]);
            }
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
                int scale = row.getInt("scale");
                CorrelationId correlation = CorrelationId.of(row.getString("correlation_id"));
                Timestamp financed = row.getTimestamp("financed_at");
                BnplPurchase purchase = new BnplPurchase(BnplPurchaseId.of(row.getObject("id", UUID.class)),
                        BnplOfferId.of(row.getObject("offer_id", UUID.class)),
                        row.getObject("checkout_session_id", UUID.class), row.getObject("party_id", UUID.class),
                        row.getObject("merchant_id", UUID.class), BnplProduct.valueOf(row.getString("product")),
                        BnplPlan.valueOf(row.getString("plan")), currency,
                        BnplPurchaseStatus.valueOf(row.getString("status")), row.getDate("business_date").toLocalDate(),
                        row.getTimestamp("authorised_at").toInstant(),
                        Optional.ofNullable(financed).map(Timestamp::toInstant),
                        Optional.ofNullable(row.getObject("order_id", UUID.class)), correlation);
                return Optional.of(new LockedPurchase(purchase,
                        Money.ofPersisted(row.getLong("gross_minor"), currency, scale),
                        Money.ofPersisted(row.getLong("fee_minor"), currency, scale),
                        Money.ofPersisted(row.getLong("net_minor"), currency, scale),
                        BnplObligationId.of(row.getObject("obligation_id", UUID.class))));
            }
        } catch (SQLException failure) {
            throw failed("locking a BNPL purchase for a refund", failure);
        }
    }

    @Override
    public Optional<BnplRefund> findByKey(Connection unitOfWork, UUID merchant, String requestedBy, String refundKey) {
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(refundKey, "refundKey");
        List<BnplRefund> found = refunds(unitOfWork, "SELECT " + REFUND_COLUMNS + " FROM bnpl.bnpl_refund r"
                + " WHERE r.merchant_id = ? AND r.requested_by = ? AND r.refund_key = ?",
                List.of(merchant, requestedBy, refundKey), "reading a BNPL refund by its key");
        return found.stream().findFirst();
    }

    @Override
    public List<BnplRefund> ofPurchase(Connection unitOfWork, BnplPurchaseId purchase) {
        Objects.requireNonNull(purchase, "purchase");
        return refunds(unitOfWork, "SELECT " + REFUND_COLUMNS + " FROM bnpl.bnpl_refund r WHERE r.purchase_id = ?"
                + " ORDER BY r.created_at, r.id", List.of(purchase.value()), "reading a BNPL purchase's refunds");
    }

    @Override
    public List<BnplRefund> atMerchant(Connection unitOfWork, BnplPurchaseId purchase, UUID merchant) {
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(merchant, "merchant");
        // The tenant is in the statement (INV-MER-01).
        return refunds(unitOfWork, "SELECT " + REFUND_COLUMNS + " FROM bnpl.bnpl_refund r"
                + " WHERE r.purchase_id = ? AND r.merchant_id = ? ORDER BY r.created_at, r.id",
                List.of(purchase.value(), merchant), "reading a merchant's BNPL refunds");
    }

    @Override
    public Optional<BnplPurchaseId> purchaseOfRefundAtMerchant(Connection unitOfWork, UUID refund, UUID merchant) {
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(merchant, "merchant");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // The tenant is in the statement (INV-MER-01): another merchant's refund is the same empty answer.
                "SELECT purchase_id FROM bnpl.bnpl_refund WHERE id = ? AND merchant_id = ?")) {
            select.setObject(1, refund);
            select.setObject(2, merchant);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(BnplPurchaseId.of(row.getObject(1, UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a merchant's BNPL refund", failure);
        }
    }

    @Override
    public List<BnplRefund.Adjustment> adjustmentsOf(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, sequence, instalment_id, kind, currency, scale, principal_cancelled_minor,"
                        + " interest_released_minor, whole, late_fee_released_minor FROM bnpl.bnpl_refund_adjustment"
                        + " WHERE obligation_id = ? ORDER BY sequence DESC, id")) {
            select.setObject(1, obligation.value());
            List<BnplRefund.Adjustment> adjustments = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    adjustments.add(adjustment(rows));
                }
            }
            return adjustments;
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's refund adjustments", failure);
        }
    }

    @Override
    public void insertScheduleVersion(Connection unitOfWork, BnplPurchaseStore.StoredSchedule schedule, UUID refund) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(refund, "refund");
        Money principal = schedule.principal();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_schedule (id, obligation_id, schedule_version, engine, currency, scale,"
                        + " principal_minor, start_date, instalment_count, created_at, refund_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, schedule.id());
            insert.setObject(2, schedule.obligation().value());
            insert.setInt(3, schedule.version());
            insert.setString(4, schedule.engine());
            insert.setString(5, principal.currency().code());
            insert.setInt(6, principal.scale());
            insert.setLong(7, principal.minorUnits());
            insert.setDate(8, Date.valueOf(schedule.start()));
            insert.setInt(9, schedule.instalments().size());
            insert.setObject(10, refund);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a refund's BNPL schedule version", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_instalment (id, schedule_id, obligation_id, sequence, due_date, currency, scale,"
                        + " principal_minor, interest_minor, amount_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (BnplPurchaseStore.StoredInstalment instalment : schedule.instalments()) {
                insert.setObject(1, instalment.id());
                insert.setObject(2, schedule.id());
                insert.setObject(3, schedule.obligation().value());
                insert.setInt(4, instalment.sequence());
                insert.setDate(5, Date.valueOf(instalment.dueDate()));
                insert.setString(6, instalment.amount().currency().code());
                insert.setInt(7, instalment.amount().scale());
                insert.setLong(8, instalment.principal().minorUnits());
                insert.setLong(9, instalment.interest().minorUnits());
                insert.setLong(10, instalment.amount().minorUnits());
                insert.executeUpdate();
            }
        } catch (SQLException failure) {
            throw failed("inserting a refund's BNPL schedule version's instalments", failure);
        }
    }

    @Override
    public Instant insertAgreementVersion(Connection unitOfWork, BnplAgreement agreement, UUID refund,
            int scheduleVersion) {
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(refund, "refund");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // effective_from and created_at are the trigger's.
                "INSERT INTO bnpl.bnpl_agreement (agreement_id, version, obligation_id, offer_id, decision_id,"
                        + " terms_version_id, product, plan, schedule_engine, accrual_engine, allocation_engine,"
                        + " refund_allocation_engine, fee_schedule_version_id, agreement_template_id,"
                        + " agreement_template_version, canonical_format, canonical_terms, terms_sha256, document_sha256,"
                        + " effective_from, created_at, refund_id, schedule_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(),"
                        + " clock_timestamp(), ?, ?) RETURNING created_at")) {
            insert.setObject(1, agreement.id().value());
            insert.setInt(2, agreement.version());
            insert.setObject(3, agreement.obligation().value());
            insert.setObject(4, agreement.offer().value());
            insert.setObject(5, agreement.decision());
            insert.setObject(6, agreement.termsVersionId().value());
            insert.setString(7, agreement.product().name());
            insert.setString(8, agreement.plan().name());
            insert.setString(9, agreement.scheduleEngine());
            insert.setString(10, agreement.accrualEngine().orElse(null));
            insert.setString(11, agreement.allocationEngine());
            insert.setString(12, agreement.refundAllocationEngine());
            insert.setObject(13, agreement.feeScheduleVersion());
            insert.setString(14, agreement.template().id());
            insert.setInt(15, agreement.template().version());
            insert.setInt(16, agreement.canonicalFormat());
            insert.setString(17, agreement.canonicalTerms());
            insert.setBytes(18, agreement.termsSha256());
            insert.setBytes(19, agreement.documentSha256());
            insert.setObject(20, refund);
            insert.setInt(21, scheduleVersion);
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp("created_at").toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a refund's BNPL agreement version", failure);
        }
    }

    @Override
    public Instant insertRefund(Connection unitOfWork, BnplRefund refund) {
        Objects.requireNonNull(refund, "refund");
        Instant created;
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // created_at is the trigger's.
                "INSERT INTO bnpl.bnpl_refund (id, purchase_id, obligation_id, merchant_id, door, requested_by,"
                        + " refund_key, currency, scale, amount_minor, refunded_before_minor, fee_returned_minor,"
                        + " payable_debit_minor, cancelled_unbilled_minor, cancelled_billed_minor, excess_minor, excess_to,"
                        + " interest_accrued_released_minor, interest_due_released_minor, allocation_engine, agreement_id,"
                        + " agreement_version, schedule_version, reason, journal_entry_id, value_date, created_at,"
                        + " correlation_id, late_fee_released_minor)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " clock_timestamp(), ?, ?) RETURNING created_at")) {
            Money amount = refund.amount();
            insert.setObject(1, refund.id());
            insert.setObject(2, refund.purchase().value());
            insert.setObject(3, refund.obligation().value());
            insert.setObject(4, refund.merchant());
            insert.setString(5, refund.door().name());
            insert.setString(6, refund.requestedBy());
            insert.setString(7, refund.refundKey());
            insert.setString(8, amount.currency().code());
            insert.setInt(9, amount.scale());
            insert.setLong(10, amount.minorUnits());
            insert.setLong(11, refund.refundedBefore().minorUnits());
            insert.setLong(12, refund.feeReturned().minorUnits());
            insert.setLong(13, refund.payableDebit().minorUnits());
            insert.setLong(14, refund.cancelledUnbilled().minorUnits());
            insert.setLong(15, refund.cancelledBilled().minorUnits());
            insert.setLong(16, refund.excess().minorUnits());
            insert.setString(17, refund.excessTo().name());
            insert.setLong(18, refund.interestAccruedReleased().minorUnits());
            insert.setLong(19, refund.interestDueReleased().minorUnits());
            insert.setString(20, refund.allocationEngine());
            insert.setObject(21, refund.agreement().value());
            insert.setInt(22, refund.agreementVersion());
            insert.setInt(23, refund.scheduleVersion());
            insert.setString(24, refund.reason());
            insert.setObject(25, refund.entry().value());
            insert.setDate(26, Date.valueOf(refund.valueDate()));
            insert.setString(27, refund.correlation().value());
            insert.setLong(28, refund.lateFeeReleased().minorUnits());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                created = row.getTimestamp("created_at").toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL refund", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_refund_adjustment (id, refund_id, obligation_id, sequence, instalment_id, kind,"
                        + " currency, scale, principal_cancelled_minor, interest_released_minor, whole,"
                        + " late_fee_released_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (BnplRefund.Adjustment adjustment : refund.adjustments()) {
                insert.setObject(1, adjustment.id());
                insert.setObject(2, refund.id());
                insert.setObject(3, refund.obligation().value());
                insert.setInt(4, adjustment.sequence());
                insert.setObject(5, adjustment.instalment());
                insert.setString(6, adjustment.kind().name());
                insert.setString(7, refund.amount().currency().code());
                insert.setInt(8, refund.amount().scale());
                insert.setLong(9, adjustment.principalCancelled().minorUnits());
                insert.setLong(10, adjustment.interestReleased().minorUnits());
                insert.setBoolean(11, adjustment.whole());
                insert.setLong(12, adjustment.lateFeeReleased().minorUnits());
                insert.executeUpdate();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL refund's adjustments", failure);
        }
        return created;
    }

    @Override
    public boolean fullyRefunded(Connection unitOfWork, BnplPurchaseId purchase) {
        Objects.requireNonNull(purchase, "purchase");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.purchase SET status = 'FULLY_REFUNDED' WHERE id = ? AND status = 'FINANCED'")) {
            update.setObject(1, purchase.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("moving a BNPL purchase FULLY_REFUNDED", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static List<BnplRefund> refunds(Connection unitOfWork, String sql, List<Object> parameters,
            String operation) {
        Map<UUID, RefundRow> rows = new LinkedHashMap<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int index = 0; index < parameters.size(); index++) {
                select.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.put(row.getObject("id", UUID.class), refundRow(row));
                }
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<BnplRefund.Adjustment>> adjustments = new LinkedHashMap<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT refund_id, id, sequence, instalment_id, kind, currency, scale, principal_cancelled_minor,"
                        + " interest_released_minor, whole, late_fee_released_minor FROM bnpl.bnpl_refund_adjustment"
                        + " WHERE refund_id = ANY (?) ORDER BY sequence DESC")) {
            select.setArray(1, unitOfWork.createArrayOf("uuid", rows.keySet().toArray()));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    adjustments.computeIfAbsent(row.getObject("refund_id", UUID.class), any -> new ArrayList<>())
                            .add(adjustment(row));
                }
            }
        } catch (SQLException failure) {
            throw failed(operation + "' adjustments", failure);
        }
        List<BnplRefund> refunds = new ArrayList<>();
        for (Map.Entry<UUID, RefundRow> row : rows.entrySet()) {
            refunds.add(row.getValue().with(adjustments.getOrDefault(row.getKey(), List.of())));
        }
        return refunds;
    }

    /** A refund's row before its adjustments are joined. */
    private record RefundRow(UUID id, BnplPurchaseId purchase, BnplObligationId obligation, UUID merchant,
            BnplRefund.Door door, String requestedBy, String refundKey, Money amount, Money refundedBefore,
            Money feeReturned, Money payableDebit, Money cancelledUnbilled, Money cancelledBilled, Money excess,
            BnplRefund.ExcessTo excessTo, Money interestAccruedReleased, Money interestDueReleased,
            String allocationEngine, BnplAgreementId agreement, int agreementVersion, int scheduleVersion, String reason,
            JournalEntryId entry, java.time.LocalDate valueDate, Instant createdAt, CorrelationId correlation) {

        BnplRefund with(List<BnplRefund.Adjustment> adjustments) {
            return new BnplRefund(id, purchase, obligation, merchant, door, requestedBy, refundKey, amount, refundedBefore,
                    feeReturned, payableDebit, cancelledUnbilled, cancelledBilled, excess, excessTo,
                    interestAccruedReleased, interestDueReleased, allocationEngine, agreement, agreementVersion,
                    scheduleVersion, reason, entry, valueDate, Optional.of(createdAt), correlation, adjustments);
        }
    }

    private static RefundRow refundRow(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        int scale = row.getInt("scale");
        return new RefundRow(row.getObject("id", UUID.class), BnplPurchaseId.of(row.getObject("purchase_id", UUID.class)),
                BnplObligationId.of(row.getObject("obligation_id", UUID.class)), row.getObject("merchant_id", UUID.class),
                BnplRefund.Door.valueOf(row.getString("door")), row.getString("requested_by"),
                row.getString("refund_key"), money(row, "amount_minor", currency, scale),
                money(row, "refunded_before_minor", currency, scale), money(row, "fee_returned_minor", currency, scale),
                money(row, "payable_debit_minor", currency, scale), money(row, "cancelled_unbilled_minor", currency, scale),
                money(row, "cancelled_billed_minor", currency, scale), money(row, "excess_minor", currency, scale),
                BnplRefund.ExcessTo.valueOf(row.getString("excess_to")),
                money(row, "interest_accrued_released_minor", currency, scale),
                money(row, "interest_due_released_minor", currency, scale), row.getString("allocation_engine"),
                BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getInt("agreement_version"),
                row.getInt("schedule_version"), row.getString("reason"),
                JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                row.getDate("value_date").toLocalDate(), row.getTimestamp("created_at").toInstant(),
                CorrelationId.of(row.getString("correlation_id")));
    }

    private static BnplRefund.Adjustment adjustment(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        int scale = row.getInt("scale");
        return new BnplRefund.Adjustment(row.getObject("id", UUID.class), row.getInt("sequence"),
                row.getObject("instalment_id", UUID.class),
                RefundAllocation.Cancellation.Kind.valueOf(row.getString("kind")),
                money(row, "principal_cancelled_minor", currency, scale),
                money(row, "interest_released_minor", currency, scale), row.getBoolean("whole"),
                money(row, "late_fee_released_minor", currency, scale));
    }

    private static Money money(ResultSet row, String column, CurrencyCode currency, int scale) throws SQLException {
        return Money.ofPersisted(row.getLong(column), currency, scale);
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
