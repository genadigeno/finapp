package com.finapp.settlement;

/**
 * The settlement file's machine (`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.1) — of
 * which only birth exists yet.
 *
 * <p>`P8-TSK-002` receives files; nothing parses, declines or accepts them, so the only status
 * a row can hold is {@code RECEIVED}, and `V002`'s generated {@code CHECK} and transition
 * trigger admit exactly that: no edge, because no producer of an edge exists. {@code PARSED}
 * and {@code REJECTED} join with the parse leg (`P8-TSK-008`), {@code ACCEPTED} with the
 * accept leg (`P8-TSK-009`) — each regenerating the constraint with the edges its producer
 * brings, the three-layer discipline's rule that a table cannot honestly precede its machine.
 */
public enum FileStatus {
    RECEIVED
}
