package com.nutrilog.chat;

import java.util.*;
import com.nutrilog.session.SessionDirectory.Turn;
import java.util.concurrent.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

/** 使用ChatModel单轮API；不使用自动执行工具的ChatClient循环。 */
public final class BudgetedChatLoop implements AutoCloseable {
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> {
            var thread = new Thread(task, "chat-model-worker"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    public String run(ChatModel model, List<ToolCallback> callbacks, String system, String input) {
        return run(model, callbacks, system, input, new ExecutionBudget(), new RequestTrace());
    }
    public String run(ChatModel model, List<ToolCallback> callbacks, String system, String input, RequestTrace trace) {
        return run(model, callbacks, system, input, new ExecutionBudget(), trace);
    }
    String run(ChatModel model, List<ToolCallback> callbacks, String system, String input, ExecutionBudget budget) {
        return run(model, callbacks, system, input, budget, new RequestTrace());
    }
    public String run(ChatModel model, List<ToolCallback> callbacks, String system, String input,
                      RequestTrace trace, List<Turn> history) {
        return run(model, callbacks, system, input, new ExecutionBudget(), trace, history);
    }

    private String run(ChatModel model, List<ToolCallback> callbacks, String system, String input,
                       ExecutionBudget budget, RequestTrace trace) {
        return run(model, callbacks, system, input, budget, trace, List.of());
    }

    private String run(ChatModel model, List<ToolCallback> callbacks, String system, String input,
                       ExecutionBudget budget, RequestTrace trace, List<Turn> history) {
        var allowed = new HashMap<String, ToolCallback>();
        callbacks.forEach(c -> allowed.put(c.getToolDefinition().name(), c));
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(system));
        for (Turn turn : history) {
            messages.add(new UserMessage(turn.message()));
            messages.add(AssistantMessage.builder().content(turn.answer()).build());
        }
        messages.add(new UserMessage(input));
        while (true) {
            var timeout = budget.beforeModel();
            var base = model.getDefaultOptions();
            var builder = base instanceof OpenAiChatOptions openAi ? openAi.mutate() : OpenAiChatOptions.builder();
            var options = builder.toolCallbacks(callbacks).timeout(timeout).maxRetries(0).build();
            long modelStart = trace.modelStarted();
            String modelStatus = "ERROR";
            org.springframework.ai.chat.model.ChatResponse response;
            try {
            Future<org.springframework.ai.chat.model.ChatResponse> future;
            try { future = workers.submit(() -> model.call(new Prompt(List.copyOf(messages), options))); }
            catch (RejectedExecutionException busy) { throw new ChatFailure("MODEL_BUSY", 503); }
            try { response = future.get(Math.min(timeout.toNanos(), budget.remaining().toNanos()), TimeUnit.NANOSECONDS); }
            catch (TimeoutException expired) {
                future.cancel(true);
                budget.remaining();
                throw new ChatFailure("MODEL_TIMEOUT", 504);
            } catch (ChatFailure expired) {
                future.cancel(true); throw expired;
            } catch (InterruptedException interrupted) {
                future.cancel(true); Thread.currentThread().interrupt();
                throw new ChatFailure("EXECUTION_CANCELLED", 503);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof RuntimeException cause) throw cause;
                throw new ChatFailure("MODEL_ERROR", 502);
            }
            budget.remaining();
            modelStatus = "OK";
            } finally { trace.modelFinished(modelStart, modelStatus); }
            if (response == null || response.getResult() == null) return null;
            var output = response.getResult().getOutput();
            if (output == null) return null;
            if (!output.hasToolCalls()) return output.getText();
            messages.add(output);
            var results = new ArrayList<ToolResponseMessage.ToolResponse>();
            for (var call : output.getToolCalls()) {
                budget.beforeTool();
                var callback = allowed.get(call.name());
                if (callback == null) throw new ChatFailure("UNKNOWN_TOOL", 502);
                long toolStart = trace.toolStarted(callback.getToolDefinition().name());
                String toolStatus = "ERROR";
                String result;
                try {
                    result = callback.call(call.arguments());
                    budget.remaining();
                    toolStatus = businessStatus(result);
                } finally { trace.toolFinished(callback.getToolDefinition().name(), toolStart, toolStatus); }
                results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(results).build());
        }
    }
    private String businessStatus(String result) {
        try {
            var status = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result).path("status").asString("");
            return Set.of("OK", "NOT_FOUND", "INVALID_ARGUMENT").contains(status) ? status : "COMPLETED";
        } catch (RuntimeException malformed) { return "COMPLETED"; }
    }
    @Override public void close() { workers.shutdownNow(); }
}
