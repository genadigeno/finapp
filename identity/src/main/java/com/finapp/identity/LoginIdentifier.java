package com.finapp.identity;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What someone types to say which account they are.
 *
 * <p><strong>Deliberately not an email address.</strong> `PHASE_1_PLAN.md` §4 states the rule and
 * this type is where it is enforced: a login identifier that is also a contact channel cannot be
 * changed without changing how someone logs in, and cannot be verified without blocking login.
 * An {@code Identity} therefore carries this, and an email address separately — changeable, and
 * separately verified.
 *
 * <p><strong>Normalised on construction, once.</strong> Lower-cased, because a person who typed
 * their identifier with a capital on Tuesday is the same person, and a uniqueness constraint that
 * treats the two as different accounts would let one person hold two logins that look identical.
 * Normalising here rather than at each call site is what makes the database's unique index mean
 * what it appears to mean.
 *
 * <p><strong>The charset is default-deny</strong>, for the same reason the correlation identifier's
 * is: this value reaches log lines and error paths, so a permissive charset is a log-injection
 * vector. It is narrower than a name's on purpose — a name must accept whatever a person is called,
 * while a login identifier is something the platform issues or the person chooses from a stated
 * set, so a restriction here rejects nothing legitimate.
 *
 * <p><strong>Classified {@code CONFIDENTIAL} rather than {@code RESTRICTED-PII}</strong>, and the
 * reasoning is in {@code DATA_CLASSIFICATION.md}: it is not itself personal data, but knowing one
 * exists tells an attacker an account exists, which is the enumeration risk {@code INV-IDN-07}
 * exists for. That is why it is absent from {@link Identity#toString()}.
 */
public record LoginIdentifier(String value) {

    /** Long enough for any reasonable handle; short enough to bound a row and an index. */
    public static final int MAX_LENGTH = 64;

    /** At least this long, so a single character cannot be brute-forced by enumeration. */
    public static final int MIN_LENGTH = 3;

    /**
     * Letters, digits, and the three separators handles conventionally use.
     *
     * <p>No {@code @}, deliberately. Permitting it would invite exactly the email-as-login
     * confusion this type exists to prevent, and would do it silently — the first person to enter
     * an address would establish a convention nobody decided.
     */
    private static final Pattern ALLOWED = Pattern.compile("[a-z0-9._-]+");

    public LoginIdentifier {
        Objects.requireNonNull(value, "loginIdentifier must not be null");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "loginIdentifier must be between "
                            + MIN_LENGTH
                            + " and "
                            + MAX_LENGTH
                            + " characters but was "
                            + value.length());
        }
        if (!ALLOWED.matcher(value).matches()) {
            // The value is not echoed. It is what someone types to log in, and a rejection message
            // repeating it would put a near-miss of a real account identifier into a log line.
            throw new IllegalArgumentException(
                    "loginIdentifier contains characters outside the permitted set");
        }
    }

    /**
     * Masked, like {@code PartyName} and for a related reason.
     *
     * <p>A record's generated {@code toString} prints every component, so without this the
     * identifier would reach any log line that happened to interpolate an {@code Identity}'s
     * fields — and an identifier in a log is an enumeration aid for anyone who can read logs.
     */
    @Override
    public String toString() {
        return "LoginIdentifier[***]";
    }
}
