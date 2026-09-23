package com.finapp.app.consent;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.consent.ConsentErrorCode;
import com.finapp.consent.ConsentPurpose;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's own consent posture (`P2-TSK-018`): {@code POST /v1/me/consents},
 * {@code DELETE /v1/me/consents/{purpose}}, {@code GET /v1/me/consents}.
 *
 * <h2>The {@code /v1/me} shape, with one path variable — and why that is not an identifier</h2>
 *
 * <p>Every other {@code /v1/me} surface takes no path variable at all, because ADR-0031's defect
 * is trusting an identifier out of the request. {@code {purpose}} is not one: it is a closed
 * enum naming a <em>category of processing shared by everyone</em> — it cannot name a resource,
 * a person, or anything of anybody else's, so there is still nothing here an attacker can point
 * at a victim. The row the withdrawal lands on is derived entirely from the session.
 *
 * <h2>{@code INV-IDN-04}, at the surface that exists because of it</h2>
 *
 * <p>{@code @RequiresSession} and nothing more: granting or withdrawing one's own consent is
 * not a privileged capability, so no permission applies — and the session is never itself a
 * lawful basis, which is what the records these endpoints write exist to provide.
 */
@RestController
@RequestMapping(path = "/me/consents", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class ConsentController {

    @NonNull private final ConsentService consents;

    /**
     * Grants for a purpose, against the text version the person was shown.
     *
     * <p>A {@code 201} <strong>every</strong> time, including for a repeated grant: each accepted
     * grant creates a new immutable fact (ADR-0037 — duplicates are honest history, and the
     * derivation absorbs them), so there is no converged-replay case for a different status to
     * describe. The response is the purpose's basis row rather than nothing, because a client
     * must not guess what its grant produced (the {@code PATCH /v1/me} argument).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PurposeBasisResponse grant(
            @Valid @RequestBody ConsentGrantRequest body, HttpServletRequest request) {
        return switch (consents.grant(current(request), body.purpose(), body.textVersion())) {
            case ConsentService.GrantResult.Granted granted -> render(granted.basis());
            case ConsentService.GrantResult.UnknownVersion ignored ->
                    throw new ApiException(
                            ConsentErrorCode.UNKNOWN_TEXT_VERSION,
                            "A consent grant named a text version never published for its"
                                    + " purpose");
            case ConsentService.GrantResult.ReconsentRequired ignored ->
                    throw new ApiException(
                            ConsentErrorCode.RECONSENT_REQUIRED,
                            "A consent grant named a text version superseded by one requiring"
                                    + " re-consent");
            case ConsentService.GrantResult.NotResolvable ignored ->
                    throw notResolvable();
        };
    }

    /**
     * Withdraws for a purpose — a new record, never a deletion.
     *
     * <p>A {@code 204} with nothing to say, which is itself part of {@code INV-CNS-01}: the
     * response discloses nothing a subsequent {@code GET} would not, and that {@code GET}
     * answers a withdrawal and an absence identically. A withdrawal with no grant before it
     * succeeds too — it is honest history, and refusing it would make withdrawal refusable by
     * something other than authentication.
     */
    @DeleteMapping(path = "/{purpose}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable ConsentPurpose purpose, HttpServletRequest request) {
        if (!consents.withdraw(current(request), purpose)) {
            throw notResolvable();
        }
    }

    /** The caller's current basis per purpose, with the current text a grant would be against. */
    @GetMapping
    public List<PurposeBasisResponse> currentBases(HttpServletRequest request) {
        return consents.currentBases(current(request)).orElseThrow(ConsentController::notResolvable)
                .stream()
                .map(ConsentController::render)
                .toList();
    }

    // -----------------------------------------------------------------

    private static PurposeBasisResponse render(ConsentService.PurposeBasis basis) {
        return new PurposeBasisResponse(
                basis.purpose().name(),
                basis.granted(),
                basis.currentTextVersion(),
                basis.currentText());
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        // Unreachable while the interceptor is registered and the class carries @RequiresSession.
        // A refusal rather than an assumption, because proceeding without a proven owner is the
        // one failure an endpoint under /me must never have.
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/consents is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }

    /**
     * The session resolves to no Identity — the {@code MeController} not-resolvable case: a data
     * defect registration makes impossible, logged as ours because a customer cannot fix it.
     */
    private static ApiException notResolvable() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A proven session resolved to no Identity; registration should make this"
                        + " impossible",
                "no consent posture exists for this session");
    }

    /**
     * One purpose's row in what {@code /v1/me/consents} publishes.
     *
     * <p>{@code granted} is the derivation's answer and deliberately nothing more: no
     * "withdrawn at", no latest action, no history — any of which would distinguish a
     * withdrawal from an absence, which {@code INV-CNS-01} forbids this surface to do. The
     * current text rides along because the words are what a person consents to, and
     * {@code consent_text.body} is {@code PUBLIC} — the one classification made for exactly
     * this publication.
     */
    public record PurposeBasisResponse(
            String purpose, boolean granted, int currentTextVersion, String currentText) {}
}
