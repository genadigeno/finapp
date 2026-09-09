package com.finapp.kyc;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One question to one external verifier — the domain-owned provider interface (`P2-TSK-009`,
 * ADR-0008, ADR-0038).
 *
 * <h2>Our vocabulary in, our vocabulary out</h2>
 *
 * <p>Nothing provider-shaped crosses this port: the subject is our identifiers, the result is
 * our {@link CheckOutcome} plus the raw bytes retained as evidence. Provider wire vocabulary —
 * paths, verdict strings, error shapes — lives only in an adapter, so swapping a provider is an
 * adapter change and a provider's odd day cannot become the domain's vocabulary.
 *
 * <h2>The contract is total: provider misbehaviour is a result, never an exception</h2>
 *
 * <p>A timeout, an unreachable host, a 5xx, garbage, and a state we have never seen are all
 * {@link CheckOutcome#INDETERMINATE} — <em>we do not know</em> is an answer ({@code INV-LIFE-03})
 * and the adapter's whole job is to say it rather than throw it. An exception escaping this port
 * is a defect in the adapter, not a fact about the provider, and it leaves the check visibly
 * {@code DISPATCHED} — the reconcilable state — rather than fabricating an outcome.
 */
public interface VerificationProvider {

    /** The one question this provider answers. A run dispatches one check per registered type. */
    CheckType checkType();

    /**
     * Asks the provider, with a bounded wait.
     *
     * <p>Called while <strong>no database connection is held</strong> — the dispatch is already
     * durable and the outcome transaction opens afterwards ({@code VerificationRunService}'s
     * choreography). An HTTP round-trip holding one of eight pooled connections is the
     * `P1-TSK-026` failure shape.
     */
    ProviderResult verify(VerificationSubject subject);

    /** Who is being verified. Identifiers only — the envelope's metadata-only instinct. */
    record VerificationSubject(KycCaseId caseId, UUID customerId) {
        public VerificationSubject {
            Objects.requireNonNull(caseId, "caseId must not be null");
            Objects.requireNonNull(customerId, "customerId must not be null");
        }
    }

    /**
     * The provider's answer, normalised — plus whatever bytes were actually received, verbatim.
     *
     * <p>Evidence is absent only when nothing arrived (a timeout, a refused connection).
     * <strong>Anything received is retained</strong>, malformed and garbage included
     * ({@code INV-HIST-02}): the unparseable answer is precisely the evidence an investigation
     * of the provider wants.
     */
    record ProviderResult(CheckOutcome outcome, Optional<byte[]> evidence) {
        public ProviderResult {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
            evidence = evidence.map(byte[]::clone);
        }

        public static ProviderResult of(CheckOutcome outcome, byte[] evidence) {
            return new ProviderResult(outcome, Optional.of(evidence));
        }

        public static ProviderResult withoutEvidence(CheckOutcome outcome) {
            return new ProviderResult(outcome, Optional.empty());
        }

        @Override
        public Optional<byte[]> evidence() {
            return evidence.map(byte[]::clone);
        }

        /** The outcome and whether bytes arrived — never the bytes ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "ProviderResult[" + outcome + ", evidence=" + evidence.isPresent() + "]";
        }
    }
}
