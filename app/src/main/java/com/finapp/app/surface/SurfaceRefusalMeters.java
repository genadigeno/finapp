package com.finapp.app.surface;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code finapp.identity.surface.refusal{type,kind,outcome}}: role-holding identities refused on the public surface, per door, whether
 * the request carried the public edge's marker or none, and at sign-in whether the password was the identity's (`P15-TSK-042`).
 * Eighteen series, registered eagerly.
 *
 * <p>{@code kind="unmarked"} is the rollout signal: a request with no usable marker where an edge must have set one, so either the
 * edge was rolled after the application or something other than the edge reached it. {@code outcome="credential_proven"} is the
 * compromise signal: an operator's correct password presented from the internet is a phished one. Staff are refused either way; this
 * is how either is seen before anyone calls. Every other door's refusal is {@code outcome="refused"}: it proves no credential. Which
 * identity is the audit trail's question ({@code identity.PublicSurfaceRefused}).
 */
public final class SurfaceRefusalMeters {

    private static final String NAME = "finapp.identity.surface.refusal";

    static final String PROVEN = "credential_proven";
    static final String UNPROVEN = "credential_unproven";
    static final String REFUSED = "refused";

    private final Map<SurfaceDoor, Map<Surface, Map<String, Counter>>> counters = new EnumMap<>(SurfaceDoor.class);

    public SurfaceRefusalMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        for (SurfaceDoor door : SurfaceDoor.values()) {
            Map<Surface, Map<String, Counter>> perSurface = new EnumMap<>(Surface.class);
            for (Surface surface : Surface.values()) {
                if (surface.admitsRoleHolders()) {
                    continue;
                }
                Map<String, Counter> perOutcome = new java.util.HashMap<>();
                for (String outcome : door == SurfaceDoor.AUTHENTICATION ? List.of(PROVEN, UNPROVEN) : List.of(REFUSED)) {
                    perOutcome.put(
                            outcome,
                            Counter.builder(NAME)
                                    .tag("type", door.tag())
                                    .tag("kind", surface.tag())
                                    .tag("outcome", outcome)
                                    .description("Role-holding identities refused on the public surface, per door")
                                    .register(registry));
                }
                perSurface.put(surface, perOutcome);
            }
            counters.put(door, perSurface);
        }
    }

    /** One refusal at a door that proves no credential. Sign-in says whether its password was the identity's: {@link #recordSignIn}. */
    public void record(SurfaceDoor door, Surface surface) {
        if (Objects.requireNonNull(door, "door must not be null") == SurfaceDoor.AUTHENTICATION) {
            throw new IllegalArgumentException("a sign-in refusal says whether the password was the identity's: recordSignIn");
        }
        counterOf(door, surface, REFUSED).increment();
    }

    /** One refusal at sign-in, and whether the password presented was the identity's. */
    public void recordSignIn(Surface surface, boolean proven) {
        counterOf(SurfaceDoor.AUTHENTICATION, surface, proven ? PROVEN : UNPROVEN).increment();
    }

    /** A surface that admits role-holders refuses nobody, so naming one is a defect, refused loudly. */
    private Counter counterOf(SurfaceDoor door, Surface surface, String outcome) {
        Map<String, Counter> perOutcome = counters.get(door).get(surface);
        if (perOutcome == null) {
            throw new IllegalArgumentException(surface + " admits role-holders and refuses nobody");
        }
        return perOutcome.get(outcome);
    }
}
