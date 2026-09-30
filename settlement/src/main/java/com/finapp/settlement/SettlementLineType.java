package com.finapp.settlement;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The closed set of canonical settlement line types (`P8-TSK-008`, ADR-0065 §2) — the
 * platform's vocabulary, never a provider's ({@code INV-PAY-03}: a provider code like the
 * simulated PSP's {@code SALE} lives only inside its format adapter).
 *
 * <p><strong>An unknown provider type is degraded, never dropped</strong> ({@code INV-REC-02}):
 * it becomes {@link #OTHER_IN} or {@link #OTHER_OUT} by its direction — and never a success
 * type, so nothing a counterparty invents can silently claim to be a capture. It survives
 * parsing to become a typed break at matching (`P8-TSK-011`), which is where a human sees it.
 */
public enum SettlementLineType {

    /** The counterparty reports our capture settled — allocates, posts nothing (ADR-0065 §2). */
    CAPTURE,

    /** Our refund, reported completed. */
    REFUND,

    /** A chargeback the network took. */
    CHARGEBACK,

    /** A chargeback returned — the dispute was won. */
    CHARGEBACK_REVERSAL,

    /** The network's dispute fee — posted at its stage, so this line allocates, never posts. */
    DISPUTE_FEE,

    /**
     * The counterparty's own fee for processing — the one thing hop 1 will post
     * (`P8-TSK-009`, ADR-0065 §2). Also what a gross-plus-fee source line's fee half becomes,
     * carrying {@code ORIGINAL_REF} to the transaction it rode in on.
     */
    PROCESSING_FEE,

    /** A correction the counterparty issues as a NEW line in a later batch — never an edit. */
    COUNTERPARTY_ADJUSTMENT,

    /** An inbound line no mapping knows — kept, typed as a break at matching, never a success. */
    OTHER_IN,

    /** An outbound line no mapping knows. */
    OTHER_OUT,

    /**
     * Money the settlement bank credited to the platform's account (`P8-TSK-016`, ADR-0065 §3) —
     * cash, recognised at the statement's acceptance against the ATTRIBUTED counterparty's
     * clearing position, then matched to that counterparty's remittance.
     */
    BANK_CREDIT,

    /** Money the settlement bank debited from the platform's account — cash leaving. */
    BANK_DEBIT,

    /**
     * The settlement bank's own charge (`P8-TSK-016`): posted DR {@code PROCESSING_COSTS} at the
     * recognition, so its line is checked against the pinned bank fee terms, never allocated.
     */
    BANK_FEE,

    /**
     * A credit the instant scheme reports settled into the platform in its cycle (`P8-TSK-017`)
     * — a pay-in's, or a parked execution's — allocating, posting nothing.
     */
    CREDIT_IN,

    /** A debit the instant scheme reports settled out of the platform — a withdrawal or return. */
    DEBIT_OUT,

    /**
     * The instant scheme's own charge (`P8-TSK-017`) — posted DR {@code PROCESSING_COSTS} at the
     * report's recognition like the PSP's fee, carrying {@code ORIGINAL_REF} to its entry.
     */
    SCHEME_FEE,

    /**
     * A payout the payout provider reports executed (`P8-TSK-018`) — money out of the platform to
     * a merchant's destination, allocating against its {@code MERCHANT_PAYOUT}, posting nothing.
     */
    PAYOUT_EXECUTED,

    /**
     * A payout the beneficiary bank returned (`P8-TSK-018`) — money back in, reaching only that
     * payout's own {@code PAYOUT_RETURN} (the operation-anchored rule), never the payout itself.
     */
    PAYOUT_RETURNED,

    /**
     * The payout provider's own charge (`P8-TSK-018`) — posted DR {@code PROCESSING_COSTS} at the
     * report's recognition, carrying {@code ORIGINAL_REF} to the payout it rode in on.
     */
    PAYOUT_FEE;

    /** The report vocabulary `V003` admitted; the bank members arrived with `V005`. */
    public static java.util.Set<SettlementLineType> reportVocabulary() {
        return java.util.EnumSet.range(CAPTURE, OTHER_OUT);
    }

    /** The vocabulary through the bank statement — what `V005` admitted. */
    public static java.util.Set<SettlementLineType> bankVocabulary() {
        return java.util.EnumSet.range(CAPTURE, BANK_FEE);
    }

    /** The vocabulary through the scheme's cycle report — what `V006` admitted. */
    public static java.util.Set<SettlementLineType> schemeVocabulary() {
        return java.util.EnumSet.range(CAPTURE, SCHEME_FEE);
    }

    /**
     * Whether this is a report's own fee — what hop 1's recognition posts, DR
     * {@code PROCESSING_COSTS} against the source's position (`P8-TSK-009`, `P8-TSK-017`,
     * `P8-TSK-018`). The bank's fee is hop 2's ({@code BankRecognition}).
     */
    public boolean isReportFee() {
        return this == PROCESSING_FEE || this == SCHEME_FEE || this == PAYOUT_FEE;
    }

    /** Whether this is a bank statement's line (`P8-TSK-016`). */
    public boolean isBankLine() {
        return this == BANK_CREDIT || this == BANK_DEBIT || this == BANK_FEE;
    }

    /** A subset's {@code CHECK} value list, in declaration order. */
    public static String sqlValueList(java.util.Set<SettlementLineType> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The whole {@code CHECK} value list (`V007`) — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
