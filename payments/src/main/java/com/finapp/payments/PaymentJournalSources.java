package com.finapp.payments;

import static com.finapp.platform.sourcejoin.ExpectedLeg.Side.CREDIT;
import static com.finapp.platform.sourcejoin.ExpectedLeg.Side.DEBIT;
import static com.finapp.platform.sourcejoin.SourceDoor.POSTING;

import com.finapp.platform.sourcejoin.ExpectedLeg;
import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.Keys;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SqlSourceJoin;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * The {@code payments} module's read port for the source-join census (`P15-TSK-035`): every namespace this module posts - the
 * capture, the instant execution, the refund, the unmatched confirmation, the withdrawal, the seven dispute stages, the outbound
 * credit and its return - joined to its own rows. A fee taken net of the amount posts in the same entry on other purposes, so the
 * figure is compared on the purposes that carry the money the record states, never on every line.
 */
public final class PaymentJournalSources implements JournalSources {

    private static final Set<String> CARD_CLEARING = Set.of("SETTLEMENT_CLEARING");
    private static final Set<String> INSTANT_OR_WALLET = Set.of("INSTANT_CLEARING", "CUSTOMER_WALLET");
    private static final Set<String> WALLET_OR_PAYABLE = Set.of("CUSTOMER_WALLET", "MERCHANT_PAYABLE");
    private static final Set<String> REFUND_CREDITS = Set.of("SETTLEMENT_CLEARING", "INSTANT_CLEARING", "CUSTOMER_WALLET");
    private static final Set<String> WALLET = Set.of("CUSTOMER_WALLET");
    /** The counterparty clearings a rail declares (ADR-0065, ADR-0078): which one is the rail's, and this names them all. */
    static final Set<String> CLEARINGS = Set.of("SETTLEMENT_CLEARING", "INSTANT_CLEARING", "PAYOUT_CLEARING",
            "FX_PROVIDER_CLEARING", "CORRIDOR_CLEARING");

    static final String CAPTURE = "SELECT id::text AS key_text, captured_amount_minor, captured_currency, captured_scale"
            + " FROM payments.payment_attempt WHERE id = ANY (?)";
    static final String EXECUTION = "SELECT a.id::text AS key_text, i.amount_minor, i.currency, i.scale"
            + " FROM payments.payment_attempt a JOIN payments.payment_intent i ON i.id = a.intent_id WHERE a.id = ANY (?)";
    static final String REFUND = "SELECT id::text AS key_text, amount_minor, currency, scale FROM payments.refund WHERE id = ANY (?)";
    static final String UNMATCHED = "SELECT k.v AS key_text, u.amount_minor, u.currency, u.scale FROM unnest(?::text[]) k(v)"
            + " JOIN payments.unmatched_confirmation u ON u.rail = split_part(k.v, ':', 1)"
            + " AND u.scheme_reference = substr(k.v, strpos(k.v, ':') + 1)";
    static final String WITHDRAWAL = "SELECT id::text AS key_text, amount_minor, currency, scale FROM payments.withdrawal WHERE id = ANY (?)";
    static final String DISPUTE = "SELECT id::text AS key_text, chargeback_amount_minor, chargeback_currency, chargeback_scale,"
            + " dispute_fee_amount_minor, dispute_fee_currency, dispute_fee_scale FROM payments.dispute WHERE id = ANY (?)";
    static final String REATTRIBUTION = "SELECT k.v AS key_text, d.chargeback_amount_minor, d.chargeback_currency, d.chargeback_scale"
            + " FROM unnest(?::text[]) k(v) JOIN payments.dispute d ON d.id = split_part(k.v, ':', 1)::uuid";
    static final String CREDIT_ROW = "SELECT id::text AS key_text, amount_minor, amount_currency, amount_scale, held_minor,"
            + " held_currency, held_scale FROM payments.outbound_credit WHERE id = ANY (?)";
    static final String CREDIT_RETURN = "SELECT outbound_credit_id::text AS key_text, amount_minor, amount_currency, amount_scale"
            + " FROM payments.outbound_credit_return WHERE outbound_credit_id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(POSTING, "payment-capture:", "payments.payment_attempt by the attempt id: the captured amount on the"
                    + " SETTLEMENT_CLEARING debit and the wallet-or-payable credit; a fee is separate lines on other purposes",
                    CAPTURE, Keys.suffixUuid(), PaymentJournalSources::capture),
            new SqlSourceJoin(POSTING, "payment-execution:", "payments.payment_attempt and its payment_intent by the attempt id: the"
                    + " intent's amount on the instant-clearing-or-wallet debit and the wallet-or-payable credit",
                    EXECUTION, Keys.suffixUuid(), row -> onBoth(row, "amount_minor", "currency", "scale", INSTANT_OR_WALLET, WALLET_OR_PAYABLE)),
            new SqlSourceJoin(POSTING, "payment-refund:", "payments.refund by the refund id: the refund's amount on the wallet-or-payable"
                    + " debit and the clearing-or-wallet credit; a returned fee is separate lines",
                    REFUND, Keys.suffixUuid(), row -> onBoth(row, "amount_minor", "currency", "scale", WALLET_OR_PAYABLE, REFUND_CREDITS)),
            new SqlSourceJoin(POSTING, "unmatched-confirmation:", "payments.unmatched_confirmation by rail and scheme reference: debits and"
                    + " credits equal its amount",
                    UNMATCHED, Keys.suffixMatching(Keys.railReference()), row -> total(row, "amount_minor", "currency", "scale")),
            new SqlSourceJoin(POSTING, "wallet-withdrawal:", "payments.withdrawal by the withdrawal id: debits and credits equal its amount",
                    WITHDRAWAL, Keys.suffixUuid(), row -> total(row, "amount_minor", "currency", "scale")),
            new SqlSourceJoin(POSTING, "dispute-chargeback:", "payments.dispute by the dispute id: debits and credits equal the chargeback amount",
                    DISPUTE, Keys.suffixUuid(), row -> total(row, "chargeback_amount_minor", "chargeback_currency", "chargeback_scale")),
            new SqlSourceJoin(POSTING, "dispute-attribution:", "payments.dispute by the dispute id: at most the chargeback amount (a counterparty's"
                    + " share grows under re-attribution, so the row states only its ceiling)",
                    DISPUTE, Keys.suffixUuid(), row -> atMost(row, "chargeback_amount_minor", "chargeback_currency", "chargeback_scale")),
            new SqlSourceJoin(POSTING, "dispute-won:", "payments.dispute by the dispute id: debits and credits equal the chargeback amount returned",
                    DISPUTE, Keys.suffixUuid(), row -> total(row, "chargeback_amount_minor", "chargeback_currency", "chargeback_scale")),
            new SqlSourceJoin(POSTING, "dispute-restoration:", "payments.dispute by the dispute id: at most the chargeback amount (the share at the win)",
                    DISPUTE, Keys.suffixUuid(), row -> atMost(row, "chargeback_amount_minor", "chargeback_currency", "chargeback_scale")),
            new SqlSourceJoin(POSTING, "dispute-loss:", "payments.dispute by the dispute id: at most the chargeback amount (the excess at the loss)",
                    DISPUTE, Keys.suffixUuid(), row -> atMost(row, "chargeback_amount_minor", "chargeback_currency", "chargeback_scale")),
            new SqlSourceJoin(POSTING, "dispute-fee:", "payments.dispute by the dispute id: debits and credits equal the dispute fee",
                    DISPUTE, Keys.suffixUuid(), row -> total(row, "dispute_fee_amount_minor", "dispute_fee_currency", "dispute_fee_scale")),
            new SqlSourceJoin(POSTING, "dispute-reattribution:", "payments.dispute by the dispute id in the key's head: at most the chargeback amount (a re-attribution moves part of it)",
                    REATTRIBUTION, Keys.suffixMatching(Keys.uuidHead()), row -> atMost(row, "chargeback_amount_minor", "chargeback_currency", "chargeback_scale")),
            new SqlSourceJoin(POSTING, "outbound-credit:", "payments.outbound_credit by the credit id: the held amount on the wallet debit and the instructed amount on the clearing credit, each in its own currency",
                    CREDIT_ROW, Keys.suffixUuid(), PaymentJournalSources::outboundCredit),
            new SqlSourceJoin(POSTING, "crossborder-return:", "payments.outbound_credit_return by the credit id: its amount on the clearing debit and the wallet credit, in the returned currency; the fee refund is lines in the source currency",
                    CREDIT_RETURN, Keys.suffixUuid(), row -> onBoth(row, "amount_minor", "amount_currency", "amount_scale", CLEARINGS, WALLET)));

    @Override
    public String module() {
        return "payments";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }

    private static SourceFigures capture(ResultSet row) throws SQLException {
        return onBoth(row, "captured_amount_minor", "captured_currency", "captured_scale", CARD_CLEARING, WALLET_OR_PAYABLE);
    }

    private static SourceFigures outboundCredit(ResultSet row) throws SQLException {
        return SourceFigures.of(
                ExpectedLeg.exactOn(DEBIT, WALLET, row.getString("held_currency"), row.getInt("held_scale"), row.getLong("held_minor")),
                ExpectedLeg.exactOn(CREDIT, CLEARINGS, row.getString("amount_currency"), row.getInt("amount_scale"), row.getLong("amount_minor")));
    }

    private static SourceFigures total(ResultSet row, String minor, String currency, String scale) throws SQLException {
        long amount = row.getLong(minor);
        return row.wasNull() ? SourceFigures.noFigure() : SourceFigures.total(row.getString(currency).trim(), row.getInt(scale), amount);
    }

    private static SourceFigures atMost(ResultSet row, String minor, String currency, String scale) throws SQLException {
        long amount = row.getLong(minor);
        if (row.wasNull()) {
            return SourceFigures.noFigure();
        }
        String code = row.getString(currency).trim();
        int exponent = row.getInt(scale);
        return SourceFigures.of(ExpectedLeg.atMost(DEBIT, code, exponent, amount), ExpectedLeg.atMost(CREDIT, code, exponent, amount));
    }

    private static SourceFigures onBoth(ResultSet row, String minor, String currency, String scale, Set<String> debits, Set<String> credits)
            throws SQLException {
        long amount = row.getLong(minor);
        if (row.wasNull()) {
            return SourceFigures.noFigure();
        }
        String code = row.getString(currency).trim();
        int exponent = row.getInt(scale);
        return SourceFigures.of(ExpectedLeg.exactOn(DEBIT, debits, code, exponent, amount), ExpectedLeg.exactOn(CREDIT, credits, code, exponent, amount));
    }
}
