package com.finapp.payments;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The rails this build declares (`P7-TSK-001`, ADR-0059 §1) — an immutable directory from
 * {@link RailId} to its declaration, so a capability decision keys on the <strong>stored</strong>
 * rail of the row in hand, never on whichever adapter the resolving instance happens to have
 * wired.
 *
 * <h2>Why a directory already, with one rail</h2>
 *
 * <p>An outcome is resolved by any instance — a webhook, a sweeper tick, a resolver on a
 * machine that never dispatched the payment ({@code DISTRIBUTED_EXECUTION.md} §3). The only
 * multi-instance-honest source for "which rail is this payment on" is therefore the attempt
 * row, and the lookup that turns the stored name back into capabilities is this class. With
 * one rail the directory is small; the shape is what routing (`P7-TSK-003`) and the second
 * rail (`P7-TSK-006`) inherit unchanged.
 *
 * <h2>An unknown rail is a wiring fault, never a domain refusal</h2>
 *
 * <p>A stored rail this build does not declare means a build was deployed that no longer
 * declares a rail it has rows for. The lookup throws {@link IllegalStateException} naming the
 * rail, so the outcome transaction fails loudly before any line posts to a guessed clearing
 * account — the right failure, in the {@code ChartOfAccounts} missing-seed shape.
 */
public final class PaymentRails {

    private final Map<RailId, PaymentRail> byId;

    private PaymentRails(Map<RailId, PaymentRail> byId) {
        this.byId = byId;
    }

    /** Builds the directory, refusing a duplicate declaration by name. */
    public static PaymentRails of(Collection<PaymentRail> rails) {
        Objects.requireNonNull(rails, "rails must not be null");
        Map<RailId, PaymentRail> byId = new LinkedHashMap<>();
        for (PaymentRail rail : rails) {
            Objects.requireNonNull(rail, "a declared rail must not be null");
            if (byId.putIfAbsent(rail.id(), rail) != null) {
                throw new IllegalArgumentException(
                        "two rails declare the id '" + rail.id().value()
                                + "': a rail's name is its identity, and a second declaration"
                                + " is a wiring fault");
            }
        }
        return new PaymentRails(Map.copyOf(byId));
    }

    /**
     * The declaration a candidate rail resolves to, or empty when this build declares none
     * (`P7-TSK-003`): routing records the absence as a step
     * ({@code RoutingRejection#UNDECLARED_BY_BUILD}) and moves to the next candidate, because
     * a policy may lawfully outlive a build's declarations — where a STORED rail's lookup
     * ({@link #capabilitiesOf}) stays loud, since rows on an undeclared rail mean a
     * deployment regressed under live money.
     */
    public java.util.Optional<PaymentRail> declared(RailId rail) {
        Objects.requireNonNull(rail, "rail must not be null");
        return java.util.Optional.ofNullable(byId.get(rail));
    }

    /** Every declared rail's id — what the operator surface validates names against. */
    public java.util.Set<RailId> declaredIds() {
        return byId.keySet();
    }

    /** The declared capabilities of the rail a stored row names. */
    public RailCapabilities capabilitiesOf(RailId rail) {
        Objects.requireNonNull(rail, "rail must not be null");
        PaymentRail declared = byId.get(rail);
        if (declared == null) {
            throw new IllegalStateException(
                    "no rail declares '" + rail.value() + "': a stored rail this build does not"
                            + " declare is a wiring fault, never a domain refusal (ADR-0059)");
        }
        return declared.capabilities();
    }
}
