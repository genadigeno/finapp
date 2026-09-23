package com.finapp.app.accounts;

import com.finapp.accounts.CustomerAccountId;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.ledger.BalanceDisplay;
import com.finapp.ledger.StatementDerivation;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The customer account product over HTTP (`P3-TSK-013`): open, list, and read a balance.
 *
 * <h2>Ownership</h2>
 *
 * <p>The open and the list take <strong>no identifier at all</strong> — the `/v1/me` shape,
 * ADR-0031's rule in its strongest form. The balance read takes the one path identifier of the
 * surface, and its ownership lives in the store's {@code WHERE} clause
 * ({@code CustomerAccountStore.findOwnedBy}): unknown, not-yours and malformed are one
 * {@code 404}, because a distinct answer would make this endpoint an oracle over other
 * people's accounts (the {@code P1-TSK-016} session reasoning).
 *
 * <h2>Amounts are decimal strings</h2>
 *
 * <p>The first monetary values ever published by this platform leave as
 * {@code {"amount": "12.50", "currency": "USD"}} — the amount a <strong>string</strong>,
 * because a JSON number is a {@code double} in every careless client, and {@code INV-MON-01}'s
 * reasoning does not stop at our own boundary. The response also says <strong>which numbers
 * these are</strong> — settled, holds, available — and that they are a projection
 * ({@code kind}), because a response that just said "balance" would mean whichever number its
 * reader assumed (ADR-0041's consequence, {@code INV-BAL-04}'s presentation).
 */
@RestController
@RequestMapping(path = "/me/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class AccountController {

    @NonNull private final AccountService accounts;

    /**
     * Opens the caller's account of the requested product type, or replays the opening.
     *
     * <p>{@code 201} for the converged repeat as well as the creation — one intent, answered
     * with the creation's own status (the platform's convergence idiom, `P2-TSK-016`); what
     * distinguishes creation is the records, never the answer.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public AccountService.AccountView openAccount(
            @Valid @RequestBody AccountOpenRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return accounts.open(
                current(request),
                body.productType(),
                CurrencyCode.of(body.currency()),
                idempotencyKey);
    }

    /** The caller's products — every status, oldest first. */
    @GetMapping
    public List<AccountService.AccountView> listAccounts(HttpServletRequest request) {
        return accounts.list(current(request));
    }

    /**
     * Ends the caller's agreement — the milestone acceptance's last clause (`P3-TSK-014`).
     *
     * <p>{@code 204} for the converged repeat as well as the close: a retried {@code DELETE}
     * whose first response was lost must not read as a failure, and what distinguishes the
     * closing call is the records (one audit record, one event), never the answer. No
     * idempotency key: closing moves no money — the zero-balance precondition is what makes
     * that true — and convergence is the retry mechanism (the consent-withdrawal shape).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(@PathVariable("id") String id, HttpServletRequest request) {
        accounts.close(current(request), parsedOrAbsent(id))
                .orElseThrow(AccountController::accountNotFound);
    }

    /** The balances of the caller's account, per currency, named for what they are. */
    @GetMapping("/{id}/balance")
    public BalanceResponse readBalance(@PathVariable("id") String id, HttpServletRequest request) {
        return accounts
                .balance(current(request), parsedOrAbsent(id))
                .map(AccountController::render)
                .orElseThrow(AccountController::accountNotFound);
    }

    /**
     * The statement of the caller's account for {@code [from, to]}, derived from postings
     * (`P3-TSK-018`, {@code INV-ACC-02}'s drill-down shape): the opening balance, every line,
     * and a closing that reconciles to them <strong>by construction</strong> — see
     * {@link StatementDerivation}.
     *
     * <p>The period parameters are the caller's own correctable values, so their refusals are
     * specific 422s naming the parameter — unlike the account identifier, whose unknown,
     * not-yours and malformed shapes stay one 404, because a date discloses nothing about
     * anybody else's resources while an identifier answer would.
     */
    @GetMapping("/{id}/statement")
    public StatementResponse readStatement(
            @PathVariable("id") String id,
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            HttpServletRequest request) {
        LocalDate fromDate = parsedDate(from, "from");
        LocalDate toDate = parsedDate(to, "to");
        if (fromDate.isAfter(toDate)) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A statement was requested for a period that starts after it ends",
                    "'from' must not be after 'to'.");
        }
        return accounts
                .statement(current(request), parsedOrAbsent(id), fromDate, toDate)
                .map(owned -> render(owned, fromDate, toDate))
                .orElseThrow(AccountController::accountNotFound);
    }

    // -----------------------------------------------------------------

    /** One balance line: the three numbers, each named, each a decimal string. */
    public record BalanceLine(String currency, String settled, String holds, String available) {}

    /**
     * The balance response. {@code kind} is always {@code "PROJECTION"}: these numbers are the
     * transactional display projection (ADR-0041) — current with every posting, and never the
     * input to a financial decision ({@code INV-BAL-05}).
     */
    public record BalanceResponse(String accountId, String kind, List<BalanceLine> balances) {}

    private static BalanceResponse render(AccountService.Balances owned) {
        return new BalanceResponse(
                owned.account().id().value().toString(),
                "PROJECTION",
                owned.perCurrency().stream().map(AccountController::render).toList());
    }

    private static BalanceLine render(BalanceDisplay.DisplayedBalance balance) {
        return new BalanceLine(
                balance.settled().currency().code(),
                decimal(balance.settled()),
                decimal(balance.holds()),
                decimal(balance.available()));
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    /**
     * One statement line: the account's own side of one journal entry. {@code entryId} is
     * the drill-down key ({@code INV-ACC-02}); {@code reference} is the caller's own economic
     * event. Deliberately absent: any counterparty account, and any {@code reason} — free
     * text written by a person is audit material, never statement material
     * ({@code RESTRICTED-PII}).
     */
    public record StatementLineView(
            String entryId,
            String postingDate,
            String valueDate,
            String entryType,
            String direction,
            String amount,
            String reference) {}

    /** One currency's statement: opening, the lines, and the closing they reconcile to. */
    public record StatementSection(
            String currency, String opening, String closing, List<StatementLineView> lines) {}

    /**
     * The statement response. {@code kind} is always {@code "DERIVED"}: these are settled
     * numbers derived from postings — the authoritative record, never the display projection
     * — which is what makes the statement evidence-shaped.
     */
    public record StatementResponse(
            String accountId, String kind, String from, String to,
            List<StatementSection> sections) {}

    private static StatementResponse render(
            AccountService.Statement owned, LocalDate from, LocalDate to) {
        return new StatementResponse(
                owned.account().id().value().toString(),
                "DERIVED",
                from.toString(),
                to.toString(),
                owned.perCurrency().stream().map(AccountController::render).toList());
    }

    private static StatementSection render(StatementDerivation.AccountStatement statement) {
        return new StatementSection(
                statement.opening().currency().code(),
                decimal(statement.opening()),
                decimal(statement.closing()),
                statement.lines().stream().map(AccountController::render).toList());
    }

    private static StatementLineView render(StatementDerivation.StatementLine line) {
        return new StatementLineView(
                line.entry().value().toString(),
                line.postingDate().toString(),
                line.valueDate().toString(),
                line.entryType().name(),
                line.direction().name(),
                decimal(line.amount()),
                line.reference());
    }

    /**
     * A period parameter is the caller's own correctable value: refused as a 422 naming the
     * parameter, never echoing the value ({@code P0-TSK-025}'s detail rule).
     */
    private static LocalDate parsedDate(String raw, String parameter) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A statement period parameter was not an ISO-8601 date",
                    "'" + parameter + "' must be an ISO-8601 date (YYYY-MM-DD).");
        }
    }

    /**
     * A malformed identifier is treated as an identifier that names nothing — one {@code 404}
     * with unknown and not-yours ({@code P1-TSK-016}; malformed-equals-absent).
     */
    private static CustomerAccountId parsedOrAbsent(String raw) {
        try {
            return CustomerAccountId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw accountNotFound();
        }
    }

    private static ApiException accountNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No account of the caller's matches the requested identifier",
                "no such account");
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/accounts is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
