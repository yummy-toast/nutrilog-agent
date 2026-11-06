package com.nutrilog.meal;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local-db")
@RequestMapping("/api/v1/meals/previews")
public class MealController {
    private final MealRecords records;

    public MealController(MealRecords records) { this.records = records; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MealRecords.View preview(Principal principal, @RequestBody MealRecords.PreviewRequest request) {
        return records.preview(principal.getName(), request);
    }

    @GetMapping("/{id}")
    public MealRecords.View get(Principal principal, @PathVariable UUID id) {
        return records.get(principal.getName(), id);
    }

    @PostMapping("/{id}/confirm")
    public MealRecords.View confirm(Principal principal, @PathVariable UUID id) {
        return records.confirm(principal.getName(), id);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException error) {
        String code = switch (error.getStatusCode().value()) {
            case 404 -> "NOT_FOUND";
            case 410 -> "PREVIEW_EXPIRED";
            case 503 -> "MEAL_STORAGE_UNAVAILABLE";
            default -> "INVALID_REQUEST";
        };
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code", code, "message", error.getReason()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "请求格式无效"));
    }
}
