package com.finapp.platform.audit.archive;

import java.util.Optional;

/**
 * The write-once storage the audit archive copies to (`P16-TSK-010`; {@code DATA_RETENTION.md} section 3; ADR-0135).
 *
 * <p><strong>Write once, never delete.</strong> The port has no overwrite and no delete: an object, once written at a key, is what
 * every later read of that key returns. A real bucket holds that by Object Lock and a policy refusing deletion
 * ({@code infra/terraform/modules/object-storage}); an adapter holds it by asking the store to write only where no object is.
 *
 * <p>Every method either answers or throws {@link AuditArchiveStoreException} within the adapter's own time bound. A store that is
 * down, slow past that bound or refusing costs a segment its turn and nothing else: no segment is marked until both its objects
 * have been read back.
 */
public interface AuditArchiveStore {

    /** What a write found. */
    enum Put {
        /** No object was at the key, and this one is now. */
        WRITTEN,
        /** An object was already at the key and was left alone: the caller reads it back to know what it is. */
        ALREADY_PRESENT
    }

    /**
     * Writes {@code bytes} at {@code key} if no object is there.
     *
     * @throws AuditArchiveStoreException if the store did not answer, or answered anything but a write or "already there"
     */
    Put putIfAbsent(String key, byte[] bytes);

    /**
     * The object at {@code key}, or empty if none is there. At most {@code maxBytes + 1} bytes are read: an object longer than
     * the bound comes back truncated to one byte past it, so it can never compare equal to what was expected.
     *
     * @throws AuditArchiveStoreException if the store did not answer, or answered anything but the object or "not found"
     */
    Optional<byte[]> get(String key, int maxBytes);
}
