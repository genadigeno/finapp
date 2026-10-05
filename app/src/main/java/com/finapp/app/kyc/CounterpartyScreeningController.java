package com.finapp.app.kyc;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The counterparty reviewer's door (`P9-TSK-016`, ADR-0081 point 4): a person holding
 * {@code COUNTERPARTY_SCREENING_REVIEW} (granted to {@code KYC_REVIEWER}) releases or blocks a screening
 * in review, with a reason code and a narrative - keyed per principal. There is no customer surface:
 * nothing here tells a customer a counterparty was screened (tipping-off).
 */
@RestController
@RequestMapping(path = "/operator/kyc/counterparty-screenings", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CounterpartyScreeningController {

    @NonNull private final CounterpartyScreeningDesk desk;

    /** Releases or blocks a screening in review. */
    @PostMapping(path = "/{id}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.COUNTERPARTY_SCREENING_REVIEW)
    @RequiresIdempotencyKey
    public CounterpartyScreeningDesk.ScreeningDecisionReceipt decideCounterpartyScreening(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody ScreeningDecisionRequest body) {
        return desk.decide(idempotencyKey, id, body);
    }

    /** {@code RELEASE} or {@code BLOCK}, a reason code that justifies it, and the narrative. */
    @ClosedBody
    public record ScreeningDecisionRequest(
            @NotBlank String decision,
            @NotBlank String reasonCode,
            @NotBlank @Size(min = 1, max = 1000) String narrative) {

        @Override
        public String toString() {
            // The narrative is a reviewer's prose about a counterparty: it never reaches a log.
            return "ScreeningDecisionRequest[" + decision + ", " + reasonCode + ", narrative="
                    + (narrative == null ? 0 : narrative.length()) + " chars]";
        }
    }
}
