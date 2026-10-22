package com.finapp.bnpl;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The hermetic world of the BNPL proofs (`P12-TSK-025`): {@link BnplCorrectionWorld} - T-pur, T-svc with its collection,
 * condition and late-fee slot, T-rep, T-ref, T-rev and T-wvr over {@link BnplApplicationFakes} - with the real T-acc
 * ({@link BnplAccruals}), T-pay ({@link BnplPayoffs}) and T-dsp ({@link BnplDisputes}) beside them, so every history a
 * proof reads is one the domain itself wrote. {@link Records} reads an obligation's whole {@link BnplRecord} from the fakes'
 * state - each fact stamped in the order it was written ({@link BnplApplicationFakes#written}) - and {@link Reads} answers
 * the three-way proof's outside reads from the fakes' book; {@link #proofs} is the production composition over both.
 *
 * <p>Every case reads on {@link #SNAPSHOT}, a connection that says what a read-only {@code REPEATABLE READ} snapshot says and
 * does nothing else - the proofs refuse any other. The database's own reading is {@code BnplProofDatabaseTest}'s.
 */
@SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
final class BnplProofWorld {

    /** A connection that is a read-only {@code REPEATABLE READ} snapshot and nothing more. */
    static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(BnplProofWorld.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isReadOnly" -> true;
                case "getAutoCommit" -> false;
                case "getTransactionIsolation" -> Connection.TRANSACTION_REPEATABLE_READ;
                default -> throw new UnsupportedOperationException("the hermetic snapshot answers only its shape");
            });

    final BnplCorrectionWorld corrections = new BnplCorrectionWorld();
    final BnplConditionWorld base = corrections.base;
    final BnplApplicationFakes fakes = corrections.fakes;
    final BnplAccruals accruals = new BnplAccruals(fakes.servicing, fakes.accruals, fakes.purchases, fakes.terms.store(),
            fakes.book, BnplConditionWorld.IDS);
    final BnplPayoffs payoffs = fakes.payoffsOver(base.servicing, base.repayments, base.closures, BnplConditionWorld.IDS);
    final BnplDisputes disputes = fakes.disputesOver(base.refunds, corrections.actingParties, BnplConditionWorld.IDS,
            BnplConditionWorld.CLOCK);
    final Records records = new Records();
    final Reads reads = new Reads();
    final BnplProofs proofs = new BnplProofs(records, new BnplSubledgerProof(), new BnplReplayProof(),
            new BnplThreeWayProof(reads, fakes.merchants));

    // ------------------------------------------------------------------ acts

    /** T-acc caught up as at 08:30 UTC on {@code date} - every elapsed date before it - then the servicing step at 09:00. */
    ServicingOutcome stepOn(LocalDate date, BnplConditionWorld.Agreement agreement) {
        accrueOn(date, agreement);
        return base.serviceOn(date, agreement);
    }

    /** T-acc as at 08:30 UTC on {@code date}, until caught up. */
    void accrueOn(LocalDate date, BnplConditionWorld.Agreement agreement) {
        fakes.now = date.atTime(8, 30).toInstant(ZoneOffset.UTC);
        try (SecurityContext.Scope system = SecurityContext.enter(BnplConditionWorld.SYSTEM)) {
            for (int call = 0; call < 32; call++) {
                BnplAccruals.Outcome outcome = fakes.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW,
                        agreement.obligation()));
                if (outcome.caughtUp()) {
                    return;
                }
            }
        }
        throw new IllegalStateException("T-acc never caught up");
    }

    /** Every servicing step from {@code from} through {@code to}, each with T-acc first. */
    void stepThrough(LocalDate from, LocalDate to, BnplConditionWorld.Agreement agreement) {
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            stepOn(date, agreement);
        }
    }

    /** The customer repays exactly what every billed instalment has due on {@code date}, under {@code key}. */
    BnplRepayments.Repaid repayDueOn(LocalDate date, BnplConditionWorld.Agreement agreement, String key) {
        long due = 0;
        for (BnplPurchaseStore.BilledItem item : fakes.purchases.billedItems(BnplApplicationFakes.UOW,
                agreement.obligation())) {
            due += item.dueTotal().minorUnits();
        }
        return base.repayOn(date, agreement, key, due);
    }

    BnplPayoffs.Quoted quoteOn(LocalDate date, BnplConditionWorld.Agreement agreement, String key) {
        return quoteOn(date, agreement, key, Optional.empty());
    }

    /** A quote made on {@code date}, good through {@code goodThrough} (today when absent) - its projection beyond. */
    BnplPayoffs.Quoted quoteOn(LocalDate date, BnplConditionWorld.Agreement agreement, String key,
            Optional<LocalDate> goodThrough) {
        accrueOn(date, agreement);
        fakes.now = date.atTime(10, 0).toInstant(ZoneOffset.UTC);
        return fakes.inTransaction(() -> payoffs.quote(BnplApplicationFakes.UOW, agreement.agreement(), base.party,
                new BnplPayoffs.QuoteRequest(key, goodThrough), BnplConditionWorld.CORRELATION));
    }

    BnplPayoffs.Executed payOffOn(LocalDate date, BnplConditionWorld.Agreement agreement, String key) {
        UUID quote = quoteOn(date, agreement, key + "-quote").quote().id();
        fakes.now = date.atTime(10, 30).toInstant(ZoneOffset.UTC);
        return fakes.inTransaction(() -> payoffs.execute(BnplApplicationFakes.UOW, agreement.agreement(), base.party, key,
                quote, base.customer, BnplConditionWorld.CORRELATION));
    }

    /** A dispute of {@code minor} opened at 12:00 and accepted by the merchant at 13:00 on {@code date} - a refund. */
    BnplDisputes.Acted disputeRefundedOn(LocalDate date, BnplConditionWorld.Agreement agreement, String key, long minor) {
        fakes.now = date.atTime(12, 0).toInstant(ZoneOffset.UTC);
        BnplDispute dispute = fakes.inTransaction(() -> disputes.open(BnplApplicationFakes.UOW, agreement.agreement(),
                base.party, new BnplDisputes.OpenRequest(key, BnplConditionWorld.eur(minor),
                        BnplDisputeReason.NOT_RECEIVED, "not received"), base.customer,
                BnplConditionWorld.CORRELATION)).dispute();
        fakes.now = date.atTime(13, 0).toInstant(ZoneOffset.UTC);
        return fakes.inTransaction(() -> disputes.respond(BnplApplicationFakes.UOW, dispute.id(), base.merchant,
                new BnplDisputes.Response(BnplDisputes.Decision.ACCEPT, "we accept", "merchant-key-1",
                        BnplKeyIssuer.KEY_ISSUER), base.merchantActor, BnplConditionWorld.CORRELATION));
    }

    // ------------------------------------------------------------------ readings

    /** One whole reading on the snapshot. */
    BnplProofs.Reading reading() {
        return proofs.prove(SNAPSHOT);
    }

    /** {@code agreement}'s record as the fakes hold it now. */
    BnplRecord record(BnplConditionWorld.Agreement agreement) {
        return records.read(SNAPSHOT, agreement.obligation()).orElseThrow();
    }

    BnplSubledgerProof.Verdict subledger(BnplConditionWorld.Agreement agreement) {
        return new BnplSubledgerProof().prove(record(agreement));
    }

    BnplReplayProof.Replay replay(BnplConditionWorld.Agreement agreement) {
        return new BnplReplayProof().replay(record(agreement));
    }

    /** {@code work} as {@code actor} - an operator act. */
    <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor)) {
            return work.get();
        }
    }

    // ------------------------------------------------------------------ the record, from the fakes

    /** The order a fact was written in, as a database instant. */
    Instant at(Object key) {
        Long order = fakes.written.get(key);
        if (order == null) {
            throw new IllegalStateException("the fakes recorded no write of " + key);
        }
        return Instant.parse("2001-01-01T00:00:00Z").plusMillis(order);
    }

    /** {@link BnplRecordStore} over the fakes' state. */
    final class Records implements BnplRecordStore {

        @Override
        public List<BnplObligationId> obligationIds(Connection unitOfWork) {
            return fakes.purchases.obligations.values().stream().map(BnplObligation::id).toList();
        }

        @Override
        public Optional<BnplRecord> read(Connection unitOfWork, BnplObligationId id) {
            BnplObligation obligation = fakes.purchases.obligations.get(id.value());
            if (obligation == null) {
                return Optional.empty();
            }
            BnplPurchase purchase = fakes.purchases.rows.get(obligation.purchase().value());
            MerchantFinancing financing = fakes.purchases.financings.get(purchase.id().value());
            List<BnplRefund> refunds = fakes.refunds.rows.values().stream()
                    .filter(row -> row.obligation().equals(id)).sorted(Comparator.comparing(row -> at(row.id()))).toList();

            List<BnplRecord.Agreement> agreements = new ArrayList<>();
            fakes.purchases.everyAgreement.values().stream()
                    .filter(row -> row.id().equals(obligation.agreement()))
                    .sorted(Comparator.comparingInt(BnplAgreement::version))
                    .forEach(row -> {
                        Optional<BnplRefund> made = row.version() == 1 ? Optional.empty()
                                : refunds.stream().filter(refund -> refund.agreementVersion() == row.version()).findFirst();
                        agreements.add(new BnplRecord.Agreement(row.stampedAt(at("agreement:" + row.id().value() + ":"
                                + row.version())), made.map(BnplRefund::id),
                                made.map(BnplRefund::scheduleVersion).orElse(1)));
                    });
            Map<BnplTermsVersionId, Optional<BnplTermsVersion>> versions = new java.util.LinkedHashMap<>();
            agreements.forEach(agreement -> versions.put(agreement.row().termsVersionId(),
                    Optional.ofNullable(fakes.terms.versions.get(agreement.row().termsVersionId()))));

            List<BnplRecord.Schedule> schedules = new ArrayList<>();
            fakes.purchases.versions.values().stream().filter(row -> row.obligation().equals(id))
                    .sorted(Comparator.comparingInt(BnplPurchaseStore.StoredSchedule::version))
                    .forEach(row -> schedules.add(new BnplRecord.Schedule(row, row.version() == 1 ? Optional.empty()
                            : refunds.stream().filter(refund -> refund.scheduleVersion() == row.version()).findFirst()
                                    .map(BnplRefund::id), at(row.id()))));

            Optional<BnplPayoffExecution> execution = fakes.payoffs.executions.values().stream()
                    .filter(row -> row.obligation().equals(id)).findFirst();
            List<BnplRecord.Billing> billings = fakes.purchases.billings.values().stream()
                    .filter(row -> row.obligation().equals(id)).map(row -> {
                        Instant written = at(row.id());
                        Optional<UUID> early = execution.filter(found -> at(found.id()).isBefore(written)
                                && row.dueDate().isAfter(found.businessDate())).map(BnplPayoffExecution::id);
                        return new BnplRecord.Billing(row, LocalDate.ofInstant(fakes.writtenOn.get(row.id()),
                                ZoneOffset.UTC), early, written);
                    }).toList();

            List<BnplRecord.Reversal> reversals = new ArrayList<>();
            for (BnplRepaymentReversal row : fakes.corrections.reversals.values()) {
                if (!row.obligation().equals(id)) {
                    continue;
                }
                BnplRepaymentReversal stamped = row.decidedAt().isEmpty() ? row : new BnplRepaymentReversal(row.id(),
                        row.obligation(), row.repayment(), row.agreement(), row.agreementVersion(), row.status(),
                        row.proposedBy(), row.proposedAt(), row.proposalReason(), row.decidedBy(),
                        Optional.of(at("decided:" + row.id())), row.decisionReason(), row.entry(), row.valueDate());
                List<BnplRecord.Negation> negations = new ArrayList<>();
                fakes.corrections.negations.forEach((allocation, reversal) -> {
                    if (reversal.equals(row.id())) {
                        BnplPurchaseStore.Allocation line = fakes.purchases.repayments.get(row.repayment()).allocations()
                                .stream().filter(found -> found.id().equals(allocation)).findFirst().orElseThrow();
                        negations.add(new BnplRecord.Negation(allocation, line.amount()));
                    }
                });
                // bnpl V019's releases (the Phase 12 transition gate's A7-3), as JdbcBnplRecordStore reads them.
                List<BnplRecord.FeeRelease> releases = fakes.corrections.feeReleases.stream()
                        .filter(release -> release.reversal().equals(row.id()))
                        .map(release -> new BnplRecord.FeeRelease(release.instalment(), release.amount(), release.entry()))
                        .toList();
                reversals.add(new BnplRecord.Reversal(stamped, negations, releases));
            }
            List<BnplWaiver> waivers = fakes.corrections.waiverRows.values().stream()
                    .filter(row -> row.obligation().equals(id))
                    .map(row -> row.decidedAt().isEmpty() ? row : new BnplWaiver(row.id(), row.obligation(), row.kind(),
                            row.assessment(), row.instalment(), row.amount(), row.agreement(), row.agreementVersion(),
                            row.status(), row.proposedBy(), row.proposedAt(), row.proposalReason(), row.decidedBy(),
                            Optional.of(at("decided:" + row.id())), row.decisionReason(), row.entry())).toList();

            List<LedgerAccount> accounts = fakes.book.accountsOf(unitOfWork, id);
            Set<LedgerAccountId> ids = new LinkedHashSet<>();
            accounts.forEach(account -> ids.add(account.id()));
            List<BnplRecord.EntryLines> shared = new ArrayList<>();
            shared.add(new BnplRecord.EntryLines(financing.entry(), fakes.book.entryLines(unitOfWork, financing.entry())));
            refunds.forEach(refund -> shared.add(new BnplRecord.EntryLines(refund.entry(),
                    fakes.book.entryLines(unitOfWork, refund.entry()))));
            Optional<BnplClosure> closure = Optional.ofNullable(fakes.repayments.closures.get(id.value()));
            return Optional.of(new BnplRecord(obligation, closure.map(found -> at(found.id())), purchase, financing,
                    at("financing:" + purchase.id().value()), Optional.ofNullable(fakes.offers.rows.get(purchase.offer()
                            .value())), Optional.ofNullable(fakes.offers.terms.get(purchase.offer().value())),
                    agreements, versions, schedules, billings,
                    stamped(fakes.accruals.rows.stream().filter(row -> row.obligation().equals(id)).toList(),
                            BnplInterestAccrual::id),
                    stamped(fakes.servicing.applications.values().stream().filter(row -> row.obligation().equals(id))
                            .toList(), BnplCreditBalanceApplication::id),
                    stamped(fakes.purchases.repayments.values().stream().filter(row -> row.obligation().equals(id))
                            .toList(), BnplPurchaseStore.Repayment::id),
                    stamped(fakes.collections.rows.stream().filter(row -> row.obligation().equals(id)).toList(),
                            BnplCollectionAttempt::id),
                    stamped(refunds, BnplRefund::id),
                    stamped(fakes.conditions.assessments.stream().filter(row -> row.obligation().equals(id)).toList(),
                            BnplFeeAssessment::id),
                    stamped(fakes.conditions.events.stream().filter(row -> row.obligation().equals(id)).toList(),
                            BnplConditionEvent::id),
                    stamped(fakes.payoffs.quotes.values().stream().filter(row -> row.obligation().equals(id)).toList(),
                            BnplPayoffQuote::id),
                    execution.map(found -> new BnplRecord.Stamped<>(found, at(found.id()))),
                    fakes.disputes.rows.values().stream().filter(row -> row.obligation().equals(id)).toList(),
                    reversals, waivers, closure.map(found -> new BnplRecord.Stamped<>(found, at(found.id()))),
                    accounts.stream().map(account -> new BnplRecord.LedgerAccountRow(account.id(), account.purpose(),
                            account.currency())).toList(),
                    ids.isEmpty() ? List.of() : fakes.book.datedLinesOn(unitOfWork, ids, LocalDate.of(9999, 12, 31)),
                    shared, Optional.ofNullable(fakes.merchants.payables.get(purchase.merchant())),
                    Optional.of(fakes.book.operational(unitOfWork, AccountPurpose.BNPL_MERCHANT_FEE_INCOME,
                            obligation.currency()))));
        }

        private <T> List<BnplRecord.Stamped<T>> stamped(List<T> rows, java.util.function.Function<T, UUID> id) {
            return rows.stream().map(row -> new BnplRecord.Stamped<>(row, at(id.apply(row))))
                    .sorted(Comparator.comparing(BnplRecord.Stamped::at)).toList();
        }
    }

    // ------------------------------------------------------------------ the outside reads, from the fakes

    /** {@link BnplProofReads} over the fakes' book: each payable's {@code bnpl.}-keyed lines, the trial balance, two flags. */
    final class Reads implements BnplProofReads {

        /** {@code INV-MER-02}'s equation as merchant's drill-down would judge it - true unless a case breaks it. */
        boolean equationHolds = true;
        boolean capitalHolds = true;
        boolean exposureHolds = true;

        @Override
        public Optional<PayableLeg> payableLeg(Connection snapshot, UUID merchant, CurrencyCode currency) {
            LedgerAccountId payable = fakes.merchants.payables.get(merchant);
            if (payable == null) {
                return Optional.empty();
            }
            List<KeyedLine> lines = new ArrayList<>();
            for (BnplApplicationFakes.Book.Line line : fakes.book.lines) {
                if (line.account().equals(payable) && line.key().startsWith("bnpl.")) {
                    lines.add(new KeyedLine(line.entry(), line.direction(), line.amount(),
                            line.key().substring(0, line.key().indexOf(':') + 1)));
                }
            }
            return Optional.of(new PayableLeg(payable, lines, equationHolds));
        }

        @Override
        public boolean trialBalanced(Connection snapshot, CurrencyCode currency) {
            Money net = Money.ofMinorUnits(0, currency);
            for (BnplApplicationFakes.Book.Line line : fakes.book.lines) {
                if (line.amount().currency().equals(currency)) {
                    net = line.direction() == Direction.DEBIT ? net.plus(line.amount()) : net.minus(line.amount());
                }
            }
            return net.isZero();
        }

        @Override
        public boolean capitalHolds(Connection snapshot, CurrencyCode currency) {
            return capitalHolds;
        }

        @Override
        public boolean exposureHolds(Connection snapshot) {
            return exposureHolds;
        }
    }

    // ------------------------------------------------------------------ plants - a world broken on purpose

    /** A raw posting on {@code account} and {@code against}, under {@code key}, with no row naming it. */
    JournalEntryId plantPosting(String key, LedgerAccountId account, LedgerAccountId against, long minor, LocalDate date) {
        JournalEntryId entry = JournalEntryId.of(BnplConditionWorld.IDS.next());
        Money amount = BnplConditionWorld.eur(minor);
        fakes.book.lines.add(new BnplApplicationFakes.Book.Line(key, account, Direction.DEBIT, amount, date, date, entry));
        fakes.book.lines.add(new BnplApplicationFakes.Book.Line(key, against, Direction.CREDIT, amount, date, date, entry));
        return entry;
    }
}
