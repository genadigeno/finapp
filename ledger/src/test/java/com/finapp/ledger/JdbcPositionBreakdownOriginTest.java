package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link JdbcPositionBreakdown} does with each origin its statement hands back (`P14-TSK-022`'s review, M-3): the real fold over stood-in rows, so a name this build does not know is proven to read as no origin and never to throw. An old instance can meet a later release's origin under a rolling
 * deploy, on {@code GET /v1/merchant/payable} and the {@code MERCHANT_PAYABLE} and {@code BNPL_THREE_WAY} proofs. That the statement's own {@code p.origin} is what PostgreSQL returns is {@code PriorPeriodAdjustmentDatabaseTest#whatAStatementShowsOfIt}'s, owed.
 */
@DisplayName("a merchant's breakdown over each origin the statement can hand back (P14-TSK-022, P14-TSK-023)")
class JdbcPositionBreakdownOriginTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String FACING_A_WALLET = "CUSTOMER_WALLET|DEBIT";

    /** One line as the statement returns it, on a CREDIT-normal payable. */
    private static Map<String, Object> line(String direction, long minor, String origin, String counterparty, String namespace) {
        Map<String, Object> row = new HashMap<>();
        row.put("direction", direction);
        row.put("amount_minor", minor);
        row.put("currency", "EUR");
        row.put("scale", (short) 2);
        row.put("origin", origin);
        row.put("counterparty", counterparty);
        row.put("namespace", namespace);
        return row;
    }

    /** A connection answering the account's read, then the lines' read, in that order: the real class's two statements. */
    private static Connection answering(List<Map<String, Object>> lines) {
        Map<String, Object> account = new HashMap<>();
        account.put("currency", "EUR");
        account.put("normal_balance", "CREDIT");
        Deque<List<Map<String, Object>>> reads = new ArrayDeque<>(List.of(List.of(account), lines));
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statementAnswering(reads.removeFirst());
            case "createArrayOf" -> Proxy.newProxyInstance(Array.class.getClassLoader(), new Class<?>[] {Array.class}, (array, called, given) -> null);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static PreparedStatement statementAnswering(List<Map<String, Object>> rows) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> method.getName().equals("executeQuery") ? rowsOf(rows) : null);
    }

    private static ResultSet rowsOf(List<Map<String, Object>> rows) {
        Iterator<Map<String, Object>> cursor = rows.iterator();
        Object[] current = new Object[1];
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "next" -> {
                    if (!cursor.hasNext()) {
                        return false;
                    }
                    current[0] = cursor.next();
                    return true;
                }
                case "getString", "getLong", "getShort" -> {
                    return ((Map<?, ?>) current[0]).get(args[0]);
                }
                case "close" -> {
                    return null;
                }
                default -> throw new UnsupportedOperationException(method.getName() + Arrays.toString(args));
            }
        });
    }

    private static PositionBreakdown.Breakdown breakdownOf(List<Map<String, Object>> lines) {
        return new JdbcPositionBreakdown().breakdown(answering(lines), LedgerAccountId.next(IDS), List.of(AccountPurpose.CUSTOMER_WALLET), List.of("bnpl.refund:"));
    }

    private static Money signedSumOf(PositionBreakdown.Breakdown breakdown) {
        Money sum = Money.ofPersisted(0, EUR, 2);
        for (PositionBreakdown.Bucket bucket : breakdown.buckets()) {
            sum = bucket.direction() == Direction.CREDIT ? sum.plus(bucket.total()) : sum.minus(bucket.total());
        }
        return sum;
    }

    @Test
    @DisplayName("an origin this build does not know reads as none, never a throw: the line reads by what it faces, keeps any key, and is folded once, so the buckets still sum to the position")
    void anOriginThisBuildDoesNotKnowReadsByItsCounterparty() {
        PositionBreakdown.Breakdown breakdown = breakdownOf(List.of(line("CREDIT", 1_500, "A_LATER_RELEASES_ORIGIN", FACING_A_WALLET, null), line("DEBIT", 400, "A_LATER_RELEASES_ORIGIN", FACING_A_WALLET, "bnpl.refund:")));

        assertThat(breakdown.buckets()).hasSize(2).allSatisfy(bucket -> {
            assertThat(bucket.reconciliationAttributed()).isFalse();
            assertThat(bucket.accountingOrigin()).isFalse();
            assertThat(bucket.counterparty()).contains(new PositionBreakdown.Counterparty(AccountPurpose.CUSTOMER_WALLET, Direction.DEBIT));
        });
        assertThat(breakdown.buckets()).anySatisfy(bucket -> assertThat(bucket.namespace()).contains("bnpl.refund:"));
        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(1_100, EUR, 2));
        assertThat(signedSumOf(breakdown)).isEqualTo(breakdown.position());
    }

    @Test
    @DisplayName("every origin it knows reads by its origin: ACCOUNTING and RECONCILIATION each under its flag, MANUAL under neither, all three facing nothing; an entry no proposal posted, a NULL from the statement, by what it faces; one bucket per reading, the sum holding")
    void everyKnownOriginKeepsItsRule() {
        PositionBreakdown.Breakdown breakdown = breakdownOf(List.of(line("CREDIT", 100, "ACCOUNTING", FACING_A_WALLET, null), line("CREDIT", 20, "RECONCILIATION", FACING_A_WALLET, null), line("CREDIT", 3, "MANUAL", FACING_A_WALLET, null), line("CREDIT", 4, null, FACING_A_WALLET, null)));

        assertThat(breakdown.buckets()).hasSize(4);
        assertThat(breakdown.buckets()).filteredOn(PositionBreakdown.Bucket::accountingOrigin).singleElement().satisfies(bucket -> {
            assertThat(bucket.counterparty()).isEmpty();
            assertThat(bucket.total()).isEqualTo(Money.ofPersisted(100, EUR, 2));
        });
        assertThat(breakdown.buckets()).filteredOn(PositionBreakdown.Bucket::reconciliationAttributed).singleElement().satisfies(bucket -> {
            assertThat(bucket.counterparty()).isEmpty();
            assertThat(bucket.total()).isEqualTo(Money.ofPersisted(20, EUR, 2));
        });
        assertThat(breakdown.buckets()).filteredOn(bucket -> bucket.counterparty().isPresent()).singleElement().satisfies(bucket -> assertThat(bucket.total()).as("no proposal alone").isEqualTo(Money.ofPersisted(4, EUR, 2)));
        assertThat(breakdown.buckets()).filteredOn(bucket -> bucket.counterparty().isEmpty() && !bucket.accountingOrigin() && !bucket.reconciliationAttributed()).singleElement().satisfies(bucket -> assertThat(bucket.total()).as("MANUAL").isEqualTo(Money.ofPersisted(3, EUR, 2)));
        assertThat(signedSumOf(breakdown)).isEqualTo(breakdown.position());
    }

    @Test
    @DisplayName("P14-TSK-023 (ac): a MANUAL line through the real fold, each direction, facing a wallet: no counterparty, no key, one bucket with a posting that faces nothing, folded once, so the sum holds and the term is the caller's other")
    void aManualLineFacesNothing() {
        PositionBreakdown.Breakdown breakdown = breakdownOf(List.of(line("CREDIT", 1_500, "MANUAL", FACING_A_WALLET, null), line("DEBIT", 400, "MANUAL", "CUSTOMER_WALLET|CREDIT", null), line("CREDIT", 7, null, null, null)));

        assertThat(breakdown.buckets()).hasSize(2).allSatisfy(bucket -> {
            assertThat(bucket.counterparty()).isEmpty();
            assertThat(bucket.namespace()).isEmpty();
            assertThat(bucket.accountingOrigin()).isFalse();
            assertThat(bucket.reconciliationAttributed()).isFalse();
        });
        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(1_107, EUR, 2));
        assertThat(signedSumOf(breakdown)).isEqualTo(breakdown.position());
    }

    @Test
    @DisplayName("P14-TSK-023 (ac), walked: a reversal's entry no proposal posted, so the statement's scalar subquery returns NULL and its line reads by what it faces beside the MANUAL line it reverses, the sum holding at zero")
    void aReversalOfAManualLineHasNoOrigin() {
        PositionBreakdown.Breakdown breakdown = breakdownOf(List.of(line("CREDIT", 1_500, "MANUAL", FACING_A_WALLET, null), line("DEBIT", 1_500, null, "CUSTOMER_WALLET|CREDIT", null)));

        assertThat(breakdown.buckets()).filteredOn(bucket -> bucket.direction() == Direction.DEBIT).singleElement().satisfies(bucket -> assertThat(bucket.counterparty()).contains(new PositionBreakdown.Counterparty(AccountPurpose.CUSTOMER_WALLET, Direction.CREDIT)));
        assertThat(breakdown.buckets()).filteredOn(bucket -> bucket.direction() == Direction.CREDIT).singleElement().satisfies(bucket -> assertThat(bucket.counterparty()).isEmpty());
        assertThat(breakdown.position()).isEqualTo(Money.ofPersisted(0, EUR, 2));
        assertThat(signedSumOf(breakdown)).isEqualTo(breakdown.position());
    }

    @Test
    @DisplayName("the name is resolved, not parsed: each known name its origin, anything else empty")
    void theNameIsResolved() {
        for (AdjustmentOrigin origin : AdjustmentOrigin.values()) {
            assertThat(AdjustmentOrigin.named(origin.name())).contains(origin);
        }
        assertThat(AdjustmentOrigin.named("A_LATER_RELEASES_ORIGIN")).isEmpty();
        assertThat(AdjustmentOrigin.named("manual")).isEmpty();
        assertThat(Optional.ofNullable((String) null).flatMap(AdjustmentOrigin::named)).isEmpty();
    }
}
