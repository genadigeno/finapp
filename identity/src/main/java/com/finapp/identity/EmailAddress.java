package com.finapp.identity;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A contact channel address (`P1-TSK-023`).
 *
 * <h2>Deliberately not the login identifier, and this is the type that proves it</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §Value objects: <em>"a login identifier that is also a contact channel
 * cannot be changed without changing how someone logs in, and cannot be verified without blocking
 * login."</em> {@link LoginIdentifier} excludes {@code @} from its charset specifically so the
 * confusion cannot arrive silently through the first person who types an address; this is the other
 * half of that decision finally existing.
 *
 * <h2>Normalised on the way in, once</h2>
 *
 * <p>Lower-cased and trimmed at construction, so a capital letter finds the same row the unique
 * index claimed — the {@code LoginIdentifier} argument. The <strong>local part</strong> is
 * technically case-sensitive per RFC 5321 and no mail system anybody uses treats it that way;
 * normalising is the choice that makes "is this the same address?" answerable, and the alternative
 * is two verified channels differing only in capitalisation.
 *
 * <h2>Validation is deliberately shallow</h2>
 *
 * <p>One {@code @}, something either side, a dot in the domain, no whitespace, no control
 * characters. <strong>Not</strong> RFC 5322: that grammar admits addresses no mail provider accepts
 * and rejecting a valid one locks a customer out of recovery. The real verification is the
 * challenge — an address is proven by somebody reading what was sent to it, not by a regex.
 *
 * <p>Control characters are excluded for {@code PartyName}'s reason ({@code P1-TSK-006}): this value
 * is rendered back to a person and written to a {@code RESTRICTED-PII} column, so a CR in it is a
 * forged log line.
 */
public record EmailAddress(String value) {

    /** Matches the column bound. Long enough for any real address; short enough to be a bound. */
    public static final int MAX_LENGTH = 254;

    /**
     * Shallow on purpose — see the class javadoc.
     *
     * <p>Excludes whitespace and every C0/C1 control character by admitting only printable
     * non-space characters, so the charset states what is allowed rather than listing what is not.
     */
    private static final Pattern PERMITTED =
            Pattern.compile("[\\p{Graph}&&[^@]]+@[\\p{Graph}&&[^@]]+\\.[\\p{Graph}&&[^@.]]+");

    public EmailAddress {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim().toLowerCase(Locale.ROOT);

        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "an email address must be at most " + MAX_LENGTH + " characters");
        }
        if (!PERMITTED.matcher(value).matches()) {
            // No echo of the rejected value. It is RESTRICTED-PII and this message reaches a log.
            throw new IllegalArgumentException("that is not a usable email address");
        }
    }

    /**
     * From a wrapped value, which is how one arrives from a request body.
     *
     * <p>The unwrap happens <strong>here</strong>, inside {@code identity}, rather than in the
     * controller: {@code SecretsAreUnwrappedInOnePlaceTest} pins the set of production classes that
     * may expose a wrapped value, and an entry in {@code app} would mean PII had left the module
     * that owns it. The controller passes the wrapper through untouched.
     */
    public static EmailAddress of(com.finapp.sharedkernel.security.Sensitive<String> address) {
        Objects.requireNonNull(address, "address must not be null");
        return new EmailAddress(address.expose());
    }

    /**
     * Masked, because a record's generated {@code toString} prints every component.
     *
     * <p>{@code INV-AUD-02}: an address in a log line is PII in a system with different access
     * control and months of retention. The domain part is kept, because "which provider" is
     * occasionally an operational question and is not a person.
     */
    @Override
    public String toString() {
        return "EmailAddress[***@" + value.substring(value.indexOf('@') + 1) + "]";
    }
}
