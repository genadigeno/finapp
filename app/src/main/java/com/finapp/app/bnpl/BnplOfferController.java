package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's BNPL offer doors (`P12-TSK-011`; PHASE_12_PLAN.md section 9, BNPL_LIFECYCLES.md section 4.3): read one's
 * own offer - the plan, the illustrative schedule, the totals, the expiry and the document - and decline it while it is
 * {@code OFFERED} and unexpired, keyed ({@code bnpl.offer-decline:CUSTOMER:<id>}) under the platform's conditional step-up.
 * The decline has no body. And accept it (`P12-TSK-013`) - the purchase: a {@code MULTI_FACTOR} act, keyed
 * ({@code bnpl.offer-acceptance:CUSTOMER:<id>}), echoing the offer's terms hash - {@code 201} with the agreement, the
 * purchase and checkout's order: the session is paid.
 */
@RestController
@RequestMapping(path = "/me/bnpl-offers", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class BnplOfferController {

    @NonNull private final BnplOfferDesk desk;
    @NonNull private final BnplAgreementDesk agreements;

    /** The caller's own offer, its terms re-derived and verified against their stored hashes. */
    @GetMapping("/{id}")
    public BnplOfferDesk.BnplOfferView readBnplOffer(@PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    /** Declines the caller's offer while it is OFFERED and before its expiry on the database clock. */
    @PostMapping("/{id}/decline")
    @RequiresIdempotencyKey
    public BnplOfferDesk.BnplOfferDecision declineBnplOffer(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.decline(current(request), id, idempotencyKey);
    }

    /**
     * Accepts the caller's offer - the purchase: credit's decision consumed, the merchant financed, the obligation born and
     * the checkout session completed - only while it is offered and unexpired on the database clock, and only for the
     * terms whose hash is echoed.
     */
    @PostMapping(path = "/{id}/acceptance", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplAgreementDesk.BnplAcceptedView acceptBnplOffer(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplAcceptanceBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return agreements.accept(current(request), id, idempotencyKey, body,
                Optional.ofNullable(request.getRemoteAddr()), Optional.ofNullable(request.getHeader(HttpHeaders.USER_AGENT)));
    }

    /**
     * An acceptance: the offer's terms hash as the offer read showed it (lowercase hexadecimal). Closed - an unknown field
     * is {@code 422}; nothing about the evidence is the body's to claim.
     */
    @ClosedBody
    public record BnplAcceptanceBody(@NotBlank @Size(max = 64) String termsSha256) {}

    static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: a BNPL offer door is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
