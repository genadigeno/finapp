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
            events.subList(eventCount, events.size()).clear();
            payloads.subList(payloadCount, payloads.size()).clear();
            audited.subList(auditCount, audited.size()).clear();
            throw refused;
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

        @Override
        public Lock lockForPurchase(Connection unitOfWork, UUID sessionRef) {
            throw new UnsupportedOperationException("the purchase's (P12-TSK-013)");
        }

        @Override
        public Completion completeByFinancing(Connection unitOfWork, UUID sessionRef, BnplPurchaseId purchase,
                com.finapp.ledger.JournalEntryId financingEntry, com.finapp.sharedkernel.correlation.Correlation correlation) {
            throw new UnsupportedOperationException("the purchase's (P12-TSK-013)");
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

        @Override
        public Optional<com.finapp.ledger.LedgerAccountId> payableAccount(Connection unitOfWork, UUID merchantRef,
                com.finapp.sharedkernel.money.CurrencyCode currency) {
            throw new UnsupportedOperationException("the purchase's");
        }

        @Override
        public MerchantFeeSplit pinFee(Connection unitOfWork, UUID merchantRef, BnplProduct product,
                BnplPurchaseId purchaseId, Money gross) {
            throw new UnsupportedOperationException("the purchase's (P12-TSK-013)");
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
}
