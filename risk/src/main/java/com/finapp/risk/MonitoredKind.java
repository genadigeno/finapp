package com.finapp.risk;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The kinds of a monitored transaction (`P13-TSK-019`; {@code PHASE_13_PLAN.md} section 12.7; ADR-0111 decision 2;
 * {@code INV-RSK-09}) - a closed vocabulary, and the <strong>posting-key namespace register</strong>: each kind names the
 * namespaces the ledger carries today that classify into it, read from every module's posting keys on the day this task
 * was built ({@code DESIGN-019} section 26). A {@code POSTING} entry whose namespace no kind names is
 * {@link #UNCLASSIFIED} - recorded, gauged and (by `-015`) alerted, never skipped: nothing passes unseen. A
 * {@code REVERSAL} and an {@code ADJUSTMENT} are classified by their entry type, whatever their key's namespace, because
 * the ledger's own type is the stronger fact ({@code INV-REV-01}).
 *
 * <p>A namespace is the posting key up to and including its first {@code ':'} ({@code transfer:}, {@code bnpl.billing:},
 * {@code lending.statement:}); a key with no {@code ':'} is its own namespace and is unknown here. Every member's list is
 * exact: {@code MonitoringClassificationTest} pins it, and {@code app}'s {@code PostingKeyNamespaceRegisterTest} fails
 * the build on a posting-key literal in any module's production source that no kind names.
 */
public enum MonitoredKind {

    TRANSFER("transfer:"),
    WITHDRAWAL("wallet-withdrawal:"),
    CARD_PAYMENT("payment-capture:"),
    PAYMENT_EXECUTION("payment-execution:"),
    PAYMENT_REFUND("payment-refund:"),
    UNMATCHED_CONFIRMATION("unmatched-confirmation:"),
    OUTBOUND_CREDIT("outbound-credit:"),
    CROSS_BORDER_RETURN("crossborder-return:", "crossborder-return-fee:"),
    MERCHANT_PAYOUT("merchant-payout:"),
    MERCHANT_PAYOUT_RETURN("merchant-payout-return:"),
    DISPUTE("dispute-chargeback:", "dispute-fee:", "dispute-loss:", "dispute-won:", "dispute-restoration:",
            "dispute-attribution:", "dispute-reattribution:"),
    SETTLEMENT("settlement-batch:"),
    RECONCILIATION_SUSPENSE("recon-suspense:"),
    FX_TRADE("fx-trade:"),
    FX_COVER("fx-cover:"),
    LOAN_DISBURSEMENT("lending.disbursement:", "lending.draw:"),
    LOAN_REPAYMENT("lending.repayment:"),
    LOAN_SERVICING("lending.accrual:", "lending.accrual-correction:", "lending.billing:", "lending.statement:",
            "lending.fee:", "lending.credit-application:", "lending.amendment:", "lending.closure-refund:"),
    LENDING_CAPITAL("lending.capital-contribution:"),
    BNPL_FINANCING("bnpl.financing:"),
    BNPL_REPAYMENT("bnpl.repayment:"),
    BNPL_REFUND("bnpl.refund:", "bnpl.closure-refund:"),
    BNPL_SERVICING("bnpl.billing:", "bnpl.accrual:", "bnpl.accrual-correction:", "bnpl.fee:", "bnpl.credit-application:",
            "bnpl.reversal-fee-release:"),
    /** A {@code REVERSAL} entry, whatever its key - it names the original it compensates. */
    REVERSAL(),
    /** An {@code ADJUSTMENT} entry, whatever its key - a person's reasoned correction. */
    ADJUSTMENT(),
    /** A {@code POSTING} whose namespace the register does not know. Recorded and gauged; never skipped. */
    UNCLASSIFIED();

    private static final Map<String, MonitoredKind> BY_NAMESPACE = Arrays.stream(values())
            .flatMap(kind -> kind.namespaces.stream().map(namespace -> Map.entry(namespace, kind)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private final List<String> namespaces;

    MonitoredKind(String... namespaces) {
        this.namespaces = List.of(namespaces);
    }

    /** The posting-key namespaces that classify into this kind - empty for the three kinds classified otherwise. */
    public List<String> namespaces() {
        return namespaces;
    }

    /** The kind a {@code POSTING} entry's namespace classifies into, if the register knows it. */
    public static Optional<MonitoredKind> ofNamespace(String namespace) {
        return Optional.ofNullable(BY_NAMESPACE.get(Objects.requireNonNull(namespace, "namespace")));
    }

    /** Every namespace the register knows, in declaration order. */
    public static List<String> registeredNamespaces() {
        return Arrays.stream(values()).flatMap(kind -> kind.namespaces.stream()).toList();
    }

    /** A posting key's namespace: up to and including the first {@code ':'}; the whole key when it has none. */
    public static String namespaceOf(String postingKey) {
        Objects.requireNonNull(postingKey, "postingKey");
        int colon = postingKey.indexOf(':');
        return colon < 0 ? postingKey : postingKey.substring(0, colon + 1);
    }

    /** The {@code CHECK}'s literal list, so the migration test holds the enum and the schema to one set. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
    }
}
