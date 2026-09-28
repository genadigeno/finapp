package com.finapp.app.payments;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
 * The customer's withdrawals over HTTP (`P7-TSK-008`, ADR-0062 §6): the keyed dispatch and
 * the ownership-scoped read — the `/v1/me` shape, permission-free because the session IS the
 * authorization and every statement carries the owner ({@code ADR-0031}); the conditional
 * step-up is the service's, at the write.
 *
 * <p><strong>Keyed</strong> ({@code INV-IDEM-01}): money leaves the platform, so a lost
 * response must replay the recorded judgement — {@code COMPLETED}, {@code FAILED} or
 * honestly {@code UNKNOWN} — and never dispatch twice; a reused key for a different request
 * is the distinct {@code 409} ({@code INV-IDEM-03}). <strong>No cancel and no reversal
 * route exists</strong>, deliberately: the rail declares final-on-acceptance with no
 * reversal capability, and the domain refuses what the declaration forbids
 * ({@code INV-REV-03}).
 */
@RestController
@RequestMapping(path = "/me/withdrawals", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class WithdrawalController {

    @NonNull private final WithdrawalService withdrawals;

    /**
     * Named {@code createWithdrawal} rather than {@code withdraw}: springdoc derives the
     * published operationId from the Java method name, and the consent surface already
     * publishes {@code withdraw} — a second one would renumber ITS identity to
     * {@code withdraw_1} (the `P2-TSK-006` {@code view_1} precedent, caught by the
     * contract guard).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalService.WithdrawalView createWithdrawal(
            @Valid @RequestBody WithdrawalRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return withdrawals.withdraw(current(request), body, idempotencyKey);
    }

    /** One of the caller's withdrawals — unknown, not-yours and malformed are one 404. */
    @GetMapping("/{id}")
    public WithdrawalService.WithdrawalView readWithdrawal(
            @PathVariable("id") String id, HttpServletRequest request) {
        return withdrawals
                .read(current(request), id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        PlatformErrorCode.NOT_FOUND,
                                        "No withdrawal of the caller's matches the requested"
                                                + " identifier",
                                        "no such withdrawal"));
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/withdrawals is reachable"
                        + " without SessionAuthenticationInterceptor having run");
    }
}
