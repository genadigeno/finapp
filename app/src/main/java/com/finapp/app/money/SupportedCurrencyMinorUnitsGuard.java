package com.finapp.app.money;

import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the running JDK disagrees with the pinned minor units (`P9-TSK-002`,
 * ADR-0074 §9).
 *
 * <h2>The failure this exists to stop</h2>
 *
 * <p>{@code CurrencyCode.minorUnits()} is the JDK's ISO 4217 data, and that data changes between
 * JDK versions. Every stored amount keeps the scale it was created with ({@code INV-MON-05}), so an
 * instance on a JDK that moved a currency's minor units would create new amounts that disagree
 * with every amount already on the books - and the first sum across that boundary throws
 * {@code ScaleMismatchException}, on live history, at an arbitrary moment after the deploy that
 * caused it. The JDK upgrade is an ordinary operational act; nothing about it says "currency".
 *
 * <h2>Why a startup guard as well as a test</h2>
 *
 * <p>{@code SupportedCurrencyMinorUnitsArePinnedTest} catches the drift on the build's JDK. The
 * image that runs may be built on another. So the same comparison runs here, against the JDK the
 * instance actually started on, and a disagreement stops the instance before it serves a request.
 *
 * <p>It deliberately does not "fix" anything: adopting the JDK's new value would reinterpret
 * history, and keeping the pin would make new amounts disagree with the JDK the rest of the code
 * reads. Either is a decision for a person, and a migration.
 */
@Component
public class SupportedCurrencyMinorUnitsGuard {

    public SupportedCurrencyMinorUnitsGuard() {
        verify(CurrencyCode::minorUnits);
    }

    /**
     * Compares every pinned currency's minor units with {@code running}'s answer.
     *
     * @param running the minor units as the running platform reports them - in production the
     *     JDK through {@code CurrencyCode::minorUnits}; a test plants a drift
     * @throws IllegalStateException naming every currency that disagrees
     */
    public static void verify(ToIntFunction<CurrencyCode> running) {
        List<String> disagreements = new ArrayList<>();
        for (Map.Entry<CurrencyCode, Integer> pin : SupportedCurrencies.PINNED_MINOR_UNITS.entrySet()) {
            int actual = running.applyAsInt(pin.getKey());
            if (actual != pin.getValue()) {
                disagreements.add(
                        pin.getKey() + " is pinned to " + pin.getValue() + " minor units, the running"
                                + " JDK says " + actual);
            }
        }
        if (!disagreements.isEmpty()) {
            throw new IllegalStateException(
                    "The running JDK's ISO 4217 data disagrees with the pinned minor units, and"
                            + " stored amounts keep the scale they were created with (INV-MON-05):"
                            + " starting would make new amounts disagree with history. "
                            + String.join("; ", disagreements));
        }
    }
}
