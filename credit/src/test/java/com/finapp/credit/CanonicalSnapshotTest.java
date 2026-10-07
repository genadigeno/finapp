package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical snapshot, format 1 (`P10-TSK-008`, {@code INV-CRD-07}): the same content always yields the same bytes
 * and hash, whatever order it was assembled in and whatever the JVM's locale, time zone or charset; absence is explicit;
 * the form reads back exactly; and a read of a code the content does not hold is an error.
 */
@DisplayName("the canonical decision snapshot, format 1 (P10-TSK-008)")
class CanonicalSnapshotTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID REQUEST = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID PARTY = UUID.fromString("0190a1b2-0000-7000-8000-00000000000b");
    private static final PinnedVersions VERSIONS = new PinnedVersions(
            UUID.fromString("0190a1b2-0000-7000-8000-00000000000c"), UUID.fromString("0190a1b2-0000-7000-8000-00000000000d"), 1);
    private static final CreditRecordId RECORD = CreditRecordId.of(UUID.fromString("0190a1b2-0000-7000-8000-00000000000e"));

    @Test
    @DisplayName("the golden snapshot renders exactly its golden bytes and hash")
    void theGoldenSnapshot() throws IOException {
        String canonical = CanonicalSnapshot.render(golden(attributes()));
        assertThat(canonical).isEqualTo(resource("credit/golden-snapshot-format-1.json").strip());
        assertThat(HexFormat.of().formatHex(CanonicalSnapshot.sha256(canonical)))
                .isEqualTo(resource("credit/golden-snapshot-format-1.sha256").strip());
    }

    @Test
    @DisplayName("any insertion order of the attributes yields identical bytes and hash")
    void insertionOrderNeverMatters() {
        String expected = CanonicalSnapshot.render(golden(attributes()));
        Random shuffles = new Random(42);
        for (int i = 0; i < 25; i++) {
            List<CreditAttribute> permuted = new ArrayList<>(attributes());
            Collections.shuffle(permuted, shuffles);
            assertThat(CanonicalSnapshot.render(golden(permuted))).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("another default locale, time zone and file encoding yield identical bytes")
    void theEnvironmentNeverMatters() {
        String expected = CanonicalSnapshot.render(golden(attributes()));
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        String encoding = System.getProperty("file.encoding");
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            System.setProperty("file.encoding", "UTF-16");
            assertThat(CanonicalSnapshot.render(golden(attributes()))).isEqualTo(expected);
            assertThat(CanonicalSnapshot.sha256(expected)).isEqualTo(CanonicalSnapshot.sha256(expected));
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
            System.setProperty("file.encoding", encoding);
        }
    }

    @Test
    @DisplayName("absence is explicit in the form, and the form reads back exactly")
    void absenceIsExplicitAndTheFormRoundTrips() {
        SnapshotContent content = golden(attributes());
        String canonical = CanonicalSnapshot.render(content);
        assertThat(canonical).contains("{\"code\":\"BUREAU_DEFAULTS_72M\",\"value\":{\"absent\":\"true\"}");
        SnapshotContent read = CanonicalSnapshot.parse(canonical);
        assertThat(CanonicalSnapshot.render(read)).isEqualTo(canonical);
        assertThat(read.attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE).value())
                .isEqualTo(new AttributeValue.MoneyValue(Money.ofMinorUnits(420_050, EUR)));
        assertThat(read.attribute(CreditAttributeCode.RISK_SIGNAL).provenance())
                .isEqualTo(new AttributeProvenance.Port("risk-signal", 1));
    }

    @Test
    @DisplayName("text the renderer would not write is refused - whitespace, an escape, a reordered key")
    void nonCanonicalTextIsRefused() {
        String canonical = CanonicalSnapshot.render(golden(attributes()));
        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalSnapshot.parse(canonical.replace(",\"party\"", ", \"party\"")));
        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalSnapshot.parse(canonical.replace("PERSONAL_LOAN", "PERSONAL\\u005fLOAN")));
        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalSnapshot.parse(
                canonical.replace("{\"minor\":\"420050\",\"currency\":\"EUR\",\"scale\":\"2\"}",
                        "{\"currency\":\"EUR\",\"minor\":\"420050\",\"scale\":\"2\"}")));
    }

    @Test
    @DisplayName("a read of an attribute the snapshot does not hold is an error, never a default (INV-CRD-07)")
    void aMissingAttributeReadIsAnErrorNeverADefault() {
        List<CreditAttribute> withoutIncome = new ArrayList<>(attributes());
        withoutIncome.removeIf(attribute -> attribute.code() == CreditAttributeCode.DECLARED_MONTHLY_INCOME);
        SnapshotContent content = golden(withoutIncome);
        assertThatExceptionOfType(MissingAttributeException.class)
                .isThrownBy(() -> content.attribute(CreditAttributeCode.DECLARED_MONTHLY_INCOME));
        assertThat(content.attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M).absent()).as("absent is held").isTrue();
    }

    @Test
    @DisplayName("a money attribute in another currency is refused by the content itself (INV-CRD-12)")
    void aForeignCurrencyIsRefused() {
        List<CreditAttribute> foreign = new ArrayList<>(attributes());
        foreign.removeIf(attribute -> attribute.code() == CreditAttributeCode.BUREAU_TOTAL_BALANCE);
        foreign.add(new CreditAttribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                new AttributeValue.MoneyValue(Money.ofMinorUnits(100, CurrencyCode.of("USD"))), record()));
        assertThatIllegalArgumentException().isThrownBy(() -> golden(foreign));
    }

    @Test
    @DisplayName("a source-kinds marker names its kinds in one canonical code, and reads them back")
    void theMarkerIsCanonical() {
        assertThat(SourceKindsMarker.of(java.util.Set.of(CreditSourceKind.FINANCIAL_DATA, CreditSourceKind.BUREAU)).value())
                .isEqualTo("BUREAU_AND_FINANCIAL_DATA");
        assertThat(SourceKindsMarker.kindsOf(new AttributeValue.CodeValue("FINANCIAL_DATA")))
                .containsExactly(CreditSourceKind.FINANCIAL_DATA);
        assertThatIllegalArgumentException().isThrownBy(() -> SourceKindsMarker.kindsOf(new AttributeValue.CodeValue("NOPE")));
    }

    // -----------------------------------------------------------------

    static SnapshotContent golden(List<CreditAttribute> attributes) {
        return new SnapshotContent(REQUEST, PARTY, CreditProduct.PERSONAL_LOAN, Money.ofMinorUnits(1_000_000, EUR),
                Optional.of(36), VERSIONS, attributes);
    }

    private static AttributeProvenance record() {
        return new AttributeProvenance.Record(RECORD, CreditSourceKind.BUREAU, "bureau-sim-a", 1);
    }

    /** Every code once - a representative mix of every value and provenance shape. */
    static List<CreditAttribute> attributes() {
        AttributeProvenance bureau = record();
        AttributeProvenance notRead = new AttributeProvenance.NotRead(CreditSourceKind.FINANCIAL_DATA);
        AttributeProvenance freezer = new AttributeProvenance.Port("snapshot-freezer", 1);
        List<CreditAttribute> all = new ArrayList<>();
        all.add(new CreditAttribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, new AttributeValue.IntegerValue(712), bureau));
        all.add(new CreditAttribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, new AttributeValue.IntegerValue(4), bureau));
        all.add(new CreditAttribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, new AttributeValue.IntegerValue(0), bureau));
        all.add(new CreditAttribute(CreditAttributeCode.BUREAU_DEFAULTS_72M, new AttributeValue.Absent(), bureau));
        all.add(new CreditAttribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, new AttributeValue.BooleanValue(false), bureau));
        all.add(new CreditAttribute(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
                new AttributeValue.MoneyValue(Money.ofMinorUnits(35_000, EUR)), bureau));
        all.add(new CreditAttribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                new AttributeValue.MoneyValue(Money.ofMinorUnits(420_050, EUR)), bureau));
        all.add(new CreditAttribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME, new AttributeValue.Absent(), notRead));
        all.add(new CreditAttribute(CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE, new AttributeValue.Absent(), notRead));
        all.add(new CreditAttribute(CreditAttributeCode.DECLARED_MONTHLY_INCOME,
                new AttributeValue.MoneyValue(Money.ofMinorUnits(320_000, EUR)), new AttributeProvenance.Declared()));
        all.add(new CreditAttribute(CreditAttributeCode.DECLARED_MONTHLY_EXPENDITURE,
                new AttributeValue.MoneyValue(Money.ofMinorUnits(140_000, EUR)), new AttributeProvenance.Declared()));
        all.add(new CreditAttribute(CreditAttributeCode.PARTY_AGE_YEARS, new AttributeValue.Absent(),
                new AttributeProvenance.Port("party-facts", 1)));
        all.add(new CreditAttribute(CreditAttributeCode.PARTY_RESIDENCY_COUNTRY, new AttributeValue.Absent(),
                new AttributeProvenance.Port("party-facts", 1)));
        all.add(new CreditAttribute(CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT,
                new AttributeValue.MoneyValue(Money.ofMinorUnits(0, EUR)), new AttributeProvenance.Port("platform-exposure", 1)));
        all.add(new CreditAttribute(CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE,
                new AttributeValue.MoneyValue(Money.ofMinorUnits(0, EUR)), new AttributeProvenance.Port("reserved-exposure", 1)));
        all.add(new CreditAttribute(CreditAttributeCode.RISK_SIGNAL, new AttributeValue.CodeValue("NOT_ASSESSED"),
                new AttributeProvenance.Port("risk-signal", 1)));
        all.add(new CreditAttribute(CreditAttributeCode.SOURCE_UNAVAILABLE, new AttributeValue.Absent(), freezer));
        all.add(new CreditAttribute(CreditAttributeCode.CURRENCY_NOT_SUPPORTED, new AttributeValue.Absent(), freezer));
        return all;
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = CanonicalSnapshotTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(stream).as(name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
