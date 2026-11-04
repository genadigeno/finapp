package com.finapp.accounting;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The regulatory adapters this build holds, by name (`P14-TSK-018`; ADR-0118 decision 3): an obligation names one. Identical on
 * every instance of one build. A name held twice is refused at start-up. An adapter a row names and this build no longer holds
 * is a refusal at a door and a failed call at the drive: the permit stands.
 */
public final class RegulatoryAdapters {

    private final Map<String, RegulatoryReportingProvider> byName;

    public RegulatoryAdapters(Collection<RegulatoryReportingProvider> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        TreeMap<String, RegulatoryReportingProvider> held = new TreeMap<>();
        for (RegulatoryReportingProvider adapter : adapters) {
            if (held.putIfAbsent(adapter.name(), adapter) != null) {
                throw new IllegalArgumentException("a regulatory adapter's name is held once: " + adapter.name());
            }
        }
        this.byName = Map.copyOf(held);
    }

    /** The adapter named {@code name}. */
    public Optional<RegulatoryReportingProvider> named(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }
}
