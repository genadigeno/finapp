package com.finapp.reconciliation;

import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Rule 0's recognition (`P11-TSK-004`, ADR-0096 section 4, PHASE_11_PLAN.md section 12.9;
 * {@code INV-LND-13}, {@code INV-SET-06}, {@code INV-REC-06}): a bank statement's credit line
 * becomes LENDING CAPITAL only when it matches an approved contribution's expectation - and only in
 * the statement's own acceptance, the one transaction in which cash moves on the books.
 *
 * <h2>Three calls, one acceptance transaction</h2>
 *
 * <ol>
 *   <li>{@link #claim} - before the recognition entry is computed: each unattributed credit line
 *       carrying a remittance reference is offered; the {@code OPEN}
 *       {@code LENDING_CAPITAL_CONTRIBUTION} expectations its reference reaches UNDER THE
 *       STATEMENT'S OWN SOURCE are locked {@code FOR UPDATE} in id order, and a line claims one
 *       when the currency and scale agree, the amount is EXACTLY the expectation's (no amount
 *       tolerance exists - {@code INV-REC-08}) and the database's date is within the expectation's
 *       window ({@code expected_by}, judged on {@code statement_timestamp()} read after the lock).
 *       One line per expectation, in line order; a line that claims nothing stays unattributed
 *       and parks in suspense like any other.
 *   <li>{@link #match} - after the item is born: the item is matched WHOLE to its expectation by a
 *       {@code RECOGNITION} decision (verdict {@code CAPITAL_RECOGNISED}, its one candidate
 *       snapshotted, so the run replay re-derives it exactly), the allocation settles the
 *       expectation, and {@code reconciliation.SettlementExpectationSettled} is announced.
 *   <li>{@link #record} - after the recognition posts: one {@code capital_recognition} row per
 *       claimed line carrying the entry whose {@code CR LENDING_CAPITAL} it explains -
 *       {@code UNIQUE (expectation_id)} and {@code UNIQUE (external_item_id)}: born once per
 *       contribution and once per statement line, whatever retries or re-imports arrive.
 * </ol>
 *
 * <p>Ten acceptors of competing statements of one source serialise on settlement's source row
 * lock before any of this; a statement of another source never reaches the expectation (the key
 * scope). A re-imported or duplicated line finds its expectation already {@code SETTLED} and
 * claims nothing - it is cash nobody's pattern explains, a break like any other.
 */
@RequiredArgsConstructor
public final class CapitalRecognition {

    @NonNull private final MatchingStore store;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;

    /** One line offered for capital: its id and number, its remittance reference and amount. */
    public record Candidate(UUID lineId, int lineNo, String reference, Money amount) {

        public Candidate {
            Objects.requireNonNull(lineId, "lineId must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        /** Identifiers only - never an amount or a reference ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Candidate[" + lineId + ", line " + lineNo + "]";
        }
    }

    /** A line matched to its contribution: the item, the expectation, the decision, the amount. */
    public record Matched(UUID itemId, UUID expectationId, UUID decisionId, Money amount) {

        public Matched {
            Objects.requireNonNull(itemId, "itemId must not be null");
            Objects.requireNonNull(expectationId, "expectationId must not be null");
            Objects.requireNonNull(decisionId, "decisionId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        @Override
        public String toString() {
            return "Matched[" + itemId + " -> " + expectationId + "]";
        }
    }

    /**
     * Which of {@code candidates} are capital: line id to the expectation it claims, the
     * expectations locked {@code FOR UPDATE} for the rest of the acceptance.
     *
     * @param sourceId the statement's own source - the key scope a contribution is opened under
     */
    public Map<UUID, UUID> claim(Connection unitOfWork, UUID sourceId, List<Candidate> candidates) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.isEmpty()) {
            return Map.of();
        }
        List<Claimable> claimable = new ArrayList<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT e.id, k.key_value, e.amount_minor, e.currency, e.scale"
                                + " FROM reconciliation.expectation_key k"
                                + " JOIN reconciliation.expectation e ON e.id = k.expectation_id"
                                + " WHERE k.source_id = ? AND k.key_kind = 'REMITTANCE_REF'"
                                + " AND k.key_value = ANY (?)"
                                + " AND e.kind = 'LENDING_CAPITAL_CONTRIBUTION'"
                                + " AND e.status = 'OPEN' AND e.allocated_minor = 0"
                                + " AND e.resolved_minor = 0 AND e.direction = 'INBOUND'"
                                // The window, on the database's clock - a lapsed contribution
                                // never becomes capital, whatever arrives later.
                                + " AND e.expected_by >= (statement_timestamp() AT TIME ZONE 'UTC')::date"
                                + " ORDER BY e.id FOR UPDATE OF e")) {
            Array references =
                    unitOfWork.createArrayOf(
                            "text", candidates.stream().map(Candidate::reference).distinct().toArray());
            select.setObject(1, sourceId);
            select.setArray(2, references);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    claimable.add(
                            new Claimable(
                                    row.getObject("id", UUID.class),
                                    row.getString("key_value"),
                                    Money.ofPersisted(
                                            row.getLong("amount_minor"),
                                            CurrencyCode.of(row.getString("currency").trim()),
                                            row.getShort("scale"))));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the contributions a statement's lines may recognise", failure);
        }
        Map<UUID, UUID> claimed = new LinkedHashMap<>();
        Set<UUID> taken = new HashSet<>();
        List<Candidate> inLineOrder = new ArrayList<>(candidates);
        inLineOrder.sort(Comparator.comparingInt(Candidate::lineNo));
        for (Candidate candidate : inLineOrder) {
            for (Claimable expectation : claimable) {
                if (!taken.contains(expectation.id())
                        && expectation.reference().equals(candidate.reference())
                        && expectation.amount().currency().equals(candidate.amount().currency())
                        && expectation.amount().scale() == candidate.amount().scale()
                        && expectation.amount().minorUnits() == candidate.amount().minorUnits()) {
                    claimed.put(candidate.lineId(), expectation.id());
                    taken.add(expectation.id());
                    break;
                }
            }
        }
        return Map.copyOf(claimed);
    }

    /**
     * Matches the just-born capital item whole to the expectation it claimed - the decision, its
     * candidate, the allocation, the expectation {@code SETTLED} and announced, the item
     * {@code PENDING -> MATCHED}. The expectation was locked by {@link #claim} in this same
     * transaction.
     */
    public Matched match(
            Connection unitOfWork,
            UUID runId,
            UUID ruleSetId,
            UUID sourceId,
            UUID itemId,
            Money amount,
            UUID expectationId,
            Actor actor,
            Instant at,
            LocalDate decidedOn,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        MatchEngine.HitFacts candidate = lockedCandidate(unitOfWork, expectationId);
        if (candidate.kind() != ExpectationKind.LENDING_CAPITAL_CONTRIBUTION
                || candidate.remainderMinor() != amount.minorUnits()
                || !candidate.amount().currency().equals(amount.currency())
                || candidate.amount().scale() != amount.scale()) {
            throw new IllegalStateException(
                    "a claimed capital expectation moved under its own lock (the claim and the match"
                            + " share one acceptance transaction)");
        }
        UUID decisionId = ids.next();
        store.insertDecision(
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        itemId,
                        runId,
                        DecisionOrigin.RECOGNITION,
                        ruleSetId,
                        Optional.empty(),
                        Optional.of(Cardinality.ONE_TO_ONE),
                        Optional.of(KeyKind.REMITTANCE_REF),
                        DecisionOutcome.MATCHED,
                        Optional.of(1),
                        Optional.of(1),
                        Optional.empty(),
                        Optional.empty(),
                        actor,
                        at,
                        decidedOn,
                        correlation,
                        MatchingStore.Basis.of(
                                DecisionVerdict.CAPITAL_RECOGNISED, JudgedStatus.PENDING, amount.minorUnits())));
        store.insertCandidates(unitOfWork, decisionId, List.of(candidate));
        store.insertAllocation(
                unitOfWork,
                new MatchingStore.NewAllocation(ids.next(), decisionId, itemId, expectationId, amount, at, correlation));
        ExpectationStatus status =
                store.allocateToExpectation(
                        unitOfWork, expectationId, amount, "decision=" + decisionId, actor, at, correlation);
        if (status != ExpectationStatus.SETTLED) {
            throw new IllegalStateException("an exact capital line settles its contribution whole");
        }
        store.bumpResidualOnSubjects(unitOfWork, expectationId, itemId);
        if (!store.markItemMatchedFrom(
                unitOfWork, itemId, ItemStatus.PENDING.name(), amount.minorUnits(), actor, at, correlation)) {
            throw new IllegalStateException("a capital item is matched in the transaction that bore it");
        }
        ReconciliationEvents.expectationSettled(
                outbox, unitOfWork, ids, expectationId, candidate.kind(), sourceId, at, correlation);
        return new Matched(itemId, expectationId, decisionId, amount);
    }

    /**
     * Records each matched line against the recognition entry whose {@code CR LENDING_CAPITAL} it
     * explains - after the posting, in the same transaction. The two uniques are the arbiters of
     * "once per line, once per contribution" for every writer.
     */
    public void record(
            Connection unitOfWork,
            UUID batchId,
            List<Matched> matched,
            UUID entryId,
            LocalDate recognisedOn,
            Instant at,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(entryId, "entryId must not be null");
        if (matched.isEmpty()) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.capital_recognition (id, expectation_id,"
                                + " external_item_id, decision_id, batch_id, journal_entry_id,"
                                + " amount_minor, currency, scale, recognised_on, created_at,"
                                + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Matched line : matched) {
                insert.setObject(1, ids.next());
                insert.setObject(2, line.expectationId());
                insert.setObject(3, line.itemId());
                insert.setObject(4, line.decisionId());
                insert.setObject(5, batchId);
                insert.setObject(6, entryId);
                insert.setLong(7, line.amount().minorUnits());
                insert.setString(8, line.amount().currency().code());
                insert.setInt(9, line.amount().scale());
                insert.setObject(10, recognisedOn);
                insert.setTimestamp(11, Timestamp.from(at));
                insert.setString(12, correlation.value());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not record the capital recognitions", failure);
        }
    }

    private MatchEngine.HitFacts lockedCandidate(Connection unitOfWork, UUID expectationId) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id, kind, direction, amount_minor, currency, scale,"
                                + " amount_minor - allocated_minor - resolved_minor AS remainder,"
                                + " opened_at, expected_by, operation_ref"
                                + " FROM reconciliation.expectation WHERE id = ? FOR UPDATE")) {
            select.setObject(1, expectationId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    throw new ReconciliationStorageException("a claimed expectation " + expectationId + " vanished");
                }
                return new MatchEngine.HitFacts(
                        row.getObject("id", UUID.class),
                        ExpectationKind.valueOf(row.getString("kind")),
                        ExpectationDirection.valueOf(row.getString("direction")),
                        Money.ofPersisted(
                                row.getLong("amount_minor"),
                                CurrencyCode.of(row.getString("currency").trim()),
                                row.getShort("scale")),
                        row.getLong("remainder"),
                        row.getTimestamp("opened_at").toInstant(),
                        row.getObject("expected_by", LocalDate.class),
                        Optional.of(KeyKind.REMITTANCE_REF),
                        row.getString("operation_ref"),
                        Optional.empty());
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read a claimed capital expectation", failure);
        }
    }

    private record Claimable(UUID id, String reference, Money amount) {}
}
