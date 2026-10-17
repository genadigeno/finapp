package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplPartyStanding;
import com.finapp.credit.CreditPartyStanding;
import java.sql.Connection;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * bnpl's {@link BnplPartyStanding} over credit's {@link CreditPartyStanding} (`P12-TSK-010`; lending's
 * {@code CreditLoanPartyStanding}): one standing rule for the platform's credit - a verified customer, {@code ACTIVE},
 * read authoritatively on the caller's connection - so bnpl refuses exactly whom credit refuses, and never from a cache.
 */
@RequiredArgsConstructor
final class CreditBnplPartyStanding implements BnplPartyStanding {

    @NonNull private final CreditPartyStanding<Connection> standing;

    @Override
    public boolean inGoodStanding(Connection unitOfWork, UUID party) {
        return standing.inGoodStanding(unitOfWork, party);
    }
}
