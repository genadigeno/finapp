package com.finapp.platform.money;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The one place that knows how a {@link Money} becomes three database columns, and back.
 *
 * <p>ADR-0003 fixes the storage shape:
 *
 * <pre>
 *   &lt;name&gt;_amount_minor  BIGINT    NOT NULL
 *   &lt;name&gt;_currency      CHAR(3)   NOT NULL
 *   &lt;name&gt;_scale         SMALLINT  NOT NULL
 * </pre>
 *
 * <p><strong>Why three columns and not one.</strong> A single {@code NUMERIC} would drop the
 * currency, and a {@code NUMERIC(19,2)} would bake one currency's precision into the schema —
 * wrong for JPY at 0 decimals and for BHD at 3. Integer minor units keep arithmetic exact
 * ({@code INV-MON-01}) and let the same column pair hold any currency.
 *
 * <p><strong>Why scale is stored rather than derived.</strong> Minor-unit counts are data
 * that changes. A row storing only {@code 1234} and {@code USD} would silently change meaning
 * if that data ever changed — 12.34 becoming 1.234. Storing the scale the amount was written
 * with makes a historical row interpretable as originally written ({@code INV-MON-05}), and
 * is the whole reason {@link Money#ofPersisted} exists.
 *
 * <p><strong>What this class is not.</strong> It is not a JPA {@code @Embeddable} and does
 * not depend on any persistence framework. The platform has not chosen a data-access
 * mechanism — see {@code CURRENT_STATE.md} §Unresolved Architectural Questions — and
 * committing to one here, in the task that maps money, would be deciding it by accident. A
 * JPA embeddable, a Spring Data JDBC converter or a hand-written row mapper can all be built
 * on this class when that decision is made; none of them needs a different column shape.
 *
 * <p>Reading is deliberately strict. A row that cannot be turned back into the exact amount
 * that was written is a corrupt financial record, and the only safe response is to refuse it
 * rather than return an approximation.
 */
public final class MoneyColumns {

    /** Suffix for the integer minor units column. */
    public static final String AMOUNT_MINOR_SUFFIX = "_amount_minor";

    /** Suffix for the ISO 4217 currency column. */
    public static final String CURRENCY_SUFFIX = "_currency";

    /** Suffix for the stored scale column. */
    public static final String SCALE_SUFFIX = "_scale";

    private MoneyColumns() {
        // Static holder for a persistence convention; not instantiable.
    }

    /**
     * The three column names for a monetary field.
     *
     * <p>Prefixing by field name is what lets one table hold several amounts —
     * {@code gross_amount_minor} beside {@code fee_amount_minor} — without either becoming
     * "the" amount by convention.
     *
     * @param fieldName the logical name of the monetary field, for example {@code "gross"}
     */
    public static ColumnNames columnsFor(String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        return new ColumnNames(
                fieldName + AMOUNT_MINOR_SUFFIX,
                fieldName + CURRENCY_SUFFIX,
                fieldName + SCALE_SUFFIX);
    }

    /** The values to write, in the order the columns are declared. */
    public static long amountMinorOf(Money money) {
        return required(money).minorUnits();
    }

    /** @return the ISO 4217 code, exactly three characters, for a {@code CHAR(3)} column */
    public static String currencyOf(Money money) {
        return required(money).currency().code();
    }

    /**
     * @return the scale as a {@code short}, matching {@code SMALLINT}. Narrowing is safe and
     *     checked: {@link Money} bounds scale well inside a {@code short}, and a value that
     *     did not fit would mean the amount was already corrupt.
     */
    public static short scaleOf(Money money) {
        int scale = required(money).scale();
        if (scale < 0 || scale > Short.MAX_VALUE) {
            // Defence in depth: Money already bounds scale well inside a short, so this is
            // unreachable today. It exists so that widening Money's bound past SMALLINT
            // fails loudly here rather than silently narrowing on the way to the database.
            throw new MonetaryColumnException(
                    "Scale " + scale + " does not fit a SMALLINT column; the amount is corrupt");
        }
        return (short) scale;
    }

    /**
     * Rebuilds an amount from its three stored columns, with the scale it was written under.
     *
     * <p>Uses {@link Money#ofPersisted} rather than a currency lookup, so a row written when
     * a currency had a different minor-unit count still reads back as the amount it was.
     *
     * @throws MonetaryColumnException if the stored values do not form a valid amount
     */
    public static Money read(long amountMinor, String currency, short scale) {
        if (currency == null) {
            throw new MonetaryColumnException(
                    "Currency column is null; a stored amount with no currency cannot be "
                            + "interpreted (INV-MON-02)");
        }
        // CHAR(3) is blank-padded by PostgreSQL, and some drivers surface that padding.
        // Stripping it is not the silent coercion CurrencyCode forbids: it removes padding
        // the column type added, not a difference the caller supplied. Trailing only —
        // CHAR never pads on the left, so a leading space is corruption, not padding.
        String code = currency.stripTrailing();
        try {
            return Money.ofPersisted(amountMinor, CurrencyCode.of(code), scale);
        } catch (RuntimeException e) {
            throw new MonetaryColumnException(
                    "Stored amount is not a valid monetary value: amount_minor="
                            + amountMinor
                            + ", currency='"
                            + currency
                            + "', scale="
                            + scale,
                    e);
        }
    }

    private static Money required(Money money) {
        return Objects.requireNonNull(money, "money must not be null");
    }

    /**
     * The three column names for one monetary field.
     *
     * @param amountMinor the {@code BIGINT} column
     * @param currency the {@code CHAR(3)} column
     * @param scale the {@code SMALLINT} column
     */
    public record ColumnNames(String amountMinor, String currency, String scale) {

        /**
         * The DDL fragment declaring these three columns, so a migration cannot declare a
         * monetary field with the wrong types or forget a {@code NOT NULL}.
         *
         * <p>Every column is {@code NOT NULL}: a partially-populated monetary value — an
         * amount with no currency, or a currency with no scale — is not a smaller amount of
         * information, it is an uninterpretable one.
         */
        public String ddl() {
            return amountMinor
                    + " BIGINT NOT NULL, "
                    + currency
                    + " CHAR(3) NOT NULL, "
                    + scale
                    + " SMALLINT NOT NULL, "
                    // CHAR(3) is not a guarantee: PostgreSQL pads rather than rejects, so it
                    // accepts 'US ' as happily as 'USD'. Without this check the only thing
                    // standing between a malformed code and a balance is the application
                    // remembering to validate on read. INV-MON-02 is enforceable in the
                    // schema, so DEFINITION_OF_DONE §1.3 says it belongs there.
                    + "CHECK (" + currency + " ~ '^[A-Z]{3}$'), "
                    // The same bound Money enforces, generated from the same constant so the
                    // two cannot drift.
                    + "CHECK (" + scale + " BETWEEN 0 AND " + Money.MAX_SUPPORTED_SCALE + ")";
        }
    }
}
