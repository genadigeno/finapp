package com.finapp.merchant;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link MerchantApiKey}. Typed (ADR-0013), UUIDv7 by construction.
 *
 * <p><strong>Public, deliberately, and that is what makes it the lookup prefix.</strong> The
 * presented credential is {@code <keyId>.<secret>} (ADR-0052's prefix rule): the id selects
 * one row by primary key and the secret is then verified against that row's hash, so
 * authentication never scans hashes. One public identifier doing both jobs rather than a
 * second opaque prefix column — and it is the value an audit record names when a key is
 * issued or revoked, because naming the key without naming the secret is the entire point.
 *
 * <p>A UUIDv7 discloses its creation time; for a key that is not a secret and the row records
 * {@code issued_at} anyway. The <em>secret</em> is {@link MerchantApiKeySecret}, which is not
 * a UUID for exactly the reason {@code SessionToken} is not: an identifier carries structure,
 * and a secret must carry none.
 */
public final class MerchantApiKeyId extends EntityId {

    private MerchantApiKeyId(UUID value) {
        super(value);
    }

    public static MerchantApiKeyId next(IdGenerator ids) {
        return new MerchantApiKeyId(ids.next());
    }

    /** For values read back from storage, or parsed from a presented credential. */
    public static MerchantApiKeyId of(UUID value) {
        return new MerchantApiKeyId(value);
    }
}
