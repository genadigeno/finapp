package com.finapp.identity;

import java.util.Objects;
import java.util.Optional;

/**
 * What a credential verification decided (`P1-TSK-008`).
 *
 * <h2>There is no reason, and that is the design</h2>
 *
 * <p>A failure carries <strong>nothing</strong>: no reason code, no identity, no status, no
 * {@code Optional} that happens to be empty in one case and populated in another. That is not
 * minimalism, it is {@code INV-IDN-07} made structural.
 *
 * <p>The invariant says a failed authentication must be indistinguishable between an existing and a
 * non-existent account. A caller will branch on whatever it is handed - that is what callers are
 * for - so a {@code reason} field is an enumeration oracle with a delay fuse: harmless on the day it
 * is added, and the day somebody maps it to a message the platform has two response shapes and
 * nothing failed. Handing the caller nothing to branch on is the only version of this that survives
 * a future author in a hurry.
 *
 * <p>The one distinction the platform legitimately needs - <em>which identifier was attempted</em>,
 * for the audit record - is something the caller already has, because it supplied it. Nothing has to
 * come back out of here for that.
 *
 * <h2>Success carries the identity, because the caller cannot get it any other way</h2>
 *
 * <p>{@code P1-TSK-010} needs it to issue a session. Resolving the login identifier a second time
 * would be a second query and, worse, a second chance for the two lookups to disagree.
 */
public final class VerificationOutcome {

    /**
     * The single failure value.
     *
     * <p>One instance, shared. Two failures are not merely equal, they are <em>identical</em> - so
     * there is no object identity to tell them apart either, which closes the last place a
     * distinction could hide.
     */
    private static final VerificationOutcome FAILED = new VerificationOutcome(null);

    private final IdentityId identityId;

    private VerificationOutcome(IdentityId identityId) {
        this.identityId = identityId;
    }

    public static VerificationOutcome succeeded(IdentityId identityId) {
        return new VerificationOutcome(
                Objects.requireNonNull(identityId, "identityId must not be null"));
    }

    /**
     * The password did not verify.
     *
     * <p>Returned identically when the identity does not exist, when it cannot authenticate, when it
     * holds no credential, and when the password is simply wrong. The caller cannot tell, because
     * there is nothing here to tell it with.
     */
    public static VerificationOutcome failed() {
        return FAILED;
    }

    public boolean isSuccess() {
        return identityId != null;
    }

    /** The authenticated identity, present only on success. */
    public Optional<IdentityId> identityId() {
        return Optional.ofNullable(identityId);
    }

    /** Says whether it succeeded and, on success, which identity. Never why it failed. */
    @Override
    public String toString() {
        return isSuccess() ? "VerificationOutcome[succeeded, " + identityId + "]"
                : "VerificationOutcome[failed]";
    }
}
