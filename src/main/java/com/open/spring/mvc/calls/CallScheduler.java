package com.open.spring.mvc.calls;

import java.time.Duration;

/** Runs CallSupervisor's work later and off the request thread, so a slow LiveKit never holds up a webhook. */
public interface CallScheduler {

    void schedule(Runnable task, Duration delay);
}
