package com.nutrilog.chat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import static org.junit.jupiter.api.Assertions.*;
class BudgetedChatLoopTest {
    public static class Counter {
        int calls;
        @Tool(description="test") public String search() { calls++; return "found"; }
    }
    private ChatResponse tools(int count) {
        var calls = new ArrayList<AssistantMessage.ToolCall>();
        for (int i=0;i<count;i++) calls.add(new AssistantMessage.ToolCall("id"+i,"function","search","{}"));
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }
    @Test void repeatedModelLoopStopsAtSix() {
        var counter = new Counter(); var models = new AtomicInteger();
        ChatModel model = prompt -> { models.incrementAndGet(); return tools(1); };
        try(var loop = new BudgetedChatLoop()) {
            var error = assertThrows(ChatFailure.class, () -> loop.run(model, Arrays.asList(ToolCallbacks.from(counter)), "system", "input"));
            assertEquals("MODEL_LIMIT_EXCEEDED",error.code());
            assertEquals(6,models.get()); assertEquals(6,counter.calls);
        }
    }
    @Test void batchStopsBeforeNinthTool() {
        var counter = new Counter();
        try(var loop = new BudgetedChatLoop()) {
            var error = assertThrows(ChatFailure.class, () -> loop.run(prompt -> tools(9), Arrays.asList(ToolCallbacks.from(counter)), "system", "input"));
            assertEquals("TOOL_LIMIT_EXCEEDED",error.code()); assertEquals(8,counter.calls);
        }
    }
    @Test void lateResponseCannotExecuteToolsAndTimeoutReachesProvider() {
        var now = new AtomicLong(); var counter = new Counter();
        var budget = new ExecutionBudget(now::get, Duration.ofSeconds(60));
        now.set(Duration.ofSeconds(55).toNanos());
        ChatModel model = prompt -> {
            assertEquals(Duration.ofSeconds(5), ((OpenAiChatOptions)prompt.getOptions()).getTimeout());
            now.set(Duration.ofSeconds(61).toNanos());
            return tools(1);
        };
        try(var loop = new BudgetedChatLoop()) {
            assertEquals("EXECUTION_TIMEOUT", assertThrows(ChatFailure.class, () ->
                loop.run(model, Arrays.asList(ToolCallbacks.from(counter)), "system", "input", budget)).code());
            assertEquals(0,counter.calls);
        }
    }
    @Test void timeoutRequestsInterruptionAndDoesNotExecuteLateTool() throws Exception {
        var interrupted = new CountDownLatch(1); var counter = new Counter();
        ChatModel model = prompt -> {
            try { Thread.sleep(10000); }
            catch (InterruptedException expected) { interrupted.countDown(); }
            return tools(1);
        };
        try(var loop = new BudgetedChatLoop()) {
            var error = assertThrows(ChatFailure.class, () -> loop.run(model, Arrays.asList(ToolCallbacks.from(counter)), "system", "input",
                new ExecutionBudget(System::nanoTime, Duration.ofMillis(300))));
            assertTrue(Set.of("MODEL_TIMEOUT","EXECUTION_TIMEOUT").contains(error.code()));
            assertTrue(interrupted.await(2,TimeUnit.SECONDS)); assertEquals(0,counter.calls);
        }
    }
    @Test void concurrentRequestsHaveIndependentBudgets() throws Exception {
        var models = new AtomicInteger();
        ChatModel model = prompt -> { models.incrementAndGet(); return tools(1); };
        try(var loop = new BudgetedChatLoop(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = new Counter(); var second = new Counter();
            Callable<String> a = () -> assertThrows(ChatFailure.class, () -> loop.run(model,
                Arrays.asList(ToolCallbacks.from(first)), "system", "a")).code();
            Callable<String> b = () -> assertThrows(ChatFailure.class, () -> loop.run(model,
                Arrays.asList(ToolCallbacks.from(second)), "system", "b")).code();
            var fa = callers.submit(a); var fb = callers.submit(b);
            assertEquals("MODEL_LIMIT_EXCEEDED",fa.get(5,TimeUnit.SECONDS));
            assertEquals("MODEL_LIMIT_EXCEEDED",fb.get(5,TimeUnit.SECONDS));
            assertEquals(6,first.calls); assertEquals(6,second.calls); assertEquals(12,models.get());
        }
    }
    @Test void uncooperativeCallsKeepTheirSlotsUntilTheyReallyStop() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(4);
        ChatModel stuck = prompt -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* 模拟不响应取消的SDK */ }
            }
            return tools(1);
        };
        try(var loop = new BudgetedChatLoop(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = new ArrayList<Future<ChatFailure>>();
            for(int i=0;i<4;i++) pending.add(callers.submit(() -> assertThrows(ChatFailure.class, () ->
                loop.run(stuck, List.of(), "system", "input",
                    new ExecutionBudget(System::nanoTime,Duration.ofSeconds(1))))));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                for(var task:pending) assertEquals(504,task.get(3,TimeUnit.SECONDS).status());
                assertEquals("MODEL_BUSY",assertThrows(ChatFailure.class, () ->
                    loop.run(stuck,List.of(),"system","fifth")).code());
            } finally { release.countDown(); }
        }
    }

    @Test void retainsConfiguredProviderOptions() {
        ChatModel model = new ChatModel() {
            @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                return OpenAiChatOptions.builder().model("configured-model").temperature(0.0).build();
            }
            @Override public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                var options = (OpenAiChatOptions)prompt.getOptions();
                assertEquals("configured-model",options.getModel());
                assertEquals(0.0,options.getTemperature());
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("done").build())));
            }
        };
        try(var loop = new BudgetedChatLoop()) {
            assertEquals("done",loop.run(model,List.of(),"system","input"));
        }
    }

}
