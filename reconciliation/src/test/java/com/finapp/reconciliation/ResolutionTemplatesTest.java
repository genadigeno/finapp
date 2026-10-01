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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The templates' pure seat (`P8-TSK-015`, ADR-0071 §2; ADR-0069 §2's per-type table): which
 * kinds each type admits, where each kind applies, the whole-residual amount, the derived
 * four-eyes flag and the exact lines — and the request's shape screen, judged before any
 * claim. `P8-TST-002` pins the admission table EXACTLY against a hand transcription of
 * ADR-0069 §2 (so neither the code nor the table can change silently), and the four-eyes
 * derivation's correction: a zero-value acknowledgement is one person's only on a
 * {@code TIMING_DIFFERENCE}.
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
                // A diverged replay's break stands on a DECISION and owns no value
                // (P8-TSK-022): its one disposal is the acknowledgement asserted below.
                // Keyed on the cause, whatever the type it was reclassified onto (P8-TST-002).
                boolean divergence = cause == BreakCause.REPLAY_DIVERGED;
                if (type.mayOwnSuspense() && !admitted.isEmpty()
                        && type != BreakType.MISSING_EXTERNAL && !divergence) {
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
            // REPLAY_DIVERGED's refinement is its cause's whatever the type (P8-TST-002).
            if (cause != BreakCause.REMITTANCE_DIFFERS && cause != BreakCause.REPLAY_DIVERGED) {
                assertThat(ResolutionTemplates.admittedKinds(BreakType.SETTLEMENT_MISMATCH,
                                cause))
                        .as("a statement cause closes only EVIDENCED: " + cause)
                        .isEmpty();
            }
        }
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.DUPLICATE_EXTERNAL, BreakCause.EXECUTION_ALREADY_EXPLAINED))
                .as("a parking whose execution a credit already explains is never attributed"
                        + " twice (P8-TSK-020, ADR-0070 point 8)")
                .doesNotContain(ResolutionKind.TRANSFER_TO_ACCOUNT)
                .containsExactlyInAnyOrder(
                        ResolutionKind.WRITE_OFF, ResolutionKind.OFFSET_SUSPENSE,
                        ResolutionKind.RECOGNISE_GAIN);
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.DUPLICATE_EXTERNAL, BreakCause.REPEATED_FINGERPRINT))
                .as("the refinement is the cause's alone")
                .contains(ResolutionKind.TRANSFER_TO_ACCOUNT);
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT))
                .as("an unattributed or attributed parking goes to its owner by a transfer")
                .contains(ResolutionKind.TRANSFER_TO_ACCOUNT, ResolutionKind.RECOGNISE_GAIN);
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.TIMING_DIFFERENCE, BreakCause.values()[0]))
                .containsExactly(ResolutionKind.ACKNOWLEDGE);
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.PROCESSING_ERROR, BreakCause.REPLAY_DIVERGED))
                .as("a diverged replay is acknowledged once investigated - it holds no value"
                        + " (P8-TSK-022)")
                .containsExactly(ResolutionKind.ACKNOWLEDGE);
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.PROCESSING_ERROR, BreakCause.ITEM_ERRORED))
                .as("the refinement is the cause's alone")
                .contains(ResolutionKind.WRITE_OFF);
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
        assertThat(EnumSet.copyOf(java.util.Arrays.stream(BreakCause.values())
                        .filter(ResolutionTemplates::timingCause).toList()))
                .as("the timing detectors: exactly the causes raising TIMING_DIFFERENCE")
                .containsExactlyInAnyOrder(BreakCause.LATE_MATCH, BreakCause.CYCLE_MISMATCH);
        assertThat(ResolutionTemplates.fourEyes(ResolutionKind.ACKNOWLEDGE, eur(1),
                        BreakType.TIMING_DIFFERENCE, BreakCause.LATE_MATCH))
                .as("one unit at issue is two people's").isTrue();
        // Every (type, cause) combination - a superset of what a reclassification can reach,
        // because the cause is frozen at raise and the type moves (P8-TST-002's gate find).
        for (BreakType type : BreakType.values()) {
            for (BreakCause cause : BreakCause.values()) {
                assertThat(ResolutionTemplates.fourEyes(ResolutionKind.EVIDENCED, eur(9), type,
                                cause))
                        .as("EVIDENCED is the platform's: %s/%s", type, cause).isFalse();
                boolean onePerson = type == BreakType.TIMING_DIFFERENCE
                        && (cause == BreakCause.LATE_MATCH
                                || cause == BreakCause.CYCLE_MISMATCH);
                assertThat(ResolutionTemplates.fourEyes(ResolutionKind.ACKNOWLEDGE, eur(0), type,
                                cause))
                        .as("a zero-value ACKNOWLEDGE of a %s raised by %s is %s", type, cause,
                                onePerson ? "one person's" : "four-eyes (the correction)")
                        .isEqualTo(!onePerson);
                for (ResolutionKind kind :
                        EnumSet.complementOf(EnumSet.of(ResolutionKind.ACKNOWLEDGE,
                                ResolutionKind.EVIDENCED))) {
                    assertThat(ResolutionTemplates.fourEyes(kind, eur(0), type, cause))
                            .as("%s is always two people (%s/%s)", kind, type, cause).isTrue();
                }
            }
        }
    }

    // ----------------------------------------------------------------- the exact table

    /**
     * ADR-0069 §2's per-type table, transcribed by hand - the kinds each type admits for its
     * ordinary causes. Kept apart from the code it pins: a change to either fails here.
     */
    private static final Map<BreakType, Set<ResolutionKind>> TABLE = table();

    /** The cause refinements the table's prose and P8-TSK-020/-022 add, exactly. */
    private static final Map<BreakCause, Set<ResolutionKind>> REFINED = Map.of(
            BreakCause.EXECUTION_ALREADY_EXPLAINED,
            EnumSet.of(ResolutionKind.WRITE_OFF, ResolutionKind.OFFSET_SUSPENSE,
                    ResolutionKind.RECOGNISE_GAIN),
            BreakCause.STATEMENT_GAP, EnumSet.noneOf(ResolutionKind.class),
            BreakCause.OPENING_BALANCE, EnumSet.noneOf(ResolutionKind.class),
            BreakCause.REPLAY_DIVERGED, EnumSet.of(ResolutionKind.ACKNOWLEDGE));

    private static Map<BreakType, Set<ResolutionKind>> table() {
        ResolutionKind ack = ResolutionKind.ACKNOWLEDGE;
        ResolutionKind writeOff = ResolutionKind.WRITE_OFF;
        ResolutionKind transfer = ResolutionKind.TRANSFER_TO_ACCOUNT;
        ResolutionKind offset = ResolutionKind.OFFSET_SUSPENSE;
        ResolutionKind gain = ResolutionKind.RECOGNISE_GAIN;
        Map<BreakType, Set<ResolutionKind>> table = new EnumMap<>(BreakType.class);
        table.put(BreakType.MISSING_EXTERNAL, EnumSet.of(writeOff, transfer));
        table.put(BreakType.MISSING_INTERNAL, EnumSet.of(transfer, writeOff, offset, gain));
        table.put(BreakType.UNKNOWN_EXTERNAL, EnumSet.of(transfer, writeOff, offset, gain));
        table.put(BreakType.AMOUNT_MISMATCH, EnumSet.of(writeOff, transfer, gain));
        table.put(BreakType.CURRENCY_MISMATCH, EnumSet.of(transfer, writeOff, offset));
        table.put(BreakType.FEE_MISMATCH, EnumSet.of(ack));
        table.put(BreakType.DUPLICATE_EXTERNAL, EnumSet.of(offset, transfer, writeOff, gain));
        table.put(BreakType.DUPLICATE_INTERNAL, EnumSet.of(ack, writeOff));
        table.put(BreakType.AMBIGUOUS_MATCH,
                EnumSet.of(ResolutionKind.MANUAL_MATCH, transfer, writeOff, gain));
        table.put(BreakType.TIMING_DIFFERENCE, EnumSet.of(ack));
        table.put(BreakType.REVERSAL_MISMATCH, EnumSet.of(transfer, offset, writeOff));
        table.put(BreakType.REFUND_MISMATCH, EnumSet.of(writeOff, transfer));
        // REMITTANCE_DIFFERS, its one ordinary cause; the statement causes are REFINED.
        table.put(BreakType.SETTLEMENT_MISMATCH, EnumSet.of(writeOff, transfer, gain));
        table.put(BreakType.PROCESSING_ERROR, EnumSet.of(transfer, offset, writeOff, gain));
        return table;
    }

    @Test
    @DisplayName("ADR-0069 section 2's table, EXACTLY: every (type, cause) a detector raises admits"
            + " precisely its row's kinds - 40 (type, kind) pairs over the 14 rows, 44 table cells"
            + " refused over the six template kinds - and the four cause refinements, keyed on the"
            + " CAUSE over every type a reclassification can move the break onto")
    void theAdmissionTableIsExactlyTheAdrs() {
        assertThat(TABLE).as("all fourteen rows transcribed").hasSize(BreakType.values().length);
        int pairs = TABLE.values().stream().mapToInt(Set::size).sum();
        assertThat(pairs).as("the hand count of the table's (type, kind) pairs").isEqualTo(40);
        Set<ResolutionKind> templateKinds = EnumSet.complementOf(
                EnumSet.of(ResolutionKind.EVIDENCED, ResolutionKind.REPUDIATE_BATCH));
        assertThat(templateKinds).hasSize(6);
        assertThat(templateKinds.size() * BreakType.values().length - pairs)
                .as("the table cells the battery refuses (REPUDIATE_BATCH's 14 are shape"
                        + " refusals, never a type's)").isEqualTo(44);

        int raisedPairs = 0;
        for (BreakCause cause : BreakCause.values()) {
            for (BreakType type : cause.raisesAs()) {
                raisedPairs++;
                Set<ResolutionKind> expected = REFINED.getOrDefault(cause, TABLE.get(type));
                assertThat(ResolutionTemplates.admittedKinds(type, cause))
                        .as("%s raised by %s admits exactly its row", type, cause)
                        .isEqualTo(expected);
            }
        }
        assertThat(raisedPairs).as("every detector's (cause, type) pair checked").isEqualTo(26);
        for (BreakCause refined : REFINED.keySet()) {
            assertThat(refined.raisesAs()).as(refined + " refines one type").hasSize(1);
        }

        // Every (type, cause) combination - a superset of the reclassifications BreakCaseFile
        // admits: the cause is frozen at raise and only the type moves, so a refinement is the
        // CAUSE's whatever the current type (P8-TST-002's gate find).
        for (BreakType type : BreakType.values()) {
            for (BreakCause cause : BreakCause.values()) {
                Set<ResolutionKind> row =
                        type == BreakType.SETTLEMENT_MISMATCH
                                        && cause != BreakCause.REMITTANCE_DIFFERS
                                ? EnumSet.noneOf(ResolutionKind.class)
                                : EnumSet.copyOf(TABLE.get(type));
                Set<ResolutionKind> expected = switch (cause) {
                    case REPLAY_DIVERGED -> EnumSet.of(ResolutionKind.ACKNOWLEDGE);
                    case STATEMENT_GAP, OPENING_BALANCE -> EnumSet.noneOf(ResolutionKind.class);
                    case EXECUTION_ALREADY_EXPLAINED -> {
                        row.remove(ResolutionKind.TRANSFER_TO_ACCOUNT);
                        yield row;
                    }
                    default -> row;
                };
                assertThat(ResolutionTemplates.admittedKinds(type, cause))
                        .as("a %s break raised by %s (however it was reclassified)", type, cause)
                        .isEqualTo(expected);
            }
        }
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.TIMING_DIFFERENCE, BreakCause.REPLAY_DIVERGED))
                .as("a diverged replay reclassified onto the timing type keeps its refinement")
                .containsExactly(ResolutionKind.ACKNOWLEDGE);
        assertThat(ResolutionTemplates.admittedKinds(
                        BreakType.UNKNOWN_EXTERNAL, BreakCause.EXECUTION_ALREADY_EXPLAINED))
                .as("an explained duplicate reclassified onto UNKNOWN_EXTERNAL is never"
                        + " transferred")
                .doesNotContain(ResolutionKind.TRANSFER_TO_ACCOUNT);
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
