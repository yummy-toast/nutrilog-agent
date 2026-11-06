package com.nutrilog.food;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FoodService {
    public record Source(String name, String version, boolean demo, long externalId,
                         String originalDescription, String url, String archiveSha256) {}
    public record Nutrients(BigDecimal energyKcal, BigDecimal proteinGrams,
                            BigDecimal fatGrams, BigDecimal carbohydrateGrams) {}
    public record Food(long id, String name, String state, List<String> aliases,
                       Nutrients per100g, Source source) {}
    public record Value(BigDecimal amount, String unit, String status) {}
    public record Calculation(long foodId, String name, String state, BigDecimal weightGrams,
                              Value energy, Value protein, Value fat, Value carbohydrate, Source source) {}
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private final List<Food> foods;
    public FoodService(FoodCatalog catalog) { this.foods = List.copyOf(catalog.all()); }

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
    public static boolean isValidWeight(BigDecimal grams) {
        return grams != null && grams.signum() > 0 && grams.compareTo(n("100000")) <= 0;
    }
    public Calculation calculate(Long id, BigDecimal grams) {
        if (id == null || !isValidWeight(grams))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "食品编号必填，重量须大于0且不超过100000克");
        Food food = get(id);
        Nutrients data = food.per100g();
        return fromSnapshot(food.id(), food.name(), food.state(), food.source(), data, grams);
    }
    public Calculation recalculate(Calculation previous, Nutrients per100g, BigDecimal grams) {
        if (!isValidWeight(grams)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重量无效");
        return fromSnapshot(previous.foodId(), previous.name(), previous.state(), previous.source(), per100g, grams);
    }
    private Calculation fromSnapshot(long id, String name, String state, Source source, Nutrients data, BigDecimal grams) {
        return new Calculation(id, name, state, grams,
            scale(data.energyKcal(), grams, "kcal"), scale(data.proteinGrams(), grams, "g"),
            scale(data.fatGrams(), grams, "g"), scale(data.carbohydrateGrams(), grams, "g"), source);
    }
    private Value scale(BigDecimal base, BigDecimal grams, String unit) {
        return base == null ? new Value(null, unit, "MISSING")
            : new Value(base.multiply(grams).movePointLeft(2).setScale(2, RoundingMode.HALF_UP), unit, "KNOWN");
    }
}
