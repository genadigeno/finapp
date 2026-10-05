package com.finapp.payments;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which rails speak which operation port - a directory, not a wired singleton (`P9-TSK-014`,
 * ADR-0080 section 2, paying ADR-0059 section 1's recorded seam). The composition root builds it:
 * {@code RailId → PushRail} for the instant rail, {@code RailId → CorridorRail} for the corridor
 * rails, beside every declared corridor rail's {@link CorridorDeclaration}.
 *
 * <p>A command looks its routed rail up and <strong>refuses, with nothing sent</strong>, a rail
 * that lacks the operation it needs: a withdrawal routed to a corridor rail finds no push rail and
 * stops inside its first transaction, before the hold, so nothing is written and nothing leaves.
 *
 * <p>Immutable after composition, and identical on every instance - a function of the build's
 * declarations and the deployment's configured adapters, holding no state of its own.
 */
public final class RailOperations {

    private final Map<RailId, PushRail> push;
    private final Map<RailId, CorridorRail> corridor;
    private final Map<RailId, CorridorDeclaration> declarations;

    private RailOperations(
            Map<RailId, PushRail> push,
            Map<RailId, CorridorRail> corridor,
            Map<RailId, CorridorDeclaration> declarations) {
        this.push = Map.copyOf(push);
        this.corridor = Map.copyOf(corridor);
        this.declarations = Map.copyOf(declarations);
    }

    /**
     * The directory, composed and verified against the build's declared rails: every declared
     * corridor rail ({@code RefundMode.NONE}) has exactly one coherent {@link CorridorDeclaration}
     * and every declaration a declared corridor rail; a push adapter speaks for a declared push rail
     * that is no corridor; a corridor adapter speaks for a declared corridor rail, under its own id;
     * and no rail speaks both ports.
     */
    public static RailOperations of(
            PaymentRails rails,
            Collection<CorridorDeclaration> corridorDeclarations,
            Map<RailId, PushRail> pushRails,
            Map<RailId, CorridorRail> corridorRails) {
        Objects.requireNonNull(rails, "rails must not be null");
        Objects.requireNonNull(corridorDeclarations, "corridorDeclarations must not be null");
        Objects.requireNonNull(pushRails, "pushRails must not be null");
        Objects.requireNonNull(corridorRails, "corridorRails must not be null");
        Map<RailId, CorridorDeclaration> declared = new LinkedHashMap<>();
        for (CorridorDeclaration declaration : corridorDeclarations) {
            PaymentRail rail = rails.declared(declaration.rail())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "a corridor declaration names '" + declaration.rail().value()
                                    + "', which no rail of this build declares"));
            declaration.requireCoherentWith(rail);
            if (declared.putIfAbsent(declaration.rail(), declaration) != null) {
                throw new IllegalArgumentException(
                        "two corridor declarations for '" + declaration.rail().value() + "'");
            }
        }
        for (RailId rail : rails.declaredIds()) {
            boolean corridorRail =
                    rails.capabilitiesOf(rail).refundMode() == RailCapabilities.RefundMode.NONE;
            if (corridorRail && !declared.containsKey(rail)) {
                throw new IllegalArgumentException(
                        "rail '" + rail.value() + "' carries credits only (RefundMode.NONE) and has no"
                                + " corridor declaration: its coverage, windows and charge bearer are"
                                + " part of its truth (ADR-0080 section 1)");
            }
        }
        pushRails.forEach((id, adapter) -> {
            Objects.requireNonNull(adapter, "a push adapter must not be null");
            RailCapabilities capabilities = rails.capabilitiesOf(id);
            if (capabilities.interactionModel() != InteractionModel.PUSH
                    || capabilities.refundMode() == RailCapabilities.RefundMode.NONE) {
                throw new IllegalArgumentException(
                        "a push adapter speaks for a push rail that is no corridor: '" + id.value() + "'");
            }
        });
        corridorRails.forEach((id, adapter) -> {
            Objects.requireNonNull(adapter, "a corridor adapter must not be null");
            if (!declared.containsKey(id)) {
                throw new IllegalArgumentException(
                        "a corridor adapter speaks for '" + id.value() + "', which is no declared corridor rail");
            }
            if (!adapter.id().equals(id)) {
                throw new IllegalArgumentException(
                        "the corridor adapter for '" + id.value() + "' speaks for '" + adapter.id().value() + "'");
            }
            if (pushRails.containsKey(id)) {
                throw new IllegalArgumentException("rail '" + id.value() + "' cannot speak both ports");
            }
        });
        return new RailOperations(pushRails, corridorRails, declared);
    }

    /** No adapter at all - a deployment configuring no wire (and the card-only tests). */
    public static RailOperations none() {
        return new RailOperations(Map.of(), Map.of(), Map.of());
    }

    /** One push rail and nothing else - the Phase 7 deployments' shape, for tests and tools. */
    public static RailOperations ofPush(RailId rail, PushRail adapter) {
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(adapter, "adapter must not be null");
        return new RailOperations(Map.of(rail, adapter), Map.of(), Map.of());
    }

    /** The push adapter speaking for {@code rail}, or empty: the rail lacks the push operation here. */
    public Optional<PushRail> pushRail(RailId rail) {
        Objects.requireNonNull(rail, "rail must not be null");
        return Optional.ofNullable(push.get(rail));
    }

    /** The corridor adapter speaking for {@code rail}, or empty. */
    public Optional<CorridorRail> corridorRail(RailId rail) {
        Objects.requireNonNull(rail, "rail must not be null");
        return Optional.ofNullable(corridor.get(rail));
    }

    /** The corridor declaration of {@code rail}, or empty when it is no declared corridor rail. */
    public Optional<CorridorDeclaration> corridorDeclaration(RailId rail) {
        Objects.requireNonNull(rail, "rail must not be null");
        return Optional.ofNullable(declarations.get(rail));
    }

    /** Every declared corridor rail's declaration - configured adapter or not. */
    public Collection<CorridorDeclaration> corridorDeclarations() {
        return declarations.values();
    }

    /** The rails a push adapter speaks for. */
    public Set<RailId> pushRailIds() {
        return push.keySet();
    }

    /** Whether any push adapter is configured - the bank branch's honest 503 when none is. */
    public boolean anyPushRail() {
        return !push.isEmpty();
    }
}
