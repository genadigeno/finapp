package com.finapp.app.settlement;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The simulated settlement bank's statement, rendered as `SIM_STATEMENT_TAGGED` v1
 * (`P8-TSK-016`) — what the bank did on the platform's settlement account in one currency: the
 * chain's sequence, the opening balance it continues from, one {@code :61:} record per movement
 * (a credit or debit carrying the counterparty's structured remittance reference, or the bank's
 * own fee), and the closing balance computed the way the bank computes it — opening + credits −
 * debits − fees — so a render always adds up (`P8-TST-001`, extracted from
 * {@code BankStatementCashDatabaseTest}'s helper, which keeps its own copy).
 *
 * <p>Balances are SIGNED minor units: a credit balance renders {@code C}, a debit balance
 * {@code D}. The chain's own faults are stated as the statements themselves: a gap is a
 * statement whose sequence skips one, a non-zero opening is a first statement opening anywhere
 * but zero. The account record is the simulated bank's opaque reference for the currency
 * ({@code SIMBANK-<CCY>-01}), the one the composition configures by default. Integer arithmetic
 * only — no floating point near money. Amounts render at the statement currency's own minor
 * units (`P9-TSK-003`: JPY {@code 12120}, BHD {@code 24.960}; EUR, GBP and USD unchanged).
 */
public final class SimulatedBankStatements {

    private final String reference;
    private final String currency;
    private final long sequence;
    private final LocalDate openingDate;
    private final long openingMinor;
    private final List<String> records = new ArrayList<>();
    private final int scale;
    private long movementMinor;

    public SimulatedBankStatements(
            String reference,
            String currency,
            long sequence,
            LocalDate openingDate,
            long openingMinor) {
        this.reference = Objects.requireNonNull(reference);
        this.currency = Objects.requireNonNull(currency);
        this.openingDate = Objects.requireNonNull(openingDate);
        if (sequence < 1) {
            throw new IllegalArgumentException("a statement sequence is 1-based");
        }
        this.sequence = sequence;
        this.openingMinor = openingMinor;
        this.scale = CurrencyCode.of(currency).minorUnits();
    }

    /** Money in, attributed by its remittance reference when it carries one. */
    public SimulatedBankStatements credit(
            LocalDate valueDate, long minor, Optional<String> remittanceRef) {
        movementMinor += positive(minor);
        records.add(line(valueDate, "C", minor, remittanceRef));
        return this;
    }

    /** Money out, attributed by its remittance reference when it carries one. */
    public SimulatedBankStatements debit(
            LocalDate valueDate, long minor, Optional<String> remittanceRef) {
        movementMinor -= positive(minor);
        records.add(line(valueDate, "D", minor, remittanceRef));
        return this;
    }

    /** The bank's own charge — no counterparty's remittance answers to it. */
    public SimulatedBankStatements fee(LocalDate valueDate, long minor) {
        movementMinor -= positive(minor);
        records.add(line(valueDate, "F", minor, Optional.empty()));
        return this;
    }

    /** A {@code :86:} narrative for the movement just added - free text the door screens. */
    public SimulatedBankStatements narrative(String text) {
        if (records.isEmpty()) {
            throw new IllegalStateException("a narrative follows a movement");
        }
        records.add(":86:" + Objects.requireNonNull(text));
        return this;
    }

    /** The closing balance the render will declare: opening plus every movement, signed. */
    public long closingMinor() {
        return openingMinor + movementMinor;
    }

    /** The statement's bytes: UTF-8, LF, the closing balance the bank's own arithmetic gives. */
    public byte[] render(LocalDate closingDate) {
        StringBuilder text =
                new StringBuilder()
                        .append(":20:").append(reference).append('\n')
                        .append(":25:SIMBANK-").append(currency).append("-01\n")
                        .append(":28C:").append(sequence).append('\n')
                        .append(":60F:").append(balance(openingDate, openingMinor)).append('\n');
        for (String record : records) {
            text.append(record).append('\n');
        }
        text.append(":62F:").append(balance(closingDate, closingMinor())).append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String balance(LocalDate date, long signedMinor) {
        return (signedMinor < 0 ? "D" : "C") + "," + date + "," + currency + ","
                + decimal(Math.abs(signedMinor));
    }

    private String line(
            LocalDate valueDate, String mark, long minor, Optional<String> remittanceRef) {
        return ":61:" + valueDate + "," + mark + "," + decimal(minor)
                + remittanceRef.map(ref -> "," + ref).orElse("");
    }

    private static long positive(long minor) {
        if (minor <= 0) {
            throw new IllegalArgumentException("a statement movement is positive");
        }
        return minor;
    }

    /** Minor units as the format writes them, at the currency's own scale - exact. */
    private String decimal(long minor) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }
}
