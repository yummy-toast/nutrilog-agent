package com.nutrilog.auth;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean UserDetailsService users(Environment environment) {
        var users = new InMemoryUserDetailsManager();
        if (environment.acceptsProfiles(Profiles.of("demo-auth"))) {
            var encoder = new BCryptPasswordEncoder();
            users.createUser(User.withUsername("alice").password("{bcrypt}" + encoder.encode("alice-demo-only")).roles("USER").build());
            users.createUser(User.withUsername("bob").password("{bcrypt}" + encoder.encode("bob-demo-only")).roles("USER").build());
        }
        return users;
    }
    static void json(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(Map.of("code",code)));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET,"/","/index.html","/demo.css","/demo.js","/api/v1/health","/api/v1/foods/**","/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/chat","/api/v1/nutrition/calculate","/api/v1/auth/login").permitAll()
                .requestMatchers("/error").permitAll().anyRequest().authenticated())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/chat","/api/v1/nutrition/calculate"))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.loginPage("/api/v1/auth/login").loginProcessingUrl("/api/v1/auth/login")
                .successHandler((request,response,authentication) -> json(response,200,"OK"))
                .failureHandler((request,response,error) -> json(response,401,"LOGIN_FAILED")))
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request,response,authentication) -> json(response,200,"OK")))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,error) -> json(response,401,"UNAUTHORIZED"))
                .accessDeniedHandler((request,response,error) -> json(response,403,"FORBIDDEN")))
            .build();
    }
}
