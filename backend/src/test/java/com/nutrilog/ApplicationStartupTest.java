package com.nutrilog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationStartupTest {
    @Test void startsWithoutModelCredentials() {
        try (var context = new SpringApplicationBuilder(NutriLogApplication.class)
                .web(WebApplicationType.NONE).run()) {
            assertEquals("UP", context.getBean(HealthController.class).health().get("status"));
        }
    }
}
