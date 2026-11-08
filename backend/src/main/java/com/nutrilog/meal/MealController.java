package com.nutrilog.meal;

import com.nutrilog.session.SessionDirectory;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.MissingServletRequestParameterException;
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
@RequestMapping("/api/v1/meals")
public class MealController {
    private final MealRecords records;

    private final SessionDirectory sessions;

    public MealController(MealRecords records, SessionDirectory sessions) {
        this.records = records;
        this.sessions = sessions;
    }

    public record SessionPreviewRequest(UUID sessionId, LocalDate date, MealRecords.MealType mealType) {}

    @PostMapping("/previews")
    @ResponseStatus(HttpStatus.CREATED)
    public MealRecords.View preview(Principal principal, @RequestBody MealRecords.PreviewRequest request) {
        return records.preview(principal.getName(), request);
    }

    @PostMapping("/previews/from-session")
    @ResponseStatus(HttpStatus.CREATED)
    public MealRecords.View previewFromSession(Principal principal, @RequestBody SessionPreviewRequest request) {
        if (request.sessionId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定会话");
        String owner = principal.getName();
        var state = sessions.snapshot(owner, request.sessionId()).state();
        if (state.selectedFoodId() == null || state.weightGrams() == null
            || !"USER_CONFIRMED".equals(state.weightBasis()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先在会话中确认食品和重量");

        return records.preview(owner, new MealRecords.PreviewRequest(
            state.selectedFoodId(), state.weightGrams(), request.date(), request.mealType()));
    }

    @GetMapping("/previews/{id}")
    public MealRecords.View get(Principal principal, @PathVariable UUID id) {
        return records.get(principal.getName(), id);
    }

    @PostMapping("/previews/{id}/confirm")
    public MealRecords.View confirm(Principal principal, @PathVariable UUID id) {
        return records.confirm(principal.getName(), id);
    }

    @PostMapping("/records/{id}/correction-previews")
    @ResponseStatus(HttpStatus.CREATED)
    public MealRecords.View correction(Principal principal, @PathVariable UUID id,
                                       @RequestBody MealRecords.CorrectionRequest request) {
        return records.correction(principal.getName(), id, request);
    }

    @GetMapping("/daily")
    public MealRecords.Day daily(Principal principal,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return records.daily(principal.getName(), date);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException error) {
        String code = switch (error.getStatusCode().value()) {
            case 403 -> "SESSION_ACCESS_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "RECORD_CONFLICT";
            case 422 -> "NUTRITION_SNAPSHOT_UNAVAILABLE";
            case 410 -> "PREVIEW_EXPIRED";
            case 503 -> "MEAL_STORAGE_UNAVAILABLE";
            default -> "INVALID_REQUEST";
        };
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code", code, "message", error.getReason()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "请求格式无效"));
    }
}
