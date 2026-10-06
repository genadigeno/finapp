package com.finapp.app.crossborder;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
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
 * The customer's cross-border offers (`P9-TSK-018`, `PHASE_9_PLAN.md` section 9): an offer disclosing the rate,
 * the margin over mid, the corridor's fee, the total debit and the guaranteed destination amount, frozen - and
 * read back by its owner. A closed body: the amount is a decimal string, and no rate is ever accepted.
 */
@RestController
@RequestMapping(path = "/me/cross-border/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class CrossBorderQuoteController {

    @NonNull private final CrossBorderQuoteDesk desk;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public CrossBorderQuoteDesk.CrossBorderOfferView requestCrossBorderQuote(
            @Valid @RequestBody CrossBorderQuoteRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.quote(current(request), idempotencyKey, body);
    }

    @GetMapping("/{id}")
    public CrossBorderQuoteDesk.CrossBorderOfferView readCrossBorderQuote(@PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/cross-border/quotes is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }

    /** The beneficiary, the source currency, which side is fixed, and the fixed amount as a decimal string. */
    @ClosedBody
    public record CrossBorderQuoteRequest(
            @NotBlank String beneficiaryId,
            @NotBlank String sourceCurrency,
            @NotBlank String fixedSide,
            @NotBlank String amount) {}
}
