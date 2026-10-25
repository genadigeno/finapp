package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The closed vocabularies risk declares before any behaviour (`P13-TSK-001`), each pinned to exactly the members
 * {@code PHASE_13_PLAN.md} sections 12.1 to 12.8, {@code RISK_LIFECYCLES.md} and ADR-0108...ADR-0112 name - so a member
 * added, dropped or renamed is a reviewed change to this test as well as to the enum, never a silent widening of what a
 * later task's {@code CHECK}, engine or policy door must accept. The bnpl precedent ({@code BnplVocabulariesTest}).
 */
@DisplayName("risk's closed vocabularies (P13-TSK-001)")
class RiskVocabulariesTest {

    @Test
    @DisplayName("seven operations: five judge a movement through a seam, the credit signal and the monitoring key do not")
    void theOperations() {
        assertThat(names(RiskOperation.values())).containsExactly("TRANSFER", "WITHDRAWAL", "CARD_PAYMENT",
                "WALLET_PAYMENT", "CROSS_BORDER", "CREDIT_SIGNAL", "MONITORING");
        assertThat(Arrays.stream(RiskOperation.values()).filter(RiskOperation::judgesAMovement))
                .containsExactly(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL, RiskOperation.CARD_PAYMENT,
                        RiskOperation.WALLET_PAYMENT, RiskOperation.CROSS_BORDER);
    }

    @Test
    @DisplayName("five decisions declared strictest first - DENY > HOLD > REVIEW > CHALLENGE > ALLOW - and the strictest of"
            + " two is the earlier declared")
    void theDecisionsAndTheirPrecedence() {
        assertThat(names(RiskDecision.values())).containsExactly("DENY", "HOLD", "REVIEW", "CHALLENGE", "ALLOW");
        assertThat(RiskDecision.strictest(RiskDecision.ALLOW, RiskDecision.CHALLENGE)).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(RiskDecision.strictest(RiskDecision.CHALLENGE, RiskDecision.REVIEW)).isEqualTo(RiskDecision.REVIEW);
        assertThat(RiskDecision.strictest(RiskDecision.REVIEW, RiskDecision.HOLD)).isEqualTo(RiskDecision.HOLD);
        assertThat(RiskDecision.strictest(RiskDecision.HOLD, RiskDecision.DENY)).isEqualTo(RiskDecision.DENY);
        assertThat(RiskDecision.strictest(RiskDecision.DENY, RiskDecision.ALLOW)).isEqualTo(RiskDecision.DENY);
        assertThat(RiskDecision.strictest(RiskDecision.ALLOW, RiskDecision.ALLOW)).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    @DisplayName("each operation admits exactly the decisions its consumer can honour - HOLD only where a native hold"
            + " exists (the withdrawal; the card payment since -010's second slice, never the wallet payment), none for"
            + " the signal and the sweep")
    void theAdmittedDecisions() {
        assertThat(RiskDecision.admittedBy(RiskOperation.TRANSFER)).containsExactlyInAnyOrder(
                RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY, RiskDecision.REVIEW);
        assertThat(RiskDecision.admittedBy(RiskOperation.CROSS_BORDER)).containsExactlyInAnyOrder(
                RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY, RiskDecision.REVIEW);
        assertThat(RiskDecision.admittedBy(RiskOperation.WITHDRAWAL)).containsExactlyInAnyOrder(
                RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY, RiskDecision.HOLD, RiskDecision.REVIEW);
        assertThat(RiskDecision.admittedBy(RiskOperation.CARD_PAYMENT)).containsExactlyInAnyOrder(
                RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY, RiskDecision.HOLD);
        assertThat(RiskDecision.admittedBy(RiskOperation.WALLET_PAYMENT)).containsExactlyInAnyOrder(
                RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY);
        assertThat(RiskDecision.admittedBy(RiskOperation.CREDIT_SIGNAL)).isEmpty();
        assertThat(RiskDecision.admittedBy(RiskOperation.MONITORING)).isEmpty();
        assertThat(RiskDecision.HOLD.isAdmittedBy(RiskOperation.WITHDRAWAL)).isTrue();
        assertThat(RiskDecision.HOLD.isAdmittedBy(RiskOperation.CARD_PAYMENT))
                .as("-010's second slice: the card authorised, its capture held (risk V008, payments V034)").isTrue();
        assertThat(RiskDecision.HOLD.isAdmittedBy(RiskOperation.WALLET_PAYMENT))
                .as("a wallet has no native hold at confirmation (C2)").isFalse();
        // Every judged operation admits ALLOW and DENY: a policy can always permit and always refuse.
        for (RiskOperation operation : RiskOperation.values()) {
            if (operation.judgesAMovement()) {
                assertThat(RiskDecision.admittedBy(operation)).as(operation.name())
                        .contains(RiskDecision.ALLOW, RiskDecision.DENY);
            }
        }
        // The answer is a fresh set: a caller cannot widen the declaration.
        RiskDecision.admittedBy(RiskOperation.MONITORING).add(RiskDecision.DENY);
        assertThat(RiskDecision.admittedBy(RiskOperation.MONITORING)).isEmpty();
    }

    @Test
    @DisplayName("the twenty-two rule kinds of section 12.2, each written for at least one operation and never the credit"
            + " signal, each with a typed parameter schema of distinct names; the monitoring kinds the sweep's alone")
    void theRuleKinds() {
        assertThat(names(RuleKind.values())).containsExactly("AMOUNT_BAND", "VELOCITY_COUNT", "VELOCITY_SUM",
                "NEW_PAYEE", "NEW_DEVICE", "SESSION_ASSURANCE_BELOW", "RECENT_AUTH_FAILURES",
                "RECENT_CREDENTIAL_CHANGE", "RECENT_RECOVERY", "ACCOUNT_AGE_BELOW", "KYC_NOT_APPROVED",
                "SCREENING_HIT", "PROFILE_RATING", "MERCHANT_SUSPENDED", "CORRIDOR_HIGH_RISK", "DORMANT_THEN_ACTIVE",
                "RAPID_IN_OUT", "STRUCTURING", "ROUND_AMOUNT", "REFUND_RATIO", "CHARGEBACK_RATIO",
                "BNPL_MULTI_APPLICATION");
        for (RuleKind kind : RuleKind.values()) {
            assertThat(kind.operations()).as("%s is written for some operation", kind).isNotEmpty();
            assertThat(kind.operations()).as("%s is never the credit signal's", kind)
                    .doesNotContain(RiskOperation.CREDIT_SIGNAL);
            assertThat(kind.parameters()).as("%s's parameter names are distinct", kind)
                    .extracting(RuleKind.Parameter::name).doesNotHaveDuplicates();
            for (RuleKind.Parameter parameter : kind.parameters()) {
                assertThat(parameter.type()).as("%s.%s is typed", kind, parameter.name()).isNotNull();
            }
            for (RiskOperation operation : kind.operations()) {
                assertThat(kind.appliesTo(operation)).isTrue();
            }
        }
        for (RuleKind monitoringOnly : List.of(RuleKind.DORMANT_THEN_ACTIVE, RuleKind.RAPID_IN_OUT,
                RuleKind.STRUCTURING, RuleKind.BNPL_MULTI_APPLICATION)) {
            assertThat(monitoringOnly.operations()).as(monitoringOnly.name()).containsExactly(RiskOperation.MONITORING);
        }
        // P13-TSK-014: the ratios widened to the payments for T-eval (the merchant's), scope only - and given their
        // count floor; BNPL stays the sweep's alone (it feeds credit's signal, never a seam).
        for (RuleKind ratio : List.of(RuleKind.REFUND_RATIO, RuleKind.CHARGEBACK_RATIO)) {
            assertThat(ratio.operations()).as(ratio.name()).containsExactlyInAnyOrder(RiskOperation.CARD_PAYMENT,
                    RiskOperation.WALLET_PAYMENT, RiskOperation.MONITORING);
            assertThat(ratio.parameters()).extracting(RuleKind.Parameter::name)
                    .containsExactly("window", "ratio_bps", "threshold_count");
        }
        assertThat(RuleKind.MERCHANT_SUSPENDED.operations())
                .containsExactlyInAnyOrder(RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT);
        assertThat(RuleKind.CORRIDOR_HIGH_RISK.operations())
                .containsExactlyInAnyOrder(RiskOperation.CROSS_BORDER, RiskOperation.MONITORING);
        assertThat(RuleKind.KYC_NOT_APPROVED.parameters()).isEmpty();
        assertThat(RuleKind.STRUCTURING.parameters()).extracting(RuleKind.Parameter::name)
                .containsExactly("window", "below_minor", "threshold_count", "sum_minor");
        assertThat(RuleKind.VELOCITY_SUM.parameters()).extracting(RuleKind.Parameter::type)
                .containsExactly(RuleKind.ParameterType.VELOCITY_DIMENSION, RuleKind.ParameterType.WINDOW,
                        RuleKind.ParameterType.MINOR_AMOUNT);
        assertThat(RuleKind.VELOCITY_COUNT.parameters()).extracting(RuleKind.Parameter::type)
                .containsExactly(RuleKind.ParameterType.VELOCITY_DIMENSION, RuleKind.ParameterType.WINDOW,
                        RuleKind.ParameterType.COUNT);
        // P13-TSK-006: SUBJECT_KIND left the parameter types when the velocity dimension became its own vocabulary -
        // no kind used it after; risk V005 dropped it from the type CHECK.
        assertThat(names(RuleKind.ParameterType.values())).containsExactly("COUNT", "MINOR_AMOUNT", "BASIS_POINTS",
                "DAYS", "HOURS", "WINDOW", "VELOCITY_DIMENSION", "CORRIDOR_SET", "ASSURANCE", "RATING");
        assertThatThrownBy(() -> new RuleKind.Parameter("Threshold", RuleKind.ParameterType.COUNT))
                .as("a parameter name is lower snake case")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("seven alert kinds, each investigative one folding into a case kind, the two operational ones into none")
    void theAlertKinds() {
        assertThat(names(AlertKind.values())).containsExactly("FRAUD", "ACCOUNT_TAKEOVER", "MERCHANT_ABUSE", "AML",
                "SCREENING_HIT", "UNCLASSIFIED_TRANSACTION", "VELOCITY_DIVERGENCE");
        assertThat(AlertKind.FRAUD.caseKind()).contains(CaseKind.FRAUD);
        assertThat(AlertKind.ACCOUNT_TAKEOVER.caseKind()).contains(CaseKind.ATO);
        assertThat(AlertKind.MERCHANT_ABUSE.caseKind()).contains(CaseKind.MERCHANT_ABUSE);
        assertThat(AlertKind.AML.caseKind()).contains(CaseKind.AML);
        assertThat(AlertKind.SCREENING_HIT.caseKind()).contains(CaseKind.AML);
        assertThat(AlertKind.UNCLASSIFIED_TRANSACTION.operational()).isTrue();
        assertThat(AlertKind.VELOCITY_DIVERGENCE.operational()).isTrue();
        assertThat(AlertKind.firstFor(CaseKind.AML)).isEqualTo(AlertKind.AML);
        assertThatThrownBy(() -> AlertKind.firstFor(CaseKind.COLLECTIONS))
                .as("a collections case opens on a default event, never on an alert (C3)")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(names(AlertSeverity.values())).containsExactly("LOW", "MEDIUM", "HIGH", "CRITICAL");
    }

    @Test
    @DisplayName("five case kinds, four states with DISPOSED the only closed one, five dispositions of which the confirming,"
            + " referring ones are two persons' and the closes one person's")
    void theCases() {
        assertThat(names(CaseKind.values())).containsExactly("FRAUD", "ATO", "MERCHANT_ABUSE", "AML", "COLLECTIONS");
        assertThat(names(CaseStatus.values())).containsExactly("OPEN", "IN_INVESTIGATION", "ESCALATED", "DISPOSED");
        assertThat(Arrays.stream(CaseStatus.values()).filter(status -> !status.open()))
                .containsExactly(CaseStatus.DISPOSED);
        assertThat(names(Disposition.values())).containsExactly("CLOSED_FALSE_POSITIVE", "CLOSED_NO_ACTION",
                "CONFIRMED_FRAUD", "CONFIRMED_SUSPICIOUS", "REFERRED");
        assertThat(Arrays.stream(Disposition.values()).filter(Disposition::fourEyes))
                .containsExactly(Disposition.CONFIRMED_FRAUD, Disposition.CONFIRMED_SUSPICIOUS, Disposition.REFERRED);
        assertThat(Arrays.stream(Disposition.values()).filter(Disposition::confirms))
                .containsExactly(Disposition.CONFIRMED_FRAUD, Disposition.CONFIRMED_SUSPICIOUS);
    }

    @Test
    @DisplayName("twelve action kinds, each naming its target and its owning domain; the three with no owning command"
            + " unavailable (P13-TSK-017: accounts' suspension, crossborder's block); the platform never resets a"
            + " credential")
    void theActionKinds() {
        assertThat(names(ActionKind.values())).containsExactly("SUSPEND_IDENTITY", "REINSTATE_IDENTITY",
                "REVOKE_SESSIONS", "SUSPEND_ACCOUNT", "REINSTATE_ACCOUNT", "SUSPEND_MERCHANT", "BLOCK_BENEFICIARY",
                "RELEASE_HELD_WITHDRAWAL", "DENY_HELD_WITHDRAWAL", "CAPTURE_HELD_PAYMENT", "VOID_HELD_PAYMENT",
                "RESCREEN_PARTY");
        assertThat(Arrays.stream(ActionKind.values()).filter(kind -> !kind.available()))
                .as("no owning command: accounts has no suspension, crossborder no block (P13-TSK-017)")
                .containsExactly(ActionKind.SUSPEND_ACCOUNT, ActionKind.REINSTATE_ACCOUNT, ActionKind.BLOCK_BENEFICIARY);
        assertThat(ActionKind.CAPTURE_HELD_PAYMENT.domain()).isEqualTo(ActionKind.Domain.PAYMENTS);
        assertThat(ActionKind.VOID_HELD_PAYMENT.domain()).isEqualTo(ActionKind.Domain.PAYMENTS);
        assertThat(ActionKind.SUSPEND_IDENTITY.domain()).isEqualTo(ActionKind.Domain.IDENTITY);
        assertThat(ActionKind.SUSPEND_IDENTITY.target()).isEqualTo(ActionTarget.PARTY);
        assertThat(ActionKind.RELEASE_HELD_WITHDRAWAL.target()).as("the judged movement itself")
                .isEqualTo(ActionTarget.WITHDRAWAL);
        assertThat(ActionKind.VOID_HELD_PAYMENT.target()).isEqualTo(ActionTarget.PAYMENT_INTENT);
        for (OverrideEffect effect : OverrideEffect.values()) {
            effect.action().ifPresent(kind -> assertThat(effect.targetKind()).as(effect.name())
                    .contains(kind.target().name()));
        }
        assertThat(ActionKind.RELEASE_HELD_WITHDRAWAL.domain()).isEqualTo(ActionKind.Domain.PAYMENTS);
        assertThat(ActionKind.BLOCK_BENEFICIARY.target()).isEqualTo(ActionTarget.BENEFICIARY);
        assertThat(ActionKind.RESCREEN_PARTY.domain()).isEqualTo(ActionKind.Domain.KYC);
        assertThat(names(ActionKind.Domain.values()))
                .containsExactly("IDENTITY", "ACCOUNTS", "MERCHANT", "CROSSBORDER", "PAYMENTS", "KYC");
        assertThat(names(ActionKind.values())).noneMatch(name -> name.contains("CREDENTIAL") || name.contains("REVERSE"));
        assertThat(names(ActionOutcome.values())).containsExactly("DONE", "ALREADY", "REFUSED");
    }

    @Test
    @DisplayName("eight subject kinds (the plan's six, plus the identity and the credit agreement the events name - P13-TSK-005),"
            + " four windows of fixed length, four ratings least to most severe with PROHIBITED set only by disposition,"
            + " two assurances with MULTI_FACTOR satisfying both")
    void theSubjectsWindowsRatingsAndAssurances() {
        assertThat(names(SubjectKind.values()))
                .containsExactly("PARTY", "ACCOUNT", "DEVICE", "MERCHANT", "BENEFICIARY", "SESSION", "IDENTITY",
                        "CREDIT_AGREEMENT");
        assertThat(names(WindowKind.values())).containsExactly("H1", "D1", "D7", "D30");
        // P13-TSK-006 (the coordinator's decision at wave C): the velocity dimensions, in the order R2a and R2 lock them.
        // P13-TSK-014 appended SESSION - last, so no existing lock or canonical order moved.
        assertThat(names(VelocityDimension.values()))
                .containsExactly("PARTY", "SOURCE_ACCOUNT", "DESTINATION", "DEVICE", "MERCHANT", "SESSION");
        assertThat(WindowKind.H1.length()).isEqualTo(Duration.ofHours(1));
        assertThat(WindowKind.D1.length()).isEqualTo(Duration.ofDays(1));
        assertThat(WindowKind.D7.length()).isEqualTo(Duration.ofDays(7));
        assertThat(WindowKind.D30.length()).isEqualTo(Duration.ofDays(30));
        assertThat(names(RiskRating.values())).containsExactly("LOW", "MEDIUM", "HIGH", "PROHIBITED");
        assertThat(RiskRating.HIGH.atLeast(RiskRating.MEDIUM)).isTrue();
        assertThat(RiskRating.LOW.atLeast(RiskRating.MEDIUM)).isFalse();
        assertThat(Arrays.stream(RiskRating.values()).filter(RiskRating::onlyByDisposition))
                .containsExactly(RiskRating.PROHIBITED);
        assertThat(names(RiskAssurance.values())).containsExactly("SINGLE_FACTOR", "MULTI_FACTOR");
        assertThat(RiskAssurance.MULTI_FACTOR.satisfies(RiskAssurance.MULTI_FACTOR)).isTrue();
        assertThat(RiskAssurance.MULTI_FACTOR.satisfies(RiskAssurance.SINGLE_FACTOR)).isTrue();
        assertThat(RiskAssurance.SINGLE_FACTOR.satisfies(RiskAssurance.MULTI_FACTOR)).isFalse();
    }

    @Test
    @DisplayName("one engine at version 1, current; an unheld version is empty, never a fallback")
    void theEngine() {
        assertThat(names(RiskEngine.Kind.values())).containsExactly("RISK");
        assertThat(names(RiskEngine.values())).containsExactly("RISK_ENGINE_V1");
        RiskEngine current = RiskEngine.current(RiskEngine.Kind.RISK);
        assertThat(current.kind()).isEqualTo(RiskEngine.Kind.RISK);
        assertThat(current.number()).isEqualTo(1);
        assertThat(RiskEngine.of(RiskEngine.Kind.RISK, 1)).contains(current);
        assertThat(RiskEngine.of(RiskEngine.Kind.RISK, 2)).isEmpty();
        assertThat(RiskEngine.of(RiskEngine.Kind.RISK, 0)).isEmpty();
    }

    @Test
    @DisplayName("the error codes and audit actions declared so far - the role split's three and two (-001), the policy"
            + " doors' four and four (-002), the reprocessing door's one and one (-019), the profile read's act (-007), the"
            + " override doors' two and four (-012), the alert machine's two and three (-015), the case's five and eight"
            + " (-016), the action machine's none and four (-017), the queues' one and two (-018), the regulatory report's"
            + " two and six (-021) - namespaced risk., the"
            + " refusals 403, the policy codes"
            + " the register's statuses")
    void theCodesAndActions() {
        // Each task adds its own codes and acts here, keeping the others' (the wave's rule).
        assertThat(Arrays.stream(RiskErrorCode.values()).map(RiskErrorCode::code))
                // Each task adds its own codes and acts here, keeping the others' (the wave's rule).
                .containsExactlyInAnyOrder("risk.NotFound", "risk.SelfApprovalRefused", "risk.SelfDealingRefused",
                        "risk.PolicyNotActive", "risk.PolicyStale", "risk.PolicyInvalid", "risk.ReasonRequired",
                        "risk.ReprocessingNotBehind",
                        // P13-TSK-012: the override doors' two.
                        "risk.ActionNotProposable", "risk.OverrideStale",
                        // P13-TSK-015: the alert's machine's two.
                        "risk.AlertNotOpen", "risk.SuppressionStale",
                        // P13-TSK-016: the case's two planned and DESIGN-016 D10's three.
                        "risk.CaseRevisionStale", "risk.CaseNotOpen", "risk.CaseActNotAdmitted", "risk.CaseNotTaken",
                        "risk.CaseRequestInvalid",
                        // P13-TSK-018: the queues' refusal of a read they cannot take as asked (DESIGN-018 D12).
                        "risk.QueueRequestInvalid",
                        // P13-TSK-021: the report's state conflicts and the draft it cannot take (DESIGN-021 D10).
                        "risk.ReportNotFileable", "risk.ReportRequestInvalid");
        assertThat(RiskErrorCode.NOT_FOUND.status()).isEqualTo(404);
        assertThat(RiskErrorCode.SELF_APPROVAL_REFUSED.status()).isEqualTo(403);
        assertThat(RiskErrorCode.SELF_DEALING_REFUSED.status()).isEqualTo(403);
        assertThat(RiskErrorCode.POLICY_NOT_ACTIVE.status()).isEqualTo(409);
        assertThat(RiskErrorCode.POLICY_STALE.status()).isEqualTo(409);
        assertThat(RiskErrorCode.POLICY_INVALID.status()).isEqualTo(422);
        assertThat(RiskErrorCode.REASON_REQUIRED.status()).isEqualTo(422);
        assertThat(RiskErrorCode.REPROCESSING_NOT_BEHIND.status()).isEqualTo(422);
        assertThat(RiskErrorCode.ACTION_NOT_PROPOSABLE.status()).isEqualTo(409);
        assertThat(RiskErrorCode.OVERRIDE_STALE.status()).isEqualTo(409);
        assertThat(RiskErrorCode.ALERT_NOT_OPEN.status()).isEqualTo(409);
        assertThat(RiskErrorCode.SUPPRESSION_STALE.status()).isEqualTo(409);
        assertThat(RiskErrorCode.CASE_REVISION_STALE.status()).isEqualTo(409);
        assertThat(RiskErrorCode.CASE_NOT_OPEN.status()).isEqualTo(409);
        assertThat(RiskErrorCode.CASE_ACT_NOT_ADMITTED.status()).isEqualTo(409);
        assertThat(RiskErrorCode.CASE_NOT_TAKEN.status()).isEqualTo(422);
        assertThat(RiskErrorCode.CASE_REQUEST_INVALID.status()).isEqualTo(422);
        assertThat(RiskErrorCode.QUEUE_REQUEST_INVALID.status()).isEqualTo(422);
        assertThat(RiskErrorCode.REPORT_NOT_FILEABLE.status()).as("the register's planned status").isEqualTo(409);
        assertThat(RiskErrorCode.REPORT_REQUEST_INVALID.status()).isEqualTo(422);
        assertThat(Arrays.stream(RiskAuditAction.values()).map(RiskAuditAction::code))
                .containsExactlyInAnyOrder("risk.PolicyProposed", "risk.PolicyActivated", "risk.PolicyRejected",
                        "risk.PolicyRetired", "risk.SelfApprovalRefused", "risk.SelfDealingRefused",
                        "risk.ReprocessingOrdered", "risk.ProfileRead",
                        // P13-TSK-004: the analyst's assessment read and replay.
                        "risk.AssessmentRead", "risk.Replayed",
                        // P13-TSK-012: the override's three acts and its issued action's conclusion.
                        "risk.OverrideProposed", "risk.OverrideApproved", "risk.OverrideRejected",
                        "risk.ActionConcluded",
                        // P13-TSK-015: the suppression's three acts.
                        "risk.AlertSuppressionProposed", "risk.AlertSuppressed", "risk.AlertSuppressionRejected",
                        // P13-TSK-016: the case's five acts and the disposition's three.
                        "risk.CaseOpened", "risk.CaseTaken", "risk.CaseNoted", "risk.CaseEscalated", "risk.CaseDisposed",
                        "risk.DispositionProposed", "risk.DispositionApproved", "risk.DispositionRejected",
                        // P13-TSK-017: the action machine's three acts and the platform's dispatch.
                        "risk.ActionProposed", "risk.ActionApproved", "risk.ActionRejected", "risk.ActionDispatched",
                        // P13-TSK-018: the queues' and the case file's audited reads.
                        "risk.AlertRead", "risk.CaseRead",
                        // P13-TSK-021: the report's three acts, the authority's two answers, the AML desk's read.
                        "risk.ReportDrafted", "risk.ReportApproved", "risk.ReportFiled", "risk.ReportAcknowledged",
                        "risk.ReportRejected", "risk.RegulatoryReportRead");
        assertThat(RiskAuditAction.ACTION_PROPOSED.requiresReason()).isTrue();
        assertThat(RiskAuditAction.ACTION_APPROVED.requiresReason()).isTrue();
        assertThat(RiskAuditAction.ACTION_REJECTED.requiresReason()).isTrue();
        assertThat(RiskAuditAction.ACTION_DISPATCHED.requiresReason()).as("the platform's act").isFalse();
        for (RiskAuditAction action : RiskAuditAction.values()) {
            assertThat(action.description()).as(action.name()).isNotBlank();
        }
        // The platform's acts are reasoned by the act they follow; a person's discretionary act requires a reason.
        for (RiskAuditAction action : List.of(RiskAuditAction.SELF_APPROVAL_REFUSED, RiskAuditAction.SELF_DEALING_REFUSED,
                RiskAuditAction.POLICY_RETIRED, RiskAuditAction.PROFILE_READ, RiskAuditAction.ASSESSMENT_READ,
                RiskAuditAction.ACTION_CONCLUDED, RiskAuditAction.CASE_OPENED, RiskAuditAction.CASE_TAKEN,
                RiskAuditAction.CASE_DISPOSED, RiskAuditAction.ALERT_READ, RiskAuditAction.CASE_READ,
                RiskAuditAction.REPORT_ACKNOWLEDGED, RiskAuditAction.REPORT_REJECTED,
                RiskAuditAction.REGULATORY_REPORT_READ)) {
            assertThat(action.requiresReason()).as("%s is the platform's act, reasoned by the act it follows", action)
                    .isFalse();
        }
        for (RiskAuditAction action : List.of(RiskAuditAction.POLICY_PROPOSED, RiskAuditAction.POLICY_ACTIVATED,
                RiskAuditAction.POLICY_REJECTED, RiskAuditAction.REPROCESSING_ORDERED, RiskAuditAction.REPLAYED,
                RiskAuditAction.OVERRIDE_PROPOSED, RiskAuditAction.OVERRIDE_APPROVED, RiskAuditAction.OVERRIDE_REJECTED,
                RiskAuditAction.ALERT_SUPPRESSION_PROPOSED, RiskAuditAction.ALERT_SUPPRESSED,
                RiskAuditAction.ALERT_SUPPRESSION_REJECTED, RiskAuditAction.CASE_NOTED, RiskAuditAction.CASE_ESCALATED,
                RiskAuditAction.DISPOSITION_PROPOSED, RiskAuditAction.DISPOSITION_APPROVED,
                RiskAuditAction.DISPOSITION_REJECTED, RiskAuditAction.REPORT_DRAFTED, RiskAuditAction.REPORT_APPROVED,
                RiskAuditAction.REPORT_FILED)) {
            assertThat(action.requiresReason()).as("%s is a person's discretionary act", action).isTrue();
        }
    }

    @Test
    @DisplayName("each aggregate has its own typed identifier - UUIDv7 only, and never equal across kinds")
    void theTypedIdentifiers() {
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        UUID value = ids.next();
        assertThat(RiskAssessmentId.of(value).value()).isEqualTo(value);
        assertThat(RiskAssessmentId.of(value)).isEqualTo(RiskAssessmentId.of(value));
        assertThat((Object) RiskCaseId.of(value))
                .as("a case and an assessment sharing a value are still two different things")
                .isNotEqualTo(RiskAssessmentId.of(value));
        assertThat((Object) RiskAlertId.of(value)).isNotEqualTo(RiskCaseId.of(value));
        assertThat(RiskPolicyVersionId.next(ids).value().version()).isEqualTo(7);
        assertThat(RiskSignalId.next(ids)).isNotEqualTo(RiskSignalId.next(ids));
        assertThat(DecisionOverrideId.of(value).value()).isEqualTo(value);
        assertThat(RiskActionId.of(value).value()).isEqualTo(value);
        assertThat(RegulatoryReportId.of(value).value()).isEqualTo(value);
        assertThat(MonitoredTransactionId.next(ids).value().version()).isEqualTo(7);
        assertThat(ScreeningListVersionId.next(ids).value().version()).isEqualTo(7);
        assertThat(CustomerRiskProfileId.of(value).value()).as("a profile's identity is its party's").isEqualTo(value);
        assertThatThrownBy(() -> RiskCaseId.of(UUID.randomUUID()))
                .as("a v4 value carries no creation instant (ADR-0013)")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the span names are risk's, dotted, and name no attribute")
    void theSpans() {
        for (String span : List.of(RiskSpans.EVALUATE, RiskSpans.ADVISE, RiskSpans.POLICY_ACTIVATE,
                RiskSpans.MONITORING_INTAKE, RiskSpans.CASE_DISPOSE, RiskSpans.ACTION_DISPATCH, RiskSpans.REPORT,
                RiskSpans.VELOCITY_PROOF, RiskSpans.PROFILE_RECOMPUTE, RiskSpans.PROFILE_READ, RiskSpans.ALERT_RAISE,
                RiskSpans.ALERT_SUPPRESS, RiskSpans.ACTION_PROPOSE, RiskSpans.ACTION_DECIDE, RiskSpans.QUEUE,
                RiskSpans.CASE_READ, RiskSpans.REPORT_INQUIRE)) {
            assertThat(span).startsWith("risk.").matches("[a-z]+(\\.[a-z]+)+");
        }
        assertThat(EnumSet.allOf(RiskOperation.class)).hasSize(7);
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
