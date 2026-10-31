package com.finapp.accounting.reporting;

import com.finapp.accounting.JournalReads;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The GL view of one journal entry (`P14-TSK-007`; {@code INV-ACC-02}): the entry and each of its lines with the GL
 * account it maps to under the version its posting month is read under - empty for a line the version does not map,
 * shown, never hidden.
 */
public record GlEntry(PinnedMapping mapping, JournalReads.Entry entry, List<MappedLine> lines) {

    public GlEntry {
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(entry, "entry");
        lines = List.copyOf(lines);
    }

    /** A line and its GL account code under the version, if a rule maps it. */
    public record MappedLine(JournalReads.Line line, Optional<String> glAccountCode) {

        public MappedLine {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(glAccountCode, "glAccountCode");
        }
    }
}
