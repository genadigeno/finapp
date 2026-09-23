package com.finapp.app.ledger;

import com.finapp.ledger.AdjustmentCommand;
import com.finapp.ledger.AdjustmentProposal;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentProposalStatus;
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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The `/v1/ledger/adjustments` slice behind {@link AdjustmentController} (`P3-TSK-017` the
 * write, `P3-TSK-021` the four-eyes lifecycle): parses the boundary's decimal strings into
 * exact {@code Money}, and runs each ledger command in one transaction.
 *
 * <p><strong>Every parse refusal is the caller's {@code 422}, naming the line and the field
 * and never the value</strong> ({@code P0-TSK-025}'s detail rule): an amount with more
 * precision than the currency allows is refused rather than rounded ({@code INV-MON-03} —
 * {@link Money#of(BigDecimal, CurrencyCode)} is the exact constructor, and this boundary is
 * precisely where a silent rounding would otherwise creep in), a zero or negative amount is
 * refused by {@link JournalLine}'s own positivity rule, and a malformed account identifier
 * or currency never reaches the domain.
 *
 * <p><strong>A malformed proposal identifier is one that names nothing</strong>: unknown and
 * malformed are one {@code 404} (`P1-TSK-016`'s malformed-equals-absent), with the same
 * client detail the service's own not-found produces, asserted as an equality between the
 * causes.
 */
@RequiredArgsConstructor
public final class LedgerAdjustments {

    static final String NOT_FOUND_DETAIL = "no such adjustment proposal";

    @NonNull private final AdjustmentService adjustments;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** The propose response: the proposal awaiting a second person — never an amount. */
    public record ProposalView(String proposalId, String status) {}

    /** The approval response: the adjusting entry's identifier — and never an amount. */
    public record AdjustmentView(String entryId) {}

    /**
     * The proposal in full — what an approver reads before approving. An authorised
     * operator sees the amounts and the justification (the `P2-TSK-016` reviewer-sees-
     * everything argument: the person asked to answer for a decision cannot answer for
     * evidence they cannot see). Absent decision fields are {@code null}, never invented.
     */
    public record ProposalDetailView(
            String proposalId,
            String status,
            String postingDate,
            String valueDate,
            String reference,
            String reason,
            String proposedBy,
            String proposedAt,
            List<LineView> lines,
            String decidedBy,
            String decidedAt,
            String entryId) {}

    /** One proposed line, the amount as a decimal string (`P3-TSK-013`'s reasoning). */
    public record LineView(String accountId, String direction, String amount, String currency) {}

    /**
     * Records the proposal (`P3-TSK-021`): <strong>nothing posts here</strong> — the entry
     * is the approval's, by a second person ({@code INV-AUD-04}).
     */
    public ProposalView propose(AdjustmentRequest body, String idempotencyKey) {
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
        AdjustmentService.ProposalResult result =
                inOneTransaction(unitOfWork -> adjustments.propose(unitOfWork, command));
        return new ProposalView(
                result.proposalId().value().toString(),
                AdjustmentProposalStatus.PROPOSED.name());
    }

    /** The proposal in full, or the one {@code 404} for unknown-and-malformed alike. */
    public ProposalDetailView view(String rawId) {
        AdjustmentProposalId id = parsedOrAbsent(rawId);
        AdjustmentProposal proposal =
                inOneTransaction(unitOfWork -> adjustments.find(unitOfWork, id))
                        .orElseThrow(LedgerAdjustments::proposalNotFound);
        return render(proposal);
    }

    /** Approves as the acting person and posts the entry — the journal-write command. */
    public AdjustmentView approve(String rawId) {
        AdjustmentProposalId id = parsedOrAbsent(rawId);
        PostingResult result =
                inOneTransaction(unitOfWork -> adjustments.approve(unitOfWork, id));
        return new AdjustmentView(result.entryId().value().toString());
    }

    /** Rejects or withdraws; converges on a proposal already rejected. */
    public void reject(String rawId) {
        AdjustmentProposalId id = parsedOrAbsent(rawId);
        inOneTransaction(unitOfWork -> adjustments.reject(unitOfWork, id));
    }

    private static ProposalDetailView render(AdjustmentProposal proposal) {
        return new ProposalDetailView(
                proposal.id().value().toString(),
                proposal.status().name(),
                proposal.postingDate().toString(),
                proposal.valueDate().toString(),
                proposal.reference(),
                proposal.reason(),
                proposal.proposedBy(),
                proposal.proposedAt().toString(),
                proposal.lines().stream().map(LedgerAdjustments::render).toList(),
                proposal.decidedBy().orElse(null),
                proposal.decidedAt().map(Object::toString).orElse(null),
                proposal.entry().map(entry -> entry.value().toString()).orElse(null));
    }

    private static LineView render(JournalLine line) {
        return new LineView(
                line.account().value().toString(),
                line.direction().name(),
                line.amount().toBigDecimal().toPlainString(),
                line.amount().currency().code());
    }

    /**
     * A malformed identifier is treated as an identifier that names nothing — one
     * {@code 404} with unknown (`P1-TSK-016`; malformed-equals-absent).
     */
    private static AdjustmentProposalId parsedOrAbsent(String raw) {
        try {
            return AdjustmentProposalId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw proposalNotFound();
        }
    }

    /** The same client detail {@code ApiErrorHandler} writes for the service's not-found. */
    private static ApiException proposalNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No adjustment proposal matches the requested identifier",
                NOT_FOUND_DETAIL);
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
