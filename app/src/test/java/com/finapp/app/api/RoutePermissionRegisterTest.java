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
                    // P11-TSK-004: the lending treasury's doors - four-eyes contributions and the capital report.
                    entry("POST /v1/operator/lending/capital-contributions", PermissionName.LENDING_CAPITAL_ADMINISTER),
                    entry("POST /v1/operator/lending/capital-contributions/{contributionId}/approval",
                            PermissionName.LENDING_CAPITAL_ADMINISTER),
                    entry("POST /v1/operator/lending/capital-contributions/{contributionId}/rejection",
                            PermissionName.LENDING_CAPITAL_ADMINISTER),
                    entry("GET /v1/operator/lending/capital", PermissionName.LENDING_INVESTIGATE),
                    // P11-TSK-005: the lending officer's terms doors - four-eyes terms versions (LENDING_ADMINISTER)
                    // and the terms in force at an instant (LENDING_INVESTIGATE); both held by LENDING_OFFICER.
                    entry("POST /v1/operator/lending/terms", PermissionName.LENDING_ADMINISTER),
                    entry("POST /v1/operator/lending/terms/{versionId}/approval", PermissionName.LENDING_ADMINISTER),
                    entry("POST /v1/operator/lending/terms/{versionId}/rejection", PermissionName.LENDING_ADMINISTER),
                    entry("GET /v1/operator/lending/terms", PermissionName.LENDING_INVESTIGATE),
                    // P11-TSK-020: the loan servicing agent's doors - any loan read (audited lending.LoanRead) and the repayment
                    // reversal under four eyes: LOAN_SERVICE proposes and rejects, LOAN_SERVICE_APPROVE approves (never one's
                    // own - the domain and lending V016's CHECK); the negatives are RepaymentReversalApiTest's.
                    entry("GET /v1/operator/loans/{id}", PermissionName.LOAN_SERVICE),
                    entry("POST /v1/operator/loans/{id}/repayments/{repaymentId}/reversal", PermissionName.LOAN_SERVICE),
                    entry("POST /v1/operator/loans/{id}/repayments/{repaymentId}/reversal/approval",
                            PermissionName.LOAN_SERVICE_APPROVE),
                    entry("POST /v1/operator/loans/{id}/repayments/{repaymentId}/reversal/rejection",
                            PermissionName.LOAN_SERVICE),
                    // P11-TSK-025: the waiver doors under four eyes - LOAN_SERVICE proposes and rejects, LOAN_SERVICE_APPROVE
                    // approves (never one's own - the domain and lending V021's CHECK); the negatives are WaiverApiTest's.
                    entry("POST /v1/operator/loans/{id}/waivers", PermissionName.LOAN_SERVICE),
                    entry("POST /v1/operator/loans/{id}/waivers/{waiverId}/approval", PermissionName.LOAN_SERVICE_APPROVE),
                    entry("POST /v1/operator/loans/{id}/waivers/{waiverId}/rejection", PermissionName.LOAN_SERVICE),
                    // P11-TSK-027: the restructuring doors under four eyes - LOAN_SERVICE proposes and rejects,
                    // LOAN_SERVICE_APPROVE approves (never one's own - the domain and lending V023's CHECK); the customer's
                    // acceptance is a session door; the negatives are AmendmentApiTest's.
                    entry("POST /v1/operator/loans/{id}/amendments", PermissionName.LOAN_SERVICE),
                    entry("POST /v1/operator/loans/{id}/amendments/{amendmentId}/approval",
                            PermissionName.LOAN_SERVICE_APPROVE),
                    entry("POST /v1/operator/loans/{id}/amendments/{amendmentId}/rejection", PermissionName.LOAN_SERVICE),
                    // P11-TSK-029: the lending investigator's doors - a loan's explanation from its rows (audited
                    // lending.ExplanationRead) and its replay with a reason (audited lending.LoanReplayed), never of the
                    // investigator's own party's loan; the negatives are LendingProofApiTest's.
                    entry("GET /v1/operator/loans/{id}/explanation", PermissionName.LENDING_INVESTIGATE),
                    entry("POST /v1/operator/loans/{id}/replay", PermissionName.LENDING_INVESTIGATE),
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
                    // P11-TSK-030: the four lending reports - amounts as of a business date in one snapshot, each serving
                    // recorded lending.ReportRead.
                    entry("GET /v1/operator/reports/lending/portfolio", PermissionName.LENDING_INVESTIGATE),
                    entry("GET /v1/operator/reports/lending/delinquency", PermissionName.LENDING_INVESTIGATE),
                    entry("GET /v1/operator/reports/lending/accrual", PermissionName.LENDING_INVESTIGATE),
                    entry("GET /v1/operator/reports/lending/capital", PermissionName.LENDING_INVESTIGATE),
                    // P10-TSK-018: the underwriter's doors - the review queue and the acts on a referral's case, held by
                    // UNDERWRITER alone (ADR-0089 point 8).
                    entry("GET /v1/operator/credit/review-cases", PermissionName.CREDIT_UNDERWRITE),
                    entry("POST /v1/operator/credit/review-cases/{id}/assignment", PermissionName.CREDIT_UNDERWRITE),
                    entry("POST /v1/operator/credit/review-cases/{id}/release", PermissionName.CREDIT_UNDERWRITE),
                    entry("POST /v1/operator/credit/review-cases/{id}/decision", PermissionName.CREDIT_UNDERWRITE),
                    entry("POST /v1/operator/credit/review-cases/{id}/second-approval", PermissionName.CREDIT_UNDERWRITE),
                    // P12-TSK-004: the BNPL officer's terms doors - four-eyes terms versions and their reads, every route
                    // BNPL_ADMINISTER (held by BNPL_OFFICER); the negatives are BnplTermsEndpointDatabaseTest's.
                    entry("POST /v1/operator/bnpl/terms", PermissionName.BNPL_ADMINISTER),
                    entry("POST /v1/operator/bnpl/terms/{versionId}/approval", PermissionName.BNPL_ADMINISTER),
                    entry("POST /v1/operator/bnpl/terms/{versionId}/rejection", PermissionName.BNPL_ADMINISTER),
                    // Risk policy versions under four eyes (P13-TSK-002): the risk officer's desk
                    entry("POST /v1/operator/risk/policies", PermissionName.RISK_POLICY_ADMINISTER),
                    entry("POST /v1/operator/risk/policies/{versionId}/activation", PermissionName.RISK_POLICY_ADMINISTER),
                    entry("POST /v1/operator/risk/policies/{versionId}/rejection", PermissionName.RISK_POLICY_ADMINISTER),
                    entry("GET /v1/operator/risk/policies", PermissionName.RISK_POLICY_ADMINISTER),
                    entry("GET /v1/operator/risk/policies/{versionId}", PermissionName.RISK_POLICY_ADMINISTER),
                    // P12-TSK-006: a merchant's BNPL enablement under four eyes, and its BNPL fee schedule per product.
                    entry("POST /v1/operator/merchants/{merchantId}/bnpl-enablement", PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{merchantId}/bnpl-enablement/{enablementId}/approval",
                            PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{merchantId}/bnpl-enablement/{enablementId}/rejection",
                            PermissionName.MERCHANT_ADMINISTER),
                    entry("POST /v1/operator/merchants/{merchantId}/bnpl-enablement/{enablementId}/withdrawal",
                            PermissionName.MERCHANT_ADMINISTER),
                    entry("PUT /v1/operator/merchants/{merchantId}/bnpl-fee-schedules/{product}",
                            PermissionName.FEE_ADMINISTER),
                    entry("GET /v1/operator/bnpl/terms", PermissionName.BNPL_ADMINISTER),
                    // The operator's BNPL refund beside the merchant's (P12-TSK-021, ADR-0105 decision 9): the payments
                    // refund's one permission, the money-operating population - the ADR's default.
                    entry("POST /v1/operator/bnpl-purchases/{id}/refunds", PermissionName.PAYMENT_REFUND),
                    entry("GET /v1/operator/bnpl/terms/{versionId}", PermissionName.BNPL_ADMINISTER),
                    // The BNPL servicing corrections under four eyes (P12-TSK-024): a BNPL_SERVICE holder proposes and
                    // rejects (the proposer's withdrawal included), a different BNPL_SERVICE_APPROVE holder approves - never
                    // one's own (the domain and bnpl V016's CHECKs); the negatives are BnplServicingApiTest's.
                    entry("POST /v1/operator/bnpl-agreements/{id}/repayments/{repaymentId}/reversal",
                            PermissionName.BNPL_SERVICE),
                    entry("POST /v1/operator/bnpl-agreements/{id}/repayments/{repaymentId}/reversal/approval",
                            PermissionName.BNPL_SERVICE_APPROVE),
                    entry("POST /v1/operator/bnpl-agreements/{id}/repayments/{repaymentId}/reversal/rejection",
                            PermissionName.BNPL_SERVICE),
                    entry("POST /v1/operator/bnpl-agreements/{id}/waivers", PermissionName.BNPL_SERVICE),
                    entry("POST /v1/operator/bnpl-agreements/{id}/waivers/{waiverId}/approval",
                            PermissionName.BNPL_SERVICE_APPROVE),
                    entry("POST /v1/operator/bnpl-agreements/{id}/waivers/{waiverId}/rejection",
                            PermissionName.BNPL_SERVICE),
                    // The BNPL investigator's doors (P12-TSK-025): BNPL_INVESTIGATE (held by BNPL_OFFICER) - the agreement
                    // read, its explanation and its replay, each audited, never of the investigator's own party's agreement.
                    // The negatives are BnplInvestigationDeskTest's.
                    entry("GET /v1/operator/bnpl-agreements/{id}", PermissionName.BNPL_INVESTIGATE),
                    entry("GET /v1/operator/bnpl-agreements/{id}/explanation", PermissionName.BNPL_INVESTIGATE),
                    entry("POST /v1/operator/bnpl-agreements/{id}/replay", PermissionName.BNPL_INVESTIGATE),
                    // P12-TSK-026: the four BNPL operations reports - amounts as of a business date in one snapshot, each
                    // serving recorded bnpl.ReportRead.
                    entry("GET /v1/operator/reports/bnpl/portfolio", PermissionName.BNPL_INVESTIGATE),
                    entry("GET /v1/operator/reports/bnpl/merchant-financing", PermissionName.BNPL_INVESTIGATE),
                    entry("GET /v1/operator/reports/bnpl/refunds", PermissionName.BNPL_INVESTIGATE),
                    entry("GET /v1/operator/reports/bnpl/delinquency", PermissionName.BNPL_INVESTIGATE),
                    // The operator's BNPL dispute doors (P12-TSK-023, ADR-0106 decision 5): BNPL_DISPUTE_RESOLVE (held by
                    // BNPL_SERVICING_AGENT) - four-eyes is inside the permission, two distinct holders, never between
                    // roles; the read audited. The negatives are BnplDisputeDeskTest's.
                    entry("GET /v1/operator/bnpl-disputes/{id}", PermissionName.BNPL_DISPUTE_RESOLVE),
                    // The Phase 12 -> 13 gate's A7-2: the queue of contested (or open) disputes, audited per page.
                    entry("GET /v1/operator/bnpl-disputes", PermissionName.BNPL_DISPUTE_RESOLVE),
                    entry("POST /v1/operator/bnpl-disputes/{id}/resolution", PermissionName.BNPL_DISPUTE_RESOLVE),
                    entry("POST /v1/operator/bnpl-disputes/{id}/resolution/approval",
                            PermissionName.BNPL_DISPUTE_RESOLVE),
                    entry("POST /v1/operator/bnpl-disputes/{id}/resolution/rejection",
                            PermissionName.BNPL_DISPUTE_RESOLVE),
                    // P13-TSK-019: the risk officer's monitoring door - a reprocessing of the intake ordered from an
                    // earlier watermark (keyed, reasoned, audited risk.ReprocessingOrdered); RISK_POLICY_ADMINISTER
                    // (held by RISK_OFFICER); the negatives are RiskMonitoringEndpointDatabaseTest's.
                    entry("POST /v1/operator/risk/monitoring/reprocessing", PermissionName.RISK_POLICY_ADMINISTER),
                    // P13-TSK-007: the analyst's customer risk profile door - a party's rating history, audited
                    // risk.ProfileRead, the analyst's own party refused risk.SelfDealingRefused; RISK_INVESTIGATE (held
                    // by RISK_ANALYST); the negatives are ProfileApiTest's.
                    entry("GET /v1/operator/risk/parties/{partyId}/profile", PermissionName.RISK_INVESTIGATE),
                    // P13-TSK-004: the analyst's assessment doors - the sealed assessment read (audited
                    // risk.AssessmentRead) and its replay from the seal (reasoned, audited risk.Replayed), the analyst's
                    // own party refused risk.SelfDealingRefused; RISK_INVESTIGATE (held by RISK_ANALYST); the negatives
                    // are RiskAssessmentApiTest's.
                    entry("GET /v1/operator/risk/assessments/{assessmentId}", PermissionName.RISK_INVESTIGATE),
                    entry("POST /v1/operator/risk/assessments/{assessmentId}/replay", PermissionName.RISK_INVESTIGATE),
                    // P13-TSK-012: the risk officer's override doors - the overridden assessment served under the
                    // officer's own permission (roles are a partition: RISK_OFFICER holds no RISK_INVESTIGATE), propose,
                    // approve (a second holder at or above C7, MULTI_FACTOR; the proposer refused
                    // risk.SelfApprovalRefused) and reject; RISK_OVERRIDE (held by RISK_OFFICER); the negatives are
                    // RiskOverrideApiTest's.
                    entry("GET /v1/operator/risk/assessments/{assessmentId}/overrides", PermissionName.RISK_OVERRIDE),
                    entry("POST /v1/operator/risk/assessments/{assessmentId}/overrides", PermissionName.RISK_OVERRIDE),
                    entry("GET /v1/operator/risk/assessments/{assessmentId}/overrides/{overrideId}",
                            PermissionName.RISK_OVERRIDE),
                    entry("POST /v1/operator/risk/assessments/{assessmentId}/overrides/{overrideId}/approval",
                            PermissionName.RISK_OVERRIDE),
                    entry("POST /v1/operator/risk/assessments/{assessmentId}/overrides/{overrideId}/rejection",
                            PermissionName.RISK_OVERRIDE),
                    // P13-TSK-016: the analyst's case doors - a case opened by hand, the investigator's acts (take,
                    // note, evidence, escalate; the body carrying the revision) and a disposition proposed under
                    // RISK_INVESTIGATE; its approval (a second holder, MULTI_FACTOR; the proposer refused
                    // risk.SelfApprovalRefused) and rejection under RISK_CASE_APPROVE - both held by RISK_ANALYST; the
                    // queue and the case read are P13-TSK-018's (below); the negatives are RiskCaseApiTest's.
                    entry("POST /v1/operator/risk/cases", PermissionName.RISK_INVESTIGATE),
                    entry("POST /v1/operator/risk/cases/{caseId}/actions", PermissionName.RISK_INVESTIGATE),
                    entry("POST /v1/operator/risk/cases/{caseId}/dispositions", PermissionName.RISK_INVESTIGATE),
                    entry("POST /v1/operator/risk/cases/{caseId}/dispositions/{dispositionId}/approval",
                            PermissionName.RISK_CASE_APPROVE),
                    entry("POST /v1/operator/risk/cases/{caseId}/dispositions/{dispositionId}/rejection",
                            PermissionName.RISK_CASE_APPROVE),
                    // P13-TSK-017: a downstream action proposed on a taken case under RISK_INVESTIGATE; its approval
                    // (a second holder, MULTI_FACTOR, 202 - the platform dispatches it through the owning domain's
                    // keyed door) and rejection under RISK_CASE_APPROVE; the negatives are RiskActionApiTest's.
                    entry("POST /v1/operator/risk/cases/{caseId}/risk-actions", PermissionName.RISK_INVESTIGATE),
                    entry("POST /v1/operator/risk/cases/{caseId}/risk-actions/{actionId}/approval",
                            PermissionName.RISK_CASE_APPROVE),
                    entry("POST /v1/operator/risk/cases/{caseId}/risk-actions/{actionId}/rejection",
                            PermissionName.RISK_CASE_APPROVE),
                    // P13-TSK-018: the investigator's reads - the case queue, a case's file, the alert queue - under
                    // RISK_INVESTIGATE, each audited and self-dealing withheld or refused; the suppression door over
                    // P13-TSK-015's machine - the proposal under RISK_INVESTIGATE, the approval (a second holder,
                    // MULTI_FACTOR; the proposer refused risk.SelfApprovalRefused) and the rejection under
                    // RISK_CASE_APPROVE; the negatives are QueueApiTest's and AlertSuppressionApiTest's.
                    entry("GET /v1/operator/risk/cases", PermissionName.RISK_INVESTIGATE),
                    entry("GET /v1/operator/risk/cases/{caseId}", PermissionName.RISK_INVESTIGATE),
                    entry("GET /v1/operator/risk/alerts", PermissionName.RISK_INVESTIGATE),
                    entry("POST /v1/operator/risk/alerts/{alertId}/suppression", PermissionName.RISK_INVESTIGATE),
                    entry("POST /v1/operator/risk/alerts/{alertId}/suppression/approval",
                            PermissionName.RISK_CASE_APPROVE),
                    entry("POST /v1/operator/risk/alerts/{alertId}/suppression/rejection",
                            PermissionName.RISK_CASE_APPROVE),
                    // P13-TSK-021: the AML desk's regulatory report - drafted and approved (a second holder,
                    // MULTI_FACTOR) under AML_REPORT_APPROVE, filed (MULTI_FACTOR, 202) under AML_REPORT_FILE, and read
                    // (the narrative's one door, DESIGN-021 D9) under AML_REPORT_APPROVE; the negatives are
                    // ReportApiTest's (another desk 403, no session 401, the drafter's approval 403).
                    entry("POST /v1/operator/risk/cases/{caseId}/reports", PermissionName.AML_REPORT_APPROVE),
                    entry("POST /v1/operator/risk/cases/{caseId}/reports/{reportId}/approval",
                            PermissionName.AML_REPORT_APPROVE),
                    entry("POST /v1/operator/risk/cases/{caseId}/reports/{reportId}/filing",
                            PermissionName.AML_REPORT_FILE),
                    entry("GET /v1/operator/risk/cases/{caseId}/reports/{reportId}",
                            PermissionName.AML_REPORT_APPROVE),
                    // P13-TSK-023: the phase's proofs as operator-runnable, audited reads under RISK_INVESTIGATE - the
                    // four operations reports with their censuses (audited risk.ReportRead), the day's replay (reasoned,
                    // audited risk.Replayed) and the case's evidence door (audited risk.CaseRead, self-dealing refused);
                    // the negatives are RiskReportsApiTest's (the risk officer 403, no session 401).
                    entry("GET /v1/operator/reports/risk/decisions", PermissionName.RISK_INVESTIGATE),
                    entry("GET /v1/operator/reports/risk/alerts", PermissionName.RISK_INVESTIGATE),
                    entry("GET /v1/operator/reports/risk/cases", PermissionName.RISK_INVESTIGATE),
                    entry("GET /v1/operator/reports/risk/velocity", PermissionName.RISK_INVESTIGATE),
                    entry("POST /v1/operator/risk/assessments/replay", PermissionName.RISK_INVESTIGATE),
                    entry("GET /v1/operator/risk/cases/{caseId}/evidence/{evidenceKind}/{evidenceId}",
                            PermissionName.RISK_INVESTIGATE)));

    /**
     * The CONDITIONAL half (the Phase 12 -> 13 gate's A5-F1): a permission a route asks BESIDE its declared one when the
     * request names what needs it - here a merchant key's scope. An annotation cannot carry a condition, so the register
     * pins the mapping the route's operations apply ({@code MerchantApiKeyOperations.SCOPE_PERMISSIONS}); the negatives
     * over HTTP are {@code MerchantApiKeyDatabaseTest#aRefundScopeAsksPaymentRefundBesideMerchantAdminister}'s.
     */
    private static final Map<String, PermissionName> CONDITIONAL_REGISTER = new TreeMap<>(Map.ofEntries(
            // Issuing a key that refunds the merchant's BNPL purchases and accepts its disputes is a money-operating act.
            entry("POST /v1/operator/merchants/{id}/api-keys?scope=BNPL_REFUND", PermissionName.PAYMENT_REFUND)));

    /** The MVC mapping, by name - actuator registers a second one (the rule test's reason). */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("the conditional permissions are exactly the register: a BNPL_REFUND-scoped key's issuance asks"
            + " PAYMENT_REFUND beside the route's MERCHANT_ADMINISTER (the Phase 12 -> 13 gate's A5-F1)")
    void everyConditionalPermissionIsRegistered() {
        Map<String, PermissionName> applied = new TreeMap<>();
        com.finapp.app.merchant.MerchantApiKeyOperations.SCOPE_PERMISSIONS.forEach((scope, permission) -> applied.put(
                "POST /v1/operator/merchants/{id}/api-keys?scope=" + scope.name(), permission));
        assertThat(applied).as("a conditional permission is a reviewed decision too").isEqualTo(CONDITIONAL_REGISTER);
        for (String route : CONDITIONAL_REGISTER.keySet()) {
            assertThat(REGISTER).as("the conditional route's declared permission stays registered")
                    .containsKey(route.substring(0, route.indexOf('?')));
        }
    }

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
                        PermissionName.CREDIT_INVESTIGATE,
                        PermissionName.CREDIT_UNDERWRITE,
                        PermissionName.RISK_OVERRIDE);
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
