package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycPolicyVersion;
import com.finapp.kyc.VerificationCheck;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.PartyRescreening;
import com.finapp.risk.ScreeningList;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PartyRescreening} over kyc's case-bound screening (`P13-TSK-022`; {@code DESIGN-022} D1), hermetic over scripted
 * stores: kyc's answer for the list's own check type - the latest decided {@code SANCTIONS} check for the sanctions list,
 * {@code PEP} for the PEP list - three bounded facts; a check of the type in flight {@code IN_FLIGHT}; none decided
 * {@code NOT_SCREENED}; a relationship no longer {@code ACTIVE} {@code Inactive}; read on a snapshot of the port's own,
 * never the caller's transaction. kyc's stores are only read: nothing is requested, dispatched or written.
 */
@DisplayName("PartyRescreening over kyc's case-bound screening: kyc's answer, read on its own snapshot (P13-TSK-022)")
class KycPartyRescreeningTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String KEY = PartyRescreening.KEY_PREFIX + UUID.randomUUID();

    private final UUID party = IDS.next();
    private final UUID customer = IDS.next();
    private final KycCaseId kycCase = KycCaseId.next(IDS);
    private final Checks checks = new Checks();
    private final Snapshots transactions = new Snapshots();
    private CustomerStatus status = CustomerStatus.ACTIVE;
    private final PartyRescreening rescreening = new KycPartyRescreening(new Parties(), new Cases(), checks,
            transactions);

    @Test
    @DisplayName("the list's own type: the sanctions list reads the latest decided SANCTIONS check, the PEP list the PEP"
            + " check - its id, whether it hit, when kyc decided it - on one snapshot of the port's own")
    void theListsOwnCheck() {
        Instant earlier = CLOCK.instant().minus(Duration.ofDays(30));
        Instant later = CLOCK.instant().minus(Duration.ofHours(1));
        VerificationCheck sanctionsClear = check(CheckType.SANCTIONS, CheckOutcome.CLEAR, earlier);
        VerificationCheck sanctionsHit = check(CheckType.SANCTIONS, CheckOutcome.HIT, later);
        VerificationCheck pepClear = check(CheckType.PEP, CheckOutcome.CLEAR, earlier);
        checks.rows.addAll(List.of(sanctionsClear, sanctionsHit, pepClear,
                check(CheckType.IDENTITY, CheckOutcome.CLEAR, CLOCK.instant())));

        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY))
                .isEqualTo(new PartyRescreening.Screened(sanctionsHit.id().value(), true, later));
        assertThat(rescreening.ask(party, ScreeningList.PEP, KEY))
                .isEqualTo(new PartyRescreening.Screened(pepClear.id().value(), false, earlier));
        assertThat(transactions.snapshots).as("each ask its own read-only snapshot, never a writing transaction")
                .isEqualTo(2);
        assertThat(transactions.writes).isZero();
    }

    @Test
    @DisplayName("a check of the type still requested or dispatched is IN_FLIGHT - kyc has not answered yet, whatever"
            + " earlier answer stands")
    void aCheckInFlight() {
        checks.rows.add(check(CheckType.PEP, CheckOutcome.CLEAR, CLOCK.instant().minus(Duration.ofDays(9))));
        checks.rows.add(VerificationCheck.request(IDS, CLOCK, kycCase, CheckType.PEP));
        assertThat(rescreening.ask(party, ScreeningList.PEP, KEY))
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.IN_FLIGHT));
        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY)).as("the other type is its own question")
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
    }

    @Test
    @DisplayName("nothing decided of the type - no check, or only INDETERMINATE (we do not know) - is NOT_SCREENED; no"
            + " case either; never a clear")
    void nothingDecidedIsNotScreened() {
        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY))
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
        checks.rows.add(check(CheckType.SANCTIONS, CheckOutcome.INDETERMINATE, CLOCK.instant()));
        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY))
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
        PartyRescreening noCase = new KycPartyRescreening(new Parties(), new NoCases(), checks, transactions);
        assertThat(noCase.ask(party, ScreeningList.SANCTIONS, KEY))
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
    }

    @Test
    @DisplayName("a relationship no longer ACTIVE - SUSPENDED, PENDING, or no live customer - is Inactive: nothing to"
            + " rescreen")
    void notActiveIsInactive() {
        checks.rows.add(check(CheckType.SANCTIONS, CheckOutcome.CLEAR, CLOCK.instant()));
        for (CustomerStatus other : List.of(CustomerStatus.SUSPENDED, CustomerStatus.PENDING)) {
            status = other;
            assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY)).as("%s", other)
                    .isInstanceOf(PartyRescreening.Inactive.class);
        }
        status = CustomerStatus.ACTIVE;
        assertThat(rescreening.ask(IDS.next(), ScreeningList.SANCTIONS, KEY)).as("no live customer")
                .isInstanceOf(PartyRescreening.Inactive.class);
    }

    @Test
    @DisplayName("the answer names nothing of kyc's row but its id, whether it hit and when: its text names neither")
    void theAnswerIsBounded() {
        checks.rows.add(check(CheckType.SANCTIONS, CheckOutcome.HIT, CLOCK.instant()));
        PartyRescreening.Answer answer = rescreening.ask(party, ScreeningList.SANCTIONS, KEY);
        assertThat(answer.toString()).isEqualTo("Screened[hit=true]");
        assertThat(KycPartyRescreening.typeOf(ScreeningList.SANCTIONS)).isEqualTo(CheckType.SANCTIONS);
        assertThat(KycPartyRescreening.typeOf(ScreeningList.PEP)).isEqualTo(CheckType.PEP);
    }

    // -----------------------------------------------------------------

    private VerificationCheck check(CheckType type, CheckOutcome outcome, Instant decidedAt) {
        return VerificationCheck.rehydrate(CheckId.next(IDS), kycCase, type, outcome.toStatus(),
                decidedAt.minus(Duration.ofMinutes(1)), decidedAt);
    }

    /** risk's unit of work, counted: snapshots and writes, each on no connection (the stores are scripted). */
    private static final class Snapshots implements RiskTransactions {
        int snapshots;
        int writes;

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            writes++;
            return work.apply(null);
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            snapshots++;
            return work.apply(null);
        }
    }

    /** The one party, with its live customer in {@link #status}. */
    private final class Parties implements PartyStore<Connection> {

        @Override
        public Optional<Customer> findLiveCustomerFor(Connection unitOfWork, PartyId partyId) {
            return partyId.value().equals(party)
                    ? Optional.of(Customer.rehydrate(CustomerId.of(customer), PartyId.of(party), status,
                            CLOCK.instant(), CLOCK.instant()))
                    : Optional.empty();
        }

        @Override
        public Optional<com.finapp.party.Party> findById(Connection unitOfWork, PartyId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean moveCustomerStatus(Connection unitOfWork, CustomerId customerId, CustomerStatus from,
                CustomerStatus to, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Customer> organisationRegisteredBy(Connection unitOfWork, PartyId registrantPartyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.PartyKind> kindOf(Connection unitOfWork, PartyId partyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.PartyKind> kindOfCustomer(Connection unitOfWork, CustomerId customerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PartyId> partyOfCustomer(Connection unitOfWork, CustomerId customerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PartyId> activeCustomerPartiesAfter(Connection unitOfWork, Optional<PartyId> after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.PartyName> rename(Connection unitOfWork, PartyId id,
                com.finapp.party.PartyName newName) {
            throw new UnsupportedOperationException();
        }
    }

    /** The customer's one latest case. */
    private class Cases implements KycCaseStore<Connection> {

        @Override
        public Optional<KycCase> findLatestFor(Connection unitOfWork, UUID customerId) {
            return customerId.equals(customer)
                    ? Optional.of(KycCase.rehydrate(kycCase, customer, KycCaseKind.KYC, KycCaseStatus.APPROVED,
                            KycPolicyVersion.CURRENT, CLOCK.instant(), CLOCK.instant()))
                    : Optional.empty();
        }

        @Override
        public Opening openOrConverge(Connection unitOfWork, KycCase fresh) {
            throw new UnsupportedOperationException("the port opens no case");
        }

        @Override
        public Optional<KycCase> findOpenFor(Connection unitOfWork, UUID customerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean moveStatus(Connection unitOfWork, KycCaseId id, KycCaseStatus from, KycCaseStatus to,
                Instant at) {
            throw new UnsupportedOperationException("the port moves no case");
        }

        @Override
        public Optional<KycCase> findById(Connection unitOfWork, KycCaseId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean moveToReadyForDecision(Connection unitOfWork, KycCaseId id, KycCaseStatus from, Instant at) {
            throw new UnsupportedOperationException();
        }
    }

    /** A customer with no case. */
    private final class NoCases extends Cases {
        @Override
        public Optional<KycCase> findLatestFor(Connection unitOfWork, UUID customerId) {
            return Optional.empty();
        }
    }

    /** The case's checks - read only: a request, a dispatch, a completion or evidence is refused loudly. */
    private static final class Checks implements CheckStore<Connection> {
        final List<VerificationCheck> rows = new ArrayList<>();

        @Override
        public List<VerificationCheck> forCase(Connection unitOfWork, KycCaseId caseId) {
            return rows.stream().filter(check -> check.caseId().equals(caseId)).toList();
        }

        @Override
        public Requested requestOrConverge(Connection unitOfWork, VerificationCheck fresh) {
            throw new UnsupportedOperationException("the port requests no check");
        }

        @Override
        public Optional<VerificationCheck> findById(Connection unitOfWork, CheckId checkId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean dispatch(Connection unitOfWork, CheckId checkId, Instant at) {
            throw new UnsupportedOperationException("the port dispatches no check");
        }

        @Override
        public boolean complete(Connection unitOfWork, CheckId checkId, CheckOutcome outcome, Instant at) {
            throw new UnsupportedOperationException("the port completes no check");
        }

        @Override
        public void appendEvidence(Connection unitOfWork, com.finapp.kyc.EvidenceId id, CheckId checkId,
                byte[] payload, Instant receivedAt) {
            throw new UnsupportedOperationException("the port writes no evidence");
        }
    }
}
