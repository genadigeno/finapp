package com.finapp.app.security;

import com.finapp.platform.security.KeyRing;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * Refuses to start while a retired key version still has rows under it (`P15-TSK-019`, ADR-0123 point 4).
 *
 * <p>The rule, and why a check: a key version is retired when its key is gone, so every row under it would be unreadable,
 * and every row here is evidence that may not be re-encrypted. {@code KeyRing} already refuses a version nothing accounts
 * for; this asks the real tables whether a version declared retired really has nothing under it. A replica started with
 * such a retirement refuses to start, so the rollout stalls and the replicas it would replace keep serving.
 *
 * <p><strong>Read at startup, on every replica, and only if something is retired.</strong> With no retirement declared
 * nothing is read at all, which is every ring today: {@code INSERT}-only evidence never stops having rows under a version,
 * so C12's purge is what would first make one retirable. Unprovable refuses: a column this replica cannot read, an unknown
 * ring, an unreachable database while a retirement is declared. A retired version is only ever claimed with its
 * evidence on the table.
 *
 * <p><strong>The stamped columns are held to the schema</strong>: {@code KeyRingRetirementGuardCoversEveryColumnTest} reads
 * every migration and fails on a {@code key_version} column this table does not list. {@code credit.credit_evidence}'s
 * version column is not readable by the application's role ({@code V012} grants it the row's identifying columns only),
 * so that ring's retirements are refused outright until a grant exists.
 */
@Slf4j
@Component
public class KeyRingRetirementGuard {

    /** Bean name of each ring, and every column its versions are stamped in, by {@code schema.table.column}. */
    static final Map<String, List<String>> STAMPED_COLUMNS = Map.of(
            "mfaKeyRing", List.of("identity.mfa_enrolment.key_version"),
            "documentKeyRing", List.of(
                    "kyc.kyc_document.key_version",
                    "kyc.verification_evidence.key_version",
                    "kyc.counterparty_screening.subject_key_version",
                    "kyc.counterparty_screening_attempt.evidence_key_version"),
            "paymentEvidenceKeyRing", List.of("payments.provider_evidence.key_version"),
            "disputeEvidenceKeyRing", List.of("payments.dispute_evidence.key_version"),
            "payoutEvidenceKeyRing", List.of("merchant.payout_evidence.key_version"),
            "settlementFileKeyRing", List.of("settlement.file.key_version"),
            "fxEvidenceKeyRing", List.of("fx.fx_provider_evidence.key_version"),
            "creditEvidenceKeyRing", List.of("credit.credit_evidence.key_version"));

    /** Columns the application role cannot read: a ring with one of these can never be proved retirable from here. */
    static final Set<String> NOT_READABLE_BY_THE_APPLICATION = Set.of("credit.credit_evidence.key_version");

    private static final int CHECK_TIMEOUT_SECONDS = 60;

    /** Whether any row stands under one of the versions, in one column. */
    @FunctionalInterface
    interface RowCensus {
        boolean anyRowUnder(String qualifiedColumn, Set<Integer> versions) throws SQLException;
    }

    /** A connection source: its own seam, the metrics precedent. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    KeyRingRetirementGuard(ListableBeanFactory beans, DataSource dataSource) {
        verify(beans.getBeansOfType(KeyRing.class), new JdbcRowCensus(dataSource::getConnection));
    }

    /**
     * The startup rule, over planted rings and rows in its tests.
     *
     * @throws IllegalStateException if a retired version has rows under it, or that could not be established
     */
    static void verify(Map<String, KeyRing> rings, RowCensus census) {
        for (Map.Entry<String, KeyRing> named : rings.entrySet()) {
            if (named.getValue().retiredVersions().isEmpty()) {
                continue;
            }
            checkRetired(named.getKey(), named.getValue(), census);
        }
    }

    private static void checkRetired(String ringName, KeyRing ring, RowCensus census) {
        List<String> columns = STAMPED_COLUMNS.get(ringName);
        if (columns == null) {
            throw new IllegalStateException(
                    "Refusing to start: " + ring.name() + " declares retired key versions and its columns are not known here");
        }
        Set<Integer> retired = ring.retiredVersions();
        for (String column : columns) {
            if (NOT_READABLE_BY_THE_APPLICATION.contains(column)) {
                throw new IllegalStateException(
                        "Refusing to start: " + ring.name() + " declares retired key versions and " + column
                                + " cannot be read by the application's role, so no retirement here can be proved");
            }
            boolean rowsRemain;
            try {
                rowsRemain = census.anyRowUnder(column, retired);
            } catch (SQLException unprovable) {
                throw new IllegalStateException(
                        "Refusing to start: whether rows remain under the retired versions of " + ring.name() + " in " + column
                                + " could not be established (" + unprovable.getSQLState() + ")");
            }
            if (rowsRemain) {
                throw new IllegalStateException(
                        "Refusing to start: " + ring.name() + " declares retired key versions " + retired + " and " + column
                                + " still holds rows under them. A version is retired only when no row stands under it");
            }
        }
        log.info("{}: retired key versions {} hold no rows", ring.name(), retired);
    }

    /** The real tables: one connection, read-only, bounded per column. */
    static final class JdbcRowCensus implements RowCensus {

        private final Connections connections;

        JdbcRowCensus(Connections connections) {
            this.connections = connections;
        }

        @Override
        public boolean anyRowUnder(String qualifiedColumn, Set<Integer> versions) throws SQLException {
            int columnStart = qualifiedColumn.lastIndexOf('.');
            String table = qualifiedColumn.substring(0, columnStart);
            String column = qualifiedColumn.substring(columnStart + 1);
            try (Connection connection = connections.open()) {
                connection.setReadOnly(true);
                try (PreparedStatement read =
                        connection.prepareStatement("SELECT 1 FROM " + table + " WHERE " + column + " = ANY (?) LIMIT 1")) {
                    read.setQueryTimeout(CHECK_TIMEOUT_SECONDS);
                    Array array = connection.createArrayOf("integer", versions.toArray());
                    read.setArray(1, array);
                    try (ResultSet row = read.executeQuery()) {
                        return row.next();
                    }
                }
            }
        }
    }
}
