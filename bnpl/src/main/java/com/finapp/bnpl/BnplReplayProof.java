package com.finapp.bnpl;

import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.AccrualTerms;
import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.DueItemId;
import com.finapp.creditmath.DueState;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.creditmath.LendingEngine;
import com.finapp.creditmath.OverpaymentTreatment;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The replay proof (`P12-TSK-025`; {@code PHASE_12_PLAN.md} sections 12.2, 12.6 and 12.12 point 4; ADR-0102, ADR-0105;
 * lending's {@code LoanReplayProof}; {@code INV-BNPL-05}, {@code INV-BNPL-06}, {@code INV-BNPL-09}, {@code INV-LND-03},
 * {@code -05} and {@code -09} by analogy): one BNPL obligation re-derived from its {@link BnplRecord} by the engines each
 * row pinned ({@link BnplReplayer}) - {@code IDENTICAL} when every figure re-derives to the row's, {@code DIVERGED} with
 * each disagreeing fact named (by kind and id, never an amount) otherwise.
 *
 * <h2>What is re-derived, from what</h2>
 *
 * <ul>
 *   <li><strong>Every agreement version</strong> - version 1's columns held to its pinned terms version and to the offer it
 *       copied, its canonical terms re-rendered from the offer's rows and the pinned terms ({@link BnplOfferTerms}) and
 *       their {@code terms_sha256} re-verified, the offer document's hash too; a later version an exact copy of its
 *       predecessor made by a refund of the record; every engine it pins held by this build.
 *   <li><strong>Every schedule version</strong> - version 1 projected by its pinned engine over the gross from the purchase's
 *       business date; version n+1 re-derived at the refund that made it ({@link BnplRefunds#nextSchedule}).
 *   <li><strong>Then the history in the lock's order</strong> - the rows ordered by the database instant each was written
 *       at under the obligation's row lock (B4): every accrual (its period of the schedule in force, its base from the
 *       journal lines valued on or before its date and written before it, its cumulative and posted figures by the pinned
 *       accrual engine; no elapsed date with principal outstanding missing), every billing (the period's posted accruals,
 *       {@code least(not yet due, amount - interest)}, the last the remainder; its entry valued its due date or a payoff's
 *       date), every credit-balance application and every repayment's allocation (the pinned allocation engine over the due
 *       state the rows and the ledger held just before it - a collection's without the instalments its date's dispute hold
 *       covers), every refund (the position, {@link RefundAllocationEngineV1}, the releases, the adjustments, the schedule
 *       it made), every late fee (the pinned fee within the cap, on its instalment's own day), the payoff (its quote's
 *       balances, every fact dated its execution's value date - `-022`'s hand-off), every approved reversal (an exact
 *       negation) and waiver (within its room) and the closure's refund.
 * </ul>
 *
 * <h2>The two arms the Phase 11 gate taught</h2>
 *
 * <ul>
 *   <li><strong>R1's arm</strong> ({@link Check#VALUE_DATE}, {@link Check#ACCRUAL}): no principal fact - a repayment, an
 *       application, a refund, a reversal - valued before the obligation's floor as it stood when the fact was written
 *       ({@code bnpl.principal_value_floor}'s rule replayed from the rows); and every accrual's base is still the final
 *       ledger's principal valued through its date, so a principal line dated back into an accrued date is found.
 *   <li><strong>RG-M5</strong> ({@link Check#CATCH_UP_MISSING}): held credit standing beside a billed item still due at a
 *       servicing STEP's end - judged where a step's later slots write (its collection attempts, its condition changes,
 *       its late fees), after the step's catch-up application: never between steps, where a reversal may legitimately
 *       leave credit beside a re-opened item until the next one (`-024`'s hand-off). And lending's
 *       {@link Check#APPLICATION_MISSING} at each billing.
 * </ul>
 *
 * <p>The ledger "as of" a fact is every line of the entries a row named before it - plus any line no row names, which the
 * subledger proof fails on its own. Pure: a function of the record - the same verdict on every instance and in every JVM,
 * whatever commits after the snapshot. Nothing is repaired: a {@code DIVERGED} is an alert. A database clock stepped back
 * between two facts of one obligation could order them wrongly - a false {@code DIVERGED}, never a hidden one (recorded,
 * `P12-TSK-025`).
 */
public final class BnplReplayProof {

    /** The replay's verdict. */
    public enum Verdict {
        IDENTICAL,
        DIVERGED
    }

    /** The kind of fact that failed to re-derive. */
    public enum Check {
        /** A row pins an engine version this build does not hold. */
        ENGINE_ABSENT,
        /** A pinned terms version, the offer or its terms cannot be read. */
        TERMS_UNREADABLE,
        /** An agreement version's columns disagree with its pinned terms, its offer, or its predecessor. */
        AGREEMENT,
        /** An agreement version's canonical terms do not re-render to their stored hash. */
        TERMS,
        /** A schedule version does not re-derive. */
        SCHEDULE,
        /** An accrual does not re-derive - or its base is no longer the final ledger's principal through its date (R1). */
        ACCRUAL,
        /** An elapsed date with principal outstanding has no accrual. */
        ACCRUAL_MISSING,
        /** A billing does not re-derive. */
        BILLING,
        /** A credit-balance application does not re-derive. */
        APPLICATION,
        /** A billed item met a credit balance and nothing was applied at its billing. */
        APPLICATION_MISSING,
        /** Held credit stood beside a billed item still due at a servicing step's end (RG-M5). */
        CATCH_UP_MISSING,
        /** A repayment's allocation does not re-derive. */
        ALLOCATION,
        /** A principal fact is valued before its floor, or a payoff fact away from the payoff's date (R1, RG-M1). */
        VALUE_DATE,
        /** A refund does not re-derive. */
        REFUND,
        /** A late fee does not re-derive. */
        FEE,
        /** The payoff's quote or execution does not re-derive. */
        PAYOFF,
        /** A reversal does not negate its repayment exactly. */
        REVERSAL,
        /** A waiver exceeds what its fee still had to forgive. */
        WAIVER,
        /** The closure's refund is not the credit balance held, or a receivable stood. */
        CLOSURE
    }

    /** One fact that failed to re-derive: its kind and its row - never a figure ({@code INV-AUD-02}). */
    public record Finding(Check check, UUID subject) {

        public Finding {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(subject, "subject");
        }
    }

    /** One obligation's replay: the verdict, what diverged, and how many facts were re-derived. */
    public record Replay(BnplObligationId obligation, Verdict verdict, List<Finding> findings, int replayed) {

        public Replay {
            Objects.requireNonNull(obligation, "obligation");
            Objects.requireNonNull(verdict, "verdict");
            findings = List.copyOf(findings);
            if ((verdict == Verdict.IDENTICAL) != findings.isEmpty()) {
                throw new IllegalArgumentException("a replay is IDENTICAL exactly when nothing diverged");
            }
        }

        /** The kinds that diverged. */
        public Set<Check> checks() {
            Set<Check> checks = new java.util.TreeSet<>();
            findings.forEach(finding -> checks.add(finding.check()));
            return checks;
        }

        @Override
        public String toString() {
            return "Replay[" + obligation.value() + ", " + verdict + (findings.isEmpty() ? "" : " " + checks()) + "]";
        }
    }

    /** Replays {@code record}. */
    public Replay replay(BnplRecord record) {
        Objects.requireNonNull(record, "record");
        Walk walk = new Walk(record);
        walk.agreements();
        walk.firstSchedule();
        walk.history();
        walk.accrualGaps();
        walk.refundMadeEveryLaterVersion();
        List<Finding> findings = List.copyOf(walk.findings.values());
        return new Replay(record.id(), findings.isEmpty() ? Verdict.IDENTICAL : Verdict.DIVERGED, findings, walk.replayed);
    }

    // ------------------------------------------------------------------ the walk

    /** One replay's state: the record, the findings, and the due state rebuilt as the history is walked. */
    private static final class Walk {

        private final BnplRecord record;
        private final Money zero;
        private final Map<String, Finding> findings = new LinkedHashMap<>();
        private int replayed;

        private final Map<JournalEntryId, BnplSubledgerProof.Namer> named;
        private final Map<AccountPurpose, LedgerAccountId> accounts = new EnumMap<>(AccountPurpose.class);
        private final Map<LedgerAccountId, List<PurposeBalances.DatedLine>> linesByAccount = new HashMap<>();
        private final Map<JournalEntryId, List<PurposeBalances.DatedLine>> linesByEntry = new HashMap<>();

        // The state rebuilt in the lock's order.
        private final Map<UUID, Item> items = new LinkedHashMap<>();
        private final Map<UUID, BnplPurchaseStore.Allocation> allocations = new HashMap<>();
        private final Set<UUID> remadeSchedules = new HashSet<>();
        private final Set<Integer> remadeAgreements = new HashSet<>();
        private final List<BnplRefund.Adjustment> adjustments = new ArrayList<>();
        private final Set<UUID> charged = new HashSet<>();
        private final Map<UUID, Money> waivedOf = new HashMap<>();
        private final Map<UUID, Money> releasedOn = new HashMap<>();
        private BnplAgreement governing;
        private BnplPurchaseStore.StoredSchedule schedule;
        private Optional<BnplInterestAccrual> lastAccrual = Optional.empty();
        private Money refundedBefore;
        private Money feeReturnedBefore;
        private Money feesAssessed;
        private boolean closed;

        Walk(BnplRecord record) {
            this.record = record;
            this.zero = record.zero();
            this.refundedBefore = zero;
            this.feeReturnedBefore = zero;
            this.feesAssessed = zero;
            this.named = BnplSubledgerProof.namedEntries(record);
            for (AccountPurpose purpose : BnplSubledgerProof.PURPOSES) {
                record.account(purpose).ifPresent(account -> accounts.put(purpose, account.id()));
            }
            record.journalLines().forEach(line -> {
                linesByAccount.computeIfAbsent(line.account(), key -> new ArrayList<>()).add(line);
                linesByEntry.computeIfAbsent(line.entry(), key -> new ArrayList<>()).add(line);
            });
            record.repayments().forEach(repayment -> repayment.row().allocations()
                    .forEach(line -> allocations.put(line.id(), line)));
        }

        private void diverged(Check check, UUID subject) {
            findings.putIfAbsent(check + "/" + subject, new Finding(check, subject));
        }

        // -------------------------------------------------------------- agreements

        void agreements() {
            BnplAgreement previous = null;
            List<BnplRecord.Agreement> versions = record.agreements();
            for (int i = 0; i < versions.size(); i++) {
                BnplRecord.Agreement version = versions.get(i);
                BnplAgreement row = version.row();
                replayed++;
                UUID subject = row.id().value();
                if (row.version() != i + 1 || !row.obligation().equals(record.id())) {
                    diverged(Check.AGREEMENT, subject);
                }
                if (!BnplReplayer.holdsEvery(row)) {
                    diverged(Check.ENGINE_ABSENT, subject);
                }
                if (!MessageDigest.isEqual(BnplOfferTerms.sha256(row.canonicalTerms()), row.termsSha256())) {
                    // The stored text and its stored hash disagree: neither is what the customer accepted.
                    diverged(Check.TERMS, subject);
                }
                if (row.version() == 1) {
                    versionOne(row);
                } else if (previous == null || !copies(row, previous) || version.refund().isEmpty()
                        || record.refunds().stream().noneMatch(refund -> refund.row().id().equals(version.refund().get())
                                && refund.row().agreementVersion() == row.version())) {
                    diverged(Check.AGREEMENT, subject);
                }
                previous = row;
            }
            if (versions.isEmpty() || !versions.get(0).row().id().equals(record.obligation().agreement())) {
                diverged(Check.AGREEMENT, record.obligation().agreement().value());
            }
        }

        /** Version 1: the offer copied, its columns the pinned terms', its canonical terms re-rendered and re-verified. */
        private void versionOne(BnplAgreement row) {
            UUID subject = row.id().value();
            Optional<BnplTermsVersion> version = record.termsVersions().getOrDefault(row.termsVersionId(),
                    Optional.empty());
            if (version.isEmpty() || record.offer().isEmpty() || record.offerTerms().isEmpty()) {
                diverged(Check.TERMS_UNREADABLE, subject);
                return;
            }
            BnplTerms pinned = version.get().terms();
            BnplOffer offer = record.offer().get();
            BnplOfferStore.StoredTerms stored = record.offerTerms().get();
            MerchantFeeSplit split = record.financing().split();
            boolean facts = row.product() == pinned.product() && row.plan() == offer.plan()
                    && row.offer().equals(offer.id()) && row.decision().equals(offer.decision())
                    && row.termsVersionId().equals(offer.termsVersionId())
                    && record.purchase().offer().equals(offer.id())
                    && row.scheduleEngine().equals(stored.scheduleEngine())
                    && row.accrualEngine().equals(pinned.payInN().map(own -> own.accrualEngine().name()))
                    && row.allocationEngine().equals(pinned.allocationEngine().name())
                    && row.refundAllocationEngine().equals(pinned.refundAllocationEngine().name())
                    && row.template().equals(pinned.agreementTemplate())
                    && row.feeScheduleVersion().equals(split.feeScheduleVersionId())
                    && stored.amount().equals(split.gross())
                    && row.canonicalFormat() == stored.canonicalFormat();
            if (!facts) {
                diverged(Check.AGREEMENT, subject);
            }
            try {
                BnplIllustration illustration = BnplReplayer.project(stored.scheduleEngine(), pinned, offer.plan(),
                        stored.amount(), stored.illustrativeStart());
                String rendered = BnplOfferTerms.render(new BnplOfferTerms.Content(offer.id(), offer.application(),
                        offer.decision(), offer.termsVersionId(), version.get().row().version(), pinned, offer.plan(),
                        illustration));
                byte[] hash = BnplOfferTerms.sha256(rendered);
                byte[] document = BnplOfferTerms.sha256(BnplOfferDocument.render(offer.id(), pinned, offer.plan(),
                        illustration, HexFormat.of().formatHex(hash)));
                // The re-verification: the terms as the rows re-render them hash to what the agreement and the offer store.
                if (!rendered.equals(row.canonicalTerms()) || !MessageDigest.isEqual(hash, row.termsSha256())
                        || !MessageDigest.isEqual(hash, stored.termsSha256())
                        || !MessageDigest.isEqual(document, row.documentSha256())) {
                    diverged(Check.TERMS, subject);
                }
            } catch (BnplReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, subject);
            } catch (RuntimeException unrenderable) {
                diverged(Check.TERMS, subject);
            }
        }

        /** Version n+1 is version n's terms, engines, pins and template exactly (INV-HIST-04). */
        private static boolean copies(BnplAgreement row, BnplAgreement previous) {
            return row.id().equals(previous.id()) && row.obligation().equals(previous.obligation())
                    && row.offer().equals(previous.offer()) && row.decision().equals(previous.decision())
                    && row.termsVersionId().equals(previous.termsVersionId()) && row.product() == previous.product()
                    && row.plan() == previous.plan() && row.scheduleEngine().equals(previous.scheduleEngine())
                    && row.accrualEngine().equals(previous.accrualEngine())
                    && row.allocationEngine().equals(previous.allocationEngine())
                    && row.refundAllocationEngine().equals(previous.refundAllocationEngine())
                    && row.feeScheduleVersion().equals(previous.feeScheduleVersion())
                    && row.template().equals(previous.template()) && row.canonicalFormat() == previous.canonicalFormat()
                    && row.canonicalTerms().equals(previous.canonicalTerms())
                    && MessageDigest.isEqual(row.termsSha256(), previous.termsSha256())
                    && MessageDigest.isEqual(row.documentSha256(), previous.documentSha256());
        }

        // -------------------------------------------------------------- schedule version 1

        void firstSchedule() {
            List<BnplRecord.Schedule> versions = record.schedules();
            for (int i = 0; i < versions.size(); i++) {
                BnplPurchaseStore.StoredSchedule row = versions.get(i).row();
                if (row.version() != i + 1 || !row.obligation().equals(record.id())
                        || (row.version() == 1) != versions.get(i).refund().isEmpty()) {
                    diverged(Check.SCHEDULE, row.id());
                }
            }
            Optional<BnplRecord.Schedule> first = versions.stream().filter(found -> found.row().version() == 1)
                    .findFirst();
            Optional<BnplAgreement> agreement = record.agreements().stream().map(BnplRecord.Agreement::row)
                    .filter(found -> found.version() == 1).findFirst();
            if (first.isEmpty() || agreement.isEmpty()) {
                diverged(Check.SCHEDULE, first.map(found -> found.row().id()).orElse(record.id().value()));
                return;
            }
            replayed++;
            BnplPurchaseStore.StoredSchedule row = first.get().row();
            Optional<BnplTerms> pinned = pinned(agreement.get());
            if (pinned.isEmpty()) {
                diverged(Check.TERMS_UNREADABLE, row.id());
                return;
            }
            try {
                Money gross = record.financing().split().gross();
                BnplIllustration projected = BnplReplayer.project(row.engine(), pinned.get(),
                        record.obligation().plan(), gross, record.obligation().purchaseDate());
                boolean same = row.engine().equals(agreement.get().scheduleEngine()) && row.principal().equals(gross)
                        && row.start().equals(record.obligation().purchaseDate())
                        && row.instalments().size() == projected.lines().size();
                for (int i = 0; same && i < row.instalments().size(); i++) {
                    BnplPurchaseStore.StoredInstalment stored = row.instalments().get(i);
                    BnplIllustration.Line line = projected.lines().get(i);
                    same = stored.sequence() == line.sequence() && stored.dueDate().equals(line.dueDate())
                            && stored.principal().equals(line.principal()) && stored.interest().equals(line.interest())
                            && stored.amount().equals(line.amount());
                }
                if (!same) {
                    diverged(Check.SCHEDULE, row.id());
                }
            } catch (BnplReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, row.id());
            } catch (RuntimeException refused) {
                diverged(Check.SCHEDULE, row.id());
            }
        }

        /** Every later schedule version was re-derived at the refund that made it - one no refund made is a finding. */
        void refundMadeEveryLaterVersion() {
            for (BnplRecord.Schedule version : record.schedules()) {
                if (version.row().version() > 1 && !remadeSchedules.contains(version.row().id())) {
                    diverged(Check.SCHEDULE, version.row().id());
                }
            }
            for (BnplRecord.Agreement version : record.agreements()) {
                if (version.row().version() > 1 && !remadeAgreements.contains(version.row().version())) {
                    diverged(Check.AGREEMENT, version.row().id().value());
                }
            }
        }

        // -------------------------------------------------------------- the history

        /** One fact in the lock's order. */
        private record Event(Instant at, int rank, UUID id, Object fact) {}

        /** A collection attempt, a condition change: a servicing step's later slot. */
        private record StepSlot(UUID id, Object row) {}

        void history() {
            List<Event> events = new ArrayList<>();
            // A refund's schedule and agreement versions are applied by the refund (they were inserted in its transaction,
            // before its row); version 1 of each opens the history.
            record.agreements().stream().filter(version -> version.row().version() == 1).forEach(version ->
                    events.add(new Event(version.row().createdAt(), 0, version.row().id().value(), version.row())));
            record.schedules().stream().filter(version -> version.row().version() == 1).forEach(version ->
                    events.add(new Event(version.at(), 1, version.row().id(), version.row())));
            record.accruals().forEach(row -> events.add(new Event(row.at(), 2, row.row().id(), row.row())));
            record.billings().forEach(row -> events.add(new Event(row.at(), 3, row.row().id(), row)));
            record.applications().forEach(row -> events.add(new Event(row.at(), 4, row.row().id(), row.row())));
            record.repayments().forEach(row -> events.add(new Event(row.at(), 5, row.row().id(), row.row())));
            record.attempts().forEach(row -> events.add(new Event(row.at(), 6, row.row().id(),
                    new StepSlot(row.row().id(), row.row()))));
            record.refunds().forEach(row -> events.add(new Event(row.at(), 7, row.row().id(), row)));
            record.conditions().forEach(row -> events.add(new Event(row.at(), 8, row.row().id(),
                    new StepSlot(row.row().id(), row.row()))));
            record.fees().forEach(row -> events.add(new Event(row.at(), 9, row.row().id(), row)));
            record.quotes().forEach(row -> events.add(new Event(row.at(), 10, row.row().id(), row)));
            record.execution().ifPresent(row -> events.add(new Event(row.at(), 11, row.row().id(), row.row())));
            record.reversals().stream().filter(reversal -> reversal.row().status() == BnplCorrectionStatus.APPROVED)
                    .forEach(reversal -> events.add(new Event(reversal.row().decidedAt().orElse(reversal.row().proposedAt()),
                            12, reversal.row().id(), reversal)));
            record.waivers().stream().filter(waiver -> waiver.status() == BnplCorrectionStatus.APPROVED)
                    .forEach(waiver -> events.add(new Event(waiver.decidedAt().orElse(waiver.proposedAt()), 13,
                            waiver.id(), waiver)));
            record.closure().ifPresent(row -> events.add(new Event(row.at(), 14, row.row().id(), row)));
            events.sort(Comparator.comparing(Event::at).thenComparingInt(Event::rank).thenComparing(Event::id));

            for (Event event : events) {
                switch (event.fact()) {
                    case BnplAgreement agreement -> governing = agreement;
                    case BnplPurchaseStore.StoredSchedule fixed -> schedule = fixed;
                    case BnplInterestAccrual accrual -> accrual(accrual, event.at());
                    case BnplRecord.Billing billing -> billing(billing);
                    case BnplCreditBalanceApplication application -> application(application, event.at());
                    case BnplPurchaseStore.Repayment repayment -> repayment(repayment, event.at());
                    case StepSlot slot -> stepSlot(slot.id(), event.at());
                    case BnplRecord.Stamped<?> stamped when stamped.row() instanceof BnplRefund refund ->
                            refund(refund, stamped.at());
                    case BnplRecord.Stamped<?> stamped when stamped.row() instanceof BnplFeeAssessment fee ->
                            fee(fee, stamped.at());
                    case BnplRecord.Stamped<?> stamped when stamped.row() instanceof BnplPayoffQuote quote ->
                            quote(quote, stamped.at());
                    case BnplRecord.Stamped<?> stamped when stamped.row() instanceof BnplClosure closure ->
                            closure(closure, stamped.at());
                    case BnplPayoffExecution execution -> execution(execution, event.at());
                    case BnplRecord.Reversal reversal -> reversal(reversal);
                    case BnplWaiver waiver -> waiver(waiver);
                    default -> throw new IllegalStateException("an unknown fact in the history");
                }
            }
        }

        // -------------------------------------------------------------- accruals

        private void accrual(BnplInterestAccrual row, Instant at) {
            replayed++;
            UUID subject = row.id();
            InterestAccrual stored = row.accrual();
            try {
                if (governing == null || schedule == null || !governing.id().equals(row.agreement())
                        || governing.version() != row.agreementVersion()
                        || governing.accrualEngine().filter(stored.engine().name()::equals).isEmpty()
                        || row.entry().isPresent() != stored.posted().isPositive()) {
                    diverged(Check.ACCRUAL, subject);
                    return;
                }
                EngineVersion engine = BnplReplayer.shared(LendingEngine.ACCRUAL, stored.engine().name());
                BnplTerms pinned = pinned(governing).orElse(null);
                if (pinned == null || pinned.payInN().isEmpty()) {
                    diverged(Check.TERMS_UNREADABLE, subject);
                    return;
                }
                BnplTerms.PayInNTerms own = pinned.payInN().get();
                AccrualTerms terms = new AccrualTerms(record.obligation().currency(), pinned.nominalAnnualRate(),
                        own.dayCount(), own.interestRounding(), engine);
                AccrualPeriod period = periodOf(stored.date());
                Optional<InterestAccrual> previous = lastAccrual.map(BnplInterestAccrual::accrual)
                        .filter(prior -> prior.period().equals(period));
                Money base = base(stored.date(), Optional.of(at));
                InterestAccrual derived = BnplReplayer.accrue(terms, period, previous, stored.date(), base);
                if (!period.equals(stored.period()) || !derived.base().equals(stored.base())
                        || !derived.posted().equals(stored.posted())
                        || derived.cumulativeExact().compareTo(stored.cumulativeExact()) != 0
                        || row.entry().filter(entry -> !valuedOn(entry, stored.date())).isPresent()) {
                    diverged(Check.ACCRUAL, subject);
                } else if (!base(stored.date(), Optional.empty()).equals(stored.base())) {
                    // R1's arm: the base the step read is still the ledger's - no principal line valued on or before the
                    // date was written after it.
                    diverged(Check.ACCRUAL, subject);
                }
            } catch (BnplReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, subject);
            } catch (RuntimeException refused) {
                diverged(Check.ACCRUAL, subject);
            } finally {
                lastAccrual = Optional.of(row);
            }
        }

        /** The period of the schedule in force that holds {@code date}. */
        private AccrualPeriod periodOf(LocalDate date) {
            for (AccrualPeriod period : BnplAccruals.periodsOf(schedule)) {
                if (period.contains(date)) {
                    return period;
                }
            }
            throw new IllegalStateException("an accrued date lies in an instalment period of the schedule in force");
        }

        /**
         * Principal outstanding at the end of {@code date}: {@code BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE} over the lines
         * valued on or before it, of the entries written before {@code before} - or, with none, of every entry.
         */
        private Money base(LocalDate date, Optional<Instant> before) {
            Money base = zero;
            for (AccountPurpose purpose : List.of(AccountPurpose.BNPL_PRINCIPAL, AccountPurpose.BNPL_PRINCIPAL_DUE)) {
                for (PurposeBalances.DatedLine line : linesOf(purpose)) {
                    if (!line.valueDate().isAfter(date) && before.map(at -> writtenBefore(line.entry(), at)).orElse(true)) {
                        base = line.direction() == Direction.DEBIT ? base.plus(line.amount()) : base.minus(line.amount());
                    }
                }
            }
            return base;
        }

        /** Every elapsed date from the purchase to each accrual with principal outstanding has its row. */
        void accrualGaps() {
            if (record.accruals().isEmpty()) {
                return;
            }
            LocalDate expected = record.obligation().purchaseDate();
            for (BnplRecord.Stamped<BnplInterestAccrual> accrual : record.accruals().stream()
                    .sorted(Comparator.comparing(found -> found.row().accrual().date())).toList()) {
                for (LocalDate date = expected; date.isBefore(accrual.row().accrual().date()); date = date.plusDays(1)) {
                    if (base(date, Optional.of(accrual.at())).isPositive()) {
                        diverged(Check.ACCRUAL_MISSING, accrual.row().id());
                        break;
                    }
                }
                expected = accrual.row().accrual().date().plusDays(1);
            }
        }

        // -------------------------------------------------------------- billings

        private void billing(BnplRecord.Billing stamped) {
            replayed++;
            BnplBilling billing = stamped.row();
            UUID subject = billing.id();
            Optional<BnplRecord.Stamped<BnplPayoffExecution>> payoff = stamped.payoffExecution().flatMap(id ->
                    record.execution().filter(execution -> execution.row().id().equals(id)));
            try {
                Optional<BnplPurchaseStore.StoredInstalment> instalment = schedule == null ? Optional.empty()
                        : schedule.instalments().stream().filter(row -> row.id().equals(billing.instalment())).findFirst();
                if (governing == null || instalment.isEmpty() || !schedule.id().equals(billing.scheduleId())
                        || schedule.version() != billing.scheduleVersion()
                        || !schedule.engine().equals(billing.scheduleEngine())
                        || !governing.id().equals(billing.agreement()) || governing.version() != billing.agreementVersion()
                        || !governing.accrualEngine().equals(billing.accrualEngine())
                        || instalment.get().sequence() != billing.sequence()
                        || !instalment.get().dueDate().equals(billing.dueDate())
                        || stamped.payoffExecution().isPresent() != payoff.isPresent()
                        || (billing.dueDate().isAfter(stamped.businessDate()) && payoff.isEmpty())) {
                    diverged(Check.BILLING, subject);
                } else {
                    LocalDate periodStart = BnplServicing.periodStart(schedule, instalment.get());
                    Money interest = zero;
                    if (governing.accrualEngine().isPresent()) {
                        for (BnplRecord.Stamped<BnplInterestAccrual> accrual : record.accruals()) {
                            LocalDate date = accrual.row().accrual().date();
                            if (!date.isBefore(periodStart) && date.isBefore(billing.dueDate())
                                    && accrual.at().isBefore(stamped.at())) {
                                interest = interest.plus(accrual.row().accrual().posted());
                            }
                        }
                    }
                    Money notDue = asOf(AccountPurpose.BNPL_PRINCIPAL, stamped.at());
                    Money principal = BnplServicing.principalDue(schedule, instalment.get(), interest, notDue, zero);
                    // A payoff's early billing is valued the payoff's date; every other its due date (RG-M1, `-022`).
                    LocalDate entryValue = payoff.filter(found -> billing.dueDate().isAfter(found.row().businessDate()))
                            .map(found -> found.row().valueDate()).orElse(billing.dueDate());
                    if (!periodStart.equals(billing.periodStart()) || !interest.equals(billing.interest())
                            || !principal.equals(billing.principal())
                            || billing.entry().isPresent() != interest.plus(principal).isPositive()
                            || billing.entry().filter(entry -> !valuedOn(entry, entryValue)).isPresent()) {
                        diverged(Check.BILLING, subject);
                    }
                }
            } catch (RuntimeException refused) {
                diverged(Check.BILLING, subject);
            }
            items.put(billing.instalment(), new Item(billing.instalment(), billing.id(), billing.dueDate(),
                    billing.interest(), billing.principal()));
            // After an item is billed, the step applies a credit balance held to what is due - its absence is a finding.
            boolean applied = record.applications().stream()
                    .anyMatch(application -> application.row().billing().filter(subject::equals).isPresent());
            if (!applied && credit(stamped.at()).isPositive() && anythingDue()) {
                diverged(Check.APPLICATION_MISSING, subject);
            }
        }

        // -------------------------------------------------------------- the payers

        private void application(BnplCreditBalanceApplication application, Instant at) {
            replayed++;
            UUID subject = application.id();
            try {
                Optional<AllocationRules> rules = rules(application.agreement(), application.agreementVersion(),
                        application.allocationEngine());
                if (rules.isEmpty()) {
                    diverged(Check.APPLICATION, subject);
                } else {
                    AllocationRules settling = new AllocationRules(rules.get().engine(), rules.get().sequence(),
                            rules.get().order(), OverpaymentTreatment.HOLD_AS_CREDIT);
                    List<AllocationLine> derived = new ArrayList<>();
                    Money total = zero;
                    for (AllocationLine line : BnplReplayer.allocate(credit(at), dueState(at, Optional.empty()),
                            settling)) {
                        if (line.target() == AllocationLine.Target.DUE_ITEM) {
                            derived.add(line);
                            total = total.plus(line.amount());
                        }
                    }
                    boolean same = derived.size() == application.lines().size() && total.equals(application.amount());
                    for (int i = 0; same && i < derived.size(); i++) {
                        AllocationLine line = derived.get(i);
                        BnplCreditBalanceApplication.Line row = application.lines().get(i);
                        Item item = items.get(line.item().orElseThrow().value());
                        same = item != null && item.billing.equals(row.billing())
                                && line.component().orElseThrow() == row.component() && line.amount().equals(row.amount());
                    }
                    if (!same) {
                        diverged(Check.APPLICATION, subject);
                    }
                }
            } catch (BnplReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, subject);
            } catch (RuntimeException refused) {
                diverged(Check.APPLICATION, subject);
            }
            principalFactValued(subject, application.valueDate(), at);
            for (BnplCreditBalanceApplication.Line line : application.lines()) {
                itemOfBilling(line.billing()).ifPresent(item -> item.pay(line.component(), line.amount()));
            }
        }

        private void repayment(BnplPurchaseStore.Repayment repayment, Instant at) {
            replayed++;
            UUID subject = repayment.id();
            try {
                Optional<AllocationRules> rules = rules(repayment.agreement(), repayment.agreementVersion(),
                        repayment.allocationEngine());
                if (rules.isEmpty()) {
                    diverged(Check.ALLOCATION, subject);
                } else {
                    // A collection allocates over the due state without the instalments its date's dispute hold covers.
                    Optional<LocalDate> heldOn = BnplPurchaseStore.Repayment.AUTO_COLLECTION.equals(repayment.kind())
                            ? record.attempts().stream().filter(attempt -> attempt.row().repayment()
                                    .filter(subject::equals).isPresent()).findFirst()
                                    .map(attempt -> attempt.row().attemptDate())
                            : Optional.empty();
                    if (BnplPurchaseStore.Repayment.AUTO_COLLECTION.equals(repayment.kind()) && heldOn.isEmpty()) {
                        diverged(Check.ALLOCATION, subject); // a collection's money is one attempt's (bnpl V011)
                    }
                    List<AllocationLine> derived = BnplReplayer.allocate(repayment.amount(), dueState(at, heldOn),
                            rules.get());
                    boolean same = derived.size() == repayment.allocations().size();
                    for (int i = 0; same && i < derived.size(); i++) {
                        AllocationLine line = derived.get(i);
                        BnplPurchaseStore.Allocation row = repayment.allocations().get(i);
                        same = row.sequence() == i + 1 && line.target().name().equals(row.target())
                                && line.item().map(DueItemId::value).equals(row.instalment())
                                && line.component().map(Enum::name).equals(row.component())
                                && line.amount().equals(row.amount());
                    }
                    if (!same) {
                        diverged(Check.ALLOCATION, subject);
                    }
                }
            } catch (BnplReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, subject);
            } catch (RuntimeException refused) {
                diverged(Check.ALLOCATION, subject);
            }
            if (BnplPurchaseStore.Repayment.AT_PURCHASE.equals(repayment.kind())) {
                if (!repayment.valueDate().equals(record.obligation().purchaseDate())) {
                    diverged(Check.VALUE_DATE, subject);
                }
            } else {
                principalFactValued(subject, repayment.valueDate(), at);
            }
            if (BnplPurchaseStore.Repayment.PAYOFF.equals(repayment.kind())) {
                Optional<BnplPayoffQuote> quote = record.execution().flatMap(execution -> record.quotes().stream()
                        .map(BnplRecord.Stamped::row).filter(found -> found.id().equals(execution.row().quote()))
                        .findFirst());
                if (quote.isEmpty() || !quote.get().amount().equals(repayment.amount())
                        || !quote.get().id().toString().equals(repayment.requestKey())) {
                    diverged(Check.PAYOFF, subject);
                }
            }
            if (!valuedOn(repayment.entry(), repayment.valueDate())) {
                diverged(Check.VALUE_DATE, subject);
            }
            for (BnplPurchaseStore.Allocation line : repayment.allocations()) {
                if ("DUE_ITEM".equals(line.target())) {
                    item(line.instalment().orElseThrow()).ifPresent(item -> item.pay(
                            AllocationComponent.valueOf(line.component().orElseThrow()), line.amount()));
                }
            }
        }

        /**
         * R1's arm on a principal fact: valued never before the floor as it stood when it was written - and, once the
         * obligation's payoff executed, exactly the payoff's value date (RG-M1, `-022`'s hand-off).
         */
        private void principalFactValued(UUID subject, LocalDate valueDate, Instant at) {
            Optional<BnplPayoffExecution> payoff = record.execution().filter(found -> found.at().isBefore(at))
                    .map(BnplRecord.Stamped::row);
            if (valueDate.isBefore(floor(at)) || payoff.filter(found -> !found.valueDate().equals(valueDate)).isPresent()) {
                diverged(Check.VALUE_DATE, subject);
            }
        }

        /**
         * {@code bnpl.principal_value_floor} as it stood just before {@code at}: the later of the purchase's business date,
         * the latest billing's value date (a billing beside the payoff counted at most at the payoff's date) and the day
         * after the latest accrual.
         */
        private LocalDate floor(Instant at) {
            LocalDate floor = record.obligation().purchaseDate();
            Optional<LocalDate> payoffDate = record.execution().filter(found -> found.at().isBefore(at))
                    .map(found -> found.row().valueDate());
            for (BnplRecord.Billing billing : record.billings()) {
                if (billing.at().isBefore(at)) {
                    LocalDate counted = payoffDate.filter(date -> date.isBefore(billing.row().dueDate()))
                            .orElse(billing.row().dueDate());
                    floor = counted.isAfter(floor) ? counted : floor;
                }
            }
            for (BnplRecord.Stamped<BnplInterestAccrual> accrual : record.accruals()) {
                LocalDate next = accrual.row().accrual().date().plusDays(1);
                if (accrual.at().isBefore(at) && next.isAfter(floor)) {
                    floor = next;
                }
            }
            return floor;
        }

        // -------------------------------------------------------------- a servicing step's later slots (RG-M5)

        /**
         * A collection attempt, a condition change, a late fee: written by a servicing step after its catch-up application
         * - credit still held beside anything billed and due here means the step's catch-up did not apply it.
         */
        private void stepSlot(UUID subject, Instant at) {
            if (credit(at).isPositive() && anythingDue()) {
                diverged(Check.CATCH_UP_MISSING, subject);
            }
        }

        // -------------------------------------------------------------- refunds

        private void refund(BnplRefund refund, Instant at) {
            replayed++;
            UUID subject = refund.id();
            Optional<BnplRecord.Schedule> made = record.schedules().stream()
                    .filter(version -> version.refund().filter(subject::equals).isPresent()).findFirst();
            Optional<BnplRecord.Agreement> madeAgreement = record.agreements().stream()
                    .filter(version -> version.refund().filter(subject::equals).isPresent()).findFirst();
            boolean active = !closed;
            try {
                MerchantFeeSplit split = record.financing().split();
                Money gross = split.gross();
                Optional<BnplPurchaseStore.StoredSchedule> first = record.schedules().stream()
                        .map(BnplRecord.Schedule::row).filter(row -> row.version() == 1).findFirst();
                if (governing == null || schedule == null || first.isEmpty()
                        || !refund.refundedBefore().equals(refundedBefore) || !refund.purchase().equals(record.purchase().id())
                        || !refund.merchant().equals(record.purchase().merchant())
                        || !refund.agreement().equals(governing.id())
                        || !refund.allocationEngine().equals(governing.refundAllocationEngine())) {
                    diverged(Check.REFUND, subject);
                    return;
                }
                List<BnplPurchaseStore.BilledItem> billed = billedItems();
                Money notDue = asOf(AccountPurpose.BNPL_PRINCIPAL, at);
                BnplRefunds.Derivation derived = BnplRefunds.derive(first.get(), schedule, billed, adjustments, notDue,
                        gross, split.fee(), refundedBefore);
                // The fee returned is the rows' own, cumulatively - the three-way proof's fee arm recomputes each under the
                // purchase's pin by merchant's arithmetic; here the engine is held to the split around it.
                Money returnedBefore = feeReturnedBefore;
                RefundAllocationOutcome outcome = BnplReplayer.allocateRefund(refund.allocationEngine(),
                        derived.position(), refund.amount(), (before, refunded) -> {
                            if (before.equals(refundedBefore) && refunded.equals(refund.amount())) {
                                return refund.feeReturned();
                            }
                            if (before.isZero() && refunded.equals(refundedBefore)) {
                                return returnedBefore;
                            }
                            throw new IllegalStateException("the engine asked a fee the rows do not carry");
                        });
                if (!(outcome instanceof RefundAllocationOutcome.Allocated allocated)) {
                    diverged(Check.REFUND, subject);
                    return;
                }
                RefundAllocation allocation = allocated.allocation();
                Money cancelledUnbilled = allocation.cancelled(RefundAllocation.Cancellation.Kind.UNBILLED);
                Money cancelledBilled = allocation.cancelled(RefundAllocation.Cancellation.Kind.BILLED_UNPAID);
                boolean payInN = governing.accrualEngine().isPresent();
                Money accruedReleased = zero;
                Map<Integer, Money> dueInterestReleased = new TreeMap<>();
                if (active && payInN) {
                    if (allocation.leavesNoPrincipalUnpaid() || notDue.minus(cancelledUnbilled).isZero()) {
                        accruedReleased = asOf(AccountPurpose.BNPL_INTEREST_ACCRUED, at);
                    }
                    if (allocation.leavesNoPrincipalUnpaid()) {
                        for (BnplRefunds.Slot slot : derived.slots()) {
                            if (slot.billed().isPresent()) {
                                Money due = slot.billed().get().due(AllocationComponent.INTEREST);
                                if (due.isPositive()) {
                                    dueInterestReleased.put(slot.sequence(), due);
                                }
                            }
                        }
                    }
                }
                Map<Integer, Money> lateFees = active ? BnplRefunds.lateFeesReleased(derived, allocation) : new TreeMap<>();
                // The adjustment rows' ids are minted and ignored: the comparison is of every other column.
                List<BnplRefund.Adjustment> expected = BnplRefunds.adjustments(derived, allocation, dueInterestReleased,
                        lateFees, zero, new IdGenerator(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new SecureRandom()));
                Money dueReleased = zero;
                for (Money released : dueInterestReleased.values()) {
                    dueReleased = dueReleased.plus(released);
                }
                boolean same = (active || allocation.cancellations().isEmpty())
                        && cancelledUnbilled.equals(refund.cancelledUnbilled())
                        && cancelledBilled.equals(refund.cancelledBilled()) && allocation.excess().equals(refund.excess())
                        && allocation.payableDebit().equals(refund.payableDebit())
                        && accruedReleased.equals(refund.interestAccruedReleased())
                        && dueReleased.equals(refund.interestDueReleased())
                        && sameAdjustments(expected, refund.adjustments())
                        && (refund.excessTo() == BnplRefund.ExcessTo.NONE) == !allocation.excess().isPositive()
                        && (active || refund.excessTo() != BnplRefund.ExcessTo.CREDIT_BALANCE);
                // Schedule and agreement version n+1 exactly when an unbilled instalment is cancelled or shortened.
                if (cancelledUnbilled.isPositive()) {
                    BnplTerms pinned = pinned(governing).orElseThrow(() -> new IllegalStateException("terms unreadable"));
                    BnplPurchaseStore.StoredSchedule next = BnplRefunds.nextSchedule(UUID::randomUUID, record.id(),
                            record.obligation().purchaseDate(), () -> pinned, first.get(), schedule, billed, derived,
                            allocation, notDue.minus(cancelledUnbilled));
                    same &= made.isPresent() && madeAgreement.isPresent()
                            && sameSchedule(next, made.get().row()) && made.get().row().version() == schedule.version() + 1
                            && madeAgreement.get().row().version() == governing.version() + 1
                            && madeAgreement.get().scheduleVersion() == next.version()
                            && refund.agreementVersion() == governing.version() + 1
                            && refund.scheduleVersion() == next.version();
                } else {
                    same &= made.isEmpty() && madeAgreement.isEmpty() && refund.agreementVersion() == governing.version()
                            && refund.scheduleVersion() == schedule.version();
                }
                if (!same) {
                    diverged(Check.REFUND, subject);
                }
            } catch (BnplReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, subject);
            } catch (RuntimeException refused) {
                diverged(Check.REFUND, subject);
            }
            if (active) {
                principalFactValued(subject, refund.valueDate(), at);
            }
            // The state after it: the versions it made in force, its cancellations and releases settled on their items.
            made.ifPresent(version -> {
                schedule = version.row();
                remadeSchedules.add(version.row().id());
            });
            madeAgreement.ifPresent(version -> {
                governing = version.row();
                remadeAgreements.add(version.row().version());
            });
            for (BnplRefund.Adjustment adjustment : refund.adjustments()) {
                if (adjustment.kind() == RefundAllocation.Cancellation.Kind.BILLED_UNPAID) {
                    item(adjustment.instalment()).ifPresent(item -> {
                        item.pay(AllocationComponent.PRINCIPAL, adjustment.principalCancelled());
                        item.pay(AllocationComponent.INTEREST, adjustment.interestReleased());
                        item.pay(AllocationComponent.FEES, adjustment.lateFeeReleased());
                    });
                    releasedOn.merge(adjustment.instalment(), adjustment.lateFeeReleased(), Money::plus);
                }
            }
            adjustments.addAll(refund.adjustments());
            refundedBefore = refundedBefore.plus(refund.amount());
            feeReturnedBefore = feeReturnedBefore.plus(refund.feeReturned());
        }

        private static boolean sameAdjustments(List<BnplRefund.Adjustment> expected, List<BnplRefund.Adjustment> rows) {
            if (expected.size() != rows.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                BnplRefund.Adjustment derived = expected.get(i);
                BnplRefund.Adjustment row = rows.get(i);
                if (derived.sequence() != row.sequence() || !derived.instalment().equals(row.instalment())
                        || derived.kind() != row.kind() || !derived.principalCancelled().equals(row.principalCancelled())
                        || !derived.interestReleased().equals(row.interestReleased()) || derived.whole() != row.whole()
                        || !derived.lateFeeReleased().equals(row.lateFeeReleased())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean sameSchedule(BnplPurchaseStore.StoredSchedule derived, BnplPurchaseStore.StoredSchedule row) {
            if (derived.version() != row.version() || !derived.engine().equals(row.engine())
                    || !derived.principal().equals(row.principal()) || !derived.start().equals(row.start())
                    || derived.instalments().size() != row.instalments().size()) {
                return false;
            }
            for (int i = 0; i < derived.instalments().size(); i++) {
                BnplPurchaseStore.StoredInstalment a = derived.instalments().get(i);
                BnplPurchaseStore.StoredInstalment b = row.instalments().get(i);
                if (a.sequence() != b.sequence() || !a.dueDate().equals(b.dueDate()) || !a.principal().equals(b.principal())
                        || !a.interest().equals(b.interest()) || !a.amount().equals(b.amount())) {
                    return false;
                }
            }
            return true;
        }

        // -------------------------------------------------------------- fees, waivers, reversals, payoff, closure

        private void fee(BnplFeeAssessment fee, Instant at) {
            replayed++;
            stepSlot(fee.id(), at);
            Optional<Item> item = item(fee.instalment());
            try {
                Optional<BnplTerms> pinned = governing == null ? Optional.empty() : pinned(governing);
                if (pinned.isEmpty() || item.isEmpty() || fee.kind() != BnplFeeKind.LATE
                        || !governing.id().equals(fee.agreement()) || governing.version() != fee.agreementVersion()
                        || charged.contains(fee.instalment())) {
                    diverged(Check.FEE, fee.id());
                } else {
                    BnplTerms terms = pinned.get();
                    // The hold as the step met it - the disputes opened, and lifted, before the fee (P12-TST-002).
                    BnplDisputeHold hold = record.disputeHoldAt(at);
                    Money room = terms.lateFeeCap().minus(feesAssessed);
                    Money expected = terms.lateFee().compareTo(room) <= 0 ? terms.lateFee() : room;
                    Item due = item.get();
                    if (!terms.lateFee().isPositive() || !room.isPositive() || !expected.equals(fee.amount())
                            || hold.heldOn(due.dueDate, fee.businessDate())
                            || ChronoUnit.DAYS.between(hold.clockFrom(due.dueDate, fee.businessDate()), fee.businessDate())
                                    < terms.lateFeeDay()
                            || !due.due(AllocationComponent.INTEREST).plus(due.due(AllocationComponent.PRINCIPAL))
                                    .isPositive()
                            || !valuedOn(fee.entry(), fee.businessDate())) {
                        diverged(Check.FEE, fee.id());
                    }
                }
            } catch (RuntimeException refused) {
                diverged(Check.FEE, fee.id());
            }
            feesAssessed = feesAssessed.plus(fee.amount());
            charged.add(fee.instalment());
            item.ifPresent(due -> due.feesBilled = due.feesBilled.plus(fee.amount()));
        }

        private void waiver(BnplWaiver waiver) {
            replayed++;
            Optional<Item> item = item(waiver.instalment());
            Optional<BnplFeeAssessment> assessment = record.fees().stream().map(BnplRecord.Stamped::row)
                    .filter(fee -> fee.id().equals(waiver.assessment()) && fee.instalment().equals(waiver.instalment()))
                    .findFirst();
            if (item.isEmpty() || assessment.isEmpty() || waiver.kind() != BnplWaiverKind.FEE || waiver.entry().isEmpty()) {
                diverged(Check.WAIVER, waiver.id());
            } else {
                // The room, least(its instalment's fees still due, the assessment never forgiven) - R3.
                Money unforgiven = assessment.get().amount().minus(waivedOf.getOrDefault(waiver.assessment(), zero))
                        .minus(releasedOn.getOrDefault(waiver.instalment(), zero));
                Money dueFees = item.get().due(AllocationComponent.FEES);
                Money room = dueFees.compareTo(unforgiven) <= 0 ? dueFees : unforgiven;
                if (!waiver.amount().isPositive() || waiver.amount().compareTo(room) > 0) {
                    diverged(Check.WAIVER, waiver.id());
                }
            }
            waivedOf.merge(waiver.assessment(), waiver.amount(), Money::plus);
            item.ifPresent(due -> due.feesBilled = due.feesBilled.minus(waiver.amount()));
        }

        private void reversal(BnplRecord.Reversal stamped) {
            replayed++;
            BnplRepaymentReversal reversal = stamped.row();
            Instant at = reversal.decidedAt().orElse(reversal.proposedAt());
            Optional<BnplPurchaseStore.Repayment> reversed = record.repayments().stream().map(BnplRecord.Stamped::row)
                    .filter(row -> row.id().equals(reversal.repayment())).findFirst();
            Map<UUID, Money> negated = new HashMap<>();
            stamped.negations().forEach(negation -> negated.merge(negation.allocation(), negation.amount(), Money::plus));
            boolean exact = reversed.isPresent() && !BnplPurchaseStore.Repayment.PAYOFF.equals(reversed.get().kind())
                    && reversal.entry().isPresent() && reversal.valueDate().isPresent()
                    && negated.size() == reversed.get().allocations().size();
            if (exact) {
                for (BnplPurchaseStore.Allocation line : reversed.get().allocations()) {
                    exact &= line.amount().equals(negated.get(line.id()));
                }
            }
            if (!exact) {
                diverged(Check.REVERSAL, reversal.id());
            }
            reversal.valueDate().ifPresent(value -> {
                principalFactValued(reversal.id(), value, at);
                reversal.entry().filter(entry -> !valuedOn(entry, value))
                        .ifPresent(entry -> diverged(Check.VALUE_DATE, reversal.id()));
            });
            for (BnplRecord.Negation negation : stamped.negations()) {
                BnplPurchaseStore.Allocation line = allocations.get(negation.allocation());
                if (line != null && "DUE_ITEM".equals(line.target())) {
                    item(line.instalment().orElseThrow()).ifPresent(due -> due.pay(
                            AllocationComponent.valueOf(line.component().orElseThrow()), negation.amount().negated()));
                }
            }
        }

        private void quote(BnplPayoffQuote quote, Instant at) {
            replayed++;
            Money principal = asOf(AccountPurpose.BNPL_PRINCIPAL, at);
            Money principalDue = asOf(AccountPurpose.BNPL_PRINCIPAL_DUE, at);
            Money accrued = asOf(AccountPurpose.BNPL_INTEREST_ACCRUED, at);
            Money interestDue = asOf(AccountPurpose.BNPL_INTEREST_DUE, at);
            Money feesDue = asOf(AccountPurpose.BNPL_FEES_DUE, at);
            Money credit = credit(at);
            boolean same = governing != null && governing.version() == quote.agreementVersion()
                    && governing.scheduleEngine().equals(quote.scheduleEngine())
                    && governing.accrualEngine().equals(quote.accrualEngine())
                    && governing.allocationEngine().equals(quote.allocationEngine())
                    && principal.equals(quote.principal()) && principalDue.equals(quote.principalDue())
                    && accrued.equals(quote.interestAccrued()) && interestDue.equals(quote.interestDue())
                    && feesDue.equals(quote.feesDue()) && credit.equals(quote.creditBalance())
                    && quote.amount().equals(principal.plus(principalDue).plus(accrued).plus(interestDue).plus(feesDue)
                            .minus(credit).plus(quote.projectedInterest()));
            // The projection re-derived as the quote made it (`P12-TSK-022`): ACCRUAL_ENGINE_V1 over [its date, G - 1] of
            // the span in force, on P + PD, chained onto the latest accrual of a period of that span.
            try {
                Money base = principal.plus(principalDue);
                Money projected = zero;
                long days = 0;
                Optional<BnplTerms> pinned = governing == null ? Optional.empty() : pinned(governing);
                if (governing != null && governing.accrualEngine().isPresent() && pinned.isPresent()
                        && pinned.get().payInN().isPresent() && quote.goodThrough().isAfter(quote.businessDate())
                        && base.isPositive() && schedule != null && !schedule.instalments().isEmpty()) {
                    List<AccrualPeriod> periods = BnplAccruals.periodsOf(schedule);
                    LocalDate spanEnd = periods.get(periods.size() - 1).endExclusive();
                    LocalDate through = quote.goodThrough().minusDays(1).isBefore(spanEnd)
                            ? quote.goodThrough().minusDays(1) : spanEnd.minusDays(1);
                    if (!through.isBefore(quote.businessDate())) {
                        BnplTerms.PayInNTerms own = pinned.get().payInN().get();
                        AccrualTerms terms = new AccrualTerms(record.obligation().currency(),
                                pinned.get().nominalAnnualRate(), own.dayCount(), own.interestRounding(),
                                BnplReplayer.shared(LendingEngine.ACCRUAL, governing.accrualEngine().get()));
                        Optional<InterestAccrual> last = lastAccrual.map(BnplInterestAccrual::accrual)
                                .filter(prior -> periods.contains(prior.period()));
                        Money interest = BnplReplayer.project(terms, periods, last, quote.businessDate(), through, base);
                        projected = Money.ofPersisted(interest.minorUnits(), base.currency(), base.scale());
                        days = ChronoUnit.DAYS.between(quote.businessDate(), through) + 1;
                    }
                }
                same &= base.equals(quote.projectionBase()) && projected.equals(quote.projectedInterest())
                        && days == quote.projectedDays();
            } catch (BnplReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, quote.id());
            } catch (RuntimeException refused) {
                same = false;
            }
            if (!same) {
                diverged(Check.PAYOFF, quote.id());
            }
        }

        private void execution(BnplPayoffExecution execution, Instant at) {
            replayed++;
            Optional<BnplPayoffQuote> quote = record.quotes().stream().map(BnplRecord.Stamped::row)
                    .filter(found -> found.id().equals(execution.quote())).findFirst();
            if (quote.isEmpty() || !execution.businessDate().equals(quote.get().goodThrough())
                    || governing == null || governing.version() != quote.get().agreementVersion()) {
                diverged(Check.PAYOFF, execution.id());
            }
            LocalDate floor = floor(at);
            LocalDate expected = floor.isAfter(execution.businessDate()) ? floor : execution.businessDate();
            if (!execution.valueDate().equals(expected)) {
                diverged(Check.VALUE_DATE, execution.id());
            }
            if (record.closure().filter(closure -> closure.row().reason() == BnplClosureReason.PAID_OFF_EARLY)
                    .isEmpty()) {
                diverged(Check.PAYOFF, execution.id());
            }
        }

        private void closure(BnplClosure closure, Instant at) {
            replayed++;
            boolean settled = closure.refund().equals(credit(at));
            for (AccountPurpose receivable : List.of(AccountPurpose.BNPL_PRINCIPAL, AccountPurpose.BNPL_PRINCIPAL_DUE,
                    AccountPurpose.BNPL_INTEREST_ACCRUED, AccountPurpose.BNPL_INTEREST_DUE, AccountPurpose.BNPL_FEES_DUE)) {
                settled &= asOf(receivable, at).isZero();
            }
            if (!settled || anythingDue()) {
                diverged(Check.CLOSURE, closure.id());
            }
            closed = true;
        }

        // -------------------------------------------------------------- plumbing

        /** The pinned allocation rules of the agreement version in force, when it is the one the row names. */
        private Optional<AllocationRules> rules(BnplAgreementId agreement, int version, String engine) {
            if (governing == null || !governing.id().equals(agreement) || governing.version() != version
                    || !governing.allocationEngine().equals(engine)) {
                return Optional.empty();
            }
            EngineVersion allocation = BnplReplayer.shared(LendingEngine.ALLOCATION, engine);
            return pinned(governing).filter(pinned -> pinned.allocationEngine() == allocation)
                    .map(pinned -> new AllocationRules(pinned.allocationEngine(), pinned.allocationSequence(),
                            pinned.allocationOrder(), pinned.overpaymentTreatment()));
        }

        private Optional<BnplTerms> pinned(BnplAgreement agreement) {
            return record.termsVersions().getOrDefault(agreement.termsVersionId(), Optional.empty())
                    .map(BnplTermsVersion::terms);
        }

        /**
         * The due state just before {@code at}: each item's billed less settled, without those held on {@code heldOn} by the
         * disputes opened (and not yet lifted) before {@code at} - the hold the collection met (P12-TST-002).
         */
        private DueState dueState(Instant at, Optional<LocalDate> heldOn) {
            BnplDisputeHold hold = record.disputeHoldAt(at);
            List<DueState.DueItem> due = new ArrayList<>();
            items.values().stream().sorted(Comparator.comparing(item -> item.dueDate)).forEach(item -> {
                if (!item.total().isZero() && heldOn.filter(day -> hold.heldOn(item.dueDate, day)).isEmpty()) {
                    due.add(new DueState.DueItem(DueItemId.of(item.instalment()), item.dueDate,
                            item.due(AllocationComponent.FEES), item.due(AllocationComponent.INTEREST),
                            item.due(AllocationComponent.PRINCIPAL)));
                }
            });
            return new DueState(due, asOf(AccountPurpose.BNPL_PRINCIPAL, at));
        }

        /** The billed items as the due state reads them - {@code bnpl.billing_component_billed} less {@code _paid}. */
        private List<BnplPurchaseStore.BilledItem> billedItems() {
            List<BnplPurchaseStore.BilledItem> billed = new ArrayList<>();
            items.values().stream().sorted(Comparator.comparing(item -> item.dueDate)).forEach(item -> billed.add(
                    new BnplPurchaseStore.BilledItem(item.billing, item.instalment(), item.dueDate, item.feesBilled,
                            item.interestBilled, item.principalBilled, item.paid.get(AllocationComponent.FEES),
                            item.paid.get(AllocationComponent.INTEREST), item.paid.get(AllocationComponent.PRINCIPAL))));
            return billed;
        }

        private boolean anythingDue() {
            return items.values().stream().anyMatch(item -> item.total().isPositive());
        }

        private Optional<Item> item(UUID instalment) {
            return Optional.ofNullable(items.get(instalment));
        }

        private Optional<Item> itemOfBilling(UUID billing) {
            return items.values().stream().filter(item -> item.billing.equals(billing)).findFirst();
        }

        private List<PurposeBalances.DatedLine> linesOf(AccountPurpose purpose) {
            LedgerAccountId account = accounts.get(purpose);
            return account == null ? List.of() : linesByAccount.getOrDefault(account, List.of());
        }

        /** Whether every line of {@code entry} on the obligation's accounts is valued {@code date}. */
        private boolean valuedOn(JournalEntryId entry, LocalDate date) {
            List<PurposeBalances.DatedLine> lines = linesByEntry.getOrDefault(entry, List.of());
            return !lines.isEmpty() && lines.stream().allMatch(line -> line.valueDate().equals(date));
        }

        /** Whether {@code entry} was written before {@code at} - an entry no row names counts as always written. */
        private boolean writtenBefore(JournalEntryId entry, Instant at) {
            BnplSubledgerProof.Namer namer = named.get(entry);
            return namer == null || namer.at().isBefore(at);
        }

        /** {@code purpose}'s balance on its normal side over the entries written before {@code at}. */
        private Money asOf(AccountPurpose purpose, Instant at) {
            Money balance = zero;
            for (PurposeBalances.DatedLine line : linesOf(purpose)) {
                if (writtenBefore(line.entry(), at)) {
                    balance = line.direction() == Direction.DEBIT ? balance.plus(line.amount())
                            : balance.minus(line.amount());
                }
            }
            return purpose == AccountPurpose.BNPL_CREDIT_BALANCE ? balance.negated() : balance;
        }

        /** The credit balance held just before {@code at}. */
        private Money credit(Instant at) {
            return asOf(AccountPurpose.BNPL_CREDIT_BALANCE, at);
        }

        /** One billed item, rebuilt: billed (its fees assessed less waived) and settled by every payer and refund. */
        private final class Item {

            private final UUID instalment;
            private final UUID billing;
            private final LocalDate dueDate;
            private final Money interestBilled;
            private final Money principalBilled;
            private Money feesBilled = zero;
            private final Map<AllocationComponent, Money> paid = new EnumMap<>(AllocationComponent.class);

            Item(UUID instalment, UUID billing, LocalDate dueDate, Money interestBilled, Money principalBilled) {
                this.instalment = instalment;
                this.billing = billing;
                this.dueDate = dueDate;
                this.interestBilled = interestBilled;
                this.principalBilled = principalBilled;
                for (AllocationComponent component : AllocationComponent.values()) {
                    paid.put(component, zero);
                }
            }

            UUID instalment() {
                return instalment;
            }

            void pay(AllocationComponent component, Money amount) {
                paid.merge(component, amount, Money::plus);
            }

            Money billed(AllocationComponent component) {
                return switch (component) {
                    case FEES -> feesBilled;
                    case INTEREST -> interestBilled;
                    case PRINCIPAL -> principalBilled;
                };
            }

            Money due(AllocationComponent component) {
                return billed(component).minus(paid.get(component));
            }

            Money total() {
                return due(AllocationComponent.FEES).plus(due(AllocationComponent.INTEREST))
                        .plus(due(AllocationComponent.PRINCIPAL));
            }
        }
    }

}
