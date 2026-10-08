package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads behind the customer's credit profile, the investigator's explanation and the evidence read (`P10-TSK-017`) -
 * every customer read scoped by its owner in the statement, every operator read by an id the explanation names.
 * Stateless; on the caller's unit of work.
 */
public final class JdbcCreditReads {

    /** A source on file: its kind, its provider and when it last answered for the party - never a figure. */
    public record SourceOnFile(CreditSourceKind kind, String providerCode, Instant retrievedAt) {}

    /** A decision still in its validity: the product, the outcome and until when - never an amount. */
    public record CurrentDecision(UUID decisionRequest, CreditProduct product, DecisionOutcome outcome, Instant validUntil) {}

    /** The party's sources on file, the latest answer of each kind and provider. */
    public List<SourceOnFile> sourcesOnFile(Connection unitOfWork, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT source_kind, provider_code, max(retrieved_at) AS retrieved_at FROM credit.credit_record"
                        + " WHERE party_id = ? GROUP BY source_kind, provider_code ORDER BY source_kind, provider_code")) {
            select.setObject(1, party);
            List<SourceOnFile> sources = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    sources.add(new SourceOnFile(CreditSourceKind.valueOf(row.getString("source_kind")),
                            row.getString("provider_code"), row.getTimestamp("retrieved_at").toInstant()));
                }
            }
            return sources;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the sources on file", failure));
        }
    }

    /** The party's decisions still in their validity on the database's clock, oldest first. */
    public List<CurrentDecision> currentDecisions(Connection unitOfWork, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT decision_request_id, product, outcome, valid_until FROM credit.credit_decision"
                        + " WHERE party_id = ? AND valid_until > statement_timestamp() ORDER BY decided_at, id")) {
            select.setObject(1, party);
            List<CurrentDecision> decisions = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    decisions.add(new CurrentDecision(row.getObject("decision_request_id", UUID.class),
                            CreditProduct.valueOf(row.getString("product")), DecisionOutcome.valueOf(row.getString("outcome")),
                            row.getTimestamp("valid_until").toInstant()));
                }
            }
            return decisions;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the current decisions", failure));
        }
    }

    /** When the record was retrieved by its provider, if it exists. */
    public Optional<Instant> retrievedAt(Connection unitOfWork, CreditRecordId record) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT retrieved_at FROM credit.credit_record WHERE id = ?")) {
            select.setObject(1, record.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a credit record", failure));
        }
    }

    /**
     * The evidence row that delivered {@code record}: its data request's latest attempt with a payload, neither a
     * duplicate nor withdrawn - read through V012's column grant, never the content.
     */
    public Optional<CreditEvidenceId> evidenceOf(Connection unitOfWork, CreditRecordId record) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT e.id FROM credit.credit_evidence e JOIN credit.credit_record r ON r.data_request_id = e.data_request_id"
                        + " WHERE r.id = ? AND NOT e.duplicate AND NOT e.consent_withdrawn ORDER BY e.attempt DESC LIMIT 1")) {
            select.setObject(1, record.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(CreditEvidenceId.of(row.getObject(1, UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("finding a record's evidence", failure));
        }
    }

    /** The evidence's ciphertext through {@code credit.read_evidence} - the definer, with the reason it demands. */
    public Optional<CreditEvidenceCipher.Encrypted> readEvidence(Connection unitOfWork, CreditEvidenceId evidence, String reason) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT content_ciphertext, content_nonce, key_version FROM credit.read_evidence(?, ?)")) {
            select.setObject(1, evidence.value());
            select.setString(2, reason);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next() || row.getBytes(1) == null) {
                    return Optional.empty();
                }
                return Optional.of(new CreditEvidenceCipher.Encrypted(row.getBytes(1), row.getBytes(2), row.getInt(3)));
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading credit evidence", failure));
        }
    }
}
