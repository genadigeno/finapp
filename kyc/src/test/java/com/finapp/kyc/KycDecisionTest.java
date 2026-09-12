package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision's own invariants (`P2-TSK-013`, {@code INV-KYC-02}) — what can never be
 * constructed, because a record that guards itself at birth needs no guard downstream.
 */
@DisplayName("a KYC decision (P2-TSK-013)")
class KycDecisionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("the automatic policy refuses a case with any non-CLEAR check, loudly")
    void theAutomaticPolicyRefusesANonClearCheck() {
        // The backlog's own test, at the domain (INV-LIFE-02's principle): reachability through
        // the assessment is not the control - the factory's refusal is, and the silent
        // alternative is an automatic approval of a case that owed a person a judgement
        // (INV-KYC-04).
        KycCase kycCase = aCase();
        for (CheckStatus nonClear :
                List.of(CheckStatus.HIT, CheckStatus.INDETERMINATE, CheckStatus.DISPATCHED)) {
            assertThatThrownBy(
                            () ->
                                    KycDecision.automatic(
                                            IDS,
                                            CLOCK,
                                            kycCase,
                                            List.of(check(CheckStatus.CLEAR), check(nonClear))))
                    .as("a %s check is not CLEAR, whatever else is", nonClear)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the automatic policy refuses a KYB case, whatever its checks say")
    void theAutomaticPolicyRefusesAKybCase() {
        // P2-TSK-015's policy, at the domain rather than only the orchestration branch: an
        // organisation's decision is a reviewer's judgement over the ownership graph.
        // Readiness requires every owner ANSWERED, not APPROVED - so an automatic all-clear
        // approval here could clear a terminal-REJECTED owner by silence (INV-KYC-04's shape).
        KycCase kybCase = KycCase.open(IDS, CLOCK, IDS.next(), KycCaseKind.KYB);
        assertThatThrownBy(
                        () ->
                                KycDecision.automatic(
                                        IDS, CLOCK, kybCase, List.of(check(CheckStatus.CLEAR))))
                .as("all-clear checks must not buy an organisation an automatic approval")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KYB");
    }

    @Test
    @DisplayName("an automatic decision is APPROVED, actorless, and carries the stated reason")
    void anAutomaticDecisionIsThePolicySpeaking() {
        KycCase kycCase = aCase();
        KycDecision decision =
                KycDecision.automatic(
                        IDS, CLOCK, kycCase, List.of(check(CheckStatus.CLEAR)));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVED);
        assertThat(decision.basis()).isEqualTo(DecisionBasis.AUTOMATIC);
        assertThat(decision.decidedBy()).isEmpty();
        assertThat(decision.reason()).isEqualTo(KycDecision.AUTOMATIC_APPROVAL_REASON);
        assertThat(decision.policyVersion())
                .as("the CASE's pinned regime, copied - never CURRENT re-read (INV-HIST-04)")
                .isEqualTo(kycCase.policyVersion());
        assertThat(decision.evidence()).hasSize(1);
    }

    @Test
    @DisplayName("a decision resting on nothing is unrepresentable")
    void aDecisionMustReferenceEvidence() {
        // The half of "NOT NULL evidence refs" the schema cannot express: non-emptiness of
        // another table's rows. Refused at birth instead.
        assertThatThrownBy(
                        () ->
                                KycDecision.byReviewer(
                                        IDS,
                                        CLOCK,
                                        aCase(),
                                        List.of(),
                                        IDS.next(),
                                        DecisionOutcome.REJECTED,
                                        "confirmed sanctions hit, ticket OPS-8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference the checks")
                .hasMessageContaining("INV-KYC-02");
    }

    @Test
    @DisplayName("the reason is bounded by the audit record's own bound")
    void theReasonIsBounded() {
        assertThatThrownBy(
                        () ->
                                KycDecision.byReviewer(
                                        IDS,
                                        CLOCK,
                                        aCase(),
                                        List.of(check(CheckStatus.HIT)),
                                        IDS.next(),
                                        DecisionOutcome.REJECTED,
                                        "z".repeat(AuditRecord.MAX_REASON_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                KycDecision.byReviewer(
                                        IDS,
                                        CLOCK,
                                        aCase(),
                                        List.of(check(CheckStatus.HIT)),
                                        IDS.next(),
                                        DecisionOutcome.REJECTED,
                                        "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                KycDecision.byReviewer(
                                        IDS,
                                        CLOCK,
                                        aCase(),
                                        List.of(check(CheckStatus.HIT)),
                                        IDS.next(),
                                        DecisionOutcome.REJECTED,
                                        "z".repeat(AuditRecord.MAX_REASON_LENGTH)))
                .as("the bound itself is legal - an off-by-one here is a 500 at the last write")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the outcome-to-case-status mapping covers both outcomes and lands on terminals")
    void theOutcomeMappingIsTotalAndTerminal() {
        for (DecisionOutcome outcome : DecisionOutcome.values()) {
            assertThat(outcome.caseStatus().isTerminal())
                    .as("%s must drive the case to a terminal (INV-LIFE-04)", outcome)
                    .isTrue();
        }
        assertThat(DecisionOutcome.APPROVED.caseStatus()).isEqualTo(KycCaseStatus.APPROVED);
        assertThat(DecisionOutcome.REJECTED.caseStatus()).isEqualTo(KycCaseStatus.REJECTED);
    }

    // -----------------------------------------------------------------

    private static KycCase aCase() {
        return KycCase.open(IDS, CLOCK, IDS.next(), KycCaseKind.KYC);
    }

    private static VerificationCheck check(CheckStatus status) {
        VerificationCheck fresh =
                VerificationCheck.request(IDS, CLOCK, KycCaseId.next(IDS), CheckType.SANCTIONS);
        if (status == CheckStatus.REQUESTED) {
            return fresh;
        }
        VerificationCheck dispatched =
                VerificationCheck.rehydrate(
                        fresh.id(),
                        fresh.caseId(),
                        fresh.type(),
                        CheckStatus.DISPATCHED,
                        fresh.requestedAt(),
                        Instant.now(CLOCK));
        if (status == CheckStatus.DISPATCHED) {
            return dispatched;
        }
        return VerificationCheck.rehydrate(
                fresh.id(),
                fresh.caseId(),
                fresh.type(),
                status,
                fresh.requestedAt(),
                Instant.now(CLOCK));
    }
}
