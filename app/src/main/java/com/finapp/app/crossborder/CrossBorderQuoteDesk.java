package com.finapp.app.crossborder;

import com.finapp.app.api.DecimalText;
import com.finapp.app.fx.FxQuoteMetrics;
import com.finapp.crossborder.BeneficiaryId;
import com.finapp.crossborder.CrossBorderFx;
import com.finapp.crossborder.CrossborderErrorCode;
import com.finapp.crossborder.OfferIssuance;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.fx.FxErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryException;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The boundary half of the cross-border quote door (`P9-TSK-018`, PHASE_9_PLAN.md section 12.3): parse exactly,
 * key the request per principal, run the claim (Tx1), the re-screen and the firm quote between the
 * transactions, and the record (Tx2) - every refusal recorded on the claim, so a replay answers the same.
 * A refusal is crossborder's or fx's code, answered by name; nothing here prices or judges payability.
 */
@Slf4j
public final class CrossBorderQuoteDesk {

    static final String SCOPE = "crossborder.quote:";

    private final OfferIssuance offers;
    private final IdentityStore<Connection> identities;
    private final IdempotentExecutor executor;
    private final TransactionRunner transactions;
    private final FxQuoteMetrics metrics;
    private final Clock clock;

    public CrossBorderQuoteDesk(
            OfferIssuance offers,
            IdentityStore<Connection> identities,
            IdempotentExecutor executor,
            TransactionRunner transactions,
            FxQuoteMetrics metrics,
            Clock clock) {
        this.offers = Objects.requireNonNull(offers, "offers must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The offer as its owner sees it - every figure frozen; never the provider's rate or reference. */
    public record CrossBorderOfferView(
            String id,
            String beneficiaryId,
            String status,
            String fixedSide,
            String sourceCurrency,
            String destinationCurrency,
            String sourceAmount,
            String fee,
            String totalDebit,
            String destinationAmount,
            String customerRate,
            String disclosedMarginOverMid,
            long deliveryEstimateHours,
            String expiresAt) {}

    /** A refusal, by whose vocabulary it is. */
    private record Refusal(String vocabulary, String code) {}

    public CrossBorderOfferView quote(Session current, String idempotencyKey, CrossBorderQuoteController.CrossBorderQuoteRequest body) {
        Objects.requireNonNull(current, "current must not be null");
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        String claimKey = key.scope() + "|" + key.key();
        // The amount's shape before any connection is taken (the Phase 9 to 10 transition gate): an exponent such as
        // 1E+400000000 parses instantly and then costs minutes and gigabytes in the rescale - never inside Tx1.
        if (!DecimalText.plain(body.amount())) {
            throw malformed();
        }

        record Begun(IdempotentExecutor.BeginOutcome outcome, OfferIssuance.Claimed claimed, Refusal refusal) {}
        Begun begun = transactions.inTransaction(unitOfWork -> {
            UUID party = partyOf(unitOfWork, current);
            OfferIssuance.Ask ask = parse(unitOfWork, party, body);
            RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "crossborder.quote", party.toString(),
                            ask.beneficiary().value().toString(), ask.source().code(), Boolean.toString(ask.fixedSource()),
                            ask.amount().currency().code(), Long.toString(ask.amount().minorUnits()))
                    .getBytes(StandardCharsets.UTF_8));
            AtomicReference<OfferIssuance.Claimed> claimed = new AtomicReference<>();
            AtomicReference<Refusal> refused = new AtomicReference<>();
            IdempotentExecutor.BeginOutcome outcome = executor.begin(unitOfWork, key, fingerprint, claimedUnit -> {
                // A refusal can follow writes - the request row and kyc's re-screen are written before fx's claim
                // refuses (AMOUNT_OUT_OF_RANGE, TOO_MANY_OPEN_QUOTES) - so the claim runs under a savepoint and a
                // refusal rolls back to it: only the claim's refusal outcome commits (the Phase 9 to 10 transition
                // gate; CrossBorderPaymentDesk's pattern).
                Savepoint before = savepoint(claimedUnit);
                try {
                    claimed.set(offers.claim(claimedUnit, claimKey, ask, now(), correlation));
                } catch (OfferIssuance.OfferRefused refusal) {
                    refused.set(new Refusal("XB", refusal.code().name()));
                } catch (CrossBorderFx.FxRefused refusal) {
                    refused.set(new Refusal("FX", refusal.code()));
                }
                if (refused.get() != null) {
                    rollbackTo(claimedUnit, before);
                }
                return new byte[] {1};
            });
            if (refused.get() != null) {
                executor.complete(unitOfWork, key, false, failure(refused.get()));
            }
            return new Begun(outcome, claimed.get(), refused.get());
        });
        if (begun.outcome().replay().isPresent()) {
            return replayed(current, begun.outcome().replay().get());
        }
        if (begun.refusal() != null) {
            throw refused(begun.refusal());
        }

        OfferIssuance.Claimed claimed = begun.claimed();
        if (claimed.request().rescreen().isPresent()) {
            try {
                offers.rescreen(claimed, correlation);
            } catch (RuntimeException failure) {
                // The re-screen stays requested and due; the judgement below answers it as unavailable.
                log.warn("Cross-border re-screen failed: {}", failure.getClass().getSimpleName());
            }
            Refusal afterRescreen = transactions.inTransaction(unitOfWork -> {
                try {
                    offers.judgeRescreen(unitOfWork, claimed);
                    return null;
                } catch (OfferIssuance.OfferRefused refusal) {
                    Refusal recorded = new Refusal("XB", refusal.code().name());
                    executor.complete(unitOfWork, key, false, failure(recorded));
                    return recorded;
                }
            });
            if (afterRescreen != null) {
                throw refused(afterRescreen);
            }
        }

        CrossBorderFx.Sourcing sourcing = offers.firmQuote(claimed);

        record Recorded(OfferIssuance.Offered offered, Refusal refusal) {}
        Recorded recorded = transactions.inTransaction(unitOfWork -> {
            // fx's quote is issued before the corridor's maximum is judged on what it buys: a refusal rolls the issued
            // quote back with everything else, and only the claim's refusal outcome commits.
            Savepoint before = savepoint(unitOfWork);
            Refusal failed;
            try {
                OfferIssuance.Offered offered = offers.complete(unitOfWork, claimed, sourcing, actor, now(), correlation);
                executor.complete(unitOfWork, key, true,
                        StoredResponse.of(("OK|" + offered.quote().id()).getBytes(StandardCharsets.UTF_8), "text/plain"));
                return new Recorded(offered, null);
            } catch (OfferIssuance.OfferRefused refusal) {
                failed = new Refusal("XB", refusal.code().name());
            } catch (CrossBorderFx.FxRefused refusal) {
                failed = new Refusal("FX", refusal.code());
            }
            rollbackTo(unitOfWork, before);
            executor.complete(unitOfWork, key, false, failure(failed));
            return new Recorded(null, failed);
        });
        if (recorded.refusal() != null) {
            throw refused(recorded.refusal());
        }
        metrics.quoted(pair(recorded.offered()), "issued");
        return view(recorded.offered());
    }

    public CrossBorderOfferView read(Session current, String rawId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        return transactions.inTransaction(unitOfWork -> offers.read(unitOfWork, id, partyOf(unitOfWork, current)))
                .map(CrossBorderQuoteDesk::view)
                .orElseThrow(CrossBorderQuoteDesk::notFound);
    }

    // ------------------------------------------------------------------ plumbing

    private OfferIssuance.Ask parse(Connection unitOfWork, UUID party, CrossBorderQuoteController.CrossBorderQuoteRequest body) {
        try {
            BeneficiaryId beneficiary = BeneficiaryId.of(UUID.fromString(body.beneficiaryId()));
            CurrencyCode source = CurrencyCode.of(body.sourceCurrency());
            boolean fixedSource = switch (body.fixedSide()) {
                case "FIXED_SOURCE" -> true;
                case "FIXED_DESTINATION" -> false;
                default -> throw new IllegalArgumentException("fixedSide is FIXED_SOURCE or FIXED_DESTINATION");
            };
            return new OfferIssuance.Ask(party, beneficiary, source, fixedSource,
                    amountOf(unitOfWork, body, source, fixedSource, party, beneficiary));
        } catch (IllegalArgumentException | MonetaryException | ArithmeticException malformed) {
            // A fixed detail: a JDK or value-type message echoes the input and names internal classes.
            throw malformed();
        }
    }

    private static ApiException malformed() {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The cross-border quote request was not valid",
                "beneficiaryId is a beneficiary's identifier, sourceCurrency ISO 4217, fixedSide FIXED_SOURCE or"
                        + " FIXED_DESTINATION, and amount a plain decimal exact at the fixed side's minor units.");
    }

    private static Savepoint savepoint(Connection unitOfWork) {
        try {
            return unitOfWork.setSavepoint();
        } catch (SQLException failure) {
            throw new IllegalStateException("the quote's savepoint could not be set (SQLState " + failure.getSQLState() + ")");
        }
    }

    private static void rollbackTo(Connection unitOfWork, Savepoint savepoint) {
        try {
            unitOfWork.rollback(savepoint);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "a refused quote could not be rolled back to its savepoint (SQLState " + failure.getSQLState() + ")");
        }
    }

    /**
     * The fixed amount, exact at its currency's scale. A fixed destination is in the beneficiary's currency,
     * which the claim reads; the amount's currency is carried here as stated by the side, and the claim refuses
     * a mismatch.
     */
    private Money amountOf(
            Connection unitOfWork, CrossBorderQuoteController.CrossBorderQuoteRequest body, CurrencyCode source,
            boolean fixedSource, UUID party, BeneficiaryId beneficiary) {
        CurrencyCode currency = fixedSource
                ? source
                : offers.destinationOf(unitOfWork, beneficiary, party)
                        // Not the caller's, or unknown: the same answer as every non-payable beneficiary.
                        .orElseThrow(() -> refused(new Refusal("XB", CrossborderErrorCode.BENEFICIARY_NOT_PAYABLE.name())));
        return Money.of(DecimalText.parse(body.amount()), currency);
    }

    private static StoredResponse failure(Refusal refusal) {
        return StoredResponse.of(("ERR|" + refusal.vocabulary() + "|" + refusal.code()).getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private CrossBorderOfferView replayed(Session current, StoredResponse response) {
        String[] fields = new String(response.body(), StandardCharsets.UTF_8).split("\\|", -1);
        if (fields[0].equals("ERR")) {
            throw refused(new Refusal(fields[1], fields[2]));
        }
        return read(current, fields[1]);
    }

    private static ApiException refused(Refusal refusal) {
        return refusal.vocabulary().equals("FX")
                ? new ApiException(FxErrorCode.valueOf(refusal.code()), "The cross-border quote was refused")
                : new ApiException(CrossborderErrorCode.valueOf(refusal.code()), "The cross-border quote was refused");
    }

    private static ApiException notFound() {
        return new ApiException(CrossborderErrorCode.OFFER_NOT_FOUND, "No offer matches the requested identifier", "no such offer.");
    }

    private static String pair(OfferIssuance.Offered offered) {
        return offered.offer().source().currency().code() + "-" + offered.offer().destination().currency().code();
    }

    static CrossBorderOfferView view(OfferIssuance.Offered offered) {
        CrossBorderFx.Quote quote = offered.quote();
        return new CrossBorderOfferView(
                quote.id().toString(),
                offered.offer().beneficiary().value().toString(),
                quote.status(),
                quote.fixedSource() ? "FIXED_SOURCE" : "FIXED_DESTINATION",
                offered.offer().source().currency().code(),
                offered.offer().destination().currency().code(),
                offered.offer().source().toBigDecimal().toPlainString(),
                offered.offer().fee().toBigDecimal().toPlainString(),
                offered.offer().totalDebit().toBigDecimal().toPlainString(),
                offered.offer().destination().toBigDecimal().toPlainString(),
                quote.customerRate().toPlainString(),
                quote.disclosedMarginOverMid().toPlainString(),
                offered.offer().deliveryEstimateHours(),
                quote.expiresAt().toString());
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
                .orElseThrow(() -> new IllegalStateException("a cross-border quote runs inside a correlation scope"))
                .correlationId();
    }
}
