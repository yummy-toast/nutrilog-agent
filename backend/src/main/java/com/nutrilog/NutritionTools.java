package com.nutrilog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class NutritionTools {
    // 最小协议验证：基础热量由调用方提供，不代表已接入食品数据库。
    @Tool(description = "按每100克热量和食物重量进行确定性计算；参数都必须来自已知数据")
    public BigDecimal calculateCalories(BigDecimal kcalPer100g, BigDecimal weightGrams) {
        if (kcalPer100g == null || weightGrams == null
                || kcalPer100g.signum() < 0 || weightGrams.signum() <= 0) {
            throw new IllegalArgumentException("基础热量必须非负，重量必须大于零");
        }
        return kcalPer100g.multiply(weightGrams).divide(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
