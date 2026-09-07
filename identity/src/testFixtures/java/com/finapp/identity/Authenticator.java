package com.finapp.identity;

import com.finapp.sharedkernel.security.Sensitive;
import java.time.Clock;
import java.time.Instant;

/**
 * What a customer's authenticator app does (`P1-TSK-017`).
 *
 * <h2>A fixture, and that placement is the modelling</h2>
 *
 * <p>The server <strong>verifies</strong> codes; <strong>generating</strong> them is the device's
 * job. Making this production API would put the ability to mint valid codes into the platform, which
 * is the one capability an attacker who reached the server would most like to find.
 *
 * <p>It lives in `identity`'s test fixtures so it can reach `TotpVerifier`'s package-private
 * arithmetic without that arithmetic becoming public - the same reason `platform` publishes its
 * database harness as a fixture rather than as production code (`P0-TSK-035`).
 */
public final class Authenticator {

    private Authenticator() {}

    /** The code this secret produces right now, as a phone would show it. */
    public static String codeNow(Sensitive<String> secret, TotpParameters parameters, Clock clock) {
        return codeAt(secret, parameters, Instant.now(clock));
    }

    /** The code at a given instant, for testing the window and expiry. */
    public static String codeAt(Sensitive<String> secret, TotpParameters parameters, Instant at) {
        return TotpVerifier.generate(
                TotpVerifier.Base32.decode(secret.expose()),
                at.getEpochSecond() / parameters.periodSeconds(),
                parameters);
    }
}
