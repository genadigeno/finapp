package com.finapp.fx;

import com.finapp.ledger.SupportedCurrencies;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A whole pricing policy version as proposed (`P9-TSK-007`, ADR-0075 §7): every (pair, purpose)
 * it prices, the open-quote cap, and the proposer's reason. A proposal restates the policy in full
 * - never a delta - so every version is self-describing and every quote's pinned version answers
 * for its price alone ({@code INV-HIST-04}).
 */
public record PricingPolicyProposal(List<PolicyPair> pairs, int openQuoteCap, String reason) {

    public PricingPolicyProposal {
        pairs = List.copyOf(pairs);
        Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * Judges the proposal before anything is stored.
     *
     * @throws PricingPolicyAdministration.PricingPolicyInvalid for an empty policy, a duplicated
     *     (pair, purpose), a currency that is not postable, an out-of-range cap, or a malformed
     *     reason
     * @throws PricingPolicyAdministration.ProviderNotDeclared for a provider the running build
     *     does not declare
     */
    public void validate(Set<String> declaredProviders) {
        Objects.requireNonNull(declaredProviders, "declaredProviders must not be null");
        FxReasons.refuse(reason, PricingPolicyAdministration.PricingPolicyInvalid::new);
        if (pairs.isEmpty()) {
            throw new PricingPolicyAdministration.PricingPolicyInvalid("a policy prices at least one pair");
        }
        if (openQuoteCap < 1 || openQuoteCap > 100) {
            throw new PricingPolicyAdministration.PricingPolicyInvalid("the open-quote cap is 1..100");
        }
        Set<String> seen = new HashSet<>();
        for (PolicyPair pair : pairs) {
            String key = pair.pricing().source().code() + "-" + pair.pricing().destination().code()
                    + "/" + pair.purpose();
            if (!seen.add(key)) {
                throw new PricingPolicyAdministration.PricingPolicyInvalid(
                        "a policy prices each (pair, purpose) once: " + key + " is repeated");
            }
            if (!SupportedCurrencies.ALL.contains(pair.pricing().source())
                    || !SupportedCurrencies.ALL.contains(pair.pricing().destination())) {
                throw new PricingPolicyAdministration.PricingPolicyInvalid(
                        "a policy prices only postable currencies: " + key);
            }
            for (String provider : pair.providers()) {
                if (!declaredProviders.contains(provider)) {
                    throw new PricingPolicyAdministration.ProviderNotDeclared(provider);
                }
            }
        }
    }
}
