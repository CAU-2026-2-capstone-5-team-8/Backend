package com.cau.capstone8.backend.book;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Activates a complete, versioned selection without deleting collected or user data. */
@Service
public class CatalogSelectionService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final JdbcTemplate jdbc;
    public CatalogSelectionService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Decision(String bookId, boolean included, String reason) {}
    public record Topic(String mlTopicId, List<String> rawSha256, String filteredBooksSha256,
                        List<Decision> decisions) {}
    public record Manifest(String contractVersion, String snapshotId, String sourceSnapshotId,
                           String sourceManifestSha256, String pipelineRevision, String pipelineCodeSha256,
                           List<Topic> topics) {}
    public record Result(String snapshotId, int included, int excluded, boolean replayed) {}

    @Transactional
    public Result activate(Path path) {
        try {
            require(Files.isRegularFile(path) && Files.size(path)<=5*1024*1024,"invalid selection file");
            byte[] bytes=Files.readAllBytes(path);
            Manifest m=JSON.readValue(bytes,Manifest.class);
            require("catalog-selection-v1".equals(m.contractVersion()),"unsupported selection contract");
            text(m.snapshotId()); text(m.sourceSnapshotId()); hash(m.sourceManifestSha256());
            require(m.pipelineRevision()!=null && m.pipelineRevision().matches("[0-9a-f]{40}"),"pipeline revision required");
            hash(m.pipelineCodeSha256());
            require(m.topics()!=null && !m.topics().isEmpty(),"complete topic decisions required");
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('local-catalog-import-v1'))",Object.class);
            var sources=jdbc.queryForList("select manifest_hash from backend.discovery_catalog_import where snapshot_id=?",String.class,m.sourceSnapshotId());
            require(sources.size()==1 && sources.getFirst().equals(m.sourceManifestSha256()),"source snapshot/hash mismatch");
            String digest=LocalCatalogImportService.hash(bytes);
            var previous=jdbc.queryForList("select manifest_hash from backend.catalog_selection_snapshot where snapshot_id=?",String.class,m.snapshotId());
            boolean replayed=!previous.isEmpty();
            require(!replayed || previous.getFirst().equals(digest),"snapshot has different immutable input");
            if (!replayed) jdbc.update("insert into backend.catalog_selection_snapshot(snapshot_id,manifest_hash,manifest) values (?,?,cast(? as jsonb))",
                    m.snapshotId(),digest,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            var seenTopics=new HashSet<Long>(); int included=0,excluded=0;
            for (Topic t:m.topics()) {
                text(t.mlTopicId()); hash(t.filteredBooksSha256());
                require(t.rawSha256()!=null && !t.rawSha256().isEmpty(),"raw provenance required");
                t.rawSha256().forEach(CatalogSelectionService::hash);
                var ids=jdbc.queryForList("select id from backend.topic where ml_topic_id=?",Long.class,t.mlTopicId());
                require(ids.size()==1 && seenTopics.add(ids.getFirst()),"unknown/duplicate topic");
                long topic=ids.getFirst();
                var expected=new LinkedHashMap<String,Long>();
                jdbc.query("select b.ml_book_id,b.id from backend.discovery_catalog_member d join backend.book b on b.id=d.book_id where d.snapshot_id=? and d.topic_id=?",
                        (org.springframework.jdbc.core.RowCallbackHandler)rs->expected.put(rs.getString(1),rs.getLong(2)),m.sourceSnapshotId(),topic);
                require(!expected.isEmpty(),"topic absent from source snapshot");
                require(t.decisions()!=null && t.decisions().size()==expected.size(),"incomplete topic decisions");
                if (!replayed) jdbc.update("insert into backend.catalog_selection_topic values (?,?)",m.snapshotId(),topic);
                for (Decision d:t.decisions()) {
                    text(d.bookId());
                    require((d.included()?"provider_filter_pass":"provider_filter_rejected").equals(d.reason()),"invalid decision reason");
                    Long book=expected.remove(d.bookId());
                    require(book!=null,"unknown/duplicate book decision");
                    if (!replayed) jdbc.update("insert into backend.catalog_selection_member values (?,?,?,?,?)",m.snapshotId(),topic,book,d.included(),d.reason());
                    if (d.included()) included++; else excluded++;
                }
                require(expected.isEmpty(),"incomplete topic decisions");
                jdbc.update("insert into backend.catalog_selection_current(topic_id,snapshot_id) values (?,?) on conflict(topic_id) do update set snapshot_id=excluded.snapshot_id",topic,m.snapshotId());
            }
            var sourceTopics=new HashSet<>(jdbc.queryForList("select distinct topic_id from backend.discovery_catalog_member where snapshot_id=?",Long.class,m.sourceSnapshotId()));
            require(seenTopics.equals(sourceTopics),"all source topics must be selected atomically");
            return new Result(m.snapshotId(),included,excluded,replayed);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("cannot read catalog selection",e); }
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> current() {
        return jdbc.queryForList("""
                select t.ml_topic_id as "topic", c.snapshot_id as "snapshotId",
                       s.manifest_hash as "manifestHash", s.manifest->>'pipeline_revision' as "pipelineRevision",
                       count(m.book_id) filter(where m.included) as "included",
                       count(m.book_id) filter(where not m.included) as "excluded"
                from backend.catalog_selection_current c join backend.topic t on t.id=c.topic_id
                join backend.catalog_selection_snapshot s on s.snapshot_id=c.snapshot_id
                join backend.catalog_selection_member m on m.snapshot_id=c.snapshot_id and m.topic_id=c.topic_id
                group by t.ml_topic_id,c.snapshot_id,s.manifest_hash,s.manifest order by t.ml_topic_id
                """);
    }
    private static void require(boolean value,String message) { if(!value) throw new IllegalArgumentException(message); }
    private static void text(String value) { require(value!=null && !value.isBlank() && value.length()<=200,"invalid identifier"); }
    private static void hash(String value) { require(value!=null && value.matches("sha256:[0-9a-f]{64}"),"invalid hash"); }
}
