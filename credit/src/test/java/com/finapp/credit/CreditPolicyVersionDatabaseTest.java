package com.finapp.credit;

import static com.finapp.credit.CreditPolicyFixtures.ADMINISTRATION;
import static com.finapp.credit.CreditPolicyFixtures.STORE;
import static com.finapp.credit.CreditPolicyFixtures.activate;
import static com.finapp.credit.CreditPolicyFixtures.clearPending;
import static com.finapp.credit.CreditPolicyFixtures.policy;
import static com.finapp.credit.CreditPolicyFixtures.propose;
import static com.finapp.credit.CreditPolicyFixtures.status;
import static com.finapp.credit.ScorecardFixtures.correlation;
import static com.finapp.credit.ScorecardFixtures.count;
import static com.finapp.credit.ScorecardFixtures.employee;
import static com.finapp.credit.ScorecardFixtures.inOneTransaction;
import static com.finapp.credit.ScorecardFixtures.race;
import static com.finapp.credit.ScorecardFixtures.refused;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The credit policy's versioning against a real database (`P10-TSK-012`; {@code INV-CRD-05}, {@code INV-CRD-10},
 * {@code INV-HIST-04}, {@code INV-AUD-04}): every race counted, the four eyes, the frozen rules and the never-approving
 * fallback held for every writer by raw SQL, the version active at any past instant answered from the rows, and an
 * activation waiting for the decision that pinned its predecessor.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the credit policy and its versioning (P10-TSK-012)")
class CreditPolicyVersionDatabaseTest {

    private static final CreditProduct LOAN = CreditProduct.PERSONAL_LOAN;
    private static final CreditProduct LINE = CreditProduct.CREDIT_LINE;

    @Test
    @Order(1)
    @DisplayName("each product's seed is its v1, proposed by the migration, its policy exactly the expected one - never active by migration")
    void theSeedsAreProposals() throws SQLException {
        for (CreditProduct product : CreditProduct.values()) {
            Optional<CreditPolicyStore.PolicyVersion> seed = inOneTransaction(uow -> STORE.policy(uow, CreditPolicyV1.id(product)));
            assertThat(seed).as(product.name()).isPresent();
            assertThat(seed.get().row().version()).isEqualTo(1);
            assertThat(seed.get().row().product()).isEqualTo(product);
            assertThat(seed.get().row().proposedBy()).isEqualTo("migration:V008");
            assertThat(seed.get().policy()).isEqualTo(CreditPolicyV1.policy(product));
        }
        assertThat(count("SELECT count(*) FROM credit.credit_policy_event WHERE to_status = 'ACTIVE'"
                + " AND actor_id LIKE 'migration:%'")).as("no migration ever activates").isZero();
    }

    @Test
    @Order(2)
    @DisplayName("each product's v1 is activated by a person - not its proposer, the migration - and decides from then")
    void eachProductsV1IsActivatedByAPerson() throws SQLException {
        for (CreditProduct product : CreditProduct.values()) {
            Actor officer = employee();
            // Another suite needing v1 in force may have activated it first (CreditPolicyFixtures.activateV1).
            Optional<CreditPolicyAdministration.Decided> activated = CreditPolicyFixtures.activateV1(product, officer);
            if (activated.isPresent()) {
                assertThat(activated.get().status()).as(product.name()).isEqualTo(CreditPolicyStatus.ACTIVE);
                assertThat(activated.get().retired()).as("the first version retires nothing").isEmpty();
                assertThat(inOneTransaction(uow -> ADMINISTRATION.activeAt(uow, product, Optional.empty())).row().id())
                        .isEqualTo(CreditPolicyV1.id(product));
                assertThat(decidedBy(CreditPolicyV1.id(product)))
                        .as("two parties: the migration proposed, a person activated").isEqualTo(officer.id());
            } else {
                assertThat(status(CreditPolicyV1.id(product))).as(product.name()).isIn("ACTIVE", "RETIRED");
                assertThat(decidedBy(CreditPolicyV1.id(product)))
                        .as("two parties: the migration proposed, a person activated").doesNotStartWith("migration:");
            }
        }
    }

    @Test
    @DisplayName("ten approvers of one proposal: one activation, nine PolicyStale - counted")
    void tenApproversActivateOnce() throws Exception {
        CreditPolicyAdministration.Proposed proposed = propose(LOAN, 901, employee());
        List<Object> outcomes = race(10, () -> {
            try {
                return inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "racing", correlation()));
            } catch (CreditPolicyAdministration.PolicyStale stale) {
                return stale;
            }
        });
        assertThat(outcomes.stream().filter(CreditPolicyAdministration.Decided.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(CreditPolicyAdministration.PolicyStale.class::isInstance)).hasSize(9);
        assertThat(status(proposed.id())).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM credit.credit_policy_version WHERE product = 'PERSONAL_LOAN'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditPolicyVersionActivated'"
                + " AND aggregate_id = '" + proposed.id().value() + "'")).as("one event").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.PolicyVersionActivated'"
                + " AND target_id = '" + proposed.id().value() + "'")).as("losers record nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("ten proposers of one product: one proposal, nine ProposalPending - counted")
    void tenProposersLeaveOneProposal() throws Exception {
        clearPending(LINE);
        List<Object> outcomes = race(10, () -> {
            try {
                return inOneTransaction(uow -> ADMINISTRATION.propose(uow, policy(LINE, 902), "racing", employee(),
                        correlation()));
            } catch (CreditPolicyAdministration.ProposalPending pending) {
                return pending;
            }
        });
        assertThat(outcomes.stream().filter(CreditPolicyAdministration.Proposed.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(CreditPolicyAdministration.ProposalPending.class::isInstance)).hasSize(9);
        assertThat(count("SELECT count(*) FROM credit.credit_policy_version WHERE product = 'CREDIT_LINE'"
                + " AND status = 'PROPOSED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("self-approval is refused at the domain, and by the CHECK for a raw-SQL writer - each rank alone")
    void selfApprovalIsRefusedAtTheDomainAndTheCheck() throws SQLException {
        Actor proposer = employee();
        CreditPolicyAdministration.Proposed proposed = propose(LOAN, 903, proposer);
        assertThatExceptionOfType(CreditPolicyAdministration.SelfApprovalRefused.class).isThrownBy(() -> inOneTransaction(
                uow -> ADMINISTRATION.approve(uow, proposed.id(), proposer, "my own", correlation())));
        String selfActivation = "UPDATE credit.credit_policy_version SET status = 'ACTIVE', decided_by = proposed_by,"
                + " decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'";
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, selfActivation, "23514");
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, selfActivation, "23514");
        }
        assertThat(status(proposed.id())).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("rules are immutable from insert - an UPDATE, a DELETE and a late rule refused while PROPOSED, and again once ACTIVE")
    void rulesAreImmutableFromInsert() throws SQLException {
        CreditPolicyAdministration.Proposed proposed = propose(LOAN, 904, employee());
        rulesRefused(proposed.id());
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "now active", correlation()));
        rulesRefused(proposed.id());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "TRUNCATE credit.credit_policy_rule", "P0001");
            refused(owner, "UPDATE credit.credit_policy_version SET maximum_exposure_minor = 1 WHERE id = '"
                    + proposed.id().value() + "'", "P0001");
            refused(owner, "UPDATE credit.credit_policy_version SET unavailable_fallback = 'DECLINE' WHERE id = '"
                    + proposed.id().value() + "'", "P0001");
            refused(owner, "DELETE FROM credit.credit_policy_version WHERE id = '" + proposed.id().value() + "'", "P0001");
        }
        assertThat(inOneTransaction(uow -> STORE.policy(uow, proposed.id())).orElseThrow().policy())
                .isEqualTo(policy(LOAN, 904));
    }

    private static void rulesRefused(CreditPolicyVersionId id) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.credit_policy_rule SET operand_integer = 999 WHERE policy_version_id = '"
                    + id.value() + "' AND rule_code = 'SCORE_FLOOR'", "P0001");
            refused(owner, "DELETE FROM credit.credit_policy_rule WHERE policy_version_id = '" + id.value()
                    + "' AND rule_code = 'SOURCE_UNAVAILABLE_FALLBACK'", "P0001");
            refused(owner, "INSERT INTO credit.credit_policy_rule (policy_version_id, ordinal, rule_code, subject_kind,"
                    + " subject, operator, effect, reason_code) VALUES ('" + id.value() + "', 99, 'LATE', 'ATTRIBUTE',"
                    + " 'RISK_SIGNAL', 'IS_PRESENT', 'REFER', 'CRD-RISK-REFERRAL')", "P0001");
        }
    }

    @Test
    @DisplayName("one ACTIVE version per product - a raw-SQL second activation is refused by the partial unique")
    void oneActivePerProduct() throws SQLException {
        activate(LOAN, 905);
        CreditPolicyAdministration.Proposed second = propose(LOAN, 906, employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.credit_policy_version SET status = 'ACTIVE', decided_by = 'someone-else',"
                    + " decision_reason = 'raw' WHERE id = '" + second.id().value() + "'", "23505");
        }
        assertThat(count("SELECT count(*) FROM credit.credit_policy_version WHERE product = 'PERSONAL_LOAN'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the fallback never approves and no policy is born active - each refused for a raw-SQL writer")
    void theFallbackNeverApprovesForEveryWriter() throws SQLException {
        String insert = "INSERT INTO credit.credit_policy_version (id, product, version, status, currency, scale,"
                + " assessment_rate_bps, minimum_disposable_minor, minimum_payment_ratio_bps, maximum_exposure_minor,"
                + " unavailable_fallback, auto_approval_ceiling_minor, proposed_by, proposed_at, proposal_reason)"
                + " VALUES (gen_random_uuid(), 'PERSONAL_LOAN', 9999, '%s', 'EUR', 2, 900, 0, 300, 100, '%s', 100, 'a',"
                + " now(), 'r')";
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, insert.formatted("PROPOSED", "APPROVE"), "23514");
            refused(owner, insert.formatted("ACTIVE", "REFER"), "P0001");
        }
    }

    @Test
    @DisplayName("the version active at any past instant is answered from the rows, over a three-version history, boundaries included")
    void theVersionActiveAtAnyPastInstantIsAnswerable() throws Exception {
        CreditPolicyVersionId first = activate(LINE, 907);
        Thread.sleep(20);
        CreditPolicyVersionId second = activate(LINE, 908);
        Thread.sleep(20);
        CreditPolicyVersionId third = activate(LINE, 909);
        Instant firstFrom = effective(first, "effective_from");
        Instant secondFrom = effective(second, "effective_from");
        Instant thirdFrom = effective(third, "effective_from");
        assertThat(effective(first, "effective_to")).as("they meet").isEqualTo(secondFrom);
        assertThat(effective(second, "effective_to")).as("they meet").isEqualTo(thirdFrom);
        assertThat(activeAt(firstFrom)).contains(first);
        assertThat(activeAt(secondFrom.minusNanos(1000))).as("the instant before the boundary").contains(first);
        assertThat(activeAt(secondFrom)).as("the boundary belongs to the successor").contains(second);
        assertThat(activeAt(thirdFrom.minusNanos(1000))).contains(second);
        assertThat(activeAt(thirdFrom)).contains(third);
        assertThat(activeAt(thirdFrom.plusSeconds(3600))).as("still active").contains(third);
        assertThat(inOneTransaction(uow -> ADMINISTRATION.activeAt(uow, LINE, Optional.empty())).row().id())
                .as("with no instant, the version ACTIVE by status").isEqualTo(third);
        assertThat(inOneTransaction(uow -> ADMINISTRATION.activeAt(uow, LINE, Optional.of(secondFrom))).policy())
                .as("the read returns the version's whole policy").isEqualTo(policy(LINE, 908));
    }

    @Test
    @DisplayName("over a GENERATED history of twelve activations, each switch instant and the microsecond before it are"
            + " answered exactly (P10-DOC-001)")
    void theVersionActiveAtAnyInstantOverAGeneratedHistory() throws Exception {
        java.util.Random random = new java.util.Random(20_261_009L);
        List<CreditPolicyVersionId> versions = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            versions.add(activate(LINE, 920 + i));
            Thread.sleep(random.nextInt(15));
        }
        for (int i = 0; i < versions.size(); i++) {
            Instant from = effective(versions.get(i), "effective_from");
            assertThat(activeAt(from)).as("switch %d belongs to its successor", i).contains(versions.get(i));
            if (i > 0) {
                assertThat(effective(versions.get(i - 1), "effective_to")).as("switch %d: the periods meet", i)
                        .isEqualTo(from);
                assertThat(activeAt(from.minusNanos(1000))).as("the microsecond before switch %d", i)
                        .contains(versions.get(i - 1));
            }
        }
        assertThat(activeAt(effective(versions.get(11), "effective_from").plusSeconds(3600))).contains(versions.get(11));
    }

    @Test
    @DisplayName("a proposal and a rejection are each audited once, by operation and target, with the reason; a refused"
            + " approval and a stale rejection record nothing (P10-DOC-001)")
    void aProposalAndARejectionAreEachAudited() throws Exception {
        Actor proposer = employee();
        CreditPolicyAdministration.Proposed proposed = propose(LOAN, 912, proposer);
        String target = proposed.id().value().toString();
        assertThat(count(audited("credit.PolicyVersionProposed", target))).as("the proposal").isEqualTo(1);
        assertThatExceptionOfType(CreditPolicyAdministration.SelfApprovalRefused.class).isThrownBy(() -> inOneTransaction(
                uow -> ADMINISTRATION.approve(uow, proposed.id(), proposer, "my own", correlation())));
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = '" + target + "'"
                + " AND operation <> 'credit.PolicyVersionProposed'")).as("a refused approval records nothing").isZero();
        inOneTransaction(uow -> ADMINISTRATION.reject(uow, proposed.id(), employee(), "withdrawn after review",
                correlation()));
        assertThat(count(audited("credit.PolicyVersionRejected", target))).as("the rejection").isEqualTo(1);
        assertThatExceptionOfType(CreditPolicyAdministration.PolicyStale.class).isThrownBy(() -> inOneTransaction(
                uow -> ADMINISTRATION.reject(uow, proposed.id(), employee(), "too late", correlation())));
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = '" + target + "'"))
                .as("the stale rejection recorded nothing").isEqualTo(2);
    }

    private static String audited(String operation, String target) {
        return "SELECT count(*) FROM platform.audit_record WHERE operation = '" + operation + "' AND target_id = '"
                + target + "' AND reason IS NOT NULL AND outcome = 'SUCCEEDED'";
    }

    @Test
    @DisplayName("a retirement that commits alone is refused at commit - only beside its successor")
    void aRetirementNeverCommitsAlone() throws SQLException {
        CreditPolicyVersionId active = activate(LOAN, 910);
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.credit_policy_version SET status = 'RETIRED' WHERE id = '" + active.value() + "'",
                    "P0001");
        }
        assertThat(status(active)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an activation on a database clock behind its predecessor's start commits: the predecessor ends one"
            + " microsecond after it began, the successor starts exactly there (X-TSK-017)")
    void anActivationSurvivesADatabaseClockBehindItsPredecessor() throws SQLException {
        CreditPolicyVersionId predecessor = activate(LOAN, 913);
        CreditPolicyAdministration.Proposed successor = propose(LOAN, 914, employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try {
                // The clock behind: the predecessor's start planted an hour past this transaction's, by the owner with the
                // edge trigger disabled - inside a transaction rolled back, so no history keeps it.
                Instant planted = plantStartAnHourAhead(owner, predecessor);
                CreditPolicyAdministration.Decided activated =
                        ADMINISTRATION.approve(owner, successor.id(), employee(), "a clock behind", correlation());
                try (Statement now = owner.createStatement()) {
                    now.execute("SET CONSTRAINTS ALL IMMEDIATE");
                }
                assertThat(activated.retired()).contains(predecessor);
                Instant end = effectiveIn(owner, predecessor, "effective_to");
                assertThat(end).as("never at or before its own start").isEqualTo(planted.plusNanos(1_000));
                assertThat(effectiveIn(owner, successor.id(), "effective_from")).as("they meet").isEqualTo(end);
            } finally {
                owner.rollback();
            }
        }
        assertThat(status(predecessor)).as("the plant rolled back with it").isEqualTo("ACTIVE");
    }

    private static Instant plantStartAnHourAhead(Connection owner, CreditPolicyVersionId id) throws SQLException {
        try (Statement plant = owner.createStatement()) {
            plant.execute("ALTER TABLE credit.credit_policy_version DISABLE TRIGGER credit_policy_permits_only_machine_edges");
            Instant planted;
            try (ResultSet row = plant.executeQuery("UPDATE credit.credit_policy_version SET effective_from ="
                    + " transaction_timestamp() + interval '1 hour' WHERE id = '" + id.value() + "' RETURNING effective_from")) {
                row.next();
                planted = row.getTimestamp(1).toInstant();
            }
            // The plant queued the deferred successor check (a no-op for an ACTIVE row); fire it so the table can be
            // altered again, then defer the checks once more for the activation under test.
            plant.execute("SET CONSTRAINTS ALL IMMEDIATE");
            plant.execute("ALTER TABLE credit.credit_policy_version ENABLE TRIGGER credit_policy_permits_only_machine_edges");
            plant.execute("SET CONSTRAINTS ALL DEFERRED");
            return planted;
        }
    }

    private static Instant effectiveIn(Connection connection, CreditPolicyVersionId id, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT " + column + " FROM credit.credit_policy_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }

    @Test
    @DisplayName("an activation waits for a decision holding the active version FOR SHARE as its pin, then retires it")
    void anActivationWaitsForAPinnedReader() throws Exception {
        CreditPolicyVersionId pinned = activate(LOAN, 911);
        CreditPolicyAdministration.Proposed successor = propose(LOAN, 912, employee());
        try (Connection decision = DatabaseRoles.application()) {
            decision.setAutoCommit(false);
            try (PreparedStatement pin = decision.prepareStatement(
                    "SELECT id FROM credit.credit_policy_version WHERE id = ? FOR SHARE")) {
                pin.setObject(1, pinned.value());
                try (ResultSet row = pin.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
            }
            int decisionPid;
            try (Statement pid = decision.createStatement(); ResultSet row = pid.executeQuery("SELECT pg_backend_pid()")) {
                row.next();
                decisionPid = row.getInt(1);
            }
            CompletableFuture<CreditPolicyAdministration.Decided> activation = CompletableFuture.supplyAsync(() ->
                    inOneTransaction(uow -> ADMINISTRATION.approve(uow, successor.id(), employee(), "waits", correlation())));
            awaitASessionBlockedBy(decisionPid);
            assertThat(activation).as("blocked behind the pin").isNotDone();
            assertThat(status(pinned)).as("the pinned version decides while the decision is open").isEqualTo("ACTIVE");
            decision.commit();
            assertThat(activation.get(30, TimeUnit.SECONDS).retired()).contains(pinned);
        }
        assertThat(status(pinned)).isEqualTo("RETIRED");
        assertThat(status(successor.id())).isEqualTo("ACTIVE");
    }

    /** Waits until a session is blocked by {@code blocker} - the activation behind the pin. */
    private static void awaitASessionBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (count("SELECT count(*) FROM pg_stat_activity WHERE " + blocker + " = ANY (pg_blocking_pids(pid))") > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no activation ever waited on the pinned version");
    }

    private static String decidedBy(CreditPolicyVersionId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT decided_by FROM credit.credit_policy_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getString(1);
        }
    }

    private static Optional<CreditPolicyVersionId> activeAt(Instant instant) {
        return inOneTransaction(uow -> STORE.activeAt(uow, LINE, instant));
    }

    private static Instant effective(CreditPolicyVersionId id, String column) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT " + column + " FROM credit.credit_policy_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }
}
