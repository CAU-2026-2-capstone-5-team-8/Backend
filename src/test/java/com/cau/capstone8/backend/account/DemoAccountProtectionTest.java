package com.cau.capstone8.backend.account;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="app.auth.mode=demo")
@Testcontainers
class DemoAccountProtectionTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11");
    @LocalServerPort int port;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;

    @Test void demoModeNeverExposesRegisteredAccountThroughLegacyUserPath() throws Exception {
        var session=accounts.register(new AccountModels.Register(
                UUID.randomUUID()+"@example.com","example-password-123","Account"));
        long demo=jdbc.queryForObject("INSERT INTO backend.app_user(display_name) VALUES ('Legacy demo') RETURNING id",Long.class);
        try (var client=HttpClient.newHttpClient()) {
            assertThat(client.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:"+port+"/api/users/"+session.userId()+"/shelf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:"+port+"/api/users/"+demo+"/shelf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:"+port+"/api/me")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }
}
