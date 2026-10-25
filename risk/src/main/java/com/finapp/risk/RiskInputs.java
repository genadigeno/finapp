package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The readings only other modules can answer (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 7.1, 12.2-12.5;
 * ADR-0107 decision 3) - a port risk declares and {@code app} implements over the owning modules' reads, because risk
 * sees none of them ({@code RiskModuleIsolationTest}). Read by T-eval on the caller's unit of work, after the pin and the
 * subjects' serialisation, under the evaluation's statement timeout and behind its savepoint - so a failure or a
 * timeout here is the recorded fail-safe, never the caller's abort; and by T-advise the same way.
 *
 * <p><strong>Where every reading comes from</strong> (the design's mapping, 2026-10-23). Risk computes from its own
 * tables: the velocity figures ({@link VelocityWindows}), the profile's rating ({@link CustomerRiskProfiles#current}),
 * the credential change and the recovery on the acting identity, the source account's age and a beneficiary payee's
 * first sighting ({@link SignalReadings}), and - since `P13-TSK-013` - an account payee's and a device's first sighting
 * (the destination's and the device's earliest counted window, {@link VelocityWindows#firstCounted}). This port answers
 * the rest: the session's assurance ({@link SessionAssurance}), the party's screening hit ({@link ScreeningClearances}),
 * whether its KYC is approved, whether the merchant is suspended and the authentication failures per window.
 * <strong>A reading no source can answer is {@code ABSENT}, never a default</strong> - a rule over it does not fire and
 * its hit says {@code ABSENT}: today the authentication failures (identity's throttle counts consecutive failures toward
 * a lockout, not the rule's {@code H1}/{@code D1} windows, and its failure event names no identity, {@code INV-IDN-07}).
 */
@FunctionalInterface
public interface RiskInputs {

    /** Nothing answered - every reading {@code ABSENT}: the suites' and a composition with no source. */
    RiskInputs NONE = (unitOfWork, request, judged, now) -> Readings.none();

    /**
     * The readings for {@code request} on {@code unitOfWork} at {@code now} - the database instant T-eval cut its
     * windows at, which every lookback is judged against. {@code judged} is the pinned version's rule kinds: an
     * implementation reads only what one of them judges (every read is paid inside the caller's money lock), and the
     * session's assurance always - a {@code CHALLENGE} is answered from it whatever rule raised it.
     */
    Readings read(Connection unitOfWork, RiskEvaluationRequest request, Set<RuleKind> judged, Instant now);

    /** The answered readings, each {@code PRESENT} or {@code ABSENT}. */
    record Readings(
            Reading<RiskAssurance> sessionAssurance,
            Reading<Boolean> screeningHit,
            Reading<Boolean> kycApproved,
            Reading<Boolean> merchantSuspended,
            Map<WindowKind, Long> authFailures) {

        public Readings {
            Objects.requireNonNull(sessionAssurance, "sessionAssurance");
            Objects.requireNonNull(screeningHit, "screeningHit");
            Objects.requireNonNull(kycApproved, "kycApproved");
            Objects.requireNonNull(merchantSuspended, "merchantSuspended");
            Objects.requireNonNull(authFailures, "authFailures");
            EnumMap<WindowKind, Long> ordered = new EnumMap<>(WindowKind.class);
            ordered.putAll(authFailures);
            authFailures = Collections.unmodifiableMap(ordered);
        }

        /** Every reading {@code ABSENT}. */
        public static Readings none() {
            return new Readings(Reading.absent(), Reading.absent(), Reading.absent(), Reading.absent(), Map.of());
        }

        @Override
        public String toString() {
            // Never a reading's value: the inputs are the Transaction Risk Profile, CONFIDENTIAL.
            return "RiskInputs.Readings";
        }
    }
}
