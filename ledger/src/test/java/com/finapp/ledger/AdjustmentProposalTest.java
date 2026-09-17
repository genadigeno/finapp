package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proposal aggregate's own rules (`P3-TSK-021`, {@code INV-AUD-04},
 * {@code INV-LIFE-02}): self-approval is refused <em>by the aggregate</em> — not merely
 * unreachable through the API — because every aggregate is eventually driven by a second
 * caller, and the second caller of this one will be exactly the kind of operator tooling
 * four-eyes exists to constrain.
 */
@DisplayName("the adjustment proposal refuses what four-eyes forbids (P3-TSK-021)")
class AdjustmentProposalTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final String INITIATOR = "initiator-1";
    private static final String APPROVER = "approver-2";

    @Test
    @DisplayName("the initiator cannot approve their own proposal (INV-AUD-04's negative)")
    void selfApprovalIsRefusedByTheAggregate() {
        AdjustmentProposal proposal = proposed();
        assertThatThrownBy(() -> proposal.requireApprovableBy(INITIATOR))
                .isInstanceOf(SelfApprovalRefusedException.class);
        // The positive control, so the refusal is not blanket: a second person may.
        assertThatCode(() -> proposal.requireApprovableBy(APPROVER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("both terminal states refuse every further decision (INV-LIFE-04)")
    void terminalStatesAreTerminal() {
        for (AdjustmentProposal decided : List.of(approved(), rejected())) {
            assertThatThrownBy(() -> decided.requireApprovableBy(APPROVER))
                    .isInstanceOf(AdjustmentProposalNotOpenException.class);
            assertThatThrownBy(decided::requireRejectable)
                    .isInstanceOf(AdjustmentProposalNotOpenException.class);
        }
        // The machine itself says so, derived rather than listed: PROPOSED has exactly the
        // two decision edges, and neither terminal has any.
        assertThat(AdjustmentProposalStatus.PROPOSED.permittedTransitions())
                .containsExactlyInAnyOrder(
                        AdjustmentProposalStatus.APPROVED, AdjustmentProposalStatus.REJECTED);
        assertThat(AdjustmentProposalStatus.APPROVED.isTerminal()).isTrue();
        assertThat(AdjustmentProposalStatus.REJECTED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("an approved proposal naming one person twice is unconstructible")
    void anApprovedProposalAlwaysNamesTwoPeople() {
        assertThatThrownBy(
                        () ->
                                decided(
                                        AdjustmentProposalStatus.APPROVED,
                                        INITIATOR,
                                        Optional.of(JournalEntryId.next(IDS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-AUD-04");
        // A REJECTED proposal MAY name one person twice: withdrawal is the initiator's own
        // act, deliberately (removing an action is not performing one).
        assertThatCode(
                        () ->
                                decided(
                                        AdjustmentProposalStatus.REJECTED,
                                        INITIATOR,
                                        Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a reason-less or single-sided proposal is unconstructible")
    void theProposalRefusesWhatCouldNeverPost() {
        assertThatThrownBy(
                        () ->
                                AdjustmentProposal.propose(
                                        AdjustmentProposalId.next(IDS),
                                        LocalDate.parse("2026-09-17"),
                                        LocalDate.parse("2026-09-17"),
                                        "adj-ref",
                                        "   ",
                                        INITIATOR,
                                        lines(),
                                        CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-REV-04");
        assertThatThrownBy(
                        () ->
                                AdjustmentProposal.propose(
                                        AdjustmentProposalId.next(IDS),
                                        LocalDate.parse("2026-09-17"),
                                        LocalDate.parse("2026-09-17"),
                                        "adj-ref",
                                        "a real reason",
                                        INITIATOR,
                                        lines().subList(0, 1),
                                        CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-LED-02");
    }

    @Test
    @DisplayName("no rendering carries the reason (INV-AUD-02: a person's free prose)")
    void theReasonReachesNoRendering() {
        AdjustmentProposal proposal = proposed();
        assertThat(proposal.toString()).doesNotContain("NEEDLE-a-person-wrote-this");
        assertThat(new SelfApprovalRefusedException(proposal.id()).getMessage())
                .doesNotContain("NEEDLE-a-person-wrote-this");
        assertThat(
                        new AdjustmentProposalNotOpenException(
                                        proposal.id(), AdjustmentProposalStatus.APPROVED)
                                .getMessage())
                .doesNotContain("NEEDLE-a-person-wrote-this");
    }

    private static AdjustmentProposal proposed() {
        return AdjustmentProposal.propose(
                AdjustmentProposalId.next(IDS),
                LocalDate.parse("2026-09-17"),
                LocalDate.parse("2026-09-17"),
                "adj-ref",
                "NEEDLE-a-person-wrote-this",
                INITIATOR,
                lines(),
                CLOCK);
    }

    private static AdjustmentProposal approved() {
        return decided(
                AdjustmentProposalStatus.APPROVED,
                APPROVER,
                Optional.of(JournalEntryId.next(IDS)));
    }

    private static AdjustmentProposal rejected() {
        return decided(AdjustmentProposalStatus.REJECTED, APPROVER, Optional.empty());
    }

    private static AdjustmentProposal decided(
            AdjustmentProposalStatus status,
            String decidedBy,
            Optional<JournalEntryId> entry) {
        return new AdjustmentProposal(
                AdjustmentProposalId.next(IDS),
                status,
                LocalDate.parse("2026-09-17"),
                LocalDate.parse("2026-09-17"),
                "adj-ref",
                "NEEDLE-a-person-wrote-this",
                INITIATOR,
                Instant.now(CLOCK),
                lines(),
                Optional.of(decidedBy),
                Optional.of(Instant.now(CLOCK)),
                entry);
    }

    private static List<JournalLine> lines() {
        return List.of(
                new JournalLine(
                        LedgerAccountId.of(IDS.next()),
                        Direction.DEBIT,
                        Money.ofMinorUnits(500, USD)),
                new JournalLine(
                        LedgerAccountId.of(IDS.next()),
                        Direction.CREDIT,
                        Money.ofMinorUnits(500, USD)));
    }
}
