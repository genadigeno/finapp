package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The resolution machine re-asks the platform's records over an item's keys exactly as the
 * matcher asked them at raise (the Phase 8 -> 9 transition, IDEM-2): the two vocabularies'
 * mapping is held equal, member by member, or a re-asked lookup could classify the same item
 * differently from the break's own frozen answer.
 */
@DisplayName("the re-asked lookup speaks the matcher's key vocabulary (the Phase 8 -> 9 transition)")
class ResolutionLookupSubjectTest {

    @Test
    @DisplayName("every item key maps onto the lookup's kind exactly as Matching.mapToLookup does")
    void theMappingIsTheMatchersOwn() throws Exception {
        Method matchers = Matching.class.getDeclaredMethod("mapToLookup", ItemKeyKind.class);
        matchers.setAccessible(true);
        for (ItemKeyKind kind : ItemKeyKind.values()) {
            assertThat(JdbcResolutionStore.lookupKind(kind).name())
                    .as("%s re-asked as the matcher asked it", kind)
                    .isEqualTo(matchers.invoke(null, kind));
        }
    }
}
