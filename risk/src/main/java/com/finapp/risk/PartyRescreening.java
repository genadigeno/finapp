package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Risk asks kyc for a party's screening against a list (`P13-TSK-001` declared it; `P13-TSK-022` reshaped and implemented
 * it; {@code PHASE_13_PLAN.md} section 12.10; ADR-0107 decision 3; {@code ROADMAP.md} Refinement 3; OQ-8; {@code
 * INV-RSK-02}) - <strong>risk asks, kyc screens</strong>. The rescreening leg calls it once per claimed permit, on
 * <strong>no risk transaction</strong> (the port's own, opened by its implementation), under the permit's key
 * ({@link #KEY_PREFIX} + the permit's id); {@code app} implements it through kyc's re-screening door.
 *
 * <p><strong>The answer is kyc's</strong> - never a verdict risk derives: kyc's answer to the permit's keyed screening of the
 * list's type for the party ({@link Screened}: kyc's screening id, whether it hit, when kyc was asked it and decided it),
 * a screening not yet decided ({@link Pending}), or a party with no {@code ACTIVE} or {@code PENDING} customer relationship
 * ({@link Inactive}), or an onboarding one with no KYC case ({@link NotOnboarded}). Whether the screened answer is a rescreening <em>under the list version</em> is risk's judgement of
 * currency ({@link RescreeningRun}): only a screening kyc was <em>asked</em> after the version came into force is one.
 *
 * <p><strong>The key binds kyc's question</strong> (the owner's decision O1, kyc {@code V011}): kyc's re-screening door asks a
 * post-decision {@code RESCREEN} check under it (and its retry ordinal), unique - so a re-driven permit, a lease lost
 * mid-call or ten sweepers meet one question and one provider call, and the answer is that keyed check's, never another
 * question's. Evidence beside a decided case, never a re-decision; on an undecided case its hit goes to kyc's review.
 */
@FunctionalInterface
public interface PartyRescreening {

    /** The prefix of the key every request for a permit presents - {@code risk.rescreen:<permit id>}. */
    String KEY_PREFIX = "risk.rescreen:";

    /**
     * kyc's answer to one claim's {@code question} - on the implementation's own transaction, never the caller's.
     *
     * @throws RuntimeException when kyc cannot be asked - the leg records the permit unavailable and backs it off
     */
    Answer ask(Question question);

    /** The request key of {@code permit}. */
    static String keyOf(RescreeningPermitId permit) {
        return KEY_PREFIX + Objects.requireNonNull(permit, "permit").value();
    }

    /**
     * The permit a rescreen key names - {@code risk.rescreen:<permit id>} and, as kyc stores it, its ordinal
     * {@code #n} - or empty for a key that is not a permit's (`P13-TSK-022`'s last review: a late hit told back to
     * risk by kyc's check, which carries the key).
     */
    static Optional<RescreeningPermitId> permitOf(String rescreenKey) {
        if (rescreenKey == null || !rescreenKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        String rest = rescreenKey.substring(KEY_PREFIX.length());
        int ordinal = rest.indexOf('#');
        String id = ordinal < 0 ? rest : rest.substring(0, ordinal);
        if (ordinal >= 0 && !rest.substring(ordinal + 1).matches("[0-9]{1,6}")) {
            return Optional.empty();
        }
        try {
            UUID permit = UUID.fromString(id);
            return permit.toString().equals(id) ? Optional.of(RescreeningPermitId.of(permit)) : Optional.empty();
        } catch (IllegalArgumentException notAPermit) {
            return Optional.empty();
        }
    }

    /**
     * One claim's question (`P13-TSK-022`'s review, IMPORTANT 2): the party, the list, the permit's key - and the leg's
     * bound on how often it is asked, so a provider outage or a restart mid-call never fails a permit early.
     *
     * <ul>
     *   <li>{@code attempt} - the claim's attempt (1 .. the leg's {@code maxAttempts}): the most distinct questions the
     *       permit may have asked by this claim - its key's ordinals {@code #0 .. #attempt-1}, one new ordinal per claim
     *       while the previous answered "we do not know" or was abandoned;</li>
     *   <li>{@code abandonedBefore} - the claim's own instant less one lease, both the database's: a question asked
     *       before it and still unanswered was abandoned (its asker lost the call - a crash, a shutdown mid-call), so the
     *       next ordinal is asked rather than waiting on it forever.</li>
     * </ul>
     */
    record Question(UUID partyId, ScreeningList list, String requestKey, int attempt, Instant abandonedBefore) {
        public Question {
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(list, "list");
            Objects.requireNonNull(requestKey, "requestKey");
            Objects.requireNonNull(abandonedBefore, "abandonedBefore");
            if (attempt < 1) {
                throw new IllegalArgumentException("a question is asked by a claim: its attempt is at least one");
            }
        }

        @Override
        public String toString() {
            // Codes only: the party is CONFIDENTIAL.
            return "Question[" + list + ", attempt " + attempt + "]";
        }
    }

    /** kyc's answer: closed, so the leg judges every case. */
    sealed interface Answer permits Screened, Pending, Inactive, NotOnboarded, Unavailable {}

    /**
     * kyc's latest decided screening of the list's type - its id, whether it hit, when kyc was asked it and when kyc
     * decided it. Four bounded facts: never kyc's row, never the provider's evidence, never a name.
     *
     * <p>{@code requestedAt} is what currency is judged by (`P13-TSK-022`'s review): a screening asked before a list
     * version came into force was asked against the list before it, however late its answer came - so a check dispatched
     * before the activation and decided after it is not a rescreening under the version.
     */
    record Screened(UUID screeningRef, boolean hit, Instant requestedAt, Instant decidedAt) implements Answer {
        public Screened {
            Objects.requireNonNull(screeningRef, "screeningRef");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(decidedAt, "decidedAt");
            if (decidedAt.isBefore(requestedAt)) {
                throw new IllegalArgumentException("a screening is decided no earlier than it was asked");
            }
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

    /** The party has no {@code ACTIVE} or {@code PENDING} customer relationship any more - nothing to rescreen. */
    record Inactive() implements Answer {}

    /**
     * The party's customer relationship is {@code PENDING} with no KYC case yet (`P13-TSK-022`'s review, IMPORTANT 4):
     * never screened, so nothing to rescreen - its onboarding, when it begins, screens it against the list in force. Not
     * an exposure, never a failure: counted on its own ({@code NOT_ONBOARDED}). An {@code ACTIVE} customer with no case
     * is never this - it was decided on nothing, and stays {@link Pending} {@code NOT_SCREENED}, failing counted.
     */
    record NotOnboarded() implements Answer {}

    /** kyc could not be asked - the leg's own answer when the port throws; never kyc's. */
    record Unavailable() implements Answer {}
}
