package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The case file's pure rules (`P8-TSK-014`): the note screen at the domain rank (the same
 * shapes `V019`'s {@code CHECK}s refuse, window for window and group for group - corrected by the
 * Phase 8 → 9 transition, SEC-03), the subject and parking table a
 * reclassification is judged against (ADR-0069 §2, the one authority), and the settlement
 * status derivation with its precedence — {@code CASH_CONFIRMED} included, reachable in the
 * database only when `P8-TSK-016` lands.
 */
@DisplayName("the case file's pure rules (P8-TSK-014)")
class CaseFileRulesTest {

    // ----------------------------------------------------------------- the note screen

    /** 4111111111111111 is the canonical Luhn-valid test card number. */
    @ParameterizedTest(name = "refused: {0}")
    @ValueSource(strings = {
        "card 4111111111111111 seen in the report",
        "4111111111111111",
        // A Luhn-valid 16-digit WINDOW inside a longer digit run.
        "ref 994111111111111111 end",
        "no spaces:4111111111111111:end",
    })
    void aCardNumberShapeIsRefused(String body) {
        assertThat(NoteScreen.screenNote(body)).contains(NoteScreen.Refusal.CARD_NUMBER_SHAPE);
        assertThatThrownBy(() -> BreakCaseFile.refuseNote(body))
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                .hasMessageNotContaining("4111");
    }

    @ParameterizedTest(name = "refused: {0}")
    @ValueSource(strings = {
        "paid to GB82WEST12345698765432 per the bank",
        "DE89370400440532013000",
        "iban:FR1420041010050500013M02606",
    })
    void anAccountShapeIsRefused(String body) {
        // A real IBAN's long digit run may trip the card-number scan first; either way the
        // body is refused and nothing is stored.
        assertThat(NoteScreen.screenNote(body)).isPresent();
    }

    @Test
    @DisplayName("the account shape alone, with no digit run a card scan could catch")
    void theAccountShapeIsItsOwnRule() {
        assertThat(NoteScreen.screenNote("moved to GB82WESTABCDEFGHIJKLM today"))
                .contains(NoteScreen.Refusal.ACCOUNT_SHAPE);
        assertThat(NoteScreen.screenNote("moved to xGB82WESTABCDEFGHIJKLM today"))
                .as("inside a longer word it is not the shape (an alphanumeric boundary)")
                .isEmpty();
    }

    /**
     * SEC-03 (the Phase 8 → 9 transition): the card number and the account identifier as a
     * person writes them - grouped by spaces or dashes, or printed in groups of four - were
     * admitted by the contiguous scans; every door that runs this screen refuses them now.
     */
    @ParameterizedTest(name = "refused as written: {0}")
    @ValueSource(strings = {
        "customer says card 4111 1111 1111 1111 was charged twice",
        "customer says card 4111-1111-1111-1111 was charged twice",
        // NEW-SEC-2 (the re-gate): the machine separators ':' and '_' group a card number too.
        "customer says card 4111:1111:1111:1111 was charged twice",
        "customer says card 4111_1111_1111_1111 was charged twice",
        "refund instead to GB82 WEST 1234 5698 7654 32",
    })
    void theGroupedShapesAreRefused(String body) {
        assertThat(NoteScreen.screenNote(body))
                .as("the grouped card number and the printed account identifier are refused")
                .isPresent();
        assertThatThrownBy(() -> BreakCaseFile.refuseNote(body))
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                .hasMessageNotContaining("4111")
                .hasMessageNotContaining("WEST");
        assertThatThrownBy(() -> ResolutionMachine.refuseNarrative(body))
                .as("the resolution narrative's door")
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class)
                .hasMessageNotContaining("4111");
        assertThatThrownBy(() -> ResolutionMachine.refuseReason(body))
                .as("the rejection reason's door")
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class)
                .hasMessageNotContaining("4111");
        assertThatThrownBy(() -> RuleSetAdministration.refuseReason(body))
                .as("the rule set's proposal and decision reasons' door")
                .isInstanceOf(RuleSetAdministration.RuleSetInvalid.class)
                .hasMessageNotContaining("4111");
        assertThatThrownBy(() -> RunAdministration.refuseReason(body))
                .as("SEC-04: the reprocess and requeue reasons' door, unscreened until now")
                .isInstanceOf(RunAdministration.ReasonRequired.class)
                .hasMessageNotContaining("4111")
                .hasMessageNotContaining("WEST");
        assertThatThrownBy(
                        () -> BreakCaseFile.refuseLink(EvidenceTargetKind.OPERATION,
                                "CARD_CAPTURE:" + body.replace(' ', '-')))
                .as("an evidence reference")
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                .hasMessageNotContaining("4111");
    }

    @ParameterizedTest(name = "admitted: {0}")
    @ValueSource(strings = {
        "the PSP confirmed the capture settled a day late",
        // Thirteen digits that fail Luhn: a network reference, not a card.
        "network ref 1234567890123 checked",
        // Twelve digits that fail Luhn: inside the 12..19 band, but no card.
        "batch 123456789012 re-sent",
        "operation 01a0e2bc-8200-7014-8000-000000000014 traced",
        // A UUID whose dash-joined digits are a Luhn-valid span is still the platform's own.
        "duplicate of run 7b8f2ab5-3451-4013-9675-f6ad325b55dd",
    })
    void ordinaryProseIsAdmitted(String body) {
        assertThat(NoteScreen.screenNote(body)).isEmpty();
    }

    @Test
    @DisplayName("the bounds: empty refused, 4000 admitted, 4001 refused")
    void theBoundsAreTheColumn() {
        assertThat(NoteScreen.screenNote("")).contains(NoteScreen.Refusal.EMPTY);
        assertThat(NoteScreen.screenNote("a".repeat(4000))).isEmpty();
        assertThat(NoteScreen.screenNote("a".repeat(4001))).contains(NoteScreen.Refusal.TOO_LONG);
    }

    @Test
    @DisplayName("the Luhn scan is V019's: every 12..19 window of a group, and every span of"
            + " whole groups joined by single spaces or dashes")
    void theLuhnScanMirrorsTheDatabaseFunction() {
        // 4111111111111111 + one digit: the 16-window at offset 0 is valid.
        assertThat(NoteScreen.holdsLuhnValidDigitRun("41111111111111117")).isTrue();
        // A 13-digit Luhn-valid run (4222222222222).
        assertThat(NoteScreen.holdsLuhnValidDigitRun("4222222222222")).isTrue();
        // SEC-03: digits split by a space are ONE grouped card number - this case was pinned
        // the other way ("two runs, each too short") until the Phase 8 -> 9 transition.
        assertThat(NoteScreen.holdsLuhnValidDigitRun("4111111 111111111")).isTrue();
        // A twelve-digit Luhn-valid card (ISO/IEC 7812's lower bound).
        assertThat(NoteScreen.holdsLuhnValidDigitRun("501800000009")).isTrue();
    }

    // ----------------------------------------------------------------- the taxonomy table

    @Test
    @DisplayName("ADR-0069 section 2's Subject column: which kinds each type stands on")
    void eachTypeStandsOnItsTableSubjects() {
        assertThat(standsOn(BreakType.MISSING_EXTERNAL))
                .containsExactly(BreakSubjectKind.EXPECTATION);
        assertThat(standsOn(BreakType.DUPLICATE_INTERNAL))
                .containsExactly(BreakSubjectKind.EXPECTATION);
        assertThat(standsOn(BreakType.UNKNOWN_EXTERNAL))
                .containsExactlyInAnyOrder(
                        BreakSubjectKind.EXTERNAL_ITEM, BreakSubjectKind.SUSPENSE_ITEM);
        assertThat(standsOn(BreakType.AMOUNT_MISMATCH))
                .containsExactlyInAnyOrder(
                        BreakSubjectKind.EXPECTATION, BreakSubjectKind.EXTERNAL_ITEM);
        assertThat(standsOn(BreakType.TIMING_DIFFERENCE))
                .containsExactly(BreakSubjectKind.DECISION);
        assertThat(standsOn(BreakType.SETTLEMENT_MISMATCH))
                .containsExactlyInAnyOrder(
                        BreakSubjectKind.EXPECTATION, BreakSubjectKind.EXTERNAL_ITEM,
                        BreakSubjectKind.RUN);
        assertThat(standsOn(BreakType.PROCESSING_ERROR))
                .containsExactlyInAnyOrder(
                        BreakSubjectKind.EXTERNAL_ITEM, BreakSubjectKind.RUN,
                        BreakSubjectKind.DECISION);
        assertThat(standsOn(BreakType.DUPLICATE_EXTERNAL))
                .as("an item, or the suspense item of a parking whose execution is already"
                        + " explained (P8-TSK-020, ADR-0069 section 2 amended)")
                .containsExactlyInAnyOrder(
                        BreakSubjectKind.EXTERNAL_ITEM, BreakSubjectKind.SUSPENSE_ITEM);
        for (BreakType itemOnly :
                List.of(
                        BreakType.MISSING_INTERNAL, BreakType.CURRENCY_MISMATCH,
                        BreakType.FEE_MISMATCH,
                        BreakType.AMBIGUOUS_MATCH, BreakType.REVERSAL_MISMATCH,
                        BreakType.REFUND_MISMATCH)) {
            assertThat(standsOn(itemOnly)).as("%s", itemOnly)
                    .containsExactly(BreakSubjectKind.EXTERNAL_ITEM);
        }
    }

    @Test
    @DisplayName("the Parked column per subject: an item parks exactly for the suspense-owning"
            + " types, a suspense item always, nothing else ever")
    void parkingFollowsTheSubject() {
        for (BreakType type : BreakType.values()) {
            assertThat(type.parksOn(BreakSubjectKind.EXTERNAL_ITEM))
                    .as("%s on an item", type)
                    .isEqualTo(type.mayOwnSuspense());
            assertThat(type.parksOn(BreakSubjectKind.SUSPENSE_ITEM)).isTrue();
            assertThat(type.parksOn(BreakSubjectKind.EXPECTATION)).isFalse();
            assertThat(type.parksOn(BreakSubjectKind.RUN)).isFalse();
            assertThat(type.parksOn(BreakSubjectKind.DECISION)).isFalse();
        }
        assertThat(BreakType.FEE_MISMATCH.parksOn(BreakSubjectKind.EXTERNAL_ITEM))
                .as("an expensed fee never parks")
                .isFalse();
    }

    @Test
    @DisplayName("the reclassification's exit rule (the Phase 8 -> 9 transition): a cause keeps"
            + " an exit through a kind its subject takes, its own evidence, or an"
            + " acknowledgement back on its raise type - never otherwise")
    void aReclassificationKeepsTheCausesExit() {
        ResolutionTemplates.Holding nothing = new ResolutionTemplates.Holding.Nothing();
        ResolutionTemplates.Holding remainder = new ResolutionTemplates.Holding.Remainder(
                java.util.UUID.randomUUID(), ExpectationDirection.INBOUND,
                com.finapp.sharedkernel.money.Money.ofPersisted(
                        35_00, com.finapp.sharedkernel.money.CurrencyCode.of("EUR"), 2),
                java.util.UUID.randomUUID());
        ResolutionTemplates.Holding parkedCredit = new ResolutionTemplates.Holding.Parked(
                java.util.UUID.randomUUID(), Optional.of(java.util.UUID.randomUUID()),
                SuspenseSide.CREDIT,
                com.finapp.sharedkernel.money.Money.ofPersisted(
                        12_00, com.finapp.sharedkernel.money.CurrencyCode.of("EUR"), 2),
                java.util.UUID.randomUUID());
        Optional<ExpectationKind> capture = Optional.of(ExpectationKind.CARD_CAPTURE);
        Optional<ExpectationKind> remittance = Optional.of(ExpectationKind.REMITTANCE);

        // The three stranding shapes the gate found: refused.
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.PROCESSING_ERROR,
                BreakCause.STATEMENT_GAP, BreakSubjectKind.RUN, nothing, Optional.empty()))
                .as("(a) the gap's closer selects SETTLEMENT_MISMATCH").isPresent();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.SETTLEMENT_MISMATCH,
                BreakCause.RUN_BLOCKED, BreakSubjectKind.RUN, nothing, Optional.empty()))
                .as("(b) the requeued run's completion selects PROCESSING_ERROR").isPresent();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.SETTLEMENT_MISMATCH,
                BreakCause.EXPECTATION_OVERDUE, BreakSubjectKind.EXPECTATION, remainder,
                capture))
                .as("(c) a capture's closers select MISSING_EXTERNAL and AMOUNT_MISMATCH")
                .isPresent();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.PROCESSING_ERROR,
                BreakCause.OPENING_BALANCE, BreakSubjectKind.RUN, nothing, Optional.empty()))
                .as("a run's completion would discard an opening balance it never explains")
                .isPresent();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.AMOUNT_MISMATCH,
                BreakCause.EXPECTATION_OVERDUE, BreakSubjectKind.EXPECTATION, remainder,
                remittance))
                .as("a remittance's settling allocation selects SETTLEMENT_MISMATCH, never"
                        + " AMOUNT_MISMATCH: a write-off admitted now does not survive the"
                        + " remainder")
                .isPresent();

        // The exits.
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.AMOUNT_MISMATCH,
                BreakCause.EXPECTATION_OVERDUE, BreakSubjectKind.EXPECTATION, remainder,
                capture))
                .as("the capture's settling allocation still finds AMOUNT_MISMATCH").isEmpty();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.SETTLEMENT_MISMATCH,
                BreakCause.EXPECTATION_OVERDUE, BreakSubjectKind.EXPECTATION, remainder,
                remittance))
                .as("the remittance's settling allocation finds SETTLEMENT_MISMATCH").isEmpty();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.PROCESSING_ERROR,
                BreakCause.LATE_MATCH, BreakSubjectKind.DECISION, nothing, Optional.empty()))
                .as("a timing difference is always recoverable: back, then acknowledged")
                .isEmpty();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.TIMING_DIFFERENCE,
                BreakCause.REPLAY_DIVERGED, BreakSubjectKind.DECISION, nothing,
                Optional.empty()))
                .as("a diverged replay admits its acknowledgement on any type").isEmpty();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.UNKNOWN_EXTERNAL,
                BreakCause.AMOUNT_DIFFERS, BreakSubjectKind.EXTERNAL_ITEM, parkedCredit,
                Optional.empty()))
                .as("parked value: a kind admitted now is durable, its unpark closes the owner")
                .isEmpty();
        assertThat(ResolutionTemplates.reclassificationStrands(BreakType.FEE_MISMATCH,
                BreakCause.GRACE_EXPIRED, BreakSubjectKind.EXTERNAL_ITEM, nothing,
                Optional.empty()))
                .as("an acknowledgement needs no value").isEmpty();
    }

    private static Set<BreakSubjectKind> standsOn(BreakType type) {
        Set<BreakSubjectKind> kinds = EnumSet.noneOf(BreakSubjectKind.class);
        for (BreakSubjectKind kind : BreakSubjectKind.values()) {
            if (type.admits(kind)) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    // ----------------------------------------------------------------- settlement status

    @Test
    @DisplayName("the status precedence: RESOLVED, CASH_CONFIRMED, REPORTED, OVERDUE, PENDING")
    void theStatusIsDerivedByPrecedence() {
        Optional<ExpectationStatus> settled = Optional.of(ExpectationStatus.SETTLED);
        Optional<ExpectationStatus> open = Optional.of(ExpectationStatus.OPEN);
        assertThat(SettlementStatus.derive(
                        ExpectationStatus.RESOLVED_BY_ADJUSTMENT, true, List.of(settled)))
                .isEqualTo(SettlementStatus.RESOLVED);
        assertThat(SettlementStatus.derive(
                        ExpectationStatus.SETTLED, false, List.of(settled, settled)))
                .as("every contributing batch's remittance settled by the bank")
                .isEqualTo(SettlementStatus.CASH_CONFIRMED);
        assertThat(SettlementStatus.derive(
                        ExpectationStatus.SETTLED, true, List.of(settled, open)))
                .as("one remittance still open: reported, not yet cash")
                .isEqualTo(SettlementStatus.REPORTED);
        assertThat(SettlementStatus.derive(
                        ExpectationStatus.SETTLED, false, List.of(Optional.empty())))
                .as("a zero-net batch opened no remittance: it stays reported")
                .isEqualTo(SettlementStatus.REPORTED);
        assertThat(SettlementStatus.derive(ExpectationStatus.SETTLED, false, List.of()))
                .isEqualTo(SettlementStatus.REPORTED);
        assertThat(SettlementStatus.derive(
                        ExpectationStatus.PARTIALLY_SETTLED, true, List.of(settled)))
                .as("part of the money still unaccounted for, past its window")
                .isEqualTo(SettlementStatus.OVERDUE);
        assertThat(SettlementStatus.derive(ExpectationStatus.PARTIALLY_SETTLED, false, List.of()))
                .isEqualTo(SettlementStatus.PENDING);
        assertThat(SettlementStatus.derive(ExpectationStatus.OPEN, true, List.of()))
                .isEqualTo(SettlementStatus.OVERDUE);
        assertThat(SettlementStatus.derive(ExpectationStatus.OPEN, false, List.of()))
                .isEqualTo(SettlementStatus.PENDING);
    }

    // ----------------------------------------------------------------- link shapes

    @Test
    @DisplayName("a link's reference is an identifier: a UUID, or <kind>:<ref> for an operation")
    void aLinkReferenceIsAnIdentifier() {
        assertThatThrownBy(() -> BreakCaseFile.refuseLink(
                        EvidenceTargetKind.JOURNAL_ENTRY, "not-a-uuid"))
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class);
        assertThatThrownBy(() -> BreakCaseFile.refuseLink(
                        EvidenceTargetKind.OPERATION, "NOT_A_KIND:abc"))
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class);
        assertThatThrownBy(() -> BreakCaseFile.refuseLink(
                        EvidenceTargetKind.OPERATION, "CARD_CAPTURE:4111111111111111"))
                .as("the reference is screened like a note")
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class);
        BreakCaseFile.refuseLink(
                EvidenceTargetKind.OPERATION, "CARD_CAPTURE:01a0e2bc-8200-7014-8000-000000000014");
        BreakCaseFile.refuseLink(
                EvidenceTargetKind.SETTLEMENT_FILE, "01a0e2bc-8200-7014-8000-000000000014");
    }
}
