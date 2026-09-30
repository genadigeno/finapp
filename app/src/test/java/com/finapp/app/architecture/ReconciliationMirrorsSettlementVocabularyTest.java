package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.ItemKeyKind;
import com.finapp.settlement.LineReferenceKind;
import com.finapp.settlement.SettlementLineType;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mirrored vocabularies are ONE vocabulary (`P8-TSK-009`, ADR-0064): an external item is
 * the working copy of a settlement line, but no build edge may exist between the modules, so
 * each side holds its own enum and THIS test — in the one module that sees both — holds the
 * copies name-equal. The intake crosses by {@code valueOf(name())}; a member added on one
 * side without the other fails here, never as a runtime {@code IllegalArgumentException} in
 * an acceptance transaction.
 */
@DisplayName("reconciliation mirrors settlement's line vocabulary (P8-TSK-009, ADR-0064)")
class ReconciliationMirrorsSettlementVocabularyTest {

    @Test
    @DisplayName("ExternalLineType mirrors SettlementLineType, name for name")
    void lineTypesMirror() {
        assertThat(names(ExternalLineType.values()))
                .as("the item's vocabulary is the canonical line's, copied because ADR-0064"
                        + " forbids the import")
                .isEqualTo(names(SettlementLineType.values()));
    }

    @Test
    @DisplayName("ItemKeyKind mirrors LineReferenceKind, name for name")
    void keyKindsMirror() {
        assertThat(names(ItemKeyKind.values()))
                .as("the item's keys are the line's references, copied")
                .isEqualTo(names(LineReferenceKind.values()));
    }

    @Test
    @DisplayName("the allocating split is exactly the fees' exclusion (ADR-0065 §§2-3)")
    void theAllocatingSplitIsTheFee() {
        assertThat(
                        Arrays.stream(ExternalLineType.values())
                                .filter(type -> !type.allocating())
                                .map(Enum::name))
                .as("a non-allocating line's effect is the recognition entry itself: the"
                        + " processing fee's and the scheme's fee at hop 1 (P8-TSK-017), the"
                        + " bank's fee at hop 2 (P8-TSK-016)")
                .containsExactly("PROCESSING_FEE", "BANK_FEE", "SCHEME_FEE");
    }

    private static java.util.Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }
}
