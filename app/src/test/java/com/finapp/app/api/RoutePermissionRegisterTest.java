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
                    entry("GET /v1/operator/settlement/batches/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    // The reconciliation controller's own door (P8-TSK-007, ADR-0067
                    // section 8): adopting history is the CONTROLLER's act, a population
                    // disjoint even from the operator desk it oversees; the positions
                    // report - amounts on the record, audited per serving (ADR-0072) -
                    // stays the investigator's.
                    entry("POST /v1/operator/reconciliation/opening-position", PermissionName.RECONCILIATION_ADMINISTER),
                    entry("GET /v1/operator/reports/reconciliation/positions", PermissionName.RECONCILIATION_INVESTIGATE),
                    // The matcher's explanation doors (P8-TSK-011, ADR-0068 section 7):
                    // a decision explains itself from stored rows alone; every door the
                    // investigator's, read-only.
                    entry("GET /v1/operator/reconciliation/runs", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/runs/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/decisions/{id}", PermissionName.RECONCILIATION_INVESTIGATE),
                    entry("GET /v1/operator/reconciliation/allocations/{id}", PermissionName.RECONCILIATION_INVESTIGATE)));

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
                        PermissionName.DISPUTE_ADMINISTER);
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
