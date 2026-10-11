package com.finapp.app.lending;

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
 * The customer's loan account doors (`P11-TSK-012`; PHASE_11_PLAN.md section 9): their loans, and one of them - the
 * state, the kind, the agreement version that governs it (a loan's principal, a line's limit) and the six balances
 * derived from journal lines. Owner-scoped; another party's loan is the uniform {@code 404}. Reads only - the account is
 * opened by the offer's acceptance.
 */
@RestController
@RequestMapping(path = "/me/loans", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class LoanController {

    @NonNull private final LoanAccountDesk desk;

    /** The caller's loans, newest first. */
    @GetMapping
    public LoanAccountDesk.LoanListView listLoans(HttpServletRequest request) {
        return desk.list(current(request));
    }

    /** The caller's own loan. */
    @GetMapping("/{id}")
    public LoanAccountDesk.LoanView readLoan(@PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/loans is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
