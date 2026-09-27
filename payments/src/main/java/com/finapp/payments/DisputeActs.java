package com.finapp.payments;

import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import java.sql.Connection;
import java.time.Instant;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one way an act on a dispute begins (`P7-TSK-014`) — shared by the evidence upload and the
 * response dispatch, so both find, lock and judge a dispute identically.
 *
 * <h2>The attempt first, then the dispute — the order every delivery keeps</h2>
 *
 * <p>A dispute delivery locks the contested attempt {@code FOR UPDATE} and then the dispute row
 * (`P7-TSK-012`/`-013`); an act that locked the dispute first and then touched the attempt would
 * meet a delivery holding the reverse and deadlock — the `P7-TSK-011` class. So the act finds the
 * dispute (tenant-scoped for a counterparty, the predicate in the statement), locks its attempt,
 * then locks the dispute row — {@code FOR UPDATE OF} the dispute only — and judges everything
 * under both. Every rule a response or upload obeys is therefore the dispute's state at the
 * moment the act commits.
 */
@RequiredArgsConstructor
final class DisputeActs {

    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;

    /**
     * The dispute {@code actor} may act on, locked in the attempt-then-dispute order; an
     * operator's act additionally reaches only a payment whose credited account its policy names.
     *
     * @throws UnknownDisputeException no dispute visible to the actor has the id
     * @throws DisputeResponseRefusedException {@code COUNTERPARTY_ANSWERS} — an operator on a
     *     payment its policy does not reach
     */
    DisputeStore.Found lockForAct(Connection unitOfWork, DisputeActor actor, DisputeId id) {
        DisputeStore.Found loose = find(unitOfWork, actor, id);
        attempts.lockById(unitOfWork, loose.dispute().attemptId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a dispute's attempt exists: V020's foreign key holds it"));
        DisputeStore.Found locked =
                switch (actor) {
                    case DisputeActor.Counterparty counterparty ->
                            disputes.lockForCounterparties(unitOfWork, id, counterparty.accounts())
                                    .orElseThrow(UnknownDisputeException::new);
                    case DisputeActor.Operator operator -> {
                        DisputeStore.Found found =
                                disputes.lockForResponder(unitOfWork, id)
                                        .orElseThrow(UnknownDisputeException::new);
                        if (!operator.actsFor().contains(creditedPurpose(unitOfWork, found))) {
                            throw new DisputeResponseRefusedException(
                                    DisputeResponseRefusedException.Refusal.COUNTERPARTY_ANSWERS);
                        }
                        yield found;
                    }
                };
        return locked;
    }

    /**
     * The dispute as a read path finds it — tenant-scoped for a counterparty, across tenants for
     * an operator — without a lock: the reads' shape, and the act's first look.
     */
    DisputeStore.Found find(Connection unitOfWork, DisputeActor actor, DisputeId id) {
        java.util.Optional<DisputeStore.Found> found =
                switch (actor) {
                    case DisputeActor.Counterparty counterparty ->
                            counterparty.accounts().isEmpty()
                                    ? java.util.Optional.empty()
                                    : disputes.findForCounterparties(
                                            unitOfWork, id, counterparty.accounts());
                    case DisputeActor.Operator operator -> disputes.findById(unitOfWork, id);
                };
        return found.orElseThrow(UnknownDisputeException::new);
    }

    /**
     * The guards every act obeys, in the order their answers are most useful: the stage (only a
     * {@code CHARGED_BACK} dispute takes an answer — an inquiry has nothing to contest, a
     * represented or resolved one takes none, {@code INV-LIFE-04}), then an answer already
     * standing (the evidence set froze with it), then the network's deadline — the platform's
     * clock refusing only its OWN dispatch, never deciding an outcome (ADR-0061 §7).
     */
    static void requireRespondable(
            Dispute dispute, boolean answered, Instant now) {
        if (dispute.stage() != DisputeStage.CHARGED_BACK) {
            throw new DisputeResponseRefusedException(
                    DisputeResponseRefusedException.Refusal.NOT_RESPONDABLE);
        }
        if (answered) {
            throw new DisputeResponseRefusedException(
                    DisputeResponseRefusedException.Refusal.ALREADY_ANSWERED);
        }
        if (dispute.respondBy().isPresent() && now.isAfter(dispute.respondBy().get())) {
            throw new DisputeResponseRefusedException(
                    DisputeResponseRefusedException.Refusal.DEADLINE_PASSED);
        }
    }

    /** An operator's act carries its reason ({@code INV-AUD-03}) — the boundary validates it too. */
    static java.util.Optional<String> reasonOf(DisputeActor actor) {
        return switch (actor) {
            case DisputeActor.Counterparty counterparty -> java.util.Optional.empty();
            case DisputeActor.Operator operator -> {
                if (operator.reason().isEmpty()) {
                    throw new IllegalArgumentException("an operator's act on a dispute is reasoned");
                }
                yield operator.reason();
            }
        };
    }

    private com.finapp.ledger.AccountPurpose creditedPurpose(
            Connection unitOfWork, DisputeStore.Found found) {
        PaymentIntent intent =
                intents.findById(unitOfWork, found.intentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a dispute's intent exists: V003's foreign key"
                                                        + " holds it"));
        // The ledger's by-id read is a share lock (ChargebackAccounting's postability read): the
        // purpose never changes, and FOR SHARE conflicts with nothing a dispute path holds.
        return ledgerAccounts.lockForShare(unitOfWork, intent.creditAccount())
                .map(LedgerAccount::purpose)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "an intent's credit account exists: V002's reference"
                                                + " holds it"));
    }
}
