package com.nutrilog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbacks;
import static org.junit.jupiter.api.Assertions.*;
class ToolProtocolTest {
    @Test void executesThroughJsonToolProtocol() {
        var callbacks = ToolCallbacks.from(new NutritionTools());
        assertEquals(1, callbacks.length);
        assertEquals("calculateCalories", callbacks[0].getToolDefinition().name());
        String output = callbacks[0].call("{\"kcalPer100g\":116,\"weightGrams\":200}");
        assertEquals("232.00", output);
    }
}
