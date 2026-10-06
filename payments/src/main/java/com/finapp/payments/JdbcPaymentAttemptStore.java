package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
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

/** {@link PaymentAttemptStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcPaymentAttemptStore implements PaymentAttemptStore<Connection> {

    private static final String COLUMNS =
            "id, intent_id, auth_reference, capture_reference, auth_provider_reference,"
                    + " capture_provider_reference, authorized_amount_minor,"
                    + " authorized_currency, authorized_scale, captured_amount_minor,"
                    + " captured_currency, captured_scale, failure_reason, status, created_at,"
                    + " rail, interaction_model, void_reference, void_provider_reference,"
                    + " end_to_end_reference, authorization_handle, scheme_reference,"
                    + " settlement_cycle, last_dispatched_at";

    /** {@link #COLUMNS}, each qualified as {@code a.<column>} — for the joined reads. */
    private static String qualified() {
        return java.util.Arrays.stream(COLUMNS.split(", "))
                .map(column -> "a." + column.strip())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    @Override
    public void insert(Connection unitOfWork, PaymentAttempt attempt) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.payment_attempt (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?,"
                                + " GREATEST(CAST(? AS timestamptz), CASE WHEN ? THEN statement_timestamp() END))")) {
            insert.setObject(1, attempt.id().value());
            insert.setObject(2, attempt.intentId().value());
            insert.setString(
                    3,
                    attempt.authorizationReference() == null
                            ? null
                            : attempt.authorizationReference().value());
            insert.setString(
                    4,
                    attempt.captureReference() == null
                            ? null
                            : attempt.captureReference().value());
            insert.setString(
                    5,
                    attempt.authorizationProviderReference() == null
                            ? null
                            : attempt.authorizationProviderReference().value());
            insert.setString(
                    6,
                    attempt.captureProviderReference() == null
                            ? null
                            : attempt.captureProviderReference().value());
            setMoney(insert, 7, attempt.authorizedAmount());
            setMoney(insert, 10, attempt.capturedAmount());
            insert.setString(
                    13, attempt.failureReason() == null ? null : attempt.failureReason().name());
            insert.setString(14, attempt.status().name());
            insert.setTimestamp(15, Timestamp.from(attempt.createdAt()));
            insert.setString(16, attempt.rail().value());
            insert.setString(17, attempt.interactionModel().name());
            insert.setString(
                    18,
                    attempt.voidReference() == null ? null : attempt.voidReference().value());
            insert.setString(
                    19,
                    attempt.voidProviderReference() == null
                            ? null
                            : attempt.voidProviderReference().value());
            insert.setString(
                    20,
                    attempt.endToEndReference() == null
                            ? null
                            : attempt.endToEndReference().value());
            // Birth never carries a handle: it arrives with the scheme's answer, through
            // openInitiation's one registered expose site.
            insert.setString(21, null);
            insert.setString(
                    22,
                    attempt.schemeReference().map(ProviderReference::value).orElse(null));
            insert.setString(23, attempt.settlementCycle().orElse(null));
            // A push attempt's birth permit: never older than the database's clock (X-TSK-013),
            // never before its created_at (the CHECK); a two-step attempt carries none.
            insert.setTimestamp(
                    24,
                    attempt.lastDispatchedAt() == null
                            ? null
                            : Timestamp.from(attempt.lastDispatchedAt()));
            insert.setBoolean(25, attempt.lastDispatchedAt() != null);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "inserting payment attempt " + attempt.id(), failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> findForIntent(
            Connection unitOfWork, PaymentIntentId intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE intent_id = ?"
                                + " ORDER BY created_at DESC, id DESC LIMIT 1")) {
            read.setObject(1, intent.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading the attempt of intent " + intent, failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> lockById(Connection unitOfWork, PaymentAttemptId attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, attempt.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("locking attempt " + attempt, failure));
        }
    }

    @Override
    public List<PaymentAttempt> findSweepable(
            Connection unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit) {
        Objects.requireNonNull(dispatchedBefore, "dispatchedBefore must not be null");
        Objects.requireNonNull(unknownBefore, "unknownBefore must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        // The four resolvable states, pinned as literals beside the machine's own exact
        // values() pin (P5-TSK-007): no isSweepable() derivation exists, so a new machine
        // state forces this list into review - recorded to the phase audit. State age is the
        // latest transition row, with birth as the fallback (an attempt is BORN
        // AUTH_DISPATCHED, so its dispatch age IS its birth age).
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt a"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.payment_attempt_event e"
                                + "   WHERE e.attempt_id = a.id) h ON true"
                                // TWO_STEP only (P7-TSK-002, INV-RAIL-01): this sweep
                                // resolves by querying the CARD provider with our stored
                                // reference, which a push row does not even carry - its
                                // resolution arrives with its rail (P7-TSK-006, -009).
                                + " WHERE a.interaction_model = 'TWO_STEP'"
                                + " AND ((a.status IN ('AUTH_DISPATCHED', 'CAPTURE_DISPATCHED',"
                                + "                    'VOID_DISPATCHED')"
                                + "        AND COALESCE(h.entered, a.created_at) <= ?)"
                                + "    OR (a.status IN ('AUTH_UNKNOWN', 'CAPTURE_UNKNOWN',"
                                + "                     'VOID_UNKNOWN')"
                                + "        AND COALESCE(h.entered, a.created_at) <= ?))"
                                + " ORDER BY a.created_at, a.id"
                                + " LIMIT ?")) {
            read.setTimestamp(1, java.sql.Timestamp.from(dispatchedBefore));
            read.setTimestamp(2, java.sql.Timestamp.from(unknownBefore));
            read.setInt(3, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<PaymentAttempt> sweepable = new ArrayList<>();
                while (rows.next()) {
                    sweepable.add(rehydrate(rows));
                }
                return List.copyOf(sweepable);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading sweepable attempts", failure));
        }
    }

    @Override
    public List<PaymentAttempt> findStrandedAuthorizations(
            Connection unitOfWork, Instant authorizedBefore, int limit) {
        Objects.requireNonNull(authorizedBefore, "authorizedBefore must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        // findSweepable's age expression: the latest transition - the move INTO AUTHORIZED -
        // with birth as the fallback.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + qualified() + " FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.payment_attempt_event e"
                                + "   WHERE e.attempt_id = a.id) h ON true"
                                // AUTHORIZED is two-step vocabulary by CHECK; stated anyway,
                                // and the intent's capture mode is the leg's licence
                                // (P7-TSK-002): a resting authorization is a person's to
                                // take, never a sweep's.
                                + " WHERE a.status = 'AUTHORIZED'"
                                + "   AND a.interaction_model = 'TWO_STEP'"
                                + "   AND i.capture_mode = 'AUTOMATIC'"
                                + "   AND COALESCE(h.entered, a.created_at) <= ?"
                                + " ORDER BY a.created_at, a.id"
                                + " LIMIT ?")) {
            read.setTimestamp(1, java.sql.Timestamp.from(authorizedBefore));
            read.setInt(2, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<PaymentAttempt> stranded = new ArrayList<>();
                while (rows.next()) {
                    stranded.add(rehydrate(rows));
                }
                return List.copyOf(stranded);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading stranded authorizations", failure));
        }
    }

    @Override
    public UnknownReading unknownReading(
            Connection unitOfWork, java.time.Duration dispatchedBound) {
        Objects.requireNonNull(dispatchedBound, "dispatchedBound must not be null");
        // One aggregate over what is stuck, aged the sweeper's way (the findSweepable
        // expression: the latest transition, birth as the fallback): the two honestly-unknown
        // states, and - since the Phase 6 -> 7 transition, the payout's shape (P6-TSK-013) -
        // the two dispatched states and AUTHORIZED once past the sweep's own bound. The
        // server's clock decides the age - never an instance's (ADR-0014) - floored at zero.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT count(*),"
                                // floor()::bigint, never a double: the age is a
                                // whole number of seconds and EXTRACT would hand
                                // JDBC a floating-point value to round for us
                                // (INV-MON-01 is about signatures, and this is the
                                // discipline behind it - the rule caught it here).
                                + " GREATEST(0, COALESCE(floor(EXTRACT(EPOCH FROM now()"
                                + "   - min(COALESCE(h.entered, a.created_at))))::bigint, 0))"
                                + " FROM payments.payment_attempt a"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.payment_attempt_event e"
                                + "   WHERE e.attempt_id = a.id) h ON true"
                                // The push model's stuck states count too (P7-TSK-002):
                                // visibility precedes the rails that will produce them.
                                // AWAITING_PAYER is deliberately absent - waiting on the
                                // payer's PSP is not stuck by our clock (P7-TSK-009 owns
                                // its ageing).
                                + " WHERE a.status IN"
                                + "     ('AUTH_UNKNOWN', 'CAPTURE_UNKNOWN', 'EXECUTION_UNKNOWN',"
                                + "      'VOID_UNKNOWN')"
                                + "    OR (a.status IN"
                                + "          ('AUTH_DISPATCHED', 'CAPTURE_DISPATCHED',"
                                + "           'EXECUTION_DISPATCHED', 'AUTHORIZED',"
                                + "           'VOID_DISPATCHED')"
                                + "        AND COALESCE(h.entered, a.created_at)"
                                + "            <= now() - make_interval(secs => ?))")) {
            read.setLong(1, dispatchedBound.toSeconds());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new UnknownReading(row.getLong(1), row.getLong(2));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the stuck-attempt gauge", failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> findByOperationReference(
            Connection unitOfWork, ProviderIdempotencyReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // Either minted reference names the attempt: the authorization's or
                        // the capture's - both stored before anything was sent (INV-PAY-04),
                        // both carrying V003's plain UNIQUEs, so at most one row answers.
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE auth_reference = ? OR capture_reference = ?")) {
            read.setString(1, reference.value());
            read.setString(2, reference.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading an attempt by operation reference", failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> findById(Connection unitOfWork, PaymentAttemptId attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt WHERE id = ?")) {
            read.setObject(1, attempt.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading payment attempt " + attempt, failure));
        }
    }

    @Override
    public boolean dispatchCapture(
            Connection unitOfWork, PaymentAttemptId attempt,
            ProviderIdempotencyReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?, capture_reference = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.CAPTURE_DISPATCHED.name());
            update.setString(2, reference.value());
            update.setObject(3, attempt.value());
            update.setString(4, PaymentAttemptStatus.AUTHORIZED.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "dispatching the capture of attempt " + attempt, failure));
        }
    }

    @Override
    public boolean capture(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference,
            Money capturedAmount) {
        requireLegal(attempt, from, PaymentAttemptStatus.CAPTURED);
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(capturedAmount, "capturedAmount must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?,"
                                + " capture_provider_reference = ?, captured_amount_minor = ?,"
                                + " captured_currency = ?, captured_scale = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.CAPTURED.name());
            update.setString(2, providerReference.value());
            update.setLong(3, capturedAmount.minorUnits());
            update.setString(4, capturedAmount.currency().code());
            update.setShort(5, (short) capturedAmount.scale());
            update.setObject(6, attempt.value());
            update.setString(7, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("capturing attempt " + attempt, failure));
        }
    }

    @Override
    public boolean markCaptureUnknown(Connection unitOfWork, PaymentAttemptId attempt) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.CAPTURE_UNKNOWN.name());
            update.setObject(2, attempt.value());
            update.setString(3, PaymentAttemptStatus.CAPTURE_DISPATCHED.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "marking the capture of attempt " + attempt + " unknown", failure));
        }
    }

    @Override
    public boolean dispatchVoid(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderIdempotencyReference reference) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?, void_reference = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.VOID_DISPATCHED.name());
            update.setString(2, reference.value());
            update.setObject(3, attempt.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "dispatching the void of attempt " + attempt, failure));
        }
    }

    @Override
    public boolean voided(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?,"
                                + " void_provider_reference = ? WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.VOIDED.name());
            update.setString(2, providerReference.value());
            update.setObject(3, attempt.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "concluding the void of attempt " + attempt, failure));
        }
    }

    @Override
    public boolean markVoidUnknown(Connection unitOfWork, PaymentAttemptId attempt) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.VOID_UNKNOWN.name());
            update.setObject(2, attempt.value());
            update.setString(3, PaymentAttemptStatus.VOID_DISPATCHED.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "marking the void of attempt " + attempt + " unknown", failure));
        }
    }

    @Override
    public boolean authorize(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference,
            Money authorizedAmount) {
        requireLegal(attempt, from, PaymentAttemptStatus.AUTHORIZED);
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(authorizedAmount, "authorizedAmount must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?,"
                                + " auth_provider_reference = ?, authorized_amount_minor = ?,"
                                + " authorized_currency = ?, authorized_scale = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.AUTHORIZED.name());
            update.setString(2, providerReference.value());
            update.setLong(3, authorizedAmount.minorUnits());
            update.setString(4, authorizedAmount.currency().code());
            update.setShort(5, (short) authorizedAmount.scale());
            update.setObject(6, attempt.value());
            update.setString(7, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "authorizing payment attempt " + attempt, failure));
        }
    }

    @Override
    public boolean fail(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            PaymentFailureReason reason) {
        requireLegal(attempt, from, PaymentAttemptStatus.FAILED);
        Objects.requireNonNull(reason, "reason must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?, failure_reason = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.FAILED.name());
            update.setString(2, reason.name());
            update.setObject(3, attempt.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("failing payment attempt " + attempt, failure));
        }
    }

    @Override
    public boolean markAuthUnknown(Connection unitOfWork, PaymentAttemptId attempt) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.AUTH_UNKNOWN.name());
            update.setObject(2, attempt.value());
            update.setString(3, PaymentAttemptStatus.AUTH_DISPATCHED.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "marking payment attempt " + attempt + " unknown", failure));
        }
    }

    @Override
    public void recordTransition(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            PaymentAttemptStatus to,
            Actor actor,
            Instant occurredAt) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.payment_attempt_event"
                                + " (attempt_id, from_status, to_status, actor_id, actor_type,"
                                + " occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, attempt.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "recording the transition of payment attempt " + attempt, failure));
        }
    }

    // ------------------------------------------------------- the push model (P7-TSK-009)

    @Override
    public Optional<PaymentAttempt> findByEndToEndReference(
            Connection unitOfWork, EndToEndReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE end_to_end_reference = ?")) {
            read.setString(1, reference.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading an attempt by end-to-end reference", failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> findByCaptureProviderReference(
            Connection unitOfWork, ProviderReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE capture_provider_reference = ?")) {
            read.setString(1, reference.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading an attempt by capture provider reference", failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> findBySchemeReference(
            Connection unitOfWork, ProviderReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE scheme_reference = ?")) {
            read.setString(1, reference.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading an attempt by scheme reference", failure));
        }
    }

    @Override
    public boolean openInitiation(
            Connection unitOfWork, PaymentAttemptId attempt, Sensitive<String> handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET authorization_handle = ?"
                                + " WHERE id = ? AND status = ? AND authorization_handle"
                                + " IS NULL")) {
            // The bind-side expose (P7-TSK-009), registered in
            // SecretsAreUnwrappedInOnePlaceTest: the capability URL becomes bytes exactly
            // where the column takes it, and nowhere upstream.
            update.setString(1, handle.expose());
            update.setObject(2, attempt.value());
            update.setString(3, PaymentAttemptStatus.AWAITING_PAYER.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "storing the initiation handle of attempt " + attempt, failure));
        }
    }

    @Override
    public boolean execute(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference schemeReference,
            Optional<String> settlementCycle) {
        requireLegal(attempt, from, PaymentAttemptStatus.EXECUTED);
        Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?,"
                                + " scheme_reference = ?, settlement_cycle = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.EXECUTED.name());
            update.setString(2, schemeReference.value());
            update.setString(3, settlementCycle.orElse(null));
            update.setObject(4, attempt.value());
            update.setString(5, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("executing attempt " + attempt, failure));
        }
    }

    @Override
    public boolean failHandleless(
            Connection unitOfWork, PaymentAttemptId attempt, PaymentFailureReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        // The handle predicate IS the safety (ADR-0062 section 3 adapted): a
                        // row holding a handle has an initiation the payer can complete, so
                        // no unavailability conclusion may fail it - whichever instance's
                        // re-initiate raced this verdict, the row count decides.
                        "UPDATE payments.payment_attempt SET status = ?, failure_reason = ?"
                                + " WHERE id = ? AND status = ?"
                                + " AND authorization_handle IS NULL")) {
            update.setString(1, PaymentAttemptStatus.FAILED.name());
            update.setString(2, reason.name());
            update.setObject(3, attempt.value());
            update.setString(4, PaymentAttemptStatus.AWAITING_PAYER.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "failing the handleless initiation of attempt " + attempt,
                            failure));
        }
    }

    @Override
    public boolean renewInitiationPermit(
            Connection unitOfWork, PaymentAttemptId attempt, Instant expected) {
        Objects.requireNonNull(expected, "expected must not be null");
        // The database's instant, strictly forward (X-TSK-013): never this instance's clock.
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET last_dispatched_at ="
                                + " GREATEST(last_dispatched_at + interval '1 microsecond',"
                                + " statement_timestamp())"
                                + " WHERE id = ? AND status = ?"
                                + " AND last_dispatched_at <= ?")) {
            update.setObject(1, attempt.value());
            update.setString(2, PaymentAttemptStatus.AWAITING_PAYER.name());
            update.setTimestamp(3, Timestamp.from(expected));
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "renewing the initiation permit of attempt " + attempt, failure));
        }
    }

    @Override
    public List<PaymentAttempt> findResolvableInitiations(
            Connection unitOfWork, Instant contactedBefore, int limit) {
        Objects.requireNonNull(contactedBefore, "contactedBefore must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE interaction_model = 'PUSH'"
                                + " AND status = 'AWAITING_PAYER'"
                                + " AND last_dispatched_at <= ?"
                                + " ORDER BY last_dispatched_at, id"
                                + " LIMIT ?")) {
            read.setTimestamp(1, Timestamp.from(contactedBefore));
            read.setInt(2, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<PaymentAttempt> resolvable = new ArrayList<>();
                while (rows.next()) {
                    resolvable.add(rehydrate(rows));
                }
                return List.copyOf(resolvable);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading resolvable initiations", failure));
        }
    }

    @Override
    public UnknownReading awaitingReading(Connection unitOfWork) {
        // The unknownReading discipline on the pay-in's own gauge (P7-TSK-009): age from
        // BIRTH, not the permit - the payer has been deciding since the initiation opened,
        // and a permit renewal must not make an old wait look young. Floor to a whole
        // second the same way, the server's clock only.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT count(*),"
                                + " GREATEST(0, COALESCE(floor(EXTRACT(EPOCH FROM now()"
                                + "   - min(created_at)))::bigint, 0))"
                                + " FROM payments.payment_attempt"
                                + " WHERE interaction_model = 'PUSH'"
                                + " AND status = 'AWAITING_PAYER'")) {
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new UnknownReading(row.getLong(1), row.getLong(2));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the awaiting-payer gauge", failure));
        }
    }

    /** The machine's legality in the writer too — an illegal ask is a caller defect, loud. */
    private static void requireLegal(
            PaymentAttemptId attempt, PaymentAttemptStatus from, PaymentAttemptStatus to) {
        // Exact although the row is not in hand (P7-TSK-002): non-terminal states are
        // model-exclusive, so an edge belongs to at most one machine -
        // InteractionModelMachinesTest pins the disjointness this rests on. The trigger is
        // the rank beneath, holding the row's own model against every writer.
        if (!InteractionModel.anyPermits(from, to)) {
            throw new IllegalPaymentAttemptTransitionException(attempt, from, to);
        }
    }

    private static void setMoney(PreparedStatement statement, int firstIndex, Money amount)
            throws SQLException {
        if (amount == null) {
            statement.setObject(firstIndex, null);
            statement.setString(firstIndex + 1, null);
            statement.setObject(firstIndex + 2, null);
        } else {
            statement.setLong(firstIndex, amount.minorUnits());
            statement.setString(firstIndex + 1, amount.currency().code());
            statement.setShort(firstIndex + 2, (short) amount.scale());
        }
    }

    @Override
    public List<PaymentAttempt> pageByStatus(
            Connection unitOfWork,
            PaymentAttemptStatus status,
            java.util.UUID after,
            int limit) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE status = ? AND id > ? ORDER BY id LIMIT ?")) {
            select.setString(1, status.name());
            select.setObject(2, after);
            select.setInt(3, limit);
            try (ResultSet rows = select.executeQuery()) {
                List<PaymentAttempt> page = new ArrayList<>();
                while (rows.next()) {
                    page.add(rehydrate(rows));
                }
                return List.copyOf(page);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("paging attempts by status", failure));
        }
    }

    private static PaymentAttempt rehydrate(ResultSet row) throws SQLException {
        String authReference = row.getString("auth_reference");
        String captureReference = row.getString("capture_reference");
        String authProviderReference = row.getString("auth_provider_reference");
        String captureProviderReference = row.getString("capture_provider_reference");
        String voidReference = row.getString("void_reference");
        String voidProviderReference = row.getString("void_provider_reference");
        String reason = row.getString("failure_reason");
        String endToEnd = row.getString("end_to_end_reference");
        String handle = row.getString("authorization_handle");
        String schemeReference = row.getString("scheme_reference");
        Timestamp lastDispatched = row.getTimestamp("last_dispatched_at");
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.of(row.getObject("id", UUID.class)),
                PaymentIntentId.of(row.getObject("intent_id", UUID.class)),
                RailId.of(row.getString("rail")),
                InteractionModel.valueOf(row.getString("interaction_model")),
                authReference == null ? null : new ProviderIdempotencyReference(authReference),
                captureReference == null
                        ? null
                        : new ProviderIdempotencyReference(captureReference),
                authProviderReference == null
                        ? null
                        : new ProviderReference(authProviderReference),
                readMoney(row, "authorized_amount_minor", "authorized_currency",
                        "authorized_scale"),
                captureProviderReference == null
                        ? null
                        : new ProviderReference(captureProviderReference),
                readMoney(row, "captured_amount_minor", "captured_currency", "captured_scale"),
                voidReference == null
                        ? null
                        : new ProviderIdempotencyReference(voidReference),
                voidProviderReference == null
                        ? null
                        : new ProviderReference(voidProviderReference),
                reason == null ? null : PaymentFailureReason.valueOf(reason),
                PaymentAttemptStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                endToEnd == null ? null : new EndToEndReference(endToEnd),
                handle == null ? null : Sensitive.of(handle),
                schemeReference == null ? null : new ProviderReference(schemeReference),
                row.getString("settlement_cycle"),
                lastDispatched == null ? null : lastDispatched.toInstant());
    }

    private static Money readMoney(
            ResultSet row, String minorColumn, String currencyColumn, String scaleColumn)
            throws SQLException {
        long minor = row.getLong(minorColumn);
        if (row.wasNull()) {
            return null;
        }
        // The STORED scale, never re-derived (INV-MON-05).
        return Money.ofPersisted(
                minor, CurrencyCode.of(row.getString(currencyColumn)),
                row.getShort(scaleColumn));
    }
}
