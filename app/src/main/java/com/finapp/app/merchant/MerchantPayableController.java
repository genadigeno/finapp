package com.finapp.app.merchant;

import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.MerchantPayable;
import com.finapp.sharedkernel.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/merchant/payable} (`P6-TSK-010`, {@code INV-MER-02} as a surface): what the platform
 * owes the calling merchant, per currency, and the terms that explain it.
 *
 * <h2>{@code DERIVED}, not {@code PROJECTION} — and the difference is the design</h2>
 *
 * <p>The customer balance endpoint reads the ledger's display projection and labels it
 * {@code PROJECTION} (ADR-0041): current with every posting and never the input to a financial
 * decision. This view cannot do that, because it reports a <em>drill-down beside its figure</em>,
 * and the drill-down must sum to the figure. A projection is maintained by a different mechanism
 * than the lines it would sit beside, so the two could disagree — the {@code StatementDerivation}
 * reasoning. Here the figure and every term come from one read of the payable's lines, and the
 * response says so.
 *
 * <h2>The tenant is the key</h2>
 *
 * <p>No identifier in the route, as with {@code /v1/merchant/transactions}: a question about
 * another merchant's payable has no shape a request could take.
 *
 * <p>The handler is {@code viewMerchantPayable}, not {@code view}: springdoc derives each
 * {@code operationId} from the method name, and a generic one would renumber somebody else's
 * published endpoint ({@code OpenApiContractTest}'s build rule).
 */
@RestController
@RequestMapping(path = "/merchant", produces = MediaType.APPLICATION_JSON_VALUE)
public class MerchantPayableController {

    private final MerchantPayableQuery payables;

    public MerchantPayableController(MerchantPayableQuery payables) {
        this.payables = Objects.requireNonNull(payables, "payables must not be null");
    }

    /**
     * One currency's payable. Every figure is a decimal string; {@code position} equals
     * {@code captured − fees − refunded + feesReturned + other} exactly, and {@code other} is
     * signed (an adjustment today; a payout until `P6-TSK-012` gives it its own term).
     */
    public record PayableLine(
            String currency,
            String position,
            String captured,
            String fees,
            String refunded,
            String feesReturned,
            String other) {}

    /** {@code kind} is always {@code "DERIVED"} — see the class javadoc. */
    public record PayableView(String kind, List<PayableLine> payables) {}

    @GetMapping("/payable")
    @RequiresMerchantKey
    public PayableView viewMerchantPayable(HttpServletRequest request) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        List<MerchantPayable.Payable> owed = payables.payablesOf(merchant.merchantId());
        return new PayableView(
                "DERIVED", owed.stream().map(MerchantPayableController::render).toList());
    }

    private static PayableLine render(MerchantPayable.Payable payable) {
        return new PayableLine(
                payable.position().currency().code(),
                decimal(payable.position()),
                decimal(payable.captured()),
                decimal(payable.fees()),
                decimal(payable.refunded()),
                decimal(payable.feesReturned()),
                decimal(payable.other()));
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }
}
