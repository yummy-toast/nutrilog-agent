package com.nutrilog.chat;
public interface ChatGateway {
    String answer(String message);
    default String answer(String message, RequestTrace trace) { return answer(message); }
}
