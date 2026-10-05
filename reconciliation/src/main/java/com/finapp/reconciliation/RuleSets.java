package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The read side of the versioned rule sets (`P8-TSK-004`, ADR-0068 §8) — what the opener
 * needs: which version is {@code ACTIVE} for a source, and its lag days per kind.
 *
 * <p>Read <strong>without a lock</strong>, deliberately (ADR-0067 §7): a concurrent
 * activation (`P8-TSK-022`) shows either version, and either is valid, because the deciding
 * version is pinned on every row it dates ({@code rule_set_id NOT NULL}, {@code INV-HIST-04}).
 * Version 1 is seeded {@code ACTIVE} per source by `V002` and activation retires the prior
 * version in its own transaction, so exactly one version is always active — the opener
 * cannot fail to find one, and an absence is a defect that throws.
 */
public interface RuleSets {

    /**
     * The source's {@code ACTIVE} version.
     *
     * @throws RuleSetMissing when the source has none yet (`P9-TSK-011`)
     */
    ActiveRuleSet activeFor(Connection unitOfWork, UUID sourceId);

    /**
     * Whether the source has an {@code ACTIVE} version - the {@code rule_set.missing} gauge's read
     * (`P9-TSK-011`), in terms of {@link #activeFor} so every implementation answers alike.
     */
    default boolean hasActive(Connection unitOfWork, UUID sourceId) {
        try {
            activeFor(unitOfWork, sourceId);
            return true;
        } catch (RuleSetMissing missing) {
            return false;
        }
    }

    /** The active version's identity and the dating facts the opener reads off it. */
    record ActiveRuleSet(
            UUID id, int version, Map<ExpectationKind, Integer> lagDays, int fundingLagDays) {

        public ActiveRuleSet {
            Objects.requireNonNull(id, "id must not be null");
            lagDays = Map.copyOf(lagDays);
        }

        /** The lag for {@code kind} — seeded for every kind the source's evidence settles. */
        public int lagDaysFor(ExpectationKind kind) {
            Integer lag = lagDays.get(kind);
            if (lag == null) {
                throw new IllegalStateException(
                        "rule set " + id + " holds no lag for " + kind
                                + ": every kind a source's evidence settles is seeded"
                                + " (ADR-0068 §8), so this opener resolved the wrong source");
            }
            return lag;
        }
    }
}
