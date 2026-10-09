package com.finapp.app.api;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * WHICH permission each guarded route declares, pinned (`P6-DOC-001`).
 *
 * <p>{@code EveryEndpointDeclaresARuleTest} proves every route declares <em>a</em> rule, and
 * {@code DenyByDefaultDatabaseTest} proves the interceptor refuses - and audits - a caller who
 * lacks the declared permission. Nothing proved the declaration was the right one: the Phase 6
 * review found that moving a route to another permission the same role holds passed every test,
 * and that for most operator routes the tenancy battery's table cited negative tests that did
 * not exist. With the declaration pinned here, the two halves compose: the route requires
 * exactly this permission, and the interceptor refuses whoever lacks it.
 *
 * <p>Derived from the handler mapping that actually serves requests, so a route added,
 * removed or re-permissioned anywhere fails here until this register says so - and the change
 * is a reviewed line in a diff, not a silent widening.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("every permission-guarded route declares the permission the register pins")
class RoutePermissionRegisterTest {

    /** Every {@code @RequiresPermission} route, as {@code METHOD path}, and what it requires. */
    private static final Map<String, PermissionName> REGISTER =
            new TreeMap<>(
                    Map.ofEntries(
                    // Identity administration (Phase 1)
                    entry("DELETE /v1/identities/{id}/suspension", PermissionName.IDENTITY_SUSPEND),
                    entry("POST /v1/identities/{id}/suspension", PermissionName.IDENTITY_SUSPEND),
                    entry("POST /v1/identities/{id}/roles", PermissionName.ROLE_ASSIGN),
                    // KYC review (Phase 2)
                    entry("GET /v1/kyc/cases/{id}", PermissionName.KYC_REVIEW),
                    entry("POST /v1/kyc/cases/{id}/decision", PermissionName.KYC_REVIEW),
                    entry("POST /v1/kyc/cases/{id}/reviews/{taskId}/resolution", PermissionName.KYC_REVIEW),
                    // Ledger adjustments, four-eyes by identity (Phase 3)
                    entry("POST /v1/ledger/adjustments", PermissionName.LEDGER_ADJUST),
                    entry("GET /v1/ledger/adjustments/{id}", PermissionName.LEDGER_ADJUST),
                    entry("DELETE /v1/ledger/adjustments/{id}", PermissionName.LEDGER_ADJUST),
                    entry("POST /v1/ledger/adjustments/{id}/approval", PermissionName.LEDGER_ADJUST),
                    // Transfer reversal (Phase 4), refund (Phase 5), void (P7-TSK-004)
                    entry("POST /v1/transfers/{id}/reversal", PermissionName.TRANSFER_REVERSE),
                    entry("POST /v1/payments/{id}/refund", PermissionName.PAYMENT_REFUND),
                    entry("POST /v1/payments/{id}/void", PermissionName.PAYMENT_REFUND),
                    // Merchant onboarding and administration (Phase 6)
                    entry("POST /v1/operator/merchants", PermissionName.MERCHANT_ONBOARD),
                    entry("GET /v1/operator/merchants/{id}", PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{id}/suspension", PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{id}/reinstatement", PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{id}/closure", PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{id}/api-keys", PermissionName.MERCHANT_ADMINISTER),
                    entry("GET /v1/operator/merchants/{id}/api-keys", PermissionName.MERCHANT_ADMINISTER),
                    entry("DELETE /v1/operator/merchants/{id}/api-keys/{keyId}", PermissionName.MERCHANT_ADMINISTER),
                    // Fee schedules (Phase 6)
                    entry("POST /v1/operator/fee-schedules", PermissionName.FEE_ADMINISTER),
                    entry("GET /v1/operator/fee-schedules", PermissionName.FEE_ADMINISTER),
                    entry("GET /v1/operator/fee-schedules/{id}", PermissionName.FEE_ADMINISTER),
                    entry("POST /v1/operator/fee-schedules/{id}/versions", PermissionName.FEE_ADMINISTER),
                    entry("PUT /v1/operator/merchants/{merchantId}/fee-schedule", PermissionName.FEE_ADMINISTER),
                    entry("GET /v1/operator/merchants/{merchantId}/fee-schedule", PermissionName.FEE_ADMINISTER),
                    // Payout destinations: proposed and withdrawn by administration, decided by approval (Phase 6)
                    entry("POST /v1/operator/merchants/{merchantId}/payout-destinations", PermissionName.MERCHANT_ADMINISTER),
                    entry("GET /v1/operator/merchants/{merchantId}/payout-destinations", PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{merchantId}/payout-destinations/{destinationId}/withdrawal", PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{merchantId}/payout-destinations/{destinationId}/approval", PermissionName.PAYOUT_DESTINATION_APPROVE),
                    entry("POST /v1/operator/merchants/{merchantId}/payout-destinations/{destinationId}/rejection", PermissionName.PAYOUT_DESTINATION_APPROVE),
                    // The operator's payout on a merchant's behalf (Phase 6, ADR-0057 section 6)
                    entry("POST /v1/operator/merchants/{merchantId}/payouts", PermissionName.MERCHANT_PAYOUT),
                    // Rail routing: the versioned policy, availability, the explanation (Phase 7, ADR-0060)
                    entry("POST /v1/operator/routing-policy/versions", PermissionName.PAYMENT_ROUTING_ADMINISTER),
                    entry("POST /v1/operator/rails/{rail}/availability", PermissionName.PAYMENT_ROUTING_ADMINISTER),
                    entry("GET /v1/operator/payments/{intentId}/routing", PermissionName.PAYMENT_ROUTING_ADMINISTER),
                    // Disputes: the operator's reads, each dispute shown audited (Phase 7, ADR-0061)
                    entry("GET /v1/operator/disputes/{disputeId}", PermissionName.DISPUTE_ADMINISTER),
                    entry("GET /v1/operator/payments/{intentId}/disputes", PermissionName.DISPUTE_ADMINISTER),
                    // Disputes: answering a chargeback on behalf, for a payment with no merchant,
                    // reasoned; every evidence read audited (P7-TSK-014, ADR-0061 section 7)
                    entry("POST /v1/operator/disputes/{disputeId}/evidence", PermissionName.DISPUTE_ADMINISTER),
                    entry("GET /v1/operator/disputes/{disputeId}/evidence/{evidenceId}", PermissionName.DISPUTE_ADMINISTER),
                    entry("POST /v1/operator/disputes/{disputeId}/representment", PermissionName.DISPUTE_ADMINISTER),
                    entry("POST /v1/operator/disputes/{disputeId}/acceptance", PermissionName.DISPUTE_ADMINISTER),
                    // The chargeback-ratio report (P7-TSK-015): the standing desk's evidence,
                    // across every merchant, audited per report served
                    entry("GET /v1/operator/reports/chargeback-ratio", PermissionName.MERCHANT_ADMINISTER),
                    // Settlement evidence (P8-TSK-003, ADR-0066): introducing and attesting
                    // is SETTLEMENT_INGEST; every read - and the ONE content path, reasoned
                    // and audited per read (INV-REC-10) - is RECONCILIATION_INVESTIGATE
                    entry("POST /v1/operator/settlement/files", PermissionName.SETTLEMENT_INGEST),
                    entry("POST /v1/operator/settlement/files/{id}/attestation", PermissionName.SETTLEMENT_INGEST),
                    // P8-TSK-021: an operator's pull, the ingest permission's other channel.
                    entry("POST /v1/operator/settlement/sources/{code}/fetch", PermissionName.SETTLEMENT_INGEST),
                    entry("GET /v1/operator/settlement/sources", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/settlement/files", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/settlement/files/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/settlement/refused-deliveries", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("POST /v1/operator/settlement/files/{id}/content-reads", PermissionName.RECONCILIATION_INVESTIGATE),
                    // The parse leg's surfaces (P8-TSK-008): declining is the ingest desk's
                    // reasoned judgement (moved from -003, whose schema had no REJECTED);
                    // the batch read is the parsed totals an attester examines first, and
                    // an investigator's evidence view.
                    entry("POST /v1/operator/settlement/files/{id}/decline", PermissionName.SETTLEMENT_INGEST),
                    // P8-TSK-022: readmission is a controller's recovery - never the ingesting
                    // role's; verification reads content, so it is the investigator's.
                    entry("POST /v1/operator/settlement/files/{id}/readmission", PermissionName.RECONCILIATION_ADMINISTER),
                    entry("POST /v1/operator/settlement/files/{id}/verification", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/settlement/batches/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    // The reconciliation controller's own door (P8-TSK-007, ADR-0067
                    // section 8): adopting history is the CONTROLLER's act, a population
                    // disjoint even from the operator desk it oversees; the positions
                    // report - amounts on the record, audited per serving (ADR-0072) -
                    // stays the investigator's.
                    entry("POST /v1/operator/reconciliation/opening-position", PermissionName.RECONCILIATION_ADMINISTER),
                    // P8-TSK-022: the controller's doors - rule sets under four-eyes, reprocessing
                    // and requeue under ADMINISTER; the replay is the investigator's.
                    entry("GET /v1/operator/reconciliation/rule-sets", PermissionName.RECONCILIATION_ADMINISTER),
                    entry("POST /v1/operator/reconciliation/rule-sets", PermissionName.RECONCILIATION_ADMINISTER),
                    entry("POST /v1/operator/reconciliation/rule-sets/{id}/approval", PermissionName.RECONCILIATION_ADMINISTER),
                    entry("POST /v1/operator/reconciliation/rule-sets/{id}/rejection", PermissionName.RECONCILIATION_ADMINISTER),
                    entry("POST /v1/operator/reconciliation/sources/{code}/reprocessing", PermissionName.RECONCILIATION_ADMINISTER),
                    entry("POST /v1/operator/reconciliation/runs/{id}/requeue", PermissionName.RECONCILIATION_ADMINISTER),
                    entry("POST /v1/operator/reconciliation/runs/{id}/replay", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reports/reconciliation/positions", PermissionName.RECONCILIATION_INVESTIGATE),
                    // P8-TSK-024: the four remaining reports (ADR-0072 section 3) - amounts,
                    // so the investigator's alone, every serving audited.
                    entry("GET /v1/operator/reports/reconciliation/suspense", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reports/reconciliation/unmatched", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reports/reconciliation/summary", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reports/reconciliation/provider-costs", PermissionName.RECONCILIATION_INVESTIGATE),
                    // The matcher's explanation doors (P8-TSK-011, ADR-0068 section 7):
                    // a decision explains itself from stored rows alone; every door the
                    // investigator's, read-only.
                    entry("GET /v1/operator/reconciliation/runs", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/runs/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/decisions/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/allocations/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    // The investigator's desk (P8-TSK-014, ADR-0069 section 7): every door
                    // under the investigator's permission - the controller's role alone is
                    // refused (ReconciliationInvestigationDatabaseTest's negatives).
                    entry("GET /v1/operator/reconciliation/breaks", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/breaks/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/breaks/{id}/trace", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("POST /v1/operator/reconciliation/breaks/{id}/assignment", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("POST /v1/operator/reconciliation/breaks/{id}/notes", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("POST /v1/operator/reconciliation/breaks/{id}/evidence-links", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("POST /v1/operator/reconciliation/breaks/{id}/classification", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/expectations", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/expectations/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/settlement-status", PermissionName.RECONCILIATION_INVESTIGATE),
                    // P8-TSK-015: the resolver's doors - money-moving, template-bound
                    // correction under four-eyes (ADR-0071 section 10). RESOLVE is the
                    // operator's alone: the controller who can loosen a tolerance cannot
                    // resolve the breaks it would hide.
                    entry("POST /v1/operator/reconciliation/breaks/{id}/resolutions", PermissionName.RECONCILIATION_RESOLVE),
                    // P8-TSK-023: a batch's repudiation is a resolution - the resolver's, four-eyes.
                    entry("POST /v1/operator/reconciliation/batches/{settlementBatchId}/repudiation", PermissionName.RECONCILIATION_RESOLVE),
                    // The Phase 8 -> 9 transition (SEC-01): what an approver reads before
                    // approving - every operand and the frozen lines - under the approver's own
                    // permission.
                    entry("GET /v1/operator/reconciliation/resolutions/{id}", PermissionName.RECONCILIATION_RESOLVE),
                    entry("POST /v1/operator/reconciliation/resolutions/{id}/approval", PermissionName.RECONCILIATION_RESOLVE),
                    entry("POST /v1/operator/reconciliation/resolutions/{id}/rejection", PermissionName.RECONCILIATION_RESOLVE),
                    entry("DELETE /v1/operator/reconciliation/resolutions/{id}", PermissionName.RECONCILIATION_RESOLVE),
                    // P9-TSK-007: the FX controller's doors - the pricing policy under four-eyes,
                    // the kill switch one person to stop and two to restart; a population no
                    // other role holds (ADR-0075 section 3).
                    entry("GET /v1/operator/fx/pricing-policies", PermissionName.FX_ADMINISTER),
                    entry("POST /v1/operator/fx/pricing-policies", PermissionName.FX_ADMINISTER),
                    entry("POST /v1/operator/fx/pricing-policies/{id}/approval", PermissionName.FX_ADMINISTER),
                    entry("POST /v1/operator/fx/pricing-policies/{id}/rejection", PermissionName.FX_ADMINISTER),
                    // P9-TSK-025: the operator's FX trade reversal, four-eyes.
                    entry("POST /v1/operator/fx/trades/{id}/reversal", PermissionName.FX_TRADE_REVERSE),
                    entry("POST /v1/operator/fx/trades/{id}/reversal/{rid}/approval", PermissionName.FX_TRADE_REVERSE),
                    entry("POST /v1/operator/fx/trades/{id}/reversal/{rid}/rejection", PermissionName.FX_TRADE_REVERSE),
                    // P9-TSK-027: Phase 9's reports and the payment trace - an investigator's reads.
                    entry("GET /v1/operator/reports/fx/position", PermissionName.FX_INVESTIGATE),
                    entry("GET /v1/operator/reports/fx/revenue", PermissionName.FX_INVESTIGATE),
                    entry("GET /v1/operator/reports/cross-border/corridors", PermissionName.FX_INVESTIGATE),
                    entry("GET /v1/operator/cross-border/payments/{id}/trace", PermissionName.FX_INVESTIGATE),
                    entry("POST /v1/operator/fx/pairs/{pair}/availability", PermissionName.FX_ADMINISTER),
                    entry("POST /v1/operator/fx/providers/{code}/availability", PermissionName.FX_ADMINISTER),
                    entry("POST /v1/operator/fx/enable-requests/{rid}/approval", PermissionName.FX_ADMINISTER),
                    entry("POST /v1/operator/fx/enable-requests/{rid}/rejection", PermissionName.FX_ADMINISTER),
                    // P9-TSK-013: the FX investigator's read of a trade's provenance - held by the
                    // reconciliation operator, never by whoever sets prices; audited at every serving.
                    entry("GET /v1/operator/fx/trades/{id}/provenance", PermissionName.FX_INVESTIGATE),
                    // P9-TSK-015: the corridor doors - the corridor policy under four-eyes, the corridor
                    // kill switch one person to stop and two to restart; held by FX_CONTROLLER beside the
                    // pricing policy (ADR-0080 section 4).
                    entry("GET /v1/operator/cross-border/corridor-policies", PermissionName.CROSSBORDER_ADMINISTER),
                    entry("POST /v1/operator/cross-border/corridor-policies", PermissionName.CROSSBORDER_ADMINISTER),
                    entry("POST /v1/operator/cross-border/corridor-policies/{id}/approval", PermissionName.CROSSBORDER_ADMINISTER),
                    entry("POST /v1/operator/cross-border/corridor-policies/{id}/rejection", PermissionName.CROSSBORDER_ADMINISTER),
                    entry("POST /v1/operator/cross-border/corridors/{corridor}/availability", PermissionName.CROSSBORDER_ADMINISTER),
                    entry("POST /v1/operator/cross-border/corridor-enable-requests/{rid}/approval",
                            PermissionName.CROSSBORDER_ADMINISTER),
                    entry("POST /v1/operator/cross-border/corridor-enable-requests/{rid}/rejection",
                            PermissionName.CROSSBORDER_ADMINISTER),
                    // P9-TSK-016: the counterparty reviewer's door - a person releases or blocks a
                    // screening in review; held by KYC_REVIEWER beside KYC_REVIEW (ADR-0081 point 4).
                    entry("POST /v1/operator/kyc/counterparty-screenings/{id}/decision",
                            PermissionName.COUNTERPARTY_SCREENING_REVIEW),
                    // P10-TSK-011: the scorecard doors - model versions under four eyes, held by
                    // CREDIT_POLICY_OFFICER (ADR-0086 section 3); every act keyed.
                    entry("POST /v1/operator/credit/scorecards", PermissionName.CREDIT_POLICY_ADMINISTER),
                    entry("POST /v1/operator/credit/scorecards/{versionId}/approval", PermissionName.CREDIT_POLICY_ADMINISTER),
                    entry("POST /v1/operator/credit/scorecards/{versionId}/rejection", PermissionName.CREDIT_POLICY_ADMINISTER),
                    // P10-TSK-012: the credit policy doors - policy versions under four eyes, every act keyed,
                    // and the policy in force at an instant for the investigator; both held by
                    // CREDIT_POLICY_OFFICER (ADR-0086 sections 4-5).
                    entry("POST /v1/operator/credit/policies", PermissionName.CREDIT_POLICY_ADMINISTER),
                    entry("POST /v1/operator/credit/policies/{versionId}/approval", PermissionName.CREDIT_POLICY_ADMINISTER),
                    entry("POST /v1/operator/credit/policies/{versionId}/rejection", PermissionName.CREDIT_POLICY_ADMINISTER),
                    entry("GET /v1/operator/credit/policies", PermissionName.CREDIT_INVESTIGATE),
                    // P10-TSK-017: the investigator's doors - a decision's explanation from rows alone, and a record's
                    // raw evidence with a reason; each serving audited, held by CREDIT_POLICY_OFFICER.
                    entry("GET /v1/operator/credit/decisions/{id}/explanation", PermissionName.CREDIT_INVESTIGATE),
                    // P10-TSK-019: the replay - a decision re-derived from its sealed inputs, audited.
                    entry("POST /v1/operator/credit/decisions/{id}/replay", PermissionName.CREDIT_INVESTIGATE),
                    entry("POST /v1/operator/credit/records/{id}/evidence-read", PermissionName.CREDIT_INVESTIGATE),
                    // P10-TSK-020: the three operations reports - counts and rates in one snapshot, each serving audited.
                    entry("GET /v1/operator/reports/credit/outcomes", PermissionName.CREDIT_INVESTIGATE),
                    entry("GET /v1/operator/reports/credit/reasons", PermissionName.CREDIT_INVESTIGATE),
                    entry("GET /v1/operator/reports/credit/sources", PermissionName.CREDIT_INVESTIGATE),
                    // P10-TSK-018: the underwriter's doors - the review queue and the acts on a referral's case, held by
                    // UNDERWRITER alone (ADR-0089 point 8).
                    entry("GET /v1/operator/credit/review-cases", PermissionName.CREDIT_UNDERWRITE),
                    entry("POST /v1/operator/credit/review-cases/{id}/assignment", PermissionName.CREDIT_UNDERWRITE),
                    entry("POST /v1/operator/credit/review-cases/{id}/release", PermissionName.CREDIT_UNDERWRITE),
                    entry("POST /v1/operator/credit/review-cases/{id}/decision", PermissionName.CREDIT_UNDERWRITE),
                    entry("POST /v1/operator/credit/review-cases/{id}/second-approval", PermissionName.CREDIT_UNDERWRITE)));

    /** The MVC mapping, by name - actuator registers a second one (the rule test's reason). */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("the declared permissions are exactly the register, route by route")
    void everyGuardedRouteRequiresItsRegisteredPermission() {
        assertThat(declared())
                .as("a route's permission is a reviewed decision: change the register with it")
                .isEqualTo(REGISTER);
    }

    @Test
    @DisplayName("the register is not vacuous: it holds every phase's privileged surfaces")
    void theRegisterIsNotVacuous() {
        assertThat(REGISTER.values())
                .as("each permission the operator populations hold guards at least one route")
                .contains(
                        PermissionName.IDENTITY_SUSPEND,
                        PermissionName.KYC_REVIEW,
                        PermissionName.LEDGER_ADJUST,
                        PermissionName.TRANSFER_REVERSE,
                        PermissionName.PAYMENT_REFUND,
                        PermissionName.MERCHANT_ONBOARD,
                        PermissionName.MERCHANT_ADMINISTER,
                        PermissionName.FEE_ADMINISTER,
                        PermissionName.PAYOUT_DESTINATION_APPROVE,
                        PermissionName.MERCHANT_PAYOUT,
                        PermissionName.PAYMENT_ROUTING_ADMINISTER,
                        PermissionName.DISPUTE_ADMINISTER,
                        PermissionName.FX_ADMINISTER,
                        PermissionName.CROSSBORDER_ADMINISTER,
                        PermissionName.COUNTERPARTY_SCREENING_REVIEW,
                        PermissionName.CREDIT_POLICY_ADMINISTER,
                        PermissionName.CREDIT_INVESTIGATE);
    }

    private Map<String, PermissionName> declared() {
        Map<String, PermissionName> declared = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> route :
                mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = route.getValue();
            if (!handler.getBeanType().getName().startsWith("com.finapp.")) {
                continue;
            }
            RequiresPermission rule = handler.getMethodAnnotation(RequiresPermission.class);
            if (rule == null) {
                rule = handler.getBeanType().getAnnotation(RequiresPermission.class);
            }
            if (rule == null) {
                continue;
            }
            for (RequestMethod method : route.getKey().getMethodsCondition().getMethods()) {
                for (String path : route.getKey().getPatternValues()) {
                    declared.put(method.name() + " " + path, rule.value());
                }
            }
        }
        return declared;
    }
}
