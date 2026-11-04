package com.nutrilog.chat;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = ChatController.class)
public class ChatErrorHandler {
    public record Error(String code, String message, String requestId) {}
    @ExceptionHandler(ChatFailure.class)
    public ResponseEntity<Error> handle(ChatFailure failure, HttpServletRequest request) {
        var trace = RequestTrace.from(request);
        trace.failure(failure.code());
        String message = switch (failure.code()) {
            case "INVALID_MESSAGE" -> "消息须为1至2000个字符，且不能全为空白";
            case "MODEL_UNAVAILABLE" -> "模型尚未配置，请启用live配置";
            case "MODEL_BUSY" -> "模型服务繁忙，请稍后重试";
            case "MODEL_LIMIT_EXCEEDED", "TOOL_LIMIT_EXCEEDED" -> "本次查询达到执行次数上限，请简化问题后重试";
            case "EXECUTION_TIMEOUT" -> "本次查询超过总时限，请稍后重试";
            case "EXECUTION_CANCELLED" -> "本次查询已中断";
            case "UNKNOWN_TOOL" -> "模型请求了不支持的工具";
            case "MODEL_TIMEOUT" -> "模型响应超时，请稍后重试";
            case "MODEL_EMPTY_RESPONSE" -> "模型未返回有效回答";
            default -> "模型服务调用失败，请稍后重试";
        };
        return ResponseEntity.status(failure.status()).body(new Error(failure.code(), message, trace.id()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Error> unreadable(HttpServletRequest request) {
        var trace = RequestTrace.from(request);
        trace.failure("INVALID_REQUEST");
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST", "请提供合法的JSON消息请求", trace.id()));
    }
}
