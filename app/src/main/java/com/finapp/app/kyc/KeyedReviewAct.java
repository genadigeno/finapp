package com.finapp.app.kyc;

import com.finapp.kyc.DecisionOutcome;
import com.finapp.kyc.InformationRequestReason;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.ReviewTaskId;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;

/**
 * A reviewer's act under its {@code Idempotency-Key} (`P17-TSK-029`, gap G-61; ADR-0004, {@code INV-IDEM-01}, {@code -03}): the
 * case's decision, or a review task's resolution, run at most once per key and replayed on every retry. Since `P17-TSK-126` also
 * a reviewer's request for information and the customer's answer to it - the same discipline, each under its own scope.
 *
 * <p><strong>The key names its principal</strong> (`X-TSK-003`): the scope is the act's and the reviewer's, so two reviewers
 * choosing the same key never meet, and a retry is always the reviewer's own. <strong>The fingerprint is the request's
 * meaning</strong> - the case, the outcome or the task, and the reason's digest, so the stored record carries no prose - and the
 * same key under another of them is {@code 409 api.Conflict}.
 *
 * <p><strong>Only the act is stored; a refusal never spends the key.</strong> The act's own outcome ({@code RECORDED},
 * {@code RESOLVED}) is the response of record, claimed, written and stored in the act's transaction, so a retry after a lost
 * answer replays it rather than meeting the conditional it already won as a {@code 409}. A refusal - already decided, not ready,
 * not found - changed nothing; it leaves this class as {@link Refused}, which rolls the transaction back and the claim with it,
 * so a later retry under the same key is judged again: a case not ready at the first attempt is decidable under the same key once
 * it is. Storing the refusal instead would pin a passing answer on the key for its whole retention.
 */
final class KeyedReviewAct {

    static final String DECISION_SCOPE = "kyc.case-decision:";
    static final String RESOLUTION_SCOPE = "kyc.review-resolution:";
    static final String INFORMATION_REQUEST_SCOPE = "kyc.information-request:";
    static final String INFORMATION_RESPONSE_SCOPE = "kyc.information-response:";

    private final IdempotentExecutor executor;
    private final IdempotencyKey key;
    private final RequestFingerprint fingerprint;

    private KeyedReviewAct(IdempotentExecutor executor, IdempotencyKey key, RequestFingerprint fingerprint) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    }

    /** A reviewer's decision on a case under {@code rawKey}. */
    static KeyedReviewAct decision(
            IdempotentExecutor executor, String rawKey, Actor reviewer, KycCaseId caseId, DecisionOutcome outcome, String reason) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        return new KeyedReviewAct(executor, key(DECISION_SCOPE, reviewer, rawKey),
                fingerprint(caseId.value() + "|" + outcome.name() + "|" + digest(reason)));
    }

    /** A reviewer's resolution of one of a case's review tasks under {@code rawKey}. */
    static KeyedReviewAct resolution(
            IdempotentExecutor executor, String rawKey, Actor reviewer, KycCaseId caseId, ReviewTaskId taskId, String reason) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        return new KeyedReviewAct(executor, key(RESOLUTION_SCOPE, reviewer, rawKey),
                fingerprint(caseId.value() + "|" + taskId.value() + "|" + digest(reason)));
    }

    /**
     * A reviewer's request to a case's customer for more information under {@code rawKey} (`P17-TSK-126`, ADR-0155 decision 3):
     * the fingerprint is the case, the reason code and the text's digest.
     */
    static KeyedReviewAct informationRequest(
            IdempotentExecutor executor, String rawKey, Actor reviewer, KycCaseId caseId, InformationRequestReason reason,
            String requestText) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return new KeyedReviewAct(executor, key(INFORMATION_REQUEST_SCOPE, reviewer, rawKey),
                fingerprint(caseId.value() + "|" + reason.name() + "|" + digest(requestText)));
    }

    /**
     * The customer's answer to their case's open request under {@code rawKey} (`P17-TSK-126`, ADR-0155 decision 6): the scope
     * names the customer, as every key names its principal; the fingerprint is the answer's digest - the case is the session's.
     */
    static KeyedReviewAct informationResponse(IdempotentExecutor executor, String rawKey, Actor customer, String answer) {
        return new KeyedReviewAct(executor, key(INFORMATION_RESPONSE_SCOPE, customer, rawKey),
                fingerprint("answer|" + digest(answer)));
    }

    IdempotencyKey key() {
        return key;
    }

    RequestFingerprint fingerprint() {
        return fingerprint;
    }

    /**
     * Runs {@code act} in {@code unitOfWork} under the key: {@code acted} stored and answered, or replayed from the store on a
     * retry; any other outcome thrown as {@link Refused}, which the caller catches outside its transaction.
     */
    <R extends Enum<R>> R run(Connection unitOfWork, Class<R> outcomes, R acted, Function<Connection, R> act) {
        Objects.requireNonNull(outcomes, "outcomes must not be null");
        Objects.requireNonNull(acted, "acted must not be null");
        Objects.requireNonNull(act, "act must not be null");
        IdempotentExecutor.ExecutionOutcome outcome = executor.execute(unitOfWork, key, fingerprint, work -> {
            R judged = act.apply(work);
            if (judged != acted) {
                throw new Refused(judged);
            }
            return CommandResult.succeeded(StoredResponse.of(judged.name().getBytes(StandardCharsets.UTF_8), "text/plain"));
        });
        byte[] stored = outcome.body().orElseThrow(() -> new IllegalStateException("a reviewer's act stores its outcome"));
        return Enum.valueOf(outcomes, new String(stored, StandardCharsets.UTF_8));
    }

    /**
     * A refusal under a key: it changed nothing, so it rolls its transaction back - the claim included - and the caller answers
     * {@link #outcome()}. Control flow, not a failure: no stack trace.
     */
    static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Enum<?> outcome;

        Refused(Enum<?> outcome) {
            super("refused: " + outcome.name(), null, false, false);
            this.outcome = outcome;
        }

        <R extends Enum<R>> R outcome(Class<R> outcomes) {
            return outcomes.cast(outcome);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static IdempotencyKey key(String scope, Actor reviewer, String rawKey) {
        Objects.requireNonNull(reviewer, "reviewer must not be null");
        return new IdempotencyKey(scope + reviewer.keyedType().name() + ":" + reviewer.id(), rawKey);
    }

    private static RequestFingerprint fingerprint(String meaning) {
        return RequestFingerprint.sha256(meaning.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(reason.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", impossible);
        }
    }
}
