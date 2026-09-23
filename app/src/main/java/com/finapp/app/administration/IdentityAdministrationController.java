package com.finapp.app.administration;

import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two administrative endpoints (`P1-TSK-028`).
 *
 * <h2>These are the only two endpoints in the phase that carry {@code @RequiresPermission}</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §7 lists both and <strong>no task owned either</strong> — the sixth
 * backlog defect of that class in Phase 1, found by {@code P1-TSK-020}'s completion gate. Until
 * they existed, the annotation had no production caller, {@code identity.IdentitySuspended} was
 * declared unemitted, and {@code Identity.suspend} was a method with confident javadoc and nothing
 * calling it.
 *
 * <p>They were deliberately not built inside {@code P1-TSK-020}: an admin endpoint invented to give
 * an annotation something to point at is a <strong>security surface chosen to suit a test</strong>,
 * which is {@code P1-TSK-018}'s recorded reasoning for shipping {@code @RequiresAssurance} with a
 * probe endpoint instead.
 *
 * <h2>Two checks, and the second is not the usual one</h2>
 *
 * <p>{@link RequiresPermission} answers <em>may an actor of this kind do this at all?</em> The
 * ownership half lives in {@link IdentityAdministration} and is <strong>inverted</strong>: the
 * subject must <em>not</em> be the actor. A boundary annotation cannot express it, because it is
 * static per handler and knows nothing about which identity the path names (ADR-0031).
 *
 * <h2>The subject identifier comes from the path, and that is correct here</h2>
 *
 * <p>Everywhere else in this codebase an identifier out of the request is the defect ADR-0031
 * names. Here it is the operation: an administrator acts on somebody they name. What replaces the
 * ownership predicate is the permission plus the not-self rule, and
 * {@code OwnershipIsScopedTest} classifies the store methods {@code ADMINISTERED} rather than
 * squeezing them into a class that would read as a proof.
 *
 * <h2>Existence is disclosed, deliberately</h2>
 *
 * <p>A {@code 404} for an identity that does not exist tells the caller it does not exist — which
 * would be an oracle on an unauthenticated surface and is correct here, because the caller is a
 * <strong>proven administrator</strong>. {@code INV-IDN-07} governs what a stranger can learn;
 * refusing to tell an administrator whether an account exists would make the endpoint unusable and
 * protect nobody.
 */
@RestController
@RequestMapping(path = "/identities", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class IdentityAdministrationController {

    @NonNull private final IdentityAdministrationService administration;

    /**
     * Suspends an identity and ends every session it holds.
     *
     * <p>{@code POST} rather than {@code DELETE}, and a subresource rather than a status field: the
     * request carries a reason, and a suspension is a thing that happened rather than a value that
     * changed. It is also where reinstatement attaches — {@link #reinstateIdentity} deletes this
     * subresource (`P1-TSK-032`).
     */
    @PostMapping("/{id}/suspension")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(PermissionName.IDENTITY_SUSPEND)
    public void suspendIdentity(
            @PathVariable String id,
            @Valid @RequestBody SuspensionRequest body,
            HttpServletRequest request) {

        IdentityAdministration.Suspension outcome =
                administration.suspend(subject(id), actor(request), body.reason());

        switch (outcome) {
            case SUSPENDED -> {
                // The only path that returns.
            }
            case SELF ->
                    throw new ApiException(
                            PlatformErrorCode.VALIDATION_FAILED,
                            "An administrator named themselves as the subject of a suspension",
                            "an administrator cannot suspend their own identity");
            case NOT_FOUND ->
                    throw new ApiException(
                            PlatformErrorCode.NOT_FOUND, "No identity matched the given identifier");
            case NOT_ACTIVE ->
                    // 409, not 204: reporting success would tell an administrator they had just
                    // ended somebody's sessions when another administrator had already done it,
                    // and the difference matters when two people are working an incident.
                    throw new ApiException(
                            PlatformErrorCode.CONFLICT,
                            "The identity was not ACTIVE when the suspension was attempted",
                            "this identity is not active");
        }
    }

    /**
     * Lifts a suspension (`P1-TSK-032`).
     *
     * <p>{@code DELETE} on the suspension subresource {@link #suspendIdentity} creates — the
     * suspension is what is removed, never the identity. The reason travels in the request body
     * rather than a query parameter, because it is free prose that may name a person or an
     * incident and a URL reaches access logs ({@code INV-AUD-02}); see {@link ReinstatementRequest}.
     *
     * <p>The permission is {@code IDENTITY_SUSPEND}, deliberately not a new one — the
     * administrator trusted to impose a suspension is the administrator trusted to lift one, which
     * is {@code ROLE_ASSIGN}'s own "grant or revoke" shape. See {@code PermissionName}.
     *
     * <p><strong>Sessions are not restored.</strong> The suspension revoked them and revocations
     * do not un-happen ({@code INV-HIST-01}); the person logs in again, which re-proves the
     * credential rather than resurrecting a bearer token in whoever's hands last held it.
     */
    @DeleteMapping("/{id}/suspension")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(PermissionName.IDENTITY_SUSPEND)
    public void reinstateIdentity(
            @PathVariable String id,
            @Valid @RequestBody ReinstatementRequest body,
            HttpServletRequest request) {

        IdentityAdministration.Reinstatement outcome =
                administration.reinstate(subject(id), actor(request), body.reason());

        switch (outcome) {
            case REINSTATED -> {
                // The only path that returns.
            }
            case SELF ->
                    // Nearly unreachable - a suspended identity holds no live session - and kept
                    // for the trail property: no administrative record ever names one party twice.
                    throw new ApiException(
                            PlatformErrorCode.VALIDATION_FAILED,
                            "An administrator named themselves as the subject of a reinstatement",
                            "an administrator cannot reinstate their own identity");
            case NOT_FOUND ->
                    throw new ApiException(
                            PlatformErrorCode.NOT_FOUND, "No identity matched the given identifier");
            case NOT_SUSPENDED ->
                    // 409, not 204: ACTIVE and CLOSED both land here, and only the first could
                    // honestly be called "already done" - a CLOSED identity is gone permanently
                    // and reporting success would claim it can log in again.
                    throw new ApiException(
                            PlatformErrorCode.CONFLICT,
                            "The identity was not SUSPENDED when the reinstatement was attempted",
                            "this identity is not suspended");
        }
    }

    /** Grants a role. */
    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(PermissionName.ROLE_ASSIGN)
    public void assignRole(
            @PathVariable String id,
            @Valid @RequestBody RoleAssignmentRequest body,
            HttpServletRequest request) {

        IdentityAdministration.RoleGrant outcome =
                administration.assignRole(
                        subject(id), body.role(), actor(request), body.reason());

        switch (outcome) {
            case GRANTED, ALREADY_HELD -> {
                // Idempotent in effect, and reported as success in both cases: the caller asked for
                // the identity to hold the role and it does. A 409 here would make a retried
                // request after a lost response look like a failure.
            }
            case SELF ->
                    throw new ApiException(
                            PlatformErrorCode.VALIDATION_FAILED,
                            "An administrator named themselves as the subject of a role assignment",
                            "an administrator cannot assign a role to their own identity");
            case NOT_FOUND ->
                    throw new ApiException(
                            PlatformErrorCode.NOT_FOUND, "No identity matched the given identifier");
        }
    }

    // -----------------------------------------------------------------

    private static IdentityId subject(String id) {
        try {
            return IdentityId.of(java.util.UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            // The same answer as an identity that does not exist. A distinct code would say
            // nothing useful and would give a caller a way to tell a malformed identifier from an
            // absent one, which is a difference nobody needs.
            throw new ApiException(
                    PlatformErrorCode.NOT_FOUND, "A malformed identity identifier was presented");
        }
    }

    private static IdentityId actor(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated.identityId();
        }
        // Unreachable while the interceptor is registered and @RequiresPermission implies
        // @RequiresSession. A refusal rather than an assumption, because proceeding without a
        // proven actor would attribute a privileged action to nobody - permanently (INV-HIST-03).
        throw new IllegalStateException(
                "No authenticated session on the request: an administrative handler is reachable"
                        + " without SessionAuthenticationInterceptor having run");
    }
}
