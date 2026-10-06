package com.finapp.crossborder;

import com.finapp.crossborder.BeneficiaryVocabulary.EntityType;
import com.finapp.crossborder.BeneficiaryVocabulary.PayeeCheck;
import com.finapp.crossborder.BeneficiaryVocabulary.SelectionOutcome;
import com.finapp.crossborder.BeneficiaryVocabulary.StatusCause;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Plain-JDBC storage for beneficiaries, their registrations and corridor selections (ADR-0033,
 * `P9-TSK-017`). {@code crossborder V003}'s CHECKs, edge trigger and grants are the rank beneath.
 */
public final class JdbcBeneficiaryStore implements BeneficiaryStore {

    private static final String BENEFICIARY_COLUMNS =
            "id, owner_party, registration_id, rail, destination_reference, suffix, payee_check, acknowledged_no_match,"
                    + " destination_country, destination_currency, entity_type, nickname, status, screening_id,"
                    + " registered_at, revoked_at";

    private static final String REGISTRATION_COLUMNS =
            "id, owner_party, exchange_reference, selection_id, rail, destination_country, destination_currency,"
                    + " entity_type, created_at";

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(ResultSet row) throws SQLException;
    }

    // ------------------------------------------------------------------ registrations and selections

    @Override
    public Optional<RegistrationRow> registrationByReference(Connection unitOfWork, String exchangeReference) {
        Objects.requireNonNull(exchangeReference, "exchangeReference must not be null");
        return one(unitOfWork, "SELECT " + REGISTRATION_COLUMNS + " FROM crossborder.beneficiary_registration"
                        + " WHERE exchange_reference = ?",
                statement -> statement.setString(1, exchangeReference), JdbcBeneficiaryStore::registration,
                "reading a beneficiary registration");
    }

    /**
     * The registration's lock: advisory namespace 9 on the registration id, transaction-scoped and held to
     * commit - the registration is append-only by grant, so a row lock (which needs an UPDATE grant) is not
     * available; every completion of one registration serialises here before it reads.
     */
    static final int NAMESPACE = 9;

    @Override
    public Optional<RegistrationRow> lockRegistration(Connection unitOfWork, UUID registrationId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(registrationId, "registrationId must not be null");
        try (PreparedStatement lock = unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, NAMESPACE);
            lock.setString(2, registrationId.toString());
            lock.execute();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("locking beneficiary registration " + registrationId, failure), failure);
        }
        return one(unitOfWork, "SELECT " + REGISTRATION_COLUMNS + " FROM crossborder.beneficiary_registration WHERE id = ?",
                statement -> statement.setObject(1, registrationId), JdbcBeneficiaryStore::registration,
                "reading beneficiary registration " + registrationId);
    }

    @Override
    public void insertSelection(
            Connection unitOfWork, UUID selectionId, CorridorPolicyId policy, CorridorSelection.Selection selection,
            Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(selectionId, "selectionId must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(selection, "selection must not be null");
        Objects.requireNonNull(at, "at must not be null");
        String chosen = selection.chosen().orElseThrow(() -> new IllegalArgumentException("only a chosen selection is stored"));
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO crossborder.corridor_selection (id, policy_id, destination_country, destination_currency,"
                            + " entity_type, available_corridors, chosen_rail, selected_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, selectionId);
                insert.setObject(2, policy.value());
                insert.setString(3, selection.inputs().country().code());
                insert.setString(4, selection.inputs().currency().code());
                insert.setString(5, selection.inputs().entityType().name());
                insert.setArray(6, unitOfWork.createArrayOf("text", selection.availableCorridors().stream().sorted().toArray()));
                insert.setString(7, chosen);
                insert.setTimestamp(8, Timestamp.from(at));
                insert.executeUpdate();
            }
            try (PreparedStatement step = unitOfWork.prepareStatement(
                    "INSERT INTO crossborder.corridor_selection_step (selection_id, ordinal, corridor, rail, outcome)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                for (CorridorSelection.Step judged : selection.steps()) {
                    step.setObject(1, selectionId);
                    step.setInt(2, judged.ordinal());
                    step.setString(3, judged.corridor());
                    step.setString(4, judged.rail());
                    step.setString(5, judged.outcome().name());
                    step.addBatch();
                }
                step.executeBatch();
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording a corridor selection", failure), failure);
        }
    }

    @Override
    public Optional<SelectionRow> selection(Connection unitOfWork, UUID selectionId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(selectionId, "selectionId must not be null");
        try {
            CorridorPolicyId policy;
            CorridorSelection.Inputs inputs;
            Set<String> available;
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT policy_id, destination_country, destination_currency, entity_type, available_corridors"
                            + " FROM crossborder.corridor_selection WHERE id = ?")) {
                select.setObject(1, selectionId);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    policy = CorridorPolicyId.of(row.getObject("policy_id", UUID.class));
                    inputs = new CorridorSelection.Inputs(CountryCode.of(row.getString("destination_country")),
                            CurrencyCode.of(row.getString("destination_currency")),
                            EntityType.valueOf(row.getString("entity_type")));
                    Array corridors = row.getArray("available_corridors");
                    available = Set.copyOf(Arrays.asList((String[]) corridors.getArray()));
                }
            }
            List<CorridorSelection.Step> steps = new ArrayList<>();
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT ordinal, corridor, rail, outcome FROM crossborder.corridor_selection_step"
                            + " WHERE selection_id = ? ORDER BY ordinal")) {
                select.setObject(1, selectionId);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        steps.add(new CorridorSelection.Step(row.getInt("ordinal"), row.getString("corridor"),
                                row.getString("rail"), SelectionOutcome.valueOf(row.getString("outcome"))));
                    }
                }
            }
            return Optional.of(new SelectionRow(selectionId, policy, inputs, available, steps));
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("reading corridor selection " + selectionId, failure), failure);
        }
    }

    @Override
    public boolean insertRegistration(Connection unitOfWork, RegistrationRow registration) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(registration, "registration must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO crossborder.beneficiary_registration (" + REGISTRATION_COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (exchange_reference) DO NOTHING")) {
            insert.setObject(1, registration.id());
            insert.setObject(2, registration.owner());
            insert.setString(3, registration.exchangeReference());
            insert.setObject(4, registration.selectionId());
            insert.setString(5, registration.rail());
            insert.setString(6, registration.country().code());
            insert.setString(7, registration.currency().code());
            insert.setString(8, registration.entityType().name());
            insert.setTimestamp(9, Timestamp.from(registration.createdAt()));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording a beneficiary registration", failure), failure);
        }
    }

    // ------------------------------------------------------------------ beneficiaries

    @Override
    public Optional<BeneficiaryRow> beneficiaryOfRegistration(Connection unitOfWork, UUID registrationId) {
        Objects.requireNonNull(registrationId, "registrationId must not be null");
        return one(unitOfWork, "SELECT " + BENEFICIARY_COLUMNS + " FROM crossborder.beneficiary WHERE registration_id = ?",
                statement -> statement.setObject(1, registrationId), JdbcBeneficiaryStore::beneficiary,
                "reading the beneficiary of registration " + registrationId);
    }

    @Override
    public void insertBeneficiary(Connection unitOfWork, BeneficiaryRow beneficiary) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO crossborder.beneficiary (" + BENEFICIARY_COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, beneficiary.id().value());
            insert.setObject(2, beneficiary.owner());
            insert.setObject(3, beneficiary.registrationId());
            insert.setString(4, beneficiary.rail());
            insert.setString(5, beneficiary.destinationReference());
            insert.setString(6, beneficiary.suffix());
            insert.setString(7, beneficiary.payeeCheck().name());
            insert.setBoolean(8, beneficiary.acknowledgedNoMatch());
            insert.setString(9, beneficiary.country().code());
            insert.setString(10, beneficiary.currency().code());
            insert.setString(11, beneficiary.entityType().name());
            insert.setString(12, beneficiary.nickname());
            insert.setString(13, beneficiary.status().name());
            insert.setObject(14, beneficiary.screeningId());
            insert.setTimestamp(15, Timestamp.from(beneficiary.registeredAt()));
            insert.setTimestamp(16, beneficiary.revokedAt().map(Timestamp::from).orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording beneficiary " + beneficiary.id(), failure), failure);
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            UUID eventId,
            BeneficiaryId beneficiary,
            Optional<BeneficiaryStatus> from,
            BeneficiaryStatus to,
            StatusCause cause,
            Optional<UUID> screening,
            Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO crossborder.beneficiary_status_event (id, beneficiary_id, from_status, to_status, cause,"
                        + " screening_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, eventId);
            insert.setObject(2, beneficiary.value());
            insert.setString(3, from.map(Enum::name).orElse(null));
            insert.setString(4, to.name());
            insert.setString(5, cause.name());
            insert.setObject(6, screening.orElse(null));
            insert.setTimestamp(7, Timestamp.from(at));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording a move of beneficiary " + beneficiary, failure), failure);
        }
    }

    @Override
    public Optional<BeneficiaryRow> findOwned(Connection unitOfWork, BeneficiaryId id, UUID owner) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        return one(unitOfWork, "SELECT " + BENEFICIARY_COLUMNS + " FROM crossborder.beneficiary WHERE id = ? AND owner_party = ?",
                statement -> {
                    statement.setObject(1, id.value());
                    statement.setObject(2, owner);
                }, JdbcBeneficiaryStore::beneficiary, "reading beneficiary " + id);
    }

    @Override
    public Optional<BeneficiaryRow> lockOwned(Connection unitOfWork, BeneficiaryId id, UUID owner) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        return one(unitOfWork, "SELECT " + BENEFICIARY_COLUMNS + " FROM crossborder.beneficiary WHERE id = ? AND owner_party = ?"
                        + " FOR UPDATE",
                statement -> {
                    statement.setObject(1, id.value());
                    statement.setObject(2, owner);
                }, JdbcBeneficiaryStore::beneficiary, "locking beneficiary " + id);
    }

    @Override
    public List<BeneficiaryRow> listOwned(Connection unitOfWork, UUID owner, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + BENEFICIARY_COLUMNS + " FROM crossborder.beneficiary WHERE owner_party = ?"
                        + " ORDER BY registered_at DESC, id DESC LIMIT ?")) {
            select.setObject(1, owner);
            select.setInt(2, limit);
            List<BeneficiaryRow> rows = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(beneficiary(row));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("listing beneficiaries", failure), failure);
        }
    }

    @Override
    public Optional<BeneficiaryRow> lockById(Connection unitOfWork, BeneficiaryId id) {
        Objects.requireNonNull(id, "id must not be null");
        return one(unitOfWork, "SELECT " + BENEFICIARY_COLUMNS + " FROM crossborder.beneficiary WHERE id = ? FOR UPDATE",
                statement -> statement.setObject(1, id.value()), JdbcBeneficiaryStore::beneficiary, "locking beneficiary " + id);
    }

    @Override
    public Optional<BeneficiaryRow> lockOwnedForShare(Connection unitOfWork, BeneficiaryId id, UUID owner) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        return one(unitOfWork, "SELECT " + BENEFICIARY_COLUMNS + " FROM crossborder.beneficiary WHERE id = ? AND owner_party = ?"
                        + " FOR SHARE",
                statement -> {
                    statement.setObject(1, id.value());
                    statement.setObject(2, owner);
                }, JdbcBeneficiaryStore::beneficiary, "reading beneficiary " + id + " for share");
    }

    @Override
    public void pointScreening(Connection unitOfWork, BeneficiaryId id, UUID screening) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(screening, "screening must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE crossborder.beneficiary SET screening_id = ? WHERE id = ? AND status <> 'REVOKED'")) {
            update.setObject(1, screening);
            update.setObject(2, id.value());
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("repointing beneficiary " + id, failure), failure);
        }
    }

    @Override
    public boolean move(
            Connection unitOfWork, BeneficiaryId id, BeneficiaryStatus from, BeneficiaryStatus to, Optional<Instant> revokedAt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE crossborder.beneficiary SET status = ?, revoked_at = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setTimestamp(2, revokedAt.map(Timestamp::from).orElse(null));
            update.setObject(3, id.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("moving beneficiary " + id, failure), failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static <T> Optional<T> one(Connection unitOfWork, String sql, Binder binder, Reader<T> reader, String doing) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            binder.bind(select);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(reader.read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe(doing, failure), failure);
        }
    }

    private static RegistrationRow registration(ResultSet row) throws SQLException {
        return new RegistrationRow(
                row.getObject("id", UUID.class),
                row.getObject("owner_party", UUID.class),
                row.getString("exchange_reference"),
                row.getObject("selection_id", UUID.class),
                row.getString("rail"),
                CountryCode.of(row.getString("destination_country")),
                CurrencyCode.of(row.getString("destination_currency")),
                EntityType.valueOf(row.getString("entity_type")),
                row.getTimestamp("created_at").toInstant());
    }

    private static BeneficiaryRow beneficiary(ResultSet row) throws SQLException {
        Timestamp revokedAt = row.getTimestamp("revoked_at");
        return new BeneficiaryRow(
                BeneficiaryId.of(row.getObject("id", UUID.class)),
                row.getObject("owner_party", UUID.class),
                row.getObject("registration_id", UUID.class),
                row.getString("rail"),
                row.getString("destination_reference"),
                row.getString("suffix"),
                PayeeCheck.valueOf(row.getString("payee_check")),
                row.getBoolean("acknowledged_no_match"),
                CountryCode.of(row.getString("destination_country")),
                CurrencyCode.of(row.getString("destination_currency")),
                EntityType.valueOf(row.getString("entity_type")),
                row.getString("nickname"),
                BeneficiaryStatus.valueOf(row.getString("status")),
                row.getObject("screening_id", UUID.class),
                row.getTimestamp("registered_at").toInstant(),
                Optional.ofNullable(revokedAt).map(Timestamp::toInstant));
    }
}
