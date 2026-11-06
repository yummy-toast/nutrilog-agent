package com.nutrilog.meal;

import com.nutrilog.food.FoodService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local-db")
public class MealRecords {
    public enum MealType { BREAKFAST, LUNCH, DINNER, SNACK }
    public record PreviewRequest(Long foodId, BigDecimal weightGrams, LocalDate date, MealType mealType) {}
    public record Details(LocalDate date, MealType mealType, FoodService.Calculation nutrition) {}
    public record View(UUID operationId, UUID recordId, String status, Instant expiresAt,
                       Instant confirmedAt, Details details) {}

    private final String url;
    private final FoodService foods;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public MealRecords(@Value("${nutrilog.session.jdbc-url}") String url, FoodService foods) {
        this(url, foods, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    MealRecords(String url, FoodService foods, Clock clock) {
        this.url = url;
        this.foods = foods;
        this.clock = clock;
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS meal_operation (
                    operation_id VARCHAR(36) PRIMARY KEY,
                    owner_name VARCHAR(255) NOT NULL,
                    expires_at BIGINT NOT NULL,
                    confirmed_at BIGINT,
                    details CLOB NOT NULL
                )
                """);
        } catch (SQLException error) {
            throw new IllegalStateException("无法初始化记餐数据库", error);
        }
    }

    public View preview(String owner, PreviewRequest request) {
        if (request == null || request.date() == null || request.mealType() == null
            || request.date().isAfter(LocalDate.now(clock))
            || !FoodService.isValidWeight(request.weightGrams()) || request.weightGrams().stripTrailingZeros().scale() > 3)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期、餐次或重量无效");

        var details = new Details(request.date(), request.mealType(),
            foods.calculate(request.foodId(), request.weightGrams().stripTrailingZeros()));
        var id = UUID.randomUUID();
        long expires = clock.millis() + Duration.ofMinutes(10).toMillis();
        try (var connection = open(); var statement = connection.prepareStatement("""
            INSERT INTO meal_operation (operation_id, owner_name, expires_at, details) VALUES (?, ?, ?, ?)
            """)) {
            statement.setString(1, id.toString());
            statement.setString(2, owner);
            statement.setLong(3, expires);
            statement.setString(4, json.writeValueAsString(details));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw unavailable();
        }
        return new View(id, null, "PENDING", Instant.ofEpochMilli(expires), null, details);
    }

    public View get(String owner, UUID id) {
        try (var connection = open()) {
            return read(connection, owner, id);
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    public View confirm(String owner, UUID id) {
        try (var connection = open(); var update = connection.prepareStatement("""
            UPDATE meal_operation SET confirmed_at = ?
            WHERE operation_id = ? AND owner_name = ? AND confirmed_at IS NULL AND expires_at > ?
            """)) {
            long now = clock.millis();
            update.setLong(1, now);
            update.setString(2, id.toString());
            update.setString(3, owner);
            update.setLong(4, now);
            update.executeUpdate();

            View result = read(connection, owner, id);
            if (result.confirmedAt() == null)
                throw new ResponseStatusException(HttpStatus.GONE, "记餐预览已过期");
            return result;
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    private View read(Connection connection, String owner, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT expires_at, confirmed_at, details FROM meal_operation WHERE operation_id = ? AND owner_name = ?
            """)) {
            statement.setString(1, id.toString());
            statement.setString(2, owner);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记餐预览不存在");
                long expires = rows.getLong(1);
                Long confirmed = rows.getObject(2, Long.class);
                var details = json.readValue(rows.getString(3), Details.class);
                String status = confirmed != null ? "CONFIRMED" : clock.millis() >= expires ? "EXPIRED" : "PENDING";
                return new View(id, confirmed == null ? null : id, status, Instant.ofEpochMilli(expires),
                    confirmed == null ? null : Instant.ofEpochMilli(confirmed), details);
            }
        }
    }

    private Connection open() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "记餐存储暂不可用");
    }
}
