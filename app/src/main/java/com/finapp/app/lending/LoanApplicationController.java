package com.finapp.app.lending;

import com.finapp.lending.LendingSpans;
import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's loan application doors (`P11-TSK-010`; PHASE_11_PLAN.md section 9, LENDING_LIFECYCLES.md section
 * 4.3): submit - keyed per principal ({@code lending.application:CUSTOMER:<id>}), a {@code MULTI_FACTOR} act, {@code 202}
 * with the application {@code AWAITING_DECISION} and credit's decision request opened beside it; read one's own
 * application; and withdraw it while credit's request is still cancellable - keyed
 * ({@code lending.application-withdrawal:CUSTOMER:<id>}), the platform's conditional step-up. The body is closed: no
 * rate, offer, score or decision field exists to be sent.
 */
@RestController
@RequestMapping(path = "/me/loan-applications", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class LoanApplicationController {

    @NonNull private final LoanApplicationDesk desk;
    @NonNull private final LendingFlowScope flow;

    /** Applies for a personal loan or a credit line - one act opening credit's decision request in the same commit. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LoanApplicationDesk.LoanApplicationView submitLoanApplication(
            @Valid @RequestBody LoanApplicationBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.door(LendingSpans.APPLY, () -> desk.submit(current(request), idempotencyKey, body));
    }

    /** The caller's own application: its status, and - once declined - credit's reasons in the customer's words. */
    @GetMapping("/{id}")
    public LoanApplicationDesk.LoanApplicationView readLoanApplication(
            @PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    /** Withdraws the caller's application, cancelling credit's request - only before credit has evaluated it. */
    @PostMapping("/{id}/withdrawal")
    @RequiresIdempotencyKey
    public LoanApplicationDesk.LoanApplicationView withdrawLoanApplication(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.doorOnApplication(LendingSpans.WITHDRAW, id, () -> desk.withdraw(current(request), id, idempotencyKey));
    }

    /**
     * A loan application: the product, the amount (a loan) or limit (a line) and its currency as an exact decimal, the
     * term in months (a loan's alone), the repayment (loan) or statement (line) day, the repayment method, the payout
     * preference (optional; a loan's alone may be {@code EXTERNAL_ACCOUNT}) and the declared monthly figures (optional,
     * passed to credit). Closed - an unknown field is {@code 422}.
     */
    @ClosedBody
    public record LoanApplicationBody(
            @NotBlank @Size(max = 32) String product,
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 3) String currency,
            @Min(1) @Max(600) Integer termMonths,
            @NotNull @Min(1) @Max(31) Integer repaymentDay,
            @NotBlank @Size(max = 32) String repaymentMethod,
            @Size(max = 32) String payoutPreference,
            @Size(max = 32) String declaredMonthlyIncome,
            @Size(max = 32) String declaredMonthlyExpenditure) {}

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/loan-applications is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
