package com.finapp.credit;

import java.util.Arrays;
import java.util.Objects;

/**
 * The bytes a provider actually sent, verbatim (`P10-TSK-005`): the evidence a credit record keeps
 * (`-006`, encrypted) so an answer can be explained and a dispute answered from what arrived
 * ({@code INV-HIST-02}). The {@code FxProvider.Evidence} shape.
 *
 * <p>{@code toString} renders the length alone - a credit report never reaches a log line.
 */
public record CreditEvidence(byte[] bytes) {

    public CreditEvidence {
        Objects.requireNonNull(bytes, "bytes");
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CreditEvidence that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "CreditEvidence[" + bytes.length + " bytes]";
    }
}
