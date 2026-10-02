package com.cau.capstone8.backend.account;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Component
public class AccountOwnership implements WebMvcConfigurer {
    private final JdbcTemplate jdbc;
    private final boolean demo;
    public AccountOwnership(JdbcTemplate jdbc,@Value("${app.auth.mode:required}") String mode) {
        this.jdbc=jdbc;
        demo=mode.equals("demo");
    }

    public void user(long requested) {
        Long current=CurrentAccount.optionalId();
        if (current != null) {
            if (current != requested) throw new AccountException(403,"FORBIDDEN","본인의 정보만 이용할 수 있습니다.");
        } else if (!demo || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM backend.user_account WHERE user_id=?)",Boolean.class,requested))) {
            throw new AccountException(401,"AUTH_REQUIRED","로그인이 필요합니다.");
        }
    }

    private void resource(String sql,long id) {
        var owners=jdbc.query(sql,(rs,n)->rs.getLong(1),id);
        if (owners.isEmpty()) throw new ResourceNotFoundException("요청한 정보를 찾을 수 없습니다.");
        user(owners.getFirst());
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                Object attribute=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                if (!(attribute instanceof Map<?,?> vars)) return true;
                if (vars.containsKey("userId")) user(id(vars,"userId"));
                if (vars.containsKey("sessionId")) resource(
                        "SELECT user_id FROM backend.assessment_session WHERE id=?",id(vars,"sessionId"));
                if (vars.containsKey("runId")) resource(
                        "SELECT user_id FROM backend.recommendation_run WHERE id=?",id(vars,"runId"));
                if (vars.containsKey("itemId")) resource("""
                        SELECT r.user_id FROM backend.recommendation_item i
                        JOIN backend.recommendation_run r ON r.id=i.run_id WHERE i.id=?
                        """,id(vars,"itemId"));
                return true;
            }
        }).addPathPatterns("/api/**");
    }

    private long id(Map<?,?> vars,String key) {
        try {
            long value=Long.parseLong(vars.get(key).toString());
            if (value<=0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new AccountException(400,"INVALID_REQUEST","올바른 식별자를 입력해 주세요.");
        }
    }
}
