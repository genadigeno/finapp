package com.finapp.transfers;

import static com.finapp.platform.sourcejoin.SourceDoor.POSTING;
import static com.finapp.platform.sourcejoin.SourceDoor.REVERSAL;

import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.Keys;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SqlSourceJoin;
import java.util.List;

/**
 * The {@code transfers} module's read port for the source-join census (`P15-TSK-035`): every namespace this module posts, joined to
 * {@code transfers.transfer}. Its own table only.
 */
public final class TransferJournalSources implements JournalSources {

    static final String TRANSFER = "SELECT id::text AS key_text, amount_minor, currency, scale FROM transfers.transfer"
            + " WHERE id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(POSTING, "transfer:", "transfers.transfer by the transfer id: its debits and credits equal the"
                    + " transfer's amount in its currency",
                    TRANSFER, Keys.suffixUuid(),
                    row -> SourceFigures.total(row.getString("currency"), row.getInt("scale"), row.getLong("amount_minor"))),
            new SqlSourceJoin(REVERSAL, "transfer:", "transfers.transfer by the transfer id: the reversal mirrors it, debits and"
                    + " credits equal the transfer's amount",
                    TRANSFER, Keys.suffixUuid(),
                    row -> SourceFigures.total(row.getString("currency"), row.getInt("scale"), row.getLong("amount_minor"))));

    @Override
    public String module() {
        return "transfers";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }
}
