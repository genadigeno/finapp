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
        int eventCount = events.size();
        int payloadCount = payloads.size();
        int auditCount = audited.size();
        try {
            return work.get();
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

        @Override
        public Money returnedFee(Connection unitOfWork, BnplPurchaseId purchaseId, Money refundedBefore,
                Money refunded) {
            throw new UnsupportedOperationException("the refund's (P12-TSK-021)");
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
        final Map<UUID, Billing> billings = new LinkedHashMap<>();
        final Map<UUID, Repayment> repayments = new LinkedHashMap<>();
        final List<String> history = new ArrayList<>();

        /** Everything, for the unit of work's rollback. */
        record State(Map<UUID, BnplPurchase> rows, Map<UUID, MerchantFinancing> financings,
                Map<UUID, BnplObligation> obligations, Map<UUID, BnplAgreement> agreements,
                Map<UUID, BnplAcceptanceEvidence> acceptances, Map<UUID, StoredSchedule> schedules,
                Map<UUID, Billing> billings, Map<UUID, Repayment> repayments, List<String> history) {}

        State state() {
            return new State(new LinkedHashMap<>(rows), new LinkedHashMap<>(financings), new LinkedHashMap<>(obligations),
                    new LinkedHashMap<>(agreements), new LinkedHashMap<>(acceptances), new LinkedHashMap<>(schedules),
                    new LinkedHashMap<>(billings), new LinkedHashMap<>(repayments), new ArrayList<>(history));
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
        }

        @Override
        public void insertBilling(Connection unitOfWork, Billing billing) {
            step("billing insert");
            if (billings.containsKey(billing.instalment())) {
                throw new BnplStorageException("bnpl_billing_instalment_unique: SQLSTATE 23505");
            }
            BnplObligation obligation = obligations.get(billing.obligation().value());
            if (billing.valueDate().isBefore(obligation.purchaseDate())) {
                throw new BnplStorageException("a BNPL billing is value-dated on or after its floor: P0001");
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
            repayments.put(repayment.id(), repayment);
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
            return Optional.ofNullable(schedules.get(obligation.value())).filter(found -> found.version() == version);
        }

        @Override
        public List<UUID> billedInstalments(Connection unitOfWork, BnplObligationId obligation) {
            return billings.values().stream().filter(row -> row.obligation().equals(obligation))
                    .map(Billing::instalment).toList();
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
                Money amount, java.time.LocalDate valueDate) {}

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
        /** The projection rows of every L8 call, in order. */
        final List<java.util.Set<com.finapp.ledger.LedgerAccountId>> projections = new ArrayList<>();

        /** Everything, for the unit of work's rollback. */
        record State(Map<com.finapp.ledger.LedgerAccountId, com.finapp.ledger.LedgerAccount> accounts, List<Line> lines,
                List<String> keys) {}

        State state() {
            return new State(new LinkedHashMap<>(accounts), new ArrayList<>(lines), new ArrayList<>(keys));
        }

        void restore(State state) {
            reset(accounts, state.accounts());
            lines.clear();
            lines.addAll(state.lines());
            keys.clear();
            keys.addAll(state.keys());
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
                lines.add(new Line(key, line.account(), line.direction(), line.amount(), command.valueDate()));
            }
            return com.finapp.ledger.JournalEntryId.of(IDS.next());
        }

        @Override
        public Money balanceOf(Connection unitOfWork, com.finapp.ledger.LedgerAccount account) {
            return balance(account.id(), account.normalBalance() == com.finapp.ledger.NormalBalance.DEBIT);
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

    /** Lending capital: the recognised capital less the book's BNPL principal, judged under L6. */
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

    private static <K, V> void reset(Map<K, V> into, Map<K, V> from) {
        into.clear();
        into.putAll(from);
    }
}
