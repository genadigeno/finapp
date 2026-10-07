package com.finapp.crossborder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.crossborder.BeneficiaryVocabulary.EntityType;
import com.finapp.crossborder.BeneficiaryVocabulary.ScreeningOutcome;
import com.finapp.crossborder.BeneficiaryVocabulary.SelectionOutcome;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cross-border beneficiary's pure rules (`P9-TSK-017`, ADR-0080 sections 3 and 5a): the selection's
 * judgement order and determinism, the machine exhaustively, the listener's moves (a revoked beneficiary is
 * never moved), the shaped statuses, the registration's shape screens and redaction, and {@code V003}
 * generated from the same vocabularies.
 */
@DisplayName("the cross-border beneficiary's rules (P9-TSK-017)")
class BeneficiaryDomainTest {

    private static final CountryCode JP = CountryCode.of("JP");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CorridorSelection.Inputs INPUTS = new CorridorSelection.Inputs(JP, JPY, EntityType.INDIVIDUAL);

    private static CorridorTerms corridor(String source, List<String> rails) {
        CurrencyCode s = CurrencyCode.of(source);
        return new CorridorTerms(new CorridorKey(s, JPY, JP), rails, Money.of(new BigDecimal("2.50"), s), BigDecimal.ZERO,
                RoundingPolicy.HALF_EVEN, Money.of(new BigDecimal("1500000"), JPY), Duration.ofHours(168),
                Duration.ofHours(24), Set.of(RequiredData.BENEFICIARY_NAME, RequiredData.ENTITY_TYPE));
    }

    private static CorridorDirectory directory(Set<CorridorDirectory.DeclaredRail> declared, Set<String> operable) {
        return new CorridorDirectory() {
            @Override
            public Set<DeclaredRail> declaredRails() {
                return declared;
            }

            @Override
            public boolean operable(String rail) {
                return operable.contains(rail);
            }
        };
    }

    private static CorridorDirectory.DeclaredRail rail(String name, String country, String currency) {
        return new CorridorDirectory.DeclaredRail(name,
                Set.of(new CorridorDirectory.Coverage(CountryCode.of(country), CurrencyCode.of(currency))));
    }

    // ------------------------------------------------------------------ selection

    @Test
    @DisplayName("each candidate is judged in order - undeclared, currency, coverage, operability - and the first"
            + " eligible is CHOSEN, nothing after it judged")
    void theSelectionJudgesInOrder() {
        CorridorDirectory directory = directory(
                Set.of(rail("rail-usd", "US", "USD"), rail("rail-jpy-kr", "KR", "JPY"), rail("rail-down", "JP", "JPY"),
                        rail("rail-up", "JP", "JPY"), rail("rail-later", "JP", "JPY")),
                Set.of("rail-up", "rail-later"));
        CorridorSelection.Selection selection = CorridorSelection.select(INPUTS,
                List.of(corridor("EUR", List.of("rail-ghost", "rail-usd", "rail-jpy-kr", "rail-down", "rail-up", "rail-later"))),
                code -> true, directory);
        assertThat(selection.steps()).extracting(CorridorSelection.Step::outcome).containsExactly(
                SelectionOutcome.UNDECLARED_BY_BUILD, SelectionOutcome.CURRENCY_UNSUPPORTED, SelectionOutcome.NO_COVERAGE,
                SelectionOutcome.UNAVAILABLE, SelectionOutcome.CHOSEN);
        assertThat(selection.chosen()).contains("rail-up");
    }

    @Test
    @DisplayName("only available corridors delivering the currency in the country give candidates, in code order,"
            + " a rail judged once; recomputing over the stored inputs reproduces the selection")
    void theSelectionIsDeterministic() {
        CorridorDirectory directory = directory(Set.of(rail("rail-a", "JP", "JPY"), rail("rail-b", "JP", "JPY")),
                Set.of("rail-b"));
        List<CorridorTerms> corridors = List.of(corridor("USD", List.of("rail-b")), corridor("EUR", List.of("rail-a", "rail-b")),
                corridor("GBP", List.of("rail-a")));
        CorridorSelection.Selection first =
                CorridorSelection.select(INPUTS, corridors, code -> !code.equals("GBP-JPY-JP"), directory);
        assertThat(first.availableCorridors()).containsExactlyInAnyOrder("EUR-JPY-JP", "USD-JPY-JP");
        assertThat(first.steps()).extracting(CorridorSelection.Step::corridor, CorridorSelection.Step::rail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EUR-JPY-JP", "rail-a"),
                        org.assertj.core.groups.Tuple.tuple("EUR-JPY-JP", "rail-b"));
        CorridorSelection.Selection again =
                CorridorSelection.select(INPUTS, corridors, first.availableCorridors()::contains, directory);
        assertThat(again).isEqualTo(first);
        assertThat(CorridorSelection.select(INPUTS, corridors, code -> false, directory).chosen())
                .as("no available corridor, nothing chosen").isEmpty();
    }

    // ------------------------------------------------------------------ the machine

    @Test
    @DisplayName("the machine has exactly ADR-0080's edges, revocation from every non-terminal state, REVOKED final")
    void theMachine() {
        EnumSet<BeneficiaryStatus> nonTerminal = EnumSet.complementOf(EnumSet.of(BeneficiaryStatus.REVOKED));
        for (BeneficiaryStatus from : BeneficiaryStatus.values()) {
            for (BeneficiaryStatus to : BeneficiaryStatus.values()) {
                boolean expected = switch (from) {
                    case PENDING_SCREENING -> to == BeneficiaryStatus.ACTIVE || to == BeneficiaryStatus.IN_REVIEW;
                    case IN_REVIEW -> to == BeneficiaryStatus.ACTIVE || to == BeneficiaryStatus.BLOCKED;
                    case ACTIVE -> to == BeneficiaryStatus.IN_REVIEW;
                    case BLOCKED, REVOKED -> false;
                } || (to == BeneficiaryStatus.REVOKED && nonTerminal.contains(from));
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("the listener's moves: cleared activates, review and block follow kyc, unavailable moves nothing,"
            + " and a revoked beneficiary is never moved")
    void theListenerMoves() {
        assertThat(Beneficiaries.target(BeneficiaryStatus.PENDING_SCREENING, ScreeningOutcome.CLEARED)).contains(BeneficiaryStatus.ACTIVE);
        assertThat(Beneficiaries.target(BeneficiaryStatus.PENDING_SCREENING, ScreeningOutcome.IN_REVIEW)).contains(BeneficiaryStatus.IN_REVIEW);
        assertThat(Beneficiaries.target(BeneficiaryStatus.IN_REVIEW, ScreeningOutcome.CLEARED)).contains(BeneficiaryStatus.ACTIVE);
        assertThat(Beneficiaries.target(BeneficiaryStatus.IN_REVIEW, ScreeningOutcome.BLOCKED)).contains(BeneficiaryStatus.BLOCKED);
        assertThat(Beneficiaries.target(BeneficiaryStatus.ACTIVE, ScreeningOutcome.IN_REVIEW)).contains(BeneficiaryStatus.IN_REVIEW);
        assertThat(Beneficiaries.target(BeneficiaryStatus.ACTIVE, ScreeningOutcome.CLEARED)).isEmpty();
        assertThat(Beneficiaries.target(BeneficiaryStatus.PENDING_SCREENING, ScreeningOutcome.BLOCKED))
                .as("only a reviewed beneficiary is blocked").isEmpty();
        for (ScreeningOutcome outcome : ScreeningOutcome.values()) {
            assertThat(Beneficiaries.target(BeneficiaryStatus.REVOKED, outcome)).as("revoked, " + outcome).isEmpty();
            assertThat(Beneficiaries.target(BeneficiaryStatus.PENDING_SCREENING, ScreeningOutcome.UNAVAILABLE)).isEmpty();
        }
    }

    @Test
    @DisplayName("the shaped statuses hide review and block (tipping-off)")
    void theShapes() {
        assertThat(BeneficiaryStatus.PENDING_SCREENING.shaped()).isEqualTo(BeneficiaryStatus.IN_REVIEW.shaped())
                .isEqualTo("PENDING_VERIFICATION");
        assertThat(BeneficiaryStatus.BLOCKED.shaped()).isEqualTo("UNAVAILABLE");
        assertThat(BeneficiaryStatus.ACTIVE.shaped()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ the registration

    @Test
    @DisplayName("a grant or nickname holding a bank identifier is refused; the grant and the name are redacted")
    void theRegistrationScreensAndRedacts() {
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> registration(owner, "GB82WEST12345698765432", "Aunt"))
                .isInstanceOf(Beneficiaries.RegistrationInvalid.class);
        assertThatThrownBy(() -> registration(owner, "grant-1", "card 4111 1111 1111 1111"))
                .isInstanceOf(Beneficiaries.RegistrationInvalid.class);
        Beneficiaries.Registration ok = registration(owner, "grant-secret-77", "Aunt in Osaka");
        assertThat(ok.toString()).doesNotContain("grant-secret-77").doesNotContain("Akiko Tanaka");
        assertThat(Beneficiaries.exchangeReference(owner, "grant-secret-77")).matches("XBB[0-9a-f]{32}")
                .isEqualTo(Beneficiaries.exchangeReference(owner, "grant-secret-77"))
                .isNotEqualTo(Beneficiaries.exchangeReference(UUID.randomUUID(), "grant-secret-77"));
        assertThat(new CounterpartyScreening.Request("r", "Akiko Tanaka", JP, EntityType.INDIVIDUAL,
                        BeneficiaryVocabulary.PayeeCheck.MATCH, "registrant").toString()).doesNotContain("Akiko");
        assertThat(new CorridorDirectory.Exchange.Exchanged("dest-ref-9", "AB12", BeneficiaryVocabulary.PayeeCheck.MATCH, JP,
                        JPY, EntityType.INDIVIDUAL).toString()).doesNotContain("dest-ref-9");
    }

    @Test
    @DisplayName("Phase 9 to 10 transition: a provider's reference holding a bank identifier or card number, or outside the"
            + " column's charset, is not storable - refused before any write; an opaque reference is")
    void aProvidersShapedReferenceIsNotStorable() {
        assertThat(Beneficiaries.storable(exchanged("XD-1a2b3c4d-17", "0017"))).isTrue();
        for (String shaped : List.of("DE89370400440532013000", "XD-DE89370400440532013000", "4111111111111111",
                "ref:4111-1111-1111-1111", "not opaque!", "")) {
            assertThat(Beneficiaries.storable(exchanged(shaped, "0017"))).as(shaped).isFalse();
        }
        assertThat(Beneficiaries.storable(exchanged("XD-1a2b3c4d-17", "17"))).as("a suffix is four").isFalse();
    }

    private static CorridorDirectory.Exchange.Exchanged exchanged(String destination, String suffix) {
        return new CorridorDirectory.Exchange.Exchanged(destination, suffix, BeneficiaryVocabulary.PayeeCheck.MATCH, JP, JPY,
                EntityType.INDIVIDUAL);
    }

    private static Beneficiaries.Registration registration(UUID owner, String grant, String nickname) {
        return new Beneficiaries.Registration(owner, JP, JPY, grant, "Akiko Tanaka", nickname, EntityType.INDIVIDUAL, false);
    }

    // ------------------------------------------------------------------ the schema

    @Test
    @DisplayName("V003's closed lists are the vocabularies', and the beneficiary updates only its machine columns")
    void theMigrationAgrees() {
        String sql = migration();
        assertThat(sql)
                .contains("CHECK (status IN (" + BeneficiaryStatus.sqlValueList() + "))")
                .contains("CHECK (outcome IN (" + BeneficiaryVocabulary.sqlValueList(SelectionOutcome.values()) + "))")
                .contains("CHECK (payee_check IN (" + BeneficiaryVocabulary.sqlValueList(BeneficiaryVocabulary.PayeeCheck.values()) + "))")
                .contains("CHECK (entity_type IN (" + BeneficiaryVocabulary.sqlValueList(EntityType.values()) + "))")
                .contains("CHECK (cause IN (" + BeneficiaryVocabulary.sqlValueList(BeneficiaryVocabulary.StatusCause.values()) + "))")
                .contains("GRANT UPDATE (status, screening_id, revoked_at) ON crossborder.beneficiary TO finapp_app")
                .doesNotContainPattern("GRANT[^;]*DELETE")
                .doesNotContain("UPDATE ON crossborder.beneficiary_status_event");
    }

    private static String migration() {
        String path = "db/migration/crossborder/V003__cross_border_beneficiaries.sql";
        try (InputStream in = BeneficiaryDomainTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
