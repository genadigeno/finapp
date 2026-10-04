package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The counterparty chart's completeness rule (`P9-TSK-010`, ADR-0078 section 4): every declared
 * counterparty has its registry row, of its declared kind, and one seeded account per declared
 * currency. The app's {@code CounterpartyChartGuard} runs it at startup, so a missing seed is
 * caught at deploy, never at the first posting; {@link ChartOfAccounts} stays the per-call
 * backstop. Pure over two reads, so the refusal is testable with planted gaps.
 */
public final class CounterpartyChart {

    private CounterpartyChart() {}

    /** The registry and account reads the rule needs. */
    public interface Readings {

        /** The registered counterparty with this code, if any. */
        Optional<Counterparty> counterparty(String code);

        /** Whether the counterparty's account for this purpose and currency is seeded. */
        boolean hasAccount(AccountPurpose purpose, String code, CurrencyCode currency);
    }

    /**
     * Every gap between the declarations and the chart, in declaration order - empty when the
     * chart is complete.
     */
    public static List<String> gaps(Collection<CounterpartyClearing> declared, Readings readings) {
        Objects.requireNonNull(declared, "declared must not be null");
        Objects.requireNonNull(readings, "readings must not be null");
        List<String> gaps = new ArrayList<>();
        for (CounterpartyClearing clearing : declared) {
            Optional<Counterparty> registered = readings.counterparty(clearing.code());
            if (registered.isEmpty()) {
                gaps.add("counterparty '" + clearing.code() + "' is declared but has no registry row");
                continue;
            }
            if (registered.get().kind() != clearing.kind()) {
                gaps.add("counterparty '" + clearing.code() + "' is registered as "
                        + registered.get().kind() + " but declared as " + clearing.kind());
            }
            clearing.currencies().stream()
                    .sorted(java.util.Comparator.comparing(CurrencyCode::code))
                    .filter(currency -> !readings.hasAccount(clearing.purpose(), clearing.code(), currency))
                    .forEach(currency -> gaps.add("counterparty '" + clearing.code() + "' settles "
                            + currency + " on " + clearing.purpose() + " but has no seeded account"));
        }
        return List.copyOf(gaps);
    }

    /** Refuses, listing every gap at once, when the chart does not seed every declaration. */
    public static void verify(Collection<CounterpartyClearing> declared, Readings readings) {
        List<String> gaps = gaps(declared, readings);
        if (!gaps.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: the counterparty chart is incomplete - every declared"
                            + " counterparty is registered and seeded by the migration that admits it"
                            + " (ADR-0078 section 4): " + String.join("; ", gaps));
        }
    }
}
