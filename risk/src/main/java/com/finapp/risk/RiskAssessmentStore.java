package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the sealed assessments (`P13-TSK-004`; {@code risk V007}; ADR-0033) - a port whose one write is the
 * born-once insert, every other method a read, all on the caller's unit of work. Nothing here updates or deletes: the
 * tables refuse both for every writer.
 */
public interface RiskAssessmentStore {

    /** The assessment sealed for {@code commandRef} of {@code operation}, if one is - no lock. */
    Optional<RiskAssessment> find(Connection unitOfWork, RiskOperation operation, CommandRef commandRef);

    /** The assessment {@code id}, if one exists - no lock. */
    Optional<RiskAssessment> read(Connection unitOfWork, RiskAssessmentId id);

    /**
     * Inserts {@code assessment} whole - the head {@code ON CONFLICT (operation, command_ref) DO NOTHING}, then its
     * inputs, hits and reasons. False when another transaction sealed the same command first: nothing was written.
     */
    boolean insert(Connection unitOfWork, RiskAssessment assessment);

    /**
     * The movement of every assessment {@link VelocityOutcome#COUNTED} at or after {@code from}, at its cut instant -
     * the velocity proof's evidence ({@link AssessmentVelocityEvidence}); the head's columns only, no lock.
     */
    List<VelocityEvidence.Counted> countedSince(Connection unitOfWork, Instant from);

    /**
     * The movement of every assessment that permitted its movement <em>without counting it</em>
     * ({@link VelocityOutcome#UNCOUNTED}) minted at or after {@code from}, at its cut or else its seal (`P13-TSK-023`) -
     * the velocity proof's explained set: such a movement posts and is rightly absent from its windows. Entered by the
     * primary key over the id's minting millisecond; no lock. Every store of record answers it; a suite's fake that
     * never seals an uncounted movement may leave the default.
     */
    default List<VelocityEvidence.Counted> uncountedSince(Connection unitOfWork, Instant from) {
        return List.of();
    }

    /**
     * The payer and the merchant of the checkout payment judged under {@code commandRef} - the intent's id (`-010`) -
     * as its sealed {@code CARD_PAYMENT} or {@code WALLET_PAYMENT} assessment names them; empty when risk never judged
     * that payment (`P13-TSK-014`: a refund's and a chargeback's attribution, read from risk's own record, never from
     * payments'). No lock: a sealed row never changes. An intent judged under both operations (a re-confirmation with
     * another instrument) names one payer and one merchant either way - the latest is answered.
     */
    Optional<JudgedPayment> judgedPayment(Connection unitOfWork, CommandRef commandRef);

    /** Whose checkout payment it was, and whom it paid - identifiers only. */
    record JudgedPayment(UUID party, UUID merchant) {
        public JudgedPayment {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(merchant, "merchant");
        }

        @Override
        public String toString() {
            // Never the references: who paid whom is CONFIDENTIAL.
            return "JudgedPayment";
        }
    }
}
