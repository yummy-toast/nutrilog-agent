package com.nutrilog;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NutritionToolsTest {
    private final NutritionTools tools = new NutritionTools();
    @Test void scalesWeight() {
        assertEquals(new BigDecimal("232.00"), tools.calculateCalories(new BigDecimal("116"), new BigDecimal("200")));
    }
    @Test void distinguishesZeroFromMissing() {
        assertEquals(new BigDecimal("0.00"), tools.calculateCalories(BigDecimal.ZERO, BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class, () -> tools.calculateCalories(null, BigDecimal.TEN));
    }
    @Test void rejectsInvalidWeight() {
        assertThrows(IllegalArgumentException.class, () -> tools.calculateCalories(BigDecimal.TEN, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> tools.calculateCalories(BigDecimal.TEN, new BigDecimal("-1")));
    }
}
