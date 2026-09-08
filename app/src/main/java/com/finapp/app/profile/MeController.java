package com.finapp.app.profile;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.party.PartyName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's own profile (`P1-TSK-030`).
 *
 * <h2>Why this endpoint existed on paper for the whole phase</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §7 declared {@code GET /v1/me} and {@code PATCH /v1/me} and
 * <strong>no backlog task owned either</strong> — the eighth backlog defect of that class in Phase
 * 1, and the first found by a <em>review</em> rather than by the task that tripped over it. Until
 * now {@code party.ProfileChanged} was catalogued and declared unemitted for exactly that reason.
 *
 * <h2>No identifier, anywhere in the request</h2>
 *
 * <p>{@code /me} takes no path variable and no body field naming a party. That is the strongest form
 * of ADR-0031's ownership rule rather than an absence of one: the defect it names is trusting an
 * identifier out of the request, and there is none here to trust. See {@link ProfileService}.
 */
@RestController
@RequestMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
public class MeController {

    private final ProfileService profiles;

    public MeController(ProfileService profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
    }

    /** The caller's own profile. */
    @GetMapping
    public ProfileResponse readProfile(HttpServletRequest request) {
        return render(
                profiles.read(current(request)).orElseThrow(MeController::profileNotResolvable));
    }

    /**
     * Changes the caller's own display name.
     *
     * <h2>It returns the profile rather than {@code 204}, and the first reason given for that was
     * wrong</h2>
     *
     * <p>The original javadoc said the value <em>"is normalised by PartyName on the way in, so it is
     * not necessarily what was sent"</em>. <strong>{@code PartyName} normalises nothing</strong> —
     * its own documentation refuses to, in as many words: <em>"Sanitising input at construction to
     * defend an output is how a value gets silently corrupted for every consumer to protect one."</em>
     * It validates and stores verbatim. The completion gate found it, and it is the eighth javadoc
     * in this phase to assert something the code does not do.
     *
     * <p>The decision stands on a different and true argument: <strong>a {@code PATCH} that returned
     * nothing would make a client guess</strong>. It cannot assume the stored value equals what it
     * sent — a concurrent change, a future normalisation, or a field this endpoint does not accept
     * could all make that false — so returning the profile is returning the <em>state</em> rather
     * than an echo, and it saves the round trip a client would otherwise always make.
     */
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProfileResponse updateProfile(
            @Valid @RequestBody ProfileUpdateRequest body, HttpServletRequest request) {
        return render(
                profiles.rename(current(request), new PartyName(body.displayName()))
                        .orElseThrow(MeController::profileNotResolvable));
    }

    // -----------------------------------------------------------------

    private static ProfileResponse render(ProfileService.Profile profile) {
        return new ProfileResponse(
                profile.party().id().value().toString(),
                profile.party().kind().name(),
                profile.party().name().value(),
                profile.loginIdentifier(),
                profile.party().registeredAt());
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        // Unreachable while the interceptor is registered and the class carries @RequiresSession. A
        // refusal rather than an assumption, because proceeding without a proven owner is the one
        // failure an endpoint called /me must never have.
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }

    /**
     * The session resolves to no Party.
     *
     * <p>Structurally possible and never produced: ADR-0029 declines the cross-schema foreign key,
     * so the database would accept an identity whose party does not exist, and what prevents it is
     * that registration writes both in one commit. A {@code 404} rather than a {@code 500} because
     * it is a data defect rather than a platform failure — but it is <strong>logged as our
     * problem</strong>, since a customer cannot fix it and nobody would look for it otherwise.
     */
    private static ApiException profileNotResolvable() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A proven session resolved to no Party; registration should make this impossible",
                "no profile exists for this session");
    }

    /**
     * What {@code /v1/me} publishes.
     *
     * <p><strong>The login identifier is {@code CONFIDENTIAL} and is returned anyway.</strong> Its
     * classification is about existence disclosure to <em>strangers</em>; the caller is the owner,
     * who already knows it, and a profile without <em>"what I log in as"</em> is missing the field
     * people actually check. This is not {@code P1-TSK-006}'s case, where identifiers were withheld
     * because that endpoint is unauthenticated and replayable by anyone holding an idempotency key.
     *
     * <p><strong>Customer status is deliberately absent.</strong> Registration opens a Customer at
     * {@code PENDING} and nothing in Phase 1 moves it — Phase 2's KYC decision does. A field that is
     * permanently {@code PENDING} is a contract commitment with no content, and it reads to a client
     * like a defect in our system rather than an absence in it.
     *
     * <p><strong>Identity status is absent for a smaller reason</strong>: a suspended identity
     * cannot hold a live session (`P1-TSK-028`), so this endpoint is unreachable while it is
     * anything but {@code ACTIVE}. A field that can only hold one value is not information.
     */
    public record ProfileResponse(
            String partyId,
            String kind,
            String displayName,
            String loginIdentifier,
            Instant registeredAt) {}
}
