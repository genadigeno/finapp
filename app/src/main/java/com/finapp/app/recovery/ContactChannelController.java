package com.finapp.app.recovery;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.app.session.Unauthenticated;
import com.finapp.identity.EmailAddress;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registering a contact channel and proving control of it (`P1-TSK-023`).
 *
 * <h2>Adding needs a session; verifying does not, and the asymmetry is the design</h2>
 *
 * <p>If registering a channel were unauthenticated, an attacker could point recovery at their own
 * mailbox without holding anything at all — so the first move in a takeover still costs a stolen
 * password.
 *
 * <p>Verification is unauthenticated because the token arrives <strong>in the mailbox</strong>, and
 * whoever reads it may be in a different browser or on a different device. Holding the token
 * <em>is</em> the proof of control, which is exactly what is being verified, so requiring a session
 * on top would add nothing and would make the common path fail.
 */
@RestController
@RequestMapping(path = "/me/channels", produces = MediaType.APPLICATION_JSON_VALUE)
public class ContactChannelController {

    private final RecoveryApplicationService recoveries;

    public ContactChannelController(RecoveryApplicationService recoveries) {
        this.recoveries = Objects.requireNonNull(recoveries, "recoveries must not be null");
    }

    /**
     * Registers a channel against the authenticated identity.
     *
     * <p>202 with no body: the challenge went to the address, and in Phase 1 nothing delivers it.
     * Returning it here would hand it to whoever asked, so proving control would prove nothing.
     */
    @PostMapping
    @RequiresSession
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void addChannel(
            HttpServletRequest request, @Valid @RequestBody ContactChannelRequest body) {
        recoveries.addChannel(current(request).identityId(), address(body));
    }

    /** Proves control. One refusal for every reason it could be refused. */
    @PostMapping("/verification")
    @Unauthenticated
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyChannel(@Valid @RequestBody ContactChannelVerificationRequest body) {
        if (!recoveries.verifyChannel(body.token())) {
            throw new ApiException(
                    PlatformErrorCode.FORBIDDEN, "A channel verification was refused");
        }
    }

    // -----------------------------------------------------------------

    /**
     * A malformed address is a mistake about the caller's own input, so it is a 422 naming the
     * field rather than a 500.
     *
     * <p>The {@code P1-TSK-006} finding, where a NUL byte in a display name reached PostgreSQL three
     * layers down and surfaced as {@code api.InternalError} — our fault reported for their input,
     * which {@code ERROR_CONTRACT.md} §3 forbids and which a client may retry for ever.
     */
    private static EmailAddress address(ContactChannelRequest body) {
        try {
            return EmailAddress.of(body.address());
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED, "That is not a usable email address");
        }
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: the handler is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
