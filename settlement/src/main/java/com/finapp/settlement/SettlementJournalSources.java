package com.finapp.settlement;

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
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * The {@code settlement} module's read port for the source-join census (`P15-TSK-035`): one entry per accepted batch, one batch per
 * file, whatever the number of lines the file carried. A statement's cash fold lands on {@code CASH_AT_BANK}; a report's fee fold
 * lands on {@code PROCESSING_COSTS}. Which side depends on the sign: the other side of the same purpose is held at zero, so a
 * flipped sign is a mismatch rather than a pass. A repudiation's reversal posts the mirror.
 */
public final class SettlementJournalSources implements JournalSources {

    private static final Set<String> CASH = Set.of("CASH_AT_BANK");
    private static final Set<String> COSTS = Set.of("PROCESSING_COSTS");
    private static final String STATEMENT_FORMAT = "SIM_STATEMENT_TAGGED";

    /** The fee fold: {@code BatchRecognition}'s - F = sum(fee OUT) - sum(fee IN) over its report-fee lines. */
    static final String BATCH = "SELECT b.id::text AS key_text, b.currency, b.net_scale, b.format_id, b.net_minor,"
            + " coalesce((SELECT sum(CASE WHEN l.direction = 'OUTBOUND' THEN l.amount_minor ELSE -l.amount_minor END)"
            + " FROM settlement.line l WHERE l.batch_id = b.id AND l.line_type IN ('PROCESSING_FEE', 'SCHEME_FEE', 'PAYOUT_FEE', 'FX_FEE')),"
            + " 0) AS fee_fold FROM settlement.batch b WHERE b.id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(POSTING, "settlement-batch:", "settlement.batch by the batch id: a statement's net on CASH_AT_BANK, its side by the"
                    + " net's sign; a report's fee fold on PROCESSING_COSTS, its side by the fold's sign",
                    BATCH, Keys.suffixUuid(), row -> figures(row, false)),
            new SqlSourceJoin(REVERSAL, "settlement-batch:", "settlement.batch by the batch id: a repudiation's reversal is the mirror of its acceptance's entry",
                    BATCH, Keys.suffixUuid(), row -> figures(row, true)));

    @Override
    public String module() {
        return "settlement";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }

    private static SourceFigures figures(ResultSet row, boolean mirrored) throws SQLException {
        String currency = row.getString("currency");
        int scale = row.getInt("net_scale");
        boolean statement = STATEMENT_FORMAT.equals(row.getString("format_id"));
        // A decimal: the fold is a SUM, which may exceed a bigint, and a sign flips here without overflow.
        BigInteger signed = (statement ? row.getBigDecimal("net_minor") : row.getBigDecimal("fee_fold")).toBigIntegerExact();
        Set<String> purpose = statement ? CASH : COSTS;
        BigInteger debit = (mirrored ? signed.negate() : signed).max(BigInteger.ZERO);
        BigInteger credit = (mirrored ? signed : signed.negate()).max(BigInteger.ZERO);
        return SourceFigures.of(ExpectedLeg.exactOn(DEBIT, purpose, currency, scale, debit), ExpectedLeg.exactOn(CREDIT, purpose, currency, scale, credit));
    }
}
