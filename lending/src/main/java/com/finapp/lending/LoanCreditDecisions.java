package com.finapp.lending;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Credit decisioning, as lending reaches it (`P11-TSK-010`; {@code PHASE_11_PLAN.md} section 3.2, A16) - a port
 * {@code app} implements over credit's published API, because lending has no build edge to {@code credit} (the
 * crossborder precedent). Every method runs on the CALLER's unit of work, so what credit writes commits or rolls back
 * with lending's own rows: an application exists iff its credit request does.
 *
 * <p>Built by `P11-TSK-010`: {@link #open} over {@code DecisionRequests.submit} (credit's arbiters and its consent gate,
 * {@code INV-CNS-01}), {@link #cancel} over {@code DecisionRequests.cancel}, and {@link #reasonTexts} over credit's
 * decision read. The offer (`P11-TSK-011`) adds {@link #decisionFor} - the authoritative read the origination step
 * decides on - and {@link #requestOf}, which resolves a credit event's decision to its request for the hint; the
 * acceptance (`P11-TSK-012`) adds {@link #lockExposure} (L0, over credit's profile lock) and {@link #consume} (over
 * credit's {@code CreditDecisionConsumptions}) - each to this same port.
 *
 * <p><strong>The refusals lending gives meaning to are declared here</strong> - an open request
 * ({@link RequestOpen}), terms outside credit's product bounds ({@link TermsOutOfBounds}), a request past cancellation
 * ({@link NotCancellable}). Every other refusal of credit's - the applicant not eligible, a missing consent basis, a
 * product no credit policy offers - is credit's own exception, passed through this port and lending untouched, so the
 * door answers it unchanged ({@code PHASE_11_PLAN.md} section 9).
 */
public interface LoanCreditDecisions {

    /**
     * Opens credit's decision request for {@code party} - the customer as actor - and answers its id.
     *
     * @throws RequestOpen when the party already holds an open request for the product (credit's partial unique)
     * @throws TermsOutOfBounds when the amount, limit or term is outside credit's product bounds
     */
    UUID open(Connection unitOfWork, UUID party, CreditTerms terms, Actor actor, CorrelationId correlation);

    /**
     * Cancels {@code party}'s request {@code decisionRequest} - credit's own cancellation under its request row lock.
     *
     * @throws NotCancellable when credit has evaluated it, or it is closed
     */
    void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor, CorrelationId correlation);

    /** A decided request's reasons as the customer may read them, in order; empty when it has none. */
    List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest);

    /**
     * Where credit's request {@code decisionRequest} of {@code party} stands, read authoritatively on the caller's unit
     * of work (`P11-TSK-011`): still open, decided - approved or declined - or closed without a decision. The origination
     * step decides on this read alone; a credit event never stands in for it ({@code INV-EVT-04}).
     *
     * @throws IllegalStateException when the party has no such request - an application's request is its own party's
     *     and is never deleted
     */
    CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party);

    /** The request credit's decision {@code decision} answers, or empty when no decision has the id. */
    Optional<UUID> requestOf(Connection unitOfWork, UUID decision);

    /**
     * L0 (`P11-TSK-012`; {@code PHASE_11_PLAN.md} section 7.2): {@code party}'s credit profile row {@code FOR UPDATE},
     * held to the caller's commit - taken FIRST by every lending transaction that raises the party's exposure or moves
     * it between reserved, committed and outstanding (the acceptance; the disbursement and the reversal later), so such
     * a transaction and a credit decision for the party serialise ({@code INV-CRD-09}, {@code INV-LND-06}).
     *
     * @return {@code false} when the party has no credit profile - then it has no credit decision, and so no offer to
     *     accept; the caller finds nothing under it
     */
    boolean lockExposure(Connection unitOfWork, UUID party);

    /**
     * Takes up credit's approved decision {@code decision} for the agreement {@code agreement} - credit's own consumption
     * port, on the caller's unit of work (`P11-TSK-012`, ADR-0091 point 4): under the party's profile lock (re-entrant
     * after {@link #lockExposure}), only while {@code APPROVED} and before its {@code valid_until} on the database
     * clock, at most once ({@code INV-LND-06}). The reservation the decision held ends in the same commit that births
     * the agreement whose principal or limit replaces it. Credit's audit record is written by credit.
     */
    Consumption consume(Connection unitOfWork, UUID decision, LoanAgreementId agreement);

    /** What came of a consumption, as the acceptance needs it (`P11-TSK-012`). */
    sealed interface Consumption {

        /**
         * Consumed by this agreement - the decision's party and approved amount, so the acceptance can hold them to the
         * offer's (A20).
         */
        record Consumed(UUID decision, UUID party, Money amount) implements Consumption {
            public Consumed {
                Objects.requireNonNull(decision, "decision must not be null");
                Objects.requireNonNull(party, "party must not be null");
                Objects.requireNonNull(amount, "amount must not be null");
            }

            /** Never the amount or the party ({@code INV-AUD-02}). */
            @Override
            public String toString() {
                return "Consumed[" + decision + "]";
            }
        }

        /** Not consumable - credit's own answer, by name; nothing was consumed. */
        record Refused(Refusal refusal) implements Consumption {
            public Refused {
                Objects.requireNonNull(refusal, "refusal must not be null");
            }
        }

        /** Why credit would not consume - credit's four refusals, by name. */
        enum Refusal {
            /** The decision is not an approval. */
            NOT_APPROVED,
            /** The approval lapsed at its {@code valid_until}, on the database clock read under the profile lock. */
            LAPSED,
            /** Another consumer already took the approval up. */
            ALREADY_CONSUMED,
            /** Credit has no such decision. */
            NOT_FOUND
        }
    }

    /** Where a credit decision request stands, as the origination step needs it (`P11-TSK-011`). */
    sealed interface CreditOutcome {

        /** The request is open: no decision yet - the application keeps waiting. */
        record Pending() implements CreditOutcome {}

        /**
         * Approved: exactly what the offer carries (A20) - the amount (a loan's principal, a line's limit), an instalment
         * product's term, and the decision's validity on the database clock.
         *
         * @param decision credit's decision id, by value
         * @param party the decision's party - the application's own
         * @param product the decision's product, by name
         * @param amount the approved amount
         * @param termMonths the approved term - an instalment product's alone
         * @param validUntil the instant the unconsumed decision lapses
         */
        record Approved(
                UUID decision,
                UUID party,
                LoanProduct product,
                Money amount,
                Optional<Integer> termMonths,
                Instant validUntil) implements CreditOutcome {

            public Approved {
                Objects.requireNonNull(decision, "decision must not be null");
                Objects.requireNonNull(party, "party must not be null");
                Objects.requireNonNull(product, "product must not be null");
                Objects.requireNonNull(amount, "amount must not be null");
                Objects.requireNonNull(termMonths, "termMonths must not be null");
                Objects.requireNonNull(validUntil, "validUntil must not be null");
            }

            /** Never the amount or the party ({@code INV-AUD-02}). */
            @Override
            public String toString() {
                return "Approved[" + decision + ", " + product + "]";
            }
        }

        /** Declined: no offer; the decision's reasons are read through {@link #reasonTexts}. */
        record Declined(UUID decision) implements CreditOutcome {
            public Declined {
                Objects.requireNonNull(decision, "decision must not be null");
            }
        }

        /** Closed without a decision: cancelled, expired or abandoned by credit. */
        record Closed(Closure closure) implements CreditOutcome {
            public Closed {
                Objects.requireNonNull(closure, "closure must not be null");
            }
        }

        /** How credit's request closed without a decision - credit's own terminal states, by name. */
        enum Closure {
            CANCELLED,
            EXPIRED,
            ABANDONED
        }
    }

    /**
     * What the customer asks credit for: the product (credit's of the same name), the amount or limit, the term (an
     * instalment product's alone) and the declared monthly figures - passed to credit, never stored or logged by lending.
     */
    record CreditTerms(
            LoanProduct product,
            Money requested,
            Optional<Integer> termMonths,
            Optional<Money> declaredMonthlyIncome,
            Optional<Money> declaredMonthlyExpenditure) {

        public CreditTerms {
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(requested, "requested must not be null");
            Objects.requireNonNull(termMonths, "termMonths must not be null");
            Objects.requireNonNull(declaredMonthlyIncome, "declaredMonthlyIncome must not be null");
            Objects.requireNonNull(declaredMonthlyExpenditure, "declaredMonthlyExpenditure must not be null");
        }

        /** Never a figure ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "CreditTerms[" + product + ", redacted]";
        }
    }

    /** The party already holds an open credit request for the product. */
    final class RequestOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public RequestOpen() {
            super("an open credit decision request exists for this product");
        }
    }

    /** The terms are outside credit's product bounds; the message names the bound, never the customer's figure. */
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
