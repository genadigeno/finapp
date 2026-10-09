package com.finapp.credit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The evaluation engines this build holds, by version (`P10-TSK-013`; {@code INV-CRD-01}, PHASE_10_PLAN.md section 14
 * row 26). Replay selects the engine a snapshot PINNED, never the newest: a decision made under engine 1 is re-derived
 * under engine 1 for as long as it can be asked about, so an engine is added beside the old and never replaces it.
 *
 * <p>Immutable, and the same on every instance - a build's engines are its code.
 */
public final class EngineVersions {

    /** This build's engines: version 1 alone. */
    public static final EngineVersions STANDARD = new EngineVersions(List.of(new PolicyEvaluatorV1()));

    private final Map<Integer, PolicyEvaluator> engines;

    /** The engines {@code engines} - one per version, each version from 1 held. */
    EngineVersions(List<PolicyEvaluator> engines) {
        Objects.requireNonNull(engines, "engines");
        Map<Integer, PolicyEvaluator> byVersion = new TreeMap<>();
        for (PolicyEvaluator engine : engines) {
            if (byVersion.put(engine.engineVersion(), engine) != null) {
                throw new IllegalArgumentException("engine version " + engine.engineVersion() + " is held twice");
            }
        }
        if (byVersion.isEmpty() || !byVersion.containsKey(PolicyEvaluatorV1.VERSION)) {
            throw new IllegalArgumentException("every build holds engine version 1, for replay");
        }
        this.engines = Map.copyOf(byVersion);
    }

    /**
     * The engines {@code engines} - how a build that adds engine 2 beside engine 1 declares them (`P10-TSK-019`: the
     * replay suite proves old decisions replay under their own engine when a newer one is held).
     */
    public static EngineVersions of(List<PolicyEvaluator> engines) {
        return new EngineVersions(engines);
    }

    /** The engine of {@code version}; a version this build does not hold is a defect, never a fallback to another. */
    public PolicyEvaluator engine(int version) {
        PolicyEvaluator engine = engines.get(version);
        if (engine == null) {
            throw new IllegalStateException("engine version " + version + " is not held by this build");
        }
        return engine;
    }
}
