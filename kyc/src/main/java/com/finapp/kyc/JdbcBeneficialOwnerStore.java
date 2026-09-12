package com.finapp.kyc;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Plain-JDBC storage for beneficial owners (ADR-0033, `P2-TSK-015`).
 *
 * <p>Every check in {@link #declare} runs <em>after</em> the case row is locked
 * {@code FOR UPDATE} — see {@link BeneficialOwnerStore#declare} for why the lock, not the
 * predicate, is what makes the answers trustworthy under concurrency. The unique index on
 * {@code (case_id, owner_party_id)} stays as defence in depth behind the locked duplicate
 * check, so the insert itself is plain and loud (`P2-TSK-013`'s idiom): a violation here would
 * mean the lock protocol was broken, which must fail the transaction rather than converge.
 */
public final class JdbcBeneficialOwnerStore implements BeneficialOwnerStore<Connection> {

    private static final String TABLE = "kyc.beneficial_owner";

    /** The statuses still accepting declarations: everything before the set freezes at RFD. */
    private static final List<KycCaseStatus> ACCEPTING =
            List.of(
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    KycCaseStatus.IN_REVIEW);

    @Override
    public Declared declare(Connection unitOfWork, BeneficialOwner owner) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        try {
            Optional<LockedCase> locked = lockCase(unitOfWork, owner.caseId());
            if (locked.isEmpty()
                    || locked.get().kind() != KycCaseKind.KYB
                    || !ACCEPTING.contains(locked.get().status())) {
                return Declared.CASE_NOT_ACCEPTING;
            }
            if (alreadyDeclared(unitOfWork, owner)) {
                return Declared.ALREADY_DECLARED;
            }
            if (owner.stakeBasisPoints().isPresent()
                    && declaredStake(unitOfWork, owner.caseId())
                                    + owner.stakeBasisPoints().getAsInt()
                            > 10_000) {
                return Declared.STAKE_EXCEEDS_WHOLE;
            }
            insert(unitOfWork, owner);
            return Declared.DECLARED;
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "declaring an owner onto KYB case " + owner.caseId(), failure));
        }
    }

    @Override
    public List<KycCaseId> parentCasesOf(Connection unitOfWork, KycCaseId verificationCaseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(verificationCaseId, "verificationCaseId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT case_id FROM " + TABLE
                                + " WHERE verification_case_id = ?")) {
            select.setObject(1, verificationCaseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<KycCaseId> parents = new ArrayList<>();
                while (rows.next()) {
                    parents.add(KycCaseId.of(rows.getObject("case_id", UUID.class)));
                }
                return List.copyOf(parents);
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "reading the KYB cases awaiting verification case "
                                    + verificationCaseId,
                            failure));
        }
    }

    @Override
    public List<DeclaredOwner> ownersOf(Connection unitOfWork, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT bo.id, bo.case_id, bo.owner_party_id, bo.verification_case_id,"
                                + " bo.stake_basis_points, bo.control_role, bo.declared_at,"
                                + " oc.status AS verification_status"
                                + " FROM " + TABLE + " bo"
                                + " JOIN kyc.kyc_case oc ON oc.id = bo.verification_case_id"
                                + " WHERE bo.case_id = ?"
                                + " ORDER BY bo.declared_at, bo.id")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<DeclaredOwner> owners = new ArrayList<>();
                while (rows.next()) {
                    int stake = rows.getInt("stake_basis_points");
                    boolean stakeAbsent = rows.wasNull();
                    String role = rows.getString("control_role");
                    owners.add(
                            new DeclaredOwner(
                                    BeneficialOwner.rehydrate(
                                            BeneficialOwnerId.of(
                                                    rows.getObject("id", UUID.class)),
                                            KycCaseId.of(rows.getObject("case_id", UUID.class)),
                                            rows.getObject("owner_party_id", UUID.class),
                                            KycCaseId.of(
                                                    rows.getObject(
                                                            "verification_case_id", UUID.class)),
                                            stakeAbsent
                                                    ? OptionalInt.empty()
                                                    : OptionalInt.of(stake),
                                            Optional.ofNullable(role).map(ControlRole::valueOf),
                                            rows.getTimestamp("declared_at").toInstant()),
                                    KycCaseStatus.valueOf(
                                            rows.getString("verification_status"))));
                }
                return List.copyOf(owners);
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "reading the declared owners of KYB case " + caseId, failure));
        }
    }

    private record LockedCase(KycCaseStatus status, KycCaseKind kind) {}

    /**
     * Locks the case row and reports what it holds. Empty when no such case exists — which the
     * caller reports as {@code CASE_NOT_ACCEPTING} rather than distinguishing, because a
     * declaration onto a case that never existed and one onto a frozen case call for the same
     * caller behaviour.
     */
    private static Optional<LockedCase> lockCase(Connection unitOfWork, KycCaseId caseId)
            throws SQLException {
        try (PreparedStatement lock =
                unitOfWork.prepareStatement(
                        "SELECT status, case_kind FROM kyc.kyc_case WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, caseId.value());
            try (ResultSet row = lock.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new LockedCase(
                                KycCaseStatus.valueOf(row.getString("status")),
                                KycCaseKind.valueOf(row.getString("case_kind"))));
            }
        }
    }

    private static boolean alreadyDeclared(Connection unitOfWork, BeneficialOwner owner)
            throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT 1 FROM " + TABLE + " WHERE case_id = ? AND owner_party_id = ?")) {
            select.setObject(1, owner.caseId().value());
            select.setObject(2, owner.ownerPartyId());
            try (ResultSet row = select.executeQuery()) {
                return row.next();
            }
        }
    }

    private static long declaredStake(Connection unitOfWork, KycCaseId caseId)
            throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT COALESCE(SUM(stake_basis_points), 0) AS declared FROM " + TABLE
                                + " WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong("declared");
            }
        }
    }

    private static void insert(Connection unitOfWork, BeneficialOwner owner)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE
                                + " (id, case_id, owner_party_id, verification_case_id,"
                                + " stake_basis_points, control_role, declared_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, owner.id().value());
            insert.setObject(2, owner.caseId().value());
            insert.setObject(3, owner.ownerPartyId());
            insert.setObject(4, owner.verificationCaseId().value());
            OptionalInt stake = owner.stakeBasisPoints();
            if (stake.isPresent()) {
                insert.setInt(5, stake.getAsInt());
            } else {
                insert.setNull(5, java.sql.Types.INTEGER);
            }
            insert.setString(6, owner.controlRole().map(Enum::name).orElse(null));
            insert.setTimestamp(7, Timestamp.from(owner.declaredAt()));
            insert.executeUpdate();
        }
    }
}
