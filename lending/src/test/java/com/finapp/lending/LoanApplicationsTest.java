package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The loan application, hermetic (`P11-TSK-010`; {@code LENDING_LIFECYCLES.md} section 4.3; {@code INV-LIFE-01},
 * {@code INV-LIFE-02}): T-app's order - standing, the terms pin, the day judged, credit's request, the application born
 * {@code SUBMITTED} and moved {@code AWAITING_DECISION} - every refusal before credit is asked writing nothing and
 * asking nothing, credit's refusals translated or passed through untouched, the withdrawal only from
 * {@code AWAITING_DECISION} and only while credit's request is cancellable, and no event carrying a figure. The
 * database's ranks and the transaction's atomicity are {@code LoanApplicationDatabaseTest}'s.
 */
@DisplayName("the loan application: T-app's order, the refusals, the withdrawal (P11-TSK-010)")
class LoanApplicationsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final UUID PARTY = UUID.randomUUID();
    private static final Actor CUSTOMER = new Actor("identity-1", ActorType.CUSTOMER);
    private static final CorrelationId CORRELATION = CorrelationId.of("p11t10-unit");
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            LoanApplicationsTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private final List<String> calls = new ArrayList<>();
    private final List<String> published = new ArrayList<>();
    private final FakeApplications store = new FakeApplications();
    private final FakeCredit credit = new FakeCredit();
    private final Map<LoanProduct, LoanTermsStore.LoanTermsVersion> active = new HashMap<>();
    private boolean inGoodStanding = true;

    private final LoanApplications applications = new LoanApplications(
            store,
            new LoanTermsAdministration(terms(), (uow, record) -> {}, (OutboxWriter<Connection>) (uow, envelope, payload,
                    mediaType) -> {}, IDS, CLOCK, LoanTermsFixtures.BOUNDS),
            (uow, party) -> {
                calls.add("standing");
                return inGoodStanding;
            },
            credit,
            (OutboxWriter<Connection>) (uow, envelope, payload, mediaType) ->
                    published.add(envelope.eventType() + " " + new String(payload, StandardCharsets.UTF_8)),
            IDS,
            CLOCK);

    // ------------------------------------------------------------------ the submission

    @Test
    @DisplayName("one act, in T-app's order: standing, the terms pin, credit's request, the application SUBMITTED then"
            + " AWAITING_DECISION with both history rows, and the event - naming the ids, never a figure")
    void oneActInOrder() {
        activate(LoanProduct.PERSONAL_LOAN);
        LoanApplication born = applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION);
        assertThat(calls).containsExactly("standing", "pin PERSONAL_LOAN", "credit.open", "insert", "event SUBMITTED",
                "SUBMITTED->AWAITING_DECISION", "event AWAITING_DECISION");
        assertThat(born.status()).isEqualTo(LoanApplicationStatus.AWAITING_DECISION);
        assertThat(born.decisionRequest()).isEqualTo(credit.opened.get(0));
        assertThat(born.termsVersionId()).isEqualTo(active.get(LoanProduct.PERSONAL_LOAN).row().id());
        assertThat(store.history).extracting(LoanApplicationStore.HistoryRow::reason)
                .containsExactly("APPLICATION_SUBMITTED", "CREDIT_REQUEST_OPENED");
        assertThat(published).singleElement().satisfies(event -> assertThat(event)
                .startsWith(LoanApplications.SUBMITTED_EVENT)
                .contains(born.id().value().toString(), born.decisionRequest().toString(), "PERSONAL_LOAN"));
        // The figures are looked for with the identifiers masked: a random UUID's hex holds "36" in about a third of
        // runs, which made this check flaky without making it any stronger (found by P11-TSK-009's verification).
        assertThat(published.get(0).replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>"))
                .doesNotContain("10000", "3200", "1400", "36");
        assertThat(credit.asked.get(0).toString()).doesNotContain("10000");
    }

    @Test
    @DisplayName("a party not in good standing, an unoffered product and a day the terms do not offer are refused before"
            + " credit is asked - nothing written, nothing published")
    void refusalsBeforeCreditAskNothing() {
        inGoodStanding = false;
        activate(LoanProduct.PERSONAL_LOAN);
        assertThatThrownBy(() -> applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.NotInGoodStanding.class);
        inGoodStanding = true;
        assertThatThrownBy(() -> applications.submit(UOW, PARTY, line(), CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.ProductNotOffered.class);
        assertThatThrownBy(() -> applications.submit(UOW, PARTY, loan(11), CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.AmountOutOfRange.class)
                .hasMessageContaining("repayment day");
        assertThat(credit.opened).isEmpty();
        assertThat(store.rows).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("credit's open request is ApplicationOpen, credit's bounds AmountOutOfRange, lending's own one-open"
            + " backstop ApplicationOpen; credit's other refusals pass through untouched - nothing of lending's written")
    void creditsRefusals() {
        activate(LoanProduct.PERSONAL_LOAN);
        credit.refusal = new LoanCreditDecisions.RequestOpen();
        assertThatThrownBy(() -> applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.ApplicationOpen.class);
        credit.refusal = new LoanCreditDecisions.TermsOutOfBounds("the amount is 500.00 to 25000.00 EUR");
        assertThatThrownBy(() -> applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.AmountOutOfRange.class)
                .hasMessage("the amount is 500.00 to 25000.00 EUR");
        RuntimeException creditsOwn = new IllegalStateException("credit's consent refusal");
        credit.refusal = creditsOwn;
        assertThatThrownBy(() -> applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION)).isSameAs(creditsOwn);
        assertThat(store.rows).isEmpty();
        credit.refusal = null;
        store.oneOpenTaken = true;
        assertThatThrownBy(() -> applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.ApplicationOpen.class);
        assertThat(store.history).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a credit line pays out to the wallet only (A25)")
    void aLinePaysOutToTheWallet() {
        assertThatThrownBy(() -> new LoanApplications.Submission(LoanProduct.CREDIT_LINE, eur(2_000_00), Optional.empty(),
                        Optional.empty(), Optional.empty(), 5, RepaymentMethod.WALLET, PayoutPreference.EXTERNAL_ACCOUNT))
                .isInstanceOf(LoanApplications.SubmissionInvalid.class);
        assertThat(new LoanApplications.Submission(LoanProduct.PERSONAL_LOAN, eur(10_000_00), Optional.of(36),
                Optional.empty(), Optional.empty(), 5, RepaymentMethod.WALLET, PayoutPreference.EXTERNAL_ACCOUNT)
                .toString()).doesNotContain("10000");
    }

    // ------------------------------------------------------------------ the withdrawal

    @Test
    @DisplayName("a withdrawal locks the application, cancels credit's request and moves it WITHDRAWN with its history and"
            + " the closed event")
    void aWithdrawalCancelsCreditsRequest() {
        activate(LoanProduct.PERSONAL_LOAN);
        LoanApplication born = applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION);
        calls.clear();
        published.clear();
        LoanApplication withdrawn = applications.withdraw(UOW, born.id(), PARTY, CUSTOMER, CORRELATION);
        assertThat(calls).containsExactly("lock", "credit.cancel", "AWAITING_DECISION->WITHDRAWN", "event WITHDRAWN");
        assertThat(withdrawn.status()).isEqualTo(LoanApplicationStatus.WITHDRAWN);
        assertThat(published).singleElement().satisfies(event -> assertThat(event)
                .startsWith(LoanApplications.CLOSED_EVENT).contains("WITHDRAWN", "CUSTOMER_WITHDREW"));
    }

    @Test
    @DisplayName("a withdrawal after credit's evaluation, or of a closed application, is ApplicationNotWithdrawable -"
            + " nothing moved; another party's is ApplicationNotFound")
    void theWithdrawalRefusals() {
        activate(LoanProduct.PERSONAL_LOAN);
        LoanApplication born = applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION);
        credit.cancellable = false;
        assertThatThrownBy(() -> applications.withdraw(UOW, born.id(), PARTY, CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.ApplicationNotWithdrawable.class)
                .hasMessageContaining("decision will arrive");
        assertThat(store.rows.get(born.id().value()).status()).isEqualTo(LoanApplicationStatus.AWAITING_DECISION);
        assertThatThrownBy(() -> applications.withdraw(UOW, born.id(), UUID.randomUUID(), CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.ApplicationNotFound.class);
        credit.cancellable = true;
        applications.withdraw(UOW, born.id(), PARTY, CUSTOMER, CORRELATION);
        int cancels = credit.cancelled.size();
        assertThatThrownBy(() -> applications.withdraw(UOW, born.id(), PARTY, CUSTOMER, CORRELATION))
                .isInstanceOf(LoanApplications.ApplicationNotWithdrawable.class)
                .hasMessageContaining("WITHDRAWN");
        assertThat(credit.cancelled).as("a terminal application never asks credit").hasSize(cancels);
    }

    @Test
    @DisplayName("the owner's read: credit's reasons only once DECLINED; another party's is empty")
    void theRead() {
        activate(LoanProduct.PERSONAL_LOAN);
        LoanApplication born = applications.submit(UOW, PARTY, loan(10), CUSTOMER, CORRELATION);
        assertThat(applications.read(UOW, born.id(), PARTY).orElseThrow().reasonTexts()).isEmpty();
        assertThat(applications.read(UOW, born.id(), UUID.randomUUID())).isEmpty();
        store.force(born.id(), LoanApplicationStatus.DECLINED);
        assertThat(applications.read(UOW, born.id(), PARTY).orElseThrow().reasonTexts())
                .containsExactly("Your income could not support the repayments.");
    }

    // ------------------------------------------------------------------ fixtures

    private static LoanApplications.Submission loan(int day) {
        return new LoanApplications.Submission(LoanProduct.PERSONAL_LOAN, eur(10_000_00), Optional.of(36),
                Optional.of(eur(3_200_00)), Optional.of(eur(1_400_00)), day, RepaymentMethod.WALLET, PayoutPreference.WALLET);
    }

    private static LoanApplications.Submission line() {
        return new LoanApplications.Submission(LoanProduct.CREDIT_LINE, eur(2_000_00), Optional.empty(), Optional.empty(),
                Optional.empty(), 5, RepaymentMethod.WALLET, PayoutPreference.WALLET);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, LoanTermsFixtures.EUR);
    }

    private void activate(LoanProduct product) {
        LoanTerms terms = LoanTermsFixtures.of(product, "0.0899").build();
        active.put(product, new LoanTermsStore.LoanTermsVersion(new LoanTermsStore.VersionRow(LoanTermsVersionId.next(IDS),
                product, 1, LoanTermsVersionStatus.ACTIVE, "alice", CLOCK.instant(), Optional.of("bob"),
                Optional.of(CLOCK.instant()), Optional.of(CLOCK.instant()), Optional.empty()), terms));
    }

    /** The two reads the pin makes; any other call is a test defect. */
    private LoanTermsStore terms() {
        return (LoanTermsStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {LoanTermsStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "shareActive" -> {
                        calls.add("pin " + args[1]);
                        yield Optional.ofNullable(active.get((LoanProduct) args[1])).map(v -> v.row().id());
                    }
                    case "version" -> active.values().stream()
                            .filter(v -> v.row().id().equals(args[1])).findFirst();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private final class FakeCredit implements LoanCreditDecisions {
        final List<UUID> opened = new ArrayList<>();
        final List<CreditTerms> asked = new ArrayList<>();
        final List<UUID> cancelled = new ArrayList<>();
        RuntimeException refusal;
        boolean cancellable = true;

        @Override
        public UUID open(Connection unitOfWork, UUID party, CreditTerms terms, Actor actor, CorrelationId correlation) {
            calls.add("credit.open");
            if (refusal != null) {
                throw refusal;
            }
            UUID id = UUID.randomUUID();
            opened.add(id);
            asked.add(terms);
            return id;
        }

        @Override
        public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                CorrelationId correlation) {
            calls.add("credit.cancel");
            if (!cancellable) {
                throw new NotCancellable();
            }
            cancelled.add(decisionRequest);
        }

        @Override
        public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
            return List.of("Your income could not support the repayments.");
        }

        @Override
        public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
            throw new UnsupportedOperationException("the application never reads a decision");
        }

        @Override
        public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
            throw new UnsupportedOperationException("the application never resolves a decision");
        }
    }

    private final class FakeApplications implements LoanApplicationStore {
        final Map<UUID, LoanApplication> rows = new HashMap<>();
        final List<HistoryRow> history = new ArrayList<>();
        boolean oneOpenTaken;

        @Override
        public boolean insert(Connection unitOfWork, NewApplication application) {
            calls.add("insert");
            if (oneOpenTaken) {
                return false;
            }
            rows.put(application.id().value(), new LoanApplication(application.id(), application.party(),
                    application.product(), LoanApplicationStatus.SUBMITTED, application.decisionRequest(),
                    application.termsVersion(), 1, application.repaymentDay(), application.repaymentMethod(),
                    application.payoutPreference(), CLOCK.instant(), Optional.empty(), application.correlation()));
            return true;
        }

        @Override
        public boolean transition(
                Connection unitOfWork, LoanApplicationId id, LoanApplicationStatus from, LoanApplicationStatus to) {
            calls.add(from + "->" + to);
            LoanApplication row = rows.get(id.value());
            if (row == null || row.status() != from) {
                return false;
            }
            force(id, to);
            return true;
        }

        void force(LoanApplicationId id, LoanApplicationStatus to) {
            LoanApplication row = rows.get(id.value());
            rows.put(id.value(), new LoanApplication(row.id(), row.party(), row.product(), to, row.decisionRequest(),
                    row.termsVersionId(), row.termsVersion(), row.repaymentDay(), row.repaymentMethod(),
                    row.payoutPreference(), row.submittedAt(),
                    to == LoanApplicationStatus.AWAITING_DECISION ? Optional.empty() : Optional.of(CLOCK.instant()),
                    row.correlation()));
        }

        @Override
        public void appendEvent(Connection unitOfWork, HistoryRow row) {
            calls.add("event " + row.to());
            history.add(row);
        }

        @Override
        public Optional<LoanApplication> ownedBy(Connection unitOfWork, LoanApplicationId id, UUID party) {
            return Optional.ofNullable(rows.get(id.value())).filter(row -> row.party().equals(party));
        }

        @Override
        public Optional<LoanApplication> lockOwnedBy(Connection unitOfWork, LoanApplicationId id, UUID party) {
            calls.add("lock");
            return ownedBy(unitOfWork, id, party);
        }

        @Override
        public List<LoanApplicationId> claimAwaiting(Connection unitOfWork, int limit, java.time.Duration permit) {
            throw new UnsupportedOperationException("the application never claims");
        }

        @Override
        public Optional<LoanApplication> lockClaimed(Connection unitOfWork, LoanApplicationId id) {
            throw new UnsupportedOperationException("the application never locks a claimed row");
        }

        @Override
        public boolean hint(Connection unitOfWork, UUID decisionRequest) {
            throw new UnsupportedOperationException("the application never hints");
        }
    }
}
