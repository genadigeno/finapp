package com.finapp.bnpl;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The correction database suites' world (`P12-TSK-024`): {@link BnplConditionDatabaseWorld} - bnpl's JDBC stores on a
 * moved database clock, the scripted ports, the in-memory book behind one lock, T-pur, T-svc, T-rep, T-ref - with T-rev and
 * T-wvr over {@link JdbcBnplCorrectionStore}, a credit port that records each L0 it takes, a capital port whose headroom a
 * case sets, and operators of parties of their own (and one of the customer's).
 *
 * <p>Written by `P12-TSK-024` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
final class BnplCorrectionDatabaseWorld {

    static final JdbcBnplCorrectionStore CORRECTIONS = new JdbcBnplCorrectionStore();

    final BnplConditionDatabaseWorld base = new BnplConditionDatabaseWorld();
    /** Whose party each operator is. */
    final Map<String, UUID> operatorParties = new ConcurrentHashMap<>();
    /** Every L0 the corrections took, in order (the party locked). */
    final List<UUID> profilesLocked = new CopyOnWriteArrayList<>();
    /** The capital headroom the capital port answers. */
    volatile Money headroom = Money.ofMinorUnits(1_000_000_00L, BnplTermsFixtures.EUR);

    final BnplCreditDecisions credit = new BnplCreditDecisions() {
        @Override
        public UUID open(Connection unitOfWork, UUID party, CreditRequest request, Actor actor,
                CorrelationId correlation) {
            return base.ports.open(unitOfWork, party, request, actor, correlation);
        }

        @Override
        public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                CorrelationId correlation) {
            base.ports.cancel(unitOfWork, decisionRequest, party, actor, correlation);
        }

        @Override
        public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
            return base.ports.reasonTexts(unitOfWork, decisionRequest);
        }

        @Override
        public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
            return base.ports.decisionFor(unitOfWork, decisionRequest, party);
        }

        @Override
        public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
            return base.ports.requestOf(unitOfWork, decision);
        }

        @Override
        public boolean lockExposure(Connection unitOfWork, UUID party) {
            profilesLocked.add(party);
            return true;
        }

        @Override
        public Consumption consume(Connection unitOfWork, UUID decision, BnplAgreementId agreement) {
            return base.ports.consume(unitOfWork, decision, agreement);
        }
    };

    final BnplCapital capital = (uow, currency) -> new BnplHeadroom(currency, headroom);
    final BnplRepaymentReversals reversals = new BnplRepaymentReversals(CORRECTIONS,
            BnplConditionDatabaseWorld.SERVICING, BnplConditionDatabaseWorld.PURCHASES, credit, capital, base.book,
            (uow, actor) -> Optional.ofNullable(operatorParties.get(actor.id())), new JdbcOutboxWriter(),
            new JdbcAuditWriter(), BnplConditionDatabaseWorld.IDS, BnplConditionDatabaseWorld.CLOCK);
    final BnplWaivers waivers = new BnplWaivers(CORRECTIONS, BnplConditionDatabaseWorld.SERVICING,
            BnplConditionDatabaseWorld.PURCHASES, base.ports, base.book, base.closures,
            (uow, actor) -> Optional.ofNullable(operatorParties.get(actor.id())), new JdbcOutboxWriter(),
            new JdbcAuditWriter(), BnplConditionDatabaseWorld.IDS, BnplConditionDatabaseWorld.CLOCK);

    /** A fresh operator of a party of their own. */
    Actor operator() {
        Actor operator = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        operatorParties.put(operator.id(), UUID.randomUUID());
        return operator;
    }

    /** An operator whose party is {@code party}'s. */
    Actor insider(UUID party) {
        Actor operator = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        operatorParties.put(operator.id(), party);
        return operator;
    }

    /** {@code work} on {@code date}'s moved clock, one committed transaction, {@code actor} in scope. */
    <R> R on(LocalDate date, Actor actor, Function<Connection, R> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor)) {
            return BnplClockFrame.inOneTransaction(base.frameOn(date), work);
        }
    }

    BnplRepaymentReversal propose(LocalDate date, BnplConditionDatabaseWorld.Purchased bought, UUID repayment,
            Actor actor) {
        return on(date, actor, uow -> reversals.propose(uow, bought.agreement(), repayment, "applied to the wrong"
                + " agreement", actor, BnplAcceptanceDatabaseTest.correlation()));
    }

    BnplRepaymentReversals.Decided approve(LocalDate date, BnplConditionDatabaseWorld.Purchased bought, UUID repayment,
            UUID reversal, Actor actor) {
        return on(date, actor, uow -> reversals.approve(uow, bought.agreement(), repayment, reversal, "checked",
                actor, BnplAcceptanceDatabaseTest.correlation()));
    }

    BnplWaiver proposeWaiver(LocalDate date, BnplConditionDatabaseWorld.Purchased bought, UUID assessment, long minor,
            Actor actor) {
        return on(date, actor, uow -> waivers.propose(uow, bought.agreement(), assessment,
                BnplConditionDatabaseWorld.eur(minor), "goodwill", actor, BnplAcceptanceDatabaseTest.correlation()));
    }

    BnplWaivers.Decided approveWaiver(LocalDate date, BnplConditionDatabaseWorld.Purchased bought, UUID waiver,
            Actor actor) {
        return on(date, actor, uow -> waivers.approve(uow, bought.agreement(), waiver, "upheld", actor,
                BnplAcceptanceDatabaseTest.correlation()));
    }

    /** The obligation's fee assessments, oldest first. */
    List<BnplFeeAssessment> fees(BnplConditionDatabaseWorld.Purchased bought) {
        return BnplAcceptanceDatabaseTest.inOneTransaction(uow -> BnplConditionDatabaseWorld.CONDITIONS
                .assessments(uow, bought.obligation()));
    }

    /** {@code bnpl.billing_component_billed - _paid} of instalment {@code sequence}'s {@code component}. */
    long due(BnplConditionDatabaseWorld.Purchased bought, int sequence, String component) throws java.sql.SQLException {
        return Long.parseLong(BnplAcceptanceDatabaseTest.scalar("SELECT (bnpl.billing_component_billed(b.id, '"
                + component + "') - bnpl.billing_component_paid(b.id, '" + component + "'))::text FROM bnpl.bnpl_billing b"
                + " WHERE b.instalment_id = '" + bought.instalment(sequence) + "'"));
    }
}
