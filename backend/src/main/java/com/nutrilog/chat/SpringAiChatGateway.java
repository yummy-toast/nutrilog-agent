package com.nutrilog.chat;

import com.nutrilog.agent.FoodAgentTools;
import org.springframework.ai.support.ToolCallbacks;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import com.nutrilog.food.FoodService;
import com.nutrilog.session.SessionDirectory.Turn;
import com.nutrilog.session.SessionDirectory.State;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SpringAiChatGateway implements ChatGateway {
    private final ChatModel model;
    private final BudgetedChatLoop loop = new BudgetedChatLoop();
    private final FoodAgentTools tools;
    public SpringAiChatGateway(ObjectProvider<ChatModel> models, FoodAgentTools tools) {
        this.model = models.getIfAvailable();
        this.tools = tools;
    }
    @Override public String answer(String message) { return answer(message, new RequestTrace()); }
    @Override public String answer(String message, RequestTrace trace) {
        return answer(message, trace, List.of());
    }
    @Override public String answer(String message, RequestTrace trace, List<Turn> history) {
        return answerSession(message, trace, history, null).answer();
    }
    @Override public SessionAnswer answerSession(String message, RequestTrace trace, List<Turn> history, State state) {
        if (model == null) throw new ChatFailure("MODEL_UNAVAILABLE", 503);
        String system = "你是只读营养查询助手。只使用当前请求提供的对话消息理解上下文，不得假设其他会话的内容；历史中出现的候选不代表用户已确认；不记餐、不声称已经保存。食品和营养事实必须调用提供的工具，不凭自身知识补数值。食品不明确先搜索，多候选必须澄清；缺重量追问，不猜克数。MISSING不等于0。保留生熟、加盐等限定与来源。工具失败如实说明并请求纠正，不将失败当成功。不要输出内部推理。";
        if (state != null && state.selectedFoodId() != null) {
            system += "\n会话最近由用户显式确认的食品编号：" + state.selectedFoodId()
                + "。最近确认的重量（克）：" + (state.weightGrams() == null ? "未确认" : state.weightGrams().toPlainString())
                + "。仅在当前问题指向该食品时使用这些值；当前问题更换食品或重量时以当前问题为准。"
                + "重量未确认时，不从旧食品的历史回答沿用重量。";
        }
        if (state != null && !state.candidateFoodIds().isEmpty()) {
            system += "\n最近一次成功搜索的候选食品编号：" + state.candidateFoodIds()
                + "。这些只是候选，只有用户明确选择后才可确定食品。";
        }
        var latestSearch = new AtomicReference<List<Long>>();
        var requestTools = state == null ? tools : tools.observingSearch(
            foods -> latestSearch.set(foods.stream().map(FoodService.Food::id).distinct().toList()));
        String answer = loop.run(model, Arrays.asList(ToolCallbacks.from(requestTools)), system, message, trace, history);
        return new SessionAnswer(answer, Optional.ofNullable(latestSearch.get()));
    }
    @PreDestroy public void shutdown() { loop.close(); }
}
