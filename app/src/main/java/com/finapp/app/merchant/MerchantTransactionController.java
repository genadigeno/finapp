package com.finapp.app.merchant;

import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/merchant/transactions} (`P6-TSK-009`): the merchant sees its business —
 * {@link MerchantTransactionReport}'s movements for a bounded period, per currency.
 *
 * <h2>The tenant is the key, and there is no identifier to name anybody else's</h2>
 *
 * <p>The route takes no merchant and no resource identifier at all. The tenant comes from the
 * credential, and the report is scoped to it at the ledger, so "merchant A asks about B's
 * transactions" has no shape a request could take — A's report simply contains none of B's rows.
 * That is the strongest form the one-404 discipline can take: not a refusal that has to be
 * indistinguishable, but a question that cannot be asked.
 *
 * <h2>The period's refusals are specific</h2>
 *
 * <p>The account statement's recorded reasoning: a date is the caller's own correctable value and
 * discloses nothing about anybody else, so a malformed or oversized period is a {@code 422}
 * naming the parameter rather than the uniform {@code 404} an identifier would get.
 *
 * <p>The handler is {@code listMerchantTransactions}, not {@code list}: springdoc derives each
 * {@code operationId} from the method name and renumbers collisions, so a generic name here would
 * break somebody else's published endpoint ({@code OpenApiContractTest}'s build rule).
 */
@RestController
@RequestMapping(path = "/merchant", produces = MediaType.APPLICATION_JSON_VALUE)
public class MerchantTransactionController {

    private final MerchantTransactionReport report;

    public MerchantTransactionController(MerchantTransactionReport report) {
        this.report = Objects.requireNonNull(report, "report must not be null");
    }

    /**
     * One movement. {@code gross} and {@code fee} are magnitudes; {@code net} carries the sign —
     * what this entry did to what the platform owes you. Within a currency, the {@code net}s sum
     * to {@code closing − opening}: the report reconciles to the ledger by construction.
     */
    public record MovementView(
            String kind,
            String entryId,
            String postingDate,
            String gross,
            String fee,
            String net,
            String checkoutId,
            String orderId,
            String paymentIntentId,
            String refundId) {}

    /** One currency: opening, the movements, and the closing they reconcile to. */
    public record SectionView(
            String currency, String opening, String closing, List<MovementView> movements) {}

    /** The report for {@code [from, to]}. */
    public record TransactionsView(String from, String to, List<SectionView> sections) {}

    @GetMapping("/transactions")
    @RequiresMerchantKey
    public TransactionsView listMerchantTransactions(
            HttpServletRequest request,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        LocalDate fromDate = parsedDate(from, "from");
        LocalDate toDate = parsedDate(to, "to");
        List<MerchantTransactionReport.Section> sections;
        try {
            sections = report.report(merchant, fromDate, toDate);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A merchant transaction report was requested for an invalid period",
                    refused.getMessage());
        }
        return new TransactionsView(
                fromDate.toString(),
                toDate.toString(),
                sections.stream().map(MerchantTransactionController::render).toList());
    }

    private static SectionView render(MerchantTransactionReport.Section section) {
        return new SectionView(
                section.opening().currency().code(),
                decimal(section.opening()),
                decimal(section.closing()),
                section.movements().stream().map(MerchantTransactionController::render).toList());
    }

    private static MovementView render(MerchantTransactionReport.Movement movement) {
        return new MovementView(
                movement.kind().name(),
                movement.entry().value().toString(),
                movement.postingDate().toString(),
                movement.gross().map(MerchantTransactionController::decimal).orElse(null),
                movement.fee().map(MerchantTransactionController::decimal).orElse(null),
                decimal(movement.net()),
                text(movement.checkoutId()),
                text(movement.orderId()),
                text(movement.paymentIntentId()),
                text(movement.refundId()));
    }

    private static String text(Optional<UUID> id) {
        return id.map(UUID::toString).orElse(null);
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    private static LocalDate parsedDate(String raw, String name) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A merchant transaction report was requested with a malformed date",
                    "'" + name + "' must be an ISO date (YYYY-MM-DD).");
        }
    }
}
