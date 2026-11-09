package com.finapp.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What is one source, and that the key comes from a bounded set (`P15-TSK-022`, {@code THREAT_MODEL.md} T1, T40). */
@DisplayName("what one source is, and the bounded key it maps to (P15-TSK-022)")
class SourceBucketTest {

    @Test
    @DisplayName("an IPv6 host's /64 is one source: a host owns its whole /64, so the full address would be a limit it does not feel")
    void anIpv6SlashSixtyFourIsOneSource() {
        assertThat(SourceBucket.keyOf("2001:db8:1:2:3:4:5:6")).isEqualTo(SourceBucket.keyOf("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        assertThat(SourceBucket.keyOf("2001:db8:1:2::")).isEqualTo(SourceBucket.keyOf("2001:db8:1:2:3:4:5:6"));
    }

    @Test
    @DisplayName("the next /64 is another source")
    void theNextSlashSixtyFourIsAnotherSource() {
        // Fixed inputs that do not share a bucket: the hash is SHA-256, so this holds for these addresses for ever.
        assertThat(SourceBucket.keyOf("2001:db8:1:2::")).isNotEqualTo(SourceBucket.keyOf("2001:db8:1:3::"));
    }

    @Test
    @DisplayName("an IPv4-mapped IPv6 address is the IPv4 address it names")
    void anIpv4MappedAddressIsTheIpv4Address() {
        assertThat(SourceBucket.keyOf("::ffff:192.0.2.10")).isEqualTo(SourceBucket.keyOf("192.0.2.10"));
        assertThat(SourceBucket.keyOf("::ffff:c000:20a")).isEqualTo(SourceBucket.keyOf("192.0.2.10"));
    }

    @Test
    @DisplayName("an IPv4 address is whole: its neighbour is another source")
    void anIpv4AddressIsWhole() {
        assertThat(SourceBucket.keyOf("192.0.2.10")).isNotEqualTo(SourceBucket.keyOf("192.0.2.11"));
    }

    @Test
    @DisplayName("a scope id on a link-local address is not part of the source")
    void aZoneIsDropped() {
        assertThat(SourceBucket.keyOf("fe80:0:0:0:1:2:3:4%eth0")).isEqualTo(SourceBucket.keyOf("fe80::1:2:3:4"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "unknown", "abc", "localhost", "evil.example", "999.1.1.1", "1.2.3", "1.2.3.4.5", "1.2.3.4:443", ":::", "2001:db8::g", "not:an:address"
    })
    @DisplayName("anything that is not an address shares one key, and none of it is ever resolved")
    void whatDoesNotParseSharesOneKey(String notAnAddress) {
        assertThat(SourceBucket.keyOf(notAnAddress)).isEqualTo(SourceBucket.UNPARSEABLE);
    }

    @Test
    @DisplayName("an absent or absurdly long value is that one key too")
    void absentOrLongIsUnparseable() {
        assertThat(SourceBucket.keyOf(null)).isEqualTo(SourceBucket.UNPARSEABLE);
        assertThat(SourceBucket.keyOf("1".repeat(65))).isEqualTo(SourceBucket.UNPARSEABLE);
    }

    @Test
    @DisplayName("the key is at most four hex digits, so one door's window holds at most 65,537 rows whatever arrives")
    void theKeySpaceIsBounded() {
        Set<String> keys = new HashSet<>();
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                String key = SourceBucket.keyOf("10." + a + "." + b + ".1");
                assertThat(key).matches("[0-9a-f]{4}");
                keys.add(key);
            }
        }
        assertThat(keys).as("a good hash spreads 65,536 sources widely").hasSizeGreaterThan(40_000);
        assertThat(SourceBucket.BUCKETS).isEqualTo(1 << 16);
    }

    @Test
    @DisplayName("the key is the bytes' SHA-256, stable across instances and restarts: every replica names the same row")
    void theKeyIsStable() {
        assertThat(SourceBucket.keyOf("192.0.2.10")).isEqualTo(SourceBucket.keyOf("192.0.2.10")).isEqualTo("a58d");
        assertThat(SourceBucket.keyOf("2001:db8:1:2::1")).isEqualTo(SourceBucket.keyOf("2001:db8:1:2::1"));
    }
}
