package com.finapp.merchant;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.SupportedCurrencies;
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
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Onboards a merchant: the commercial relationship and its books, in one transaction
 * (`P6-TSK-003`, ADR-0050…0052).
 *
 * <p><strong>The gate is a per-decision authoritative read inside this unit of work.</strong>
 * {@link MerchantVerification} answers "may this party be a merchant?" from the organisation
 * party's live customer being {@code ACTIVE} — the KYB decision's faithful projection
 * ({@code INV-KYC-05}), consumed, never recomputed. A refusal throws before any write, so a
 * refused onboarding writes nothing, structurally.
 *
 * <p><strong>Keyed at the financial boundary</strong> ({@code INV-IDEM-01}): scope
 * {@code merchant.onboard}, the operator's key, the fingerprint binding the actor and the
 * request's meaning. Ten instances with one key produce one merchant, one payable account,
 * one audit record and one event; the replay renders the recorded outcome byte-for-byte.
 * Two <em>different</em> keys for one organisation are two legitimate shops — no one-live
 * index, deliberately (`PHASE_6_PLAN.md` §4, the decision recorded rather than implied).
 *
 * <p><strong>The books open with the relationship</strong> ({@code INV-MER-02}): the payable
 * is a {@code MERCHANT_PAYABLE} ledger account — a LIABILITY, because what a merchant is owed
 * is money the platform holds against them — created in this same transaction through
 * {@code createOrConverge}, so a partially-repeated onboarding still lands on one account.
 */
public final class MerchantOnboarding {

    static final String EVENT_TYPE = "merchant.MerchantOnboarded";
    static final String PRODUCER = "merchant";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "merchant";
    static final String IDEMPOTENCY_SCOPE = "merchant.onboard";

    /** The onboarding's judgement, as the surface renders it. */
    public record OnboardingResult(MerchantId merchantId, boolean replayed) {}

    /** The operator's request, parsed and bounded by the surface before this class runs. */
    public record OnboardMerchantCommand(
            String idempotencyKey,
            UUID partyId,
            String legalName,
            String displayName,
            CurrencyCode settlementCurrency) {}

    private final MerchantStore<Connection> merchants;
    private final LedgerAccountStore<Connection> ledgerAccounts;
    private final MerchantVerification<Connection> verification;
    private final IdempotentExecutor executor;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public MerchantOnboarding(
            MerchantStore<Connection> merchants,
            LedgerAccountStore<Connection> ledgerAccounts,
            MerchantVerification<Connection> verification,
            IdempotentExecutor executor,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.merchants = Objects.requireNonNull(merchants, "merchants must not be null");
        this.ledgerAccounts =
                Objects.requireNonNull(ledgerAccounts, "ledgerAccounts must not be null");
        this.verification = Objects.requireNonNull(verification, "verification must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Onboards, or replays the recorded outcome for a retried key ({@code INV-IDEM-01}). */
    public OnboardingResult onboard(Connection unitOfWork, OnboardMerchantCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // An unestablished actor is an error, never a default (ADR-0021).
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // Refused before any lookup: a payable the residual account cannot exist for must not
        // exist for a millisecond (the AccountOpening reasoning, INV-BAL-03).
        if (!SupportedCurrencies.ALL.contains(command.settlementCurrency())) {
            throw new UnsupportedSettlementCurrencyException();
        }

        // The gate: one authoritative read, its answer conflating its causes (INV-IDN-07's
        // reasoning at this boundary - the refusal is not an oracle over parties).
        UUID verifiedParty =
                verification
                        .eligibleOrganisation(unitOfWork, command.partyId())
                        .map(ignored -> command.partyId())
                        .orElseThrow(MerchantNotEligibleException::new);

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, command.idempotencyKey());
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(canonicalForm(command, actor));

        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> accept(uow, command, verifiedParty, actor, correlation));

        String recorded =
                new String(
                        outcome.body()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a recorded onboarding outcome always"
                                                                + " carries the merchant id")),
                        StandardCharsets.UTF_8);
        return new OnboardingResult(
                MerchantId.of(UUID.fromString(recorded)), outcome.replayed());
    }

    /** The acceptance: the merchant, its books, the audit record and the event — one commit. */
    private CommandResult accept(
            Connection uow,
            OnboardMerchantCommand command,
            UUID verifiedParty,
            Actor actor,
            Correlation correlation) {
        Merchant merchant =
                Merchant.onboard(
                        ids,
                        clock,
                        verifiedParty,
                        command.legalName(),
                        command.displayName(),
                        command.settlementCurrency());
        merchants.insert(uow, merchant);

        // The books, in the same transaction (INV-MER-02): what the platform owes this
        // merchant is a LIABILITY position - and the only place the figure will ever exist.
        ledgerAccounts.createOrConverge(
                uow,
                LedgerAccount.owned(
                        ids,
                        clock,
                        AccountType.LIABILITY,
                        AccountPurpose.MERCHANT_PAYABLE,
                        merchant.settlementCurrency(),
                        merchant.id().value()));

        Instant now = Instant.now(clock);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        MerchantAuditAction.MERCHANT_ONBOARDED,
                        TARGET_TYPE,
                        merchant.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and enumerated names - never a balance (INV-AUD-02).
                        Optional.of(
                                "merchant=" + merchant.id()
                                        + ", party=" + merchant.partyRef()
                                        + ", currency="
                                        + merchant.settlementCurrency().code())));

        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        merchant.id(),
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                // Identifiers and enumerated names only (INV-AUD-02, plan section 10).
                EventPayload.of()
                        .with("settlementCurrency", merchant.settlementCurrency().code())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);

        return CommandResult.succeeded(
                StoredResponse.of(
                        merchant.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }

    /**
     * The fingerprint's subject: the actor and the request's meaning — a reused key from a
     * different operator, for a different party, name or currency is the distinct 409
     * ({@code INV-IDEM-03}), never a silent replay.
     */
    private static byte[] canonicalForm(OnboardMerchantCommand command, Actor actor) {
        return String.join(
                        "|",
                        IDEMPOTENCY_SCOPE,
                        actor.type().name(),
                        actor.id(),
                        command.partyId().toString(),
                        command.legalName(),
                        command.displayName(),
                        command.settlementCurrency().code())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The flow's correlation with the cause resolved — the {@code AccountOpening} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a merchant onboarding must run inside a"
                                                        + " correlation scope"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
