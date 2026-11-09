package com.finapp.accounting;

import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.Keys;
import com.finapp.platform.sourcejoin.SourceDoor;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SqlSourceJoin;
import java.util.List;

/**
 * The {@code accounting} module's read port for the source-join census (`P15-TSK-035`): a prior-period correction is named by its row,
 * which names its entry. Presence only: the figures of a prior-period adjustment are the ledger proposal's own lines, and that
 * proposal cannot post other lines than it was read with (ledger's approval, {@code INV-REV-04}). The closed set of presence-only joins
 * is {@code SourceJoinCoverageTest}'s to hold.
 */
public final class AccountingJournalSources implements JournalSources {

    static final String PRIOR_PERIOD = "SELECT journal_entry_id::text AS key_text FROM accounting.prior_period_adjustment"
            + " WHERE journal_entry_id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(SourceDoor.ADJUSTMENT, "ACCOUNTING", "accounting.prior_period_adjustment by its journal entry: the correction's row names the entry; presence only, its figures are the ledger proposal's lines",
                    PRIOR_PERIOD, Keys.entryId(), row -> SourceFigures.presenceOnly("the ledger proposal's lines, held by its approval (INV-REV-04)")));

    @Override
    public String module() {
        return "accounting";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }
}
