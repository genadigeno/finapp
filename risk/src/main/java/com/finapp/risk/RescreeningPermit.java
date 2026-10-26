package com.finapp.risk;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One party's rescreening under one list version (`P13-TSK-022`; {@code RISK_LIFECYCLES.md} section 4.10; {@code risk
 * V017}'s {@code rescreening_permit}) - born once per (party, version) by the activation's enqueue
 * ({@code rescreening_permit_once}), claimed by the sweep's rescreening leg, concluded once:
 * {@code PENDING → DONE(CLEARED | HIT | SKIPPED) | FAILED(cause)}, a claim or a deferral moving it {@code PENDING →
 * PENDING} with its permit ahead. Its id is the clearance signal's born-once key and its request's key to kyc.
 *
 * <p>Read with its version's list and activation instant (the claim's join): what the request names and the currency
 * line a clearance is judged against.
 *
 * @param id the permit's identity
 * @param partyId the party rescreened
 * @param listVersionId the list version it rescreens under
 * @param list the version's list
 * @param listActivatedAt the version's activation, on the database clock
 * @param status where it stands
 * @param outcome the conclusion, when {@code DONE}
 * @param cause why it waits (the last deferral's) or why it failed
 * @param signalId the clearance signal it recorded, for {@code CLEARED} and {@code HIT}
 * @param nextAttemptAt the permit, while {@code PENDING}
 * @param attempts the claims counted
 * @param correlationId the activation's correlation
 */
public record RescreeningPermit(
        RescreeningPermitId id,
        UUID partyId,
        ScreeningListVersionId listVersionId,
        ScreeningList list,
        Instant listActivatedAt,
        Status status,
        Optional<Outcome> outcome,
        Optional<Cause> cause,
        Optional<RiskSignalId> signalId,
        Optional<Instant> nextAttemptAt,
        int attempts,
        String correlationId) {

    /** The permit's machine. */
    public enum Status {
        /** Due, claimed or deferred - awaiting its conclusion. */
        PENDING,
        /** Concluded: kyc's current clearance recorded, or the party no longer active. Terminal. */
        DONE,
        /** Concluded without a clearance after the bound - the exposure, counted. Terminal. */
        FAILED;

        /** The quoted SQL list. */
        public static String sqlValueList() {
            return list(values());
        }
    }

    /** A {@code DONE} permit's conclusion. */
    public enum Outcome {
        /** kyc's screening under the version cleared - {@code SCREENING_CLEARED} recorded. */
        CLEARED,
        /** kyc's screening under the version hit - {@code SCREENING_HIT} recorded and alerted. */
        HIT,
        /** The party has no {@code ACTIVE} customer relationship any more - nothing recorded. */
        SKIPPED;

        /** The quoted SQL list. */
        public static String sqlValueList() {
            return list(values());
        }

        /** True when the conclusion recorded a clearance signal. */
        public boolean recordsASignal() {
            return this != SKIPPED;
        }
    }

    /** Why a permit waits, or why it failed. */
    public enum Cause {
        /** kyc's latest decided screening predates the version's activation - kyc has not screened under it. */
        STALE,
        /** A screening of the list's type is in flight in kyc. */
        IN_FLIGHT,
        /** kyc holds no decided screening of the list's type for the party. */
        NOT_SCREENED,
        /** kyc could not be asked. */
        UNAVAILABLE;

        /** The quoted SQL list. */
        public static String sqlValueList() {
            return list(values());
        }
    }

    public RescreeningPermit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(listVersionId, "listVersionId");
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(listActivatedAt, "listActivatedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(signalId, "signalId");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if ((status == Status.PENDING) != nextAttemptAt.isPresent()) {
            throw new IllegalArgumentException("a permit holds its permit exactly while PENDING");
        }
        if ((status == Status.DONE) != outcome.isPresent()) {
            throw new IllegalArgumentException("a permit names its outcome exactly when DONE");
        }
        if ((status == Status.FAILED && cause.isEmpty()) || (status == Status.DONE && cause.isPresent())) {
            throw new IllegalArgumentException("a failed permit names its cause; a done one none");
        }
        if (signalId.isPresent() != outcome.map(Outcome::recordsASignal).orElse(false)) {
            throw new IllegalArgumentException("a permit names its signal exactly when it recorded a clearance");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts are never negative");
        }
    }

    /** The key its request to kyc presents. */
    public String requestKey() {
        return PartyRescreening.keyOf(id);
    }

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        // Codes and counts only: the party is CONFIDENTIAL.
        return "RescreeningPermit[" + list + ", " + status + outcome.map(o -> "(" + o + ")").orElse("")
                + cause.map(c -> "(" + c + ")").orElse("") + ", attempts=" + attempts + "]";
    }
}
