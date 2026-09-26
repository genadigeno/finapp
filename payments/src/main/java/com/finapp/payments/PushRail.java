package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The provider-neutral push-rail port (`P7-TSK-006`, ADR-0062 §1): account-to-account and
 * instant payments as the core sees them — our end-to-end references out, our verdicts back,
 * and the scheme's transaction reference and settlement-cycle identifier carried in every
 * answer as stored values (Phase 8's reconciliation keys). Identifier schemes, alias types,
 * message formats, reason codes, time-outs, limits and operating hours are adapter
 * configuration; a second scheme is an adapter plus routing rules, never a core change
 * ({@code INV-RAIL-01}).
 *
 * <p><strong>Nothing here reserves and nothing here is reversible</strong>: a push is final
 * on acceptance (ADR-0059 §1's second column), a "return" is a new transfer, and the port's
 * shape says so by offering no void and no capture.
 *
 * <p>The adapter holds no state and no connection: every instance presents the same stored
 * references, and the callers own permits, retries and machines (`P7-TSK-009`'s territory).
 */
public interface PushRail {

    /** The adapter's name for telemetry and logs — never a branching key. */
    String schemeName();

    /**
     * Exchanges the payer's grant — obtained by the customer's client at the rail provider,
     * never containing bank details the platform sees (ADR-0062 §2) — for the three values
     * the platform is allowed to keep: an opaque destination reference, a four-character
     * display suffix, and the confirmation-of-payee result.
     */
    ExchangeAnswer exchange(GrantExchange request);

    /** Sends a credit transfer carrying our minted end-to-end reference. */
    PushAnswer send(CreditTransfer request);

    /** The scheme's status investigation of a send, by our reference. */
    PushInquiryAnswer inquire(EndToEndReference ourReference);

    /**
     * Starts a pay-by-bank pay-in: the answer's authorization handle is what the payer's
     * client follows to the payer's own PSP, where consent is given with strong customer
     * authentication — never here.
     */
    InitiationAnswer initiate(PayInInitiation request);

    /** The scheme's status investigation of an initiation, by our reference. */
    PushInquiryAnswer inquireInitiation(EndToEndReference ourReference);

    /**
     * The grant exchange's ask. The grant is an opaque, single-use value minted by the rail
     * provider; it is never logged, never stored, and spent on this one call.
     */
    record GrantExchange(EndToEndReference reference, String grant) {
        public GrantExchange {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(grant, "grant must not be null");
            if (grant.isBlank()) {
                throw new IllegalArgumentException("a grant must not be blank");
            }
        }
    }

    /** An outbound push to a destination the exchange named — by its opaque reference only. */
    record CreditTransfer(
            EndToEndReference reference, ProviderReference destination, Money amount) {
        public CreditTransfer {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }

    /** A pay-by-bank initiation: the payer pushes to the platform after authorizing. */
    record PayInInitiation(EndToEndReference reference, Money amount) {
        public PayInInitiation {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }
}
