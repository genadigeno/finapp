package com.finapp.crossborder;

import static com.finapp.platform.sourcejoin.SourceDoor.POSTING;

import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.Keys;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SqlSourceJoin;
import java.util.List;

/**
 * The {@code crossborder} module's read port for the source-join census (`P15-TSK-035`): the fee refunded when a parked return
 * resolves four-eyes is the frozen offer's fee, and that row is this module's, found through the payment that names the credit.
 */
public final class CrossBorderJournalSources implements JournalSources {

    static final String RETURN_FEE = "SELECT p.outbound_credit_id::text AS key_text, o.fee_minor, o.source_currency, o.fee_scale"
            + " FROM crossborder.payment p JOIN crossborder.payment_offer o ON o.id = p.offer_id"
            + " WHERE p.outbound_credit_id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(POSTING, "crossborder-return-fee:", "crossborder.payment by the credit id, its offer's fee: debits and credits"
                    + " equal the fee, in the source currency",
                    RETURN_FEE, Keys.suffixUuid(),
                    row -> SourceFigures.total(row.getString("source_currency"), row.getInt("fee_scale"), row.getLong("fee_minor"))));

    @Override
    public String module() {
        return "crossborder";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }
}
