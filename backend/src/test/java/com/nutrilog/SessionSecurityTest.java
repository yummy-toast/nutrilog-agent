package com.nutrilog;

import com.nutrilog.auth.*;
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
    @Import({SecurityConfiguration.class,AuthController.class,SessionDirectory.class,SessionController.class,SessionErrorHandler.class})
    static class Config {}
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
            assertEquals(403,api.perform(delete("/api/v1/sessions/"+id).session(bob)
                .header("X-CSRF-TOKEN",csrf(api,bob))).andReturn().getResponse().getStatus());
            assertEquals(200,api.perform(post("/api/v1/auth/logout").session(alice)
                .header("X-CSRF-TOKEN",csrf(api,alice))).andReturn().getResponse().getStatus());
            assertTrue(alice.isInvalid());
            assertEquals(401,api.perform(get("/api/v1/auth/me")).andReturn().getResponse().getStatus());
        }
    }
}
