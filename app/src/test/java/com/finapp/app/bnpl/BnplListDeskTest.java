package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.RecordedReads;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplApplicationStatus;
import com.finapp.bnpl.BnplAuditAction;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplOperatorLists;
import com.finapp.bnpl.BnplPurchaseId;
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
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BNPL's operator lists, hermetic (`P17-TSK-035`): the reader's own party withheld from every list and never read - an
 * unplaced reader refused {@code 403} and recorded {@code FAILED}, a purchase of the reader's own party likewise; one
 * purchase's refunds walked oldest first with their amounts as exact decimals; the servicing queue in ADR-C133's
 * pending-decision shape. The SQL over PostgreSQL: {@code BnplReadGapsApiDatabaseTest}.
 */
@DisplayName("BNPL's operator lists and the approver's queue (P17-TSK-035)")
@SuppressWarnings("try") // The correlation and actor scopes are used for their close side effect (the established idiom).
class BnplListDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor READER = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final UUID READERS_PARTY = UUID.randomUUID();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final List<AuditRecord> written = new ArrayList<>();
    private final AuditWriter<Connection> writer = (unitOfWork, record) -> written.add(record);
    private final Fake lists = new Fake();
    private Optional<UUID> readersParty = Optional.of(READERS_PARTY);
    private final BnplListDesk desk = new BnplListDesk(lists, (unitOfWork, actor) -> readersParty,
            new RecordedReads(writer, IDS, CLOCK), writer, IDS, CLOCK, transactions());

    @Test
    @DisplayName("one purchase's refunds walk once, oldest first, each amount an exact decimal; every page recorded"
            + " bnpl.RefundsRead against the purchase, never an amount in the summary")
    void aPurchasesRefundsWalkOnce() {
        BnplPurchaseId purchase = BnplPurchaseId.of(IDS.next());
        lists.parties.put(purchase, UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            lists.refunds.add(refund(purchase, Instant.parse("2026-11-01T00:00:00Z").plusSeconds(i * 60L), 10_00 * (i + 1)));
        }
        List<BnplListDesk.BnplPurchaseRefundView> walked = new ArrayList<>();
        String cursor = null;
        do {
            String after = cursor;
            BnplListDesk.BnplPurchaseRefundPage page = as(() -> desk.refunds(purchase.value().toString(), after, "2"));
            walked.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(walked).extracting(BnplListDesk.BnplPurchaseRefundView::amount).containsExactly("10.00", "20.00", "30.00");
        assertThat(walked).allSatisfy(view -> {
            assertThat(view.currency()).isEqualTo("EUR");
            assertThat(view.door()).isEqualTo("OPERATOR");
        });
        assertThat(written).hasSize(2).allSatisfy(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.REFUNDS_READ);
            assertThat(record.targetId()).isEqualTo(purchase.value().toString());
            assertThat(record.changeSummary().orElseThrow()).doesNotContain("10.00", "20.00", "30.00");
        });
    }

    @Test
    @DisplayName("a purchase of the reader's own party, or a reader the platform cannot place, is 403 bnpl.SelfDealingRefused"
            + " recorded FAILED with nothing served; an unknown or malformed purchase is the uniform 404 bnpl.NotFound")
    void ownPartyUnplacedAndUnknownPurchases() {
        BnplPurchaseId own = BnplPurchaseId.of(IDS.next());
        lists.parties.put(own, READERS_PARTY);
        assertError(() -> desk.refunds(own.value().toString(), null, null), BnplErrorCode.SELF_DEALING_REFUSED);
        readersParty = Optional.empty();
        BnplPurchaseId theirs = BnplPurchaseId.of(IDS.next());
        lists.parties.put(theirs, UUID.randomUUID());
        assertError(() -> desk.refunds(theirs.value().toString(), null, null), BnplErrorCode.SELF_DEALING_REFUSED);
        assertError(() -> desk.agreements(null, null, null, null), BnplErrorCode.SELF_DEALING_REFUSED);
        assertError(() -> desk.servicingRequests(null, null, null), BnplErrorCode.SELF_DEALING_REFUSED);
        assertThat(lists.read).as("nothing was read").isZero();
        assertThat(written).hasSize(4).allSatisfy(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.SELF_DEALING_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
        });
        assertThat(written).extracting(AuditRecord::targetType)
                .containsExactly("bnpl_purchase", "bnpl_purchase", "bnpl_agreements", "bnpl_servicing_requests");
        written.clear();
        readersParty = Optional.of(READERS_PARTY);
        assertError(() -> desk.refunds(IDS.next().toString(), null, null), BnplErrorCode.NOT_FOUND);
        assertError(() -> desk.refunds(UUID.randomUUID().toString(), null, null), BnplErrorCode.NOT_FOUND);
        assertError(() -> desk.refunds("not-a-purchase", null, null), BnplErrorCode.NOT_FOUND);
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("the lists withhold the reader's own party and pass the filter whole; the queue is in the pending-decision"
            + " shape (P17-TSK-040) - bnpl.<Kind>, the agreement its subject, a reversal's repayment its related id, the proposer"
            + " and their reason named")
    void theListsWithholdTheReadersParty() {
        UUID merchant = UUID.randomUUID();
        as(() -> desk.agreements("ACTIVE", merchant.toString(), null, null));
        assertThat(lists.withheld).isEqualTo(READERS_PARTY);
        assertThat(lists.filter).isEqualTo("ACTIVE|" + merchant);
        as(() -> desk.applications("OFFERED", null, null));
        assertThat(lists.filter).isEqualTo("OFFERED");
        UUID repayment = UUID.randomUUID();
        lists.servicing.add(new BnplOperatorLists.ServicingRequest(IDS.next(), BnplOperatorLists.ServicingKind.REPAYMENT_REVERSAL,
                BnplAgreementId.of(IDS.next()), "another-agent", Instant.parse("2026-11-01T00:00:00Z"), "the payment bounced",
                Optional.of(repayment)));
        PendingDecisions.PendingDecision view = as(() -> desk.servicingRequests("PROPOSED", null, null)).items().getFirst();
        assertThat(view.kind()).isEqualTo("bnpl.RepaymentReversal");
        assertThat(view.subjectId()).isEqualTo(lists.servicing.getFirst().agreement().value().toString());
        assertThat(view.proposedBy()).isEqualTo("another-agent");
        assertThat(view.reasonSummary()).isEqualTo("the payment bounced");
        assertThat(view.relatedId()).as("a reversal names its repayment").isEqualTo(repayment.toString());
        assertThat(written).extracting(AuditRecord::operation).containsExactly(BnplAuditAction.AGREEMENTS_READ,
                BnplAuditAction.APPLICATIONS_READ, BnplAuditAction.SERVICING_REQUESTS_READ);
    }

    @Test
    @DisplayName("the request rules: a queue's status PROPOSED or absent, a merchant a UUID, a status of the vocabulary, a limit"
            + " 1..200 - each 422 api.ValidationFailed, nothing read or recorded")
    void theRequestRules() {
        assertError(() -> desk.servicingRequests("REJECTED", null, null), PlatformErrorCode.VALIDATION_FAILED);
        assertError(() -> desk.agreements(null, "a-merchant", null, null), PlatformErrorCode.VALIDATION_FAILED);
        assertError(() -> desk.agreements("OPEN", null, null, null), PlatformErrorCode.VALIDATION_FAILED);
        assertError(() -> desk.applications(null, null, "0"), PlatformErrorCode.VALIDATION_FAILED);
        assertError(() -> desk.refunds(IDS.next().toString(), null, "1000"), PlatformErrorCode.VALIDATION_FAILED);
        assertThat(lists.read).isZero();
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private <T> T as(Supplier<T> read) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(READER)) {
            return read.get();
        }
    }

    private void assertError(Runnable request, com.finapp.platform.api.ErrorCode code) {
        assertThatThrownBy(() -> as(() -> {
            request.run();
            return null;
        })).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @SuppressWarnings("unchecked")
    private static BnplTransactions transactions() {
        BnplTransactions transactions = mock(BnplTransactions.class);
        when(transactions.inTransaction(any())).thenAnswer(call -> ((Function<Connection, Object>) call.getArgument(0)).apply(null));
        return transactions;
    }

    private static BnplOperatorLists.RefundHead refund(BnplPurchaseId purchase, Instant created, long minor) {
        Money zero = Money.zero(EUR);
        return new BnplOperatorLists.RefundHead(IDS.next(), purchase, "OPERATOR", "an-operator", Money.ofMinorUnits(minor, EUR),
                zero, zero, Money.ofMinorUnits(minor, EUR), Money.ofMinorUnits(minor, EUR), zero, zero, "NONE", zero, zero,
                BnplAgreementId.of(IDS.next()), 2, 2, "goods returned", IDS.next(), LocalDate.of(2026, 11, 1), created);
    }

    /** The read model over lists, honouring the refunds' ascending keyset and the limit as the SQL does. */
    private static final class Fake implements BnplOperatorLists {

        final Map<BnplPurchaseId, UUID> parties = new HashMap<>();
        final List<RefundHead> refunds = new ArrayList<>();
        final List<ServicingRequest> servicing = new ArrayList<>();
        UUID withheld;
        String filter;
        int read;

        @Override
        public List<ApplicationHead> applications(Connection unitOfWork, Optional<BnplApplicationStatus> status,
                UUID withheldParty, Optional<Cursor> after, int limit) {
            read++;
            withheld = withheldParty;
            filter = status.map(Enum::name).orElse("");
            return List.of();
        }

        @Override
        public List<AgreementHead> agreements(Connection unitOfWork, Optional<BnplObligationStatus> status,
                Optional<UUID> merchant, UUID withheldParty, Optional<Cursor> after, int limit) {
            read++;
            withheld = withheldParty;
            filter = status.map(Enum::name).orElse("") + "|" + merchant.map(UUID::toString).orElse("");
            return List.of();
        }

        @Override
        public Optional<UUID> purchaseParty(Connection unitOfWork, BnplPurchaseId purchase) {
            return Optional.ofNullable(parties.get(purchase));
        }

        @Override
        public List<RefundHead> refunds(Connection unitOfWork, BnplPurchaseId purchase, Optional<Cursor> after, int limit) {
            read++;
            Comparator<RefundHead> order = Comparator.comparing(RefundHead::createdAt).thenComparing(RefundHead::id);
            return refunds.stream().filter(refund -> refund.purchase().equals(purchase)).sorted(order)
                    .filter(refund -> after.isEmpty() || refund.createdAt().isAfter(after.get().at())
                            || (refund.createdAt().equals(after.get().at()) && refund.id().compareTo(after.get().id()) > 0))
                    .limit(limit).toList();
        }

        @Override
        public List<ServicingRequest> pendingServicing(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after,
                int limit) {
            read++;
            withheld = withheldParty;
            return servicing.stream().limit(limit).toList();
        }

        @Override
        public List<PendingProposal> pendingTermsVersions(Connection unitOfWork, Optional<Cursor> after, int limit) {
            throw new UnsupportedOperationException("the pending queues are not under test here");
        }

        @Override
        public List<PendingProposal> pendingRefundProposals(Connection unitOfWork, UUID withheldParty, Optional<Cursor> after,
                int limit) {
            throw new UnsupportedOperationException("the pending queues are not under test here");
        }

        @Override
        public List<PendingProposal> pendingDisputeResolutions(Connection unitOfWork, UUID withheldParty,
                Optional<Cursor> after, int limit) {
            throw new UnsupportedOperationException("the pending queues are not under test here");
        }
    }
}
