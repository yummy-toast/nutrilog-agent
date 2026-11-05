package com.nutrilog.chat;
import java.util.List;
import java.util.Optional;
import com.nutrilog.session.SessionDirectory.Turn;
import com.nutrilog.session.SessionDirectory.State;
public interface ChatGateway {
    String answer(String message);
    default String answer(String message, RequestTrace trace) { return answer(message); }
    default String answer(String message, RequestTrace trace, List<Turn> history) {
        if (!history.isEmpty()) throw new IllegalStateException("网关不支持会话历史");
        return answer(message, trace);
    }
    record SessionAnswer(String answer, Optional<List<Long>> searchCandidates) {}
    default SessionAnswer answerSession(String message, RequestTrace trace, List<Turn> history, State state) {
        return new SessionAnswer(answer(message, trace, history), Optional.empty());
    }
}
