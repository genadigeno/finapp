package com.finapp.app.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The exposure census (`P11-TSK-013`; ADR-0091 point 9, PHASE_11_PLAN.md sections 12.10 and 13.1; {@code INV-CRD-09},
 * {@code INV-LND-06}): per party and currency, credit's reserved exposure plus lending's committed and outstanding
 * principal never exceeds the maximum exposure the deciding policy declared - recomputed from decision, consumption,
 * agreement and journal rows alone, order-free (the Phase 10 storm's census shape, extended to lending's figures).
 *
 * <h2>The rule, and why it is order-free</h2>
 *
 * <p>A party's <em>items</em> are its live approvals (approved, still valid on the database clock, unconsumed - credit's
 * reservation predicate) each with its amount, and its loan accounts each with its exposure figure
 * ({@link LendingCreditExposure}'s table) - both carrying the maximum exposure of the policy their decision was made
 * under. Every decision of a party is serialised by its profile lock (L0), and each judged its limit over the
 * reservations and commitments of those before it. So for every limit {@code m} among the party's items, the items
 * decided under a limit at most {@code m} must fit {@code m}: the last of them in the lock's order saw every other one -
 * reserved if still unconsumed then, committed if consumed - at a figure no lower than today's (a loan's principal only
 * falls after its acceptance; a line's limit is its approval). No serialisation order is needed, which is the point:
 * the decision rows hold none a census could trust ({@code decided_at} is not the lock's order).
 *
 * <p>The bureau's balance is the decision's external term and moves outside the platform; the census bounds the
 * platform's own terms against the limit, which every decision guaranteed with room to spare for the bureau's.
 *
 * <p><strong>What fails it</strong>: a sum above a limit; a loan account carrying exposure whose decision is not found
 * (an orphan the census will not guess a limit for). Lines drawn beyond their limit after a reversed paydown
 * (`-020`/`-023`) count their drawn principal here as in the exposure read - if that breaches a limit, it is a breach the
 * operator must see.
 *
 * <p>Two statements in the caller's ONE {@code REPEATABLE READ} snapshot (the caller opens it - {@link
 * ExposureCensusMetrics}): every row from one instant. Report-only and lock-free: nothing is written, so N instances
 * reading it can only agree.
 */
public final class ExposureCensus {

    /** Live approvals: credit's reservation predicate ({@code JdbcReservedExposure}), with their policy's limit. */
    static final String RESERVED = "SELECT d.party_id, d.currency, p.maximum_exposure_minor, d.approved_minor"
            + " FROM credit.credit_decision d JOIN credit.credit_policy_version p ON p.id = d.policy_version_id"
            + " WHERE d.outcome = 'APPROVED' AND d.valid_until > statement_timestamp()"
            + " AND NOT EXISTS (SELECT 1 FROM credit.credit_decision_consumption c WHERE c.decision_id = d.id)";

    /** Every loan account, the exposure read's columns, with its party, currency and its decision's policy limit. */
    static final String COMMITTED_AND_OUTSTANDING = "SELECT e.loan_id, e.kind, e.status, e.agreement_scale,"
            + " e.agreement_amount_minor, p.scale, p.net_minor, e.party_id, e.currency, pv.maximum_exposure_minor"
            + " FROM lending.party_principal_exposure e"
            + " LEFT JOIN credit.credit_decision d ON d.id = e.decision_id"
            + " LEFT JOIN credit.credit_policy_version pv ON pv.id = d.policy_version_id"
            + " LEFT JOIN LATERAL (SELECT j.scale,"
            + " sum(CASE j.direction WHEN 'DEBIT' THEN j.amount_minor ELSE -j.amount_minor END) AS net_minor"
            + " FROM ledger.ledger_account a JOIN ledger.journal_line j ON j.ledger_account_id = a.id"
            + " WHERE a.owner_kind = 'LOAN' AND a.owner_ref = e.loan_id AND a.currency = e.currency"
            + " AND j.currency = e.currency AND a.purpose IN (?, ?)"
            + " GROUP BY j.scale) p ON true"
            + " ORDER BY e.loan_id, p.scale";

    /** What a census found: how many parties hold and fail, and which fail (ids only - never a figure). */
    public record Report(int holding, int failing, Set<UUID> failingParties) {
        public Report {
            failingParties = Set.copyOf(failingParties);
            if (failing != failingParties.size()) {
                throw new IllegalArgumentException("every failing party is named");
            }
        }

        /** No figure, no party id renders. */
        @Override
        public String toString() {
            return "Report[holding=" + holding + ", failing=" + failing + "]";
        }
    }

    /** One item of a party's exposure: the limit its decision's policy declared (empty: none found), and its figure. */
    record Item(Optional<Money> limit, Money figure) {}

    /** Takes the census on {@code snapshot} - a connection the caller holds in one {@code REPEATABLE READ} transaction. */
    public Report take(Connection snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<UUID, Map<CurrencyCode, List<Item>>> byParty = new HashMap<>();
        try {
            try (Statement read = snapshot.createStatement(); ResultSet row = read.executeQuery(RESERVED)) {
                while (row.next()) {
                    CurrencyCode currency = CurrencyCode.of(row.getString(2).strip());
                    items(byParty, row.getObject(1, UUID.class), currency).add(new Item(
                            Optional.of(Money.ofMinorUnits(row.getLong(3), currency)),
                            Money.ofMinorUnits(row.getLong(4), currency)));
                }
            }
            try (PreparedStatement read = snapshot.prepareStatement(COMMITTED_AND_OUTSTANDING)) {
                LendingCreditExposure.bindPrincipalPurposes(read);
                for (Lent lent : lent(read)) {
                    items(byParty, lent.party(), lent.loan().agreed().currency()).add(new Item(lent.limit(), lent.loan().figure()));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(DatabaseFailure.describe("taking the exposure census", failure));
        }
        Set<UUID> failing = new TreeSet<>();
        byParty.forEach((party, currencies) -> currencies.values().forEach(items -> {
            if (!fits(items)) {
                failing.add(party);
            }
        }));
        return new Report(byParty.size() - failing.size(), failing.size(), failing);
    }

    /** For every limit among the items, the items under a limit at most it fit it; an unlimited item with a figure fails. */
    static boolean fits(List<Item> items) {
        for (Item item : items) {
            if (item.limit().isEmpty() && item.figure().isPositive()) {
                return false;
            }
        }
        for (Item bound : items) {
            if (bound.limit().isEmpty()) {
                continue;
            }
            Money limit = bound.limit().get();
            Money sum = Money.ofPersisted(0, limit.currency(), limit.scale());
            for (Item item : items) {
                if (item.limit().isPresent() && item.limit().get().compareTo(limit) <= 0) {
                    sum = sum.plus(item.figure());
                }
            }
            if (sum.compareTo(limit) > 0) {
                return false;
            }
        }
        return true;
    }

    private static List<Item> items(Map<UUID, Map<CurrencyCode, List<Item>>> byParty, UUID party, CurrencyCode currency) {
        return byParty.computeIfAbsent(party, key -> new HashMap<>()).computeIfAbsent(currency, key -> new ArrayList<>());
    }

    /** A loan account with its party and its decision's policy limit. */
    private record Lent(UUID party, Optional<Money> limit, LendingCreditExposure.LoanPrincipal loan) {}

    private static List<Lent> lent(PreparedStatement read) throws SQLException {
        Map<UUID, Lent> byLoan = new LinkedHashMap<>();
        try (ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                UUID loan = rows.getObject(1, UUID.class);
                Lent known = byLoan.get(loan);
                CurrencyCode currency = CurrencyCode.of(rows.getString(9).strip());
                if (known == null) {
                    long amount = rows.getLong(5);
                    if (rows.wasNull()) {
                        throw new IllegalStateException("loan " + loan + " has no agreement - the census refuses it");
                    }
                    long maximum = rows.getLong(10);
                    Optional<Money> limit = rows.wasNull() ? Optional.empty()
                            : Optional.of(Money.ofMinorUnits(maximum, currency));
                    known = new Lent(rows.getObject(8, UUID.class), limit, new LendingCreditExposure.LoanPrincipal(loan,
                            rows.getString(2), rows.getString(3), Money.ofPersisted(amount, currency, rows.getInt(4)),
                            new ArrayList<>()));
                    byLoan.put(loan, known);
                }
                BigDecimal net = rows.getBigDecimal(7);
                if (net != null) {
                    known.loan().drawnByScale().add(Money.ofPersisted(net.longValueExact(), currency, rows.getInt(6)));
                }
            }
        }
        return List.copyOf(byLoan.values());
    }
}
