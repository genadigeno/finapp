package com.finapp.app.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The post-recovery window's command (`P16-TSK-024`): what it reads from its arguments and the provider's file before anything is
 * delivered. Its delivery and its hold run against a restored database, in the restore rehearsal (section 11.55), and are held there by
 * {@code RestoreRehearsalScriptTest}.
 */
@DisplayName("the post-recovery window's command reads its window and the file's business date, or refuses (P16-TSK-024)")
class RestoreReconciliationTest {

    @Test
    @DisplayName("the window's start as psql prints a timestamptz, at any precision, or in ISO-8601; anything else refuses")
    void theWindowStartsWhereTheScriptReadIt() {
        assertThat(RestoreReconciliation.instant("2026-11-13 13:45:23.342625+00"))
                .isEqualTo(OffsetDateTime.of(2026, 11, 13, 13, 45, 23, 342_625_000, ZoneOffset.UTC));
        assertThat(RestoreReconciliation.instant("2026-11-13 13:45:23+00"))
                .isEqualTo(OffsetDateTime.of(2026, 11, 13, 13, 45, 23, 0, ZoneOffset.UTC));
        assertThat(RestoreReconciliation.instant("2026-11-13 15:45:23.5+02"))
                .isEqualTo(OffsetDateTime.of(2026, 11, 13, 15, 45, 23, 500_000_000, ZoneOffset.ofHours(2)));
        assertThat(RestoreReconciliation.instant("2026-11-13T13:45:23Z"))
                .isEqualTo(OffsetDateTime.of(2026, 11, 13, 13, 45, 23, 0, ZoneOffset.UTC));
        assertThatThrownBy(() -> RestoreReconciliation.instant("yesterday")).isInstanceOf(DateTimeParseException.class);
    }

    @Test
    @DisplayName("the business date is the one the statement's opening balance states; a file stating none is refused before delivery")
    void theBusinessDateIsTheStatementsOwn() {
        String statement = ":20:RRSEED-STMT-2-0A1B2C3D\n:25:SIMBANK-EUR-01\n:28C:2\n:60F:C,2026-11-12,EUR,10.00\n:61:2026-11-12,C,1.00,LCAP\n"
                + ":62F:C,2026-11-12,EUR,11.00\n";
        assertThat(RestoreReconciliation.businessDate(statement)).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(RestoreReconciliation.businessDate(statement.replace(":60F:C,", ":60F:D,"))).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThatThrownBy(() -> RestoreReconciliation.businessDate(":20:X\n:61:2026-11-12,C,1.00,LCAP\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("1.00");
    }

    @Test
    @DisplayName("the hold it requires names the application's role and all three platform roles")
    void theHoldNamesTheRoles() {
        assertThat(RestoreReconciliation.APPLICATION_ROLE).isEqualTo("finapp_app");
        assertThat(RestoreReconciliation.PLATFORM_ROLES).isEqualTo(List.of("finapp_app", "finapp_migrator", "finapp_reporting"));
    }
}
