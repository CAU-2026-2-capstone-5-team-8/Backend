package com.cau.capstone8.backend.book;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Import all canonical metadata, with projections supplied only for explicitly analyzed books. */
@Service
public class DiscoveryCatalogImportService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final JdbcTemplate jdbc;
    private final LocalCatalogImportService catalog;

    public DiscoveryCatalogImportService(JdbcTemplate jdbc, LocalCatalogImportService catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public record Coverage(String bookId, int tocEntryCount, int descriptionCount, int sourceCount) {}
    public record Batch(LocalCatalogImportService.TopicMapping topic,
                        String booksPath, String booksSha256, String evidencePath, String evidenceSha256,
                        String candidatesPath, String candidatesSha256, Map<String,String> canonicalHashes) {}
    public record Manifest(String contractVersion, String snapshotId, List<Batch> topics,
                           List<LocalCatalogImportService.ProjectionSource> acceptedProjectionSources) {}
    public record TopicResult(long topicId, int bookCount, int tocBookCount, int projectionCount,
                              int conceptBookCount) {}
    public record Result(String snapshotId, Map<String,TopicResult> topics, int bookCount,
                         int tocBookCount, int projectionCount, boolean replayed) {}

    @Transactional
    public Result importManifest(Path path) {
        try {
            byte[] manifestBytes = read(path);
            Manifest manifest = JSON.readValue(manifestBytes, Manifest.class);
            require("discovery-catalog-import-v1".equals(manifest.contractVersion()), "unsupported discovery contract");
            text(manifest.snapshotId(),120);
            require(manifest.topics()!=null && !manifest.topics().isEmpty(), "explicit topic batches required");
            require(manifest.acceptedProjectionSources()!=null, "explicit accepted projection sources required");
            var sourceIds = new HashSet<String>();
            for (var source : manifest.acceptedProjectionSources()) {
                text(source.snapshotId(),120); hash(source.candidatesSha256());
                require(sourceIds.add(source.snapshotId()), "duplicate accepted source");
            }
            // Both local import entry points share the same writer lock.
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('local-catalog-import-v1'))",Object.class);
            String manifestHash = LocalCatalogImportService.hash(manifestBytes);
            var previous = jdbc.queryForList("select manifest_hash from backend.discovery_catalog_import where snapshot_id=?",
                    manifest.snapshotId());
            boolean replayed = !previous.isEmpty();
            if (replayed) require(manifestHash.equals(previous.getFirst().get("manifest_hash")),
                    "snapshot_id already has different immutable input");
            else jdbc.update("insert into backend.discovery_catalog_import(snapshot_id,manifest_hash,manifest) values (?,?,cast(? as jsonb))",
                    manifest.snapshotId(),manifestHash,new String(manifestBytes,java.nio.charset.StandardCharsets.UTF_8));
            Path base = path.toAbsolutePath().getParent();
            var topicIds = new HashSet<String>();
            var bookIds = new HashSet<String>();
            var results = new LinkedHashMap<String,TopicResult>();
            int totalToc=0, totalProjections=0;
            for (Batch batch : manifest.topics()) {
                require(batch.topic()!=null, "topic mapping required");
                var t = batch.topic();
                text(t.code(),80); text(t.name(),120); text(t.parentCode(),80);
                text(t.parentName(),120); text(t.mlTopicId(),120);
                require(!t.code().equals(t.parentCode()), "topic cannot be its own parent");
                require(topicIds.add(t.mlTopicId()), "duplicate topic batch");
                require(batch.canonicalHashes()!=null && batch.canonicalHashes().keySet().equals(
                        java.util.Set.of("books.jsonl","documents.jsonl","toc.jsonl","sources.jsonl")),
                        "four canonical source hashes required");
                batch.canonicalHashes().values().forEach(DiscoveryCatalogImportService::hash);
                require(batch.booksSha256().equals(batch.canonicalHashes().get("books.jsonl")),
                        "canonical books hash differs from import bytes");
                var books = rows(base,batch.booksPath(),batch.booksSha256(),LocalCatalogImportService.CanonicalBook.class);
                var evidence = rows(base,batch.evidencePath(),batch.evidenceSha256(),Coverage.class);
                var byBook = new LinkedHashMap<String,Coverage>();
                for (Coverage c : evidence) {
                    text(c.bookId(),200);
                    require(c.tocEntryCount()>=0 && c.descriptionCount()>=0 && c.sourceCount()>=1,
                            "invalid evidence counts");
                    require(byBook.putIfAbsent(c.bookId(),c)==null, "duplicate evidence book_id");
                }
                var candidates = new LinkedHashMap<String,LocalCatalogImportService.Candidate>();
                if (batch.candidatesPath()==null) require(batch.candidatesSha256()==null, "unexpected candidates hash");
                else for (var c : rows(base,batch.candidatesPath(),batch.candidatesSha256(),LocalCatalogImportService.Candidate.class)) {
                    text(c.bookId(),200);
                    require(candidates.putIfAbsent(c.bookId(),c)==null, "duplicate candidate book_id");
                }
                long parent = catalog.topic(t.parentCode(),t.parentName(),null,null);
                long topic = catalog.topic(t.code(),t.name(),parent,t.mlTopicId());
                int tocCount=0, conceptCount=0;
                for (var b : books) {
                    LocalCatalogImportService.validateBook(b,t.mlTopicId());
                    require(bookIds.add(b.bookId()), "duplicate canonical book across batches");
                    Coverage coverage = byBook.remove(b.bookId());
                    require(coverage!=null, "canonical book missing evidence row");
                    long bookId = catalog.book(b);
                    catalog.link(bookId,topic);
                    var candidate = candidates.remove(b.bookId());
                    Long projectionId = null;
                    if (candidate!=null) {
                        require(coverage.tocEntryCount()>0, "projection in this handoff requires TOC evidence");
                        LocalCatalogImportService.validate(b,candidate,t.mlTopicId());
                        projectionId = catalog.projection(bookId,topic,candidate,manifest.snapshotId(),
                                batch.candidatesSha256(),manifest.acceptedProjectionSources());
                        totalProjections++;
                        if (!candidate.coveredConcepts().isEmpty()) conceptCount++;
                    }
                    if (coverage.tocEntryCount()>0) tocCount++;
                    if (!replayed) jdbc.update("""
                            insert into backend.discovery_catalog_member(snapshot_id,book_id,topic_id,projection_id,
                                toc_entry_count,description_count,source_count,books_hash,evidence_hash)
                            values (?,?,?,?,?,?,?,?,?)
                            """,manifest.snapshotId(),bookId,topic,projectionId,coverage.tocEntryCount(),
                            coverage.descriptionCount(),coverage.sourceCount(),batch.booksSha256(),batch.evidenceSha256());
                    else {
                        var members = jdbc.queryForList("""
                                select projection_id,toc_entry_count,description_count,source_count,books_hash,evidence_hash
                                from backend.discovery_catalog_member where snapshot_id=? and book_id=? and topic_id=?
                                """,manifest.snapshotId(),bookId,topic);
                        require(members.size()==1, "missing immutable discovery member");
                        var member = members.getFirst();
                        require(java.util.Objects.equals(projectionId,member.get("projection_id"))
                                && coverage.tocEntryCount()==((Number)member.get("toc_entry_count")).intValue()
                                && coverage.descriptionCount()==((Number)member.get("description_count")).intValue()
                                && coverage.sourceCount()==((Number)member.get("source_count")).intValue()
                                && batch.booksSha256().equals(member.get("books_hash"))
                                && batch.evidenceSha256().equals(member.get("evidence_hash")),
                                "stored discovery member differs from immutable input");
                    }
                }
                require(byBook.isEmpty() && candidates.isEmpty(), "orphan evidence/candidate book_id");
                totalToc+=tocCount;
                results.put(t.mlTopicId(),new TopicResult(topic,books.size(),tocCount,
                        batch.candidatesPath()==null?0:jdbc.queryForObject(
                                "select count(*) from backend.discovery_catalog_member where snapshot_id=? and topic_id=? and projection_id is not null",
                                Integer.class,manifest.snapshotId(),topic),conceptCount));
            }
            require(jdbc.queryForObject("select count(*) from backend.discovery_catalog_member where snapshot_id=?",
                    Integer.class,manifest.snapshotId())==bookIds.size(), "discovery member count differs");
            return new Result(manifest.snapshotId(),results,bookIds.size(),totalToc,totalProjections,replayed);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("discovery handoff could not be read",e); }
    }

    private static <T> List<T> rows(Path base,String name,String expected,Class<T> type) throws Exception {
        text(name,4096); hash(expected);
        byte[] bytes = read(base.resolve(name));
        require(LocalCatalogImportService.hash(bytes).equals(expected), "input SHA-256 mismatch: "+name);
        var result = new java.util.ArrayList<T>();
        for (String line : new String(bytes,java.nio.charset.StandardCharsets.UTF_8).lines().toList())
            if (!line.isBlank()) result.add(JSON.readValue(line,type));
        require(!result.isEmpty(), "empty JSONL: "+name);
        return result;
    }
    private static byte[] read(Path p) throws Exception {
        require(Files.isRegularFile(p) && Files.size(p)<=20*1024*1024, "missing or oversized discovery input: "+p);
        return Files.readAllBytes(p);
    }
    private static void hash(String value) { require(value!=null && value.matches("sha256:[0-9a-f]{64}"),"invalid input hash"); }
    private static void text(String value,int max) { require(value!=null && !value.isBlank() && value.length()<=max,"invalid discovery text"); }
    private static void require(boolean ok,String message) { if(!ok) throw new IllegalArgumentException(message); }
}
