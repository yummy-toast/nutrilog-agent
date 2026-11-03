package com.nutrilog.chat;

import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService service;
    public ChatController(ChatService service) { this.service = service; }
    public record Request(String message) {}
    public record Response(String code, String answer) {}
    @PostMapping
    public Response chat(@RequestBody Request request) {
        // 入口直接校验，确保无效消息不触发模型；服务层另有防御校验。
        if (request == null || request.message() == null || request.message().isBlank()
            || request.message().length() > 2000) throw new ChatFailure("INVALID_MESSAGE", 400);
        return new Response("OK", service.reply(request.message()));
    }
}
