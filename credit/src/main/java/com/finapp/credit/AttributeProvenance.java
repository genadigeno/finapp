package com.finapp.credit;

import java.util.Objects;

/**
 * Where a credit attribute came from (`P10-TSK-005`, completed by `P10-TSK-008`; PHASE_10_PLAN.md section 12.2,
 * {@code INV-CRD-07}): every attribute carries it, so an explanation names the source of every figure - and of every
 * absence - and a replay reads the stored attribute rather than re-normalising it.
 */
public sealed interface AttributeProvenance
        permits AttributeProvenance.Provider,
                AttributeProvenance.Record,
                AttributeProvenance.Unavailable,
                AttributeProvenance.NotRead,
                AttributeProvenance.Declared,
                AttributeProvenance.Port {

    /**
     * Normalised from a provider's answer - an adapter's output, before it is stored.
     *
     * @param kind the source kind
     * @param providerCode the provider's code - its declaration's and its evidence's
     * @param normaliserVersion the adapter's normaliser version - a change to it never moves a past decision, because
     *     replay reads the stored attributes ({@code INV-CRD-01})
     */
    record Provider(CreditSourceKind kind, String providerCode, int normaliserVersion) implements AttributeProvenance {
        public Provider {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(providerCode, "providerCode");
            if (normaliserVersion < 1) {
                throw new IllegalArgumentException("a normaliser version counts from 1");
            }
        }
    }

    /** Read from a stored credit record at the freeze (`P10-TSK-008`) - the record that explains the figure. */
    record Record(CreditRecordId record, CreditSourceKind kind, String providerCode, int normaliserVersion)
            implements AttributeProvenance {
        public Record {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(providerCode, "providerCode");
            if (normaliserVersion < 1) {
                throw new IllegalArgumentException("a normaliser version counts from 1");
            }
        }
    }

    /** Absent because the source was unavailable past its deadline (`INV-CRD-10`) - the data request that ran out. */
    record Unavailable(CreditSourceKind kind, CreditDataRequestId dataRequest) implements AttributeProvenance {
        public Unavailable {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(dataRequest, "dataRequest");
        }
    }

    /** Absent because the pinned policy reads no source of this kind - nothing was asked. */
    record NotRead(CreditSourceKind kind) implements AttributeProvenance {
        public NotRead {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The applicant's own declaration, on the request. */
    record Declared() implements AttributeProvenance {}

    /**
     * Answered by a port the decision consults - party facts, the risk seam, reserved exposure - with its version, so a
     * decision made before the port's implementation changed replays identically after it ({@code INV-CRD-01}).
     *
     * @param port the port's stable name
     * @param version the answering implementation's version
     */
    record Port(String port, int version) implements AttributeProvenance {
        public Port {
            Objects.requireNonNull(port, "port");
            if (!port.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("a port name is a lowercase token");
            }
            if (version < 1) {
                throw new IllegalArgumentException("a port version counts from 1");
            }
        }
    }
}
