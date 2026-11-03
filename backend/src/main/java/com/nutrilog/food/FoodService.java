package com.nutrilog.food;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FoodService {
    public record Source(String name, String version, boolean demo) {}
    public record Nutrients(BigDecimal energyKcal, BigDecimal proteinGrams,
                            BigDecimal fatGrams, BigDecimal carbohydrateGrams) {}
    public record Food(long id, String name, String state, List<String> aliases,
                       Nutrients per100g, Source source) {}
    public record Value(BigDecimal amount, String unit, String status) {}
    public record Calculation(long foodId, String name, String state, BigDecimal weightGrams,
                              Value energy, Value protein, Value fat, Value carbohydrate, Source source) {}
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static final Source DEMO = new Source("合成教学数据，不可作为饮食依据", "demo-v1", true);
    private final List<Food> foods = List.of(
        new Food(1, "演示生大米", "RAW", List.of("米饭", "大米"),
                 new Nutrients(n("350"), n("7"), n("1"), n("78")), DEMO),
        new Food(2, "演示熟米饭", "COOKED", List.of("米饭", "熟饭"),
                 new Nutrients(n("116"), n("2.5"), n("0.3"), n("25")), DEMO),
        new Food(3, "演示缺项食品", "UNKNOWN", List.of("缺项"),
                 new Nutrients(n("100"), n("5"), BigDecimal.ZERO, null), DEMO));

    public List<Food> search(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关键词长度须为1至100");
        String query = keyword.strip();
        return foods.stream().filter(f -> f.name().contains(query)
            || f.aliases().stream().anyMatch(a -> a.contains(query))).toList();
    }
    public Food get(long id) {
        return foods.stream().filter(f -> f.id() == id).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "食品不存在"));
    }
    public Calculation calculate(Long id, BigDecimal grams) {
        if (id == null || grams == null || grams.signum() <= 0 || grams.compareTo(n("100000")) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "食品编号必填，重量须大于0且不超过100000克");
        Food food = get(id);
        Nutrients data = food.per100g();
        return new Calculation(food.id(), food.name(), food.state(), grams,
            scale(data.energyKcal(), grams, "kcal"), scale(data.proteinGrams(), grams, "g"),
            scale(data.fatGrams(), grams, "g"), scale(data.carbohydrateGrams(), grams, "g"), food.source());
    }
    private Value scale(BigDecimal base, BigDecimal grams, String unit) {
        return base == null ? new Value(null, unit, "MISSING")
            : new Value(base.multiply(grams).movePointLeft(2).setScale(2, RoundingMode.HALF_UP), unit, "KNOWN");
    }
}
