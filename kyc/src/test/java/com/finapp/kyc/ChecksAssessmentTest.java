package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform's own reading of a case's checks ({@code INV-KYC-01}, {@code INV-KYC-04}) — the
 * rule that stands between a provider's answer and the case's status.
 */
@DisplayName("ChecksAssessment (P2-TSK-009)")
class ChecksAssessmentTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS =
            new IdGenerator(CLOCK, new java.security.SecureRandom());
    private static final Set<CheckType> REQUIRED =
            EnumSet.of(CheckType.IDENTITY, CheckType.DOCUMENT);

    private final KycCaseId caseId = KycCaseId.next(IDS);

    @Test
    @DisplayName("every required type CLEAR, nothing in flight, no hit: clear to proceed")
    void allClearProceeds() {
        assertThat(
                        ChecksAssessment.of(
                                REQUIRED,
                                List.of(
                                        at(CheckType.IDENTITY, CheckStatus.CLEAR),
                                        at(CheckType.DOCUMENT, CheckStatus.CLEAR))))
                .isEqualTo(ChecksAssessment.CLEAR_TO_PROCEED);
    }

    @Test
    @DisplayName("a HIT blocks, whatever else the checks say - even a later CLEAR of its own type")
    void aHitBlocksEverything() {
        // INV-KYC-04: a hit is resolved by a PERSON, never by a retry that happened to pass.
        // A later CLEAR of the same type un-blocking the case would be exactly the silent
        // clearance the invariant names as a sanctions breach.
        assertThat(
                        ChecksAssessment.of(
                                REQUIRED,
                                List.of(
                                        at(CheckType.IDENTITY, CheckStatus.CLEAR),
                                        at(CheckType.DOCUMENT, CheckStatus.HIT),
                                        at(CheckType.DOCUMENT, CheckStatus.CLEAR))))
                .isEqualTo(ChecksAssessment.BLOCKED);
    }

    @Test
    @DisplayName("anything in flight, or a required type unanswered, is incomplete")
    void inFlightOrMissingIsIncomplete() {
        assertThat(
                        ChecksAssessment.of(
                                REQUIRED,
                                List.of(
                                        at(CheckType.IDENTITY, CheckStatus.CLEAR),
                                        at(CheckType.DOCUMENT, CheckStatus.DISPATCHED))))
                .isEqualTo(ChecksAssessment.INCOMPLETE);
        assertThat(
                        ChecksAssessment.of(
                                REQUIRED, List.of(at(CheckType.IDENTITY, CheckStatus.CLEAR))))
                .as("a required type with no check at all is unanswered, not passed")
                .isEqualTo(ChecksAssessment.INCOMPLETE);
    }

    @Test
    @DisplayName("INDETERMINATE alone is incomplete - never a pass, never a refusal")
    void indeterminateDecidesNothing() {
        // INV-LIFE-03: "we do not know" is recorded as exactly that. The case does not decide.
        assertThat(
                        ChecksAssessment.of(
                                REQUIRED,
                                List.of(
                                        at(CheckType.IDENTITY, CheckStatus.CLEAR),
                                        at(CheckType.DOCUMENT, CheckStatus.INDETERMINATE))))
                .isEqualTo(ChecksAssessment.INCOMPLETE);
    }

    @Test
    @DisplayName("an INDETERMINATE followed by a CLEAR of the same type proceeds")
    void resolutionIsANewCheck() {
        // ADR-0038 made real: the failed attempt's evidence stays true, and the new check is
        // what answers the question.
        assertThat(
                        ChecksAssessment.of(
                                REQUIRED,
                                List.of(
                                        at(CheckType.IDENTITY, CheckStatus.CLEAR),
                                        at(CheckType.DOCUMENT, CheckStatus.INDETERMINATE),
                                        at(CheckType.DOCUMENT, CheckStatus.CLEAR))))
                .isEqualTo(ChecksAssessment.CLEAR_TO_PROCEED);
    }

    @Test
    @DisplayName("an empty requirement is refused - absence of questions must never assess")
    void emptyRequirementIsRefused() {
        // A run with no required types would assess every case as complete by absence of
        // evidence, which is a decision by silence - the exact thing this phase forbids.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChecksAssessment.of(EnumSet.noneOf(CheckType.class), List.of()));
    }

    @Test
    @DisplayName("a budget-exhausted INDETERMINATE blocks, and review names the newest unknown")
    void exhaustedIndeterminateGoesToAPerson() {
        // P2-TSK-010: after the budget the platform stops asking machines and asks a person -
        // silence resolves nothing in either direction (INV-KYC-04). The newest unknown is what
        // the reviewer starts from; its predecessors stay true beside it (ADR-0038).
        VerificationCheck newestUnknown =
                at(CheckType.DOCUMENT, CheckStatus.INDETERMINATE, minutesLater(2));
        List<VerificationCheck> checks =
                List.of(
                        at(CheckType.IDENTITY, CheckStatus.CLEAR, minutesLater(0)),
                        at(CheckType.DOCUMENT, CheckStatus.INDETERMINATE, minutesLater(0)),
                        at(CheckType.DOCUMENT, CheckStatus.INDETERMINATE, minutesLater(1)),
                        newestUnknown);

        assertThat(ChecksAssessment.of(REQUIRED, checks)).isEqualTo(ChecksAssessment.BLOCKED);
        assertThat(ChecksAssessment.needingReview(REQUIRED, checks))
                .containsExactly(newestUnknown);
    }

    @Test
    @DisplayName("an INDETERMINATE under budget stays incomplete - still answerable by a retry")
    void underBudgetIsStillIncomplete() {
        List<VerificationCheck> checks =
                List.of(
                        at(CheckType.IDENTITY, CheckStatus.CLEAR, minutesLater(0)),
                        at(CheckType.DOCUMENT, CheckStatus.INDETERMINATE, minutesLater(0)),
                        at(CheckType.DOCUMENT, CheckStatus.INDETERMINATE, minutesLater(1)));

        assertThat(ChecksAssessment.of(REQUIRED, checks)).isEqualTo(ChecksAssessment.INCOMPLETE);
        assertThat(ChecksAssessment.needingReview(REQUIRED, checks))
                .as("BLOCKED and needingReview must be one judgement: not blocked, no tasks")
                .isEmpty();
    }

    @Test
    @DisplayName("a later CLEAR satisfies an exhausted type - the asymmetry with a HIT, on purpose")
    void aLaterClearSatisfiesAnExhaustedType() {
        // A hit is an ANSWER that demands a person, so a CLEAR never un-blocks it
        // (aHitBlocksEverything). Exhaustion is the ABSENCE of an answer, and an answer arriving
        // ends the absence - resolution-is-a-new-check working (ADR-0038).
        assertThat(
                        ChecksAssessment.of(
                                REQUIRED,
                                List.of(
                                        at(CheckType.IDENTITY, CheckStatus.CLEAR, minutesLater(0)),
                                        at(
                                                CheckType.DOCUMENT,
                                                CheckStatus.INDETERMINATE,
                                                minutesLater(0)),
                                        at(
                                                CheckType.DOCUMENT,
                                                CheckStatus.INDETERMINATE,
                                                minutesLater(1)),
                                        at(
                                                CheckType.DOCUMENT,
                                                CheckStatus.INDETERMINATE,
                                                minutesLater(2)),
                                        at(CheckType.DOCUMENT, CheckStatus.CLEAR, minutesLater(3)))))
                .isEqualTo(ChecksAssessment.CLEAR_TO_PROCEED);
    }

    @Test
    @DisplayName("review on a hit names the hit check - every one of them")
    void reviewOnAHitNamesTheHit() {
        VerificationCheck hit = at(CheckType.DOCUMENT, CheckStatus.HIT, minutesLater(1));
        List<VerificationCheck> checks =
                List.of(at(CheckType.IDENTITY, CheckStatus.CLEAR, minutesLater(0)), hit);

        assertThat(ChecksAssessment.needingReview(REQUIRED, checks)).containsExactly(hit);
    }

    private VerificationCheck at(CheckType type, CheckStatus status) {
        return at(type, status, Instant.parse("2026-09-09T09:00:00Z"));
    }

    private VerificationCheck at(CheckType type, CheckStatus status, Instant requested) {
        return VerificationCheck.rehydrate(
                CheckId.next(IDS), caseId, type, status, requested, requested.plusSeconds(1));
    }

    private static Instant minutesLater(int minutes) {
        return Instant.parse("2026-09-09T09:00:00Z").plusSeconds(60L * minutes);
    }
}
