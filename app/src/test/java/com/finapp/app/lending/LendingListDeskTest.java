package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.RecordedReads;
import com.finapp.lending.LendingAuditAction;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LendingOperatorLists;
import com.finapp.lending.LoanApplicationStatus;
import com.finapp.lending.LoanExplanations;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanOperatorReads;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanStatus;
import com.finapp.lending.LoanTermsVersionId;
import com.finapp.lending.LoanTermsVersionStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lending's operator lists, hermetic (`P17-TSK-035`): the reader's own party withheld from every list of a party's records -
 * an unplaced reader, or one naming their own party, refused {@code 403} and recorded {@code FAILED} with nothing read; every
 * page of a party's records recorded once; the servicing queue in ADR-C133's pending-decision shape, walked oldest first; the
 * terms versions unrecorded, their proposer named. The account reads and the SQL over PostgreSQL:
 * {@code LendingReadGapsApiDatabaseTest} and lending's {@code LendingOperatorListsDatabaseTest}.
 */
@DisplayName("lending's operator lists and the approver's queue (P17-TSK-035)")
@SuppressWarnings("try") // The correlation and actor scopes are used for their close side effect (the established idiom).
class LendingListDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor READER = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final UUID READERS_PARTY = UUID.randomUUID();

    private final List<AuditRecord> written = new ArrayList<>();
    private final AuditWriter<Connection> writer = (unitOfWork, record) -> written.add(record);
    private final Fake lists = new Fake();
    private Optional<UUID> readersParty = Optional.of(READERS_PARTY);
    private final LendingListDesk desk = new LendingListDesk(lists, mock(LoanOperatorReads.class),
            mock(LoanExplanations.class), (unitOfWork, actor) -> readersParty, new RecordedReads(writer, IDS, CLOCK), writer, IDS,
            CLOCK, transactions());

    @Test
    @DisplayName("the loans are read with the reader's own party withheld, the filter passed whole, and the page recorded"
            + " lending.LoansRead once")
    void theLoansWithholdTheReadersParty() {
        UUID party = UUID.randomUUID();
        lists.loans.add(loan(party, Instant.parse("2026-11-01T00:00:00Z")));
        LendingListDesk.LoanPage page = as(() -> desk.loans(party.toString(), "ACTIVE", "B2_30_59", null, null));
        assertThat(lists.withheld).isEqualTo(READERS_PARTY);
        assertThat(lists.filter).isEqualTo(new LendingOperatorLists.LoanFilter(Optional.of(party), Optional.of(LoanStatus.ACTIVE),
                Optional.of("B2_30_59")));
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.partyId()).isEqualTo(party.toString());
            assertThat(item.bucket()).isEqualTo("B2_30_59");
            assertThat(item.defaulted()).isFalse();
        });
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(LendingAuditAction.LOANS_READ);
            assertThat(record.targetId()).isEqualTo(party.toString());
            assertThat(record.changeSummary()).hasValue("party=" + party + ", state=ACTIVE, bucket=B2_30_59, continued=false,"
                    + " served=1");
        });
    }

    @Test
    @DisplayName("a reader the platform cannot place in a party, and one naming their own party, is 403"
            + " lending.SelfDealingRefused - nothing read, the attempt recorded FAILED")
    void anUnplacedReaderOrTheirOwnPartyIsRefused() {
        readersParty = Optional.empty();
        assertSelfDealing(() -> desk.loans(null, null, null, null, null));
        assertSelfDealing(() -> desk.applications(null, null, null));
        assertSelfDealing(() -> desk.servicingRequests(null, null, null));
        readersParty = Optional.of(READERS_PARTY);
        assertSelfDealing(() -> desk.loans(READERS_PARTY.toString(), null, null, null, null));
        assertThat(lists.read).as("nothing was read").isZero();
        assertThat(written).hasSize(4).allSatisfy(record -> {
            assertThat(record.operation()).isEqualTo(LendingAuditAction.SELF_DEALING_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.actor()).isEqualTo(READER);
        });
        assertThat(written).extracting(AuditRecord::targetType)
                .containsExactly("loans", "loan_applications", "loan_servicing_requests", "loans");
    }

    @Test
    @DisplayName("the servicing queue walks once, oldest first, in the one pending-decision shape (P17-TSK-040): lending.<Kind>,"
            + " the loan its subject, a reversal's repayment its related id, the proposer and their reason named")
    void theServicingQueueWalksOnce() {
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        UUID repayment = UUID.randomUUID();
        lists.servicing.add(new LendingOperatorLists.ServicingRequest(IDS.next(), LendingOperatorLists.ServicingKind.WAIVER,
                LoanId.of(IDS.next()), READER.id(), start, "a late fee charged in error", Optional.empty()));
        lists.servicing.add(new LendingOperatorLists.ServicingRequest(IDS.next(), LendingOperatorLists.ServicingKind.AMENDMENT,
                LoanId.of(IDS.next()), "another-agent", start.plusSeconds(60), "hardship", Optional.empty()));
        lists.servicing.add(new LendingOperatorLists.ServicingRequest(IDS.next(),
                LendingOperatorLists.ServicingKind.REPAYMENT_REVERSAL, LoanId.of(IDS.next()), "another-agent",
                start.plusSeconds(120), "the payment bounced", Optional.of(repayment)));
        List<PendingDecisions.PendingDecision> walked = new ArrayList<>();
        String cursor = null;
        do {
            String after = cursor;
            PendingDecisions.PendingDecisionPage page = as(() -> desk.servicingRequests("PROPOSED", after, "2"));
            walked.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(walked).extracting(PendingDecisions.PendingDecision::kind)
                .containsExactly("lending.Waiver", "lending.Amendment", "lending.RepaymentReversal");
        assertThat(walked).extracting(PendingDecisions.PendingDecision::subjectId)
                .containsExactlyElementsOf(lists.servicing.stream().map(request -> request.loan().value().toString()).toList());
        assertThat(walked.getFirst().proposedBy()).isEqualTo(READER.id());
        assertThat(walked.getFirst().reasonSummary()).isEqualTo("a late fee charged in error");
        assertThat(walked.get(1).proposedBy()).isEqualTo("another-agent");
        assertThat(walked.getLast().relatedId()).as("a reversal names its repayment").isEqualTo(repayment.toString());
        assertThat(walked.getFirst().relatedId()).isNull();
        assertThat(written).hasSize(2).allSatisfy(record ->
                assertThat(record.operation()).isEqualTo(LendingAuditAction.SERVICING_REQUESTS_READ));
    }

    @Test
    @DisplayName("the request rules: a queue's status is PROPOSED or absent, a bucket CURRENT or a band, a party a UUID, a limit"
            + " 1..200, a cursor the collection's own - each 422 api.ValidationFailed, nothing read or recorded")
    void theRequestRules() {
        assertRefused(() -> desk.servicingRequests("APPROVED", null, null));
        assertRefused(() -> desk.loans(null, null, "B9_LATE", null, null));
        assertRefused(() -> desk.loans("someone", null, null, null, null));
        assertRefused(() -> desk.loans(null, "OPEN", null, null, null));
        assertRefused(() -> desk.applications(null, null, "201"));
        assertRefused(() -> desk.applications("NOPE", null, null));
        assertRefused(() -> desk.applications(null, OperatorCollection.cursor(LendingListDesk.LOANS, "1",
                UUID.randomUUID().toString()), null));
        assertRefused(() -> desk.termsVersions("MORTGAGE", null, null, null));
        assertThat(lists.read).isZero();
        assertThat(written).isEmpty();
        assertThat(LendingListDesk.BUCKETS).containsExactly("CURRENT", "B1_1_29", "B2_30_59", "B3_60_89", "B4_90_PLUS");
    }

    @Test
    @DisplayName("the terms versions are configuration: unrecorded, as the terms in force are; each names its proposer and"
            + " marks the caller's own, never its decider")
    void theTermsVersionsAreUnrecorded() {
        lists.terms.add(new LendingOperatorLists.TermsVersionHead(LoanTermsVersionId.of(IDS.next()), LoanProduct.PERSONAL_LOAN, 2,
                LoanTermsVersionStatus.PROPOSED, READER.id(), Instant.parse("2026-11-01T00:00:00Z"), "a new rate",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        LendingListDesk.LoanTermsVersionHeadView view = as(() -> desk.termsVersions(null, "PROPOSED", null, null)).items()
                .getFirst();
        assertThat(view.proposedBy()).isEqualTo(READER.id());
        assertThat(view.proposedByCaller()).isTrue();
        assertThat(LendingListDesk.LoanTermsVersionHeadView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName).doesNotContain("decidedBy");
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private <T> T as(Supplier<T> read) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(READER)) {
            return read.get();
        }
    }

    private void assertRefused(Runnable request) {
        assertThatThrownBy(() -> as(() -> {
            request.run();
            return null;
        })).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }

    private void assertSelfDealing(Runnable request) {
        assertThatThrownBy(() -> as(() -> {
            request.run();
            return null;
        })).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(LendingErrorCode.SELF_DEALING_REFUSED));
    }

    @SuppressWarnings("unchecked")
    private static LendingTransactions transactions() {
        LendingTransactions transactions = mock(LendingTransactions.class);
        when(transactions.inTransaction(any())).thenAnswer(call -> ((Function<Connection, Object>) call.getArgument(0)).apply(null));
        return transactions;
    }

    private static LendingOperatorLists.LoanHead loan(UUID party, Instant opened) {
        return new LendingOperatorLists.LoanHead(LoanId.of(IDS.next()), party, LoanProduct.PERSONAL_LOAN, LoanKind.INSTALMENT,
                LoanStatus.ACTIVE, CurrencyCode.of("EUR"), opened, Optional.empty(),
                Optional.of(new LendingOperatorLists.RecordedCondition("B2_30_59", false)));
    }

    /** The read model over lists, honouring the queue's ascending keyset and the limit as the SQL does. */
    private static final class Fake implements LendingOperatorLists {

        final List<LoanHead> loans = new ArrayList<>();
        final List<ServicingRequest> servicing = new ArrayList<>();
        final List<TermsVersionHead> terms = new ArrayList<>();
        UUID withheld;
        LoanFilter filter;
        int read;

        @Override
        public List<ApplicationHead> applications(Connection unitOfWork, Optional<LoanApplicationStatus> status,
                UUID withheldParty, Optional<Cursor> after, int limit) {
            read++;
            withheld = withheldParty;
            return List.of();
        }

        @Override
        public List<LoanHead> loans(Connection unitOfWork, LoanFilter loanFilter, UUID withheldParty, Optional<Cursor> after,
                int limit) {
            read++;
            withheld = withheldParty;
            filter = loanFilter;
            return loans.stream().limit(limit).toList();
        }

        @Override
        public List<ServicingRequest> pendingServicing(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after,
                int limit) {
            read++;
            withheld = withheldParty;
            Comparator<ServicingRequest> order = Comparator.comparing(ServicingRequest::proposedAt)
                    .thenComparing(ServicingRequest::id);
            return servicing.stream().sorted(order)
                    .filter(request -> after.isEmpty() || request.proposedAt().isAfter(after.get().at())
                            || (request.proposedAt().equals(after.get().at()) && request.id().compareTo(after.get().id()) > 0))
                    .limit(limit).toList();
        }

        @Override
        public List<TermsVersionHead> termsVersions(Connection unitOfWork, Optional<LoanProduct> product,
                Optional<LoanTermsVersionStatus> status, Optional<Cursor> after, int limit) {
            read++;
            return terms.stream().limit(limit).toList();
        }

        @Override
        public List<TermsVersionHead> pendingTermsVersions(Connection unitOfWork, Optional<Cursor> after, int limit) {
            read++;
            return terms.stream().limit(limit).toList();
        }

        @Override
        public List<PendingContribution> pendingCapitalContributions(Connection unitOfWork, Optional<Cursor> after, int limit) {
            throw new UnsupportedOperationException("the pending queues are not under test here");
        }
    }
}
