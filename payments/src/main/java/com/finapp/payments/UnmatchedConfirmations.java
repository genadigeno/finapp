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
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Parks a money-carrying confirmation that names no initiation the platform made
 * (`P7-TSK-009`, ADR-0062 §5, {@code INV-REC-05}): the scheme says value moved toward our
 * clearing position, the books must say where it rests, and guessing an owner is the one
 * thing this class exists to refuse.
 *
 * <h2>One parking per (rail, scheme reference), two arbiters</h2>
 *
 * <p>The posting keys {@code unmatched-confirmation:<rail>:<reference>} — a replay converges
 * on the original entry ({@code PostingService}'s contract) — and the row's
 * {@code UNIQUE (rail, scheme_reference)} converges the insert, so ten fresh-id deliveries
 * of one confirmation park once and post once, counted. Post THEN insert, deliberately: a
 * crash between them leaves the entry, and the redelivery replays it and lands the row —
 * never the row without the money fact it points to.
 *
 * <h2>The lines, and the alert</h2>
 *
 * <p>DR the rail's own clearing position ({@code INV-RAIL-04}: money arrived on that rail's
 * counterparty) / CR {@code SUSPENSE_UNMATCHED} — the seam account's first poster, exactly
 * as ADR-0059 §4 recorded it would arrive. The act is audited
 * ({@code UNMATCHED_CONFIRMATION_PARKED}: the platform moving money it cannot attribute is
 * a first-class auditable act) and logged loud; the age gauge is the standing alert.
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

    /** What one delivery did: parked by this call, or converged on the standing record. */
    public record Parked(boolean acting) {}

    /**
     * Parks the confirmation's value in suspense, at most once for its scheme reference.
     * Runs on the caller's connection, inside the delivery's transaction, as the platform.
     */
    public Parked park(
            Connection uow,
            RailId rail,
            ProviderReference schemeReference,
            Money amount,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);

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
        LedgerAccount clearing = chart.resolve(uow, clearingPurpose, amount.currency());
        LedgerAccount suspense =
                chart.resolve(uow, AccountPurpose.SUSPENSE_UNMATCHED, amount.currency());

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        PostingResult posted =
                postings.post(
                        uow,
                        new PostingCommand(
                                POSTING_KEY_PREFIX + rail.value() + ":"
                                        + schemeReference.value(),
                                today,
                                today,
                                schemeReference.value(),
                                List.of(
                                        new JournalLine(
                                                clearing.id(), Direction.DEBIT, amount),
                                        new JournalLine(
                                                suspense.id(), Direction.CREDIT, amount))));

        boolean acting =
                store.insert(
                        uow,
                        new UnmatchedConfirmation(
                                ids.next(),
                                rail,
                                schemeReference,
                                amount,
                                now,
                                posted.entryId().value()));
        if (!acting) {
            // The record stands from an earlier delivery; the posting above replayed the
            // same entry. Nothing to say twice.
            return new Parked(false);
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
                        correlation.correlationId(),
                        // Identifiers and the rail - never the amount (INV-AUD-02).
                        Optional.of(
                                "rail=" + rail.value()
                                        + ", entry=" + posted.entryId().value())));
        // The loud half of INV-REC-05's alerting; the parked-age gauge is the standing one.
        log.warn(
                "A pay-in confirmation on rail {} named no initiation this platform made and"
                        + " carried value; it is parked in SUSPENSE_UNMATCHED (entry {}) and"
                        + " awaits an operator - it will NEVER be credited by guesswork"
                        + " (ADR-0062 section 5, INV-REC-05)",
                rail.value(),
                posted.entryId().value());
        return new Parked(true);
    }
}
