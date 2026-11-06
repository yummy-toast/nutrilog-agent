package com.nutrilog.meal;

import com.nutrilog.food.FoodService;
import com.nutrilog.food.JsonFoodCatalog;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class MealRecordsTest {
    @TempDir Path data;
    private final Instant now = Instant.parse("2026-11-06T02:00:00Z");
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    private MealRecords records(Instant time) {
        return new MealRecords("jdbc:h2:file:" + data.resolve("meals"),
            new FoodService(new JsonFoodCatalog()), Clock.fixed(time, zone));
    }

    private MealRecords.PreviewRequest request() {
        return new MealRecords.PreviewRequest(168935L, new BigDecimal("200"),
            LocalDate.of(2026, 11, 6), MealRecords.MealType.LUNCH);
    }

    @Test void confirmsOnceAcrossConcurrentRetriesAndDatabaseReopen() throws Exception {
        var records = records(now);
        var preview = records.preview("alice", request());
        assertEquals("PENDING", preview.status());
        assertNull(preview.recordId());
        assertEquals(new BigDecimal("260.00"), preview.details().nutrition().energy().amount());

        var start = new CountDownLatch(1);
        var firstCaller = records(now.plusSeconds(1));
        var secondCaller = records(now.plusSeconds(2));
        MealRecords.View confirmed;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return firstCaller.confirm("alice", preview.operationId()); });
            var second = executor.submit(() -> { start.await(); return secondCaller.confirm("alice", preview.operationId()); });
            start.countDown();
            confirmed = first.get(10, TimeUnit.SECONDS);
            assertEquals(confirmed, second.get(10, TimeUnit.SECONDS));
        }
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(preview.operationId(), confirmed.recordId());
        assertEquals(preview.details(), confirmed.details());
        var restarted = records(now.plusSeconds(601));
        assertEquals(confirmed, restarted.confirm("alice", preview.operationId()));
    }

    @Test void rejectsOtherUsersAndExpiredPreviews() {
        var records = records(now);
        var preview = records.preview("alice", request());
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> records.get("bob", preview.operationId())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> records.confirm("bob", preview.operationId())).getStatusCode().value());
        var later = records(now.plusSeconds(600));
        assertEquals("EXPIRED", later.get("alice", preview.operationId()).status());
        assertEquals(410, assertThrows(ResponseStatusException.class,
            () -> later.confirm("alice", preview.operationId())).getStatusCode().value());
        assertNull(later.get("alice", preview.operationId()).recordId());
    }

    @Test void validatesPreviewInputsBeforeCreatingAnOperation() {
        var records = records(now);
        var request = request();
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> records.preview("alice",
            new MealRecords.PreviewRequest(request.foodId(), BigDecimal.ZERO, request.date(), request.mealType())))
            .getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> records.preview("alice",
            new MealRecords.PreviewRequest(request.foodId(), request.weightGrams(), request.date().plusDays(1), request.mealType())))
            .getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> records.preview("alice",
            new MealRecords.PreviewRequest(999L, request.weightGrams(), request.date(), request.mealType())))
            .getStatusCode().value());
    }

    @Test void dailyUsesOnlyOwnersConfirmedSnapshotsForTheMealDate() {
        var catalog = new JsonFoodCatalog().all();
        var source = catalog.getFirst().source();
        var complete = new FoodService.Food(1L, "完整食品", "COOKED", java.util.List.of(),
            new FoodService.Nutrients(new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO,
                new BigDecimal("20")), source);
        var incomplete = new FoodService.Food(2L, "缺项食品", "COOKED", java.util.List.of(),
            new FoodService.Nutrients(new BigDecimal("50"), null, BigDecimal.ZERO, null), source);
        var foods = new FoodService(() -> java.util.List.of(complete, incomplete));
        String url = "jdbc:h2:file:" + data.resolve("daily");
        var writer = new MealRecords(url, foods, Clock.fixed(now, zone));
        LocalDate day = request().date().minusDays(1);
        var first = new MealRecords.PreviewRequest(1L, new BigDecimal("100"), day, MealRecords.MealType.LUNCH);
        var second = new MealRecords.PreviewRequest(2L, new BigDecimal("100"), day, MealRecords.MealType.DINNER);

        var a = writer.preview("alice", first);
        writer.confirm("alice", a.operationId());
        writer.confirm("alice", a.operationId());
        writer.confirm("alice", writer.preview("alice", second).operationId());
        writer.preview("alice", first);
        writer.confirm("bob", writer.preview("bob", first).operationId());
        writer.confirm("alice", writer.preview("alice", requestWithDate(first, day.minusDays(1))).operationId());

        var reader = new MealRecords(url, new FoodService(java.util.List::of), Clock.fixed(now, zone));
        var report = reader.daily("alice", day);
        assertEquals(2, report.records().size());
        assertTrue(report.records().stream().allMatch(row -> row.details().date().equals(day)));
        assertEquals(new MealRecords.Total("COMPLETE", new BigDecimal("150.00"), new BigDecimal("150.00"),
            "kcal", 2, 0), report.totals().energy());
        assertEquals(new MealRecords.Total("PARTIAL", null, new BigDecimal("10.00"), "g", 1, 1),
            report.totals().protein());
        assertEquals(new BigDecimal("0.00"), report.totals().fat().amount());
        assertEquals("PARTIAL", report.totals().carbohydrate().status());
        assertEquals(1, reader.daily("bob", day).records().size());
        assertEquals("NO_RECORDS", reader.daily("alice", day.plusDays(1)).totals().energy().status());

        writer.confirm("alice", writer.preview("alice", requestWithDate(second, day.minusDays(2))).operationId());
        assertEquals(new MealRecords.Total("MISSING", null, null, "g", 0, 1),
            reader.daily("alice", day.minusDays(2)).totals().protein());
    }

    private MealRecords.PreviewRequest requestWithDate(MealRecords.PreviewRequest request, LocalDate date) {
        return new MealRecords.PreviewRequest(request.foodId(), request.weightGrams(), date, request.mealType());
    }
}
