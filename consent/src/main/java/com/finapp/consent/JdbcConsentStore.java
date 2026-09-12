package com.finapp.consent;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ConsentStore} over JDBC (ADR-0033: explicit SQL, the caller's connection).
 *
 * <p><strong>Every read orders by {@code seq}</strong>, the server-assigned identity column —
 * never by {@code recorded_at}, whose values come from N instances' clocks and cannot totally
 * order concurrent facts. {@code seq} is assigned by the one server every instance shares, so
 * which of two racing facts is later has one answer and every reader gives it.
 */
public final class JdbcConsentStore implements ConsentStore<Connection> {

    @Override
    public void append(Connection unitOfWork, ConsentRecord record) {
        String sql =
                "INSERT INTO consent.consent_record"
                        + " (id, party_id, purpose, action, text_version, recorded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, record.id().value());
            insert.setObject(2, record.partyId());
            insert.setString(3, record.purpose().name());
            insert.setString(4, record.action().name());
            insert.setInt(5, record.textVersion());
            insert.setTimestamp(6, Timestamp.from(record.recordedAt()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new ConsentStorageException(
                    DatabaseFailure.describe(
                            "appending a consent record " + record.id(), e));
        }
    }

    @Override
    public Optional<ConsentRecord> latestFor(
            Connection unitOfWork, UUID partyId, ConsentPurpose purpose) {
        String sql =
                "SELECT id, party_id, purpose, action, text_version, recorded_at"
                        + " FROM consent.consent_record"
                        + " WHERE party_id = ? AND purpose = ?"
                        + " ORDER BY seq DESC LIMIT 1";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, partyId);
            select.setString(2, purpose.name());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? Optional.of(
                                ConsentRecord.rehydrate(
                                        ConsentRecordId.of((UUID) rows.getObject("id")),
                                        (UUID) rows.getObject("party_id"),
                                        ConsentPurpose.valueOf(rows.getString("purpose")),
                                        ConsentAction.valueOf(rows.getString("action")),
                                        rows.getInt("text_version"),
                                        rows.getTimestamp("recorded_at").toInstant()))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new ConsentStorageException(
                    DatabaseFailure.describe(
                            "reading the latest consent record for party " + partyId, e));
        }
    }

    @Override
    public boolean hasCurrentBasis(
            Connection unitOfWork, UUID partyId, ConsentPurpose purpose) {
        // One statement, one snapshot: the fact and the text-version state it is judged
        // against cannot come from two instants. Absence is refusal (INV-CNS-01): no row and
        // a latest WITHDRAWAL both fall through to false, indistinguishably. The NOT EXISTS
        // is INV-CNS-04's bite: a grant against v3 stops being a basis the moment any later
        // version of the same purpose's text records requires_reconsent.
        String sql =
                "SELECT (r.action = 'GRANT'"
                        + "        AND NOT EXISTS (SELECT 1 FROM consent.consent_text t"
                        + "                         WHERE t.purpose = r.purpose"
                        + "                           AND t.version > r.text_version"
                        + "                           AND t.requires_reconsent))"
                        + " FROM consent.consent_record r"
                        + " WHERE r.party_id = ? AND r.purpose = ?"
                        + " ORDER BY r.seq DESC LIMIT 1";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, partyId);
            select.setString(2, purpose.name());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new ConsentStorageException(
                    DatabaseFailure.describe(
                            "deriving the consent basis for party " + partyId, e));
        }
    }

    @Override
    public ConsentText currentTextFor(Connection unitOfWork, ConsentPurpose purpose) {
        String sql =
                "SELECT purpose, version, body, requires_reconsent, published_at"
                        + " FROM consent.consent_text"
                        + " WHERE purpose = ?"
                        + " ORDER BY version DESC LIMIT 1";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setString(1, purpose.name());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    // Every purpose's v1 is seeded by the migration that created the table, so
                    // an empty answer means migrations did not run - a deployment defect, and
                    // returning empty would let a caller treat it as "no text, no gate".
                    throw new ConsentStorageException(
                            "no consent text exists for purpose " + purpose
                                    + " - the V002 seed should make this impossible");
                }
                return new ConsentText(
                        ConsentPurpose.valueOf(rows.getString("purpose")),
                        rows.getInt("version"),
                        rows.getString("body"),
                        rows.getBoolean("requires_reconsent"),
                        rows.getTimestamp("published_at").toInstant());
            }
        } catch (SQLException e) {
            throw new ConsentStorageException(
                    DatabaseFailure.describe(
                            "reading the current consent text for " + purpose, e));
        }
    }
}
