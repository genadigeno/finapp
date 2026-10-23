package com.finapp.risk;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The closed vocabulary of risk signals (`P13-TSK-005`; {@code PHASE_13_PLAN.md} sections 3.1, 10, 12.5;
 * {@code RISK_LIFECYCLES.md} section 4.4) - one member per consumed signal-recording event, each declaring the
 * {@link SubjectKind} its event is about, the event type that is its one source, and the typed, bounded attributes
 * the event carries. The sweep's own kinds arrive with the tasks that record them (`-020`, `-022`): nothing is
 * declared here that this task does not record, so a {@code CHECK}, a reading or a rule never admits a kind no
 * handler has ever written.
 *
 * <p><strong>Why the schema is here and not in the handler.</strong> The handler in {@code app} maps an event's
 * payload to a signal; this enum says what a signal of each kind may carry, so a mapping that drops an attribute,
 * adds one, or passes a value outside its bound is refused by {@link RiskSignal}'s constructor before any row
 * exists - and {@code risk V003}'s {@code CHECK risk_signal_attributes_match_kind} says the same thing to every
 * writer. Each attribute is one typed column of the table; there is no free-text column, no description and no
 * amount (OQ-4; {@code INV-AUD-02}; plan section 8).
 *
 * <p><strong>What the plan's list named that is not here, and why</strong> (the design's finding, 2026-10-23):
 * identity's {@code AuthenticationFailed} exists but names no identity by design ({@code INV-IDN-07}), so a signal from
 * it would have no subject; {@code AuthenticationLocked}, {@code MfaChallengeFailed}, {@code ContactChannelAdded} and
 * {@code SessionRotated} are audit actions, not events; kyc publishes no case outcome, and its counterparty screening
 * event names its subject only inside crossborder's request-reference convention - the screening's outcome reaches
 * risk as crossborder's own committed {@link #BENEFICIARY_ACTIVATED} and {@link #BENEFICIARY_BLOCKED}; merchant
 * publishes no enablement or suspension event, so {@link #MERCHANT_ONBOARDED} is its one lifecycle signal. Each
 * arrives as a kind here with the task that first records it - never before.
 */
public enum SignalKind {

    /** identity's credential changed - the ATO lookback's first element (`-013`). */
    CREDENTIAL_CHANGED(SubjectKind.IDENTITY, "identity.CredentialChanged"),

    /** A recovery of the identity began; the recovery request and the contact channel it went to, by id. */
    RECOVERY_INITIATED(SubjectKind.IDENTITY, "identity.RecoveryInitiated",
            Attribute.of("request_ref", AttributeType.REF), Attribute.of("channel_ref", AttributeType.REF)),

    /** The recovery completed - a credential was set through it. */
    RECOVERY_COMPLETED(SubjectKind.IDENTITY, "identity.RecoveryCompleted",
            Attribute.of("request_ref", AttributeType.REF), Attribute.of("channel_ref", AttributeType.REF)),

    /** The identity was suspended by an administrator; the status it now reads. */
    IDENTITY_SUSPENDED(SubjectKind.IDENTITY, "identity.IdentitySuspended", Attribute.of("status", AttributeType.CODE)),

    /** An account opened; its product - the account's age reading starts here. */
    ACCOUNT_OPENED(SubjectKind.ACCOUNT, "accounts.AccountOpened", Attribute.of("product", AttributeType.CODE)),

    /** A cross-border beneficiary registered - the new payee (`-013`); its country and currency. */
    BENEFICIARY_REGISTERED(SubjectKind.BENEFICIARY, "crossborder.BeneficiaryRegistered",
            Attribute.of("country", AttributeType.COUNTRY), Attribute.of("currency", AttributeType.CURRENCY)),

    /** The beneficiary's counterparty screening cleared, as crossborder committed it; the status it came from. */
    BENEFICIARY_ACTIVATED(SubjectKind.BENEFICIARY, "crossborder.BeneficiaryActivated",
            Attribute.of("country", AttributeType.COUNTRY), Attribute.of("currency", AttributeType.CURRENCY),
            Attribute.of("previous_status", AttributeType.CODE)),

    /** The beneficiary's counterparty screening hit, as crossborder committed it - blocked. */
    BENEFICIARY_BLOCKED(SubjectKind.BENEFICIARY, "crossborder.BeneficiaryBlocked",
            Attribute.of("country", AttributeType.COUNTRY), Attribute.of("currency", AttributeType.CURRENCY),
            Attribute.of("previous_status", AttributeType.CODE)),

    /** A merchant onboarded; its settlement currency - the merchant's age reading starts here. */
    MERCHANT_ONBOARDED(SubjectKind.MERCHANT, "merchant.MerchantOnboarded", Attribute.of("currency", AttributeType.CURRENCY)),

    /** A loan's delinquency bucket changed; the condition after it and the bucket before. */
    LOAN_DELINQUENCY_CHANGED(SubjectKind.CREDIT_AGREEMENT, "lending.LoanDelinquencyChanged",
            Attribute.of("product", AttributeType.CODE), Attribute.of("business_date", AttributeType.DATE),
            Attribute.of("days_past_due", AttributeType.DAYS), Attribute.of("bucket", AttributeType.CODE),
            Attribute.of("previous_bucket", AttributeType.CODE), Attribute.of("defaulted", AttributeType.FLAG),
            Attribute.of("agreement_version", AttributeType.VERSION)),

    /** A loan defaulted; the condition at the default. */
    LOAN_DEFAULTED(SubjectKind.CREDIT_AGREEMENT, "lending.LoanDefaulted",
            Attribute.of("product", AttributeType.CODE), Attribute.of("business_date", AttributeType.DATE),
            Attribute.of("days_past_due", AttributeType.DAYS), Attribute.of("bucket", AttributeType.CODE),
            Attribute.of("defaulted", AttributeType.FLAG), Attribute.of("agreement_version", AttributeType.VERSION)),

    /** A BNPL obligation's delinquency bucket changed. */
    BNPL_DELINQUENCY_CHANGED(SubjectKind.CREDIT_AGREEMENT, "bnpl.BnplDelinquencyChanged",
            Attribute.of("product", AttributeType.CODE), Attribute.of("business_date", AttributeType.DATE),
            Attribute.of("days_past_due", AttributeType.DAYS), Attribute.of("bucket", AttributeType.CODE),
            Attribute.of("previous_bucket", AttributeType.CODE), Attribute.of("defaulted", AttributeType.FLAG),
            Attribute.of("agreement_version", AttributeType.VERSION)),

    /** A BNPL obligation defaulted. */
    BNPL_DEFAULTED(SubjectKind.CREDIT_AGREEMENT, "bnpl.BnplDefaulted",
            Attribute.of("product", AttributeType.CODE), Attribute.of("business_date", AttributeType.DATE),
            Attribute.of("days_past_due", AttributeType.DAYS), Attribute.of("bucket", AttributeType.CODE),
            Attribute.of("defaulted", AttributeType.FLAG), Attribute.of("agreement_version", AttributeType.VERSION));

    /**
     * An attribute's type - each a bound the database carries as a typed column and a {@code CHECK}, and this enum
     * carries as the validation every value passes before a {@link RiskSignal} exists. Every canonical form is a
     * string of the payload grammar ({@code [A-Za-z0-9_-]{1,200}}), so nothing an event cannot carry can be an
     * attribute, and nothing an attribute holds can be a name, a description or an amount.
     */
    public enum AttributeType {
        /** A closed code of the producer's own vocabulary - an enumerated name, bounded, never enumerated here. */
        CODE("^[A-Z][A-Z0-9_]{0,63}$"),
        /** An ISO 3166-1 alpha-2 country code. */
        COUNTRY("^[A-Z]{2}$"),
        /** An ISO 4217 currency code. */
        CURRENCY("^[A-Z]{3}$"),
        /** An ISO-8601 date - a producer's business date. */
        DATE("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"),
        /** A count of days, 0 to 36500. */
        DAYS("^(0|[1-9][0-9]{0,4})$"),
        /** A boolean, {@code true} or {@code false}. */
        FLAG("^(true|false)$"),
        /** A version number, at least 1. */
        VERSION("^[1-9][0-9]{0,8}$"),
        /** A canonical UUID. */
        REF("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

        private final Pattern shape;

        AttributeType(String shape) {
            this.shape = Pattern.compile(shape);
        }

        /** Whether {@code value} is a value of this type, within its bound. */
        public boolean admits(String value) {
            if (value == null || !shape.matcher(value).matches()) {
                return false;
            }
            return switch (this) {
                case DATE -> parses(() -> LocalDate.parse(value));
                case DAYS -> Integer.parseInt(value) <= 36500;
                case REF -> parses(() -> UUID.fromString(value));
                default -> true;
            };
        }

        private static boolean parses(Runnable parse) {
            try {
                parse.run();
                return true;
            } catch (RuntimeException notOne) {
                return false;
            }
        }
    }

    /** One attribute of a kind: its name - the column it is stored in - and its type. */
    public record Attribute(String name, AttributeType type) {

        /** Every attribute column the table has - the union of every kind's schema, in the table's order. */
        public static final List<String> COLUMNS = List.of(
                "product", "status", "previous_status", "bucket", "previous_bucket", "country", "currency",
                "business_date", "days_past_due", "defaulted", "agreement_version", "channel_ref", "request_ref");

        private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,30}$");

        public Attribute {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("an attribute's name is a column name: " + name);
            }
        }

        public static Attribute of(String name, AttributeType type) {
            return new Attribute(name, type);
        }
    }

    /** Every attribute column the table has - {@link Attribute#COLUMNS}, named here for the store and the tests. */
    public static final List<String> ATTRIBUTE_COLUMNS = Attribute.COLUMNS;

    private final SubjectKind subject;
    private final String sourceEventType;
    private final List<Attribute> attributes;

    SignalKind(SubjectKind subject, String sourceEventType, Attribute... attributes) {
        this.subject = subject;
        this.sourceEventType = sourceEventType;
        this.attributes = List.of(attributes);
        for (Attribute attribute : attributes) {
            if (!Attribute.COLUMNS.contains(attribute.name())) {
                throw new IllegalStateException(attribute.name() + " is not a column of risk_signal");
            }
        }
        if (this.attributes.stream().map(Attribute::name).distinct().count() != this.attributes.size()) {
            throw new IllegalStateException("an attribute is declared twice on " + name());
        }
    }

    /** What a signal of this kind is about. */
    public SubjectKind subject() {
        return subject;
    }

    /** The one event type a signal of this kind is born from. */
    public String sourceEventType() {
        return sourceEventType;
    }

    /** The attributes a signal of this kind carries - every one required, nothing else admitted. */
    public List<Attribute> attributes() {
        return attributes;
    }

    /** The declared attribute of that name, if this kind has one. */
    public Optional<Attribute> attribute(String name) {
        return attributes.stream().filter(attribute -> attribute.name().equals(name)).findFirst();
    }

    /** The kind born from {@code eventType}, if any - each event type is one kind's source. */
    public static Optional<SignalKind> recordedFrom(String eventType) {
        return Arrays.stream(values()).filter(kind -> kind.sourceEventType.equals(eventType)).findFirst();
    }

    /** The quoted, comma-separated value list the migration's {@code CHECK} constraint uses. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
    }
}
