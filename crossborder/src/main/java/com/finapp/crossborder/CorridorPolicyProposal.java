package com.finapp.crossborder;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A whole corridor policy version as proposed (`P9-TSK-015`): every corridor's terms and the
 * proposer's reason. Judged by {@link #validate} at proposal AND again at approval - the build may
 * have changed between the two acts, and a corridor no declared rail can carry is never activated.
 */
public record CorridorPolicyProposal(List<CorridorTerms> corridors, String reason) {

    /** A version's bound - a policy is a short list of decided corridors. */
    public static final int MAX_CORRIDORS = 100;

    public CorridorPolicyProposal {
        Objects.requireNonNull(corridors, "corridors must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        corridors = List.copyOf(corridors);
    }

    /**
     * Judges the version: 1..100 corridors, each named once, the reason screened, every candidate rail
     * declared by the build and covering (country, D), and every required datum one the platform holds.
     *
     * @throws CorridorPolicyAdministration.CorridorPolicyInvalid for a malformed version or reason
     * @throws CorridorPolicyAdministration.RailNotDeclared for a rail the build does not declare, or one
     *     that does not cover the corridor's (country, D)
     * @throws CorridorPolicyAdministration.RequiredDataUnsatisfiable for data the platform does not hold
     */
    public void validate(CorridorDirectory directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        CrossborderReasons.refuse(reason, CorridorPolicyAdministration.CorridorPolicyInvalid::new);
        judge(corridors, directory);
    }

    /** The corridor judgements alone - shared with the approval's re-judgement of stored terms. */
    static void judge(List<CorridorTerms> corridors, CorridorDirectory directory) {
        if (corridors.isEmpty() || corridors.size() > MAX_CORRIDORS) {
            throw new CorridorPolicyAdministration.CorridorPolicyInvalid(
                    "a corridor policy version holds 1.." + MAX_CORRIDORS + " corridors");
        }
        Set<CorridorKey> seen = new HashSet<>();
        for (CorridorTerms terms : corridors) {
            if (!seen.add(terms.key())) {
                throw new CorridorPolicyAdministration.CorridorPolicyInvalid(
                        "corridor " + terms.key() + " appears twice in one version");
            }
            for (String rail : terms.rails()) {
                CorridorDirectory.DeclaredRail declared = directory.declared(rail)
                        .orElseThrow(() -> new CorridorPolicyAdministration.RailNotDeclared(rail, terms.key()));
                if (!declared.covers(terms.key().country(), terms.key().destination())) {
                    throw new CorridorPolicyAdministration.RailNotDeclared(rail, terms.key());
                }
            }
            for (RequiredData datum : terms.requiredData()) {
                if (!datum.held()) {
                    throw new CorridorPolicyAdministration.RequiredDataUnsatisfiable(datum, terms.key());
                }
            }
        }
    }
}
