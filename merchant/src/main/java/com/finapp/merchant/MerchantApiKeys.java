package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Issues and revokes a merchant's API credentials (`P6-TSK-002`, ADR-0052) — the operator's
 * acts, behind {@code MERCHANT_ADMINISTER}.
 *
 * <p><strong>The secret is returned exactly once and is then unreachable.</strong> Issuance is
 * the only method on this platform that hands a caller a merchant credential, and it does so
 * from the freshly generated value in memory — never from a read, because no read can produce
 * it ({@code INV-IDN-01}). A caller that loses it issues a new key; there is no recovery path
 * and building one would be building the leak.
 *
 * <p><strong>Issuance is keyed, and the claim stores the key id — NEVER the secret.</strong>
 * A retried issuance that minted a second secret would leave a live credential nobody knows
 * about, unrevokable in practice because the operator never saw it; so the claim exists, and
 * a replay converges on the same key ({@code INV-IDEM-01}). But the obvious way to make that
 * replay render the original response — recording the response bytes, as every other keyed
 * command on this platform does — would write a live merchant credential into
 * {@code platform.idempotency_record.response_body}, where it would sit recoverable for the
 * claim's whole retention. That is precisely what {@code INV-IDN-01} forbids, and it would
 * make `V003`'s "there is nothing here to leak" false by a different route.
 *
 * <p>So <strong>show-once means once</strong>: the secret is returned by the call that minted
 * it and by nothing else, ever. A replay answers with the key id and no secret — the honest
 * answer, since the credential was already issued and already shown — and an operator who
 * lost it issues a new key. This is the one place the platform's byte-for-byte replay
 * discipline yields, and it yields to the stronger invariant rather than being quietly bent.
 *
 * <p><strong>Revocation is reasoned and terminal.</strong> A revoked key is never reinstated
 * ({@code MerchantApiKeyStatus}), the reason is required ({@code INV-AUD-03} — revoking a
 * counterparty's access is a security judgement), and a repeat converges on the revoked key
 * rather than failing: one act, however many operators asked.
 */
@RequiredArgsConstructor
public final class MerchantApiKeys {

    static final String TARGET_TYPE = "merchant_api_key";
    static final String IDEMPOTENCY_SCOPE = "merchant.api-key.issue";

    /**
     * What issuance hands back. {@code secret} is present on the call that MINTED the key and
     * empty on every replay — see the class comment: it is never stored, so a replay has
     * nothing to render and says so rather than inventing one.
     */
    public record IssuedKey(
            MerchantApiKeyId keyId,
            Optional<com.finapp.sharedkernel.security.Sensitive<String>> secret,
            boolean replayed) {

        /**
         * Masked: the {@code AuthenticatedSession} discipline. A record's generated
         * {@code toString} printing a live merchant credential into a log is the accident
         * {@code INV-AUD-02} exists to stop.
         */
        @Override
        public String toString() {
            return "IssuedKey[keyId=" + keyId + ", replayed=" + replayed + ", secret="
                    + (secret.isPresent()
                            ? com.finapp.sharedkernel.security.Sensitive.MASK
                            : "absent")
                    + "]";
        }
    }

    @NonNull private final MerchantApiKeyStore<Connection> keys;
    @NonNull private final MerchantStore<Connection> merchants;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final SecureRandom randomness;

    /** Issues a key for the merchant, or replays the recorded outcome for a retried key. */
    public IssuedKey issue(
            Connection unitOfWork, MerchantId merchantId, String idempotencyKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        Actor actor = SecurityContext.require();
        Correlation correlation = correlation();

        // The subject must exist and must be one a key could act for: a credential for a
        // closed merchant is a door into a relationship that ended.
        Merchant merchant =
                merchants
                        .findById(unitOfWork, merchantId)
                        .orElseThrow(UnknownMerchantException::new);
        if (merchant.status() == MerchantStatus.CLOSED) {
            throw new MerchantNotKeyableException();
        }

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        (IDEMPOTENCY_SCOPE + "|" + actor.type().name() + "|" + actor.id() + "|"
                                        + merchantId.value())
                                .getBytes(StandardCharsets.UTF_8));

        // The minted secret leaves the command through this holder rather than through the
        // recorded response, which is the whole point: the claim persists the key id and the
        // plaintext never reaches storage. A local rather than a field, so nothing is shared
        // between concurrent issuances on any instance.
        AtomicReference<com.finapp.sharedkernel.security.Sensitive<String>> minted =
                new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> mint(uow, merchantId, actor, correlation, minted));

        String recordedKeyId =
                new String(
                        outcome.body()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a recorded issuance outcome always"
                                                                + " carries the key id")),
                        StandardCharsets.UTF_8);
        return new IssuedKey(
                MerchantApiKeyId.of(UUID.fromString(recordedKeyId)),
                // Present on the acting call, empty on a replay: none was stored.
                Optional.ofNullable(minted.get()),
                outcome.replayed());
    }

    /** The merchant's keys — metadata only; no secret and no hash is rendered anywhere. */
    public List<MerchantApiKey> list(Connection unitOfWork, MerchantId tenant) {
        return keys.listFor(unitOfWork, tenant);
    }

    /**
     * Revokes one of the merchant's keys with the operator's reason. Converges on a key
     * already revoked; refuses one that is not this merchant's with the same empty answer as
     * one that does not exist ({@code INV-MER-01}).
     */
    public void revoke(
            Connection unitOfWork, MerchantId tenant, MerchantApiKeyId keyId, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = correlation();

        MerchantApiKey before =
                keys.findOwnedForUpdate(unitOfWork, tenant, keyId)
                        .orElseThrow(UnknownMerchantApiKeyException::new);
        if (before.status() == MerchantApiKeyStatus.REVOKED) {
            return; // One revocation, however many operators asked.
        }

        MerchantApiKey revoked = before.revoke(clock);
        if (!keys.revoke(unitOfWork, before, revoked, reason)) {
            throw new MerchantStorageException(
                    "a locked api key row's conditional revocation found another writer's state");
        }

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        MerchantAuditAction.MERCHANT_API_KEY_REVOKED,
                        TARGET_TYPE,
                        keyId.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // The key and its merchant by identifier - never the hash (INV-AUD-02).
                        Optional.of("key=" + keyId + ", merchant=" + tenant)));
    }

    private CommandResult mint(
            Connection uow,
            MerchantId merchantId,
            Actor actor,
            Correlation correlation,
            AtomicReference<com.finapp.sharedkernel.security.Sensitive<String>> minted) {
        MerchantApiKeySecret secret = MerchantApiKeySecret.issue(randomness);
        MerchantApiKey key = MerchantApiKey.issue(ids, clock, merchantId, secret, actor.id());
        keys.insert(uow, key);

        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        MerchantAuditAction.MERCHANT_API_KEY_ISSUED,
                        TARGET_TYPE,
                        key.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // The key id is PUBLIC and is exactly what an auditor needs to tie a
                        // later merchant action to this issuance; the secret appears nowhere.
                        Optional.of(
                                "key=" + key.id() + ", merchant=" + merchantId + ", algorithm="
                                        + key.algorithm())));

        // THE SECRET GOES TO THE CALLER, NOT TO STORAGE. The recorded response is the key id
        // alone: enough for a replay to converge on the same credential, and nothing a
        // database reader could ever present as one (INV-IDN-01). The class comment records
        // why this deviates from the platform's byte-for-byte replay discipline.
        // The WRAPPED plaintext: this command never holds a bare secret (the accessor's
        // own comment says why), so the only unwrap on the whole path is the boundary's.
        minted.set(secret.plaintext());
        return CommandResult.succeeded(
                StoredResponse.of(
                        key.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }

    private static Correlation correlation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a merchant api key command must run inside a"
                                                + " correlation scope"));
    }
}
