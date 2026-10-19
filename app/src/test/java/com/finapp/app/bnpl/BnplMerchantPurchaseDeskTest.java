package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplPurchaseReads;
import com.finapp.bnpl.BnplPurchaseStatus;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.MerchantBnplPurchaseView;
import com.finapp.ledger.JournalEntryId;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantId;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The merchant's BNPL purchase read at its boundary, hermetic (`P12-TSK-014`; {@code INV-MER-01}, {@code INV-MER-03},
 * {@code INV-CRD-04}, ADR-0031): the answer's field set is closed and carries nothing of the customer's credit; the
 * financing's figures and pinned version answered exactly; the tenant the key's merchant, bound into the read; another
 * merchant's purchase, an unknown one and a malformed id one {@code 404 bnpl.NotFound}, byte for byte; and the read one
 * door's snapshot. The route over a real database is {@code BnplMerchantReadDatabaseTest}'s.
 */
@DisplayName("the merchant's BNPL purchase read at its boundary (P12-TSK-014)")
class BnplMerchantPurchaseDeskTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    /** Words naming a customer credit datum - none may be a member of the merchant's answer (INV-CRD-04). */
    private static final List<String> CREDIT_WORDS = List.of("party", "customer", "plan", "decision", "eligib", "reason",
            "score", "approved", "limit", "schedule", "instalment", "installment", "repayment", "balance", "principal",
            "interest", "late", "delinquen", "dpd", "agreement", "obligation", "wallet");

    @Test
    @DisplayName("the answer's field set is closed - the merchant's own facts, nothing of the customer's credit")
    void theViewIsClosed() {
        assertThat(Arrays.stream(BnplMerchantPurchaseDesk.BnplMerchantPurchaseView.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("a member added to the merchant's answer is a datum sent to a merchant (INV-CRD-04)")
                .containsExactly("purchaseId", "checkoutId", "orderId", "product", "status", "currency", "gross", "fee",
                        "net", "feeScheduleVersionId", "entryId", "financedAt", "refunded", "refunds");
        assertThat(Arrays.stream(BnplMerchantPurchaseDesk.BnplMerchantPurchaseRefundView.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("a refund's members are its own purchase-level figures (P12-TSK-021) - never an instalment or excess")
                .containsExactly("refundId", "amount", "feeReturned", "payableDebit", "appliedAt", "door", "reason");
    }

    @Test
    @DisplayName("the figures are the financing's, in exact decimal strings; the version the pin's; the entry F1's")
    void theViewIsTheFinancing() {
        MerchantBnplPurchaseView purchase = purchase(IDS.next(), 600_00, 24_17, 575_83);
        BnplMerchantPurchaseDesk.BnplMerchantPurchaseView view = BnplMerchantPurchaseDesk.view(purchase);
        assertThat(view).isEqualTo(new BnplMerchantPurchaseDesk.BnplMerchantPurchaseView(
                purchase.purchase().value().toString(), purchase.checkoutSession().toString(),
                purchase.order().toString(), "BNPL_PAY_IN_N", "FINANCED", "EUR", "600.00", "24.17", "575.83",
                purchase.feeScheduleVersion().toString(), purchase.financingEntry().value().toString(),
                "2026-10-18T09:00:00Z", "0.00", java.util.List.of()));
    }

    @Test
    @DisplayName("a purchase with a refund reads PARTIALLY_REFUNDED - derived, never stored - with the refund's own figures"
            + " and the merchant's own reason (P12-TSK-021, -014's carry)")
    void aRefundedPurchaseReadsPartiallyRefunded() {
        MerchantBnplPurchaseView purchase = purchase(IDS.next(), 600_00, 24_00, 576_00).withRefunds(java.util.List.of(
                new com.finapp.bnpl.MerchantBnplRefundView(IDS.next(), Money.ofMinorUnits(180_00, EUR),
                        Money.ofMinorUnits(7_20, EUR), Money.ofMinorUnits(172_80, EUR),
                        java.time.Instant.parse("2026-10-20T10:00:00Z"), com.finapp.bnpl.BnplRefund.Door.MERCHANT,
                        java.util.Optional.of("returned goods"))));
        BnplMerchantPurchaseDesk.BnplMerchantPurchaseView view = BnplMerchantPurchaseDesk.view(purchase);
        assertThat(view.status()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(view.refunded()).isEqualTo("180.00");
        assertThat(view.refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.payableDebit()).isEqualTo("172.80");
            assertThat(refund.feeReturned()).isEqualTo("7.20");
            assertThat(refund.reason()).isEqualTo("returned goods");
        });
    }

    @Test
    @DisplayName("the needle walk: the serialised answer has no member naming a credit datum, and no value but the"
            + " merchant's own facts")
    void noCreditDataReachesTheMerchant() throws Exception {
        UUID merchant = IDS.next();
        MerchantBnplPurchaseView purchase = purchase(merchant, 600_00, 24_00, 576_00);
        World world = new World();
        world.rows.put(purchase.purchase().value(), new Row(merchant, purchase));
        String body = JsonMapper.builder().build().writeValueAsString(
                world.desk().read(key(merchant), purchase.purchase().value().toString()));

        JsonNode answer = JsonMapper.builder().build().readTree(body);
        List<String> members = new ArrayList<>();
        answer.propertyNames().forEach(members::add);
        assertThat(members).hasSize(14).allSatisfy(member -> assertThat(CREDIT_WORDS)
                .as("member %s names a customer credit datum", member)
                // The merchant's own fee schedule is the merchant's datum, not the customer's schedule.
                .noneMatch(word -> member.toLowerCase(Locale.ROOT).replace("feeschedule", "").contains(word)));
        assertThat(body).doesNotContain(merchant.toString())
                .as("the tenant is the key's, never echoed");
    }

    @Test
    @DisplayName("INV-MER-01: the key's merchant is the tenant bound into the read; another merchant's purchase, an"
            + " unknown one and a malformed id are one 404 bnpl.NotFound, identical - and the malformed id reads nothing")
    void anotherMerchantsPurchaseIsTheUniformNotFound() {
        UUID owner = IDS.next();
        MerchantBnplPurchaseView purchase = purchase(owner, 600_00, 24_00, 576_00);
        World world = new World();
        world.rows.put(purchase.purchase().value(), new Row(owner, purchase));
        BnplMerchantPurchaseDesk desk = world.desk();
        String id = purchase.purchase().value().toString();

        assertThat(desk.read(key(owner), id).net()).isEqualTo("576.00");
        assertThat(world.tenants).containsExactly(owner);

        UUID stranger = IDS.next();
        List<ApiException> refusals = new ArrayList<>();
        for (String asked : List.of(id, IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            assertThatThrownBy(() -> desk.read(key(stranger), asked)).isInstanceOfSatisfying(ApiException.class,
                    refusals::add);
        }
        assertThat(refusals).allSatisfy(refusal -> {
            assertThat(refusal.errorCode()).isEqualTo(BnplErrorCode.NOT_FOUND);
            assertThat(refusal.getMessage()).isEqualTo(refusals.get(0).getMessage());
            assertThat(refusal.clientDetail()).isEqualTo(refusals.get(0).clientDetail());
        });
        assertThat(world.tenants).as("the stranger's two well-formed asks bound the stranger; a UUID that is no v7 id and a malformed one read nothing")
                .containsExactly(owner, stranger, stranger);
    }

    @Test
    @DisplayName("the read runs inside the door's one snapshot - never a statement outside it")
    void theReadIsInsideTheSnapshot() {
        UUID merchant = IDS.next();
        MerchantBnplPurchaseView purchase = purchase(merchant, 600_00, 24_00, 576_00);
        World world = new World();
        world.rows.put(purchase.purchase().value(), new Row(merchant, purchase));
        world.desk().read(key(merchant), purchase.purchase().value().toString());
        assertThat(world.snapshots).isEqualTo(1);
        assertThat(world.outside).as("a statement ran outside the snapshot").isZero();
    }

    // ------------------------------------------------------------------ the world

    private record Row(UUID merchant, MerchantBnplPurchaseView purchase) {}

    /** bnpl's store as its statement answers - merchant_id = ? in it - inside a counting, connection-less snapshot. */
    private static final class World {

        final Map<UUID, Row> rows = new ConcurrentHashMap<>();
        final List<UUID> tenants = new ArrayList<>();
        int snapshots;
        int outside;
        private boolean inside;

        BnplMerchantPurchaseDesk desk() {
            BnplPurchaseStore store = proxy(BnplPurchaseStore.class, (method, args) -> {
                if (!method.getName().equals("atMerchant")) {
                    throw new AssertionError("the merchant's read runs one statement: " + method.getName());
                }
                if (!inside) {
                    outside++;
                }
                BnplPurchaseId id = (BnplPurchaseId) args[1];
                UUID merchant = (UUID) args[2];
                tenants.add(merchant);
                return Optional.ofNullable(rows.get(id.value())).filter(row -> row.merchant().equals(merchant))
                        .map(Row::purchase);
            });
            Connection connection = proxy(Connection.class, (method, args) -> null);
            return new BnplMerchantPurchaseDesk(new BnplPurchaseReads(store), new BnplMerchantPurchaseDesk.Snapshot() {
                @Override
                public <R> R inSnapshot(java.util.function.Function<Connection, R> work) {
                    snapshots++;
                    inside = true;
                    try {
                        return work.apply(connection);
                    } finally {
                        inside = false;
                    }
                }
            });
        }
    }

    /** A handler for an interface's own methods; {@code Object}'s answered by identity. */
    @FunctionalInterface
    private interface Handler {
        Object handle(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(BnplMerchantPurchaseDeskTest.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> type.getSimpleName() + "@fake";
                    default -> handler.handle(method, args);
                }));
    }

    private static AuthenticatedMerchant key(UUID merchant) {
        return new AuthenticatedMerchant(MerchantId.of(merchant), MerchantApiKeyId.of(IDS.next()));
    }

    private static MerchantBnplPurchaseView purchase(UUID merchant, long gross, long fee, long net) {
        return new MerchantBnplPurchaseView(BnplPurchaseId.next(IDS), IDS.next(), IDS.next(),
                BnplProduct.BNPL_PAY_IN_N, BnplPurchaseStatus.FINANCED, eur(gross), eur(fee), eur(net),
                IDS.next(), JournalEntryId.of(IDS.next()), Instant.parse("2026-10-18T09:00:00Z"));
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
