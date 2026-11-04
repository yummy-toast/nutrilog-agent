package com.nutrilog.chat;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ChatGateway gateway;
    public ChatService(ChatGateway gateway) { this.gateway = gateway; }
    public String reply(String message) { return reply(message, new RequestTrace()); }
    public String reply(String message, RequestTrace trace) {
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new ChatFailure("INVALID_MESSAGE", 400);
        try {
            String answer = gateway.answer(message.strip(), trace);
            if (answer == null || answer.isBlank()) throw new ChatFailure("MODEL_EMPTY_RESPONSE", 502);
            return answer;
        } catch (ChatFailure known) { throw known; }
        catch (RuntimeException failure) {
            // 不返回或记录上游消息、用户输入、密钥和堆栈。
            log.warn("requestId={} event=model_error exceptionType={}", trace.id(), failure.getClass().getName());
            if (isTimeout(failure)) throw new ChatFailure("MODEL_TIMEOUT", 504);
            throw new ChatFailure("MODEL_ERROR", 502);
        }
    }
    private boolean isTimeout(Throwable failure) {
        for (int depth = 0; failure != null && depth < 20; depth++, failure = failure.getCause()) {
            if (failure instanceof SocketTimeoutException || failure instanceof HttpTimeoutException
                || failure instanceof TimeoutException || failure.getClass().getSimpleName().equals("OpenAITimeoutException"))
                return true;
        }
        return false;
    }
}
