package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.TotpParameters;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A database suite's operator stepping up over the real endpoints (`P15-TSK-039`): enrol a TOTP factor on a password session,
 * confirm it, prove it, and take the elevated session. The doors the access review ruled need {@code MULTI_FACTOR}, so the
 * suites that drive them need this shape of session; their own step-up negatives keep the password one.
 */
public final class OperatorStepUp {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);

    private OperatorStepUp() {}

    /** The suite's own {@code post}: path, body, bearer, idempotency key. */
    @FunctionalInterface
    public interface Post {
        HttpResponse<String> send(String path, String body, String bearer, String key) throws Exception;
    }

    /** Enrols and confirms a factor on {@code passwordToken}, proves it, and returns the {@code MULTI_FACTOR} session's token. */
    public static String elevated(String passwordToken, Post post) throws Exception {
        Sensitive<String> secret = Sensitive.of(secretFrom(post.send("/v1/me/mfa", null, passwordToken, null).body()));
        long step = Instant.now(CLOCK).getEpochSecond() / TotpParameters.current().periodSeconds();
        // The PREVIOUS step's code: confirmation consumes its step, and the challenge that follows uses the current one.
        String confirming = Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond((step - 1) * TotpParameters.current().periodSeconds()));
        assertThat(post.send("/v1/me/mfa/confirmation", "{\"code\":\"" + confirming + "\"}", passwordToken, null).statusCode())
                .isEqualTo(204);
        HttpResponse<String> elevated = post.send("/v1/authentications/mfa",
                "{\"code\":\"" + Authenticator.codeNow(secret, TotpParameters.current(), CLOCK) + "\"}", passwordToken, null);
        assertThat(elevated.statusCode()).as("the step-up: %s", elevated.body()).isEqualTo(200);
        return group(elevated.body(), "\"sessionToken\"\\s*:\\s*\"([^\"]+)\"");
    }

    private static String secretFrom(String body) {
        return group(body, "secret=([A-Z2-7]+)");
    }

    private static String group(String body, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(body);
        assertThat(matcher.find()).as("expected %s in the response", pattern).isTrue();
        return matcher.group(1);
    }
}
