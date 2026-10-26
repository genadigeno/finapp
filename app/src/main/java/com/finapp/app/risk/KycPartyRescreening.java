package com.finapp.app.risk;

import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.party.Customer;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.PartyRescreening;
import com.finapp.risk.ScreeningList;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link PartyRescreening} through kyc's re-screening door (`P13-TSK-022`, the owner's decision O1 "add the kyc
 * door"; ADR-0107 decision 3; {@code PHASE_13_PLAN.md} section 12.10; {@code INV-RSK-02}; {@code INV-LIFE-04} as
 * amended) - <strong>risk asks, kyc screens</strong>, and now kyc can be asked again.
 *
 * <ol>
 *   <li><strong>Placed</strong>, on a read-only snapshot of its own (never risk's transaction - the leg asks holding no
 *       risk row): the party's live customer relationship, {@code ACTIVE} or {@code PENDING} (an onboarding customer
 *       is rescreened too - its onboarding screening may have been asked before the list came into force) - else
 *       {@link Inactive}; its latest KYC case - else, for a {@code PENDING} customer, {@link NotOnboarded} (never
 *       screened, nothing to rescreen: its onboarding screens it against the list in force - the review's IMPORTANT 4),
 *       and for an {@code ACTIVE} one {@link Pending} {@code NOT_SCREENED} (decided on nothing: the exposure, failing
 *       counted); and the questions the permit already asked.</li>
 *   <li><strong>Asked</strong>, when the claim may ask a new question: {@link Door#rescreen} - kyc's
 *       {@code VerificationRunService.rescreen}: a {@code RESCREEN} check on that case under the permit's key and the
 *       next ordinal, its dispatch committed, the provider asked holding no connection, the outcome with its evidence
 *       and trail - and, for a hit on a case not yet decided, kyc's own assessment (fail-closed); a decided case stays
 *       as it was, the customer untouched.</li>
 *   <li><strong>Answered</strong> from the permit's own questions alone - never from another's: a {@code HIT} on any of
 *       them wins (fail-closed) and is asked again under its own key - kyc answers from the row, no provider call, and
 *       re-runs its routing of the hit, which heals a crash between the hit's commit and that routing (the last
 *       review's item 2); a {@code CLEAR} answers only when no newer question is still its asker's (the last review's
 *       item 3 - an older question's late clear never concludes the permit over a live newer one). Either is
 *       {@link Screened} (kyc's check id, whether it hit, when it was asked and decided - both on the database's
 *       clock, the one the list version's activation is stamped on). Otherwise the newest question decides - in flight
 *       under its asker is {@link Pending} {@code IN_FLIGHT}; "we do not know" ({@code INDETERMINATE}), or in flight
 *       but asked before the question's abandonment line, is a new question under the next ordinal when the claim may
 *       ask one, else {@link Pending} ({@code NOT_SCREENED} or {@code IN_FLIGHT}) and the leg backs the permit off. A
 *       hit kyc records on one of the permit's questions after the permit concluded otherwise - a late callback for an
 *       abandoned question, a slow call that outlived its lease - is told to risk by {@code RescreeningHits}.</li>
 * </ol>
 *
 * <p><strong>The bound is the leg's</strong> (the review's IMPORTANT 2). A claim at attempt {@code n} may have asked the
 * ordinals {@code #0 .. #n-1}: one new question per claim while the last answered "we do not know" or was abandoned -
 * a provider outage is asked again at every claim of the leg's backoff, about 34.6 hours by default, and a restart that
 * killed a call mid-flight (a dispatched check no asker will ever complete) is abandoned once it is older than a lease
 * at the claim and asked again, never waited on forever. kyc's onboarding budget of three unknowns is the onboarding's,
 * not this.
 *
 * <p><strong>The key.</strong> The permit's request key ({@code risk.rescreen:<permit id>}) and an ordinal:
 * {@code risk.rescreen:<permit id>#0}, {@code #1}, ... - kyc's {@code verification_check_rescreen_once} makes one key
 * one question, so ten sweepers, a lease lost mid-call, or a replay meet one row. kyc's
 * {@code CounterpartyScreenings} screens a payee abroad by name, country and payee check - facts a party does not
 * have - and is not used.
 */
public final class KycPartyRescreening implements PartyRescreening {

    /** The customer relationships a list update reaches: decided and onboarding. */
    static final Set<CustomerStatus> RESCREENED = EnumSet.of(CustomerStatus.PENDING, CustomerStatus.ACTIVE);

    /**
     * kyc's door - {@code VerificationRunService::rescreen} where a provider is configured, {@link #UNAVAILABLE}
     * where none is (every ask then {@link Unavailable}, the permits backed off and failed at their bound, counted).
     */
    @FunctionalInterface
    public interface Door {

        /** No kyc provider is configured on this instance: nothing can be asked. */
        Door UNAVAILABLE = (caseId, customerId, type, rescreenKey) -> {
            throw new IllegalStateException("no kyc verification provider is configured: nothing can be rescreened");
        };

        /** One keyed {@code RESCREEN} check asked (or converged on) - kyc's row as it stands after the call. */
        VerificationCheck rescreen(KycCaseId caseId, UUID customerId, CheckType type, String rescreenKey);
    }

    private final PartyStore<Connection> parties;
    private final KycCaseStore<Connection> cases;
    private final CheckStore<Connection> checks;
    private final Door door;
    private final RiskTransactions transactions;

    public KycPartyRescreening(PartyStore<Connection> parties, KycCaseStore<Connection> cases,
            CheckStore<Connection> checks, Door door, RiskTransactions transactions) {
        this.parties = Objects.requireNonNull(parties, "parties");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.checks = Objects.requireNonNull(checks, "checks");
        this.door = Objects.requireNonNull(door, "door");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Answer ask(Question question) {
        Objects.requireNonNull(question, "question");
        if (!question.requestKey().startsWith(KEY_PREFIX)) {
            throw new IllegalArgumentException("a rescreen is asked under a permit's key");
        }
        Placed placed = transactions.inSnapshot(unitOfWork -> place(unitOfWork, question));
        return switch (placed) {
            case Placed.Gone gone -> gone.answer();
            case Placed.Keyed keyed -> answerOf(keyed.check());
            case Placed.Ask ask -> answerOf(door.rescreen(ask.caseId(), ask.customerId(), typeOf(question.list()),
                    ask.rescreenKey()));
        };
    }

    /** Where the party stands, on {@code unitOfWork} - one snapshot, so the customer, its case and its checks agree. */
    Placed place(Connection unitOfWork, Question question) {
        Optional<Customer> customer = parties.findLiveCustomerFor(unitOfWork, PartyId.of(question.partyId()));
        if (customer.isEmpty() || !RESCREENED.contains(customer.get().status())) {
            return new Placed.Gone(new Inactive());
        }
        Optional<KycCase> latest = cases.findLatestFor(unitOfWork, customer.get().id().value());
        if (latest.isEmpty()) {
            // Onboarding with no case: never screened, nothing to rescreen. Decided with no case: the exposure.
            return new Placed.Gone(customer.get().status() == CustomerStatus.PENDING ? new NotOnboarded()
                    : new Pending(Wait.NOT_SCREENED));
        }
        List<VerificationCheck> asked = askedBy(unitOfWork, question);
        UUID customerId = customer.get().id().value();
        for (int ordinal = 0; ordinal < asked.size(); ordinal++) {
            VerificationCheck check = asked.get(ordinal);
            if (check.status() == CheckStatus.HIT) {
                // A hit on ANY of the permit's questions wins (fail-closed) - and is asked again under its own key: kyc
                // answers from the row (no provider call) and re-runs its routing of the hit, so a crash between the
                // hit's commit and kyc's assessment of it is healed by the leg's next claim.
                return new Placed.Ask(check.caseId(), customerId, keyed(question.requestKey(), ordinal));
            }
        }
        int newestClear = -1;
        for (int ordinal = 0; ordinal < asked.size(); ordinal++) {
            if (asked.get(ordinal).status() == CheckStatus.CLEAR) {
                newestClear = ordinal;
            }
        }
        if (newestClear >= 0) {
            // A clear answers only when nothing newer is still its asker's: an older question's late clear never
            // concludes the permit while a newer question is live (a newer abandoned or unknown one may: its late hit,
            // if one comes, is told through RescreeningRun.hit).
            Optional<VerificationCheck> newerLive = asked.subList(newestClear + 1, asked.size()).stream()
                    .filter(check -> !check.status().isTerminal() && !abandoned(check, question.abandonedBefore()))
                    .findFirst();
            return new Placed.Keyed(newerLive.orElse(asked.get(newestClear)));
        }
        Placed.Ask next = new Placed.Ask(latest.get().id(), customerId, keyed(question.requestKey(), asked.size()));
        if (asked.isEmpty()) {
            return next;
        }
        VerificationCheck newest = asked.getLast();
        boolean unanswered = newest.status() == CheckStatus.INDETERMINATE
                || abandoned(newest, question.abandonedBefore());
        // One new question per claim, at most the claim's attempt: the leg's backoff and bound govern the asking.
        return unanswered && asked.size() < question.attempt() ? next : new Placed.Keyed(newest);
    }

    /**
     * The permit's questions in ordinal order - {@code #0} upward until the first never asked, at most the claim's
     * attempt (each claim asks at most one).
     */
    private List<VerificationCheck> askedBy(Connection unitOfWork, Question question) {
        List<VerificationCheck> asked = new ArrayList<>();
        for (int ordinal = 0; ordinal < question.attempt(); ordinal++) {
            Optional<VerificationCheck> check = checks.findRescreen(unitOfWork, keyed(question.requestKey(), ordinal));
            if (check.isEmpty()) {
                break;
            }
            if (check.get().type() != typeOf(question.list())) {
                throw new IllegalStateException("a permit's key asked another list's screening");
            }
            asked.add(check.get());
        }
        return asked;
    }

    /**
     * A question still unanswered that was asked before {@code abandonedBefore} - more than a lease before the claim,
     * both instants the database's ({@code kyc V011} stamps the ask with {@code clock_timestamp()}): its asker lost the
     * call, and no one will complete it.
     */
    static boolean abandoned(VerificationCheck check, Instant abandonedBefore) {
        return !check.status().isTerminal() && check.requestedAt().isBefore(abandonedBefore);
    }

    /** kyc's answer from one of the permit's keyed checks - never from another question's. */
    static Answer answerOf(VerificationCheck check) {
        Objects.requireNonNull(check, "check");
        if (check.onboarding()) {
            throw new IllegalStateException("a permit's key names a rescreen, never an onboarding check");
        }
        return switch (check.status()) {
            case CLEAR, HIT -> new Screened(check.id().value(), check.status() == CheckStatus.HIT, check.requestedAt(),
                    check.statusChangedAt());
            case REQUESTED, DISPATCHED -> new Pending(Wait.IN_FLIGHT);
            case INDETERMINATE -> new Pending(Wait.NOT_SCREENED);
        };
    }

    /** The {@code ordinal}-th key of a permit: its request key, {@code #}, the ordinal. */
    static String keyed(String requestKey, int ordinal) {
        return requestKey + "#" + ordinal;
    }

    /** The kyc screening a list is: a sanctions list is kyc's {@code SANCTIONS} check, a PEP list its {@code PEP}. */
    static CheckType typeOf(ScreeningList list) {
        return switch (Objects.requireNonNull(list, "list")) {
            case SANCTIONS -> CheckType.SANCTIONS;
            case PEP -> CheckType.PEP;
        };
    }

    /** What the snapshot found. */
    sealed interface Placed {

        /** Nothing to ask: the party's relationship is gone, or it has no case. */
        record Gone(Answer answer) implements Placed {}

        /** The permit's questions answer: one of its keyed checks. */
        record Keyed(VerificationCheck check) implements Placed {}

        /**
         * kyc is asked under {@code rescreenKey} on that case: a new question, or a hit's own key asked again - kyc answers
         * it from the row, never the provider, and re-runs its routing of the hit.
         */
        record Ask(KycCaseId caseId, UUID customerId, String rescreenKey) implements Placed {}
    }
}
