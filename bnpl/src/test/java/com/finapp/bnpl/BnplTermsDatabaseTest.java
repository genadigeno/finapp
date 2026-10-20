package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * BNPL terms as versioned data against a real database (`P12-TSK-004`; {@code bnpl V002}, ADR-0102 decision 4;
 * {@code INV-HIST-04}, {@code INV-AUD-04}, {@code INV-LND-05} and {@code INV-LND-09} by analogy): every race counted -
 * ten proposers, ten approvers, an approval racing a rejection; four eyes, immutability for every role, one
 * {@code PROPOSED} and one {@code ACTIVE} version per product, pay-in-4's zero rate and nothing migration-activated, held
 * for every writer by raw SQL; an activation waiting for the pin of its predecessor; the effective periods clamped on a
 * database clock that stepped back. lending's {@code LoanTermsDatabaseTest}, applied.
 *
 * <p>The two products are shared rows in the suite's database, so every case first brings a product to the state it
 * needs through the domain itself and never assumes what an earlier case left; no case leaves a proposal pending (the
 * Phase 11 gate's R6).
 */
@Tag("database")
@DisplayName("BNPL terms as versioned data (P12-TSK-004)")
class BnplTermsDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcBnplTermsStore STORE = new JdbcBnplTermsStore();
    private static final BnplTermsAdministration ADMINISTRATION = new BnplTermsAdministration(STORE, new JdbcAuditWriter(),
            new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
    private static final BnplProduct P4 = BnplProduct.BNPL_PAY_IN_4;
    private static final BnplProduct PN = BnplProduct.BNPL_PAY_IN_N;

    @AfterEach
    void noTermsProposalLeftPending() {
        clearPending(P4);
        clearPending(PN);
    }

    @Test
    @DisplayName("ten proposers on ten connections leave one PROPOSED and nine ProposalPending; a raw second ACTIVE by a"
            + " different person is refused by the partial unique - one of each per product, counted")
    void oneProposedAndOneActivePerProduct() throws Exception {
        clearPending(PN);
        List<Object> outcomes = race(10, () -> {
            try {
                return inOneTransaction(uow -> ADMINISTRATION.propose(uow, BnplTermsFixtures.payInN("0.1601").build(),
                        "racing", employee(), correlation()));
            } catch (BnplTermsAdministration.ProposalPending pending) {
                return pending;
            }
        });
        assertThat(outcomes.stream().filter(BnplTermsAdministration.Proposed.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BnplTermsAdministration.ProposalPending.class::isInstance)).hasSize(9);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_terms_version WHERE product = 'BNPL_PAY_IN_N'"
                + " AND status = 'PROPOSED'")).isEqualTo(1);

        activate(P4);
        activate(P4);
        BnplTermsAdministration.Proposed second = propose(P4, employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE bnpl.bnpl_terms_version SET status = 'ACTIVE', decided_by = 'someone-else',"
                    + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw' WHERE id = '" + second.id().value() + "'",
                    "23505");
        }
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_terms_version WHERE product = 'BNPL_PAY_IN_4'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("an approval and a rejection racing on two connections leave exactly one decision - one terminal edge in"
            + " the history, the loser TermsStale - over ten rounds, counted")
    void approvalAndRejectionLeaveExactlyOne() throws Exception {
        for (int round = 0; round < 10; round++) {
            BnplTermsAdministration.Proposed proposed = propose(PN, employee());
            List<Object> outcomes = race(2, List.<Callable<Object>>of(
                    () -> decideOrStale(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "racing approval",
                            correlation())),
                    () -> decideOrStale(uow -> ADMINISTRATION.reject(uow, proposed.id(), employee(), "racing rejection",
                            correlation()))));
            assertThat(outcomes.stream().filter(BnplTermsAdministration.Decided.class::isInstance)).as("round %d", round)
                    .hasSize(1);
            assertThat(outcomes.stream().filter(BnplTermsAdministration.TermsStale.class::isInstance)).hasSize(1);
            assertThat(count("SELECT count(*) FROM bnpl.bnpl_terms_version_event WHERE terms_version_id = '"
                    + proposed.id().value() + "' AND from_status = 'PROPOSED'")).as("one decision row").isEqualTo(1);
            assertThat(status(proposed.id())).isIn("ACTIVE", "REJECTED");
        }
    }

    @Test
    @DisplayName("ten approvers activate once and retire the predecessor in the same transaction: one ACTIVE, the periods"
            + " meeting, one event, one audit record; a retirement alone never commits")
    void activationRetiresThePredecessorAtomically() throws Exception {
        BnplTermsVersionId predecessor = activate(PN);
        BnplTermsAdministration.Proposed proposed = propose(PN, employee());
        List<Object> outcomes = race(10, () -> decideOrStale(
                uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "racing", correlation())));
        assertThat(outcomes.stream().filter(BnplTermsAdministration.Decided.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BnplTermsAdministration.TermsStale.class::isInstance)).hasSize(9);
        assertThat(status(proposed.id())).isEqualTo("ACTIVE");
        assertThat(status(predecessor)).isEqualTo("RETIRED");
        assertThat(effective(predecessor, "effective_to")).as("the periods meet")
                .isEqualTo(effective(proposed.id(), "effective_from"));
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '"
                + BnplTermsAdministration.ACTIVATED_EVENT + "' AND aggregate_id = '" + proposed.id().value() + "'"))
                .as("one event").isEqualTo(1);
        assertThat(count(audited("bnpl.TermsVersionActivated", proposed.id()))).as("losers record nothing").isEqualTo(1);
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE bnpl.bnpl_terms_version SET status = 'RETIRED' WHERE id = '" + proposed.id().value()
                    + "'", "P0001");
        }
        assertThat(status(proposed.id())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the proposer cannot approve: refused at the domain, its attempt recorded FAILED; and by the CHECK for a"
            + " raw-SQL writer of either role - each rank alone")
    void theProposerCannotApprove() throws SQLException {
        Actor proposer = employee();
        BnplTermsAdministration.Proposed proposed = propose(P4, proposer);
        BnplTermsAdministration.SelfApprovalRefused refusal = (BnplTermsAdministration.SelfApprovalRefused)
                org.assertj.core.api.Assertions.catchThrowable(() -> inOneTransaction(
                        uow -> ADMINISTRATION.approve(uow, proposed.id(), proposer, "my own", correlation())));
        assertThat(refusal).isNotNull();
        inOneTransaction(uow -> {
            ADMINISTRATION.recordRefusal(uow, refusal, proposer, correlation());
            return null;
        });
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'bnpl.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_type = 'bnpl_terms_version' AND target_id = '" + proposed.id().value()
                + "'")).isEqualTo(1);
        String selfActivation = "UPDATE bnpl.bnpl_terms_version SET status = 'ACTIVE', decided_by = proposed_by,"
                + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'";
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, selfActivation, "23514");
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, selfActivation, "23514");
        }
        assertThat(status(proposed.id())).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("no terms version is migration-activated: nothing is seeded, and no version is born ACTIVE - or PROPOSED"
            + " without its term set - by insert, for any role")
    void noTermsVersionIsMigrationActivated() throws SQLException {
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_terms_version WHERE proposed_by LIKE 'migration:%'"
                + " OR proposed_by = 'finapp_migrator'")).as("nothing is seeded").isZero();
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_terms_version WHERE status IN ('ACTIVE', 'RETIRED')"
                + " AND (decided_by IS NULL OR decided_by = proposed_by)")).as("every activation two persons'").isZero();
        clearPending(P4);
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (writer) {
                refused(writer, "INSERT INTO bnpl.bnpl_terms_version (id, product, version, status, proposed_by,"
                        + " proposed_by_type, proposed_at, proposal_reason, decided_by, decided_by_type, decision_reason,"
                        + " correlation_id) VALUES (gen_random_uuid(), 'BNPL_PAY_IN_4', 9999, 'ACTIVE', 'a', 'EMPLOYEE',"
                        + " now(), 'r', 'b', 'EMPLOYEE', 'r', 'c')", "P0001");
                refused(writer, "INSERT INTO bnpl.bnpl_terms_version (id, product, version, status, proposed_by,"
                        + " proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES (gen_random_uuid(),"
                        + " 'BNPL_PAY_IN_4', 9998, 'PROPOSED', 'a', 'EMPLOYEE', now(), 'r', 'c')", "P0001");
            }
        }
    }

    @Test
    @DisplayName("the term set is never updated or deleted by any role - the application role holds no privilege, the"
            + " owner meets the trigger; the history is append-only; truncation is refused; a version's identity is frozen")
    void theTermSetIsNeverUpdatedOrDeletedByAnyRole() throws SQLException {
        BnplTermsVersionId id = activate(PN);
        String update = "UPDATE bnpl.bnpl_terms SET late_fee_minor = 1 WHERE terms_version_id = '" + id.value() + "'";
        String delete = "DELETE FROM bnpl.bnpl_terms WHERE terms_version_id = '" + id.value() + "'";
        try (Connection application = DatabaseRoles.application()) {
            refused(application, update, "42501");
            refused(application, delete, "42501");
            refused(application, "UPDATE bnpl.bnpl_terms_version_event SET reason = 'x' WHERE terms_version_id = '"
                    + id.value() + "'", "42501");
            refused(application, "UPDATE bnpl.bnpl_terms_version SET version = 7 WHERE id = '" + id.value() + "'",
                    "42501");
            refused(application, "DELETE FROM bnpl.bnpl_terms_version WHERE id = '" + id.value() + "'", "42501");
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, update, "P0001");
            refused(owner, delete, "P0001");
            refused(owner, "UPDATE bnpl.bnpl_terms_version_event SET reason = 'x' WHERE terms_version_id = '"
                    + id.value() + "'", "P0001");
            refused(owner, "UPDATE bnpl.bnpl_terms_version SET product = 'BNPL_PAY_IN_4' WHERE id = '" + id.value()
                    + "'", "P0001");
            refused(owner, "TRUNCATE bnpl.bnpl_terms CASCADE", "P0001");
            refused(owner, "DELETE FROM bnpl.bnpl_terms_version WHERE id = '" + id.value() + "'", "P0001");
            refused(owner, "INSERT INTO bnpl.bnpl_terms SELECT * FROM bnpl.bnpl_terms WHERE terms_version_id = '"
                    + id.value() + "'", "P0001");
        }
        // Unchanged: the fixture's own late fee, never the 1 the refused UPDATE wrote. (The fixture's default late fee
        // became 0 at P12-TSK-020 - "no late fee unless a suite asks" - and this literal 500 was left behind;
        // P12-TSK-027.)
        long fixture = BnplTermsFixtures.payInN("0.1999").build().lateFee().minorUnits();
        assertThat(fixture).isNotEqualTo(1);
        assertThat(count("SELECT late_fee_minor FROM bnpl.bnpl_terms WHERE terms_version_id = '" + id.value() + "'"))
                .isEqualTo(fixture);
    }

    @Test
    @DisplayName("a pay-in-4 version holds no rate: the kind CHECK refuses a non-zero rate, another plan, interest"
            + " rounding or an accrual engine from a raw writer - and admits the plan's own term set")
    void aPayIn4VersionHoldsNoRate() throws SQLException {
        clearPending(P4);
        rawPayIn4Proposal("nominal_annual_rate", "'0'", true);
        rawPayIn4Proposal("nominal_annual_rate", "'0.01'", false);
        rawPayIn4Proposal("instalment_count", "6", false);
        rawPayIn4Proposal("interval_days", "7", false);
        rawPayIn4Proposal("interest_rounding", "'HALF_EVEN'", false);
        rawPayIn4Proposal("accrual_engine", "'ACCRUAL_ENGINE_V1'", false);
        rawPayIn4Proposal("schedule_engine", "'SCHEDULE_ENGINE_V1'", false);
        rawPayIn4Proposal("servicing_zone", "'+01:00'", false);
        rawPayIn4Proposal("offer_validity_minutes", "31", false);
        assertThatExceptionOfType(BnplTerms.TermsInvalid.class).as("the domain refuses it first")
                .isThrownBy(() -> BnplTermsFixtures.payIn4().nominalAnnualRate(new java.math.BigDecimal("0.01")).build());
    }

    @Test
    @DisplayName("a pay-in-N set that cannot amortise is refused with the margin before anything is written; the same rate"
            + " without the 12-month plan is proposed")
    void aPayInNSetThatCannotAmortiseIsRefusedWithTheMargin() throws SQLException {
        clearPending(PN);
        long before = count("SELECT count(*) FROM bnpl.bnpl_terms_version WHERE product = 'BNPL_PAY_IN_N'");
        BnplTerms steep = BnplTermsFixtures.payInN("0.85")
                .payInN(Optional.of(BnplTermsFixtures.payInNPart(List.of(3, 6, 12), 28))).build();
        assertThatExceptionOfType(BnplTermsAdministration.TermsNotAmortising.class).isThrownBy(() -> inOneTransaction(
                uow -> ADMINISTRATION.propose(uow, steep, "too steep", employee(), correlation())));
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_terms_version WHERE product = 'BNPL_PAY_IN_N'"))
                .as("nothing written").isEqualTo(before);
        BnplTermsAdministration.Proposed shorter = inOneTransaction(uow -> ADMINISTRATION.propose(uow, steep.toBuilder()
                .payInN(Optional.of(BnplTermsFixtures.payInNPart(List.of(3, 6), 28))).build(), "shorter plans",
                employee(), correlation()));
        assertThat(status(shorter.id())).isEqualTo("PROPOSED");
        BnplTermsVersion read = inOneTransaction(uow -> ADMINISTRATION.version(uow, shorter.id()));
        assertThat(read.terms().payInN().orElseThrow().allowedTerms()).as("the term set reads back whole")
                .containsExactly(3, 6);
    }

    @Test
    @DisplayName("without an active version the product is not offered: the pin answers ProductNotOffered; with one, an"
            + " activation waits on the pin's FOR SHARE in A2's statement and retires the pinned version only after it")
    void withoutAnActiveVersionTheProductIsNotOffered() throws Exception {
        // The shared rows may hold an active version from an earlier case, so the closed state is planted: inside one
        // owner transaction, rolled back, the edge trigger disabled and every ACTIVE pay-in-4 version moved to RETIRED
        // (its successor check deferred to a commit that never comes) - the pin, on that connection, must find none.
        Optional<BnplTermsVersionId> beforeAny =
                inOneTransaction(uow -> STORE.activeAt(uow, P4, Instant.parse("2000-01-01T00:00:00Z")));
        assertThat(beforeAny).as("nothing was in force before any activation").isEmpty();
        activate(P4);
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement plant = owner.createStatement()) {
                plant.execute("ALTER TABLE bnpl.bnpl_terms_version DISABLE TRIGGER"
                        + " bnpl_terms_version_permits_only_machine_edges");
                plant.execute("UPDATE bnpl.bnpl_terms_version SET status = 'RETIRED',"
                        + " effective_to = effective_from + interval '1 microsecond'"
                        + " WHERE product = 'BNPL_PAY_IN_4' AND status = 'ACTIVE'");
                assertThatExceptionOfType(BnplTermsAdministration.ProductNotOffered.class)
                        .as("no ACTIVE version: the product is not offered")
                        .isThrownBy(() -> ADMINISTRATION.pinActive(owner, P4));
            } finally {
                owner.rollback();
            }
        }
        BnplTermsVersionId pinned = activate(P4);
        BnplTermsAdministration.Proposed successor = propose(P4, employee());
        try (Connection offer = DatabaseRoles.application()) {
            offer.setAutoCommit(false);
            BnplTermsVersion held = ADMINISTRATION.pinActive(offer, P4);
            assertThat(held.id()).as("the pin holds the version in force").isEqualTo(pinned);
            int offerPid;
            try (Statement pid = offer.createStatement(); ResultSet row = pid.executeQuery("SELECT pg_backend_pid()")) {
                row.next();
                offerPid = row.getInt(1);
            }
            CompletableFuture<BnplTermsAdministration.Decided> activation = CompletableFuture.supplyAsync(() ->
                    inOneTransaction(uow -> ADMINISTRATION.approve(uow, successor.id(), employee(), "waits", correlation())));
            String waiting = awaitAStatementBlockedBy(offerPid);
            assertThat(waiting).as("the activation waits on A2 - the ACTIVE row's lock - and nothing earlier")
                    .contains("FOR UPDATE").contains("status = 'ACTIVE'");
            assertThat(activation).as("blocked behind the pin").isNotDone();
            assertThat(status(pinned)).isEqualTo("ACTIVE");
            offer.commit();
            assertThat(activation.get(30, TimeUnit.SECONDS).retired()).contains(pinned);
        }
        assertThat(status(pinned)).isEqualTo("RETIRED");
        assertThat(status(successor.id())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an activation on a database clock behind its predecessor's start commits: the predecessor ends one"
            + " microsecond after it began, the successor starts exactly there (credit V014's lesson)")
    void aBackwardClockIsClamped() throws SQLException {
        BnplTermsVersionId predecessor = activate(PN);
        BnplTermsAdministration.Proposed successor = propose(PN, employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try {
                Instant planted;
                try (Statement plant = owner.createStatement()) {
                    plant.execute("ALTER TABLE bnpl.bnpl_terms_version DISABLE TRIGGER"
                            + " bnpl_terms_version_permits_only_machine_edges");
                    try (ResultSet row = plant.executeQuery("UPDATE bnpl.bnpl_terms_version SET effective_from ="
                            + " transaction_timestamp() + interval '1 hour' WHERE id = '" + predecessor.value()
                            + "' RETURNING effective_from")) {
                        row.next();
                        planted = row.getTimestamp(1).toInstant();
                    }
                    plant.execute("SET CONSTRAINTS ALL IMMEDIATE");
                    plant.execute("ALTER TABLE bnpl.bnpl_terms_version ENABLE TRIGGER"
                            + " bnpl_terms_version_permits_only_machine_edges");
                    plant.execute("SET CONSTRAINTS ALL DEFERRED");
                }
                BnplTermsAdministration.Decided activated =
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

    // ------------------------------------------------------------------ plumbing

    /** A raw proposal of pay-in-4's plan terms, one column replaced - committed when admitted, else refused 23514. */
    private static void rawPayIn4Proposal(String column, String value, boolean admitted) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection application = DatabaseRoles.application()) {
            application.setAutoCommit(false);
            try (Statement statement = application.createStatement()) {
                statement.execute("INSERT INTO bnpl.bnpl_terms_version (id, product, version, status, proposed_by,"
                        + " proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES ('" + id
                        + "', 'BNPL_PAY_IN_4', (SELECT COALESCE(max(version), 0) + 1 FROM bnpl.bnpl_terms_version WHERE"
                        + " product = 'BNPL_PAY_IN_4'), 'PROPOSED', 'raw', 'EMPLOYEE', now(), 'raw proposal', 'raw')");
                java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
                values.put("terms_version_id", "'" + id + "'");
                values.put("product", "'BNPL_PAY_IN_4'");
                values.put("currency", "'EUR'");
                values.put("scale", "2");
                values.put("nominal_annual_rate", "'0'");
                values.put("frequency", "'BIWEEKLY'");
                values.put("late_fee_minor", "500");
                values.put("late_fee_day", "5");
                values.put("late_fee_cap_minor", "1500");
                values.put("grace_days", "3");
                values.put("delinquency_bounds", "ARRAY[1, 30, 60, 90]");
                values.put("default_threshold_days", "90");
                values.put("allocation_sequence", "'OLDEST_DUE_FIRST'");
                values.put("allocation_order", "ARRAY['FEES', 'INTEREST', 'PRINCIPAL']");
                values.put("overpayment_treatment", "'HOLD_AS_CREDIT'");
                values.put("auto_collection", "true");
                values.put("partial_collection", "true");
                values.put("collection_retry_days", "1");
                values.put("instalment_rounding", "'DOWN'");
                values.put("interest_rounding", "NULL");
                values.put("day_count", "NULL");
                values.put("servicing_zone", "'UTC'");
                values.put("instalment_count", "4");
                values.put("interval_days", "14");
                values.put("allowed_terms", "NULL");
                values.put("min_first_period_days", "NULL");
                values.put("offer_validity_minutes", "30");
                values.put("dispute_response_days", "14");
                values.put("dispute_four_eyes_threshold_minor", "25000");
                values.put("payoff_horizon_days", "30");
                values.put("schedule_engine", "'BNPL_SCHEDULE_ENGINE_V1'");
                values.put("accrual_engine", "NULL");
                values.put("allocation_engine", "'ALLOCATION_ENGINE_V1'");
                values.put("refund_allocation_engine", "'REFUND_ALLOCATION_ENGINE_V1'");
                values.put("agreement_template_id", "'BNPL_PAY_IN_4_AGREEMENT'");
                values.put("agreement_template_version", "1");
                values.put(column, value);
                String sql = "INSERT INTO bnpl.bnpl_terms (" + String.join(", ", values.keySet()) + ") VALUES ("
                        + String.join(", ", values.values()) + ")";
                if (admitted) {
                    statement.execute(sql);
                } else {
                    refused(application, sql, "23514");
                }
            } finally {
                // Never committed: the raw proposal must not stand pending for the next case.
                application.rollback();
            }
        }
    }

    private static Object decideOrStale(Function<Connection, BnplTermsAdministration.Decided> act) {
        try {
            return inOneTransaction(act);
        } catch (BnplTermsAdministration.TermsStale stale) {
            return stale;
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** Rejects the product's pending proposal, if there is one, so a case can propose. */
    private static void clearPending(BnplProduct product) {
        Optional<BnplTermsVersionId> pending = inOneTransaction(uow -> {
            try (Statement statement = uow.createStatement(); ResultSet row = statement.executeQuery(
                    "SELECT id FROM bnpl.bnpl_terms_version WHERE product = '" + product.name()
                            + "' AND status = 'PROPOSED'")) {
                return row.next() ? Optional.of(BnplTermsVersionId.of(row.getObject(1, UUID.class))) : Optional.empty();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        pending.ifPresent(id -> inOneTransaction(
                uow -> ADMINISTRATION.reject(uow, id, employee(), "cleared by the suite", correlation())));
    }

    /** A fresh proposal of the product's plan terms, by {@code proposer}. */
    private static BnplTermsAdministration.Proposed propose(BnplProduct product, Actor proposer) {
        clearPending(product);
        BnplTerms terms = product == P4 ? BnplTermsFixtures.payIn4().build() : BnplTermsFixtures.payInN("0.1999").build();
        return inOneTransaction(uow -> ADMINISTRATION.propose(uow, terms, "a case's proposal", proposer, correlation()));
    }

    /** A fresh version, proposed by one person and activated by another. */
    private static BnplTermsVersionId activate(BnplProduct product) {
        BnplTermsAdministration.Proposed proposed = propose(product, employee());
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "a case's activation", correlation()));
        return proposed.id();
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String audited(String operation, BnplTermsVersionId id) {
        return "SELECT count(*) FROM platform.audit_record WHERE operation = '" + operation + "' AND target_id = '"
                + id.value() + "' AND reason IS NOT NULL AND outcome = 'SUCCEEDED'";
    }

    private static String status(BnplTermsVersionId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT status FROM bnpl.bnpl_terms_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getString(1);
        }
    }

    private static Instant effective(BnplTermsVersionId id, String column) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            return effectiveIn(migrator, id, column);
        }
    }

    private static Instant effectiveIn(Connection connection, BnplTermsVersionId id, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT " + column + " FROM bnpl.bnpl_terms_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }

    /** Waits until a session is blocked by {@code blocker}, and answers the statement it waits in. */
    private static String awaitAStatementBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                if (row.next()) {
                    return row.getString(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no activation ever waited on the pinned version");
    }

    /** {@code sql}, run alone by {@code connection}, is refused with {@code sqlState}. */
    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        List<Callable<T>> works = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            works.add(work);
        }
        return race(racers, works);
    }

    private static <T> List<T> race(int racers, List<Callable<T>> works) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (Callable<T> work : works) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
