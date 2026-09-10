package com.finapp.kyc;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * The three screening questions over the simulated provider (`P2-TSK-010`, ADR-0008,
 * `PHASE_2_PLAN.md` §5: screening is a {@code VerificationCheck} of the three screening types —
 * one machine, not a second one).
 *
 * <p>One class with three static factories rather than three near-identical adapters: the whole
 * job is binding a {@link CheckType} to its wire path, and a factory is what makes a
 * type-to-path mismatch <strong>unconstructible</strong> — there is no public constructor to
 * hand {@code SANCTIONS} the PEP path. The port's total contract (misbehaviour is a result,
 * never an exception, defaulting to {@code INDETERMINATE}) lives in the shared
 * {@link SimulatedProviderClient} and is inherited, not restated; its matrix is
 * {@code VerificationAdapterTest}'s subject and is deliberately not duplicated per type.
 */
public final class ScreeningAdapter implements VerificationProvider {

    /** The simulated wire paths — published for tests that stub the provider. */
    public static final String SANCTIONS_PATH = "/sanctions-screenings";

    public static final String PEP_PATH = "/pep-screenings";
    public static final String ADVERSE_MEDIA_PATH = "/adverse-media-screenings";

    private final CheckType type;
    private final String path;
    private final SimulatedProviderClient client;

    private ScreeningAdapter(CheckType type, String path, URI baseUrl, Duration timeout) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.client = new SimulatedProviderClient(baseUrl, timeout);
    }

    public static ScreeningAdapter sanctions(URI baseUrl, Duration timeout) {
        return new ScreeningAdapter(CheckType.SANCTIONS, SANCTIONS_PATH, baseUrl, timeout);
    }

    public static ScreeningAdapter pep(URI baseUrl, Duration timeout) {
        return new ScreeningAdapter(CheckType.PEP, PEP_PATH, baseUrl, timeout);
    }

    public static ScreeningAdapter adverseMedia(URI baseUrl, Duration timeout) {
        return new ScreeningAdapter(CheckType.ADVERSE_MEDIA, ADVERSE_MEDIA_PATH, baseUrl, timeout);
    }

    @Override
    public CheckType checkType() {
        return type;
    }

    @Override
    public ProviderResult verify(VerificationSubject subject) {
        return client.ask(path, subject);
    }
}
