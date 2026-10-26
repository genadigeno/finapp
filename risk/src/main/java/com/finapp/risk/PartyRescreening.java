package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Risk asks kyc for a party's screening against a list (`P13-TSK-001` declared it; `P13-TSK-022` reshaped and implemented
 * it; {@code PHASE_13_PLAN.md} section 12.10; ADR-0107 decision 3; {@code ROADMAP.md} Refinement 3; OQ-8; {@code
 * INV-RSK-02}) - <strong>risk asks, kyc screens</strong>. The rescreening leg calls it once per claimed permit, on
 * <strong>no risk transaction</strong> (the port's own, opened by its implementation), under the permit's key
 * ({@link #KEY_PREFIX} + the permit's id); {@code app} implements it over kyc's case-bound screening.
 *
 * <p><strong>The answer is kyc's, as of now</strong> - never a verdict risk derives: kyc's latest decided screening of the
 * list's type for the party ({@link Screened}: kyc's screening id, whether it hit, when kyc decided it), a screening not
 * yet decided ({@link Pending}), or a party with no {@code ACTIVE} customer relationship ({@link Inactive}). Whether the
 * screened answer is a rescreening <em>under the list version</em> is risk's judgement of currency
 * ({@link RescreeningRun}): only a screening kyc decided at or after the version's activation is one.
 *
 * <p><strong>The key is presented for kyc's door to come</strong> (`DESIGN-022` D1): kyc today has no door that screens a
 * decided party again - the case-bound check converges on an answered question, and a list update's re-verification is
 * a new case (`INV-LIFE-04`) that {@code app} does not open - so the implementation reads kyc's current screening and the
 * key binds nothing yet. A kyc re-screening door keyed by it slots in here and nowhere else.
 */
@FunctionalInterface
public interface PartyRescreening {

    /** The prefix of the key every request for a permit presents - {@code risk.rescreen:<permit id>}. */
    String KEY_PREFIX = "risk.rescreen:";

    /**
     * kyc's answer for {@code partyId} against {@code list}, requested under {@code requestKey} - on the
     * implementation's own transaction, never the caller's.
     *
     * @throws RuntimeException when kyc cannot be asked - the leg records the permit unavailable and backs it off
     */
    Answer ask(UUID partyId, ScreeningList list, String requestKey);

    /** The request key of {@code permit}. */
    static String keyOf(RescreeningPermitId permit) {
        return KEY_PREFIX + Objects.requireNonNull(permit, "permit").value();
    }

    /** kyc's answer: closed, so the leg judges every case. */
    sealed interface Answer permits Screened, Pending, Inactive, Unavailable {}

    /**
     * kyc's latest decided screening of the list's type - its id, whether it hit, when kyc decided it. Three bounded facts:
     * never kyc's row, never the provider's evidence, never a name.
     */
    record Screened(UUID screeningRef, boolean hit, Instant decidedAt) implements Answer {
        public Screened {
            Objects.requireNonNull(screeningRef, "screeningRef");
            Objects.requireNonNull(decidedAt, "decidedAt");
        }

        @Override
        public String toString() {
            return "Screened[hit=" + hit + "]";
        }
    }

    /** No decided screening of the list's type: one is in flight, or none was ever decided. */
    record Pending(Wait reason) implements Answer {
        public Pending {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Why a screening is pending. */
    enum Wait {
        /** A screening of the type is requested or dispatched and not yet answered. */
        IN_FLIGHT,
        /** The party's live customer holds no decided screening of the type - never screened, or only "we do not know". */
        NOT_SCREENED
    }

    /** The party has no {@code ACTIVE} customer relationship any more - nothing to rescreen. */
    record Inactive() implements Answer {}

    /** kyc could not be asked - the leg's own answer when the port throws; never kyc's. */
    record Unavailable() implements Answer {}
}
