package com.nutrilog.agent;

import com.nutrilog.food.FoodService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FoodAgentTools {
    protected final FoodService service;
    public FoodAgentTools(FoodService service) { this.service = service; }
    public record Result<T>(String status, T data, String message) {}
    @Tool(description = "按食品关键词搜索候选。保留生熟、加盐等限定；多个候选时向用户澄清，不得擅自选择。无候选不得编造食品。")
    public Result<List<FoodService.Food>> searchFoods(String keyword) {
        return execute(() -> service.search(keyword));
    }
    @Tool(description = "按已选定的食品编号和可食部克重计算营养。仅使用用户选定或搜索确认的编号；缺重量先追问。MISSING表示未知，不得说成0。返回来源，不得自行改写数值。")
    public Result<FoodService.Calculation> calculateFoodNutrition(Long foodId, BigDecimal weightGrams) {
        return execute(() -> service.calculate(foodId, weightGrams));
    }
    private <T> Result<T> execute(Supplier<T> work) {
        try { return new Result<>("OK", work.get(), null); }
        catch (ResponseStatusException error) {
            return new Result<>(error.getStatusCode().value() == 404 ? "NOT_FOUND" : "INVALID_ARGUMENT",
                null, error.getReason());
        }
    }
}
