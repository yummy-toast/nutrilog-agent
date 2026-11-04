package com.nutrilog.chat;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 显式传递的请求上下文；日志字段仅允许程序生成的事件、状态及工具白名单名称。 */
public final class RequestTrace {
    static final String ATTRIBUTE = RequestTrace.class.getName();
    private static final Logger log = LoggerFactory.getLogger(RequestTrace.class);
    private final String id = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private int models;
    private int tools;
    private String outcome = "OK";
    public String id() { return id; }
    public static RequestTrace from(HttpServletRequest request) {
        var trace = (RequestTrace) request.getAttribute(ATTRIBUTE);
        if (trace == null) { trace = new RequestTrace(); request.setAttribute(ATTRIBUTE, trace); }
        return trace;
    }
    public void start() { event("request_started", "request", 0, 0, "STARTED"); }
    public long modelStarted() { event("model_started", "model", ++models, 0, "STARTED"); return System.nanoTime(); }
    public void modelFinished(long start, String status) { event("model_finished", "model", models, elapsed(start), status); }
    public long toolStarted(String name) { event("tool_started", name, ++tools, 0, "STARTED"); return System.nanoTime(); }
    public void toolFinished(String name, long start, String status) { event("tool_finished", name, tools, elapsed(start), status); }
    public void failure(String code) { outcome = code; }
    public void finish(int httpStatus) {
        log.info("event=request_finished requestId={} httpStatus={} outcome={} durationMs={} modelCalls={} toolCalls={}",
            id, httpStatus, httpStatus >= 400 && outcome.equals("OK") ? "HTTP_ERROR" : outcome, elapsed(started), models, tools);
    }
    private long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
    private void event(String event, String stage, int count, long duration, String status) {
        log.info("event={} requestId={} stage={} count={} durationMs={} status={}", event,id,stage,count,duration,status);
    }
}
