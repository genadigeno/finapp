package com.finapp.app.crossborder;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CrossBorderInstruction;
import com.finapp.crossborder.OfferStore;
import com.finapp.fx.ConversionParticipants;
import com.finapp.fx.FxQuoteId;
import com.finapp.fx.QuoteStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A cross-border movement's facts as a judgement needs them (`P13-TSK-011`; {@code PHASE_13_PLAN.md} sections 7.2, 7.3,
 * 12.1): whose movement, which quote, along which corridor, to which beneficiary, the quote's two figures, and the source
 * wallet's <strong>customer account id</strong> (the wallet product the money leaves - the ledger's {@code owner_ref},
 * never a {@code LedgerAccountId}, `-006`). Read from crossborder's frozen offer and accounts' wallet - identifiers and
 * amounts, never a name - for the seam's T-eval ({@link #locked}) and the door's step-up advice ({@link #owned}).
 */
public interface CrossBorderMovements {

    /** One movement, by reference. Its {@code toString} names nothing: the subjects and the amounts are the caller's. */
    record Movement(UUID party, UUID quote, CorridorKey corridor, UUID beneficiary, Money source, Money destination,
            Optional<UUID> sourceAccount) {
        public Movement {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(quote, "quote");
            Objects.requireNonNull(corridor, "corridor");
            Objects.requireNonNull(beneficiary, "beneficiary");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(sourceAccount, "sourceAccount");
            if (!source.currency().equals(corridor.source()) || !destination.currency().equals(corridor.destination())) {
                throw new IllegalArgumentException("a movement's figures are in its corridor's two currencies");
            }
        }

        @Override
        public String toString() {
            return "CrossBorderMovements.Movement";
        }
    }

    /**
     * The seam's facts, on the authorization's own {@code unitOfWork} - <strong>after taking the money rows the
     * authorization will take, in the Phase 9 lock order</strong>: the quote {@code FOR UPDATE} (position (3), "quote
     * before any wallet"), then the source wallet's ledger-account row {@code FOR UPDATE} (position (4)) - so that T-eval's
     * risk rows, (R2a) held to commit, come after every money row the transaction takes (the Phase 13 order's first
     * shape). The acceptance and the hold later re-lock rows already held: granted, no new lock, no order change.
     */
    Movement locked(Connection unitOfWork, CrossBorderInstruction instruction);

    /**
     * The door's facts, read with no lock on the door's own {@code unitOfWork}: empty when the quote's offer is not
     * {@code party}'s. The source account is empty when the party holds no wallet in the corridor's source currency.
     */
    Optional<Movement> owned(Connection unitOfWork, UUID party, UUID quote);

    /**
     * The production resolution, over crossborder's frozen offer, fx's quote row and participants, and the accounts'
     * stores.
     *
     * <ul>
     *   <li><strong>The wallet</strong> is the one fx's acceptance debits: {@link ConversionParticipants#activeCustomer}
     *       then {@link ConversionParticipants#wallet} in the corridor's source currency - the same port, the same calls,
     *       so the row locked here is the row {@code HoldService.place} locks after the acceptance. No wallet (no active
     *       customer, no agreement, no wallet in the currency) is no source account and no lock: the acceptance refuses
     *       it after the seams ({@code SOURCE_WALLET_MISSING}), rolling the judgement back with it.</li>
     *   <li><strong>The quote</strong> is locked owned by the party; absent, nothing is locked (the acceptance refuses
     *       it). The offer is crossborder's frozen row - one per quote, append-only by grant - read plainly.</li>
     *   <li><strong>The door's source account</strong> is the live WALLET product's id - the wallet ledger account's
     *       {@code owner_ref} by construction ({@code PartyConversionParticipants.wallet}); the seam's is read from the
     *       locked row itself.</li>
     * </ul>
     */
    static CrossBorderMovements over(OfferStore offers, QuoteStore quotes, ConversionParticipants participants,
            LedgerAccountStore<Connection> ledgerAccounts, CustomerAccountStore<Connection> products) {
        Objects.requireNonNull(offers, "offers");
        Objects.requireNonNull(quotes, "quotes");
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(ledgerAccounts, "ledgerAccounts");
        Objects.requireNonNull(products, "products");
        return new CrossBorderMovements() {
            @Override
            public Movement locked(Connection unitOfWork, CrossBorderInstruction instruction) {
                Objects.requireNonNull(unitOfWork, "unitOfWork");
                Objects.requireNonNull(instruction, "instruction");
                UUID party = instruction.customerId();
                // (3) the quote, then (4) the wallet - Phase 9's order - and only then risk's rows.
                quotes.lockOwned(unitOfWork, FxQuoteId.of(instruction.quoteId()), party);
                Optional<UUID> sourceAccount = participants.activeCustomer(unitOfWork, party)
                        .flatMap(customer -> participants.wallet(unitOfWork, customer, instruction.corridor().source()))
                        .flatMap(wallet -> ledgerAccounts.lockForUpdate(unitOfWork, wallet))
                        .flatMap(LedgerAccount::ownerRef);
                OfferStore.OfferRow offer = offers.offerOwned(unitOfWork, instruction.quoteId(), party)
                        .orElseThrow(() -> new IllegalStateException("the offer the authorization read in this"
                                + " transaction is gone or not its owner's - an invariant is broken"));
                return movement(offer, sourceAccount);
            }

            @Override
            public Optional<Movement> owned(Connection unitOfWork, UUID party, UUID quote) {
                Objects.requireNonNull(unitOfWork, "unitOfWork");
                Objects.requireNonNull(party, "party");
                Objects.requireNonNull(quote, "quote");
                return offers.offerOwned(unitOfWork, quote, party).map(offer -> movement(offer,
                        participants.activeCustomer(unitOfWork, party)
                                .filter(customer -> participants.wallet(unitOfWork, customer,
                                        offer.corridor().source()).isPresent())
                                .flatMap(customer -> products.findLive(unitOfWork, customer, ProductType.WALLET))
                                .map(CustomerAccount::id)
                                .map(id -> id.value())));
            }
        };
    }

    private static Movement movement(OfferStore.OfferRow offer, Optional<UUID> sourceAccount) {
        return new Movement(offer.owner(), offer.quoteId(), offer.corridor(), offer.beneficiary().value(), offer.source(),
                offer.destination(), sourceAccount);
    }
}
