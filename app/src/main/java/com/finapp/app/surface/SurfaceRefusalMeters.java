package com.finapp.app.surface;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code finapp.identity.surface.refusal{type,kind}}: role-holding identities refused on the public surface, per door, and
 * whether the request carried the public edge's marker or none (`P15-TSK-042`). Sixteen series, registered eagerly.
 *
 * <p>{@code kind="unmarked"} is the rollout signal: a request with no usable marker where an edge must have set one, so either the
 * edge was rolled after the application or something other than the edge reached it. Staff are refused either way; this is
 * how that is seen before anyone calls. Which identity is the audit trail's question ({@code identity.PublicSurfaceRefused}).
 */
public final class SurfaceRefusalMeters {

    private static final String NAME = "finapp.identity.surface.refusal";

    private final Map<SurfaceDoor, Map<Surface, Counter>> counters = new EnumMap<>(SurfaceDoor.class);

    public SurfaceRefusalMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        for (SurfaceDoor door : SurfaceDoor.values()) {
            Map<Surface, Counter> perSurface = new EnumMap<>(Surface.class);
            for (Surface surface : Surface.values()) {
                if (surface.admitsRoleHolders()) {
                    continue;
                }
                perSurface.put(
                        surface,
                        Counter.builder(NAME)
                                .tag("type", door.tag())
                                .tag("kind", surface.tag())
                                .description("Role-holding identities refused on the public surface, per door")
                                .register(registry));
            }
            counters.put(door, perSurface);
        }
    }

    /** One refusal. A surface that admits role-holders refuses nobody, so naming one is a defect, refused loudly. */
    public void record(SurfaceDoor door, Surface surface) {
        Counter counter = counters.get(Objects.requireNonNull(door, "door must not be null")).get(surface);
        if (counter == null) {
            throw new IllegalArgumentException(surface + " admits role-holders and refuses nobody");
        }
        counter.increment();
    }
}
