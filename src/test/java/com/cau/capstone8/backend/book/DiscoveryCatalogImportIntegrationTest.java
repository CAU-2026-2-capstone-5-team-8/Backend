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

@SpringBootTest
@Testcontainers
class DiscoveryCatalogImportIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired DiscoveryCatalogImportService importer;
    @Autowired LocalCatalogImportService legacy;
    @Autowired CatalogSummaryService summary;
    @Autowired BookService books;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path dir;
    final JsonMapper json = new JsonMapper();
    final LocalCatalogImportIntegrationTest fixture = new LocalCatalogImportIntegrationTest();
    final String a="isbn13:9788952117441", b="isbn13:9788961055680";
    Map<String,Object> manifest, la, os;
    Map<String,String> mapping(String code,String topic) {
        return Map.of("code",code,"name","Synthetic "+code,"parent_code","ROOT","parent_name","Synthetic Root","ml_topic_id",topic);
    }
    String hash(String file) throws Exception { return LocalCatalogImportService.hash(Files.readAllBytes(dir.resolve(file))); }
    void write(String file,String content) throws Exception { Files.writeString(dir.resolve(file),content); }
    Path manifest() throws Exception { write("manifest.json",json.writeValueAsString(manifest));return dir.resolve("manifest.json"); }
    Map<String,Object> batch(String code,String topic,String isbn,boolean candidate) throws Exception {
        write(code+"-books",fixture.book("isbn13:"+isbn,isbn).replace("linear-algebra",topic));
        write(code+"-coverage",json.writeValueAsString(Map.of("book_id","isbn13:"+isbn,"toc_entry_count",candidate?2:0,"description_count",0,"source_count",1)));
        var batch = new LinkedHashMap<String,Object>();
        batch.put("topic",mapping(code,topic));batch.put("books_path",code+"-books");batch.put("books_sha256",hash(code+"-books"));
        batch.put("evidence_path",code+"-coverage");batch.put("evidence_sha256",hash(code+"-coverage"));
        batch.put("candidates_path",candidate?code+"-candidates":null);batch.put("candidates_sha256",null);
        if(candidate){write(code+"-candidates",fixture.candidate("isbn13:"+isbn));batch.put("candidates_sha256",hash(code+"-candidates"));}
        batch.put("canonical_hashes",Map.of("books.jsonl",hash(code+"-books"),"toc.jsonl",hash(code+"-coverage"),"documents.jsonl",hash(code+"-coverage"),"sources.jsonl",hash(code+"-coverage")));
        return batch;
    }
    @BeforeEach void setup() throws Exception {
        jdbc.execute("truncate backend.app_user,backend.topic,backend.book,backend.local_catalog_import,backend.discovery_catalog_import cascade");
        la=batch("LA","linear-algebra","9788952117441",true);
        os=batch("OS","operating-systems","9788961055680",false);
        manifest=new LinkedHashMap<>(Map.of("contract_version","discovery-catalog-import-v1","snapshot_id","synthetic-discovery-v1","topics",List.of(la,os),"accepted_projection_sources",List.of()));
    }
    @Test void replayCatalogCoverageAndNoFabricatedProjections() throws Exception {
        var first=importer.importManifest(manifest());
        var ids=jdbc.queryForList("select id from backend.book order by id",Long.class);
        assertThat(first.bookCount()).isEqualTo(2);assertThat(first.tocBookCount()).isEqualTo(1);assertThat(first.projectionCount()).isEqualTo(1);
        assertThat(importer.importManifest(manifest()).replayed()).isTrue();
        assertThat(jdbc.queryForList("select id from backend.book order by id",Long.class)).isEqualTo(ids);
        assertThat(summary.summary().conceptBookCount()).isEqualTo(1);
        assertThat(summary.summary().tocBookCount()).isEqualTo(1);
        var all=books.list(null,0,1);
        assertThat(all.totalElements()).isEqualTo(2);assertThat(all.totalPages()).isEqualTo(2);
        assertThat(all.content().getFirst().topics().getFirst().tocEntryCount()).isEqualTo(2);
        var osBook=books.list(first.topics().get("operating-systems").topicId(),0,20).content().getFirst();
        assertThat(osBook.topics().getFirst().rankingCandidate()).isFalse();
        assertThat(osBook.topics().getFirst().coveredConceptCount()).isNull();
        assertThat(books.list(null,Integer.MAX_VALUE,100).content()).isEmpty();
        var topic=first.topics().get("linear-algebra").topicId();
        for(String area:List.of("VOCABULARY","BACKGROUND_KNOWLEDGE","COMPREHENSION"))
            for(int i=0;i<3;i++)jdbc.update("insert into backend.question(topic_id,measurement_area,difficulty,prompt,concept_id,version,active) values (?,?,1,'synthetic','matrix','v1',true)",topic,area);
        assertThat(summary.readyTopicIds()).containsExactly(topic);
        jdbc.update("update backend.question set active=false where id=(select min(id) from backend.question)");
        assertThat(summary.readyTopicIds()).isEmpty();
    }
    @Test void lateOrphanRollsBackAllTopicsBooksAndImportHistory() throws Exception {
        write("OS-coverage",Files.readString(dir.resolve("OS-coverage"))+"\n"+json.writeValueAsString(Map.of("book_id","orphan","toc_entry_count",0,"description_count",0,"source_count",1)));
        os.put("evidence_sha256",hash("OS-coverage"));
        assertThatThrownBy(()->importer.importManifest(manifest())).hasMessageContaining("orphan");
        for(String table:List.of("book","topic","book_ranking_v2_projection","discovery_catalog_import","discovery_catalog_member"))
            assertThat(jdbc.queryForObject("select count(*) from backend."+table,Integer.class)).isZero();
    }
    @Test void immutableManifestHashAndCoverageIdentityAreEnforced() throws Exception {
        importer.importManifest(manifest());manifest.put("snapshot_id","synthetic-discovery-v2");
        write("LA-coverage",Files.readString(dir.resolve("LA-coverage"))+"\n"+Files.readString(dir.resolve("LA-coverage")));la.put("evidence_sha256",hash("LA-coverage"));
        assertThatThrownBy(()->importer.importManifest(manifest())).hasMessageContaining("duplicate evidence");
        assertThat(jdbc.queryForObject("select count(*) from backend.discovery_catalog_import",Integer.class)).isEqualTo(1);
        manifest.put("snapshot_id","synthetic-discovery-v1");
        assertThatThrownBy(()->importer.importManifest(manifest())).hasMessageContaining("different immutable input");
    }
    @Test void unavailableAuthorDoesNotRejectTheWholeCatalogOrInventCanonicalMetadata() throws Exception {
        String original=Files.readString(dir.resolve("OS-books")).replace("[\"Synthetic author\"]","[]");
        write("OS-books",original);
        os.put("books_sha256",hash("OS-books"));
        var hashes=new LinkedHashMap<>((Map<String,String>)os.get("canonical_hashes"));
        hashes.put("books.jsonl",hash("OS-books"));os.put("canonical_hashes",hashes);
        assertThat(importer.importManifest(manifest()).bookCount()).isEqualTo(2);
        assertThat(importer.importManifest(manifest()).replayed()).isTrue();
        assertThat(jdbc.queryForObject("select author from backend.book where ml_book_id=?",String.class,b)).isEqualTo("저자 정보 없음");
        assertThat(Files.readString(dir.resolve("OS-books"))).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from backend.book_ranking_v2_projection",Integer.class)).isEqualTo(1);
    }
    @Test void explicitLegacyAcknowledgmentReusesIdenticalProjectionWithoutChangingSource() throws Exception {
        var old=new LinkedHashMap<String,Object>(Map.of("contract_version","local-catalog-import-v1","snapshot_id","synthetic-old-v1","books_path","LA-books","books_sha256",hash("LA-books"),"candidates_path","LA-candidates","candidates_sha256",hash("LA-candidates"),"selected_book_ids",List.of(a),"topic",mapping("LA","linear-algebra")));
        write("old-manifest",json.writeValueAsString(old));var original=legacy.importManifest(dir.resolve("old-manifest"));
        assertThatThrownBy(()->importer.importManifest(manifest())).hasMessageContaining("different content or provenance");
        manifest.put("accepted_projection_sources",List.of(Map.of("snapshot_id","synthetic-old-v1","candidates_sha256",hash("LA-candidates"))));
        importer.importManifest(manifest());
        assertThat(jdbc.queryForObject("select projection_id from backend.discovery_catalog_member where book_id=?",Long.class,original.bookIds().get(a))).isEqualTo(original.projectionIds().get(a));
        assertThat(jdbc.queryForObject("select source_artifact_version from backend.book_ranking_v2_projection",String.class)).isEqualTo("synthetic-old-v1");
        manifest.put("snapshot_id","synthetic-discovery-v2");
        write("LA-candidates",fixture.candidate(a).replace("matrix","vector"));la.put("candidates_sha256",hash("LA-candidates"));
        assertThatThrownBy(()->importer.importManifest(manifest())).hasMessageContaining("different content or provenance");
        assertThat(jdbc.queryForObject("select count(*) from backend.discovery_catalog_import",Integer.class)).isEqualTo(1);
    }
}
