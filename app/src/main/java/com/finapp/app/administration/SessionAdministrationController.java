package com.finapp.app.administration;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionAdministration;
import com.finapp.identity.SessionId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Another identity's sessions (`P17-TSK-015`, gap G-12): listed with no token material, one ended, or all ended - without
 * suspending the person. Under {@code SESSION_ADMINISTER}, every route on a {@code MULTI_FACTOR} session; the acts carry a reason
 * and record their refusals. The administrator's own identity is refused as the role doors refuse it ({@code 422}); an identity
 * that does not exist is {@code 404}; a session already ended, or not that identity's, is answered as done ({@code 204}), so the
 * door is no oracle over session identifiers.
 */
@RestController
@RequestMapping(path = "/operator/identities/{id}/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SessionAdministrationController {

    @NonNull private final SessionAdministrationService administration;

    /** The identity's live sessions, newest first: identifier, issue, the bound it ends at unless used, assurance. Recorded. */
    @GetMapping
    @RequiresPermission(PermissionName.SESSION_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public LiveSessionsView listIdentitySessions(@PathVariable("id") String id) {
        return administration.liveSessionsOf(subject(id))
                .map(live -> new LiveSessionsView(live.stream().map(LiveSessionView::of).toList()))
                .orElseThrow(SessionAdministrationController::notFound);
    }

    /** Ends one of the identity's sessions, with a reason; {@code 204} whether it was live or had already ended. */
    @DeleteMapping("/{session}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(PermissionName.SESSION_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RecordsRefusals
    public void revokeIdentitySession(
            @PathVariable("id") String id,
            @PathVariable("session") String session,
            @Valid @RequestBody SessionRevocationRequest body,
            HttpServletRequest request) {
        IdentityId subject = subject(id);
        IdentityId actor = actor(request);
        // A value no session can carry is an empty session: nothing ends, after the same refusals, answered as an ended one.
        answer(administration.revoke(subject, session(session), actor, body.reason()));
    }

    /** Ends every live session of the identity, without suspending it, with a reason; answers how many ended. */
    @DeleteMapping
    @RequiresPermission(PermissionName.SESSION_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RecordsRefusals
    public SessionsRevokedView revokeIdentitySessions(
            @PathVariable("id") String id, @Valid @RequestBody SessionRevocationRequest body, HttpServletRequest request) {
        SessionAdministration.AllRevoked outcome = administration.revokeAll(subject(id), actor(request), body.reason());
        answer(outcome.outcome());
        return new SessionsRevokedView(outcome.revoked());
    }

    private static void answer(SessionAdministration.Outcome outcome) {
        switch (outcome) {
            case DONE -> {
                // The only path that returns.
            }
            case SELF -> throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "An administrator named themselves as the subject of a session revocation",
                    "an administrator ends their own sessions through their own doors, not this one");
            case NOT_FOUND -> throw notFound();
        }
    }

    private static IdentityId subject(String id) {
        try {
            return IdentityId.of(UUID.fromString(id));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(PlatformErrorCode.NOT_FOUND, "A malformed identity identifier was presented");
        }
    }

    private static Optional<SessionId> session(String sessionId) {
        try {
            return Optional.of(SessionId.of(UUID.fromString(sessionId)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No identity matched the given identifier");
    }

    private static IdentityId actor(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated.identityId();
        }
        throw new IllegalStateException(
                "No authenticated session on the request: an administrative handler is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }

    /** The reason an administrator gives for ending somebody's session: the suspension's bounds and charset. */
    public record SessionRevocationRequest(
            @NotBlank
                    @Size(min = SuspensionRequest.REASON_MIN, max = SuspensionRequest.REASON_MAX)
                    @Pattern(regexp = SuspensionRequest.REASON_CHARSET)
                    String reason) {}

    /** The identity's live sessions. */
    public record LiveSessionsView(List<LiveSessionView> sessions) {}

    /**
     * One live session: no token and no hash, no device detail. {@code expiresAt} is the earlier of its two bounds - when it ends
     * unless it is used - and {@code absoluteExpiresAt} the one no use extends.
     */
    public record LiveSessionView(String id, Instant issuedAt, Instant expiresAt, Instant absoluteExpiresAt, String assurance) {
        static LiveSessionView of(Session session) {
            Instant idle = session.idleExpiresAt();
            Instant absolute = session.absoluteExpiresAt();
            return new LiveSessionView(session.id().value().toString(), session.issuedAt(),
                    idle.isBefore(absolute) ? idle : absolute, absolute, session.assurance().name());
        }
    }

    /** How many sessions an "end them all" ended. */
    public record SessionsRevokedView(int sessionsRevoked) {}
}
