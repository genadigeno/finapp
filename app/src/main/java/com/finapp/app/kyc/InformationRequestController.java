package com.finapp.app.kyc;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.identity.Session;
import com.finapp.kyc.KycCaseId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A reviewer asks a case's customer for more information (`P17-TSK-126`, ADR-0155 decision 3; finapp-admin-panel B6.6 item 3,
 * gap G-63): the case moves {@code IN_REVIEW → AWAITING_CUSTOMER} until the customer answers or the request's deadline passes.
 *
 * <p>The decision's controls (`P17-TSK-029`): {@code KYC_REVIEW}, a {@code MULTI_FACTOR} session and an {@code Idempotency-Key}
 * scoped to the reviewer - a retry replays the first {@code 204}, the key under another request is {@code 409 api.Conflict}.
 * <strong>Every refusal is recorded</strong> ({@link RecordsRefusals}: the error code and the door, never the body), so a text
 * the tipping-off rule refused leaves a trail of who tried.
 */
@RestController
@RequestMapping(path = "/kyc/cases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class InformationRequestController {

    @NonNull private final InformationRequestDesk desk;
    @NonNull private final IdempotentExecutor idempotentExecutor;

    /**
     * Asks the case's customer for more information: {@code 204}; {@code 404} for no such case; {@code 409} for a case not in
     * review, already awaiting its customer, or a KYB case; {@code 422 kyc.RequestTextRefused} for a text the customer may not
     * be sent.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping("/{id}/information-request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(PermissionName.KYC_REVIEW)
    @RequiresIdempotencyKey
    @RecordsRefusals
    public void requestCustomerInformation(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody RequestForInformation body,
            HttpServletRequest request) {
        KycCaseId caseId = caseId(id);
        switch (desk.request(caseId, body.reasonCode(), body.requestText(), reviewer(request),
                KeyedReviewAct.informationRequest(idempotentExecutor, idempotencyKey, SecurityContext.require(), caseId,
                        body.reasonCode(), body.requestText()))) {
            case REQUESTED -> {
                // The only path that returns.
            }
            case NOT_FOUND -> throw new ApiException(PlatformErrorCode.NOT_FOUND, "No KYC case matched the given identifier");
            case NOT_IN_REVIEW -> throw new ApiException(PlatformErrorCode.CONFLICT,
                    "An information request on a case not in review", "this case is not in review");
            case ALREADY_AWAITING -> throw new ApiException(PlatformErrorCode.CONFLICT,
                    "An information request on a case already awaiting its customer",
                    "this case already awaits the customer's answer to an open request");
            case NOT_OFFERED -> throw new ApiException(PlatformErrorCode.CONFLICT,
                    "An information request on a KYB case, which has no answer door yet (ADR-0155 decision 9)",
                    "information requests are offered on a person's KYC case");
        }
    }

    private static KycCaseId caseId(String id) {
        try {
            return KycCaseId.of(UUID.fromString(id));
        } catch (IllegalArgumentException malformed) {
            // Malformed and absent are one answer, as the case file answers them.
            throw new ApiException(PlatformErrorCode.NOT_FOUND, "A malformed case identifier was presented");
        }
    }

    private static IdentityId reviewer(HttpServletRequest request) {
        if (request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION) instanceof Session authenticated) {
            return authenticated.identityId();
        }
        // Unreachable while the interceptor runs: a request with no proven reviewer would be attributed to nobody, forever.
        throw new IllegalStateException(
                "No authenticated session on the request: a reviewer handler is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
