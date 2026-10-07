package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.consent.ConsentGate;
import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.ConsentStore;
import com.finapp.consent.ConsentText;
import com.finapp.credit.CreditSourceKind;
import java.sql.Connection;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The source-kind to consent-purpose mapping the credit adapter owns ({@code P10-TSK-002},
 * {@code INV-CRD-03}): every kind mapped, each to its own purpose, and a basis for one kind never
 * admitting the other.
 */
@DisplayName("each credit source kind has its own consent purpose (P10-TSK-002)")
class CreditConsentPurposeMappingTest {

    @Test
    @DisplayName("BUREAU is CREDIT_BUREAU_ACCESS and FINANCIAL_DATA is FINANCIAL_DATA_ACCESS")
    void eachKindHasItsPurpose() {
        assertThat(ConsentBackedCreditConsentGate.purposeOf(CreditSourceKind.BUREAU))
                .isEqualTo(ConsentPurpose.CREDIT_BUREAU_ACCESS);
        assertThat(ConsentBackedCreditConsentGate.purposeOf(CreditSourceKind.FINANCIAL_DATA))
                .isEqualTo(ConsentPurpose.FINANCIAL_DATA_ACCESS);
    }

    @Test
    @DisplayName("every source kind is mapped, and no two kinds share a purpose - no combined credit basis")
    void theMappingIsExhaustiveAndInjective() {
        Set<ConsentPurpose> purposes = new HashSet<>();
        for (CreditSourceKind kind : CreditSourceKind.values()) {
            assertThat(purposes.add(ConsentBackedCreditConsentGate.purposeOf(kind)))
                    .as("%s must have a purpose of its own", kind)
                    .isTrue();
        }
        assertThat(purposes).hasSize(CreditSourceKind.values().length)
                .doesNotContain(ConsentPurpose.KYC_PROCESSING, ConsentPurpose.SCREENING);
    }

    @Test
    @DisplayName("a bureau grant alone answers absent for financial data, and the reverse")
    void aBasisForOneKindNeverAdmitsTheOther() {
        for (CreditSourceKind granted : CreditSourceKind.values()) {
            ConsentBackedCreditConsentGate gate = new ConsentBackedCreditConsentGate(
                    new ConsentGate<>(storeGranting(ConsentBackedCreditConsentGate.purposeOf(granted))));
            for (CreditSourceKind asked : CreditSourceKind.values()) {
                assertThat(gate.permits(null, PARTY, asked))
                        .as("with only %s's purpose granted, asking for %s", granted, asked)
                        .isEqualTo(asked == granted);
            }
        }
    }

    @Test
    @DisplayName("the gate asks consent for exactly the party and the kind's purpose, nothing cached")
    void everyQuestionReachesTheStore() {
        Map<ConsentPurpose, Integer> asked = new EnumMap<>(ConsentPurpose.class);
        ConsentBackedCreditConsentGate gate = new ConsentBackedCreditConsentGate(new ConsentGate<>(
                new RecordingStore(asked)));
        Arrays.stream(CreditSourceKind.values()).forEach(kind -> {
            gate.permits(null, PARTY, kind);
            gate.permits(null, PARTY, kind);
        });
        assertThat(asked).containsOnly(
                Map.entry(ConsentPurpose.CREDIT_BUREAU_ACCESS, 2), Map.entry(ConsentPurpose.FINANCIAL_DATA_ACCESS, 2));
    }

    // -----------------------------------------------------------------

    private static final UUID PARTY = UUID.fromString("0190a1b2-0000-7000-8000-000000000001");

    /** A store in which exactly one purpose is currently granted to {@link #PARTY}. */
    private static ConsentStore<Connection> storeGranting(ConsentPurpose purpose) {
        return new RecordingStore(new EnumMap<>(ConsentPurpose.class)) {
            @Override
            public boolean hasCurrentBasis(Connection unitOfWork, UUID partyId, ConsentPurpose asked) {
                return PARTY.equals(partyId) && asked == purpose;
            }
        };
    }

    /** Counts every question; answers no basis. Only the derivation read is exercised here. */
    private static class RecordingStore implements ConsentStore<Connection> {

        private final Map<ConsentPurpose, Integer> asked;

        RecordingStore(Map<ConsentPurpose, Integer> asked) {
            this.asked = asked;
        }

        @Override
        public boolean hasCurrentBasis(Connection unitOfWork, UUID partyId, ConsentPurpose purpose) {
            asked.merge(purpose, 1, Integer::sum);
            return false;
        }

        @Override
        public void append(Connection unitOfWork, ConsentRecord record) {
            throw new UnsupportedOperationException("the credit gate never writes consent");
        }

        @Override
        public Optional<ConsentRecord> latestFor(Connection unitOfWork, UUID partyId, ConsentPurpose purpose) {
            throw new UnsupportedOperationException("the credit gate reads only the derived basis");
        }

        @Override
        public ConsentText currentTextFor(Connection unitOfWork, ConsentPurpose purpose) {
            throw new UnsupportedOperationException("the credit gate reads only the derived basis");
        }

        @Override
        public GrantAssessment assessGrant(Connection unitOfWork, ConsentPurpose purpose, int textVersion) {
            throw new UnsupportedOperationException("the credit gate never grants");
        }
    }
}
