package com.open.spring.mvc.calls;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A CallScheduler whose time only moves when a test says so: tasks run in runDue(), in the order
 * they come due, including ones they schedule for the same moment.
 */
final class ManualCallScheduler implements CallScheduler {

    /** A clock the test moves forward. */
    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private record Scheduled(Instant due, long order, Runnable task) {}

    final TestClock clock = new TestClock(Instant.parse("2026-11-22T12:00:00Z"));
    private final List<Scheduled> tasks = new ArrayList<>();
    private long nextOrder;

    @Override
    public void schedule(Runnable task, Duration delay) {
        tasks.add(new Scheduled(clock.instant().plus(delay), nextOrder++, task));
    }

    void advance(Duration duration) {
        clock.advance(duration);
        runDue();
    }

    void runDue() {
        while (true) {
            Scheduled next = tasks.stream()
                    .filter(scheduled -> !scheduled.due().isAfter(clock.instant()))
                    .min(Comparator.comparing(Scheduled::due).thenComparing(Scheduled::order))
                    .orElse(null);
            if (next == null) {
                return;
            }
            tasks.remove(next);
            next.task().run();
        }
    }
}
