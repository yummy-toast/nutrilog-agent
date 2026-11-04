package com.nutrilog.chat;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;

class RequestTraceTest {
    private static final String PRIVATE = "private-prompt-answer-parameter-marker";
    public static class Tools {
        @Tool(description="test") public String lookup(String text) {
            return "{\"status\":\"NOT_FOUND\",\"message\":\"" + PRIVATE + "\"}";
        }
    }
    static class Capture extends AppenderBase<ILoggingEvent> {
        final List<String> lines = new CopyOnWriteArrayList<>();
        @Override protected void append(ILoggingEvent event) { lines.add(event.getFormattedMessage()); }
    }
    @Test void concurrentHttpRequestsKeepTheirTraceAcrossModelAndToolWithoutLeakingContent() throws Exception {
        var logger = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var capture = new Capture(); capture.start(); logger.addAppender(capture);
        try(var loop = new BudgetedChatLoop(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatModel model = prompt -> {
                var output = prompt.getInstructions().size() == 2
                    ? AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("id", "function", "lookup", "{\"text\":\""+PRIVATE+"\"}"))).build()
                    : AssistantMessage.builder().content(PRIVATE).build();
                return new ChatResponse(List.of(new Generation(output)));
            };
            ChatGateway gateway = new ChatGateway() {
                public String answer(String message) { throw new AssertionError("必须显式传递追踪"); }
                public String answer(String message, RequestTrace trace) {
                    return loop.run(model, Arrays.asList(ToolCallbacks.from(new Tools())), "system", message, trace);
                }
            };
            var api = MockMvcBuilders.standaloneSetup(new ChatController(new ChatService(gateway)))
                .setControllerAdvice(new ChatErrorHandler()).addInterceptors(new ChatTraceInterceptor()).build();
            Callable<String> request = () -> {
                var response = api.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\""+PRIVATE+"\"}")).andReturn().getResponse();
                assertEquals(200,response.getStatus());
                var id = JsonMapper.builder().build().readTree(response.getContentAsString()).get("requestId").asString();
                assertEquals(id,response.getHeader("X-Request-Id"));
                return id;
            };
            var a = callers.submit(request); var b = callers.submit(request);
            var ids = List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            assertNotEquals(ids.get(0),ids.get(1));
            for (String id:ids) {
                var lines = capture.lines.stream().filter(line -> line.contains("requestId="+id)).toList();
                assertEquals(8,lines.size()); // 开始 + 两轮模型各开始/结束 + 一次工具开始/结束 + 请求结束
                assertTrue(lines.stream().anyMatch(line -> line.contains("event=tool_finished") && line.contains("status=NOT_FOUND")));
                assertTrue(lines.stream().anyMatch(line -> line.contains("event=request_finished") && line.contains("modelCalls=2 toolCalls=1")));
                assertTrue(lines.stream().anyMatch(line -> line.contains("event=request_started")));
            }
            assertTrue(capture.lines.stream().noneMatch(line -> line.contains(PRIVATE)));
        } finally { logger.detachAppender(capture); capture.stop(); }
    }
    @Test void failureKeepsIdAndLogsOnlySafeErrorMetadata() throws Exception {
        var logger = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var capture = new Capture(); capture.start(); logger.addAppender(capture);
        try {
            ChatGateway gateway = message -> { throw new RuntimeException(PRIVATE); };
            var api = MockMvcBuilders.standaloneSetup(new ChatController(new ChatService(gateway)))
                .setControllerAdvice(new ChatErrorHandler()).addInterceptors(new ChatTraceInterceptor()).build();
            var response = api.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\""+PRIVATE+"\"}")).andReturn().getResponse();
            assertEquals(502,response.getStatus());
            var id = JsonMapper.builder().build().readTree(response.getContentAsString()).get("requestId").asString();
            assertEquals(id,response.getHeader("X-Request-Id"));
            assertTrue(capture.lines.stream().anyMatch(line -> line.contains("requestId="+id)
                && line.contains("event=request_finished") && line.contains("outcome=MODEL_ERROR")));
            assertTrue(capture.lines.stream().noneMatch(line -> line.contains(PRIVATE)));
            assertFalse(response.getContentAsString().contains(PRIVATE));
        } finally { logger.detachAppender(capture); capture.stop(); }
    }

}
