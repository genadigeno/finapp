package com.finapp.app.abuse;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * The deployment-wide key of the per-source buckets (`P15-TSK-022`, {@code THREAT_MODEL.md} T1), read as
 * {@code finapp.abuse.bucket.key}.
 *
 * <p>Every replica holds the same value, or one source counts in a different row per replica. It is an HMAC key,
 * {@code AT_LEAST_32}. Its secrecy is what stops an attacker computing, offline, an address of their own that shares a named
 * victim's bucket. Rotating it reshuffles the buckets once: each source starts a fresh window in a new bucket, and while a
 * rollout has both keys on the fleet a source can count once per key.
 */
public final class SourceBucketKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "source bucket key",
                    "source bucket key",
                    "FINAPP_ABUSE_BUCKET_KEY",
                    "/source-bucket",
                    KeyLength.AT_LEAST_32,
                    ".");

    private SourceBucketKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used - false anywhere the database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
