package com.finapp.app.credit;

import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditDataSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A source kind's provider order, read from configuration (`P10-TSK-021`; ADR-0085 section 10):
 * {@code finapp.credit.<kind>.providers} - the provider codes in the order a birth tries them - and
 * {@code finapp.credit.<kind>.disabled} - the codes a birth skips.
 *
 * <p><strong>Fail-safe first.</strong> No real provider can be named yet: a bureau pull needs the party's date of birth
 * and residence (unresolved question #13) and a financial-data pull an account connection (#14), neither of which the
 * platform holds. So any provider named is refused at startup - loudly, never silently ignored - and every kind's order
 * is empty, selecting its fail-safe ({@link UnconfiguredBureau}, {@link UnconfiguredFinancialData}): every pull
 * {@code Unavailable}, nothing ever data. The selection itself is the credit module's, proven against the simulators.
 *
 * <p>Pure: the same configuration selects the same provider on every instance.
 */
final class CreditSourceOrder {

    private static final String CODE_SHAPE = "[a-z][a-z0-9-]{0,31}";

    private CreditSourceOrder() {}

    /**
     * The kind's configuration: its order from {@code providers} and {@code disabled}, falling to {@code failSafe}.
     *
     * @param property the kind's property prefix, for the refusal's message ({@code finapp.credit.bureau})
     * @param refusal why no provider can be named yet - the unresolved question that blocks it
     * @throws IllegalStateException for a provider named, a malformed or repeated code, or a disabled code naming no
     *     provider
     */
    static CreditDataCollection.Configured configured(String property, String providers, String disabled,
            CreditDataSource failSafe, CreditDataCollection.Timing timing, String refusal) {
        Objects.requireNonNull(failSafe, "failSafe");
        List<String> order = codes(property + ".providers", providers);
        Set<String> skipped = new LinkedHashSet<>(codes(property + ".disabled", disabled));
        if (!order.containsAll(skipped)) {
            throw new IllegalStateException(property + ".disabled names a provider " + property
                    + ".providers does not - a disabled code must name a configured provider");
        }
        if (!order.isEmpty()) {
            throw new IllegalStateException(property + ".providers names " + String.join(",", order) + ", but " + refusal);
        }
        return new CreditDataCollection.Configured(List.of(), Set.of(), Optional.of(failSafe), timing);
    }

    /** The comma-separated codes of {@code value}, in order - each well formed, none repeated. */
    static List<String> codes(String property, String value) {
        Objects.requireNonNull(value, "value");
        List<String> codes = new ArrayList<>();
        if (value.isBlank()) {
            return codes;
        }
        for (String part : value.split(",", -1)) {
            String code = part.strip();
            if (!code.matches(CODE_SHAPE)) {
                throw new IllegalStateException(property + " holds a malformed provider code");
            }
            if (codes.contains(code)) {
                throw new IllegalStateException(property + " names " + code + " twice");
            }
            codes.add(code);
        }
        return codes;
    }
}
