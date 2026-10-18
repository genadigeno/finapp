package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The checkout session's BNPL options (`P12-TSK-011`; PHASE_12_PLAN.md section 9 - the route its section 17 table left
 * unassigned): the products the session's merchant offers for its gross, each plan illustrated - <strong>no
 * eligibility</strong>, nothing written, credit not asked.
 *
 * <p><strong>A {@code POST} to {@code /v1/checkout/sessions/bnpl-options} with the session's TOKEN in the body</strong>,
 * not the plan's {@code GET /v1/checkout/sessions/{id}/bnpl-options} - and the refinement is the platform's own rules
 * meeting: the read needs the session HOLDER (the plan's section 11), the holder's capability is the token (an id is not a
 * capability - the confirmation door's {@code P6-TSK-007} reasoning, and the session id is never shown to the customer),
 * and a token may travel only in a request body ({@code CredentialReachesNoEmittedSinkTest}), which a {@code GET} has not.
 * The confirmation door's shape, {@code /checkout/sessions/confirmation}: a customer session AND the token. Unkeyed - a
 * read, writing nothing to replay.
 */
@RestController
@RequestMapping(path = "/checkout/sessions/bnpl-options", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class BnplOptionsController {

    @NonNull private final BnplOfferDesk desk;

    /** The BNPL options of the session the token opens. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public BnplOfferDesk.BnplOptionsView readBnplOptions(@Valid @RequestBody BnplOptionsBody body) {
        return desk.options(body);
    }

    /**
     * The session whose options are asked: its token, wrapped from deserialisation - never logged, stored or echoed.
     * Closed - an unknown field is {@code 422}.
     *
     * @param sessionToken the checkout session's token - the capability the customer holds
     */
    @ClosedBody
    public record BnplOptionsBody(@NotNull Sensitive<String> sessionToken) {}
}
