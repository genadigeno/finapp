package com.finapp.risk.testing;

import com.finapp.platform.security.ActorType;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.CaseAction;
import com.finapp.risk.CaseActionKind;
import com.finapp.risk.CaseEvent;
import com.finapp.risk.CaseEventKind;
import com.finapp.risk.CaseKind;
import com.finapp.risk.Disposition;
import com.finapp.risk.JdbcRiskCaseStore;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskCaseStore;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SlaDeadlines;
import com.finapp.risk.SubjectKind;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Planted case rows for the database suites that need a disposition to exist (`P13-TSK-016`): since {@code risk V012} a
 * {@code DISPOSITION} profile version names its disposition by foreign key - a {@code DISPOSITION_PROPOSED}
 * investigator action - so `-007`'s profile suites stand a party at a rating over real rows, never a random id. A case of
 * the party is opened (revision 0, its {@code OPENED} history row) and one proposal action per disposition asked is
 * appended; nothing is decided - the profile's rows are the suites' subject, the case's are {@code CaseDatabaseTest}'s.
 */
public final class RiskCaseFixtures {

    private static final JdbcRiskCaseStore STORE = new JdbcRiskCaseStore();

    private RiskCaseFixtures() {}

    /** {@code count} disposition ids of a fresh {@code FRAUD} case of {@code party}, on the caller's unit of work. */
    public static List<UUID> dispositions(Connection unitOfWork, IdGenerator ids, UUID party, int count) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(party, "party");
        SignalSubject subject = SignalSubject.of(SubjectKind.PARTY, party);
        RiskCase riskCase = STORE.lockOpen(unitOfWork, subject, CaseKind.FRAUD).orElseGet(() -> {
            RiskCase opened = STORE.insert(unitOfWork, new RiskCaseStore.NewCase(RiskCaseId.next(ids), CaseKind.FRAUD,
                    subject, SlaDeadlines.hours(CaseKind.FRAUD), Optional.empty(), Optional.empty(), Optional.empty(),
                    "planted-case")).orElseThrow();
            STORE.appendEvent(unitOfWork, new CaseEvent(opened.id(), 0, CaseEventKind.OPENED, opened.status(),
                    opened.level(), opened.queue(), new RiskCase.Person("planted", ActorType.EMPLOYEE),
                    Optional.empty(), Optional.empty(), Optional.empty(), "planted-case", Optional.empty()));
            return opened;
        });
        int ordinal = STORE.actions(unitOfWork, riskCase.id()).size();
        List<UUID> dispositions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = ids.next();
            STORE.appendAction(unitOfWork, new CaseAction(id, riskCase.id(), ++ordinal,
                    CaseActionKind.DISPOSITION_PROPOSED, new RiskCase.Person("planted", ActorType.EMPLOYEE),
                    Optional.of("a planted disposition"), AlertEvidence.NONE, Optional.of(Disposition.CONFIRMED_FRAUD),
                    false, Optional.empty(), "planted-case", Optional.empty()));
            dispositions.add(id);
        }
        return List.copyOf(dispositions);
    }
}
