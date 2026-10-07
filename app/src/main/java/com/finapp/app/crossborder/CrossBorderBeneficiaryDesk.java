package com.finapp.app.crossborder;

import com.finapp.crossborder.Beneficiaries;
import com.finapp.crossborder.BeneficiaryId;
import com.finapp.crossborder.BeneficiaryStore;
import com.finapp.crossborder.BeneficiaryVocabulary;
import com.finapp.crossborder.CorridorDirectory;
import com.finapp.crossborder.CrossborderErrorCode;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The boundary half of the beneficiary doors (`P9-TSK-017`, `PHASE_9_PLAN.md` section 9): parse exactly,
 * gate on step-up, key the registration and the revocation per principal, run the registration as two
 * transactions around the provider exchange (the claim and the selection, then the record and kyc's
 * request), ask the screening synchronously after the record commits, and answer with the shaped status.
 * Every refusal is recorded on the claim, so a replay answers the same; a {@code 503} is retried with a
 * new key and the same grant, which the exchange reference converges.
 */
@Slf4j
public final class CrossBorderBeneficiaryDesk {

    static final String REGISTER_SCOPE = "crossborder.beneficiary:";
    static final String REVOKE_SCOPE = "crossborder.beneficiary-revocation:";

    private final Beneficiaries beneficiaries;
    private final IdentityStore<Connection> identities;
    private final MfaEnrolmentStore<Connection> enrolments;
    private final IdempotentExecutor executor;
    private final TransactionRunner transactions;
    private final Clock clock;

    public CrossBorderBeneficiaryDesk(
            Beneficiaries beneficiaries,
            IdentityStore<Connection> identities,
            MfaEnrolmentStore<Connection> enrolments,
            IdempotentExecutor executor,
            TransactionRunner transactions,
            Clock clock) {
        this.beneficiaries = Objects.requireNonNull(beneficiaries, "beneficiaries must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ------------------------------------------------------------------ responses

    /** A beneficiary as its owner sees it - the status shaped for tipping-off, never a reference or a name. */
    public record CrossBorderBeneficiaryView(
            String id, String nickname, String suffix, String country, String currency, String entityType, String status) {}

    /** The owner's beneficiaries, newest first. */
    public record CrossBorderBeneficiaryList(List<CrossBorderBeneficiaryView> beneficiaries, boolean truncated) {}

    /** The revocation's answer - the same from every state. */
    public record CrossBorderRevocationReceipt(String status) {}

    // ------------------------------------------------------------------ register

    public CrossBorderBeneficiaryView register(
            Session current, String idempotencyKey, CrossBorderBeneficiaryController.CrossBorderBeneficiaryRequest body) {
        Objects.requireNonNull(current, "current must not be null");
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(REGISTER_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);

        record Begun(IdempotentExecutor.BeginOutcome outcome, Beneficiaries.Begun begun, Beneficiaries.Registration request,
                CrossborderErrorCode refusal) {}
        Begun begun = transactions.inTransaction(unitOfWork -> {
            UUID party = partyOf(unitOfWork, current);
            // The step-up gate, before any write: the ApiException aborts the transaction.
            requireConditionalAssurance(unitOfWork, current);
            Beneficiaries.Registration request = parse(party, body);
            RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "crossborder.beneficiary",
                            party.toString(), request.country().code(), request.currency().code(), digest(request.grant()),
                            digest(request.name()), digest(request.nickname()), request.entityType().name(),
                            Boolean.toString(request.acknowledgeNoMatch()))
                    .getBytes(StandardCharsets.UTF_8));
            AtomicReference<Beneficiaries.Begun> started = new AtomicReference<>();
            AtomicReference<CrossborderErrorCode> refused = new AtomicReference<>();
            IdempotentExecutor.BeginOutcome outcome = executor.begin(unitOfWork, key, fingerprint, claimed -> {
                try {
                    started.set(beneficiaries.begin(claimed, request, now()));
                } catch (Beneficiaries.BeneficiaryRefused refusal) {
                    refused.set(refusal.code());
                }
                return new byte[] {1};
            });
            if (refused.get() != null) {
                executor.complete(unitOfWork, key, false, failure(refused.get()));
            }
            return new Begun(outcome, started.get(), request, refused.get());
        });
        if (begun.outcome().replay().isPresent()) {
            return replayed(current, begun.outcome().replay().get());
        }
        if (begun.refusal() != null) {
            throw refused(begun.refusal());
        }
        if (begun.begun().existing().isPresent()) {
            // This grant's beneficiary already exists: the request converges on it.
            BeneficiaryStore.BeneficiaryRow existing = begun.begun().existing().get();
            transactions.inTransaction(unitOfWork -> executor.complete(unitOfWork, key, true, success(existing.id())));
            return view(existing);
        }

        CorridorDirectory.Exchange exchange = beneficiaries.exchange(begun.begun(), begun.request().grant());
        if (!(exchange instanceof CorridorDirectory.Exchange.Exchanged exchanged)) {
            CrossborderErrorCode code = exchange instanceof CorridorDirectory.Exchange.Refused
                    ? CrossborderErrorCode.GRANT_REFUSED
                    : CrossborderErrorCode.PROVIDER_UNAVAILABLE;
            transactions.inTransaction(unitOfWork -> executor.complete(unitOfWork, key, false, failure(code)));
            throw refused(code);
        }

        record Recorded(Beneficiaries.Registered registered, CrossborderErrorCode refusal) {}
        Recorded recorded = transactions.inTransaction(unitOfWork -> {
            try {
                Beneficiaries.Registered registered = beneficiaries.complete(
                        unitOfWork, begun.begun(), exchanged, begun.request(), actor, now(), correlation);
                executor.complete(unitOfWork, key, true, success(registered.beneficiary().id()));
                return new Recorded(registered, null);
            } catch (Beneficiaries.BeneficiaryRefused refusal) {
                executor.complete(unitOfWork, key, false, failure(refusal.code()));
                return new Recorded(null, refusal.code());
            }
        });
        if (recorded.refusal() != null) {
            throw refused(recorded.refusal());
        }
        if (recorded.registered().recorded()) {
            try {
                beneficiaries.screenNow(recorded.registered().beneficiary(), correlation);
            } catch (RuntimeException failure) {
                // The screening is requested and due: kyc's retry decides it. The class name only.
                log.warn("Synchronous counterparty screening failed: {}", failure.getClass().getSimpleName());
            }
        }
        return read(current, recorded.registered().beneficiary().id().value().toString());
    }

    // ------------------------------------------------------------------ read and revoke

    public CrossBorderBeneficiaryList list(Session current) {
        List<BeneficiaryStore.BeneficiaryRow> rows =
                transactions.inTransaction(unitOfWork -> beneficiaries.list(unitOfWork, partyOf(unitOfWork, current)));
        return new CrossBorderBeneficiaryList(rows.stream().map(CrossBorderBeneficiaryDesk::view).toList(), rows.size() >= Beneficiaries.LIST_BOUND);
    }

    public CrossBorderBeneficiaryView read(Session current, String rawId) {
        BeneficiaryId id = beneficiaryId(rawId);
        return transactions.inTransaction(unitOfWork -> beneficiaries.find(unitOfWork, id, partyOf(unitOfWork, current)))
                .map(CrossBorderBeneficiaryDesk::view)
                .orElseThrow(CrossBorderBeneficiaryDesk::notFound);
    }

    /** Revokes from any non-terminal state - the same {@code REVOKED} answer from every one. */
    public CrossBorderRevocationReceipt revoke(Session current, String rawId, String idempotencyKey) {
        BeneficiaryId id = beneficiaryId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(REVOKE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("crossborder.beneficiary-revocation|" + id.value()).getBytes(StandardCharsets.UTF_8));
        guarded(() -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
            beneficiaries.revoke(uow, id, partyOf(uow, current), actor, now(), correlation);
            return CommandResult.succeeded(StoredResponse.of("REVOKED".getBytes(StandardCharsets.UTF_8), "text/plain"));
        })));
        return new CrossBorderRevocationReceipt("REVOKED");
    }

    // ------------------------------------------------------------------ plumbing

    private Beneficiaries.Registration parse(UUID party, CrossBorderBeneficiaryController.CrossBorderBeneficiaryRequest body) {
        try {
            return new Beneficiaries.Registration(
                    party,
                    CountryCode.of(body.country()),
                    CurrencyCode.of(body.currency()),
                    body.grant(),
                    body.name(),
                    body.nickname(),
                    BeneficiaryVocabulary.EntityType.valueOf(body.entityType()),
                    Boolean.TRUE.equals(body.acknowledgeNoMatch()));
        } catch (Beneficiaries.RegistrationInvalid malformed) {
            // The domain's own defect text: fixed sentences naming the rule, never the value.
            throw new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The beneficiary registration was not valid",
                    malformed.getMessage());
        } catch (IllegalArgumentException | com.finapp.sharedkernel.money.MonetaryException malformed) {
            // A JDK or value-type message echoes the input and names internal classes (Enum.valueOf's
            // "No enum constant com.finapp..."): a fixed detail instead (the Phase 9 to 10 transition gate).
            throw new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The beneficiary registration was not valid",
                    "country is ISO 3166 alpha-2, currency ISO 4217, entityType INDIVIDUAL or BUSINESS.");
        }
    }

    /** {@code MULTI_FACTOR} exactly of an identity with a factor - {@code BeneficiaryService}'s structural rule. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A beneficiary registration from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    private CrossBorderBeneficiaryView replayed(Session current, StoredResponse response) {
        String[] fields = new String(response.body(), StandardCharsets.UTF_8).split("\\|", -1);
        if (fields[0].equals("ERR")) {
            throw refused(CrossborderErrorCode.valueOf(fields[1]));
        }
        return read(current, fields[1]);
    }

    private static StoredResponse success(BeneficiaryId id) {
        return StoredResponse.of(("OK|" + id.value()).getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static StoredResponse failure(CrossborderErrorCode code) {
        return StoredResponse.of(("ERR|" + code.name()).getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static CrossBorderBeneficiaryView view(BeneficiaryStore.BeneficiaryRow row) {
        return new CrossBorderBeneficiaryView(row.id().value().toString(), row.nickname(), row.suffix(), row.country().code(),
                row.currency().code(), row.entityType().name(), row.status().shaped());
    }

    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (Beneficiaries.BeneficiaryNotFound unknown) {
            throw notFound();
        }
    }

    private static ApiException refused(CrossborderErrorCode code) {
        return new ApiException(code, "The beneficiary registration was refused");
    }

    private static ApiException notFound() {
        return new ApiException(CrossborderErrorCode.BENEFICIARY_NOT_FOUND,
                "No beneficiary matches the requested identifier", "no such beneficiary.");
    }

    private static BeneficiaryId beneficiaryId(String raw) {
        try {
            return BeneficiaryId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", impossible);
        }
    }

    /** {@code Session -> Identity -> Party}: the {@code ProfileService} chain. */
    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a beneficiary command runs inside a correlation scope"))
                .correlationId();
    }
}
