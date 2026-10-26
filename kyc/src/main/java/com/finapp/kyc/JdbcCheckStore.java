package com.finapp.kyc;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Plain-JDBC storage for verification checks and their evidence (ADR-0033, `P2-TSK-009`).
 *
 * <p>Encryption of evidence happens here, the {@code JdbcDocumentStore} split: where bytes rest
 * is the adapter's concern, and the same {@link DocumentCipher} serves both because documents
 * and provider evidence are one at-rest concern (ADR-0036 groups them; one key per concern
 * means one key here, and {@code key_version} per row keeps a later split one rotation away).
 *
 * <h2>The convergence rule, and its one residual race</h2>
 *
 * <p>{@code requestOrConverge} converges on an existing check of the type when the newest is
 * in-flight or answered ({@code CLEAR}, {@code HIT}) — a run must not re-ask an answered
 * question. An {@code INDETERMINATE} newest is <strong>not an answered question</strong>
 * (`P2-TSK-010`, correcting `P2-TSK-009`'s converge-in-any-state, which made an unknown
 * unresolvable on the run path against ADR-0038's resolution-is-a-new-check): while the type's
 * unknowns are under {@link ChecksAssessment#INDETERMINATE_RETRY_BUDGET} a <em>new</em> check is
 * inserted — the retry — and at or past it the run converges on the newest, because the
 * assessment now routes the type to a person rather than back to the machine. The count is
 * compared with {@code >=}, so the race below overshooting the budget routes to review sooner,
 * never later.
 *
 * <p>The partial unique index arbitrates the concurrent-insert race for in-flight checks —
 * two instances both deciding to retry insert two {@code REQUESTED} rows, the index refuses the
 * second, and the savepoint path hands the loser the winner's check. The pre-flight read is the
 * <em>business rule</em> (one question per type), not a substitute for the index
 * (`P1-TSK-006`'s distinction). The residual: an instance reading just before another's
 * terminal commit can insert a redundant second question, because the terminal check has left
 * the index. That race produces a wasted provider call and an extra answer, <strong>never a
 * wrong one</strong> — an extra {@code CLEAR} changes no assessment, an extra {@code HIT} only
 * blocks harder, and an extra {@code INDETERMINATE} only spends the budget faster — which is
 * the safe direction, and why it is recorded rather than locked away.
 *
 * <h2>Rescreens (`P13-TSK-022`, {@code kyc V011})</h2>
 *
 * <p>A {@link CheckPurpose#RESCREEN} check is asked by its key, not by convergence: the key's
 * unique is its arbiter, onboarding's one-in-flight index holds onboarding checks only, and
 * every onboarding read here - {@code forCase}, the convergence, the budget's count - names
 * {@code purpose = 'ONBOARDING'}, so a list update's screening never answers a run's required
 * type, never holds a run in flight and never spends its budget. {@code allForCase} reads both -
 * an undecided case's assessment and decision, where a rescreen's {@code HIT} needs a person
 * (the review's fail-closed rule), and the reviewer's read; {@code latestDecidedScreenings}
 * reads both, bounded, for risk's screening-hit reading.
 */
@RequiredArgsConstructor
public final class JdbcCheckStore implements CheckStore<Connection> {

    private static final String TABLE = "kyc.verification_check";
    private static final String EVIDENCE_TABLE = "kyc.verification_evidence";

    /** A check's columns, evidence excluded - its purpose since `P13-TSK-022` (kyc V011). */
    private static final String COLUMNS =
            "id, case_id, check_type, status, requested_at, status_changed_at, purpose";

    /** The longest rescreen key kyc V011 admits - risk's permit key and its ordinal. */
    static final int RESCREEN_KEY_MAX = 200;

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @NonNull private final DocumentCipher cipher;

    @Override
    public Requested requestOrConverge(Connection unitOfWork, VerificationCheck fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        try {
            Optional<VerificationCheck> existing =
                    newestOfType(unitOfWork, fresh.caseId(), fresh.type());
            if (existing.isPresent() && !retryable(unitOfWork, existing.get())) {
                return new Requested(existing.get(), false);
            }
            Savepoint beforeInsert = unitOfWork.setSavepoint("verification_check_request");
            try {
                insert(unitOfWork, fresh);
                unitOfWork.releaseSavepoint(beforeInsert);
                return new Requested(fresh, true);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                unitOfWork.rollback(beforeInsert);
                // READ COMMITTED takes a new snapshot per statement, so this read sees the
                // committed winner that just refused our insert.
                return newestOfType(unitOfWork, fresh.caseId(), fresh.type())
                        .map(winner -> new Requested(winner, false))
                        .orElseThrow(
                                () ->
                                        new KycStorageException(
                                                "the one-in-flight index refused a check insert"
                                                        + " but no check is visible - a"
                                                        + " concurrent requester may have rolled"
                                                        + " back; retry"));
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "requesting a " + fresh.type() + " check on case " + fresh.caseId(),
                            failure));
        }
    }

    /**
     * Whether the newest check of a type invites a successor rather than convergence.
     *
     * <p>Only a terminal {@code INDETERMINATE} under the budget does: "we do not know" is not an
     * answered question (ADR-0038), and its resolution is a new check — until the budget, after
     * which the assessment routes the type to a person and the run stops asking machines.
     */
    private static boolean retryable(Connection unitOfWork, VerificationCheck newest)
            throws SQLException {
        if (newest.status() != CheckStatus.INDETERMINATE) {
            return false;
        }
        try (PreparedStatement count =
                unitOfWork.prepareStatement(
                        "SELECT count(*) FROM " + TABLE
                                + " WHERE case_id = ? AND check_type = ?"
                                + " AND status = 'INDETERMINATE' AND purpose = 'ONBOARDING'")) {
            count.setObject(1, newest.caseId().value());
            count.setString(2, newest.type().name());
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1) < ChecksAssessment.INDETERMINATE_RETRY_BUDGET;
            }
        }
    }

    private static void insert(Connection unitOfWork, VerificationCheck check)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE
                                + " (id, case_id, check_type, status, requested_at,"
                                + " status_changed_at, purpose)"
                                + " VALUES (?, ?, ?, ?, ?, ?, 'ONBOARDING')")) {
            insert.setObject(1, check.id().value());
            insert.setObject(2, check.caseId().value());
            insert.setString(3, check.type().name());
            insert.setString(4, check.status().name());
            insert.setTimestamp(5, Timestamp.from(check.requestedAt()));
            insert.setTimestamp(6, Timestamp.from(check.statusChangedAt()));
            insert.executeUpdate();
        }
    }

    @Override
    public boolean dispatch(Connection unitOfWork, CheckId checkId, Instant at) {
        return move(unitOfWork, checkId, CheckStatus.REQUESTED, CheckStatus.DISPATCHED, at);
    }

    @Override
    public boolean complete(
            Connection unitOfWork, CheckId checkId, CheckOutcome outcome, Instant at) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return move(unitOfWork, checkId, CheckStatus.DISPATCHED, outcome.toStatus(), at);
    }

    /** The conditional transition whose row count is the outcome — the platform's protocol. */
    private static boolean move(
            Connection unitOfWork, CheckId checkId, CheckStatus from, CheckStatus to, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(checkId, "checkId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET status = ?,"
                                // GREATEST(?, requested_at) - the P1-TSK-031 drift, clamped in
                                // the statement: a callback completes the check on whichever
                                // instance the provider reaches, whose clock may read behind the
                                // one that requested it (ADR-0014: skew is bounded, never zero),
                                // and a legal answer must not die on V004's CHECK.
                                + " status_changed_at = GREATEST(?, requested_at)"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, checkId.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "moving check " + checkId + " from " + from + " to " + to, failure));
        }
    }

    @Override
    public Optional<VerificationCheck> findById(Connection unitOfWork, CheckId checkId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(checkId, "checkId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS
                                + " FROM " + TABLE + " WHERE id = ?")) {
            select.setObject(1, checkId.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(rehydrate(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("reading check " + checkId, failure));
        }
    }

    @Override
    public List<VerificationCheck> forCase(Connection unitOfWork, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS
                                + " FROM " + TABLE
                                + " WHERE case_id = ? AND purpose = 'ONBOARDING'")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<VerificationCheck> checks = new ArrayList<>();
                while (rows.next()) {
                    checks.add(rehydrate(rows));
                }
                return checks;
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("reading the checks of case " + caseId, failure));
        }
    }

    @Override
    public List<VerificationCheck> allForCase(Connection unitOfWork, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<VerificationCheck> checks = new ArrayList<>();
                while (rows.next()) {
                    checks.add(rehydrate(rows));
                }
                return checks;
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "reading every check of case " + caseId, failure));
        }
    }

    /**
     * One index probe per screening type (`P13-TSK-022`'s review): the lateral's {@code LIMIT 1}
     * walks {@code verification_check_decided_latest} in its own order, so the read is two rows
     * however many rescreens a case has accumulated.
     */
    private static final String LATEST_DECIDED_SCREENINGS =
            "SELECT latest.* FROM (VALUES ('SANCTIONS'), ('PEP')) AS screening (screened_type)"
                    + " CROSS JOIN LATERAL (SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE case_id = ? AND check_type = screening.screened_type"
                    + " AND status IN ('CLEAR', 'HIT')"
                    + " ORDER BY requested_at DESC, status_changed_at DESC, id DESC LIMIT 1) AS latest";

    @Override
    public List<VerificationCheck> latestDecidedScreenings(
            Connection unitOfWork, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(LATEST_DECIDED_SCREENINGS)) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<VerificationCheck> checks = new ArrayList<>();
                while (rows.next()) {
                    checks.add(rehydrate(rows));
                }
                return checks;
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "reading the latest decided screenings of case " + caseId, failure));
        }
    }

    /**
     * The keyed rescreen: one insert that is a no-op under a key already asked, then the row the
     * key names. {@code ON CONFLICT (rescreen_key) DO NOTHING} waits on a concurrent uncommitted
     * insert of the same key and then does nothing, and the read after it - a new snapshot under
     * READ COMMITTED - sees the winner: ten instances asking one key make one row. Both stamps
     * are one {@code clock_timestamp()}, the database's.
     */
    @Override
    public Requested requestRescreen(
            Connection unitOfWork,
            KycCaseId caseId,
            CheckType type,
            CheckId fresh,
            String rescreenKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        requireRescreenKey(rescreenKey);
        if (type != CheckType.SANCTIONS && type != CheckType.PEP) {
            throw new IllegalArgumentException(
                    "a rescreen is a SANCTIONS or PEP screening, never " + type);
        }
        try {
            boolean created;
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "WITH asked AS (SELECT clock_timestamp() AS at)"
                                    + " INSERT INTO " + TABLE
                                    + " (id, case_id, check_type, status, requested_at,"
                                    + " status_changed_at, purpose, rescreen_key)"
                                    + " SELECT ?, ?, ?, 'REQUESTED', at, at, 'RESCREEN', ?"
                                    + " FROM asked ON CONFLICT (rescreen_key) DO NOTHING")) {
                insert.setObject(1, fresh.value());
                insert.setObject(2, caseId.value());
                insert.setString(3, type.name());
                insert.setString(4, rescreenKey);
                created = insert.executeUpdate() == 1;
            }
            VerificationCheck standing =
                    findRescreen(unitOfWork, rescreenKey)
                            .orElseThrow(
                                    () ->
                                            new KycStorageException(
                                                    "a rescreen key conflicted and no check"
                                                            + " stands for it - a concurrent"
                                                            + " requester may have rolled back;"
                                                            + " retry"));
            if (!standing.caseId().equals(caseId) || standing.type() != type) {
                throw new IllegalStateException(
                        "a rescreen key asks one question: it already asked another case or"
                                + " another type");
            }
            return new Requested(standing, created);
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "requesting a " + type + " rescreen on case " + caseId, failure));
        }
    }

    @Override
    public Optional<VerificationCheck> findRescreen(Connection unitOfWork, String rescreenKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        requireRescreenKey(rescreenKey);
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE rescreen_key = ?")) {
            select.setString(1, rescreenKey);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(rehydrate(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("reading a rescreen by its key", failure));
        }
    }

    @Override
    public Optional<String> rescreenKeyOf(Connection unitOfWork, CheckId checkId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(checkId, "checkId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT rescreen_key FROM " + TABLE + " WHERE id = ? AND purpose = 'RESCREEN'")) {
            select.setObject(1, checkId.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("reading the key of check " + checkId, failure));
        }
    }

    private static void requireRescreenKey(String rescreenKey) {
        Objects.requireNonNull(rescreenKey, "rescreenKey must not be null");
        if (rescreenKey.isBlank() || rescreenKey.length() > RESCREEN_KEY_MAX) {
            throw new IllegalArgumentException(
                    "a rescreen key is 1.." + RESCREEN_KEY_MAX + " characters");
        }
    }

    @Override
    public void appendEvidence(
            Connection unitOfWork,
            EvidenceId id,
            CheckId checkId,
            byte[] payload,
            Instant receivedAt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(checkId, "checkId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");

        DocumentCipher.Encrypted encrypted = cipher.encrypt(DocumentBytes.of(payload));
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + EVIDENCE_TABLE
                                + " (id, check_id, content_ciphertext, content_nonce,"
                                + " key_version, checksum_sha256, content_length, received_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id.value());
            insert.setObject(2, checkId.value());
            insert.setBytes(3, encrypted.ciphertext());
            insert.setBytes(4, encrypted.nonce());
            insert.setInt(5, encrypted.keyVersion());
            insert.setBytes(6, KycDocument.checksumOf(payload));
            insert.setInt(7, payload.length);
            insert.setTimestamp(8, Timestamp.from(receivedAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "retaining evidence for check " + checkId, failure));
        }
    }

    private Optional<VerificationCheck> newestOfType(
            Connection unitOfWork, KycCaseId caseId, CheckType type) throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS
                                + " FROM " + TABLE
                                + " WHERE case_id = ? AND check_type = ?"
                                + " AND purpose = 'ONBOARDING'"
                                + " ORDER BY requested_at DESC, id DESC LIMIT 1")) {
            select.setObject(1, caseId.value());
            select.setString(2, type.name());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(rehydrate(rows)) : Optional.empty();
            }
        }
    }

    private static VerificationCheck rehydrate(ResultSet row) throws SQLException {
        return VerificationCheck.rehydrate(
                CheckId.of(row.getObject("id", UUID.class)),
                KycCaseId.of(row.getObject("case_id", UUID.class)),
                CheckType.valueOf(row.getString("check_type")),
                CheckStatus.valueOf(row.getString("status")),
                row.getTimestamp("requested_at").toInstant(),
                row.getTimestamp("status_changed_at").toInstant(),
                CheckPurpose.valueOf(row.getString("purpose")));
    }
}
