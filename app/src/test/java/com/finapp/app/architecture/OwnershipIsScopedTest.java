package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every resource-scoped persistence operation is classified (`P1-TSK-021`, ADR-0031).
 *
 * <h2>Why a build rule exists at all, when the ADR said none could</h2>
 *
 * <p>ADR-0031 records: <em>"An operation missing its ownership check is not detectable by the
 * boundary rule ... no build rule closes this."</em> That is true of the <strong>boundary</strong>
 * rule and it is not the end of the matter, because {@code INV-IDN-05} taught the lesson one
 * milestone earlier: <em>a list of tests is a snapshot</em>, and the operation added in Phase 4 will
 * not be in it.
 *
 * <p>So this does what {@code MfaBypassPathsAreEnumeratedTest} does for session origins. It does not
 * decide whether an operation is safe. It forces every operation that <em>could</em> be unsafe to be
 * <strong>classified</strong>, and fails the build on a new one.
 *
 * <h2>The five correct statements that look exactly like the defect</h2>
 *
 * <p>Surveying {@code identity} found five statements targeting a row <strong>by primary key with no
 * owner predicate</strong> — {@code SessionStore.revoke}, {@code touch},
 * {@code MfaEnrolmentStore.confirm}, {@code consumeStep}, {@code CredentialStore.supersede}. All
 * five are correct, and in SQL all five are indistinguishable from the defect ADR-0031 describes.
 *
 * <p>The difference is <strong>provenance</strong>: an identifier that came from an owner-constrained
 * read is safe, and one that came from a request is not. A rule that merely forbade the shape would
 * have produced five false positives on its first run — and ADR-0019's own reasoning is that a rule
 * with an exemption list is a rule somebody turns off. So the rule classifies rather than forbids.
 *
 * <h2>What this closes, and what it does not — stated rather than implied</h2>
 *
 * <ul>
 *   <li><strong>Closes:</strong> a new resource-scoped store operation shipping with nobody having
 *       decided how ownership is established. It also fails when an {@link Scope#OWNER_SCOPED}
 *       statement loses its owner predicate, which the behavioural test catches too — two controls
 *       blind in different directions, the {@code P1-TSK-020} argument.
 *   <li><strong>Does not close:</strong> an {@code OWNER_SCOPED} statement binding the
 *       <em>wrong</em> owner. The shape would be right and the parameter wrong. Only the negative
 *       behavioural test catches that, which is why both exist and why {@link #NEGATIVE_TESTS}
 *       is held against the register.
 *   <li><strong>Does not close:</strong> whether an {@code AUTHORITATIVE_ID} claim is true. That
 *       claim is a review artefact; its value is that a sixth one cannot be added without somebody
 *       writing the sentence.
 *   <li><strong>Does not close:</strong> an ownership decision taken somewhere that issues no SQL.
 * </ul>
 *
 * <h2>The tenant's packages see further (`P6-TST-001`)</h2>
 *
 * <p>Two shapes hid every statement they carried, and both were found in the tenant's own modules.
 * {@code checkout} holds each cross-module reference by value as a bare {@code UUID} (ADR-0029),
 * which is not an {@code EntityId}; and a store that hands its statement to a generic helper
 * ({@code one(unitOfWork, sql, ...)}) splits the two halves this rule looks for, the identifier
 * in the caller and {@code prepareStatement} in the helper, so neither method carries both. In
 * {@link #TENANT_PACKAGES} a {@code UUID} parameter is a resource identifier and a same-class
 * helper's SQL is the caller's own, one hop deep. {@link #tenantColumnsLiveOnlyInTheTenantsPackages}
 * pins why those two packages are enough: a statement over a tenant column anywhere else fails the
 * build, because the widening would not see it. Widening the other modules is recorded work, not
 * this rule's claim.
 */
@Tag("architecture")
@DisplayName("every resource-scoped persistence operation is classified (P1-TSK-021)")
class OwnershipIsScopedTest {

    /** How an operation establishes that the caller may act on <em>this</em> resource. */
    private enum Scope {

        /**
         * The owner is a predicate in the statement itself.
         *
         * <p>Never a load-then-compare: that is a TOCTOU race, and it checks a copy of the truth
         * rather than the truth (ADR-0031).
         */
        OWNER_SCOPED,

        /**
         * The resource identifier can only have come from an owner-constrained read.
         *
         * <p>The entry names that read. The check happened there; repeating it would be checking
         * the same fact twice against the same authority.
         */
        AUTHORITATIVE_ID,

        /**
         * The rows have no owner at all.
         *
         * <p>Platform infrastructure — an outbox row belongs to a flow, not to a party — so there is
         * no ownership question to answer. <strong>This is an escape hatch and is written down as
         * one:</strong> like {@link #AUTHORITATIVE_ID} it is a review artefact rather than a
         * mechanical proof, and its value is that labelling a customer-owned table with it requires
         * somebody to type a sentence that is false.
         */
        NOT_OWNED,

        /**
         * Holding the token <em>is</em> the authorisation.
         *
         * <p>Added by {@code P1-TSK-023}. Recovery completion targets a request by an identifier
         * that came straight from the URL, so it is not {@link #AUTHORITATIVE_ID} — and it is not
         * scoped by owner either, because the caller is somebody who <strong>cannot log in</strong>
         * and has no identity to scope against. What authorises it is a high-entropy single-use
         * token sent to a previously verified channel.
         *
         * <p>Squeezing this into one of the others would have been the easy move and would have made
         * the register say something false. Naming it is the point: the statement must carry the
         * bearer predicate, which is checked, and the security argument then rests on where the token
         * was sent — which is {@code INV-IDN-06} and is checked by the abuse-case tests.
         */
        BEARER_SCOPED,

        /**
         * An administrator names the subject, and the subject is deliberately somebody else.
         *
         * <p>Added by {@code P1-TSK-028}, and it exists because that task <strong>broke an
         * assumption this class had been resting on</strong>: {@link #takesAResourceIdentifier}
         * excluded {@code IdentityId} on the reasoning that it <em>is</em> the owner, so an
         * operation scoped by one is scoped by definition. That is true of every operation written
         * before — a customer acting on their own identity — and false of an administrative one,
         * where the identifier comes straight from a URL and names a different person entirely.
         *
         * <p>So the exclusion is now conditional, and an operation that reaches a row of
         * {@code identity.identity} <em>by primary key</em> must be classified. There is no
         * ownership predicate to check and there should not be one: what replaces it is a
         * permission at the boundary and the <strong>not-self</strong> rule in the domain, which is
         * ownership inverted. The entry must name the check that stands in for the missing
         * predicate, so an operation added later cannot inherit this label without one.
         */
        ADMINISTERED,

        /**
         * The identifier is derived from a proven {@code Session} held in memory, and no statement
         * in the chain carries an owner predicate because none needs to.
         *
         * <p>Added by {@code P1-TSK-030}, and it exists because that task tried
         * {@link #AUTHORITATIVE_ID} first and <strong>this rule refused it</strong>. The chain for
         * {@code /v1/me} is {@code Session.identityId() → Identity.partyId() → PartyId}, and the
         * read in the middle is {@code JdbcIdentityStore.findById} — which {@code P1-TSK-028}
         * classified {@link #ADMINISTERED} precisely because an administrator names its subject from
         * a URL. Citing it as owner-constrained would have been a claim that is false, and the guard
         * said so in those words: <em>every operation citing it inherits the gap</em>.
         *
         * <p>This is {@code P1-TSK-021}'s recorded uncheckable case, arriving: <em>"SessionRotation
         * holds a proven Session object rather than reading one, so there is no statement to
         * inspect."</em> The proof happened at the door, in
         * {@code SessionAuthenticationInterceptor}, before any of this ran.
         *
         * <p><strong>So the entry names the endpoint rather than a read</strong>, and
         * {@link #sessionDerivedEndpointsTakeNoIdentifier} checks the one thing that is mechanically
         * checkable and is also the actual control: that endpoint's handlers accept
         * <strong>no request-supplied identifier at all</strong>. An endpoint with nothing to name a
         * resource with cannot be pointed at somebody else's.
         */
        SESSION_DERIVED,

        /**
         * An external system names the resource, and a signature is what authorises the naming.
         *
         * <p>Added by {@code P2-TSK-011} for the provider-callback door, and it is a new class
         * because every existing label would say something false: not {@link #ADMINISTERED} (no
         * permission, no not-self rule — the caller is a machine, not a person with a role), not
         * {@link #AUTHORITATIVE_ID} (the identifier comes straight from the request body, which
         * is exactly the provenance that class excludes), not {@link #BEARER_SCOPED} (the
         * statement carries no bearer predicate — the proof happened at the boundary, over the
         * whole body). Squeezing it in would repeat the mistake this register's history warns
         * about: {@code SESSION_DERIVED} and {@code BEARER_SCOPED} both exist because a squeeze
         * would have lied.
         *
         * <p>What stands in for the ownership predicate, and the entry must say so: the HMAC
         * signature verified over the raw body <em>before</em> the read runs (the caller proved
         * it is the provider we dispatched to); the identifier is one the platform itself handed
         * out — dispatch commits before the provider is ever called, so a check id the provider
         * presents is a check id we gave it; and every write the callback can trigger is a
         * conditional transition whose losing branch appends evidence and changes nothing.
         */
        SIGNED_CALLBACK
    }

    /**
     * Every persistence method taking a resource identifier, and how ownership is established.
     *
     * <p>An entry is a claim, and writing the claim is the point of the list rather than a way past
     * it. A method that appears here for the first time is the moment to ask whether the identifier
     * can reach it from a request.
     */
    private static final Map<String, Entry> REGISTER =
            Map.ofEntries(
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.revokeOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "DELETE /v1/sessions/{id} - a resource identifier from the"
                                        + " request (this entry said 'the only' such operation"
                                        + " until P3-TSK-013's balance read became the second)."
                                        + " The owner is the proven session's identity.")),
                    Map.entry(
                            "com.finapp.accounts.JdbcCustomerAccountStore.lockOwnedBy",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "DELETE /v1/me/accounts/{id} - findOwnedBy's statement plus"
                                        + " FOR UPDATE, the closers' serialization point"
                                        + " (P3-TSK-014). Same ownership predicate, same"
                                        + " session-derived customer: a stranger's close finds"
                                        + " nothing to lock.")),
                    Map.entry(
                            "com.finapp.accounts.JdbcCustomerAccountStore.lockOwnedForShare",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "POST /v1/me/accounts/{id}/currencies - findOwnedBy's"
                                        + " statement plus FOR SHARE, the wallet openers' rank"
                                        + " beneath the close (P9-TSK-004). Same ownership"
                                        + " predicate, same session-derived customer: a"
                                        + " stranger's addition finds nothing to lock.")),
                    Map.entry(
                            "com.finapp.accounts.JdbcCustomerAccountStore.moveStatus",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.accounts.JdbcCustomerAccountStore.lockOwnedBy",
                                    "P3-TSK-014's close: the identifier reaching this method"
                                        + " comes only from lockOwnedBy - owner in the"
                                        + " statement, row lock held - so the conditional's own"
                                        + " AND status = ? is the machine's edge, not an"
                                        + " ownership check.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcLedgerAccountStore.moveStatus",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "A ledger account's status is the accounting's own fact"
                                        + " (P3-TSK-014): the one production caller is"
                                        + " AccountClosing, whose identifiers come from"
                                        + " lockOwnedForUpdate(ownerRef) under the product"
                                        + " row's owner-scoped lock - and the ledger itself"
                                        + " knows only an opaque owner_ref (ADR-0042). The"
                                        + " surfaces that control disclosure remain the"
                                        + " product's.")),
                    Map.entry(
                            "com.finapp.accounts.JdbcCustomerAccountStore.findOwnedBy",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "GET /v1/me/accounts/{id}/balance - the identifier comes from"
                                        + " the path, and customer_id = ? in the statement is the"
                                        + " ownership check (P3-TSK-013). The customer itself is"
                                        + " session-derived (findLiveCustomerFor), so not-yours,"
                                        + " unknown and malformed are one empty answer and one"
                                        + " 404.")),
                    Map.entry(
                            "com.finapp.paymentmethods.JdbcPaymentMethodStore.findOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "DELETE /v1/me/payment-methods/{id}'s second half"
                                        + " (P5-TSK-005): after the conditional detach matched"
                                        + " nothing, this any-status read - party_id = ? in"
                                        + " the statement - is what tells the caller's own"
                                        + " already-detached row (converge, 204) from unknown"
                                        + " and not-yours (one 404). Without the predicate a"
                                        + " stranger's DELETE of a live row would answer 204"
                                        + " and read as theirs.")),
                    Map.entry(
                            "com.finapp.paymentmethods.JdbcPaymentMethodStore.detach",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "DELETE /v1/me/payment-methods/{id} (P5-TSK-005's surface;"
                                        + " the store landed with P5-TSK-004) - the identifier"
                                        + " comes from the path, and party_id = ? in the"
                                        + " statement is the ownership check: a payment method"
                                        + " belongs to the Party, and the conditional's row"
                                        + " count folds not-yours and already-detached into"
                                        + " one indistinguishable false.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "Confirm and cancel (P5-TSK-009's commands; the surface is"
                                        + " P5-TSK-011) - the identifier comes from the caller,"
                                        + " and party_id = ? in the statement is the ownership"
                                        + " check: not-yours and does-not-exist are one empty"
                                        + " answer and will be one 404, never an oracle over"
                                        + " other people's payments.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentIntentStore.findById",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "Two callers. The converge re-read after a lost conditional"
                                        + " transition, inside the same command transaction that"
                                        + " already passed findOwned - authoritative there. And,"
                                        + " since P5-TSK-015, the refund command's read: the"
                                        + " identifier comes from the URL of"
                                        + " POST /v1/payments/{id}/refund and names SOMEBODY"
                                        + " ELSE'S payment - the operation, not a defect (the"
                                        + " P1-TSK-028 class). What stands in for the missing"
                                        + " ownership predicate:"
                                        + " @RequiresPermission(PAYMENT_REFUND) at the boundary,"
                                        + " asserted with nothing written by"
                                        + " PaymentRefundEndpointDatabaseTest's permissionless"
                                        + " refusal. Customer HTTP reads go through findOwned.")),
                    Map.entry(
                            "com.finapp.fx.JdbcQuoteStore.findOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P9-TSK-008. GET /v1/me/fx/quotes/{id} - a resource identifier from the request; the"
                                        + " owner is the session's party, owner_party_id = ? in the statement: a stranger's"
                                        + " id, a malformed one and an absent one are one 404.")),
                    Map.entry(
                            "com.finapp.fx.JdbcQuoteStore.lockOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P9-TSK-008. POST /v1/me/fx/quotes/{id}/cancellation - findOwned's statement plus FOR" + " UPDATE, the cancellation's serialization point against the sweep. Same ownership" + " predicate: a stranger's cancel finds nothing to lock.")),
                    Map.entry(
                            "com.finapp.fx.JdbcCoverStore.byQuote",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-021. A quote's cover of one kind, read or locked by the wanted-position rule"
                                        + " (CoverUnwinds) inside the abandonment writer's transaction, the quote already"
                                        + " locked by lockOwned under its owner - never a request value, never a surface.")),
                    Map.entry(
                            "com.finapp.fx.JdbcCoverStore.lockWanted",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.fx.JdbcQuoteStore.lockOwned",
                                    "P9-TSK-012. Whether a cover's quote still wants it - two statuses FOR SHARE, read by"
                                        + " the platform's cover legs (FxCoverOutcomes, FxCoverDispatch) only, never a surface;"
                                        + " the quote id is the cover row's, written in the conversion's transaction from the"
                                        + " quote lockOwned resolved as the caller's own and frozen by fx V006's trigger.")),
                    Map.entry(
                            "com.finapp.fx.JdbcQuoteStore.plan",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.fx.JdbcQuoteStore.lockOwned",
                                    "P9-TSK-009. The frozen posting plan, read by FxConversion.convert only on the id"
                                        + " lockOwned resolved as the caller's own in the same transaction (the quote row is"
                                        + " held FOR UPDATE); V005's freeze makes the owner immutable.")),
                    Map.entry(
                            "com.finapp.fx.JdbcQuoteStore.transition",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.fx.JdbcQuoteStore.lockOwned",
                                    "P9-TSK-009. The private conditional UPDATE behind expire and execute (accept moved to"
                                        + " judged, X-TSK-016) - each called by FxConversion.convert only on the id lockOwned"
                                        + " resolved as the caller's own in the same transaction, the row still held FOR UPDATE.")),
                    Map.entry(
                            "com.finapp.fx.JdbcQuoteStore.judged",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.fx.JdbcQuoteStore.lockOwned",
                                    "X-TSK-016. The private conditional UPDATE behind accept and cancel that returns the"
                                        + " instant it judged - called by FxConversion.convert and QuoteLifecycle.cancel only"
                                        + " on the id lockOwned resolved as the caller's own in the same transaction (cancel's"
                                        + " P9-TSK-008 entry moved here with it: V005's freeze makes the owner immutable, so"
                                        + " the ownership that read established still holds).")),
                    Map.entry(
                            "com.finapp.fx.JdbcQuoteStore.insertEvent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "X-TSK-016. The private insert behind appendEvent and appendEventAt (appendEvent's"
                                        + " P9-TSK-008 entry moved here with it): the append-only history row, written beside the"
                                        + " edge it evidences on the id the caller just inserted, locked as its own or moved by"
                                        + " the expiry conditional - the JdbcCheckoutSessionStore.appendHistory reasoning: a"
                                        + " history row belongs to the quote it names, and an unowned provenance cannot be cited"
                                        + " as AUTHORITATIVE_ID.")),
                    Map.entry(
                            "com.finapp.fx.JdbcTradeStore.insert",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-009. The trade's birth: the id is minted by the conversion (FxTradeId.next),"
                                        + " never a request value, and the owner is copied from the plan of the quote"
                                        + " lockOwned resolved - V006's birth trigger requires the copy to equal the quote's.")),
                    Map.entry(
                            "com.finapp.fx.JdbcTradeStore.attachEntry",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-009. Attaches the posted entry to the trade the same transaction just inserted"
                                        + " on its minted id - never a request value; V006's edge trigger admits it once.")),
                    Map.entry(
                            "com.finapp.fx.JdbcTradeStore.find",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-025. The operator's trade reversal: a trade identifier from an FX_TRADE_REVERSE"
                                        + " holder's request - an operator acts on any customer's trade by design, and every"
                                        + " act is four-eyes and audited; the customer's own read stays findOwned.")),
                    Map.entry(
                            "com.finapp.fx.JdbcTradeStore.lock",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-025. The same trade the reversal found, locked after its quote (the lock order) -"
                                        + " the JdbcTradeStore.find reasoning.")),
                    Map.entry(
                            "com.finapp.fx.JdbcTradeStore.reverse",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-025. BOOKED -> REVERSED on the trade the approval locked, in the approval's"
                                        + " transaction - never a request value beyond the operator's; V006's edge trigger"
                                        + " admits the one edge.")),
                    Map.entry(
                            "com.finapp.fx.JdbcTradeReversalStore.propose",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-025. A proposal on the trade the operator named, locked in the same transaction -"
                                        + " the JdbcTradeStore.find reasoning; V009's birth trigger admits a BOOKED conversion"
                                        + " only.")),
                    Map.entry(
                            "com.finapp.fx.JdbcTradeStore.findOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P9-TSK-009. GET /v1/me/fx/conversions/{tradeId} - a resource identifier from the"
                                        + " request; owner_party_id = ? in the statement: a stranger's id, a malformed one"
                                        + " and an absent one are one 404.")),
                    Map.entry(
                            "com.finapp.fx.JdbcPricingPolicyStore.holdActive",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-008. The pinned version's FOR SHARE re-read in the quote's Tx2: the id comes only" + " from the quote request the same flight stored - never a request value; the pricing" + " policy is platform-wide configuration with no owner to scope by.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditStore.complete",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-020. The one applier's completion edge: the credit id comes only from the"
                                        + " row it locked FOR UPDATE in the same transaction - found by our own"
                                        + " end-to-end reference or the sweep's candidate read, never a request value -"
                                        + " and the edge is conditional on the status read under that lock.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditStore.fail",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-020. The one applier's failure edge, on the row it locked - the"
                                        + " JdbcOutboundCreditStore.complete reasoning.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditStore.recordProviderReference",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-020. The provider's reference stored once on the row the applier locked -"
                                        + " the JdbcOutboundCreditStore.complete reasoning; set-once by its conditional.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditStore.markDelivered",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-020. A completed credit's delivery stored once on the row the applier"
                                        + " locked - the JdbcOutboundCreditStore.complete reasoning.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditReturnStore.findByCredit",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-023. The return fact read by the credit it returns: the credit id comes only"
                                        + " from the row an applier or the resolution port locked FOR UPDATE (found by our"
                                        + " end-to-end reference, the provider's reference through its claim, or a sweep's"
                                        + " candidate) - never a request value.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditStore.requestRecall",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-024. The recall mark: the credit id comes only from the payment the customer's"
                                        + " cancellation found by findOwned (the owner a predicate), its credit locked FOR"
                                        + " UPDATE in the same transaction - never a request value; set once by its conditional.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditStore.recordRecallOutcome",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-024. The provider's recall answer stored once on the row the applier locked -"
                                        + " the JdbcOutboundCreditStore.complete reasoning.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditStore.move",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-019. The outbound credit id comes only from the credit this flight's"
                                        + " authorization dispatched (the payment it committed, found by the owner's"
                                        + " dispatch key), locked FOR UPDATE by lock in the same transaction - never a"
                                        + " request value; the edge is conditional on the status read under the lock.")),
                    Map.entry(
                            "com.finapp.payments.JdbcOutboundCreditStore.renewPermit",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-019. A takeover's permit renewal: the credit id comes only from the"
                                        + " payment the same key's flight committed (byDispatchKey, the owner a"
                                        + " predicate) - never a request value.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcBeneficiaryStore.appendEvent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-017. The beneficiary id comes only from a row this transaction"
                                        + " just inserted or locked - by lockOwned (the owner a predicate) or"
                                        + " lockById (kyc's deciding transaction, the beneficiary named by its screening reference) - never a request value;"
                                        + " the history is append-only by grant.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcBeneficiaryStore.insertSelection",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-017. The policy id comes only from the active version read in the"
                                        + " same transaction (CorridorPolicyStore.active) - platform-wide"
                                        + " configuration with no owner - and the selection id is minted here.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcBeneficiaryStore.pointScreening",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-018. Called only inside kyc's deciding transaction (T-e), on the"
                                        + " beneficiary just locked FOR UPDATE by lockById - its id parsed from the"
                                        + " screening's own request reference, never a request value - to make a"
                                        + " decided re-screen the current clearance.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcBeneficiaryStore.move",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-017. The beneficiary id comes only from a row locked FOR UPDATE in"
                                        + " the same transaction - lockOwned, whose statement carries the owner"
                                        + " predicate (a customer's revocation), or lockById (kyc's T-e) -"
                                        + " and the move is conditional on the expected status.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcCorridorPolicyStore.insertProposal",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-015. The identifier is minted by the domain (CorridorPolicyId.next) inside the proposal's own transaction - never a request value. The corridor policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(CROSSBORDER_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " CorridorAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcCorridorPolicyStore.decide",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-015. The version is locked FOR UPDATE by lock(id) in the same transaction first, and the move is conditional on its status (V002's machine trigger beneath). The corridor policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(CROSSBORDER_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " CorridorAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcCorridorPolicyStore.retire",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-015. The id comes only from lockActive's row, locked in the same transaction - never a request value. The corridor policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(CROSSBORDER_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " CorridorAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcCorridorPolicyStore.appendEvent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-015. The id comes only from a row this transaction just inserted or locked; the history is append-only for every writer. The corridor policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(CROSSBORDER_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " CorridorAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.crossborder.JdbcCorridorPolicyStore.corridorsOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-015. A private loader: the version id comes only from a version row this store just read. The corridor policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(CROSSBORDER_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " CorridorAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditDataRequestStore.byId",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-006. The private reader behind find and lock: the data request id is minted"
                                        + " by credit when the request is opened (or returned by the sweep's claim), never"
                                        + " a customer's value. A data request is the platform's collection record with no"
                                        + " customer door in this task; the customer's view arrives with P10-TSK-017.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditDataRequestStore.transition",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-006. Every move takes an id from a row this transaction locked FOR UPDATE"
                                        + " (lock(id)), and each statement is conditional on the expected status; no door"
                                        + " hands it a caller's identifier.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditDataRequestStore.insertAttempt",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-006. An insert keyed by a data request this transaction locked FOR UPDATE;"
                                        + " the (request, attempt) primary key arbitrates two writers of one attempt.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditDataRequestStore.insertRecord",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-006. The record's id is minted here and its data request is the row this"
                                        + " transaction locked FOR UPDATE from REQUESTED; UNIQUE (data_request_id) refuses"
                                        + " a second record whatever the caller.")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionSnapshotStore.recordOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-008. The data request id comes only from a row this transaction locked"
                                        + " FOR UPDATE (lockDataRequestsOf, by the decision request the caller holds"
                                        + " locked), never a customer's value; the freeze has no customer door - the"
                                        + " deciding transaction (P10-TSK-015) is its only caller.")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionSnapshotStore.insertSnapshot",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-008. The snapshot id is minted by the freezer; UNIQUE (decision_request_id,"
                                        + " sequence) arbitrates every writer, and the table refuses any change once born.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditAssessmentStore.bySnapshot",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-011. The snapshot id is the snapshot the assessor holds - frozen in the"
                                        + " deciding transaction (P10-TSK-015), never a customer's value; the assessment"
                                        + " has no customer door.")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.ownedBy",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "GET /v1/me/credit/decision-requests/{id} - the identifier from the path, and"
                                        + " party_id = ? in the statement is the session's own party; another party's"
                                        + " request and an absent one are one empty answer, the 404 (P10-TSK-014).")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.lockOwnedBy",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "POST /v1/me/credit/decision-requests/{id}/cancellation - ownedBy's statement plus"
                                        + " FOR UPDATE (lock order element (2)), the cancellation's serialization point"
                                        + " (P10-TSK-014).")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.insert",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-014. The request id is minted by DecisionRequests (DecisionRequestId.next) inside"
                                        + " the submission's own transaction, never a request value; the party is the"
                                        + " session's own, resolved from the proven Session; the partial UNIQUE (party_id,"
                                        + " product) over the open states arbitrates every writer.")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.history",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-014, the JdbcWithdrawalStore.appendHistory shape: the private trail writer"
                                        + " behind insert and transition, its id only the one the caller just inserted or"
                                        + " just moved under its own conditional - append-only by trigger.")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.lock",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-015. The platform's progress step: the request id comes only from claimDue's"
                                        + " page (FOR UPDATE SKIP LOCKED over the due open requests) - never a request"
                                        + " value; the progress has no customer door, and the request's owner is the party"
                                        + " it carries, not the caller.")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.pin",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-015. The id is the one the step holds FOR UPDATE through lock, under its own"
                                        + " SUBMITTED conditional; the pins are the ACTIVE versions read FOR SHARE in the same"
                                        + " transaction, written once by the V010 trigger's rule.")),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.transition",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.credit.JdbcDecisionRequestStore.lockOwnedBy",
                                    "P10-TSK-014. The request id comes from lockOwnedBy, which this transaction holds"
                                        + " FOR UPDATE under party_id = ? - the cancellation's only door; the progress"
                                        + " steps (P10-TSK-015) take it from their own claimed page.")),
                    Map.entry(
                            "com.finapp.credit.JdbcPolicyEvaluationStore.byAssessment",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-013. The assessment id is the assessment the evaluator holds - born in the"
                                        + " deciding transaction (P10-TSK-015) from the snapshot it froze, never a"
                                        + " customer's value; the evaluation has no customer door.")),
                    Map.entry(
                            "com.finapp.credit.JdbcScorecardStore.insertProposal",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-011. The version id is minted by the administration; a scorecard model is"
                                        + " platform-wide configuration with no owner to scope by, and every door is"
                                        + " behind @RequiresPermission(CREDIT_POLICY_ADMINISTER), asserted per route by"
                                        + " ScorecardAdministrationEndpointDatabaseTest with nothing written.")),
                    Map.entry(
                            "com.finapp.credit.JdbcScorecardStore.appendEvent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-011. The version id comes only from a row this transaction just inserted"
                                        + " or locked FOR UPDATE; the history is append-only for every writer.")),
                    Map.entry(
                            "com.finapp.credit.JdbcScorecardStore.decide",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-011. The id comes only from a row this transaction locked FOR UPDATE"
                                        + " (lock(id)), the move conditional on PROPOSED; the model is platform-wide"
                                        + " configuration behind CREDIT_POLICY_ADMINISTER, four-eyes by CHECK.")),
                    Map.entry(
                            "com.finapp.credit.JdbcScorecardStore.retire",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-011. The id comes only from the family's ACTIVE row this transaction locked"
                                        + " FOR UPDATE (lockActive), the move conditional on ACTIVE, and a deferred trigger"
                                        + " refuses a retirement that commits without its successor.")),
                    Map.entry(
                            "com.finapp.credit.JdbcScorecardStore.model",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-011. A read of platform-wide configuration: the version id is the one a"
                                        + " snapshot pinned or one this transaction locked; a scorecard has no owner to"
                                        + " scope by and no customer door.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditPolicyStore.insertProposal",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-012. The version id is minted by the administration; a credit policy is"
                                        + " platform-wide configuration with no owner to scope by, and every writing door is"
                                        + " behind @RequiresPermission(CREDIT_POLICY_ADMINISTER), asserted per route by"
                                        + " CreditPolicyEndpointDatabaseTest with nothing written.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditPolicyStore.appendEvent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-012. The version id comes only from a row this transaction just inserted"
                                        + " or locked FOR UPDATE; the history is append-only for every writer.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditPolicyStore.decide",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-012. The id comes only from a row this transaction locked FOR UPDATE"
                                        + " (lock(id)), the move conditional on PROPOSED; the policy is platform-wide"
                                        + " configuration behind CREDIT_POLICY_ADMINISTER, four-eyes by CHECK.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditPolicyStore.retire",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-012. The id comes only from the product's ACTIVE row this transaction"
                                        + " locked FOR UPDATE (lockActive), the move conditional on ACTIVE, and a deferred"
                                        + " trigger refuses a retirement that commits without its successor.")),
                    Map.entry(
                            "com.finapp.credit.JdbcCreditPolicyStore.policy",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P10-TSK-012. A read of platform-wide configuration: the version id is one the"
                                        + " effective periods or the ACTIVE status answered, or one a snapshot pinned; a"
                                        + " policy has no owner to scope by, and its one door is the investigator's, behind"
                                        + " @RequiresPermission(CREDIT_INVESTIGATE).")),
                    Map.entry(
                            "com.finapp.kyc.JdbcCounterpartyScreeningStore.decideAutomatically",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-016. The screening id comes only from a row this"
                                        + " transaction locked FOR UPDATE (lock(id)) - minted by kyc"
                                        + " at the request, never a customer's value - and the move"
                                        + " is conditional on the expected unanswered status. A"
                                        + " counterparty screening is kyc's compliance record with no"
                                        + " customer owner to scope by; no customer door reaches it.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcCounterpartyScreeningStore.decideByReviewer",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-016. The screening id comes from a reviewer's URL; what"
                                        + " stands in for the ownership predicate is"
                                        + " @RequiresPermission(COUNTERPARTY_SCREENING_REVIEW) at the"
                                        + " boundary, asserted per route by"
                                        + " CounterpartyScreeningDatabaseTest, plus the row locked FOR"
                                        + " UPDATE first and the statement's status = 'IN_REVIEW'"
                                        + " condition.")),
                    Map.entry(
                            "com.finapp.fx.JdbcPricingPolicyStore.insertProposal",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-007. The identifier is minted by the domain (PricingPolicyId.next) inside the proposal's own transaction - never a request value. The pricing policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(FX_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " FxAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.fx.JdbcPricingPolicyStore.decide",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-007. The version is locked FOR UPDATE by lock(id) in the same transaction first, and the move is conditional on its status (V004's machine trigger beneath). The pricing policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(FX_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " FxAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.fx.JdbcPricingPolicyStore.retire",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-007. The id comes only from lockActive's row, locked in the same transaction - never a request value. The pricing policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(FX_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " FxAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.fx.JdbcPricingPolicyStore.appendEvent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-007. The id comes only from a row this transaction just inserted or locked; the history is append-only for every writer. The pricing policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(FX_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " FxAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.fx.JdbcPricingPolicyStore.pairsOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-007. A private loader: the version id comes only from a version row this store just read. The pricing policy is platform-wide"
                                        + " configuration with no owner to scope by; every door"
                                        + " is behind @RequiresPermission(FX_ADMINISTER),"
                                        + " asserted per route with nothing written by"
                                        + " FxAdministrationEndpointDatabaseTest.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRoutingStore.findVersionById",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-003. The identifier never comes from a request: the"
                                        + " explanation resolves it from the decision row's"
                                        + " policy_version_id (a NOT NULL foreign key,"
                                        + " INV-HIST-04), and the keyed creation re-reads the"
                                        + " id its own executor recorded. Versions are"
                                        + " platform-wide configuration with no owner to"
                                        + " scope by.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRoutingStore.rulesOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-003. A private loader: the version id comes only"
                                        + " from a version row this store just read - never a"
                                        + " request value; platform configuration has no owner"
                                        + " to scope by, and its surfaces are permission-walled"
                                        + " (RoutingPolicyDatabaseTest's refusals).")),
                    Map.entry(
                            "com.finapp.payments.JdbcRoutingStore.stepsOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-003; the subject-general read since P7-TSK-008."
                                        + " A private loader: the decision id comes only from"
                                        + " a decision row this store just read. The boundary"
                                        + " reasoning that lived on the"
                                        + " findLatestDecisionForIntent entry moved here when"
                                        + " that method became a one-line delegate the sweep"
                                        + " no longer sees (P7-TSK-008): the confirmation's"
                                        + " own Tx2 reads the decision its Tx1 minted; the"
                                        + " operator's explanation names SOMEBODY ELSE'S"
                                        + " payment BY DESIGN (the refund's P1-TSK-028"
                                        + " class), with"
                                        + " @RequiresPermission(PAYMENT_ROUTING_ADMINISTER)"
                                        + " standing in for the ownership predicate -"
                                        + " asserted by RoutingPolicyDatabaseTest's"
                                        + " permissionless and wrong-role refusals - and"
                                        + " every read audited"
                                        + " (PAYMENT_ROUTING_EXPLANATION_READ); the"
                                        + " withdrawal's arm reads the decision its own"
                                        + " dispatch pinned.")),
                    Map.entry(
                            "com.finapp.payments.JdbcWithdrawalStore.findForUpdate",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-008, the JdbcMerchantPayoutStore.findForUpdate"
                                        + " reasoning at the wallet: the lock every resolver"
                                        + " takes before applying the scheme's word. Its id"
                                        + " comes only from a committed dispatch this flight"
                                        + " made (Withdrawals.withdraw), a takeover's"
                                        + " dispatch-key convergence read (owner-scoped by"
                                        + " customer_id = ?), or a candidate the inquiry"
                                        + " sweep read and then locked the same way - never"
                                        + " a request's raw identifier: the surface read is"
                                        + " findOwned, whose statement carries the customer.")),
                    Map.entry(
                            "com.finapp.payments.JdbcWithdrawalStore.appendHistory",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-008, the JdbcMerchantPayoutStore.appendHistory"
                                        + " shape: a private trail writer whose id comes only"
                                        + " from the conditional transition that just fired"
                                        + " on the locked row.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.findForCounterparties",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "GET /v1/merchant/disputes/{disputeId} (P7-TSK-012) - the"
                                        + " identifier comes from the path, and"
                                        + " credit_account_id = ANY (?) in the statement is"
                                        + " the tenant check (INV-MER-01): the accounts are the"
                                        + " authenticated merchant's own payables, from the"
                                        + " ledger's owner_ref-scoped read in the same"
                                        + " transaction. Another tenant's dispute, unknown and"
                                        + " malformed are one empty answer and one 404.")),
                    Map.entry(
                            "com.finapp.fx.JdbcFxProvenanceStore.provenance",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P9-TSK-013. The investigator's read of one FX trade's provenance names"
                                        + " SOMEBODY ELSE'S conversion by design: @RequiresPermission(FX_INVESTIGATE)"
                                        + " stands in for the ownership predicate - asserted by"
                                        + " FxProvenanceDatabaseTest's wrong-role refusal - and every serving is"
                                        + " audited (fx.TradeProvenanceRead). No customer surface reaches it.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.findById",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-012. The operator's read of one dispute names"
                                        + " SOMEBODY ELSE'S contested payment by design (the"
                                        + " routing explanation's class):"
                                        + " @RequiresPermission(DISPUTE_ADMINISTER) stands in"
                                        + " for the ownership predicate - asserted by"
                                        + " DisputeNotificationDatabaseTest's wrong-role"
                                        + " refusal and DenyByDefaultDatabaseTest - and every"
                                        + " dispute shown is audited (DISPUTE_READ). No merchant"
                                        + " surface reaches it: theirs is"
                                        + " findForCounterparties. Since P8-TSK-014 the break"
                                        + " trace reads it too, under"
                                        + " RECONCILIATION_INVESTIGATE, only to learn the"
                                        + " disputed attempt whose provider statements it"
                                        + " names - the dispute id is the operation_ref of"
                                        + " reconciliation's own stored expectation, never a"
                                        + " request's, and nothing of the dispute is served.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.listForIntent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-012. The operator's read of one payment's"
                                        + " disputes, as JdbcDisputeStore.findById: the"
                                        + " DISPUTE_ADMINISTER wall stands in for the"
                                        + " predicate and every dispute shown is audited.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.historyOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-012. A dispute's trail, read only for a dispute a"
                                        + " scoped or permissioned read returned a statement"
                                        + " earlier in the same transaction - the merchant's"
                                        + " findForCounterparties (tenant predicate in the"
                                        + " statement) or the operator's findById/listForIntent"
                                        + " (the DISPUTE_ADMINISTER wall) - never a request's"
                                        + " raw identifier.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.appendHistory",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-012, the JdbcWithdrawalStore.appendHistory shape: a"
                                        + " private trail writer whose id comes only from the"
                                        + " conditional transition that just fired on the row"
                                        + " this delivery inserted or locked - the network's"
                                        + " reference attributed it behind the signed door.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.standing",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-013. The combined bound's read (behind"
                                        + " attributedStanding and standingOn): the chargebacks"
                                        + " standing on an attempt the caller holds FOR UPDATE -"
                                        + " reached from the signed door's attribution by OUR"
                                        + " operation reference, from the refund command's own"
                                        + " intent read, or from a refund row a resolver locked -"
                                        + " never a request's identifier, and no surface returns"
                                        + " what it reads: a sum for the bound, rows for the"
                                        + " re-attribution.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.anyRestorableTo",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.accounts.JdbcCustomerAccountStore.lockOwnedBy",
                                    "P7-TSK-013, JdbcPaymentIntentStore.anyInFlightCrediting's"
                                        + " twin: a close asks whether a won chargeback would"
                                        + " credit the account back, under the close's lock. The"
                                        + " account is the customer's own, locked by"
                                        + " lockOwnedBy's owner predicate. A boolean leaves, and"
                                        + " no row of anybody's.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.lockForCounterparties",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "POST /v1/merchant/disputes/{disputeId}/evidence,"
                                        + " .../representment and .../acceptance (P7-TSK-014) - the locking"
                                        + " twin of findForCounterparties: the identifier comes from the"
                                        + " path, and credit_account_id = ANY (?) in the LOCKING statement"
                                        + " is the tenant check (INV-MER-01), the accounts the authenticated"
                                        + " merchant's own payables from the ledger's owner_ref-scoped read."
                                        + " FOR UPDATE OF d, taken after the dispute's attempt. Another"
                                        + " tenant's dispute is one 404.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.lockForResponder",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014. The operator's act on a dispute (evidence,"
                                        + " representment, acceptance on behalf) names SOMEBODY ELSE'S"
                                        + " contested payment by design:"
                                        + " @RequiresPermission(DISPUTE_ADMINISTER) stands in for the"
                                        + " ownership predicate, the act is reasoned and audited, and"
                                        + " DisputeActs narrows it further to a payment whose credited"
                                        + " account the operator's policy reaches (a customer wallet - no"
                                        + " merchant), refusing the rest. No merchant surface reaches it:"
                                        + " theirs is lockForCounterparties.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeEvidenceStore.readContentForCounterparties",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "GET /v1/merchant/disputes/{disputeId}/evidence/{evidenceId}"
                                        + " (P7-TSK-014) - both identifiers from the path, and"
                                        + " credit_account_id = ANY (?) in the statement is the tenant check"
                                        + " (INV-MER-01): the document's dispute must contest a payment that"
                                        + " credited the authenticated merchant's own payable. Another"
                                        + " tenant's document, unknown and malformed are one 404; every"
                                        + " content read is audited (DISPUTE_EVIDENCE_READ).")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeEvidenceStore.readContent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014. The operator's read of a dispute document's content,"
                                        + " across tenants by permission (INV-DSP-03: the payment's merchant"
                                        + " and operators holding the dispute permission):"
                                        + " @RequiresPermission(DISPUTE_ADMINISTER) stands in for the"
                                        + " predicate, and every content read writes DISPUTE_EVIDENCE_READ"
                                        + " in its own transaction. No merchant surface reaches it: theirs"
                                        + " is readContentForCounterparties.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeEvidenceStore.findByContent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014. The upload's content address, read only for a dispute"
                                        + " DisputeActs.lockForAct locked earlier in the same transaction"
                                        + " through a scoped (lockForCounterparties) or permissioned"
                                        + " (lockForResponder) statement - never a request's raw identifier.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeEvidenceStore.countFor",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014. The document bound's count, for a dispute"
                                        + " DisputeActs.lockForAct locked earlier in the same transaction"
                                        + " through a scoped or permissioned statement - never a request's"
                                        + " raw identifier; a number leaves, no row.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeEvidenceStore.listFor",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014. A dispute's document metadata, read only for a"
                                        + " dispute a scoped or permissioned read returned earlier in the"
                                        + " same transaction - DisputeReads (findForCounterparties with its"
                                        + " tenant predicate, or the audited operator reads) or the response"
                                        + " dispatch's locked act - never a request's raw identifier; no"
                                        + " content leaves.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeEvidenceStore.contentsOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014. The documents a response transmits: the ids are the"
                                        + " response row's own frozen evidence list - a response this flight"
                                        + " dispatched under the locked act, one its claim's dispatch key"
                                        + " converged on (scoped by the responder's claim scope), or a sweep"
                                        + " candidate - and every read of them writes"
                                        + " DISPUTE_EVIDENCE_TRANSMITTED before the bytes leave.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeResponseStore.findLive",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014. Whether a live answer stands, for a dispute"
                                        + " DisputeActs.lockForAct locked earlier in the same transaction"
                                        + " through a scoped or permissioned statement - a boolean decision,"
                                        + " no row returned to any surface.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeResponseStore.listFor",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014. A dispute's answers, read only for a dispute a scoped"
                                        + " or permissioned DisputeReads read returned earlier in the same"
                                        + " transaction - never a request's raw identifier.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeResponseStore.lockForOutcome",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014, the JdbcRefundStore.lockForOutcome reasoning: the"
                                        + " lock every resolver takes before applying the PSP's word. Its id"
                                        + " comes only from this flight's committed dispatch, a takeover's"
                                        + " dispatch-key convergence read (scoped by the responder's claim"
                                        + " scope), or a candidate the sweep read - never a request's raw"
                                        + " identifier.")),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeResponseStore.renewSendPermit",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-014, JdbcRefundStore.renewSendPermit's twin: the"
                                        + " conditional forward-only permit, for a response a takeover's"
                                        + " scoped dispatch-key read or the sweep's candidate list named -"
                                        + " never a request's raw identifier; the conditional IS the permit.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.openInitiation",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-009. The initiation handle's one store, behind"
                                        + " status = AWAITING_PAYER AND handle IS NULL: its"
                                        + " id comes only from the dispatch this flight's"
                                        + " Tx1 committed (PaymentConfirmation's Dispatch"
                                        + " record) or from a sweep candidate read - never"
                                        + " a request's raw identifier, and the value it"
                                        + " writes came from the scheme's answer to OUR"
                                        + " reference, not from any caller.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.execute",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-009, the .authorize/.capture reasoning on the"
                                        + " push machine's inbound edge: a conditional"
                                        + " transition whose id comes from the SIGNED"
                                        + " callback's attribution read (by OUR minted"
                                        + " end-to-end reference - the SIGNED_CALLBACK"
                                        + " provenance) or the sweep's candidate read;"
                                        + " the losers of the row count converge.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.failHandleless",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-009. The unavailability conclusion's own"
                                        + " conditional (status AND handle IS NULL in one"
                                        + " WHERE - ADR-0062 section 3 adapted): its id is"
                                        + " Tx1's own carried fact or a sweep candidate's,"
                                        + " never a request value.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.renewInitiationPermit",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-009, the JdbcRefundStore.renewSendPermit shape:"
                                        + " the sweep's wire-noise arbiter, conditional on"
                                        + " the expected permit value read from the same"
                                        + " candidate row - no request value can reach"
                                        + " it.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRoutingStore.appendStep",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P7-TSK-003. The abandonment's append: the decision id is"
                                        + " Tx1's own carried fact (the Dispatch record),"
                                        + " minted server-side beside the attempt it governs -"
                                        + " no request value can reach it (INV-RAIL-02's"
                                        + " trail).")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantApiKeyStore.findLiveFor",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-002. THE AUTHENTICATING LOOKUP - and the one entry"
                                        + " in this register whose identifier comes from an"
                                        + " UNAUTHENTICATED caller, because it is what"
                                        + " establishes the tenant in the first place. There is"
                                        + " no ownership predicate available and there cannot"
                                        + " be: the request has no owner yet. What stands in"
                                        + " for it is the credential itself - the row is"
                                        + " selected by the PUBLIC key id and then the"
                                        + " presented secret is verified in constant time"
                                        + " against that row's hash, which the aggregate never"
                                        + " surrenders; and the query JOINS merchant.merchant"
                                        + " requiring ACTIVE, so a revoked key or a suspended"
                                        + " merchant is refused by the lookup rather than"
                                        + " after it. Every failure is one 401 with its causes"
                                        + " conflated.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantApiKeyStore.listFor",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P6-TSK-002. merchant_id = ? in the statement, from the"
                                        + " operator's path variable today and from the"
                                        + " authenticated tenant when the merchant-facing"
                                        + " surfaces arrive (INV-MER-01). No key of another"
                                        + " merchant can appear in the result, whatever the"
                                        + " caller asked for - which is why the predicate is in"
                                        + " the SQL rather than in a filter afterwards.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantApiKeyStore.findOwnedForUpdate",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P6-TSK-002. The revocation's locking read: id = ? AND"
                                        + " merchant_id = ? together, so naming another"
                                        + " merchant's key and naming one that does not exist"
                                        + " are ONE empty answer and one 404 - the surface is"
                                        + " not an oracle over other companies' credentials"
                                        + " (INV-MER-01). FOR UPDATE is the serialization"
                                        + " point, never the ownership check.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantStore.read",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-003. The one read behind findById and"
                                        + " findByIdForUpdate - the method that actually carries"
                                        + " the identifier into the statement, which is what this"
                                        + " register classifies. The identifier comes from the URL"
                                        + " of GET /v1/operator/merchants/{id} and the three"
                                        + " standing moves, and it names a COUNTERPARTY, not the"
                                        + " caller: there is no ownership predicate to check and"
                                        + " there should not be one (the P1-TSK-028 class). What"
                                        + " stands in for it: @RequiresPermission at the boundary"
                                        + " - MERCHANT_ADMINISTER - asserted with nothing written"
                                        + " by MerchantEndpointDatabaseTest's permissionless AND"
                                        + " wrong-population refusals, plus the required reason on"
                                        + " every move (INV-AUD-03). FOR UPDATE is the"
                                        + " serialization point, never an ownership check - said"
                                        + " here so a later reader cannot mistake the lock for a"
                                        + " predicate. When P6-TSK-002 adds the merchant-facing"
                                        + " surface, its reads carry merchant_id = ? from the"
                                        + " authenticated key (INV-MER-01) and are OWNER_SCOPED -"
                                        + " a different entry, not a widening of this one.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantStore.appendHistory",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-003, and the label is the guard's correction rather"
                                        + " than this task's first answer: AUTHORITATIVE_ID citing"
                                        + " read was REFUSED, because read is ADMINISTERED and"
                                        + " every operation citing an unowned provenance inherits"
                                        + " the gap (the P1-TSK-030 rule, working). The honest"
                                        + " account: the identifier's chain begins at a URL, so"
                                        + " what stands in for the missing predicate is the same"
                                        + " substitute read names - MERCHANT_ADMINISTER at the"
                                        + " boundary with the required reason. The history row for"
                                        + " a move that just landed:"
                                        + " the identifier was validated by the locking read in"
                                        + " the same transaction, and the conditional"
                                        + " WHERE status = ? row count already refused the write"
                                        + " if another writer had moved the row - so this insert"
                                        + " records a transition that provably happened."
                                        + " Append-only at the privilege; no owner predicate"
                                        + " exists because a history row belongs to the merchant"
                                        + " it names.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.appendHistory",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-011, on the JdbcMerchantStore.appendHistory reasoning"
                                        + " restated rather than re-argued: the destination"
                                        + " identifier reached this insert through the locking"
                                        + " read's id = ? AND merchant_id = ? pairing, but the"
                                        + " merchant itself was named by an operator's URL, so the"
                                        + " provenance is administered, not owned, and cannot be"
                                        + " cited as AUTHORITATIVE_ID. What stands in for the"
                                        + " missing predicate: MERCHANT_ADMINISTER or"
                                        + " PAYOUT_DESTINATION_APPROVE at the boundary with the"
                                        + " required reason, asserted with nothing written by"
                                        + " PayoutDestinationEndpointDatabaseTest's permission"
                                        + " negatives - and, for the platform's effectuation, the"
                                        + " enumerated system-actor site. The row records a"
                                        + " transition the conditional WHERE status = ? write"
                                        + " proved had happened, in the same transaction."
                                        + " Append-only at the privilege.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantPayoutStore.findByDispatchKey",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P6-TSK-012. The takeover's convergence read: the payout a"
                                        + " client key dispatched, found by merchant_id = ? AND"
                                        + " dispatch_key = ? IN THE STATEMENT, so a key another"
                                        + " merchant used converges on nothing of this merchant's."
                                        + " The merchant is the authenticated key's own, or the"
                                        + " operator's URL under MERCHANT_PAYOUT; the claim's scope"
                                        + " carries the same merchant, so the two can never"
                                        + " disagree. Negative:"
                                        + " MerchantPayoutEndpointDatabaseTest#aClientKeyIsEachMerchantsOwn"
                                        + " - two merchants, one key, two payouts.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantPayoutStore.appendHistory",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-012, the JdbcPayoutDestinationStore.appendHistory"
                                        + " reasoning restated: the payout identifier reached this"
                                        + " insert through the locking read's id = ? AND"
                                        + " merchant_id = ? pairing, in the same transaction as"
                                        + " the conditional WHERE status = ? write it records. The"
                                        + " writer is always the platform - an enumerated"
                                        + " system-actor site applying the provider's answer - so"
                                        + " the provenance is administered, not owned. Append-only"
                                        + " at the privilege.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutEvidenceStore.append",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-012. The provider's retained answer, written against"
                                        + " a payout the SAME transaction locked through"
                                        + " JdbcMerchantPayoutStore.findForUpdate's id = ? AND"
                                        + " merchant_id = ? - or a candidate the resolution sweep"
                                        + " read and then locked the same way. Never a merchant's"
                                        + " read (nothing decrypts it on any request path); the"
                                        + " writer is the platform's outcome transaction, an"
                                        + " enumerated system-actor site. Append-only for every"
                                        + " writer by V007's trigger, SELECT/INSERT at the grant.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.findOwnedBy",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P6-TSK-007. The merchant's own read, and the tenant predicate is IN THE"
                                        + " STATEMENT: id = ? AND merchant_ref = ?, so an unknown session, a"
                                        + " malformed identifier and a COMPETITOR'S are one empty answer produced"
                                        + " by the database. A different KIND of owner from party_id (P6-TSK-002's"
                                        + " distinction): a missing party_id discloses one person's data, a missing"
                                        + " merchant_ref discloses a competitor's pricing and what their customers"
                                        + " bought. THIS ENTRY IS THE GATE'S OWN FINDING - the read was first"
                                        + " written as findById(...).filter(session -> ...merchantRef equals...),"
                                        + " which is correct today and invisible to this register tomorrow, because"
                                        + " what it classifies is the method that carries an identifier into a"
                                        + " STATEMENT. P6-TSK-004's survivor is the recorded lesson: a predicate"
                                        + " that only appears to scope is the one that stops scoping quietly."
                                        + " Negative: CheckoutFlowDatabaseTest#aMerchantReadsOnlyItsOwnSession"
                                        + " establishes a real second merchant with a real session of its own.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.read",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-006. The one read behind findById and findByIdForUpdate - the method that"
                                        + " actually carries the identifier into the statement, which is what this register"
                                        + " classifies. A CHECKOUT SESSION HAS NO OWNER in this platform's sense: it is a"
                                        + " merchant's offer to a customer who may not have an account at all, and neither party"
                                        + " owns it the way a party owns a wallet. What stands in for an ownership predicate is"
                                        + " THE TOKEN - findByToken resolves a session only for the holder of its secret, and that"
                                        + " is the path every customer-facing read will take (P6-TSK-007). This id-addressed read"
                                        + " is reached only through findById and findByIdForUpdate, each classified in its own"
                                        + " entry since P6-TST-001: the confirming customer's render, handed the session its token"
                                        + " resolved, and the merchant's withdrawal, after findOwnedBy accepted the identifier."
                                        + " (Until P6-TST-001 this said 'the operator's and the completion's': the completion"
                                        + " reads by intent, through findByIntentForUpdate, and no operator route reads a"
                                        + " session.) ADMINISTERED rather than AUTHORITATIVE_ID because the helper serves both"
                                        + " chains, and an entry citing one read would misstate the other.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.appendHistory",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-006. The append-only history row, written beside the conditional transition it"
                                        + " evidences, on the identifier the caller's locking read validated in the same"
                                        + " transaction - and the conditional WHERE status = ? row count already refused the write"
                                        + " if another writer had moved the row, so this insert records a transition that provably"
                                        + " happened. Append-only at the privilege; no owner predicate exists because a history row"
                                        + " belongs to the session it names (the JdbcMerchantStore.appendHistory reasoning, and"
                                        + " ADMINISTERED for the same reason - a provenance that is itself unowned cannot be cited"
                                        + " as AUTHORITATIVE_ID).")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.readSchedule",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004. The platform's OWN pricing, addressed by its own identifier: there"
                                        + " is no owner to scope by, because a fee schedule belongs to the platform"
                                        + " rather than to any merchant. What stands in for a predicate:"
                                        + " @RequiresPermission(FEE_ADMINISTER) at the boundary, asserted with nothing"
                                        + " written by FeeScheduleDatabaseTest's permissionless AND wrong-population"
                                        + " refusals - a LEDGER_OPERATOR commands the money and still cannot set its"
                                        + " prices. Reads only; the rows cannot be changed by anyone at all (V004's"
                                        + " unconditional trigger and the withheld UPDATE grant).")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.findVersion",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004. THE RECOMPUTATION READ (INV-MER-03): given what an assessment"
                                        + " pinned, this returns exactly what priced it. Addressed by the version's own"
                                        + " identifier, which is the platform's pricing rather than anyone's data, so"
                                        + " there is no owner to scope by; FEE_ADMINISTER stands in at the boundary."
                                        + " The row is immutable, so this read cannot see a value that has changed since"
                                        + " the pin - which is the whole of the invariant's promise.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.listVersions",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004. The platform's OWN pricing, addressed by its own identifier: there"
                                        + " is no owner to scope by, because a fee schedule belongs to the platform"
                                        + " rather than to any merchant. What stands in for a predicate:"
                                        + " @RequiresPermission(FEE_ADMINISTER) at the boundary, asserted with nothing"
                                        + " written by FeeScheduleDatabaseTest's permissionless AND wrong-population"
                                        + " refusals - a LEDGER_OPERATOR commands the money and still cannot set its"
                                        + " prices. Reads only; the rows cannot be changed by anyone at all (V004's"
                                        + " unconditional trigger and the withheld UPDATE grant).")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.findEffectiveVersion",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004. The platform's OWN pricing, addressed by its own identifier: there"
                                        + " is no owner to scope by, because a fee schedule belongs to the platform"
                                        + " rather than to any merchant. What stands in for a predicate:"
                                        + " @RequiresPermission(FEE_ADMINISTER) at the boundary, asserted with nothing"
                                        + " written by FeeScheduleDatabaseTest's permissionless AND wrong-population"
                                        + " refusals - a LEDGER_OPERATOR commands the money and still cannot set its"
                                        + " prices. Reads only; the rows cannot be changed by anyone at all (V004's"
                                        + " unconditional trigger and the withheld UPDATE grant).")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.nextVersionNumber",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004. Not a resource read at all but the version-minting COUNT - an"
                                        + " optimistic one, with NO LOCK BEHIND IT and that absence is the design. A"
                                        + " schedule row cannot be locked, because PostgreSQL requires the UPDATE"
                                        + " privilege to take a row lock and V004 withholds it from a table nothing"
                                        + " may ever update: the immutability and the choice of arbiter are one fact"
                                        + " stated twice. What makes this read safe is the unique index on"
                                        + " (fee_schedule_id, version), which refuses a taken number and is also the"
                                        + " queue - a racer waits on the index and re-reads. No ownership predicate"
                                        + " because a schedule belongs to the platform; FEE_ADMINISTER stands in.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.findAssignment",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004, and the honest account of a predicate that is the right SHAPE but"
                                        + " not yet an ownership check. The statement carries merchant_id = ? - what the"
                                        + " platform charges one counterparty is commercially sensitive to every other,"
                                        + " so the predicate belongs in the SQL for exactly INV-MER-01's reason. What it"
                                        + " is NOT today is an OWNERSHIP check: the identifier comes from the URL of an"
                                        + " operator route and names a COUNTERPARTY, not the caller, so labelling it"
                                        + " OWNER_SCOPED would claim a control nobody exercises (the P1-TSK-030 rule)."
                                        + " The substitute is FEE_ADMINISTER at the boundary with the required reason"
                                        + " (INV-AUD-03). When P6-TSK-007 resolves pricing for a merchant it"
                                        + " AUTHENTICATED, that caller's read is OWNER_SCOPED - and that is a"
                                        + " reclassification made with its own negative test, not a widening assumed here.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.findEffectiveVersionFor",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004, and the honest account of a predicate that is the right SHAPE but"
                                        + " not yet an ownership check. The statement carries merchant_id = ? - what the"
                                        + " platform charges one counterparty is commercially sensitive to every other,"
                                        + " so the predicate belongs in the SQL for exactly INV-MER-01's reason. What it"
                                        + " is NOT today is an OWNERSHIP check: the identifier comes from the URL of an"
                                        + " operator route and names a COUNTERPARTY, not the caller, so labelling it"
                                        + " OWNER_SCOPED would claim a control nobody exercises (the P1-TSK-030 rule)."
                                        + " The substitute is FEE_ADMINISTER at the boundary with the required reason"
                                        + " (INV-AUD-03). When P6-TSK-007 resolves pricing for a merchant it"
                                        + " AUTHENTICATED, that caller's read is OWNER_SCOPED - and that is a"
                                        + " reclassification made with its own negative test, not a widening assumed here.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.assign",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004. The pointer write, under the MERCHANT row's FOR UPDATE lock taken"
                                        + " by the command in the same transaction - which is what lets the first"
                                        + " assignment and a later move share one path without the primary key having to"
                                        + " refuse nine concurrent first-assignments. merchant_id = ? is in the statement"
                                        + " for the tenant reason above; the identifier's provenance is an operator's URL,"
                                        + " so FEE_ADMINISTER and the required reason are the substitute. No DELETE"
                                        + " exists: unassigning is not modelled.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.appendAssignmentHistory",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004. The append-only history row, written beside the pointer move it"
                                        + " evidences, on the identifier the locking read validated in the same"
                                        + " transaction. Append-only at the privilege; no owner predicate exists because"
                                        + " a history row belongs to the merchant it names (the JdbcMerchantStore"
                                        + " .appendHistory reasoning, and ADMINISTERED for the same reason - a provenance"
                                        + " that is itself unowned cannot be cited as AUTHORITATIVE_ID).")),
                    // ------------------------------------------------------ P6-TST-001
                    // What the widened detector sees in the tenant's packages: statements whose
                    // identifier is a bare UUID, or reaches the statement through a same-class
                    // helper. Every one was invisible to this register until then.
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.findByIntentOwnedBy",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P6-TSK-009, classified by P6-TST-001. The transaction"
                                        + " report's checkout read: payment_intent_ref = ? AND"
                                        + " merchant_ref = ? in the statement, the tenant the"
                                        + " authenticated key's own. Unreachable over HTTP - the"
                                        + " report follows only references on the merchant's own"
                                        + " payable - so its negative drives the store directly:"
                                        + " CheckoutFlowDatabaseTest"
                                        + "#theEnrichmentReadIsTenantScopedInItsOwnStatement.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.findByIntentForUpdate",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-007, classified by P6-TST-001. The completion's lock,"
                                        + " payment_intent_ref = ? and no tenant predicate. The"
                                        + " intent comes from the capture the platform is applying"
                                        + " - CheckoutSessions.completed, called by the capture"
                                        + " composition inside the outcome transaction, an"
                                        + " enumerated system-actor site - never from a request."
                                        + " What stands in for the predicate: that site, and the"
                                        + " capture's conditional transition, won before the"
                                        + " composition runs; V002's trigger sets the reference"
                                        + " once.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.opened",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                                    "The Phase 7 -> 8 transition. The public payment door's"
                                        + " refusal of a checkout's intent: payment_intent_ref = ?"
                                        + " and no tenant predicate, asked by"
                                        + " PaymentService.confirm only AFTER findOwned resolved"
                                        + " the intent as the caller's own in the same"
                                        + " transaction - so a stranger's intent id meets the"
                                        + " command's one 404, never a refusal that says the"
                                        + " intent exists. It answers a boolean and returns no"
                                        + " row.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.findById",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.checkout.JdbcCheckoutSessionStore.findByToken",
                                    "P6-TSK-006, classified by P6-TST-001. The public face of"
                                        + " read, and its one production caller is the confirming"
                                        + " customer's render (CheckoutSessions.ownedBySession),"
                                        + " handed the session the same request resolved by its"
                                        + " token. The token lookup is the proof; this read"
                                        + " repeats nothing.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.findByIdForUpdate",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.checkout.JdbcCheckoutSessionStore.findOwnedBy",
                                    "P6-TSK-008, classified by P6-TST-001. The withdrawal's lock,"
                                        + " taken on the identifier the merchant's own findOwnedBy"
                                        + " - id = ? AND merchant_ref = ? - accepted in the same"
                                        + " transaction (CheckoutSessions.abandon). V002's trigger"
                                        + " freezes merchant_ref, so the ownership that read"
                                        + " established still holds under the lock.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcOrderStore.findBySession",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.checkout.JdbcCheckoutSessionStore.findOwnedBy",
                                    "P6-TSK-007, classified by P6-TST-001. The order a session"
                                        + " produced, read by the session identifier an owner-"
                                        + " scoped read returned: the merchant's findOwnedBy when"
                                        + " a session is rendered, findByIntentOwnedBy in the"
                                        + " transaction report, and the confirming customer's"
                                        + " token-resolved session. No request names an order.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcOrderStore.read",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.checkout.JdbcCheckoutSessionStore.findOwnedBy",
                                    "P6-TST-001. The helper behind findBySession and findById,"
                                        + " which complete its WHERE with their own predicate; the"
                                        + " identifier it carries is findBySession's, whose entry"
                                        + " is the reasoning. findById has no production caller.")),
                    Map.entry(
                            "com.finapp.checkout.JdbcOrderStore.findById",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-006, classified by P6-TST-001. An order by its own"
                                        + " identifier, and no production path calls it: no"
                                        + " request names an order, and an operator's order"
                                        + " surface is later work. Classified now so that surface"
                                        + " meets an entry saying what it must bring - a"
                                        + " permission at the boundary, or merchant_ref = ? in"
                                        + " the statement.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcFeeScheduleStore.findSchedule",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-004, classified by P6-TST-001. The public face of"
                                        + " readSchedule, which carries the identifier into the"
                                        + " statement: the platform's own pricing by its own"
                                        + " identifier, reached only from operator routes under"
                                        + " FEE_ADMINISTER. readSchedule's entry is the"
                                        + " reasoning.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantPayoutStore.find",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P6-TSK-012, classified by P6-TST-001. The read behind"
                                        + " GET /v1/merchant/payouts/{payoutId}: id = ? AND"
                                        + " merchant_id = ? IN THE STATEMENT, the merchant the"
                                        + " authenticated key's own, so another merchant's payout,"
                                        + " an unknown one and a malformed one are one 404."
                                        + " Invisible until P6-TST-001: the statement is handed"
                                        + " to a generic helper, so neither half carried both the"
                                        + " identifier and prepareStatement. Negative:"
                                        + " MerchantPayoutEndpointDatabaseTest#oneNotFound.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantPayoutStore.findForUpdate",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-012, classified by P6-TST-001. The lock on a payout"
                                        + " being judged: the dispatch's own payout, minted in the"
                                        + " command under its merchant (MerchantPayouts), or a"
                                        + " resolution candidate's (merchant, id) pair the sweep"
                                        + " read itself (MerchantPayoutResolution) - never an"
                                        + " identifier from a request. id = ? AND merchant_id = ?"
                                        + " is in the statement as the pairing's second rank; what"
                                        + " stands in for an ownership check is that the platform"
                                        + " holds both halves.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantPayoutStore.pageCompleted",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P8-TSK-007. The opening-position backfill's walk over"
                                        + " every merchant's completed payouts, deliberately"
                                        + " cross-tenant: the reconciliation controller adopts"
                                        + " history under RECONCILIATION_ADMINISTER"
                                        + " (RoutePermissionRegisterTest pins the route; the"
                                        + " cross-desk negatives are"
                                        + " ReconciliationOpeningDatabaseTest's). The UUID is a"
                                        + " paging cursor, never a target - rows are selected by"
                                        + " status alone, id > ? only orders the walk, and the"
                                        + " cursor's value is the last id of this same"
                                        + " statement's previous page. Nothing it returns is"
                                        + " served to a tenant: the walk opens expectations"
                                        + " through the recorder and answers counts.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutReturnStore.page",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P8-TSK-019. The opening-position backfill's walk over"
                                        + " every recorded payout return, deliberately"
                                        + " cross-tenant, under RECONCILIATION_ADMINISTER - the"
                                        + " JdbcMerchantPayoutStore.pageCompleted reasoning. The"
                                        + " UUID is a paging cursor, never a target: rows are"
                                        + " selected by id > ? only to order the walk, the"
                                        + " cursor the last id of this statement's previous"
                                        + " page, and nothing is served to a tenant.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutReturnStore.findByPayout",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P8-TSK-019. The payout return worker's converge check:"
                                        + " the identifier is the payout PayoutReturns.apply has"
                                        + " just locked FOR UPDATE, found by the provider's or"
                                        + " our reference off accepted settlement evidence -"
                                        + " never an identifier from a request. No route serves"
                                        + " it; the worker acts as the platform"
                                        + " (SystemActorCallSitesAreEnumeratedTest) and answers"
                                        + " a typed outcome, identifiers only.")),
                    // P8-TSK-010's reference finders (findByCaptureProviderReference,
                    // the dispute's and payout's findByProviderReference, the claim's
                    // findByExecution) carry no entry HERE deliberately: the sweep
                    // classifies methods that target a resource by its own identifier,
                    // and those finders take a counterparty's stored REFERENCE - the
                    // typing lookup's read, whose posture their javadocs state.
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantStore.findById",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-003, classified by P6-TST-001. The public face of"
                                        + " read, and read's reasoning holds: an operator names"
                                        + " the merchant in the URL under MERCHANT_ADMINISTER. On"
                                        + " the merchant's own surface the identifier IS the"
                                        + " authenticated tenant (a checkout opened,"
                                        + " GET /v1/merchant/me): the key names the row, so there"
                                        + " is no other merchant it could reach.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantStore.findByIdForUpdate",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-003, classified by P6-TST-001. The locking face of"
                                        + " read: the standing moves, the fee assignment and the"
                                        + " payout dispatch lock the merchant row an operator's URL"
                                        + " named or the authenticated key is. read's substitute,"
                                        + " and FOR UPDATE is the serialisation point, never an"
                                        + " ownership check.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPaymentFeePinStore.findFor",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-005, classified by P6-TST-001. A payment's pinned"
                                        + " price, by its intent - a bare UUID, so invisible until"
                                        + " P6-TST-001. Reached from the capture's and the"
                                        + " refund's compositions inside the outcome transaction,"
                                        + " and from the pin's own convergence check at"
                                        + " confirmation; the one request that names an intent is"
                                        + " the operator's refund, under PAYMENT_REFUND at the"
                                        + " boundary. A pin names the merchant it prices, and the"
                                        + " composition refuses a credit to any payable but that"
                                        + " merchant's.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.find",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.merchant.JdbcMerchantPayoutStore.find",
                                    "P6-TSK-011, classified by P6-TST-001. The destination a"
                                        + " payout names, read with the payout's own (merchant,"
                                        + " destination) pair after the payout was read owner-"
                                        + " scoped - JdbcMerchantPayoutStore.find, or"
                                        + " findByDispatchKey on a replay - and a proposal read"
                                        + " back in the transaction that minted it. No request"
                                        + " names a destination here; id = ? AND merchant_id = ?"
                                        + " is in the statement regardless.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.findForUpdate",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P6-TSK-011, classified by P6-TST-001. The decisions' lock -"
                                        + " approval, rejection, withdrawal - on an operator's"
                                        + " PAIRING, /merchants/{merchantId}/payout-destinations"
                                        + "/{destinationId}: id = ? AND merchant_id = ? in the"
                                        + " statement, so a destination named under another"
                                        + " merchant's path is the unknown one's 404 and nothing"
                                        + " moves. The effectuation sweep takes the same lock on a"
                                        + " candidate it read itself. Negative:"
                                        + " PayoutDestinationEndpointDatabaseTest#oneNotFound.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.findEffectiveForShare",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "P6-TSK-012, classified by P6-TST-001. The dispatch's share"
                                        + " lock on the destination a payout will pay: merchant_id"
                                        + " = ? AND status = 'EFFECTIVE' in the statement, the"
                                        + " merchant the authenticated key's own or the one an"
                                        + " operator's MERCHANT_PAYOUT route names. Without it a"
                                        + " merchant with no destination of its own would pay out"
                                        + " to another merchant's account. Negative:"
                                        + " MerchantTenancyBatteryDatabaseTest"
                                        + "#aPayoutPaysOnlyItsOwnMerchantsDestination.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.findEffective",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-011, classified by P6-TST-001. The operator's list,"
                                        + " GET /v1/operator/merchants/{merchantId}/payout-"
                                        + "destinations under MERCHANT_ADMINISTER: what the named"
                                        + " merchant's payouts go to. merchant_id = ? in the"
                                        + " statement confines the answer to that merchant, which"
                                        + " is a subject an operator named rather than a caller's"
                                        + " own - the P6-TSK-004 rule, with the permission the"
                                        + " substitute.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.findOpen",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-011, classified by P6-TST-001. The same list's open"
                                        + " change, proposed or cooling off, on findEffective's"
                                        + " reasoning: merchant_id = ? in the statement, the"
                                        + " merchant an operator named under"
                                        + " MERCHANT_ADMINISTER.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.findEffectiveForUpdate",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P6-TSK-011, classified by P6-TST-001. The effectuation's"
                                        + " lock on the destination it supersedes, for the"
                                        + " merchant of the approved candidate the sweep read and"
                                        + " locked itself - an enumerated system-actor site,"
                                        + " never an identifier from a request.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentIntentStore.transition",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                                    "The conditional status write (P5-TSK-009): the identifier"
                                        + " was validated by findOwned in the same command, and"
                                        + " the WHERE status = ? row count is the concurrency"
                                        + " arbiter, with V002's trigger beneath.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentIntentStore.recordTransition",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                                    "The append-only history row, written beside the"
                                        + " conditional transition it evidences, on the same"
                                        + " validated identifier.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.findForIntent",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                                    "The converged confirm's answer and the outcome flow's"
                                        + " read: the intent identifier passed findOwned in"
                                        + " the same command; the attempt is the intent's own"
                                        + " row.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.findById",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                                    "The capture command's read (P5-TSK-010): attempt"
                                        + " identifiers come from the platform's own flows -"
                                        + " the confirm that minted them behind the intent's"
                                        + " owner-scoped read, or a resolver's sweep - never"
                                        + " a request's. No HTTP path takes an attempt id.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.dispatchCapture",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentCapture.capture",
                                    "The capture dispatch's conditional write (P5-TSK-010):"
                                        + " AUTHORIZED -> CAPTURE_DISPATCHED with the minted"
                                        + " reference, the row count arbitrating racing"
                                        + " dispatchers - on the identifier the command just"
                                        + " read and judged.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.capture",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentCapture.capture",
                                    "The capture outcome's conditional write, atomic with the"
                                        + " posting on the same connection (ADR-0048) - the"
                                        + " same minted identifier, carried across the"
                                        + " provider call.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.markCaptureUnknown",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentCapture.capture",
                                    "As JdbcPaymentAttemptStore.capture - the honest-ambiguity"
                                        + " edge, nothing posted (INV-LIFE-03).")),
                    Map.entry(
                            "com.finapp.payments.JdbcClearingRecordStore.findForAttempt",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentClearing.record",
                                    "The refused clearing insert's read-back (P7-TSK-005),"
                                        + " on the identifier the same delivery attributed"
                                        + " through findByOperationReference over OUR minted"
                                        + " capture reference (INV-PAY-04 inbound, behind"
                                        + " the authenticated webhook door) - it tells the"
                                        + " rail's harmless repetition apart from a foreign"
                                        + " acquirer-reference claim. No HTTP path takes an"
                                        + " attempt id.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.dispatchVoid",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentVoid.voidAuthorized",
                                    "The void dispatch's conditional write (P7-TSK-004):"
                                        + " AUTHORIZED -> VOID_DISPATCHED with the minted"
                                        + " reference, the row count arbitrating the race"
                                        + " against the capture chain - on the identifier the"
                                        + " command just read behind the intent's owner-scoped"
                                        + " read (the customer door) or the operator's"
                                        + " PAYMENT_REFUND wall. The redirect's writer is the"
                                        + " outcome transaction, same discipline.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.voided",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentVoid.completeDispatched",
                                    "The void outcome's conditional write (P7-TSK-004): the"
                                        + " same minted identifier, carried across the"
                                        + " provider call - the release acknowledged, nothing"
                                        + " posted.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.markVoidUnknown",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentVoid.completeDispatched",
                                    "As JdbcPaymentAttemptStore.voided - the honest-ambiguity"
                                        + " edge, nothing concluded (INV-LIFE-03).")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.authorize",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentConfirmation.confirm",
                                    "The outcome's conditional write (P5-TSK-009): the attempt"
                                        + " identifier was minted by this command's own Tx1 and"
                                        + " carried across the provider call - never a"
                                        + " request's - and the WHERE status = ? row count"
                                        + " makes racing resolvers converge.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.fail",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentConfirmation.confirm",
                                    "As JdbcPaymentAttemptStore.authorize - the same minted"
                                        + " identifier, the failing edge.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.markAuthUnknown",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentConfirmation.confirm",
                                    "As JdbcPaymentAttemptStore.authorize - the same minted"
                                        + " identifier, the honest-ambiguity edge"
                                        + " (INV-LIFE-03).")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.recordTransition",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentConfirmation.confirm",
                                    "The append-only history row beside the attempt's own"
                                        + " conditional transition, on the same minted"
                                        + " identifier.")),
                    Map.entry(
                            "com.finapp.payments.JdbcProviderEvidenceStore.payloadsFor",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                                    "The retained evidence of one attempt, decrypted and"
                                        + " checksum-verified: attempt identifiers trace to"
                                        + " the intent's owner-scoped read (findForIntent is"
                                        + " keyed by the intent findOwned validated) - never"
                                        + " a request's. Today's callers are the database"
                                        + " suite and the coming reconciliation surface.")),
                    Map.entry(
                            "com.finapp.payments.JdbcProviderEvidenceStore.evidenceMetadataFor",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P8-TSK-014. A break trace's walk to the provider"
                                        + " statements about an operation - METADATA only"
                                        + " (id, kind, capture time; no ciphertext column is"
                                        + " selected) - deliberately cross-tenant: an"
                                        + " investigator follows a break to whoever's attempt,"
                                        + " refund or withdrawal it names, under"
                                        + " @RequiresPermission(RECONCILIATION_INVESTIGATE)"
                                        + " (RoutePermissionRegisterTest pins every desk route;"
                                        + " the wrong-role negatives are"
                                        + " ReconciliationInvestigationDatabaseTest's). The"
                                        + " identifier is never a request's: it is the"
                                        + " operation_ref reconciliation's own expectation row"
                                        + " stored when the operation completed, reached by"
                                        + " the break's stored subject chain. The payload stays"
                                        + " behind payloadsFor's decrypt-and-verify read.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentAttemptStore.lockById",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "The refund dispatch's serialisation point (P5-TSK-015):"
                                        + " FOR UPDATE on the attempt row, taken FIRST in the"
                                        + " pinned attempt-then-account lock order. The"
                                        + " identifier is the command's own findForIntent"
                                        + " result behind the ADMINISTERED intent read - never"
                                        + " a request's; no HTTP path takes an attempt id.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.findById",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "The replay re-read (P5-TSK-015): the refund identifier"
                                        + " comes from the idempotency claim's own recorded"
                                        + " body, minted by the platform on the creating call -"
                                        + " never a request's. No HTTP path takes a refund"
                                        + " id.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.listFor",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "The refunds of one attempt: the attempt identifier is the"
                                        + " command's own findForIntent result (as lockById),"
                                        + " never a request's. Today's callers are the"
                                        + " database suite; the P5-TSK-016 view will walk the"
                                        + " same platform-held chain.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.sumCompletedFor",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "P6-TSK-014. The sibling above, on the same platform-held"
                                        + " attempt identifier and under the same lock - and"
                                        + " COMPLETED rather than non-failed, which is a"
                                        + " different question rather than a stricter version"
                                        + " of the same one: the bound asks what may still be"
                                        + " returned, this asks what HAS been, because a fee"
                                        + " returned against a refund still in flight would be"
                                        + " returned against money that may never leave.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.sumNonFailedFor",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "The bound's lock-then-look read (P5-TSK-015): summed"
                                        + " under the attempt row lock the command just took,"
                                        + " on the same platform-held attempt identifier.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.complete",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "The refund outcome's conditional write, atomic with the"
                                        + " hold release and the posting (ADR-0048 section 4) -"
                                        + " the refund identifier was minted by this command's"
                                        + " own Tx1 and carried across the provider call, and"
                                        + " the WHERE status = ? row count makes racing"
                                        + " resolvers converge.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.fail",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "As JdbcRefundStore.complete - the failing edge, the hold"
                                        + " released, nothing posted.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.markUnknown",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "As JdbcRefundStore.complete - the honest-ambiguity edge:"
                                        + " the hold STANDS, nothing posted (INV-LIFE-03).")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.recordTransition",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "The append-only history row beside the refund's own"
                                        + " conditional transition, on the same minted"
                                        + " identifier.")),
                    Map.entry(
                            "com.finapp.transfers.JdbcBeneficiaryStore.remove",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "DELETE /v1/beneficiaries/{id} (P4-TSK-007's surface; the"
                                        + " store landed with P4-TSK-006) - the identifier"
                                        + " comes from the path, and party_id = ? in the"
                                        + " statement is the ownership check: a beneficiary"
                                        + " belongs to the Party, and the conditional's row"
                                        + " count folds not-yours and already-removed into one"
                                        + " indistinguishable false.")),
                    Map.entry(
                            "com.finapp.transfers.JdbcBeneficiaryStore.findOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "DELETE /v1/beneficiaries/{id}'s second half (P4-TSK-007):"
                                        + " after the conditional removal matched nothing, this"
                                        + " any-status read - party_id = ? in the statement -"
                                        + " is what tells the caller's own already-removed row"
                                        + " (converge, 204) from unknown and not-yours (one"
                                        + " 404). Without the predicate a stranger's DELETE of"
                                        + " a live row would answer 204 and read as theirs.")),
                    Map.entry(
                            "com.finapp.transfers.JdbcTransferStore.findOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "GET /v1/transfers/{id} (P4-TSK-008) - the identifier comes"
                                        + " from the path, and customer_id = ? in the statement"
                                        + " is the ownership check (V002 named the column for"
                                        + " this read on the day it was created). The customer"
                                        + " itself is session-derived (findLiveCustomerFor), so"
                                        + " not-yours, unknown and malformed are one empty"
                                        + " answer and one 404.")),
                    Map.entry(
                            "com.finapp.transfers.JdbcTransferStore.findById",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.transfers.TransferExecution.execute",
                                    "Renders the POST response: the identifier is the execution"
                                        + " command's own result inside the same transaction -"
                                        + " minted by the claim this call just made or"
                                        + " replayed, whose fingerprint binds the actor and"
                                        + " party - never a request's. The HTTP reads go"
                                        + " through findOwned.")),
                    Map.entry(
                            "com.finapp.transfers.JdbcTransferStore.lockById",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P4-TSK-009. The reversal's FOR UPDATE serialisation point:"
                                        + " the identifier comes from the URL of"
                                        + " POST /v1/transfers/{id}/reversal and names SOMEBODY"
                                        + " ELSE'S transfer - that is the operation, not a"
                                        + " defect (the P1-TSK-028 class). What stands in for"
                                        + " the missing ownership predicate:"
                                        + " @RequiresPermission(TRANSFER_REVERSE) at the"
                                        + " boundary, asserted with nothing written by"
                                        + " TransferReversalDatabaseTest's permissionless"
                                        + " refusal. The lock is the reversal's concurrency"
                                        + " arbiter, not an ownership mechanism.")),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.revokeAll",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "Bulk revocation. The SessionId it takes is the session to"
                                        + " SPARE, never the target; every row it touches is"
                                        + " selected by identity_id. The detector found this"
                                        + " PRIVATE helper rather than the two public methods that"
                                        + " delegate to it, which is more accurate than the register"
                                        + " I first wrote: the statement is here.")),
                    Map.entry(
                            "com.finapp.party.JdbcPartyStore.findById",
                            new Entry(
                                    Scope.SESSION_DERIVED,
                                    "com.finapp.app.profile.MeController",
                                    "P1-TSK-030, and party's FIRST ownership surface - the"
                                        + " assertion that it had none was written to fail exactly"
                                        + " here. AUTHORITATIVE_ID was tried first and this rule"
                                        + " REFUSED it: the read in the chain is"
                                        + " JdbcIdentityStore.findById, which P1-TSK-028 classified"
                                        + " ADMINISTERED because an administrator names its subject"
                                        + " from a URL - so citing it as owner-constrained would"
                                        + " have been false. The ownership is the proven Session"
                                        + " itself: Session.identityId() -> Identity.partyId().")),
                    Map.entry(
                            "com.finapp.party.JdbcPartyStore.rename",
                            new Entry(
                                    Scope.SESSION_DERIVED,
                                    "com.finapp.app.profile.MeController",
                                    "P1-TSK-030, and the same provenance as findById - the write"
                                        + " reaches the row the read resolved, in the same"
                                        + " transaction. The statement's other predicate,"
                                        + " display_name <> ?, is not an ownership check at all: it"
                                        + " makes a no-op rename write no audit record.")),
                    Map.entry(
                            "com.finapp.identity.JdbcIdentityStore.findById",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P1-TSK-028. The identifier comes from the URL of"
                                        + " POST /v1/identities/{id}/suspension and names SOMEBODY"
                                        + " ELSE - that is the operation, not a defect. What stands"
                                        + " in for the missing ownership predicate:"
                                        + " @RequiresPermission(IDENTITY_SUSPEND) at the boundary,"
                                        + " and IdentityAdministration refusing subject.equals(actor)"
                                        + " in the domain. Both are asserted by"
                                        + " IdentityAdministrationDatabaseTest.")),
                    Map.entry(
                            "com.finapp.identity.JdbcIdentityStore.moveStatus",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P1-TSK-028, and the same argument as findById. The statement"
                                        + " additionally carries AND status = ?, which is not an"
                                        + " ownership predicate at all - it is the concurrency"
                                        + " protocol that makes two administrators acting at once"
                                        + " produce one transition and one audit record.")),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.lockIdentity",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "The FOR UPDATE lock revokeAll takes on the identity row, found"
                                        + " by the detector rather than by me - the P1-TSK-021"
                                        + " finding that a rule stopping at the public method is one"
                                        + " ordinary extraction dodges. It became visible to this"
                                        + " rule at P1-TSK-028, which narrowed the IdentityId"
                                        + " exclusion. Classified ADMINISTERED as the WEAKER of its"
                                        + " two provenances: reached from a proven session for a"
                                        + " customer's own revocation, and from a URL for a"
                                        + " suspension. A label must be one thing, and naming the"
                                        + " safer path would describe the caller that needs no"
                                        + " protection.")),
                    Map.entry(
                            "com.finapp.identity.JdbcRecoveryRequestStore.consume",
                            new Entry(
                                    Scope.BEARER_SCOPED,
                                    "Recovery completion. The request identifier comes from the URL"
                                        + " and the caller holds no session - that is what recovery"
                                        + " is for - so the token is the whole authorisation, and"
                                        + " the statement carries token_hash = ?. It also carries"
                                        + " status, expiry AND the credential the request was bound"
                                        + " to, so a token that survived a password change is"
                                        + " already dead.")),
                    Map.entry(
                            "com.finapp.identity.JdbcContactChannelStore.findOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "A contact channel read by identifier. The owner is the proven"
                                        + " session's identity, in the statement - a channel is the"
                                        + " thing an attacker most wants to point at their own"
                                        + " mailbox, so reading somebody else's must be impossible"
                                        + " rather than merely unusual.")),
                    Map.entry(
                            "com.finapp.platform.outbox.OutboxRelay.markPublished",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "An outbox row belongs to a flow, not to a party. Nobody can"
                                        + " own it, so there is no ownership question - and the"
                                        + " entry exists because the rule sweeps every module rather"
                                        + " than a list of the ones that matter today. (This entry"
                                        + " predicted that Phase 3's ledger identifiers would"
                                        + " surface here on the day they were declared, and"
                                        + " P3-TSK-005 is that day - the prediction held.)")),
                    Map.entry(
                            "com.finapp.ledger.JdbcJournalEntryStore.findById",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-005. A journal entry is the platform's own accounting"
                                        + " record: its lines may touch many parties' accounts and"
                                        + " the entry belongs to none of them, so there is no"
                                        + " single owner to scope by and inventing one would type"
                                        + " a sentence that is false. Disclosure control is the"
                                        + " SURFACE's. P3-TSK-016's reversal arrived and said"
                                        + " so: an IN-PROCESS caller (ReversalService reads the"
                                        + " original a commanding flow names - no HTTP surface"
                                        + " exists), and P3-TSK-018's statements arrived through"
                                        + " their own reader (JdbcStatementDerivation - scoped"
                                        + " one level up by the caller's own product, see its"
                                        + " entry). The URL-named arrival this entry predicted"
                                        + " landed on the PROPOSAL instead (P3-TSK-021: the"
                                        + " four-eyes surface names proposals, and the entry id"
                                        + " only ever comes back out), so this method still has"
                                        + " no URL-named caller.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcBalanceDerivation.derive",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-008. The derivation is the ledger's own definition"
                                        + " of a settled balance, computed over the platform's"
                                        + " accounting rows - a ledger account may be the"
                                        + " platform's (operational, no owner exists) or a"
                                        + " customer's, so ownership is a property of the"
                                        + " SURFACE that discloses the number, not of the"
                                        + " computation. Platform callers run as the platform"
                                        + " (the projection check, the hold decision, the"
                                        + " close's zero-check); the one customer surface is"
                                        + " P3-TSK-018's statement, which arrived and said so:"
                                        + " it reaches here only through the caller's own"
                                        + " product (findOwnedBy, ownership in the statement)."
                                        + " (The entry named P3-TSK-018 as 'the balance"
                                        + " endpoint' - that surface was P3-TSK-013's display,"
                                        + " the recorded one-task plan drift, corrected here.)")),
                    Map.entry(
                            "com.finapp.ledger.JdbcPositionBreakdown.breakdown",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P6-TSK-010. The derivation read a second way - the same"
                                        + " fold over the same lines, each carrying how its entry"
                                        + " treated a named counterparty purpose - and classified"
                                        + " as derive is, for derive's reason: a ledger account"
                                        + " may be the platform's or a party's, so ownership is"
                                        + " a property of the SURFACE that discloses the figure,"
                                        + " not of the computation. The one surface is the"
                                        + " merchant payable view, which reaches here only with"
                                        + " accounts from findAllOwned (owner_ref = ? in the"
                                        + " ledger's statement) under the merchant's own key.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcBalanceDerivation.linesInRange",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-008. The private helper holding derive's line"
                                        + " statements - the detector locates the helper where"
                                        + " the statement actually is (the P1-TSK-021"
                                        + " revokeAll finding), and its classification is"
                                        + " derive's own, one entry up.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcStatementDerivation.periodLines",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-018. The statement's period-line read - the"
                                        + " derivation's reasoning verbatim: the rows are the"
                                        + " platform's accounting record and ownership is the"
                                        + " disclosing SURFACE's. The one production caller is"
                                        + " the /v1/me statement endpoint, whose account id is"
                                        + " resolved through CustomerAccountStore.findOwnedBy"
                                        + " (customer_id = ? in the statement) before this"
                                        + " reader is ever asked - the AUTHORITATIVE_ID shape,"
                                        + " labelled NOT_OWNED honestly because the ledger row"
                                        + " itself has no owner column to scope by and the"
                                        + " provenance lives in another module's store.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcBalanceProjection.upsert",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-009. The projection updater, reached only from"
                                        + " PostingService's already-authorised effect: the"
                                        + " account ids come from the validated entry's own"
                                        + " lines, never from a request, and the row it moves"
                                        + " is the ledger's derived bookkeeping of the posting"
                                        + " it rides (INV-BAL-01). No read exists to scope -"
                                        + " BalanceProjectionTest pins the port to one void"
                                        + " method (INV-BAL-05).")),
                    Map.entry(
                            "com.finapp.ledger.ProjectionVerification.seqOf",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-010. The verification job's read of the"
                                        + " projection watermark - platform bookkeeping over"
                                        + " the ledger's own rows, account ids enumerated"
                                        + " from the tables themselves, never a request. The"
                                        + " verdict it feeds carries no balance out"
                                        + " (BalanceProjectionTest pins it), so there is"
                                        + " nothing here a caller could disclose.")),
                    Map.entry(
                            "com.finapp.ledger.ProjectionVerification.entriesOn",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-010. The applied-entry count over journal lines"
                                        + " - seqOf's sibling, same classification, same"
                                        + " reasoning, one entry up.")),
                    Map.entry(
                            "com.finapp.ledger.ProjectionVerification.rowOf",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-010. The private helper reading the projection"
                                        + " row for the comparison - the detector locates"
                                        + " the helper where the statement actually is (the"
                                        + " P1-TSK-021 revokeAll finding); its"
                                        + " classification is seqOf's, two entries up.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcBalanceProjection.normalBalanceOf",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-009. The private helper reading the account's"
                                        + " frozen classification for upsert's sign"
                                        + " convention - the detector locates the helper"
                                        + " where the statement actually is (the P1-TSK-021"
                                        + " revokeAll finding), and its classification is"
                                        + " upsert's own, one entry up.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcAdjustmentProposalStore.read",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P3-TSK-021. The one statement behind findById and its"
                                        + " FOR UPDATE twin lockById (the approval's"
                                        + " lock-then-look serialisation point, P2-TSK-015) -"
                                        + " the detector locates the helper where the"
                                        + " statement actually is (the P1-TSK-021 revokeAll"
                                        + " finding). The URL-named administrative arrival"
                                        + " the journal findById entry predicted, landed on"
                                        + " the proposal: the identifier comes from"
                                        + " /v1/ledger/adjustments/{id}, and the checks that"
                                        + " stand in for an ownership predicate are"
                                        + " @RequiresPermission(LEDGER_ADJUST) at the"
                                        + " boundary (AdjustmentController, refusal audited)"
                                        + " and the four-eyes rules at the domain - a"
                                        + " proposal has no single owner to scope by,"
                                        + " deliberately: the initiator must NOT be able to"
                                        + " fence the approver out, because a second person"
                                        + " reading it is the control (INV-AUD-04).")),
                    Map.entry(
                            "com.finapp.ledger.JdbcAdjustmentProposalStore.linesOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P3-TSK-021. The proposal's line read, keyed by the"
                                        + " proposal the caller already resolved - reached"
                                        + " only through read, whose classification it"
                                        + " shares.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcAdjustmentProposalStore.decide",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P3-TSK-021. The conditional decision (status ="
                                        + " 'PROPOSED' in the statement, the row count the"
                                        + " outcome), always under lockById's FOR UPDATE."
                                        + " The checks that stand in: the boundary"
                                        + " permission, the aggregate's self-approval and"
                                        + " terminal refusals judged on the LOCKED row, and"
                                        + " V010's approver <> initiator CHECK plus the"
                                        + " freeze trigger beneath - the named negatives are"
                                        + " AdjustmentEndpointDatabaseTest#"
                                        + "selfApprovalIsRefused and"
                                        + " #aSessionWithoutTheRoleIsRefused.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcJournalEntryStore.reversalLinesOf",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-016. The bound's domain-half read: every committed"
                                        + " reversal line of one original, folded through"
                                        + " Money by ReversalBound. The identifier comes from"
                                        + " a commanding flow on an internal API (no HTTP"
                                        + " surface exists), and the journal's no-single-owner"
                                        + " stance is findById's, one entry down; the race the"
                                        + " lock-free read admits is arbitrated by V009's"
                                        + " trigger under the advisory lock.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcLedgerAccountStore.lockForShare",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "The Phase 6 -> 7 transition. lockForUpdate's shared face:"
                                        + " a payment's confirmation share-locks the account its"
                                        + " capture will credit, so it serialises with an account"
                                        + " close's FOR UPDATE. The identifier is the intent's own"
                                        + " credit account, read after findOwned resolved the"
                                        + " intent as the caller's - platform-held, never a"
                                        + " request's; ownership is the commanding surface's, as"
                                        + " lockForUpdate's.")),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantStore.findByIdForShare",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "The Phase 6 -> 7 transition. The share-locking face of"
                                        + " read, for requireTrading: on the creation the"
                                        + " authenticated key's own tenant, on the customer's"
                                        + " confirmation the merchant the SESSION names, which the"
                                        + " token opened - platform-held on both. FOR SHARE is the"
                                        + " serialisation point against a close's FOR UPDATE,"
                                        + " never an ownership check.")),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentIntentStore.anyInFlightCrediting",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.accounts.JdbcCustomerAccountStore.lockOwnedBy",
                                    "The Phase 6 -> 7 transition: a close asks whether a payment"
                                        + " in flight will credit the account, under the close's"
                                        + " lock. The account is the customer's own, locked by"
                                        + " lockOwnedBy's owner predicate; the merchant close asks"
                                        + " of the payable an operator's MERCHANT_ADMINISTER route"
                                        + " named. A boolean leaves, and no row of anybody's.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.lockForOutcome",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "The Phase 6 -> 7 transition. The refund row locked FOR"
                                        + " UPDATE before an answer is applied - the command's Tx2,"
                                        + " the sweep and the webhook - so the permit judged and"
                                        + " the conditional write see one row. The identifier is"
                                        + " minted by the command's own Tx1, the sweep's own"
                                        + " candidate, or the webhook's attribution by our minted"
                                        + " reference - never a request's.")),
                    Map.entry(
                            "com.finapp.payments.JdbcRefundStore.renewSendPermit",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.PaymentRefund.refund",
                                    "The Phase 6 -> 7 transition, payments V009: the send"
                                        + " permit's conditional renewal before a takeover's or a"
                                        + " re-drive's own send, forward only and only while the"
                                        + " refund is resolvable. The identifier is the refund the"
                                        + " claim's dispatch key converged on, or the sweep's own"
                                        + " candidate.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcLedgerAccountStore.lockForUpdate",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-015. The balance-affecting decision's serialization"
                                        + " point (ADR-0039): HoldService locks the account row"
                                        + " here, and a ledger account may be the platform's or"
                                        + " a customer's - the ledger knows only an opaque"
                                        + " owner_ref (ADR-0042), so ownership is the commanding"
                                        + " SURFACE's, and today the callers are platform flows"
                                        + " and tests. Phase 4's transfers arrive with their own"
                                        + " owner-scoped resolution and must come here and say"
                                        + " so.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcHoldStore.findById",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-015. A hold is the ledger's own reservation row -"
                                        + " the JdbcJournalEntryStore.findById stance: no single"
                                        + " owner exists to scope by, disclosure control belongs"
                                        + " to the surface, and no HTTP surface exists (plan"
                                        + " section 9 declares none). The Phase 4 flow that"
                                        + " first names a hold from a request must come here"
                                        + " and say so.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcHoldStore.findActiveFor",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-015. The availability decision's second input -"
                                        + " the standing holds of one account, read under the"
                                        + " account row's lock by HoldService and by the close's"
                                        + " emptiness check. The account id comes from the"
                                        + " locked read, never a request.")),
                    Map.entry(
                            "com.finapp.ledger.JdbcHoldStore.moveToReleased",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-015. The conditional release whose row count is"
                                        + " the outcome - the hold id reaches it through"
                                        + " HoldService.release, which locked the account row"
                                        + " first; the AND status = 'ACTIVE' is the machine's"
                                        + " edge, not an ownership check (the accounts"
                                        + " moveStatus reasoning).")),
                    Map.entry(
                            "com.finapp.ledger.JdbcBalanceProjection.adjustHolds",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "P3-TSK-015. The projection's second write seam: the"
                                        + " holds_minor consequence of an already-decided"
                                        + " placement or release, on the deciding transaction."
                                        + " The account id comes from the decision's own locked"
                                        + " read; no read exists to scope"
                                        + " (BalanceProjectionTest pins the port to void"
                                        + " writes).")),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.revoke",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.SessionRotation.rotate",
                                    "Called only by rotation, which holds the proven current"
                                        + " Session. No request can name the row. P1-TSK-016 found"
                                        + " the request-facing version of this taking an owner and"
                                        + " never checking it; that path is revokeOwned now.")),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.touch",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.JdbcSessionStore.findLive",
                                    "The interceptor touches the session it just authenticated by"
                                        + " token. The token IS the proof of ownership, so the row"
                                        + " is the caller's by construction.")),
                    Map.entry(
                            "com.finapp.identity.JdbcMfaEnrolmentStore.confirm",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.JdbcMfaEnrolmentStore.findByStatus",
                                    "MfaEnrolmentService.confirm resolves the enrolment FROM the"
                                        + " session's identity. No enrolment identifier appears in"
                                        + " the request at all - which is why /v1/me/mfa is"
                                        + " /me/ rather than /mfa/{id}.")),
                    Map.entry(
                            "com.finapp.identity.JdbcMfaEnrolmentStore.consumeStep",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.JdbcMfaEnrolmentStore.findByStatus",
                                    "MfaChallenge.elevate resolves the active factor from the"
                                        + " session's identity before spending its step.")),
                    Map.entry(
                            "com.finapp.identity.JdbcCredentialStore.supersede",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.JdbcCredentialStore.findActive",
                                    "Upgrade-on-use supersedes the credential it just verified,"
                                        + " which was read by identity. Nothing accepts a credential"
                                        + " identifier from anywhere.")),
                    Map.entry(
                            "com.finapp.party.JdbcPartyStore.findLiveCustomerFor",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-008 (SESSION_DERIVED via KycDocumentController,"
                                        + " where the PartyId comes from the proven session's"
                                        + " Identity and the endpoint names nobody),"
                                        + " reclassified by P2-TSK-015 when OwnerDeclaration"
                                        + " became its second caller with a DECLARANT-supplied"
                                        + " party identifier - the lockIdentity rule: a label"
                                        + " must be one thing, named for the weaker provenance,"
                                        + " because naming the safer path would describe the"
                                        + " caller that needs no protection. What stands in on"
                                        + " the declaration path: the audited kyc.OwnerDeclared"
                                        + " record, the acting-person authorization arriving"
                                        + " with P2-TSK-016, and the read resolving only to a"
                                        + " customer identifier that then feeds"
                                        + " conditional-everything writes.")),
                    Map.entry(
                            "com.finapp.party.JdbcPartyStore.organisationRegisteredBy",
                            new Entry(
                                    Scope.SESSION_DERIVED,
                                    "com.finapp.app.kyc.KybController",
                                    "P2-TSK-016. The KYB surface's ownership-by-absence chain:"
                                        + " the PartyId is the proven session's own"
                                        + " (Session.identityId() -> Identity.partyId(), held in"
                                        + " memory), and the statement's registrant_party_id = ?"
                                        + " IS the ownership predicate - the read can only ever"
                                        + " resolve to the organisation this person registered."
                                        + " The one request-supplied identifier on the endpoint,"
                                        + " ownerPartyId, names the declaration's SUBJECT in the"
                                        + " body and never a resource, which is exactly the"
                                        + " distinction this class's check encodes.")),
                    Map.entry(
                            "com.finapp.party.OrganisationRegistration.findExisting",
                            new Entry(
                                    Scope.SESSION_DERIVED,
                                    "com.finapp.app.kyc.OrganisationController",
                                    "P2-TSK-016. The registration's pre-read and its"
                                        + " post-conflict re-read: the registrant PartyId is the"
                                        + " proven session's own, the statement filters by"
                                        + " registrant_party_id = ?, and the endpoint takes no"
                                        + " identifier at all - a name is not one. A private"
                                        + " helper found by the detector, the revokeAll"
                                        + " shape.")),
                    Map.entry(
                            "com.finapp.party.OrganisationRegistration.insertRegistrant",
                            new Entry(
                                    Scope.SESSION_DERIVED,
                                    "com.finapp.app.kyc.OrganisationController",
                                    "P2-TSK-016. The registrant row's insert: both identifiers"
                                        + " are this transaction's own - the customer was minted"
                                        + " two statements earlier and the registrant is the"
                                        + " proven session's party - and the total unique index"
                                        + " on registrant_party_id is the arbiter, so a lost"
                                        + " race converges rather than double-registers. A"
                                        + " private helper found by the detector.")),
                    Map.entry(
                            "com.finapp.party.JdbcPartyStore.kindOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-015. A deliberately narrow read beside findById,"
                                        + " added so the owner-declaration path would not"
                                        + " falsify findById's SESSION_DERIVED claim: the"
                                        + " PartyId is declarant-supplied, and what comes back"
                                        + " is only the kind - the refusal input for the"
                                        + " bounded-depth rule (an ORGANISATION owner is"
                                        + " refused), never the person. Standing in: the"
                                        + " audited declaration and P2-TSK-016's acting-person"
                                        + " authorization.")),
                    Map.entry(
                            "com.finapp.party.JdbcPartyStore.kindOfCustomer",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-015. The case-kind resolution behind the"
                                        + " case-opening consumer: the CustomerId is the"
                                        + " consumed party.CustomerOpened event's aggregate -"
                                        + " minted by registration, carried through the outbox"
                                        + " and inbox, appearing in no request. Nothing"
                                        + " request-supplied can reach it today; classified"
                                        + " ADMINISTERED as the honest weaker label for a read"
                                        + " whose caller is the platform acting on its own"
                                        + " event, disclosing only PERSON-or-ORGANISATION.")),
                    Map.entry(
                            "com.finapp.party.JdbcPartyStore.partyOfCustomer",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-019. The consent-gate resolution behind the same"
                                        + " consumer, with kindOfCustomer's provenance"
                                        + " verbatim: the CustomerId is the consumed event's"
                                        + " aggregate, minted by registration and appearing in"
                                        + " no request. It discloses only the party behind a"
                                        + " customer, to the platform, on its own event - the"
                                        + " honest weaker label again.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcDocumentStore.findByChecksum",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.kyc.JdbcKycCaseStore.findOpenFor",
                                    "The converge branch of appendOrConverge: the KycCaseId it"
                                        + " takes is the one the document being appended already"
                                        + " carries, which came from findOpenFor - scoped by"
                                        + " customer_id in the statement. A private helper found"
                                        + " by the detector, which is the P1-TSK-021 finding"
                                        + " working as designed.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcDocumentStore.readContent",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-008. The reviewer surface (P2-TSK-012) arrived and"
                                        + " brought NO content endpoint, because the plan's"
                                        + " section 7 declares none and inventing one would be a"
                                        + " security surface chosen to suit a register entry"
                                        + " (the P1-TSK-028 rule) - so this still has no"
                                        + " production HTTP caller, now as a recorded remainder"
                                        + " rather than a prediction. What stands in for the"
                                        + " missing ownership predicate is structural:"
                                        + " production code reads content only through"
                                        + " DocumentAccess, which writes the INV-KYC-06 audit"
                                        + " record in the same unit of work - the trail of who"
                                        + " looked is the control, asserted by"
                                        + " DocumentUploadDatabaseTest.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcKycCaseStore.moveStatus",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.kyc.JdbcKycCaseStore.findOpenFor",
                                    "P2-TSK-005's seam, driven since P2-TSK-009 by the"
                                        + " assessment. A case identifier reaching THIS method"
                                        + " still comes only from findOpenFor or from"
                                        + " openOrConverge's own insert, both scoped by"
                                        + " customer_id in the statement - P2-TSK-012's reviewer"
                                        + " surface came and said so: its URL-derived exit goes"
                                        + " through moveToReadyForDecision (ADMINISTERED,"
                                        + " below), never here. The statement's AND status = ?"
                                        + " is the concurrency protocol, not an ownership"
                                        + " predicate - JdbcIdentityStore.moveStatus's recorded"
                                        + " distinction.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcKycCaseStore.findById",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-012. A reviewer names a case from the URL - the"
                                        + " ADMINISTERED shape: what stands in for the ownership"
                                        + " predicate is KYC_REVIEW at the boundary, the read"
                                        + " being on the record (kyc.CaseRead names who looked -"
                                        + " the reviewer is the insider surface), and existence"
                                        + " disclosure to a proven reviewer being the P1-TSK-028"
                                        + " decision.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcKycCaseStore.moveToReadyForDecision",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-012's exit, consolidated by P2-TSK-015 into the one"
                                        + " transition into READY_FOR_DECISION: reached with a"
                                        + " URL-derived case identifier after a"
                                        + " KYC_REVIEW-authorized resolution or decision"
                                        + " commits, and by the assessment's re-route. Every"
                                        + " clause is conditional in the statement - status ="
                                        + " from AND no OPEN task AND the ownership gate, under"
                                        + " the case-row lock - so a wrong identifier moves"
                                        + " nothing and the losing branch changes nothing.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcBeneficialOwnerStore.lockCase",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-015. The declaration's first act: the KYB case"
                                        + " identifier is supplied by the declarant"
                                        + " (P2-TSK-016's endpoint; today the OwnerDeclaration"
                                        + " service's caller). What stands in for the ownership"
                                        + " predicate: the declaration is an audited act"
                                        + " (kyc.OwnerDeclared names who declared), the"
                                        + " acting-person authorization arrives with the"
                                        + " endpoint, and this locked read discloses only"
                                        + " status and kind while every reachable write is"
                                        + " refused unless the case is a KYB case still"
                                        + " accepting owners. A private helper found by the"
                                        + " detector - the revokeAll shape.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcBeneficialOwnerStore.declaredStake",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-015. The stake sum behind the 10000-basis-point"
                                        + " bound, read under the case-row lock lockCase just"
                                        + " took - same provenance, same standing-in controls,"
                                        + " and a read that discloses a sum to a caller already"
                                        + " entitled to declare onto the case.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcBeneficialOwnerStore.parentCasesOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-015's re-route read: which KYB cases pin this case"
                                        + " as an owner's verification. The identifier's weakest"
                                        + " provenance is the reviewer's URL-derived case id"
                                        + " (the decision door), the lockIdentity"
                                        + " weaker-of-two-provenances rule; the stronger one is"
                                        + " the assessment's own chain. It returns case"
                                        + " identifiers only, and everything done with them is"
                                        + " a conditional, losing-branch-changes-nothing"
                                        + " move.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcBeneficialOwnerStore.ownersOf",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-016. The owner-graph read behind both views. The"
                                        + " identifier's weakest provenance is the reviewer's"
                                        + " URL-derived case id (the lockIdentity"
                                        + " weaker-of-two-provenances rule), where KYC_REVIEW at"
                                        + " the boundary and the kyc.CaseRead audit stand in for"
                                        + " the ownership predicate; the stronger provenance is"
                                        + " KybService's session-derived chain, which reaches"
                                        + " only the caller's own organisation's case. A read"
                                        + " that returns declarations - no document content, no"
                                        + " evidence bytes.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcReviewTaskStore.resolve",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-012. The task and case identifiers come from a"
                                        + " reviewer's URL; what stands in for the ownership"
                                        + " predicate is KYC_REVIEW at the boundary plus the"
                                        + " statement's own three conditions - id, the"
                                        + " belongs-to-case predicate (another case's task named"
                                        + " through this URL matches nothing), and status ="
                                        + " OPEN, so the losing branch writes nothing. The"
                                        + " winner's kyc.ReviewResolved record names the"
                                        + " reviewer in the same transaction (INV-KYC-04).")),
                    Map.entry(
                            "com.finapp.kyc.JdbcReviewTaskStore.findByIdForCase",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-012. The read that disambiguates a lost resolve"
                                        + " (absent vs already resolved) and shares its"
                                        + " composite predicate: id AND case_id, so another"
                                        + " case's task is indistinguishable from no task."
                                        + " KYC_REVIEW at the boundary.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcReviewTaskStore.forCase",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-012. The reviewer listing of a case's tasks,"
                                        + " URL-derived case identifier; KYC_REVIEW at the"
                                        + " boundary and the read audited as part of"
                                        + " kyc.CaseRead in the same unit of work.")),
                    Map.entry(
                            "com.finapp.party.JdbcPartyStore.moveCustomerStatus",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-014. The projection write (ADR-0035, INV-KYC-05):"
                                        + " the customer identifier is never a request's - it is"
                                        + " read from the kyc case row the decision names, and"
                                        + " the one production caller is DecisionRecording,"
                                        + " reached only past KYC_REVIEW or as the platform's"
                                        + " automatic policy. What stands in for an ownership"
                                        + " predicate is the statement's own conditional"
                                        + " (status = PENDING - the machine's edge, so a wrong"
                                        + " identifier or a non-pending customer moves nothing"
                                        + " and the caller fails the whole transaction loudly),"
                                        + " and the kyc.DecisionRecorded record riding the same"
                                        + " transaction. Nothing else transitions a customer"
                                        + " from a verification outcome.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcCheckStore.newestOfType",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.kyc.JdbcKycCaseStore.findOpenFor",
                                    "P2-TSK-009. The convergence read of requestOrConverge (one"
                                        + " question per type): its KycCaseId comes from the run,"
                                        + " which resolved the case through findOpenFor - scoped"
                                        + " by customer_id in the statement. A private helper"
                                        + " found by the detector, the JdbcDocumentStore"
                                        + ".findByChecksum shape.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcCheckStore.forCase",
                            new Entry(
                                    Scope.ADMINISTERED,
                                    "P2-TSK-009 (AUTHORITATIVE_ID on findOpenFor), reclassified"
                                        + " by P2-TSK-012 when the reviewer surface became its"
                                        + " second caller with a URL-derived case identifier -"
                                        + " the lockIdentity precedent: a label must be one"
                                        + " thing, named for the WEAKER of two provenances,"
                                        + " because naming the safer path (the assessment, whose"
                                        + " id still comes from findOpenFor) would describe the"
                                        + " caller that needs no protection. What stands in on"
                                        + " the reviewer path: KYC_REVIEW at the boundary, the"
                                        + " audited kyc.CaseRead in the same unit of work, and"
                                        + " this being a read whose rows a reviewer is entitled"
                                        + " to see.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcCheckStore.move",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.kyc.JdbcKycCaseStore.findOpenFor",
                                    "P2-TSK-009. The conditional transition behind dispatch and"
                                        + " complete: the CheckId is read off a VerificationCheck"
                                        + " the run holds, minted or converged by"
                                        + " requestOrConverge under a case findOpenFor resolved."
                                        + " No check identifier appears in any request. The"
                                        + " statement's AND status = ? is the concurrency"
                                        + " protocol, not an ownership predicate.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcCheckStore.findById",
                            new Entry(
                                    Scope.SIGNED_CALLBACK,
                                    "P2-TSK-011. The CheckId comes from a provider callback's"
                                        + " body - an external caller naming a resource, the"
                                        + " exact shape this rule exists to force a decision"
                                        + " on. What stands in for the ownership predicate: the"
                                        + " HMAC signature verified at the boundary BEFORE this"
                                        + " read runs, the identifier being one the platform"
                                        + " handed the provider (dispatch-before-call), and"
                                        + " every reachable write being a conditional"
                                        + " transition whose losing branch appends evidence and"
                                        + " changes nothing. ProviderCallbackDatabaseTest"
                                        + " proves unsigned and mis-signed deliveries are"
                                        + " refused with NOTHING written.")),
                    Map.entry(
                            "com.finapp.kyc.JdbcCheckStore.appendEvidence",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.kyc.JdbcKycCaseStore.findOpenFor",
                                    "P2-TSK-009. Evidence is retained against the check whose"
                                        + " complete() the writer just won - the same in-memory"
                                        + " VerificationCheck, provenance as move. INV-HIST-02's"
                                        + " append; the row is never read back by identifier from"
                                        + " any request.")));

    /**
     * The negative test that proves each {@link Scope#OWNER_SCOPED} predicate is load-bearing.
     *
     * <p>The build rule sees the <em>shape</em> of the statement and cannot see that it binds the
     * right owner. Only a behavioural test can, so the register is held against the suite: an
     * owner-scoped operation with no named negative test fails the build.
     */
    private static final Map<String, String> NEGATIVE_TESTS =
            Map.ofEntries(
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.ownedBy",
                            "com.finapp.app.credit.DecisionRequestDatabaseTest.anotherPartysRequestIsNotFound"),
                    Map.entry(
                            "com.finapp.credit.JdbcDecisionRequestStore.lockOwnedBy",
                            "com.finapp.app.credit.DecisionRequestDatabaseTest.anotherPartysRequestIsNotFound"),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.findOwnedBy",
                            "com.finapp.app.checkout.CheckoutFlowDatabaseTest"
                                    + ".aMerchantReadsOnlyItsOwnSession"),
                    Map.entry(
                            "com.finapp.checkout.JdbcCheckoutSessionStore.findByIntentOwnedBy",
                            "com.finapp.app.checkout.CheckoutFlowDatabaseTest"
                                    + ".theEnrichmentReadIsTenantScopedInItsOwnStatement"),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantPayoutStore.find",
                            "com.finapp.app.merchant.MerchantPayoutEndpointDatabaseTest"
                                    + ".oneNotFound"),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.findForUpdate",
                            "com.finapp.app.merchant.PayoutDestinationEndpointDatabaseTest"
                                    + ".oneNotFound"),
                    Map.entry(
                            "com.finapp.merchant.JdbcPayoutDestinationStore.findEffectiveForShare",
                            "com.finapp.app.merchant.MerchantTenancyBatteryDatabaseTest"
                                    + ".aPayoutPaysOnlyItsOwnMerchantsDestination"),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.findForCounterparties",
                            "com.finapp.app.merchant.MerchantTenancyBatteryDatabaseTest"
                                    + ".everyAddressedRouteAnswersAnotherTenantsResourceAsUnknown"),
                    // P7-TSK-014: the upload's and the answer's LOCKING read, and the document
                    // read - each merchant route probed A-on-B by the same battery.
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeStore.lockForCounterparties",
                            "com.finapp.app.merchant.MerchantTenancyBatteryDatabaseTest"
                                    + ".everyAddressedRouteAnswersAnotherTenantsResourceAsUnknown"),
                    Map.entry(
                            "com.finapp.payments.JdbcDisputeEvidenceStore"
                                    + ".readContentForCounterparties",
                            "com.finapp.app.merchant.MerchantTenancyBatteryDatabaseTest"
                                    + ".everyAddressedRouteAnswersAnotherTenantsResourceAsUnknown"),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantPayoutStore.findByDispatchKey",
                            "com.finapp.app.merchant.MerchantPayoutEndpointDatabaseTest"
                                    + ".aClientKeyIsEachMerchantsOwn"),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantApiKeyStore.findOwnedForUpdate",
                            "com.finapp.app.merchant.MerchantApiKeyDatabaseTest"
                                    + ".anotherMerchantsKeyIsTheSame404"),
                    Map.entry(
                            "com.finapp.merchant.JdbcMerchantApiKeyStore.listFor",
                            "com.finapp.app.merchant.MerchantApiKeyDatabaseTest"
                                    + ".noSecretIsRecoverable"),
                    Map.entry(
                            "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                            "com.finapp.app.payments.PaymentAuthorizationDatabaseTest"
                            + ".aStrangersPaymentIntentIsOneEmptyAnswer"),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.revokeOwned",
                            "com.finapp.app.domain.SessionOwnershipDatabaseTest"
                            + ".revocationIsRefusedForSomebodyElsesSession"),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.revokeAll",
                            "com.finapp.app.domain.SessionRevocationDatabaseTest"
                            + ".revokeAllIsScopedToItsIdentity"),
                    Map.entry(
                            "com.finapp.identity.JdbcContactChannelStore.findOwned",
                            "com.finapp.app.domain.RecoveryAbuseDatabaseTest"
                            + ".aChannelIsNotReadableByAnotherIdentity"),
                    Map.entry(
                            "com.finapp.paymentmethods.JdbcPaymentMethodStore.findOwned",
                            "com.finapp.app.paymentmethods.PaymentMethodEndpointDatabaseTest"
                            + ".aStrangersPaymentMethodIdIsOne404OnDelete"),
                    Map.entry(
                            "com.finapp.paymentmethods.JdbcPaymentMethodStore.detach",
                            "com.finapp.app.paymentmethods.PaymentMethodDatabaseTest"
                            + ".detachmentConvergesAndIsOwnershipScoped"),
                    Map.entry(
                            "com.finapp.transfers.JdbcBeneficiaryStore.remove",
                            "com.finapp.app.transfers.BeneficiaryDatabaseTest"
                            + ".removalConvergesAndIsOwnershipScoped"),
                    Map.entry(
                            "com.finapp.transfers.JdbcBeneficiaryStore.findOwned",
                            "com.finapp.app.transfers.BeneficiaryEndpointDatabaseTest"
                            + ".aStrangersBeneficiaryIdIsOne404OnDelete"),
                    Map.entry(
                            "com.finapp.transfers.JdbcTransferStore.findOwned",
                            "com.finapp.app.transfers.TransferEndpointDatabaseTest"
                            + ".aStrangersTransferIdIsOne404"),
                    Map.entry(
                            "com.finapp.accounts.JdbcCustomerAccountStore.findOwnedBy",
                            "com.finapp.app.domain.AccountEndpointDatabaseTest"
                            + ".ownershipIsExactlyTheCallers"),
                    Map.entry(
                            "com.finapp.accounts.JdbcCustomerAccountStore.lockOwnedBy",
                            "com.finapp.app.domain.AccountEndpointDatabaseTest"
                            + ".closingEndToEnd"),
                    Map.entry(
                            "com.finapp.accounts.JdbcCustomerAccountStore.lockOwnedForShare",
                            "com.finapp.app.domain.AccountEndpointDatabaseTest"
                            + ".addingACurrencyOverHttp"),
                    Map.entry(
                            "com.finapp.fx.JdbcQuoteStore.findOwned",
                            "com.finapp.app.fx.FxQuoteEndpointDatabaseTest.theQuoteIsItsOwners"),
                    Map.entry(
                            "com.finapp.fx.JdbcQuoteStore.lockOwned",
                            "com.finapp.app.fx.FxQuoteEndpointDatabaseTest.theQuoteIsItsOwners"),
                    Map.entry(
                            "com.finapp.fx.JdbcTradeStore.findOwned",
                            "com.finapp.app.fx.FxConversionDatabaseTest.theDoors"));

    /**
     * The identity schema's owner column. (This said "one name, because one module owns every
     * table this rule covers" until `P3-TSK-013` brought the second module: an OWNER_SCOPED
     * statement is now held to {@link #OWNERSHIP_PREDICATES} — each member a documented proof —
     * rather than to this one column.)
     */
    private static final String OWNER_PREDICATE = "identity_id = ?";

    /**
     * Predicates that establish <em>whose</em> row this is.
     *
     * <p>Four, each a real proof rather than a convenience. {@code identity_id = ?} names the owner
     * directly. {@code token_hash = ?} is the session lookup: a session token is a bearer credential,
     * so presenting it <strong>is</strong> the proof of ownership — which is why the interceptor may
     * touch the row it just authenticated without a second check. {@code customer_id = ?} is the
     * {@code kyc} schema's owner column (`P2-TSK-005`): a case belongs to the customer under
     * verification, and every read that hands out a case identifier is scoped by it — the entry
     * this set's own javadoc predicted would need writing down, written down.
     * {@code party_id = ?} is the {@code transfers.beneficiary} owner column (`P4-TSK-006`): a
     * saved destination belongs to the <em>Party</em> — it outlives any one customer relationship
     * (the plan §5 ownership line, the consent-record precedent) — and the party reaching the
     * statement is session-derived by the surface (`P4-TSK-007`), never a request's claim.
     */
    /** What a {@link Scope#BEARER_SCOPED} statement must carry. */
    private static final String BEARER_PREDICATE = "token_hash = ?";

    /**
     * What an {@link Scope#OWNER_SCOPED} statement must carry — one of these, in the SQL.
     *
     * <p>{@code merchant_id = ?} joins them at `P6-TSK-002`, and it is a different KIND of
     * owner from the four above: those name a person or their relationship, this names a
     * <strong>tenant</strong> — a company whose rows must be unreachable from another
     * company's credential ({@code INV-MER-01}). The rule is the same and the consequence of
     * losing it is larger: a missing {@code party_id} discloses one person's data, a missing
     * {@code merchant_id} discloses a competitor's.
     *
     * <p>{@code merchant_ref = ?} is <strong>the same tenant predicate spelled the way a module
     * that cannot see {@code merchant} must spell it</strong> (`P6-TSK-007`). ADR-0029 has
     * cross-module references travel by value, so {@code checkout} holds a merchant as a bare
     * {@code uuid} column named {@code _ref} rather than a typed {@code _id} — the isolation
     * showing up in the schema. Two spellings for one rule is a cost worth naming; the
     * alternative is a module depending on another module to say whose row this is.
     *
     * <p>{@code credit_account_id = ANY (?)} is <strong>the third spelling</strong>
     * (`P7-TSK-012`): {@code payments} cannot see {@code merchant} at all, and a payment's
     * counterparty is the account it credited — so a merchant's disputes are the disputes on
     * payments that credited ITS payables, and the set bound here is resolved from the
     * authenticated merchant through the ledger's {@code owner_ref = ?} read in the same
     * transaction ({@code MerchantPayable}'s precedent). The spelling differs; the proof is the
     * same, and the tenancy battery's probe is what shows the right set is bound.
     */
    private static final Set<String> OWNERSHIP_PREDICATES =
            Set.of(
                    OWNER_PREDICATE,
                    "token_hash = ?",
                    "customer_id = ?",
                    "party_id = ?",
                    "merchant_id = ?",
                    "merchant_ref = ?",
                    "credit_account_id = ANY (?)");

    /**
     * The tenant's packages (`P6-TST-001`): where every statement over a tenant column lives, and
     * where the detector widens to see the two shapes the class javadoc names.
     *
     * <p>Two, because {@code merchant_id} and {@code merchant_ref} are only ever written in SQL
     * here, and {@link #tenantColumnsLiveOnlyInTheTenantsPackages} holds that true. What the
     * widening does not cover is stated rather than implied: a merchant's LEDGER rows are
     * reached by {@code owner_ref}, a column the ledger module classifies for every owner
     * alike, and a customer module's by-value reads are the same blind spot left for later.
     */
    private static final Set<String> TENANT_PACKAGES =
            Set.of("com.finapp.merchant", "com.finapp.checkout");

    /** The tenant columns, as a statement spells them - never as prose about them. */
    private static final java.util.regex.Pattern TENANT_COLUMN =
            java.util.regex.Pattern.compile("\\bmerchant_(?:id|ref)\\b");

    private record Entry(Scope scope, String authoritativeRead, String reason) {
        Entry(Scope scope, String reason) {
            this(scope, null, reason);
        }
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("no persistence method takes a resource identifier without being classified")
    void everyResourceScopedOperationIsClassified() {
        assertThat(resourceScopedPersistenceMethods())
                .as("a persistence method that targets a resource by its own identifier is the"
                        + " shape ADR-0031's ownership defect takes. Classify it in REGISTER as"
                        + " OWNER_SCOPED (the owner is a predicate in the statement) or"
                        + " AUTHORITATIVE_ID (the identifier can only come from an owner-constrained"
                        + " read, which the entry must name)")
                .isEqualTo(new TreeSet<>(REGISTER.keySet()));
    }

    @Test
    @DisplayName("an OWNER_SCOPED statement actually carries the owner predicate")
    void ownerScopedStatementsCarryThePredicate() {
        List<String> missing = new ArrayList<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() != Scope.OWNER_SCOPED) {
                        return;
                    }
                    String statement = statementOf(method);
                    if (OWNERSHIP_PREDICATES.stream().noneMatch(statement::contains)) {
                        missing.add(method);
                    }
                });

        // Read from the source rather than inferred from the signature, because that is what
        // catches the defect: dropping `AND identity_id = ?` leaves the signature untouched. The
        // behavioural test catches it too, and neither replaces the other - a static sweep cannot
        // see which owner is bound, and a behavioural test cannot fail a build (P1-TSK-020).
        //
        // Read from the STRING LITERALS only, and that is not fastidiousness. The first version
        // searched the whole method body, and the mutation removing the predicate SURVIVED - because
        // revokeOwned's own comment reads "identity_id = ? IS the ownership check". A `contains`
        // over source text matches prose, so the rule was reporting a control it did not have,
        // which is worse than none because it is believed (P0-TST-008). Third occurrence of this
        // class in two tasks: right about the property, wrong about where to look.
        assertThat(missing)
                .as("an OWNER_SCOPED method whose statement has lost its ownership predicate"
                        + " (one of " + OWNERSHIP_PREDICATES + ") is classified as safe and is"
                        + " not")
                .isEmpty();
    }

    @Test
    @DisplayName("an AUTHORITATIVE_ID entry names a read that exists")
    void authoritativeReadsExist() {
        JavaClasses production = productionClasses();
        List<String> stale = new ArrayList<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() != Scope.AUTHORITATIVE_ID) {
                        return;
                    }
                    if (!methodExists(production, entry.authoritativeRead())) {
                        stale.add(method + " -> " + entry.authoritativeRead());
                    }
                });

        // The P1-TSK-019 gate's own finding, applied here from the start: that guard checked its
        // origin TYPES resolved and not that the METHODS did, so a rename left it matching nothing
        // while still reporting coverage. An entry naming a read that no longer exists is a claim
        // nobody can evaluate.
        assertThat(stale)
                .as("an AUTHORITATIVE_ID claim naming a read that does not exist is a claim about"
                        + " nothing, and the operation it excuses becomes unexamined")
                .isEmpty();
    }

    @Test
    @DisplayName("a BEARER_SCOPED statement actually carries the bearer predicate")
    void bearerScopedStatementsCarryThePredicate() {
        List<String> missing = new ArrayList<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() != Scope.BEARER_SCOPED) {
                        return;
                    }
                    if (!statementOf(method).contains(BEARER_PREDICATE)) {
                        missing.add(method);
                    }
                });

        // Symmetric with ownerScopedStatementsCarryThePredicate, and for the same reason: dropping
        // the predicate leaves the signature untouched, so nothing else in this rule would notice.
        // Here the consequence is sharper - a completion scoped by identifier alone would let anybody
        // who can read a recovery request identifier replace a stranger's credential.
        assertThat(missing)
                .as("a BEARER_SCOPED method whose statement has lost `" + BEARER_PREDICATE + "` is"
                        + " authorised by an identifier that is not a secret")
                .isEmpty();
    }

    @Test
    @DisplayName("an AUTHORITATIVE_ID provenance is itself ownership-establishing")
    void authoritativeReadsAreThemselvesScoped() {
        JavaClasses production = productionClasses();
        List<String> unscoped = new ArrayList<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() != Scope.AUTHORITATIVE_ID) {
                        return;
                    }
                    // Provenance that issues no SQL cannot be checked this way, and SessionRotation
                    // is the case: it holds a proven Session object rather than reading one. That
                    // limit is stated rather than papered over - checking it would require knowing
                    // where the caller's Session came from, which is a taint question.
                    if (!issuesSql(production, entry.authoritativeRead())) {
                        return;
                    }
                    String statement = statementOf(entry.authoritativeRead());
                    if (OWNERSHIP_PREDICATES.stream().noneMatch(statement::contains)) {
                        unscoped.add(method + " -> " + entry.authoritativeRead());
                    }
                });

        // Without this the AUTHORITATIVE_ID class is an unchecked escape hatch, and five of the
        // seven entries rest on it. The whole justification is "the identifier came from an
        // owner-constrained read" - so if that read stops being owner-constrained, every operation
        // it excuses becomes unscoped at once, silently, and the register still reads as a control.
        assertThat(unscoped)
                .as("an AUTHORITATIVE_ID entry claims its identifier came from an owner-constrained"
                        + " read. A read scoped by nothing but a primary key proves no ownership,"
                        + " and every operation citing it inherits the gap")
                .isEmpty();
    }

    @Test
    @DisplayName("every OWNER_SCOPED operation names a negative test that exists")
    void ownerScopedOperationsHaveNegativeTests() {
        TreeSet<String> ownerScoped = new TreeSet<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() == Scope.OWNER_SCOPED) {
                        ownerScoped.add(method);
                    }
                });

        assertThat(new TreeSet<>(NEGATIVE_TESTS.keySet()))
                .as("INV-AUD-03 requires a PASSING NEGATIVE TEST per authorization check, and"
                        + " ADR-0031 requires one per resource-scoped operation. The build rule"
                        + " cannot see which owner is bound; the negative test is what does")
                .isEqualTo(ownerScoped);

        JavaClasses tests = testClasses();
        List<String> missing =
                NEGATIVE_TESTS.values().stream()
                        .filter(named -> !methodExists(tests, named))
                        .toList();
        assertThat(missing)
                .as("a named negative test that does not exist is the pattern this phase has met"
                        + " five times: a claim that reads as true, so the next reader stops looking")
                .isEmpty();
    }

    @Test
    @DisplayName("a statement given an owner must reference the owner")
    void everyOwnerTakingStatementReferencesTheOwner() {
        List<String> ignoringTheOwner = new ArrayList<>();
        for (JavaClass javaClass : productionClasses()) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!issuesSql(method) || !takesTheOwner(method)) {
                    continue;
                }
                String qualified = javaClass.getName() + "." + method.getName();
                if (!referencesTheOwner(statementOf(qualified))) {
                    ignoringTheOwner.add(qualified);
                }
            }
        }

        // This is the OTHER half of ownership, and the defect it catches is one this repository has
        // actually shipped. P1-TSK-016 found SessionRevocation.revoke taking an `owner` and never
        // using it: the statement was `WHERE id = ? AND status = 'ACTIVE'`, so ANY caller could end
        // ANY session by identifier, while the audit record confidently asserted an owner nobody had
        // verified. Worse than an absent parameter, because the signature read as though ownership
        // were enforced.
        //
        // A method handed an IdentityId and not mentioning the owner column is that shape exactly.
        // It also covers what the resource-identifier rule structurally cannot see: `findLiveFor`
        // takes no resource identifier, so nothing above would notice it losing its scope - and that
        // is a BULK disclosure, every session of every customer, rather than one row.
        assertThat(ignoringTheOwner)
                .as("a persistence method handed an IdentityId and not naming the owner in its"
                        + " statement has been given ownership information and discarded it - the"
                        + " P1-TSK-016 defect, where the signature reads as though the check is"
                        + " enforced and the audit trail is then wrong rather than silent")
                .isEmpty();
    }

    @Test
    @DisplayName("every module with production code is within reach of this rule")
    void everyModuleWithProductionCodeIsAnalysed() {
        java.util.Set<String> analysed =
                productionClasses().stream()
                        .map(ProductionModules::of)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // The sibling idiom, and the completion gate added it because this suite had deviated from
        // it with a bare isNotEmpty(). Set equality on the register protects `identity` and
        // `platform` - narrowing the sweep would drop their entries and fail - but `party` is
        // protected by nothing: partyHasNothingToScope would pass VACUOUSLY over a sweep that never
        // reached it, which is the exact P0-TSK-008 finding and the reason every rule suite here
        // carries this assertion.
        assertThat(analysed)
                .as("every module with production classes must be within reach of the ownership"
                        + " rule, or an unclassified operation in it is simply invisible")
                .containsAll(ProductionModules.onClasspathWithProductionClasses());
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees production code and finds real methods")
    void theGuardHasTeeth() {
        assertThat(productionClasses())
                .as("the sweep must actually import production classes")
                .isNotEmpty();

        // Without this, every assertion above passes over a detector that matches nothing - the
        // "green while checking nothing" failure this repository has met repeatedly.
        assertThat(resourceScopedPersistenceMethods())
                .as("the detector must find the operations that exist today")
                .isNotEmpty();

        // And the source reader must actually return a body, or ownerScopedStatementsCarryThePredicate
        // would fail for the wrong reason - or, worse, a `contains` over the whole file would pass
        // for the wrong reason.
        assertThat(methodSource("com.finapp.identity.JdbcSessionStore.revokeOwned"))
                .as("the method-body extraction must find the statement it claims to read")
                .contains("UPDATE")
                .contains(OWNER_PREDICATE);
    }

    /**
     * A {@code SESSION_DERIVED} endpoint accepts nothing that could name a resource.
     *
     * <p>That is the whole control, and it is stronger than a predicate rather than weaker: ADR-0031
     * names <em>trusting an identifier out of the request</em> as the defect, and an endpoint with
     * no path variable and no request parameter has none to trust. It is also the one part of this
     * classification a build rule can check, so it is the part that is checked.
     */
    @Test
    @DisplayName("a SESSION_DERIVED endpoint takes no request-supplied identifier")
    void sessionDerivedEndpointsTakeNoIdentifier() {
        java.util.Set<String> endpoints =
                REGISTER.values().stream()
                        .filter(entry -> entry.scope() == Scope.SESSION_DERIVED)
                        .map(Entry::authoritativeRead)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        assertThat(endpoints)
                .as("a SESSION_DERIVED entry must name the endpoint whose absence of parameters is"
                        + " the control")
                .isNotEmpty();

        JavaClasses classes = testClasses();
        for (String endpoint : endpoints) {
            assertThat(productionClasses().contain(endpoint) || classes.contain(endpoint))
                    .as("%s does not exist", endpoint)
                    .isTrue();

            for (JavaMethod handler : productionClasses().get(endpoint).getMethods()) {
                for (com.tngtech.archunit.core.domain.JavaParameter parameter :
                        handler.getParameters()) {
                    boolean namesAResource =
                            parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.PathVariable
                                                    .class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.RequestParam
                                                    .class);
                    assertThat(namesAResource)
                            .as(
                                    "%s.%s accepts a request-supplied identifier, so the resource is"
                                        + " no longer derived from the session alone",
                                    endpoint, handler.getName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("P6-TST-001: a tenant column is written in SQL only inside the tenant's packages")
    void tenantColumnsLiveOnlyInTheTenantsPackages() throws IOException {
        java.util.TreeSet<String> outside = new java.util.TreeSet<>();
        java.util.TreeSet<String> inside = new java.util.TreeSet<>();
        for (Path source : productionSources()) {
            String text = Files.readString(source);
            if (!TENANT_COLUMN.matcher(stringLiteralsOf(text)).find()) {
                continue;
            }
            String type = typeOf(source, text);
            if (inTenantPackage(type.substring(0, type.lastIndexOf('.')))) {
                inside.add(type);
            } else {
                outside.add(type);
            }
        }

        // The widening in TENANT_PACKAGES is what makes the tenant's UUID reads and helper-split
        // statements visible to this register. A statement over merchant_id or merchant_ref in
        // any other package would be one the widening never looks at - invisible exactly as
        // checkout's reads were until this task. So it fails here and forces the decision:
        // move the statement into the tenant's store, or widen TENANT_PACKAGES to where it is.
        //
        // Literals only, comments stripped by a scanner rather than a regex: a comment naming
        // the column is prose, and the P1-TSK-021 lesson is that a rule matching prose reports
        // a control it does not have.
        assertThat(outside)
                .as("a tenant column (merchant_id, merchant_ref) in a SQL literal outside "
                        + TENANT_PACKAGES + " is a tenant statement the widened detector cannot"
                        + " see")
                .isEmpty();
        assertThat(inside)
                .as("the guard is not vacuous: it finds the tenant's own statements")
                .contains(
                        "com.finapp.checkout.JdbcCheckoutSessionStore",
                        "com.finapp.merchant.JdbcMerchantPayoutStore",
                        "com.finapp.merchant.JdbcPayoutDestinationStore");
    }

    @Test
    @DisplayName("P6-TST-001: the widening has teeth - it sees the UUID reads and the helper-split"
            + " statements it exists for")
    void theTenantWideningSeesWhatItWasFor() {
        // Named rather than counted: the four shapes P6-TSK-009's gate and this task's design
        // found, each of which the EntityId-only, direct-call-only detector could not see.
        assertThat(resourceScopedPersistenceMethods())
                .contains(
                        // a bare UUID, read directly
                        "com.finapp.checkout.JdbcCheckoutSessionStore.findByIntentOwnedBy",
                        "com.finapp.merchant.JdbcPaymentFeePinStore.findFor",
                        // a typed id, its statement split across a generic helper
                        "com.finapp.merchant.JdbcMerchantPayoutStore.find",
                        "com.finapp.merchant.JdbcPayoutDestinationStore.findEffectiveForShare");
        // ...and the scanner the guard rests on strips comments and keeps text blocks.
        assertThat(stringLiteralsOf("// merchant_id = ?\n/* merchant_ref */ String x = \"id = ?\";"))
                .doesNotContain("merchant_");
        assertThat(stringLiteralsOf("String x = \"\"\"\n  WHERE merchant_id = ?\n  \"\"\";"))
                .contains("merchant_id = ?");
    }

    @Test
    @DisplayName("party's ownership surface is classified, and it exists as of P1-TSK-030")
    void partysOwnershipSurfaceIsClassified() {
        // This assertion used to read "party owns no resource-scoped operation", and it was written
        // to FAIL the day that stopped being true. P1-TSK-030 is that day: /v1/me reads and renames
        // a Party by identifier, which is the module's first ownership surface.
        //
        // The guard behaved exactly as intended - it did not quietly widen, it broke - so what
        // replaces it is the same claim from the other side: whatever party operations exist must
        // be in REGISTER, which the sweep above already enforces, and there must be some, or this
        // assertion has silently become the vacuous thing it replaced.
        assertThat(resourceScopedPersistenceMethods())
                .as("party's ownership surface must be visible to this rule, not merely absent")
                .anyMatch(method -> method.startsWith("com.finapp.party."));
    }

    // -----------------------------------------------------------------

    /**
     * Production methods that issue SQL and take a resource identifier.
     *
     * <p>Detected structurally, never by name. <em>Issues SQL</em> is a call to
     * {@link java.sql.Connection#prepareStatement}, which cannot be dodged by renaming a class out
     * of the {@code *Store} convention. <em>Resource identifier</em> is an {@code EntityId} subtype
     * other than {@code IdentityId} — so a new aggregate's identifier is in scope the day it is
     * declared, without anyone remembering.
     *
     * <p>In {@link #TENANT_PACKAGES} both halves widen (`P6-TST-001`): a bare {@code UUID} is a
     * resource identifier, and SQL a same-class helper issues is the caller's, one hop deep.
     */
    private static TreeSet<String> resourceScopedPersistenceMethods() {
        TreeSet<String> found = new TreeSet<>();
        for (JavaClass javaClass : productionClasses()) {
            for (JavaMethod method : javaClass.getMethods()) {
                String qualified = javaClass.getName() + "." + method.getName();
                if (!issuesSqlInScope(method)
                        || !takesAResourceIdentifierInScope(method, qualified)) {
                    continue;
                }
                found.add(qualified);
            }
        }
        return found;
    }

    /**
     * Whether a named method issues SQL, as the detector sees it. Absent methods are handled by
     * {@code authoritativeReadsExist}.
     */
    private static boolean issuesSql(JavaClasses classes, String qualified) {
        String owner = qualified.substring(0, qualified.lastIndexOf('.'));
        String method = qualified.substring(qualified.lastIndexOf('.') + 1);
        return classes.contain(owner)
                && classes.get(owner).getMethods().stream()
                        .filter(candidate -> candidate.getName().equals(method))
                        .anyMatch(OwnershipIsScopedTest::issuesSqlInScope);
    }

    private static boolean inTenantPackage(String packageName) {
        return TENANT_PACKAGES.stream()
                .anyMatch(tenant -> packageName.equals(tenant) || packageName.startsWith(tenant + "."));
    }

    /**
     * SQL issued by the method itself or, in a tenant package, by a same-class helper it calls.
     *
     * <p>One hop, deliberately: a store's generic helper ({@code one(unitOfWork, sql, ...)}) is
     * the shape found, and a chain deeper than that is a design to question before it is one to
     * detect. The helper itself carries no identifier of its own when it takes its parameters as
     * a varargs array, so it is classified through its callers rather than for them.
     */
    private static boolean issuesSqlInScope(JavaMethod method) {
        if (issuesSql(method)) {
            return true;
        }
        if (!inTenantPackage(method.getOwner().getPackageName())) {
            return false;
        }
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            if (!call.getTargetOwner().equals(method.getOwner())) {
                continue;
            }
            java.util.Optional<JavaMethod> helper = call.getTarget().resolveMember();
            if (helper.isPresent() && !helper.get().equals(method) && issuesSql(helper.get())) {
                return true;
            }
        }
        return false;
    }

    /** {@link #takesAResourceIdentifier}, and in a tenant package a bare {@code UUID} too. */
    private static boolean takesAResourceIdentifierInScope(JavaMethod method, String qualified) {
        if (takesAResourceIdentifier(method, qualified)) {
            return true;
        }
        return inTenantPackage(method.getOwner().getPackageName())
                && method.getRawParameterTypes().stream()
                        .anyMatch(parameter -> parameter.getName().equals("java.util.UUID"));
    }

    /** Every production source file on the platform - the guard reads what the build compiles. */
    private static List<Path> productionSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (java.util.stream.Stream<Path> modules = Files.list(repositoryRoot())) {
            for (Path module : modules.toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> files = Files.walk(main)) {
                    files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
                }
            }
        }
        return sources;
    }

    /** The type a source file declares, from its package line and its file name. */
    private static String typeOf(Path source, String text) {
        java.util.regex.Matcher declared =
                java.util.regex.Pattern.compile("(?m)^package\\s+([\\w.]+);").matcher(text);
        String name = source.getFileName().toString();
        return (declared.find() ? declared.group(1) + "." : "")
                + name.substring(0, name.length() - ".java".length());
    }

    /**
     * The contents of every string literal and text block in {@code source}, and nothing else.
     *
     * <p>A character scanner rather than a pattern, because the two things a pattern gets wrong
     * here are the two that matter: a quote inside a comment, and a comment marker inside a
     * string ({@code "http://"}). Character literals are skipped so {@code '"'} opens nothing.
     */
    private static String stringLiteralsOf(String source) {
        StringBuilder literals = new StringBuilder();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? length : end;
                literals.append(source, i + 3, end).append('\n');
                i = Math.min(length, end + 3);
            } else if (c == '"') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                literals.append(source, i + 1, Math.min(j, length)).append('\n');
                i = j + 1;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '\'') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return literals.toString();
    }

    /** A method handed the owner. {@code IdentityId} is the owner type in every module here. */
    private static boolean takesTheOwner(JavaMethod method) {
        return method.getRawParameterTypes().stream()
                .anyMatch(parameter -> parameter.getName().equals("com.finapp.identity.IdentityId"));
    }

    /**
     * Whether a statement names who the row belongs to.
     *
     * <p>{@code identity.identity} is the one table where the owner column is called {@code id},
     * because the row <em>is</em> the identity. That is a structural fact about the schema rather
     * than an exemption for a particular method, so it is stated as part of the rule - the only
     * caller today is the {@code FOR UPDATE} lock bulk revocation takes.
     */
    private static boolean referencesTheOwner(String statement) {
        return statement.contains("identity_id")
                || (statement.contains("identity.identity") && statement.contains("id = ?"));
    }

    private static boolean issuesSql(JavaMethod method) {
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            if (call.getTargetOwner().isAssignableTo(java.sql.Connection.class)
                    && call.getName().equals("prepareStatement")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A parameter naming a resource rather than its owner.
     *
     * <p>{@code IdentityId} is <em>usually</em> the owner, so an operation scoped by one is scoped
     * by definition and needs no entry. {@code P1-TSK-028} made that conditional rather than
     * absolute: an administrative operation takes an {@code IdentityId} that came from a URL and
     * names <strong>somebody else</strong>, so there the identity is the resource.
     *
     * <p>The discriminator is the statement: reaching a row of {@code identity.identity} by its
     * primary key is an operation <em>on</em> an identity, which is exactly the administrative
     * shape. Scoping <em>by</em> an identity — {@code WHERE identity_id = ?} on somebody's sessions
     * or credentials — is the ordinary one and stays excluded.
     */
    private static boolean takesAResourceIdentifier(JavaMethod method, String qualified) {
        boolean nonIdentityResource =
                method.getRawParameterTypes().stream()
                        .anyMatch(
                                parameter ->
                                        parameter.isAssignableTo(
                                                        com.finapp.sharedkernel.id.EntityId.class)
                                                && !parameter
                                                        .getName()
                                                        .equals("com.finapp.identity.IdentityId"));
        return nonIdentityResource || actsOnAnIdentityRow(method, qualified);
    }

    /** Takes an {@code IdentityId} and reaches {@code identity.identity} by primary key. */
    private static boolean actsOnAnIdentityRow(JavaMethod method, String qualified) {
        boolean takesAnIdentity =
                method.getRawParameterTypes().stream()
                        .anyMatch(
                                parameter ->
                                        parameter
                                                .getName()
                                                .equals("com.finapp.identity.IdentityId"));
        if (!takesAnIdentity) {
            return false;
        }
        String statement = statementOf(qualified);
        return statement.contains("identity.identity") && statement.contains("id = ?");
    }

    private static boolean methodExists(JavaClasses classes, String qualified) {
        String owner = qualified.substring(0, qualified.lastIndexOf('.'));
        String method = qualified.substring(qualified.lastIndexOf('.') + 1);
        return classes.contain(owner)
                && classes.get(owner).getMethods().stream()
                        .anyMatch(candidate -> candidate.getName().equals(method));
    }

    /**
     * The string literals of one method, concatenated - the SQL and nothing else.
     *
     * <p>A comment cannot satisfy this, which is the whole point: SQL is only SQL if it is inside a
     * literal.
     *
     * <p><strong>The unrolled-loop pattern, deliberately.</strong> The obvious
     * {@code (?:[^"\]|\.)*} form backtracks catastrophically, and it survived until the
     * completion gate widened the sweep from two small methods to every persistence method - at
     * which point it overflowed the stack on the first long body. A regex that is correct on the
     * input its author happened to try is the same class of defect as a rule that is correct on the
     * module its author was thinking of.
     */
    private static String statementOf(String qualified) {
        String body = methodSource(qualified);
        StringBuilder literals = new StringBuilder(literalsIn(body));

        // ...and the values of the class's own String constants that this body names.
        //
        // Added by P1-TSK-028, which is the first statement built from a table-name constant:
        // `"SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?"` puts `identity.identity`
        // nowhere in this method's literals, so `referencesTheOwner` could not see an owner that is
        // plainly there. The blind spot is older than that task and had simply never been reached,
        // because every earlier statement happened to mention `identity_id` in a literal of its own.
        //
        // A rule that silently stops recognising a correct statement is the failure this class was
        // written to prevent, one level up: it would have forced the constant to be inlined - a
        // change made to satisfy a detector rather than to state a property.
        String owner = qualified.substring(0, qualified.lastIndexOf('.'));
        for (java.util.Map.Entry<String, String> constant : stringConstantsOf(owner).entrySet()) {
            if (namesIdentifier(body, constant.getKey())) {
                literals.append(constant.getValue());
            }
        }
        return literals.toString();
    }

    private static String literalsIn(String source) {
        StringBuilder literals = new StringBuilder();
        java.util.regex.Matcher quoted =
                java.util.regex.Pattern.compile("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"")
                        .matcher(source);
        while (quoted.find()) {
            literals.append(quoted.group());
        }
        return literals.toString();
    }

    /** Every {@code static final String NAME = "..."} the class declares. */
    private static java.util.Map<String, String> stringConstantsOf(String owner) {
        java.util.Map<String, String> constants = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher declared =
                java.util.regex.Pattern.compile(
                                "static final String\\s+([A-Z0-9_]+)\\s*=\\s*"
                                        + "((?:\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"\\s*\\+?\\s*)+);")
                        .matcher(readSourceOf(owner));
        while (declared.find()) {
            constants.put(declared.group(1), literalsIn(declared.group(2)));
        }
        return constants;
    }

    /** Word-boundary match, so {@code TABLE} is not found inside {@code TABLE_NAME}. */
    private static boolean namesIdentifier(String body, String name) {
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b")
                .matcher(body)
                .find();
    }

    /** The body of one method, read from source - the only place a SQL literal exists. */
    private static String methodSource(String qualified) {
        String owner = qualified.substring(0, qualified.lastIndexOf('.'));
        String method = qualified.substring(qualified.lastIndexOf('.') + 1);
        String source = readSourceOf(owner);

        int signature = declarationOf(source, method);
        int open = source.indexOf('{', signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char character = source.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("Unbalanced braces reading " + qualified);
    }

    /**
     * Where the method is <em>declared</em>, never where it is called.
     *
     * <p>The first version searched for {@code " name("} and took the first hit. In
     * {@code JdbcSessionStore} that is {@code return revokeAll(unitOfWork, ...)} inside
     * {@code revokeAllFor} — a <strong>call site</strong> — so the extraction would have read the
     * wrong body and the predicate assertion would have been about a method nobody chose. Found by
     * running it rather than by reading it, which is how this class of defect is always found here.
     */
    private static int declarationOf(String source, String method) {
        int from = 0;
        while (true) {
            int hit = source.indexOf(method + "(", from);
            if (hit < 0) {
                throw new IllegalStateException("No declaration of " + method + " in that source");
            }
            // The line-feed codepoint, not System.lineSeparator(): this reads a source FILE, whose
            // endings are LF in this repository regardless of the platform running the build.
            int lineStart = source.lastIndexOf(10, hit) + 1;
            String line = source.substring(lineStart, hit).trim();
            if (line.startsWith("public ")
                    || line.startsWith("private ")
                    || line.startsWith("protected ")) {
                return hit;
            }
            from = hit + 1;
        }
    }

    private static String readSourceOf(String type) {
        String module = type.substring("com.finapp.".length());
        module = module.substring(0, module.indexOf('.'));
        Path path =
                repositoryRoot()
                        .resolve(module)
                        .resolve("src/main/java")
                        .resolve(type.replace('.', '/') + ".java");
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the source of " + type, e);
        }
    }

    /** The {@code DomainGlossaryTest} idiom - the working directory differs between IDE and Gradle. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }

    private static JavaClasses testClasses() {
        return new ClassFileImporter().importPackages("com.finapp");
    }
}
