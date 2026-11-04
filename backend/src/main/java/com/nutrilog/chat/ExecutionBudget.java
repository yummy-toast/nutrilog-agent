package com.nutrilog.chat;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 每个请求新建；只由该请求的编排线程访问。 */
public final class ExecutionBudget {
    private final LongSupplier clock;
    private final long started;
    private final long totalNanos;
    private int models;
    private int tools;
    public ExecutionBudget() { this(System::nanoTime, Duration.ofSeconds(60)); }
    ExecutionBudget(LongSupplier clock, Duration total) {
        this.clock = clock; this.started = clock.getAsLong(); this.totalNanos = total.toNanos();
    }
    public Duration remaining() {
        long nanos = totalNanos - (clock.getAsLong() - started);
        if (nanos <= 0) throw new ChatFailure("EXECUTION_TIMEOUT", 504);
        return Duration.ofNanos(nanos);
    }
    public Duration beforeModel() {
        var left = remaining();
        if (models >= 6) throw new ChatFailure("MODEL_LIMIT_EXCEEDED", 502);
        models++;
        return left.compareTo(Duration.ofSeconds(30)) < 0 ? left : Duration.ofSeconds(30);
    }
    public void beforeTool() {
        remaining();
        if (tools >= 8) throw new ChatFailure("TOOL_LIMIT_EXCEEDED", 502);
        tools++;
    }
}
