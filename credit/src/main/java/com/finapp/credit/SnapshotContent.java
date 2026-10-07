package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything a decision may read, as frozen (`P10-TSK-008`; PHASE_10_PLAN.md section 12.2, {@code INV-CRD-07}): the
 * request - its decision request, party, product, amount and term - the pinned versions, and every attribute with its
 * provenance, one per code, held sorted by code so no insertion order can change what the canonical form says.
 *
 * <p>{@link #attribute} throws for a code the content does not hold: a read of a missing attribute is an evaluation
 * error, never a default.
 */
public record SnapshotContent(
        UUID decisionRequest,
        UUID party,
        CreditProduct product,
        Money requestedAmount,
        Optional<Integer> termMonths,
        PinnedVersions versions,
        List<CreditAttribute> attributes) {

    public SnapshotContent {
        Objects.requireNonNull(decisionRequest, "decisionRequest");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(requestedAmount, "requestedAmount");
        Objects.requireNonNull(termMonths, "termMonths");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(attributes, "attributes");
        if (!requestedAmount.currency().equals(product.currency())) {
            throw new IllegalArgumentException("the requested amount is in the product's currency");
        }
        Map<CreditAttributeCode, CreditAttribute> byCode = new EnumMap<>(CreditAttributeCode.class);
        for (CreditAttribute attribute : attributes) {
            if (byCode.put(attribute.code(), attribute) != null) {
                throw new IllegalArgumentException("one attribute per code: " + attribute.code());
            }
            if (attribute.value() instanceof AttributeValue.MoneyValue money
                    && !money.value().currency().equals(product.currency())) {
                throw new IllegalArgumentException("every money attribute is in the product's currency (INV-CRD-12)");
            }
        }
        attributes = attributes.stream().sorted(Comparator.comparing(attribute -> attribute.code().name())).toList();
    }

    /** The attribute for {@code code} - absent or present, but held; a code the content does not hold is an error. */
    public CreditAttribute attribute(CreditAttributeCode code) {
        Objects.requireNonNull(code, "code");
        return attributes.stream()
                .filter(attribute -> attribute.code() == code)
                .findFirst()
                .orElseThrow(() -> new MissingAttributeException(code));
    }

    /** No attribute value renders. */
    @Override
    public String toString() {
        return "SnapshotContent[" + decisionRequest + ", " + product + ", " + attributes.size() + " attributes]";
    }
}
