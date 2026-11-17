package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check state machine, rejected <strong>by the aggregate</strong> ({@code INV-LIFE-02}) —
 * the {@code KycCaseLifecycleTest} idiom: the full cross product against
 * {@link CheckStatus#permittedTransitions()}, so a state added to the machine is swept the
 * moment it exists.
 */
@DisplayName("VerificationCheck lifecycle (P2-TSK-009)")
class CheckLifecycleTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS =
            new IdGenerator(CLOCK, new java.security.SecureRandom());

    @Test
    @DisplayName("a check is requested REQUESTED, dated once")
    void requestsRequested() {
        VerificationCheck check =
                VerificationCheck.request(IDS, CLOCK, KycCaseId.next(IDS), CheckType.IDENTITY);

        assertThat(check.status()).isEqualTo(CheckStatus.REQUESTED);
        assertThat(check.requestedAt()).isEqualTo(check.statusChangedAt());
    }

    @Test
    @DisplayName("every state pair behaves exactly as the machine declares")
    void everyTransitionIsEnforced() {
        for (CheckStatus from : CheckStatus.values()) {
            for (CheckStatus to : CheckStatus.values()) {
                VerificationCheck check = at(from);
                boolean permitted = from.permittedTransitions().contains(to);

                if (permitted) {
                    assertThat(move(check, to).status())
                            .as("%s -> %s is declared permitted and must succeed", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> move(check, to))
                            .as("%s -> %s is not declared permitted and must be refused", from, to)
                            .isInstanceOf(IllegalCheckTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("the outcomes are terminal - INDETERMINATE included, so a check never flaps")
    void everyOutcomeIsTerminal() {
        // INV-LIFE-03's sharpest consequence: "we do not know" is an ANSWER, recorded as such,
        // and its resolution is a NEW check (ADR-0038) - a check that could move out of
        // INDETERMINATE would be a check whose evidence stops being true.
        for (CheckStatus terminal :
                EnumSet.of(CheckStatus.CLEAR, CheckStatus.HIT, CheckStatus.INDETERMINATE)) {
            assertThat(terminal.isTerminal()).isTrue();

            VerificationCheck answered = at(terminal);
            for (CheckStatus target : CheckStatus.values()) {
                assertThatThrownBy(() -> move(answered, target))
                        .as("%s -> %s must be refused", terminal, target)
                        .isInstanceOf(IllegalCheckTransitionException.class)
                        .hasMessageContaining("terminal");
            }
        }
    }

    @Test
    @DisplayName("the terminal set is the outcome set, so the one-in-flight predicate is right")
    void theTerminalSetIsTheOutcomeSet() {
        // sqlTerminalValueList() generates the one-in-flight index predicate;
        // VerificationCheckMigrationTest reconciles the migration against the same generator,
        // and this pins the generator against the machine. Every CheckOutcome maps onto exactly
        // this set, so "the outcomes" and "what frees the in-flight slot" are one definition.
        assertThat(CheckStatus.sqlTerminalValueList())
                .isEqualTo("'CLEAR', 'HIT', 'INDETERMINATE'");
        for (CheckOutcome outcome : CheckOutcome.values()) {
            assertThat(outcome.toStatus().isTerminal()).isTrue();
        }
    }

    // -----------------------------------------------------------------

    /** A check standing at {@code status}, via rehydration — the storage path, not a back door. */
    private static VerificationCheck at(CheckStatus status) {
        Instant requested = Instant.parse("2026-09-09T09:00:00Z");
        return VerificationCheck.rehydrate(
                CheckId.next(IDS),
                KycCaseId.next(IDS),
                CheckType.IDENTITY,
                status,
                requested,
                requested.plusSeconds(60));
    }

    /**
     * Drives the transition through the aggregate's own methods.
     *
     * <p>The tripwire: a target the machine permits but no method reaches would silently turn
     * the permitted half of the sweep vacuous, so it fails loudly instead.
     */
    @Test
    @DisplayName("P13-TSK-022: a check keeps its purpose through its machine - a request is ONBOARDING, a rescreen stays"
            + " RESCREEN dispatched and answered; a rescreen is a SANCTIONS or PEP screening, never another type")
    void thePurposeIsFrozen() {
        VerificationCheck onboarding =
                VerificationCheck.request(IDS, CLOCK, KycCaseId.next(IDS), CheckType.IDENTITY);
        assertThat(onboarding.purpose()).isEqualTo(CheckPurpose.ONBOARDING);
        assertThat(onboarding.onboarding()).isTrue();
        Instant asked = Instant.now(CLOCK);
        VerificationCheck rescreen =
                VerificationCheck.rehydrate(
                        CheckId.next(IDS),
                        KycCaseId.next(IDS),
                        CheckType.SANCTIONS,
                        CheckStatus.REQUESTED,
                        asked,
                        asked,
                        CheckPurpose.RESCREEN);
        VerificationCheck answered = rescreen.dispatch(CLOCK).complete(CheckOutcome.HIT, CLOCK);
        assertThat(answered.purpose()).isEqualTo(CheckPurpose.RESCREEN);
        assertThat(answered.onboarding()).isFalse();
        assertThat(answered.toString()).endsWith(", RESCREEN]");
        assertThatThrownBy(
                        () ->
                                VerificationCheck.rehydrate(
                                        CheckId.next(IDS),
                                        KycCaseId.next(IDS),
                                        CheckType.IDENTITY,
                                        CheckStatus.REQUESTED,
                                        asked,
                                        asked,
                                        CheckPurpose.RESCREEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CheckPurpose.sqlValueList()).isEqualTo("'ONBOARDING', 'RESCREEN'");
    }

    @Test
    @DisplayName(
            "an answer on a clock trailing the dispatch is stamped at the dispatch's instant, never"
                    + " before it; a clock past it stamps its own read (X-TSK-006, ADR-0063"
                    + " decision 2)")
    void anAnswerOnATrailingClockIsStampedAtTheDispatch() {
        // Requested at 09:00, dispatched at 09:01 - by an instance whose clock led this one's.
        Instant requested = Instant.parse("2026-09-09T09:00:00Z");
        Instant dispatchedAt = requested.plusSeconds(60);
        VerificationCheck dispatched =
                VerificationCheck.rehydrate(
                        CheckId.next(IDS),
                        KycCaseId.next(IDS),
                        CheckType.IDENTITY,
                        CheckStatus.DISPATCHED,
                        requested,
                        dispatchedAt);
        Clock trailing = Clock.offset(Clock.fixed(dispatchedAt, ZoneOffset.UTC), Duration.ofSeconds(-5));
        assertThat(Instant.now(trailing))
                .as("precondition: the clock trails the dispatch, and not only the request")
                .isBefore(dispatchedAt)
                .isAfter(requested);

        VerificationCheck answered = dispatched.complete(CheckOutcome.CLEAR, trailing);
        assertThat(answered.status()).isEqualTo(CheckStatus.CLEAR);
        assertThat(answered.statusChangedAt()).as("the predecessor's instant").isEqualTo(dispatchedAt);

        // A floor, not a pin.
        Clock ahead = Clock.fixed(dispatchedAt.plusSeconds(5), ZoneOffset.UTC);
        assertThat(dispatched.complete(CheckOutcome.CLEAR, ahead).statusChangedAt())
                .isEqualTo(dispatchedAt.plusSeconds(5));
    }

    private static VerificationCheck move(VerificationCheck check, CheckStatus to) {
        return switch (to) {
            case DISPATCHED -> check.dispatch(CLOCK);
            case CLEAR -> check.complete(CheckOutcome.CLEAR, CLOCK);
            case HIT -> check.complete(CheckOutcome.HIT, CLOCK);
            case INDETERMINATE -> check.complete(CheckOutcome.INDETERMINATE, CLOCK);
            case REQUESTED ->
                    throw new IllegalCheckTransitionException(
                            check.id(), check.status(), CheckStatus.REQUESTED);
        };
    }
}
