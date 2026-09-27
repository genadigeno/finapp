package com.finapp.app.payments;

import com.finapp.payments.DisputeReason;
import com.finapp.payments.DisputeStage;
import java.util.Map;
import java.util.Optional;

/**
 * The card PSP's dispute words, confined to the webhook door that receives them (`P7-TSK-012`,
 * ADR-0061 §6, {@code INV-PAY-03}): its stage words and its reason codes, each through a
 * <strong>total</strong> table whose default is indeterminate — never a guess. The wire is the
 * simulated PSP's (ours end to end, Stripe-shaped); what crosses into {@code payments} is only
 * {@link DisputeStage} and {@link DisputeReason}, and the raw words rest verbatim in the
 * retained evidence (`INV-HIST-02`).
 *
 * <h2>Two defaults, deliberately different</h2>
 *
 * <p>An unknown <strong>stage word</strong> is unactionable: the stage decides what the
 * dispute is and, from `P7-TSK-013`, what money moves, so a word we cannot read moves nothing
 * and is counted as the integration break it is. An unknown <strong>reason code</strong> is
 * {@link DisputeReason#UNCATEGORISED}: the reason decides no money, so a new code from the
 * network never holds up the stage it arrived with — it is recorded as unknown, never placed.
 */
final class CardDisputeVocabulary {

    /**
     * The stage words (the Stripe dispute-status shape). The two {@code warning_*} words are
     * one stage: both are an inquiry, before and after the merchant's answer to it.
     */
    private static final Map<String, DisputeStage> STAGES =
            Map.of(
                    "warning_needs_response", DisputeStage.INQUIRY,
                    "warning_under_review", DisputeStage.INQUIRY,
                    "warning_closed", DisputeStage.CLOSED,
                    "needs_response", DisputeStage.CHARGED_BACK,
                    "under_review", DisputeStage.REPRESENTED,
                    "won", DisputeStage.WON,
                    "lost", DisputeStage.LOST,
                    "accepted", DisputeStage.ACCEPTED);

    /** The reason codes, each to the card networks' own grouping (DisputeReason's javadoc). */
    private static final Map<String, DisputeReason> REASONS =
            Map.of(
                    "fraudulent", DisputeReason.FRAUD,
                    "unrecognized", DisputeReason.FRAUD,
                    "debit_not_authorized", DisputeReason.AUTHORIZATION,
                    "duplicate", DisputeReason.PROCESSING_ERROR,
                    "product_not_received", DisputeReason.CONSUMER_DISPUTE,
                    "product_unacceptable", DisputeReason.CONSUMER_DISPUTE,
                    "subscription_canceled", DisputeReason.CONSUMER_DISPUTE,
                    "credit_not_processed", DisputeReason.CONSUMER_DISPUTE);

    private CardDisputeVocabulary() {}

    /** The stage the word names, or empty — nothing moves on a word we cannot read. */
    static Optional<DisputeStage> stage(String word) {
        return word == null ? Optional.empty() : Optional.ofNullable(STAGES.get(word));
    }

    /** The category the code maps to — {@code UNCATEGORISED} for anything else, absent included. */
    static DisputeReason reason(String code) {
        return code == null
                ? DisputeReason.UNCATEGORISED
                : REASONS.getOrDefault(code, DisputeReason.UNCATEGORISED);
    }

    /** Every stage word the door reads — the mapping table's test enumerates it. */
    static Map<String, DisputeStage> stageWords() {
        return STAGES;
    }

    /** Every reason code the door places — as {@link #stageWords()}. */
    static Map<String, DisputeReason> reasonCodes() {
        return REASONS;
    }
}
