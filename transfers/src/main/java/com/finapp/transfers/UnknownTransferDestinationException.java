package com.finapp.transfers;

import java.io.Serial;

/**
 * The named destination resolves to no wallet at all — a boundary mistake (an identifier that
 * names nothing), never a committed outcome. A destination that <em>exists</em> and cannot
 * post is different and does commit: {@code FAILED(DESTINATION_NOT_POSTABLE)}, because the
 * caller named something real and the domain judged it (`P4-TSK-005`'s design).
 */
public final class UnknownTransferDestinationException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public UnknownTransferDestinationException() {
        super("no such destination product holds a wallet to transfer to");
    }
}
