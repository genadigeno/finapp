package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The FX provider boundary (`P9-TSK-006`, ADR-0075 §1-2, ADR-0077 §4, ADR-0008's port shape):
 * the counterparty the platform trades with to cover a customer's conversion.
 *
 * <p><strong>Two acts, two risks.</strong> {@link #firmQuote} moves no money: a quote is
 * information, so failover across providers is safe. {@link #execute} IS the trade, so a lost
 * response is never a "no" - only a definitive answer in so many words concludes anything, and
 * {@link #inquire} asks what became of our reference.
 *
 * <p><strong>Verdicts, never vocabulary</strong> ({@code INV-PAY-03}): no provider status,
 * path or field crosses this port; an implementation maps its wire onto these answers
 * <em>totally</em>, and every answer it does not understand - an unknown status, a 5xx, a
 * malformed body, a rate or an amount more precise than its type admits - is
 * {@code Indeterminate}. No mapping default is ever a success.
 *
 * <p><strong>Our reference is the subject</strong> ({@code INV-PAY-04}): the quote request's
 * {@code QR} and the execution's {@code T} travel on the wire as the idempotency key, and the
 * provider's contract - contract-tested against the simulator - is to <em>dedupe on our
 * reference before judging the quote's validity</em>, so re-sending {@code T} after the lock
 * lapsed returns the original execution if one happened (ADR-0077 §4).
 *
 * <p>Every answer that carries received bytes carries them verbatim, for the caller to retain as
 * evidence ({@code INV-HIST-02}); no answer's {@code toString} renders them.
 */
public interface FxProvider {

    /** The provider's code - its declaration's, its evidence's and its meter's. */
    String code();

    /** A firm quote for {@code request} - information, never money. */
    FirmQuoteAnswer firmQuote(FirmQuoteRequest request);

    /** Executes against a firm quote under our reference {@code T} - the money act. */
    ExecutionAnswer execute(ExecutionRequest request);

    /** What became of our reference {@code T}. */
    ExecutionAnswer inquire(String clientReference);

    /** A platform-minted reference: our quote request's {@code QR} or an execution's {@code T}. */
    Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    /** Validates a reference against {@link #REFERENCE}. */
    static String reference(String value) {
        Objects.requireNonNull(value, "reference must not be null");
        if (!REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("not a platform reference");
        }
        return value;
    }

    /** A firm-quote request: our reference, the pair, which side is fixed, and its exact amount. */
    record FirmQuoteRequest(
            String quoteRequestReference,
            CurrencyCode source,
            CurrencyCode destination,
            FixedSide fixedSide,
            Money amount) {

        public FirmQuoteRequest {
            reference(quoteRequestReference);
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(fixedSide, "fixedSide must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (source.equals(destination)) {
                throw new IllegalArgumentException("a quote converts between two currencies");
            }
            CurrencyCode fixed = fixedSide == FixedSide.FIXED_SOURCE ? source : destination;
            if (!amount.currency().equals(fixed)) {
                throw new IllegalArgumentException("the amount is in the fixed side's currency");
            }
        }
    }

    /** An execution request: our reference {@code T} and the provider's quote it executes. */
    record ExecutionRequest(
            String clientReference,
            String providerQuoteReference,
            CurrencyCode source,
            CurrencyCode destination,
            FixedSide fixedSide,
            Money amount) {

        public ExecutionRequest {
            reference(clientReference);
            Objects.requireNonNull(providerQuoteReference, "providerQuoteReference must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(fixedSide, "fixedSide must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }

    /** Why an answer could not be understood - every one of them {@code Indeterminate}. */
    enum Indeterminacy {
        /** The wait expired: the request may have reached the provider. */
        TIMEOUT,
        /** The transport broke mid-exchange. */
        TRANSPORT,
        /** A 5xx or another non-answer status. */
        SERVER_ERROR,
        /** A body that could not be read as an answer, or one past the retention bound. */
        MALFORMED,
        /** A status this adapter does not know. */
        UNKNOWN_STATE,
        /** A rate or an amount more precise than its type admits - refused, never rounded. */
        OVER_PRECISE
    }

    /** The answer to {@link #firmQuote}. */
    sealed interface FirmQuoteAnswer
            permits FirmQuoteAnswer.Quoted,
                    FirmQuoteAnswer.Declined,
                    FirmQuoteAnswer.NothingSent,
                    FirmQuoteAnswer.Indeterminate {

        /** The provider's firm quote: its reference, the priced facts and the value date. */
        record Quoted(
                String providerQuoteReference,
                ProviderQuote quote,
                LocalDate valueDate,
                Evidence evidence)
                implements FirmQuoteAnswer {
            public Quoted {
                Objects.requireNonNull(providerQuoteReference, "providerQuoteReference must not be null");
                Objects.requireNonNull(quote, "quote must not be null");
                Objects.requireNonNull(valueDate, "valueDate must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        /** The provider declined to quote, in so many words. */
        record Declined(DeclineReason reason, Evidence evidence) implements FirmQuoteAnswer {
            public Declined {
                Objects.requireNonNull(reason, "reason must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        /** Nothing was transmitted (a refused connection) - knowledge, not ambiguity. */
        record NothingSent() implements FirmQuoteAnswer {}

        /** No answer that can be acted on. */
        record Indeterminate(Indeterminacy cause, Optional<Evidence> evidence)
                implements FirmQuoteAnswer {
            public Indeterminate {
                Objects.requireNonNull(cause, "cause must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }
    }

    /** Why a provider declined to quote. */
    enum DeclineReason {
        PAIR_NOT_QUOTED,
        AMOUNT_OUT_OF_RANGE,
        MARKET_CLOSED
    }

    /** The answer to {@link #execute} and {@link #inquire}. */
    sealed interface ExecutionAnswer
            permits ExecutionAnswer.Executed,
                    ExecutionAnswer.Rejected,
                    ExecutionAnswer.Unrecognised,
                    ExecutionAnswer.NothingSent,
                    ExecutionAnswer.Indeterminate {

        /**
         * The provider executed our reference: its trade reference, the amounts it states it sold
         * and bought, its executed rate and value date - the facts the cover will copy, exact.
         */
        record Executed(
                String providerTradeReference,
                Money sold,
                Money bought,
                ExchangeRate executedRate,
                LocalDate valueDate,
                Evidence evidence)
                implements ExecutionAnswer {
            public Executed {
                Objects.requireNonNull(providerTradeReference, "providerTradeReference must not be null");
                Objects.requireNonNull(sold, "sold must not be null");
                Objects.requireNonNull(bought, "bought must not be null");
                Objects.requireNonNull(executedRate, "executedRate must not be null");
                Objects.requireNonNull(valueDate, "valueDate must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        /** A definitive refusal - the only answer that licenses minting a new reference. */
        record Rejected(RejectReason reason, Evidence evidence) implements ExecutionAnswer {
            public Rejected {
                Objects.requireNonNull(reason, "reason must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        /** The provider has never seen our reference - in so many words, inquiry only. */
        record Unrecognised(Evidence evidence) implements ExecutionAnswer {
            public Unrecognised {
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }

        /** Nothing was transmitted (a refused connection) - knowledge, not ambiguity. */
        record NothingSent() implements ExecutionAnswer {}

        /** No answer that can be acted on: re-send the same reference, or inquire. */
        record Indeterminate(Indeterminacy cause, Optional<Evidence> evidence)
                implements ExecutionAnswer {
            public Indeterminate {
                Objects.requireNonNull(cause, "cause must not be null");
                Objects.requireNonNull(evidence, "evidence must not be null");
            }
        }
    }

    /** Why a provider definitively refused an execution (ADR-0077 §4's requote triggers). */
    enum RejectReason {
        QUOTE_EXPIRED,
        PRICE_CHANGED,
        LIMIT
    }

    /** Received bytes, verbatim - for the caller to retain; never rendered. */
    record Evidence(byte[] bytes) {

        public Evidence {
            Objects.requireNonNull(bytes, "bytes must not be null");
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Evidence that && java.util.Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(bytes);
        }

        /** The length only - provider bytes never reach a log line. */
        @Override
        public String toString() {
            return "Evidence[" + bytes.length + " bytes]";
        }
    }
}
