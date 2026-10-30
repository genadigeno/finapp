package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The closed vocabularies of the {@code accounting} module (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 6, 13) -
 * each held to the plan <strong>exactly</strong>: the members as an ordered list, so a value added, dropped or renamed
 * is a failing test rather than a silent change to what a later machine or table admits; and every lifecycle as its
 * <strong>whole edge matrix</strong> - every ordered pair of statuses asserted moved-to or not - so an edge widened is
 * caught as surely as one removed (the {@code RiskVocabulariesTest} idiom).
 */
@DisplayName("accounting's closed vocabularies (P14-TSK-001)")
class AccountingVocabulariesTest {

    // ------------------------------------------------------------------ the members, exactly as the plan names them

    @Test
    @DisplayName("every vocabulary's members are exactly the plan's, in order")
    void theMembersAreThePlans() {
        assertThat(names(GlClassification.values())).containsExactly("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");
        assertThat(names(PeriodStatus.values())).containsExactly("OPEN", "LOCKED", "CLOSE_PROPOSED", "CLOSED");
        // Section 13.5's nine checks.
        assertThat(names(CloseCheckCode.values())).containsExactly(
                "LEDGER_TRIAL_BALANCE", "GL_EQUALS_LEDGER", "RULE_CONFORMANCE", "SOURCE_COMPLETENESS",
                "ACCRUALS_COMPLETE", "PROJECTION_VERIFIED", "RECONCILIATION_DISCLOSURE", "VALUATION_SET_APPROVED",
                "OPEN_ACCOUNTING_BREAKS");
        // Section 13.6's fourteen reports.
        assertThat(names(ReportKind.values())).containsExactly(
                "TRIAL_BALANCE", "GL_ACTIVITY", "BALANCES", "FEE_AND_INTEREST_REVENUE", "RECEIVABLES_AND_PAYABLES",
                "SETTLEMENT_AND_CLEARING_POSITIONS", "SUSPENSE", "FX_GAINS_AND_LOSSES", "LOAN_PORTFOLIO",
                "BNPL_EXPOSURE", "RECONCILIATION_STATUS", "CLOSE_STATUS", "BALANCE_SHEET", "INCOME_STATEMENT");
        assertThat(names(RunStatus.values()))
                .containsExactly("GENERATING", "FINAL", "PROVISIONAL", "FAILED", "SUPERSEDED");
        // Section 13.3's three levels: two of mapping, three of conformance, two of source.
        assertThat(names(BreakType.values())).containsExactly(
                "MAPPING_GAP", "DOUBLE_MAPPING", "UNREGISTERED_POSTING", "SHAPE_MISMATCH", "UNBALANCED_ENTRY",
                "SOURCE_DISCREPANCY", "SOURCE_DELAYED");
        assertThat(names(BreakStatus.values())).containsExactly("OPEN", "ACKNOWLEDGED", "RESOLVED");
        // P14-TSK-002's four dating classes.
        assertThat(names(DatingClass.values()))
                .containsExactly("TODAY_CLOCK", "TODAY_DATABASE", "STORED_ROW", "ECONOMIC_DATE");
        // P14-TSK-002's register: the ledger's three entry types (held equal to JournalEntryType in app) and a line's side.
        assertThat(names(EntryType.values())).containsExactly("POSTING", "REVERSAL", "ADJUSTMENT");
        assertThat(names(Side.values())).containsExactly("DEBIT", "CREDIT", "EITHER");
        assertThat(names(SubmissionStatus.values()))
                .containsExactly("DRAFT", "VALIDATED", "APPROVED", "SUBMITTED", "ACKNOWLEDGED", "REJECTED");
        assertThat(StatementSection.values()).hasSize(17);
    }

    // ------------------------------------------------------------------ classification and statement sections

    @Test
    @DisplayName("ASSET and EXPENSE are debit-normal, the other three credit-normal; three balance-sheet elements")
    void theClassificationsCarryTheirNormalSide() {
        assertThat(EnumSet.allOf(GlClassification.class).stream().filter(GlClassification::debitNormal))
                .containsExactlyInAnyOrder(GlClassification.ASSET, GlClassification.EXPENSE);
        assertThat(EnumSet.allOf(GlClassification.class).stream().filter(GlClassification::balanceSheet))
                .containsExactlyInAnyOrder(GlClassification.ASSET, GlClassification.LIABILITY, GlClassification.EQUITY);
    }

    @Test
    @DisplayName("a section admits only classifications of its own statement - a balance sheet never presents a revenue")
    void aSectionAdmitsOnlyItsStatementsClassifications() {
        for (StatementSection section : StatementSection.values()) {
            assertThat(section.admitted()).as("%s admits something", section).isNotEmpty();
            boolean balanceSheet = section.statement() == StatementSection.Statement.BALANCE_SHEET;
            assertThat(section.admitted())
                    .as("%s (%s) admits only that statement's classifications", section, section.statement())
                    .allMatch(classification -> classification.balanceSheet() == balanceSheet);
        }
    }

    @Test
    @DisplayName("every classification is presentable - each is admitted by at least one section")
    void everyClassificationHasASection() {
        for (GlClassification classification : GlClassification.values()) {
            assertThat(Arrays.stream(StatementSection.values()).filter(section -> section.admits(classification)))
                    .as("a GL account classified %s must have a section to be presented in", classification)
                    .isNotEmpty();
        }
        // The two deliberate two-classification sections, and nothing else is one.
        assertThat(Arrays.stream(StatementSection.values()).filter(section -> section.admitted().size() > 1))
                .containsExactlyInAnyOrder(StatementSection.SUSPENSE, StatementSection.FX_RESULT);
    }

    @Test
    @DisplayName("a section's admitted set cannot be widened at run time")
    void theAdmittedSetIsImmutable() {
        Set<GlClassification> admitted = StatementSection.CASH_AND_BANK.admitted();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> admitted.add(GlClassification.REVENUE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ the lifecycles, edge by edge

    @Test
    @DisplayName("the period's machine is exactly the plan's six edges - lock, propose, approve, reject, unlock, reopen")
    void thePeriodsEdges() {
        assertThat(edges(PeriodStatus.values(), PeriodStatus::canMoveTo)).containsExactlyInAnyOrder(
                "OPEN->LOCKED", "LOCKED->CLOSE_PROPOSED", "CLOSE_PROPOSED->CLOSED", "CLOSE_PROPOSED->LOCKED",
                "LOCKED->OPEN", "CLOSED->OPEN");
        // Only an open period takes postings: from the lock's commit the ledger's cutoff refuses them (INV-ACC-08).
        assertThat(EnumSet.allOf(PeriodStatus.class).stream().filter(PeriodStatus::admitsPostings))
                .containsExactly(PeriodStatus.OPEN);
        // Four eyes on the approve, the unlock and the reopen; not on the lock nor on a rejection.
        assertThat(edges(PeriodStatus.values(), PeriodStatus::fourEyesTo))
                .containsExactlyInAnyOrder("CLOSE_PROPOSED->CLOSED", "LOCKED->OPEN", "CLOSED->OPEN");
    }

    @Test
    @DisplayName("a report run completes once, and only FINAL is superseded - never deleted")
    void theRunsEdges() {
        assertThat(edges(RunStatus.values(), RunStatus::canMoveTo)).containsExactlyInAnyOrder(
                "GENERATING->FINAL", "GENERATING->PROVISIONAL", "GENERATING->FAILED", "FINAL->SUPERSEDED");
        assertThat(EnumSet.allOf(RunStatus.class).stream().filter(RunStatus::contentFrozen))
                .containsExactlyInAnyOrder(RunStatus.FINAL, RunStatus.PROVISIONAL, RunStatus.SUPERSEDED);
        assertThat(EnumSet.allOf(RunStatus.class).stream().filter(RunStatus::issuable)).containsExactly(RunStatus.FINAL);
    }

    @Test
    @DisplayName("a break is acknowledged or resolved, never re-opened")
    void theBreaksEdges() {
        assertThat(edges(BreakStatus.values(), BreakStatus::canMoveTo))
                .containsExactlyInAnyOrder("OPEN->ACKNOWLEDGED", "OPEN->RESOLVED", "ACKNOWLEDGED->RESOLVED");
        assertThat(EnumSet.allOf(BreakStatus.class).stream().filter(BreakStatus::open))
                .containsExactlyInAnyOrder(BreakStatus.OPEN, BreakStatus.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("a submission moves forward only, and is answered - never edited - once submitted")
    void theSubmissionsEdges() {
        assertThat(edges(SubmissionStatus.values(), SubmissionStatus::canMoveTo)).containsExactlyInAnyOrder(
                "DRAFT->VALIDATED", "VALIDATED->APPROVED", "APPROVED->SUBMITTED", "SUBMITTED->ACKNOWLEDGED",
                "SUBMITTED->REJECTED");
        assertThat(EnumSet.allOf(SubmissionStatus.class).stream().filter(SubmissionStatus::immutable))
                .containsExactlyInAnyOrder(
                        SubmissionStatus.SUBMITTED, SubmissionStatus.ACKNOWLEDGED, SubmissionStatus.REJECTED);
    }

    @Test
    @DisplayName("only a stored-row or economic date may lie in the past - the back-dating posters")
    void theBackDatingClasses() {
        assertThat(EnumSet.allOf(DatingClass.class).stream().filter(DatingClass::mayBackDate))
                .containsExactlyInAnyOrder(DatingClass.STORED_ROW, DatingClass.ECONOMIC_DATE);
    }

    @Test
    @DisplayName("the edge matrix helper sees every ordered pair - the guard is not vacuous")
    void theEdgeHelperIsNotVacuous() {
        // A matrix built over nothing would make every containsExactlyInAnyOrder above compare two empty lists.
        assertThat(edges(PeriodStatus.values(), (from, to) -> true)).hasSize(16);
    }

    // ------------------------------------------------------------------ the module's codes

    @Test
    @DisplayName("the role split's error codes and audit action are namespaced accounting.* with their statuses")
    void theRoleSplitsCodes() {
        assertThat(AccountingErrorCode.NOT_FOUND.code()).isEqualTo("accounting.NotFound");
        assertThat(AccountingErrorCode.NOT_FOUND.status()).isEqualTo(404);
        assertThat(AccountingErrorCode.SELF_APPROVAL_REFUSED.code()).isEqualTo("accounting.SelfApprovalRefused");
        assertThat(AccountingErrorCode.SELF_APPROVAL_REFUSED.status()).as("a forbidden act, not a conflict").isEqualTo(403);
        assertThat(AccountingAuditAction.SELF_APPROVAL_REFUSED.code()).isEqualTo("accounting.SelfApprovalRefused");
        assertThat(AccountingAuditAction.SELF_APPROVAL_REFUSED.requiresReason()).isFalse();
        // P14-TSK-003: the mapping doors' six - content 422s, the stale and retroactive 409s, the reason 422.
        assertThat(Arrays.stream(AccountingErrorCode.values()).map(c -> c.code() + "=" + c.status()).toList())
                .containsExactly("accounting.NotFound=404", "accounting.SelfApprovalRefused=403",
                        "accounting.MappingInvalid=422", "accounting.MappingNotTotal=422",
                        "accounting.MappingIncompatible=422", "accounting.MappingStale=409",
                        "accounting.MappingRetroactive=409", "accounting.ReasonRequired=422");
        // P14-TSK-002: the posting rule register's audited read - no reason, an operator's read.
        assertThat(AccountingAuditAction.POSTING_RULES_READ.code()).isEqualTo("accounting.PostingRulesRead");
        assertThat(AccountingAuditAction.POSTING_RULES_READ.requiresReason()).isFalse();
        // P14-TSK-003: the mapping's three acts, each reasoned.
        assertThat(Arrays.stream(AccountingAuditAction.values()).map(a -> a.code() + "=" + a.requiresReason()).toList())
                .containsExactly("accounting.SelfApprovalRefused=false", "accounting.PostingRulesRead=false",
                        "accounting.MappingProposed=true", "accounting.MappingActivated=true",
                        "accounting.MappingRejected=true");
    }

    // ------------------------------------------------------------------

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** Every ordered pair (from, to) the predicate admits, as "FROM->TO" - the whole matrix, self-pairs included. */
    private static <E extends Enum<E>> List<String> edges(E[] values, BiPredicate<E, E> moves) {
        List<String> edges = new ArrayList<>();
        for (E from : values) {
            for (E to : values) {
                if (moves.test(from, to)) {
                    edges.add(from.name() + "->" + to.name());
                }
            }
        }
        return edges;
    }
}
