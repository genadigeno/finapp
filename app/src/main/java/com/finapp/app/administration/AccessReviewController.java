package com.finapp.app.administration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.RoleName;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The check of the holdings the factor rule found standing (`P15-TSK-039`; {@code PRIVILEGED_ACCESS_REVIEW.md} §5 and §6).
 *
 * <p>Refusing a grant says nothing about a role held since before the rule. This names them: each live holding of a role
 * that needs an enrolled factor ({@code RoleName.requiresEnrolledFactor}: one that can redirect a merchant's money, and since
 * `P15-TSK-032` {@code ADMINISTRATOR} and the risk desk's approver) whose holder has none. It revokes nothing, and no
 * migration does either: a role a person holds is theirs until someone decides otherwise, with a reason, and the review is
 * where that is decided. The administrator's read, because {@code ROLE_ASSIGN} is the permission that acts on the answer;
 * recorded, and on a {@code MULTI_FACTOR} session: the answer is a list of privileged holders with no second factor, which
 * a stolen password must not be enough to read.
 */
@RestController
@RequestMapping(path = "/operator/access-review", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccessReviewController {

    /** The factorless review's collection name, inside its cursors (`P17-TSK-042`). */
    static final String FACTORLESS = "identity.factorless-holdings";

    static final int FACTORLESS_DEFAULT_LIMIT = 50;
    static final int FACTORLESS_MAXIMUM_LIMIT = 200;

    /** The SoD-conflict review's collection name, inside its cursors (`P17-TSK-122`). */
    static final String SOD_CONFLICTS = "identity.sod-conflicts";

    static final int SOD_CONFLICTS_DEFAULT_LIMIT = 50;
    static final int SOD_CONFLICTS_MAXIMUM_LIMIT = 200;

    @NonNull private final IdentityAdministrationService administration;

    /**
     * The live holdings of a role that needs an enrolled factor, by a holder with none, as a section 7 collection
     * (`P17-TSK-042`; read "at most 200 with {@code truncated}" until then): oldest first, the identity and then the role
     * breaking the tie; {@code limit} 50 unless named, 200 at most; the opaque {@code cursor} of the page before. Every page
     * recorded.
     */
    @GetMapping("/factorless-holdings")
    @RequiresPermission(PermissionName.ROLE_ASSIGN)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public FactorlessHoldingPage listFactorlessHoldings(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        int size = OperatorCollection.limit(limit, FACTORLESS_DEFAULT_LIMIT, FACTORLESS_MAXIMUM_LIMIT);
        Optional<RoleAssignmentStore.FactorlessHolding> after = factorlessCursor(cursor);
        IdentityAdministration.FactorlessHoldings served = administration.factorlessHoldings(after, size);
        String next = null;
        if (served.more() && !served.holdings().isEmpty()) {
            RoleAssignmentStore.FactorlessHolding tail = served.holdings().get(served.holdings().size() - 1);
            next = OperatorCollection.cursor(FACTORLESS, tail.assignedAt().toString(), tail.identityId().value().toString(),
                    tail.role().name());
        }
        return new FactorlessHoldingPage(served.holdings().stream().map(FactorlessHoldingView::of).toList(), next);
    }

    /** The holding {@code cursor} names as the last one served, or none for the first page; anything else refused. */
    static Optional<RoleAssignmentStore.FactorlessHolding> factorlessCursor(String cursor) {
        return OperatorCollection.key(FACTORLESS, cursor, 3).map(key -> {
            try {
                return new RoleAssignmentStore.FactorlessHolding(IdentityId.of(UUID.fromString(key.get(1))),
                        RoleName.valueOf(key.get(2)), Instant.parse(key.get(0)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    /**
     * The live holdings that conflict under the owner's segregation-of-duties table (`P17-TSK-016`): those granted before the rule,
     * which the grant now refuses. It revokes nothing; the review decides each, through the revocation door. A section 7
     * collection (`P17-TSK-122`; read "at most 200 with {@code truncated}" until then): oldest conflict first - since, the later
     * of the two grants - the identity, the first role and the second breaking the tie; {@code limit} 50 unless named, 200 at
     * most; the opaque {@code cursor} of the page before. Under {@code ROLE_ASSIGN} on a {@code MULTI_FACTOR} session, every page
     * recorded, as the factorless review.
     */
    @GetMapping("/sod-conflicts")
    @RequiresPermission(PermissionName.ROLE_ASSIGN)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public SodConflictPage listSodConflicts(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        int size = OperatorCollection.limit(limit, SOD_CONFLICTS_DEFAULT_LIMIT, SOD_CONFLICTS_MAXIMUM_LIMIT);
        Optional<RoleAssignmentStore.ConflictingHolding> after = sodConflictCursor(cursor);
        IdentityAdministration.SodConflicts served = administration.sodConflicts(after, size);
        String next = null;
        if (served.more() && !served.conflicts().isEmpty()) {
            RoleAssignmentStore.ConflictingHolding tail = served.conflicts().get(served.conflicts().size() - 1);
            next = OperatorCollection.cursor(SOD_CONFLICTS, tail.since().toString(), tail.identityId().value().toString(),
                    tail.first().name(), tail.second().name());
        }
        return new SodConflictPage(served.conflicts().stream().map(SodConflictView::of).toList(), next);
    }

    /** The conflict {@code cursor} names as the last one served, or none for the first page; anything else refused. */
    static Optional<RoleAssignmentStore.ConflictingHolding> sodConflictCursor(String cursor) {
        return OperatorCollection.key(SOD_CONFLICTS, cursor, 4).map(key -> {
            try {
                return new RoleAssignmentStore.ConflictingHolding(IdentityId.of(UUID.fromString(key.get(1))),
                        RoleName.valueOf(key.get(2)), RoleName.valueOf(key.get(3)), Instant.parse(key.get(0)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    /** A page of the SoD-conflict review (`P17-TSK-122`): {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SodConflictPage(List<SodConflictView> items, String nextCursor) {}

    /** Who, which two roles, since when - the later of the two grants: nothing else about the person. */
    public record SodConflictView(String identityId, List<String> roles, Instant since) {
        static SodConflictView of(RoleAssignmentStore.ConflictingHolding holding) {
            return new SodConflictView(holding.identityId().value().toString(),
                    List.of(holding.first().name(), holding.second().name()), holding.since());
        }
    }

    /** A page of the factorless review (`P17-TSK-042`): {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FactorlessHoldingPage(List<FactorlessHoldingView> items, String nextCursor) {}

    /** Who, which role, since when: nothing else about the person. */
    public record FactorlessHoldingView(String identityId, String role, Instant assignedAt) {
        static FactorlessHoldingView of(RoleAssignmentStore.FactorlessHolding holding) {
            return new FactorlessHoldingView(holding.identityId().value().toString(), holding.role().name(), holding.assignedAt());
        }
    }
}
