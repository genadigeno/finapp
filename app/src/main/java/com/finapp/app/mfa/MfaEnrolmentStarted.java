package com.finapp.app.mfa;

/**
 * What a customer receives when they begin an enrolment (`P1-TSK-017`).
 *
 * <h2>One artefact, and it contains the secret</h2>
 *
 * <p>**Said plainly, because the field name does not say it:** `provisioningUri` is an
 * `otpauth://` URI with the shared secret inside it. That is what a QR code *is*. There is no
 * design in which the customer configures an authenticator without receiving the secret.
 *
 * <p>`PHASE_1_PLAN.md` §184 says *"never emitted"* and `INV-AUD-02` forbids credentials in API
 * responses. Neither can be met literally, so the bound is: emitted **once**, in the response to
 * the request that created it, to the **proven owner**, and never retrievable afterwards. A
 * customer who loses it re-enrols.
 *
 * <h2>The separate `sharedSecret` field is gone, and the build rule is why</h2>
 *
 * <p>The first version carried the base32 secret *as well*, for manual entry. `secretsAreWrapped`
 * flagged it and **was right** - a `String sharedSecret` on a serialised record is exactly the
 * shape `INV-AUD-02` exists to stop, and wrapping it was not available either, because the
 * platform's serialiser renders `Sensitive` as a mask and the customer would have received
 * «redacted».
 *
 * <p>So the code changed rather than the rule - `P1-TSK-007`'s lesson that a second security-rule
 * modification in one task is a signal to reconsider the design. Removing it is a genuine
 * improvement and not a dodge: the URI is the canonical artefact every authenticator consumes, a
 * client that wants a manual-entry string parses it out, and the same secret in two fields is one
 * more place for it to be logged.
 */
public record MfaEnrolmentStarted(String provisioningUri, int digits, int periodSeconds) {}
