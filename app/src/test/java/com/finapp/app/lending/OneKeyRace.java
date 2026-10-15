package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Ten requests under one {@code Idempotency-Key}, released at once - the Phase 11 gate's keyed-door clause
 * ({@code PHASE_GATES.md} section 5, Phase 11 "Idempotency"; {@code INV-IDEM-01}): every keyed door, raced by ten
 * requests carrying one key and one body, makes one effect and answers one response; a racer that finds the key's
 * execution still in flight is {@code 409 api.IdempotencyInProgress} and nothing else. The effect itself - one row, one
 * audit record, one entry - is the calling suite's to count; this answers the one receipt.
 */
final class OneKeyRace {

    static final int RACERS = 10;

    private OneKeyRace() {}

    /**
     * {@code request} - one key, one body, one session - sent by ten threads at once: every answer is the act's
     * {@code success} status with one body, byte for byte, or the key's in-progress refusal; at least one answered the
     * act; and a retry afterwards replays the same receipt. Answers that receipt.
     */
    static HttpResponse<String> tenUnderOneKey(int success, Callable<HttpResponse<String>> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> racing = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            HttpResponse<String> receipt = null;
            for (Future<HttpResponse<String>> answer : racing) {
                HttpResponse<String> response = answer.get(2, TimeUnit.MINUTES);
                if (response.statusCode() == 409 && response.body().contains("api.IdempotencyInProgress")) {
                    continue;
                }
                assertThat(response.statusCode()).as("the act's answer or the key's in-progress refusal: %s",
                        response.body()).isEqualTo(success);
                if (receipt == null) {
                    receipt = response;
                } else {
                    assertThat(response.body()).as("every answer the one receipt, byte for byte").isEqualTo(receipt.body());
                }
            }
            assertThat(receipt).as("at least one of the ten answered the act").isNotNull();
            HttpResponse<String> replay = request.call();
            assertThat(replay.statusCode()).as(replay.body()).isEqualTo(success);
            assertThat(replay.body()).as("a later retry replays the one receipt").isEqualTo(receipt.body());
            return receipt;
        } finally {
            pool.shutdownNow();
        }
    }
}
