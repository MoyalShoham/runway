package io.github.moyalshoham.runway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SimulationLimiterTest {

    @Test
    void aRequestBeyondTheLimitIsTurnedAwayInsteadOfPilingUp() throws Exception {
        SimulationLimiter limiter = new SimulationLimiter(1, 50);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> limiter.run(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 1;
            }));
            running.await();

            assertThatThrownBy(() -> limiter.run(() -> 2)).isInstanceOf(BusyException.class);
            release.countDown();
        } finally {
            pool.shutdown();
        }
        Thread.sleep(100);
        assertThat(limiter.run(() -> 3)).isEqualTo(3);
    }
}
