package com.cau.capstone8.backend;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReadingShelfIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json=new ObjectMapper();
    long user, other, book;
    @BeforeEach void seed() {
        user=jdbc.queryForObject("insert into backend.app_user(display_name) values(?) returning id",Long.class,"reader-"+UUID.randomUUID());
        other=jdbc.queryForObject("insert into backend.app_user(display_name) values('Other reader') returning id",Long.class);
        book=jdbc.queryForObject("insert into backend.book(title,author,description) values('Test book','Test author','Description') returning id",Long.class);
    }
    String shelf(){return "/api/users/"+user+"/shelf/"+book;}
    String review(){return "/api/users/"+user+"/reviews/"+book;}
    @Test void addingAgainDoesNotOverwriteReadingProgressOrNotes() throws Exception {
        assertThat(call("POST",shelf(),null).statusCode()).isEqualTo(200);
        call("PUT",shelf(),"{\"status\":\"READING\",\"note\":\"Keep this\"}");
        var result=json.readTree(call("POST",shelf(),null).body());
        assertThat(result.path("status").asString()).isEqualTo("READING");
        assertThat(result.path("note").asString()).isEqualTo("Keep this");
        assertThat(json.readTree(call("GET","/api/users/"+user+"/shelf",null).body()).path("totalElements").asInt()).isEqualTo(1);
    }
    @Test void savesReplacesListsAndRemovesShelfWithoutDuplicates() throws Exception {
        assertThat(call("PUT",shelf(),"{\"status\":\"WANT_TO_READ\",\"note\":\"my note\"}").statusCode()).isEqualTo(200);
        assertThat(call("PUT",shelf(),"{\"status\":\"FINISHED\",\"note\":\"finished\"}").statusCode()).isEqualTo(200);
        var result=json.readTree(call("GET","/api/users/"+user+"/shelf",null).body());
        assertThat(result.path("totalElements").asInt()).isEqualTo(1);
        assertThat(result.path("content").get(0).path("status").asString()).isEqualTo("FINISHED");
        assertThat(result.path("content").get(0).path("note").asString()).isEqualTo("finished");
        assertThat(call("DELETE",shelf(),null).statusCode()).isEqualTo(204);
        assertThat(call("DELETE",shelf(),null).statusCode()).isEqualTo(204);
        assertThat(json.readTree(call("GET","/api/users/"+user+"/shelf",null).body()).path("totalElements").asInt()).isZero();
    }
    @Test void userPathsKeepShelfAndReviewUpdatesSeparate() throws Exception {
        call("PUT",shelf(),"{\"status\":\"READING\",\"note\":\"only mine\"}");
        call("PUT",review(),"{\"difficulty\":\"HARD\",\"text\":\"Need prerequisites\"}");
        call("DELETE","/api/users/"+other+"/shelf/"+book,null);
        call("DELETE","/api/users/"+other+"/reviews/"+book,null);
        assertThat(json.readTree(call("GET","/api/users/"+other+"/shelf",null).body()).path("totalElements").asInt()).isZero();
        var mine=json.readTree(call("GET","/api/users/"+user+"/shelf",null).body()).path("content").get(0);
        assertThat(mine.path("review").path("difficulty").asString()).isEqualTo("HARD");
        assertThat(mine.path("note").asString()).isEqualTo("only mine");
    }
    @Test void publicReviewsNeverIncludePersonalNotesAndAreReplaceable() throws Exception {
        call("PUT",shelf(),"{\"status\":\"READING\",\"note\":\"PRIVATE-MEMO\"}");
        assertThat(call("PUT",review(),"{\"difficulty\":\"HARD\",\"text\":\"First review\"}").statusCode()).isEqualTo(200);
        call("PUT",review(),"{\"difficulty\":\"APPROPRIATE\",\"text\":\"Updated review\"}");
        var response=call("GET","/api/books/"+book+"/reviews",null);
        assertThat(response.body()).doesNotContain("PRIVATE-MEMO","note","First review");
        var page=json.readTree(response.body());
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertThat(page.path("content").get(0).path("text").asString()).isEqualTo("Updated review");
        call("DELETE",shelf(),null);
        assertThat(json.readTree(call("GET","/api/books/"+book+"/reviews",null).body()).path("totalElements").asInt()).isEqualTo(1);
        assertThat(call("DELETE",review(),null).statusCode()).isEqualTo(204);
        assertThat(json.readTree(call("GET","/api/books/"+book+"/reviews",null).body()).path("totalElements").asInt()).isZero();
    }
    @Test void paginatesAndFiltersShelf() throws Exception {
        call("PUT",shelf(),"{\"status\":\"READING\",\"note\":\"\"}");
        long second=jdbc.queryForObject("insert into backend.book(title,author,description) values('Second','Author','') returning id",Long.class);
        call("PUT","/api/users/"+user+"/shelf/"+second,"{\"status\":\"FINISHED\",\"note\":\"\"}");
        var page=json.readTree(call("GET","/api/users/"+user+"/shelf?size=1",null).body());
        assertThat(page.path("totalElements").asInt()).isEqualTo(2);
        assertThat(page.path("content").size()).isEqualTo(1);
        var filtered=json.readTree(call("GET","/api/users/"+user+"/shelf?status=FINISHED",null).body());
        assertThat(filtered.path("totalElements").asInt()).isEqualTo(1);
        assertThat(filtered.path("content").get(0).path("bookId").asLong()).isEqualTo(second);
        assertThat(json.readTree(call("GET","/api/users/"+user+"/shelf?page=2147483647&size=100",null).body()).path("content").size()).isZero();
    }
    @Test void rejectsInvalidInputAndUnknownReferences() throws Exception {
        for(String body:new String[]{"not-json","{}","{\"status\":\"BAD\"}","{\"status\":\"READING\",\"note\":\""+"a".repeat(1001)+"\"}"})
            assertThat(call("PUT",shelf(),body).statusCode()).as(body).isEqualTo(400);
        for(String body:new String[]{"{}","{\"difficulty\":\"EASY\",\"text\":\" \"}","{\"difficulty\":\"BAD\",\"text\":\"Text\"}","{\"difficulty\":\"HARD\",\"text\":\""+"a".repeat(301)+"\"}"})
            assertThat(call("PUT",review(),body).statusCode()).isEqualTo(400);
        assertThat(call("GET","/api/users/"+user+"/shelf?size=101",null).statusCode()).isEqualTo(400);
        assertThat(call("GET","/api/books/"+book+"/reviews?page=-1",null).statusCode()).isEqualTo(400);
        assertThat(call("GET","/api/users/9223372036854775807/shelf",null).statusCode()).isEqualTo(404);
        assertThat(call("PUT","/api/users/"+user+"/shelf/9223372036854775807","{\"status\":\"READING\",\"note\":\"\"}").statusCode()).isEqualTo(404);
    }
    HttpResponse<String> call(String method,String path,String body) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
