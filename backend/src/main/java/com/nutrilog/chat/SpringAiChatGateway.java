package com.nutrilog.chat;

import com.nutrilog.agent.FoodAgentTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SpringAiChatGateway implements ChatGateway {
    private final ChatClient client;
    private final FoodAgentTools tools;
    public SpringAiChatGateway(ObjectProvider<ChatModel> models, FoodAgentTools tools) {
        var model = models.getIfAvailable();
        this.client = model == null ? null : ChatClient.builder(model).build();
        this.tools = tools;
    }
    @Override public String answer(String message) {
        if (client == null) throw new ChatFailure("MODEL_UNAVAILABLE", 503);
        return client.prompt().system("你是只读营养查询助手。每次请求独立，没有历史或已选食品；不记餐、不声称已经保存。食品和营养事实必须调用提供的工具，不凭自身知识补数值。食品不明确先搜索，多候选必须澄清；缺重量追问，不猜克数。MISSING不等于0。保留生熟、加盐等限定与来源。工具失败如实说明并请求纠正，不将失败当成功。不要输出内部推理。")
            .user(message).tools(tools).call().content();
    }
}
