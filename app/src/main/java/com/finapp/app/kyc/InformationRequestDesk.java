package com.finapp.app.kyc;

import com.finapp.identity.Identity;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.kyc.CustomerAnswer;
import com.finapp.kyc.CustomerRequestText;
import com.finapp.kyc.InformationRequestReason;
import com.finapp.kyc.InformationRequests;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycErrorCode;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A case awaiting its customer, at its two doors (`P17-TSK-126`, ADR-0155): the reviewer's request and the customer's answer,
 * each one kyc transaction under its {@code Idempotency-Key} ({@link KeyedReviewAct}), and the case's readiness re-asked after a
 * return commits.
 *
 * <p><strong>The text is screened before anything opens.</strong> {@link CustomerRequestText#of} refuses a text that could tip
 * the customer off; the door answers {@code 422 kyc.RequestTextRefused} naming the rule, no transaction began and no key was
 * spent. The log line names the rule, never the text.
 *
 * <p><strong>A refusal spends no key</strong> ({@link KeyedReviewAct}): a case not in review, already awaiting, a KYB case, or
 * an answer with nothing to answer roll their transaction back with the claim, so the same key is judged again later.
 *
 * <p><strong>The return re-asks readiness after its commit</strong> (ADR-0155 decision 8), and on a replay too - the
 * {@code ReviewService.resolve} discipline: a reviewer may have resolved the last task while the customer held the case, and a
 * crash between the answer's commit and the re-ask is healed by the customer's retry.
 */
public final class InformationRequestDesk {

    /** What the customer's answer came to. */
    public enum Answered {
        /** The open request is answered and the case is back in review. */
        ANSWERED,
        /** Nothing to answer: no request open, or its deadline passed. */
        NOTHING_TO_ANSWER,
        /** The session resolves to no customer with an open case. */
        NO_OPEN_CASE
    }

    private final InformationRequests requests;
    private final KycCaseStore<Connection> cases;
    private final IdentityStore<Connection> identities;
    private final PartyStore<Connection> parties;
    private final Clock clock;
    private final KycUnitOfWork units;

    public InformationRequestDesk(
            InformationRequests informationRequests,
            KycCaseStore<Connection> kycCaseStore,
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.requests = Objects.requireNonNull(informationRequests, "informationRequests must not be null");
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.identities = Objects.requireNonNull(identityStore, "identityStore must not be null");
        this.parties = Objects.requireNonNull(partyStore, "partyStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.units = new KycUnitOfWork(
                Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    /**
     * The reviewer {@code reviewer} asks the customer of {@code caseId}, under {@code keyed}.
     *
     * @throws ApiException {@code 422 kyc.RequestTextRefused} for a text the customer may not be sent
     */
    public InformationRequests.Requested request(
            KycCaseId caseId, InformationRequestReason reason, String requestText, IdentityId reviewer, KeyedReviewAct keyed) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(reviewer, "reviewer must not be null");
        Objects.requireNonNull(keyed, "keyed must not be null");
        CustomerRequestText text = screened(requestText);
        CorrelationId correlation = correlation();
        try {
            return units.inTransaction(unitOfWork -> keyed.run(unitOfWork, InformationRequests.Requested.class,
                    InformationRequests.Requested.REQUESTED,
                    work -> requests.request(work, caseId, reason, text, SecurityContext.require(), reviewer.value(),
                            correlation)));
        } catch (KeyedReviewAct.Refused refused) {
            return refused.outcome(InformationRequests.Requested.class);
        }
    }

    /**
     * The customer on {@code current} answers their open case's open request with {@code answerText}, under {@code keyed}; the
     * case's readiness re-asked after the commit, and after a replay.
     */
    public Answered answer(Session current, String answerText, KeyedReviewAct keyed) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(keyed, "keyed must not be null");
        CustomerAnswer answer = new CustomerAnswer(Objects.requireNonNull(answerText, "answerText must not be null"));
        CorrelationId correlation = correlation();
        // The case is the session's, found before the key's transaction: a customer with no open case has nothing a key could
        // stand for, and the re-ask after the commit needs the case's identifier however the act came out.
        Optional<KycCase> openCase = units.inTransaction(unitOfWork -> openCaseOf(unitOfWork, current));
        if (openCase.isEmpty()) {
            return Answered.NO_OPEN_CASE;
        }
        KycCaseId caseId = openCase.get().id();
        Answered outcome;
        try {
            outcome = units.inTransaction(unitOfWork -> keyed.run(unitOfWork, Answered.class, Answered.ANSWERED,
                    work -> cases.findById(work, caseId)
                            .filter(kycCase -> !kycCase.status().isTerminal())
                            .flatMap(kycCase -> requests.answer(work, kycCase, answer, SecurityContext.require(),
                                    current.identityId().value(), correlation))
                            .map(answered -> Answered.ANSWERED)
                            .orElse(Answered.NOTHING_TO_ANSWER)));
        } catch (KeyedReviewAct.Refused refused) {
            outcome = refused.outcome(Answered.class);
        }
        if (outcome == Answered.ANSWERED) {
            reaskReadiness(List.of(caseId));
        }
        return outcome;
    }

    /**
     * Re-asks each case's {@code IN_REVIEW → READY_FOR_DECISION} exit, each in its own transaction - the one move every return
     * to review owes (ADR-0155 decision 8). Harmless in every cause it loses: a task still open, a case no longer in review, a
     * KYB graph not yet terminal, a lost race.
     */
    void reaskReadiness(List<KycCaseId> returned) {
        for (KycCaseId caseId : returned) {
            units.inTransaction(unitOfWork ->
                    cases.moveToReadyForDecision(unitOfWork, caseId, KycCaseStatus.IN_REVIEW, Instant.now(clock)));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private Optional<KycCase> openCaseOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(Identity::partyId)
                .flatMap(party -> parties.findLiveCustomerFor(unitOfWork, PartyId.of(party)))
                .flatMap(customer -> cases.findOpenFor(unitOfWork, customer.id().value()));
    }

    private static CustomerRequestText screened(String requestText) {
        try {
            return CustomerRequestText.of(Objects.requireNonNull(requestText, "requestText must not be null"));
        } catch (CustomerRequestText.Refused refused) {
            // The rule is the log's and the reviewer's; the text is neither's to repeat.
            throw new ApiException(KycErrorCode.REQUEST_TEXT_REFUSED,
                    "a reviewer's request text was refused by the tipping-off rule " + refused.rule(),
                    refused.getMessage());
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException(
                        "a request for information must run inside a correlation scope: its records carry the flow's identifier"))
                .correlationId();
    }
}
