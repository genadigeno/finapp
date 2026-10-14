package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The replay proof (`P11-TSK-029`; PHASE_11_PLAN.md sections 12.1 and 13.1; ADR-0092 points 8-9; {@code INV-LND-05},
 * {@code INV-LND-09}, {@code INV-LND-03}, {@code INV-LND-04}): one loan account of either kind re-derived from its
 * {@link LoanRecord} by the engines each row pinned ({@link LoanReplayer}) - {@code IDENTICAL} when every figure re-derives
 * to the row's, {@code DIVERGED} with each disagreeing fact named (by kind and id, never an amount) otherwise.
 *
 * <h2>What is re-derived, from what</h2>
 *
 * <ul>
 *   <li><strong>Every agreement version</strong> - its canonical terms re-rendered from the rows in their stored format
 *       (1 and 2 from the offer and the version's columns, 3 from the amendment and the schedule it fixed, 4 from the
 *       prepayment and the schedule it fixed) and their SHA-256 re-verified against {@code terms_sha256}; its columns held
 *       to the pinned terms version (engines, template); an instalment offer's illustration re-projected; every engine
 *       version it pins held by this build.
 *   <li><strong>Every schedule version</strong> - version 1 projected from the accrual start, an amendment's re-calculated
 *       and a prepayment's re-calculated at the superseded level instalment, each from the principal outstanding the
 *       ledger held at its birth, over the open period of the calendar it superseded: every instalment compared.
 *   <li><strong>Then the history in the lock's order</strong> - the rows ordered by the database instant each was written
 *       under the loan's row lock (L3; lending {@code V025} for the decisions): every accrual (its period, its base from the
 *       journal lines dated on or before it, its cumulative figure, its posting; no elapsed date with principal outstanding
 *       missing), every billing and statement (interest from the period's accruals, principal from the schedule and the
 *       principal not yet due), every credit-balance application and every allocation (the pinned allocation engine over
 *       the due state the rows and the ledger held just before it; an application missing where credit met a due item),
 *       every late fee (the pinned fee within the cap), every waiver and reversal (within what was due or paid), every
 *       re-scheduled arrear and the closure's refund.
 * </ul>
 *
 * <p>The ledger "as of" an instant is every line of the entries a row named before it - plus any line no row names, which
 * the subledger proof fails on its own. Pure: a function of the record - the same verdict on every instance, in every JVM,
 * whatever commits after the snapshot. Nothing is repaired: a {@code DIVERGED} is an alert.
 */
public final class LoanReplayProof {

    /** The replay's verdict. */
    public enum Verdict {
        IDENTICAL,
        DIVERGED
    }

    /** The kind of fact that failed to re-derive. */
    public enum Check {
        /** A row pins an engine version this build does not hold. */
        ENGINE_ABSENT,
        /** A pinned terms version (or the offer) cannot be read by this build. */
        TERMS_UNREADABLE,
        /** An agreement version's columns disagree with its pinned terms version. */
        AGREEMENT,
        /** An agreement version's canonical terms do not re-render to its stored hash. */
        TERMS,
        /** A schedule version does not re-derive. */
        SCHEDULE,
        /** An accrual does not re-derive. */
        ACCRUAL,
        /** An elapsed date with principal outstanding has no accrual. */
        ACCRUAL_MISSING,
        /** A billing does not re-derive. */
        BILLING,
        /** A statement does not re-derive. */
        STATEMENT,
        /** A credit-balance application does not re-derive. */
        APPLICATION,
        /** A billed item met a credit balance and nothing was applied. */
        APPLICATION_MISSING,
        /** A repayment's allocation does not re-derive. */
        ALLOCATION,
        /** A late fee does not re-derive. */
        FEE,
        /** A waiver exceeds what was due or paid. */
        WAIVER,
        /** A reversal does not negate its repayment exactly. */
        REVERSAL,
        /** A re-scheduled arrear is not what its item had due. */
        RESCHEDULING,
        /** The closure's refund is not the credit balance held. */
        CLOSURE
    }

    /** One fact that failed to re-derive: its kind and its row - never a figure ({@code INV-AUD-02}). */
    public record Finding(Check check, UUID subject) {

        public Finding {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(subject, "subject");
        }
    }

    /** One account's replay: the verdict, what diverged, and how many facts were re-derived (never zero for a loan). */
    public record Replay(LoanId loan, Verdict verdict, List<Finding> findings, int replayed) {

        public Replay {
            Objects.requireNonNull(loan, "loan");
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
    }

    /** Replays {@code record}. */
    public Replay replay(LoanRecord record) {
        Objects.requireNonNull(record, "record");
        Walk walk = new Walk(record);
        walk.agreements();
        walk.schedules();
        walk.history();
        walk.accrualGaps();
        List<Finding> findings = List.copyOf(walk.findings.values());
        return new Replay(record.id(), findings.isEmpty() ? Verdict.IDENTICAL : Verdict.DIVERGED, findings, walk.replayed);
    }

    // ------------------------------------------------------------------ the walk

    /** One replay's state: the record, the findings, and the due state rebuilt as the history is walked. */
    private static final class Walk {

        private final LoanRecord record;
        private final Money zero;
        private final Map<String, Finding> findings = new LinkedHashMap<>();
        private int replayed;

        private final Map<JournalEntryId, LoanSubledgerProof.Namer> named;
        private final Map<AccountPurpose, LedgerAccountId> accounts = new EnumMap<>(AccountPurpose.class);
        private final Map<UUID, LoanRecord.Agreement> agreementsById = new HashMap<>();
        private final Map<UUID, LoanRecord.ScheduleVersion> schedulesById = new HashMap<>();

        // The due state, rebuilt in the lock's order.
        private final Map<UUID, Item> items = new LinkedHashMap<>();
        private final Map<UUID, LoanRecord.PaymentLine> paymentLines = new HashMap<>();
        private final Map<LedgerAccountId, List<PurposeBalances.DatedLine>> linesByAccount = new HashMap<>();
        private final Map<UUID, LoanRecord.Accrual> previousAccrual = new HashMap<>();
        private final Set<UUID> billedInstalments = new HashSet<>();
        private Optional<LoanRecord.Agreement> governing = Optional.empty();
        private Optional<LoanRecord.ScheduleVersion> schedule = Optional.empty();
        private Optional<LoanRecord.StatementRow> lastStatement = Optional.empty();
        private Money feesAssessed;
        private final Map<UUID, Money> refundedPerAssessment = new HashMap<>();

        Walk(LoanRecord record) {
            this.record = record;
            this.zero = record.zero();
            this.feesAssessed = zero;
            this.named = LoanSubledgerProof.namedEntries(record);
            for (AccountPurpose purpose : LoanSubledgerProof.PURPOSES) {
                record.account(purpose).ifPresent(account -> accounts.put(purpose, account.id()));
            }
            record.agreements().forEach(agreement -> agreementsById.put(agreement.id(), agreement));
            record.schedules().forEach(found -> schedulesById.put(found.id(), found));
            record.repayments().forEach(payment -> payment.lines().forEach(line -> paymentLines.put(line.id(), line)));
            record.journalLines().forEach(line -> linesByAccount.computeIfAbsent(line.account(), key -> new ArrayList<>())
                    .add(line));
            LoanRecord.Accrual before = null;
            for (LoanRecord.Accrual accrual : record.accruals()) {
                if (before != null) {
                    previousAccrual.put(accrual.id(), before);
                }
                before = accrual;
            }
        }

        private List<PurposeBalances.DatedLine> linesOf(AccountPurpose purpose) {
            LedgerAccountId account = accounts.get(purpose);
            return account == null ? List.of() : linesByAccount.getOrDefault(account, List.of());
        }

        private void diverged(Check check, UUID subject) {
            findings.putIfAbsent(check + "/" + subject, new Finding(check, subject));
        }

        // -------------------------------------------------------------- agreements

        void agreements() {
            for (LoanRecord.Agreement agreement : record.agreements()) {
                replayed++;
                Optional<LoanTermsStore.LoanTermsVersion> version =
                        record.termsVersions().getOrDefault(agreement.termsVersionId(), Optional.empty());
                boolean held = agreement.scheduleEngine().map(n -> LoanReplayer.holds(LendingEngine.SCHEDULE, n)).orElse(true)
                        && LoanReplayer.holds(LendingEngine.ACCRUAL, agreement.accrualEngine())
                        && LoanReplayer.holds(LendingEngine.ALLOCATION, agreement.allocationEngine())
                        && agreement.statementEngine().map(n -> LoanReplayer.holds(LendingEngine.STATEMENT, n)).orElse(true);
                if (!held) {
                    diverged(Check.ENGINE_ABSENT, agreement.id());
                }
                if (version.isEmpty()) {
                    diverged(Check.TERMS_UNREADABLE, agreement.id());
                    continue;
                }
                LoanTerms pinned = version.get().terms();
                if (!factsHold(agreement, pinned)) {
                    diverged(Check.AGREEMENT, agreement.id());
                }
                try {
                    String rendered = LoanReplayer.render(agreement.canonicalFormat(),
                            content(agreement, version.get(), pinned), amendment(agreement, pinned),
                            prepayment(agreement));
                    // The re-verification: the hash of the terms as the rows re-render them is the hash stored.
                    if (!MessageDigest.isEqual(CanonicalOfferTerms.sha256(rendered), agreement.termsSha256())) {
                        diverged(Check.TERMS, agreement.id());
                    }
                } catch (RuntimeException unrenderable) {
                    diverged(Check.TERMS, agreement.id());
                }
                if (agreement.version() == 1 && record.loan().kind() == LoanKind.INSTALMENT) {
                    illustration(agreement, pinned);
                }
            }
        }

        /** An agreement version's columns agree with the terms version it pins. */
        private boolean factsHold(LoanRecord.Agreement agreement, LoanTerms pinned) {
            return agreement.product() == pinned.product()
                    && agreement.scale() == pinned.currency().minorUnits()
                    && pinned.currency().equals(record.loan().currency())
                    && agreement.accrualEngine() == pinned.accrualEngine().number()
                    && agreement.allocationEngine() == pinned.allocationEngine().number()
                    && agreement.scheduleEngine().equals(pinned.instalment().map(own -> own.scheduleEngine().number()))
                    && agreement.statementEngine().equals(pinned.revolving().map(own -> own.statementEngine().number()))
                    && agreement.templateId().equals(pinned.agreementTemplate().id())
                    && agreement.templateVersion() == pinned.agreementTemplate().version();
        }

        /** The version's governing terms: the pinned term set with the version's own rate (an amendment's, `-027`). */
        private LoanTerms governing(LoanRecord.Agreement agreement, LoanTerms pinned) {
            return pinned.toBuilder().nominalAnnualRate(new BigDecimal(agreement.nominalAnnualRate())).build();
        }

        private CanonicalOfferTerms.Content content(LoanRecord.Agreement agreement, LoanTermsStore.LoanTermsVersion version,
                LoanTerms pinned) {
            LoanOffer offer = record.offer().orElseThrow(() -> new IllegalStateException("the offer is unreadable"));
            return new CanonicalOfferTerms.Content(record.loan().offer(), offer.application(), agreement.decision(),
                    LoanTermsVersionId.of(agreement.termsVersionId()), version.row().version(), governing(agreement, pinned),
                    record.money(agreement.amountMinor()), agreement.termMonths(), agreement.repaymentDay(),
                    agreement.version() == 1 ? offer.terms().illustration() : Optional.empty());
        }

        private Optional<CanonicalOfferTerms.Amendment> amendment(LoanRecord.Agreement agreement, LoanTerms pinned) {
            if (agreement.amendment().isEmpty()) {
                return Optional.empty();
            }
            UUID id = agreement.amendment().get();
            LoanRecord.Amendment amendment = record.amendments().stream().filter(found -> found.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("an amended version names its amendment"));
            LoanRecord.ScheduleVersion fixed = scheduleOf(agreement);
            Money arrears = zero;
            for (LoanRecord.Rescheduling rescheduling : record.reschedulings()) {
                if (rescheduling.amendmentId().equals(id)) {
                    arrears = arrears.plus(rescheduling.principal());
                }
            }
            ZoneId zone = ZoneId.of(pinned.servicingZone());
            return Optional.of(new CanonicalOfferTerms.Amendment(id, amendment.amendsVersion(),
                    previous(agreement).termsSha256Hex(), agreement.version(),
                    LocalDate.ofInstant(agreement.effectiveFrom(), zone), fixed.startDate(),
                    fixed.instalments().get(0).dueDate(), amendment.remainingInstalments(), fixed.principal(), arrears));
        }

        private Optional<CanonicalOfferTerms.Prepayment> prepayment(LoanRecord.Agreement agreement) {
            if (agreement.prepayment().isEmpty()) {
                return Optional.empty();
            }
            LoanRecord.Payment prepaid = payment(agreement.prepayment().get());
            LoanRecord.ScheduleVersion fixed = scheduleOf(agreement);
            return Optional.of(new CanonicalOfferTerms.Prepayment(prepaid.id(), agreement.version() - 1,
                    previous(agreement).termsSha256Hex(), agreement.version(), prepaid.valueDate(), prepaid.amount(),
                    fixed.startDate(), fixed.instalments().get(0).dueDate(), fixed.termMonths(), fixed.principal(),
                    fixed.instalment()));
        }

        /** The offer's illustrative schedule, re-projected by the pinned engine from its own first day. */
        private void illustration(LoanRecord.Agreement agreement, LoanTerms pinned) {
            Optional<LoanOffer.Illustration> stored = record.offer().flatMap(offer -> offer.terms().illustration());
            if (stored.isEmpty() || agreement.scheduleEngine().isEmpty() || agreement.termMonths().isEmpty()) {
                diverged(Check.TERMS, agreement.id());
                return;
            }
            try {
                ScheduleOutcome outcome = LoanReplayer.project(agreement.scheduleEngine().get(), governing(agreement, pinned),
                        record.money(agreement.amountMinor()), agreement.termMonths().get(), agreement.repaymentDay(),
                        stored.get().start());
                if (!(outcome instanceof ScheduleOutcome.Projected projected)
                        || !LoanOffer.Illustration.of(projected.schedule()).equals(stored.get())) {
                    diverged(Check.TERMS, agreement.id());
                }
            } catch (LoanReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, agreement.id());
            }
        }

        // -------------------------------------------------------------- schedules

        void schedules() {
            List<LoanRecord.ScheduleVersion> versions = record.schedules();
            for (int i = 0; i < versions.size(); i++) {
                LoanRecord.ScheduleVersion fixed = versions.get(i);
                replayed++;
                LoanRecord.Agreement agreement = agreementsById.get(fixed.agreementId());
                Optional<LoanTerms> pinned = agreement == null ? Optional.empty() : pinned(agreement);
                if (agreement == null || pinned.isEmpty() || agreement.version() != fixed.agreementVersion()
                        || fixed.version() != i + 1 || agreement.scheduleEngine().filter(n -> n == fixed.engine()).isEmpty()) {
                    diverged(Check.SCHEDULE, fixed.id());
                    continue;
                }
                LoanTerms governing = governing(agreement, pinned.get());
                try {
                    Optional<ScheduleOutcome> outcome = rederive(fixed, i == 0 ? Optional.empty()
                            : Optional.of(versions.get(i - 1)), agreement, governing);
                    if (outcome.isEmpty() || !(outcome.get() instanceof ScheduleOutcome.Projected projected)
                            || !same(projected.schedule(), fixed)) {
                        diverged(Check.SCHEDULE, fixed.id());
                    }
                } catch (LoanReplayer.EngineAbsent absent) {
                    diverged(Check.ENGINE_ABSENT, fixed.id());
                } catch (RuntimeException refused) {
                    diverged(Check.SCHEDULE, fixed.id());
                }
            }
        }

        /** The schedule version as its pinned engine re-derives it from the facts of its birth; empty when they disagree. */
        private Optional<ScheduleOutcome> rederive(LoanRecord.ScheduleVersion fixed,
                Optional<LoanRecord.ScheduleVersion> superseded, LoanRecord.Agreement agreement, LoanTerms governing) {
            int engine = fixed.engine();
            if (superseded.isEmpty()) {
                // Version 1 from the accrual start (both paths, ADR-0097) over the agreement's principal and term.
                if (record.accrualStart().filter(start -> start.startDate().equals(fixed.startDate())).isEmpty()
                        || agreement.termMonths().isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(LoanReplayer.project(engine, governing, record.money(agreement.amountMinor()),
                        agreement.termMonths().get(), agreement.repaymentDay(), fixed.startDate()));
            }
            // A later version starts the open period of the calendar it superseded, from the principal outstanding
            // (LOAN_PRINCIPAL) the ledger held at its birth - after rule 13 or the prepayment's rule 5.
            Money outstanding = asOf(AccountPurpose.LOAN_PRINCIPAL, fixed.createdAt());
            InstalmentCalendar previous = calendar(superseded.get());
            if (!fixed.principal().equals(outstanding) || fixed.instalments().isEmpty()) {
                return Optional.empty();
            }
            if (agreement.amendment().isPresent()) {
                LoanRecord.Amendment amendment = record.amendments().stream()
                        .filter(found -> found.id().equals(agreement.amendment().get())).findFirst().orElseThrow();
                LocalDate effective = LocalDate.ofInstant(agreement.effectiveFrom(),
                        ZoneId.of(governing.servicingZone()));
                AccrualPeriod open = previous.periodOf(effective);
                if (!open.start().equals(fixed.startDate())) {
                    return Optional.empty();
                }
                return Optional.of(LoanReplayer.recalculate(engine, governing, outstanding,
                        amendment.remainingInstalments(), agreement.repaymentDay(), open.start(), open.endExclusive()));
            }
            if (agreement.prepayment().isPresent()) {
                LoanRecord.Payment prepaid = payment(agreement.prepayment().get());
                AccrualPeriod open = previous.periodOf(prepaid.valueDate());
                if (!open.start().equals(fixed.startDate())) {
                    return Optional.empty();
                }
                Set<UUID> billedBefore = new HashSet<>();
                for (LoanRecord.Billing billing : record.billings()) {
                    if (billing.createdAt().isBefore(prepaid.createdAt())) {
                        billing.instalment().ifPresent(billedBefore::add);
                    }
                }
                int unbilled = (int) superseded.get().instalments().stream()
                        .filter(instalment -> !billedBefore.contains(instalment.id())).count();
                return Optional.of(LoanReplayer.recalculateAtInstalment(engine, governing, outstanding,
                        superseded.get().instalment(), unbilled, agreement.repaymentDay(), open.start(),
                        open.endExclusive()));
            }
            return Optional.empty();
        }

        /** Every figure of the re-derived schedule is the row's. */
        private static boolean same(Schedule derived, LoanRecord.ScheduleVersion fixed) {
            if (!derived.principal().equals(fixed.principal()) || !derived.instalment().equals(fixed.instalment())
                    || !derived.finalInstalment().equals(fixed.finalInstalment())
                    || !derived.totalInterest().equals(fixed.totalInterest()) || derived.termMonths() != fixed.termMonths()
                    || !derived.start().equals(fixed.startDate()) || derived.intendedDay() != fixed.intendedDay()
                    || derived.periods().size() != fixed.instalments().size()) {
                return false;
            }
            for (int i = 0; i < derived.periods().size(); i++) {
                SchedulePeriod period = derived.periods().get(i);
                LoanRecord.Instalment row = fixed.instalments().get(i);
                if (period.sequence() != row.sequence() || !period.periodStart().equals(row.periodStart())
                        || !period.dueDate().equals(row.dueDate()) || period.days() != row.days()
                        || !period.openingBalance().equals(row.opening()) || !period.interest().equals(row.interest())
                        || !period.principal().equals(row.principal()) || !period.amount().equals(row.amount())
                        || !period.closingBalance().equals(row.closing())) {
                    return false;
                }
            }
            return true;
        }

        // -------------------------------------------------------------- the history

        /** One fact in the lock's order. */
        private record Event(Instant at, int rank, UUID id, Object fact) {}

        void history() {
            List<Event> events = new ArrayList<>();
            record.agreements().forEach(agreement -> events.add(new Event(agreement.createdAt(), 0, agreement.id(),
                    agreement)));
            record.schedules().forEach(fixed -> events.add(new Event(fixed.createdAt(), 1, fixed.id(), fixed)));
            record.accruals().forEach(accrual -> events.add(new Event(accrual.createdAt(), 2, accrual.id(), accrual)));
            record.billings().forEach(billing -> events.add(new Event(billing.createdAt(), 3, billing.id(), billing)));
            record.statements().forEach(statement -> events.add(new Event(statement.createdAt(), 3, statement.id(),
                    statement)));
            record.applications().forEach(application -> events.add(new Event(application.createdAt(), 4,
                    application.id(), application)));
            record.fees().forEach(fee -> events.add(new Event(fee.createdAt(), 5, fee.id(), fee)));
            record.reschedulings().forEach(rescheduling -> events.add(new Event(rescheduling.createdAt(), 6,
                    rescheduling.id(), rescheduling)));
            record.repayments().forEach(payment -> events.add(new Event(payment.createdAt(), 7, payment.id(), payment)));
            record.waivers().stream().filter(LoanRecord.WaiverRow::approved).forEach(waiver -> events.add(
                    new Event(waiver.decidedAt().orElseThrow(), 8, waiver.id(), waiver)));
            record.reversals().stream().filter(reversal -> "APPROVED".equals(reversal.status())).forEach(reversal ->
                    events.add(new Event(reversal.decidedAt().orElseThrow(), 9, reversal.id(), reversal)));
            record.closure().ifPresent(closure -> events.add(new Event(closure.createdAt(), 10, closure.id(), closure)));
            events.sort(Comparator.comparing(Event::at).thenComparingInt(Event::rank).thenComparing(Event::id));

            for (Event event : events) {
                switch (event.fact()) {
                    case LoanRecord.Agreement agreement -> governing = Optional.of(agreement);
                    case LoanRecord.ScheduleVersion fixed -> schedule = Optional.of(fixed);
                    case LoanRecord.Accrual accrual -> accrual(accrual);
                    case LoanRecord.Billing billing -> billing(billing);
                    case LoanRecord.StatementRow statement -> statement(statement);
                    case LoanRecord.Application application -> application(application);
                    case LoanRecord.Fee fee -> fee(fee);
                    case LoanRecord.Rescheduling rescheduling -> rescheduling(rescheduling);
                    case LoanRecord.Payment payment -> payment(payment);
                    case LoanRecord.WaiverRow waiver -> waiver(waiver);
                    case LoanRecord.Reversal reversal -> reversal(reversal);
                    case LoanRecord.Closure closure -> closure(closure);
                    default -> throw new IllegalStateException("an unknown fact in the history");
                }
            }
        }

        // -------------------------------------------------------------- accruals

        private void accrual(LoanRecord.Accrual accrual) {
            replayed++;
            Optional<LoanRecord.Agreement> agreement = governingFor(accrual.agreementId(), accrual.agreementVersion());
            if (agreement.isEmpty() || agreement.get().accrualEngine() != accrual.engine()) {
                diverged(Check.ACCRUAL, accrual.id());
                return;
            }
            if (!LoanReplayer.holds(LendingEngine.ACCRUAL, accrual.engine())) {
                diverged(Check.ENGINE_ABSENT, accrual.id());
                return;
            }
            Optional<LoanTerms> pinned = pinned(agreement.get());
            if (pinned.isEmpty()) {
                diverged(Check.ACCRUAL, accrual.id());
                return;
            }
            try {
                AccrualPeriod period = periodOf(accrual.date(), agreement.get(), pinned.get());
                AccrualPeriod stored = new AccrualPeriod(accrual.periodStart(), accrual.periodEnd());
                if (!period.equals(stored)) {
                    diverged(Check.ACCRUAL, accrual.id());
                    return;
                }
                AccrualTerms terms = new AccrualTerms(record.loan().currency(),
                        new BigDecimal(agreement.get().nominalAnnualRate()), pinned.get().dayCount(),
                        pinned.get().interestRounding(), LoanReplayer.version(LendingEngine.ACCRUAL, accrual.engine()));
                Optional<InterestAccrual> previous = previousInPeriod(accrual);
                Money base = base(accrual.date(), accrual.createdAt());
                InterestAccrual derived = LoanReplayer.accrue(terms, period, previous, accrual.date(), base);
                if (!derived.base().equals(accrual.base()) || !derived.posted().equals(accrual.posted())
                        || derived.cumulativeExact().compareTo(accrual.cumulativeExact()) != 0) {
                    diverged(Check.ACCRUAL, accrual.id());
                }
            } catch (LoanReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, accrual.id());
            } catch (RuntimeException refused) {
                diverged(Check.ACCRUAL, accrual.id());
            }
        }

        /** The period the account's calendar gives {@code date}: the schedule in force's, or the line's statement cycle. */
        private AccrualPeriod periodOf(LocalDate date, LoanRecord.Agreement agreement, LoanTerms pinned) {
            if (record.loan().kind() == LoanKind.INSTALMENT) {
                return calendar(schedule.orElseThrow(() -> new IllegalStateException("an accrual follows its schedule")))
                        .periodOf(date);
            }
            return LoanReplayer.cycle(statementTerms(agreement, pinned), openedOn(pinned), date);
        }

        /** The previous accrual of the same period - the engine's predecessor - as the step read it: the stored row. */
        private Optional<InterestAccrual> previousInPeriod(LoanRecord.Accrual accrual) {
            LoanRecord.Accrual previous = previousAccrual.get(accrual.id());
            if (previous == null || !previous.periodStart().equals(accrual.periodStart())
                    || !previous.periodEnd().equals(accrual.periodEnd())) {
                return Optional.empty();
            }
            return Optional.of(interestAccrual(previous));
        }

        private InterestAccrual interestAccrual(LoanRecord.Accrual row) {
            return new InterestAccrual(row.date(), new AccrualPeriod(row.periodStart(), row.periodEnd()), row.base(),
                    row.cumulativeExact().setScale(Act365Fixed.EXACT_SCALE), row.posted(),
                    LoanReplayer.version(LendingEngine.ACCRUAL, row.engine()));
        }

        /**
         * Principal outstanding at the end of {@code date} as the accrual step read it: {@code LOAN_PRINCIPAL +
         * LOAN_PRINCIPAL_DUE} over the lines dated on or before it, of the entries written before {@code before}.
         */
        private Money base(LocalDate date, Instant before) {
            Money base = zero;
            for (AccountPurpose purpose : List.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE)) {
                for (PurposeBalances.DatedLine line : linesOf(purpose)) {
                    if (!line.valueDate().isAfter(date) && writtenBefore(line.entry(), before)) {
                        base = line.direction() == Direction.DEBIT ? base.plus(line.amount()) : base.minus(line.amount());
                    }
                }
            }
            return base;
        }

        /** Every elapsed date between the accrual start and the latest accrual with principal outstanding has its row. */
        void accrualGaps() {
            if (record.accruals().isEmpty()) {
                return;
            }
            Optional<LocalDate> start = record.loan().kind() == LoanKind.INSTALMENT
                    ? record.accrualStart().map(LoanRecord.AccrualStartRow::startDate)
                    : record.agreements().stream().findFirst().flatMap(this::pinned).map(this::openedOn);
            if (start.isEmpty()) {
                return;
            }
            LocalDate expected = start.get();
            for (LoanRecord.Accrual accrual : record.accruals()) {
                for (LocalDate date = expected; date.isBefore(accrual.date()); date = date.plusDays(1)) {
                    if (base(date, accrual.createdAt()).isPositive()) {
                        diverged(Check.ACCRUAL_MISSING, accrual.id());
                        break;
                    }
                }
                expected = accrual.date().plusDays(1);
            }
        }

        // -------------------------------------------------------------- billings and statements

        private void billing(LoanRecord.Billing billing) {
            replayed++;
            try {
                Optional<LoanRecord.Agreement> agreement = governingFor(billing.agreementId(), billing.agreementVersion());
                LoanRecord.ScheduleVersion fixed = schedule.orElse(null);
                if (agreement.isEmpty() || fixed == null || !fixed.id().equals(billing.scheduleId())
                        || fixed.version() != billing.scheduleVersion() || fixed.engine() != billing.scheduleEngine()
                        || agreement.get().accrualEngine() != billing.accrualEngine()) {
                    diverged(Check.BILLING, billing.id());
                } else if (!LoanReplayer.holds(LendingEngine.SCHEDULE, billing.scheduleEngine())
                        || !LoanReplayer.holds(LendingEngine.ACCRUAL, billing.accrualEngine())) {
                    diverged(Check.ENGINE_ABSENT, billing.id());
                } else {
                    AccrualPeriod period = new AccrualPeriod(billing.periodStart(), billing.dueDate());
                    boolean candidate = candidate(billing, fixed, period);
                    Money interest = zero;
                    int rows = 0;
                    for (LoanRecord.Accrual accrual : record.accruals()) {
                        if (accrual.periodStart().equals(period.start()) && accrual.periodEnd().equals(period.endExclusive())
                                && accrual.createdAt().isBefore(billing.createdAt())) {
                            interest = interest.plus(accrual.posted());
                            rows++;
                        }
                    }
                    Money notDue = asOf(AccountPurpose.LOAN_PRINCIPAL, billing.createdAt());
                    Money principal = principalDue(billing, fixed, interest, notDue);
                    if (!candidate || ("POST_MATURITY".equals(billing.kind()) && rows == 0)
                            || !interest.equals(billing.interestDue()) || !principal.equals(billing.principalDue())) {
                        System.err.println("DEBUGBILLING candidate=" + candidate + " rows=" + rows + " interest=" + interest
                                + "/" + billing.interestDue() + " principal=" + principal + "/" + billing.principalDue()
                                + " notDue=" + notDue + " seq=" + billing.sequence() + " kind=" + billing.kind());
                        diverged(Check.BILLING, billing.id());
                    }
                }
            } catch (RuntimeException refused) {
                diverged(Check.BILLING, billing.id());
            }
            billing.instalment().ifPresent(billedInstalments::add);
            items.put(billing.id(), new Item(billing.dueDate(), billing.interestDue(), billing.principalDue()));
            applicationExpected(billing.id(), billing.createdAt());
        }

        /** The billed item is one the schedule in force names: an instalment of it, or a period of its calendar after it. */
        private boolean candidate(LoanRecord.Billing billing, LoanRecord.ScheduleVersion fixed, AccrualPeriod period) {
            if ("INSTALMENT".equals(billing.kind())) {
                return fixed.instalments().stream().anyMatch(instalment -> billing.instalment()
                        .filter(instalment.id()::equals).isPresent() && instalment.sequence() == billing.sequence()
                        && instalment.periodStart().equals(period.start())
                        && instalment.dueDate().equals(period.endExclusive()));
            }
            InstalmentCalendar calendar = calendar(fixed);
            LoanRecord.Instalment last = fixed.instalments().get(fixed.instalments().size() - 1);
            int sequence = fixed.instalments().size();
            AccrualPeriod continued = calendar.periodOf(last.dueDate());
            while (continued.start().isBefore(period.start())) {
                sequence++;
                continued = calendar.periodOf(continued.endExclusive());
            }
            return continued.equals(period) && billing.sequence() == sequence + 1 && billing.principalDue().isZero();
        }

        /** {@code max(0, A - interest)} capped at the principal not yet due; the final instalment all of it. */
        private Money principalDue(LoanRecord.Billing billing, LoanRecord.ScheduleVersion fixed, Money interest,
                Money notDue) {
            if ("POST_MATURITY".equals(billing.kind())) {
                return zero;
            }
            Money capped = notDue.isNegative() ? zero : notDue;
            if (billing.sequence() == fixed.instalments().get(fixed.instalments().size() - 1).sequence()) {
                return capped;
            }
            Money portion = fixed.instalment().minus(interest);
            if (!portion.isPositive()) {
                return zero;
            }
            return portion.compareTo(notDue) <= 0 ? portion : capped;
        }

        private void statement(LoanRecord.StatementRow statement) {
            replayed++;
            try {
                Optional<LoanRecord.Agreement> agreement =
                        governingFor(statement.agreementId(), statement.agreementVersion());
                Optional<LoanTerms> pinned = agreement.flatMap(this::pinned);
                if (agreement.isEmpty() || pinned.isEmpty()
                        || agreement.get().statementEngine().filter(n -> n == statement.statementEngine()).isEmpty()
                        || agreement.get().accrualEngine() != statement.accrualEngine()) {
                    diverged(Check.STATEMENT, statement.id());
                } else {
                    StatementTerms terms = statementTerms(agreement.get(), pinned.get());
                    LocalDate openedOn = openedOn(pinned.get());
                    LocalDate start = lastStatement.map(LoanRecord.StatementRow::cycleEnd).orElse(openedOn);
                    AccrualPeriod cycle = LoanReplayer.cycle(terms, openedOn, start);
                    List<InterestAccrual> accruals = new ArrayList<>();
                    for (LoanRecord.Accrual accrual : record.accruals()) {
                        if (accrual.periodStart().equals(cycle.start()) && accrual.periodEnd().equals(cycle.endExclusive())
                                && accrual.createdAt().isBefore(statement.createdAt())) {
                            accruals.add(interestAccrual(accrual));
                        }
                    }
                    Money notDueAtClose = zero;
                    Money dueAtClose = zero;
                    LocalDate close = cycle.endExclusive().minusDays(1);
                    for (PurposeBalances.DatedLine line : record.journalLines()) {
                        if (line.valueDate().isAfter(close) || !writtenBefore(line.entry(), statement.createdAt())) {
                            continue;
                        }
                        Money signed = line.direction() == Direction.DEBIT ? line.amount() : line.amount().negated();
                        if (line.account().equals(accounts.get(AccountPurpose.LOAN_PRINCIPAL))) {
                            notDueAtClose = notDueAtClose.plus(signed);
                        } else if (line.account().equals(accounts.get(AccountPurpose.LOAN_PRINCIPAL_DUE))) {
                            dueAtClose = dueAtClose.plus(signed);
                        }
                    }
                    Money notDueNow = asOf(AccountPurpose.LOAN_PRINCIPAL, statement.createdAt());
                    Money cap = notDueAtClose.compareTo(notDueNow) <= 0 ? notDueAtClose : notDueNow;
                    Statement derived = LoanReplayer.state(terms, cycle, accruals, feesCarried(cycle, statement.createdAt()),
                            cap, notDueAtClose.plus(dueAtClose).minus(cap));
                    if (!cycle.start().equals(statement.cycleStart()) || !cycle.endExclusive().equals(statement.cycleEnd())
                            || !derived.interestBilled().equals(statement.interestBilled())
                            || !derived.feesCarried().equals(statement.feesCarried())
                            || !derived.principalBilled().equals(statement.principalBilled())
                            || !derived.minimumPayment().equals(statement.minimumPayment())
                            || !derived.dueDate().equals(statement.dueDate())) {
                        diverged(Check.STATEMENT, statement.id());
                    }
                }
            } catch (LoanReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, statement.id());
            } catch (RuntimeException refused) {
                diverged(Check.STATEMENT, statement.id());
            }
            lastStatement = Optional.of(statement);
            items.put(statement.id(), new Item(statement.dueDate(), statement.interestBilled(),
                    statement.principalBilled()));
            applicationExpected(statement.id(), statement.createdAt());
        }

        /** The fees assessed in the cycle before the statement, less those waived before it - carried by the statement. */
        private Money feesCarried(AccrualPeriod cycle, Instant before) {
            Money carried = zero;
            Set<UUID> inCycle = new HashSet<>();
            for (LoanRecord.Fee fee : record.fees()) {
                if (cycle.contains(fee.businessDate()) && fee.createdAt().isBefore(before)) {
                    carried = carried.plus(fee.amount());
                    inCycle.add(fee.id());
                }
            }
            for (LoanRecord.WaiverRow waiver : record.waivers()) {
                if (waiver.kind() == WaiverKind.FEE && waiver.approved()
                        && waiver.feeAssessmentId().filter(inCycle::contains).isPresent()
                        && waiver.decidedAt().orElseThrow().isBefore(before)) {
                    carried = carried.minus(waiver.amount());
                }
            }
            return carried;
        }

        /** After an item is billed, the step applies a credit balance held to what is due - its absence is a finding. */
        private void applicationExpected(UUID item, Instant billedAt) {
            boolean applied = record.applications().stream().anyMatch(application -> application.billingId().equals(item));
            if (applied) {
                return;
            }
            Money credit = asOf(AccountPurpose.LOAN_CREDIT_BALANCE, billedAt).negated();
            if (credit.isPositive() && items.values().stream().anyMatch(due -> due.total().isPositive())) {
                diverged(Check.APPLICATION_MISSING, item);
            }
        }

        // -------------------------------------------------------------- the payers

        private void application(LoanRecord.Application application) {
            replayed++;
            try {
                Optional<LoanRecord.Agreement> agreement =
                        governingFor(application.agreementId(), application.agreementVersion());
                Optional<AllocationRules> rules = agreement.flatMap(found -> rules(found, application.allocationEngine()));
                if (rules.isEmpty()) {
                    diverged(Check.APPLICATION, application.id());
                } else {
                    AllocationRules settling = new AllocationRules(rules.get().engine(), rules.get().sequence(),
                            rules.get().order(), OverpaymentTreatment.HOLD_AS_CREDIT);
                    Money credit = asOf(AccountPurpose.LOAN_CREDIT_BALANCE, application.createdAt()).negated();
                    List<AllocationLine> derived = new ArrayList<>();
                    Money total = zero;
                    for (AllocationLine line : LoanReplayer.allocate(credit, dueState(application.createdAt()), settling)) {
                        if (line.target() == AllocationLine.Target.DUE_ITEM) {
                            derived.add(line);
                            total = total.plus(line.amount());
                        }
                    }
                    boolean same = derived.size() == application.lines().size() && total.equals(application.amount());
                    for (int i = 0; same && i < derived.size(); i++) {
                        AllocationLine line = derived.get(i);
                        LoanRecord.ApplicationLine row = application.lines().get(i);
                        same = row.ordinal() == i + 1 && line.item().orElseThrow().value().equals(row.billingId())
                                && line.component().orElseThrow() == row.component() && line.amount().equals(row.amount());
                    }
                    if (!same) {
                        diverged(Check.APPLICATION, application.id());
                    }
                }
            } catch (LoanReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, application.id());
            } catch (RuntimeException refused) {
                diverged(Check.APPLICATION, application.id());
            }
            for (LoanRecord.ApplicationLine line : application.lines()) {
                item(line.billingId()).ifPresent(due -> due.pay(line.component(), line.amount()));
            }
        }

        private void payment(LoanRecord.Payment payment) {
            replayed++;
            try {
                Optional<LoanRecord.Agreement> agreement = governingFor(payment.agreementId(), payment.agreementVersion());
                Optional<AllocationRules> rules = agreement.flatMap(found -> rules(found, payment.allocationEngine()));
                if (rules.isEmpty()) {
                    diverged(Check.ALLOCATION, payment.id());
                } else {
                    DueState state = dueState(payment.createdAt());
                    List<AllocationLine> derived = payment.kind() == RepaymentKind.PREPAYMENT
                            ? LoanReplayer.allocatePrepayment(payment.amount(), state, rules.get())
                            : LoanReplayer.allocate(payment.amount(), state, rules.get());
                    boolean same = derived.size() == payment.lines().size();
                    for (int i = 0; same && i < derived.size(); i++) {
                        AllocationLine line = derived.get(i);
                        LoanRecord.PaymentLine row = payment.lines().get(i);
                        same = row.ordinal() == i + 1 && line.target() == row.target()
                                && line.item().map(DueItemId::value).equals(row.billingId())
                                && line.component().equals(row.component()) && line.amount().equals(row.amount());
                    }
                    if (!same) {
                        diverged(Check.ALLOCATION, payment.id());
                    }
                }
            } catch (LoanReplayer.EngineAbsent absent) {
                diverged(Check.ENGINE_ABSENT, payment.id());
            } catch (RuntimeException refused) {
                diverged(Check.ALLOCATION, payment.id());
            }
            for (LoanRecord.PaymentLine line : payment.lines()) {
                if (line.target() == AllocationLine.Target.DUE_ITEM) {
                    item(line.billingId().orElseThrow()).ifPresent(due -> due.pay(line.component().orElseThrow(),
                            line.amount()));
                }
            }
        }

        /** The due state just before {@code before}: the items' billed less paid, and the principal not yet due. */
        private DueState dueState(Instant before) {
            List<DueState.DueItem> due = new ArrayList<>();
            items.forEach((id, item) -> {
                if (item.total().isPositive() || item.total().isNegative()) {
                    due.add(new DueState.DueItem(DueItemId.of(id), item.dueDate, item.fees(), item.interest(),
                            item.principal()));
                }
            });
            return new DueState(due, asOf(AccountPurpose.LOAN_PRINCIPAL, before));
        }

        private Optional<AllocationRules> rules(LoanRecord.Agreement agreement, int engine) {
            if (agreement.allocationEngine() != engine) {
                return Optional.empty();
            }
            LoanReplayer.version(LendingEngine.ALLOCATION, engine);
            return pinned(agreement).map(AllocationRules::of)
                    .filter(rules -> rules.engine().number() == engine);
        }

        // -------------------------------------------------------------- fees, waivers, reversals, arrears, closure

        private void fee(LoanRecord.Fee fee) {
            replayed++;
            Optional<LoanRecord.Agreement> agreement = governingFor(fee.agreementId(), fee.agreementVersion());
            Optional<LoanTerms> pinned = agreement.flatMap(this::pinned);
            Optional<Item> item = item(fee.dueItemId());
            if (pinned.isEmpty() || item.isEmpty() || !"LATE".equals(fee.kind())) {
                diverged(Check.FEE, fee.id());
            } else {
                Money room = pinned.get().lateFeeCap().minus(feesAssessed);
                Money expected = pinned.get().lateFee().compareTo(room) <= 0 ? pinned.get().lateFee() : room;
                if (!room.isPositive() || !expected.equals(fee.amount())
                        || !item.get().interest().plus(item.get().principal()).isPositive()
                        || ChronoUnit.DAYS.between(item.get().dueDate, fee.businessDate()) < pinned.get().lateFeeDay()) {
                    diverged(Check.FEE, fee.id());
                }
            }
            feesAssessed = feesAssessed.plus(fee.amount());
            item.ifPresent(due -> due.feesBilled = due.feesBilled.plus(fee.amount()));
        }

        private void waiver(LoanRecord.WaiverRow waiver) {
            replayed++;
            Optional<Item> item = item(waiver.dueItemId());
            if (item.isEmpty()) {
                diverged(Check.WAIVER, waiver.id());
                return;
            }
            Item due = item.get();
            switch (waiver.kind()) {
                case FEE -> {
                    if (waiver.amount().compareTo(due.fees()) > 0) {
                        diverged(Check.WAIVER, waiver.id());
                    }
                    due.feesBilled = due.feesBilled.minus(waiver.amount());
                }
                case INTEREST -> {
                    if (waiver.amount().compareTo(due.interest()) > 0) {
                        diverged(Check.WAIVER, waiver.id());
                    }
                    due.interestBilled = due.interestBilled.minus(waiver.amount());
                }
                case FEE_REFUND -> {
                    UUID assessment = waiver.feeAssessmentId().orElse(waiver.id());
                    Money refunded = refundedPerAssessment.getOrDefault(assessment, zero);
                    if (waiver.amount().compareTo(due.feesPaid.minus(refunded)) > 0) {
                        diverged(Check.WAIVER, waiver.id());
                    }
                    refundedPerAssessment.put(assessment, refunded.plus(waiver.amount()));
                }
            }
        }

        private void reversal(LoanRecord.Reversal reversal) {
            replayed++;
            LoanRecord.Payment reversed = payment(reversal.repaymentId());
            Map<UUID, Money> negated = new HashMap<>();
            reversal.negations().forEach(negation -> negated.put(negation.allocationId(), negation.amount()));
            boolean exact = negated.size() == reversed.lines().size() && reversed.kind() != RepaymentKind.PREPAYMENT;
            for (LoanRecord.PaymentLine line : reversed.lines()) {
                exact &= line.amount().equals(negated.get(line.id()));
            }
            if (!exact) {
                diverged(Check.REVERSAL, reversal.id());
            }
            for (LoanRecord.Negation negation : reversal.negations()) {
                LoanRecord.PaymentLine line = paymentLines.get(negation.allocationId());
                if (line != null && line.target() == AllocationLine.Target.DUE_ITEM) {
                    item(line.billingId().orElseThrow()).ifPresent(due -> due.pay(line.component().orElseThrow(),
                            negation.amount().negated()));
                }
            }
        }

        private void rescheduling(LoanRecord.Rescheduling rescheduling) {
            replayed++;
            Optional<Item> item = item(rescheduling.billingId());
            Optional<LocalDate> effective = record.journalLines().stream()
                    .filter(line -> line.entry().equals(rescheduling.entry())).map(PurposeBalances.DatedLine::valueDate)
                    .findFirst();
            if (item.isEmpty() || effective.isEmpty() || !item.get().principal().equals(rescheduling.principal())
                    || !item.get().dueDate.isBefore(effective.get())) {
                diverged(Check.RESCHEDULING, rescheduling.id());
            }
            item.ifPresent(due -> due.principalBilled = due.principalBilled.minus(rescheduling.principal()));
        }

        private void closure(LoanRecord.Closure closure) {
            replayed++;
            Money held = asOf(AccountPurpose.LOAN_CREDIT_BALANCE, closure.createdAt()).negated();
            if (!held.equals(closure.refund())) {
                diverged(Check.CLOSURE, closure.id());
            }
        }

        // -------------------------------------------------------------- plumbing

        /** The governing agreement at this point of the walk, when it is the one the row names. */
        private Optional<LoanRecord.Agreement> governingFor(UUID agreementId, int version) {
            return governing.filter(agreement -> agreement.id().equals(agreementId) && agreement.version() == version);
        }

        private Optional<LoanTerms> pinned(LoanRecord.Agreement agreement) {
            return record.termsVersions().getOrDefault(agreement.termsVersionId(), Optional.empty())
                    .map(LoanTermsStore.LoanTermsVersion::terms);
        }

        private StatementTerms statementTerms(LoanRecord.Agreement agreement, LoanTerms pinned) {
            StatementTerms terms = StatementTerms.of(pinned, agreement.repaymentDay());
            int engine = agreement.statementEngine().orElseThrow(() -> new IllegalStateException("a line pins a statement"
                    + " engine"));
            if (terms.statementEngine().number() != engine) {
                throw new IllegalStateException("the agreement and its terms name one statement engine");
            }
            LoanReplayer.version(LendingEngine.STATEMENT, engine);
            return terms;
        }

        private LocalDate openedOn(LoanTerms pinned) {
            return LocalDate.ofInstant(record.loan().openedAt(), ZoneId.of(pinned.servicingZone()));
        }

        private static InstalmentCalendar calendar(LoanRecord.ScheduleVersion fixed) {
            List<AccrualPeriod> periods = new ArrayList<>();
            for (LoanRecord.Instalment instalment : fixed.instalments()) {
                periods.add(new AccrualPeriod(instalment.periodStart(), instalment.dueDate()));
            }
            return new InstalmentCalendar(periods, fixed.intendedDay());
        }

        private LoanRecord.ScheduleVersion scheduleOf(LoanRecord.Agreement agreement) {
            return record.schedules().stream().filter(found -> found.agreementId().equals(agreement.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("a later version commits beside its schedule"));
        }

        private LoanRecord.Agreement previous(LoanRecord.Agreement agreement) {
            return record.agreements().stream().filter(found -> found.version() == agreement.version() - 1).findFirst()
                    .orElseThrow(() -> new IllegalStateException("a later version follows its predecessor"));
        }

        private LoanRecord.Payment payment(UUID id) {
            return record.repayments().stream().filter(found -> found.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("a row names a repayment of its loan"));
        }

        private Optional<Item> item(UUID id) {
            return Optional.ofNullable(items.get(id));
        }

        /** Whether {@code entry} was written before {@code before} - an entry no row names counts as always written. */
        private boolean writtenBefore(JournalEntryId entry, Instant before) {
            LoanSubledgerProof.Namer namer = named.get(entry);
            return namer == null || namer.at().isBefore(before);
        }

        /** {@code purpose}'s balance (DR - CR) over the entries written before {@code before}. */
        private Money asOf(AccountPurpose purpose, Instant before) {
            Money balance = zero;
            for (PurposeBalances.DatedLine line : linesOf(purpose)) {
                if (writtenBefore(line.entry(), before)) {
                    balance = line.direction() == Direction.DEBIT ? balance.plus(line.amount())
                            : balance.minus(line.amount());
                }
            }
            return balance;
        }

        /** One billed item, rebuilt: billed (with its fees, less waivers and re-scheduled arrears) and paid. */
        private final class Item {

            private final LocalDate dueDate;
            private Money feesBilled = zero;
            private Money interestBilled;
            private Money principalBilled;
            private Money feesPaid = zero;
            private Money interestPaid = zero;
            private Money principalPaid = zero;

            Item(LocalDate dueDate, Money interestBilled, Money principalBilled) {
                this.dueDate = dueDate;
                this.interestBilled = interestBilled;
                this.principalBilled = principalBilled;
            }

            void pay(AllocationComponent component, Money amount) {
                switch (component) {
                    case FEES -> feesPaid = feesPaid.plus(amount);
                    case INTEREST -> interestPaid = interestPaid.plus(amount);
                    case PRINCIPAL -> principalPaid = principalPaid.plus(amount);
                }
            }

            Money fees() {
                return feesBilled.minus(feesPaid);
            }

            Money interest() {
                return interestBilled.minus(interestPaid);
            }

            Money principal() {
                return principalBilled.minus(principalPaid);
            }

            Money total() {
                return fees().plus(interest()).plus(principal());
            }
        }
    }
}
