package com.nutrilog.chat;

import java.util.*;
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
        return run(model, callbacks, system, input, new ExecutionBudget());
    }
    String run(ChatModel model, List<ToolCallback> callbacks, String system, String input, ExecutionBudget budget) {
        var allowed = new HashMap<String, ToolCallback>();
        callbacks.forEach(c -> allowed.put(c.getToolDefinition().name(), c));
        var messages = new ArrayList<Message>(List.of(new SystemMessage(system), new UserMessage(input)));
        while (true) {
            var timeout = budget.beforeModel();
            var base = model.getDefaultOptions();
            var builder = base instanceof OpenAiChatOptions openAi ? openAi.mutate() : OpenAiChatOptions.builder();
            var options = builder.toolCallbacks(callbacks).timeout(timeout).maxRetries(0).build();
            Future<org.springframework.ai.chat.model.ChatResponse> future;
            try { future = workers.submit(() -> model.call(new Prompt(List.copyOf(messages), options))); }
            catch (RejectedExecutionException busy) { throw new ChatFailure("MODEL_BUSY", 503); }
            org.springframework.ai.chat.model.ChatResponse response;
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
                String result = callback.call(call.arguments());
                budget.remaining();
                results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(results).build());
        }
    }
    @Override public void close() { workers.shutdownNow(); }
}
