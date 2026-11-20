package com.finapp.app.risk;

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
 * The held card payments, read by a risk investigator (`P17-TSK-032`, gap G-43): a §7 collection - {@code limit} 50 unless
 * named, 200 at most, the opaque {@code cursor} of the page before - of the open capture holds, the deadline earliest first, the
 * reader's own party's withheld; a reader the platform cannot place {@code 403 risk.SelfDealingRefused}. Read-only: the doors that
 * capture or void a held payment stay risk's override and action doors (ADR-0112). Every page recorded
 * {@code payments.HeldPaymentsListed}.
 */
@RestController
@RequestMapping(path = "/operator/held-payments", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class HeldPaymentController {

    @NonNull private final HeldPaymentReadDesk desk;

    @GetMapping
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public HeldPaymentReadDesk.HeldPaymentPage listHeldPayments(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.heldPayments(cursor, limit);
    }
}
