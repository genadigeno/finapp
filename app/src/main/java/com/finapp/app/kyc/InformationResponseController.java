package com.finapp.app.kyc;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.kyc.KycErrorCode;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer answers a reviewer's request for information (`P17-TSK-126`, ADR-0155 decision 6): the case returns to review.
 *
 * <p>The {@code /v1/me} shape: no identifier anywhere - the session resolves the customer, the customer their open case, the
 * case its one open request - so nothing here can point at somebody else's case. Keyed per customer: a retry after a lost
 * answer replays the {@code 204}. {@code 409 kyc.NoOpenInformationRequest} when there is nothing to answer - none asked, already
 * answered, or past its deadline on the database clock; {@code 409 kyc.NoOpenCase} without an open case.
 */
@RestController
@RequestMapping(path = "/me/kyc", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class InformationResponseController {

    @NonNull private final InformationRequestDesk desk;
    @NonNull private final IdempotentExecutor idempotentExecutor;

    /** Answers the open request on the caller's case. */
    @PostMapping(path = "/information-response", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresIdempotencyKey
    public void answerInformationRequest(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody InformationResponse body,
            HttpServletRequest request) {
        switch (desk.answer(current(request), body.answer(),
                KeyedReviewAct.informationResponse(idempotentExecutor, idempotencyKey, SecurityContext.require(),
                        body.answer()))) {
            case ANSWERED -> {
                // The only path that returns.
            }
            case NOTHING_TO_ANSWER -> throw new ApiException(KycErrorCode.NO_OPEN_INFORMATION_REQUEST,
                    "An answer with no open, unlapsed request on the caller's case");
            case NO_OPEN_CASE -> throw new ApiException(KycErrorCode.NO_OPEN_CASE,
                    "An answer from a caller with no open verification case");
        }
    }

    private static Session current(HttpServletRequest request) {
        if (request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION) instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException("No authenticated session on the request: /v1/me/kyc/information-response is reachable"
                + " without SessionAuthenticationInterceptor having run");
    }
}
