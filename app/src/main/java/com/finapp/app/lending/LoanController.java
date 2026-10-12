package com.finapp.app.lending;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's loan account doors (`P11-TSK-012`; PHASE_11_PLAN.md section 9): their loans, and one of them - the
 * state, the kind, the agreement version that governs it (a loan's principal, a line's limit) and the six balances
 * derived from journal lines. Owner-scoped; another party's loan is the uniform {@code 404}. The account is opened by the
 * offer's acceptance; the one command here is a line's draw to the wallet (`P11-TSK-021`) - a {@code MULTI_FACTOR} act,
 * keyed ({@code lending.draw:CUSTOMER:<id>}), {@code 201} with the draw made.
 */
@RestController
@RequestMapping(path = "/me/loans", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class LoanController {

    @NonNull private final LoanAccountDesk desk;
    @NonNull private final CreditLineDrawDesk draws;

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

    /**
     * The caller's own loan's schedule (`P11-TSK-014`): a version - the latest when {@code version} is absent - with its
     * instalments. A loan not yet disbursed has none.
     */
    @GetMapping("/{id}/schedule")
    public LoanAccountDesk.ScheduleView readSchedule(
            @PathVariable("id") String id,
            @RequestParam(name = "version", required = false) Integer version,
            HttpServletRequest request) {
        return desk.schedule(current(request), id, java.util.Optional.ofNullable(version));
    }

    /**
     * Draws on the caller's own credit line to their wallet (`P11-TSK-021`) - only while the line is active, its draws
     * not suspended, within its available limit and the platform's lending capital.
     */
    @PostMapping(path = "/{id}/draws", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public CreditLineDrawDesk.CreditLineDrawView drawOnCreditLine(
            @PathVariable("id") String id,
            @Valid @RequestBody CreditLineDrawBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return draws.draw(current(request), id, idempotencyKey, body);
    }

    /**
     * A draw: the amount and its currency, the amount an exact decimal at the currency's minor units. Closed - an unknown
     * field is {@code 422}; the destination is the borrower's own wallet, never the body's to name (A25).
     */
    @ClosedBody
    public record CreditLineDrawBody(
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 3) String currency) {}

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
