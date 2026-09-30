package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Plain-JDBC readings over `V002`'s register (ADR-0033, `P8-TSK-007`). */
public final class JdbcExpectationReadings implements ExpectationReadings<Connection> {

    @Override
    public List<OpenRemainder> openRemainders(Connection unitOfWork) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT source_id, position_purpose, direction, amount_minor,"
                                + " allocated_minor, resolved_minor, currency, scale"
                                + " FROM reconciliation.expectation"
                                + " WHERE status IN ('OPEN', 'PARTIALLY_SETTLED')")) {
            try (ResultSet rows = select.executeQuery()) {
                List<OpenRemainder> remainders = new ArrayList<>();
                while (rows.next()) {
                    CurrencyCode currency =
                            CurrencyCode.of(rows.getString("currency").stripTrailing());
                    short scale = rows.getShort("scale");
                    // Row-level Money arithmetic, caller-level fold (P3-TSK-008's rule).
                    Money remainder =
                            Money.ofPersisted(rows.getLong("amount_minor"), currency, scale)
                                    .minus(
                                            Money.ofPersisted(
                                                    rows.getLong("allocated_minor"),
                                                    currency,
                                                    scale))
                                    .minus(
                                            Money.ofPersisted(
                                                    rows.getLong("resolved_minor"),
                                                    currency,
                                                    scale));
                    remainders.add(
                            new OpenRemainder(
                                    rows.getObject("source_id", UUID.class),
                                    AccountPurpose.valueOf(
                                            rows.getString("position_purpose")),
                                    ExpectationDirection.valueOf(rows.getString("direction")),
                                    remainder));
                }
                return List.copyOf(remainders);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    DatabaseFailure.describe("reading the open remainders", failure));
        }
    }

    @Override
    public List<OpenItemRemainder> openItemRemainders(Connection unitOfWork) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        // Allocating lines only (fees' effect IS the recognition entry),
                        // undisposed statuses only - the list generated from the enum. An
                        // attributed bank line's remainder stands in its ATTRIBUTED source's
                        // position identity - the recognition credited that source's
                        // clearing position (P8-TSK-016).
                        "SELECT COALESCE(attributed_source_id, source_id) AS source_id,"
                                + " position_purpose, direction, amount_minor,"
                                + " allocated_minor, parked_minor, offset_minor, currency,"
                                + " scale"
                                + " FROM reconciliation.external_item"
                                + " WHERE status IN ('PENDING', 'UNMATCHED')"
                                + " AND line_type IN (" + ExternalLineType.sqlAllocatingList()
                                + ")")) {
            try (ResultSet rows = select.executeQuery()) {
                List<OpenItemRemainder> remainders = new ArrayList<>();
                while (rows.next()) {
                    CurrencyCode currency =
                            CurrencyCode.of(rows.getString("currency").stripTrailing());
                    short scale = rows.getShort("scale");
                    // Row-level Money arithmetic, caller-level fold (P3-TSK-008's rule).
                    Money remainder =
                            Money.ofPersisted(rows.getLong("amount_minor"), currency, scale)
                                    .minus(
                                            Money.ofPersisted(
                                                    rows.getLong("allocated_minor"),
                                                    currency,
                                                    scale))
                                    .minus(
                                            Money.ofPersisted(
                                                    rows.getLong("parked_minor"),
                                                    currency,
                                                    scale))
                                    .minus(
                                            Money.ofPersisted(
                                                    rows.getLong("offset_minor"),
                                                    currency,
                                                    scale));
                    String position = rows.getString("position_purpose");
                    if (position == null) {
                        // An unpositioned line (an unattributed bank line) is born PARKED
                        // in its acceptance transaction and so never undisposed in any
                        // snapshot: reaching here is a broken record, and a proof over it
                        // fails loudly rather than skip value (INV-REC-06).
                        throw new ReconciliationStorageException(
                                "an undisposed allocating external item stands in no"
                                        + " position (V008's position rule)");
                    }
                    remainders.add(
                            new OpenItemRemainder(
                                    rows.getObject("source_id", UUID.class),
                                    AccountPurpose.valueOf(position),
                                    ExpectationDirection.valueOf(rows.getString("direction")),
                                    remainder));
                }
                return List.copyOf(remainders);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    DatabaseFailure.describe("reading the open item remainders", failure));
        }
    }

    @Override
    public List<KnownLine> knownLines(Connection unitOfWork) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT journal_entry_id, ledger_account_id"
                                + " FROM reconciliation.expectation"
                                + " WHERE journal_entry_id IS NOT NULL")) {
            try (ResultSet rows = select.executeQuery()) {
                List<KnownLine> known = new ArrayList<>();
                while (rows.next()) {
                    known.add(
                            new KnownLine(
                                    rows.getObject("journal_entry_id", UUID.class),
                                    rows.getObject("ledger_account_id", UUID.class)));
                }
                return List.copyOf(known);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    DatabaseFailure.describe("reading the known lines", failure));
        }
    }

    @Override
    public Map<UUID, Long> openCountBySource(Connection unitOfWork) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT source_id, count(*) AS open_count"
                                + " FROM reconciliation.expectation"
                                + " WHERE status IN ('OPEN', 'PARTIALLY_SETTLED')"
                                + " GROUP BY source_id")) {
            try (ResultSet rows = select.executeQuery()) {
                Map<UUID, Long> counts = new HashMap<>();
                while (rows.next()) {
                    counts.put(
                            rows.getObject("source_id", UUID.class),
                            rows.getLong("open_count"));
                }
                return Map.copyOf(counts);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    DatabaseFailure.describe("counting the open expectations", failure));
        }
    }
}
