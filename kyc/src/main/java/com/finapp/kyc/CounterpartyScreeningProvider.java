package com.finapp.kyc;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The counterparty screening boundary (`P9-TSK-016`, ADR-0081 point 1; PHASE_9_PLAN.md section 3's
 * ports table) - implemented by the existing {@link ScreeningAdapter}, with a counterparty subject
 * beside its case-bound one. Called with no database connection held.
 *
 * <p>Total: every misbehaviour is an answer, never an exception. A provider that cannot be reached or
 * does not answer is {@code UNAVAILABLE} - nothing known, retried, nothing cleared; an answer that
 * cannot be read is {@code INDETERMINATE} - a person reviews it. Neither is ever a success.
 */
public interface CounterpartyScreeningProvider {

    /** Screens {@code subject} under the screening's id (the provider's idempotency reference). */
    Answer screen(CounterpartyScreeningId screeningId, CounterpartySubject subject);

    /** The provider's verdict and, when anything arrived, the bytes verbatim (evidence). */
    record Answer(CounterpartyScreeningVocabulary.Verdict verdict, Optional<byte[]> evidence) {
        public Answer {
            Objects.requireNonNull(verdict, "verdict must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
            evidence = evidence.map(byte[]::clone);
        }

        public static Answer of(CounterpartyScreeningVocabulary.Verdict verdict, byte[] evidence) {
            return new Answer(verdict, Optional.of(evidence));
        }

        public static Answer withoutEvidence(CounterpartyScreeningVocabulary.Verdict verdict) {
            return new Answer(verdict, Optional.empty());
        }

        @Override
        public Optional<byte[]> evidence() {
            return evidence.map(byte[]::clone);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Answer that
                    && verdict == that.verdict
                    && evidence.isPresent() == that.evidence.isPresent()
                    && (evidence.isEmpty() || Arrays.equals(evidence.get(), that.evidence.get()));
        }

        @Override
        public int hashCode() {
            return verdict.hashCode();
        }

        @Override
        public String toString() {
            return "Answer[" + verdict + ", evidence=" + evidence.map(bytes -> bytes.length + " bytes").orElse("none") + "]";
        }
    }
}
