package com.finapp.crossborder;

import com.finapp.crossborder.BeneficiaryVocabulary.EntityType;
import com.finapp.crossborder.BeneficiaryVocabulary.PayeeCheck;
import com.finapp.crossborder.BeneficiaryVocabulary.ScreeningOutcome;
import com.finapp.crossborder.BeneficiaryVocabulary.StatusCause;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The cross-border beneficiary (`P9-TSK-017`, ADR-0080 sections 3 and 5a, ADR-0081; {@code INV-XB-02},
 * {@code INV-RAIL-02}, {@code INV-RAIL-03}, {@code INV-KYC-05}).
 *
 * <h2>Registration is two transactions around an exchange</h2>
 *
 * <p>{@link #begin} (in the door's claiming transaction) selects the provider under the active corridor
 * policy and records the selection and a registration keyed by the exchange reference - derived from the
 * owner and the grant, so the same grant presented again converges on it and the provider, deduping on
 * the reference, answers the same exchange. {@link #exchange} runs with no connection held.
 * {@link #complete} (in the recording transaction) locks the registration, judges the attested
 * attributes and the payee acknowledgement, requests kyc's screening <em>in the same unit of work</em> and
 * records the beneficiary {@code PENDING_SCREENING} with its history, audit record and event. The screening
 * is then asked synchronously ({@link #screenNow}); kyc decides, and moves the beneficiary through
 * {@link #screeningDecided} in its own deciding transaction (T-e).
 *
 * <h2>What crossborder never holds</h2>
 *
 * <p>The name passes to kyc and the grant to the provider; neither is stored here. The beneficiary is the
 * provider's opaque reference, a suffix, the payee check and the attested attributes.
 */
@RequiredArgsConstructor
public final class Beneficiaries {

    public static final String REGISTERED_EVENT = "crossborder.BeneficiaryRegistered";
    public static final String ACTIVATED_EVENT = "crossborder.BeneficiaryActivated";
    public static final String BLOCKED_EVENT = "crossborder.BeneficiaryBlocked";
    public static final String REVOKED_EVENT = "crossborder.BeneficiaryRevoked";

    static final String TARGET_TYPE = "crossborder_beneficiary";

    /** A read's bound - a list is never unbounded. */
    public static final int LIST_BOUND = 100;

    private static final Pattern GRANT = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    @NonNull private final BeneficiaryStore store;
    @NonNull private final CorridorPolicyStore policies;
    @NonNull private final CorridorAvailabilityStore availability;
    @NonNull private final CorridorDirectory directory;
    @NonNull private final CounterpartyScreening screening;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;

    // ------------------------------------------------------------------ requests and outcomes

    /** A registration request - the grant and the name are redacted in {@code toString}. */
    public record Registration(
            UUID owner,
            CountryCode country,
            CurrencyCode currency,
            String grant,
            String name,
            String nickname,
            EntityType entityType,
            boolean acknowledgeNoMatch) {
        public Registration {
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(grant, "grant must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(nickname, "nickname must not be null");
            Objects.requireNonNull(entityType, "entityType must not be null");
            if (!GRANT.matcher(grant).matches() || grant.chars().noneMatch(Character::isLetter)
                    || InstrumentShapes.holdsAny(grant)) {
                throw new RegistrationInvalid("a grant is 1-128 characters of [A-Za-z0-9_.:-], carries a letter, and"
                        + " never takes a bank identifier's shape");
            }
            if (name.isBlank() || name.length() > 140 || name.chars().anyMatch(Character::isISOControl)) {
                throw new RegistrationInvalid("a beneficiary name is 1..140 characters with no control character");
            }
            if (nickname.isBlank() || nickname.length() > 40 || nickname.chars().anyMatch(Character::isISOControl)) {
                throw new RegistrationInvalid("a nickname is 1..40 characters with no control character");
            }
            if (InstrumentShapes.holdsAny(nickname)) {
                throw new RegistrationInvalid("a nickname must not hold a card-number or bank-account shape");
            }
        }

        @Override
        public String toString() {
            return "Registration[owner=" + owner + ", country=" + country + ", currency=" + currency
                    + ", grant=<redacted>, name=<redacted>, entityType=" + entityType + "]";
        }
    }

    /** Tx1's outcome: the registration, and its beneficiary when one was already recorded. */
    public record Begun(BeneficiaryStore.RegistrationRow registration, Optional<BeneficiaryStore.BeneficiaryRow> existing) {}

    /** Tx2's outcome; {@code recorded} false when a racing flight recorded it first. */
    public record Registered(BeneficiaryStore.BeneficiaryRow beneficiary, boolean recorded) {}

    // ------------------------------------------------------------------ refusals

    /** A registration or command crossborder refuses, with its code. */
    public static final class BeneficiaryRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final CrossborderErrorCode code;

        BeneficiaryRefused(CrossborderErrorCode code, String detail) {
            super(detail);
            this.code = code;
        }

        public CrossborderErrorCode code() {
            return code;
        }
    }

    /** A malformed registration; the message names the defect. */
    public static final class RegistrationInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public RegistrationInvalid(String defect) {
            super(defect);
        }
    }

    /** No beneficiary of the caller's has this id. */
    public static final class BeneficiaryNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BeneficiaryNotFound() {
            super("no beneficiary of yours has this identifier");
        }
    }

    // ------------------------------------------------------------------ registration

    /** The provider-facing reference for {@code owner}'s {@code grant}: {@code XBB} and 32 hex digits. */
    public static String exchangeReference(UUID owner, String grant) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("crossborder.beneficiary:" + owner + ":" + grant).getBytes(StandardCharsets.UTF_8));
            return "XBB" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", impossible);
        }
    }

    /**
     * Tx1: selects the provider under the active corridor policy and records the selection and the
     * registration - or converges on the registration this grant already has.
     *
     * @throws BeneficiaryRefused {@code CORRIDOR_NOT_OFFERED} when no available corridor delivers the
     *     currency in the country, or no candidate rail is eligible
     */
    public Begun begin(Connection unitOfWork, Registration request, Instant now) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(now, "now must not be null");
        String reference = exchangeReference(request.owner(), request.grant());
        Optional<BeneficiaryStore.RegistrationRow> known = store.registrationByReference(unitOfWork, reference);
        if (known.isPresent()) {
            return converged(unitOfWork, known.get());
        }
        CorridorPolicyStore.VersionView active = policies.active(unitOfWork)
                .orElseThrow(() -> notOffered("no corridor policy is active"));
        CorridorSelection.Selection selection = CorridorSelection.select(
                new CorridorSelection.Inputs(request.country(), request.currency(), request.entityType()),
                active.corridors(),
                code -> availability.isAvailable(unitOfWork, CorridorKey.parse(code)),
                directory);
        String rail = selection.chosen().orElseThrow(() -> notOffered("no available corridor rail delivers there"));
        UUID selectionId = ids.next();
        store.insertSelection(unitOfWork, selectionId, active.row().id(), selection, now);
        BeneficiaryStore.RegistrationRow registration = new BeneficiaryStore.RegistrationRow(
                ids.next(), request.owner(), reference, selectionId, rail, request.country(), request.currency(),
                request.entityType(), now);
        if (!store.insertRegistration(unitOfWork, registration)) {
            return converged(unitOfWork, store.registrationByReference(unitOfWork, reference)
                    .orElseThrow(() -> new CrossborderStorageException(
                            "a registration was refused as a duplicate but none is visible; retry", null)));
        }
        return new Begun(registration, Optional.empty());
    }

    /** The exchange - no connection held. */
    public CorridorDirectory.Exchange exchange(Begun begun, String grant) {
        Objects.requireNonNull(begun, "begun must not be null");
        Objects.requireNonNull(grant, "grant must not be null");
        return directory.exchange(begun.registration().rail(), begun.registration().exchangeReference(), grant);
    }

    /**
     * Tx2: records the beneficiary {@code PENDING_SCREENING}, with kyc's screening requested in the same
     * unit of work. A racing flight that recorded it first is converged on.
     *
     * @throws BeneficiaryRefused {@code CORRIDOR_NOT_OFFERED} when the provider attests another destination;
     *     {@code NO_MATCH_UNACKNOWLEDGED} when the payee check is not {@code MATCH} and the customer did not
     *     acknowledge it - nothing is recorded, and the same grant may be presented again
     */
    public Registered complete(
            Connection unitOfWork,
            Begun begun,
            CorridorDirectory.Exchange.Exchanged exchanged,
            Registration request,
            Actor actor,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(begun, "begun must not be null");
        Objects.requireNonNull(exchanged, "exchanged must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        BeneficiaryStore.RegistrationRow registration = store.lockRegistration(unitOfWork, begun.registration().id())
                .orElseThrow(() -> new IllegalStateException("a begun registration is always visible to its completion"));
        Optional<BeneficiaryStore.BeneficiaryRow> existing = store.beneficiaryOfRegistration(unitOfWork, registration.id());
        if (existing.isPresent()) {
            return new Registered(existing.get(), false);
        }
        if (!exchanged.country().equals(registration.country()) || !exchanged.currency().equals(registration.currency())
                || exchanged.entityType() != registration.entityType()) {
            throw notOffered("the provider attests a destination other than the one requested");
        }
        if (exchanged.payeeCheck() != PayeeCheck.MATCH && !request.acknowledgeNoMatch()) {
            throw new BeneficiaryRefused(CrossborderErrorCode.NO_MATCH_UNACKNOWLEDGED,
                    "the payee check did not match: acknowledge it to register this beneficiary");
        }
        BeneficiaryId id = BeneficiaryId.next(ids);
        UUID screeningId = screening.requestWithin(unitOfWork, new CounterpartyScreening.Request(
                screeningReference(id), request.name(), exchanged.country(), exchanged.entityType(), exchanged.payeeCheck()));
        BeneficiaryStore.BeneficiaryRow beneficiary = new BeneficiaryStore.BeneficiaryRow(
                id, registration.owner(), registration.id(), registration.rail(), exchanged.destinationReference(),
                exchanged.suffix(), exchanged.payeeCheck(), exchanged.payeeCheck() != PayeeCheck.MATCH, exchanged.country(),
                exchanged.currency(), exchanged.entityType(), request.nickname(), BeneficiaryStatus.PENDING_SCREENING,
                screeningId, now, Optional.empty());
        store.insertBeneficiary(unitOfWork, beneficiary);
        store.appendEvent(unitOfWork, ids.next(), id, Optional.empty(), BeneficiaryStatus.PENDING_SCREENING,
                StatusCause.REGISTERED, Optional.of(screeningId), now);
        record(unitOfWork, actor, now, CrossborderAuditAction.BENEFICIARY_REGISTERED, id,
                "beneficiary=" + id.value() + ", country=" + exchanged.country().code() + ", currency="
                        + exchanged.currency().code() + ", rail=" + registration.rail() + ", payeeCheck="
                        + exchanged.payeeCheck().name(),
                correlation);
        announce(unitOfWork, REGISTERED_EVENT, beneficiary, Optional.empty(), now, correlation);
        return new Registered(beneficiary, true);
    }

    /** Asks kyc to screen now - no connection held; kyc moves the beneficiary in its own T-e. */
    public void screenNow(BeneficiaryStore.BeneficiaryRow beneficiary, CorrelationId correlation) {
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        screening.screenNow(beneficiary.screeningId(), correlation);
    }

    /** kyc's request reference for {@code id}'s screening. */
    public static String screeningReference(BeneficiaryId id) {
        return "xb-beneficiary-" + id.value();
    }

    // ------------------------------------------------------------------ the customer

    /**
     * Revokes {@code owner}'s beneficiary from any non-terminal state; a revoked one converges. The
     * outcome is the same {@code REVOKED} whatever the state it left - the door answers identically.
     *
     * @throws BeneficiaryNotFound when {@code id} is not {@code owner}'s
     */
    public BeneficiaryStore.BeneficiaryRow revoke(
            Connection unitOfWork, BeneficiaryId id, UUID owner, Actor actor, Instant now, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        BeneficiaryStore.BeneficiaryRow row = store.lockOwned(unitOfWork, id, owner).orElseThrow(BeneficiaryNotFound::new);
        if (row.status() == BeneficiaryStatus.REVOKED) {
            return row;
        }
        if (!store.move(unitOfWork, id, row.status(), BeneficiaryStatus.REVOKED, Optional.of(now))) {
            throw new IllegalStateException("the locked beneficiary was moved by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, ids.next(), id, Optional.of(row.status()), BeneficiaryStatus.REVOKED,
                StatusCause.CUSTOMER, Optional.empty(), now);
        record(unitOfWork, actor, now, CrossborderAuditAction.BENEFICIARY_REVOKED, id,
                "beneficiary=" + id.value() + ", from=" + row.status().name(), correlation);
        BeneficiaryStore.BeneficiaryRow revoked = withStatus(row, BeneficiaryStatus.REVOKED, Optional.of(now));
        announce(unitOfWork, REVOKED_EVENT, revoked, Optional.of(row.status()), now, correlation);
        return revoked;
    }

    public Optional<BeneficiaryStore.BeneficiaryRow> find(Connection unitOfWork, BeneficiaryId id, UUID owner) {
        return store.findOwned(unitOfWork, id, owner);
    }

    public List<BeneficiaryStore.BeneficiaryRow> list(Connection unitOfWork, UUID owner) {
        return store.listOwned(unitOfWork, owner, LIST_BOUND);
    }

    // ------------------------------------------------------------------ the screening listener

    /**
     * Moves the beneficiary whose current screening is {@code screeningId} - inside kyc's deciding
     * transaction (T-e). A revoked beneficiary is left {@code REVOKED}; an unavailable answer moves nothing;
     * a screening that is no beneficiary's current one moves nothing.
     */
    public void screeningDecided(
            Connection unitOfWork, UUID screeningId, ScreeningOutcome outcome, Instant now, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(screeningId, "screeningId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Optional<BeneficiaryStore.BeneficiaryRow> locked = store.lockByScreening(unitOfWork, screeningId);
        if (locked.isEmpty() || locked.get().status() == BeneficiaryStatus.REVOKED) {
            return;
        }
        BeneficiaryStore.BeneficiaryRow row = locked.get();
        Optional<BeneficiaryStatus> target = target(row.status(), outcome);
        if (target.isEmpty()) {
            return;
        }
        if (!store.move(unitOfWork, row.id(), row.status(), target.get(), Optional.empty())) {
            throw new IllegalStateException("the locked beneficiary was moved by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, ids.next(), row.id(), Optional.of(row.status()), target.get(), StatusCause.SCREENING,
                Optional.of(screeningId), now);
        BeneficiaryStore.BeneficiaryRow moved = withStatus(row, target.get(), Optional.empty());
        if (target.get() == BeneficiaryStatus.ACTIVE) {
            announce(unitOfWork, ACTIVATED_EVENT, moved, Optional.of(row.status()), now, correlation);
        } else if (target.get() == BeneficiaryStatus.BLOCKED) {
            announce(unitOfWork, BLOCKED_EVENT, moved, Optional.of(row.status()), now, correlation);
        }
    }

    /** Where a screening outcome takes a beneficiary in {@code current}, if anywhere. */
    static Optional<BeneficiaryStatus> target(BeneficiaryStatus current, ScreeningOutcome outcome) {
        BeneficiaryStatus wanted = switch (outcome) {
            case CLEARED -> BeneficiaryStatus.ACTIVE;
            case IN_REVIEW -> BeneficiaryStatus.IN_REVIEW;
            case BLOCKED -> BeneficiaryStatus.BLOCKED;
            case UNAVAILABLE -> null;
        };
        if (wanted == null || wanted == current || !current.canMoveTo(wanted)) {
            return Optional.empty();
        }
        return Optional.of(wanted);
    }

    // ------------------------------------------------------------------ plumbing

    private Begun converged(Connection unitOfWork, BeneficiaryStore.RegistrationRow registration) {
        return new Begun(registration, store.beneficiaryOfRegistration(unitOfWork, registration.id()));
    }

    private static BeneficiaryRefused notOffered(String detail) {
        return new BeneficiaryRefused(CrossborderErrorCode.CORRIDOR_NOT_OFFERED, detail);
    }

    private static BeneficiaryStore.BeneficiaryRow withStatus(
            BeneficiaryStore.BeneficiaryRow row, BeneficiaryStatus status, Optional<Instant> revokedAt) {
        return new BeneficiaryStore.BeneficiaryRow(row.id(), row.owner(), row.registrationId(), row.rail(),
                row.destinationReference(), row.suffix(), row.payeeCheck(), row.acknowledgedNoMatch(), row.country(),
                row.currency(), row.entityType(), row.nickname(), status, row.screeningId(), row.registeredAt(), revokedAt);
    }

    private void record(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            CrossborderAuditAction action,
            BeneficiaryId id,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        action,
                        TARGET_TYPE,
                        id.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }

    private void announce(
            Connection unitOfWork,
            String type,
            BeneficiaryStore.BeneficiaryRow beneficiary,
            Optional<BeneficiaryStatus> from,
            Instant at,
            CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("beneficiary", beneficiary.id().value().toString())
                .with("country", beneficiary.country().code())
                .with("currency", beneficiary.currency().code());
        if (from.isPresent()) {
            payload = payload.with("from", from.get().name());
        }
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        CorridorPolicyAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        beneficiary.id(),
                        TARGET_TYPE,
                        at,
                        CorridorPolicyAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
