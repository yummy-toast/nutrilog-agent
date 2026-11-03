package com.nutrilog;
import com.nutrilog.food.FoodService;
import com.nutrilog.food.JsonFoodCatalog;
import java.util.List;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class FoodServiceTest {
    private final FoodService service = new FoodService(new JsonFoodCatalog());
    @Test void preservesAmbiguousCandidates() {
        assertEquals(2, service.search("米饭").size());
        assertTrue(service.search("不存在").isEmpty());
    }
    @Test void calculatesFromSelectedFood() {
        var result = service.calculate(168935L, new BigDecimal("200"));
        assertEquals(new BigDecimal("260.00"), result.energy().amount());
        assertFalse(result.source().demo());
        assertEquals(168935L, result.source().externalId());
        assertTrue(result.source().originalDescription().contains("with salt"));
    }
    @Test void distinguishesMissingAndKnownZero() {
        var fixture = new FoodService.Food(3, "测试缺失", "UNKNOWN", List.of(),
            new FoodService.Nutrients(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, null),
            service.get(168935).source());
        var isolatedService = new FoodService(() -> List.of(fixture));
        var result = isolatedService.calculate(3L, new BigDecimal("200"));
        assertNull(result.carbohydrate().amount());
        assertEquals("MISSING", result.carbohydrate().status());
        assertEquals(new BigDecimal("0.00"), result.fat().amount());
        assertEquals("KNOWN", result.fat().status());
        assertEquals("KNOWN", result.energy().status());
    }
    @Test void rejectsInvalidInputs() {
        assertThrows(ResponseStatusException.class, () -> service.calculate(168935L, BigDecimal.ZERO));
        assertThrows(ResponseStatusException.class, () -> service.calculate(null, BigDecimal.ONE));
        assertThrows(ResponseStatusException.class, () -> service.calculate(999L, BigDecimal.ONE));
        assertThrows(ResponseStatusException.class, () -> service.search(" "));
    }
}
