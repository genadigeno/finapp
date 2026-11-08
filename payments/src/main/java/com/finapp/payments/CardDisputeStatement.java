package com.finapp.payments;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The card PSP's own dispute statement read off the wire (`P15-TSK-036`, {@code INV-PAY-03}): the fields the webhook door
 * read until the inquiry replaced the callback as the source. Total: anything the statement needs and this cannot read is
 * an {@code INDETERMINATE} answer, never half a statement.
 */
final class CardDisputeStatement {

    private static final Pattern OPERATION = field("operation");
    private static final Pattern DISPUTE = field("dispute");
    private static final Pattern STAGE = field("stage");
    private static final Pattern REASON = field("reasonCode");
    private static final Pattern AMOUNT = field("amountMinor");
    private static final Pattern CURRENCY = field("currency");
    private static final Pattern FEE = field("feeMinor");
    private static final Pattern RESPOND_BY = field("respondBy");
    private static final Pattern SCALE = Pattern.compile("\"scale\"\\s*:\\s*([0-9]{1,2})\\b");

    /** Minor units as the wire carries them: digits only, and short enough for a long. */
    private static final Pattern MINOR_UNITS = Pattern.compile("[0-9]{1,18}");

    private CardDisputeStatement() {}

    static DisputeAnswer answerOf(String text, String provider, ProviderReference asked, byte[] received) {
        Optional<ProviderIdempotencyReference> operation = value(text, OPERATION).flatMap(CardDisputeStatement::ourReference);
        // An answer about a dispute other than the one asked is not an answer to this question.
        boolean aboutAsked = value(text, DISPUTE).filter(asked.value()::equals).isPresent();
        Optional<DisputeStage> stage = value(text, STAGE).flatMap(CardDisputeVocabulary::stage);
        Optional<Money> amount = amount(text);
        if (operation.isEmpty() || !aboutAsked || stage.isEmpty() || amount.isEmpty()) {
            return DisputeAnswer.indeterminate(received);
        }
        Optional<Optional<Money>> fee = fee(text, amount.get());
        Optional<Optional<Instant>> respondBy = respondBy(text, stage.get());
        if (fee.isEmpty() || respondBy.isEmpty()) {
            return DisputeAnswer.indeterminate(received);
        }
        try {
            return DisputeAnswer.found(
                    operation.get(),
                    new DisputeNotice(
                            provider,
                            asked,
                            stage.get(),
                            CardDisputeVocabulary.reason(value(text, REASON).orElse(null)),
                            amount.get(),
                            fee.get(),
                            respondBy.get()),
                    received);
        } catch (IllegalArgumentException incoherent) {
            // A fee on an inquiry: the statement contradicts itself, so it is not knowledge.
            return DisputeAnswer.indeterminate(received);
        }
    }

    private static Optional<ProviderIdempotencyReference> ourReference(String value) {
        try {
            return Optional.of(new ProviderIdempotencyReference(value));
        } catch (IllegalArgumentException notOurs) {
            return Optional.empty();
        }
    }

    /**
     * The disputed amount in the wire's own shape, total: digits only, a currency we know, and exactly that currency's
     * scale - an amount created now from an external statement, so {@code ofMinorUnits}, never a scale taken on trust.
     */
    private static Optional<Money> amount(String text) {
        Optional<String> minor = value(text, AMOUNT).filter(digits -> MINOR_UNITS.matcher(digits).matches());
        Optional<String> code = value(text, CURRENCY);
        Optional<String> scale = value(text, SCALE);
        if (minor.isEmpty() || code.isEmpty() || scale.isEmpty()) {
            return Optional.empty();
        }
        try {
            CurrencyCode currency = CurrencyCode.of(code.get());
            if (Integer.parseInt(scale.get()) != currency.minorUnits()) {
                return Optional.empty();
            }
            Money amount = Money.ofMinorUnits(Long.parseLong(minor.get()), currency);
            return amount.isPositive() ? Optional.of(amount) : Optional.empty();
        } catch (RuntimeException unusable) {
            return Optional.empty();
        }
    }

    /** Absent or zero is no fee (outer present, inner empty); digits that do not parse are unusable (outer empty). */
    private static Optional<Optional<Money>> fee(String text, Money amount) {
        Optional<String> minor = value(text, FEE);
        if (minor.isEmpty()) {
            return Optional.of(Optional.empty());
        }
        if (!MINOR_UNITS.matcher(minor.get()).matches()) {
            return Optional.empty();
        }
        try {
            Money fee = Money.ofMinorUnits(Long.parseLong(minor.get()), amount.currency());
            return Optional.of(fee.isZero() ? Optional.empty() : Optional.of(fee));
        } catch (RuntimeException unusable) {
            return Optional.empty();
        }
    }

    /** The network's representment deadline: dropped on an inquiry (out of scope), unusable if unparseable. */
    private static Optional<Optional<Instant>> respondBy(String text, DisputeStage stage) {
        Optional<String> stated = value(text, RESPOND_BY);
        if (stated.isEmpty() || !stage.isChargedBack()) {
            return Optional.of(Optional.empty());
        }
        try {
            return Optional.of(Optional.of(Instant.parse(stated.get())));
        } catch (DateTimeParseException unusable) {
            return Optional.empty();
        }
    }

    private static Pattern field(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"");
    }

    private static Optional<String> value(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
