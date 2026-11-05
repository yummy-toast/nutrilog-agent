package com.nutrilog;

import com.nutrilog.auth.*;
import com.nutrilog.chat.*;
import com.nutrilog.agent.FoodAgentTools;
import com.nutrilog.food.FoodService;
import com.nutrilog.food.JsonFoodCatalog;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.*;
import java.util.List;
import com.nutrilog.session.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({SecurityConfiguration.class,AuthController.class,SessionDirectory.class,SessionController.class,SessionErrorHandler.class,SessionChatController.class,ChatService.class,SpringAiChatGateway.class,ChatErrorHandler.class,ChatTraceConfiguration.class})
    static class Config {
        @Bean FoodService foods() { return new FoodService(new JsonFoodCatalog()); }
        @Bean FoodAgentTools foodTools(FoodService foods) { return new FoodAgentTools(foods); }
        @Bean ChatModel model() {
            return prompt -> {
                var messages = prompt.getInstructions();
                if (messages.getLast() instanceof ToolResponseMessage) {
                    if (messages.get(messages.size() - 3).getText().equals("搜索后超时"))
                        throw new IllegalStateException(new java.net.SocketTimeoutException());
                    return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("请确认食品").build())));
                }
                var input = messages.getLast().getText();
                if (List.of("搜索米饭", "搜索后超时", "搜索不存在").contains(input)) {
                    String keyword = switch (input) {
                        case "搜索不存在" -> "不存在";
                        default -> "米饭";
                    };
                    String arguments = JsonMapper.builder().build().writeValueAsString(java.util.Map.of("keyword", keyword));
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("search", "function", "searchFoods", arguments)))
                        .build())));
                }
                if (input.equals("模拟超时"))
                    throw new IllegalStateException(new java.net.SocketTimeoutException());
                String answer;
                if (input.equals("确认熟米饭") || input.equals("更新重量") || input.equals("改成生米")) {
                    String system = messages.getFirst().getText();
                    if (input.equals("改成生米")) {
                        assertTrue(system.contains("食品编号：169756"));
                        assertTrue(system.contains("重量（克）：未确认"));
                    } else {
                        assertTrue(system.contains("食品编号：168935"));
                        assertTrue(system.contains(input.equals("更新重量") ? "重量（克）：250" : "重量（克）：200"));
                    }
                    answer = "已收到";
                } else if (input.equals("200克呢？")) {
                    assertEquals(4, messages.size());
                    assertEquals(MessageType.USER, messages.get(1).getMessageType());
                    assertEquals("查熟米饭", messages.get(1).getText());
                    assertEquals(MessageType.ASSISTANT, messages.get(2).getMessageType());
                    assertEquals("请提供重量", messages.get(2).getText());
                    answer = "熟米饭200克";
                } else {
                    assertEquals("查熟米饭", input);
                    assertEquals(2, messages.size());
                    answer = "请提供重量";
                }
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(answer).build())));
            };
        }
    }
    private MockHttpServletResponse send(MockMvc api, MockHttpSession session, String id, String input) throws Exception {
        return send(api, session, id, java.util.Map.of("message", input));
    }
    private MockHttpServletResponse send(MockMvc api, MockHttpSession session, String id, java.util.Map<String,?> body) throws Exception {
        return api.perform(post("/api/v1/sessions/"+id+"/messages").session(session)
            .header("X-CSRF-TOKEN",csrf(api,session)).contentType("application/json")
            .content(JsonMapper.builder().build().writeValueAsString(body)))
            .andReturn().getResponse();
    }
    private MockHttpSession login(MockMvc api, String user, String password) throws Exception {
        var csrf=api.perform(get("/api/v1/auth/csrf")).andReturn();
        var session=(MockHttpSession)csrf.getRequest().getSession(false);
        String token=JsonMapper.builder().build().readTree(csrf.getResponse().getContentAsString()).get("token").asString();
        String oldId=session.getId();
        var result=api.perform(post("/api/v1/auth/login").session(session)
            .header("X-CSRF-TOKEN",token).param("username",user).param("password",password)).andReturn();
        assertEquals(200,result.getResponse().getStatus());
        assertNotEquals(oldId,session.getId());
        return session;
    }
    private String csrf(MockMvc api, MockHttpSession session) throws Exception {
        var result=api.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        return JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).get("token").asString();
    }
    @Test void realFilterChainEnforcesLoginCsrfAndOwnership() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().setActiveProfiles("demo-auth");
            context.register(Config.class); context.refresh();
            var api=MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
            assertEquals(401,api.perform(get("/api/v1/sessions")).andReturn().getResponse().getStatus());
            var alice=login(api,"alice","alice-demo-only"); var bob=login(api,"bob","bob-demo-only");
            assertEquals(403,api.perform(post("/api/v1/sessions").session(alice).contentType("application/json")
                .content("{\"title\":\"早餐\"}")).andReturn().getResponse().getStatus());
            var created=api.perform(post("/api/v1/sessions").session(alice).header("X-CSRF-TOKEN",csrf(api,alice))
                .contentType("application/json").content("{\"title\":\"早餐\",\"userId\":\"bob\"}")).andReturn().getResponse();
            assertEquals(200,created.getStatus());
            String id=JsonMapper.builder().build().readTree(created.getContentAsString()).get("sessionId").asString();
            assertEquals(200,api.perform(get("/api/v1/sessions/"+id).session(alice)).andReturn().getResponse().getStatus());
            assertEquals(403,api.perform(get("/api/v1/sessions/"+id).session(bob)).andReturn().getResponse().getStatus());
            var history = api.perform(get("/api/v1/sessions/"+id+"/history").session(alice))
                .andReturn().getResponse();
            assertEquals(200, history.getStatus());
            assertTrue(JsonMapper.builder().build().readTree(history.getContentAsString()).get("turns").isEmpty());
            assertEquals(403, api.perform(get("/api/v1/sessions/"+id+"/history").session(bob))
                .andReturn().getResponse().getStatus());
            assertEquals(403, send(api, bob, id, "越权请求").getStatus());
            assertEquals(200, send(api, alice, id, "查熟米饭").getStatus());
            var followup = send(api, alice, id, "200克呢？");
            assertEquals(200, followup.getStatus());
            var reply = JsonMapper.builder().build().readTree(followup.getContentAsByteArray());
            assertEquals("熟米饭200克", reply.get("answer").asString());
            assertEquals(followup.getHeader("X-Request-Id"), reply.get("requestId").asString());
            assertEquals(504, send(api, alice, id, "模拟超时").getStatus());
            var committed = api.perform(get("/api/v1/sessions/"+id+"/history").session(alice))
                .andReturn().getResponse();
            assertEquals(2, JsonMapper.builder().build().readTree(committed.getContentAsByteArray()).get("turns").size());
            assertEquals(400, send(api, alice, id, java.util.Map.of("message", "不应调用模型", "weightGrams", 200)).getStatus());
            assertEquals(404, send(api, alice, id, java.util.Map.of("message", "不应调用模型", "selectedFoodId", 99999)).getStatus());
            assertEquals(400, send(api, alice, id, java.util.Map.of("message", "不应调用模型", "selectedFoodId", 168935,
                "weightGrams", new java.math.BigDecimal("0.0001"))).getStatus());
            assertEquals(200, send(api, alice, id, java.util.Map.of("message", "确认熟米饭",
                "selectedFoodId", 168935, "weightGrams", 200)).getStatus());
            var directory = context.getBean(SessionDirectory.class);
            var sessionId = java.util.UUID.fromString(id);
            assertEquals("USER_CONFIRMED", directory.snapshot("alice", sessionId).state().weightBasis());
            assertEquals(200, send(api, alice, id, java.util.Map.of("message", "更新重量", "weightGrams", 250)).getStatus());
            var beforeTimeout = directory.snapshot("alice", sessionId);
            assertEquals(504, send(api, alice, id, java.util.Map.of("message", "模拟超时", "selectedFoodId", 169756)).getStatus());
            assertEquals(beforeTimeout, directory.snapshot("alice", sessionId));
            assertEquals(200, send(api, alice, id, java.util.Map.of("message", "改成生米", "selectedFoodId", 169756)).getStatus());
            var changed = directory.snapshot("alice", sessionId).state();
            assertEquals(169756L, changed.selectedFoodId());
            assertNull(changed.weightGrams());
            assertNull(changed.weightBasis());
            var beforeSearchFailure = directory.snapshot("alice", sessionId);
            assertEquals(504, send(api, alice, id, "搜索后超时").getStatus());
            assertEquals(beforeSearchFailure, directory.snapshot("alice", sessionId));
            assertEquals(200, send(api, alice, id, "搜索米饭").getStatus());
            var candidates = directory.snapshot("alice", sessionId).state();
            assertEquals(java.util.Set.of(169756L, 168935L), java.util.Set.copyOf(candidates.candidateFoodIds()));
            assertNull(candidates.selectedFoodId());
            assertNull(candidates.weightGrams());
            assertEquals(200, send(api, alice, id, "搜索不存在").getStatus());
            assertTrue(directory.snapshot("alice", sessionId).state().candidateFoodIds().isEmpty());
            assertEquals(200, send(api, alice, id, "搜索米饭").getStatus());
            assertEquals(200, send(api, alice, id, java.util.Map.of("message", "确认熟米饭",
                "selectedFoodId", 168935, "weightGrams", 200)).getStatus());
            var confirmed = directory.snapshot("alice", sessionId).state();
            assertEquals(168935L, confirmed.selectedFoodId());
            assertTrue(confirmed.candidateFoodIds().isEmpty());
            assertEquals(403,api.perform(delete("/api/v1/sessions/"+id).session(bob)
                .header("X-CSRF-TOKEN",csrf(api,bob))).andReturn().getResponse().getStatus());
            assertEquals(200,api.perform(post("/api/v1/auth/logout").session(alice)
                .header("X-CSRF-TOKEN",csrf(api,alice))).andReturn().getResponse().getStatus());
            assertTrue(alice.isInvalid());
            assertEquals(401,api.perform(get("/api/v1/auth/me")).andReturn().getResponse().getStatus());
        }
    }
}
