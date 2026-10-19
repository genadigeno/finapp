package com.finapp.bnpl;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The bnpl module's audited acts (`P12-TSK-004`; {@code INV-AUD-01}, {@code INV-AUD-04}; {@code PHASE_12_PLAN.md} section
 * 11): every act on a BNPL terms version, with its reason, and the refused self-approval. Never an amount, a rate or a
 * reason in a summary ({@code INV-AUD-02}). Later tasks add their own beside these.
 */
@RequiredArgsConstructor
public enum BnplAuditAction implements AuditableAction {

    /** A BNPL officer proposed a new version of a product's terms. */
    TERMS_VERSION_PROPOSED(
            "bnpl.TermsVersionProposed",
            "A holder of BNPL_ADMINISTER proposed a whole new version of a BNPL product's terms - plans, rate (pay-in-N),"
                    + " late fee, allocation, delinquency bounds, the dispute window, the offer validity cap, engines and"
                    + " template, judged complete and within bounds (bnpl.TermsInvalid, bnpl.TermsNotAmortising otherwise)"
                    + " and immutable from this moment (INV-HIST-04) - awaiting a DIFFERENT holder's activation"
                    + " (INV-AUD-04). The summary names the product and the version number only.",
            true),

    /** A different BNPL officer activated a terms version, retiring its predecessor. */
    TERMS_VERSION_ACTIVATED(
            "bnpl.TermsVersionActivated",
            "A holder of BNPL_ADMINISTER other than its proposer activated a BNPL terms version (INV-AUD-04, also held by"
                    + " bnpl V002's four-eyes CHECK), retiring the product's predecessor in the same transaction so one"
                    + " version is in force at every instant; the event bnpl.BnplTermsVersionActivated carries the version."
                    + " Every later offer pins it; existing agreements keep theirs (INV-HIST-04).",
            true),

    /** A terms version was rejected, or withdrawn by its proposer. */
    TERMS_VERSION_REJECTED(
            "bnpl.TermsVersionRejected",
            "A proposed BNPL terms version was rejected - or withdrawn by its proposer; it was never in force and the"
                    + " product is free for a new proposal.",
            true),

    /** A proposer tried to approve their own proposal and was refused. */
    SELF_APPROVAL_REFUSED(
            "bnpl.SelfApprovalRefused",
            "A proposer tried to approve their own BNPL proposal - a terms version's activation (act=terms:activate),"
                    + " a repayment reversal's or a waiver's approval (act=repayment-reversal:approve, act=waiver:approve;"
                    + " P12-TSK-024) -"
                    + " and was refused before anything was written (INV-AUD-04; also held by the table's four-eyes"
                    + " CHECK). Recorded FAILED in a transaction of its own after the act rolled back, so the attempt"
                    + " survives (the Phase 11 completion gate's R4, applied from bnpl's first door). Its target the"
                    + " proposal; the summary names it and the act - never an amount.",
            false),

    /** A customer applied for BNPL at checkout, opening one credit decision request (`P12-TSK-010`). */
    APPLICATION_SUBMITTED(
            "bnpl.ApplicationSubmitted",
            "A customer, from a MULTI_FACTOR session, applied for a BNPL product and plan inside an open checkout session"
                    + " (POST /v1/me/bnpl-applications, keyed bnpl.application:CUSTOMER:<id>) - judged in good standing,"
                    + " the session open, unexpired and within credit's bounds, its merchant BNPL-enabled and covering its"
                    + " fee, the product's terms ACTIVE - and credit's decision request was opened from the BNPL origin"
                    + " for exactly the session's gross in the same transaction (credit's own credit.CreditDecisionRequested"
                    + " event beside it). Its target the application; the summary names the product, the plan, the"
                    + " session and credit's request - never an amount or a token (INV-AUD-02).",
            false),

    /** A customer withdrew a BNPL application, cancelling its credit request (`P12-TSK-010`). */
    APPLICATION_WITHDRAWN(
            "bnpl.ApplicationWithdrawn",
            "A customer withdrew their own BNPL application while it awaited a decision"
                    + " (POST /v1/me/bnpl-applications/{id}/withdrawal, keyed, the platform's conditional step-up) - the"
                    + " application locked (B1) and moved WITHDRAWN, and credit's request cancelled from the BNPL origin"
                    + " under credit's own row lock in the same transaction (credit's credit.DecisionRequestCancelled"
                    + " record beside it). Refused, with nothing written, once credit has evaluated the request. Its"
                    + " target the application; the summary names it and credit's request.",
            false),

    /** A customer declined a BNPL offer (`P12-TSK-011`). */
    OFFER_DECLINED(
            "bnpl.OfferDeclined",
            "A customer declined their own BNPL offer (POST /v1/me/bnpl-offers/{id}/decline, keyed"
                    + " bnpl.offer-decline:CUSTOMER:<id>, the platform's conditional step-up) - the offer locked (B2) and"
                    + " moved DECLINED only while OFFERED and before its expires_at on the database clock, the complement"
                    + " of the sweep's expiry, so exactly one of DECLINED and EXPIRED. Nothing is released: credit's"
                    + " unconsumed approval lapses at its own validity, and the checkout session stays OPEN for another"
                    + " instrument. Its target the offer; the summary names it and its application - never an amount.",
            false),

    /** A customer accepted a BNPL offer - the purchase (`P12-TSK-013`). */
    OFFER_ACCEPTED(
            "bnpl.OfferAccepted",
            "A customer accepted their own BNPL offer from a MULTI_FACTOR session (POST /v1/me/bnpl-offers/{id}/acceptance,"
                    + " keyed bnpl.offer-acceptance:CUSTOMER:<id>, the offer's terms hash echoed) - the purchase: under the"
                    + " party's credit profile lock credit's decision consumed (credit's own credit.DecisionConsumed beside"
                    + " it), the capital judged under its row lock, agreement version 1 committed as an exact copy of the"
                    + " offer's terms with its evidence, the purchase, the obligation, schedule version 1 and its six ledger"
                    + " accounts born, the merchant financed and - for pay-in-4 - instalment 1 collected from the wallet,"
                    + " and the checkout session completed by financing with its order, all in one transaction. Its target"
                    + " the purchase; the summary names the offer, the agreement and its version and the purchase - never an"
                    + " amount (INV-AUD-02). Refusals and the losers of a race record nothing.",
            false),

    /** The purchase financed its merchant (`P12-TSK-013`). */
    MERCHANT_FINANCED(
            "bnpl.MerchantFinanced",
            "A BNPL purchase financed its merchant (owner decision B2): the merchant's MERCHANT_PAYABLE credited the gross"
                    + " less the merchant discount priced by its pinned BNPL fee schedule version, the receivable"
                    + " BNPL_PRINCIPAL debited the gross and BNPL_MERCHANT_FEE_INCOME credited the fee by the entry F1"
                    + " (bnpl.financing:<purchase>), funded from lending capital - in the acceptance's transaction, under"
                    + " the accepting customer as actor (the act that commits the financing). Its target the purchase; the"
                    + " summary names the purchase, the financing entry and the fee schedule version - never an amount or"
                    + " the merchant's figures.",
            false),

    /** A merchant refund of a BNPL purchase applied - at the merchant's door or the operator's (`P12-TSK-021`). */
    REFUND_APPLIED(
            "bnpl.RefundApplied",
            "A BNPL purchase was refunded (owner decisions B3 and B7, ADR-0105) - by its merchant over an API key carrying"
                    + " the BNPL refund scope (POST /v1/merchant/bnpl-purchases/{id}/refunds, keyed"
                    + " bnpl.refund:MERCHANT:<merchant>) or by an operator holding PAYMENT_REFUND"
                    + " (POST /v1/operator/bnpl-purchases/{id}/refunds, keyed bnpl.refund:<actor type>:<id> - CUSTOMER-typed"
                    + " for an operator session today), with a reason:"
                    + " under the purchase's lock the refund bounded by what remains of it (INV-BNPL-07), under the"
                    + " obligation's the unpaid instalments cancelled latest first and the excess returned to the"
                    + " customer's wallet (or held as credit), the merchant fee returned by the cumulative method and the"
                    + " merchant's payable debited the refund's net - below zero after a payout by at most the purchase's"
                    + " financed net (INV-MER-07 as amended) - in one entry (bnpl.refund:<refund>), the schedule and"
                    + " agreement versions it made and any closure in the same transaction. Its target the refund; the"
                    + " summary names the purchase, the door and the merchant's key id - never an amount (INV-AUD-02).",
            true),

    /** A merchant key without the BNPL refund scope tried to refund (`P12-TSK-021`). */
    REFUND_SCOPE_REFUSED(
            "bnpl.RefundScopeRefused",
            "A merchant's API key that was not issued with the BNPL_REFUND scope tried to refund a BNPL purchase and was"
                    + " refused 403 bnpl.RefundScopeRequired before anything was locked or written; recorded FAILED in a"
                    + " transaction of its own so the attempt survives. Its target the purchase named in the path (not"
                    + " confirmed to be the merchant's - nothing was read); the summary names the key id.",
            false),

    /** An operator tried to act on a BNPL purchase, dispute or agreement of their own party (`P12-TSK-021`, `-023`, `-024`). */
    SELF_DEALING_REFUSED(
            "bnpl.SelfDealingRefused",
            "An operator tried to refund, at the operator's BNPL refund door, a purchase whose customer is the operator's"
                    + " own party - or to resolve, propose, approve or reject the resolution of a BNPL dispute of their own"
                    + " party (P12-TSK-023) - or to propose, approve or reject a repayment reversal or a waiver on an"
                    + " agreement of their own party, or as an operator the platform cannot place in a party (fail closed;"
                    + " P12-TSK-024) - and was refused 403 bnpl.SelfDealingRefused with nothing written; recorded FAILED in"
                    + " a transaction of its own after the act rolled back. Its target the purchase, the dispute or the"
                    + " agreement; the summary names it and the act - never an amount.",
            false),

    /** A BNPL servicing agent proposed the reversal of a repayment (`P12-TSK-024`). */
    REPAYMENT_REVERSAL_PROPOSED(
            "bnpl.RepaymentReversalProposed",
            "A holder of BNPL_SERVICE proposed, with a reason, the exact reversal of one repayment of an ACTIVE BNPL"
                    + " agreement (POST /v1/operator/bnpl-agreements/{id}/repayments/{rid}/reversal, keyed"
                    + " bnpl.servicing:<actor type>:<id>) - under the obligation's lock, one live proposal per repayment,"
                    + " never a payoff's, the credit it held still held; nothing posted, awaiting a DIFFERENT holder of"
                    + " BNPL_SERVICE_APPROVE (INV-AUD-04). Its target the reversal; the summary names the agreement and"
                    + " the repayment - never an amount.",
            true),

    /** A different BNPL servicing agent approved a repayment reversal, posting F8 (`P12-TSK-024`). */
    REPAYMENT_REVERSAL_APPROVED(
            "bnpl.RepaymentReversalApproved",
            "A holder of BNPL_SERVICE_APPROVE other than its proposer approved a BNPL repayment reversal (INV-AUD-04, also"
                    + " held by bnpl V016's four-eyes CHECK), naming the proposal: under the party's credit profile"
                    + " (L0), the obligation (B4), the proposal (B6), the capital row when principal is re-instated (L6)"
                    + " and the wallet (P), F8 - the ledger's ReversalService swapping the repayment's F3 entry exactly"
                    + " under bnpl.repayment-reversal:<reversal>, valued max(today, floor) - the allocation negated, the"
                    + " amount due again, the wallet credited back, in one transaction with bnpl.BnplRepaymentReversed."
                    + " Its target the reversal; the summary names the agreement, the repayment and both entries - never"
                    + " an amount.",
            true),

    /** A repayment reversal was rejected, or withdrawn by its proposer (`P12-TSK-024`). */
    REPAYMENT_REVERSAL_REJECTED(
            "bnpl.RepaymentReversalRejected",
            "A proposed BNPL repayment reversal was rejected by a holder of BNPL_SERVICE - or withdrawn by its proposer;"
                    + " nothing was posted and the repayment may be proposed for reversal again.",
            true),

    /** A BNPL servicing agent proposed waiving an amount of a late fee (`P12-TSK-024`). */
    WAIVER_PROPOSED(
            "bnpl.WaiverProposed",
            "A holder of BNPL_SERVICE proposed, with a reason, waiving an amount of one late fee of an ACTIVE BNPL"
                    + " agreement (POST /v1/operator/bnpl-agreements/{id}/waivers, keyed bnpl.servicing:<actor type>:<id>)"
                    + " - within what may still be forgiven (its unpaid remainder, never what was paid, released or"
                    + " waived: the Phase 11 gate's R3), one proposal per fee at a time; nothing posted, awaiting a"
                    + " DIFFERENT holder of BNPL_SERVICE_APPROVE. Its target the waiver; the summary names the agreement"
                    + " and the assessment - never an amount.",
            true),

    /** A different BNPL servicing agent approved a fee waiver, posting F7 (`P12-TSK-024`). */
    WAIVER_APPROVED(
            "bnpl.WaiverApproved",
            "A holder of BNPL_SERVICE_APPROVE other than its proposer approved a BNPL fee waiver (INV-AUD-04, also held by"
                    + " bnpl V016's four-eyes CHECK): the bound re-judged under the obligation's lock (B4), F7 - the"
                    + " ledger's ReversalService of the assessment's F4 entry for the amount (DR BNPL_FEE_INCOME / CR"
                    + " BNPL_FEES_DUE, bnpl.waiver:<waiver>) - and the closure when it zeroed the last receivable, in one"
                    + " transaction with bnpl.BnplFeeWaived. Its target the waiver; the summary names the agreement, the"
                    + " assessment and the entry - never an amount.",
            true),

    /** A fee waiver was rejected, or withdrawn by its proposer (`P12-TSK-024`). */
    WAIVER_REJECTED(
            "bnpl.WaiverRejected",
            "A proposed BNPL fee waiver was rejected by a holder of BNPL_SERVICE - or withdrawn by its proposer; nothing"
                    + " was posted and the fee may be proposed for waiver again.",
            true),

    /** A customer opened a dispute of their own BNPL purchase (`P12-TSK-023`). */
    DISPUTE_OPENED(
            "bnpl.DisputeOpened",
            "A customer contested their own BNPL purchase with the platform (POST /v1/me/bnpl-agreements/{id}/disputes,"
                    + " keyed bnpl.dispute:CUSTOMER:<id>, ADR-0106) with a reason category and a reason: under the"
                    + " purchase's lock the amount bounded by what remains of it (INV-BNPL-07), under the obligation's the"
                    + " instalments a refund of it would cancel - latest first - held from collection, days past due and"
                    + " late fees, and the merchant's response deadline stamped on the database clock (the pinned window,"
                    + " OQ-10). Its target the dispute; the summary names the purchase and the deadline - never an amount.",
            true),

    /** A merchant answered a BNPL dispute - with evidence, or accepting it (`P12-TSK-023`). */
    DISPUTE_RESPONDED(
            "bnpl.DisputeResponded",
            "A merchant answered a dispute of its BNPL purchase over its own API key, tenant-scoped (POST"
                    + " /v1/merchant/bnpl-disputes/{id}/response, keyed bnpl.dispute-response:MERCHANT:<merchant>) before"
                    + " the response deadline on the database clock, with a statement: contesting it (MERCHANT_RESPONDED,"
                    + " an operator resolves) or accepting it (resolved as a refund at once, bnpl.DisputeResolved beside it)."
                    + " Its target the dispute; the summary names the decision and the merchant's key id.",
            true),

    /** A BNPL dispute was resolved - as a refund or upheld (`P12-TSK-023`). */
    DISPUTE_RESOLVED(
            "bnpl.DisputeResolved",
            "A BNPL dispute was resolved (ADR-0106): as a refund - by the merchant's acceptance, by its silence past the"
                    + " response deadline (actor SYSTEM, the deadline sweep) or by an operator holding BNPL_DISPUTE_RESOLVE"
                    + " - executed as a BnplRefund of exactly the disputed amount under the purchase's one bound in the same"
                    + " transaction (INV-BNPL-08); or upheld by an operator, the hold lifted and nothing re-billed. Above the"
                    + " pinned four-eyes threshold an operator's resolution is a proposal another operator approved. Its"
                    + " target the dispute; the summary names the outcome, the resolver's door and any refund - never an"
                    + " amount.",
            false),

    /** A customer withdrew their BNPL dispute (`P12-TSK-023`). */
    DISPUTE_WITHDRAWN(
            "bnpl.DisputeWithdrawn",
            "A customer withdrew their own open BNPL dispute (POST /v1/me/bnpl-disputes/{id}/withdrawal, keyed"
                    + " bnpl.dispute-withdrawal:CUSTOMER:<id>): the hold lifted - each held instalment's days past due and"
                    + " late-fee day counting from the withdrawal's business date - and nothing posted. Its target the"
                    + " dispute.",
            false),

    /** An operator proposed a BNPL dispute's resolution above the four-eyes threshold (`P12-TSK-023`). */
    DISPUTE_RESOLUTION_PROPOSED(
            "bnpl.DisputeResolutionProposed",
            "An operator holding BNPL_DISPUTE_RESOLVE proposed the resolution - a refund or upheld - of a BNPL dispute the"
                    + " merchant answered whose amount is above its pinned four-eyes threshold (POST"
                    + " /v1/operator/bnpl-disputes/{id}/resolution, keyed bnpl.servicing:<actor type>:<id>), with a reason;"
                    + " another operator approves or rejects it. Its target the proposal; the summary names the dispute and"
                    + " the outcome proposed.",
            true),

    /** A BNPL dispute's resolution proposal was rejected (`P12-TSK-023`). */
    DISPUTE_RESOLUTION_REJECTED(
            "bnpl.DisputeResolutionRejected",
            "An operator holding BNPL_DISPUTE_RESOLVE rejected - or, its proposer, withdrew - a proposed resolution of a"
                    + " BNPL dispute (POST /v1/operator/bnpl-disputes/{id}/resolution/rejection), with a reason; the dispute"
                    + " awaits a new proposal. Its target the proposal; the summary names the dispute.",
            true),

    /** An operator read a BNPL dispute - the customer's reason and the merchant's statement (`P12-TSK-023`). */
    DISPUTE_READ(
            "bnpl.DisputeRead",
            "An operator holding BNPL_DISPUTE_RESOLVE read a BNPL dispute (GET /v1/operator/bnpl-disputes/{id}): the"
                    + " customer's reason and the merchant's statement (RESTRICTED-PII), the amount, the hold and any"
                    + " proposal - every access recorded (INV-DSP-03 by analogy). Its target the dispute.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
