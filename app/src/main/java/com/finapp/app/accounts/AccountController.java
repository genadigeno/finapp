package com.finapp.app.accounts;

import com.finapp.accounts.CustomerAccountId;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.ledger.BalanceDisplay;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    }

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

    /** The balances of the caller's account, per currency, named for what they are. */
    @GetMapping("/{id}/balance")
    public BalanceResponse readBalance(@PathVariable("id") String id, HttpServletRequest request) {
        return accounts
                .balance(current(request), parsedOrAbsent(id))
                .map(AccountController::render)
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
