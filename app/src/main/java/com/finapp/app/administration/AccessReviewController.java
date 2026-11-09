package com.finapp.app.administration;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleAssignmentStore;
import java.time.Instant;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The check of the holdings the factor rule found standing (`P15-TSK-039`; {@code PRIVILEGED_ACCESS_REVIEW.md} §5 and §6).
 *
 * <p>Refusing a grant says nothing about a role held since before the rule. This names them: each live holding of a role
 * that can redirect a merchant's money whose holder has no enrolled factor. It revokes nothing, and no migration does
 * either: a role a person holds is theirs until someone decides otherwise, with a reason, and the review is where that
 * is decided. The administrator's read, because {@code ROLE_ASSIGN} is the permission that acts on the answer; recorded.
 */
@RestController
@RequestMapping(path = "/operator/access-review", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccessReviewController {

    /** A review reads this, so it need never be paged; a page full of holdings says so and the rest wait for the next. */
    private static final int MOST_SERVED = 200;

    @NonNull private final IdentityAdministrationService administration;

    /** The live holdings of a money-redirecting role with no enrolled factor, oldest first. */
    @GetMapping("/factorless-holdings")
    @RequiresPermission(PermissionName.ROLE_ASSIGN)
    public FactorlessHoldingsView listFactorlessHoldings() {
        List<RoleAssignmentStore.FactorlessHolding> served = administration.factorlessHoldings(MOST_SERVED + 1);
        return new FactorlessHoldingsView(
                served.stream().limit(MOST_SERVED).map(FactorlessHoldingView::of).toList(), served.size() > MOST_SERVED);
    }

    /** The holdings served and whether there were more. */
    public record FactorlessHoldingsView(List<FactorlessHoldingView> holdings, boolean truncated) {}

    /** Who, which role, since when: nothing else about the person. */
    public record FactorlessHoldingView(String identityId, String role, Instant assignedAt) {
        static FactorlessHoldingView of(RoleAssignmentStore.FactorlessHolding holding) {
            return new FactorlessHoldingView(holding.identityId().value().toString(), holding.role().name(), holding.assignedAt());
        }
    }
}
