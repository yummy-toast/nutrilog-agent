package com.nutrilog;
import com.nutrilog.chat.*;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;

class ChatApiTest {
    private static class Stub implements ChatGateway {
        int calls;
        java.util.function.Function<String,String> behavior = message -> "回答";
        public String answer(String message) { calls++; return behavior.apply(message); }
    }
    private final Stub gateway = new Stub();
    private org.springframework.test.web.servlet.MockMvc api() {
        return MockMvcBuilders.standaloneSetup(new ChatController(new ChatService(gateway)))
            .setControllerAdvice(new ChatErrorHandler()).addInterceptors(new ChatTraceInterceptor()).build();
    }
    private org.springframework.mock.web.MockHttpServletResponse send(String json) throws Exception {
        var response = api().perform(post("/api/v1/chat").header("X-Request-Id", "untrusted-client-id")
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse();
        var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId").asString());
        assertNotEquals("untrusted-client-id", body.get("requestId").asString());
        assertDoesNotThrow(() -> java.util.UUID.fromString(body.get("requestId").asString()));
        return response;
    }
    @Test void rejectsInvalidMessagesBeforeModel() throws Exception {
        for (String json : new String[]{"{}", "null", "{\"message\":\" \"}", "{\"message\":null}", "{\"message\":\""+"a".repeat(2001)+"\"}"})
            assertEquals(400, send(json).getStatus());
        assertEquals(0, gateway.calls);
    }
    @Test void rejectsMalformedJson() throws Exception {
        assertEquals(400, send("{").getStatus()); assertEquals(0, gateway.calls);
    }
    @Test void returnsClarificationAsSuccess() throws Exception {
        gateway.behavior = message -> "请说明食品";
        var response = send("{\"message\":\"200克呢？\"}");
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("请说明食品"));
    }
    @Test void mapsNestedTimeoutWithoutLeakingMessage() throws Exception {
        gateway.behavior = message -> { throw new RuntimeException("secret-test-marker", new SocketTimeoutException()); };
        var response = send("{\"message\":\"查询米饭\"}");
        assertEquals(504, response.getStatus());
        assertTrue(response.getContentAsString().contains("MODEL_TIMEOUT"));
        assertFalse(response.getContentAsString().contains("secret-test-marker"));
    }
    @Test void mapsEmptyAndProviderFailures() throws Exception {
        gateway.behavior = message -> gateway.calls == 1 ? " " : failProvider();
        assertEquals(502, send("{\"message\":\"查询米饭\"}").getStatus());
        assertEquals(502, send("{\"message\":\"查询米饭\"}").getStatus());
    }
    private String failProvider() { throw new RuntimeException("secret-test-marker"); }
    @Test void mapsUnconfiguredModel() throws Exception {
        gateway.behavior = message -> { throw new ChatFailure("MODEL_UNAVAILABLE",503); };
        assertEquals(503, send("{\"message\":\"查询米饭\"}").getStatus());
    }
}
