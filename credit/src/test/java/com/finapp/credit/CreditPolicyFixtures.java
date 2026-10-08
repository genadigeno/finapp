package com.finapp.credit;

import static com.finapp.credit.ScorecardFixtures.inOneTransaction;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * The policy suites' shared machinery (`P10-TSK-012`), {@link ScorecardFixtures}'s shape: each product's versions are
 * one shared row set, so every case brings its product to the state it needs through the domain itself - rejecting a
 * pending proposal, proposing and activating its own version - and never assumes what an earlier case left.
 */
final class CreditPolicyFixtures {

    static final JdbcCreditPolicyStore STORE = new JdbcCreditPolicyStore();
    static final CreditPolicyAdministration ADMINISTRATION = new CreditPolicyAdministration(
            STORE, new JdbcAuditWriter(), new JdbcOutboxWriter(), ScorecardFixtures.IDS, ScorecardFixtures.CLOCK);

    private CreditPolicyFixtures() {}

    /** v1's policy with {@code rate} basis points for its stress rate - so each case's version is told apart. */
    static CreditPolicy policy(CreditProduct product, int rate) {
        CreditPolicy v1 = CreditPolicyV1.policy(product);
        return new CreditPolicy(product, rate, v1.minimumDisposable(), v1.minimumPaymentRatioBps(), v1.maximumExposure(),
                v1.maximumDataAge(), v1.unavailableFallback(), v1.autoApprovalCeiling(), v1.rules());
    }

    /** Rejects the product's pending proposal, if there is one, so a case can propose. */
    static void clearPending(CreditProduct product) {
        inOneTransaction(uow -> {
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT id FROM credit.credit_policy_version WHERE product = ? AND status = 'PROPOSED'")) {
                select.setString(1, product.name());
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        ADMINISTRATION.reject(uow, CreditPolicyVersionId.of(row.getObject(1, UUID.class)),
                                ScorecardFixtures.employee(), "cleared for the next case", ScorecardFixtures.correlation());
                    }
                }
                return null;
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    /** A fresh proposal by {@code proposer}. */
    static CreditPolicyAdministration.Proposed propose(CreditProduct product, int rate, Actor proposer) {
        clearPending(product);
        return inOneTransaction(uow -> ADMINISTRATION.propose(uow, policy(product, rate), "a case's proposal", proposer,
                ScorecardFixtures.correlation()));
    }

    /** A fresh version, proposed by one person and activated by another. */
    static CreditPolicyVersionId activate(CreditProduct product, int rate) {
        CreditPolicyAdministration.Proposed proposed = propose(product, rate, ScorecardFixtures.employee());
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), ScorecardFixtures.employee(),
                "a case's activation", ScorecardFixtures.correlation()));
        return proposed.id();
    }

    /** The version's status, read by the migrator. */
    static String status(CreditPolicyVersionId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT status FROM credit.credit_policy_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getString(1);
        }
    }
}
