package com.finapp.app.fx;

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
 * The FX operator's reads (`P17-TSK-037`, gaps G-100…G-102): the reference rates and their age, and the booked trades, under
 * {@code FX_INVESTIGATE} - an investigator's reads, as a trade's provenance and the FX reports are; each pair's and provider's
 * availability and the enable requests under {@code FX_ADMINISTER} - the controller who switches and approves reads what they
 * decide. Read-only: every act stays on {@code FxAdministrationController}. The lists that grow are §7 collections.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/fx", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FxReadController {

    @NonNull private final FxReadDesk desk;

    /** The declared reference source's latest rate of each canonical pair, its instants and its age in seconds. */
    @GetMapping("/rates")
    @RequiresPermission(PermissionName.FX_INVESTIGATE)
    public FxReadDesk.FxRateList listFxReferenceRates() {
        return desk.rates();
    }

    /** Every directed pair of the postable currencies, with its availability and who changed it, when. */
    @GetMapping("/pairs")
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    public FxReadDesk.FxAvailabilityList listFxPairs() {
        return desk.pairs();
    }

    /** Every declared provider, with its availability and who changed it, when. */
    @GetMapping("/providers")
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    public FxReadDesk.FxAvailabilityList listFxProviders() {
        return desk.providers();
    }

    /** The enable requests, newest first: {@code status} PROPOSED, APPROVED or REJECTED narrows them. */
    @GetMapping("/enable-requests")
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    public FxReadDesk.FxEnableRequestPage listFxEnableRequests(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.enableRequests(status, cursor, limit);
    }

    /**
     * The trades booked in {@code [from, to)} - at most 31 days - newest first, of one pair ({@code EUR-USD}) and one status
     * when named. Every page recorded {@code fx.TradesListed}.
     */
    @GetMapping("/trades")
    @RequiresPermission(PermissionName.FX_INVESTIGATE)
    public FxReadDesk.FxTradePage listFxTrades(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "pair", required = false) String pair,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.trades(from, to, pair, status, cursor, limit);
    }
}
