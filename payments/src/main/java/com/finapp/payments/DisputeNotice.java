package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * One dispute notification, already translated into the platform's vocabulary by the door that
 * received it (`P7-TSK-012`, ADR-0061 §6, {@code INV-PAY-03}): the PSP's stage word and reason
 * code have been mapped through the door's total tables, and the raw bytes rest as evidence
 * (`INV-HIST-02`). Nothing here is provider vocabulary except the provider's own name and its
 * opaque dispute reference — the pair {@code UNIQUE} in `V020`.
 *
 * @param provider the PSP's stable adapter name — the uniqueness scope of its references
 * @param reference the PSP's identifier for the dispute, stable across its stages
 * @param stage the stage the network says the dispute has reached
 * @param reason the reason category the network's code maps to
 * @param amount the amount the network states — the transaction it asks about at an inquiry,
 *     what it took from the chargeback on (only the latter is recorded: {@link
 *     Dispute#chargeback()})
 */
public record DisputeNotice(
        String provider,
        ProviderReference reference,
        DisputeStage stage,
        DisputeReason reason,
        Money amount) {

    public DisputeNotice {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Dispute.requireProvider(provider);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a disputed amount must be positive");
        }
    }

    /** Never the amount, never the reference: a record's generated form would print both
     * (INV-AUD-02). */
    @Override
    public String toString() {
        return "DisputeNotice[" + provider + ", " + stage + ", " + reason + "]";
    }
}
