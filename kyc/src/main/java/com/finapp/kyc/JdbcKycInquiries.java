package com.finapp.kyc;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The reviewers' queues on PostgreSQL (`P17-TSK-029`). Each statement is one keyset range: its row comparison names exactly the
 * columns its {@code ORDER BY} names, in that order, compared {@code >} because both orders ascend.
 *
 * <p>The review queue restates {@code kyc_case_review_queue}'s predicate (kyc {@code V015}), so the planner reads the open cases
 * as one range of that index, never the decided history. The backlog is one range of V009's
 * {@code counterparty_screening_awaiting_review}, whose rows are exactly the screenings {@code IN_REVIEW}.
 */
public final class JdbcKycInquiries implements KycInquiries {

    static final String OPEN_CASES = "SELECT id, customer_id, case_kind, status, policy_version, opened_at, status_changed_at"
            + " FROM kyc.kyc_case WHERE status NOT IN ('APPROVED', 'REJECTED') AND status = ANY (?)";
    static final String AWAITING_REVIEW =
            "SELECT id, status, decided_at FROM kyc.counterparty_screening WHERE status = 'IN_REVIEW'";

    @Override
    public List<KycCase> openCases(
            Connection unitOfWork, Set<KycCaseStatus> statuses, Optional<QueuePosition> after, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(statuses, "statuses must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (statuses.isEmpty() || statuses.stream().anyMatch(KycCaseStatus::isTerminal)) {
            throw new IllegalArgumentException("the review queue holds open cases only: " + statuses);
        }
        positive(limit);
        String sql = OPEN_CASES
                + (after.isPresent() ? " AND (opened_at, id) > (?, ?)" : "")
                + " ORDER BY opened_at, id LIMIT ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            int parameter = 1;
            select.setArray(parameter++, unitOfWork.createArrayOf(
                    "text", statuses.stream().map(Enum::name).sorted().toArray()));
            if (after.isPresent()) {
                select.setTimestamp(parameter++, Timestamp.from(after.get().openedAt()));
                select.setObject(parameter++, after.get().caseId());
            }
            select.setInt(parameter, limit);
            List<KycCase> page = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    page.add(KycCase.rehydrate(
                            KycCaseId.of(rows.getObject("id", UUID.class)),
                            rows.getObject("customer_id", UUID.class),
                            KycCaseKind.valueOf(rows.getString("case_kind")),
                            KycCaseStatus.valueOf(rows.getString("status")),
                            new KycPolicyVersion(rows.getString("policy_version")),
                            rows.getTimestamp("opened_at").toInstant(),
                            rows.getTimestamp("status_changed_at").toInstant()));
                }
            }
            return page;
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe("reading a page of the KYC review queue", failure));
        }
    }

    @Override
    public List<AwaitingReview> screeningsAwaitingReview(Connection unitOfWork, Optional<BacklogPosition> after, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(after, "after must not be null");
        positive(limit);
        String sql = AWAITING_REVIEW
                + (after.isPresent() ? " AND (decided_at, id) > (?, ?)" : "")
                + " ORDER BY decided_at, id LIMIT ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            int parameter = 1;
            if (after.isPresent()) {
                select.setTimestamp(parameter++, Timestamp.from(after.get().raisedAt()));
                select.setObject(parameter++, after.get().screeningId());
            }
            select.setInt(parameter, limit);
            List<AwaitingReview> page = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    page.add(new AwaitingReview(
                            CounterpartyScreeningId.of(rows.getObject("id", UUID.class)),
                            CounterpartyScreeningStatus.valueOf(rows.getString("status")),
                            rows.getTimestamp("decided_at").toInstant()));
                }
            }
            return page;
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("reading a page of the counterparty screenings awaiting review", failure));
        }
    }

    private static void positive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a page reads at least one row: " + limit);
        }
    }
}
