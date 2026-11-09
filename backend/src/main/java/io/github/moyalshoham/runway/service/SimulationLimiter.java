package io.github.moyalshoham.runway.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds how many Monte Carlo simulations run at once, so a burst of forecast or scenario requests
 * queues briefly and then gets a 503 instead of starving the whole server of CPU and memory.
 */
@Component
public class SimulationLimiter {

    private final Semaphore permits;
    private final long waitMillis;

    public SimulationLimiter(
            @Value("${runway.simulations.max-concurrent:0}") int maxConcurrent,
            @Value("${runway.simulations.wait-millis:5000}") long waitMillis) {
        int n = maxConcurrent > 0
                ? maxConcurrent
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.permits = new Semaphore(n, true);
        this.waitMillis = waitMillis;
    }

    public <T> T run(Supplier<T> simulation) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException("Interrupted while waiting for a simulation slot");
        }
        if (!acquired) {
            throw new BusyException("The server is busy with other forecasts; try again in a few seconds");
        }
        try {
            return simulation.get();
        } finally {
            permits.release();
        }
    }
}
