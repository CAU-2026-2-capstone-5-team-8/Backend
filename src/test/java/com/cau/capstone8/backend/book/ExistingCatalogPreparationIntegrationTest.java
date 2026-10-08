package com.cau.capstone8.backend.book;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import com.cau.capstone8.backend.topic.TopicContentPreparationService;

@SpringBootTest(properties={"topic-content-preparation.enabled=true"})
@Testcontainers
class ExistingCatalogPreparationIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11");
    @Autowired JdbcTemplate jdbc;
    @Autowired ExistingCatalogPreparationService service;
    @Autowired TopicContentPreparationService content;
    @TempDir Path dir;
    final JsonMapper json=new JsonMapper();
    final String slug="operating-systems", book="book_"+"d".repeat(20), hash="sha256:"+"a".repeat(64);
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE backend.app_user,backend.topic,backend.book,backend.discovery_catalog_import,backend.catalog_selection_snapshot CASCADE");
        jdbc.update("INSERT INTO backend.topic(id,code,name) VALUES (9700,'CS','컴퓨터과학')");
        jdbc.update("INSERT INTO backend.topic(id,code,name,parent_id,ml_topic_id) VALUES (9701,'OS','운영체제',9700,?)",slug);
        jdbc.update("INSERT INTO backend.book(id,title,author,description,ml_book_id) VALUES (9702,'Original','Author','',?)",book);
        jdbc.update("INSERT INTO backend.book_topic(book_id,topic_id,is_primary,topic_weight) VALUES (9702,9701,true,1)");
        jdbc.update("INSERT INTO backend.catalog_selection_snapshot(snapshot_id,manifest_hash,manifest) VALUES ('original',?,'{}')",hash);
        jdbc.update("INSERT INTO backend.catalog_selection_topic VALUES ('original',9701)");
        jdbc.update("INSERT INTO backend.catalog_selection_member VALUES ('original',9701,9702,true,'provider_filter_pass')");
        jdbc.update("INSERT INTO backend.catalog_selection_current VALUES (9701,'original')");
    }
    Path write(String name,Object data) throws Exception {Path p=dir.resolve(name);Files.writeString(p,json.writeValueAsString(data)+"\n");return p;}
    String digest(Path p) throws Exception {return LocalCatalogImportService.hash(Files.readAllBytes(p));}
    Path plan(boolean extra) throws Exception {
        var b=new LinkedHashMap<String,Object>();b.put("book_id",book);b.put("isbn_10",null);b.put("isbn_13",null);b.put("title","Original");b.put("subtitle",null);b.put("authors",List.of("Author"));b.put("publisher",null);b.put("published_year",null);b.put("language","en");b.put("topics",List.of(slug));
        Path books=write("books.jsonl",b);Path evidence=write("coverage.jsonl",Map.of("book_id",book,"toc_entry_count",1,"description_count",0,"source_count",1));
        if(extra) {
            b.put("book_id","book_"+"e".repeat(20));b.put("title","Extra");
            Files.writeString(books,json.writeValueAsString(b)+"\n",StandardOpenOption.APPEND);
            Files.writeString(evidence,json.writeValueAsString(Map.of("book_id",b.get("book_id"),"toc_entry_count",1,"description_count",0,"source_count",1))+"\n",StandardOpenOption.APPEND);
        }
        var batch=new LinkedHashMap<String,Object>();batch.put("topic",Map.of("code","OS","name","운영체제","parent_code","CS","parent_name","컴퓨터과학","ml_topic_id",slug));
        batch.put("books_path","books.jsonl");batch.put("books_sha256",digest(books));batch.put("evidence_path","coverage.jsonl");batch.put("evidence_sha256",digest(evidence));batch.put("candidates_path",null);batch.put("candidates_sha256",null);
        batch.put("canonical_hashes",Map.of("books.jsonl",digest(books),"documents.jsonl",hash,"toc.jsonl",hash,"sources.jsonl",hash));
        Path imported=write("import.json",Map.of("contract_version","discovery-catalog-import-v1","snapshot_id","catalog-backfill-test","accepted_projection_sources",List.of(),"topics",List.of(batch)));
        var decisions=new ArrayList<>(List.of(Map.of("book_id",book,"included",true,"reason","provider_filter_pass")));
        if(extra)decisions.add(Map.of("book_id","book_"+"e".repeat(20),"included",true,"reason","provider_filter_pass"));
        write("selection.json",Map.of("contract_version","catalog-selection-v1","snapshot_id","catalog-backfill-test-selected","source_snapshot_id","catalog-backfill-test","source_manifest_sha256",digest(imported),"pipeline_revision","b".repeat(40),"pipeline_code_sha256",hash,"topics",List.of(Map.of("ml_topic_id",slug,"raw_sha256",List.of(hash),"filtered_books_sha256",digest(books),"decisions",decisions))));
        return write("plan.json",Map.of("contractVersion","existing-catalog-preparation-v1","topics",List.of(Map.of("slug",slug,"baselineSelection","original","baselineHash",hash,"importManifest","import.json","selectionManifest","selection.json"))));
    }
    @Test void preservesBooksAndHistoryThenQueuesNormalContentStage() throws Exception {
        Path plan=plan(false);assertThat(service.apply(plan)).containsEntry(slug,1);assertThat(service.apply(plan)).containsEntry(slug,1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.book",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.catalog_selection_snapshot WHERE snapshot_id='original'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
        var job=content.claim();assertThat(job).isNotNull();assertThat(job.snapshotId()).isEqualTo("catalog-backfill-test");
    }
    @Test void refusesStaleBaselineAndLeavesCatalogUntouched() throws Exception {
        Path plan=plan(false);jdbc.update("UPDATE backend.catalog_selection_snapshot SET manifest_hash=? WHERE snapshot_id='original'","sha256:"+"b".repeat(64));
        assertThatThrownBy(()->service.apply(plan)).hasMessageContaining("baseline changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.discovery_catalog_import",Integer.class)).isZero();
    }
    @Test void rollsBackUnexpectedAddedBookAndPreservesExistingSelection() throws Exception {
        Path plan=plan(true);assertThatThrownBy(()->service.apply(plan)).hasMessageContaining("preserve every visible book");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.book",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM backend.catalog_selection_current WHERE topic_id=9701",String.class)).isEqualTo("original");
    }
    @Test void refusesActiveDiagnosisBank() throws Exception {
        Path plan=plan(false);jdbc.update("INSERT INTO backend.question(topic_id,measurement_area,difficulty,prompt,version,active) VALUES (9701,'VOCABULARY',1,'Synthetic','test',true)");
        assertThatThrownBy(()->service.apply(plan)).hasMessageContaining("active diagnosis");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.discovery_catalog_import",Integer.class)).isZero();
    }
    @Test void missingArtifactInLaterTopicRollsBackEarlierTopic() throws Exception {
        Path plan=plan(false);
        jdbc.update("INSERT INTO backend.topic(id,code,name,parent_id,ml_topic_id) VALUES (9703,'ALG','알고리즘',9700,'algorithms')");
        jdbc.update("INSERT INTO backend.catalog_selection_topic VALUES ('original',9703)");
        jdbc.update("INSERT INTO backend.catalog_selection_current VALUES (9703,'original')");
        var document=(tools.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(plan));
        var topics=(tools.jackson.databind.node.ArrayNode)document.path("topics");
        topics.add(json.valueToTree(Map.of("slug","algorithms","baselineSelection","original","baselineHash",hash,
                "importManifest","missing.json","selectionManifest","selection.json")));
        Files.writeString(plan,json.writeValueAsString(document));
        assertThatThrownBy(()->service.apply(plan)).isInstanceOf(NoSuchFileException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.discovery_catalog_import",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.catalog_selection_snapshot",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM backend.catalog_selection_current WHERE topic_id=9701",String.class)).isEqualTo("original");
    }
}
