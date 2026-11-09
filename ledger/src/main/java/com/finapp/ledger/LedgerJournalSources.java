package com.finapp.ledger;

import com.finapp.platform.sourcejoin.ExpectedLeg;
import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.Keys;
import com.finapp.platform.sourcejoin.SourceDoor;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SqlSourceJoin;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * The {@code ledger} module's read port for the source-join census (`P15-TSK-035`): a manual adjustment is its proposal, and the
 * proposal's lines are the record of what the approver read. Per currency and direction, the lines the proposal named must be the
 * lines that posted. Prior-period and reconciliation adjustments answer to their own domain records in their own modules.
 */
public final class LedgerJournalSources implements JournalSources {

    /** One row per currency and direction: {@code SqlSourceJoin} joins the rows of one proposal. */
    static final String MANUAL_PROPOSAL = "SELECT p.id::text AS key_text, l.direction, l.currency, l.scale, sum(l.amount_minor) AS amount_minor"
            + " FROM ledger.adjustment_proposal p JOIN ledger.adjustment_proposal_line l ON l.proposal_id = p.id"
            + " WHERE p.id = ANY (?) AND p.origin = 'MANUAL' AND p.status = 'APPROVED'"
            + " GROUP BY p.id, l.direction, l.currency, l.scale";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(SourceDoor.ADJUSTMENT, "MANUAL", "ledger.adjustment_proposal by the proposal id in the key: an approved MANUAL proposal, and per currency and direction its lines equal the entry's",
                    MANUAL_PROPOSAL, Keys.suffixUuid(), row -> {
                        ExpectedLeg.Side side = "DEBIT".equals(row.getString("direction")) ? ExpectedLeg.Side.DEBIT : ExpectedLeg.Side.CREDIT;
                        return SourceFigures.of(new ExpectedLeg(side, Set.of(), row.getString("currency").trim(), row.getInt("scale"),
                                BigInteger.valueOf(row.getLong("amount_minor")), ExpectedLeg.Bound.EXACT));
                    }));

    @Override
    public String module() {
        return "ledger";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }
}
