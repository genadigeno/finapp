package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The owner's agreement reads, hermetic (`P12-TSK-013`; {@code INV-BNPL-01}): the list, one agreement with its six
 * balances derived from the obligation's journal lines - never a stored figure - and its schedule with each instalment's
 * billed mark; another party's agreement the same empty answer. The snapshot the door runs them in is {@code app}'s.
 */
@DisplayName("the owner's agreement reads: balances derived from journal lines, owner-scoped (P12-TSK-013)")
class BnplObligationsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-013-reads");
    private static final long GROSS = 600_00L;

    private BnplApplicationFakes world;
    private BnplObligations obligations;
    private BnplAcceptances.Accepted accepted;
    private UUID party;

    @BeforeEach
    void setUp() {
        world = new BnplApplicationFakes();
        BnplTermsAdministration terms = new BnplTermsAdministration(world.terms.store(), world.audit, world.outbox, IDS,
                CLOCK, BnplTermsFixtures.BOUNDS);
        BnplApplications applications = new BnplApplications(world.store, terms, world.standing, world.sessions,
                world.merchants, BnplTermsFixtures.BOUNDS, world.credit, world.outbox, world.audit, IDS, CLOCK);
        BnplOrigination origination = new BnplOrigination(world.store, world.offers, terms, world.credit, world.sessions,
                world.outbox, IDS, CLOCK);
        BnplAcceptances acceptances = new BnplAcceptances(world.offers, world.terms.store(), world.purchases,
                world.credit, world.standing, world.sessions, world.merchants, world.capital, world.wallets, world.book,
                world.outbox, world.audit, IDS, CLOCK);
        obligations = new BnplObligations(world.purchases, world.book);
        party = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.merchants.payables.put(merchant, world.book.external("payable", IDS.next()));
        LedgerAccountId wallet = world.book.external("wallet", IDS.next());
        world.book.funds.put(wallet, Money.ofMinorUnits(1_000_00, BnplTermsFixtures.EUR));
        world.wallets.byParty.put(party, wallet);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);

        String token = world.sessions.open(merchant, GROSS);
        BnplApplication application = world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, party,
                new BnplApplications.Submission(Sensitive.of(token), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4),
                new Actor(party.toString(), ActorType.CUSTOMER), CORRELATION));
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW, application.id(),
                new Actor("platform", ActorType.SYSTEM)));
        BnplOffer offer = world.offers.ofApplication(BnplApplicationFakes.UOW, application.id()).orElseThrow();
        accepted = world.inTransaction(() -> acceptances.accept(BnplApplicationFakes.UOW, offer.id(), party,
                new BnplAcceptances.Acceptance(world.offers.terms.get(offer.id().value()).termsSha256Hex(),
                        new BnplAcceptanceEvidence(UUID.randomUUID(), UUID.randomUUID(), "MULTI_FACTOR", Optional.empty(),
                                Optional.empty())),
                new Actor(party.toString(), ActorType.CUSTOMER), Correlation.startingWith(CORRELATION)));
    }

    @Test
    @DisplayName("an agreement's six balances are its journal lines' - pay-in-4 after instalment 1: 450.00 not yet due,"
            + " nothing billed unpaid, no interest, fee or credit")
    void theBalancesAreDerivedFromJournalLines() {
        BnplObligations.Read read = obligations.read(BnplApplicationFakes.UOW, accepted.agreement().id(), party)
                .orElseThrow();
        BnplObligations.Balances balances = read.balances();
        assertThat(balances.principal()).isEqualTo(eur(450_00));
        assertThat(balances.principalDue()).isEqualTo(eur(0));
        assertThat(balances.principalOutstanding()).isEqualTo(eur(450_00));
        assertThat(balances.interestAccrued()).isEqualTo(eur(0));
        assertThat(balances.interestDue()).isEqualTo(eur(0));
        assertThat(balances.feesDue()).isEqualTo(eur(0));
        assertThat(balances.creditBalance()).isEqualTo(eur(0));
        assertThat(read.owned().gross()).isEqualTo(eur(GROSS));
        assertThat(read.owned().purchase().order()).contains(accepted.order());
        assertThat(balances.toString()).doesNotContain("450");
    }

    @Test
    @DisplayName("the schedule is version 1, four instalments from the purchase's day, instalment 1 marked billed")
    void theScheduleMarksWhatIsBilled() {
        BnplObligations.Schedule schedule = obligations.schedule(BnplApplicationFakes.UOW, accepted.agreement().id(),
                party).orElseThrow();
        assertThat(schedule.schedule().version()).isEqualTo(1);
        assertThat(schedule.schedule().instalments()).hasSize(4);
        UUID first = schedule.schedule().instalments().get(0).id();
        assertThat(schedule.billed()).containsOnlyKeys(first);
        // P12-TSK-015: the billed item - instalment 1 billed and paid at purchase, nothing still due.
        BnplPurchaseStore.BilledItem item = schedule.billedItem(first).orElseThrow();
        assertThat(item.billedPrincipal()).isEqualTo(eur(150_00));
        assertThat(item.paidPrincipal()).isEqualTo(eur(150_00));
        assertThat(item.dueTotal()).isEqualTo(eur(0));
        assertThat(schedule.billedItem(schedule.schedule().instalments().get(1).id())).isEmpty();
    }

    @Test
    @DisplayName("the list names the party's agreements; another party's agreement is the same empty answer")
    void ownerScoped() {
        assertThat(obligations.list(BnplApplicationFakes.UOW, party)).singleElement()
                .satisfies(owned -> assertThat(owned.agreement().id()).isEqualTo(accepted.agreement().id()));
        UUID stranger = UUID.randomUUID();
        assertThat(obligations.list(BnplApplicationFakes.UOW, stranger)).isEmpty();
        assertThat(obligations.read(BnplApplicationFakes.UOW, accepted.agreement().id(), stranger)).isEmpty();
        assertThat(obligations.schedule(BnplApplicationFakes.UOW, accepted.agreement().id(), stranger)).isEmpty();
    }

    @Test
    @DisplayName("an account outside the obligation's six purposes is refused, never read")
    void aStrayAccountIsRefused() {
        com.finapp.ledger.LedgerAccount stray = com.finapp.ledger.LedgerAccount.owned(IDS, CLOCK,
                com.finapp.ledger.AccountType.ASSET, com.finapp.ledger.AccountPurpose.BNPL_PRINCIPAL,
                BnplTermsFixtures.EUR, accepted.obligation().id().value());
        world.book.accounts.put(stray.id(), stray);
        assertThatThrownBy(() -> obligations.read(BnplApplicationFakes.UOW, accepted.agreement().id(), party))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, BnplTermsFixtures.EUR);
    }
}
