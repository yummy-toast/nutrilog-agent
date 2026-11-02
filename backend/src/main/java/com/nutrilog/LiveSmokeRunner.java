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
        private BigDecimal lastResult;
        private BigDecimal lastKcalPer100g;
        private BigDecimal lastWeightGrams;
        @Tool(description = "按已知的每100克热量和重量计算热量")
        public BigDecimal calculateCalories(BigDecimal kcalPer100g, BigDecimal weightGrams) {
            calls.incrementAndGet();
            lastKcalPer100g = kcalPer100g;
            lastWeightGrams = weightGrams;
            lastResult = new NutritionTools().calculateCalories(kcalPer100g, weightGrams);
            return lastResult;
        }
    }
    @Override public void run(String... args) {
        ProbeTools probe = new ProbeTools();
        String answer = builder.build().prompt()
            .system("这是协议验证。必须调用提供的计算工具获取数值，不得自行心算。")
            .user("测试输入：某食物每100克116千卡，吃了200克。请调用工具计算，并用中文回答。")
            .tools(probe).call().content();
        if (probe.calls.get() != 1) throw new IllegalStateException("工具调用次数不符合预期，冒烟失败");
        if (probe.lastResult == null || probe.lastResult.compareTo(new BigDecimal("232")) != 0)
            throw new IllegalStateException("工具计算结果不符合预期");
        if (probe.lastKcalPer100g.compareTo(new BigDecimal("116")) != 0
                || probe.lastWeightGrams.compareTo(new BigDecimal("200")) != 0)
            throw new IllegalStateException("工具入参与测试输入不符");
        if (answer == null || !answer.contains("232"))
            throw new IllegalStateException("最终回答未包含预期数值，需人工核查");
        System.out.println("LIVE_SMOKE inputs=" + probe.lastKcalPer100g + "," + probe.lastWeightGrams);
        System.out.println("LIVE_SMOKE toolResult=" + probe.lastResult);
        System.out.println("LIVE_SMOKE toolCalls=" + probe.calls.get());
        System.out.println("LIVE_SMOKE answer=" + answer);
    }
}
