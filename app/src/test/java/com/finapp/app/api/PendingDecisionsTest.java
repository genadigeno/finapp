package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one pending-decision shape's item (`P17-TSK-127`): its kind, its subject's kind, whether the reader proposed it. */
@DisplayName("a pending decision names its kind, its subject's kind and whether the reader proposed it (P17-TSK-127)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PendingDecisionsTest {

    private static final Instant AT = Instant.parse("2026-11-21T09:00:00Z");
    private static final Actor PROPOSER = new Actor("operator-1", ActorType.EMPLOYEE);
    private static final Actor APPROVER = new Actor("operator-2", ActorType.EMPLOYEE);

    @Test
    @DisplayName("every kind is <module>.<Kind>, named once, and every subject kind is an upper-case word")
    void theVocabularyIsWellFormed() {
        Pattern wire = Pattern.compile("[a-z]+\\.[A-Z][A-Za-z]+");
        assertThat(Arrays.stream(PendingDecisionKind.values()).map(PendingDecisionKind::wire))
                .allMatch(name -> wire.matcher(name).matches())
                .doesNotHaveDuplicates();
        assertThat(Arrays.stream(PendingDecisionKind.values()).flatMap(kind -> kind.subjectKinds().stream()))
                .allMatch(word -> word.matches("[A-Z]+(?:_[A-Z]+)*"));
    }

    @Test
    @DisplayName("the subject's kind is the kind's own, absent with no subject, and named row by row where a kind has two")
    void theSubjectKindFollowsTheKind() {
        UUID account = UUID.randomUUID();
        PendingDecisions.PendingDecision restriction =
                PendingDecisions.of(UUID.randomUUID(), PendingDecisionKind.ACCOUNTS_RESTRICTION, "p", AT, "why", account);
        assertThat(restriction.kind()).isEqualTo("accounts.Restriction");
        assertThat(restriction.subjectKind()).isEqualTo("ACCOUNT");
        assertThat(restriction.subjectId()).isEqualTo(account.toString());

        assertThat(PendingDecisions.of(UUID.randomUUID(), PendingDecisionKind.LEDGER_ADJUSTMENT, "p", AT, "why", null)
                .subjectKind()).isNull();

        assertThat(PendingDecisions.ofSubject(UUID.randomUUID(), PendingDecisionKind.DEADLETTER_DISCARD, "p", AT, "why",
                "INBOX_RECORD", UUID.randomUUID()).subjectKind()).isEqualTo("INBOX_RECORD");
        assertThatThrownBy(() -> PendingDecisions.of(UUID.randomUUID(), PendingDecisionKind.FX_ENABLE_REQUEST, "p", AT, "why",
                "GBP-JPY")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PendingDecisions.ofSubject(UUID.randomUUID(), PendingDecisionKind.FX_ENABLE_REQUEST, "p", AT,
                "why", "CORRIDOR", "GBP-JPY")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("proposedByCaller is true for the proposer alone: another operator, and a read with no actor, see false")
    void theCallerIsJudgedAgainstTheRequestsOwnActor() {
        try (SecurityContext.Scope proposer = SecurityContext.enter(PROPOSER)) {
            assertThat(item().proposedByCaller()).isTrue();
        }
        try (SecurityContext.Scope approver = SecurityContext.enter(APPROVER)) {
            assertThat(item().proposedByCaller()).isFalse();
        }
        assertThat(item().proposedByCaller()).isFalse();
        assertThat(PendingDecisions.proposedByCaller(null)).isFalse();
    }

    @Test
    @DisplayName("every queue takes PROPOSED beside its own word, and refuses any other status")
    void theUniformWordNamesEveryQueue() {
        PendingDecisions.pendingOnly(null, "VALIDATED");
        PendingDecisions.pendingOnly("VALIDATED", "VALIDATED");
        PendingDecisions.pendingOnly(PendingDecisions.UNIFORM_STATUS, "VALIDATED");
        PendingDecisions.pendingOnly("PROPOSED", "PROPOSED");
        assertThatThrownBy(() -> PendingDecisions.pendingOnly("APPROVED", "VALIDATED")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PendingDecisions.pendingOnly("proposed", "PROPOSED")).isInstanceOf(ApiException.class);
    }

    private static PendingDecisions.PendingDecision item() {
        return PendingDecisions.of(UUID.randomUUID(), PendingDecisionKind.TRANSFERS_REVERSAL, PROPOSER.id(), AT, "why",
                UUID.randomUUID());
    }
}
