package com.finapp.app.customers;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
 * The operator's customer reads (`P17-TSK-026`, gaps G-30, G-31, G-70; finapp-admin-panel ADR-C132): find a customer by an exact
 * identifier, read its profile - identifiers and statuses, never a name, contact detail or document (D-5) - and its consent
 * history. Under {@code CUSTOMER_READ}, each on a {@code MULTI_FACTOR} session - a map of who is a customer is what a stolen
 * password must not buy, the staff directory's reason - and every one recorded ({@code RecordedReads}, held at the answer).
 */
@RestController
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CustomerReadController {

    @NonNull private final CustomerReadDesk desk;

    /**
     * The customer an identifier names, exactly: {@code kind} one of {@code CUSTOMER_ID}, {@code PARTY_ID},
     * {@code LOGIN_IDENTIFIER} or {@code ACCOUNT_ID}, the value in the body and never the URL (ADR-C132 decision 4), since a
     * login identifier is a person's own handle. Answers the customer's id or {@code 404}; recorded either way, the value
     * never. No idempotency key: it changes nothing.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/customers/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CUSTOMER_READ)
    public CustomerReadDesk.CustomerLookupAnswer lookUpCustomer(@Valid @RequestBody CustomerLookupRequest body) {
        return desk.lookup(body.kind(), body.value());
    }

    /** One customer's profile: identifiers and statuses only. Recorded, the customer the target; an unknown id is {@code 404}. */
    @GetMapping("/customers/{id}")
    @RequiresPermission(PermissionName.CUSTOMER_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public CustomerReadDesk.CustomerProfile readCustomer(@PathVariable("id") String customerId) {
        return desk.profile(customerId);
    }

    /**
     * A party's consent history, append-only (ADR-0037): a §7 collection - {@code limit} 50 unless named, 200 at most, the opaque
     * {@code cursor} of the page before - newest fact first, by the history's own server-assigned order. Recorded once per page.
     */
    @GetMapping("/parties/{id}/consents")
    @RequiresPermission(PermissionName.CUSTOMER_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public CustomerReadDesk.ConsentHistoryPage listPartyConsents(
            @PathVariable("id") String partyId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.consents(partyId, cursor, limit);
    }

    /** The lookup's two fields. */
    public record CustomerLookupRequest(@NotNull String kind, @NotNull String value) {}
}
