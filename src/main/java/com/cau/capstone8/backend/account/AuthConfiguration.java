package com.cau.capstone8.backend.account;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class AuthConfiguration {
    @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor productionSafety(
            org.springframework.core.env.Environment environment) {
        return beans -> ProductionSafety.validate(environment);
    }
    @Bean PasswordEncoder passwordEncoder() {
        return new Pbkdf2PasswordEncoder("", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    }
    @Bean UserDetailsService noBasicLogin() {
        return name -> { throw new UsernameNotFoundException("Bearer authentication required"); };
    }
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain security(HttpSecurity http, AccountService accounts, ClientAddress clientAddress,
            @Value("${app.auth.mode:required}") String mode) throws Exception {
        if (!Set.of("required","demo").contains(mode)) throw new IllegalArgumentException("Invalid auth mode");
        // Authentication uses an explicit Authorization header, never ambient cookies/Basic.
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
                .headers(h -> h.cacheControl(c -> {}))
                .exceptionHandling(e -> e
                    .authenticationEntryPoint((req,res,ex) -> error(res,401,"AUTH_REQUIRED","로그인이 필요합니다."))
                    .accessDeniedHandler((req,res,ex) -> error(res,403,"FORBIDDEN","접근 권한이 없습니다.")))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/api/auth/register","/api/auth/login","/actuator/health","/error").permitAll()
                     .requestMatchers(HttpMethod.GET,"/api/topics","/api/topics/*/concept-map","/api/books","/api/books/**").permitAll()
                     .requestMatchers(HttpMethod.GET,"/swagger-ui.html","/swagger-ui/**","/v3/api-docs","/v3/api-docs/**").permitAll()
                     .requestMatchers("/api/me","/api/me/**","/api/auth/logout").authenticated();
                    if (mode.equals("demo")) a.anyRequest().permitAll();
                    else a.anyRequest().authenticated();
                })
                .addFilterBefore(new BearerFilter(accounts, clientAddress), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static void error(HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(
                Map.of("code",code,"message",message,"traceId",UUID.randomUUID().toString())));
    }

    static class BearerFilter extends OncePerRequestFilter {
        private final AccountService accounts;
        private final ClientAddress clientAddress;
        // Bounded, per-process throttle. Trust is explicit and defaults to the socket peer only.
        private final Map<String,Window> attempts = new HashMap<>();
        BearerFilter(AccountService accounts) { this(accounts, new ClientAddress("")); }
        BearerFilter(AccountService accounts, ClientAddress clientAddress) {
            this.accounts=accounts; this.clientAddress=clientAddress;
        }
        private synchronized boolean allow(String ip) {
            long now = Instant.now().getEpochSecond();
            attempts.entrySet().removeIf(e -> now-e.getValue().start() >= 60);
            Window previous=attempts.get(ip);
            if (previous == null && attempts.size() >= 10_000) return false;
            int count=previous == null ? 1 : previous.count()+1;
            attempts.put(ip,new Window(previous == null ? now : previous.start(),count));
            return count<=10;
        }
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
                throws ServletException,IOException {
            String path=req.getServletPath();
            if (req.getMethod().equals("POST")
                    && (path.equals("/api/auth/login") || path.equals("/api/auth/register"))
                    && !allow(clientAddress.resolve(req))) {
                res.setHeader("Retry-After","60");
                error(res,429,"AUTH_RATE_LIMIT","요청이 많습니다. 잠시 후 다시 시도해 주세요.");
                return;
            }
            String header=req.getHeader("Authorization");
            if (header != null) {
                if (!header.startsWith("Bearer ")) {
                    error(res,401,"INVALID_TOKEN","로그인 정보를 확인해 주세요."); return;
                }
                Optional<Long> user=accounts.authenticate(header.substring(7));
                if (user.isEmpty()) {
                    error(res,401,"INVALID_TOKEN","로그인이 만료되었거나 유효하지 않습니다."); return;
                }
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user.get(),null,List.of()));
            }
            chain.doFilter(req,res);
        }
        private record Window(long start,int count) {}
    }
}
