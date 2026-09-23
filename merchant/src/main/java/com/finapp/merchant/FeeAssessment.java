package com.finapp.merchant;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * What one capture is priced at, and under which version ({@code INV-MER-03},
 * {@code INV-MER-04}, {@code INV-HIST-04}).
 *
 * <p><strong>{@code fee + net == gross}, always, by construction</strong> — the net is derived
 * by subtraction inside {@link FeeCalculation}, never computed and rounded independently. This
 * record's constructor re-asserts it anyway: the class that guarantees the property and the
 * class that carries it are different classes, and an assessment that reached here without the
 * property is a defect worth refusing at the boundary rather than posting.
 *
 * <p><strong>{@code version} is the whole of {@code INV-HIST-04}'s obligation.</strong> It
 * names an immutable row, so this assessment is recomputable for as long as the record exists.
 *
 * @param gross what the customer paid
 * @param fee what the platform earns, recognised at capture (ADR-0050 §2)
 * @param net what the merchant is owed — {@code gross - fee}
 * @param version the fee schedule version that produced this, pinned
 */
public record FeeAssessment(Money gross, Money fee, Money net, FeeScheduleVersionId version) {

    public FeeAssessment {
        Objects.requireNonNull(gross, "gross must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        Objects.requireNonNull(net, "net must not be null");
        Objects.requireNonNull(version, "version must not be null");
        // INV-MER-04, restated at the boundary. plus() already refuses a currency or scale
        // mismatch, so this compares like with like or throws saying why.
        if (!fee.plus(net).equals(gross)) {
            throw new IllegalArgumentException(
                    "a fee split must conserve the capture exactly: "
                            + fee
                            + " + "
                            + net
                            + " != "
                            + gross
                            + " (INV-MER-04)");
        }
    }

    /**
     * Whether the fee is larger than what arrived, leaving the merchant owing rather than
     * owed.
     *
     * <p>Reachable whenever the fixed part exceeds a small capture — 0.30 on 0.10. The
     * arithmetic does <strong>not</strong> clamp, and that is deliberate: a clamp would make
     * the recorded fee differ from what the pinned version produces on recomputation, breaking
     * {@code INV-MER-03} for the sake of a number that looks nicer. The real remedy is a
     * minimum capture amount, which belongs to the checkout session's validation rather than
     * to arithmetic — see {@code CURRENT_STATE.md} §Known Architectural Debt.
     *
     * <p>Exposed so a consumer can refuse, warn or meter rather than discover it in a
     * statement.
     */
    public boolean exceedsGross() {
        return net.isNegative();
    }
}
