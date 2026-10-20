package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.MerchantFeeSplit;
import com.finapp.merchant.BnplEnablement;
import com.finapp.merchant.BnplEnablementStatus;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeSchedule;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantBnplProduct;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Merchant BNPL enablement, its BNPL fee schedule and the fee pin against the real schema (`P12-TSK-006`; OQ-8,
 * {@code INV-AUD-04}, {@code INV-MER-03}, {@code INV-MER-04}, {@code INV-BNPL-04}): every rank of the four eyes - the
 * domain, the approval's own {@code proposed_by <> ?}, `V010`'s {@code CHECK} - and the counted races the backlog names:
 * ten proposers leave one live proposal and nine {@code Pending}; an approval and a rejection racing leave exactly one
 * decision; ten pinners for one purchase leave one pin and never a second version; a suspension or a withdrawal offers
 * nothing; the pin is never updated, deleted or truncated by any role. Each act in a transaction of its own, on the
 * application role, as the doors run them.
 */
@DateShifted
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest
@DisplayName("merchant BNPL enablement and pricing against the schema (P12-TSK-006)")
class MerchantBnplEnablementDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private BnplMerchants bnplMerchants;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private MerchantAdministration administration;
    @Autowired private MerchantBnplDesk desk;

    @Test
    @DisplayName("a merchant is BNPL-enabled only by two people: proposed, then approved by a second operator")
    void enablementIsFourEyes() throws Exception {
        MerchantId merchant = merchant();
        Actor proposer = operator();
        Actor approver = operator();
        BnplEnablement proposed = act(proposer, uow -> enablements.propose(uow, merchant, "contract", proposer,
                correlation()));
        assertThat(standing(merchant)).isEqualTo(BnplMerchants.Standing.NOT_ENABLED);

        BnplEnablement approved = act(approver, uow -> enablements.approve(uow, merchant, proposed.id(), "reviewed",
                approver, correlation())).enablement();

        assertThat(approved.status()).isEqualTo(BnplEnablementStatus.ACTIVE);
        assertThat(approved.decidedAt().orElseThrow()).isAfterOrEqualTo(approved.proposedAt());
        assertThat(standing(merchant)).as("enabled, not yet priced").isEqualTo(BnplMerchants.Standing.NOT_PRICED);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND outcome = 'SUCCEEDED'"
                + " AND operation IN ('merchant.BnplEnablementProposed', 'merchant.BnplEnablementApproved')",
                proposed.id().value().toString())).isEqualTo(2);
    }

    @Test
    @DisplayName("the proposer cannot approve - refused at the door (audited FAILED in its own transaction), by the"
            + " approval statement and by V010's CHECK for every writer")
    void theProposerCannotApprove() throws Exception {
        MerchantId merchant = merchant();
        Actor proposer = operator();
        BnplEnablement proposed = act(proposer, uow -> enablements.propose(uow, merchant, "contract", proposer,
                correlation()));

        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                SecurityContext.Scope who = SecurityContext.enter(proposer)) {
            assertThatThrownBy(() -> desk.approve(UUID.randomUUID().toString(), merchant.value().toString(),
                    proposed.id().value().toString(), "my own"))
                    .hasMessageContaining("proposer");
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'merchant.BnplEnablementApprovalRefused' AND outcome = 'FAILED' AND target_id = ?",
                proposed.id().value().toString())).as("recorded FAILED, once (R4)").isEqualTo(1);
        assertThat(status(proposed)).isEqualTo("PROPOSED");

        // The statement's own predicate: the conditional approval by the proposer writes nothing.
        assertThat(rawUpdate(DatabaseRoles.application(), "UPDATE merchant.merchant_bnpl_enablement SET status ="
                + " 'ACTIVE', decided_by = ?, decision_reason = 'raw' WHERE id = ? AND status = 'PROPOSED'"
                + " AND proposed_by <> ?", proposer.id(), proposed.id().value(), proposer.id())).isZero();
        // And V010's CHECK for a writer that skips both: refused.
        assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), "UPDATE merchant.merchant_bnpl_enablement"
                + " SET status = 'ACTIVE', decided_by = ?, decision_reason = 'raw' WHERE id = ?", proposer.id(),
                proposed.id().value()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("merchant_bnpl_enablement_approver_is_not_proposer");
        assertThat(status(proposed)).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("ten proposers for one merchant: one live proposal, nine Pending")
    void oneLiveProposalPerMerchant() throws Exception {
        MerchantId merchant = merchant();
        List<Object> outcomes = tenAtOnce(() -> {
            Actor proposer = operator();
            try {
                return act(proposer, uow -> enablements.propose(uow, merchant, "contract", proposer, correlation()));
            } catch (BnplEnablements.Pending pending) {
                return pending;
            }
        });
        assertThat(outcomes.stream().filter(BnplEnablement.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BnplEnablements.Pending.class::isInstance)).hasSize(9);
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_enablement WHERE merchant_id = ?::uuid",
                merchant.value().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("five approvers and five rejecters racing on one proposal leave exactly one decision")
    void approvalAndRejectionLeaveExactlyOne() throws Exception {
        MerchantId merchant = merchant();
        Actor proposer = operator();
        BnplEnablement proposed = act(proposer, uow -> enablements.propose(uow, merchant, "contract", proposer,
                correlation()));
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
        List<Object> outcomes = tenAtOnce(() -> {
            Actor decider = operator();
            boolean approve = turn.getAndIncrement() % 2 == 0;
            try {
                return act(decider, uow -> approve
                        ? enablements.approve(uow, merchant, proposed.id(), "yes", decider, correlation())
                        : enablements.reject(uow, merchant, proposed.id(), "no", decider, correlation()));
            } catch (BnplEnablements.NotOpen notOpen) {
                return notOpen;
            }
        });
        assertThat(outcomes.stream().filter(BnplEnablements.Decided.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BnplEnablements.NotOpen.class::isInstance)).hasSize(9);
        assertThat(status(proposed)).isIn("ACTIVE", "REJECTED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation IN"
                + " ('merchant.BnplEnablementApproved', 'merchant.BnplEnablementRejected')",
                proposed.id().value().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("a RETAINED schedule is refused for BNPL - by the door and by V010's trigger for every writer - and a"
            + " pin naming a retaining version is refused")
    void aRetainedScheduleIsRefusedForBnpl() throws Exception {
        MerchantId merchant = enabledMerchant();
        Actor operator = operator();
        FeeSchedule retained = schedule(operator, RefundFeePolicy.RETAINED);
        assertThatThrownBy(() -> act(operator, uow -> pricing.assign(uow, merchant, MerchantBnplProduct.BNPL_PAY_IN_4,
                retained.id(), "wrong", operator, correlation()))).isInstanceOf(BnplPricing.ScheduleNotReturning.class);
        assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), "INSERT INTO merchant.merchant_bnpl_fee_schedule"
                + " (merchant_id, product, fee_schedule_id, assigned_at, assigned_by) VALUES (?, 'BNPL_PAY_IN_4', ?,"
                + " now(), 'raw')", merchant.value(), retained.id().value()))
                .isInstanceOf(SQLException.class).hasMessageContaining("retains");
        assertThat(standing(merchant)).isEqualTo(BnplMerchants.Standing.NOT_PRICED);

        FeeSchedule returning = schedule(operator, RefundFeePolicy.RETURNED);
        act(operator, uow -> pricing.assign(uow, merchant, MerchantBnplProduct.BNPL_PAY_IN_4, returning.id(), "ok",
                operator, correlation()));
        UUID retainedVersion = scalarUuid("SELECT id FROM merchant.fee_schedule_version WHERE fee_schedule_id = ?",
                retained.id().value());
        assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), "INSERT INTO merchant.bnpl_fee_pin"
                + " (purchase_id, merchant_id, product, fee_schedule_version_id, gross_amount_minor, gross_currency,"
                + " gross_scale, pinned_at, pinned_by) VALUES (?, ?, 'BNPL_PAY_IN_4', ?, 10000, 'EUR', 2, now(), 'raw')",
                UUID.randomUUID(), merchant.value(), retainedVersion))
                .isInstanceOf(SQLException.class).hasMessageContaining("returns its fee");
    }

    @Test
    @DisplayName("ten pinners for one purchase: one pin and nine AlreadyPinned - never a second version; the split"
            + " conserves the gross")
    void theFeePinIsBornOncePerPurchase() throws Exception {
        MerchantId merchant = pricedMerchant();
        BnplPurchaseId purchase = BnplPurchaseId.next(IDS);
        Money gross = Money.ofMinorUnits(10_000, EUR);
        List<Object> outcomes = tenAtOnce(() -> {
            Actor customer = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
            try {
                return act(customer, uow -> bnplMerchants.pinFee(uow, merchant.value(), BnplProduct.BNPL_PAY_IN_4,
                        purchase, gross));
            } catch (BnplMerchants.AlreadyPinned pinned) {
                return pinned;
            }
        });
        List<MerchantFeeSplit> splits = outcomes.stream().filter(MerchantFeeSplit.class::isInstance)
                .map(MerchantFeeSplit.class::cast).toList();
        assertThat(splits).hasSize(1);
        assertThat(outcomes.stream().filter(BnplMerchants.AlreadyPinned.class::isInstance)).hasSize(9);
        assertThat(splits.get(0).fee().plus(splits.get(0).net())).isEqualTo(gross);
        assertThat(count("SELECT count(*) FROM merchant.bnpl_fee_pin WHERE purchase_id = ?::uuid",
                purchase.value().toString())).isEqualTo(1);

        // The return telescopes under the pin, read back from the row.
        Money first = act(operator(), uow -> bnplMerchants.returnedFee(uow, purchase, Money.ofMinorUnits(0, EUR),
                Money.ofMinorUnits(3_333, EUR)));
        Money rest = act(operator(), uow -> bnplMerchants.returnedFee(uow, purchase, Money.ofMinorUnits(3_333, EUR),
                Money.ofMinorUnits(6_667, EUR)));
        assertThat(first.plus(rest)).isEqualTo(splits.get(0).fee());
    }

    @Test
    @DisplayName("the pin is never updated, deleted or truncated by any role - the application's grant or V004's trigger")
    void thePinIsNeverUpdatedOrDeletedByAnyRole() throws Exception {
        MerchantId merchant = pricedMerchant();
        BnplPurchaseId purchase = BnplPurchaseId.next(IDS);
        act(operator(), uow -> bnplMerchants.pinFee(uow, merchant.value(), BnplProduct.BNPL_PAY_IN_4, purchase,
                Money.ofMinorUnits(10_000, EUR)));
        for (String sql : List.of(
                "UPDATE merchant.bnpl_fee_pin SET gross_amount_minor = 1 WHERE purchase_id = ?",
                "DELETE FROM merchant.bnpl_fee_pin WHERE purchase_id = ?")) {
            assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), sql, purchase.value()))
                    .as("the application role: " + sql).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> rawUpdate(DatabaseRoles.migrator(), sql, purchase.value()))
                    .as("the migrator, bound by the trigger: " + sql)
                    .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
        }
        assertThatThrownBy(() -> rawUpdate(DatabaseRoles.migrator(), "TRUNCATE merchant.bnpl_fee_pin"))
                .isInstanceOf(SQLException.class).hasMessageContaining("never truncated");
        assertThat(count("SELECT count(*) FROM merchant.bnpl_fee_pin WHERE purchase_id = ?::uuid",
                purchase.value().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("a suspended merchant is not enabled: a suspension waits for a standing reader holding the merchant row,"
            + " then the standing and the pin refuse while the enablement stands")
    void aSuspendedMerchantIsNotEnabled() throws Exception {
        MerchantId merchant = pricedMerchant();
        assertThat(standing(merchant)).isEqualTo(BnplMerchants.Standing.ENABLED);
        try (Connection reader = DatabaseRoles.application()) {
            reader.setAutoCommit(false);
            assertThat(bnplMerchants.standing(reader, merchant.value(), BnplProduct.BNPL_PAY_IN_4))
                    .isEqualTo(BnplMerchants.Standing.ENABLED);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> suspension = pool.submit(() -> act(operator(),
                        uow -> administration.suspend(uow, merchant, "investigation")));
                awaitBlockedOn("merchant.merchant", "FOR UPDATE");
                assertThat(suspension.isDone()).as("the suspension waits for the standing's share lock").isFalse();
                reader.commit();
                suspension.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(standing(merchant)).isEqualTo(BnplMerchants.Standing.NOT_TRADING);
        assertThatThrownBy(() -> act(operator(), uow -> bnplMerchants.pinFee(uow, merchant.value(),
                BnplProduct.BNPL_PAY_IN_4, BnplPurchaseId.next(IDS), Money.ofMinorUnits(10_000, EUR))))
                .isInstanceOf(BnplMerchants.NotOffered.class);
        act(operator(), uow -> administration.reinstate(uow, merchant, "cleared"));
        assertThat(standing(merchant)).isEqualTo(BnplMerchants.Standing.ENABLED);
    }

    @Test
    @DisplayName("a withdrawn enablement offers nothing - and the withdrawal waits for a standing reader holding the row")
    void aWithdrawnEnablementOffersNothing() throws Exception {
        MerchantId merchant = pricedMerchant();
        BnplEnablement active = act(operator(), uow -> enablements.live(uow, merchant)).orElseThrow();
        Actor withdrawer = operator();
        try (Connection reader = DatabaseRoles.application()) {
            reader.setAutoCommit(false);
            assertThat(bnplMerchants.standing(reader, merchant.value(), BnplProduct.BNPL_PAY_IN_4))
                    .isEqualTo(BnplMerchants.Standing.ENABLED);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<BnplEnablements.Decided> withdrawal = pool.submit(() -> act(withdrawer, uow ->
                        enablements.withdraw(uow, merchant, active.id(), "fraud signals", withdrawer, correlation())));
                awaitBlockedOn("merchant_bnpl_enablement", "FOR UPDATE");
                assertThat(withdrawal.isDone()).as("the withdrawal waits for the share lock").isFalse();
                reader.commit();
                assertThat(withdrawal.get(30, TimeUnit.SECONDS).enablement().status())
                        .isEqualTo(BnplEnablementStatus.WITHDRAWN);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(standing(merchant)).isEqualTo(BnplMerchants.Standing.NOT_ENABLED);
        assertThatThrownBy(() -> act(operator(), uow -> bnplMerchants.pinFee(uow, merchant.value(),
                BnplProduct.BNPL_PAY_IN_4, BnplPurchaseId.next(IDS), Money.ofMinorUnits(10_000, EUR))))
                .isInstanceOfSatisfying(BnplMerchants.NotOffered.class,
                        refused -> assertThat(refused.standing()).isEqualTo(BnplMerchants.Standing.NOT_ENABLED));
        assertThat(rawCount("SELECT count(*) FROM merchant.bnpl_fee_pin WHERE merchant_id = ?", merchant.value()))
                .isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private MerchantId pricedMerchant() throws Exception {
        MerchantId merchant = enabledMerchant();
        Actor operator = operator();
        FeeSchedule schedule = schedule(operator, RefundFeePolicy.RETURNED);
        act(operator, uow -> pricing.assign(uow, merchant, MerchantBnplProduct.BNPL_PAY_IN_4, schedule.id(),
                "contract", operator, correlation()));
        return merchant;
    }

    private MerchantId enabledMerchant() throws Exception {
        MerchantId merchant = merchant();
        Actor proposer = operator();
        Actor approver = operator();
        BnplEnablement proposed = act(proposer, uow -> enablements.propose(uow, merchant, "contract", proposer,
                correlation()));
        act(approver, uow -> enablements.approve(uow, merchant, proposed.id(), "reviewed", approver, correlation()));
        return merchant;
    }

    private FeeSchedule schedule(Actor operator, RefundFeePolicy policy) throws Exception {
        return act(operator, uow -> {
            FeeSchedule created = feeSchedules.create(uow, "bnpl " + UUID.randomUUID(), EUR);
            feeSchedules.addVersion(uow, created.id(), new FeeSchedules.NewVersion(FeeRate.of(new BigDecimal("0.0299")),
                    Money.ofMinorUnits(0, EUR), RoundingPolicy.HALF_EVEN, policy, Optional.empty(), "bnpl pricing"));
            return created;
        });
    }

    private static MerchantId merchant() throws SQLException {
        MerchantId id = MerchantId.next(IDS);
        rawUpdate(DatabaseRoles.application(), "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                + " settlement_currency, status, created_at, status_changed_at) VALUES (?, ?, 'Acme GmbH', 'Acme', 'EUR',"
                + " 'ACTIVE', now(), now())", id.value(), UUID.randomUUID());
        return id;
    }

    private BnplMerchants.Standing standing(MerchantId merchant) throws Exception {
        return act(operator(), uow -> bnplMerchants.standing(uow, merchant.value(), BnplProduct.BNPL_PAY_IN_4));
    }

    /** {@code work} as {@code actor}, in one committed transaction on the application role; a throw rolls it back. */
    private static <R> R act(Actor actor, Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                SecurityContext.Scope who = SecurityContext.enter(actor);
                Connection uow = DatabaseRoles.application()) {
            uow.setAutoCommit(false);
            try {
                R result = work.apply(uow);
                uow.commit();
                return result;
            } catch (RuntimeException refused) {
                uow.rollback();
                throw refused;
            }
        }
    }

    private static Actor operator() {
        return new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static String status(BnplEnablement enablement) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT status FROM merchant.merchant_bnpl_enablement WHERE id = ?")) {
            select.setObject(1, enablement.id().value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static int rawUpdate(Connection connection, String sql, Object... arguments) throws SQLException {
        try (connection; PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static long count(String sql, String parameter) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setString(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long rawCount(String sql, Object parameter) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setObject(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static UUID scalarUuid(String sql, Object parameter) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setObject(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    /** The {@code P0-TST-004} idiom: the waiting side observed Lock-waiting, never assumed. */
    private static void awaitBlockedOn(String table, String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity"
                        + " WHERE wait_event_type = 'Lock' AND query LIKE '%" + table + "%' AND query LIKE '%"
                        + marker + "%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError("nothing ever waited on " + table + " - the share lock is not held");
    }

    private static List<Object> tenAtOnce(Callable<Object> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> pending = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return attempt.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
