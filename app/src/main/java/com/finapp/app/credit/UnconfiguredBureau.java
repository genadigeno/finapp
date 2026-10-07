package com.finapp.app.credit;

import com.finapp.credit.BureauAnswer;
import com.finapp.credit.BureauRequest;
import com.finapp.credit.CreditBureau;
import java.util.Objects;
import java.util.Optional;

/**
 * The bureau when none is configured (`P10-TSK-006`): every pull is {@code Unavailable(PROVIDER_ERROR)} - fail safe,
 * the counterparty-screening precedent: nothing is ever data, so a missing bureau can approve nothing
 * ({@code INV-CRD-10}), and the deadline and the policy's fallback do the rest.
 */
public final class UnconfiguredBureau implements CreditBureau {

    /** The code its data requests and meters carry. */
    public static final String CODE = "bureau-none";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public BureauAnswer pull(BureauRequest request) {
        Objects.requireNonNull(request, "request");
        return new BureauAnswer.Unavailable(BureauAnswer.UnavailableCause.PROVIDER_ERROR, Optional.empty());
    }

    @Override
    public String toString() {
        return "UnconfiguredBureau[" + CODE + "]";
    }
}
