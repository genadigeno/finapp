package com.finapp.kyc;

import java.net.URI;
import java.time.Duration;

/**
 * Identity verification over the simulated provider (`P2-TSK-009`, ADR-0008).
 *
 * <p>A thin binding of {@link SimulatedProviderClient} to one question and one path — the whole
 * adapter, because the port's contract (misbehaviour is a result, never an exception) lives in
 * the shared client and the vocabulary mapping is its one job.
 */
public final class IdentityVerificationAdapter implements VerificationProvider {

    /** The simulated wire path — published for tests that stub the provider. */
    public static final String PATH = "/identity-verifications";

    private final SimulatedProviderClient client;

    public IdentityVerificationAdapter(URI baseUrl, Duration timeout) {
        this.client = new SimulatedProviderClient(baseUrl, timeout);
    }

    @Override
    public CheckType checkType() {
        return CheckType.IDENTITY;
    }

    @Override
    public ProviderResult verify(VerificationSubject subject) {
        return client.ask(PATH, subject);
    }
}
