package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplCapital;
import com.finapp.bnpl.BnplHeadroom;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * bnpl's {@link BnplCapital} over lending's {@link LendingCapitalHeadroom} (`P12-TSK-005`; ADR-0104 decision 3, the
 * crossborder and lending port precedent): {@link LendingCapitalHeadroom#underLock} on bnpl's own connection - the
 * currency's {@code LENDING_CAPITAL} row {@code FOR UPDATE} (L6) taken in bnpl's transaction and held to its commit, then
 * the headroom derived from journal lines, BNPL principal counted by ledger purpose - and bnpl given the one figure it
 * judges by. Never {@code atRest}: an unlocked reading decides nothing. A failed read propagates and fails bnpl's
 * transaction. {@code LendingBnplCapitalTest} holds the adapter to the lock and to lending's figure.
 */
@RequiredArgsConstructor
final class LendingBnplCapital implements BnplCapital {

    @NonNull private final LendingCapitalHeadroom headroom;

    @Override
    public BnplHeadroom underLock(Connection unitOfWork, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        LendingCapitalHeadroom.Headroom judged = headroom.underLock(unitOfWork, currency);
        return new BnplHeadroom(judged.currency(), judged.headroom());
    }
}
