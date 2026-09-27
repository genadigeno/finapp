package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.DisputeReason;
import com.finapp.payments.DisputeStage;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The card PSP's dispute vocabulary (`P7-TSK-012`, {@code INV-PAY-03}'s state-mapping table
 * test): every word the door reads, where it goes, and the two defaults — an unknown stage word
 * moves NOTHING, an unknown reason code is {@code UNCATEGORISED}, never a guess.
 */
@DisplayName("the card PSP's dispute vocabulary (P7-TSK-012)")
class CardDisputeVocabularyTest {

    @Test
    @DisplayName("every stage word, pinned - and every stage of ours has a word")
    void everyStageWord() {
        assertThat(CardDisputeVocabulary.stageWords())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "warning_needs_response", DisputeStage.INQUIRY,
                                "warning_under_review", DisputeStage.INQUIRY,
                                "warning_closed", DisputeStage.CLOSED,
                                "needs_response", DisputeStage.CHARGED_BACK,
                                "under_review", DisputeStage.REPRESENTED,
                                "won", DisputeStage.WON,
                                "lost", DisputeStage.LOST,
                                "accepted", DisputeStage.ACCEPTED));
        assertThat(EnumSet.copyOf(CardDisputeVocabulary.stageWords().values()))
                .as("the network can say every stage the machine has")
                .isEqualTo(EnumSet.allOf(DisputeStage.class));
        CardDisputeVocabulary.stageWords()
                .forEach((word, stage) -> assertThat(CardDisputeVocabulary.stage(word))
                        .contains(stage));
    }

    @Test
    @DisplayName("INV-PAY-03's default: an unknown, absent or near-miss stage word is"
            + " indeterminate - nothing moves")
    void anUnknownStageWordMovesNothing() {
        for (String word :
                java.util.Arrays.asList(
                        null, "", "prevented", "charge_refunded", "WON", "Won", "won ",
                        "INQUIRY", "chargeback")) {
            assertThat(CardDisputeVocabulary.stage(word)).as("'%s'", word).isEmpty();
        }
    }

    @Test
    @DisplayName("every reason code, pinned to the networks' own groupings")
    void everyReasonCode() {
        assertThat(CardDisputeVocabulary.reasonCodes())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "fraudulent", DisputeReason.FRAUD,
                                "unrecognized", DisputeReason.FRAUD,
                                "debit_not_authorized", DisputeReason.AUTHORIZATION,
                                "duplicate", DisputeReason.PROCESSING_ERROR,
                                "product_not_received", DisputeReason.CONSUMER_DISPUTE,
                                "product_unacceptable", DisputeReason.CONSUMER_DISPUTE,
                                "subscription_canceled", DisputeReason.CONSUMER_DISPUTE,
                                "credit_not_processed", DisputeReason.CONSUMER_DISPUTE));
        CardDisputeVocabulary.reasonCodes()
                .forEach((code, reason) -> assertThat(CardDisputeVocabulary.reason(code))
                        .isEqualTo(reason));
        assertThat(CardDisputeVocabulary.reasonCodes().values())
                .as("the default is never a mapped value: UNCATEGORISED is only ever the"
                        + " fallback")
                .doesNotContain(DisputeReason.UNCATEGORISED);
    }

    @Test
    @DisplayName("an unknown or absent reason code is UNCATEGORISED - recorded as unknown,"
            + " never placed in a category")
    void anUnknownReasonIsUncategorised() {
        for (String code : java.util.Arrays.asList(null, "", "general", "FRAUDULENT", "10.4")) {
            assertThat(CardDisputeVocabulary.reason(code))
                    .as("'%s'", code)
                    .isEqualTo(DisputeReason.UNCATEGORISED);
        }
        assertThat(List.of(DisputeReason.values())).contains(DisputeReason.UNCATEGORISED);
    }
}
