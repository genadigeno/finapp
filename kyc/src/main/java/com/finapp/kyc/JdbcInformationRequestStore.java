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
import java.util.UUID;

/**
 * Reviewers' requests for information on PostgreSQL (`P17-TSK-126`, kyc {@code V016}).
 *
 * <p><strong>Isolation this relies on:</strong> {@code READ COMMITTED}. The answer is one conditional {@code UPDATE}: a sweeper
 * holding the row makes it wait, and on resume PostgreSQL re-evaluates {@code status = 'OPEN'} against the committed row, so an
 * expired request is not answered. The expiry's inner {@code SELECT … FOR UPDATE SKIP LOCKED} passes over a row an answer holds,
 * and its outer predicate repeats the inner one, so a row closed between the two is not closed twice. Both judge the deadline on
 * {@code statement_timestamp()}: one clock for every instance. Each close stamps {@code closed_at} with that clock, never before
 * the request ({@code GREATEST}, V016's {@code CHECK}).
 */
public final class JdbcInformationRequestStore implements InformationRequestStore<Connection> {

    static final String TABLE = "kyc.information_request";

    static final String COLUMNS = "id, case_id, reason_code, request_text, requested_by, requested_at, due_at, correlation_id,"
            + " status, closed_at, answer_text, answered_by";

    static final String ANSWER = "UPDATE " + TABLE + " SET status = 'ANSWERED',"
            + " closed_at = GREATEST(statement_timestamp(), requested_at), answer_text = ?, answered_by = ?"
            + " WHERE case_id = ? AND status = 'OPEN' AND due_at > statement_timestamp()"
            + " RETURNING " + COLUMNS;

    static final String EXPIRE_PAGE = "UPDATE " + TABLE + " SET status = 'EXPIRED',"
            + " closed_at = GREATEST(statement_timestamp(), requested_at)"
            + " WHERE id IN (SELECT id FROM " + TABLE
            + " WHERE status = 'OPEN' AND due_at <= statement_timestamp()"
            + " ORDER BY due_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
            + " AND status = 'OPEN' AND due_at <= statement_timestamp()"
            + " RETURNING id, case_id, due_at, correlation_id";

    @Override
    public void open(Connection unitOfWork, InformationRequest request) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!request.isOpen()) {
            throw new IllegalArgumentException("a request is written OPEN: " + request);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + TABLE + " (id, case_id, reason_code, request_text, requested_by, requested_at, due_at,"
                        + " correlation_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')")) {
            insert.setObject(1, request.id().value());
            insert.setObject(2, request.caseId().value());
            insert.setString(3, request.reason().name());
            insert.setString(4, request.text().value());
            insert.setObject(5, request.requestedBy());
            insert.setTimestamp(6, Timestamp.from(request.requestedAt()));
            insert.setTimestamp(7, Timestamp.from(request.dueAt()));
            insert.setString(8, request.correlationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe(
                    "writing an information request on KYC case " + request.caseId(), failure));
        }
    }

    @Override
    public Optional<InformationRequest> findOpenFor(Connection unitOfWork, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE case_id = ? AND status = 'OPEN'")) {
            select.setObject(1, caseId.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe(
                    "reading the open information request of KYC case " + caseId, failure));
        }
    }

    @Override
    public List<InformationRequest> forCase(Connection unitOfWork, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE case_id = ? ORDER BY requested_at, id")) {
            select.setObject(1, caseId.value());
            List<InformationRequest> requests = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    requests.add(rehydrate(rows));
                }
            }
            return requests;
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe(
                    "reading the information requests of KYC case " + caseId, failure));
        }
    }

    @Override
    public Optional<InformationRequest> answer(
            Connection unitOfWork, KycCaseId caseId, CustomerAnswer answer, UUID answeredBy) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(answeredBy, "answeredBy must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(ANSWER)) {
            update.setString(1, answer.value());
            update.setObject(2, answeredBy);
            update.setObject(3, caseId.value());
            try (ResultSet row = update.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe(
                    "answering the open information request of KYC case " + caseId, failure));
        }
    }

    @Override
    public List<Lapsed> expirePage(Connection unitOfWork, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("an expiry page is 1..1000 rows: " + limit);
        }
        try (PreparedStatement update = unitOfWork.prepareStatement(EXPIRE_PAGE)) {
            update.setInt(1, limit);
            List<Lapsed> lapsed = new ArrayList<>();
            try (ResultSet rows = update.executeQuery()) {
                while (rows.next()) {
                    lapsed.add(new Lapsed(
                            InformationRequestId.of(rows.getObject("id", UUID.class)),
                            KycCaseId.of(rows.getObject("case_id", UUID.class)),
                            rows.getTimestamp("due_at").toInstant(),
                            rows.getString("correlation_id")));
                }
            }
            return lapsed;
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe("expiring lapsed information requests", failure));
        }
    }

    private static InformationRequest rehydrate(ResultSet row) throws SQLException {
        Timestamp closedAt = row.getTimestamp("closed_at");
        String answerText = row.getString("answer_text");
        return InformationRequest.rehydrate(
                InformationRequestId.of(row.getObject("id", UUID.class)),
                KycCaseId.of(row.getObject("case_id", UUID.class)),
                InformationRequestReason.valueOf(row.getString("reason_code")),
                // The stored text passed the screen when it was written; a rule added since does not unwrite history, so a
                // text the current rules would refuse is read back as stored - the rehydrate idiom: the row was already valid.
                readText(row.getString("request_text")),
                row.getObject("requested_by", UUID.class),
                row.getTimestamp("requested_at").toInstant(),
                row.getTimestamp("due_at").toInstant(),
                row.getString("correlation_id"),
                InformationRequestStatus.valueOf(row.getString("status")),
                Optional.ofNullable(closedAt).map(Timestamp::toInstant),
                Optional.ofNullable(answerText).map(CustomerAnswer::new),
                Optional.ofNullable(row.getObject("answered_by", UUID.class)));
    }

    private static CustomerRequestText readText(String stored) {
        return CustomerRequestText.stored(stored);
    }
}
