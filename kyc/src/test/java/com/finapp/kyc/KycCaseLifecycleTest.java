package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The KycCase state machine, rejected <strong>by the aggregate</strong> ({@code INV-LIFE-02}).
 *
 * <p>The {@code CustomerLifecycleTest} idiom, and here the second-caller argument is not a
 * precaution but the design: this aggregate is built to be driven by consumers, provider
 * callbacks and reviewer tools from its first production caller, so the machine living anywhere
 * above the aggregate would protect none of them.
 *
 * <p><strong>Every transition is enumerated, not the ones somebody remembered.</strong> The
 * sweep walks the full cross product against {@link KycCaseStatus#permittedTransitions()}, so a
 * state added to the machine is swept the moment it exists.
 */
@DisplayName("KycCase lifecycle (P2-TSK-005)")
class KycCaseLifecycleTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS =
            new IdGenerator(CLOCK, new java.security.SecureRandom());

    @Test
    @DisplayName("a case opens OPEN, dated once, with the current policy pinned")
    void opensOpen() {
        KycCase kycCase = KycCase.open(IDS, CLOCK, IDS.next());

        assertThat(kycCase.status()).isEqualTo(KycCaseStatus.OPEN);
        assertThat(kycCase.openedAt()).isEqualTo(kycCase.statusChangedAt());
        // INV-HIST-04 at the moment it is free: which regime a case is assessed under is a fact
        // about the case, unrecoverable if not recorded when the case is born.
        assertThat(kycCase.policyVersion()).isEqualTo(KycPolicyVersion.CURRENT);
    }

    @Test
    @DisplayName("every state pair behaves exactly as the machine declares")
    void everyTransitionIsEnforced() {
        for (KycCaseStatus from : KycCaseStatus.values()) {
            for (KycCaseStatus to : KycCaseStatus.values()) {
                KycCase kycCase = at(from);
                boolean permitted = from.permittedTransitions().contains(to);

                if (permitted) {
                    assertThat(move(kycCase, to).status())
                            .as("%s -> %s is declared permitted and must succeed", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> move(kycCase, to))
                            .as("%s -> %s is not declared permitted and must be refused", from, to)
                            .isInstanceOf(IllegalKycCaseTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("APPROVED and REJECTED are terminal: nothing leads out, including themselves")
    void bothDecisionStatesAreTerminal() {
        // INV-LIFE-04, asserted separately from the sweep because it is the property the whole
        // phase depends on rather than a consequence of the table: a decided case that could be
        // re-decided makes "may this party transact?" ambiguous forever (INV-KYC-03's stated
        // harm), and the one-open-case index is built from exactly this terminal set.
        for (KycCaseStatus terminal : EnumSet.of(KycCaseStatus.APPROVED, KycCaseStatus.REJECTED)) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(terminal.permittedTransitions()).isEmpty();

            KycCase decided = at(terminal);
            for (KycCaseStatus target : KycCaseStatus.values()) {
                assertThatThrownBy(() -> move(decided, target))
                        .as("%s -> %s must be refused", terminal, target)
                        .isInstanceOf(IllegalKycCaseTransitionException.class)
                        .hasMessageContaining("terminal");
            }
        }
    }

    @Test
    @DisplayName("the machine has exactly the two terminal states, so the index predicate is right")
    void theTerminalSetIsTheDecisionPair() {
        // sqlTerminalValueList() generates the one-open-case index predicate, so this literal is
        // load-bearing on the schema side too - KycCaseMigrationTest reconciles the migration
        // against the same generator, and this pins the generator against the machine.
        assertThat(KycCaseStatus.sqlTerminalValueList()).isEqualTo("'APPROVED', 'REJECTED'");
    }

    @Test
    @DisplayName("a policy version is bounded and closed, because it reaches rows and log lines")
    void policyVersionIsBounded() {
        assertThatThrownBy(() -> new KycPolicyVersion("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KycPolicyVersion("x".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KycPolicyVersion("has spaces"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new KycPolicyVersion("kyc-2026.09").value()).isEqualTo("kyc-2026.09");
    }

    // -----------------------------------------------------------------

    /** A case standing at {@code status}, via rehydration — the storage path, not a back door. */
    private static KycCase at(KycCaseStatus status) {
        Instant opened = Instant.parse("2026-09-09T09:00:00Z");
        return KycCase.rehydrate(
                KycCaseId.next(IDS),
                IDS.next(),
                status,
                KycPolicyVersion.CURRENT,
                opened,
                opened.plusSeconds(60));
    }

    private static KycCase move(KycCase kycCase, KycCaseStatus to) {
        return switch (to) {
            case OPEN ->
                    // Nothing legally moves TO OPEN, and the machine says so; there is
                    // deliberately no aggregate method for it, so the sweep drives the check
                    // through the enum the way a hand-written caller could not.
                    refuse(kycCase, to);
            case CHECKS_IN_PROGRESS -> kycCase.beginChecks(CLOCK);
            case IN_REVIEW -> kycCase.requireReview(CLOCK);
            case READY_FOR_DECISION -> kycCase.readyForDecision(CLOCK);
            case APPROVED -> kycCase.approve(CLOCK);
            case REJECTED -> kycCase.reject(CLOCK);
        };
    }

    /**
     * Stands in for the aggregate method that does not exist: no method moves a case to
     * {@code to}, and the machine must agree that nothing may — this throws the same exception
     * the aggregate would, and the sweep's "permitted" branch fails loudly if the machine ever
     * declares such a move legal while no method exists to make it.
     */
    private static KycCase refuse(KycCase kycCase, KycCaseStatus to) {
        if (kycCase.status().canTransitionTo(to)) {
            throw new IllegalStateException(
                    "the machine permits a transition to "
                            + to
                            + " but the aggregate offers no method for it - add one");
        }
        throw new IllegalKycCaseTransitionException(kycCase.id(), kycCase.status(), to);
    }
}
