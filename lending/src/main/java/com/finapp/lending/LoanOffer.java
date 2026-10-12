package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A loan offer as recorded (`P11-TSK-011`, {@code lending V005}; {@code LENDING_LIFECYCLES.md} section 4.4): one approved
 * credit decision turned into explicit, expiring terms - the machine row ({@code loan_offer}: who, which state, the
 * decision and the pinned terms version by id, the offering instant and the expiry, both the database's) and its
 * immutable {@link Terms} ({@code loan_offer_terms}).
 *
 * @param decision credit's approved decision, by value (ADR-0029)
 * @param termsVersionId the product's terms version {@code ACTIVE} at the offering instant, pinned {@code FOR SHARE}
 * @param termsVersion that version's number
 * @param offeredAt the database's instant of the offering, read after the step's locks
 * @param expiresAt {@code LEAST(decision valid_until, offeredAt + the pinned version's offer validity)}, stamped by the
 *     database
 * @param closedAt the database's instant of the terminal edge, once there
 * @param correlation the application's - one correlation from the submission to the offer and its closure
 */
public record LoanOffer(
        LoanOfferId id,
        LoanApplicationId application,
        UUID party,
        LoanProduct product,
        LoanOfferStatus status,
        UUID decision,
        LoanTermsVersionId termsVersionId,
        int termsVersion,
        Instant offeredAt,
        Instant expiresAt,
        Optional<Instant> closedAt,
        CorrelationId correlation,
        Terms terms) {

    public LoanOffer {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(application, "application must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
        Objects.requireNonNull(offeredAt, "offeredAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Objects.requireNonNull(terms, "terms must not be null");
    }

    /** Never the party or a figure ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LoanOffer[" + id.value() + ", " + product + ", " + status + "]";
    }

    /**
     * What the offer offers - born once with it, never changed (`P11-TSK-011`; A20, A5, {@code INV-LND-05}): exactly the
     * approved amount and term, the pinned version's rate, engines and template, the loan's illustrative schedule or the
     * line's minimum-payment rule, and the canonical terms with their hash and the document's.
     *
     * @param amount the approved principal (loan) or limit (line)
     * @param termMonths the approved term - a loan's alone
     * @param nominalAnnualRate the pinned version's rate, canonical
     * @param repaymentDay the application's repayment (loan) or statement (line) day
     * @param illustration the loan's illustrative schedule figures - present exactly for a loan
     * @param minimumPayment the line's minimum-payment rule - present exactly for a line
     * @param scheduleEngine the schedule engine version - a loan's
     * @param statementEngine the statement engine version - a line's
     * @param canonicalTerms {@link CanonicalOfferTerms} in the format they declare, exactly the bytes hashed
     * @param termsSha256 the SHA-256 of {@code canonicalTerms}
     * @param documentSha256 the SHA-256 of the offer document's bytes ({@link OfferDocument})
     */
    public record Terms(
            Money amount,
            Optional<Integer> termMonths,
            BigDecimal nominalAnnualRate,
            int repaymentDay,
            Optional<Illustration> illustration,
            Optional<MinimumPaymentRule> minimumPayment,
            Optional<EngineVersion> scheduleEngine,
            EngineVersion accrualEngine,
            EngineVersion allocationEngine,
            Optional<EngineVersion> statementEngine,
            AgreementTemplate template,
            String canonicalTerms,
            byte[] termsSha256,
            byte[] documentSha256) {

        public Terms {
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(termMonths, "termMonths must not be null");
            Objects.requireNonNull(nominalAnnualRate, "nominalAnnualRate must not be null");
            Objects.requireNonNull(illustration, "illustration must not be null");
            Objects.requireNonNull(minimumPayment, "minimumPayment must not be null");
            Objects.requireNonNull(scheduleEngine, "scheduleEngine must not be null");
            Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
            Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
            Objects.requireNonNull(statementEngine, "statementEngine must not be null");
            Objects.requireNonNull(template, "template must not be null");
            Objects.requireNonNull(canonicalTerms, "canonicalTerms must not be null");
            termsSha256 = Objects.requireNonNull(termsSha256, "termsSha256 must not be null").clone();
            documentSha256 = Objects.requireNonNull(documentSha256, "documentSha256 must not be null").clone();
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("an offer offers a positive amount");
            }
            if (illustration.isPresent() == minimumPayment.isPresent()) {
                throw new IllegalArgumentException("an offer shows a loan's schedule or a line's rule, exactly one");
            }
            if (termsSha256.length != 32 || documentSha256.length != 32) {
                throw new IllegalArgumentException("the hashes are SHA-256");
            }
        }

        @Override
        public byte[] termsSha256() {
            return termsSha256.clone();
        }

        @Override
        public byte[] documentSha256() {
            return documentSha256.clone();
        }

        /** The terms hash in lowercase hexadecimal - what the acceptance echoes (`P11-TSK-012`). */
        public String termsSha256Hex() {
            return HexFormat.of().formatHex(termsSha256);
        }

        /** The document hash in lowercase hexadecimal. */
        public String documentSha256Hex() {
            return HexFormat.of().formatHex(documentSha256);
        }

        /** Never a figure or the rate ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Terms[" + template + ", redacted]";
        }
    }

    /**
     * A loan offer's illustrative schedule (A5): computed by the pinned schedule engine as if the loan's interest started
     * on {@code start} - the offering day, labelled as such; the real schedule is generated at the accrual start
     * (`P11-TSK-014`). The totals are what the offer discloses: no APR (L8).
     */
    public record Illustration(
            LocalDate start,
            LocalDate firstDueDate,
            Money instalment,
            Money finalInstalment,
            Money totalInterest,
            Money totalPayable) {

        public Illustration {
            Objects.requireNonNull(start, "start must not be null");
            Objects.requireNonNull(firstDueDate, "firstDueDate must not be null");
            Objects.requireNonNull(instalment, "instalment must not be null");
            Objects.requireNonNull(finalInstalment, "finalInstalment must not be null");
            Objects.requireNonNull(totalInterest, "totalInterest must not be null");
            Objects.requireNonNull(totalPayable, "totalPayable must not be null");
        }

        /** The schedule's figures, as the engine produced them. */
        static Illustration of(Schedule schedule) {
            return new Illustration(schedule.start(), schedule.periods().get(0).dueDate(), schedule.instalment(),
                    schedule.finalInstalment(), schedule.totalInterest(), schedule.totalPayable());
        }

        @Override
        public String toString() {
            return "Illustration[from " + start + ", redacted]";
        }
    }

    /** A credit line offer's minimum-payment rule (A23, A24): the floor, the ratio of drawn principal, the days to due. */
    public record MinimumPaymentRule(Money floor, int ratioBps, int paymentDueDays) {

        public MinimumPaymentRule {
            Objects.requireNonNull(floor, "floor must not be null");
        }

        @Override
        public String toString() {
            return "MinimumPaymentRule[redacted]";
        }
    }
}
