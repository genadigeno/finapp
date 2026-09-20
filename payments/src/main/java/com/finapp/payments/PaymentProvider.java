package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The provider port: ask a PSP to move money, in our vocabulary, totally (`P5-TSK-003`,
 * ADR-0049, ADR-0008).
 *
 * <h2>Our vocabulary in, our vocabulary out</h2>
 *
 * <p>Nothing provider-shaped crosses this port ({@code INV-PAY-03}): requests carry our
 * identifiers, our {@link Money} and an opaque instrument token; answers are
 * {@link ProviderAnswer}/{@link QueryAnswer} plus the raw bytes retained as evidence. Provider
 * wire vocabulary — paths, field names, verdict strings — lives only in an adapter, so swapping
 * a provider is an adapter change and a provider's odd day cannot become the domain's
 * vocabulary. The port is deliberately <strong>one provider wide</strong>: multi-rail routing,
 * capability declaration and per-rail finality modelling are Phase 7's (ADR-0049 §4), and
 * building them against a sample of one is how the sample becomes the design.
 *
 * <h2>The contract is total: provider misbehaviour is a result, never an exception</h2>
 *
 * <p>A timeout, a 5xx, garbage, a state nobody mapped — all
 * {@link ProviderAnswer.Verdict#INDETERMINATE} ({@code INV-LIFE-03}); a connection refused
 * before anything was sent is the one transport failure that is knowledge —
 * {@link ProviderAnswer.Verdict#NOTHING_SENT}. An exception escaping this port is a defect in
 * the adapter, not a fact about the provider, and it leaves the operation visibly
 * {@code *_DISPATCHED} — the reconcilable state — rather than fabricating an outcome (the
 * `P2-TSK-009` totality rule, with money on it now).
 *
 * <h2>Idempotent at the provider, by signature</h2>
 *
 * <p>Every money-moving operation takes the platform-minted
 * {@link ProviderIdempotencyReference}, minted and persisted <em>before</em> dispatch by the
 * caller (ADR-0046), so a re-dispatch presents the identical reference and the provider
 * performs one operation ({@code INV-PAY-04}). {@link #query} keys on the same reference,
 * because it exists even when the provider's own reference never arrived.
 *
 * <h2>Called while no database connection is held</h2>
 *
 * <p>The dispatch is already durable and the outcome transaction opens afterwards (ADR-0046's
 * choreography, `P5-TSK-009`). An HTTP round-trip holding one of eight pooled connections is
 * the `P1-TSK-026` failure shape.
 */
public interface PaymentProvider {

    /**
     * This provider's stable name — the evidence and meter tag value ({@code provider} joins
     * {@code ALLOWED_TAG_KEYS} with `P5-TSK-017`), and the per-provider scope of stored
     * provider references (`P5-TSK-008`).
     */
    String providerName();

    /** Asks the provider to authorize against the instrument. No ledger effect ever (ADR-0048). */
    ProviderAnswer authorize(AuthorizationRequest request);

    /** Asks the provider to capture a previously approved authorization. */
    ProviderAnswer capture(CaptureRequest request);

    /** Asks the provider to refund against a previously approved capture. */
    ProviderAnswer refund(RefundRequest request);

    /**
     * Asks the provider what happened to the operation <strong>our</strong> reference names —
     * ADR-0046's resolution by query. Read-only and idempotent at the provider, so every
     * instance may ask concurrently: no lease, no leader.
     */
    QueryAnswer query(ProviderIdempotencyReference ourReference);

    /**
     * An authorization dispatch: the reference, the tokenised instrument, the amount.
     *
     * <p>The token is an opaque {@link InstrumentToken} on purpose — {@code paymentmethods} is
     * invisible to this module by the PCI build-graph decision (`P5-TSK-001`,
     * {@code INV-PAY-02}), and resolution from a customer's stored instrument to its token is a
     * port {@code app} implements (`P5-TSK-009`). It travels wrapped, and comes off only on the
     * provider wire ({@code INV-AUD-02}; the type's own javadoc has the rule's history).
     */
    record AuthorizationRequest(
            ProviderIdempotencyReference reference, InstrumentToken instrumentToken, Money amount) {

        public AuthorizationRequest {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(instrumentToken, "instrumentToken must not be null");
            requirePositive(amount);
        }

        /** Reference and currency — never the token, never the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "AuthorizationRequest[" + reference.value() + ", " + amount.currency() + "]";
        }
    }

    /** A capture dispatch: our reference, the authorization's provider reference, the amount. */
    record CaptureRequest(
            ProviderIdempotencyReference reference, ProviderReference authorization, Money amount) {
        public CaptureRequest {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(authorization, "authorization must not be null");
            requirePositive(amount);
        }

        /** References and currency — never the amount ({@code INV-AUD-02}): a record's generated
         * {@code toString} would render {@link Money}, whose kernel diagnostics do. */
        @Override
        public String toString() {
            return "CaptureRequest["
                    + reference.value()
                    + ", "
                    + authorization.value()
                    + ", "
                    + amount.currency()
                    + "]";
        }
    }

    /** A refund dispatch: our reference, the capture's provider reference, the amount. */
    record RefundRequest(
            ProviderIdempotencyReference reference, ProviderReference capture, Money amount) {
        public RefundRequest {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(capture, "capture must not be null");
            requirePositive(amount);
        }

        /** References and currency — never the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "RefundRequest["
                    + reference.value()
                    + ", "
                    + capture.value()
                    + ", "
                    + amount.currency()
                    + "]";
        }
    }

    private static void requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            // Names the fact and the currency, never the amount (INV-AUD-02, the Transfer
            // precedent): this message reaches logs.
            throw new IllegalArgumentException(
                    "a provider-bound amount must be strictly positive; refused a non-positive "
                            + amount.currency()
                            + " amount");
        }
    }
}
