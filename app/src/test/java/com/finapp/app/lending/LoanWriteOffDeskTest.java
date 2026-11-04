package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.lending.LoanId;
import com.finapp.lending.LoanWriteOff;
import com.finapp.lending.LoanWriteOffPosition;
import com.finapp.lending.LoanWriteOffStatus;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The loan write-off doors' boundary, hermetic (`P14-TSK-021`): each route's permission, the approval's {@code MULTI_FACTOR} (the
 * owner's decision on the entry), every act keyed; the receipt a retried key replays, never a reason or a person; the key held to every
 * figure the approver named; what they name parsed exactly, never by {@code new BigDecimal}; nothing printing a figure or a reason.
 * Over HTTP the doors are {@code LoanWriteOffApiTest} (written, owed - C5).
 */
@DisplayName("the loan write-off doors' boundary (P14-TSK-021)")
class LoanWriteOffDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("the proposal carries the propose permission; the read, the list and both decisions the approve permission (each door serves"
            + " what it decides under its own); only the approval is MULTI_FACTOR; every act keyed and the reads not")
    void theRoutesPermissionsAndAssurance() throws NoSuchMethodException {
        Method propose = LoanWriteOffController.class.getMethod("proposeLoanWriteOff", String.class, String.class, LoanWriteOffController.LoanWriteOffProposalRequest.class);
        Method approve = LoanWriteOffController.class.getMethod("approveLoanWriteOff", String.class, String.class, String.class,
                LoanWriteOffController.LoanWriteOffApprovalRequest.class);
        Method reject = LoanWriteOffController.class.getMethod("rejectLoanWriteOff", String.class, String.class, String.class,
                LoanWriteOffController.LoanWriteOffRejectionRequest.class);
        Method read = LoanWriteOffController.class.getMethod("readLoanWriteOff", String.class, String.class);
        Method list = LoanWriteOffController.class.getMethod("listPendingLoanWriteOffs");
        assertThat(propose.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.LOAN_WRITE_OFF_PROPOSE);
        for (Method approver : List.of(approve, reject, read, list)) {
            assertThat(approver.getAnnotation(RequiresPermission.class).value()).as(approver.getName())
                    .isEqualTo(PermissionName.LOAN_WRITE_OFF_APPROVE);
        }
        assertThat(approve.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        for (Method lower : List.of(propose, reject, read, list)) {
            assertThat(lower.getAnnotation(RequiresAssurance.class)).as(lower.getName()).isNull();
        }
        for (Method act : List.of(propose, approve, reject)) {
            assertThat(act.getAnnotation(RequiresIdempotencyKey.class)).as(act.getName()).isNotNull();
        }
        for (Method seen : List.of(read, list)) {
            assertThat(seen.getAnnotation(GetMapping.class)).as(seen.getName()).isNotNull();
            assertThat(seen.getAnnotation(RequiresIdempotencyKey.class)).as(seen.getName()).isNull();
        }
    }

    @Test
    @DisplayName("a receipt replays exactly, proposed and approved, and holds no reason and no person")
    void theReceiptReplaysAndNamesNoPerson() {
        LoanWriteOff proposed = writeOff(LoanWriteOffStatus.PROPOSED, Optional.empty(), Optional.empty(), Optional.empty());
        LoanWriteOffDesk.WriteOffReceipt first = LoanWriteOffDesk.receipt(proposed);
        byte[] stored = LoanWriteOffDesk.stored(first).body();
        assertThat(LoanWriteOffDesk.replayed(stored)).isEqualTo(first);
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("a private reason").doesNotContain("the proposer");
        assertThat(first.total()).isEqualTo("115.00");
        assertThat(first.principalTotal()).isEqualTo("100.00");
        assertThat(first.decidedAt()).isNull();

        LoanWriteOff approved = writeOff(LoanWriteOffStatus.APPROVED, Optional.of(Instant.parse("2026-11-04T10:00:00Z")),
                Optional.of(JournalEntryId.of(IDS.next())), Optional.of(LocalDate.parse("2026-11-04")));
        LoanWriteOffDesk.WriteOffReceipt decided = LoanWriteOffDesk.receipt(approved);
        assertThat(LoanWriteOffDesk.replayed(LoanWriteOffDesk.stored(decided).body())).isEqualTo(decided);
        assertThat(decided.valueDate()).isEqualTo("2026-11-04");
    }

    @Test
    @DisplayName("the key is held to every figure, the revision and the reason: any other body fingerprints differently, so it conflicts")
    void theKeyIsHeldToEveryFigure() {
        UUID account = IDS.next();
        UUID id = IDS.next();
        LoanWriteOffPosition named = position(90_00, 10_00, 5_00, 7_00, 3_00, 42);
        String held = LoanWriteOffDesk.approvalFingerprint(account, id, named, "agreed");
        assertThat(LoanWriteOffDesk.approvalFingerprint(account, id, position(90_00, 10_00, 5_00, 7_00, 3_00, 42), "agreed")).isEqualTo(held);
        List<String> others = new ArrayList<>(List.of(
                LoanWriteOffDesk.approvalFingerprint(account, id, position(90_01, 10_00, 5_00, 7_00, 3_00, 42), "agreed"),
                LoanWriteOffDesk.approvalFingerprint(account, id, position(90_00, 10_01, 5_00, 7_00, 3_00, 42), "agreed"),
                LoanWriteOffDesk.approvalFingerprint(account, id, position(90_00, 10_00, 5_01, 7_00, 3_00, 42), "agreed"),
                LoanWriteOffDesk.approvalFingerprint(account, id, position(90_00, 10_00, 5_00, 7_01, 3_00, 42), "agreed"),
                LoanWriteOffDesk.approvalFingerprint(account, id, position(90_00, 10_00, 5_00, 7_00, 3_01, 42), "agreed"),
                LoanWriteOffDesk.approvalFingerprint(account, id, position(90_00, 10_00, 5_00, 7_00, 3_00, 43), "agreed"),
                LoanWriteOffDesk.approvalFingerprint(account, id, named, "agreed, on reflection"),
                LoanWriteOffDesk.approvalFingerprint(account, IDS.next(), named, "agreed"),
                LoanWriteOffDesk.approvalFingerprint(IDS.next(), id, named, "agreed")));
        assertThat(others).doesNotContain(held).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("what the approver names is parsed exactly: an exponent, an over-scale, a negative, an unknown currency, an amount past what"
            + " the platform can hold and a missing revision are each 422 api.ValidationFailed, never a 500")
    void whatIsNamedIsParsedExactly() {
        assertThat(LoanWriteOffDesk.attested(body("EUR", "90.00", 42L))).isEqualTo(position(90_00, 10_00, 5_00, 7_00, 3_00, 42));
        for (LoanWriteOffController.LoanWriteOffApprovalRequest refused : List.of(
                body("EUR", "9e1", 42L), body("EUR", "90.001", 42L), body("EUR", "-90.00", 42L), body("???", "90.00", 42L),
                body("EUR", "90,00", 42L), body("EUR", "999999999999999999999999.00", 42L), body("EUR", "90.00", null),
                body("EUR", "90.00", -1L))) {
            assertThatThrownBy(() -> LoanWriteOffDesk.attested(refused)).as(String.valueOf(refused.revision()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode().code()).isEqualTo("api.ValidationFailed"));
        }
    }

    @Test
    @DisplayName("no request, no view and no write-off prints a reason or a figure")
    void nothingPrintsAReasonOrAFigure() {
        String secret = "a private reason";
        assertThat(new LoanWriteOffController.LoanWriteOffProposalRequest(secret).toString()).doesNotContain(secret);
        assertThat(new LoanWriteOffController.LoanWriteOffRejectionRequest(secret).toString()).doesNotContain(secret);
        assertThat(body("EUR", "90.00", 42L).toString()).doesNotContain("90.00").doesNotContain("agreed");
        LoanWriteOff row = writeOff(LoanWriteOffStatus.PROPOSED, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(row.toString()).doesNotContain(secret).doesNotContain("the proposer");
        assertThat(position(90_00, 10_00, 5_00, 7_00, 3_00, 42).toString()).doesNotContain("90.00").doesNotContain("115");
    }

    private static LoanWriteOffController.LoanWriteOffApprovalRequest body(String currency, String principal, Long revision) {
        return new LoanWriteOffController.LoanWriteOffApprovalRequest(currency, principal, "10.00", "5.00", "7.00", "3.00", revision, "agreed");
    }

    private static LoanWriteOffPosition position(long principal, long due, long accrued, long interestDue, long fees, long revision) {
        return new LoanWriteOffPosition(eur(principal), eur(due), eur(accrued), eur(interestDue), eur(fees), revision);
    }

    private static LoanWriteOff writeOff(LoanWriteOffStatus status, Optional<Instant> decidedAt, Optional<JournalEntryId> entry, Optional<LocalDate> valueDate) {
        return new LoanWriteOff(IDS.next(), LoanId.of(IDS.next()), position(90_00, 10_00, 5_00, 7_00, 3_00, 42), IDS.next(), 1, status, "the proposer",
                Instant.parse("2026-11-03T10:00:00Z"), "a private reason", decidedAt.map(at -> "the approver"), decidedAt,
                decidedAt.map(at -> "another private reason"), entry, valueDate);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
