package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplDisputeStore} over plain JDBC (`P12-TSK-023`, ADR-0033; {@code bnpl V015}): every instant and business date
 * the database's - stamped by {@code bnpl V015}'s triggers after the act's locks, whatever is bound overwritten - every edge
 * conditional on its from-state, every merchant read {@code merchant_id = ?} in the statement, every failure its operation
 * and SQLSTATE only ({@link BnplStorageException}).
 */
public final class JdbcBnplDisputeStore implements BnplDisputeStore {

    private static final String COLUMNS = "d.id, d.purchase_id, d.obligation_id, d.merchant_id, d.party_id,"
            + " d.agreement_id, d.terms_version_id, d.status, d.reason_category, d.reason, d.request_key, d.opened_at,"
            + " d.opened_business_date, d.response_deadline_at, d.merchant_statement, d.merchant_responded_at,"
            + " d.resolved_by, d.resolution_reason, d.resolved_at, d.resolved_business_date, d.refund_id, d.proposal_id,"
            + " d.correlation_id, c.currency, c.scale, c.amount_minor, c.refunded_before_minor";

    private static final String FROM = " FROM bnpl.bnpl_dispute d JOIN bnpl.bnpl_dispute_claim c ON c.dispute_id = d.id";

    private static final String PROPOSAL_COLUMNS = "id, dispute_id, outcome, status, proposed_by, proposal_reason,"
            + " proposed_at, decided_by, decision_reason, decided_at, correlation_id";

    @Override
    public Optional<BnplDispute> find(Connection unitOfWork, BnplDisputeId id) {
        Objects.requireNonNull(id, "id");
        return first(disputes(unitOfWork, "SELECT " + COLUMNS + FROM + " WHERE d.id = ?", List.of(id.value()),
                "reading a BNPL dispute"));
    }

    @Override
    public Optional<BnplDispute> lock(Connection unitOfWork, BnplDisputeId id) {
        Objects.requireNonNull(id, "id");
        return first(disputes(unitOfWork, "SELECT " + COLUMNS + FROM + " WHERE d.id = ? FOR UPDATE OF d",
                List.of(id.value()), "locking a BNPL dispute"));
    }

    @Override
    public Optional<BnplDispute> findByKey(Connection unitOfWork, UUID party, String requestKey) {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(requestKey, "requestKey");
        return first(disputes(unitOfWork, "SELECT " + COLUMNS + FROM + " WHERE d.party_id = ? AND d.request_key = ?",
                List.of(party, requestKey), "reading a BNPL dispute by its key"));
    }

    @Override
    public Optional<BnplDispute> openOf(Connection unitOfWork, BnplPurchaseId purchase) {
        Objects.requireNonNull(purchase, "purchase");
        return first(disputes(unitOfWork, "SELECT " + COLUMNS + FROM + " WHERE d.purchase_id = ?"
                + " AND d.status IN ('OPEN', 'MERCHANT_RESPONDED')", List.of(purchase.value()),
                "reading a BNPL purchase's open dispute"));
    }

    @Override
    public BnplDispute insert(Connection unitOfWork, BnplDispute dispute) {
        Objects.requireNonNull(dispute, "dispute");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // The opening, its business date, the deadline and the permit are the trigger's.
                "INSERT INTO bnpl.bnpl_dispute (id, purchase_id, obligation_id, merchant_id, party_id, agreement_id,"
                        + " terms_version_id, status, reason_category, reason, request_key, opened_at,"
                        + " opened_business_date, response_deadline_at, next_sweep_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, clock_timestamp(), current_date,"
                        + " clock_timestamp() + interval '1 day', clock_timestamp(), ?)")) {
            insert.setObject(1, dispute.id().value());
            insert.setObject(2, dispute.purchase().value());
            insert.setObject(3, dispute.obligation().value());
            insert.setObject(4, dispute.merchant());
            insert.setObject(5, dispute.party());
            insert.setObject(6, dispute.agreement().value());
            insert.setObject(7, dispute.termsVersion().value());
            insert.setString(8, dispute.category().name());
            insert.setString(9, dispute.reason());
            insert.setString(10, dispute.requestKey());
            insert.setString(11, dispute.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL dispute", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_dispute_claim (dispute_id, currency, scale, amount_minor, refunded_before_minor)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            insert.setObject(1, dispute.id().value());
            insert.setString(2, dispute.amount().currency().code());
            insert.setInt(3, dispute.amount().scale());
            insert.setLong(4, dispute.amount().minorUnits());
            insert.setLong(5, dispute.refundedBefore().minorUnits());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL dispute's claim", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_dispute_hold (id, dispute_id, obligation_id, sequence, instalment_id, due_date,"
                        + " kind, currency, scale, held_principal_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (BnplDispute.Held held : dispute.holds()) {
                insert.setObject(1, held.id());
                insert.setObject(2, dispute.id().value());
                insert.setObject(3, dispute.obligation().value());
                insert.setInt(4, held.sequence());
                insert.setObject(5, held.instalment());
                insert.setDate(6, Date.valueOf(held.dueDate()));
                insert.setString(7, held.kind().name());
                insert.setString(8, held.principal().currency().code());
                insert.setInt(9, held.principal().scale());
                insert.setLong(10, held.principal().minorUnits());
                insert.addBatch();
            }
            if (!dispute.holds().isEmpty()) {
                insert.executeBatch();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL dispute's hold", failure);
        }
        return find(unitOfWork, dispute.id())
                .orElseThrow(() -> new IllegalStateException("a dispute just born is readable in its transaction"));
    }

    @Override
    public Optional<BnplDispute> move(Connection unitOfWork, BnplDispute moved, BnplDisputeStatus from) {
        Objects.requireNonNull(moved, "moved");
        Objects.requireNonNull(from, "from");
        int changed;
        try (PreparedStatement update = unitOfWork.prepareStatement(
                // The instants and the business date are the trigger's; the edge conditional on its from-state.
                "UPDATE bnpl.bnpl_dispute SET status = ?, merchant_statement = ?, resolved_by = ?, resolution_reason = ?,"
                        + " refund_id = ?, proposal_id = ?,"
                        + " merchant_responded_at = CASE WHEN ? THEN clock_timestamp() ELSE merchant_responded_at END,"
                        + " resolved_at = CASE WHEN ? THEN clock_timestamp() ELSE NULL END,"
                        + " resolved_business_date = CASE WHEN ? THEN current_date ELSE NULL END"
                        + " WHERE id = ? AND status = ?")) {
            boolean answered = moved.merchantStatement().isPresent() && from == BnplDisputeStatus.OPEN;
            boolean terminal = !moved.open();
            update.setString(1, moved.status().name());
            update.setString(2, moved.merchantStatement().orElse(null));
            update.setString(3, moved.resolvedBy().orElse(null));
            update.setString(4, moved.resolutionReason().orElse(null));
            uuidOrNull(update, 5, moved.refund());
            uuidOrNull(update, 6, moved.proposal());
            update.setBoolean(7, answered);
            update.setBoolean(8, terminal);
            update.setBoolean(9, terminal);
            update.setObject(10, moved.id().value());
            update.setString(11, from.name());
            changed = update.executeUpdate();
        } catch (SQLException failure) {
            throw failed("moving a BNPL dispute", failure);
        }
        return changed == 1 ? find(unitOfWork, moved.id()) : Optional.empty();
    }

    @Override
    public void appendEvent(Connection unitOfWork, Event event) {
        Objects.requireNonNull(event, "event");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_dispute_event (id, dispute_id, from_status, to_status, actor, occurred_at,"
                        + " correlation_id) VALUES (?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, event.id());
            insert.setObject(2, event.dispute().value());
            insert.setString(3, event.from().map(Enum::name).orElse(null));
            insert.setString(4, event.to().name());
            insert.setString(5, event.actor());
            insert.setString(6, event.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("appending a BNPL dispute's history", failure);
        }
    }

    @Override
    public List<BnplDisputeId> claimLapsed(Connection unitOfWork, int limit, Duration cadence) {
        Objects.requireNonNull(cadence, "cadence");
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one dispute");
        }
        if (cadence.isNegative() || cadence.isZero()) {
            throw new IllegalArgumentException("the retry cadence is positive");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                // The page locked SKIP LOCKED, each permit re-stamped on the database clock; the claim's transaction
                // commits at once, so it holds no row when each step then takes B5, B4 and B6 in order (ADR-0106 decision 9).
                "UPDATE bnpl.bnpl_dispute SET next_sweep_at = clock_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM bnpl.bnpl_dispute WHERE status = 'OPEN'"
                        + " AND response_deadline_at <= statement_timestamp() AND next_sweep_at <= statement_timestamp()"
                        + " ORDER BY next_sweep_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND status = 'OPEN' RETURNING id")) {
            claim.setLong(1, cadence.toMillis());
            claim.setInt(2, limit);
            List<BnplDisputeId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(BnplDisputeId.of(rows.getObject(1, UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw failed("claiming lapsed BNPL disputes", failure);
        }
    }

    @Override
    public List<BnplDispute> openAtMerchant(Connection unitOfWork, UUID merchant) {
        Objects.requireNonNull(merchant, "merchant");
        // The tenant is in the statement (INV-MER-01).
        return disputes(unitOfWork, "SELECT " + COLUMNS + FROM + " WHERE d.merchant_id = ?"
                + " AND d.status IN ('OPEN', 'MERCHANT_RESPONDED') ORDER BY d.opened_at, d.id LIMIT 500",
                List.of(merchant), "reading a merchant's open BNPL disputes");
    }

    @Override
    public boolean anyOpenAtMerchant(Connection unitOfWork, UUID merchant) {
        Objects.requireNonNull(merchant, "merchant");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT EXISTS (SELECT 1 FROM bnpl.bnpl_dispute"
                + " WHERE merchant_id = ? AND status IN ('OPEN', 'MERCHANT_RESPONDED'))")) {
            select.setObject(1, merchant);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading whether a merchant has an open BNPL dispute", failure);
        }
    }

    @Override
    public Optional<BnplDisputeProposal> liveProposal(Connection unitOfWork, BnplDisputeId dispute) {
        Objects.requireNonNull(dispute, "dispute");
        return proposal(unitOfWork, "SELECT " + PROPOSAL_COLUMNS + " FROM bnpl.bnpl_dispute_proposal WHERE dispute_id = ?"
                + " AND status IN ('PROPOSED', 'APPROVED')", List.of(dispute.value()), "reading a dispute's proposal");
    }

    @Override
    public BnplDisputeProposal insertProposal(Connection unitOfWork, BnplDisputeProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_dispute_proposal (id, dispute_id, outcome, status, proposed_by, proposal_reason,"
                        + " proposed_at, correlation_id) VALUES (?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, proposal.id());
            insert.setObject(2, proposal.dispute().value());
            insert.setString(3, proposal.outcome().name());
            insert.setString(4, proposal.proposedBy());
            insert.setString(5, proposal.proposalReason());
            insert.setString(6, proposal.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL dispute proposal", failure);
        }
        return proposal(unitOfWork, "SELECT " + PROPOSAL_COLUMNS + " FROM bnpl.bnpl_dispute_proposal WHERE id = ?",
                List.of(proposal.id()), "reading a BNPL dispute proposal")
                .orElseThrow(() -> new IllegalStateException("a proposal just born is readable in its transaction"));
    }

    @Override
    public Optional<BnplDisputeProposal> lockProposal(Connection unitOfWork, UUID id, BnplDisputeId dispute) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dispute, "dispute");
        return proposal(unitOfWork, "SELECT " + PROPOSAL_COLUMNS + " FROM bnpl.bnpl_dispute_proposal"
                + " WHERE id = ? AND dispute_id = ? FOR UPDATE", List.of(id, dispute.value()),
                "locking a BNPL dispute proposal");
    }

    @Override
    public Optional<BnplDisputeProposal> decide(Connection unitOfWork, BnplDisputeProposal decided) {
        Objects.requireNonNull(decided, "decided");
        int changed;
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.bnpl_dispute_proposal SET status = ?, decided_by = ?, decision_reason = ?,"
                        + " decided_at = clock_timestamp() WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, decided.status().name());
            update.setString(2, decided.decidedBy().orElseThrow());
            update.setString(3, decided.decisionReason().orElseThrow());
            update.setObject(4, decided.id());
            changed = update.executeUpdate();
        } catch (SQLException failure) {
            throw failed("deciding a BNPL dispute proposal", failure);
        }
        return changed == 1 ? proposal(unitOfWork, "SELECT " + PROPOSAL_COLUMNS
                + " FROM bnpl.bnpl_dispute_proposal WHERE id = ?", List.of(decided.id()),
                "reading a BNPL dispute proposal") : Optional.empty();
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<BnplDispute> first(List<BnplDispute> found) {
        return found.stream().findFirst();
    }

    private static List<BnplDispute> disputes(Connection unitOfWork, String sql, List<Object> parameters,
            String operation) {
        Map<UUID, Row> rows = new LinkedHashMap<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int index = 0; index < parameters.size(); index++) {
                select.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.put(row.getObject("id", UUID.class), Row.of(row));
                }
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
        List<BnplDispute> disputes = new ArrayList<>();
        for (Row row : rows.values()) {
            disputes.add(row.dispute(holds(unitOfWork, row.id(), row.currency(), row.scale())));
        }
        return disputes;
    }

    private static List<BnplDispute.Held> holds(Connection unitOfWork, UUID dispute, CurrencyCode currency, int scale) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, sequence, instalment_id, due_date, kind, held_principal_minor FROM bnpl.bnpl_dispute_hold"
                        + " WHERE dispute_id = ? ORDER BY sequence DESC")) {
            select.setObject(1, dispute);
            List<BnplDispute.Held> held = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    held.add(new BnplDispute.Held(row.getObject("id", UUID.class), row.getInt("sequence"),
                            row.getObject("instalment_id", UUID.class), row.getDate("due_date").toLocalDate(),
                            RefundAllocation.Cancellation.Kind.valueOf(row.getString("kind")),
                            Money.ofPersisted(row.getLong("held_principal_minor"), currency, scale)));
                }
            }
            return held;
        } catch (SQLException failure) {
            throw failed("reading a BNPL dispute's hold", failure);
        }
    }

    /** One dispute row, read whole before its hold is read (one open result set at a time). */
    private record Row(UUID id, UUID purchase, UUID obligation, UUID merchant, UUID party, UUID agreement,
            UUID termsVersion, String status, String category, String reason, String requestKey, Instant openedAt,
            LocalDate openedOn, Instant deadline, String statement, Instant respondedAt, String resolvedBy,
            String resolutionReason, Instant resolvedAt, LocalDate resolvedOn, UUID refund, UUID proposal,
            String correlation, CurrencyCode currency, int scale, long amount, long refundedBefore) {

        static Row of(ResultSet row) throws SQLException {
            Date resolvedOn = row.getDate("resolved_business_date");
            return new Row(row.getObject("id", UUID.class), row.getObject("purchase_id", UUID.class),
                    row.getObject("obligation_id", UUID.class), row.getObject("merchant_id", UUID.class),
                    row.getObject("party_id", UUID.class), row.getObject("agreement_id", UUID.class),
                    row.getObject("terms_version_id", UUID.class), row.getString("status"),
                    row.getString("reason_category"), row.getString("reason"), row.getString("request_key"),
                    instant(row.getTimestamp("opened_at")), row.getDate("opened_business_date").toLocalDate(),
                    instant(row.getTimestamp("response_deadline_at")), row.getString("merchant_statement"),
                    instant(row.getTimestamp("merchant_responded_at")), row.getString("resolved_by"),
                    row.getString("resolution_reason"), instant(row.getTimestamp("resolved_at")),
                    resolvedOn == null ? null : resolvedOn.toLocalDate(), row.getObject("refund_id", UUID.class),
                    row.getObject("proposal_id", UUID.class), row.getString("correlation_id"),
                    CurrencyCode.of(row.getString("currency").strip()), row.getInt("scale"), row.getLong("amount_minor"),
                    row.getLong("refunded_before_minor"));
        }

        BnplDispute dispute(List<BnplDispute.Held> holds) {
            return new BnplDispute(BnplDisputeId.of(id), BnplPurchaseId.of(purchase), BnplObligationId.of(obligation),
                    merchant, party, BnplAgreementId.of(agreement), BnplTermsVersionId.of(termsVersion),
                    BnplDisputeStatus.valueOf(status), BnplDisputeReason.valueOf(category), reason, requestKey,
                    Money.ofPersisted(amount, currency, scale), Money.ofPersisted(refundedBefore, currency, scale),
                    Optional.of(openedAt), Optional.of(openedOn), Optional.of(deadline), Optional.ofNullable(statement),
                    Optional.ofNullable(respondedAt), Optional.ofNullable(resolvedBy),
                    Optional.ofNullable(resolutionReason), Optional.ofNullable(resolvedAt), Optional.ofNullable(resolvedOn),
                    Optional.ofNullable(refund), Optional.ofNullable(proposal), CorrelationId.of(correlation), holds);
        }

        private static Instant instant(Timestamp stamp) {
            return stamp == null ? null : stamp.toInstant();
        }
    }

    private static Optional<BnplDisputeProposal> proposal(Connection unitOfWork, String sql, List<Object> parameters,
            String operation) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int index = 0; index < parameters.size(); index++) {
                select.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                Timestamp decidedAt = row.getTimestamp("decided_at");
                return Optional.of(new BnplDisputeProposal(row.getObject("id", UUID.class),
                        BnplDisputeId.of(row.getObject("dispute_id", UUID.class)),
                        BnplDisputeStatus.valueOf(row.getString("outcome")),
                        BnplDisputeProposal.Status.valueOf(row.getString("status")), row.getString("proposed_by"),
                        row.getString("proposal_reason"), Optional.of(row.getTimestamp("proposed_at").toInstant()),
                        Optional.ofNullable(row.getString("decided_by")),
                        Optional.ofNullable(row.getString("decision_reason")),
                        Optional.ofNullable(decidedAt).map(Timestamp::toInstant),
                        CorrelationId.of(row.getString("correlation_id"))));
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static void uuidOrNull(PreparedStatement statement, int index, Optional<UUID> value) throws SQLException {
        if (value.isPresent()) {
            statement.setObject(index, value.get());
        } else {
            statement.setNull(index, Types.OTHER);
        }
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
