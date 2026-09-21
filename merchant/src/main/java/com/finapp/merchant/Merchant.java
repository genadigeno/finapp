package com.finapp.merchant;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The merchant: a commercial counterparty the platform accepts payments for, owes money to,
 * and pays out (`P6-TSK-003`, bounded context 12, ADR-0050…0052).
 *
 * <p><strong>What this aggregate deliberately does not hold: money.</strong> The payable is
 * the merchant's {@code MERCHANT_PAYABLE} ledger position and exists nowhere else
 * ({@code INV-MER-02}) — no field here, no column in `V002`, ever. What it holds is the
 * commercial relationship: whose organisation this is ({@code partyRef}, by value — the KYB
 * gate resolved it, never a request), what it is called, which currency it settles in, and
 * whether it may currently start new business ({@link MerchantStatus}).
 *
 * <p><strong>One constructor holding the coherence</strong> (the {@code Transfer} idiom):
 * names within bounds, the settlement currency present, timestamps ordered. {@code rehydrate}
 * applies the same checks — a corrupt row is refused at read, never propagated.
 *
 * <p><strong>Transitions are the machine's</strong> ({@code INV-LIFE-02}): {@link #suspend},
 * {@link #reinstate} and {@link #close} refuse illegal edges at the aggregate; `V002`'s
 * every-writer trigger refuses them for raw SQL and the migrator; the store's conditional
 * {@code UPDATE … WHERE status = ?} converges the racers. Three layers, blind in different
 * directions.
 */
public final class Merchant {

    /** Generated into `V002`'s CHECKs; one definition (`MerchantMigrationTest` reconciles). */
    public static final int MAX_NAME_LENGTH = 200;

    private final MerchantId id;
    private final UUID partyRef;
    private final String legalName;
    private final String displayName;
    private final CurrencyCode settlementCurrency;
    private final MerchantStatus status;
    private final Instant createdAt;
    private final Instant statusChangedAt;

    private Merchant(
            MerchantId id,
            UUID partyRef,
            String legalName,
            String displayName,
            CurrencyCode settlementCurrency,
            MerchantStatus status,
            Instant createdAt,
            Instant statusChangedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.partyRef = Objects.requireNonNull(partyRef, "partyRef must not be null");
        this.legalName = validName(legalName, "legalName");
        this.displayName = validName(displayName, "displayName");
        this.settlementCurrency =
                Objects.requireNonNull(settlementCurrency, "settlementCurrency must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.statusChangedAt =
                Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
        if (statusChangedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("statusChangedAt must not precede createdAt");
        }
    }

    /** Onboarding creates {@code ACTIVE} directly: the KYB gate is what pended (ADR-0044). */
    public static Merchant onboard(
            IdGenerator ids,
            Clock clock,
            UUID verifiedPartyRef,
            String legalName,
            String displayName,
            CurrencyCode settlementCurrency) {
        Instant now = Instant.now(clock);
        return new Merchant(
                MerchantId.next(ids),
                verifiedPartyRef,
                legalName,
                displayName,
                settlementCurrency,
                MerchantStatus.ACTIVE,
                now,
                now);
    }

    /** Reconstitutes from storage. Applies the coherence; a corrupt row is refused at read. */
    public static Merchant rehydrate(
            MerchantId id,
            UUID partyRef,
            String legalName,
            String displayName,
            CurrencyCode settlementCurrency,
            MerchantStatus status,
            Instant createdAt,
            Instant statusChangedAt) {
        return new Merchant(
                id,
                partyRef,
                legalName,
                displayName,
                settlementCurrency,
                status,
                createdAt,
                statusChangedAt);
    }

    /** Administratively freezes new dispatches. Landed money still lands; reversible. */
    public Merchant suspend(Clock clock) {
        return transitioned(MerchantStatus.SUSPENDED, clock);
    }

    /** Lifts the freeze. */
    public Merchant reinstate(Clock clock) {
        return transitioned(MerchantStatus.ACTIVE, clock);
    }

    /** Ends the commercial relationship. Terminal; the books are not (`INV-HIST-01`). */
    public Merchant close(Clock clock) {
        return transitioned(MerchantStatus.CLOSED, clock);
    }

    private Merchant transitioned(MerchantStatus to, Clock clock) {
        if (!status.canTransitionTo(to)) {
            throw new IllegalMerchantTransitionException(status, to);
        }
        return new Merchant(
                id,
                partyRef,
                legalName,
                displayName,
                settlementCurrency,
                to,
                createdAt,
                Instant.now(clock));
    }

    private static String validName(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    field + " must be 1.." + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    public MerchantId id() {
        return id;
    }

    public UUID partyRef() {
        return partyRef;
    }

    public String legalName() {
        return legalName;
    }

    public String displayName() {
        return displayName;
    }

    public CurrencyCode settlementCurrency() {
        return settlementCurrency;
    }

    public MerchantStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }
}
