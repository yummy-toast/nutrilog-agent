package com.nutrilog.chat;

import com.nutrilog.session.SessionDirectory;
import com.nutrilog.session.SessionDirectory.Turn;
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

    public SessionChatController(SessionDirectory directory, ChatService chat) {
        this.directory = directory;
        this.chat = chat;
    }

    @PostMapping
    public ChatController.Response send(@PathVariable UUID id, @RequestBody ChatController.Request request,
                                        Principal principal, HttpServletRequest http) {
        if (principal == null) throw new ChatFailure("UNAUTHORIZED", 401);
        String message = request == null ? null : request.message();
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new ChatFailure("INVALID_MESSAGE", 400);

        var trace = RequestTrace.from(http);
        try {
            var completed = directory.executeTurn(principal.getName(), id, message.strip(), snapshot -> {
                String answer = chat.reply(message, trace, recentHistory(snapshot.turns()));
                return new SessionDirectory.CompletedTurn(answer, snapshot.state());
            });
            return new ChatController.Response("OK", completed.answer(), trace.id());
        } catch (ResponseStatusException error) {
            int status = error.getStatusCode().value();
            if (status == 409) throw new ChatFailure("SESSION_BUSY", 409);
            if (status == 403) throw new ChatFailure("SESSION_ACCESS_DENIED", 403);
            throw error;
        }
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
