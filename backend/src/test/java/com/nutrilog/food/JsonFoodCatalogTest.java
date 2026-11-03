package com.nutrilog.food;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import static org.junit.jupiter.api.Assertions.*;

class JsonFoodCatalogTest {
    private ObjectNode document() throws Exception {
        try (var input = new ClassPathResource("data/selected-foods.json").getInputStream()) {
            return (ObjectNode) JsonMapper.builder().build().readTree(input);
        }
    }
    @Test void rejectsWrongReferenceWeight() throws Exception {
        var root = document();
        root.put("referenceGrams", 1);
        assertThrows(IllegalArgumentException.class, () -> JsonFoodCatalog.parse(root));
    }
    @Test void rejectsDuplicateIdentity() throws Exception {
        var root = document();
        var rows = (ArrayNode) root.get("foods");
        rows.add(rows.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> JsonFoodCatalog.parse(root));
    }
    @Test void retainsNullAndZeroSeparately() throws Exception {
        var root = document();
        var nutrients = (ObjectNode) root.get("foods").get(0).get("per100g");
        nutrients.putNull("carbohydrateGrams");
        nutrients.put("fatGrams", "0");
        var food = JsonFoodCatalog.parse(root).getFirst();
        assertNull(food.per100g().carbohydrateGrams());
        assertEquals(BigDecimal.ZERO, food.per100g().fatGrams());
    }
    @Test void rejectsNegativeNutrition() throws Exception {
        var root = document();
        ((ObjectNode) root.get("foods").get(0).get("per100g")).put("energyKcal", "-1");
        assertThrows(IllegalArgumentException.class, () -> JsonFoodCatalog.parse(root));
    }
}
