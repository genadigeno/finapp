package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountNotPostableException;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The credit line draw - T-drw (`P11-TSK-021`; {@code PHASE_11_PLAN.md} sections 7.1, 7.2, 12.6, 12.8 rule 1, 12.9 and
 * 12.10; ADR-0100 points 3, 4, 9 and 10, ADR-0096; {@code INV-LND-14}, {@code INV-LND-13}, {@code INV-LND-01},
 * {@code INV-IDEM-01}, {@code INV-BAL-05}). The borrower draws against their line to their wallet - never beyond the
 * available limit and never beyond the platform's lending capital - in <strong>one transaction</strong>, on the caller's
 * unit of work, which the door's idempotency claim ({@code lending.draw:CUSTOMER:<id>}) wraps: a retried key replays, and
 * every refusal is thrown before any write, so it writes nothing and rolls the claim back with it.
 *
 * <h2>The order, which is the lock order (L3, L6, L8)</h2>
 *
 * <ol>
 *   <li><strong>(L3)</strong> the line {@code FOR UPDATE}, owner-scoped - the serialisation point of every act on the
 *       account: two draws, a draw and a servicing step, a draw and (later) a repayment or a statement each wait here, and
 *       each derives after the other's commit;
 *   <li>a draw already born under the customer's key converges - the same amount answers it as a replay, another amount is
 *       the key reused ({@code UNIQUE (loan_id, draw_key)}, the arbiter behind a claim that has lapsed);
 *   <li>a credit line {@code ACTIVE} - never an instalment loan, a closing or a closed line ({@code LineNotDrawable});
 *   <li>the database clock, read after the lock: the value date is its business date in the agreement's pinned servicing
 *       zone (A10) - and the draw's suspension ({@link DrawSuspension}, past due or defaulted, A25) judged on it;
 *   <li>the party's standing, read in the transaction (plan section 11) - a suspended customer cannot draw;
 *   <li><strong>the available limit</strong> ({@link AvailableLimit}): the agreement's limit less
 *       {@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE}, both folded from the line's journal lines in ONE statement under L3 -
 *       never a projection, never a stored number ({@code INV-LND-14}, {@code INV-BAL-05}). Judged before the capital lock,
 *       so a draw past its own limit never queues on the currency's hot row;
 *   <li><strong>(L6)</strong> the currency's {@code LENDING_CAPITAL} row {@code FOR UPDATE} through
 *       {@link LendingCapitalHeadroom#underLock} and headroom &ge; the amount (A28, {@code INV-LND-13}) - after L3, before
 *       any wallet or projection row ({@code PHASE_11_PLAN.md} section 7.3 (c)); every acceptance, draw and
 *       principal-reinstating reversal in the currency serialises here, so two of them never both see the last capital
 *       free;
 *   <li>the wallet resolved through {@link LoanWallets} - draws go to the wallet only (A25) - after L6: resolving may
 *       open the wallet through accounts' doors, which writes wallet rows, and no wallet row is touched before the capital
 *       row;
 *   <li>posting rule 1 through {@link PostingService} under {@code lending.draw:<drawId>} - {@code DR LOAN_PRINCIPAL /
 *       CR CUSTOMER_WALLET}, {@code posting_date = value_date} - its projection rows last (L8). A wallet the ledger will
 *       not post refuses the draw ({@code WalletUnavailable});
 *   <li>then only inserts: the {@code credit_line_draw} row naming its entry and the agreement version it was judged
 *       against, and {@code lending.CreditLineDrawn} in the outbox.
 * </ol>
 *
 * <p><strong>No L0</strong>: the party's exposure does not move. An {@code ACTIVE} line already counts
 * {@code GREATEST(limit, drawn)} ({@code PlatformCreditExposure} v2, `-013`), and a draw admitted within the available limit
 * leaves the drawn principal at or below the limit - the figure stays the limit. <strong>Capital moves</strong>: the
 * headroom's outstanding term grows by the amount at this commit (A28: capital is the money deployed, exposure the
 * promise). <strong>No audit record</strong>: a customer's own draw is its row and its entry. No lock is held across
 * anything but the database.
 */
@RequiredArgsConstructor
public final class CreditLineDraws {

    public static final String DRAWN_EVENT = "lending.CreditLineDrawn";

    /** The ledger key's prefix (rule 1): one entry per draw, whoever posts it and however often. */
    public static final String POSTING_KEY_PREFIX = "lending.draw:";

    /**
     * The longest key a customer may draw under: the door's own bound ({@link IdempotencyKey#MAX_LENGTH}), which
     * {@code lending V013}'s {@code credit_line_draw_key_bounded} repeats for every writer.
     */
    public static final int MAX_DRAW_KEY_LENGTH = IdempotencyKey.MAX_LENGTH;

    /**
     * The drawn principal is every line on the two accounts, whatever its value date: no lending posting is dated after
     * the business date of its own commit, and the limit must never read a future-dated principal line as headroom.
     */
    static final LocalDate THROUGH_EVERY_VALUE_DATE = LocalDate.of(9999, 12, 31);

    @NonNull private final LoanStore loans;
    @NonNull private final CreditLineDrawStore draws;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final DrawSuspension suspension;
    @NonNull private final LoanPartyStanding standing;
    @NonNull private final LendingCapitalHeadroom headroom;
    @NonNull private final LoanWallets wallets;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What the customer asks: an amount, under their own key. */
    public record Request(String drawKey, Money amount) {

        public Request {
            Objects.requireNonNull(drawKey, "drawKey must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (drawKey.isEmpty() || drawKey.length() > MAX_DRAW_KEY_LENGTH) {
                throw new IllegalArgumentException("a draw key is 1 to " + MAX_DRAW_KEY_LENGTH + " characters");
            }
        }

        /** Never the key or the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Request[redacted]";
        }
    }

    /** What the draw committed - or, {@code replayed}, the draw a key retried after its claim lapsed converged on. */
    public record Drawn(CreditLineDraw draw, boolean replayed) {

        public Drawn {
            Objects.requireNonNull(draw, "draw must not be null");
        }
    }

    // ------------------------------------------------------------------ T-drw

    /**
     * Draws {@code request.amount()} on {@code party}'s line {@code id} to their wallet, on the caller's unit of work, inside
     * the caller's correlation and actor scopes (the posting carries both).
     *
     * @throws LineNotFound the party has no account with this id (another party's included)
     * @throws DrawKeyReused a draw under this key exists for another amount
     * @throws LineNotDrawable the account is not a credit line, or the line is not {@code ACTIVE}
     * @throws AmountInvalid the amount is not positive, or not in the line's currency and scale
     * @throws DrawsSuspended an amount is past due or the line is defaulted
     * @throws LoanApplications.NotInGoodStanding the party is not a verified customer in good standing now
     * @throws LimitExceeded the amount exceeds the available limit
     * @throws LoanAcceptances.CapitalUnavailable the currency's recognised capital cannot cover the amount
     * @throws WalletUnavailable the wallet cannot be had, or the ledger will not post to it
     */
    public Drawn draw(
            Connection unitOfWork, LoanId id, UUID party, Request request, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");

        // (L3) The line, owner-scoped: another party's account is never locked, and is the same answer as an absent one.
        LoanStore.Owned locked = loans.lockOwnedBy(unitOfWork, id, party).orElseThrow(LineNotFound::new);
        Loan loan = locked.loan();
        LoanAgreement agreement = locked.agreement();
        // A draw already born under this key: the arbiter behind a claim that has lapsed.
        Optional<CreditLineDraw> born = draws.findByKey(unitOfWork, id, request.drawKey());
        if (born.isPresent()) {
            if (!born.get().amount().equals(request.amount())) {
                throw new DrawKeyReused();
            }
            return new Drawn(born.get(), true);
        }
        if (loan.kind() != LoanKind.REVOLVING || loan.status() != LoanStatus.ACTIVE) {
            throw new LineNotDrawable(loan.product());
        }
        Money amount = request.amount();
        Money limitAmount = agreement.amount();
        if (!amount.isPositive() || !amount.currency().equals(limitAmount.currency())
                || amount.scale() != limitAmount.scale()) {
            throw new AmountInvalid();
        }
        // The database clock, read after the lock, in the agreement's pinned zone.
        Instant now = draws.databaseNow(unitOfWork);
        LoanTerms pinned = terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        LocalDate valueDate = LocalDate.ofInstant(now, ZoneId.of(pinned.servicingZone()));
        if (suspension.suspended(unitOfWork, id, valueDate)) {
            throw new DrawsSuspended();
        }
        if (!standing.inGoodStanding(unitOfWork, party)) {
            throw new LoanApplications.NotInGoodStanding();
        }
        // The available limit, derived from journal lines under L3 (INV-LND-14).
        CurrencyCode currency = loan.currency();
        LedgerAccountId receivable = owned(unitOfWork, id, AccountPurpose.LOAN_PRINCIPAL, currency);
        AvailableLimit available = new AvailableLimit(limitAmount, drawnPrincipal(unitOfWork, loan, limitAmount,
                receivable, owned(unitOfWork, id, AccountPurpose.LOAN_PRINCIPAL_DUE, currency)));
        if (!available.admits(amount)) {
            throw new LimitExceeded();
        }
        // (L6) The capital row, then headroom judged under it (INV-LND-13) - before any wallet row (section 7.3 (c)).
        if (!headroom.underLock(unitOfWork, currency).admits(amount)) {
            throw new LoanAcceptances.CapitalUnavailable(loan.product());
        }
        // Draws go to the wallet only (A25): resolved - opened through accounts' doors if absent - after L6.
        LedgerAccountId wallet = wallets.walletFor(unitOfWork, party, currency).orElseThrow(WalletUnavailable::new);
        UUID drawId = ids.next();
        JournalEntryId entry = post(unitOfWork, drawId, loan, amount, receivable, wallet, valueDate);
        CreditLineDraw unstamped = new CreditLineDraw(drawId, id, request.drawKey(), amount, agreement.id(),
                agreement.version(), entry, valueDate, now);
        Instant drawnAt = draws.insert(unitOfWork, unstamped, correlation);
        CreditLineDraw draw = new CreditLineDraw(drawId, id, request.drawKey(), amount, agreement.id(),
                agreement.version(), entry, valueDate, drawnAt);
        publish(unitOfWork, loan, draw, correlation);
        return new Drawn(draw, false);
    }

    // ------------------------------------------------------------------ the money

    /**
     * {@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE}, DR-CR over the line's journal lines read in ONE statement - a billing
     * moving principal from not-due to due is wholly in or wholly out - folded through {@link Money} at the agreement's
     * scale (never a SQL sum, {@code INV-MON-01}). Under L3 nothing else moves it.
     */
    private Money drawnPrincipal(
            Connection unitOfWork, Loan loan, Money limit, LedgerAccountId principal, LedgerAccountId principalDue) {
        Money drawn = Money.ofPersisted(0, loan.currency(), limit.scale());
        for (PurposeBalances.DatedLine line
                : balances.datedLinesOn(unitOfWork, Set.of(principal, principalDue), THROUGH_EVERY_VALUE_DATE)) {
            drawn = line.direction() == Direction.DEBIT ? drawn.plus(line.amount()) : drawn.minus(line.amount());
        }
        return drawn;
    }

    /**
     * Posting rule 1 for a draw: {@code DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET}, dated the value date, its reference the
     * line (rule 1's) - a fresh draw id per attempt, so a replay under its key is a defect, never a second posting.
     */
    private JournalEntryId post(
            Connection unitOfWork, UUID drawId, Loan loan, Money amount, LedgerAccountId receivable,
            LedgerAccountId wallet, LocalDate valueDate) {
        PostingResult result;
        try {
            result = postings.post(unitOfWork, new PostingCommand(POSTING_KEY_PREFIX + drawId, valueDate, valueDate,
                    loan.id().value().toString(), List.of(
                            new JournalLine(receivable, Direction.DEBIT, amount),
                            new JournalLine(wallet, Direction.CREDIT, amount))));
        } catch (LedgerAccountNotPostableException notPostable) {
            throw new WalletUnavailable();
        }
        if (result.replayed()) {
            throw new IllegalStateException("a draw's entry exists for a draw id minted in this transaction (INV-IDEM-01)");
        }
        return result.entryId();
    }

    private LedgerAccountId owned(Connection unitOfWork, LoanId loan, AccountPurpose purpose, CurrencyCode currency) {
        return ledgerAccounts.findOwned(unitOfWork, loan.value(), purpose, currency)
                .orElseThrow(() -> new IllegalStateException("the acceptance opened the line's six accounts"))
                .id();
    }

    // ------------------------------------------------------------------ the event

    /** {@code lending.CreditLineDrawn} - the draw, its entry, its amount and currency, the value date; no wallet. */
    private void publish(Connection unitOfWork, Loan loan, CreditLineDraw draw, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("drawId", draw.id().toString())
                .with("journalEntryId", draw.journalEntry().value().toString())
                .with("valueDate", draw.valueDate().toString())
                .with("amountMinor", Long.toString(draw.amount().minorUnits()))
                .with("scale", Integer.toString(draw.amount().scale()))
                .with("currency", draw.amount().currency().code())
                .with("agreementVersion", Integer.toString(draw.agreementVersion()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        DRAWN_EVENT,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        loan.id(),
                        LoanAcceptances.LOAN_AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The party has no account with this id - another party's included. */
    public static final class LineNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LineNotFound() {
            super("no such loan account");
        }
    }

    /** A draw was born under this key for another amount - the key reused after its claim lapsed. */
    public static final class DrawKeyReused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DrawKeyReused() {
            super("the key was already used for a different draw");
        }
    }

    /** The account is an instalment loan, or a line no longer {@code ACTIVE}. */
    public static final class LineNotDrawable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LoanProduct product;

        LineNotDrawable(LoanProduct product) {
            super("the account is not a credit line open for draws");
            this.product = product;
        }

        /** The account's product - for the meter. */
        public LoanProduct product() {
            return product;
        }
    }

    /** The amount is not positive, or not in the line's currency and scale. */
    public static final class AmountInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmountInvalid() {
            super("the amount is not a positive amount in the line's currency");
        }
    }

    /** An amount of the line is past due, or the line is defaulted (A25). */
    public static final class DrawsSuspended extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DrawsSuspended() {
            super("the line's draws are suspended");
        }
    }

    /** The amount exceeds the line's available limit ({@code INV-LND-14}). */
    public static final class LimitExceeded extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LimitExceeded() {
            super("the draw exceeds the line's available limit");
        }
    }

    /** The wallet cannot be had now, or the ledger will not post to it. */
    public static final class WalletUnavailable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        WalletUnavailable() {
            super("the wallet cannot receive the draw now");
        }
    }
}
