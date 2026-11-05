package com.nutrilog.chat;

import com.nutrilog.session.SessionDirectory;
import com.nutrilog.session.SessionDirectory.Turn;
import com.nutrilog.session.SessionDirectory.State;
import com.nutrilog.food.FoodService;
import java.math.BigDecimal;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/sessions/{id}/messages")
public class SessionChatController {
    private final SessionDirectory directory;
    private final ChatService chat;
    private final FoodService foods;
    public record Request(String message, Long selectedFoodId, BigDecimal weightGrams) {}

    public SessionChatController(SessionDirectory directory, ChatService chat, FoodService foods) {
        this.directory = directory;
        this.chat = chat;
        this.foods = foods;
    }

    @PostMapping
    public ChatController.Response send(@PathVariable UUID id, @RequestBody Request request,
                                        Principal principal, HttpServletRequest http) {
        if (principal == null) throw new ChatFailure("UNAUTHORIZED", 401);
        String message = request == null ? null : request.message();
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new ChatFailure("INVALID_MESSAGE", 400);

        var trace = RequestTrace.from(http);
        try {
            var completed = directory.executeTurn(principal.getName(), id, message.strip(), snapshot -> {
                State state = updateSelection(snapshot.state(), request);
                String answer = chat.reply(message, trace, recentHistory(snapshot.turns()), state);
                return new SessionDirectory.CompletedTurn(answer, state);
            });
            return new ChatController.Response("OK", completed.answer(), trace.id());
        } catch (ResponseStatusException error) {
            int status = error.getStatusCode().value();
            if (status == 409) throw new ChatFailure("SESSION_BUSY", 409);
            if (status == 403) throw new ChatFailure("SESSION_ACCESS_DENIED", 403);
            throw error;
        }
    }

    private State updateSelection(State previous, Request request) {
        Long requestedFood = request.selectedFoodId();
        BigDecimal requestedWeight = request.weightGrams();
        if (requestedFood == null && requestedWeight == null) return previous;

        Long selected = previous.selectedFoodId();
        BigDecimal weight = previous.weightGrams();
        String basis = previous.weightBasis();
        var candidates = previous.candidateFoodIds();
        if (requestedFood != null) {
            try {
                foods.get(requestedFood);
            } catch (ResponseStatusException error) {
                if (error.getStatusCode().value() == 404) throw new ChatFailure("FOOD_NOT_FOUND", 404);
                throw error;
            }
            if (!requestedFood.equals(selected)) {
                weight = null;
                basis = null;
            }
            selected = requestedFood;
            candidates = List.of();
        }
        if (requestedWeight != null) {
            if (selected == null) throw new ChatFailure("FOOD_SELECTION_REQUIRED", 400);
            if (!FoodService.isValidWeight(requestedWeight) || requestedWeight.stripTrailingZeros().scale() > 3)
                throw new ChatFailure("INVALID_WEIGHT", 400);
            weight = requestedWeight.stripTrailingZeros();
            basis = "USER_CONFIRMED";
        }
        return new State(candidates, selected, weight, basis);
    }

    private List<Turn> recentHistory(List<Turn> turns) {
        int from = turns.size();
        long length = 0;
        while (from > 0 && turns.size() - from < 10) {
            Turn turn = turns.get(from - 1);
            length += (long) turn.message().length() + turn.answer().length();
            if (length > 16000) break;
            from--;
        }
        return turns.subList(from, turns.size());
    }
}
