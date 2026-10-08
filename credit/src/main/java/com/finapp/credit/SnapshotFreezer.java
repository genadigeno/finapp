package com.finapp.credit;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Freezes a decision's inputs (`P10-TSK-008`; ADR-0087 section 2, PHASE_10_PLAN.md section 12.2; {@code INV-CRD-07},
 * {@code INV-CRD-08}, {@code INV-CRD-06}, {@code INV-CRD-10}, {@code INV-CRD-12}, {@code INV-CRD-04}).
 *
 * <h2>Inside the caller's transaction, under its lock</h2>
 *
 * <p>The progress step (`P10-TSK-015`) calls {@link #freeze} holding the decision request's row lock (element (2)); the
 * freeze then takes the request's data requests {@code FOR UPDATE} by id (element (4)) - the rows a recorder meets too,
 * so an answer recorded during the freeze is either in it or belongs to no snapshot.
 *
 * <h2>Every required source, judged on the database's clock</h2>
 *
 * <ul>
 *   <li>its latest data request {@code RECEIVED}, the record fresh ({@code retrieved_at >= transaction_timestamp() -
 *       max_age}, the pinned policy's maximum age, handed in) - its attributes enter, each with its record's provenance;
 *   <li>the record stale - collection re-opens for that kind under a new reference in this same transaction (the
 *       lifecycle's one backward edge), and nothing is frozen: a stale record never decides;
 *   <li>{@code UNAVAILABLE} past its deadline - its attributes enter {@code ABSENT}, and {@code SOURCE_UNAVAILABLE}
 *       names the kind ({@code INV-CRD-10});
 *   <li>still in flight - {@link Freeze.NotReady}; consent withdrawn - {@link Freeze.ConsentWithdrawn}, for the request
 *       to act on.
 * </ul>
 *
 * <p>The rest comes from the request and the ports: the declared figures, the party facts ({@code ABSENT} while the
 * platform holds none - unresolved question #13), the risk seam's signal as given ({@code INV-CRD-04}), the reserved
 * exposure and the platform's outstanding credit (`P10-TSK-010`), each with its port's version. A money figure in another currency is {@code ABSENT} with {@code CURRENCY_NOT_SUPPORTED}, never converted.
 * Every code is held exactly once - a missing one is a defect here, never a default downstream.
 *
 * <p>Born once per {@code (request, sequence)}: {@code INSERT ... ON CONFLICT DO NOTHING}, then read - ten freezers,
 * one snapshot, every one of them answered with it.
 */
@RequiredArgsConstructor
public final class SnapshotFreezer {

    static final String FREEZER_PORT = "snapshot-freezer";
    static final int FREEZER_VERSION = 1;

    @NonNull private final DecisionSnapshotStore store;
    @NonNull private final CreditDataCollection collection;
    @NonNull private final CreditPartyStanding<Connection> partyStanding;
    @NonNull private final CreditRiskSignal<Connection> riskSignal;
    @NonNull private final ReservedExposure<Connection> reservedExposure;
    @NonNull private final PlatformCreditExposure<Connection> platformExposure;
    @NonNull private final IdGenerator ids;

    /** What to freeze: the request, its pinned versions and the maximum age of each source kind the policy reads. */
    public record FreezeInput(
            UUID decisionRequest,
            UUID party,
            CreditProduct product,
            Money requestedAmount,
            Optional<Integer> termMonths,
            Optional<Money> declaredMonthlyIncome,
            Optional<Money> declaredMonthlyExpenditure,
            PinnedVersions versions,
            Map<CreditSourceKind, Duration> maxAgeByKind,
            int sequence) {
        public FreezeInput {
            Objects.requireNonNull(decisionRequest, "decisionRequest");
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(requestedAmount, "requestedAmount");
            Objects.requireNonNull(termMonths, "termMonths");
            Objects.requireNonNull(declaredMonthlyIncome, "declaredMonthlyIncome");
            Objects.requireNonNull(declaredMonthlyExpenditure, "declaredMonthlyExpenditure");
            Objects.requireNonNull(versions, "versions");
            maxAgeByKind = Map.copyOf(Objects.requireNonNull(maxAgeByKind, "maxAgeByKind"));
            for (Optional<Money> declared : List.of(declaredMonthlyIncome, declaredMonthlyExpenditure)) {
                declared.ifPresent(money -> {
                    if (!money.currency().equals(product.currency())) {
                        throw new IllegalArgumentException("a declared figure is in the product's currency");
                    }
                });
            }
            maxAgeByKind.values().forEach(age -> {
                if (age.isNegative() || age.isZero()) {
                    throw new IllegalArgumentException("a maximum age is positive");
                }
            });
            if (sequence < 1) {
                throw new IllegalArgumentException("a sequence counts from 1");
            }
        }
    }

    /** What a freeze did. */
    public sealed interface Freeze
            permits Freeze.Frozen, Freeze.Recollecting, Freeze.NotReady, Freeze.ConsentWithdrawn {

        /** The snapshot - born here, or the one another freezer already froze ({@code replayed}). */
        record Frozen(DecisionSnapshot snapshot, boolean replayed) implements Freeze {}

        /** Records found stale: collection re-opened for each kind, nothing frozen. */
        record Recollecting(Map<CreditSourceKind, CreditDataCollection.Opened> reopened) implements Freeze {}

        /** Some required source has not answered yet, or never was asked. */
        record NotReady(Set<CreditSourceKind> kinds) implements Freeze {}

        /** A required source's consent was withdrawn. */
        record ConsentWithdrawn(Set<CreditSourceKind> kinds) implements Freeze {}
    }

    /** Freezes {@code input} in the caller's unit of work. */
    public Freeze freeze(Connection uow, FreezeInput input, CorrelationId correlation) {
        Objects.requireNonNull(uow, "uow");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(correlation, "correlation");
        Optional<DecisionSnapshotStore.StoredSnapshot> already = store.snapshotOf(uow, input.decisionRequest(), input.sequence());
        if (already.isPresent()) {
            return new Freeze.Frozen(read(already.get()), true);
        }
        List<DecisionSnapshotStore.DataRequestState> requests = store.lockDataRequestsOf(uow, input.decisionRequest());

        Map<CreditSourceKind, DecisionSnapshotStore.StoredRecord> fresh = new EnumMap<>(CreditSourceKind.class);
        Map<CreditSourceKind, CreditDataRequestId> unavailable = new EnumMap<>(CreditSourceKind.class);
        Set<CreditSourceKind> stale = EnumSet.noneOf(CreditSourceKind.class);
        Set<CreditSourceKind> notReady = EnumSet.noneOf(CreditSourceKind.class);
        Set<CreditSourceKind> withdrawn = EnumSet.noneOf(CreditSourceKind.class);
        for (Map.Entry<CreditSourceKind, Duration> required : input.maxAgeByKind().entrySet()) {
            CreditSourceKind kind = required.getKey();
            Optional<DecisionSnapshotStore.DataRequestState> latest = requests.stream()
                    .filter(request -> request.kind() == kind)
                    .max(Comparator.comparing(DecisionSnapshotStore.DataRequestState::requestedAt)
                            .thenComparing(request -> request.id().value()));
            if (latest.isEmpty()) {
                notReady.add(kind);
                continue;
            }
            DecisionSnapshotStore.DataRequestState state = latest.get();
            switch (state.status()) {
                case RECEIVED -> {
                    DecisionSnapshotStore.StoredRecord record = store.recordOf(uow, state.id(), required.getValue())
                            .orElseThrow(() -> new IllegalStateException("a RECEIVED data request without its record"));
                    if (record.fresh()) {
                        fresh.put(kind, record);
                    } else {
                        stale.add(kind);
                    }
                }
                case UNAVAILABLE -> {
                    if (state.pastDeadline()) {
                        unavailable.put(kind, state.id());
                    } else {
                        notReady.add(kind);
                    }
                }
                case CONSENT_WITHDRAWN -> withdrawn.add(kind);
                case REQUESTED -> notReady.add(kind);
            }
        }
        if (!withdrawn.isEmpty()) {
            return new Freeze.ConsentWithdrawn(withdrawn);
        }
        if (!notReady.isEmpty()) {
            return new Freeze.NotReady(notReady);
        }
        if (!stale.isEmpty()) {
            Map<CreditSourceKind, CreditDataCollection.Opened> reopened = new EnumMap<>(CreditSourceKind.class);
            for (CreditSourceKind kind : stale) {
                reopened.put(kind, collection.openWithin(uow, new CreditDataCollection.Opening(
                        input.decisionRequest(), input.party(), input.product(), kind), correlation));
            }
            return new Freeze.Recollecting(reopened);
        }

        SnapshotContent content = new SnapshotContent(input.decisionRequest(), input.party(), input.product(),
                input.requestedAmount(), input.termMonths(), input.versions(),
                attributes(uow, input, fresh, unavailable));
        String canonical = CanonicalSnapshot.render(content);
        byte[] sha256 = CanonicalSnapshot.sha256(canonical);
        DecisionSnapshotId id = DecisionSnapshotId.next(ids);
        boolean born = store.insertSnapshot(uow, id, input.decisionRequest(), input.sequence(), CanonicalSnapshot.FORMAT,
                canonical, sha256, input.versions());
        DecisionSnapshotStore.StoredSnapshot stored = store.snapshotOf(uow, input.decisionRequest(), input.sequence())
                .orElseThrow(() -> new IllegalStateException("a snapshot neither born nor found - the caller is not READ COMMITTED"));
        return new Freeze.Frozen(read(stored), !born);
    }

    /**
     * The successor of {@code previous} (`P10-TSK-016`; PHASE_10_PLAN.md section 12.7): the same request, versions and
     * records, with the party's reserved exposure as it now stands under the deciding transaction's profile lock -
     * frozen as the next sequence, born once ({@code UNIQUE (decision_request_id, sequence)}). Never a re-collection: the
     * records are the ones the request was evaluated on; only the exposure moved.
     */
    public Freeze.Frozen successor(Connection uow, DecisionSnapshot previous, Money reserved) {
        Objects.requireNonNull(uow, "uow");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(reserved, "reserved");
        SnapshotContent prior = previous.content();
        int sequence = previous.sequence() + 1;
        Optional<DecisionSnapshotStore.StoredSnapshot> already = store.snapshotOf(uow, prior.decisionRequest(), sequence);
        if (already.isPresent()) {
            return new Freeze.Frozen(read(already.get()), true);
        }
        List<CreditAttribute> attributes = new ArrayList<>();
        for (CreditAttribute attribute : prior.attributes()) {
            attributes.add(attribute.code() == CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE
                    ? new CreditAttribute(attribute.code(), new AttributeValue.MoneyValue(reserved),
                            new AttributeProvenance.Port("reserved-exposure", reservedExposure.version()))
                    : attribute);
        }
        SnapshotContent content = new SnapshotContent(prior.decisionRequest(), prior.party(), prior.product(),
                prior.requestedAmount(), prior.termMonths(), prior.versions(), attributes);
        String canonical = CanonicalSnapshot.render(content);
        byte[] sha256 = CanonicalSnapshot.sha256(canonical);
        boolean born = store.insertSnapshot(uow, DecisionSnapshotId.next(ids), prior.decisionRequest(), sequence,
                CanonicalSnapshot.FORMAT, canonical, sha256, prior.versions());
        DecisionSnapshotStore.StoredSnapshot stored = store.snapshotOf(uow, prior.decisionRequest(), sequence)
                .orElseThrow(() -> new IllegalStateException("a successor neither born nor found"));
        return new Freeze.Frozen(read(stored), !born);
    }

    /** The request's latest snapshot - the one its latest evaluation was made on. */
    public Optional<DecisionSnapshot> latest(Connection uow, UUID decisionRequest) {
        return store.latestSnapshotOf(uow, decisionRequest).map(this::read);
    }

    /** The party's reserved exposure in {@code currency} as the freezer's seam reads it - the deciding step's re-read. */
    public Money reservedFor(Connection uow, UUID party, com.finapp.sharedkernel.money.CurrencyCode currency) {
        return reservedExposure.reservedFor(uow, party, currency);
    }

    private List<CreditAttribute> attributes(
            Connection uow,
            FreezeInput input,
            Map<CreditSourceKind, DecisionSnapshotStore.StoredRecord> fresh,
            Map<CreditSourceKind, CreditDataRequestId> unavailable) {
        List<CreditAttribute> attributes = new ArrayList<>();
        Set<CreditSourceKind> foreign = EnumSet.noneOf(CreditSourceKind.class);
        for (CreditSourceKind kind : CreditSourceKind.values()) {
            Set<CreditAttributeCode> codes = codesOf(kind);
            if (!input.maxAgeByKind().containsKey(kind)) {
                codes.forEach(code -> attributes.add(absent(code, new AttributeProvenance.NotRead(kind))));
            } else if (unavailable.containsKey(kind)) {
                AttributeProvenance why = new AttributeProvenance.Unavailable(kind, unavailable.get(kind));
                codes.forEach(code -> attributes.add(absent(code, why)));
            } else {
                DecisionSnapshotStore.StoredRecord record = fresh.get(kind);
                AttributeProvenance from = new AttributeProvenance.Record(
                        record.id(), kind, record.providerCode(), record.normaliserVersion());
                Map<CreditAttributeCode, AttributeValue> stored = new EnumMap<>(CreditAttributeCode.class);
                for (DecisionSnapshotStore.StoredAttribute attribute : record.attributes()) {
                    if (attribute.code() == CreditAttributeCode.CURRENCY_NOT_SUPPORTED
                            && attribute.value() instanceof AttributeValue.CodeValue marker) {
                        foreign.addAll(SourceKindsMarker.kindsOf(marker));
                    } else if (codes.contains(attribute.code())) {
                        stored.put(attribute.code(), attribute.value());
                    }
                }
                for (CreditAttributeCode code : codes) {
                    AttributeValue value = stored.getOrDefault(code, new AttributeValue.Absent());
                    if (value instanceof AttributeValue.MoneyValue money
                            && !money.value().currency().equals(input.product().currency())) {
                        // Never converted (INV-CRD-12): absent, and the marker says why.
                        value = new AttributeValue.Absent();
                        foreign.add(kind);
                    }
                    attributes.add(new CreditAttribute(code, value, from));
                }
            }
        }
        AttributeProvenance freezer = new AttributeProvenance.Port(FREEZER_PORT, FREEZER_VERSION);
        attributes.add(marker(CreditAttributeCode.SOURCE_UNAVAILABLE, unavailable.keySet(), freezer));
        attributes.add(marker(CreditAttributeCode.CURRENCY_NOT_SUPPORTED, foreign, freezer));

        AttributeProvenance declared = new AttributeProvenance.Declared();
        attributes.add(money(CreditAttributeCode.DECLARED_MONTHLY_INCOME, input.declaredMonthlyIncome(), declared));
        attributes.add(money(CreditAttributeCode.DECLARED_MONTHLY_EXPENDITURE, input.declaredMonthlyExpenditure(), declared));

        CreditPartyStanding.PartyFacts facts = partyStanding.facts(uow, input.party());
        AttributeProvenance party = new AttributeProvenance.Port("party-facts", facts.version());
        attributes.add(new CreditAttribute(CreditAttributeCode.PARTY_AGE_YEARS, facts.ageYears()
                .<AttributeValue>map(age -> new AttributeValue.IntegerValue(age)).orElse(new AttributeValue.Absent()), party));
        attributes.add(new CreditAttribute(CreditAttributeCode.PARTY_RESIDENCY_COUNTRY, facts.residenceCountry()
                .<AttributeValue>map(country -> new AttributeValue.CodeValue(country.code()))
                .orElse(new AttributeValue.Absent()), party));

        CreditRiskSignal.RiskSignal signal = riskSignal.signal(uow, input.party());
        attributes.add(new CreditAttribute(CreditAttributeCode.RISK_SIGNAL, new AttributeValue.CodeValue(signal.code()),
                new AttributeProvenance.Port("risk-signal", signal.seamVersion())));

        Money reserved = reservedExposure.reservedFor(uow, input.party(), input.product().currency());
        attributes.add(new CreditAttribute(CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE,
                new AttributeValue.MoneyValue(reserved), new AttributeProvenance.Port("reserved-exposure",
                        reservedExposure.version())));

        Money outstanding = platformExposure.outstandingFor(uow, input.party(), input.product().currency());
        attributes.add(new CreditAttribute(CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT,
                new AttributeValue.MoneyValue(outstanding), new AttributeProvenance.Port("platform-exposure",
                        platformExposure.version())));

        Set<CreditAttributeCode> held = EnumSet.noneOf(CreditAttributeCode.class);
        attributes.forEach(attribute -> held.add(attribute.code()));
        if (!held.equals(EnumSet.allOf(CreditAttributeCode.class))) {
            throw new IllegalStateException("a snapshot holds every attribute code exactly once");
        }
        return attributes;
    }

    private DecisionSnapshot read(DecisionSnapshotStore.StoredSnapshot stored) {
        return new DecisionSnapshot(stored.id(), stored.sequence(), stored.format(), stored.canonical(), stored.sha256(),
                stored.frozenAt(), CanonicalSnapshot.parse(stored.canonical()));
    }

    /** The attribute codes a source kind's answer normalises to. */
    static Set<CreditAttributeCode> codesOf(CreditSourceKind kind) {
        return switch (kind) {
            case BUREAU -> EnumSet.copyOf(CreditBureau.ATTRIBUTES);
            case FINANCIAL_DATA -> EnumSet.copyOf(FinancialDataProvider.ATTRIBUTES);
        };
    }

    private static CreditAttribute absent(CreditAttributeCode code, AttributeProvenance provenance) {
        return new CreditAttribute(code, new AttributeValue.Absent(), provenance);
    }

    private static CreditAttribute marker(CreditAttributeCode code, Set<CreditSourceKind> kinds, AttributeProvenance from) {
        return kinds.isEmpty() ? absent(code, from) : new CreditAttribute(code, SourceKindsMarker.of(kinds), from);
    }

    private static CreditAttribute money(CreditAttributeCode code, Optional<Money> value, AttributeProvenance from) {
        return new CreditAttribute(code, value.<AttributeValue>map(AttributeValue.MoneyValue::new)
                .orElse(new AttributeValue.Absent()), from);
    }
}
