package com.finapp.app.recovery;

import com.finapp.app.session.Unauthenticated;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.identity.RecoveryRequestId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account recovery over HTTP (`P1-TSK-023`, {@code INV-IDN-06}).
 *
 * <h2>Unauthenticated by definition</h2>
 *
 * <p>Recovery is for somebody who cannot log in, so requiring a session would make it useless.
 * That is why every control here is on the <em>channel</em>: a previously registered and verified
 * address, a single-use expiring token sent to it, cooling-off, and a binding to the credential the
 * request was raised against.
 *
 * <h2>Neither endpoint says anything</h2>
 *
 * <p>Initiation is <strong>202 with no body, always</strong> — unknown identifier, no verified
 * channel, cooling-off refused, all identical. Completion is 204 or a uniform refusal. A response
 * that varied would turn recovery into the account-existence oracle {@code INV-IDN-07} forbids, and
 * this endpoint is the one an attacker probes first because it needs nothing to call.
 *
 * <h2>The token is never in a response</h2>
 *
 * <p>Returning it would hand it to whoever asked, so proving control of the channel would prove
 * nothing. In Phase 1 it is delivered <strong>nowhere</strong>: {@code PHASE_1_PLAN.md} §8 records
 * that the channel adapter is Phase 15's, and the domain returns the plaintext to its caller so a
 * notifier can attach in the same transaction.
 */
@RestController
@RequestMapping("/recoveries")
@Unauthenticated
@RequiredArgsConstructor
public class RecoveryController {

    @NonNull private final RecoveryApplicationService recoveries;

    /** Begins recovery. Always 202. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void initiateRecovery(@Valid @RequestBody RecoveryInitiationRequest request) {
        recoveries.initiate(new LoginIdentifier(request.loginIdentifier()));
    }

    /**
     * Completes it.
     *
     * <p>A malformed identifier is refused exactly as a wrong token is: telling a caller their
     * identifier was at least well-formed is a free bit for anybody probing.
     */
    @PostMapping("/{id}/completion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeRecovery(
            @PathVariable String id, @Valid @RequestBody RecoveryCompletionRequest request) {
        if (!recoveries.complete(
                requestId(id), request.token(), password(request))) {
            throw new ApiException(
                    PlatformErrorCode.FORBIDDEN, "A recovery completion was refused");
        }
    }

    /**
     * A password below the domain minimum is a 422 naming the field, not a uniform refusal.
     *
     * <p>The opposite of {@code AuthenticationService}, deliberately. There, a short password must be
     * indistinguishable from a wrong one, because telling the caller would give the endpoint a
     * second response shape and a fast path that skips the derivation. Here the caller has ALREADY
     * proven control of the channel, so they are the account holder and telling them their new
     * password is too short discloses nothing to anybody else.
     */
    private static RawPassword password(RecoveryCompletionRequest request) {
        try {
            return new RawPassword(request.password());
        } catch (IllegalArgumentException tooShort) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED, "That password is not usable");
        }
    }

    private static RecoveryRequestId requestId(String id) {
        try {
            return RecoveryRequestId.of(UUID.fromString(id));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(
                    PlatformErrorCode.FORBIDDEN, "A recovery completion was refused");
        }
    }
}
