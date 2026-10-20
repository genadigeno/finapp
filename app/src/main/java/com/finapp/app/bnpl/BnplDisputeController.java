package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.bnpl.BnplSpans;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's BNPL dispute doors (`P12-TSK-023`; ADR-0106 decisions 1 and 2; {@code PHASE_12_PLAN.md} section 9): open
 * a dispute of one's own agreement's purchase - the amount held from collection, days past due and late fees until the
 * merchant accepts, its deadline passes or an operator resolves - and withdraw it. Another party's agreement or dispute is
 * the uniform {@code 404}. The platform's conditional step-up.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class BnplDisputeController {

    @NonNull private final BnplDisputeDesk desk;
    @NonNull private final BnplFlowScope flow;

    /**
     * Opens a dispute of the caller's own agreement's purchase: an amount up to what remains of it, a category and a reason
     * (RESTRICTED-PII). Keyed ({@code bnpl.dispute:CUSTOMER:<id>}); {@code 201} with the dispute; {@code 409
     * bnpl.DisputeOpen} for a second open one.
     */
    @PostMapping(path = "/me/bnpl-agreements/{id}/disputes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplDisputeDesk.BnplDisputeView openBnplDispute(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplDisputeOpeningBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.doorOnAgreement(BnplSpans.DISPUTE_OPEN, id, () -> desk.open(BnplOfferController.current(request), id, idempotencyKey, body));
    }

    /** Withdraws the caller's own open dispute - the hold lifted, nothing posted. Keyed. */
    @PostMapping(path = "/me/bnpl-disputes/{id}/withdrawal")
    @RequiresIdempotencyKey
    public BnplDisputeDesk.BnplDisputeView withdrawBnplDispute(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.doorOnDispute(BnplSpans.DISPUTE_WITHDRAW, id, () -> desk.withdraw(BnplOfferController.current(request), id, idempotencyKey));
    }

    /**
     * A dispute: the amount and its currency - an exact decimal at the currency's minor units - the category
     * ({@code NOT_RECEIVED}, {@code NOT_AS_DESCRIBED}, {@code RETURNED_NOT_REFUNDED}, {@code OTHER}) and the reason,
     * required (RESTRICTED-PII, screened for card and account numbers). Closed - an unknown field is {@code 422}.
     */
    @ClosedBody
    public record BnplDisputeOpeningBody(
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 3) String currency,
            @NotBlank @Size(max = 32) String category,
            @NotBlank @Size(max = 1000) String reason) {}
}
