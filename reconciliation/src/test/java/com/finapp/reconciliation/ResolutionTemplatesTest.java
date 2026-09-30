package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The templates' pure seat (`P8-TSK-015`, ADR-0071 §2; ADR-0069 §2's per-type table): which
 * kinds each type admits, where each kind applies, the whole-residual amount, the derived
 * four-eyes flag and the exact lines — and the request's shape screen, judged before any
 * claim.
 */
@DisplayName("resolution templates (P8-TSK-015)")
class ResolutionTemplatesTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID POSITION = IDS.next();
    private static final LedgerAccountId LOSSES = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId GAINS = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId SUSPENSE = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId TARGET = LedgerAccountId.of(IDS.next());

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static ResolutionTemplates.Holding remainder(ExpectationDirection direction) {
        return new ResolutionTemplates.Holding.Remainder(
                IDS.next(), direction, eur(12_34), POSITION);
    }

    private static ResolutionTemplates.Holding parked(SuspenseSide side) {
        return new ResolutionTemplates.Holding.Parked(
                IDS.next(), Optional.of(IDS.next()), side, eur(56_78), POSITION);
    }

    @Test
    @DisplayName("ADR-0069 section 2's table: no gain where the value is somebody's, no"
            + " person kind on a statement cause, EVIDENCED and REPUDIATE_BATCH never a"
            + " type's person kind")
    void theAdmissionTableIsTheAdrs() {
        for (BreakType type : BreakType.values()) {
            for (BreakCause cause : BreakCause.values()) {
                Set<ResolutionKind> admitted = ResolutionTemplates.admittedKinds(type, cause);
                assertThat(admitted)
                        .doesNotContain(ResolutionKind.EVIDENCED, ResolutionKind.REPUDIATE_BATCH);
                if (type.mayOwnSuspense() && !admitted.isEmpty()
                        && type != BreakType.MISSING_EXTERNAL) {
                    assertThat(admitted)
                            .as("every suspense-owning type writes off a DEBIT item: " + type)
                            .contains(ResolutionKind.WRITE_OFF);
                }
            }
        }
        for (BreakType noGain :
                List.of(BreakType.REVERSAL_MISMATCH, BreakType.REFUND_MISMATCH,
                        BreakType.CURRENCY_MISMATCH)) {
            assertThat(ResolutionTemplates.admittedKinds(noGain, BreakCause.values()[0]))
                    .as(noGain + " admits no gain (the transition's A1)")
                    .doesNotContain(ResolutionKind.RECOGNISE_GAIN);
        }
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.SETTLEMENT_MISMATCH, BreakCause.REMITTANCE_DIFFERS))
                .containsExactlyInAnyOrder(
                        ResolutionKind.WRITE_OFF, ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionKind.RECOGNISE_GAIN);
        for (BreakCause cause : BreakCause.values()) {
            if (cause != BreakCause.REMITTANCE_DIFFERS) {
                assertThat(ResolutionTemplates.admittedKinds(BreakType.SETTLEMENT_MISMATCH,
                                cause))
                        .as("a statement cause closes only EVIDENCED: " + cause)
                        .isEmpty();
            }
        }
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.TIMING_DIFFERENCE, BreakCause.values()[0]))
                .containsExactly(ResolutionKind.ACKNOWLEDGE);
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.FEE_MISMATCH, BreakCause.values()[0]))
                .containsExactly(ResolutionKind.ACKNOWLEDGE);
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.AMBIGUOUS_MATCH, BreakCause.values()[0]))
                .contains(ResolutionKind.MANUAL_MATCH);
        for (BreakType type : BreakType.values()) {
            if (type != BreakType.AMBIGUOUS_MATCH) {
                assertThat(ResolutionTemplates.admittedKinds(type, BreakCause.values()[0]))
                        .as("MANUAL_MATCH stands in for the engine on ambiguity alone")
                        .doesNotContain(ResolutionKind.MANUAL_MATCH);
            }
        }
    }

    @Test
    @DisplayName("a kind's lines decide where it applies: a CREDIT item is never written off"
            + " and a DEBIT item never a gain")
    void theSideRuleIsTheLinesOwn() {
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.WRITE_OFF, remainder(ExpectationDirection.INBOUND))).isEmpty();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.WRITE_OFF, parked(SuspenseSide.DEBIT))).isEmpty();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.WRITE_OFF, parked(SuspenseSide.CREDIT))).isPresent();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.WRITE_OFF, remainder(ExpectationDirection.OUTBOUND)))
                .isPresent();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.TRANSFER_TO_ACCOUNT, parked(SuspenseSide.CREDIT))).isEmpty();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.TRANSFER_TO_ACCOUNT, remainder(ExpectationDirection.OUTBOUND)))
                .isEmpty();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.TRANSFER_TO_ACCOUNT, parked(SuspenseSide.DEBIT))).isPresent();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.RECOGNISE_GAIN, parked(SuspenseSide.CREDIT))).isEmpty();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.RECOGNISE_GAIN, parked(SuspenseSide.DEBIT))).isPresent();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.RECOGNISE_GAIN, remainder(ExpectationDirection.INBOUND)))
                .isPresent();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.OFFSET_SUSPENSE, remainder(ExpectationDirection.INBOUND)))
                .isPresent();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.ACKNOWLEDGE, new ResolutionTemplates.Holding.Nothing()))
                .isEmpty();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.WRITE_OFF, new ResolutionTemplates.Holding.Nothing()))
                .isPresent();
        assertThat(ResolutionTemplates.sideRefusal(
                ResolutionKind.WRITE_OFF,
                new ResolutionTemplates.Holding.Remainder(
                        IDS.next(), ExpectationDirection.INBOUND, eur(0), POSITION)))
                .as("an emptied subject holds nothing to dispose of")
                .isPresent();
    }

    @Test
    @DisplayName("the whole residual, never typed; four-eyes wherever value is at issue")
    void theAmountIsTheWholeResidual() {
        assertThat(ResolutionTemplates.amount(
                        ResolutionKind.WRITE_OFF, parked(SuspenseSide.DEBIT), eur(1)))
                .isEqualTo(eur(56_78));
        assertThat(ResolutionTemplates.amount(
                        ResolutionKind.TRANSFER_TO_ACCOUNT,
                        remainder(ExpectationDirection.OUTBOUND), eur(1)))
                .isEqualTo(eur(12_34));
        assertThat(ResolutionTemplates.amount(
                        ResolutionKind.ACKNOWLEDGE, new ResolutionTemplates.Holding.Nothing(),
                        eur(3_00)))
                .as("an acknowledgement disposes of nothing: its amount is the value at"
                        + " issue")
                .isEqualTo(eur(3_00));
        assertThat(ResolutionTemplates.fourEyes(ResolutionKind.ACKNOWLEDGE, eur(0))).isFalse();
        assertThat(ResolutionTemplates.fourEyes(ResolutionKind.ACKNOWLEDGE, eur(1))).isTrue();
        assertThat(ResolutionTemplates.fourEyes(ResolutionKind.EVIDENCED, eur(9))).isFalse();
        for (ResolutionKind kind :
                EnumSet.complementOf(EnumSet.of(ResolutionKind.ACKNOWLEDGE,
                        ResolutionKind.EVIDENCED))) {
            assertThat(ResolutionTemplates.fourEyes(kind, eur(0)))
                    .as(kind + " is always two people").isTrue();
        }
    }

    @Test
    @DisplayName("the lines: write-off DR losses, transfer CR the target, gain CR gains -"
            + " the subject's own position or suspense on the other side, balanced")
    void theLinesAreTheTemplates() {
        assertThat(lines(ResolutionKind.WRITE_OFF, remainder(ExpectationDirection.INBOUND)))
                .containsExactly(
                        new JournalLine(LOSSES, Direction.DEBIT, eur(12_34)),
                        new JournalLine(LedgerAccountId.of(POSITION), Direction.CREDIT,
                                eur(12_34)));
        assertThat(lines(ResolutionKind.WRITE_OFF, parked(SuspenseSide.DEBIT)))
                .containsExactly(
                        new JournalLine(LOSSES, Direction.DEBIT, eur(56_78)),
                        new JournalLine(SUSPENSE, Direction.CREDIT, eur(56_78)));
        assertThat(lines(ResolutionKind.TRANSFER_TO_ACCOUNT, parked(SuspenseSide.CREDIT)))
                .containsExactly(
                        new JournalLine(SUSPENSE, Direction.DEBIT, eur(56_78)),
                        new JournalLine(TARGET, Direction.CREDIT, eur(56_78)));
        assertThat(lines(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        remainder(ExpectationDirection.OUTBOUND)))
                .containsExactly(
                        new JournalLine(LedgerAccountId.of(POSITION), Direction.DEBIT,
                                eur(12_34)),
                        new JournalLine(TARGET, Direction.CREDIT, eur(12_34)));
        assertThat(lines(ResolutionKind.RECOGNISE_GAIN, parked(SuspenseSide.CREDIT)))
                .containsExactly(
                        new JournalLine(SUSPENSE, Direction.DEBIT, eur(56_78)),
                        new JournalLine(GAINS, Direction.CREDIT, eur(56_78)));
        for (ResolutionKind kind :
                EnumSet.of(ResolutionKind.ACKNOWLEDGE, ResolutionKind.OFFSET_SUSPENSE,
                        ResolutionKind.MANUAL_MATCH, ResolutionKind.EVIDENCED)) {
            assertThatThrownBy(() -> lines(kind, parked(SuspenseSide.CREDIT)))
                    .as(kind + " posts no adjustment")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static List<JournalLine> lines(
            ResolutionKind kind, ResolutionTemplates.Holding holding) {
        return ResolutionTemplates.lines(
                kind, holding, LOSSES, GAINS, SUSPENSE, Optional.of(TARGET));
    }

    // ----------------------------------------------------------------- the shape screen

    private static ResolutionMachine.ProposalRequest request(
            ResolutionKind kind, ResolutionReasonCode code, String narrative) {
        return new ResolutionMachine.ProposalRequest(
                kind, code, narrative,
                kind == ResolutionKind.TRANSFER_TO_ACCOUNT
                        ? Optional.of(IDS.next()) : Optional.empty(),
                kind == ResolutionKind.OFFSET_SUSPENSE
                        ? Optional.of(IDS.next()) : Optional.empty(),
                kind == ResolutionKind.MANUAL_MATCH
                        ? Optional.of(IDS.next()) : Optional.empty());
    }

    @Test
    @DisplayName("the shape screen: EVIDENCED and REPUDIATE_BATCH never through this door, a"
            + " code outside the kind's subset refused, operands exactly the kind's, the"
            + " narrative bounded and screened")
    void theShapeScreenRefusesBeforeAnyClaim() {
        assertThatThrownBy(() -> ResolutionMachine.refuseShape(request(
                        ResolutionKind.EVIDENCED, ResolutionReasonCode.EVIDENCE_RECEIVED,
                        "n")))
                .isInstanceOf(ResolutionMachine.ResolutionKindNotAllowed.class);
        assertThatThrownBy(() -> ResolutionMachine.refuseShape(request(
                        ResolutionKind.REPUDIATE_BATCH,
                        ResolutionReasonCode.EVIDENCE_REPUDIATED, "n")))
                .isInstanceOf(ResolutionMachine.ResolutionKindNotAllowed.class);
        for (ResolutionKind kind : ResolutionKind.admittedByV007()) {
            if (kind == ResolutionKind.EVIDENCED) {
                continue;
            }
            for (ResolutionReasonCode code : ResolutionReasonCode.values()) {
                ResolutionMachine.ProposalRequest shaped = request(kind, code, "why");
                if (kind.admittedReasonCodes().contains(code)) {
                    ResolutionMachine.refuseShape(shaped);
                } else {
                    assertThatThrownBy(() -> ResolutionMachine.refuseShape(shaped))
                            .as(kind + " refuses " + code)
                            .isInstanceOf(ResolutionMachine.ReasonCodeNotAllowed.class);
                }
            }
        }
        assertThatThrownBy(() -> ResolutionMachine.refuseShape(
                        new ResolutionMachine.ProposalRequest(
                                ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED,
                                "why", Optional.of(IDS.next()), Optional.empty(),
                                Optional.empty())))
                .as("a write-off names no target")
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class);
        assertThatThrownBy(() -> ResolutionMachine.refuseShape(
                        new ResolutionMachine.ProposalRequest(
                                ResolutionKind.TRANSFER_TO_ACCOUNT,
                                ResolutionReasonCode.FUNDS_ATTRIBUTED, "why", Optional.empty(),
                                Optional.empty(), Optional.empty())))
                .as("a transfer names its target")
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class);
        assertThatThrownBy(() -> ResolutionMachine.refuseShape(request(
                        ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED, " ")))
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class);
        assertThatThrownBy(() -> ResolutionMachine.refuseShape(request(
                        ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED,
                        "x".repeat(1001))))
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class);
        ResolutionMachine.refuseShape(request(
                ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED, "x".repeat(1000)));
        assertThatThrownBy(() -> ResolutionMachine.refuseShape(request(
                        ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED,
                        "card 4111111111111111 seen")))
                .as("a card-number shape never reaches the CONFIDENTIAL narrative")
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class)
                .hasMessageNotContaining("4111");
        assertThatThrownBy(() -> ResolutionMachine.refuseShape(request(
                        ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED,
                        "iban GB82WEST12345698765432")))
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class);
        assertThatThrownBy(() -> ResolutionMachine.refuseReason(""))
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class);
    }
}
