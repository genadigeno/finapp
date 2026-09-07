package com.finapp.app.recovery;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /v1/recoveries/{id}/completion}.
 *
 * <h2>Both fields are wrapped, and the build rule decided that rather than taste</h2>
 *
 * <p>The password for {@code AuthenticationRequest} reason: a record generated {@code toString}
 * prints every component, so one careless interpolation prints a customer password with no getter
 * call and nothing a reviewer stops at.
 *
 * <p>The <strong>token</strong> for a sharper one. Whoever holds it can replace the credential
 * without knowing the old one, so it is a bearer credential with more power than the password it
 * replaces - a token in a log is an account takeover waiting for somebody to read the archive.
 *
 * <h2>No bound on either</h2>
 *
 * <p>A token that matches nothing is refused by the lookup finding nothing, which is the same answer
 * a spent or expired one gets. Rejecting a malformed one <em>differently</em> would tell a caller
 * their guess was at least the right shape - the argument {@code SingleUseToken.of} makes for
 * validating nothing.
 *
 * @param token proof of control of the registered channel
 * @param password the replacement. Never logged, never echoed
 */
public record RecoveryCompletionRequest(
        @NotNull Sensitive<String> token, @NotNull Sensitive<String> password) {}
