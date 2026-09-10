package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The review-task machine and its opening act (`P2-TSK-010`, {@code INV-LIFE-01},
 * {@code INV-LIFE-04}). Tiny on purpose: two states, one edge — the resolution that walks it is
 * `P2-TSK-012`'s, and this pins the shape that task will build against.
 */
@DisplayName("ReviewTask (P2-TSK-010)")
class ReviewTaskTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS =
            new IdGenerator(CLOCK, new java.security.SecureRandom());

    @Test
    @DisplayName("OPEN leads only to RESOLVED, and RESOLVED is terminal - no unresolve")
    void theMachineHasOneEdgeAndOneEnd() {
        // A wrong resolution is a NEW review event on the case (the plan's own words), never a
        // reopened record - the record is what a decision may later rest on (INV-KYC-02).
        for (ReviewTaskStatus status : ReviewTaskStatus.values()) {
            for (ReviewTaskStatus target : ReviewTaskStatus.values()) {
                boolean permitted =
                        status == ReviewTaskStatus.OPEN && target == ReviewTaskStatus.RESOLVED;
                assertThat(status.canTransitionTo(target))
                        .as("%s -> %s", status, target)
                        .isEqualTo(permitted);
            }
        }
        assertThat(ReviewTaskStatus.RESOLVED.isTerminal()).isTrue();
        assertThat(ReviewTaskStatus.OPEN.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("a task opens OPEN, against its raising check, dated by the injected clock")
    void openingIsTheWholeAct() {
        KycCaseId caseId = KycCaseId.next(IDS);
        CheckId checkId = CheckId.next(IDS);

        ReviewTask task = ReviewTask.open(IDS, CLOCK, caseId, checkId);

        assertThat(task.status()).isEqualTo(ReviewTaskStatus.OPEN);
        assertThat(task.caseId()).isEqualTo(caseId);
        assertThat(task.checkId())
                .as("the task is 'resolve what THIS question raised' - the reference is what"
                        + " makes creation idempotent and the trail joinable")
                .isEqualTo(checkId);
        assertThat(task.openedAt()).isEqualTo(Instant.now(CLOCK));
    }
}
