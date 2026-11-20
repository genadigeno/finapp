package com.finapp.app.crossborder;

import com.finapp.app.session.RecordsRefusals;
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
 * The cross-border operator's reads (`P17-TSK-037`, gaps G-101 and G-103): the corridors with their availability and the
 * corridor enable requests under {@code CROSSBORDER_ADMINISTER} - the controller who switches and approves reads what they
 * decide; the payments under {@code FX_INVESTIGATE}, an investigator's read as a payment's trace is. Read-only: every act stays
 * on {@code CorridorAdministrationController}. The lists that grow are §7 collections.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/cross-border", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CrossBorderReadController {

    @NonNull private final CrossBorderReadDesk desk;

    /** The active corridor policy's corridors, each with its availability and who changed it, when. */
    @GetMapping("/corridors")
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    public CrossBorderReadDesk.CorridorAvailabilityList listOperatorCorridors() {
        return desk.corridors();
    }

    /** The corridor enable requests, newest first: {@code status} PROPOSED, APPROVED or REJECTED narrows them. */
    @GetMapping("/corridor-enable-requests")
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    public CrossBorderReadDesk.CorridorEnableRequestPage listCorridorEnableRequests(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.enableRequests(status, cursor, limit);
    }

    /**
     * The payments created in {@code [from, to)} - at most 31 days - newest first, of one corridor ({@code EUR-USD-US}) and one
     * status when named. Every page recorded {@code crossborder.CrossBorderPaymentsListed}.
     */
    @GetMapping("/payments")
    @RequiresPermission(PermissionName.FX_INVESTIGATE)
    public CrossBorderReadDesk.CrossBorderPaymentPage listOperatorCrossBorderPayments(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "corridor", required = false) String corridor,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.payments(from, to, corridor, status, cursor, limit);
    }
}
