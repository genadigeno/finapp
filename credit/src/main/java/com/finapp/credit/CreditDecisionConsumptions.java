package com.finapp.credit;

import java.time.Instant;
import java.util.Objects;

/**
 * Taking up an approved credit decision (`P11-TSK-001`; PHASE_11_PLAN.md section 12.10, ADR-0091; {@code INV-LND-06},
 * {@code INV-CRD-09}, {@code INV-CRD-02}, {@code INV-IDEM-01}) - credit's published command port, the one writer of the
 * born-once consumption fact. An approval reserves exposure while it is valid and unconsumed; consuming it ends the
 * reservation, which the consumer's own commitment (a loan's principal, a line's limit) replaces in the same transaction.
 *
 * <p><strong>In the caller's transaction, in this order</strong>: the decision's party read plain; the party's
 * {@code credit_profile} row taken {@code FOR UPDATE} (lock-order element (1), Phase 11's L0 - so a consumption and a
 * decision for one party serialise); the decision re-read and judged - not {@code APPROVED}, {@link
 * Consumption.NotApproved}; already consumed, by this consumer {@link Consumption.Consumed} replayed, by another
 * {@link Consumption.AlreadyConsumed}; {@code valid_until} at or before the database's clock read under the lock,
 * {@link Consumption.Lapsed} (the exact negation of the reservation's {@code valid_until > now}); else consumed. The
 * caller's rollback leaves no row and no audit record; a consumption that commits is final - there is no release.
 *
 * <p>Every refusal about the <em>decision</em> is a typed answer, never an exception to parse. A consumer already bound
 * to a <em>different</em> decision is the caller's defect: refused by {@code UNIQUE (consumer_kind, consumer_ref)}, it
 * surfaces as {@link CreditStorageException} and the caller's transaction must roll back. {@link Consumption.Consumed}
 * carries the decision, so the consumer can hold it to its own party, product and amount - credit never reads the
 * consumer.
 *
 * <p>Requires {@code READ COMMITTED}, the platform's isolation (ADR-0039): the existence and lapse judgements read after
 * the lock see what the lock's previous holder committed. Under a snapshot isolation a racing consumer's committed row
 * would be invisible to that read, and the loser would meet {@code UNIQUE (decision_id)} instead of
 * {@link Consumption.AlreadyConsumed} - a wrong answer, never a second consumption. The boundary's exactness also rests
 * on the database clock not stepping back across the lock's handover (ADR-0063 point 4's bounded-skew premise);
 * {@code P11-TSK-029}'s exposure census is the continuous check.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CreditDecisionConsumptions<T> {

    /**
     * Takes up the approved {@code decision} for {@code consumer}, on the caller's unit of work. Requires an actor and a
     * correlation in scope - the winner's audit record carries both.
     */
    Consumption consume(T unitOfWork, CreditDecisionId decision, ConsumerRef consumer);

    /** What came of a consumption - five answers, closed. */
    sealed interface Consumption {

        /** The decision the answer is about. */
        CreditDecisionId decisionId();

        /**
         * The decision is consumed by the asking consumer: by this call ({@code replayed} false), or already, by the same
         * consumer's earlier call ({@code replayed} true - the same id, no second record).
         */
        record Consumed(CreditDecisionConsumptionId id, CreditDecision decision, boolean replayed) implements Consumption {

            public Consumed {
                Objects.requireNonNull(id, "id");
                Objects.requireNonNull(decision, "decision");
            }

            @Override
            public CreditDecisionId decisionId() {
                return decision.id();
            }
        }

        /** The decision is not an approval - nothing to take up. */
        record NotApproved(CreditDecisionId decision) implements Consumption {

            public NotApproved {
                Objects.requireNonNull(decision, "decision");
            }

            @Override
            public CreditDecisionId decisionId() {
                return decision;
            }
        }

        /** The approval lapsed at {@code validUntil}, on the database's clock - it neither reserves nor is consumable. */
        record Lapsed(CreditDecisionId decision, Instant validUntil) implements Consumption {

            public Lapsed {
                Objects.requireNonNull(decision, "decision");
                Objects.requireNonNull(validUntil, "validUntil");
            }

            @Override
            public CreditDecisionId decisionId() {
                return decision;
            }
        }

        /** The approval was already taken up - {@code by} another consumer. */
        record AlreadyConsumed(CreditDecisionId decision, ConsumerRef by) implements Consumption {

            public AlreadyConsumed {
                Objects.requireNonNull(decision, "decision");
                Objects.requireNonNull(by, "by");
            }

            @Override
            public CreditDecisionId decisionId() {
                return decision;
            }
        }

        /** No such decision. */
        record NotFound(CreditDecisionId decision) implements Consumption {

            public NotFound {
                Objects.requireNonNull(decision, "decision");
            }

            @Override
            public CreditDecisionId decisionId() {
                return decision;
            }
        }
    }
}
