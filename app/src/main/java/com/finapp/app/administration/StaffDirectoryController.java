package com.finapp.app.administration;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff directory (`P17-TSK-014`, gaps G-10 and G-11; finapp-admin-panel ADR-C132): whom an administrator acts on. Under
 * {@code IDENTITY_READ}; the list, the detail and the lookup on a {@code MULTI_FACTOR} session, as the factorless review is - a
 * list of who holds the privileged roles is the map a stolen password must not buy - and each recorded. The catalogue is the
 * code's own and names nobody.
 */
@RestController
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class StaffDirectoryController {

    @NonNull private final StaffDirectoryService directory;

    /**
     * The identities that hold, or held, an operator role: a §7 collection - {@code limit} 50 unless named, 200 at most, the
     * opaque {@code cursor} of the page before - newest first by creation, the id breaking the tie; {@code role} a live holding
     * of that role, {@code status} the identity's. Recorded once per request with its filter.
     */
    @GetMapping("/identities")
    @RequiresPermission(PermissionName.IDENTITY_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public StaffDirectoryService.IdentityDirectoryPage listStaffIdentities(
            @RequestParam(name = "role", required = false) String role,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return directory.page(role, status, cursor, limit);
    }

    /** One identity, identifier-first: status, suspension, factor state, live roles. Recorded, the identity the target. */
    @GetMapping("/identities/{id}")
    @RequiresPermission(PermissionName.IDENTITY_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public StaffDirectoryService.IdentityDetailView readStaffIdentity(@PathVariable("id") String identityId) {
        return directory.detail(identityId);
    }

    /**
     * The identity a login identifier names, exactly - the identifier in the body, never the URL (ADR-C132 decision 4), since it
     * may be a person's own handle. Answers the id or {@code 404}; recorded either way, the identifier never. No idempotency key:
     * it changes nothing.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/identities/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.IDENTITY_READ)
    public StaffDirectoryService.IdentityLookupAnswer lookUpStaffIdentity(@Valid @RequestBody IdentityLookupRequest body) {
        return directory.lookup(body.loginIdentifier());
    }

    /** The role catalogue: every role, what it grants, whether it needs an enrolled factor - from {@code RoleName} itself. */
    @GetMapping("/roles")
    @RequiresPermission(PermissionName.IDENTITY_READ)
    public List<StaffDirectoryService.RoleCatalogueEntry> listOperatorRoles() {
        return directory.roles();
    }

    /** The lookup's one field. */
    public record IdentityLookupRequest(@NotNull String loginIdentifier) {}
}
