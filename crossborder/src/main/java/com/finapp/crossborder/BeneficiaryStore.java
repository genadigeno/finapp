package com.finapp.crossborder;

import com.finapp.crossborder.BeneficiaryVocabulary.EntityType;
import com.finapp.crossborder.BeneficiaryVocabulary.PayeeCheck;
import com.finapp.crossborder.BeneficiaryVocabulary.StatusCause;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where beneficiaries, their registrations and their corridor selections rest (`P9-TSK-017`,
 * {@code crossborder V003}). Every write runs in the caller's unit of work; every contended write is a
 * conditional the database arbitrates - the registration on its exchange reference, the beneficiary on
 * its registration, a move on the expected status under the row lock.
 */
public interface BeneficiaryStore {

    /** One registration: who, which grant (by derived reference only), which selection and rail. */
    record RegistrationRow(
            UUID id,
            UUID owner,
            String exchangeReference,
            UUID selectionId,
            String rail,
            CountryCode country,
            CurrencyCode currency,
            EntityType entityType,
            Instant createdAt) {
        public RegistrationRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(exchangeReference, "exchangeReference must not be null");
            Objects.requireNonNull(selectionId, "selectionId must not be null");
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(entityType, "entityType must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    /** One beneficiary as stored; the provider's reference is redacted in {@code toString}. */
    record BeneficiaryRow(
            BeneficiaryId id,
            UUID owner,
            UUID registrationId,
            String rail,
            String destinationReference,
            String suffix,
            PayeeCheck payeeCheck,
            boolean acknowledgedNoMatch,
            CountryCode country,
            CurrencyCode currency,
            EntityType entityType,
            String nickname,
            BeneficiaryStatus status,
            UUID screeningId,
            Instant registeredAt,
            Optional<Instant> revokedAt) {
        public BeneficiaryRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(registrationId, "registrationId must not be null");
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(destinationReference, "destinationReference must not be null");
            Objects.requireNonNull(suffix, "suffix must not be null");
            Objects.requireNonNull(payeeCheck, "payeeCheck must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(entityType, "entityType must not be null");
            Objects.requireNonNull(nickname, "nickname must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(screeningId, "screeningId must not be null");
            Objects.requireNonNull(registeredAt, "registeredAt must not be null");
            Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        }

        @Override
        public String toString() {
            return "BeneficiaryRow[id=" + id + ", status=" + status + ", country=" + country + ", currency=" + currency
                    + ", destination=<redacted>]";
        }
    }

    /** A stored selection, for recomputation. */
    record SelectionRow(
            UUID id, CorridorPolicyId policy, CorridorSelection.Inputs inputs, Set<String> availableCorridors,
            List<CorridorSelection.Step> steps) {}

    Optional<RegistrationRow> registrationByReference(Connection unitOfWork, String exchangeReference);

    /** The registration, after taking its transaction-scoped lock (advisory namespace 9) - Tx2 serialises on it. */
    Optional<RegistrationRow> lockRegistration(Connection unitOfWork, UUID registrationId);

    /** Inserts the selection and every step. */
    void insertSelection(
            Connection unitOfWork, UUID selectionId, CorridorPolicyId policy, CorridorSelection.Selection selection,
            Instant at);

    Optional<SelectionRow> selection(Connection unitOfWork, UUID selectionId);

    /** Inserts {@code registration}; false when its exchange reference is already registered. */
    boolean insertRegistration(Connection unitOfWork, RegistrationRow registration);

    Optional<BeneficiaryRow> beneficiaryOfRegistration(Connection unitOfWork, UUID registrationId);

    void insertBeneficiary(Connection unitOfWork, BeneficiaryRow beneficiary);

    /** Appends one edge of the machine's history. */
    void appendEvent(
            Connection unitOfWork,
            UUID eventId,
            BeneficiaryId beneficiary,
            Optional<BeneficiaryStatus> from,
            BeneficiaryStatus to,
            StatusCause cause,
            Optional<UUID> screening,
            Instant at);

    /** {@code owner}'s beneficiary {@code id}, if it is theirs. */
    Optional<BeneficiaryRow> findOwned(Connection unitOfWork, BeneficiaryId id, UUID owner);

    /** {@code owner}'s beneficiary {@code id} under {@code FOR UPDATE}, if it is theirs. */
    Optional<BeneficiaryRow> lockOwned(Connection unitOfWork, BeneficiaryId id, UUID owner);

    /** {@code owner}'s beneficiaries, newest first, at most {@code limit}. */
    List<BeneficiaryRow> listOwned(Connection unitOfWork, UUID owner, int limit);

    /** The beneficiary {@code id} under {@code FOR UPDATE} - the screening listener's lock (`P9-TSK-018`). */
    Optional<BeneficiaryRow> lockById(Connection unitOfWork, BeneficiaryId id);

    /** {@code owner}'s beneficiary {@code id} under {@code FOR SHARE} - the quote's payability read (`P9-TSK-018`). */
    Optional<BeneficiaryRow> lockOwnedForShare(Connection unitOfWork, BeneficiaryId id, UUID owner);

    /** Points the beneficiary at {@code screening}, its current clearance (`P9-TSK-018`, a re-screen decided). */
    void pointScreening(Connection unitOfWork, BeneficiaryId id, UUID screening);

    /** Moves {@code id} from {@code from} to {@code to}; false when it was not there. */
    boolean move(Connection unitOfWork, BeneficiaryId id, BeneficiaryStatus from, BeneficiaryStatus to, Optional<Instant> revokedAt);
}
