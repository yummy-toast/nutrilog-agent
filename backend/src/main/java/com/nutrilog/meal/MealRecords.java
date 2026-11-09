package com.nutrilog.meal;

import com.nutrilog.food.FoodService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.*;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
    public record CorrectionRequest(Long expectedVersion, Long foodId, BigDecimal weightGrams,
                                    LocalDate date, MealType mealType, String note) {}
    public record Details(LocalDate date, MealType mealType, FoodService.Calculation nutrition,
                          FoodService.Nutrients per100g, String note) {}
    private record Current(Details details, long version) {}
    private record Operation(View view, Long expectedVersion) {}
    public record View(UUID operationId, UUID recordId, String status, Instant expiresAt,
                       Instant confirmedAt, Details details, UUID targetRecordId, Long version) {}

    public record Total(String status, BigDecimal amount, BigDecimal knownSubtotal, String unit,
                        int knownCount, int missingCount) {}
    public record Totals(Total energy, Total protein, Total fat, Total carbohydrate) {}
    public record Day(LocalDate date, List<View> records, Totals totals) {}

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
            statement.execute("ALTER TABLE meal_operation ADD COLUMN IF NOT EXISTS target_id VARCHAR(36)");
            statement.execute("ALTER TABLE meal_operation ADD COLUMN IF NOT EXISTS expected_version BIGINT");
            statement.execute("ALTER TABLE meal_operation ADD COLUMN IF NOT EXISTS record_version BIGINT DEFAULT 1 NOT NULL");
            statement.execute("ALTER TABLE meal_operation ADD COLUMN IF NOT EXISTS current_details CLOB");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS meal_audit (
                    operation_id VARCHAR(36) PRIMARY KEY,
                    owner_name VARCHAR(255) NOT NULL,
                    record_id VARCHAR(36) NOT NULL,
                    event_type VARCHAR(24) NOT NULL,
                    record_version BIGINT NOT NULL,
                    occurred_at BIGINT NOT NULL
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

        var nutrition = foods.calculate(request.foodId(), request.weightGrams().stripTrailingZeros());
        var details = new Details(request.date(), request.mealType(), nutrition, foods.get(nutrition.foodId()).per100g(), null);
        return insertPreview(owner, details, null, null);
    }

    public View correction(String owner, UUID recordId, CorrectionRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 1
            || request.date() != null && request.date().isAfter(LocalDate.now(clock))
            || request.note() != null && request.note().length() > 500
            || request.weightGrams() != null && (!FoodService.isValidWeight(request.weightGrams())
                || request.weightGrams().stripTrailingZeros().scale() > 3)
            || request.foodId() == null && request.weightGrams() == null && request.date() == null
                && request.mealType() == null && request.note() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "纠正参数无效");

        Current current = current(owner, recordId);
        if (current.version() != request.expectedVersion()) throw conflict();
        var previous = current.details();
        var nutrition = previous.nutrition();
        var per100g = previous.per100g();
        if (request.foodId() != null && request.foodId() != nutrition.foodId()) {
            if (request.weightGrams() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "更换食品时须提供新重量");
            nutrition = foods.calculate(request.foodId(), request.weightGrams().stripTrailingZeros());
            per100g = foods.get(nutrition.foodId()).per100g();
        } else if (request.weightGrams() != null && request.weightGrams().compareTo(nutrition.weightGrams()) != 0) {
            if (per100g == null)
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "旧记录没有每百克快照，无法按原数据改重量");
            nutrition = foods.recalculate(nutrition, per100g, request.weightGrams().stripTrailingZeros());
        }
        var details = new Details(request.date() == null ? previous.date() : request.date(),
            request.mealType() == null ? previous.mealType() : request.mealType(), nutrition, per100g,
            request.note() == null ? previous.note() : request.note());
        return insertPreview(owner, details, recordId, current.version());
    }

    private Current current(String owner, UUID recordId) {
        try (var connection = open(); var query = connection.prepareStatement("""
            SELECT COALESCE(current_details, details), record_version FROM meal_operation
            WHERE operation_id = ? AND owner_name = ? AND confirmed_at IS NOT NULL AND target_id IS NULL
            """)) {
            query.setString(1, recordId.toString());
            query.setString(2, owner);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw notFound();
                return new Current(json.readValue(rows.getString(1), Details.class), rows.getLong(2));
            }
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    private View insertPreview(String owner, Details details, UUID target, Long version) {
        var id = UUID.randomUUID();
        long expires = clock.millis() + Duration.ofMinutes(10).toMillis();
        try (var connection = open(); var statement = connection.prepareStatement("""
            INSERT INTO meal_operation (operation_id, owner_name, expires_at, details, target_id, expected_version)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, id.toString());
            statement.setString(2, owner);
            statement.setLong(3, expires);
            statement.setString(4, json.writeValueAsString(details));
            statement.setString(5, target == null ? null : target.toString());
            statement.setObject(6, version);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw unavailable();
        }
        return new View(id, null, "PENDING", Instant.ofEpochMilli(expires), null, details, target, version);
    }

    public View get(String owner, UUID id) {
        try (var connection = open()) {
            return read(connection, owner, id, false).view();
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    public View confirm(String owner, UUID id) {
        try (var connection = open()) {
            connection.setAutoCommit(false);
            try {
                Operation operation = read(connection, owner, id, true);
                View preview = operation.view();
                if (preview.confirmedAt() != null) {
                    connection.commit();
                    return preview;
                }
                long now = clock.millis();
                if (now >= preview.expiresAt().toEpochMilli())
                    throw new ResponseStatusException(HttpStatus.GONE, "记餐预览已过期");

                if (preview.targetRecordId() != null) applyCorrection(connection, owner, operation);
                try (var update = connection.prepareStatement(
                    "UPDATE meal_operation SET confirmed_at = ? WHERE operation_id = ? AND owner_name = ?")) {
                    update.setLong(1, now);
                    update.setString(2, id.toString());
                    update.setString(3, owner);
                    update.executeUpdate();
                }
                View result = read(connection, owner, id, false).view();
                auditConfirmation(connection, owner, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            }
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    private void auditConfirmation(Connection connection, String owner, View result) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO meal_audit (operation_id, owner_name, record_id, event_type, record_version, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, result.operationId().toString());
            statement.setString(2, owner);
            statement.setString(3, result.recordId().toString());
            statement.setString(4, result.targetRecordId() == null ? "MEAL_CREATED" : "MEAL_CORRECTED");
            statement.setLong(5, result.version());
            statement.setLong(6, result.confirmedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void applyCorrection(Connection connection, String owner, Operation operation) throws SQLException {
        try (var update = connection.prepareStatement("""
            UPDATE meal_operation SET current_details = ?, record_version = record_version + 1
            WHERE operation_id = ? AND owner_name = ? AND confirmed_at IS NOT NULL
                AND target_id IS NULL AND record_version = ?
            """)) {
            update.setString(1, json.writeValueAsString(operation.view().details()));
            update.setString(2, operation.view().targetRecordId().toString());
            update.setString(3, owner);
            update.setLong(4, operation.expectedVersion());
            if (update.executeUpdate() != 1) throw conflict();
        }
    }

    public Day daily(String owner, LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now(clock)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询日期无效");

        var records = new ArrayList<View>();
        try (var connection = open(); var statement = connection.prepareStatement("""
            SELECT operation_id, expires_at, confirmed_at, COALESCE(current_details, details), record_version
            FROM meal_operation
            WHERE owner_name = ? AND confirmed_at IS NOT NULL AND target_id IS NULL
            ORDER BY confirmed_at, operation_id
            """)) {
            statement.setString(1, owner);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var details = json.readValue(rows.getString(4), Details.class);
                    if (!details.date().equals(date)) continue;
                    var id = UUID.fromString(rows.getString(1));
                    records.add(new View(id, id, "CONFIRMED", Instant.ofEpochMilli(rows.getLong(2)),
                        Instant.ofEpochMilli(rows.getLong(3)), details, null, rows.getLong(5)));
                }
            }
        } catch (SQLException error) {
            throw unavailable();
        }
        var totals = new Totals(sum(records, FoodService.Calculation::energy, "kcal"),
            sum(records, FoodService.Calculation::protein, "g"),
            sum(records, FoodService.Calculation::fat, "g"),
            sum(records, FoodService.Calculation::carbohydrate, "g"));
        return new Day(date, List.copyOf(records), totals);
    }

    private Total sum(List<View> records, Function<FoodService.Calculation, FoodService.Value> field, String unit) {
        BigDecimal knownSum = BigDecimal.ZERO;
        int known = 0;
        for (View record : records) {
            var value = field.apply(record.details().nutrition());
            if (value.amount() != null && "KNOWN".equals(value.status())) {
                knownSum = knownSum.add(value.amount());
                known++;
            }
        }
        int missing = records.size() - known;
        if (records.isEmpty()) return new Total("NO_RECORDS", null, null, unit, 0, 0);
        if (missing == 0) return new Total("COMPLETE", knownSum, knownSum, unit, known, 0);
        if (known == 0) return new Total("MISSING", null, null, unit, 0, missing);
        return new Total("PARTIAL", null, knownSum, unit, known, missing);
    }

    private Operation read(Connection connection, String owner, UUID id, boolean lock) throws SQLException {
        String sql = """
            SELECT expires_at, confirmed_at, details, target_id, expected_version
            FROM meal_operation WHERE operation_id = ? AND owner_name = ?
            """;
        try (var statement = connection.prepareStatement(lock ? sql + " FOR UPDATE" : sql)) {
            statement.setString(1, id.toString());
            statement.setString(2, owner);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw notFound();
                long expires = rows.getLong(1);
                Long confirmed = rows.getObject(2, Long.class);
                var details = json.readValue(rows.getString(3), Details.class);
                String targetId = rows.getString(4);
                UUID target = targetId == null ? null : UUID.fromString(targetId);
                Long expected = rows.getObject(5, Long.class);
                String status = confirmed != null ? "CONFIRMED" : clock.millis() >= expires ? "EXPIRED" : "PENDING";
                Long version = expected;
                if (confirmed != null) version = expected == null ? 1L : expected + 1;
                var view = new View(id, confirmed == null ? null : target == null ? id : target, status,
                    Instant.ofEpochMilli(expires), confirmed == null ? null : Instant.ofEpochMilli(confirmed),
                    details, target, version);
                return new Operation(view, expected);
            }
        }
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "记录版本已变更，请刷新并重新预览");
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "记餐记录或预览不存在");
    }

    private Connection open() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "记餐存储暂不可用");
    }
}
