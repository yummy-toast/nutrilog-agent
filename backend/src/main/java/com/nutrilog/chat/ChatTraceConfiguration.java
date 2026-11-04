package com.nutrilog.chat;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class ChatTraceConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ChatTraceInterceptor()).addPathPatterns("/api/v1/chat");
    }
}
