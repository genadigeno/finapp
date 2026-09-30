package com.finapp.identity;

/**
 * What an actor may do (`P1-TSK-020`, ADR-0031).
 *
 * <h2>Every value names catalogued actions, and none invents a capability</h2>
 *
 * <p>Each names privileged actions `AUDITABLE_ACTIONS.md` already declares - from identity
 * suspension, role assignment and the KYC review actions `P2-TSK-003` catalogued to the ledger,
 * payment and merchant acts of Phases 3 to 6 - so the vocabulary follows the registry rather than
 * anticipating it. Which route requires which value is pinned by {@code RoutePermissionRegisterTest}
 * (`P6-DOC-001`). <em>(This heading said "three values" until then; there are fifteen — thirteen until `P7-TSK-003`, fourteen until `P7-TSK-012`.)</em> A permission for an action nobody has catalogued would
 * be a claim about a capability that does not exist.
 *
 * <p><em>(This javadoc said "two values" and that neither admin endpoint existed - true when
 * written, closed by `P1-TSK-028` and widened by `P2-TSK-004`.)</em> The permissions exist because
 * the ACTIONS do, and because a check with no vocabulary cannot be tested at all.
 *
 * <h2>There is deliberately no `sqlValueList()`</h2>
 *
 * <p>{@link RoleName} has one because a `CHECK` constraint persists a role. **A permission is never
 * a column**: ADR-0031 puts the role-to-permission mapping in code, so there is no constraint this
 * could generate and there will not be one. This enum shipped with the method, and the completion
 * gate removed it as dead - a helper that produces a SQL fragment for a column that does not exist
 * implies permissions are persisted somewhere, which is the opposite of the decision.
 */
public enum PermissionName {

    /**
     * Suspend an identity so it can no longer authenticate — and lift a suspension.
     *
     * <p>The most consequential thing one person can do to another's account here: it does not
     * merely deny a request, it ends the person's ability to log in at all.
     *
     * <p><strong>One permission for both directions, not two</strong> (`P1-TSK-032`), which is
     * {@link #ROLE_ASSIGN}'s own shape — "grant or revoke a role". The administrator trusted to
     * impose a suspension is the administrator trusted to lift one, and a separate
     * {@code IDENTITY_REINSTATE} held by the only role that exists would be vocabulary with no
     * decision behind it. The audit trail distinguishes the two actions; the permission need not.
     */
    IDENTITY_SUSPEND,

    /**
     * Grant or revoke a role.
     *
     * <p>**The permission that grants permissions**, which is why it is the one to watch: anybody
     * holding it can give themselves any other, so an audit record naming the actor is the only
     * thing that makes the escalation visible afterwards.
     */
    ROLE_ASSIGN,

    /**
     * Review KYC/KYB cases: read a case with its evidence, resolve screening hits, record
     * decisions (`P2-TSK-004`).
     *
     * <p>The first permission whose actions live outside {@code identity} — the vocabulary is
     * still this module's, because ADR-0031 keeps authorization here as a recorded merge, and one
     * foreign-domain permission does not reach its split trigger. It names actions the registry
     * already declares ({@code kyc.DecisionRecorded}, {@code kyc.ScreeningHitResolved},
     * {@code kyc.DocumentContentRead}); the endpoints that check it are {@code P2-TSK-012}'s,
     * which is why it ships before them — a check with no vocabulary cannot be built, let alone
     * tested.
     *
     * <p><strong>Deliberately not folded into what an administrator holds.</strong> Reviewing a
     * person's identity documents and managing identities are different trust decisions taken
     * about different people — the first real least-privilege split between administrative
     * populations. An administrator holding {@code ROLE_ASSIGN} can still grant themselves
     * {@code KYC_REVIEWER}; what the split buys is that the escalation is a recorded grant in
     * the trail rather than a capability that was silently always there ({@code P1-TSK-028}'s
     * honesty about what refusing self-elevation buys).
     */
    KYC_REVIEW,

    /**
     * Command a journal posting directly over an HTTP surface (`P3-TSK-007`).
     *
     * <p>Posting authority becomes a privileged capability rather than an ambient one — but the
     * permission gates <strong>surfaces where a person commands a posting</strong>, never the
     * in-process command path: {@code PostingService} is the one write path {@code INV-LED-04}
     * permits, invoked by the platform's own orchestrations under the flow's actor (a Phase 4
     * transfer runs as the customer, and a customer moving their own money holds no ledger
     * permission). It names an action the registry already declares
     * ({@code ledger.JournalEntryPosted}); no production endpoint carries it yet, deliberately —
     * inventing one to give the check a caller would be a surface chosen to suit a test (the
     * {@code KYC_REVIEW} precedent), so it ships with a probe.
     */
    LEDGER_POST,

    /**
     * Post a manual adjustment — the highest-risk manual financial action (`P3-TSK-007`).
     *
     * <p>Distinct from {@link #LEDGER_POST} even though one role holds both today, because the
     * <em>checks</em> differ per surface: `P3-TSK-017`'s adjustment endpoint checks exactly this
     * one, and an adjustment additionally demands a recorded reason ({@code INV-REV-04}) with
     * four-eyes arriving as recorded debt ({@code INV-AUD-04}). Splitting a permission later
     * means re-auditing every check site; splitting a role later is a new role and a migration —
     * so the vocabulary is precise and the bundling is coarse. Names
     * {@code ledger.AdjustmentPosted}; the endpoint is `P3-TSK-017`'s, so it ships with a probe.
     */
    LEDGER_ADJUST,

    /**
     * Reverse a completed transfer (`P4-TSK-009`) — the privileged, reasoned correction:
     * {@code COMPLETED -> REVERSED} with the new referencing entry, the original left
     * byte-identical ({@code INV-REV-01}).
     *
     * <p>Distinct from {@link #LEDGER_ADJUST} for the same reason that one is distinct from
     * {@link #LEDGER_POST}: the checks differ per surface — the reversal endpoint checks exactly
     * this, demands a recorded reason, and is bounded by the original ({@code INV-REV-02}),
     * which is why four-eyes is deliberately not required of it where the unbounded adjustment
     * earns {@code INV-AUD-04}'s regime (`PHASE_4_PLAN.md` §11). Names
     * {@code transfers.TransferReversed}, declared and emitted by the same task — the second
     * permission whose actions live outside {@code identity} and outside {@code ledger};
     * authorization stays here per ADR-0031's recorded merge, the {@code KYC_REVIEW} precedent.
     * Unlike its two ledger siblings it ships with its real check site,
     * {@code POST /v1/transfers/'{id}'/reversal}.
     */
    TRANSFER_REVERSE,

    /**
     * Command a refund of a captured payment (`P5-TSK-015`): a privileged, reasoned return of
     * captured value — {@code INV-AUD-03}'s regime, the reversal precedent applied to the
     * provider-decided rail. Distinct from {@link #TRANSFER_REVERSE} because the operations
     * and their bounds differ (a refund is bounded by its capture and decided by a third
     * party); joins {@code LEDGER_OPERATOR} rather than minting a role, because a role exists
     * when a distinct trust decision does and there is one money-operating population
     * (`P4-TSK-009`'s recorded sentence). Ships with its real check site,
     * {@code POST /v1/payments/'{id}'/refund}.
     */
    PAYMENT_REFUND,

    /**
     * Onboard a merchant (`P6-TSK-003`): create the commercial counterparty and its payable
     * ledger account, gated on the KYB decision's projection ({@code INV-KYC-05} consumed).
     * Not a money-operating permission - onboarding opens books and moves nothing through
     * them - so it does NOT join {@code LEDGER_OPERATOR}: administering counterparties is a
     * distinct trust decision (`P2-TSK-004`'s rule), held by
     * {@link RoleName#MERCHANT_ADMINISTRATOR}. Names {@code merchant.MerchantOnboarded};
     * ships with its real check site, {@code POST /v1/operator/merchants}.
     */
    MERCHANT_ONBOARD,

    /**
     * Administer an onboarded merchant (`P6-TSK-003`): suspend, reinstate and close - each a
     * reasoned judgement about a counterparty ({@code INV-AUD-03}) - and, from `P6-TSK-002`,
     * issue and revoke the merchant's API keys, and from `P6-TSK-011` propose, list and withdraw
     * its payout destinations (approving one is {@link #PAYOUT_DESTINATION_APPROVE}'s).
     * Distinct from {@link #MERCHANT_ONBOARD}
     * because the checks differ per surface (the state moves demand recorded reasons; the
     * onboarding demands the KYB gate), while one role holds both - one
     * merchant-administering population until a trust decision splits it, the
     * {@code LEDGER_OPERATOR} reasoning. Ships with its real check sites, the
     * {@code /v1/operator/merchants/'{id}'/*} state moves — and, from `P7-TSK-015`, the
     * chargeback-ratio report {@code GET /v1/operator/reports/chargeback-ratio}: every merchant's
     * card sales and chargebacks for a month, the evidence the standing judgement rests on
     * (the card schemes' monitoring number), audited per report ({@code payments.ChargebackRatioRead}).
     * A dispute's own details stay behind {@link #DISPUTE_ADMINISTER}.
     */
    MERCHANT_ADMINISTER,

    /**
     * Administer the platform's pricing (`P6-TSK-004`, ADR-0050): create fee schedules and
     * their immutable versions, and assign a merchant to one. Names
     * {@code merchant.FeeScheduleVersionCreated} and its siblings; ships with its real check
     * sites, {@code /v1/operator/fee-schedules} and
     * {@code PUT /v1/operator/merchants/'{id}'/fee-schedule}.
     *
     * <p><strong>Its own permission, because pricing is not standing.</strong> Deciding what
     * the platform charges is a commercial act; deciding whether a counterparty may trade is a
     * risk act. In most organisations those are different desks, and this permission is what
     * makes splitting them a one-line change rather than a refactor — the
     * {@link #PAYMENT_REFUND} shape.
     *
     * <p><strong>Held by {@link RoleName#MERCHANT_ADMINISTRATOR} today</strong>, because a
     * role exists when a distinct trust decision does and nothing has yet taken the decision
     * to separate pricing from counterparty administration (`P4-TSK-009`'s recorded sentence).
     * Precise vocabulary, coarse bundling — and when a phase does separate them, the
     * separation is expressible without inventing a permission after the fact.
     */
    FEE_ADMINISTER,

    /**
     * Approve or reject a proposed payout destination (`P6-TSK-011`, ADR-0056 — the plan's
     * {@code PAYOUT_APPROVE}, named for what it approves: payouts themselves have no approval
     * step, and a permission read as "approve payouts" is one somebody grants by mistake).
     * Names {@code merchant.PayoutDestinationApproved} and its siblings; ships with its real
     * check sites, the {@code .../payout-destinations/'{id}'/approval} and {@code /rejection}
     * routes.
     *
     * <p><strong>Four-eyes is distinct identities, not distinct permissions</strong> — the
     * {@code P3-TSK-021} shape, where one {@code LEDGER_ADJUST} population both proposes and
     * approves: an operator may hold this and {@link #MERCHANT_ADMINISTER} together, and the
     * approval statement refuses the proposer whatever they hold ({@code INV-AUD-04}).
     *
     * <p><strong>Held by {@link RoleName#MERCHANT_ADMINISTRATOR} today</strong>, the
     * {@link #FEE_ADMINISTER} reasoning: its own permission because a treasury desk approving
     * where money goes is a real future split, one role because nothing has yet taken that
     * decision.
     */
    PAYOUT_DESTINATION_APPROVE,

    /**
     * Initiate a payout of a merchant's payable on the merchant's behalf (`P6-TSK-012`,
     * ADR-0057 §6) — the operator route beside the merchant's own API-key route, reasoned and
     * audited as {@code merchant.MerchantPayoutInitiatedByOperator}. Ships with its real check
     * site, {@code POST /v1/operator/merchants/'{merchantId}'/payouts}.
     *
     * <p><strong>Money leaves the platform, so it is the money-operating population's</strong>:
     * held by {@link RoleName#LEDGER_OPERATOR} beside {@link #PAYMENT_REFUND}, never by
     * {@link RoleName#MERCHANT_ADMINISTRATOR} — onboarding opens books and moves nothing through
     * them, which is the sentence that role's javadoc wrote for this permission's arrival. What
     * the holder cannot do is choose where the money goes: a payout dispatches only to the
     * merchant's effective destination, which four-eyes and a cooling-off guard (ADR-0056).
     */
    MERCHANT_PAYOUT,

    /**
     * Administer payment routing (`P7-TSK-003`, ADR-0060): create an immutable routing
     * policy version, record a rail as in or out of service, and read a payment's routing
     * explanation. Names {@code payments.PaymentRoutingVersionCreated},
     * {@code payments.RailAvailabilityChanged} and
     * {@code payments.PaymentRoutingExplanationRead}; ships with its real check sites, the
     * {@code /v1/operator/routing-policy} and {@code /v1/operator/rails} routes.
     *
     * <p><strong>Its own permission, because how money travels is not whether it moves.</strong>
     * Deciding the rail a payment rides — and taking one out of service — is a payment
     * -operations judgement; posting, adjusting and refunding are acts on the money itself.
     * A routing or treasury-operations desk is a real future split, and this permission is
     * what makes it a one-line change — the {@link #FEE_ADMINISTER} shape, restated not
     * re-argued.
     *
     * <p><strong>Held by {@link RoleName#LEDGER_OPERATOR} today</strong>, because routing is
     * the money-operating population's concern (the {@link #MERCHANT_PAYOUT} arrival's
     * reasoning: it shapes what happens to payments, not to counterparties), and a role for
     * a split nobody has made is a trust decision nobody took.
     */
    PAYMENT_ROUTING_ADMINISTER,

    /**
     * Administer disputes (`P7-TSK-012`, ADR-0061): read any dispute — somebody else's
     * contested payment, its reason and its amount — and, from `P7-TSK-014`, accept one or
     * submit evidence on a payment with no merchant. Names {@code payments.DisputeRead}; ships
     * with its real check sites, {@code GET /v1/operator/disputes/'{disputeId}'} and
     * {@code GET /v1/operator/payments/'{intentId}'/disputes} — and, since `P7-TSK-014`, the
     * acts: {@code POST .../disputes/'{disputeId}'/evidence}, {@code .../representment} and
     * {@code .../acceptance} (reasoned, only where the payment credited no merchant) and the
     * audited content read {@code GET .../evidence/'{evidenceId}'}.
     *
     * <p><strong>Its own permission, because contesting money is not moving it.</strong> A
     * dispute desk answering the network with evidence is a real future split from the desk
     * that posts, refunds and routes — the {@link #PAYMENT_ROUTING_ADMINISTER} shape, restated
     * not re-argued.
     *
     * <p><strong>Held by {@link RoleName#LEDGER_OPERATOR} today</strong>: a chargeback is
     * money forced back through the rail, and answering it is payment operations — the
     * money-operating population's concern until a trust decision splits it.
     */
    DISPUTE_ADMINISTER,

    /**
     * Introduce settlement evidence and attest another person's upload (`P8-TSK-003`,
     * ADR-0066 §1–§2): {@code POST /v1/operator/settlement/files} and
     * {@code POST .../files/'{id}'/attestation}. Names
     * {@code settlement.SettlementFileUploaded} and
     * {@code settlement.SettlementFileAttested}; ships with its real check sites.
     *
     * <p><strong>Its own permission, because evidence intake is not operating the money.</strong>
     * A settlement file decides nothing until the accept leg reads it (`P8-TSK-009`), but what
     * it will decide is which clearing positions discharge into cash — so who may introduce it
     * is the phase's first money-relevant authorization, and it belongs to the reconciliation
     * population, not the money-operating one ({@code INV-SET-07}'s reasoning: the threat is a
     * single insider, and the fewer hats one desk wears the fewer files one person can both
     * introduce and vouch for). The second-person control lives <em>inside</em> this
     * population, by actor distinctness at two ranks, not across populations.
     */
    SETTLEMENT_INGEST,

    /**
     * Investigate settlement evidence (`P8-TSK-003`, ADR-0066 §7): the metadata reads —
     * {@code GET /v1/operator/settlement/sources}, {@code .../files[/'{id}']},
     * {@code .../refused-deliveries} — and the ONE content path,
     * {@code POST .../files/'{id}'/content-reads}, reasoned and audited per read
     * ({@code INV-REC-10}). Names {@code settlement.SettlementFileContentRead}; ships with its
     * real check sites.
     *
     * <p><strong>Distinct from {@link #SETTLEMENT_INGEST}</strong> because the checks differ
     * per surface (the reads demand nothing; the content read demands a recorded reason), and
     * because reading a bank statement's raw bytes — names, counterparty details,
     * {@code RESTRICTED-PII} — is a different trust decision from delivering a file whose
     * bytes one already holds. One role holds both today; the vocabulary is precise so a later
     * split is a one-line change (the {@code FEE_ADMINISTER} shape).
     */
    RECONCILIATION_INVESTIGATE,

    /**
     * Administer the reconciliation register itself (`P8-TSK-007`, ADR-0067 §8, owner
     * decision O1's second, disjoint role): the one route today is the opening-position
     * backfill, {@code POST /v1/operator/reconciliation/opening-position} — a reasoned,
     * keyed, audited act that adopts Phases 5–7's completed clearing operations as tracked
     * expectations. Names {@code reconciliation.OpeningPositionRecorded}; ships with its
     * real check site.
     *
     * <p><strong>Distinct from {@link #RECONCILIATION_INVESTIGATE} and held by a different
     * population</strong>: adopting history into the register shapes what every later proof
     * and break is judged against, which is a stronger act than reading evidence or feeding
     * it in — so it belongs to {@code RECONCILIATION_CONTROLLER}, pairwise disjoint from the
     * operator desk it oversees, exactly as that desk is disjoint from the money-operating
     * one it checks.
     */
    RECONCILIATION_ADMINISTER,

    /**
     * Resolve a reconciliation break by a person's act (`P8-TSK-015`, ADR-0071 §§2-4, owner
     * decision O1): propose a template-bound, reason-coded resolution whose lines are derived
     * from the subject's remainder - never typed - approve another person's proposal, reject
     * one with a reason, or withdraw one's own. The routes are
     * {@code POST .../breaks/'{id}'/resolutions}, {@code POST .../resolutions/'{id}'/approval},
     * {@code .../rejection} and {@code DELETE .../resolutions/'{id}'}. Names the four
     * {@code reconciliation.Resolution*} audit actions; ships with its real check sites.
     *
     * <p><strong>Distinct from {@link #RECONCILIATION_INVESTIGATE}</strong> because it moves
     * money (a write-off, a transfer, a gain) under four-eyes, which reading and annotating a
     * break never does; <strong>held by {@code RECONCILIATION_OPERATOR}, never by
     * {@code RECONCILIATION_CONTROLLER}</strong> - whoever can loosen a tolerance cannot
     * resolve the breaks it would hide. Four-eyes is distinct identities, not distinct
     * permissions: the approver holds this like the proposer.
     */
    RECONCILIATION_RESOLVE
}
