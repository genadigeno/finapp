package com.finapp.credit;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * A real decision request row for a suite that needs one to hang a data request or a snapshot on (`P10-TSK-014`):
 * since `credit V010` both reference the request, so a bare minted id no longer stands in for one. Born through the
 * store - the trigger stamps its windows - for a fresh or the given party; the party's open request for the product when
 * one exists (one open per party and product).
 */
final class DecisionRequestRows {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final JdbcDecisionRequestStore STORE = new JdbcDecisionRequestStore();

    private DecisionRequestRows() {}

    /** {@code party}'s open request for {@code product} - submitted on {@code unitOfWork} when none is open. */
    static UUID submitted(Connection unitOfWork, UUID party, CreditProduct product) {
        Optional<DecisionRequestId> open = STORE.openFor(unitOfWork, party, product);
        if (open.isPresent()) {
            return open.get().value();
        }
        CreditProfile profile = new JdbcCreditProfiles(IDS).ensure(unitOfWork, party);
        DecisionRequestId id = DecisionRequestId.next(IDS);
        DecisionRequest.Application application = new DecisionRequest.Application(product,
                Money.ofMinorUnits(product == CreditProduct.PERSONAL_LOAN ? 1_000_000 : 200_000, product.currency()),
                product.revolving() ? Optional.empty() : Optional.of(36), Optional.empty(), Optional.empty());
        if (!STORE.insert(unitOfWork, id, party, profile.id(), application, product.requestValidity(), "suite-fixture",
                new Actor(party.toString(), ActorType.CUSTOMER))) {
            throw new IllegalStateException("a fixture request lost its slot to a concurrent one");
        }
        return id.value();
    }
}
