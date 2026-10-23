package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The sealed inputs of one evaluation (`P13-TSK-003`; {@code PHASE_13_PLAN.md} sections 12.2-12.5; ADR-0109
 * decision 3; {@code INV-RSK-01}, {@code INV-RSK-04}) - every reading a {@link RuleKind} may judge, each typed, bounded
 * and {@link Reading optional} where a source may be absent. Read by nobody here: the caller (`-004`'s T-eval) fills
 * them from the signals (`-005`), the windows (`-006`), the profile (`-007`), the session and the clearances, in
 * its own transaction, and seals them beside the verdict; replay rebuilds this value from the sealed row and runs the
 * engine again ({@code INV-CRD-01}'s rule).
 *
 * <p><strong>Judged whole at construction.</strong> Every bound is checked and an input outside it is refused, never
 * clamped; every map is copied into a declaration-ordered form so two presentations of the same readings are one
 * value and one canonical form ({@link CanonicalEvaluation}); a judged operation carries its amount, the sweep's
 * need not. The subjects are what the assessment is <em>about</em> - references only ({@link SignalSubject}: a UUID,
 * or a device's hash) - and no rule reads them.
 *
 * <p><strong>The bounds</strong> are the arithmetic's: a sum of at most {@link #MAX_AMOUNTS} amounts of at most
 * {@link #MAX_MINOR} stays under 10^18, and a share's cross-multiplication by 10,000 basis points stays under
 * 10^18 - both inside {@code long} ({@code INV-MON-01}, no floating point and no overflow).
 */
public record EvaluationInputs(
        RiskOperation operation,
        Map<SubjectKind, SignalSubject> subjects,
        Reading<Money> amount,
        Map<VelocityKey, VelocityReading> velocity,
        Reading<FirstSeen> payeeFirstSeen,
        Reading<FirstSeen> deviceFirstSeen,
        Reading<RiskAssurance> sessionAssurance,
        Map<WindowKind, Long> authFailures,
        Reading<LastSeen> credentialChange,
        Reading<LastSeen> recovery,
        Reading<Integer> accountAgeDays,
        Reading<Boolean> kycApproved,
        Reading<Boolean> screeningHit,
        Reading<RiskRating> profileRating,
        Reading<Boolean> merchantSuspended,
        Reading<String> corridor,
        MonitoringReadings monitoring) {

    /** The largest sealed amount, count-of-money or flow: 10^14 minor units (EUR 10^12). */
    public static final long MAX_MINOR = 100_000_000_000_000L;
    /** The largest sealed count. */
    public static final long MAX_COUNT = 1_000_000_000L;
    /** The largest sealed number of days - a hundred years. */
    public static final int MAX_DAYS = 36_500;
    /** The largest sealed number of hours - a hundred years. */
    public static final long MAX_HOURS = 876_000L;
    /** The most amounts one window seals. */
    public static final int MAX_AMOUNTS = 10_000;

    public EvaluationInputs {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(payeeFirstSeen, "payeeFirstSeen");
        Objects.requireNonNull(deviceFirstSeen, "deviceFirstSeen");
        Objects.requireNonNull(sessionAssurance, "sessionAssurance");
        Objects.requireNonNull(authFailures, "authFailures");
        Objects.requireNonNull(credentialChange, "credentialChange");
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(accountAgeDays, "accountAgeDays");
        Objects.requireNonNull(kycApproved, "kycApproved");
        Objects.requireNonNull(screeningHit, "screeningHit");
        Objects.requireNonNull(profileRating, "profileRating");
        Objects.requireNonNull(merchantSuspended, "merchantSuspended");
        Objects.requireNonNull(corridor, "corridor");
        Objects.requireNonNull(monitoring, "monitoring");
        if (operation == RiskOperation.CREDIT_SIGNAL) {
            throw new IllegalArgumentException("the credit signal evaluates no policy: it answers the profile's rating");
        }
        if (operation.judgesAMovement() && !amount.isPresent()) {
            throw new IllegalArgumentException(operation + " judges a movement: its amount is sealed");
        }
        amount.value().ifPresent(money -> {
            if (money.minorUnits() < 0 || money.minorUnits() > MAX_MINOR) {
                throw new IllegalArgumentException("the judged amount is 0 to " + MAX_MINOR + " minor units");
            }
        });
        EnumMap<SubjectKind, SignalSubject> orderedSubjects = new EnumMap<>(SubjectKind.class);
        for (Map.Entry<SubjectKind, SignalSubject> entry : subjects.entrySet()) {
            SignalSubject subject = Objects.requireNonNull(entry.getValue(), "a subject");
            if (subject.kind() != entry.getKey()) {
                throw new IllegalArgumentException("a subject is held under its own kind");
            }
            orderedSubjects.put(entry.getKey(), subject);
        }
        subjects = Collections.unmodifiableMap(orderedSubjects);
        TreeMap<VelocityKey, VelocityReading> orderedVelocity = new TreeMap<>();
        for (Map.Entry<VelocityKey, VelocityReading> entry : velocity.entrySet()) {
            orderedVelocity.put(Objects.requireNonNull(entry.getKey(), "a velocity key"),
                    Objects.requireNonNull(entry.getValue(), "a velocity reading"));
        }
        velocity = Collections.unmodifiableSortedMap(orderedVelocity);
        authFailures = MonitoringReadings.ordered(authFailures, "authFailures");
        for (long failures : authFailures.values()) {
            if (failures < 0 || failures > MAX_COUNT) {
                throw new IllegalArgumentException("an authentication failure count is 0 to " + MAX_COUNT);
            }
        }
        accountAgeDays.value().ifPresent(days -> {
            if (days < 0 || days > MAX_DAYS) {
                throw new IllegalArgumentException("an account age is 0 to " + MAX_DAYS + " days");
            }
        });
        corridor.value().ifPresent(code -> {
            if (!ParameterValue.Corridors.CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("a corridor is named SRC-DST-CC");
            }
        });
    }

    /** A builder with every reading {@code ABSENT} and no subject - the shape a caller fills source by source. */
    public static Builder builder(RiskOperation operation) {
        return new Builder(operation);
    }

    @Override
    public String toString() {
        // Never a figure or a reference: the inputs are the Transaction Risk Profile, CONFIDENTIAL.
        return "EvaluationInputs[" + operation + ", " + subjects.size() + " subjects, " + velocity.size()
                + " velocity readings]";
    }

    /** Fills an {@link EvaluationInputs} reading by reading; everything not set stays {@code ABSENT}. */
    public static final class Builder {

        private final RiskOperation operation;
        private final Map<SubjectKind, SignalSubject> subjects = new EnumMap<>(SubjectKind.class);
        private Reading<Money> amount = Reading.absent();
        private final Map<VelocityKey, VelocityReading> velocity = new TreeMap<>();
        private Reading<FirstSeen> payeeFirstSeen = Reading.absent();
        private Reading<FirstSeen> deviceFirstSeen = Reading.absent();
        private Reading<RiskAssurance> sessionAssurance = Reading.absent();
        private final Map<WindowKind, Long> authFailures = new EnumMap<>(WindowKind.class);
        private Reading<LastSeen> credentialChange = Reading.absent();
        private Reading<LastSeen> recovery = Reading.absent();
        private Reading<Integer> accountAgeDays = Reading.absent();
        private Reading<Boolean> kycApproved = Reading.absent();
        private Reading<Boolean> screeningHit = Reading.absent();
        private Reading<RiskRating> profileRating = Reading.absent();
        private Reading<Boolean> merchantSuspended = Reading.absent();
        private Reading<String> corridor = Reading.absent();
        private MonitoringReadings monitoring = MonitoringReadings.none();

        private Builder(RiskOperation operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        public Builder subject(SignalSubject subject) {
            subjects.put(Objects.requireNonNull(subject, "subject").kind(), subject);
            return this;
        }

        public Builder amount(Money amount) {
            this.amount = Reading.of(amount);
            return this;
        }

        public Builder velocity(SubjectKind dimension, WindowKind window, long count, long sumMinor) {
            velocity.put(new VelocityKey(dimension, window), new VelocityReading(count, sumMinor));
            return this;
        }

        public Builder payeeFirstSeen(FirstSeen firstSeen) {
            this.payeeFirstSeen = Reading.of(firstSeen);
            return this;
        }

        public Builder deviceFirstSeen(FirstSeen firstSeen) {
            this.deviceFirstSeen = Reading.of(firstSeen);
            return this;
        }

        public Builder sessionAssurance(RiskAssurance assurance) {
            this.sessionAssurance = Reading.of(assurance);
            return this;
        }

        public Builder authFailures(WindowKind window, long count) {
            authFailures.put(Objects.requireNonNull(window, "window"), count);
            return this;
        }

        public Builder credentialChange(LastSeen lastSeen) {
            this.credentialChange = Reading.of(lastSeen);
            return this;
        }

        public Builder recovery(LastSeen lastSeen) {
            this.recovery = Reading.of(lastSeen);
            return this;
        }

        public Builder accountAgeDays(int days) {
            this.accountAgeDays = Reading.of(days);
            return this;
        }

        public Builder kycApproved(boolean approved) {
            this.kycApproved = Reading.of(approved);
            return this;
        }

        public Builder screeningHit(boolean hit) {
            this.screeningHit = Reading.of(hit);
            return this;
        }

        public Builder profileRating(RiskRating rating) {
            this.profileRating = Reading.of(rating);
            return this;
        }

        public Builder merchantSuspended(boolean suspended) {
            this.merchantSuspended = Reading.of(suspended);
            return this;
        }

        public Builder corridor(String corridor) {
            this.corridor = Reading.of(corridor);
            return this;
        }

        public Builder monitoring(MonitoringReadings monitoring) {
            this.monitoring = Objects.requireNonNull(monitoring, "monitoring");
            return this;
        }

        public EvaluationInputs build() {
            return new EvaluationInputs(operation, subjects, amount, velocity, payeeFirstSeen, deviceFirstSeen,
                    sessionAssurance, authFailures, credentialChange, recovery, accountAgeDays, kycApproved,
                    screeningHit, profileRating, merchantSuspended, corridor, monitoring);
        }
    }
}
