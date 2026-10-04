package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Plain-JDBC pricing policy storage (`P9-TSK-007`, ADR-0033). The machine, the four-eyes rule,
 * the partial uniques and the frozen content are {@code fx V004}'s, for every writer; this class
 * maps a unique violation on insert to {@link PricingPolicyAdministration.ProposalPending}.
 */
@RequiredArgsConstructor
public final class JdbcPricingPolicyStore implements PricingPolicyStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String VERSION_COLUMNS =
            "id, version, status, open_quote_cap, proposed_by, proposed_at, decided_by";

    @NonNull private final IdGenerator ids;

    @Override
    public int maxVersion(Connection unitOfWork) {
        try (PreparedStatement select =
                        unitOfWork.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM fx.pricing_policy_version");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getInt(1);
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("reading the latest policy version", failure), failure);
        }
    }

    @Override
    public void insertProposal(
            Connection unitOfWork,
            PricingPolicyId id,
            int version,
            PricingPolicyProposal proposal,
            String proposedBy,
            Instant at) {
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO fx.pricing_policy_version (id, version, status, open_quote_cap,"
                                    + " proposed_by, proposed_at, proposal_reason)"
                                    + " VALUES (?, ?, 'PROPOSED', ?, ?, ?, ?)")) {
                insert.setObject(1, id.value());
                insert.setInt(2, version);
                insert.setInt(3, proposal.openQuoteCap());
                insert.setString(4, proposedBy);
                insert.setTimestamp(5, Timestamp.from(at));
                insert.setString(6, proposal.reason());
                insert.executeUpdate();
            }
            try (PreparedStatement pair =
                    unitOfWork.prepareStatement(
                            "INSERT INTO fx.pricing_pair (policy_id, source_currency, destination_currency,"
                                    + " purpose, providers, spread, markup, rate_scale, rate_rounding,"
                                    + " amount_rounding, margin_rounding, window_seconds,"
                                    + " cover_margin_seconds, band, reference_max_age_seconds,"
                                    + " source_min_minor, source_max_minor, destination_min_minor,"
                                    + " destination_max_minor)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (PolicyPair entry : proposal.pairs()) {
                    PricingPair p = entry.pricing();
                    Array providers = unitOfWork.createArrayOf("text", entry.providers().toArray());
                    pair.setObject(1, id.value());
                    pair.setString(2, p.source().code());
                    pair.setString(3, p.destination().code());
                    pair.setString(4, entry.purpose().name());
                    pair.setArray(5, providers);
                    pair.setBigDecimal(6, p.spread().value());
                    pair.setBigDecimal(7, p.markup().value());
                    pair.setInt(8, p.rateScale());
                    pair.setString(9, p.rateRounding().name());
                    pair.setString(10, p.amountRounding().name());
                    pair.setString(11, p.marginRounding().name());
                    pair.setLong(12, entry.window().toSeconds());
                    pair.setLong(13, entry.coverMargin().toSeconds());
                    pair.setBigDecimal(14, entry.band());
                    pair.setLong(15, entry.referenceMaxAge().toSeconds());
                    pair.setLong(16, p.sourceBounds().minimum().minorUnits());
                    pair.setLong(17, p.sourceBounds().maximum().minorUnits());
                    pair.setLong(18, p.destinationBounds().minimum().minorUnits());
                    pair.setLong(19, p.destinationBounds().maximum().minorUnits());
                    pair.addBatch();
                }
                pair.executeBatch();
            }
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())
                    || (failure.getNextException() != null
                            && UNIQUE_VIOLATION.equals(failure.getNextException().getSQLState()))) {
                throw new PricingPolicyAdministration.ProposalPending(failure);
            }
            throw new FxStorageException(DatabaseFailure.describe("proposing a pricing policy", failure), failure);
        }
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, PricingPolicyId id) {
        return one(unitOfWork, "SELECT " + VERSION_COLUMNS + " FROM fx.pricing_policy_version WHERE id = ? FOR UPDATE",
                id.value());
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork) {
        return one(unitOfWork,
                "SELECT " + VERSION_COLUMNS + " FROM fx.pricing_policy_version WHERE status = 'ACTIVE' FOR UPDATE",
                null);
    }

    @Override
    public boolean decide(
            Connection unitOfWork,
            PricingPolicyId id,
            PricingPolicyStatus from,
            PricingPolicyStatus to,
            String decidedBy,
            String reason,
            Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE fx.pricing_policy_version SET status = ?, decided_by = ?, decided_at = ?,"
                                + " decision_reason = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setString(2, decidedBy);
            update.setTimestamp(3, Timestamp.from(at));
            update.setString(4, reason);
            update.setObject(5, id.value());
            update.setString(6, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("deciding a pricing policy", failure), failure);
        }
    }

    @Override
    public boolean retire(Connection unitOfWork, PricingPolicyId id, Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE fx.pricing_policy_version SET status = 'RETIRED', retired_at = ?"
                                + " WHERE id = ? AND status = 'ACTIVE'")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("retiring a pricing policy", failure), failure);
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            PricingPolicyId id,
            Optional<PricingPolicyStatus> from,
            PricingPolicyStatus to,
            String actorId,
            String reason,
            Instant at) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.pricing_policy_event (id, policy_id, from_status, to_status,"
                                + " actor_id, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, ids.next());
            insert.setObject(2, id.value());
            insert.setString(3, from.map(Enum::name).orElse(null));
            insert.setString(4, to.name());
            insert.setString(5, actorId);
            insert.setString(6, reason);
            insert.setTimestamp(7, Timestamp.from(at));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("recording a pricing policy event", failure), failure);
        }
    }

    @Override
    public List<VersionView> versions(Connection unitOfWork, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("a version list is 1..100 long");
        }
        List<VersionView> views = new ArrayList<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + VERSION_COLUMNS + " FROM fx.pricing_policy_version ORDER BY version DESC LIMIT ?")) {
            select.setInt(1, limit);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    VersionRow version = rehydrate(row);
                    views.add(new VersionView(version, row.getTimestamp("proposed_at").toInstant(), List.of()));
                }
            }
            List<VersionView> withPairs = new ArrayList<>();
            for (VersionView view : views) {
                withPairs.add(new VersionView(view.row(), view.proposedAt(), pairsOf(unitOfWork, view.row().id())));
            }
            return withPairs;
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("listing pricing policies", failure), failure);
        }
    }

    private List<PolicyPair> pairsOf(Connection unitOfWork, PricingPolicyId id) throws SQLException {
        List<PolicyPair> pairs = new ArrayList<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT * FROM fx.pricing_pair WHERE policy_id = ?"
                                + " ORDER BY purpose, source_currency, destination_currency")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
                    CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
                    String[] providers = (String[]) row.getArray("providers").getArray();
                    pairs.add(
                            new PolicyPair(
                                    PricingPurpose.valueOf(row.getString("purpose")),
                                    List.of(providers),
                                    new PricingPair(
                                            source,
                                            destination,
                                            new Margin(row.getBigDecimal("spread").stripTrailingZeros()),
                                            new Margin(row.getBigDecimal("markup").stripTrailingZeros()),
                                            row.getInt("rate_scale"),
                                            RoundingPolicy.valueOf(row.getString("rate_rounding")),
                                            RoundingPolicy.valueOf(row.getString("amount_rounding")),
                                            RoundingPolicy.valueOf(row.getString("margin_rounding")),
                                            new NotionalBounds(
                                                    Money.ofMinorUnits(row.getLong("source_min_minor"), source),
                                                    Money.ofMinorUnits(row.getLong("source_max_minor"), source)),
                                            new NotionalBounds(
                                                    Money.ofMinorUnits(row.getLong("destination_min_minor"), destination),
                                                    Money.ofMinorUnits(row.getLong("destination_max_minor"), destination))),
                                    Duration.ofSeconds(row.getLong("window_seconds")),
                                    Duration.ofSeconds(row.getLong("cover_margin_seconds")),
                                    row.getBigDecimal("band").stripTrailingZeros(),
                                    Duration.ofSeconds(row.getLong("reference_max_age_seconds"))));
                }
            }
        }
        return pairs;
    }

    private Optional<VersionRow> one(Connection unitOfWork, String sql, UUID id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            if (id != null) {
                select.setObject(1, id);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("locking a pricing policy", failure), failure);
        }
    }

    private static VersionRow rehydrate(ResultSet row) throws SQLException {
        return new VersionRow(
                PricingPolicyId.of(row.getObject("id", UUID.class)),
                row.getInt("version"),
                PricingPolicyStatus.valueOf(row.getString("status")),
                row.getInt("open_quote_cap"),
                row.getString("proposed_by"),
                Optional.ofNullable(row.getString("decided_by")));
    }
}
