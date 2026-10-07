package com.finapp.credit;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a bureau pull answered (`P10-TSK-005`; ADR-0085 section 2, {@code INV-CRD-10},
 * {@code INV-LIFE-03}) - data, partial data, or unavailable, and never a fault dressed as data.
 *
 * <p>Closed. An adapter maps every wire outcome onto exactly one of these and never throws for a
 * provider fault; whatever it cannot understand - an unknown status, a malformed body, a 5xx, a
 * timeout - is {@link Unavailable}, which carries no attribute at all. {@link Partial} carries the
 * attributes present and states the absent ones as {@link AttributeValue.Absent}; nothing is
 * defaulted.
 */
public sealed interface BureauAnswer permits BureauAnswer.Received, BureauAnswer.Partial, BureauAnswer.Unavailable {

    /**
     * A complete answer: every attribute present.
     *
     * @param providerCode the bureau's code
     * @param normaliserVersion the adapter's normaliser version
     * @param retrievedAt when the bureau says it produced the report
     * @param attributes the normalised attributes, one per code, none absent
     * @param evidence the bytes received, verbatim
     */
    record Received(
            String providerCode,
            int normaliserVersion,
            Instant retrievedAt,
            List<CreditAttribute> attributes,
            CreditEvidence evidence)
            implements BureauAnswer {
        public Received {
            Objects.requireNonNull(providerCode, "providerCode");
            Objects.requireNonNull(retrievedAt, "retrievedAt");
            Objects.requireNonNull(evidence, "evidence");
            attributes = distinct(attributes);
            if (attributes.isEmpty() || attributes.stream().anyMatch(CreditAttribute::absent)) {
                throw new IllegalArgumentException("a received answer carries every attribute, none absent");
            }
        }
    }

    /**
     * A partial answer: the attributes present, and at least one {@code Absent} - a field the bureau
     * did not report, or money in a currency the product cannot assess (with the
     * {@code CURRENCY_NOT_SUPPORTED} marker), never converted.
     *
     * @param providerCode the bureau's code
     * @param normaliserVersion the adapter's normaliser version
     * @param retrievedAt when the bureau says it produced the report
     * @param attributes the normalised attributes, one per code, at least one absent
     * @param evidence the bytes received, verbatim
     */
    record Partial(
            String providerCode,
            int normaliserVersion,
            Instant retrievedAt,
            List<CreditAttribute> attributes,
            CreditEvidence evidence)
            implements BureauAnswer {
        public Partial {
            Objects.requireNonNull(providerCode, "providerCode");
            Objects.requireNonNull(retrievedAt, "retrievedAt");
            Objects.requireNonNull(evidence, "evidence");
            attributes = distinct(attributes);
            if (attributes.stream().noneMatch(CreditAttribute::absent)) {
                throw new IllegalArgumentException("a partial answer states at least one absent attribute");
            }
        }

        /** The codes this answer states as absent. */
        public Set<CreditAttributeCode> absentCodes() {
            return attributes.stream()
                    .filter(CreditAttribute::absent)
                    .map(CreditAttribute::code)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * No answer that can be used: never an attribute, whatever arrived.
     *
     * @param cause why
     * @param evidence the bytes received, when any arrived - kept as evidence, never parsed into data
     */
    record Unavailable(UnavailableCause cause, Optional<CreditEvidence> evidence) implements BureauAnswer {
        public Unavailable {
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /** Why an answer could not be used. */
    enum UnavailableCause {
        /** The client's wait expired - a slow bureau and a silent one are the same to the caller. */
        TIMEOUT,
        /** A body that could not be read as a report, wholly - its surviving fields are never parsed. */
        MALFORMED,
        /** A status this adapter does not know - a modelled state, never data ({@code INV-LIFE-03}). */
        UNKNOWN_STATUS,
        /** The bureau answered an error, or the transport broke mid-exchange. */
        PROVIDER_ERROR
    }

    private static List<CreditAttribute> distinct(List<CreditAttribute> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        List<CreditAttribute> copy = List.copyOf(attributes);
        Set<CreditAttributeCode> seen = new HashSet<>();
        for (CreditAttribute attribute : copy) {
            if (!seen.add(attribute.code())) {
                throw new IllegalArgumentException("one attribute per code: " + attribute.code());
            }
        }
        return copy;
    }
}
