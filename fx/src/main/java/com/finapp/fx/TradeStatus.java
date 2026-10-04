package com.finapp.fx;

/** A trade's position (`P9-TSK-009`; the lifecycle document section 3.2). */
public enum TradeStatus {
    /** Booked with its entry - final but for the one four-eyes reversal. */
    BOOKED,
    /** Reversed by an approved operator reversal (`P9-TSK-025`). Terminal. */
    REVERSED
}
