package com.finapp.accounting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The posting rule register (`P14-TSK-002`; ADR-0117 decision 1; {@code PHASE_14_PLAN.md} section 13.2;
 * {@code INV-ACC-12}'s static half): every posting namespace the platform writes, read from the code that writes it, with
 * its rule, version, line shape and dating class. <strong>Code, not data an operator activates</strong> (ADR-0117's
 * rejected alternative: data would describe code it cannot change) - versioned by {@link #CATALOGUE_VERSION}; a changed
 * rule is a new {@link PostingRule} version with an {@code effectiveFrom} posting date, and an entry is judged by the
 * version in force at its posting date ({@link #inForce}).
 *
 * <p>Descriptive only: it invents no treatment and posts nothing. A new poster registers its namespace in the same change
 * ({@code app}'s {@code PostingRuleRegisterTest} fails the build otherwise, the {@code *HasOnePosterTest} precedent), and
 * the runtime check of every entry against it is `P14-TSK-010`'s.
 *
 * <p>The constructor refuses an incoherent catalogue - a repeated {@code (id, version)}, an id spanning two namespaces or
 * types, versions whose effective dates do not rise with them - so {@link #CURRENT} failing to build fails every test and
 * the application's start, never a later read.
 */
public final class PostingRuleRegister {

    /** The catalogue's version: raised whenever any rule is added, versioned or withdrawn. */
    public static final int CATALOGUE_VERSION = 1;

    /**
     * The effective date of every version-1 rule: each describes the code as it has posted since its namespace's first
     * entry - a rule that later gained a line gained an <em>optional</em> one, so every earlier entry still conforms.
     */
    public static final LocalDate ORIGIN = LocalDate.EPOCH;

    /** The register as built. */
    public static final PostingRuleRegister CURRENT =
            new PostingRuleRegister(CATALOGUE_VERSION, PostingRuleCatalogue.rules());

    private final int catalogueVersion;
    private final List<PostingRule> rules;
    /** {@code type namespace} -> the rule ids keyed there, each id's versions ascending. */
    private final Map<String, Map<String, List<PostingRule>>> byKey;

    public PostingRuleRegister(int catalogueVersion, List<PostingRule> rules) {
        if (catalogueVersion < 1) {
            throw new IllegalArgumentException("a catalogue version counts from 1");
        }
        this.catalogueVersion = catalogueVersion;
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (this.rules.isEmpty()) {
            throw new IllegalArgumentException("a register registers something");
        }
        Map<String, List<PostingRule>> byId = new LinkedHashMap<>();
        for (PostingRule rule : this.rules) {
            byId.computeIfAbsent(rule.id(), id -> new ArrayList<>()).add(rule);
        }
        Map<String, Map<String, List<PostingRule>>> keyed = new HashMap<>();
        byId.forEach((id, versions) -> {
            versions.sort(Comparator.comparingInt(PostingRule::version));
            for (int i = 0; i < versions.size(); i++) {
                PostingRule rule = versions.get(i);
                if (i > 0) {
                    PostingRule earlier = versions.get(i - 1);
                    if (rule.version() == earlier.version()) {
                        throw new IllegalArgumentException(id + " registers version " + rule.version() + " twice");
                    }
                    if (rule.type() != earlier.type() || !rule.namespace().equals(earlier.namespace())) {
                        throw new IllegalArgumentException(id + "'s versions must describe one type and namespace");
                    }
                    if (!rule.effectiveFrom().isAfter(earlier.effectiveFrom())) {
                        throw new IllegalArgumentException(id + "'s version " + rule.version()
                                + " must take effect after version " + earlier.version());
                    }
                }
            }
            PostingRule first = versions.get(0);
            keyed.computeIfAbsent(key(first.type(), first.namespace()), k -> new LinkedHashMap<>())
                    .put(id, List.copyOf(versions));
        });
        Map<String, Map<String, List<PostingRule>>> frozen = new HashMap<>();
        keyed.forEach((key, ids) -> frozen.put(key, Collections.unmodifiableMap(ids)));
        this.byKey = Map.copyOf(frozen);
    }

    /** The catalogue's version. */
    public int catalogueVersion() {
        return catalogueVersion;
    }

    /** Every rule, every version, in the catalogue's order. */
    public List<PostingRule> rules() {
        return rules;
    }

    /** Every namespace registered for {@code type}, sorted. */
    public Set<String> namespaces(EntryType type) {
        Set<String> namespaces = new TreeSet<>();
        rules.stream().filter(rule -> rule.type() == type).forEach(rule -> namespaces.add(rule.namespace()));
        return namespaces;
    }

    /**
     * The rules that judge an entry of {@code type} under {@code namespace} posted on {@code postingDate}: per rule id,
     * the latest version whose {@code effectiveFrom} is on or before the date. Empty when the namespace is unregistered
     * ({@code UNREGISTERED_POSTING}) or no version is yet in force.
     */
    public List<PostingRule> inForce(EntryType type, String namespace, LocalDate postingDate) {
        Objects.requireNonNull(postingDate, "postingDate");
        Map<String, List<PostingRule>> ids = byKey.getOrDefault(key(type, namespace), Map.of());
        List<PostingRule> judging = new ArrayList<>();
        for (List<PostingRule> versions : ids.values()) {
            PostingRule inForce = null;
            for (PostingRule version : versions) {
                if (!version.effectiveFrom().isAfter(postingDate)) {
                    inForce = version;
                }
            }
            if (inForce != null) {
                judging.add(inForce);
            }
        }
        return List.copyOf(judging);
    }

    /** The rule with this id at this version, if registered. */
    public Optional<PostingRule> rule(String id, int version) {
        return rules.stream().filter(rule -> rule.id().equals(id) && rule.version() == version).findFirst();
    }

    /**
     * The namespace of a command key - the key up to and including its first {@code ':'}; a key with none is its own
     * namespace and no registered rule's ({@code MonitoredKind}'s convention, `P13-TSK-019`).
     */
    public static String namespaceOf(String key) {
        int colon = Objects.requireNonNull(key, "key").indexOf(':');
        return colon < 0 ? key : key.substring(0, colon + 1);
    }

    private static String key(EntryType type, String namespace) {
        return type.name() + ' ' + namespace;
    }
}
