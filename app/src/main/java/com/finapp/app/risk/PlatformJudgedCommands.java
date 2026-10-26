package com.finapp.app.risk;

import com.finapp.risk.CommandRef;
import com.finapp.risk.JudgedCommands;
import com.finapp.risk.RiskDay;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskStorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link JudgedCommands} over the owners' own tables (`P13-TSK-023`; {@code PHASE_13_PLAN.md} section 17: "the judged
 * commands counted through ports {@code app} implements over transfers', payments' and crossborder's stores - reads, no
 * edge"; {@code INV-RSK-01}; {@code DESIGN-023} D2) - the {@code LendingCreditExposure} precedent of an {@code app}
 * adapter reading another schema, because none of the three owners' stores has a range read and a census is no reason to
 * grow three ports. Every statement is a {@code SELECT} on the caller's snapshot, entered through the owner's primary key
 * or unique ({@code id >= ? AND id < ?} over its UUIDv7 - {@link RiskDay}); no lock; no risk table named
 * ({@code NoCustomerSurfaceReadsRiskTest}).
 *
 * <p>What each owner holds as judged - every row its seam judged by construction, and no row it may not have:
 *
 * <ul>
 *   <li>{@code TRANSFER} (the transfer's id): {@code COMPLETED}, {@code REVERSED}, and {@code FAILED} as
 *       {@code LIMIT_REFUSED} or {@code RISK_REFUSED} - the refusals before the seams ({@code SELF_TRANSFER}, the
 *       postability and currency checks, {@code INSUFFICIENT_FUNDS}) never reached risk, and
 *       {@code DESTINATION_NOT_POSTABLE} is produced on both sides of them, so it is no judged row but is recorded.</li>
 *   <li>{@code WITHDRAWAL} (the withdrawal's id): a person's ({@code instruction_reference IS NULL}) - the platform's
 *       instructed payouts are never judged.</li>
 *   <li>{@code CARD_PAYMENT} / {@code WALLET_PAYMENT} (the intent's id): a merchant-bound intent (its credit account's
 *       ledger purpose {@code MERCHANT_PAYABLE} - a top-up is not judged) that left {@code REQUIRES_CONFIRMATION} other
 *       than by cancellation, by a card token's payment method or by its debit wallet - a bank pay-in is not judged.</li>
 *   <li>{@code CROSS_BORDER} (the <em>quote's</em> id): every {@code crossborder.payment} - a refused command leaves
 *       crossborder no row, which the census knows.</li>
 * </ul>
 *
 * <p><strong>The intents' day is their creation's, not their judgement's</strong> (the review's M4 - recorded, not
 * fixed). A transfer, a withdrawal and a cross-border payment are minted by the command the seam judges, so their id's
 * day is the judgement's day. A payment intent is minted at its <em>creation</em> and judged at its
 * <em>confirmation</em>, which may come any number of days later (an intent has no expiry). So for
 * {@code CARD_PAYMENT} and {@code WALLET_PAYMENT} the census of day {@code D} counts the intents created on {@code D}
 * that the snapshot finds judged, and its assessments are those sealed on {@code D}. What that means, exactly:
 *
 * <ul>
 *   <li>no difference is manufactured - a seal and its intent on different days reconcile by the cross-day lookup, in
 *       both directions ({@code reconciled});</li>
 *   <li>the day's {@code commands} and {@code assessments} totals for intents may differ with nothing wrong;</li>
 *   <li><strong>the limit</strong>: an intent created on {@code D} and confirmed without its seal is named
 *       {@code missing} only by the census <em>of {@code D}</em> - the meters' reading (today and yesterday) stops seeing
 *       it once {@code D} is two days old; the operator's decisions report for {@code D} still names it.</li>
 * </ul>
 * The exact day would need the confirmation's instant behind an index - {@code payment_intent_event.occurred_at} has
 * none and its id is an identity, not a UUIDv7 - which is a payments migration this task does not own (owner item).
 */
public final class PlatformJudgedCommands implements JudgedCommands {

    static final String TRANSFERS = "SELECT id FROM transfers.transfer WHERE id >= ? AND id < ?"
            + " AND (status IN ('COMPLETED', 'REVERSED') OR failure_reason IN ('LIMIT_REFUSED', 'RISK_REFUSED'))";

    static final String TRANSFERS_RECORDED = "SELECT id FROM transfers.transfer WHERE id = ANY (?)";

    static final String WITHDRAWALS = "SELECT id FROM payments.withdrawal WHERE id >= ? AND id < ?"
            + " AND instruction_reference IS NULL";

    static final String WITHDRAWALS_RECORDED = "SELECT id FROM payments.withdrawal WHERE id = ANY (?)";

    static final String CARD_PAYMENTS = "SELECT i.id FROM payments.payment_intent i"
            + " JOIN paymentmethods.payment_method m ON m.id = i.payment_method_id"
            + " JOIN ledger.ledger_account a ON a.id = i.credit_account_id"
            + " WHERE i.id >= ? AND i.id < ? AND m.kind = 'CARD_TOKEN' AND a.purpose = 'MERCHANT_PAYABLE'"
            + " AND i.status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')";

    static final String WALLET_PAYMENTS = "SELECT i.id FROM payments.payment_intent i"
            + " JOIN ledger.ledger_account a ON a.id = i.credit_account_id"
            + " WHERE i.id >= ? AND i.id < ? AND i.debit_account_id IS NOT NULL AND a.purpose = 'MERCHANT_PAYABLE'"
            + " AND i.status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')";

    static final String INTENTS_RECORDED = "SELECT id FROM payments.payment_intent WHERE id = ANY (?)";

    static final String CROSS_BORDER = "SELECT quote_id FROM crossborder.payment WHERE id >= ? AND id < ?";

    static final String CROSS_BORDER_RECORDED = "SELECT quote_id FROM crossborder.payment WHERE quote_id = ANY (?)";

    private static final Map<RiskOperation, String> JUDGED = Map.of(
            RiskOperation.TRANSFER, TRANSFERS,
            RiskOperation.WITHDRAWAL, WITHDRAWALS,
            RiskOperation.CARD_PAYMENT, CARD_PAYMENTS,
            RiskOperation.WALLET_PAYMENT, WALLET_PAYMENTS,
            RiskOperation.CROSS_BORDER, CROSS_BORDER);

    private static final Map<RiskOperation, String> RECORDED = Map.of(
            RiskOperation.TRANSFER, TRANSFERS_RECORDED,
            RiskOperation.WITHDRAWAL, WITHDRAWALS_RECORDED,
            RiskOperation.CARD_PAYMENT, INTENTS_RECORDED,
            RiskOperation.WALLET_PAYMENT, INTENTS_RECORDED,
            RiskOperation.CROSS_BORDER, CROSS_BORDER_RECORDED);

    @Override
    public Set<CommandRef> judged(Connection snapshot, RiskOperation operation, RiskDay day) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(day, "day");
        String sql = statement(JUDGED, operation);
        Set<CommandRef> judged = new LinkedHashSet<>();
        try (PreparedStatement select = snapshot.prepareStatement(sql)) {
            select.setObject(1, day.fromId());
            select.setObject(2, day.toId());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    judged.add(CommandRef.of(row.getObject(1, UUID.class)));
                }
            }
        } catch (SQLException failure) {
            throw failed("counting the judged commands of " + operation, failure);
        }
        return judged;
    }

    @Override
    public Set<CommandRef> recorded(Connection snapshot, RiskOperation operation, Collection<CommandRef> refs) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(refs, "refs");
        String sql = statement(RECORDED, operation);
        List<UUID> ids = new ArrayList<>(refs.size());
        for (CommandRef ref : refs) {
            uuid(ref.value()).ifPresent(ids::add);
        }
        Set<CommandRef> recorded = new LinkedHashSet<>();
        if (ids.isEmpty()) {
            return recorded;
        }
        try (PreparedStatement select = snapshot.prepareStatement(sql)) {
            select.setArray(1, snapshot.createArrayOf("uuid", ids.toArray()));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    recorded.add(CommandRef.of(row.getObject(1, UUID.class)));
                }
            }
        } catch (SQLException failure) {
            throw failed("probing the recorded commands of " + operation, failure);
        }
        return recorded;
    }

    private static String statement(Map<RiskOperation, String> statements, RiskOperation operation) {
        String sql = statements.get(Objects.requireNonNull(operation, "operation"));
        if (sql == null) {
            throw new IllegalArgumentException(operation + " is no judged money-moving command");
        }
        return sql;
    }

    /** A canonical UUID - every seam's command ref is one; anything else names no owner's row. */
    private static java.util.Optional<UUID> uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? java.util.Optional.of(parsed) : java.util.Optional.empty();
        } catch (IllegalArgumentException malformed) {
            return java.util.Optional.empty();
        }
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("the owners' stores failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
