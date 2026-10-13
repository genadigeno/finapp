package com.finapp.lending;

import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A credit line's closure request - T-clo (`P11-TSK-023`; {@code PHASE_11_PLAN.md} sections 5.3, 7.1, 7.2 and 12.6,
 * {@code LENDING_LIFECYCLES.md} section 4.5; ADR-0100 point 8; {@code INV-LIFE-04}, {@code INV-LND-08},
 * {@code INV-IDEM-01}). The customer asks to close their line, in <strong>one transaction</strong> on the caller's unit of
 * work, which the door's idempotency claim ({@code lending.closure:CUSTOMER:<id>}, {@code MULTI_FACTOR}) wraps: a retried
 * key replays, and every refusal is thrown before any write.
 *
 * <h2>The order, which is the lock order (L3, L7, L8)</h2>
 *
 * <ol>
 *   <li><strong>(L3)</strong> the line {@code FOR UPDATE}, owner-scoped - the serialisation point of every act on the
 *       account: a closure request and a draw (`-021`) racing each wait here, so exactly one order holds - the draw before
 *       {@code CLOSING} (admitted, then counted in what is outstanding) or after it (refused, {@code LineNotDrawable});
 *   <li>a request already born for the line ({@code UNIQUE (loan_id)}, the arbiter behind a lapsed claim and a second key):
 *       under the same key it replays; under another, a {@code CLOSING} line answers that same request (ten requests, one
 *       request) and a {@code CLOSED} one is {@code LoanClosed};
 *   <li>a credit line ({@code LineNotClosable} for an instalment loan - it closes only by being repaid) {@code ACTIVE}
 *       ({@code LoanClosed} once closed);
 *   <li>the database clock, read after the lock, and its business date in the pinned zone;
 *   <li><strong>settled?</strong> ({@link LoanClosures#settlement}): every receivable zero and every elapsed date accrued -
 *       and, when the credit balance is positive, <strong>(L7)</strong> the wallet's ledger row locked and {@code ACTIVE};
 *   <li>settled: the request born {@code CLOSED}, then the closure - the refund (L8, {@code lending.closure-refund:<loan>}),
 *       {@code loan_closure}, {@code ACTIVE -> CLOSED (CLOSED_BY_CUSTOMER)}, {@code lending.LoanClosed}; otherwise the
 *       request born {@code CLOSING} and {@code ACTIVE -> CLOSING} - the line takes no further draw, is still serviced
 *       (accrued, stated, collected) and closes in the transaction that zeroes its last receivable;
 *   <li>its history row and {@code lending.LineClosureRequested}.
 * </ol>
 *
 * <p><strong>No L0</strong>: a closing line's exposure falls - {@code PlatformCreditExposure} counts an {@code ACTIVE}
 * line's {@code GREATEST(limit, drawn)} but a {@code CLOSING} line's drawn principal (`-013`) - and a falling figure never
 * needs the profile lock (a deciding transaction meanwhile reads the higher, safe one; the census asserts the lower).
 * <strong>No L6</strong>: capital is consumed by drawn principal only (A28), which the request does not move. <strong>No
 * standing check</strong>: a suspended customer may still close. <strong>No audit record</strong>: a customer's own act is
 * its row and its history.
 */
@RequiredArgsConstructor
public final class LineClosures {

    /** The event every closure request publishes - the outcome beside it. */
    public static final String REQUESTED_EVENT = "lending.LineClosureRequested";

    /** The history row's reason for {@code ACTIVE -> CLOSING}. */
    static final String CLOSURE_REQUESTED = "CLOSURE_REQUESTED";

    /** The longest key a customer may request under: the door's own bound, which {@code lending V018} repeats. */
    public static final int MAX_REQUEST_KEY_LENGTH = IdempotencyKey.MAX_LENGTH;

    @NonNull private final LoanStore loans;
    @NonNull private final LineClosureStore requests;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanClosures closures;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * What the request committed - or, {@code replayed}, the request this one converged on (the same key after a lapsed
     * claim, or another key on a line already {@code CLOSING}) - and the closure it brought, when it closed the line.
     */
    public record Requested(LineClosureRequest request, Optional<LoanClosure> closure, boolean replayed) {

        public Requested {
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(closure, "closure must not be null");
        }
    }

    /**
     * Requests the closure of {@code party}'s line {@code id}, on the caller's unit of work, inside the caller's correlation
     * and actor scopes (a refund's posting carries both).
     *
     * @throws LineNotFound the party has no account with this id (another party's included)
     * @throws LineNotClosable the account is an instalment loan
     * @throws LoanClosed the line is already closed
     */
    public Requested request(Connection unitOfWork, LoanId id, UUID party, String requestKey, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        if (requestKey.isEmpty() || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
            throw new IllegalArgumentException("a closure key is 1 to " + MAX_REQUEST_KEY_LENGTH + " characters");
        }

        // (L3) The line, owner-scoped: another party's account is never locked, and is the same answer as an absent one.
        LoanStore.Owned locked = loans.lockOwnedBy(unitOfWork, id, party).orElseThrow(LineNotFound::new);
        Loan loan = locked.loan();
        LoanAgreement agreement = locked.agreement();
        // A request already born for the line: the arbiter behind a lapsed claim, and behind a second key.
        Optional<LineClosureRequest> born = requests.findByLoan(unitOfWork, id);
        if (born.isPresent()) {
            if (born.get().requestKey().equals(requestKey)) {
                return new Requested(born.get(), Optional.empty(), true);
            }
            if (loan.status() == LoanStatus.CLOSED) {
                throw new LoanClosed();
            }
            return new Requested(born.get(), Optional.empty(), true);
        }
        if (loan.kind() != LoanKind.REVOLVING) {
            throw new LineNotClosable();
        }
        if (loan.status() != LoanStatus.ACTIVE) {
            // A line leaves ACTIVE only beside its request (lending V018's edge function), so a line without one that is
            // not ACTIVE is closed by another path (P11-TSK-026's payoff births the request it closes beside, so in practice
            // a CLOSED line always has its request - answered LoanClosed above).
            throw new LoanClosed();
        }
        // The database clock, read after the lock, in the agreement's pinned zone.
        Instant now = requests.databaseNow(unitOfWork);
        ZoneId zone = ZoneId.of(terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms().servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);

        // Settled now (every receivable zero, every elapsed date accrued, the refund creditable under L7)? Closed at the
        // request. Otherwise CLOSING: no further draws, closed by the transaction that zeroes the last receivable.
        Optional<LoanClosures.Settlement> settled = closures.settlement(unitOfWork, loan, agreement, zone, today);
        LineClosureOutcome outcome = settled.isPresent() ? LineClosureOutcome.CLOSED : LineClosureOutcome.CLOSING;
        LineClosureRequest unstamped = new LineClosureRequest(ids.next(), id, requestKey, outcome, now);
        Instant requestedAt = requests.insert(unitOfWork, unstamped, correlation);
        LineClosureRequest request = new LineClosureRequest(unstamped.id(), id, requestKey, outcome, requestedAt);
        Optional<LoanClosure> closure = Optional.empty();
        if (settled.isPresent()) {
            closure = Optional.of(closures.close(unitOfWork, loan, settled.get(), ClosureReason.CLOSED_BY_CUSTOMER, today,
                    Optional.empty(), () -> actor, correlation));
        } else {
            if (!loans.beginClosing(unitOfWork, id)) {
                throw new IllegalStateException("the locked ACTIVE line did not move to CLOSING under its own row lock");
            }
            loans.appendEvent(unitOfWork, new LoanStore.HistoryRow(ids.next(), id, Optional.of(LoanStatus.ACTIVE),
                    LoanStatus.CLOSING, CLOSURE_REQUESTED, actor, correlation));
        }
        publish(unitOfWork, loan, request, correlation);
        return new Requested(request, closure, false);
    }

    /**
     * A line's payoff is its customer's request to end it (`P11-TSK-026`, ADR-0100 point 8): on an {@code ACTIVE} line the
     * payoff births the line's one closure request, outcome {@code CLOSED}, and publishes {@code lending.LineClosureRequested}
     * - so the payoff closes the line {@code CLOSED_BY_CUSTOMER} through the closure's own edges in the same transaction; a
     * {@code CLOSING} line's request already stands. On the caller's unit of work, under the caller's L3.
     *
     * @param requestKey the payoff's key for the request - its quote's
     */
    void requestForPayoff(Connection unitOfWork, Loan line, String requestKey, CorrelationId correlation) {
        Objects.requireNonNull(line, "line");
        if (line.kind() != LoanKind.REVOLVING || line.status() != LoanStatus.ACTIVE) {
            return;
        }
        LineClosureRequest unstamped =
                new LineClosureRequest(ids.next(), line.id(), requestKey, LineClosureOutcome.CLOSED, requests.databaseNow(
                        unitOfWork));
        Instant requestedAt = requests.insert(unitOfWork, unstamped, correlation);
        publish(unitOfWork, line, new LineClosureRequest(unstamped.id(), line.id(), requestKey, LineClosureOutcome.CLOSED,
                requestedAt), correlation);
    }

    /** {@code lending.LineClosureRequested} - the line, the request and its outcome; never the key. */
    private void publish(Connection unitOfWork, Loan loan, LineClosureRequest request, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("requestId", request.id().toString())
                .with("outcome", request.outcome().name());
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        REQUESTED_EVENT,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        loan.id(),
                        LoanAcceptances.LOAN_AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The party has no account with this id - another party's included. */
    public static final class LineNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LineNotFound() {
            super("no such loan account");
        }
    }

    /** The account is an instalment loan: it closes only by being repaid. */
    public static final class LineNotClosable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LineNotClosable() {
            super("the account is not a credit line");
        }
    }

    /** The line is already closed. */
    public static final class LoanClosed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LoanClosed() {
            super("the line is closed");
        }
    }
}
