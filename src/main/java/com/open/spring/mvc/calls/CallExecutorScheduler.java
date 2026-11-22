package com.open.spring.mvc.calls;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * CallScheduler on one thread of its own, made the first time a call needs it (so nothing runs
 * while calls are off), apart from Spring's shared scheduler so other @Scheduled jobs never wait
 * on LiveKit.
 */
@Component
public class CallExecutorScheduler implements CallScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CallExecutorScheduler.class);

    private ScheduledExecutorService executor;

    @Override
    public void schedule(Runnable task, Duration delay) {
        executor().schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.error("A video call task failed", e);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized ScheduledExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "calls-supervisor");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    @PreDestroy
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
