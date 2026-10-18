package com.finapp.bnpl;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Credit decisioning, as bnpl reaches it (`P12-TSK-010`; {@code PHASE_12_PLAN.md} sections 3.3 and 7.1 T-app,
 * {@code BNPL_LIFECYCLES.md} section 4.2; owner decision B5 - one decision per purchase) - a port {@code app} implements
 * over credit's published API, because bnpl has no build edge to {@code credit} (ADR-0101 decision 6, lending's
 * {@code LoanCreditDecisions} precedent). Every method runs on the CALLER's unit of work, so what credit writes commits or
 * rolls back with bnpl's own rows: an application exists iff its credit request does.
 *
 * <p><strong>The BNPL origin, always.</strong> Every request this port opens is opened from credit's
 * {@code RequestOrigin.BNPL_APPLICATION} - the one origin that admits a BNPL product (`P12-TSK-001`), so no customer can open
 * a BNPL reservation outside a purchase - and every cancellation is asked from that origin, so the customer's own credit
 * door cannot cancel it and this one cannot cancel a loan's.
 *
 * <p>Built by `P12-TSK-010`: {@link #open}, {@link #cancel}, {@link #reasonTexts}. The offer (`P12-TSK-011`) adds
 * {@link #decisionFor} - credit's request and, once decided, its decision, read authoritatively on the caller's
 * connection with no credit lock taken, so the offering transaction never waits on credit's deciding one - and
 * {@link #requestOf}, the decision event's hint resolved to its request. The acceptance (`P12-TSK-013`) adds
 * {@link #lockExposure} (L0) and {@link #consume}.
 *
 * <p><strong>The refusals bnpl gives meaning to are declared here</strong> - an open request ({@link RequestOpen}), an
 * amount or plan outside credit's product bounds ({@link TermsOutOfBounds}), a request past cancellation
 * ({@link NotCancellable}). Every other refusal of credit's - the applicant not eligible, a missing consent basis, a
 * product no credit policy offers - is credit's own exception, passed through this port and bnpl untouched, so the door
 * answers it unchanged ({@code PHASE_12_PLAN.md} section 9).
 */
public interface BnplCreditDecisions {

    /**
     * Opens credit's decision request for {@code party} - the customer as actor - and answers its id.
     *
     * @throws RequestOpen when the party already holds an open request for the product (credit's partial unique)
     * @throws TermsOutOfBounds when the amount or the plan's term is outside credit's product bounds
     */
    UUID open(Connection unitOfWork, UUID party, CreditRequest request, Actor actor, CorrelationId correlation);

    /**
     * Cancels {@code party}'s request {@code decisionRequest} - credit's own cancellation under its request row lock,
     * asked from the BNPL origin.
     *
     * @throws NotCancellable when credit has evaluated it, or it is closed
     */
    void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor, CorrelationId correlation);

    /** A decided request's reasons as the customer may read them, in order; empty when it has none. */
    List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest);

    /**
     * Credit's authoritative answer for {@code party}'s request {@code decisionRequest} (`P12-TSK-011`, T-off): still
     * open, closed without a decision, declined, or approved with its figures - plain reads on the caller's connection,
     * no credit lock taken. Never an event's payload: a hint only makes the application due.
     *
     * @throws IllegalStateException when the request is not the party's, or a decided request has no decision - bnpl's
     *     own record named it, so its absence is a broken database, never a refusal
     */
    CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party);

    /** The request the decision {@code decision} answers - {@code credit.CreditDecisionRecorded}'s hint - or empty. */
    Optional<UUID> requestOf(Connection unitOfWork, UUID decision);

    /**
     * (L0) {@code party}'s credit profile {@code FOR UPDATE} on the caller's connection (`P12-TSK-013`, T-pur) - credit's own
     * profile lock, the one its deciding transaction takes first, so an acceptance and a credit decision for the party
     * serialise and the decision sees the reservation or the obligation's principal, never neither ({@code INV-BNPL-10}).
     * Taken FIRST, before any bnpl row.
     *
     * @return whether the party has a profile - an offered party always does, its decision was made under it
     */
    boolean lockExposure(Connection unitOfWork, UUID party);

    /**
     * Consumes credit's decision {@code decision} for the BNPL agreement {@code agreement} (`P12-TSK-013`) - credit's
     * published consumption, the consumer {@code ConsumerRef(BNPL_AGREEMENT, agreement)}: credit re-takes L0 (re-entrant),
     * judges the lapse on its own clock under it, writes its consumption under its two uniques and its own audit record,
     * all on the caller's unit of work.
     */
    Consumption consume(Connection unitOfWork, UUID decision, BnplAgreementId agreement);

    /** What credit answers a consumption with (`P12-TSK-013`). */
    sealed interface Consumption {

        /** Consumed for this agreement: the decision's party and approved amount, which the purchase holds to its own. */
        record Consumed(UUID decision, UUID party, Money amount) implements Consumption {

            public Consumed {
                Objects.requireNonNull(decision, "decision must not be null");
                Objects.requireNonNull(party, "party must not be null");
                Objects.requireNonNull(amount, "amount must not be null");
            }

            /** Never the amount ({@code INV-AUD-02}). */
            @Override
            public String toString() {
                return "Consumed[" + decision + ", redacted]";
            }
        }

        /** Refused by name - nothing consumed. */
        record Refused(Refusal refusal) implements Consumption {

            public Refused {
                Objects.requireNonNull(refusal, "refusal must not be null");
            }
        }

        /** Why credit would not consume the decision. */
        enum Refusal {
            /** Not an approval. */
            NOT_APPROVED,
            /** Its validity has passed on credit's clock. */
            LAPSED,
            /** Consumed already, by another consumer. */
            ALREADY_CONSUMED,
            /** No such decision. */
            NOT_FOUND
        }
    }

    /** What credit answers a BNPL application's request with (`P12-TSK-011`). */
    sealed interface CreditOutcome {

        /** The request is still open - collecting, evaluating or in review. */
        record Pending() implements CreditOutcome {}

        /** The request closed without a decision. */
        record Closed(Closure closure) implements CreditOutcome {

            public Closed {
                Objects.requireNonNull(closure, "closure must not be null");
            }
        }

        /** Credit declined. */
        record Declined(UUID decision) implements CreditOutcome {

            public Declined {
                Objects.requireNonNull(decision, "decision must not be null");
            }
        }

        /**
         * Credit approved {@code amount} (at most the request - the purchase) and, for pay-in-N, {@code termMonths}, valid
         * until {@code validUntil} on the database clock.
         */
        record Approved(UUID decision, UUID party, BnplProduct product, Money amount, OptionalInt termMonths,
                Instant validUntil) implements CreditOutcome {

            public Approved {
                Objects.requireNonNull(decision, "decision must not be null");
                Objects.requireNonNull(party, "party must not be null");
                Objects.requireNonNull(product, "product must not be null");
                Objects.requireNonNull(amount, "amount must not be null");
                Objects.requireNonNull(termMonths, "termMonths must not be null");
                Objects.requireNonNull(validUntil, "validUntil must not be null");
            }

            /** Never the amount ({@code INV-AUD-02}). */
            @Override
            public String toString() {
                return "Approved[" + decision + ", " + product + ", redacted]";
            }
        }

        /** How a request closed without a decision - each an application closure reason of its own. */
        enum Closure {
            CANCELLED,
            EXPIRED,
            ABANDONED;

            /** The application's closure reason for this closure. */
            public BnplApplicationClosureReason reason() {
                return switch (this) {
                    case CANCELLED -> BnplApplicationClosureReason.CREDIT_REQUEST_CANCELLED;
                    case EXPIRED -> BnplApplicationClosureReason.CREDIT_REQUEST_EXPIRED;
                    case ABANDONED -> BnplApplicationClosureReason.CREDIT_REQUEST_ABANDONED;
                };
            }
        }
    }

    /**
     * What a BNPL application asks credit for: the product (credit's of the same name), the purchase amount - the
     * checkout session's gross, never a customer's figure - and the plan's term in months (pay-in-N's; pay-in-4's fixed
     * plan sends none). No declared figures: a BNPL request carries none.
     */
    record CreditRequest(BnplProduct product, Money amount, OptionalInt termMonths) {

        public CreditRequest {
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(termMonths, "termMonths must not be null");
        }

        /** The request a purchase of {@code amount} on {@code plan} asks for. */
        public static CreditRequest of(BnplProduct product, BnplPlan plan, Money amount) {
            Objects.requireNonNull(plan, "plan must not be null");
            if (!Objects.requireNonNull(product, "product must not be null").plans().contains(plan)) {
                throw new IllegalArgumentException(plan + " is not a plan of " + product);
            }
            return new CreditRequest(product, amount, plan.termMonths());
        }

        /** Never the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "CreditRequest[" + product + ", redacted]";
        }
    }

    /** The party already holds an open credit request for the product. */
    final class RequestOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public RequestOpen() {
            super("an open credit decision request exists for this product");
        }
    }

    /** The amount or term is outside credit's product bounds; the message names the bound, never the customer's figure. */
    final class TermsOutOfBounds extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public TermsOutOfBounds(String bound) {
            super(bound);
        }
    }

    /** Credit's request is past cancellation - evaluated, or closed. */
    final class NotCancellable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NotCancellable() {
            super("the credit decision request can no longer be cancelled");
        }
    }
}
