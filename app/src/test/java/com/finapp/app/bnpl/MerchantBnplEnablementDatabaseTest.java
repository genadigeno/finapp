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
    @Autowired private com.finapp.platform.audit.AuditWriter<Connection> auditWriter;

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

    // ------------------------------------------------------------------ the Phase 12 -> 13 gate (A5-F5, A3-M1)

    private static final String CARD = "card 4111 1111 1111 1111 on file";

    @Test
    @DisplayName("the Phase 12 -> 13 gate's A5-F5: merchant V012's twin agrees with InstrumentShapes over a corpus; every"
            + " BNPL reason column refuses an instrument shape for a raw writer past the domain - the proposal, the"
            + " decision, the withdrawal and the fee schedule's history; the door answers 422, never a 5xx")
    void everyBnplReasonColumnRefusesAnInstrumentShape() throws Exception {
        List<String> corpus = List.of(CARD, "card 4111-1111-1111-1111", "4111111111111111", "4111 1111 1111 1112",
                "pay to GB82 WEST 1234 5698 7654 32", "GB82WEST12345698765432", "GB82 WEST 1234 5698 7654 33",
                "contract 0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b signed", "FY26 plan 2027 will need more",
                "invoice 2026-10-22 order 12345", "merchant contract signed");
        try (Connection app = DatabaseRoles.application();
                PreparedStatement verdict = app.prepareStatement("SELECT merchant.holds_card_number_shape(?),"
                        + " merchant.holds_account_identifier_shape(?), merchant.holds_instrument_shape(?)")) {
            for (String text : corpus) {
                verdict.setString(1, text);
                verdict.setString(2, text);
                verdict.setString(3, text);
                try (ResultSet row = verdict.executeQuery()) {
                    row.next();
                    boolean card = com.finapp.sharedkernel.security.InstrumentShapes.holdsCardNumber(text);
                    boolean account = com.finapp.sharedkernel.security.InstrumentShapes.holdsAccountIdentifier(text);
                    assertThat(row.getBoolean(1)).as("card verdict for [%s]", text).isEqualTo(card);
                    assertThat(row.getBoolean(2)).as("account verdict for [%s]", text).isEqualTo(account);
                    assertThat(row.getBoolean(3)).isEqualTo(card || account);
                }
            }
        }

        MerchantId merchant = merchant();
        assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), "INSERT INTO merchant.merchant_bnpl_enablement"
                + " (id, merchant_id, status, proposed_by, proposed_at, proposal_reason) VALUES (?, ?, 'PROPOSED', 'raw',"
                + " now(), ?)", UUID.randomUUID(), merchant.value(), CARD))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("merchant_bnpl_enablement_proposal_reason_no_instrument_shape");
        Actor proposer = operator();
        BnplEnablement proposed = act(proposer, uow -> enablements.propose(uow, merchant, "contract", proposer,
                correlation()));
        assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), "UPDATE merchant.merchant_bnpl_enablement SET"
                + " status = 'REJECTED', decided_by = 'raw', decision_reason = ? WHERE id = ?", CARD,
                proposed.id().value()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("merchant_bnpl_enablement_decision_reason_no_instrument_shape");
        Actor approver = operator();
        act(approver, uow -> enablements.approve(uow, merchant, proposed.id(), "reviewed", approver, correlation()));
        assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), "UPDATE merchant.merchant_bnpl_enablement SET"
                + " status = 'WITHDRAWN', withdrawn_by = 'raw', withdrawal_reason = ? WHERE id = ?", CARD,
                proposed.id().value()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("merchant_bnpl_enablement_withdrawal_reason_no_instrument_shape");
        FeeSchedule schedule = schedule(operator(), RefundFeePolicy.RETURNED);
        assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), "INSERT INTO"
                + " merchant.merchant_bnpl_fee_schedule_event (merchant_id, product, to_fee_schedule_id, reason, actor_id,"
                + " actor_type, occurred_at) VALUES (?, 'BNPL_PAY_IN_4', ?, ?, 'raw', 'EMPLOYEE', now())",
                merchant.value(), schedule.id().value(), "pay to GB82 WEST 1234 5698 7654 32"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("merchant_bnpl_fee_schedule_event_reason_no_instrument_shape");
        assertThat(status(proposed)).as("the raw writers changed nothing").isEqualTo("ACTIVE");

        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                SecurityContext.Scope who = SecurityContext.enter(operator())) {
            assertThatThrownBy(() -> desk.propose(UUID.randomUUID().toString(), merchant().value().toString(), CARD))
                    .isInstanceOfSatisfying(com.finapp.platform.api.ApiException.class, refused -> {
                        assertThat(refused.errorCode())
                                .isEqualTo(com.finapp.platform.api.PlatformErrorCode.VALIDATION_FAILED);
                        assertThat(refused.clientDetail().orElseThrow()).doesNotContain("4111");
                    });
        }
    }

    @Test
    @DisplayName("the Phase 12 -> 13 gate's A3-M1: an instance whose JVM clock runs two hours past a version's"
            + " effective_from still pins the version in force on the DATABASE's clock, stamped with the database's"
            + " instant; and V012 refuses, for every writer, a pin whose instant is outside its own transaction's span")
    void thePinIsResolvedAndStampedOnTheDatabaseClock() throws Exception {
        MerchantId merchant = enabledMerchant();
        Actor operator = operator();
        FeeSchedule schedule = schedule(operator, RefundFeePolicy.RETURNED);
        act(operator, uow -> pricing.assign(uow, merchant, MerchantBnplProduct.BNPL_PAY_IN_4, schedule.id(),
                "contract", operator, correlation()));
        java.time.Instant databaseNow = databaseNow();
        // Version 2: a lower rate, in force an hour from the database's now.
        act(operator, uow -> feeSchedules.addVersion(uow, schedule.id(), new FeeSchedules.NewVersion(
                FeeRate.of(new BigDecimal("0.0100")), Money.ofMinorUnits(0, EUR), RoundingPolicy.HALF_EVEN,
                RefundFeePolicy.RETURNED, Optional.of(databaseNow.plusSeconds(3_600)), "the next rate")));
        UUID inForce = scalarUuid("SELECT id FROM merchant.fee_schedule_version WHERE fee_schedule_id = ? AND version"
                + " = 1", schedule.id().value());
        Clock skewed = Clock.offset(Clock.systemUTC(), java.time.Duration.ofHours(2));
        BnplPricing onASkewedInstance = new BnplPricing(new com.finapp.merchant.JdbcBnplPricingStore(),
                new com.finapp.merchant.JdbcBnplEnablementStore(), new com.finapp.merchant.JdbcMerchantStore(),
                new com.finapp.merchant.JdbcFeeScheduleStore(), auditWriter, IDS, skewed);
        UUID purchase = UUID.randomUUID();
        Actor customer = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);

        com.finapp.merchant.FeeAssessment split = act(customer, uow -> onASkewedInstance.pin(uow, merchant,
                MerchantBnplProduct.BNPL_PAY_IN_4, purchase, Money.ofMinorUnits(10_000, EUR), customer));

        assertThat(split.version().value()).as("version 1, in force on the database's clock").isEqualTo(inForce);
        assertThat(split.fee()).isEqualTo(Money.ofMinorUnits(299, EUR));
        assertThat(scalarUuid("SELECT fee_schedule_version_id FROM merchant.bnpl_fee_pin WHERE purchase_id = ?",
                purchase)).isEqualTo(inForce);
        assertThat(count("SELECT count(*) FROM merchant.bnpl_fee_pin WHERE purchase_id = ?::uuid AND pinned_at"
                + " BETWEEN now() - interval '5 minutes' AND now() + interval '5 minutes'", purchase.toString()))
                .as("stamped with the database's instant, not the skewed instance's").isEqualTo(1);

        for (String instant : List.of("now() + interval '1 hour'", "now() - interval '1 hour'")) {
            assertThatThrownBy(() -> rawUpdate(DatabaseRoles.application(), "INSERT INTO merchant.bnpl_fee_pin"
                    + " (purchase_id, merchant_id, product, fee_schedule_version_id, gross_amount_minor, gross_currency,"
                    + " gross_scale, pinned_at, pinned_by) VALUES (?, ?, 'BNPL_PAY_IN_4', ?, 10000, 'EUR', 2, " + instant
                    + ", 'raw')", UUID.randomUUID(), merchant.value(), inForce))
                    .as(instant).isInstanceOf(SQLException.class)
                    .hasMessageContaining("its own transaction's on the database clock");
        }
    }

    @Test
    @DisplayName("A3-M1's other half: a version created to take effect IMMEDIATELY, stamped by an instance an hour ahead of"
            + " the database, is in force from its commit - the resolution and V012's pin trigger both read it so - and"
            + " the pin is stamped with the database's instant")
    void anImmediateVersionStampedAheadIsInForceAtOnce() throws Exception {
        MerchantId merchant = pricedMerchant();
        UUID schedule = scalarUuid("SELECT fee_schedule_id FROM merchant.merchant_bnpl_fee_schedule WHERE merchant_id = ?"
                + " AND product = 'BNPL_PAY_IN_4'", merchant.value());
        UUID immediate = IDS.next();
        // Version 2: effective_from = created_at (immediate), both an hour past the database's clock - a fast instance's.
        rawUpdate(DatabaseRoles.application(), "INSERT INTO merchant.fee_schedule_version (id, fee_schedule_id, version, rate,"
                + " fixed_amount_minor, fixed_currency, fixed_scale, rounding_policy, refund_fee_policy, effective_from,"
                + " created_at, created_by) SELECT ?, ?, 2, 0.0100, 0, 'EUR', 2, 'HALF_EVEN', 'RETURNED',"
                + " now() + interval '1 hour', now() + interval '1 hour', 'a fast instance'", immediate, schedule);
        BnplPurchaseId purchase = BnplPurchaseId.next(IDS);

        MerchantFeeSplit split = act(operator(), uow -> bnplMerchants.pinFee(uow, merchant.value(),
                BnplProduct.BNPL_PAY_IN_4, purchase, Money.ofMinorUnits(10_000, EUR)));

        assertThat(split.fee()).as("priced by the immediate version's 1.00 %").isEqualTo(Money.ofMinorUnits(100, EUR));
        assertThat(scalarUuid("SELECT fee_schedule_version_id FROM merchant.bnpl_fee_pin WHERE purchase_id = ?",
                purchase.value())).isEqualTo(immediate);
        assertThat(count("SELECT count(*) FROM merchant.bnpl_fee_pin WHERE purchase_id = ?::uuid AND pinned_at"
                + " < now() + interval '5 minutes'", purchase.value().toString()))
                .as("stamped with the database's instant, not the version's").isEqualTo(1);
    }

    private static java.time.Instant databaseNow() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT clock_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getObject(1, java.time.OffsetDateTime.class).toInstant();
        }
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
