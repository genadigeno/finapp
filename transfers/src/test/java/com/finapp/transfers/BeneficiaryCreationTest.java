package com.finapp.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BeneficiaryCreation}: the destination judged through the resolution port before
 * anything is written (`P4-TSK-006`'s scope sentence). Hermetic with fakes — the real
 * {@code JdbcTransferParticipants} is already proven against a database by the execution
 * suite, and the wiring of this command to it arrives with the surface (`P4-TSK-007`).
 */
@DisplayName("beneficiary creation (P4-TSK-006)")
class BeneficiaryCreationTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final RecordingStore store = new RecordingStore();

    @Test
    @DisplayName("an unknown destination is refused with nothing written")
    void anUnknownDestinationIsRefusedWithNothingWritten() {
        BeneficiaryCreation<Object> creation =
                new BeneficiaryCreation<>(participants(Optional.empty()), store, IDS, CLOCK);

        assertThatThrownBy(
                        () ->
                                creation.createOrConverge(
                                        new Object(), UUID.randomUUID(), "Aunt Vera",
                                        UUID.randomUUID()))
                .isInstanceOf(UnknownBeneficiaryDestinationException.class);
        assertThat(store.saved)
                .as("a boundary mistake commits no fact: the store must never be asked")
                .isEmpty();
    }

    @Test
    @DisplayName("a known destination is saved ACTIVE for the party — postability not judged")
    void aKnownDestinationIsSavedActiveForTheParty() {
        // postable = false, deliberately: existence is this decision's question and postability
        // is each transfer's (the accepted race, plan section 7) - a product suspended today
        // may post tomorrow, and re-judging it here would only go stale.
        BeneficiaryCreation<Object> creation =
                new BeneficiaryCreation<>(
                        participants(Optional.of(side(false))), store, IDS, CLOCK);
        UUID party = UUID.randomUUID();
        UUID destination = UUID.randomUUID();

        BeneficiaryStore.Creation result =
                creation.createOrConverge(new Object(), party, "Aunt Vera", destination);

        assertThat(result.created()).isTrue();
        assertThat(store.saved).hasSize(1);
        Beneficiary saved = store.saved.getFirst();
        assertThat(saved.status()).isEqualTo(BeneficiaryStatus.ACTIVE);
        assertThat(saved.partyId()).isEqualTo(party);
        assertThat(saved.destinationAccountId()).isEqualTo(destination);
        assertThat(saved.displayName()).isEqualTo("Aunt Vera");
    }

    // -----------------------------------------------------------------

    private static TransferParticipants.Side side(boolean postable) {
        return new TransferParticipants.Side(
                LedgerAccountId.of(IDS.next()), CurrencyCode.of("USD"), postable);
    }

    private static TransferParticipants<Object> participants(
            Optional<TransferParticipants.Side> destination) {
        return new TransferParticipants<>() {
            @Override
            public Optional<Source> sourceOwnedBy(
                    Object unitOfWork, UUID callerPartyId, UUID sourceProductRef) {
                throw new UnsupportedOperationException("not this command's question");
            }

            @Override
            public Optional<Side> destination(Object unitOfWork, UUID destinationProductRef) {
                return destination;
            }
        };
    }

    /** Records what was saved; converge behaviour is the database test's subject. */
    private static final class RecordingStore implements BeneficiaryStore<Object> {
        private final List<Beneficiary> saved = new ArrayList<>();

        @Override
        public Creation createOrConverge(Object unitOfWork, Beneficiary fresh) {
            saved.add(fresh);
            return new Creation(fresh, true);
        }

        @Override
        public Optional<Beneficiary> findLive(
                Object unitOfWork, UUID partyId, UUID destinationAccountId) {
            return Optional.empty();
        }

        @Override
        public Optional<Beneficiary> findOwned(
                Object unitOfWork, BeneficiaryId beneficiary, UUID partyId) {
            throw new UnsupportedOperationException("not this command's question");
        }

        @Override
        public java.util.List<Beneficiary> listLiveFor(Object unitOfWork, UUID partyId) {
            throw new UnsupportedOperationException("not this command's question");
        }

        @Override
        public boolean remove(
                Object unitOfWork, BeneficiaryId beneficiary, UUID partyId, Instant at) {
            throw new UnsupportedOperationException("not this command's question");
        }
    }
}
