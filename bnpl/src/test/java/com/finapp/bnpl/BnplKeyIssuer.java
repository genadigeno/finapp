package com.finapp.bnpl;

import com.finapp.platform.security.Actor;
import java.util.Optional;
import java.util.UUID;

/**
 * The operator who issued the suites' merchant keys (the Phase 12 -> 13 gate's A5-F1): a person of a party of their own -
 * never a customer's - so a merchant refund or a dispute's acceptance through the worlds' keys passes the issuer rule
 * ({@code BnplRefunds.refuseIssuersOwn}), which fails closed on a key whose issuer is absent or cannot be placed in a party.
 * The worlds' acting-party ports answer {@link #partyOf} for it; a suite proving the rule names another issuer.
 */
final class BnplKeyIssuer {

    /** The issuing operator's actor id, as merchant {@code V003}'s {@code issued_by} holds one. */
    static final String ID = "operator-who-issued-the-key";

    /** Their party - nobody's customer in any world. */
    static final UUID PARTY = UUID.fromString("0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b");

    /** The request's {@code keyIssuer}. */
    static final Optional<String> KEY_ISSUER = Optional.of(ID);

    private BnplKeyIssuer() {}

    /** An acting-party port's answer for the issuer alone - empty for every other actor. */
    static Optional<UUID> partyOf(Actor actor) {
        return ID.equals(actor.id()) ? Optional.of(PARTY) : Optional.empty();
    }
}
