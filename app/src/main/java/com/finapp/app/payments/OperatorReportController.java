package com.finapp.app.payments;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's reports (`P7-TSK-015`, {@code PHASE_7_PLAN.md} §15) — the first: the chargeback
 * ratio per merchant, which is a REPORT and never a metric tag (ADR-0018: a merchant tag is
 * unbounded cardinality, and a tenant's data in a system with its own access control).
 *
 * <p>Under {@link PermissionName#MERCHANT_ADMINISTER} — the desk that rules on a merchant's
 * standing, which is what the ratio informs — across every tenant by design, and every report
 * served on the record ({@code payments.ChargebackRatioRead}). Read-only: nothing is claimed,
 * keyed or written but the audit record. Handler names are deliberately distinctive (the springdoc
 * {@code operationId} rule, {@code OpenApiContractTest}).
 */
@RestController
@RequestMapping(path = "/operator/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OperatorReportController {

    @NonNull private final DisputeOperations disputes;

    /**
     * Every merchant's card sales and chargebacks for one calendar month, UTC, worst first, at most
     * 100 with {@code truncated} saying when there were more. {@code month} is {@code YYYY-MM},
     * the current month when absent; anything else is the one 422.
     */
    @GetMapping("/chargeback-ratio")
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public DisputeOperations.ChargebackRatioReport readChargebackRatioReport(
            @RequestParam(value = "month", required = false) String month) {
        return disputes.chargebackRatioReport(month);
    }
}
