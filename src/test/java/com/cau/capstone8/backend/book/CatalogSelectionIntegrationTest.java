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
class CatalogSelectionIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11");
    @Autowired CatalogSelectionService selection;
    @Autowired BookService books;
    @Autowired CatalogSummaryService summary;
    @Autowired BookRankingV2ProjectionRepository projections;
    @Autowired BookFeatureRepository features;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path dir;
    final JsonMapper json=new JsonMapper();
    final String hash="sha256:"+"a".repeat(64);
    Map<String,Object> manifest;
    Map<String,Object> topic;
    Path write() throws Exception {
        Path p=dir.resolve("selection.json");Files.writeString(p,json.writeValueAsString(manifest));return p;
    }
    Map<String,Object> decision(String id,boolean included) {
        return Map.of("book_id",id,"included",included,"reason",included?"provider_filter_pass":"provider_filter_rejected");
    }
    @BeforeEach void setup() {
        jdbc.execute("truncate backend.book,backend.topic,backend.discovery_catalog_import,backend.catalog_selection_snapshot cascade");
        jdbc.update("insert into backend.topic(id,code,name,ml_topic_id) values (8001,'SEL','Synthetic','linear-algebra')");
        jdbc.update("insert into backend.discovery_catalog_import values ('source',?, '{}'::jsonb,now())",hash);
        for(int id:List.of(8101,8102)) {
            jdbc.update("insert into backend.book(id,title,author,description,ml_book_id) values (?,'Synthetic','Synthetic','Synthetic',?)",id,"book:"+id);
            jdbc.update("insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight) values (?,8001,true,1)",id);
            jdbc.update("insert into backend.discovery_catalog_member(snapshot_id,book_id,topic_id,toc_entry_count,description_count,source_count,books_hash,evidence_hash) values ('source',?,8001,2,1,1,?,?)",id,hash,hash);
            jdbc.update("""
                    insert into backend.book_ranking_v2_projection(book_id,topic_id,version,active,topic_distribution,
                    covered_concepts,prerequisite_concepts,source_artifact_version,source_artifact_hash,config_version,config_hash)
                    values (?,8001,'synthetic',true,'{"linear-algebra":1}', '[{"concept":"matrix","weight":1}]', '[]','source',?,'synthetic',?)
                    """,id,hash,hash);
            jdbc.update("insert into backend.book_feature(book_id,topic_id,version,active,vocabulary,knowledge,comprehension,topic_relevance) values (?,8001,'synthetic',true,0.5,0.5,0.5,1)",id);
        }
        topic=new LinkedHashMap<>(Map.of("ml_topic_id","linear-algebra","raw_sha256",List.of(hash),"filtered_books_sha256",hash,
                "decisions",List.of(decision("book:8101",true),decision("book:8102",false))));
        manifest=new LinkedHashMap<>(Map.of("contract_version","catalog-selection-v1","snapshot_id","selection-v1",
                "source_snapshot_id","source","source_manifest_sha256",hash,"pipeline_revision","b".repeat(40),
                "pipeline_code_sha256",hash,"topics",List.of(topic)));
    }
    @Test void filtersPaginationCountsAndAllNewCandidatePathsWithoutDeletingEvidence() throws Exception {
        assertThat(books.list(null,0,20).totalElements()).isEqualTo(2);
        assertThat(selection.activate(write()).included()).isEqualTo(1);
        assertThat(books.list(null,0,1).totalElements()).isEqualTo(1);
        assertThat(books.list(8001L,1,1).content()).isEmpty();
        assertThat(summary.summary().bookCount()).isEqualTo(1);
        assertThat(summary.summary().tocBookCount()).isEqualTo(1);
        assertThat(summary.summary().rankingCandidateCount()).isEqualTo(1);
        assertThat(summary.summary().conceptBookCount()).isEqualTo(1);
        assertThat(projections.findByTopicIdAndActiveTrueOrderByBookId(8001)).extracting(BookRankingV2Projection::getBookId).containsExactly(8101L);
        assertThat(projections.findByBookIdAndTopicIdAndActiveTrue(8102,8001)).isEmpty();
        assertThat(features.findByTopicIdAndActiveTrueOrderByBookId(8001)).hasSize(1);
        assertThat(features.findByBookIdAndTopicIdAndActiveTrue(8102,8001)).isEmpty();
        assertThat(books.detail(8102).id()).isEqualTo(8102L);
        assertThat(jdbc.queryForObject("select count(*) from backend.book",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from backend.book_ranking_v2_projection where active",Integer.class)).isEqualTo(2);
        assertThat(selection.current()).hasSize(1);
        assertThat(new CatalogSelectionService(jdbc).current()).isEqualTo(selection.current());
    }
    @Test void replayAndExplicitNewVersionCanRestoreSelectionWithoutChangingIds() throws Exception {
        selection.activate(write());assertThat(selection.activate(write()).replayed()).isTrue();
        manifest.put("snapshot_id","selection-v2");
        topic.put("decisions",List.of(decision("book:8101",true),decision("book:8102",true)));
        selection.activate(write());assertThat(books.list(null,0,20).totalElements()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from backend.catalog_selection_snapshot",Integer.class)).isEqualTo(2);
    }
    @Test void changedImmutableInputAndIncompleteDecisionsLeaveCurrentSelectionUntouched() throws Exception {
        selection.activate(write());
        topic.put("decisions",List.of(decision("book:8101",true)));
        assertThatThrownBy(()->selection.activate(write())).hasMessageContaining("immutable");
        manifest.put("snapshot_id","invalid-v2");
        assertThatThrownBy(()->selection.activate(write())).hasMessageContaining("incomplete");
        assertThat(jdbc.queryForObject("select snapshot_id from backend.catalog_selection_current",String.class)).isEqualTo("selection-v1");
        assertThat(jdbc.queryForObject("select count(*) from backend.catalog_selection_snapshot",Integer.class)).isEqualTo(1);
    }
    @Test void missingTopicLateInManifestRollsBackEarlierActivation() throws Exception {
        selection.activate(write());manifest.put("snapshot_id","bad");
        var other=new LinkedHashMap<>(topic);other.put("ml_topic_id","unknown");manifest.put("topics",List.of(topic,other));
        assertThatThrownBy(()->selection.activate(write())).hasMessageContaining("topic");
        assertThat(jdbc.queryForObject("select snapshot_id from backend.catalog_selection_current",String.class)).isEqualTo("selection-v1");
    }
    @Test void rejectsWrongSourceHashDuplicateDecisionsAndUnlistedNewBooksStayHidden() throws Exception {
        manifest.put("source_manifest_sha256","sha256:"+"c".repeat(64));
        assertThatThrownBy(()->selection.activate(write())).hasMessageContaining("source");
        manifest.put("source_manifest_sha256",hash);topic.put("decisions",List.of(decision("book:8101",true),decision("book:8101",false)));
        assertThatThrownBy(()->selection.activate(write())).hasMessageContaining("duplicate");
        topic.put("decisions",List.of(decision("book:8101",true),decision("book:8102",false)));selection.activate(write());
        jdbc.update("insert into backend.book(id,title,author,description,ml_book_id) values (8103,'Later','Synthetic','Synthetic','book:8103')");
        jdbc.update("insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight) values (8103,8001,true,1)");
        assertThat(books.list(8001L,0,20).totalElements()).isEqualTo(1);
    }
}
