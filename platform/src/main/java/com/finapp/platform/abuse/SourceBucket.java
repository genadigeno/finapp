package com.finapp.platform.abuse;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns the address of the connection into {@link WindowCounter} keys from a bounded set (`P15-TSK-022`, {@code THREAT_MODEL.md}
 * T1, T40).
 *
 * <p><strong>Which address.</strong> The caller's only input is the one the container reports, which is the connection's, or the
 * one the edge wrote for a connection the edge made ({@code ForwardedAddressTest}). This class never sees a header.
 *
 * <p><strong>What is one source.</strong> An IPv4 address, whole. An IPv6 address by its /64, because one host commonly owns a whole
 * /64 and keying on the full address would give it 2^64 budgets; the edge groups on the same /64. An IPv4-mapped IPv6 address is
 * the IPv4 address it names, or the same client would have two budgets.
 *
 * <p><strong>What is one site.</strong> An IPv6 /48, the block a holder commonly gets whole. A /64 is a cheap thing to have 65,536 of,
 * so a limit per /64 alone is one a /48's holder multiplies by tens of thousands. An IPv4 address has no site: it is scarce already.
 *
 * <p><strong>Why a bucket and not the address.</strong> A row per source per window is sized by the attacker: an IPv6 /32 holds 2^32
 * sources. So a key is 16 bits of a keyed hash: at most {@value #BUCKETS} rows per count per window, whatever arrives, and the table
 * holds no address. Two sources in a bucket share a limit, which at ten thousand sources in a window is about one source in seven
 * sharing with one other.
 *
 * <p><strong>Why it is keyed.</strong> An unkeyed hash can be computed offline. A /48 holds a /64 in any given bucket about 63% of the
 * time, so an attacker who knows a victim's address could spend that victim's bucket from an address of their own, with no valid
 * body, and do it again at every window's predictable start: a sustained lockout of a named source. HMAC-SHA-256 under a
 * deployment-wide key removes the computation. Every replica holds the same key, or a source would count in a different row per
 * replica. Rotating it reshuffles the buckets once: each source starts a fresh window in a new bucket.
 *
 * <p><strong>Anything that does not parse</strong> is one key, not a key of its own, so nothing can mint keys by being malformed.
 *
 * <p>The literal is parsed without name resolution: an address that is not an address must never become a DNS query on an
 * unauthenticated path.
 */
public final class SourceBucket {

    /** How many keys a real address maps to. {@code UNPARSEABLE} is one more. */
    public static final int BUCKETS = 65_536;

    /** The one key everything that is not an address shares. */
    static final String UNPARSEABLE = "unparseable";

    private static final String HMAC = "HmacSHA256";

    private static final int IPV6_LENGTH = 16;
    private static final int IPV6_SOURCE_BYTES = 8;
    private static final int IPV6_SITE_BYTES = 6;

    /** Separates what is hashed, so a /64 and a /48 with the same leading bytes are never one input. */
    private static final byte SOURCE_DOMAIN = 1;
    private static final byte SITE_DOMAIN = 2;

    private static final Pattern DOTTED_QUAD = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private final SecretKeySpec key;

    /** @param key the deployment-wide key, at least 32 bytes */
    public SourceBucket(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < 32) {
            throw new IllegalArgumentException("the source bucket key must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(key, HMAC);
    }

    /**
     * What one request counts against.
     *
     * @param key this request's source
     * @param site its IPv6 /48, or empty for an IPv4 address and anything that did not parse
     */
    public record Source(String key, Optional<String> site) {}

    /**
     * The counter keys for an address the container reported.
     *
     * @param remoteAddress {@code request.getRemoteAddr()}: anything else is not this class's contract
     */
    public Source of(String remoteAddress) {
        byte[] address = addressOf(remoteAddress);
        if (address == null) {
            return new Source(UNPARSEABLE, Optional.empty());
        }
        if (address.length != IPV6_LENGTH) {
            return new Source(bucket(SOURCE_DOMAIN, address), Optional.empty());
        }
        return new Source(
                bucket(SOURCE_DOMAIN, Arrays.copyOf(address, IPV6_SOURCE_BYTES)),
                Optional.of(bucket(SITE_DOMAIN, Arrays.copyOf(address, IPV6_SITE_BYTES))));
    }

    private static byte[] addressOf(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isEmpty() || remoteAddress.length() > 64) {
            return null;
        }
        int zone = remoteAddress.indexOf('%');
        String literal = zone < 0 ? remoteAddress : remoteAddress.substring(0, zone);
        if (literal.indexOf(':') >= 0) {
            return ipv6(literal);
        }
        return ipv4(literal);
    }

    private static byte[] ipv6(String literal) {
        try {
            // The brackets make it a literal to InetAddress: without them a string that is not one can go to the resolver.
            return InetAddress.getByName("[" + literal + "]").getAddress();
        } catch (UnknownHostException notAnAddress) {
            return null;
        }
    }

    private static byte[] ipv4(String literal) {
        if (!DOTTED_QUAD.matcher(literal).matches()) {
            return null;
        }
        String[] octets = literal.split("\\.");
        byte[] address = new byte[octets.length];
        for (int i = 0; i < octets.length; i++) {
            int octet = Integer.parseInt(octets[i]);
            if (octet > 255) {
                return null;
            }
            address[i] = (byte) octet;
        }
        return address;
    }

    private String bucket(byte domain, byte[] bytes) {
        byte[] digest = hmac(domain, bytes);
        return String.format("%04x", ((digest[0] & 0xff) << 8) | (digest[1] & 0xff));
    }

    private byte[] hmac(byte domain, byte[] bytes) {
        try {
            // A Mac is not safe across threads and this is a request path: one per call.
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update(domain);
            return mac.doFinal(bytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("HMAC-SHA-256 is required of every JVM", impossible);
        }
    }
}
