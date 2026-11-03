package com.nutrilog.agent;

import com.nutrilog.food.FoodService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("food-agent-smoke")
public class FoodAgentSmokeRunner implements CommandLineRunner {
    private final ChatClient.Builder builder;
    private final FoodService service;
    public FoodAgentSmokeRunner(ChatClient.Builder builder, FoodService service) {
        this.builder = builder; this.service = service;
    }
    public static class ObservedTools extends FoodAgentTools {
        final List<String> calls = new ArrayList<>();
        FoodService.Calculation calculation;
        public ObservedTools(FoodService service) { super(service); }
        @Override @Tool(description = "搜索食品候选；保留限定，多个候选先向用户澄清，不得擅自选定。")
        public Result<List<FoodService.Food>> searchFoods(String keyword) {
            calls.add("searchFoods");
            return super.searchFoods(keyword);
        }
        @Override @Tool(description = "使用已确认食品编号与克重计算营养；不可猜编号、不可把缺失当0。")
        public Result<FoodService.Calculation> calculateFoodNutrition(Long foodId, BigDecimal weightGrams) {
            calls.add("calculateFoodNutrition");
            var result = super.calculateFoodNutrition(foodId, weightGrams);
            calculation = result.data();
            return result;
        }
    }
    @Override public void run(String... args) {
        runCase("selected", "应用可信上下文：用户已选定食品168935（长粒白米饭，熟，未强化，加盐）。用户问：计算200克的营养。", List.of("calculateFoodNutrition"));
        runCase("ambiguous", "用户问：200克米饭多少热量？当前没有选中的食品。", List.of("searchFoods"));
    }
    private void runCase(String name, String question, List<String> expected) {
        var observed = new ObservedTools(service);
        var answer = builder.build().prompt().system("使用工具获取业务事实。已选定食品可直接计算。未知食品先搜索；存在生熟等歧义必须追问，不能猜测、计算或给出总热量。完整保留来源与限定；不展示内部推理。")
            .user(question).tools(observed).call().content();
        if (!observed.calls.equals(expected)) throw new IllegalStateException(name + "工具路径不符合预期：" + observed.calls);
        if (name.equals("selected") && (observed.calculation == null || observed.calculation.foodId() != 168935
            || observed.calculation.weightGrams().compareTo(new BigDecimal("200")) != 0
            || observed.calculation.energy().amount().compareTo(new BigDecimal("260")) != 0))
            throw new IllegalStateException("计算参数或结果不符");
        System.out.println("FOOD_AGENT " + name + " calls=" + observed.calls);
        System.out.println("FOOD_AGENT " + name + " answer=" + answer);
    }
}
