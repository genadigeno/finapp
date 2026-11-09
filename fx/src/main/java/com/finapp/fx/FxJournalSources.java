package com.finapp.fx;

import static com.finapp.platform.sourcejoin.ExpectedLeg.Side.CREDIT;
import static com.finapp.platform.sourcejoin.ExpectedLeg.Side.DEBIT;
import static com.finapp.platform.sourcejoin.SourceDoor.POSTING;
import static com.finapp.platform.sourcejoin.SourceDoor.REVERSAL;

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
 * The {@code fx} module's read port for the source-join census (`P15-TSK-035`): a conversion and its reversal, two legs in two
 * currencies each; a cover, the plan's position closed against the provider's clearing. A spread, a residual or a realised result
 * sits on purposes of its own, outside these figures.
 */
public final class FxJournalSources implements JournalSources {

    private static final Set<String> WALLET = Set.of("CUSTOMER_WALLET");
    private static final Set<String> POSITION = Set.of("FX_POSITION");
    private static final Set<String> CLEARINGS = Set.of("FX_PROVIDER_CLEARING", "CORRIDOR_CLEARING");

    static final String TRADE = "SELECT id::text AS key_text, source_currency, destination_currency, source_scale, destination_scale,"
            + " customer_source_minor, customer_destination_minor, position_source_minor, position_destination_minor"
            + " FROM fx.trade WHERE id = ANY (?)";
    static final String COVER = "SELECT cover_id::text AS key_text, sold_currency, sold_minor, sold_scale, bought_currency, bought_minor,"
            + " bought_scale, plan_sold_minor, plan_bought_minor FROM fx.cover_execution"
            + " WHERE journal_entry_id IS NOT NULL AND cover_id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(POSTING, "fx-trade:", "fx.trade by the trade id: the wallet debit and credit at the customer's source and destination amounts,"
                    + " the position credit and debit at the position's, each in its own currency",
                    TRADE, Keys.suffixUuid(), row -> trade(row, false)),
            new SqlSourceJoin(REVERSAL, "fx-trade:", "fx.trade by the trade id: the reversal mirrors the trade's four figures, each in its own currency",
                    TRADE, Keys.suffixUuid(), row -> trade(row, true)),
            new SqlSourceJoin(POSTING, "fx-cover:", "fx.cover_execution by the cover id: the plan's sold on the position debit and its bought on the position credit,"
                    + " the executed sold on the clearing credit and bought on the clearing debit",
                    COVER, Keys.suffixUuid(), FxJournalSources::cover));

    @Override
    public String module() {
        return "fx";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }

    private static SourceFigures trade(ResultSet row, boolean reversal) throws SQLException {
        String source = row.getString("source_currency").trim();
        String destination = row.getString("destination_currency").trim();
        int sourceScale = row.getInt("source_scale");
        int destinationScale = row.getInt("destination_scale");
        ExpectedLeg.Side walletOutbound = reversal ? CREDIT : DEBIT;
        ExpectedLeg.Side walletInbound = reversal ? DEBIT : CREDIT;
        ExpectedLeg.Side positionOutbound = reversal ? DEBIT : CREDIT;
        ExpectedLeg.Side positionInbound = reversal ? CREDIT : DEBIT;
        return SourceFigures.of(
                ExpectedLeg.exactOn(walletOutbound, WALLET, source, sourceScale, row.getLong("customer_source_minor")),
                ExpectedLeg.exactOn(walletInbound, WALLET, destination, destinationScale, row.getLong("customer_destination_minor")),
                ExpectedLeg.exactOn(positionOutbound, POSITION, source, sourceScale, row.getLong("position_source_minor")),
                ExpectedLeg.exactOn(positionInbound, POSITION, destination, destinationScale, row.getLong("position_destination_minor")));
    }

    private static SourceFigures cover(ResultSet row) throws SQLException {
        String sold = row.getString("sold_currency").trim();
        String bought = row.getString("bought_currency").trim();
        int soldScale = row.getInt("sold_scale");
        int boughtScale = row.getInt("bought_scale");
        return SourceFigures.of(
                ExpectedLeg.exactOn(DEBIT, POSITION, sold, soldScale, row.getLong("plan_sold_minor")),
                ExpectedLeg.exactOn(CREDIT, CLEARINGS, sold, soldScale, row.getLong("sold_minor")),
                ExpectedLeg.exactOn(DEBIT, CLEARINGS, bought, boughtScale, row.getLong("bought_minor")),
                ExpectedLeg.exactOn(CREDIT, POSITION, bought, boughtScale, row.getLong("plan_bought_minor")));
    }
}
