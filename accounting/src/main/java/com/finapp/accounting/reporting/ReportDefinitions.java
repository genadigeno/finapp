package com.finapp.accounting.reporting;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The report definitions this build holds (`P14-TSK-008`; ADR-0115 decision 1) - by code, every version kept, so a run
 * generated from version 1 regenerates from version 1 after version 2 ships. A new run is generated from its code's
 * highest version. Immutable; identical on every instance of one build (a rolling deploy can hold two builds, and a run
 * pins the version it was generated from, so the two never mix in one run).
 *
 * <p>Production holds the {@link ReportDefinition} beans the composition root collects - the first is `P14-TSK-009`'s
 * {@link TrialBalanceDefinition}; a code no bean declares is refused {@code accounting.ReportDefinitionUnknown}.
 */
public final class ReportDefinitions {

    /** A definition's code: {@code accounting V004}'s {@code report_run_definition_shaped}. */
    public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    /** A definition's highest version: {@code accounting V004}'s {@code report_run_versions_bounded}. */
    public static final int MAX_VERSION = 9999;

    private final Map<String, TreeMap<Integer, ReportDefinition>> byCode;

    private ReportDefinitions(Map<String, TreeMap<Integer, ReportDefinition>> byCode) {
        this.byCode = byCode;
    }

    /**
     * The registry of {@code definitions}.
     *
     * @throws IllegalArgumentException for a malformed code, a version out of 1 to {@link #MAX_VERSION}, or a code and
     *     version held twice
     */
    public static ReportDefinitions of(Collection<? extends ReportDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, TreeMap<Integer, ReportDefinition>> byCode = new TreeMap<>();
        for (ReportDefinition definition : definitions) {
            String code = Objects.requireNonNull(definition.code(), "a definition's code");
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("a report definition's code is " + CODE.pattern() + ": " + code);
            }
            if (definition.version() < 1 || definition.version() > MAX_VERSION) {
                throw new IllegalArgumentException("a report definition's version is 1 to " + MAX_VERSION + ": " + code);
            }
            if (byCode.computeIfAbsent(code, ignored -> new TreeMap<>())
                    .putIfAbsent(definition.version(), definition) != null) {
                throw new IllegalArgumentException("a report definition's code and version are held once: " + code
                        + " v" + definition.version());
            }
        }
        return new ReportDefinitions(Collections.unmodifiableMap(byCode));
    }

    /** The registry of {@code definitions}. */
    public static ReportDefinitions of(ReportDefinition... definitions) {
        return of(List.of(definitions));
    }

    /** The highest version of {@code code} - what a new run is generated from. */
    public Optional<ReportDefinition> current(String code) {
        TreeMap<Integer, ReportDefinition> versions = code == null ? null : byCode.get(code);
        return versions == null ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
    }

    /** Exactly {@code code} version {@code version} - what a run regenerates from. */
    public Optional<ReportDefinition> find(String code, int version) {
        TreeMap<Integer, ReportDefinition> versions = code == null ? null : byCode.get(code);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    /** The codes held, sorted - the bounded values of the meters' {@code definition} tag. */
    public List<String> codes() {
        return List.copyOf(byCode.keySet());
    }
}
