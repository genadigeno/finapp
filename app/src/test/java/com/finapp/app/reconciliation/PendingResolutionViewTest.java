package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.reconciliation.ResolutionKind;
import com.finapp.reconciliation.ResolutionReasonCode;
import com.finapp.reconciliation.ResolutionStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One item of the approver's queue (`P17-TSK-107`; finapp-admin-panel ADR-C133 decision 3): the pending-decision shape, the caller's
 * own proposals marked, the subject a break or a repudiated batch, and the reason summarised by its code - never the narrative.
 */
@DisplayName("an item of the approver's queue (P17-TSK-107)")
class PendingResolutionViewTest {

    private static final Instant PROPOSED_AT = Instant.parse("2026-11-18T08:00:00.123456Z");

    @Test
    @DisplayName("a break's proposal: its kind under the domain, its break as the subject, the reason code as the summary")
    void aBreaksProposal() {
        UUID id = UUID.randomUUID();
        UUID breakId = UUID.randomUUID();
        ResolutionStore.PendingRow row = new ResolutionStore.PendingRow(id, Optional.of(breakId), Optional.empty(),
                ResolutionKind.ACKNOWLEDGE, ResolutionReasonCode.FEE_ACCEPTED_AS_CHARGED, "proposer-1", PROPOSED_AT);

        BreakResolutionDesk.PendingReconciliationResolution view = BreakResolutionDesk.pendingView(row, "approver-2");

        assertThat(view).isEqualTo(new BreakResolutionDesk.PendingReconciliationResolution(
                id.toString(), "reconciliation.ACKNOWLEDGE", "proposer-1", "2026-11-18T08:00:00.123456Z",
                "FEE_ACCEPTED_AS_CHARGED", breakId.toString(), "BREAK", false));
    }

    @Test
    @DisplayName("the caller's own proposal is marked, so an approver tells it apart before the door refuses its approval")
    void theCallersOwnIsMarked() {
        ResolutionStore.PendingRow row = new ResolutionStore.PendingRow(UUID.randomUUID(), Optional.of(UUID.randomUUID()),
                Optional.empty(), ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED, "proposer-1", PROPOSED_AT);

        assertThat(BreakResolutionDesk.pendingView(row, "proposer-1").proposedByCaller()).isTrue();
        assertThat(BreakResolutionDesk.pendingView(row, "someone-else").proposedByCaller()).isFalse();
    }

    @Test
    @DisplayName("a batch's repudiation names the batch as its subject")
    void aRepudiationNamesItsBatch() {
        UUID batch = UUID.randomUUID();
        ResolutionStore.PendingRow row = new ResolutionStore.PendingRow(UUID.randomUUID(), Optional.empty(), Optional.of(batch),
                ResolutionKind.REPUDIATE_BATCH, ResolutionReasonCode.EVIDENCE_REPUDIATED, "proposer-1", PROPOSED_AT);

        BreakResolutionDesk.PendingReconciliationResolution view = BreakResolutionDesk.pendingView(row, "approver-2");

        assertThat(view.subjectId()).isEqualTo(batch.toString());
        assertThat(view.subjectKind()).isEqualTo("SETTLEMENT_BATCH");
        assertThat(view.kind()).isEqualTo("reconciliation.REPUDIATE_BATCH");
    }
}
