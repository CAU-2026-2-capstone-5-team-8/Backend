package com.cau.capstone8.backend.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class AuthRateLimitTest {
    @Test void trustedProxyDoesNotCollapseDifferentClientsIntoOneQuota() throws Exception {
        var filter = new AuthConfiguration.BearerFilter(mock(AccountService.class),
                new ClientAddress("10.1.0.0/16"));
        for (int i=1; i<=15; i++) {
            assertThat(call(filter, "10.1.2.3", "198.51.100."+i)).isEqualTo(200);
        }
        for (int i=0; i<9; i++) assertThat(call(filter,"10.1.2.3","198.51.100.1")).isEqualTo(200);
        assertThat(call(filter,"10.1.2.3","198.51.100.1")).isEqualTo(429);
        assertThat(call(filter,"10.1.2.3","198.51.100.2")).isEqualTo(200);
    }
    @Test void untrustedPeerCannotRotateQuotaWithForgedHeaders() throws Exception {
        var filter = new AuthConfiguration.BearerFilter(mock(AccountService.class), new ClientAddress("10.1.0.0/16"));
        for (int i=0;i<10;i++) assertThat(call(filter,"198.51.100.1","203.0.113."+i)).isEqualTo(200);
        assertThat(call(filter,"198.51.100.1","203.0.113.99")).isEqualTo(429);
    }
    @Test void rightmostUntrustedHopWinsAndMalformedHeadersFallBackToPeer() {
        var resolver = new ClientAddress("10.1.0.0/16,2001:db8:1::/48");
        assertThat(address(resolver,"10.1.2.3","203.0.113.99, 198.51.100.7, 10.1.0.4")).isEqualTo("198.51.100.7");
        assertThat(address(resolver,"10.1.2.3","198.51.100.7, hostname")).isEqualTo("10.1.2.3");
        assertThat(address(resolver,"10.1.2.3","2001:db8::7")).isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(address(resolver,"::ffff:10.1.2.3","::ffff:198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(address(resolver,"10.1.2.3", "198.51.100.7,".repeat(20))).isEqualTo("10.1.2.3");
    }
    @Test void invalidOrTrustAllConfigurationIsRejected() {
        for (String input : new String[]{"0.0.0.0/0","::/0","localhost","10.0.0.1/99","*"})
            assertThatThrownBy(() -> new ClientAddress(input)).isInstanceOf(IllegalArgumentException.class);
    }
    private String address(ClientAddress resolver, String peer, String header) {
        var req=new MockHttpServletRequest(); req.setRemoteAddr(peer); req.addHeader("X-Forwarded-For",header);
        return resolver.resolve(req);
    }
    private int call(AuthConfiguration.BearerFilter filter, String peer, String header) throws Exception {
        var req=new MockHttpServletRequest("POST","/api/auth/login"); req.setServletPath("/api/auth/login");
        req.setRemoteAddr(peer); req.addHeader("X-Forwarded-For",header);
        var res=new MockHttpServletResponse(); filter.doFilter(req,res,new MockFilterChain()); return res.getStatus();
    }
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
