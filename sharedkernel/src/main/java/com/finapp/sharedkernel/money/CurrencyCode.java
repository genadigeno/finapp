package com.finapp.sharedkernel.money;

import java.io.Serializable;
import java.util.Currency;
import java.util.Objects;

/**
 * An ISO 4217 currency, as a value.
 *
 * <p>Exists so that a currency can never be a bare {@code String} that some code forgot to
 * check. {@code INV-MON-02} requires every monetary value to carry an explicit currency;
 * making the currency a type is what stops "USD" and "usd" and {@code null} all being valid
 * arguments to the same parameter.
 *
 * <p><strong>Only real currencies.</strong> ISO 4217 also defines codes that are not
 * currencies in the sense this platform needs — {@code XXX} ("no currency"), {@code XAU}
 * (gold), {@code XDR} (Special Drawing Rights). The JDK reports {@code -1} minor units for
 * these, meaning "no minor unit is defined", and an amount denominated in one of them cannot
 * be given exact integer minor units. They are rejected rather than silently treated as
 * having zero decimal places, which would make one gram of gold and one thousandth of a gram
 * the same value.
 *
 * <p><strong>Case is not normalised.</strong> {@code "usd"} is rejected rather than
 * uppercased. Silent coercion of input is the habit this platform avoids everywhere else,
 * and there is no reading of "usd" that is more correct than telling the caller.
 *
 * <p>The minor-unit count comes from the JDK's ISO 4217 data, which changes between JDK
 * versions. That is exactly why {@link Money} stores the scale it was constructed with
 * rather than looking it up on demand — see ADR-0003.
 *
 * @param code the three-letter uppercase ISO 4217 alphabetic code
 */
public record CurrencyCode(String code) implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final int ISO_4217_CODE_LENGTH = 3;

    public CurrencyCode {
        Objects.requireNonNull(code, "currency code must not be null");
        if (code.length() != ISO_4217_CODE_LENGTH || !isUppercaseLetters(code)) {
            throw new IllegalArgumentException(
                    "Currency code must be three uppercase letters (ISO 4217), but was: '" + code + "'");
        }
        Currency currency = lookup(code);
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "Currency '"
                            + code
                            + "' defines no minor unit, so an exact monetary amount cannot be "
                            + "expressed in it. Codes such as XXX, XAU and XDR are not usable as "
                            + "a currency for monetary amounts.");
        }
    }

    /** Factory mirroring the canonical constructor, for readability at call sites. */
    public static CurrencyCode of(String code) {
        return new CurrencyCode(code);
    }

    /**
     * The number of decimal places this currency is denominated in — 2 for USD, 0 for JPY,
     * 3 for BHD, 4 for CLF.
     *
     * <p>This is the platform's current view. A monetary amount that has already been created
     * keeps the scale it was created with ({@link Money#scale()}), so a change to this data in
     * a future JDK cannot retroactively reinterpret a stored amount.
     */
    public int minorUnits() {
        return lookup(code).getDefaultFractionDigits();
    }

    @Override
    public String toString() {
        return code;
    }

    private static Currency lookup(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a known ISO 4217 currency code: '" + code + "'", e);
        }
    }

    private static boolean isUppercaseLetters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }
}
