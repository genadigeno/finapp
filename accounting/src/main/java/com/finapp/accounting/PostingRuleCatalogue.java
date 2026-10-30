package com.finapp.accounting;

import static com.finapp.accounting.DatingClass.ECONOMIC_DATE;
import static com.finapp.accounting.DatingClass.STORED_ROW;
import static com.finapp.accounting.DatingClass.TODAY_CLOCK;
import static com.finapp.accounting.DatingClass.TODAY_DATABASE;
import static com.finapp.accounting.EntryType.ADJUSTMENT;
import static com.finapp.accounting.EntryType.POSTING;
import static com.finapp.accounting.EntryType.REVERSAL;
import static com.finapp.accounting.LineShape.optional;
import static com.finapp.accounting.LineShape.required;
import static com.finapp.accounting.Side.CREDIT;
import static com.finapp.accounting.Side.DEBIT;
import static com.finapp.accounting.Side.EITHER;

import java.util.ArrayList;
import java.util.List;

/**
 * The catalogue of {@link PostingRuleRegister#CURRENT} (`P14-TSK-002`), read posting site by posting site at master
 * {@code 0bfac06d}: every {@code new PostingCommand(}, {@code new ReversalCommand(}, {@code new OwnedAdjustmentCommand(}
 * and {@code new AdjustmentCommand(} in production code - 41 files in nine modules - and the documents each shape is held
 * against ({@code LEDGER_MODEL.md} sections 9 to 11, ADR-0096 section 2, ADR-0103, ADR-0050, ADR-0061, ADR-0071, the
 * settlement, reconciliation and dispute lifecycles). Where a document and the code disagreed the code is registered
 * and the document corrected ({@code PHASE_14_PLAN.md} section 13.2's erratum).
 *
 * <p><strong>Dating</strong> - where each rule's posting date comes from, the fact `P14-TSK-006`'s cutoff and owner
 * decision C3 turn on: {@link DatingClass#TODAY_CLOCK} the poster's UTC instance clock; {@link DatingClass#TODAY_DATABASE}
 * the database clock in the servicing zone (lending, bnpl - lending's principal facts floored forward, never back, by
 * {@code principalValueDate}); {@link DatingClass#STORED_ROW} a date stamped on a source row and possibly days old when
 * the entry posts; {@link DatingClass#ECONOMIC_DATE} the event's own date - lending's accrual and the manual adjustment's
 * proposer-stated accounting date. The last two are the back-dating posters.
 */
final class PostingRuleCatalogue {

    // ------------------------------------------------------------------ purposes, as the ledger names them

    static final String CUSTOMER_WALLET = "CUSTOMER_WALLET";
    static final String MERCHANT_PAYABLE = "MERCHANT_PAYABLE";
    static final String SETTLEMENT_CLEARING = "SETTLEMENT_CLEARING";
    static final String INSTANT_CLEARING = "INSTANT_CLEARING";
    static final String PAYOUT_CLEARING = "PAYOUT_CLEARING";
    static final String FX_PROVIDER_CLEARING = "FX_PROVIDER_CLEARING";
    static final String CORRIDOR_CLEARING = "CORRIDOR_CLEARING";
    static final String CASH_AT_BANK = "CASH_AT_BANK";
    static final String SUSPENSE_UNMATCHED = "SUSPENSE_UNMATCHED";
    static final String PROCESSING_COSTS = "PROCESSING_COSTS";
    static final String RECONCILIATION_LOSSES = "RECONCILIATION_LOSSES";
    static final String RECONCILIATION_GAINS = "RECONCILIATION_GAINS";
    static final String CHARGEBACK_RECOVERABLE = "CHARGEBACK_RECOVERABLE";
    static final String DISPUTE_COSTS = "DISPUTE_COSTS";
    static final String FEE_REVENUE = "FEE_REVENUE";
    static final String FX_POSITION = "FX_POSITION";
    static final String FX_SPREAD_REVENUE = "FX_SPREAD_REVENUE";
    static final String ROUNDING_RESIDUAL = "ROUNDING_RESIDUAL";
    static final String FX_REALISED_GAINS = "FX_REALISED_GAINS";
    static final String FX_REALISED_LOSSES = "FX_REALISED_LOSSES";
    static final String LENDING_CAPITAL = "LENDING_CAPITAL";
    static final String LOAN_PRINCIPAL = "LOAN_PRINCIPAL";
    static final String LOAN_PRINCIPAL_DUE = "LOAN_PRINCIPAL_DUE";
    static final String LOAN_INTEREST_ACCRUED = "LOAN_INTEREST_ACCRUED";
    static final String LOAN_INTEREST_DUE = "LOAN_INTEREST_DUE";
    static final String LOAN_FEES_DUE = "LOAN_FEES_DUE";
    static final String LOAN_CREDIT_BALANCE = "LOAN_CREDIT_BALANCE";
    static final String LOAN_INTEREST_INCOME = "LOAN_INTEREST_INCOME";
    static final String LOAN_FEE_INCOME = "LOAN_FEE_INCOME";
    static final String BNPL_PRINCIPAL = "BNPL_PRINCIPAL";
    static final String BNPL_PRINCIPAL_DUE = "BNPL_PRINCIPAL_DUE";
    static final String BNPL_INTEREST_ACCRUED = "BNPL_INTEREST_ACCRUED";
    static final String BNPL_INTEREST_DUE = "BNPL_INTEREST_DUE";
    static final String BNPL_FEES_DUE = "BNPL_FEES_DUE";
    static final String BNPL_CREDIT_BALANCE = "BNPL_CREDIT_BALANCE";
    static final String BNPL_MERCHANT_FEE_INCOME = "BNPL_MERCHANT_FEE_INCOME";
    static final String BNPL_INTEREST_INCOME = "BNPL_INTEREST_INCOME";
    static final String BNPL_FEE_INCOME = "BNPL_FEE_INCOME";

    /** The clearing positions a settlement source discharges, one per counterparty kind (ADR-0065, ADR-0078). */
    static final List<String> SOURCE_POSITIONS = List.of(
            SETTLEMENT_CLEARING, INSTANT_CLEARING, PAYOUT_CLEARING, FX_PROVIDER_CLEARING, CORRIDOR_CLEARING);

    /** What a reconciliation resolution holds: a parked suspense item, or an expectation's remainder on its position. */
    static final List<String> RESOLUTION_HOLDINGS = List.of(
            SUSPENSE_UNMATCHED, SETTLEMENT_CLEARING, INSTANT_CLEARING, PAYOUT_CLEARING, FX_PROVIDER_CLEARING,
            CORRIDOR_CLEARING);

    /**
     * The purposes open to a free ({@code MANUAL}) adjustment - every purpose outside the ledger's
     * {@code AccountPurpose.closedToFreeAdjustments()}, which {@code PostingRuleRegisterTest} holds this list equal to.
     */
    static final List<String> OPEN_TO_FREE_ADJUSTMENT = List.of(
            CUSTOMER_WALLET, MERCHANT_PAYABLE, CHARGEBACK_RECOVERABLE, DISPUTE_COSTS, FEE_REVENUE);

    private static final String LOAN_DUE = "the due accounts per allocation";

    private PostingRuleCatalogue() {}

    static List<PostingRule> rules() {
        List<PostingRule> rules = new ArrayList<>();
        transfers(rules);
        payments(rules);
        disputes(rules);
        crossBorder(rules);
        merchant(rules);
        settlementAndReconciliation(rules);
        fx(rules);
        lending(rules);
        bnpl(rules);
        adjustments(rules);
        return List.copyOf(rules);
    }

    // ------------------------------------------------------------------ transfers

    private static void transfers(List<PostingRule> rules) {
        rules.add(rule("TRANSFER", POSTING, "transfer:", "transfers", posters("TransferExecution"),
                "An internal transfer between two customer wallets",
                "transfers.transfer (the transfer id)", TODAY_CLOCK,
                "TransferExecution; PHASE_14_PLAN.md 13.2 (erratum: no fee lines)",
                required(DEBIT, CUSTOMER_WALLET), required(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("TRANSFER-REVERSAL", REVERSAL, "transfer:", "transfers", posters("TransferReversal"),
                "An approved transfer reversal - the exact mirror of the transfer's entry",
                "transfers.transfer (the transfer id)", TODAY_CLOCK,
                "TransferReversal; LEDGER_MODEL.md 6",
                required(DEBIT, CUSTOMER_WALLET), required(CREDIT, CUSTOMER_WALLET)));
    }

    // ------------------------------------------------------------------ payments

    private static void payments(List<PostingRule> rules) {
        rules.add(rule("CARD-CAPTURE-TOPUP", POSTING, "payment-capture:", "payments", posters("PaymentOutcomes"),
                "A card wallet top-up captured",
                "payments.payment_attempt (the attempt id)", TODAY_CLOCK,
                "PAYMENT_LIFECYCLES.md capture; SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md 2 (CARD_CAPTURE)",
                required(DEBIT, SETTLEMENT_CLEARING), required(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("CARD-CAPTURE-MERCHANT", POSTING, "payment-capture:", "payments", posters("PaymentOutcomes"),
                "A merchant-bound card checkout captured, the platform's fee taken out of the payable in the same entry",
                "payments.payment_attempt (the attempt id)", TODAY_CLOCK,
                "ADR-0050 2 and 6; MerchantSettlement",
                required(DEBIT, SETTLEMENT_CLEARING), required(CREDIT, MERCHANT_PAYABLE),
                optional(DEBIT, MERCHANT_PAYABLE), optional(CREDIT, FEE_REVENUE)));
        rules.add(rule("PUSH-EXECUTION-TOPUP", POSTING, "payment-execution:", "payments", posters("PaymentOutcomes"),
                "An instant-scheme pay-in executed into the customer's wallet",
                "payments.payment_attempt (the attempt id)", TODAY_CLOCK,
                "SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md 2 (PUSH_PAY_IN); ADR-0059 4",
                required(DEBIT, INSTANT_CLEARING), required(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("PUSH-EXECUTION-MERCHANT", POSTING, "payment-execution:", "payments",
                posters("PaymentOutcomes"),
                "A merchant-bound payment executed on the instant scheme or the book rail (the payer's wallet the"
                        + " counterpart), the fee split as a capture's",
                "payments.payment_attempt (the attempt id)", TODAY_CLOCK,
                "ADR-0059 4 and 6; ADR-0050; MerchantSettlement",
                required(DEBIT, INSTANT_CLEARING, CUSTOMER_WALLET), required(CREDIT, MERCHANT_PAYABLE),
                optional(DEBIT, MERCHANT_PAYABLE), optional(CREDIT, FEE_REVENUE)));
        rules.add(rule("REFUND-TOPUP", POSTING, "payment-refund:", "payments", posters("PaymentOutcomes"),
                "A wallet top-up refunded - a card refund, or an instant-scheme return payment",
                "payments.refund (the refund id)", TODAY_CLOCK,
                "PAYMENT_LIFECYCLES.md refund; SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md 2 (CARD_REFUND, PUSH_RETURN)",
                required(DEBIT, CUSTOMER_WALLET), required(CREDIT, SETTLEMENT_CLEARING, INSTANT_CLEARING)));
        rules.add(rule("REFUND-MERCHANT", POSTING, "payment-refund:", "payments", posters("PaymentOutcomes"),
                "A merchant-bound payment refunded out of the payable - to the rail's clearing, or on the book rail to"
                        + " the payer's wallet - its fee share returned under a RETURNED policy",
                "payments.refund (the refund id)", TODAY_CLOCK,
                "ADR-0050 6; MerchantSettlement (RETAINED / RETURNED); ADR-0059 6",
                required(DEBIT, MERCHANT_PAYABLE),
                required(CREDIT, SETTLEMENT_CLEARING, INSTANT_CLEARING, CUSTOMER_WALLET),
                optional(DEBIT, FEE_REVENUE), optional(CREDIT, MERCHANT_PAYABLE)));
        rules.add(rule("UNMATCHED-CONFIRMATION", POSTING, "unmatched-confirmation:", "payments",
                posters("UnmatchedConfirmations"),
                "An instant-scheme credit no payment explains, parked on receipt",
                "payments.scheme_execution_claim (rail and scheme reference)", TODAY_CLOCK,
                "SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md 2 (UNMATCHED_CONFIRMATION)",
                required(DEBIT, INSTANT_CLEARING), required(CREDIT, SUSPENSE_UNMATCHED)));
        rules.add(rule("WALLET-WITHDRAWAL", POSTING, "wallet-withdrawal:", "payments", posters("WithdrawalOutcomes"),
                "A wallet withdrawal completed on the instant scheme - also lending's external payout (ADR-0096 rule 12)",
                "payments.withdrawal (the withdrawal id)", TODAY_CLOCK,
                "SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md 2 (PUSH_WITHDRAWAL); PHASE_14_PLAN.md 13.2 (erratum:"
                        + " INSTANT_CLEARING, not PAYOUT_CLEARING)",
                required(DEBIT, CUSTOMER_WALLET), required(CREDIT, INSTANT_CLEARING)));
    }

    // ------------------------------------------------------------------ disputes (ADR-0061)

    private static void disputes(List<PostingRule> rules) {
        String stages = "RAIL_AND_DISPUTE_LIFECYCLES.md stage table; ADR-0061";
        String dispute = "payments.dispute (the dispute id)";
        rules.add(rule("DISPUTE-CHARGEBACK", POSTING, "dispute-chargeback:", "payments",
                posters("ChargebackAccounting"), "A chargeback - the external fact",
                dispute, TODAY_CLOCK, stages,
                required(DEBIT, CHARGEBACK_RECOVERABLE), required(CREDIT, SETTLEMENT_CLEARING)));
        rules.add(rule("DISPUTE-ATTRIBUTION", POSTING, "dispute-attribution:", "payments",
                posters("ChargebackAccounting"), "The counterparty's posted share of a chargeback attributed to it",
                dispute, TODAY_CLOCK, stages,
                required(DEBIT, MERCHANT_PAYABLE, CUSTOMER_WALLET), required(CREDIT, CHARGEBACK_RECOVERABLE)));
        rules.add(rule("DISPUTE-WON", POSTING, "dispute-won:", "payments", posters("ChargebackAccounting"),
                "A dispute won - the chargeback reversed by the network",
                dispute, TODAY_CLOCK, stages,
                required(DEBIT, SETTLEMENT_CLEARING), required(CREDIT, CHARGEBACK_RECOVERABLE)));
        rules.add(rule("DISPUTE-RESTORATION", POSTING, "dispute-restoration:", "payments",
                posters("ChargebackAccounting"), "A won dispute's attributed share restored to the counterparty",
                dispute, TODAY_CLOCK, stages,
                required(DEBIT, CHARGEBACK_RECOVERABLE), required(CREDIT, MERCHANT_PAYABLE, CUSTOMER_WALLET)));
        rules.add(rule("DISPUTE-LOSS", POSTING, "dispute-loss:", "payments", posters("ChargebackAccounting"),
                "A dispute lost or accepted - the excess nobody bears expensed",
                dispute, TODAY_CLOCK, stages,
                required(DEBIT, DISPUTE_COSTS), required(CREDIT, CHARGEBACK_RECOVERABLE)));
        rules.add(rule("DISPUTE-FEE", POSTING, "dispute-fee:", "payments", posters("ChargebackAccounting"),
                "The PSP's dispute fee, once, when reported",
                dispute, TODAY_CLOCK, stages,
                required(DEBIT, DISPUTE_COSTS), required(CREDIT, SETTLEMENT_CLEARING)));
        rules.add(rule("DISPUTE-REATTRIBUTION", POSTING, "dispute-reattribution:", "payments",
                posters("ChargebackAccounting"),
                "A standing chargeback's headroom freed - a capture landed, a counted refund failed, a sibling won - and"
                        + " re-attributed to the counterparty",
                dispute, TODAY_CLOCK, stages,
                required(DEBIT, MERCHANT_PAYABLE, CUSTOMER_WALLET),
                required(CREDIT, CHARGEBACK_RECOVERABLE, DISPUTE_COSTS)));
    }

    // ------------------------------------------------------------------ cross-border (LEDGER_MODEL.md 9)

    private static void crossBorder(List<PostingRule> rules) {
        rules.add(rule("OUTBOUND-CREDIT", POSTING, "outbound-credit:", "payments", posters("OutboundCreditOutcomes"),
                "A cross-border credit accepted by the corridor provider: the hold released, the conversion booked onto"
                        + " this entry (no separate fx-trade: entry), the fee recognised",
                "payments.outbound_credit (the credit id)", TODAY_CLOCK,
                "LEDGER_MODEL.md 9; ConversionLines.composeCrossBorder",
                required(DEBIT, CUSTOMER_WALLET), optional(CREDIT, FEE_REVENUE), required(CREDIT, FX_POSITION),
                required(DEBIT, FX_POSITION), required(CREDIT, CORRIDOR_CLEARING),
                optional(CREDIT, FX_SPREAD_REVENUE), optional(EITHER, ROUNDING_RESIDUAL)));
        rules.add(rule("CROSSBORDER-RETURN", POSTING, "crossborder-return:", "payments",
                posters("OutboundCreditReturns"),
                "A cross-border return applied from evidence: the returned amount to the wallet in its currency, the"
                        + " fee refunded in the source currency; nothing re-converted",
                "payments.outbound_credit (the credit id)", TODAY_CLOCK,
                "LEDGER_MODEL.md 9; CrossBorderCompletion.returnLines",
                required(DEBIT, CORRIDOR_CLEARING), required(CREDIT, CUSTOMER_WALLET),
                optional(DEBIT, FEE_REVENUE), optional(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("CROSSBORDER-RETURN-FEE", POSTING, "crossborder-return-fee:", "app",
                posters("CorridorReturnResolutions"),
                "A parked cross-border return resolved four-eyes: the fee refunded (the principal is the resolution's"
                        + " own RECON-TRANSFER adjustment)",
                "payments.outbound_credit (the credit id)", TODAY_CLOCK,
                "LEDGER_MODEL.md 9",
                required(DEBIT, FEE_REVENUE), required(CREDIT, CUSTOMER_WALLET)));
    }

    // ------------------------------------------------------------------ merchant

    private static void merchant(List<PostingRule> rules) {
        rules.add(rule("MERCHANT-PAYOUT", POSTING, "merchant-payout:", "merchant", posters("MerchantPayoutOutcomes"),
                "A merchant payout accepted by the rail - instructed, not settled; also BNPL's F10",
                "merchant.payout (the payout id)", TODAY_CLOCK,
                "CHECKOUT_MERCHANT_LIFECYCLES.md payout; LEDGER_MODEL.md 11 (F10)",
                required(DEBIT, MERCHANT_PAYABLE), required(CREDIT, PAYOUT_CLEARING)));
        rules.add(rule("MERCHANT-PAYOUT-RETURN", POSTING, "merchant-payout-return:", "merchant",
                posters("PayoutReturns"),
                "A payout returned by the beneficiary bank, applied from the payout report's evidence",
                "merchant.payout_return (the payout id)", STORED_ROW,
                "SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md 5.10 (dated the item's batch accepted_on)",
                required(DEBIT, PAYOUT_CLEARING), required(CREDIT, MERCHANT_PAYABLE)));
    }

    // ------------------------------------------------------------------ settlement and reconciliation

    private static void settlementAndReconciliation(List<PostingRule> rules) {
        rules.add(rule("SETTLEMENT-REPORT", POSTING, "settlement-batch:", "settlement", posters("BatchAcceptance"),
                "A provider's settlement report accepted (hop 1): its processing fees recognised against the source's"
                        + " clearing",
                "settlement.settlement_batch (the batch id)", STORED_ROW,
                "SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md 3.3 (posting date accepted_on); BatchRecognition",
                required(EITHER, PROCESSING_COSTS), required(EITHER, SOURCE_POSITIONS)));
        rules.add(rule("SETTLEMENT-STATEMENT", POSTING, "settlement-batch:", "settlement", posters("BatchAcceptance"),
                "A bank statement accepted (hop 2) - the one way cash moves on the books: cash, the attributed sources'"
                        + " clearings, the bank's charges, unattributed credits and debits to suspense never netted,"
                        + " and lending capital recognised (ADR-0096 rule 0)",
                "settlement.settlement_batch (the batch id)", STORED_ROW,
                "SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md 3.3; BankRecognition; ADR-0065 3; ADR-0096 4",
                optional(EITHER, CASH_AT_BANK), optional(EITHER, SOURCE_POSITIONS), optional(EITHER, PROCESSING_COSTS),
                optional(CREDIT, SUSPENSE_UNMATCHED), optional(DEBIT, SUSPENSE_UNMATCHED),
                optional(CREDIT, LENDING_CAPITAL)));
        rules.add(rule("SETTLEMENT-REPUDIATION", REVERSAL, "settlement-batch:", "reconciliation",
                posters("BatchRepudiations"),
                "An accepted batch repudiated four-eyes - its recognition reversed exactly",
                "reconciliation.resolution (REPUDIATE_BATCH) over settlement.settlement_batch", TODAY_CLOCK,
                "SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md repudiation; ADR-0065 6",
                optional(EITHER, CASH_AT_BANK), optional(EITHER, SOURCE_POSITIONS), optional(EITHER, PROCESSING_COSTS),
                optional(DEBIT, SUSPENSE_UNMATCHED), optional(CREDIT, SUSPENSE_UNMATCHED),
                optional(DEBIT, LENDING_CAPITAL)));
        rules.add(rule("RECON-SUSPENSE", POSTING, "recon-suspense:", "reconciliation", posters("Suspense"),
                "A remainder parked in suspense, or a parked item released (unpark, offset, a resolution's manual match"
                        + " or a repudiation) - aggregated per transaction, position and value date",
                "reconciliation.park (the park id)", STORED_ROW,
                "SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md suspense; ADR-0070 (dated the deciding act's date - a"
                        + " resolution's stored proposal date)",
                required(EITHER, SUSPENSE_UNMATCHED), required(EITHER, SOURCE_POSITIONS)));
    }

    // ------------------------------------------------------------------ fx (LEDGER_MODEL.md 9)

    private static void fx(List<PostingRule> rules) {
        rules.add(rule("FX-TRADE", POSTING, "fx-trade:", "fx", posters("FxConversion"),
                "A wallet conversion: two single-currency legs meeting on FX_POSITION, the spread recognised in the"
                        + " computed leg",
                "fx.trade (the trade id)", STORED_ROW,
                "LEDGER_MODEL.md 9 (posting date trade.booked_on); ConversionLines",
                required(DEBIT, CUSTOMER_WALLET), required(CREDIT, FX_POSITION), required(DEBIT, FX_POSITION),
                required(CREDIT, CUSTOMER_WALLET), optional(CREDIT, FX_SPREAD_REVENUE),
                optional(EITHER, ROUNDING_RESIDUAL)));
        rules.add(rule("FX-COVER", POSTING, "fx-cover:", "fx", posters("FxCoverOutcomes"),
                "A cover or an unwind executed with the provider: the plan's position legs closed onto the provider's"
                        + " clearing, the difference realised per leg",
                "fx.cover (the cover id)", STORED_ROW,
                "LEDGER_MODEL.md 9 (posting date cover_execution.recorded_on); CoverLines",
                required(DEBIT, FX_POSITION), required(CREDIT, FX_PROVIDER_CLEARING),
                required(DEBIT, FX_PROVIDER_CLEARING), required(CREDIT, FX_POSITION),
                optional(CREDIT, FX_REALISED_GAINS), optional(DEBIT, FX_REALISED_LOSSES)));
        rules.add(rule("FX-TRADE-REVERSAL", REVERSAL, "fx-trade:", "fx", posters("TradeReversals"),
                "An approved trade reversal - the exact mirror of the trade's entry",
                "fx.trade_reversal over fx.trade (the trade id)", TODAY_CLOCK,
                "LEDGER_MODEL.md 9 (P9-TSK-025)",
                required(DEBIT, FX_POSITION), required(CREDIT, CUSTOMER_WALLET), required(DEBIT, CUSTOMER_WALLET),
                required(CREDIT, FX_POSITION), optional(DEBIT, FX_SPREAD_REVENUE),
                optional(EITHER, ROUNDING_RESIDUAL)));
    }

    // ------------------------------------------------------------------ lending (ADR-0096 section 2)

    private static void lending(List<PostingRule> rules) {
        String adr = "ADR-0096 2 rule ";
        String loan = "lending.loan (the loan or credit line id)";
        rules.add(rule("LND-1", POSTING, "lending.disbursement:", "lending", posters("LoanDisbursements"),
                "A loan disbursed to the borrower's wallet, the origination fee recognised upfront",
                loan, TODAY_DATABASE, adr + "1",
                required(DEBIT, LOAN_PRINCIPAL), required(CREDIT, CUSTOMER_WALLET), optional(CREDIT, LOAN_FEE_INCOME)));
        rules.add(rule("LND-1D", POSTING, "lending.draw:", "lending", posters("CreditLineDraws"),
                "A credit line drawn to the borrower's wallet",
                "lending.credit_line_draw (the draw id)", TODAY_DATABASE, adr + "1",
                required(DEBIT, LOAN_PRINCIPAL), required(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("LND-2", POSTING, "lending.accrual:", "lending", posters("LoanServicing"),
                "A day's interest earned - posted dated the accrual date itself (the back-dating poster, C3)",
                "lending.interest_accrual (loan, accrual date)", ECONOMIC_DATE, adr + "2 (posting = value = the accrual date)",
                required(DEBIT, LOAN_INTEREST_ACCRUED), required(CREDIT, LOAN_INTEREST_INCOME)));
        rules.add(rule("LND-3", POSTING, "lending.billing:", "lending", posters("InstalmentBillings"),
                "An instalment billed (or a post-maturity period): accrued interest and principal made due",
                "lending.instalment_billing (the instalment, or loan and period end)", TODAY_DATABASE, adr + "3",
                optional(DEBIT, LOAN_INTEREST_DUE), optional(CREDIT, LOAN_INTEREST_ACCRUED),
                optional(DEBIT, LOAN_PRINCIPAL_DUE), optional(CREDIT, LOAN_PRINCIPAL)));
        rules.add(rule("LND-3S", POSTING, "lending.statement:", "lending", posters("CreditLineStatements"),
                "A credit line's statement issued: accrued interest and the minimum principal made due",
                "lending.credit_line_statement (line, statement date)", TODAY_DATABASE, adr + "3",
                optional(DEBIT, LOAN_INTEREST_DUE), optional(CREDIT, LOAN_INTEREST_ACCRUED),
                optional(DEBIT, LOAN_PRINCIPAL_DUE), optional(CREDIT, LOAN_PRINCIPAL)));
        rules.add(rule("LND-4", POSTING, "lending.fee:", "lending", posters("LateFees"),
                "A late fee assessed on a billed item",
                "lending.fee_assessment (billed item, LATE)", TODAY_DATABASE, adr + "4",
                required(DEBIT, LOAN_FEES_DUE), required(CREDIT, LOAN_FEE_INCOME)));
        rules.add(rule("LND-5", POSTING, "lending.repayment:", "lending", posters("Repayments"),
                "A repayment from the wallet (customer, auto-collection, prepayment or payoff), allocated to "
                        + LOAN_DUE + ", the line's paydown, the rest held as a credit balance",
                "lending.repayment (the repayment id)", TODAY_DATABASE, adr + "5 and 7",
                required(DEBIT, CUSTOMER_WALLET), optional(CREDIT, LOAN_FEES_DUE), optional(CREDIT, LOAN_INTEREST_DUE),
                optional(CREDIT, LOAN_PRINCIPAL_DUE), optional(CREDIT, LOAN_PRINCIPAL),
                optional(CREDIT, LOAN_CREDIT_BALANCE)));
        rules.add(rule("LND-6", POSTING, "lending.credit-application:", "lending",
                posters("CreditBalanceApplications"),
                "A credit balance applied to " + LOAN_DUE,
                "lending.credit_application (the billed item)", TODAY_DATABASE, adr + "6",
                required(DEBIT, LOAN_CREDIT_BALANCE), optional(CREDIT, LOAN_FEES_DUE),
                optional(CREDIT, LOAN_INTEREST_DUE), optional(CREDIT, LOAN_PRINCIPAL_DUE)));
        rules.add(rule("LND-7", POSTING, "lending.closure-refund:", "lending", posters("LoanClosures"),
                "A closed loan's credit balance refunded to the wallet", loan, TODAY_DATABASE, adr + "7",
                required(DEBIT, LOAN_CREDIT_BALANCE), required(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("LND-8", REVERSAL, "lending.waiver:", "lending", posters("Waivers"),
                "An unpaid fee waived four-eyes - the exact reversal of rule 4's entry",
                "lending.waiver (the waiver id)", TODAY_DATABASE, adr + "8",
                required(DEBIT, LOAN_FEE_INCOME), required(CREDIT, LOAN_FEES_DUE)));
        rules.add(rule("LND-8a", POSTING, "lending.waiver:", "lending", posters("Waivers"),
                "A paid fee refunded four-eyes, into the credit balance",
                "lending.waiver (the waiver id)", TODAY_DATABASE, adr + "8a",
                required(DEBIT, LOAN_FEE_INCOME), required(CREDIT, LOAN_CREDIT_BALANCE)));
        rules.add(rule("LND-9", POSTING, "lending.waiver:", "lending", posters("Waivers"),
                "A billed item's due interest waived four-eyes",
                "lending.waiver (the waiver id)", TODAY_DATABASE, adr + "9",
                required(DEBIT, LOAN_INTEREST_INCOME), required(CREDIT, LOAN_INTEREST_DUE)));
        rules.add(rule("LND-10", REVERSAL, "lending.repayment-reversal:", "lending", posters("RepaymentReversals"),
                "A repayment reversed four-eyes - the exact mirror of rule 5's entry, dated no earlier than it",
                "lending.repayment_reversal (the reversal id)", TODAY_DATABASE, adr + "10",
                optional(DEBIT, LOAN_FEES_DUE), optional(DEBIT, LOAN_INTEREST_DUE), optional(DEBIT, LOAN_PRINCIPAL_DUE),
                optional(DEBIT, LOAN_PRINCIPAL), optional(DEBIT, LOAN_CREDIT_BALANCE),
                required(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("LND-13", POSTING, "lending.amendment:", "lending", posters("LoanAmendments"),
                "Arrears re-scheduled by an accepted amendment", "lending.loan_amendment (the amendment id)",
                TODAY_DATABASE, adr + "13",
                required(DEBIT, LOAN_PRINCIPAL), required(CREDIT, LOAN_PRINCIPAL_DUE)));
    }

    // ------------------------------------------------------------------ bnpl (ADR-0103, LEDGER_MODEL.md 11)

    private static void bnpl(List<PostingRule> rules) {
        String model = "LEDGER_MODEL.md 11 ";
        String obligation = "bnpl.obligation (the customer repayment obligation)";
        rules.add(rule("BNPL-F1", POSTING, "bnpl.financing:", "bnpl", posters("BnplAcceptances"),
                "A purchase financed: the merchant credited gross, its discount taken as income",
                "bnpl.purchase (the purchase id)", TODAY_DATABASE, model + "F1",
                required(DEBIT, BNPL_PRINCIPAL), required(CREDIT, MERCHANT_PAYABLE),
                optional(DEBIT, MERCHANT_PAYABLE), optional(CREDIT, BNPL_MERCHANT_FEE_INCOME)));
        rules.add(rule("BNPL-F2", POSTING, "bnpl.billing:", "bnpl", posters("BnplAcceptances", "BnplServicing"),
                "An instalment billed (pay-in-4's first at purchase, the rest by servicing, the payoff's early billing)",
                "bnpl.instalment (the instalment id)", TODAY_DATABASE, model + "F2",
                optional(DEBIT, BNPL_PRINCIPAL_DUE), optional(CREDIT, BNPL_PRINCIPAL),
                optional(DEBIT, BNPL_INTEREST_DUE), optional(CREDIT, BNPL_INTEREST_ACCRUED)));
        rules.add(rule("BNPL-F2a", POSTING, "bnpl.accrual:", "bnpl", posters("BnplAccruals"),
                "A day's pay-in-N interest earned - posted today, valued the accrual date",
                "bnpl.interest_accrual (obligation, accrual date)", TODAY_DATABASE, model + "F2a",
                required(DEBIT, BNPL_INTEREST_ACCRUED), required(CREDIT, BNPL_INTEREST_INCOME)));
        rules.add(rule("BNPL-F3", POSTING, "bnpl.repayment:", "bnpl", posters("BnplAcceptances", "BnplRepayments"),
                "A repayment from the wallet (at purchase, customer, auto-collection, payoff) allocated to the due"
                        + " accounts, the rest held as a credit balance",
                "bnpl.repayment (the repayment id)", TODAY_DATABASE, model + "F3",
                required(DEBIT, CUSTOMER_WALLET), optional(CREDIT, BNPL_PRINCIPAL_DUE),
                optional(CREDIT, BNPL_INTEREST_DUE), optional(CREDIT, BNPL_FEES_DUE),
                optional(CREDIT, BNPL_CREDIT_BALANCE)));
        rules.add(rule("BNPL-F3a", POSTING, "bnpl.credit-application:", "bnpl",
                posters("BnplCreditBalanceApplications"), "A credit balance applied to the due accounts",
                obligation, TODAY_DATABASE, model + "F3a",
                required(DEBIT, BNPL_CREDIT_BALANCE), optional(CREDIT, BNPL_PRINCIPAL_DUE),
                optional(CREDIT, BNPL_INTEREST_DUE), optional(CREDIT, BNPL_FEES_DUE)));
        rules.add(rule("BNPL-CLOSURE", POSTING, "bnpl.closure-refund:", "bnpl", posters("BnplClosures"),
                "A closed obligation's credit balance refunded to the wallet", obligation, TODAY_DATABASE,
                model + "closure refund",
                required(DEBIT, BNPL_CREDIT_BALANCE), required(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("BNPL-F4", POSTING, "bnpl.fee:", "bnpl", posters("BnplConditions"),
                "A late fee assessed on an instalment", "bnpl.fee_assessment (instalment, LATE)", TODAY_DATABASE,
                model + "F4",
                required(DEBIT, BNPL_FEES_DUE), required(CREDIT, BNPL_FEE_INCOME)));
        rules.add(rule("BNPL-F5", POSTING, "bnpl.refund:", "bnpl", posters("BnplRefunds"),
                "A merchant refund (or a dispute refunded, F9) of a financed purchase: the payable debited, the"
                        + " discount returned, principal cancelled, the excess to the wallet or the credit balance, the"
                        + " released interest and fees reversed",
                "bnpl.refund (the refund id)", TODAY_DATABASE, model + "F5, F5a and F9",
                required(DEBIT, MERCHANT_PAYABLE), optional(DEBIT, BNPL_MERCHANT_FEE_INCOME),
                optional(CREDIT, BNPL_PRINCIPAL), optional(CREDIT, BNPL_PRINCIPAL_DUE),
                optional(CREDIT, CUSTOMER_WALLET, BNPL_CREDIT_BALANCE), optional(CREDIT, MERCHANT_PAYABLE),
                optional(DEBIT, BNPL_INTEREST_INCOME), optional(CREDIT, BNPL_INTEREST_ACCRUED),
                optional(CREDIT, BNPL_INTEREST_DUE), optional(DEBIT, BNPL_FEE_INCOME), optional(CREDIT, BNPL_FEES_DUE)));
        rules.add(rule("BNPL-F7", REVERSAL, "bnpl.waiver:", "bnpl", posters("BnplWaivers"),
                "A late fee waived four-eyes - the exact reversal of F4's entry", "bnpl.waiver (the waiver id)",
                TODAY_DATABASE, model + "F7",
                required(DEBIT, BNPL_FEE_INCOME), required(CREDIT, BNPL_FEES_DUE)));
        rules.add(rule("BNPL-F8", REVERSAL, "bnpl.repayment-reversal:", "bnpl", posters("BnplRepaymentReversals"),
                "A repayment reversed four-eyes - the exact mirror of F3's entry",
                "bnpl.repayment_reversal (the reversal id)", TODAY_DATABASE, model + "F8",
                optional(DEBIT, BNPL_PRINCIPAL_DUE), optional(DEBIT, BNPL_INTEREST_DUE), optional(DEBIT, BNPL_FEES_DUE),
                optional(DEBIT, BNPL_CREDIT_BALANCE), required(CREDIT, CUSTOMER_WALLET)));
        rules.add(rule("BNPL-F8a", POSTING, "bnpl.reversal-fee-release:", "bnpl", posters("BnplRepaymentReversals"),
                "The late fee a reversal re-opened on an instalment a refund cancelled whole, released",
                "bnpl.repayment_reversal (the reversal id)", TODAY_DATABASE, model + "F8a",
                required(DEBIT, BNPL_FEE_INCOME), required(CREDIT, BNPL_FEES_DUE)));
    }

    // ------------------------------------------------------------------ adjustments (ledger V010, V015; ADR-0071)

    private static void adjustments(List<PostingRule> rules) {
        String resolution = "reconciliation.resolution (its stored proposal date; approved four-eyes)";
        rules.add(rule("ADJ-MANUAL", ADJUSTMENT, "MANUAL", "ledger", posters("LedgerAdjustments"),
                "An approved manual adjustment, four-eyes, on purposes open to free adjustment - dated the posting date"
                        + " its proposer stated",
                "ledger.adjustment_proposal (the proposal id)", ECONOMIC_DATE,
                "INV-REV-04; ledger V010, V015 (closed purposes refused)",
                required(DEBIT, OPEN_TO_FREE_ADJUSTMENT), required(CREDIT, OPEN_TO_FREE_ADJUSTMENT)));
        rules.add(rule("RECON-WRITE-OFF", ADJUSTMENT, "RECONCILIATION", "reconciliation",
                posters("ResolutionMachine"),
                "A break written off: a parked item or an expectation's remainder taken to losses",
                resolution, STORED_ROW, "ADR-0071; ResolutionTemplates (WRITE_OFF)",
                required(DEBIT, RECONCILIATION_LOSSES), required(CREDIT, RESOLUTION_HOLDINGS)));
        rules.add(rule("RECON-TRANSFER", ADJUSTMENT, "RECONCILIATION", "reconciliation",
                posters("ResolutionMachine"),
                "Parked or remaining value transferred to a customer wallet or a merchant payable",
                resolution, STORED_ROW, "ADR-0071; ResolutionTemplates (TRANSFER_TO_ACCOUNT)",
                required(DEBIT, RESOLUTION_HOLDINGS), required(CREDIT, CUSTOMER_WALLET, MERCHANT_PAYABLE)));
        rules.add(rule("RECON-GAIN", ADJUSTMENT, "RECONCILIATION", "reconciliation", posters("ResolutionMachine"),
                "A parked credit nobody can claim recognised as a gain",
                resolution, STORED_ROW, "ADR-0071; ResolutionTemplates (RECOGNISE_GAIN)",
                required(DEBIT, SUSPENSE_UNMATCHED), required(CREDIT, RECONCILIATION_GAINS)));
    }

    // ------------------------------------------------------------------

    private static List<String> posters(String... posters) {
        return List.of(posters);
    }

    private static PostingRule rule(
            String id,
            EntryType type,
            String namespace,
            String sourceModule,
            List<String> posters,
            String economicEvent,
            String authoritativeReference,
            DatingClass dating,
            String catalogue,
            LineShape... lines) {
        return new PostingRule(id, 1, PostingRuleRegister.ORIGIN, type, namespace, sourceModule, posters,
                economicEvent, authoritativeReference, dating, List.of(lines), catalogue);
    }
}
