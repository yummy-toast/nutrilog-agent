package com.nutrilog.food;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class FoodController {
    private final FoodService service;
    public FoodController(FoodService service) { this.service = service; }
    @GetMapping("/foods")
    public List<FoodService.Food> search(@RequestParam String keyword) { return service.search(keyword); }
    @GetMapping("/foods/{id}")
    public FoodService.Food detail(@PathVariable long id) { return service.get(id); }
    public record CalculateRequest(Long foodId, BigDecimal weightGrams) {}
    @PostMapping("/nutrition/calculate")
    public FoodService.Calculation calculate(@RequestBody CalculateRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return service.calculate(request.foodId(), request.weightGrams());
    }
}
