package com.finapp.app.bnpl;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's BNPL agreement reads (`P12-TSK-013`; {@code PHASE_12_PLAN.md} section 9): one's own agreements, one
 * agreement - its state, product and plan, the version and its hash, the financed amount and the six balances derived from
 * journal lines - and its schedule with each instalment's billed mark. Owner-scoped: another party's agreement is the
 * uniform {@code 404}. Each read one {@code REPEATABLE READ} snapshot. Repayments, payoffs and disputes are later tasks'.
 */
@RestController
@RequestMapping(path = "/me/bnpl-agreements", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class BnplAgreementController {

    @NonNull private final BnplAgreementDesk desk;

    /** The caller's own agreements, newest first. */
    @GetMapping
    public BnplAgreementDesk.BnplAgreementListView listBnplAgreements(HttpServletRequest request) {
        return desk.list(current(request));
    }

    /** The caller's own agreement, its balances derived from journal lines. */
    @GetMapping("/{id}")
    public BnplAgreementDesk.BnplAgreementView readBnplAgreement(@PathVariable("id") String id,
            HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    /** The caller's own agreement's schedule, each instalment marked billed or not. */
    @GetMapping("/{id}/schedule")
    public BnplAgreementDesk.BnplScheduleView readBnplAgreementSchedule(@PathVariable("id") String id,
            HttpServletRequest request) {
        return desk.schedule(current(request), id);
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/bnpl-agreements is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
