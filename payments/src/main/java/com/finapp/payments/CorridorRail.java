package com.finapp.payments;

import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The corridor provider boundary (`P9-TSK-014`, ADR-0079, ADR-0080; PHASE_9_PLAN.md section 3's
 * ports table) - a credits-only rail across a border, a SEPARATE port from {@link PushRail}: the
 * corridor needs the beneficiary exchange and the recall, which the push rail must not have, and it
 * has no pay-in, no initiation and no return payment, which the push rail does. The
 * {@code RailOperations} directory names which rails speak which port.
 *
 * <h2>Totality</h2>
 *
 * <p>Every answer is one of a closed set, and the adapter's mapping is total: only a refused
 * connection is {@code NothingSent} (knowledge that nothing left), and everything it cannot read in
 * so many words - a timeout, a 5xx, an unknown word, a missing field, an amount more precise than its
 * currency - is {@code Indeterminate}. No default is a success. The payee check maps a close match
 * and any unmapped answer to {@link PayeeCheck#NO_MATCH}, never {@link PayeeCheck#MATCH} (ADR-0080
 * section 3).
 *
 * <h2>Idempotency (INV-PAY-04)</h2>
 *
 * <p>Our end-to-end reference {@code E} is minted and stored before any send, travels as the
 * idempotency key, and is re-sent unchanged: the provider dedupes on it, so a re-send answers the
 * credit's current state ({@link SendAnswer.Received} or {@link SendAnswer.Accepted}) and never
 * creates a second credit - the contract battery's subject. A recall is idempotent on {@code E} too.
 */
public interface CorridorRail {

    /** The retained body's bound - past it, the answer is indeterminate and nothing is retained. */
    int MAX_EVIDENCE_BYTES = ProviderEvidenceStore.MAX_PAYLOAD_BYTES;

    /** The rail this adapter speaks for - a declared corridor rail. */
    RailId id();

    /** Exchanges the customer's single-use grant for the provider's opaque beneficiary reference. */
    BeneficiaryExchange exchangeBeneficiary(BeneficiaryGrant grant);

    /** Instructs one credit, keyed by our {@code E}; a re-send of the same {@code E} is deduped. */
    SendAnswer send(CreditInstruction instruction);

    /** The provider's authoritative word on one credit, with its delivery and return facts. */
    InquiryAnswer inquire(EndToEndReference reference);

    /** Asks the provider to recall one credit; only its definitive {@code Recalled} concludes. */
    RecallAnswer recall(EndToEndReference reference);

    /** Why an answer could not be read as one. */
    enum Indeterminacy {
        TIMEOUT,
        TRANSPORT,
        SERVER_ERROR,
        MALFORMED,
        UNKNOWN_STATE,
        OVER_PRECISE
    }

    /** The provider's payee check: a close match is not a match. */
    enum PayeeCheck {
        MATCH,
        NO_MATCH,
        UNAVAILABLE
    }

    /** What the provider attests the beneficiary is. */
    enum EntityType {
        INDIVIDUAL,
        BUSINESS
    }

    /** Why a grant was refused - definitive, and nothing was registered. */
    enum ExchangeRefusal {
        GRANT_INVALID,
        GRANT_EXPIRED,
        GRANT_USED
    }

    /** Why a credit was refused - definitive, and nothing was sent onward. */
    enum SendRejection {
        BENEFICIARY_CLOSED,
        LIMIT,
        CURRENCY_NOT_CARRIED
    }

    /** Where one credit stands at the provider. */
    enum CreditState {
        /** Acknowledged, not committed: the provider may still accept or reject it. */
        RECEIVED,
        /** Committed: the provider will deliver it - final on acceptance. */
        ACCEPTED,
        /** Refused definitively. */
        REJECTED,
        /** Recalled at our request before acceptance. */
        RECALLED
    }

    /** One retained provider body - opaque bytes, bounded, never logged. */
    record Evidence(byte[] body) {
        public Evidence {
            Objects.requireNonNull(body, "body must not be null");
            if (body.length == 0 || body.length > MAX_EVIDENCE_BYTES) {
                throw new IllegalArgumentException(
                        "evidence is 1.." + MAX_EVIDENCE_BYTES + " bytes");
            }
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Evidence that && java.util.Arrays.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "Evidence[" + body.length + " bytes]";
        }
    }

    /** The customer's single-use grant, exchanged under our reference (the idempotency key). */
    record BeneficiaryGrant(EndToEndReference reference, String grant) {
        public BeneficiaryGrant {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(grant, "grant must not be null");
            if (!PushRail.GrantExchange.wellShaped(grant)) {
                throw new IllegalArgumentException(
                        "a grant must be 1-" + PushRail.GrantExchange.MAX_GRANT_LENGTH
                                + " characters of [A-Za-z0-9_.:-], carry a letter, and never take a"
                                + " bank identifier's shape (INV-RAIL-03)");
            }
        }

        @Override
        public String toString() {
            return "BeneficiaryGrant[reference=" + reference + ", grant=<redacted>]";
        }
    }

    /** The exchange's answer. */
    sealed interface BeneficiaryExchange {

        /** The suffix the customer recognises their beneficiary by: four letters or digits. */
        Pattern SUFFIX = Pattern.compile("[A-Za-z0-9]{4}");

        /**
         * The provider's opaque reference and what it attests - never a name or an account
         * identifier (INV-RAIL-03): those stay with the provider and, for screening, with kyc.
         */
        record Exchanged(
                ProviderReference destination,
                String suffix,
                PayeeCheck payeeCheck,
                CountryCode country,
                CurrencyCode currency,
                EntityType entityType,
                Evidence evidence)
                implements BeneficiaryExchange {
            public Exchanged {
                Objects.requireNonNull(destination, "destination must not be null");
                Objects.requireNonNull(suffix, "suffix must not be null");
                Objects.requireNonNull(payeeCheck, "payeeCheck must not be null");
                Objects.requireNonNull(country, "country must not be null");
                Objects.requireNonNull(currency, "currency must not be null");
                Objects.requireNonNull(entityType, "entityType must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
                if (!SUFFIX.matcher(suffix).matches()) {
                    throw new IllegalArgumentException("a beneficiary suffix is four letters or digits");
                }
            }

            @Override
            public String toString() {
                return "Exchanged[destination=<redacted>, suffix=" + suffix + ", payeeCheck=" + payeeCheck
                        + ", country=" + country + ", currency=" + currency + ", entityType="
                        + entityType + "]";
            }
        }

        record Refused(ExchangeRefusal reason, Evidence evidence) implements BeneficiaryExchange {
            public Refused {
                Objects.requireNonNull(reason, "reason must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        record NothingSent() implements BeneficiaryExchange {}

        record Indeterminate(Indeterminacy cause, Optional<Evidence> evidence) implements BeneficiaryExchange {
            public Indeterminate {
                Objects.requireNonNull(cause, "cause must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }
    }

    /** One credit to instruct: our reference, the provider's beneficiary reference, the amount. */
    record CreditInstruction(EndToEndReference reference, ProviderReference destination, Money amount) {
        public CreditInstruction {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("a credit instructs a positive amount");
            }
        }

        @Override
        public String toString() {
            return "CreditInstruction[reference=" + reference + ", destination=<redacted>, amount=<redacted>]";
        }
    }

    /** The send's answer. */
    sealed interface SendAnswer {

        /** Acknowledged, not committed: only an inquiry or a later answer says what follows. */
        record Received(Evidence evidence) implements SendAnswer {
            public Received {
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        /** Committed, with the provider's reference - reconciliation's alias key. */
        record Accepted(ProviderReference providerReference, Instant acceptedAt, Evidence evidence)
                implements SendAnswer {
            public Accepted {
                Objects.requireNonNull(providerReference, "providerReference must not be null");
                Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        record Rejected(SendRejection reason, Evidence evidence) implements SendAnswer {
            public Rejected {
                Objects.requireNonNull(reason, "reason must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        record NothingSent() implements SendAnswer {}

        record Indeterminate(Indeterminacy cause, Optional<Evidence> evidence) implements SendAnswer {
            public Indeterminate {
                Objects.requireNonNull(cause, "cause must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }
    }

    /** A credit coming back: the provider's return reference, the returned amount and when. */
    record ReturnFact(ProviderReference returnReference, Money amount, Instant returnedAt) {
        public ReturnFact {
            Objects.requireNonNull(returnReference, "returnReference must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(returnedAt, "returnedAt must not be null");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("a return carries a positive amount");
            }
        }
    }

    /** The inquiry's answer. */
    sealed interface InquiryAnswer {

        /**
         * The provider knows the credit. Several facts may arrive at once - accepted and delivered,
         * accepted and returned - and each is carried, so the applier takes each edge in order.
         */
        record Found(
                CreditState state,
                Optional<ProviderReference> providerReference,
                Optional<Instant> acceptedAt,
                Optional<Instant> deliveredAt,
                Optional<ReturnFact> returned,
                Optional<SendRejection> rejection,
                Evidence evidence)
                implements InquiryAnswer {
            public Found {
                Objects.requireNonNull(state, "state must not be null");
                Objects.requireNonNull(providerReference, "providerReference must not be null");
                Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
                Objects.requireNonNull(deliveredAt, "deliveredAt must not be null");
                Objects.requireNonNull(returned, "returned must not be null");
                Objects.requireNonNull(rejection, "rejection must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
                boolean accepted = state == CreditState.ACCEPTED;
                if (accepted != (providerReference.isPresent() && acceptedAt.isPresent())) {
                    throw new IllegalArgumentException(
                            "an accepted credit carries the provider's reference and its acceptance"
                                    + " time, and only an accepted one does");
                }
                if (!accepted && (deliveredAt.isPresent() || returned.isPresent())) {
                    throw new IllegalArgumentException(
                            "delivery and return are facts of an accepted credit only");
                }
                if ((state == CreditState.REJECTED) != rejection.isPresent()) {
                    throw new IllegalArgumentException("a rejection carries its reason, and only a rejection");
                }
            }
        }

        /** Explicit and parsed: the provider has never seen this {@code E}. */
        record Unrecognised(Evidence evidence) implements InquiryAnswer {
            public Unrecognised {
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        record NothingSent() implements InquiryAnswer {}

        record Indeterminate(Indeterminacy cause, Optional<Evidence> evidence) implements InquiryAnswer {
            public Indeterminate {
                Objects.requireNonNull(cause, "cause must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }
    }

    /** The recall's answer: only {@code Recalled} concludes; {@code TooLate} concludes nothing. */
    sealed interface RecallAnswer {

        record Recalled(Evidence evidence) implements RecallAnswer {
            public Recalled {
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        record TooLate(Evidence evidence) implements RecallAnswer {
            public TooLate {
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        record Unrecognised(Evidence evidence) implements RecallAnswer {
            public Unrecognised {
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        record NothingSent() implements RecallAnswer {}

        record Indeterminate(Indeterminacy cause, Optional<Evidence> evidence) implements RecallAnswer {
            public Indeterminate {
                Objects.requireNonNull(cause, "cause must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }
    }
}
