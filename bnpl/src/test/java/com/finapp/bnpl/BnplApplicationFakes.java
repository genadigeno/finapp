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
        /** A failure injected at the named operation - "insert", "transition", "appendEvent". */
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

        /** Moves a row as another writer would (the origination step, `P12-TSK-011`). */
        void put(BnplApplication row, BnplApplicationStatus to) {
            boolean terminal = to != BnplApplicationStatus.AWAITING_DECISION;
            rows.put(row.id().value(), new BnplApplication(row.id(), row.party(), row.product(), row.plan(), to,
                    row.checkoutSession(), row.merchant(), row.decisionRequest(), row.termsVersionId(),
                    row.termsVersion(),
                    to == BnplApplicationStatus.CLOSED_UNDECIDED
                            ? Optional.of(BnplApplicationClosureReason.SESSION_CLOSED) : Optional.empty(),
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
                        default -> throw new UnsupportedOperationException(method.getName() + ": not the pin's read");
                    });
        }
    }
}
