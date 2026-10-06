package com.finapp.crossborder;

import com.finapp.crossborder.BeneficiaryVocabulary.EntityType;
import com.finapp.crossborder.BeneficiaryVocabulary.SelectionOutcome;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The provider selected for a beneficiary at registration (`P9-TSK-017`, ADR-0080 section 5a) - a pure
 * function of the pinned corridor policy version's corridors, the inputs, the corridors observed
 * available and the build's corridor directory, so recomputing it over a stored selection reproduces it
 * ({@code INV-RAIL-02}'s determinism at a new subject).
 *
 * <p>The candidates are the rails of every available corridor delivering the beneficiary's currency in
 * its country, corridors in code order and rails in each corridor's policy order, a rail judged once at
 * its first appearance. Each is judged in the order the outcomes are declared - the build declares it,
 * it delivers the currency, it covers the country, it is operable here - and the first eligible one is
 * {@code CHOSEN}; nothing after it is judged.
 */
public final class CorridorSelection {

    private CorridorSelection() {}

    /** What the selection judges. */
    public record Inputs(CountryCode country, CurrencyCode currency, EntityType entityType) {
        public Inputs {
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(entityType, "entityType must not be null");
        }
    }

    /** One judged candidate. */
    public record Step(int ordinal, String corridor, String rail, SelectionOutcome outcome) {
        public Step {
            Objects.requireNonNull(corridor, "corridor must not be null");
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /** The judgement: every step up to the chosen rail, and the available corridors it read. */
    public record Selection(Inputs inputs, Set<String> availableCorridors, List<Step> steps) {
        public Selection {
            Objects.requireNonNull(inputs, "inputs must not be null");
            availableCorridors = Set.copyOf(availableCorridors);
            steps = List.copyOf(steps);
        }

        /** The chosen rail, if any candidate was eligible. */
        public Optional<String> chosen() {
            return steps.stream().filter(step -> step.outcome() == SelectionOutcome.CHOSEN).map(Step::rail).findFirst();
        }
    }

    /**
     * Judges {@code inputs} over {@code corridors} (the pinned version's), with {@code available} the
     * corridors observed available and {@code directory} the build's declarations and operability.
     */
    public static Selection select(
            Inputs inputs, List<CorridorTerms> corridors, Predicate<String> available, CorridorDirectory directory) {
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(corridors, "corridors must not be null");
        Objects.requireNonNull(available, "available must not be null");
        Objects.requireNonNull(directory, "directory must not be null");
        List<CorridorTerms> matching = corridors.stream()
                .filter(terms -> terms.key().destination().equals(inputs.currency()))
                .filter(terms -> terms.key().country().equals(inputs.country()))
                .sorted(Comparator.comparing(terms -> terms.key().code()))
                .toList();
        Set<String> observed = new java.util.TreeSet<>();
        Map<String, String> candidates = new LinkedHashMap<>();
        for (CorridorTerms terms : matching) {
            if (!available.test(terms.key().code())) {
                continue;
            }
            observed.add(terms.key().code());
            for (String rail : terms.rails()) {
                candidates.putIfAbsent(rail, terms.key().code());
            }
        }
        List<Step> steps = new ArrayList<>();
        int ordinal = 0;
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            SelectionOutcome outcome = judge(candidate.getKey(), inputs, directory);
            steps.add(new Step(++ordinal, candidate.getValue(), candidate.getKey(), outcome));
            if (outcome == SelectionOutcome.CHOSEN) {
                break;
            }
        }
        return new Selection(inputs, observed, steps);
    }

    private static SelectionOutcome judge(String rail, Inputs inputs, CorridorDirectory directory) {
        Optional<CorridorDirectory.DeclaredRail> declared = directory.declared(rail);
        if (declared.isEmpty()) {
            return SelectionOutcome.UNDECLARED_BY_BUILD;
        }
        if (declared.get().coverage().stream().noneMatch(coverage -> coverage.currency().equals(inputs.currency()))) {
            return SelectionOutcome.CURRENCY_UNSUPPORTED;
        }
        if (!declared.get().covers(inputs.country(), inputs.currency())) {
            return SelectionOutcome.NO_COVERAGE;
        }
        if (!directory.operable(rail)) {
            return SelectionOutcome.UNAVAILABLE;
        }
        return SelectionOutcome.CHOSEN;
    }
}
