package com.nutrilog;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 仅显式live-smoke配置运行；不作为公开聊天接口。 */
@Component
@Profile("live-smoke")
public class LiveSmokeRunner implements CommandLineRunner {
    private final ChatClient.Builder builder;
    public LiveSmokeRunner(ChatClient.Builder builder) { this.builder = builder; }
    public static class ProbeTools {
        private final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "按已知的每100克热量和重量计算热量")
        public BigDecimal calculateCalories(BigDecimal kcalPer100g, BigDecimal weightGrams) {
            calls.incrementAndGet();
            return new NutritionTools().calculateCalories(kcalPer100g, weightGrams);
        }
    }
    @Override public void run(String... args) {
        ProbeTools probe = new ProbeTools();
        String answer = builder.build().prompt()
            .system("这是协议验证。必须调用提供的计算工具获取数值，不得自行心算。")
            .user("测试输入：某食物每100克116千卡，吃了200克。请调用工具计算，并用中文回答。")
            .tools(probe).call().content();
        if (probe.calls.get() == 0) throw new IllegalStateException("模型未调用工具，冒烟失败");
        System.out.println("LIVE_SMOKE toolCalls=" + probe.calls.get());
        System.out.println("LIVE_SMOKE answer=" + answer);
    }
}
