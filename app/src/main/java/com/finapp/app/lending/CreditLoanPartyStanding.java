package com.finapp.app.lending;

import com.finapp.credit.CreditPartyStanding;
import com.finapp.lending.LoanPartyStanding;
import java.sql.Connection;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Lending's {@link LoanPartyStanding} over credit's {@link CreditPartyStanding} (`P11-TSK-010`): one standing rule for
 * the platform's credit - a verified customer, {@code ACTIVE}, read authoritatively on the caller's connection - so
 * lending refuses exactly whom credit refuses, and never from a cache.
 */
@RequiredArgsConstructor
public final class CreditLoanPartyStanding implements LoanPartyStanding {

    @NonNull private final CreditPartyStanding<Connection> standing;

    @Override
    public boolean inGoodStanding(Connection unitOfWork, UUID party) {
        return standing.inGoodStanding(unitOfWork, party);
    }
}
