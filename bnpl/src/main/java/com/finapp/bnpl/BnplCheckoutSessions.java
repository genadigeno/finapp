package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The checkout session, as bnpl reads, locks and completes it - a port {@code app} implements over checkout's
 * {@code CheckoutSessions} (`P12-TSK-007`; ADR-0101 decision 4, PHASE_12_PLAN.md sections 3.3, 5.2a, 7.1 T-pur and 7.2
 * B3, BNPL_LIFECYCLES.md section 4.4; {@code INV-BNPL-03}, {@code INV-MER-06}).
 *
 * <p><strong>The session stays checkout's.</strong> bnpl has no build edge to {@code checkout}
 * ({@code BnplModuleIsolationTest}); it learns a session through {@link Session} - bnpl's own record, the merchant and
 * the session by value (ADR-0029, the {@code UUID merchantRef} agreed with {@code BnplMerchants}) - and asks checkout to
 * complete it. No state of its own; code on every instance.
 *
 * <p><strong>B3, judged at once.</strong> {@link #lockForPurchase} takes the session row {@code FOR UPDATE} on the
 * caller's own connection and judges it there - {@code OPEN}, and its deadline not passed on the database clock read
 * after the lock - answering {@link NotPayable} with nothing written and nothing else locked, so T-pur refuses before
 * it takes (P) (PHASE_12_PLAN.md section 7.3 (c): a capture completes only a {@code PAYMENT_PENDING} session, which a
 * financing never completes).
 *
 * <p><strong>The completion re-judges.</strong> {@link #completeByFinancing} moves the session {@code OPEN → COMPLETED}
 * by financing - intent-less, the purchase recorded as its financing reference, set once - and births checkout's order
 * with the financing entry as its {@code captured_entry_ref}, the order's event and audit record, all in the caller's
 * transaction. The deadline is judged again in the write itself, so a purchase whose own work crossed the deadline is
 * {@link NotPayable} too, and the caller rolls back whole.
 */
public interface BnplCheckoutSessions {

    /**
     * A checkout session as bnpl sees it.
     *
     * @param sessionRef the session's identifier, by value
     * @param merchantRef the merchant the session pays, by value (merchant's id)
     * @param gross what the customer is buying for - the purchase's gross G
     * @param expiresAt the offer's deadline; a BNPL offer never outlives it (A17)
     * @param open whether checkout's machine still has the session {@code OPEN}
     * @param expired whether its deadline had passed <strong>on the database clock</strong> when the session was read -
     *     never an instance's clock (PHASE_12_PLAN.md section 7); a reading for the application door and the sweep,
     *     which only B3's judgement under the lock makes binding
     */
    record Session(UUID sessionRef, UUID merchantRef, Money gross, Instant expiresAt, boolean open, boolean expired) {

        public Session {
            Objects.requireNonNull(sessionRef, "sessionRef must not be null");
            Objects.requireNonNull(merchantRef, "merchantRef must not be null");
            Objects.requireNonNull(gross, "gross must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }

        /** Identifiers and the standing only - never the gross ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Session[" + sessionRef + ", merchant=" + merchantRef + ", open=" + open + ", expired=" + expired
                    + "]";
        }
    }

    /** What B3's judgement answers. */
    sealed interface Lock permits Payable, NotPayable {}

    /** What the completion answers. */
    sealed interface Completion permits Completed, NotPayable {}

    /** The session is locked by the caller's transaction, {@code OPEN} and before its deadline. */
    record Payable(Session session) implements Lock {

        public Payable {
            Objects.requireNonNull(session, "session must not be null");
        }
    }

    /** The session was completed by financing; its order is {@code orderRef}. */
    record Completed(UUID orderRef) implements Completion {

        public Completed {
            Objects.requireNonNull(orderRef, "orderRef must not be null");
        }
    }

    /** Refused with nothing written - T-pur's {@code 409 bnpl.SessionNotPayable} (`P12-TSK-013`). */
    record NotPayable(Reason reason) implements Lock, Completion {

        public NotPayable {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** Why a session is not payable by a BNPL purchase. */
    enum Reason {
        /** Checkout's machine has moved it: a card or wallet confirmation won, or it completed, expired or ended. */
        NOT_OPEN,
        /** Still {@code OPEN} by its row, but at or past its deadline on the database clock. */
        EXPIRED
    }

    /**
     * The session a customer's checkout token opens, or empty - unlocked, for the application door and the offer sweep
     * (`-010`, `-011`), its deadline judged on the database clock read after the row. The token travels wrapped and is never logged, stored or echoed ({@code ConfirmSessionRequest}'s
     * rule); unknown, malformed and another's are one empty answer.
     */
    Optional<Session> read(Connection unitOfWork, Sensitive<String> sessionToken);

    /**
     * B3: the session row {@code FOR UPDATE} on {@code unitOfWork}, judged at once on the database clock read after the
     * lock.
     *
     * @throws IllegalStateException no session has {@code sessionRef} - bnpl's own record named it, so its absence is a
     *     broken database, never a refusal
     */
    Lock lockForPurchase(Connection unitOfWork, UUID sessionRef);

    /**
     * The session completed by financing and checkout's order born, in the caller's transaction (T-pur, after the
     * financing entry posted). {@link NotPayable} writes nothing; the caller rolls back.
     *
     * @param purchase the BNPL purchase financing the sale - recorded as the session's financing reference, set once
     * @param financingEntry the financing journal entry (F1) - the order's {@code captured_entry_ref}
     */
    Completion completeByFinancing(
            Connection unitOfWork,
            UUID sessionRef,
            BnplPurchaseId purchase,
            JournalEntryId financingEntry,
            Correlation correlation);
}
