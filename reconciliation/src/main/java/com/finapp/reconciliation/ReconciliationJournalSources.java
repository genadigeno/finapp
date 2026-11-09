package com.finapp.reconciliation;

import static com.finapp.platform.sourcejoin.SourceDoor.ADJUSTMENT;
import static com.finapp.platform.sourcejoin.SourceDoor.POSTING;

import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.Keys;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SqlSourceJoin;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * The {@code reconciliation} module's read port for the source-join census (`P15-TSK-035`): a park or an unpark, one entry covering
 * many suspense items of one group, and a resolution's adjustment, one per resolution. The park's figure is the sum of its items;
 * an unpark's is what it released, or - a repudiation's repost, which releases nothing - the repudiation item it opened.
 */
public final class ReconciliationJournalSources implements JournalSources {

    static final String PARK = "SELECT p.id::text AS key_text, p.currency, p.kind,"
            + " (SELECT coalesce(sum(i.amount_minor), 0) FROM reconciliation.suspense_item i WHERE i.park_id = p.id) AS parked,"
            + " (SELECT coalesce(sum(r.amount_minor), 0) FROM reconciliation.suspense_release r WHERE r.park_id = p.id) AS released,"
            + " (SELECT coalesce(sum(i.amount_minor), 0) FROM reconciliation.suspense_item i"
            + " WHERE i.entry_id = p.journal_entry_id AND i.origin = 'REPUDIATION') AS reposted,"
            + " coalesce((SELECT max(i.scale) FROM reconciliation.suspense_item i WHERE i.park_id = p.id OR i.entry_id = p.journal_entry_id),"
            + " (SELECT max(i.scale) FROM reconciliation.suspense_item i JOIN reconciliation.suspense_release r ON r.item_id = i.id WHERE r.park_id = p.id)) AS item_scale"
            + " FROM reconciliation.park p WHERE p.id = ANY (?)";
    static final String RESOLUTION = "SELECT id::text AS key_text, proposed_amount_minor, currency, scale FROM reconciliation.resolution WHERE id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(POSTING, "recon-suspense:", "reconciliation.park by the park id: debits and credits equal the items it parked,"
                    + " or the amount an unpark released or a repudiation reposted",
                    PARK, Keys.suffixUuid(), ReconciliationJournalSources::park),
            new SqlSourceJoin(ADJUSTMENT, "RECONCILIATION", "reconciliation.resolution by the entry's reference: debits and credits equal the resolution's proposed amount",
                    RESOLUTION, Keys.referenceUuid(),
                    row -> SourceFigures.total(row.getString("currency").trim(), row.getInt("scale"), row.getLong("proposed_amount_minor"))));

    @Override
    public String module() {
        return "reconciliation";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }

    private static SourceFigures park(ResultSet row) throws SQLException {
        int scale = row.getInt("item_scale");
        if (row.wasNull()) {
            return SourceFigures.noFigure();
        }
        // Sums, read as decimals: a sum may exceed a bigint, and that is this record's mismatch, never a failed batch.
        BigInteger amount = ("PARK".equals(row.getString("kind")) ? row.getBigDecimal("parked")
                : row.getBigDecimal("released").signum() > 0 ? row.getBigDecimal("released") : row.getBigDecimal("reposted")).toBigIntegerExact();
        return SourceFigures.total(row.getString("currency").trim(), scale, amount);
    }
}
