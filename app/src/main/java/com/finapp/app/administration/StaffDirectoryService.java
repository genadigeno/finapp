package com.finapp.app.administration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.identity.IdentityDirectory;
import com.finapp.identity.IdentityDirectoryStore;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The staff directory's boundary (`P17-TSK-014`, gaps G-10 and G-11; finapp-admin-panel ADR-C132): the §7 request rules, the
 * transaction - each read and its audit record commit together - and the answers' shapes, identifier-first. No security scope is
 * entered here: the interceptor's names the proven caller, as {@link IdentityAdministrationService} records.
 */
@RequiredArgsConstructor
public class StaffDirectoryService {

    /** The directory's collection name, inside its cursors. */
    static final String DIRECTORY = "identity.directory";
    static final int DIRECTORY_DEFAULT_LIMIT = 50;
    static final int DIRECTORY_MAXIMUM_LIMIT = 200;

    @NonNull private final IdentityDirectory directory;
    @NonNull private final TransactionTemplate administrationTransactions;
    @NonNull private final DataSource dataSource;

    /**
     * A §7 page of the identities that hold, or held, an operator role - newest first by creation, the id breaking the tie - of a
     * live holder of {@code role} when named, in {@code status} when named.
     */
    public IdentityDirectoryPage page(String role, String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DIRECTORY_DEFAULT_LIMIT, DIRECTORY_MAXIMUM_LIMIT);
        Optional<RoleName> roleFilter = Optional.ofNullable(role).map(value -> named(RoleName.class, "role", value));
        Optional<IdentityStatus> statusFilter = Optional.ofNullable(status).map(value -> named(IdentityStatus.class, "status", value));
        Optional<IdentityDirectoryStore.Cursor> after = OperatorCollection.key(DIRECTORY, cursor, 2).map(key -> {
            try {
                return new IdentityDirectoryStore.Cursor(
                        Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        List<IdentityDirectoryStore.Entry> rows =
                inOneTransaction(unitOfWork -> directory.page(unitOfWork, roleFilter, statusFilter, after, size + 1));
        List<IdentityDirectoryStore.Entry> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            IdentityDirectoryStore.Entry tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(DIRECTORY,
                    Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, tail.createdAt())), tail.identityId().value().toString());
        }
        return new IdentityDirectoryPage(served.stream().map(IdentityDirectoryEntry::of).toList(), next);
    }

    /** One identity's detail: unknown and malformed ids are one {@code 404}, recording nothing. */
    public IdentityDetailView detail(String rawIdentityId) {
        IdentityId identityId;
        try {
            identityId = IdentityId.of(UUID.fromString(rawIdentityId));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        return inOneTransaction(unitOfWork -> directory.detail(unitOfWork, identityId))
                .map(IdentityDetailView::of)
                .orElseThrow(StaffDirectoryService::notFound);
    }

    /** The identity a login identifier names, exactly; recorded either way, the identifier never; no match is a {@code 404}. */
    public IdentityLookupAnswer lookup(String loginIdentifier) {
        return inOneTransaction(unitOfWork -> directory.lookup(unitOfWork, loginIdentifier))
                .map(found -> new IdentityLookupAnswer(found.value().toString()))
                .orElseThrow(StaffDirectoryService::notFound);
    }

    /** The role catalogue, built from {@link RoleName} itself: what each role grants and whether it needs an enrolled factor. */
    public List<RoleCatalogueEntry> roles() {
        return Arrays.stream(RoleName.values())
                .map(role -> new RoleCatalogueEntry(
                        role.name(),
                        role.permissions().stream().map(PermissionName::name).sorted().toList(),
                        role.requiresEnrolledFactor()))
                .toList();
    }

    private static <E extends Enum<E>> E named(Class<E> type, String parameter, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid(parameter + " is one of " + Arrays.toString(type.getEnumConstants()) + ".");
        }
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No identity matched the given identifier");
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return administrationTransactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }

    /** A §7 page: {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IdentityDirectoryPage(List<IdentityDirectoryEntry> items, String nextCursor) {}

    /** One identity in the directory: its identifier, status, creation and the roles it holds now - nothing about the person. */
    public record IdentityDirectoryEntry(String identityId, String status, Instant createdAt, List<String> roles) {
        static IdentityDirectoryEntry of(IdentityDirectoryStore.Entry entry) {
            return new IdentityDirectoryEntry(entry.identityId().value().toString(), entry.status().name(), entry.createdAt(),
                    entry.liveRoles().stream().map(RoleName::name).toList());
        }
    }

    /** One identity's detail, identifier-first (ADR-C132 decision 3): no name, no login identifier, no contact detail. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IdentityDetailView(
            String identityId,
            String status,
            Instant suspendedAt,
            String suspensionCause,
            boolean factorEnrolled,
            List<IdentityRoleView> roles,
            Instant createdAt) {
        static IdentityDetailView of(IdentityDirectoryStore.Detail detail) {
            return new IdentityDetailView(
                    detail.identityId().value().toString(),
                    detail.status().name(),
                    detail.suspendedAt().orElse(null),
                    detail.suspensionCause().orElse(null),
                    detail.factorEnrolled(),
                    detail.roles().stream().map(IdentityRoleView::of).toList(),
                    detail.createdAt());
        }
    }

    /** A live holding: the role, when it was granted, and the identity that granted it. */
    public record IdentityRoleView(String role, Instant assignedAt, String assignedBy) {
        static IdentityRoleView of(IdentityDirectoryStore.LiveRole role) {
            return new IdentityRoleView(role.role().name(), role.assignedAt(), role.assignedBy().value().toString());
        }
    }

    /** What a lookup answers: the identity's id, and nothing else. */
    public record IdentityLookupAnswer(String identityId) {}

    /** One role of the catalogue. */
    public record RoleCatalogueEntry(String role, List<String> permissions, boolean requiresEnrolledFactor) {}
}
