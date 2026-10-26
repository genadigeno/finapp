package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStatus;
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
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.ScreeningClearances;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ScreeningClearances} over kyc's case checks (`P13-TSK-005`), hermetic over scripted stores: the latest
 * decided sanctions or PEP check of the party's live customer's latest case, as three bounded facts; nothing where
 * there is no live customer, no case, no screening check, or none decided.
 */
@DisplayName("ScreeningClearances over kyc's case checks (P13-TSK-005)")
class KycScreeningClearancesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    // UUIDv7, as every platform identifier is (ADR-0013): PartyId.of and CustomerId.of refuse a v4.
    private final UUID party = IDS.next();
    private final UUID customer = IDS.next();
    private final KycCaseId kycCase = KycCaseId.next(IDS);
    private final ScriptedChecks checks = new ScriptedChecks();
    private final ScreeningClearances clearances = new KycScreeningClearances(
            new ScriptedParties(party, customer), new ScriptedCases(customer, kycCase), checks);

    @Test
    @DisplayName("the latest decided sanctions or PEP check is the clearance: a hit after a clear reads as a hit")
    void theLatestDecidedScreeningCheckIsTheClearance() {
        Instant earlier = Instant.now(CLOCK).minus(Duration.ofDays(30));
        Instant later = Instant.now(CLOCK).minus(Duration.ofDays(1));
        checks.add(check(CheckType.SANCTIONS, CheckOutcome.CLEAR, earlier));
        VerificationCheck hit = check(CheckType.PEP, CheckOutcome.HIT, later);
        checks.add(hit);
        checks.add(check(CheckType.IDENTITY, CheckOutcome.CLEAR, Instant.now(CLOCK)));

        Optional<ScreeningClearances.Clearance> clearance = clearances.latestFor(null, party);

        assertThat(clearance).isPresent();
        assertThat(clearance.get().screeningId()).isEqualTo(hit.id().value());
        assertThat(clearance.get().hit()).isTrue();
        assertThat(clearance.get().decidedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("a later clear supersedes an earlier hit")
    void aLaterClearSupersedesAnEarlierHit() {
        checks.add(check(CheckType.SANCTIONS, CheckOutcome.HIT, Instant.now(CLOCK).minus(Duration.ofDays(30))));
        VerificationCheck clear = check(CheckType.SANCTIONS, CheckOutcome.CLEAR, Instant.now(CLOCK).minus(Duration.ofDays(1)));
        checks.add(clear);
        assertThat(clearances.latestFor(null, party)).hasValueSatisfying(clearance -> {
            assertThat(clearance.hit()).isFalse();
            assertThat(clearance.screeningId()).isEqualTo(clear.id().value());
        });
    }

    @Test
    @DisplayName("P13-TSK-022's door: a list update's rescreen is read - an onboarding clear 400 days old, a rescreen hit"
            + " yesterday reads as a hit - and judged per type: a later PEP clear never masks a SANCTIONS hit")
    void aRescreenIsReadAndJudgedPerType() {
        checks.add(check(CheckType.SANCTIONS, CheckOutcome.CLEAR, Instant.now(CLOCK).minus(Duration.ofDays(400))));
        checks.add(check(CheckType.PEP, CheckOutcome.CLEAR, Instant.now(CLOCK).minus(Duration.ofDays(400))));
        VerificationCheck sanctionsHit = rescreen(CheckType.SANCTIONS, CheckStatus.HIT, Duration.ofDays(2));
        checks.add(sanctionsHit);
        assertThat(clearances.latestFor(null, party)).hasValueSatisfying(clearance -> {
            assertThat(clearance.hit()).isTrue();
            assertThat(clearance.screeningId()).isEqualTo(sanctionsHit.id().value());
        });
        checks.add(rescreen(CheckType.PEP, CheckStatus.CLEAR, Duration.ofDays(1)));
        assertThat(clearances.latestFor(null, party)).as("the PEP list's later clear masks nothing")
                .hasValueSatisfying(clearance -> {
                    assertThat(clearance.hit()).isTrue();
                    assertThat(clearance.screeningId()).isEqualTo(sanctionsHit.id().value());
                });
        VerificationCheck sanctionsClear = rescreen(CheckType.SANCTIONS, CheckStatus.CLEAR, Duration.ofHours(1));
        checks.add(sanctionsClear);
        assertThat(clearances.latestFor(null, party)).as("the SANCTIONS list asked again, and clear")
                .hasValueSatisfying(clearance -> assertThat(clearance.hit()).isFalse());
    }

    @Test
    @DisplayName("an undecided or indeterminate check is not a clearance, and an identity or document check never is")
    void onlyADecidedScreeningCheckCounts() {
        checks.add(requested(CheckType.SANCTIONS));
        checks.add(check(CheckType.PEP, CheckOutcome.INDETERMINATE, Instant.now(CLOCK)));
        checks.add(check(CheckType.DOCUMENT, CheckOutcome.CLEAR, Instant.now(CLOCK)));
        assertThat(clearances.latestFor(null, party)).isEmpty();
    }

    @Test
    @DisplayName("no live customer, no case, or no check: empty - never a clear")
    void absenceIsEmpty() {
        assertThat(clearances.latestFor(null, party)).as("a case with no check").isEmpty();
        assertThat(clearances.latestFor(null, IDS.next())).as("a party with no live customer").isEmpty();
        ScreeningClearances noCase = new KycScreeningClearances(
                new ScriptedParties(party, customer), new ScriptedCases(IDS.next(), kycCase), checks);
        assertThat(noCase.latestFor(null, party)).as("a customer with no case").isEmpty();
    }

    // -----------------------------------------------------------------

    private VerificationCheck check(CheckType type, CheckOutcome outcome, Instant decidedAt) {
        return VerificationCheck.rehydrate(CheckId.next(IDS), kycCase, type, outcome.toStatus(),
                decidedAt.minus(Duration.ofMinutes(1)), decidedAt);
    }

    private VerificationCheck rescreen(CheckType type, CheckStatus status, Duration ago) {
        Instant asked = Instant.now(CLOCK).minus(ago);
        return VerificationCheck.rehydrate(CheckId.next(IDS), kycCase, type, status, asked, asked.plusSeconds(1),
                com.finapp.kyc.CheckPurpose.RESCREEN);
    }

    private VerificationCheck requested(CheckType type) {
        return VerificationCheck.request(IDS, CLOCK, kycCase, type);
    }

    /** One party with one live customer. */
    private static final class ScriptedParties implements PartyStore<Connection> {
        private final UUID party;
        private final UUID customer;

        private ScriptedParties(UUID party, UUID customer) {
            this.party = party;
            this.customer = customer;
        }

        @Override
        public Optional<Customer> findLiveCustomerFor(Connection unitOfWork, PartyId partyId) {
            return partyId.value().equals(party)
                    ? Optional.of(Customer.rehydrate(CustomerId.of(customer), PartyId.of(party),
                            com.finapp.party.CustomerStatus.ACTIVE, Instant.now(CLOCK), Instant.now(CLOCK)))
                    : Optional.empty();
        }

        @Override
        public Optional<com.finapp.party.Party> findById(Connection unitOfWork, PartyId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean moveCustomerStatus(Connection unitOfWork, CustomerId customerId,
                com.finapp.party.CustomerStatus from, com.finapp.party.CustomerStatus to, Instant at) {
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
        public java.util.List<PartyId> customerPartiesAfter(Connection unitOfWork,
                java.util.Set<com.finapp.party.CustomerStatus> statuses, Optional<PartyId> after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.PartyName> rename(Connection unitOfWork, PartyId id,
                com.finapp.party.PartyName newName) {
            throw new UnsupportedOperationException();
        }
    }

    /** One customer with one latest case. */
    private static final class ScriptedCases implements KycCaseStore<Connection> {
        private final UUID customer;
        private final KycCaseId caseId;

        private ScriptedCases(UUID customer, KycCaseId caseId) {
            this.customer = customer;
            this.caseId = caseId;
        }

        @Override
        public Optional<KycCase> findLatestFor(Connection unitOfWork, UUID customerId) {
            return customerId.equals(customer)
                    ? Optional.of(KycCase.rehydrate(caseId, customer, KycCaseKind.KYC, KycCaseStatus.OPEN,
                            KycPolicyVersion.CURRENT, Instant.now(CLOCK), Instant.now(CLOCK)))
                    : Optional.empty();
        }

        @Override
        public Opening openOrConverge(Connection unitOfWork, KycCase fresh) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KycCase> findOpenFor(Connection unitOfWork, UUID customerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean moveStatus(Connection unitOfWork, KycCaseId id, KycCaseStatus from, KycCaseStatus to,
                Instant at) {
            throw new UnsupportedOperationException();
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

    private static final class ScriptedChecks implements CheckStore<Connection> {
        private final List<VerificationCheck> rows = new ArrayList<>();

        void add(VerificationCheck check) {
            rows.add(check);
        }

        @Override
        public List<VerificationCheck> forCase(Connection unitOfWork, KycCaseId caseId) {
            // kyc's onboarding read: a rescreen is not one of them (kyc V011) - the reader must not depend on it.
            return rows.stream().filter(check -> check.caseId().equals(caseId)).filter(VerificationCheck::onboarding)
                    .toList();
        }

        @Override
        public List<VerificationCheck> allForCase(Connection unitOfWork, KycCaseId caseId) {
            return rows.stream().filter(check -> check.caseId().equals(caseId)).toList();
        }

        @Override
        public Requested requestRescreen(Connection unitOfWork, KycCaseId caseId, CheckType type, CheckId fresh,
                String rescreenKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VerificationCheck> findRescreen(Connection unitOfWork, String rescreenKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Requested requestOrConverge(Connection unitOfWork, VerificationCheck fresh) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VerificationCheck> findById(Connection unitOfWork, CheckId checkId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean dispatch(Connection unitOfWork, CheckId checkId, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean complete(Connection unitOfWork, CheckId checkId, CheckOutcome outcome, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendEvidence(Connection unitOfWork, com.finapp.kyc.EvidenceId id, CheckId checkId,
                byte[] payload, Instant receivedAt) {
            throw new UnsupportedOperationException();
        }
    }
}
