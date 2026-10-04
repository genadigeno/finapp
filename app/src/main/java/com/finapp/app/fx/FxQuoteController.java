package com.finapp.app.fx;

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
 * The customer's FX quote doors (`P9-TSK-008`; PHASE_9_PLAN.md section 9): request a quote (keyed,
 * synchronous - 2 s per provider, 5 s in all), read it, cancel it, and discover the offered pairs.
 * The body is closed and carries no rate: the price is the platform's, never the client's.
 */
@RestController
@RequestMapping(path = "/me/fx", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class FxQuoteController {

    @NonNull private final FxQuoteDesk desk;
    @NonNull private final FxConversionDesk conversions;

    @PostMapping(path = "/quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public FxQuoteDesk.FxQuoteView requestQuote(
            @Valid @RequestBody QuoteRequestBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.request(current(request), idempotencyKey, body);
    }

    @GetMapping("/quotes/{id}")
    public FxQuoteDesk.FxQuoteView readQuote(@PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    @PostMapping("/quotes/{id}/cancellation")
    @RequiresIdempotencyKey
    public FxQuoteDesk.FxQuoteView cancelQuote(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.cancel(current(request), id, idempotencyKey);
    }

    @GetMapping("/pairs")
    public FxQuoteDesk.OfferedPairs pairs() {
        return desk.pairs();
    }

    /** Converts at a quote: synchronous and final - one transaction, no provider call (`P9-TSK-009`). */
    @PostMapping(path = "/conversions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public FxConversionDesk.FxTradeView convert(
            @Valid @RequestBody ConversionRequestBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return conversions.convert(current(request), idempotencyKey, body);
    }

    @GetMapping("/conversions/{tradeId}")
    public FxConversionDesk.FxTradeView readConversion(@PathVariable("tradeId") String tradeId, HttpServletRequest request) {
        return conversions.read(current(request), tradeId);
    }

    /** A conversion request: the quote to accept, and nothing else - closed, with no rate field. */
    @ClosedBody
    public record ConversionRequestBody(@NotBlank @Size(max = 64) String quoteId) {}

    /**
     * A quote request: the pair, which side is fixed, and its exact amount as a decimal string.
     * Closed - an unknown field, a rate above all, is {@code 422} - and there is no rate field.
     */
    @ClosedBody
    public record QuoteRequestBody(
            @NotBlank @Size(max = 3) String sourceCurrency,
            @NotBlank @Size(max = 3) String destinationCurrency,
            @NotBlank @Size(max = 32) String fixedSide,
            @NotBlank @Size(max = 32) String amount) {}

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/fx is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
