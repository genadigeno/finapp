package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V008` reconciled against the vocabulary that generates it (`P8-TSK-016`, ADR-0065 §3,
 * ADR-0068 §§1-3): the item's line-type and key-kind {@code CHECK}s are the enums' WHOLE value
 * lists (the bank members and {@code REMITTANCE_REF} added to `V003`'s report vocabulary), the
 * position rule is written as designed and mirrored by {@link ExternalItems.NewItem}'s own
 * construction, the frozen-copy trigger freezes the attribution with the rest of the line while
 * keeping the item machine's edges, and a value-date group's candidate may carry no key.
 */
@DisplayName("reconciliation V008 reconciliation (P8-TSK-016)")
class ReconciliationV008MigrationTest {

    private static final String V008 =
            "db/migration/reconciliation/V008__bank_items_and_attribution.sql";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID BANK_SOURCE =
            UUID.fromString("01a0e2bc-8200-7004-8000-000000000004");
    private static final UUID PSP_SOURCE =
            UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");

    @Test
    @DisplayName("the line-type and key-kind CHECKs are the enums' whole lists - the bank"
            + " members and REMITTANCE_REF exactly what V008 adds to V003's report vocabulary")
    void theBankVocabularyIsTheEnums() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "ADD CONSTRAINT external_item_line_type CHECK (line_type IN ("
                                + ExternalLineType.sqlValueList(ExternalLineType.bankVocabulary())
                                + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT external_item_key_kind CHECK (key_kind IN ("
                                + ItemKeyKind.sqlValueList(ItemKeyKind.bankVocabulary()) + "))"));

        java.util.Set<ExternalLineType> bankMembers =
                EnumSet.copyOf(ExternalLineType.bankVocabulary());
        bankMembers.removeAll(ExternalLineType.reportVocabulary());
        assertThat(bankMembers)
                .containsExactlyInAnyOrder(
                        ExternalLineType.BANK_CREDIT,
                        ExternalLineType.BANK_DEBIT,
                        ExternalLineType.BANK_FEE);
        assertThat(Arrays.stream(ExternalLineType.values())
                        .filter(ExternalLineType::isBankLine))
                .containsExactlyInAnyOrder(
                        ExternalLineType.BANK_CREDIT,
                        ExternalLineType.BANK_DEBIT,
                        ExternalLineType.BANK_FEE);
        assertThat(ExternalLineType.BANK_FEE.allocating())
                .as("a bank fee's effect IS the recognition's PROCESSING_COSTS line")
                .isFalse();
        java.util.Set<ItemKeyKind> bankKeys = EnumSet.copyOf(ItemKeyKind.bankVocabulary());
        bankKeys.removeAll(ItemKeyKind.reportVocabulary());
        assertThat(bankKeys).containsExactly(ItemKeyKind.REMITTANCE_REF);
    }

    @Test
    @DisplayName("the attribution column and the position rule are written as designed: a"
            + " report line in its position and never attributed, a bank credit or debit in a"
            + " position exactly when attributed, a bank fee in neither")
    void thePositionRuleIsWrittenAsDesigned() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("ADD COLUMN attributed_source_id UUID")
                .contains("ALTER COLUMN position_purpose DROP NOT NULL")
                .contains(normalized(
                        "ADD CONSTRAINT external_item_position_rule CHECK ("
                                + " (line_type NOT IN ('BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE')"
                                + " AND position_purpose IS NOT NULL"
                                + " AND attributed_source_id IS NULL)"
                                + " OR (line_type IN ('BANK_CREDIT', 'BANK_DEBIT')"
                                + " AND (position_purpose IS NULL)"
                                + " = (attributed_source_id IS NULL))"
                                + " OR (line_type = 'BANK_FEE'"
                                + " AND position_purpose IS NULL"
                                + " AND attributed_source_id IS NULL))"))
                .contains(normalized(
                        "ON reconciliation.external_item (attributed_source_id, status)"
                                + " WHERE attributed_source_id IS NOT NULL"));
    }

    @Test
    @DisplayName("the copied line stays frozen - its attribution and position with it - while"
            + " the item machine's edges are the enum's; a value-date candidate may be keyless;"
            + " nothing is granted")
    void theFrozenCopyAndTheKeylessCandidate() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("OR NEW.attributed_source_id IS DISTINCT FROM OLD.attributed_source_id")
                .contains("OR NEW.position_purpose IS DISTINCT FROM OLD.position_purpose")
                .contains(ItemStatus.sqlTransitionRule())
                .contains(normalized(
                        "ALTER TABLE reconciliation.match_candidate"
                                + " ALTER COLUMN key_kind DROP NOT NULL"));
        assertThat(sql)
                .as("attribution is written at birth and never moves: no grant, no DELETE")
                .doesNotContain("GRANT");
    }

    @Test
    @DisplayName("the domain rank mirrors the CHECK: NewItem refuses every position and"
            + " attribution the rule refuses")
    void theDomainRankMirrorsTheCheck() {
        Optional<AccountPurpose> clearing = Optional.of(AccountPurpose.SETTLEMENT_CLEARING);
        Optional<UUID> psp = Optional.of(PSP_SOURCE);

        // Admitted: an attributed and an unattributed bank line, a bare bank fee, a report line.
        item(ExternalLineType.BANK_CREDIT, clearing, psp);
        item(ExternalLineType.BANK_DEBIT, Optional.empty(), Optional.empty());
        item(ExternalLineType.BANK_FEE, Optional.empty(), Optional.empty());
        item(ExternalLineType.CAPTURE, clearing, Optional.empty());

        assertThatThrownBy(() -> item(ExternalLineType.BANK_CREDIT, clearing, Optional.empty()))
                .as("a bank credit in a position nobody attributed")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(ExternalLineType.BANK_DEBIT, Optional.empty(), psp))
                .as("an attributed bank debit without its source's position")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(ExternalLineType.BANK_FEE, clearing, Optional.empty()))
                .as("a bank fee stands in no position")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(ExternalLineType.BANK_FEE, Optional.empty(), psp))
                .as("a bank fee names nobody")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(ExternalLineType.CAPTURE, clearing, psp))
                .as("a report line is never attributed")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> item(ExternalLineType.CAPTURE, Optional.empty(), Optional.empty()))
                .as("a report line always stands in its source's position")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private static ExternalItems.NewItem item(
            ExternalLineType type,
            Optional<AccountPurpose> position,
            Optional<UUID> attributedSource) {
        LocalDate day = LocalDate.parse("2026-09-25");
        return new ExternalItems.NewItem(
                IDS.next(),
                IDS.next(),
                BANK_SOURCE,
                IDS.next(),
                1,
                type,
                type == ExternalLineType.BANK_CREDIT
                        ? ExpectationDirection.INBOUND
                        : ExpectationDirection.OUTBOUND,
                Money.ofPersisted(500, EUR, 2),
                position,
                attributedSource,
                day,
                Optional.empty(),
                Optional.of(day),
                new byte[32],
                Map.of(),
                Instant.now(CLOCK),
                CorrelationId.generate(IDS));
    }

    /** Whitespace collapsed, so a generated fragment matches however the SQL wraps. */
    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    private static String migration() {
        try (InputStream migration =
                ReconciliationV008MigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(V008)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + V008);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + V008, e);
        }
    }
}
