package com.finapp.app.crossborder;

import com.finapp.crossborder.BeneficiaryVocabulary;
import com.finapp.crossborder.CorridorDirectory;
import com.finapp.payments.CorridorRail;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.RailId;
import com.finapp.payments.RailOperations;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * crossborder's {@link CorridorDirectory} over payments' {@link RailOperations} (`P9-TSK-015`,
 * `P9-TSK-017`): the declared corridor rails with their coverage, which are operable here (a configured
 * adapter), and the beneficiary grant exchange - payments' answer mapped onto crossborder's words, so
 * neither module names the other's types. Every answer the adapter cannot read as an exchange or a
 * refusal is {@code Unavailable}: nothing is registered on it.
 */
final class RailDirectory implements CorridorDirectory {

    private final Set<DeclaredRail> declared;
    private final RailOperations rails;

    RailDirectory(Set<DeclaredRail> declared, RailOperations rails) {
        this.declared = Set.copyOf(declared);
        this.rails = Objects.requireNonNull(rails, "rails must not be null");
    }

    @Override
    public Set<DeclaredRail> declaredRails() {
        return declared;
    }

    @Override
    public boolean operable(String rail) {
        return adapter(rail).isPresent();
    }

    @Override
    public Exchange exchange(String rail, String reference, String grant) {
        Optional<CorridorRail> adapter = adapter(rail);
        if (adapter.isEmpty()) {
            return new Exchange.Unavailable();
        }
        CorridorRail.BeneficiaryGrant presented;
        try {
            presented = new CorridorRail.BeneficiaryGrant(new EndToEndReference(reference), grant);
        } catch (IllegalArgumentException malformed) {
            // A grant the rail's own shape refuses is a grant the provider would refuse: nothing is sent.
            return new Exchange.Refused();
        }
        return switch (adapter.get().exchangeBeneficiary(presented)) {
            case CorridorRail.BeneficiaryExchange.Exchanged exchanged -> new Exchange.Exchanged(
                    exchanged.destination().value(),
                    exchanged.suffix(),
                    BeneficiaryVocabulary.PayeeCheck.valueOf(exchanged.payeeCheck().name()),
                    exchanged.country(),
                    exchanged.currency(),
                    BeneficiaryVocabulary.EntityType.valueOf(exchanged.entityType().name()));
            case CorridorRail.BeneficiaryExchange.Refused refused -> new Exchange.Refused();
            case CorridorRail.BeneficiaryExchange.NothingSent nothing -> new Exchange.Unavailable();
            case CorridorRail.BeneficiaryExchange.Indeterminate unknown -> new Exchange.Unavailable();
        };
    }

    private Optional<CorridorRail> adapter(String rail) {
        try {
            return rails.corridorRail(RailId.of(rail));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
