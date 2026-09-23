package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The operator's fee-schedule commands and the resolution every future capture will call
 * (`P6-TSK-004`, ADR-0050).
 *
 * <h2>Why these commands carry no idempotency key, stated rather than omitted</h2>
 *
 * <p>Every keyed command on this platform is keyed because a duplicate produces a
 * <strong>duplicate effect</strong>: a second merchant, a second payable account, a second
 * credential. A duplicated version creation produces version {@code N+1} and {@code N+2} with
 * byte-identical content, and the resolution rule picks the later — which prices
 * <em>identically</em>. The harm a key prevents does not exist here, and machinery that
 * prevents nothing still has to be maintained and believed.
 *
 * <p>Assignment converges instead, the way the merchant's standing moves do
 * ({@code MerchantAdministration.move}): if the pointer already names this schedule, the
 * command returns it having written nothing — no history row, no audit record. One act,
 * however many operators asked.
 *
 * <p><strong>If version creation ever gains an effect beyond configuration, this decision has
 * to be revisited.</strong> It is recorded here so that the revisiting is a decision rather
 * than a discovery.
 *
 * <h2>The one contended thing, and why it has no lock</h2>
 *
 * <p>Minting a version number. Ten instances creating a version of one schedule must produce
 * ten distinct numbers — and the natural serialization point, the schedule row, <strong>cannot
 * be locked</strong>: PostgreSQL requires the {@code UPDATE} privilege to take a row lock, and
 * `V004` withholds it from a table nothing may ever update. The immutability and the absence
 * of a lock are one fact stated twice, not an obstacle to work around. The arbiter is the
 * unique index on {@code (fee_schedule_id, version)}; see {@link #mintVersion}.
 */
@RequiredArgsConstructor
public final class FeeSchedules {

    /**
     * How many times a version creation will re-read and retry its number before failing
     * loudly. Generous against a real operator surface; small enough that a defect elsewhere
     * cannot turn this into a spin.
     */
    static final int MAX_MINTING_ATTEMPTS = 16;

    static final String SCHEDULE_TARGET_TYPE = "fee_schedule";
    static final String VERSION_TARGET_TYPE = "fee_schedule_version";
    static final String ASSIGNMENT_TARGET_TYPE = "merchant";

    /** A schedule and its versions, newest-effective first — the operator's read. */
    public record ScheduleDetail(FeeSchedule schedule, List<FeeScheduleVersion> versions) {}

    /**
     * The operator's new-version request, parsed and bounded by the surface.
     *
     * @param effectiveFrom when it starts pricing, or empty for <strong>immediately</strong> —
     *     see {@link FeeScheduleVersion#create} for why empty is the only way to say "now"
     */
    public record NewVersion(
            FeeRate rate,
            Money fixed,
            RoundingPolicy roundingPolicy,
            RefundFeePolicy refundFeePolicy,
            Optional<Instant> effectiveFrom,
            String reason) {}

    @NonNull private final FeeScheduleStore<Connection> schedules;
    @NonNull private final MerchantStore<Connection> merchants;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ----------------------------------------------------------------- commands

    /**
     * Creates a named pricing identity. No reason required: a named container is not yet a
     * price, and the judgement worth reasoning about is the {@link #addVersion version}.
     */
    public FeeSchedule create(Connection unitOfWork, String name, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        FeeSchedule schedule = FeeSchedule.create(ids, name, currency, actor.id(), clock);
        schedules.insertSchedule(unitOfWork, schedule);

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        MerchantAuditAction.FEE_SCHEDULE_CREATED,
                        SCHEDULE_TARGET_TYPE,
                        schedule.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "schedule=" + schedule.id() + ", name=" + schedule.name()
                                        + ", currency=" + schedule.currency())));
        return schedule;
    }

    /**
     * Adds a version, effective forward.
     *
     * <p>The number is minted by {@link #mintVersion}, which has no lock behind it and says
     * why. The reason is required and recorded verbatim
     * ({@code INV-AUD-03}): changing what the platform charges is a commercial judgement, and
     * an unexplained price change is precisely what a reviewer needs to see explained.
     *
     * @throws UnknownFeeScheduleException if no schedule answers to the identifier
     * @throws BackdatedFeeScheduleVersionException if it would take effect in the past
     * @throws FeeCurrencyMismatchException if the fixed part is not in the schedule's currency
     */
    public FeeScheduleVersion addVersion(
            Connection unitOfWork, FeeScheduleId scheduleId, NewVersion request) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(request.reason(), "reason must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        FeeSchedule schedule =
                schedules
                        .findSchedule(unitOfWork, scheduleId)
                        .orElseThrow(UnknownFeeScheduleException::new);

        FeeScheduleVersion version = mintVersion(unitOfWork, schedule, request, actor);

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        MerchantAuditAction.FEE_SCHEDULE_VERSION_CREATED,
                        VERSION_TARGET_TYPE,
                        version.id().value().toString(),
                        // The operator's own words, verbatim (INV-AUD-03).
                        Optional.of(request.reason()),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "schedule=" + scheduleId + ", version=" + version.version()
                                        + ", rate=" + version.rate()
                                        + ", fixed=" + version.fixed()
                                        + ", rounding=" + version.roundingPolicy().policyName()
                                        + ", refundFee=" + version.refundFeePolicy()
                                        + ", effectiveFrom=" + version.effectiveFrom())));
        return version;
    }

    /**
     * Points a merchant at a schedule, reasoned. Converges silently when the merchant is
     * already on it — nothing happened, so nothing is recorded.
     *
     * <p>The merchant row is taken {@code FOR UPDATE} first, which is what lets the first
     * assignment and a later move share one code path without the primary key having to refuse
     * nine concurrent first-assignments.
     *
     * @throws UnknownMerchantException if no merchant answers to the identifier
     * @throws UnknownFeeScheduleException if no schedule does
     * @throws FeeCurrencyMismatchException if the schedule does not price the merchant's
     *     settlement currency
     */
    public FeeScheduleId assign(
            Connection unitOfWork, MerchantId merchantId, FeeScheduleId scheduleId, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        Merchant merchant =
                merchants
                        .findByIdForUpdate(unitOfWork, merchantId)
                        .orElseThrow(UnknownMerchantException::new);
        FeeSchedule schedule =
                schedules
                        .findSchedule(unitOfWork, scheduleId)
                        .orElseThrow(UnknownFeeScheduleException::new);

        // A schedule prices in one currency, and a merchant settles in one. Refused by name
        // here; the composite foreign key holds the version half of the same rule at V004.
        if (!schedule.currency().equals(merchant.settlementCurrency())) {
            throw new FeeCurrencyMismatchException(
                    schedule.currency(), merchant.settlementCurrency());
        }

        // A CLOSED merchant is deliberately NOT refused, and the asymmetry with key issuance
        // is reasoned rather than accidental: a credential is a door, so issuing one into an
        // ended relationship is a security fact (MerchantErrorCode.NOT_KEYABLE). A schedule
        // assignment opens nothing - no session can be created for a merchant that is not
        // ACTIVE, so nothing will ever price against it. Refusing would invent a rule with no
        // safety content.

        Optional<FeeScheduleId> previous = schedules.findAssignment(unitOfWork, merchantId);
        if (previous.filter(scheduleId::equals).isPresent()) {
            // The retry's convergence: the state this command produces, already produced, is
            // this command done (the administrative-move idiom).
            return scheduleId;
        }

        Instant at = Instant.now(clock);
        schedules.assign(unitOfWork, merchantId, previous, scheduleId, reason, at);

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        MerchantAuditAction.MERCHANT_FEE_SCHEDULE_ASSIGNED,
                        ASSIGNMENT_TARGET_TYPE,
                        merchantId.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "merchant=" + merchantId
                                        + ", from=" + previous.map(Object::toString).orElse("none")
                                        + ", to=" + scheduleId)));
        return scheduleId;
    }

    /**
     * Mints the next free version number, retrying while other writers take it first.
     *
     * <p><strong>There is no lock here, and its absence is the design rather than an
     * oversight.</strong> The natural serialization point would be the schedule row — but a
     * schedule row can never be updated, and PostgreSQL requires the {@code UPDATE} privilege
     * to take a row lock at all, which `V004` deliberately withholds. So the arbiter is the
     * unique index on {@code (fee_schedule_id, version)}, which is also the queue: a racer
     * inserting a number an uncommitted transaction holds waits on the index and is refused
     * when that transaction commits, then re-reads and takes the next.
     *
     * <p>Ten instances therefore produce ten distinct numbers with no gap. The bound exists so
     * that a pathological storm fails loudly rather than spinning: sixteen concurrent version
     * creations on one schedule is not a workload, it is a defect somewhere else.
     */
    private FeeScheduleVersion mintVersion(
            Connection unitOfWork, FeeSchedule schedule, NewVersion request, Actor actor) {
        for (int attempt = 1; attempt <= MAX_MINTING_ATTEMPTS; attempt++) {
            FeeScheduleVersion candidate =
                    FeeScheduleVersion.create(
                            ids,
                            schedule,
                            schedules.nextVersionNumber(unitOfWork, schedule.id()),
                            request.rate(),
                            request.fixed(),
                            request.roundingPolicy(),
                            request.refundFeePolicy(),
                            request.effectiveFrom(),
                            actor.id(),
                            clock);
            if (schedules.insertVersionIfNumberIsFree(unitOfWork, candidate)) {
                return candidate;
            }
        }
        throw new MerchantStorageException(
                "a fee schedule version lost "
                        + MAX_MINTING_ATTEMPTS
                        + " consecutive races for its number; the contention is not a workload");
    }

    // ----------------------------------------------------------------- reads

    /** The schedule and every version of it, newest-effective first. */
    public ScheduleDetail detail(Connection unitOfWork, FeeScheduleId scheduleId) {
        FeeSchedule schedule =
                schedules
                        .findSchedule(unitOfWork, scheduleId)
                        .orElseThrow(UnknownFeeScheduleException::new);
        return new ScheduleDetail(schedule, schedules.listVersions(unitOfWork, scheduleId));
    }

    public List<FeeSchedule> list(Connection unitOfWork) {
        return schedules.listSchedules(unitOfWork);
    }

    /** Which schedule a merchant is priced by, or empty. */
    public Optional<FeeScheduleId> assignmentOf(Connection unitOfWork, MerchantId merchantId) {
        return schedules.findAssignment(unitOfWork, merchantId);
    }

    /**
     * The version pricing {@code merchantId} at {@code instant} — what {@code P6-TSK-005} pins
     * at intent creation, and the reason the rest of this class exists.
     *
     * <p>Empty when the merchant has no assignment, or has one whose schedule has no version
     * effective yet. Honest rather than defaulted: fabricating a schedule for an unpriced
     * merchant would price a capture at a number nobody agreed.
     */
    public Optional<FeeScheduleVersion> effectiveVersionFor(
            Connection unitOfWork, MerchantId merchantId, Instant instant) {
        return schedules.findEffectiveVersionFor(unitOfWork, merchantId, instant);
    }

    /**
     * Prices {@code gross} for {@code merchantId} as at {@code instant}, pinning the version
     * that priced it.
     *
     * <p>Composition only — the arithmetic is {@link FeeCalculation}'s and is pure, so that the
     * property that matters ({@code fee + net == gross}) is provable without a database.
     */
    public Optional<FeeAssessment> assess(
            Connection unitOfWork, MerchantId merchantId, Money gross, Instant instant) {
        return effectiveVersionFor(unitOfWork, merchantId, instant)
                .map(version -> FeeCalculation.assess(gross, version));
    }

    /**
     * Recomputes an assessment under the version it pinned — {@code INV-MER-03}'s promise
     * made callable.
     *
     * <p>The version row can never change, so this reproduces the original amount to the minor
     * unit for as long as the record exists. This is the method a merchant statement dispute
     * is settled with.
     *
     * @throws UnknownFeeScheduleException if the pinned version is not there, which would mean
     *     a pinned row had been deleted — impossible through every path, and loud if it ever
     *     stopped being
     */
    public FeeAssessment recompute(
            Connection unitOfWork, Money gross, FeeScheduleVersionId versionId) {
        FeeScheduleVersion version =
                schedules
                        .findVersion(unitOfWork, versionId)
                        .orElseThrow(UnknownFeeScheduleException::new);
        return FeeCalculation.assess(gross, version);
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a fee schedule command must run inside a correlation"
                                                + " scope"));
    }
}
