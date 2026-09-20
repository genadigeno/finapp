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
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.payments.JdbcPaymentIntentStore.findOwned",
                                    "The converge re-read after a lost conditional transition,"
                                        + " inside the same command transaction that already"
                                        + " passed findOwned - the identifier is never a"
                                        + " request's. HTTP reads go through findOwned.")),
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
                            + ".closingEndToEnd"));

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

    private static final Set<String> OWNERSHIP_PREDICATES =
            Set.of(OWNER_PREDICATE, "token_hash = ?", "customer_id = ?", "party_id = ?");

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
     */
    private static TreeSet<String> resourceScopedPersistenceMethods() {
        TreeSet<String> found = new TreeSet<>();
        for (JavaClass javaClass : productionClasses()) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!issuesSql(method)
                        || !takesAResourceIdentifier(
                                method, javaClass.getName() + "." + method.getName())) {
                    continue;
                }
                found.add(javaClass.getName() + "." + method.getName());
            }
        }
        return found;
    }

    /** Whether a named method issues SQL. Absent methods are handled by {@code authoritativeReadsExist}. */
    private static boolean issuesSql(JavaClasses classes, String qualified) {
        String owner = qualified.substring(0, qualified.lastIndexOf('.'));
        String method = qualified.substring(qualified.lastIndexOf('.') + 1);
        return classes.contain(owner)
                && classes.get(owner).getMethods().stream()
                        .filter(candidate -> candidate.getName().equals(method))
                        .anyMatch(OwnershipIsScopedTest::issuesSql);
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
