package com.cau.capstone8.backend.topic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.auth.mode=required",
        "topic-preparation.enabled=true","topic-preparation.initial-delay-ms=3600000"})
@Testcontainers
class TopicCatalogRefreshIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11");
    @Autowired JdbcTemplate jdbc;
    @Autowired TopicCatalogRefreshService catalogs;
    @Autowired TopicPreparationService preparation;
    @Autowired TopicDiscoveryService discovery;
    @MockitoBean TopicPreparationAdapter adapter;
    @LocalServerPort int port;
    @TempDir Path dir;
    final JsonMapper json=new JsonMapper();
    final String slug="search-"+"a".repeat(20),parent="SRC-"+"b".repeat(16),hash="sha256:"+"c".repeat(64);
    final String baseline="book-search-initial-selected",oldBook="book_"+"d".repeat(20),newBook="book_"+"e".repeat(20);
    final String token="x".repeat(43),secondToken="y".repeat(43);
    long user;
    @BeforeEach void seed() throws Exception {
        jdbc.execute("TRUNCATE backend.app_user,backend.topic,backend.book,backend.discovery_catalog_import,backend.catalog_selection_snapshot CASCADE");
        user=user(token);user(secondToken);
        jdbc.update("INSERT INTO backend.topic(id,code,name) VALUES (9500,?,'경제 경영')",parent);
        jdbc.update("INSERT INTO backend.topic(id,code,name,parent_id,ml_topic_id) VALUES (9501,?,'경제',9500,?)","AUTO-"+slug,slug);
        jdbc.update("INSERT INTO backend.book(id,title,author,description,ml_book_id) VALUES (9502,'Original','Author','',?)",oldBook);
        jdbc.update("INSERT INTO backend.book_topic(book_id,topic_id,is_primary,topic_weight) VALUES (9502,9501,true,1)");
        jdbc.update("INSERT INTO backend.catalog_selection_snapshot(snapshot_id,manifest_hash,manifest) VALUES (?,?,'{}')",baseline,hash);
        jdbc.update("INSERT INTO backend.catalog_selection_topic VALUES (?,9501)",baseline);
        jdbc.update("INSERT INTO backend.catalog_selection_member VALUES (?,9501,9502,true,'provider_filter_pass')",baseline);
        jdbc.update("INSERT INTO backend.catalog_selection_current VALUES (9501,?)",baseline);
        when(adapter.run(any(),eq("catalog-state"),eq(slug),anyMap())).thenReturn(json.readTree("""
                {"available":true,"providers":{"yes24":{"status":"collected","bookCount":1},
                "open_library":{"status":"collected","bookCount":0},"google_books":{"status":"provider_failed","statusCode":429}},
                "privatePath":"should never appear in public responses"}
                """));
    }
    long user(String auth) throws Exception {
        long id=jdbc.queryForObject("INSERT INTO backend.app_user(display_name) VALUES ('Synthetic') RETURNING id",Long.class);
        jdbc.update("INSERT INTO backend.user_account(user_id,email,password_hash) VALUES (?,?,?)",id,"refresh-"+id+"@example.com","synthetic");
        jdbc.update("INSERT INTO backend.account_session(token_hash,user_id,expires_at) VALUES (?,?,now()+interval '1 hour')",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(auth.getBytes(StandardCharsets.UTF_8))),id);return id;
    }
    HttpResponse<String> call(String method,String auth,Object body) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/topic-requests/catalog/9501/refresh")).header("Content-Type","application/json");
        if(auth!=null)r.header("Authorization","Bearer "+auth);
        r.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void authenticatedSharedRefreshReplaysPendingAndKeepsVisibleCatalog() throws Exception {
        assertThat(call("POST",null,Map.of("mode","ALL")).statusCode()).isEqualTo(401);
        assertThat(call("POST",token,Map.of("mode","arbitrary")).statusCode()).isEqualTo(400);
        var queued=call("POST",token,Map.of("mode","ALL"));assertThat(queued.statusCode()).isEqualTo(202);
        assertThat(queued.body()).doesNotContain("privatePath","baselineSelection","claimToken",token);
        assertThat(call("POST",secondToken,Map.of("mode","ALL")).statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_catalog_refresh",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.catalog_visible_book_topic WHERE topic_id=9501",Integer.class)).isEqualTo(1);
        assertThat(catalogs.claim()).isNotNull();
        assertThat(preparation.claim()).isNull();assertThat(discovery.claim()).isNull();assertThat(catalogs.claim()).isNull();
        assertThat(catalogs.state(9501).status()).isEqualTo("RUNNING");
    }
    @Test void failedModeUsesOnlyServerObservedFailuresAndLimitsFreshAttempts() {
        catalogs.start(user,9501,new TopicCatalogRefreshService.Input("FAILED"));assertThat(catalogs.claim().providers()).containsExactly("google_books");
        jdbc.update("UPDATE backend.topic_catalog_refresh SET status='FAILED',claim_token=null,lease_until=null");
        assertThatThrownBy(()->catalogs.start(user,9501,new TopicCatalogRefreshService.Input("ALL"))).hasMessageContaining("조금 뒤");
    }
    @Test void expiredClaimsCannotPublishOrOverwriteAReplacement() {
        catalogs.start(user,9501,new TopicCatalogRefreshService.Input("ALL"));var expired=catalogs.claim();
        jdbc.update("UPDATE backend.topic_catalog_refresh SET lease_until=now()-interval '1 minute',created_at=now()-interval '2 minutes'");assertThat(catalogs.claim()).isNull();
        catalogs.start(user,9501,new TopicCatalogRefreshService.Input("FAILED"));var replacement=catalogs.claim();catalogs.fail(expired);
        assertThat(catalogs.state(9501).status()).isEqualTo("RUNNING");
        assertThatThrownBy(()->catalogs.publish(expired,json.readTree("{}"),dir.resolve("missing"),dir.resolve("missing"))).hasMessageContaining("claim expired");
        assertThat(replacement.id()).isNotEqualTo(expired.id());
    }
    Path[] handoff(TopicCatalogRefreshService.Job job,boolean keepOriginal) throws Exception {
        var ids=keepOriginal?List.of(oldBook,newBook):List.of(newBook);var books=new ArrayList<Map<String,Object>>();
        for(var id:ids) {
            var b=new LinkedHashMap<String,Object>();b.put("book_id",id);b.put("isbn_10",null);b.put("isbn_13",null);
            b.put("title",id.equals(oldBook)?"Original":"New book");b.put("authors",List.of("Author"));b.put("subtitle",null);
            b.put("publisher",null);b.put("published_year",null);b.put("language","en");b.put("topics",List.of(slug));books.add(b);
        }
        Files.writeString(dir.resolve("books.jsonl"),books.stream().map(json::writeValueAsString).reduce("",(a,b)->a+b+"\n"));
        Files.writeString(dir.resolve("coverage.jsonl"),ids.stream().map(id->json.writeValueAsString(Map.of("book_id",id,"toc_entry_count",0,"description_count",0,"source_count",1))).reduce("",(a,b)->a+b+"\n"));
        String bh=fileHash(dir.resolve("books.jsonl")),eh=fileHash(dir.resolve("coverage.jsonl")),snapshot="book-search-refresh-"+job.id();
        var batch=new LinkedHashMap<String,Object>(Map.of("topic",Map.of("code","AUTO-"+slug,"name","경제","parent_code",parent,"parent_name","경제 경영","ml_topic_id",slug),
                "books_path","books.jsonl","books_sha256",bh,"evidence_path","coverage.jsonl","evidence_sha256",eh,
                "canonical_hashes",Map.of("books.jsonl",bh,"documents.jsonl",hash,"toc.jsonl",hash,"sources.jsonl",hash)));
        batch.put("candidates_path",null);batch.put("candidates_sha256",null);
        var importPath=dir.resolve("import.json");Files.writeString(importPath,json.writeValueAsString(Map.of("contract_version","discovery-catalog-import-v1",
                "snapshot_id",snapshot,"topics",List.of(batch),"accepted_projection_sources",List.of())));
        var selected=Map.of("contract_version","catalog-selection-v1","snapshot_id",snapshot+"-selected","source_snapshot_id",snapshot,
                "source_manifest_sha256",fileHash(importPath),"pipeline_revision","a".repeat(40),"pipeline_code_sha256",hash,"topics",List.of(Map.of(
                "ml_topic_id",slug,"raw_sha256",List.of(hash),"filtered_books_sha256",bh,"decisions",ids.stream().map(id->Map.of("book_id",id,"included",true,"reason","provider_filter_pass")).toList())));
        var selection=dir.resolve("selection.json");Files.writeString(selection,json.writeValueAsString(selected));return new Path[]{importPath,selection};
    }
    String fileHash(Path path) throws Exception {return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    tools.jackson.databind.JsonNode result(TopicCatalogRefreshService.Job job,int count) {
        return json.valueToTree(Map.of("status","COLLECTED","slug",slug,"baselineSelection",baseline,"bookCount",count,"refreshedProviders",job.providers(),
                "providers",Map.of("yes24",Map.of("status","collected"),"open_library",Map.of("status","collected"),"google_books",Map.of("status","provider_failed","statusCode",429))));
    }
    @Test void publicationRollsBackAnyRemovalAndPublishesPartialSupplementWithoutDiagnosis() throws Exception {
        catalogs.start(user,9501,new TopicCatalogRefreshService.Input("ALL"));var job=catalogs.claim();var dropped=handoff(job,false);
        assertThatThrownBy(()->catalogs.publish(job,result(job,1),dropped[0],dropped[1])).hasMessageContaining("dropped existing books");
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM backend.catalog_selection_current WHERE topic_id=9501",String.class)).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.discovery_catalog_import",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.book",Integer.class)).isEqualTo(1);
        var complete=handoff(job,true);catalogs.publish(job,result(job,2),complete[0],complete[1]);assertThat(catalogs.state(9501).status()).isEqualTo("PARTIAL");
        assertThat(catalogs.state(9501).addedBookCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.catalog_visible_book_topic WHERE topic_id=9501",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.book_ranking_v2_projection",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_content_preparation",Integer.class)).isZero();
    }
    @Test void changedCurrentSelectionRejectsStaleResultBeforeReadingOrWritingFiles() {
        catalogs.start(user,9501,new TopicCatalogRefreshService.Input("ALL"));var job=catalogs.claim();
        jdbc.update("INSERT INTO backend.catalog_selection_snapshot(snapshot_id,manifest_hash,manifest) VALUES ('newer',?,'{}')",hash);
        jdbc.update("INSERT INTO backend.catalog_selection_topic VALUES ('newer',9501)");jdbc.update("UPDATE backend.catalog_selection_current SET snapshot_id='newer' WHERE topic_id=9501");
        assertThatThrownBy(()->catalogs.publish(job,result(job,2),dir.resolve("missing"),dir.resolve("missing"))).hasMessageContaining("baseline changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.discovery_catalog_import",Integer.class)).isZero();
    }
}
