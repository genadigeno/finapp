package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;

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
 * @param fee the dispute fee the PSP charged the platform (`P7-TSK-013`, ADR-0061 §4) — only on
 *     a statement from the chargeback on (a fee is charged with the funds taken, never on an
 *     inquiry), in the amount's currency and scale
 */
public record DisputeNotice(
        String provider,
        ProviderReference reference,
        DisputeStage stage,
        DisputeReason reason,
        Money amount,
        Optional<Money> fee) {

    public DisputeNotice {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        Dispute.requireProvider(provider);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a disputed amount must be positive");
        }
        if (fee.isPresent()) {
            if (!stage.isChargedBack()) {
                throw new IllegalArgumentException(
                        "a dispute fee is charged with the chargeback, never on an inquiry");
            }
            if (!fee.get().isPositive()
                    || !fee.get().currency().equals(amount.currency())
                    || fee.get().scale() != amount.scale()) {
                throw new IllegalArgumentException(
                        "a dispute fee is positive and in the statement's currency and scale");
            }
        }
    }

    /** A statement carrying no fee — the `P7-TSK-012` shape. */
    public DisputeNotice(
            String provider,
            ProviderReference reference,
            DisputeStage stage,
            DisputeReason reason,
            Money amount) {
        this(provider, reference, stage, reason, amount, Optional.empty());
    }

    /** Never an amount, never the reference: a record's generated form would print both
     * (INV-AUD-02). */
    @Override
    public String toString() {
        return "DisputeNotice[" + provider + ", " + stage + ", " + reason + "]";
    }
}
