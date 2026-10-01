package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A parking's owner (`P8-TSK-020`, ADR-0070 §2's {@code UNMATCHED_CONFIRMATION} row and §8): the
 * value payments' own entry already put into {@code SUSPENSE_UNMATCHED} gets its CREDIT suspense
 * item and the break that owns it, in the caller's transaction ({@code INV-REC-09}). It posts
 * nothing — the parking's entry is the value's only posting — and releases nothing: the item
 * leaves only by a person's resolution ({@code INV-REC-05}).
 *
 * <h2>Born together — the one deferred key</h2>
 *
 * <p>The owner's subject IS the item (ADR-0069 §2), and the item names its owner, so neither row
 * can be inserted first under immediate keys. Reconciliation `V011` made
 * {@code break_suspense_item_fk} {@code DEFERRABLE INITIALLY IMMEDIATE}; this opener alone defers
 * it, inserts the break, then the item, then sets it {@code IMMEDIATE} again — which checks the
 * pending key at once, so a defect surfaces here, never at a distant commit. Every other writer
 * keeps immediate checking.
 *
 * <h2>One owner per parking, under any race</h2>
 *
 * <p>{@code UNIQUE (origin_ref)} — the parking's id — is the arbiter. A standing item converges
 * before anything is written; otherwise the break and the item are written inside a savepoint,
 * and an inserter that loses the unique to a concurrent one rolls back to it, discarding its
 * break, the break's event, audit record and announcement with it, and converges on the winner's.
 * The opener takes no lock on any committed row and no advisory: a pay-in delivery never waits on
 * a matcher, and waits only on the unique of a concurrent inserter of the same parking.
 */
@RequiredArgsConstructor
public final class ParkedConfirmations {

    private static final String DEFERRABLE_SUBJECT = "reconciliation.break_suspense_item_fk";

    @NonNull private final BreakRegister breaks;
    @NonNull private final IdGenerator ids;
    @NonNull private final ReconciliationTelemetry telemetry;

    /** The opener counting nothing - the module's tests and any caller without meters. */
    public ParkedConfirmations(BreakRegister breaks, IdGenerator ids) {
        this(breaks, ids, ReconciliationTelemetry.NONE);
    }

    /**
     * One parking's value, as its entry holds it: side, amount and date read off the entry's
     * suspense line by the caller, never re-derived here.
     *
     * @param parkingId the parking row — the item's {@code origin_ref}
     * @param openedOn the parking entry's posting date: an adopted parking is exactly as old as
     *     its entry, never as young as its adoption (ADR-0070 §2)
     * @param cause {@link BreakCause#PARKED_ON_RECEIPT}, or
     *     {@link BreakCause#EXECUTION_ALREADY_EXPLAINED} for a parking whose scheme execution a
     *     credit already explains
     * @param internalState the parking's stored cause, frozen on the break
     */
    public record Opening(
            UUID parkingId,
            UUID sourceId,
            UUID ruleSetId,
            SuspenseSide side,
            Money amount,
            LocalDate openedOn,
            UUID entryId,
            BreakCause cause,
            InternalClassification classification,
            Optional<String> internalOperationRef,
            String internalState,
            Actor actor,
            Instant at,
            CorrelationId correlation) {

        public Opening {
            Objects.requireNonNull(parkingId, "parkingId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(openedOn, "openedOn must not be null");
            Objects.requireNonNull(entryId, "entryId must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(classification, "classification must not be null");
            Objects.requireNonNull(internalOperationRef, "internalOperationRef must not be null");
            Objects.requireNonNull(internalState, "internalState must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (cause != BreakCause.PARKED_ON_RECEIPT
                    && cause != BreakCause.EXECUTION_ALREADY_EXPLAINED) {
                throw new IllegalArgumentException(
                        "a parking's owner is raised on receipt or as an explained duplicate: "
                                + cause);
            }
            if (amount.minorUnits() <= 0) {
                throw new IllegalArgumentException("a parking holds a positive value");
            }
        }
    }

    /** {@code created}: this call opened them; otherwise another opener's stand. */
    public record Opened(boolean created, UUID suspenseItemId, UUID breakId) {}

    /** Opens the parking's item and owner, or converges on the ones standing. */
    public Opened open(Connection unitOfWork, Opening opening) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(opening, "opening must not be null");
        Optional<Opened> standing = standing(unitOfWork, opening.parkingId());
        if (standing.isPresent()) {
            return standing.get();
        }
        try {
            Savepoint beforeOwner = unitOfWork.setSavepoint("parked_confirmation_owner");
            // The losing racer's raise is undone below with its rows - and its count with it.
            int countMark = telemetry.countMark();
            UUID itemId = ids.next();
            UUID breakId = ids.next();
            constraint(unitOfWork, "DEFERRED");
            BreakRegister.Raised raised =
                    breaks.raise(
                            unitOfWork,
                            new BreakRegister.NewBreak(
                                    breakId,
                                    opening.cause().raisesAs().iterator().next(),
                                    opening.cause(),
                                    BreakRegister.Subject.suspenseItem(itemId),
                                    opening.sourceId(),
                                    opening.ruleSetId(),
                                    opening.amount(),
                                    Optional.of(
                                            opening.side() == SuspenseSide.CREDIT
                                                    ? ExpectationDirection.INBOUND
                                                    : ExpectationDirection.OUTBOUND),
                                    Optional.of(ExpectationKind.UNMATCHED_CONFIRMATION),
                                    Optional.of(opening.classification()),
                                    opening.internalOperationRef(),
                                    Optional.of(opening.internalState()),
                                    Optional.empty(),
                                    opening.actor(),
                                    opening.at(),
                                    opening.correlation()));
            if (!raised.created()) {
                throw new IllegalStateException(
                        "a freshly minted subject has no standing owner: break " + breakId);
            }
            if (!insertItem(unitOfWork, itemId, breakId, opening)) {
                // Another opener won the parking: its break, event and audit stand, ours never
                // existed.
                unitOfWork.rollback(beforeOwner);
                telemetry.discardCountsAfter(countMark);
                constraint(unitOfWork, "IMMEDIATE");
                return standing(unitOfWork, opening.parkingId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "the origin unique refused an item no reader"
                                                        + " sees"));
            }
            // The deferred subject key is checked NOW - the owner and its item stand together.
            constraint(unitOfWork, "IMMEDIATE");
            unitOfWork.releaseSavepoint(beforeOwner);
            return new Opened(true, itemId, breakId);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not open the parking's suspense item and its owner", failure);
        }
    }

    private static void constraint(Connection unitOfWork, String mode) throws SQLException {
        try (Statement set = unitOfWork.createStatement()) {
            set.execute("SET CONSTRAINTS " + DEFERRABLE_SUBJECT + " " + mode);
        }
    }

    private static boolean insertItem(
            Connection unitOfWork, UUID itemId, UUID breakId, Opening opening)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.suspense_item (id, break_id,"
                                + " external_item_id, origin, origin_ref, side, amount_minor,"
                                + " currency, scale, released_minor, status, opened_on, entry_id,"
                                + " park_id, position_account_id, status_changed_at,"
                                + " correlation_id)"
                                + " VALUES (?, ?, NULL, 'UNMATCHED_CONFIRMATION', ?, ?, ?, ?, ?,"
                                + " 0, 'OPEN', ?, ?, NULL, NULL, ?, ?)"
                                + " ON CONFLICT ON CONSTRAINT suspense_item_origin_once"
                                + " DO NOTHING")) {
            insert.setObject(1, itemId);
            insert.setObject(2, breakId);
            insert.setString(3, opening.parkingId().toString());
            insert.setString(4, opening.side().name());
            insert.setLong(5, opening.amount().minorUnits());
            insert.setString(6, opening.amount().currency().code());
            insert.setInt(7, opening.amount().scale());
            insert.setObject(8, opening.openedOn());
            insert.setObject(9, opening.entryId());
            insert.setTimestamp(10, Timestamp.from(opening.at()));
            insert.setString(11, opening.correlation().value());
            return insert.executeUpdate() == 1;
        }
    }

    private static Optional<Opened> standing(Connection unitOfWork, UUID parkingId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, break_id FROM reconciliation.suspense_item"
                                + " WHERE origin_ref = ?")) {
            read.setString(1, parkingId.toString());
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(
                                new Opened(
                                        false,
                                        row.getObject("id", UUID.class),
                                        row.getObject("break_id", UUID.class)))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the parking's standing suspense item", failure);
        }
    }
}
