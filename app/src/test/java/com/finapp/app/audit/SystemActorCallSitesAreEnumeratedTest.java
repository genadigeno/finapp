package com.finapp.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every place the platform claims to be the actor is enumerated (`P1-TSK-022`, ADR-0021).
 *
 * <h2>The acceptance criterion is a claim about a set, so it is held against the code</h2>
 *
 * <p>{@code P1-TSK-022}'s acceptance: <em>"the number of {@code enterSystem()} call sites is reduced
 * to those that are genuinely the platform acting, <strong>and each is justified</strong>."</em>
 *
 * <p>A justification written once in a review is a snapshot. ADR-0021 called {@code enterSystem()}
 * <em>"the greppable list of places Phase 1 must revisit"</em> — and grep is a thing somebody has to
 * remember to run. This is that list, held against the code, so a <strong>new</strong> claim that the
 * platform is acting fails the build until somebody writes down why.
 *
 * <p>The {@code MfaBypassPathsAreEnumeratedTest} shape, and for the same reason: the site added in
 * Phase 4 will not be in anybody's memory of this review.
 *
 * <h2>Why the count is two rather than zero</h2>
 *
 * <p>{@code SECURITY_ARCHITECTURE.md} §Who is acting is explicit that <em>"revisit every
 * {@code enterSystem()}" reads as "remove every {@code enterSystem()}" and would be wrong</em>. The
 * sites that must go are those where a real actor exists and was not established. Both survivors are
 * on <strong>unauthenticated</strong> paths, where there is no other honest answer: attributing an
 * action to an identity the platform has not proven is worse than naming the platform, because the
 * record is then complete, plausible, about the wrong party, and permanent ({@code INV-HIST-03}).
 *
 * <h2>What this does not close</h2>
 *
 * <p>It cannot tell that a justification is <em>true</em>. An entry is a review artefact; its value
 * is that adding one requires somebody to type a sentence, and that a site appearing without one
 * stops the build. What it does close is the silent arrival of a third.
 */
@Tag("architecture")
@DisplayName("every system-actor call site is enumerated (P1-TSK-022)")
class SystemActorCallSitesAreEnumeratedTest {

    /** Where the platform claims to be the acting party, and why that is the honest answer. */
    private static final Map<String, String> ENUMERATED_SITES =
            Map.ofEntries(
                    Map.entry(
                            "com.finapp.app.registration.RegistrationService.register",
                    "POST /v1/registrations is UNAUTHENTICATED, so there is no proven actor."
                        + " Attributing the action to the Party it creates was considered and"
                        + " rejected: it is circular, and it is unavailable on the refusal path"
                        + " where nothing was created - an actor that differs between success and"
                        + " failure is worse than a uniform honest one. What carries the information"
                        + " is the audit record's TARGET, the attempted login identifier, on both"
                        + " paths. Recorded in SECURITY_ARCHITECTURE.md as a site that STAYS."),
                    Map.entry(
                            "com.finapp.app.authentication.AuthenticationService.attempt",
                    "The FAILURE BRANCH of POST /v1/authentications. There may be no identity at all"
                        + " - the login identifier may name nobody - so there is nothing to"
                        + " attribute to, and naming a guessed identity would put an unproven claim"
                        + " in a permanent record. The SUCCESS BRANCH of this same method"
                        + " establishes Actor(identityId, CUSTOMER) from the identity it just"
                        + " proved, which is asserted separately below because this enumeration is"
                        + " at METHOD granularity and cannot see which branch called."),
                    Map.entry(
                            "com.finapp.app.recovery.RecoveryApplicationService.inAFlowAsThePlatform",
                    "Recovery initiation, recovery completion and channel verification. All three"
                        + " are reached by somebody who CANNOT LOG IN - that is what recovery is"
                        + " for - so there is no proven identity to attribute the action to, and"
                        + " naming a guessed one would put an unproven claim in a permanent record."
                        + " ONE helper rather than three call sites, deliberately: a reviewer asking"
                        + " 'where does recovery claim to be the platform?' reads one method."
                        + " Adding a channel does NOT come through here - it requires a session, so"
                        + " the interceptor has already established a real actor, and that asymmetry"
                        + " is what makes the first move in a takeover cost a stolen password."),
                    Map.entry(
                            "com.finapp.app.kyc.CheckOutcomeTrail.record",
                    "A check outcome is recorded (P2-TSK-009; the site MOVED here from"
                        + " VerificationRunService.audit when P2-TSK-011 gave outcomes a second"
                        + " door - one site whichever door, because two copies of this sentence"
                        + " would drift): the platform normalising a provider's answer into its"
                        + " own vocabulary. Nobody is present when a machine records what a"
                        + " machine answered - neither a run nor a provider callback has an"
                        + " authenticated caller, and attributing the outcome to the customer"
                        + " under verification would record them as having assessed themselves."
                        + " What ties the record to the flow is the CORRELATION, and the TARGET"
                        + " names the check, whose case names the customer."),
                    Map.entry(
                            "com.finapp.kyc.CustomerOpenedOpensCase.handle",
                    "The platform's first production CONSUMER (P2-TSK-007): a registration event"
                        + " opens a KYC case. A consumer has no authenticated caller - the person"
                        + " whose registration caused this is not present, and the registration's"
                        + " own audit records already name that flow's actor. Opening the case is"
                        + " the platform's own policy act, so the platform is the honest actor;"
                        + " what ties the record to the person is the CORRELATION (the producing"
                        + " flow's, entered by the consumer shell from the message envelope) and"
                        + " the TARGET, which names the customer. The class of site every future"
                        + " consumer with an audited effect will be: each comes here and says so."),
                    Map.entry(
                            "com.finapp.app.kyc.DecisionRecording.automatically",
                    "The AUTOMATIC decision (P2-TSK-013): the platform applying its own stated"
                        + " policy to an all-clear case - INV-KYC-02's second actor case in the"
                        + " invariant's own words, 'the platform under a stated automatic"
                        + " policy'. Nobody is present: the assessment that reaches this runs"
                        + " from a verification run or a provider callback, and attributing the"
                        + " approval to whichever customer's callback happened to complete the"
                        + " last check would record them as having approved themselves"
                        + " (P2-TSK-009's reasoning, at the decision). The REVIEWER path in this"
                        + " same class never comes here - it takes the person the interceptor"
                        + " proved from the established scope, which is asserted by the"
                        + " acceptance suite's decided_by and audit assertions."),
                    Map.entry(
                            "com.finapp.payments.PaymentConfirmation.confirm",
                    "The authorization outcome's transaction (P5-TSK-009, PHASE_5_PLAN.md"
                        + " section 11's enumerated site in as many words): a provider's answer"
                        + " has no session, and attributing AUTHORIZED/FAILED/AUTH_UNKNOWN to"
                        + " the person who happened to carry the synchronous confirm would"
                        + " record them as the author of the issuer's decision - and the same"
                        + " outcome applied by the sweeper or a webhook (P5-TSK-013/-014) has"
                        + " no person at all, so the attribution must not depend on which"
                        + " resolver won the harmless race (ADR-0046). The person's own acts -"
                        + " create, confirm, cancel - are audited as the person in their own"
                        + " transactions; only the outcome application enters the platform."
                        + " Since P7-TSK-009 the SAME method holds a second scope for the"
                        + " push dispatch's Tx2 - the scheme's initiation answer has no"
                        + " session either, and the claim is this entry's verbatim at the"
                        + " second machine."),
                    Map.entry(
                            "com.finapp.payments.PaymentCapture.capture",
                    "The capture, end to end (P5-TSK-010): the continuation of a confirmed"
                        + " intent has no session whichever caller chains it - the surface"
                        + " after a synchronous AUTHORIZED (P5-TSK-011) or a resolver"
                        + " (P5-TSK-013/-014) - and the dispatch AND the outcome are both the"
                        + " platform's acts (PHASE_5_PLAN.md section 11: capture and outcome"
                        + " application as the platform). Attributing the ledger's first touch"
                        + " to whichever person's request happened to carry the chain would"
                        + " record them as the author of the provider's capture and of a"
                        + " posting they never commanded."),
                    Map.entry(
                            "com.finapp.app.payments.PaymentWebhookService.effect",
                    "The webhook-driven outcome application (P5-TSK-013, ADR-0047 section 4):"
                        + " a provider's unsolicited statement has no session at all - there is"
                        + " no person in the flow to mis-attribute to, and the same outcome"
                        + " applied by the synchronous response or the sweeper is the platform's"
                        + " act already (the P5-TSK-009 reasoning, third occurrence), so the"
                        + " attribution must not depend on which resolver wins the harmless"
                        + " race. The scope wraps only the effect: authentication, evidence and"
                        + " dedupe run before it and claim nothing. Since P7-TSK-012 the same"
                        + " one site applies the network's dispute stages (ADR-0061 section 6):"
                        + " an unsolicited chargeback statement has no session either, and the"
                        + " stage trail and its audit records name the platform whichever"
                        + " delivery wins the race."),
                    Map.entry(
                            "com.finapp.payments.PaymentSweeper.sweep",
                    "The swept resolution (P5-TSK-014, ADR-0046 section 4): a scheduled"
                        + " reconciliation query has no person at all - the cleanest case of"
                        + " the P5-TSK-009 attribution reasoning, fourth occurrence - and the"
                        + " same outcome applied by the synchronous response or a webhook is"
                        + " already the platform's act, so attribution must not depend on"
                        + " which resolver wins the harmless race. The scope wraps each row's"
                        + " query-and-resolve; the candidate read before it claims nothing."),
                    Map.entry(
                            "com.finapp.payments.PaymentRefund.refund",
                            "The refund outcome's transaction (P5-TSK-015): the operator's dispatch -"
                                + " the hold placed, the wire call commanded - is audited as the operator"
                                + " in Tx1, but the provider's answer has no session (the P5-TSK-009"
                                + " reasoning, fifth occurrence), and the same outcome a later resolver"
                                + " may apply is already the platform's act, so attributing"
                                + " COMPLETED/FAILED/UNKNOWN - and the release-and-post that rides on"
                                + " COMPLETED - to the operator would record them as the author of the"
                                + " provider's decision. The scope wraps only Tx2, the outcome"
                                + " application."),
                    Map.entry(
                            "com.finapp.payments.PaymentVoid.send",
                            "The void outcome's transaction (P7-TSK-004): the dispatch is"
                                + " audited as its own actor - the cancelling customer or the"
                                + " reasoned operator - in Tx1, but the provider's answer has"
                                + " no session (the P5-TSK-009 reasoning, sixth occurrence),"
                                + " and the same outcome the sweeper's re-send or a racing"
                                + " finisher may apply is already the platform's act, so"
                                + " attributing VOIDED/VOID_UNKNOWN/FAILED to the dispatching"
                                + " caller would record them as the author of the provider's"
                                + " decision. The scope wraps only Tx2, the outcome"
                                + " application."),
                    Map.entry(
                            "com.finapp.checkout.CheckoutExpirySweeper.sweep",
                    "The expiry sweep (P6-TSK-008, ADR-0053 section 4): a deadline passing is"
                        + " the CLEANEST case on the platform of the P5-TSK-009 reasoning,"
                        + " sixth occurrence - not merely a flow with no session, but an act"
                        + " with no requester at all. Nobody asks for an expiry; the clock"
                        + " arrives. Attributing it to the merchant who made the offer would"
                        + " record them as having withdrawn it, which is a DIFFERENT act with"
                        + " its own state (ABANDONED) and its own required reason, and the two"
                        + " must stay distinguishable in the trail. The scope wraps the whole"
                        + " tick because the candidate read is the platform's too - there is no"
                        + " other actor anywhere in this path to claim it."),
                    Map.entry(
                            "com.finapp.merchant.PayoutDestinationEffectuation.sweep",
                    "The payout destination effectuation (P6-TSK-011, ADR-0056 section 3): a"
                        + " cooling-off elapsing is the expiry sweep's case again, seventh"
                        + " occurrence - an act with no requester. The decisions were people's"
                        + " and are recorded as theirs: the proposal and the approval, each its"
                        + " own audit record with its own actor and reason. Attributing the"
                        + " EFFECT to the approver would record them as having acted days after"
                        + " they did, at a moment they chose nothing; the platform making the"
                        + " approved destination effective when its pinned deadline passes is"
                        + " exactly what happened. The scope wraps the whole tick for the same"
                        + " reason as the expiry sweep's."),
                    Map.entry(
                            "com.finapp.merchant.MerchantPayouts.initiate",
                    "The payout's outcome transaction (P6-TSK-012, ADR-0051): the"
                        + " PaymentRefund.refund reasoning, pointed at a merchant. The request"
                        + " that asked is recorded as the asker - the merchant's key or the"
                        + " operator, with their reason - in the dispatch transaction's own"
                        + " audit record; the provider's answer is then applied by the"
                        + " platform, because the same answer applied by the resolution sweep"
                        + " is the platform's act too, and attribution must not depend on"
                        + " which resolver wins the harmless race. The scope wraps the outcome"
                        + " transaction only; the dispatch before it runs as the asker."),
                    Map.entry(
                            "com.finapp.merchant.MerchantPayoutResolution.sweep",
                    "The swept payout resolution (P6-TSK-012, ADR-0046 section 4): a"
                        + " scheduled query by our reference has no person at all - the"
                        + " PaymentSweeper case, eighth occurrence - and the same outcome"
                        + " applied by the synchronous answer is already the platform's act."
                        + " The scope wraps each row's query-and-resolve; the candidate read"
                        + " before it claims nothing."),
                    Map.entry(
                            "com.finapp.payments.Withdrawals.withdraw",
                    "The withdrawal's outcome transaction (P7-TSK-008, ADR-0062 section 6):"
                        + " the MerchantPayouts.initiate reasoning, pointed at a customer's"
                        + " wallet. The person is recorded as the asker in the dispatch"
                        + " transaction's own audit record; the scheme's answer is then"
                        + " applied by the platform, because the same answer applied by the"
                        + " inquiry sweep is the platform's act too, and attribution must"
                        + " not depend on which resolver wins the harmless race. The scope"
                        + " wraps the outcome transaction only; the dispatch before it runs"
                        + " as the person."),
                    Map.entry(
                            "com.finapp.payments.OutboundCreditResolution.sweep",
                    "The swept outbound credit inquiry (P9-TSK-020): a scheduled inquiry by our"
                        + " reference has no person at all - the WithdrawalResolution case on the"
                        + " corridor - and every outcome it applies is the platform's. The scope"
                        + " wraps each row's inquire-and-resolve; the candidate read claims nothing."),
                    Map.entry(
                            "com.finapp.app.payments.CorridorCallbackService.deliver",
                    "The corridor provider's callback (P9-TSK-020, ADR-0083): a provider's"
                        + " unsolicited hint has no session - the FxCallbackService.deliver"
                        + " reasoning - and what it triggers is the platform's authenticated"
                        + " inquiry of its own outbound credit, never the callback's claim."),
                    Map.entry(
                            "com.finapp.app.crossborder.PaymentsCrossBorderExecution.recordSend",
                    "The synchronous corridor answer applied (P9-TSK-020): the outcome of a send"
                        + " is the platform's act whichever resolver wins the harmless race - the"
                        + " withdrawal's rule, the dispatch the person's and the outcome the"
                        + " platform's. The scope wraps the applier only; the evidence before it"
                        + " is retained in the same transaction."),
                    Map.entry(
                            "com.finapp.payments.WithdrawalResolution.sweep",
                    "The swept withdrawal inquiry (P7-TSK-008, ADR-0062 section 3): a"
                        + " scheduled inquiry by our reference has no person at all - the"
                        + " MerchantPayoutResolution case at the push rail - and the same"
                        + " outcome applied by the synchronous answer is already the"
                        + " platform's act. The scope wraps each row's inquire-and-resolve;"
                        + " the candidate read before it claims nothing."),
                    Map.entry(
                            "com.finapp.payments.PayInResolution.sweep",
                    "The pay-in resolution sweep (P7-TSK-009, ADR-0062 section 5): the"
                        + " WithdrawalResolution case on the inbound machine - a scheduled"
                        + " re-initiate or inquiry by our reference has no person, and the"
                        + " same outcome applied by the callback door is already the"
                        + " platform's act. The scope wraps each row's contact-and-resolve;"
                        + " the candidate read and the permit renewal before it claim"
                        + " nothing."),
                    Map.entry(
                            "com.finapp.app.payments.InstantCallbackService.effect",
                    "The instant confirmation's effect (P7-TSK-009, ADR-0047 section 4):"
                        + " the payer PSP's unsolicited statement has no session - the"
                        + " PaymentWebhookService.effect reasoning at the second rail's"
                        + " door, one enumerated site whether the statement lands on the"
                        + " machine or parks in suspense."),
                    Map.entry(
                            "com.finapp.payments.ReturnResolution.sweep",
                    "The return-payment resolution sweep (P7-TSK-010, ADR-0059 section 3):"
                        + " the PaymentSweeper refund-leg case at the push rail - a"
                        + " scheduled inquiry or permit-renewed re-drive by our reference"
                        + " has no person, and the same outcome applied by the synchronous"
                        + " dispatch is already recorded against the operator who asked."
                        + " The scope wraps each row's inquire-and-resolve; the candidate"
                        + " read before it claims nothing."),
                    Map.entry(
                            "com.finapp.payments.DisputeResponses.respond",
                    "The dispute response's outcome transaction (P7-TSK-014, ADR-0061 section"
                        + " 7): the PaymentRefund.refund reasoning, pointed at a chargeback. The"
                        + " responder who asked - the merchant's key, or the operator with their"
                        + " reason - is recorded as the asker in the dispatch transaction's own"
                        + " audit record (and as the actor of the evidence it transmitted); the"
                        + " PSP's answer is then applied by the platform, because the same answer"
                        + " applied by the resolution sweep is the platform's act too. The scope"
                        + " wraps Tx2 only."),
                    Map.entry(
                            "com.finapp.payments.DisputeResponseResolution.sweep",
                    "The dispute-response resolution sweep (P7-TSK-014): the ReturnResolution"
                        + " case at the card PSP's dispute port - a scheduled inquiry or a"
                        + " permit-renewed re-send by our reference has no person, and its"
                        + " re-send's evidence transmission is honestly the platform's. The scope"
                        + " wraps each row's inquire-and-resolve; the candidate read claims"
                        + " nothing."),
                    Map.entry(
                            "com.finapp.settlement.FileParsing.sweep",
                    "The settlement intake's parse leg (P8-TSK-008, ADR-0066 section 9): a"
                        + " scheduled parse of stored evidence has no person at all - the"
                        + " expiry-sweep case at the settlement door. The people's acts are"
                        + " already theirs: the uploader's and the attester's records stand"
                        + " with their actors, and a DECLINE runs as the person who reasoned"
                        + " it. The platform reading, canonicalising or rejecting a file is"
                        + " exactly what happened, and the rejection's audit record is"
                        + " acting-only by design. The scope wraps each file's own"
                        + " transaction; the candidate read before it claims nothing."),
                    Map.entry(
                            "com.finapp.settlement.BatchAcceptance.sweep",
                    "The settlement intake's accept leg (P8-TSK-009, ADR-0065 section 2):"
                        + " the parse leg's case one hop later - a scheduled recognition of"
                        + " stored, authenticated evidence has no person. The person who"
                        + " AUTHENTICATED an upload is on the record already (the"
                        + " attestation's own audit row, held distinct at three ranks), and"
                        + " attributing the recognition to the attester would record them as"
                        + " having posted fees at a moment they chose nothing. The platform"
                        + " sequencing, recognising and handing over an accepted batch is"
                        + " exactly what happened; the acceptance's audit record is"
                        + " acting-only by design, and a losing racer records nothing. The"
                        + " scope wraps each file's own transaction; the candidate read"
                        + " before it claims nothing."),
                    Map.entry(
                            "com.finapp.reconciliation.Matching.sweep",
                    "The matcher's run leg (P8-TSK-011, ADR-0068 section 3): a scheduled"
                        + " decision over locked, stored rows has no person at all - the"
                        + " accept leg's case one hop later. A match is a pure function of"
                        + " the pinned rule set and the frozen evidence; attributing an"
                        + " allocation, a break or a park to any person would record them"
                        + " as having judged money at a moment they chose nothing. The"
                        + " decision, its audit record and its events are acting-only by"
                        + " design; a person's later act on a break runs as that person"
                        + " through the break's own doors. The scope wraps the whole"
                        + " sweep, one transaction per chunk inside it; the worklist read"
                        + " claims nothing. Since P8-TSK-013 the same sweep carries the"
                        + " grace and rematch legs - a deadline passing and late evidence"
                        + " arriving are the expiry-sweep case at the matcher: the clock"
                        + " and the counterparty's file arrive, nobody asks."),
                    Map.entry(
                            "com.finapp.reconciliation.ReconciliationSweep.sweep",
                    "Time's observers (P8-TSK-013, ADR-0069 sections 4 and 6): an"
                        + " expectation ageing past its window, a break's severity band"
                        + " crossing, a run lost at its failure bound and a recorded key"
                        + " collision surfacing are all acts with no requester - the"
                        + " expiry-sweep case, judged in SQL on the database clock against"
                        + " stored dates. Attributing an overdue break or an escalation to"
                        + " any person would record them as having judged lateness at a"
                        + " moment they chose nothing. The scope wraps the whole sweep,"
                        + " one transaction per row inside it; the candidate reads claim"
                        + " nothing."),
                    Map.entry(
                            "com.finapp.app.merchant.PayoutReturnSweep.applyContained",
                    "The payout return worker (P8-TSK-019, ADR-0073 section 4): a return the"
                        + " beneficiary bank made, applied from the payout provider's own"
                        + " evidence, has no requester - the ReturnResolution case at the"
                        + " payout: the counterparty's file arrives, nobody asks. Attributing"
                        + " the payable's restoration to any person would record them as"
                        + " having credited a merchant at a moment they chose nothing; when a"
                        + " person DOES decide - the four-eyes transfer of a return that could"
                        + " not apply - they act as themselves through the break's own doors."
                        + " The scope wraps each item's re-read-and-apply, one transaction per"
                        + " item; the page read before it claims nothing."),
                    Map.entry(
                            "com.finapp.kyc.CounterpartyScreenings.decide",
                    "The automatic counterparty screening decision (P9-TSK-016, ADR-0081): kyc decides"
                        + " from the provider's verdict and the payee check under a stated policy -"
                        + " the DecisionRecording.automatically reasoning. Whoever registered the"
                        + " beneficiary asked for the screening as themselves; recording them as"
                        + " having cleared or held a counterparty would attribute a compliance"
                        + " judgement to a customer. A person's release or block acts as themselves."),
                    Map.entry(
                            "com.finapp.app.kyc.CounterpartyScreeningRetrySchedule.sweepOnce",
                    "The counterparty screening retry (P9-TSK-016): nobody commands a retry of an"
                        + " unavailable screening - the platform asks its provider again, and what it"
                        + " decides is the automatic decision above."),
                    Map.entry(
                            "com.finapp.app.fx.FxCoverSchedule.sweepOnce",
                    "The cover sweep (P9-TSK-012, ADR-0077): the platform is principal, and its"
                        + " cover is the platform's own trade with its provider - nobody commands a"
                        + " send, an inquiry or a requote; the customer's act ended at the booking,"
                        + " which they made as themselves. Attributing a cover leg to the customer"
                        + " would record them as having traded with the provider."),
                    Map.entry(
                            "com.finapp.app.fx.FxCoverNudge.send",
                    "The cover's post-commit nudge (P9-TSK-012): the same platform act as the"
                        + " sweep, sent sooner - off the customer's request thread, after their"
                        + " booking committed under their own name."),
                    Map.entry(
                            "com.finapp.app.fx.FxCallbackService.deliver",
                    "The FX provider's callback (P9-TSK-012, ADR-0077 section 9): a provider's"
                        + " unsolicited hint has no session - the PaymentWebhookService.effect"
                        + " reasoning - and what it triggers is the platform's authenticated"
                        + " inquiry of its own cover, never the callback's claim."),
                    Map.entry(
                            "com.finapp.app.fx.FxQuoteExpirySchedule.sweepOnce",
                    "Quote expiry (P9-TSK-008, ADR-0075 section 5): a quote lapses because the"
                        + " database clock passed its expires_at - nobody chose it. Attributing the"
                        + " EXPIRED edge to any person would record them as having closed a"
                        + " customer's price they never saw; when a person DOES close one - the"
                        + " owner's cancellation - they act as themselves. The scope wraps the"
                        + " tick's pages, each one conditional UPDATE and its events."),
                    Map.entry(
                            "com.finapp.app.settlement.SettlementPullSweep.sweep",
                    "The settlement pull schedule (P8-TSK-021, ADR-0066 section 1): the platform"
                        + " fetches a counterparty's report over the source's own credential"
                        + " because the report is owed - nobody asked. Attributing a pulled"
                        + " file to a person would record them as having delivered evidence"
                        + " they never saw; when a person DOES ask - the operator's fetch door -"
                        + " they act as themselves. The scope wraps each owed key's pull, one"
                        + " permit and one reception transaction per key."));

    @Test
    @DisplayName("no production code claims the system actor without being enumerated")
    void everySiteIsEnumerated() {
        assertThat(systemActorCallSites())
                .as("a new place claiming the platform is the actor is a new place a real actor may"
                        + " have been available and not established - which records the platform as"
                        + " having done what a person did, permanently (INV-HIST-03). Add it to"
                        + " ENUMERATED_SITES with the reason there is no honest alternative")
                .isEqualTo(new TreeSet<>(ENUMERATED_SITES.keySet()));
    }

    @Test
    @DisplayName("every enumerated site still exists, so no entry is a claim about nothing")
    void noEnumeratedSiteIsStale() {
        // An entry naming a method that has been renamed or deleted silently stops applying while
        // still reading as a live decision - the P1-TSK-015 rule for exemption lists.
        assertThat(systemActorCallSites())
                .as("an enumerated site that no longer exists has stopped justifying anything")
                .containsAll(ENUMERATED_SITES.keySet());
    }

    @Test
    @DisplayName("no site claiming the platform holds a proven session")
    void noSiteClaimsThePlatformWhileHoldingAProvenIdentity() {
        JavaClasses production = productionClasses();
        TreeSet<String> holdingAProvenIdentity = new TreeSet<>();
        for (String site : ENUMERATED_SITES.keySet()) {
            String owner = site.substring(0, site.lastIndexOf('.'));
            String method = site.substring(site.lastIndexOf('.') + 1);
            production.get(owner).getMethods().stream()
                    .filter(candidate -> candidate.getName().equals(method))
                    .filter(
                            candidate ->
                                    candidate.getRawParameterTypes().stream()
                                            .anyMatch(
                                                    parameter ->
                                                            parameter
                                                                    .getName()
                                                                    .equals(
                                                                        "com.finapp.identity.Session")))
                    .forEach(candidate -> holdingAProvenIdentity.add(site));
        }

        // THE ASSERTION THIS REPLACED WAS A STALE LIST, and it was mine.
        //
        // The first version matched package names - ".registration." or ".authentication." - as a
        // proxy for "unauthenticated", and it broke the first time a third unauthenticated surface
        // appeared, one task later, by my own hand. A proxy that needs editing whenever the codebase
        // grows is the stale-list defect this repository closes by derivation everywhere else.
        //
        // This is the property the proxy was reaching for, derived rather than listed: a method that
        // is HANDED a proven Session and still claims the platform is exactly the defect ADR-0021
        // built require() to prevent - a real actor existed and was not established, and the record
        // is then complete, plausible, about the wrong party, and permanent (INV-HIST-03).
        //
        // What it still cannot see is stated in the class javadoc: whether the path a site sits on
        // is authenticated is a property of the CALL GRAPH, not of a signature. The justification
        // remains a review artefact. This is the checkable part of it, and no more.
        assertThat(holdingAProvenIdentity)
                .as("a method handed a proven Session has an actor available, so claiming the"
                        + " platform there records the wrong party permanently (ADR-0021)")
                .isEmpty();
    }

    @Test
    @DisplayName("the authentication site still establishes a real actor on success")
    void theAuthenticationSiteStillNamesThePersonOnSuccess() {
        // The gap method granularity creates, closed for the one site where it exists.
        //
        // AuthenticationService.attempt contains BOTH branches, so it is enumerated once - which
        // means replacing the success branch's `SecurityContext.enter(new Actor(...))` with
        // enterSystem() would change nothing this enumeration can see, while recording the platform
        // as having logged somebody in. That record is complete, plausible, about the wrong party,
        // and permanent (INV-HIST-03).
        //
        // Asserting the real-actor call is present is what makes that mutation fail. The behavioural
        // half is P1-TSK-010's AuthenticationEndpointDatabaseTest, which asserts the record names
        // the proven identity; neither replaces the other.
        assertThat(callsFrom("com.finapp.app.authentication.AuthenticationService", "attempt"))
                .as("the success branch must establish a real actor, or the platform is recorded as"
                        + " having logged somebody in")
                .contains("com.finapp.platform.security.SecurityContext.enter");
    }

    @Test
    @DisplayName("every module with production code is within reach of this rule")
    void everyModuleWithProductionCodeIsAnalysed() {
        java.util.Set<String> analysed =
                productionClasses().stream()
                        .map(com.finapp.app.architecture.ProductionModules::of)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // The sibling idiom, and it is here because P1-TSK-021's completion gate found this suite's
        // predecessor carrying a bare isNotEmpty() - the P0-TSK-008 finding, where a rule that stops
        // reaching a module reports safety it never checked. Deviating from the idiom four other
        // rule suites already use is what hid secretsAreWrapped's inversion in the first place.
        assertThat(analysed)
                .as("every module with production classes must be within reach, or an action"
                        + " declared or emitted there is simply invisible to this rule")
                .containsAll(
                        com.finapp.app.architecture.ProductionModules
                                .onClasspathWithProductionClasses());
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees production code and finds the sites")
    void theGuardHasTeeth() {
        assertThat(productionClasses())
                .as("the sweep must actually import production classes")
                .isNotEmpty();

        // Without this, everySiteIsEnumerated passes over a detector that matches nothing and the
        // list becomes decoration - the "green while checking nothing" failure this repository has
        // met repeatedly.
        assertThat(systemActorCallSites())
                .as("the detector must find the sites that exist today")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------

    /**
     * Production methods calling {@code SecurityContext.enterSystem()}.
     *
     * <p>A method <em>call</em>, so a mention in a comment or a javadoc cannot satisfy it — the
     * mistake {@code P1-TSK-021}'s gate found when a source-text {@code contains} matched prose.
     * {@code SecurityContext} itself is excluded: it declares the method.
     */
    private static TreeSet<String> systemActorCallSites() {
        TreeSet<String> sites = new TreeSet<>();
        for (JavaClass javaClass : productionClasses()) {
            if (javaClass.getName().equals("com.finapp.platform.security.SecurityContext")) {
                continue;
            }
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                if (call.getTargetOwner()
                                .getName()
                                .equals("com.finapp.platform.security.SecurityContext")
                        && call.getName().equals("enterSystem")) {
                    sites.add(
                            call.getOriginOwner().getName() + "." + call.getOrigin().getName());
                }
            }
        }
        return sites;
    }

    /** Every method this method calls, as {@code owner.name}. */
    private static TreeSet<String> callsFrom(String type, String method) {
        TreeSet<String> called = new TreeSet<>();
        JavaClasses production = productionClasses();
        if (!production.contain(type)) {
            throw new IllegalStateException("Not on the classpath: " + type);
        }
        production.get(type).getMethodCallsFromSelf().stream()
                .filter(call -> call.getOrigin().getName().equals(method))
                .forEach(call -> called.add(call.getTargetOwner().getName() + "." + call.getName()));
        if (called.isEmpty()) {
            throw new IllegalStateException("No method " + method + " calling anything in " + type);
        }
        return called;
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }
}
