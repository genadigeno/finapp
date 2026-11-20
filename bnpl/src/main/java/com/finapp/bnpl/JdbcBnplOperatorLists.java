package com.finapp.bnpl;

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
 * {@link BnplOperatorLists} in explicit SQL (`P17-TSK-035`). The applications walk {@code V023}'s {@code (submitted_at, id)}
 * index and the agreements its {@code (opened_at, id)} on the obligation backwards from the cursor; one merchant's ride the
 * purchase's {@code purchase_by_merchant}. A purchase's refunds walk {@code V012}'s {@code bnpl_refund_by_purchase} forwards.
 * The servicing queue is the two proposal tables' undecided rows, each walked forwards on its {@code V023}
 * {@code (proposed_at, id)} index.
 */
public final class JdbcBnplOperatorLists implements BnplOperatorLists {

    @Override
    public List<ApplicationHead> applications(Connection unitOfWork, Optional<BnplApplicationStatus> status,
            UUID withheldParty, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT id, party_id, product, plan, status, closure_reason, merchant_id,"
                + " decision_request_id, terms_version_id, submitted_at, closed_at FROM bnpl.bnpl_application"
                + " WHERE party_id <> ?");
        List<Object> arguments = new ArrayList<>(List.of(withheldParty));
        status.ifPresent(value -> {
            sql.append(" AND status = ?");
            arguments.add(value.name());
        });
        before(sql, arguments, "submitted_at", "id", after);
        sql.append(" ORDER BY submitted_at DESC, id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the BNPL applications", rows -> new ApplicationHead(
                BnplApplicationId.of(rows.getObject("id", UUID.class)),
                rows.getObject("party_id", UUID.class),
                rows.getString("product"),
                rows.getString("plan"),
                BnplApplicationStatus.valueOf(rows.getString("status")),
                Optional.ofNullable(rows.getString("closure_reason")),
                rows.getObject("merchant_id", UUID.class),
                rows.getObject("decision_request_id", UUID.class),
                BnplTermsVersionId.of(rows.getObject("terms_version_id", UUID.class)),
                rows.getTimestamp("submitted_at").toInstant(),
                instant(rows.getTimestamp("closed_at"))));
    }

    @Override
    public List<AgreementHead> agreements(Connection unitOfWork, Optional<BnplObligationStatus> status,
            Optional<UUID> merchant, UUID withheldParty, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT o.agreement_id, o.id, o.purchase_id, o.party_id, p.merchant_id, o.product,"
                + " o.plan, o.currency, o.status, o.opened_at, o.closed_at, o.closure_reason FROM bnpl.bnpl_obligation o"
                + " JOIN bnpl.purchase p ON p.id = o.purchase_id WHERE o.party_id <> ?");
        List<Object> arguments = new ArrayList<>(List.of(withheldParty));
        status.ifPresent(value -> {
            sql.append(" AND o.status = ?");
            arguments.add(value.name());
        });
        merchant.ifPresent(value -> {
            sql.append(" AND p.merchant_id = ?");
            arguments.add(value);
        });
        before(sql, arguments, "o.opened_at", "o.id", after);
        sql.append(" ORDER BY o.opened_at DESC, o.id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the BNPL agreements", rows -> new AgreementHead(
                BnplAgreementId.of(rows.getObject("agreement_id", UUID.class)),
                BnplObligationId.of(rows.getObject("id", UUID.class)),
                BnplPurchaseId.of(rows.getObject("purchase_id", UUID.class)),
                rows.getObject("party_id", UUID.class),
                rows.getObject("merchant_id", UUID.class),
                rows.getString("product"),
                rows.getString("plan"),
                CurrencyCode.of(rows.getString("currency").strip()),
                BnplObligationStatus.valueOf(rows.getString("status")),
                rows.getTimestamp("opened_at").toInstant(),
                instant(rows.getTimestamp("closed_at")),
                Optional.ofNullable(rows.getString("closure_reason"))));
    }

    @Override
    public Optional<UUID> purchaseParty(Connection unitOfWork, BnplPurchaseId purchase) {
        Objects.requireNonNull(purchase, "purchase");
        return read(unitOfWork, "SELECT party_id FROM bnpl.purchase WHERE id = ?", List.of(purchase.value()),
                "reading a purchase's party", rows -> rows.getObject("party_id", UUID.class)).stream().findFirst();
    }

    @Override
    public List<RefundHead> refunds(Connection unitOfWork, BnplPurchaseId purchase, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(after, "after");
        List<Object> arguments = new ArrayList<>(List.of(purchase.value()));
        String sql = "SELECT id, purchase_id, door, requested_by, currency, scale, amount_minor, refunded_before_minor,"
                + " fee_returned_minor, payable_debit_minor, cancelled_unbilled_minor, cancelled_billed_minor, excess_minor,"
                + " excess_to, interest_accrued_released_minor, interest_due_released_minor, agreement_id, agreement_version,"
                + " schedule_version, reason, journal_entry_id, value_date, created_at FROM bnpl.bnpl_refund"
                + " WHERE purchase_id = ?" + beyond("bnpl_refund", "created_at", arguments, after)
                + " ORDER BY created_at, id LIMIT ?";
        arguments.add(bounded(limit));
        return read(unitOfWork, sql, arguments, "listing a purchase's refunds", rows -> {
            CurrencyCode currency = CurrencyCode.of(rows.getString("currency").strip());
            int scale = rows.getInt("scale");
            return new RefundHead(
                    rows.getObject("id", UUID.class),
                    BnplPurchaseId.of(rows.getObject("purchase_id", UUID.class)),
                    rows.getString("door"),
                    rows.getString("requested_by"),
                    money(rows, "amount_minor", currency, scale),
                    money(rows, "refunded_before_minor", currency, scale),
                    money(rows, "fee_returned_minor", currency, scale),
                    money(rows, "payable_debit_minor", currency, scale),
                    money(rows, "cancelled_unbilled_minor", currency, scale),
                    money(rows, "cancelled_billed_minor", currency, scale),
                    money(rows, "excess_minor", currency, scale),
                    rows.getString("excess_to"),
                    money(rows, "interest_accrued_released_minor", currency, scale),
                    money(rows, "interest_due_released_minor", currency, scale),
                    BnplAgreementId.of(rows.getObject("agreement_id", UUID.class)),
                    rows.getInt("agreement_version"),
                    rows.getInt("schedule_version"),
                    rows.getString("reason"),
                    rows.getObject("journal_entry_id", UUID.class),
                    rows.getObject("value_date", java.time.LocalDate.class),
                    rows.getTimestamp("created_at").toInstant());
        });
    }

    @Override
    public List<ServicingRequest> pendingServicing(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after,
            int limit) {
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        List<Object> arguments = new ArrayList<>();
        // Each branch walks its own (proposed_at, id) index from the cursor; the union is ordered once.
        String waivers = "SELECT w.id, 'WAIVER' AS kind, w.agreement_id, w.obligation_id, w.proposed_by, w.proposed_at,"
                + " w.proposal_reason, NULL::uuid AS repayment_id FROM bnpl.bnpl_waiver w WHERE NOT EXISTS (SELECT 1"
                + " FROM bnpl.bnpl_waiver_decision d WHERE d.waiver_id = w.id)" + beyond("w", "proposed_at", arguments, after);
        String reversals = "SELECT r.id, 'REPAYMENT_REVERSAL', r.agreement_id, r.obligation_id, r.proposed_by, r.proposed_at,"
                + " r.proposal_reason, r.repayment_id FROM bnpl.bnpl_repayment_reversal r WHERE r.status = 'PROPOSED'"
                + beyond("r", "proposed_at", arguments, after);
        String sql = "SELECT p.* FROM (" + waivers + " UNION ALL " + reversals + ") p"
                + " JOIN bnpl.bnpl_obligation o ON o.id = p.obligation_id WHERE o.party_id <> ?"
                + " ORDER BY p.proposed_at, p.id LIMIT ?";
        arguments.add(withheldParty);
        arguments.add(bounded(limit));
        return read(unitOfWork, sql, arguments, "listing the BNPL servicing proposals awaiting a decision",
                rows -> new ServicingRequest(
                        rows.getObject("id", UUID.class),
                        ServicingKind.valueOf(rows.getString("kind")),
                        BnplAgreementId.of(rows.getObject("agreement_id", UUID.class)),
                        rows.getString("proposed_by"),
                        rows.getTimestamp("proposed_at").toInstant(),
                        rows.getString("proposal_reason"),
                        Optional.ofNullable(rows.getObject("repayment_id", UUID.class))));
    }

    @Override
    public List<PendingProposal> pendingTermsVersions(Connection unitOfWork, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(after, "after");
        List<Object> arguments = new ArrayList<>();
        String sql = "SELECT t.id, t.proposed_by, t.proposed_at, t.proposal_reason, t.product AS subject"
                + " FROM bnpl.bnpl_terms_version t WHERE t.status = 'PROPOSED'" + beyond("t", "proposed_at", arguments, after)
                + " ORDER BY t.proposed_at ASC, t.id ASC LIMIT ?";
        arguments.add(bounded(limit));
        return read(unitOfWork, sql, arguments, "listing the BNPL terms versions awaiting a decision",
                JdbcBnplOperatorLists::pendingProposal);
    }

    @Override
    public List<PendingProposal> pendingRefundProposals(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after,
            int limit) {
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        List<Object> arguments = new ArrayList<>(List.of(withheldParty));
        String sql = "SELECT r.id, r.proposed_by, r.proposed_at, r.proposal_reason, r.purchase_id::text AS subject"
                + " FROM bnpl.refund_proposal r JOIN bnpl.purchase p ON p.id = r.purchase_id"
                + " WHERE r.status = 'PROPOSED' AND p.party_id <> ?" + beyond("r", "proposed_at", arguments, after)
                + " ORDER BY r.proposed_at ASC, r.id ASC LIMIT ?";
        arguments.add(bounded(limit));
        return read(unitOfWork, sql, arguments, "listing the BNPL refund proposals awaiting a decision",
                JdbcBnplOperatorLists::pendingProposal);
    }

    @Override
    public List<PendingProposal> pendingDisputeResolutions(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after,
            int limit) {
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        List<Object> arguments = new ArrayList<>(List.of(withheldParty));
        // The dispute's id, not the proposal's: the approval and rejection doors are the dispute's. One live proposal a
        // dispute (bnpl_dispute_proposal_one_live), so (proposed_at, dispute id) is a total order.
        StringBuilder sql = new StringBuilder("SELECT d.id, substr(p.proposed_by, char_length('OPERATOR:') + 1) AS proposed_by,"
                + " p.proposed_at, p.proposal_reason, d.purchase_id::text AS subject FROM bnpl.bnpl_dispute_proposal p"
                + " JOIN bnpl.bnpl_dispute d ON d.id = p.dispute_id WHERE p.status = 'PROPOSED' AND d.party_id <> ?");
        after.ifPresent(cursor -> {
            sql.append(" AND (p.proposed_at, d.id) > (?, ?)");
            arguments.add(Timestamp.from(cursor.at()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY p.proposed_at ASC, d.id ASC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the BNPL dispute resolutions awaiting approval",
                JdbcBnplOperatorLists::pendingProposal);
    }

    private static PendingProposal pendingProposal(ResultSet rows) throws SQLException {
        return new PendingProposal(
                rows.getObject("id", UUID.class),
                rows.getString("proposed_by"),
                rows.getTimestamp("proposed_at").toInstant(),
                rows.getString("proposal_reason"),
                rows.getString("subject"));
    }

    private static Money money(ResultSet rows, String column, CurrencyCode currency, int scale) throws SQLException {
        return Money.ofPersisted(rows.getLong(column), currency, scale);
    }

    private static Optional<Instant> instant(Timestamp stamp) {
        return Optional.ofNullable(stamp).map(Timestamp::toInstant);
    }

    /** Strictly before the cursor in a descending order: the row comparison the index walks backwards. */
    private static void before(StringBuilder sql, List<Object> arguments, String stamp, String id, Optional<Cursor> after) {
        after.ifPresent(cursor -> {
            sql.append(" AND (").append(stamp).append(", ").append(id).append(") < (?, ?)");
            arguments.add(Timestamp.from(cursor.at()));
            arguments.add(cursor.id());
        });
    }

    /** Strictly after the cursor in an ascending order, on {@code alias}'s {@code stamp} and id. */
    private static String beyond(String alias, String stamp, List<Object> arguments, Optional<Cursor> after) {
        if (after.isEmpty()) {
            return "";
        }
        arguments.add(Timestamp.from(after.get().at()));
        arguments.add(after.get().id());
        return " AND (" + alias + "." + stamp + ", " + alias + ".id) > (?, ?)";
    }

    private static int bounded(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one row, not " + limit);
        }
        return limit;
    }

    @FunctionalInterface
    private interface Row<T> {
        T of(ResultSet rows) throws SQLException;
    }

    private static <T> List<T> read(Connection unitOfWork, String sql, List<Object> arguments, String what, Row<T> row) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < arguments.size(); i++) {
                select.setObject(i + 1, arguments.get(i));
            }
            List<T> found = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(row.of(rows));
                }
            }
            return found;
        } catch (SQLException failure) {
            throw new BnplStorageException("bnpl storage failed " + what + ": SQLSTATE " + failure.getSQLState());
        }
    }
}
