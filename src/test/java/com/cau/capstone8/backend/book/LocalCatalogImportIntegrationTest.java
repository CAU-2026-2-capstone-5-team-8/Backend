package com.cau.capstone8.backend.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Testcontainers
class LocalCatalogImportIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired LocalCatalogImportService importer;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path dir;
    final JsonMapper json = new JsonMapper();
    final String idA = "isbn13:9788952117441", idB = "isbn13:9788961055680";
    Map<String, Object> manifest;

    @BeforeEach void prepare() throws Exception {
        // Only the disposable PostgreSQL owned by this Testcontainers class.
        jdbc.execute("truncate backend.app_user, backend.topic, backend.book cascade");
        Files.writeString(dir.resolve("books.jsonl"), book(idA,"9788952117441") + "\n" + book(idB,"9788961055680") + "\n");
        Files.writeString(dir.resolve("candidates.jsonl"), candidate(idA) + "\n" + candidate(idB) + "\n");
        manifest = new LinkedHashMap<>(Map.of(
                "contract_version","local-catalog-import-v1", "snapshot_id","synthetic-import-test-v1",
                "books_path","books.jsonl", "books_sha256",hash("books.jsonl"),
                "candidates_path","candidates.jsonl", "candidates_sha256",hash("candidates.jsonl"),
                "selected_book_ids",List.of(idA,idB),
                "topic",Map.of("code","LA","name","Synthetic LA","parent_code","MAT",
                        "parent_name","Synthetic Math","ml_topic_id","linear-algebra")));
    }

    @Test void importsReplayKeepsIdsAndOriginalVersionsAndNullValues() throws Exception {
        var first = importer.importManifest(writeManifest());
        var second = importer.importManifest(writeManifest());
        assertThat(first.replayed()).isFalse(); assertThat(second.replayed()).isTrue();
        assertThat(second.bookIds()).isEqualTo(first.bookIds());
        assertThat(second.projectionIds()).isEqualTo(first.projectionIds());
        assertThat(jdbc.queryForObject("select count(*) from backend.book",Integer.class)).isEqualTo(2);
        var projection = jdbc.queryForMap("select * from backend.book_ranking_v2_projection where id=?",first.projectionIds().get(idA));
        assertThat(projection).containsEntry("version","book-v1")
                .containsEntry("config_version","synthetic-config-v1")
                .containsEntry("lexical_difficulty",null)
                .containsEntry("source_artifact_hash",manifest.get("candidates_sha256"));
        assertThat(projection.get("prerequisite_concepts").toString()).isEqualTo("[]");
        assertThat(jdbc.queryForObject("select ml_book_id from backend.book where id=?",String.class,first.bookIds().get(idA))).isEqualTo(idA);
    }

    @Test void alteredSnapshotAndSameFeatureVersionNeverOverwrite() throws Exception {
        var first = importer.importManifest(writeManifest());
        Files.writeString(dir.resolve("candidates.jsonl"),candidate(idA).replace("\"matrix\"","\"vector\"")+"\n"+candidate(idB)+"\n");
        manifest.put("candidates_sha256",hash("candidates.jsonl"));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("different immutable input");
        manifest.put("snapshot_id","synthetic-import-test-v2");
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("different content or provenance");
        assertThat(jdbc.queryForObject("select covered_concepts::text from backend.book_ranking_v2_projection where id=?",String.class,
                first.projectionIds().get(idA))).contains("matrix").doesNotContain("vector");
        assertThat(jdbc.queryForObject("select count(*) from backend.local_catalog_import",Integer.class)).isEqualTo(1);
    }

    @Test void failureOnSecondBookRollsBackTheEntireCatalogAndTopics() throws Exception {
        Files.writeString(dir.resolve("candidates.jsonl"),candidate(idA)+"\n"+candidate(idB).replace("1.0","2.0")+"\n");
        manifest.put("candidates_sha256",hash("candidates.jsonl"));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("invalid score");
        for(String table:List.of("book","topic","book_topic","book_ranking_v2_projection","local_catalog_import"))
            assertThat(jdbc.queryForObject("select count(*) from backend."+table,Integer.class)).isZero();
    }

    @Test void refusesIsbnCollisionInsteadOfMergingAnExistingBook() throws Exception {
        jdbc.update("insert into backend.book(title,author,description,isbn) values ('Existing','Existing','',?)","9788952117441");
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("different immutable metadata");
        assertThat(jdbc.queryForObject("select ml_book_id from backend.book where isbn=?",String.class,"9788952117441")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from backend.book",Integer.class)).isEqualTo(1);
    }

    @Test void rejectsHashMissingDuplicateAndWrongIdentityInputs() throws Exception {
        manifest.put("books_sha256","sha256:"+"0".repeat(64));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("SHA-256 mismatch");
        manifest.put("books_sha256",hash("books.jsonl"));manifest.put("selected_book_ids",List.of(idA,idA));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("duplicate selected");
        manifest.put("selected_book_ids",List.of("missing"));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("missing from handoff");
        manifest.put("selected_book_ids",List.of(idA));
        Files.writeString(dir.resolve("books.jsonl"),book(idA,"9788961055680"));
        manifest.put("books_sha256",hash("books.jsonl"));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("book_id/ISBN mismatch");
        Files.delete(dir.resolve("books.jsonl"));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("missing or oversized input");
    }

    @Test void rejectsDuplicateRowsUnknownFieldsAndScalarCoercion() throws Exception {
        Files.writeString(dir.resolve("candidates.jsonl"),candidate(idA)+"\n"+candidate(idA));
        manifest.put("candidates_sha256",hash("candidates.jsonl"));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).hasMessageContaining("duplicate candidate");
        Files.writeString(dir.resolve("candidates.jsonl"),candidate(idA).replace("1.0","\"1.0\""));
        manifest.put("candidates_sha256",hash("candidates.jsonl"));
        assertThatThrownBy(()->importer.importManifest(writeManifest())).isInstanceOf(RuntimeException.class);
        manifest.put("unexpected",true);
        assertThatThrownBy(()->importer.importManifest(writeManifest())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select count(*) from backend.book",Integer.class)).isZero();
    }

    Path writeManifest() throws Exception { Path p=dir.resolve("manifest.json"); Files.writeString(p,json.writeValueAsString(manifest));return p; }
    String hash(String name) throws Exception { return LocalCatalogImportService.hash(Files.readAllBytes(dir.resolve(name))); }
    String book(String id,String isbn) { return """
            {"book_id":"%s","isbn_10":null,"isbn_13":"%s","title":"Synthetic book",
             "subtitle":null,"authors":["Synthetic author"],"publisher":null,"published_year":null,
             "language":"en","topics":["mathematics","linear-algebra"]}
            """.formatted(id,isbn).replace("\n", "").strip(); }
    String candidate(String id) { return """
            {"book_id":"%s","topic_distribution":{"linear-algebra":1.0},
             "covered_concepts":[{"concept":"matrix","weight":1.0}],"prerequisite_concepts":[],
             "lexical_difficulty":null,"syntactic_complexity":null,"concept_density":null,"prerequisite_demand":null,
             "feature_version":"book-v1","config_version":"synthetic-config-v1","config_hash":"sha256:%s"}
            """.formatted(id,"a".repeat(64)).replace("\n", "").strip(); }
}
