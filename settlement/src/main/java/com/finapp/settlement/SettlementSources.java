package com.finapp.settlement;

import com.finapp.ledger.AccountPurpose;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The source register: every settlement source this build declares (`P8-TSK-002`, ADR-0064).
 *
 * <p>The {@code PaymentRails} shape for the same reason: compiled data, identical on every
 * instance, so ten instances cannot disagree about who reports what. {@code INV-SET-05}'s
 * static rank lives in construction — one source per code, and <strong>at most one source per
 * settled position</strong>, because a position two sources could discharge is a position no
 * break can be attributed against. The composition root builds it from each counterparty's own
 * declaration and verifies coverage there ({@code SettlementBeans}): a settling rail without a
 * source fails composition and the build, never a payment.
 */
public final class SettlementSources {

    private final Map<String, SettlementSourceDescriptor> byCode;

    /** Each report source's remittance-reference shape, compiled once (`P8-TSK-016`). */
    private final Map<String, Pattern> remittancePatterns;

    private SettlementSources(Map<String, SettlementSourceDescriptor> byCode) {
        this.byCode = byCode;
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        byCode.values()
                .forEach(
                        source ->
                                source.remittanceReferencePattern()
                                        .ifPresent(
                                                pattern ->
                                                        compiled.put(
                                                                source.code(),
                                                                Pattern.compile(pattern))));
        this.remittancePatterns = Map.copyOf(compiled);
    }

    /** Builds the register, refusing a duplicate code or a twice-discharged position. */
    public static SettlementSources of(Collection<SettlementSourceDescriptor> sources) {
        Objects.requireNonNull(sources, "sources must not be null");
        Map<String, SettlementSourceDescriptor> byCode = new LinkedHashMap<>();
        Map<AccountPurpose, String> byPosition = new LinkedHashMap<>();
        for (SettlementSourceDescriptor source : sources) {
            Objects.requireNonNull(source, "a declared source must not be null");
            if (byCode.putIfAbsent(source.code(), source) != null) {
                throw new IllegalArgumentException(
                        "two sources declare the code '" + source.code()
                                + "': a source's code is its identity");
            }
            source.settledPosition()
                    .ifPresent(
                            position -> {
                                String first = byPosition.putIfAbsent(position, source.code());
                                if (first != null) {
                                    throw new IllegalArgumentException(
                                            "sources '" + first + "' and '" + source.code()
                                                    + "' both declare the position " + position
                                                    + ": exactly one declared source discharges"
                                                    + " each externally settling position"
                                                    + " (INV-SET-05)");
                                }
                            });
        }
        return new SettlementSources(Map.copyOf(byCode));
    }

    /** The declared source a code resolves to, or empty when this build declares none. */
    public Optional<SettlementSourceDescriptor> byCode(String code) {
        Objects.requireNonNull(code, "code must not be null");
        return Optional.ofNullable(byCode.get(code));
    }

    /** Every declared source, in declaration order. */
    public Collection<SettlementSourceDescriptor> declared() {
        return byCode.values();
    }

    /**
     * The one declared source whose remittance-reference pattern FULLY matches {@code reference}
     * (`P8-TSK-016`, ADR-0065 §3, {@code INV-SET-05}) — attribution is normalisation, not
     * matching: deterministic, compiled, identical on every instance. Zero matches or two are
     * both empty, never the first of two: an ambiguous line is unexplained value, parked owned
     * at acceptance, never guessed into one counterparty's position.
     */
    public Optional<SettlementSourceDescriptor> attribute(String reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        List<String> matching =
                remittancePatterns.entrySet().stream()
                        .filter(entry -> entry.getValue().matcher(reference).matches())
                        .map(Map.Entry::getKey)
                        .toList();
        return matching.size() == 1
                ? Optional.of(byCode.get(matching.get(0)))
                : Optional.empty();
    }

    /** The one declared source discharging {@code position}, or empty. */
    public Optional<SettlementSourceDescriptor> dischargedBy(AccountPurpose position) {
        Objects.requireNonNull(position, "position must not be null");
        return byCode.values().stream()
                .filter(source -> source.settledPosition().equals(Optional.of(position)))
                .findFirst();
    }
}
