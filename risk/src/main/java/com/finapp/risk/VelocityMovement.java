package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One judged movement as the velocity windows see it (`P13-TSK-006`; {@code PHASE_13_PLAN.md} section 12.4): the
 * subject it carries in each {@link VelocityDimension} - each optional, because not every operation has every role
 * (a withdrawal has no destination account, a transfer no merchant, a system actor no device or session) - and the
 * amount it moves. Built by `-004`'s T-eval from what the seam adapter passes; nothing here is read from another module.
 *
 * <p><strong>Judged whole at construction.</strong> Each reference has its dimension's shape (a canonical UUID; a
 * device only ever by {@link DeviceHash}); the amount is non-negative, at most {@link EvaluationInputs#MAX_MINOR}
 * minor units, and carries its currency and scale ({@code INV-MON-01}, {@code INV-MON-02}). A movement that carries no
 * subject at all is admitted and counts in no window.
 */
public record VelocityMovement(Map<VelocityDimension, String> subjects, Money amount) {

    public VelocityMovement {
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(amount, "amount");
        EnumMap<VelocityDimension, String> held = new EnumMap<>(VelocityDimension.class);
        for (Map.Entry<VelocityDimension, String> subject : subjects.entrySet()) {
            VelocityDimension dimension = Objects.requireNonNull(subject.getKey(), "a dimension");
            String reference = Objects.requireNonNull(subject.getValue(), "a subject reference");
            if (!dimension.referenceShape().matcher(reference).matches()) {
                // Never the value: a refused device reference may be a description (INV-AUD-02).
                throw new IllegalArgumentException("a " + dimension + " reference is "
                        + (dimension == VelocityDimension.DEVICE ? "identity's device hash" : "a canonical UUID"));
            }
            held.put(dimension, reference);
        }
        if (amount.minorUnits() < 0 || amount.minorUnits() > EvaluationInputs.MAX_MINOR) {
            throw new IllegalArgumentException("a counted amount is 0 to " + EvaluationInputs.MAX_MINOR + " minor units");
        }
        subjects = Collections.unmodifiableMap(held);
    }

    /** A movement of {@code amount}, its subjects added role by role. */
    public static Builder of(Money amount) {
        return new Builder(amount);
    }

    /** The subject this movement carries in {@code dimension}, when it carries one. */
    public Optional<String> subject(VelocityDimension dimension) {
        return Optional.ofNullable(subjects.get(Objects.requireNonNull(dimension, "dimension")));
    }

    @Override
    public String toString() {
        // Never a reference or the amount: who moved how much is CONFIDENTIAL and RESTRICTED-FINANCIAL.
        return "VelocityMovement[" + subjects.keySet() + "]";
    }

    /** Adds the movement's subjects role by role - each at most once. */
    public static final class Builder {

        private final Money amount;
        private final Map<VelocityDimension, String> subjects = new EnumMap<>(VelocityDimension.class);

        private Builder(Money amount) {
            this.amount = Objects.requireNonNull(amount, "amount");
        }

        public Builder party(UUID party) {
            return put(VelocityDimension.PARTY, party);
        }

        /**
         * The account the money leaves - by the <strong>customer account's id</strong>, the {@code owner_ref} the
         * ledger records for it and {@code monitored_transaction.subject_ref} holds, never the ledger account's own id
         * (a transfer carries {@code LedgerAccountId}s: `-008`'s adapter resolves the owner) - so the proof's evidence
         * names the same subject the window counts.
         */
        public Builder sourceAccount(UUID account) {
            return put(VelocityDimension.SOURCE_ACCOUNT, account);
        }

        /** The destination is an account - the customer account's id, as {@link #sourceAccount}... */
        public Builder destinationAccount(UUID account) {
            return put(VelocityDimension.DESTINATION, account);
        }

        /** ...or a cross-border beneficiary - one role either way. */
        public Builder destinationBeneficiary(UUID beneficiary) {
            return put(VelocityDimension.DESTINATION, beneficiary);
        }

        /** A device, only ever by its hash (OQ-4). */
        public Builder device(DeviceHash device) {
            return put(VelocityDimension.DEVICE, Objects.requireNonNull(device, "device").value());
        }

        public Builder merchant(UUID merchant) {
            return put(VelocityDimension.MERCHANT, merchant);
        }

        /** The identity session that confirmed it, by the session's id (`P13-TSK-014`). */
        public Builder session(UUID session) {
            return put(VelocityDimension.SESSION, session);
        }

        public VelocityMovement build() {
            return new VelocityMovement(subjects, amount);
        }

        private Builder put(VelocityDimension dimension, UUID id) {
            return put(dimension, Objects.requireNonNull(id, dimension.name()).toString());
        }

        private Builder put(VelocityDimension dimension, String reference) {
            if (subjects.putIfAbsent(dimension, reference) != null) {
                throw new IllegalArgumentException("a movement carries one " + dimension);
            }
            return this;
        }
    }
}
