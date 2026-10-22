package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.correlation;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.customer;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.employee;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.inOneTransaction;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.CLOCK;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.IDS;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.PURCHASES;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.REFUNDS;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.SERVICING;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.TERMS_STORE;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Nothing leaves a terminal state, for any writer (the Phase 12 exit review's finding; {@code INV-LIFE-04},
 * {@code INV-HIST-01}; {@code bnpl V002}, {@code V003}, {@code V004}, {@code V006}/{@code V012}, {@code V010},
 * {@code V015}, {@code V016}): on REAL terminal rows - each reached through the domain over bnpl's own JDBC stores, never
 * planted - every raw-SQL edge out of it is refused for the migrator (the schema's owner) and the application role alike:
 * the purchase out of {@code FINANCED} back to {@code AUTHORISED} and out of {@code FULLY_REFUNDED}; the obligation out of
 * {@code CLOSED}; the dispute out of {@code RESOLVED_REFUND}, {@code RESOLVED_UPHELD} and {@code WITHDRAWN}; a dispute
 * proposal's, a repayment reversal's and a waiver's decision re-decided; the terms version out of {@code RETIRED} and
 * {@code REJECTED}; the offer out of {@code ACCEPTED}, {@code DECLINED} and {@code EXPIRED}; the application out of
 * {@code OFFERED}, {@code DECLINED} and {@code WITHDRAWN}. Each edge is shaped to satisfy every rank after the terminal one
 * where it can (a withdrawal by the dispute's own customer, refunding nothing; an approval by another operator), so the
 * terminal rank - not a later one - is what must refuse it. Every refusal is collected, then judged at once, and the rows
 * are re-read unchanged.
 */
@Tag("database")
@DisplayName("nothing leaves a terminal state, for any role, on real terminal rows (the Phase 12 exit review)")
class BnplTerminalEdgesDatabaseTest {

    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    /** One raw edge: what it attempts, its statement, and the SQLSTATE each role must meet. */
    private record Edge(String name, String sql, String migratorState, String applicationState) {}

    @Test
    @DisplayName("every raw edge out of every terminal state is refused for the migrator and the application role; every"
            + " terminal row re-read unchanged")
    void nothingLeavesATerminalStateForAnyRole() throws Exception {
        BnplCorrectionDatabaseWorld corrections = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld world = corrections.base;
        // Every operator a party of their own, never a customer's: the dispute's self-dealing judgement fails closed on an
        // operator the platform cannot place (the Phase 12 -> 13 gate's A7-6).
        BnplDisputes disputes = new BnplDisputes(new JdbcBnplDisputeStore(), REFUNDS, SERVICING, PURCHASES, TERMS_STORE,
                world.refunds, world.ports, world.book, (uow, actor) -> Optional.of(UUID.nameUUIDFromBytes(
                        ("operator:" + actor.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);
        List<Edge> edges = new ArrayList<>();
        Map<String, String> terminal = new LinkedHashMap<>();

        // ---- the purchase and the obligation: FINANCED, FULLY_REFUNDED, CLOSED
        BnplConditionDatabaseWorld.Purchased financed = world.plain();
        String financedPurchase = where(financed.purchase().value());
        edges.add(p0001("purchase FINANCED -> AUTHORISED",
                "UPDATE bnpl.purchase SET status = 'AUTHORISED'" + financedPurchase));
        BnplConditionDatabaseWorld.Purchased refunded = world.plain();
        world.refundOn(refunded.purchaseDate().plusDays(1), refunded, "full", 600_00);
        String refundedPurchase = where(refunded.purchase().value());
        for (String to : List.of("FINANCED", "AUTHORISED")) {
            edges.add(p0001("purchase FULLY_REFUNDED -> " + to,
                    "UPDATE bnpl.purchase SET status = '" + to + "'" + refundedPurchase));
        }
        String closedObligation = where(refunded.obligation().value());
        edges.add(p0001("obligation CLOSED -> ACTIVE",
                "UPDATE bnpl.bnpl_obligation SET status = 'ACTIVE', closure_reason = NULL" + closedObligation));
        edges.add(p0001("obligation CLOSED -> CLOSED for another reason",
                "UPDATE bnpl.bnpl_obligation SET closure_reason = 'REPAID'" + closedObligation));
        terminal.put("SELECT status FROM bnpl.purchase" + financedPurchase, "FINANCED");
        terminal.put("SELECT status FROM bnpl.purchase" + refundedPurchase, "FULLY_REFUNDED");
        terminal.put("SELECT status || ':' || closure_reason FROM bnpl.bnpl_obligation" + closedObligation,
                "CLOSED:REFUNDED");

        // ---- the dispute: RESOLVED_REFUND (the merchant's acceptance), RESOLVED_UPHELD, WITHDRAWN
        Actor operatorA = new Actor("operator-a", ActorType.CUSTOMER);
        Actor operatorB = new Actor("operator-b", ActorType.CUSTOMER);
        BnplConditionDatabaseWorld.Purchased accepted = world.plain();
        BnplDispute refundOutcome = open(world, disputes, accepted, 100_00);
        respond(world, disputes, refundOutcome, accepted, BnplDisputes.Decision.ACCEPT);
        BnplConditionDatabaseWorld.Purchased contested = world.plain();
        BnplDispute upheld = open(world, disputes, contested, 200_00);
        respond(world, disputes, upheld, contested, BnplDisputes.Decision.CONTEST);
        resolve(world, disputes, upheld, contested, operatorA, BnplDisputeStatus.RESOLVED_UPHELD);
        BnplConditionDatabaseWorld.Purchased withdrawing = world.plain();
        BnplDispute withdrawn = open(world, disputes, withdrawing, 100_00);
        BnplClockFrame.inOneTransaction(world.frameOn(withdrawing.purchaseDate().plusDays(2)), uow -> disputes.withdraw(
                uow, withdrawn.id(), withdrawing.party(), customer(withdrawing.party()), correlation()));
        Map<String, BnplDispute> resolved = new LinkedHashMap<>();
        resolved.put("RESOLVED_REFUND", refundOutcome);
        resolved.put("RESOLVED_UPHELD", upheld);
        resolved.put("WITHDRAWN", withdrawn);
        for (Map.Entry<String, BnplDispute> dispute : resolved.entrySet()) {
            String from = dispute.getKey();
            String at = where(dispute.getValue().id().value());
            edges.add(p0001("dispute " + from + " -> OPEN", "UPDATE bnpl.bnpl_dispute SET status = 'OPEN',"
                    + " resolved_by = NULL, resolution_reason = NULL, resolved_at = NULL, resolved_business_date = NULL,"
                    + " refund_id = NULL, proposal_id = NULL, next_sweep_at = response_deadline_at" + at));
            edges.add(p0001("dispute " + from + " -> MERCHANT_RESPONDED", "UPDATE bnpl.bnpl_dispute SET status ="
                    + " 'MERCHANT_RESPONDED', merchant_statement = coalesce(merchant_statement, 'raw'), resolved_by ="
                    + " NULL, resolution_reason = NULL, resolved_at = NULL, resolved_business_date = NULL, refund_id ="
                    + " NULL, proposal_id = NULL" + at));
            if (!from.equals("WITHDRAWN")) {
                // Shaped as the withdrawal edge wants it: by the dispute's own customer, refunding nothing.
                edges.add(p0001("dispute " + from + " -> WITHDRAWN by its own customer", "UPDATE bnpl.bnpl_dispute SET"
                        + " status = 'WITHDRAWN', resolved_by = 'CUSTOMER:' || party_id::text, resolution_reason = NULL,"
                        + " refund_id = NULL, proposal_id = NULL" + at));
            }
            if (!from.equals("RESOLVED_UPHELD")) {
                // Shaped as the operator's decision wants it: an operator, reasoned, the merchant's answer untouched.
                edges.add(p0001("dispute " + from + " -> RESOLVED_UPHELD by an operator", "UPDATE bnpl.bnpl_dispute"
                        + " SET status = 'RESOLVED_UPHELD', resolved_by = 'OPERATOR:operator-z', resolution_reason ="
                        + " 'raw', refund_id = NULL, proposal_id = NULL" + at));
            }
            if (!from.equals("RESOLVED_REFUND")) {
                edges.add(p0001("dispute " + from + " -> RESOLVED_REFUND by an operator", "UPDATE bnpl.bnpl_dispute"
                        + " SET status = 'RESOLVED_REFUND', resolved_by = 'OPERATOR:operator-z', resolution_reason ="
                        + " 'raw'" + at));
            }
            terminal.put("SELECT status FROM bnpl.bnpl_dispute" + at, from);
        }

        // ---- the dispute proposal: APPROVED (by another operator) and REJECTED
        BnplConditionDatabaseWorld.Purchased approving = world.plain();
        BnplDispute aboveThreshold = open(world, disputes, approving, 300_00);
        respond(world, disputes, aboveThreshold, approving, BnplDisputes.Decision.CONTEST);
        UUID approvedProposal = resolve(world, disputes, aboveThreshold, approving, operatorA,
                BnplDisputeStatus.RESOLVED_REFUND).proposal().orElseThrow().id();
        BnplClockFrame.inOneTransaction(world.frameOn(approving.purchaseDate().plusDays(3)), uow -> disputes.approve(uow,
                aboveThreshold.id(), approvedProposal, "upheld on review", operatorB, correlation()));
        BnplConditionDatabaseWorld.Purchased rejecting = world.plain();
        BnplDispute declinedDispute = open(world, disputes, rejecting, 300_00);
        respond(world, disputes, declinedDispute, rejecting, BnplDisputes.Decision.CONTEST);
        UUID rejectedProposal = resolve(world, disputes, declinedDispute, rejecting, operatorA,
                BnplDisputeStatus.RESOLVED_REFUND).proposal().orElseThrow().id();
        BnplClockFrame.inOneTransaction(world.frameOn(rejecting.purchaseDate().plusDays(3)), uow -> disputes.reject(uow,
                declinedDispute.id(), rejectedProposal, "not on this evidence", operatorB, correlation()));
        for (Map.Entry<String, UUID> proposal : Map.of("APPROVED", approvedProposal, "REJECTED", rejectedProposal)
                .entrySet()) {
            String at = where(proposal.getValue());
            edges.add(p0001("dispute proposal " + proposal.getKey() + " -> PROPOSED", "UPDATE bnpl.bnpl_dispute_proposal"
                    + " SET status = 'PROPOSED', decided_by = NULL, decision_reason = NULL, decided_at = NULL" + at));
            String other = proposal.getKey().equals("APPROVED") ? "REJECTED" : "APPROVED";
            edges.add(p0001("dispute proposal " + proposal.getKey() + " -> " + other + " (re-decided by another)",
                    "UPDATE bnpl.bnpl_dispute_proposal SET status = '" + other + "', decided_by = 'OPERATOR:operator-z',"
                            + " decision_reason = 'raw'" + at));
            terminal.put("SELECT status FROM bnpl.bnpl_dispute_proposal" + at, proposal.getKey());
        }

        // ---- the repayment reversal: APPROVED and REJECTED (by an operator who did not propose)
        BnplConditionDatabaseWorld.Purchased repaid = world.plain();
        LocalDate due = repaid.due(2);
        world.serviceOn(due, repaid);
        UUID firstRepayment = world.repayOn(due, repaid, "rev-1", 50_00).repayment().id();
        UUID secondRepayment = world.repayOn(due, repaid, "rev-2", 60_00).repayment().id();
        BnplRepaymentReversal approvedReversal = corrections.propose(due, repaid, firstRepayment,
                corrections.operator());
        corrections.approve(due, repaid, firstRepayment, approvedReversal.id(), corrections.operator());
        BnplRepaymentReversal rejectedReversal = corrections.propose(due, repaid, secondRepayment,
                corrections.operator());
        Actor reverser = corrections.operator();
        corrections.on(due, reverser, uow -> corrections.reversals.reject(uow, repaid.agreement(), secondRepayment,
                rejectedReversal.id(), "applied correctly", reverser, correlation()));
        for (Map.Entry<String, UUID> reversal : Map.of("APPROVED", approvedReversal.id(), "REJECTED",
                rejectedReversal.id()).entrySet()) {
            String at = where(reversal.getValue());
            edges.add(p0001("repayment reversal " + reversal.getKey() + " -> PROPOSED", "UPDATE"
                    + " bnpl.bnpl_repayment_reversal SET status = 'PROPOSED', decided_by = NULL, decided_by_type = NULL,"
                    + " decision_reason = NULL, journal_entry_id = NULL, value_date = NULL" + at));
            String other = reversal.getKey().equals("APPROVED") ? "REJECTED" : "APPROVED";
            edges.add(p0001("repayment reversal " + reversal.getKey() + " -> " + other + " (re-decided by another)",
                    "UPDATE bnpl.bnpl_repayment_reversal SET status = '" + other + "', decided_by = 'operator-z',"
                            + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw', journal_entry_id = "
                            + (other.equals("APPROVED") ? "gen_random_uuid(), value_date = DATE '" + due + "'"
                                    : "NULL, value_date = NULL") + at));
            terminal.put("SELECT status FROM bnpl.bnpl_repayment_reversal" + at, reversal.getKey());
        }

        // ---- the waiver's decision: APPROVED and REJECTED - decided once, never re-decided nor edited
        BnplConditionDatabaseWorld.Purchased late = world.feeBearing();
        world.serviceOn(late.due(2), late);
        LocalDate feeDay = late.due(2).plusDays(5);
        world.serviceOn(feeDay, late);
        BnplFeeAssessment fee = corrections.fees(late).get(0);
        BnplWaiver approvedWaiver = corrections.proposeWaiver(feeDay, late, fee.id(), 2_00, corrections.operator());
        corrections.approveWaiver(feeDay, late, approvedWaiver.id(), corrections.operator());
        BnplWaiver rejectedWaiver = corrections.proposeWaiver(feeDay, late, fee.id(), 1_00, corrections.operator());
        Actor waiverDecider = corrections.operator();
        corrections.on(feeDay, waiverDecider, uow -> corrections.waivers.reject(uow, late.agreement(),
                rejectedWaiver.id(), "not warranted", waiverDecider, correlation()));
        for (Map.Entry<String, UUID> waiver : Map.of("APPROVED", approvedWaiver.id(), "REJECTED", rejectedWaiver.id())
                .entrySet()) {
            String other = waiver.getKey().equals("APPROVED") ? "REJECTED" : "APPROVED";
            edges.add(new Edge("waiver " + waiver.getKey() + " re-decided " + other + " (a second decision)",
                    "INSERT INTO bnpl.bnpl_waiver_decision (id, waiver_id, proposed_by, outcome, decided_by,"
                            + " decided_by_type, decided_at, decision_reason, journal_entry_id, correlation_id) SELECT"
                            + " gen_random_uuid(), w.id, w.proposed_by, '" + other + "', 'operator-z', 'EMPLOYEE',"
                            + " clock_timestamp(), 'raw', " + (other.equals("APPROVED") ? "gen_random_uuid()" : "NULL")
                            + ", 'raw' FROM bnpl.bnpl_waiver w WHERE w.id = '" + waiver.getValue() + "'",
                    "23505", "23505"));
            edges.add(new Edge("waiver decision " + waiver.getKey() + " edited to " + other,
                    "UPDATE bnpl.bnpl_waiver_decision SET outcome = '" + other + "' WHERE waiver_id = '"
                            + waiver.getValue() + "'", "P0001", "42501"));
            terminal.put("SELECT string_agg(outcome, ',') FROM bnpl.bnpl_waiver_decision WHERE waiver_id = '"
                    + waiver.getValue() + "'", waiver.getKey());
        }

        // ---- the terms version: RETIRED (world.feeBearing() retired its predecessor, then itself) and REJECTED
        String retired = scalar("SELECT id::text FROM bnpl.bnpl_terms_version WHERE status = 'RETIRED' AND product ="
                + " 'BNPL_PAY_IN_4' ORDER BY effective_to DESC LIMIT 1");
        BnplTermsAdministration administration = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
        BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow,
                BnplTermsFixtures.payIn4().lateFee(eur(1_00)).build(), "to be rejected", employee(), correlation()));
        inOneTransaction(uow -> administration.reject(uow, proposed.id(), employee(), "not this one", correlation()));
        Map<String, String> versions = Map.of("RETIRED", retired, "REJECTED", proposed.id().value().toString());
        for (Map.Entry<String, String> version : versions.entrySet()) {
            String at = " WHERE id = '" + version.getValue() + "'";
            edges.add(p0001("terms version " + version.getKey() + " -> ACTIVE", "UPDATE bnpl.bnpl_terms_version SET"
                    + " status = 'ACTIVE', effective_to = NULL" + at));
            edges.add(p0001("terms version " + version.getKey() + " -> PROPOSED", "UPDATE bnpl.bnpl_terms_version SET"
                    + " status = 'PROPOSED', decided_by = NULL, decided_by_type = NULL, decided_at = NULL,"
                    + " decision_reason = NULL, effective_from = NULL, effective_to = NULL" + at));
            if (version.getKey().equals("REJECTED")) {
                edges.add(p0001("terms version REJECTED -> RETIRED", "UPDATE bnpl.bnpl_terms_version SET"
                        + " status = 'RETIRED'" + at));
            }
            terminal.put("SELECT status FROM bnpl.bnpl_terms_version" + at, version.getKey());
        }

        // ---- the offer: ACCEPTED (the financed purchase's), DECLINED and EXPIRED
        String acceptedOffer = scalar("SELECT offer_id::text FROM bnpl.purchase" + financedPurchase);
        BnplAcceptanceDatabaseTest.Offered declining = BnplAcceptanceDatabaseTest.offered();
        Boolean declined = inOneTransaction(uow -> BnplConditionDatabaseWorld.OFFERS.decline(uow, declining.offer()));
        assertThat(declined).isTrue();
        BnplAcceptanceDatabaseTest.Offered expiring = BnplAcceptanceDatabaseTest.offered();
        Duration pastExpiry = BnplClockFrame.frameTo(Instant.now().plus(Duration.ofDays(2)));
        Boolean expired = BnplClockFrame.inOneTransaction(pastExpiry,
                uow -> BnplConditionDatabaseWorld.OFFERS.expire(uow, expiring.offer()));
        assertThat(expired).isTrue();
        Map<String, String> offers = Map.of("ACCEPTED", acceptedOffer, "DECLINED", declining.offer().value().toString(),
                "EXPIRED", expiring.offer().value().toString());
        for (Map.Entry<String, String> offer : offers.entrySet()) {
            String at = " WHERE id = '" + offer.getValue() + "'";
            edges.add(p0001("offer " + offer.getKey() + " -> OFFERED", "UPDATE bnpl.bnpl_offer SET status = 'OFFERED',"
                    + " next_step_at = clock_timestamp()" + at));
            for (String to : List.of("ACCEPTED", "DECLINED", "EXPIRED")) {
                if (!to.equals(offer.getKey())) {
                    edges.add(p0001("offer " + offer.getKey() + " -> " + to, "UPDATE bnpl.bnpl_offer SET status = '"
                            + to + "'" + at));
                }
            }
            terminal.put("SELECT status FROM bnpl.bnpl_offer" + at, offer.getKey());
        }

        // ---- the application: OFFERED (the declined offer's), DECLINED and WITHDRAWN
        String offeredApplication = scalar("SELECT application_id::text FROM bnpl.bnpl_offer WHERE id = '"
                + declining.offer().value() + "'");
        Map<String, String> applications = new LinkedHashMap<>();
        applications.put("OFFERED", offeredApplication);
        applications.put("DECLINED", closedApplication(BnplApplicationStatus.DECLINED));
        applications.put("WITHDRAWN", closedApplication(BnplApplicationStatus.WITHDRAWN));
        for (Map.Entry<String, String> application : applications.entrySet()) {
            String at = " WHERE id = '" + application.getValue() + "'";
            for (String to : List.of("SUBMITTED", "AWAITING_DECISION", "OFFERED", "DECLINED", "WITHDRAWN")) {
                if (!to.equals(application.getKey())) {
                    edges.add(p0001("application " + application.getKey() + " -> " + to, "UPDATE bnpl.bnpl_application"
                            + " SET status = '" + to + "'" + at));
                }
            }
            edges.add(p0001("application " + application.getKey() + " moves its permit", "UPDATE"
                    + " bnpl.bnpl_application SET next_step_at = clock_timestamp()" + at));
            terminal.put("SELECT status FROM bnpl.bnpl_application" + at, application.getKey());
        }

        // ---- every edge, every role: collected, then judged at once
        List<String> violations = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); Connection application = DatabaseRoles.application()) {
            migrator.setAutoCommit(false);
            application.setAutoCommit(false);
            for (Edge edge : edges) {
                attempt(migrator, "migrator", edge.sql(), edge.migratorState(), edge.name(), violations);
                attempt(application, "application", edge.sql(), edge.applicationState(), edge.name(), violations);
            }
        }
        assertThat(violations).as(edges.size() + " edges x 2 roles").isEmpty();
        assertThat(edges).hasSizeGreaterThan(50);
        for (Map.Entry<String, String> row : terminal.entrySet()) {
            assertThat(scalar(row.getKey())).as(row.getKey()).isEqualTo(row.getValue());
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Edge p0001(String name, String sql) {
        return new Edge(name, sql, "P0001", "P0001");
    }

    private static String where(UUID id) {
        return " WHERE id = '" + id + "'";
    }

    /** {@code sql} as {@code role}, rolled back whatever happened; a refusal other than {@code expected} is a violation. */
    private static void attempt(Connection connection, String role, String sql, String expected, String edge,
            List<String> violations) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int moved = statement.executeUpdate(sql);
            violations.add(edge + " [" + role + "]: ADMITTED (" + moved + " row(s)) - expected " + expected);
        } catch (SQLException refused) {
            if (!expected.equals(refused.getSQLState())) {
                violations.add(edge + " [" + role + "]: " + refused.getSQLState() + " - expected " + expected + " ("
                        + refused.getMessage().lines().findFirst().orElse("") + ")");
            }
        } finally {
            connection.rollback();
        }
    }

    private static BnplDispute open(BnplConditionDatabaseWorld world, BnplDisputes disputes,
            BnplConditionDatabaseWorld.Purchased bought, long minor) {
        return BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(1)), uow -> disputes.open(
                uow, bought.agreement(), bought.party(), new BnplDisputes.OpenRequest("open", eur(minor),
                        BnplDisputeReason.NOT_RECEIVED, "it never came"), customer(bought.party()), correlation()))
                .dispute();
    }

    private static void respond(BnplConditionDatabaseWorld world, BnplDisputes disputes, BnplDispute dispute,
            BnplConditionDatabaseWorld.Purchased bought, BnplDisputes.Decision decision) {
        BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(2)), uow -> disputes.respond(uow,
                dispute.id(), bought.merchant(), new BnplDisputes.Response(decision, "we shipped it", "key-under-test",
                        BnplKeyIssuer.KEY_ISSUER), new Actor(bought.merchant().toString(), ActorType.MERCHANT),
                correlation()));
    }

    private static BnplDisputes.Acted resolve(BnplConditionDatabaseWorld world, BnplDisputes disputes,
            BnplDispute dispute, BnplConditionDatabaseWorld.Purchased bought, Actor operator, BnplDisputeStatus outcome) {
        return BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(3)), uow -> disputes.resolve(
                uow, dispute.id(), outcome, "on the evidence", operator, correlation()));
    }

    /** An application closed {@code to} through the store - submitted, awaiting, then closed. */
    private static String closedApplication(BnplApplicationStatus to) {
        UUID party = UUID.randomUUID();
        BnplApplicationStore.NewApplication row = new BnplApplicationStore.NewApplication(BnplApplicationId.next(IDS),
                party, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), inOneTransaction(uow -> TERMS_STORE.active(uow, BnplProduct.BNPL_PAY_IN_4))
                        .orElseThrow(), customer(party), correlation());
        JdbcBnplApplicationStore applications = new JdbcBnplApplicationStore();
        inOneTransaction(uow -> {
            applications.insert(uow, row);
            applications.transition(uow, row.id(), BnplApplicationStatus.SUBMITTED,
                    BnplApplicationStatus.AWAITING_DECISION);
            assertThat(applications.close(uow, row.id(), to, Optional.empty())).isTrue();
            return null;
        });
        return row.id().value().toString();
    }
}
