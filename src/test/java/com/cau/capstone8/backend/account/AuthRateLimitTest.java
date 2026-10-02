package com.cau.capstone8.backend.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class AuthRateLimitTest {
    @Test void throttlesCredentialEndpointsBeforeInvokingPasswordVerification() throws Exception {
        var accounts=mock(AccountService.class);
        var filter=new AuthConfiguration.BearerFilter(accounts);
        for (int i=0;i<11;i++) {
            var request=new MockHttpServletRequest("POST","/api/auth/login");
            request.setServletPath("/api/auth/login");
            request.setRemoteAddr("127.0.0.1");
            var response=new MockHttpServletResponse();
            var chain=new MockFilterChain();
            filter.doFilter(request,response,chain);
            if (i<10) assertThat(chain.getRequest()).isNotNull();
            else {
                assertThat(chain.getRequest()).isNull();
                assertThat(response.getStatus()).isEqualTo(429);
                assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            }
        }
        verifyNoInteractions(accounts);
    }
}
