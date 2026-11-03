package com.nutrilog.food;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JsonFoodCatalog implements FoodCatalog {
    private final List<FoodService.Food> foods;
    public JsonFoodCatalog() {
        try (var input = new ClassPathResource("data/selected-foods.json").getInputStream()) {
            foods = parse(JsonMapper.builder().build().readTree(input));
        } catch (Exception exception) {
            throw new IllegalStateException("食品目录加载失败，拒绝回退到演示数据", exception);
        }
    }
    static List<FoodService.Food> parse(JsonNode root) {
        if (root.required("referenceGrams").asInt() != 100)
            throw new IllegalArgumentException("食品参考重量必须为100克");
        var rows = root.required("foods");
        if (!rows.isArray() || rows.isEmpty()) throw new IllegalArgumentException("食品目录为空");
        var result = new ArrayList<FoodService.Food>();
        var ids = new HashSet<Long>();
        for (var row : rows) {
            long id = row.required("fdcId").asLong();
            if (id <= 0 || !ids.add(id)) throw new IllegalArgumentException("食品编号重复或无效");
            var aliases = new ArrayList<String>();
            for (var alias : row.required("aliases")) aliases.add(alias.asString());
            var n = row.required("per100g");
            var source = new FoodService.Source(root.required("source").asString(),
                root.required("dataset").asString(), false, id,
                row.required("originalFood").required("description").asString(),
                row.required("sourceUrl").asString(), root.required("archiveSha256").asString());
            result.add(new FoodService.Food(id, row.required("name").asString(),
                row.required("state").asString(), List.copyOf(aliases),
                new FoodService.Nutrients(number(n, "energyKcal"), number(n, "proteinGrams"),
                    number(n, "fatGrams"), number(n, "carbohydrateGrams")), source));
        }
        return List.copyOf(result);
    }
    private static BigDecimal number(JsonNode node, String field) {
        var value = node.required(field);
        if (value.isNull()) return null;
        var number = new BigDecimal(value.asString());
        if (number.signum() < 0) throw new IllegalArgumentException("营养值不能为负");
        return number;
    }
    @Override public List<FoodService.Food> all() { return foods; }
}
