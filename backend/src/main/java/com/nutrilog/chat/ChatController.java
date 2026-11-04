package com.nutrilog.chat;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService service;
    public ChatController(ChatService service) { this.service = service; }
    public record Request(String message) {}
    public record Response(String code, String answer, String requestId) {}
    @PostMapping
    public Response chat(@RequestBody Request request, HttpServletRequest http) {
        // 入口直接校验，确保无效消息不触发模型；服务层另有防御校验。
        if (request == null || request.message() == null || request.message().isBlank()
            || request.message().length() > 2000) throw new ChatFailure("INVALID_MESSAGE", 400);
        var trace = RequestTrace.from(http);
        return new Response("OK", service.reply(request.message(), trace), trace.id());
    }
}
