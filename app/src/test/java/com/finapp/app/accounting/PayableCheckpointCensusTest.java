package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.SourceProofs;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PositionBreakdown;
import com.finapp.merchant.MerchantPayable;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census of the merchant API's payable checkpoints (`X-TSK-021`; ADR-0133 decision 3): each payable read the way the API reads
 * it, from its checkpoint, against the whole fold, on one snapshot. Hermetic - the reads are the real {@link MerchantPayable} over
 * stand-in breakdowns and accounts, the snapshot a scripted connection - so the comparison and the reading's mapping are held here;
 * the census over a real database, a real checkpoint planted wrong, is {@code PositionCheckpointDatabaseTest}'s.
 */
@DisplayName("the census of the merchant API's payable checkpoints against the whole fold (X-TSK-021)")
class PayableCheckpointCensusTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID MERCHANT = IDS.next();
    private static final PositionBreakdown.Counterparty FACING_CLEARING =
            new PositionBreakdown.Counterparty(AccountPurpose.SETTLEMENT_CLEARING, Direction.DEBIT);

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    /** A payable's breakdown: captured, and fees, its position their difference. */
    private static PositionBreakdown<Connection> breakdown(long captured, long fees) {
        return (unitOfWork, account, precedence, namespaces) -> new PositionBreakdown.Breakdown(account, eur(captured - fees), List.of(
                new PositionBreakdown.Bucket(Direction.CREDIT, Optional.of(FACING_CLEARING), eur(captured), false),
                new PositionBreakdown.Bucket(Direction.DEBIT, Optional.empty(), eur(fees), false)));
    }

    @SuppressWarnings("unchecked")
    private static LedgerAccountStore<Connection> accounts(int payables) {
        List<LedgerAccount> owned = java.util.stream.IntStream.range(0, payables)
                .mapToObj(i -> LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.MERCHANT_PAYABLE, EUR, MERCHANT))
                .toList();
        return (LedgerAccountStore<Connection>) Proxy.newProxyInstance(LedgerAccountStore.class.getClassLoader(),
                new Class<?>[] {LedgerAccountStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findAllOwned")) {
                        return owned;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The proofs' snapshot, answering the merchants' census with the one merchant. */
    private static Connection snapshot() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    assertThat((String) args[0]).isEqualTo(ProductSourceProofs.MERCHANTS);
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                            (statement, called, given) -> switch (called.getName()) {
                                case "executeQuery" -> merchants();
                                case "close" -> null;
                                default -> throw new UnsupportedOperationException(called.getName());
                            });
                });
    }

    private static ResultSet merchants() {
        Iterator<UUID> cursor = List.of(MERCHANT).iterator();
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (!cursor.hasNext()) {
                            yield false;
                        }
                        cursor.next();
                        yield true;
                    }
                    case "getObject" -> MERCHANT;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static List<ProductSourceProofs.PayableTerms> census(MerchantPayable wholeFold, MerchantPayable apiRead) {
        return ProductSourceProofs.merchantPayables(wholeFold, apiRead).apply(snapshot());
    }

    @Test
    @DisplayName("a checkpointed read that is the whole fold's agrees, and the proof names nothing")
    void anAgreeingReadIsNothing() {
        LedgerAccountStore<Connection> accounts = accounts(1);
        List<ProductSourceProofs.PayableTerms> terms = census(
                new MerchantPayable(accounts, breakdown(10_000, 250)), new MerchantPayable(accounts, breakdown(10_000, 250)));

        assertThat(terms).singleElement().satisfies(payable -> {
            assertThat(payable.checkpointAgrees()).isTrue();
            assertThat(payable.merchant()).isEqualTo(MERCHANT);
        });
        assertThat(ProductSourceProofs.checkpointReading(terms).discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("a checkpointed read that differs in one term - a forged or wrong checkpoint - is named, by a fixed string and no amount")
    void aDifferingReadIsNamed() {
        LedgerAccountStore<Connection> accounts = accounts(1);
        List<ProductSourceProofs.PayableTerms> terms = census(
                new MerchantPayable(accounts, breakdown(10_000, 250)), new MerchantPayable(accounts, breakdown(10_000, 251)));

        assertThat(terms).singleElement().satisfies(payable -> {
            assertThat(payable.checkpointAgrees()).isFalse();
            assertThat(payable.sumHolds()).as("the terms proof reads the whole fold, which is right").isTrue();
        });
        SourceProofs.Reading reading = ProductSourceProofs.checkpointReading(terms);
        assertThat(reading.proof()).isEqualTo(SourceProofs.Proof.PAYABLE_CHECKPOINT);
        assertThat(reading.complete()).isTrue();
        assertThat(reading.discrepancies()).singleElement().satisfies(discrepancy -> {
            assertThat(discrepancy.subject()).isEqualTo(MERCHANT + ":EUR");
            assertThat(discrepancy.evidence()).isEqualTo("checkpointAgreesWithTheWholeFold=false")
                    .doesNotContain("251").doesNotContain("2.51");
        });
        assertThat(ProductSourceProofs.payableReading(terms).discrepancies()).as("not the terms proof's finding").isEmpty();
    }

    @Test
    @DisplayName("a read that comes back with another number of payables disagrees, never passes")
    void anotherNumberOfPayablesDisagrees() {
        LedgerAccountStore<Connection> one = accounts(1);
        List<ProductSourceProofs.PayableTerms> terms = census(
                new MerchantPayable(one, breakdown(10_000, 250)), new MerchantPayable(accounts(2), breakdown(10_000, 250)));

        assertThat(terms).singleElement().satisfies(payable -> assertThat(payable.checkpointAgrees()).isFalse());
    }

    @Test
    @DisplayName("the payable source answers both its proofs, the terms and the checkpoint, from one reading")
    void thePayableSourceAnswersBothProofs() {
        List<ProductSourceProofs.PayableTerms> terms = List.of(new ProductSourceProofs.PayableTerms(MERCHANT, "EUR", true, false));
        ProductSourceProofs proofs = new ProductSourceProofs(
                snapshot -> { throw new AssertionError("lending is not read"); },
                snapshot -> { throw new AssertionError("capital is not read"); },
                snapshot -> { throw new AssertionError("bnpl is not read"); },
                snapshot -> { throw new AssertionError("fx is not read"); },
                snapshot -> { throw new AssertionError("position is not read"); },
                snapshot -> terms,
                snapshot -> { throw new AssertionError("projections are not read"); });

        List<SourceProofs.Reading> readings = proofs.read(snapshot(), SourceProofs.Source.MERCHANT_PAYABLE);

        assertThat(readings).extracting(SourceProofs.Reading::proof)
                .containsExactly(SourceProofs.Proof.MERCHANT_PAYABLE, SourceProofs.Proof.PAYABLE_CHECKPOINT);
        assertThat(readings.get(1).discrepancies()).singleElement()
                .satisfies(discrepancy -> assertThat(discrepancy.subject()).isEqualTo(MERCHANT + ":EUR"));
    }
}
