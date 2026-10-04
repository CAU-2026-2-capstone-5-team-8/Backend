package com.cau.capstone8.backend.account;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.auth.mode=required", "app.auth.trusted-proxy-cidrs=127.0.0.1/32,::1/128"})
@Testcontainers
class ProxyAuthIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();
    @Test void proxyUsersHaveIndependentQuotasAndRotatingIpsCannotAttackOneAccount() throws Exception {
        String prefix = UUID.randomUUID().toString();
        for (int i=1;i<=12;i++) assertThat(login("198.51.100."+i, prefix+"-"+i+"@example.invalid").statusCode()).isEqualTo(401);
        String account = prefix+"-target@example.invalid";
        for (int i=1;i<=10;i++) assertThat(login("203.0.113."+i,account).statusCode()).isEqualTo(401);
        var blocked = login("203.0.113.11",account.toUpperCase());
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).contains("60");
        assertThat(blocked.body()).contains("AUTH_RATE_LIMIT").doesNotContain(account);
        assertThat(login("203.0.113.12",prefix+"-another@example.invalid").statusCode()).isEqualTo(401);
    }
    private HttpResponse<String> login(String address,String email) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
                .header("Content-Type","application/json").header("X-Forwarded-For",address)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+email+"\",\"password\":\"invalid-password\"}"))
                .build(),HttpResponse.BodyHandlers.ofString());
    }
}
