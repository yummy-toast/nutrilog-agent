package com.nutrilog;
import com.nutrilog.food.FoodService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class FoodServiceTest {
    private final FoodService service = new FoodService();
    @Test void preservesAmbiguousCandidates() {
        assertEquals(2, service.search("米饭").size());
        assertTrue(service.search("不存在").isEmpty());
    }
    @Test void calculatesFromSelectedFood() {
        var result = service.calculate(2L, new BigDecimal("200"));
        assertEquals(new BigDecimal("232.00"), result.energy().amount());
        assertTrue(result.source().demo());
    }
    @Test void distinguishesMissingAndKnownZero() {
        var result = service.calculate(3L, new BigDecimal("200"));
        assertNull(result.carbohydrate().amount());
        assertEquals("MISSING", result.carbohydrate().status());
        assertEquals(new BigDecimal("0.00"), result.fat().amount());
        assertEquals("KNOWN", result.fat().status());
        assertEquals("KNOWN", result.energy().status());
    }
    @Test void rejectsInvalidInputs() {
        assertThrows(ResponseStatusException.class, () -> service.calculate(2L, BigDecimal.ZERO));
        assertThrows(ResponseStatusException.class, () -> service.calculate(null, BigDecimal.ONE));
        assertThrows(ResponseStatusException.class, () -> service.calculate(999L, BigDecimal.ONE));
        assertThrows(ResponseStatusException.class, () -> service.search(" "));
    }
}
