package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link BnplCorrectionStore} over plain JDBC (`P12-TSK-024`, ADR-0033; lending's {@code JdbcRepaymentReversalStore} and
 * {@code JdbcWaiverStore}): every instant the database's - {@code bnpl V016}'s triggers stamp them after the act's locks,
 * {@code clock_timestamp()} - every row below an agreement matched to its obligation in the statement, every failure its
 * operation and SQLSTATE only ({@link BnplStorageException}) - never an amount, a reason or a party.
 */
public final class JdbcBnplCorrectionStore implements BnplCorrectionStore {

    private static final String REVERSALS = "SELECT id, obligation_id, repayment_id, agreement_id, agreement_version,"
            + " status, proposed_by, proposed_at, proposal_reason, decided_by, decided_at, decision_reason,"
            + " journal_entry_id, value_date FROM bnpl.bnpl_repayment_reversal";

    private static final String WAIVERS = "SELECT w.id, w.obligation_id, w.kind, w.fee_assessment_id, w.instalment_id,"
            + " w.currency, w.scale, w.amount_minor, w.agreement_id, w.agreement_version, w.proposed_by, w.proposed_at,"
            + " w.proposal_reason, d.outcome, d.decided_by, d.decided_at, d.decision_reason, d.journal_entry_id"
            + " FROM bnpl.bnpl_waiver w LEFT JOIN bnpl.bnpl_waiver_decision d ON d.waiver_id = w.id";

    // ------------------------------------------------------------------ the operator's addressing

    @Override
    public Optional<Addressed> addressed(Connection unitOfWork, BnplAgreementId agreement) {
        Objects.requireNonNull(agreement, "agreement");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // An operator acts on any customer's agreement (BNPL_SERVICE): the agreement is the path's; its party is a
                // frozen column, read unlocked so L0 comes first and self-dealing is refused before any lock.
                "SELECT o.id, o.party_id FROM bnpl.bnpl_obligation o WHERE o.agreement_id = ?")) {
            select.setObject(1, agreement.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(new Addressed(BnplObligationId.of(row.getObject("id", UUID.class)),
                        row.getObject("party_id", UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("addressing a BNPL agreement", failure);
        }
    }

    @Override
    public Optional<BnplPurchaseStore.Repayment> repayment(Connection unitOfWork, BnplObligationId obligation,
            UUID repayment) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(repayment, "repayment");
        try (PreparedStatement select = unitOfWork.prepareStatement(JdbcBnplRepaymentStore.REPAYMENTS
                + " WHERE r.obligation_id = ? AND r.id = ? ORDER BY l.sequence")) {
            select.setObject(1, obligation.value());
            select.setObject(2, repayment);
            List<BnplPurchaseStore.Repayment> found = JdbcBnplRepaymentStore.read(select);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        } catch (SQLException failure) {
            throw failed("reading a BNPL repayment of an obligation", failure);
        }
    }

    // ------------------------------------------------------------------ the repayment reversal

    @Override
    public Optional<BnplRepaymentReversal> liveReversal(Connection unitOfWork, UUID repayment) {
        Objects.requireNonNull(repayment, "repayment");
        try (PreparedStatement select = unitOfWork.prepareStatement(REVERSALS
                + " WHERE repayment_id = ? AND status IN ('PROPOSED', 'APPROVED')")) {
            select.setObject(1, repayment);
            return one(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL repayment's live reversal", failure);
        }
    }

    @Override
    public Optional<Instant> insertReversal(Connection unitOfWork, NewReversal reversal) {
        Objects.requireNonNull(reversal, "reversal");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // The partial unique arbitrates two live proposals for one repayment: the loser inserts nothing.
                "INSERT INTO bnpl.bnpl_repayment_reversal (id, obligation_id, repayment_id, agreement_id,"
                        + " agreement_version, status, proposed_by, proposed_by_type, proposed_at, proposal_reason,"
                        + " correlation_id) VALUES (?, ?, ?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)"
                        + " ON CONFLICT (repayment_id) WHERE status IN ('PROPOSED', 'APPROVED') DO NOTHING"
                        + " RETURNING proposed_at")) {
            int i = 0;
            insert.setObject(++i, reversal.id());
            insert.setObject(++i, reversal.obligation().value());
            insert.setObject(++i, reversal.repayment());
            insert.setObject(++i, reversal.agreement().value());
            insert.setInt(++i, reversal.agreementVersion());
            insert.setString(++i, reversal.proposer().id());
            insert.setString(++i, reversal.proposer().type().name());
            insert.setString(++i, reversal.reason());
            insert.setString(++i, reversal.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("proposing a BNPL repayment reversal", failure);
        }
    }

    @Override
    public Optional<BnplRepaymentReversal> lockReversal(Connection unitOfWork, UUID reversal) {
        Objects.requireNonNull(reversal, "reversal");
        try (PreparedStatement select = unitOfWork.prepareStatement(REVERSALS + " WHERE id = ? FOR UPDATE")) {
            select.setObject(1, reversal);
            return one(select);
        } catch (SQLException failure) {
            throw failed("locking a BNPL repayment reversal", failure);
        }
    }

    @Override
    public Optional<BnplRepaymentReversal> reversal(Connection unitOfWork, UUID reversal) {
        Objects.requireNonNull(reversal, "reversal");
        try (PreparedStatement select = unitOfWork.prepareStatement(REVERSALS + " WHERE id = ?")) {
            select.setObject(1, reversal);
            return one(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL repayment reversal", failure);
        }
    }

    @Override
    public boolean decideReversal(Connection unitOfWork, ReversalDecision decision) {
        Objects.requireNonNull(decision, "decision");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                // Conditional on PROPOSED; the decision instant is the trigger's (clock_timestamp(), never granted).
                "UPDATE bnpl.bnpl_repayment_reversal SET status = ?, decided_by = ?, decided_by_type = ?,"
                        + " decision_reason = ?, journal_entry_id = ?, value_date = ?"
                        + " WHERE id = ? AND status = 'PROPOSED'")) {
            int i = 0;
            update.setString(++i, decision.outcome().name());
            update.setString(++i, decision.decider().id());
            update.setString(++i, decision.decider().type().name());
            update.setString(++i, decision.reason());
            update.setObject(++i, decision.entry().map(JournalEntryId::value).orElse(null));
            update.setObject(++i, decision.valueDate().orElse(null));
            update.setObject(++i, decision.reversal());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("deciding a BNPL repayment reversal", failure);
        }
    }

    @Override
    public void insertNegations(Connection unitOfWork, UUID reversal, List<BnplPurchaseStore.Allocation> allocations,
            Supplier<UUID> ids) {
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(allocations, "allocations");
        Objects.requireNonNull(ids, "ids");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_repayment_allocation_reversal (id, reversal_id, allocation_id, currency, scale,"
                        + " amount_minor) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (BnplPurchaseStore.Allocation allocation : allocations) {
                int i = 0;
                insert.setObject(++i, ids.get());
                insert.setObject(++i, reversal);
                insert.setObject(++i, allocation.id());
                insert.setString(++i, allocation.amount().currency().code());
                insert.setInt(++i, allocation.amount().scale());
                insert.setLong(++i, allocation.amount().minorUnits());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failed("negating a reversed BNPL repayment's allocation", failure);
        }
    }

    @Override
    public Set<UUID> wholeCancelledInstalments(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT DISTINCT instalment_id FROM bnpl.bnpl_refund_adjustment"
                        + " WHERE obligation_id = ? AND kind = 'BILLED_UNPAID' AND whole")) {
            select.setObject(1, obligation.value());
            Set<UUID> instalments = new HashSet<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    instalments.add(row.getObject(1, UUID.class));
                }
            }
            return Set.copyOf(instalments);
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's instalments a refund cancelled whole", failure);
        }
    }

    @Override
    public void insertFeeReleases(Connection unitOfWork, UUID reversal, BnplObligationId obligation,
            JournalEntryId entry, Map<UUID, Money> released, Supplier<UUID> ids) {
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(released, "released");
        Objects.requireNonNull(ids, "ids");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_reversal_fee_release (id, reversal_id, obligation_id, instalment_id, currency,"
                        + " scale, amount_minor, journal_entry_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Map.Entry<UUID, Money> release : released.entrySet()) {
                int i = 0;
                insert.setObject(++i, ids.get());
                insert.setObject(++i, reversal);
                insert.setObject(++i, obligation.value());
                insert.setObject(++i, release.getKey());
                insert.setString(++i, release.getValue().currency().code());
                insert.setInt(++i, release.getValue().scale());
                insert.setLong(++i, release.getValue().minorUnits());
                insert.setObject(++i, entry.value());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failed("releasing a late fee a BNPL reversal re-opened", failure);
        }
    }

    @Override
    public List<BnplRepaymentReversal> reversalsOf(Connection unitOfWork, BnplObligationId obligation, int limit) {
        Objects.requireNonNull(obligation, "obligation");
        if (limit < 1) {
            throw new IllegalArgumentException("a history reads at least one reversal");
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(REVERSALS
                + " WHERE obligation_id = ? ORDER BY proposed_at DESC, id DESC LIMIT ?")) {
            select.setObject(1, obligation.value());
            select.setInt(2, limit);
            return many(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's reversals", failure);
        }
    }

    @Override
    public Set<UUID> reversedAmong(Connection unitOfWork, Collection<UUID> repayments) {
        Objects.requireNonNull(repayments, "repayments");
        if (repayments.isEmpty()) {
            return Set.of();
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT repayment_id FROM bnpl.bnpl_repayment_reversal WHERE status = 'APPROVED'"
                        + " AND repayment_id = ANY (?)")) {
            Array ids = unitOfWork.createArrayOf("uuid", repayments.toArray());
            select.setArray(1, ids);
            Set<UUID> reversed = new HashSet<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    reversed.add(rows.getObject(1, UUID.class));
                }
            }
            return Set.copyOf(reversed);
        } catch (SQLException failure) {
            throw failed("reading which BNPL repayments are reversed", failure);
        }
    }

    // ------------------------------------------------------------------ the fee waiver

    @Override
    public Optional<BnplFeeAssessment> assessment(Connection unitOfWork, BnplObligationId obligation, UUID assessment) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(assessment, "assessment");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, instalment_id, kind, business_date, currency, scale, amount_minor, agreement_id,"
                        + " agreement_version, journal_entry_id, correlation_id FROM bnpl.bnpl_fee_assessment"
                        + " WHERE obligation_id = ? AND id = ?")) {
            select.setObject(1, obligation.value());
            select.setObject(2, assessment);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BnplFeeAssessment(row.getObject("id", UUID.class), obligation,
                        row.getObject("instalment_id", UUID.class), BnplFeeKind.valueOf(row.getString("kind")),
                        row.getObject("business_date", LocalDate.class),
                        Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("currency").strip()),
                                row.getInt("scale")),
                        BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getInt("agreement_version"),
                        JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                        CorrelationId.of(row.getString("correlation_id"))));
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL fee assessment of an obligation", failure);
        }
    }

    @Override
    public List<BnplWaiver> waiversOf(Connection unitOfWork, UUID assessment) {
        Objects.requireNonNull(assessment, "assessment");
        try (PreparedStatement select = unitOfWork.prepareStatement(WAIVERS
                + " WHERE w.fee_assessment_id = ? ORDER BY w.proposed_at, w.id")) {
            select.setObject(1, assessment);
            return waivers(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL fee's waivers", failure);
        }
    }

    @Override
    public Money releasedOn(Connection unitOfWork, UUID instalment, Money zero) {
        Objects.requireNonNull(instalment, "instalment");
        Objects.requireNonNull(zero, "zero");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // The refunds' releases (OQ-14) and the reversals' (the Phase 12 transition gate's A7-3, bnpl V020) - the
                // never-forgiven arm of bnpl.fee_waiver_room, the same two sums.
                "SELECT (SELECT coalesce(sum(late_fee_released_minor), 0) FROM bnpl.bnpl_refund_adjustment"
                        + " WHERE instalment_id = ? AND kind = 'BILLED_UNPAID')"
                        + " + (SELECT coalesce(sum(amount_minor), 0) FROM bnpl.bnpl_reversal_fee_release"
                        + " WHERE instalment_id = ?)")) {
            select.setObject(1, instalment);
            select.setObject(2, instalment);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return Money.ofPersisted(row.getLong(1), zero.currency(), zero.scale());
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL instalment's released late fees", failure);
        }
    }

    @Override
    public Instant insertWaiver(Connection unitOfWork, NewWaiver waiver) {
        Objects.requireNonNull(waiver, "waiver");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_waiver (id, obligation_id, kind, fee_assessment_id, instalment_id, currency, scale,"
                        + " amount_minor, agreement_id, agreement_version, proposed_by, proposed_by_type, proposed_at,"
                        + " proposal_reason, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?) RETURNING proposed_at")) {
            int i = 0;
            insert.setObject(++i, waiver.id());
            insert.setObject(++i, waiver.obligation().value());
            insert.setString(++i, waiver.kind().name());
            insert.setObject(++i, waiver.assessment());
            insert.setObject(++i, waiver.instalment());
            insert.setString(++i, waiver.amount().currency().code());
            insert.setInt(++i, waiver.amount().scale());
            insert.setLong(++i, waiver.amount().minorUnits());
            insert.setObject(++i, waiver.agreement().value());
            insert.setInt(++i, waiver.agreementVersion());
            insert.setString(++i, waiver.proposer().id());
            insert.setString(++i, waiver.proposer().type().name());
            insert.setString(++i, waiver.reason());
            insert.setString(++i, waiver.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("proposing a BNPL fee waiver", failure);
        }
    }

    @Override
    public Optional<BnplWaiver> waiver(Connection unitOfWork, BnplObligationId obligation, UUID waiver) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(waiver, "waiver");
        try (PreparedStatement select = unitOfWork.prepareStatement(WAIVERS
                + " WHERE w.obligation_id = ? AND w.id = ?")) {
            select.setObject(1, obligation.value());
            select.setObject(2, waiver);
            List<BnplWaiver> found = waivers(select);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        } catch (SQLException failure) {
            throw failed("reading a BNPL waiver of an obligation", failure);
        }
    }

    @Override
    public boolean decideWaiver(Connection unitOfWork, WaiverDecision decision) {
        Objects.requireNonNull(decision, "decision");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // Decided once: UNIQUE (waiver_id) beneath B4 - a second decision inserts nothing.
                "INSERT INTO bnpl.bnpl_waiver_decision (id, waiver_id, proposed_by, outcome, decided_by, decided_by_type,"
                        + " decided_at, decision_reason, journal_entry_id, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?, ?) ON CONFLICT (waiver_id) DO NOTHING")) {
            int i = 0;
            insert.setObject(++i, decision.id());
            insert.setObject(++i, decision.waiver());
            insert.setString(++i, decision.proposedBy());
            insert.setString(++i, decision.outcome().name());
            insert.setString(++i, decision.decider().id());
            insert.setString(++i, decision.decider().type().name());
            insert.setString(++i, decision.reason());
            insert.setObject(++i, decision.entry().map(JournalEntryId::value).orElse(null));
            insert.setString(++i, decision.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("deciding a BNPL fee waiver", failure);
        }
    }

    @Override
    public List<BnplWaiver> waiversOfObligation(Connection unitOfWork, BnplObligationId obligation, int limit) {
        Objects.requireNonNull(obligation, "obligation");
        if (limit < 1) {
            throw new IllegalArgumentException("a history reads at least one waiver");
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(WAIVERS
                + " WHERE w.obligation_id = ? ORDER BY w.proposed_at DESC, w.id DESC LIMIT ?")) {
            select.setObject(1, obligation.value());
            select.setInt(2, limit);
            return waivers(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's waivers", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<BnplRepaymentReversal> one(PreparedStatement select) throws SQLException {
        List<BnplRepaymentReversal> found = many(select);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static List<BnplRepaymentReversal> many(PreparedStatement select) throws SQLException {
        List<BnplRepaymentReversal> reversals = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                reversals.add(new BnplRepaymentReversal(row.getObject("id", UUID.class),
                        BnplObligationId.of(row.getObject("obligation_id", UUID.class)),
                        row.getObject("repayment_id", UUID.class),
                        BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getInt("agreement_version"),
                        BnplCorrectionStatus.valueOf(row.getString("status")), row.getString("proposed_by"),
                        row.getTimestamp("proposed_at").toInstant(), row.getString("proposal_reason"),
                        Optional.ofNullable(row.getString("decided_by")), instant(row, "decided_at"),
                        Optional.ofNullable(row.getString("decision_reason")),
                        Optional.ofNullable(row.getObject("journal_entry_id", UUID.class)).map(JournalEntryId::of),
                        Optional.ofNullable(row.getObject("value_date", LocalDate.class))));
            }
        }
        return reversals;
    }

    private static List<BnplWaiver> waivers(PreparedStatement select) throws SQLException {
        List<BnplWaiver> waivers = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                String outcome = row.getString("outcome");
                waivers.add(new BnplWaiver(row.getObject("id", UUID.class),
                        BnplObligationId.of(row.getObject("obligation_id", UUID.class)),
                        BnplWaiverKind.valueOf(row.getString("kind")), row.getObject("fee_assessment_id", UUID.class),
                        row.getObject("instalment_id", UUID.class),
                        Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("currency").strip()),
                                row.getInt("scale")),
                        BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getInt("agreement_version"),
                        outcome == null ? BnplCorrectionStatus.PROPOSED : BnplCorrectionStatus.valueOf(outcome),
                        row.getString("proposed_by"), row.getTimestamp("proposed_at").toInstant(),
                        row.getString("proposal_reason"), Optional.ofNullable(row.getString("decided_by")),
                        instant(row, "decided_at"), Optional.ofNullable(row.getString("decision_reason")),
                        Optional.ofNullable(row.getObject("journal_entry_id", UUID.class)).map(JournalEntryId::of)));
            }
        }
        return waivers;
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getTimestamp(column)).map(Timestamp::toInstant);
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
