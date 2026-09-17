package com.finapp.app.ledger;

import com.finapp.ledger.AdjustmentCommand;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingResult;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.MonetaryException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The `/v1/ledger/adjustments` slice behind {@link AdjustmentController} (`P3-TSK-017`):
 * parses the boundary's decimal strings into exact {@code Money}, and runs the ledger's
 * adjustment command in one transaction.
 *
 * <p><strong>Every parse refusal is the caller's {@code 422}, naming the line and the field
 * and never the value</strong> ({@code P0-TSK-025}'s detail rule): an amount with more
 * precision than the currency allows is refused rather than rounded ({@code INV-MON-03} —
 * {@link Money#of(BigDecimal, CurrencyCode)} is the exact constructor, and this boundary is
 * precisely where a silent rounding would otherwise creep in), a zero or negative amount is
 * refused by {@link JournalLine}'s own positivity rule, and a malformed account identifier
 * or currency never reaches the domain.
 */
public final class LedgerAdjustments {

    private final AdjustmentService adjustments;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public LedgerAdjustments(
            AdjustmentService adjustments,
            TransactionTemplate transactions,
            DataSource dataSource) {
        this.adjustments = Objects.requireNonNull(adjustments, "adjustments must not be null");
        this.transactions =
                Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** The response: the adjusting entry's identifier — and never an amount. */
    public record AdjustmentView(String entryId) {}

    public AdjustmentView adjust(AdjustmentRequest body, String idempotencyKey) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        // Parse before the transaction opens: a malformed request must not cost a
        // connection (the P1-TSK-026 reasoning, applied to parsing rather than derivation).
        AdjustmentCommand command =
                new AdjustmentCommand(
                        idempotencyKey,
                        body.postingDate(),
                        body.valueDate(),
                        body.reference(),
                        body.reason(),
                        parsedLines(body.lines()));
        PostingResult result =
                inOneTransaction(unitOfWork -> adjustments.adjust(unitOfWork, command));
        return new AdjustmentView(result.entryId().value().toString());
    }

    private static List<JournalLine> parsedLines(List<AdjustmentRequest.Line> lines) {
        List<JournalLine> parsed = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            AdjustmentRequest.Line line = lines.get(i);
            LedgerAccountId account;
            try {
                account = LedgerAccountId.of(UUID.fromString(line.accountId()));
            } catch (IllegalArgumentException malformed) {
                throw refused(i, "accountId", "is not a well-formed account identifier");
            }
            CurrencyCode currency;
            try {
                currency = CurrencyCode.of(line.currency());
            } catch (IllegalArgumentException malformed) {
                throw refused(i, "currency", "is not a supported ISO 4217 code");
            }
            BigDecimal decimal;
            try {
                decimal = new BigDecimal(line.amount());
            } catch (NumberFormatException malformed) {
                throw refused(i, "amount", "is not a decimal number");
            }
            Money amount;
            try {
                // Exact, or refused: an adjustment states its amount at the currency's own
                // scale, and rounding here would be the silent step INV-MON-03 forbids.
                amount = Money.of(decimal, currency);
            } catch (MonetaryException inexact) {
                throw refused(
                        i, "amount", "is not representable at the currency's scale");
            }
            try {
                parsed.add(new JournalLine(account, line.direction(), amount));
            } catch (IllegalArgumentException notPositive) {
                throw refused(i, "amount", "must be strictly positive");
            }
        }
        return parsed;
    }

    /** Names the line and the field, never the value (`P0-TSK-025`'s detail rule). */
    private static ApiException refused(int index, String field, String constraint) {
        String detail = "lines[" + index + "]." + field + " " + constraint + ".";
        return new ApiException(
                PlatformErrorCode.VALIDATION_FAILED,
                "An adjustment line was refused at the boundary: " + detail,
                detail);
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
