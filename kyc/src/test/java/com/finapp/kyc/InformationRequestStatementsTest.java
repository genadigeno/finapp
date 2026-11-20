package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statements that make the answer and the expiry correct at N instances (`P17-TSK-126`, ADR-0155 decision 7), pinned as
 * text: no instance's clock decides the deadline, a sweeper skips what another holds, and a row closed between the page's
 * pick and its update is not closed twice. Their behaviour under concurrent transactions on PostgreSQL is owed to the database
 * tier (the owner's ruling of 2026-11-20); what they say is held here.
 */
@DisplayName("the expiry and the answer are judged on the database clock, and the sweep skips locked rows (P17-TSK-126)")
class InformationRequestStatementsTest {

    @Test
    @DisplayName("an expiry page picks OPEN lapsed rows FOR UPDATE SKIP LOCKED, earliest due first, and re-checks both on update")
    void theExpiryIsLeaderless() {
        String sql = JdbcInformationRequestStore.EXPIRE_PAGE;
        assertThat(sql).contains("FOR UPDATE SKIP LOCKED")
                .contains("ORDER BY due_at, id LIMIT ?")
                .contains("SET status = 'EXPIRED'")
                .doesNotContain("now()")
                .doesNotContain("?::timestamptz");
        assertThat(occurrences(sql, "due_at <= statement_timestamp()"))
                .as("judged on the database clock in the pick and again on the locked row")
                .isEqualTo(2);
        assertThat(occurrences(sql, "status = 'OPEN'"))
                .as("a row closed between the pick and the update is not closed twice")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an answer closes only an OPEN request not yet due on the database clock")
    void theAnswerIsConditionalOnTheDeadline() {
        assertThat(JdbcInformationRequestStore.ANSWER)
                .contains("WHERE case_id = ? AND status = 'OPEN' AND due_at > statement_timestamp()")
                .contains("SET status = 'ANSWERED'")
                .contains("RETURNING");
    }

    @Test
    @DisplayName("both closes stamp the database's clock, never before the asking")
    void bothClosesStampTheDatabaseClock() {
        assertThat(JdbcInformationRequestStore.ANSWER).contains("closed_at = GREATEST(statement_timestamp(), requested_at)");
        assertThat(JdbcInformationRequestStore.EXPIRE_PAGE).contains("closed_at = GREATEST(statement_timestamp(), requested_at)");
    }

    private static int occurrences(String text, String fragment) {
        Matcher matcher = Pattern.compile(Pattern.quote(fragment)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
