package com.finapp.platform.abuse;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Turns the address of the connection into a {@link WindowCounter} key from a bounded set (`P15-TSK-022`, {@code THREAT_MODEL.md}
 * T1, T40).
 *
 * <p><strong>Which address.</strong> The caller's only input is the one the container reports, which is the connection's, or the
 * one the edge wrote for a connection the edge made ({@code ForwardedAddressTest}). This class never sees a header.
 *
 * <p><strong>What is one source.</strong> An IPv4 address, whole. An IPv6 address by its /64, because one host commonly owns a whole
 * /64 and keying on the full address would give it 2^64 budgets; the edge groups on the same /64. An IPv4-mapped IPv6 address is
 * the IPv4 address it names, or the same client would have two budgets.
 *
 * <p><strong>Why a bucket and not the address.</strong> A row per source per window is sized by the attacker: an IPv6 /32 holds 2^32
 * sources. So the key is 16 bits of a hash: at most {@value #BUCKETS} rows per door per window, whatever arrives, and the table
 * holds no address. Two sources in a bucket share a limit, which at ten thousand sources in a window is about one source in seven
 * sharing with one other. The hash is not keyed: someone holding enough addresses can compute one that shares a known victim's
 * bucket and spend that bucket for a window. It needs the victim's address and buys one window.
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

    private static final int IPV6_PREFIX_BYTES = 8;

    private static final Pattern DOTTED_QUAD = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private SourceBucket() {}

    /**
     * The counter key for an address the container reported.
     *
     * @param remoteAddress {@code request.getRemoteAddr()}: anything else is not this class's contract
     * @return a key from a bounded set, for this address's source
     */
    public static String keyOf(String remoteAddress) {
        byte[] source = sourceOf(remoteAddress);
        if (source == null) {
            return UNPARSEABLE;
        }
        return String.format("%04x", bucket(source));
    }

    private static byte[] sourceOf(String remoteAddress) {
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
            byte[] address = InetAddress.getByName("[" + literal + "]").getAddress();
            return address.length == IPV6_PREFIX_BYTES * 2 ? Arrays.copyOf(address, IPV6_PREFIX_BYTES) : address;
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

    private static int bucket(byte[] source) {
        byte[] digest = sha256().digest(source);
        return ((digest[0] & 0xff) << 8) | (digest[1] & 0xff);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
