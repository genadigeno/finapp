/**
 * The contract between the source-join census and each posting module's read port (`P15-TSK-035`; ADR-0117 decision 2, dated
 * note 2026-11-09). A module publishes one {@code JournalSources}: for each posting namespace it writes, the join from a
 * journal entry to the record that caused it, and that record's figures. {@code accounting}'s census compares them with the
 * entry's lines; {@code app} composes every module's port. {@code platform} because every posting module and {@code accounting}
 * may see it and none may see each other: nothing here reads a table.
 */
package com.finapp.platform.sourcejoin;
