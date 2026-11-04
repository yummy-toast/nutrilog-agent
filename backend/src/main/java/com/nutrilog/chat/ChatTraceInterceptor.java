package com.nutrilog.chat;
import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;

public final class ChatTraceInterceptor implements HandlerInterceptor {
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var trace = RequestTrace.from(request);
        response.setHeader("X-Request-Id", trace.id());
        trace.start();
        return true;
    }
    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
        RequestTrace.from(request).finish(response.getStatus());
    }
}
