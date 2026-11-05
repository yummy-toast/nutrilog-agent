package com.nutrilog.chat;
import java.util.List;
import com.nutrilog.session.SessionDirectory.Turn;
public interface ChatGateway {
    String answer(String message);
    default String answer(String message, RequestTrace trace) { return answer(message); }
    default String answer(String message, RequestTrace trace, List<Turn> history) {
        if (!history.isEmpty()) throw new IllegalStateException("网关不支持会话历史");
        return answer(message, trace);
    }
}
