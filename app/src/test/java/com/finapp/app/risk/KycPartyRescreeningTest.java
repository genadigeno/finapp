package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckPurpose;
import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.ChecksAssessment;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PartyRescreening} through kyc's re-screening door (`P13-TSK-022`, the owner's decision O1), hermetic over
 * scripted stores and a scripted door: a decided party is asked again under the permit's key - never answered from its
 * onboarding screening, however recent; the keyed check answers once asked; in flight is {@code IN_FLIGHT} and asks
 * nothing; "we do not know" retries under the next key ordinal up to kyc's budget; an onboarding ({@code PENDING})
 * customer is asked too; a relationship gone is {@code Inactive}; the placing read on a snapshot of the port's own,
 * never a risk transaction.
 */
@DisplayName("PartyRescreening through kyc's door: a decided party asked again under the permit's key (P13-TSK-022)")
class KycPartyRescreeningTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String KEY = PartyRescreening.KEY_PREFIX + UUID.randomUUID();
    /** The database's clock at the door's ask - after the list's activation. */
    private static final Instant ASKED = CLOCK.instant().plusSeconds(30);

    private final UUID party = IDS.next();
    private final UUID customer = IDS.next();
    private final KycCaseId kycCase = KycCaseId.next(IDS);
    private final Checks checks = new Checks();
    private final Door door = new Door();
    private final Snapshots transactions = new Snapshots();
    private CustomerStatus status = CustomerStatus.ACTIVE;
    private KycCaseStatus caseStatus = KycCaseStatus.APPROVED;
    private final PartyRescreening rescreening = new KycPartyRescreening(new Parties(), new Cases(), checks, door,
            transactions);

    @Test
    @DisplayName("the acceptance's heart: a decided party is ASKED AGAIN through kyc's door under the permit's first key"
            + " - never answered from its onboarding screening - and kyc's keyed answer is the Screened: its id, whether"
            + " it hit, when asked and decided")
    void aDecidedPartyIsAskedAgainUnderThePermitsKey() {
        checks.add(onboarding(CheckType.SANCTIONS, CheckOutcome.CLEAR, CLOCK.instant().minus(Duration.ofDays(400))));
        door.answers = CheckStatus.CLEAR;
        PartyRescreening.Answer answer = rescreening.ask(party, ScreeningList.SANCTIONS, KEY);
        assertThat(door.asked).containsExactly(kycCase + "|" + customer + "|SANCTIONS|" + KEY + "#0");
        VerificationCheck keyed = checks.byKey.get(KEY + "#0");
        assertThat(answer).isEqualTo(new PartyRescreening.Screened(keyed.id().value(), false, ASKED, ASKED));
        assertThat(transactions.snapshots).as("placed on a read-only snapshot of its own").isEqualTo(1);
        assertThat(transactions.writes).as("never a risk transaction: kyc's door writes kyc's rows").isZero();

        door.answers = CheckStatus.HIT;
        assertThat(rescreening.ask(party, ScreeningList.PEP, KEY + "-pep")).as("the PEP list asks kyc's PEP screening")
                .isInstanceOf(PartyRescreening.Screened.class)
                .satisfies(screened -> assertThat(((PartyRescreening.Screened) screened).hit()).isTrue());
        assertThat(door.asked).last().asString().contains("|PEP|");
    }

    @Test
    @DisplayName("once asked, the keyed check answers - the door is not called again: answered is Screened, in flight is"
            + " IN_FLIGHT (another asker holds the call, or a crash left it dispatched)")
    void theKeyedCheckAnswersOnceAsked() {
        checks.add(rescreen(KEY + "#0", CheckType.SANCTIONS, CheckStatus.HIT));
        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY)).isInstanceOf(PartyRescreening.Screened.class)
                .satisfies(screened -> assertThat(((PartyRescreening.Screened) screened).hit()).isTrue());
        checks.add(rescreen(KEY + "-b#0", CheckType.SANCTIONS, CheckStatus.DISPATCHED));
        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY + "-b"))
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.IN_FLIGHT));
        assertThat(door.asked).as("never a second question under a key").isEmpty();
    }

    @Test
    @DisplayName("\"we do not know\" retries under the next key ordinal - one ask per call - up to kyc's budget; at the"
            + " budget the party is NOT_SCREENED and nothing more is asked")
    void anUnknownRetriesUnderTheNextOrdinalUpToTheBudget() {
        checks.add(rescreen(KEY + "#0", CheckType.SANCTIONS, CheckStatus.INDETERMINATE));
        door.answers = CheckStatus.INDETERMINATE;
        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY))
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
        assertThat(door.asked).containsExactly(kycCase + "|" + customer + "|SANCTIONS|" + KEY + "#1");
        for (int ordinal = 2; ordinal < ChecksAssessment.INDETERMINATE_RETRY_BUDGET; ordinal++) {
            rescreening.ask(party, ScreeningList.SANCTIONS, KEY);
        }
        int asked = door.asked.size();
        assertThat(asked).isEqualTo(ChecksAssessment.INDETERMINATE_RETRY_BUDGET - 1);
        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY))
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
        assertThat(door.asked).as("the budget spent: nothing more asked").hasSize(asked);
    }

    @Test
    @DisplayName("an onboarding (PENDING) customer is asked too, on its open case - the review's MINOR 9; a relationship"
            + " neither ACTIVE nor PENDING, or none, is Inactive; a customer with no case is NOT_SCREENED")
    void whoIsAsked() {
        door.answers = CheckStatus.CLEAR;
        status = CustomerStatus.PENDING;
        caseStatus = KycCaseStatus.CHECKS_IN_PROGRESS;
        assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY)).isInstanceOf(PartyRescreening.Screened.class);
        assertThat(door.asked).hasSize(1);
        for (CustomerStatus gone : List.of(CustomerStatus.SUSPENDED, CustomerStatus.CLOSED, CustomerStatus.REJECTED)) {
            status = gone;
            assertThat(rescreening.ask(party, ScreeningList.SANCTIONS, KEY + gone)).as("%s", gone)
                    .isInstanceOf(PartyRescreening.Inactive.class);
        }
        status = CustomerStatus.ACTIVE;
        assertThat(rescreening.ask(IDS.next(), ScreeningList.SANCTIONS, KEY)).as("no live customer")
                .isInstanceOf(PartyRescreening.Inactive.class);
        PartyRescreening noCase = new KycPartyRescreening(new Parties(), new NoCases(), checks, door, transactions);
        assertThat(noCase.ask(party, ScreeningList.SANCTIONS, KEY + "-none"))
                .isEqualTo(new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
        assertThat(door.asked).as("nothing asked for a party gone or with no case").hasSize(1);
    }

    @Test
    @DisplayName("refusals: a key that is not a permit's, a key that asked another list's screening; no provider anywhere"
            + " is the door's throw - the leg's Unavailable")
    void refusals() {
        assertThatThrownBy(() -> rescreening.ask(party, ScreeningList.SANCTIONS, "not-a-permit"))
                .isInstanceOf(IllegalArgumentException.class);
        checks.add(rescreen(KEY + "-x#0", CheckType.PEP, CheckStatus.CLEAR));
        assertThatThrownBy(() -> rescreening.ask(party, ScreeningList.SANCTIONS, KEY + "-x"))
                .isInstanceOf(IllegalStateException.class);
        PartyRescreening unavailable = new KycPartyRescreening(new Parties(), new Cases(), checks,
                KycPartyRescreening.Door.UNAVAILABLE, transactions);
        assertThatThrownBy(() -> unavailable.ask(party, ScreeningList.SANCTIONS, KEY + "-u"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KycPartyRescreening.answerOf(onboarding(CheckType.SANCTIONS, CheckOutcome.CLEAR,
                CLOCK.instant()))).as("a permit's key never names an onboarding check")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the answer names nothing of kyc's row but its id, whether it hit and when: its text names neither")
    void theAnswerIsBounded() {
        checks.add(rescreen(KEY + "#0", CheckType.SANCTIONS, CheckStatus.HIT));
        PartyRescreening.Answer answer = rescreening.ask(party, ScreeningList.SANCTIONS, KEY);
        assertThat(answer.toString()).isEqualTo("Screened[hit=true]");
        assertThat(KycPartyRescreening.typeOf(ScreeningList.SANCTIONS)).isEqualTo(CheckType.SANCTIONS);
        assertThat(KycPartyRescreening.typeOf(ScreeningList.PEP)).isEqualTo(CheckType.PEP);
        assertThat(KycPartyRescreening.keyed(KEY, 2)).isEqualTo(KEY + "#2");
        assertThat(KycPartyRescreening.RESCREENED).containsExactlyInAnyOrder(CustomerStatus.ACTIVE,
                CustomerStatus.PENDING);
    }

    // -----------------------------------------------------------------

    private VerificationCheck onboarding(CheckType type, CheckOutcome outcome, Instant decidedAt) {
        return VerificationCheck.rehydrate(CheckId.next(IDS), kycCase, type, outcome.toStatus(),
                decidedAt.minus(Duration.ofMinutes(1)), decidedAt);
    }

    private VerificationCheck rescreen(String key, CheckType type, CheckStatus checkStatus) {
        VerificationCheck check = VerificationCheck.rehydrate(CheckId.next(IDS), kycCase, type, checkStatus, ASKED, ASKED,
                CheckPurpose.RESCREEN);
        checks.keys.put(check.id(), key);
        return check;
    }

    /** kyc's door, scripted: asked once per call, the keyed check written as kyc would - RESCREEN, the database's ask. */
    private final class Door implements KycPartyRescreening.Door {
        final List<String> asked = new ArrayList<>();
        CheckStatus answers = CheckStatus.CLEAR;

        @Override
        public VerificationCheck rescreen(KycCaseId caseId, UUID customerId, CheckType type, String rescreenKey) {
            asked.add(caseId + "|" + customerId + "|" + type + "|" + rescreenKey);
            VerificationCheck check = KycPartyRescreeningTest.this.rescreen(rescreenKey, type, answers);
            checks.add(check);
            return check;
        }
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
        public List<PartyId> customerPartiesAfter(Connection unitOfWork, java.util.Set<CustomerStatus> statuses,
                Optional<PartyId> after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.PartyName> rename(Connection unitOfWork, PartyId id,
                com.finapp.party.PartyName newName) {
            throw new UnsupportedOperationException();
        }
    }

    /** The customer's one latest case, in {@link #caseStatus}. */
    private class Cases implements KycCaseStore<Connection> {

        @Override
        public Optional<KycCase> findLatestFor(Connection unitOfWork, UUID customerId) {
            return customerId.equals(customer)
                    ? Optional.of(KycCase.rehydrate(kycCase, customer, KycCaseKind.KYC, caseStatus,
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

    /** The case's checks, read only here - kyc's door writes the keyed rows; the port requests nothing itself. */
    private static final class Checks implements CheckStore<Connection> {
        final List<VerificationCheck> rows = new ArrayList<>();
        final Map<CheckId, String> keys = new LinkedHashMap<>();
        final Map<String, VerificationCheck> byKey = new LinkedHashMap<>();

        void add(VerificationCheck check) {
            rows.add(check);
            Optional.ofNullable(keys.get(check.id())).ifPresent(key -> byKey.put(key, check));
        }

        @Override
        public List<VerificationCheck> forCase(Connection unitOfWork, KycCaseId caseId) {
            return rows.stream().filter(check -> check.caseId().equals(caseId)).filter(VerificationCheck::onboarding)
                    .toList();
        }

        @Override
        public List<VerificationCheck> allForCase(Connection unitOfWork, KycCaseId caseId) {
            return rows.stream().filter(check -> check.caseId().equals(caseId)).toList();
        }

        @Override
        public Optional<VerificationCheck> findRescreen(Connection unitOfWork, String rescreenKey) {
            return Optional.ofNullable(byKey.get(rescreenKey));
        }

        @Override
        public Requested requestRescreen(Connection unitOfWork, KycCaseId caseId, CheckType type, CheckId fresh,
                String rescreenKey) {
            throw new UnsupportedOperationException("the port requests nothing: kyc's door does");
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
