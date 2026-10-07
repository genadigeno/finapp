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
 * The customer's beneficiaries abroad (`P9-TSK-017`, `PHASE_9_PLAN.md` section 9, ADR-0080 section 3):
 * register by the corridor provider's grant, read them with a status shaped for tipping-off, and revoke
 * from any state with one identical answer. Every route is the caller's own; another customer's
 * beneficiary answers the uniform {@code 404}.
 */
@RestController
@RequestMapping(path = "/me/cross-border/beneficiaries", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class CrossBorderBeneficiaryController {

    @NonNull private final CrossBorderBeneficiaryDesk desk;

    /** Registers a beneficiary - keyed; step-up when a factor is enrolled; screened before it is payable. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public CrossBorderBeneficiaryDesk.CrossBorderBeneficiaryView registerBeneficiary(
            @Valid @RequestBody CrossBorderBeneficiaryRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.register(current(request), idempotencyKey, body);
    }

    @GetMapping
    public CrossBorderBeneficiaryDesk.CrossBorderBeneficiaryList listBeneficiaries(HttpServletRequest request) {
        return desk.list(current(request));
    }

    @GetMapping("/{id}")
    public CrossBorderBeneficiaryDesk.CrossBorderBeneficiaryView readBeneficiary(@PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    /** Revokes from every state but {@code REVOKED} - the same answer whatever it was. */
    @PostMapping("/{id}/revocation")
    @RequiresIdempotencyKey
    public CrossBorderBeneficiaryDesk.CrossBorderRevocationReceipt revokeBeneficiary(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.revoke(current(request), id, idempotencyKey);
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/cross-border/beneficiaries is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }

    /**
     * A registration: the destination, the provider's single-use grant, the beneficiary's name (for
     * screening by kyc, stored only there), a nickname, the entity type, and the acknowledgement of a
     * payee check that did not match. The grant and the name never reach a log.
     */
    @ClosedBody
    public record CrossBorderBeneficiaryRequest(
            @NotBlank @Size(min = 1, max = 2) String country,
            @NotBlank @Size(min = 1, max = 3) String currency,
            @NotBlank @Size(max = 128) String grant,
            @NotBlank @Size(max = 140) String name,
            @NotBlank @Size(max = 40) String nickname,
            @NotBlank @Size(min = 1, max = 16) String entityType,
            Boolean acknowledgeNoMatch) {

        @Override
        public String toString() {
            return "CrossBorderBeneficiaryRequest[country=" + country + ", currency=" + currency
                    + ", grant=<redacted>, name=<redacted>, entityType=" + entityType + "]";
        }
    }
}
