package com.finapp.merchant;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory stores for the merchant BNPL services' hermetic tests (`P12-TSK-006`). Each fake holds the SAME conditional
 * rules its JDBC store's statements hold - the one-live rule, the conditional edges with the approval's
 * {@code proposed_by <> ?}, the primary key on the pin - so a domain check removed is caught here even before `V010`'s
 * ranks; the every-writer ranks themselves are {@code MerchantBnplEnablementDatabaseTest}'s. No locks: one thread.
 */
final class BnplFakes {

    private BnplFakes() {}

    /** A unit of work no fake reads: every call on it fails, so a store that leaks to JDBC is loud. */
    static final Connection CONNECTION = (Connection) java.lang.reflect.Proxy.newProxyInstance(
            BnplFakes.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("toString")) {
                    return "BnplFakes.CONNECTION";
                }
                throw new UnsupportedOperationException("the in-memory stores issue no SQL: " + method.getName());
            });

    /** The {@link BnplEnablementStore}'s statements, in memory. */
    static final class Enablements implements BnplEnablementStore<Connection> {

        final Map<BnplEnablementId, BnplEnablement> rows = new LinkedHashMap<>();
        private final Clock clock;

        Enablements(Clock clock) {
            this.clock = clock;
        }

        @Override
        public BnplEnablement insertProposal(
                Connection unitOfWork, BnplEnablementId id, MerchantId merchant, String proposedBy, String reason) {
            if (live(unitOfWork, merchant).isPresent()) {
                throw new LivePresent();
            }
            BnplEnablement row = new BnplEnablement(id, merchant, BnplEnablementStatus.PROPOSED, proposedBy,
                    clock.instant(), reason, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
            rows.put(id, row);
            return row;
        }

        @Override
        public Optional<BnplEnablement> find(Connection unitOfWork, MerchantId merchant, BnplEnablementId id) {
            return Optional.ofNullable(rows.get(id)).filter(row -> row.merchantId().equals(merchant));
        }

        @Override
        public Optional<BnplEnablement> lock(Connection unitOfWork, MerchantId merchant, BnplEnablementId id) {
            return find(unitOfWork, merchant, id);
        }

        @Override
        public Optional<BnplEnablement> decide(
                Connection unitOfWork, BnplEnablementId id, BnplEnablementStatus to, String decidedBy, String reason) {
            BnplEnablement row = rows.get(id);
            if (row == null || row.status() != BnplEnablementStatus.PROPOSED
                    || (to == BnplEnablementStatus.ACTIVE && row.proposedBy().equals(decidedBy))) {
                return Optional.empty();
            }
            BnplEnablement decided = new BnplEnablement(id, row.merchantId(), to, row.proposedBy(), row.proposedAt(),
                    row.proposalReason(), Optional.of(decidedBy), Optional.of(clock.instant()), Optional.of(reason),
                    Optional.empty(), Optional.empty(), Optional.empty());
            rows.put(id, decided);
            return Optional.of(decided);
        }

        @Override
        public Optional<BnplEnablement> withdraw(
                Connection unitOfWork, BnplEnablementId id, String withdrawnBy, String reason) {
            BnplEnablement row = rows.get(id);
            if (row == null || row.status() != BnplEnablementStatus.ACTIVE) {
                return Optional.empty();
            }
            BnplEnablement withdrawn = new BnplEnablement(id, row.merchantId(), BnplEnablementStatus.WITHDRAWN,
                    row.proposedBy(), row.proposedAt(), row.proposalReason(), row.decidedBy(), row.decidedAt(),
                    row.decisionReason(), Optional.of(withdrawnBy), Optional.of(clock.instant()), Optional.of(reason));
            rows.put(id, withdrawn);
            return Optional.of(withdrawn);
        }

        @Override
        public Optional<BnplEnablement> activeForShare(Connection unitOfWork, MerchantId merchant) {
            return rows.values().stream()
                    .filter(row -> row.merchantId().equals(merchant) && row.status() == BnplEnablementStatus.ACTIVE)
                    .findFirst();
        }

        @Override
        public Optional<BnplEnablement> live(Connection unitOfWork, MerchantId merchant) {
            return rows.values().stream()
                    .filter(row -> row.merchantId().equals(merchant) && row.status().isLive())
                    .findFirst();
        }
    }

    /** Merchants by id; only the reads the BNPL services make. */
    static final class Merchants implements MerchantStore<Connection> {

        final Map<MerchantId, Merchant> rows = new HashMap<>();

        @Override
        public void insert(Connection unitOfWork, Merchant merchant) {
            rows.put(merchant.id(), merchant);
        }

        @Override
        public Optional<Merchant> findById(Connection unitOfWork, MerchantId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Merchant> findByIdForUpdate(Connection unitOfWork, MerchantId id) {
            return findById(unitOfWork, id);
        }

        @Override
        public Optional<Merchant> findByIdForShare(Connection unitOfWork, MerchantId id) {
            return findById(unitOfWork, id);
        }

        @Override
        public boolean transition(Connection unitOfWork, Merchant before, Merchant transitioned) {
            rows.put(transitioned.id(), transitioned);
            return true;
        }
    }

    /** The BNPL assignment and the pin; the pin's primary key held. */
    static final class Pricing implements BnplPricingStore<Connection> {

        final Map<String, FeeScheduleId> assignments = new HashMap<>();
        final List<String> history = new ArrayList<>();
        final Map<UUID, BnplFeePin> pins = new LinkedHashMap<>();

        @Override
        public Optional<FeeScheduleId> findAssignment(
                Connection unitOfWork, MerchantId merchant, MerchantBnplProduct product) {
            return Optional.ofNullable(assignments.get(merchant + "|" + product));
        }

        @Override
        public void assign(Connection unitOfWork, MerchantId merchant, MerchantBnplProduct product,
                Optional<FeeScheduleId> previous, FeeScheduleId schedule, String reason, String actorId,
                String actorType, Instant at) {
            assignments.put(merchant + "|" + product, schedule);
            history.add(previous.map(Object::toString).orElse("none") + "->" + schedule);
        }

        @Override
        public boolean insertPin(Connection unitOfWork, BnplFeePin pin) {
            return pins.putIfAbsent(pin.purchaseRef(), pin) == null;
        }

        @Override
        public Optional<BnplFeePin> findPin(Connection unitOfWork, UUID purchaseRef) {
            return Optional.ofNullable(pins.get(purchaseRef));
        }
    }

    /** Schedules and immutable versions; the resolution rule as {@link JdbcFeeScheduleStore}'s SQL states it. */
    static final class Schedules implements FeeScheduleStore<Connection> {

        final Map<FeeScheduleId, FeeSchedule> schedules = new LinkedHashMap<>();
        final Map<FeeScheduleVersionId, FeeScheduleVersion> versions = new LinkedHashMap<>();

        @Override
        public void insertSchedule(Connection unitOfWork, FeeSchedule schedule) {
            schedules.put(schedule.id(), schedule);
        }

        @Override
        public Optional<FeeSchedule> findSchedule(Connection unitOfWork, FeeScheduleId id) {
            return Optional.ofNullable(schedules.get(id));
        }

        @Override
        public List<FeeSchedule> listSchedules(Connection unitOfWork) {
            return List.copyOf(schedules.values());
        }

        @Override
        public boolean insertVersionIfNumberIsFree(Connection unitOfWork, FeeScheduleVersion version) {
            versions.put(version.id(), version);
            return true;
        }

        @Override
        public int nextVersionNumber(Connection unitOfWork, FeeScheduleId scheduleId) {
            return (int) versions.values().stream().filter(v -> v.scheduleId().equals(scheduleId)).count() + 1;
        }

        @Override
        public Optional<FeeScheduleVersion> findVersion(Connection unitOfWork, FeeScheduleVersionId id) {
            return Optional.ofNullable(versions.get(id));
        }

        @Override
        public List<FeeScheduleVersion> listVersions(Connection unitOfWork, FeeScheduleId scheduleId) {
            return versions.values().stream().filter(v -> v.scheduleId().equals(scheduleId))
                    .sorted(FeeScheduleVersion.EFFECTIVE_ORDER).toList();
        }

        @Override
        public Optional<FeeScheduleVersion> findEffectiveVersion(
                Connection unitOfWork, FeeScheduleId scheduleId, Instant instant) {
            return versions.values().stream()
                    .filter(v -> v.scheduleId().equals(scheduleId) && !v.effectiveFrom().isAfter(instant))
                    .max(Comparator.comparing(FeeScheduleVersion::effectiveFrom)
                            .thenComparingInt(FeeScheduleVersion::version));
        }

        @Override
        public Optional<FeeScheduleId> findAssignment(Connection unitOfWork, MerchantId merchantId) {
            return Optional.empty();
        }

        @Override
        public void assign(Connection unitOfWork, MerchantId merchantId, Optional<FeeScheduleId> previous,
                FeeScheduleId scheduleId, String reason, Instant at) {
            throw new UnsupportedOperationException("the card assignment is not BNPL's");
        }

        @Override
        public Optional<FeeScheduleVersion> findEffectiveVersionFor(
                Connection unitOfWork, MerchantId merchantId, Instant instant) {
            return Optional.empty();
        }
    }

    /** Every record appended, in order. */
    static final class Audit implements AuditWriter<Connection> {

        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void append(Connection unitOfWork, AuditRecord record) {
            records.add(record);
        }
    }
}
