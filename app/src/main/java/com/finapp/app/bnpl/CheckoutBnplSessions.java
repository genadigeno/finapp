package com.finapp.app.bnpl;

import com.finapp.app.checkout.CheckoutSessions;
import com.finapp.app.checkout.UnknownCheckoutSessionException;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.checkout.FinancingRefusal;
import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * bnpl's {@link BnplCheckoutSessions} over checkout's {@link CheckoutSessions} (`P12-TSK-007`; ADR-0101 decision 4, the
 * crossborder and lending port precedent): the session read by its token, locked and judged at B3, and completed by
 * financing - every call on bnpl's own connection, in bnpl's transaction, with checkout's own aggregate, store and
 * order as the judges. bnpl learns {@link BnplCheckoutSessions.Session} and no checkout type.
 *
 * <p>The token stays wrapped from bnpl's request body to checkout's digest
 * ({@link CheckoutSessionToken#presented(Sensitive)}), so this class unwraps nothing. A session bnpl's own record names
 * and checkout cannot find is a broken database, never a refusal: it propagates and fails bnpl's transaction.
 * {@code CheckoutBnplSessionsTest} holds the order lock-then-clock and the mapping; {@code CheckoutFinancingDatabaseTest}
 * the races against a real schema.
 */
@RequiredArgsConstructor
final class CheckoutBnplSessions implements BnplCheckoutSessions {

    @NonNull private final CheckoutSessions sessions;

    @Override
    public Optional<Session> read(Connection unitOfWork, Sensitive<String> sessionToken) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sessionToken, "sessionToken must not be null");
        return sessions.findByToken(unitOfWork, CheckoutSessionToken.presented(sessionToken))
                // The deadline on the DATABASE clock, read after the row (the B3 rule, unlocked).
                .map(session -> viewOf(session, sessions.databaseNow(unitOfWork)));
    }

    @Override
    public Optional<Session> byRef(Connection unitOfWork, UUID sessionRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sessionRef, "sessionRef must not be null");
        return sessions.ownedBySession(unitOfWork, CheckoutSessionId.of(sessionRef))
                // The deadline on the DATABASE clock, read after the row (the B3 rule, unlocked) - the sweep's reading.
                .map(session -> viewOf(session, sessions.databaseNow(unitOfWork)));
    }

    @Override
    public Lock lockForPurchase(Connection unitOfWork, UUID sessionRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sessionRef, "sessionRef must not be null");
        CheckoutSessions.FinancingJudgement judged;
        try {
            judged = sessions.lockForFinancing(unitOfWork, CheckoutSessionId.of(sessionRef));
        } catch (UnknownCheckoutSessionException absent) {
            throw brokenReference();
        }
        return judged.refusal()
                .<Lock>map(refusal -> new NotPayable(reasonOf(refusal)))
                // Payable means unexpired on the database clock read under the lock.
                .orElseGet(() -> new Payable(viewOf(judged.session(), false)));
    }

    @Override
    public Completion completeByFinancing(
            Connection unitOfWork,
            UUID sessionRef,
            BnplPurchaseId purchase,
            JournalEntryId financingEntry,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sessionRef, "sessionRef must not be null");
        Objects.requireNonNull(purchase, "purchase must not be null");
        Objects.requireNonNull(financingEntry, "financingEntry must not be null");
        CheckoutSessions.FinancingCompletion completed;
        try {
            completed =
                    sessions.completedByFinancing(
                            unitOfWork,
                            CheckoutSessionId.of(sessionRef),
                            purchase.value(),
                            financingEntry.value(),
                            correlation);
        } catch (UnknownCheckoutSessionException absent) {
            throw brokenReference();
        }
        return switch (completed) {
            case CheckoutSessions.FinancingCompletion.Financed financed ->
                    new Completed(financed.order().id().value());
            case CheckoutSessions.FinancingCompletion.Refused refused ->
                    new NotPayable(reasonOf(refused.reason()));
        };
    }

    private static Session viewOf(CheckoutSession session, java.time.Instant databaseNow) {
        return viewOf(session, !databaseNow.isBefore(session.expiresAt()));
    }

    private static Session viewOf(CheckoutSession session, boolean expired) {
        return new Session(
                session.id().value(),
                session.merchantRef(),
                session.amount(),
                session.expiresAt(),
                session.status() == CheckoutSessionStatus.OPEN,
                expired);
    }

    private static Reason reasonOf(FinancingRefusal refusal) {
        return switch (refusal) {
            case NOT_OPEN -> Reason.NOT_OPEN;
            case EXPIRED -> Reason.EXPIRED;
        };
    }

    private static IllegalStateException brokenReference() {
        return new IllegalStateException(
                "a checkout session bnpl's own record names does not exist - a broken database, never a refusal");
    }
}
