package com.nutrilog;
import com.nutrilog.agent.FoodAgentTools;
import com.nutrilog.food.FoodService;
import com.nutrilog.food.JsonFoodCatalog;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class FoodAgentToolsTest {
    private final FoodAgentTools tools = new FoodAgentTools(new FoodService(new JsonFoodCatalog()));
    @Test void returnsBothCandidates() {
        assertEquals(2, tools.searchFoods("米饭").data().size());
        assertTrue(tools.searchFoods("不存在").data().isEmpty());
    }
    @Test void returnsBusinessErrorsWithoutInventingFacts() {
        assertEquals("NOT_FOUND", tools.calculateFoodNutrition(999L, BigDecimal.ONE).status());
        var result = tools.calculateFoodNutrition(168935L, BigDecimal.ZERO);
        assertEquals("INVALID_ARGUMENT", result.status());
        assertNull(result.data());
    }
    @Test void exposesOnlyBusinessToolsAndSerializesSource() {
        var callbacks = ToolCallbacks.from(tools);
        assertEquals(2, callbacks.length);
        var callback = java.util.Arrays.stream(callbacks)
            .filter(c -> c.getToolDefinition().name().equals("calculateFoodNutrition")).findFirst().orElseThrow();
        var json = JsonMapper.builder().build().readTree(callback.call("{\"foodId\":168935,\"weightGrams\":200}"));
        assertEquals("OK", json.get("status").asString());
        assertEquals(260, json.get("data").get("energy").get("amount").asInt());
        assertEquals(168935, json.get("data").get("source").get("externalId").asLong());
    }
}
