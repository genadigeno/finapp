package com.finapp.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What is one source, and that the key comes from a bounded set (`P15-TSK-022`, {@code THREAT_MODEL.md} T1, T40). */
@DisplayName("what one source is, and the bounded, keyed bucket it maps to (P15-TSK-022)")
class SourceBucketTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final byte[] ANOTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    private final SourceBucket buckets = new SourceBucket(KEY);

    private String keyOf(String address) {
        return buckets.of(address).key();
    }

    private Optional<String> siteOf(String address) {
        return buckets.of(address).site();
    }

    @Test
    @DisplayName("an IPv6 host's /64 is one source: a host owns its whole /64, so the full address would be a limit it does not feel")
    void anIpv6SlashSixtyFourIsOneSource() {
        assertThat(keyOf("2001:db8:1:2:3:4:5:6")).isEqualTo(keyOf("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        assertThat(keyOf("2001:db8:1:2::")).isEqualTo(keyOf("2001:db8:1:2:3:4:5:6"));
    }

    @Test
    @DisplayName("the next /64 is another source")
    void theNextSlashSixtyFourIsAnotherSource() {
        // Fixed inputs under a fixed key that do not share a bucket.
        assertThat(keyOf("2001:db8:1:2::")).isNotEqualTo(keyOf("2001:db8:1:3::"));
    }

    @Test
    @DisplayName("an IPv6 /48 is one site, across every /64 in it, and the next /48 is another")
    void anIpv6SlashFortyEightIsOneSite() {
        assertThat(siteOf("2001:db8:1:2::1")).isPresent().isEqualTo(siteOf("2001:db8:1:ffff:1:2:3:4"));
        assertThat(siteOf("2001:db8:2:2::1")).isPresent().isNotEqualTo(siteOf("2001:db8:1:2::1"));
    }

    @Test
    @DisplayName("an IPv4 address, or what does not parse, has no site")
    void anIpv4AddressHasNoSite() {
        assertThat(siteOf("192.0.2.10")).isEmpty();
        assertThat(siteOf("::ffff:192.0.2.10")).as("an IPv4-mapped address is its IPv4 address").isEmpty();
        assertThat(siteOf("not an address")).isEmpty();
    }

    @Test
    @DisplayName("an IPv4-mapped IPv6 address is the IPv4 address it names")
    void anIpv4MappedAddressIsTheIpv4Address() {
        assertThat(keyOf("::ffff:192.0.2.10")).isEqualTo(keyOf("192.0.2.10"));
        assertThat(keyOf("::ffff:c000:20a")).isEqualTo(keyOf("192.0.2.10"));
    }

    @Test
    @DisplayName("an IPv4 address is whole: its neighbour is another source")
    void anIpv4AddressIsWhole() {
        assertThat(keyOf("192.0.2.10")).isNotEqualTo(keyOf("192.0.2.11"));
    }

    @Test
    @DisplayName("a scope id on a link-local address is not part of the source")
    void aZoneIsDropped() {
        assertThat(keyOf("fe80:0:0:0:1:2:3:4%eth0")).isEqualTo(keyOf("fe80::1:2:3:4"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "unknown", "abc", "localhost", "evil.example", "999.1.1.1", "1.2.3", "1.2.3.4.5", "1.2.3.4:443", ":::", "2001:db8::g", "not:an:address"
    })
    @DisplayName("anything that is not an address shares one key, and none of it is ever resolved")
    void whatDoesNotParseSharesOneKey(String notAnAddress) {
        assertThat(keyOf(notAnAddress)).isEqualTo(SourceBucket.UNPARSEABLE);
    }

    @Test
    @DisplayName("an absent or absurdly long value is that one key too")
    void absentOrLongIsUnparseable() {
        assertThat(keyOf(null)).isEqualTo(SourceBucket.UNPARSEABLE);
        assertThat(keyOf("1".repeat(65))).isEqualTo(SourceBucket.UNPARSEABLE);
    }

    @Test
    @DisplayName("the key is at most four hex digits, so one count's window holds at most 65,537 rows whatever arrives")
    void theKeySpaceIsBounded() {
        Set<String> keys = new HashSet<>();
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                String key = keyOf("10." + a + "." + b + ".1");
                assertThat(key).matches("[0-9a-f]{4}");
                keys.add(key);
            }
        }
        assertThat(keys).as("a good hash spreads 65,536 sources widely").hasSizeGreaterThan(40_000);
        assertThat(SourceBucket.BUCKETS).isEqualTo(1 << 16);
    }

    @Test
    @DisplayName("the bucket is the key's: without it, nobody can compute which of their addresses shares a victim's bucket")
    void theKeyDecidesTheBucket() {
        SourceBucket another = new SourceBucket(ANOTHER_KEY);
        int same = 0;
        for (int host = 0; host < 256; host++) {
            String address = "198.51.100." + host;
            if (buckets.of(address).key().equals(another.of(address).key())) {
                same++;
            }
        }
        assertThat(same).as("under another key the addresses land elsewhere").isLessThan(16);
    }

    @Test
    @DisplayName("the key is HMAC-SHA-256 under the deployment's key, stable across instances and restarts: every replica names the same row")
    void theKeyIsStable() {
        assertThat(keyOf("192.0.2.10")).isEqualTo(new SourceBucket(KEY).of("192.0.2.10").key()).isEqualTo("12f5");
        assertThat(keyOf("2001:db8:1:2::1")).isEqualTo(keyOf("2001:db8:1:2::1"));
    }

    @Test
    @DisplayName("a key shorter than the digest is refused")
    void aShortKeyIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceBucket(new byte[31]));
    }
}
