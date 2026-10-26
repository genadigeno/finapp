package com.finapp.app.risk;

import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.ChecksAssessment;
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
import java.util.EnumSet;
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
 *       is rescreened too - the review's MINOR 9: its onboarding screening may have been asked before the list came
 *       into force) - else {@link Inactive}; its latest KYC case - else {@link Pending} {@code NOT_SCREENED}; and the
 *       permit's keyed check, if the permit already asked one.</li>
 *   <li><strong>Asked</strong>, when the permit has not: {@link Door#rescreen} - kyc's
 *       {@code VerificationRunService.rescreen}: a {@code RESCREEN} check on that case under the permit's key, its
 *       dispatch committed, the provider asked holding no connection, the outcome with its evidence and trail - and no
 *       assessment: the case stays as it was, the customer untouched, no review task.</li>
 *   <li><strong>Answered</strong> from the keyed check alone - never from another question's: answered
 *       {@code CLEAR}/{@code HIT} is {@link Screened} (kyc's check id, whether it hit, when it was asked and decided -
 *       both on the database's clock, the one the list version's activation is stamped on); in flight is
 *       {@link Pending} {@code IN_FLIGHT} (another asker holds the call, or a crash left it dispatched; the next claim
 *       asks the same key and kyc answers from the row - never a second provider call); "we do not know"
 *       ({@code INDETERMINATE}) is a retry under the next key ordinal, at most
 *       {@link ChecksAssessment#INDETERMINATE_RETRY_BUDGET} keys - kyc's own budget - then {@link Pending}
 *       {@code NOT_SCREENED}, the permit failing at its bound, counted.</li>
 * </ol>
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
    public Answer ask(UUID partyId, ScreeningList list, String requestKey) {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(requestKey, "requestKey");
        if (!requestKey.startsWith(KEY_PREFIX)) {
            throw new IllegalArgumentException("a rescreen is asked under a permit's key");
        }
        Placed placed = transactions.inSnapshot(unitOfWork -> place(unitOfWork, partyId, list, requestKey));
        return switch (placed) {
            case Placed.Gone gone -> gone.answer();
            case Placed.Keyed keyed -> answerOf(keyed.check());
            case Placed.Unasked unasked -> answerOf(door.rescreen(unasked.caseId(), unasked.customerId(),
                    typeOf(list), unasked.rescreenKey()));
        };
    }

    /** Where the party stands, on {@code unitOfWork} - one snapshot, so the customer, its case and its checks agree. */
    Placed place(Connection unitOfWork, UUID partyId, ScreeningList list, String requestKey) {
        Optional<Customer> customer = parties.findLiveCustomerFor(unitOfWork, PartyId.of(partyId));
        if (customer.isEmpty() || !RESCREENED.contains(customer.get().status())) {
            return new Placed.Gone(new Inactive());
        }
        Optional<KycCase> latest = cases.findLatestFor(unitOfWork, customer.get().id().value());
        if (latest.isEmpty()) {
            return new Placed.Gone(new Pending(Wait.NOT_SCREENED));
        }
        Optional<VerificationCheck> last = Optional.empty();
        for (int ordinal = 0; ordinal < ChecksAssessment.INDETERMINATE_RETRY_BUDGET; ordinal++) {
            String key = keyed(requestKey, ordinal);
            Optional<VerificationCheck> asked = checks.findRescreen(unitOfWork, key);
            if (asked.isEmpty()) {
                return new Placed.Unasked(latest.get().id(), customer.get().id().value(), key);
            }
            if (asked.get().type() != typeOf(list)) {
                throw new IllegalStateException("a permit's key asked another list's screening");
            }
            last = asked;
            if (asked.get().status() != CheckStatus.INDETERMINATE) {
                return new Placed.Keyed(asked.get());
            }
        }
        // kyc's budget of "we do not know" spent under this permit: not screened.
        return new Placed.Keyed(last.orElseThrow());
    }

    /** kyc's answer from the permit's keyed check - never from another question's. */
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

        /** The permit's key already asked: its check answers. */
        record Keyed(VerificationCheck check) implements Placed {}

        /** The permit has not asked under {@code rescreenKey}: kyc is asked on that case. */
        record Unasked(KycCaseId caseId, UUID customerId, String rescreenKey) implements Placed {}
    }
}
