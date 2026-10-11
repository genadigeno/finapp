package com.finapp.lending;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
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
 * decision read. The offer (`P11-TSK-011`) adds {@code decisionFor}, the acceptance (`-012`) {@code consume} and
 * {@code lockExposure} - each to this same port.
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
