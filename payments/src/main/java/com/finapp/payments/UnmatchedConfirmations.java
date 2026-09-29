package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Parks value the scheme says moved toward our clearing position when no initiation of ours
 * explains it (`P7-TSK-009`, ADR-0062 §5, {@code INV-REC-05}): the books must say where it
 * rests, and guessing an owner is the one thing this class exists to refuse.
 *
 * <h2>Three causes</h2>
 *
 * <p>A statement that named nothing we made ({@code UNATTRIBUTED}); one that named an attempt
 * already concluded — failed, or executed under another scheme reference
 * ({@code ATTEMPT_CONCLUDED}); and one that named a waiting attempt but executed an amount
 * other than the initiation's ask ({@code AMOUNT_MISMATCH}). The last two arrived with the
 * Phase 7 -&gt; 8 transition: the gate found both dropped at {@code INFO} or left unbooked —
 * money that moved, posted nowhere, parked nowhere, alerted nowhere.
 *
 * <h2>One money fact per scheme execution — the claim first</h2>
 *
 * <p>The parking claims its {@code (rail, scheme reference)} ({@link SchemeExecutionClaim})
 * <em>before</em> anything posts, and only the claim's winner posts, inserts, audits and
 * counts. A later delivery of the same execution — a fresh event id, a later day, a different
 * stated amount — converges on the standing parking without touching the ledger (the gate
 * found the old post-then-insert re-posting on every delivery with that day's date in the
 * fingerprint: a fresh-id duplicate on a later UTC day became a permanent poison delivery). An
 * execution already claimed by a pay-in, a withdrawal or a return is explained: nothing parks
 * (the gate found a withdrawal's own confirmation echoed to the pay-in door parked as INBOUND
 * money for value that went out, and a credited execution's unattributed duplicate parked
 * beside its credit).
 *
 * <h2>The lines, and the alert</h2>
 *
 * <p>DR the rail's own clearing position ({@code INV-RAIL-04}: money arrived on that rail's
 * counterparty) / CR {@code SUSPENSE_UNMATCHED} — the seam account's first poster, exactly as
 * ADR-0059 §4 recorded it would arrive. The act is audited
 * ({@code UNMATCHED_CONFIRMATION_PARKED}: the platform moving money it cannot attribute is a
 * first-class auditable act), counted after its commit through the {@link RailOutcomeObserver}
 * and logged loud; the age gauge is the standing alert.
 */
@Slf4j
@RequiredArgsConstructor
public final class UnmatchedConfirmations {

    static final String POSTING_KEY_PREFIX = "unmatched-confirmation:";

    @NonNull private final UnmatchedConfirmationStore<Connection> store;
    @NonNull private final PaymentRails rails;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final PostingService postings;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The one-money-fact arbiter (the Phase 7 -&gt; 8 transition). Appended last. */
    @NonNull private final SchemeExecutionClaimStore<Connection> claims;

    /** Where an acting parking is counted, after its commit. Appended last. */
    @NonNull private final RailOutcomeObserver observer;

    /**
     * The expectation-opening seam (`P8-TSK-005`, ADR-0067 §2): the parking's clearing line
     * opens its {@code UNMATCHED_CONFIRMATION} expectation in the parking's transaction, the
     * claim winner only - the scheme's report reaches the value as an expectation, the first
     * step in adopting Phase 7's suspense (the CREDIT suspense item and its break are
     * `P8-TSK-020`'s, through this same call). Appended last.
     */
    @NonNull private final SettlementExpectations expectations;

    /** One statement's value to park, and what it named. */
    public record Parking(
            RailId rail,
            ProviderReference schemeReference,
            Money amount,
            UnmatchedConfirmation.Attribution attribution,
            Correlation correlation) {

        public Parking {
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(schemeReference, "schemeReference must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(attribution, "attribution must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }

    /**
     * What one delivery did.
     *
     * @param acting whether THIS call parked
     * @param parking the parking the execution rests in — this call's or an earlier one's —
     *     or empty when a pay-in, a withdrawal or a return already explains the execution
     */
    public record Parked(boolean acting, Optional<UUID> parking) {

        public Parked {
            Objects.requireNonNull(parking, "parking must not be null");
        }
    }

    /**
     * Whether a statement on {@code rail} in {@code currency} can park at all (the Phase 7 -&gt; 8
     * transition): the rail declares a clearing position and the chart holds it and the
     * suspense account in that currency. A door asks first, so a statement it cannot park is
     * refused as unmappable with its evidence kept — never an exception inside the delivery
     * that rolls the evidence back and has the scheme redeliver for ever.
     */
    public boolean canPark(Connection uow, RailId rail, com.finapp.sharedkernel.money.CurrencyCode currency) {
        return rails.capabilitiesOf(rail)
                .clearingPurpose()
                .map(purpose ->
                        chart.find(uow, purpose, currency).isPresent()
                                && chart.find(uow, AccountPurpose.SUSPENSE_UNMATCHED, currency)
                                        .isPresent())
                .orElse(false);
    }

    /**
     * Parks the statement's value in suspense, at most once for its scheme execution. Runs on
     * the caller's connection, inside the caller's transaction, as the platform.
     */
    public Parked park(Connection uow, Parking parking) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);
        RailId rail = parking.rail();
        ProviderReference schemeReference = parking.schemeReference();
        Money amount = parking.amount();

        AccountPurpose clearingPurpose =
                rails.capabilitiesOf(rail)
                        .clearingPurpose()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "rail '" + rail.value() + "' declares no"
                                                        + " clearing position, so no"
                                                        + " confirmation of it can park"
                                                        + " (ADR-0059 section 4)"));

        UUID id = ids.next();
        SchemeExecutionClaim standing =
                claims.claim(
                        uow,
                        new SchemeExecutionClaim(
                                rail, schemeReference, SchemeExecutionClaim.Subject.UNMATCHED,
                                id, now));
        if (!standing.heldBy(SchemeExecutionClaim.Subject.UNMATCHED, id)) {
            return yielded(uow, parking, standing);
        }

        LedgerAccount clearing = chart.resolve(uow, clearingPurpose, amount.currency());
        LedgerAccount suspense =
                chart.resolve(uow, AccountPurpose.SUSPENSE_UNMATCHED, amount.currency());
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        // The operation the posting key names: the scheme execution on its rail.
        String execution = rail.value() + ":" + schemeReference.value();
        // Only the claim's winner reaches this posting: a later delivery never re-posts, so
        // its date can never disagree with the entry's (the gate's poison-delivery find).
        PostingResult posted =
                postings.post(
                        uow,
                        new PostingCommand(
                                POSTING_KEY_PREFIX + execution,
                                today,
                                today,
                                schemeReference.value(),
                                List.of(
                                        new JournalLine(
                                                clearing.id(), Direction.DEBIT, amount),
                                        new JournalLine(
                                                suspense.id(), Direction.CREDIT, amount))));

        UnmatchedConfirmation.Attribution attribution = parking.attribution();
        // THE EXPECTATION (P8-TSK-005, ADR-0067 §2, §5): the clearing line's copy, keyed by
        // the scheme's reference alone - the parking's named reference, cause and attempt are
        // NOT keys (a named reference may be an attempt's own end-to-end reference, which that
        // attempt's expectation already keys); they travel to -020's suspense item instead.
        // The cycle the parking stored is the expectation's attribute.
        expectations.open(
                uow,
                new SettlementExpectations.Opening(
                        SettlementExpectations.Kind.UNMATCHED_CONFIRMATION,
                        execution,
                        POSTING_KEY_PREFIX + execution,
                        clearingPurpose,
                        clearing.id(),
                        posted.entryId(),
                        attribution.settlementCycle(),
                        List.of(
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.SCHEME_REF,
                                        schemeReference.value())),
                        parking.correlation()));
        if (!store.insert(
                uow,
                new UnmatchedConfirmation(
                        id,
                        rail,
                        schemeReference,
                        amount,
                        now,
                        posted.entryId().value(),
                        attribution))) {
            throw new IllegalStateException(
                    "a won scheme-execution claim met a standing parking on " + rail.value()
                            + ": every parking claims before it inserts (V023)");
        }

        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        platform,
                        now,
                        PaymentsAuditAction.UNMATCHED_CONFIRMATION_PARKED,
                        "unmatched_confirmation",
                        schemeReference.value(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        parking.correlation().correlationId(),
                        // Identifiers, the rail and the cause - never the amount (INV-AUD-02).
                        Optional.of(
                                "rail=" + rail.value()
                                        + ", entry=" + posted.entryId().value()
                                        + ", cause=" + attribution.cause()
                                        + attribution.attempt()
                                                .map(attempt -> ", attempt=" + attempt.value())
                                                .orElse(""))));
        observer.unmatchedParked(rail);
        // The loud half of INV-REC-05's alerting; the parked-age gauge is the standing one.
        log.warn(
                "A confirmation on rail {} carried value no initiation of ours explains ({}{});"
                        + " it is parked in SUSPENSE_UNMATCHED (entry {}) and awaits an"
                        + " operator - it will NEVER be credited by guesswork (ADR-0062"
                        + " section 5, INV-REC-05)",
                rail.value(),
                attribution.cause(),
                attribution.attempt().map(attempt -> ", attempt " + attempt.value()).orElse(""),
                posted.entryId().value());
        return new Parked(true, Optional.of(id));
    }

    /** The claim stood with another subject: converge on it, or yield to what explains it. */
    private Parked yielded(Connection uow, Parking parking, SchemeExecutionClaim standing) {
        if (standing.subject() == SchemeExecutionClaim.Subject.UNMATCHED) {
            // An earlier delivery of the same execution parked it; nothing is said twice.
            boolean sameAmount =
                    store.findByReference(uow, parking.rail(), parking.schemeReference())
                            .map(parked -> parked.amount().equals(parking.amount()))
                            .orElse(true);
            if (!sameAmount) {
                log.warn(
                        "A repeated confirmation of parked execution {} on rail {} stated a"
                                + " different amount; the first parking stands and this"
                                + " statement rests as evidence - a contradiction"
                                + " reconciliation must see",
                        standing.subjectId(),
                        parking.rail().value());
            }
            return new Parked(false, Optional.of(standing.subjectId()));
        }
        // A pay-in, a withdrawal or a return already explains this execution: parking it too
        // would count one arrival twice (a credit) or book outbound value as inbound (a
        // withdrawal's or a return's echo). Loud, because a scheme restating an execution
        // without our reference is worth an operator's eye - but nothing moves.
        log.warn(
                "A confirmation on rail {} carrying value named a scheme execution already"
                        + " explained by {} {}; nothing parks and the statement rests as"
                        + " evidence (one scheme execution, one money fact - V023)",
                parking.rail().value(),
                standing.subject(),
                standing.subjectId());
        return new Parked(false, Optional.empty());
    }
}
