package com.finapp.app.crossborder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The corridor policy's version 1 (`P9-TSK-015`; ADR-0080 section 4, owner decision O7) - the exact body
 * two controllers propose and activate through {@code POST /v1/operator/cross-border/corridor-policies}
 * and its approval (OPERATIONS_RUNBOOK), never a seed (D26): EUR -> USD/US, EUR -> JPY/JP, USD -> BHD/BH
 * and GBP -> USD/US on {@code corridor-sim-a}; transfer fees EUR 2.50 / GBP 2.00 / USD 3.00 + 0 bps
 * (rounded half-even); maxima USD 10,000.00 / JPY 1,500,000 / BHD 4,000.000; screening valid 7 days
 * (168 hours); delivery estimated at one day; the beneficiary's name and entity type required.
 */
public final class CorridorPolicyV1 {

    /** One O7 corridor: S, D, country, the fee in S, the maximum in D. */
    record Row(String source, String destination, String country, String fee, String maximum) {}

    static final List<Row> CORRIDORS = List.of(
            new Row("EUR", "USD", "US", "2.50", "10000.00"),
            new Row("EUR", "JPY", "JP", "2.50", "1500000"),
            new Row("USD", "BHD", "BH", "3.00", "4000.000"),
            new Row("GBP", "USD", "US", "2.00", "10000.00"));

    private CorridorPolicyV1() {}

    /** The proposal body with the proposer's reason. */
    public static String json(String reason) {
        return "{\"corridors\":[" + CORRIDORS.stream().map(CorridorPolicyV1::corridor).collect(Collectors.joining(","))
                + "],\"reason\":\"" + reason + "\"}";
    }

    static String corridor(Row row) {
        return "{\"source\":\"" + row.source() + "\",\"destination\":\"" + row.destination() + "\",\"country\":\""
                + row.country() + "\",\"rails\":[\"corridor-sim-a\"],\"feeFixed\":\"" + row.fee()
                + "\",\"feeMargin\":\"0\",\"feeRounding\":\"HALF_EVEN\",\"maximum\":\"" + row.maximum()
                + "\",\"screeningValidityHours\":168,\"deliveryEstimateHours\":24,"
                + "\"requiredData\":[\"BENEFICIARY_NAME\",\"ENTITY_TYPE\"]}";
    }
}
