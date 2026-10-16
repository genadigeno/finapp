package com.finapp.credit;

import java.util.Objects;

/**
 * Where a credit decision request was opened (`P12-TSK-001`; PHASE_12_PLAN.md section 12.1, the Phase 11 exit review's
 * owner item #9 applied forward) - recorded on {@code decision_request}, frozen by its machine trigger, and bound to the
 * product by credit {@code V022}'s {@code decision_request_origin_per_product} CHECK.
 *
 * <p><strong>Closed, and one family each.</strong> A lending product's request is opened at credit's customer door (or
 * through lending's application, which submits on the customer's behalf at the same door); a BNPL product's only by the
 * BNPL application, inside a checkout - so no customer can open a BNPL reservation outside a purchase. A submission whose
 * origin does not admit its product is {@code credit.ProductNotOffered}, before anything is read or written; a
 * cancellation asked from another origin finds no request.
 */
public enum RequestOrigin {

    /** Credit's customer door ({@code POST /v1/me/credit/decision-requests}) and lending's application. */
    CUSTOMER_DOOR(CreditProduct.Family.LENDING),

    /** The BNPL application (`P12-TSK-010`), one request per purchase. */
    BNPL_APPLICATION(CreditProduct.Family.BNPL);

    private final CreditProduct.Family family;

    RequestOrigin(CreditProduct.Family family) {
        this.family = family;
    }

    /** Whether a request for {@code product} may be opened from this origin. */
    public boolean admits(CreditProduct product) {
        return Objects.requireNonNull(product, "product").family() == family;
    }

    /** The one origin a request for {@code product} is opened from. */
    public static RequestOrigin of(CreditProduct product) {
        Objects.requireNonNull(product, "product");
        for (RequestOrigin origin : values()) {
            if (origin.admits(product)) {
                return origin;
            }
        }
        throw new IllegalStateException(product + " has no origin");
    }
}
