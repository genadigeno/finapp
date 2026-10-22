package com.finapp.bnpl;

import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.Act365Fixed;
import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The JDBC reader of a BNPL obligation's whole record (`P12-TSK-025`; lending's {@code JdbcLoanRecordStore}): one plain
 * {@code SELECT} per bnpl table, every row of the obligation in a fixed order, the stores bnpl already has where they read
 * a whole list (refunds and their adjustments, fee assessments, reversals, waivers, disputes, the payoff, the closure,
 * schedules, the offer and the pinned terms), and the ledger through {@link BnplBook} - the obligation's six accounts, every
 * journal line on them in one statement ({@code datedLinesOn}), and the lines of its financing's and refunds' entries
 * ({@code entryLines}) - never a ledger table read here, never the projection. Writes nothing; the caller holds ONE
 * {@code REPEATABLE READ} snapshot.
 */
@RequiredArgsConstructor
public final class JdbcBnplRecordStore implements BnplRecordStore {

    /** Every journal line, whatever its value date: the latest date the database and the driver both carry. */
    static final LocalDate EVERY_VALUE_DATE = LocalDate.of(9999, 12, 31);

    /** Every row of an obligation - no history page is shorter than its whole record. */
    static final int EVERY_ROW = Integer.MAX_VALUE;

    @NonNull private final BnplOfferStore offers;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplRepaymentStore repayments;
    @NonNull private final BnplRefundStore refunds;
    @NonNull private final BnplConditionStore conditions;
    @NonNull private final BnplPayoffStore payoffs;
    @NonNull private final BnplDisputeStore disputes;
    @NonNull private final BnplCorrectionStore corrections;
    @NonNull private final BnplMerchants merchants;
    @NonNull private final BnplBook book;

    @Override
    public List<BnplObligationId> obligationIds(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                        "SELECT id FROM bnpl.bnpl_obligation ORDER BY opened_at, id");
                ResultSet rows = select.executeQuery()) {
            List<BnplObligationId> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(BnplObligationId.of(rows.getObject("id", UUID.class)));
            }
            return List.copyOf(ids);
        } catch (SQLException failure) {
            throw failed("listing the BNPL obligations", failure);
        }
    }

    @Override
    public Optional<BnplRecord> read(Connection c, BnplObligationId obligationId) {
        Objects.requireNonNull(c, "unitOfWork");
        Objects.requireNonNull(obligationId, "obligation");
        UUID id = obligationId.value();
        List<Head> heads = query(c, "SELECT o.id AS obligation_id, o.purchase_id, o.agreement_id, o.party_id, o.decision_id,"
                + " o.product, o.plan, o.currency, o.status AS obligation_status, o.purchase_date, o.opened_at, o.closed_at,"
                + " o.closure_reason, o.correlation_id AS obligation_correlation, p.offer_id, p.checkout_session_id,"
                + " p.merchant_id, p.status AS purchase_status, p.business_date, p.authorised_at, p.financed_at,"
                + " p.order_id, p.correlation_id AS purchase_correlation, f.journal_entry_id, f.scale, f.gross_minor,"
                + " f.fee_minor, f.net_minor, f.fee_schedule_version_id, f.financed_at AS financing_at"
                + " FROM bnpl.bnpl_obligation o JOIN bnpl.purchase p ON p.id = o.purchase_id"
                + " JOIN bnpl.merchant_financing f ON f.purchase_id = p.id WHERE o.id = ?", id, JdbcBnplRecordStore::head);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        Head head = heads.get(0);
        BnplObligation obligation = head.obligation();
        // Every fact re-stamped in the record's order (V017's recorded_order): the proofs compare stamps, and a database
        // clock stepped back would otherwise put a later fact before an earlier one (P12-TST-001's finding).
        Map<String, Instant> at = restamped(c, id);
        CurrencyCode currency = obligation.currency();
        CorrelationId flow = obligation.correlation();

        List<BnplRecord.Agreement> agreements = query(c, "SELECT * FROM bnpl.bnpl_agreement WHERE obligation_id = ?"
                + " ORDER BY version", id, row -> agreement(row, obligation, at));
        Map<BnplTermsVersionId, Optional<BnplTermsVersion>> versions = new LinkedHashMap<>();
        for (BnplRecord.Agreement agreement : agreements) {
            versions.computeIfAbsent(agreement.row().termsVersionId(), version -> {
                try {
                    return terms.version(c, version);
                } catch (RuntimeException unreadable) {
                    return Optional.empty(); // an engine or a shape this build does not hold - the replay reports it
                }
            });
        }
        Optional<BnplOffer> offer;
        Optional<BnplOfferStore.StoredTerms> offerTerms;
        try {
            offer = offers.ownedBy(c, head.purchase().offer(), obligation.party());
            offerTerms = offers.terms(c, head.purchase().offer());
        } catch (RuntimeException unreadable) {
            offer = Optional.empty();
            offerTerms = Optional.empty();
        }

        List<BnplRecord.Schedule> schedules = new ArrayList<>();
        for (ScheduleMeta meta : query(c, "SELECT schedule_version, refund_id, created_at FROM bnpl.bnpl_schedule"
                + " WHERE obligation_id = ? ORDER BY schedule_version", id, row -> new ScheduleMeta(
                        row.getInt("schedule_version"), uuid(row, "refund_id"), instant(row, "created_at")))) {
            purchases.schedule(c, obligationId, meta.version()).ifPresent(found ->
                    schedules.add(new BnplRecord.Schedule(found, meta.refund(),
                            at.getOrDefault("schedule:" + meta.version(), meta.at()))));
        }

        List<BnplRecord.Billing> billings = query(c, "SELECT b.*, i.sequence FROM bnpl.bnpl_billing b"
                + " JOIN bnpl.bnpl_instalment i ON i.id = b.instalment_id WHERE b.obligation_id = ?"
                + " ORDER BY b.billed_at, b.id", id, row -> billing(row, obligation, at));
        billings.sort(java.util.Comparator.comparing(BnplRecord.Billing::at));
        List<BnplRecord.Stamped<BnplInterestAccrual>> accruals = query(c, "SELECT * FROM bnpl.bnpl_interest_accrual"
                + " WHERE obligation_id = ? ORDER BY accrual_date", id, row -> accrual(row, obligationId, flow, at));

        Map<UUID, List<BnplCreditBalanceApplication.Line>> lines = new HashMap<>();
        query(c, "SELECT l.* FROM bnpl.bnpl_credit_balance_allocation l JOIN bnpl.bnpl_credit_balance_application a"
                + " ON a.id = l.application_id WHERE a.obligation_id = ? ORDER BY l.application_id, l.ordinal", id, row -> {
                    lines.computeIfAbsent(row.getObject("application_id", UUID.class), any -> new ArrayList<>())
                            .add(new BnplCreditBalanceApplication.Line(row.getObject("id", UUID.class),
                                    row.getObject("billing_id", UUID.class),
                                    AllocationComponent.valueOf(row.getString("component")), money(row, "amount_minor")));
                    return row;
                });
        List<BnplRecord.Stamped<BnplCreditBalanceApplication>> applications = query(c, "SELECT * FROM"
                + " bnpl.bnpl_credit_balance_application WHERE obligation_id = ? ORDER BY created_at, id", id, row ->
                new BnplRecord.Stamped<>(new BnplCreditBalanceApplication(row.getObject("id", UUID.class), obligationId,
                        uuid(row, "billing_id"), integer(row, "catch_up_sequence"), money(row, "amount_minor"),
                        BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getInt("agreement_version"),
                        row.getString("allocation_engine"), row.getObject("value_date", LocalDate.class),
                        lines.getOrDefault(row.getObject("id", UUID.class), List.of()),
                        JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                        correlation(row, flow)), stamp(at, row, "created_at")));
        applications.sort(java.util.Comparator.comparing(BnplRecord.Stamped::at));

        Map<UUID, Instant> received = new HashMap<>();
        query(c, "SELECT id, received_at FROM bnpl.bnpl_repayment WHERE obligation_id = ?", id, row ->
                received.put(row.getObject("id", UUID.class), instant(row, "received_at")));
        List<BnplRecord.Stamped<BnplPurchaseStore.Repayment>> repaid = new ArrayList<>();
        for (BnplPurchaseStore.Repayment repayment : repayments.ofObligation(c, obligationId, EVERY_ROW)) {
            repaid.add(new BnplRecord.Stamped<>(repayment, at.getOrDefault(repayment.id().toString(),
                    received.getOrDefault(repayment.id(), Instant.EPOCH))));
        }
        repaid.sort(java.util.Comparator.comparing(BnplRecord.Stamped::at));

        List<BnplRecord.Stamped<BnplCollectionAttempt>> attempts = query(c, "SELECT * FROM bnpl.bnpl_collection_attempt"
                + " WHERE obligation_id = ? ORDER BY created_at, id", id, row -> new BnplRecord.Stamped<>(
                        new BnplCollectionAttempt(row.getObject("id", UUID.class), obligationId,
                                row.getObject("instalment_id", UUID.class), row.getObject("attempt_date", LocalDate.class),
                                CollectionOutcome.valueOf(row.getString("outcome")), money(row, "due_minor"),
                                money(row, "collected_minor"), row.getInt("agreement_version"), uuid(row, "repayment_id"),
                                correlation(row, flow)), stamp(at, row, "created_at")));
        attempts.sort(java.util.Comparator.comparing(BnplRecord.Stamped::at));

        List<BnplRecord.Stamped<BnplRefund>> refunded = new ArrayList<>();
        for (BnplRefund refund : refunds.ofPurchase(c, head.purchase().id())) {
            refunded.add(new BnplRecord.Stamped<>(refund, at.getOrDefault(refund.id().toString(),
                    refund.createdAt().orElse(Instant.EPOCH))));
        }
        refunded.sort(java.util.Comparator.comparing(BnplRecord.Stamped::at));

        Map<UUID, Instant> assessedAt = new HashMap<>();
        query(c, "SELECT id, created_at FROM bnpl.bnpl_fee_assessment WHERE obligation_id = ?", id, row ->
                assessedAt.put(row.getObject("id", UUID.class), instant(row, "created_at")));
        List<BnplRecord.Stamped<BnplFeeAssessment>> fees = new ArrayList<>();
        for (BnplFeeAssessment fee : conditions.assessments(c, obligationId)) {
            fees.add(new BnplRecord.Stamped<>(fee, at.getOrDefault(fee.id().toString(),
                    assessedAt.getOrDefault(fee.id(), Instant.EPOCH))));
        }
        fees.sort(java.util.Comparator.comparing(BnplRecord.Stamped::at));

        List<BnplRecord.Stamped<BnplConditionEvent>> changes = query(c, "SELECT * FROM bnpl.bnpl_condition_event"
                + " WHERE obligation_id = ? ORDER BY created_at, id", id, row -> new BnplRecord.Stamped<>(
                        new BnplConditionEvent(row.getObject("id", UUID.class), obligationId,
                                row.getObject("business_date", LocalDate.class),
                                BnplConditionKind.valueOf(row.getString("kind")),
                                new BnplDelinquencyState(row.getInt("days_past_due"),
                                        BnplDelinquencyBucket.fromRecorded(row.getString("bucket")),
                                        row.getBoolean("defaulted")),
                                Optional.ofNullable(row.getString("previous_bucket"))
                                        .flatMap(BnplDelinquencyBucket::fromRecorded),
                                BnplAgreementId.of(row.getObject("agreement_id", UUID.class)),
                                row.getInt("agreement_version"), correlation(row, flow)), stamp(at, row, "created_at")));
        changes.sort(java.util.Comparator.comparing(BnplRecord.Stamped::at));

        List<BnplRecord.Stamped<BnplPayoffQuote>> quotes = new ArrayList<>();
        for (UUID quote : query(c, "SELECT id FROM bnpl.bnpl_payoff_quote WHERE obligation_id = ?"
                + " ORDER BY created_at, id", id, row -> row.getObject("id", UUID.class))) {
            payoffs.findQuote(c, obligationId, quote).ifPresent(found ->
                    quotes.add(new BnplRecord.Stamped<>(found, at.getOrDefault(found.id().toString(),
                            found.createdAt()))));
        }
        quotes.sort(java.util.Comparator.comparing(BnplRecord.Stamped::at));
        Optional<BnplRecord.Stamped<BnplPayoffExecution>> execution = payoffs.findExecution(c, obligationId)
                .map(found -> new BnplRecord.Stamped<>(found, at.getOrDefault(found.id().toString(),
                        found.executedAt())));

        List<BnplDispute> disputed = new ArrayList<>();
        for (UUID dispute : query(c, "SELECT id FROM bnpl.bnpl_dispute WHERE obligation_id = ? ORDER BY opened_at, id",
                id, row -> row.getObject("id", UUID.class))) {
            disputes.find(c, BnplDisputeId.of(dispute)).map(found -> restamped(found, at)).ifPresent(disputed::add);
        }

        Map<UUID, List<BnplRecord.Negation>> negations = new HashMap<>();
        query(c, "SELECT n.reversal_id, n.allocation_id, n.currency, n.scale, n.amount_minor"
                + " FROM bnpl.bnpl_repayment_allocation_reversal n JOIN bnpl.bnpl_repayment_reversal r"
                + " ON r.id = n.reversal_id WHERE r.obligation_id = ? ORDER BY n.reversal_id, n.allocation_id", id, row -> {
                    negations.computeIfAbsent(row.getObject("reversal_id", UUID.class), any -> new ArrayList<>())
                            .add(new BnplRecord.Negation(row.getObject("allocation_id", UUID.class),
                                    money(row, "amount_minor")));
                    return row;
                });
        // Each reversal's releases of the late fees it re-opened (the Phase 12 transition gate's A7-3, bnpl V019) - part of
        // its own act, in its decision's place in the order.
        Map<UUID, List<BnplRecord.FeeRelease>> releases = new HashMap<>();
        query(c, "SELECT f.reversal_id, f.instalment_id, f.currency, f.scale, f.amount_minor, f.journal_entry_id"
                + " FROM bnpl.bnpl_reversal_fee_release f WHERE f.obligation_id = ? ORDER BY f.reversal_id,"
                + " f.instalment_id", id, row -> {
                    releases.computeIfAbsent(row.getObject("reversal_id", UUID.class), any -> new ArrayList<>())
                            .add(new BnplRecord.FeeRelease(row.getObject("instalment_id", UUID.class),
                                    money(row, "amount_minor"),
                                    JournalEntryId.of(row.getObject("journal_entry_id", UUID.class))));
                    return row;
                });
        List<BnplRecord.Reversal> reversals = new ArrayList<>();
        for (BnplRepaymentReversal reversal : corrections.reversalsOf(c, obligationId, EVERY_ROW)) {
            reversals.add(new BnplRecord.Reversal(restamped(reversal, at), negations.getOrDefault(reversal.id(),
                    List.of()), releases.getOrDefault(reversal.id(), List.of())));
        }
        List<BnplWaiver> waivers = corrections.waiversOfObligation(c, obligationId, EVERY_ROW).stream()
                .map(waiver -> restamped(waiver, at)).toList();

        Optional<BnplRecord.Stamped<BnplClosure>> closure = repayments.closureOf(c, obligationId).map(found ->
                new BnplRecord.Stamped<>(found, at.getOrDefault("closure", first(query(c, "SELECT created_at FROM"
                        + " bnpl.bnpl_obligation_closure WHERE obligation_id = ?", id, row -> instant(row, "created_at")))
                        .orElse(Instant.EPOCH))));

        // The ledger: the obligation's accounts and every line on them in ONE statement; the shared entries' lines.
        List<BnplRecord.LedgerAccountRow> owned = new ArrayList<>();
        Set<LedgerAccountId> ownedIds = new LinkedHashSet<>();
        for (LedgerAccount account : book.accountsOf(c, obligationId)) {
            owned.add(new BnplRecord.LedgerAccountRow(account.id(), account.purpose(), account.currency()));
            ownedIds.add(account.id());
        }
        List<PurposeBalances.DatedLine> journal = ownedIds.isEmpty() ? List.of()
                : book.datedLinesOn(c, ownedIds, EVERY_VALUE_DATE);
        List<BnplRecord.EntryLines> shared = new ArrayList<>();
        shared.add(new BnplRecord.EntryLines(head.financing().entry(), book.entryLines(c, head.financing().entry())));
        for (BnplRecord.Stamped<BnplRefund> refund : refunded) {
            shared.add(new BnplRecord.EntryLines(refund.row().entry(), book.entryLines(c, refund.row().entry())));
        }
        Optional<LedgerAccountId> payable = merchants.payableAccount(c, head.purchase().merchant(), currency);
        Optional<LedgerAccountId> feeIncome;
        try {
            feeIncome = Optional.of(book.operational(c, AccountPurpose.BNPL_MERCHANT_FEE_INCOME, currency));
        } catch (RuntimeException unseeded) {
            feeIncome = Optional.empty();
        }

        Optional<Instant> closedAt = closure.isPresent() ? closure.map(BnplRecord.Stamped::at) : head.closedAt();
        return Optional.of(new BnplRecord(obligation, closedAt, head.purchase(), head.financing(),
                at.getOrDefault("financing", head.financedAt()), offer, offerTerms, agreements, versions, schedules, billings, accruals, applications,
                repaid, attempts, refunded, fees, changes, quotes, execution, disputed, reversals, waivers, closure, owned,
                journal, shared, payable, feeIncome));
    }

    // ------------------------------------------------------------------ the rows

    private record Head(BnplObligation obligation, Optional<Instant> closedAt, BnplPurchase purchase,
            MerchantFinancing financing, Instant financedAt) {}

    private record ScheduleMeta(int version, Optional<UUID> refund, Instant at) {}

    private static Head head(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        BnplProduct product = BnplProduct.valueOf(row.getString("product"));
        BnplPlan plan = BnplPlan.valueOf(row.getString("plan"));
        BnplObligationId obligationId = BnplObligationId.of(row.getObject("obligation_id", UUID.class));
        BnplPurchaseId purchaseId = BnplPurchaseId.of(row.getObject("purchase_id", UUID.class));
        UUID party = row.getObject("party_id", UUID.class);
        String closure = row.getString("closure_reason");
        BnplObligation obligation = new BnplObligation(obligationId, purchaseId,
                BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), party,
                row.getObject("decision_id", UUID.class), product, plan, currency,
                BnplObligationStatus.valueOf(row.getString("obligation_status")),
                row.getObject("purchase_date", LocalDate.class), instant(row, "opened_at"),
                Optional.ofNullable(closure).map(BnplClosureReason::valueOf),
                CorrelationId.of(row.getString("obligation_correlation")));
        BnplPurchase purchase = new BnplPurchase(purchaseId, BnplOfferId.of(row.getObject("offer_id", UUID.class)),
                row.getObject("checkout_session_id", UUID.class), party, row.getObject("merchant_id", UUID.class), product,
                plan, currency, BnplPurchaseStatus.valueOf(row.getString("purchase_status")),
                row.getObject("business_date", LocalDate.class), instant(row, "authorised_at"),
                optionalInstant(row, "financed_at"), Optional.ofNullable(row.getObject("order_id", UUID.class)),
                CorrelationId.of(row.getString("purchase_correlation")));
        int scale = row.getInt("scale");
        MerchantFeeSplit split = new MerchantFeeSplit(Money.ofPersisted(row.getLong("gross_minor"), currency, scale),
                Money.ofPersisted(row.getLong("fee_minor"), currency, scale),
                Money.ofPersisted(row.getLong("net_minor"), currency, scale),
                row.getObject("fee_schedule_version_id", UUID.class));
        MerchantFinancing financing = new MerchantFinancing(purchaseId, row.getObject("merchant_id", UUID.class), split,
                JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)));
        return new Head(obligation, optionalInstant(row, "closed_at"), purchase, financing, instant(row, "financing_at"));
    }

    private static BnplRecord.Agreement agreement(ResultSet row, BnplObligation obligation, Map<String, Instant> at)
            throws SQLException {
        BnplAgreementTemplate template = BnplAgreementTemplate.of(row.getString("agreement_template_id"),
                        row.getInt("agreement_template_version"))
                .orElseThrow(() -> new IllegalStateException("an agreement names a registered template"));
        BnplAgreement agreement = new BnplAgreement(BnplAgreementId.of(row.getObject("agreement_id", UUID.class)),
                row.getInt("version"), obligation.id(), BnplOfferId.of(row.getObject("offer_id", UUID.class)),
                row.getObject("decision_id", UUID.class),
                BnplTermsVersionId.of(row.getObject("terms_version_id", UUID.class)),
                BnplProduct.valueOf(row.getString("product")), BnplPlan.valueOf(row.getString("plan")),
                row.getString("schedule_engine"), Optional.ofNullable(row.getString("accrual_engine")),
                row.getString("allocation_engine"), row.getString("refund_allocation_engine"),
                row.getObject("fee_schedule_version_id", UUID.class), template, row.getInt("canonical_format"),
                row.getString("canonical_terms"), row.getBytes("terms_sha256"), row.getBytes("document_sha256"),
                at.getOrDefault("agreement:" + row.getObject("agreement_id", UUID.class) + ":" + row.getInt("version"),
                        instant(row, "created_at")));
        return new BnplRecord.Agreement(agreement, uuid(row, "refund_id"), row.getInt("schedule_version"));
    }

    private static BnplRecord.Billing billing(ResultSet row, BnplObligation obligation, Map<String, Instant> at)
            throws SQLException {
        UUID absent = new UUID(0, 0);
        BnplBilling billing = new BnplBilling(row.getObject("id", UUID.class), row.getObject("instalment_id", UUID.class),
                row.getInt("sequence"), obligation.id(), uuid(row, "schedule_id").orElse(absent),
                Optional.ofNullable(integer(row, "schedule_version").orElse(null)).orElse(0),
                Optional.ofNullable(row.getString("schedule_engine")).orElse(""),
                uuid(row, "agreement_id").map(BnplAgreementId::of).orElse(obligation.agreement()),
                integer(row, "agreement_version").orElse(0), Optional.ofNullable(row.getString("accrual_engine")),
                Optional.ofNullable(row.getObject("period_start", LocalDate.class))
                        .orElse(row.getObject("value_date", LocalDate.class)),
                row.getObject("value_date", LocalDate.class), money(row, "principal_minor"), money(row, "interest_minor"),
                uuid(row, "journal_entry_id").map(JournalEntryId::of), correlation(row, obligation.correlation()));
        LocalDate businessDate = Optional.ofNullable(row.getObject("business_date", LocalDate.class))
                .orElse(billing.dueDate());
        return new BnplRecord.Billing(billing, businessDate, uuid(row, "payoff_execution_id"),
                stamp(at, row, "billed_at"));
    }

    private static BnplRecord.Stamped<BnplInterestAccrual> accrual(ResultSet row, BnplObligationId obligation,
            CorrelationId flow, Map<String, Instant> at) throws SQLException {
        InterestAccrual accrual = new InterestAccrual(row.getObject("accrual_date", LocalDate.class),
                new AccrualPeriod(row.getObject("period_start", LocalDate.class),
                        row.getObject("period_end", LocalDate.class)),
                money(row, "base_minor"), row.getBigDecimal("cumulative_exact").setScale(Act365Fixed.EXACT_SCALE),
                money(row, "posted_minor"), EngineVersion.valueOf(row.getString("accrual_engine")));
        return new BnplRecord.Stamped<>(new BnplInterestAccrual(row.getObject("id", UUID.class), obligation, accrual,
                BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getInt("agreement_version"),
                uuid(row, "journal_entry_id").map(JournalEntryId::of), correlation(row, flow)), stamp(at, row, "created_at"));
    }

    // ------------------------------------------------------------------ the record's order (V017)

    /**
     * Every fact of the obligation with its place in the record's order - V017's {@code recorded_order}, drawn under B4 -
     * and its stamp, re-stamped MONOTONICALLY in that order: each fact at the later of its own stamp and one microsecond
     * after the fact before it. A re-stamped instant is never earlier than the stamp its fact was written with, and equals
     * it unless the database clock stepped back under it; every comparison the proofs make then reads the obligation lock's
     * order, never a clock (lending V025's rule, `P11-TSK-029`; `P12-TST-001`'s finding). Facts written before V017 carry
     * no order and come first, by their stamps. Keyed by the fact's id - or {@code agreement:<id>:<version>},
     * {@code schedule:<version>}, {@code financing}, {@code closure}, and a proposal's and a decision's own keys.
     */
    static Map<String, Instant> restamped(Connection c, UUID obligation) {
        String sql = "SELECT k, o, at FROM ("
                + "SELECT 'financing' AS k, f.recorded_order AS o, f.financed_at AS at FROM bnpl.merchant_financing f"
                + " JOIN bnpl.bnpl_obligation x ON x.purchase_id = f.purchase_id WHERE x.id = ?"
                + " UNION ALL SELECT 'agreement:' || agreement_id || ':' || version, recorded_order, created_at FROM"
                + " bnpl.bnpl_agreement WHERE obligation_id = ?"
                + " UNION ALL SELECT 'schedule:' || schedule_version, recorded_order, created_at FROM bnpl.bnpl_schedule"
                + " WHERE obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, billed_at FROM bnpl.bnpl_billing WHERE obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, created_at FROM bnpl.bnpl_interest_accrual WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, created_at FROM bnpl.bnpl_credit_balance_application WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, received_at FROM bnpl.bnpl_repayment WHERE obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, created_at FROM bnpl.bnpl_collection_attempt WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, created_at FROM bnpl.bnpl_refund WHERE obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, created_at FROM bnpl.bnpl_condition_event WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, created_at FROM bnpl.bnpl_fee_assessment WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, created_at FROM bnpl.bnpl_payoff_quote WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT id::text, recorded_order, executed_at FROM bnpl.bnpl_payoff_execution WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT 'closure', recorded_order, created_at FROM bnpl.bnpl_obligation_closure WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT 'reversal:' || id, recorded_order, proposed_at FROM bnpl.bnpl_repayment_reversal"
                + " WHERE obligation_id = ?"
                + " UNION ALL SELECT 'reversal-decided:' || id, decided_order, decided_at FROM"
                + " bnpl.bnpl_repayment_reversal WHERE obligation_id = ? AND decided_at IS NOT NULL"
                + " UNION ALL SELECT 'waiver:' || id, recorded_order, proposed_at FROM bnpl.bnpl_waiver WHERE"
                + " obligation_id = ?"
                + " UNION ALL SELECT 'waiver-decided:' || d.waiver_id, d.recorded_order, d.decided_at FROM"
                + " bnpl.bnpl_waiver_decision d JOIN bnpl.bnpl_waiver w ON w.id = d.waiver_id WHERE w.obligation_id = ?"
                + " UNION ALL SELECT 'dispute:' || id, recorded_order, opened_at FROM bnpl.bnpl_dispute WHERE"
                + " obligation_id = ? AND opened_at IS NOT NULL"
                + " UNION ALL SELECT 'dispute-resolved:' || d.id, (SELECT max(e.recorded_order) FROM"
                + " bnpl.bnpl_dispute_event e WHERE e.dispute_id = d.id AND e.to_status IN ('RESOLVED_REFUND',"
                + " 'RESOLVED_UPHELD', 'WITHDRAWN')), d.resolved_at FROM bnpl.bnpl_dispute d WHERE d.obligation_id = ?"
                + " AND d.resolved_at IS NOT NULL"
                + ") facts ORDER BY o NULLS FIRST, at, k";
        try (PreparedStatement select = c.prepareStatement(sql)) {
            int parameters = sql.length() - sql.replace("?", "").length();
            for (int i = 1; i <= parameters; i++) {
                select.setObject(i, obligation);
            }
            Map<String, Instant> ordered = new HashMap<>();
            Instant previous = null;
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Instant stamp = rows.getTimestamp("at").toInstant();
                    Instant restamped = previous == null || stamp.isAfter(previous) ? stamp : previous.plusNanos(1_000);
                    ordered.put(rows.getString("k"), restamped);
                    previous = restamped;
                }
            }
            return ordered;
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's record order", failure);
        }
    }

    /** The row's re-stamped instant - by its id - else its own stamp. */
    private static Instant stamp(Map<String, Instant> at, ResultSet row, String column) throws SQLException {
        Instant own = instant(row, column);
        return at.getOrDefault(row.getObject("id", UUID.class).toString(), own);
    }

    private static BnplDispute restamped(BnplDispute dispute, Map<String, Instant> at) {
        String key = dispute.id().value().toString();
        return new BnplDispute(dispute.id(), dispute.purchase(), dispute.obligation(), dispute.merchant(), dispute.party(),
                dispute.agreement(), dispute.termsVersion(), dispute.status(), dispute.category(), dispute.reason(),
                dispute.requestKey(), dispute.amount(), dispute.refundedBefore(),
                dispute.openedAt().map(own -> at.getOrDefault("dispute:" + key, own)), dispute.openedOn(),
                dispute.responseDeadline(), dispute.merchantStatement(), dispute.merchantRespondedAt(),
                dispute.resolvedBy(), dispute.resolutionReason(),
                dispute.resolvedAt().map(own -> at.getOrDefault("dispute-resolved:" + key, own)), dispute.resolvedOn(),
                dispute.refund(), dispute.proposal(), dispute.correlation(), dispute.holds());
    }

    private static BnplRepaymentReversal restamped(BnplRepaymentReversal reversal, Map<String, Instant> at) {
        return new BnplRepaymentReversal(reversal.id(), reversal.obligation(), reversal.repayment(), reversal.agreement(),
                reversal.agreementVersion(), reversal.status(), reversal.proposedBy(),
                at.getOrDefault("reversal:" + reversal.id(), reversal.proposedAt()), reversal.proposalReason(),
                reversal.decidedBy(), reversal.decidedAt().map(own -> at.getOrDefault("reversal-decided:"
                        + reversal.id(), own)), reversal.decisionReason(), reversal.entry(), reversal.valueDate());
    }

    private static BnplWaiver restamped(BnplWaiver waiver, Map<String, Instant> at) {
        return new BnplWaiver(waiver.id(), waiver.obligation(), waiver.kind(), waiver.assessment(), waiver.instalment(),
                waiver.amount(), waiver.agreement(), waiver.agreementVersion(), waiver.status(), waiver.proposedBy(),
                at.getOrDefault("waiver:" + waiver.id(), waiver.proposedAt()), waiver.proposalReason(),
                waiver.decidedBy(), waiver.decidedAt().map(own -> at.getOrDefault("waiver-decided:" + waiver.id(), own)),
                waiver.decisionReason(), waiver.entry());
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Row<T> {
        T map(ResultSet row) throws SQLException;
    }

    private static <T> List<T> query(Connection unitOfWork, String sql, UUID obligation, Row<T> mapper) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, obligation);
            List<T> found = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(mapper.map(rows));
                }
            }
            return found;
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's record", failure);
        }
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Money money(ResultSet row, String column) throws SQLException {
        return Money.ofPersisted(row.getLong(column), CurrencyCode.of(row.getString("currency").strip()),
                row.getInt("scale"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static Optional<Instant> optionalInstant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }

    private static Optional<UUID> uuid(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, UUID.class));
    }

    private static Optional<Integer> integer(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static CorrelationId correlation(ResultSet row, CorrelationId fallback) throws SQLException {
        String value = row.getString("correlation_id");
        return value == null ? fallback : CorrelationId.of(value);
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
