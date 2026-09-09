package com.finapp.kyc;

import java.net.URI;
import java.time.Duration;

/**
 * Document verification over the simulated provider (`P2-TSK-009`, ADR-0008).
 *
 * <p>The {@link IdentityVerificationAdapter} shape: one question, one path, the shared client's
 * contract. What the provider is asked <em>about</em> is the subject's identifiers — routing a
 * specific captured document to the provider is part of `P2-TSK-010`+'s evidence wiring and the
 * simulated protocol has no use for the bytes.
 */
public final class DocumentVerificationAdapter implements VerificationProvider {

    /** The simulated wire path — published for tests that stub the provider. */
    public static final String PATH = "/document-verifications";

    private final SimulatedProviderClient client;

    public DocumentVerificationAdapter(URI baseUrl, Duration timeout) {
        this.client = new SimulatedProviderClient(baseUrl, timeout);
    }

    @Override
    public CheckType checkType() {
        return CheckType.DOCUMENT;
    }

    @Override
    public ProviderResult verify(VerificationSubject subject) {
        return client.ask(PATH, subject);
    }
}
