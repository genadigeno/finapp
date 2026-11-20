package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
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
 * {@link LendingOperatorLists} in explicit SQL (`P17-TSK-035`). The applications walk {@code V036}'s
 * {@code (submitted_at, id)} index and the loans its {@code (opened_at, id)} backwards from the cursor; a party's loans ride
 * {@code V006}'s {@code loan_by_party}. A loan's condition is the latest {@code loan_condition_event} of each kind - the
 * delinquency report's derivation (`LendingReports`), on {@code V020}'s {@code loan_condition_event_latest}. The servicing
 * queue is the three proposal tables' undecided rows, each walked forwards on its {@code V036} {@code (proposed_at, id)}
 * index. The terms versions are a small table - a few versions a product a year - read whole in order.
 */
public final class JdbcLendingOperatorLists implements LendingOperatorLists {

    /** The states a condition is evaluated in (INV-LND-08): a closed, cancelled or written-off account takes none. */
    private static final String IN_SERVICING = "l.status IN ('ACTIVE', 'CLOSING')";

    private static final String BUCKET = "COALESCE((SELECT c.bucket FROM lending.loan_condition_event c WHERE c.loan_id = l.id"
            + " AND c.kind = 'DELINQUENCY' ORDER BY c.business_date DESC LIMIT 1), 'CURRENT')";

    private static final String DEFAULTED = "COALESCE((SELECT c.defaulted FROM lending.loan_condition_event c"
            + " WHERE c.loan_id = l.id AND c.kind = 'DEFAULT' ORDER BY c.business_date DESC LIMIT 1), false)";

    @Override
    public List<ApplicationHead> applications(Connection unitOfWork, Optional<LoanApplicationStatus> status,
            UUID withheldParty, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT id, party_id, product, status, decision_request_id, terms_version_id,"
                + " submitted_at, closed_at FROM lending.loan_application WHERE party_id <> ?");
        List<Object> arguments = new ArrayList<>(List.of(withheldParty));
        status.ifPresent(value -> {
            sql.append(" AND status = ?");
            arguments.add(value.name());
        });
        before(sql, arguments, "submitted_at", "id", after);
        sql.append(" ORDER BY submitted_at DESC, id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the loan applications", rows -> new ApplicationHead(
                LoanApplicationId.of(rows.getObject("id", UUID.class)),
                rows.getObject("party_id", UUID.class),
                LoanProduct.valueOf(rows.getString("product")),
                LoanApplicationStatus.valueOf(rows.getString("status")),
                rows.getObject("decision_request_id", UUID.class),
                LoanTermsVersionId.of(rows.getObject("terms_version_id", UUID.class)),
                rows.getTimestamp("submitted_at").toInstant(),
                instant(rows.getTimestamp("closed_at"))));
    }

    @Override
    public List<LoanHead> loans(Connection unitOfWork, LoanFilter filter, UUID withheldParty, Optional<Cursor> after,
            int limit) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT l.id, l.party_id, l.product, l.kind, l.status, l.currency, l.opened_at,"
                + " l.closed_at, " + IN_SERVICING + " AS in_servicing, CASE WHEN " + IN_SERVICING + " THEN " + BUCKET
                + " END AS bucket, CASE WHEN " + IN_SERVICING + " THEN " + DEFAULTED + " END AS defaulted"
                + " FROM lending.loan l WHERE l.party_id <> ?");
        List<Object> arguments = new ArrayList<>(List.of(withheldParty));
        filter.party().ifPresent(party -> {
            sql.append(" AND l.party_id = ?");
            arguments.add(party);
        });
        filter.status().ifPresent(status -> {
            sql.append(" AND l.status = ?");
            arguments.add(status.name());
        });
        filter.bucket().ifPresent(bucket -> {
            sql.append(" AND ").append(IN_SERVICING).append(" AND ").append(BUCKET).append(" = ?");
            arguments.add(bucket);
        });
        before(sql, arguments, "l.opened_at", "l.id", after);
        sql.append(" ORDER BY l.opened_at DESC, l.id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the loans", rows -> new LoanHead(
                LoanId.of(rows.getObject("id", UUID.class)),
                rows.getObject("party_id", UUID.class),
                LoanProduct.valueOf(rows.getString("product")),
                LoanKind.valueOf(rows.getString("kind")),
                LoanStatus.valueOf(rows.getString("status")),
                CurrencyCode.of(rows.getString("currency").strip()),
                rows.getTimestamp("opened_at").toInstant(),
                instant(rows.getTimestamp("closed_at")),
                rows.getBoolean("in_servicing")
                        ? Optional.of(new RecordedCondition(rows.getString("bucket"), rows.getBoolean("defaulted")))
                        : Optional.empty()));
    }

    @Override
    public List<ServicingRequest> pendingServicing(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after,
            int limit) {
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        List<Object> arguments = new ArrayList<>();
        // Each branch walks its own (proposed_at, id) index from the cursor; the union is ordered once.
        String waivers = "SELECT w.id, 'WAIVER' AS kind, w.loan_id, w.proposed_by, w.proposed_at, w.proposal_reason,"
                + " NULL::uuid AS repayment_id FROM lending.waiver w WHERE NOT EXISTS (SELECT 1 FROM lending.waiver_decision d"
                + " WHERE d.waiver_id = w.id)" + beyond("w", arguments, after);
        String amendments = "SELECT a.id, 'AMENDMENT', a.loan_id, a.proposed_by, a.proposed_at, a.proposal_reason, NULL::uuid"
                + " FROM lending.loan_amendment a WHERE NOT EXISTS (SELECT 1 FROM lending.loan_amendment_decision d"
                + " WHERE d.amendment_id = a.id)" + beyond("a", arguments, after);
        String reversals = "SELECT r.id, 'REPAYMENT_REVERSAL', r.loan_id, r.proposed_by, r.proposed_at, r.proposal_reason,"
                + " r.repayment_id FROM lending.repayment_reversal r WHERE r.status = 'PROPOSED'" + beyond("r", arguments, after);
        String sql = "SELECT p.* FROM (" + waivers + " UNION ALL " + amendments + " UNION ALL " + reversals + ") p"
                + " JOIN lending.loan l ON l.id = p.loan_id WHERE l.party_id <> ? ORDER BY p.proposed_at, p.id LIMIT ?";
        arguments.add(withheldParty);
        arguments.add(bounded(limit));
        return read(unitOfWork, sql, arguments, "listing the servicing proposals awaiting a decision", rows -> new ServicingRequest(
                rows.getObject("id", UUID.class),
                ServicingKind.valueOf(rows.getString("kind")),
                LoanId.of(rows.getObject("loan_id", UUID.class)),
                rows.getString("proposed_by"),
                rows.getTimestamp("proposed_at").toInstant(),
                rows.getString("proposal_reason"),
                Optional.ofNullable(rows.getObject("repayment_id", UUID.class))));
    }

    @Override
    public List<TermsVersionHead> termsVersions(Connection unitOfWork, Optional<LoanProduct> product,
            Optional<LoanTermsVersionStatus> status, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT id, product, version, status, proposed_by, proposed_at, proposal_reason,"
                + " decided_at, decision_reason, effective_from, effective_to FROM lending.loan_terms_version WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        product.ifPresent(value -> {
            sql.append(" AND product = ?");
            arguments.add(value.name());
        });
        status.ifPresent(value -> {
            sql.append(" AND status = ?");
            arguments.add(value.name());
        });
        before(sql, arguments, "proposed_at", "id", after);
        sql.append(" ORDER BY proposed_at DESC, id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the terms versions", JdbcLendingOperatorLists::termsHead);
    }

    @Override
    public List<TermsVersionHead> pendingTermsVersions(Connection unitOfWork, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(after, "after");
        List<Object> arguments = new ArrayList<>();
        String sql = "SELECT id, product, version, status, proposed_by, proposed_at, proposal_reason, decided_at,"
                + " decision_reason, effective_from, effective_to FROM lending.loan_terms_version t WHERE t.status = 'PROPOSED'"
                + beyond("t", arguments, after) + " ORDER BY t.proposed_at ASC, t.id ASC LIMIT ?";
        arguments.add(bounded(limit));
        return read(unitOfWork, sql, arguments, "listing the terms versions awaiting a decision",
                JdbcLendingOperatorLists::termsHead);
    }

    @Override
    public List<PendingContribution> pendingCapitalContributions(Connection unitOfWork, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(after, "after");
        List<Object> arguments = new ArrayList<>();
        String sql = "SELECT c.id, c.proposed_by, c.proposed_at, c.reason, c.currency FROM lending.capital_contribution c"
                + " WHERE NOT EXISTS (SELECT 1 FROM lending.capital_contribution_event d WHERE d.contribution_id = c.id"
                + " AND d.to_status <> 'PROPOSED')" + beyond("c", arguments, after)
                + " ORDER BY c.proposed_at ASC, c.id ASC LIMIT ?";
        arguments.add(bounded(limit));
        return read(unitOfWork, sql, arguments, "listing the capital contributions awaiting a decision",
                rows -> new PendingContribution(
                        CapitalContributionId.of(rows.getObject("id", UUID.class)),
                        rows.getString("proposed_by"),
                        rows.getTimestamp("proposed_at").toInstant(),
                        rows.getString("reason"),
                        CurrencyCode.of(rows.getString("currency").strip())));
    }

    private static TermsVersionHead termsHead(ResultSet rows) throws SQLException {
        return new TermsVersionHead(
                LoanTermsVersionId.of(rows.getObject("id", UUID.class)),
                LoanProduct.valueOf(rows.getString("product")),
                rows.getInt("version"),
                LoanTermsVersionStatus.valueOf(rows.getString("status")),
                rows.getString("proposed_by"),
                rows.getTimestamp("proposed_at").toInstant(),
                rows.getString("proposal_reason"),
                instant(rows.getTimestamp("decided_at")),
                Optional.ofNullable(rows.getString("decision_reason")),
                instant(rows.getTimestamp("effective_from")),
                instant(rows.getTimestamp("effective_to")));
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

    /** Strictly after the cursor in an ascending order, on {@code alias}'s proposal stamp and id. */
    private static String beyond(String alias, List<Object> arguments, Optional<Cursor> after) {
        if (after.isEmpty()) {
            return "";
        }
        arguments.add(Timestamp.from(after.get().at()));
        arguments.add(after.get().id());
        return " AND (" + alias + ".proposed_at, " + alias + ".id) > (?, ?)";
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
            throw new LendingStorageException("lending storage failed " + what + ": SQLSTATE " + failure.getSQLState());
        }
    }
}
