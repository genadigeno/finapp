package com.finapp.app.kyc;

import com.finapp.app.api.RecordedRead;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reviewers' queues (`P17-TSK-029`, gaps G-60, G-64, G-65; finapp-admin-panel B6.4, ADR-C132): the KYC review queue, a
 * customer's latest case, and the counterparty screenings awaiting a person - each on a {@code MULTI_FACTOR} session, since a
 * list of customers under review is a map, and each answer recorded ({@link RecordedRead}: the permissions also guard acts, so
 * the guard at the answer holds these by their mark). The handlers' names are distinctive (the springdoc {@code operationId}
 * rule).
 */
@RestController
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class KycQueueController {

    @NonNull private final KycQueueDesk desk;

    /**
     * The open KYC cases, oldest opening first, the case id breaking a tie - a §7 collection, {@code limit} 50 unless named, 200 at
     * most - of one open {@code status} when named: {@code OPEN}, {@code CHECKS_IN_PROGRESS}, {@code IN_REVIEW},
     * {@code AWAITING_CUSTOMER} (`P17-TSK-126`) or {@code READY_FOR_DECISION}. Every page recorded {@code kyc.ReviewQueueRead}.
     */
    @GetMapping("/kyc/cases")
    @RequiresPermission(PermissionName.KYC_REVIEW)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RecordedRead
    public KycQueueDesk.ReviewQueuePage listKycReviewQueue(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.queue(status, cursor, limit);
    }

    /** A customer's latest KYC case, open or decided, as its head; recorded {@code kyc.CaseRead}. {@code 404} for none. */
    @GetMapping("/customers/{id}/kyc-case")
    @RequiresPermission(PermissionName.KYC_REVIEW)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RecordedRead
    public KycQueueDesk.KycCaseHead readCustomerLatestKycCase(@PathVariable("id") String customerId) {
        return desk.latestOf(customerId);
    }

    /**
     * The counterparty screenings awaiting a person ({@code status=IN_REVIEW}, the default and the only value), oldest raised
     * first, the screening id breaking a tie - a §7 collection, 50 unless named, 200 at most - each its identifier, status and when
     * it was raised, nothing about its subject. Every page recorded {@code kyc.ScreeningBacklogRead}; a refusal recorded too, as
     * every door under the Phase 9 permission records it ({@code RefusedActsAreRecordedTest}, the review door's own shape).
     */
    @GetMapping("/kyc/counterparty-screenings")
    @RequiresPermission(PermissionName.COUNTERPARTY_SCREENING_REVIEW)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RecordedRead
    @RecordsRefusals
    public KycQueueDesk.ScreeningBacklogPage listCounterpartyScreeningBacklog(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.backlog(status, cursor, limit);
    }
}
