package com.finapp.bnpl;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The BNPL application's world, hermetic (`P12-TSK-010`): an in-memory application store holding {@code bnpl V003}'s
 * arbiters (the session's and the party-and-product's partial uniques, {@code UNIQUE (decision_request_id)}, the
 * conditional edges, owner-scoped reads), credit's decision requests with credit's one-open-request rule and its
 * cancellation, the checkout session read by token, the merchant's standing and fee coverage, the terms pin, and a
 * {@link #inTransaction unit of work} that rolls every fake back on a thrown refusal - so "nothing written" is asserted
 * on the fakes' state exactly as the database tests assert it on rows. Every port call is logged in order, so the
 * submission's sequence is observable. The database's own ranks are {@code BnplApplicationStoreDatabaseTest}'s.
 *
 * <p>Extended by `P12-TSK-011` with the origination step's world: the application's permit, claim, system lock, closure
 * with its reason and hints; credit's decisions ({@link Credit#approve}, {@link Credit#decline}) behind
 * {@code decisionFor}; the session read by reference on a database clock ({@link #now}); and {@link Offers}, an in-memory
 * {@code bnpl V004} - {@code UNIQUE (application_id)}, {@code UNIQUE (decision_id)}, the expiry stamped as
 * {@code LEAST(decision validity, session expiry, offered_at + validity)}, never born expired, the decline and the expiry
 * complementary on the clock, the terms hash held to the bytes, and the application's {@code -> OFFERED} only beside its
 * offer.
 *
 * <p>Extended by `P12-TSK-013` with the purchase's world (T-pur): credit's profile lock and its consumption under its two
 * uniques and its clock; checkout's session locked and judged at once, completed by financing with its order; the
 * merchant's payable and its fee pin; lending capital's headroom over the book's BNPL principal; the wallet; and
 * {@link Purchases} and {@link Book}, in-memory {@code bnpl V006} and ledger - the uniques, the born-once rows, the
 * balanced, once-only postings and the derived balances. Every step of the purchase is logged in order and can be made to
 * fail ({@link #failAt}), so "a failure at every step leaves nothing" is asserted on the fakes' state.
 */
final class BnplApplicationFakes {

    static final Connection UOW = (Connection) Proxy.newProxyInstance(BnplApplicationFakes.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private static final com.finapp.sharedkernel.id.IdGenerator IDS = new com.finapp.sharedkernel.id.IdGenerator(
            java.time.Clock.systemUTC(), new java.security.SecureRandom());

    /** Every port call, in order. */
    final List<String> log = new ArrayList<>();

    final Store store = new Store();
    final Credit credit = new Credit();
    final Sessions sessions = new Sessions();
    final Merchants merchants = new Merchants();
    final Terms terms = new Terms();
    final Offers offers = new Offers();
    final Purchases purchases = new Purchases();
    final Book book = new Book();
    final Capital capital = new Capital();
    final Wallets wallets = new Wallets();
    final Servicing servicing = new Servicing();
    final Repayments repayments = new Repayments();
    final Accruals accruals = new Accruals();
    final Refunds refunds = new Refunds();
    final Collections collections = new Collections();
    final Payoffs payoffs = new Payoffs();
    final Conditions conditions = new Conditions();
    final Corrections corrections = new Corrections();
    /** The purchase step that throws when reached - {@code null} for none. */
    String failAt;
    /** What happens in the world as a purchase step is reached - a clock moving, a rival committing. */
    final Map<String, Runnable> at = new LinkedHashMap<>();
    /** The database clock - what every stamp and every clock-sided conditional reads. */
    Instant now = Instant.parse("2026-10-17T10:10:00Z");
    final List<EventEnvelope> events = new ArrayList<>();
    final List<String> payloads = new ArrayList<>();
    final List<AuditRecord> audited = new ArrayList<>();
    boolean inGoodStanding = true;

    final OutboxWriter<Connection> outbox = (uow, envelope, payload, mediaType) -> {
        log.add("event " + envelope.eventType());
        events.add(envelope);
        payloads.add(new String(payload, StandardCharsets.UTF_8));
    };
    final AuditWriter<Connection> audit = (uow, record) -> {
        log.add("audit " + record.operation().code());
        audited.add(record);
    };
    final BnplPartyStanding standing = (uow, party) -> {
        log.add("standing");
        return inGoodStanding;
    };

    /**
     * {@code work} as one transaction: on a thrown exception every fake is restored to its state before - the
     * database's rollback - and the exception rethrown.
     */
    <T> T inTransaction(Supplier<T> work) {
        Map<UUID, BnplApplication> rows = new LinkedHashMap<>(store.rows);
        List<BnplApplicationStore.HistoryRow> history = new ArrayList<>(store.history);
        Map<UUID, Credit.Request> requests = new LinkedHashMap<>(credit.requests);
        Map<UUID, Instant> permits = new LinkedHashMap<>(store.permits);
        Map<UUID, BnplOffer> offerRows = new LinkedHashMap<>(offers.rows);
        Map<UUID, BnplOfferStore.StoredTerms> offerTerms = new LinkedHashMap<>(offers.terms);
        List<BnplOfferStore.HistoryRow> offerHistory = new ArrayList<>(offers.history);
        Map<UUID, UUID> consumptions = new LinkedHashMap<>(credit.consumptions);
        Map<String, BnplCheckoutSessions.Session> sessionRows = new LinkedHashMap<>(sessions.byToken);
        Map<UUID, UUID> financedBy = new LinkedHashMap<>(sessions.financedBy);
        Map<UUID, MerchantFeeSplit> pins = new LinkedHashMap<>(merchants.pins);
        Purchases.State purchaseState = purchases.state();
        Book.State bookState = book.state();
        Servicing.State servicingState = servicing.state();
        Map<UUID, BnplClosure> closureState = repayments.state();
        List<BnplInterestAccrual> accrualState = accruals.state();
        Refunds.State refundState = refunds.state();
        List<BnplCollectionAttempt> attemptState = collections.state();
        Payoffs.State payoffState = payoffs.state();
        Conditions.State conditionState = conditions.state();
        Corrections.State correctionState = corrections.state();
        int eventCount = events.size();
        int payloadCount = payloads.size();
        int auditCount = audited.size();
        try {
            T result = work.get();
            // At commit (bnpl V011's deferred trigger): every AUTO_COLLECTION repayment is named by its attempt.
            collections.atCommit();
            // At commit (bnpl V014's deferred trigger): every payoff execution's obligation is CLOSED PAID_OFF_EARLY.
            payoffs.atCommit();
            // At commit (bnpl V016's deferred triggers): every APPROVED reversal negates every allocation of its repayment.
            corrections.atCommit();
            return result;
        } catch (RuntimeException refused) {
            store.rows.clear();
            store.rows.putAll(rows);
            store.history.clear();
            store.history.addAll(history);
            credit.requests.clear();
            credit.requests.putAll(requests);
            store.permits.clear();
            store.permits.putAll(permits);
            offers.rows.clear();
            offers.rows.putAll(offerRows);
            offers.terms.clear();
            offers.terms.putAll(offerTerms);
            offers.history.clear();
            offers.history.addAll(offerHistory);
            credit.consumptions.clear();
            credit.consumptions.putAll(consumptions);
            sessions.byToken.clear();
            sessions.byToken.putAll(sessionRows);
            sessions.financedBy.clear();
            sessions.financedBy.putAll(financedBy);
            merchants.pins.clear();
            merchants.pins.putAll(pins);
            purchases.restore(purchaseState);
            book.restore(bookState);
            servicing.restore(servicingState);
            repayments.restore(closureState);
            accruals.restore(accrualState);
            refunds.restore(refundState);
            collections.restore(attemptState);
            payoffs.restore(payoffState);
            conditions.restore(conditionState);
            corrections.restore(correctionState);
            events.subList(eventCount, events.size()).clear();
            payloads.subList(payloadCount, payloads.size()).clear();
            audited.subList(auditCount, audited.size()).clear();
            throw refused;
        }
    }

    /** A logged purchase step - the one {@link #failAt} names throws, as a crash or a refused write at that step. */
    void step(String name) {
        log.add(name);
        Runnable happening = at.remove(name);
        if (happening != null) {
            happening.run();
        }
        if (name.equals(failAt)) {
            throw new InjectedFailure(name);
        }
    }

    /** A failure injected at a purchase step. */
    static final class InjectedFailure extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        InjectedFailure(String step) {
            super("injected at " + step);
        }
    }

    // ------------------------------------------------------------------ bnpl V003, in memory

    final class Store implements BnplApplicationStore {

        static final Set<BnplApplicationStatus> OPEN_FOR_SESSION = EnumSet.of(BnplApplicationStatus.SUBMITTED,
                BnplApplicationStatus.AWAITING_DECISION, BnplApplicationStatus.OFFERED);
        static final Set<BnplApplicationStatus> OPEN_FOR_PARTY = EnumSet.of(BnplApplicationStatus.SUBMITTED,
                BnplApplicationStatus.AWAITING_DECISION);

        final Map<UUID, BnplApplication> rows = new LinkedHashMap<>();
        final List<HistoryRow> history = new ArrayList<>();
        /** Each application's permit, {@code next_step_at}. */
        final Map<UUID, Instant> permits = new LinkedHashMap<>();
        /** A failure injected at the named operation - "insert", "transition", "appendEvent", "close". */
        String failAt = "";

        @Override
        public boolean insert(Connection unitOfWork, NewApplication application) {
            log.add("insert");
            fail("insert");
            boolean conflict = rows.values().stream().anyMatch(row ->
                    (row.checkoutSession().equals(application.checkoutSession()) && OPEN_FOR_SESSION.contains(row.status()))
                            || (row.party().equals(application.party()) && row.product() == application.product()
                                    && OPEN_FOR_PARTY.contains(row.status()))
                            || row.decisionRequest().equals(application.decisionRequest()));
            if (conflict) {
                return false;
            }
            BnplTermsVersion version = terms.versions.get(application.termsVersion());
            rows.put(application.id().value(), new BnplApplication(application.id(), application.party(),
                    application.product(), application.plan(), BnplApplicationStatus.SUBMITTED,
                    application.checkoutSession(), application.merchant(), application.decisionRequest(),
                    application.termsVersion(), version.row().version(), Optional.empty(),
                    Instant.parse("2026-10-17T10:00:00Z"), Optional.empty(), application.correlation()));
            permits.put(application.id().value(), now);
            return true;
        }

        @Override
        public boolean transition(
                Connection unitOfWork, BnplApplicationId id, BnplApplicationStatus from, BnplApplicationStatus to) {
            log.add("transition " + from + " -> " + to);
            fail("transition");
            BnplApplication row = rows.get(id.value());
            if (row == null || row.status() != from) {
                return false;
            }
            put(row, to);
            return true;
        }

        @Override
        public void appendEvent(Connection unitOfWork, HistoryRow row) {
            log.add("history " + row.to());
            fail("appendEvent");
            history.add(row);
        }

        @Override
        public Optional<BnplApplication> ownedBy(Connection unitOfWork, BnplApplicationId id, UUID party) {
            return Optional.ofNullable(rows.get(id.value())).filter(row -> row.party().equals(party));
        }

        @Override
        public Optional<BnplApplication> lockOwnedBy(Connection unitOfWork, BnplApplicationId id, UUID party) {
            log.add("B1 " + id.value());
            return ownedBy(unitOfWork, id, party);
        }

        @Override
        public boolean close(Connection unitOfWork, BnplApplicationId id, BnplApplicationStatus to,
                Optional<BnplApplicationClosureReason> reason) {
            log.add("close " + to + reason.map(r -> " " + r).orElse(""));
            fail("close");
            BnplApplication row = rows.get(id.value());
            if (row == null || row.status() != BnplApplicationStatus.AWAITING_DECISION) {
                return false;
            }
            if ((to == BnplApplicationStatus.CLOSED_UNDECIDED) != reason.isPresent()) {
                throw new BnplStorageException("bnpl_application_closure_reason_coherent: SQLSTATE 23514");
            }
            // bnpl V004's rank: -> OFFERED only beside its offer.
            if (to == BnplApplicationStatus.OFFERED && offers.rows.values().stream()
                    .noneMatch(offer -> offer.application().equals(id))) {
                throw new BnplStorageException("a BNPL application is OFFERED only beside its offer: SQLSTATE P0001");
            }
            put(row, to, reason);
            return true;
        }

        @Override
        public List<BnplApplicationId> claimAwaiting(Connection unitOfWork, int limit, java.time.Duration permit) {
            log.add("claim applications");
            List<BnplApplicationId> claimed = new ArrayList<>();
            for (BnplApplication row : rows.values()) {
                if (claimed.size() < limit && row.status() == BnplApplicationStatus.AWAITING_DECISION
                        && !permits.get(row.id().value()).isAfter(now)) {
                    permits.put(row.id().value(), now.plus(permit));
                    claimed.add(row.id());
                }
            }
            return claimed;
        }

        @Override
        public Optional<BnplApplication> lockClaimed(Connection unitOfWork, BnplApplicationId id) {
            log.add("B1 " + id.value());
            return Optional.ofNullable(rows.get(id.value()));
        }

        @Override
        public boolean hintRequest(Connection unitOfWork, UUID decisionRequest) {
            log.add("hint request");
            return hint(row -> row.decisionRequest().equals(decisionRequest));
        }

        @Override
        public boolean hintSession(Connection unitOfWork, UUID checkoutSession) {
            log.add("hint session");
            return hint(row -> row.checkoutSession().equals(checkoutSession));
        }

        private boolean hint(java.util.function.Predicate<BnplApplication> which) {
            boolean hinted = false;
            for (BnplApplication row : rows.values()) {
                if (which.test(row) && row.status() == BnplApplicationStatus.AWAITING_DECISION
                        && permits.get(row.id().value()).isAfter(now)) {
                    permits.put(row.id().value(), now);
                    hinted = true;
                }
            }
            return hinted;
        }

        /** Moves a row as another writer would (the origination step, `P12-TSK-011`). */
        void put(BnplApplication row, BnplApplicationStatus to) {
            put(row, to, to == BnplApplicationStatus.CLOSED_UNDECIDED
                    ? Optional.of(BnplApplicationClosureReason.SESSION_CLOSED) : Optional.empty());
        }

        void put(BnplApplication row, BnplApplicationStatus to, Optional<BnplApplicationClosureReason> reason) {
            boolean terminal = to != BnplApplicationStatus.AWAITING_DECISION;
            rows.put(row.id().value(), new BnplApplication(row.id(), row.party(), row.product(), row.plan(), to,
                    row.checkoutSession(), row.merchant(), row.decisionRequest(), row.termsVersionId(),
                    row.termsVersion(), reason,
                    row.submittedAt(), terminal ? Optional.of(Instant.parse("2026-10-17T10:05:00Z")) : Optional.empty(),
                    row.correlation()));
        }

        private void fail(String operation) {
            if (failAt.equals(operation)) {
                throw new BnplStorageException("injected failure at " + operation);
            }
        }
    }

    // ------------------------------------------------------------------ credit's decision requests

    final class Credit implements BnplCreditDecisions {

        /** A request as credit holds it - its party, what was asked, its origin's actor and its status. */
        record Request(UUID party, BnplProduct product, Money amount, OptionalInt termMonths, Actor actor, String status) {}

        final Map<UUID, Request> requests = new LinkedHashMap<>();
        /** Each DECIDED request's decision. */
        final Map<UUID, CreditOutcome> decisions = new LinkedHashMap<>();
        /** Credit's bound refusal, when set. */
        Optional<String> outOfBounds = Optional.empty();
        List<String> reasons = List.of("We could not verify your income.");
        /** Credit's consumptions: decision -> the agreement that consumed it (credit's two uniques). */
        final Map<UUID, UUID> consumptions = new LinkedHashMap<>();
        boolean profiled = true;

        @Override
        public boolean lockExposure(Connection unitOfWork, UUID party) {
            step("L0");
            return profiled;
        }

        @Override
        public Consumption consume(Connection unitOfWork, UUID decision, BnplAgreementId agreement) {
            step("consume");
            Optional<CreditOutcome.Approved> approved = decisions.values().stream()
                    .filter(CreditOutcome.Approved.class::isInstance).map(CreditOutcome.Approved.class::cast)
                    .filter(found -> found.decision().equals(decision)).findFirst();
            if (approved.isEmpty()) {
                boolean declined = decisions.values().stream().anyMatch(found ->
                        found instanceof CreditOutcome.Declined refused && refused.decision().equals(decision));
                return new Consumption.Refused(declined ? Consumption.Refusal.NOT_APPROVED : Consumption.Refusal.NOT_FOUND);
            }
            UUID consumer = consumptions.get(decision);
            if (consumer != null) {
                return consumer.equals(agreement.value())
                        ? new Consumption.Consumed(decision, approved.get().party(), approved.get().amount())
                        : new Consumption.Refused(Consumption.Refusal.ALREADY_CONSUMED);
            }
            if (!approved.get().validUntil().isAfter(now)) {
                return new Consumption.Refused(Consumption.Refusal.LAPSED);
            }
            consumptions.put(decision, agreement.value());
            return new Consumption.Consumed(decision, approved.get().party(), approved.get().amount());
        }

        @Override
        public UUID open(Connection unitOfWork, UUID party, CreditRequest request, Actor actor, CorrelationId correlation) {
            log.add("credit open");
            if (outOfBounds.isPresent()) {
                throw new TermsOutOfBounds(outOfBounds.get());
            }
            // Credit's one-open-request partial unique per party and product.
            if (requests.values().stream().anyMatch(open -> open.party().equals(party)
                    && open.product() == request.product() && open.status().equals("SUBMITTED"))) {
                throw new RequestOpen();
            }
            UUID id = UUID.randomUUID();
            requests.put(id, new Request(party, request.product(), request.amount(), request.termMonths(), actor,
                    "SUBMITTED"));
            return id;
        }

        @Override
        public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                CorrelationId correlation) {
            log.add("credit cancel");
            Request request = requests.get(decisionRequest);
            if (request == null || !request.party().equals(party)) {
                throw new IllegalStateException("no such request of the party's");
            }
            if (!request.status().equals("SUBMITTED")) {
                throw new NotCancellable();
            }
            requests.put(decisionRequest, new Request(request.party(), request.product(), request.amount(),
                    request.termMonths(), request.actor(), "CANCELLED"));
        }

        @Override
        public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
            return reasons;
        }

        @Override
        public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
            log.add("credit decision");
            Request request = requests.get(decisionRequest);
            if (request == null || !request.party().equals(party)) {
                throw new IllegalStateException("an application's credit request is its own party's");
            }
            return switch (request.status()) {
                case "SUBMITTED", "EVALUATED" -> new CreditOutcome.Pending();
                case "CANCELLED" -> new CreditOutcome.Closed(CreditOutcome.Closure.CANCELLED);
                case "EXPIRED" -> new CreditOutcome.Closed(CreditOutcome.Closure.EXPIRED);
                case "ABANDONED" -> new CreditOutcome.Closed(CreditOutcome.Closure.ABANDONED);
                case "DECIDED" -> decisions.get(decisionRequest);
                default -> throw new IllegalStateException(request.status());
            };
        }

        @Override
        public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
            return decisions.entrySet().stream()
                    .filter(entry -> switch (entry.getValue()) {
                        case CreditOutcome.Approved approved -> approved.decision().equals(decision);
                        case CreditOutcome.Declined declined -> declined.decision().equals(decision);
                        default -> false;
                    })
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        /** Credit approves {@code minorUnits} on the request, valid until {@code validUntil}; the decision's id. */
        UUID approve(UUID decisionRequest, long minorUnits, Instant validUntil) {
            Request request = requests.get(decisionRequest);
            UUID decision = UUID.randomUUID();
            decisions.put(decisionRequest, new CreditOutcome.Approved(decision, request.party(), request.product(),
                    Money.ofMinorUnits(minorUnits, BnplTermsFixtures.EUR), request.termMonths(), validUntil));
            status(decisionRequest, "DECIDED");
            return decision;
        }

        /** Credit declines the request; the decision's id. */
        UUID decline(UUID decisionRequest) {
            UUID decision = UUID.randomUUID();
            decisions.put(decisionRequest, new CreditOutcome.Declined(decision));
            status(decisionRequest, "DECIDED");
            return decision;
        }

        /** Credit's request moves, as credit's own sweep or the customer's door would move it. */
        void status(UUID decisionRequest, String status) {
            Request request = requests.get(decisionRequest);
            requests.put(decisionRequest, new Request(request.party(), request.product(), request.amount(),
                    request.termMonths(), request.actor(), status));
        }

        /** Credit evaluates the request - past cancellation. */
        void evaluate(UUID decisionRequest) {
            Request request = requests.get(decisionRequest);
            requests.put(decisionRequest, new Request(request.party(), request.product(), request.amount(),
                    request.termMonths(), request.actor(), "EVALUATED"));
        }

        /** Credit's request closes - expired - leaving the application awaiting the sweep. */
        void expire(UUID decisionRequest) {
            Request request = requests.get(decisionRequest);
            requests.put(decisionRequest, new Request(request.party(), request.product(), request.amount(),
                    request.termMonths(), request.actor(), "EXPIRED"));
        }
    }

    // ------------------------------------------------------------------ checkout, merchant, terms

    final class Sessions implements BnplCheckoutSessions {

        final Map<String, Session> byToken = new LinkedHashMap<>();

        /** A session the token opens: open, unexpired, {@code grossMinor} EUR at {@code merchant}. */
        String open(UUID merchant, long grossMinor) {
            return put(new Session(UUID.randomUUID(), merchant, Money.ofMinorUnits(grossMinor, BnplTermsFixtures.EUR),
                    Instant.parse("2026-10-17T11:00:00Z"), true, false));
        }

        String put(Session session) {
            String token = "cs_" + UUID.randomUUID();
            byToken.put(token, session);
            return token;
        }

        @Override
        public Optional<Session> read(Connection unitOfWork, Sensitive<String> sessionToken) {
            log.add("session read");
            return Optional.ofNullable(byToken.get(sessionToken.expose()));
        }

        @Override
        public Optional<Session> byRef(Connection unitOfWork, UUID sessionRef) {
            log.add("session by ref");
            return byToken.values().stream().filter(session -> session.sessionRef().equals(sessionRef)).findFirst()
                    // The deadline on the database clock, read after the row.
                    .map(session -> new Session(session.sessionRef(), session.merchantRef(), session.gross(),
                            session.expiresAt(), session.open(), !now.isBefore(session.expiresAt())));
        }

        /** Checkout moves the session out of {@code OPEN} - paid by card, abandoned or expired. */
        void close(UUID sessionRef) {
            byToken.replaceAll((token, session) -> session.sessionRef().equals(sessionRef)
                    ? new Session(session.sessionRef(), session.merchantRef(), session.gross(), session.expiresAt(),
                            false, session.expired())
                    : session);
        }

        /** Each session completed by financing: session -> the purchase (checkout V004's financed_by_ref, set once). */
        final Map<UUID, UUID> financedBy = new LinkedHashMap<>();
        /** The financing entry each completion was asked to carry as its order's captured entry. */
        final Map<UUID, com.finapp.ledger.JournalEntryId> financingEntries = new LinkedHashMap<>();

        @Override
        public Lock lockForPurchase(Connection unitOfWork, UUID sessionRef) {
            step("B3");
            return judged(sessionRef).<Lock>map(NotPayable::new).orElseGet(() -> new Payable(row(sessionRef)));
        }

        @Override
        public Completion completeByFinancing(Connection unitOfWork, UUID sessionRef, BnplPurchaseId purchase,
                com.finapp.ledger.JournalEntryId financingEntry, com.finapp.sharedkernel.correlation.Correlation correlation) {
            step("complete session");
            Optional<Reason> refused = judged(sessionRef);
            if (refused.isPresent()) {
                return new NotPayable(refused.get());
            }
            financingEntries.put(sessionRef, financingEntry);
            if (financedBy.putIfAbsent(sessionRef, purchase.value()) != null) {
                throw new IllegalStateException("financed_by_ref is set once");
            }
            close(sessionRef);
            return new Completed(UUID.randomUUID());
        }

        /** A card or wallet confirmation won the session: it is no longer OPEN. */
        void paidByCard(UUID sessionRef) {
            close(sessionRef);
        }

        private Optional<Reason> judged(UUID sessionRef) {
            Session session = row(sessionRef);
            if (!session.open()) {
                return Optional.of(Reason.NOT_OPEN);
            }
            return now.isBefore(session.expiresAt()) ? Optional.empty() : Optional.of(Reason.EXPIRED);
        }

        private Session row(UUID sessionRef) {
            return byToken.values().stream().filter(session -> session.sessionRef().equals(sessionRef)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no such session"));
        }
    }

    final class Merchants implements BnplMerchants {

        final Map<UUID, Standing> standings = new LinkedHashMap<>();
        /** The smallest gross, in minor units, that covers the fee. */
        long coversFrom = 1;

        @Override
        public Standing standing(Connection unitOfWork, UUID merchantRef, BnplProduct product) {
            log.add("merchant standing");
            return standings.getOrDefault(merchantRef, Standing.NOT_TRADING);
        }

        @Override
        public boolean coversFee(Connection unitOfWork, UUID merchantRef, BnplProduct product, Money gross) {
            log.add("merchant fee");
            return standing(unitOfWork, merchantRef, product) == Standing.ENABLED && gross.minorUnits() >= coversFrom;
        }

        /** Each merchant's MERCHANT_PAYABLE. */
        final Map<UUID, com.finapp.ledger.LedgerAccountId> payables = new LinkedHashMap<>();
        /** The fee pinned per purchase (bnpl_fee_pin, the purchase its primary key). */
        final Map<UUID, MerchantFeeSplit> pins = new LinkedHashMap<>();
        /** The merchant's BNPL fee in basis points; HALF_EVEN, as FeeCalculation. */
        long feeBasisPoints = 400;
        final UUID feeScheduleVersion = UUID.randomUUID();

        @Override
        public Optional<com.finapp.ledger.LedgerAccountId> payableAccount(Connection unitOfWork, UUID merchantRef,
                com.finapp.sharedkernel.money.CurrencyCode currency) {
            return Optional.ofNullable(payables.get(merchantRef));
        }

        @Override
        public MerchantFeeSplit pinFee(Connection unitOfWork, UUID merchantRef, BnplProduct product,
                BnplPurchaseId purchaseId, Money gross) {
            step("fee pin");
            Standing standing = standing(unitOfWork, merchantRef, product);
            if (standing != Standing.ENABLED) {
                throw new NotOffered(standing);
            }
            if (pins.containsKey(purchaseId.value())) {
                throw new AlreadyPinned();
            }
            long fee = java.math.BigDecimal.valueOf(gross.minorUnits()).multiply(java.math.BigDecimal.valueOf(feeBasisPoints))
                    .divide(java.math.BigDecimal.valueOf(10_000), 0, java.math.RoundingMode.HALF_EVEN).longValueExact();
            if (fee >= gross.minorUnits()) {
                throw new BelowFee();
            }
            MerchantFeeSplit split = new MerchantFeeSplit(gross, Money.ofPersisted(fee, gross.currency(), gross.scale()),
                    Money.ofPersisted(gross.minorUnits() - fee, gross.currency(), gross.scale()), feeScheduleVersion);
            pins.put(purchaseId.value(), split);
            return split;
        }

        /** The merchant fee a refund returns: the reference arithmetic over the purchase's pin (P12-TSK-021). */
        @Override
        public Money returnedFee(Connection unitOfWork, BnplPurchaseId purchaseId, Money refundedBefore,
                Money refunded) {
            log.add("returned fee");
            MerchantFeeSplit pinned = pins.get(purchaseId.value());
            if (pinned == null) {
                throw new NotPinned();
            }
            return com.finapp.bnpl.testing.ReferenceMerchantFeeReturn.halfEven(pinned.gross(), pinned.fee())
                    .returnedFee(refundedBefore, refunded);
        }
    }

    /** The terms in force, behind a store answering only the pin's two reads. */
    final class Terms {

        final Map<BnplProduct, BnplTermsVersionId> active = new EnumMap<>(BnplProduct.class);
        final Map<BnplTermsVersionId, BnplTermsVersion> versions = new LinkedHashMap<>();

        BnplTermsVersion activate(BnplTerms terms, int number) {
            BnplTermsVersionId id = BnplTermsVersionId.of(IDS.next());
            Instant at = Instant.parse("2026-10-17T09:00:00Z");
            BnplTermsVersion version = new BnplTermsVersion(new BnplTermsStore.VersionRow(id, terms.product(), number,
                    BnplTermsVersionStatus.ACTIVE, "alice", at, Optional.of("bob"), Optional.of(at), Optional.of(at),
                    Optional.empty()), terms);
            versions.put(id, version);
            active.put(terms.product(), id);
            return version;
        }

        BnplTermsStore store() {
            return (BnplTermsStore) Proxy.newProxyInstance(BnplApplicationFakes.class.getClassLoader(),
                    new Class<?>[] {BnplTermsStore.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "shareActive" -> {
                            log.add("terms pin " + args[1]);
                            yield Optional.ofNullable(active.get((BnplProduct) args[1]));
                        }
                        case "version" -> Optional.ofNullable(versions.get((BnplTermsVersionId) args[1]));
                        case "active" -> Optional.ofNullable(active.get((BnplProduct) args[1]));
                        default -> throw new UnsupportedOperationException(method.getName() + ": not the pin's read");
                    });
        }
    }

    // ------------------------------------------------------------------ bnpl V004, in memory (P12-TSK-011)

    final class Offers implements BnplOfferStore {

        final Map<UUID, BnplOffer> rows = new LinkedHashMap<>();
        final Map<UUID, StoredTerms> terms = new LinkedHashMap<>();
        final List<HistoryRow> history = new ArrayList<>();
        final Map<UUID, Instant> permits = new LinkedHashMap<>();

        @Override
        public Offered insert(Connection unitOfWork, NewOffer offer) {
            log.add("offer insert");
            if (rows.values().stream().anyMatch(row -> row.application().equals(offer.application()))) {
                throw new BnplStorageException("bnpl_offer_application_unique: SQLSTATE 23505");
            }
            if (rows.values().stream().anyMatch(row -> row.decision().equals(offer.decision()))) {
                throw new BnplStorageException("bnpl_offer_decision_unique: SQLSTATE 23505");
            }
            BnplApplication application = store.rows.get(offer.application().value());
            if (application == null || application.status() != BnplApplicationStatus.AWAITING_DECISION
                    || !application.party().equals(offer.party()) || application.product() != offer.product()
                    || application.plan() != offer.plan()
                    || !application.checkoutSession().equals(offer.checkoutSession())) {
                throw new BnplStorageException("a BNPL offer is born for its own application AWAITING_DECISION: P0001");
            }
            if (!offer.termsVersion().equals(BnplApplicationFakes.this.terms.active.get(offer.product()))) {
                throw new BnplStorageException("a BNPL offer pins its product's ACTIVE terms version: P0001");
            }
            BnplTermsVersion version = BnplApplicationFakes.this.terms.versions.get(offer.termsVersion());
            Instant offeredAt = now;
            Instant expiresAt = java.util.stream.Stream.of(offer.decisionValidUntil(), offer.sessionExpiresAt(),
                    offeredAt.plus(java.time.Duration.ofMinutes(version.terms().offerValidityMinutes())))
                    .min(Instant::compareTo).orElseThrow();
            if (!expiresAt.isAfter(offeredAt)) {
                throw new BnplStorageException("a BNPL offer is never born expired: P0001");
            }
            rows.put(offer.id().value(), new BnplOffer(offer.id(), offer.application(), offer.party(), offer.product(),
                    offer.plan(), BnplOfferStatus.OFFERED, offer.decision(), offer.termsVersion(),
                    version.row().version(), offer.checkoutSession(), offeredAt, expiresAt, Optional.empty(),
                    offer.correlation()));
            permits.put(offer.id().value(), expiresAt);
            return new Offered(offeredAt, expiresAt);
        }

        @Override
        public void insertTerms(Connection unitOfWork, BnplOfferId offer, StoredTerms stored) {
            log.add("offer terms");
            BnplOffer row = rows.get(offer.value());
            if (row == null || row.status() != BnplOfferStatus.OFFERED || terms.containsKey(offer.value())) {
                throw new BnplStorageException("a BNPL offer's terms are born once beside it: P0001");
            }
            if (!java.util.Arrays.equals(BnplOfferTerms.sha256(stored.canonicalTerms()), stored.termsSha256())) {
                throw new BnplStorageException("bnpl_offer_terms_hash_is_the_terms: SQLSTATE 23514");
            }
            terms.put(offer.value(), stored);
        }

        @Override
        public void appendEvent(Connection unitOfWork, HistoryRow row) {
            log.add("offer history " + row.to());
            history.add(row);
        }

        @Override
        public Instant databaseNow(Connection unitOfWork) {
            return now;
        }

        @Override
        public List<BnplOfferId> claimDue(Connection unitOfWork, int limit, java.time.Duration permit) {
            log.add("claim offers");
            List<BnplOfferId> claimed = new ArrayList<>();
            for (BnplOffer row : rows.values()) {
                if (claimed.size() < limit && row.status() == BnplOfferStatus.OFFERED
                        && !permits.get(row.id().value()).isAfter(now)) {
                    permits.put(row.id().value(), now.plus(permit));
                    claimed.add(row.id());
                }
            }
            return claimed;
        }

        @Override
        public Optional<BnplOffer> lockClaimed(Connection unitOfWork, BnplOfferId id) {
            log.add("B2 " + id.value());
            return Optional.ofNullable(rows.get(id.value()));
        }

        @Override
        public Optional<BnplOffer> lockOwnedBy(Connection unitOfWork, BnplOfferId id, UUID party) {
            log.add("B2 " + id.value());
            return ownedBy(unitOfWork, id, party);
        }

        @Override
        public Optional<BnplOffer> ownedBy(Connection unitOfWork, BnplOfferId id, UUID party) {
            return Optional.ofNullable(rows.get(id.value())).filter(row -> row.party().equals(party));
        }

        @Override
        public Optional<BnplOffer> ofApplication(Connection unitOfWork, BnplApplicationId application) {
            return rows.values().stream().filter(row -> row.application().equals(application)).findFirst();
        }

        @Override
        public Optional<StoredTerms> terms(Connection unitOfWork, BnplOfferId id) {
            return Optional.ofNullable(terms.get(id.value()));
        }

        @Override
        public boolean expire(Connection unitOfWork, BnplOfferId id) {
            return move(id, BnplOfferStatus.EXPIRED, row -> !row.expiresAt().isAfter(now));
        }

        @Override
        public boolean decline(Connection unitOfWork, BnplOfferId id) {
            return move(id, BnplOfferStatus.DECLINED, row -> row.expiresAt().isAfter(now));
        }

        @Override
        public boolean accept(Connection unitOfWork, BnplOfferId id) {
            step("offer accept");
            // bnpl V006: ACCEPTED only beside the offer's FINANCED purchase.
            boolean financed = purchases.rows.values().stream().anyMatch(purchase -> purchase.offer().equals(id)
                    && purchase.status() == BnplPurchaseStatus.FINANCED);
            if (rows.containsKey(id.value()) && rows.get(id.value()).status() == BnplOfferStatus.OFFERED && !financed) {
                throw new BnplStorageException("a BNPL offer is ACCEPTED only beside its FINANCED purchase: P0001");
            }
            return move(id, BnplOfferStatus.ACCEPTED, row -> row.expiresAt().isAfter(now));
        }

        @Override
        public void permitAtExpiry(Connection unitOfWork, BnplOfferId id) {
            log.add("offer permit at expiry");
            BnplOffer row = rows.get(id.value());
            if (row != null && row.status() == BnplOfferStatus.OFFERED) {
                permits.put(id.value(), row.expiresAt());
            }
        }

        private boolean move(BnplOfferId id, BnplOfferStatus to, java.util.function.Predicate<BnplOffer> clockSide) {
            log.add("offer " + to);
            BnplOffer row = rows.get(id.value());
            if (row == null || row.status() != BnplOfferStatus.OFFERED || !clockSide.test(row)) {
                return false;
            }
            rows.put(id.value(), new BnplOffer(row.id(), row.application(), row.party(), row.product(), row.plan(), to,
                    row.decision(), row.termsVersionId(), row.termsVersion(), row.checkoutSession(), row.offeredAt(),
                    row.expiresAt(), Optional.of(now), row.correlation()));
            return true;
        }
    }

    // ------------------------------------------------------------------ bnpl V006, in memory (P12-TSK-013)

    final class Purchases implements BnplPurchaseStore {

        final Map<UUID, BnplPurchase> rows = new LinkedHashMap<>();
        final Map<UUID, MerchantFinancing> financings = new LinkedHashMap<>();
        final Map<UUID, BnplObligation> obligations = new LinkedHashMap<>();
        final Map<UUID, BnplAgreement> agreements = new LinkedHashMap<>();
        final Map<UUID, BnplAcceptanceEvidence> acceptances = new LinkedHashMap<>();
        final Map<UUID, StoredSchedule> schedules = new LinkedHashMap<>();
        /** Every schedule version, by obligation and version ({@code schedules} holds each obligation's latest). */
        final Map<String, StoredSchedule> versions = new LinkedHashMap<>();
        final Map<UUID, BnplBilling> billings = new LinkedHashMap<>();
        final Map<UUID, Repayment> repayments = new LinkedHashMap<>();
        final List<String> history = new ArrayList<>();

        /** Everything, for the unit of work's rollback. */
        record State(Map<UUID, BnplPurchase> rows, Map<UUID, MerchantFinancing> financings,
                Map<UUID, BnplObligation> obligations, Map<UUID, BnplAgreement> agreements,
                Map<UUID, BnplAcceptanceEvidence> acceptances, Map<UUID, StoredSchedule> schedules,
                Map<UUID, BnplBilling> billings, Map<UUID, Repayment> repayments, List<String> history,
                Map<String, StoredSchedule> versions) {}

        State state() {
            return new State(new LinkedHashMap<>(rows), new LinkedHashMap<>(financings), new LinkedHashMap<>(obligations),
                    new LinkedHashMap<>(agreements), new LinkedHashMap<>(acceptances), new LinkedHashMap<>(schedules),
                    new LinkedHashMap<>(billings), new LinkedHashMap<>(repayments), new ArrayList<>(history),
                    new LinkedHashMap<>(versions));
        }

        void restore(State state) {
            reset(rows, state.rows());
            reset(financings, state.financings());
            reset(obligations, state.obligations());
            reset(agreements, state.agreements());
            reset(acceptances, state.acceptances());
            reset(schedules, state.schedules());
            reset(billings, state.billings());
            reset(repayments, state.repayments());
            history.clear();
            history.addAll(state.history());
            reset(versions, state.versions());
        }

        @Override
        public Authorised insertPurchase(Connection unitOfWork, NewPurchase purchase) {
            step("purchase insert");
            // purchase UNIQUE (checkout_session_id), UNIQUE (offer_id).
            if (rows.values().stream().anyMatch(row -> row.checkoutSession().equals(purchase.checkoutSession()))) {
                throw new BnplStorageException("purchase_checkout_session_unique: SQLSTATE 23505");
            }
            if (rows.values().stream().anyMatch(row -> row.offer().equals(purchase.offer()))) {
                throw new BnplStorageException("purchase_offer_unique: SQLSTATE 23505");
            }
            BnplOffer offer = offers.rows.get(purchase.offer().value());
            if (offer == null || offer.status() != BnplOfferStatus.OFFERED) {
                throw new BnplStorageException("a BNPL purchase is born for its own party's OFFERED offer: P0001");
            }
            java.time.LocalDate date = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
            rows.put(purchase.id().value(), new BnplPurchase(purchase.id(), purchase.offer(), purchase.checkoutSession(),
                    purchase.party(), purchase.merchant(), purchase.product(), purchase.plan(), purchase.currency(),
                    BnplPurchaseStatus.AUTHORISED, date, now, Optional.empty(), Optional.empty(), purchase.correlation()));
            return new Authorised(date, now);
        }

        @Override
        public Optional<Instant> finance(Connection unitOfWork, BnplPurchaseId purchase, UUID order) {
            step("purchase FINANCED");
            BnplPurchase row = rows.get(purchase.value());
            if (row == null || row.status() != BnplPurchaseStatus.AUTHORISED) {
                return Optional.empty();
            }
            if (!financings.containsKey(purchase.value())) {
                throw new BnplStorageException("a BNPL purchase is FINANCED only beside its merchant financing: P0001");
            }
            rows.put(purchase.value(), new BnplPurchase(row.id(), row.offer(), row.checkoutSession(), row.party(),
                    row.merchant(), row.product(), row.plan(), row.currency(), BnplPurchaseStatus.FINANCED,
                    row.businessDate(), row.authorisedAt(), Optional.of(now), Optional.of(order), row.correlation()));
            return Optional.of(now);
        }

        @Override
        public void appendPurchaseEvent(Connection unitOfWork, HistoryRow row) {
            history.add("purchase " + row.to());
        }

        @Override
        public void insertFinancing(Connection unitOfWork, MerchantFinancing financing) {
            step("financing insert");
            if (financings.containsKey(financing.purchase().value())) {
                throw new BnplStorageException("merchant_financing_pk: SQLSTATE 23505");
            }
            if (financings.values().stream().anyMatch(row -> row.entry().equals(financing.entry()))) {
                throw new BnplStorageException("merchant_financing_journal_entry_unique: SQLSTATE 23505");
            }
            financings.put(financing.purchase().value(), financing);
        }

        @Override
        public Instant insertObligation(Connection unitOfWork, NewObligation obligation) {
            step("obligation insert");
            if (obligations.values().stream().anyMatch(row -> row.purchase().equals(obligation.purchase())
                    || row.decision().equals(obligation.decision()) || row.agreement().equals(obligation.agreement()))) {
                throw new BnplStorageException("bnpl_obligation unique: SQLSTATE 23505");
            }
            BnplPurchase purchase = rows.get(obligation.purchase().value());
            obligations.put(obligation.id().value(), new BnplObligation(obligation.id(), obligation.purchase(),
                    obligation.agreement(), obligation.party(), obligation.decision(), obligation.product(),
                    obligation.plan(), obligation.currency(), BnplObligationStatus.ACTIVE, purchase.businessDate(), now,
                    Optional.empty(), obligation.correlation()));
            return now;
        }

        @Override
        public void appendObligationEvent(Connection unitOfWork, ObligationHistoryRow row) {
            history.add("obligation " + row.to());
        }

        @Override
        public Instant insertAgreement(Connection unitOfWork, BnplAgreement agreement) {
            step("agreement insert");
            // bnpl V006: version 1 an exact copy of the OFFERED offer's terms.
            BnplOfferStore.StoredTerms stored = offers.terms.get(agreement.offer().value());
            if (agreement.version() != 1 || stored == null
                    || !stored.canonicalTerms().equals(agreement.canonicalTerms())
                    || !java.util.Arrays.equals(stored.termsSha256(), agreement.termsSha256())
                    || !java.util.Arrays.equals(stored.documentSha256(), agreement.documentSha256())) {
                throw new BnplStorageException("a BNPL agreement's version 1 is an exact copy of its offer's terms: P0001");
            }
            if (agreements.containsKey(agreement.id().value())) {
                throw new BnplStorageException("bnpl_agreement_pk: SQLSTATE 23505");
            }
            agreements.put(agreement.id().value(), agreement.stampedAt(now));
            return now;
        }

        @Override
        public Instant insertAcceptance(Connection unitOfWork, UUID id, BnplAgreement agreement,
                BnplAcceptanceEvidence evidence, CorrelationId correlation) {
            step("acceptance insert");
            if (!BnplAcceptanceEvidence.MULTI_FACTOR.equals(evidence.assuranceLevel())) {
                throw new BnplStorageException("bnpl_acceptance_multi_factor: SQLSTATE 23514");
            }
            acceptances.put(agreement.id().value(), evidence);
            return now;
        }

        @Override
        public void insertSchedule(Connection unitOfWork, StoredSchedule schedule) {
            step("schedule insert");
            Money scheduled = Money.ofPersisted(0, schedule.principal().currency(), schedule.principal().scale());
            for (StoredInstalment instalment : schedule.instalments()) {
                scheduled = scheduled.plus(instalment.principal());
            }
            if (!scheduled.equals(schedule.principal())) {
                throw new BnplStorageException("a BNPL schedule conserves (INV-BNPL-05): P0001");
            }
            schedules.put(schedule.obligation().value(), schedule);
            versions.put(schedule.obligation().value() + ":" + schedule.version(), schedule);
        }

        /** Whether the obligation has a billing dated {@code dueDate} - one per due date (bnpl V008, V012's rank). */
        boolean billedOn(BnplObligationId obligation, java.time.LocalDate dueDate) {
            return billings.values().stream()
                    .anyMatch(row -> row.obligation().equals(obligation) && row.dueDate().equals(dueDate));
        }

        @Override
        public void insertBilling(Connection unitOfWork, BnplBilling billing) {
            step("billing insert");
            // bnpl V006/V008: UNIQUE (instalment_id), UNIQUE (obligation_id, value_date).
            if (billings.containsKey(billing.instalment())) {
                throw new BnplStorageException("bnpl_billing_instalment_unique: SQLSTATE 23505");
            }
            if (billings.values().stream().anyMatch(row -> row.obligation().equals(billing.obligation())
                    && row.dueDate().equals(billing.dueDate()))) {
                throw new BnplStorageException("bnpl_billing_due_date_unique: SQLSTATE 23505");
            }
            BnplObligation obligation = obligations.get(billing.obligation().value());
            StoredSchedule schedule = schedules.get(billing.obligation().value());
            StoredInstalment instalment = schedule.instalments().stream()
                    .filter(candidate -> candidate.id().equals(billing.instalment())).findFirst().orElseThrow();
            java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
            // bnpl V008's trigger: dated its due date, once it has come - early only beside the obligation's payoff
            // execution (bnpl V014); oldest first; the period it closes.
            Optional<BnplPayoffExecution> payoff = payoffs.executionOf(billing.obligation());
            if (!billing.dueDate().equals(instalment.dueDate())
                    || (instalment.dueDate().isAfter(today) && payoff.isEmpty())
                    || billing.dueDate().isBefore(obligation.purchaseDate())) {
                throw new BnplStorageException("a BNPL billing is dated its instalment's due date, once that date has"
                        + " come: P0001");
            }
            if (billings.values().stream().anyMatch(row -> row.obligation().equals(billing.obligation())
                    && !row.dueDate().isBefore(billing.dueDate()))
                    || schedule.instalments().stream().anyMatch(earlier -> earlier.sequence() < instalment.sequence()
                            && !billedOn(billing.obligation(), earlier.dueDate()))) {
                throw new BnplStorageException("an obligation's instalments are billed oldest first: P0001");
            }
            if (!billing.periodStart().equals(BnplServicing.periodStart(schedule, instalment))) {
                throw new BnplStorageException("a BNPL billing closes its instalment's own period: P0001");
            }
            // The interest: pay-in-4 none; pay-in-N exactly the period's posted accruals, every date of it accrued.
            Money zero = Money.ofPersisted(0, instalment.amount().currency(), instalment.amount().scale());
            Money accrued = zero;
            if (schedule.engine().equals("SCHEDULE_ENGINE_V1")) {
                accrued = servicing.periodInterest(UOW, billing.obligation(), billing.periodStart(), billing.dueDate(),
                        zero);
                // The reach: the due date, or for a payoff's billing the earlier of it and the payoff's date (V014).
                java.time.LocalDate reach = payoff.map(BnplPayoffExecution::businessDate)
                        .filter(date -> date.isBefore(billing.dueDate())).orElse(billing.dueDate());
                if (reach.isAfter(billing.periodStart()) && servicing.latestAccrualDate(UOW, billing.obligation())
                        .filter(latest -> !latest.isBefore(reach.minusDays(1))).isEmpty()) {
                    throw new BnplStorageException("a pay-in-N billing waits for every accrual of its period: P0001");
                }
            }
            if (!billing.interest().equals(accrued)) {
                throw new BnplStorageException("a BNPL billing's interest is exactly its period's accrued interest: P0001");
            }
            // The principal: least(remaining, max(0, amount - interest)); the last instalment the remainder.
            Money remaining = schedule.principal();
            for (BnplBilling row : billings.values()) {
                if (row.obligation().equals(billing.obligation())) {
                    remaining = remaining.minus(row.principal());
                }
            }
            Money expected;
            if (instalment.sequence() == schedule.instalments().size()) {
                expected = remaining;
            } else {
                Money portion = instalment.amount().minus(billing.interest());
                portion = portion.isNegative() ? zero : portion;
                expected = portion.compareTo(remaining) <= 0 ? portion : remaining;
            }
            if (remaining.isNegative() || !billing.principal().equals(expected)) {
                throw new BnplStorageException("a BNPL billing bills least(remaining, amount - interest) of principal:"
                        + " P0001");
            }
            billings.put(billing.instalment(), billing);
        }

        @Override
        public void insertRepayment(Connection unitOfWork, Repayment repayment) {
            step("repayment insert");
            if (repayments.values().stream().anyMatch(row -> row.obligation().equals(repayment.obligation())
                    && row.kind().equals(repayment.kind()) && row.requestKey().equals(repayment.requestKey()))) {
                throw new BnplStorageException("bnpl_repayment_request_unique: SQLSTATE 23505");
            }
            // bnpl V010's trigger: an ACTIVE obligation; never before the floor nor after the later of today and it
            BnplObligation obligation = obligations.get(repayment.obligation().value());
            if (obligation == null || obligation.status() != BnplObligationStatus.ACTIVE) {
                throw new BnplStorageException("a BNPL repayment repays its ACTIVE obligation: P0001");
            }
            // bnpl V014: PAYOFF only beside its obligation's execution, keyed by its quote, dated its value date.
            if (repayment.kind().equals(Repayment.PAYOFF) && payoffs.executionOf(repayment.obligation())
                    .filter(execution -> execution.quote().toString().equals(repayment.requestKey())
                            && execution.valueDate().equals(repayment.valueDate())).isEmpty()) {
                throw new BnplStorageException("a PAYOFF repayment is born only beside its obligation's payoff"
                        + " execution: P0001");
            }
            if (!repayment.kind().equals(Repayment.AT_PURCHASE)) {
                java.time.LocalDate floor = servicing.principalValueFloor(unitOfWork, repayment.obligation());
                java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
                java.time.LocalDate ceiling = today.isAfter(floor) ? today : floor;
                if (repayment.valueDate().isBefore(floor) || repayment.valueDate().isAfter(ceiling)) {
                    throw new BnplStorageException("a BNPL repayment is value-dated on or after its obligation's floor"
                            + " and never after today: P0001");
                }
            }
            for (Allocation line : repayment.allocations()) {
                if (line.target().equals("NOT_DUE_PRINCIPAL")) {
                    throw new BnplStorageException("bnpl_repayment_allocation_target: SQLSTATE 23514");
                }
                if (line.target().equals("DUE_ITEM") && !billings.containsKey(line.instalment().orElseThrow())) {
                    throw new BnplStorageException("a BNPL repayment pays only a billed instalment: P0001");
                }
            }
            repayments.put(repayment.id(), repayment);
            // At commit: no billed component paid beyond what it billed (every payer's).
            for (Allocation line : repayment.allocations()) {
                if (!line.target().equals("DUE_ITEM")) {
                    continue;
                }
                BnplBilling billing = billings.get(line.instalment().orElseThrow());
                Money zero = Money.ofPersisted(0, billing.principal().currency(), billing.principal().scale());
                Money billed = billed(billing, line.component().orElseThrow(), zero);
                if (paid(billing, line.component().orElseThrow(), zero).compareTo(billed) > 0) {
                    repayments.remove(repayment.id());
                    throw new BnplStorageException("no billed component is ever paid beyond what it billed: P0001");
                }
            }
        }

        @Override
        public List<Owned> ownedBy(Connection unitOfWork, UUID party, int limit) {
            return obligations.values().stream().filter(row -> row.party().equals(party))
                    .sorted(java.util.Comparator.comparing(BnplObligation::openedAt).reversed()).limit(limit)
                    .map(this::owned).toList();
        }

        @Override
        public Optional<Owned> agreementOwnedBy(Connection unitOfWork, BnplAgreementId id, UUID party) {
            return obligations.values().stream()
                    .filter(row -> row.agreement().equals(id) && row.party().equals(party)).findFirst().map(this::owned);
        }

        @Override
        public Optional<StoredSchedule> schedule(Connection unitOfWork, BnplObligationId obligation, int version) {
            return Optional.ofNullable(versions.get(obligation.value() + ":" + version));
        }

        @Override
        public Optional<BnplPurchaseStatus> purchaseStatus(Connection unitOfWork, BnplPurchaseId purchase) {
            return Optional.ofNullable(rows.get(purchase.value())).map(BnplPurchase::status);
        }

        @Override
        public List<BilledItem> billedItems(Connection unitOfWork, BnplObligationId obligation) {
            List<BilledItem> items = new ArrayList<>();
            for (BnplBilling billing : billings.values().stream().filter(row -> row.obligation().equals(obligation))
                    .sorted(java.util.Comparator.comparing(BnplBilling::dueDate)).toList()) {
                Money zero = Money.ofPersisted(0, billing.principal().currency(), billing.principal().scale());
                items.add(new BilledItem(billing.id(), billing.instalment(), billing.dueDate(), billedFees(billing, zero),
                        billing.interest(), billing.principal(), paid(billing, "FEES", zero), paid(billing, "INTEREST", zero),
                        paid(billing, "PRINCIPAL", zero)));
            }
            return items;
        }

        /**
         * {@code bnpl V016}'s {@code billing_component_billed(.., 'FEES')}: the instalment's assessed late fees (V013) less
         * their approved waivers (`P12-TSK-024`; {@link Corrections#waiverTermDropped} drops the second term - a probe only).
         */
        Money billedFees(BnplBilling billing, Money zero) {
            Money fees = zero;
            for (BnplFeeAssessment assessment : conditions.assessments) {
                if (assessment.instalment().equals(billing.instalment())) {
                    fees = fees.plus(assessment.amount());
                }
            }
            if (!corrections.waiverTermDropped) {
                for (BnplWaiver waiver : corrections.waivers()) {
                    if (waiver.status() == BnplCorrectionStatus.APPROVED
                            && waiver.instalment().equals(billing.instalment())) {
                        fees = fees.minus(waiver.amount());
                    }
                }
            }
            return fees;
        }

        /** What {@code billing} billed of {@code component} - {@code bnpl.billing_component_billed}. */
        Money billed(BnplBilling billing, String component, Money zero) {
            return switch (component) {
                case "INTEREST" -> billing.interest();
                case "PRINCIPAL" -> billing.principal();
                default -> billedFees(billing, zero);
            };
        }

        /** {@code bnpl.billing_component_paid}: every application line on the billing, every repayment line on its instalment. */
        Money paid(BnplBilling billing, String component, Money zero) {
            Money paid = zero;
            for (BnplCreditBalanceApplication application : servicing.applications.values()) {
                for (BnplCreditBalanceApplication.Line line : application.lines()) {
                    if (line.billing().equals(billing.id()) && line.component().name().equals(component)) {
                        paid = paid.plus(line.amount());
                    }
                }
            }
            for (Repayment repayment : repayments.values()) {
                for (Allocation line : repayment.allocations()) {
                    if (line.target().equals("DUE_ITEM") && line.instalment().filter(billing.instalment()::equals)
                            .isPresent() && line.component().filter(component::equals).isPresent()) {
                        paid = paid.plus(line.amount());
                        // bnpl V016: less what an approved reversal's negating row un-paid (`P12-TSK-024`).
                        if (corrections.negated(line.id())) {
                            paid = paid.minus(line.amount());
                        }
                    }
                }
            }
            // bnpl V012: every refund's cancellation of the billed principal and release of the billed interest.
            for (BnplRefund refund : refunds.rows.values()) {
                for (BnplRefund.Adjustment adjustment : refund.adjustments()) {
                    if (adjustment.kind() == RefundAllocation.Cancellation.Kind.BILLED_UNPAID
                            && adjustment.instalment().equals(billing.instalment())) {
                        paid = paid.plus(switch (component) {
                            case "PRINCIPAL" -> adjustment.principalCancelled();
                            case "INTEREST" -> adjustment.interestReleased();
                            // bnpl V013: a refund's release of the late fees on an instalment it cancelled whole (OQ-14).
                            default -> adjustment.lateFeeReleased();
                        });
                    }
                }
            }
            return paid;
        }

        /** {@code JdbcBnplPurchaseStore.atMerchant}'s statement: the purchase at the merchant, joined to its financing. */
        @Override
        public Optional<MerchantBnplPurchaseView> atMerchant(Connection unitOfWork, BnplPurchaseId id, UUID merchant) {
            BnplPurchase purchase = rows.get(id.value());
            MerchantFinancing financing = financings.get(id.value());
            if (purchase == null || financing == null || !purchase.merchant().equals(merchant)
                    || !financing.merchant().equals(purchase.merchant())) {
                return Optional.empty();
            }
            MerchantFeeSplit split = financing.split();
            return Optional.of(new MerchantBnplPurchaseView(purchase.id(), purchase.checkoutSession(),
                    purchase.order().orElseThrow(), purchase.product(), purchase.status(), split.gross(), split.fee(),
                    split.net(), split.feeScheduleVersionId(), financing.entry(), purchase.financedAt().orElseThrow()));
        }

        private Owned owned(BnplObligation obligation) {
            return new Owned(obligation, rows.get(obligation.purchase().value()),
                    agreements.get(obligation.agreement().value()),
                    financings.get(obligation.purchase().value()).split().gross());
        }
    }

    /** The ledger as bnpl commands it, in memory: accounts, row locks, balanced once-only postings, derived balances. */
    final class Book implements BnplBook {

        /** One posted line. */
        record Line(String key, com.finapp.ledger.LedgerAccountId account, com.finapp.ledger.Direction direction,
                Money amount, java.time.LocalDate valueDate, java.time.LocalDate postingDate) {}

        final Map<com.finapp.ledger.LedgerAccountId, com.finapp.ledger.LedgerAccount> accounts = new LinkedHashMap<>();
        final Map<com.finapp.ledger.LedgerAccountId, String> labels = new LinkedHashMap<>();
        final Map<com.finapp.ledger.LedgerAccountId, com.finapp.ledger.LedgerAccountStatus> statuses =
                new LinkedHashMap<>();
        /** A wallet's funds before any line here. */
        final Map<com.finapp.ledger.LedgerAccountId, Money> funds = new LinkedHashMap<>();
        final Map<com.finapp.ledger.AccountPurpose, com.finapp.ledger.LedgerAccountId> operational =
                new EnumMap<>(com.finapp.ledger.AccountPurpose.class);
        final List<Line> lines = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        /** Every entry's lines by its id - the journal ({@code JournalEntryStore.findById}, `P12-TSK-024`). */
        final Map<com.finapp.ledger.JournalEntryId, List<com.finapp.ledger.JournalLine>> entries = new LinkedHashMap<>();
        /** Every reversal's original, by the reversal's id - the ledger's {@code reverses_entry_id}. */
        final Map<com.finapp.ledger.JournalEntryId, com.finapp.ledger.JournalEntryId> reversalOf = new LinkedHashMap<>();
        /** The projection rows of every L8 call, in order. */
        final List<java.util.Set<com.finapp.ledger.LedgerAccountId>> projections = new ArrayList<>();

        /** Everything, for the unit of work's rollback. */
        record State(Map<com.finapp.ledger.LedgerAccountId, com.finapp.ledger.LedgerAccount> accounts, List<Line> lines,
                List<String> keys, Map<com.finapp.ledger.JournalEntryId, List<com.finapp.ledger.JournalLine>> entries,
                Map<com.finapp.ledger.JournalEntryId, com.finapp.ledger.JournalEntryId> reversalOf) {}

        State state() {
            return new State(new LinkedHashMap<>(accounts), new ArrayList<>(lines), new ArrayList<>(keys),
                    new LinkedHashMap<>(entries), new LinkedHashMap<>(reversalOf));
        }

        void restore(State state) {
            reset(accounts, state.accounts());
            lines.clear();
            lines.addAll(state.lines());
            keys.clear();
            keys.addAll(state.keys());
            reset(entries, state.entries());
            reset(reversalOf, state.reversalOf());
        }

        /** An account outside the obligation - a payable or a wallet - known by {@code label} in the log. */
        com.finapp.ledger.LedgerAccountId external(String label, UUID id) {
            com.finapp.ledger.LedgerAccountId account = com.finapp.ledger.LedgerAccountId.of(id);
            labels.put(account, label);
            statuses.put(account, com.finapp.ledger.LedgerAccountStatus.ACTIVE);
            return account;
        }

        @Override
        public BnplLedgerAccounts openAccounts(Connection unitOfWork, BnplObligationId obligation,
                com.finapp.sharedkernel.money.CurrencyCode currency) {
            step("accounts open");
            List<com.finapp.ledger.LedgerAccount> opened = new ArrayList<>();
            for (com.finapp.ledger.AccountPurpose purpose : BnplAccounts.purposes()) {
                com.finapp.ledger.LedgerAccount account = com.finapp.ledger.LedgerAccount.owned(IDS,
                        java.time.Clock.systemUTC(), BnplAccounts.declaredTypeOf(purpose), purpose, currency,
                        obligation.value());
                accounts.put(account.id(), account);
                labels.put(account.id(), purpose.name());
                opened.add(account);
            }
            return new BnplLedgerAccounts(opened.get(0), opened.get(1), opened.get(2), opened.get(3), opened.get(4),
                    opened.get(5));
        }

        @Override
        public List<com.finapp.ledger.LedgerAccount> accountsOf(Connection unitOfWork, BnplObligationId obligation) {
            return accounts.values().stream()
                    .filter(account -> account.ownerRef().filter(obligation.value()::equals).isPresent()).toList();
        }

        @Override
        public com.finapp.ledger.LedgerAccountId operational(Connection unitOfWork, com.finapp.ledger.AccountPurpose purpose,
                com.finapp.sharedkernel.money.CurrencyCode currency) {
            return operational.computeIfAbsent(purpose, any -> {
                com.finapp.ledger.LedgerAccountId id = com.finapp.ledger.LedgerAccountId.of(IDS.next());
                labels.put(id, purpose.name());
                return id;
            });
        }

        @Override
        public Optional<com.finapp.ledger.LedgerAccountStatus> lockForUpdate(Connection unitOfWork,
                com.finapp.ledger.LedgerAccountId account) {
            step("lock " + labels.getOrDefault(account, "?"));
            return Optional.ofNullable(statuses.get(account));
        }

        @Override
        public Money availableUnderLock(Connection unitOfWork, com.finapp.ledger.LedgerAccountId account) {
            log.add("available " + labels.getOrDefault(account, "?"));
            Money available = funds.get(account);
            for (Line line : lines) {
                if (line.account().equals(account)) {
                    available = line.direction() == com.finapp.ledger.Direction.CREDIT ? available.plus(line.amount())
                            : available.minus(line.amount());
                }
            }
            return available;
        }

        @Override
        public void lockBalancesInOrder(Connection unitOfWork, java.util.Collection<com.finapp.ledger.LedgerAccountId> rows) {
            step("L8");
            projections.add(java.util.Set.copyOf(rows));
        }

        @Override
        public com.finapp.ledger.JournalEntryId post(Connection unitOfWork, com.finapp.ledger.PostingCommand command) {
            String key = command.idempotencyKey();
            step("post " + key.substring(0, key.indexOf(':') + 1));
            if (keys.contains(key)) {
                throw new IllegalStateException("a BNPL entry exists for an id minted in this transaction (INV-IDEM-01)");
            }
            Money debits = null;
            Money credits = null;
            for (com.finapp.ledger.JournalLine line : command.lines()) {
                if (line.direction() == com.finapp.ledger.Direction.DEBIT) {
                    debits = debits == null ? line.amount() : debits.plus(line.amount());
                } else {
                    credits = credits == null ? line.amount() : credits.plus(line.amount());
                }
            }
            if (debits == null || !debits.equals(credits)) {
                throw new IllegalStateException("an entry balances (INV-LED-01)");
            }
            keys.add(key);
            for (com.finapp.ledger.JournalLine line : command.lines()) {
                lines.add(new Line(key, line.account(), line.direction(), line.amount(), command.valueDate(),
                        command.postingDate()));
            }
            com.finapp.ledger.JournalEntryId id = com.finapp.ledger.JournalEntryId.of(IDS.next());
            entries.put(id, List.copyOf(command.lines()));
            return id;
        }

        /**
         * The ledger's {@code ReversalService}, in memory (`P12-TSK-024`): balanced, once per key in the
         * {@code ledger.reverse} scope, never of a reversal, and bounded - every line mirrors one of the original's on the
         * same account in the opposite direction, and with every earlier reversal of it never more than the original
         * ({@code INV-REV-02}; the ledger's {@code OverReversalException}).
         */
        @Override
        public com.finapp.ledger.JournalEntryId reverse(Connection unitOfWork, com.finapp.ledger.ReversalCommand command) {
            String key = "ledger.reverse:" + command.idempotencyKey();
            step("reverse " + command.idempotencyKey().substring(0, command.idempotencyKey().indexOf(':') + 1));
            if (keys.contains(key)) {
                throw new IllegalStateException("a BNPL reversal exists for an id decided in this transaction (INV-IDEM-01)");
            }
            List<com.finapp.ledger.JournalLine> original = entries.get(command.original());
            if (original == null || reversalOf.containsKey(command.original())) {
                throw new IllegalArgumentException("no reversible journal entry " + command.original());
            }
            Money debits = null;
            Money credits = null;
            for (com.finapp.ledger.JournalLine line : command.lines()) {
                if (line.direction() == com.finapp.ledger.Direction.DEBIT) {
                    debits = debits == null ? line.amount() : debits.plus(line.amount());
                } else {
                    credits = credits == null ? line.amount() : credits.plus(line.amount());
                }
            }
            if (debits == null || !debits.equals(credits)) {
                throw new IllegalStateException("an entry balances (INV-LED-01)");
            }
            Map<String, Money> room = new LinkedHashMap<>();
            for (com.finapp.ledger.JournalLine line : original) {
                room.merge(line.account().value() + ":" + line.direction().opposite(), line.amount(), Money::plus);
            }
            for (Map.Entry<com.finapp.ledger.JournalEntryId, com.finapp.ledger.JournalEntryId> earlier
                    : reversalOf.entrySet()) {
                if (earlier.getValue().equals(command.original())) {
                    for (com.finapp.ledger.JournalLine line : entries.get(earlier.getKey())) {
                        room.merge(line.account().value() + ":" + line.direction(), line.amount(), Money::minus);
                    }
                }
            }
            Map<String, Money> asked = new LinkedHashMap<>();
            for (com.finapp.ledger.JournalLine line : command.lines()) {
                asked.merge(line.account().value() + ":" + line.direction(), line.amount(), Money::plus);
            }
            for (Map.Entry<String, Money> line : asked.entrySet()) {
                Money left = room.get(line.getKey());
                if (left == null || line.getValue().compareTo(left) > 0) {
                    throw new IllegalStateException("over-reversal of " + command.original() + " (INV-REV-02)");
                }
            }
            keys.add(key);
            for (com.finapp.ledger.JournalLine line : command.lines()) {
                lines.add(new Line(key, line.account(), line.direction(), line.amount(), command.valueDate(),
                        command.postingDate()));
            }
            com.finapp.ledger.JournalEntryId id = com.finapp.ledger.JournalEntryId.of(IDS.next());
            entries.put(id, List.copyOf(command.lines()));
            reversalOf.put(id, command.original());
            return id;
        }

        @Override
        public List<com.finapp.ledger.JournalLine> entryLines(Connection unitOfWork,
                com.finapp.ledger.JournalEntryId entry) {
            return entries.getOrDefault(entry, List.of());
        }

        @Override
        public Money balanceOf(Connection unitOfWork, com.finapp.ledger.LedgerAccount account) {
            return balance(account.id(), account.normalBalance() == com.finapp.ledger.NormalBalance.DEBIT);
        }

        /** {@code JdbcPurposeBalances.datedLinesOn}: the accounts' lines valued on or before the date, by value date. */
        @Override
        public List<com.finapp.ledger.PurposeBalances.DatedLine> datedLinesOn(Connection unitOfWork,
                java.util.Set<com.finapp.ledger.LedgerAccountId> rows, java.time.LocalDate valueDateThrough) {
            log.add("dated lines");
            return lines.stream()
                    .filter(line -> rows.contains(line.account()) && !line.valueDate().isAfter(valueDateThrough))
                    .sorted(java.util.Comparator.comparing(Line::valueDate))
                    .map(line -> new com.finapp.ledger.PurposeBalances.DatedLine(
                            com.finapp.ledger.JournalEntryId.of(IDS.next()),
                            line.account(), line.valueDate(), line.direction(), line.amount()))
                    .toList();
        }

        /** DR - CR of {@code account}'s lines (debit-normal), or CR - DR; zero in EUR when it has none. */
        Money balance(com.finapp.ledger.LedgerAccountId account, boolean debitNormal) {
            Money balance = Money.ofMinorUnits(0, BnplTermsFixtures.EUR);
            for (Line line : lines) {
                if (line.account().equals(account)) {
                    boolean adds = (line.direction() == com.finapp.ledger.Direction.DEBIT) == debitNormal;
                    balance = adds ? balance.plus(line.amount()) : balance.minus(line.amount());
                }
            }
            return balance;
        }

        /** The lines posted under keys starting {@code prefix}. */
        List<Line> linesOf(String prefix) {
            return lines.stream().filter(line -> line.key().startsWith(prefix)).toList();
        }

        /** Σ DR - CR of every BNPL principal account - what the capital headroom and the exposure count. */
        Money bnplPrincipal() {
            Money principal = Money.ofMinorUnits(0, BnplTermsFixtures.EUR);
            for (com.finapp.ledger.LedgerAccount account : accounts.values()) {
                if (BnplAccounts.principalPurposes().contains(account.purpose())) {
                    principal = principal.plus(balance(account.id(), true));
                }
            }
            return principal;
        }
    }

    /**
     * {@code bnpl V008}'s servicing world, in memory (`P12-TSK-015`): the permit and its one-statement claim on the database
     * clock, B4 (logged), the principal value floor, the accrual record (the owner-role accrual fixture's
     * {@link #accrue}), the latest schedule, and the credit-balance application's arbiters - {@code UNIQUE (billing_id)},
     * the next catch-up number, the floor's two ranks, the rows summing to the application and no billed component paid
     * beyond what it billed.
     */
    final class Servicing implements BnplServicingStore {

        /** One accrued date - the record P12-TSK-017's T-acc writes; here the owner-role fixture's. */
        record Accrual(BnplObligationId obligation, java.time.LocalDate date, Money posted) {}

        final Map<UUID, Instant> permits = new LinkedHashMap<>();
        final List<Accrual> accruals = new ArrayList<>();
        final Map<UUID, BnplCreditBalanceApplication> applications = new LinkedHashMap<>();

        /** Everything, for the unit of work's rollback. */
        record State(Map<UUID, Instant> permits, List<Accrual> accruals,
                Map<UUID, BnplCreditBalanceApplication> applications) {}

        State state() {
            return new State(new LinkedHashMap<>(permits), new ArrayList<>(accruals), new LinkedHashMap<>(applications));
        }

        void restore(State state) {
            reset(permits, state.permits());
            accruals.clear();
            accruals.addAll(state.accruals());
            reset(applications, state.applications());
        }

        /** The owner-role accrual fixture: a date's posted interest on the record (and its F2a lines on the book). */
        void accrue(BnplObligationId obligation, java.time.LocalDate date, Money posted) {
            accruals.add(new Accrual(obligation, date, posted));
            if (posted.isPositive()) {
                com.finapp.ledger.LedgerAccount accrued = book.accountsOf(UOW, obligation).stream()
                        .filter(account -> account.purpose() == com.finapp.ledger.AccountPurpose.BNPL_INTEREST_ACCRUED)
                        .findFirst().orElseThrow();
                com.finapp.ledger.LedgerAccountId income = book.operational(UOW,
                        com.finapp.ledger.AccountPurpose.BNPL_INTEREST_INCOME, BnplTermsFixtures.EUR);
                String key = "bnpl.accrual:" + obligation.value() + ":" + date;
                book.keys.add(key);
                book.lines.add(new Book.Line(key, accrued.id(), com.finapp.ledger.Direction.DEBIT, posted, date, date));
                book.lines.add(new Book.Line(key, income, com.finapp.ledger.Direction.CREDIT, posted, date, date));
            }
        }

        private java.time.LocalDate today() {
            return java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
        }

        Instant permitOf(BnplObligation obligation) {
            return permits.getOrDefault(obligation.id().value(), obligation.openedAt());
        }

        @Override
        public List<BnplObligationId> claimDue(Connection unitOfWork, int limit, java.time.Duration retryCadence) {
            log.add("claim");
            List<BnplObligation> due = purchases.obligations.values().stream()
                    .filter(row -> row.status() == BnplObligationStatus.ACTIVE && !permitOf(row).isAfter(now))
                    .sorted(java.util.Comparator.comparing(this::permitOf)
                            .thenComparing(row -> row.id().value()))
                    .limit(limit).toList();
            for (BnplObligation row : due) {
                permits.put(row.id().value(), now.plus(retryCadence));
            }
            return due.stream().map(BnplObligation::id).toList();
        }

        @Override
        public Optional<Locked> lock(Connection unitOfWork, BnplObligationId obligation) {
            step("B4");
            BnplObligation row = purchases.obligations.get(obligation.value());
            if (row == null) {
                return Optional.empty();
            }
            BnplAgreement agreement = purchases.agreements.get(row.agreement().value());
            return Optional.of(new Locked(row, agreement.id(), agreement.version(), agreement.termsVersionId(),
                    agreement.scheduleEngine(), agreement.accrualEngine(), agreement.allocationEngine(), "UTC"));
        }

        @Override
        public Instant databaseNow(Connection unitOfWork) {
            return now;
        }

        @Override
        public java.time.LocalDate principalValueFloor(Connection unitOfWork, BnplObligationId obligation) {
            java.time.LocalDate floor = purchases.obligations.get(obligation.value()).purchaseDate();
            // bnpl V014: a billing beside a payoff execution counts at most at the execution's value date.
            Optional<java.time.LocalDate> payoffDate = payoffs.executionOf(obligation).map(BnplPayoffExecution::valueDate);
            for (BnplBilling billing : purchases.billings.values()) {
                if (!billing.obligation().equals(obligation)) {
                    continue;
                }
                java.time.LocalDate counted = payoffDate.filter(date -> date.isBefore(billing.dueDate()))
                        .orElse(billing.dueDate());
                if (counted.isAfter(floor)) {
                    floor = counted;
                }
            }
            Optional<java.time.LocalDate> accrued = latestAccrualDate(unitOfWork, obligation);
            if (accrued.isPresent() && accrued.get().plusDays(1).isAfter(floor)) {
                floor = accrued.get().plusDays(1);
            }
            return floor;
        }

        @Override
        public Optional<java.time.LocalDate> latestAccrualDate(Connection unitOfWork, BnplObligationId obligation) {
            return accruals.stream().filter(row -> row.obligation().equals(obligation)).map(Accrual::date)
                    .max(java.util.Comparator.naturalOrder());
        }

        @Override
        public Money periodInterest(Connection unitOfWork, BnplObligationId obligation, java.time.LocalDate from,
                java.time.LocalDate toExclusive, Money zero) {
            Money interest = zero;
            for (Accrual row : accruals) {
                if (row.obligation().equals(obligation) && !row.date().isBefore(from) && row.date().isBefore(toExclusive)) {
                    interest = interest.plus(row.posted());
                }
            }
            return interest;
        }

        @Override
        public Optional<BnplPurchaseStore.StoredSchedule> latestSchedule(Connection unitOfWork,
                BnplObligationId obligation) {
            return Optional.ofNullable(purchases.schedules.get(obligation.value()));
        }

        @Override
        public int latestCatchUpSequence(Connection unitOfWork, BnplObligationId obligation) {
            return applications.values().stream().filter(row -> row.obligation().equals(obligation))
                    .flatMap(row -> row.catchUp().stream()).max(Integer::compare).orElse(0);
        }

        @Override
        public void insertApplication(Connection unitOfWork, BnplCreditBalanceApplication application) {
            step("application insert");
            BnplObligationId obligation = application.obligation();
            if (application.billing().isPresent() && applications.values().stream()
                    .anyMatch(row -> row.billing().equals(application.billing()))) {
                throw new BnplStorageException("bnpl_credit_balance_application_billing_unique: SQLSTATE 23505");
            }
            if (application.catchUp().isPresent()
                    && application.catchUp().get() != latestCatchUpSequence(unitOfWork, obligation) + 1) {
                throw new BnplStorageException("a catch-up credit-balance application is its obligation's next: P0001");
            }
            // The Phase 11 gate's R1: never before the floor, never after the later of today and the floor.
            java.time.LocalDate floor = principalValueFloor(unitOfWork, obligation);
            java.time.LocalDate ceiling = today().isAfter(floor) ? today() : floor;
            if (application.valueDate().isBefore(floor) || application.valueDate().isAfter(ceiling)) {
                throw new BnplStorageException("a credit-balance application is value-dated on or after its obligation's"
                        + " floor and never after today: P0001");
            }
            applications.put(application.id(), application);
            // At commit: no billed component paid beyond what it billed (every payer's).
            for (BnplCreditBalanceApplication.Line line : application.lines()) {
                BnplBilling billing = purchases.billings.values().stream()
                        .filter(row -> row.id().equals(line.billing())).findFirst().orElseThrow(() ->
                                new BnplStorageException("a credit-balance allocation pays a billing of its own"
                                        + " obligation: P0001"));
                Money zero = Money.ofPersisted(0, billing.principal().currency(), billing.principal().scale());
                Money billed = purchases.billed(billing, line.component().name(), zero);
                if (purchases.paid(billing, line.component().name(), zero).compareTo(billed) > 0) {
                    applications.remove(application.id());
                    throw new BnplStorageException("no billed component is ever paid beyond what it billed: P0001");
                }
            }
        }

        @Override
        public boolean restamp(Connection unitOfWork, BnplObligationId obligation, java.time.LocalDate openDate) {
            BnplObligation row = purchases.obligations.get(obligation.value());
            if (row == null || row.status() != BnplObligationStatus.ACTIVE) {
                return false;
            }
            permits.put(obligation.value(), openDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            return true;
        }
    }

    /**
     * {@code bnpl V010}'s repayment and closure world, in memory (`P12-TSK-018`): the owner-scoped B4 (logged), the key's
     * arbiter, the history, and the closure - {@code UNIQUE (obligation_id)}, its trigger's ranks (an {@code ACTIVE}
     * obligation closing {@code REPAID}, every instalment of the schedule billed, every billed component paid in full) and
     * the conditional edge.
     */
    final class Repayments implements BnplRepaymentStore {

        final Map<UUID, BnplClosure> closures = new LinkedHashMap<>();
        /** The owner-scoped locks taken, in order - for the lock-order assertions. */
        final List<UUID> lockedAgreements = new ArrayList<>();

        Map<UUID, BnplClosure> state() {
            return new LinkedHashMap<>(closures);
        }

        void restore(Map<UUID, BnplClosure> state) {
            reset(closures, state);
        }

        @Override
        public Optional<BnplServicingStore.Locked> lockOwnedBy(Connection unitOfWork, BnplAgreementId agreement,
                UUID party) {
            step("B4");
            Optional<BnplObligation> row = purchases.obligations.values().stream()
                    .filter(candidate -> candidate.agreement().equals(agreement) && candidate.party().equals(party))
                    .findFirst();
            if (row.isEmpty()) {
                return Optional.empty();
            }
            lockedAgreements.add(agreement.value());
            BnplAgreement signed = purchases.agreements.get(agreement.value());
            return Optional.of(new BnplServicingStore.Locked(row.get(), signed.id(), signed.version(),
                    signed.termsVersionId(), signed.scheduleEngine(), signed.accrualEngine(), signed.allocationEngine(),
                    "UTC"));
        }

        @Override
        public Optional<BnplPurchaseStore.Repayment> findByKey(Connection unitOfWork, BnplObligationId obligation,
                String kind, String requestKey) {
            return purchases.repayments.values().stream().filter(row -> row.obligation().equals(obligation)
                    && row.kind().equals(kind) && row.requestKey().equals(requestKey)).findFirst();
        }

        @Override
        public List<BnplPurchaseStore.Repayment> ofObligation(Connection unitOfWork, BnplObligationId obligation,
                int limit) {
            List<BnplPurchaseStore.Repayment> rows = new ArrayList<>(purchases.repayments.values().stream()
                    .filter(row -> row.obligation().equals(obligation)).toList());
            java.util.Collections.reverse(rows);
            return rows.stream().limit(limit).toList();
        }

        @Override
        public Optional<BnplClosure> closureOf(Connection unitOfWork, BnplObligationId obligation) {
            return Optional.ofNullable(closures.get(obligation.value()));
        }

        @Override
        public void insertClosure(Connection unitOfWork, BnplClosure closure) {
            step("closure insert");
            if (closures.containsKey(closure.obligation().value())) {
                throw new BnplStorageException("bnpl_obligation_closure_obligation_unique: SQLSTATE 23505");
            }
            BnplObligation obligation = purchases.obligations.get(closure.obligation().value());
            BnplPurchaseStatus purchase = obligation == null ? null
                    : purchases.rows.get(obligation.purchase().value()).status();
            // bnpl V012: REPAID, REFUNDED (the purchase fully refunded) or REFUNDED_PARTIALLY_REPAID (beside a refund);
            // bnpl V014: PAID_OFF_EARLY beside the obligation's payoff execution, and nothing else beside one.
            boolean paidOff = payoffs.executionOf(closure.obligation()).isPresent();
            boolean admitted = switch (closure.reason()) {
                case REPAID -> !paidOff && purchase != BnplPurchaseStatus.FULLY_REFUNDED;
                case REFUNDED -> !paidOff && purchase == BnplPurchaseStatus.FULLY_REFUNDED;
                case REFUNDED_PARTIALLY_REPAID -> !paidOff && purchase == BnplPurchaseStatus.FINANCED
                        && closure.closingRefund().isPresent();
                case PAID_OFF_EARLY -> paidOff && closure.closingRefund().isEmpty() && closure.repayment()
                        .map(id -> purchases.repayments.get(id)).filter(row -> row == null
                                || !row.kind().equals(BnplPurchaseStore.Repayment.PAYOFF)).isEmpty();
            };
            if (obligation == null || obligation.status() != BnplObligationStatus.ACTIVE || !admitted) {
                throw new BnplStorageException("an ACTIVE BNPL obligation closes REPAID, REFUNDED or"
                        + " REFUNDED_PARTIALLY_REPAID here: P0001");
            }
            BnplPurchaseStore.StoredSchedule schedule = purchases.schedules.get(closure.obligation().value());
            if (schedule.instalments().stream()
                    .anyMatch(instalment -> !purchases.billedOn(closure.obligation(), instalment.dueDate()))) {
                throw new BnplStorageException("a BNPL obligation closes only once every instalment is billed: P0001");
            }
            for (BnplBilling billing : purchases.billings.values()) {
                if (!billing.obligation().equals(closure.obligation())) {
                    continue;
                }
                Money zero = Money.ofPersisted(0, billing.principal().currency(), billing.principal().scale());
                if (!purchases.paid(billing, "INTEREST", zero).equals(billing.interest())
                        || !purchases.paid(billing, "PRINCIPAL", zero).equals(billing.principal())
                        || !purchases.paid(billing, "FEES", zero).equals(purchases.billedFees(billing, zero))) {
                    throw new BnplStorageException("a BNPL obligation closes only once every billed item is paid in"
                            + " full: P0001");
                }
            }
            closures.put(closure.obligation().value(), closure);
        }

        @Override
        public boolean close(Connection unitOfWork, BnplObligationId obligation, BnplClosureReason reason) {
            step("obligation CLOSED");
            BnplObligation row = purchases.obligations.get(obligation.value());
            if (row == null || row.status() != BnplObligationStatus.ACTIVE) {
                return false;
            }
            BnplClosure closure = closures.get(obligation.value());
            if (closure == null || closure.reason() != reason) {
                throw new BnplStorageException("a BNPL obligation is CLOSED only beside its closure: P0001");
            }
            purchases.obligations.put(obligation.value(), new BnplObligation(row.id(), row.purchase(), row.agreement(),
                    row.party(), row.decision(), row.product(), row.plan(), row.currency(), BnplObligationStatus.CLOSED,
                    row.purchaseDate(), row.openedAt(), Optional.of(reason), row.correlation()));
            return true;
        }
    }

    /**
     * {@code bnpl V011}'s collection attempts, in memory (`P12-TSK-019`): {@code UNIQUE (instalment_id, attempt_date)} and
     * {@code UNIQUE (repayment_id)}; the trigger's ranks - an {@code ACTIVE} obligation under its agreement version in force
     * whose pinned terms collect automatically, {@code PARTIAL} only when they allow it, dated the database clock's business
     * date, a billed instalment of its own obligation due by the date, never back-filled, due less collected what the
     * instalment still has due, and the repayment its own {@code AUTO_COLLECTION} under the attempt's key for the amount
     * collected, every line on the instalment; and at commit every {@code AUTO_COLLECTION} repayment named by its attempt.
     */
    final class Collections implements BnplCollectionStore {

        final List<BnplCollectionAttempt> rows = new ArrayList<>();
        /** Drop {@code UNIQUE (instalment_id, attempt_date)} - for a probe only. */
        boolean dateUniqueDropped;

        List<BnplCollectionAttempt> state() {
            return new ArrayList<>(rows);
        }

        void restore(List<BnplCollectionAttempt> state) {
            rows.clear();
            rows.addAll(state);
        }

        /** The deferred trigger: an automatic collection's money always has its attempt. */
        void atCommit() {
            for (BnplPurchaseStore.Repayment repayment : purchases.repayments.values()) {
                if (repayment.kind().equals(BnplPurchaseStore.Repayment.AUTO_COLLECTION) && rows.stream()
                        .noneMatch(row -> row.repayment().filter(repayment.id()::equals).isPresent())) {
                    throw new BnplStorageException("an automatic BNPL collection's repayment is named by its collection"
                            + " attempt: P0001");
                }
            }
        }

        List<BnplCollectionAttempt> of(BnplObligationId obligation) {
            return rows.stream().filter(row -> row.obligation().equals(obligation)).toList();
        }

        @Override
        public Map<UUID, java.time.LocalDate> latestAttempts(Connection unitOfWork, BnplObligationId obligation) {
            log.add("attempts read");
            Map<UUID, java.time.LocalDate> latest = new LinkedHashMap<>();
            for (BnplCollectionAttempt row : of(obligation)) {
                latest.merge(row.instalment(), row.attemptDate(), (a, b) -> a.isAfter(b) ? a : b);
            }
            return latest;
        }

        @Override
        public void insertAttempt(Connection unitOfWork, BnplCollectionAttempt attempt) {
            step("attempt insert");
            if (!dateUniqueDropped && rows.stream().anyMatch(row -> row.instalment().equals(attempt.instalment())
                    && row.attemptDate().equals(attempt.attemptDate()))) {
                throw new BnplStorageException("bnpl_collection_attempt_instalment_date_unique: SQLSTATE 23505");
            }
            if (attempt.repayment().isPresent()
                    && rows.stream().anyMatch(row -> row.repayment().equals(attempt.repayment()))) {
                throw new BnplStorageException("bnpl_collection_attempt_repayment_unique: SQLSTATE 23505");
            }
            BnplObligation obligation = purchases.obligations.get(attempt.obligation().value());
            if (obligation == null || obligation.status() != BnplObligationStatus.ACTIVE) {
                throw new BnplStorageException("a BNPL collection is attempted only on an ACTIVE obligation: P0001");
            }
            BnplAgreement agreement = purchases.agreements.get(obligation.agreement().value());
            BnplTerms pinned = terms.versions.get(agreement.termsVersionId()).terms();
            if (attempt.agreementVersion() != agreement.version() || !pinned.autoCollection()) {
                throw new BnplStorageException("a BNPL collection is attempted only under an agreement whose pinned terms"
                        + " collect automatically: P0001");
            }
            if (attempt.outcome() == CollectionOutcome.PARTIAL && !pinned.partialCollection()) {
                throw new BnplStorageException("a partial BNPL collection only where the pinned terms allow it: P0001");
            }
            if (!attempt.attemptDate().equals(java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC))) {
                throw new BnplStorageException("a BNPL collection attempt is dated the business date of the database"
                        + " clock: P0001");
            }
            BnplBilling billing = purchases.billings.get(attempt.instalment());
            if (billing == null || !billing.obligation().equals(attempt.obligation())
                    || billing.dueDate().isAfter(attempt.attemptDate())) {
                throw new BnplStorageException("a BNPL collection is attempted on a billed instalment of its own"
                        + " obligation, on or after its due date: P0001");
            }
            if (rows.stream().anyMatch(row -> row.instalment().equals(attempt.instalment())
                    && row.attemptDate().isAfter(attempt.attemptDate()))) {
                throw new BnplStorageException("a BNPL collection attempt is never back-filled: P0001");
            }
            Money zero = Money.ofPersisted(0, billing.principal().currency(), billing.principal().scale());
            Money stillDue = billing.interest().minus(purchases.paid(billing, "INTEREST", zero))
                    .plus(billing.principal().minus(purchases.paid(billing, "PRINCIPAL", zero)))
                    .plus(purchases.billedFees(billing, zero).minus(purchases.paid(billing, "FEES", zero)));
            if (!attempt.due().minus(attempt.collected()).equals(stillDue)) {
                throw new BnplStorageException("a BNPL collection attempt records what its instalment had due and what"
                        + " it collected: P0001");
            }
            if (attempt.repayment().isPresent()) {
                BnplPurchaseStore.Repayment repayment = purchases.repayments.get(attempt.repayment().get());
                if (repayment == null || !repayment.obligation().equals(attempt.obligation())
                        || !repayment.kind().equals(BnplPurchaseStore.Repayment.AUTO_COLLECTION)
                        || !repayment.requestKey().equals(BnplCollectionAttempt.requestKey(attempt.instalment(),
                                attempt.attemptDate()))
                        || !repayment.amount().equals(attempt.collected())
                        || repayment.allocations().stream().anyMatch(line -> !line.target().equals("DUE_ITEM")
                                || !line.instalment().orElseThrow().equals(attempt.instalment()))) {
                    throw new BnplStorageException("a BNPL collection names its own AUTO_COLLECTION repayment, which pays"
                            + " only its instalment: P0001");
                }
            }
            rows.add(attempt);
        }
    }

    /**
     * {@code bnpl V009}'s accrual writer, in memory (`P12-TSK-017`): the rows T-acc births - each also on
     * {@link Servicing#accruals}, the record the billing reads - and the trigger's ranks: {@code UNIQUE (obligation_id,
     * accrual_date)}, oldest first, never early on the database clock, never before the purchase's business date, an
     * {@code ACTIVE} pay-in-N obligation under its agreement version in force and the engine it pins, an instalment period
     * of the latest schedule version (nothing at or after the final due date), never into a billed period. B4 is the
     * servicing fake's, logged.
     */
    final class Accruals implements BnplAccrualStore {

        final List<BnplInterestAccrual> rows = new ArrayList<>();

        List<BnplInterestAccrual> state() {
            return new ArrayList<>(rows);
        }

        void restore(List<BnplInterestAccrual> state) {
            rows.clear();
            rows.addAll(state);
        }

        /** The obligation's rows, oldest first. */
        List<BnplInterestAccrual> of(BnplObligationId obligation) {
            return rows.stream().filter(row -> row.obligation().equals(obligation))
                    .sorted(java.util.Comparator.comparing(row -> row.accrual().date())).toList();
        }

        @Override
        public Optional<BnplInterestAccrual> latest(Connection unitOfWork, BnplObligationId obligation) {
            List<BnplInterestAccrual> mine = of(obligation);
            return mine.isEmpty() ? Optional.empty() : Optional.of(mine.get(mine.size() - 1));
        }

        @Override
        public void insert(Connection unitOfWork, BnplInterestAccrual row) {
            step("accrual insert");
            BnplObligation obligation = purchases.obligations.get(row.obligation().value());
            java.time.LocalDate date = row.accrual().date();
            if (rows.stream().anyMatch(other -> other.obligation().equals(row.obligation())
                    && other.accrual().date().equals(date))) {
                throw new BnplStorageException("bnpl_interest_accrual_date_unique: SQLSTATE 23505");
            }
            if (obligation == null || obligation.status() != BnplObligationStatus.ACTIVE
                    || obligation.product() != BnplProduct.BNPL_PAY_IN_N) {
                throw new BnplStorageException("interest accrues only on an ACTIVE pay-in-N obligation: P0001");
            }
            BnplAgreement agreement = purchases.agreements.get(obligation.agreement().value());
            if (!agreement.id().equals(row.agreement()) || agreement.version() != row.agreementVersion()
                    || !agreement.accrualEngine().equals(Optional.of(row.accrual().engine().name()))) {
                throw new BnplStorageException("an accrual names its agreement version in force and its engine: P0001");
            }
            java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
            if (!date.isBefore(today) || date.isBefore(obligation.purchaseDate())) {
                throw new BnplStorageException("a date accrues only once it has ended on the database clock: P0001");
            }
            BnplPurchaseStore.StoredSchedule schedule = purchases.schedules.get(obligation.id().value());
            if (!BnplAccruals.periodsOf(schedule).contains(row.accrual().period())) {
                throw new BnplStorageException("an accrual lies in an instalment period of the latest schedule: P0001");
            }
            if (latest(unitOfWork, row.obligation()).filter(prior -> !prior.accrual().date().isBefore(date)).isPresent()) {
                throw new BnplStorageException("an obligation's dates accrue oldest first, each once: P0001");
            }
            if (purchases.billings.values().stream().anyMatch(billing -> billing.obligation().equals(row.obligation())
                    && billing.dueDate().isAfter(date))) {
                throw new BnplStorageException("a date inside a period already billed never accrues: P0001");
            }
            rows.add(row);
            servicing.accruals.add(new Servicing.Accrual(row.obligation(), date, row.accrual().posted()));
        }

        @Override
        public Optional<java.time.Duration> oldestLag(Connection unitOfWork) {
            java.time.Duration oldest = null;
            java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
            for (BnplObligation obligation : purchases.obligations.values()) {
                if (obligation.status() != BnplObligationStatus.ACTIVE
                        || obligation.product() != BnplProduct.BNPL_PAY_IN_N) {
                    continue;
                }
                java.time.LocalDate next = latest(unitOfWork, obligation.id())
                        .map(row -> row.accrual().date().plusDays(1)).orElse(obligation.purchaseDate());
                List<com.finapp.creditmath.AccrualPeriod> periods =
                        BnplAccruals.periodsOf(purchases.schedules.get(obligation.id().value()));
                java.time.LocalDate spanEnd = periods.get(periods.size() - 1).endExclusive();
                if (next.isBefore(today) && next.isBefore(spanEnd)) {
                    java.time.Duration lag = java.time.Duration.between(
                            next.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), now);
                    if (oldest == null || lag.compareTo(oldest) > 0) {
                        oldest = lag;
                    }
                }
            }
            return Optional.ofNullable(oldest);
        }
    }

    /**
     * {@code bnpl V012}'s refund world, in memory (`P12-TSK-021`): the purchase's row lock B5 (logged; merchant-scoped in
     * the statement at the merchant's door), the key's arbiter {@code UNIQUE (merchant_id, requested_by, refund_key)}, the
     * refund trigger's ranks - a {@code FINANCED} purchase of its merchant, Σ refunds never above the gross
     * ({@code INV-BNPL-07}), Σ payable debits never above the financed net (B7), the versions in force, the floor, a
     * {@code CLOSED} obligation's refund all excess to the wallet - the adjustments summing to the refund, the schedule and
     * agreement versions n+1 (the next version, principal falling by exactly the unbilled cancellation, every billing kept
     * as billed), and the purchase's terminal edge only when the refunds total the gross.
     */
    final class Refunds implements BnplRefundStore {

        final Map<UUID, BnplRefund> rows = new LinkedHashMap<>();
        /** Every agreement version a refund made, by agreement and version. */
        final Map<String, BnplAgreement> agreementVersions = new LinkedHashMap<>();
        /** The purchases locked under B5, in order. */
        final List<UUID> locked = new ArrayList<>();

        record State(Map<UUID, BnplRefund> rows, Map<String, BnplAgreement> agreementVersions) {}

        State state() {
            return new State(new LinkedHashMap<>(rows), new LinkedHashMap<>(agreementVersions));
        }

        void restore(State state) {
            reset(rows, state.rows());
            reset(agreementVersions, state.agreementVersions());
        }

        @Override
        public Optional<LockedPurchase> lockAtMerchant(Connection unitOfWork, BnplPurchaseId id, UUID merchant) {
            step("B5");
            return lockedPurchase(id).filter(found -> found.purchase().merchant().equals(merchant));
        }

        @Override
        public Optional<LockedPurchase> lock(Connection unitOfWork, BnplPurchaseId id) {
            step("B5");
            return lockedPurchase(id);
        }

        private Optional<LockedPurchase> lockedPurchase(BnplPurchaseId id) {
            BnplPurchase purchase = purchases.rows.get(id.value());
            MerchantFinancing financing = purchases.financings.get(id.value());
            if (purchase == null || financing == null) {
                return Optional.empty();
            }
            BnplObligation obligation = purchases.obligations.values().stream()
                    .filter(row -> row.purchase().equals(id)).findFirst().orElseThrow();
            locked.add(id.value());
            MerchantFeeSplit split = financing.split();
            return Optional.of(new LockedPurchase(purchase, split.gross(), split.fee(), split.net(), obligation.id()));
        }

        @Override
        public Optional<BnplRefund> findByKey(Connection unitOfWork, UUID merchant, String requestedBy,
                String refundKey) {
            return rows.values().stream().filter(row -> row.merchant().equals(merchant)
                    && row.requestedBy().equals(requestedBy) && row.refundKey().equals(refundKey)).findFirst();
        }

        @Override
        public List<BnplRefund> ofPurchase(Connection unitOfWork, BnplPurchaseId purchase) {
            return rows.values().stream().filter(row -> row.purchase().equals(purchase)).toList();
        }

        @Override
        public List<BnplRefund.Adjustment> adjustmentsOf(Connection unitOfWork, BnplObligationId obligation) {
            List<BnplRefund.Adjustment> adjustments = new ArrayList<>();
            for (BnplRefund refund : rows.values()) {
                if (refund.obligation().equals(obligation)) {
                    adjustments.addAll(refund.adjustments());
                }
            }
            return adjustments;
        }

        @Override
        public void insertScheduleVersion(Connection unitOfWork, BnplPurchaseStore.StoredSchedule schedule, UUID refund) {
            step("schedule version insert");
            BnplPurchaseStore.StoredSchedule previous = purchases.schedules.get(schedule.obligation().value());
            BnplObligation obligation = purchases.obligations.get(schedule.obligation().value());
            if (obligation.status() != BnplObligationStatus.ACTIVE || schedule.version() != previous.version() + 1
                    || !schedule.start().equals(obligation.purchaseDate())) {
                throw new BnplStorageException("a BNPL schedule version n+1 is the next version of an ACTIVE obligation,"
                        + " from its purchase date: P0001");
            }
            Money scheduled = Money.ofPersisted(0, schedule.principal().currency(), schedule.principal().scale());
            for (int index = 0; index < schedule.instalments().size(); index++) {
                BnplPurchaseStore.StoredInstalment instalment = schedule.instalments().get(index);
                if (instalment.sequence() != index + 1) {
                    throw new BnplStorageException("a BNPL schedule's instalments are numbered 1 to n: P0001");
                }
                if (!instalment.principal().isPositive()
                        && !purchases.billedOn(schedule.obligation(), instalment.dueDate())) {
                    throw new BnplStorageException("a BNPL instalment schedules a positive principal: P0001");
                }
                scheduled = scheduled.plus(instalment.principal());
            }
            if (!scheduled.equals(schedule.principal())) {
                throw new BnplStorageException("a BNPL schedule conserves (INV-BNPL-05): P0001");
            }
            for (BnplBilling billing : purchases.billings.values()) {
                if (billing.obligation().equals(schedule.obligation()) && schedule.instalments().stream().noneMatch(row ->
                        row.dueDate().equals(billing.dueDate()) && row.principal().equals(billing.principal())
                                && row.interest().equals(billing.interest()))) {
                    throw new BnplStorageException("a refund's schedule version keeps every billed instalment as it"
                            + " was billed: P0001");
                }
            }
            pendingVersions.put(refund, previous);
            purchases.schedules.put(schedule.obligation().value(), schedule);
            purchases.versions.put(schedule.obligation().value() + ":" + schedule.version(), schedule);
        }

        /** Each refund's predecessor schedule, for the at-insert half of the version identity (V012's deferred check). */
        private final Map<UUID, BnplPurchaseStore.StoredSchedule> pendingVersions = new LinkedHashMap<>();

        @Override
        public Instant insertAgreementVersion(Connection unitOfWork, BnplAgreement agreement, UUID refund,
                int scheduleVersion) {
            step("agreement version insert");
            BnplAgreement previous = purchases.agreements.get(agreement.id().value());
            if (agreement.version() != previous.version() + 1
                    || !agreement.canonicalTerms().equals(previous.canonicalTerms())
                    || !java.util.Arrays.equals(agreement.termsSha256(), previous.termsSha256())
                    || !agreement.allocationEngine().equals(previous.allocationEngine())
                    || !agreement.refundAllocationEngine().equals(previous.refundAllocationEngine())
                    || !agreement.feeScheduleVersion().equals(previous.feeScheduleVersion())
                    || scheduleVersion != purchases.schedules.get(agreement.obligation().value()).version()) {
                throw new BnplStorageException("a BNPL agreement version n+1 is a copy of version n governing the next"
                        + " schedule version: P0001");
            }
            purchases.agreements.put(agreement.id().value(), agreement.stampedAt(now));
            agreementVersions.put(agreement.id().value() + ":" + agreement.version(), agreement.stampedAt(now));
            return now;
        }

        @Override
        public Instant insertRefund(Connection unitOfWork, BnplRefund refund) {
            step("refund insert");
            if (rows.values().stream().anyMatch(row -> row.merchant().equals(refund.merchant())
                    && row.requestedBy().equals(refund.requestedBy()) && row.refundKey().equals(refund.refundKey()))) {
                throw new BnplStorageException("bnpl_refund_request_unique: SQLSTATE 23505");
            }
            BnplPurchase purchase = purchases.rows.get(refund.purchase().value());
            MerchantFeeSplit split = purchases.financings.get(refund.purchase().value()).split();
            BnplObligation obligation = purchases.obligations.get(refund.obligation().value());
            if (purchase.status() != BnplPurchaseStatus.FINANCED || !purchase.merchant().equals(refund.merchant())) {
                throw new BnplStorageException("a BNPL refund refunds a FINANCED purchase of its merchant: P0001");
            }
            Money refunded = Money.ofPersisted(0, refund.amount().currency(), refund.amount().scale());
            Money debited = refunded;
            Money returned = refunded;
            for (BnplRefund row : ofPurchase(unitOfWork, refund.purchase())) {
                refunded = refunded.plus(row.amount());
                debited = debited.plus(row.payableDebit());
                returned = returned.plus(row.feeReturned());
            }
            if (!refund.refundedBefore().equals(refunded) || refunded.plus(refund.amount()).compareTo(split.gross()) > 0) {
                throw new BnplStorageException("a BNPL purchase's refunds never total more than its gross"
                        + " (INV-BNPL-07): P0001");
            }
            if (debited.plus(refund.payableDebit()).compareTo(split.net()) > 0
                    || returned.plus(refund.feeReturned()).compareTo(split.fee()) > 0) {
                throw new BnplStorageException("a BNPL purchase's refunds take back at most its financed net (B7): P0001");
            }
            BnplAgreement agreement = purchases.agreements.get(obligation.agreement().value());
            if (refund.agreementVersion() != agreement.version()
                    || refund.scheduleVersion() != purchases.schedules.get(obligation.id().value()).version()) {
                throw new BnplStorageException("a BNPL refund names its agreement and schedule versions in force: P0001");
            }
            java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
            if (obligation.status() == BnplObligationStatus.ACTIVE) {
                java.time.LocalDate floor = servicing.principalValueFloor(unitOfWork, obligation.id());
                java.time.LocalDate ceiling = today.isAfter(floor) ? today : floor;
                if (refund.valueDate().isBefore(floor) || refund.valueDate().isAfter(ceiling)) {
                    throw new BnplStorageException("a BNPL refund is value-dated on or after its obligation's floor"
                            + " and never after today: P0001");
                }
            } else if (refund.cancelled().isPositive() || refund.interestReleased().isPositive()
                    || refund.excessTo() != BnplRefund.ExcessTo.WALLET) {
                throw new BnplStorageException("a refund of a CLOSED obligation cancels nothing and pays the wallet"
                        + " only: P0001");
            }
            // The version identity: a refund that cancels unbilled principal made the version, its predecessor's principal
            // less exactly that cancellation.
            BnplPurchaseStore.StoredSchedule previous = pendingVersions.remove(refund.id());
            if (refund.cancelledUnbilled().isPositive() != (previous != null)) {
                throw new BnplStorageException("a BNPL refund that cancels unbilled principal commits beside the schedule"
                        + " version it made: P0001");
            }
            if (previous != null && !purchases.schedules.get(obligation.id().value()).principal()
                    .equals(previous.principal().minus(refund.cancelledUnbilled()))) {
                throw new BnplStorageException("a refund's schedule version schedules its predecessor's principal less"
                        + " exactly the unbilled cancellation: P0001");
            }
            for (BnplRefund.Adjustment adjustment : refund.adjustments()) {
                boolean billed = purchases.billings.containsKey(adjustment.instalment());
                if ((adjustment.kind() == RefundAllocation.Cancellation.Kind.BILLED_UNPAID) != billed) {
                    throw new BnplStorageException("a billed-unpaid cancellation names a billed instalment, an unbilled"
                            + " one an instalment not yet billed: P0001");
                }
            }
            BnplRefund stamped = refund.stampedAt(now);
            rows.put(refund.id(), stamped);
            // At commit: no billed component settled beyond what it billed.
            for (BnplRefund.Adjustment adjustment : refund.adjustments()) {
                BnplBilling billing = purchases.billings.get(adjustment.instalment());
                if (billing == null) {
                    continue;
                }
                Money zero = Money.ofPersisted(0, billing.principal().currency(), billing.principal().scale());
                if (purchases.paid(billing, "PRINCIPAL", zero).compareTo(billing.principal()) > 0
                        || purchases.paid(billing, "INTEREST", zero).compareTo(billing.interest()) > 0
                        || purchases.paid(billing, "FEES", zero).compareTo(purchases.billedFees(billing, zero)) > 0) {
                    rows.remove(refund.id());
                    throw new BnplStorageException("no billed component is ever settled beyond what it billed: P0001");
                }
                // bnpl V013 (OQ-14): an instalment cancelled whole owes no late fee after the refund.
                if (adjustment.kind() == RefundAllocation.Cancellation.Kind.BILLED_UNPAID && adjustment.whole()
                        && !purchases.paid(billing, "FEES", zero).equals(purchases.billedFees(billing, zero))) {
                    rows.remove(refund.id());
                    throw new BnplStorageException("a BNPL refund that cancels a billed instalment whole releases every"
                            + " late fee still due on it: P0001");
                }
            }
            return now;
        }

        @Override
        public boolean fullyRefunded(Connection unitOfWork, BnplPurchaseId purchase) {
            step("purchase FULLY_REFUNDED");
            BnplPurchase row = purchases.rows.get(purchase.value());
            if (row == null || row.status() != BnplPurchaseStatus.FINANCED) {
                return false;
            }
            Money refunded = Money.ofPersisted(0, row.currency(), row.currency().minorUnits());
            for (BnplRefund refund : ofPurchase(unitOfWork, purchase)) {
                refunded = refunded.plus(refund.amount());
            }
            if (!refunded.equals(purchases.financings.get(purchase.value()).split().gross())) {
                throw new BnplStorageException("a BNPL purchase is FULLY_REFUNDED only when its refunds total its gross:"
                        + " P0001");
            }
            purchases.rows.put(purchase.value(), new BnplPurchase(row.id(), row.offer(), row.checkoutSession(),
                    row.party(), row.merchant(), row.product(), row.plan(), row.currency(),
                    BnplPurchaseStatus.FULLY_REFUNDED, row.businessDate(), row.authorisedAt(), row.financedAt(),
                    row.order(), row.correlation()));
            return true;
        }

        @Override
        public List<BnplRefund> atMerchant(Connection unitOfWork, BnplPurchaseId purchase, UUID merchant) {
            return rows.values().stream()
                    .filter(row -> row.purchase().equals(purchase) && row.merchant().equals(merchant)).toList();
        }

        @Override
        public Optional<BnplPurchaseId> purchaseOfRefundAtMerchant(Connection unitOfWork, UUID refund, UUID merchant) {
            return Optional.ofNullable(rows.get(refund)).filter(row -> row.merchant().equals(merchant))
                    .map(BnplRefund::purchase);
        }
    }

    /** Lending capital: the recognised capital less the book's BNPL principal, judged under L6. */
    /**
     * {@code bnpl V014}'s payoff world, in memory (`P12-TSK-022`): the quote - {@code UNIQUE (obligation_id, request_key)},
     * its trigger's ranks (an {@code ACTIVE} obligation under its agreement version in force, made on the database clock's
     * date) and its {@code CHECK} that the amount is its parts (the record's own) - and the execution -
     * {@code UNIQUE (quote_id)}, {@code UNIQUE (obligation_id)}, its own obligation's quote on an {@code ACTIVE} obligation,
     * on the quote's good-through date which is today, valued {@code max(today, floor)} with the floor read at its birth -
     * and at commit every execution's obligation {@code CLOSED (PAID_OFF_EARLY)}.
     */
    final class Payoffs implements BnplPayoffStore {

        final Map<UUID, BnplPayoffQuote> quotes = new LinkedHashMap<>();
        final Map<UUID, BnplPayoffExecution> executions = new LinkedHashMap<>();

        /** Everything, for the unit of work's rollback. */
        record State(Map<UUID, BnplPayoffQuote> quotes, Map<UUID, BnplPayoffExecution> executions) {}

        State state() {
            return new State(new LinkedHashMap<>(quotes), new LinkedHashMap<>(executions));
        }

        void restore(State state) {
            reset(quotes, state.quotes());
            reset(executions, state.executions());
        }

        Optional<BnplPayoffExecution> executionOf(BnplObligationId obligation) {
            return executions.values().stream().filter(row -> row.obligation().equals(obligation)).findFirst();
        }

        void atCommit() {
            for (BnplPayoffExecution execution : executions.values()) {
                BnplObligation obligation = purchases.obligations.get(execution.obligation().value());
                if (obligation.status() != BnplObligationStatus.CLOSED
                        || obligation.closureReason().filter(BnplClosureReason.PAID_OFF_EARLY::equals).isEmpty()) {
                    throw new BnplStorageException("a BNPL payoff execution committed without its obligation CLOSED"
                            + " PAID_OFF_EARLY: P0001");
                }
            }
        }

        @Override
        public Optional<BnplPayoffQuote> findQuoteByKey(Connection unitOfWork, BnplObligationId obligation,
                String requestKey) {
            return quotes.values().stream().filter(row -> row.obligation().equals(obligation)
                    && row.requestKey().equals(requestKey)).findFirst();
        }

        @Override
        public Optional<BnplPayoffQuote> findQuote(Connection unitOfWork, BnplObligationId obligation, UUID quote) {
            return Optional.ofNullable(quotes.get(quote)).filter(row -> row.obligation().equals(obligation));
        }

        @Override
        public Instant insertQuote(Connection unitOfWork, BnplPayoffQuote quote) {
            step("quote insert");
            if (findQuoteByKey(unitOfWork, quote.obligation(), quote.requestKey()).isPresent()) {
                throw new BnplStorageException("bnpl_payoff_quote_key_unique: SQLSTATE 23505");
            }
            BnplObligation obligation = purchases.obligations.get(quote.obligation().value());
            BnplAgreement agreement = obligation == null ? null : purchases.agreements.get(obligation.agreement().value());
            java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
            if (obligation == null || obligation.status() != BnplObligationStatus.ACTIVE
                    || agreement.version() != quote.agreementVersion() || !quote.businessDate().equals(today)) {
                throw new BnplStorageException("a BNPL payoff is quoted only for an ACTIVE obligation under its agreement"
                        + " in force, on the database clock's date: P0001");
            }
            quotes.put(quote.id(), quote);
            return now;
        }

        @Override
        public Optional<BnplPayoffExecution> findExecution(Connection unitOfWork, BnplObligationId obligation) {
            return executionOf(obligation);
        }

        @Override
        public Instant insertExecution(Connection unitOfWork, BnplPayoffExecution execution) {
            step("execution insert");
            if (executions.values().stream().anyMatch(row -> row.quote().equals(execution.quote()))) {
                throw new BnplStorageException("bnpl_payoff_execution_quote_unique: SQLSTATE 23505");
            }
            if (executionOf(execution.obligation()).isPresent()) {
                throw new BnplStorageException("bnpl_payoff_execution_obligation_unique: SQLSTATE 23505");
            }
            BnplPayoffQuote quote = quotes.get(execution.quote());
            BnplObligation obligation = purchases.obligations.get(execution.obligation().value());
            java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
            if (quote == null || !quote.obligation().equals(execution.obligation()) || obligation == null
                    || obligation.status() != BnplObligationStatus.ACTIVE
                    || purchases.agreements.get(obligation.agreement().value()).version() != quote.agreementVersion()) {
                throw new BnplStorageException("a BNPL payoff executes its own obligation's quote, on an ACTIVE"
                        + " obligation: P0001");
            }
            if (!execution.businessDate().equals(quote.goodThrough()) || !execution.businessDate().equals(today)) {
                throw new BnplStorageException("a BNPL payoff quote is executed only on its good-through date: P0001");
            }
            java.time.LocalDate floor = servicing.principalValueFloor(unitOfWork, execution.obligation());
            if (!execution.valueDate().equals(floor.isAfter(today) ? floor : today)) {
                throw new BnplStorageException("a BNPL payoff is value-dated max(its date, the floor): P0001");
            }
            executions.put(execution.id(), execution);
            return now;
        }
    }

    final class Capital implements BnplCapital {

        Money recognised = Money.ofMinorUnits(1_000_000_00L, BnplTermsFixtures.EUR);

        @Override
        public BnplHeadroom underLock(Connection unitOfWork, com.finapp.sharedkernel.money.CurrencyCode currency) {
            step("L6");
            return new BnplHeadroom(currency, recognised.minus(book.bnplPrincipal()));
        }
    }

    /** The customer's existing wallet. */
    final class Wallets implements BnplWallets {

        final Map<UUID, com.finapp.ledger.LedgerAccountId> byParty = new LinkedHashMap<>();

        @Override
        public Optional<com.finapp.ledger.LedgerAccountId> existingWallet(Connection unitOfWork, UUID party,
                com.finapp.sharedkernel.money.CurrencyCode currency) {
            return Optional.ofNullable(byParty.get(party));
        }
    }

    /** T-svc's collection slot over these fakes (`P12-TSK-019`): the collections, through T-rep's posting half. */
    BnplCollections collectionsOver(BnplClosures closures, com.finapp.sharedkernel.id.IdGenerator ids,
            java.time.Clock clock) {
        BnplRepayments posting = new BnplRepayments(repayments, servicing, purchases, terms.store(), wallets, book,
                closures, outbox, ids, clock);
        return new BnplCollections(collections, posting, purchases, wallets, book, outbox, ids, clock);
    }

    /** T-pay over these fakes (`P12-TSK-022`): the quote and the execution, through the step's billing and T-rep's half. */
    BnplPayoffs payoffsOver(BnplServicing servicingStep, BnplRepayments posting, BnplClosures closures,
            com.finapp.sharedkernel.id.IdGenerator ids) {
        return new BnplPayoffs(repayments, payoffs, servicing, purchases, terms.store(), accruals, wallets, book,
                servicingStep, posting, closures, ids);
    }

    /**
     * {@code bnpl V013}'s condition history and fee assessments, in memory (`P12-TSK-020`): {@code UNIQUE (obligation_id,
     * business_date, kind)} and {@code UNIQUE (instalment_id, kind)} (each droppable for a probe), and the triggers' ranks -
     * an {@code ACTIVE} obligation under its agreement version in force, dated the database clock's business date, never
     * dated before a recorded change; DPD the date difference to the oldest billed instalment still owing anything, the
     * bucket its bucket under the pinned bounds, the flag set at the threshold and cleared at cure, a change only; a fee on
     * a billed instalment of its own obligation aged at least the pinned day, at most the pinned fee, the agreement's fees
     * within the cap, the instalment still owing interest or principal. Computed here from the fakes' rows - the database's
     * own derivation, never the domain's.
     */
    final class Conditions implements BnplConditionStore {

        final List<BnplConditionEvent> events = new ArrayList<>();
        final List<BnplFeeAssessment> assessments = new ArrayList<>();
        /** Drop {@code UNIQUE (obligation_id, business_date, kind)} - for a probe only. */
        boolean onceADayUniqueDropped;
        /** Drop {@code UNIQUE (instalment_id, kind)} - for a probe only. */
        boolean feeUniqueDropped;

        record State(List<BnplConditionEvent> events, List<BnplFeeAssessment> assessments) {}

        State state() {
            return new State(new ArrayList<>(events), new ArrayList<>(assessments));
        }

        void restore(State state) {
            events.clear();
            events.addAll(state.events());
            assessments.clear();
            assessments.addAll(state.assessments());
        }

        List<BnplConditionEvent> of(BnplObligationId obligation) {
            return events.stream().filter(row -> row.obligation().equals(obligation)).toList();
        }

        List<BnplFeeAssessment> feesOf(BnplObligationId obligation) {
            return assessments.stream().filter(row -> row.obligation().equals(obligation)).toList();
        }

        @Override
        public Instant databaseNow(Connection unitOfWork) {
            return now;
        }

        @Override
        public Optional<BnplConditionEvent> latest(Connection unitOfWork, BnplObligationId obligation,
                BnplConditionKind kind) {
            log.add("condition read");
            return of(obligation).stream().filter(row -> row.kind() == kind)
                    .max(java.util.Comparator.comparing(BnplConditionEvent::businessDate));
        }

        /** {@code bnpl.days_past_due}: the date less the oldest billed due date still owing anything, or 0. */
        int daysPastDue(BnplObligationId obligation, java.time.LocalDate on) {
            java.time.LocalDate oldest = null;
            for (BnplBilling billing : purchases.billings.values()) {
                if (!billing.obligation().equals(obligation) || !billing.dueDate().isBefore(on)) {
                    continue;
                }
                Money zero = Money.ofPersisted(0, billing.principal().currency(), billing.principal().scale());
                Money owing = zero;
                for (String component : List.of("FEES", "INTEREST", "PRINCIPAL")) {
                    owing = owing.plus(purchases.billed(billing, component, zero))
                            .minus(purchases.paid(billing, component, zero));
                }
                if (owing.isPositive() && (oldest == null || billing.dueDate().isBefore(oldest))) {
                    oldest = billing.dueDate();
                }
            }
            return oldest == null ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(oldest, on);
        }

        @Override
        public void insertCondition(Connection unitOfWork, BnplConditionEvent event) {
            step("condition insert");
            if (!onceADayUniqueDropped && events.stream().anyMatch(row -> row.obligation().equals(event.obligation())
                    && row.businessDate().equals(event.businessDate()) && row.kind() == event.kind())) {
                throw new BnplStorageException("bnpl_condition_event_once_a_day: SQLSTATE 23505");
            }
            BnplObligation obligation = purchases.obligations.get(event.obligation().value());
            if (obligation == null || obligation.status() != BnplObligationStatus.ACTIVE) {
                throw new BnplStorageException("a BNPL condition is recorded only for an ACTIVE obligation: P0001");
            }
            BnplAgreement agreement = purchases.agreements.get(obligation.agreement().value());
            if (event.agreementVersion() != agreement.version()) {
                throw new BnplStorageException("a BNPL condition names its agreement version in force: P0001");
            }
            if (!event.businessDate().equals(java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC))) {
                throw new BnplStorageException("a BNPL condition is dated the business date of the database clock: P0001");
            }
            if (of(event.obligation()).stream().anyMatch(row -> row.businessDate().isAfter(event.businessDate()))) {
                throw new BnplStorageException("a BNPL condition is never dated before a change already recorded: P0001");
            }
            BnplTerms pinned = terms.versions.get(agreement.termsVersionId()).terms();
            int derived = daysPastDue(event.obligation(), event.businessDate());
            if (event.state().daysPastDue() != derived
                    || !event.state().bucket().equals(BnplDelinquencyBucket.of(derived, pinned.delinquencyBounds()))) {
                throw new BnplStorageException("a BNPL condition's days past due and bucket are derived from its billed"
                        + " and settled rows: P0001");
            }
            Optional<BnplDelinquencyBucket> wasBucket = latest(unitOfWork, event.obligation(),
                    BnplConditionKind.DELINQUENCY).flatMap(row -> row.state().bucket());
            boolean wasDefaulted = latest(unitOfWork, event.obligation(), BnplConditionKind.DEFAULT)
                    .map(row -> row.state().defaulted()).orElse(false);
            if (event.state().defaulted() != (derived >= pinned.defaultThresholdDays() || (wasDefaulted && derived > 0))) {
                throw new BnplStorageException("a BNPL condition's default flag is set at the threshold and cleared at"
                        + " cure: P0001");
            }
            if ((event.kind() == BnplConditionKind.DELINQUENCY && (!event.previousBucket().equals(wasBucket)
                    || event.state().bucket().equals(wasBucket)))
                    || (event.kind() == BnplConditionKind.DEFAULT && event.state().defaulted() == wasDefaulted)) {
                throw new BnplStorageException("a BNPL condition is recorded only on a change: P0001");
            }
            events.add(event);
        }

        @Override
        public List<BnplFeeAssessment> assessments(Connection unitOfWork, BnplObligationId obligation) {
            log.add("assessments read");
            return feesOf(obligation);
        }

        @Override
        public void insertAssessment(Connection unitOfWork, BnplFeeAssessment assessment) {
            step("fee insert");
            if (!feeUniqueDropped && assessments.stream().anyMatch(row -> row.instalment().equals(assessment.instalment())
                    && row.kind() == assessment.kind())) {
                throw new BnplStorageException("bnpl_fee_assessment_once_per_instalment: SQLSTATE 23505");
            }
            BnplObligation obligation = purchases.obligations.get(assessment.obligation().value());
            if (obligation == null || obligation.status() != BnplObligationStatus.ACTIVE) {
                throw new BnplStorageException("a BNPL fee is assessed only on an ACTIVE obligation: P0001");
            }
            BnplBilling billing = purchases.billings.get(assessment.instalment());
            if (billing == null || !billing.obligation().equals(assessment.obligation())) {
                throw new BnplStorageException("a BNPL fee is assessed on a billed instalment of its own obligation: P0001");
            }
            BnplAgreement agreement = purchases.agreements.get(obligation.agreement().value());
            if (assessment.agreementVersion() != agreement.version()) {
                throw new BnplStorageException("a BNPL fee names its agreement version in force: P0001");
            }
            java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
            if (!assessment.businessDate().equals(today)) {
                throw new BnplStorageException("a BNPL fee is dated the business date of the database clock: P0001");
            }
            BnplTerms pinned = terms.versions.get(agreement.termsVersionId()).terms();
            if (java.time.temporal.ChronoUnit.DAYS.between(billing.dueDate(), today) < pinned.lateFeeDay()) {
                throw new BnplStorageException("a BNPL late fee is assessed only once its instalment's age has reached the"
                        + " pinned late-fee day: P0001");
            }
            Money assessed = Money.ofPersisted(0, assessment.amount().currency(), assessment.amount().scale());
            for (BnplFeeAssessment row : feesOf(assessment.obligation())) {
                assessed = assessed.plus(row.amount());
            }
            if (assessment.amount().compareTo(pinned.lateFee()) > 0
                    || assessed.plus(assessment.amount()).compareTo(pinned.lateFeeCap()) > 0) {
                throw new BnplStorageException("a BNPL late fee is at most the pinned fee, and an agreement's fees at most"
                        + " the pinned cap: P0001");
            }
            Money zero = Money.ofPersisted(0, billing.principal().currency(), billing.principal().scale());
            Money owing = billing.interest().minus(purchases.paid(billing, "INTEREST", zero))
                    .plus(billing.principal().minus(purchases.paid(billing, "PRINCIPAL", zero)));
            if (!owing.isPositive()) {
                throw new BnplStorageException("a BNPL late fee is assessed only on an instalment still owing interest or"
                        + " principal: P0001");
            }
            assessments.add(assessment);
        }

        @Override
        public Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> pastDueByProductAndBucket(Connection unitOfWork) {
            Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> counts = new EnumMap<>(BnplProduct.class);
            for (BnplObligation obligation : purchases.obligations.values()) {
                if (obligation.status() != BnplObligationStatus.ACTIVE) {
                    continue;
                }
                latest(unitOfWork, obligation.id(), BnplConditionKind.DELINQUENCY).flatMap(row -> row.state().bucket())
                        .ifPresent(bucket -> counts.computeIfAbsent(obligation.product(),
                                any -> new EnumMap<>(BnplDelinquencyBucket.class)).merge(bucket, 1L, Long::sum));
            }
            return counts;
        }

        @Override
        public Map<BnplProduct, Long> defaultedByProduct(Connection unitOfWork) {
            Map<BnplProduct, Long> counts = new EnumMap<>(BnplProduct.class);
            for (BnplObligation obligation : purchases.obligations.values()) {
                if (obligation.status() == BnplObligationStatus.ACTIVE && latest(unitOfWork, obligation.id(),
                        BnplConditionKind.DEFAULT).map(row -> row.state().defaulted()).orElse(false)) {
                    counts.merge(obligation.product(), 1L, Long::sum);
                }
            }
            return counts;
        }
    }

    /** T-svc's condition and late-fee slot over these fakes (`P12-TSK-020`). */
    BnplConditions conditionsOver(com.finapp.sharedkernel.id.IdGenerator ids, java.time.Clock clock) {
        return new BnplConditions(conditions, purchases, terms.store(), book, outbox, ids, clock);
    }

    /**
     * {@code bnpl V016}'s servicing corrections, in memory (`P12-TSK-024`): the reversal's machine - born {@code PROPOSED}
     * on an {@code ACTIVE} obligation for its own repayment under that repayment's agreement version, never a payoff's;
     * one live proposal per repayment (the partial unique); {@code PROPOSED -> APPROVED | REJECTED} only; the four-eyes
     * {@code CHECK}; an approval only while {@code ACTIVE} and valued between the floor and the later of today and it -
     * the negating rows (mirroring, once per allocation, only for an {@code APPROVED} reversal, whole at commit); and the
     * waiver - born on an {@code ACTIVE} obligation under its agreement version in force, within
     * {@code bnpl.fee_waiver_room}, one undecided per assessment - and its decision, born once, four eyes, an approval only
     * while {@code ACTIVE} and within the room. Each arbiter droppable for a probe. Computed here from the fakes' rows - the
     * database's own rules, never the domain's.
     */
    final class Corrections implements BnplCorrectionStore {

        final Map<UUID, BnplRepaymentReversal> reversals = new LinkedHashMap<>();
        /** Negating rows: allocation id to reversal id. */
        final Map<UUID, UUID> negations = new LinkedHashMap<>();
        final Map<UUID, BnplWaiver> waiverRows = new LinkedHashMap<>();
        /** The proposer's copy each decision carries, by waiver - the four-eyes CHECK's input. */
        final Map<UUID, String> decisionProposers = new LinkedHashMap<>();
        /** Drop the reversal's and the decision's four-eyes {@code CHECK}s - for a probe only. */
        boolean fourEyesCheckDropped;
        /** Drop the partial {@code UNIQUE (repayment_id)} over live reversals - for a probe only. */
        boolean oneLiveUniqueDropped;
        /** Drop the approved-waiver term of {@code billing_component_billed('FEES')} - for a probe only. */
        boolean waiverTermDropped;
        /** Drop the waiver triggers' bound - for a probe only. */
        boolean waiverRoomDropped;

        record State(Map<UUID, BnplRepaymentReversal> reversals, Map<UUID, UUID> negations,
                Map<UUID, BnplWaiver> waiverRows, Map<UUID, String> decisionProposers) {}

        State state() {
            return new State(new LinkedHashMap<>(reversals), new LinkedHashMap<>(negations),
                    new LinkedHashMap<>(waiverRows), new LinkedHashMap<>(decisionProposers));
        }

        void restore(State state) {
            reset(reversals, state.reversals());
            reset(negations, state.negations());
            reset(waiverRows, state.waiverRows());
            reset(decisionProposers, state.decisionProposers());
        }

        /** Whether an approved reversal's negating row undid allocation {@code allocation}. */
        boolean negated(UUID allocation) {
            return negations.containsKey(allocation);
        }

        List<BnplWaiver> waivers() {
            return List.copyOf(waiverRows.values());
        }

        /** At commit: every APPROVED reversal negates every allocation row of its repayment. */
        void atCommit() {
            for (BnplRepaymentReversal reversal : reversals.values()) {
                if (reversal.status() != BnplCorrectionStatus.APPROVED) {
                    continue;
                }
                for (BnplPurchaseStore.Allocation line : purchases.repayments.get(reversal.repayment()).allocations()) {
                    if (!reversal.id().equals(negations.get(line.id()))) {
                        throw new BnplStorageException("an approved BNPL repayment reversal negates every allocation row"
                                + " of its repayment: P0001");
                    }
                }
            }
        }

        private java.time.LocalDate today() {
            return java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
        }

        private boolean active(BnplObligationId obligation) {
            BnplObligation row = purchases.obligations.get(obligation.value());
            return row != null && row.status() == BnplObligationStatus.ACTIVE;
        }

        @Override
        public Optional<Addressed> addressed(Connection unitOfWork, BnplAgreementId agreement) {
            log.add("addressed");
            return purchases.obligations.values().stream().filter(row -> row.agreement().equals(agreement)).findFirst()
                    .map(row -> new Addressed(row.id(), row.party()));
        }

        @Override
        public Optional<BnplPurchaseStore.Repayment> repayment(Connection unitOfWork, BnplObligationId obligation, UUID repayment) {
            return Optional.ofNullable(purchases.repayments.get(repayment))
                    .filter(row -> row.obligation().equals(obligation));
        }

        @Override
        public Optional<BnplRepaymentReversal> liveReversal(Connection unitOfWork, UUID repayment) {
            return reversals.values().stream().filter(row -> row.repayment().equals(repayment)
                    && row.status() != BnplCorrectionStatus.REJECTED).findFirst();
        }

        @Override
        public Optional<Instant> insertReversal(Connection unitOfWork, NewReversal reversal) {
            step("reversal insert");
            if (!oneLiveUniqueDropped && liveReversal(unitOfWork, reversal.repayment()).isPresent()) {
                return Optional.empty();
            }
            BnplPurchaseStore.Repayment repayment = purchases.repayments.get(reversal.repayment());
            if (!active(reversal.obligation())) {
                throw new BnplStorageException("a repayment of a BNPL obligation not ACTIVE is never reversed: P0001");
            }
            if (repayment == null || !repayment.obligation().equals(reversal.obligation())
                    || !repayment.agreement().equals(reversal.agreement())
                    || repayment.agreementVersion() != reversal.agreementVersion()) {
                throw new BnplStorageException("a BNPL repayment reversal names a repayment of its own obligation, under"
                        + " its agreement version: P0001");
            }
            if (repayment.kind().equals(BnplPurchaseStore.Repayment.PAYOFF)) {
                throw new BnplStorageException("a PAYOFF repayment closed its obligation and is never reversed: P0001");
            }
            reversals.put(reversal.id(), new BnplRepaymentReversal(reversal.id(), reversal.obligation(),
                    reversal.repayment(), reversal.agreement(), reversal.agreementVersion(), BnplCorrectionStatus.PROPOSED,
                    reversal.proposer().id(), now, reversal.reason(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            return Optional.of(now);
        }

        @Override
        public Optional<BnplRepaymentReversal> lockReversal(Connection unitOfWork, UUID reversal) {
            step("B6");
            return Optional.ofNullable(reversals.get(reversal));
        }

        @Override
        public Optional<BnplRepaymentReversal> reversal(Connection unitOfWork, UUID reversal) {
            return Optional.ofNullable(reversals.get(reversal));
        }

        @Override
        public boolean decideReversal(Connection unitOfWork, ReversalDecision decision) {
            step("reversal " + decision.outcome());
            BnplRepaymentReversal row = reversals.get(decision.reversal());
            if (row == null || row.status() != BnplCorrectionStatus.PROPOSED) {
                return false;
            }
            if (!fourEyesCheckDropped && decision.outcome() == BnplCorrectionStatus.APPROVED
                    && decision.decider().id().equals(row.proposedBy())) {
                throw new BnplStorageException("bnpl_repayment_reversal_four_eyes: SQLSTATE 23514");
            }
            if (decision.outcome() == BnplCorrectionStatus.APPROVED) {
                if (!active(row.obligation())) {
                    throw new BnplStorageException("a repayment of a BNPL obligation not ACTIVE is never reversed: P0001");
                }
                java.time.LocalDate floor = servicing.principalValueFloor(unitOfWork, row.obligation());
                java.time.LocalDate value = decision.valueDate().orElseThrow();
                java.time.LocalDate ceiling = today().isAfter(floor) ? today() : floor;
                if (value.isBefore(floor) || value.isAfter(ceiling)) {
                    throw new BnplStorageException("a BNPL repayment reversal is valued on or after its obligation's"
                            + " floor and never after today: P0001");
                }
            }
            reversals.put(row.id(), new BnplRepaymentReversal(row.id(), row.obligation(), row.repayment(),
                    row.agreement(), row.agreementVersion(), decision.outcome(), row.proposedBy(), row.proposedAt(),
                    row.proposalReason(), Optional.of(decision.decider().id()), Optional.of(now),
                    Optional.of(decision.reason()), decision.entry(), decision.valueDate()));
            return true;
        }

        @Override
        public void insertNegations(Connection unitOfWork, UUID reversal, List<BnplPurchaseStore.Allocation> allocations,
                java.util.function.Supplier<UUID> ids) {
            step("negations insert");
            BnplRepaymentReversal row = reversals.get(reversal);
            if (row == null || row.status() != BnplCorrectionStatus.APPROVED) {
                throw new BnplStorageException("only an APPROVED BNPL repayment reversal negates allocations: P0001");
            }
            BnplPurchaseStore.Repayment repayment = purchases.repayments.get(row.repayment());
            for (BnplPurchaseStore.Allocation line : allocations) {
                if (repayment.allocations().stream().noneMatch(own -> own.id().equals(line.id())
                        && own.amount().equals(line.amount()))) {
                    throw new BnplStorageException("a negating row mirrors an allocation row of the reversed repayment"
                            + " exactly: P0001");
                }
                if (negations.containsKey(line.id())) {
                    throw new BnplStorageException("bnpl_repayment_allocation_reversal_allocation_unique: SQLSTATE 23505");
                }
                ids.get();
                negations.put(line.id(), reversal);
            }
        }

        @Override
        public List<BnplRepaymentReversal> reversalsOf(Connection unitOfWork, BnplObligationId obligation, int limit) {
            List<BnplRepaymentReversal> rows = new ArrayList<>(reversals.values().stream()
                    .filter(row -> row.obligation().equals(obligation)).toList());
            java.util.Collections.reverse(rows);
            return rows.stream().limit(limit).toList();
        }

        @Override
        public Set<UUID> reversedAmong(Connection unitOfWork, java.util.Collection<UUID> repayments) {
            return reversals.values().stream().filter(row -> row.status() == BnplCorrectionStatus.APPROVED
                    && repayments.contains(row.repayment())).map(BnplRepaymentReversal::repayment)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override
        public Optional<BnplFeeAssessment> assessment(Connection unitOfWork, BnplObligationId obligation,
                UUID assessment) {
            return conditions.assessments.stream()
                    .filter(row -> row.id().equals(assessment) && row.obligation().equals(obligation)).findFirst();
        }

        @Override
        public List<BnplWaiver> waiversOf(Connection unitOfWork, UUID assessment) {
            return waiverRows.values().stream().filter(row -> row.assessment().equals(assessment)).toList();
        }

        @Override
        public Money releasedOn(Connection unitOfWork, UUID instalment, Money zero) {
            Money released = zero;
            for (BnplRefund refund : refunds.rows.values()) {
                for (BnplRefund.Adjustment adjustment : refund.adjustments()) {
                    if (adjustment.kind() == RefundAllocation.Cancellation.Kind.BILLED_UNPAID
                            && adjustment.instalment().equals(instalment)) {
                        released = released.plus(adjustment.lateFeeReleased());
                    }
                }
            }
            return released;
        }

        /** {@code bnpl.fee_waiver_room}: least(FEES billed less settled, assessed less approved waivers less released). */
        Money room(BnplFeeAssessment assessment) {
            BnplBilling billing = purchases.billings.get(assessment.instalment());
            Money zero = Money.ofPersisted(0, assessment.amount().currency(), assessment.amount().scale());
            Money unpaid = purchases.billedFees(billing, zero).minus(purchases.paid(billing, "FEES", zero));
            Money waived = zero;
            for (BnplWaiver waiver : waiversOf(UOW, assessment.id())) {
                if (waiver.status() == BnplCorrectionStatus.APPROVED) {
                    waived = waived.plus(waiver.amount());
                }
            }
            Money never = assessment.amount().minus(waived).minus(releasedOn(UOW, assessment.instalment(), zero));
            return unpaid.compareTo(never) <= 0 ? unpaid : never;
        }

        @Override
        public Instant insertWaiver(Connection unitOfWork, NewWaiver waiver) {
            step("waiver insert");
            BnplObligation obligation = purchases.obligations.get(waiver.obligation().value());
            if (!active(waiver.obligation())) {
                throw new BnplStorageException("a BNPL waiver is proposed only on an ACTIVE obligation: P0001");
            }
            BnplAgreement inForce = purchases.agreements.get(obligation.agreement().value());
            if (!waiver.agreement().equals(inForce.id()) || waiver.agreementVersion() != inForce.version()) {
                throw new BnplStorageException("a BNPL waiver names its obligation's agreement version in force: P0001");
            }
            BnplFeeAssessment assessment = assessment(unitOfWork, waiver.obligation(), waiver.assessment())
                    .filter(row -> row.instalment().equals(waiver.instalment()))
                    .orElseThrow(() -> new BnplStorageException("bnpl_waiver_assessment_fk: SQLSTATE 23503"));
            if (!waiverRoomDropped && waiver.amount().compareTo(room(assessment)) > 0) {
                throw new BnplStorageException("a BNPL waiver is bounded by what its fee still has due and was never"
                        + " forgiven: P0001");
            }
            if (waiversOf(unitOfWork, waiver.assessment()).stream()
                    .anyMatch(row -> row.status() == BnplCorrectionStatus.PROPOSED)) {
                throw new BnplStorageException("a BNPL waiver of this fee already awaits a decision: P0001");
            }
            waiverRows.put(waiver.id(), new BnplWaiver(waiver.id(), waiver.obligation(), waiver.kind(),
                    waiver.assessment(), waiver.instalment(), waiver.amount(), waiver.agreement(),
                    waiver.agreementVersion(), BnplCorrectionStatus.PROPOSED, waiver.proposer().id(), now, waiver.reason(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            return now;
        }

        @Override
        public Optional<BnplWaiver> waiver(Connection unitOfWork, BnplObligationId obligation, UUID waiver) {
            log.add("waiver read");
            return Optional.ofNullable(waiverRows.get(waiver)).filter(row -> row.obligation().equals(obligation));
        }

        @Override
        public boolean decideWaiver(Connection unitOfWork, WaiverDecision decision) {
            step("waiver " + decision.outcome());
            BnplWaiver row = waiverRows.get(decision.waiver());
            if (row == null || !row.proposedBy().equals(decision.proposedBy())) {
                throw new BnplStorageException("bnpl_waiver_decision_waiver_fk: SQLSTATE 23503");
            }
            if (row.status() != BnplCorrectionStatus.PROPOSED) {
                return false;
            }
            if (!fourEyesCheckDropped && decision.outcome() == BnplCorrectionStatus.APPROVED
                    && decision.decider().id().equals(decision.proposedBy())) {
                throw new BnplStorageException("bnpl_waiver_decision_four_eyes: SQLSTATE 23514");
            }
            if (decision.outcome() == BnplCorrectionStatus.APPROVED) {
                if (!active(row.obligation())) {
                    throw new BnplStorageException("a BNPL waiver is approved only on an ACTIVE obligation: P0001");
                }
                BnplFeeAssessment assessment = assessment(unitOfWork, row.obligation(), row.assessment()).orElseThrow();
                if (!waiverRoomDropped && row.amount().compareTo(room(assessment)) > 0) {
                    throw new BnplStorageException("a BNPL waiver is bounded by what its fee still has due and was never"
                            + " forgiven: P0001");
                }
            }
            decisionProposers.put(row.id(), decision.proposedBy());
            waiverRows.put(row.id(), new BnplWaiver(row.id(), row.obligation(), row.kind(), row.assessment(),
                    row.instalment(), row.amount(), row.agreement(), row.agreementVersion(), decision.outcome(),
                    row.proposedBy(), row.proposedAt(), row.proposalReason(), Optional.of(decision.decider().id()),
                    Optional.of(now), Optional.of(decision.reason()), decision.entry()));
            return true;
        }

        @Override
        public List<BnplWaiver> waiversOfObligation(Connection unitOfWork, BnplObligationId obligation, int limit) {
            List<BnplWaiver> rows = new ArrayList<>(waiverRows.values().stream()
                    .filter(row -> row.obligation().equals(obligation)).toList());
            java.util.Collections.reverse(rows);
            return rows.stream().limit(limit).toList();
        }
    }


    private static <K, V> void reset(Map<K, V> into, Map<K, V> from) {
        into.clear();
        into.putAll(from);
    }
}
