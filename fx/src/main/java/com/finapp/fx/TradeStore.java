package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for booked trades and the covers they want (`P9-TSK-009`) - on the caller's unit of
 * work. {@code fx V006} holds the single execution, the copies' equality to the accepted quote,
 * the freeze, the entry attached once, and the cover's machine and permit for every writer.
 */
public interface TradeStore {

    /** What the database stamped on a booked trade. */
    record Booked(Instant bookedAt, LocalDate bookedOn) {}

    /** A trade as its owner sees it. */
    record TradeRow(
            FxTradeId id,
            FxQuoteId quoteId,
            UUID owner,
            PricingPurpose purpose,
            FixedSide fixedSide,
            TradeStatus status,
            Money customerSource,
            Money customerDestination,
            BigDecimal executedRate,
            Instant bookedAt,
            LocalDate bookedOn,
            UUID journalEntryId) {}

    /** A cover to insert, with its attempt 1. */
    record CoverDraft(
            UUID id,
            FxQuoteId quoteId,
            CoverKind kind,
            String providerCode,
            CurrencyCode source,
            CurrencyCode destination,
            FixedSide fixedSide,
            Money fixedAmount,
            String clientReference,
            String providerQuoteReference,
            String correlationId) {}

    /** Books the plan of {@code plan}'s quote - which must be ACCEPTED - as trade {@code id}. */
    Booked insert(Connection unitOfWork, FxTradeId id, QuoteStore.PlanRow plan, String correlationId);

    /** Attaches the trade's entry, once. */
    void attachEntry(Connection unitOfWork, FxTradeId id, UUID journalEntryId);

    /** The trade, only if {@code owner} owns it. */
    Optional<TradeRow> findOwned(Connection unitOfWork, FxTradeId id, UUID owner);

    /** A cover named by one of its attempts' references - its identity and where it stands. */
    record CoverByReference(UUID coverId, CoverStatus status) {}

    /**
     * The cover one of whose attempts carries {@code clientReference} (`P9-TSK-011`): what
     * reconciliation's reference lookup asks of an FX provider's {@code COVER_REF} - a cover still in
     * flight is our evidence not yet confirmed, an absent one is a reference we never minted. Read
     * through {@code cover_attempt}'s unique reference; no lock, the answer is a classification.
     */
    Optional<CoverByReference> coverByClientReference(Connection unitOfWork, String clientReference);

    /** The cover and its attempt 1 - our reference stored before anything is ever sent. */
    void insertCover(Connection unitOfWork, CoverDraft draft);
}
